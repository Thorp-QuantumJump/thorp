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

## 작업 메시지: `RegimeLinkJob` v1 — 목표 계약, DEP-004-011 검증 전 동결 금지

```json
{
  "job": {"linkId": "RL-20260918-000101", "attempt": 1, "scenario": "CONTINUOUS"},
  "window": {"d0": "2012-04-02", "d21": "2012-05-02", "d63": "2012-07-03",
             "lastAvailable": "2012-07-03", "completedTradingDays": 63,
             "baselineDate": "2012-03-30"},
  "run": {"runId": "RUN-000123", "effectiveStart": "2010-04-02", "initialCash": "10000000.00",
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
- `inputs.portfolioSeries`·`weightsSeries`(축약 예시의 옛 필드다. 확정 모양은 아래 "구간 입력 확장"의
  `openingPositions`·`dailyPositions`)는 `baselineDate`부터 `lastAvailable`까지, `trades`는
  `d0`~`lastAvailable` 안에 체결된 것만. **백엔드가 잘라 담는다.** 워커는 이 범위 밖을 요청하지 않고,
  범위가 모자라면(연속 운용의 `baselineDate` 행 없음 등; 현금 시작은 initialCash·빈 시작 보유) `INPUT_INCOMPLETE`로 실패한다 — 채우지 않는다. 자료 부족 판정은 발행 전 백엔드 몫이다(research R13).
- `universe.items[].delistedOn`이 구간 안이면 그 전날까지만 계산하고 결과에 `partial=true`. 백엔드는 폐지
  전날까지의 거래일 수가 21 이상일 때만 작업을 발행하고, `window.lastAvailable`·`completedTradingDays`는 이미
  폐지 전날 기준으로 줄인 값이다 — 그래서 `window21`은 언제나 완성이다. 004의 저장 시계열은 폐지 뒤에도
  이어지지만(폐지 종목을 마지막 종가로 평가) 백엔드는 그 구간을 담지 않는다(research R4).
- `inputDigest` = 백엔드가 `inputs·window·job.scenario·marketData` canonical JSON에 sha256. 워커는 검증하지 않고 결과에 그대로
  돌려준다(재현 대조는 백엔드 몫).
- 크기 상한: `dailyPositions`의 종목 수 ≤ 대응표 섹터 수, 날짜 행 수 ≤ 65. 넘으면 백엔드가 발행하지 않고
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
               "turnover": "0.3125", "turnoverBasis": "ANNUALIZED_252", "costs": {"commission": "0", "spread": "0", "slippage": "1523.00", "tax": "0", "total": "1523.00"},
               "lossContribution": {"SEMICONDUCTOR": "1.20000000", "BIO": "-0.20000000"}, "complete": true},
  "window21": {"…같은 모양…": "…", "complete": true},
  "holdings": [{"date": "2012-04-02", "actual": {"091160.XKRX": "0.3980"}}],
  "trades": [{"date": "2012-05-02", "instrumentId": "091160.XKRX", "side": "SELL", "quantity": 12, "price": "23150", "cost": "254.00", "reason": "REBALANCE"}],
  "kospi": {"window63": {"return": "0.0180", "maxDrawdown": "-0.0420"}, "window21": {"return": "0.0050", "maxDrawdown": "-0.0150"}},
  "basis": {"kind": "HISTORICAL_REVISION", "dataVersion": "KRX-2026-09-09", "datasetId": "KRX_DAILY_20260915", "analyticsMethod": "ANALYTICS-1"},
  "inputDigest": "e3b0…"
}
```

- `window21`은 `d0`~`d21`, `window63`은 `d0`~`min(d63, lastAvailable)`. `tradingDays`가 실제 길이고
  `complete`가 63(또는 21)을 다 채웠는지다. 수익률·낙폭은 연환산하지 않으며 회전율은 ANNUALIZED_252다.
