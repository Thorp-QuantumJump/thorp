package com.ssafy.thorp.learning.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.learning.application.result.LearningArticleResult;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /learning/articles/{articleId}} 응답의 {@code data}(learning-public-api §2).
 *
 * <p>키 생략은 필드마다 정한다. 전역 {@code NON_NULL}을 쓰지 않는다(backend/CLAUDE.md). 블록은 종류에 해당하지 않는 키를, 출처는 없는
 * {@code url}을 생략하고, {@code example.unavailableReason}과 {@code sectors[].name}은 명시적 null로 싣는다.
 */
public record LearningArticleResponse(
        String articleId,
        String title,
        String question,
        int readingOrder,
        List<Block> blocks,
        Example example,
        List<Source> sources,
        Instant publishedAt) {

    public static LearningArticleResponse from(LearningArticleResult result) {
        LearningArticleResult.Example example = result.example();
        return new LearningArticleResponse(
                result.articleId(),
                result.title(),
                result.question(),
                result.readingOrder(),
                result.blocks().stream()
                        .map(
                                block ->
                                        new Block(
                                                block.type(),
                                                block.text(),
                                                block.termId(),
                                                block.label(),
                                                block.sentence()))
                        .toList(),
                new Example(
                        example.available(),
                        example.sectors().stream()
                                .map(
                                        sector ->
                                                new ExampleSector(
                                                        sector.code(),
                                                        sector.name(),
                                                        sector.weightTenths()))
                                .toList(),
                        example.missingSectorCodes(),
                        example.unavailableReason() == null
                                ? null
                                : example.unavailableReason().name()),
                result.sources().stream()
                        .map(source -> new Source(source.label(), source.url()))
                        .toList(),
                result.publishedAt());
    }

    /** 본문 블록. 종류에 해당하지 않는 키는 싣지 않는다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Block(String type, String text, String termId, String label, String sentence) {}

    /** {@code unavailableReason}은 {@code available=true}여도 키를 싣고 값을 null로 둔다. */
    public record Example(
            boolean available,
            List<ExampleSector> sectors,
            List<String> missingSectorCodes,
            String unavailableReason) {}

    /** {@code name}은 대응표에 없는 코드면 null로 싣는다. */
    public record ExampleSector(String code, String name, int weightTenths) {}

    /** {@code url}이 없으면 키를 싣지 않는다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String label, String url) {}
}
