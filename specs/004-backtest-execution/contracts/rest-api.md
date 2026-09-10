# Contract: Backtest REST API v2 (Backend ↔ Frontend)

`docs/specs/backtest-api/README.md`를 이 문서 내용으로 갱신한다. 봉투 `ApiResponse`, 외부 경로 `/api/backtests`(프록시가 `/api` 제거), 소수는 plain string, 날짜는 거래일 `YYYY-MM-DD`, 시각은 UTC ISO-8601 — 현행 규칙 유지. 모든 엔드포인트는 인증 필요(R-12). 다른 사용자의 요청은 404.

## 상태 값

`QUEUED` `RUNNING` `COMPLETED` `FAILED` `CANCELLED`. 종결: 뒤 셋. 프론트 표시 이름은 대기·실행·완료·실패·취소.

## POST /backtests — 분석 요청 접수

요청 본문(003이 만든 분석 요청):

```json
{
  "executionType": "SINGLE",
  "sectorAllocation": {
    "mappingVersion": "SECTOR-ETF-2026-09",
    "items": [
      {"sectorId": "SEMICONDUCTOR", "instrumentId": "091160.XKRX", "targetWeight": "0.4000"},
      {"sectorId": "BATTERY", "instrumentId": "305720.XKRX", "targetWeight": "0.3000"},
      {"sectorId": "BIO", "instrumentId": "244580.XKRX", "targetWeight": "0.3000"}
    ]
  },
  "strategy": {"strategyId": "FIXED_WEIGHT", "rulesVersion": "1", "registryVersion": "PARAM-2026-09", "parameters": {"rebalance": "MONTH_END"}},
  "conditions": {"start": "2018-01-02", "end": "2025-12-30", "initialCash": "10000000.00", "costProfileVersion": null, "evaluationSplitVersion": "SPLIT-1", "scenario": "FULL_PERIOD"}
}
```

- `targetWeight`는 `FIXED_WEIGHT`에서만 필수, 다른 전략에서는 금지(400).
- `costProfileVersion` null → 서버 기본(현재 `PROVISIONAL-DEMO-1`).
- `scenario`: `FULL_PERIOD`(주 분석) · `FRESH_CASH_FROM` · `COST_ESCALATION` · `TARGET_RISK` (FR-016, 별도 실행으로 기록).

응답 `202 Accepted`:

```json
{"backtestId": "BT-20260910-000123", "status": "QUEUED", "resultSource": "NEW", "effectiveStart": "2018-04-02", "effectiveEnd": "2025-12-30", "versions": {"costProfile": {"version": "PROVISIONAL-DEMO-1", "provisional": true}, "rules": "1", "data": "KRX-2026-09-09", "engine": "nautilus_trader 1.219.0 / worker 2.0.0"}}
```

- `resultSource`: `NEW` 새 실행 / `JOINED` 진행 중 동일 버전 실행에 합류(status는 그 실행의 현재 상태) / `REUSED` 완료 결과 재사용(status `COMPLETED`, 결과 즉시 조회 가능).
- 오류: 400 `INVALID_INPUT`(비중 합·상한 합계·식별자), 422 `RULES_NOT_REGISTERED`(PD-03·04 미확정 전략), 422 `INSUFFICIENT_HISTORY`(워밍업 부족, 본문에 필요 시작일), 429 `EXECUTION_LIMIT`(대기열·동시 실행 상한, 본문에 사유), 503 `BROKER_UNAVAILABLE`(현행).

## GET /backtests/{backtestId} — 상태·요약

`200` 항상(종결 여부 무관, 현행 규칙).

```json
{
  "backtestId": "BT-20260910-000123", "status": "RUNNING", "resultSource": "NEW", "partial": false,
  "progress": {"stage": "COMPUTING", "lastTradingDate": "2021-06-30", "completedTradingDays": 860, "totalTradingDays": 1905},
  "queuedAt": "…", "startedAt": "…", "finishedAt": null, "queueWaitSeconds": 4,
  "config": {…요청 원문 + effectiveStart/End…},
  "versions": {…},
  "summary": null,
  "failure": null
}
```

