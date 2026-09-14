package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.news.application.result.CollectedNewsItem;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.model.NewsDigest;
import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsSourceType;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import com.ssafy.thorp.news.infrastructure.config.NewsLinkRegistry;
import com.ssafy.thorp.news.infrastructure.config.RegistryNewsTopicCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 필터·중복·묶음·요약·정렬 규칙 v1(plan "필터·중복·묶음 규칙 v1", "요약·정렬 규칙 v1").
 *
 * <p>모두 결정론적이어야 한다. 같은 입력이면 항상 같은 결과가 나온다.
 */
class NewsProcessingRulesTest {

    private static final Instant COLLECTED = Instant.parse("2026-09-10T06:00:00Z");
    private static final String GROUPING_RULE = "news-grouping-v1";

    private static final NewsLinkRegistry LINKS =
            new NewsLinkRegistry(
                    "news-link-test",
                    List.of(
                            new NewsLinkRegistry.Topic(
                                    "semiconductor", "반도체", true, List.of("sector-semi")),
                            new NewsLinkRegistry.Topic("prices", "물가", false, List.of())),
                    List.of(
                            new NewsLinkRegistry.CategoryMapping("경제", "semiconductor"),
                            new NewsLinkRegistry.CategoryMapping("물가", "prices")));

    private static NewsArticle article(
            long publisherId, String url, String title, Instant publishedAt) {
        CanonicalNewsUrl canonical = CanonicalNewsUrl.normalize(url);
        PublicationTimeStatus status = NewsArticle.classifyPublicationTime(publishedAt, COLLECTED);
        return NewsArticle.collected(
                NewsSourceType.RSS,
                publisherId,
                null,
                url,
                canonical,
                title,
                "ko",
                status == PublicationTimeStatus.VERIFIED ? publishedAt : null,
                status,
                COLLECTED,
                null,
                canonical.value(),
                "hash",
                "run-1",
                true);
    }

    @Nested
    @DisplayName("URL 정규화와 중복 식별 (plan 규칙 3·4)")
    class Duplicates {

        private final NewsUrlNormalizer normalizer = new NewsUrlNormalizer();

        @Test
        @DisplayName("같은 원문의 변형은 하나로 합쳐진다")
        void 정규화() {
            assertThat(normalizer.canonicalize("https://n.example.com/a/?utm_source=x#top"))
                    .contains(CanonicalNewsUrl.normalize("https://N.example.com/a"));
        }

        @Test
        @DisplayName("정규화할 수 없는 주소는 빈 값이다")
        void 정규화_불가() {
            assertThat(normalizer.canonicalize("not a url")).isEmpty();
            assertThat(normalizer.canonicalize(null)).isEmpty();
        }

        @Test
        @DisplayName("주소가 있으면 canonical URL이 중복 열쇠다")
        void url_열쇠() {
            CollectedNewsItem item =
                    new CollectedNewsItem(
                            "guid-1",
                            "https://n.example.com/a",
                            "제목",
                            "ko",
                            null,
                            null,
                            List.of(),
                            NewsSourceType.RSS);

            assertThat(normalizer.duplicateKey(item, 1L)).contains("https://n.example.com/a");
        }

        @Test
        @DisplayName("주소가 없으면 매체와 항목 식별자로 판정한다")
        void guid_열쇠() {
            CollectedNewsItem item =
                    new CollectedNewsItem(
                            "guid-1", null, "제목", "ko", null, null, List.of(), NewsSourceType.RSS);

            Optional<String> forPublisherOne = normalizer.duplicateKey(item, 1L);
            Optional<String> forPublisherTwo = normalizer.duplicateKey(item, 2L);

            assertThat(forPublisherOne).isPresent();
            assertThat(forPublisherOne).as("같은 guid라도 매체가 다르면 별개다").isNotEqualTo(forPublisherTwo);
        }

        @Test
        @DisplayName("둘 다 없으면 제목·매체·시각 hash로 판정한다")
        void hash_열쇠() {
            Instant at = Instant.parse("2026-09-10T04:00:00Z");
            CollectedNewsItem item =
                    new CollectedNewsItem(
                            null, null, "제목", "ko", at, null, List.of(), NewsSourceType.RSS);

            assertThat(normalizer.duplicateKey(item, 1L)).isPresent();
            assertThat(normalizer.duplicateKey(item, 1L))
                    .as("같은 입력이면 같은 열쇠다")
                    .isEqualTo(normalizer.duplicateKey(item, 1L));
        }

