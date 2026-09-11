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
| requester_id | BIGINT FK → users NOT NULL | 취소·재시도 권한 주체. 계산 기록에는 복사하지 않는다 |
| execution_type | VARCHAR(20) NOT NULL | `SINGLE` · `SEARCH_BUNDLE` · `REGIME_SECTOR` · `REGIME_TOTAL` (007·010이 지정) |
| result_source | VARCHAR(10) NOT NULL | `NEW` · `JOINED` · `REUSED` |
| retry_of_request_id | BIGINT FK → backtest_request NULL | 재시도 원본 |
| cancelled_at | TIMESTAMPTZ NULL | 이 요청의 취소 시각 |
| cancelled_last_trading_date | DATE NULL | 남은 활성 요청이 있어 run이 계속 돌 때만 취소 시점의 `run.progress_last_trading_date`를 복사. 마지막 활성 요청의 취소는 NULL — 범위는 FINISHED(CANCELLED)의 날짜를 run에서 읽는다(R-04) |
| created_at | TIMESTAMPTZ NOT NULL | |

**파생 상태**: `cancelled_at IS NOT NULL` → `CANCELLED`(`partial=true`), 그 외 `run.status`·`run.partial`. 진행 정도는 run에서 읽되, **취소된 요청의 완료 범위는 `cancelled_last_trading_date`**다(run이 계속 돌아 `progress_last_trading_date`가 늘어나도 바뀌지 않는다). 완료 범위 = `COALESCE(request.cancelled_last_trading_date, run.progress_last_trading_date)`. 스냅샷이 있는 요청의 시계열·거래·보유·비중·예외 조회는 그 날짜까지 읽기 필터로 자르고 요약 지표는 주지 않으며, run이 아직 종결 전이면 `409 RESULT_NOT_READY`, run이 `FAILED`면 `409 BACKTEST_FAILED`다. 마지막 활성 요청의 취소와 실행 시간 한도는 스냅샷을 남기지 않는다 — 워커는 마지막 PROGRESS 뒤 몇 거래일을 더 계산한 뒤 멈추므로 FINISHED(CANCELLED)의 `lastTradingDate`가 실제 범위고, 그 요청만 부분 결과 요약을 본다. 먼저 취소한 요청은 run이 어떻게 끝나든 스냅샷 범위·요약 없음을 유지한다(R-04).

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
| versions | JSONB NOT NULL | `{mapping, rules, costProfile, data, engine, evaluationSplit, worker}` + provisional 플래그 |
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
| publish_count | SMALLINT NOT NULL DEFAULT 0 | confirm된 발행 횟수(첫 발행 + 재발행). 중복 실행 판정과 autoRetried 기준(R-18) |
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
- 대기 중(`QUEUED`) run이 `CANCELLED`가 된 뒤 STARTED가 도착하면 백엔드가 CANCEL 제어 메시지를 보낸다(R-04).

**취소 규칙(Edge Cases)**: 요청 취소는 해당 `backtest_request.cancelled_at`만 기록한다. 같은 run에 `cancelled_at IS NULL`인 요청이 하나도 남지 않을 때만 run에 `cancel_requested_at`을 기록하고 QUEUED면 즉시 `CANCELLED`, RUNNING이면 제어 메시지를 보낸다. run이 `FAILED`면 합류한 모든 요청이 FAILED로 보이고 각각 재시도할 수 있다. 남은 활성 요청이 있으면 `cancelled_last_trading_date`에 그 순간의 `run.progress_last_trading_date`를 복사하고, 마지막 활성 요청이면 복사하지 않는다(범위는 FINISHED가 run에 채운다, R-04).

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

## strategy_rules (전략 규칙 버전)

| 컬럼 | 타입 | 규칙 |
|---|---|---|
| strategy_id | VARCHAR(32) | PK 일부 |
| rules_version | VARCHAR(32) | PK 일부 |
| status | VARCHAR(20) NOT NULL | `REGISTERED` · `PENDING_DECISION` |
| provisional | BOOLEAN NOT NULL DEFAULT false | true면 팀 결정(PD) 전 임시 규칙. 결과 `versions.rules.provisional`로 노출 |
| spec | JSONB NULL | 규칙 명세(관측 길이·예외 처리·선정·배분·동점 등). PENDING이면 null |
| decision_ref | TEXT | PD 번호·회의 기록 |
| created_at | TIMESTAMPTZ | |

