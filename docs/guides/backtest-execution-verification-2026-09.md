# 백테스트 실행 검증 기록 (spec 004, 2026-09-15)

`specs/004-backtest-execution/quickstart.md` S1~S13을 **실제 프로세스**(백엔드 · RabbitMQ · 워커 2 · PostgreSQL)로 끝까지 돈 기록이다.
T001~T015가 dev에 병합된 시점(dev `1adef09` 위, 브랜치 `docs(be)/154-spec-004-wrap-up`)의 T013 산출물이다.

> **이 문서가 말하는 것**: 구현이 계약대로 동작한다는 것 — "구현 완료". **말하지 않는 것**: 사용자가 쓸 만한가(사용성), 결과가 투자 판단에
> 유효한가(효과). 그 둘은 별도 보고 대상이다(constitution 완료의 정의). 자료는 합성 픽스처라 어떤 수익률 숫자도 시장에 대한 진술이 아니다.

## 환경

| 항목 | 값 |
|---|---|
| 실행 PC | Windows 11, Docker Desktop(PostgreSQL 16 · RabbitMQ 3.13 컨테이너), 백엔드·워커는 호스트 프로세스 |
| 백엔드 | `SPRING_FLYWAY_ENABLED=true SPRING_JPA_HIBERNATE_DDL_AUTO=validate ./gradlew bootRun --args='--spring.profiles.active=local'` — Flyway 16개 마이그레이션 적용, 표 40개, 큐 5개(quorum 2) 선언 |
| 워커 | `WORKER_ROLE=single` 1 + `WORKER_ROLE=flex` 1, `MARKET_DATA_URL` → 로컬 PostgreSQL |
| 자료 | `worker/scripts/load_three_etf_fixture.py` — `FIXTURE-three_etf_24m`(3종목·2023-01-02~2024-12-31·주말만 뺀 평일 522일·분배금 2·분할 1·정지 1), `daily_price` 1,436행 · `daily_index` 522행 · `corporate_action` 3행. 두 번째 실행은 "already present" |
| 사용자 | `users` 2행(id 1·2), 헤더 `X-User-Id` + spec 014 CSRF(`GET /auth/csrf` → 쿠키 `XSRF-TOKEN` → 헤더 `X-XSRF-TOKEN`) |
| S12 전용 | 백엔드를 `THORP_BACKTEST_LIMITS_MAX_QUEUED_PER_USER=2 THORP_BACKTEST_LIMITS_MAX_RUN_SECONDS=1 THORP_BACKTEST_LIVENESS_INTERVAL=PT1S`로 재기동 |

로컬 볼륨은 `docker compose down -v`로 비운 뒤 시작했다 — local 프로필의 Hibernate 스키마에는 001 자료 표가 없다.

## 결과 요약

