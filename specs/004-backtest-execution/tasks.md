# Tasks: 백테스트 실행

**Input**: `specs/004-backtest-execution/` — plan.md, spec.md(Clarifications 5건), research.md(R-01~R-19), data-model.md, contracts/{rest-api,messaging,market-data-input}.md, quickstart.md(S1~S12)

**작업 단위 규칙** (constitution "문서 역할"·Jira 규칙): 아래 T항목 하나 = Jira Task 하나 = 브랜치 하나 = MR 하나. 제목은 `[분야] 작업 내용` 형식이며 분야는 BE/FE/AI/DE/INFRA/DOCS. **워커(Python, `worker/`) 작업도 백엔드 파트 소관이므로 `[BE]`로 표기한다(2026-09-10 확정). 파일 경로가 `worker/`인 Task가 워커 작업이다.** 한 사람이 전부 맡는 전제로 2026-09-10에 25개를 13개로 재편했다(같은 파트가 연속으로 하는 작업, 한쪽만 병합하면 반쪽이 되는 작업, 합쳐도 하루치인 문서 작업을 묶음). Jira 이슈는 발급되어 있다(Epic `S15P21E205-30 백테스트`; Story: US1→`-38`, Setup·Foundational·Polish→`-21`, US2→`-125`, US3→`-126`, US4→`-127`). 병합으로 흡수된 12개 이슈(131·132·133·136·139·141·144·150·151·152·155·156)는 `[병합→…]` 제목과 코멘트를 남기고 종결했다. 브랜치는 `{type}({position})/{JIRA_NUMBER}-{desc}`(예: `feat(be)/130-backtest-schema-v3-v4`)로 만든다. 각 항목 아래 들여쓴 체크리스트는 구현 메모이며 Jira에 올리지 않는다. 각 Task의 완료 조건은 constitution "완료의 정의" 다섯 가지(구현·테스트/동작 확인·리뷰·MR 병합·문서)다.

**Tests**: 각 MR은 자기 변경의 테스트를 포함한다(R-16 검증 명령). 결정성(SC-002)·수기 대조(SC-003) 테스트는 T008에 명시했다.

**Format**: `- [ ] [ID] [P?] [Story?] [분야] 설명 (파일 경로) — Jira 키` — `[P]` = 다른 파일·의존 없음, 병렬 가능.

## Path Conventions

- Backend: `backend/src/main/java/com/ssafy/thorp/backtest/`(이하 `backtest/`), 마이그레이션 `backend/src/main/resources/db/migration/`, 테스트 `backend/src/test/java/com/ssafy/thorp/backtest/`
- Worker: `worker/src/worker/`, 테스트 `worker/tests/`, 픽스처 `worker/examples/`
- 계약 문서·픽스처: `docs/specs/backtest-api/`, `docs/specs/backtest-messaging/`
- 인프라: `docker-compose.dev.yml`, `infra/docker-compose.prod.yml`, `infra/scripts/`

---

## Phase 1: Setup (계약·환경)

**Purpose**: 양쪽(Java·Python)이 같은 계약 v2 위에서 시작하고, 실행 환경이 준비된다.

- [x] T001 [P] [BE] 메시지·API 계약 v2: 문서·픽스처·Java 레코드·quorum 토폴로지·pydantic 모델 (`docs/specs/backtest-api/{README.md,examples/*.json}`, `docs/specs/backtest-messaging/{README.md,examples/*.json}`, `backtest/application/port/message/*.java`, `backtest/infrastructure/messaging/{BacktestRabbitConfig,RabbitBacktestJobPublisher,RabbitBacktestControlPublisher}.java`, `backend/src/test/.../BacktestMessageRoundTripTest.java`, `worker/src/worker/contracts/{engine_input,engine_output,events,control}.py`, `worker/src/worker/messaging.py`, `worker/tests/{test_contracts,test_rabbitmq}.py`) — Jira `S15P21E205-128`
  - 문서: `specs/004-backtest-execution/contracts/rest-api.md`·`messaging.md`를 두 README에 반영, "같은 조건이면 항상 새 Job" 문장 제거. API 예시 8종(request v2, accepted-joined/reused, detail-running/cancelled, weights, exceptions, execution-metrics)·메시징 예시 v2 7종(engine-input, started, progress, finished-{completed,failed,cancelled}, control-cancel) 추가, v1 삭제. 픽스처 불변 조건 갱신(run 식별자 `RUN-…`, schemaVersion 2). `conditions.scenarioParams`(REST)와 `run.scenarioParams`·`run.scenarioRules`(메시징) 필드 포함, `engine-input-target-risk.json` 예시 추가(PD-18)
  - Java: `EngineInput` v2(run·universe.items·marketData.dataVersion/fillPrice/signalPrice·allocation{strategyId,rulesVersion,rules,parameters}·execution.costProfile·evaluation·progress, benchmark 제거), `BacktestEvent` v2 payload(stage·lastTradingDate·status/partial·resource), `ControlMessage{type,runId,requestedAt}`. `BacktestRabbitConfig`: `backtest.jobs` quorum + `x-delivery-limit=1` + 기존 DLX, fanout `backtest.control` 추가. 작업 메시지 `message_id="JOB-"+runId`
  - Python: 같은 필드의 pydantic v2 모델, `SCHEMA_VERSION=2`, `CONTROL_EXCHANGE`. `EngineOutput` v2 필드(weightsSeries·weightsSource·costs·liquidation·analytics·exceptions·versions·run.partial/lastTradingDate; 자원 사용량은 FINISHED payload `resource`). `strategy_params/` MA_CROSSOVER 삭제, 다섯 전략 파라미터 모델 추가
  - 양쪽 라운드트립 테스트가 같은 examples 픽스처로 통과, `test_rabbitmq.py`가 Java 상수와 동일함을 검증