초기 행: `FIXED_WEIGHT/1`, `EQUAL_WEIGHT/1` REGISTERED(provisional=false); `INVERSE_VOLATILITY/1 {lookbackDays:63, zeroVolatility:"EXCLUDE_THEN_ALLOCATE", weightCap:"1.0000", investedFraction:"1.0000"}` REGISTERED **provisional=true, decision_ref PD-02**(관측 길이는 spec FR-023 값, 나머지는 spec Edge Case가 PD-02 계열 결정 항목으로 둔 값. analyze C1); `SECTOR_MOMENTUM/PENDING`, `MINIMUM_VARIANCE/PENDING` PENDING_DECISION(decision_ref PD-03·PD-04); `TARGET_RISK_OVERLAY/1 {lookbackDays:63, adjustAt:"REBALANCE", scaleDownOnly:true, targetVolatility:{default:"0.1000", min:"0.0500", max:"0.2000", step:"0.0100"}}` REGISTERED provisional=false(decision_ref PD-18, 2026-09-10 팀 확정. 전략이 아닌 시나리오 오버레이 규칙이지만 같은 표로 버전 관리). 파라미터 후보·범위(PD-02)는 003 등록표 소관이며 이 테이블은 계산 규칙만 담는다.

## backtest_run_metric (실행 측정 기록)

| 컬럼 | 타입 |
|---|---|
| id | BIGINT PK |
| run_id | BIGINT FK NOT NULL |
| execution_type | VARCHAR(20) NOT NULL |
| status | VARCHAR(20) NOT NULL (종결 상태) |
| queued_at / started_at / finished_at | TIMESTAMPTZ |
| wait_ms / run_ms | BIGINT (started-queued, finished-started) |
| concurrent_running | INTEGER (종결 시점의 RUNNING run 수) |
| peak_rss_mb / cpu_seconds | NUMERIC (FINISHED payload `resource`) |
| auto_retried | BOOLEAN (attempt > publish_count, R-18 — 재발행 중복은 재시도가 아니다) |
| result_bytes | BIGINT (JSONB 합계 길이) |
| recorded_at | TIMESTAMPTZ |

## backtest_reuse_event (재조회 기록)

`id, run_id FK, request_id FK, kind VARCHAR(10) (JOINED | REUSED), at TIMESTAMPTZ`. 원가 기록에서 새 계산과 구분한다(FR-008).

## v_run_metric_summary (뷰)

`execution_type`별 `count, fail_rate, cancel_rate, auto_retry_rate, wait_p50_ms, wait_p95_ms, run_p50_ms, run_p95_ms, peak_rss_p95_mb, reuse_count`(reuse_event 조인). `percentile_cont` 사용. 이 뷰가 PD-06 상한 결정의 근거 자료다.

## 검증 규칙 (요청 접수 시, 백엔드)

- 섹터 배분: 대응표 버전 존재, 섹터 중복 없음, `FIXED_WEIGHT`면 목표 비중 합 = 1(허용 오차 없이 plain string 비교), 그 외 전략은 목표 비중 미지정.
- 비중 상한 합계 ≥ 고정 투자 비중 합계(아니면 400 `INVALID_INPUT`, Edge Case).
- `strategy_rules.status = REGISTERED`(아니면 422 `RULES_NOT_REGISTERED`).
- 가용 기간 ≥ 워밍업(아니면 422 `INSUFFICIENT_HISTORY`).
- 시작 금액 > 0, NUMERIC(19,2) 범위(현행).
- `scenario=TARGET_RISK`면 `scenarioParams.targetVolatility` 필수, 0.0500~0.2000 범위·0.0100 간격(아니면 400 `INVALID_INPUT`). 다른 시나리오에서는 `scenarioParams` 금지. `FRESH_CASH_FROM`은 `scenarioParams.startDate` 필수.
- 종목 식별자 `^\d{6}\.XKRX$`(현행), 최대 50개(현행).

## 마이그레이션 V3 요약

1. `backtest_job` → `backtest_run` 개명, 새 컬럼 추가(`dedup_key`는 기존 행에 대해 request_spec 기반으로 계산하거나 `LEGACY-{id}`를 채움), `status` CHECK에 `CANCELLED` 추가. `published_at`(R-18)은 기존 행에 `created_at`을 채우고 `publish_count=1`, `dlq_count=0`으로 둔다(이미 발행된 Job).
2. `backtest_request` 생성(`cancelled_last_trading_date` 포함), 기존 run마다 `result_source=NEW`, `requester_id`는 기존 행에 없으므로 마이그레이션용 시스템 사용자 또는 데모 데이터 삭제 중 택일(데모 데이터라 삭제를 권장하며 tasks에서 확정).
3. `backtest_result.backtest_job_id` → `run_id`, `backtest_trade.backtest_job_id` → `run_id` 개명, 새 JSONB 열 추가.
4. `cost_profile`, `strategy_rules`, `backtest_run_metric`, `backtest_reuse_event`, 뷰 생성과 초기 행 삽입.
