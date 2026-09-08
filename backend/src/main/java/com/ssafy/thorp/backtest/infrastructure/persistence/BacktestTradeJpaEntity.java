package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.TradeSide;
import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
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
 * {@code backtest_trade} 테이블 매핑. Job과 1:N. 거래는 페이징·필터 대상이라 행으로 둔다.
 *
 * <p>필터·정렬 키({@code instrument_id}, {@code status}, {@code entry_time})만 컬럼으로 꺼내고, 체결가·수량·손익 같은 숫자는
 * 계약 DTO({@link EngineOutput.TradeResult}) 그대로 {@code detail} JSON에 담는다. 결과 엔티티와 같은 이유(decimal
 * scale 보존)다. 컬럼과 JSON에 같은 값이 일부 중복되지만, 조회 측이 {@code detail} 하나만 역직렬화하면 계약 형태의 거래를 그대로 얻는다.
 *
 * <p>정렬은 {@code (backtest_job_id, entry_time, id)}로 고정한다. {@code entry_time}만으로는 같은 시각의 거래 순서가 불안정해
 * 페이징에서 행이 중복·누락된다. 거래 행은 한 번 쓰고 바뀌지 않아 생성·수정 시각을 두지 않는다.
 */
@Entity
@Table(
        name = "backtest_trade",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_backtest_trade_job_trade",
                        columnNames = {"backtest_job_id", "trade_id"}),
        indexes =
                @Index(
                        name = "idx_backtest_trade_job_entry",
                        columnList = "backtest_job_id, entry_time, id"))
public class BacktestTradeJpaEntity {

    /** 시퀀스 증분과 같은 값. 거래는 한 결과에 수백 건씩 들어오므로 1로 두면 건마다 시퀀스를 조회한다. */
    static final int SEQUENCE_ALLOCATION_SIZE = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "backtest_trade_seq_generator")
    @SequenceGenerator(
            name = "backtest_trade_seq_generator",
            sequenceName = "backtest_trade_seq",
            allocationSize = SEQUENCE_ALLOCATION_SIZE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "backtest_job_id", nullable = false)
    private BacktestJobJpaEntity job;

    @Column(nullable = false, length = 255)
    private String tradeId;

    @Column(nullable = false, length = 32)
    private String instrumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TradeSide side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TradeStatus status;

    @Column(nullable = false)
    private Instant entryTime;

    /** OPEN 거래는 null. */
    private Instant exitTime;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private EngineOutput.TradeResult detail;

    protected BacktestTradeJpaEntity() {}

    private BacktestTradeJpaEntity(BacktestJobJpaEntity job, EngineOutput.TradeResult trade) {
        this.job = job;
        this.tradeId = trade.tradeId();
        this.instrumentId = trade.instrumentId();
        this.side = trade.side();
        this.status = trade.status();
        this.entryTime = trade.entryTime();
        this.exitTime = trade.exitTime();
        this.detail = trade;
    }

    static BacktestTradeJpaEntity from(BacktestJobJpaEntity job, EngineOutput.TradeResult trade) {
        return new BacktestTradeJpaEntity(job, trade);
    }

    public Long getId() {
        return id;
    }

    public BacktestJobJpaEntity getJob() {
        return job;
    }

    public String getTradeId() {
        return tradeId;
    }

    public String getInstrumentId() {
        return instrumentId;
    }

    public TradeSide getSide() {
        return side;
    }

    public TradeStatus getStatus() {
        return status;
    }

    public Instant getEntryTime() {
        return entryTime;
    }

    public Instant getExitTime() {
        return exitTime;
    }

    public EngineOutput.TradeResult getDetail() {
        return detail;
    }
}