- [ ] T002 [P] [INFRA] quorum 큐 전환 절차·워커 실행 환경·장애 시나리오 확인 (`docker-compose.dev.yml`, `infra/docker-compose.prod.yml`, `infra/scripts/migrate-jobs-queue.sh`, `infra/scripts/deploy.sh`, `infra/.env.prod.example`, `docs/guides/backtest-queue-migration.md`) — Jira `S15P21E205-129`
  - dev compose 워커에 `NUMBA_NUM_THREADS=OMP_NUM_THREADS=OPENBLAS_NUM_THREADS=MKL_NUM_THREADS=1`(R-14), 워커용 읽기 전용 DB 접속 `MARKET_DATA_URL`(R-09)
  - `migrate-jobs-queue.sh`: 접수 중단 → `messages_ready`·`messages_unacknowledged` 모두 0 확인 → `rabbitmqctl delete_queue backtest.jobs` → 백엔드 재시작으로 quorum 재선언 → 실패 시 되돌리기(선언 revert). `deploy.sh`에 1회 실행 안내. **T001 배포와 같은 단계에서 실행한다**
  - (T010 병합 뒤) 전환 실행, `rabbitmqctl list_queues name type arguments`로 quorum·delivery-limit 확인. quickstart S6: 워커 2 replica에서 RUNNING 중 `kill -9` 1회 → 재실행·완료, 2회 → DLQ → FAILED(SYSTEM_FAILURE). 가이드에 기록, 운영 배포 절차에 1회 실행 단계 추가

---

## Phase 2: Foundational (모든 스토리가 의존)

**Purpose**: 요청/실행 분리 스키마·등록표, 자료 입력 경로.

- [ ] T003 [BE] 스키마 V3·V4: 요청/실행 분리와 비용·규칙 등록표 (`backend/src/main/resources/db/migration/{V3__backtest_run_request,V4__cost_profile_strategy_rules}.sql`, `backtest/domain/model/{BacktestRequest,BacktestRun,RunStatus,ResultSource,ExecutionType,CostProfile,StrategyRules,RulesStatus}.java`, `backtest/application/service/{CostProfileService,StrategyRulesService}.java`, `backtest/infrastructure/persistence/*`, `common/exception/ErrorCode.java`) — Jira `S15P21E205-130`
  - V3(data-model.md 요약): `backtest_job`→`backtest_run` 개명·컬럼 추가(dedup_key, CANCELLED, progress_*, attempt, versions JSONB, published_at·publish_count·dlq_count — R-18), `backtest_request` 생성(`cancelled_last_trading_date`·`stopped_run` 포함 — R-04), `backtest_result`/`backtest_trade` FK→`run_id`, JSONB 열(weights_series, costs, liquidation, analytics, exceptions, versions). 부분 유니크 `UNIQUE (dedup_key) WHERE status IN ('QUEUED','RUNNING','COMPLETED')`. 기존 DEMO 데이터는 `requester_id NOT NULL` 때문에 삭제 권장(MR 리뷰에서 확정)
  - 도메인: `RunStatus.CANCELLED`·`isFinal()`, `BacktestRun.markRunning(executionId)`(attempt+1)/`markCompleted`/`markFailed`/`markCancelled`/`recordProgress`, `BacktestRequest.cancel()`
  - V4: `cost_profile` 초기 행 `PROVISIONAL-DEMO-1`(수수료 0·호가차 0·슬리피지 5bps·세금 0, provisional=true, 'ADR-0004 데모 값 재사용, PD-07 확정 전'); `strategy_rules`(provisional 열 포함): FIXED_WEIGHT/1·EQUAL_WEIGHT/1 REGISTERED, INVERSE_VOLATILITY/1 {lookbackDays:63, zeroVolatility:"EXCLUDE_THEN_ALLOCATE", weightCap, investedFraction} **provisional=true, decision_ref PD-02**(analyze C1), SECTOR_MOMENTUM·MINIMUM_VARIANCE PENDING_DECISION(PD-03·04)
  - `strategy_rules`에 `TARGET_RISK_OVERLAY/1 {lookbackDays:63, adjustAt:"REBALANCE", scaleDownOnly:true, targetVolatility:{default:"0.1000", min:"0.0500", max:"0.2000", step:"0.0100"}}` REGISTERED provisional=false(PD-18 확정) 행 추가
  - `StrategyRulesService.requireRegistered()` → PENDING이면 422 `RULES_NOT_REGISTERED`. `ErrorCode` 추가: RULES_NOT_REGISTERED, INSUFFICIENT_HISTORY, EXECUTION_LIMIT, NOT_CANCELLABLE, NOT_RETRYABLE, NO_PARTIAL_RESULT
  - 기존 `BacktestJob*Test`를 Run/Request 테스트로 교체, `FlywayMigrationTest`·`ArchitectureTest` 통과
