package com.ssafy.thorp.paramsearch;

import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 탐색을 접수할 수 있는 전략을 고른다. 통합 테스트가 실제 등록표와 실제 {@code strategy_rules}를 쓰므로 값을 적어 두지 않는다.
 *
 * <p><b>여덟 테스트가 같은 판정을 각자 복사해 갖고 있었다.</b> 기준이 바뀌자 여덟 곳을 함께 고쳐야 했고, 그중 하나라도 빠뜨리면 그 테스트만 조용히 달라진다.
 * 그래서 한 곳으로 모은다.
 *
 * <p><b>판정은 {@link com.ssafy.thorp.paramsearch.application.service.SearchTargetResolver}와 같은
 * 기준이다</b> — 등록표에 있고, 004가 실행을 지원하고, 자동 탐색이 실행 입력을 다 만들 수 있는 전략. 여기서만 달리 고르면 테스트가 접수되지 않을 조합을 만들어
 * 내거나, 반대로 접수 거부를 검증하지 못한다.
 *
 * <p>예전 판정이던 "축이 있는가"는 쓰지 않는다. 그것은 실행 입력을 누가 대는지를 말해 주지 않으며, 003이 등록표를 넓혀 모든 전략에 축이 생기자 곧바로 깨졌다.
 */
public final class SearchableStrategies {

    /** 등록표가 "목표 비중이 사용자에게서 온다"를 나타내는 값. 운영 코드와 같은 어휘다(003 계약 §1). */
    private static final String USER_SUPPLIED_TARGET_WEIGHT = "USER";

    private static final String REGISTERED_RULES =
            "SELECT DISTINCT strategy_id FROM strategy_rules WHERE status = 'REGISTERED'";

    private SearchableStrategies() {}

    /** 탐색을 접수할 수 있는 전략 전체. 등록표의 순서를 보존한다. */
    public static List<String> of(ParameterRegistryPort registry, JdbcTemplate jdbcTemplate) {
        List<String> executable = jdbcTemplate.queryForList(REGISTERED_RULES, String.class);
        return registry.activeStrategies().stream()
                .filter(registration -> executable.contains(registration.strategyId()))
                .filter(
                        registration ->
                                !USER_SUPPLIED_TARGET_WEIGHT.equals(
                                        registration.targetWeightSource()))
                .map(StrategyRegistration::strategyId)
                .toList();
    }

    /**
     * 기준 전략을 뺀 목록.
     *
     * <p>기준 전략은 손실 제약의 잣대로 따로 발행되므로(007 FR-013d), 그것까지 탐색 대상에 넣으면 발행 수를 세는 테스트가 기준 실행까지 함께 세게 된다.
     */
    public static List<String> excludingBaseline(
            ParameterRegistryPort registry, JdbcTemplate jdbcTemplate) {
        String baselineStrategyId = registry.baselineStrategyId().orElse(null);
        return of(registry, jdbcTemplate).stream()
                .filter(strategyId -> !strategyId.equals(baselineStrategyId))
                .toList();
    }
}
