package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.UnpublishResult;

/**
 * 게시 취소 200의 {@code data}(learning-admin-api §7, 예시 {@code unpublish.json}). 이미 초안이었으면 {@code
 * unpublishedRevisionNo}가 명시적 null이다.
 */
public record UnpublishResponse(String articleId, String status, Integer unpublishedRevisionNo) {

    public static UnpublishResponse from(UnpublishResult result) {
        return new UnpublishResponse(
                result.articleId(), result.status().name(), result.unpublishedRevisionNo());
    }
}
