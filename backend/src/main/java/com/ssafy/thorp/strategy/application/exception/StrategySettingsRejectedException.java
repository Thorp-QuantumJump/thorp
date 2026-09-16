package com.ssafy.thorp.strategy.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 접수 시점 설정 재검증이 요청을 거절할 때 던진다(FR-006, contracts/strategy-registry-api.md §2).
 *
 * <p><b>문장을 여기서 지어내지 않는다.</b> 규칙 위반의 문구는 등록표가 소유하고 화면은 {@code detail}을 그대로 보인다. 서버가 자기 말로 다시 쓰면 같은
 * 위반이 화면과 서버에서 다른 문장으로 보이고, 사용자는 두 안내가 같은 것을 뜻하는지 알 수 없다.
 *
 * <p>거절 사유마다 사용자가 다음에 할 일이 다르므로 {@code details}에 담는 것도 다르다 — 버전이 바뀌었으면 다시 확인할 거리를, 범위를 벗어났으면 어디까지
 * 허용되는지를, 규칙을 어겼으면 어느 규칙인지를 준다.
 */
public class StrategySettingsRejectedException extends BusinessException {

    private StrategySettingsRejectedException(
            ErrorCode errorCode, String message, Map<String, Object> details) {
        super(errorCode, message, details);
    }

    /** 화면이 읽은 등록표와 접수 시점의 등록표가 다르다. 409 — 사용자가 확인한 것과 실행될 것이 달라진다. */
    public static StrategySettingsRejectedException versionChanged(
            String requestedVersion, String currentVersion) {
        return new StrategySettingsRejectedException(
                ErrorCode.REGISTRY_VERSION_CHANGED,
                ErrorCode.REGISTRY_VERSION_CHANGED.getMessage(),
                details(
                        "requestedRegistryVersion", requestedVersion,
                        "currentRegistryVersion", currentVersion));
    }

    /** 활성 전략에 없다. 422 — 요청 형식은 옳고 지금 실행할 수 없는 것이다. */
    public static StrategySettingsRejectedException strategyNotRegistered(String strategyId) {
        return new StrategySettingsRejectedException(
                ErrorCode.STRATEGY_NOT_REGISTERED,
                ErrorCode.STRATEGY_NOT_REGISTERED.getMessage(),
                details("strategyId", strategyId));
    }

    /** 등록표에 없는 축 이름이 왔거나 등록된 축이 빠졌다. 400 — 요청 자체가 등록표와 맞지 않는다. */
    public static StrategySettingsRejectedException axisMismatch(
            String strategyId, List<String> unknownAxes, List<String> missingAxes) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("strategyId", strategyId);
        if (!unknownAxes.isEmpty()) {
            details.put("unknownAxes", unknownAxes);
        }
        if (!missingAxes.isEmpty()) {
            details.put("missingAxes", missingAxes);
        }
        return new StrategySettingsRejectedException(
                ErrorCode.UNREGISTERED_PARAMETER_VALUE,
                "요청한 설정 항목이 등록표와 다릅니다: strategyId=%s, 등록되지 않은 항목=%s, 빠진 항목=%s"
                        .formatted(strategyId, unknownAxes, missingAxes),
                details);
    }

    /**
     * 값이 허용 범위 밖이다. 400.
     *
     * <p>허용 범위를 함께 담는 이유: 어디까지 되는지 모르면 사용자가 다시 시도할 때 같은 거절을 또 받는다. <b>값을 잘라 통과시키지 않는다</b> — 그러면
     * 사용자가 고른 것과 다른 조건의 결과가 그 조건의 결과로 저장된다.
     */
    public static StrategySettingsRejectedException valueOutOfRange(
            String strategyId, String axisName, Object value, Object allowedRange) {
        return new StrategySettingsRejectedException(
                ErrorCode.UNREGISTERED_PARAMETER_VALUE,
                "허용 범위를 벗어난 값입니다: strategyId=%s, 항목=%s, 값=%s, 허용 범위=%s"
                        .formatted(strategyId, axisName, value, allowedRange),
                details(
                        "strategyId", strategyId,
                        "axis", axisName,
                        "value", value,
                        "allowedRange", allowedRange));
    }

    /** 등록표의 유효성 규칙을 어겼다. 422 — 문장은 등록표가 준 것을 그대로 쓴다. */
    public static StrategySettingsRejectedException ruleViolated(
            String strategyId, String reasonCode, String reasonDetail) {
        return new StrategySettingsRejectedException(
                ErrorCode.VALIDATION_RULE_VIOLATED,
                reasonDetail == null || reasonDetail.isBlank()
                        ? ErrorCode.VALIDATION_RULE_VIOLATED.getMessage()
                        : reasonDetail,
                details(
                        "strategyId", strategyId,
                        "code", reasonCode,
                        "detail", reasonDetail));
    }

    /** 시작 금액이 공통 분석 조건의 허용 범위 밖이다. 400. */
    public static StrategySettingsRejectedException initialAmountOutOfRange(
            Object value, Object allowedRange) {
        return new StrategySettingsRejectedException(
                ErrorCode.UNREGISTERED_PARAMETER_VALUE,
                "허용 범위를 벗어난 시작 금액입니다: 값=%s, 허용 범위=%s".formatted(value, allowedRange),
                details("value", value, "allowedRange", allowedRange));
    }

    /** {@code details}에 null 값을 담지 않는다 — {@code Map.copyOf}가 NPE 다. */
    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                details.put(String.valueOf(keyValues[i]), value);
            }
        }
        return details;
    }
}
