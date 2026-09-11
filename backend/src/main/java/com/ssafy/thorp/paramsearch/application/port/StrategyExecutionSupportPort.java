package com.ssafy.thorp.paramsearch.application.port;

import java.util.Map;
import java.util.Set;

/**
 * 004가 현재 실행을 지원하는 전략(contracts/param-search-internal.md §1.1, research R8).
 *
 * <p>"이 전략을 실행할 수 있는가"는 004의 사실이므로 004에 묻는다. 007도 003도 대신 선언하지 않는다 — 한쪽이 다른 쪽을 대신 선언하면 같은 사실이 두 곳으로
 * 갈라지고, 007이 자체 목록으로 판정하면 그것이 FR-025 위반이다.
 *
 * <p>탐색 대상은 {@code ParameterRegistryPort.activeStrategies()}와 이 집합의 <b>교집합</b>이다(FR-026). 004가 워커에
 * 전략을 추가하고 이 목록에 넣으면 007은 코드 변경 없이 그 전략을 탐색 대상으로 얻는다.
 *
 * <p>조회 실패는 등록표 조회 실패와 같이 다룬다 — 503 {@code REGISTRY_UNAVAILABLE}이고 대체값으로 실행하지 않는다.
 */
public interface StrategyExecutionSupportPort {

    /**
     * 실행을 지원하는 전략과 그 전략의 규칙 버전.
     *
     * <p>버전까지 받는 이유: 004에 후보를 접수하려면 어느 규칙 버전으로 계산할지 함께 넘겨야 하는데, 그 값을 007이 고르면 004의 등록표와 갈린다. 묶음은 접수
     * 시점 버전을 스냅샷에 고정해 끝까지 쓴다(FR-012).
     */
    Map<String, String> supportedStrategies();

    /** 탐색 대상 판정에 쓰는 집합. 버전이 필요 없는 호출자를 위한 것이다(FR-026). */
    default Set<String> supportedStrategyIds() {
        return supportedStrategies().keySet();
    }
}
