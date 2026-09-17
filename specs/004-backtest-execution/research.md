# Research: 백테스트 실행 (Phase 0)

**Date**: 2026-09-10 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

Technical Context의 미확정 항목과 설계 선택을 결정으로 정리한다. 팀 결정 항목(PD-03·04·06·07)은 값을 정하지 않고 "값이 들어올 자리"만 정한다. 형식: Decision / Rationale / Alternatives.

## R-01 기준 엔진과 전략 구현 방식

- **Decision**: NautilusTrader 1.219.0을 유지한다. 다섯 전략을 Nautilus `Strategy` 하나(`PortfolioRebalanceExecution`)로 실행하고, 목표 비중 계산은 `worker/src/worker/allocation/` 아래 순수 함수(`compute_weights(history, params, rules) -> AllocationResult`)로 분리한다. 리밸런싱 판단일 bar-close에 목표 비중을 받아 정수 수량 주문을 만들고, 체결은 다음 거래일 시가 QuoteTick(현행 방식)로 한다.
- **Rationale**: constitution "계산·데이터 규칙"이 Nautilus를 기준 엔진으로 정했다. 체결·현금·수수료 회계를 Nautilus에 맡기고 배분 규칙만 순수 함수로 두면 수기 대조(SC-003)와 규칙 버전 교체(PD-03·04)가 쉽다.
- **Alternatives**: pandas 벡터화 엔진(빠르지만 constitution 위반, 별도 엔진은 Nautilus와 대조 후에만 허용) / 전략마다 Nautilus Strategy 분리(체결 코드 5중 복제).

## R-02 분석 요청과 실행의 분리

- **Decision**: `backtest_request`(사용자 식별자 `BT-…`, 요청자, 취소 시각, 결과 출처)와 `backtest_run`(동일 버전 키, 상태, 진행, 결과)을 다대일로 둔다. 기존 `backtest_job`은 V3 마이그레이션에서 `backtest_run`으로 개명·확장하고 기존 행마다 request 1개를 생성한다. `backtest_result`·`backtest_trade`의 FK는 run을 가리킨다. 동일 버전 중복 방지는 부분 유니크 인덱스 `UNIQUE (dedup_key) WHERE status IN ('QUEUED','RUNNING','COMPLETED')`로 한다.
- **Rationale**: Clarify Q2·Q3. 완료 결과만 재사용하고 실패·취소 실행은 같은 키로 새 실행을 허용해야 하므로 전체 유니크가 아니라 부분 유니크가 맞다.
- **Alternatives**: 단일 테이블 self-reference(plan Complexity Tracking 참고) / 애플리케이션 락으로 중복 방지(경합 시 두 실행이 생김).

## R-03 동일 버전 키

- **Decision**: FR-003 항목을 canonical JSON(키 사전순 정렬, 소수는 plain string, null 키 제거)으로 직렬화해 SHA-256 hex 64자를 `dedup_key`로 저장한다. 포함 항목: strategyId, rulesVersion, parameters, sector→ETF 대응(대응표 버전 + 정렬된 (sectorId, instrumentId, targetWeight) 목록), **warmupTradingDays(서버가 확정한 실효 워밍업 거래일 수, R-15)**, dataVersion, evaluationSplitVersion, costProfileVersion, engineVersion(`nautilus_trader` 버전 + 워커 엔진 버전 문자열. 워커 이미지 digest·의존성 fingerprint를 여기에 넣는 것은 발표 후 Phase 10 T028), scenario·scenarioParams·시나리오 규칙 버전, 분석 기간·시작 금액. 요청자·executionType은 제외한다. 워밍업이 키에 없으면 같은 전략을 다른 공통 워밍업으로 돌린 두 실행이 같은 키로 묶여 잘못된 결과를 재사용한다(plan 리뷰 6번).
- **Rationale**: constitution "실행·캐시" 캐시 키 항목과 일치. Jackson `ORDER_MAP_ENTRIES_BY_KEYS` + 기존 `messageObjectMapper`(BigDecimal plain string)로 구현 가능해 새 라이브러리가 필요 없다.
- **Alternatives**: DB에서 컬럼 조합 유니크(JSONB 파라미터 비교 불안정) / UUIDv5(결국 canonical 문자열이 필요).

## R-04 취소 채널

