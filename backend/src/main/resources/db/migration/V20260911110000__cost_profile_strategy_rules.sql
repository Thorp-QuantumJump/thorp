-- 비용 조건·전략 규칙 등록표. spec 004 T003(Jira S15P21E205-130), data-model.md, research R-07·R-08.
--
-- 파일 이름이 순번(V5)이 아니라 타임스탬프인 이유: 순번은 병렬 브랜치에서 반드시 겹친다.
-- 실제로 2026-09-10 에 서로 다른 파트가 각자 V3 를 만들어 Flyway 기동이 막혔다(S15P21E205-171).
-- 2026-09-11 팀 결정으로 이후 마이그레이션은 `V{yyyyMMddHHmm}__{설명}.sql` 로 짓는다.
--
-- 이 파일이 run/request 개명(V202609111110)보다 먼저다 — `backtest_run` 이 두 등록표를 FK로 참조한다.

-- ---------------------------------------------------------------------------
-- cost_profile: 비용 조건 버전 (R-07)
-- 실행 행은 버전 문자열만 들고 다니고 실제 bps 값은 여기서 읽는다. 비용이 바뀌면 행을 추가하고
-- 기존 실행의 결과 해석은 그대로 둔다(원칙 VIII 재현 기록).
-- ---------------------------------------------------------------------------
CREATE TABLE cost_profile (
    version        VARCHAR(32)              NOT NULL PRIMARY KEY,
    commission_bps NUMERIC(8, 4)            NOT NULL,
    spread_bps     NUMERIC(8, 4)            NOT NULL,
    slippage_bps   NUMERIC(8, 4)            NOT NULL,
    tax_bps        NUMERIC(8, 4)            NOT NULL,
    escalation     JSONB,
    provisional    BOOLEAN                  NOT NULL,
    note           TEXT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

COMMENT ON COLUMN cost_profile.escalation IS '비용 상승 시나리오 정의(PD-07 확정 전이라 null)';
COMMENT ON COLUMN cost_profile.provisional IS 'true = 팀 결정(PD-07) 전 임시값. 결과 versions.costProfile.provisional 로 노출한다';

-- 현행 데모 값을 그대로 옮긴다. 값을 정한 것이 아니라 ADR-0004 가 이미 쓰던 값을 버전으로 감싼 것이다.
INSERT INTO cost_profile (version, commission_bps, spread_bps, slippage_bps, tax_bps,
                          escalation, provisional, note)
VALUES ('PROVISIONAL-DEMO-1', 0, 0, 5, 0,
        NULL, true, 'ADR-0004 데모 값 재사용, PD-07 확정 전');

-- ---------------------------------------------------------------------------
-- strategy_rules: 전략 규칙 버전 (R-08)
-- 워커는 EngineInput 에 실려 온 규칙 명세만 신뢰하고 자체 기본값을 두지 않는다. 그래서 팀이 확정하지
-- 않은 전략은 명세 없이 PENDING_DECISION 행만 두고, 접수 단계에서 422 로 거부한다.
-- ---------------------------------------------------------------------------
CREATE TABLE strategy_rules (
    strategy_id   VARCHAR(32)              NOT NULL,
    rules_version VARCHAR(32)              NOT NULL,
    status        VARCHAR(20)              NOT NULL,
    provisional   BOOLEAN                  NOT NULL DEFAULT false,
    spec          JSONB,
    decision_ref  TEXT,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (strategy_id, rules_version),
    CONSTRAINT ck_strategy_rules_status
        CHECK (status IN ('REGISTERED', 'PENDING_DECISION')),
    -- REGISTERED 인데 명세가 없으면 워커가 계산할 규칙이 없고, PENDING 인데 명세가 있으면
    -- 확정되지 않은 값이 실행에 실려 나간다. 둘 다 조용히 지나가면 안 되므로 제약으로 막는다.
    CONSTRAINT ck_strategy_rules_spec
        CHECK ((status = 'REGISTERED' AND spec IS NOT NULL)
            OR (status = 'PENDING_DECISION' AND spec IS NULL))
);

COMMENT ON COLUMN strategy_rules.provisional IS 'true = 팀 결정(PD) 전 임시 규칙. 결과 versions.rules.provisional 로 노출한다';

-- spec FR-021: 사용자가 지정한 목표 비중으로 리밸런싱 주기마다 재조정한다. 비중 값 자체는 요청이 준다.
INSERT INTO strategy_rules (strategy_id, rules_version, status, provisional, spec, decision_ref)
VALUES ('FIXED_WEIGHT', '1', 'REGISTERED', false,
        '{"targetWeights": "USER_SPECIFIED", "weightsSource": "USER"}'::jsonb,
        'spec 004 FR-021');

-- spec FR-022: 투자 가능 섹터에 같은 목표 비중.
INSERT INTO strategy_rules (strategy_id, rules_version, status, provisional, spec, decision_ref)
VALUES ('EQUAL_WEIGHT', '1', 'REGISTERED', false,
        '{"targetWeights": "EQUAL_AMONG_INVESTABLE", "weightsSource": "COMPUTED"}'::jsonb,
        'spec 004 FR-022');

-- spec FR-023 은 관측 63거래일만 정했다. 0 변동성 처리·비중 상한·투자 비중 합계는 spec Edge Case 가
-- PD-02 계열 결정 항목으로 남긴 값이라, 출발안을 등록하되 provisional 로 표시한다(analyze C1).
-- PD-02 가 확정되면 이 행을 갱신하고 provisional 을 false 로 바꾼다.
INSERT INTO strategy_rules (strategy_id, rules_version, status, provisional, spec, decision_ref)
VALUES ('INVERSE_VOLATILITY', '1', 'REGISTERED', true,
        '{"lookbackDays": 63, "zeroVolatility": "EXCLUDE_THEN_ALLOCATE",
          "weightCap": "1.0000", "investedFraction": "1.0000",
          "weightsSource": "COMPUTED"}'::jsonb,
        'PD-02 미확정 — spec 004 FR-023 관측 길이만 확정, 나머지는 출발안');