| S | 시나리오 | 결과 | 핵심 관측 |
|---|---|---|---|
| S1 | 접수·상태·완료 | **통과** | 202 `QUEUED` → `RUNNING` → `COMPLETED` **5.9초**(대기 0.28초, 실행 4.2초, 392거래일). `summary.totalReturn=0.16298…`(**의미 변경 이전 값** — 2026-09-17 T022가 `INVERSE_VOLATILITY/1`을 제자리에서 갱신해 상한 잔여를 현금으로 두고 비중을 0.000001 단위로 떨어뜨린다. 같은 요청의 수치가 달라진다), `versions.costProfile.provisional=true`, `effectiveStart=2023-07-03`(BIO 상장일로 당겨짐) |
| S2 | 동일 버전 재사용 | **통과** | 사용자 2, 같은 본문 → 202 `COMPLETED` `REUSED`, 새 `backtestId`. `backtest_run` 1행 그대로, `backtest_reuse_event` `REUSED:SINGLE` 1 |
| S3 | 진행 중 합류 | **통과** | A 접수 직후 B 같은 본문 → B는 `RUNNING` `JOINED`, 둘 다 `run_id=3`. `reuse_event` `JOINED` 1 |
| S4 | 취소와 부분 결과 | **통과** | PROGRESS(2023-10-26) 뒤 취소 → 202 `CANCELLED cancelRequested=true` → 워커가 다음 거래일 처리 뒤 멈춤 → `CANCELLED partial=true lastTradingDate=2024-05-10`, `/timeseries` 200(그 날짜까지) |
| S5 | 실패와 재시도 | **통과(자료 버전 갈아타기는 미검증)** | 한 종목(305720)의 2024-03-04~08 가격 5행을 지운 상태로 요청 → 0.3초 만에 `FAILED DATA_INSUFFICIENT "305720.XKRX is missing 5 trading day(s): 2024-03-04, …"`, `summary=null`, `progress.stage=PREPARING_DATA`. 5행을 되돌린 뒤 `POST /retry` → 202 `retryOf=BT-000026` 새 id → **새 실행(run 22, 같은 동일 버전 키)** 이 3.5초 만에 `COMPLETED`(`totalReturn 0.0993`). 실패한 실행은 재사용되지 않았다. quickstart 마지막 문장 "복구된 새 `data_version`으로 요청하면 다른 키의 새 실행"은 **확인하지 못했다** — 백엔드가 붙이는 자료 버전이 상수라 다른 버전을 가리키는 요청을 만들 수 없다(「한계」). 첫 시도는 `scenario=COST_ESCALATION`(워커 미지원)으로 `FAILED INVALID_REQUEST` → retry → 같은 사유로 FAILED 였고, 그건 "실패 후 재접수"만 보인 것이라 원래 시나리오로 다시 돌렸다 |
| S6 | 시스템 장애 자동 재시도 1회 | **통과** | 실행 중 single 워커 프로세스 강제 종료 → 브로커 재전달 → **flex 워커가 4초 안에 완료**, `attempt=2 publish_count=1 dlq_count=0`, 측정 `auto_retried=true worker_role=flex`(FINISHED 값 우선) |
| S7 | 결정성 | **통과** | `worker.demo`로 같은 입력을 두 번 → `performance`·`portfolioSeries`·`trades` 완전 동일. 다른 것은 `run.startedAt`·`finishedAt`뿐 (`tests/fixtures/engine-input-three-etf-{fixed,inverse_vol}.json`) |
| S8 | 수기 대조 | **통과(자동)** | `pytest tests/test_manual_reconciliation.py tests/test_determinism.py` 11 passed — 스프레드시트식 독립 계산(`reconcile.py`)과 엔진 출력이 `PROVISIONAL_TOLERANCE=0`에서 일치 |
| S9 | 미확정 규칙 거부 | **실패 → 수정 → 통과** | 첫 실행 **500 INTERNAL_ERROR**(아래 발견 1). 수정 뒤 `422 RULES_NOT_REGISTERED "SECTOR_MOMENTUM/PENDING — 등록되지 않음"` |
| S10 | 측정 기록 | **통과** | `GET /internal/execution-metrics`(X-Ops-Token) = 뷰 `v_run_metric_summary`. SINGLE 16건: fail 0.125·cancel 0.125·autoRetry 0.0625, wait p50 40ms/p95 15,440ms/p99 16,238ms(S12에서 워커를 멈춘 동안의 대기가 들어감), run p50 1,677ms/p95 4,211ms, `flexServedRate` 0.0625, `reuseCount` 4, `limitHitCount` 2. 토큰 없음·틀림 → 404 |
| S11 | 목표 위험 맞춤 | **통과** | `TARGET_RISK 0.0500` → 별도 실행(`effectiveStart=2023-09-28`, 63거래일 워밍업), `/weights` 항목마다 `riskScale`(0.383…)·`estimatedVolatility`(0.1304…). `0.2500` → 400, `FULL_PERIOD`+`scenarioParams` → 400 |
| S12 | 실행 한도 장치 | **통과** | (a) 워커 정지 상태에서 같은 사용자 3건째 → **429 PER_USER** `queued=2 limit=2 retryAfterSeconds=2 provisional=true`, 같은 본문 재요청은 `JOINED`로 429 아님, `backtest_limit_hit ADMISSION_REJECTED`. (b) max-run 1초 → 스위퍼가 CANCEL → `CANCELLED partial=true failure.code=EXECUTION_LIMIT` 261/392거래일, `backtest_limit_hit RUN_STOPPED`, 측정 `CANCELLED/EXECUTION_LIMIT` |
| S13 | 단건·벌크 큐 분리 | **부분 통과 — 007 결함 발견** | 탐색 묶음 후보 3건이 **`backtest.jobs.bulk`로 가고 flex만 집었다**(flex 로그 bulk 3·single 0). 그 동안 들어온 단건은 **433ms 만에 RUNNING**(single 워커). 그러나 후보 3건 모두 **계약 위반으로 DLQ → FAILED SYSTEM_FAILURE**(아래 발견 2). DLQ 경로·집계(`SEARCH_BUNDLE 3, failRate 1.0, worker_role null`)는 설계대로 |

