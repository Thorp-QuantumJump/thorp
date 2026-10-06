package com.ssafy.thorp.learning.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** 변경 기록은 추가·조회만 한다. {@code JpaRepository}를 상속하지 않는 이유는 {@code delete*}가 딸려 오기 때문이다(판 저장소와 같다). */
public interface LearningReleaseLogJpaRepository
        extends Repository<LearningReleaseLogJpaEntity, Long> {

    LearningReleaseLogJpaEntity saveAndFlush(LearningReleaseLogJpaEntity entity);

    /** 모든 줄과 그 단계·글의 키. 최신 순서(시각이 같으면 나중에 더한 줄이 먼저)다. */
    @Query(
            "select new com.ssafy.thorp.learning.infrastructure.persistence.KeyedReleaseLogRow("
                    + "s.stageKey, a.articleKey, l)"
                    + " from LearningReleaseLogJpaEntity l"
                    + " join LearningStageJpaEntity s on s.id = l.stageId"
                    + " left join LearningArticleJpaEntity a on a.id = l.articleId"
                    + " order by l.occurredAt desc, l.id desc")
    List<KeyedReleaseLogRow> findAllKeyedLatestFirst();
}
