package com.ssafy.thorp.strategy.domain;

import com.ssafy.thorp.strategy.domain.model.ChangeHistoryEntry;
import com.ssafy.thorp.strategy.domain.model.MinimumHistoryRequirement;
import com.ssafy.thorp.strategy.domain.model.ParameterConstraint;
import com.ssafy.thorp.strategy.domain.model.ParameterDefinition;
import com.ssafy.thorp.strategy.domain.model.StrategyDefinition;
import com.ssafy.thorp.strategy.domain.vo.ChangeType;
import com.ssafy.thorp.strategy.domain.vo.ConstraintOperator;
import com.ssafy.thorp.strategy.domain.vo.EnumOption;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.domain.vo.NumericRange;
import com.ssafy.thorp.strategy.domain.vo.ParameterType;
import com.ssafy.thorp.strategy.domain.vo.ParameterValue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 계약 문서(`docs/specs/strategy-definition/`)의 정의 예시를 도메인 객체로 옮긴 픽스처.
 *
 * <p>네 전략의 파라미터 구성이 서로 다르다는 점이 중요하다. 개수는 0~4개, 타입은 INTEGER·DECIMAL·BOOLEAN·ENUM이 섞이고, 최소 이력 규칙도 네
 * 종류가 모두 나온다. 그래도 전부 같은 {@link ParameterDefinition} 목록으로 표현된다는 것을 테스트가 확인한다.
 */
public final class StrategyFixtures {

    private static final String SCHEMA_VERSION = "1.0.0";
    private static final Set<ExecutionScope> BOTH_SCOPES =
            Set.of(ExecutionScope.BACKTEST, ExecutionScope.LIVE);
    private static final Set<ExecutionScope> BACKTEST_ONLY = Set.of(ExecutionScope.BACKTEST);

    private StrategyFixtures() {}

    /** 파라미터 4개, 타입 3종(INTEGER·BOOLEAN·ENUM), 최소 이력은 MAX_PARAMETERS. */
    public static StrategyDefinition smaCrossover() {
        return new StrategyDefinition(
                SCHEMA_VERSION,
                "SMA_CROSSOVER",
                1,
                "이동평균 교차",
                "단기 이동평균과 장기 이동평균의 교차로 추세 전환을 포착한다.",
                "단기 이동평균이 장기 이동평균을 아래에서 위로 교차한 다음 거래일 시가에 전량 진입한다.",
                "단기 이동평균이 장기 이동평균을 위에서 아래로 교차한 다음 거래일 시가에 전량 청산한다.",
                BOTH_SCOPES,
                List.of(
                        integerParameter(
                                "shortPeriod",
                                "단기 이동평균 기간",
                                "단기 이동평균을 계산할 거래일 수.",
                                true,
                                null,
                                NumericRange.closed(bd("2"), bd("120"), BigDecimal.ONE)),
                        integerParameter(
                                "longPeriod",
                                "장기 이동평균 기간",
                                "장기 이동평균을 계산할 거래일 수.",
                                true,
                                null,
                                NumericRange.closed(bd("5"), bd("400"), BigDecimal.ONE)),
                        new ParameterDefinition(
                                "allowShort",
                                "공매도 허용",
                                "하향 교차 시 매도 포지션까지 잡을지 여부.",
                                ParameterType.BOOLEAN,
                                false,
                                ParameterValue.of(false),
                                null,
                                BOTH_SCOPES,
                                null,
                                null),
                        new ParameterDefinition(
                                "rebalancePeriod",
                                "신호 확인 주기",
                                "교차 여부를 확인하는 주기.",
                                ParameterType.ENUM,
                                false,
                                ParameterValue.ofEnum("DAILY"),
                                null,
                                BOTH_SCOPES,
                                null,
                                List.of(
                                        new EnumOption("DAILY", "매 거래일"),
                                        new EnumOption("WEEKLY", "매주"),
                                        new EnumOption("MONTHLY", "매월")))),
                List.of(
                        new ParameterConstraint(
                                "shortPeriod",
                                ConstraintOperator.LT,
                                "longPeriod",
                                "단기 이동평균 기간은 장기 이동평균 기간보다 짧아야 합니다.")),
                MinimumHistoryRequirement.maxOf(List.of("shortPeriod", "longPeriod"), 1),
                List.of(initialChange(1, LocalDate.of(2026, 9, 3))),
                false);
    }

