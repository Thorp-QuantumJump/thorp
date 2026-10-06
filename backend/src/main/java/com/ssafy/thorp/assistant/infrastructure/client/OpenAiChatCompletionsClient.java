package com.ssafy.thorp.assistant.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thorp.assistant.application.port.AssistantModelPort;
import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.Conversation;
import com.ssafy.thorp.assistant.application.result.Conversation.Exchange;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.ModelDraft;
import com.ssafy.thorp.assistant.application.result.ModelDraft.AnswerPart;
import com.ssafy.thorp.assistant.application.result.ModelTurn;
import com.ssafy.thorp.assistant.application.result.PageKind;
import com.ssafy.thorp.assistant.application.result.ToolArgument;
import com.ssafy.thorp.assistant.application.result.ToolCall;
import com.ssafy.thorp.assistant.application.result.ToolRequests;
import com.ssafy.thorp.assistant.application.result.ToolResult;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.assistant.infrastructure.config.AssistantModelProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chat Completions 형식의 {@link AssistantModelPort}(계약 §2.1~§2.3, ADR-0008 D2).
 *
 * <p>요청에는 질문·근거 팩·도구 목록·이전 라운드의 도구 요청과 결과만 들어간다. {@code store:false}라 이력은 매번 여기서 다시 담는다. 응답은 {@code
 * tool_calls}면 {@link ToolRequests}, JSON 본문이면 {@link ModelDraft}로 읽고, 그 밖(거부·빈 본문·스키마 불일치)은 503이다.
 *
 * <p>로그에는 지연·토큰 사용량·도구 요청 수만 남긴다. 질문·답·근거 원문은 남기지 않는다(FR-014).
 */
public class OpenAiChatCompletionsClient implements AssistantModelPort {
    static final String PROMPT_RESOURCE = "assistant/prompts/answer-ko.txt";
    static final String TERM_PROMPT_RESOURCE = "assistant/prompts/term-ko.txt";
    static final String RESPONSE_SCHEMA_NAME = "assistant_answer";
    static final String UNPARSED_ARGUMENTS_KEY = "_unparsed";
    private static final Logger log = LoggerFactory.getLogger(OpenAiChatCompletionsClient.class);

    private final AssistantHttpClient http;
    private final ObjectMapper mapper;
    private final String model;
    private final String prompt;
    private final String termPrompt;
    private final JsonNode responseFormat;

    public OpenAiChatCompletionsClient(
            AssistantHttpClient http, AssistantModelProperties properties, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
        this.model = properties.getModel().strip();
        this.prompt = loadResource(PROMPT_RESOURCE);
        this.termPrompt = loadResource(TERM_PROMPT_RESOURCE);
        this.responseFormat = responseFormat(mapper);
    }

    @Override
    public ModelTurn step(
            Conversation conversation,
            List<Evidence> facts,
            List<ToolSpec> tools,
            Duration timeout) {
        ObjectNode body = request(conversation, facts, tools);
        long started = System.nanoTime();
        JsonNode response = http.post(body, timeout);
        ModelTurn turn = parse(response);
        JsonNode usage = response.path("usage");
        log.info(
                "assistant model call: latencyMs={} promptTokens={} completionTokens={} toolCalls={}",
                (System.nanoTime() - started) / 1_000_000,
                usage.path("prompt_tokens").asInt(-1),
                usage.path("completion_tokens").asInt(-1),
                turn instanceof ToolRequests requests ? requests.calls().size() : 0);
        return turn;
    }

    // ---- 요청

