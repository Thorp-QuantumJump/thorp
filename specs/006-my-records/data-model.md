# Data Model: 내 기록

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

Flyway 변경 1건(`V2026{...}__my_record.sql`)에 표 두 개를 만들고, 기존 표에는 값을 채우는 일만 한다.
**기존 컬럼을 지우거나 타입을 바꾸지 않는다** — 되돌릴 수 없는 변경이 없다(constitution 스키마 변경 규칙).

## 0. 무엇을 만들지 않는가

먼저 이것부터 적는다. 006이 다루는 값의 대부분은 이미 저장소에 있고 006은 그것을 가리킨다.

| 이미 있는 것 | 어디에 | 006이 하는 일 |
|---|---|---|
| 공용 계산 결과·거래 내역·예외 처리 기록 | `backtest_run`, `backtest_result`, `backtest_trade` | 가리켜 읽는다. 복제하지 않고 삭제하지도 않는다 |
| 재현 기록(FR-010) | `backtest_result.versions`, `backtest_request`, `cost_profile`, `strategy_rules`, `dataset_version` | 004 FR-028이 남긴 것을 그대로 보존한다. 새 재현 항목을 만들지 않는다(원칙 VIII) |
| 결과 열람 열쇠 | `backtest_request.public_token` (로그인 요청은 만료 없음) | '다시 보기' 링크로 쓴다(R3) |
| 탐색 묶음과 후보 | `param_search`, `search_candidate` | 가리켜 읽는다. `owner_user_id`를 채우는 일만 새로 한다 |
| 경제환경 스냅샷 | `regime_search_snapshot`, `regime_search_case` | 가리켜 읽는다. 공용 스냅샷이라 소유자 자리가 없다 |
| 섹터-ETF 대응표 버전·항목 | `sector_etf_map_version`, `sector_etf_map_entry` | UI-01 판정에 읽는다(R8) |
| 세션·사용자 | `spring_session`, `users` | 소유자 식별에만 쓴다. 식별정보를 복제하지 않는다 |

**감사 표를 새로 만들지 않는다.** FR-019의 시도·실패 감사는 `backtest_reuse_event`·
`backtest_run_dlq_attempt`·`param_search`의 상태 기록이 이미 담고 있다. 열람 이력은
`my_record.last_viewed_at` 한 값으로 두고 사용자 화면에 나열하지 않는다.

## 1. `my_record` — 개인 기록 항목

소유·이름·목록 자리만 담는다. 계산 결과는 담지 않는다.

| 컬럼 | 타입 | 제약 | 뜻 |
|---|---|---|---|
| `id` | BIGINT | PK, `my_record_seq` | 내부 식별자. API에 노출하지 않는다 |
| `record_id` | VARCHAR(32) | NOT NULL UNIQUE, `^MR-\d{8}-\d{6}$` | 사용자에게 보이는 식별자. `backtest_id`와 같은 방식이다(backend/CLAUDE.md: DB PK를 노출하지 않는다) |
| `owner_id` | BIGINT | NOT NULL → `users(id)` | 소유자. 이메일·닉네임은 복제하지 않는다 |
| `kind` | VARCHAR(20) | NOT NULL, `ANALYSIS`·`SEARCH_BUNDLE`·`REGIME_SEARCH` | 종류(FR-004 필터 축) |
| `analysis_request_id` | BIGINT | → `backtest_request(id)`, nullable | 단일 분석이 가리키는 요청. `public_token`을 여기서 읽는다 |
| `analysis_run_id` | BIGINT | → `backtest_run(id)`, nullable | 같은 조건 재분석 판정용(FR-009). 요청은 새로 생겨도 실행은 같다 |
| `param_search_id` | BIGINT | → `param_search(id)`, nullable | 탐색 묶음 |
| `regime_snapshot_id` | BIGINT | → `regime_search_snapshot(id)`, nullable | 경제환경 탐색 |
| `regime_entry_path` | VARCHAR(20) | nullable, `MARKET_FLOW`·`STRATEGY_RESULT` | 검색 경로. 경로가 다르면 별도 항목이다(FR-012 후단) |
| `name` | VARCHAR(120) | NOT NULL | 자동 부여 후 사용자가 바꿀 수 있다. 유일성을 강제하지 않는다(R12) |
| `sector_codes` | TEXT[] | NOT NULL | 목록 필터용 복제. 저장 뒤 바뀌지 않는다(R2) |
| `strategy_ids` | TEXT[] | NOT NULL | 같음. 탐색 묶음은 여러 개일 수 있다 |
| `period_start` | DATE | nullable | 분석 기간 시작. 경제환경 탐색은 NULL(기준월로 대신한다) |
| `period_end` | DATE | nullable | 분석 기간 끝 |
| `saved_at` | TIMESTAMPTZ | NOT NULL | 목록 기본 정렬 키 |
| `last_viewed_at` | TIMESTAMPTZ | nullable | 마지막 '다시 보기' 시각. 보관 기간을 나중에 도입할 수 있게 둔다(PD-13) |
| `deleted_at` | TIMESTAMPTZ | nullable | 개인 항목 삭제 표시. 공용 결과는 건드리지 않는다(R6) |
| `is_copy` | BOOLEAN | NOT NULL DEFAULT false | 같은 원본을 가리키는 별도 이름의 사본(Edge Cases) |
| `created_at` `updated_at` | TIMESTAMPTZ | NOT NULL | `BaseEntity` |

