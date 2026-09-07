# 백테스트 메시지 계약 (Backend ↔ Worker, RabbitMQ)

- 상태: 초안 (2026-09-07). 결정 사항은 [ADR-0002](../../adr/0002-rabbitmq-backtest-message-contract.md)
- 원본 설계: Notion `NautilusTrader 설계(input, output)` — "INPUT Spec", "OUTPUT Spec", "RabbitMQ 연동 및 실시간 그래프 설계"
- 이 폴더의 `examples/*.json`이 **양쪽 코드의 단일 기준**이다. 백엔드(`backtest/application/port/message`)와
  워커(`worker/src/worker/contracts`)는 이 파일을 테스트에서 직접 읽어 round-trip을 검증한다. 필드를 바꾸면 이 폴더 →
  백엔드 DTO → 워커 모델 순서로 같은 MR에서 함께 고친다.

## 1. 역할

| 구성 요소 | 책임 |
|---|---|
| Backend API | 요청 검증, `backtestId` 생성, Job(PENDING)·outbox 저장, 작업 발행, 상태·결과 조회, SSE |
| RabbitMQ | 작업·이벤트 메시지 보관·전달 |
| Worker (실행 Consumer) | 작업 수신, `BacktestEngine.run()` 호출, 벤치마크 계산, STARTED / PROGRESS / FINISHED 발행, ACK |
| 엔진 (`BacktestEngine` 구현) | `EngineInput` → `EngineOutput`. 진행 콜백 제공. **DB·브로커를 모른다** |
| Backend 결과 Consumer | 이벤트 검증, Job 상태 갱신, 최종 결과 저장, SSE 전달 |

```
Frontend → POST /backtests → Job 저장(PENDING) → [backtest.jobs] ← 202 + backtestId
[backtest.jobs] → Worker → engine.run() → STARTED / PROGRESS… / FINISHED → [backtest.events]
[backtest.events] → Backend 결과 Consumer → RUNNING / SSE progress / 결과 저장 + COMPLETED|FAILED
```

워커와 엔진은 결과 DB에 직접 쓰지 않는다. 성과 지표는 엔진이 계산하고 백엔드는 저장·조회만 한다.

## 2. RabbitMQ 토폴로지

| 항목 | 이름 | 속성 |
|---|---|---|
| Exchange | `backtest` | direct, durable |
| 작업 큐 | `backtest.jobs` | durable, DLX `backtest.dlx`, dead-letter key `backtest.job.dead` |
| 작업 binding | `backtest` → `backtest.jobs` | routing key `backtest.job.requested` |
| 이벤트 큐 | `backtest.events` | durable, DLX `backtest.dlx`, dead-letter key `backtest.event.dead` |
| 이벤트 binding | `backtest` → `backtest.events` | routing key `backtest.event` |
| Dead-letter exchange | `backtest.dlx` | direct, durable |
| 작업 DLQ | `backtest.jobs.dlq` | binding key `backtest.job.dead` |
| 이벤트 DLQ | `backtest.events.dlq` | binding key `backtest.event.dead` |

선언은 백엔드 애플리케이션이 한다(Spring `Declarables`). 인프라가 미리 만들어 두어도 같은 속성이면 충돌하지 않는다.
DLQ로 간 메시지는 자동 재투입하지 않고 원인 수정 후 운영자가 재처리한다.

## 3. 메시지 속성 (작업·이벤트 공통)

| 속성 | 값 |
|---|---|
| `content_type` | `application/json` |
| `delivery_mode` | 2 (persistent) |
| `message_id` | 작업: 발행 측 고유 ID(재시도 시 유지). 이벤트: 본문 `eventId` |
| `correlation_id` | `backtestId` |
| header `schemaVersion` | `1` |

워커(pika)는 Spring의 `__TypeId__` 헤더를 보내지 않는다. 백엔드는 헤더가 아니라 리스너 파라미터 타입과 본문 `type` 필드로 역직렬화한다.

## 4. 메시지 본문

### 4.1 작업 메시지 = `EngineInput` 전체 — [`examples/engine-input.json`](examples/engine-input.json)

축약 모델을 따로 두지 않는다. 백엔드가 사용자 요청(`BacktestRequest`: run.start/end, universe, strategy, portfolio, benchmark)에
아래 **고정 실행 정책**과 `run.backtestId`, `run.randomSeed`, `strategy.version`, `marketData`를 채워 발행한다.

| 경로 | 고정값 |
|---|---|
| `marketData` | `datasetId=KRX_DAILY_V1`, `interval=1D`, `adjustment=SPLIT_ADJUSTED`, `priceType=LAST` |
| `portfolio.currency` / `shortSelling` / `leverage` | `KRW` / `false` / `"1.0"` |
| `execution.signalTiming` / `executionTiming` | `BAR_CLOSE` / `NEXT_BAR_OPEN` (t 종가 신호 → t+1 시가 체결) |
| `execution.orderTypes` | `["MARKET"]` |
| `execution.commission` | `model=BROKER`, `broker=KIWOOM`, `version=2026-09` |
| `execution.slippage` | `model=FIXED_BPS`, `bps=5` (0.05%) |
| `execution.partialFill` / `liquidity.model` | `false` / `UNLIMITED` |
| `execution.tradingHalt` / `delisting` | `WAIT_UNTIL_RESUME` / `CAUSE_BASED` |

