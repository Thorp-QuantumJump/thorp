package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.ArticleStatus;
import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.PublicationEvent.PublicationAction;
import java.time.Instant;

/**
 * 관리 글 목록의 한 항목(learning-admin-api §2). 초안 글도 포함한다.
 *
 * @param hasUnpublishedChanges 공개 중인 판 이후 초안이 저장됐는가. 초안 글이면 false다
 * @param needsUpdate 공개 중인 판의 예시 섹터가 지금 열린 대응표에 없는가. 초안 글이면 false, 공개 판에 예시가 없거나 대응표를 읽지 못하면 null이다
 * @param lastPublication 게시·게시 취소 기록 중 마지막 것. 없으면 null이다
 * @param format 초안 형식
 * @param stage 초안 배치의 단계
 * @param stagePosition 초안 배치의 단계 안 순서
 * @param publishedEdition 공개 중인 판의 종류. 게시되지 않았으면 null이다
 * @param needsReview 공개 판의 제도 안내 기준 시점이 설정 기간보다 오래됐는가. 제도 안내가 없거나 게시되지 않았으면 null이다(R37)
 * @param pendingTransfers 초안에서 넘겨받는 중인 용어 수(R24)
 */
public record AdminArticleSummaryResult(
        String articleId,
        String title,
        int readingOrder,
        ArticleStatus status,
        int draftVersion,
        boolean hasUnpublishedChanges,
        Boolean needsUpdate,
        LastPublication lastPublication,
        ArticleFormat format,
        StageSummary stage,
        int stagePosition,
        Edition publishedEdition,
        Boolean needsReview,
        int pendingTransfers) {

    /**
     * @param revisionNo 기록이 가리키는 판 번호
     * @param actorUserId 사용자 식별자만 싣는다. 이메일·닉네임은 싣지 않는다
     */
    public record LastPublication(
            PublicationAction action, Integer revisionNo, long actorUserId, Instant occurredAt) {}

    /**
     * @param released 단계가 공개됐는가. 초안 배치가 공개 전 단계면 V2 글은 '단계 공개'로만 게시된다
     */
    public record StageSummary(String stageKey, int number, boolean released) {}
}
