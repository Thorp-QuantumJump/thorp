package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 지금 공개된 콘텐츠의 크기(data-model §12-1). 나의 학습의 모든 수는 이 범위만 센다.
 *
 * <p><b>공개된 글</b>은 {@code CURRENT} 판이 게시 중인 글이다. 공개 배치 단계는 늘 공개된 단계다. 이전 판({@code LEGACY}) 글, 게시
 * 취소된 글, 지운 문제는 여기 없다. 그 기록은 남지만 수에 넣지 않는다(FR-020, FR-031).
 *
 * @param stages 여섯 단계 전부, 번호 순서
 * @param questions 공개된 글마다 공개 판의 문제 식별자, 판의 순서
 * @param terms 공개된 글마다 공개된 용어(게시 용어 색인)
 */
public record ContentScope(
        List<StageScope> stages,
        Map<ArticleId, List<QuestionId>> questions,
        Map<ArticleId, List<TermId>> terms) {

    public ContentScope {
        stages = List.copyOf(stages);
        Map<ArticleId, List<QuestionId>> copiedQuestions = new HashMap<>();
        questions.forEach((article, ids) -> copiedQuestions.put(article, List.copyOf(ids)));
        questions = Map.copyOf(copiedQuestions);
        Map<ArticleId, List<TermId>> copiedTerms = new HashMap<>();
        terms.forEach((article, ids) -> copiedTerms.put(article, List.copyOf(ids)));
        terms = Map.copyOf(copiedTerms);
        for (StageScope stage : stages) {
            if (!stage.released() && !stage.articles().isEmpty()) {
                throw new IllegalArgumentException(
                        "an unreleased stage has no published articles: " + stage.stageKey());
            }
        }
    }

    /** 공개된 글 전부. 읽는 순서(단계 번호, 단계 안 순서, {@code articleId})다. */
    public List<ArticleId> articles() {
        List<ArticleId> all = new ArrayList<>();
        stages.forEach(stage -> all.addAll(stage.articles()));
        return all;
    }

    public boolean isPublished(ArticleId articleId) {
        return stageOf(articleId).isPresent();
    }

    /** 이 글이 공개 배치된 단계. 공개된 글이 아니면 비어 있다. */
    public Optional<StageScope> stageOf(ArticleId articleId) {
        return stages.stream().filter(stage -> stage.articles().contains(articleId)).findFirst();
    }

    /** 글 번호 {@code {단계 번호}-{단계 안 순번}}(data-model §12-2). 공개된 글이 아니면 비어 있다. */
    public Optional<String> numberOf(ArticleId articleId) {
        return stageOf(articleId)
                .map(stage -> stage.number() + "-" + (stage.articles().indexOf(articleId) + 1));
    }

    /** 공개 판의 문제. 공개된 글이 아니거나 문제가 없으면 빈 목록이다. */
    public List<QuestionId> questionsOf(ArticleId articleId) {
        return questions.getOrDefault(articleId, List.of());
    }

    /** 공개된 용어. 공개된 글이 아니거나 용어가 없으면 빈 목록이다. */
    public List<TermId> termsOf(ArticleId articleId) {
        return terms.getOrDefault(articleId, List.of());
    }

    public int articleCount() {
        return articles().size();
    }

    public int questionCount() {
        return articles().stream().mapToInt(article -> questionsOf(article).size()).sum();
    }

    public int termCount() {
        return articles().stream().mapToInt(article -> termsOf(article).size()).sum();
    }

    /** 공개된 단계 가운데 공개된 글이 한 편 이상인 단계. */
    public List<StageScope> nonEmptyReleasedStages() {
        return stages.stream().filter(StageScope::nonEmpty).toList();
    }

    public boolean anyReleased() {
        return stages.stream().anyMatch(StageScope::released);
    }

    public boolean allStagesReleased() {
        return stages.stream().allMatch(StageScope::released);
    }

    /** 경험치 최대치 = 100 × 공개된 글 수 + 50 × 비어 있지 않은 공개 단계 수(FR-029). */
    public int maxExp() {
        return ExpLedger.ARTICLE_MAX_POINTS * articleCount()
                + ExpLedger.STAGE_COMPLETED_POINTS * nonEmptyReleasedStages().size();
    }

    /**
     * 단계 하나와 그 단계의 공개된 글.
     *
     * @param articles 공개 배치가 이 단계인 공개된 글, 읽는 순서. 공개 전 단계는 비어 있다
     */
    public record StageScope(
            StageKey stageKey, int number, boolean released, List<ArticleId> articles) {

        public StageScope {
            Objects.requireNonNull(stageKey, "stageKey must not be null");
            articles = List.copyOf(articles);
        }

        /** 공개됐고 공개된 글이 한 편 이상이다. 단계 완주·단계 배지·'탐험'은 이 단계만 본다(FR-031). */
        public boolean nonEmpty() {
            return released && !articles.isEmpty();
        }
    }
}
