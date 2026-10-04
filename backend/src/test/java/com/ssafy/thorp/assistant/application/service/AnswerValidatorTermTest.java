package com.ssafy.thorp.assistant.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.assistant.application.result.AnswerStatus;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.EvidenceSource;
import com.ssafy.thorp.assistant.application.result.EvidenceSourceType;
import com.ssafy.thorp.assistant.application.result.ModelDraft;
import com.ssafy.thorp.assistant.application.result.ModelDraft.AnswerPart;
import com.ssafy.thorp.assistant.application.result.PageKind;
import com.ssafy.thorp.assistant.application.service.AnswerValidator.Policy;
import com.ssafy.thorp.assistant.application.service.AnswerValidator.Rejection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 진단 화면 정책(spec 017 FR-007·FR-008, 계약 §2.4, T011). 숫자는 그 문장이 가리키는 근거 원문에 같은 표기로 있을 때만 쓰고, 자리표시자는
 * {@code factIds}의 부분집합이면 된다.
 */
class AnswerValidatorTermTest {

    static Evidence registry(String id, String label, String value) {
        return new Evidence(
                id,
                label,
                value,
                null,
                null,
                EvidenceKind.TEXT,
                EvidenceSource.of(EvidenceSourceType.REGISTRY));
    }

    static final List<Evidence> FACTS =
            List.of(
                    registry("F1", "설정 항목: 역변동성 · 최대 비중", "한 섹터에 최대 40%까지만 배분한다."),
                    registry("F2", "설정 항목: 역변동성 · 관측 기간", "변동성을 재는 데 쓸 과거 20거래일의 길이다."),
                    registry("F3", "섹터 목록", "코스피200(산업, 대표 ETF KODEX 200, 기초 지수 코스피 200)"),
                    registry("F4", "설정 항목: 섹터 모멘텀 · 선정 섹터 수", "두 섹터를 고른다."));

    private final AnswerValidator validator =
            new AnswerValidator(AnswerValidatorTest.SETTINGS, List.of("추천합니다"));

    private AnswerValidator.Rendered render(AnswerPart... parts) {
        return validator.render(
                new ModelDraft(AnswerStatus.ANSWERED, List.of(parts)), FACTS, Policy.TERM, 0);
    }

    private static AnswerPart part(String text, String... factIds) {
        return new AnswerPart(text, List.of(factIds));
    }

