package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.ArticleStatus;
import com.ssafy.thorp.learning.domain.model.PublicationEvent.PublicationAction;
import java.time.Instant;

/**
 * 관리 글 목록의 한 항목(learning-admin-api §2). 초안 글도 포함한다.
 *
 * @param hasUnpublishedChanges 공개 중인 판 이후 초안이 저장됐는가. 초안 글이면 false다
 * @param needsUpdate 공개 중인 판의 예시 섹터가 지금 열린 대응표에 없는가. 초안 글이면 false, 대응표를 읽지 못하면 null이다
 * @param lastPublication 게시·게시 취소 기록 중 마지막 것. 없으면 null이다
 */
public record AdminArticleSummaryResult(
        String articleId,
        String title,
        int readingOrder,
        ArticleStatus status,
        int draftVersion,
        boolean hasUnpublishedChanges,
        Boolean needsUpdate,
        LastPublication lastPublication) {

    /**
     * @param revisionNo 기록이 가리키는 판 번호
     * @param actorUserId 사용자 식별자만 싣는다. 이메일·닉네임은 싣지 않는다
     */
    public record LastPublication(
            PublicationAction action, Integer revisionNo, long actorUserId, Instant occurredAt) {}
}
