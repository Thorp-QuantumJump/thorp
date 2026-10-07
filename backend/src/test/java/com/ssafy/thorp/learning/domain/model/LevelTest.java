package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.learning.domain.model.Level.Milestone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 레벨 문턱과 보이는 레벨(FR-029, data-model §12-3, research R31). */
class LevelTest {

    @Test
    @DisplayName("합계가 문턱과 같으면 그 레벨이다. 100은 레벨 2, 99는 레벨 1이다")
    void thresholdIsInclusive() {
        assertThat(Level.levelFor(0)).isEqualTo(1);
        assertThat(Level.levelFor(99)).isEqualTo(1);
        assertThat(Level.levelFor(100)).isEqualTo(2);
        assertThat(Level.levelFor(299)).isEqualTo(2);
        assertThat(Level.levelFor(4500)).isEqualTo(10);
    }

    @Test
    @DisplayName("문턱은 표대로이고 11 이상은 900씩 오른다")
    void thresholds() {
        assertThat(java.util.stream.IntStream.rangeClosed(1, 12).map(Level::threshold).toArray())
                .containsExactly(0, 100, 300, 600, 1000, 1500, 2100, 2800, 3600, 4500, 5400, 6300);
        assertThat(Level.levelFor(5399)).isEqualTo(10);
        assertThat(Level.levelFor(5400)).isEqualTo(11);
    }

    @Test
    @DisplayName("1·2단계 8편이 공개되면 최대치 900, 보이는 레벨 4, N은 5, 사유는 단계가 더 공개된 뒤다")
    void firstTwoStagesShowUpToLevelFour() {
        ContentScope scope = LearnerScopes.firstTwoStages();

        Level level = Level.of(0, scope.maxExp(), scope.allStagesReleased());

        assertThat(scope.maxExp()).isEqualTo(900);
        assertThat(level.visibleMax()).isEqualTo(4);
        assertThat(level.unreachableFrom()).isEqualTo(5);
        assertThat(level.unreachableReason()).isEqualTo(PreparingReason.MORE_STAGES);
        assertThat(level.milestones())
                .containsExactly(
                        new Milestone(1, 0),
                        new Milestone(2, 100),
                        new Milestone(3, 300),
                        new Milestone(4, 600),
                        new Milestone(5, 1000));
    }

    @Test
    @DisplayName("여섯 단계 27편이면 최대치 3,000, 보이는 레벨 8, N은 9, 사유는 글이 더 공개된 뒤다")
    void allStagesShowUpToLevelEight() {
        ContentScope scope = LearnerScopes.allStages();

        Level level = Level.of(720, scope.maxExp(), scope.allStagesReleased());

        assertThat(scope.articleCount()).isEqualTo(27);
        assertThat(scope.maxExp()).isEqualTo(3000);
        assertThat(level.visibleMax()).isEqualTo(8);
        assertThat(level.unreachableFrom()).isEqualTo(9);
        assertThat(level.unreachableReason()).isEqualTo(PreparingReason.MORE_ARTICLES);
    }

    @Test
    @DisplayName("게시 취소로 받은 경험치가 남아 현재 레벨이 더 높으면 현재 레벨까지 보인다")
    void currentLevelAboveContentMaximum() {
        ContentScope scope = LearnerScopes.firstTwoStages();

        Level level = Level.of(1200, scope.maxExp(), scope.allStagesReleased());

        assertThat(level.current()).isEqualTo(5);
        assertThat(level.visibleMax()).isEqualTo(5);
        assertThat(level.unreachableFrom()).isEqualTo(6);
    }

    @Test
    @DisplayName("다음 레벨까지 남은 경험치는 다음 문턱에서 합계를 뺀 값이다")
    void remainingToNext() {
        Level level = Level.of(720, 900, false);

        assertThat(level.current()).isEqualTo(4);
        assertThat(level.currentThreshold()).isEqualTo(600);
        assertThat(level.nextThreshold()).isEqualTo(1000);
        assertThat(level.remainingToNext()).isEqualTo(280);
    }
}
