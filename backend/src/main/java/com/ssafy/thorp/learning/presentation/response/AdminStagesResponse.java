package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.StageAdminResult;
import com.ssafy.thorp.learning.application.result.StageAdminResult.StageArticle;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /admin/learning/stages}의 {@code data}(learning-admin-api §9, 예시 {@code
 * admin-stages.json}).
 *
 * <p>{@code releasedAt}·{@code releasedByUserId}·{@code publishedEdition}은 없으면 명시적 null이다.
 */
public record AdminStagesResponse(List<Stage> stages) {

    public static AdminStagesResponse from(List<StageAdminResult> results) {
        return new AdminStagesResponse(results.stream().map(AdminStagesResponse::stage).toList());
    }

    private static Stage stage(StageAdminResult result) {
        return new Stage(
                result.stage().stageKey().value(),
                result.stage().number(),
                result.stage().name(),
                result.stage().levelLabel(),
                result.stage().summary(),
                result.stage().releasedAt(),
                result.stage().releasedByUserId(),
                result.releasable(),
                result.articles().stream().map(AdminStagesResponse::article).toList(),
                result.placementOnlyArticles().stream().map(AdminStagesResponse::article).toList());
    }

    private static Article article(StageArticle article) {
        return new Article(
                article.articleId(),
                article.title(),
                article.format().name(),
                article.status().name(),
                article.publishedEdition() == null ? null : article.publishedEdition().name(),
                article.draftVersion(),
                article.stagePosition());
    }

    /**
     * @param releasable 공개 전이고 앞 단계가 공개되었다. 검사 통과 여부는 미리 검사(§10)가 본다
     * @param articles 초안 단계가 이 단계인 글
     * @param placementOnlyArticles 공개 배치만 이 단계인 글. 공개 집합에 들어가며 단계 공개에서 {@code PLACEMENT}로 걸린다
     */
    public record Stage(
            String stageKey,
            int number,
            String name,
            String levelLabel,
            String summary,
            Instant releasedAt,
            Long releasedByUserId,
            boolean releasable,
            List<Article> articles,
            List<Article> placementOnlyArticles) {}

    /**
     * @param stagePosition 이 단계 안 순서. {@code placementOnlyArticles}는 공개 배치의 값이다
     */
    public record Article(
            String articleId,
            String title,
            String format,
            String status,
            String publishedEdition,
            int draftVersion,
            int stagePosition) {}
}
