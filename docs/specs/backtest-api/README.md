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

## 2. 상태 조회

```
GET /backtests/{backtestId}
```

```json
{ "success": true, "data": { "backtestId": "BT-20260908-000001", "status": "RUNNING" }, "error": null }
```

상태 흐름은 `QUEUED → RUNNING → COMPLETED | FAILED`다(ADR-0002 D1). `RUNNING` 이후는 Worker의 결과 이벤트로
갱신된다. 결과 지표(수익률·MDD·Sharpe·거래 내역) 필드는 결과 조회 작업에서 이 응답에 더한다.

## 3. 오류 응답

모든 오류는 공통 포맷을 쓴다.

```json
{ "success": false, "data": null, "error": { "code": "INVALID_INPUT", "message": "..." } }
```

| HTTP | `error.code` | 언제 |
|---|---|---|
| 400 | `INVALID_INPUT` | 필수 필드 누락, 종목 형식·개수 위반, 소수 자릿수 초과, `maxPositionPct` 범위 위반, `start > end`, `parameters` 값에 `null` |
| 404 | `RESOURCE_NOT_FOUND` | 없는 `backtestId` 조회 |
| 503 | `BROKER_UNAVAILABLE` | 작업 큐에 연결 실패. Job은 저장되지 않으므로 **그대로 재시도해도 안전하다** |
| 500 | `INTERNAL_ERROR` | 그 외 |

400의 `message`는 첫 번째 위반 필드만 담는다(`portfolio.initialCash: ...` 형태). 사용자에게 그대로 보여주기보다 필드
위치를 잡는 용도로 쓰는 편이 낫다.

## 4. 프론트가 알아야 할 것

- `backtestId`는 숫자가 아니라 문자열이다. 경로 변수도 문자열로 넣는다.
- 같은 조건으로 다시 요청하면 **항상 새 Job**이 만들어진다(새 `backtestId`). 입력이 같아도 결과를 재사용하지 않는다.
- 요청 구조는 설계 `BacktestRequest`와 같은 5블록(run / universe / strategy / portfolio / benchmark)이다.
- 종목은 계약상 50개까지 보낼 수 있지만, 현재 엔진은 1종목만 처리한다. 2개 이상 보내면 Job은 접수되고 실행 단계에서
  `FAILED`가 된다. MVP 화면은 1종목만 보낸다.
