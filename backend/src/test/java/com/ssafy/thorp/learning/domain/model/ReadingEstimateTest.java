package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramBlock.Series;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramKind;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TableBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.RichText.SourceRun;
import com.ssafy.thorp.learning.domain.model.RichText.TextRun;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 예상 읽기 시간과 최소 머문 시간(research R20, quickstart 1 "읽기 시간"). 규칙 값은 v2 파일의 출발값(분당 500자, 나눗수 3, 최저
 * 30초)이다.
 */
class ReadingEstimateTest {

    private static ReadingEstimate estimate(int bodyChars) {
        return ReadingEstimate.of(bodyChars, 500, 3, 30);
    }

    @ParameterizedTest(name = "{0}자 → {1}분")
    @CsvSource({"1749, 3", "1750, 4", "3749, 7", "3750, 8", "0, 1", "200, 1"})
    @DisplayName("보이는 분은 반올림하고 최소 1분이다. 게시 경계는 1,750~3,749자다")
    void minutesRoundHalfUp(int bodyChars, int minutes) {
        assertThat(estimate(bodyChars).readingMinutes()).isEqualTo(minutes);
        assertThat(ReadingEstimate.minutes(bodyChars, 500)).isEqualTo(minutes);
    }

    @ParameterizedTest(name = "{0}자 → {1}초")
    @CsvSource({"1750, 70", "2480, 100", "3749, 150", "100, 30", "749, 30", "751, 31"})
    @DisplayName("최소 머문 시간은 반올림 전 읽기 초의 1/3을 올림하고 최저 30초다")
    void minDwellSecondsCeilWithFloor(int bodyChars, int seconds) {
        assertThat(estimate(bodyChars).minDwellSeconds()).isEqualTo(seconds);
    }

    @Test
    @DisplayName("본문 글자 수는 공백을 빼고, 제목·질문·출처 표시·표 제목·도식 제목은 세지 않는다")
    void bodyCharsCountsVisibleBody() {
        TermId volatility = new TermId("volatility");
        List<ArticleBlock> blocks =
                List.of(
                        new HeadingBlock("가 나"),
                        new ArticleBlock.ParagraphBlock(
                                new RichText(
                                        List.of(
                                                new TextRun("다라"),
                                                new SourceRun(
                                                        new SourceRef(
                                                                "세지 않는 출처",
                                                                "https://example.com"))))),
                        new TermBlock(volatility),
                        new TableBlock(
                                "세지 않는 표 제목",
                                List.of("마", "바"),
                                List.of(List.of(RichText.text("사"), RichText.text("아")))),
                        new DiagramBlock(
                                DiagramKind.BAR,
                                "세지 않는 도식 제목",
                                "원",
                                List.of("칸", "칸"),
                                List.of(new Series("계열", List.of(BigDecimal.ONE, BigDecimal.ONE))),
                                "자차"));
        List<LearningTerm> terms =
                List.of(LearningTerm.v2(volatility, "카", List.of("세지 않는 다른 이름"), "타", "파", "하"));

        // 가나(2) + 다라(2) + 카타파하(4) + 마바사아(4) + 자차(2)
        assertThat(ReadingEstimate.bodyChars(blocks, terms)).isEqualTo(14);
    }

    @Test
    @DisplayName("V1 용어 블록은 이름과 설명 문장을 센다")
    void bodyCharsCountsV1TermSentence() {
        TermId term = new TermId("max-drawdown");

        assertThat(
                        ReadingEstimate.bodyChars(
                                List.of(new TermBlock(term)),
                                List.of(new LearningTerm(term, "가 나", "다 라 마"))))
                .isEqualTo(5);
    }
}
