package com.ssafy.thorp.paramsearch.application.port;

import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import java.util.List;
import java.util.Optional;

/**
 * 003이 소유한 전략·파라미터 등록표를 007이 읽는 <b>유일한</b> 통로(contracts/param-search-internal.md §1).
 *
 * <p>007은 이 포트가 돌려주는 값 외에 전략에 대해 아무것도 알지 못한다 — 전략 목록 상수·전략별 파라미터값·{@code strategyId} 분기를 어디에도 두지
 * 않는다(FR-025). 003이 완성되면 구현체만 교체한다.
 *
 * <p><b>조회 실패는 대체하지 않는다.</b> 구현체가 예외를 던지면 호출자는 접수를 거부한다(503 {@code REGISTRY_UNAVAILABLE}). 캐시된 등록
 * 정보나 007 내부 기본값으로 채워 실행하면 그 묶음의 참조 버전이 뜻을 잃는다(FR-028, research R9).
 */
public interface ParameterRegistryPort {

    /** 등록표 버전. 접수 시점에 고정해 묶음에 저장한다(FR-028). 임시 구현은 {@code PROVISIONAL-}로 시작한다. */
    String version();

    /** 활성 전략 전체. 007은 전략 목록을 자체적으로 갖지 않는다(FR-025·FR-026). */
    List<StrategyRegistration> activeStrategies();

    /** 전략 하나의 등록 정보. 등록되지 않았으면 empty — 호출자가 거부 사유로 옮긴다(FR-026). */
    Optional<StrategyRegistration> find(String strategyId);

    /** 조합 하나가 유효성 제약을 만족하는지. 위반 시 사유 코드를 담아 돌려준다(FR-006). */
    ValidationVerdict validate(
            String strategyId, ParameterCombination combination, SearchContext context);

    /**
     * 손실 제약의 기준으로 쓸 전략. 등록표가 기준 전략 역할로 지정한다(FR-013d). 지정이 없으면 empty다.
     *
     * <p>empty면 하락 폭 필터를 적용하지 않고 그 사실을 결과에 표시한다. <b>007이 어느 전략이 기준인지 추정하지 않는다</b> — 기준 전략 ID를 코드에
     * 두면 그것이 FR-025 위반이고, 사용자 등록 전략이 늘어나면 곧바로 깨진다.
     */
    Optional<String> baselineStrategyId();
}
