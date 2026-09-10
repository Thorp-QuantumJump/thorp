-- 자동 파라미터 대량 탐색(spec 007). ParamSearchJpaEntity / SearchCandidateJpaEntity 기준.
-- V1·V2와 같이 PostgreSQL 문법을 그대로 쓴다(운영·로컬·테스트 모두 PostgreSQL).
--
-- 저장 규칙(V2 규약을 그대로 따른다): 질의(필터·정렬·조인)에 쓰는 값만 컬럼, 표시용 값은 JSONB.
-- decimal은 예외 없이 JSONB다. NUMERIC 컬럼은 scale을 임의로 정해야 하고 그 자릿수를 넘는 값을
-- 조용히 반올림하지만, JSON은 계약이 준 값과 scale을 그대로 보존한다.
--
-- 성과 숫자를 여기에 복제하지 않는다. 후보의 수익·하락 폭·거래비용은 backtest_result가 원본이고
-- search_candidate.backtest_job_id가 그것을 가리킨다. 복제하면 004가 결과를 재저장할 때 두 값이 갈린다.

-- 탐색 묶음. PK는 SEQUENCE다 — persist() 시점에 PK가 나와야 그 값으로 search_id를
-- INSERT 전에 채울 수 있다(backtest_job과 같은 방식).
CREATE SEQUENCE param_search_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE param_search (
    id                   BIGINT                   NOT NULL PRIMARY KEY,
    search_id            VARCHAR(32)              NOT NULL UNIQUE,
    -- 인증 도입 전이라 NULL이다. 컬럼만 미리 두고 소유자 필터는 006이 붙인다.
    owner_user_id        BIGINT,
    strategy_ids         JSONB                    NOT NULL,
    instrument_ids       JSONB                    NOT NULL,
    -- 임시 등록표 구현은 'PROVISIONAL-*'를 넣는다. PD-02 확정 등록표의 결과가 아님을 값 자체가 밝힌다.
    registry_version     VARCHAR(64)              NOT NULL,
    -- 전략 파라미터 축. 재현 기록(FR-020)의 탐색 공간이다.
    search_space         JSONB                    NOT NULL,
    -- 평가 시나리오 축. 전략 파라미터 축과 별도 컬럼이다(FR-021).
    -- 경제환경 검색 설정 축은 010 소유라 여기에 만들지 않는다.
    evaluation_split     JSONB,
    search_method        VARCHAR(32)              NOT NULL,
    -- 무작위 표본을 도입하면 채운다. 현재 탐색 방법이 FULL_GRID뿐이라 항상 NULL이다.
    random_seed          INTEGER,
    -- 접수 시점 스냅샷이며 이후 바뀌지 않는다(FR-013c, 원칙 VII). 절대 문턱 숫자를 담지 않는다.
    selection_rule       JSONB                    NOT NULL,
    -- 적용 한도 스냅샷과 임시값 표시. 확정 전 값으로 돈 결과임을 결과에서 되짚을 수 있어야 한다.
    limits               JSONB                    NOT NULL,
    -- 시작 시점의 자료·등록표·비용·엔진 버전(FR-012).
    reference_versions   JSONB                    NOT NULL,
    -- 동일 비중 기준 Job(FR-013d). 하락 폭 필터의 기준값이 여기서 나온다.
    baseline_job_id      BIGINT REFERENCES backtest_job (id),
    status               VARCHAR(20)              NOT NULL,
    stop_reason          VARCHAR(32),
    -- 예상·유효·완료·실패·제외·미실행(FR-008). 여섯 값이 함께 갱신되고 개별 필터·정렬 대상이
    -- 아니라 한 덩이로 둔다.
    counts               JSONB                    NOT NULL,
    -- 선정 결과 스냅샷. 완료·부분 완료 후 1회 계산해 저장한다.
    selection            JSONB,
    data_version_changed BOOLEAN                  NOT NULL,
    started_at           TIMESTAMP WITH TIME ZONE,
    finished_at          TIMESTAMP WITH TIME ZONE,
    created_at           TIMESTAMP                NOT NULL,
    updated_at           TIMESTAMP                NOT NULL
);

-- 탐색 후보. 묶음과 1:N이며 후보 1건 = 백테스트 Job 1건이다.
-- 시퀀스 증분은 엔티티의 allocationSize(50)와 같아야 한다. 후보는 묶음당 수백~천 건이다.
CREATE SEQUENCE search_candidate_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE search_candidate (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    param_search_id    BIGINT       NOT NULL REFERENCES param_search (id),
    candidate_no       INTEGER      NOT NULL,
    strategy_id        VARCHAR(64)  NOT NULL,
    parameters         JSONB        NOT NULL,
    -- 축 이름 → 등록표 인덱스. 이웃 판정(FR-013a)의 근거이며 재현 기록의 일부다.
    grid_coordinates   JSONB        NOT NULL,
    -- 발행 전·제외·미실행 후보는 NULL이다. 계산하지 않은 후보도 사유와 함께 남는다(FR-020).
    backtest_job_id    BIGINT REFERENCES backtest_job (id),
    status             VARCHAR(20)  NOT NULL,
    reason_code        VARCHAR(64),
    reason_detail      TEXT,
    -- 안정성 값·이웃 수·calculable 여부. 이웃이 없으면 calculable=false로 남기고 0으로 채우지
    -- 않는다(FR-013b, 원칙 VI) — 0은 "매우 안정적"으로 읽혀 계산 불가와 정반대다.
    stability          JSONB,
    retry_count        INTEGER      NOT NULL,
    -- 구간별(내부 개발·외부 평가·잠금) 성과 자리. 평가 분할이 미등록이라 현재 NULL이다.
    period_performance JSONB,
    -- 현재 전부 UNVALIDATED다. 평가 분할(PD-12)이 미확정이라 검증 여부를 말할 수 없다.
    validation_label   VARCHAR(24)  NOT NULL,
    selected           BOOLEAN      NOT NULL,
    -- 선정·탈락 사유(어느 필터에서 탈락했는지, 동률 여부).
    selection_reason   JSONB,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    CONSTRAINT uk_search_candidate_search_no UNIQUE (param_search_id, candidate_no)
);

-- 006 내 기록의 목록 조회 대비. 인증 도입 전에는 owner_user_id가 NULL이라 쓰이지 않는다.
CREATE INDEX idx_param_search_owner_created ON param_search (owner_user_id, created_at DESC);

-- counts 집계와 다음 발행 대상 조회. 발행 창이 매번 "이 묶음의 PENDING 후보"를 찾는다.
CREATE INDEX idx_search_candidate_search_status ON search_candidate (param_search_id, status);

-- 표시 후보 조회. 결과 화면이 선정된 소수 후보만 읽는다.
CREATE INDEX idx_search_candidate_search_selected ON search_candidate (param_search_id, selected);

-- Job 하나가 두 후보에 붙지 않게 한다. NULL은 여러 행이 가질 수 있어(PostgreSQL UNIQUE 규칙)
-- 발행 전·제외·미실행 후보가 서로 충돌하지 않는다.
CREATE UNIQUE INDEX uk_search_candidate_job ON search_candidate (backtest_job_id);
