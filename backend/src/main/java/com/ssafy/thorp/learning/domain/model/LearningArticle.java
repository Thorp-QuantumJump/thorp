package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.exception.DraftChangedException;
import com.ssafy.thorp.learning.domain.exception.FormatOutdatedException;
import com.ssafy.thorp.learning.domain.exception.InvalidDraftException;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 글의 초안 작업본(data-model §3-2, §10-1). 불변 객체이고 전이 메서드는 바뀐 사본을 돌려준다.
 *
 * <p>초안은 쓰는 도중의 값을 담는다. 제목·질문이 비어 있거나 예시가 없어도 만들 수 있다. 게시해도 되는 글인지는 게시 전 검사가 가른다.
 *
 * <p><b>형식별 모양</b>(research R16): V1 초안은 9월 판 블록 네 종류(문단·가상의 계산 예는 글자 조각 하나), 설명 문장 하나짜리 용어, 출처 목록만
 * 담고 확인 문제가 없다. V2 초안은 모든 블록과 세 문장 용어, 확인 문제를 담고 출처 목록은 비어 있다(출처는 글자 조각이다).
 *
 * <p><b>불변식</b>: {@code status == PUBLISHED}이면 {@code publishedRevisionId}가 있고, {@code DRAFT}이면 없다.
 * 공개 배치는 공개 중인 판과 함께 있거나 함께 없다. 같은 관계를 테이블 CHECK 제약도 건다.
 *
 * @param id DB 대리키. 저장 전이면 null이며 응답에 내보내지 않는다
 * @param readingOrder 첫 단계 공개 전 목록의 순서. V2 글에서는 쓰지 않는다(R15)
 * @param placement 초안 배치. 다시 게시해야 공개된다(FR-023)
 * @param publishedPlacement 공개 배치. 게시가 초안 배치를 옮겨 적고 게시 취소가 비운다
 * @param example 초안에서는 null일 수 있다
 * @param publishedRevisionId 공개 중인 판의 대리키
 * @param draftVersion 초안을 저장할 때마다 1 오른다(research R12)
 * @param quiz 확인 문제 초안. 식별자가 모두 채워져 있다
 * @param quizSeq 문제·보기 식별자 카운터. 줄지 않는다(R21)
 */
