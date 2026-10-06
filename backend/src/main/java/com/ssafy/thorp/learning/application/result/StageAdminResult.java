package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.ArticleStatus;
import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.LearningStage;
import java.util.List;

/**
 * 관리 단계 목록의 한 단계(learning-admin-api §9).
 *
 * @param releasable 공개 전이고 앞 단계가 공개되었다. 검사 통과 여부는 미리 검사가 본다
 * @param articles 초안 단계가 이 단계인 글. 초안 단계 안 순서다
 * @param placementOnlyArticles 공개 배치만 이 단계인 글. 공개 배치 단계 안 순서다
 */
public record StageAdminResult(
        LearningStage stage,
        boolean releasable,
        List<StageArticle> articles,
        List<StageArticle> placementOnlyArticles) {

    public StageAdminResult {
        articles = List.copyOf(articles);
        placementOnlyArticles = List.copyOf(placementOnlyArticles);
    }

    /**
     * @param publishedEdition 공개 중인 판의 종류. 게시되지 않았으면 null이다
     * @param stagePosition 이 단계 안 순서. {@code articles}는 초안 배치, {@code placementOnlyArticles}는 공개
     *     배치의 값이다
     */
    public record StageArticle(
            String articleId,
            String title,
            ArticleFormat format,
            ArticleStatus status,
            Edition publishedEdition,
            int draftVersion,
            int stagePosition) {}
}
