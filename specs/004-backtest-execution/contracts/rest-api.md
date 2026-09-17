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
- `warmupTradingDays`(선택, 정수 ≥ 0): 비교 묶음의 공통 워밍업. 003(다섯 전략 비교)·007(자동 탐색)이 후보 전체의 최대 관측 길이를 넘긴다. 서버는 이 값을 그대로 워밍업으로 써서 동일 버전 키에 넣는다(R-15·R-03). **이 값으로 `effectiveStart`를 뒤로 밀지는 않는다** — 워밍업 자료는 요청 구간보다 앞에서 읽고, 앞이 모자랄 때 집계 시작일을 미루는 판정은 워커가 단독으로 한다(001 `contracts/market-dataset.md` 「읽기 규칙」). null이면 선택 전략의 관측 길이를 쓴다. 값이 선택 전략의 요구값보다 **작으면 올리지 않고** 422 `INSUFFICIENT_HISTORY`(본문에 필요 값)로 거부한다 — 묶음 최대값 계산이 틀렸다는 신호다. 같으면 통과, 크면 요청값 사용.
- `scenario`: `FULL_PERIOD`(주 분석) · `FRESH_CASH_FROM` · `COST_ESCALATION` · `TARGET_RISK` (FR-016, 별도 실행으로 기록).
- `scenarioParams`: `TARGET_RISK`면 `{"targetVolatility": "0.1000"}` 필수(연환산, 0.0500~0.2000, 0.0100 간격, 기본값은 003 등록표가 제공). `FRESH_CASH_FROM`이면 `{"startDate": "2020-03-02"}`. `FULL_PERIOD`·`COST_ESCALATION`에서는 null. 규칙(63거래일 추정·월말 조절·하향만)은 서버가 `strategy_rules` `TARGET_RISK_OVERLAY/1`에서 채우며 사용자 입력이 아니다(PD-18).

응답 `202 Accepted`:

```json
{"backtestId": "BT-20260910-000123", "status": "QUEUED", "resultSource": "NEW", "effectiveStart": "2018-04-02", "effectiveEnd": "2025-12-30", "versions": {"mapping": "SECTOR-ETF-2026-09", "rules": {"version": "1", "provisional": false}, "costProfile": {"version": "PROVISIONAL-DEMO-1", "provisional": true}, "data": "KRX-2026-09-09", "engine": "nautilus_trader 1.219.0 / worker 2.2.0", "evaluationSplit": "SPLIT-1"}, "publicToken": "3f9a1c7e5b2d4a6f8e0c1b3d5f7a9c2e"}
```

- **로그인하지 않아도 접수한다**(spec 005 B01). 요청자 없이 저장되고(`backtest_request.requester_id` NULL, R-12가 열어 둔 경로) 결과는 `publicToken`으로만 연다. 취소·재시도·조회(`/backtests/{backtestId}/**`)는 그대로 소유자 경로라 비로그인은 401이다. 비로그인 요청은 사용자별 대기 한도 대신 비로그인 요청 전부가 나눠 쓰는 몫(`max-queued-anonymous`, 전체 한도보다 작다)과 전체 한도를 받는다. 몫이 차면 429 `reason: ANONYMOUS`. 값과 비로그인끼리의 공정성(IP·세션 단위)은 PD-06 담당이 실측 뒤 정한다.
- `publicToken`(spec 005 FR-019): 요청마다 발급하는 소문자 16진 32자. NEW·JOINED·REUSED 모두 새로 받는다. 로그인 요청은 만료가 없고, 비로그인 요청은 접수 시각 + 유효 시간(spec 005 PD-23 확정 전 임시값)에 닫힌다.

