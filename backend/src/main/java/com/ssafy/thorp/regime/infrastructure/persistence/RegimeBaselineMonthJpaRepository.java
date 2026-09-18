package com.ssafy.thorp.regime.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface RegimeBaselineMonthJpaRepository
        extends JpaRepository<RegimeBaselineMonthJpaEntity, String> {}
