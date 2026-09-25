package com.ssafy.thorp.backtest.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code cost_profile} 테이블 매핑. 마이그레이션이 채우는 읽기 전용 등록표라 생성자·수정 메서드를 두지 않는다.
 *
 * <p>{@code created_at}은 매핑하지 않는다. 조회에 쓰지 않고 DB 기본값이 채우므로 엔티티가 알 필요가 없다.
 */
@Entity
@Table(name = "cost_profile")
public class CostProfileJpaEntity {

    @Id
    @Column(length = 32)
    private String version;

    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal commissionBps;

    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal spreadBps;

    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal slippageBps;

    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal taxBps;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> escalation;

    @Column(nullable = false)
    private boolean provisional;

    @Column(columnDefinition = "text")
    private String note;

    @Column(columnDefinition = "text")
    private String taxNote;

    protected CostProfileJpaEntity() {}

    public String getVersion() {
        return version;
    }

    public BigDecimal getCommissionBps() {
        return commissionBps;
    }

    public BigDecimal getSpreadBps() {
        return spreadBps;
    }

    public BigDecimal getSlippageBps() {
        return slippageBps;
    }

    public BigDecimal getTaxBps() {
        return taxBps;
    }

    public Map<String, Object> getEscalation() {
        return escalation;
    }

    public boolean isProvisional() {
        return provisional;
    }

    public String getNote() {
        return note;
    }

    public String getTaxNote() {
        return taxNote;
    }
}
