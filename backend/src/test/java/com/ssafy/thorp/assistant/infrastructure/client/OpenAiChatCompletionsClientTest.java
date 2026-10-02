package com.ssafy.thorp.assistant.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.Conversation;
import com.ssafy.thorp.assistant.application.result.Conversation.Exchange;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.ModelDraft;
import com.ssafy.thorp.assistant.application.result.ModelTurn;
import com.ssafy.thorp.assistant.application.result.PageKind;
import com.ssafy.thorp.assistant.application.result.ToolArgument;
import com.ssafy.thorp.assistant.application.result.ToolCall;
import com.ssafy.thorp.assistant.application.result.ToolRequests;
import com.ssafy.thorp.assistant.application.result.ToolResult;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.assistant.infrastructure.config.AssistantModelProperties;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 계약 §2.1~§2.3(T020). 가짜 HTTP 서버가 받은 본문과 돌려준 응답으로 요청 조립·이력·파싱·오류 매핑을 고정한다. */
@ExtendWith(OutputCaptureExtension.class)
class OpenAiChatCompletionsClientTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final String TOOL_CALLS_RESPONSE =
            """
            {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
              "tool_calls":[{"id":"call_1","type":"function","function":{"name":"getEquityOn","arguments":"{\\"date\\":\\"2024-03-04\\"}"}}]}}],
             "usage":{"prompt_tokens":289,"completion_tokens":286,"total_tokens":575}}
            """;
    static final String DRAFT_RESPONSE =
            """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
              "content":"{\\"status\\":\\"ANSWERED\\",\\"answerParts\\":[{\\"text\\":\\"비용을 뺀 전체 수익률은 {{F1}}입니다.\\",\\"factIds\\":[\\"F1\\"]}]}"}}],
             "usage":{"prompt_tokens":300,"completion_tokens":40,"total_tokens":340}}
            """;

    record Scripted(int status, String body, Duration delay) {
        static Scripted ok(String body) {
            return new Scripted(200, body, Duration.ZERO);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> received = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private final Deque<Scripted> responses = new ArrayDeque<>();
    private HttpServer server;
    private OpenAiChatCompletionsClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    received.add(mapper.readTree(exchange.getRequestBody()));
                    authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    Scripted scripted = responses.poll();
                    if (scripted == null) {
                        scripted = new Scripted(500, "{}", Duration.ZERO);
                    }
                    try {
                        Thread.sleep(scripted.delay().toMillis());
                    } catch (InterruptedException exc) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] bytes = scripted.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(scripted.status(), bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        AssistantModelProperties properties = new AssistantModelProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        properties.setModel("gpt-5-mini");
        client =
                new OpenAiChatCompletionsClient(
                        new AssistantHttpClient(properties, mapper), properties, mapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    static Evidence fact(String id, String label, String value, LocalDate asOf) {
        return new Evidence(id, label, value, asOf, "summary", EvidenceKind.RATIO);
    }

    static final List<Evidence> FACTS =
            List.of(
                    fact("F1", "비용 차감 후 전체 수익률", "0.166652", LocalDate.of(2024, 12, 31)),
                    fact("F2", "거래비용 합계", "17078", null));
    static final List<ToolSpec> TOOLS =
            List.of(
                    ToolSpec.dates("getEquityOn", "특정 날짜의 시계열 점", List.of("date")),
                    ToolSpec.dates("getEquityRange", "구간의 첫·끝·최고·최저 점", List.of("from", "to")));

    ModelTurn step(Conversation conversation, List<Evidence> facts, List<ToolSpec> tools) {
        return client.step(conversation, facts, tools, Duration.ofSeconds(5));
    }

    static Conversation question(String question) {
        return new Conversation(question, List.of());
    }

    @Test
    @DisplayName(
            "요청은 model·store:false·parallel_tool_calls:false·strict 응답 스키마·서버 도구만 담고, 질문과 근거 팩을 developer/user 로 나눈다")
    void requestShape() {
        responses.add(Scripted.ok(TOOL_CALLS_RESPONSE));

        step(question("거래비용은 어떻게 구성됐어?"), FACTS, TOOLS);

        JsonNode body = received.get(0);
        assertThat(body.get("model").asText()).isEqualTo("gpt-5-mini");
        assertThat(body.get("store").asBoolean()).isFalse();
        assertThat(body.get("parallel_tool_calls").asBoolean()).isFalse();
        assertThat(body.get("tool_choice").asText()).isEqualTo("auto");
        JsonNode format = body.get("response_format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        assertThat(format.get("json_schema").get("strict").asBoolean()).isTrue();
        assertThat(format.get("json_schema").get("name").asText()).isEqualTo("assistant_answer");
        JsonNode schema = format.get("json_schema").get("schema");
        assertThat(schema.get("properties").get("status").get("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("ANSWERED", "INSUFFICIENT_EVIDENCE", "OUT_OF_SCOPE", "GREETING");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();

        JsonNode tools = body.get("tools");
        assertThat(tools).hasSize(2);
        for (JsonNode tool : tools) {
            assertThat(tool.get("type").asText()).isEqualTo("function");
            assertThat(tool.get("function").get("strict").asBoolean()).isTrue();
            assertThat(
                            tool.get("function")
                                    .get("parameters")
                                    .get("additionalProperties")
                                    .asBoolean())
                    .isFalse();
        }
        assertThat(tools.get(1).get("function").get("parameters").get("required"))
                .extracting(JsonNode::asText)
                .containsExactly("from", "to");

        JsonNode messages = body.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("developer");
        assertThat(messages.get(0).get("content").asText()).contains("자리표시자");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("content").asText())
                .contains("질문:\n거래비용은 어떻게 구성됐어?")
                .contains("F1 비용 차감 후 전체 수익률 (2024-12-31) = 0.166652")
                .contains("F2 거래비용 합계 = 17078");
        assertThat(authorizations.get(0)).isEqualTo("Bearer test-key");
    }

    @Test
    @DisplayName("직렬화된 요청 어디에도 공개 토큰·backtestId 가 없다 — 입력에 없으니 나갈 수 없다")
    void requestCarriesNoIdentifiers() {
        responses.add(Scripted.ok(DRAFT_RESPONSE));

        step(question("수익률?"), FACTS, TOOLS);

        assertThat(received.get(0).toString())
                .doesNotContain(TOKEN, "BT-", "backtestId", "publicToken");
    }

    @Test
    @DisplayName("성공·실패 호출의 로그에 키·공개 토큰·질문 원문이 남지 않는다")
    void logsCarryNoSecretsOrText(CapturedOutput output) {
        responses.add(Scripted.ok(TOOL_CALLS_RESPONSE));
        responses.add(
                new Scripted(401, "{\"error\":{\"message\":\"bad key test-key\"}}", Duration.ZERO));
        String question = "토큰 " + TOKEN + " 결과의 거래비용은?";

        step(question(question), FACTS, TOOLS);
        assertThatThrownBy(() -> step(question(question), FACTS, TOOLS))
                .isInstanceOf(BusinessException.class);

        assertThat(output.getAll())
                .contains("assistant model call")
                .contains("assistant model unavailable")
                .doesNotContain("test-key", TOKEN, "거래비용은?", "0.166652");
    }

    @Test
    @DisplayName("tool_calls 는 ToolCall(id·name·arguments) 로 읽는다")
    void parsesToolCalls() {
        responses.add(Scripted.ok(TOOL_CALLS_RESPONSE));

        ModelTurn turn = step(question("그날 자산은?"), FACTS, TOOLS);

        assertThat(turn)
                .isInstanceOfSatisfying(
                        ToolRequests.class,
                        requests ->
                                assertThat(requests.calls())
                                        .containsExactly(
                                                new ToolCall(
                                                        "call_1",
                                                        "getEquityOn",
                                                        Map.of("date", "2024-03-04"))));
    }

    @Test
    @DisplayName("arguments 가 JSON 객체가 아니면 빈 인자가 아니라 검증에 걸리는 값으로 넘긴다")
    void nonJsonArgumentsFailValidation() {
        responses.add(
                Scripted.ok(
                        TOOL_CALLS_RESPONSE.replace("{\\\"date\\\":\\\"2024-03-04\\\"}", "어제 자산")));

        ToolRequests requests = (ToolRequests) step(question("q"), FACTS, TOOLS);

        Map<String, String> arguments = requests.calls().get(0).arguments();
        assertThat(arguments).doesNotContainKey("date").isNotEmpty();
        assertThat(arguments).containsKey(OpenAiChatCompletionsClient.UNPARSED_ARGUMENTS_KEY);
    }

    @Test
    @DisplayName("arguments 가 빈 문자열이어도 500 이 아니라 검증에 걸리는 값으로 넘긴다")
    void emptyArgumentsFailValidation() {
        responses.add(
                Scripted.ok(TOOL_CALLS_RESPONSE.replace("{\\\"date\\\":\\\"2024-03-04\\\"}", "")));

        ToolRequests requests = (ToolRequests) step(question("q"), FACTS, TOOLS);

        assertThat(requests.calls().get(0).arguments())
                .containsKey(OpenAiChatCompletionsClient.UNPARSED_ARGUMENTS_KEY);
    }

    @Test
    @DisplayName(
            "두 번째 라운드 요청에는 이전 assistant(tool_calls)·tool 메시지가 들어 있고, 도구로 얻은 근거는 사용자 메시지에 중복되지 않는다")
    void secondRoundCarriesHistory() {
        responses.add(Scripted.ok(DRAFT_RESPONSE));
        Evidence fromTool = fact("F3", "자산가치", "10500000", LocalDate.of(2024, 3, 4));
        Exchange exchange =
                new Exchange(
                        List.of(
                                new ToolCall("call_1", "getEquityOn", Map.of("date", "2024-03-04")),
                                new ToolCall("call_2", "deleteEverything", Map.of())),
                        List.of(
                                new ToolResult("call_1", List.of(fromTool), null),
                                ToolResult.unavailable("call_2", "사용할 수 없음: 그런 도구가 없습니다")));
        List<Evidence> facts = List.of(FACTS.get(0), FACTS.get(1), fromTool);

        step(new Conversation("그날 자산은?", List.of(exchange)), facts, TOOLS);

        JsonNode messages = received.get(0).get("messages");
        assertThat(messages)
                .extracting(m -> m.get("role").asText())
                .containsExactly("developer", "user", "assistant", "tool", "tool");
        assertThat(messages.get(1).get("content").asText())
                .contains("F1 ", "F2 ")
                .doesNotContain("F3 ");
        JsonNode assistant = messages.get(2);
        assertThat(assistant.get("content").isNull()).isTrue();
        assertThat(assistant.get("tool_calls")).hasSize(2);
        JsonNode first = assistant.get("tool_calls").get(0);
        assertThat(first.get("id").asText()).isEqualTo("call_1");
        assertThat(first.get("type").asText()).isEqualTo("function");
        assertThat(first.get("function").get("name").asText()).isEqualTo("getEquityOn");
        assertThat(first.get("function").get("arguments").asText())
                .isEqualTo("{\"date\":\"2024-03-04\"}");
        assertThat(messages.get(3).get("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(messages.get(3).get("content").asText())
                .contains("F3 자산가치 (2024-03-04) = 10500000");
        assertThat(messages.get(4).get("tool_call_id").asText()).isEqualTo("call_2");
        assertThat(messages.get(4).get("content").asText()).isEqualTo("사용할 수 없음: 그런 도구가 없습니다");
    }

    @Test
    @DisplayName("최종 본문은 ModelDraft(status·answerParts) 로 읽는다")
    void parsesDraft() {
        responses.add(Scripted.ok(DRAFT_RESPONSE));

        ModelTurn turn = step(question("수익률?"), FACTS, TOOLS);

        assertThat(turn)
                .isInstanceOfSatisfying(
                        ModelDraft.class,
                        draft -> {
                            assertThat(draft.status()).isEqualTo(AnswerStatus.ANSWERED);
                            assertThat(draft.parts()).hasSize(1);
                            assertThat(draft.parts().get(0).text())
                                    .isEqualTo("비용을 뺀 전체 수익률은 {{F1}}입니다.");
                            assertThat(draft.parts().get(0).factIds()).containsExactly("F1");
                        });
    }

    @Test
    @DisplayName("도구가 없으면 tools·tool_choice·parallel_tool_calls 를 보내지 않는다")
    void noToolsOmitsToolFields() {
        responses.add(Scripted.ok(DRAFT_RESPONSE));

        step(question("수익률?"), FACTS, List.of());

        JsonNode body = received.get(0);
        assertThat(body.has("tools")).isFalse();
        assertThat(body.has("tool_choice")).isFalse();
        assertThat(body.has("parallel_tool_calls")).isFalse();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
            strings = {
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"refusal\":\"답할 수 없습니다\"}}]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"그냥 문장\"}}]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"status\\\":\\\"MAYBE\\\",\\\"answerParts\\\":[]}\"}}]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"status\\\":\\\"ANSWERED\\\"}\"}}]}",
                "{\"choices\":[]}",
                "not json at all",
                "",
                "null",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"status\\\":\\\"OUT_OF_SCOPE\\\",\\\"answerParts\\\":[{\\\"text\\\":\\\"x\\\",\\\"factIds\\\":[1]}]}\"}}]}"
            })
    @DisplayName("거부·빈 본문·스키마 불일치·JSON 아님은 모두 503 ASSISTANT_UNAVAILABLE 이고 메시지는 고정 문구다")
    void unusableResponsesAreUnavailable(String body) {
        responses.add(Scripted.ok(body));

        assertUnavailable(() -> step(question("q"), FACTS, TOOLS));
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {401, 429, 500})
    @DisplayName("2xx 가 아니면 503 이다")
    void httpErrorsAreUnavailable(int status) {
        responses.add(new Scripted(status, "{\"error\":{\"message\":\"nope\"}}", Duration.ZERO));

        assertUnavailable(() -> step(question("q"), FACTS, TOOLS));
    }

    @Test
    @DisplayName("라운드 예산을 넘기면 503 이다")
    void timeoutIsUnavailable() {
        responses.add(new Scripted(200, DRAFT_RESPONSE, Duration.ofSeconds(2)));

        assertUnavailable(() -> client.step(question("q"), FACTS, TOOLS, Duration.ofMillis(300)));
    }

    @Test
    @DisplayName("남은 예산이 0 이면 호출하지 않고 503 이다")
    void zeroBudgetSkipsCall() {
        assertUnavailable(() -> client.step(question("q"), FACTS, TOOLS, Duration.ZERO));

        assertThat(received).isEmpty();
    }

    private static void assertUnavailable(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exc -> {
                            assertThat(exc.getErrorCode())
                                    .isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
                            assertThat(exc.getMessage())
                                    .isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE.getMessage());
                        });
    }

    @Test
    @DisplayName("결과 화면은 015 프롬프트, 진단 화면은 용어 프롬프트를 developer 메시지로 보낸다(017 research R6)")
    void promptByKind() {
        responses.add(Scripted.ok(DRAFT_RESPONSE));
        responses.add(Scripted.ok(DRAFT_RESPONSE));

        step(question("수익률은?"), FACTS, TOOLS);
        step(new Conversation(PageKind.STRATEGY_SETTINGS, "리밸런싱이 뭐야?", List.of()), FACTS, TOOLS);

        assertThat(received.get(0).path("messages").get(0).path("content").asText())
                .isEqualTo(
                        OpenAiChatCompletionsClient.loadResource(
                                OpenAiChatCompletionsClient.PROMPT_RESOURCE));
        assertThat(received.get(1).path("messages").get(0).path("content").asText())
                .isEqualTo(
                        OpenAiChatCompletionsClient.loadResource(
                                OpenAiChatCompletionsClient.TERM_PROMPT_RESOURCE));
    }

    @Test
    @DisplayName("열거 인자는 strict enum 으로, 인자 없는 도구는 빈 properties·required 로 정의한다")
    void enumAndNoArgumentToolDefinitions() {
        responses.add(Scripted.ok(DRAFT_RESPONSE));
        List<ToolSpec> tools =
                List.of(
                        new ToolSpec(
                                "readArticle",
                                "게시 글 본문. weights-and-rebalancing=비중과 리밸런싱",
                                List.of(
                                        ToolArgument.oneOf(
                                                "articleId",
                                                List.of("weights-and-rebalancing", "backtest")))),
                        new ToolSpec("readSectorList", "섹터 목록", List.of()));

        step(new Conversation(PageKind.SECTOR_SELECTION, "대표 ETF가 뭐야?", List.of()), FACTS, tools);

        JsonNode definitions = received.get(0).path("tools");
        JsonNode article = definitions.get(0).path("function");
        assertThat(article.path("strict").asBoolean()).isTrue();
        assertThat(article.path("parameters").path("properties").path("articleId").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("weights-and-rebalancing", "backtest");
        assertThat(article.path("parameters").path("required").get(0).asText())
                .isEqualTo("articleId");
        assertThat(article.path("parameters").path("additionalProperties").asBoolean()).isFalse();
        JsonNode sectorList = definitions.get(1).path("function").path("parameters");
        assertThat(sectorList.path("properties").size()).isZero();
        assertThat(sectorList.path("required").size()).isZero();
    }
}
