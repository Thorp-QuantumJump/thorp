# 백테스트 메시지 계약 (Backend ↔ Worker, RabbitMQ)

- 상태: **schemaVersion 2** (2026-09-11, spec 004 T001). v1 결정 사항은 [ADR-0002](../../adr/0002-rabbitmq-backtest-message-contract.md),
  v2 변경의 근거는 [`specs/004-backtest-execution/contracts/messaging.md`](../../../specs/004-backtest-execution/contracts/messaging.md)와
  research R-01~R-17이다.
- 원본 설계: Notion `NautilusTrader 설계(input, output)` — "INPUT Spec", "OUTPUT Spec", "RabbitMQ 연동 및 실시간 그래프 설계"
- 이 폴더의 `examples/*.json`이 **양쪽 코드의 단일 기준**이다. 백엔드(`backtest/application/port/message`)와
  워커(`worker/src/worker/contracts`)는 이 파일을 테스트에서 직접 읽어 round-trip을 검증한다. 필드를 바꾸면 이 폴더 →
  백엔드 DTO → 워커 모델 순서로 같은 MR에서 함께 고친다.

## v1 → v2 요약

| 항목 | v1 | v2 |
|---|---|---|
| 실행 식별자 | `backtestId` (요청 = 실행) | `runId` (여러 요청이 한 실행에 합류, spec 004 FR-002). 요청/실행 분리(T003) 전까지 백엔드는 `backtestId` 값을 그대로 `runId`로 보낸다 |
| 종목 지정 | `universe.instrumentIds[]` | `universe.items[] { sectorId?, instrumentId, targetWeight? }` + `mappingVersion?` |
| 전략 블록 | `strategy { strategyId, version, parameters }` | `allocation { strategyId, rulesVersion, rules?, parameters }` |
| 비용 | `execution.commission` + `execution.slippage` | `execution.costProfile { version, commissionBps, spreadBps, slippageBps, taxBps, provisional }` |
| 가격 계열 | `marketData.adjustment` | + `fillPrice=RAW`, `signalPrice=ADJUSTED`, `distributions=CASH_ON_PAY_DATE`, `dataVersion?` |
| 시나리오 | 없음 | `run.scenario` (+ `scenarioParams?`, `scenarioRules?`, `effectiveStart?`) |
| 평가·진행 | 고정(20거래일) | `evaluation { splitVersion?, warmupTradingDays?, liquidationAtEnd }`, `progress { batchTradingDays }` |
| 벤치마크 | `benchmark` 블록, `FINISHED.benchmark` | **제거**. 벤치마크는 이 계약의 대상이 아니다 |
| 이벤트 | `{ engineOutput, benchmark }` | STARTED·PROGRESS에 `stage`, PROGRESS에 `lastTradingDate`, FINISHED에 `status`·`partial`·`lastTradingDate`·`resource` |
| 최종 상태 | COMPLETED / FAILED | + **CANCELLED**(부분 결과) |
| 제어 | 없음 | `backtest.control` fanout, `ControlMessage { CANCEL }` |
| 작업 큐 | classic | quorum + `x-delivery-limit=1` |

## 1. 역할

| 구성 요소 | 책임 |
|---|---|
| Backend API | 요청 검증, 실행(`runId`) 결정, run·request 저장, **커밋 후** 작업 발행(`published_at` 재발행, spec 004 R-18), 취소 요청 발행, 상태·결과 조회, SSE |
| RabbitMQ | 작업·이벤트·제어 메시지 보관·전달 |
| Worker (실행 Consumer) | 작업 수신, `BacktestEngine.run()` 호출, STARTED / PROGRESS / FINISHED 발행, ACK, 제어 메시지(CANCEL) 수신 |
| 엔진 (`BacktestEngine` 구현) | `EngineInput` → `EngineOutput`. 진행 콜백 제공. **DB·브로커를 모른다** |
| Backend 결과 Consumer | 이벤트 검증, 실행·요청 상태 갱신, 최종 결과 저장, SSE 전달 (`BacktestEventListener` → `BacktestEventValidator` → `BacktestEventService`) |

