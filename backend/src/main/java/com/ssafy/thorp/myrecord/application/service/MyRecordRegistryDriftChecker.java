package com.ssafy.thorp.myrecord.application.service;

import com.ssafy.thorp.myrecord.application.result.RegistryDrift;
import com.ssafy.thorp.strategy.application.result.StrategyRegistryResult;
import com.ssafy.thorp.strategy.application.service.StrategyRegistryQueryService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 저장된 설정값을 <b>지금</b> 등록표와 견준다(spec 006 FR-008, contracts "registryDrift").
 *
 * <p><b>등록표를 006이 해석하지 않는다.</b> 축의 허용 범위는 003이 {@link StrategyRegistryQueryService}로 내보내는 모양 그대로
 * 읽고, 여기서는 저장값이 그 안에 드는지만 본다. 축 이름으로 분기하거나 전략별 규칙을 아는 코드가 여기 있으면 그것이 003 지식의 중복이다.
 *
 * <p><b>유효성 규칙({@code validationRules})은 보지 않는다.</b> 그 판정은 섹터 수 같은 지금의 선택에 딸려 있어 저장된 조건만으로는 답이 없다 —
 * 003 화면이 값을 채운 뒤에 한다. 여기서 보는 것은 "이 값이 축의 허용 범위 안인가" 하나다.
 *
 * <p>판정할 근거가 없으면(전략이 등록표에서 사라졌거나 저장된 설정이 없거나 등록표를 읽지 못하면) <b>빈 값</b>을 돌려준다. "범위 안"으로 답하면 모르는 것과 괜찮은
 * 것이 섞인다(원칙 VI).
 */
@Component
public class MyRecordRegistryDriftChecker {

    private static final Logger log = LoggerFactory.getLogger(MyRecordRegistryDriftChecker.class);

    private final StrategyRegistryQueryService registry;

    public MyRecordRegistryDriftChecker(StrategyRegistryQueryService registry) {
        this.registry = registry;
    }

    /**
     * @param config 저장 당시 실행 조건({@code reopenPayload}). {@code strategyId}와 {@code parameters}를 읽는다
     * @return 판정 결과. 판정할 근거가 없으면 빈 값 — 부른 쪽이 키를 만들지 않는다
     */
    public Optional<RegistryDrift> check(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return Optional.empty();
        }
        Object strategyId = config.get("strategyId");
        Map<String, Object> parameters = asMap(config.get("parameters"));
        if (strategyId == null || parameters.isEmpty()) {
            return Optional.empty();
        }

        List<StrategyRegistryResult.AxisView> axes;
        try {
            axes = axesOf(String.valueOf(strategyId));
        } catch (RuntimeException e) {
            log.warn("등록표를 읽지 못해 범위 판정을 건너뛴다: strategyId={}", strategyId, e);
            return Optional.empty();
        }
        if (axes.isEmpty()) {
            // 전략이 비활성이 됐거나 축이 없다. 견줄 것이 없으므로 판정하지 않는다.
            return Optional.empty();
        }

        List<RegistryDrift.Field> out = new ArrayList<>();
        for (StrategyRegistryResult.AxisView axis : axes) {
            Object saved = parameters.get(axis.name());
            if (saved == null) {
                // 저장 당시 없던 축이거나 003이 새로 더한 축이다. 없는 값은 범위 밖이 아니다.
                continue;
            }
            currentRange(axis, saved).ifPresent(range -> out.add(field(axis, saved, range)));
        }
        return Optional.of(new RegistryDrift(!out.isEmpty(), out));
    }

    private static RegistryDrift.Field field(
            StrategyRegistryResult.AxisView axis, Object saved, Map<String, Object> range) {
        return new RegistryDrift.Field(axis.name(), saved, range);
    }

    /**
     * 저장값이 이 축의 허용 범위 밖이면 지금 범위를, 안이면 빈 값을 돌려준다.
     *
     * <p>목록형은 후보에 <b>있는가</b>로 보고, 고를 수 없는 후보({@code selectable=false})도 있는 것으로 센다 — 그것은 지금 선택 조건이
     * 만든 판정이지 값이 사라졌다는 뜻이 아니다. 입력형은 {@code min}·{@code max}만 본다. {@code step}은 맞지 않아도 003이 반올림해
     * 보이므로 범위 밖으로 알리지 않는다.
     */
    private static Optional<Map<String, Object>> currentRange(
            StrategyRegistryResult.AxisView axis, Object saved) {
        if (axis.choices() != null) {
            boolean present =
                    axis.choices().stream().anyMatch(choice -> sameValue(choice.value(), saved));
            if (present) {
                return Optional.empty();
            }
            Map<String, Object> range = new LinkedHashMap<>();
            range.put("choices", axis.choices().stream().map(c -> c.value()).toList());
            return Optional.of(range);
        }
        StrategyRegistryResult.RangeView range = axis.range();
        if (range == null) {
            return Optional.empty();
        }
        BigDecimal value = number(saved);
        if (value == null) {
            return Optional.empty();
        }
        BigDecimal min = number(range.min());
        BigDecimal max = number(range.max());
        boolean below = min != null && value.compareTo(min) < 0;
        boolean above = max != null && value.compareTo(max) > 0;
        if (!below && !above) {
            return Optional.empty();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "min", range.min());
        put(out, "max", range.max());
        put(out, "step", range.step());
        return Optional.of(out);
    }

    private List<StrategyRegistryResult.AxisView> axesOf(String strategyId) {
        // universeSize 를 주지 않는다 — 유효성 제약은 지금의 섹터 선택에 딸려 있고 저장된 조건만으로는
        // 판정할 근거가 없다. 여기서 보는 것은 축의 허용 범위뿐이다.
        return registry.query(null).strategies().stream()
                .filter(strategy -> strategy.strategyId().equals(strategyId))
                .findFirst()
                .map(StrategyRegistryResult.StrategyView::axes)
                .orElse(List.of());
    }

    /** 숫자는 표기가 갈린다(JSONB 의 {@code 3} 과 {@code 3.0}). 값으로 견주고 문자열로 견주지 않는다. */
    private static boolean sameValue(Object registered, Object saved) {
        BigDecimal left = number(registered);
        BigDecimal right = number(saved);
        if (left != null && right != null) {
            return left.compareTo(right) == 0;
        }
        return String.valueOf(registered).equals(String.valueOf(saved));
    }

    private static BigDecimal number(Object value) {
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
