-- 백테스트 결과. BacktestResultJpaEntity / BacktestTradeJpaEntity 기준.
-- V1과 같이 PostgreSQL 문법을 그대로 쓴다(운영·로컬·테스트 모두 PostgreSQL).
--
-- 저장 규칙: 질의(필터·정렬·조인)에 쓰는 값만 컬럼, 표시용 값은 JSONB.
-- decimal은 예외 없이 JSONB다. NUMERIC 컬럼은 scale을 임의로 정해야 하고 그 자릿수를 넘는 값을
-- 조용히 반올림하지만, JSON은 계약이 준 값과 scale을 그대로 보존한다.

-- 결과. Job과 1:1이며 PK가 곧 FK다.
CREATE TABLE backtest_result (
    backtest_job_id   BIGINT                   NOT NULL PRIMARY KEY REFERENCES backtest_job (id),
    execution_id      VARCHAR(255)             NOT NULL,
    finished_event_id VARCHAR(255)             NOT NULL,
    started_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    error_code        VARCHAR(255),
    error_message     TEXT,
    performance       JSONB,
    execution         JSONB,
    portfolio_series  JSONB                    NOT NULL,
    benchmark         JSONB,
    diagnostics       JSONB                    NOT NULL,
    created_at        TIMESTAMP                NOT NULL,
    updated_at        TIMESTAMP                NOT NULL
);

-- 거래. Job과 1:N. 페이징·필터 대상이라 행으로 둔다.
-- 시퀀스 증분은 엔티티의 allocationSize(50)와 같아야 한다. 거래는 한 결과에 수백 건씩 들어온다.
CREATE SEQUENCE backtest_trade_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE backtest_trade (
    id              BIGINT                   NOT NULL PRIMARY KEY,
    backtest_job_id BIGINT                   NOT NULL REFERENCES backtest_job (id),
    trade_id        VARCHAR(255)             NOT NULL,
    instrument_id   VARCHAR(32)              NOT NULL,
    side            VARCHAR(8)               NOT NULL,
    status          VARCHAR(8)               NOT NULL,
    entry_time      TIMESTAMP WITH TIME ZONE NOT NULL,
    exit_time       TIMESTAMP WITH TIME ZONE,
    detail          JSONB                    NOT NULL,
    CONSTRAINT uk_backtest_trade_job_trade UNIQUE (backtest_job_id, trade_id)
);

-- 조회 API의 기본 정렬 (entry_time, id). entry_time만으로는 같은 시각의 거래 순서가 불안정해 페이징에서 행이 중복·누락된다.
CREATE INDEX idx_backtest_trade_job_entry ON backtest_trade (backtest_job_id, entry_time, id);