```
Frontend → POST /backtests → Job 저장(QUEUED) → [backtest.jobs] ← 202 + backtestId
[backtest.jobs] → Worker → engine.run() → STARTED / PROGRESS… / FINISHED → [backtest.events]
[backtest.events] → Backend 결과 Consumer → RUNNING / SSE progress / 결과 저장 + COMPLETED|FAILED|CANCELLED
Backend ── CANCEL ──▶ [backtest.control] (fanout) ──▶ 모든 Worker (자기 runId면 다음 거래일에 중단)
```

워커와 엔진은 결과 DB에 직접 쓰지 않는다. 성과 지표는 엔진이 계산하고 백엔드는 저장·조회만 한다.

## 2. RabbitMQ 토폴로지

| 항목 | 이름 | 속성 |
|---|---|---|
| Exchange | `backtest` | direct, durable |
| 작업 큐 | `backtest.jobs` | **quorum**, `x-delivery-limit=1`, DLX `backtest.dlx`, dead-letter key `backtest.job.dead` |
| 작업 binding | `backtest` → `backtest.jobs` | routing key `backtest.job.requested` |
| 이벤트 큐 | `backtest.events` | durable, DLX `backtest.dlx`, dead-letter key `backtest.event.dead` |
| 이벤트 binding | `backtest` → `backtest.events` | routing key `backtest.event` |
| 제어 exchange | `backtest.control` | **fanout**, durable. 워커마다 exclusive·auto-delete 큐를 바인딩한다(T011) |
| Dead-letter exchange | `backtest.dlx` | direct, durable |
| 작업 DLQ | `backtest.jobs.dlq` | binding key `backtest.job.dead` |
| 이벤트 DLQ | `backtest.events.dlq` | binding key `backtest.event.dead` |

선언은 백엔드 애플리케이션이 한다(Spring `Declarables`, `BacktestRabbitConfig`). 인프라가 미리 만들어 두어도 같은 속성이면 충돌하지 않는다.
`x-delivery-limit=1`은 워커가 죽어 requeue된 작업을 **한 번만** 다시 배달하고 그 다음은 DLQ로 보낸다(spec 004 FR-023 자동 재시도 1회, research R-06).
DLQ로 간 메시지는 자동 재투입하지 않고 원인 수정 후 운영자가 재처리한다.

**v1 classic 큐가 이미 있는 환경(개발 PC의 기존 볼륨, 운영 브로커)은 v2 백엔드를 띄우기 전에 `backtest.jobs`를 한 번 삭제해야 한다.**
classic → quorum은 큐 인자 변경이라 같은 이름으로 재선언하면 `PRECONDITION_FAILED`로 선언이 실패하고, 큐는 classic 그대로 남아 `x-delivery-limit`이 적용되지 않는다.
삭제 전제조건은 **접수(백엔드 `POST /backtests`)를 멈춘 상태에서 `messages_ready`와 `messages_unacknowledged`가 모두 0**이다. 미ACK만 확인하면 대기 중인 작업을 잃는다.
개발 환경 명령은 `specs/004-backtest-execution/quickstart.md`, 운영 절차(중단·확인·삭제·재선언·되돌리기)는 T002 `docs/guides/backtest-queue-migration.md`가 정한다.

## 3. 메시지 속성 (작업·이벤트·제어 공통)

| 속성 | 값 |
|---|---|
| `content_type` | `application/json` |
| `delivery_mode` | 2 (persistent) |
| `message_id` | 작업: 발행 측 고유 ID(재시도 시 유지). 이벤트: 본문 `eventId` |
| `correlation_id` | `runId` |
| header `schemaVersion` | `2` |
| header `publishAttempt` | 작업만. 이 메시지를 보낼 때의 `backtest_run.publish_count`(1부터). 워커는 읽지 않는다 |

