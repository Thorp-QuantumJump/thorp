package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.learning.domain.model.RichText.SourceRun;
import com.ssafy.thorp.learning.domain.model.RichText.TermMentionRun;
import com.ssafy.thorp.learning.domain.model.RichText.TextRun;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 글자 조각 규칙(data-model §5-2). */
class RichTextTest {

    private static final SourceRef SOURCE = new SourceRef("출처", "https://example.com");

    @Test
    @DisplayName("글자 조각은 1자 이상이고 용어 언급의 글자는 1~50자다")
    void runLengths() {
        assertThatThrownBy(() -> new TextRun("")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new TermMentionRun(new TermId("max-drawdown"), "가".repeat(50)).text())
                .hasSize(50);
        assertThatThrownBy(() -> new TermMentionRun(new TermId("max-drawdown"), "가".repeat(51)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TermMentionRun(new TermId("max-drawdown"), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("출처 주소는 http·https만, 2,000자 이하이고 이름은 100자 이하다")
    void sourceRules() {
        assertThat(new SourceRef("", "http://example.com").label()).isEmpty();
        assertThatThrownBy(() -> new SourceRef("출처", "javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceRef("출처", "https://" + "a".repeat(1993)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SourceRef("출처", "https://" + "a".repeat(1992)).url()).hasSize(2000);
        assertThatThrownBy(() -> new SourceRef("가".repeat(101), "https://example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("출처 표시는 첫 조각일 수 없다(인용한 글자 바로 옆, FR-007)")
    void sourceCannotLead() {
        assertThatThrownBy(() -> new RichText(List.of(new SourceRun(SOURCE), new TextRun("글자"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new RichText(List.of(new TextRun("글자"), new SourceRun(SOURCE))).runs())
                .hasSize(2);
    }

    @Test
    @DisplayName("글자 수는 글자와 용어 언급의 text를 더하고 출처 표시는 세지 않는다")
    void lengthCountsTextAndMentions() {
        RichText text =
                new RichText(
                        List.of(
                                new TextRun("가나"),
                                new TermMentionRun(new TermId("volatility"), "변동성"),
                                new SourceRun(SOURCE)));

        assertThat(text.length()).isEqualTo(5);
        assertThat(text.plainText()).isEqualTo("가나변동성");
        assertThat(text.mentions())
                .extracting(TermMentionRun::termId)
                .containsExactly(new TermId("volatility"));
        assertThat(text.sources()).hasSize(1);
        assertThat(text.isSingleText()).isFalse();
        assertThat(RichText.text("가").isSingleText()).isTrue();
    }

    @Test
    @DisplayName("필드 규칙은 글자 수·조각 수·허용 조각 종류를 본다")
    void fieldRequirements() {
        RichText withMention =
                new RichText(
                        List.of(
                                new TextRun("가"),
                                new TermMentionRun(new TermId("volatility"), "나")));

        withMention.require("f", 1, 2, 2, RichText.ALL_KINDS);
        assertThatThrownBy(() -> withMention.require("f", 3, 10, 2, RichText.ALL_KINDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withMention.require("f", 1, 1, 2, RichText.ALL_KINDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withMention.require("f", 1, 10, 1, RichText.ALL_KINDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withMention.require("f", 1, 10, 2, RichText.TEXT_AND_SOURCE))
                .isInstanceOf(IllegalArgumentException.class);
        new RichText(List.of()).require("cell", 0, 100, 10, RichText.TEXT_AND_SOURCE);
    }
}