전체 끝난 뒤 DB: `backtest_run` COMPLETED 13 · FAILED 6 · CANCELLED 2, `backtest_request` NEW 21 · REUSED 2 · JOINED 3(S5 재실행 2건 포함).

## 발견

### 1. [수정함] 팀 결정 대기 전략 거절이 422가 아니라 500 (S9)

- **증상**: `strategyId=SECTOR_MOMENTUM rulesVersion=PENDING` 접수 → `500 INTERNAL_ERROR`. 로그: `Application exception overridden by commit exception … UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only`.
- **원인**: T014가 `BacktestService.create`에 `noRollbackFor = BusinessException`을 걸었는데, 그 안에서 부르는 `StrategyRulesService.requireRegistered`·`CostProfileService.resolve`의 `@Transactional(readOnly)` 프록시는 기본 규칙이라 거절을 던지며 **바깥 트랜잭션을 rollback-only로 표시**했다. `create`가 커밋하려는 순간 예외. 단위 테스트는 목이라 프록시가 없어 보이지 않았고, 429 경로만 IT로 고정돼 있었다.
- **수정**: 두 메서드에 `noRollbackFor = BusinessException.class`(`81e1ea2`). `ExecutionLimitApiIT.pendingRulesRejectionIsA422NotASilentRollback`이 실제 프록시 경로로 고정.
- **교훈**: `noRollbackFor`는 **호출 사슬 전체**가 같은 뜻이어야 한다. 접수 트랜잭션 안에서 `BusinessException`을 던지는 `@Transactional` 메서드를 새로 만들면 같은 설정을 붙인다(`backend/CLAUDE.md`에 규칙으로 추가).

### 2. [007 담당자에게 전달] 자동 탐색 후보가 워커 계약을 위반해 전부 DLQ (S13)

- **증상**: `POST /param-searches`(EQUAL_WEIGHT + INVERSE_VOLATILITY lookbackDays [21, 63]) → 후보 3건 접수·발행은 정상이나 워커가 `contract violation; rejecting to DLQ`:
  `allocation.rebalance Field required` / `allocation.lookbackDays Extra inputs are not permitted`.
- **원인**: 007이 만드는 `allocation.parameters`가 `{"lookbackDays": 63}`(EQUAL_WEIGHT는 `{}`)이다. 004 계약(`worker/src/worker/contracts/strategy_params`)에서 세 실행 가능 전략의 파라미터는 `{"rebalance": "MONTH_END"}` 하나뿐이고, `lookbackDays`는 파라미터가 아니라 **규칙 등록표**(`strategy_rules INVERSE_VOLATILITY/1 {lookbackDays: 63}`)의 값이다. 003 파라미터 등록표가 `INVERSE_VOLATILITY`에 `lookbackDays` 축을 두는 것과 004 규칙 모델이 어긋난다 — 스펙 사이의 정합 문제이며 004 코드로 고칠 일이 아니다.
- **004 쪽 동작은 설계대로**: 계약 위반 → requeue 없이 DLQ → `dlq_count >= publish_count` → `FAILED SYSTEM_FAILURE` → 007에 `BacktestFinished` → 묶음 `COMPLETED counts.failed=3`. 측정 행은 `worker_role null`(STARTED가 없었으니 맞다).
- **후속**: 007·003 담당자에게 구두 전달. 두 갈래 — (a) 007이 `rebalance`를 넣고 `lookbackDays`를 규칙 버전 선택으로 바꾼다, (b) 004가 `INVERSE_VOLATILITY` 파라미터에 `lookbackDays`를 허용하고 규칙의 값을 덮어쓰게 한다(동일 버전 키·`rules` 사본과의 관계를 다시 정해야 한다). 어느 쪽이든 spec 결정이라 티켓은 담당자가 만든다.
- **첫 시도의 다른 거절 둘**은 정상 동작이다: `lookbackDays 252`는 픽스처 24개월로 워밍업을 못 채워 `422 INSUFFICIENT_HISTORY`; `FIXED_WEIGHT`를 탐색에 넣으면 목표 비중이 없어 `400 INVALID_INPUT`.

### 3. [기록] 작은 것들

