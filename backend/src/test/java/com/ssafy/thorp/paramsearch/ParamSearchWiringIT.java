package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.port.StrategyExecutionSupportPort;
import com.ssafy.thorp.paramsearch.application.service.CandidateValidator;
import com.ssafy.thorp.paramsearch.application.service.CandidateValidator.ValidatedPoint;
import com.ssafy.thorp.paramsearch.application.service.GridExpander;
import com.ssafy.thorp.paramsearch.application.service.GridExpander.GridPoint;
import com.ssafy.thorp.paramsearch.application.service.SearchTargetResolver;
import com.ssafy.thorp.paramsearch.application.service.SearchTargetResolver.SearchTargets;
import com.ssafy.thorp.paramsearch.infrastructure.support.StrategyExecutionSupportAdapter;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.SearchContext;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import com.ssafy.thorp.strategy.infrastructure.registry.FileParameterRegistry;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실제 컨텍스트·실제 데이터로 M2의 경계가 이어지는지 본다.
 *
 * <p>fixture 테스트가 확인하지 못하는 것 둘을 본다. (1) 포트 구현이 <b>빈으로 실제 주입되는지</b> — 구현이 둘이거나 하나도 없으면 기동 자체가 실패하고,
 * 그것은 단위 테스트에 걸리지 않는다. (2) {@code StrategyExecutionSupportAdapter}가 <b>004의 실제 등록표 ({@code
 * strategy_rules})를 읽는지</b> — 이 경로는 fixture가 흉내 내던 부분이다.
 *
 * <p>전략 이름을 단언에 쓰지 않는다. 마이그레이션이 전략을 더하거나 규칙 상태를 바꿔도 이 테스트는 그대로 성립해야 한다 — 확인하는 것은 특정 전략이 아니라 <b>두
 * 집합의 관계</b>다(FR-026).
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class ParamSearchWiringIT extends PostgresTestSupport {

    @Autowired private ParameterRegistryPort registry;
    @Autowired private StrategyExecutionSupportPort executionSupport;
    @Autowired private SearchTargetResolver resolver;
    @Autowired private GridExpander expander;
    @Autowired private CandidateValidator validator;
    @Autowired private CandidateJobLauncher launcher;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("포트 구현이 빈으로 주입되고 등록표가 확정 여부를 밝힌다")
    void portsAreWired() {
        assertThat(registry).isInstanceOf(FileParameterRegistry.class);
        assertThat(executionSupport).isInstanceOf(StrategyExecutionSupportAdapter.class);
        assertThat(launcher).isNotNull();
        assertThat(registry.version()).isNotBlank();
        // 값이 팀 확정값인지를 응답·화면이 계속 표시할 수 있어야 한다(research R1 개정). PD-02가
        // 2026-09-15에 확정돼 지금은 false이며, 미확정 값을 다시 담으면 version의 접두만으로 true가 된다.
        assertThat(registry.provisional()).isFalse();
        assertThat(registry.activeStrategies()).isNotEmpty();
    }

    @Test
    @DisplayName("실행 지원 목록은 004의 strategy_rules에서 온다 — 007이 자체 목록을 갖지 않는다")
    void supportedStrategiesComeFromStrategyRules() {
        List<String> registeredInDb =
                jdbcTemplate.queryForList(
                        "SELECT DISTINCT strategy_id FROM strategy_rules WHERE status ="
                                + " 'REGISTERED'",
                        String.class);

        Set<String> supported = executionSupport.supportedStrategyIds();

        assertThat(supported).containsExactlyInAnyOrderElementsOf(registeredInDb);
        // 규칙이 팀 결정 대기인 전략은 실행할 수 없으므로 목록에 없어야 한다.
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT DISTINCT strategy_id FROM strategy_rules WHERE status ="
                                        + " 'PENDING_DECISION' AND strategy_id NOT IN (SELECT"
                                        + " strategy_id FROM strategy_rules WHERE status ="
                                        + " 'REGISTERED')",
                                String.class))
                .as("팀 결정 대기 전략은 실행 지원 목록에 없다")
                .doesNotContainAnyElementsOf(supported);
    }

    @Test
    @DisplayName("활성 전략 전부를 요청하면 지원 여부에 따라 대상과 거부로 갈린다")
    void activeStrategiesSplitByExecutionSupport() {
        List<String> active =
                registry.activeStrategies().stream().map(StrategyRegistration::strategyId).toList();
        Set<String> supported = executionSupport.supportedStrategyIds();

        SearchTargets targets = resolver.resolve(active);

        assertThat(targets.registryVersion()).isEqualTo(registry.version());
        assertThat(targets.notRegistered()).as("등록표가 준 전략은 미등록일 수 없다").isEmpty();
        assertThat(targets.targets())
                .extracting(StrategyRegistration::strategyId)
                .allSatisfy(strategyId -> assertThat(supported).contains(strategyId));
        assertThat(targets.notExecutable())
                .allSatisfy(strategyId -> assertThat(supported).doesNotContain(strategyId));
        // 요청한 전략은 대상이거나 거부다. 조용히 사라지는 전략이 없어야 한다.
        assertThat(targets.targets().size() + targets.notExecutable().size())
                .isEqualTo(active.size());
    }

    @Test
    @DisplayName("실행 지원 전략의 전체 그리드가 등록값대로 펼쳐지고 유효성 판정까지 돈다")
    void expandsAndValidatesWithRealRegistry() {
        SearchTargets targets =
                resolver.resolve(
                        registry.activeStrategies().stream()
                                .map(StrategyRegistration::strategyId)
                                .toList());
        // 지원 전략이 하나도 없으면 아래 단언이 무의미해진다. 마이그레이션이 규칙을 등록해 두고 있다.
        assertThat(targets.targets()).as("004가 실행을 지원하는 전략이 있어야 한다").isNotEmpty();

        long expected =
                targets.targets().stream().mapToLong(StrategyRegistration::combinationCount).sum();
        List<GridPoint> points = expander.expand(targets.fullSearchSpace());
        List<ValidatedPoint> validated = validator.validate(points, new SearchContext(2));

        assertThat(targets.fullSearchSpace().combinationCount()).isEqualTo(expected);
        assertThat(points).hasSize((int) expected);
        assertThat(validated).hasSize(points.size());
        // 제외된 조합은 사유 코드를 등록표에서 그대로 받아 온다(FR-006).
        assertThat(validated)
                .filteredOn(point -> !point.isValid())
                .allSatisfy(point -> assertThat(point.verdict().reasonCode()).isNotBlank());
        // 판정이 실제로 돌아 결과가 채워졌는지만 본다. 지금 실행 지원되는 전략에 등록된 규칙이 없어
        // 제외가 0건일 수 있다 — 규칙이 도는 경로는 FileParameterRegistryTest와
        // CandidateValidatorTest가 본다. 여기서 확인할 것은 배선이다.
        assertThat(validated).allSatisfy(point -> assertThat(point.verdict()).isNotNull());
    }
}
