package com.ssafy.thorp.backtest.domain.vo;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 백테스트 핵심 지표 집합. 값은 Worker가 지표 계산 규약({@code metricDefinitionVersion})에 따라 산출하고, 백엔드는 저장·조회만 한다.
 *
 * <p>nullable 지표의 {@code null}은 "계산 불가"(FR-011)를 뜻한다. 0이나 무한대로 대체하지 않는다.
 *
 * <p>최대 낙폭 구간(FR-008): {@code maxDrawdown}이 음수이면 고점일·저점일은 항상 있고, 회복일만 미회복 시 {@code null}이다. {@code
 * maxDrawdown}이 0(하락 없음) 또는 {@code null}이면 세 날짜 모두 {@code null}이며 "구간 없음"을 뜻한다.
 *
 * <p>이 클래스는 지표를 다시 계산하지 않고, 저장된 값 사이의 정합(부호·범위·날짜 순서)만 검사한다.
 */
public record PerformanceSummary(
        long initialEquity,
        long finalEquity,
        Double totalReturn,
        Double cagr,
        Double maxDrawdown,
        LocalDate maxDrawdownPeakDate,
        LocalDate maxDrawdownTroughDate,
        LocalDate maxDrawdownRecoveryDate,
        Double volatility,
        Double sharpeRatio,
        int tradeCount,
        Double winRate,
        Double profitFactor,
        String metricDefinitionVersion) {

    public PerformanceSummary {
        require(initialEquity > 0, "initialEquity must be positive: " + initialEquity);
        require(finalEquity >= 0, "finalEquity must not be negative: " + finalEquity);
        require(tradeCount >= 0, "tradeCount must not be negative: " + tradeCount);
        requireFinite(totalReturn, "totalReturn");
        requireFinite(cagr, "cagr");
        requireFinite(maxDrawdown, "maxDrawdown");
        requireFinite(volatility, "volatility");
        requireFinite(sharpeRatio, "sharpeRatio");
        requireFinite(winRate, "winRate");
        requireFinite(profitFactor, "profitFactor");
        require(
                maxDrawdown == null || maxDrawdown <= 0,
                "maxDrawdown must be zero or negative: " + maxDrawdown);
        require(
                volatility == null || volatility >= 0,
                "volatility must not be negative: " + volatility);
        require(
                winRate == null || (winRate >= 0 && winRate <= 1),
                "winRate must be between 0 and 1: " + winRate);
        require(
                profitFactor == null || profitFactor >= 0,
                "profitFactor must not be negative: " + profitFactor);
        Objects.requireNonNull(metricDefinitionVersion, "metricDefinitionVersion must not be null");
        require(!metricDefinitionVersion.isBlank(), "metricDefinitionVersion must not be blank");
        validateDrawdownPeriod(
                maxDrawdown, maxDrawdownPeakDate, maxDrawdownTroughDate, maxDrawdownRecoveryDate);
    }

    private static void validateDrawdownPeriod(
            Double maxDrawdown, LocalDate peak, LocalDate trough, LocalDate recovery) {
        if (maxDrawdown == null || maxDrawdown == 0) {
            require(
                    peak == null && trough == null && recovery == null,
                    "drawdown period dates must be null when maxDrawdown is " + maxDrawdown);
            return;
        }
        require(
                peak != null && trough != null,
                "maxDrawdownPeakDate and maxDrawdownTroughDate are required when maxDrawdown is"
                        + " negative");
        require(
                !peak.isAfter(trough),
                "maxDrawdownPeakDate must not be after maxDrawdownTroughDate: "
                        + peak
                        + " > "
                        + trough);
        require(
                recovery == null || recovery.isAfter(trough),
                "maxDrawdownRecoveryDate must be after maxDrawdownTroughDate: "
                        + recovery
                        + " <= "
                        + trough);
    }

    private static void requireFinite(Double value, String name) {
        require(value == null || Double.isFinite(value), name + " must be finite: " + value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
