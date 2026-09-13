# 백테스트 HTTP 계약 (Backend ↔ Frontend)

브로커 메시지 계약은 [`../backtest-messaging/README.md`](../backtest-messaging/README.md)에 있다. 이 문서는 프론트가 쓰는
HTTP 계약만 다룬다. 근거: [ADR-0002](../../adr/0002-rabbitmq-backtest-message-contract.md) D1~D4.

예시 파일이 계약의 기준이다. 백엔드 컨트롤러 테스트가 이 값들을 그대로 쓴다.

- 요청: [`examples/backtest-request.json`](examples/backtest-request.json)
- 202 응답: [`examples/backtest-accepted.json`](examples/backtest-accepted.json)

## 1. 백테스트 실행 요청

```
POST /backtests
Content-Type: application/json
```

응답은 `202 Accepted`. 백엔드는 계산하지 않고 Job을 저장한 뒤 작업 큐에 넣는다.

### 요청 필드 (schema v2, spec 004)

v1과 모양이 다르다. 종목 목록 대신 **섹터 배분**을 받고, 전략에 규칙 버전이 붙고, 기간·금액·시나리오가 `conditions`로 모인다.
전체 규칙은 [`specs/004-backtest-execution/contracts/rest-api.md`](../../../specs/004-backtest-execution/contracts/rest-api.md)가 원본이다.

| 경로 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `executionType` | `enum` | 필수 | `SINGLE` `SEARCH_BUNDLE` `REGIME_SECTOR` `REGIME_TOTAL` |
| `sectorAllocation.mappingVersion` | `string` | 필수 | 섹터-ETF 대응표 버전. 서버가 (섹터, 종목) 쌍을 이 버전으로 검증한다 |
| `sectorAllocation.items[].sectorId` | `string` | 필수 | 대응표의 섹터 식별자 |
| `sectorAllocation.items[].instrumentId` | `string` | 필수 | `{6자리 종목코드}.XKRX`. 중복 금지, 1~50개 |
| `sectorAllocation.items[].targetWeight` | `string` (decimal) | 조건부 | **`FIXED_WEIGHT`에서만 필수이고 다른 전략에서는 금지**(400). 합이 1이어야 한다 |
| `strategy.strategyId` | `string` | 필수 | 등록표에 `REGISTERED`인 전략만. 팀 미확정 전략은 422 `RULES_NOT_REGISTERED` |
| `strategy.rulesVersion` | `string` | 필수 | 전략 규칙 버전. 결과 재현의 기준이다 |
| `strategy.registryVersion` | `string` | 선택 | 003 파라미터 등록표 버전. 지금은 기록만 한다 |
| `strategy.parameters` | `object` | 필수 | 전략별 자유 형식. 빈 객체 허용, 값에 `null` 금지 |
| `conditions.start` / `end` | `date` | 필수 | `start`가 `end`보다 늦을 수 없다 |
| `conditions.initialCash` | `string` (decimal) | 필수 | 0보다 큼. 소수점 2자리까지 |
| `conditions.costProfileVersion` | `string` | 선택 | `null` = 서버 기본(현재 `PROVISIONAL-DEMO-1`) |
| `conditions.evaluationSplitVersion` | `string` | 선택 | 평가 분할 버전 |
| `conditions.warmupTradingDays` | `int` | 선택 | 비교 묶음의 공통 워밍업. `null` = 선택 전략의 관측 길이. **선택 전략의 요구값보다 작으면 올리지 않고 422 `INSUFFICIENT_HISTORY`** |
| `conditions.scenario` | `enum` | 선택 | `FULL_PERIOD`(기본) `FRESH_CASH_FROM` `COST_ESCALATION` `TARGET_RISK` |
| `conditions.scenarioParams` | `object` | 조건부 | `TARGET_RISK`면 `targetVolatility`(0.0500~0.2000, 0.0100 간격) 필수, `FRESH_CASH_FROM`이면 `startDate` 필수, 그 외에는 금지 |

