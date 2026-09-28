package com.ssafy.thorp.assistant.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.assistant.application.service.AnswerValidator;
import com.ssafy.thorp.assistant.application.service.AssistantCapabilities;
import com.ssafy.thorp.assistant.application.service.AssistantQuestionService;
import com.ssafy.thorp.assistant.application.service.AssistantRateLimiter;
import com.ssafy.thorp.assistant.application.service.ToolLoop;
import com.ssafy.thorp.assistant.infrastructure.config.AssistantConfig;
import com.ssafy.thorp.assistant.presentation.controller.AssistantController;
import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.result.application.service.PublicResultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 기능이 꺼진 환경(FR-016). 켜진 환경은 {@link AssistantControllerTest}가 본다 — 플래그가 컨텍스트 속성이라 클래스를 나눴다. */
@WebMvcTest(AssistantController.class)
@Import({
    SecurityConfig.class,
    AssistantConfig.class,
    AssistantQuestionService.class,
    AssistantCapabilities.class,
    AssistantRateLimiter.class,
    AnswerValidator.class,
    ToolLoop.class,
    AssistantControllerTest.Fakes.class
})
@TestPropertySource(properties = "thorp.assistant.enabled-scopes=")
class AssistantControllerDisabledTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AssistantControllerTest.Fakes fakes;
    @MockitoBean private PublicResultService publicResults;

    @Test
    @DisplayName("capabilities 는 200 이고 examples/capabilities-disabled.json 과 같다")
    void capabilitiesDisabled() throws Exception {
        String body =
                mockMvc.perform(get("/assistant/capabilities"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(objectMapper.readTree(body))
                .isEqualTo(AssistantControllerTest.example("capabilities-disabled.json"));
    }

    @Test
    @DisplayName("질문 경로는 404 이고 입력이 틀려도 400 이 아니라 404 다 — 경로를 노출하지 않는다")
    void questionsAreNotFound() throws Exception {
        mockMvc.perform(
                        post("/assistant/questions")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        AssistantControllerTest.body(
                                                "거래비용은?",
                                                AssistantControllerTest.resultContext(
                                                        AssistantControllerTest.TOKEN))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(
                        post("/assistant/questions")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        AssistantControllerTest.body(
                                                "가".repeat(301), "{\"kind\":\"LEARN\"}")))
                .andExpect(status().isNotFound());

        assertThat(fakes.modelCalls.get()).isZero();
        verify(publicResults, never()).resolve(any());
    }
}
