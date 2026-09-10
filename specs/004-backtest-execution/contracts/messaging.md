# Contract: Backtest Messaging v2 (Backend ↔ Worker, RabbitMQ)

`docs/specs/backtest-messaging/README.md`를 이 내용으로 갱신하고 `examples/`에 schemaVersion 2 픽스처를 둔다. 표기 규칙(소수 plain string, 날짜는 거래일, 시각 UTC, 누락 키 = null, 식별자 ≤255자, `{6자리}.XKRX`), 백엔드가 토폴로지를 선언하고 워커는 passive declare만 하는 규칙, ACK 규칙(계약 위반 → reject/DLQ, 발행 실패 → requeue)은 현행 유지.

## 토폴로지

| 이름 | 종류 | 변경 |
|---|---|---|
| `backtest` (direct, durable) | exchange | 유지 |
| `backtest.jobs` | queue | **quorum**, `x-delivery-limit=1`, DLX `backtest.dlx` → `backtest.jobs.dlq`. 전환 시 기존 classic 큐 삭제 필요 |
| `backtest.events` | queue | 유지 |
| `backtest.control` (fanout, durable) | exchange | **신규**. 워커마다 exclusive·auto-delete 큐를 바인딩 |
| `backtest.jobs.dlq` | queue | 유지. **백엔드가 소비**해 run을 FAILED(SYSTEM_FAILURE)로 종결 |

메시지 속성: `content_type=application/json`, `delivery_mode=2`, header `schemaVersion: 2`. 작업 메시지 `message_id = "JOB-" + runId`, `correlation_id = runId`. **식별자는 사용자용 backtestId가 아니라 run 식별자(`RUN-{id}`)다.** 여러 요청이 한 run에 합류하므로 워커는 요청을 알지 못한다.

## 작업 메시지: EngineInput v2 (`backtest.job.requested`)

```json
{
  "run": {"runId": "RUN-000123", "effectiveStart": "2018-04-02", "start": "2018-01-02", "end": "2025-12-30", "randomSeed": 42, "scenario": "FULL_PERIOD", "scenarioParams": null, "scenarioRules": null},
  "universe": {"mappingVersion": "SECTOR-ETF-2026-09", "items": [{"sectorId": "SEMICONDUCTOR", "instrumentId": "091160.XKRX", "targetWeight": "0.4000"}]},
  "marketData": {"datasetId": "KRX_DAILY_V1", "dataVersion": "KRX-2026-09-09", "interval": "1D", "fillPrice": "RAW", "signalPrice": "ADJUSTED", "distributions": "CASH_ON_PAY_DATE"},
  "allocation": {"strategyId": "INVERSE_VOLATILITY", "rulesVersion": "1", "rules": {"lookbackDays": 63, "zeroVolatility": "EXCLUDE_THEN_ALLOCATE", "weightCap": "1.0000", "investedFraction": "1.0000"}, "parameters": {"rebalance": "MONTH_END"}},
  "portfolio": {"initialCash": "10000000.00", "currency": "KRW", "integerLots": true, "shortSelling": false, "leverage": "1.0"},
  "execution": {"signalTiming": "BAR_CLOSE", "executionTiming": "NEXT_BAR_OPEN", "orderTypes": ["MARKET"], "costProfile": {"version": "PROVISIONAL-DEMO-1", "commissionBps": "0", "spreadBps": "0", "slippageBps": "5", "taxBps": "0", "provisional": true}, "tradingHalt": "DEFER_TO_NEXT_TRADABLE", "delisting": "CAUSE_BASED"},
  "evaluation": {"splitVersion": "SPLIT-1", "warmupTradingDays": 63, "liquidationAtEnd": true},
  "progress": {"batchTradingDays": 20}
}
```

- `allocation.rules`는 `strategy_rules.spec` 사본이다. 워커는 여기 없는 기본값을 쓰지 않는다. `strategyId`가 규칙 없이 오면 `INVALID_REQUEST`.
- `run.scenario=TARGET_RISK`면 `run.scenarioParams={"targetVolatility": "0.1000"}`와 `run.scenarioRules={"version": "TARGET_RISK_OVERLAY/1", "lookbackDays": 63, "adjustAt": "REBALANCE", "scaleDownOnly": true}`가 함께 온다(둘 다 백엔드가 채움). 워커는 리밸런싱 판단일마다 최근 `lookbackDays` 포트폴리오 일별 수익률 표준편차 × √252로 변동성을 추정하고, 추정치 > 목표이면 투자 비중 합계에 `target/estimate`를 곱한다. `weightsSeries[]`에 `riskScale`·`estimatedVolatility`를 기록한다. `FRESH_CASH_FROM`이면 `run.scenarioParams={"startDate": "…"}`. 규칙이 빠진 `TARGET_RISK`는 `INVALID_REQUEST`.
- `marketData.fillPrice=RAW`, `signalPrice=ADJUSTED`는 FR-013을 고정한 값이며 다른 값은 거부.
- `benchmark` 블록은 v2에서 제거(005 보조 보기는 별도 spec).

