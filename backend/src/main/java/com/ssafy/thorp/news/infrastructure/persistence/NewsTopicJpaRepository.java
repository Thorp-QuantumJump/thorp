package com.ssafy.thorp.news.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 등록된 경제 주제. */
public interface NewsTopicJpaRepository extends JpaRepository<NewsTopicJpaEntity, Long> {

    Optional<NewsTopicJpaEntity> findByTopicKey(String topicKey);
}
