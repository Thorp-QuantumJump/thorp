# Data Model: 백테스트 실행 (Phase 1)

**Spec**: [spec.md](spec.md) Key Entities | **Decisions**: [research.md](research.md) R-02·03·05·06·07·08·10·11

저장 규칙(backend/CLAUDE.md 유지): 조회·필터·조인에 쓰는 값만 컬럼, 소수·시계열·구조는 JSONB. 시각은 TIMESTAMPTZ, 거래일은 DATE. 기존 `backtest_job` → `backtest_run` 개명은 V3 마이그레이션에서 한다.

## 엔티티 대응

| spec Key Entity | 테이블 |
|---|---|
| 분석 요청 | `backtest_request` (+ 요청 원문은 `backtest_run.request_spec`) |
| 실행 상태 | `backtest_run.status/progress_*` + `backtest_request.cancelled_at` |
| 동일 결과 식별 키 | `backtest_run.dedup_key` |
| 백테스트 결과 | `backtest_result` |
| 거래 내역 | `backtest_trade` |
| 비용 조건(버전) | `cost_profile` |
| 전략 규칙(버전) | `strategy_rules` |
| 예외 처리 기록 | `backtest_result.exceptions` |
| 실행 측정 기록 | `backtest_run_metric`, `backtest_reuse_event`, 뷰 `v_run_metric_summary` |

## backtest_request (분석 요청)

| 컬럼 | 타입 | 규칙 |
|---|---|---|
| id | BIGINT PK (seq) | |
| backtest_id | VARCHAR(32) UNIQUE NOT NULL | `BT-{yyyyMMdd}-{id %06d}` (현행 규칙 유지, 사용자에게 보이는 식별자) |
| run_id | BIGINT FK → backtest_run NOT NULL | 합류·재사용 시 기존 run |
| requester_id | BIGINT FK → users NULL 허용 | 취소·재시도 권한 주체. 계산 기록에는 복사하지 않는다. NULL = 비로그인 요청(spec 005 B01, 결과는 공개 토큰으로만 연다) |
| public_token | VARCHAR(32) NOT NULL UNIQUE | 결과 공개 토큰(spec 005 FR-019). 소문자 16진 32자 |
| public_token_expires_at | TIMESTAMPTZ NULL | 비로그인 요청만 채운다. `requester_id IS NOT NULL OR public_token_expires_at IS NOT NULL` 제약 |
| execution_type | VARCHAR(20) NOT NULL | `SINGLE` · `SEARCH_BUNDLE` · `REGIME_SECTOR` · `REGIME_TOTAL` (007·010이 지정) |
| result_source | VARCHAR(10) NOT NULL | `NEW` · `JOINED` · `REUSED` |
| retry_of_request_id | BIGINT FK → backtest_request NULL | 재시도 원본 |
| cancelled_at | TIMESTAMPTZ NULL | 이 요청의 취소 시각 |
| cancelled_last_trading_date | DATE NULL | 취소 시점의 `run.progress_last_trading_date` 복사. **NULL = 취소 시점까지 완료한 거래일 없음**(첫 PROGRESS 전 취소). `stopped_run=false`인 요청의 완료 범위(R-04) |
| stopped_run | BOOLEAN NOT NULL DEFAULT false | 이 취소가 마지막 활성 요청의 취소여서 run을 멈췄으면 true. true면 완료 범위는 run에서 읽는다(R-04) |
| created_at | TIMESTAMPTZ NOT NULL | |

**파생 상태**: `cancelled_at IS NOT NULL` → `CANCELLED`, 그 외 `run.status`. 완료 범위 = `stopped_run ? run.progress_last_trading_date : request.cancelled_last_trading_date`. 스냅샷 **NULL은 "완료한 거래일 없음"이며 run 날짜로 대체하지 않는다**(첫 PROGRESS 전 취소 뒤 B가 계산해도 A의 범위는 없음). `stopped_run=false`인 취소 요청은 `partial=true`, 요약 없음, 시계열·거래·보유·비중·예외 조회는 스냅샷 날짜까지 읽기 필터 — run이 아직 종결 전이면 `409 RESULT_NOT_READY`, run이 `FAILED`면 `409 BACKTEST_FAILED`, 스냅샷이 NULL이면 run이 끝난 뒤에도 `409 NO_PARTIAL_RESULT`. `stopped_run=true`인 요청(마지막 활성 요청의 취소)과 실행 시간 한도는 run의 날짜를 쓴다 — 워커는 마지막 PROGRESS 뒤 몇 거래일을 더 계산한 뒤 멈추므로 FINISHED(CANCELLED)의 `lastTradingDate`가 실제 범위고, 그 요청만 부분 결과 요약을 본다. 먼저 취소한 요청은 run이 어떻게 끝나든 스냅샷 범위·요약 없음을 유지한다. CANCEL에 유예 시간 안 응답이 없어 run이 `FAILED(SYSTEM_FAILURE)`로 강제 종결되면 취소 요청은 `CANCELLED`·`partial=false`·요약 없음·하위 `409 BACKTEST_FAILED`다(R-04·R-19).

