-- 개별 체결 기록 (spec 004·011 DEP-004-011).
--
-- backtest_trade 는 진입·청산을 묶은 단위라 묶음이 닫히면 양쪽 비용이 합쳐지고 세금은 실현 손익에만 들어간다.
-- 임의 구간(경제환경 사례의 사후 구간)의 거래·비용·현금 흐름을 다시 세려면 체결 단위가 있어야 한다.
--
-- 저장 규칙은 backtest_trade 와 같다: 질의(필터·정렬)에 쓰는 값만 컬럼, 나머지는 계약 DTO 그대로 JSONB.
-- decimal 은 예외 없이 JSONB 안의 문자열이다.

CREATE SEQUENCE backtest_fill_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE backtest_fill (
    id            BIGINT                   NOT NULL PRIMARY KEY,
    run_id        BIGINT                   NOT NULL REFERENCES backtest_run (id),
    fill_id       VARCHAR(255)             NOT NULL,
    instrument_id VARCHAR(32)              NOT NULL,
    side          VARCHAR(4)               NOT NULL,
    fill_time     TIMESTAMP WITH TIME ZONE NOT NULL,
    detail        JSONB                    NOT NULL,
    CONSTRAINT uk_backtest_fill_run_fill UNIQUE (run_id, fill_id),
    CONSTRAINT ck_backtest_fill_side CHECK (side IN ('BUY', 'SELL'))
);

-- 구간 조회의 정렬 (fill_time, id). fill_time 만으로는 같은 시각(같은 날 09:00)의 체결 순서가 불안정하다.
CREATE INDEX idx_backtest_fill_run_time ON backtest_fill (run_id, fill_time, id);

-- 체결을 기록한 결과인지. 이 마이그레이션 전의 결과와 체결을 기록하지 않는 워커가 낸 결과는 false 다.
-- backtest_fill 에 행이 없는 것만으로는 "체결이 없었다"와 "기록하지 않았다"를 가를 수 없다 — 기록이 없는
-- 결과를 체결 0건으로 읽으면 구간 비용이 0 으로 계산된다(원칙 VI).
ALTER TABLE backtest_result ADD COLUMN fills_recorded BOOLEAN NOT NULL DEFAULT FALSE;
