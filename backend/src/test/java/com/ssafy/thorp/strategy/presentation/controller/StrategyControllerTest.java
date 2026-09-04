package com.ssafy.thorp.strategy.presentation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.strategy.application.result.StrategyDefinitionResult;
import com.ssafy.thorp.strategy.application.result.StrategySummaryResult;
import com.ssafy.thorp.strategy.application.service.StrategyCatalogService;
import com.ssafy.thorp.strategy.application.service.StrategyConfigValidationService;
import com.ssafy.thorp.strategy.domain.StrategyFixtures;
import com.ssafy.thorp.strategy.domain.exception.StrategyNotFoundException;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP 계약 확인. 서비스는 대역이며, 여기서 보는 것은 상태 코드와 응답 JSON의 모양이다. */
@WebMvcTest(StrategyController.class)
class StrategyControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private StrategyCatalogService catalogService;
    @MockitoBean private StrategyConfigValidationService validationService;

    @Test
    @DisplayName("GET /strategies → 200 + 전략 요약 목록")
    void listReturnsSummaries() throws Exception {
        when(catalogService.findAll(ExecutionScope.BACKTEST))
                .thenReturn(
                        List.of(
                                StrategySummaryResult.from(StrategyFixtures.smaCrossover()),
                                StrategySummaryResult.from(StrategyFixtures.macdSignal())));

        mockMvc.perform(get("/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].strategyId").value("SMA_CROSSOVER"))
                .andExpect(jsonPath("$.data[0].displayName").value("이동평균 교차"))
                .andExpect(jsonPath("$.data[0].parameterCount").value(4))
                .andExpect(jsonPath("$.data[0].executionScopes[0]").value("BACKTEST"))
                .andExpect(jsonPath("$.data[0].executionScopes[1]").value("LIVE"))
                .andExpect(jsonPath("$.data[1].strategyId").value("MACD_SIGNAL"))
                .andExpect(jsonPath("$.error").isEmpty());
    }

    @Test
    @DisplayName("GET /strategies?executionScope=LIVE → 그 목적의 목록을 조회한다")
    void listPassesExecutionScope() throws Exception {
        when(catalogService.findAll(ExecutionScope.LIVE)).thenReturn(List.of());

        mockMvc.perform(get("/strategies").param("executionScope", "LIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(catalogService).findAll(ExecutionScope.LIVE);
    }

    @Test
    @DisplayName("GET /strategies?executionScope=PAPER → 400, 닫힌 어휘 밖의 값은 받지 않는다")
    void listRejectsUnknownExecutionScope() throws Exception {
        mockMvc.perform(get("/strategies").param("executionScope", "PAPER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        verify(catalogService, never()).findAll(any());
    }

    @Test
    @DisplayName("GET /strategies/{id} → 200 + 정의 전체. 타입에 없는 범위 표현은 응답에서 빠진다")
    void getReturnsDefinition() throws Exception {
        when(catalogService.findById("SMA_CROSSOVER"))
                .thenReturn(StrategyDefinitionResult.from(StrategyFixtures.smaCrossover()));

        mockMvc.perform(get("/strategies/SMA_CROSSOVER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategyId").value("SMA_CROSSOVER"))
                .andExpect(jsonPath("$.data.definitionVersion").value(1))
                .andExpect(jsonPath("$.data.entryCondition").isNotEmpty())
                .andExpect(jsonPath("$.data.parameters.length()").value(4))
                // INTEGER 는 수치 범위를 갖고 허용 값 목록은 없다
                .andExpect(jsonPath("$.data.parameters[0].name").value("shortPeriod"))
                .andExpect(jsonPath("$.data.parameters[0].numericRange.min").value(2))
                .andExpect(jsonPath("$.data.parameters[0].numericRange.max").value(120))
                .andExpect(jsonPath("$.data.parameters[0].numericRange.step").value(1))
                .andExpect(jsonPath("$.data.parameters[0].enumValues").doesNotExist())
                // 필수 파라미터에는 기본값 필드 자체가 없다
                .andExpect(jsonPath("$.data.parameters[0].defaultValue").doesNotExist())
                // BOOLEAN 은 범위 표현이 둘 다 없고 기본값만 있다
                .andExpect(jsonPath("$.data.parameters[2].type").value("BOOLEAN"))
                .andExpect(jsonPath("$.data.parameters[2].defaultValue").value(false))
                .andExpect(jsonPath("$.data.parameters[2].numericRange").doesNotExist())
                .andExpect(jsonPath("$.data.parameters[2].enumValues").doesNotExist())
                // ENUM 은 허용 값 목록을 표시 이름과 함께 갖는다
                .andExpect(jsonPath("$.data.parameters[3].type").value("ENUM"))
                .andExpect(jsonPath("$.data.parameters[3].defaultValue").value("DAILY"))
                .andExpect(jsonPath("$.data.parameters[3].enumValues[0].value").value("DAILY"))
                .andExpect(
                        jsonPath("$.data.parameters[3].enumValues[0].displayName").value("매 거래일"))
                .andExpect(jsonPath("$.data.parameterConstraints[0].left").value("shortPeriod"))
                .andExpect(jsonPath("$.data.parameterConstraints[0].operator").value("LT"))
                .andExpect(
                        jsonPath("$.data.minimumHistoryRequirement.rule").value("MAX_PARAMETERS"))
                .andExpect(jsonPath("$.data.minimumHistoryRequirement.unit").value("bars"))
                .andExpect(jsonPath("$.data.minimumHistoryRequirement.value").doesNotExist())
                .andExpect(jsonPath("$.data.changeHistory[0].changedAt").value("2026-09-03"));
    }

    @Test
    @DisplayName("GET /strategies/{id} 없는 전략 → 404 + RESOURCE_NOT_FOUND")
    void getReturnsNotFound() throws Exception {
        when(catalogService.findById("NO_SUCH_STRATEGY"))
                .thenThrow(new StrategyNotFoundException("NO_SUCH_STRATEGY"));

        mockMvc.perform(get("/strategies/NO_SUCH_STRATEGY"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /strategies/config/validate 필수 필드 누락 → 400, 판정은 시도하지 않는다")
    void validateRejectsMalformedRequest() throws Exception {
        mockMvc.perform(
                        post("/strategies/config/validate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"parameters\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        verify(validationService, never()).validate(any());
    }
}