**인덱스**: `(requester_id, created_at DESC)`, `(run_id)`.

## backtest_run (실행)

| 컬럼 | 타입 | 규칙 |
|---|---|---|
| id | BIGINT PK (seq, 기존 `backtest_job_seq` 재사용) | |
| dedup_key | CHAR(64) NOT NULL | SHA-256 hex (R-03) |
| status | VARCHAR(20) NOT NULL | `QUEUED` `RUNNING` `COMPLETED` `FAILED` `CANCELLED` |
| execution_type | VARCHAR(20) NOT NULL | 첫 요청의 유형. 측정 분류에 사용 |
| strategy_id | VARCHAR(32) NOT NULL | `FIXED_WEIGHT` `EQUAL_WEIGHT` `INVERSE_VOLATILITY` `SECTOR_MOMENTUM` `MINIMUM_VARIANCE` |
| rules_version | VARCHAR(32) NOT NULL FK → strategy_rules | |
| cost_profile_version | VARCHAR(32) NOT NULL FK → cost_profile | |
| data_version | VARCHAR(64) NOT NULL | 001 자료 마감/버전 문자열 |
| request_spec | JSONB NOT NULL | canonical 요청(섹터 배분·파라미터·기간·금액·시나리오). 키 계산 입력과 동일 |
| versions | JSONB NOT NULL | `{mapping, rules, costProfile, data, engine, evaluationSplit, worker}` + provisional 플래그. `dependencies{numpy, scipy}` 선택 필드는 발표 후(Phase 10 T028) |
| effective_start / effective_end | DATE NOT NULL | 워밍업 이후 시작일, 평가 종료일 (R-15) |
| progress_stage | VARCHAR(20) NULL | `PREPARING_DATA` `COMPUTING` `SAVING` |
| progress_last_trading_date | DATE NULL | 처리한 마지막 거래일 = 취소·한도 시 완료 범위 |
| progress_completed_days / progress_total_days | INTEGER NULL | 비율 산출용 |
| attempt | SMALLINT NOT NULL DEFAULT 0 | STARTED마다 +1. `> publish_count`이면 자동 재시도 발생(재발행 중복은 세지 않는다, R-18) |
| current_execution_id | VARCHAR(255) NULL | 마지막 STARTED의 executionId |
| partial | BOOLEAN NOT NULL DEFAULT false | CANCELLED·한도 도달로 끊긴 결과 |
| failure_code / failure_message | VARCHAR(64) / TEXT NULL | `SYSTEM_FAILURE` `INVALID_REQUEST` `DATA_INSUFFICIENT` `CONSTRAINT_VIOLATION` `EXECUTION_LIMIT` … |
| cancel_requested_at | TIMESTAMPTZ NULL | 마지막 활성 요청 취소로 run 취소를 요청한 시각 |
| queued_at / started_at / finished_at | TIMESTAMPTZ | 측정 기록 원천 |
| published_at | TIMESTAMPTZ NULL | 작업 메시지 발행이 브로커에서 확인된 시각. `QUEUED`인데 NULL이면 재발행 대상(R-18) |
| publish_count | SMALLINT NOT NULL DEFAULT 0 | 발행 **시도** 횟수(첫 발행 + 재발행, 시도 직전 +1, confirm 무관). 브로커에 있을 수 있는 메시지 수의 상한. 중복 실행 판정과 autoRetried 기준(R-18) |
| dlq_count | SMALLINT NOT NULL DEFAULT 0 | 이 run의 작업 메시지가 DLQ로 간 횟수. `>= publish_count`일 때만 SYSTEM_FAILURE 종결(R-18) |
| random_seed | INTEGER NOT NULL | 42 고정(현행) |
| created_at / updated_at | TIMESTAMPTZ NOT NULL | |

