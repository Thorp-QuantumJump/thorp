# Quickstart: 백테스트 실행 검증 시나리오

spec의 User Story·SC를 끝에서 끝까지 확인하는 절차다. 구현 코드는 tasks.md와 구현 단계에 있다. 계약은 [contracts/](contracts/), 테이블은 [data-model.md](data-model.md)를 본다.

## 준비

```bash
docker compose -f docker-compose.dev.yml up -d postgres rabbitmq
# quorum 큐 전환(기존 classic 큐가 있는 환경에서 최초 1회). 백엔드·워커를 멈춘 뒤
# messages_ready·messages_unacknowledged 가 모두 0인지 확인하고 삭제한다(미ACK만 보면 대기 작업을 잃는다)
docker compose -f docker-compose.dev.yml exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
docker compose -f docker-compose.dev.yml exec rabbitmq rabbitmqctl delete_queue backtest.jobs
cd backend && ./gradlew spotlessApply build && ./gradlew bootRun --args='--spring.profiles.active=local'
cd worker && ruff check src tests && ruff format --check src tests && pytest && python -m worker.main
```

인증 토큰 `$TOKEN`(사용자 A), `$TOKEN_B`(사용자 B)를 준비한다. 요청 본문 예시는 `docs/specs/backtest-api/examples/backtest-request.json`.

## S1. 접수·상태·완료 (US1, SC-001)

```bash
curl -s -X POST localhost:8080/backtests -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d @req.json
```

기대: 즉시 `202`, `status=QUEUED`, `resultSource=NEW`, `effectiveStart`가 요청 시작일보다 뒤(워밍업). 이어서 `GET /backtests/{id}`를 몇 초 간격으로 조회하면 `progress.stage`가 `PREPARING_DATA → COMPUTING`으로, `lastTradingDate`가 증가하며 `COMPLETED`에서 `summary`·`versions`가 채워진다. `versions.costProfile.provisional=true`가 보인다.

## S2. 동일 버전 재사용 (US3-1, FR-003)

S1 완료 뒤 같은 본문을 사용자 B 토큰으로 다시 보낸다. 기대: `202`, `status=COMPLETED`, `resultSource=REUSED`, 새 `backtestId`. `backtest_run` 행 수는 늘지 않고 `backtest_reuse_event`에 `REUSED` 1건.

## S3. 진행 중 합류 (Clarify Q3)

긴 기간 요청을 보낸 직후(RUNNING 중) 같은 본문을 사용자 B로 보낸다. 기대: `resultSource=JOINED`, 두 backtestId의 `GET`이 같은 `progress`를 보이고 완료 시 같은 `summary`. run은 하나.

## S4. 취소와 부분 결과 (US3-4, SC-006)

S3 상태에서 사용자 A가 `POST /backtests/{A}/cancel`. 기대: A는 `CANCELLED`, B는 계속 `RUNNING`(마지막 요청이 아니므로 계산 유지). 이어 B도 취소하면 다음 진행 배치 안에 워커가 `FINISHED(CANCELLED)`를 보내고 B는 `CANCELLED, partial=true, progress.lastTradingDate`가 채워지며 `/timeseries`가 그 날짜까지의 데이터를 `200`으로 돌려준다. 이후 같은 본문 재요청은 `resultSource=NEW`(취소 결과는 재사용 안 함).

합류 취소(Clarify Q3·R-04): A·B가 같은 실행에 합류한 상태에서 A만 취소한다. 기대: A는 즉시 `CANCELLED`·`partial=true`, `progress.lastTradingDate`가 취소 시점 값으로 고정, B는 계속 `RUNNING`. B 실행 중 A `/timeseries`는 `409 RESULT_NOT_READY`. B 완료 뒤 A를 다시 조회해도 완료 범위가 그대로고 `summary`는 null(`summaryUnavailable: CANCELLED_WHILE_RUN_CONTINUED`), `/timeseries`는 그 날짜까지만 온다. B는 `COMPLETED` 전체 결과. 변형 1: A 취소 뒤 B도 취소하면 run은 B의 정지 날짜(A 스냅샷보다 뒤)에서 멈추고 B만 부분 요약을 보며 A는 그대로다. 변형 2: A·B가 아직 대기 중일 때 A를 취소하면 A의 완료 범위는 null이고, B가 완료된 뒤에도 A `/timeseries`는 `409 NO_PARTIAL_RESULT`다. 변형 3: 워커를 모두 내린 뒤 마지막 요청을 취소하면 `cancel-grace-seconds` 뒤 run이 `FAILED(SYSTEM_FAILURE)`가 되고 결과 행이 없으며 요청은 `CANCELLED`·`partial=false`·`summaryUnavailable: CANCEL_UNANSWERED`다.

## S5. 실패와 재시도 (US3-5, FR-010)

