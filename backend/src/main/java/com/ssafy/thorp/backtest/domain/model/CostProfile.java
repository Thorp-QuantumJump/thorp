package com.ssafy.thorp.backtest.domain.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * 비용 조건 버전(spec 004 research R-07, data-model {@code cost_profile}).
 *
 * <p>실행은 버전 문자열만 들고 다니고 실제 bps 값은 이 등록표에서 읽는다. 비용 가정이 바뀌면 값을 고치는 대신 새 버전 행을 넣는다 — 이미 끝난 실행의 결과를 나중
 * 비용으로 재해석하면 재현이 깨진다(원칙 VIII).
 *
 * @param escalation 비용 상승 시나리오 정의. null = 미정(PD-07)
 * @param provisional true = 팀 결정(PD-07) 전 임시값. 결과에 "임시 비용"으로 표시한다
 */
public record CostProfile(
        String version,
        BigDecimal commissionBps,
        BigDecimal spreadBps,
        BigDecimal slippageBps,
        BigDecimal taxBps,
        Map<String, Object> escalation,
        boolean provisional,
        String note,
        /** 세금이 왜 그 값인지의 한 줄 설명(spec 015 도우미 근거). 없을 수 있다. */
        String taxNote) {

    public CostProfile(
            String version,
            BigDecimal commissionBps,
            BigDecimal spreadBps,
            BigDecimal slippageBps,
            BigDecimal taxBps,
            Map<String, Object> escalation,
            boolean provisional,
            String note) {
        this(
                version,
                commissionBps,
                spreadBps,
                slippageBps,
                taxBps,
                escalation,
                provisional,
                note,
                null);
    }

    public CostProfile {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        requireNonNegative(commissionBps, "commissionBps");
        requireNonNegative(spreadBps, "spreadBps");
        requireNonNegative(slippageBps, "slippageBps");
        requireNonNegative(taxBps, "taxBps");
        escalation = escalation == null ? null : Map.copyOf(escalation);
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative: " + value);
        }
    }
}
