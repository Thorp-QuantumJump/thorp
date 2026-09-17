# Quickstart: 백테스트 실행 검증 시나리오

spec의 User Story·SC를 끝에서 끝까지 확인하는 절차다. 구현 코드는 tasks.md와 구현 단계에 있다. 계약은 [contracts/](contracts/), 테이블은 [data-model.md](data-model.md)를 본다.

## 준비

```bash
# 0. 로컬 볼륨을 비운다. local 프로필은 Flyway 가 꺼져 있어 Hibernate 가 만든 스키마에는 001 자료 표(daily_price 등)가
#    없다 — 운영과 같은 스키마로 검증하려면 Flyway 로 새로 만든다. 로컬 자료만 사라진다.
docker compose -f docker-compose.dev.yml down -v
docker compose -f docker-compose.dev.yml up -d postgres rabbitmq

# 1. 백엔드를 Flyway 켜서 한 번 띄운다 — 스키마(001 표 포함)와 RabbitMQ 토폴로지(exchange 3, queue 5, binding 5)를 만든다.
cd backend && ./gradlew spotlessApply build
SPRING_FLYWAY_ENABLED=true SPRING_JPA_HIBERNATE_DDL_AUTO=validate ./gradlew bootRun --args='--spring.profiles.active=local'

# 2. 픽스처 자료를 001 표에 넣는다(로컬 전용, 멱등). 백엔드가 접수 때 붙이는 자료 버전이 상수 FIXTURE-three_etf_24m 이라
#    이 자료로만 S1 이 돈다 — 진짜 KRX 자료는 001 T019(자료 버전 규칙)와 함께 붙인다. 운영 DB 에는 넣지 않는다.
cd worker && ruff check src tests scripts && ruff format --check src tests scripts && pytest
MARKET_DATA_URL=postgresql://thorp:thorp@localhost:5432/thorp python scripts/load_three_etf_fixture.py

# 3. 워커 둘 — 단건 전용과 벌크 우선 공용(R-20). 각각 별도 터미널.
MARKET_DATA_URL=postgresql://thorp:thorp@localhost:5432/thorp WORKER_ROLE=single python -m worker.main
MARKET_DATA_URL=postgresql://thorp:thorp@localhost:5432/thorp WORKER_ROLE=flex   python -m worker.main
```

전부 컨테이너로 띄우려면 `docker compose -f docker-compose.dev.yml --profile app up -d --build` — 이때도 백엔드에
`SPRING_FLYWAY_ENABLED=true`를 넘겨야 001 표가 생긴다(dev compose 기본은 Hibernate `update`).

**요청자는 로그인 세션으로 식별한다**(spec 014, T017). 아래에서 사용자 A는 `-b a.jar`, 사용자 B는 `-b b.jar`로 읽는다.
상태를 바꾸는 요청(POST·DELETE)에는 쿠키 단지의 CSRF 토큰을 헤더로 함께 보낸다. 운영자 경로는 세션이 아니라
`X-Ops-Token: dev-ops-token`이다(014가 운영자 역할을 정하면 바뀐다).

```bash
csrf() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$1"; }
login() {  # login <쿠키파일> <이메일> <비밀번호> — 계정은 회원가입(spec 014)으로 미리 만든다
  curl -s -c "$1" localhost:8080/auth/csrf > /dev/null
  curl -s -b "$1" -c "$1" -X POST localhost:8080/auth/sessions -H 'Content-Type: application/json'     -H "X-XSRF-TOKEN: $(csrf "$1")" -d "{\"email\": \"$2\", \"password\": \"$3\"}"
}
login a.jar user-a@example.com 'Password!1'
login b.jar user-b@example.com 'Password!1'
```

요청 본문 예시는 `docs/specs/backtest-api/examples/backtest-request.json` — 종목은 픽스처의 세 개
(`091160`·`305720`·`244580.XKRX`), 대응표 버전 `SECTOR-ETF-2026-09`, 기간은 2023-01-02~2024-12-31 안이어야 한다.

## S1. 접수·상태·완료 (US1, SC-001)

```bash
curl -s -X POST localhost:8080/backtests -b a.jar -H "X-XSRF-TOKEN: $(csrf a.jar)"   -H 'Content-Type: application/json' -d @req.json
```

기대: 즉시 `202`, `status=QUEUED`, `resultSource=NEW`, `effectiveStart`는 요청 시작일(휴장일이면 그 뒤 첫 거래일). **워밍업만큼 밀리지 않는다** — 미는 판정은 워커가 한다(R-15). 이어서 `GET /backtests/{id}`를 몇 초 간격으로 조회하면 `progress.stage`가 `PREPARING_DATA → COMPUTING`으로, `lastTradingDate`가 증가하며 `COMPLETED`에서 `summary`·`versions`가 채워진다. `versions.costProfile.provisional=true`가 보인다.