- **Decision**: fanout exchange `backtest.control`을 추가한다. 워커는 시작 시 exclusive·auto-delete 큐를 바인딩하고 별도 스레드에서 `{type: CANCEL, runId}`를 받아 `cancel_requested` 집합에 넣는다. 전략은 `on_bar`(거래일마다 호출)에서 집합을 확인해 자기 run이면 즉시 정지하고, 소비자는 `FINISHED(status=CANCELLED, partial=true, lastTradingDate)`와 부분 EngineOutput을 발행한 뒤 ACK한다. **합류 요청 하나만 취소한 경우**(남은 활성 요청이 있어 run은 계속 도는 경우) 백엔드는 그 요청에만 `cancelled_last_trading_date = run.progress_last_trading_date`를 스냅샷으로 남긴다. **마지막 활성 요청의 취소(run을 실제로 멈추는 취소)는 스냅샷을 남기지 않는다** — 백엔드가 아는 날짜는 마지막 PROGRESS 배치까지인데 워커는 그 뒤 몇 거래일을 더 계산한 다음 `on_bar`에서 멈추므로, 그 요청의 범위는 `FINISHED(CANCELLED).lastTradingDate`가 run에 기록된 값이다(2차 리뷰 3번). 요청에 `stopped_run BOOLEAN`을 둔다: 이 취소가 마지막 활성 요청의 취소여서 run을 멈췄으면 true. 완료 범위는 `stopped_run`이면 run의 날짜(FINISHED가 채움), 아니면 `cancelled_last_trading_date`이며 **NULL은 "완료한 거래일 없음"**(대기 중·자료 준비 중 등 첫 PROGRESS 전 취소)이다. NULL을 run 날짜로 대체하지 않는다 — 대체하면 나중에 B가 계산한 범위가 A의 것으로 보인다(3차 리뷰 2번). 완료한 거래일이 없는 취소 요청의 하위 리소스는 run이 끝난 뒤에도 `409 NO_PARTIAL_RESULT`(재시도 무의미)다. 스냅샷이 있는 요청(먼저 취소한 요청)의 조회는 상태 `CANCELLED`, `partial=true`, 완료 범위 = 스냅샷 날짜이며, 시계열·거래·보유·비중·예외는 그 날짜까지 **읽기 필터**로 잘라 주고 요약 지표는 주지 않는다(`summary=null` — 백엔드가 지표를 다시 계산하지 않는다, 원칙 1). run이 나중에 어떻게 끝나든(COMPLETED, 다른 요청의 취소로 더 긴 범위의 CANCELLED, FAILED) 이 요청의 표시는 바뀌지 않는다. A가 먼저 취소하고(스냅샷 d1) B가 마지막으로 취소해 run이 d2 ≥ d1에서 멈추면 A는 d1·요약 없음, B는 d2·부분 요약이다. **공용 run이 끝나기 전에는 취소된 합류 요청의 상세 결과(시계열·거래 등)가 저장돼 있지 않으므로** 하위 리소스는 `409 RESULT_NOT_READY`를 돌려주고, run이 `FAILED`로 끝나면 부분 결과를 끝내 확보할 수 없어 `409 BACKTEST_FAILED`다(2차 리뷰 2번). 상태 조회(`GET /backtests/{id}`)는 스냅샷만으로 답하므로 항상 200이다. 실행 중에도 거래·비중을 저장하는 대안은 PROGRESS에 상세를 싣거나 워커가 중간 결과를 발행해야 해서 버렸다 — spec Edge Case는 합류 취소에 즉시 부분 결과를 약속하지 않는다. **취소 무응답(3차 리뷰 3번)**: CANCEL을 보낸 뒤 `cancel-grace-seconds`(R-19) 안에 FINISHED가 오지 않으면 실행 주체(워커)가 없는 것이므로 run을 `FAILED(failure_code=SYSTEM_FAILURE, message="cancel unanswered", partial=false)`로 강제 종결하고 결과 행을 만들지 않는다. 협력 취소(`CANCELLED partial=true` + 부분 결과)와 구분한다. 취소를 누른 요청은 `CANCELLED`로 보이되 `partial=false`·`summary=null`·`summaryUnavailable: CANCEL_UNANSWERED`, 하위 리소스는 `409 BACKTEST_FAILED`. 강제 종결 뒤 늦게 도착한 FINISHED는 종결 상태 규칙으로 무시한다. 대기 중 취소는 백엔드가 run을 CANCELLED로 바꾸는 것으로 끝내고, 그 run의 STARTED가 나중에 도착하면 백엔드가 CANCEL 제어 메시지를 보낸다.
- **Rationale**: Clarify Q1·Q3. 취소 확인 지점을 거래일 단위로 두면 완료 범위가 "처리한 마지막 거래일"과 정확히 일치한다(Q4). 큐 재정렬이나 메시지 삭제는 RabbitMQ에서 불가능하므로 소비 시점 확인이 유일한 실용적 방법이다.
- **Alternatives**: 상태만 바꾸고 워커는 끝까지 계산(자원 낭비, 부분 결과 시점 불명) / 워커가 DB 플래그 폴링(워커 DB 접근 금지 계약 위반).

## R-05 진행 정도 저장과 조회

- **Decision**: 백엔드가 PROGRESS 이벤트(20거래일 배치, 현행)를 받아 `backtest_run.progress_stage / progress_last_trading_date / progress_completed_days / progress_total_days`에 저장한다. 조회는 기존 `GET /backtests/{id}` 폴링으로 한다. 단계 값은 `PREPARING_DATA`(STARTED 수신) → `COMPUTING`(첫 PROGRESS) → `SAVING`(FINISHED 처리 중) → 종결. SSE는 이번 범위에서 만들지 않고 `LoggingBacktestProgressNotifier`는 삭제한다.
- **Rationale**: Clarify Q4. 20거래일 배치 저장은 실행당 수백 번 UPDATE 이하라 부담이 없고, 폴링 API가 이미 있다. SSE는 사용성 관찰에서 필요가 확인되면 추가한다.
- **Alternatives**: SSE 즉시 도입(프론트 변경 필요, 005 범위) / 진행 미저장(다른 화면에서 돌아왔을 때 SC-001 위반).

## R-06 시스템 장애 1회 자동 재시도

- **Decision**: `backtest.jobs`를 quorum queue(`x-queue-type=quorum`, `x-delivery-limit=1`, 기존 DLX 유지)로 선언한다. 워커 프로세스가 죽어 unacked 메시지가 재전달되면 한 번만 재실행되고, 두 번째도 끊기면 브로커가 DLQ로 보낸다. 백엔드에 `backtest.jobs.dlq` 리스너를 추가해 `run.dlq_count`를 올리고, `dlq_count >= publish_count`(발행한 메시지가 모두 DLQ로 끝남)일 때 run을 `FAILED(failureCode=SYSTEM_FAILURE)`로 종결한다(R-18 중복 실행 규칙). STARTED 이벤트의 새 executionId를 받으면 `attempt`를 1 올리고, 측정 기록의 `autoRetried = attempt > publish_count`로 남긴다 — 재발행으로 생긴 중복 실행은 자동 재시도로 세지 않는다. 워커가 스스로 판정한 결정적 오류(INVALID_REQUEST·자료 부족·제약 위반)는 FINISHED(FAILED)로 응답하고 ACK하므로 재시도되지 않는다.
- **Rationale**: Clarify Q5. 브로커가 재시도 횟수를 세므로 워커·백엔드에 카운터 경합이 없다. 기존 DLX·DLQ 토폴로지를 그대로 쓴다.
- **Alternatives**: `redelivered` 플래그로 워커 판단(2회째와 3회째 구분 불가) / 백엔드가 STARTED 횟수로 CANCEL 발행(경합, 계산 낭비).
- **주의**: classic → quorum 전환은 인자 변경이라 기존 큐를 삭제해야 한다. 개발 환경은 `rabbitmqctl delete_queue backtest.jobs`(quickstart), 운영은 배포 절차에 넣는다. **선언 변경(T001)과 큐 전환은 같은 배포 단계다** — 전환 없이 v2 백엔드를 띄우면 재선언이 `PRECONDITION_FAILED`로 실패하고 큐는 classic으로 남는다. 삭제는 접수를 멈춘 상태에서 `messages_ready`·`messages_unacknowledged`가 모두 0일 때만 한다(미ACK만 확인하면 대기 작업을 잃는다).

