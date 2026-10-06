package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.PublishCheckFailure;
import com.ssafy.thorp.learning.domain.model.StageRelease.Blocker;
import java.util.List;

/**
 * 단계 미리 검사(learning-admin-api §10). 단계 공개와 같은 판정이며 아무것도 쓰지 않았다.
 *
 * @param blockers 글 검사와 별개로 공개를 막는 것
 * @param articles 공개 집합의 글 전부. 초안 단계가 이 단계인 글, 그다음 공개 배치만 이 단계인 글이다
 * @param checkVersion v2 검사 규칙 버전
 */
public record StageReadinessResult(
        String stageKey,
        List<Blocker> blockers,
        List<ArticleReadiness> articles,
        String checkVersion) {

    public StageReadinessResult {
        blockers = List.copyOf(blockers);
        articles = List.copyOf(articles);
    }

    /** {@code blockers}와 모든 글의 항목이 비었다. */
    public boolean ready() {
        return blockers.isEmpty()
                && articles.stream().allMatch(article -> article.failures().isEmpty());
    }

    /**
     * @param draftVersion 화면이 단계 공개 요청에 그대로 싣는 값
     * @param failures 형식·배치·용어 소유 항목, 그다음 v2 검사 항목
     */
    public record ArticleReadiness(
            String articleId, int draftVersion, List<PublishCheckFailure> failures) {

        public ArticleReadiness {
            failures = List.copyOf(failures);
        }
    }
}
