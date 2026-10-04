package com.ssafy.thorp.assistant.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.ModelDraft;
import com.ssafy.thorp.assistant.application.result.ModelDraft.AnswerPart;
import com.ssafy.thorp.assistant.application.result.PageKind;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 계약 §2.4 규칙 표(T011). 하나라도 걸리면 답 전체가 근거 부족 안내로 바뀐다. */
class AnswerValidatorTest {

    static final AssistantSettings SETTINGS =
            new AssistantSettings(
                    Set.of(PageKind.RESULT),
                    8,
                    5,
                    1000,
                    300,
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(30),
                    2,
                    60,
                    30,
                    600,
                    5,
                    5,
                    40);

    static final List<Evidence> FACTS =
            List.of(
                    new Evidence(
                            "F1",
                            "비용 차감 후 전체 수익률",
                            "0.166652",
                            LocalDate.of(2024, 12, 31),
                            "summary",
                            EvidenceKind.RATIO),
                    new Evidence("F2", "거래 횟수", "24", null, "summary", EvidenceKind.INTEGER),
                    new Evidence("F3", "평가 종료일", "2024-12-31", null, "settings", EvidenceKind.DATE),
                    new Evidence("F4", "거래비용 합계", "17078.49", null, "settings", EvidenceKind.MONEY),
                    new Evidence("F5", "수수료", "12000", null, "settings", EvidenceKind.MONEY),
                    new Evidence("F6", "슬리피지", "5078", null, "settings", EvidenceKind.MONEY));

    private final AnswerValidator validator = new AnswerValidator(SETTINGS, List.of("추천합니다", "확률"));

    private static ModelDraft answered(AnswerPart... parts) {
        return new ModelDraft(AnswerStatus.ANSWERED, List.of(parts));
    }

    private static AnswerPart part(String text, String... factIds) {
        return new AnswerPart(text, List.of(factIds));
    }