## R-07 비용 조건 버전

- **Decision**: `cost_profile(version PK, commission_bps, spread_bps, slippage_bps, tax_bps, escalation JSONB, provisional BOOL, note)` 테이블을 둔다. PD-07 확정 전에는 현행 데모 값(수수료 ZERO, 슬리피지 5bps, 세금·호가차 0)을 `PROVISIONAL-DEMO-1`, `provisional=true`로 등록하고 요청이 버전을 지정하지 않으면 이 값을 쓴다. 결과 `versions.costProfile`에 버전과 provisional 여부를 담아 005가 "임시 비용" 한 문장을 표시할 수 있게 한다.
- **Rationale**: spec Assumptions("확정 전 임시값을 쓰고 임시임을 표시"). 값을 새로 정하지 않고 이미 코드에 있는 값을 재사용하므로 AI가 결정 항목을 확정하는 것이 아니다.
- **Alternatives**: 비용 미확정 시 실행 거부(모든 전략이 막혀 개발이 멈춤).

## R-08 전략 규칙 등록표와 미확정 규칙 처리

- **Decision**: `strategy_rules(strategy_id, rules_version, spec JSONB, status, provisional BOOL, decision_ref)` 테이블을 둔다. `FIXED_WEIGHT`, `EQUAL_WEIGHT`는 spec FR-021·022의 문장 그대로 `REGISTERED`로 넣는다. `INVERSE_VOLATILITY`는 FR-023의 관측 63거래일은 spec이 정한 값이지만, 0 변동성 처리(`EXCLUDE_THEN_ALLOCATE`)·비중 상한·투자 비중 합계는 spec Edge Case가 "PD-02·PD-03 계열 결정 항목"으로 둔 값이므로 `REGISTERED`이되 **`provisional=true`, `decision_ref=PD-02`**로 등록한다(R-07 비용 처리와 같은 방식). 결과 `versions.rules.provisional`로 노출해 005가 임시 규칙임을 표시한다. `SECTOR_MOMENTUM`(PD-03), `MINIMUM_VARIANCE`(PD-04)는 `PENDING_DECISION` 행만 두고 요청 시 백엔드가 422 `RULES_NOT_REGISTERED`로 거부한다. 확정되면 그 `PENDING_DECISION` 행(`/1`)을 `REGISTERED`로 바꾸고 `spec`을 채운다 — 이 행을 가리키는 실행은 없어(접수 거절) 재현이 깨지지 않는다(2026-09-17 PD-03·04 확정, T022·T023). `INVERSE_VOLATILITY/1`은 아래 임시 행 규칙대로 제자리에서 갱신한다(`capOverflow`·`weightUnit`, T022) — 보존할 결과가 없다는 전제(tasks Phase 9 범위 결정). 실행이 참조하는 확정 행을 바꿔야 하는 날부터는 새 버전 행 + `SUPERSEDED`와 신규 접수/저장된 실행의 검사 분리가 필요하며 그것은 발표 후 Phase 10 T029다. 화면용 조회(`GET /backtests/registry`, T025)는 전략당 한 행을 내려 준다. 워커의 `rules_registry.py`는 EngineInput에 담긴 규칙 명세만 신뢰하고 자체 기본값을 두지 않는다.
- **Rationale**: FR-010(낮은 수익으로 위장 금지)과 constitution "AI 작업 방식". 미확정 규칙을 임의 구현하면 나중에 결과 버전이 갈라진다. 역변동성의 0 변동성 규칙을 `provisional` 없이 등록하면 AI가 PD-02 계열 값을 확정한 것이 되므로(analyze C1) 임시 표시를 붙인다.
- **Alternatives**: 출발안을 임의로 코드에 넣기(금지) / 두 전략을 spec에서 제외(범위 축소는 팀 결정).
- **역변동성 0 변동성 규칙**: spec Edge Case의 예시 중 "그 섹터 제외 후 나머지 배분"을 임시 등록 값(`provisional=true`)으로 쓴다. PD-02가 확정되면 그 값으로 행을 갱신하고 `provisional=false`로 바꾼다.

## R-09 시장 자료 입력 경로

- **Decision**: 워커 `DataProvider`에 `PostgresMarketDataProvider`를 추가하고 [contracts/market-data-input.md](contracts/market-data-input.md)의 테이블·컬럼을 읽는다. 체결에는 원시 OHLC, 신호·성과에는 조정 종가, 분배금 이벤트는 지급일 현금 반영, 상장·폐지일로 가용 기간을 자른다. 001이 자료를 넣기 전까지는 같은 스키마의 CSV 픽스처(`worker/examples/`)로 테스트한다. 현행 yfinance 경로는 실행 경로에서 제거하고 개발 스크립트로만 남긴다.
- **Rationale**: 원칙 X(개발·검증 원천만 허용, 재배포 금지)와 001과의 경계. 워커의 `validate_prices()` fail-closed 검증은 그대로 재사용한다.
- **Alternatives**: 워커가 Parquet 직접 읽기(001이 저장 형식을 정하기 전이라 미정; provider 인터페이스로 열어둔다).

## R-10 실행 측정 기록과 원가

