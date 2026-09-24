package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.news.infrastructure.config.NewsLinkRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 제목 키워드 섹터 연결을 사람이 검수할 정답 표본으로 잰다(spec FR-010, S15P21E205-437).
 *
 * <p>정답은 LLM이 제목만 읽고 1차로 달았다. 2026-09-25 뉴스 전용 섹터를 더하며 이 표본을 보고 일반 키워드를 보탰으므로 이 값은 <b>개발 표본
 * 측정</b>이다(원칙 VII). 미사용 평가는 새로 수집한 기사로 다시 한다.
 *
 * <p><b>측정값을 파일에 적어 두고 그 값과 같은지 본다.</b> 키워드나 정답을 바꾸면 이 시험이 깨지고, 바꾼 사람은 새 값을 파일에 적어야 한다. 정밀도·재현율이
 * 조용히 달라지지 않게 하려는 것이다. 정밀도에는 따로 회귀 하한을 둔다 — 제목 한 단어로 잘못 연결된 기사는 사용자가 "이 섹터 뉴스"로 읽는다.
 */
class NewsSectorKeywordEvaluationTest {

    /**
     * 정밀도의 회귀 하한.
     *
     * <p>2026-09-25 뉴스 전용 섹터를 더한 뒤의 측정(0.732)을 보고 정한 값이다. "이만큼이면 충분하다"는 기준이 아니라, 규칙을 고치다 잘못 연결이
     * 늘어나는 것을 막는 하한으로만 쓴다. 뉴스 전용 섹터는 시황·거시처럼 한 기사가 여러 섹터에 걸리기 쉬워 투자 대상 8개만 쟀을 때(0.947)보다 낮다.
     */
    private static final double MIN_PRECISION = 0.70;

    private static JsonNode json(String resource) {
        try (InputStream in = NewsSectorKeywordEvaluationTest.class.getResourceAsStream(resource)) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(resource, e);
        }
    }

    private static NewsLinkRegistry registry() {
        try (InputStream in = new ClassPathResource("news/news-link-v2.json").getInputStream()) {
            return new ObjectMapper()
                    .disable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(in, NewsLinkRegistry.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("정답 표본의 정밀도·재현율이 파일에 적은 측정값과 같고 정밀도 하한을 넘는다")
    void 정밀도_재현율() {
        NewsLinkRegistry registry = registry();
        JsonNode eval = json("/news/sector-link-eval-v1.json");
        JsonNode fixture = json("/news/" + eval.get("titleSource").asText());
        assertThat(eval.get("linkRuleVersion").asText()).isEqualTo(registry.version());

        Map<String, String> titleByUrl = new HashMap<>();
        fixture.get("articles")
                .forEach(a -> titleByUrl.put(a.get("url").asText(), a.get("title").asText()));

        int scored = 0;
        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        for (JsonNode label : eval.get("labels")) {
            String title = titleByUrl.get(label.get("url").asText());
            assertThat(title).as("정답이 가리키는 기사가 표본에 있다").isNotNull();
            if (label.get("ambiguous").asBoolean()) {
                continue;
            }
            scored++;
            Set<String> gold = new HashSet<>();
            label.get("sectors").forEach(s -> gold.add(s.asText()));
            Set<String> predicted = new HashSet<>();
            for (String topicKey : registry.matchTitle(title).keySet()) {
                registry.findTopic(topicKey).ifPresent(t -> predicted.addAll(t.sectorIds()));
            }
            assertThat(registry.knownSectorIds()).containsAll(gold);
            for (String sector : predicted) {
                if (gold.contains(sector)) {
                    truePositives++;
                } else {
                    falsePositives++;
                }
            }
            for (String sector : gold) {
                if (!predicted.contains(sector)) {
                    falseNegatives++;
                }
            }
        }

        JsonNode measured = eval.get("measured");
        double precision = (double) truePositives / (truePositives + falsePositives);
        double recall = (double) truePositives / (truePositives + falseNegatives);
        assertThat(List.of(scored, truePositives, falsePositives, falseNegatives))
                .as("키워드나 정답을 바꿨다면 measured 값을 새로 적는다")
                .containsExactly(
                        measured.get("scoredArticles").asInt(),
                        measured.get("truePositives").asInt(),
                        measured.get("falsePositives").asInt(),
                        measured.get("falseNegatives").asInt());
        assertThat(Math.round(precision * 1000) / 1000.0)
                .isEqualTo(measured.get("precision").asDouble());
        assertThat(Math.round(recall * 1000) / 1000.0).isEqualTo(measured.get("recall").asDouble());
        assertThat(precision).isGreaterThanOrEqualTo(MIN_PRECISION);
    }
}
