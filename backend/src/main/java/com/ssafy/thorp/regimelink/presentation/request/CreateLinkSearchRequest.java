package com.ssafy.thorp.regimelink.presentation.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 검색 기록 생성 요청 (contracts §1b).
 *
 * @param clientRequestId 요청자별 멱등 키. 전체 탐색 전환은 <b>새 키</b>로 보낸다 — 같은 키에 다른 본문은 400 이다
 * @param previousLinkSearchId 같은 흐름의 직전 기록. 없으면 비운다
 */
public record CreateLinkSearchRequest(
        @NotBlank String clientRequestId,
        @NotBlank String snapshotKey,
        @NotBlank String base,
        String compare,
        @NotBlank String entryPath,
        boolean fullSearchShown,
        String previousLinkSearchId) {}