- **Decision**: 백엔드가 종결 이벤트마다 `backtest_run_metric`(run_id, execution_type, status, queued_at, started_at, finished_at, wait_ms, run_ms, peak_rss_mb, cpu_seconds, auto_retried, result_bytes)을 기록한다. 자원 사용량은 워커가 FINISHED payload의 `resource{peakRssMb, cpuSeconds, wallSeconds}`에 실어 보낸다(EngineOutput 밖 — 결정성 비교 대상에서 제외. T001은 cpu·wall 초만 채우고 peak RSS는 T012에서). 재조회는 `backtest_reuse_event(run_id, request_id, kind JOINED|REUSED, at)`로 따로 기록한다. 집계는 SQL 뷰 `v_run_metric_summary`(유형별 `percentile_cont(0.5/0.95)` 대기·소요, 실패율, 자동 재시도율)와 `GET /internal/execution-metrics`로 제공한다. 원가는 cpu_seconds·peak_rss_mb·result_bytes 원단위로만 기록하고 금액 환산 단가는 정하지 않는다.
- **Rationale**: 원칙 IX와 US4. 뷰 하나면 운영자가 볼 수 있고 대시보드는 필요가 확인되면 만든다.
- **Alternatives**: Micrometer 히스토그램(프로세스 재시작 시 소실, 실행 유형별 원가 기록 불가) / 별도 시계열 DB(미측정 병목에 인프라 추가).
- **구현 시 결정(T012, 2026-09-15)**: ① **기록은 종결 세 지점에서 직접 부른다** — `BacktestEventService.handleFinished`(FINISHED 셋)·`handleDeadLetter`(DLQ 종결)·`RunLivenessSweeper.settleOne`(취소 무응답 강제 종결). `BacktestFinished` 이벤트를 구독하는 방식은 버렸다: 그 이벤트는 007 을 위한 모듈 경계 신호라 FINISHED 의 `resource{peakRssMb, cpuSeconds}`가 실려 있지 않고, 측정을 위해 필드를 늘리면 받는 쪽마다 모르는 값이 생긴다. 세 지점 모두 실행 행을 잠근 트랜잭션 안이라 종결 UPDATE 와 측정 INSERT 가 함께 커밋되거나 함께 사라진다. `run_id UNIQUE + ON CONFLICT DO NOTHING` 으로 같은 실행을 두 번 세지 않는다 ② **한도 도달은 `backtest_limit_hit` 표를 따로 둔다** — 접수 거절(429)은 실행 행이 없어 측정 기록에 들어갈 자리가 없고, 실행 시간 상한 정지(스위퍼가 `EXECUTION_LIMIT` 으로 CANCEL 을 보낸 순간)는 실행이 아직 끝나지 않았다. 둘을 한 표에 `kind` 로 가른다 ③ **어느 큐·어느 워커가 집었는지는 STARTED 가 알려 준다** — 계약 v2 안에서 `resource`에 선택 필드 `queue`·`role` 을 더했다(하위호환, 없으면 null). 백엔드는 `backtest_run.served_queue`·`worker_role` 에 전용 UPDATE 로 쓰고 종결 때 측정 행으로 복사한다. flex 워커가 단건을 지원한 비율(R-20)은 이 값 없이는 셀 수 없다. **FINISHED 의 `resource`에도 `queue`·`role`을 싣고 측정은 그것을 먼저 쓴다**(1차 리뷰 3) — 중복 실행(R-18)에서는 마지막 STARTED 의 워커와 결과를 낸 워커가 다를 수 있고, STARTED 값은 FINISHED 가 없는 종결(DLQ·강제 종결·실행 전 취소)의 대체다. **`peakRssMb`는 프로세스 누적 최고값(`ru_maxrss`)이 아니라 이 실행 동안 STARTED·PROGRESS·FINISHED 시점에 표본으로 잰 최댓값이다**(1차 리뷰 2) — 워커가 실행을 순차 처리하므로 누적값이면 큰 실행 뒤의 작은 실행이 모두 그 값이 되어 유형 비교가 무의미해진다. **종결은 넷이 아니라 다섯 지점이다** — 실행 전 취소(`BacktestCancelService`, QUEUED → CANCELLED)도 직접 기록한다(1차 리뷰 1). 빠지면 늦은 FINISHED 는 최종 상태라 무시되어 그 실행은 측정에서 영영 빠진다 ④ **운영자 권한은 `X-Ops-Token` 헤더 ↔ `thorp.ops.token`** 이다(`CurrentRequester` 와 같은 임시 통로). 로그인(spec 014)이 없어 역할을 알 수 없고, 토큰이 설정되지 않은 환경에서는 경로가 없는 것처럼 404 를 돌려준다 — 운영에 값을 넣지 않으면 열리지 않는다. spec 014 가 들어오면 `OperatorGate` 하나만 바꾼다 ⑤ `result_bytes` 는 `backtest_result` 행의 `pg_column_size` 다 — JSONB 컬럼을 하나씩 더하는 것보다 실제 저장 크기(TOAST 압축 뒤)에 가깝고 한 질의다 ⑥ 집계는 `recorded_at` 범위를 받는 native 질의 하나로 하고 뷰는 같은 SELECT 의 범위 없는 판이다 — 뷰에는 인자를 줄 수 없고, 두 SQL 이 갈라지면 S10 의 "뷰와 값이 같다"가 깨지므로 질의 문자열을 한 상수에서 만든다.

## R-11 결과 산출 항목 확장

- **Decision**: EngineOutput에 `weightsSeries`(리밸런싱일별 목표·실제 비중, `weightsSource: USER|COMPUTED`), `costs`(commission·spread·slippage·tax·total), `liquidation`(markToMarket·liquidated 두 결과), `analytics`(recoveryDays·turnover·correlationMatrix·riskContribution·lossContribution·weightError), `exceptions[]`(시점·종목·규칙·처리), `versions{}`를 추가한다. 계산은 `performance.py`를 확장한다. 상관관계·위험 기여는 평가 기간 일별 수익률의 표본 공분산으로 계산하고 방법을 `analytics.method`에 적는다(PD-04 공분산 방법과는 별개 표기).
- **Rationale**: FR-017·027·029·030과 005가 그대로 표시할 항목 목록.
- **Alternatives**: 백엔드에서 파생 지표 계산(원칙 "Backend는 계산하지 않는다" 위반).

## R-12 요청자와 권한

- **Decision**: `backtest_request.requester_id`는 `users.id` FK로 필수다. 취소·재시도는 요청자 본인만 할 수 있고 타인 요청은 404로 숨긴다. 계산 기록(`backtest_run`·result)에는 요청자를 넣지 않는다.
- **Rationale**: Clarify Q2·Q3 뒤 남은 저영향 항목. 저장소에 `users`·`email_verifications`가 있어 인증이 전제다. 원칙 VIII "개인 식별정보는 계산 기록과 분리".
- **Alternatives**: 익명 요청 허용(취소 권한 판정 불가). 인증·006 spec에서 익명을 허용하기로 하면 컬럼을 nullable로 완화한다.

## R-13 API 규약 선택 (constitution "넣지 않은 영역")

