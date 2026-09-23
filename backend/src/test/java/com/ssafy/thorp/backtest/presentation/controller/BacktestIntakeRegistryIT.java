package com.ssafy.thorp.backtest.presentation.controller;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 화면을 우회한 요청도 서버가 막는지(T049·T051, FR-006, quickstart 5-1).
 *
 * <p><b>거절한 요청이 행을 남기지 않는 것까지 본다.</b> 상태 코드만 보면 "거절했지만 실행은 만들어진" 경우를 놓친다 — 접수는 거절을 쓰기보다 먼저 하기로 되어
 * 있고({@code BacktestService}의 {@code noRollbackFor}), 그 순서가 깨지면 절반만 쓰인 접수가 커밋된다.
 *
 * <p><b>[미완료] {@code registryVersion} 없는 요청의 202는 FR-006·SC-003 통과가 아니다.</b> 워커가 아직 등록표 축 이름을 읽지 못해
 * 관문을 전부에 걸 수 없어 남긴 과도기 경로이고, 그 사실을 여기서도 테스트 이름과 주석으로 붙박아 둔다. 004가 워커 계약을 맞춘 뒤 이 경로는 사라진다.
 *
 * <p>브로커 컨테이너는 띄우지 않는다({@code BacktestResultApiIT}와 같은 방식). 발행은 커밋 뒤라 접수 응답에 영향을 주지 않는다.
 */
