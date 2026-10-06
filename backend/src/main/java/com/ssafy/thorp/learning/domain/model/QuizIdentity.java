package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.exception.InvalidDraftException;
import com.ssafy.thorp.learning.domain.exception.InvalidDraftException.FieldViolation;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 확인 문제·보기의 고정 식별자(data-model §6-2·§6-3, research R21). 순수 함수만 둔다.
 *
 * <p><b>식별자는 서버가 준다.</b> 저장할 때 식별자가 없는 문제·보기에 글의 {@code quiz_seq}를 1씩 올려 {@code q{n}}·{@code c{n}}을
 * 준다. 문제와 보기가 같은 카운터를 쓰고, 지운 번호는 다시 쓰지 않는다. 화면이 식별자를 지어 보내면 거절한다. 사용자의 첫 답은 (글, 문제 식별자)로 남으므로, 화면이
 * 식별자를 고를 수 있으면 다른 문제의 답이 이 문제에 붙는다.
 */
public final class QuizIdentity {

    private QuizIdentity() {}

    /** 식별자를 채운 문제 목록과 오른 카운터. */
    public record Assigned(List<QuizQuestion> quiz, int quizSeq) {
        public Assigned {
            quiz = List.copyOf(quiz);
        }
    }

    /** 같은 문제 판정으로 새 식별자를 받은 문제. */
    public record Renamed(QuestionId from, QuestionId to) {
        public Renamed {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
        }
    }

    /** 같은 문제 판정 결과. {@code quiz}는 판과 초안에 함께 넣는 문제 목록이다. */
    public record Reconciled(List<QuizQuestion> quiz, List<Renamed> renamed, int quizSeq) {
        public Reconciled {
            quiz = List.copyOf(quiz);
            renamed = List.copyOf(renamed);
        }
    }

    /**
     * 저장 요청의 문제에 식별자를 채운다(data-model §6-2).
     *
     * @param requested 요청의 문제. 새 문제·보기는 식별자가 null이다
     * @param current 지금 초안의 문제. 요청이 실을 수 있는 식별자는 여기 있는 것뿐이다
     * @param quizSeq 지금 카운터
     * @throws InvalidDraftException 지금 초안에 없는 식별자, 한 글 안에서 겹치는 식별자. 위치는 {@code
     *     quiz[i].questionId}·{@code quiz[i].choices[j].choiceId}다
     */
    public static Assigned assign(
            List<QuizQuestion> requested, List<QuizQuestion> current, int quizSeq) {
        Set<QuestionId> currentQuestions = new HashSet<>();
        Set<ChoiceId> currentChoices = new HashSet<>();
        for (QuizQuestion question : current) {
            currentQuestions.add(question.questionId());
            currentChoices.addAll(question.choiceIds());
        }
        List<FieldViolation> violations = new ArrayList<>();
        Set<QuestionId> seenQuestions = new HashSet<>();
        Set<ChoiceId> seenChoices = new HashSet<>();
        for (int q = 0; q < requested.size(); q++) {
            QuizQuestion question = requested.get(q);
            String questionField = "quiz[" + q + "].questionId";
            if (question.questionId() != null) {
                if (!currentQuestions.contains(question.questionId())) {
                    violations.add(new FieldViolation(questionField, "지금 초안에 없는 문제 식별자입니다"));
                } else if (!seenQuestions.add(question.questionId())) {
                    violations.add(new FieldViolation(questionField, "같은 문제 식별자가 두 번 있습니다"));
                }
            }
            List<QuizChoice> choices = question.choices();
            for (int c = 0; c < choices.size(); c++) {
                ChoiceId choiceId = choices.get(c).choiceId();
                if (choiceId == null) {
                    continue;
                }
                String choiceField = "quiz[" + q + "].choices[" + c + "].choiceId";
                if (!currentChoices.contains(choiceId)) {
                    violations.add(new FieldViolation(choiceField, "지금 초안에 없는 보기 식별자입니다"));
                } else if (!seenChoices.add(choiceId)) {
                    violations.add(new FieldViolation(choiceField, "같은 보기 식별자가 두 번 있습니다"));
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new InvalidDraftException(violations);
        }
        int sequence = quizSeq;
        List<QuizQuestion> assigned = new ArrayList<>();
        for (QuizQuestion question : requested) {
            QuestionId questionId = question.questionId();
            if (questionId == null) {
                questionId = QuestionId.of(++sequence);
            }
            List<QuizChoice> choices = new ArrayList<>();
            for (QuizChoice choice : question.choices()) {
                choices.add(
                        choice.choiceId() == null
                                ? choice.withId(ChoiceId.of(++sequence))
                                : choice);
            }
            assigned.add(question.withIds(questionId, choices));
        }
        return new Assigned(assigned, sequence);
    }

    /**
     * 게시하는 초안의 문제를 그 글의 가장 최근 판과 비교한다(data-model §6-3).
     *
     * <ul>
     *   <li>최근 판에 같은 식별자의 문제가 있고 정답 보기 식별자와 보기 식별자 집합이 같다 → 같은 문제다. 질문·보기·풀이 글자나 보기 순서만 바뀐 경우다
     *   <li>최근 판에 같은 식별자의 문제가 있는데 정답이 다르거나 보기가 더해지거나 빠졌다 → 새 문제다. 새 식별자를 준다
     *   <li>최근 판에 없다 → 새 문제다. 그 식별자는 아직 어느 판에도 없으므로 그대로 둔다
     * </ul>
     *
     * @param draft 게시하는 초안의 문제. 식별자가 모두 채워져 있다
     * @param latestPublished 그 글의 가장 최근 판의 문제. 판이 없거나 LEGACY 판이면 비어 있다
     */
    public static Reconciled reconcile(
            List<QuizQuestion> draft, List<QuizQuestion> latestPublished, int quizSeq) {
        Map<QuestionId, QuizQuestion> latest = new HashMap<>();
        latestPublished.forEach(question -> latest.put(question.questionId(), question));
        int sequence = quizSeq;
        List<QuizQuestion> reconciled = new ArrayList<>();
        List<Renamed> renamed = new ArrayList<>();
        for (QuizQuestion question : draft) {
            Objects.requireNonNull(question.questionId(), "draft question must have an id");
            QuizQuestion previous = latest.get(question.questionId());
            if (previous == null || sameQuestion(previous, question)) {
                reconciled.add(question);
                continue;
            }
            QuestionId fresh = QuestionId.of(++sequence);
            renamed.add(new Renamed(question.questionId(), fresh));
            reconciled.add(question.withIds(fresh, question.choices()));
        }
        return new Reconciled(reconciled, renamed, sequence);
    }

    private static boolean sameQuestion(QuizQuestion previous, QuizQuestion next) {
        return previous.correctChoiceIds().equals(next.correctChoiceIds())
                && previous.choiceIds().equals(next.choiceIds());
    }
}