    @Test
    @DisplayName("자리표시자 없이 근거를 풀어 쓴 문장은 통과하고 그 근거가 쓰인 근거로 남는다")
    void paraphraseWithoutPlaceholderPasses() {
        AnswerValidator.Rendered rendered = render(part("한 섹터에 넣을 수 있는 몫의 위쪽 한계예요.", "F1"));

        assertThat(rendered.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(rendered.evidence()).extracting(Evidence::id).containsExactly("F1");
        assertThat(rendered.rejectedBy()).isNull();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "한 섹터에는 최대 40%까지 넣어요.|F1|true",
                "한 섹터에는 최대 ４０％까지 넣어요.|F1|true",
                "한 섹터에는 최대 50%까지 넣어요.|F1|false",
                "과거 20거래일 동안의 움직임으로 재요.|F2|true",
                "과거 20 거래일 동안의 움직임으로 재요.|F2|true",
                "과거 20%의 움직임으로 재요.|F2|false",
                "과거 20일 동안의 움직임으로 재요.|F2|false",
                "대표 ETF는 KODEX 200이에요.|F3|true",
                "한 섹터에는 최대 40%까지 넣어요.|F2|false",
                "섹터 두 섹터를 골라요. 두 섹터를 고른다는 뜻이에요.|F4|true",
                "세 섹터를 골라요.|F4|false",
                "스무 거래일을 봐요.|F2|false",
                "첫째, 한편 변동성을 재는 기간이에요.|F2|true",
                "두 번째로 변동성을 재요.|F2|true",
                "기간은 몇 %인가요?|F2|false",
                "자세한 건 https://example.com 에 있어요.|F2|false",
                "금액은 ₩ 단위예요.|F2|false",
                "열다섯 거래일을 봐요.|F2|false",
                "스물두 개 섹터를 봐요.|F2|false",
                "두 가지 방식으로 나눠요.|F2|true",
                "한 번 정한 비중으로 되돌려요.|F2|true",
                "40 원칙에 따라 나눠요.|F1|true",
                "20 일반 거래일과 같아요.|F2|true",
                "과거 20 거래일을 봐요.|F2|true",
                "두 주식을 비교하지 않아요.|F2|true",
                "1，000원까지예요.|F1|false"
            })
    @DisplayName("숫자·한글 수량 토큰은 그 문장이 가리킨 근거 원문에 같은 표기로 있어야 한다(전각은 반각과 같다)")
    void quotedNumbers(String text, String factId, boolean passes) {
        AnswerValidator.Rendered rendered = render(part(text, factId));

        assertThat(rendered.status())
                .isEqualTo(passes ? AnswerStatus.ANSWERED : AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(rendered.rejectedBy()).isEqualTo(passes ? null : Rejection.NUMBER);
    }

    @Test
    @DisplayName("다른 문장 조각이 가리킨 근거의 숫자를 빌려 쓰면 거절한다")
    void cannotBorrowNumbersFromAnotherPart() {
        AnswerValidator.Rendered rendered =
                render(part("관측 기간은 과거 구간의 길이예요.", "F2"), part("최대 비중은 40%예요.", "F2"));

        assertThat(rendered.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(rendered.rejectedBy()).isEqualTo(Rejection.NUMBER);
    }

    @Test
    @DisplayName("자리표시자는 factIds 의 부분집합이면 되고, 근거 문장을 통째로 넣을 수 있다")
    void placeholderSubset() {
        AnswerValidator.Rendered rendered = render(part("설명은 이래요: {{F2}}", "F2", "F1"));

        assertThat(rendered.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(rendered.answer()).isEqualTo("설명은 이래요: 변동성을 재는 데 쓸 과거 20거래일의 길이다.");
        assertThat(rendered.evidence()).extracting(Evidence::id).containsExactly("F2", "F1");
    }

    @Test
    @DisplayName("factIds 에 없는 자리표시자, 빈 factIds, 모르는 근거는 거절하고 걸린 규칙을 남긴다")
    void placeholderAndFactRules() {
        assertThat(render(part("설명: {{F2}}", "F1")).rejectedBy()).isEqualTo(Rejection.PLACEHOLDER);
        assertThat(render(part("근거 없는 문장이에요.")).rejectedBy()).isEqualTo(Rejection.EMPTY);
        assertThat(render(part("모르는 근거예요.", "F9")).rejectedBy()).isEqualTo(Rejection.UNKNOWN_FACT);
        assertThat(render(part("이 전략을 추천합니다.", "F1")).rejectedBy()).isEqualTo(Rejection.FORBIDDEN);
    }

    @Test
    @DisplayName("용어 정책의 고정 문장 셋은 계약 §1 표와 같다")
    void fixedMessages() {
        assertThat(
                        validator
                                .render(
                                        new ModelDraft(
                                                AnswerStatus.INSUFFICIENT_EVIDENCE, List.of()),
                                        FACTS,
                                        Policy.TERM,
                                        0)
                                .answer())
                .isEqualTo("이 사이트 자료로는 확인할 수 없어요.");
        assertThat(
                        validator
                                .render(
                                        new ModelDraft(AnswerStatus.OUT_OF_SCOPE, List.of()),
                                        FACTS,
                                        Policy.TERM,
                                        0)
                                .answer())
                .isEqualTo(
                        "이 도우미는 이 사이트에 나오는 용어와 설정의 뜻을 설명해요. 무엇을 고를지는 정해 드리지 않고, 백테스트 결과는 결과 화면에서 물어볼 수 있어요.");
        assertThat(
                        validator
                                .render(
                                        new ModelDraft(AnswerStatus.GREETING, List.of()),
                                        FACTS,
                                        Policy.TERM,
                                        0)
                                .answer())
                .isEqualTo("안녕하세요. 이 화면에 나오는 용어를 물어보세요. 예: 역변동성은 어떻게 비중을 정해?");
    }

    @Test
    @DisplayName("결과 화면은 015 정책 그대로이고, 진단 화면 두 종류는 용어 정책이다")
    void policyByKind() {
        assertThat(Policy.of(PageKind.RESULT)).isEqualTo(Policy.RESULT);
        assertThat(Policy.of(PageKind.SECTOR_SELECTION)).isEqualTo(Policy.TERM);
        assertThat(Policy.of(PageKind.STRATEGY_SETTINGS)).isEqualTo(Policy.TERM);
        // 결과 정책은 자리표시자 밖 숫자를 근거 원문에 있어도 거절한다.
        assertThat(
                        validator
                                .render(
                                        new ModelDraft(
                                                AnswerStatus.ANSWERED,
                                                List.of(part("최대 40%까지예요.", "F1"))),
                                        FACTS,
                                        Policy.RESULT,
                                        0)
                                .rejectedBy())
                .isEqualTo(Rejection.PLACEHOLDER);
    }

    @Test
    @DisplayName("길이 상한은 서버가 붙일 접두 길이를 미리 뺀 값으로 검사한다")
    void reservedPrefixLengthCountsTowardLimit() {
        String text = "가".repeat(590);
        ModelDraft draft = new ModelDraft(AnswerStatus.ANSWERED, List.of(part(text, "F2")));

        assertThat(validator.render(draft, FACTS, Policy.TERM, 0).status())
                .isEqualTo(AnswerStatus.ANSWERED);
        assertThat(validator.render(draft, FACTS, Policy.TERM, 30).rejectedBy())
                .isEqualTo(Rejection.LENGTH);
    }
}