## S2. 동일 버전 재사용 (US3-1, FR-003)

S1 완료 뒤 같은 본문을 사용자 B(`-b b.jar`)로 다시 보낸다. 기대: `202`, `status=COMPLETED`, `resultSource=REUSED`, 새 `backtestId`. `backtest_run` 행 수는 늘지 않고 `backtest_reuse_event`에 `REUSED` 1건.

## S3. 진행 중 합류 (Clarify Q3)

긴 기간 요청을 보낸 직후(RUNNING 중) 같은 본문을 사용자 B로 보낸다. 기대: `resultSource=JOINED`, 두 backtestId의 `GET`이 같은 `progress`를 보이고 완료 시 같은 `summary`. run은 하나.

## S4. 취소와 부분 결과 (US3-4, SC-006)

S3 상태에서 사용자 A가 `POST /backtests/{A}/cancel`. 기대: A는 `CANCELLED`, B는 계속 `RUNNING`(마지막 요청이 아니므로 계산 유지). 이어 B도 취소하면 다음 진행 배치 안에 워커가 `FINISHED(CANCELLED)`를 보내고 B는 `CANCELLED, partial=true, progress.lastTradingDate`가 채워지며 `/timeseries`가 그 날짜까지의 데이터를 `200`으로 돌려준다. 이후 같은 본문 재요청은 `resultSource=NEW`(취소 결과는 재사용 안 함).

합류 취소(Clarify Q3·R-04): A·B가 같은 실행에 합류한 상태에서 A만 취소한다. 기대: A는 즉시 `CANCELLED`·`partial=true`, `progress.lastTradingDate`가 취소 시점 값으로 고정, B는 계속 `RUNNING`. B 실행 중 A `/timeseries`는 `409 RESULT_NOT_READY`. B 완료 뒤 A를 다시 조회해도 완료 범위가 그대로고 `summary`는 null(`summaryUnavailable: CANCELLED_WHILE_RUN_CONTINUED`), `/timeseries`는 그 날짜까지만 온다. B는 `COMPLETED` 전체 결과. 변형 1: A 취소 뒤 B도 취소하면 run은 B의 정지 날짜(A 스냅샷보다 뒤)에서 멈추고 B만 부분 요약을 보며 A는 그대로다. 변형 2: A·B가 아직 대기 중일 때 A를 취소하면 A의 완료 범위는 null이고, B가 완료된 뒤에도 A `/timeseries`는 `409 NO_PARTIAL_RESULT`다. 변형 3: 워커를 모두 내린 뒤 마지막 요청을 취소하면 `cancel-grace-seconds` 뒤 run이 `FAILED(SYSTEM_FAILURE)`가 되고 결과 행이 없으며 요청은 `CANCELLED`·`partial=false`·`summaryUnavailable: CANCEL_UNANSWERED`다.

## S5. 실패와 재시도 (US3-5, FR-010)

존재하지 않는 거래일 구간이 생기도록 픽스처에서 한 종목의 일부 행을 지운 자료 버전으로 요청한다. 기대: `FAILED`, `failure.code=DATA_INSUFFICIENT`, `summary=null`(0으로 채우지 않음). `POST /backtests/{id}/retry` → `202` 새 id, `retryOf` 원 id. 자료를 복구한 새 `data_version`으로 요청하면 다른 키의 새 실행.

> 로컬(2026-09-15): 백엔드 자료 버전이 상수라 "행을 지운 **다른** 자료 버전"을 만들 수 없다. 대신 `daily_price`의 불변 트리거를 잠시 끄고 한 종목 5행을 지워 요청(→ `DATA_INSUFFICIENT`), 임시 표에서 되돌린 뒤 `/retry`(→ 같은 키의 새 실행이 `COMPLETED`)로 앞 두 문장을 확인한다. 마지막 문장(자료 버전 갈아타기)은 어댑터가 001 표를 읽게 된 뒤 확인한다.

## S6. 시스템 장애 자동 재시도 1회 (Clarify Q5)

RUNNING 중 워커 프로세스를 `kill -9`한다. 기대: 브로커 재전달로 다른 워커(또는 재시작한 워커)가 다시 실행, `GET`에 상태 변화 없이 `attempt=2`가 DB에 기록되고 완료된다. 두 번째 실행 중 다시 `kill -9`하면 메시지가 DLQ로 가고 백엔드가 `FAILED, failure.code=SYSTEM_FAILURE`로 종결한다. `backtest_run_metric.auto_retried=true`.