`publishAttempt`는 **백엔드가 자기 자신에게 보내는 표시**다. 그 메시지가 DLQ로 끝났을 때 "어느 발행 시도가 죽었는지"를 구분해 같은 메시지를 두 번
세지 않는다(R-18 ③) — DLQ 소비는 at-least-once라 커밋 뒤 ACK이 유실되면 같은 메시지가 다시 오고, 두 번 세면 살아 있는 작업이 남았는데도
`dlq_count >= publish_count`가 서서 실행이 잘못 `FAILED`로 끝난다. 워커는 이 헤더를 읽지 않으므로 없어도 계산에는 영향이 없다.

워커(pika)는 Spring의 `__TypeId__` 헤더를 보내지 않는다. 백엔드는 헤더가 아니라 리스너 파라미터 타입과 본문 `type` 필드로 역직렬화한다.
v1(`schemaVersion=1`) 메시지는 양쪽 모두 계약 오류로 거절한다 — 백엔드·워커를 함께 배포한다.

## 4. 메시지 본문

### 4.1 작업 메시지 = `EngineInput` 전체 — [`examples/engine-input.json`](examples/engine-input.json), [`examples/engine-input-target-risk.json`](examples/engine-input-target-risk.json)

여덟 블록 `run` / `universe` / `marketData` / `allocation` / `portfolio` / `execution` / `evaluation` / `progress`가 모두 필수다.
백엔드가 사용자 요청(run.start/end, universe, strategy, portfolio)에 아래 **고정 실행 정책**(`ExecutionPolicy`)과 `run.runId`, `run.randomSeed`,
`allocation.rulesVersion`, `marketData`를 채워 발행한다. `engine-input.json`은 조립기 결과(003 연동 전: `sectorId`·`mappingVersion`·`rules` 없음),
`engine-input-target-risk.json`은 모든 선택 필드가 채워진 전체 예시(PD-18 목표 위험 맞춤, 003 연동 뒤)다.

| 경로 | 고정값 |
|---|---|
| `run.scenario` | `FULL_PERIOD` (주 분석). 시나리오 `FRESH_CASH_FROM`·`COST_ESCALATION`·`TARGET_RISK`는 T009 |
| `marketData` | `datasetId=KRX_DAILY_V1`, `interval=1D`, `adjustment=SPLIT_ADJUSTED`, `priceType=LAST`, `fillPrice=RAW`, `signalPrice=ADJUSTED`, `distributions=CASH_ON_PAY_DATE` |
| `portfolio.currency` / `shortSelling` / `leverage` / `integerLots` | `KRW` / `false` / `"1.0"` / `true` |
| `execution.signalTiming` / `executionTiming` | `BAR_CLOSE` / `NEXT_BAR_OPEN` (t 종가 신호 → t+1 시가 체결) |
| `execution.orderTypes` | `["MARKET"]` |
| `execution.costProfile` | `version=PROVISIONAL-DEMO-1`, 수수료·스프레드·세금 `"0"`, `slippageBps="5"`, `provisional=true` — 팀 결정(PD-07) 전 임시값. 결과 `versions.costProfile`에 그대로 남는다 |
| `execution.partialFill` / `liquidity.model` | `false` / `UNLIMITED` |
| `execution.tradingHalt` / `delisting` | `DEFER_TO_NEXT_TRADABLE` / `CAUSE_BASED` |
| `evaluation` | `liquidationAtEnd=false`, `splitVersion`·`warmupTradingDays` 없음(엔진이 전략 관측 기간으로 정한다) |
| `progress.batchTradingDays` | `20` |

`allocation.parameters`는 전략별 자유 형식 객체 — **정수는 정수, 소수는 JSON 숫자로 그대로 통과**시키고 문자열로 바꾸지 않는다(금액·비율 문자열 규칙은
선언된 decimal 필드에만 적용). 픽스처의 `threshold: 0.05`가 이 규칙의 round-trip 검사용이다. `allocation.rules`는 전략 규칙 등록표의 사본이며
없으면(null) 엔진은 자체 기본값을 두지 않고 거절한다(T006부터).

### 4.2 이벤트 envelope — `event-*.json` 공통