- **Decision**: 기존 `ApiResponse` 봉투·202/200/409 패턴을 유지한다. 신규: `POST /backtests/{id}/cancel`(202, 종결 상태면 409 `NOT_CANCELLABLE`), `POST /backtests/{id}/retry`(202 새 backtestId, FAILED·CANCELLED에서만, 아니면 409 `NOT_RETRYABLE`), `GET /backtests/{id}/weights`. `POST /backtests`의 202 본문에 `resultSource: NEW|JOINED|REUSED`와 `effectiveStart`를 추가한다. 오류 코드 추가: `RULES_NOT_REGISTERED`(422), `INSUFFICIENT_HISTORY`(422), `EXECUTION_LIMIT`(429), `NOT_CANCELLABLE`/`NOT_RETRYABLE`(409). 상태 문자열은 `CANCELLED`.
- **Rationale**: 기존 계약과의 일관성. 세부는 [contracts/rest-api.md](contracts/rest-api.md).

## R-14 결정성 보장

- **Decision**: 시드 42 고정(현행), 종목·날짜 정렬 고정, numpy·numba·BLAS 단일 스레드(운영 compose에 이미 설정, 개발 compose에도 추가), 부동소수 누적 순서 고정. CI에 "같은 EngineInput 두 번 실행 → EngineOutput JSON 완전 일치" 테스트를 둔다.
- **Rationale**: SC-002. 재사용의 정당성은 재계산이 같다는 사실에서 온다.

## R-15 공통 워밍업과 실행 가능 기간

- **Decision**: 백엔드가 요청 시 001의 공통 평가 기간(가장 늦은 상장일 이후)과 **워밍업 거래일 수**를 합해 `effectiveStart`를 계산해 run에 고정하고 202 응답에 돌려준다. 워밍업 거래일 수 = 요청 `conditions.warmupTradingDays`가 있으면 그 값, 없으면 선택 전략의 관측 길이 + 최근 제외 구간. **요청값이 선택 전략의 요구값보다 작으면 조용히 올리지 않고 422 `INSUFFICIENT_HISTORY`(본문에 필요 값)로 거부한다** — 비교 묶음이 최대값을 잘못 계산했다는 신호이며, `max()`로 고쳐 통과시키면 묶음 안 실행들의 `effectiveStart`가 어긋난 채 지나간다(2차 리뷰 5번). FR-018의 "비교 후보 중 가장 긴 관측 기간"은 요청 하나만 보는 004가 알 수 없으므로, **비교 묶음을 아는 쪽(003의 다섯 전략 비교 화면, 007 자동 탐색)이 후보 전체의 최대값을 `warmupTradingDays`로 넘긴다**. 넘기지 않은 단독 요청은 자기 전략 값으로 계산한다. 경계 테스트(T005): 요청값 = 요구값 → 통과·그 값 사용, 작음 → 422, 큼 → 요청값 사용. 같은 시작일·같은 `warmupTradingDays`로 고정 비중과 역변동성을 요청하면 두 실행의 `effectiveStart`가 같다(T005 테스트로 고정). 확정한 워밍업은 동일 버전 키에 들어가고(R-03) 메시지 계약 `evaluation.warmupTradingDays`에 실려 워커가 같은 값을 쓴다. 가용 기간이 워밍업보다 짧으면 422 `INSUFFICIENT_HISTORY`. 상한 합계가 투자 비중을 채울 수 없는 설정은 422 `VALIDATION_RULE_VIOLATED`으로 실행 전에 거부한다(2026-09-17 PD-04 ④로 400 `INVALID_INPUT`에서 옮겼다. 종목 수는 요청의 투자 대상 섹터 수 기준이며, 실행 중 이력 부족으로 줄어든 경우는 거부가 아니라 현금 잔여다 — PD-03 ⑥).
- **Rationale**: FR-018, Edge Case, 원칙 IV "공통 평가 구간이 줄면 실행 전에 알린다". 날짜 산술은 계산이 아니라 검증이므로 백엔드에서 해도 원칙 1에 어긋나지 않는다.

## R-16 테스트·검증 기준 선택 (constitution "넣지 않은 영역")

- **Decision**: Task 완료 조건 2의 "테스트"는 backend `./gradlew spotlessApply build`, worker `ruff check src tests && ruff format --check src tests && pytest`로 한다. 계약 변경은 양쪽 픽스처 테스트가 같은 `examples/*.json`을 읽어 통과해야 한다. 수기 대조(SC-003)는 3개 ETF·24개월 CSV 픽스처에 대해 스프레드시트 계산 결과를 `worker/tests/fixtures/manual_reconciliation.json`으로 고정하고 pytest가 허용 오차(PD-12 확정 전 임시 0, 즉 정확 일치) 안에서 비교한다.
- **Rationale**: 현행 검증 명령을 그대로 쓴다. 허용 오차 0은 정수 수량·원 단위 회계에서 가능하고 PD-12가 정해지면 바꾼다.

## R-17 목표 위험 맞춤 시나리오 (PD-18, 2026-09-10 팀 확정)

- **Decision**: `scenario=TARGET_RISK`는 전략 위에 얹는 공통 오버레이로 구현한다. 요청 `conditions.scenarioParams.targetVolatility`(연환산, plain string, 기본 `"0.1000"`, 허용 0.05~0.20, 0.01 간격)를 받고, 규칙은 `strategy_rules`의 `TARGET_RISK_OVERLAY/1 {lookbackDays:63, adjustAt:"REBALANCE", scaleDownOnly:true}`(REGISTERED, provisional=false)로 버전 관리한다. 워커는 리밸런싱 판단일마다 최근 63거래일 포트폴리오 일별 수익률(전략 목표 비중 기준)의 표준편차 × √252로 변동성을 추정하고, 추정치 > 목표이면 투자 비중 합계에 `target/estimate`를 곱해 나머지를 현금으로 두며, 추정치 ≤ 목표이면 조절하지 않는다. 조절 비율·추정 변동성을 `weightsSeries[].riskScale`·`estimatedVolatility`에 기록한다. 이 값들은 동일 버전 키(R-03)에 포함된다(scenario·scenarioParams·규칙 버전).
- **Rationale**: 005 FR-017이 "별도 시나리오 보조 보기"로 요구한다. 레버리지 금지(FR-012)라 상향 조절은 불가능하므로 하향만 한다. 관측 63거래일·월말 조절은 역변동성(FR-023)·기본 리밸런싱(FR-011)과 같은 값을 써서 새 결정을 만들지 않는다. 목표 수준은 결과 의미를 바꾸는 사용자 선택이므로 003 등록표 항목(기본값 표기는 "예시·출발점")으로 둔다.
- **Alternatives**: 전략별로 다른 규칙(공통 오버레이면 충분, 비교 조건이 같아짐) / 004 범위 제외(005 보조 보기가 빈 화면이 됨) / 상향 조절 허용(레버리지 금지 위반).

