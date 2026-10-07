package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 경험치 장부와 정답 몫 상한(FR-028, SC-015, research R29, quickstart 10-5). */
class ExpLedgerTest {

    private static final ArticleId ARTICLE = new ArticleId("recovery-period");
    private static final Instant AT = Instant.parse("2026-11-20T03:10:00Z");

    @Test
    @DisplayName("세 문제 글을 완료하고 모두 맞히면 20 + 60 + 20 = 100이다")
    void threeQuestionsAllCorrect() {
        ExpLedger ledger = ExpLedger.empty().plus(List.of(ExpEntry.articleCompleted(ARTICLE, AT)));
        for (String question : List.of("q1", "q2", "q3")) {
            ledger = answerCorrect(ledger, question, 3, false);
        }
        ledger = ledger.plus(List.of(ExpEntry.allCorrect(ARTICLE, AT)));

        assertThat(ledger.total()).isEqualTo(100);
        assertThat(ledger.correctShareAwarded(ARTICLE)).isEqualTo(60);
    }

    @Test
    @DisplayName("두 문제 글에서 하나만 맞히면 20 + 30 = 50이다")
    void twoQuestionsOneCorrect() {
        ExpLedger ledger = ExpLedger.empty().plus(List.of(ExpEntry.articleCompleted(ARTICLE, AT)));
        ledger = answerCorrect(ledger, "q1", 2, false);

        assertThat(ledger.total()).isEqualTo(50);
    }

    @Test
    @DisplayName("모두 답한 글에 운영자가 더한 문제를 맞혀도 정답 몫은 0이다")
    void questionAddedAfterAllAnswered() {
        ExpLedger ledger = answerCorrect(ExpLedger.empty(), "q1", 2, false);

        assertThat(ledger.correctShare(ARTICLE, 3, true)).isZero();
        // 같은 장부라도 모두 답하기 전이었다면 몫이 있다. 0은 '모두 답한 뒤'라서다.
        assertThat(ledger.correctShare(ARTICLE, 3, false)).isEqualTo(20);
    }

    @Test
    @DisplayName("문제가 바뀌어 새 식별자가 생기거나 문제 수가 줄어도 정답 몫 합은 60을 넘지 않는다")
    void shareCappedWhenQuestionsChange() {
        ExpLedger ledger = answerCorrect(ExpLedger.empty(), "q1", 3, false);
        // 운영자가 정답을 바꿔 q2가 q4로 바뀌었다. 문제는 여전히 셋이다.
        ledger = answerCorrect(ledger, "q3", 3, false);
        ledger = answerCorrect(ledger, "q4", 3, false);
        assertThat(ledger.correctShareAwarded(ARTICLE)).isEqualTo(60);
        assertThat(ledger.correctShare(ARTICLE, 3, false)).isZero();

        // 세 문제 중 하나에 답한 뒤 두 문제로 줄면 몫이 30이 되지만 합은 60에서 멈춘다.
        ExpLedger shrunk = answerCorrect(ExpLedger.empty(), "q1", 3, false);
        shrunk = answerCorrect(shrunk, "q5", 2, false);
        assertThat(shrunk.correctShare(ARTICLE, 2, false)).isEqualTo(10);
        shrunk = answerCorrect(shrunk, "q6", 2, false);
        assertThat(shrunk.correctShareAwarded(ARTICLE)).isEqualTo(60);
    }

    @Test
    @DisplayName("같은 일로 두 번 더해도 장부 줄은 하나다")
    void sameDeedOnce() {
        ExpLedger ledger =
                ExpLedger.empty()
                        .plus(
                                List.of(
                                        ExpEntry.articleCompleted(ARTICLE, AT),
                                        ExpEntry.articleCompleted(ARTICLE, AT.plusSeconds(5))));

        assertThat(ledger.entries()).hasSize(1);
        assertThat(ledger.total()).isEqualTo(20);
    }

    @Test
    @DisplayName("현재 레벨에 닿은 시각은 합계가 문턱을 처음 넘은 줄의 시각이다")
    void reachedAt() {
        ExpLedger ledger =
                ExpLedger.empty()
                        .plus(
                                List.of(
                                        ExpEntry.articleCompleted(ARTICLE, AT),
                                        ExpEntry.stageCompleted(
                                                new StageKey("returns-and-risk"),
                                                AT.plusSeconds(60)),
                                        ExpEntry.allCorrect(ARTICLE, AT.plusSeconds(120))));

        assertThat(ledger.reachedAt(Level.threshold(1))).isNull();
        assertThat(ledger.reachedAt(Level.threshold(2))).isNull();
        assertThat(ledger.total()).isEqualTo(90);
        assertThat(ledger.reachedAt(70)).isEqualTo(AT.plusSeconds(60));
    }

    /** 맞은 첫 답 하나를 서비스와 같은 규칙으로 장부에 더한다. 몫이 0이면 줄을 쓰지 않는다. */
    private static ExpLedger answerCorrect(
            ExpLedger ledger, String question, int questionCount, boolean allAnswered) {
        int share = ledger.correctShare(ARTICLE, questionCount, allAnswered);
        if (share == 0) {
            return ledger;
        }
        return ledger.plus(
                List.of(ExpEntry.answerCorrect(ARTICLE, new QuestionId(question), share, AT)));
    }
}
