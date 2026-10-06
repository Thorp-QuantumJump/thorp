package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.domain.model.PublicationEvent;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.util.Map;
import java.util.Optional;

/**
 * 게시·게시 취소 기록({@code learning_publication_event}).
 *
 * <p><b>추가와 마지막 기록 조회만 있다.</b> 기록을 고치거나 지우는 메서드를 두지 않는다(FR-018, 원칙 VIII).
 */
public interface PublicationEventRepository {

    PublicationEvent append(PublicationEvent event);

    /** 이 글의 마지막 기록. 기록이 없으면 비어 있다. */
    Optional<PublicationEvent> findLastByArticleId(ArticleId articleId);

    /** 글마다 마지막 기록. 기록이 없는 글은 결과에 없다. */
    Map<ArticleId, PublicationEvent> findLastForEachArticle();
}
