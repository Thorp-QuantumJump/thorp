package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 초안 용어 설명({@code learning_term}). 초안 작업본이며 게시 기록이 아니다.
 *
 * <p><b>공개 응답을 만드는 경로에서 쓰지 않는다.</b> 공개 문장은 판과 게시 용어 색인에서만 읽는다(SC-006).
 */
public interface LearningTermRepository {

    /** 이 {@code termId}를 쓰고 있는 글. {@code termId}는 모든 글의 초안 용어에서 유일하다. */
    Optional<ArticleId> findOwnerArticleKey(TermId termId);

    /** 모든 글의 초안 용어 {@code termId}. 언급 조각이 가리킬 수 있는 용어를 판정할 때 쓴다({@code TERM_MENTION_KNOWN}). */
    Set<TermId> findAllTermIds();

    /** 이 글의 초안 용어를 통째로 바꾼다. 목록에서 빠진 용어는 행을 지운다(data-model §3-3). */
    void replaceForArticle(ArticleId articleId, List<LearningTerm> terms);
}