### 제약

```sql
-- 종류와 참조가 짝이 맞는다. 어긋나면 목록이 읽을 원본을 고를 수 없다.
CONSTRAINT ck_my_record_ref_matches_kind CHECK (
    (kind = 'ANALYSIS'       AND analysis_request_id IS NOT NULL AND analysis_run_id IS NOT NULL
                             AND param_search_id IS NULL AND regime_snapshot_id IS NULL
                             AND regime_entry_path IS NULL)
 OR (kind = 'SEARCH_BUNDLE'  AND param_search_id IS NOT NULL
                             AND analysis_request_id IS NULL AND analysis_run_id IS NULL
                             AND regime_snapshot_id IS NULL AND regime_entry_path IS NULL)
 OR (kind = 'REGIME_SEARCH'  AND regime_snapshot_id IS NOT NULL AND regime_entry_path IS NOT NULL
                             AND analysis_request_id IS NULL AND analysis_run_id IS NULL
                             AND param_search_id IS NULL))

-- 기간은 둘이 함께 있거나 함께 없다.
CONSTRAINT ck_my_record_period CHECK (
    (period_start IS NULL AND period_end IS NULL)
 OR (period_start IS NOT NULL AND period_end IS NOT NULL AND period_start <= period_end))
```

세 개의 nullable FK를 두고 종류로 짝을 맞추는 이유는, 참조 무결성을 DB가 지키게 하기 위해서다.
`ref_id BIGINT` 한 컬럼으로 종류별 대상을 가리키면 어느 표도 그 값을 보증하지 않는다.

### 인덱스

```sql
-- 목록 기본 경로: 소유자의 살아 있는 항목을 저장일 최신순으로. 커서 쪽 나누기(R14)가 이 인덱스를 탄다.
CREATE INDEX idx_my_record_list ON my_record (owner_id, saved_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- 섹터 필터.
CREATE INDEX idx_my_record_sectors ON my_record USING GIN (sector_codes);

-- 같은 조건 재분석은 새 항목을 만들지 않는다(FR-009). 사본은 예외다(R6).
CREATE UNIQUE INDEX uk_my_record_analysis ON my_record (owner_id, analysis_run_id)
    WHERE deleted_at IS NULL AND is_copy = false AND analysis_run_id IS NOT NULL;
CREATE UNIQUE INDEX uk_my_record_search ON my_record (owner_id, param_search_id)
    WHERE deleted_at IS NULL AND is_copy = false AND param_search_id IS NOT NULL;
-- 진입 경로가 다르면 같은 스냅샷도 별도 항목이다(Story 3 시나리오 4).
CREATE UNIQUE INDEX uk_my_record_regime
    ON my_record (owner_id, regime_snapshot_id, regime_entry_path)
    WHERE deleted_at IS NULL AND is_copy = false AND regime_snapshot_id IS NOT NULL;

-- 탐색 묶음 상한 판정(R11)이 세는 경로.
CREATE INDEX idx_my_record_kind_count ON my_record (owner_id, kind)
    WHERE deleted_at IS NULL;
```

### 상태는 컬럼이 아니다

목록의 상태(완료·진행 중·실패·취소·부분 완료)는 `my_record`에 없고 조인으로 읽는다(R2).

| 종류 | 상태 출처 |
|---|---|
| `ANALYSIS` | `backtest_run.status` + `backtest_request.cancelled_at`(이 요청만 취소했는지) |
| `SEARCH_BUNDLE` | `param_search.status` + `stop_reason` + `counts`(부분 완료 범위) |
| `REGIME_SEARCH` | `regime_search_snapshot.status` + `status_reason` |

## 2. `my_record_regime_link` — 경제환경 기록의 연결 정보

