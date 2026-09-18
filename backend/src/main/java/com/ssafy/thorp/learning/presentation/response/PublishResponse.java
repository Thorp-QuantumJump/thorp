package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.PublishResult;
import java.time.Instant;

/** 게시 200의 {@code data}(learning-admin-api §6, 예시 {@code publish-success.json}). */
public record PublishResponse(
        String articleId,
        String status,
        int revisionNo,
        String checkVersion,
        String sectorMapVersion,
        Instant publishedAt) {

    public static PublishResponse from(PublishResult result) {
        return new PublishResponse(
                result.articleId(),
                result.status().name(),
                result.revisionNo(),
                result.checkVersion(),
                result.sectorMapVersion(),
                result.publishedAt());
    }
}
