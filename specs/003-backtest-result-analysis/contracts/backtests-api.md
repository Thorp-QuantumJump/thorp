# Backtests API 계약

**Base path**: `/backtests` | **Date**: 2026-09-03 | 공통 규칙은 [README.md](./README.md)

| 구분 | 메서드·경로 | 스펙 | 이 브랜치 |
|---|---|---|---|
| Job 생성 | `POST /backtests` | 001 | 구현(`initialCash` 추가) |
| 상태·지표 조회 | `GET /backtests/{backtestId}` | 001 + 003 US1 | 구현(`config`·`summary` 추가) |
| 자산 시계열 | `GET /backtests/{backtestId}/timeseries` | 003 US2 | 구현 |
| 거래 내역 | `GET /backtests/{backtestId}/trades` | 003 US3 | **예약** (형태만 정의) |
| 지표 정의 열람 | `GET /metric-definitions/{version}` | 003 US4 | **예약** (형태만 정의) |
| 계산 불가 이유 | `summary.*` 옆 이유 필드 | 003 US4 | **예약** |

---

## POST /backtests

Job을 저장(QUEUED)하고 Worker에 발행한다. 계산 결과는 포함하지 않는다(비동기 계약).

**Request** — 스키마 [backtest-create-request.schema.json](./backtest-create-request.schema.json)

| 필드 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `strategy` | string | O | not blank | 전략 id (`trend`, `dualMomentum`, `rsiMeanReversion` …) |
| `startDate` | string(date) | O | `<= endDate` | 시작일 |
| `endDate` | string(date) | O | | 종료일 |
| `initialCash` | integer | X | `> 0` | **신규**. 초기 자산(KRW). 생략 시 `10000000` |

**Response** `202 Accepted` — `data`는 [backtest-detail.schema.json](./backtest-detail.schema.json) (`summary`는 항상 `null`)

```json
{
  "success": true,
  "data": {
    "backtestId": 4,
    "status": "QUEUED",
    "config": { "strategy": "trend", "startDate": "2019-01-01", "endDate": "2023-12-31", "initialCash": 10000000 },
    "summary": null
  },
  "error": null
}
```

기존 계약(`{ backtestId, status: QUEUED }`)은 유지된다. `config`·`summary`는 추가 필드다.

**Errors**: `400 INVALID_INPUT` — 필수 필드 누락, `startDate > endDate`, `initialCash <= 0`

---

## GET /backtests/{backtestId}

Job 상태와, COMPLETED이면 핵심 지표(US1)를 돌려준다. 폴링용이므로 어떤 상태든 200이다.

**Response** `200 OK` — `data`는 [backtest-detail.schema.json](./backtest-detail.schema.json)

| 필드 | 타입 | nullable | 설명 |
|---|---|---|---|
| `backtestId` | integer | X | Job id |
| `status` | enum | X | `QUEUED \| RUNNING \| COMPLETED \| FAILED` |
| `config.strategy` | string | X | 전략 id |
| `config.startDate` | date | X | |
| `config.endDate` | date | X | |
| `config.initialCash` | integer | X | 초기 자산(KRW) |
| `summary` | object | **O** | `status == COMPLETED`이고 결과가 저장되어 있을 때만 객체, 그 외 `null` |
| `summary.initialEquity` | integer | X | 시작 자산(KRW). `config.initialCash`와 같다 |
| `summary.finalEquity` | integer | X | 종료 시점 총 자산(KRW) |
| `summary.totalReturn` | number | O | `finalEquity / initialEquity - 1` |
| `summary.cagr` | number | O | 연환산 수익률 |
| `summary.maxDrawdown` | number | O | 최대 낙폭, 0 또는 음수 |
| `summary.volatility` | number | O | 연환산 변동성 |
| `summary.sharpeRatio` | number | O | 배수 |
| `summary.tradeCount` | integer | X | 청산 거래 수. 0은 정당한 값 |
| `summary.winRate` | number | O | `0 ~ 1` |
| `summary.profitFactor` | number | O | 배수, `>= 0` |
| `summary.metricDefinitionVersion` | string | X | 지표 계산 규약 버전(`"1.0"`). 정의는 plan research.md §2 |