**인덱스**: `UNIQUE (dedup_key) WHERE status IN ('QUEUED','RUNNING','COMPLETED')` (부분 유니크, R-02). `(status, queued_at)` 대기열 조회용. `(status, published_at)` 재발행 조회용(R-18).

**상태 전이**

```text
QUEUED ──STARTED──▶ RUNNING ──FINISHED(COMPLETED)──▶ COMPLETED
  │                    │
  │                    ├──FINISHED(FAILED) / DLQ──▶ FAILED
  │                    └──FINISHED(CANCELLED)────▶ CANCELLED (partial=true)
  └──마지막 활성 요청 취소──▶ CANCELLED
```

- `RUNNING`은 `QUEUED`에서만 진입한다. 재시도로 두 번째 STARTED가 오면 상태는 그대로 두고 `attempt`만 올린다.
- 종결 상태(`COMPLETED` `FAILED` `CANCELLED`)에서 오는 이벤트는 기록만 하고 무시한다(현행 규칙 유지).
- 종결 이벤트 적용 규칙(R-18, 중복 실행): `FINISHED(COMPLETED)`는 어느 executionId든 적용, `FINISHED(FAILED)`는 `event.executionId == current_execution_id`일 때만 적용, DLQ는 `dlq_count >= publish_count`일 때만 `FAILED(SYSTEM_FAILURE)`, `FINISHED(CANCELLED)`는 먼저 도착한 것을 적용.
- 강제 종결(R-19): CANCEL 뒤 `cancel-grace-seconds` 안에 FINISHED가 없으면 `FAILED(partial=false, 결과 행 없음, failure_code = SYSTEM_FAILURE 또는 EXECUTION_LIMIT)`. 협력 취소(`CANCELLED partial=true`)와 구분한다. 정체(마지막 이벤트 뒤 `stale-run-seconds`)도 CANCEL을 보내 같은 경로로 들어간다.
- 대기 중(`QUEUED`) run이 `CANCELLED`가 된 뒤 STARTED가 도착하면 백엔드가 CANCEL 제어 메시지를 보낸다(R-04).

**취소 규칙(Edge Cases)**: 요청 취소는 해당 `backtest_request.cancelled_at`만 기록한다. 같은 run에 `cancelled_at IS NULL`인 요청이 하나도 남지 않을 때만 run에 `cancel_requested_at`을 기록하고 QUEUED면 즉시 `CANCELLED`, RUNNING이면 제어 메시지를 보낸다. run이 `FAILED`면 합류한 모든 요청이 FAILED로 보이고 각각 재시도할 수 있다. 취소마다 `cancelled_last_trading_date`에 그 순간의 `run.progress_last_trading_date`(없으면 NULL)를 복사하고, 마지막 활성 요청이면 `stopped_run=true`로 표시한다(그 요청의 범위는 FINISHED가 run에 채운 날짜, R-04). CANCEL에 `cancel-grace-seconds` 안 응답이 없으면 run을 `FAILED(SYSTEM_FAILURE, partial=false)`로 강제 종결하고 결과 행을 만들지 않는다(R-19).

## backtest_result (결과)

기존 열 유지(`performance`, `execution`, `portfolio_series`, `benchmark`, `diagnostics`, `error_*`, `started_at`, `finished_at`, `execution_id`, `finished_event_id`). PK/FK를 `run_id`로 바꾸고 아래를 추가한다(모두 JSONB, FR-027).

| 컬럼 | 내용 |
|---|---|
| weights_series | `[{date, target:{instrumentId: w}, actual:{…}, weightError}]`, `weightsSource: USER|COMPUTED` |
| costs | `{commission, spread, slippage, tax, total}` (plain string 소수) |
| liquidation | `{markToMarket:{finalEquity,totalReturn}, liquidated:{finalEquity,totalReturn,liquidationCost}}` |
| analytics | `{recoveryDays, turnover, correlationMatrix, riskContribution, lossContribution, method}` |
| exceptions | `[{date, instrumentId?, kind: ZERO_VOLATILITY|NO_SELECTION|ESTIMATION_FAILED|CONSTRAINT_VIOLATION|HALT_DEFERRED|WEIGHT_ROUNDING, rule, action}]` |
| versions | run.versions 사본(결과 단독 조회용) |

부분 결과(CANCELLED)는 `portfolio_series`·`weights_series`·거래가 `progress_last_trading_date`까지만 채워지고 `performance`는 그 구간 기준으로 계산된 값이 들어간다. 005는 `partial=true`와 함께 표시한다.

