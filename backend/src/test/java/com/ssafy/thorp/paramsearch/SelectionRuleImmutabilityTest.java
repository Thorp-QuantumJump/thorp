package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 선정 규칙과 탐색 범위의 불변성(T110·T113, FR-013c, 원칙 VII).
 *
 * <p><b>기준을 바꿔 다시 보는 것은 새 실험이다.</b> 이미 끝난 묶음의 선정 규칙이나 범위를 고쳐 결과를 다시 그리면, 그 묶음이 "무엇을 기준으로 무엇을
 * 탐색했는가"에 대한 답이 시간에 따라 달라진다. 재현 기록이 기록이려면 한 번 적힌 값이 그대로 있어야 한다(원칙 VIII).
 *
 * <p>그래서 변경은 <b>새 묶음으로만</b> 일어난다. 여기서 보는 것이 둘이다 — 범위를 바꿔 다시 접수하면 새 {@code searchId}가 나오고 이전 묶음의
 * 상태·선정·스냅샷이 그대로인가, 그리고 <b>기존 묶음의 선정 규칙을 고치는 경로가 코드에 아예 없는가</b>.
 *
 * <p>뒤엣것을 코드에서 직접 확인하는 이유: "지금은 그런 경로가 없다"는 것만으로는 다음 사람이 무심코 추가하는 것을 막지 못한다. 저장 경로에 그 자리가 없다는 사실
 * 자체를 테스트로 고정한다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.param-search.enabled=true",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class SelectionRuleImmutabilityTest extends PostgresTestSupport {

    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    /** 묶음을 갱신하는 유일한 저장 경로. 여기에 스냅샷 항목이 들어오면 불변성이 깨진다. */
    private static final Path UPDATE_PATH =
            Path.of(
                    "src/main/java/com/ssafy/thorp/paramsearch/infrastructure/persistence/ParamSearchJpaEntity.java");

    @Autowired private ParamSearchService paramSearchService;
    @Autowired private ParamSearchRepository paramSearchRepository;
    @Autowired private ParameterRegistryPort registry;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private CandidateJobLauncher launcher;

    private final AtomicLong nextJobId = new AtomicLong(980_000L);

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        jdbcTemplate.update("DELETE FROM param_search");
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id >= 900000");
        jdbcTemplate.update("DELETE FROM backtest_run WHERE id >= 900000");
        nextJobId.set(980_000L);
        Mockito.when(launcher.launchCandidate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
        Mockito.when(launcher.launchBaseline(ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
    }

    @Test
    @DisplayName("범위를 바꿔 다시 접수하면 새 묶음이 생기고 이전 묶음은 그대로다")
    void narrowingTheRangeCreatesANewBundle() {
        ParamSearch first = accept(Map.of());
        SearchSpace spaceBefore = first.getSearchSpace();
        Map<String, Object> ruleBefore = first.getSelectionRule().toMap();
        ParamSearchStatus statusBefore = first.getStatus();

        ParamSearch second = accept(narrowedSpace());

        assertThat(second.getSearchId().value())
                .as("기준을 바꿔 다시 보는 것은 새 실험이다(FR-013c)")
                .isNotEqualTo(first.getSearchId().value());
        assertThat(second.getSearchSpace())
                .as("좁힌 범위가 실제로 달라야 이 검증이 성립한다")
                .isNotEqualTo(spaceBefore);

        ParamSearch reloaded = paramSearchRepository.findById(first.getId()).orElseThrow();
        assertThat(reloaded.getSearchSpace()).isEqualTo(spaceBefore);
        assertThat(reloaded.getSelectionRule().toMap()).isEqualTo(ruleBefore);
        assertThat(reloaded.getStatus())
                .as("이전 묶음의 상태 표시가 뒤늦게 달라지면 사용자가 본 화면이 거짓이 된다")
                .isEqualTo(statusBefore);
    }

    @Test
    @DisplayName("두 묶음은 각자의 스냅샷을 갖는다 — 나중 접수가 앞 묶음을 덮지 않는다")
    void eachBundleKeepsItsOwnSnapshot() {
        ParamSearch first = accept(Map.of());
        ParamSearch second = accept(narrowedSpace());

        ParamSearch firstReloaded = paramSearchRepository.findById(first.getId()).orElseThrow();
        ParamSearch secondReloaded = paramSearchRepository.findById(second.getId()).orElseThrow();

        assertThat(firstReloaded.getSearchSpace()).isNotEqualTo(secondReloaded.getSearchSpace());
        assertThat(firstReloaded.getRegistryVersion())
                .isEqualTo(secondReloaded.getRegistryVersion());
    }

    @Test
    @DisplayName("기존 묶음의 선정 규칙을 고치는 저장 경로가 없다")
    void thereIsNoUpdatePathForTheSelectionRule() throws IOException {
        String source = Files.readString(UPDATE_PATH, StandardCharsets.UTF_8);
        // import 줄에도 같은 낱말이 있어 메서드 뒤부터 끝을 찾는다.
        int start = source.indexOf("void applyProgress(");
        assertThat(start).as("갱신 경로의 이름이 바뀌면 이 검증이 조용히 무력해진다").isNotNegative();
        int end = source.indexOf("@PrePersist", start);
        String applyProgress = source.substring(start, end < 0 ? source.length() : end);

        for (String snapshotField :
                List.of("selectionRule", "searchSpace", "limits", "referenceVersions")) {
            assertThat(applyProgress)
                    .as("%s 는 접수 시점에 고정된다. 갱신 경로가 생기면 재현 기록이 시간에 따라 달라진다", snapshotField)
                    .doesNotContain("this." + snapshotField + " =");
        }
    }

    @Test
    @DisplayName("선정 규칙은 접수 때 한 번 정해지고 그 뒤 도메인에 바꾸는 메서드가 없다")
    void theDomainExposesNoRuleMutator() {
        ParamSearch search = accept(Map.of());

        assertThat(
                        java.util.Arrays.stream(ParamSearch.class.getMethods())
                                .map(java.lang.reflect.Method::getName)
                                .filter(name -> name.toLowerCase().contains("selectionrule"))
                                .toList())
                .as("읽는 것 말고 바꾸는 메서드가 있으면 안 된다")
                .containsExactly("getSelectionRule");
        assertThat(search.getSelectionRule()).isNotNull();
    }

    /** 등록된 후보값 중 앞의 둘만 남겨 범위를 좁힌다. 등록표 밖 값은 400이므로 안에서만 고른다(FR-002). */
    private Map<String, Map<String, List<Object>>> narrowedSpace() {
        Map<String, Map<String, List<Object>>> space = new java.util.LinkedHashMap<>();
        for (String strategyId : searchableStrategyIds()) {
            StrategyRegistration registration = registry.find(strategyId).orElseThrow();
            Map<String, List<Object>> axes = new java.util.LinkedHashMap<>();
            for (ParameterAxis axis : registration.axes()) {
                List<Object> values = new ArrayList<>(axis.searchValues());
                if (values.size() > 2) {
                    values = values.subList(0, 2);
                }
                axes.put(axis.name(), values);
            }
            if (!axes.isEmpty()) {
                space.put(strategyId, axes);
            }
        }
        return space;
    }

    private ParamSearch accept(Map<String, Map<String, List<Object>>> space) {
        ParamSearchResult accepted =
                paramSearchService.create(
                        new CreateParamSearchCommand(
                                null,
                                UNIVERSE,
                                searchableStrategyIds(),
                                null,
                                null,
                                null,
                                space,
                                registry.version()));
        return paramSearchRepository.findBySearchId(accepted.searchId()).orElseThrow();
    }

    private long insertRun() {
        long id = nextJobId.getAndIncrement();
        jdbcTemplate.update(
                "INSERT INTO backtest_run (id, backtest_id, strategy_id, strategy_version,"
                        + " parameters, instrument_ids, start_date, end_date, initial_cash,"
                        + " position_sizing_type, random_seed, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'STUB', '1', '{}'::jsonb, '[\"091160.XKRX\"]'::jsonb,"
                        + " DATE '2020-01-02', DATE '2026-08-31', 10000000.00,"
                        + " 'STRATEGY_DEFINED', 42, 'QUEUED', NOW(), NOW())",
                id,
                "BT-STUB-" + id);
        return id;
    }

    /** 등록표 활성 ∩ 004 지원 ∩ 축 보유. 전략 ID를 테스트에 적지 않는다(FR-025). */
    private List<String> searchableStrategyIds() {
        List<String> registered =
                jdbcTemplate.queryForList(
                        "SELECT DISTINCT strategy_id FROM strategy_rules WHERE status ="
                                + " 'REGISTERED'",
                        String.class);
        return registry.activeStrategies().stream()
                .filter(registration -> !registration.axes().isEmpty())
                .map(StrategyRegistration::strategyId)
                .filter(registered::contains)
                .toList();
    }
}
