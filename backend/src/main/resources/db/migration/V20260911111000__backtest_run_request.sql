-- 분석 요청과 실행의 분리. spec 004 T003(Jira S15P21E205-130), data-model.md, research R-02·R-04·R-05·R-18.
--
-- 요청(backtest_request)과 실행(backtest_run)은 다대일이다. 같은 조건의 요청은 진행 중인 실행에 합류하고
-- 완료된 실행의 결과를 재사용한다(Clarify Q2·Q3). 결과·거래는 실행에 붙는다.
--
-- 등록표(V20260911110000)가 먼저다 — backtest_run 이 두 등록표를 FK로 참조한다.

-- ---------------------------------------------------------------------------
-- 1) DEMO 데이터 폐기
--
-- backtest_request.requester_id 는 NOT NULL(FK → users)인데 기존 backtest_job 행에는 요청자가 없다.
-- 보존하려면 시스템 사용자를 만들어야 하는데, 기존 행은 v1 계약(단일 종목 모멘텀)의 산출물이라
-- v2 결과 화면에서 읽을 수 없다. tasks.md T003 이 삭제를 확정했다.
--
-- 007 param_search 가 FK로 참조하므로 링크를 먼저 끊는다. 두 컬럼 모두 nullable 이다.
-- 삭제하지 않고 NULL 로 두는 이유: 탐색 묶음 자체는 007 의 자료이고 이 마이그레이션의 대상이 아니다.
-- ---------------------------------------------------------------------------
UPDATE search_candidate SET backtest_job_id = NULL WHERE backtest_job_id IS NOT NULL;
UPDATE param_search SET baseline_job_id = NULL WHERE baseline_job_id IS NOT NULL;
DELETE FROM backtest_trade;
DELETE FROM backtest_result;
DELETE FROM backtest_job;

-- ---------------------------------------------------------------------------
-- 2) backtest_job → backtest_run
--
-- 테이블을 개명해도 이 테이블을 가리키는 FK 제약은 자동으로 따라온다(007 search_candidate.backtest_job_id,
-- param_search.baseline_job_id 포함). 그쪽 컬럼 이름은 007 소관이라 건드리지 않는다.
-- ---------------------------------------------------------------------------
ALTER TABLE backtest_job RENAME TO backtest_run;
ALTER SEQUENCE backtest_job_seq RENAME TO backtest_run_seq;

-- backtest_run.backtest_id 는 남겨 둔다. 최종 모델에서 사용자에게 보이는 식별자는 요청의 것이지만,
-- 접수·이벤트 경로가 아직 실행 하나를 요청 하나로 다루므로(T005 전) 지금 떼면 그 경로가 끊긴다.
-- T005 가 접수를 요청/실행으로 나누면서 이 컬럼을 지우고 backtest_request.backtest_id 만 남긴다.
--
-- 같은 이유로 strategy_version·parameters·instrument_ids·start_date·end_date·initial_cash·
-- position_sizing_type·max_position_pct·benchmark_instrument_id 도 남겨 둔다. 최종 모델은 이것들을
-- request_spec(canonical 요청) 하나로 접지만, 그 JSON 모양은 T005 의 REST 계약이 정한다.

-- 실행 식별·재사용 (R-02·R-03)
--
-- 아래 컬럼 중 접수 경로가 채우는 것(dedup_key, execution_type, rules_version, cost_profile_version,
-- data_version, request_spec, versions, effective_start/end)은 **T005 까지 nullable** 이다.
-- 지금 NOT NULL 로 두면 T003 이 값을 지어내야 하는데, 그 값의 정의(canonical 요청·동일 버전 키)는
-- T005 의 REST 계약이 정한다. T005 가 접수 경로를 붙이면서 NOT NULL 로 조인다.
--
-- dedup_key 는 data-model 의 CHAR(64) 대신 VARCHAR(64) 다. CHAR 는 짧은 값을 공백으로 채워
-- 비교·조회에서 조용한 차이를 만든다. 길이는 SHA-256 hex 그대로 64자다.
ALTER TABLE backtest_run ADD COLUMN dedup_key            VARCHAR(64);
ALTER TABLE backtest_run ADD COLUMN execution_type       VARCHAR(20);
ALTER TABLE backtest_run ADD COLUMN rules_version        VARCHAR(32);
ALTER TABLE backtest_run ADD COLUMN cost_profile_version VARCHAR(32);
ALTER TABLE backtest_run ADD COLUMN data_version         VARCHAR(64);
ALTER TABLE backtest_run ADD COLUMN request_spec         JSONB;
ALTER TABLE backtest_run ADD COLUMN versions             JSONB;
ALTER TABLE backtest_run ADD COLUMN effective_start      DATE;
ALTER TABLE backtest_run ADD COLUMN effective_end        DATE;

