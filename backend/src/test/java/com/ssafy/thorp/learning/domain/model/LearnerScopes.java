package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.model.ContentScope.StageScope;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 나의 학습 도메인 테스트가 쓰는 공개 범위. 여섯 단계(data-model §13-2)에 단계마다 공개된 글 수를 정해 만든다. 글은 {@code a{단계}-{순번}},
 * 글마다 문제 둘({@code q1}·{@code q2})과 용어 하나({@code t{단계}-{순번}})다.
 */
final class LearnerScopes {

    static final List<StageKey> STAGE_KEYS =
            List.of(
                    new StageKey("etf-market-basics"),
                    new StageKey("returns-and-risk"),
                    new StageKey("allocation-rules"),
                    new StageKey("backtest-and-costs"),
                    new StageKey("pitfalls-and-validation"),
                    new StageKey("economy-and-regimes"));

    private LearnerScopes() {}

    /**
     * @param published 단계마다 공개된 글 수. 길이 6. 음수는 공개 전 단계다
     */
    static ContentScope scope(int... published) {
        if (published.length != STAGE_KEYS.size()) {
            throw new IllegalArgumentException("six stages");
        }
        List<StageScope> stages = new ArrayList<>();
        Map<ArticleId, List<QuestionId>> questions = new HashMap<>();
        Map<ArticleId, List<TermId>> terms = new HashMap<>();
        for (int index = 0; index < published.length; index++) {
            int number = index + 1;
            List<ArticleId> articles = new ArrayList<>();
            for (int position = 1; position <= Math.max(0, published[index]); position++) {
                ArticleId article = article(number, position);
                articles.add(article);
                questions.put(article, List.of(new QuestionId("q1"), new QuestionId("q2")));
                terms.put(article, List.of(new TermId("t" + number + "-" + position)));
            }
            stages.add(
                    new StageScope(STAGE_KEYS.get(index), number, published[index] >= 0, articles));
        }
        return new ContentScope(stages, questions, terms);
    }

    static ArticleId article(int stage, int position) {
        return new ArticleId("a" + stage + "-" + position);
    }

    /** 1·2단계에 네 편씩, 나머지는 공개 전(quickstart 10-8의 8편). */
    static ContentScope firstTwoStages() {
        return scope(4, 4, -1, -1, -1, -1);
    }

    /** 여섯 단계 27편(data-model §13-3). */
    static ContentScope allStages() {
        return scope(4, 4, 9, 5, 3, 2);
    }

    static StageKey stageKey(int number) {
        return STAGE_KEYS.get(number - 1);
    }
}