| 필드 | 타입 | 의미 |
|---|---|---|
| `schemaVersion` | integer | `2` |
| `eventId` | string | 이벤트 고유 ID. 재발행 시 유지 (중복 방지 키) |
| `runId` | string | 실행 식별자. `EngineInput.run.runId`와 같다. 워커는 요청(`backtestId`)을 모른다 |
| `executionId` | string | 엔진 실행 시도 식별자. 재실행(재전달)마다 새 값. 같은 `runId`에 다른 `executionId`의 STARTED가 오면 자동 재시도다 |
| `type` | enum | `STARTED` / `PROGRESS` / `FINISHED` |
| `sequence` | integer | 실행 시도 내 순번. STARTED=0, 이후 1씩 증가 |
| `occurredAt` | string(datetime) | UTC ISO-8601 |
| `payload` | object | type별 본문 |

| type | payload | 예시 |
|---|---|---|
| `STARTED` | `{ startedAt, stage=PREPARING_DATA, resource? { pid, hostname } }` | [`event-started.json`](examples/event-started.json) |
| `PROGRESS` | `{ stage=COMPUTING, completedTradingDays, totalTradingDays, lastTradingDate, portfolioSeries[] { date, equity, cumulativeReturn, drawdown } }` — `progress.batchTradingDays` 단위, 이번 구간에서 새로 계산한 포인트만, 마지막 잔여 구간도 발행. `lastTradingDate`는 취소·한도 시 완료 범위 | [`event-progress.json`](examples/event-progress.json) |
| `FINISHED` | `{ status, partial, lastTradingDate?, engineOutput, resource? { peakRssMb, cpuSeconds, wallSeconds } }` — `status`는 `engineOutput.run.status`와 같아야 한다. `partial=true`는 CANCELLED에서만 | [`event-finished-completed.json`](examples/event-finished-completed.json), [`event-finished-failed.json`](examples/event-finished-failed.json), [`event-finished-cancelled.json`](examples/event-finished-cancelled.json) |

### 4.3 `EngineOutput`

구조는 설계 문서 OUTPUT Spec을 따르고 spec 004(FR-017·FR-020·FR-027·FR-028·FR-030)가 블록을 보충했다. v2에서 추가된 블록은 전부 생략 가능하며
단일 종목 데모 엔진은 채울 수 있는 것(`costs`, `versions`, `run.partial/lastTradingDate/effectiveStart/effectiveEnd`, `performance.netTotalReturn/totalTradingCost`,
`trades[].reason`)만 채운다.

- `run { runId, status, startedAt, finishedAt, partial?, lastTradingDate?, effectiveStart?, effectiveEnd?, error? }`. `status`는 `COMPLETED` / `FAILED` / `CANCELLED`.
- `run.error { code, message }` — 실패 시 채우고 성공 시 없음. 코드는 `INVALID_REQUEST`, `DATA_INSUFFICIENT`, `CONSTRAINT_VIOLATION`, `ESTIMATION_FAILED`,
  `ENGINE_ERROR`, `INTERNAL_ERROR`. (`INSUFFICIENT_CASH`는 오류가 아니라 `diagnostics.warnings[].code`)
- FAILED일 때 `performance`와 `execution`은 없음(null), `portfolioSeries`·`trades`는 `[]`. 존재하지 않는 성과를 0으로 채우지 않는다.
- **CANCELLED**는 `partial=true`이고 `lastTradingDate`까지의 **부분 결과**를 COMPLETED와 같은 형태로 담는다(`performance`·`execution` 필수). 취소는 다음 거래일
  처리 시점에 반영되므로 `portfolioSeries`의 마지막 날짜가 `lastTradingDate`다.
- `portfolioSeries[].positions`는 보유 구성이 바뀐 거래일 + 시작일 + 종료일에만 존재한다. `portfolioSeries[].cashFlows[] { type, instrumentId?, amount }`는
  분배금 등 현금 흐름이 있는 날에만 존재한다(FR-013).
