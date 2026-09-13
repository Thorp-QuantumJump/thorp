package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsSourceType;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 묶음 품질을 고정 검증 표본으로 측정한다(spec SC-002).
 *
 * <p>완료 기준이 둘이다. <b>서로 다른 사건을 한 묶음으로 잘못 합친 사례가 0건</b>이고, <b>같은 사건이 여러 묶음으로 나뉜 비율이 10% 이하</b>다.
 *
 * <p>둘을 따로 재는 이유가 있다. 잘못 합치면 사용자가 관계없는 보도를 한 사건으로 읽는다. 나뉘는 것은 같은 사건이 두 번 보이는 정도라 덜 나쁘다. 그래서 잘못 합치기는
 * 0건, 나뉘기는 비율로 둔다.
 *
 * <p>표본은 버전 관리되는 파일이다. 규칙을 바꾸면 표본도 새 버전으로 만들고 기존 판정을 덮어쓰지 않는다.
 */
class NewsGroupingQualityTest {

    /** 같은 사건이 여러 묶음으로 나뉜 비율의 상한. */
    private static final double MAX_SPLIT_RATE = 0.10;

    private final NewsGroupingPolicy policy = new NewsGroupingPolicy();

    private record Sample(String expectedEvent, NewsGroupingPolicy.Candidate candidate) {}

    private static JsonNode fixture() {
        try (InputStream in =
                NewsGroupingQualityTest.class.getResourceAsStream("/news/grouping-quality.json")) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("표본을 읽을 수 없다", e);
        }
    }

    private static List<Sample> samples() {
        JsonNode root = fixture();
        Instant collectedAt = Instant.parse(root.get("collectedAt").asText());
        List<Sample> samples = new ArrayList<>();

        for (JsonNode node : root.get("articles")) {
            CanonicalNewsUrl canonical = CanonicalNewsUrl.normalize(node.get("url").asText());
            Instant publishedAt = Instant.parse(node.get("publishedAt").asText());
            PublicationTimeStatus status =
                    NewsArticle.classifyPublicationTime(publishedAt, collectedAt);

            NewsArticle article =
                    NewsArticle.collected(
                            NewsSourceType.RSS,
                            node.get("publisherId").asLong(),
                            null,
                            node.get("url").asText(),
                            canonical,
                            node.get("title").asText(),
                            "ko",
                            status == PublicationTimeStatus.VERIFIED ? publishedAt : null,
                            status,
                            collectedAt,
                            null,
                            canonical.value(),
                            "hash",
                            "fixture",
                            true);

            Set<String> topics = new LinkedHashSet<>();
            node.get("topics").forEach(topic -> topics.add(topic.asText()));
            samples.add(
                    new Sample(
                            node.get("expectedEvent").asText(),
                            new NewsGroupingPolicy.Candidate(article, topics)));
        }
        return samples;
    }

    @Test
    @DisplayName("서로 다른 사건이 한 묶음으로 합쳐진 사례가 0건이다")
    void 잘못된_병합이_없다() {
        List<Sample> samples = samples();
        Map<String, String> eventByUrl = new HashMap<>();
        samples.forEach(
                s ->
                        eventByUrl.put(
                                s.candidate().article().getCanonicalUrl().value(),
                                s.expectedEvent()));

        List<List<NewsGroupingPolicy.Candidate>> groups =
                policy.group(
                        samples.stream().map(Sample::candidate).toList(),
                        NewsGroupingPolicy.RULE_VERSION);

        List<Set<String>> mixed = new ArrayList<>();
        for (List<NewsGroupingPolicy.Candidate> group : groups) {
            Set<String> events = new LinkedHashSet<>();
            group.forEach(c -> events.add(eventByUrl.get(c.article().getCanonicalUrl().value())));
            if (events.size() > 1) {
                mixed.add(events);
            }
        }

        assertThat(mixed).as("한 묶음에 사건이 둘 이상 섞였다").isEmpty();
    }

    @Test
    @DisplayName("같은 사건이 나뉜 비율이 10% 이하다")
    void 분리율이_한도_이하다() {
        List<Sample> samples = samples();
        Map<String, String> eventByUrl = new HashMap<>();
        samples.forEach(
                s ->
                        eventByUrl.put(
                                s.candidate().article().getCanonicalUrl().value(),
                                s.expectedEvent()));

        List<List<NewsGroupingPolicy.Candidate>> groups =
                policy.group(
                        samples.stream().map(Sample::candidate).toList(),
                        NewsGroupingPolicy.RULE_VERSION);

        // 사건마다 몇 개의 묶음으로 흩어졌는지 센다. 하나면 정상, 둘 이상이면 그만큼 나뉜 것이다.
        Map<String, Set<Integer>> groupsByEvent = new HashMap<>();
        for (int index = 0; index < groups.size(); index++) {
            for (NewsGroupingPolicy.Candidate candidate : groups.get(index)) {
                String event = eventByUrl.get(candidate.article().getCanonicalUrl().value());
                groupsByEvent.computeIfAbsent(event, key -> new HashSet<>()).add(index);
            }
        }

        int events = groupsByEvent.size();
        int extraGroups = groupsByEvent.values().stream().mapToInt(set -> set.size() - 1).sum();
        double splitRate = (double) extraGroups / events;

        assertThat(splitRate)
                .as("사건 %d개 중 초과 묶음 %d개", events, extraGroups)
                .isLessThanOrEqualTo(MAX_SPLIT_RATE);
    }

    @Test
    @DisplayName("48시간을 넘긴 같은 제목은 다른 사건으로 남는다")
    void 오래된_보도는_분리된다() {
        List<Sample> samples = samples();

        List<List<NewsGroupingPolicy.Candidate>> groups =
                policy.group(
                        samples.stream().map(Sample::candidate).toList(),
                        NewsGroupingPolicy.RULE_VERSION);

        boolean oldOneIsAlone =
                groups.stream()
                        .anyMatch(
                                group ->
                                        group.size() == 1
                                                && group.get(0)
                                                        .article()
                                                        .getCanonicalUrl()
                                                        .value()
                                                        .endsWith("/chip/old"));

        assertThat(oldOneIsAlone).as("사흘 전 같은 제목 보도는 따로 남아야 한다").isTrue();
    }

    @Test
    @DisplayName("표본과 규칙 버전이 함께 기록돼 있다")
    void 표본_버전() {
        JsonNode root = fixture();

        assertThat(root.get("fixtureVersion").asText()).isEqualTo("grouping-quality-v1");
        assertThat(root.get("groupingRuleVersion").asText())
                .isEqualTo(NewsGroupingPolicy.RULE_VERSION);
    }
}