FR-012가 요구하는 "연결한 섹터·ETF·전략·사후 평가 구간"은 [011](../011-regime-strategy-link/spec.md)이
만들 값이고 011은 아직 spec만 있다. **자리를 만들고 값은 비운다**(R9).

| 컬럼 | 타입 | 제약 | 뜻 | 지금 채우나 |
|---|---|---|---|---|
| `record_id` | BIGINT | PK → `my_record(id)` | 경제환경 종류 항목 하나당 한 행 | 예 |
| `linked_record_id` | BIGINT | nullable → `my_record(id)` | 전략 결과에서 진입했다면 출발한 분석 기록 | 011 이후 |
| `linked_sector_codes` | TEXT[] | nullable | 연결한 섹터 | 011 이후 |
| `linked_instrument_ids` | TEXT[] | nullable | 연결한 대표 ETF | 011 이후 |
| `linked_strategy_ids` | TEXT[] | nullable | 연결해 비교한 전략 | 011 이후 |
| `evaluation_window` | JSONB | nullable | 사후 평가 구간 | 011 이후 |
| `etf_availability_filter` | JSONB | nullable | ETF 가용성 필터 적용 내용 | 011 이후 |
| `created_at` `updated_at` | TIMESTAMPTZ | NOT NULL | | 예 |

**nullable로 두는 것이 이 표의 요점이다.** 값이 없으면 화면은 "아직 연결 정보가 없다"를 보이고,
기본값이나 추정값으로 채우지 않는다(원칙 VI).

`linked_record_id`가 FR-017의 "참조 삭제 표시"가 걸리는 **유일한 자리**다. 그 기록이 삭제되면
(`deleted_at IS NOT NULL`) 이 행은 그대로 두고 읽을 때 삭제됨을 표시한다. 006은 기록 간 참조를
일반적으로 추적하는 장치를 만들지 않는다 — 005가 비교를 서버 자원으로 만들지 않기로 했으므로
(005 R-04) 분석 기록에 들어오는 참조는 현재 이 컬럼 말고 없다. 따라서 이 규칙은 구현하되 **011이
오기 전에는 발생 조건이 없다.** 구현했다는 사실을 검증됐다는 뜻으로 쓰지 않는다(constitution 완료의 정의).

## 3. 기존 표에 하는 일

| 표 | 변경 | 근거 |
|---|---|---|
| `param_search.owner_user_id` | 스키마 변경 없음. **접수 시 값을 채운다**(지금은 항상 NULL) | 컬럼 주석이 "소유자 필터는 006이 붙인다"로 이 일을 이미 예약해 두었다 |
| `backtest_request` | 변경 없음 | `requester_id`·`public_token`을 읽기만 한다 |
| `regime_search_snapshot` | 변경 없음 | 공용 스냅샷이다. 소유자 컬럼을 붙이지 않는다 |

## 4. 항목이 만들어지는 자리

| 언제 | 누가 | 무엇을 |
|---|---|---|
| 백테스트 접수(`POST /backtests`)가 로그인 요청일 때 | `MyRecordRegistrar` | 같은 `owner_id`+`run_id`의 살아 있는 항목을 먼저 찾는다. 있으면 그것을 돌려주고 새로 만들지 않는다(FR-009). 없으면 `ANALYSIS` 항목을 만든다 |
| 탐색 접수(`POST /param-searches`)가 로그인 요청일 때 | `MyRecordRegistrar` | 상한을 먼저 판정하고(R11) `SEARCH_BUNDLE` 항목을 만들며 `param_search.owner_user_id`를 채운다 |
| 경제환경 검색을 기록으로 남길 때 | `MyRecordRegistrar` | `REGIME_SEARCH` 항목을 `regime_entry_path`와 함께 만든다. 같은 스냅샷이라도 경로가 다르면 별도 행이다 |

비로그인 요청은 항목을 만들지 않는다. 진단 자체는 그대로 되고 저장이 필요한 지점에서 014의 로그인
안내를 부른다(FR-020).

## 5. 엔티티 상태 전이

`my_record` 자체의 상태는 두 가지뿐이다.

```
살아 있음 (deleted_at IS NULL) ──삭제 확인──▶ 삭제됨 (deleted_at 설정)
```

되돌아오는 화살표가 없다 — 복구 기능을 만들지 않는다(R6). 이름 변경은 상태 전이가 아니라 `name`
갱신이며 결과·참조 버전을 바꾸지 않는다(FR-017, SC 검증 대상).

계산의 상태 전이(`QUEUED → RUNNING → COMPLETED | FAILED | CANCELLED`)는 004 소유이며 006이 바꾸지
않는다. 006은 그 값을 읽어 보인다.
