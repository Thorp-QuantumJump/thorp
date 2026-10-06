package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.vo.ArticleId;

/** 외부 식별자({@code articleId}) → 내부 대리키. 저장소 구현끼리 같은 규칙을 쓴다. */
final class PersistenceLookups {

    private PersistenceLookups() {}

    /**
     * @throws IllegalArgumentException 없는 글. 호출하는 유스케이스가 먼저 존재를 확인하므로 여기 오면 프로그래밍 오류다
     */
    static Long requireArticleId(LearningArticleJpaRepository repository, ArticleId articleId) {
        return repository
                .findIdByArticleKey(articleId.value())
                .orElseThrow(() -> new IllegalArgumentException("article not found: " + articleId));
    }
}