## S7. 결정성 (SC-002, FR-004)

pytest `test_determinism.py`: 같은 EngineInput 픽스처를 두 번 실행해 EngineOutput JSON이 바이트 단위로 같다.
**발표 전에는 같은 개발 환경에서의 재현만 보장한다**(T022). scipy를 정확한 버전으로 고정하고 모든 배분 비중을
0.000001 단위로 떨어뜨려 마지막 자리 차이를 줄였지만, 다른 OS·CPU·이미지에서의 바이트 동일은 워커 이미지 잠금과
`versions.dependencies` 기록(Phase 10 T027·T028)이 들어간 뒤에 말할 수 있다. 통합: S1 결과와 강제 재계산(테스트용 내부 플래그 또는 다른 `data_version` 라벨로 같은 데이터) 결과의 `summary`·`/trades`·`/weights`가 완전히 같다.

## S8. 수기 대조 (US2, SC-003, SC-004)

`worker/examples/three_etf_24m/`(3개 ETF, 24개월, 분배금 2건, 거래 정지 1일 포함) 픽스처로 `FIXED_WEIGHT`·`EQUAL_WEIGHT`·`INVERSE_VOLATILITY`를 실행한다. pytest `test_manual_reconciliation.py`가 `fixtures/manual_reconciliation.json`(스프레드시트로 계산한 대표 거래일 3개의 체결일·수량·현금·비용·분배금)과 정확히 일치하는지 확인한다. 확인 항목: 모든 체결일 = 판단일 다음 거래일, 정수 수량, 거래 정지일 체결이 다음 거래 가능일로 이월되고 `reason=HALT_DEFERRED`, 분배금이 `cashFlows`에 한 번만, 결과 시작일 = 워밍업 이후.

## S9. 미확정 규칙 거부와 최소분산 실행 (FR-010·FR-025, PD-03·04)

`strategyId=SECTOR_MOMENTUM`으로 요청. 기대: `422 RULES_NOT_REGISTERED`, 실행 생성 없음. T023이 규칙을 등록하면 이 갈래도 실행 시나리오가 된다.

`MINIMUM_VARIANCE`는 PD-04가 확정돼 **실행 시나리오다**(T022). S1과 같은 본문에 전략만 바꿔 요청하면 `COMPLETED`이고
`/weights`의 `weightsSource=COMPUTED`, 각 항목의 비중이 비음수·상한 이하이며 합이 투자 목표와 같다(AC5).
`strategy.parameters.maxWeight`(정수 %)를 함께 보내면 그 값이 유효 상한이다 — **상한 40%에 종목 2개면 채울 수 있는 최대가
80%라 접수가 `422 VALIDATION_RULE_VIOLATED`로 거절한다.** 종목 3개면 통과하고, 그중 이력이 모자란 종목이 있는 달에는
유효 투자 목표가 줄어 나머지가 현금으로 남고 `exceptions`에 `EFFECTIVE_TARGET_REDUCED`가 기록된다.

## S10. 측정 기록 (US4, SC-007)

S1~S6을 마친 뒤 `GET /internal/execution-metrics?type=SINGLE`에 헤더 `X-Ops-Token: {thorp.ops.token}`(local은 `dev-ops-token`)을 붙여 부른다. 기대: `count ≥ 6`, `failRate`·`cancelRate`·`autoRetryRate`가 0보다 크고, `waitP50Ms ≤ waitP95Ms ≤ waitP99Ms`, `reuseCount ≥ 1`, S13을 거쳤으면 `flexServedRate > 0`. `psql -c 'select * from v_run_metric_summary'`와 값이 같다(`from`·`to`를 주지 않았을 때). 헤더가 없거나 틀리면 `404 RESOURCE_NOT_FOUND` — 토큰이 설정되지 않은 환경에서도 같다. S12를 거쳤으면 `backtest_limit_hit`에 `ADMISSION_REJECTED`(429)와 `RUN_STOPPED`(실행 시간 상한) 한 건씩, 응답 `limitHitCount ≥ 2`.

## S11. 목표 위험 맞춤 시나리오 (FR-016, PD-18)

