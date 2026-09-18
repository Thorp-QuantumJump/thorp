package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.AdminArticleSummaryResult;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /admin/learning/articles}의 {@code data}(learning-admin-api §2, 예시 {@code
 * admin-articles-list.json}).
 *
 * <p>{@code needsUpdate}·{@code lastPublication}·{@code revisionNo}는 없으면 명시적 null이다.
 */
public record AdminArticleListResponse(List<Item> articles) {

    public static AdminArticleListResponse from(List<AdminArticleSummaryResult> results) {
        return new AdminArticleListResponse(
                results.stream()
                        .map(
                                result ->
                                        new Item(
                                                result.articleId(),
                                                result.title(),
                                                result.readingOrder(),
                                                result.status().name(),
                                                result.draftVersion(),
                                                result.hasUnpublishedChanges(),
                                                result.needsUpdate(),
                                                result.lastPublication() == null
                                                        ? null
                                                        : new LastPublication(
                                                                result.lastPublication()
                                                                        .action()
                                                                        .name(),
                                                                result.lastPublication()
                                                                        .revisionNo(),
                                                                result.lastPublication()
                                                                        .actorUserId(),
                                                                result.lastPublication()
                                                                        .occurredAt())))
                        .toList());
    }

    public record Item(
            String articleId,
            String title,
            int readingOrder,
            String status,
            int draftVersion,
            boolean hasUnpublishedChanges,
            Boolean needsUpdate,
            LastPublication lastPublication) {}

    public record LastPublication(
            String action, Integer revisionNo, long actorUserId, Instant occurredAt) {}
}
