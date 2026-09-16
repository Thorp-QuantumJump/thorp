package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.exception.ParamSearchRejectedException;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.port.StrategyExecutionSupportPort;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import com.ssafy.thorp.paramsearch.registry.RegistryFixtures;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 접수 거부가 남기는 기록(T091·T092, FR-005b·FR-020, Edge Case).
 *
 * <p><b>거부도 재현 기록이다.</b> 무엇을 왜 실행하지 않았는지가 남아야 사용자가 "범위를 좁혀 다시" 할 수 있고, 나중에 그 묶음을 되짚을 수 있다. 그래서 거부는
 * 예외를 던지면서도 묶음을 {@code REJECTED}로 <b>저장</b>한다.
 *
 * <p>거부에서 하지 <b>않는</b> 것도 함께 고정한다 — 표본을 뽑아 상한 안으로 줄여 실행하거나(FR-005b), 유효 조합이 없는데 제약을 느슨히 해 억지로 후보를
 * 만들지 않는다. 후보 행과 실행 행이 0건인지 보는 이유가 그것이다.
 *
 * <p>등록표는 fixture다. 운영 등록표로는 이 두 경로를 만들 수 없다 — 상한(운영 120·로컬 24)을 넘기려면 후보값이 그만큼 필요한데 등록된 축이 작고, 유효
 * 조합 0건을 만들려면 모든 조합을 걸러 내는 규칙이 필요한데 그런 전략은 실행 지원 목록에 없어 그 앞에서 다른 사유로 거부된다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.param-search.enabled=true",
            // 상한을 낮춰 둔다. 여기서 볼 것은 상한 값이 아니라 넘겼을 때의 동작이다.
            "thorp.param-search.max-candidates=2",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class ParamSearchRejectionIT extends PostgresTestSupport {

    private static final String VERSION = "FIXTURE-REJECT-1";
    private static final String STRATEGY = "FIXTURE_STRATEGY";
    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    @Autowired private ParamSearchRepository paramSearchRepository;
    @Autowired private SearchCandidateRepository candidateRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ParamSearchService paramSearchService;

    @MockitoBean private ParameterRegistryPort registry;
    @MockitoBean private StrategyExecutionSupportPort executionSupport;
    @MockitoBean private CandidateJobLauncher launcher;

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        jdbcTemplate.update("DELETE FROM param_search");
        Mockito.when(executionSupport.supportedStrategies())
                .thenReturn(RegistryFixtures.supporting(STRATEGY).supportedStrategies());
    }

    @Test
    @DisplayName("상한을 넘으면 422이고 묶음만 REJECTED로 남는다 — 표본을 뽑지 않는다")
    void overTheLimitIsRejectedWithoutSampling() {
        // 축 하나에 후보값 4개 = 조합 4건. 상한 2를 넘긴다.
        useRegistry(
                RegistryFixtures.registry(VERSION)
                        .strategy(STRATEGY, RegistryFixtures.axisOfSize("x", 4))
                        .build());

        assertThatThrownBy(() -> paramSearchService.create(command()))
                .isInstanceOf(ParamSearchRejectedException.class)
                .extracting(e -> ((ParamSearchRejectedException) e).getErrorCode())
                .isEqualTo(ErrorCode.CANDIDATE_LIMIT_EXCEEDED);

        ParamSearch rejected = onlySearch();
        assertThat(rejected.getStatus()).isEqualTo(ParamSearchStatus.REJECTED);
        assertThat(rejected.getStopReason()).isEqualTo(StopReason.CANDIDATE_LIMIT);
        assertThat(candidateRepository.findByParamSearchId(rejected.getId()))
                .as("상한을 넘겼다고 표본을 뽑아 실행하지 않는다(FR-005b)")
                .isEmpty();
        Mockito.verifyNoInteractions(launcher);
    }

    @Test
    @DisplayName("상한 초과 메시지에 유효 후보 수와 상한, 좁히는 방법이 들어 있다")
    void theLimitMessageTellsWhatToDo() {
        useRegistry(
                RegistryFixtures.registry(VERSION)
                        .strategy(STRATEGY, RegistryFixtures.axisOfSize("x", 4))
                        .build());

        assertThatThrownBy(() -> paramSearchService.create(command()))
                .hasMessageContaining("4")
                .hasMessageContaining("2")
                .as("화면이 '무엇을 하라'를 그대로 보여 줄 수 있어야 한다(FR-005b)")
                .hasMessageContaining("줄이");
    }

    @Test
    @DisplayName("유효 조합이 0건이면 422이고 어느 제약인지 한 문장이 남는다")
    void noValidCombinationIsRejectedWithTheReason() {
        // 모든 조합을 거르는 규칙. 판정은 등록표가 소유하므로 fixture가 그 자리를 대신한다.
        useRegistry(
                RegistryFixtures.registry(VERSION)
                        .strategy(STRATEGY, RegistryFixtures.axisOfSize("x", 2))
                        .rule(STRATEGY, "ALWAYS_VIOLATED", (parameters, context) -> true)
                        .build());

        assertThatThrownBy(() -> paramSearchService.create(command()))
                .isInstanceOf(ParamSearchRejectedException.class)
                .extracting(e -> ((ParamSearchRejectedException) e).getErrorCode())
                .isEqualTo(ErrorCode.NO_VALID_COMBINATION);

        ParamSearch rejected = onlySearch();
        assertThat(rejected.getStatus()).isEqualTo(ParamSearchStatus.REJECTED);
        assertThat(rejected.getStopReason()).isEqualTo(StopReason.NO_VALID_CANDIDATE);
        assertThat(rejected.getCounts().valid()).isZero();
        assertThat(rejected.getCounts().excluded())
                .as("제외된 조합의 수는 세어 둔다 — 0건이 '아무것도 없었다'와 구분된다")
                .isPositive();
        Mockito.verifyNoInteractions(launcher);
    }

    @Test
    @DisplayName("거부해도 묶음 식별자가 남아 사용자가 기록을 되짚을 수 있다")
    void theRejectedBundleKeepsItsIdentifier() {
        useRegistry(
                RegistryFixtures.registry(VERSION)
                        .strategy(STRATEGY, RegistryFixtures.axisOfSize("x", 4))
                        .build());

        assertThatThrownBy(() -> paramSearchService.create(command()))
                .isInstanceOfSatisfying(
                        ParamSearchRejectedException.class,
                        exception ->
                                assertThat(exception.getRejected().getSearchId().value())
                                        .startsWith("PS-"));
    }

    private void useRegistry(ParameterRegistryPort fixture) {
        Mockito.when(registry.version()).thenReturn(fixture.version());
        Mockito.when(registry.activeStrategies()).thenReturn(fixture.activeStrategies());
        Mockito.when(registry.baselineStrategyId()).thenReturn(fixture.baselineStrategyId());
        Mockito.when(registry.find(Mockito.anyString()))
                .thenAnswer(invocation -> fixture.find(invocation.getArgument(0)));
        Mockito.when(registry.validate(Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenAnswer(
                        invocation ->
                                fixture.validate(
                                        invocation.getArgument(0),
                                        invocation.getArgument(1),
                                        invocation.getArgument(2)));
    }

    private CreateParamSearchCommand command() {
        return new CreateParamSearchCommand(
                null, UNIVERSE, List.of(STRATEGY), null, null, null, Map.of(), VERSION);
    }

    private ParamSearch onlySearch() {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM param_search", Long.class);
        assertThat(ids).as("거부도 묶음 하나로 남는다(FR-020)").hasSize(1);
        return paramSearchRepository.findById(ids.get(0)).orElseThrow();
    }
}