- `weightsSeries[] { date, target{}, actual{}, weightError?, riskScale?, estimatedVolatility? }` + `weightsSource` (`USER` / `COMPUTED`): 목표 비중과 실제 비중(FR-019).
  `riskScale`·`estimatedVolatility` 는 `scenario=TARGET_RISK` 에서만 존재하고 그때 `target` 은 조절 뒤 비중이다(PD-18).
- `trades[]`: OPEN 거래는 `exitTime`, `exitPrice`, `grossPnl`, `realizedPnl`, `returnRate`가 없음이고 `commission`·`slippageCost`는 진입 측만 합산한다.
  `reason` (`SIGNAL` / `REBALANCE` / `HALT_DEFERRED` / `LIQUIDATION`)과 이월 주문의 `plannedDate`는 선택.
- `costs { commission, spread, slippage, tax, total }` 비용 합계(FR-027). `performance.netTotalReturn`·`totalTradingCost`와 짝.
- `liquidation { markToMarket { finalEquity, totalReturn }, liquidated { finalEquity, totalReturn, liquidationCost } }` — `evaluation.liquidationAtEnd=true`일 때(FR-017).
- `analytics { recoveryDays?, turnover, correlationMatrix, riskContribution, lossContribution, weightError, method }` 파생 지표(FR-027).
  정의는 워커 `performance.calculate_analytics` docstring(T008 결정)이 원본이고 `method="sample-covariance-daily"`. 계산 불가 항목은 키 생략.
- `exceptions[] { date, instrumentId?, kind, rule, action }` 예외 처리 기록(FR-020·FR-030). `kind`는 `ZERO_VOLATILITY`, `NO_SELECTION`, `ESTIMATION_FAILED`,
  `CONSTRAINT_VIOLATION`, `HALT_DEFERRED`, `WEIGHT_ROUNDING` 등 개방 집합.
- `versions { mapping?, rules { version, provisional }, scenarioRules?, costProfile { version, provisional }, data, engine, worker }` 참조 버전(FR-028).
- 계산 불가 성과 지표(설계 OUTPUT Spec 규칙): 변동성 0 → `sharpeRatio`만 null. 관측치 2개 미만 → `volatility`·`sharpeRatio` null.
  CLOSED 거래 0건 → `winRate` null. `initialEquity ≤ 0` → `totalReturn`·`cagr` null. 기간이 1년 미만이라는 이유로 `cagr`을 null로 만들지 않는다.
- **체결가 의미**: 계약상 `trades[].entryPrice`/`exitPrice`는 슬리피지가 반영된 실제 평균 체결가다. **현재 픽스처는 기준가를 그대로 넣고 슬리피지를
  `slippageCost`로 분리한 단순 회계 예시**라 항등식은 성립하지만 가격 의미는 계약과 다르다. 구조·회계 검증에는 쓰되, 엔진 계산 정답(golden)으로 쓰기 전에 생성기를 수정한다.

### 4.4 제어 메시지 `ControlMessage` — [`examples/control-cancel.json`](examples/control-cancel.json)

`backtest.control`(fanout)로 모든 워커에 전달된다. `{ schemaVersion=2, type=CANCEL, runId, requestedAt }`.
워커는 자기 프로세스가 실행 중인 `runId`면 **다음 거래일 처리 시점**에 멈추고 `FINISHED(status=CANCELLED, partial=true)`를 발행한다. 모르는 `runId`는
무시한다(다른 워커의 실행이거나 이미 끝났다). 발행 측은 백엔드 `RabbitBacktestControlPublisher`(비영속, routing key 없음, `correlationId=runId`,
헤더 `schemaVersion`), 수신 루프는 워커 `rabbitmq.run_control_listener`(별도 연결·스레드, exclusive·auto-delete 큐, auto-ack)다. 워커는 받은 runId를
`CancelRegistry`에 표시하고 엔진이 거래일마다 확인한다. 끝난 runId의 표시는 지운다 — 재전달된 같은 runId가 옛 취소로 멈추면 안 된다(T011).

## 5. 표기 규약

