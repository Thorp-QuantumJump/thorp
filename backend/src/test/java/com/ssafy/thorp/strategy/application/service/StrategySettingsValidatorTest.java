package com.ssafy.thorp.strategy.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.registry.RegistryFixtures;
import com.ssafy.thorp.strategy.application.exception.StrategySettingsRejectedException;
import com.ssafy.thorp.strategy.application.port.AllowedRange;
import com.ssafy.thorp.strategy.application.port.AxisCondition;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 접수 시점 설정 재검증(T048, FR-006, quickstart 5-1).
 *
 * <p>합성 등록표를 쓴다 — 실제 전략 이름을 쓰면 "그 전략이라서 되는 것"과 "어떤 전략이든 되는 것"을 구분할 수 없다({@link RegistryFixtures}와
 * 같은 이유).
 *
 * <p>거절을 단언할 때 <b>상태 코드와 함께 값이 고쳐지지 않았다는 것도 본다.</b> 이 검증기의 핵심은 판정 자체가 아니라 "고쳐서 통과시키는 경로가 없다"는
 * 것이고(원칙 VI), 그것은 거절했다는 사실만으로는 증명되지 않는다.
 */
class StrategySettingsValidatorTest {

    private static final String VERSION = "FIXTURE-1";
    private static final String STRATEGY = "SYNTHETIC-A";
    private static final BigDecimal VALID_AMOUNT = new BigDecimal("10");

    /** 시작 금액은 fixture가 {@code 1~100} 범위로 고정해 둔다. 전략과 무관한 공통 조건이다. */
    private static StrategySettingsValidator validatorOf(ParameterRegistryPort registry) {
        return new StrategySettingsValidator(registry);
    }

    private static Map<String, Object> settings(Object... keyValues) {
        Map<String, Object> settings = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            settings.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return settings;
    }

    /** 축 하나짜리 기본 등록표. 허용값은 {@code [21, 63, 126]}이다. */
    private static ParameterRegistryPort simpleRegistry() {
        return RegistryFixtures.registry(VERSION)
                .strategy(
                        STRATEGY,
                        RegistryFixtures.axis("lookbackDays", java.util.List.of(21, 63, 126)))
                .build();
    }

