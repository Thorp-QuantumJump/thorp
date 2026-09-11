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
 * <p>세 가지가 겹쳐 시작일을 뒤로 민다. ① 요청 시작일 ② 가장 늦게 상장한 종목의 상장일(그 전에는 공통 비교가 불가능하다) ③ 전략이 첫 신호를 내기까지 필요한 관측
 * 거래일 수. 셋을 순서대로 적용한 날짜가 {@code effectiveStart}이고, 같은 값이 동일 버전 키에도 들어간다(R-03).
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
     * @param commonStart 상장일까지 반영한 공통 평가 가능 시작일. 진단용
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
        int required = requiredWarmupTradingDays(rules);
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

        LocalDate commonStart = commonStart(instrumentIds, requestedStart);
        LocalDate effectiveStart =
                marketMetadata
                        .evaluationStart(commonStart, effectiveWarmup)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INSUFFICIENT_HISTORY,
                                                "%s부터 워밍업 %d거래일을 채울 자료가 없습니다."
                                                        .formatted(commonStart, effectiveWarmup)));
        if (effectiveStart.isAfter(requestedEnd)) {
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_HISTORY,
                    "워밍업 %d거래일을 채우면 평가 시작일이 %s로 요청 종료일 %s를 넘습니다. 시작일을 앞당기거나 기간을 늘려 주세요."
                            .formatted(effectiveWarmup, effectiveStart, requestedEnd));
        }
        return new EffectivePeriod(effectiveStart, requestedEnd, effectiveWarmup, commonStart);
    }

    /**
     * 전략이 첫 신호를 내기까지 필요한 관측 거래일 수.
     *
     * <p>등록표 명세에서 읽는다 — 코드에 전략별 숫자를 박으면 규칙 버전을 올려도 값이 따라오지 않는다. 명세에 관측 길이가 없는 전략(고정 비중·동일 비중)은 0이다.
     */
    public static int requiredWarmupTradingDays(StrategyRules rules) {
        Map<String, Object> spec = rules.spec();
        if (spec == null) {
            return 0;
        }
        return intValue(spec, "lookbackDays") + intValue(spec, "skipRecentDays");
    }

    private static int intValue(Map<String, Object> spec, String key) {
        return Optional.ofNullable(spec.get(key))
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).intValue())
                .orElse(0);
    }

    /** 가장 늦게 상장한 종목이 공통 구간의 시작을 정한다(원칙 IV). */
    private LocalDate commonStart(List<String> instrumentIds, LocalDate requestedStart) {
        LocalDate start = requestedStart;
        for (String instrumentId : instrumentIds) {
            LocalDate listedOn =
                    marketMetadata
                            .listedOn(instrumentId)
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.INVALID_INPUT,
                                                    "모르는 종목입니다: " + instrumentId));
            if (listedOn.isAfter(start)) {
                start = listedOn;
            }
        }
        return start;
    }
}
