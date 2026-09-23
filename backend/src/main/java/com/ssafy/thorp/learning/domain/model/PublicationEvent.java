package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.time.Instant;
import java.util.Objects;

/**
 * 게시·게시 취소 기록 하나(FR-018). 추가만 한다.
 *
 * @param id 저장 전이면 null
 * @param revisionId {@code PUBLISH}면 새 판, {@code UNPUBLISH}면 내려간 판
 * @param actorUserId 수행한 관리 계정의 사용자 식별자
 */
public record PublicationEvent(
        Long id,
        ArticleId articleId,
        PublicationAction action,
        Long revisionId,
        long actorUserId,
        Instant occurredAt) {

    /** 게시 기록의 종류(data-model §3-4). */
    public enum PublicationAction {
        PUBLISH,
        UNPUBLISH
    }

    public PublicationEvent {
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
