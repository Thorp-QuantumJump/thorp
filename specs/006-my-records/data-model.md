# Data Model: 내 기록

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

Flyway 변경 1건(`V2026{...}__my_record.sql`)에 표 **하나**를 만들고, 기존 표에는 행을 더하는 일만 한다.
**기존 컬럼을 지우거나 타입을 바꾸지 않는다** — 되돌릴 수 없는 변경이 없다(constitution 스키마 변경 규칙).

## 0. 무엇을 만들지 않는가

먼저 이것부터 적는다. 006이 다루는 값의 대부분은 이미 저장소에 있고 006은 그것을 가리킨다.

| 이미 있는 것 | 어디에 | 006이 하는 일 |
|---|---|---|
| 공용 계산 결과·거래 내역·예외 처리 기록 | `backtest_run`, `backtest_result`, `backtest_trade` | 가리켜 읽는다. 복제하지 않고 삭제하지도 않는다 |
| 재현 기록(FR-010) | `backtest_result.versions`, `backtest_request`, `cost_profile`, `strategy_rules`, `dataset_version` | 004 FR-028이 남긴 것을 그대로 보존한다. 새 재현 항목을 만들지 않는다(원칙 VIII) |
| 결과 열람 열쇠 | `backtest_request.public_token` (로그인 요청은 만료 없음) | '다시 보기' 링크로 쓴다(R3) |
| 탐색 묶음과 후보 | `param_search`, `search_candidate` | 가리켜 읽는다. `owner_user_id`는 접수가 이미 채운다(`ParamSearchService`가 `command.requesterId()`를 넣는다) — 006이 할 일이 없다 |
| 경제환경 스냅샷 | `regime_search_snapshot`, `regime_search_case` | 가리켜 읽는다. 공용 스냅샷이라 소유자 자리가 없다 |
| 경제환경 개인 검색 기록 | `regime_search_log` (owner_user_id·snapshot_id·snapshot_key·entry_path·baseline_origin·viewed_at) | **010이 표를 만들고 "행은 006이 만든다"고 예약해 두었다**(`RegimeSearchLogJpaEntity` 주석, 010 FR-032). 006이 행을 쓴다. 표를 새로 만들지 않는다 |
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
| `regime_log_id` | BIGINT | → `regime_search_log(id)`, nullable | 경제환경 탐색. 검색 경로·기준월 근거·스냅샷 참조는 그 행에 있다. 경로가 다르면 로그 행이 다르므로 별도 항목이 된다(FR-012 후단) |
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
                             AND param_search_id IS NULL AND regime_log_id IS NULL)
 OR (kind = 'SEARCH_BUNDLE'  AND param_search_id IS NOT NULL
                             AND analysis_request_id IS NULL AND analysis_run_id IS NULL
                             AND regime_log_id IS NULL)
 OR (kind = 'REGIME_SEARCH'  AND regime_log_id IS NOT NULL
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
-- 로그 행 하나에 항목 하나. 진입 경로가 다르면 로그 행이 다르므로 별도 항목이다(Story 3 시나리오 4).
CREATE UNIQUE INDEX uk_my_record_regime ON my_record (owner_id, regime_log_id)
    WHERE deleted_at IS NULL AND is_copy = false AND regime_log_id IS NOT NULL;

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
| `REGIME_SEARCH` | `regime_search_log.snapshot_id` → `regime_search_snapshot.status` + `status_reason`. `snapshot_id`가 NULL이면 010 FR-035의 **철회**라 '더 볼 수 없음'으로 보인다 |

## 2. 경제환경 연결 정보는 006이 만들지 않는다

FR-012가 요구하는 "연결한 섹터·ETF·전략·사후 평가 구간·ETF 가용성 필터"는 [011](../011-regime-strategy-link/spec.md)이
만들 값이고, 010 FR-032도 "그 두 항목은 011 FR-004·FR-023이 채운다"고 명시했다. 011은 아직 spec만 있다.

**006은 그 자리를 미리 만들지 않는다.** 처음 안은 nullable 컬럼을 가진 표를 하나 더 두는 것이었으나,
011이 자기 표를 새 Flyway 변경으로 더하면 006의 스키마를 고칠 일이 없으므로 미리 만들 이유가 없다.
화면은 연결 정보가 없을 때 "아직 연결 정보가 없다"를 보이고, 기본값이나 추정값으로 채우지 않는다(원칙 VI,
spec FR-012).

FR-017의 "참조 삭제 표시"가 걸릴 자리도 따라서 **현재 006 안에는 없다.** 005가 비교를 서버 자원으로
만들지 않으므로(005 R-04) 분석 기록에 들어오는 참조는 011이 만들기 전까지 존재하지 않는다. 표시 규칙은
읽기 경로에 두되 발생 조건이 아직 없다는 사실을 quickstart에 적는다.

## 3. 기존 표에 하는 일

| 표 | 변경 | 근거 |
|---|---|---|
| `param_search.owner_user_id` | 변경 없음 | 접수가 이미 채운다. 컬럼 주석("소유자 필터는 006이 붙인다")은 007 구현 뒤 남은 옛 문장이라 이번에 고친다 |
| `regime_search_log` | 스키마 변경 없음. **행을 쓴다** | 010이 표와 읽기 리포지토리(`isWithdrawn`)만 만들고 쓰는 쪽은 006에 맡겼다 |
| `backtest_request` | 변경 없음 | `requester_id`·`public_token`을 읽기만 한다 |
| `regime_search_snapshot` | 변경 없음 | 공용 스냅샷이다. 소유자 컬럼을 붙이지 않는다 |

## 4. 항목이 만들어지는 자리

| 언제 | 누가 | 무엇을 |
|---|---|---|
| 백테스트 접수(`POST /backtests`)가 로그인 요청이고 `executionType`이 단건(SINGLE)일 때 | `MyRecordRegistrar` (004가 발행하는 접수 이벤트를 `BEFORE_COMMIT`에 듣는다) | 같은 `owner_id`+`run_id`의 살아 있는 항목을 먼저 찾는다. 있으면 그것을 돌려주고 새로 만들지 않는다(FR-009). 없으면 `ANALYSIS` 항목을 만든다 |
| 탐색 접수(`POST /param-searches`) | `MyRecordRegistrar` (007의 접수 이벤트를 듣는다) | `SEARCH_BUNDLE` 항목을 만든다. 탐색 후보가 만드는 백테스트 요청(`executionType = SEARCH_BUNDLE`)은 `ANALYSIS` 항목을 만들지 않는다 — 묶음 항목이 그것을 대표한다. 상한 판정(R11)은 이벤트가 아니라 접수 **전**에 007 서비스가 `MyRecordLimitPolicy`를 불러 한다 |
| 로그인 사용자가 경제환경 검색 결과를 열었을 때(`POST /my-records/regime-searches`) | `MyRecordRegistrar` | `regime_search_log` 행(소유자·스냅샷·진입 경로·기준월 근거)과 `REGIME_SEARCH` 항목을 **같은 트랜잭션**에 만든다. 010의 `GET /regime-searches`는 손대지 않는다 — 진입 경로는 조회한 화면만 알고, 조회(GET)에 쓰기 부작용을 붙이지 않는다 |

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
