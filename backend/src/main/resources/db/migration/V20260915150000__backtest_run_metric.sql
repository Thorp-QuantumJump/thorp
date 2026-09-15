-- 실행 측정 기록과 한도 도달 기록, 집계 뷰. spec 004 T012(Jira S15P21E205-153), research R-10, data-model.md.
--
-- FR-006·PD-06: 실행 한도 값은 실측으로 정한다. 종결(COMPLETED·FAILED·CANCELLED·DLQ·강제 종결)마다 한 행을
-- 남기고 운영자가 유형별 대기·소요·실패·자동 재시도·원가(원단위)를 본다. 큐 분리(R-20) 뒤 N/M 워커 비율을
-- 조정할 근거(어느 큐·어느 역할의 워커가 집었나)도 여기 있다.
--
-- 원가는 cpu 초·메모리 MB·바이트 원단위만 둔다. 금액 단가·상한 값은 넣지 않는다(PD-06·D19).

CREATE SEQUENCE backtest_run_metric_seq START WITH 1 INCREMENT BY 1;

-- run_id 는 ON DELETE CASCADE 다. 운영에서 실행 행은 지우지 않지만(append-only), 실행이 없는 측정은 뜻이 없고
-- 테스트 픽스처는 실행 행을 지워 정리한다 — 여기서 막으면 실행을 지우는 모든 정리가 이 표 때문에 실패한다.
CREATE TABLE backtest_run_metric (
    id                 BIGINT                   NOT NULL PRIMARY KEY,
    run_id             BIGINT                   NOT NULL REFERENCES backtest_run (id) ON DELETE CASCADE,
    execution_type     VARCHAR(20)              NOT NULL,
    status             VARCHAR(20)              NOT NULL,
    failure_code       VARCHAR(64),
    queued_at          TIMESTAMP WITH TIME ZONE,
    started_at         TIMESTAMP WITH TIME ZONE,
    finished_at        TIMESTAMP WITH TIME ZONE,
    wait_ms            BIGINT,
    run_ms             BIGINT,
    concurrent_running INTEGER                  NOT NULL,
    peak_rss_mb        NUMERIC(12, 1),
    cpu_seconds        NUMERIC(12, 3),
    auto_retried       BOOLEAN                  NOT NULL,
    result_bytes       BIGINT                   NOT NULL,
    served_queue       VARCHAR(40),
    worker_role        VARCHAR(10),
    recorded_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_backtest_run_metric_status
        CHECK (status IN ('COMPLETED', 'FAILED', 'CANCELLED'))
);

COMMENT ON TABLE backtest_run_metric IS
    '실행 종결 한 번에 한 행(R-10). PD-06 한도 값과 R-20 워커 비율 결정의 근거. append-only';
COMMENT ON COLUMN backtest_run_metric.wait_ms IS 'started_at - queued_at. STARTED 를 못 받은 실행은 null';
COMMENT ON COLUMN backtest_run_metric.run_ms IS 'finished_at - started_at. STARTED 를 못 받은 실행은 null';
COMMENT ON COLUMN backtest_run_metric.concurrent_running IS '종결 시점에 RUNNING 이던 다른 실행 수';
COMMENT ON COLUMN backtest_run_metric.auto_retried IS
    'attempt > publish_count (R-18). 재발행으로 생긴 중복 실행은 재시도가 아니다';
COMMENT ON COLUMN backtest_run_metric.result_bytes IS
    'backtest_result 행의 pg_column_size(TOAST 압축 뒤). 결과 없는 종결(DLQ·강제 종결)은 0';
COMMENT ON COLUMN backtest_run_metric.served_queue IS
    '집은 작업 큐(backtest_run.served_queue 복사). null = 알 수 없음';
COMMENT ON COLUMN backtest_run_metric.worker_role IS
    '집은 워커 역할 single|flex(backtest_run.worker_role 복사). 단건 유형에서 flex 비율 = 공용 워커가 도운 비율(R-20)';