- `resultSource`: `NEW` 새 실행 / `JOINED` 진행 중 동일 버전 실행에 합류(status는 그 실행의 현재 상태) / `REUSED` 완료 결과 재사용(status `COMPLETED`, 결과 즉시 조회 가능).
- `versions`(FR-028, data-model `backtest_run.versions`): `rules`·`costProfile`은 `{version, provisional}`이고 `provisional=true`는 팀 결정(PD-02·PD-07) 전 임시값으로 계산했다는 표시다. `scenarioRules`는 `TARGET_RISK`일 때만(`TARGET_RISK_OVERLAY/1`). 결과를 낸 워커의 자기 보고 `worker`는 결과가 저장된 뒤 상세에만 붙는다(T018).
- 오류: 400 `INVALID_INPUT`(비중 합·상한 합계·식별자), 422 `RULES_NOT_REGISTERED`(PD-03·04 미확정 전략), 422 `INSUFFICIENT_HISTORY`(워밍업 부족, 본문에 필요 시작일), 429 `EXECUTION_LIMIT`(사용자별·비로그인 합계·전체 대기열 상한, 접수 트랜잭션에서 원자적으로 검사. 본문에 사유·현재 대기 수·`retryAfterSeconds`. 값은 PD-06, 장치는 T014/R-19. JOINED·REUSED는 세지 않는다. 동시 실행 상한은 워커 배치가 강제하므로 접수에서 검사하지 않는다), 503 `BROKER_UNAVAILABLE`(현행).

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
- `CANCELLED`: `partial=true`. 두 경우가 있다. ① **실행을 실제로 멈춘 요청**(마지막 활성 요청의 취소·실행 시간 한도): `progress.lastTradingDate` = FINISHED(CANCELLED)의 `lastTradingDate`(취소를 누른 시점의 진행 날짜보다 며칠 뒤일 수 있다 — 워커는 다음 거래일 처리 시점에 멈춘다), `summary`는 그 구간 기준 값. ② **먼저 취소해 실행은 계속 돈 요청**: `progress.lastTradingDate` = 취소 시점 스냅샷(`cancelled_last_trading_date`, 이후 바뀌지 않음), `summary=null`, `summaryUnavailable: "CANCELLED_WHILE_RUN_CONTINUED"`. 실행이 나중에 완료되거나 다른 요청이 더 늦게 취소해 더 긴 부분 결과가 생겨도 이 요청은 자기 스냅샷을 넘는 결과를 보이지 않는다(R-04). 완료한 거래일이 없으면(첫 PROGRESS 전 취소) `progress.lastTradingDate=null`이고 하위 리소스는 run이 끝난 뒤에도 `409 NO_PARTIAL_RESULT`다. ③ **취소 무응답**(CANCEL 뒤 유예 시간 안 FINISHED 없음, R-19): run은 `FAILED(SYSTEM_FAILURE)`로 강제 종결되고 결과가 없다. 취소한 요청은 `CANCELLED`·`partial=false`·`summary=null`·`summaryUnavailable: "CANCEL_UNANSWERED"`, `failure`에 run 실패 사유, 하위 리소스 `409 BACKTEST_FAILED`. 상세 조회는 모든 경우 200이다. 같은 조건을 다시 보려면 새 요청을 낸다(REUSED).
- `FAILED`: `failure: {code, message}`. code는 `SYSTEM_FAILURE` `INVALID_REQUEST` `DATA_INSUFFICIENT` `CONSTRAINT_VIOLATION` `ESTIMATION_FAILED`(최소분산 공분산 검사·솔버 실패, T022) `EXECUTION_LIMIT`. `SYSTEM_FAILURE`에는 워커 소멸(DLQ)뿐 아니라 정체·취소 무응답 강제 종결(R-19)이 포함되며 `message`로 구분한다. 낮은 수익으로 표현하지 않는다.
- `summary`(COMPLETED·CANCELLED): 핵심 숫자 3개(`netTotalReturn`, `maxDrawdown`, `totalTradingCost`) + `liquidation{markToMarket, liquidated}` + `costs{…}` + `analytics{recoveryDays, turnover}` + `exceptionCount`.

## POST /backtests/{backtestId}/cancel

`202` `{backtestId, status}`. 이 요청만 취소한다(다른 합류 요청은 영향 없음, 마지막이면 실행 정지). 취소 시점의 완료 범위를 요청에 스냅샷으로 남기므로(`cancelled_last_trading_date`), 다른 합류 요청 때문에 실행이 계속돼도 이 요청의 표시 범위는 늘어나지 않는다. 종결 상태면 `409 NOT_CANCELLABLE`. 취소 반영은 다음 진행 배치 이내이며 그동안 `status`는 `RUNNING`, `cancelRequested: true`로 보인다.

## POST /backtests/{backtestId}/retry

`202` 새 `{backtestId, status: QUEUED, resultSource, retryOf, publicToken}`(`publicToken`은 새 요청의 것, spec 005). 원 요청이 `FAILED` 또는 `CANCELLED`일 때만. 아니면 `409 NOT_RETRYABLE`. 같은 조건이므로 동일 버전 규칙이 그대로 적용된다(다른 사용자가 이미 완료했으면 `REUSED`).

## GET /backtests/{backtestId}/timeseries · /trades · /positions · /weights

