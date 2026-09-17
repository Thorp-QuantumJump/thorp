package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.util.List;
import java.util.Optional;

/**
 * 불변 게시 판({@code learning_article_revision}).
 *
 * <p><b>추가와 조회만 있다. 수정·삭제 메서드를 두지 않는다</b>(data-model §3-3, 원칙 VIII). 판을 고칠 수 있으면 "그때 공개한 내용"을 나중
 * 값으로 다시 쓰게 된다.
 */
public interface ArticleRevisionRepository {

    /**
     * 새 판을 추가한다. 판이 속한 글은 {@code snapshot.articleId}로 찾는다.
     *
     * @return 대리키가 채워진 판
     */
    ArticleRevision save(ArticleRevision revision);

    Optional<ArticleRevision> findById(long revisionId);

    /** 이 글의 판 전부. {@code revision_no} 오름차순. */
    List<ArticleRevision> findByArticleId(ArticleId articleId);
}
