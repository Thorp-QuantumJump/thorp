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
-- 이 파일은 구조만 바꾸고 기존 행은 건드리지 않는다. 지금 모든 전략은 행이 하나라 아래 부분 유니크
-- 인덱스를 그대로 만족한다.

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
