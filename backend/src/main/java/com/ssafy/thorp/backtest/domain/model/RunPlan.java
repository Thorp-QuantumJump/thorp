package com.ssafy.thorp.backtest.domain.model;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * 접수 시점에 서버가 확정해 실행에 고정하는 값(spec 004 R-03·R-15).
 *
 * <p>{@link RunState}가 "실행되면서 바뀌는 것"이라면 이쪽은 "접수에서 정해지고 끝까지 바뀌지 않는 것"이다. 둘을 나눈 이유는 재현 때문이다 — 여기 있는
 * 값이 하나라도 달랐으면 다른 숫자가 나왔을 것이고, 그래서 이 값들이 {@code dedupKey}를 이룬다.
 *
 * @param dedupKey 동일 버전 키(R-03). 이 값이 같으면 재계산 결과가 같다고 선언하는 것이다
 * @param rulesVersion 적용한 전략 규칙 버전({@code strategy_rules})
 * @param costProfileVersion 적용한 비용 조건 버전({@code cost_profile})
 * @param dataVersion 적용한 시장 자료 버전(001 {@code dataset_version})
 * @param effectiveStart 워밍업을 소진한 뒤 실제 평가가 시작되는 거래일(R-15). 202 응답과 조회에 그대로 나간다
 * @param effectiveEnd 평가 종료일. 지금은 요청 종료일과 같고, 자료 마감으로 당겨질 여지를 위해 따로 둔다
 * @param warmupTradingDays 서버가 확정한 실효 워밍업 거래일 수. 키에 들어가므로 같이 보관한다
 * @param requestSpec 접수한 요청의 canonical 사본. 조회 {@code config}가 이것을 그대로 돌려준다 — 섹터·목표 비중처럼 실행 스칼라 컬럼에
 *     자리가 없는 값이 여기 있다
 * @param versions 결과에 표시할 버전 묶음(비용 조건·규칙·자료·엔진)
 */
public record RunPlan(
        String dedupKey,
        String rulesVersion,
        String costProfileVersion,
        String dataVersion,
        LocalDate effectiveStart,
        LocalDate effectiveEnd,
        Integer warmupTradingDays,
        Map<String, Object> requestSpec,
        Map<String, Object> versions) {

    public RunPlan {
        if (dedupKey != null && dedupKey.length() > 64) {
            throw new IllegalArgumentException("dedupKey must be at most 64 characters");
        }
        if (warmupTradingDays != null && warmupTradingDays < 0) {
            throw new IllegalArgumentException(
                    "warmupTradingDays must not be negative: " + warmupTradingDays);
        }
        if (effectiveStart != null
                && effectiveEnd != null
                && effectiveStart.isAfter(effectiveEnd)) {
            throw new IllegalArgumentException(
                    "effectiveStart must not be after effectiveEnd: "
                            + effectiveStart
                            + " > "
                            + effectiveEnd);
        }
        requestSpec = requestSpec == null ? null : Map.copyOf(requestSpec);
        versions = versions == null ? null : Map.copyOf(versions);
    }

    /**
     * 접수 경로를 거치지 않은 실행(T003까지 만들어진 행, 테스트 픽스처)의 빈 계획.
     *
     * <p>이 값이 남아 있는 실행은 재사용 판정에 걸리지 않는다 — {@code dedupKey}가 없으면 같은 조건인지 말할 수 없기 때문이다.
     */
    public static RunPlan empty() {
        return new RunPlan(null, null, null, null, null, null, null, null, null);
    }

    public boolean isReusable() {
        return dedupKey != null;
    }

    public Map<String, Object> requestSpecOrEmpty() {
        return Objects.requireNonNullElseGet(requestSpec, Map::of);
    }
}
