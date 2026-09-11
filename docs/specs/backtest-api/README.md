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

### 요청 필드

| 경로 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `run.start` | `date` (`YYYY-MM-DD`) | 필수 | `end`보다 늦을 수 없다 |
| `run.end` | `date` | 필수 | |
| `universe.instrumentIds` | `string[]` | 필수 | 1~50개. 각 항목은 `{6자리 종목코드}.XKRX`. 중복 금지 |
| `strategy.strategyId` | `string` | 필수 | 공백 아님, 64자 이하 |
| `strategy.parameters` | `object` | 필수 | 전략별 자유 형식. 빈 객체 허용. 값에 `null`을 쓰지 않는다. 숫자는 숫자로 보낸다(정수/소수 유지) |
| `portfolio.initialCash` | `string` (decimal) | 필수 | 0보다 큼. 소수점 2자리까지 |
| `portfolio.positionSizing` | `object` | 선택 | 생략하면 `type = STRATEGY_DEFINED`, 제한 없음 |
| `portfolio.positionSizing.type` | `enum` | 블록이 있으면 필수 | `STRATEGY_DEFINED` |
| `portfolio.positionSizing.maxPositionPct` | `string` (decimal) | 선택 | `(0, 1]`. 소수점 4자리까지. 생략 = 제한 없음 |
| `benchmark` | `object` | 선택 | 생략 = 벤치마크 미지정 |
| `benchmark.instrumentId` | `string` | 블록이 있으면 필수 | 종목 형식과 같음 |

금액과 비율은 **문자열**로 보낸다(부동소수 오차 방지). 소수 자릿수 제한은 저장 컬럼(`NUMERIC(19,2)`,
`NUMERIC(5,4)`)과 같다 — 더 세밀한 값은 저장 시 반올림되어 조용히 달라지므로 요청 단계에서 거부한다.

`strategy.parameters`의 스키마 검증은 전략 카탈로그 작업에서 붙는다. 지금은 `strategyId`가 비어 있는지만 본다.

### 202 응답

```json
{ "success": true, "data": { "backtestId": "BT-20260908-000001", "status": "QUEUED" }, "error": null }
```

`backtestId`는 **문자열** `BT-{yyyyMMdd}-{6자리 이상 순번}`이다(ADR-0002 D2). DB PK(숫자)는 노출하지 않는다. 순번이
999,999를 넘으면 자릿수가 늘어나므로 **길이를 가정하지 말고 문자열로 다룬다.**

## 2. 결과 조회

결과는 응답 크기 때문에 네 리소스로 나눈다(설계 OUTPUT Spec "API 응답 분리"). 필드 의미·타입·nullable 규칙은
그 문서를 따르고, **분리된 응답의 껍데기와 준비 상태 오류(409 두 코드)는 이 계약에서 정한다.**

| 리소스 | 역할 |
|---|---|
| `GET /backtests/{backtestId}` | 상태 + `config`(무엇을 어떻게 돌렸나) + `summary`(전체 성과) + `error` + 실행 시각 |
| `GET /backtests/{backtestId}/timeseries` | 차트용 거래일별 시계열 |
| `GET /backtests/{backtestId}/trades` | 포지션 단위 거래 내역(페이지네이션·필터) |
| `GET /backtests/{backtestId}/positions?date=` | 특정 시점 보유 구성(직전 스냅샷 규칙) |

예시 파일이 계약의 기준이다. 백엔드 통합 테스트가 응답과 이 파일들의 동치를 단언한다.
네 파일은 브로커 계약 픽스처 [`../backtest-messaging/examples/event-finished-completed.json`](../backtest-messaging/examples/event-finished-completed.json)와
[`engine-input.json`](../backtest-messaging/examples/engine-input.json)을 투영한 것이므로, 그 픽스처가 바뀌면 같이 고친다.

- [`examples/backtest-detail.json`](examples/backtest-detail.json)
- [`examples/backtest-timeseries.json`](examples/backtest-timeseries.json)
- [`examples/backtest-trades.json`](examples/backtest-trades.json)
- [`examples/backtest-positions.json`](examples/backtest-positions.json)