-- PD-03·PD-04 미확정. 명세 없이 행만 두고 접수에서 422 RULES_NOT_REGISTERED 로 거부한다.
-- 규칙을 임의로 정해 넣으면 나중에 확정값과 결과 버전이 갈린다(constitution "실행 전 결정 임의 확정 금지").
INSERT INTO strategy_rules (strategy_id, rules_version, status, provisional, spec, decision_ref)
VALUES ('SECTOR_MOMENTUM', '1', 'PENDING_DECISION', false, NULL,
        'PD-03 미확정 — 유형·점수 산식·관측 길이·선정 수·동점 처리');

INSERT INTO strategy_rules (strategy_id, rules_version, status, provisional, spec, decision_ref)
VALUES ('MINIMUM_VARIANCE', '1', 'PENDING_DECISION', false, NULL,
        'PD-04 미확정 — 관측 기간·공분산 추정 방법·해 없음 처리');

-- 목표 위험 맞춤 시나리오의 공통 오버레이(PD-18 확정, R-17). 전략이 아니라 전략 위에 얹는 규칙이라
-- 같은 등록표에 두고 run.versions.scenarioRules 로 참조한다.
INSERT INTO strategy_rules (strategy_id, rules_version, status, provisional, spec, decision_ref)
VALUES ('TARGET_RISK_OVERLAY', '1', 'REGISTERED', false,
        '{"lookbackDays": 63, "adjustAt": "REBALANCE", "scaleDownOnly": true,
          "targetVolatility": {"default": "0.1000", "min": "0.0500",
                               "max": "0.2000", "step": "0.0100"}}'::jsonb,
        'PD-18 확정 2026-09-10');