class BacktestIntakeRegistryIT extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ParameterRegistryPort registry;

    private long newUser(String tag) {
        String suffix = tag + (System.nanoTime() % 1_000_000_000L);
        String email = "intake-" + suffix + "@example.com";
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

    private long countRequests() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM backtest_request", Long.class);
    }

    private long countRuns() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM backtest_run", Long.class);
    }

    /**
     * {@code EQUAL_WEIGHT}을 쓰는 이유: 004 등록표에 {@code REGISTERED}이고 003 등록표에 활성이며 축이 {@code
     * rebalancePeriod} 하나뿐이라, 거절 사유를 하나씩 따로 낼 수 있다. 목표 비중은 {@code FIXED_WEIGHT}에서만 보낼 수 있으므로 여기서는
     * 싣지 않는다.
     *
     * @param registryVersionJson `"1.0.0"` 같은 JSON 조각. {@code null}이면 필드를 통째로 생략한다
     */
    private String body(
            String registryVersionJson, String parametersJson, String initialCash, int day) {
        String registryLine =
                registryVersionJson == null
                        ? ""
                        : "    \"registryVersion\": " + registryVersionJson + ",\n";
        return """
                {
                  "executionType": "SINGLE",
                  "sectorAllocation": {
                    "mappingVersion": "SECTOR-ETF-2026-09",
                    "items": [
                      {"sectorId": "SEMICONDUCTOR", "instrumentId": "091160.XKRX"},
                      {"sectorId": "BATTERY", "instrumentId": "305720.XKRX"}
                    ]
                  },
                  "strategy": {
                    "strategyId": "EQUAL_WEIGHT",
                    "rulesVersion": "1",
                %s    "parameters": %s
                  },
                  "conditions": {
                    "start": "2024-02-%02d",
                    "end": "2024-11-29",
                    "initialCash": "%s"
                  }
                }
                """
                .formatted(registryLine, parametersJson, day, initialCash);
    }

    private ResultActions submit(long user, String requestBody) throws Exception {
        return mockMvc.perform(
                post("/backtests")
                        .with(csrf())
                        .with(as(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody));
    }

    /** 등록표가 준 유일한 선택지. 값을 여기 적지 않고 포트에서 읽는다 — 버전을 올리면 이 테스트도 따라간다. */
    private static final String VALID_REBALANCE =
            "{\"mode\": \"CALENDAR\", \"unit\": \"MONTH_END\"}";

    private static final String VALID_PARAMETERS = "{\"rebalancePeriod\": " + VALID_REBALANCE + "}";
    private static final String VALID_CASH = "10000000.00";

    @Test
    @DisplayName("등록표 버전이 다르면 409이고 실행·요청 행이 생기지 않는다")
    void 버전_불일치는_409이고_행을_남기지_않는다() throws Exception {
        long user = newUser("v");
        long requestsBefore = countRequests();
        long runsBefore = countRuns();

        submit(user, body("\"0.0.0\"", VALID_PARAMETERS, VALID_CASH, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("REGISTRY_VERSION_CHANGED"));

        assertThat(countRequests()).isEqualTo(requestsBefore);
        assertThat(countRuns()).isEqualTo(runsBefore);
    }

    @Test
    @DisplayName("등록표에 없는 값은 400이고 잘라 넣어 통과시키지 않는다")
    void 범위_밖_값은_400이다() throws Exception {
        long user = newUser("r");
        long runsBefore = countRuns();

        submit(
                        user,
                        body(
                                "\"" + registry.version() + "\"",
                                "{\"rebalancePeriod\": {\"mode\": \"TRADING_DAYS\", \"days\": 7}}",
                                VALID_CASH,
                                2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNREGISTERED_PARAMETER_VALUE"));

        assertThat(countRuns()).isEqualTo(runsBefore);
    }

    @Test
    @DisplayName("등록된 축이 빠지면 400이다 — 기본값으로 채워 통과시키지 않는다")
    void 빠진_축은_400이다() throws Exception {
        long user = newUser("m");
        long runsBefore = countRuns();

        submit(user, body("\"" + registry.version() + "\"", "{}", VALID_CASH, 3))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNREGISTERED_PARAMETER_VALUE"))
                .andExpect(
                        jsonPath("$.error.details.missingAxes")
                                .value(org.hamcrest.Matchers.hasItem("rebalancePeriod")));

        assertThat(countRuns()).isEqualTo(runsBefore);
    }

    @Test
    @DisplayName("시작 금액이 등록표의 공통 조건 범위 밖이면 400이다")
    void 시작_금액_범위_밖은_400이다() throws Exception {
        long user = newUser("c");
        long runsBefore = countRuns();

        // 등록표의 최소 시작 금액은 백만 원이다. 형식은 옳고 값이 범위 밖인 요청이라 400이다.
        submit(user, body("\"" + registry.version() + "\"", VALID_PARAMETERS, "1000.00", 4))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNREGISTERED_PARAMETER_VALUE"));

        assertThat(countRuns()).isEqualTo(runsBefore);
    }

    @Test
    @DisplayName("등록표와 맞는 요청은 202로 접수된다")
    void 등록표와_맞으면_202다() throws Exception {
        long user = newUser("o");

        submit(user, body("\"" + registry.version() + "\"", VALID_PARAMETERS, VALID_CASH, 5))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    /**
     * <b>[미완료] 이 202를 FR-006·SC-003 통과로 보고하지 않는다.</b>
     *
     * <p>004 픽스처와 워커가 아직 등록표 축 이름을 쓰지 않아 관문을 전부에 걸 수 없어 남긴 과도기 경로다. 여기서 보내는 {@code parameters}는
     * 등록표의 {@code rebalancePeriod}가 아니라 워커가 읽는 {@code rebalance} 문자열이라, 관문을 전부에 걸면 이 요청은 400이 된다.
     * 워커 계약이 등록표 축 이름에 맞춰지면 이 테스트는 "거절한다"로 바뀐다.
     */
    @Test
    @DisplayName("[미완료·과도기] registryVersion 없는 요청은 검증을 건너뛰고 202다")
    void registryVersion이_없으면_과도기_호환으로_통과한다() throws Exception {
        long user = newUser("l");

        submit(user, body(null, "{\"rebalance\": \"MONTH_END\"}", VALID_CASH, 6))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }
}
