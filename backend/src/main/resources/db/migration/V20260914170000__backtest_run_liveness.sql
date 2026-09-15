-- 정체 감지와 강제 종결에 필요한 두 컬럼. spec 004 T014(Jira S15P21E205-168), research R-19.
--
-- 1) last_event_at — 워커가 살아 있다는 마지막 신호(STARTED·PROGRESS 수신 시각).
--
-- updated_at 으로 대신하지 않는다. 그 값은 발행 기록(published_at·publish_count·dlq_count)이
-- 갱신될 때도 올라가므로, 실제로 멈춘 실행이 "방금 살아 있었다"로 보여 정체를 놓친다. 이 셈이
-- 틀리는 방향은 "안 잡는 쪽"이라 위험하다 — 워커 하나가 prefetch=1 로 묶이면 처리량이 절반이 되는데
-- 아무도 모르게 된다.
--
-- 2) cancel_reason — CANCEL 을 보낸 이유.
--
-- 유예 시간 안에 FINISHED 가 오지 않아 강제 종결할 때 failure_code 가 이 값에 따라 갈린다(R-19):
-- 사용자 취소·정체는 SYSTEM_FAILURE, 실행 시간 초과는 EXECUTION_LIMIT. 종결 시점에 조건을 다시
-- 계산해 추론할 수도 있지만, 그때의 설정값이 취소를 보낼 때와 다르면 사유가 뒤바뀐다.

ALTER TABLE backtest_run ADD COLUMN last_event_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE backtest_run ADD COLUMN cancel_reason VARCHAR(32);

COMMENT ON COLUMN backtest_run.last_event_at IS
    'STARTED·PROGRESS 를 받은 마지막 시각. 정체 판정 기준(R-19). 발행 기록은 이 값을 건드리지 않는다';
COMMENT ON COLUMN backtest_run.cancel_reason IS
    'CANCEL 을 보낸 이유: USER(요청자 취소) · EXECUTION_LIMIT(실행 시간 초과) · STALE(정체). '
    '유예 뒤 강제 종결의 failure_code 가 이 값으로 갈린다';

ALTER TABLE backtest_run ADD CONSTRAINT ck_backtest_run_cancel_reason
    CHECK (cancel_reason IS NULL OR cancel_reason IN ('USER', 'EXECUTION_LIMIT', 'STALE'));

-- 정체 감지는 "RUNNING 인데 마지막 신호가 오래된 실행"을, 유예 감시는 "취소를 보낸 지 오래된 실행"을
-- 주기적으로 훑는다. 둘 다 상태로 먼저 좁힌다.
CREATE INDEX idx_backtest_run_liveness ON backtest_run (status, last_event_at);
CREATE INDEX idx_backtest_run_cancel_pending ON backtest_run (cancel_requested_at)
    WHERE cancel_requested_at IS NOT NULL;
