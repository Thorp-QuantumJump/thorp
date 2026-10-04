package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.news.application.exception.NewsQueryException;
import com.ssafy.thorp.news.support.StubSectorCatalog;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 섹터 연결과 섹터 조회가 spec 001의 등록표를 따르는지(spec FR-005·FR-010, T024).
 *
 * <p>핵심은 하나다. <b>012는 섹터 식별자를 만들지 않는다.</b> 뉴스의 주제 등록표에 적힌 섹터도 001에 실재해야 연결된다.
 */
class NewsSectorLinkPolicyTest {

    private static final String REGISTERED = "SEMICONDUCTOR";
    private static final String STALE_ENTRY = "OLD_SECTOR";

    @Nested
    @DisplayName("기사 연결")
    class Linking {

        @Test
        @DisplayName("001에 없는 섹터는 연결하지 않는다")
        void 미등록_제외() {
            NewsSectorLinkPolicy policy =
                    new NewsSectorLinkPolicy(new StubSectorCatalog(Set.of(REGISTERED)));

            Set<String> proposed = new LinkedHashSet<>(List.of(REGISTERED, STALE_ENTRY));

            assertThat(policy.resolve(proposed))
                    .as("없는 섹터로 연결하면 어떤 조회에도 걸리지 않는 연결이 남는다")
                    .containsExactly(REGISTERED);
        }

        @Test
        @DisplayName("대응표가 아직 없으면 섹터를 하나도 연결하지 않는다")
        void 대응표_미발행() {
            NewsSectorLinkPolicy policy = new NewsSectorLinkPolicy(new StubSectorCatalog());

            assertThat(policy.resolve(Set.of(REGISTERED)))
                    .as("주제 연결만 남고 섹터는 대응표가 나온 뒤 다시 수집하면 붙는다")
                    .isEmpty();
        }