        @Test
        @DisplayName("어떤 식별자도 만들 수 없으면 수집에서 제외한다")
        void 식별_불가() {
            CollectedNewsItem item =
                    new CollectedNewsItem(
                            null, null, "제목", "ko", null, null, List.of(), NewsSourceType.RSS);

            assertThat(normalizer.duplicateKey(item, 1L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("섹터·주제 연결 (plan 규칙 2)")
    class Relevance {

        private final NewsRelevanceClassifier classifier =
                new NewsRelevanceClassifier(new RegistryNewsTopicCatalog(LINKS));

        @Test
        @DisplayName("등록표 매핑을 통과한 분류 값만 주제가 된다")
        void 매핑_통과() {
            NewsRelevanceClassifier.Relevance relevance = classifier.classify(List.of("경제"), false);

            assertThat(relevance.accepted()).isTrue();
            assertThat(relevance.topicKeys()).containsExactly("semiconductor");
            assertThat(relevance.sectorIds()).containsExactly("sector-semi");
        }

        @Test
        @DisplayName("매핑되지 않은 분류 값은 연결 근거가 아니고 미등록으로 센다")
        void 미등록_분류() {
            NewsRelevanceClassifier.Relevance relevance =
                    classifier.classify(List.of("연예", "경제"), false);

            assertThat(relevance.topicKeys()).containsExactly("semiconductor");
            assertThat(relevance.unmappedCategories()).containsExactly("연예");
        }

        @Test
        @DisplayName("등록된 주제 키가 그대로 오면 매핑 없이 받는다")
        void 주제_키_직접_수용() {
            // GDELT는 원문 분류 값이 아니라 조회에 쓴 주제를 싣는다. 이 경로가 없으면 전량 폐기된다.
            NewsRelevanceClassifier.Relevance relevance =
                    classifier.classify(List.of("semiconductor"), true);

            assertThat(relevance.accepted()).isTrue();
            assertThat(relevance.topicKeys()).containsExactly("semiconductor");
            assertThat(relevance.unmappedCategories()).isEmpty();
        }

        @Test
        @DisplayName("등록되지 않은 주제 키는 받지 않는다")
        void 미등록_주제_키() {
            assertThat(classifier.classify(List.of("unknown-topic"), true).accepted()).isFalse();
        }

        @Test
        @DisplayName("연결된 주제가 없으면 수집 대상이 아니다")
        void 연결_없음() {
            assertThat(classifier.classify(List.of("연예"), false).accepted()).isFalse();
            assertThat(classifier.classify(List.of(), false).accepted()).isFalse();
        }

        @Test
        @DisplayName("해외 보도는 국내 연결 주제가 있어야 받는다")
        void 해외_보도() {
            assertThat(classifier.classify(List.of("경제"), true).accepted())
                    .as("semiconductor는 국내 연결 주제다")
                    .isTrue();
            assertThat(classifier.classify(List.of("물가"), true).accepted())
                    .as("prices는 국내 연결 주제가 아니다")
                    .isFalse();
            assertThat(classifier.classify(List.of("물가"), false).accepted())
                    .as("국내 보도는 국내 연결 여부를 따지지 않는다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("사건 묶음 (plan 규칙 5)")
    class Grouping {

        private final NewsGroupingPolicy policy = new NewsGroupingPolicy();

        private NewsGroupingPolicy.Candidate candidate(
                String url, String title, Instant at, String... topics) {
            return new NewsGroupingPolicy.Candidate(article(1L, url, title, at), Set.of(topics));
        }

        @Test
        @DisplayName("제목 token은 정규화 후 비교한다")
        void 제목_정규화() {
            assertThat(NewsGroupingPolicy.titleTokens("반도체  수출, 지표가 발표됐다!"))
                    .containsExactlyInAnyOrder("반도체", "수출", "지표가", "발표됐다");
            assertThat(NewsGroupingPolicy.titleTokens("Chip Exports  RISE"))
                    .containsExactlyInAnyOrder("chip", "exports", "rise");
        }

        @Test
        @DisplayName("세 조건을 모두 만족해야 합친다")
        void 세_조건() {
            Instant base = Instant.parse("2026-09-10T04:00:00Z");
            NewsGroupingPolicy.Candidate a =
                    candidate("https://a.example.com/1", "반도체 수출 지표 발표", base, "semiconductor");
            NewsGroupingPolicy.Candidate near =
                    candidate(
                            "https://b.example.com/1",
                            "반도체 수출 지표 발표 소식",
                            base.plus(Duration.ofHours(1)),
                            "semiconductor");

            assertThat(policy.group(List.of(a, near), GROUPING_RULE)).hasSize(1);
        }

        @Test
        @DisplayName("48시간을 넘기면 합치지 않는다")
        void 시간_경계() {
            Instant base = Instant.parse("2026-09-08T04:00:00Z");
            NewsGroupingPolicy.Candidate a =
                    candidate("https://a.example.com/1", "반도체 수출 지표 발표", base, "semiconductor");
            NewsGroupingPolicy.Candidate late =
                    candidate(
                            "https://b.example.com/1",
                            "반도체 수출 지표 발표",
                            base.plus(Duration.ofHours(48)).plusSeconds(1),
                            "semiconductor");

            assertThat(policy.group(List.of(a, late), GROUPING_RULE)).hasSize(2);
        }

        @Test
        @DisplayName("공통 주제가 없으면 합치지 않는다")
        void 주제_없음() {
            Instant base = Instant.parse("2026-09-10T04:00:00Z");
            NewsGroupingPolicy.Candidate a =
                    candidate("https://a.example.com/1", "반도체 수출 지표 발표", base, "semiconductor");
            NewsGroupingPolicy.Candidate other =
                    candidate("https://b.example.com/1", "반도체 수출 지표 발표", base, "prices");

            assertThat(policy.group(List.of(a, other), GROUPING_RULE)).hasSize(2);
        }

        @Test
        @DisplayName("제목 유사도가 0.60 미만이면 합치지 않는다")
        void 유사도_경계() {
            Instant base = Instant.parse("2026-09-10T04:00:00Z");
            NewsGroupingPolicy.Candidate a =
                    candidate("https://a.example.com/1", "반도체 수출 지표 발표", base, "semiconductor");
            NewsGroupingPolicy.Candidate different =
                    candidate(
                            "https://b.example.com/1", "환율 급등 원인 분석 보도 자료", base, "semiconductor");

            assertThat(policy.group(List.of(a, different), GROUPING_RULE)).hasSize(2);
        }

        @Test
        @DisplayName("A와 B, B와 C가 비슷해도 A와 C가 미달이면 연쇄 병합하지 않는다")
        void 전이적_병합_금지() {
            Instant base = Instant.parse("2026-09-10T04:00:00Z");
            NewsGroupingPolicy.Candidate a =
                    candidate("https://a.example.com/1", "반도체 수출 지표 발표", base, "semiconductor");
            NewsGroupingPolicy.Candidate b =
                    candidate("https://b.example.com/1", "반도체 수출 지표 공표 발표", base, "semiconductor");
            NewsGroupingPolicy.Candidate c =
                    candidate(
                            "https://c.example.com/1", "수출 지표 공표 확정 결과 정리", base, "semiconductor");

            List<List<NewsGroupingPolicy.Candidate>> groups =
                    policy.group(List.of(a, b, c), GROUPING_RULE);

            assertThat(groups).as("complete-link이므로 묶음의 모든 기사와 조건을 만족해야 한다").hasSize(2);
            assertThat(groups.stream().map(List::size)).containsExactlyInAnyOrder(2, 1);
        }

        @Test
        @DisplayName("입력 순서가 달라도 같은 묶음이 나온다")
        void 결정론() {
            Instant base = Instant.parse("2026-09-10T04:00:00Z");
            NewsGroupingPolicy.Candidate a =
                    candidate("https://a.example.com/1", "반도체 수출 지표 발표", base, "semiconductor");
            NewsGroupingPolicy.Candidate b =
                    candidate(
                            "https://b.example.com/1",
                            "반도체 수출 지표 발표 소식",
                            base.plus(Duration.ofMinutes(10)),
                            "semiconductor");

            assertThat(policy.group(List.of(a, b), GROUPING_RULE))
                    .hasSameSizeAs(policy.group(List.of(b, a), GROUPING_RULE));
        }
    }

    @Nested
    @DisplayName("사실 요약 (spec FR-003)")
    class Summary {

        private final NewsSummaryPolicy policy =
                new NewsSummaryPolicy(new RegistryNewsTopicCatalog(LINKS));

        @Test
        @DisplayName("제목·매체명·주제가 모두 있을 때만 한 문장을 만든다")
        void 요약_생성() {
            Optional<String> summary =
                    policy.summarize(
                            new NewsSummaryPolicy.Input(
                                    "반도체 수출 지표가 발표됐다", "예시경제", Set.of("semiconductor"), 2));

            assertThat(summary).isPresent();
            assertThat(summary.get()).contains("반도체 수출 지표가 발표됐다");
        }

        @Test
        @DisplayName("주제가 없으면 표시 대상에서 제외한다")
        void 주제_없음() {
            assertThat(policy.summarize(new NewsSummaryPolicy.Input("제목", "예시경제", Set.of(), 1)))
                    .isEmpty();
        }

        @Test
        @DisplayName("매체명이 없으면 표시 대상에서 제외한다")
        void 매체명_없음() {
            assertThat(
                            policy.summarize(
                                    new NewsSummaryPolicy.Input(
                                            "제목", " ", Set.of("semiconductor"), 1)))
                    .isEmpty();
        }

        @Test
        @DisplayName("전망·판정·투자 시사점을 덧붙이지 않는다")
        void 금지_표현() {
            String summary =
                    policy.summarize(
                                    new NewsSummaryPolicy.Input(
                                            "반도체 수출 지표가 발표됐다", "예시경제", Set.of("semiconductor"), 2))
                            .orElseThrow();

            assertThat(summary)
                    .doesNotContain("전망")
                    .doesNotContain("수혜")
                    .doesNotContain("호재")
                    .doesNotContain("기대")
                    .doesNotContain("때문");
        }

        @Test
        @DisplayName("같은 입력이면 같은 문장이 나온다")
        void 결정론() {
            NewsSummaryPolicy.Input input =
                    new NewsSummaryPolicy.Input("제목", "예시경제", Set.of("semiconductor"), 2);

            assertThat(policy.summarize(input)).isEqualTo(policy.summarize(input));
        }
    }

    @Nested
    @DisplayName("주요도 정렬 (plan 요약·정렬 규칙 v1)")
    class Importance {

        private final NewsImportancePolicy policy = new NewsImportancePolicy();

        private NewsDigest.Projection projection(
                String url, int publisherCount, Instant latestVerified, Instant collectedAt) {
            NewsArticle representative =
                    latestVerified == null
                            ? article(1L, url, "제목", null)
                            : article(1L, url, "제목", latestVerified);
            return new NewsDigest.Projection(
                    com.ssafy.thorp.news.domain.vo.NewsDigestId.of(
                            representative.getArticleId(), GROUPING_RULE),
                    representative,
                    "제목",
                    "요약",
                    List.of(representative),
                    publisherCount,
                    latestVerified,
                    latestVerified,
                    "news-importance-v1");
        }

        @Test
        @DisplayName("매체 수가 많은 묶음이 앞선다")
        void 매체_수() {
            NewsDigest.Projection few =
                    projection(
                            "https://a.example.com/1",
                            1,
                            Instant.parse("2026-09-10T05:00:00Z"),
                            COLLECTED);
            NewsDigest.Projection many =
                    projection(
                            "https://b.example.com/1",
                            3,
                            Instant.parse("2026-09-10T04:00:00Z"),
                            COLLECTED);

            assertThat(policy.order(List.of(few, many)))
                    .extracting(NewsDigest.Projection::distinctPublisherCount)
                    .containsExactly(3, 1);
        }

        @Test
        @DisplayName("발행 시각이 확인된 묶음이 미확인보다 앞선다")
        void 확인_여부() {
            NewsDigest.Projection unknown =
                    projection("https://a.example.com/1", 2, null, COLLECTED);
            NewsDigest.Projection verified =
                    projection(
                            "https://b.example.com/1",
                            2,
                            Instant.parse("2026-09-10T04:00:00Z"),
                            COLLECTED);

            assertThat(policy.order(List.of(unknown, verified)))
                    .extracting(NewsDigest.Projection::hasVerifiedPublishedAt)
                    .containsExactly(true, false);
        }

        @Test
        @DisplayName("같은 조건이면 최신 발행 시각이 앞선다")
        void 최근성() {
            NewsDigest.Projection older =
                    projection(
                            "https://a.example.com/1",
                            2,
                            Instant.parse("2026-09-10T03:00:00Z"),
                            COLLECTED);
            NewsDigest.Projection newer =
                    projection(
                            "https://b.example.com/1",
                            2,
                            Instant.parse("2026-09-10T05:00:00Z"),
                            COLLECTED);

            assertThat(policy.order(List.of(older, newer)))
                    .extracting(NewsDigest.Projection::latestPublishedAt)
                    .containsExactly(
                            Instant.parse("2026-09-10T05:00:00Z"),
                            Instant.parse("2026-09-10T03:00:00Z"));
        }

        @Test
        @DisplayName("모두 같으면 묶음 식별자 오름차순으로 안정된다")
        void 안정_정렬() {
            Instant same = Instant.parse("2026-09-10T04:00:00Z");
            NewsDigest.Projection one = projection("https://a.example.com/1", 2, same, COLLECTED);
            NewsDigest.Projection two = projection("https://b.example.com/1", 2, same, COLLECTED);

            List<String> forward =
                    policy.order(List.of(one, two)).stream()
                            .map(p -> p.digestId().value())
                            .toList();
            List<String> reversed =
                    policy.order(List.of(two, one)).stream()
                            .map(p -> p.digestId().value())
                            .toList();

            assertThat(forward).isEqualTo(reversed).isSorted();
        }
    }
}