## backtest_trade (거래 내역)

기존 열 유지, FK를 `run_id`로. `detail` JSONB에 `reason: REBALANCE | HALT_DEFERRED | LIQUIDATION`과 `plannedDate`(거래 불가로 미룬 경우 원래 체결 예정일)를 추가한다. 분배금은 거래가 아니라 `portfolio_series[].cashFlows`에 `DISTRIBUTION`으로 기록한다(FR-013, 한 번만 반영).

## cost_profile (비용 조건 버전)

| 컬럼 | 타입 | 규칙 |
|---|---|---|
| version | VARCHAR(32) PK | 예: `PROVISIONAL-DEMO-1` |
| commission_bps / spread_bps / slippage_bps / tax_bps | NUMERIC(8,4) NOT NULL | |
| escalation | JSONB NULL | 비용 상승 시나리오 정의(PD-07) |
| provisional | BOOLEAN NOT NULL | true면 결과에 "임시 비용" 표시 |
| note | TEXT | 근거(ADR-0004, 팀 결정 링크) |
| created_at | TIMESTAMPTZ | |

초기 행: `PROVISIONAL-DEMO-1 (0, 0, 5, 0, null, true, 'ADR-0004 데모 값 재사용, PD-07 확정 전')`.

확정 행(PD-07, 2026-09-17, T021이 추가): `KR-ETF-2026-09 (1.5, 3, 5, 0, null, false, 'PD-07 확정 2026-09-17')`. 기존 행은 고치지 않고 `default-cost-profile`만 새 버전으로 바꾼다.

## strategy_rules (전략 규칙 버전)

| 컬럼 | 타입 | 규칙 |
|---|---|---|
| strategy_id | VARCHAR(32) | PK 일부 |
| rules_version | VARCHAR(32) | PK 일부 |
| status | VARCHAR(20) NOT NULL | `REGISTERED` · `PENDING_DECISION`. 새 버전으로 대체된 옛 행을 뜻하는 `SUPERSEDED`는 발표 후(Phase 10 T029)에 CHECK 제약과 함께 추가한다 |
| provisional | BOOLEAN NOT NULL DEFAULT false | true면 팀 결정(PD) 전 임시 규칙. 결과 `versions.rules.provisional`로 노출 |
| spec | JSONB NULL | 규칙 명세(관측 길이·예외 처리·선정·배분·동점 등). PENDING이면 null |
| decision_ref | TEXT | PD 번호·회의 기록 |
| created_at | TIMESTAMPTZ | |

초기 행: `FIXED_WEIGHT/1`, `EQUAL_WEIGHT/1` REGISTERED(provisional=false); `INVERSE_VOLATILITY/1 {lookbackDays:63, zeroVolatility:"EXCLUDE_THEN_ALLOCATE", weightCap:"1.0000", investedFraction:"1.0000"}` REGISTERED **provisional=true, decision_ref PD-02**(관측 길이는 spec FR-023 값, 나머지는 spec Edge Case가 PD-02 계열 결정 항목으로 둔 값. analyze C1); `SECTOR_MOMENTUM/1`, `MINIMUM_VARIANCE/1` PENDING_DECISION(decision_ref PD-03·PD-04 — 2026-09-17 확정. 실제 SQL의 rules_version은 `1`이다. T022(최소분산)·T023(모멘텀)이 **이 행을** REGISTERED provisional=false로 바꿔 spec을 채웠다 — 가리키는 실행이 없어 재현이 깨지지 않는다. 그래서 지금 `PENDING_DECISION` 행은 없다. `INVERSE_VOLATILITY/1`도 T022가 제자리에서 갱신한다(`capOverflow`·`weightUnit` 추가, provisional=true 유지) — R-08이 임시 행은 확정 시 갱신한다고 계획했고 보존할 결과가 없다. 규칙 명세는 spec PD 표를 따른다); `TARGET_RISK_OVERLAY/1 {lookbackDays:63, adjustAt:"REBALANCE", scaleDownOnly:true, targetVolatility:{default:"0.1000", min:"0.0500", max:"0.2000", step:"0.0100"}}` REGISTERED provisional=false(decision_ref PD-18, 2026-09-10 팀 확정. 전략이 아닌 시나리오 오버레이 규칙이지만 같은 표로 버전 관리). 파라미터 후보·범위(PD-02)는 003 등록표 소관이며 이 테이블은 계산 규칙만 담는다.

## backtest_run_metric (실행 측정 기록)

