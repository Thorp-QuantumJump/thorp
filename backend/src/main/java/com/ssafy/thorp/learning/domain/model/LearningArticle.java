package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.exception.DraftChangedException;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 글의 초안 작업본(data-model §3-1, §9). 불변 객체이고 전이 메서드는 바뀐 사본을 돌려준다.
 *
 * <p>초안은 쓰는 도중의 값을 담는다. 제목·질문이 비어 있거나 예시가 없어도 만들 수 있다. 게시해도 되는 글인지는 게시 전 검사가 가른다.
 *
 * <p><b>불변식</b>: {@code status == PUBLISHED}이면 {@code publishedRevisionId}가 있고, {@code DRAFT}이면 없다.
 * 같은 관계를 테이블 CHECK 제약도 건다.
 *
 * @param id DB 대리키. 저장 전이면 null이며 응답에 내보내지 않는다
 * @param example 초안에서는 null일 수 있다
 * @param publishedRevisionId 공개 중인 판의 대리키
 * @param draftVersion 초안을 저장할 때마다 1 오른다(research R12)
 */
public record LearningArticle(
        Long id,
        ArticleId articleId,
        String title,
        String question,
        int readingOrder,
        List<ArticleBlock> blocks,
        ExampleAllocation example,
        List<ArticleSource> sources,
        ArticleStatus status,
        Long publishedRevisionId,
        int draftVersion,
        List<LearningTerm> terms) {

    public static final int TITLE_MAX_LENGTH = 100;
    public static final int QUESTION_MAX_LENGTH = 200;
    public static final int MIN_READING_ORDER = 1;
    public static final int MAX_READING_ORDER = 999;

    public LearningArticle {
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (title.length() > TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "title must be at most " + TITLE_MAX_LENGTH + " characters");
        }
        if (question.length() > QUESTION_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "question must be at most " + QUESTION_MAX_LENGTH + " characters");
        }
        if (readingOrder < MIN_READING_ORDER || readingOrder > MAX_READING_ORDER) {
            throw new IllegalArgumentException(
                    "readingOrder must be within "
                            + MIN_READING_ORDER
                            + ".."
                            + MAX_READING_ORDER
                            + ": "
                            + readingOrder);
        }
        blocks = List.copyOf(blocks);
        if (blocks.size() > ArticleBlock.MAX_BLOCKS) {
            throw new IllegalArgumentException(
                    "an article must have at most "
                            + ArticleBlock.MAX_BLOCKS
                            + " blocks: "
                            + blocks.size());
        }
        sources = List.copyOf(sources);
        terms = List.copyOf(terms);
        Set<TermId> termIds = new HashSet<>();
        for (LearningTerm term : terms) {
            if (!termIds.add(term.termId())) {
                throw new IllegalArgumentException("duplicate termId in article: " + term.termId());
            }
        }
        if (draftVersion < 0) {
            throw new IllegalArgumentException("draftVersion must be >= 0: " + draftVersion);
        }
        if ((status == ArticleStatus.PUBLISHED) != (publishedRevisionId != null)) {
            throw new IllegalArgumentException(
                    "status and publishedRevisionId disagree: status="
                            + status
                            + ", publishedRevisionId="
                            + publishedRevisionId);
        }
    }

    /**
     * 초안을 통째로 바꾼다. 공개 내용(판·색인)은 바뀌지 않는다.
     *
     * @throws DraftChangedException 읽은 뒤 다른 곳에서 초안이 저장됐을 때. 덮어쓰지 않는다
     */
    public LearningArticle saveDraft(
            int expectedDraftVersion,
            String newTitle,
            String newQuestion,
            int newReadingOrder,
            List<ArticleBlock> newBlocks,
            ExampleAllocation newExample,
            List<ArticleSource> newSources,
            List<LearningTerm> newTerms) {
        requireDraftVersion(expectedDraftVersion);
        return new LearningArticle(
                id,
                articleId,
                newTitle,
                newQuestion,
                newReadingOrder,
                newBlocks,
                newExample,
                newSources,
                status,
                publishedRevisionId,
                draftVersion + 1,
                newTerms);
    }

    /**
     * 새 판을 공개 중인 판으로 삼는다. 게시 전 검사는 호출자가 먼저 통과시킨다.
     *
     * @throws DraftChangedException 운영자가 보지 않은 초안을 게시하지 않는다
     */
    public LearningArticle publish(int expectedDraftVersion, long revisionId) {
        requireDraftVersion(expectedDraftVersion);
        return withPublication(ArticleStatus.PUBLISHED, revisionId);
    }

    /** 공개를 내린다. 이미 초안이면 아무것도 바꾸지 않고 자신을 돌려준다(data-model §7). */
    public LearningArticle unpublish() {
        if (status == ArticleStatus.DRAFT) {
            return this;
        }
        return withPublication(ArticleStatus.DRAFT, null);
    }

    /** 초안 판 번호가 기대한 값인지. */
    public boolean isDraftVersion(int expectedDraftVersion) {
        return draftVersion == expectedDraftVersion;
    }

    private void requireDraftVersion(int expectedDraftVersion) {
        if (!isDraftVersion(expectedDraftVersion)) {
            throw new DraftChangedException(draftVersion);
        }
    }

    private LearningArticle withPublication(ArticleStatus newStatus, Long revisionId) {
        return new LearningArticle(
                id,
                articleId,
                title,
                question,
                readingOrder,
                blocks,
                example,
                sources,
                newStatus,
                revisionId,
                draftVersion,
                terms);
    }
}