- `progress.stage`: `PREPARING_DATA` `COMPUTING` `SAVING`. QUEUED에서는 `progress` null, `queueWaitSeconds`만 채움.
- `CANCELLED`: `partial=true`, `progress.lastTradingDate`가 완료 범위. `summary`는 부분 구간 기준 값.
- `FAILED`: `failure: {code, message}`. code는 `SYSTEM_FAILURE` `INVALID_REQUEST` `DATA_INSUFFICIENT` `CONSTRAINT_VIOLATION` `EXECUTION_LIMIT`. 낮은 수익으로 표현하지 않는다.
- `summary`(COMPLETED·CANCELLED): 핵심 숫자 3개(`netTotalReturn`, `maxDrawdown`, `totalTradingCost`) + `liquidation{markToMarket, liquidated}` + `costs{…}` + `analytics{recoveryDays, turnover}` + `exceptionCount`.

## POST /backtests/{backtestId}/cancel

`202` `{backtestId, status}`. 이 요청만 취소한다(다른 합류 요청은 영향 없음, 마지막이면 실행 정지). 종결 상태면 `409 NOT_CANCELLABLE`. 취소 반영은 다음 진행 배치 이내이며 그동안 `status`는 `RUNNING`, `cancelRequested: true`로 보인다.

## POST /backtests/{backtestId}/retry

`202` 새 `{backtestId, status: QUEUED, resultSource, retryOf}`. 원 요청이 `FAILED` 또는 `CANCELLED`일 때만. 아니면 `409 NOT_RETRYABLE`. 같은 조건이므로 동일 버전 규칙이 그대로 적용된다(다른 사용자가 이미 완료했으면 `REUSED`).

## GET /backtests/{backtestId}/timeseries · /trades · /positions · /weights

- 현행 세 개 유지. `409 RESULT_NOT_READY`(QUEUED·RUNNING), `409 BACKTEST_FAILED`(FAILED). `CANCELLED`는 `200`에 부분 구간 데이터 + `partial=true`.
- 신규 `GET /backtests/{backtestId}/weights`: `[{date, target:{instrumentId: weight}, actual:{…}, weightError}]`, `weightsSource: USER|COMPUTED`.
- `/trades` 항목에 `reason`(`REBALANCE` `HALT_DEFERRED` `LIQUIDATION`)·`plannedDate` 추가. `/timeseries` 항목에 `cashFlows[{type: DISTRIBUTION, instrumentId, amount}]` 추가.
- `GET /backtests/{backtestId}/exceptions`: `[{date, instrumentId, kind, rule, action}]` — 005가 "결과 옆 한 문장"에 쓰는 원천.

## GET /internal/execution-metrics?type=&from=&to= (운영자)

`v_run_metric_summary` 행을 그대로 반환: `executionType, count, failRate, cancelRate, autoRetryRate, waitP50Ms, waitP95Ms, runP50Ms, runP95Ms, peakRssP95Mb, reuseCount`. 운영자 권한 필요. 사용자 화면에 노출하지 않는다.

## 오류 코드 추가분

| code | HTTP | 뜻 |
|---|---|---|
| RULES_NOT_REGISTERED | 422 | 전략 규칙 팀 미확정(PD-03·04) |
| INSUFFICIENT_HISTORY | 422 | 가용 기간이 워밍업보다 짧음 |
| EXECUTION_LIMIT | 429 | 대기열·동시 실행 상한 |
| NOT_CANCELLABLE / NOT_RETRYABLE | 409 | 상태 전이 불가 |

기존 `INVALID_INPUT` `RESOURCE_NOT_FOUND` `RESULT_NOT_READY` `BACKTEST_FAILED` `BROKER_UNAVAILABLE` `INTERNAL_ERROR` 유지.

## 예시 파일

`docs/specs/backtest-api/examples/`에 `backtest-request.json`(v2), `backtest-accepted-joined.json`, `backtest-accepted-reused.json`, `backtest-detail-running.json`, `backtest-detail-cancelled.json`, `backtest-weights.json`, `backtest-exceptions.json`, `execution-metrics.json`을 추가·교체한다.