- [ ] T004 [P] [BE] 시장 자료 공급자와 3종목 24개월 CSV 픽스처 (`worker/src/worker/market_data.py`, `worker/src/worker/config.py`, `worker/examples/three_etf_24m/{prices_raw,prices_adjusted,distributions,splits,instruments,mapping}.csv`, `worker/tests/test_market_data.py`) — Jira `S15P21E205-134`
  - `PostgresMarketDataProvider(url)`: contracts/market-data-input.md의 열을 읽어 원시 OHLC·조정 종가·분배금·분할·상장/폐지일·data_version 반환. 읽기 전용, 종목 ≤50·기간 한 번에 조회
  - `CsvMarketDataProvider`가 같은 스키마의 픽스처 디렉터리를 읽음(`versions.data="FIXTURE-three_etf_24m"`). 픽스처에 분배금 2건·거래 정지 1일·상장일 다른 종목 1개 포함(quickstart S8)
  - 결측 거래일 → `DataInsufficientError`(보간 금지), 기존 `validate_prices()` 재사용. `YFinanceDataProvider`는 `worker/scripts/`로 이동해 실행 경로에서 제외(원칙 X)

**Checkpoint**: 계약 v2·스키마·등록표·자료 경로가 준비되어 스토리 구현을 시작할 수 있다.

---

## Phase 3: User Story 1 - 분석을 요청하면 즉시 접수되고 진행 상태가 보인다 (Priority: P1) 🎯 MVP

**Goal**: v2 요청을 접수해 식별자·대기 상태를 즉시 돌려주고, 대기·실행·완료·실패·취소 상태와 진행 정도(단계·처리한 마지막 거래일·비율)를 조회할 수 있다.

**Independent Test**: quickstart S1. `POST /backtests` 직후 `202 {backtestId, status: QUEUED, resultSource: NEW, effectiveStart}`, 폴링하면 `progress.stage`·`lastTradingDate`가 바뀌고 `COMPLETED`에서 `versions`가 채워진다. 실패 시 `failure.code`가 보이고 `summary=null`.

- [ ] T005 [US1] [BE] 접수·상태 API v2와 이벤트 처리(동일 버전 키·진행 저장·attempt) (`backtest/presentation/{request/BacktestJobRequest,response/BacktestJobResponse,response/BacktestDetailResponse,controller/BacktestController}.java`, `backtest/application/service/{BacktestService,JobRepublisher,DedupKeyCalculator,EffectivePeriodCalculator,EngineInputAssembler,BacktestEventService,BacktestResultQueryService}.java`, `backtest/infrastructure/messaging/BacktestEventListener.java`, 테스트 `{DedupKeyCalculatorTest,EffectivePeriodCalculatorTest,BacktestServiceTest,JobRepublisherTest,BacktestPublishAfterCommitIT,BacktestControllerTest,BacktestEventServiceTest,BacktestEventFlowIT,BacktestResultApiIT}`) — Jira `S15P21E205-135`
  - 접수: 요청 본문 contracts/rest-api.md 구조(executionType·sectorAllocation·strategy·conditions). 검증: targetWeight는 FIXED_WEIGHT만·합=1, 상한 합계 ≥ 투자 비중, 식별자 패턴, 규칙 REGISTERED, 워밍업 가용 기간(422 INSUFFICIENT_HISTORY). `conditions.warmupTradingDays`(선택) 수용 → `EffectivePeriodCalculator`가 요청값(없으면 전략 관측 길이)으로 `effectiveStart` 산출, 요청값 < 전략 요구값이면 422 INSUFFICIENT_HISTORY — 올리지 않는다(R-15). 테스트: 경계 3종(같음·작음·큼), 같은 시작일·같은 warmupTradingDays로 FIXED_WEIGHT와 INVERSE_VOLATILITY를 요청하면 effectiveStart가 같다. 대응표·자료 버전·상장일 읽기 포트가 없으면 이 Task에서 픽스처 기반으로 만든다(analyze M1)
  - `DedupKeyCalculator`: canonical JSON(키 정렬·BigDecimal plain string·null 제거) → SHA-256. 포함 항목은 R-03(실효 `warmupTradingDays`·scenarioParams 포함). 요청자·executionType 제외, 같은 전략을 다른 워밍업으로 요청하면 키가 다름을 테스트로 고정
  - `BacktestService.create()`: NEW 경로(키 조회 → 없으면 run+request 생성 → **커밋 후** 발행, R-18). 발행 시도 직전 `publish_count += 1`(confirm 무관, R-18), confirm 뒤 `published_at` 채움. `JobRepublisher`(스케줄 30초)가 `QUEUED AND published_at IS NULL AND created_at < now()-1분`을 재발행. JOINED/REUSED는 T010. 테스트: 커밋 실패 시 미발행, 발행 시각 ≥ 커밋 시각(IT), 발행 실패 → 재발행 1회 → published_at 채움, 중복 발행 종결 규칙(R-18): ⑤ 중복 중 하나가 비current executionId로 FAILED → 무시·RUNNING 유지 → 다른 하나 COMPLETED 적용, ⑥ confirm 유실 재현(첫 발행은 브로커 도달·confirm 예외 → publish_count=1·published_at NULL → 재발행 → 2) → 하나만 DLQ → `dlq_count < publish_count`라 RUNNING 유지 → 다른 실행 COMPLETED, ⑦ `autoRetried = attempt > publish_count`(중복 실행은 false, 재전달은 true)
  - 시나리오 검증: `TARGET_RISK`면 `scenarioParams.targetVolatility` 필수·0.0500~0.2000·0.0100 간격, `FRESH_CASH_FROM`이면 `startDate` 필수, 그 외 `scenarioParams` 금지(400). scenario·scenarioParams·오버레이 규칙 버전을 동일 버전 키에 포함
  - `EngineInputAssembler` v2: 대응표·규칙 사본·비용 프로필·dataVersion·effectiveStart·seed 42. `TARGET_RISK`면 `run.scenarioRules`에 `TARGET_RISK_OVERLAY/1` 사본을 채움. 202 응답에 resultSource·effectiveStart/End·versions
  - 조회 `GET /backtests/{id}`: 파생 상태(cancelled_at→CANCELLED, 완료 범위는 `cancelled_last_trading_date` — R-04), progress{stage,lastTradingDate,completed/totalTradingDays}, queueWaitSeconds, partial, resultSource, versions, failure{code,message}, summary. 타인 요청 404
  - 이벤트: `handleStarted` QUEUED→RUNNING·attempt+1·stage=PREPARING_DATA(RUNNING 중 다른 executionId면 attempt만 +1); `handleProgress` 트랜잭션 저장(`LoggingBacktestProgressNotifier` 삭제); `handleFinished(COMPLETED|FAILED)` 결과·versions·failure_code 저장 — COMPLETED는 어느 executionId든 적용, FAILED는 `event.executionId == run.current_execution_id`일 때만(R-18). CANCELLED는 T011. 종결 후 이벤트 무시 유지
