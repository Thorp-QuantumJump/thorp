package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import com.ssafy.thorp.news.domain.model.NewsRuleVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code news_rule_version} 매핑. 정규화·연결·묶음·정렬 규칙의 버전이다.
 *
 * <p>규칙이 바뀌면 기존 행을 고치지 않고 새 버전을 더한다. 저장된 기사와 묶음이 자기가 쓴 버전을 참조하므로 같은 버전으로 다시 실행하면 같은 결과가 나온다(원칙
 * VIII).
 *
 * <p>{@code definition}은 임계값과 정렬 순서처럼 재현에 필요한 값만 담는다. 기사 본문이나 외부 응답 원문을 담지 않는다.
 */
@Entity
@Table(name = "news_rule_version")
public class NewsRuleVersionJpaEntity extends BaseEntity {

    @Id
    @Column(length = 64)
    private String ruleVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NewsRuleVersion.RuleType ruleType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> definition;

    @Column(nullable = false)
    private Instant effectiveFrom;

    private Instant retiredAt;

    protected NewsRuleVersionJpaEntity() {}

    NewsRuleVersionJpaEntity(
            String ruleVersion,
            NewsRuleVersion.RuleType ruleType,
            Map<String, Object> definition,
            Instant effectiveFrom,
            Instant retiredAt) {
        this.ruleVersion = ruleVersion;
        this.ruleType = ruleType;
        this.definition = definition;
        this.effectiveFrom = effectiveFrom;
        this.retiredAt = retiredAt;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public NewsRuleVersion.RuleType getRuleType() {
        return ruleType;
    }

    public Map<String, Object> getDefinition() {
        return definition;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    /** 규칙을 물린다. 정의는 그대로 두어 과거 결과의 근거가 남는다. */
    void retire(Instant retiredAt) {
        this.retiredAt = retiredAt;
    }
}
