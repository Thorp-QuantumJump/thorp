package com.ssafy.thorp.news.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.news.application.port.GdeltNewsSource;
import com.ssafy.thorp.news.application.result.CollectedNewsItem;
import com.ssafy.thorp.news.application.result.NewsFetchResult;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.infrastructure.client.NewsHttpSecurityPolicy.SourceKind;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GDELT DOC 2.0 ArticleList로 해외 보도를 보완 수집한다(research Decision 3).
 *
 * <p>기간과 주제로 조회를 제한하고 장기 원천 전체를 적재하지 않는다(spec FR-013).
 *
 * <p><b>{@code seendate}를 기사 발행 시각으로 쓰지 않는다.</b> GDELT가 그 기사를 본 시각이지 매체가 발행한 시각이 아니다. 별도 필드로 두고 발행
 * 시각은 비워 둔다(spec FR-012).
 *
 * <p>받은 레코드 수가 요청한 상한과 같으면 구간을 다 받지 못했을 수 있다. 그 사실을 결과에 실어 호출부가 구간을 쪼개 다시 부르게 한다. 부분 수집을 완전 수집으로
 * 표현하지 않는다.
 */
@Component
public class GdeltDocNewsClient {

    /** JSON을 해석할 수 없다. 그 응답 전체를 격리한다. */
    public static final String MALFORMED_JSON = "MALFORMED_JSON";

    private static final String ENDPOINT = "https://api.gdeltproject.org/api/v2/doc/doc";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NewsHttpClient httpClient;
    private final NewsHttpSecurityPolicy policy;
    private final int maxRecords;

    public GdeltDocNewsClient(
            NewsHttpClient httpClient,
            NewsHttpSecurityPolicy policy,
            @Value("${thorp.news.gdelt.max-records:75}") int maxRecords) {
        this.httpClient = httpClient;
        this.policy = policy;
        this.maxRecords = maxRecords;
    }

    /** 한 구간을 조회한다. 구간 분할 판단은 호출부가 한다. */
    public NewsFetchResult fetch(GdeltNewsSource source) {
        URI url = policy.requireAllowedUrl(buildUrl(source), SourceKind.GDELT_DOC);
        NewsHttpResponse response = httpClient.get(url, SourceKind.GDELT_DOC, null, null);
        policy.requireAllowedContentType(response.contentType(), SourceKind.GDELT_DOC);

        JsonNode articles = readArticles(response.body());
        List<CollectedNewsItem> items = new ArrayList<>();
        for (JsonNode article : articles) {
            toItem(article, source.topicKeys()).ifPresent(items::add);
        }

        // 필터 전 원시 레코드 수로 판단한다. 걸러낸 뒤 세면 상한 도달을 놓친다.
        boolean capped = articles.size() >= maxRecords;
        return capped ? NewsFetchResult.capped(items) : NewsFetchResult.complete(items, null, null);
    }

    private String buildUrl(GdeltNewsSource source) {
        String query = buildQuery(source.topicKeys());
        return ENDPOINT
                + "?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&mode=ArticleList&format=json"
                + "&startdatetime="
                + GdeltTimestamps.format(source.windowStart())
                + "&enddatetime="
                + GdeltTimestamps.format(source.windowEnd())
                + "&maxrecords="
                + maxRecords;
    }

    /** 등록된 주제 키로 조회를 좁힌다. 주제가 없으면 국내 관련 기본 질의를 쓴다. */
    private static String buildQuery(List<String> topicKeys) {
        if (topicKeys.isEmpty()) {
            return "sourcecountry:southkorea";
        }
        return String.join(" OR ", topicKeys);
    }

    private JsonNode readArticles(byte[] body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode articles = root.path("articles");
            return articles.isArray() ? articles : MAPPER.createArrayNode();
        } catch (IOException e) {
            throw NewsHttpSecurityPolicy.reject(MALFORMED_JSON);
        }
    }

    /**
     * 제목과 주소가 모두 있어야 표시 후보가 된다. 없는 레코드는 그 항목만 제외한다.
     *
     * <p>GDELT 응답에는 분류 값이 없다. 대신 <b>조회에 쓴 주제</b>를 그 기사에 물려준다. 우리가 그 주제로 질의해 받은 결과이므로 연결 근거가 된다. 이
     * 값을 비워 두면 관련성 필터가 전량 거부해 보완 수집이 한 건도 저장되지 않는다.
     */
    private static java.util.Optional<CollectedNewsItem> toItem(
            JsonNode article, List<String> queriedTopicKeys) {
        String url = text(article, "url");
        String title = text(article, "title");
        if (url == null || title == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
                new CollectedNewsItem(
                        null,
                        url,
                        title,
                        toLanguageTag(text(article, "language")),
                        null,
                        GdeltTimestamps.parse(text(article, "seendate")).orElse(null),
                        queriedTopicKeys,
                        com.ssafy.thorp.news.domain.vo.NewsSourceType.GDELT_DOC));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * GDELT는 언어를 영문 이름으로 준다. 저장은 BCP 47 태그로 한다.
     *
     * <p>밝히지 않았거나 우리가 모르는 이름이면 영어로 단정하지 않고 미상으로 남긴다.
     */
    private static String toLanguageTag(String gdeltLanguage) {
        if (gdeltLanguage == null) {
            return NewsArticle.UNDETERMINED_LANGUAGE;
        }
        return switch (gdeltLanguage.toLowerCase(Locale.ROOT)) {
            case "korean" -> "ko";
            case "japanese" -> "ja";
            case "chinese" -> "zh";
            case "english" -> "en";
            default -> NewsArticle.UNDETERMINED_LANGUAGE;
        };
    }
}