- [ ] T006 [P] [US1] [BE] 워커 소비자 v2와 다종목 리밸런싱 Nautilus 전략 (`worker/src/worker/{consumer,rabbitmq,engine_runner,nautilus_engine,portfolio_execution,engine_support}.py`, `worker/tests/{test_consumer,test_portfolio_execution}.py`) — Jira `S15P21E205-137`
  - 소비자 v2: STARTED{stage: PREPARING_DATA, resource{pid,hostname}}, PROGRESS{stage: COMPUTING, lastTradingDate,…} 20거래일 배치, FINISHED{status, partial, lastTradingDate, engineOutput, resource{peakRssMb,cpuSeconds,wallSeconds}}(`resource.getrusage`). INVALID_REQUEST·DATA_INSUFFICIENT·CONSTRAINT_VIOLATION·ESTIMATION_FAILED는 FINISHED(FAILED) 후 ACK(재시도 없음); 프로세스 예외는 전파해 unacked로 죽게 둔다. `engine_support.py` 제한을 v2 기준(fillPrice=RAW, signalPrice=ADJUSTED, 규칙 사본 필수)으로 교체
  - `PortfolioRebalanceExecution(NautilusStrategy)`: 월말 판단일 `on_bar`(마지막 종목 bar 뒤)에서 배분 모듈(T007)에 조정 종가 이력 전달 → 목표 비중 → 현금·현재가 기준 정수 수량 주문 → 다음 거래일 시가 QuoteTick 시장가 체결. 공매도·레버리지 불가, 매도→매수 순서 고정
  - 거래 정지·거래 불가(halted 또는 volume 0) → 다음 거래 가능일로 이월, `reason=HALT_DEFERRED, plannedDate`
  - 워밍업: `evaluation.warmupTradingDays` 소비 후 `effectiveStart`부터 성과 집계. 종료일: 시가평가·청산(전량 매도, 비용 포함) 두 결과(`liquidation`)
  - 비중 오차·잔여 현금을 `weightsSeries[].weightError`·`portfolioSeries[].cash`로 기록(재정규화 금지). `scenario=FRESH_CASH_FROM`(시작일·시작 현금 재지정) 지원
  - `scenario=TARGET_RISK` 오버레이(PD-18): 리밸런싱 판단일마다 최근 `scenarioRules.lookbackDays`(63) 포트폴리오 일별 수익률 표준편차 × √252로 변동성 추정, 추정치 > `scenarioParams.targetVolatility`이면 투자 비중 합계에 `target/estimate`를 곱해 나머지를 현금으로, 아니면 그대로(하향만, 레버리지 금지). `weightsSeries[].riskScale`·`estimatedVolatility` 기록. 규칙 없이 오면 INVALID_REQUEST

**Checkpoint**: 접수·상태 흐름과 워커 실행 경로가 연결된다. 전략 계산 검증은 US2 뒤에 S1로 재확인한다.

---

## Phase 4: User Story 2 - 다섯 전략이 당시 실행 가능한 규칙으로 계산된다 (Priority: P1)

**Goal**: 등록된 세 배분 규칙(고정·동일·역변동성)을 공통 거래 규칙 위에서 계산하고 FR-027 결과 항목을 모두 산출한다. 미등록 두 전략(PD-03·04)은 거부한다.

**Independent Test**: quickstart S8·S9·S7. 3종목 24개월 픽스처로 세 전략을 실행해 대표 거래일 3개의 체결일·수량·현금·비용·분배금이 `manual_reconciliation.json`과 정확히 일치, 모든 체결일 = 판단일 다음 거래일, 거래 정지일 이월 `reason=HALT_DEFERRED`, 결과 시작일 = 워밍업 이후. `SECTOR_MOMENTUM` 요청은 422. 같은 입력 두 번 실행 시 EngineOutput 바이트 동일.

