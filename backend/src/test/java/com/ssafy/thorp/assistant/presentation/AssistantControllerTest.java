package com.ssafy.thorp.assistant.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.assistant.MutableClock;
import com.ssafy.thorp.assistant.application.port.AssistantModelPort;
import com.ssafy.thorp.assistant.application.port.EvidenceBuilder;
import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.Conversation;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceBundle;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.ModelDraft;
import com.ssafy.thorp.assistant.application.result.ModelDraft.AnswerPart;
import com.ssafy.thorp.assistant.application.result.ModelTurn;
import com.ssafy.thorp.assistant.application.result.PageContext;
import com.ssafy.thorp.assistant.application.result.PageKind;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.assistant.application.service.AnswerValidator;
import com.ssafy.thorp.assistant.application.service.AssistantCapabilities;
import com.ssafy.thorp.assistant.application.service.AssistantQuestionService;
import com.ssafy.thorp.assistant.application.service.AssistantRateLimiter;
import com.ssafy.thorp.assistant.application.service.ToolLoop;
import com.ssafy.thorp.assistant.infrastructure.config.AssistantConfig;
import com.ssafy.thorp.assistant.presentation.controller.AssistantController;
import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.result.application.service.PublicResultService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 계약 §1(T010). 한도는 작게 두어 확인한다: IP 2/분, 토큰 1/분, 하루 3. 테스트마다 시계를 이틀 돌려 창을 비운다.
 *
 * <p>응답 모양은 {@code docs/specs/assistant-api/examples/*.json}과 대조한다 — 그 파일이 화면과 IT 의 공통 기준이다.
 */
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
@TestPropertySource(
        properties = {
            "thorp.assistant.enabled-scopes=RESULT",
            "thorp.assistant.limits.per-ip-per-minute=2",
            "thorp.assistant.limits.per-token-per-minute=1",
            "thorp.assistant.limits.per-day=3"
        })
class AssistantControllerTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final Path EXAMPLES = Path.of("..", "docs", "specs", "assistant-api", "examples");

    /** 가짜 모델·근거 빌더·시계. 테스트가 {@link #script}로 모델 응답을 바꾼다. */
    @TestConfiguration
    static class Fakes {
        final AtomicReference<Supplier<ModelTurn>> script =
                new AtomicReference<>(Fakes::answeredDraft);
        final AtomicInteger modelCalls = new AtomicInteger();
        final MutableClock clock = new MutableClock(Instant.parse("2026-09-24T03:00:00Z"));

        static ModelTurn answeredDraft() {
            return new ModelDraft(
                    AnswerStatus.ANSWERED,
                    List.of(
                            new AnswerPart(
                                    "비용을 뺀 전체 수익률은 {{F1}}이고, 거래비용은 모두 {{F4}}입니다.",
                                    List.of("F1", "F4")),
                            new AnswerPart(
                                    "그중 수수료가 {{F5}}, 슬리피지가 {{F6}}입니다.", List.of("F5", "F6"))));
        }

        @Bean
        Clock clock() {
            return clock;
        }

        @Bean
        AssistantModelPort fakeModel() {
            return (Conversation conversation,
                    List<Evidence> facts,
                    List<ToolSpec> tools,
                    Duration t) -> {
                modelCalls.incrementAndGet();
                return script.get().get();
            };
        }

        @Bean
        EvidenceBuilder fakeBuilder() {
            return new EvidenceBuilder() {
                @Override
                public boolean supports(PageKind kind) {
                    return kind == PageKind.RESULT;
                }

                @Override
                public EvidenceBundle build(PageContext context) {
                    return new EvidenceBundle(
                            List.of(
                                    new Evidence(
                                            null,
                                            "비용 차감 후 전체 수익률",
                                            "0.166652",
                                            LocalDate.of(2024, 12, 31),
                                            "summary",
                                            EvidenceKind.RATIO),
                                    new Evidence(
                                            null,
                                            "전체 수익률",
                                            "0.18",
                                            null,
                                            "summary",
                                            EvidenceKind.RATIO),
                                    new Evidence(
                                            null,
                                            "거래 횟수",
                                            "24",
                                            null,
                                            "summary",
                                            EvidenceKind.INTEGER),
                                    new Evidence(
                                            null,
                                            "거래비용 합계",
                                            "17078",
                                            null,
                                            "settings",
                                            EvidenceKind.MONEY),
                                    new Evidence(
                                            null,
                                            "수수료",
                                            "12000",
                                            null,
                                            "settings",
                                            EvidenceKind.MONEY),
                                    new Evidence(
                                            null,
                                            "슬리피지",
                                            "5078",
                                            null,
                                            "settings",
                                            EvidenceKind.MONEY)),
                            List.of(),
                            null);
                }
            };
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Fakes fakes;
    @MockitoBean private PublicResultService publicResults;

    @BeforeEach
    void freshWindows() {
        fakes.clock.advance(Duration.ofDays(2));
        fakes.script.set(Fakes::answeredDraft);
        fakes.modelCalls.set(0);
    }

    static String body(String question, String context) {
        return "{\"question\":" + quote(question) + ",\"context\":" + context + "}";
    }

    static String resultContext(String token) {
        return "{\"kind\":\"RESULT\",\"publicToken\":\"" + token + "\"}";
    }

    static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
    }

    MockHttpServletRequestBuilder ask(String json, String ip) {
        return post("/assistant/questions")
                .with(csrf())
                .with(
                        request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    ResultActions askOk(String ip, String token) throws Exception {
        return mockMvc.perform(ask(body("거래비용은 어떻게 구성됐어?", resultContext(token)), ip));
    }

    static JsonNode example(String name) throws IOException {
        return new ObjectMapper().readTree(Files.readString(EXAMPLES.resolve(name)));
    }

    JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("capabilities 는 로그인 없이 200 이고 examples/capabilities-enabled.json 과 같다")
    void capabilitiesEnabled() throws Exception {
        ResultActions actions =
                mockMvc.perform(get("/assistant/capabilities")).andExpect(status().isOk());

        assertThat(json(actions)).isEqualTo(example("capabilities-enabled.json"));
    }

    @Test
    @DisplayName("완료 결과 질문은 200 이고 examples/answer-answered.json 과 같다 — 숫자는 모두 서버 치환값")
    void answersLikeExample() throws Exception {
        ResultActions actions = askOk("10.0.0.1", TOKEN).andExpect(status().isOk());

        assertThat(json(actions)).isEqualTo(example("answer-answered.json"));
        verify(publicResults).resolve(TOKEN);
    }

    @Test
    @DisplayName("모델 응답이 검증에 걸리면 examples/answer-insufficient.json 모양이다")
    void insufficientLikeExample() throws Exception {
        fakes.script.set(
                () ->
                        new ModelDraft(
                                AnswerStatus.ANSWERED,
                                List.of(new AnswerPart("수익률은 16.67%입니다.", List.of("F1")))));

        ResultActions actions = askOk("10.0.0.2", TOKEN).andExpect(status().isOk());

        assertThat(json(actions)).isEqualTo(example("answer-insufficient.json"));
    }

    @Test
    @DisplayName("범위 밖 답은 examples/answer-out-of-scope.json 모양이다")
    void outOfScopeLikeExample() throws Exception {
        fakes.script.set(() -> new ModelDraft(AnswerStatus.OUT_OF_SCOPE, List.of()));

        ResultActions actions = askOk("10.0.0.3", TOKEN).andExpect(status().isOk());

        assertThat(json(actions)).isEqualTo(example("answer-out-of-scope.json"));
    }

    @Test
    @DisplayName("301자·공백 질문은 400 이고 모델을 부르지 않는다")
    void rejectsQuestionLength() throws Exception {
        mockMvc.perform(ask(body("가".repeat(301), resultContext(TOKEN)), "10.0.0.4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(ask(body("   ", resultContext(TOKEN)), "10.0.0.4"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(ask(body("가".repeat(300), resultContext(TOKEN)), "10.0.0.4"))
                .andExpect(status().isOk());

        assertThat(fakes.modelCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("낯선 kind·RESULT 에 토큰 없음·식별자 둘 동시는 400 이다")
    void rejectsBadContext() throws Exception {
        mockMvc.perform(ask(body("q", "{\"kind\":\"LEARN\",\"articleId\":\"a\"}"), "10.0.0.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(ask(body("q", "{\"kind\":\"RESULT\"}"), "10.0.0.5"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        ask(
                                body(
                                        "q",
                                        "{\"kind\":\"RESULT\",\"publicToken\":\""
                                                + TOKEN
                                                + "\",\"articleId\":\"a\"}"),
                                "10.0.0.5"))
                .andExpect(status().isBadRequest());

        assertThat(fakes.modelCalls.get()).isZero();
        verify(publicResults, never()).resolve(any());
    }

    @Test
    @DisplayName("CSRF 가 없으면 403 이다")
    void rejectsMissingCsrf() throws Exception {
        mockMvc.perform(
                        post("/assistant/questions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("q", resultContext(TOKEN))))
                .andExpect(status().isForbidden());

        assertThat(fakes.modelCalls.get()).isZero();
    }

    @Test
    @DisplayName("토큰 판정(400/404/410)은 모델 호출 전에 끝난다")
    void tokenJudgementPrecedesModel() throws Exception {
        when(publicResults.resolve("bad"))
                .thenThrow(new BusinessException(ErrorCode.INVALID_INPUT));
        when(publicResults.resolve(TOKEN.replace('0', 'f')))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        when(publicResults.resolve(TOKEN.replace('0', 'e')))
                .thenThrow(new BusinessException(ErrorCode.RESULT_EXPIRED));

        askOk("10.0.0.6", "bad").andExpect(status().isBadRequest());
        askOk("10.0.0.7", TOKEN.replace('0', 'f')).andExpect(status().isNotFound());
        askOk("10.0.0.8", TOKEN.replace('0', 'e'))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("RESULT_EXPIRED"));

        assertThat(fakes.modelCalls.get()).isZero();
    }

    @Test
    @DisplayName("IP 분당 한도를 넘으면 429 ASSISTANT_LIMIT 과 retryAfterSeconds 다")
    void perIpLimit() throws Exception {
        askOk("10.0.1.1", TOKEN).andExpect(status().isOk());
        askOk("10.0.1.1", TOKEN.replace('0', '1')).andExpect(status().isOk());

        ResultActions actions =
                askOk("10.0.1.1", TOKEN.replace('0', '2')).andExpect(status().isTooManyRequests());

        JsonNode error = json(actions).get("error");
        JsonNode expected = example("error-limit.json").get("error");
        assertThat(error.get("code")).isEqualTo(expected.get("code"));
        assertThat(error.get("message")).isEqualTo(expected.get("message"));
        assertThat(error.get("details").get("retryAfterSeconds").asLong()).isBetween(1L, 60L);
        assertThat(error.get("details").get("reason").asText()).isEqualTo("PER_IP");
        assertThat(fakes.modelCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("결과 토큰별 분당 한도를 넘으면 429 다")
    void perTokenLimit() throws Exception {
        askOk("10.0.2.1", TOKEN).andExpect(status().isOk());

        askOk("10.0.2.2", TOKEN)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.details.reason").value("PER_TOKEN"));
    }

    @Test
    @DisplayName("하루 전체 상한을 넘으면 429 이고 다시 가능한 시점이 실린다")
    void perDayLimit() throws Exception {
        askOk("10.0.3.1", TOKEN.replace('0', '1')).andExpect(status().isOk());
        askOk("10.0.3.2", TOKEN.replace('0', '2')).andExpect(status().isOk());
        askOk("10.0.3.3", TOKEN.replace('0', '3')).andExpect(status().isOk());

        askOk("10.0.3.4", TOKEN.replace('0', '4'))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.details.reason").value("PER_DAY"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());
    }

    @Test
    @DisplayName("모델 장애·시간 초과는 503 이고 examples/error-unavailable.json 과 같다")
    void unavailableLikeExample() throws Exception {
        fakes.script.set(
                () -> {
                    throw new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE);
                });

        ResultActions actions = askOk("10.0.4.1", TOKEN).andExpect(status().isServiceUnavailable());

        assertThat(json(actions)).isEqualTo(example("error-unavailable.json"));
    }
}