종결 한 번에 한 행. `run_id`가 UNIQUE라 늦게 온 중복 종결은 `ON CONFLICT DO NOTHING`으로 무시된다(T012).

| 컬럼 | 타입 |
|---|---|
| id | BIGINT PK |
| run_id | BIGINT FK NOT NULL UNIQUE, ON DELETE CASCADE (실행이 없는 측정은 뜻이 없다) |
| execution_type | VARCHAR(20) NOT NULL (`backtest_run.execution_type`, 없으면 SINGLE) |
| status | VARCHAR(20) NOT NULL (종결 상태 COMPLETED · FAILED · CANCELLED) |
| failure_code | VARCHAR(64) (FAILED 의 엔진 오류·SYSTEM_FAILURE, EXECUTION_LIMIT 으로 끊긴 CANCELLED) |
| queued_at / started_at / finished_at | TIMESTAMPTZ |
| wait_ms / run_ms | BIGINT (started-queued, finished-started. STARTED 를 못 받은 실행은 null) |
| concurrent_running | INTEGER (종결 시점의 RUNNING run 수, 자기 자신 제외) |
| peak_rss_mb / cpu_seconds | NUMERIC(12,1) / NUMERIC(12,3) (FINISHED payload `resource`. DLQ·강제 종결은 null) |
| auto_retried | BOOLEAN (attempt > publish_count, R-18 — 재발행 중복은 재시도가 아니다) |
| result_bytes | BIGINT (`backtest_result` 행의 `pg_column_size`. 결과 없는 종결은 0) |
| data_rows / data_bytes | BIGINT (FINISHED `resource.dataRows`·`dataBytes` — 읽은 가격·지수·기업행위 행 수(지수는 달력으로 읽고 버려 행 수에만), 가격 프레임 메모리 바이트. 데이터 원가(FR-008, T019). 자료를 읽지 못한 종결은 null) |
| served_queue / worker_role | VARCHAR(40) / VARCHAR(10) (어느 큐에서 어느 역할의 워커가 **결과를 냈나** — R-20 N/M 비율 조정 근거. FINISHED `resource.queue`·`role`이 먼저고, FINISHED 가 없는 종결은 STARTED 가 실행 행에 남긴 값) |
| recorded_at | TIMESTAMPTZ |

`backtest_run`에도 `served_queue`·`worker_role` 두 컬럼이 있다 — STARTED 를 받을 때 전용 UPDATE 로 쓰고(컬럼 소유 규칙) 종결 때 여기로 복사한다.

## backtest_limit_hit (한도 도달 기록)

실행 행이 없어도 남겨야 하는 사건(접수 거절)이 있어 측정 기록과 표를 나눈다. append-only.

| 컬럼 | 타입 |
|---|---|
| id | BIGINT PK |
| kind | VARCHAR(20) NOT NULL (`ADMISSION_REJECTED` 429 · `RUN_STOPPED` 실행 시간 상한으로 CANCEL) |
| execution_type | VARCHAR(20) NOT NULL |
| reason | VARCHAR(32) NOT NULL (`PER_USER` · `TOTAL` · `EXECUTION_LIMIT`) |
| requester_id | BIGINT (거절은 있고 정지는 null) |
| run_id | BIGINT FK (정지는 있고 거절은 null) |
| at | TIMESTAMPTZ NOT NULL |

## backtest_reuse_event (재조회 기록)

`id, run_id FK, request_id FK NULL, kind VARCHAR(10) (JOINED | REUSED), at TIMESTAMPTZ`. 원가 기록에서 새 계산과 구분한다(FR-008). `request_id`는 spec 005의 만료 정리가 비로그인 요청을 지울 때 NULL로 끊긴다. 기록 행은 지우지 않으므로 `reuse_count`가 정리로 줄지 않는다(005 FR-021·T022).

## v_run_metric_summary (뷰)

