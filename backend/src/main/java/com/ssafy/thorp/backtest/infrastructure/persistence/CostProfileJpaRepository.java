package com.ssafy.thorp.backtest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface CostProfileJpaRepository extends JpaRepository<CostProfileJpaEntity, String> {}
