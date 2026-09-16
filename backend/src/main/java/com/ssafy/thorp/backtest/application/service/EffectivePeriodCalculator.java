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
 * <p><b>평가 시작일 = 요청 시작일 당일 또는 그 뒤 첫 거래일이다.</b> 워밍업만큼 뒤로 밀지 않는다. 워밍업 자료는 요청 구간보다 <b>앞에서</b> 읽고 앞이
 * 모자라면 있는 만큼만 읽기 때문이다(001 {@code contracts/market-dataset.md} 「읽기 규칙」). 그래서 2020-01-01부터 보고 싶다는 요청은
 * 2020-01-01부터 평가한다. 요청 시작일을 거래일에 맞추기만 하는 것은, 주말을 평가 첫날로 삼으면 워커가 고르는 첫 거래일과 값이 갈리기 때문이다.
 *
 * <p><b>미는 판정은 워커가 단독으로 한다.</b> 성과를 집계하기 시작하는 날은 {@code max(앵커, calendar[warmup])}이고 그 판정은 워커
 * {@code engine_runner.evaluation_start()}의 몫이다(같은 계약). 백엔드가 여기서 먼저 밀면 그 값이 앵커로 실려 나가 <b>워커가 되돌릴 수
 * 없다</b> — 밀린 날짜를 받은 워커는 그것이 요청 시작일인지 이미 밀린 값인지 구별하지 못한다. 그래서 판정하는 곳을 한 군데로 둔다. 앞이 모자라면 워커가 모자란 만큼만
 * 미루고, 읽어 온 달력 전체로도 워밍업을 채우지 못하면 그때 {@code MarketDataError}로 멈춘다.
 *
 * <p><b>{@code commonStart}는 계속 계산하되 {@code effectiveStart}를 정하는 데 쓰지 않는다.</b> 001 {@code
 * contracts/evaluation-window.md}가 정한 "후보 집합을 평가할 수 있는 가장 이른 날"이고, 003 화면의 "이 섹터를 빼면 기간이 늘어납니다" 안내와
 * 007의 탐색 구간 산출이 그 값을 쓴다(FR-007·FR-008). 여기서는 진단용으로 싣고, <b>그 값이 요청 종료일보다 뒤일 때만</b> 거절의 근거가 된다 — 요청
 * 구간 안에 이 후보 집합으로 평가할 수 있는 날이 하루도 없다는 뜻이라 워커에게 보내도 결과가 나오지 않는다.
 *
 * <p><b>이 계산기는 데이터셋이 언제 시작하는지 모른다.</b> {@link
 * com.ssafy.thorp.backtest.application.port.MarketMetadataPort}에 자료 시작일을 묻는 길이 없어 {@code
 * commonStart}의 기준점도 상장일 하나다. 픽스처에서 둘은 8년 어긋나 있다 — 어댑터 상장일은 2015·2018이고 워커가 읽는 자료는
 * 2023-01-02부터다({@code MarketFixtureAlignmentTest}가 이 사실을 붙박는다). 요청 시작일을 그대로 쓰는 지금 규칙에서는 이 한계가
 * {@code effectiveStart}를 틀리게 하지 않는다 — 사용자가 자료보다 앞을 고르면 워커가 되민다. 근본 해결은 포트가 자료 시작일을 알려 주는 것이며 001의
 * 자료 계층과 함께 옮긴다(001 {@code tasks.md} T029).
 *
 * <p><b>남는 한계</b>: 되밀기가 일하면 사용자에게 보여 준 {@code effectiveStart}와 실제 평가 기간이 갈린다. 실패하는 것보다 낫지만 완전한 해결은
 * 아니다. 픽스처 달력이 공휴일을 모르는 것도 같은 성격의 한계다.
 *
 * <p><b>요청값이 전략 요구값보다 작으면 조용히 올리지 않고 거절한다.</b> 비교 묶음(003 다섯 전략 화면, 007 자동 탐색)이 후보 전체의 최대 관측 길이를
 * 넘기기로 되어 있는데, 그보다 작은 값이 왔다는 것은 묶음 쪽 계산이 틀렸다는 신호다. {@code max()}로 고쳐 통과시키면 묶음 안 실행들의 워밍업이 서로 어긋난 채
 * 지나가고, 그러면 나란히 놓고 비교한 숫자가 사실은 다른 관측 길이로 낸 값이 된다.
 */
@Component
public class EffectivePeriodCalculator {

    private final MarketMetadataPort marketMetadata;

    public EffectivePeriodCalculator(MarketMetadataPort marketMetadata) {
        this.marketMetadata = marketMetadata;
    }

    /**
     * @param effectiveStart 요청 시작일 당일 또는 그 뒤 첫 거래일. <b>워밍업만큼 밀린 값이 아니다</b> — 미는 일은 워커가 한다
     * @param warmupTradingDays 서버가 확정한 실효 워밍업. 동일 버전 키에 들어간다
     * @param commonStart 001 {@code contracts/evaluation-window.md}의 {@code commonStart} — 가장 늦게
     *     상장한 종목의 상장일 이후 워밍업 {@code warmupTradingDays}번째 거래일. <b>요청 시작일은 이 값에 들어가지 않고, 이 값도 {@code
     *     effectiveStart}에 들어가지 않는다.</b> 진단용이며 요청 종료일을 넘을 때만 거절의 근거가 된다
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
        // 요청 시작일을 거래일에 맞추기만 한다. commonStart 와 max 하지 않는다 — 여기서 밀면 그
        // 값이 앵커로 실려 나가 워커가 되돌릴 수 없다(클래스 주석).
        LocalDate effectiveStart =
                marketMetadata
                        .firstTradingDayOnOrAfter(requestedStart)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INSUFFICIENT_HISTORY,
                                                "요청 시작일 %s 이후에 거래일이 없습니다."
                                                        .formatted(requestedStart)));
        // 밀지 않게 됐어도 거절해야 하는 경계는 남는다. 요청 구간 안에 이 후보 집합으로 평가할 수
        // 있는 날이 하루도 없으면 보내 봐야 결과가 나오지 않는다 — 워커도 읽어 온 달력으로 워밍업을
        // 채우지 못하면 MarketDataError 로 멈춘다. 접수에서 사유를 말해 주는 편이 낫다.
        if (commonStart.isAfter(requestedEnd)) {
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_HISTORY,
                    "가장 늦은 상장일 %s 이후 워밍업 %d거래일을 채우고 나면 평가할 수 있는 첫날이 %s라 요청 종료일 %s를 넘습니다. 종료일을 늦추거나 상장이 늦은 종목을 빼 주세요."
                            .formatted(latestListed, effectiveWarmup, commonStart, requestedEnd));
        }
        // 요청 구간에 거래일이 아예 없는 경우다. 워밍업이 0이어도 걸리므로 "상장이 늦은 종목을
        // 빼라"는 조언을 여기 쓰면 틀린다.
        if (effectiveStart.isAfter(requestedEnd)) {
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_HISTORY,
                    "요청 시작일 %s 이후 첫 거래일 %s가 요청 종료일 %s를 넘습니다. 기간을 다시 골라 주세요."
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

    /** 가장 늦게 상장한 종목이 {@code commonStart}를 정한다(원칙 IV). 요청 시작일은 섞지 않는다 — 워밍업 기준점은 상장일이다. */
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
