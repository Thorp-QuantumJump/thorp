package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.paramsearch.application.exception.RegistryUnavailableException;
import com.ssafy.thorp.paramsearch.application.exception.StrategyNotExecutableException;
import com.ssafy.thorp.paramsearch.application.exception.StrategyNotRegisteredException;
import com.ssafy.thorp.paramsearch.application.port.ParameterRegistryPort;
import com.ssafy.thorp.paramsearch.application.port.StrategyExecutionSupportPort;
import com.ssafy.thorp.paramsearch.application.port.StrategyRegistration;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 요청 전략을 탐색 대상으로 바꾼다 — <b>조합을 만들기 전에</b> 거부할 것을 거부한다(FR-026, research R8).
 *
 * <p>탐색 대상은 '003 등록표의 활성 전략' ∩ '004가 실행을 지원하는 전략'이다. 앞에 없으면 {@link
 * StopReason#STRATEGY_NOT_REGISTERED}, 뒤에 없으면 {@link StopReason#STRATEGY_NOT_EXECUTABLE}이며 두 사유를
 * 구분해 남긴다. 사용자가 할 수 있는 일이 다르기 때문이다.
 *
 * <p><b>007 내부 전략 목록과 비교하지 않는다.</b> 판정에 쓰는 두 집합은 모두 포트가 준 값이고, 이 클래스에는 전략 ID가 한 번도 나타나지
 * 않는다(FR-025).
 *
 * <p>거부를 조합 생성 앞에 두는 이유: 미지원 전략을 그대로 발행하면 후보 전량이 실패로 끝나 "실행 불가"와 "낮은 수익"의 구분이 흐려지고 실행 자원도 버린다(원칙
 * VI).
 */
@Service
public class SearchTargetResolver {

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

        Set<String> supported = readSupported();
        List<StrategyRegistration> targets = new ArrayList<>();
        List<String> notRegistered = new ArrayList<>();
        List<String> notExecutable = new ArrayList<>();

        for (String strategyId : requestedStrategyIds) {
            Optional<StrategyRegistration> registration = readRegistration(strategyId);
            if (registration.isEmpty()) {
                notRegistered.add(strategyId);
            } else if (!supported.contains(strategyId)) {
                notExecutable.add(strategyId);
            } else {
                targets.add(registration.get());
            }
        }
        return new SearchTargets(
                registryVersion(),
                List.copyOf(targets),
                List.copyOf(notRegistered),
                List.copyOf(notExecutable));
    }

    private Set<String> readSupported() {
        try {
            return executionSupport.supportedStrategyIds();
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
     * @param targets 탐색 대상 전략의 등록 정보. 요청 순서를 보존한다
     * @param notRegistered 등록표에 없는 전략
     * @param notExecutable 등록됐으나 004가 실행을 지원하지 않는 전략
     */
    public record SearchTargets(
            String registryVersion,
            List<StrategyRegistration> targets,
            List<String> notRegistered,
            List<String> notExecutable) {

        public boolean isRejected() {
            return !notRegistered.isEmpty() || !notExecutable.isEmpty();
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
            throw new IllegalStateException("no rejection to report");
        }
    }
}