- 현행 세 개 유지. `409 RESULT_NOT_READY`(QUEUED·RUNNING), `409 BACKTEST_FAILED`(FAILED). `CANCELLED`는 `200`에 부분 구간 데이터 + `partial=true`. 먼저 취소한 요청(스냅샷 있음)은 공용 실행이 끝난 뒤 `cancelled_last_trading_date`까지만 날짜로 잘라 준다(읽기 필터, 재계산 없음). **공용 실행이 아직 종결 전이면 `409 RESULT_NOT_READY`**(상세 결과가 저장되기 전), **공용 실행이 `FAILED`로 끝나면 `409 BACKTEST_FAILED`**(부분 결과를 확보하지 못함). 완료한 거래일이 없는 취소 요청(스냅샷 NULL)은 `409 NO_PARTIAL_RESULT`. `/weights`·`/trades`·`/positions`·`/exceptions`도 같다.
- 신규 `GET /backtests/{backtestId}/weights`: `[{date, target:{instrumentId: weight}, actual:{…}, weightError}]`, `weightsSource: USER|COMPUTED`.
- `/trades` 항목에 `reason`(`REBALANCE` `HALT_DEFERRED` `LIQUIDATION`)·`plannedDate` 추가. 질의 `from`·`to`(선택, `YYYY-MM-DD`, 양 끝 포함, spec 005 T015): 진입 또는 청산이 그 기간 안인 거래만. `from > to`는 400 `INVALID_INPUT`. `/timeseries` 항목에 `cashFlows[{type: DISTRIBUTION, instrumentId, amount}]` 추가.
- `GET /backtests/{backtestId}/exceptions`: `[{date, instrumentId, kind, rule, action}]` — 005가 "결과 옆 한 문장"에 쓰는 원천. `kind`는 기존 `INSUFFICIENT_HISTORY`·`ZERO_VOLATILITY`에 `CAP_OVERFLOW_CASH`·`EFFECTIVE_TARGET_REDUCED`·`SINGLE_ASSET_FALLBACK`·`NO_SELECTION`이 더해진다(2026-09-17 PD-03·04, T022·T023).

## GET /internal/execution-metrics?type=&from=&to= (운영자)

`v_run_metric_summary` 행을 그대로 반환: `executionType, count, failRate, cancelRate, autoRetryRate, waitP50Ms, waitP95Ms, runP50Ms, runP95Ms, peakRssP95Mb, reuseCount`. 운영자 권한 필요. 사용자 화면에 노출하지 않는다.

## GET /backtests/registry — 규칙·비용 버전 조회 (T025, 2026-09-17)

003 화면이 요청에 실을 `strategy.rulesVersion`(필수)과 명시할 `conditions.costProfileVersion`을 읽는다. 로그인 불필요.

```json
{ "strategyRules": [ { "strategyId": "INVERSE_VOLATILITY", "rulesVersion": "1", "status": "REGISTERED", "provisional": true },
                     { "strategyId": "SECTOR_MOMENTUM",    "rulesVersion": "1", "status": "PENDING_DECISION", "provisional": false } ],
  "defaultCostProfile": { "version": "PROVISIONAL-DEMO-1", "provisional": true,
                          "commissionBps": "0", "spreadBps": "0", "slippageBps": "5", "taxBps": "0" } }
```

- `strategyRules`는 전략당 한 행(`REGISTERED` 또는 `PENDING_DECISION`). 화면은 `REGISTERED` 행의 `rulesVersion`을 싣고 `PENDING_DECISION`이면 '분석하기'를 막는다. `TARGET_RISK_OVERLAY` 행도 나가며 화면은 `strategyId`로 조인만 한다.
- 화면은 `defaultCostProfile.version`을 요청에 **명시해** 보낸다(기본 버전이 배포로 바뀌어도 사용자가 본 조건이 적용되도록). 비용 수치는 문자열 decimal.

## 오류 코드 추가분

| code | HTTP | 뜻 |
|---|---|---|
| RULES_NOT_REGISTERED | 422 | 전략 규칙 팀 미확정(`PENDING_DECISION`) |
| VALIDATION_RULE_VIOLATED | 422 | 접수 때 알 수 있는 설정 모순: `상한 × 투자 대상 종목 수 < 투자 비중`(PD-04 ④, 기존 400 `INVALID_INPUT`에서 옮김) |
| INSUFFICIENT_HISTORY | 422 | 가용 기간이 워밍업보다 짧음 |
| ESTIMATION_FAILED | `failure.code` | 최소분산 공분산 검사·SLSQP 수렴·사후 검증 실패 → 실행 전체 실패(PD-04 ③, T022) |
| EXECUTION_LIMIT | 429 / `failure.code` | 접수: 대기열 상한 초과(사용자별·전체, 본문 `retryAfterSeconds`). 실행: 실행 시간 상한 초과로 정지(run `CANCELLED partial=true`, `failure.code=EXECUTION_LIMIT`). 값은 PD-06(R-19) |
| NOT_CANCELLABLE / NOT_RETRYABLE | 409 | 상태 전이 불가 |
| NO_PARTIAL_RESULT | 409 | 취소 요청에 완료한 거래일이 없음(첫 PROGRESS 전 취소). 재시도 무의미 |

기존 `INVALID_INPUT` `RESOURCE_NOT_FOUND` `RESULT_NOT_READY` `BACKTEST_FAILED` `BROKER_UNAVAILABLE` `INTERNAL_ERROR` 유지.

## 예시 파일

`docs/specs/backtest-api/examples/`에 `backtest-request.json`(v2), `backtest-accepted-joined.json`, `backtest-accepted-reused.json`, `backtest-detail-running.json`, `backtest-detail-cancelled.json`, `backtest-detail-cancelled-joined.json`, `backtest-limit-exceeded.json`, `backtest-weights.json`, `backtest-exceptions.json`, `execution-metrics.json`을 추가·교체한다.
