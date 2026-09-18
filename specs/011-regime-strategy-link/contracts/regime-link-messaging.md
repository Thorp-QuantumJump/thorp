# Contract: Regime Link Messaging v1 (Backend ↔ Worker, RabbitMQ)

백테스트 메시징(`docs/specs/backtest-messaging/`)과 **분리된** 경로다(research R1). 표기 규칙은 같다 —
소수 plain string, 날짜 `YYYY-MM-DD`, 시각 UTC, null은 키 생략. `docs/specs/regime-link-messaging/README.md`와
`examples/*.json` 픽스처를 이 내용으로 만들고, 백엔드 `regimelink/application/port/message`와 워커
`worker/regime_link/contracts.py`가 **같은 픽스처**로 round-trip 테스트한다. 스키마 변경은 픽스처 →
백엔드 → 워커 순으로 같은 MR에서 한다(`worker/CLAUDE.md`).

## 토폴로지 (백엔드가 선언, 워커는 선언하지 않는다)

| 이름 | 종류 | 속성 |
|---|---|---|
| `regime-link` | exchange (direct, durable) | |
| `regime-link.jobs` | queue | quorum, `x-delivery-limit=1`, DLX `regime-link.dlx` → `regime-link.jobs.dlq` |
| `regime-link.jobs.dlq` | queue | **백엔드가 소비**해 링크를 `FAILED(SYSTEM_FAILURE)`로 |
| `regime-link.events` | queue | 워커 → 백엔드 |

routing key: 작업 `regime-link.job.requested`, 이벤트 `regime-link.event`. 메시지 속성
`content_type=application/json`, `delivery_mode=2`, header `schemaVersion: 1`,
작업 `message_id = "RLJ-" + linkId + "-" + attempt`, `correlation_id = linkId`. 상수는 백엔드
`RegimeLinkRabbitConfig`의 `public static final String`과 워커 `regime_link/messaging.py`가 같은 값을
가져야 하며 워커 테스트가 Java 파일을 읽어 대조한다(백테스트와 같은 방식).

## 작업 메시지: `RegimeLinkJob` v1

```json
{
  "job": {"linkId": "RL-20260918-000101", "attempt": 1, "scenario": "CONTINUOUS"},
  "window": {"d0": "2012-04-02", "d21": "2012-05-02", "d63": "2012-07-03",
             "lastAvailable": "2012-07-03", "completedTradingDays": 63,
             "baselineDate": "2012-03-30"},
  "run": {"runId": "RUN-000123", "effectiveStart": "2018-04-02", "initialCash": "10000000.00",
          "analyticsMethod": "ANALYTICS-1"},
  "universe": {"mappingVersion": "SECTOR-ETF-2026-09",
               "items": [{"sectorId": "SEMICONDUCTOR", "instrumentId": "091160.XKRX", "delistedOn": null}]},
  "marketData": {"datasetId": "KRX_DAILY_20260915", "dataVersion": "KRX-2026-09-09",
                 "benchmarkInstrumentId": "KOSPI"},
  "inputs": {
    "portfolioSeries": [{"date": "2012-03-30", "equity": "10412000.00", "cash": "120000.00"}],
    "weightsSeries": [{"date": "2012-03-30", "actual": {"091160.XKRX": "0.3980", "305720.XKRX": "0.3010", "244580.XKRX": "0.3010"}}],
    "trades": [{"date": "2012-05-02", "instrumentId": "091160.XKRX", "side": "SELL", "quantity": 12, "price": "23150",
                "costs": {"commission": "0", "spread": "0", "slippage": "254.00", "tax": "0"}, "reason": "REBALANCE"}]
  },
  "inputDigest": "e3b0…(64자)"
}
```

- `window.baselineDate`는 d0 **직전 거래일**이다. 연속 운용의 구간 수익률 기준값
  `equity[baselineDate]`가 여기서 나온다. `scenario=FRESH_CASH`면 기준은 `run.initialCash`이고
  `baselineDate`는 참고용이다(research R3).
- `inputs.portfolioSeries`·`weightsSeries`는 `baselineDate`부터 `lastAvailable`까지, `trades`는
  `d0`~`lastAvailable` 안에 체결된 것만. **백엔드가 잘라 담는다.** 워커는 이 범위 밖을 요청하지 않고,
  범위가 모자라면(예: `baselineDate` 행 없음) `DATA_INSUFFICIENT`로 답한다 — 채우지 않는다.
- `universe.items[].delistedOn`이 구간 안이면 그 전날까지만 계산하고 결과에 `partial=true`.
- `inputDigest` = 백엔드가 `inputs` canonical JSON에 sha256. 워커는 검증하지 않고 결과에 그대로
  돌려준다(재현 대조는 백엔드 몫).
- 크기 상한: `weightsSeries`의 종목 수 ≤ 대응표 섹터 수, 행 수 ≤ 65. 넘으면 백엔드가 발행하지 않고
  `FAILED(INPUT_TOO_LARGE)`.

## 이벤트 봉투