- [ ] T007 [P] [US2] [BE] 기업행위·비용 프로필·배분 규칙 모듈(고정·동일·역변동성) (`worker/src/worker/{corporate_actions,costs,rules_registry}.py`, `worker/src/worker/allocation/{__init__,base,fixed,equal,inverse_vol}.py`, `worker/tests/{test_corporate_actions,test_costs,test_allocation}.py`) — Jira `S15P21E205-140`
  - 분배금: 보유 중 `pay_date`에 `amount_per_share × 보유 수량` 현금 유입 1회, `portfolioSeries[].cashFlows[{type: DISTRIBUTION}]`. ETF 내부 보수 미차감(FR-013). 분할은 수량 조정
  - 비용: `costProfile`의 commission·spread·slippage·tax bps를 체결마다 적용해 `trades[].detail`과 `costs{…,total}`. 하드코딩 `SLIPPAGE_BPS=5` 제거. `escalation`은 `scenario=COST_ESCALATION`일 때만, 미정의면 INVALID_REQUEST
  - `compute_weights(history, params, rules) -> AllocationResult{weights, source: USER|COMPUTED, exceptions[]}` 순수 함수. FIXED_WEIGHT(source=USER), EQUAL_WEIGHT(1/N), INVERSE_VOLATILITY(`rules.lookbackDays` 63 표준편차 역수, 0 변동성은 `rules.zeroVolatility`대로 + `exceptions[{kind: ZERO_VOLATILITY}]`, `weightCap` 초과 재분배 불가 시 CONSTRAINT_VIOLATION). 규칙 값은 EngineInput `allocation.rules`에서만 읽고 자체 기본값 없음(provisional, PD-02)
  - `rules_registry.py`: SECTOR_MOMENTUM·MINIMUM_VARIANCE 또는 rules 없음 → INVALID_REQUEST("RULES_NOT_REGISTERED"). 두 전략 모듈 파일은 만들지 않음(PD-03·04 확정 후 별도 Task)
- [ ] T008 [US2] [BE] 성과 지표 확장과 결정성·수기 대조 테스트(SC-002·SC-003) (`worker/src/worker/{performance,engine_runner}.py`, `worker/tests/{test_performance,test_determinism,test_manual_reconciliation}.py`, `worker/tests/fixtures/{manual_reconciliation.json,engine-input-three-etf-{fixed,equal,inverse_vol}.json}`, `docs/specs/backtest-messaging/examples/reconciliation/*.csv`) — Jira `S15P21E205-142`
  - `performance{…, netTotalReturn, maxDrawdown, totalTradingCost}`, `analytics{recoveryDays, turnover, correlationMatrix, riskContribution, lossContribution, weightError, method:"sample-covariance-daily"}`, `exceptions[]` 집계, `versions{mapping, rules(provisional), scenarioRules(TARGET_RISK_OVERLAY/1, 해당 시), costProfile(provisional), data, engine:"nautilus_trader 1.219.0", worker}`. 결정성·수기 대조 테스트에 `TARGET_RISK` 픽스처 1건 추가(quickstart S11). 부분 결과(취소·한도)는 `lastTradingDate`까지의 시계열로 같은 함수가 계산
  - 결정성: 같은 EngineInput 두 번 실행 → `run.startedAt`·`run.finishedAt` 두 필드를 뺀 `EngineOutput.to_json()`이 바이트 동일(세 전략). 자원 사용량은 EngineOutput 밖(FINISHED payload `resource`)이라 비교 대상이 아니다. 시각 두 필드는 존재만 확인
  - 수기 대조: 스프레드시트로 계산한 대표 거래일 3개(첫 리밸런싱·분배금 지급일·거래 정지 이월일)의 체결일·수량·현금·비용·분배금을 JSON으로 고정, `PROVISIONAL_TOLERANCE = 0`(PD-12 확정 시 상수만 변경). 스프레드시트 원본 CSV 보관
  - 검증(SC-004 "판단일 다음 거래일 이후"): `reason ∈ {SIGNAL, REBALANCE, LIQUIDATION}`인 체결은 체결일 = 판단일 다음 거래일, `reason=HALT_DEFERRED`인 체결은 `plannedDate` = 판단일 다음 거래일이고 체결일 = 그 이후 첫 거래 가능일. 두 경우 모두 신호에 판단일 이후 자료를 쓰지 않았는지(look-ahead 0건) 확인. 정수 수량, 결과 시작일 = effectiveStart
- [ ] T009 [US2] [BE] 결과 v2 저장·조회 API(비중·예외·거래 사유·현금 흐름) (`backtest/application/service/BacktestResultQueryService.java`, `backtest/presentation/controller/BacktestController.java`, `backtest/presentation/response/{BacktestWeightsResponse,BacktestExceptionsResponse,BacktestTimeseriesResponse,BacktestTradePageResponse}.java`, `backtest/infrastructure/persistence/BacktestResultJpaEntity.java`, `backend/src/test/.../BacktestResultApiIT.java`) — Jira `S15P21E205-146`
  - FINISHED payload의 v2 필드를 `backtest_result` JSONB 열에 저장(T005 저장 경로 확장)
  - `GET /backtests/{id}/weights`(weightsSource 포함), `GET /backtests/{id}/exceptions`, `/trades` 항목 reason·plannedDate, `/timeseries` 항목 cashFlows
  - 409 규칙 유지(RESULT_NOT_READY, BACKTEST_FAILED); CANCELLED는 200 + partial=true(T011 이후 활성)

**Checkpoint**: 세 전략이 픽스처에서 수기 계산과 일치하고 결정적이다. S1을 실제 전략으로 재검증한다.

---

## Phase 5: User Story 3 - 같은 조건은 재사용하고, 실패는 재시도하며, 오래 걸리면 취소한다 (Priority: P2)

**Goal**: 동일 버전 재사용(REUSED)·진행 중 합류(JOINED), 요청 단위 취소와 마지막 요청 취소 시 실행 정지·부분 결과, 실패·취소 후 재시도, 시스템 장애 1회 자동 재시도.

