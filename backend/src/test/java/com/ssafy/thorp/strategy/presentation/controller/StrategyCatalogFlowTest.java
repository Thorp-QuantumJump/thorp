package com.ssafy.thorp.strategy.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.strategy.application.service.StrategyCatalogService;
import com.ssafy.thorp.strategy.application.service.StrategyConfigValidationService;
import com.ssafy.thorp.strategy.infrastructure.catalog.ClasspathStrategyRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 배포되는 정의로 조회 → 값 수정 → 판정까지 실제로 도는지 확인한다.
 *
 * <p>대역을 쓰지 않는다. 레지스트리·서비스·컨트롤러를 실제로 엮어 클래스패스의 전략 정의를 그대로 쓴다 — 각 조각이 따로 맞는 것과 이어 붙였을 때 도는 것은 다른
 * 문제이고, 이 기능에서 확인해야 하는 것은 후자다. DB·브로커는 이 경로에 없다.
 */
@WebMvcTest(StrategyController.class)
@Import({
    ClasspathStrategyRegistry.class,
    StrategyCatalogService.class,
    StrategyConfigValidationService.class
})
class StrategyCatalogFlowTest {

    private static final String VALIDATE_URL = "/strategies/config/validate";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("전략을 고르고 값을 바꿔 보낸 뒤 판정까지 이어진다")
    void selectThenAdjustThenValidate() throws Exception {
        // 1) 무슨 전략이 있는지 본다
        mockMvc.perform(get("/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[?(@.strategyId=='MOMENTUM')]").exists());

        // 2) 고른 전략이 무슨 값을 요구하는지 본다
        mockMvc.perform(get("/strategies/MOMENTUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parameters[0].name").value("lookbackDays"))
                .andExpect(jsonPath("$.data.parameters[0].required").value(true))
                .andExpect(jsonPath("$.data.parameters[0].numericRange.min").value(5))
                .andExpect(jsonPath("$.data.parameters[0].numericRange.max").value(250))
                .andExpect(jsonPath("$.data.parameters[1].name").value("entryThreshold"))
                .andExpect(jsonPath("$.data.parameters[1].required").value(false))
                .andExpect(jsonPath("$.data.parameters[1].defaultValue").value(0));

        // 3) 기본값을 그대로 두고 필수 값만 바꿔 보낸다 → 수용, 생략한 값은 기본값으로 채워진다
        mockMvc.perform(
                        post(VALIDATE_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "strategyId": "MOMENTUM",
                                          "executionScope": "BACKTEST",
                                          "parameters": { "lookbackDays": 60 }
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.definitionVersion").value(1))
                .andExpect(jsonPath("$.data.executionScope").value("BACKTEST"))
                .andExpect(jsonPath("$.data.violations").isEmpty())
                .andExpect(jsonPath("$.data.resolvedParameters.lookbackDays").value(60))
                .andExpect(jsonPath("$.data.resolvedParameters.entryThreshold").value(0))
                .andExpect(jsonPath("$.data.requiredHistoryBars").value(61));

        // 4) 선택 파라미터까지 바꿔 보낸다 → 보낸 값이 그대로 확정된다
        mockMvc.perform(
                        post(VALIDATE_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "strategyId": "MOMENTUM",
                                          "parameters": { "lookbackDays": 120, "entryThreshold": 0.05 }
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                // executionScope 를 생략하면 BACKTEST 로 본다
                .andExpect(jsonPath("$.data.executionScope").value("BACKTEST"))
                .andExpect(jsonPath("$.data.resolvedParameters.entryThreshold").value(0.05))
                .andExpect(jsonPath("$.data.requiredHistoryBars").value(121));
    }

    @Test
    @DisplayName("정의에 없는 파라미터를 보내면 그 이름을 짚어 거부한다")
    void rejectsUnknownParameter() throws Exception {
        mockMvc.perform(
                        post(VALIDATE_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "strategyId": "MOMENTUM",
                                          "parameters": { "lookbackDays": 60, "topN": 5 }
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.violations.length()").value(1))
                .andExpect(jsonPath("$.data.violations[0].code").value("UNKNOWN_PARAMETER"))
                .andExpect(jsonPath("$.data.violations[0].parameterName").value("topN"))
                .andExpect(jsonPath("$.data.violations[0].message").isNotEmpty())
                // 거부일 때도 두 필드는 키째 남고 값만 null 이다. 소비 지점이 키의 유무로 분기하지 않게 한다.
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.allOf(
                                                org.hamcrest.Matchers.containsString(
                                                        "\"resolvedParameters\":null"),
                                                org.hamcrest.Matchers.containsString(
                                                        "\"requiredHistoryBars\":null"))));
    }

    @Test
    @DisplayName("타입이 다르면 어느 타입을 기대했는지와 함께 거부한다")
    void rejectsTypeMismatch() throws Exception {
        mockMvc.perform(
                        post(VALIDATE_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "strategyId": "MOMENTUM",
                                          "parameters": { "lookbackDays": "60" }
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.violations[0].code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.data.violations[0].parameterName").value("lookbackDays"))
                .andExpect(jsonPath("$.data.violations[0].detail.expectedType").value("INTEGER"));
    }

    @Test
    @DisplayName("범위를 벗어나면 허용 범위와 실제 값을 함께 알려 준다")
    void rejectsOutOfRange() throws Exception {
        mockMvc.perform(
                        post(VALIDATE_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "strategyId": "MOMENTUM",
                                          "parameters": { "lookbackDays": 9999 }
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.violations[0].code").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.data.violations[0].detail.min").value(5))
                .andExpect(jsonPath("$.data.violations[0].detail.max").value(250))
                .andExpect(jsonPath("$.data.violations[0].detail.actual").value(9999));
    }

    @Test
    @DisplayName("여러 곳이 틀리면 한 번에 모두 알려 준다")
    void reportsEveryViolationAtOnce() throws Exception {
        mockMvc.perform(
                        post(VALIDATE_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "strategyId": "SMA_CROSSOVER",
                                          "parameters": {
                                            "shortPeriod": 100,
                                            "longPeriod": 500,
                                            "rebalancePeriod": "HOURLY",
                                            "topN": 5
                                          }
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.violations.length()").value(3))
                .andExpect(jsonPath("$.data.violations[0].code").value("UNKNOWN_PARAMETER"))
                .andExpect(jsonPath("$.data.violations[1].code").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.data.violations[1].parameterName").value("longPeriod"))
                .andExpect(jsonPath("$.data.violations[2].code").value("NOT_IN_ENUM"))
                .andExpect(jsonPath("$.data.violations[2].parameterName").value("rebalancePeriod"));
    }

    @Test
    @DisplayName("지원하지 않는 실행 목적은 전략을 특정해 거부한다")
    void rejectsUnsupportedExecutionScope() throws Exception {
        // MACD_SIGNAL 은 BACKTEST 전용이라 LIVE 목록에 없다
        mockMvc.perform(get("/strategies").param("executionScope", "LIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.strategyId=='MACD_SIGNAL')]").doesNotExist());

        mockMvc.perform(
                        post(VALIDATE_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "strategyId": "MACD_SIGNAL",
                                          "executionScope": "LIVE",
                                          "parameters": { "fastPeriod": 12, "slowPeriod": 26 }
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.executionScope").value("LIVE"))
                .andExpect(jsonPath("$.data.violations.length()").value(1))
                .andExpect(
                        jsonPath("$.data.violations[0].code")
                                .value("EXECUTION_SCOPE_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.data.violations[0].detail.supported").value("BACKTEST"));
    }

    @Test
    @DisplayName("없는 전략은 조회에서 404, 판정에서는 UNKNOWN_STRATEGY")
    void unknownStrategyIsHandledOnBothPaths() throws Exception {
        mockMvc.perform(get("/strategies/NO_SUCH_STRATEGY"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(
                        post(VALIDATE_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"strategyId":"momentum","parameters":{"lookbackDays":60}}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(
                        jsonPath("$.data.definitionVersion")
                                .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.violations[0].code").value("UNKNOWN_STRATEGY"))
                .andExpect(
                        jsonPath("$.data.violations[0].parameterName")
                                .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("응답 JSON이 계약 파일의 정의와 같다")
    void responseMatchesDefinitionFile() throws Exception {
        String body =
                mockMvc.perform(get("/strategies/SMA_CROSSOVER"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        JsonNode responded = objectMapper.readTree(body).get("data");
        JsonNode declared =
                objectMapper.readTree(
                        Files.readString(
                                Path.of("src/main/resources/strategies/SMA_CROSSOVER.json")));

        // 계약 파일이 명시한 null(예: "unit": null)은 응답에서 필드째 빠진다. 스키마가 값이 아니라
        // 필드의 유무로 타입별 배타성을 표현하기 때문이다. 그 차이만 걷어내면 둘은 같아야 한다.
        assertThat(responded).isEqualTo(withoutNulls(declared));
    }

    private static JsonNode withoutNulls(JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode copy = node.deepCopy();
            node.properties()
                    .forEach(
                            entry -> {
                                if (entry.getValue().isNull()) {
                                    copy.remove(entry.getKey());
                                } else {
                                    copy.set(entry.getKey(), withoutNulls(entry.getValue()));
                                }
                            });
            return copy;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode copy = node.deepCopy();
            for (int index = 0; index < node.size(); index++) {
                copy.set(index, withoutNulls(node.get(index)));
            }
            return copy;
        }
        return node;
    }
}
