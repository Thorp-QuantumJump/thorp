package com.ssafy.thorp.learning.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.learning.application.result.AdminArticleResult;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import java.util.List;

/**
 * 관리 초안 하나의 {@code data}(learning-admin-api §4, 예시 {@code admin-article.json}). 만들기(201)·저장(200)
 * 응답도 같다.
 *
 * <p>초안의 {@code term} 블록은 {@code termId}만 담는다. 문장은 {@code terms}에 있다. {@code example}과 {@code
 * publishedRevisionNo}는 없으면 명시적 null이다.
 */
public record AdminArticleResponse(
        String articleId,
        String status,
        int draftVersion,
        Draft draft,
        Integer publishedRevisionNo) {

    public static AdminArticleResponse from(AdminArticleResult result) {
        LearningArticle article = result.article();
        return new AdminArticleResponse(
                article.articleId().value(),
                article.status().name(),
                article.draftVersion(),
                new Draft(
                        article.title(),
                        article.question(),
                        article.readingOrder(),
                        article.blocks().stream()
                                .map(
                                        block ->
                                                switch (block) {
                                                    case HeadingBlock heading ->
                                                            new Block(
                                                                    "heading",
                                                                    heading.text(),
                                                                    null);
                                                    case ParagraphBlock paragraph ->
                                                            new Block(
                                                                    "paragraph",
                                                                    paragraph.text(),
                                                                    null);
                                                    case TermBlock term ->
                                                            new Block(
                                                                    "term",
                                                                    null,
                                                                    term.termId().value());
                                                    case HypotheticalBlock hypothetical ->
                                                            new Block(
                                                                    "hypothetical",
                                                                    hypothetical.text(),
                                                                    null);
                                                })
                                .toList(),
                        article.example() == null
                                ? null
                                : new Example(
                                        article.example().sectors().stream()
                                                .map(
                                                        sector ->
                                                                new Sector(
                                                                        sector.code(),
                                                                        sector.weightTenths()))
                                                .toList()),
                        article.sources().stream()
                                .map(source -> new Source(source.label(), source.url()))
                                .toList(),
                        article.terms().stream()
                                .map(
                                        term ->
                                                new Term(
                                                        term.termId().value(),
                                                        term.label(),
                                                        term.sentence()))
                                .toList()),
                result.publishedRevisionNo());
    }

    public record Draft(
            String title,
            String question,
            int readingOrder,
            List<Block> blocks,
            Example example,
            List<Source> sources,
            List<Term> terms) {}

    /** 종류에 해당하지 않는 키는 싣지 않는다. 저장 요청의 블록과 같은 모양이다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Block(String type, String text, String termId) {}

    public record Example(List<Sector> sectors) {}

    public record Sector(String code, int weightTenths) {}

    /** {@code url}이 없으면 키를 싣지 않는다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String label, String url) {}

    public record Term(String termId, String label, String sentence) {}
}
