package com.ssafy.thorp.assistant.application.service;

import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.ModelDraft;
import com.ssafy.thorp.assistant.application.result.ModelDraft.AnswerPart;
import com.ssafy.thorp.assistant.application.result.PageKind;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 모델 초안을 계약 §2.4 규칙 표대로 검사하고, 통과하면 {@code {{Fn}}}을 근거 값의 표기로 치환한다(015 FR-009, 017 FR-007).
 *
 * <p>하나라도 걸리면 답변 전체를 버리고 근거 부족 고정 안내로 바꾼다. 503이 아니다 — 모델은 응답했으나 쓸 수 없는 경우다. 결과 화면은 답의 모든 숫자·날짜가 서버가
 * 읽은 값이고, 진단 화면은 답의 모든 숫자가 그 문장이 가리키는 근거 원문에 같은 표기로 있다.
 *
 * <p>금지 표현 목록은 <b>보조 검사</b>다. 실제 방어는 프롬프트와 품질 표본(묶음 6)이다. 숫자·근거 ID만 보므로 근거를 가리키면서 근거 밖 일반 지식을 문장으로
 * 보태는 답은 여기서 막지 못한다(017 FR-007 남은 위험).
 */
@Component
public class AnswerValidator {
    static final String INSUFFICIENT_MESSAGE = "이 화면 자료만으로 확인할 수 없습니다.";
    static final String OUT_OF_SCOPE_MESSAGE =
            "이 도우미는 지금 보고 있는 기준 결과의 기록을 설명합니다. 투자 추천이나 앞으로의 성과, 다른 결과에 대해서는 답하지 않습니다.";
    static final String GREETING_MESSAGE = "안녕하세요. 지금 보고 있는 결과에 대해 물어보세요. 예: 수익률은 어떻게 계산된거야?";
    static final String TERM_INSUFFICIENT_MESSAGE = "이 사이트 자료로는 확인할 수 없어요.";
    static final String TERM_OUT_OF_SCOPE_MESSAGE =
            "이 도우미는 이 사이트에 나오는 용어와 설정의 뜻을 설명해요. 무엇을 고를지는 정해 드리지 않고, 백테스트 결과는 결과 화면에서 물어볼 수 있어요.";
    static final String TERM_GREETING_MESSAGE = "안녕하세요. 이 화면에 나오는 용어를 물어보세요. 예: 역변동성은 어떻게 비중을 정해?";
    static final String FORBIDDEN_PHRASES_RESOURCE = "assistant/forbidden-phrases.txt";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(F\\d+)}}");

    /**
     * 결과 화면: 자리표시자 밖에 남으면 안 되는 것 — 아라비아 숫자(전각 포함)·%·통화 기호·URL·경로({@code /trades}). 한글 사이의 빗금({@code
     * 매수/매도})은 남는다. 날짜·금액은 숫자를 품으니 함께 걸린다. 한글 수사는 남는다.
     */
    private static final Pattern LITERAL_VALUE =
            Pattern.compile(
                    "[0-9０-９%％₩$]|https?://|www\\.|://|/[A-Za-z_]|\\\\[A-Za-z]",
                    Pattern.CASE_INSENSITIVE);

    /** 두 정책 모두 자리표시자 밖에서 거절하는 것: 통화 기호·URL·경로. */
    private static final Pattern NEVER_LITERAL =
            Pattern.compile(
                    "[₩$]|https?://|www\\.|://|/[A-Za-z_]|\\\\[A-Za-z]", Pattern.CASE_INSENSITIVE);

    /** 계약 §2.4 수량 단위 목록. 긴 단위부터 맞춘다({@code 거래일}→{@code 일}, {@code 개월}→{@code 개}). */
    private static final String UNITS = "거래일|개월|가지|종목|섹터|%|％|원|개|번|일|주|년";

    private static final String COUNT_UNITS = "거래일|개월|가지|종목|섹터|개|번|일|주|년";

    /** 아라비아 숫자 덩어리와 바로 붙거나 한 칸 뒤의 단위. 끝의 쉼표·마침표는 숫자에 넣지 않는다. */
    private static final Pattern NUMBER_TOKEN =
            Pattern.compile("[0-9０-９](?:[0-9０-９.,]*[0-9０-９])?(?:\\s?(?:" + UNITS + "))?");

    /**
     * 어절 첫머리의 고유어 수사 + 수량 단위. 서수({@code 두 번째})는 뺀다. 한자어 수사는 {@code 이번}·{@code 일주} 같은 일반 낱말과 겹쳐 대상이
     * 아니다(계약 §2.4).
     */
    private static final Pattern KOREAN_COUNT_TOKEN =
            Pattern.compile(
                    "(?<![가-힣])(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|스무|스물|서른|마흔|쉰)\\s?(?:"
                            + COUNT_UNITS
                            + ")(?!째)");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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

    /** 숫자 규칙(017 data-model §7). */
    public enum NumberRule {
        /** 자리표시자 밖 숫자 전부 거절(한글 수사는 허용). */
        STRICT,
        /** 숫자·한글 수량 토큰이 그 문장 조각의 근거 원문에 같은 표기로 있어야 한다. */
        QUOTED
    }

    /** 자리표시자와 {@code factIds}의 관계(017 data-model §7). */
    public enum PlaceholderRule {
        /** 집합이 같다. */
        EXACT,
        /** 자리표시자 ⊆ {@code factIds}. 근거를 풀어 쓴 문장은 자리표시자 없이 근거를 가리킨다. */
        SUBSET
    }

    /** 걸린 규칙 이름. 운영 로그에만 남기고 걸린 문구는 남기지 않는다(017 FR-013). */
    public enum Rejection {
        EMPTY,
        UNKNOWN_FACT,
        PLACEHOLDER,
        NUMBER,
        LENGTH,
        FORBIDDEN
    }

    /** 화면 종류가 정하는 검증 정책과 고정 문장 셋(017 data-model §7, 계약 §1). */
    public record Policy(
            NumberRule numbers,
            PlaceholderRule placeholders,
            String insufficientMessage,
            String outOfScopeMessage,
            String greetingMessage) {

        public static final Policy RESULT =
                new Policy(
                        NumberRule.STRICT,
                        PlaceholderRule.EXACT,
                        INSUFFICIENT_MESSAGE,
                        OUT_OF_SCOPE_MESSAGE,
                        GREETING_MESSAGE);

        public static final Policy TERM =
                new Policy(
                        NumberRule.QUOTED,
                        PlaceholderRule.SUBSET,
                        TERM_INSUFFICIENT_MESSAGE,
                        TERM_OUT_OF_SCOPE_MESSAGE,
                        TERM_GREETING_MESSAGE);

        public static Policy of(PageKind kind) {
            return kind == PageKind.RESULT ? RESULT : TERM;
        }
    }

    /**
     * 검증·치환 결과. {@code ANSWERED}가 아니면 답은 고정 문장이고 근거는 비어 있다(계약 §1).
     *
     * @param rejectedBy 모델 초안이 검증에 걸려 버려졌으면 그 규칙, 아니면 null
     */
    public record Rendered(
            AnswerStatus status, String answer, List<Evidence> evidence, Rejection rejectedBy) {}

    public Rendered render(ModelDraft draft, List<Evidence> facts) {
        return render(draft, facts, Policy.RESULT);
    }

    public Rendered render(ModelDraft draft, List<Evidence> facts, Policy policy) {
        if (draft == null || draft.status() == null) {
            return insufficient(policy, Rejection.EMPTY);
        }
        return switch (draft.status()) {
            case OUT_OF_SCOPE ->
                    new Rendered(
                            AnswerStatus.OUT_OF_SCOPE, policy.outOfScopeMessage(), List.of(), null);
            case INSUFFICIENT_EVIDENCE -> insufficient(policy, null);
            case GREETING ->
                    new Rendered(AnswerStatus.GREETING, policy.greetingMessage(), List.of(), null);
            case ANSWERED -> answered(draft.parts(), facts, policy);
        };
    }

    static Rendered insufficient() {
        return insufficient(Policy.RESULT, null);
    }

    private static Rendered insufficient(Policy policy, Rejection rejection) {
        return new Rendered(
                AnswerStatus.INSUFFICIENT_EVIDENCE,
                policy.insufficientMessage(),
                List.of(),
                rejection);
    }

    private Rendered answered(List<AnswerPart> parts, List<Evidence> facts, Policy policy) {
        if (parts == null || parts.isEmpty()) {
            return insufficient(policy, Rejection.EMPTY);
        }
        if (parts.size() > settings.maxParts()) {
            return insufficient(policy, Rejection.LENGTH);
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
                return insufficient(policy, Rejection.EMPTY);
            }
            Set<String> declared = new HashSet<>(factIds);
            if (!byId.keySet().containsAll(declared)) {
                return insufficient(policy, Rejection.UNKNOWN_FACT);
            }
            Set<String> placeholders = new HashSet<>();
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                placeholders.add(matcher.group(1));
            }
            boolean placeholdersOk =
                    switch (policy.placeholders()) {
                        case EXACT -> placeholders.equals(declared);
                        case SUBSET -> declared.containsAll(placeholders);
                    };
            if (!placeholdersOk) {
                return insufficient(policy, Rejection.PLACEHOLDER);
            }
            String residual = PLACEHOLDER.matcher(text).replaceAll("");
            boolean numbersOk =
                    switch (policy.numbers()) {
                        case STRICT -> !LITERAL_VALUE.matcher(residual).find();
                        case QUOTED -> quotedNumbersOk(residual, cited(declared, byId));
                    };
            if (!numbersOk) {
                return insufficient(policy, Rejection.NUMBER);
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
            return insufficient(policy, Rejection.LENGTH);
        }
        for (String phrase : forbiddenPhrases) {
            if (full.contains(phrase)) {
                return insufficient(policy, Rejection.FORBIDDEN);
            }
        }
        return new Rendered(AnswerStatus.ANSWERED, full, List.copyOf(used.values()), null);
    }

    /** 이 문장 조각이 가리키는 근거의 원문들. 비교를 위해 공백을 지우고 전각을 반각으로 바꿔 둔다. */
    private static List<String> cited(Set<String> declared, Map<String, Evidence> byId) {
        List<String> values = new ArrayList<>();
        for (String id : declared) {
            String value = byId.get(id).value();
            if (value != null) {
                values.add(normalize(value));
            }
        }
        return values;
    }

    /**
     * 017 계약 §2.4: 숫자 토큰과 한글 수사 + 수량 단위 토큰이 모두 인용 근거 원문 하나에 같은 표기로 있어야 한다. 토큰을 지우고도 숫자·{@code %}가
     * 남거나 통화 기호·URL·경로가 있으면 거절한다.
     */
    static boolean quotedNumbersOk(String residual, List<String> citedValues) {
        if (NEVER_LITERAL.matcher(residual).find()) {
            return false;
        }
        String rest = residual;
        for (Pattern pattern : List.of(NUMBER_TOKEN, KOREAN_COUNT_TOKEN)) {
            Matcher matcher = pattern.matcher(rest);
            StringBuilder remaining = new StringBuilder();
            while (matcher.find()) {
                String token = normalize(matcher.group());
                if (citedValues.stream().noneMatch(value -> value.contains(token))) {
                    return false;
                }
                matcher.appendReplacement(remaining, " ");
            }
            matcher.appendTail(remaining);
            rest = remaining.toString();
        }
        return !Pattern.compile("[0-9０-９%％]").matcher(rest).find();
    }

    /** 공백을 지우고 전각 숫자·{@code ％}를 반각으로 바꾼다. */
    static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char ch : WHITESPACE.matcher(text).replaceAll("").toCharArray()) {
            if (ch >= '０' && ch <= '９') {
                out.append((char) ('0' + (ch - '０')));
            } else if (ch == '％') {
                out.append('%');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
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
