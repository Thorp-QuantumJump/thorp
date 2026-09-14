package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code backtest_reuse_event} 테이블 매핑.
 *
 * <p>{@link com.ssafy.thorp.common.persistence.BaseEntity}를 상속하지 않는다 — append-only 기록이라 {@code
 * updated_at}이 영원히 {@code created_at}과 같고, 발생 시각은 {@code at} 하나면 된다.
 *
 * <p>실행·요청은 스칼라 id로만 참조한다. 연관 매핑을 걸면 기록 한 줄을 읽을 때 5년치 시계열을 가진 결과까지 딸려온다.
 */
@Entity
@Table(name = "backtest_reuse_event")
public class BacktestReuseEventJpaEntity {

    @Id
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "backtest_reuse_event_seq_generator")
    @SequenceGenerator(
            name = "backtest_reuse_event_seq_generator",
            sequenceName = "backtest_reuse_event_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false)
    private Long requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ResultSource kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionType requestExecutionType;

    @Column(name = "at", nullable = false)
    private Instant at;

    protected BacktestReuseEventJpaEntity() {}

    BacktestReuseEventJpaEntity(
            Long id,
            Long runId,
            Long requestId,
            ResultSource kind,
            ExecutionType requestExecutionType,
            Instant at) {
        this.id = id;
        this.runId = runId;
        this.requestId = requestId;
        this.kind = kind;
        this.requestExecutionType = requestExecutionType;
        this.at = at;
    }

    Long getId() {
        return id;
    }

    Long getRunId() {
        return runId;
    }

    Long getRequestId() {
        return requestId;
    }

    ResultSource getKind() {
        return kind;
    }

    ExecutionType getRequestExecutionType() {
        return requestExecutionType;
    }

    Instant getAt() {
        return at;
    }
}
