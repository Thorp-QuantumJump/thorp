package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.FillSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code backtest_fill} 테이블 매핑. 실행과 1:N. 개별 체결 한 건이 한 행이다(spec 011 DEP-004-011).
 *
 * <p>{@link BacktestTradeJpaEntity}와 같은 저장 규칙이다 — 구간 조회에 쓰는 {@code fill_time}·{@code
 * instrument_id}·{@code side}만 컬럼으로 꺼내고 체결가·수량·비용은 계약 DTO 그대로 {@code detail} JSON에 담는다(decimal
 * scale 보존). 정렬은 {@code (run_id, fill_time, id)}로 고정한다. 저장 순서가 워커의 체결 순서이므로 같은 시각의 체결도 {@code id}로
 * 순서가 남는다. 체결 행은 한 번 쓰고 바뀌지 않아 생성·수정 시각을 두지 않는다.
 */
@Entity
@Table(
        name = "backtest_fill",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_backtest_fill_run_fill",
                        columnNames = {"run_id", "fill_id"}),
        indexes = @Index(name = "idx_backtest_fill_run_time", columnList = "run_id, fill_time, id"))
public class BacktestFillJpaEntity {

    /** 시퀀스 증분과 같은 값. 체결은 한 결과에 수백~수천 건이라 1로 두면 건마다 시퀀스를 조회한다. */
    static final int SEQUENCE_ALLOCATION_SIZE = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "backtest_fill_seq_generator")
    @SequenceGenerator(
            name = "backtest_fill_seq_generator",
            sequenceName = "backtest_fill_seq",
            allocationSize = SEQUENCE_ALLOCATION_SIZE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private BacktestRunJpaEntity job;

    @Column(nullable = false, length = 255)
    private String fillId;

    @Column(nullable = false, length = 32)
    private String instrumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private FillSide side;

    @Column(nullable = false)
    private Instant fillTime;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private EngineOutput.FillResult detail;

    protected BacktestFillJpaEntity() {}

    private BacktestFillJpaEntity(BacktestRunJpaEntity job, EngineOutput.FillResult fill) {
        this.job = job;
        this.fillId = fill.fillId();
        this.instrumentId = fill.instrumentId();
        this.side = fill.side();
        this.fillTime = fill.time();
        this.detail = fill;
    }

    static BacktestFillJpaEntity from(BacktestRunJpaEntity job, EngineOutput.FillResult fill) {
        return new BacktestFillJpaEntity(job, fill);
    }

    public Long getId() {
        return id;
    }

    public String getFillId() {
        return fillId;
    }

    public String getInstrumentId() {
        return instrumentId;
    }

    public FillSide getSide() {
        return side;
    }

    public Instant getFillTime() {
        return fillTime;
    }

    public EngineOutput.FillResult getDetail() {
        return detail;
    }
}
