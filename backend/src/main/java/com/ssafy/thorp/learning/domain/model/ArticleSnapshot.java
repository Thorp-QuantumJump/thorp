package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 판에 담기는 내용(data-model §4). 게시하는 순간의 글과 그 글의 용어 설명·확인 문제 전체다.
 *
 * <p>공개 응답은 이 값만으로 만든다. 글의 {@code term} 블록 문장도 초안 {@code learning_term}이 아니라 같은 판의 {@link #terms}에서
 * 채운다. 그래야 게시 뒤 초안을 고쳐도 공개 내용이 바뀌지 않는다(SC-006).
 *
 * <p>단계와 단계 안 순서는 판에 넣지 않는다. 글 행의 공개 배치가 갖는다(research R15).
 *
 * @param format 이 판의 형식. V1 판에는 확인 문제와 {@code reading}이 없다
 * @param example V1 판은 게시 전 검사가 막으므로 늘 있다. V2 판은 예시가 선택이라 없을 수 있다. 판 JSON을 읽는 쪽이 모양을 강제하지 않도록 여기서는
 *     nullable로 둔다
 * @param draftVersion 게시 당시 초안 판 번호. 관리 목록의 {@code hasUnpublishedChanges} 판정 근거다
 * @param quiz V2 판의 확인 문제. 식별자가 모두 있고 정답 보기가 하나다. V1 판은 비어 있다
 * @param reading V2 판을 게시할 때 계산해 넣은 읽기 값(R20). V1 판은 null이다
 */
public record ArticleSnapshot(
        ArticleFormat format,
        ArticleId articleId,
        String title,
        String question,
        int readingOrder,
        List<ArticleBlock> bodyBlocks,
        ExampleAllocation example,
        List<ArticleSource> sources,
        List<LearningTerm> terms,
        int draftVersion,
        List<QuizQuestion> quiz,
        ReadingEstimate reading) {

    public ArticleSnapshot {
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(question, "question must not be null");
        bodyBlocks = List.copyOf(bodyBlocks);
        sources = List.copyOf(sources);
        terms = List.copyOf(terms);
        quiz = List.copyOf(quiz);
        if (format == ArticleFormat.V1 && (!quiz.isEmpty() || reading != null)) {
            throw new IllegalArgumentException("a V1 snapshot has no quiz and no reading");
        }
        if (format == ArticleFormat.V2 && reading == null) {
            throw new IllegalArgumentException("a V2 snapshot must carry its reading estimate");
        }
    }

    /** 9월 판 모양의 판. 확인 문제와 읽기 값이 없다. */
    public static ArticleSnapshot legacy(
            ArticleId articleId,
            String title,
            String question,
            int readingOrder,
            List<ArticleBlock> bodyBlocks,
            ExampleAllocation example,
            List<ArticleSource> sources,
            List<LearningTerm> terms,
            int draftVersion) {
        return new ArticleSnapshot(
                ArticleFormat.V1,
                articleId,
                title,
                question,
                readingOrder,
                bodyBlocks,
                example,
                sources,
                terms,
                draftVersion,
                List.of(),
                null);
    }

    /** 이 판에 들어 있는 용어 설명. */
    public Optional<LearningTerm> term(TermId termId) {
        return terms.stream().filter(term -> term.termId().equals(termId)).findFirst();
    }
}
