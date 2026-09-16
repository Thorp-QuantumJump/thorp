package com.ssafy.thorp.strategy.application.service;

import com.ssafy.thorp.strategy.application.exception.StrategySettingsRejectedException;
import com.ssafy.thorp.strategy.application.port.AnalysisCondition;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.SearchContext;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import com.ssafy.thorp.strategy.application.port.ValidationVerdict;
import com.ssafy.thorp.strategy.application.port.WarmupRule;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 분석 요청을 접수할 때 등록표로 설정을 다시 판정한다(FR-006, quickstart 5-1).
 *
 * <p><b>화면의 안내와 서버의 판정은 목적이 다르다.</b> 화면은 사용자가 고르는 동안 돕기 위해 판정하고, 서버는 실행 직전에 거절하기 위해 판정한다. 화면 검증을
 * 우회한 요청이 그대로 통과하면 허용 범위 밖 설정으로 분석이 시작되고, 그 결과가 정상적인 결과와 구별되지 않은 채 기록에 남는다.
 *
 * <p><b>고쳐서 통과시키는 경로를 두지 않는다</b>(constitution 원칙 VI). 범위를 벗어난 값을 경계로 자르거나 빠진 축을 기본값으로 채우면, 사용자가 요청한
 * 것과 다른 조건의 결과가 그 조건의 결과로 저장된다. 판정은 통과 아니면 거절 둘뿐이다.
 *
 * <p><b>거절하는 순서에 뜻이 있다.</b> 버전이 다르면 나머지 판정의 근거가 이미 바뀐 것이라 먼저 보고, 전략이 없으면 축을 볼 수 없어 그다음이며, 축 구성이 맞아야
 * 값을 판정할 수 있고, 값이 범위 안이어야 조합 규칙을 물을 수 있다. 순서를 섞으면 사용자가 앞선 원인을 모른 채 뒤의 사유만 받는다.
 *
 * <p>이 클래스는 {@code backtest}를 참조하지 않는다 — 의존은 {@code backtest → strategy} 한 방향이다(내부 계약 §4, {@code
 * ArchitectureTest}).
 */
@Service
public class StrategySettingsValidator {

    private final ParameterRegistryPort registry;

    public StrategySettingsValidator(ParameterRegistryPort registry) {
        this.registry = registry;
    }

    /**
     * 판정을 통과한 설정에서 서버가 계산해 낸 값.
     *
     * <p>워밍업을 여기서 돌려주는 이유: 같은 규칙을 화면과 서버가 각자 계산하는데, 요청이 실어 보낸 값을 그대로 믿으면 화면을 거치지 않은 요청이 워밍업을 0으로 보내
     * 평가 구간을 실제보다 길게 잡을 수 있다.
     *
     * @param warmupTradingDays 등록표의 워밍업 규칙으로 산출한 거래일 수. 규칙이 없는 전략은 0이다
     * @param registryVersion 판정에 쓴 등록표 버전. 접수가 이 값을 기록에 남긴다
     */
    public record Verified(int warmupTradingDays, String registryVersion) {}

    /**
     * @param parameters 축 이름 → 값. 등록표에 등록된 축과 정확히 같은 집합이어야 한다
     * @param registryVersion 화면이 읽은 등록표 버전
     * @param universeSize 이 요청이 고정한 섹터 후보 집합의 크기. 등록표 규칙이 쓴다
     * @param initialAmount 시작 금액
     * @throws StrategySettingsRejectedException 판정을 통과하지 못했다
     */
    public Verified verify(
            String strategyId,
            Map<String, Object> parameters,
            String registryVersion,
            int universeSize,
            BigDecimal initialAmount) {
        Objects.requireNonNull(strategyId, "strategyId must not be null");
        Map<String, Object> settings = parameters == null ? Map.of() : parameters;

        String currentVersion = registry.version();
        if (!Objects.equals(registryVersion, currentVersion)) {
            throw StrategySettingsRejectedException.versionChanged(registryVersion, currentVersion);
        }

        StrategyRegistration registration = requireActive(strategyId);
        requireSameAxes(registration, settings);
        requireValuesInRange(registration, settings);
        requireRulesSatisfied(strategyId, settings, universeSize);
        requireInitialAmountInRange(initialAmount);

        return new Verified(warmupOf(registration, settings), currentVersion);
    }

