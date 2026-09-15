package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 워밍업을 반영한 실제 평가 구간(spec 004 research R-15).
 *
 * <p><b>평가 시작일 = max(요청 시작일, 자료가 시작된 뒤 워밍업 {@code warmup}번째 거래일)</b>. 여기서 "자료가 시작된 뒤"는 가장 늦게 상장한
 * 종목의 상장일이다 — 그 전에는 공통 비교가 불가능하다. 001 {@code contracts/evaluation-window.md}가 정한 {@code
 * commonStart} 산식이 바로 뒤쪽 항이고, 산식의 기준점에 <b>요청 시작일은 들어가지 않는다</b>. 그 결과가 {@code effectiveStart}이고 같은
 * 값이 동일 버전 키에도 들어간다(R-03).
 *
 * <p><b>워밍업은 요청 구간을 깎지 않는다.</b> 요청 시작일 앞에 워밍업 자료가 있으면 워커가 요청 구간보다 앞에서 읽는다(001 {@code
 * contracts/market-dataset.md} 「읽기 규칙」). 그래서 2020-01-01부터 보고 싶다는 요청은 2020-01-01부터 평가한다. 상장일이 요청보다
 * 늦을 때만 앞에 읽을 자료가 없으므로 밀린다. 007 {@code CommonPeriodResolver}가 같은 규칙을 쓴다.
 *
 * <p><b>이 계산기는 데이터셋이 언제 시작하는지 모른다.</b> 계약이 요구하는 기준점은 사실 {@code max(상장일, 데이터셋 첫 거래일)}인데 {@link
 * com.ssafy.thorp.backtest.application.port.MarketMetadataPort}에 자료 시작일을 묻는 길이 없어 앞쪽 항만 쓴다. 픽스처에서
 * 둘은 8년 어긋나 있다 — 어댑터 상장일은 2015·2018이고 워커가 읽는 자료는 2023-01-02부터다({@code MarketFixtureAlignmentTest}가
 * 이 사실을 붙박는다). <b>그래서 이 클래스가 낸 {@code effectiveStart} 앞에 워밍업 자료가 없을 수 있다.</b>
 *
 * <p>막는 곳은 워커 {@code engine_runner.evaluation_start()}다 — 읽어 온 달력으로 {@code max(앵커,
 * calendar[warmup])}를 한 번 더 적용한다. 워커의 "자료가 시작된 뒤"는 <b>읽어 온 가격 행의 합집합 첫날</b>이라 기준점이 여기와 다르다. <b>이 값이
 * 작업 메시지에 늘 실리므로 워커의 되밀기는 모든 실행에 걸린다</b> — 늘 실린다는 것이 안심의 근거가 아니라, 워커 쪽 바닥이 없으면 모든 실행이 위험해진다는 뜻이다.
 *
 * <p><b>남는 한계</b>: 되밀기가 일하면 사용자에게 보여 준 {@code effectiveStart}와 실제 평가 기간이 갈린다. 실패하는 것보다 낫지만 완전한 해결은
 * 아니다. 근본 해결은 포트가 자료 시작일을 알려 주고 이 계산기가 {@code max(상장일, 데이터셋 첫 거래일)}에서 세는 것이며, 001의 자료 계층과 함께
 * 옮긴다(001 {@code tasks.md} T029).
 *
 * <p><b>요청값이 전략 요구값보다 작으면 조용히 올리지 않고 거절한다.</b> 비교 묶음(003 다섯 전략 화면, 007 자동 탐색)이 후보 전체의 최대 관측 길이를
 * 넘기기로 되어 있는데, 그보다 작은 값이 왔다는 것은 묶음 쪽 계산이 틀렸다는 신호다. {@code max()}로 고쳐 통과시키면 묶음 안 실행들의 {@code
 * effectiveStart}가 서로 어긋난 채 지나가고, 그러면 나란히 놓고 비교한 숫자가 사실은 다른 구간의 값이 된다.
 */
@Component
public class EffectivePeriodCalculator {

    private final MarketMetadataPort marketMetadata;

    public EffectivePeriodCalculator(MarketMetadataPort marketMetadata) {
        this.marketMetadata = marketMetadata;
    }

    /**
     * @param warmupTradingDays 서버가 확정한 실효 워밍업. 동일 버전 키에 들어간다
     * @param commonStart 001 {@code contracts/evaluation-window.md}의 {@code commonStart} — 가장 늦게
     *     상장한 종목의 상장일 이후 워밍업 {@code warmupTradingDays}번째 거래일. <b>요청 시작일은 이 값에 들어가지 않는다.</b> 진단용
     */
    public record EffectivePeriod(
            LocalDate effectiveStart,
            LocalDate effectiveEnd,
            int warmupTradingDays,
            LocalDate commonStart) {}

    /**
     * @param requestedWarmupTradingDays 요청 {@code conditions.warmupTradingDays}. null = 전략 값을 쓴다
     */
    public EffectivePeriod resolve(
            StrategyRules rules,
            List<String> instrumentIds,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            Integer requestedWarmupTradingDays) {
        return resolve(
                rules,
                null,
                instrumentIds,
                requestedStart,
                requestedEnd,
                requestedWarmupTradingDays);
    }