`{schemaVersion: 1, eventId, linkId, attempt, type, occurredAt, payload}`. `eventId`는 이벤트마다
고유하고 재발행 시 유지(멱등 키, 백테스트와 같다).

| type | payload |
|---|---|
| `STARTED` | `{startedAt, resource: {pid, hostname}}` |
| `FINISHED` | `{status: "COMPLETED"\|"FAILED", partial, delistedOn?, result?, error?, resource: {wallSeconds}}` |

### `FINISHED.result` (COMPLETED)

```json
{
  "window63": {"tradingDays": 63, "return": "0.0421", "maxDrawdown": "-0.0587", "tradeCount": 6, "tradesPerMonth": "2.00",
               "turnover": "0.3125", "costs": {"commission": "0", "spread": "0", "slippage": "1523.00", "tax": "0", "total": "1523.00"},
               "lossContribution": {"SEMICONDUCTOR": "-0.0312", "BIO": "-0.0044"}, "complete": true},
  "window21": {"…같은 모양…": "…", "complete": true},
  "holdings": [{"date": "2012-04-02", "actual": {"091160.XKRX": "0.3980"}}],
  "trades": [{"date": "2012-05-02", "instrumentId": "091160.XKRX", "side": "SELL", "quantity": 12, "price": "23150", "cost": "254.00", "reason": "REBALANCE"}],
  "kospi": {"window63": {"return": "0.0180", "maxDrawdown": "-0.0420"}, "window21": {"return": "0.0050", "maxDrawdown": "-0.0150"}},
  "basis": {"kind": "HISTORICAL_REVISION", "dataVersion": "KRX-2026-09-09", "datasetId": "KRX_DAILY_20260915", "analyticsMethod": "ANALYTICS-1"},
  "inputDigest": "e3b0…"
}
```

- `window21`은 `d0`~`d21`, `window63`은 `d0`~`min(d63, lastAvailable)`. `tradingDays`가 실제 길이고
  `complete`가 63(또는 21)을 다 채웠는지다. 연율화하지 않는다.
- `tradesPerMonth = tradeCount ÷ (tradingDays ÷ 21)`, 소수 둘째 자리.
- `turnover`·`lossContribution`은 `worker.performance.calculate_analytics`의 정의를 구간 자료에
  적용한 값이다(research R3). 워커는 종목 조정 종가를 `market_data.py`로 읽어 일별 수익률을 만든다.
  `lossContribution`은 구간 손실이 없으면 **키를 생략**한다.
- `kospi`는 `marketData.benchmarkInstrumentId`의 같은 날짜 종가로 두 값만. 전략과의 차이는 계산하지
  않는다.

### `FINISHED.error` (FAILED)

`{code, message}`, code ∈ `DATA_INSUFFICIENT`(시장 자료·입력 범위 부족) `INVALID_REQUEST`(계약은 맞지만
계산할 수 없는 조합) `ENGINE_ERROR`(그 밖의 결정적 오류). 계약 위반(JSON·스키마)은 이벤트가 아니라
reject → DLQ다.

## 검증 책임

| 항목 | 백엔드(발행 전) | 워커(수신 후) |
|---|---|---|
| 입력 실행이 `COMPLETED` | 아니면 발행하지 않음(`PENDING_RUN`) | — |
| 잘라낸 범위가 `baselineDate`~`lastAvailable`를 덮음 | 확인 후 발행 | 행이 빠졌으면 `DATA_INSUFFICIENT` |
| 종목 수·행 수 상한 | 초과면 `FAILED(INPUT_TOO_LARGE)`, 발행 안 함 | 계약 상한은 pydantic으로 |
| 시장 자료 정합 | — | `validate_prices` fail-closed(백테스트와 같다) |
| 워밍업 부족(`effectiveStart > d0`) | 링크 `DATA_INSUFFICIENT(WARMUP_SHORT)`, 발행 안 함 | — |

## 처리 규칙 (워커)

1. 파싱·스키마 실패 → requeue 없이 reject → DLQ.
2. 계산 결과가 FAILED여도 **정상 결과**로 `FINISHED` 발행 + ACK.
3. `FINISHED` 발행이 브로커에서 확인된 뒤에만 ACK(at-least-once).
4. 같은 `linkId`·`attempt`가 재전달되면 다시 계산하고 `eventId`는 유지한다(결정적 계산).
5. 시장 자료 적재 중 일시 장애(DB 접속)는 전파해 재전달 경로로, 그 뒤 예외는 결정적이므로 결과로.

## 픽스처 (`docs/specs/regime-link-messaging/examples/`)

`job-continuous.json`, `job-fresh-cash.json`, `job-partial-delisted.json`, `event-started.json`,
`event-finished-completed.json`, `event-finished-incomplete63.json`, `event-finished-failed.json`.
`reconciliation/`에 워커를 import하지 않는 독립 계산 스크립트와 기대값(`manual_reconciliation.json`)을
두어 `window63`·`window21`·`kospi`를 대조한다(백테스트 픽스처와 같은 이유 — 기대값을 엔진 출력으로
채우면 대조가 아니다).
