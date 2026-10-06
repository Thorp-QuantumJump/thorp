package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.learning.domain.exception.InvalidDraftException;
import com.ssafy.thorp.learning.domain.exception.InvalidDraftException.FieldViolation;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 확인 문제 식별자 주기와 같은 문제 판정(data-model §6-2·§6-3, research R21). */
class QuizIdentityTest {

    private static QuizChoice choice(String id, boolean correct) {
        return new QuizChoice(id == null ? null : new ChoiceId(id), "보기 " + id, correct);
    }

    private static QuizQuestion question(String id, QuizChoice... choices) {
        return new QuizQuestion(
                id == null ? null : new QuestionId(id), "질문", List.of(choices), "풀이");
    }

    private static List<String> ids(List<QuizQuestion> quiz) {
        return quiz.stream()
                .flatMap(
                        question ->
                                Stream.concat(
                                        Stream.of(question.questionId().value()),
                                        question.choices().stream()
                                                .map(choice -> choice.choiceId().value())))
                .toList();
    }

    @Test
    @DisplayName("새 문제와 보기는 같은 카운터로 문서 순서대로 식별자를 받는다")
    void assignsFromSharedCounter() {
        QuizIdentity.Assigned assigned =
                QuizIdentity.assign(
                        List.of(
                                question(null, choice(null, false), choice(null, true)),
                                question(null, choice(null, true), choice(null, false))),
                        List.of(),
                        0);

        assertThat(ids(assigned.quiz())).containsExactly("q1", "c2", "c3", "q4", "c5", "c6");
        assertThat(assigned.quizSeq()).isEqualTo(6);
    }

    @Test
    @DisplayName("이미 있는 식별자는 그대로 두고, 지운 번호는 다시 쓰지 않는다")
    void keepsExistingAndNeverReuses() {
        List<QuizQuestion> current =
                List.of(
                        question("q1", choice("c2", false), choice("c3", true)),
                        question("q4", choice("c5", true), choice("c6", false)));

        // q4를 지우고 새 문제를 더한다. 카운터는 6에서 이어진다.
        QuizIdentity.Assigned assigned =
                QuizIdentity.assign(
                        List.of(
                                question("q1", choice("c2", false), choice("c3", true)),
                                question(null, choice(null, true), choice(null, false))),
                        current,
                        6);

        assertThat(ids(assigned.quiz())).containsExactly("q1", "c2", "c3", "q7", "c8", "c9");
        assertThat(assigned.quizSeq()).isEqualTo(9);
    }

    @Test
    @DisplayName("지금 초안에 없는 식별자를 지어 보내면 위치와 함께 거절한다")
    void rejectsFabricatedIds() {
        List<QuizQuestion> current = List.of(question("q1", choice("c2", true)));

        assertThatThrownBy(
                        () ->
                                QuizIdentity.assign(
                                        List.of(question("q9", choice("c8", true))), current, 2))
                .isInstanceOfSatisfying(
                        InvalidDraftException.class,
                        e ->
                                assertThat(e.violations())
                                        .extracting(FieldViolation::field)
                                        .containsExactly(
                                                "quiz[0].questionId",
                                                "quiz[0].choices[0].choiceId"));
    }

    @Test
    @DisplayName("한 글 안에서 같은 문제·보기 식별자를 두 번 쓰면 거절한다")
    void rejectsDuplicatesAcrossArticle() {
        List<QuizQuestion> current =
                List.of(question("q1", choice("c2", true)), question("q3", choice("c4", true)));

        assertThatThrownBy(
                        () ->
                                QuizIdentity.assign(
                                        List.of(
                                                question("q1", choice("c2", true)),
                                                question("q1", choice("c2", true))),
                                        current,
                                        4))
                .isInstanceOfSatisfying(
                        InvalidDraftException.class,
                        e ->
                                assertThat(e.violations())
                                        .extracting(FieldViolation::field)
                                        .containsExactly(
                                                "quiz[1].questionId",
                                                "quiz[1].choices[0].choiceId"));
    }

    @Test
    @DisplayName("정답과 보기 집합이 같으면 같은 문제다(글자·순서만 바뀜)")
    void sameQuestionKeepsId() {
        List<QuizQuestion> latest =
                List.of(question("q1", choice("c2", false), choice("c3", true)));
        List<QuizQuestion> draft =
                List.of(
                        new QuizQuestion(
                                new QuestionId("q1"),
                                "글자를 고친 질문",
                                List.of(choice("c3", true), choice("c2", false)),
                                "고친 풀이"));

        QuizIdentity.Reconciled reconciled = QuizIdentity.reconcile(draft, latest, 3);

        assertThat(reconciled.renamed()).isEmpty();
        assertThat(reconciled.quiz()).isEqualTo(draft);
        assertThat(reconciled.quizSeq()).isEqualTo(3);
    }