| 설계 표기 | JSON | Java | Python |
|---|---|---|---|
| 금액·비율 (`decimal/string`) | **문자열** `"10000000.00"`, `"0.166652"`. **plain 표기만** — 지수 표기(`"1E+2"`)는 쓰지 않는다. 수신 측은 plain으로 정규화해 저장하므로 값은 같고 음수 scale만 0이 된다 | `BigDecimal` (`toPlainString`으로 직렬화, 숫자도 수용) | `Decimal` |
| 거래일 (`date`) | `"2025-01-03"` 타임존 없음, 거래소 현지 거래일 | `LocalDate` | `date` |
| 실행 시각 (`datetime`) | `"2026-09-07T01:00:00Z"` UTC | `Instant` | aware `datetime` |
| 정수 | JSON number | `int` / `Integer` | `int` |
| enum | 대문자 스네이크 문자열. 미지의 값은 역직렬화 실패 → 계약 오류 → DLQ | enum | `StrEnum` |
| 계산 불가 · 미지정 | **키 자체를 쓰지 않는다** (`null`도 쓰지 않음, 0·NaN 금지). 수신 측은 "키 없음 == null"로 읽고, 명시 `null`이 와도 같은 뜻으로 수용한다 | 래퍼 타입, 직렬화는 `NON_NULL` | `T \| None = None`, 덤프는 `exclude_none=True` |
| 수익률 | 비율 (`0.125` = 12.5%). MDD·drawdown은 0 또는 음수 | | |
| 식별자 문자열 (`eventId`·`executionId`·`tradeId`·`run.error.code`) | **255자 이하**. 초과는 계약 오류 → DLQ. 형식은 정하지 않는다(UUID·`EVT-000001` 모두 가능). `runId`는 비어 있지 않으면 된다 | `String`, 저장 컬럼 `VARCHAR(255)` | `str` |
| 종목 식별자 (`instrumentId` — universe·trade·position) | `{6자리 종목코드}.XKRX` (ADR-0002 D4). 형식 위반은 계약 오류 → DLQ | `InstrumentId` VO의 패턴으로 검증, 저장 컬럼 `VARCHAR(32)` | `str` |

지표 계산 규약(연환산 252, ddof=1, riskFreeRate 0.0, CAGR 달력일 기준, `tradeCount`는 CLOSED만)은 설계 문서를 따른다.
이 규약을 바꾸면 `schemaVersion`이 아니라 결과 저장소의 지표 버전을 올린다.

## 6. 픽스처 불변식

`examples/` 여덟 파일은 [`generate_fixtures.py`](generate_fixtures.py)로 만들었다(시드 42, KRX 휴장일 미반영 평일 129일).
아래 관계가 실제로 성립하며, 백엔드 결과 Consumer 검증과 통합 테스트의 단언으로 그대로 쓴다.

- 이벤트의 `runId` == `engine-input.json`의 `run.runId` (`BT-20260907-000001`), `executionId` == `EXEC-000001`
- `portfolioSeries[0].equity == performance.initialEquity == portfolio.initialCash`, `portfolioSeries[-1].equity == performance.finalEquity`
- 모든 스냅샷에서 `equity == cash + positionValue`, `drawdown <= 0`, 날짜 strictly ascending
- `positions`가 있는 날 `Σ marketValue == positionValue`; `cashFlows`가 있는 날 현금이 그만큼 늘어난다(취소 픽스처에만 있다)
- CLOSED 거래마다 `realizedPnl == grossPnl - commission - slippageCost`; `performance.tradeCount == CLOSED 거래 수`(2), `winRate = 0.5`
- `costs.total == commission + spread + slippage + tax == performance.totalTradingCost`
- `event-progress.json`: `sequence=1`, 포인트 20개 == `completedTradingDays`, `totalTradingDays=129` == 완료 픽스처 `portfolioSeries` 길이, `lastTradingDate` == 마지막 포인트 날짜
- `event-finished-completed.json`: `sequence=8` (= PROGRESS 7회 + 1), `status=COMPLETED`, `partial=false`, `execution.totalCommission > 0`
- `event-finished-failed.json`: `status=FAILED`, `run.error.code=DATA_INSUFFICIENT`, `performance`·`execution` 키 없음, `partial=false`
- `event-finished-cancelled.json`: `sequence=4` (= 60거래일 → PROGRESS 3회 + 1), `status=CANCELLED`, `partial=true`, `lastTradingDate=2025-03-25` == `portfolioSeries[-1].date`
- `engine-input.json`: `allocation.parameters.threshold`는 JSON 숫자 `0.05`. round-trip 후에도 숫자여야 한다
- 픽스처에는 `null` 값이 하나도 없다(§5 규칙). 백엔드·워커 round-trip 테스트는 픽스처 → 모델 → JSON을 **키 순서만 무시한 정확 동치**로 비교한다.
  nullable 필드를 정규화하는 헬퍼는 두지 않는다 — 필수 필드가 빠지면 픽스처에 키가 있고 출력에 없어 그대로 실패한다.