S1과 같은 본문에 `"scenario": "TARGET_RISK", "scenarioParams": {"targetVolatility": "0.0500"}`만 바꿔 요청한다. 기대: 별도 실행(새 `resultSource=NEW`, 동일 버전 키가 S1과 다름), `/weights`의 각 리밸런싱 항목에 `estimatedVolatility`·`riskScale`이 있고, 추정 변동성이 5%를 넘는 달은 `riskScale < 1`·현금 비중 증가, 넘지 않는 달은 `riskScale = 1`. `targetVolatility: "0.2500"`(범위 밖)은 `400 INVALID_INPUT`, `scenario=FULL_PERIOD`에 `scenarioParams`를 넣으면 `400`. 픽스처 3종목·24개월에서 세 전략 모두 실행되고 결과 `versions.rules`에 `TARGET_RISK_OVERLAY/1`이 기록된다.

## S12. 실행 한도 장치 (FR-006, R-19, T014)

local 기본값은 `max-queued-per-user=5`(R-19)이므로 이 시나리오에서만 `2`로 낮춰 짧게 확인한다. 픽스처 실행은 2~4초에 끝나므로 실행 시간 상한은
`1`초, 스위퍼 주기도 `PT1S`로 내려야 걸린다 — 백엔드를 `THORP_BACKTEST_LIMITS_MAX_QUEUED_PER_USER=2 THORP_BACKTEST_LIMITS_MAX_RUN_SECONDS=1
THORP_BACKTEST_LIVENESS_INTERVAL=PT1S`로 재기동한다. 대기 상한을 보려면 워커를 잠시 멈춰 요청이 `QUEUED`로 쌓이게 한다(워커가 있으면 100ms 안에
`RUNNING`이 되어 세어지지 않는다). 같은 사용자가 서로 다른 조건 3건을 **동시에** 요청한다(경합 확인). **단건(`SINGLE`)만 세므로** 탐색 묶음은 이 상한에 걸리지 않는다. 기대: 세 번째가 `429 EXECUTION_LIMIT`, 본문에 `queued=2`·`retryAfterSeconds`, 응답에 `provisional=true`. 같은 조건 재요청(JOINED)은 429가 아니다. `max-run-seconds=5`로 낮춰 긴 구간을 요청하면 실행이 CANCEL 채널로 멈추고 `CANCELLED partial=true`·`failure.code=EXECUTION_LIMIT`·완료 범위가 보인다. `backtest_limit_hit`에 `ADMISSION_REJECTED`·`RUN_STOPPED` 한 건씩 남는다(T012). **값은 임시이며 PD-06이 정하기 전까지 prod에 적용하지 않는다.**

## S13. 단건·벌크 큐 분리 (R-20, T015)

dev compose는 `worker`(single, `WORKER_ROLE=single`) 1개 + `worker-flex`(`WORKER_ROLE=flex`) 1개다. 007 묶음은 `POST /param-searches/preview`로
`registryVersion`을 받은 뒤 `POST /param-searches`에 되돌려 보낸다. 픽스처(24개월)에서는 `INVERSE_VOLATILITY`의 `lookbackDays`를 `[21, 63]`으로 좁혀야
`INSUFFICIENT_HISTORY`를 피하고, `FIXED_WEIGHT`는 목표 비중이 없어 탐색에 넣을 수 없다. **2026-09-15 기준 007 후보는 워커 계약 위반으로 DLQ에 간다**
(검증 기록 발견 2) — 큐 분리·단건 즉시 처리는 그와 무관하게 확인된다. 007로 탐색 묶음을 하나 시작한 뒤 `rabbitmqctl list_queues name messages_ready messages_unacknowledged`를 본다. 기대: 후보는 `backtest.jobs.bulk`에 쌓이고(`dispatch-window`=4 → ready 3·unacked 1) `backtest.jobs`는 0. 그 상태에서 단건을 요청하면 **single 워커가 즉시** 집어 RUNNING이 된다 — 벌크가 끝나기를 기다리지 않는다. 묶음이 끝나 bulk가 비면 flex 워커 로그에 `role=flex queue=backtest.jobs`가 찍히며 단건을 집는다(최대 1초 뒤). `max-queued-total=1`로 낮추고 bulk에 후보가 쌓인 상태에서 단건 첫 건을 요청하면 **429가 아니다** — 전체 한도는 `backtest.jobs`의 단건만 센다. 두 번째 단건은 `429 TOTAL`. `backtest.jobs.dlq`는 하나뿐이고 어느 큐에서 죽은 메시지든 T010 리스너가 `runId`·`publishAttempt`로 집계한다.

## 완료 판정

S1~S13이 모두 기대대로 나오면 "구현 완료"다. 사용성 확인·효과 입증은 별도 보고한다(constitution 완료의 정의).