-- 진행 정도 (R-05). PROGRESS 이벤트가 갱신한다. 시계열은 저장하지 않는다.
ALTER TABLE backtest_run ADD COLUMN progress_stage             VARCHAR(20);
ALTER TABLE backtest_run ADD COLUMN progress_last_trading_date DATE;
ALTER TABLE backtest_run ADD COLUMN progress_completed_days    INTEGER;
ALTER TABLE backtest_run ADD COLUMN progress_total_days        INTEGER;

-- 실행 시도와 종결 (R-06·R-18).
-- data-model 은 SMALLINT 로 적었지만 INTEGER 로 둔다. Hibernate 의 ddl-auto=validate 는 Java int 를
-- INTEGER 로 기대하므로 SMALLINT 면 엔티티 필드를 전부 short 로 바꿔야 하고, 2바이트를 아끼려고
-- 도메인 타입을 비트는 것은 남는 장사가 아니다.
ALTER TABLE backtest_run ADD COLUMN attempt              INTEGER  NOT NULL DEFAULT 0;
ALTER TABLE backtest_run ADD COLUMN current_execution_id VARCHAR(255);
ALTER TABLE backtest_run ADD COLUMN partial              BOOLEAN  NOT NULL DEFAULT false;
ALTER TABLE backtest_run ADD COLUMN failure_code         VARCHAR(64);
ALTER TABLE backtest_run ADD COLUMN failure_message      TEXT;
ALTER TABLE backtest_run ADD COLUMN cancel_requested_at  TIMESTAMP WITH TIME ZONE;

-- 작업 발행 보장 (R-18). publish_count 는 confirm 수가 아니라 **발행 시도 수**로, 브로커에 있을 수
-- 있는 메시지 수의 상한이다. dlq_count >= publish_count 일 때만 SYSTEM_FAILURE 로 종결한다.
ALTER TABLE backtest_run ADD COLUMN published_at  TIMESTAMP WITH TIME ZONE;
ALTER TABLE backtest_run ADD COLUMN publish_count INTEGER  NOT NULL DEFAULT 0;
ALTER TABLE backtest_run ADD COLUMN dlq_count     INTEGER  NOT NULL DEFAULT 0;

-- 측정 원천 (R-10). queued_at 은 기존 행이 없으므로 created_at 기본값으로 채운다.
ALTER TABLE backtest_run ADD COLUMN queued_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE backtest_run ADD COLUMN started_at  TIMESTAMP WITH TIME ZONE;
ALTER TABLE backtest_run ADD COLUMN finished_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN backtest_run.dedup_key IS '동일 버전 키 SHA-256 hex(R-03). T005 가 채우고 NOT NULL 로 조인다';
COMMENT ON COLUMN backtest_run.publish_count IS '발행 시도 횟수(시도 직전 +1, confirm 무관). 메시지 수의 상한(R-18)';
COMMENT ON COLUMN backtest_run.attempt IS 'STARTED 마다 +1. publish_count 보다 크면 자동 재시도(R-06·R-18)';

-- 상태 집합에 CANCELLED 가 들어온다. 기존 스키마에 CHECK 가 없었으므로 여기서 처음 건다.
ALTER TABLE backtest_run ADD CONSTRAINT ck_backtest_run_status
    CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'));

ALTER TABLE backtest_run ADD CONSTRAINT fk_backtest_run_cost_profile
    FOREIGN KEY (cost_profile_version) REFERENCES cost_profile (version);

-- 복합 FK. 한쪽이라도 NULL 이면 MATCH SIMPLE 기본 동작으로 제약을 통과하므로,
-- rules_version 이 아직 없는 T003~T005 사이의 행도 들어간다.
ALTER TABLE backtest_run ADD CONSTRAINT fk_backtest_run_strategy_rules
    FOREIGN KEY (strategy_id, rules_version) REFERENCES strategy_rules (strategy_id, rules_version);