        @Test
        @DisplayName("제안이 비어 있으면 빈 결과다")
        void 제안_없음() {
            NewsSectorLinkPolicy policy =
                    new NewsSectorLinkPolicy(new StubSectorCatalog(Set.of(REGISTERED)));

            assertThat(policy.resolve(Set.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("채널 주제 보완 (spec FR-010)")
    class ChannelFallback {

        private static NewsRelevanceClassifier classifier() {
            return new NewsRelevanceClassifier(
                    new com.ssafy.thorp.news.infrastructure.config.RegistryNewsTopicCatalog(
                            new com.ssafy.thorp.news.infrastructure.config.NewsLinkRegistry(
                                    "news-link-v1",
                                    List.of(
                                            new com.ssafy.thorp.news.infrastructure.config
                                                    .NewsLinkRegistry.Topic(
                                                    "prices", "물가", true, List.of()),
                                            new com.ssafy.thorp.news.infrastructure.config
                                                    .NewsLinkRegistry.Topic(
                                                    "economy-general", "경제 일반", false, List.of())),
                                    List.of(
                                            new com.ssafy.thorp.news.infrastructure.config
                                                    .NewsLinkRegistry.CategoryMapping(
                                                    "물가", "prices")))));
        }

        @Test
        @DisplayName("기사가 분류 값을 주지 않으면 채널 주제를 쓴다")
        void 분류_없으면_채널() {
            NewsRelevanceClassifier.Relevance link =
                    classifier().classify(List.of(), false, List.of("economy-general"));

            assertThat(link.accepted())
                    .as("국내 경제 매체 대부분이 기사별 분류 값을 주지 않는다. 이 경로가 없으면 전량 제외된다")
                    .isTrue();
            assertThat(link.topicKeys()).containsExactly("economy-general");
            assertThat(link.evidenceByTopicKey())
                    .as("기사가 말한 것과 우리가 채널을 보고 판단한 것은 신뢰도가 다르다")
                    .containsEntry("economy-general", NewsRelevanceClassifier.CHANNEL_EVIDENCE);
        }

        @Test
        @DisplayName("기사가 분류 값을 주면 채널 주제는 쓰지 않는다")
        void 기사가_우선() {
            NewsRelevanceClassifier.Relevance link =
                    classifier().classify(List.of("물가"), false, List.of("economy-general"));

            assertThat(link.topicKeys()).containsExactly("prices");
            assertThat(link.evidenceByTopicKey())
                    .as("근거는 기사가 준 분류 값이다. 주제 키를 되풀이하면 아무것도 말해 주지 않는다")
                    .containsEntry("prices", "물가");
        }

        @Test
        @DisplayName("등록표에 없는 채널 주제는 쓰지 않는다")
        void 미등록_채널_주제() {
            NewsRelevanceClassifier.Relevance link =
                    classifier().classify(List.of(), false, List.of("없는주제"));

            assertThat(link.accepted()).isFalse();
        }

        @Test
        @DisplayName("채널 주제만으로는 해외 기사를 받지 않는다")
        void 해외_기사는_국내_연결_주제가_필요하다() {
            NewsRelevanceClassifier.Relevance link =
                    classifier().classify(List.of(), true, List.of("economy-general"));

            assertThat(link.accepted()).as("굵은 국내 분류는 해외 보도를 받아들이는 근거가 되지 못한다").isFalse();
        }
    }

    @Nested
    @DisplayName("조회 검증")
    class Querying {

        @Test
        @DisplayName("등록된 섹터는 통과한다")
        void 등록된_섹터() {
            NewsSectorQueryPolicy policy =
                    new NewsSectorQueryPolicy(new StubSectorCatalog(Set.of(REGISTERED)));

            assertThat(policy.canQuery(REGISTERED)).isTrue();
        }

        @Test
        @DisplayName("대응표가 있는데 없는 섹터를 물으면 거부한다")
        void 미등록_섹터() {
            NewsSectorQueryPolicy policy =
                    new NewsSectorQueryPolicy(new StubSectorCatalog(Set.of(REGISTERED)));

            assertThatThrownBy(() -> policy.canQuery(STALE_ENTRY))
                    .isInstanceOf(NewsQueryException.class)
                    .as("요청 값을 되비추면 가림 규칙이 응답에서 무너진다")
                    .hasMessageNotContaining(STALE_ENTRY);
        }

        @Test
        @DisplayName("대응표가 아직 없으면 거부가 아니라 빈 화면이다")
        void 대응표_미발행이면_빈_화면() {
            NewsSectorQueryPolicy policy = new NewsSectorQueryPolicy(new StubSectorCatalog());

            assertThat(policy.canQuery(REGISTERED)).as("001 적재 일정이 뉴스 화면을 오류로 만들면 안 된다").isFalse();
        }

        @Test
        @DisplayName("섹터를 지정하지 않으면 늘 통과한다")
        void 전체_조회() {
            NewsSectorQueryPolicy policy = new NewsSectorQueryPolicy(new StubSectorCatalog());

            assertThat(policy.canQuery(null)).isTrue();
        }

        @Test
        @DisplayName("섹터 여럿은 모두 등록돼 있으면 그대로, 하나라도 모르는 값이면 거부한다")
        void 섹터_여럿() {
            NewsSectorQueryPolicy policy =
                    new NewsSectorQueryPolicy(new StubSectorCatalog(Set.of(REGISTERED, "NEWS_IT")));

            assertThat(policy.queryableOf(List.of(REGISTERED, "NEWS_IT")))
                    .containsExactly(REGISTERED, "NEWS_IT");
            assertThatThrownBy(() -> policy.queryableOf(List.of(REGISTERED, STALE_ENTRY)))
                    .isInstanceOf(NewsQueryException.class)
                    .hasMessageNotContaining(STALE_ENTRY);
        }

        @Test
        @DisplayName("001 대응표가 아직 없으면 투자 대상 섹터는 빼고 뉴스 전용 섹터만 묻는다")
        void 대응표_미발행이면_뉴스_전용_섹터만() {
            NewsSectorQueryPolicy policy =
                    new NewsSectorQueryPolicy(StubSectorCatalog.newsOwnedOnly(Set.of("NEWS_IT")));

            assertThat(policy.queryableOf(List.of(REGISTERED, "NEWS_IT")))
                    .as("확인할 수 없는 섹터 하나 때문에 확인할 수 있는 섹터의 뉴스까지 비우지 않는다")
                    .containsExactly("NEWS_IT");
            assertThat(policy.queryableOf(List.of(REGISTERED))).as("물을 섹터가 없으면 빈 화면이다").isEmpty();
        }
    }
}