금액과 비율은 **문자열**로 보낸다(부동소수 오차 방지).

**인증**: 모든 엔드포인트가 요청자를 요구한다(spec 004 R-12). 남의 요청은 403이 아니라 **404**로 숨긴다 — 403은 그 식별자가
존재한다는 사실을 알려 준다. 이 저장소에는 아직 로그인이 없어서, 개발 환경에서만 `thorp.auth.trust-user-id-header=true`와
`X-User-Id` 헤더로 대신한다. **헤더를 믿는 것은 인증이 아니다** — spec 014가 들어오면 `CurrentRequester` 하나만 바뀐다.

### 202 응답

```json
{ "success": true, "data": { "backtestId": "BT-20260911-000001", "status": "QUEUED", "resultSource": "NEW",
  "effectiveStart": "2023-07-03", "effectiveEnd": "2024-12-31", "versions": { … } }, "error": null }
```

- `backtestId`는 **요청**의 식별자다 `BT-{yyyyMMdd}-{6자리 이상 순번}`(ADR-0002 D2). 순번이 999,999를 넘으면 자릿수가
  늘어나므로 **길이를 가정하지 말고 문자열로 다룬다.** 여러 요청이 한 실행을 가리킬 수 있어서(spec 004 R-02) 실행 식별자와는 다르다.
- `resultSource`: `NEW` 새 계산 / `JOINED` 진행 중인 같은 조건 실행에 합류 / `REUSED` 완료 결과 재사용. **`REUSED`면 `status`가
  이미 `COMPLETED`이고 결과를 바로 조회할 수 있다** — 상태만 보고 폴링을 시작하지 말 것.
- `effectiveStart`는 워밍업과 상장일을 반영한 실제 평가 시작일이라 요청 시작일보다 뒤일 수 있다.
- `versions`는 결과 재현의 기준(비용 조건·규칙·자료·엔진)이고 `costProfile.provisional=true`는 팀 확정 전 임시값이라는 뜻이다.
- 오류: 400 `INVALID_INPUT`, 422 `RULES_NOT_REGISTERED`, 422 `INSUFFICIENT_HISTORY`, 429 `EXECUTION_LIMIT`(T014), 503 `BROKER_UNAVAILABLE`.

**발행은 커밋 뒤에 한다**(spec 004 R-18). 브로커에 닿지 못해도 접수는 202로 성공하고, 실행은 `QUEUED`로 남아 재발행 대상이 된다.
예전처럼 503으로 거절하고 롤백하지 않는다 — 워커의 이벤트가 커밋을 앞질러 도착하면 결과를 잃기 때문이다.

## 2. 결과 조회

결과는 응답 크기 때문에 네 리소스로 나눈다(설계 OUTPUT Spec "API 응답 분리"). 필드 의미·타입·nullable 규칙은
그 문서를 따르고, **분리된 응답의 껍데기와 준비 상태 오류(409 두 코드)는 이 계약에서 정한다.**

| 리소스 | 역할 |
|---|---|
| `GET /backtests/{backtestId}` | 상태 + `config`(무엇을 어떻게 돌렸나) + `summary`(전체 성과) + `error` + 실행 시각 |
| `GET /backtests/{backtestId}/timeseries` | 차트용 거래일별 시계열 |
| `GET /backtests/{backtestId}/trades` | 포지션 단위 거래 내역(페이지네이션·필터) |
| `GET /backtests/{backtestId}/positions?date=` | 특정 시점 보유 구성(직전 스냅샷 규칙) |
| `GET /backtests/{backtestId}/weights` | 리밸런싱마다 목표·실제 비중과 비중 출처(FR-026) |
| `GET /backtests/{backtestId}/exceptions` | 예외 처리 기록(FR-020·FR-030) — 005가 "결과 옆 한 문장"에 쓰는 원천 |

예시 파일이 계약의 기준이다. 백엔드 통합 테스트가 응답과 이 파일들의 동치를 단언한다.
네 파일은 브로커 계약 픽스처 [`../backtest-messaging/examples/event-finished-completed.json`](../backtest-messaging/examples/event-finished-completed.json)와
[`engine-input.json`](../backtest-messaging/examples/engine-input.json)을 투영한 것이므로, 그 픽스처가 바뀌면 같이 고친다.

