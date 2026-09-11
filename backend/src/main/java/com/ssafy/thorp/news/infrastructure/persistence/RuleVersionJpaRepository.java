package com.ssafy.thorp.news.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 규칙 버전. */
public interface RuleVersionJpaRepository extends JpaRepository<NewsRuleVersionJpaEntity, String> {

    @Query(
            "SELECT r FROM NewsRuleVersionJpaEntity r"
                    + " WHERE r.ruleType = :ruleType"
                    + " AND r.effectiveFrom <= :at"
                    + " AND (r.retiredAt IS NULL OR r.retiredAt > :at)"
                    + " ORDER BY r.effectiveFrom DESC")
    List<NewsRuleVersionJpaEntity> findEffective(
            @Param("ruleType") com.ssafy.thorp.news.domain.model.NewsRuleVersion.RuleType ruleType,
            @Param("at") Instant at);
}
