package com.ssafy.thorp.backtest.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /backtests/registry}(spec 004 T025, README §1.11).
 *
 * <p>003 화면이 요청에 실을 규칙 버전과 기본 비용 버전을 시드 등록표에서 그대로 읽는지 본다. 마이그레이션이 채운 행을 그대로 단언하므로 별도 준비가 없다.
 */
class BacktestRegistryApiIT extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("로그인 없이 등록표 행과 서버 기본 비용 조건을 그대로 돌려준다")
    void exposesSeededRulesAndDefaultCostProfile() throws Exception {
        String body =
                mockMvc.perform(get("/backtests/registry"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(
                                jsonPath("$.data.defaultCostProfile.version")
                                        .value("PROVISIONAL-DEMO-1"))
                        .andExpect(jsonPath("$.data.defaultCostProfile.provisional").value(true))
                        // 비용 수치는 다른 응답과 같이 문자열 decimal 이고, 값은 시드 행 그대로다.
                        // 003 이 "비용 조건을 읽기 전용으로 확인"(FR-010)하는 값이라 정확히 고정한다.
                        // 소수 네 자리는 컬럼이 NUMERIC(8,4) 라서다 — 화면은 문자열을 그대로 믿지 말고
                        // 수치로 파싱해 표시한다.
                        .andExpect(
                                jsonPath("$.data.defaultCostProfile.commissionBps").value("0.0000"))
                        .andExpect(jsonPath("$.data.defaultCostProfile.spreadBps").value("0.0000"))
                        .andExpect(
                                jsonPath("$.data.defaultCostProfile.slippageBps").value("5.0000"))
                        .andExpect(jsonPath("$.data.defaultCostProfile.taxBps").value("0.0000"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        Map<String, List<JsonNode>> byStrategy =
                nodes(objectMapper.readTree(body).at("/data/strategyRules"))
                        .collect(Collectors.groupingBy(n -> n.get("strategyId").asText()));

        // 시드(V20260911110000): 다섯 전략 + TARGET_RISK_OVERLAY.
        // 오버레이는 전략이 아니지만 같은 표라 그대로 나가고, 화면은 strategyId 로 조인만 하므로 무해하다.
        assertThat(byStrategy.keySet())
                .containsExactlyInAnyOrder(
                        "FIXED_WEIGHT",
                        "EQUAL_WEIGHT",
                        "INVERSE_VOLATILITY",
                        "SECTOR_MOMENTUM",
                        "MINIMUM_VARIANCE",
                        "TARGET_RISK_OVERLAY");

        // 시드의 REGISTERED 는 다섯(고정 비중·동일 비중·역변동성·목표 위험 오버레이·최소분산)이고
        // 나머지 하나(섹터 모멘텀)만 PD-03 구현 전이라 PENDING_DECISION 이다.
        // 최소분산은 티켓 #330 이 MINIMUM_VARIANCE/1 을 REGISTERED 로 채우면서 넘어왔다.
        assertThat(
                        nodes(objectMapper.readTree(body).at("/data/strategyRules"))
                                .filter(row -> "REGISTERED".equals(row.get("status").asText()))
                                .count())
                .isEqualTo(5);

        // 전략당 한 행 — 화면이 어느 버전을 실을지 고를 필요가 없다는 것이 이 API 의 계약이다.
        // 발표 전에는 규칙을 제자리에서 갱신하므로 버전이 늘지 않는다(버전 교체는 Phase 10).
        byStrategy.forEach((strategyId, rows) -> assertThat(rows).as(strategyId).hasSize(1));

        JsonNode inverseVolatility = byStrategy.get("INVERSE_VOLATILITY").get(0);
        assertThat(inverseVolatility.get("rulesVersion").asText()).isEqualTo("1");
        assertThat(inverseVolatility.get("status").asText()).isEqualTo("REGISTERED");
        // PD-02 미확정이라 임시 규칙이다. 화면은 이 값으로 "임시" 표시를 켠다.
        assertThat(inverseVolatility.get("provisional").asBoolean()).isTrue();

        // PENDING_DECISION 을 걸러내지 않는다 — 화면이 '분석하기'를 막을 근거가 이 상태다.
        JsonNode momentum = byStrategy.get("SECTOR_MOMENTUM").get(0);
        assertThat(momentum.get("status").asText()).isEqualTo("PENDING_DECISION");
        assertThat(momentum.get("provisional").asBoolean()).isFalse();
    }

    private static java.util.stream.Stream<JsonNode> nodes(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out.stream();
    }
}
