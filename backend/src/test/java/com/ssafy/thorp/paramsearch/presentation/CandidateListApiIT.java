package com.ssafy.thorp.paramsearch.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.SearchCounts;
import com.ssafy.thorp.paramsearch.domain.model.SelectionRule;
import com.ssafy.thorp.paramsearch.domain.vo.GridCoordinates;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 후보 목록 계약(T071·T080, contracts/param-search-api.md §4).
 *
 * <p>여기서 지키는 것 하나 — <b>계산되지 않은 후보의 성과는 {@code null}이고 0이 아니다</b>(FR-011, 원칙 VI). 0으로 채우면 "손실
 * 없음"·"수익 0%"로 읽혀 실제로 그런 결과를 낸 후보와 구분되지 않는다. 화면이 그 둘을 다르게 보여 줄 수 있으려면 응답이 먼저 달라야 한다.
 *
 * <p>후보는 저장소로 직접 만든다 — 여기서 볼 것은 접수 흐름이 아니라 <b>응답의 모양</b>이다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.param-search.enabled=true",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class CandidateListApiIT extends PostgresTestSupport {

    private static final String STRATEGY = "TEST_STRATEGY";

    @Autowired private MockMvc mockMvc;
    @Autowired private ParamSearchRepository paramSearchRepository;
    @Autowired private SearchCandidateRepository candidateRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String searchId;

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        jdbcTemplate.update("DELETE FROM param_search");
        ParamSearch saved = paramSearchRepository.save(newSearch());
        searchId = saved.getSearchId().value();
        candidateRepository.saveAll(
                saved.getId(),
                List.of(
                        SearchCandidate.pending(1, STRATEGY, parameters(0), coordinates(0)),
                        SearchCandidate.excluded(
                                2, STRATEGY, parameters(1), coordinates(1), "RULE", "제약 위반"),
                        SearchCandidate.pending(3, STRATEGY, parameters(2), coordinates(2))
                                .markDispatched(nextRunId())
                                .markFailed("ENGINE_ERROR", "후보 실행이 실패했습니다")));
    }

    @Test
    @DisplayName("계산되지 않은 후보의 성과는 null이고 0이 아니다")
    void unevaluatedCandidatesCarryNullsNotZeros() throws Exception {
        mockMvc.perform(get("/param-searches/{searchId}/candidates", searchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                // 0이 아니라 null이다 — 0은 "손실 없음"으로 읽혀 실제 0과 구분되지 않는다.
                .andExpect(
                        jsonPath(
                                "$.data.content[*].netReturn",
                                Matchers.everyItem(Matchers.nullValue())))
                .andExpect(
                        jsonPath(
                                "$.data.content[*].maxDrawdown",
                                Matchers.everyItem(Matchers.nullValue())))
                .andExpect(
                        jsonPath(
                                "$.data.content[*].tradingCost",
                                Matchers.everyItem(Matchers.nullValue())))
                .andExpect(
                        jsonPath(
                                "$.data.content[*].stability",
                                Matchers.everyItem(Matchers.nullValue())));
    }

    @Test
    @DisplayName("후보 식별자와 기본 필드가 계약대로 실린다")
    void itemCarriesTheContractFields() throws Exception {
        mockMvc.perform(get("/param-searches/{searchId}/candidates", searchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.content[0].candidateId").value(searchId + "-C001"))
                .andExpect(jsonPath("$.data.content[0].candidateNo").value(1))
                .andExpect(jsonPath("$.data.content[0].strategyId").value(STRATEGY))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.content[0].validationLabel").value("UNVALIDATED"))
                .andExpect(jsonPath("$.data.content[0].selected").value(false))
                .andExpect(jsonPath("$.data.content[0].retryCount").value(0));
    }

    @Test
    @DisplayName("상태로 거를 수 있다")
    void filtersByStatus() throws Exception {
        mockMvc.perform(
                        get("/param-searches/{searchId}/candidates", searchId)
                                .param("status", "EXCLUDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("EXCLUDED"))
                // 제외 후보는 실행이 없으므로 backtestId 도 없다.
                .andExpect(jsonPath("$.data.content[0].backtestId").doesNotExist());
    }

    @Test
    @DisplayName("상태 값은 대소문자·공백을 흡수하고 모르는 값만 400이다")
    void statusBindingAbsorbsCaseAndWhitespace() throws Exception {
        // 화면이 보내는 문자열이 저장 enum 과 정확히 같은 표기여야 한다면, 표기 하나 틀릴 때마다
        // 조회가 조용히 0건이 된다. 흡수는 하되 모르는 값은 400으로 드러낸다.
        for (String raw : List.of("EXCLUDED", "excluded", "ExClUdEd", "  EXCLUDED  ")) {
            mockMvc.perform(
                            get("/param-searches/{searchId}/candidates", searchId)
                                    .param("status", raw))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }
        for (String raw : List.of("BOGUS", "COMPLETE")) {
            mockMvc.perform(
                            get("/param-searches/{searchId}/candidates", searchId)
                                    .param("status", raw))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        }
    }

    @Test
    @DisplayName("모르는 정렬 방향은 400이다 — 조용히 뒤집지 않는다")
    void unknownSortDirectionIsRejected() throws Exception {
        for (String raw : List.of("candidateNo,asc", "candidateNo,DESC", "candidateNo,desc")) {
            mockMvc.perform(
                            get("/param-searches/{searchId}/candidates", searchId)
                                    .param("sort", raw))
                    .andExpect(status().isOk());
        }
        // 오타를 기본값으로 접으면 "수익이 큰 순"이라 적힌 화면이 가장 낮은 후보를 맨 위에 보인다.
        mockMvc.perform(
                        get("/param-searches/{searchId}/candidates", searchId)
                                .param("sort", "netReturn,dsec"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("허용 목록 밖 정렬과 과한 size는 400이다")
    void rejectsUnknownSortAndOversizedPage() throws Exception {
        mockMvc.perform(
                        get("/param-searches/{searchId}/candidates", searchId)
                                .param("sort", "secretColumn"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        mockMvc.perform(get("/param-searches/{searchId}/candidates", searchId).param("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("허용된 정렬 기준은 받는다")
    void acceptsAllowedSorts() throws Exception {
        for (String sort :
                List.of("candidateNo", "netReturn", "maxDrawdown", "tradingCost", "stability")) {
            mockMvc.perform(
                            get("/param-searches/{searchId}/candidates", searchId)
                                    .param("sort", sort))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("없는 묶음의 후보 목록은 404다")
    void unknownSearchIsNotFound() throws Exception {
        mockMvc.perform(get("/param-searches/{searchId}/candidates", "PS-20000101-000001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    private static ParameterCombination parameters(int index) {
        return ParameterCombination.of(Map.of("x", index));
    }

    private static GridCoordinates coordinates(int index) {
        return GridCoordinates.of(Map.of("x", index));
    }

    /** 후보가 FK로 가리킬 실행 행. 값 자체는 이 테스트의 관심사가 아니다. */
    private long nextRunId() {
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(id), 930000) + 1 FROM backtest_run", Long.class);
        jdbcTemplate.update(
                "INSERT INTO backtest_run (id, backtest_id, strategy_id, strategy_version,"
                        + " parameters, instrument_ids, start_date, end_date, initial_cash,"
                        + " position_sizing_type, random_seed, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'STUB', '1', '{}'::jsonb, '[\"091160.XKRX\"]'::jsonb,"
                        + " DATE '2020-01-02', DATE '2026-08-31', 10000000.00,"
                        + " 'STRATEGY_DEFINED', 42, 'FAILED', NOW(), NOW())",
                id,
                "BT-STUB-" + id);
        return id;
    }

    private static ParamSearch newSearch() {
        return ParamSearch.create(
                null,
                List.of(STRATEGY),
                List.of("091160.XKRX"),
                "PROVISIONAL-TEST",
                SearchSpace.of(
                        Map.of(
                                STRATEGY,
                                SearchSpace.StrategySpace.of(
                                        "테스트 전략",
                                        List.of(SearchSpace.Axis.of("x", List.of(0, 1, 2)))))),
                new SelectionRule(
                        new SelectionRule.DrawdownFilter("NOT_APPLIED", "NO_BASELINE_REGISTERED"),
                        new SelectionRule.StabilityFilter("BATCH_PERCENTILE", 50),
                        "NET_RETURN",
                        5,
                        "SHOW_ALL_TIED",
                        new SelectionRule.StabilityFormula(
                                "SINGLE_AXIS_ONE_STEP", "MEAN_ABS_NET_RETURN_DIFF"),
                        List.of("displayCount")),
                null,
                Map.of("maxCandidates", 24),
                Map.of("registry", "PROVISIONAL-TEST"),
                Map.of("rejectedBeforePlanning", true),
                SearchCounts.initial(3, 2, 1));
    }
}