    /** 파라미터 4개, DECIMAL 두 개와 배타 하한·상한 없는 범위 포함, 최소 이력은 PARAMETER. */
    public static StrategyDefinition rsiReversion() {
        return new StrategyDefinition(
                SCHEMA_VERSION,
                "RSI_REVERSION",
                1,
                "RSI 평균 회귀",
                "상대강도지수가 과매도 구간에 들어가면 반등을 기대해 진입하고 과매수 구간에서 청산한다.",
                "RSI가 과매도 임계값 아래로 내려간 다음 거래일 시가에 전량 진입한다.",
                "RSI가 과매수 임계값 위로 올라간 다음 거래일 시가에 전량 청산한다.",
                BOTH_SCOPES,
                List.of(
                        integerParameter(
                                "rsiPeriod",
                                "RSI 계산 기간",
                                "RSI를 계산할 거래일 수.",
                                true,
                                null,
                                NumericRange.closed(bd("2"), bd("60"), BigDecimal.ONE)),
                        decimalParameter(
                                "oversoldThreshold",
                                "과매도 임계값",
                                "이 값 아래로 내려가면 과매도로 본다.",
                                bd("30"),
                                null,
                                NumericRange.closed(bd("5"), bd("50"), bd("0.5"))),
                        decimalParameter(
                                "overboughtThreshold",
                                "과매수 임계값",
                                "이 값 위로 올라가면 과매수로 본다. 50은 포함하지 않는다.",
                                bd("70"),
                                null,
                                new NumericRange(bd("50"), false, bd("95"), true, null)),
                        integerParameter(
                                "maxHoldingBars",
                                "최대 보유 기간",
                                "과매수 신호가 오지 않아도 이 기간이 지나면 청산한다. 상한이 없다.",
                                false,
                                ParameterValue.of(20L),
                                new NumericRange(
                                        BigDecimal.ONE, true, null, true, BigDecimal.ONE))),
                List.of(
                        new ParameterConstraint(
                                "oversoldThreshold",
                                ConstraintOperator.LT,
                                "overboughtThreshold",
                                "과매도 임계값은 과매수 임계값보다 낮아야 합니다.")),
                MinimumHistoryRequirement.ofParameter("rsiPeriod", 1),
                List.of(initialChange(1, LocalDate.of(2026, 9, 3))),
                false);
    }

    /** 파라미터 3개 전부 INTEGER, BACKTEST 전용, 최소 이력은 SUM_PARAMETERS, 변경 이력 2건. */
    public static StrategyDefinition macdSignal() {
        return new StrategyDefinition(
                SCHEMA_VERSION,
                "MACD_SIGNAL",
                2,
                "MACD 시그널 교차",
                "MACD가 시그널선을 교차할 때 매매한다.",
                "MACD가 시그널선을 아래에서 위로 교차한 다음 거래일 시가에 전량 진입한다.",
                "MACD가 시그널선을 위에서 아래로 교차한 다음 거래일 시가에 전량 청산한다.",
                BACKTEST_ONLY,
                List.of(
                        integerParameter(
                                "fastPeriod",
                                "빠른 EMA 기간",
                                "빠른 지수이동평균의 기간.",
                                true,
                                null,
                                NumericRange.closed(bd("2"), bd("60"), BigDecimal.ONE),
                                BACKTEST_ONLY),
                        integerParameter(
                                "slowPeriod",
                                "느린 EMA 기간",
                                "느린 지수이동평균의 기간.",
                                true,
                                null,
                                NumericRange.closed(bd("5"), bd("200"), BigDecimal.ONE),
                                BACKTEST_ONLY),
                        integerParameter(
                                "signalPeriod",
                                "시그널 기간",
                                "MACD를 다시 평활할 기간.",
                                false,
                                ParameterValue.of(9L),
                                NumericRange.closed(bd("2"), bd("50"), BigDecimal.ONE),
                                BACKTEST_ONLY)),
                List.of(
                        new ParameterConstraint(
                                "fastPeriod",
                                ConstraintOperator.LT,
                                "slowPeriod",
                                "빠른 EMA 기간은 느린 EMA 기간보다 짧아야 합니다.")),
                MinimumHistoryRequirement.sumOf(List.of("slowPeriod", "signalPeriod"), 1),
                List.of(
                        new ChangeHistoryEntry(
                                2,
                                ChangeType.INCOMPATIBLE,
                                "fastPeriod 하한을 1에서 2로 올림",
                                LocalDate.of(2026, 9, 3)),
                        initialChange(1, LocalDate.of(2026, 9, 2))),
                false);
    }

