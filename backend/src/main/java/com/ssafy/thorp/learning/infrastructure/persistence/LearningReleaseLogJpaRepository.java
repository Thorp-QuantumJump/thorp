package com.ssafy.thorp.learning.infrastructure.persistence;

import java.util.List;
import org.springframework.data.repository.Repository;

/** 변경 기록은 추가·조회만 한다. {@code JpaRepository}를 상속하지 않는 이유는 {@code delete*}가 딸려 오기 때문이다(판 저장소와 같다). */
public interface LearningReleaseLogJpaRepository
        extends Repository<LearningReleaseLogJpaEntity, Long> {

    LearningReleaseLogJpaEntity saveAndFlush(LearningReleaseLogJpaEntity entity);

    List<LearningReleaseLogJpaEntity> findAllByOrderByOccurredAtDescIdDesc();
}
