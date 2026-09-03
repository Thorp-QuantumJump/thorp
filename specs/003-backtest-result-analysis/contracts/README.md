# Contracts: 백테스트 결과 조회 API

**Plan**: [../plan.md](../plan.md) | **Date**: 2026-09-03

프론트(FE)·워커(Worker)와 공유하는 계약이다. 변경 시 헌장 원칙 IV(생산자·소비자 호환 유지, 단계적 변경)를 따른다.

| 파일 | 대상 | 내용 |
|---|---|---|
| [backtests-api.md](./backtests-api.md) | FE ↔ BE | 엔드포인트 계약 전체. US1·US2는 이번 구현, US3·US4는 예약(응답 형태만 정의) |
| [backtest-create-request.schema.json](./backtest-create-request.schema.json) | FE → BE | `POST /backtests` 요청 본문 |
| [backtest-detail.schema.json](./backtest-detail.schema.json) | BE → FE | `GET /backtests/{id}` 및 `POST /backtests` 응답의 `data` |
| [backtest-timeseries.schema.json](./backtest-timeseries.schema.json) | BE → FE | `GET /backtests/{id}/timeseries` 응답의 `data` |
| [examples/](./examples/) | FE | 실제 응답 예시(200·202·404·409) |
| [backtest-result-table.md](./backtest-result-table.md) | Worker → DB ← BE | `backtest_result` 테이블 저장 계약 |

## 공통 규칙

- 모든 응답은 `ApiResponse` 래퍼: `{ "success": boolean, "data": T | null, "error": { "code", "message" } | null }`
- JSON 필드는 camelCase
- 비율은 number(`0.125` = 12.5%), `maxDrawdown`·`drawdown`은 0 또는 음수, 금액은 KRW 정수(JSON integer), 횟수는 integer
- 지표 필드의 `null`은 **"계산 불가"** 다. 0이나 빈 값으로 대체하지 않는다. 이유 필드는 US4에서 추가한다
- 날짜는 ISO-8601 `YYYY-MM-DD`
- 벤치마크 관련 필드는 없다(spec 003 FR-015). 프론트 차트의 벤치마크 선은 프론트에서 제거한다
- 상태 값: `QUEUED | RUNNING | COMPLETED | FAILED`
- 에러 코드(`error.code`): `INVALID_INPUT`(400), `RESOURCE_NOT_FOUND`(404), `RESULT_NOT_READY`(409, 신설), `INTERNAL_ERROR`(500)

## 프론트 담당자에게 전달할 변경점

1. `GET /backtests/{id}` 응답에 `config`와 `summary`가 추가된다. `summary`는 COMPLETED일 때만 값이 있다
2. 차트 데이터는 `GET /backtests/{id}/timeseries`에서 받는다. 전략 3개 비교 화면은 이 API를 전략 수만큼 호출한다
3. 지표는 문자열(`'+124.8%'`)이 아니라 number(`1.248`)로 온다. 포맷팅은 프론트가 한다
4. dev 시더의 `maxDrawdown`은 시계열에서 도출한 값이라 목데이터의 `mdd`와 다르다(trend −0.1261, dualMomentum −0.1171, rsiMeanReversion −0.0795)
5. `winRate`·`profitFactor`는 시더 데이터에서 `null`(계산 불가)이다. "계산 불가"로 표시해야 한다
6. `POST /backtests`에 `initialCash`(선택, 기본 10,000,000)를 보낼 수 있다
7. `summary`에 최대 낙폭 구간 3필드(`maxDrawdownPeakDate`, `maxDrawdownTroughDate`, `maxDrawdownRecoveryDate`)가 있다. 회복일이 `null`이면 "미회복", 세 필드가 모두 `null`이면 "낙폭 없음"으로 표시한다