-- 종결은 한 번이다. 늦게 온 중복 종결·재전달이 두 번 기록하려 하면 DB 가 막고 기록기는 ON CONFLICT DO NOTHING 으로 넘어간다.
CREATE UNIQUE INDEX uk_backtest_run_metric_run ON backtest_run_metric (run_id);
-- 집계는 유형별·기간별이다.
CREATE INDEX idx_backtest_run_metric_type_recorded ON backtest_run_metric (execution_type, recorded_at);

-- 한도 도달. 접수 거절(429)은 실행 행이 없어 측정 기록에 자리가 없고, 실행 시간 상한 정지(CANCEL 발송 시점)는
-- 실행이 아직 끝나지 않았다. 둘을 한 표에 kind 로 가른다(R-10 구현 시 결정 ②).
CREATE SEQUENCE backtest_limit_hit_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE backtest_limit_hit (
    id             BIGINT                   NOT NULL PRIMARY KEY,
    kind           VARCHAR(20)              NOT NULL,
    execution_type VARCHAR(20)              NOT NULL,
    reason         VARCHAR(32)              NOT NULL,
    requester_id   BIGINT,
    -- 위와 같은 이유로 CASCADE. 접수 거절 행은 run_id 가 null 이라 영향이 없다.
    run_id         BIGINT                   REFERENCES backtest_run (id) ON DELETE CASCADE,
    at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_backtest_limit_hit_kind
        CHECK (kind IN ('ADMISSION_REJECTED', 'RUN_STOPPED'))
);

COMMENT ON TABLE backtest_limit_hit IS
    '한도 도달 기록(FR-006). ADMISSION_REJECTED = 접수 429(requester_id 있음), RUN_STOPPED = 실행 시간 상한으로 CANCEL(run_id 있음)';
COMMENT ON COLUMN backtest_limit_hit.reason IS 'PER_USER · TOTAL(접수) · EXECUTION_LIMIT(정지)';

CREATE INDEX idx_backtest_limit_hit_type_at ON backtest_limit_hit (execution_type, at);

-- 유형별 집계. GET /internal/execution-metrics 는 같은 SELECT 에 recorded_at 범위만 더한다(기록기 쪽 상수와 문장이 같다).
-- percentile_cont 는 null 을 건너뛴다 — STARTED 를 못 받은 실행의 wait/run 은 분포에 들어가지 않는다.
-- reuse_count 는 요청 유형으로, limit_hit_count 는 한도 유형으로 조인한다(둘 다 실행 행이 없을 수 있다).
CREATE VIEW v_run_metric_summary AS
SELECT m.execution_type,
       count(*)                                                              AS count,
       avg(CASE WHEN m.status = 'FAILED' THEN 1.0 ELSE 0.0 END)              AS fail_rate,
       avg(CASE WHEN m.status = 'CANCELLED' THEN 1.0 ELSE 0.0 END)           AS cancel_rate,
       avg(CASE WHEN m.auto_retried THEN 1.0 ELSE 0.0 END)                   AS auto_retry_rate,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY m.wait_ms)                AS wait_p50_ms,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY m.wait_ms)               AS wait_p95_ms,
       percentile_cont(0.99) WITHIN GROUP (ORDER BY m.wait_ms)               AS wait_p99_ms,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY m.run_ms)                 AS run_p50_ms,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY m.run_ms)                AS run_p95_ms,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY m.peak_rss_mb)           AS peak_rss_p95_mb,
       avg(CASE WHEN m.worker_role = 'flex' THEN 1.0 ELSE 0.0 END)           AS flex_served_rate,
       (SELECT count(*) FROM backtest_reuse_event e
         WHERE e.request_execution_type = m.execution_type)                  AS reuse_count,
       (SELECT count(*) FROM backtest_limit_hit h
         WHERE h.execution_type = m.execution_type)                          AS limit_hit_count
  FROM backtest_run_metric m
 GROUP BY m.execution_type;

COMMENT ON VIEW v_run_metric_summary IS
    '실행 유형별 대기·소요 분포, 실패·취소·자동 재시도율, flex 워커 지원 비율, 재사용·한도 도달 수. PD-06 결정 자료(R-10)';