### 2.1 상태·설정·요약 — `GET /backtests/{backtestId}`

상태 흐름은 `QUEUED → RUNNING → COMPLETED | FAILED`다(ADR-0002 D1). `RUNNING` 이후는 Worker의 결과 이벤트로 갱신된다.

**HTTP는 정상 상태 네 가지 모두 200이다.** 설계 "HTTP 상태 코드": "Job 조회 자체가 성공했다면 백테스트가
실패했더라도 200"이고, 실패는 body의 `status`·`error`로 전달한다. 폴링은 이 리소스로 한다.

| `status` | `config` | `summary` | `error` | `startedAt`·`finishedAt` |
|---|---|---|---|---|
| `QUEUED` | O | `null` | `null` | `null` |
| `RUNNING` | O | `null` | `null` | `null` |
| `COMPLETED` | O | O | `null` | O |
| `FAILED` | O | `null` | O | O |

`config`는 요청 시점에 확정되므로 `QUEUED`부터 값이 있다(`strategyVersion` 포함).

**`config.initialCash`의 자릿수는 요청과 다를 수 있다.** 저장 컬럼이 `NUMERIC(19,2)`이라 `"10000000"`으로 보낸 값이 `"10000000.00"`으로 돌아온다. 값은 같고 표기 자릿수만 컬럼 scale로 정규화된다.

예시 파일의 `summary.benchmark`는 `null`이다. 메시지 계약 v2(spec 004 T001)에서 벤치마크가 빠졌으므로 워커는 벤치마크를 계산하지 않는다(§4).

`summary.benchmark`는 결과에 벤치마크가 있을 때만 있다. `config.benchmarkId`(요청값)와 다를 수 있으며,
**현재는 벤치마크 계산기가 없어 항상 `null`이다**(§4 참고).

`error.code`는 엔진이 준 값(`INVALID_REQUEST` `NO_MARKET_DATA` `STRATEGY_ERROR` `ENGINE_ERROR` `INTERNAL_ERROR`)이며
HTTP 오류 코드와 다른 집합이다.

### 2.2 시계열 — `GET /backtests/{backtestId}/timeseries`

거래일마다 1개, 해당 거래일 Close 기준이다. `cash`·`positionValue`·`dailyReturn`·`positions`는 내려주지 않는다.

`benchmarkReturn`은 벤치마크가 없거나 그 날짜 값이 없으면 **키 자체를 생략한다**(`null`이 아니다).

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

### 2.4 보유 구성 — `GET /backtests/{backtestId}/positions?date=`

`date`는 필수다(`YYYY-MM-DD`). 보유 구성이 바뀐 거래일 + 시작일 + 종료일에만 스냅샷이 있으므로,
**요청일 이하에서 스냅샷이 있는 가장 최근 날짜**를 돌려주고 그 날짜를 `snapshotDate`에 담는다.

- `snapshotDate < requestedDate`가 정상이다. 보유 종목·수량은 정확하지만 `marketValue`·`weight`·`unrealizedPnl`
  평가액은 **`snapshotDate`의 Close 기준**이므로 화면에 그 날짜를 표시한다.
- `positions: []`는 "그날 보유 없음이 확정"이고 "스냅샷 없음"과 다르다.
- 요청일 이전에 스냅샷이 하나도 없으면(백테스트 시작일보다 앞) 400이다.

### 2.5 하위 리소스의 준비 상태

`/timeseries`·`/trades`·`/positions`는 결과가 없으면 데이터를 만들어 내지 않고 409를 준다.
**빈 배열로 응답하지 않는다** — 실패한 실행에 `{"points":[]}`를 주면 "돌았고 자산 곡선이 비어 있었다"는 거짓이 된다.

| Job 상태 | HTTP | `error.code` | 프론트 동작 |
|---|---|---|---|
| `QUEUED`·`RUNNING` | 409 | `RESULT_NOT_READY` | 폴링 계속 |
| `FAILED` | 409 | `BACKTEST_FAILED` | **폴링 중단.** `GET /backtests/{id}`의 `error` 표시 |
| `COMPLETED` | 200 | — | 그린다 |

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
