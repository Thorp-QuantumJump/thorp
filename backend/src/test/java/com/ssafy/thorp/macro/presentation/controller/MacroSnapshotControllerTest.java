package com.ssafy.thorp.macro.presentation.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.macro.application.exception.MacroRegistryNotPublishedException;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult;
import com.ssafy.thorp.macro.application.service.MacroSnapshotQueryService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 경계만 본다 — 계약이 정한 <b>응답 봉투</b>가 실제로 그 모양으로 나가는지다 (tasks.md T032·T034).
 *
 * <p><b>서비스를 직접 부르는 테스트로는 이 자리가 보이지 않는다.</b> 봉투는 컨트롤러와 예외 처리 순서가 정하고, 그 순서는 MVC 를 지나야 드러난다. 실제로
 * {@code @Order} 없이 올렸을 때 {@code GlobalExceptionHandler} 가 먼저 잡아 구형 {@code ApiResponse}({@code
 * success/data/error})가 나갔는데, 계약 테스트는 초록이었고 애플리케이션을 띄워 부른 뒤에야 드러났다.
 */
@WebMvcTest(MacroSnapshotController.class)
@Import(SecurityConfig.class)
@DisplayName("거시지표 스냅샷 HTTP 경계")
class MacroSnapshotControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MacroSnapshotQueryService queryService;

    @Test
    @DisplayName("성공 응답은 HttpApiResponse 봉투로 나간다")
    void wrapsSuccessInHttpApiResponse() throws Exception {
        when(queryService.getSnapshot()).thenReturn(emptySnapshot());

        mockMvc.perform(get("/macro-indicators/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpStatus").value("OK"))
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.result.calculationRegistryVersion").value("REG-1"))
                .andExpect(
                        jsonPath("$.result.historicalComparisonNotice")
                                .value("최신 개정자료를 이용한 역사 비교입니다. 당시 발표값을 재현한 값이 아닙니다."))
                // 자료가 없어도 200 이다. 없음을 말하는 것도 정상적인 조회 상태다(원칙 VI).
                .andExpect(jsonPath("$.result.dataVersion").doesNotExist())
                .andExpect(jsonPath("$.result.overallReferenceMonth").doesNotExist());
    }

    @Test
    @DisplayName("등록표 미발행 500도 계약의 봉투로 나간다 — 구형 success/data/error 가 아니다")
    void wrapsFailureInContractEnvelope() throws Exception {
        when(queryService.getSnapshot()).thenThrow(new MacroRegistryNotPublishedException());

        mockMvc.perform(get("/macro-indicators/snapshot"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.httpStatus").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.success").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("인증 없이 열린다 — 사용자에게 원천 키를 요구하지 않는다")
    void requiresNoAuthentication() throws Exception {
        when(queryService.getSnapshot()).thenReturn(emptySnapshot());

        mockMvc.perform(get("/macro-indicators/snapshot")).andExpect(status().isOk());
    }

    private MacroSnapshotResult emptySnapshot() {
        return new MacroSnapshotResult(
                null,
                "REG-1",
                "DSP-1",
                false,
                null,
                new MacroSnapshotResult.CollectionState(
                        "NEVER_COLLECTED", List.of(), null, null, List.of(), false),
                List.of(),
                List.of());
    }
}
