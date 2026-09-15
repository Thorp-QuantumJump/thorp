-- spec 004 T019: 데이터 원가(FR-008·constitution IX "계산·데이터·저장 원가").
-- 측정 기록에 계산(cpu_seconds·peak_rss_mb)·저장(result_bytes)만 있고 데이터 칸이 없었다.
-- 워커가 FINISHED resource.dataRows·dataBytes 로 실어 오는 값을 그대로 둔다. 자료를 읽지 못한
-- 종결(거절·DLQ·강제 종결)은 null 이다 — 0 으로 채우지 않는다(원칙 VI).
ALTER TABLE backtest_run_metric
    ADD COLUMN data_rows  BIGINT,
    ADD COLUMN data_bytes BIGINT;

COMMENT ON COLUMN backtest_run_metric.data_rows IS
    '이 실행이 읽은 자료 행 수(가격·분배금·분할). FINISHED resource.dataRows. 자료를 읽지 못한 종결은 null';
COMMENT ON COLUMN backtest_run_metric.data_bytes IS
    '읽은 가격 프레임이 워커 메모리에서 차지한 바이트(근사). FINISHED resource.dataBytes';

-- 뷰는 열을 끝에 덧붙이기만 한다(CREATE OR REPLACE VIEW 는 기존 열의 이름·순서·타입을 바꾸지 못한다).
-- 앞부분은 V20260915150000 의 SELECT 와 같고, ExecutionMetricRepositoryImpl.SUMMARY_BODY 와도 같은 문장이어야 한다.
CREATE OR REPLACE VIEW v_run_metric_summary AS
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
         WHERE h.execution_type = m.execution_type)                          AS limit_hit_count,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY m.data_rows)              AS data_rows_p50,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY m.data_bytes)             AS data_bytes_p50
  FROM backtest_run_metric m
 GROUP BY m.execution_type;

COMMENT ON VIEW v_run_metric_summary IS
    '실행 유형별 대기·소요 분포, 실패·취소·자동 재시도율, flex 워커 지원 비율, 재사용·한도 도달 수, 데이터 원가 중앙값. PD-06 결정 자료(R-10·T019)';