- [`examples/backtest-detail.json`](examples/backtest-detail.json)
- [`examples/backtest-timeseries.json`](examples/backtest-timeseries.json)
- [`examples/backtest-trades.json`](examples/backtest-trades.json)
- [`examples/backtest-positions.json`](examples/backtest-positions.json)
- [`examples/backtest-weights.json`](examples/backtest-weights.json)
- [`examples/backtest-exceptions.json`](examples/backtest-exceptions.json)

하위 리소스 다섯 개는 모두 `partial`을 싣는다 — 취소·실행 시간 한도로 끊긴 부분 결과라는 뜻이고, 먼저 취소해 실행이 계속 돈 요청은
자기 스냅샷 날짜까지만 담긴다(§2.5).

### 2.1 상태·설정·요약 — `GET /backtests/{backtestId}`

상태 흐름은 `QUEUED → RUNNING → COMPLETED | FAILED | CANCELLED`다. `RUNNING` 이후는 Worker의 결과 이벤트로 갱신된다.

**HTTP는 모든 정상 상태에서 200이다.** 조회 자체가 성공했다면 백테스트가 실패했더라도 200이고, 실패는 body의 `status`·`failure`로
전달한다. 폴링은 이 리소스로 한다.

| `status` | `config` | `progress` | `summary` | `failure` |
|---|---|---|---|---|
| `QUEUED` | O | `null` | `null` | `null` |
| `RUNNING` | O | O | `null` | `null` |
| `COMPLETED` | O | O | O | `null` |
| `FAILED` | O | O | `null` | O |
| `CANCELLED` | O | O | 부분 구간 기준 또는 `null` | 조건부 |

- `config`는 접수가 저장한 요청 사본에 `effectiveStart`·`effectiveEnd`를 얹은 것이다. `QUEUED`부터 값이 있다.
- `progress`: `stage`(`PREPARING_DATA` → `COMPUTING` → `SAVING`), `lastTradingDate`(처리한 마지막 거래일),
  `completedTradingDays`/`totalTradingDays`. `QUEUED`에서는 `null`이고 `queueWaitSeconds`만 채운다 — 아무 거래일도
  처리하지 않았는데 0%를 보여 주면 "시작했다"로 읽힌다.
- `queueWaitSeconds`는 접수부터 실행 시작까지다. 아직 시작 전이면 지금까지 기다린 시간이다.
- `partial`은 취소·한도로 끊긴 부분 결과라는 뜻이다.
- `summaryUnavailable`은 요약을 줄 수 없는 이유다. 먼저 취소해 실행이 계속 돈 요청은 `CANCELLED_WHILE_RUN_CONTINUED`이며
  요약이 `null`이다 — 백엔드가 지표를 다시 계산하지 않기 때문이다(spec 004 R-04).
- `versions`는 202 응답과 같은 값이다.
- `failure.code`는 엔진이 준 값(`SYSTEM_FAILURE` `INVALID_REQUEST` `DATA_INSUFFICIENT` `CONSTRAINT_VIOLATION`
  `EXECUTION_LIMIT`)이며 HTTP 오류 코드와 다른 집합이다.

**`config.initialCash`의 자릿수는 요청과 다를 수 있다.** 저장 컬럼이 `NUMERIC(19,2)`이라 `"10000000"`으로 보낸 값이
`"10000000.00"`으로 돌아온다. 값은 같고 표기 자릿수만 컬럼 scale로 정규화된다.

`summary`는 v1 항목 열두 개(`initialEquity`…`totalSlippageCost`)를 그대로 두고 v2 항목 여섯을 더한 것이다(spec 004 T009).
핵심 숫자 3개는 `netTotalReturn`(비용 차감 후 전체 수익률)·`maxDrawdown`·`totalTradingCost`다.

