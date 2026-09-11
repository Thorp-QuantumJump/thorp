package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.domain.model.RulesStatus;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.backtest.infrastructure.marketdata.FixtureMarketMetadataAdapter;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 워밍업을 반영한 실제 평가 구간(spec 004 research R-15).
 *
 * <p>여기가 흔들리면 <b>비교가 무너진다</b>. 나란히 놓고 보는 두 전략의 평가 시작일이 다르면 숫자 차이가 전략 차이인지 구간 차이인지 알 수 없다.
 */
class EffectivePeriodCalculatorTest {

    private static final String SEMICONDUCTOR = "091160.XKRX";
    private static final String BIO = "244580.XKRX";

    /** 픽스처 달력과 상장일을 그대로 쓴다 — 가짜 포트를 따로 만들면 실제 구현과 달력이 갈린다. */
    private final EffectivePeriodCalculator calculator =
            new EffectivePeriodCalculator(
                    new FixtureMarketMetadataAdapter(LocalDate.of(2026, 12, 31)));

    private static StrategyRules rules(String strategyId, Integer lookbackDays) {
        Map<String, Object> spec =
                lookbackDays == null ? Map.of() : Map.of("lookbackDays", lookbackDays);
        return new StrategyRules(strategyId, "1", RulesStatus.REGISTERED, false, spec, null);
    }

    private static final StrategyRules FIXED_WEIGHT = rules("FIXED_WEIGHT", null);
    private static final StrategyRules INVERSE_VOLATILITY = rules("INVERSE_VOLATILITY", 63);

    @Test
    @DisplayName("워밍업이 없는 전략은 요청 시작일부터 바로 평가한다")
    void noWarmupStartsAtRequestedStart() {
        EffectivePeriodCalculator.EffectivePeriod period =
                calculator.resolve(
                        FIXED_WEIGHT,
                        List.of(SEMICONDUCTOR),
                        LocalDate.of(2024, 1, 2),
                        LocalDate.of(2024, 12, 31),
                        null);

        assertThat(period.effectiveStart()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(period.warmupTradingDays()).isZero();
    }

    @Test
    @DisplayName("전략이 관측 기간을 요구하면 그만큼 거래일을 소진한 뒤 시작한다")
    void warmupPushesStartForward() {
        EffectivePeriodCalculator.EffectivePeriod period =
                calculator.resolve(
                        INVERSE_VOLATILITY,
                        List.of(SEMICONDUCTOR),
                        LocalDate.of(2024, 1, 2),
                        LocalDate.of(2024, 12, 31),
                        null);

        assertThat(period.warmupTradingDays()).isEqualTo(63);
        assertThat(period.effectiveStart()).isAfter(LocalDate.of(2024, 1, 2));
    }

    @Test
    @DisplayName("가장 늦게 상장한 종목이 공통 시작을 정한다")
    void latestListingDominates() {
        EffectivePeriodCalculator.EffectivePeriod period =
                calculator.resolve(
                        FIXED_WEIGHT,
                        List.of(SEMICONDUCTOR, BIO),
                        LocalDate.of(2023, 1, 2),
                        LocalDate.of(2024, 12, 31),
                        null);

        assertThat(period.commonStart()).isEqualTo(LocalDate.of(2023, 7, 3));
        assertThat(period.effectiveStart()).isEqualTo(LocalDate.of(2023, 7, 3));
    }

    // --- R-15 경계 세 가지 ---------------------------------------------------

    @Test
    @DisplayName("요청 워밍업이 전략 요구값과 같으면 통과하고 그 값을 쓴다")
    void requestedEqualToRequiredPasses() {
        EffectivePeriodCalculator.EffectivePeriod period =
                calculator.resolve(
                        INVERSE_VOLATILITY,
                        List.of(SEMICONDUCTOR),
                        LocalDate.of(2024, 1, 2),
                        LocalDate.of(2024, 12, 31),
                        63);

        assertThat(period.warmupTradingDays()).isEqualTo(63);
    }

    @Test
    @DisplayName("요청 워밍업이 요구값보다 작으면 올리지 않고 422다 — 묶음 최대값 계산이 틀렸다는 신호다")
    void requestedBelowRequiredIsRejected() {
        assertThatThrownBy(
                        () ->
                                calculator.resolve(
                                        INVERSE_VOLATILITY,
                                        List.of(SEMICONDUCTOR),
                                        LocalDate.of(2024, 1, 2),
                                        LocalDate.of(2024, 12, 31),
                                        20))
                .isInstanceOf(BusinessException.class)
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_HISTORY);
    }

    @Test
    @DisplayName("요청 워밍업이 요구값보다 크면 요청값을 쓴다")
    void requestedAboveRequiredWins() {
        EffectivePeriodCalculator.EffectivePeriod period =
                calculator.resolve(
                        INVERSE_VOLATILITY,
                        List.of(SEMICONDUCTOR),
                        LocalDate.of(2024, 1, 2),
                        LocalDate.of(2024, 12, 31),
                        120);

        assertThat(period.warmupTradingDays()).isEqualTo(120);
    }

    @Test
    @DisplayName("같은 시작일·같은 공통 워밍업이면 전략이 달라도 평가 시작일이 같다 (비교의 전제)")
    void sameWarmupGivesSameStartAcrossStrategies() {
        LocalDate start = LocalDate.of(2024, 1, 2);
        LocalDate end = LocalDate.of(2024, 12, 31);

        LocalDate fixed =
                calculator
                        .resolve(FIXED_WEIGHT, List.of(SEMICONDUCTOR), start, end, 63)
                        .effectiveStart();
        LocalDate inverse =
                calculator
                        .resolve(INVERSE_VOLATILITY, List.of(SEMICONDUCTOR), start, end, 63)
                        .effectiveStart();

        assertThat(fixed).isEqualTo(inverse);
    }

    @Test
    @DisplayName("워밍업을 채우면 종료일을 넘는 기간은 422다")
    void warmupBeyondEndIsRejected() {
        assertThatThrownBy(
                        () ->
                                calculator.resolve(
                                        INVERSE_VOLATILITY,
                                        List.of(SEMICONDUCTOR),
                                        LocalDate.of(2024, 11, 1),
                                        LocalDate.of(2024, 12, 31),
                                        null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("요청 종료일");
    }

    @Test
    @DisplayName("모르는 종목은 400이다")
    void unknownInstrumentIsRejected() {
        assertThatThrownBy(
                        () ->
                                calculator.resolve(
                                        FIXED_WEIGHT,
                                        List.of("000000.XKRX"),
                                        LocalDate.of(2024, 1, 2),
                                        LocalDate.of(2024, 12, 31),
                                        null))
                .isInstanceOf(BusinessException.class)
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
