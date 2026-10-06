package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.application.result.LearningTermResult;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * 여러 용어의 다른 이름. 다른 글이 소유한 용어를 언급할 때 처음 나오는 자리에 붙인다(data-model §5-4, research R18). 공개 글 응답과 미리
     * 보기가 한 번에 읽는다.
     *
     * @return 게시된 용어만 담는다. 게시되지 않은 용어는 결과에 없다
     */
    Map<TermId, List<String>> findPublishedAliases(Collection<TermId> termIds);

    /**
     * 이 용어들이 지금 게시 색인에 올라 있는 글(용어 → 글). 초안에서 뺀 용어도 그 글을 다시 게시하거나 게시 취소하기 전까지는 여기 남는다. 색인에 없는 용어는
     * 결과에 없다. 한 문장으로 읽는다.
     */
    Map<TermId, ArticleId> findOwnerArticleKeys(Collection<TermId> termIds);

    /** 색인에 올라 있는 모든 {@code termId}. 언급 조각이 가리킬 수 있는 용어를 판정할 때 쓴다({@code TERM_MENTION_KNOWN}). */
    Set<TermId> findAllTermIds();

    /**
     * 이 글의 색인 행을 모두 지우고 새 판의 용어로 다시 채운다. 다른 글의 행과 {@code termId}가 겹치면 덮어쓰지 않고 실패한다. 호출자가 먼저 {@link
     * #findOwnerArticleKeys}로 막는다.
     *
     * @param format 새 판의 형식. 도움말 문장은 V1이면 설명 문장, V2면 뜻 문장이다(data-model §3-6)
     */
    void replaceForArticle(
            ArticleId articleId, long revisionId, List<LearningTerm> terms, ArticleFormat format);

    /** 이 글의 색인 행을 모두 지운다(게시 취소). */
    void deleteForArticle(ArticleId articleId);

    /**
     * 이 글의 색인 행 하나만 지운다. 넘기기가 옛 소유 글의 그 용어 행을 지울 때 쓴다(data-model §3-6, research R24). 행이 없으면 아무것도
     * 하지 않는다.
     */
    void deleteTerm(ArticleId articleId, TermId termId);

    /**
     * 글마다 공개 용어 수(커리큘럼의 {@code termCount}). {@link #findPublished}처럼 글의 공개 판과 색인 행의 판이 같은 행만 센다. 공개
     * 용어가 없는 글은 결과에 없다.
     */
    Map<ArticleId, Integer> countPublishedByArticle();
}