public record LearningArticle(
        Long id,
        ArticleId articleId,
        String title,
        String question,
        int readingOrder,
        ArticleFormat format,
        Placement placement,
        Placement publishedPlacement,
        List<ArticleBlock> blocks,
        ExampleAllocation example,
        List<ArticleSource> sources,
        ArticleStatus status,
        Long publishedRevisionId,
        int draftVersion,
        List<LearningTerm> terms,
        List<QuizQuestion> quiz,
        int quizSeq) {

    public static final int TITLE_MAX_LENGTH = 100;
    public static final int QUESTION_MAX_LENGTH = 200;
    public static final int MIN_READING_ORDER = 1;
    public static final int MAX_READING_ORDER = 999;

    public LearningArticle {
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(placement, "placement must not be null");
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
        int maxBlocks = ArticleBlock.maxBlocks(format);
        if (blocks.size() > maxBlocks) {
            throw new IllegalArgumentException(
                    "a "
                            + format
                            + " article must have at most "
                            + maxBlocks
                            + " blocks: "
                            + blocks.size());
        }
        for (ArticleBlock block : blocks) {
            if (!ArticleBlock.allowedIn(block, format)) {
                throw new IllegalArgumentException(
                        "a " + format + " article cannot hold " + block.getClass().getSimpleName());
            }
        }
        sources = List.copyOf(sources);
        if (format == ArticleFormat.V2 && !sources.isEmpty()) {
            throw new IllegalArgumentException("a V2 article keeps sources as runs, not a list");
        }
        terms = List.copyOf(terms);
        Set<TermId> termIds = new HashSet<>();
        for (LearningTerm term : terms) {
            if (!termIds.add(term.termId())) {
                throw new IllegalArgumentException("duplicate termId in article: " + term.termId());
            }
            if (format == ArticleFormat.V1 ? !term.fitsV1() : !term.fitsV2()) {
                throw new IllegalArgumentException(
                        "term does not fit a " + format + " article: " + term.termId());
            }
        }
        quiz = List.copyOf(quiz);
        requireQuiz(format, quiz);
        if (quizSeq < 0) {
            throw new IllegalArgumentException("quizSeq must be >= 0: " + quizSeq);
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
        if ((publishedPlacement == null) != (publishedRevisionId == null)) {
            throw new IllegalArgumentException(
                    "publishedPlacement and publishedRevisionId must be present together");
        }
    }

    /**
     * 관리 화면이 만드는 새 글. 본문이 빈 V2 초안이다(learning-admin-api §3). V1 초안은 기존 아홉 글에만 있다.
     *
     * @param readingOrder V2 글에서는 쓰지 않지만 테이블 CHECK(1..999)를 지키는 값. 호출자가 단계 번호로 정한다
     */
    public static LearningArticle newDraft(
            ArticleId articleId, String title, int readingOrder, Placement placement) {
        return new LearningArticle(
                null,
                articleId,
                title,
                "",
                readingOrder,
                ArticleFormat.V2,
                placement,
                null,
                List.of(),
                null,
                List.of(),
                ArticleStatus.DRAFT,
                null,
                0,
                List.of(),
                List.of(),
                0);
    }

    /** 저장소가 새 행에 준 대리키를 채운다. 그 밖의 값은 그대로다. */
    public LearningArticle withId(long newId) {
        return new LearningArticle(
                newId,
                articleId,
                title,
                question,
                readingOrder,
                format,
                placement,
                publishedPlacement,
                blocks,
                example,
                sources,
                status,
                publishedRevisionId,
                draftVersion,
                terms,
                quiz,
                quizSeq);
    }

    /**
     * 저장 요청이 담는 초안 내용. 생략된 필드는 호출자가 지금 초안의 값으로 채워 넘긴다.
     *
     * @param quiz 요청의 문제. 새 문제·보기는 식별자가 null이다
     */
    public record DraftContent(
            String title,
            String question,
            int readingOrder,
            ArticleFormat format,
            Placement placement,
            List<ArticleBlock> blocks,
            ExampleAllocation example,
            List<ArticleSource> sources,
            List<LearningTerm> terms,
            List<QuizQuestion> quiz) {

        public DraftContent {
            blocks = List.copyOf(blocks);
            sources = List.copyOf(sources);
            terms = List.copyOf(terms);
            quiz = List.copyOf(quiz);
        }
    }

    /**
     * 초안을 통째로 바꾼다. 공개 내용(판·색인·공개 배치)은 바뀌지 않는다. 새 문제·보기에 식별자를 준다(data-model §6-2).
     *
     * @param hasLegacyRevision 이 글에 LEGACY 판이 있는가. V2에서 V1로 되돌리는 저장은 있을 때만 받는다(R16)
     * @throws DraftChangedException 읽은 뒤 다른 곳에서 초안이 저장됐을 때. 덮어쓰지 않는다
     * @throws FormatOutdatedException LEGACY 판이 없는 글을 V1로 되돌리려 했다
     * @throws InvalidDraftException 지금 초안에 없는 문제·보기 식별자
     */
    public LearningArticle saveDraft(
            int expectedDraftVersion, DraftContent content, boolean hasLegacyRevision) {
        requireDraftVersion(expectedDraftVersion);
        if (format == ArticleFormat.V2
                && content.format() == ArticleFormat.V1
                && !hasLegacyRevision) {
            throw new FormatOutdatedException();
        }
        QuizIdentity.Assigned assigned = QuizIdentity.assign(content.quiz(), quiz, quizSeq);
        return new LearningArticle(
                id,
                articleId,
                content.title(),
                content.question(),
                content.readingOrder(),
                content.format(),
                content.placement(),
                publishedPlacement,
                content.blocks(),
                content.example(),
                content.sources(),
                status,
                publishedRevisionId,
                draftVersion + 1,
                content.terms(),
                assigned.quiz(),
                assigned.quizSeq());
    }

    /**
     * 같은 문제 판정(data-model §6-3)으로 새 식별자를 받은 문제를 초안에도 반영한다. 질문·보기·풀이 글자는 그대로이고 <b>초안 번호를 올리지
     * 않는다</b>. 그래서 게시 전에 읽은 초안에는 옛 식별자가 남고, 그대로 저장하면 지금 초안에 없는 식별자라 거절된다.
     */
    public LearningArticle withReconciledQuiz(QuizIdentity.Reconciled reconciled) {
        return new LearningArticle(
                id,
                articleId,
                title,
                question,
                readingOrder,
                format,
                placement,
                publishedPlacement,
                blocks,
                example,
                sources,
                status,
                publishedRevisionId,
                draftVersion,
                terms,
                reconciled.quiz(),
                reconciled.quizSeq());
    }

    /**
     * 새 판을 공개 중인 판으로 삼고 초안 배치를 공개 배치로 옮긴다. 게시 전 검사는 호출자가 먼저 통과시킨다.
     *
     * @throws DraftChangedException 운영자가 보지 않은 초안을 게시하지 않는다
     */
    public LearningArticle publish(int expectedDraftVersion, long revisionId) {
        requireDraftVersion(expectedDraftVersion);
        return withPublication(ArticleStatus.PUBLISHED, revisionId, placement);
    }

    /** 공개를 내리고 공개 배치를 비운다. 이미 초안이면 아무것도 바꾸지 않고 자신을 돌려준다(data-model §10-1). */
    public LearningArticle unpublish() {
        if (status == ArticleStatus.DRAFT) {
            return this;
        }
        return withPublication(ArticleStatus.DRAFT, null, null);
    }

    /** 초안 판 번호가 기대한 값인가. */
    public boolean isDraftVersion(int expectedDraftVersion) {
        return draftVersion == expectedDraftVersion;
    }

    private void requireDraftVersion(int expectedDraftVersion) {
        if (!isDraftVersion(expectedDraftVersion)) {
            throw new DraftChangedException(draftVersion);
        }
    }

    private LearningArticle withPublication(
            ArticleStatus newStatus, Long revisionId, Placement newPublishedPlacement) {
        return new LearningArticle(
                id,
                articleId,
                title,
                question,
                readingOrder,
                format,
                placement,
                newPublishedPlacement,
                blocks,
                example,
                sources,
                newStatus,
                revisionId,
                draftVersion,
                terms,
                quiz,
                quizSeq);
    }

    /** V1 초안에는 확인 문제가 없다. 저장된 초안의 문제·보기는 식별자가 모두 있고 한 글 안에서 겹치지 않는다. */
    private static void requireQuiz(ArticleFormat format, List<QuizQuestion> quiz) {
        if (format == ArticleFormat.V1 && !quiz.isEmpty()) {
            throw new IllegalArgumentException("a V1 article cannot hold quiz questions");
        }
        if (quiz.size() > QuizQuestion.MAX_QUESTIONS) {
            throw new IllegalArgumentException(
                    "an article must have at most "
                            + QuizQuestion.MAX_QUESTIONS
                            + " questions: "
                            + quiz.size());
        }
        Set<QuestionId> questionIds = new HashSet<>();
        Set<ChoiceId> choiceIds = new HashSet<>();
        for (QuizQuestion question : quiz) {
            if (question.questionId() == null || !questionIds.add(question.questionId())) {
                throw new IllegalArgumentException(
                        "stored questions must have unique ids: " + question.questionId());
            }
            for (QuizChoice choice : question.choices()) {
                if (choice.choiceId() == null || !choiceIds.add(choice.choiceId())) {
                    throw new IllegalArgumentException(
                            "stored choices must have unique ids: " + choice.choiceId());
                }
            }
        }
    }
}
