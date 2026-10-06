package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 초안 용어 설명({@code learning_term}). 초안 작업본이며 게시 기록이 아니다.
 *
 * <p><b>공개 응답을 만드는 경로에서 쓰지 않는다.</b> 공개 문장은 판과 게시 용어 색인에서만 읽는다(SC-006).
 *
 * <p>행은 소유 중인 행과 넘겨받는 중인 행({@code transferFrom}이 있음)으로 나뉜다. {@code termId}마다 소유 중인 행 하나, 넘겨받는 중인 행
 * 하나까지 있을 수 있다(data-model §3-3, research R24).
 */
public interface LearningTermRepository {

    /** 이 용어들을 소유 중인 글(용어 → 글). 넘겨받는 중인 행은 보지 않는다. 소유 글이 없는 용어는 결과에 없다. 한 문장으로 읽는다. */
    Map<TermId, ArticleId> findOwnerArticleKeys(Collection<TermId> termIds);

    /** 이 용어들을 넘겨받는 중인 글(용어 → 글). 넘겨받는 글이 없는 용어는 결과에 없다. 한 문장으로 읽는다. */
    Map<TermId, ArticleId> findIncomingArticleKeys(Collection<TermId> termIds);

    /** 모든 글의 초안 용어 {@code termId}. 언급 조각이 가리킬 수 있는 용어를 판정할 때 쓴다({@code TERM_MENTION_KNOWN}). */
    Set<TermId> findAllTermIds();

    /**
     * 이 글의 초안 용어를 통째로 바꾼다. 목록에서 빠진 용어는 행을 지운다(data-model §3-3). 용어의 {@code transferFrom}도 그대로 저장한다.
     */
    void replaceForArticle(ArticleId articleId, List<LearningTerm> terms);

    /** 넘기기: 옛 소유 글의 초안에서 그 용어 행을 지운다. 그 글에 행이 없으면 아무것도 하지 않는다. */
    void deleteOwnedTerm(ArticleId ownerArticleId, TermId termId);

    /** 넘기기: 이 글의 넘겨받는 중인 행을 모두 소유 중인 행으로 바꾼다. 옛 소유 글의 행을 먼저 지운 뒤 부른다. */
    void completeTransfers(ArticleId articleId);
}