-- 동일 버전 중복 방지(R-02). 전체 유니크가 아니라 부분 유니크다 — 실패·취소한 실행은 같은 키로
-- 다시 실행할 수 있어야 하고, 완료한 실행만 재사용 대상이다.
CREATE UNIQUE INDEX uk_backtest_run_dedup_active
    ON backtest_run (dedup_key)
    WHERE status IN ('QUEUED', 'RUNNING', 'COMPLETED');

-- 대기열 조회와 재발행 조회(R-18·R-19)
CREATE INDEX idx_backtest_run_status_queued ON backtest_run (status, queued_at);
CREATE INDEX idx_backtest_run_republish ON backtest_run (status, published_at);

-- ---------------------------------------------------------------------------
-- 3) backtest_request: 사용자에게 보이는 요청 (R-02)
--
-- backtest_id(BT-…)는 이제 실행이 아니라 요청의 식별자다. 합류·재사용이면 여러 요청이 같은 run 을
-- 가리키고 각자 다른 backtest_id 를 받는다.
-- ---------------------------------------------------------------------------
CREATE SEQUENCE backtest_request_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE backtest_request (
    id                          BIGINT                   NOT NULL PRIMARY KEY,
    backtest_id                 VARCHAR(32)              NOT NULL UNIQUE,
    run_id                      BIGINT                   NOT NULL REFERENCES backtest_run (id),
    requester_id                BIGINT                   NOT NULL REFERENCES users (id),
    execution_type              VARCHAR(20)              NOT NULL,
    result_source               VARCHAR(10)              NOT NULL,
    retry_of_request_id         BIGINT                   REFERENCES backtest_request (id),
    cancelled_at                TIMESTAMP WITH TIME ZONE,
    cancelled_last_trading_date DATE,
    stopped_run                 BOOLEAN                  NOT NULL DEFAULT false,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_backtest_request_result_source
        CHECK (result_source IN ('NEW', 'JOINED', 'REUSED')),
    -- 취소하지 않은 요청에 취소 부산물이 남아 있으면 조회가 잘못된 범위를 보여준다.
    CONSTRAINT ck_backtest_request_cancel_fields
        CHECK (cancelled_at IS NOT NULL
            OR (cancelled_last_trading_date IS NULL AND stopped_run = false))
);

COMMENT ON COLUMN backtest_request.cancelled_last_trading_date IS
    '취소 시점의 run.progress_last_trading_date 스냅샷. NULL = 완료한 거래일 없음(첫 PROGRESS 전 취소). R-04';
COMMENT ON COLUMN backtest_request.stopped_run IS
    'true = 이 취소가 마지막 활성 요청이라 실행을 멈췄다. 그때는 완료 범위를 run 에서 읽는다(R-04)';

CREATE INDEX idx_backtest_request_requester ON backtest_request (requester_id, created_at DESC);
CREATE INDEX idx_backtest_request_run ON backtest_request (run_id);

-- ---------------------------------------------------------------------------
-- 4) 결과·거래의 FK를 실행으로 옮기고 v2 산출 항목을 더한다 (R-11)
-- ---------------------------------------------------------------------------
ALTER TABLE backtest_result RENAME COLUMN backtest_job_id TO run_id;
ALTER TABLE backtest_trade RENAME COLUMN backtest_job_id TO run_id;
ALTER INDEX idx_backtest_trade_job_entry RENAME TO idx_backtest_trade_run_entry;
ALTER TABLE backtest_trade RENAME CONSTRAINT uk_backtest_trade_job_trade TO uk_backtest_trade_run_trade;

-- 모두 표시용이라 JSONB 다(backend/CLAUDE.md 저장 규칙: 질의에 쓰는 값만 컬럼).
-- weights_source 만 스칼라다 — 사용자 입력 비중과 계산 비중을 구분해 표시하는 필터 대상이다(FR-026).
ALTER TABLE backtest_result ADD COLUMN weights_series JSONB;
ALTER TABLE backtest_result ADD COLUMN weights_source VARCHAR(10);
ALTER TABLE backtest_result ADD COLUMN costs          JSONB;
ALTER TABLE backtest_result ADD COLUMN liquidation    JSONB;
ALTER TABLE backtest_result ADD COLUMN analytics      JSONB;
ALTER TABLE backtest_result ADD COLUMN exceptions     JSONB;
ALTER TABLE backtest_result ADD COLUMN versions       JSONB;

ALTER TABLE backtest_result ADD CONSTRAINT ck_backtest_result_weights_source
    CHECK (weights_source IS NULL OR weights_source IN ('USER', 'COMPUTED'));
