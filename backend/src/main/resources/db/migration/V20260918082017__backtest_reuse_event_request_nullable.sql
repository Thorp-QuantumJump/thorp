-- spec 005 결과 이해·비교 T022(Jira S15P21E205-335): 재사용·합류 기록이 지워진 요청을 가리키지 않게 한다.
--
-- 만료된 비로그인 요청을 정리하면 요청 행이 사라진다. 그 요청의 재사용·합류 기록까지 지우면 v_run_metric_summary 의
-- reuse_count 가 정리할 때마다 줄어, 계산 원가와 동일 결과 재조회를 가르는 측정 근거(constitution 원칙 IX,
-- spec 005 FR-021)가 사라진다. 기록은 남기고 요청과의 연결만 끊도록 request_id 의 NOT NULL 을 푼다.
--
-- 외래 키는 그대로 둔다. 값이 있으면 여전히 살아 있는 요청을 가리켜야 한다.
-- uk_backtest_reuse_event_request 도 그대로 둔다. PostgreSQL 유일 색인은 NULL 끼리 겹치지 않는다고 보므로 끊긴 기록이
-- 여럿 있어도 막히지 않고, 살아 있는 요청의 중복 기록은 계속 막는다.
--
-- 제약 완화라 기존 행이 위반하게 되는 일은 없다.

ALTER TABLE backtest_reuse_event ALTER COLUMN request_id DROP NOT NULL;

COMMENT ON COLUMN backtest_reuse_event.request_id IS
    '재사용·합류한 요청. 만료된 비로그인 요청이 정리되면 NULL 이다 — 기록은 reuse_count 근거로 남는다(spec 005 FR-021)';
