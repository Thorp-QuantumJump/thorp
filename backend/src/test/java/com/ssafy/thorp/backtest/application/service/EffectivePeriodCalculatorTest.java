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
    private static final String BATTERY = "305720.XKRX";
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

    /** 섹터 모멘텀 규칙표 행. 최근 제외 기본값은 0이고 003 축이 오면 그것이 이긴다(PD-03). */
    private static StrategyRules momentum(int lookbackDays, int skipRecentDays) {
        return new StrategyRules(
                "SECTOR_MOMENTUM",
                "1",
                RulesStatus.REGISTERED,
                false,
                Map.of("lookbackDays", lookbackDays, "skipRecentDays", skipRecentDays),
                null);
    }

    private static final StrategyRules FIXED_WEIGHT = rules("FIXED_WEIGHT", null);
    private static final StrategyRules INVERSE_VOLATILITY = rules("INVERSE_VOLATILITY", 63);

    @Test
    @DisplayName("화면이 고른 최근 제외 구간도 요구 워밍업에 들어간다 — 모멘텀은 L+S 다")
    void chosenSkipRecentDaysCountsTowardTheWarmup() {
        // 모멘텀 점수는 P(t-S)/P(t-S-L) 이라 L 만 확보하면 S 거래일이 모자라다. 규칙표 기본값(0)만
        // 보면 최근 제외 21일을 고른 요청의 요구값이 60으로 계산되고, 공통 워밍업 60이 통과한다 —
        // 그러면 초기 21거래일이 이력 부족으로 빠져 결과 구간과 수익률이 조용히 달라진다(PD-03 ⑤).
        StrategyRules rules = momentum(60, 0);
        Map<String, Object> chose21 = Map.of("rebalance", "MONTH_END", "skipRecentDays", 21);

        assertThatThrownBy(
                        () ->
                                calculator.resolve(
                                        rules,
                                        null,
                                        chose21,
                                        List.of(SEMICONDUCTOR),
                                        LocalDate.of(2024, 1, 2),
                                        LocalDate.of(2024, 12, 31),
                                        60))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_HISTORY)
                .hasMessageContaining("81");

        // 워밍업을 싣지 않은 요청은 실효 워밍업이 L+S 다 — 이 값이 그대로 워커에 실려 나간다.
        assertThat(
                        calculator
                                .resolve(
                                        rules,
                                        null,
                                        chose21,
                                        List.of(SEMICONDUCTOR),
                                        LocalDate.of(2024, 1, 2),
                                        LocalDate.of(2024, 12, 31),
                                        null)
                                .warmupTradingDays())
                .isEqualTo(81);

        // 축을 고르지 않으면 규칙표 값이 기본값이다.
        assertThat(
                        calculator
                                .resolve(
                                        momentum(60, 21),
                                        null,
                                        Map.of("rebalance", "MONTH_END"),
                                        List.of(SEMICONDUCTOR),
                                        LocalDate.of(2024, 1, 2),
                                        LocalDate.of(2024, 12, 31),
                                        null)
                                .warmupTradingDays())
                .isEqualTo(81);
    }

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
    @DisplayName("목표 위험 오버레이의 관측 기간도 워밍업에 들어간다 — 첫 판단일에 추정할 수익률이 있어야 한다")
    void targetRiskOverlayLookbackCountsAsWarmup() {
        StrategyRules overlay = rules("TARGET_RISK_OVERLAY", 63);

        EffectivePeriodCalculator.EffectivePeriod period =
                calculator.resolve(
                        FIXED_WEIGHT,
                        overlay,
                        List.of(SEMICONDUCTOR),
                        LocalDate.of(2024, 1, 2),
                        LocalDate.of(2024, 12, 31),
                        null);

        assertThat(period.warmupTradingDays()).isEqualTo(63);
        // 2015년 상장 종목이라 2024-01-02 앞에 워밍업 63거래일이 넉넉하다 — 시작일은 밀리지 않는다.
        assertThat(period.effectiveStart()).isEqualTo(LocalDate.of(2024, 1, 2));
        // 전략과 오버레이는 같은 자료를 보므로 더하지 않고 긴 쪽을 쓴다.
        assertThat(
                        EffectivePeriodCalculator.requiredWarmupTradingDays(
                                INVERSE_VOLATILITY, rules("TARGET_RISK_OVERLAY", 20)))
                .isEqualTo(63);
    }

    @Test
    @DisplayName("화면이 고른 관측 기간이 규칙표보다 길면 요구값도 그것으로 잰다 — 짧은 공통 워밍업은 422다")
    void chosenLookbackDecidesTheWarmupRequirement() {
        // 최소분산 규칙표 기본값은 120이고 003 축은 60·120·250이다. 250을 고른 요청에 워밍업 120이
        // 실려 오면 규칙표만 보는 판정은 통과시킨다 — 워커는 120거래일만 읽고 250거래일로 계산해
        // 초반 종목이 INSUFFICIENT_HISTORY 로 빠진 현금 포트폴리오를 정상 완료로 저장한다.
        StrategyRules minimumVariance = rules("MINIMUM_VARIANCE", 120);

        assertThatThrownBy(
                        () ->
                                calculator.resolve(
                                        minimumVariance,
                                        null,
                                        Map.of("rebalance", "MONTH_END", "lookbackDays", 250),
                                        List.of(SEMICONDUCTOR),
                                        LocalDate.of(2024, 1, 2),
                                        LocalDate.of(2024, 12, 31),
                                        120))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_HISTORY)
                .hasMessageContaining("250");

        // 워밍업을 싣지 않은 요청(003 화면의 보통 경로)은 실효 워밍업이 고른 축 값이다.
        // 여기서 규칙표 값을 쓰면 그 120이 그대로 워커에 실려 나가, 워커는 120거래일만 읽고
        // 250거래일로 계산한다 — 사용자가 고른 조건과 다른 결과가 그 조건의 결과로 저장된다.
        assertThat(
                        calculator
                                .resolve(
                                        minimumVariance,
                                        null,
                                        Map.of("rebalance", "MONTH_END", "lookbackDays", 250),
                                        List.of(SEMICONDUCTOR),
                                        LocalDate.of(2024, 1, 2),
                                        LocalDate.of(2024, 12, 31),
                                        null)
                                .warmupTradingDays())
                .isEqualTo(250);

        // 축을 고르지 않은 요청은 규칙표 값이 요구값이다 — 같은 워밍업이 그대로 통과한다.
        assertThat(
                        calculator
                                .resolve(
                                        minimumVariance,
                                        null,
                                        Map.of("rebalance", "MONTH_END"),
                                        List.of(SEMICONDUCTOR),
                                        LocalDate.of(2024, 1, 2),
                                        LocalDate.of(2024, 12, 31),
                                        120)
                                .warmupTradingDays())
                .isEqualTo(120);
    }

    // --- 한 규칙: effectiveStart = 요청 시작일 당일 또는 그 뒤 첫 거래일 ---

    @Test
    @DisplayName("요청 시작일이 상장일보다 한참 뒤면 워밍업이 요청 구간을 깎지 않는다 — 앞에서 읽는다")
    void warmupBeforeTheRequestDoesNotPushTheStart() {
        // 2015-01-02 상장 종목을 2024-01-02 부터 요청한다. 워밍업 63거래일은 2023년에 멀쩡히 있으므로
        // 워커가 요청 구간 앞에서 읽는다(001 contracts/market-dataset.md 「읽기 규칙」). 예전 구현은
        // 이 63일을 요청 구간에서 깎아 2024년 4월부터 평가했다 — 한 해를 요청한 사용자가 아홉 달을 받았다.
        EffectivePeriodCalculator.EffectivePeriod period =
                calculator.resolve(
                        INVERSE_VOLATILITY,
                        List.of(SEMICONDUCTOR),
                        LocalDate.of(2024, 1, 2),
                        LocalDate.of(2024, 12, 31),
                        null);

        assertThat(period.warmupTradingDays()).isEqualTo(63);
        assertThat(period.effectiveStart()).isEqualTo(LocalDate.of(2024, 1, 2));
        // commonStart 는 계약 산식 그대로 상장일 기준이라 요청과 무관하게 2015년이다.
        assertThat(period.commonStart()).isBefore(LocalDate.of(2016, 1, 1));
    }

    @Test
    @DisplayName("요청 시작일이 상장일보다 앞이어도 백엔드는 밀지 않는다 — 미는 일은 워커 하나가 한다")
    void aLateListingNoLongerPushesTheStart() {
        // BIO 는 2023-07-03 상장이라 요청 앞부분에 읽을 자료가 없다. 그래도 여기서 밀지 않는다.
        // 밀어 놓으면 그 값이 앵커로 실려 나가 워커가 되돌릴 수 없고, 판정하는 곳이 둘이 된다
        // (001 contracts/market-dataset.md 「읽기 규칙」). 워커가 읽어 온 달력으로
        // max(앵커, calendar[warmup]) 을 한 번 더 적용해 모자란 만큼만 미룬다.
        EffectivePeriodCalculator.EffectivePeriod period =
                calculator.resolve(
                        INVERSE_VOLATILITY,
                        List.of(SEMICONDUCTOR, BIO),
                        LocalDate.of(2023, 1, 2),
                        LocalDate.of(2024, 12, 31),
                        null);

        assertThat(period.effectiveStart()).isEqualTo(LocalDate.of(2023, 1, 2));
        // 계약 commonStart 자체는 그대로 계산해 싣는다 — 003 의 "이 섹터를 빼면 기간이 늘어납니다"
        // 안내와 007 의 탐색 구간이 이 값을 쓴다. effectiveStart 를 정하는 데만 쓰지 않는다.
        assertThat(period.commonStart()).isEqualTo(LocalDate.of(2023, 9, 28));
    }

    @Test
    @DisplayName("상장일이 이른 종목이면 자료가 언제 시작하든 요청 시작일이 그대로 나간다 — 백엔드는 자료 시작일을 모른다")
    void effectiveStartCannotSeeWhenTheDataActuallyBegins() {
        // 워커가 읽는 픽스처 자료는 2023-01-02 에 시작한다(MarketFixtureAlignmentTest). 그런데 이
        // 계산기의 기준점은 상장일뿐이라 2023-02-01 요청에 그 날을 그대로 돌려준다 — 그 앞에 워밍업
        // 63거래일이 있는지는 보지 못한다(실제로는 22거래일밖에 없다).
        //
        // 그래서 워커가 읽어 온 달력으로 한 번 더 되민다. 이 검사와 워커의
        // test_warmup_floor_applies_when_the_backend_start_has_no_warmup_behind_it 가 짝이다.
        EffectivePeriodCalculator.EffectivePeriod period =
                calculator.resolve(
                        INVERSE_VOLATILITY,
                        List.of(SEMICONDUCTOR, BATTERY),
                        LocalDate.of(2023, 2, 1),
                        LocalDate.of(2023, 12, 29),
                        null);

        assertThat(period.warmupTradingDays()).isEqualTo(63);
        assertThat(period.effectiveStart()).isEqualTo(LocalDate.of(2023, 2, 1));
        // commonStart 는 늦은 상장일(BATTERY 2018-09-12) 기준이라 자료가 있기 4년 전이다.
        assertThat(period.commonStart()).isBefore(LocalDate.of(2019, 1, 1));
    }

    @Test
    @DisplayName("요청 시작일을 뒤로 옮겨도 commonStart 는 꿈쩍하지 않는다 — 기준점은 상장일 하나다")
    void commonStartIgnoresTheRequestedStart() {
        LocalDate expected = LocalDate.of(2023, 9, 28);

        assertThat(
                        calculator
                                .resolve(
                                        INVERSE_VOLATILITY,
                                        List.of(BIO),
                                        LocalDate.of(2023, 1, 2),
                                        LocalDate.of(2024, 12, 31),
                                        null)
                                .commonStart())
                .isEqualTo(expected);
        assertThat(
                        calculator
                                .resolve(
                                        INVERSE_VOLATILITY,
                                        List.of(BIO),
                                        LocalDate.of(2024, 6, 3),
                                        LocalDate.of(2024, 12, 31),
                                        null)
                                .commonStart())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("요청 시작일이 휴장일이면 그 뒤 첫 거래일로 맞춘다 — 워커가 고르는 날과 같아야 한다")
    void requestedStartIsSnappedToATradingDay() {
        EffectivePeriodCalculator.EffectivePeriod period =
                calculator.resolve(
                        FIXED_WEIGHT,
                        List.of(SEMICONDUCTOR),
                        LocalDate.of(2024, 6, 1), // 토요일
                        LocalDate.of(2024, 12, 31),
                        null);

        assertThat(period.effectiveStart()).isEqualTo(LocalDate.of(2024, 6, 3));
    }

    @Test
    @DisplayName("가장 늦게 상장한 종목이 commonStart 를 정한다 — 그래도 effectiveStart 는 요청 시작일이다")
    void latestListingDominatesCommonStartOnly() {
        // 워밍업이 0인 전략이라 commonStart 는 곧 가장 늦은 상장일(BIO 2023-07-03)이다.
        EffectivePeriodCalculator.EffectivePeriod period =
                calculator.resolve(
                        FIXED_WEIGHT,
                        List.of(SEMICONDUCTOR, BIO),
                        LocalDate.of(2023, 1, 2),
                        LocalDate.of(2024, 12, 31),
                        null);

        assertThat(period.commonStart()).isEqualTo(LocalDate.of(2023, 7, 3));
        assertThat(period.effectiveStart()).isEqualTo(LocalDate.of(2023, 1, 2));
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
    @DisplayName("commonStart 가 요청 종료일을 넘으면 422다 — 요청 구간 안에 평가할 수 있는 날이 없다")
    void commonStartBeyondEndIsRejected() {
        // BIO(2023-07-03 상장) + 관측 63거래일이면 이 후보 집합을 평가할 수 있는 첫날이 2023-09-28
        // 인데 요청은 8월에 끝난다. 밀지 않게 됐어도 이 경계는 남는다 — 보내 봐야 워커가 읽어 온
        // 달력으로도 워밍업을 못 채워 MarketDataError 로 멈춘다. 접수에서 사유를 말해 주는 편이 낫다.
        assertThatThrownBy(
                        () ->
                                calculator.resolve(
                                        INVERSE_VOLATILITY,
                                        List.of(SEMICONDUCTOR, BIO),
                                        LocalDate.of(2023, 1, 2),
                                        LocalDate.of(2023, 8, 31),
                                        null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("요청 종료일")
                .hasMessageContaining("상장이 늦은 종목을 빼 주세요");
    }

    @Test
    @DisplayName("요청 구간에 거래일이 하나도 없으면 422이고, 상장 탓이 아니므로 메시지가 다르다")
    void aRequestWithoutAnyTradingDayIsRejected() {
        // 2024-06-01 토요일 ~ 2024-06-02 일요일. 상장일은 2015년이라 commonStart 는 멀쩡하다.
        assertThatThrownBy(
                        () ->
                                calculator.resolve(
                                        FIXED_WEIGHT,
                                        List.of(SEMICONDUCTOR),
                                        LocalDate.of(2024, 6, 1),
                                        LocalDate.of(2024, 6, 2),
                                        null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("요청 시작일")
                .hasMessageNotContaining("상장이 늦은 종목");
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