    @Test
    @DisplayName("등록표 버전이 다르면 409로 거절하고 현재 버전을 알려 준다")
    void 버전이_다르면_409() {
        StrategySettingsValidator validator = validatorOf(simpleRegistry());

        assertThatThrownBy(
                        () ->
                                validator.verify(
                                        STRATEGY,
                                        settings("lookbackDays", 63),
                                        "FIXTURE-0",
                                        3,
                                        VALID_AMOUNT))
                .isInstanceOf(StrategySettingsRejectedException.class)
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRY_VERSION_CHANGED);
    }

    @Test
    @DisplayName("재시도(verifySaved)는 과거 등록표 버전으로 판정받은 사본을 판정하지 않고 통과시킨다(spec 004 T029)")
    void 재시도는_과거_버전을_판정하지_않는다() {
        // 지금 등록표에는 없는 값(999)이라도 과거 버전에서 판정을 통과한 사본이면 그대로 둔다 —
        // 지금 규칙으로 판정하면 과거 요청을 그 뒤에 생긴 규칙으로 거절하게 된다.
        assertThat(
                        validatorOf(simpleRegistry())
                                .verifySaved(
                                        STRATEGY,
                                        settings("lookbackDays", 999),
                                        "FIXTURE-0",
                                        3,
                                        VALID_AMOUNT))
                .isEmpty();
    }

    @Test
    @DisplayName("재시도(verifySaved)라도 등록표 버전이 지금과 같으면 새 요청과 똑같이 판정한다")
    void 재시도라도_같은_버전이면_판정한다() {
        StrategySettingsValidator validator = validatorOf(simpleRegistry());

        assertThat(
                        validator.verifySaved(
                                STRATEGY, settings("lookbackDays", 63), VERSION, 3, VALID_AMOUNT))
                .as("버전이 같으면 판정을 건너뛰지 않는다")
                .isPresent();
        assertThatThrownBy(
                        () ->
                                validator.verifySaved(
                                        STRATEGY,
                                        settings("lookbackDays", 999),
                                        VERSION,
                                        3,
                                        VALID_AMOUNT))
                .isInstanceOf(StrategySettingsRejectedException.class)
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.UNREGISTERED_PARAMETER_VALUE);
    }

    @Test
    @DisplayName("find로는 찾히지만 비활성인 전략은 422 STRATEGY_NOT_REGISTERED다")
    void 비활성_전략은_422() {
        ParameterRegistryPort registry =
                RegistryFixtures.registry(VERSION)
                        .strategy(
                                STRATEGY,
                                RegistryFixtures.axis("lookbackDays", java.util.List.of(21, 63)))
                        .active(STRATEGY, false)
                        .build();
        // 등록표에서 사라진 것이 아니라 비활성일 뿐이다 — 과거 기록 해석에는 여전히 쓰인다(내부 계약 §2.3).
        assertThat(registry.find(STRATEGY)).isPresent();

        assertThatThrownBy(
                        () ->
                                validatorOf(registry)
                                        .verify(
                                                STRATEGY,
                                                settings("lookbackDays", 21),
                                                VERSION,
                                                3,
                                                VALID_AMOUNT))
                .isInstanceOf(StrategySettingsRejectedException.class)
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.STRATEGY_NOT_REGISTERED);
    }

    @Test
    @DisplayName("허용 범위 밖 값은 400이고 예외가 허용 범위를 담으며 값이 고쳐지지 않는다")
    void 범위_밖_값은_400이고_고치지_않는다() {
        Map<String, Object> requested = settings("lookbackDays", 999);

        assertThatThrownBy(
                        () ->
                                validatorOf(simpleRegistry())
                                        .verify(STRATEGY, requested, VERSION, 3, VALID_AMOUNT))
                .isInstanceOfSatisfying(
                        StrategySettingsRejectedException.class,
                        exc -> {
                            assertThat(exc.getErrorCode())
                                    .isEqualTo(ErrorCode.UNREGISTERED_PARAMETER_VALUE);
                            assertThat(exc.getDetails())
                                    .containsEntry("axis", "lookbackDays")
                                    .containsEntry("value", 999)
                                    .containsKey("allowedRange");
                        });
        // 경계로 잘라 통과시키는 경로가 없다는 것은 "요청 맵이 그대로다"로만 확인된다.
        assertThat(requested).containsExactlyEntriesOf(settings("lookbackDays", 999));
    }

    @Test
    @DisplayName("등록되지 않은 축 이름은 400이다")
    void 미등록_축은_400() {
        assertThatThrownBy(
                        () ->
                                validatorOf(simpleRegistry())
                                        .verify(
                                                STRATEGY,
                                                settings("lookbackDays", 63, "unknownAxis", 1),
                                                VERSION,
                                                3,
                                                VALID_AMOUNT))
                .isInstanceOfSatisfying(
                        StrategySettingsRejectedException.class,
                        exc -> {
                            assertThat(exc.getErrorCode())
                                    .isEqualTo(ErrorCode.UNREGISTERED_PARAMETER_VALUE);
                            assertThat(exc.getDetails())
                                    .containsEntry("unknownAxes", java.util.List.of("unknownAxis"));
                        });
    }

    @Test
    @DisplayName("빠진 축은 기본값으로 채우지 않고 400으로 거절한다")
    void 빠진_축은_400() {
        assertThatThrownBy(
                        () ->
                                validatorOf(simpleRegistry())
                                        .verify(STRATEGY, settings(), VERSION, 3, VALID_AMOUNT))
                .isInstanceOfSatisfying(
                        StrategySettingsRejectedException.class,
                        exc -> {
                            assertThat(exc.getErrorCode())
                                    .isEqualTo(ErrorCode.UNREGISTERED_PARAMETER_VALUE);
                            assertThat(exc.getDetails())
                                    .containsEntry(
                                            "missingAxes", java.util.List.of("lookbackDays"));
                        });
    }

    /**
     * quickstart 5-1: 비중 상한 30%에 섹터 후보가 셋이면 상한 합이 90%라 투자 비중 100%를 채울 수 없다. 34%면 102%라 채울 수 있다.
     *
     * <p>이 규칙이 {@link com.ssafy.thorp.strategy.application.port.SearchContext}의 {@code
     * universeSize}를 쓰므로, 같은 값이라도 후보 수가 달라지면 판정이 달라진다.
     */
    private static ParameterRegistryPort maxWeightRegistry() {
        return RegistryFixtures.registry(VERSION)
                .strategy(
                        STRATEGY, RegistryFixtures.axis("maxWeight", java.util.List.of(30, 34, 50)))
                .rule(
                        STRATEGY,
                        "MAX_WEIGHT_TOO_LOW",
                        (parameters, context) ->
                                new BigDecimal(String.valueOf(parameters.get("maxWeight")))
                                                .multiply(
                                                        BigDecimal.valueOf(context.universeSize()))
                                                .compareTo(BigDecimal.valueOf(100))
                                        < 0)
                .build();
    }

    @Test
    @DisplayName("등록표 규칙 위반은 422이고 detail이 등록표 문장 그대로다")
    void 규칙_위반은_422() {
        assertThatThrownBy(
                        () ->
                                validatorOf(maxWeightRegistry())
                                        .verify(
                                                STRATEGY,
                                                settings("maxWeight", 30),
                                                VERSION,
                                                3,
                                                VALID_AMOUNT))
                .isInstanceOfSatisfying(
                        StrategySettingsRejectedException.class,
                        exc -> {
                            assertThat(exc.getErrorCode())
                                    .isEqualTo(ErrorCode.VALIDATION_RULE_VIOLATED);
                            assertThat(exc.getDetails())
                                    .containsEntry("code", "MAX_WEIGHT_TOO_LOW")
                                    .containsEntry("detail", "MAX_WEIGHT_TOO_LOW 위반입니다");
                            // 화면이 그대로 보이는 문장이라 서버가 자기 말로 바꿔 쓰지 않는다.
                            assertThat(exc.getMessage()).isEqualTo("MAX_WEIGHT_TOO_LOW 위반입니다");
                        });
    }

    @Test
    @DisplayName("같은 축 값이라도 후보 수가 넉넉하면 통과한다 — 34 × 3 = 102")
    void 규칙을_만족하면_통과() {
        StrategySettingsValidator.Verified verified =
                validatorOf(maxWeightRegistry())
                        .verify(STRATEGY, settings("maxWeight", 34), VERSION, 3, VALID_AMOUNT);

        // 워밍업 규칙이 없는 전략은 0이다.
        assertThat(verified.warmupTradingDays()).isZero();
    }

    @Test
    @DisplayName("시작 금액이 공통 조건의 허용 범위 밖이면 400이다")
    void 시작_금액_범위_밖은_400() {
        assertThatThrownBy(
                        () ->
                                validatorOf(simpleRegistry())
                                        .verify(
                                                STRATEGY,
                                                settings("lookbackDays", 63),
                                                VERSION,
                                                3,
                                                new BigDecimal("1000")))
                .isInstanceOfSatisfying(
                        StrategySettingsRejectedException.class,
                        exc -> {
                            assertThat(exc.getErrorCode())
                                    .isEqualTo(ErrorCode.UNREGISTERED_PARAMETER_VALUE);
                            assertThat(exc.getDetails()).containsKey("allowedRange");
                        });
    }

    /**
     * 조건부 축(spec FR-003, {@code appliesWhen}). {@code mode}가 {@code A}일 때만 {@code aOnly}를 쓴다. 축 이름은
     * 합성 값이다 — 실제 선정 방식의 이름을 쓰면 "그 축이라서 되는 것"과 "조건이 적힌 축이면 되는 것"을 구분할 수 없다.
     */
    private static ParameterRegistryPort conditionalRegistry() {
        return RegistryFixtures.registry(VERSION)
                .strategy(
                        STRATEGY,
                        RegistryFixtures.axis("mode", java.util.List.of("A", "B")),
                        new ParameterAxis(
                                "aOnly",
                                "aOnly 라벨",
                                null,
                                null,
                                java.util.List.of(1, 2),
                                1,
                                AllowedRange.ofValues(java.util.List.of(1, 2)),
                                new AxisCondition("mode", "A"),
                                null))
                .build();
    }

    @Test
    @DisplayName("조건 밖 축은 빠져 있어도 받는다 — 등록표가 이 설정에서 쓰지 않는다고 정한 축이다")
    void 조건_밖_축은_빠져도_통과() {
        assertThatCode(
                        () ->
                                validatorOf(conditionalRegistry())
                                        .verify(
                                                STRATEGY,
                                                settings("mode", "B"),
                                                VERSION,
                                                3,
                                                VALID_AMOUNT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("조건 밖 축이 실려 오면 등록되지 않은 축과 같게 400이다 — 사용자가 정한 값이 아니다")
    void 조건_밖_축이_실리면_400() {
        assertThatThrownBy(
                        () ->
                                validatorOf(conditionalRegistry())
                                        .verify(
                                                STRATEGY,
                                                settings("mode", "B", "aOnly", 1),
                                                VERSION,
                                                3,
                                                VALID_AMOUNT))
                .isInstanceOfSatisfying(
                        StrategySettingsRejectedException.class,
                        exc ->
                                assertThat(exc.getDetails())
                                        .containsEntry("unknownAxes", java.util.List.of("aOnly")));
    }

    @Test
    @DisplayName("조건이 참인 축이 빠지면 기본값으로 채우지 않고 400이다")
    void 조건_참인_축이_빠지면_400() {
        assertThatThrownBy(
                        () ->
                                validatorOf(conditionalRegistry())
                                        .verify(
                                                STRATEGY,
                                                settings("mode", "A"),
                                                VERSION,
                                                3,
                                                VALID_AMOUNT))
                .isInstanceOfSatisfying(
                        StrategySettingsRejectedException.class,
                        exc ->
                                assertThat(exc.getDetails())
                                        .containsEntry("missingAxes", java.util.List.of("aOnly")));
    }

    @Test
    @DisplayName("워밍업은 등록표 규칙으로 산출한다 — 축 둘을 더하면 81이다")
    void 워밍업은_축_둘을_더한다() {
        ParameterRegistryPort registry =
                RegistryFixtures.registry(VERSION)
                        .strategy(
                                STRATEGY,
                                RegistryFixtures.axis("lookbackDays", java.util.List.of(63, 126)),
                                RegistryFixtures.axis("skipRecentDays", java.util.List.of(0, 18)))
                        .warmup(STRATEGY, java.util.List.of("lookbackDays", "skipRecentDays"))
                        .build();

        StrategySettingsValidator.Verified verified =
                validatorOf(registry)
                        .verify(
                                STRATEGY,
                                settings("lookbackDays", 63, "skipRecentDays", 18),
                                VERSION,
                                3,
                                VALID_AMOUNT);

        assertThat(verified.warmupTradingDays()).isEqualTo(81);
    }
}
