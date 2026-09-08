package com.ssafy.thorp.market.presentation.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ssafy.thorp.market.application.result.MarketResult;
import com.ssafy.thorp.market.application.service.MarketService;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketControllerTest {
    private final MarketService service = mock(MarketService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc =
                MockMvcBuilders.standaloneSetup(new MarketController(service))
                        .setControllerAdvice(
                                new MarketExceptionHandler(),
                                new com.ssafy.thorp.common.web.advice.GlobalExceptionHandler())
                        .build();
    }

    @ParameterizedTest
    @EnumSource(MarketCode.class)
    void allElevenRoutesReturnOneObject(MarketCode code) throws Exception {
        when(service.get(code))
                .thenReturn(
                        new MarketResult(
                                code,
                                new MarketObservation(
                                        BigDecimal.ONE,
                                        "지수",
                                        code.frequency().equals("monthly")
                                                ? "2026-08"
                                                : "2026-09-04"),
                                Instant.parse("2026-09-07T00:00:00Z"),
                                false));
        mvc.perform(get("/markets/" + code.name().toLowerCase().replace('_', '-')))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.httpStatus").value("OK"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.result.code").value(code.name()))
                .andExpect(jsonPath("$.result.status").value("ok"))
                .andExpect(jsonPath("$.result.markets").doesNotExist());
        verify(service).get(code);
    }

    @Test
    void upstreamAuthFailureIs503AndNotCaller401() throws Exception {
        when(service.get(any()))
                .thenThrow(new MarketDataException(MarketDataException.Reason.AUTHENTICATION));
        mvc.perform(get("/markets/kospi"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test
    void methodErrorUsesExistingCommonEnvelope() throws Exception {
        mvc.perform(post("/markets/kospi"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void unexpectedExceptionDoesNotExposeSecret() throws Exception {
        when(service.get(any())).thenThrow(new RuntimeException("secret-key-in-url"));
        mvc.perform(get("/markets/kospi"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }
}