**Independent Test**: quickstart S2~S6·S12. 같은 본문 재요청이 `REUSED`, RUNNING 중 재요청이 `JOINED`, A 취소 후 B 유지, B 취소 시 `CANCELLED partial=true`와 부분 시계열 200, FAILED 후 retry 202, 워커 `kill -9` 1회는 완료·2회는 `SYSTEM_FAILURE`.

- [ ] T010 [US3] [BE] 동일 버전 재사용·합류·재시도와 DLQ 처리 (`backtest/application/service/{BacktestService,BacktestRetryService}.java`, `backtest/domain/model/BacktestReuseEvent.java`, `backtest/infrastructure/persistence/{BacktestReuseEventJpaEntity,…}.java`, `backtest/infrastructure/messaging/BacktestDeadLetterListener.java`, `backtest/presentation/controller/BacktestController.java`, `backend/src/main/resources/db/migration/V5__backtest_reuse_event.sql`, 테스트 `{BacktestServiceTest,BacktestDedupIT,BacktestRetryServiceTest,BacktestDeadLetterListenerIT}`) — Jira `S15P21E205-148`
  - `create()`: 키로 활성 run 조회 → COMPLETED면 REUSED(발행 없음) / QUEUED·RUNNING이면 JOINED(발행 없음) / 없으면 NEW. 부분 유니크 위반(동시 접수 경합) 시 재조회해 JOINED로 전환
  - `backtest_reuse_event(run_id, request_id, kind, request_execution_type, at)` 기록(FR-008, analyze M2). IT: 두 사용자·같은 본문 → run 1·request 2·reuse_event 1
  - `POST /backtests/{id}/retry`: FAILED·CANCELLED에서만(아니면 409 NOT_RETRYABLE), 같은 canonical 요청으로 `create()` 재호출, `retry_of_request_id`, 202 {backtestId, status, resultSource, retryOf}
  - `@RabbitListener(queues="backtest.jobs.dlq")`: runId로 `run.dlq_count += 1`, `dlq_count >= publish_count`이고 비종결이면 FAILED(SYSTEM_FAILURE) 종결(이미 종결이면 무시, R-18). IT(Testcontainers RabbitMQ): quorum `x-delivery-limit=1` 확인, nack-requeue 2회 → DLQ → FAILED; confirm 유실로 생긴 중복(시도 2·confirm 1) 뒤 한 메시지만 DLQ → RUNNING 유지 → 다른 실행 COMPLETED
- [ ] T011 [US3] [BE] 취소: API·마지막 활성 요청 규칙·워커 제어 채널·부분 결과 (`backtest/presentation/controller/BacktestController.java`, `backtest/application/service/{BacktestCancelService,BacktestEventService}.java`, `backtest/infrastructure/messaging/RabbitBacktestControlPublisher.java`, 테스트 `{BacktestCancelServiceTest,BacktestCancelFlowIT}`, `worker/src/worker/{control,rabbitmq,consumer,portfolio_execution}.py`, `worker/tests/test_control.py`) — Jira `S15P21E205-149`
  - `POST /backtests/{id}/cancel`: 요청자 본인만, 종결이면 409 NOT_CANCELLABLE. `request.cancelled_at` 기록 → 같은 run에 활성 요청이 없으면 `run.cancel_requested_at`, QUEUED면 즉시 CANCELLED, RUNNING이면 `backtest.control`에 CANCEL{runId} 발행. 응답 `cancelRequested: true`. 취소마다 `request.cancelled_last_trading_date = run.progress_last_trading_date`(없으면 NULL = 완료 거래일 없음) 스냅샷, 마지막 활성 요청이면 `stopped_run=true`(범위는 FINISHED의 lastTradingDate가 run에 남는다 — R-04)
  - `handleFinished(CANCELLED)`: run CANCELLED·partial=true·progress_last_trading_date, 부분 결과 저장. `handleStarted`에서 run이 이미 CANCELLED면 CANCEL 재발행(대기 중 취소)
  - 합류 취소 조회: 스냅샷이 있는 요청은 `partial=true`·완료 범위=스냅샷·`summary=null`(`summaryUnavailable: CANCELLED_WHILE_RUN_CONTINUED`). 하위 리소스(`/timeseries`·`/weights`·`/trades`·`/positions`·`/exceptions`)는 run 종결 전 `409 RESULT_NOT_READY`, run FAILED면 `409 BACKTEST_FAILED`, run 종결 후에는 스냅샷 날짜까지 읽기 필터. IT `BacktestCancelFlowIT` 네 갈래: ⓐ A·B 합류 → A 취소(스냅샷 2021-06-30) → B 실행 중 A `/timeseries` → 409 RESULT_NOT_READY, A 상세는 200 CANCELLED ⓑ → B 완료 → A 재조회 시 완료 범위 2021-06-30 유지·summary null·`/timeseries` 200(그 날짜까지), B는 COMPLETED 전체 결과 ⓒ A 취소 → B FAILED → A 상세 CANCELLED 유지·하위 409 BACKTEST_FAILED ⓓ A 취소(d1) → B 취소(run이 d2 > d1에서 정지) → A d1·summary null, B d2·부분 요약·`partial=true` ⓔ A·B 대기 중(첫 PROGRESS 전) A 취소 → 스냅샷 NULL·`stopped_run=false` → B 완료 → A는 `progress.lastTradingDate=null`·summary null·하위 409 NO_PARTIAL_RESULT(B 날짜로 대체되지 않음) ⓕ 워커 없이 마지막 요청 취소 → `cancel-grace-seconds` 뒤 run FAILED(SYSTEM_FAILURE)·결과 행 없음 → 요청은 CANCELLED·`partial=false`·`summaryUnavailable: CANCEL_UNANSWERED`·하위 409 BACKTEST_FAILED, 늦게 온 FINISHED 무시(T014 sweeper와 함께 검증)
  - 워커: 시작 시 `backtest.control` exclusive·auto-delete 큐 바인딩, 별도 스레드가 CANCEL{runId}를 `threading.Event` 맵에 표시. `PortfolioRebalanceExecution.on_bar`에서 취소 확인 시 `self.stop()`; 소비자는 `lastTradingDate`까지의 부분 EngineOutput(T008 함수)으로 FINISHED{status: CANCELLED, partial: true} 발행 후 ACK. 모르는·끝난 runId 무시

