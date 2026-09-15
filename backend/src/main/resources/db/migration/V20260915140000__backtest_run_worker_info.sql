-- 어느 큐에서 어느 역할의 워커가 이 실행을 집었나. spec 004 T012(Jira S15P21E205-153), research R-10 ③·R-20.
--
-- 작업 큐가 단건(backtest.jobs)·벌크(backtest.jobs.bulk) 둘로 나뉘고 워커가 단건 전용(single)·벌크 우선
-- 공용(flex)으로 갈린 뒤, "공용 워커가 단건을 얼마나 도왔나"가 N/M 워커 비율을 정하는 근거가 됐다.
-- 그 값은 브로커도 백엔드도 모른다 — 집은 쪽(워커)만 알아서 STARTED 의 resource.queue·resource.role 에 실어
-- 보내고, 백엔드가 여기 적어 두었다가 종결 때 측정 기록(backtest_run_metric)으로 복사한다.
--
-- 이벤트 처리 경로가 전용 UPDATE 로만 쓴다(컬럼 소유 규칙). 값이 없는 행은 큐 분리 전 워커가 처리했거나
-- STARTED 를 받지 못한 실행이다.

ALTER TABLE backtest_run ADD COLUMN served_queue VARCHAR(40);
ALTER TABLE backtest_run ADD COLUMN worker_role VARCHAR(10);

COMMENT ON COLUMN backtest_run.served_queue IS
    '이 실행을 집은 작업 큐 이름(STARTED resource.queue). null = 알 수 없음(큐 분리 전 워커, STARTED 미수신)';
COMMENT ON COLUMN backtest_run.worker_role IS
    '이 실행을 집은 워커 역할 single|flex (STARTED resource.role). flex 가 단건을 도운 비율의 근거(R-20)';