    /**
     * 활성 전략만 새 실행을 받는다.
     *
     * <p>{@code find}로는 찾히지만 비활성인 전략도 여기서 걸린다 — 비활성 전략을 등록표에서 지우지 않는 것은 과거 기록을 해석하기 위한 것이고(내부 계약
     * §2.3), 새로 실행해도 된다는 뜻이 아니다.
     */
    private StrategyRegistration requireActive(String strategyId) {
        return registry.activeStrategies().stream()
                .filter(strategy -> strategy.strategyId().equals(strategyId))
                .findFirst()
                .orElseThrow(
                        () -> StrategySettingsRejectedException.strategyNotRegistered(strategyId));
    }

    /**
     * 요청의 축 집합이 등록표의 축 집합과 같은가.
     *
     * <p><b>빠진 축을 기본값으로 채우지 않는다.</b> 채우면 사용자가 정하지 않은 값으로 실행한 결과가 사용자가 정한 결과처럼 보인다. 없는 축을 무시하지도 않는다
     * — 오타 난 축 이름이 조용히 버려지면 사용자는 자기가 바꾼 설정이 반영된 줄 안다.
     */
    private void requireSameAxes(StrategyRegistration registration, Map<String, Object> settings) {
        Set<String> registered = new LinkedHashSet<>(registration.axisNames());
        List<String> unknown = new ArrayList<>();
        for (String name : settings.keySet()) {
            if (!registered.contains(name)) {
                unknown.add(name);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String name : registered) {
            if (!settings.containsKey(name)) {
                missing.add(name);
            }
        }
        if (!unknown.isEmpty() || !missing.isEmpty()) {
            throw StrategySettingsRejectedException.axisMismatch(
                    registration.strategyId(), unknown, missing);
        }
    }

    /** 축마다 값이 허용 범위 안인가. 판정은 등록표의 {@code allowedRange}가 하고 여기서 범위를 다시 적지 않는다. */
    private void requireValuesInRange(
            StrategyRegistration registration, Map<String, Object> settings) {
        for (ParameterAxis axis : registration.axes()) {
            Object value = settings.get(axis.name());
            if (axis.allowedRange() == null || !axis.allowedRange().contains(value)) {
                throw StrategySettingsRejectedException.valueOutOfRange(
                        registration.strategyId(), axis.name(), value, axis.allowedRange());
            }
        }
    }

    /** 조합이 등록표의 유효성 규칙을 만족하는가. 위반 문구는 등록표가 준 것을 그대로 옮긴다. */
    private void requireRulesSatisfied(
            String strategyId, Map<String, Object> settings, int universeSize) {
        ValidationVerdict verdict =
                registry.validate(strategyId, settings, new SearchContext(universeSize));
        if (!verdict.valid()) {
            throw StrategySettingsRejectedException.ruleViolated(
                    strategyId, verdict.reasonCode(), verdict.reasonDetail());
        }
    }

    /** 시작 금액은 전략과 무관한 공통 조건이라 등록표 최상위의 범위를 쓴다(data-model §7). */
    private void requireInitialAmountInRange(BigDecimal initialAmount) {
        AnalysisCondition condition = registry.analysisConditions().initialAmount();
        if (!condition.range().contains(initialAmount)) {
            throw StrategySettingsRejectedException.initialAmountOutOfRange(
                    initialAmount, condition.range());
        }
    }

    /** 워밍업 규칙이 없는 전략은 0이다 — 빈 규칙과 "규칙 없음"을 등록표가 그렇게 구분한다(data-model §6). */
    private static int warmupOf(StrategyRegistration registration, Map<String, Object> settings) {
        WarmupRule warmup = registration.warmup();
        return warmup == null ? 0 : warmup.evaluate(settings);
    }
}
