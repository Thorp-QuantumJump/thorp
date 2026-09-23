package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 판에 담기는 내용(data-model §3-6). 게시하는 순간의 글과 그 글의 용어 설명 전체다.
 *
 * <p>공개 응답은 이 값만으로 만든다. 글의 {@code term} 블록 문장도 초안 {@code learning_term}이 아니라 같은 판의 {@link #terms}에서
 * 채운다. 그래야 게시 뒤 초안을 고쳐도 공개 내용이 바뀌지 않는다(SC-006).
 *
 * @param example 게시 전 검사가 막으므로 판에서는 늘 있다. 판 JSON을 읽는 쪽이 모양을 강제하지 않도록 여기서는 nullable로 둔다
 * @param draftVersion 게시 당시 초안 판 번호. 관리 목록의 {@code hasUnpublishedChanges} 판정 근거다
 */
public record ArticleSnapshot(
        ArticleId articleId,
        String title,
        String question,
        int readingOrder,
        List<ArticleBlock> bodyBlocks,
        ExampleAllocation example,
        List<ArticleSource> sources,
        List<LearningTerm> terms,
        int draftVersion) {

    public ArticleSnapshot {
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(question, "question must not be null");
        bodyBlocks = List.copyOf(bodyBlocks);
        sources = List.copyOf(sources);
        terms = List.copyOf(terms);
    }

    /** 이 판에 들어 있는 용어 설명. */
    public Optional<LearningTerm> term(TermId termId) {
        return terms.stream().filter(term -> term.termId().equals(termId)).findFirst();
    }
}
