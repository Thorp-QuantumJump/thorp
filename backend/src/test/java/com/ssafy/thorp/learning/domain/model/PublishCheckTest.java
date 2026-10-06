package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.PublishCheckFailure.Check;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation.SectorWeight;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import com.ssafy.thorp.learning.infrastructure.rules.YamlPublishCheckRulesLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 게시 전 검사 아홉 가지(research R7, quickstart 3-2·3-4). 규칙은 실제 파일({@code
 * learning/publish-checks-v1.yml})에서 읽는다. 목록을 바꾸면 이 테스트가 그 목록으로 돈다.
 */
class PublishCheckTest {

    private static final PublishCheckRules RULES = new YamlPublishCheckRulesLoader().v1();
    private static final Optional<Set<String>> OPEN_MAP =
            Optional.of(Set.of("SEMICONDUCTOR", "BATTERY"));

    private static final Placement PLACEMENT = new Placement(new StageKey("returns-and-risk"), 1);

    private static final TermId MAX_DRAWDOWN = new TermId("max-drawdown");
    private static final TermId NET_TOTAL_RETURN = new TermId("net-total-return");

    // --- 통과하는 초안과 그 변형 ---

    private static List<ArticleBlock> passingBlocks() {
        return new ArrayList<>(
                List.of(
                        new ParagraphBlock("가장 크게 줄었던 폭은 시작 금액이 아니라 고점에서 잰다."),
                        new TermBlock(MAX_DRAWDOWN),
                        new ParagraphBlock("비중 합계는 늘 100%로 맞춘다."),
                        new TermBlock(NET_TOTAL_RETURN),
                        new HypotheticalBlock(
                                "100만 원으로 시작해 120만 원까지 오른 뒤 90만 원으로 내려갔다면 고점 대비 25% 줄어든 것이다.")));
    }

    private static List<LearningTerm> passingTerms() {
        return new ArrayList<>(
                List.of(
                        new LearningTerm(MAX_DRAWDOWN, "가장 크게 줄었던 폭", "고점에서 가장 크게 줄어든 비율이다."),
                        new LearningTerm(
                                NET_TOTAL_RETURN, "비용 차감 후 전체 수익률", "거래비용을 뺀 뒤 전체 기간의 수익률이다.")));
    }

    private static ExampleAllocation example(int semiconductor, int battery) {
        return new ExampleAllocation(
                List.of(
                        new SectorWeight("SEMICONDUCTOR", semiconductor),
                        new SectorWeight("BATTERY", battery)));
    }

    private static LearningArticle draft(
            String title,
            List<ArticleBlock> blocks,
            ExampleAllocation example,
            List<LearningTerm> terms) {
        return new LearningArticle(
                1L,
                new ArticleId("returns-and-losses"),
                title,
                "가장 크게 줄었던 폭은 왜 시작 금액 대비 손실과 다른가?",
                8,
                ArticleFormat.V1,
                PLACEMENT,
                null,
                blocks,
                example,
                List.of(),
                ArticleStatus.DRAFT,
                null,
                3,
                terms,
                List.of(),
                0);
    }

    private static LearningArticle passingDraft() {
        return draft("수익과 손실", passingBlocks(), example(500, 500), passingTerms());
    }

    private static List<PublishCheckFailure> run(LearningArticle draft) {
        return PublishCheck.run(draft, RULES, OPEN_MAP).failures();
    }

    private static void assertOnly(LearningArticle draft, Check check) {
        assertThat(run(draft))
                .isNotEmpty()
                .extracting(PublishCheckFailure::check)
                .containsOnly(check);
    }

