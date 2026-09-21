package com.ssafy.thorp.regimelink.presentation.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 사례 열기 요청 (contracts §2).
 *
 * @param scenario {@code CONTINUOUS}(기본) 또는 {@code FRESH_CASH}. 비우면 연속 운용이다
 * @param confirmFullPeriod 전체 기간 재계산 확인. 거짓이고 확인이 필요하면 계산도 행도 만들지 않고 200 으로 사유를 돌려준다
 */
public record OpenRegimeLinkRequest(
        @NotBlank String linkSearchId,
        @NotBlank String caseId,
        String scenario,
        boolean confirmFullPeriod) {}
