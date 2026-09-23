-- spec 011 경제환경 사례와 전략 비교 연결 (data-model.md §2~§5).
-- 011은 표 다섯을 만들고 010(regime_search_*)·004(backtest_*) 표는 읽기만 한다.
--
-- 요청 참조(base_request_id·compare_request_id·origin_request_id·created_request_id)는 전부
-- ON DELETE SET NULL 이다. 005 정리 작업이 만료된 비로그인 요청 행을 지워도 연결 기록이 남아야
-- 하고, 그 정리가 011 때문에 막히면 안 된다(data-model §3·§4a·§9).

-- PK는 SEQUENCE다. persist() 시점에 PK가 나와야 그 값으로 외부 식별자(RL-…·RLS-…)를
-- INSERT 전에 채울 수 있다(backtest_run_seq 와 같은 이유).
CREATE SEQUENCE regime_link_filter_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE regime_link_search_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE regime_link_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE regime_link_use_seq START WITH 1 INCREMENT BY 1;

-- 가용성 필터 (data-model §2). 같은 비교 묶음·같은 자료면 같은 행을 재사용한다.
-- definition 에는 성과에 의존하는 값을 넣지 않는다(FR-003) — 기존 실행의 기간도 넣지 않는다.
CREATE TABLE regime_link_filter (
    id              BIGINT      NOT NULL DEFAULT nextval('regime_link_filter_seq'),
    filter_key      CHAR(64)    NOT NULL,
    definition      JSONB       NOT NULL,
    available_range JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_regime_link_filter PRIMARY KEY (id),
    CONSTRAINT uk_regime_link_filter_key UNIQUE (filter_key)
);

-- 검색 경로별 연결 기록 (data-model §3). 목록을 확정할 때 만들고 0건이어도 저장한다.
-- 사용자 식별자는 기록 성격의 이 표에만 둔다(원칙 VIII).
CREATE TABLE regime_link_search (
    id                 BIGINT       NOT NULL DEFAULT nextval('regime_link_search_seq'),
    search_id          VARCHAR(32)  NOT NULL,
    owner_user_id      BIGINT       NOT NULL,
    entry_path         VARCHAR(24)  NOT NULL,
    snapshot_id        BIGINT,
    snapshot_key       VARCHAR(200) NOT NULL,
    mode               VARCHAR(16)  NOT NULL,
    field_id           VARCHAR(32),
    baseline_month     DATE         NOT NULL,
    base_run_id        BIGINT       NOT NULL,
    compare_run_id     BIGINT,
    base_request_id    BIGINT,
    compare_request_id BIGINT,
    filter_id          BIGINT,
    shown_cases        JSONB        NOT NULL,
    excluded_count     SMALLINT     NOT NULL,
    full_search_shown  BOOLEAN      NOT NULL DEFAULT FALSE,
    previous_search_id BIGINT,
    retention          JSONB,
    basis              VARCHAR(24)  NOT NULL,
    client_request_id  VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_regime_link_search PRIMARY KEY (id),
    CONSTRAINT uk_regime_link_search_id UNIQUE (search_id),
    -- 요청자별 검색 생성 멱등 키(REST §1b). 같은 본문 재시도는 기존 기록을 되돌린다.
    CONSTRAINT uk_regime_link_search_client_request
        UNIQUE (owner_user_id, client_request_id),
    -- 010 snapshot 철회로 행이 사라져도 snapshot_key 사본으로 "무엇을 봤나"는 남는다(010 FR-035).
    CONSTRAINT fk_regime_link_search_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES regime_search_snapshot (id) ON DELETE SET NULL,
    CONSTRAINT fk_regime_link_search_base_run FOREIGN KEY (base_run_id)
        REFERENCES backtest_run (id),
    CONSTRAINT fk_regime_link_search_compare_run FOREIGN KEY (compare_run_id)
        REFERENCES backtest_run (id),
    CONSTRAINT fk_regime_link_search_base_request FOREIGN KEY (base_request_id)
        REFERENCES backtest_request (id) ON DELETE SET NULL,
    CONSTRAINT fk_regime_link_search_compare_request FOREIGN KEY (compare_request_id)
        REFERENCES backtest_request (id) ON DELETE SET NULL,
    CONSTRAINT fk_regime_link_search_filter FOREIGN KEY (filter_id)
        REFERENCES regime_link_filter (id),
    CONSTRAINT fk_regime_link_search_previous FOREIGN KEY (previous_search_id)
        REFERENCES regime_link_search (id),
    CONSTRAINT ck_regime_link_search_entry_path
        CHECK (entry_path IN ('MARKET_FLOW', 'STRATEGY_RESULT')),
    CONSTRAINT ck_regime_link_search_mode CHECK (mode IN ('FIELD', 'COMPOSITE')),
    CONSTRAINT ck_regime_link_search_month CHECK (EXTRACT(DAY FROM baseline_month) = 1),
    CONSTRAINT ck_regime_link_search_excluded CHECK (excluded_count >= 0),
    -- STRATEGY_RESULT 는 공통 가용 범위 필터를 걸고, MARKET_FLOW 는 걸지 않는다(FR-002).
    CONSTRAINT ck_regime_link_search_filter_required
        CHECK (entry_path <> 'STRATEGY_RESULT' OR filter_id IS NOT NULL)
);

