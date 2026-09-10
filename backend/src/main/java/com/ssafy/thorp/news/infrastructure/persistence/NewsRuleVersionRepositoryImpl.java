package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.application.repository.NewsRuleVersionRepository;
import com.ssafy.thorp.news.domain.model.NewsRuleVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** {@link NewsRuleVersionRepository} 구현. 규칙은 덮어쓰지 않고 새 버전을 더한다(원칙 VIII). */
@Repository
public class NewsRuleVersionRepositoryImpl implements NewsRuleVersionRepository {

    private final RuleVersionJpaRepository jpaRepository;

    public NewsRuleVersionRepositoryImpl(RuleVersionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public NewsRuleVersion save(NewsRuleVersion ruleVersion) {
        return NewsPersistenceMapper.toDomain(
                jpaRepository.save(NewsPersistenceMapper.toEntity(ruleVersion)));
    }

    @Override
    public Optional<NewsRuleVersion> findByRuleVersion(String ruleVersion) {
        return jpaRepository.findById(ruleVersion).map(NewsPersistenceMapper::toDomain);
    }

    @Override
    public Optional<NewsRuleVersion> findEffective(NewsRuleVersion.RuleType ruleType, Instant at) {
        return jpaRepository.findEffective(ruleType, at).stream()
                .findFirst()
                .map(NewsPersistenceMapper::toDomain);
    }

    @Override
    public List<NewsRuleVersion> findAll() {
        return jpaRepository.findAll().stream().map(NewsPersistenceMapper::toDomain).toList();
    }
}