| 항목 | 뜻 |
|---|---|
| `netTotalReturn` | 비용 차감 후 전체 수익률. 자산 곡선이 이미 비용을 뺀 값이라 `totalReturn`과 같다 |
| `totalTradingCost` | 거래비용 합계 = `costs.total` |
| `costs{commission, spread, slippage, tax, total}` | 비용 네 갈래(FR-027) |
| `liquidation{markToMarket{finalEquity,totalReturn}, liquidated{finalEquity,totalReturn,liquidationCost}}` | 종료일 종가 전량 매도 가정치(FR-017). 요청이 청산을 끄면 `null` |
| `analytics{recoveryDays, turnover}` | 회복 거래일(미회복이면 `null`)·연환산 회전율. 나머지 파생 지표(상관·위험 기여·손실 기여)는 결과 저장소에 있고 이 API 로는 내려주지 않는다 |
| `exceptionCount` | 예외 처리 기록 건수. 본문은 `/exceptions` |

값의 정의는 워커 `performance.calculate_analytics` docstring 이 원본이다(spec 004 T008 결정 ①).

### 2.2 시계열 — `GET /backtests/{backtestId}/timeseries`

거래일마다 1개, 해당 거래일 Close 기준이다. `cash`·`positionValue`·`dailyReturn`·`positions`는 내려주지 않는다.

`benchmarkReturn`은 벤치마크가 없거나 그 날짜 값이 없으면 **키 자체를 생략한다**(`null`이 아니다).
`cashFlows[{type, instrumentId?, amount}]`는 분배금 등 현금 흐름이 있는 날에만 있다(FR-013, `type`은 현재 `DISTRIBUTION`). 없는 날은 키를 생략한다.

예시 파일의 `points`는 **발췌다** — 픽스처의 전체 시계열은 129개이고 파일에는 앞 3개만 담았다. 실제 응답은 거래일 수만큼 온다(5년이면 약 1,250개).

### 2.3 거래 내역 — `GET /backtests/{backtestId}/trades`

1 거래 = 포지션 라이프사이클(진입 → 청산)이다. 주문·체결 단위가 아니다.

| 파라미터 | 타입 | 기본값 | 규칙 |
|---|---|---|---|
| `page` | integer | `0` | 0 이상 |
| `size` | integer | `100` | 1~1000. **생략과 `size=0`은 다르다** — 생략은 100, `0`은 400 |
| `instrumentId` | string | (없음) | 종목 형식과 같음. 위반은 400이고 빈 페이지가 아니다 |
| `status` | enum | (없음) | `OPEN` \| `CLOSED` |

정렬은 `entryDate` 오름차순 **고정**이다(같은 날은 안정 순서). 정렬 파라미터는 없다.
거래가 0건이면 `content: []`와 200이다.

OPEN 거래는 `exitDate`·`exitPrice`·`grossPnl`·`realizedPnl`·`returnRate`가 **명시적 `null`**이다(키가 있다).
0으로 대체해 표시하지 않는다.

`reason`은 거래 사유(`REBALANCE` 리밸런싱, `HALT_DEFERRED` 거래 불가로 미룬 체결, `LIQUIDATION` 종료 청산, `SIGNAL` 단일 종목 신호)이고
항상 있다. `plannedDate`는 `HALT_DEFERRED`에서만 있는 원래 체결 예정일이며 없으면 키를 생략한다.

### 2.4 보유 구성 — `GET /backtests/{backtestId}/positions?date=`

`date`는 필수다(`YYYY-MM-DD`). 보유 구성이 바뀐 거래일 + 시작일 + 종료일에만 스냅샷이 있으므로,
**요청일 이하에서 스냅샷이 있는 가장 최근 날짜**를 돌려주고 그 날짜를 `snapshotDate`에 담는다.

- `snapshotDate < requestedDate`가 정상이다. 보유 종목·수량은 정확하지만 `marketValue`·`weight`·`unrealizedPnl`
  평가액은 **`snapshotDate`의 Close 기준**이므로 화면에 그 날짜를 표시한다.
