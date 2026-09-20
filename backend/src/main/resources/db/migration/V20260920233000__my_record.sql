-- 내 기록의 개인 항목 (spec 006 data-model §1).
--
-- 이 표는 **소유·이름·목록 자리만** 담는다. 계산 결과·거래 내역·재현 기록은 004·007·010이 이미 가진
-- 것을 가리킬 뿐 복제하지 않는다(원칙 VIII).
--
-- 왜 표를 따로 두는가: 2026-09-18 명료화가 "삭제는 개인 기록 항목만, 공용 계산 결과는 유지"로 정했다
-- (FR-017). 삭제 단위와 계산 단위가 다르면 표도 달라야 한다. backtest_run 은 공용 계산이고
-- backtest_request 는 요청자별 요청이며 regime_search_snapshot 은 content_hash 로 중복을 막은 공용
-- 스냅샷이라 소유자 자리가 아예 없다.
--
-- 상태(완료·진행 중·실패·취소·부분 완료)는 여기에 없다. 워커 이벤트로 계속 바뀌는 값이라 복제하면
-- 갱신 누락이 "실패한 분석이 목록에서는 완료로 보이는" 모습으로 드러난다(원칙 VI, research R2).
-- 복제하는 것은 접수 시점에 정해져 이후 바뀌지 않는 조건(섹터·전략·분석 기간)뿐이고 목록의
-- 정렬·필터에만 쓴다.

-- PK는 SEQUENCE다 — persist() 시점에 PK가 나와야 그 값으로 record_id 를 INSERT 전에 채울 수 있다
-- (param_search·backtest_run 과 같은 방식).
CREATE SEQUENCE my_record_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE my_record (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    -- 사용자에게 보이는 식별자. DB PK는 노출하지 않는다(ADR-0002 D2).
    record_id           VARCHAR(32)  NOT NULL,
    -- 소유자. 이메일·닉네임·전화번호를 복제하지 않는다(constitution "기록·개인정보·보관").
    owner_id            BIGINT       NOT NULL REFERENCES users (id),
    kind                VARCHAR(20)  NOT NULL,
    -- 종류별 원본 참조. 세 개를 nullable FK로 두고 kind로 짝을 맞춘다 — ref_id 한 컬럼으로 종류별
    -- 대상을 가리키면 어느 표도 그 값을 보증하지 않는다.
    analysis_request_id BIGINT       REFERENCES backtest_request (id),
    analysis_run_id     BIGINT       REFERENCES backtest_run (id),
    param_search_id     BIGINT       REFERENCES param_search (id),
    regime_log_id       BIGINT       REFERENCES regime_search_log (id),
    -- 저장 시 자동 부여하고 사용자가 바꿀 수 있다. 유일성을 강제하지 않는다(FR-003, research R12).
    name                VARCHAR(120) NOT NULL,
    -- 목록 필터용 복제. 접수 시점에 정해져 이후 바뀌지 않는다(research R2).
    sector_codes        TEXT[]       NOT NULL,
    strategy_ids        TEXT[]       NOT NULL,
    -- 분석 기간. 경제환경 탐색은 NULL이다(기준월이 그 자리를 대신한다).
    period_start        DATE,
    period_end          DATE,
    saved_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    -- 마지막 '다시 보기' 시각. 보관 기간(PD-13)을 나중에 도입할 수 있게 지금 자리를 둔다.
    -- **시간 기반 자동 삭제는 만들지 않는다**(2026-09-18 명료화).
    last_viewed_at      TIMESTAMP WITH TIME ZONE,
    -- 개인 항목 삭제 표시. 공용 결과는 건드리지 않는다(FR-017, research R6).
    deleted_at          TIMESTAMP WITH TIME ZONE,
    -- 같은 원본을 가리키는 별도 이름의 사본. 이번 범위에서 만들지 않지만 저장 구조가 막지 않게 둔다.
    is_copy             BOOLEAN      NOT NULL DEFAULT false,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uk_my_record_record_id UNIQUE (record_id),
    CONSTRAINT ck_my_record_record_id_format CHECK (record_id ~ '^MR-[0-9]{8}-[0-9]{6,}$'),
    CONSTRAINT ck_my_record_kind
        CHECK (kind IN ('ANALYSIS', 'SEARCH_BUNDLE', 'REGIME_SEARCH')),

    -- 종류와 참조가 짝이 맞는다. 어긋나면 목록이 읽을 원본을 고를 수 없다.
    CONSTRAINT ck_my_record_ref_matches_kind CHECK (
        (kind = 'ANALYSIS'      AND analysis_request_id IS NOT NULL AND analysis_run_id IS NOT NULL
                                AND param_search_id IS NULL AND regime_log_id IS NULL)
     OR (kind = 'SEARCH_BUNDLE' AND param_search_id IS NOT NULL
                                AND analysis_request_id IS NULL AND analysis_run_id IS NULL
                                AND regime_log_id IS NULL)
     OR (kind = 'REGIME_SEARCH' AND regime_log_id IS NOT NULL
                                AND analysis_request_id IS NULL AND analysis_run_id IS NULL
                                AND param_search_id IS NULL)),

    -- 기간은 둘이 함께 있거나 함께 없다.
    CONSTRAINT ck_my_record_period CHECK (
        (period_start IS NULL AND period_end IS NULL)
     OR (period_start IS NOT NULL AND period_end IS NOT NULL AND period_start <= period_end))
);

