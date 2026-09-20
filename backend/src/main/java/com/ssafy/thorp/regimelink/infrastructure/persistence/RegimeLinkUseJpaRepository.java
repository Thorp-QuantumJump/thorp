package com.ssafy.thorp.regimelink.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface RegimeLinkUseJpaRepository extends JpaRepository<RegimeLinkUseJpaEntity, Long> {

    List<RegimeLinkUseJpaEntity> findByLinkSearchId(Long linkSearchId);

    List<RegimeLinkUseJpaEntity> findByLinkId(Long linkId);
}
