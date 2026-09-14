package com.ssafy.thorp.paramsearch.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.port.ParameterRegistryPort;
import com.ssafy.thorp.paramsearch.application.port.StrategyRegistration;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 계약 검증(T042·T055·T056, contracts/param-search-api.md §1~§3).
 *
 * <p>여기서 보는 것은 <b>화면이 읽는 모양</b>이다 — 확인 화면의 네 덩어리가 다 있는지, 임시값 표시가 실리는지, 접수가 202와 식별자를 돌려주는지, 상태 조회가
 * 접수 시점 스냅샷을 그대로 보여 주는지.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.param-search.enabled=true",
            // 접수는 요청자를 요구한다 — backtest_request.requester_id 가 NOT NULL 이다(spec 004 R-12).
            "thorp.auth.trust-user-id-header=true",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class ParamSearchApiIT extends PostgresTestSupport {

    private static final String UNIVERSE = "[\"091160.XKRX\", \"305720.XKRX\"]";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ParameterRegistryPort registry;

    @MockitoBean private CandidateJobLauncher launcher;

    private final AtomicLong nextJobId = new AtomicLong(910_000L);

    private long requesterId;

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        jdbcTemplate.update("DELETE FROM param_search");
        jdbcTemplate.update("DELETE FROM backtest_run WHERE id >= 900000");
        requesterId = newUser();
        Mockito.when(launcher.launchCandidate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
        Mockito.when(launcher.launchBaseline(ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
    }

    @Test
    @DisplayName("미리보기는 확인 화면의 네 덩어리와 임시값 표시를 돌려준다")
    void previewReturnsFourSections() throws Exception {
        mockMvc.perform(
                        post("/param-searches/preview")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(previewBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 1) 대상 전략 2) 탐색 범위 요약 3) 예상 작업량 4) 적용 한도
                .andExpect(jsonPath("$.data.strategies").isArray())
                .andExpect(jsonPath("$.data.spaceSummary").isArray())
                .andExpect(jsonPath("$.data.workload.totalCombinations").isNumber())
                .andExpect(jsonPath("$.data.limits.maxCandidates").isNumber())
                .andExpect(
                        jsonPath("$.data.limits.provisional")
                                .value(Matchers.hasItem("maxCandidates")))
                .andExpect(
                        jsonPath("$.data.registryVersion")
                                .value(Matchers.startsWith("PROVISIONAL-")))
                .andExpect(jsonPath("$.data.validationLabel").value("UNVALIDATED"))
                .andExpect(jsonPath("$.data.commonPeriod.start").exists());
    }

    @Test
    @DisplayName("실행은 202와 식별자를 돌려주고 상태 조회가 접수 스냅샷을 보여 준다")
    void createThenGet() throws Exception {
        String registryVersion = registryVersionFromPreview();

        String created =
                mockMvc.perform(
                                post("/param-searches")
                                        .header(CurrentRequester.DEV_HEADER, requesterId)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(createBody(registryVersion)))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.searchId").value(Matchers.startsWith("PS-")))
                        .andExpect(jsonPath("$.data.status").exists())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String searchId = objectMapper.readTree(created).path("data").path("searchId").asText();

        mockMvc.perform(get("/param-searches/{searchId}", searchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.searchId").value(searchId))
                .andExpect(jsonPath("$.data.counts.expected").isNumber())
                .andExpect(jsonPath("$.data.config.registryVersion").value(registryVersion))
                .andExpect(jsonPath("$.data.config.searchMethod").value("FULL_GRID"))
                .andExpect(jsonPath("$.data.config.randomSeed").doesNotExist())
                .andExpect(jsonPath("$.data.config.space").isMap())
                // 선정은 아직 없다 — COMPLETED·PARTIAL 전에는 null이다(계약 §3).
                .andExpect(jsonPath("$.data.selection").doesNotExist());
    }

    @Test
    @DisplayName("등록표 버전이 다르면 409 REGISTRY_VERSION_CHANGED다")
    void staleVersionIsConflict() throws Exception {
        mockMvc.perform(
                        post("/param-searches")
                                .header(CurrentRequester.DEV_HEADER, requesterId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody("PROVISIONAL-19990101")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REGISTRY_VERSION_CHANGED"));
    }

    @Test
    @DisplayName("등록표에 없는 값으로 범위를 좁히면 400 UNREGISTERED_PARAMETER_VALUE다")
    void unregisteredValueIsRejected() throws Exception {
        String registryVersion = registryVersionFromPreview();
        String strategyId = firstStrategyIdFromPreview();
        String axis = firstAxisFromPreview();
        String body =
                """
                {
                  "universe": { "instrumentIds": %s },
                  "strategy": { "strategyIds": ["%s"] },
                  "space": { "%s": { "%s": [999999] } },
                  "registryVersion": "%s"
                }
                """
                        .formatted(UNIVERSE, strategyId, strategyId, axis, registryVersion);

        mockMvc.perform(
                        post("/param-searches")
                                .header(CurrentRequester.DEV_HEADER, requesterId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNREGISTERED_PARAMETER_VALUE"));
    }

    @Test
    @DisplayName("종목 형식이 틀리면 400이다")
    void malformedInstrumentIsRejected() throws Exception {
        String body =
                """
                {
                  "universe": { "instrumentIds": ["005930"] },
                  "strategy": { "strategyIds": ["ANY"] }
                }
                """;

        mockMvc.perform(
                        post("/param-searches/preview")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("요청자 없이 실행하면 처리된 오류다 — 500이 아니다")
    void createWithoutRequesterIsHandled() throws Exception {
        String registryVersion = registryVersionFromPreview();

        // 접수는 기준·후보를 004로 넘기는데 backtest_request.requester_id 가 NOT NULL 이다(spec 004 R-12).
        // 요청자를 null 로 통과시키면 발행 직전에 NullPointerException 으로 죽어 사용자에게 500이 나간다.
        mockMvc.perform(
                        post("/param-searches")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(registryVersion)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("미리보기는 요청자가 없어도 된다 — 저장도 실행도 하지 않는다")
    void previewWorksWithoutRequester() throws Exception {
        mockMvc.perform(
                        post("/param-searches/preview")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(previewBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("없는 묶음 조회는 404다")
    void unknownSearchIsNotFound() throws Exception {
        mockMvc.perform(get("/param-searches/{searchId}", "PS-20000101-000001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    /** 접수가 요구하는 요청자. backtest_request.requester_id 가 users 를 참조한다. */
    private long newUser() {
        String suffix = String.valueOf(System.nanoTime());
        String email = "psapi-" + suffix + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "u" + suffix,
                suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private String previewBody() {
        return """
               {
                 "universe": { "instrumentIds": %s },
                 "strategy": { "strategyIds": %s }
               }
               """
                .formatted(UNIVERSE, supportedStrategyIdsJson());
    }

    private String createBody(String registryVersion) {
        return """
               {
                 "universe": { "instrumentIds": %s },
                 "strategy": { "strategyIds": %s },
                 "registryVersion": "%s"
               }
               """
                .formatted(UNIVERSE, supportedStrategyIdsJson(), registryVersion);
    }

    /**
     * 탐색 대상이 되는 전략 — 003 등록표의 활성 전략과 004 지원 목록의 <b>교집합</b>이다(FR-026).
     *
     * <p>한쪽만 보면 안 된다. 004에 규칙이 등록됐지만 등록표에 없는 전략(시나리오 오버레이 등)을 넣으면 접수가 {@code
     * STRATEGY_NOT_REGISTERED}로 거부되어 이 테스트가 계약이 아니라 그 거부를 확인하게 된다.
     */
    private String supportedStrategyIdsJson() {
        List<String> registeredIn004 =
                jdbcTemplate.queryForList(
                        "SELECT DISTINCT strategy_id FROM strategy_rules WHERE status ="
                                + " 'REGISTERED'",
                        String.class);
        return registry.activeStrategies().stream()
                .map(StrategyRegistration::strategyId)
                .filter(registeredIn004::contains)
                .map(id -> "\"" + id + "\"")
                .reduce((left, right) -> left + ", " + right)
                .map(joined -> "[" + joined + "]")
                .orElse("[]");
    }

    private JsonNode preview() throws Exception {
        String body =
                mockMvc.perform(
                                post("/param-searches/preview")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(previewBody()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private String registryVersionFromPreview() throws Exception {
        return preview().path("registryVersion").asText();
    }

    private String firstStrategyIdFromPreview() throws Exception {
        return preview().path("spaceSummary").get(0).path("strategyId").asText();
    }

    /** 첫 전략의 첫 축. 축이 없는 전략이 먼저 오면 그 전략은 좁힐 축이 없으므로 축이 있는 첫 전략을 찾는다. */
    private String firstAxisFromPreview() throws Exception {
        JsonNode spaces = preview().path("spaceSummary");
        for (JsonNode space : spaces) {
            if (space.path("axes").size() > 0) {
                return space.path("axes").get(0).path("axis").asText();
            }
        }
        throw new IllegalStateException("등록표에 축이 있는 전략이 없다");
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
}