- `positions: []`는 "그날 보유 없음이 확정"이고 "스냅샷 없음"과 다르다.
- 요청일 이전에 스냅샷이 하나도 없으면(백테스트 시작일보다 앞) 400이다.

### 2.5 하위 리소스의 준비 상태

`/timeseries`·`/trades`·`/positions`·`/weights`·`/exceptions`는 결과가 없으면 데이터를 만들어 내지 않고 409를 준다.
**빈 배열로 응답하지 않는다** — 실패한 실행에 `{"points":[]}`를 주면 "돌았고 자산 곡선이 비어 있었다"는 거짓이 된다.

| 상태 | HTTP | `error.code` | 프론트 동작 |
|---|---|---|---|
| 실행 `QUEUED`·`RUNNING` | 409 | `RESULT_NOT_READY` | 폴링 계속 |
| 실행 `FAILED` | 409 | `BACKTEST_FAILED` | **폴링 중단.** `GET /backtests/{id}`의 `failure` 표시 |
| 실행 `COMPLETED`·`CANCELLED` | 200 | — | 그린다. `partial=true`면 부분 구간임을 표시 |
| 요청이 먼저 취소됐고 완료한 거래일이 없음 | 409 | `NO_PARTIAL_RESULT` | 재시도 무의미. 새 요청을 낸다 |

**먼저 취소해 실행이 계속 돈 요청**(spec 004 R-04)은 공용 실행이 끝난 뒤 자기 스냅샷 날짜(`progress.lastTradingDate`)까지만 잘라 준다 —
백엔드가 지표를 다시 계산하지 않는 읽기 필터다. 날짜가 있는 항목(시계열·보유·비중·예외)은 날짜로 자른다. 거래는 스냅샷 다음 날 0시(거래소 현지) 전에
**진입하고 청산까지 끝난 거래와 실행 종료까지 열려 있던 거래만** 준다 — 스냅샷을 가로질러 뒤에 청산된 거래는 청산가·실현손익이 스냅샷 밖이라 보이지 않는다
(`/positions`에는 그 보유가 있으나 `/trades`에는 없을 수 있다). 공용 실행이 종결 전이면 `RESULT_NOT_READY`, `FAILED`로 끝나면 `BACKTEST_FAILED`다.

### 2.6 비중 기록 — `GET /backtests/{backtestId}/weights`

리밸런싱마다 1개. `points[{date, target:{instrumentId: weight}, actual:{…}, weightError, riskScale?, estimatedVolatility?}]`와
`weightsSource`(`USER` 사용자가 준 비중 / `COMPUTED` 전략이 계산한 비중). `target`은 판단일에 정한 목표(현금 몫은 빠져 합이 1 미만일 수 있다),
`actual`은 체결 뒤 실제 비중, `weightError`는 Σ|target − actual|(정수 수량 반올림 등). `riskScale`·`estimatedVolatility`는
`scenario=TARGET_RISK`에서만 있고 그때 `target`은 조절 뒤 비중이다(PD-18). 그 밖에는 키를 생략한다. 예시: [`examples/backtest-weights.json`](examples/backtest-weights.json).

### 2.7 예외 처리 기록 — `GET /backtests/{backtestId}/exceptions`

`items[{date, instrumentId?, kind, rule?, action?}]`. `kind`는 개방 집합이다 — `HALT_DEFERRED`(거래 불가 이월), `HALT_SUPERSEDED`(이월 주문이
다음 판단일을 넘겨 새 목표로 교체), `WEIGHT_ROUNDING`(정수 수량으로 한 주도 못 삼), `SPLIT_FRACTION`(분할 단주), `ZERO_VOLATILITY`, `NO_SELECTION`,
`ESTIMATION_FAILED`, `CONSTRAINT_VIOLATION` 등. 포트폴리오 전체에 걸린 예외는 `instrumentId`가 없다. 정렬은 날짜 오름차순(엔진 기록 순).
예시: [`examples/backtest-exceptions.json`](examples/backtest-exceptions.json).

