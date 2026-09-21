package com.ssafy.thorp.myrecord.presentation.response;

import java.time.Instant;

/**
 * {@code DELETE /my-records/{recordId}} 응답(contracts).
 *
 * <p>지운 시각만 돌려준다. <b>되돌리는 열쇠를 주지 않는다</b> — 복구 경로를 만들지 않기로 했고(research R6), 응답에 그런 값이 있으면 언젠가 만들게
 * 된다.
 */
public record DeletedMyRecordResponse(String recordId, Instant deletedAt) {}