-- 목록 기본 경로: 소유자의 살아 있는 항목을 저장일 최신순으로. 커서 쪽 나누기(research R14)가 이 인덱스를 탄다.
CREATE INDEX idx_my_record_list ON my_record (owner_id, saved_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- 섹터 필터. 목록이 겹치는 코드 하나라도 있으면 포함으로 읽는다(&&).
CREATE INDEX idx_my_record_sectors ON my_record USING GIN (sector_codes);

-- 같은 조건 재분석은 새 항목을 만들지 않는다(FR-009). 사본과 삭제된 항목은 예외다(research R6).
CREATE UNIQUE INDEX uk_my_record_analysis ON my_record (owner_id, analysis_run_id)
    WHERE deleted_at IS NULL AND is_copy = false AND analysis_run_id IS NOT NULL;
CREATE UNIQUE INDEX uk_my_record_search ON my_record (owner_id, param_search_id)
    WHERE deleted_at IS NULL AND is_copy = false AND param_search_id IS NOT NULL;
-- 로그 행 하나에 항목 하나. 진입 경로가 다르면 로그 행이 다르므로 별도 항목이 된다(Story 3 시나리오 4).
CREATE UNIQUE INDEX uk_my_record_regime ON my_record (owner_id, regime_log_id)
    WHERE deleted_at IS NULL AND is_copy = false AND regime_log_id IS NOT NULL;

-- 탐색 묶음 저장 상한 판정(research R11)이 세는 경로.
CREATE INDEX idx_my_record_kind_count ON my_record (owner_id, kind)
    WHERE deleted_at IS NULL;

-- 010 의 표에 제약 하나를 더한다. 표는 010 이 만들었지만 "행은 006 이 만든다"고 예약해 두었고(FR-032),
-- 그 규칙이 "같은 사용자·같은 검색·같은 진입 경로면 한 행"이다(spec 006 research R15). 조회한 뒤 없으면
-- 넣는 방식은 같은 검색을 동시에 두 번 열면 기록이 둘이 되고, 그러면 목록에 같은 항목이 두 줄로 남는다 —
-- uk_my_record_regime 은 서로 다른 로그 행을 가리키는 두 항목을 막지 못한다.
--
-- 지금 이 표는 비어 있다(행을 쓰는 코드가 없었다). 기존 행이 위반하게 되는 변경이 아니다.
CREATE UNIQUE INDEX uk_regime_log_owner_key_path
    ON regime_search_log (owner_user_id, snapshot_key, entry_path);

COMMENT ON TABLE my_record IS
    '내 기록의 개인 항목(spec 006). 계산 결과를 가리킬 뿐 복제하지 않고, 삭제는 deleted_at 만 채운다';
COMMENT ON COLUMN my_record.deleted_at IS
    '개인 항목 삭제 표시(FR-017). 이 값이 차도 backtest_run·backtest_result·param_search·regime_search_log 는 그대로다';

-- V3 의 옛 문장을 지금 사실로 바꾼다. 적용된 마이그레이션은 고치지 않으므로 여기서 주석만 갱신한다
-- (constitution 스키마 변경 규칙).
COMMENT ON COLUMN param_search.owner_user_id IS
    '접수 요청자(users.id). 007 접수가 채우고 006 내 기록이 소유자 필터에 쓴다. 비로그인 접수는 없다.';
COMMENT ON INDEX idx_param_search_owner_created IS
    '006 내 기록의 목록 조회. owner_user_id 는 007 접수가 채운다.';
