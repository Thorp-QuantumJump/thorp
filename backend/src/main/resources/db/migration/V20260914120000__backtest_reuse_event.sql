-- 재사용·합류 기록. spec 004 T010(Jira S15P21E205-148), data-model.md §backtest_reuse_event, FR-008.
--
-- 같은 조건의 요청이 새 계산을 만들지 않고 진행 중 실행에 합류(JOINED)하거나 완료된 실행의 결과를
-- 재사용(REUSED)한 사실을 한 줄씩 남긴다. 원가·운영 지표에서 "새로 계산한 실행"과 "아낀 요청"을 가르는
-- 근거다 — T012 의 v_run_metric_summary 가 이 표를 조인해 reuse_count 를 센다.
--
-- 요청 행의 result_source 로 대신하지 않는 이유: 그 값은 요청의 현재 상태이고, 이 표는 접수 시점에
-- 무슨 판단이 있었는지에 대한 기록이다. 둘을 한 컬럼에 겹쳐 두면 나중에 상태가 바뀌었을 때 과거의
-- 판단을 되짚을 수 없다.
--
-- 추가만 하고 고치지 않는다(append-only). 그래서 updated_at 을 두지 않고 발생 시각 at 하나만 둔다.

CREATE SEQUENCE backtest_reuse_event_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE backtest_reuse_event (
    id                     BIGINT                   NOT NULL PRIMARY KEY,
    run_id                 BIGINT                   NOT NULL REFERENCES backtest_run (id),
    request_id             BIGINT                   NOT NULL REFERENCES backtest_request (id),
    kind                   VARCHAR(10)              NOT NULL,
    request_execution_type VARCHAR(20)              NOT NULL,
    at                     TIMESTAMP WITH TIME ZONE NOT NULL,
    -- NEW 는 이 표에 오지 않는다. 새 계산은 실행 행 자체가 기록이다.
    CONSTRAINT ck_backtest_reuse_event_kind
        CHECK (kind IN ('JOINED', 'REUSED'))
);

COMMENT ON TABLE backtest_reuse_event IS
    '같은 조건의 요청이 합류(JOINED)하거나 결과를 재사용(REUSED)한 기록(FR-008). append-only';
COMMENT ON COLUMN backtest_reuse_event.request_execution_type IS
    '접수 시점 요청의 execution_type. 요청 행과 조인하지 않고도 종류별로 셀 수 있게 복사해 둔다';
COMMENT ON COLUMN backtest_reuse_event.at IS '접수가 재사용·합류로 판단한 시각';

-- 한 요청은 많아야 한 번 재사용·합류한다. 중복 기록은 reuse_count 를 부풀리므로 DB 가 막는다.
CREATE UNIQUE INDEX uk_backtest_reuse_event_request ON backtest_reuse_event (request_id);
CREATE INDEX idx_backtest_reuse_event_run ON backtest_reuse_event (run_id);
