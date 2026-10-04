package com.ssafy.thorp.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.news.application.port.NewsSourceClient;
import com.ssafy.thorp.news.infrastructure.client.NewsHttpSecurityPolicy;
import com.ssafy.thorp.news.infrastructure.config.NewsProperties;
import com.ssafy.thorp.news.infrastructure.config.NewsPublisherRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 비밀값 가림 정책을 시험으로 고정한다(spec FR-021, SC-009).
 *
 * <p>로그·저장된 오류 분류·운영 지표·API 응답 어디에도 URL query, 인증 정보, 뉴스원 접근 주소, 외부 응답 원문이 남으면 안 된다. 관례로만 지키면 한 번의
 * 실수로 자격 증명이 로그에 흘러 들어가고, 그 사실을 아무도 모른 채 배포된다.
 *
 * <p>여기서는 두 가지를 본다. 오류 분류 코드가 주소를 담지 않는다는 것과, 뉴스 도메인 코드가 로그에 위험한 값을 넣지 않는다는 것이다.
 */
class NewsRedactionPolicyTest {

    private static final Path NEWS_SOURCE_ROOT = Path.of("src/main/java/com/ssafy/thorp/news");

    /** 로그 인자에 이 이름이 보이면 주소나 자격 증명이 실릴 수 있다. */
    private static final List<String> FORBIDDEN_LOG_ARGUMENTS =
            List.of(
                    "feedUrl()",
                    "getRssUrl()",
                    "rssUrl",
                    "getOriginalUrl()",
                    "getCanonicalUrl()",
                    "response.body()",
                    "getQuery()",
                    "e.getMessage()",
                    "getMessage()");

    /** 로그 호출의 시작. 인자는 그 뒤 짝이 맞는 닫는 괄호까지 이어 붙여 본다. */
    private static final Pattern LOG_CALL =
            Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\s*\\(");

    private static NewsHttpSecurityPolicy policy() {
        return new NewsHttpSecurityPolicy(
                new NewsProperties.Http(
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        10_485_760L,
                        Set.of("application/xml"),
                        Set.of("application/json"),
                        List.of("api.gdeltproject.org")),
                new NewsPublisherRegistry("news-publishers-test", List.of()));
    }

    private static Stream<Path> newsSources() throws IOException {
        return Files.walk(NEWS_SOURCE_ROOT).filter(p -> p.toString().endsWith(".java"));
    }

    /**
     * {@code start}에서 시작한 호출을 짝이 맞는 닫는 괄호까지 한 줄로 이어 돌려준다. 문자열 안의 괄호는 세지 않는다.
     *
     * <p>줄 단위로 보면 포매터가 인자를 다음 줄로 넘긴 호출은 첫 줄 {@code log.warn(}만 보이고 인자는 한 번도 검사되지 않는다.
     */
    private static String callAt(String body, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return body.substring(start, i + 1).replaceAll("\\s+", " ");
            }
        }
        return body.substring(start).replaceAll("\\s+", " ");
    }

    @Nested
    @DisplayName("오류 분류 코드")
    class ErrorCategories {

        @Test
        @DisplayName("거부 메시지에 주소나 질의 문자열이 없다")
        void 주소_비노출() {
            String secretUrl = "https://evil.example.com/feed.xml?token=super-secret&key=abc";

            assertThatThrownBy(
                            () ->
                                    policy().requireAllowedUrl(
                                                    secretUrl,
                                                    NewsHttpSecurityPolicy.SourceKind.RSS))
                    .isInstanceOf(NewsSourceClient.NewsSourceException.class)
                    .hasMessageNotContaining("evil.example.com")
                    .hasMessageNotContaining("super-secret")
                    .hasMessageNotContaining("token")
                    .hasMessageNotContaining("abc");
        }

        @Test
        @DisplayName("분류 코드는 대문자와 밑줄로만 이뤄진 고정 값이다")
        void 고정_분류_코드() {
            List<String> codes =
                    List.of(
                            NewsHttpSecurityPolicy.INSECURE_SCHEME,
                            NewsHttpSecurityPolicy.HOST_NOT_ALLOWED,
                            NewsHttpSecurityPolicy.TOO_MANY_REDIRECTS,
                            NewsHttpSecurityPolicy.UNSUPPORTED_CONTENT_TYPE,
                            NewsHttpSecurityPolicy.MALFORMED_URL,
                            NewsHttpSecurityPolicy.RESPONSE_TOO_LARGE);

            assertThat(codes)
                    .allSatisfy(code -> assertThat(code).matches("^[A-Z_]+$"))
                    .as("분류 코드에 값이 섞이면 그 값이 그대로 저장되고 지표 label이 된다")
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("로그 호출")
    class LogStatements {

        @Test
        @DisplayName("뉴스 도메인 로그에 주소·응답 원문·예외 메시지를 넣지 않는다")
        void 위험한_로그_인자() throws IOException {
            List<String> violations = new ArrayList<>();
            int checked = 0;

            try (Stream<Path> sources = newsSources()) {
                for (Path source : sources.toList()) {
                    String body = Files.readString(source, StandardCharsets.UTF_8);
                    Matcher call = LOG_CALL.matcher(body);
                    while (call.find()) {
                        String statement = callAt(body, call.start());
                        checked++;
                        for (String forbidden : FORBIDDEN_LOG_ARGUMENTS) {
                            if (statement.contains(forbidden)) {
                                violations.add(
                                        source.getFileName()
                                                + " :: "
                                                + forbidden
                                                + " :: "
                                                + statement);
                            }
                        }
                    }
                }
            }

            assertThat(checked).as("로그 호출을 하나도 찾지 못하면 이 시험은 아무것도 보지 않는다").isPositive();
            assertThat(violations).as("로그에 실리면 운영 로그 수집기로 그대로 흘러 나간다").isEmpty();
        }

        @Test
        @DisplayName("뉴스 도메인 어디에도 접근 주소를 문자열로 박아 두지 않는다")
        void 하드코딩된_자격증명() throws IOException {
            List<String> violations = new ArrayList<>();

            try (Stream<Path> sources = newsSources()) {
                for (Path source : sources.toList()) {
                    String body = Files.readString(source, StandardCharsets.UTF_8);
                    for (String line : body.split("\\R")) {
                        String lower = line.toLowerCase(java.util.Locale.ROOT);
                        boolean assignsSecret =
                                (lower.contains("apikey")
                                                || lower.contains("api_key")
                                                || lower.contains("token")
                                                || lower.contains("password"))
                                        && lower.contains("=")
                                        && lower.contains("\"");
                        if (assignsSecret
                                && !lower.trim().startsWith("*")
                                && !lower.trim().startsWith("//")) {
                            violations.add(source.getFileName() + " :: " + line.trim());
                        }
                    }
                }
            }

            assertThat(violations).isEmpty();
        }
    }

    @Nested
    @DisplayName("저장되는 값")
    class StoredValues {

        @Test
        @DisplayName("수집 상태의 오류 분류 컬럼 길이가 분류 코드만 담을 크기다")
        void 오류_분류_길이() throws IOException {
            String migration =
                    Files.readString(
                            Path.of("src/main/resources/db/migration/V4__market_news_digest.sql"),
                            StandardCharsets.UTF_8);

            assertThat(migration)
                    .as("길면 주소나 응답 조각을 넣고 싶은 유혹이 생긴다")
                    .contains("error_category  VARCHAR(64)");
        }
    }
}
