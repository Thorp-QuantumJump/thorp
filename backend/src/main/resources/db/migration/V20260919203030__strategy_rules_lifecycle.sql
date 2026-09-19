-- 전략 규칙 버전 수명주기(spec 004 T029, Jira S15P21E205-353).
--
-- 발표 전에는 보존할 결과가 없어 규칙을 제자리에서 고쳤다(T022·T023). 실제 결과가 쌓이면 그렇게 할 수
-- 없다 — 재현 키에 rules_version 이 들어가므로, 행을 고치면 같은 버전 문자열이 두 가지 계산을 가리키고
-- 옛 결과를 같은 규칙으로 다시 시도할 방법도 사라진다.
--
-- 그래서 이제부터 규칙을 바꿀 때는 새 버전 행을 INSERT 하고 옛 행의 status 만 SUPERSEDED 로 바꾼다.
-- 옛 행의 spec 은 그대로 남아 그 버전으로 접수된 실행의 발행·재시도가 계속 읽는다. 새 요청은 옛 버전을
-- 고를 수 없다(409 REGISTRY_VERSION_CHANGED).
--
-- 규칙 행은 건드리지 않는다. 지금 모든 전략은 행이 하나라 아래 부분 유니크 인덱스를 그대로 만족한다.
-- 바꾸는 데이터는 맨 아래 목표 위험 실행의 오버레이 버전 기록 하나다.

ALTER TABLE strategy_rules
    DROP CONSTRAINT ck_strategy_rules_status,
    DROP CONSTRAINT ck_strategy_rules_spec;

ALTER TABLE strategy_rules
    ADD CONSTRAINT ck_strategy_rules_status
        CHECK (status IN ('REGISTERED', 'PENDING_DECISION', 'SUPERSEDED')),
    -- 대체된 행도 명세를 잃으면 안 된다. 그 버전으로 접수된 실행이 아직 발행·재시도될 수 있다.
    ADD CONSTRAINT ck_strategy_rules_spec
        CHECK ((status IN ('REGISTERED', 'SUPERSEDED') AND spec IS NOT NULL)
            OR (status = 'PENDING_DECISION' AND spec IS NULL));

-- 전략마다 "지금 고를 수 있는 행"은 하나다. 둘이면 화면(GET /backtests/registry)과 007 자동 탐색이 어느
-- 버전을 실을지 행 순서에 맡기게 된다. 새 버전을 넣는 마이그레이션은 옛 행을 SUPERSEDED 로 바꾼 뒤에
-- 새 행을 INSERT 한다(같은 파일 안에서, 이 순서로).
CREATE UNIQUE INDEX uk_strategy_rules_current
    ON strategy_rules (strategy_id)
    WHERE status <> 'SUPERSEDED';

-- 목표 위험 실행에 오버레이 규칙 버전 기록을 채운다.
--
-- 목표 위험 시나리오는 2026-09-11 부터 접수했지만, 실행의 versions 에 오버레이 버전(scenarioRules)을
-- 남기기 시작한 것은 2026-09-15 부터다. 그 사이에 접수된 실행에는 기록이 없다. 이제 발행·재시도는 지금
-- 버전을 고르지 않고 이 기록으로 오버레이 명세를 읽으므로, 기록이 없으면 그 실행을 다시 계산할 수 없다
-- (재시도는 500, 대기 중 실행은 재발행에서 조립 실패로 닫힌다). 그 기간의 코드는 오버레이를
-- TARGET_RISK_OVERLAY/1 로 고정해 읽었으므로 추정이 아니라 그때 쓴 값이다.
-- backfill:begin
UPDATE backtest_run
SET versions = COALESCE(versions, '{}'::jsonb) || '{"scenarioRules": "TARGET_RISK_OVERLAY/1"}'::jsonb
WHERE request_spec ->> 'scenario' = 'TARGET_RISK'
  AND (versions IS NULL OR versions -> 'scenarioRules' IS NULL);
-- backfill:end
