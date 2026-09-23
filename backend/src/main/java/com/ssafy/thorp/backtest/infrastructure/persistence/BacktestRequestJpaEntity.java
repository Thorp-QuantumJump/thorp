package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code backtest_request} 테이블 매핑. 도메인 모델과는 {@link BacktestRequestMapper}로 변환한다.
 *
 * <p>외부 식별자 {@code backtest_id}를 PK에서 만드는 방식은 {@link BacktestRunJpaEntity}와 같다 — SEQUENCE라 {@code
 * persist()} 시점에 PK가 나오고, 그 값으로 INSERT 전에 컬럼을 채운다.
 *
 * <p>실행은 {@code run_id} 스칼라로만 참조한다. 연관 매핑을 걸면 요청 목록을 읽을 때마다 실행이 딸려오고, 실행에는 5년치 시계열을 가진 결과가 연결돼 있다.
 */
@Entity
@Table(name = "backtest_request")
public class BacktestRequestJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "backtest_request_seq_generator")
    @SequenceGenerator(
            name = "backtest_request_seq_generator",
            sequenceName = "backtest_request_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String backtestId;

    @Column(nullable = false)
    private Long runId;

    /** null = 비로그인 요청(spec 005 B01). */
    @Column(nullable = true)
    private Long requesterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionType executionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ResultSource resultSource;

    private Long retryOfRequestId;

    private Instant cancelledAt;

    private LocalDate cancelledLastTradingDate;

    @Column(nullable = false)
    private boolean stoppedRun;

    /** 결과 공개 토큰(spec 005 FR-019). 발급 뒤 바뀌지 않는다. */
    @Column(length = 32, nullable = false, unique = true, updatable = false)
    private String publicToken;

    /** null = 만료 없음(로그인 요청). */
    @Column(updatable = false)
    private Instant publicTokenExpiresAt;

    /** backtestId에 쓸 날짜 접두. 저장 전 한 번 예약하고 PK와의 결합은 PrePersist가 한다. */
    @Transient private LocalDate backtestIdDate;

    protected BacktestRequestJpaEntity() {}

    BacktestRequestJpaEntity(
            Long id,
            String backtestId,
            Long runId,
            Long requesterId,
            ExecutionType executionType,
            ResultSource resultSource,
            Long retryOfRequestId,
            Instant cancelledAt,
            LocalDate cancelledLastTradingDate,
            boolean stoppedRun,
            String publicToken,
            Instant publicTokenExpiresAt) {
        this.id = id;
        this.backtestId = backtestId;
        this.runId = runId;
        this.requesterId = requesterId;
        this.executionType = executionType;
        this.resultSource = resultSource;
        this.retryOfRequestId = retryOfRequestId;
        this.cancelledAt = cancelledAt;
        this.cancelledLastTradingDate = cancelledLastTradingDate;
        this.stoppedRun = stoppedRun;
        this.publicToken = publicToken;
        this.publicTokenExpiresAt = publicTokenExpiresAt;
    }

    /** 저장 직전 한 번만 외부 식별자를 예약한다. 이미 값이 있으면 거부한다. */
    void assignBacktestIdOn(LocalDate date) {
        if (this.backtestId != null) {
            throw new IllegalStateException("backtestId already assigned: " + this.backtestId);
        }
        this.backtestIdDate = date;
    }

    /** 취소 기록을 반영한다. 전이 규칙은 도메인이 이미 검증했으므로 여기서는 값만 바꾼다. */
    void applyCancellation(
            Instant cancelledAt, LocalDate cancelledLastTradingDate, boolean stoppedRun) {
        this.cancelledAt = cancelledAt;
        this.cancelledLastTradingDate = cancelledLastTradingDate;
        this.stoppedRun = stoppedRun;
    }

    @PrePersist
    void fillBacktestId() {
        if (backtestId == null) {
            backtestId = BacktestId.of(backtestIdDate, id).value();
        }
    }

    public Long getId() {
        return id;
    }

    public String getBacktestId() {
        return backtestId;
    }

    public Long getRunId() {
        return runId;
    }

    public Long getRequesterId() {
        return requesterId;
    }

    public ExecutionType getExecutionType() {
        return executionType;
    }

    public ResultSource getResultSource() {
        return resultSource;
    }

    public Long getRetryOfRequestId() {
        return retryOfRequestId;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public LocalDate getCancelledLastTradingDate() {
        return cancelledLastTradingDate;
    }

    public boolean isStoppedRun() {
        return stoppedRun;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Instant getPublicTokenExpiresAt() {
        return publicTokenExpiresAt;
    }
}
