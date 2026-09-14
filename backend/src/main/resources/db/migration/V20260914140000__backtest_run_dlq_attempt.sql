-- DLQ 로 끝난 발행 시도의 기록. spec 004 T010(Jira S15P21E205-148), R-18 ③.
--
-- DLQ 소비는 at-least-once 다 — dlq_count 를 올리고 커밋한 뒤 ACK 이 유실되면 브로커가 같은 메시지를
-- 다시 준다. 같은 메시지를 두 번 세면 살아 있는 작업이 남았는데도 dlq_count >= publish_count 가 서서
-- 실행이 잘못 FAILED 로 끝난다.
--
-- 그래서 "무엇을 세었는지"를 남긴다. 발행 시도마다 번호가 있고(publish_count 는 시도 직전에 오르므로
-- 그때의 값이 그 시도의 번호다) 발행기가 그 번호를 메시지에 실어 보내므로, (run_id, attempt) 가 죽은
-- 메시지 하나를 정확히 가리킨다. PK 가 중복을 막고 INSERT 의 성공 여부가 곧 "처음 세는가"다.
--
-- 비트마스크 한 컬럼으로 줄이지 않은 이유: 재발행기(JobRepublisher)는 브로커가 돌아올 때까지 30초마다
-- 다시 보내므로 publish_count 에 상한이 없다. 64를 넘는 순간 조용히 틀리는 구조를 만들지 않는다.
--
-- 추가만 하고 고치지 않는다. 실행이 지워지면 함께 지운다(ON DELETE CASCADE) — 이 기록만 남아 있어 봐야
-- 가리킬 실행이 없다.

CREATE TABLE backtest_run_dlq_attempt (
    run_id  BIGINT                   NOT NULL REFERENCES backtest_run (id) ON DELETE CASCADE,
    attempt INTEGER                  NOT NULL,
    at      TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (run_id, attempt),
    CONSTRAINT ck_backtest_run_dlq_attempt_positive CHECK (attempt >= 1)
);

COMMENT ON TABLE backtest_run_dlq_attempt IS
    'DLQ 로 끝난 발행 시도(run_id, attempt). 같은 메시지를 두 번 세지 않기 위한 기록(R-18 ③)';
COMMENT ON COLUMN backtest_run_dlq_attempt.attempt IS
    '그 메시지를 보낼 때의 backtest_run.publish_count. 발행기가 publishAttempt 헤더로 실어 보낸다';