- `tradesPerMonth = tradeCount ÷ (tradingDays ÷ 21)`, 소수 둘째 자리.
- `turnover`·`lossContribution`은 research R3의 구간 전용 산식이다. 누적 손익 함수에 조각을 그대로 넣지 않는다. 회전율은 `turnoverBasis=ANNUALIZED_252`이며 수익률·최대 낙폭은 연환산하지 않는다. 전체 구간 손익이 0일 때만 `lossContribution`을 생략하고 이익 종목도 포함한다.
- `kospi`는 `marketData.benchmarkInstrumentId`의 같은 날짜 종가로 두 값만. 전략과의 차이는 계산하지
  않는다.

### `FINISHED.error` (FAILED)

`{code, message}`, code ∈ `INPUT_INCOMPLETE`(메시지의 입력 행·필드 누락) `MARKET_DATA_MISSING`(시장 자료 정합
실패) `INVALID_REQUEST`(계약은 맞지만 계산할 수 없는 조합) `ENGINE_ERROR`(그 밖의 결정적 오류). 워커는
`DATA_INSUFFICIENT`를 내지 않는다 — 자료 부족은 발행 전 백엔드 판정이다(research R13). 계약 위반(JSON·스키마)은 이벤트가 아니라
reject → DLQ다.

## 검증 책임

| 항목 | 백엔드(발행 전) | 워커(수신 후) |
|---|---|---|
| 입력 실행이 `COMPLETED` | 아니면 발행하지 않음(`PENDING_RUN`) | — |
| 잘라낸 범위가 `baselineDate`~`lastAvailable`를 덮음 | 확인 후 발행 | 행이 빠졌으면 `INPUT_INCOMPLETE` 실패 |
| 종목 수·행 수 상한 | 초과면 `FAILED(INPUT_TOO_LARGE)`, 발행 안 함 | 계약 상한은 pydantic으로 |
| 시장 자료 정합 | — | `validate_prices` fail-closed(백테스트와 같다) → `MARKET_DATA_MISSING` 실패 |
| 구간 안 상장폐지 | 폐지 전날까지 21거래일 미만이면 `POST_INCOMPLETE(DELISTED)`, 발행 안 함. 이상이면 폐지 전날까지만 담아 발행 | `delistedOn` 전날까지만 계산, `partial=true` |
| 원천 워밍업 부족 | 링크 `DATA_INSUFFICIENT(WARMUP_SHORT)`, 발행 안 함 | — |
| 원천은 충분하나 기존 실행 effectiveStart > d0 또는 기준·종료 행 부족 | `OUT_OF_PERIOD`, 확인 후 전체 기간 실행. 필터에서 제외하지 않음 | — |

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

## 구간 입력 확장 — DEP-004-011

위 JSON은 기존 봉투를 설명하는 축약 예시이며 실행 가능한 확정 fixture가 아니다. M1 동결 전에
아래 항목을 포함한 완전한 fixture로 교체한다. 004 저장 결과와의 필드 대응·독립 대조를 먼저 통과해야 한다.

- `inputs.openingPositions`: baselineDate의 종목별 수량·평가액. FRESH_CASH는 빈 목록.
- `inputs.dailyPositions`: d0~lastAvailable의 날짜별 종목 수량·평가액. 빈 보유일도 날짜를 남긴다.
- `inputs.trades`: round-trip 대신 개별 체결 목록. dateTime·instrumentId·side·quantity·price·
  costs(commission/spread/slippage/tax)·directCashCost. 체결가에 반영된 비용은 directCashCost에서 제외.
- `inputs.cashFlows`: 구간 날짜·instrumentId·type·amount로 분배금 등을 식별한다.
- corporate action에 따른 수량·현금 변화와 가격 자료는 같은 버전으로 정합 검증한다. 확보되지 않은
  항목을 0이나 비례배분 비용으로 만들어내지 않는다.
- holdings는 dailyPositions와 portfolioSeries에서 산출하며 weightsSeries가 일별이라고 가정하지 않는다.
  입력 해시 대상에는 위 필드와 window·scenario·marketData 버전 전체가 포함된다.
