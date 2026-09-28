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

routing key: 작업 `regime-link.job.requested`, 이벤트 `regime-link.event`, DLX → DLQ `regime-link.job.dead`
(작업 routing key를 그대로 쓰지 않는다 — 백테스트의 `backtest.job.dead`와 같은 방식이다. T107). 메시지 속성
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
  "run": {"runId": "RUN-000123", "effectiveStart": "2010-04-02", "initialCash": "10000000.00",
          "analyticsMethod": "ANALYTICS-1"},
  "universe": {"mappingVersion": "SECTOR-ETF-2026-09",
               "items": [{"sectorId": "SEMICONDUCTOR", "instrumentId": "091160.XKRX"},
                         {"sectorId": "BATTERY", "instrumentId": "305720.XKRX"},
                         {"sectorId": "BIO", "instrumentId": "244580.XKRX"}]},
  "marketData": {"datasetId": "KRX_DAILY_20260915", "dataVersion": "KRX-2026-09-09",
                 "benchmarkInstrumentId": "KOSPI"},
  "inputs": {
    "portfolioSeries": [{"date": "2012-03-30", "equity": "10412000.00", "cash": "120000.00"}],
    "openingPositions": [{"instrumentId": "091160.XKRX", "quantity": 179}],
    "dailyPositions": [{"date": "2012-05-02", "positions": [{"instrumentId": "091160.XKRX", "quantity": 167}]}],
    "trades": [{"fillId": "FL-000022", "time": "2012-05-02T00:00:00Z", "instrumentId": "091160.XKRX",
                "side": "SELL", "quantity": 12, "price": "23181.44", "referencePrice": "23200",
                "costs": {"commission": "41.76", "spread": "83.52", "slippage": "139.20", "tax": "0"},
                "cashCost": "41.76", "reason": "REBALANCE"}],
    "cashFlows": [{"date": "2012-06-01", "instrumentId": "091160.XKRX", "type": "DISTRIBUTION", "amount": "16700"}]
  },
  "inputDigest": "e3b0…(64자)"
}
```

- 예시 체결의 숫자는 004 `costs.py` 규칙으로 검산된다 — 기준 금액은 `수량 × referencePrice`
  (12 × 23200 = 278,400)이고 비용 프로필 `KR-ETF-2026-09`(수수료 1.5 · 호가차 3 · 슬리피지 5 ·
  세금 0bps)를 적용하면 41.76 · 83.52 · 139.20 · 0이다. 호가차·슬리피지는 체결가를 불리하게
  움직이므로((83.52 + 139.20) ÷ 12 = 18.56) 매도 체결가는 23200 − 18.56 = 23181.44이고,
  현금으로 빠지는 `cashCost`는 수수료 + 세금이다. 픽스처도 이 관계를 만족해야 한다.
- `window.baselineDate`는 d0 **직전 거래일**이다. 연속 운용의 구간 수익률 기준값
  `equity[baselineDate]`가 여기서 나온다. `scenario=FRESH_CASH`면 기준은 `run.initialCash`이고
  `baselineDate`는 참고용이다(research R3).
- `inputs.portfolioSeries`는 `baselineDate`~`lastAvailable`의 매일, `dailyPositions`·`trades`·`cashFlows`는
  `d0`~`lastAvailable` 안의 것만, `openingPositions`는 `baselineDate` 이전 마지막 스냅샷 하나다.
  **백엔드가 잘라 담는다.** 워커는 이 범위 밖을 요청하지 않고,
  범위가 모자라면(연속 운용의 `baselineDate` 행 없음 등; 현금 시작은 initialCash·빈 시작 보유) `INPUT_INCOMPLETE`로 실패한다 — 채우지 않는다. 자료 부족 판정은 발행 전 백엔드 몫이다(research R13).
- `universe.items[].delistedOn`이 구간 안이면 그 전날까지만 계산하고 결과에 `partial=true`. 백엔드는 폐지
  전날까지의 거래일 수가 21 이상일 때만 작업을 발행하고, `window.lastAvailable`·`completedTradingDays`는 이미
  폐지 전날 기준으로 줄인 값이다 — 그래서 `window21`은 언제나 완성이다. 004의 저장 시계열은 폐지 뒤에도
  이어지지만(폐지 종목을 마지막 종가로 평가) 백엔드는 그 구간을 담지 않는다(research R4).
- `inputDigest` = 백엔드가 `inputs·window·job.scenario·marketData` canonical JSON에 sha256. 워커는 검증하지 않고 결과에 그대로
  돌려준다(재현 대조는 백엔드 몫).
- 크기 상한: `openingPositions`·`dailyPositions`의 종목 수 ≤ 대응표 섹터 수, `portfolioSeries`의 날짜 행 수 ≤ 65. 넘으면 백엔드가 발행하지 않고
  `FAILED(INPUT_TOO_LARGE)`.

## 이벤트 봉투

`{schemaVersion: 1, eventId, linkId, attempt, type, occurredAt, payload}`. `eventId`는 이벤트마다
고유하고 재발행 시 유지(멱등 키, 백테스트와 같다).

**`eventId`의 실제 형식은 UUID 문자열이다.** 워커가 고정 이름공간과 `"{linkId}:{attempt}:{type}"`에서
`uuid5`로 결정적으로 뽑으므로, 같은 작업이 재전달돼 다시 계산돼도 같은 값이 나온다(백테스트 경로처럼
실행마다 새 식별자를 발급하면 재전달마다 키가 달라져 같은 결과가 두 번 기록된다). 계약은 자유
문자열만 요구하므로 예시 파일의 `EVT-RL-000001`은 계약 위반이 아니지만 **운영에서 나오는 모양이
아니다** — 형식으로 값을 파싱하는 코드를 두지 않는다. 이름공간을 바꾸면 이미 발행한 이벤트와 다른
`eventId`가 나오므로 바꾸지 않는다.

| type | payload |
|---|---|
| `STARTED` | `{startedAt, resource: {pid, hostname}}` |
| `FINISHED` | `{status: "COMPLETED"\|"FAILED", partial, delistedOn?, result?, error?, resource: {wallSeconds}}` |

### `FINISHED.result` (COMPLETED)

```json
{
  "window63": {"tradingDays": 63, "return": "0.0421", "maxDrawdown": "-0.0587", "tradeCount": 6, "tradesPerMonth": "2.00",
               "turnover": "0.3125", "turnoverBasis": "ANNUALIZED_252", "costs": {"commission": "0", "spread": "0", "slippage": "1523.00", "tax": "0", "total": "1523.00"},
               "lossContribution": {"SEMICONDUCTOR": "1.20000000", "BATTERY": "0.32000000", "BIO": "-0.52000000"}, "complete": true},
  "window21": {"…같은 모양…": "…", "complete": true},
  "holdings": [{"date": "2012-04-02", "actual": {"091160.XKRX": "0.3980", "305720.XKRX": "0.3010", "244580.XKRX": "0.3010"}}],
  "trades": [{"date": "2012-05-02", "instrumentId": "091160.XKRX", "side": "SELL", "quantity": 12, "price": "23150", "cost": "254.00", "reason": "REBALANCE"}],
  "kospi": {"window63": {"return": "0.0180", "maxDrawdown": "-0.0420"}, "window21": {"return": "0.0050", "maxDrawdown": "-0.0150"}},
  "basis": {"kind": "HISTORICAL_REVISION", "dataVersion": "KRX-2026-09-09", "datasetId": "KRX_DAILY_20260915", "analyticsMethod": "ANALYTICS-1"},
  "inputDigest": "e3b0…"
}
```

- `window21`은 `d0`~`d21`, `window63`은 `d0`~`min(d63, lastAvailable)`. `tradingDays`가 실제 길이고
  `complete`가 63(또는 21)을 다 채웠는지다. 수익률·낙폭은 연환산하지 않으며 회전율은 ANNUALIZED_252다.
- `tradesPerMonth = tradeCount ÷ (tradingDays ÷ 21)`, 소수 둘째 자리.
- 시계열과 목록은 **구간 전체**를 담는다 — 작업의 `inputs.portfolioSeries`는 `baselineDate`부터
  `lastAvailable`까지 모든 거래일을(`dailyPositions`는 그중 수량이 바뀐 날만), 결과의 `holdings`는 구간의
  모든 거래일을, 양쪽 `trades`는 구간의 모든 거래를 담는다(결과 `trades`의 개수 = `window63.tradeCount`).
  **위 예시는 지면 때문에 한 행씩만 싣는다** — 픽스처를 만들 때 예시의 길이를 그대로 옮기지 않는다.
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

## 구간 입력의 출처와 제약 — DEP-004-011 (대조 통과 2026-09-20)

004 저장 결과로 위 `inputs`를 정확히 만들 수 있음을 독립 계산으로 확인했다(research R3 "독립 계산 대조
결과": 전략 입력 여섯을 돌려 얻은 22개 구간에서 시작 직전 평가액·일별 평가액·현금·자산·종목 구간
손익 합이 모두 차 0).
위 JSON이 확정 모양이고, 아래가 필드마다의 출처와 제약이다.

- `inputs.portfolioSeries`: baselineDate~lastAvailable의 일별 `equity`·`cash`. 연속 운용의 구간 기준값이
  `equity[baselineDate]`다. 004의 `portfolioSeries`에서 보유 수량은 아래 두 필드로 따로 뺀다.
- `inputs.openingPositions`: baselineDate **이전 마지막** 보유 스냅샷의 종목별 수량(004는 수량이 바뀌는 날마다
  스냅샷을 남기므로 그 수량이 baselineDate의 수량이다). FRESH_CASH는 빈 목록.
- `inputs.dailyPositions`: d0~lastAvailable 안에서 004가 남긴 보유 스냅샷(수량이 바뀐 날)만 그대로 담는다.
  백엔드는 채우지 않는다. 스냅샷 한 건은 **그날 보유 전체**이며 수량 0인 종목은 빠져 있다(004
  `portfolio_execution.py`가 `quantity`가 0이 아닌 종목만 담는다) — 스냅샷에 없는 종목은 그날 보유가
  없다는 뜻이므로, 이월은 **덮어쓰기**이고 실린 종목만 고치는 부분 갱신이 아니다.
  **워커의 이월은 "체결로 더하되 스냅샷이 있는 날은 그 수량으로 맞춘다"이다** —
  분할 같은 기업행위는 체결 밖에서 수량을 바꾸므로, 체결만 누적하면 그날부터 수량이 틀린다(대조에서 22개
  구간 중 9개의 평가액·자산이 어긋났고 한 구간에서 최대 59개 행이었다. 종목 손익 항등식은 그때도 0이라
  항등식만으로는 잡히지 않는다). 일별 평가액은 이월한 수량 × 그날 원시 종가다(004 엔진과 같은 식).
- `inputs.trades`: round-trip 대신 004 `fills[]`(개별 체결) 그대로 — fillId·time·instrumentId·side·quantity·
  price·referencePrice·costs(commission/spread/slippage/tax)·cashCost. `cashCost`가 직접 차감 비용이며 체결가에
  반영된 호가차·슬리피지는 들어 있지 않다. 체결 기록이 없는 실행(`fills_recorded=false`)이면 발행하지 않는다.
  **`FINISHED.result.trades`와 이름만 같고 모양이 다르다** — 결과 쪽은 `date`와 비용 한 값(`cost`)을
  쓰는 표시용 목록이다. 두 곳의 모델을 하나로 합치지 않는다.
- `inputs.cashFlows`: 구간 날짜·instrumentId·type·amount로 분배금 등을 식별한다. 체결과 이 현금흐름만으로
  구간 현금이 되살아난다(대조에서 확인).
- corporate action에 따른 수량·현금 변화와 가격 자료는 같은 버전으로 정합 검증한다. 확보되지 않은
  항목을 0이나 비례배분 비용으로 만들어내지 않는다.
- holdings는 dailyPositions와 portfolioSeries에서 산출한다. 004의 `weightsSeries`는 리밸런싱 시점 비중이므로
  일별 비중으로 쓰지 않으며 이 메시지에 담지 않는다. 입력 해시 대상에는 위 필드와 window·scenario·
  marketData 버전 전체가 포함된다.