`benchmark`는 nullable. `strategy.parameters`는 전략별 자유 형식 객체 — **정수는 정수, 소수는 JSON 숫자로 그대로 통과**시키고
문자열로 바꾸지 않는다(금액·비율 문자열 규칙은 선언된 decimal 필드에만 적용). 픽스처의 `threshold: 0.05`가 이 규칙의 round-trip 검사용이다.

### 4.2 이벤트 envelope — `event-*.json` 공통

| 필드 | 타입 | 의미 |
|---|---|---|
| `schemaVersion` | integer | `1` |
| `eventId` | string | 이벤트 고유 ID. 재발행 시 유지 (중복 방지 키) |
| `backtestId` | string | Job 식별자. `EngineInput.run.backtestId`와 같다 |
| `executionId` | string | 엔진 실행 시도 식별자. 재실행(재전달)마다 새 값 |
| `type` | enum | `STARTED` / `PROGRESS` / `FINISHED` |
| `sequence` | integer | 실행 시도 내 순번. STARTED=0, 이후 1씩 증가 |
| `occurredAt` | string(datetime) | UTC ISO-8601 |
| `payload` | object | type별 본문 |

| type | payload | 예시 |
|---|---|---|
| `STARTED` | `{ startedAt }` | [`event-started.json`](examples/event-started.json) |
| `PROGRESS` | `{ completedTradingDays, totalTradingDays, portfolioSeries[] { date, equity, cumulativeReturn, drawdown } }` — 20거래일 단위, 이번 구간에서 새로 계산한 포인트만, 마지막 잔여 구간도 발행 | [`event-progress.json`](examples/event-progress.json) |
| `FINISHED` | `{ engineOutput, benchmark }` — `engineOutput.run.status`로 성공/실패 판단. `benchmark`는 미지정·실패 시 `null` | [`event-finished-completed.json`](examples/event-finished-completed.json), [`event-finished-failed.json`](examples/event-finished-failed.json) |

### 4.3 `EngineOutput` / `BenchmarkResult`

구조는 설계 문서 OUTPUT Spec을 그대로 따른다. 설계에 없어 이 계약에서 보충한 것:

- `run.error { code, message }` — 실패 시 채우고 성공 시 없음(null). 코드는 설계 ErrorResponse의 `INVALID_REQUEST`, `NO_MARKET_DATA`,
  `STRATEGY_ERROR`, `ENGINE_ERROR`, `INTERNAL_ERROR`. (`INSUFFICIENT_CASH`는 오류가 아니라 `diagnostics.warnings[].code`)
- FAILED일 때 `performance`와 `execution`은 없음(null), `portfolioSeries`·`trades`는 `[]`. 존재하지 않는 성과를 0으로 채우지 않는다.
- `portfolioSeries[].positions`는 보유 구성이 바뀐 거래일 + 시작일 + 종료일에만 존재한다. 그 외 날짜는 필드 자체가 없다.
- `trades[]`의 OPEN 거래는 `exitTime`, `exitPrice`, `grossPnl`, `realizedPnl`, `returnRate`가 없음(null)이고
  `commission`·`slippageCost`는 진입 측만 합산한다.
- 계산 불가 성과 지표(설계 OUTPUT Spec 규칙): 변동성 0 → `sharpeRatio`만 null(`volatility`는 0). 관측치 2개 미만 → `volatility`·`sharpeRatio` null.
  CLOSED 거래 0건 → `winRate` null. `initialEquity ≤ 0` → `totalReturn`·`cagr` null. 기간이 1년 미만이라는 이유로 `cagr`을 null로 만들지 않는다.
- **체결가 의미**: 계약상 `trades[].entryPrice`/`exitPrice`는 슬리피지가 반영된 실제 평균 체결가다. **현재 픽스처는 기준가를 그대로 넣고
  슬리피지를 `slippageCost`로 분리한 단순 회계 예시**라 항등식은 성립하지만 가격 의미는 계약과 다르다. 구조·회계 검증에는 쓰되,
  엔진 계산 정답(golden)으로 쓰기 전에 생성기를 수정한다.

## 5. 표기 규약