`execution_type`별 `count, fail_rate, cancel_rate, auto_retry_rate, wait_p50_ms, wait_p95_ms, wait_p99_ms, run_p50_ms, run_p95_ms, peak_rss_p95_mb, flex_served_rate, reuse_count, limit_hit_count, data_rows_p50, data_bytes_p50`(뒤 둘은 T019가 끝에 덧붙였다 — 뷰는 열을 끝에만 더할 수 있다). `percentile_cont` 사용. `reuse_count`는 `backtest_reuse_event.request_execution_type`으로, `limit_hit_count`는 `backtest_limit_hit.execution_type`으로 조인한다. `flex_served_rate`는 `worker_role='flex'`인 행의 비율 — 단건 유형에서 이 값이 곧 "공용 워커가 단건을 지원한 비율"(R-20)이다. 이 뷰가 PD-06 상한 결정과 N/M 워커 비율 조정의 근거 자료다. `GET /internal/execution-metrics?type=&from=&to=`는 같은 집계를 `recorded_at` 범위로 잘라 돌려주고(뷰는 전체 기간), 범위를 주지 않으면 뷰와 값이 같다. 큐별 **현재** 대기 수는 기록이 아니라 브로커 상태라 RabbitMQ 관리 UI로 본다.

## 용어 (2026-09-17, analyze I7)

| 이름 | 어디에 | 뜻 |
|---|---|---|
| `investedFraction` | 규칙표 `strategy_rules.spec`(소수, 기본 `1.0000`) | 투자 대상 비중 합계. 산문의 "투자 비중"·"고정 투자 비중 합계" |
| 유효 투자 목표 | 계산 시 파생 | `min(investedFraction, 유효 상한 × 계산 가능 종목 수)`. 최소분산 합계 제약(PD-04) |
| `weightCap` | 규칙표 `strategy_rules.spec`(소수) | 종목별 상한의 기본값 |
| `maxWeight` | 003 축 → 요청 `allocation.parameters.maxWeight`(정수 %) | 사용자가 고른 종목별 상한. 있으면 `weightCap`보다 우선 |
| 유효 상한 | 접수·워커 공통 | `maxWeight/100` 또는 `weightCap`. 산문의 "상한"·"비중 상한" |

## 검증 규칙 (요청 접수 시, 백엔드)

- 섹터 배분: 대응표 버전 존재, 섹터 중복 없음, `FIXED_WEIGHT`면 목표 비중 합 = 1(허용 오차 없이 plain string 비교), 그 외 전략은 목표 비중 미지정.
- 유효 상한 × 종목 수 ≥ `investedFraction` — **유효 상한 = 요청 `allocation.parameters.maxWeight`/100이 있으면 그 값, 없으면 규칙표 `weightCap`**(analyze U1, T022). 종목 수는 요청의 투자 대상 섹터 수이고 모멘텀은 `TOP_N`=topN·`TOP_PERCENT`=ceil(N×p/100)·`ALL`=N(아니면 422 `VALIDATION_RULE_VIOLATED`, Edge Case. 2026-09-17 PD-04 ④로 400 `INVALID_INPUT`에서 옮김, T022).
- `strategy_rules.status = REGISTERED`(아니면 422 `RULES_NOT_REGISTERED`). 대체된 옛 버전의 409 처리는 발표 후(Phase 10 T029).
- 가용 기간 ≥ 워밍업(아니면 422 `INSUFFICIENT_HISTORY`).
- 시작 금액 > 0, NUMERIC(19,2) 범위(현행).
- `scenario=TARGET_RISK`면 `scenarioParams.targetVolatility` 필수, 0.0500~0.2000 범위·0.0100 간격(아니면 400 `INVALID_INPUT`). 다른 시나리오에서는 `scenarioParams` 금지. `FRESH_CASH_FROM`은 `scenarioParams.startDate` 필수.
- 종목 식별자 `^\d{6}\.XKRX$`(현행), 최대 50개(현행).

## 마이그레이션 V3 요약

1. `backtest_job` → `backtest_run` 개명, 새 컬럼 추가(`dedup_key`는 기존 행에 대해 request_spec 기반으로 계산하거나 `LEGACY-{id}`를 채움), `status` CHECK에 `CANCELLED` 추가. `published_at`(R-18)은 기존 행에 `created_at`을 채우고 `publish_count=1`, `dlq_count=0`으로 둔다(이미 발행된 Job).
2. `backtest_request` 생성(`cancelled_last_trading_date`·`stopped_run` 포함), 기존 run마다 `result_source=NEW`, `requester_id`는 기존 행에 없으므로 마이그레이션용 시스템 사용자 또는 데모 데이터 삭제 중 택일(데모 데이터라 삭제를 권장하며 tasks에서 확정).
3. `backtest_result.backtest_job_id` → `run_id`, `backtest_trade.backtest_job_id` → `run_id` 개명, 새 JSONB 열 추가.
4. `cost_profile`, `strategy_rules`, `backtest_run_metric`, `backtest_reuse_event`, 뷰 생성과 초기 행 삽입.