존재하지 않는 거래일 구간이 생기도록 픽스처에서 한 종목의 일부 행을 지운 자료 버전으로 요청한다. 기대: `FAILED`, `failure.code=DATA_INSUFFICIENT`, `summary=null`(0으로 채우지 않음). `POST /backtests/{id}/retry` → `202` 새 id, `retryOf` 원 id. 자료를 복구한 새 `data_version`으로 요청하면 다른 키의 새 실행.

## S6. 시스템 장애 자동 재시도 1회 (Clarify Q5)

RUNNING 중 워커 프로세스를 `kill -9`한다. 기대: 브로커 재전달로 다른 워커(또는 재시작한 워커)가 다시 실행, `GET`에 상태 변화 없이 `attempt=2`가 DB에 기록되고 완료된다. 두 번째 실행 중 다시 `kill -9`하면 메시지가 DLQ로 가고 백엔드가 `FAILED, failure.code=SYSTEM_FAILURE`로 종결한다. `backtest_run_metric.auto_retried=true`.

## S7. 결정성 (SC-002, FR-004)

pytest `test_determinism.py`: 같은 EngineInput 픽스처를 두 번 실행해 EngineOutput JSON이 바이트 단위로 같다. 통합: S1 결과와 강제 재계산(테스트용 내부 플래그 또는 다른 `data_version` 라벨로 같은 데이터) 결과의 `summary`·`/trades`·`/weights`가 완전히 같다.

## S8. 수기 대조 (US2, SC-003, SC-004)

`worker/examples/three_etf_24m/`(3개 ETF, 24개월, 분배금 2건, 거래 정지 1일 포함) 픽스처로 `FIXED_WEIGHT`·`EQUAL_WEIGHT`·`INVERSE_VOLATILITY`를 실행한다. pytest `test_manual_reconciliation.py`가 `fixtures/manual_reconciliation.json`(스프레드시트로 계산한 대표 거래일 3개의 체결일·수량·현금·비용·분배금)과 정확히 일치하는지 확인한다. 확인 항목: 모든 체결일 = 판단일 다음 거래일, 정수 수량, 거래 정지일 체결이 다음 거래 가능일로 이월되고 `reason=HALT_DEFERRED`, 분배금이 `cashFlows`에 한 번만, 결과 시작일 = 워밍업 이후.

## S9. 미확정 규칙 거부 (FR-010, PD-03·04)

`strategyId=SECTOR_MOMENTUM`으로 요청. 기대: `422 RULES_NOT_REGISTERED`, 실행 생성 없음. `MINIMUM_VARIANCE`도 같다. 팀이 규칙을 등록하면 이 시나리오는 실행 시나리오로 바뀐다.

## S10. 측정 기록 (US4, SC-007)

S1~S6을 마친 뒤 `GET /internal/execution-metrics?type=SINGLE`(운영자 토큰). 기대: `count ≥ 6`, `failRate`·`cancelRate`·`autoRetryRate`가 0보다 크고, `waitP50Ms ≤ waitP95Ms`, `reuseCount ≥ 1`. `psql -c 'select * from v_run_metric_summary'`와 값이 같다.

## S11. 목표 위험 맞춤 시나리오 (FR-016, PD-18)

S1과 같은 본문에 `"scenario": "TARGET_RISK", "scenarioParams": {"targetVolatility": "0.0500"}`만 바꿔 요청한다. 기대: 별도 실행(새 `resultSource=NEW`, 동일 버전 키가 S1과 다름), `/weights`의 각 리밸런싱 항목에 `estimatedVolatility`·`riskScale`이 있고, 추정 변동성이 5%를 넘는 달은 `riskScale < 1`·현금 비중 증가, 넘지 않는 달은 `riskScale = 1`. `targetVolatility: "0.2500"`(범위 밖)은 `400 INVALID_INPUT`, `scenario=FULL_PERIOD`에 `scenarioParams`를 넣으면 `400`. 픽스처 3종목·24개월에서 세 전략 모두 실행되고 결과 `versions.rules`에 `TARGET_RISK_OVERLAY/1`이 기록된다.

## S12. 실행 한도 장치 (FR-006, R-19, T014)

local 임시값(`max-queued-per-user=2`)으로 같은 사용자가 서로 다른 조건 3건을 **동시에** 요청한다(경합 확인). 기대: 세 번째가 `429 EXECUTION_LIMIT`, 본문에 `queued=2`·`retryAfterSeconds`, 응답에 `provisional=true`. 같은 조건 재요청(JOINED)은 429가 아니다. `max-run-seconds=5`로 낮춰 긴 구간을 요청하면 실행이 CANCEL 채널로 멈추고 `CANCELLED partial=true`·`failure.code=EXECUTION_LIMIT`·완료 범위가 보인다. `backtest_run_metric.limit_hit`에 두 건이 남는다. **값은 임시이며 PD-06이 정하기 전까지 prod에 적용하지 않는다.**

## 완료 판정

S1~S12가 모두 기대대로 나오면 "구현 완료"다. 사용성 확인·효과 입증은 별도 보고한다(constitution 완료의 정의).
