package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.paramsearch.application.exception.StrategyNotExecutableException;
import com.ssafy.thorp.paramsearch.application.exception.StrategyNotRegisteredException;
import com.ssafy.thorp.paramsearch.application.exception.StrategyNotSearchableException;
import com.ssafy.thorp.paramsearch.application.port.StrategyExecutionSupportPort;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import com.ssafy.thorp.strategy.application.exception.RegistryUnavailableException;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 요청 전략을 탐색 대상으로 바꾼다 — <b>조합을 만들기 전에</b> 거부할 것을 거부한다(FR-026, research R8).
 *
 * <p>탐색 대상은 '003 등록표의 활성 전략' ∩ '004가 실행을 지원하는 전략' ∩ '자동 탐색이 값을 다 만들 수 있는 전략'이다. 차례로 {@link
 * StopReason#STRATEGY_NOT_REGISTERED}·{@link StopReason#STRATEGY_NOT_EXECUTABLE}·{@link
 * StopReason#STRATEGY_NOT_SEARCHABLE}이며 세 사유를 구분해 남긴다. 사용자가 할 수 있는 일이 다르기 때문이다 — 순서대로 등록을 기다리고, 실행
 * 지원을 기다리고, 자동 탐색 대신 분석하기로 실행한다.
 *
 * <p><b>007 내부 전략 목록과 비교하지 않는다.</b> 판정에 쓰는 세 집합은 모두 포트가 준 값이고, 이 클래스에는 전략 ID가 한 번도 나타나지
 * 않는다(FR-025).
 *
 * <p>거부를 조합 생성 앞에 두는 이유: 미지원 전략을 그대로 발행하면 후보 전량이 실패로 끝나 "실행 불가"와 "낮은 수익"의 구분이 흐려지고 실행 자원도 버린다(원칙
 * VI).
 */
@Service
public class SearchTargetResolver {

    /**
     * 등록표가 "목표 비중이 사용자에게서 온다"를 나타내는 값(003 계약 §1의 {@code targetWeightSource}).
     *
     * <p>전략 ID가 아니라 등록표의 어휘다 — 전략이 늘어도 이 상수는 바뀌지 않는다.
     */
    private static final String USER_SUPPLIED_TARGET_WEIGHT = "USER";

    private final ParameterRegistryPort registry;
    private final StrategyExecutionSupportPort executionSupport;

    public SearchTargetResolver(
            ParameterRegistryPort registry, StrategyExecutionSupportPort executionSupport) {
        this.registry = registry;
        this.executionSupport = executionSupport;
    }

    /**
     * 요청 전략을 판정한다. 거부 사유가 있으면 그 사유를 담아 돌려주고 <b>예외를 던지지 않는다</b> — 미리보기는 저장 없이 사유만 알리고, 접수는 묶음을
     * {@code REJECTED}로 저장해야 해서 호출자마다 처리가 다르다.
     *
     * @param requestedStrategyIds 사용자가 고른 전략. 순서를 보존한다
     */
    public SearchTargets resolve(List<String> requestedStrategyIds) {
        if (requestedStrategyIds == null || requestedStrategyIds.isEmpty()) {
            throw new IllegalArgumentException("strategyIds must not be empty");
        }

        Map<String, String> supported = readSupported();
        List<StrategyRegistration> targets = new ArrayList<>();
        List<String> notRegistered = new ArrayList<>();
        List<String> notExecutable = new ArrayList<>();
        List<String> notSearchable = new ArrayList<>();

        for (String strategyId : requestedStrategyIds) {
            Optional<StrategyRegistration> registration = readRegistration(strategyId);
            if (registration.isEmpty()) {
                notRegistered.add(strategyId);
            } else if (!supported.containsKey(strategyId)) {
                notExecutable.add(strategyId);
            } else if (!isSearchable(registration.get())) {
                notSearchable.add(strategyId);
            } else {
                targets.add(registration.get());
            }
        }
        return new SearchTargets(
                registryVersion(),
                baselineStrategyId(),
                List.copyOf(targets),
                Map.copyOf(supported),
                List.copyOf(notRegistered),
                List.copyOf(notExecutable),
                List.copyOf(notSearchable));
    }

    /**
     * 자동 탐색이 이 전략의 실행 입력을 전부 만들 수 있는가.
     *
     * <p>목표 비중이 사용자에게서 오는 전략({@code targetWeightSource = USER})은 만들 수 없다 — 그 값은 002가 확정해 분석 요청에 담기고
     * 탐색 요청에는 없다. 그대로 발행하면 후보 전량이 접수에서 목표 비중 누락으로 거절된다.
     *
     * <p><b>축이 있는지로 판정하지 않는다.</b> 예전에는 그렇게 갈렸지만 그것은 우연이었다 — 003이 등록표를 넓혀 모든 전략에 축이 생기자 곧바로 깨졌다. 축의
     * 유무는 설정할 항목이 있는지를 말할 뿐 실행 입력을 누가 대는지를 말하지 않는다.
     *
     * <p>판정 기준은 등록표가 준 값이고 이 메서드에 전략 ID는 나타나지 않는다(FR-025).
     */
    private static boolean isSearchable(StrategyRegistration registration) {
        return !USER_SUPPLIED_TARGET_WEIGHT.equals(registration.targetWeightSource());
    }

    private Map<String, String> readSupported() {
        try {
            return executionSupport.supportedStrategies();
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RegistryUnavailableException("004 실행 지원 목록", e);
        }
    }

    private Optional<StrategyRegistration> readRegistration(String strategyId) {
        try {
            return registry.find(strategyId);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RegistryUnavailableException("003 등록표: " + strategyId, e);
        }
    }

    /** 등록표가 기준 전략 역할로 지정한 전략. 지정이 없으면 null이고 하락 폭 필터를 적용하지 않는다(FR-013d). */
    private String baselineStrategyId() {
        try {
            return registry.baselineStrategyId().orElse(null);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RegistryUnavailableException("003 기준 전략 지정", e);
        }
    }

    private String registryVersion() {
        try {
            return registry.version();
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RegistryUnavailableException("003 등록표 버전", e);
        }
    }

    /**
     * 판정 결과.
     *
     * @param registryVersion 판정에 쓴 등록표 버전. 접수는 이 값을 묶음에 고정한다(FR-028)
     * @param baselineStrategyId 등록표가 기준 전략으로 지정한 ID. 지정이 없으면 null(FR-013d)
     * @param targets 탐색 대상 전략의 등록 정보. 요청 순서를 보존한다
     * @param rulesVersionByStrategy 004가 준 전략별 규칙 버전. 묶음이 접수 시점 값으로 고정한다
     * @param notRegistered 등록표에 없는 전략
     * @param notExecutable 등록됐으나 004가 실행을 지원하지 않는 전략
     * @param notSearchable 실행은 되지만 자동 탐색이 값을 다 만들 수 없는 전략
     */
    public record SearchTargets(
            String registryVersion,
            String baselineStrategyId,
            List<StrategyRegistration> targets,
            Map<String, String> rulesVersionByStrategy,
            List<String> notRegistered,
            List<String> notExecutable,
            List<String> notSearchable) {

        public boolean isRejected() {
            return !notRegistered.isEmpty() || !notExecutable.isEmpty() || !notSearchable.isEmpty();
        }

        /**
         * 대상 전략 전체의 등록된 탐색 후보값으로 만든 탐색 공간. 사용자가 범위를 좁히지 않았을 때의 기본값이다.
         *
         * <p>등록표가 준 축 순서와 값 순서를 그대로 옮긴다 — 007이 정렬하거나 값을 만들어 내지 않는다(FR-002·FR-029). 표시 문구를 함께 담는 이유는
         * 등록표 버전이 올라간 뒤에도 그 묶음이 무엇을 탐색했는지 읽을 수 있어야 하기 때문이다(FR-020).
         */
        public SearchSpace fullSearchSpace() {
            Map<String, SearchSpace.StrategySpace> strategies = new LinkedHashMap<>();
            for (StrategyRegistration registration : targets) {
                List<SearchSpace.Axis> axes =
                        registration.axes().stream()
                                .map(
                                        axis ->
                                                new SearchSpace.Axis(
                                                        axis.name(),
                                                        axis.label(),
                                                        axis.unit(),
                                                        axis.searchValues()))
                                .toList();
                strategies.put(
                        registration.strategyId(),
                        new SearchSpace.StrategySpace(registration.name(), axes));
            }
            return SearchSpace.of(strategies);
        }

        /**
         * 거부 사유. 둘 다 걸리면 미등록을 먼저 알린다 — 등록이 먼저 필요한 조건이라 사용자가 할 일의 순서와 같다.
         *
         * @throws IllegalStateException 거부 사유가 없을 때
         */
        public StopReason stopReason() {
            if (!notRegistered.isEmpty()) {
                return StopReason.STRATEGY_NOT_REGISTERED;
            }
            if (!notExecutable.isEmpty()) {
                return StopReason.STRATEGY_NOT_EXECUTABLE;
            }
            if (!notSearchable.isEmpty()) {
                return StopReason.STRATEGY_NOT_SEARCHABLE;
            }
            throw new IllegalStateException("no rejection to report");
        }

        /** 거부를 오류 응답으로 바꾼다. 미리보기와 접수 모두 같은 HTTP 계약을 쓴다(contracts §6). */
        public BusinessException toException() {
            if (!notRegistered.isEmpty()) {
                return new StrategyNotRegisteredException(notRegistered);
            }
            if (!notExecutable.isEmpty()) {
                return new StrategyNotExecutableException(notExecutable);
            }
            if (!notSearchable.isEmpty()) {
                return new StrategyNotSearchableException(notSearchable);
            }
            throw new IllegalStateException("no rejection to report");
        }
    }
}
