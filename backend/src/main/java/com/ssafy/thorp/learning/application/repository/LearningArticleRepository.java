package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.application.result.CurrentArticleEntry;
import com.ssafy.thorp.learning.application.result.LearningArticleSummaryResult;
import com.ssafy.thorp.learning.application.result.PublishedArticleView;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.util.List;
import java.util.Optional;

/**
 * 글 초안 작업본({@code learning_article})과 공개 중인 판을 찾는 저장소.
 *
 * <p>읽어 온 {@link LearningArticle#terms()}에는 그 글의 초안 용어가 담긴다. <b>{@link #save}는 용어를 저장하지 않는다.</b> 용어
 * 행은 {@link LearningTermRepository#replaceForArticle}이 통째로 바꾼다. 두 쓰기를 한 메서드에 숨기면 "용어만 바꾸는 저장"과 "글
 * 컬럼만 바꾸는 전이(게시)"가 구분되지 않는다.
 */
public interface LearningArticleRepository {

    Optional<LearningArticle> findByArticleKey(ArticleId articleId);

    /**
     * 행을 잠그고 <b>잠근 시점의 값을 다시 읽는다</b>. 잠금만으로는 영속성 컨텍스트의 1차 캐시가 이겨 그 사이 커밋된 값을 놓친다
     * (backend/CLAUDE.md).
     */
    Optional<LearningArticle> findByArticleKeyForUpdate(ArticleId articleId);

    /** 모든 글. 초안 단계 번호, 단계 안 순서, {@code article_key} 순서다(learning-admin-api §2). */
    List<LearningArticle> findAllOrdered();

    /**
     * 지금 공개 중인 글의 목록 항목. 판 내용의 {@code readingOrder}, {@code articleId} 오름차순이다.
     *
     * <p>순서를 초안 컬럼({@code reading_order})이 아니라 판에서 읽는다. 게시 뒤 초안의 순서만 바꿔도 공개 목록은 바뀌지 않아야 한다(SC-006).
     *
     * <p>본문·용어·예시를 읽지 않는다. 목록에 필요 없는 값을 도메인 객체로 만들면 판 하나의 본문이 깨졌을 때 멀쩡한 글까지 목록에서 사라진다.
     */
    List<LearningArticleSummaryResult> findPublishedOrdered();

    /** 이 글의 공개 중인 판. 초안이거나 없는 글이면 비어 있다. */
    Optional<ArticleRevision> findPublishedRevision(ArticleId articleId);

    /** 이 글의 공개 중인 판과 공개 배치. 초안이거나 없는 글이면 비어 있다. */
    Optional<PublishedArticleView> findPublished(ArticleId articleId);

    /**
     * 새 기준 판({@code CURRENT})이 게시 중인 글. 읽는 순서(공개 배치 단계 번호, 단계 안 순서, {@code articleId})다(data-model
     * §12-2). 본문을 읽지 않는다.
     */
    List<CurrentArticleEntry> findCurrentInReadingOrder();

    /** 새 글이면 추가하고, 있으면 글 컬럼만 바꾼다. 용어는 저장하지 않는다. */
    LearningArticle save(LearningArticle article);

    boolean existsByArticleKey(ArticleId articleId);
}
