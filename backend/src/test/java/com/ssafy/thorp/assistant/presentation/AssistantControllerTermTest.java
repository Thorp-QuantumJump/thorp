package com.ssafy.thorp.assistant.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.ssafy.thorp.assistant.application.result.EvidenceSource;
import com.ssafy.thorp.assistant.application.result.EvidenceSourceType;
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
import com.ssafy.thorp.result.application.service.PublicResultService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * 진단 화면 종류(spec 017 계약 §1, T010). '투자 대상 고르기'만 켜고 '전략 선택'은 끈다 — 종류마다 따로 켜지는지 본다. 한도는 작게 둔다: IP 2/분,
 * 화면 종류별 IP 1/분, 토큰 1/분, 하루 10.
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
    AssistantControllerTermTest.Fakes.class
})
@TestPropertySource(
        properties = {
            "thorp.assistant.enabled-scopes=RESULT,SECTOR_SELECTION",
            "thorp.assistant.limits.per-ip-per-minute=2",
            "thorp.assistant.limits.per-token-per-minute=1",
            "thorp.assistant.limits.per-page-per-ip-per-minute=1",
            "thorp.assistant.limits.per-day=10"
        })
class AssistantControllerTermTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final Path EXAMPLES = Path.of("..", "docs", "specs", "assistant-api", "examples");

    @TestConfiguration
    static class Fakes {
        final AtomicReference<Supplier<ModelTurn>> script = new AtomicReference<>();
        final List<Conversation> conversations = new ArrayList<>();
        final MutableClock clock = new MutableClock(Instant.parse("2026-10-02T03:00:00Z"));
        final AtomicBoolean registryProvisional = new AtomicBoolean();
        final AtomicBoolean sectorSample = new AtomicBoolean();

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
                conversations.add(conversation);
                return script.get().get();
            };
        }

        /** 진단 두 종류의 근거: 게시 용어 하나, 등록표 설명 하나, 섹터 목록 하나. */
        @Bean
        EvidenceBuilder fakeTermBuilder() {
            return new EvidenceBuilder() {
                @Override
                public boolean supports(PageKind kind) {
                    return kind != PageKind.RESULT;
                }

                @Override
                public EvidenceBundle build(PageContext context) {
                    return new EvidenceBundle(
                            List.of(
                                    new Evidence(
                                            null,
                                            "용어: 리밸런싱",
                                            "시간이 지나 달라진 비중을 처음 정한 비중으로 되돌리는 일이다.",
                                            null,
                                            null,
                                            EvidenceKind.TEXT,
                                            new EvidenceSource(
                                                    EvidenceSourceType.TERM,
                                                    "weights-and-rebalancing",
                                                    "비중과 리밸런싱")),
                                    new Evidence(
                                            null,
                                            "설정 항목: 동일 비중 · 리밸런싱 주기",
                                            "같은 비중에서 벌어진 만큼을 얼마마다 되돌릴지 정한다.",
                                            null,
                                            null,
                                            EvidenceKind.TEXT,
                                            EvidenceSource.of(EvidenceSourceType.REGISTRY)),
                                    new Evidence(
                                            null,
                                            "섹터 목록",
                                            "반도체(산업, 대표 ETF KODEX 반도체, 기초 지수 KRX 반도체)",
                                            null,
                                            null,
                                            EvidenceKind.TEXT,
                                            EvidenceSource.of(EvidenceSourceType.SECTOR))),
                            List.of(),
                            null,
                            registryProvisional.get(),
                            sectorSample.get());
                }
            };
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Fakes fakes;
    @MockitoBean private PublicResultService publicResults;

    @BeforeEach
    void reset() {
        fakes.clock.advance(Duration.ofDays(2));
        fakes.script.set(() -> new ModelDraft(AnswerStatus.INSUFFICIENT_EVIDENCE, List.of()));
        fakes.conversations.clear();
        fakes.registryProvisional.set(false);
        fakes.sectorSample.set(false);
    }

    static ModelDraft answered(AnswerPart... parts) {
        return new ModelDraft(AnswerStatus.ANSWERED, List.of(parts));
    }

    MockHttpServletRequestBuilder ask(String context, String ip) {
        return post("/assistant/questions")
                .with(csrf())
                .with(
                        request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"리밸런싱이 뭐야?\",\"context\":" + context + "}");
    }

    static String kindContext(String kind) {
        return "{\"kind\":\"" + kind + "\"}";
    }

    JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    static JsonNode example(String name) throws IOException {
        return new ObjectMapper().readTree(Files.readString(EXAMPLES.resolve(name)));
    }

    @Test
    @DisplayName("capabilities.scopes 는 켜진 종류만 선언 순서로 보인다")
    void capabilitiesListsEnabledKinds() throws Exception {
        mockMvc.perform(get("/assistant/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopes[0]").value("RESULT"))
                .andExpect(jsonPath("$.data.scopes[1]").value("SECTOR_SELECTION"))
                .andExpect(jsonPath("$.data.scopes.length()").value(2));
    }

    @Test
    @DisplayName("근거가 없으면 examples/term-answer-insufficient.json 과 같고, 토큰 판정을 하지 않는다")
    void insufficientLikeExample() throws Exception {
        ResultActions actions =
                mockMvc.perform(ask(kindContext("SECTOR_SELECTION"), "10.0.0.1"))
                        .andExpect(status().isOk());

        assertThat(json(actions)).isEqualTo(example("term-answer-insufficient.json"));
        verifyNoInteractions(publicResults);
        assertThat(fakes.conversations.get(0).kind()).isEqualTo(PageKind.SECTOR_SELECTION);
    }

    @Test
    @DisplayName("범위 밖 답은 examples/term-answer-out-of-scope.json 과 같다")
    void outOfScopeLikeExample() throws Exception {
        fakes.script.set(() -> new ModelDraft(AnswerStatus.OUT_OF_SCOPE, List.of()));

        ResultActions actions =
                mockMvc.perform(ask(kindContext("SECTOR_SELECTION"), "10.0.0.2"))
                        .andExpect(status().isOk());

        assertThat(json(actions)).isEqualTo(example("term-answer-out-of-scope.json"));
    }

    @Test
    @DisplayName("진단 화면 요청에 publicToken 이나 다른 키가 있으면 400 이고 모델을 부르지 않는다")
    void rejectsIdentifiersAndExtraKeys() throws Exception {
        mockMvc.perform(
                        ask(
                                "{\"kind\":\"SECTOR_SELECTION\",\"publicToken\":\"" + TOKEN + "\"}",
                                "10.0.0.3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(
                        ask(
                                "{\"kind\":\"SECTOR_SELECTION\",\"selected\":[\"SEMICONDUCTOR\"]}",
                                "10.0.0.4"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.error.message")
                                .value(org.hamcrest.Matchers.containsString("kind 만")));

        assertThat(fakes.conversations).isEmpty();
    }

    @Test
    @DisplayName("꺼진 종류(STRATEGY_SETTINGS)는 질문 경로가 404 다")
    void disabledKindIsNotFound() throws Exception {
        mockMvc.perform(ask(kindContext("STRATEGY_SETTINGS"), "10.0.0.5"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("화면 종류별 IP 분당 한도를 넘기면 429 PER_PAGE 다")
    void perPageLimit() throws Exception {
        mockMvc.perform(ask(kindContext("SECTOR_SELECTION"), "10.0.0.6"))
                .andExpect(status().isOk());

        mockMvc.perform(ask(kindContext("SECTOR_SELECTION"), "10.0.0.6"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("ASSISTANT_LIMIT"))
                .andExpect(jsonPath("$.error.details.reason").value("PER_PAGE"));
    }

    @Test
    @DisplayName("IP 분당 한도는 결과 화면과 진단 화면이 함께 센다")
    void ipLimitIsShared() throws Exception {
        fakes.script.set(() -> new ModelDraft(AnswerStatus.GREETING, List.of()));
        mockMvc.perform(ask("{\"kind\":\"RESULT\",\"publicToken\":\"" + TOKEN + "\"}", "10.0.0.7"))
                .andExpect(status().isOk());
        mockMvc.perform(ask(kindContext("SECTOR_SELECTION"), "10.0.0.7"))
                .andExpect(status().isOk());

        mockMvc.perform(ask(kindContext("SECTOR_SELECTION"), "10.0.0.7"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.details.reason").value("PER_IP"));
    }

    @Test
    @DisplayName("ANSWERED 답은 근거 출처와 배우기 글 연결을 싣는다")
    void answeredCarriesSourceAndRelatedArticles() throws Exception {
        fakes.script.set(
                () ->
                        answered(
                                new AnswerPart("리밸런싱은 달라진 비중을 처음 비중으로 되돌리는 일이에요.", List.of("F1")),
                                new AnswerPart("리밸런싱 주기는 그 되돌리기를 얼마마다 할지 정해요.", List.of("F2"))));

        ResultActions actions =
                mockMvc.perform(ask(kindContext("SECTOR_SELECTION"), "10.0.0.8"))
                        .andExpect(status().isOk());

        JsonNode data = json(actions).path("data");
        assertThat(data.path("status").asText()).isEqualTo("ANSWERED");
        assertThat(data.path("evidence").get(0).path("source").path("type").asText())
                .isEqualTo("TERM");
        assertThat(data.path("evidence").get(0).path("tab").isNull()).isTrue();
        assertThat(data.path("evidence").get(1).path("source").path("articleId").isNull()).isTrue();
        assertThat(data.path("relatedArticles").size()).isEqualTo(1);
        assertThat(data.path("relatedArticles").get(0).path("articleId").asText())
                .isEqualTo("weights-and-rebalancing");
        assertThat(data.path("relatedArticles").get(0).path("title").asText())
                .isEqualTo("비중과 리밸런싱");
        assertThat(data.path("answer").asText()).startsWith("리밸런싱은");
    }

    @Test
    @DisplayName("임시 등록표·예시 대응표면 그 근거를 쓴 답 앞에 서버가 등록표 → 섹터 순서로 접두를 붙인다")
    void prefixesForProvisionalRegistryAndSampleSectors() throws Exception {
        fakes.registryProvisional.set(true);
        fakes.sectorSample.set(true);
        fakes.script.set(
                () ->
                        answered(
                                new AnswerPart("리밸런싱 주기는 되돌리는 간격이에요.", List.of("F2")),
                                new AnswerPart("반도체의 대표 ETF는 KODEX 반도체예요.", List.of("F3"))));

        String answer =
                json(mockMvc.perform(ask(kindContext("SECTOR_SELECTION"), "10.0.0.9"))
                                .andExpect(status().isOk()))
                        .path("data")
                        .path("answer")
                        .asText();

        assertThat(answer).startsWith("이 설정 설명은 아직 확정 전의 임시 내용입니다. 이 섹터 목록은 예시 대응표입니다. 리밸런싱 주기는");
    }

    @Test
    @DisplayName("접두는 그 출처의 근거를 실제로 쓴 답에만 붙는다")
    void noPrefixWhenSourceUnused() throws Exception {
        fakes.registryProvisional.set(true);
        fakes.sectorSample.set(true);
        fakes.script.set(() -> answered(new AnswerPart("리밸런싱은 비중을 되돌리는 일이에요.", List.of("F1"))));

        String answer =
                json(mockMvc.perform(ask(kindContext("SECTOR_SELECTION"), "10.0.0.10"))
                                .andExpect(status().isOk()))
                        .path("data")
                        .path("answer")
                        .asText();

        assertThat(answer).isEqualTo("리밸런싱은 비중을 되돌리는 일이에요.");
    }
}