    /** 파라미터 2개, 제약 없음, 음수 하한을 가진 DECIMAL 포함. */
    public static StrategyDefinition momentum() {
        return new StrategyDefinition(
                SCHEMA_VERSION,
                "MOMENTUM",
                1,
                "모멘텀",
                "최근 일정 기간의 수익률이 기준치를 넘으면 추세가 이어질 것으로 보고 보유한다.",
                "직전 lookbackDays 동안의 수익률이 진입 임계값을 넘은 다음 거래일 시가에 전량 진입한다.",
                "직전 lookbackDays 동안의 수익률이 진입 임계값 아래로 내려간 다음 거래일 시가에 전량 청산한다.",
                BOTH_SCOPES,
                List.of(
                        integerParameter(
                                "lookbackDays",
                                "모멘텀 관측 기간",
                                "수익률을 계산할 과거 거래일 수.",
                                true,
                                null,
                                NumericRange.closed(bd("5"), bd("250"), BigDecimal.ONE)),
                        decimalParameter(
                                "entryThreshold",
                                "진입 임계 수익률",
                                "이 수익률을 넘어야 진입한다. 0.05는 5%를 뜻한다.",
                                BigDecimal.ZERO,
                                "ratio",
                                NumericRange.closed(bd("-0.5"), BigDecimal.ONE, bd("0.01")))),
                List.of(),
                MinimumHistoryRequirement.ofParameter("lookbackDays", 1),
                List.of(initialChange(1, LocalDate.of(2026, 9, 3))),
                false);
    }

    /** 파라미터가 하나도 없는 전략. 빈 목록으로 표현되며 오류가 아니다(FR-004). */
    public static StrategyDefinition withoutParameters() {
        return new StrategyDefinition(
                SCHEMA_VERSION,
                "FIXED_WARMUP",
                1,
                "고정 이력 요구 데모",
                "FIXED 규칙과 파라미터 없는 전략을 확인하기 위한 정의.",
                "테스트 전용 정의이며 실제 매매 규칙을 담지 않는다.",
                "테스트 전용 정의이며 실제 매매 규칙을 담지 않는다.",
                BACKTEST_ONLY,
                List.of(),
                List.of(),
                MinimumHistoryRequirement.fixed(250, 0),
                List.of(initialChange(1, LocalDate.of(2026, 9, 3))),
                false);
    }

    /** 실행 목적에 따라 판정 대상이 달라지는 전략. BACKTEST 전용 파라미터를 하나 갖는다. */
    public static StrategyDefinition scopedDemo() {
        return new StrategyDefinition(
                SCHEMA_VERSION,
                "SCOPED_DEMO",
                1,
                "실행 목적별 파라미터 데모",
                "실행 목적에 따라 판정 대상 파라미터가 달라지는 경우를 확인하기 위한 정의.",
                "테스트 전용 정의이며 실제 매매 규칙을 담지 않는다.",
                "테스트 전용 정의이며 실제 매매 규칙을 담지 않는다.",
                BOTH_SCOPES,
                List.of(
                        integerParameter(
                                "period",
                                "관측 기간",
                                "양쪽 실행 목적에서 모두 쓰인다.",
                                true,
                                null,
                                NumericRange.closed(BigDecimal.ONE, bd("100"), BigDecimal.ONE)),
                        new ParameterDefinition(
                                "slippageRate",
                                "슬리피지",
                                "과거 데이터 검증에서만 의미가 있다.",
                                ParameterType.DECIMAL,
                                true,
                                null,
                                "ratio",
                                BACKTEST_ONLY,
                                NumericRange.closed(BigDecimal.ZERO, bd("0.05"), bd("0.0001")),
                                null)),
                List.of(),
                MinimumHistoryRequirement.ofParameter("period", 0),
                List.of(initialChange(1, LocalDate.of(2026, 9, 3))),
                false);
    }

    public static List<StrategyDefinition> allShippedStrategies() {
        return List.of(smaCrossover(), rsiReversion(), macdSignal(), momentum());
    }

    private static ParameterDefinition integerParameter(
            String name,
            String displayName,
            String description,
            boolean required,
            ParameterValue defaultValue,
            NumericRange range) {
        return integerParameter(
                name, displayName, description, required, defaultValue, range, BOTH_SCOPES);
    }

    private static ParameterDefinition integerParameter(
            String name,
            String displayName,
            String description,
            boolean required,
            ParameterValue defaultValue,
            NumericRange range,
            Set<ExecutionScope> scopes) {
        return new ParameterDefinition(
                name,
                displayName,
                description,
                ParameterType.INTEGER,
                required,
                defaultValue,
                "bars",
                scopes,
                range,
                null);
    }

    private static ParameterDefinition decimalParameter(
            String name,
            String displayName,
            String description,
            BigDecimal defaultValue,
            String unit,
            NumericRange range) {
        return new ParameterDefinition(
                name,
                displayName,
                description,
                ParameterType.DECIMAL,
                false,
                defaultValue == null ? null : ParameterValue.of(defaultValue),
                unit,
                BOTH_SCOPES,
                range,
                null);
    }

    private static ChangeHistoryEntry initialChange(int version, LocalDate changedAt) {
        return new ChangeHistoryEntry(version, ChangeType.INITIAL, "최초 정의", changedAt);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