## 이벤트 봉투 (`backtest.event`)

`{schemaVersion: 2, eventId, runId, executionId, type, sequence, occurredAt, payload}`. `executionId`는 전달(delivery)마다 새 UUID(현행). 같은 runId에 다른 executionId의 STARTED가 오면 백엔드는 자동 재시도로 해석해 `attempt`를 올린다.

| type | payload |
|---|---|
| `STARTED` | `{startedAt, stage: "PREPARING_DATA", resource: {pid, hostname}}` |
| `PROGRESS` | `{stage: "COMPUTING", completedTradingDays, totalTradingDays, lastTradingDate, points: [{date, equity, cumulativeReturn, drawdown}]}` (20거래일 배치 + 마지막 잔여) |
| `FINISHED` | `{status: "COMPLETED"|"FAILED"|"CANCELLED", partial, lastTradingDate, engineOutput, resource: {peakRssMb, cpuSeconds, wallSeconds}}` |

- `FINISHED.status=CANCELLED`: `partial=true`, `engineOutput`은 `lastTradingDate`까지의 부분 결과(성과·시계열·거래·비중 모두 그 구간 기준). 
- `FINISHED.status=FAILED`: `engineOutput.run.error = {code, message}`, code ∈ `INVALID_REQUEST` `DATA_INSUFFICIENT` `CONSTRAINT_VIOLATION` `ESTIMATION_FAILED` `ENGINE_ERROR`. 결정적 오류는 워커가 이 이벤트로 응답하고 ACK한다(재시도 없음).

## 제어 메시지 (`backtest.control`, fanout)

```json
{"schemaVersion": 2, "type": "CANCEL", "runId": "RUN-000123", "requestedAt": "2026-09-10T02:31:00Z"}
```

- 워커는 자기 프로세스가 실행 중인 runId면 다음 `on_bar`에서 정지하고 FINISHED(CANCELLED)를 보낸다. 모르는 runId는 무시(다른 워커의 run 또는 이미 끝난 run).
- 백엔드는 (a) 마지막 활성 요청이 취소되어 run이 RUNNING일 때, (b) CANCELLED 상태의 run에 STARTED가 도착했을 때 발행한다.

## EngineOutput v2 추가 필드

`run{runId, status, partial, lastTradingDate, effectiveStart, effectiveEnd, error}`, `performance{…현행 + netTotalReturn, maxDrawdown, totalTradingCost}`, `portfolioSeries[{date, equity, cash, positions, cashFlows[{type: DISTRIBUTION, instrumentId, amount}]}]`, `weightsSeries[…]`, `weightsSource`, `trades[{…현행 + reason, plannedDate}]`, `costs{commission, spread, slippage, tax, total}`, `liquidation{markToMarket, liquidated}`, `analytics{recoveryDays, turnover, correlationMatrix, riskContribution, lossContribution, weightError, method}`, `exceptions[{date, instrumentId, kind, rule, action}]`, `versions{mapping, rules, costProfile, data, engine, evaluationSplit, worker}`, `diagnostics{…현행 + resource}`.

## 검증 책임 (§8 갱신)

| 항목 | 백엔드(발행 전) | 워커(수신 후) |
|---|---|---|
| 규칙 등록 여부(PD 미확정) | 422 거부 | `rules` 없으면 INVALID_REQUEST |
| 비중 합·상한 합계 | 400 거부 | 재검증 후 CONSTRAINT_VIOLATION |
| 워밍업·가용 기간 | 422 거부 | 자료 로드 후 재검증 → DATA_INSUFFICIENT |
| 자료 정합(OHLC·중복·비단조) | — | `validate_prices` fail-closed |
| fillPrice/signalPrice 고정값 | 고정 | 다르면 INVALID_REQUEST |

## 픽스처 (`docs/specs/backtest-messaging/examples/`)

`engine-input.json`(v2), `event-started.json`, `event-progress.json`, `event-finished-completed.json`, `event-finished-failed.json`, `event-finished-cancelled.json`, `control-cancel.json`. 양쪽 테스트(`BacktestMessageRoundTripTest`, `worker/tests/test_contracts.py`)가 모두 읽는다. v1 픽스처는 삭제한다(호환 유지 대상 아님, DEMO 데이터 폐기 전제).
