package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code regime_link}의 JPA 매핑 (data-model §4).
 *
 * <p>{@link DynamicUpdate}를 거는 것은 이벤트 처리(상태·시각)와 발행 경로({@code job_message_id}·{@code attempt})가 서로
 * 다른 스레드에서 이 행을 쓰기 때문이다 — 기본 UPDATE 는 바꾸지 않은 컬럼까지 함께 써서 늦게 저장한 쪽이 상대의 갱신을 되돌린다(004 {@code
 * BacktestRunJpaEntity}와 같은 이유).
 */
@Entity
@DynamicUpdate
@Table(name = "regime_link")
public class RegimeLinkJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "regime_link_seq_gen")
    @SequenceGenerator(
            name = "regime_link_seq_gen",
            sequenceName = "regime_link_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String linkId;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false, length = 200)
    private String snapshotKey;

    @Column(nullable = false)
    private LocalDate endMonth;

    @Column(nullable = false, length = 16)
    private String scenario;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> postWindow;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> availability;

    @Column(nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> failure;

    @Column(length = 64)
    private String jobMessageId;

    @Column(nullable = false)
    private short attempt;

    private Instant queuedAt;

    private Instant startedAt;

    private Instant finishedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** {@code link_id}에 쓸 날짜 접두. PK 와의 결합은 {@code @PrePersist} 가 한다. */
    @Transient private LocalDate linkIdDate;

    protected RegimeLinkJpaEntity() {}

    public RegimeLinkJpaEntity(
            Long runId,
            String snapshotKey,
            LocalDate endMonth,
            String scenario,
            Map<String, Object> postWindow,
            Map<String, Object> availability,
            String status,
            short attempt,
            LocalDate linkIdDate) {
        this.linkIdDate = linkIdDate;
        this.runId = runId;
        this.snapshotKey = snapshotKey;
        this.endMonth = endMonth;
        this.scenario = scenario;
        this.postWindow = postWindow;
        this.availability = availability;
        this.status = status;
        this.attempt = attempt;
    }

    /**
     * PK 채번 뒤·INSERT 문 생성 전에 불린다.
     *
     * <p>외부 식별자를 여기서 만드는 것은 날짜와 순번(PK)으로 짓기 때문이다. persist 뒤에 채우면 그 값이 INSERT 에 실리지 않는다(004 {@code
     * BacktestRunJpaEntity} 와 같은 이유).
     */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (linkId == null) {
            linkId = LinkId.of(linkIdDate, id).value();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * 발행 경로가 쓰는 컬럼만 바꾼다.
     *
     * <p>{@code @DynamicUpdate}가 걸려 있어 바뀐 컬럼만 UPDATE 에 실린다 — 이벤트 경로가 같은 시각에 상태를 써도 서로의 값을 되돌리지
     * 않는다(backend/CLAUDE.md "한 행을 여러 경로가 갱신하면 컬럼 소유를 가른다").
     */
    void applyPublished(String jobMessageId, int attempt, Instant queuedAt) {
        this.status = "QUEUED";
        this.jobMessageId = jobMessageId;
        this.attempt = (short) attempt;
        this.queuedAt = queuedAt;
    }

    /** 이벤트 경로가 쓰는 컬럼만 바꾼다. */
    void applyRunning(Instant startedAt) {
        this.status = "RUNNING";
        this.startedAt = startedAt;
    }

    void applyCompleted(Instant finishedAt) {
        this.status = "COMPLETED";
        this.failure = null;
        this.finishedAt = finishedAt;
    }

    void applyFailed(Map<String, Object> failure, Instant finishedAt) {
        this.status = "FAILED";
        this.failure = failure;
        this.finishedAt = finishedAt;
    }

    /** 새 004 실행을 기다리는 상태로 되돌린다. 앞선 실패 기록은 지운다 — 남기면 화면이 끝난 실패를 다시 보인다. */
    void applyPendingRun(Long runId) {
        this.status = "PENDING_RUN";
        this.runId = runId;
        this.failure = null;
        this.finishedAt = null;
    }

    public Long getId() {
        return id;
    }

    public String getLinkId() {
        return linkId;
    }

    public Long getRunId() {
        return runId;
    }

    public String getSnapshotKey() {
        return snapshotKey;
    }

    public LocalDate getEndMonth() {
        return endMonth;
    }

    public String getScenario() {
        return scenario;
    }

    public Map<String, Object> getPostWindow() {
        return postWindow;
    }

    public Map<String, Object> getAvailability() {
        return availability;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getFailure() {
        return failure;
    }

    public String getJobMessageId() {
        return jobMessageId;
    }

    public short getAttempt() {
        return attempt;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
