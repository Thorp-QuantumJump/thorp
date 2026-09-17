package com.ssafy.thorp.learning.presentation.request;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.TermId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code PUT /admin/learning/articles/{articleId}} 요청(learning-admin-api §5). 초안을 통째로 바꾼다.
 *
 * <p>여기서 거절하는 것은 <b>저장할 수 없는 모양</b>이다(길이·개수·형식·중복, data-model §4·§5). 게시해도 되는 글인지(합계 100%, 용어 블록 짝,
 * 금지 표현)는 검사하지 않는다. 초안은 쓰는 도중의 값을 담을 수 있어야 하고, 그 판정은 게시 전 검사가 한다.
 *
 * <p>위반은 400 {@code INVALID_INPUT}이고 {@code error.details.fields}에 위치가 실린다. 알 수 없는 블록 {@code
 * type}처럼 본문을 읽지 못한 경우도 같다({@code GlobalExceptionHandler}).
 */
public record SaveDraftRequest(
        @NotNull Integer expectedDraftVersion,
        @NotNull @Size(max = LearningArticle.TITLE_MAX_LENGTH) String title,
        @NotNull @Size(max = LearningArticle.QUESTION_MAX_LENGTH) String question,
        @NotNull @Min(LearningArticle.MIN_READING_ORDER) @Max(LearningArticle.MAX_READING_ORDER)
                Integer readingOrder,
        @NotNull @Size(max = ArticleBlock.MAX_BLOCKS) List<@NotNull @Valid Block> blocks,
        @Valid Example example,
        @NotNull List<@NotNull @Valid Source> sources,
        @NotNull @UniqueElementKeys(key = "termId", message = "같은 termId가 두 번 있습니다")
                List<@NotNull @Valid Term> terms) {

    /**
     * 본문 블록. {@code type}으로 네 종류를 가른다. <b>알 수 없는 {@code type}은 역직렬화에서 실패해 400이 된다.</b> 모르는 블록을 조용히
     * 버리지 않는다(data-model §4, 원칙 VI).
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Heading.class, name = "heading"),
        @JsonSubTypes.Type(value = Paragraph.class, name = "paragraph"),
        @JsonSubTypes.Type(value = TermBlock.class, name = "term"),
        @JsonSubTypes.Type(value = Hypothetical.class, name = "hypothetical")
    })
    public sealed interface Block permits Heading, Paragraph, TermBlock, Hypothetical {}

    public record Heading(
            @NotNull @Size(min = 1, max = ArticleBlock.HeadingBlock.MAX_LENGTH) String text)
            implements Block {}

    public record Paragraph(
            @NotNull @Size(min = 1, max = ArticleBlock.ParagraphBlock.MAX_LENGTH) String text)
            implements Block {}

    /** 이 글에 없는 용어를 가리켜도 저장은 받는다. 게시 전 검사가 막는다(data-model §4). */
    public record TermBlock(
            @NotNull
                    @Size(min = TermId.MIN_LENGTH, max = TermId.MAX_LENGTH)
                    @Pattern(regexp = ArticleId.REGEX, message = "소문자·숫자를 하이픈으로 이은 식별자여야 합니다")
                    String termId)
            implements Block {}

    public record Hypothetical(
            @NotNull @Size(min = 1, max = ArticleBlock.HypotheticalBlock.MAX_LENGTH) String text)
            implements Block {}

    /** 예시. 합계와 섹터 수는 게시 전 검사가 본다. */
    public record Example(
            @NotNull
                    @Size(max = ExampleAllocation.MAX_SECTORS)
                    @UniqueElementKeys(key = "code", message = "같은 섹터 코드가 두 번 있습니다")
                    List<@NotNull @Valid Sector> sectors) {}

    /**
     * @param weightTenths 0.1%p 단위 정수이고 {@code 1000}이 100%다(002 selection-handoff 계약)
     */
    public record Sector(
            @NotNull
                    @Pattern(
                            regexp = ExampleAllocation.SectorWeight.CODE_REGEX,
                            message = "섹터 코드 형식이 아닙니다")
                    String code,
            @NotNull @Min(0) @Max(ExampleAllocation.FULL_WEIGHT_TENTHS) Integer weightTenths) {}

    /**
     * 출처. 길이 상한은 data-model이 정하지 않아 여기서 둔 값이다.
     *
     * @param url 없을 수 있다. 있으면 {@code http}·{@code https}만 받는다
     */
    public record Source(
            @NotBlank @Size(max = 200) String label,
            @Size(max = 2000)
                    @Pattern(regexp = "^https?://\\S+$", message = "http 또는 https 주소여야 합니다")
                    String url) {}

    /** 용어 설명. 이름과 문장의 길이 상한은 저장 컬럼과 같다. 빈 문장은 게시 전 검사가 막는다. */
    public record Term(
            @NotNull
                    @Size(min = TermId.MIN_LENGTH, max = TermId.MAX_LENGTH)
                    @Pattern(regexp = ArticleId.REGEX, message = "소문자·숫자를 하이픈으로 이은 식별자여야 합니다")
                    String termId,
            @NotNull @Size(max = LearningTerm.LABEL_MAX_LENGTH) String label,
            @NotNull @Size(max = LearningTerm.SENTENCE_MAX_LENGTH) String sentence) {}
}