## R-18 작업 발행 전달 보장 (plan 리뷰 1번, 2026-09-11)

- **Decision**: `BacktestService.create()`는 run+request를 커밋한 **뒤** 발행한다(`TransactionSynchronization.afterCommit` 또는 `@TransactionalEventListener(phase = AFTER_COMMIT)`). `backtest_run.published_at TIMESTAMPTZ NULL`을 두고 publisher confirm 뒤 채운다. 발행이 실패하면 run은 `QUEUED`·`published_at NULL`로 남고, 스케줄러 `JobRepublisher`(30초 주기)가 `status='QUEUED' AND published_at IS NULL AND created_at < now() - 1분`인 run을 재발행한다. 작업 메시지 `message_id = "JOB-" + runId`로 고정해 중복 발행이 식별 가능하고, 중복 실행이 생겨도 백엔드 상태 전이(FINISHED는 비최종 상태에서만 적용)가 두 번째 결과를 버린다. 202 응답은 커밋 시점에 돌려주며 발행 완료를 기다리지 않는다(현행 "202는 접수" 유지).
- **중복 실행 종결 규칙(2차 리뷰 1번)**: 재발행은 confirm 유실 등으로 같은 run의 메시지를 둘 만들 수 있고, 그러면 워커 두 개가 같은 결정적 입력을 동시에 돈다. 먼저 도착한 실패가 뒤에 올 정상 결과를 덮지 않도록 종결 이벤트를 다음 규칙으로 적용한다. ① `FINISHED(COMPLETED)`는 어느 executionId든 적용한다(입력이 같아 결과가 같다). ② `FINISHED(FAILED)`는 `event.executionId == run.current_execution_id`(마지막 STARTED)일 때만 적용하고 다른 실행의 실패는 기록만 한다. ③ DLQ 메시지에는 executionId가 없으므로 `run.dlq_count`를 올리고 `dlq_count >= publish_count`(발행한 메시지가 모두 DLQ로 끝남)일 때만 `FAILED(SYSTEM_FAILURE)`로 종결한다. **`publish_count`는 confirm 횟수가 아니라 발행 시도 횟수다**(시도 직전에 올린다). confirm이 유실된 첫 발행도 브로커에는 들어가 있을 수 있으므로 confirm 수를 메시지 수로 보면 과소평가돼 다른 실행이 살아 있는데 FAILED로 종결한다(3차 리뷰 1번). 시도 횟수는 실제 메시지 수의 **상한**이라 그 방향의 오류가 없고, 시도가 브로커에 닿지 못해 상한이 과대해진 run은 DLQ만으로 종결되지 않으므로 R-19의 정체 감지가 정리한다. ④ `FINISHED(CANCELLED)`는 먼저 도착한 것을 적용한다(둘 다 CANCEL을 받으므로 범위만 다르다). `publish_count`는 첫 발행과 `JobRepublisher`가 **발행 시도 직전**에 올리고, `published_at`만 confirm 뒤 채운다. 측정의 자동 재시도는 `attempt > publish_count`로 판정해 재발행 중복을 재시도로 세지 않는다. **수용한 제한(해결 아님)**: 마지막에 시작한 중복 실행이 비결정적 오류로 끝나고 먼저 시작한 실행이 나중에 성공하는 경우는 FAILED로 남고 그 성공은 버려진다. 중복이 없을 때 그 워커가 죽은 것과 같은 결과이며 입력이 결정적이라 재시도로 복구되므로 제품 정책으로 받아들인다. 완전히 닫으려면 실행별 진행 중 집합을 추적해야 해서 미룬다. 한 메시지가 DLQ도 FINISHED도 없이 사라지거나 시도 횟수가 과대하면 RUNNING에 머무는데, R-19의 정체 감지(PROGRESS 없음 → CANCEL → 유예 뒤 강제 종결)가 정리한다.
- **Rationale**: 현행 `BacktestService`는 트랜잭션 안에서 발행해 (a) 워커 STARTED/FINISHED가 커밋보다 먼저 도착하면 결과 Consumer가 모르는 runId로 버리고 (b) 발행 뒤 커밋 실패는 고아 메시지를 만든다(주석에 "outbox 도입 전까지의 한계"로 남아 있다). 커밋 후 발행이 (a)를 닫고, `published_at` 재발행이 (b)의 반대 경우(커밋 뒤 발행 실패)를 닫는다. run 행이 발행 대상과 1:1이므로 별도 outbox 테이블 없이 같은 보장이다.
- **Alternatives**: 트랜잭션 안 발행 유지(현행, 결과 유실) / 별도 outbox 테이블 + 릴레이(테이블·폴러 추가, 얻는 것이 같다) / 워커가 모르는 runId를 requeue(백엔드 지연을 워커가 감당, 계약 §7 위반).
- **검증(T005)**: ① 커밋 실패 시 발행이 일어나지 않는다(afterCommit 미호출) ② 발행 시각 ≥ 커밋 시각(IT, 워커가 커밋 전에 응답할 수 없음) ③ 발행 실패 → `published_at NULL` → 재발행기가 1회 재발행 → 성공 시 채움 ④ 중복 발행 → 둘 다 COMPLETED → 첫 번째 적용, 두 번째 무시, 결과 1건 ⑤ 중복 중 하나가 비current executionId로 FAILED → 무시·RUNNING 유지 → 다른 하나 COMPLETED 적용 ⑥ **confirm 유실 재현**: 첫 발행이 브로커에는 들어갔으나 confirm에서 예외(`publish_count=1`, `published_at NULL`) → 재발행(`publish_count=2`, confirm 성공) → 워커 둘 실행 → 하나만 DLQ(`dlq_count=1 < 2`) → RUNNING 유지 → 다른 실행 COMPLETED 적용; 둘 다 DLQ → FAILED(SYSTEM_FAILURE). 처음부터 `publish_count=2`를 놓는 테스트로 대체하지 않는다 ⑦ `autoRetried`: 중복 실행 2건은 false, 재전달(같은 메시지 두 번 STARTED)은 true.

