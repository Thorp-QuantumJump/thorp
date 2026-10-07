package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.model.ContentScope.StageScope;
import com.ssafy.thorp.learning.domain.model.LearnerProgress.MasteredVia;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.BadgeKey;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 한 사용자의 나의 학습 사실(data-model §11). 수는 {@link ContentScope}로 센다(§12-1). 게시 취소된 글과 지운 문제의 기록은 여기 남지만
 * 범위 밖이라 세지 않는다.
 *
 * @param progress 글마다 기록. 행이 없는 글은 없다
 * @param answers 모든 첫 답
 * @param ledger 경험치 장부
 * @param badges 받은 배지와 받은 시각
 */
public record LearnerFacts(
        Map<ArticleId, LearnerProgress> progress,
        List<FirstAnswer> answers,
        ExpLedger ledger,
        Map<BadgeKey, Instant> badges) {

    public LearnerFacts {
        progress = Map.copyOf(progress);
        answers = List.copyOf(answers);
        Objects.requireNonNull(ledger, "ledger must not be null");
        badges = Map.copyOf(badges);
    }

    public LearnerProgress progressOf(ArticleId articleId) {
        return progress.get(articleId);
    }

    public boolean completed(ArticleId articleId) {
        LearnerProgress row = progress.get(articleId);
        return row != null && row.completed();
    }

    public boolean mastered(ArticleId articleId) {
        LearnerProgress row = progress.get(articleId);
        return row != null && row.mastered();
    }

    /** 장부를 바꾼 사실. 정산이 더한 줄로 레벨을 다시 셀 때 쓴다. */
    public LearnerFacts withLedger(ExpLedger newLedger) {
        return new LearnerFacts(progress, answers, newLedger, badges);
    }

    /** 이 글의 공개 판에 남은 문제의 첫 답. 키는 문제 식별자다. */
    public Map<QuestionId, FirstAnswer> answersFor(
            ArticleId articleId, List<QuestionId> questions) {
        Set<QuestionId> live = new HashSet<>(questions);
        Map<QuestionId, FirstAnswer> found = new HashMap<>();
        for (FirstAnswer answer : answers) {
            if (answer.articleId().equals(articleId) && live.contains(answer.questionId())) {
                found.put(answer.questionId(), answer);
            }
        }
        return found;
    }

    /** 읽은 글 수: 공개된 글 가운데 완료한 글. */
    public int readCount(ContentScope scope) {
        return (int) scope.articles().stream().filter(this::completed).count();
    }

    /** 첫 답 정답 수: 공개된 문제 가운데 첫 답이 맞은 것. */
    public int correctCount(ContentScope scope) {
        int count = 0;
        for (ArticleId article : scope.articles()) {
            count +=
                    (int)
                            answersFor(article, scope.questionsOf(article)).values().stream()
                                    .filter(FirstAnswer::correct)
                                    .count();
        }
        return count;
    }

    /** 모두 맞힘 글 수: 공개된 글 가운데 {@code ALL_CORRECT} 장부 줄이 있는 글. */
    public int perfectCount(ContentScope scope) {
        return (int) scope.articles().stream().filter(ledger::hasAllCorrect).count();
    }

    /** 익힌 용어 수: 공개된 용어 가운데 그 용어의 글이 익힘인 것. */
    public int masteredTermCount(ContentScope scope) {
        return scope.articles().stream()
                .filter(this::mastered)
                .mapToInt(article -> scope.termsOf(article).size())
                .sum();
    }

    /** 이 단계의 공개된 글 가운데 완료한 수. */
    public int completedIn(StageScope stage) {
        return (int) stage.articles().stream().filter(this::completed).count();
    }

    /** 비어 있지 않은 공개 단계이고 공개된 글을 모두 완료했다. 글이 0편인 단계를 '모두 완료'로 보지 않는다(FR-031). */
    public boolean stageCompleted(StageScope stage) {
        return stage.nonEmpty() && completedIn(stage) == stage.articles().size();
    }

    /** 다시 풀기로 익힌 글이 하나 이상 있다. 공개 범위와 관계없다(data-model §12-4 {@code persistence}). */
    public boolean anyRetryMastered() {
        return progress.values().stream().anyMatch(row -> row.masteredVia() == MasteredVia.RETRY);
    }
}
