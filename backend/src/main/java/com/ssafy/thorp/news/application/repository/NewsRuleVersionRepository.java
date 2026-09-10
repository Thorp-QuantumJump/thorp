package com.ssafy.thorp.news.application.repository;

import com.ssafy.thorp.news.domain.model.NewsRuleVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 규칙 버전 저장소 경계(원칙 VIII). 규칙 변경은 덮어쓰지 않고 새 버전을 더한다. */
public interface NewsRuleVersionRepository {

    NewsRuleVersion save(NewsRuleVersion ruleVersion);

    Optional<NewsRuleVersion> findByRuleVersion(String ruleVersion);

    /** 그 시점에 유효한 규칙. 종류마다 하나다. */
    Optional<NewsRuleVersion> findEffective(NewsRuleVersion.RuleType ruleType, Instant at);

    List<NewsRuleVersion> findAll();
}
