package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.news.infrastructure.config.NewsLinkRegistry;
import com.ssafy.thorp.news.infrastructure.config.RegistryNewsTopicCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 요약 문장의 언어 정책을 고정 표본으로 검증한다(spec FR-003·SC-006).
 *
 * <p>뉴스는 해석의 맥락이지 판단이 아니다. 요약에 전망·판정·투자 시사점·수익 원인 단정이 들어가면 화면이 진단에서 추천으로 넘어간다. 그 경계를 표본으로 고정해 규칙이
 * 바뀌어도 같은 기준으로 잰다.
 *
 * <p>근거가 부족한 입력에서는 문장을 만들지 않는 것도 함께 본다. 없는 내용을 지어내지 않기 위해서다(원칙 VI).
 */
class NewsSummaryLanguagePolicyTest {

    private static final NewsLinkRegistry LINKS =
            new NewsLinkRegistry(
                    "news-link-test",
                    List.of(
                            new NewsLinkRegistry.Topic(
                                    "semiconductor", "반도체", true, List.of("sector-semi")),
                            new NewsLinkRegistry.Topic("prices", "물가", true, List.of()),
                            new NewsLinkRegistry.Topic("exchange-rate", "환율", true, List.of())),
                    List.of());

    private final NewsSummaryPolicy policy =
            new NewsSummaryPolicy(new RegistryNewsTopicCatalog(LINKS));

    private static JsonNode fixture() {
        try (InputStream in =
                NewsSummaryLanguagePolicyTest.class.getResourceAsStream(
                        "/news/summary-policy.json")) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("표본을 읽을 수 없다", e);
        }
    }

    private static NewsSummaryPolicy.Input toInput(JsonNode node) {
        Set<String> topics = new LinkedHashSet<>();
        node.get("topics").forEach(topic -> topics.add(topic.asText()));
        return new NewsSummaryPolicy.Input(
                node.get("title").asText(),
                node.get("publisherName").asText(),
                topics,
                node.get("distinctPublisherCount").asInt());
    }

    @Test
    @DisplayName("만들어진 요약에 금지 표현이 하나도 없다")
    void 금지_표현이_없다() {
        JsonNode root = fixture();
        List<String> violations = new ArrayList<>();

        for (JsonNode caseNode : root.get("casesRequiringSummary")) {
            String summary = policy.summarize(toInput(caseNode)).orElseThrow();

            root.get("prohibitedExpressions")
                    .fields()
                    .forEachRemaining(
                            category ->
                                    category.getValue()
                                            .forEach(
                                                    expression -> {
                                                        if (summary.contains(expression.asText())) {
                                                            violations.add(
                                                                    caseNode.get("name").asText()
                                                                            + " / "
                                                                            + category.getKey()
                                                                            + " / "
                                                                            + expression.asText());
                                                        }
                                                    }));
        }

        assertThat(violations).as("요약에 전망·판정·투자 시사점·수익 원인 단정이 섞였다").isEmpty();
    }

    @Test
    @DisplayName("근거가 충분한 입력은 요약을 만든다")
    void 요약을_만든다() {
        for (JsonNode caseNode : fixture().get("casesRequiringSummary")) {
            Optional<String> summary = policy.summarize(toInput(caseNode));

            assertThat(summary).as(caseNode.get("name").asText()).isPresent();
            assertThat(summary.get())
                    .as("요약은 대표 제목을 그대로 담는다")
                    .contains(caseNode.get("title").asText());
        }
    }

    @Test
    @DisplayName("근거가 부족한 입력은 요약을 만들지 않는다")
    void 요약을_만들지_않는다() {
        for (JsonNode caseNode : fixture().get("casesRequiringExclusion")) {
            assertThat(policy.summarize(toInput(caseNode)))
                    .as(caseNode.get("name").asText())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("요약은 제목 밖의 사실을 더하지 않는다")
    void 제목_밖_사실을_더하지_않는다() {
        NewsSummaryPolicy.Input input =
                new NewsSummaryPolicy.Input("반도체 수출 지표가 발표됐다", "예시경제", Set.of("semiconductor"), 3);

        String summary = policy.summarize(input).orElseThrow();

        // 문장에 남을 수 있는 것은 제목, 매체명, 매체 수, 그리고 고정 서술뿐이다.
        String remainder =
                summary.replace("반도체 수출 지표가 발표됐다", "")
                        .replace("예시경제", "")
                        .replace("3", "")
                        .replaceAll("[\\s'등개매체가라고보도했습니다.]", "");

        assertThat(remainder).as("고정 서술 밖의 내용이 남았다").isEmpty();
    }

    @Test
    @DisplayName("표본 버전이 기록돼 있다")
    void 표본_버전() {
        assertThat(fixture().get("fixtureVersion").asText()).isEqualTo("summary-policy-v1");
    }
}
