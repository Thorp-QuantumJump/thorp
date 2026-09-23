package com.ssafy.thorp.paramsearch.presentation;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.paramsearch.SearchableStrategies;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.support.LauncherMockedIntegrationTestSupport;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 계약 검증(T042·T055·T056, contracts/param-search-api.md §1~§3).
 *
 * <p>여기서 보는 것은 <b>화면이 읽는 모양</b>이다 — 확인 화면의 네 덩어리가 다 있는지, 임시값 표시가 실리는지, 접수가 202와 식별자를 돌려주는지, 상태 조회가
 * 접수 시점 스냅샷을 그대로 보여 주는지.
 */
class ParamSearchApiIT extends LauncherMockedIntegrationTestSupport {

    private static final String UNIVERSE = "[\"091160.XKRX\", \"305720.XKRX\"]";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ParameterRegistryPort registry;

    @Autowired private CandidateJobLauncher launcher;

    private final AtomicLong nextJobId = new AtomicLong(910_000L);

    private long requesterId;

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        // 내 기록이 이 행들을 가리키므로 먼저 지운다(spec 006 my_record FK).
        jdbcTemplate.update("DELETE FROM my_record");
        jdbcTemplate.update("DELETE FROM param_search");
        // 결과 행이 실행 행을 FK로 참조한다. 자식을 먼저 지우지 않으면 다른 테스트가
        // 남긴 결과 때문에 이 정리가 통째로 실패한다.
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id >= 900000");
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
                                .with(csrf())
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
                // 접두를 단언하지 않는다 — PD-02 확정으로 접두가 사라졌고, 임시값을 다시 담는 날
                // 이 단언이 또 낡는다. 응답이 나르는 것은 '지금 등록표의 버전'이다.
                .andExpect(
                        jsonPath("$.data.registryVersion").value(Matchers.is(registry.version())))
                .andExpect(jsonPath("$.data.validationLabel").value("UNVALIDATED"))
                .andExpect(jsonPath("$.data.commonPeriod.start").exists());
    }

    /**
     * 후보값마다 표시 문구가 함께 실린다(contracts §1).
     *
     * <p><b>값만 실어 보내면 화면이 그릴 수 없는 축이 있다.</b> 리밸런싱 주기처럼 후보값이 객체인 축은 등록표의 문구 없이는 그릴 방법이 없어 화면에 {@code
     * [object Object]}가 나왔다. 개수와 순서가 {@code values}와 맞아야 화면이 인덱스로 짝지을 수 있다.
     *
     * <p>문구의 내용을 여기 적지 않는다 — 그것은 등록표가 정하고 판이 올라가면 바뀐다. 단언하는 것은 <b>비어 있지 않다</b>와 <b>값과 같은 수만큼
     * 있다</b>이다.
     */
    @Test
    @DisplayName("미리보기의 축은 후보값과 같은 수·같은 순서의 표시 문구를 함께 나른다")
    void previewCarriesValueLabels() throws Exception {
        String body =
                mockMvc.perform(
                                post("/param-searches/preview")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(previewBody()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        JsonNode spaces = objectMapper.readTree(body).path("data").path("spaceSummary");
        assertThat(spaces).isNotEmpty();

        int checkedAxes = 0;
        for (JsonNode space : spaces) {
            for (JsonNode axis : space.path("axes")) {
                JsonNode values = axis.path("values");
                JsonNode labels = axis.path("valueLabels");

                assertThat(labels.isArray())
                        .as("axis %s must carry valueLabels", axis.path("axis").asText())
                        .isTrue();
                assertThat(labels).hasSameSizeAs(values);
                for (JsonNode label : labels) {
                    // 그릴 수 없는 문구를 문구라고 내보내지 않는다.
                    assertThat(label.asText()).isNotBlank().doesNotContain("object Object");
                }
                checkedAxes++;
            }
        }
        assertThat(checkedAxes).as("등록표에 축이 하나도 없으면 이 검사가 헛돈다").isPositive();
    }

    @Test
    @DisplayName("실행은 202와 식별자를 돌려주고 상태 조회가 접수 스냅샷을 보여 준다")
    void createThenGet() throws Exception {
        String registryVersion = registryVersionFromPreview();

        String created =
                mockMvc.perform(
                                post("/param-searches")
                                        .with(csrf())
                                        .with(as(requesterId))
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
                // 아직 끝난 후보가 없어 잠정 선정도 없다.
                .andExpect(jsonPath("$.data.selection").doesNotExist());
    }

    @Test
    @DisplayName("등록표 버전이 다르면 409 REGISTRY_VERSION_CHANGED다")
    void staleVersionIsConflict() throws Exception {
        mockMvc.perform(
                        post("/param-searches")
                                .with(csrf())
                                .with(as(requesterId))
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
                                .with(csrf())
                                .with(as(requesterId))
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
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("로그인 없이 실행하면 처리된 오류다 — 500이 아니다")
    void createWithoutRequesterIsHandled() throws Exception {
        String registryVersion = registryVersionFromPreview();

        // 접수는 기준·후보를 004로 넘기는데 backtest_request.requester_id 가 NOT NULL 이다(spec 004 R-12).
        // 요청자를 null 로 통과시키면 발행 직전에 NullPointerException 으로 죽어 사용자에게 500이 나간다.
        mockMvc.perform(
                        post("/param-searches")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(registryVersion)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("미리보기는 요청자가 없어도 된다 — 저장도 실행도 하지 않는다")
    void previewWorksWithoutRequester() throws Exception {
        mockMvc.perform(
                        post("/param-searches/preview")
                                .with(csrf())
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

    @Test
    @DisplayName("취소·재시도는 소유자만 한다 — 비로그인은 401, 남이면 없는 묶음과 같은 404다")
    void stateChangesRequireOwner() throws Exception {
        String created =
                mockMvc.perform(
                                post("/param-searches")
                                        .with(csrf())
                                        .with(as(requesterId))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(createBody(registryVersionFromPreview())))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String searchId = objectMapper.readTree(created).path("data").path("searchId").asText();
        long stranger = newUser();

        for (String path : java.util.List.of("/cancel", "/retry-failed")) {
            mockMvc.perform(post("/param-searches/{id}" + path, searchId).with(csrf()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

            // 403이면 "그 식별자는 있다"가 새어 나간다. searchId 는 날짜+순번이라 추측할 수 있다.
            mockMvc.perform(
                            post("/param-searches/{id}" + path, searchId)
                                    .with(csrf())
                                    .with(as(stranger)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }

        // 소유자는 그대로 취소할 수 있다.
        mockMvc.perform(
                        post("/param-searches/{id}/cancel", searchId)
                                .with(csrf())
                                .with(as(requesterId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.searchId").value(searchId));
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
        return SearchableStrategies.of(registry, jdbcTemplate).stream()
                .map(id -> "\"" + id + "\"")
                .reduce((left, right) -> left + ", " + right)
                .map(joined -> "[" + joined + "]")
                .orElse("[]");
    }

    private JsonNode preview() throws Exception {
        String body =
                mockMvc.perform(
                                post("/param-searches/preview")
                                        .with(csrf())
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
