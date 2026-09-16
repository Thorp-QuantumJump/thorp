package com.ssafy.thorp.strategy.application.port;

import java.util.List;
import java.util.Map;
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

    /** 등록표 버전. 접수 시점에 고정해 묶음에 저장한다(FR-028). 임시 값은 {@code PROVISIONAL-}로 시작한다. */
    String version();

    /**
     * 등록표의 값이 아직 팀 확정값이 아닌가. {@link #version()}이 {@code PROVISIONAL-}로 시작하는지로 판정한다.
     *
     * <p>접두를 떼는 날 코드가 바뀌면 안 되므로 로더가 접두를 강제하지 않고 이 메서드로 알린다. {@code true}면 화면과 요청 결과에 임시값임을 표시한다(내부
     * 계약 §2.1).
     */
    boolean provisional();

    /** <b>활성 전략만</b> 돌려준다. 007은 전략 목록을 자체적으로 갖지 않는다(FR-025·FR-026, 내부 계약 §2.3). */
    List<StrategyRegistration> activeStrategies();

    /**
     * 전략 하나의 등록 정보. <b>비활성 전략도 포함한다</b>(내부 계약 §2.3) — 과거 기록을 해석할 때 그 시점에 쓰인 이름과 축 구성이 필요하기 때문이다.
     *
     * <p>등록되지 않았으면 empty — 호출자가 거부 사유로 옮긴다(FR-026).
     */
    Optional<StrategyRegistration> find(String strategyId);

    /**
     * 그 전략의 유효성 규칙 목록. 규칙이 없는 전략은 빈 목록이다.
     *
     * <p><b>빈 목록과 "등록되지 않은 전략"은 다르다.</b> 이 메서드는 둘을 구분하지 않으므로 등록 여부는 {@link #find}로 먼저 확인한다.
     *
     * <p>003 조회 API가 쓴다. 화면이 입력형 축의 값을 타이핑하는 동안 그 자리에서 판정하려면 판정 결과가 아니라 조건 자체가 필요하다(FR-008, 내부 계약
     * §3). 007은 부르지 않아도 된다 — {@link #validate}로 충분하다.
     */
    List<ValidationRule> rules(String strategyId);

    /** 전략과 무관한 공통 분석 조건(시작 금액·목표 변동성·최소 평가 기간). 003 화면이 쓴다(data-model §7, 내부 계약 §3). */
    AnalysisConditionRegistry analysisConditions();

    /**
     * 조합 하나가 유효성 제약을 만족하는지. 위반 시 사유 코드를 담아 돌려준다(FR-006).
     *
     * <p><b>조합을 축 이름 → 값의 {@code Map}으로 받는 이유</b>: 007의 {@code ParameterCombination}을 받으면 등록표를 소유한
     * 003이 007 도메인 VO에 의존해 없애려던 역방향 의존이 되살아난다. 호출자가 자기 표현을 풀어 넘긴다.
     *
     * @param parameters 축 이름 → 값. 값에 {@code null}을 담지 않는다
     */
    ValidationVerdict validate(
            String strategyId, Map<String, Object> parameters, SearchContext context);

    /**
     * 손실 제약의 기준으로 쓸 전략. 등록표가 기준 전략 역할로 지정한다(FR-013d). 지정이 없으면 empty다.
     *
     * <p>empty면 하락 폭 필터를 적용하지 않고 그 사실을 결과에 표시한다. <b>007이 어느 전략이 기준인지 추정하지 않는다</b> — 기준 전략 ID를 코드에
     * 두면 그것이 FR-025 위반이고, 사용자 등록 전략이 늘어나면 곧바로 깨진다.
     */
    Optional<String> baselineStrategyId();
}
