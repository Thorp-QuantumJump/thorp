package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.application.result.LearningTermResult;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;
import java.util.Optional;

/**
 * 게시 용어 색인({@code learning_published_term}). 진단 화면 도움말이 읽는 유일한 원천이다.
 *
 * <p>게시·게시 취소는 글 상태 변경과 <b>같은 트랜잭션</b>에서 이 색인을 다시 쓴다. 색인과 판이 한순간이라도 어긋나면 도움말 문장과 글 본문이
 * 갈라진다(SC-003).
 */
public interface PublishedTermRepository {

    /**
     * 공개 중인 판의 용어 설명. {@code articleTitle}은 색인 행이 가리키는 판의 제목이다.
     *
     * @return 게시된 설명이 없으면 비어 있다. 초안뿐인 용어, 게시 취소된 글의 용어, 없는 용어를 가르지 않는다
     */
    Optional<LearningTermResult> findPublished(TermId termId);

    /** 이 글의 색인 행을 모두 지우고 새 판의 용어로 다시 채운다. */
    void replaceForArticle(ArticleId articleId, long revisionId, List<LearningTerm> terms);

    /** 이 글의 색인 행을 모두 지운다(게시 취소). */
    void deleteForArticle(ArticleId articleId);
}