- [ ] T014 [US3] [BE] 실행 한도 장치: 설정 가능한 대기·실행 시간 상한과 429 EXECUTION_LIMIT (`backtest/application/service/{ExecutionLimitPolicy,BacktestService,BacktestCancelService}.java`, `backtest/infrastructure/config/BacktestLimitProperties.java`, `backend/src/main/resources/application*.yml`, `common/exception/ErrorCode.java`, 테스트 `{ExecutionLimitPolicyTest,ExecutionLimitApiIT}`, `docs/specs/backtest-api/README.md`) — Jira `S15P21E205-168`
  - 설정 `thorp.backtest.limits.{max-queued-per-user, max-queued-total, max-run-seconds, stale-run-seconds, cancel-grace-seconds}`. prod 프로필 필수(미설정 시 기동 실패), local은 임시값 + `provisional=true` 표시(R-19). **값은 PD-06, 이 Task는 장치만**
  - 접수: 대기 수 검사는 접수 트랜잭션에서 `pg_advisory_xact_lock`(사용자 키·전체 키)로 직렬화. 초과 → 429 `EXECUTION_LIMIT`{reason, queued, retryAfterSeconds}. JOINED·REUSED는 세지 않음. 테스트: 동시 접수 N건(스레드) → 통과 수 ≤ 상한
  - 동시 실행 상한은 접수에서 검사하지 않는다 — 워커 replica × prefetch(1)가 강제하며 T002 배치 문서에 "동시 실행 상한 = replica 수"로 명시(R-19)
  - 실행 시간: 스케줄러가 `RUNNING AND started_at < now()-max-run-seconds`인 run에 CANCEL 발행(T011 채널) → 워커 응답 시 run `CANCELLED(partial=true, failure_code=EXECUTION_LIMIT)`, 완료 범위 표시(FR-005)
  - 정체 감지·취소 유예(R-19): `RunLivenessSweeper`가 RUNNING인데 마지막 이벤트로부터 `stale-run-seconds` 지난 run에 CANCEL 발행, 어떤 CANCEL이든 뒤로 `cancel-grace-seconds` 안에 FINISHED 없으면 `FAILED(partial=false, 결과 행 없음, failure_code = SYSTEM_FAILURE 또는 EXECUTION_LIMIT)` 강제 종결. 테스트: 워커 없이 CANCEL → 유예 뒤 FAILED·결과 행 없음·늦은 FINISHED 무시; 워커 있는 협력 취소는 CANCELLED partial=true; PROGRESS가 계속 오는 긴 실행은 정체로 보지 않음
  - T012 측정에 `limit_hit`(접수 거부·실행 정지 구분) 기록. quickstart S12

**Checkpoint**: S2~S6·S12 통과.

---

## Phase 6: User Story 4 - 실행 한도를 실측 근거로 정한다 (Priority: P2)

**Goal**: 실행 유형별 대기 포함 소요·동시 처리 수·자원 사용·실패·자동 재시도·건당 원가(원단위)를 기록하고 p50/p95·실패율을 운영자가 조회한다. 상한 값은 정하지 않는다(PD-06).

**Independent Test**: quickstart S10. S1~S6 뒤 `GET /internal/execution-metrics?type=SINGLE`이 `count ≥ 6`, 0보다 큰 `failRate`·`cancelRate`·`autoRetryRate`, `waitP50Ms ≤ waitP95Ms`, `reuseCount ≥ 1`을 돌려주고 `v_run_metric_summary`와 값이 같다.

- [ ] T012 [US4] [BE] 실행 측정 기록·집계 뷰·운영자 조회 API (`backend/src/main/resources/db/migration/V6__backtest_run_metric.sql`, `backtest/application/service/{ExecutionMetricRecorder,BacktestEventService}.java`, `backtest/presentation/controller/ExecutionMetricsController.java`, `backtest/presentation/response/ExecutionMetricsResponse.java`, 테스트 `{ExecutionMetricRecorderTest,ExecutionMetricsApiIT}`) — Jira `S15P21E205-153`
  - `backtest_run_metric` 테이블과 뷰 `v_run_metric_summary`(`percentile_cont(0.5/0.95)` 대기·소요, fail/cancel/auto-retry rate, reuse_count는 `backtest_reuse_event` 조인)
  - 종결 이벤트(COMPLETED·FAILED·CANCELLED·DLQ)마다 `ExecutionMetricRecorder.record(run)`: wait_ms, run_ms, concurrent_running, peak_rss_mb·cpu_seconds, auto_retried = attempt > publish_count(R-18), result_bytes. 유형은 `run.execution_type`
  - `GET /internal/execution-metrics?type=&from=&to=` 운영자 권한, 뷰 행 반환. 사용자 화면 노출 없음
  - 원가는 원단위(cpu·메모리·바이트)만. 금액 단가·상한 값은 넣지 않는다(PD-06·D19)

