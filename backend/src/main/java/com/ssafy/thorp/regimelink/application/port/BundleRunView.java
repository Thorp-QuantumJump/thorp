package com.ssafy.thorp.regimelink.application.port;

import com.ssafy.thorp.regimelink.domain.vo.LinkRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 공개 토큰 하나를 풀어 얻은 실행의 판정 입력 (contracts §1 {@code bundle.runs}).
 *
 * <p>가용성 판정에 필요한 값만 담는다 — 성과 수치는 들어오지 않는다. 필터 정의가 성과에 의존하지 않아야 하고(FR-003), 판정에 쓰지 않는 값을 들고 다니면 그 값이
 * 응답에 새는 길이 생긴다.
 *
 * @param requestId 공개 토큰의 분석 요청. 005 정리 작업이 지울 수 있어 저장할 때는 {@code ON DELETE SET NULL}이다
 * @param universe 이 실행이 고른 (섹터, 종목) 쌍. 접수 기록이 없는 실행은 비어 있다
 * @param end 요청한 분석 종료일. 응답의 {@code period.end}로 그대로 나간다
 * @param effectiveStart 워밍업을 소진하고 평가가 시작되는 첫 거래일. {@code OUT_OF_PERIOD} 판정의 기준이다
 * @param resultEnd 이 실행의 결과가 실제로 닿는 마지막 거래일. 취소된 실행은 요청 종료일보다 앞이고, 한 거래일도 돌지 않았으면 {@code null}이다
 * @param completed 입력 실행이 끝났는가. 아니면 계산은 {@code PENDING_RUN}으로 기다린다(M5)
 * @param initialCash 시작 금액. 비교 묶음 조건 대조(T093)와 {@code FRESH_CASH} 기준값이다
 * @param costProfileVersion 비용 조건 버전. 조건 대조에만 쓰고 값은 004 등록표가 소유한다
 * @param dataVersion 이 실행에 고정된 자료 버전. 조건 대조와 작업 메시지 {@code marketData}에 쓰인다
 */
public record BundleRunView(
        String publicToken,
        LinkRole role,
        Long requestId,
        Long runId,
        String strategyId,
        String strategyName,
        String mappingVersion,
        BigDecimal initialCash,
        String costProfileVersion,
        String dataVersion,
        List<UniverseEntry> universe,
        LocalDate start,
        LocalDate end,
        LocalDate effectiveStart,
        LocalDate resultEnd,
        int warmupTradingDays,
        boolean completed) {

    /**
     * 실행이 고른 섹터와 그 대표 종목 한 쌍.
     *
     * <p>섹터가 없는 행이 있을 수 있다 — 접수 경로를 거치지 않은 옛 실행은 종목만 남긴다. 그런 행은 대응표 판정에서 건너뛴다.
     */
    public record UniverseEntry(String sectorId, String instrumentId) {

        public UniverseEntry {
            Objects.requireNonNull(instrumentId, "instrumentId must not be null");
        }
    }

    public BundleRunView {
        Objects.requireNonNull(publicToken, "publicToken must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        universe = universe == null ? List.of() : List.copyOf(universe);
    }

    /**
     * 이 실행의 결과가 사후 구간 전체를 덮는가.
     *
     * <p>덮지 못하면 {@code OUT_OF_PERIOD}다 — 원천 자료는 충분하므로 사용자가 확인하면 전체 기간으로 다시 계산할 수 있다(Clarifications
     * 09-18 Q1). 평가 시작 전의 구간은 실행이 값을 갖지 않으므로 {@code effectiveStart}로 본다.
     *
     * <p><b>끝은 요청 종료일이 아니라 {@link #resultEnd}로 본다.</b> 취소된 실행은 요청한 기간을 다 돌지 않았고, 그 사실을 보지 않으면 결과가
     * 없는 구간을 "덮는다"고 판정해 계산할 수 없는 사례를 정상으로 보이게 한다.
     */
    public boolean covers(LocalDate windowStart, LocalDate windowEnd) {
        if (effectiveStart == null
                || resultEnd == null
                || windowStart == null
                || windowEnd == null) {
            return false;
        }
        return !effectiveStart.isAfter(windowStart) && !resultEnd.isBefore(windowEnd);
    }
}