```bash
python3 docs/specs/backtest-messaging/generate_fixtures.py docs/specs/backtest-messaging/examples
```

**완료·실패·취소 픽스처는 독립 시나리오다.** 같은 `runId`·`executionId`를 쓰지만 하나의 이벤트 흐름으로 연속 투입하지 않는다.
Consumer 테스트는 시나리오마다 저장 상태를 초기화한다. 완료 픽스처의 수치는 v1과 같다(API 예시 `docs/specs/backtest-api/examples`가 거기서 파생됐다).

## 7. 전달·저장 규칙 요약 (설계 §5)

- 작업 발행: run·request 커밋 **뒤** 발행(spec 004 R-18). publisher confirm·라우팅 확인 후 `published_at` 기록, 기록이 없는 QUEUED run은 재발행. 202는 "접수". 현행 코드(트랜잭션 안 발행)는 T005에서 바꾼다.
- 실행 Consumer: manual ACK, prefetch 1. FINISHED 발행이 브로커에서 확인된 뒤 작업 메시지 ACK. 워커가 죽으면 quorum 큐가 한 번 재배달하고(`x-delivery-limit=1`) 두 번째는 DLQ.
- 결과 Consumer: 결과 저장 + 최종 상태를 한 트랜잭션으로 커밋 후 ACK. 처리한 `eventId`·`executionId`는 결과 행에 컬럼으로 남긴다(별도 처리 이력 테이블 없음 — 멱등성은 상태 전이가 보장한다: STARTED는 QUEUED에서만, FINISHED는 비최종 상태에서만 적용). 이미 최종 상태인 실행의 늦은 이벤트는 덮어쓰지 않고 ACK. 모르는 `runId`는 경고 로그 후 ACK(DLQ로 보내지 않음). STARTED가 유실돼 QUEUED인 실행에 온 FINISHED도 적용한다.
- PROGRESS는 DB에 시계열을 저장하지 않고 SSE 경로에 넘긴 뒤 ACK(접속자 없어도 ACK). `stage`·`lastTradingDate`·거래일 수는 실행 행에 갱신한다(T003부터, research R-05).
- JSON 파싱·계약 오류는 requeue 없이 reject → DLQ. 일시 장애는 제한적 재시도.
- FINISHED(CANCELLED)는 실행 모델(T003) 전까지 백엔드가 `UnsupportedOperationException`으로 거절한다(ACK하지 않음, 재시도 후 DLQ). 취소 발행 경로가 없으므로 실제로는 오지 않는다.

## 8. 계약 검증 책임

DTO(Java record / pydantic 모델)는 구조만 표현한다. 필수 필드 검증을 NPE에 맡기지 않고, 수신 측이 **역직렬화 직후** 명시 검증한 메시지만 처리한다.

