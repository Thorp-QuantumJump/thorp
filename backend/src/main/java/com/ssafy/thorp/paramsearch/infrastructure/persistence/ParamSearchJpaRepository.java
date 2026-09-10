package com.ssafy.thorp.paramsearch.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ParamSearchJpaRepository extends JpaRepository<ParamSearchJpaEntity, Long> {

    Optional<ParamSearchJpaEntity> findBySearchId(String searchId);
}