## 3. 오류 응답

모든 오류는 공통 포맷을 쓴다.

```json
{ "success": false, "data": null, "error": { "code": "INVALID_INPUT", "message": "..." } }
```

| HTTP | `error.code` | 언제 |
|---|---|---|
| 400 | `INVALID_INPUT` | 필수 필드 누락, 종목 형식·개수 위반, 소수 자릿수 초과, `maxPositionPct` 범위 위반, `start > end`, `parameters` 값에 `null` |
| 404 | `RESOURCE_NOT_FOUND` | 없는 `backtestId` 조회 |
| 409 | `RESULT_NOT_READY` | 하위 리소스(`/timeseries`·`/trades`·`/positions`)를 조회했는데 Job이 `QUEUED`·`RUNNING`이다. **재시도하면 된다** |
| 409 | `BACKTEST_FAILED` | 하위 리소스를 조회했는데 Job이 `FAILED`다. **재시도해도 같다** — `GET /backtests/{id}`의 `error`를 본다 |
| 409 | `NO_PARTIAL_RESULT` | 먼저 취소한 요청에 완료한 거래일이 없다(첫 PROGRESS 전 취소). **재시도 무의미** — 같은 조건을 다시 보려면 새 요청을 낸다 |
| 503 | `BROKER_UNAVAILABLE` | 작업 큐에 연결 실패. Job은 저장되지 않으므로 **그대로 재시도해도 안전하다** |
| 500 | `INTERNAL_ERROR` | 그 외. **`COMPLETED`인데 결과가 없는 데이터 손상**도 여기다 — 재시도하지 말고 백엔드 로그를 본다 |

400의 `message`는 첫 번째 위반 필드만 담는다(`portfolio.initialCash: ...` 형태). 사용자에게 그대로 보여주기보다 필드
위치를 잡는 용도로 쓰는 편이 낫다.

## 4. 프론트가 알아야 할 것

- `backtestId`는 숫자가 아니라 문자열이다. 경로 변수도 문자열로 넣는다.
- 같은 조건으로 다시 요청하면 현재는 **항상 새 Job**이 만들어진다(새 `backtestId`). spec 004(T003 요청/실행 분리)부터는
  같은 입력의 요청이 진행 중인 실행에 합류하고 결과를 공유한다 — 그때 이 문서의 2절을 다시 고친다.
- 요청은 5블록(run / universe / strategy / portfolio / benchmark)이다. 블록별 필드는 위 1절 표와 요청 예시를 본다.
- 종목은 계약상 50개까지 보낼 수 있지만, 현재 엔진은 1종목만 처리한다. 2개 이상 보내면 Job은 접수되고 실행 단계에서
  `FAILED`가 된다. MVP 화면은 1종목만 보낸다.
- **금액·비율은 응답에서도 문자열이다.** `"0.166652"`처럼 온다. 숫자로 파싱해도 되지만 금액 합계처럼 정밀도가
  필요한 계산은 문자열로 다룬다.
- **`GET /backtests/{id}`는 백테스트가 실패해도 200이다.** `status`와 `error`로 판단한다. 폴링은 이 리소스로 하고,
  하위 리소스의 409 `RESULT_NOT_READY`(계속) / `BACKTEST_FAILED`(중단)로 재시도 여부를 가른다(§2.5).
- **벤치마크는 항상 비어 있다.** 요청에 `benchmark.instrumentId`를 넣으면 `config.benchmarkId`에는 보이지만, 메시지 계약
  v2에는 벤치마크가 없어 워커에 전달되지 않는다. `summary.benchmark`는 `null`이고 `timeseries[].benchmarkReturn`은 키가 없다.
  벤치마크 비교는 별도 spec에서 다룬다.
- 현재 엔진이 실행할 수 있는 전략은 `MOMENTUM`(파라미터 `lookbackDays`)뿐이다. 다른 `strategyId`는 Job이 접수되고
  실행 단계에서 `FAILED`(`INVALID_REQUEST`)가 된다.