지표의 `null`은 계산 불가(예: 거래 0건 → `winRate`, 손실 거래 없음 → `profitFactor`, 변동성 0 → `sharpeRatio`).

예시: [examples/get-backtest-completed.json](./examples/get-backtest-completed.json),
[examples/get-backtest-queued.json](./examples/get-backtest-queued.json)

**Errors**: `404 RESOURCE_NOT_FOUND` — 없는 id ([examples/error-404.json](./examples/error-404.json))

---

## GET /backtests/{backtestId}/timeseries

자산 가치 시계열(US2). COMPLETED이고 결과가 저장된 Job에만 제공한다.

**Response** `200 OK` — `data`는 [backtest-timeseries.schema.json](./backtest-timeseries.schema.json)

| 필드 | 타입 | 설명 |
|---|---|---|
| `backtestId` | integer | Job id |
| `points[]` | array | 날짜 오름차순, 비어 있지 않음. Worker 결과는 기간 내 모든 거래일(5년 ≈ 1,250개). dev 시더는 분기 20개 |
| `points[].date` | date | 거래일 |
| `points[].equity` | integer | 종가 기준 총 자산(KRW). 첫 값 = `summary.initialEquity`, 마지막 값 = `summary.finalEquity` |
| `points[].cumulativeReturn` | number | `equity / points[0].equity - 1` |
| `points[].drawdown` | number | `equity / max(equity[0..t]) - 1`, 0 또는 음수 |

예시: [examples/get-timeseries-completed.json](./examples/get-timeseries-completed.json)

**Errors**

| 상태 | 코드 | 상황 | 예시 |
|---|---|---|---|
| 404 | `RESOURCE_NOT_FOUND` | 없는 id | [error-404.json](./examples/error-404.json) |
| 409 | `RESULT_NOT_READY` | `status != COMPLETED`, 또는 COMPLETED이지만 결과 행이 아직 없음 | [error-409-result-not-ready.json](./examples/error-409-result-not-ready.json) |

프론트는 409를 "아직 준비 중"으로 보고 상태 조회(`GET /backtests/{id}`) 폴링으로 돌아간다. 404는 오류로 처리한다.

---

## 예약: GET /backtests/{backtestId}/trades (US3, 후속 티켓)

이번 브랜치에서 구현하지 않는다. 형태만 예약해 프론트가 자리를 잡을 수 있게 한다. 구현 티켓에서 확정한다.

```json
{
  "backtestId": 1,
  "trades": [
    {
      "tradeId": 1,
      "instrument": "005930",
      "side": "BUY",
      "date": "2019-02-04",
      "quantity": 10,
      "price": 45000,
      "amount": 450000,
      "realizedPnl": null
    }
  ]
}
```

- 거래 단위는 개별 체결(매수/매도 1건). `realizedPnl`은 보유를 정리하는 매도 거래에만 값, 그 외 `null`
- 거래 0건이면 `trades: []` + 200 (오류 아님, FR-012)
- COMPLETED가 아니면 409 `RESULT_NOT_READY`

## 예약: 지표 정의 열람 + 계산 불가 이유 (US4, 후속 티켓)

- `GET /metric-definitions/{version}` → 지표별 `{ key, name, meaning, calculationTarget, calculationBasis, unavailableWhen, unit, betterDirection }`
- `summary`에 `unavailableReasons: { "winRate": "NO_TRADES", "profitFactor": "NO_LOSING_TRADES" }` 같은 이유 맵을 **추가**한다(하위 호환).
  `null` 표현 자체는 바뀌지 않는다
- `GET /backtests/{id}`의 `config`에 데이터 기준(F1)·파라미터(F2) 필드가 추가될 수 있다(하위 호환)