## R-19 실행 한도 장치와 값의 분리 (plan 리뷰 4번, analyze H1, 2026-09-11)

- **Decision**: 한도 장치(T014, Jira S15P21E205-168)를 값(PD-06)과 분리해 먼저 만든다. 설정 `thorp.backtest.limits.{max-queued-per-user, max-queued-total, max-run-seconds, stale-run-seconds, cancel-grace-seconds}`. 접수 시 대기 상한 초과 → 429 `EXECUTION_LIMIT`(사유·현재 대기 수·`retryAfterSeconds`). 대기 수 검사는 접수 트랜잭션 안에서 `pg_advisory_xact_lock`(사용자 키·전체 키)로 직렬화해 동시 접수 사이의 경합을 막는다. **동시 실행 상한은 백엔드 설정에 두지 않는다**(2차 리뷰 4번) — 접수 시점에 실행 중인 작업이 없으면 여러 요청이 모두 통과하고 뒤에 워커가 동시에 소비하므로 접수 검사로는 보장이 안 된다. RabbitMQ competing consumers 구조에서 동시 실행 수는 워커 replica × prefetch(1)와 정확히 같으므로 그 배치 값이 상한이고 T002 배치 문서에 상한으로 명시한다. 실행 시작 시 백엔드가 슬롯을 예약하는 방식은 워커↔백엔드 동기화(워커 DB 접근 금지 계약과 충돌)가 필요해 버렸다. JOINED·REUSED는 새 계산이 아니라 한도에 세지 않는다. 실행 시간 상한 초과는 백엔드 스케줄러가 CANCEL 제어 메시지(R-04 채널)로 정지시키고 워커가 응답하면 run을 `CANCELLED(partial=true, failure_code=EXECUTION_LIMIT)`로 종결해 완료 범위를 표시한다(FR-005). **정체 감지·취소 유예(3차 리뷰 3번)**: 같은 스케줄러(`RunLivenessSweeper`)가 RUNNING인데 마지막 이벤트(STARTED·PROGRESS)로부터 `stale-run-seconds`가 지난 run에 CANCEL을 보내고, 어떤 이유의 CANCEL이든 뒤로 `cancel-grace-seconds` 안에 FINISHED가 없으면 실행 주체가 사라진 것으로 보아 `FAILED(partial=false)`로 강제 종결한다(failure_code는 원래 사유: 정체·사용자 취소 무응답 = SYSTEM_FAILURE, 실행 시간 초과 = EXECUTION_LIMIT). 결과 행은 만들지 않고 결과 없음을 표시한다 — 부분 결과가 없는데 부분 결과를 준다고 하지 않기 위해 CANCELLED가 아니라 FAILED다. R-18의 과대 `publish_count`로 RUNNING에 남은 run도 이 경로로 정리된다. 정상 PROGRESS는 `progress.batchTradingDays`(20거래일)마다 오므로 `stale-run-seconds`는 배치 하나 계산 시간의 수 배로 잡는다. 한도 도달은 `backtest_run_metric.limit_hit`(접수 거부·실행 정지 구분)로 기록해 PD-06 결정 자료로 쓴다. prod 프로필은 네 값이 필수(미설정 시 기동 실패), local은 임시값(사용자 5·전체 50·실행 1800초·정체 300초·유예 120초, 동시 실행은 dev compose 워커 replica 2)을 두고 `provisional=true`로 응답·결과에 표시한다.
- **Rationale**: FR-006 "상한을 정하기 전에 무제한 실행을 허용하지 않는다". plan 초판은 replica·prefetch만으로 PASS 처리했는데 그것은 동시 실행만 막고 대기 누적·실행 시간은 열려 있다. 값은 팀 결정이지만 장치가 없으면 값이 정해져도 적용할 곳이 없다. constitution "실행 전 결정 임의 확정 금지"는 값을 정하지 말라는 것이고 설정 자리를 만드는 것은 금지하지 않는다.
- **Alternatives**: PD-06 확정까지 아무 장치도 두지 않음(FR-006 위반 상태로 공개) / 값을 임의로 정해 하드코딩(금지) / RabbitMQ `x-max-length`로 큐 길이만 제한(사용자별 구분·실행 시간 제한 불가, 초과 메시지가 조용히 DLX로 간다).
- **대기열 한도가 세는 대상(2026-09-14 팀 결정, T014 구현 — 전체 한도는 2026-09-15 R-20으로 단건 큐만 세게 바뀌었다)**: `max-queued-per-user`는 **`SINGLE`만** 센다. 탐색 묶음의 적재량은 007의 `thorp.param-search.dispatch-window`(현재 4)가 이미 정확히 통제하므로 같은 큐를 두 곳에서 막지 않는다 — 두 곳에서 막으면 나중에 창을 키운 날 탐색이 접수에서 429를 받고, 007은 `advance()`가 `BacktestFinished`로만 불려 in-flight가 0이 되면 묶음이 영구 정지한다. `max-queued-total`은 반대로 종류를 가리지 않는다 — 그쪽은 사용자 사이의 공정성이 아니라 서버 보호다. `dispatch-window`를 바꿀 때는 `max-queued-total`도 함께 본다(창 × 동시 묶음 수가 전체 한도를 넘으면 탐색이 거절된다). 이 관계는 양쪽 설정 주석에 적어 두었다.
- **단일 FIFO의 공정성은 이번 범위가 아니다(2026-09-14 팀 결정 → 2026-09-15 번복, R-20. T014 리뷰에서 한 큐로는 어느 한도 정책도 성립하지 않음이 확인돼 실측을 기다리지 않고 큐를 나눴다)**: 큐가 하나뿐이라 탐색 여러 건이 겹치면 뒤에 온 단건이 그만큼 기다린다. 대기열 한도는 "쌓일 수 있는 총량"을 묶을 뿐 순서를 바꾸지 못한다. 업계 표준 해법은 **큐 분리**(대화형 `backtest.jobs` / 배치 `backtest.jobs.bulk`, 워커가 단건 큐를 먼저 소비)지만, 실제 지연·starvation 자료 없이 지금 도입하면 메시징 계약 변경과 워커 우선순위 로직만 먼저 늘어난다. **재검토 조건**: T012가 남기는 ① 단건 요청 대기시간 p95/p99 ② 사용자별 동시 탐색 수와 큐 점유량이 임계치를 넘으면 그때 큐 분리를 검토한다. 사용자별 라운드로빈은 경쟁 소비자 구조를 버려야 해 지금 규모에 과하다.
- **사용자 공개 조건**: PD-06 값이 prod 설정에 들어가고 plan Constitution Check IX가 PASS로 바뀐 뒤. 그 전에는 local 임시값으로만 동작한다.