- [수정함, 리뷰 지적] 픽스처 적재기 초판이 이미 있는 `sector`·`representative_etf` 행을 `ON CONFLICT DO UPDATE`로 덮어썼다. 두 표는 버전을 타지 않는 공용 표라 이름을 바꾸면 발행된 대응표가 가리키는 내용이 바뀐다(001은 `SectorMasterConflictError`로 막는다). 지금은 같으면 기존 id 재사용, 다르면 `MasterConflictError`로 멈춘다. 로컬 DB에서 세 경로(같음·`date`↔문자열 정규화·다름 → 예외, 원래 값 유지)를 실제로 확인했다.
- `peakRssP95Mb`가 null이다 — 워커 표본은 `/proc/self/statm`(Linux)만 읽고 이 검증은 Windows 호스트에서 돌렸다. 운영(Linux 컨테이너)에서는 채워진다. 컨테이너로 돌린 검증은 T013 뒤 별도.
- S12(b) `failure.message`가 null이다 — `CANCELLED`의 `failure.code`만 채우고 문장은 없다. 화면이 코드로 문장을 만들면 되지만, 계약이 `message`를 약속하는지 API README를 다시 볼 것.
- quickstart 초판의 `Authorization: Bearer $TOKEN`은 이 저장소에 없다 — spec 014의 세션·CSRF 경계는 있고 요청자 식별은 `X-User-Id`(개발용). 준비 절을 고쳤다.
- **[2026-09-16 갱신]** 위 표와 이 줄의 `X-User-Id` 경로는 T017(`S15P21E205-287`)에서 없어졌다 — 요청자는 로그인 세션(`POST /auth/sessions` → `THORP_SESSION` 쿠키)에서 읽는다. 이 문서는 2026-09-15 실행 기록이라 당시 절차를 그대로 남긴다. 다시 검증할 때는 004 quickstart 준비 절의 `login` 함수를 쓴다.
- 두 워커가 같은 호스트에서 돌면 `S6`에서 어느 쪽이 먼저 집는지는 push(single)가 항상 이긴다 — flex는 1초 폴링이라 단건을 "돕는" 것은 single이 바쁠 때만이다. 설계대로다(R-20).

## 한계 — 이 기록이 보증하지 않는 것

- **진짜 시장 자료로는 돌지 않는다.** 백엔드가 접수 때 붙이는 자료 버전이 상수 `FIXTURE-three_etf_24m`(`FixtureMarketMetadataAdapter`)이다. 001이 KRX 자료를 다른 `dataset_id`로 넣어도 워커는 그 이름의 `dataset_version`을 찾는다. 001 T019(자료 버전 규칙)와 함께 백엔드 어댑터를 001 표에서 읽게 바꾸는 후속이 필요하다. 같은 이유로 **자료 버전이 바뀌면 동일 버전 키가 달라져 새 실행이 된다**(S5 마지막 문장, R-03)는 것은 이 기록이 보증하지 않는다 — 키 계산에 `data_version`이 들어가는 것은 `DedupKeyCalculatorTest`가 지키고, 실제 갈아타기는 어댑터가 바뀐 뒤 다시 돈다.
- **S5의 자료 구멍은 불변 트리거를 잠시 끄고 만들었다.** 001 자료 표는 UPDATE·DELETE를 트리거가 거부한다(발행된 자료는 고치지 않는다, FR-003). 로컬에서 "행이 빠진 자료 버전"을 만들 다른 길이 없어 `ALTER TABLE daily_price DISABLE TRIGGER …` → DELETE 5행 → ENABLE, 검증 뒤 임시 표에서 INSERT로 되돌렸다. 운영에서는 하지 않는 조작이고, 되돌린 뒤 행 수(522)를 확인했다.
- 성능 숫자(대기 40ms, 실행 1.7~4.2초)는 **합성 3종목·24개월·호스트 1대**의 값이다. PD-06 한도 값의 근거는 운영 자료·운영 배치에서 다시 재야 한다.
- 컨테이너(`--profile app`) 배치로는 돌리지 않았다. 호스트 프로세스 배치와 다른 것은 네트워크 이름과 RSS 표본뿐이라고 보지만 확인한 것은 아니다.

## 재현 명령

quickstart.md 「준비」 그대로. 시나리오 본문은 `docs/specs/backtest-api/examples/backtest-request.json`을 기준으로 `conditions.start`만 바꿔 동일 버전 키를 갈랐다(같은 본문은 재사용·합류가 되므로). S6은 `Get-CimInstance Win32_Process`로 워커 PID를 찾아 `taskkill //F`, S12는 위 환경변수로 백엔드 재기동 뒤 워커를 잠시 멈춰 두 건을 QUEUED로 쌓았다.