| 검증 | 백엔드 (결과 Consumer) | 워커 (실행 Consumer) |
|---|---|---|
| 필수 정수 누락·null (`sequence`, `schemaVersion`, `tradeCount` …) | ObjectMapper `FAIL_ON_NULL_FOR_PRIMITIVES` → 역직렬화 실패 | pydantic 기본(int 필수) |
| 정수 자리에 소수 (`completedTradingDays: 1.9`) | `ACCEPT_FLOAT_AS_INT=false` → 실패 (1로 자르지 않음) | pydantic 기본 실패 |
| 미지 enum 값 | Jackson 기본 실패 | pydantic 기본 실패 |
| enum 자리에 숫자 (`type: 0`) | `FAIL_ON_NUMBERS_FOR_ENUMS` → 실패 (ordinal로 바꾸지 않음) | pydantic 기본 실패 |
| 필수 객체·문자열 누락 | 역직렬화 직후 명시 검증 | pydantic 기본(`T \| None = None`이 아니면 필수) |
| 지원하지 않는 `schemaVersion` | 명시 검증 | 명시 검증 |
| `type`과 `payload` 형태 불일치 | `type`을 먼저 읽고 payload 타입을 선택해 변환, 실패 시 거부 | 동일 |
| `EngineInput` 값 불변식 (`run.end >= start`, `effectiveStart` 범위, `universe.items` 비어 있지 않고 중복 없음, `costProfile` bps ≥ 0, `progress.batchTradingDays > 0` …) | — (발행 측) | 명시 검증(pydantic validator, README §4.1) |
| `FINISHED`의 `payload.status == engineOutput.run.status`, `partial` 일관성 | 명시 검증 | — (엔진 반환 시점에 확인) |
| STARTED·PROGRESS의 `stage`, PROGRESS의 `lastTradingDate` | 명시 검증 | — |
| 식별자 길이 255자 초과 (§5) | 명시 검증 | — |
| `instrumentId` 형식 (§5, trade·position) | 명시 검증 (`InstrumentId.REGEX`) | universe 항목은 명시 검증, 결과는 엔진 입력의 종목이 그대로 나감 |
| `sequence` 규칙 (STARTED=0, PROGRESS·FINISHED ≥ 1. 연속성은 검증하지 않음) | 명시 검증 | — |
| 상태별 nullable 규칙 (COMPLETED·CANCELLED: `performance`·`execution` 필수, `error` 없음. CANCELLED: `partial=true`·`lastTradingDate` 필수. FAILED: `error` 필수, `performance`·`execution` 없음, `portfolioSeries`·`trades` 비어야 함) | 명시 검증 (중첩 그래프 전체, 위반 필드 경로를 메시지에 포함) | — |
| CANCELLED 완료 범위 일치 (`payload.lastTradingDate` == `engineOutput.run.lastTradingDate` == `portfolioSeries` 마지막 날짜) | 명시 검증 — 실행에는 payload 값을, 결과에는 `engineOutput` 전체를 저장하므로 어긋나면 화면의 완료 날짜와 요약·시계열 범위가 달라진다 | 엔진이 `assemble_output` 한 곳에서 세 값을 함께 채운다 |
| 계약이 nullable로 정한 생략 (성과 지표 개별 null, snapshot의 `positions`·`cashFlows`, OPEN 거래의 청산 필드, v2 추가 블록 전부) | 그대로 수용. **기본값으로 보정하지 않는다** — `trades` 누락을 `[]`로 저장하면 거짓 결과가 확정된다 | — |
| 알 수 없는 필드 | 무시(`FAIL_ON_UNKNOWN_PROPERTIES=false`) — 상대가 필드를 먼저 추가해도 깨지지 않게 | 무시(`extra="ignore"`) |

검증 실패는 계약 오류로 보고 requeue 없이 reject → DLQ(§7). 픽스처 round-trip 테스트는 DTO 구조가 계약과 맞는지 확인하는 것이며 실제 수신 메시지 검증을 대신하지 않는다.
계약을 통과했지만 **이 엔진이 계산할 수 없는 요청**(다종목, 시나리오, 비용 프로파일 bps ≠ 데모값 등)은 워커 `engine_support.py`가 실행 전에 `INVALID_REQUEST`로 거절한다 — 계약 검증이 아니라 엔진 지원 범위다.
