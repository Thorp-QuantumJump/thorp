package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.learning.domain.model.BadgeDefinition.Family;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 배지 31개(FR-024, data-model §12-4). */
class BadgeCatalogTest {

    private final BadgeCatalog catalog = BadgeCatalog.of(LearnerScopes.allStages().stages());

    @Test
    @DisplayName("31개 키가 갈래 순서와 사다리 순서대로 있다")
    void thirtyOneKeys() {
        assertThat(catalog.definitions())
                .extracting(definition -> definition.key().value())
                .containsExactly(
                        "level-2",
                        "level-3",
                        "level-4",
                        "level-5",
                        "level-6",
                        "level-7",
                        "level-8",
                        "read-1",
                        "read-5",
                        "read-10",
                        "read-20",
                        "read-27",
                        "correct-1",
                        "correct-10",
                        "correct-25",
                        "correct-50",
                        "perfect-1",
                        "perfect-5",
                        "perfect-15",
                        "stage-etf-market-basics",
                        "stage-returns-and-risk",
                        "stage-allocation-rules",
                        "stage-backtest-and-costs",
                        "stage-pitfalls-and-validation",
                        "stage-economy-and-regimes",
                        "terms-1",
                        "terms-10",
                        "terms-25",
                        "terms-all",
                        "persistence",
                        "exploration");
    }

    @Test
    @DisplayName("갈래는 일곱이고 응답 순서대로 이어진다")
    void familiesInOrder() {
        List<Family> families =
                catalog.definitions().stream().map(BadgeDefinition::family).distinct().toList();

        assertThat(families).containsExactly(Family.values());
    }

    @Test
    @DisplayName("이름에 투자 실력·수익·순위로 읽히는 낱말이 없다")
    void namesSayWhatWasLearned() {
        assertThat(catalog.definitions())
                .allSatisfy(
                        definition ->
                                assertThat(definition.name() + " " + definition.criterion())
                                        .doesNotContain("고수", "전문가", "상위", "수익", "순위"));
    }

    @Test
    @DisplayName("단계 완주 배지는 단계 번호로 부르고 그 단계를 가리킨다")
    void stageBadges() {
        BadgeDefinition third =
                catalog.definitions().stream()
                        .filter(
                                definition ->
                                        definition.key().value().equals("stage-allocation-rules"))
                        .findFirst()
                        .orElseThrow();

        assertThat(third.name()).isEqualTo("3단계 완주");
        assertThat(third.criterion()).isEqualTo("3단계의 글을 모두 읽습니다");
        assertThat(third.stageNumber()).isEqualTo(3);
        assertThat(third.stageKey()).isEqualTo(LearnerScopes.stageKey(3));
    }
}