CREATE INDEX idx_regime_link_search_owner
    ON regime_link_search (owner_user_id, created_at DESC);
CREATE INDEX idx_regime_link_search_base_run ON regime_link_search (base_run_id);
CREATE INDEX idx_regime_link_search_snapshot_key ON regime_link_search (snapshot_key);

-- (실행 × 사례 × 시나리오) 계산 하나 (data-model §4).
-- 여러 사용자·여러 검색이 공유하므로 사용자 식별자와 요청 문맥을 두지 않는다(research R14).
CREATE TABLE regime_link (
    id             BIGINT       NOT NULL DEFAULT nextval('regime_link_seq'),
    link_id        VARCHAR(32)  NOT NULL,
    run_id         BIGINT       NOT NULL,
    snapshot_key   VARCHAR(200) NOT NULL,
    end_month      DATE         NOT NULL,
    scenario       VARCHAR(16)  NOT NULL,
    post_window    JSONB        NOT NULL,
    availability   JSONB        NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    failure        JSONB,
    job_message_id VARCHAR(64),
    attempt        SMALLINT     NOT NULL DEFAULT 0,
    queued_at      TIMESTAMPTZ,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_regime_link PRIMARY KEY (id),
    CONSTRAINT uk_regime_link_link_id UNIQUE (link_id),
    -- 같은 단위는 다시 계산하지 않는다(FR-010).
    CONSTRAINT uk_regime_link_unit UNIQUE (run_id, snapshot_key, end_month, scenario),
    CONSTRAINT fk_regime_link_run FOREIGN KEY (run_id) REFERENCES backtest_run (id),
    CONSTRAINT ck_regime_link_scenario CHECK (scenario IN ('CONTINUOUS', 'FRESH_CASH')),
    CONSTRAINT ck_regime_link_status
        CHECK (status IN ('PENDING_RUN', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_regime_link_month CHECK (EXTRACT(DAY FROM end_month) = 1),
    CONSTRAINT ck_regime_link_attempt CHECK (attempt >= 0)
);

CREATE INDEX idx_regime_link_run ON regime_link (run_id);
CREATE INDEX idx_regime_link_case ON regime_link (snapshot_key, end_month);
CREATE INDEX idx_regime_link_status ON regime_link (status);

-- 누가 어느 요청으로 그 계산을 열었나 (data-model §4a).
-- 공개 토큰·소유자·만료가 요청에 속하므로 기록 귀속은 여기서 정해진다.
CREATE TABLE regime_link_use (
    id                 BIGINT      NOT NULL DEFAULT nextval('regime_link_use_seq'),
    link_search_id     BIGINT      NOT NULL,
    link_id            BIGINT      NOT NULL,
    role               VARCHAR(8)  NOT NULL,
    origin_run_id      BIGINT      NOT NULL,
    origin_request_id  BIGINT,
    created_request_id BIGINT,
    created_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_regime_link_use PRIMARY KEY (id),
    CONSTRAINT uk_regime_link_use UNIQUE (link_search_id, link_id),
    CONSTRAINT fk_regime_link_use_search FOREIGN KEY (link_search_id)
        REFERENCES regime_link_search (id),
    CONSTRAINT fk_regime_link_use_link FOREIGN KEY (link_id) REFERENCES regime_link (id),
    CONSTRAINT fk_regime_link_use_origin_run FOREIGN KEY (origin_run_id)
        REFERENCES backtest_run (id),
    CONSTRAINT fk_regime_link_use_origin_request FOREIGN KEY (origin_request_id)
        REFERENCES backtest_request (id) ON DELETE SET NULL,
    CONSTRAINT fk_regime_link_use_created_request FOREIGN KEY (created_request_id)
        REFERENCES backtest_request (id) ON DELETE SET NULL,
    CONSTRAINT ck_regime_link_use_role CHECK (role IN ('BASE', 'COMPARE'))
);

CREATE INDEX idx_regime_link_use_link ON regime_link_use (link_id);
CREATE INDEX idx_regime_link_use_origin_request ON regime_link_use (origin_request_id);

-- 워커 산출물 (data-model §5). 링크가 COMPLETED 일 때만 있고 링크와 같이 산다.
-- 수치는 전부 문자열 decimal 이며 NUMERIC 컬럼으로 내리지 않는다(backend/CLAUDE.md 저장 규칙).
CREATE TABLE regime_link_result (
    link_id      BIGINT      NOT NULL,
    window63     JSONB       NOT NULL,
    window21     JSONB       NOT NULL,
    holdings     JSONB       NOT NULL,
    trades       JSONB       NOT NULL,
    kospi        JSONB       NOT NULL,
    basis        JSONB       NOT NULL,
    input_digest CHAR(64)    NOT NULL,
    computed_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_regime_link_result PRIMARY KEY (link_id),
    CONSTRAINT fk_regime_link_result_link FOREIGN KEY (link_id)
        REFERENCES regime_link (id) ON DELETE CASCADE
);