    @Test
    @DisplayName("통과하면 자리표시자가 근거 값의 표기로 치환되고 쓰인 근거만 남는다")
    void substitutesWithFormattedValues() {
        AnswerValidator.Rendered rendered =
                validator.render(
                        answered(
                                part("비용을 뺀 전체 수익률은 {{F1}}이고, 거래비용은 모두 {{F4}}입니다.", "F1", "F4"),
                                part("그중 수수료가 {{F5}}, 슬리피지가 {{F6}}입니다.", "F5", "F6")),
                        FACTS,
                        AnswerValidator.Policy.RESULT,
                        0);

        assertThat(rendered.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(rendered.answer())
                .isEqualTo(
                        "비용을 뺀 전체 수익률은 16.67%이고, 거래비용은 모두 17,078원입니다. 그중 수수료가 12,000원, 슬리피지가 5,078원입니다.");
        assertThat(rendered.evidence())
                .extracting(Evidence::id)
                .containsExactly("F1", "F4", "F5", "F6");
    }

    @Test
    @DisplayName("표기는 프론트 formatResult.ts 와 같다 — HALF_UP, -0.00 은 0.00, 날짜는 ISO 원문")
    void formattingMatchesFrontend() {
        assertThat(EvidenceKind.MONEY.format("17078.49")).isEqualTo("17,078원");
        assertThat(EvidenceKind.MONEY.format("17078.5")).isEqualTo("17,079원");
        assertThat(EvidenceKind.RATIO.format("0.166652")).isEqualTo("16.67%");
        assertThat(EvidenceKind.RATIO.format("0.12345")).isEqualTo("12.35%");
        assertThat(EvidenceKind.RATIO.format("-0.00001")).isEqualTo("0.00%");
        assertThat(EvidenceKind.DECIMAL2.format("1.2345")).isEqualTo("1.23");
        assertThat(EvidenceKind.INTEGER.format("1234")).isEqualTo("1,234");
        assertThat(EvidenceKind.DATE.format("2024-12-31")).isEqualTo("2024-12-31");
        assertThat(EvidenceKind.TEXT.format("사용자 입력")).isEqualTo("사용자 입력");
        assertThat(EvidenceKind.MONEY.format("n/a")).isEqualTo("n/a");
    }

    @Test
    @DisplayName("모르는 factId 는 거절한다")
    void rejectsUnknownFactId() {
        AnswerValidator.Rendered rendered =
                validator.render(
                        answered(part("수익률은 {{F9}}입니다.", "F9")),
                        FACTS,
                        AnswerValidator.Policy.RESULT,
                        0);

        assertInsufficient(rendered);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
            strings = {
                "수익률은 {{F1}}이고 거래는 24번입니다.",
                "수익률은 {{F1}}, 약 17%입니다.",
                "수익률은 {{F1}}이고 비용은 17078원입니다.",
                "수익률은 {{F1}}이고 기준일은 2024-12-31입니다.",
                "수익률은 {{F1}}이고 기준일은 12월 31일입니다.",
                "수익률은 {{F1}}입니다. https://example.com 참고.",
                "수익률은 {{F1}}이고 거래는 ２４번입니다.",
                "{{F1}}은 /trades에서 보세요.",
                "{{F1}}은 C:\\\\results 폴더에 있습니다."
            })
    @DisplayName("자리표시자 밖의 아라비아 숫자·%·원 금액·날짜·URL·경로는 거절한다")
    void rejectsLiteralValuesOutsidePlaceholders(String text) {
        assertInsufficient(
                validator.render(
                        answered(part(text, "F1")), FACTS, AnswerValidator.Policy.RESULT, 0));
    }

    @Test
    @DisplayName("한글 수사는 통과한다")
    void allowsKoreanNumerals() {
        AnswerValidator.Rendered rendered =
                validator.render(
                        answered(part("두 번 거래했고 세 종목을 들었으며 수익률은 {{F1}}입니다.", "F1")),
                        FACTS,
                        AnswerValidator.Policy.RESULT,
                        0);

        assertThat(rendered.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(rendered.answer()).isEqualTo("두 번 거래했고 세 종목을 들었으며 수익률은 16.67%입니다.");
        assertThat(
                        validator
                                .render(
                                        answered(part("매수/매도 모두 {{F1}}에 반영됐습니다.", "F1")),
                                        FACTS,
                                        AnswerValidator.Policy.RESULT,
                                        0)
                                .status())
                .isEqualTo(AnswerStatus.ANSWERED);
    }

    @Test
    @DisplayName("text 의 자리표시자와 factIds 가 어긋나면 어느 쪽이든 거절한다")
    void rejectsPlaceholderFactIdMismatch() {
        assertInsufficient(
                validator.render(
                        answered(part("수익률은 {{F1}}입니다.", "F1", "F4")),
                        FACTS,
                        AnswerValidator.Policy.RESULT,
                        0));
        assertInsufficient(
                validator.render(
                        answered(part("수익률은 {{F1}}, 비용은 {{F4}}입니다.", "F1")),
                        FACTS,
                        AnswerValidator.Policy.RESULT,
                        0));
    }

    @Test
    @DisplayName("ANSWERED 인데 factIds 가 빈 part 는 거절한다")
    void rejectsAnsweredPartWithoutFacts() {
        assertInsufficient(
                validator.render(
                        answered(
                                part("수익률은 {{F1}}입니다.", "F1"),
                                new AnswerPart("좋은 결과네요.", List.of())),
                        FACTS,
                        AnswerValidator.Policy.RESULT,
                        0));
    }

    @Test
    @DisplayName("전체 길이·part 수 상한을 넘으면 거절한다")
    void rejectsOverLength() {
        AnswerValidator strict =
                new AnswerValidator(
                        new AssistantSettings(
                                Set.of(PageKind.RESULT),
                                8,
                                5,
                                1000,
                                300,
                                Duration.ofMinutes(2),
                                Duration.ofSeconds(60),
                                Duration.ofSeconds(30),
                                2,
                                60,
                                30,
                                20,
                                1,
                                5,
                                40),
                        List.of());

        assertInsufficient(
                strict.render(
                        answered(part("비용을 뺀 전체 수익률은 {{F1}}입니다. 아주 긴 문장입니다.", "F1")),
                        FACTS,
                        AnswerValidator.Policy.RESULT,
                        0));
        assertInsufficient(
                strict.render(
                        answered(part("{{F1}}", "F1"), part("{{F4}}", "F4")),
                        FACTS,
                        AnswerValidator.Policy.RESULT,
                        0));
    }

    @Test
    @DisplayName("금지 표현이 들어 있으면 거절한다")
    void rejectsForbiddenPhrases() {
        assertInsufficient(
                validator.render(
                        answered(part("수익률이 {{F1}}이니 이 전략을 추천합니다.", "F1")),
                        FACTS,
                        AnswerValidator.Policy.RESULT,
                        0));
    }

    @Test
    @DisplayName("OUT_OF_SCOPE·INSUFFICIENT_EVIDENCE·GREETING 은 고정 안내와 빈 근거다")
    void fixedMessagesForNonAnswered() {
        AnswerValidator.Rendered scope =
                validator.render(
                        new ModelDraft(
                                AnswerStatus.OUT_OF_SCOPE,
                                List.of(part("이 전략 사도 됩니다 {{F1}}", "F1"))),
                        FACTS,
                        AnswerValidator.Policy.RESULT,
                        0);
        assertThat(scope.status()).isEqualTo(AnswerStatus.OUT_OF_SCOPE);
        assertThat(scope.answer()).isEqualTo(AnswerValidator.OUT_OF_SCOPE_MESSAGE);
        assertThat(scope.evidence()).isEmpty();

        assertInsufficient(
                validator.render(
                        new ModelDraft(AnswerStatus.INSUFFICIENT_EVIDENCE, List.of()),
                        FACTS,
                        AnswerValidator.Policy.RESULT,
                        0));
        assertInsufficient(validator.render(null, FACTS, AnswerValidator.Policy.RESULT, 0));

        AnswerValidator.Rendered greeting =
                validator.render(
                        new ModelDraft(AnswerStatus.GREETING, List.of(part("안녕하세요 {{F1}}", "F1"))),
                        FACTS,
                        AnswerValidator.Policy.RESULT,
                        0);
        assertThat(greeting.status()).isEqualTo(AnswerStatus.GREETING);
        assertThat(greeting.answer()).isEqualTo(AnswerValidator.GREETING_MESSAGE);
        assertThat(greeting.evidence()).isEmpty();
    }

    @Test
    @DisplayName("금지 표현 파일은 주석·빈 줄을 빼고 읽힌다")
    void loadsForbiddenPhrasesResource() {
        List<String> phrases = AnswerValidator.loadForbiddenPhrases();

        assertThat(phrases).isNotEmpty().contains("추천합니다").noneMatch(line -> line.startsWith("#"));
    }

    private static void assertInsufficient(AnswerValidator.Rendered rendered) {
        assertThat(rendered.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(rendered.answer()).isEqualTo(AnswerValidator.INSUFFICIENT_MESSAGE);
        assertThat(rendered.evidence()).isEmpty();
    }
}