    /**
     * @param scenarioOverlay 시나리오 오버레이 규칙({@code TARGET_RISK_OVERLAY}). null = 주 분석. 오버레이의 관측 길이도
     *     워밍업에 들어간다 — 첫 판단일에 변동성을 추정할 수익률이 있어야 워커가 {@code ESTIMATION_FAILED} 없이 시작한다
     */
    public EffectivePeriod resolve(
            StrategyRules rules,
            StrategyRules scenarioOverlay,
            List<String> instrumentIds,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            Integer requestedWarmupTradingDays) {
        int required = requiredWarmupTradingDays(rules, scenarioOverlay);
        int warmup = required;
        if (requestedWarmupTradingDays != null) {
            if (requestedWarmupTradingDays < required) {
                throw new BusinessException(
                        ErrorCode.INSUFFICIENT_HISTORY,
                        "요청한 공통 워밍업 %d거래일이 %s 전략이 필요로 하는 %d거래일보다 짧습니다. 비교 묶음의 최대 관측 길이를 다시 계산해 주세요."
                                .formatted(
                                        requestedWarmupTradingDays, rules.strategyId(), required));
            }
            warmup = requestedWarmupTradingDays;
        }
        final int effectiveWarmup = warmup;

        LocalDate latestListed = latestListingDate(instrumentIds);
        // 계약의 commonStart: 워밍업 기준점은 상장일 하나다. 요청 시작일은 여기 들어가지 않는다.
        LocalDate commonStart =
                marketMetadata
                        .evaluationStart(latestListed, effectiveWarmup)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INSUFFICIENT_HISTORY,
                                                "가장 늦게 상장한 종목의 상장일 %s 이후 워밍업 %d거래일을 채울 거래일이 달력에 없습니다."
                                                        .formatted(latestListed, effectiveWarmup)));
        // max(요청 시작일, commonStart). 요청 시작일이 이기면 그 뒤 첫 거래일로 맞춰 준다 —
        // 주말을 평가 첫날로 삼으면 워커가 고르는 첫 거래일과 값이 갈린다.
        //
        // 다만 이 달력은 공휴일을 모른다(FixtureMarketMetadataAdapter). 그래서 요청 시작일이
        // 공휴일이면 여전히 워커와 갈린다 — 001이 실제 달력을 주기 전까지 남는 한계이고, 이
        // 스냅은 주말 때문에 갈리는 흔한 경우만 없앤다.
        boolean pushedByListing = !requestedStart.isAfter(commonStart);
        LocalDate effectiveStart =
                pushedByListing
                        ? commonStart
                        : marketMetadata
                                .evaluationStart(requestedStart, 0)
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.INSUFFICIENT_HISTORY,
                                                        "요청 시작일 %s 이후에 거래일이 없습니다."
                                                                .formatted(requestedStart)));
        if (effectiveStart.isAfter(requestedEnd)) {
            // 밀어낸 것이 상장일인지 요청 시작일인지에 따라 해야 할 일이 다르다. 워밍업이 0인데
            // "상장이 늦은 종목을 빼라"고 하면 틀린 조언이다.
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_HISTORY,
                    pushedByListing
                            ? "가장 늦은 상장일 %s 이후 워밍업 %d거래일을 채우고 나면 평가 시작일이 %s가 되어 요청 종료일 %s를 넘습니다. 종료일을 늦추거나 상장이 늦은 종목을 빼 주세요."
                                    .formatted(
                                            latestListed,
                                            effectiveWarmup,
                                            effectiveStart,
                                            requestedEnd)
                            : "요청 시작일 %s 이후 첫 거래일 %s가 요청 종료일 %s를 넘습니다. 기간을 다시 골라 주세요."
                                    .formatted(requestedStart, effectiveStart, requestedEnd));
        }
        return new EffectivePeriod(effectiveStart, requestedEnd, effectiveWarmup, commonStart);
    }

    /**
     * 전략이 첫 신호를 내기까지 필요한 관측 거래일 수.
     *
     * <p>등록표 명세에서 읽는다 — 코드에 전략별 숫자를 박으면 규칙 버전을 올려도 값이 따라오지 않는다. 명세에 관측 길이가 없는 전략(고정 비중·동일 비중)은 0이다.
     */
    public static int requiredWarmupTradingDays(StrategyRules rules) {
        return requiredWarmupTradingDays(rules, null);
    }

    /** 전략 관측 길이와 시나리오 오버레이 관측 길이 중 긴 쪽. 둘은 같은 자료를 보므로 더하지 않는다. */
    public static int requiredWarmupTradingDays(
            StrategyRules rules, StrategyRules scenarioOverlay) {
        int strategy = lookback(rules) + skipRecent(rules);
        int overlay = scenarioOverlay == null ? 0 : lookback(scenarioOverlay);
        return Math.max(strategy, overlay);
    }

    private static int lookback(StrategyRules rules) {
        return rules.spec() == null ? 0 : intValue(rules.spec(), "lookbackDays");
    }

    private static int skipRecent(StrategyRules rules) {
        return rules.spec() == null ? 0 : intValue(rules.spec(), "skipRecentDays");
    }

    private static int intValue(Map<String, Object> spec, String key) {
        return Optional.ofNullable(spec.get(key))
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).intValue())
                .orElse(0);
    }

    /** 가장 늦게 상장한 종목이 공통 구간의 시작을 정한다(원칙 IV). 요청 시작일은 섞지 않는다 — 워밍업 기준점은 상장일이다. */
    private LocalDate latestListingDate(List<String> instrumentIds) {
        LocalDate latest = null;
        for (String instrumentId : instrumentIds) {
            LocalDate listedOn =
                    marketMetadata
                            .listedOn(instrumentId)
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.INVALID_INPUT,
                                                    "모르는 종목입니다: " + instrumentId));
            if (latest == null || listedOn.isAfter(latest)) {
                latest = listedOn;
            }
        }
        if (latest == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "종목이 하나도 없습니다.");
        }
        return latest;
    }
}
