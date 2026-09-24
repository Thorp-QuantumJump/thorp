package com.ssafy.thorp.assistant.application.service;

import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.ModelDraft;
import com.ssafy.thorp.assistant.application.result.ModelDraft.AnswerPart;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 모델 초안을 계약 §2.4 규칙 표대로 검사하고, 통과하면 {@code {{Fn}}}을 근거 값의 표기로 치환한다(FR-009).
 *
 * <p>하나라도 걸리면 답변 전체를 버리고 근거 부족 고정 안내로 바꾼다. 503이 아니다 — 모델은 응답했으나 쓸 수 없는 경우다. 그래서 답의 모든 숫자·날짜는 서버가 읽은
 * 값이다.
 *
 * <p>금지 표현 목록은 <b>보조 검사</b>다. 실제 방어는 프롬프트와 품질 표본(묶음 6)이다.
 */
@Component
public class AnswerValidator {
    static final String INSUFFICIENT_MESSAGE = "이 화면 자료만으로 확인할 수 없습니다.";
    static final String OUT_OF_SCOPE_MESSAGE =
            "이 도우미는 지금 보고 있는 기준 결과의 기록을 설명합니다. 투자 추천이나 앞으로의 성과, 다른 결과에 대해서는 답하지 않습니다.";
    static final String FORBIDDEN_PHRASES_RESOURCE = "assistant/forbidden-phrases.txt";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(F\\d+)}}");

    /**
     * 자리표시자 밖에 남으면 안 되는 것: 아라비아 숫자(전각 포함)·%·통화 기호·URL·경로({@code /trades}). 한글 사이의 빗금({@code
     * 매수/매도})은 남는다. 날짜·금액은 숫자를 품으니 함께 걸린다. 한글 수사는 남는다.
     */
    private static final Pattern LITERAL_VALUE =
            Pattern.compile(
                    "[0-9０-９%％₩$]|https?://|www\\.|://|/[A-Za-z_]|\\\\[A-Za-z]",
                    Pattern.CASE_INSENSITIVE);

    private final AssistantSettings settings;
    private final List<String> forbiddenPhrases;

    @Autowired
    public AnswerValidator(AssistantSettings settings) {
        this(settings, loadForbiddenPhrases());
    }

    AnswerValidator(AssistantSettings settings, List<String> forbiddenPhrases) {
        this.settings = settings;
        this.forbiddenPhrases = List.copyOf(forbiddenPhrases);
    }

    /** 검증·치환 결과. {@code ANSWERED}가 아니면 답은 고정 문장이고 근거는 비어 있다(계약 §1). */
    public record Rendered(AnswerStatus status, String answer, List<Evidence> evidence) {}

    public Rendered render(ModelDraft draft, List<Evidence> facts) {
        if (draft == null || draft.status() == null) {
            return insufficient();
        }
        return switch (draft.status()) {
            case OUT_OF_SCOPE ->
                    new Rendered(AnswerStatus.OUT_OF_SCOPE, OUT_OF_SCOPE_MESSAGE, List.of());
            case INSUFFICIENT_EVIDENCE -> insufficient();
            case ANSWERED ->
                    answered(draft.parts(), facts).orElseGet(AnswerValidator::insufficient);
        };
    }

    static Rendered insufficient() {
        return new Rendered(AnswerStatus.INSUFFICIENT_EVIDENCE, INSUFFICIENT_MESSAGE, List.of());
    }

    private Optional<Rendered> answered(List<AnswerPart> parts, List<Evidence> facts) {
        if (parts == null || parts.isEmpty() || parts.size() > settings.maxParts()) {
            return Optional.empty();
        }
        Map<String, Evidence> byId =
                facts.stream()
                        .filter(fact -> fact.id() != null)
                        .collect(
                                Collectors.toMap(
                                        Evidence::id,
                                        Function.identity(),
                                        (a, b) -> a,
                                        LinkedHashMap::new));
        Map<String, Evidence> used = new LinkedHashMap<>();
        StringBuilder answer = new StringBuilder();
        for (AnswerPart part : parts) {
            String text = part.text();
            List<String> factIds = part.factIds();
            if (text == null || text.isBlank() || factIds == null || factIds.isEmpty()) {
                return Optional.empty();
            }
            Set<String> declared = new HashSet<>(factIds);
            if (!byId.keySet().containsAll(declared)) {
                return Optional.empty();
            }
            Set<String> placeholders = new HashSet<>();
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                placeholders.add(matcher.group(1));
            }
            if (!placeholders.equals(declared)) {
                return Optional.empty();
            }
            String residual = PLACEHOLDER.matcher(text).replaceAll("");
            if (LITERAL_VALUE.matcher(residual).find()) {
                return Optional.empty();
            }
            String rendered =
                    PLACEHOLDER
                            .matcher(text)
                            .replaceAll(
                                    m ->
                                            Matcher.quoteReplacement(
                                                    byId.get(m.group(1)).formattedValue()));
            if (!answer.isEmpty()) {
                answer.append(' ');
            }
            answer.append(rendered.strip());
            factIds.forEach(id -> used.putIfAbsent(id, byId.get(id)));
        }
        String full = answer.toString();
        if (full.length() > settings.maxAnswerLength()) {
            return Optional.empty();
        }
        for (String phrase : forbiddenPhrases) {
            if (full.contains(phrase)) {
                return Optional.empty();
            }
        }
        return Optional.of(new Rendered(AnswerStatus.ANSWERED, full, List.copyOf(used.values())));
    }

    /** 한 줄에 하나, {@code #}으로 시작하는 줄과 빈 줄은 무시한다. */
    static List<String> loadForbiddenPhrases() {
        ClassLoader loader = AnswerValidator.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(FORBIDDEN_PHRASES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("금지 표현 목록이 없습니다: " + FORBIDDEN_PHRASES_RESOURCE);
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
            }
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }
}