    @Test
    @DisplayName("모든 검사를 통과하는 초안은 결과가 비어 있다")
    void passingDraftPasses() {
        PublishCheckResult result = PublishCheck.run(passingDraft(), RULES, OPEN_MAP);

        assertThat(result.failures()).isEmpty();
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("REQUIRED_FIELDS: 제목이 비었다")
    void requiredFields() {
        assertOnly(
                draft(" ", passingBlocks(), example(500, 500), passingTerms()),
                Check.REQUIRED_FIELDS);
    }

    @Test
    @DisplayName("REQUIRED_FIELDS: 문단이 하나도 없으면 본문 전체를 가리키고 index가 없다")
    void requiredParagraph() {
        List<ArticleBlock> blocks = passingBlocks();
        blocks.removeIf(ParagraphBlock.class::isInstance);
        blocks.add(new HeadingBlock("소제목"));

        List<PublishCheckFailure> failures =
                run(draft("수익과 손실", blocks, example(500, 500), passingTerms()));

        assertThat(failures)
                .extracting(PublishCheckFailure::check)
                .containsOnly(Check.REQUIRED_FIELDS);
        assertThat(failures.get(0).location()).isEqualTo(CheckLocation.body());
        assertThat(failures.get(0).location().index()).isNull();
    }

    @Test
    @DisplayName("EXAMPLE_PRESENT: 예시가 없다")
    void examplePresent() {
        assertOnly(draft("수익과 손실", passingBlocks(), null, passingTerms()), Check.EXAMPLE_PRESENT);
    }

    @Test
    @DisplayName("EXAMPLE_SECTOR_COUNT: 섹터가 하나뿐이다")
    void exampleSectorCount() {
        ExampleAllocation single =
                new ExampleAllocation(List.of(new SectorWeight("SEMICONDUCTOR", 1000)));

        assertOnly(
                draft("수익과 손실", passingBlocks(), single, passingTerms()),
                Check.EXAMPLE_SECTOR_COUNT);
    }

    @Test
    @DisplayName("EXAMPLE_SECTOR_IN_MAP: 열린 대응표에 없는 섹터가 있다")
    void exampleSectorInMap() {
        ExampleAllocation withBio =
                new ExampleAllocation(
                        List.of(new SectorWeight("BIO", 500), new SectorWeight("BATTERY", 500)));

        assertOnly(
                draft("수익과 손실", passingBlocks(), withBio, passingTerms()),
                Check.EXAMPLE_SECTOR_IN_MAP);
        assertThat(run(draft("수익과 손실", passingBlocks(), withBio, passingTerms())).get(0).message())
                .contains("BIO");
    }

    @Test
    @DisplayName("EXAMPLE_SECTOR_IN_MAP: 대응표를 읽지 못하면 통과로 세지 않고 거절한다 (quickstart 3-4)")
    void exampleSectorInMapWithoutMap() {
        List<PublishCheckFailure> failures =
                PublishCheck.run(passingDraft(), RULES, Optional.empty()).failures();

        assertThat(failures)
                .extracting(PublishCheckFailure::check)
                .containsExactly(Check.EXAMPLE_SECTOR_IN_MAP);
        assertThat(failures.get(0).location()).isEqualTo(CheckLocation.example());
        assertThat(failures.get(0).message()).contains("대응표를 확인할 수 없어");
    }

    @Test
    @DisplayName("EXAMPLE_WEIGHT_SUM: 합계가 100%가 아니고 지금 합계를 %로 알린다")
    void exampleWeightSum() {
        List<PublishCheckFailure> failures =
                run(draft("수익과 손실", passingBlocks(), example(500, 455), passingTerms()));

        assertThat(failures)
                .extracting(PublishCheckFailure::check)
                .containsExactly(Check.EXAMPLE_WEIGHT_SUM);
        assertThat(failures.get(0).message()).isEqualTo("예시 비중의 합계가 100%가 아닙니다. 지금 합계는 95.5%입니다.");
    }

    @Test
    @DisplayName("TERM_BLOCKS_MATCH: 용어가 본문에 없거나, 두 번 있거나, 블록이 이 글에 없는 용어를 가리킨다")
    void termBlocksMatch() {
        List<ArticleBlock> missing = passingBlocks();
        missing.remove(3);
        assertOnly(
                draft("수익과 손실", missing, example(500, 500), passingTerms()),
                Check.TERM_BLOCKS_MATCH);

        List<ArticleBlock> twice = passingBlocks();
        twice.add(new TermBlock(MAX_DRAWDOWN));
        List<PublishCheckFailure> twiceFailures =
                run(draft("수익과 손실", twice, example(500, 500), passingTerms()));
        assertThat(twiceFailures)
                .extracting(PublishCheckFailure::check)
                .containsOnly(Check.TERM_BLOCKS_MATCH);
        assertThat(twiceFailures.get(0).location()).isEqualTo(CheckLocation.term(MAX_DRAWDOWN));

        List<ArticleBlock> foreign = passingBlocks();
        foreign.add(new TermBlock(new TermId("rebalancing")));
        List<PublishCheckFailure> foreignFailures =
                run(draft("수익과 손실", foreign, example(500, 500), passingTerms()));
        assertThat(foreignFailures)
                .extracting(PublishCheckFailure::check)
                .containsOnly(Check.TERM_BLOCKS_MATCH);
        assertThat(foreignFailures.get(0).location()).isEqualTo(CheckLocation.block(5));
    }

    @Test
    @DisplayName("TERM_SENTENCE: 문장이 비었거나 300자를 넘는다")
    void termSentence() {
        List<LearningTerm> empty = passingTerms();
        empty.set(1, new LearningTerm(NET_TOTAL_RETURN, "비용 차감 후 전체 수익률", ""));
        assertOnly(draft("수익과 손실", passingBlocks(), example(500, 500), empty), Check.TERM_SENTENCE);

        List<LearningTerm> tooLong = passingTerms();
        tooLong.set(1, new LearningTerm(NET_TOTAL_RETURN, "비용 차감 후 전체 수익률", "가".repeat(301)));
        assertOnly(
                draft("수익과 손실", passingBlocks(), example(500, 500), tooLong), Check.TERM_SENTENCE);
    }

    @Test
    @DisplayName("FORBIDDEN_EXPRESSION: 금지 표현이 있으면 걸린 표현과 위치를 돌려준다")
    void forbiddenExpression() {
        List<ArticleBlock> blocks = passingBlocks();
        blocks.set(2, new ParagraphBlock("이 비중이 성공률이 높다."));

        List<PublishCheckFailure> failures =
                run(draft("수익과 손실", blocks, example(500, 500), passingTerms()));

        assertThat(failures)
                .extracting(PublishCheckFailure::check)
                .containsOnly(Check.FORBIDDEN_EXPRESSION);
        assertThat(failures.get(0).matched()).isEqualTo("성공률");
        assertThat(failures.get(0).location()).isEqualTo(CheckLocation.block(2));
    }

    @Test
    @DisplayName("RESULT_NUMBER_OUTSIDE_HYPOTHETICAL: 가상의 계산 예 밖에 결과성 숫자가 있다")
    void resultNumberOutsideHypothetical() {
        List<ArticleBlock> blocks = passingBlocks();
        blocks.set(0, new ParagraphBlock("이 전략은 2배로 불었고 1,000원이 남았다."));

        List<PublishCheckFailure> failures =
                run(draft("수익과 손실", blocks, example(500, 500), passingTerms()));

        assertThat(failures)
                .extracting(PublishCheckFailure::check)
                .containsOnly(Check.RESULT_NUMBER_OUTSIDE_HYPOTHETICAL);
        assertThat(failures)
                .extracting(PublishCheckFailure::matched)
                .containsExactly("2배", "1,000원");
    }

    @Test
    @DisplayName("quickstart 3-2 초안은 세 검사가 모두 걸리고 위치가 계약 §6 예시와 같다")
    void quickstartDraftReportsAllThree() {
        List<ArticleBlock> blocks = passingBlocks();
        blocks.set(2, new ParagraphBlock("이것이 추천 비중이다."));
        List<LearningTerm> terms = passingTerms();
        terms.set(0, new LearningTerm(MAX_DRAWDOWN, "가장 크게 줄었던 폭", "고점 대비 -30% 줄어든 폭이다."));

        List<PublishCheckFailure> failures = run(draft("수익과 손실", blocks, example(500, 450), terms));

        assertThat(failures)
                .containsExactly(
                        new PublishCheckFailure(
                                Check.EXAMPLE_WEIGHT_SUM,
                                CheckLocation.example(),
                                null,
                                "예시 비중의 합계가 100%가 아닙니다. 지금 합계는 95.0%입니다."),
                        new PublishCheckFailure(
                                Check.FORBIDDEN_EXPRESSION,
                                CheckLocation.block(2),
                                "추천 비중",
                                "추천으로 읽히는 표현이 있습니다."),
                        new PublishCheckFailure(
                                Check.RESULT_NUMBER_OUTSIDE_HYPOTHETICAL,
                                CheckLocation.term(MAX_DRAWDOWN),
                                "-30%",
                                "실제 결과로 읽히는 숫자는 가상의 계산 예 블록에만 쓸 수 있습니다."));
    }

    @Test
    @DisplayName("첫 항목에서 멈추지 않는다: 빈 초안은 걸리는 검사를 모두 돌려준다")
    void doesNotStopAtFirstFailure() {
        LearningArticle empty =
                new LearningArticle(
                        null,
                        new ArticleId("returns-and-losses"),
                        "",
                        "",
                        8,
                        ArticleFormat.V1,
                        PLACEMENT,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        ArticleStatus.DRAFT,
                        null,
                        0,
                        List.of(new LearningTerm(MAX_DRAWDOWN, "가장 크게 줄었던 폭", "")),
                        List.of(),
                        0);

        assertThat(PublishCheck.run(empty, RULES, OPEN_MAP).failures())
                .extracting(PublishCheckFailure::check)
                .containsExactly(
                        Check.REQUIRED_FIELDS,
                        Check.REQUIRED_FIELDS,
                        Check.REQUIRED_FIELDS,
                        Check.EXAMPLE_PRESENT,
                        Check.TERM_BLOCKS_MATCH,
                        Check.TERM_SENTENCE);
    }

    @Test
    @DisplayName("hypothetical 안의 금액·비율과 문단의 100% 하나는 숫자 검사에 걸리지 않는다")
    void hypotheticalNumbersAndHundredPercentAreAllowed() {
        // passingDraft의 hypothetical 블록에 120만 원·25%가, 문단에 100%가 있다.
        assertThat(run(passingDraft())).isEmpty();
    }

    @Test
    @DisplayName("금지 표현은 hypothetical 블록 안도 본다")
    void forbiddenExpressionInsideHypothetical() {
        List<ArticleBlock> blocks = passingBlocks();
        blocks.set(4, new HypotheticalBlock("수익 보장이라고 가정하자."));

        List<PublishCheckFailure> failures =
                run(draft("수익과 손실", blocks, example(500, 500), passingTerms()));

        assertThat(failures)
                .extracting(PublishCheckFailure::check)
                .containsOnly(Check.FORBIDDEN_EXPRESSION);
        assertThat(failures.get(0).location()).isEqualTo(CheckLocation.block(4));
    }

    @Test
    @DisplayName("제목 '과거가 미래를 보장하지 않는 이유'는 걸리지 않는다")
    void guaranteeAloneIsNotForbidden() {
        assertThat(
                        run(
                                draft(
                                        "과거가 미래를 보장하지 않는 이유",
                                        passingBlocks(),
                                        example(500, 500),
                                        passingTerms())))
                .isEmpty();
    }

    @Test
    @DisplayName("공백을 둘 넣은 '추천  비중'도 공백을 줄여 찾는다")
    void collapsedWhitespaceIsMatched() {
        List<ArticleBlock> blocks = passingBlocks();
        blocks.set(2, new ParagraphBlock("이것이 추천  \n비중이다."));

        List<PublishCheckFailure> failures =
                run(draft("수익과 손실", blocks, example(500, 500), passingTerms()));

        assertThat(failures).extracting(PublishCheckFailure::matched).containsExactly("추천 비중");
    }

    @Test
    @DisplayName("줄바꿈 없는 공백·전각 문자로 적어도 금지 표현과 숫자를 찾는다")
    void compatibilityCharactersAreNormalized() {
        List<ArticleBlock> blocks = passingBlocks();
        blocks.set(2, new ParagraphBlock("이것이 추천\u00A0비중이고 수익률은 ３０％다."));

        List<PublishCheckFailure> failures =
                run(draft("수익과 손실", blocks, example(500, 500), passingTerms()));

        assertThat(failures)
                .extracting(PublishCheckFailure::check, PublishCheckFailure::matched)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Check.FORBIDDEN_EXPRESSION, "추천 비중"),
                        org.assertj.core.groups.Tuple.tuple(
                                Check.RESULT_NUMBER_OUTSIDE_HYPOTHETICAL, "30%"));
    }

    @Test
    @DisplayName("'50:50 배분'·'제1원칙'은 결과성 숫자로 걸리지 않는다")
    void allocationWordsAreNotResultNumbers() {
        List<ArticleBlock> blocks = passingBlocks();
        blocks.set(2, new ParagraphBlock("50:50 배분과 60:40배분은 제1원칙을 따른다."));

        assertThat(run(draft("수익과 손실", blocks, example(500, 500), passingTerms()))).isEmpty();
    }

    @Test
    @DisplayName("공백만 담은 문단은 문단으로 세지 않는다")
    void blankParagraphDoesNotCount() {
        List<ArticleBlock> blocks = passingBlocks();
        blocks.set(0, new ParagraphBlock(" "));
        blocks.set(2, new ParagraphBlock("  "));

        assertOnly(
                draft("수익과 손실", blocks, example(500, 500), passingTerms()), Check.REQUIRED_FIELDS);
    }

    @Test
    @DisplayName("v1 검사는 V2 초안을 받지 않는다. 건너뛰어 통과로 세지 않고 예외다")
    void rejectsV2Draft() {
        LearningArticle v2 =
                LearningArticle.newDraft(
                        new ArticleId("volatility-is-not-loss"), "t", 204, PLACEMENT);

        assertThatThrownBy(() -> PublishCheck.run(v2, RULES, OPEN_MAP))
                .isInstanceOf(IllegalStateException.class);
    }
}