    ObjectNode request(Conversation conversation, List<Evidence> facts, List<ToolSpec> tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("store", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "developer").put("content", promptFor(conversation));
        messages.addObject()
                .put("role", "user")
                .put(
                        "content",
                        userContent(conversation.question(), initialFacts(conversation, facts)));
        for (Exchange exchange : conversation.exchanges()) {
            ObjectNode assistant = messages.addObject().put("role", "assistant");
            assistant.putNull("content");
            ArrayNode calls = assistant.putArray("tool_calls");
            for (ToolCall call : exchange.calls()) {
                ObjectNode node = calls.addObject().put("id", call.id()).put("type", "function");
                node.putObject("function")
                        .put("name", call.name())
                        .put("arguments", argumentsJson(call.arguments()));
            }
            for (ToolResult result : exchange.results()) {
                messages.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", result.callId())
                        .put("content", toolContent(result));
            }
        }
        if (!tools.isEmpty()) {
            ArrayNode definitions = body.putArray("tools");
            for (ToolSpec spec : tools) {
                definitions.add(toolDefinition(spec));
            }
            body.put("tool_choice", "auto");
            body.put("parallel_tool_calls", false);
        }
        body.set("response_format", responseFormat);
        return body;
    }

    /** 결과 화면은 015 프롬프트, 진단 화면 두 종류는 용어 프롬프트(017 research R6). */
    private String promptFor(Conversation conversation) {
        return conversation.kind() == PageKind.RESULT ? prompt : termPrompt;
    }

    /** 이력의 도구 결과로 들어온 근거는 {@code tool} 메시지에 이미 있으므로 사용자 메시지에는 처음 팩만 싣는다. */
    private static List<Evidence> initialFacts(Conversation conversation, List<Evidence> facts) {
        Set<String> fromTools = new HashSet<>();
        for (Exchange exchange : conversation.exchanges()) {
            for (ToolResult result : exchange.results()) {
                for (Evidence fact : result.facts()) {
                    fromTools.add(fact.id());
                }
            }
        }
        return facts.stream().filter(fact -> !fromTools.contains(fact.id())).toList();
    }

    private static String userContent(String question, List<Evidence> facts) {
        StringBuilder text = new StringBuilder("질문:\n").append(question).append("\n\n근거:\n");
        if (facts.isEmpty()) {
            text.append("(없음)\n");
        }
        for (Evidence fact : facts) {
            text.append(factLine(fact)).append('\n');
        }
        return text.toString();
    }

    /** {@code Fn label (asOf) = value}. 값은 원문 문자열이다 — 모델이 표기를 바꾸지 않게 한다(계약 §2.1). */
    static String factLine(Evidence fact) {
        StringBuilder line = new StringBuilder(fact.id()).append(' ').append(fact.label());
        if (fact.asOf() != null) {
            line.append(" (").append(fact.asOf()).append(')');
        }
        return line.append(" = ").append(fact.value()).toString();
    }

    private static String toolContent(ToolResult result) {
        if (result.unavailable()) {
            return result.unavailableReason();
        }
        if (result.facts().isEmpty()) {
            return "읽은 자료가 없습니다";
        }
        StringBuilder text = new StringBuilder();
        for (Evidence fact : result.facts()) {
            text.append(factLine(fact)).append('\n');
        }
        return text.toString();
    }

    private String argumentsJson(Map<String, String> arguments) {
        ObjectNode node = mapper.createObjectNode();
        if (arguments != null) {
            arguments.forEach(node::put);
        }
        return node.toString();
    }

    private ObjectNode toolDefinition(ToolSpec spec) {
        ObjectNode definition = mapper.createObjectNode().put("type", "function");
        ObjectNode function = definition.putObject("function");
        function.put("name", spec.name())
                .put("description", spec.description())
                .put("strict", true);
        ObjectNode parameters = function.putObject("parameters").put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");
        for (ToolArgument argument : spec.arguments()) {
            ObjectNode property = properties.putObject(argument.name()).put("type", "string");
            switch (argument.type()) {
                case DATE -> property.put("description", "YYYY-MM-DD");
                case ENUM -> {
                    ArrayNode values = property.putArray("enum");
                    argument.allowedValues().forEach(values::add);
                }
            }
            required.add(argument.name());
        }
        parameters.put("additionalProperties", false);
        return definition;
    }

    // ---- 응답