**Checkpoint**: 측정 기록이 쌓이고 PD-06 결정에 쓸 뷰가 있다.

---

## Phase 7: Polish

- [ ] T013 [BE] 마무리: 워커 레거시 제거·ADR-0005·README·quickstart 검증 기록 (`docs/adr/0005-backtest-request-run-split.md`, `README.md`, `worker/CLAUDE.md`, `backend/CLAUDE.md`, `docs/specs/101-nautilus-momentum.md`, `docs/guides/backtest-execution-verification-2026-09.md`, `specs/004-backtest-execution/quickstart.md`, `worker/src/worker/strategies/*`, `worker/src/worker/engine_support.py`, `worker/examples/momentum_fixture.csv`, `worker/tests/{test_momentum_engine,test_engine_support}.py`, `worker/pyproject.toml`) — Jira `S15P21E205-154`
  - 레거시 제거: 단일 종목 `MomentumStrategy`·`STRATEGIES` 레지스트리·`yfinance` 의존·v1 전용 테스트 삭제. `psycopg` 유지, `SQLAlchemy` 미사용이면 제거
  - ADR-0005: research R-02(요청/실행 분리)·R-04(제어 채널)·R-06(quorum 큐) 결정과 버린 대안. README "현재 구현 상태"를 다종목·다섯 전략(세 개 실행 가능, 두 개 PD 대기)로 갱신. `101-nautilus-momentum.md` 개정 또는 폐기 표시(진행 정도는 폴링+DB 저장)
  - quickstart S1~S12 전체 실행, 실제 응답·DB 값·소요 시간 기록. 실패 항목은 실패로 남기고 원인 Task를 연다. "구현 완료"만 보고하고 사용성·효과 입증은 별도임을 명시

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1**: T001 ∥ T002(환경 반영 부분). T002의 큐 전환 실행은 T001과 같은 배포 단계(전환 없이 v2 선언만 배포하면 재선언 실패). 장애 시나리오 확인만 T010 뒤.
- **Phase 2**: T003은 T001 뒤. T004는 독립.
- **Phase 3**: T005는 T001·T003 뒤. T006은 T001·T004 뒤. T005 ∥ T006.
- **Phase 4**: T007은 T001 뒤(T006과 병렬 가능). T008은 T006·T007 뒤. T009는 T005 뒤, T008 뒤 병합 권장.
- **Phase 5**: T010은 T005 뒤. T011은 T005·T006·T008 뒤. T010 ∥ T011. T014는 T005·T011 뒤(제어 채널 재사용).
- **Phase 6**: T012는 T005·T010·T011·T014 뒤(`limit_hit` 기록).
- **Phase 7**: T013은 모든 Task 뒤.

### User Story Dependencies

- **US1**: Foundational 뒤 시작. 실제 전략 실행 검증은 US2 뒤에 다시 한다.
- **US2**: T006(워커 엔진)에 의존. 백엔드 T005와 병렬 진행 가능.
- **US3**: US1(접수·이벤트)과 US2(부분 결과 계산)에 의존.
- **US4**: US1·US3의 종결 경로에 의존.

### Parallel Opportunities

- T001 ∥ T002 → T003 ∥ T004 → T005 ∥ T006 ∥ T007 → T008 → T009 ∥ T010 ∥ T011 → T014 → T012 → T013
- 한 사람이 할 때는 위 순서를 그대로 직렬로 진행하면 된다. 병렬 표시는 리뷰 대기 중 다음 Task를 시작할 수 있는 지점이다.

## Implementation Strategy

### MVP First (US1 + 등록된 세 전략)

1. T001~T004(계약·환경·스키마·자료) → T005·T006(접수·워커) → T007~T009(전략·지표·결과 API).
2. quickstart S1·S7·S8·S9로 검증 후 데모. 세 전략은 접수·실행·조회되고, 두 전략은 "팀 결정 대기"로 거부된다.

### Incremental Delivery

1. T010·T011(재사용·재시도·취소) → S2~S6. T002의 장애 시나리오 확인도 이때 마무리(큐 전환 실행은 T001 배포 시점에 끝나 있어야 한다). T014(한도 장치) → S12.
2. T012(측정) → S10. 이 뷰가 PD-06 결정 자료다.
3. T013 마무리.
4. PD-03·PD-04가 확정되면 별도 Task로 `allocation/momentum.py`·`allocation/min_variance.py`와 `strategy_rules` 행 갱신을 추가한다(이 tasks.md 범위 밖).

## Notes

- PD-03·04·06·07 값은 어느 Task에서도 정하지 않는다. 등록표 행·상수 자리만 만든다.
- 각 Task 병합 전 AI 코드리뷰(CLAUDE.md 규칙)와 R-16 검증 명령 통과가 필요하다.
- 기존 DEMO 데이터는 V3에서 폐기 전제다. 보존이 필요하면 T003 리뷰에서 마이그레이션 방식을 바꾼다.
- analyze H1(임시 실행 한도)은 plan 리뷰 4번을 반영해 장치(T014, Jira -168)와 값(PD-06)을 분리했다. 팀이 값을 주면 prod 설정만 채우고 plan IX 판정을 PASS로 바꾼다. H2(`TARGET_RISK`)는 2026-09-10 PD-18로 확정해 T001·T003·T005·T006·T008에 나눠 넣었다. 목표 변동성 항목(기본 10%, 5~20%, 1%)을 003 파라미터 등록표에 올리는 일은 003 spec 쪽 작업이다.
