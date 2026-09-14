package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.RulesStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code strategy_rules} 테이블 매핑. 마이그레이션이 채우는 읽기 전용 등록표라 생성자·수정 메서드를 두지 않는다.
 *
 * <p>PK가 {@code (strategy_id, rules_version)} 복합키라 {@link IdClass}를 쓴다. 같은 전략의 규칙이 버전별로 나란히 남아야 과거
 * 실행의 결과를 그때 규칙으로 읽을 수 있다.
 */
@Entity
@Table(name = "strategy_rules")
@IdClass(StrategyRulesJpaEntity.Key.class)
public class StrategyRulesJpaEntity {

    @Id
    @Column(length = 32)
    private String strategyId;

    @Id
    @Column(length = 32)
    private String rulesVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RulesStatus status;

    @Column(nullable = false)
    private boolean provisional;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> spec;

    @Column(columnDefinition = "text")
    private String decisionRef;

    protected StrategyRulesJpaEntity() {}

    public String getStrategyId() {
        return strategyId;
    }

    public String getRulesVersion() {
        return rulesVersion;
    }

    public RulesStatus getStatus() {
        return status;
    }

    public boolean isProvisional() {
        return provisional;
    }

    public Map<String, Object> getSpec() {
        return spec;
    }

    public String getDecisionRef() {
        return decisionRef;
    }

    /** 복합 PK. JPA가 요구하는 기본 생성자와 값 동등성을 갖춘다. */
    public static class Key implements Serializable {

        private String strategyId;
        private String rulesVersion;

        public Key() {}

        public Key(String strategyId, String rulesVersion) {
            this.strategyId = strategyId;
            this.rulesVersion = rulesVersion;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(strategyId, key.strategyId)
                    && Objects.equals(rulesVersion, key.rulesVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(strategyId, rulesVersion);
        }
    }
}