## R-20 단건·벌크 작업 큐 분리와 비대칭 워커 풀 (T014 리뷰 후속, 2026-09-15)

- **Decision**: 작업 큐를 둘로 나눈다 — `backtest.jobs`(단건 `SINGLE`, **기존 큐 그대로**)와 `backtest.jobs.bulk`(`SEARCH_BUNDLE`·`REGIME_SECTOR`·`REGIME_TOTAL`, 신설·quorum·같은 DLX·같은 DLQ). 백엔드는 접수 시 `backtest_run.execution_type`을 고정하고 `BacktestJobDispatcher.send` 한 곳에서 유형별 routing key(`backtest.job.requested` / `backtest.job.requested.bulk`)로 발행한다 — 최초 발행과 `JobRepublisher` 재발행이 같은 자리를 지나므로 재발행도 원래 큐로 간다. 워커는 두 역할이다(`WORKER_ROLE`): **single** N개는 지금처럼 `backtest.jobs`만 `basic_consume`(push, prefetch 1); **flex** M개는 `backtest.jobs.bulk`에서 `basic_get` 1건 → 없으면 `backtest.jobs`에서 `basic_get` 1건 → 둘 다 없으면 1초 대기(pull 폴링), 한 건 처리·ACK 뒤에만 다음을 집는다. 운영 초기값은 N=1·M=1(4 vCPU 상한 2). T014 한도는 **단건 큐만** 본다 — `max-queued-per-user`는 그대로 `SINGLE`만, `max-queued-total`은 `QUEUED AND execution_type='SINGLE'`만 세고 비단건은 검사하지 않는다. T014의 "묶음은 세되 거절하지 않는다" 분기·경고 로그·관련 테스트·문서를 걷어낸다. `BacktestService.create`의 `noRollbackFor = BusinessException`은 남긴다(007이 접수를 자기 트랜잭션 안에서 부르는 구조는 그대로라 다른 거절이 완료 후보 정산을 지우는 것도 막는다). 메시지 본문·`publishAttempt` 헤더·DLQ 집계(T010) 계약은 바뀌지 않는다.
- **Rationale**: T014에서 세 갈래가 모두 막혔다 — 탐색을 사용자별 한도에 세면 `dispatch-window`와 충돌하고, 전체 한도에서 거절하면 007 `advance()`가 `BacktestFinished`로만 불려 마지막 진행 후보 뒤의 거절이 묶음을 영구 정지시키고, 한도에서 빼면 탐색이 도는 동안 **단건만 계속 429**를 받는다(묶음 수 × 창 = 80 > 50). 값이 아니라 **대화형과 배치가 한 큐·한 정책을 공유하는 구조**의 문제라 R-19의 "재검토 조건(T012 실측 뒤)"을 기다리지 않고 도입한다 — 실측 없이도 정책이 성립하지 않음이 리뷰에서 확인됐기 때문이다. 큐 이름을 바꾸지 않는 이유: `backtest.jobs`→`backtest.jobs.single` 개명은 quorum 큐 교체 절차(접수 중단 → ready·unacked 0 → 삭제 → 재선언, T002 `migrate-jobs-queue.sh`)를 다시 요구하는데 `bulk`만 추가하면 전환 절차가 없고 single 워커는 코드 변경이 없다. flex가 pull인 이유: quorum 큐는 **global QoS prefetch를 지원하지 않아**(RabbitMQ 3.13 기능 비교표) 한 채널에서 두 큐를 `basic_consume`하며 "합쳐서 1건"을 표현할 수 없고, 컨슈머별 prefetch 1로 두 큐를 구독하면 워커가 2건을 unacked로 쥔다 — 처리도 안 한 두 번째 메시지가 크래시로 재전달되면 유일한 재시도 기회(`x-delivery-limit=1`)가 실행 한 번 없이 소진된다. flex가 bulk 우선인 이유: 단건 최소 처리량은 single N이 이미 보장하고, single 우선이면 단건이 계속 들어오는 동안 벌크가 굶어 묶음이 `time-limit`에 걸려 PARTIAL로 끝난다. 수용한 한계: flex 워커의 단건 응답은 최대 폴링 간격(1초)만큼 늦다(single 워커는 push라 즉시). 배포는 T014와 같은 릴리즈로 묶는다(데모 규모에서 T014 단독 배포 위험은 낮지만 함께 내보내면 그 논의가 필요 없다).
- **Alternatives**: 완전 분리(single 전용 N + bulk 전용 M — 벌크가 없으면 M이 놀고 단건이 몰려도 못 쓴다, 4 vCPU에 워커 2개라 절반을 놀릴 수 없다) / 두 큐 `basic_consume` + global prefetch 1(quorum 미지원) / flex 단건 우선(벌크 굶음) / 두 큐의 도착 시각을 비교한 전역 FIFO(RabbitMQ는 큐 내부 FIFO만 보장하고 peek이 없어 꺼내 비교하고 requeue해야 하는데 순서와 `x-delivery-limit`이 꼬인다) / 우선순위 큐 하나(`x-max-priority` — quorum 미지원) / 007에 PENDING 재시도 스케줄러를 두고 묶음도 거절(007 담당자 몫이며, 있어도 배치가 대화형 한도를 소비하는 문제는 남는다).
- **T012에 넣는 지표**: 단건 큐 대기시간 p95/p99, 벌크 큐 대기시간·후보 처리량, single 워커 사용률, flex 워커가 단건을 지원한 비율, 동시 탐색 묶음 수, 큐별 대기 작업 수, 단건 429 횟수. N/M 비율과 `dispatch-window`를 이것으로 조정한다. 폴링 지연이 실제 문제로 측정되면 flex가 single만 `basic_consume`하고 벌크는 처리 사이에 `basic_get`하는 하이브리드가 다음 단계다.