    ModelTurn parse(JsonNode response) {
        JsonNode message = response.path("choices").path(0).path("message");
        if (message.isMissingNode()) {
            throw AssistantHttpClient.unavailable("response has no message");
        }
        JsonNode refusal = message.path("refusal");
        if (refusal.isTextual() && !refusal.asText().isBlank()) {
            throw AssistantHttpClient.unavailable("model refused");
        }
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode node : toolCalls) {
                JsonNode function = node.path("function");
                calls.add(
                        new ToolCall(
                                node.path("id").asText(""),
                                function.path("name").asText(""),
                                arguments(function.path("arguments").asText(""))));
            }
            return new ToolRequests(calls);
        }
        String content = message.path("content").asText("");
        if (content.isBlank()) {
            throw AssistantHttpClient.unavailable("empty content");
        }
        return draft(content);
    }

    /** JSON 객체가 아니면 빈 인자가 아니라 검증에 걸리는 값으로 넘긴다 — 루프가 "인자가 맞지 않음"으로 거절한다. */
    private Map<String, String> arguments(String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            if (node == null || !node.isObject()) {
                return Map.of(UNPARSED_ARGUMENTS_KEY, raw);
            }
            Map<String, String> arguments = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode value = entry.getValue();
                arguments.put(
                        entry.getKey(), value.isTextual() ? value.asText() : value.toString());
            }
            return arguments;
        } catch (IOException | IllegalArgumentException exc) {
            return Map.of(UNPARSED_ARGUMENTS_KEY, raw);
        }
    }

    private ModelDraft draft(String content) {
        JsonNode node;
        try {
            node = mapper.readTree(content);
        } catch (IOException | IllegalArgumentException exc) {
            throw AssistantHttpClient.unavailable("content is not json");
        }
        JsonNode statusNode = node.path("status");
        JsonNode partsNode = node.path("answerParts");
        if (!statusNode.isTextual() || !partsNode.isArray()) {
            throw AssistantHttpClient.unavailable("content does not match schema");
        }
        AnswerStatus status;
        try {
            status = AnswerStatus.valueOf(statusNode.asText());
        } catch (IllegalArgumentException exc) {
            throw AssistantHttpClient.unavailable("unknown status");
        }
        List<AnswerPart> parts = new ArrayList<>();
        for (JsonNode part : partsNode) {
            JsonNode text = part.path("text");
            JsonNode factIds = part.path("factIds");
            if (!text.isTextual() || !factIds.isArray()) {
                throw AssistantHttpClient.unavailable("part does not match schema");
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode id : factIds) {
                if (!id.isTextual()) {
                    throw AssistantHttpClient.unavailable("factIds must be strings");
                }
                ids.add(id.asText());
            }
            parts.add(new AnswerPart(text.asText(), ids));
        }
        return new ModelDraft(status, parts);
    }

    // ---- 고정 자원

    private static JsonNode responseFormat(ObjectMapper mapper) {
        ObjectNode part = mapper.createObjectNode().put("type", "object");
        ObjectNode partProperties = part.putObject("properties");
        partProperties.putObject("text").put("type", "string");
        partProperties
                .putObject("factIds")
                .put("type", "array")
                .putObject("items")
                .put("type", "string");
        part.putArray("required").add("text").add("factIds");
        part.put("additionalProperties", false);

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode statuses = properties.putObject("status").put("type", "string").putArray("enum");
        for (AnswerStatus status : AnswerStatus.values()) {
            statuses.add(status.name());
        }
        properties.putObject("answerParts").put("type", "array").set("items", part);
        schema.putArray("required").add("status").add("answerParts");
        schema.put("additionalProperties", false);

        ObjectNode format = mapper.createObjectNode().put("type", "json_schema");
        format.putObject("json_schema")
                .put("name", RESPONSE_SCHEMA_NAME)
                .put("strict", true)
                .set("schema", schema);
        return format;
    }

    static String loadResource(String path) {
        try (InputStream in =
                OpenAiChatCompletionsClient.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("리소스가 없습니다: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }
}
