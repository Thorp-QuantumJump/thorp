package com.ssafy.thorp.assistant.application.service;

import com.ssafy.thorp.assistant.application.exception.ToolUnavailableException;
import com.ssafy.thorp.assistant.application.port.AssistantModelPort;
import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.assistant.application.port.AssistantTool;
import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.Conversation;
import com.ssafy.thorp.assistant.application.result.Conversation.Exchange;
import com.ssafy.thorp.assistant.application.result.Deadline;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceBundle;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.ModelDraft;
import com.ssafy.thorp.assistant.application.result.ModelTurn;
import com.ssafy.thorp.assistant.application.result.ToolCall;
import com.ssafy.thorp.assistant.application.result.ToolRequests;
import com.ssafy.thorp.assistant.application.result.ToolResult;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 모델 호출 ↔ 도구 실행 루프(FR-007). 모델은 도구를 <b>요청</b>만 하고, 실행은 여기서 이름·인자를 검증한 뒤 한다.
 *
 * <p>라운드는 요청당 최대 {@code maxRounds}(2)다. 그 뒤 또 도구를 요청하면 끊고 근거 부족으로 답한다(spec Edge Cases). 근거는 라운드당
 * {@code maxFactsPerRound}(30), 팩 전체 {@code maxFacts}(60)이고, 넘으면 도구 결과의 뒤를 잘라 "일부만 읽음" 근거를 남기며 초기
 * 팩은 보존한다. 전체 데드라인(15초)이 라운드 예산보다 우선이다.
 */
@Component
public class ToolLoop {
    static final String UNAVAILABLE_PREFIX = "사용할 수 없음: ";
    static final String TRUNCATED_LABEL = "읽은 범위";
    static final String TRUNCATED_VALUE = "일부만 읽음";

    private final AssistantSettings settings;
    private final Clock clock;

    public ToolLoop(AssistantSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * @param draft 모델의 최종 초안. 라운드 상한에서 끊겼으면 근거 부족 초안이다
     * @param facts id가 매겨진 전체 팩(초기 + 도구 결과). 검증기가 이 안에서만 {@code factIds}를 찾는다
     */
    public record Outcome(ModelDraft draft, List<Evidence> facts, int rounds) {}

    public Outcome run(AssistantModelPort model, String question, EvidenceBundle bundle) {
        Deadline deadline = new Deadline(clock, settings.totalBudget());
        FactPack pack = new FactPack(settings.maxFacts());
        pack.add(bundle.facts(), settings.maxFacts());
        Map<String, AssistantTool> tools = new LinkedHashMap<>();
        for (AssistantTool tool : bundle.tools()) {
            tools.put(tool.spec().name(), tool);
        }
        List<ToolSpec> specs = tools.values().stream().map(AssistantTool::spec).toList();
        List<Exchange> exchanges = new ArrayList<>();
        Duration roundBudget = settings.firstCallBudget();
        for (int round = 0; ; round++) {
            if (deadline.expired()) {
                throw unavailable("시간 예산을 넘겼습니다");
            }
            ModelTurn turn =
                    model.step(
                            new Conversation(question, List.copyOf(exchanges)),
                            pack.facts(),
                            specs,
                            deadline.slice(roundBudget));
            if (turn instanceof ModelDraft draft) {
                return new Outcome(draft, pack.facts(), round);
            }
            if (!(turn instanceof ToolRequests requests)) {
                throw unavailable("모델 응답을 해석할 수 없습니다");
            }
            if (round >= settings.maxRounds()) {
                return new Outcome(
                        new ModelDraft(AnswerStatus.INSUFFICIENT_EVIDENCE, List.of()),
                        pack.facts(),
                        round);
            }
            exchanges.add(new Exchange(requests.calls(), execute(requests.calls(), tools, pack)));
            roundBudget = settings.nextCallBudget();
        }
    }

    private List<ToolResult> execute(
            List<ToolCall> calls, Map<String, AssistantTool> tools, FactPack pack) {
        List<ToolResult> results = new ArrayList<>();
        int room = settings.maxFactsPerRound();
        for (ToolCall call : calls == null ? List.<ToolCall>of() : calls) {
            AssistantTool tool = call.name() == null ? null : tools.get(call.name());
            if (tool == null) {
                results.add(ToolResult.unavailable(call.id(), UNAVAILABLE_PREFIX + "그런 도구가 없습니다"));
                continue;
            }
            Map<String, String> arguments = call.arguments() == null ? Map.of() : call.arguments();
            String argumentProblem = checkArguments(tool.spec(), arguments);
            if (argumentProblem != null) {
                results.add(
                        ToolResult.unavailable(call.id(), UNAVAILABLE_PREFIX + argumentProblem));
                continue;
            }
            List<Evidence> produced;
            try {
                produced = tool.run(arguments);
            } catch (ToolUnavailableException exc) {
                results.add(
                        ToolResult.unavailable(call.id(), UNAVAILABLE_PREFIX + exc.getMessage()));
                continue;
            }
            List<Evidence> added = pack.add(produced, room);
            room = Math.max(0, room - added.size());
            results.add(new ToolResult(call.id(), added, null));
        }
        return results;
    }

    /** 인자 이름 집합이 정의와 정확히 같고 값이 모두 {@code YYYY-MM-DD}여야 한다. 식별자 같은 낯선 키는 여기서 걸린다. */
    private static String checkArguments(ToolSpec spec, Map<String, String> arguments) {
        Set<String> expected = new HashSet<>(spec.dateArguments());
        if (!expected.equals(arguments.keySet())) {
            return "인자는 " + spec.dateArguments() + "만 받습니다";
        }
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            try {
                LocalDate.parse(entry.getValue());
            } catch (DateTimeParseException | NullPointerException exc) {
                return entry.getKey() + "는 YYYY-MM-DD 형식이어야 합니다";
            }
        }
        return null;
    }

    private static BusinessException unavailable(String reason) {
        return new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, reason);
    }

    /** id를 {@code F1}부터 매기며 상한을 지키는 팩. 잘리면 마지막 자리에 "일부만 읽음" 근거를 둔다. */
    private static final class FactPack {
        private final int capacity;
        private final List<Evidence> facts = new ArrayList<>();

        FactPack(int capacity) {
            this.capacity = capacity;
        }

        List<Evidence> facts() {
            return List.copyOf(facts);
        }

        /** {@code room}과 남은 자리 중 작은 만큼만 받는다. 돌려주는 것은 실제로 들어간(id가 매겨진) 근거다. */
        List<Evidence> add(List<Evidence> incoming, int room) {
            List<Evidence> source = incoming == null ? List.of() : incoming;
            int available = Math.max(0, Math.min(room, capacity - facts.size()));
            boolean truncated = source.size() > available;
            int take = truncated ? Math.max(0, available - 1) : source.size();
            List<Evidence> added = new ArrayList<>();
            for (Evidence fact : source.subList(0, take)) {
                added.add(assign(fact));
            }
            if (truncated && available > 0) {
                added.add(
                        assign(
                                new Evidence(
                                        null,
                                        TRUNCATED_LABEL,
                                        TRUNCATED_VALUE
                                                + " ("
                                                + source.size()
                                                + "개 중 "
                                                + take
                                                + "개)",
                                        null,
                                        null,
                                        EvidenceKind.TEXT)));
            }
            return added;
        }

        private Evidence assign(Evidence fact) {
            Evidence numbered = fact.withId("F" + (facts.size() + 1));
            facts.add(numbered);
            return numbered;
        }
    }
}
