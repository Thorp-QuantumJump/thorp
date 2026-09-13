# worker — Backtest Worker

## 다종목 리밸런싱 엔진 (spec 004)

Python 3.11, NautilusTrader **1.219.0**. 엔진은 실행과 결과 계산만 담당하고 브로커를 모른다.
브로커 연동은 아래 [실행 Consumer (RabbitMQ)](#실행-consumer-rabbitmq)가 담당하며, 결과 DB
저장과 SSE는 워커가 아니라 백엔드 결과 Consumer 몫이다(계약 §1).

설계 근거는 [spec 004](../specs/004-backtest-execution/spec.md)·
[research](../specs/004-backtest-execution/research.md)(R-01 전략 하나, R-09 자료 공급자, R-14 결정성),
메시지 계약은 [`docs/specs/backtest-messaging/README.md`](../docs/specs/backtest-messaging/README.md)다.
이전 단일 종목 모멘텀 DEMO 엔진의 가이드([`docs/guides/nautilus-momentum-engine.md`](../docs/guides/nautilus-momentum-engine.md))는
역사 문서로 남겨 두었다 — 그 엔진은 T006에서 삭제했다.

### 설치와 실행

```bash
cd worker
uv sync --python 3.11 --group dev
uv run python -m worker.demo --input <EngineInput.json> --output engine-output.json
```

`worker.demo` 는 **픽스처 자료로만** 돈다(`--fixture`, 기본 `examples/three_etf_24m`). 실제 시세는
받지 않는다 — 운영 자료는 001이 적재한 PostgreSQL(`MARKET_DATA_URL`)에서 `worker.main` 이 읽는다.
결과 파일은 둘이다.

- `engine-output.json` — 메시지 계약 `EngineOutput`. 브로커로 나갈 payload 그 자체다.
- `engine-output-artifacts.json` — 로컬 진단 산출물 `RunArtifacts`(주문 원장 `orders`,
  재현성 `metadata`). 메시지 계약에 없으므로 브로커로 나가지 않는다.

`examples/three_etf_24m/` 는 **합성 자료**다(3종목 2023-01 ~ 2024-12, 분배금 2건, 2024-03-04 2:1
분할, 2024-06-11 거래 정지 1일). 손으로 고치지 말고 `scripts/make_three_etf_fixture.py` 를 고쳐 다시
만든다. 결과의 `versions.data = "FIXTURE-three_etf_24m"` 가 픽스처임을 표시한다.

uv 없이 설치하려면 `python -m pip install -e . -r requirements-dev.txt`를 사용할 수 있다.

### 엔진을 직접 호출하기

```python
from worker.contracts.engine_input import EngineInput
from worker.contracts.events import ProgressPoint
from worker.engine_runner import PortfolioBacktestEngine
from worker.market_data import CsvMarketDataProvider

def on_progress(point: ProgressPoint, done: int, total: int) -> None:
    ...  # 거래일 1개 = 1회. 20거래일 묶음 PROGRESS 발행은 Consumer 몫이다

engine = PortfolioBacktestEngine(CsvMarketDataProvider("examples/three_etf_24m"))
output = engine.run(EngineInput.model_validate_json(body), on_progress)
output.to_json()  # camelCase, 계산 불가 값은 키 생략
```

- 공개 실행 경로는 `PortfolioBacktestEngine.run(engine_input, on_progress) -> EngineOutput`.
  자료 공급자는 생성자 인자다(기본값 없음 — 운영은 `PostgresMarketDataProvider`, 테스트·데모는
  `CsvMarketDataProvider`). 주문 원장·metadata 가 필요하면 `run_with_artifacts(...)`.
- **계약은 통과하지만 이 엔진이 계산할 수 없는 요청은 실행 전에 거절한다**
  (`engine_support.py`, `run.error.code=INVALID_REQUEST`). 거절 대상: `marketData.fillPrice≠RAW`,
  `signalPrice≠ADJUSTED`, `allocation.rules` 없음, 미등록 전략(팀 미확정 `SECTOR_MOMENTUM`·
  `MINIMUM_VARIANCE` 는 "정하지 않았다"로 구분해 말한다), `rebalance≠MONTH_END`, 레버리지·공매도·
  부분 체결·KRW 외 통화·분수 수량, 시나리오 `COST_ESCALATION`(비용 프로필에 `escalation` 규칙이 없다),
  `TARGET_RISK` 에 `scenarioRules`·`scenarioParams.targetVolatility` 누락. 여러 항목이 동시에 미지원이면 한
  메시지에 전부 담는다.
- 자료·배분 오류는 `run.status=FAILED` 와 `run.error` 로 돌아온다: 거래일 행 부재·워밍업 부족
  `DATA_INSUFFICIENT`, 규칙 값 누락 `INVALID_REQUEST`, 조건을 만족하는 배분이 없음
  `CONSTRAINT_VIOLATION`, 목표 위험 추정에 수익률 부족 `ESTIMATION_FAILED`, 엔진 자체 불변식 위반
  `ENGINE_ERROR`. `performance`·`execution` 은 채우지 않는다(키가 빠진다).
- **일시 장애는 결과가 아니다.** 자료 적재(`MarketDataProvider.load`)에서 나는 접속 실패 같은 예외는
  `FAILED` 로 바꾸지 않고 전파한다 — 소비자가 ACK 하지 않고 재전달 경로로 넘긴다(계약 §7 ③). 결과로
  바꾸면 FINISHED 가 발행·ACK 되어 일시 장애가 최종 실패로 굳는다. 자료가 메모리에 오른 뒤의 예외는
  전부 결정적 결함이라 `ENGINE_ERROR` 결과다.
- `execution.delisting=CAUSE_BASED` 는 받되 사유 분류 미지원을 `diagnostics.warnings` 에
  `DELISTING_CLASSIFICATION_UNSUPPORTED` 로 실행당 1건 남긴다. `run.randomSeed` 생략은 42.

### 체결·계산 규칙

- **하루 순서.** 09:00 종목별 시가 QuoteTick(bid/ask = 시가 ∓ 호가차·슬리피지) → 첫 틱에서 그날의
  기업행위(분할 → 수량, 권리락일 → 분배금 자격, 지급일 → 현금) → 마지막 틱에서 전날 판단한 주문을
  **매도 먼저, 매수 나중**에 시장가 제출 → 15:30 종가 Bar → 마지막 봉에서 평가·스냅샷 → 판단일이면
  `allocation.compute_weights`(조정 종가 이력, 판단일까지만)로 목표 비중 → 정수 수량 주문 생성.
- **판단일**은 평가 시작일과 매월 마지막 거래일(`parameters.rebalance=MONTH_END`). 마지막 평가일은
  판단하지 않는다. 체결일은 항상 판단일 다음 거래일이다(SC-004).
- **거래 정지**(`trading_suspended`, 거래량 0 은 정지가 아니다)인 종목의 주문은 다음 거래 가능일로 미루고
  `trades[].reason=HALT_DEFERRED`, `plannedDate` 와 `exceptions[{kind: HALT_DEFERRED}]` 를 남긴다.
  다른 종목은 예정일에 그대로 체결된다.
- **정수 수량·재정규화 금지.** 목표 비중을 정수 주로 못 채운 몫은 현금으로 남고 리밸런싱마다
  `weightsSeries[{target, actual, weightError}]` 를 적는다(이월 주문이 다 체결된 날). 한 주도 못 사면
  `exceptions[{kind: WEIGHT_ROUNDING}]`. 체결일 시가가 판단일 종가보다 올라 현금이 모자라면 살 수
  있는 만큼만 사고 `INSUFFICIENT_CASH` 경고.
- **비용 네 갈래**(`costs.py`): 호가차·슬리피지는 체결가에, 수수료·세금(매도만)은 현금에서. 합계는
  `costs{commission, spread, slippage, tax, total}`. `trades[].commission` 은 양쪽 수수료 합이고
  세금은 `realizedPnl` 과 `costs.tax` 에만 반영된다(거래 계약에 세금 칸이 없다).
- **분배금**은 권리락일 시작 시점 보유로 자격이 생기고 지급일에 한 번 현금이 된다
  (`portfolioSeries[].cashFlows`). **분할**은 보유 수량·묶음 진입가·**그날 체결할 미체결 주문 수량**을
  같은 비율로 바꾼다(거래 아님).
- **목표 위험 맞춤**(`scenario=TARGET_RISK`, PD-18): 판단일마다 목표 비중 포트폴리오의 최근
  `scenarioRules.lookbackDays`(63) 일별 수익률 표준편차 × √252 를 추정치로 삼고, 목표
  (`scenarioParams.targetVolatility`)를 넘으면 비중에 `target/estimate` 를 곱해 나머지를 현금으로 둔다
  (내리기만). `weightsSeries[].riskScale`·`estimatedVolatility` 에 남고 `versions.scenarioRules` 가 규칙
  버전이다. 수익률이 모자라면 `ESTIMATION_FAILED`.
- **청산**(`evaluation.liquidationAtEnd`)은 종료일 종가에 전량 매도했다면 얼마가 남는가의 가정치다
  (`liquidation{markToMarket, liquidated}`). `trades`·`costs` 합계에 넣지 않는다.
- **워밍업·평가 시작**: `FRESH_CASH_FROM` 의 `scenarioParams.startDate` → `run.effectiveStart` →
  `evaluation.warmupTradingDays` → `rules.lookbackDays` → 0 순으로 정하고 `run.effectiveStart/End` 에
  실제 값을 적는다. 성과·스냅샷·진행 콜백은 평가 시작일부터다.
- **Nautilus 는 주문 경로와 체결 이벤트만 준다.** 원장(현금·보유·묶음)은 워커 장부다. 계좌는
  MARGIN — 분할 뒤 늘어난 수량을 팔면 CASH 계좌는 공매도로 보고 거절하기 때문이다. 공매도·현금 음수
  금지는 장부가 지키고, 체결가는 매 건 그 방향 호가와 같아야 한다(FillModel 슬리피지 0).
- **결정성.** 이벤트는 날짜 → 종목 이름순으로 넣고 주문도 같은 순서라, 같은 입력은
  `run.startedAt/finishedAt` 을 뺀 `EngineOutput` 이 바이트 단위로 같다.
- 지표(`performance.py`): Sharpe 252일·RF 0·ddof 1, CAGR 은 달력 일수, MDD 음수, CLOSED 만
  거래 수·승률. 확장(순수익률·분석 블록·수기 대조)은 T008.

필수 검증:

```bash
uv run ruff check src tests scripts && uv run ruff format --check src tests scripts && uv run pytest
```

- `tests/test_portfolio_execution.py` 3종목 픽스처 끝까지: 체결일 = 판단일 다음 거래일, 첫날 현금,
  정수 수량, `equity == cash + positionValue`, 비중 오차 기록, 거래 정지 이월, 분배금 1회, 분할 2배,
  비용 네 갈래·청산, 워밍업·`FRESH_CASH_FROM`, 실패 코드, 진행 콜백, 바이트 동일 결정성.
- `tests/test_engine_support.py` 지원 범위 거절과 v2 정책 수용.
- `tests/test_allocation.py`·`test_costs.py`·`test_corporate_actions.py` 순수 모듈(T007).
- `tests/test_market_data.py` 픽스처·PostgreSQL 공급자 매핑과 fail-closed 검증.

## 실행 Consumer (RabbitMQ)

RabbitMQ `backtest.jobs` 큐에서 작업 메시지(`EngineInput` 전체)를 받아 엔진을 실행하고
`backtest.events` 큐로 `STARTED` / `PROGRESS` / `FINISHED`를 발행한다. 같은 코드를 여러 개
실행해(competing consumers) 수평 확장한다. 계약은
[`docs/specs/backtest-messaging/README.md`](../docs/specs/backtest-messaging/README.md)
(§2 토폴로지, §3 메시지 속성, §4.2 envelope, §7 전달 규칙, §8 검증 책임)와
[ADR-0002](../docs/adr/0002-rabbitmq-backtest-message-contract.md)다.

```bash
# 접속 정보는 환경변수로만 주입한다. MARKET_DATA_URL 은 기본값이 없다 — 없으면 시작하지 않는다.
export RABBITMQ_URL=amqp://thorp:thorp@localhost:5672/
export MARKET_DATA_URL=postgresql://thorp_ro:...@localhost:5432/thorp
uv run python -m worker.main
```

| 파일 | 역할 |
|---|---|
| `messaging.py` | 계약 상수(exchange·queue·routing key, `SCHEMA_VERSION=2`, prefetch 1)와 경계 Protocol(`EventPublisher`, `JobAcknowledger`), 오류 분류. **pika 없음** |
| `consumer.py` | `BacktestJobConsumer` — 검증 → 엔진 호출 → 발행 순서 → ACK 판단. 브로커 기술을 모른다 |
| `rabbitmq.py` | pika 구현: publisher confirm + `mandatory` 발행, ACK/reject 매핑, 소비 루프 |
| `main.py` | 엔트리포인트(`python -m worker.main`) |

동작 규칙:

- **manual ACK, prefetch 1.** `FINISHED` 발행이 브로커에서 확인된 뒤에만 작업 메시지를 ACK한다
  (계약 §7). publisher confirm과 `mandatory`를 켜서 라우팅 실패도 확인한다 — 발행이 확인되지
  않으면 ACK하지 않는다.
- `sequence`는 `STARTED`=0에서 1씩 증가한다. `PROGRESS`는 작업 메시지의
  **`progress.batchTradingDays` 묶음**이고 이번 구간에서 새로 계산한 포인트만 담으며
  **마지막 잔여 구간도 발행**한다. 엔진 콜백은 거래일 1개 단위로 오므로 묶는 것은 Consumer 책임이다.
  STARTED의 `stage`·`resource{pid, hostname}`, PROGRESS의 `stage`·`lastTradingDate`, FINISHED의
  `status`·`partial`·`lastTradingDate`·`resource{peakRssMb, cpuSeconds, wallSeconds}`는 Consumer가
  채운다. `peakRssMb`는 `resource.getrusage`로 재고, 못 재는 플랫폼(Windows)에서는 키를 생략한다.
- 메시지 속성(계약 §3): `content_type=application/json`, `delivery_mode=2`,
  `message_id`=본문 `eventId`, `correlation_id`=`runId`, 헤더 `schemaVersion`.
  워커는 Spring의 `__TypeId__` 헤더를 보내지 않는다.
- `executionId`는 엔진 실행 시도마다 새 값, `eventId`는 이벤트마다 고유하고 재발행 시 유지한다
  (중복 방지 키 — 계약 §4.2).
- **`schemaVersion` 검증.** 상수는 `messaging.SCHEMA_VERSION = 2`(v1 메시지는 계약 오류). 헤더나 본문이 다른 값을
  표시하면 계약 오류로 보고 거절한다. 작업 메시지 본문은 `EngineInput` 전체이고 계약 픽스처에
  `schemaVersion` 키가 없으므로, 양쪽 모두 표시하지 않은 경우는 통과시킨다.
- **오류 3분류** (계약 §7·§8):

  | 상황 | 처리 |
  |---|---|
  | JSON 파싱 실패, pydantic `ValidationError`, 미지원 `schemaVersion` | 계약 오류. requeue 없이 reject → DLQ |
  | 엔진이 `FAILED` `EngineOutput` 반환(`INVALID_REQUEST`·`DATA_INSUFFICIENT`·`CONSTRAINT_VIOLATION`·`ENGINE_ERROR`) | 정상 결과. 그대로 `FINISHED`에 담아 발행하고 ACK. DLQ 아님 |
  | 발행 실패·브로커 일시 장애(`CallbackError` 포함) | 일시 장애. 제한적 재시도 후 ACK하지 않고 requeue |

- **실행 Consumer는 `EngineOutput` 내용을 해석하지 않는다.** 성공·실패 모두 같은 `FINISHED`다.
- **멱등성: 같은 `runId`가 재전달되면 그냥 다시 실행하고 `executionId`만 새로 발급한다.**
  워커는 결과 DB에 쓰지 않는 게 계약(§1)이라 처리 이력을 남길 곳이 없고, 고정 입력에 대해
  결정론적이라 재실행이 안전하다. 중복 방어는 계약 §7이 백엔드 결과 Consumer 쪽에 요구하며
  ("이미 최종 상태인 실행의 늦은 이벤트는 덮어쓰지 않고 ACK") `eventId`가 그 키다. 작업 큐는
  quorum + `x-delivery-limit=1`이라 워커가 죽어 requeue된 작업은 한 번만 다시 배달된다.
- **벤치마크는 계약 v2에 없다.** `EngineInput.benchmark`도 `FINISHED.benchmark`도 사라졌고
  v1의 `consumer.strip_benchmark`도 제거했다. 벤치마크 비교는 별도 spec에서 다룬다.
- **토폴로지(exchange·queue·binding)는 워커가 선언하지 않는다.** 선언은 백엔드
  (Spring `Declarables`) 책임이다(계약 §2). 워커는 시작할 때 `backtest.jobs` 존재만 확인하고,
  없으면 큐를 만들지 않고 "백엔드를 먼저 띄워 토폴로지를 선언하라"는 오류를 남기고 종료한다
  (exit code 1). 속성이 어긋난 큐를 워커가 만들면 백엔드 선언과 충돌해 훨씬 찾기 어려워진다.

### 로컬 실행 절차

**순서가 중요하다. 토폴로지 선언은 백엔드 몫이므로 백엔드를 한 번 띄워 큐가 만들어진 뒤에
워커를 실행한다.** 브로커를 새로 만들었다면 `backtest.jobs`가 아직 없다.

```bash
# 1. 브로커·DB (저장소 루트)
docker compose -f docker-compose.dev.yml up -d rabbitmq postgres

# 2. 백엔드를 한 번 띄워 토폴로지를 선언한다 (exchange 2, queue 4, binding 4)
cd backend && ./gradlew bootRun

# 3. 큐가 생겼는지 확인 (관리 UI http://localhost:15672, thorp/thorp)
docker exec thorp-rabbitmq rabbitmqctl list_queues name messages

# 4. 워커 (MARKET_DATA_URL 은 001 이 적재한 표가 있는 DB)
cd worker && RABBITMQ_URL=amqp://thorp:thorp@localhost:5672/ MARKET_DATA_URL=postgresql://... uv run python -m worker.main
```

### 실제 브로커 연동 확인

pytest는 브로커 없이 fake로 검증한다(아래 "테스트"). 실제 연동은 사람이 한 번 확인한다.

위 "로컬 실행 절차" 1~4를 먼저 수행한다. 워커가
`worker consuming queue=backtest.jobs prefetch=1` 을 남기면 준비된 상태다. 작업 메시지는 손으로
한 건 넣는다(백엔드가 넣을 것과 같은 속성으로 — 계약 §3). 본문은 계약 픽스처
`docs/specs/backtest-messaging/examples/engine-input-target-risk.json` 을 바탕으로 `run.scenario` 를
`FULL_PERIOD` 로 바꾸고 `marketData.dataVersion` 을 DB 의 `dataset_id` 로 맞춘 것을 쓴다.

```bash
cd worker
uv run python - <<'PY'
import pathlib
import pika

body = pathlib.Path("engine-input.json").read_text(encoding="utf-8")
connection = pika.BlockingConnection(
    pika.URLParameters("amqp://thorp:thorp@localhost:5672/")
)
channel = connection.channel()
channel.confirm_delivery()
channel.basic_publish(
    exchange="backtest",
    routing_key="backtest.job.requested",
    body=body.encode("utf-8"),
    properties=pika.BasicProperties(
        content_type="application/json",
        delivery_mode=2,
        message_id="JOB-LOCAL-000001",
        correlation_id="RUN-000123",
        headers={"schemaVersion": 2},
    ),
    mandatory=True,
)
connection.close()
print("published")
PY
```

발행 후 확인:

```bash
# backtest.jobs 는 0이어야 한다(ACK됨), backtest.events 에 STARTED 1 + PROGRESS n + FINISHED 1
docker exec thorp-rabbitmq rabbitmqctl list_queues name messages
```

이벤트 본문·속성은 관리 UI(<http://localhost:15672>, thorp/thorp)의
Queues → `backtest.events` → Get messages(Requeue: Yes)로 본다. 확인할 것:

- `backtest.jobs`가 0으로 비었는가 (= FINISHED 발행 후 ACK됐다)
- 첫 메시지가 `type=STARTED`·`sequence=0`이고 마지막이 `type=FINISHED`인가
- 각 메시지의 `correlation_id`가 본문 `runId`와 같고 `message_id`가 본문 `eventId`와 같은가
- 헤더에 `schemaVersion=2`가 있고 `__TypeId__`가 **없는가**
- `FINISHED` payload의 `status`가 `engineOutput.run.status`와 같고 `partial=false`인가

계약 오류 처리는 위 스크립트의 `body`를 깨진 JSON(`b"{"`)으로 바꿔 발행한 뒤
`backtest.jobs.dlq`에 1건이 쌓이는지로 확인한다(`backtest.jobs`는 다시 0).

### 테스트

```bash
uv run ruff check src tests scripts && uv run ruff format --check src tests scripts && uv run pytest
```

- `tests/test_consumer.py` 발행 순서·`sequence`, 묶음 크기와 잔여 구간, **FINISHED 발행
  확인 전 ACK 금지**, 계약 오류 reject, 엔진 FAILED의 ACK, 재전달 시 새 `executionId`,
  `eventId` 고유성, 계약 픽스처와의 payload 모양 대조. 엔진은 실제 `PortfolioBacktestEngine` +
  `CsvMarketDataProvider`(3종목 픽스처, 네트워크 없음)를 쓴다.
- `tests/test_rabbitmq.py` 메시지 속성, 발행 확인·재시도, ACK/reject 매핑, 큐 부재 처리,
  그리고 큐·routing key 상수가 백엔드 `BacktestRabbitConfig.java`와 같은지 대조.