    @Test
    @DisplayName("정답이 바뀌거나 보기가 더해지면 새 문제이고 새 식별자를 받는다")
    void changedQuestionIsRenamed() {
        List<QuizQuestion> latest =
                List.of(
                        question("q1", choice("c2", false), choice("c3", true)),
                        question("q4", choice("c5", true), choice("c6", false)));
        List<QuizQuestion> draft =
                List.of(
                        question("q1", choice("c2", true), choice("c3", false)),
                        question(
                                "q4",
                                choice("c5", true),
                                choice("c6", false),
                                choice("c7", false)));

        QuizIdentity.Reconciled reconciled = QuizIdentity.reconcile(draft, latest, 7);

        assertThat(reconciled.renamed())
                .containsExactly(
                        new QuizIdentity.Renamed(new QuestionId("q1"), new QuestionId("q8")),
                        new QuizIdentity.Renamed(new QuestionId("q4"), new QuestionId("q9")));
        assertThat(reconciled.quiz())
                .extracting(QuizQuestion::questionId)
                .containsExactly(new QuestionId("q8"), new QuestionId("q9"));
        assertThat(reconciled.quiz().get(0).choices())
                .as("보기 식별자는 그대로다")
                .extracting(QuizChoice::choiceId)
                .containsExactly(new ChoiceId("c2"), new ChoiceId("c3"));
        assertThat(reconciled.quizSeq()).isEqualTo(9);
    }

    @Test
    @DisplayName("카운터가 9999를 넘게 되면 식별자를 주지 않고 quiz 자리로 거절한다 (data-model §6-2)")
    void exhaustedSequenceIsRejected() {
        QuizIdentity.Assigned last =
                QuizIdentity.assign(List.of(question(null, choice(null, true))), List.of(), 9997);
        assertThat(last.quizSeq()).isEqualTo(9999);
        assertThat(ids(last.quiz())).containsExactly("q9998", "c9999");

        assertThatThrownBy(
                        () ->
                                QuizIdentity.assign(
                                        List.of(question(null, choice(null, true))),
                                        List.of(),
                                        9998))
                .isInstanceOfSatisfying(
                        InvalidDraftException.class,
                        invalid ->
                                assertThat(invalid.violations())
                                        .extracting(FieldViolation::field)
                                        .containsExactly("quiz"));

        List<QuizQuestion> latest =
                List.of(question("q1", choice("c2", true), choice("c3", false)));
        List<QuizQuestion> changed =
                List.of(question("q1", choice("c2", false), choice("c3", true)));
        assertThatThrownBy(() -> QuizIdentity.reconcile(changed, latest, 9999))
                .isInstanceOf(InvalidDraftException.class);
    }

    @Test
    @DisplayName("보기가 빠져도 새 문제이고 새 식별자를 받는다 (data-model §6-3)")
    void removedChoiceIsRenamed() {
        List<QuizQuestion> latest =
                List.of(
                        question(
                                "q1",
                                choice("c2", false),
                                choice("c3", true),
                                choice("c4", false)));
        List<QuizQuestion> draft = List.of(question("q1", choice("c2", false), choice("c3", true)));

        QuizIdentity.Reconciled reconciled = QuizIdentity.reconcile(draft, latest, 4);

        assertThat(reconciled.renamed())
                .containsExactly(
                        new QuizIdentity.Renamed(new QuestionId("q1"), new QuestionId("q5")));
        assertThat(reconciled.quizSeq()).isEqualTo(5);
    }

    @Test
    @DisplayName("가장 최근 판에 없는 문제는 새 문제이며 아직 어느 판에도 없던 식별자를 그대로 쓴다")
    void questionMissingFromLatestKeepsFreshId() {
        List<QuizQuestion> draft = List.of(question("q5", choice("c6", true), choice("c7", false)));

        QuizIdentity.Reconciled reconciled = QuizIdentity.reconcile(draft, List.of(), 7);

        assertThat(reconciled.renamed()).isEmpty();
        assertThat(reconciled.quiz()).isEqualTo(draft);
    }
}