| 설계 표기 | JSON | Java | Python |
|---|---|---|---|
| 금액·비율 (`decimal/string`) | **문자열** `"10000000.00"`, `"0.166652"` | `BigDecimal` (문자열로 직렬화, 숫자도 수용) | `Decimal` |
| 거래일 (`date`) | `"2025-01-03"` 타임존 없음, 거래소 현지 거래일 | `LocalDate` | `date` |
| 실행 시각 (`datetime`) | `"2026-09-07T01:00:00Z"` UTC | `Instant` | aware `datetime` |
| 정수 | JSON number | `int` / `Integer` | `int` |
| enum | 대문자 스네이크 문자열. 미지의 값은 역직렬화 실패 → 계약 오류 → DLQ | enum | `Literal` / `StrEnum` |
| 계산 불가 · 미지정 | **키 자체를 쓰지 않는다** (`null`도 쓰지 않음, 0·NaN 금지). 수신 측은 "키 없음 == null"로 읽고, 명시 `null`이 와도 같은 뜻으로 수용한다 | 래퍼 타입, 직렬화는 `NON_NULL` | `T \| None = None`, 덤프는 `exclude_none=True` |
| 수익률 | 비율 (`0.125` = 12.5%). MDD·drawdown은 0 또는 음수 | | |

지표 계산 규약(연환산 252, ddof=1, riskFreeRate 0.0, CAGR 달력일 기준, `tradeCount`는 CLOSED만)은 설계 문서를 따른다.
이 규약을 바꾸면 `schemaVersion`이 아니라 결과 저장소의 지표 버전을 올린다.

## 6. 픽스처 불변식

`examples/` 다섯 파일은 [`generate_fixtures.py`](generate_fixtures.py)로 만들었다(시드 42, KRX 휴장일 미반영 평일 129일).
아래 관계가 실제로 성립하며, 백엔드 결과 Consumer 검증과 통합 테스트의 단언으로 그대로 쓴다.

- 세 이벤트의 `backtestId` == `engine-input.json`의 `run.backtestId` (`BT-20260907-000001`), `executionId` == `EXEC-000001`
- `portfolioSeries[0].equity == performance.initialEquity == portfolio.initialCash`, `portfolioSeries[-1].equity == performance.finalEquity`
- 모든 스냅샷에서 `equity == cash + positionValue`, `drawdown <= 0`, 날짜 strictly ascending
- `positions`가 있는 날 `Σ marketValue == positionValue`
- CLOSED 거래마다 `realizedPnl == grossPnl - commission - slippageCost`; `performance.tradeCount == CLOSED 거래 수`(2), `winRate = 0.5`
- `event-progress.json`: `sequence=1`, 포인트 20개 == `completedTradingDays`, `totalTradingDays=129` == `portfolioSeries` 길이
- `event-finished-completed.json`: `sequence=8` (= PROGRESS 7회 + 1), `execution.totalCommission > 0`, `benchmark` 포함(`069500.XKRX`)
- `event-finished-failed.json`: `run.status=FAILED`, `run.error.code=NO_MARKET_DATA`, `performance`·`execution`·`benchmark` 키 없음
- `engine-input.json`: `strategy.parameters.threshold`는 JSON 숫자 `0.05`. round-trip 후에도 숫자여야 한다
- 픽스처에는 `null` 값이 하나도 없다(§5 규칙). 백엔드·워커 round-trip 테스트는 픽스처 → 모델 → JSON을 **키 순서만 무시한 정확 동치**로 비교한다.
  nullable 필드를 정규화하는 헬퍼는 두지 않는다 — 필수 필드가 빠지면 픽스처에 키가 있고 출력에 없어 그대로 실패한다.

```bash
python3 docs/specs/backtest-messaging/generate_fixtures.py docs/specs/backtest-messaging/examples
```

**성공·실패 픽스처는 독립 시나리오다.** `event-finished-completed.json`과 `event-finished-failed.json`은 같은 `backtestId`·`executionId`를 쓰지만
하나의 이벤트 흐름으로 연속 투입하지 않는다. Consumer 테스트는 시나리오마다 저장 상태를 초기화한다.

## 7. 전달·저장 규칙 요약 (설계 §5)

- 작업 발행: Job + outbox 같은 트랜잭션. publisher confirm·라우팅 실패 확인 후 발행 완료 기록. 202는 "접수".
- 실행 Consumer: manual ACK, prefetch 1. FINISHED 발행이 브로커에서 확인된 뒤 작업 메시지 ACK.
- 결과 Consumer: 결과 저장 + Job 최종 상태 + 처리된 `eventId` 기록을 한 트랜잭션으로 커밋 후 ACK. 이미 최종 상태인 Job의 늦은 이벤트는 덮어쓰지 않고 ACK.
- PROGRESS는 DB에 저장하지 않고 SSE 경로에 넘긴 뒤 ACK(접속자 없어도 ACK).
- JSON 파싱·계약 오류는 requeue 없이 reject → DLQ. 일시 장애는 제한적 재시도.

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
| `FINISHED`의 `engineOutput.run.status`가 COMPLETED/FAILED 외 | 명시 검증 | — (엔진 반환 시점에 확인) |
| 알 수 없는 필드 | 무시(`FAIL_ON_UNKNOWN_PROPERTIES=false`) — 상대가 필드를 먼저 추가해도 깨지지 않게 | 무시(`extra="ignore"`) |

검증 실패는 계약 오류로 보고 requeue 없이 reject → DLQ(§7). 픽스처 round-trip 테스트는 DTO 구조가 계약과 맞는지 확인하는 것이며 실제 수신 메시지 검증을 대신하지 않는다.
