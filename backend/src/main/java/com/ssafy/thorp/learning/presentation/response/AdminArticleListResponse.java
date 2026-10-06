package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.AdminArticleSummaryResult;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /admin/learning/articles}의 {@code data}(learning-admin-api §2, 예시 {@code
 * admin-articles-list.json}).
 *
 * <p>{@code needsUpdate}·{@code lastPublication}·{@code revisionNo}·{@code publishedEdition}·{@code
 * needsReview}는 없으면 명시적 null이다.
 */
public record AdminArticleListResponse(List<Item> articles) {

    public static AdminArticleListResponse from(List<AdminArticleSummaryResult> results) {
        return new AdminArticleListResponse(
                results.stream().map(AdminArticleListResponse::item).toList());
    }

    private static Item item(AdminArticleSummaryResult result) {
        AdminArticleSummaryResult.LastPublication last = result.lastPublication();
        return new Item(
                result.articleId(),
                result.title(),
                result.readingOrder(),
                result.status().name(),
                result.draftVersion(),
                result.hasUnpublishedChanges(),
                result.needsUpdate(),
                last == null
                        ? null
                        : new LastPublication(
                                last.action().name(),
                                last.revisionNo(),
                                last.actorUserId(),
                                last.occurredAt()),
                result.format().name(),
                new Stage(
                        result.stage().stageKey(),
                        result.stage().number(),
                        result.stage().released()),
                result.stagePosition(),
                result.publishedEdition() == null ? null : result.publishedEdition().name(),
                result.needsReview(),
                result.pendingTransfers());
    }

    public record Item(
            String articleId,
            String title,
            int readingOrder,
            String status,
            int draftVersion,
            boolean hasUnpublishedChanges,
            Boolean needsUpdate,
            LastPublication lastPublication,
            String format,
            Stage stage,
            int stagePosition,
            String publishedEdition,
            Boolean needsReview,
            int pendingTransfers) {}

    public record LastPublication(
            String action, Integer revisionNo, long actorUserId, Instant occurredAt) {}

    /** 초안 배치의 단계. {@code released}면 V2 글을 글마다 게시할 수 있다. */
    public record Stage(String stageKey, int number, boolean released) {}
}
