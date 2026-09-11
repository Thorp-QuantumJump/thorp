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
  "conditions": {"start": "2018-01-02", "end": "2025-12-30", "initialCash": "10000000.00", "costProfileVersion": null, "evaluationSplitVersion": "SPLIT-1", "warmupTradingDays": null, "scenario": "FULL_PERIOD", "scenarioParams": null}
}
```

- `targetWeight`는 `FIXED_WEIGHT`에서만 필수, 다른 전략에서는 금지(400).
- `costProfileVersion` null → 서버 기본(현재 `PROVISIONAL-DEMO-1`).
- `warmupTradingDays`(선택, 정수 ≥ 0): 비교 묶음의 공통 워밍업. 003(다섯 전략 비교)·007(자동 탐색)이 후보 전체의 최대 관측 길이를 넘긴다. 서버는 이 값과 선택 전략의 관측 길이 중 큰 쪽으로 `effectiveStart`를 정하고 동일 버전 키에 넣는다(R-15·R-03). null이면 선택 전략 값만 쓴다. 전략 최소 관측 길이보다 작으면 422 `INSUFFICIENT_HISTORY`(본문에 필요 값).
- `scenario`: `FULL_PERIOD`(주 분석) · `FRESH_CASH_FROM` · `COST_ESCALATION` · `TARGET_RISK` (FR-016, 별도 실행으로 기록).
- `scenarioParams`: `TARGET_RISK`면 `{"targetVolatility": "0.1000"}` 필수(연환산, 0.0500~0.2000, 0.0100 간격, 기본값은 003 등록표가 제공). `FRESH_CASH_FROM`이면 `{"startDate": "2020-03-02"}`. `FULL_PERIOD`·`COST_ESCALATION`에서는 null. 규칙(63거래일 추정·월말 조절·하향만)은 서버가 `strategy_rules` `TARGET_RISK_OVERLAY/1`에서 채우며 사용자 입력이 아니다(PD-18).

응답 `202 Accepted`:

```json
{"backtestId": "BT-20260910-000123", "status": "QUEUED", "resultSource": "NEW", "effectiveStart": "2018-04-02", "effectiveEnd": "2025-12-30", "versions": {"costProfile": {"version": "PROVISIONAL-DEMO-1", "provisional": true}, "rules": "1", "data": "KRX-2026-09-09", "engine": "nautilus_trader 1.219.0 / worker 2.0.0"}}
```

- `resultSource`: `NEW` 새 실행 / `JOINED` 진행 중 동일 버전 실행에 합류(status는 그 실행의 현재 상태) / `REUSED` 완료 결과 재사용(status `COMPLETED`, 결과 즉시 조회 가능).
- 오류: 400 `INVALID_INPUT`(비중 합·상한 합계·식별자), 422 `RULES_NOT_REGISTERED`(PD-03·04 미확정 전략), 422 `INSUFFICIENT_HISTORY`(워밍업 부족, 본문에 필요 시작일), 429 `EXECUTION_LIMIT`(사용자별·전체 대기열, 동시 실행 상한. 본문에 사유·현재 대기 수·`retryAfterSeconds`. 값은 PD-06, 장치는 T014/R-19. JOINED·REUSED는 세지 않는다), 503 `BROKER_UNAVAILABLE`(현행).

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
- `CANCELLED`: `partial=true`. 두 경우가 있다. ① 실행 자체가 멈춘 경우(마지막 활성 요청 취소·실행 시간 한도): `progress.lastTradingDate`가 완료 범위고 `summary`는 부분 구간 기준 값. ② **합류 요청 하나만 취소해 실행은 계속 도는 경우**: `progress.lastTradingDate` = 취소 시점 스냅샷(`cancelled_last_trading_date`, 이후 바뀌지 않음), `summary=null`, `summaryUnavailable: "CANCELLED_WHILE_RUN_CONTINUED"`. 실행이 나중에 완료돼도 이 요청은 전체 결과를 보이지 않는다(R-04). 같은 조건을 다시 보려면 새 요청을 낸다(REUSED).
- `FAILED`: `failure: {code, message}`. code는 `SYSTEM_FAILURE` `INVALID_REQUEST` `DATA_INSUFFICIENT` `CONSTRAINT_VIOLATION` `EXECUTION_LIMIT`. 낮은 수익으로 표현하지 않는다.
- `summary`(COMPLETED·CANCELLED): 핵심 숫자 3개(`netTotalReturn`, `maxDrawdown`, `totalTradingCost`) + `liquidation{markToMarket, liquidated}` + `costs{…}` + `analytics{recoveryDays, turnover}` + `exceptionCount`.

## POST /backtests/{backtestId}/cancel

`202` `{backtestId, status}`. 이 요청만 취소한다(다른 합류 요청은 영향 없음, 마지막이면 실행 정지). 취소 시점의 완료 범위를 요청에 스냅샷으로 남기므로(`cancelled_last_trading_date`), 다른 합류 요청 때문에 실행이 계속돼도 이 요청의 표시 범위는 늘어나지 않는다. 종결 상태면 `409 NOT_CANCELLABLE`. 취소 반영은 다음 진행 배치 이내이며 그동안 `status`는 `RUNNING`, `cancelRequested: true`로 보인다.

## POST /backtests/{backtestId}/retry

`202` 새 `{backtestId, status: QUEUED, resultSource, retryOf}`. 원 요청이 `FAILED` 또는 `CANCELLED`일 때만. 아니면 `409 NOT_RETRYABLE`. 같은 조건이므로 동일 버전 규칙이 그대로 적용된다(다른 사용자가 이미 완료했으면 `REUSED`).

## GET /backtests/{backtestId}/timeseries · /trades · /positions · /weights

- 현행 세 개 유지. `409 RESULT_NOT_READY`(QUEUED·RUNNING), `409 BACKTEST_FAILED`(FAILED). `CANCELLED`는 `200`에 부분 구간 데이터 + `partial=true`. 합류 취소 요청(실행은 계속)은 `cancelled_last_trading_date`까지만 날짜로 잘라 준다(읽기 필터, 재계산 없음). `/weights`·`/trades`·`/positions`·`/exceptions`도 같다.
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
| EXECUTION_LIMIT | 429 / `failure.code` | 접수: 대기열·동시 실행 상한 초과(본문 `retryAfterSeconds`). 실행: 실행 시간 상한 초과로 정지(run `CANCELLED partial=true`, `failure.code=EXECUTION_LIMIT`). 값은 PD-06(R-19) |
| NOT_CANCELLABLE / NOT_RETRYABLE | 409 | 상태 전이 불가 |

기존 `INVALID_INPUT` `RESOURCE_NOT_FOUND` `RESULT_NOT_READY` `BACKTEST_FAILED` `BROKER_UNAVAILABLE` `INTERNAL_ERROR` 유지.

## 예시 파일

`docs/specs/backtest-api/examples/`에 `backtest-request.json`(v2), `backtest-accepted-joined.json`, `backtest-accepted-reused.json`, `backtest-detail-running.json`, `backtest-detail-cancelled.json`, `backtest-detail-cancelled-joined.json`, `backtest-limit-exceeded.json`, `backtest-weights.json`, `backtest-exceptions.json`, `execution-metrics.json`을 추가·교체한다.
