# Tasks: 백테스트 실행

**Input**: `specs/004-backtest-execution/` — plan.md, spec.md(Clarifications 5건), research.md(R-01~R-16), data-model.md, contracts/{rest-api,messaging,market-data-input}.md, quickstart.md(S1~S10)

**작업 단위 규칙** (constitution "문서 역할"·Jira 규칙): 아래 T항목 하나 = Jira Task 하나 = 브랜치 하나 = MR 하나. 제목은 `[분야] 작업 내용` 형식이며 분야는 BE/FE/AI/DE/INFRA/DOCS. **워커(Python, `worker/`) 작업도 백엔드 파트 소관이므로 `[BE]`로 표기한다(2026-09-10 확정). 파일 경로가 `worker/`인 Task가 워커 작업이다.** Jira 번호는 이슈 생성 뒤 붙이고, 브랜치는 `{type}({position})/{JIRA}-{desc}`로 만든다. 각 항목 아래 들여쓴 체크리스트는 구현 메모이며 Jira에 올리지 않는다. 각 Task의 완료 조건은 constitution "완료의 정의" 다섯 가지(구현·테스트/동작 확인·리뷰·MR 병합·문서)다.

**Tests**: 각 MR은 자기 변경의 테스트를 포함한다(R-16 검증 명령). 별도 테스트 Task는 spec이 성공 기준으로 명시한 결정성(SC-002)·수기 대조(SC-003)만 둔다.

**Format**: `- [ ] [ID] [P?] [Story?] [분야] 설명 (파일 경로)` — `[P]` = 다른 파일·의존 없음, 병렬 가능.

## Path Conventions

- Backend: `backend/src/main/java/com/ssafy/thorp/backtest/`(이하 `backtest/`), 마이그레이션 `backend/src/main/resources/db/migration/`, 테스트 `backend/src/test/java/com/ssafy/thorp/backtest/`
- Worker: `worker/src/worker/`, 테스트 `worker/tests/`, 픽스처 `worker/examples/`
- 계약 문서·픽스처: `docs/specs/backtest-api/`, `docs/specs/backtest-messaging/`
- 인프라: `docker-compose.dev.yml`, `infra/docker-compose.prod.yml`, `infra/scripts/`

---

## Phase 1: Setup (계약·환경)

**Purpose**: 양쪽 팀이 같은 계약 픽스처를 보고 시작할 수 있게 한다.

- [ ] T001 [P] [DOCS] 백테스트 API·메시징 계약 v2 문서와 예시 픽스처 갱신 (`docs/specs/backtest-api/README.md`, `docs/specs/backtest-api/examples/*.json`, `docs/specs/backtest-messaging/README.md`, `docs/specs/backtest-messaging/examples/*.json`)
  - `specs/004-backtest-execution/contracts/rest-api.md`·`messaging.md` 내용을 두 README에 옮기고 "같은 조건이면 항상 새 Job" 문장을 제거
  - 예시 추가: `backtest-request.json`(v2), `backtest-accepted-joined.json`, `backtest-accepted-reused.json`, `backtest-detail-running.json`, `backtest-detail-cancelled.json`, `backtest-weights.json`, `backtest-exceptions.json`, `execution-metrics.json`
  - 메시징 예시 v2: `engine-input.json`, `event-started.json`, `event-progress.json`, `event-finished-{completed,failed,cancelled}.json`, `control-cancel.json`. v1 예시 삭제
  - 픽스처 불변 조건(§6) 갱신: run 식별자 `RUN-…`, schemaVersion 2
- [ ] T002 [P] [INFRA] 개발·운영 compose에 quorum 큐 전환 절차와 워커 실행 환경 반영 (`docker-compose.dev.yml`, `infra/docker-compose.prod.yml`, `infra/scripts/migrate-jobs-queue.sh`, `infra/.env.prod.example`)
  - dev compose 워커에 `NUMBA_NUM_THREADS=OMP_NUM_THREADS=OPENBLAS_NUM_THREADS=MKL_NUM_THREADS=1` 추가(R-14)
  - 워커용 읽기 전용 DB 접속 문자열 `MARKET_DATA_URL` 환경 변수 추가(R-09)
  - `migrate-jobs-queue.sh`: 미ACK 메시지 0 확인 → `rabbitmqctl delete_queue backtest.jobs` → 백엔드 재시작으로 quorum 재선언(R-06 주의 사항). 운영 배포 절차(`infra/scripts/deploy.sh`) 주석에 1회 실행 안내

---

## Phase 2: Foundational (모든 스토리가 의존)

**Purpose**: 요청/실행 분리 스키마, 등록표, 메시지 계약 v2, 자료 입력 경로. 이 단계가 끝나야 US1~US4를 시작할 수 있다.

- [ ] T003 [BE] 요청/실행 분리 마이그레이션 V3와 도메인 모델·JPA 엔티티 (`backend/src/main/resources/db/migration/V3__backtest_run_request.sql`, `backtest/domain/model/{BacktestRequest,BacktestRun,RunStatus,ResultSource,ExecutionType}.java`, `backtest/infrastructure/persistence/{BacktestRequestJpaEntity,BacktestRunJpaEntity,*Mapper,*Repository}.java`)
  - data-model.md "마이그레이션 V3 요약" 1~3: `backtest_job`→`backtest_run` 개명·컬럼 추가, `backtest_request` 생성, `backtest_result`/`backtest_trade` FK를 `run_id`로, JSONB 열(`weights_series` `costs` `liquidation` `analytics` `exceptions` `versions`) 추가
  - 부분 유니크 인덱스 `UNIQUE (dedup_key) WHERE status IN ('QUEUED','RUNNING','COMPLETED')`
  - 기존 DEMO 데이터 처리: `requester_id NOT NULL`을 위해 기존 행 삭제(plan 권장) — MR 리뷰에서 확정
  - `RunStatus`에 `CANCELLED`, `isFinal()`; `BacktestRun` 전이 `markRunning(executionId)`(attempt+1), `markCompleted/markFailed/markCancelled`, `recordProgress(...)`; `BacktestRequest.cancel()`
  - 기존 `BacktestJob*Test`를 Run/Request 테스트로 교체, `FlywayMigrationTest`·`ArchitectureTest` 통과
- [ ] T004 [P] [BE] 비용 조건·전략 규칙 등록표 테이블과 조회 서비스 (`backend/src/main/resources/db/migration/V4__cost_profile_strategy_rules.sql`, `backtest/domain/model/{CostProfile,StrategyRules,RulesStatus}.java`, `backtest/application/service/{CostProfileService,StrategyRulesService}.java`, `backtest/infrastructure/persistence/{CostProfileJpaEntity,StrategyRulesJpaEntity,…}.java`)
  - 초기 행: `PROVISIONAL-DEMO-1 (0,0,5,0,null,provisional=true, 'ADR-0004 데모 값 재사용, PD-07 확정 전')`; `FIXED_WEIGHT/1`·`EQUAL_WEIGHT/1`·`INVERSE_VOLATILITY/1{lookbackDays:63, zeroVolatility:"EXCLUDE_THEN_ALLOCATE", weightCap:"1.0000", investedFraction:"1.0000"}` REGISTERED; `SECTOR_MOMENTUM/PENDING`·`MINIMUM_VARIANCE/PENDING` PENDING_DECISION(decision_ref PD-03·PD-04) (R-07, R-08)
  - `StrategyRulesService.requireRegistered(strategyId, version)` → 없거나 PENDING이면 `RulesNotRegisteredException`(422 `RULES_NOT_REGISTERED`); `ErrorCode`에 `RULES_NOT_REGISTERED`, `INSUFFICIENT_HISTORY`, `EXECUTION_LIMIT`, `NOT_CANCELLABLE`, `NOT_RETRYABLE` 추가
- [ ] T005 [P] [BE] 메시지 계약 v2 레코드와 RabbitMQ 토폴로지(quorum 큐·제어 exchange) (`backtest/application/port/message/*.java`, `backtest/infrastructure/messaging/BacktestRabbitConfig.java`, `backtest/infrastructure/messaging/RabbitBacktestJobPublisher.java`, `backtest/infrastructure/messaging/RabbitBacktestControlPublisher.java`, `backend/src/test/.../BacktestMessageRoundTripTest.java`)
  - `EngineInput` v2(`run{runId,effectiveStart,…}`, `universe.items`, `marketData.dataVersion/fillPrice/signalPrice/distributions`, `allocation{strategyId,rulesVersion,rules,parameters}`, `execution.costProfile`, `evaluation`, `progress`), `benchmark` 제거; `BacktestEvent` v2 payload(`StartedPayload.stage/resource`, `ProgressPayload.stage/lastTradingDate`, `FinishedPayload.status/partial/lastTradingDate/resource`); `ControlMessage{type,runId,requestedAt}`; `schemaVersion=2`
  - `BacktestRabbitConfig`: `backtest.jobs`를 `QueueBuilder.durable().quorum().deliveryLimit(1).deadLetterExchange(...)`, `FanoutExchange backtest.control` 추가, 상수 `CONTROL_EXCHANGE`
  - 작업 메시지 `message_id="JOB-"+runId`, `correlation_id=runId`
  - 라운드트립 테스트가 T001의 v2 픽스처를 읽어 통과. `BacktestRabbitConfigTest` 갱신
- [ ] T006 [P] [BE] 워커 메시지 계약 v2 pydantic 모델과 픽스처 테스트 (`worker/src/worker/contracts/{engine_input,engine_output,events,control}.py`, `worker/src/worker/messaging.py`, `worker/tests/test_contracts.py`, `worker/tests/test_rabbitmq.py`)
  - T005와 같은 필드. `SCHEMA_VERSION = 2`, `CONTROL_EXCHANGE = "backtest.control"`; `test_rabbitmq.py`가 Java 상수와 동일함을 계속 검증
  - `EngineOutput` v2 필드(`weightsSeries`, `weightsSource`, `costs`, `liquidation`, `analytics`, `exceptions`, `versions`, `run.partial/lastTradingDate`, `diagnostics.resource`)
  - `strategy_params/`의 `MA_CROSSOVER` 모델 삭제(구현 없음), 다섯 전략 파라미터 모델 추가
- [ ] T007 [P] [BE] PostgreSQL 시장 자료 공급자와 3종목 24개월 CSV 픽스처 (`worker/src/worker/market_data.py`, `worker/src/worker/config.py`, `worker/examples/three_etf_24m/{prices_raw.csv,prices_adjusted.csv,distributions.csv,splits.csv,instruments.csv,mapping.csv}`, `worker/tests/test_market_data.py`)
  - `PostgresMarketDataProvider(url)`: contracts/market-data-input.md 열을 읽어 원시 OHLC·조정 종가·분배금·분할·상장/폐지일·`data_version` 반환. 읽기 전용, 종목 ≤50·기간 한 번에 조회
  - `CsvMarketDataProvider`가 같은 스키마의 픽스처 디렉터리를 읽음(`versions.data = "FIXTURE-three_etf_24m"`). 픽스처에 분배금 2건·거래 정지 1일·상장일이 다른 종목 1개 포함(quickstart S8)
  - 결측 거래일 → `DataInsufficientError`(보간 금지), 기존 `validate_prices()` 재사용. `YFinanceDataProvider`는 `worker/scripts/`로 이동해 실행 경로에서 제외(R-09)

**Checkpoint**: 스키마·등록표·계약 v2·자료 경로가 준비되어 스토리 구현을 시작할 수 있다.

---

## Phase 3: User Story 1 - 분석을 요청하면 즉시 접수되고 진행 상태가 보인다 (Priority: P1) 🎯 MVP

**Goal**: v2 요청을 접수해 식별자·대기 상태를 즉시 돌려주고, 대기·실행·완료·실패·취소 상태와 진행 정도(단계·처리한 마지막 거래일·비율)를 조회할 수 있다. 완료 결과에 참조 버전이 기록된다.

**Independent Test**: quickstart S1. `POST /backtests` 직후 `202 {backtestId, status: QUEUED, resultSource: NEW, effectiveStart}`, 폴링하면 `progress.stage`·`lastTradingDate`가 바뀌고 `COMPLETED`에서 `versions`가 채워진다. 실패 시 `failure.code`가 보이고 `summary=null`.

- [ ] T008 [US1] [BE] 분석 요청 접수 API v2: 요청 DTO·검증·동일 버전 키·실행 생성 (`backtest/presentation/request/BacktestJobRequest.java`, `backtest/presentation/response/BacktestJobResponse.java`, `backtest/presentation/controller/BacktestController.java`, `backtest/application/service/{BacktestService,DedupKeyCalculator,EffectivePeriodCalculator,EngineInputAssembler}.java`, `backend/src/test/.../{DedupKeyCalculatorTest,EffectivePeriodCalculatorTest,BacktestServiceTest,BacktestControllerTest}.java`)
  - 요청 본문 contracts/rest-api.md 구조(`executionType`, `sectorAllocation`, `strategy`, `conditions`); 검증: `targetWeight`는 FIXED_WEIGHT만, 합=1, 상한 합계 ≥ 투자 비중, 식별자 패턴, `strategy_rules` REGISTERED(T004), 워밍업 가용 기간(R-15 → 422 `INSUFFICIENT_HISTORY`)
  - `DedupKeyCalculator`: canonical JSON(`ORDER_MAP_ENTRIES_BY_KEYS`, BigDecimal plain string, null 제거) → SHA-256 hex(R-03). 요청자·executionType 제외를 테스트로 고정
  - `BacktestService.create()`: 이 Task에서는 `NEW` 경로만(키로 조회 → 없으면 run 생성 + request 생성 + 발행, 한 트랜잭션). JOINED/REUSED 분기는 T017
  - `EngineInputAssembler` v2: 대응표·등록 규칙 사본·비용 프로필·`dataVersion`(T007 계약의 최신 버전)·`effectiveStart`·seed 42
  - 202 응답에 `resultSource`, `effectiveStart/End`, `versions`
- [ ] T009 [US1] [BE] 상태·진행 조회 v2와 이벤트 처리(STARTED attempt·PROGRESS 저장·FINISHED 버전 기록) (`backtest/presentation/response/BacktestDetailResponse.java`, `backtest/application/service/{BacktestEventService,BacktestResultQueryService}.java`, `backtest/infrastructure/messaging/BacktestEventListener.java`, `backend/src/test/.../{BacktestEventServiceTest,BacktestEventFlowIT,BacktestResultApiIT}.java`)
  - `GET /backtests/{id}`: 요청의 파생 상태(`cancelled_at` → CANCELLED, 아니면 run.status), `progress{stage,lastTradingDate,completedTradingDays,totalTradingDays}`, `queueWaitSeconds`, `partial`, `resultSource`, `versions`, `failure{code,message}`, `summary`(핵심 숫자 3개·liquidation·costs·analytics 일부·exceptionCount). 타인 요청은 404
  - `handleStarted`: QUEUED→RUNNING, `attempt+1`, `current_execution_id`, `progress_stage=PREPARING_DATA`; 이미 RUNNING이고 executionId가 다르면 attempt만 +1(자동 재시도)
  - `handleProgress`: 트랜잭션으로 `progress_*` 저장(`LoggingBacktestProgressNotifier`·`BacktestProgressNotifier` 삭제)
  - `handleFinished(COMPLETED|FAILED)`: 결과 저장(v2 JSONB 열 포함), `versions` 기록, `failure_code` 매핑. CANCELLED 처리는 T018
  - 종결 상태 이후 이벤트 무시 규칙 유지
- [ ] T010 [P] [US1] [BE] 워커 소비자 v2: run 식별자·단계 이벤트·자원 사용 보고·결정적 오류 응답 (`worker/src/worker/consumer.py`, `worker/src/worker/rabbitmq.py`, `worker/src/worker/engine_runner.py`, `worker/tests/test_consumer.py`)
  - STARTED `{stage: PREPARING_DATA, resource{pid,hostname}}`, PROGRESS `{stage: COMPUTING, lastTradingDate, …}` 20거래일 배치 유지, FINISHED `{status, partial, lastTradingDate, engineOutput, resource{peakRssMb,cpuSeconds,wallSeconds}}`(`resource.getrusage`)
  - `INVALID_REQUEST`·`DATA_INSUFFICIENT`·`CONSTRAINT_VIOLATION`·`ESTIMATION_FAILED`는 FINISHED(FAILED)로 응답 후 ACK(재시도 없음, R-06); 프로세스 예외는 그대로 전파해 unacked 상태로 죽게 둔다(브로커 재전달)
  - `engine_support.py`의 단일 종목·벤치마크 제한을 v2 기준(`fillPrice=RAW`, `signalPrice=ADJUSTED`, 규칙 사본 필수)으로 교체

**Checkpoint**: 다섯 전략 중 등록된 세 전략(FIXED/EQUAL/INVERSE_VOL)이 아직 없으면 US1은 기존 단일 종목 경로로 접수·상태 흐름만 검증한다. US2 완료 후 S1을 전략 실행으로 재검증한다.

---

## Phase 4: User Story 2 - 다섯 전략이 당시 실행 가능한 규칙으로 계산된다 (Priority: P1)

**Goal**: Nautilus 위에서 다종목 월말 리밸런싱(판단일 bar-close 자료·다음 거래일 원시 시가 체결·정수 수량·잔여 현금·거래 정지 이월·분배금 1회 반영·비용 버전·공통 워밍업·청산 결과)을 구현하고, 등록된 세 배분 규칙을 계산한다. 미등록 두 전략(PD-03·04)은 거부한다. 결과는 FR-027 항목을 모두 담는다.

**Independent Test**: quickstart S8·S9·S7. 3종목 24개월 픽스처로 세 전략을 실행해 대표 거래일 3개의 체결일·수량·현금·비용·분배금이 `manual_reconciliation.json`과 정확히 일치하고, 모든 체결일 = 판단일 다음 거래일, 거래 정지일 이월 `reason=HALT_DEFERRED`, 결과 시작일 = 워밍업 이후. `SECTOR_MOMENTUM` 요청은 422. 같은 입력 두 번 실행 시 EngineOutput 바이트 동일.

- [ ] T011 [US2] [BE] 다종목 포트폴리오 리밸런싱 Nautilus 전략(정수 수량·잔여 현금·다음 거래일 시가·거래 정지 이월·워밍업·청산) (`worker/src/worker/nautilus_engine.py`, `worker/src/worker/portfolio_execution.py`, `worker/tests/test_portfolio_execution.py`)
  - `PortfolioRebalanceExecution(NautilusStrategy)`: 월말 판단일 `on_bar`(마지막 종목 bar 도착 후)에서 배분 모듈(T013)에 조정 종가 이력 전달 → 목표 비중 → 현금·현재가 기준 정수 수량 주문 생성 → 다음 거래일 시가 QuoteTick에 시장가 체결(현행 경계 유지). 공매도·레버리지 불가, 매도 먼저 매수 뒤 순서 고정(결정성)
  - 거래 정지·거래 불가(halted 또는 volume 0)면 그 종목 주문을 다음 거래 가능일로 미루고 `reason=HALT_DEFERRED, plannedDate` 기록
  - 워밍업: `evaluation.warmupTradingDays`만큼 신호 계산 전 자료를 소비하고 `effectiveStart`부터 성과 집계
  - 종료일: 시가평가 결과와 청산(전량 매도, 비용 포함) 결과를 둘 다 산출(`liquidation`)
  - 비중 오차·잔여 현금을 `weightsSeries[].weightError`·`portfolioSeries[].cash`로 기록(재정규화 금지)
- [ ] T012 [P] [US2] [BE] 기업행위(분배금 1회 현금 반영·분할 수량 조정)와 비용 프로필 적용 (`worker/src/worker/corporate_actions.py`, `worker/src/worker/costs.py`, `worker/tests/test_corporate_actions.py`, `worker/tests/test_costs.py`)
  - 분배금: 보유 중 `pay_date`에 `amount_per_share × 보유 수량` 현금 유입 1회, `portfolioSeries[].cashFlows[{type: DISTRIBUTION}]`. ETF 내부 보수 미차감(FR-013)
  - 비용: `costProfile`의 commission·spread·slippage·tax bps를 체결마다 적용해 `trades[].detail`과 `costs{…,total}` 합계. 현행 하드코딩 `SLIPPAGE_BPS=5` 제거, 프로필에서만 읽음
  - `escalation`(비용 상승 시나리오)은 `scenario=COST_ESCALATION`일 때만 적용, 미정의면 `INVALID_REQUEST`
- [ ] T013 [P] [US2] [BE] 배분 규칙 모듈(고정·동일·역변동성)과 규칙 등록 확인 (`worker/src/worker/allocation/{__init__,base,fixed,equal,inverse_vol}.py`, `worker/src/worker/rules_registry.py`, `worker/tests/test_allocation.py`)
  - `compute_weights(history: DataFrame, params, rules) -> AllocationResult{weights, source: USER|COMPUTED, exceptions[]}` 순수 함수. `FIXED_WEIGHT`: 사용자 비중 그대로(`source=USER`); `EQUAL_WEIGHT`: 1/N; `INVERSE_VOLATILITY`: `rules.lookbackDays`(63) 일별 수익률 표준편차 역수 비례, 0 변동성 종목은 `rules.zeroVolatility=EXCLUDE_THEN_ALLOCATE`대로 제외 후 나머지 배분 + `exceptions[{kind: ZERO_VOLATILITY}]`, `weightCap` 초과분 재분배 불가 시 `CONSTRAINT_VIOLATION` 예외 기록
  - `rules_registry.py`: EngineInput의 `allocation.rules`만 신뢰. `strategyId`가 `SECTOR_MOMENTUM`·`MINIMUM_VARIANCE`이거나 `rules`가 비어 있으면 `INVALID_REQUEST("RULES_NOT_REGISTERED")`. 두 전략의 모듈 파일은 만들지 않는다(PD-03·04 확정 후 별도 Task)
- [ ] T014 [US2] [BE] 성과·분석 지표 확장(핵심 숫자 3개·비용 합계·청산 결과·회복 기간·회전율·상관·위험/손실 기여·예외 기록·버전) (`worker/src/worker/performance.py`, `worker/src/worker/engine_runner.py`, `worker/tests/test_performance.py`)
  - `performance{…, netTotalReturn, maxDrawdown, totalTradingCost}`, `analytics{recoveryDays, turnover, correlationMatrix, riskContribution, lossContribution, weightError, method:"sample-covariance-daily"}`, `exceptions[]` 집계, `versions{mapping, rules, costProfile(provisional 포함), data, engine:"nautilus_trader 1.219.0", worker}` 채움
  - 부분 결과(취소·한도)는 `lastTradingDate`까지의 시계열로 같은 함수가 계산(T019가 호출)
- [ ] T015 [US2] [BE] 결정성 테스트와 수기 대조 테스트(SC-002·SC-003) (`worker/tests/test_determinism.py`, `worker/tests/test_manual_reconciliation.py`, `worker/tests/fixtures/manual_reconciliation.json`, `worker/tests/fixtures/engine-input-three-etf-{fixed,equal,inverse_vol}.json`)
  - 결정성: 같은 EngineInput 두 번 실행 → `EngineOutput.to_json()` 바이트 동일(세 전략 각각)
  - 수기 대조: 스프레드시트로 계산한 대표 거래일 3개(첫 리밸런싱·분배금 지급일·거래 정지 이월일)의 체결일·수량·현금·비용·분배금을 JSON으로 고정하고 허용 오차 0으로 비교(PD-12 확정 시 상수만 변경). 스프레드시트 원본은 `docs/specs/backtest-messaging/examples/reconciliation/`에 CSV로 보관
  - 검증 항목: 모든 체결일 = 판단일 다음 거래일(SC-004), 정수 수량, 결과 시작일 = effectiveStart
- [ ] T016 [US2] [BE] 결과 v2 저장·조회: 비중 시계열·예외 기록·거래 사유·현금 흐름 API (`backtest/application/service/BacktestResultQueryService.java`, `backtest/presentation/controller/BacktestController.java`, `backtest/presentation/response/{BacktestWeightsResponse,BacktestExceptionsResponse,BacktestTimeseriesResponse,BacktestTradePageResponse}.java`, `backtest/infrastructure/persistence/BacktestResultJpaEntity.java`, `backend/src/test/.../BacktestResultApiIT.java`)
  - FINISHED payload의 v2 필드를 `backtest_result` JSONB 열에 저장(T009의 저장 경로 확장)
  - `GET /backtests/{id}/weights`(`weightsSource` 포함), `GET /backtests/{id}/exceptions`, `/trades` 항목 `reason`·`plannedDate`, `/timeseries` 항목 `cashFlows`
  - 409 규칙 유지(`RESULT_NOT_READY`, `BACKTEST_FAILED`); CANCELLED는 200 + `partial=true`(T018 이후 활성)

**Checkpoint**: 세 전략이 픽스처에서 수기 계산과 일치하고 결정적이다. US1 S1을 실제 전략으로 재검증한다.

---

## Phase 5: User Story 3 - 같은 조건은 재사용하고, 실패는 재시도하며, 오래 걸리면 취소한다 (Priority: P2)

**Goal**: 동일 버전 완료 결과 재사용(REUSED)·진행 중 합류(JOINED), 요청 단위 취소와 마지막 요청 취소 시 실행 정지·부분 결과, 실패·취소 후 재시도, 시스템 장애 1회 자동 재시도.

**Independent Test**: quickstart S2~S6. 같은 본문 재요청이 `REUSED`, RUNNING 중 재요청이 `JOINED`, A 취소 후 B 유지, B 취소 시 `CANCELLED partial=true`와 부분 시계열 200, FAILED 후 retry 202, 워커 `kill -9` 1회는 완료·2회는 `SYSTEM_FAILURE`.

- [ ] T017 [US3] [BE] 동일 버전 재사용·진행 중 합류 분기와 재조회 기록 (`backtest/application/service/BacktestService.java`, `backtest/domain/model/BacktestReuseEvent.java`, `backtest/infrastructure/persistence/{BacktestReuseEventJpaEntity,…}.java`, `backend/src/main/resources/db/migration/V5__backtest_reuse_event.sql`, `backend/src/test/.../{BacktestServiceTest,BacktestDedupIT}.java`)
  - `create()`: 키로 활성 run 조회 → COMPLETED면 request `REUSED`(status COMPLETED, 발행 없음) / QUEUED·RUNNING이면 `JOINED`(발행 없음) / 없으면 `NEW`. 부분 유니크 위반(동시 접수 경합) 시 재조회해 JOINED로 전환
  - `backtest_reuse_event(run_id, request_id, kind, at)` 기록(FR-008)
  - IT: 두 사용자·같은 본문 → run 1개, request 2개, reuse_event 1개
- [ ] T018 [US3] [BE] 취소 API와 취소 전파(마지막 활성 요청 규칙·제어 메시지·FINISHED(CANCELLED) 처리) (`backtest/presentation/controller/BacktestController.java`, `backtest/application/service/{BacktestCancelService,BacktestEventService}.java`, `backtest/infrastructure/messaging/RabbitBacktestControlPublisher.java`, `backend/src/test/.../{BacktestCancelServiceTest,BacktestCancelFlowIT}.java`)
  - `POST /backtests/{id}/cancel`: 요청자 본인만, 종결이면 409 `NOT_CANCELLABLE`; `request.cancelled_at` 기록 → 같은 run에 활성 요청이 없으면 `run.cancel_requested_at` 기록, QUEUED면 즉시 CANCELLED, RUNNING이면 `backtest.control`에 `CANCEL{runId}` 발행
  - `handleFinished(CANCELLED)`: run CANCELLED·`partial=true`·`progress_last_trading_date`, 부분 결과 저장
  - `handleStarted`에서 run이 이미 CANCELLED면 CANCEL 재발행(R-04 대기 중 취소)
  - 응답에 `cancelRequested: true`(제어 발행 후 종결 전)
- [ ] T019 [P] [US3] [BE] 워커 제어 채널 구독과 거래일 단위 협력 취소·부분 결과 발행 (`worker/src/worker/control.py`, `worker/src/worker/rabbitmq.py`, `worker/src/worker/consumer.py`, `worker/src/worker/portfolio_execution.py`, `worker/tests/test_control.py`)
  - 시작 시 `backtest.control`에 exclusive·auto-delete 큐 바인딩, 별도 스레드가 `CANCEL{runId}`를 받아 `threading.Event` 맵에 표시(스레드 안전)
  - `PortfolioRebalanceExecution.on_bar`에서 자기 run의 취소 표시를 확인하면 `self.stop()`; 소비자는 `lastTradingDate`까지의 부분 `EngineOutput`(T014 함수)으로 `FINISHED{status: CANCELLED, partial: true}` 발행 후 ACK
  - 모르는 runId는 무시. 이미 끝난 run의 CANCEL도 무시
- [ ] T020 [US3] [BE] 재시도 API와 DLQ 소비(시스템 장애 실패 종결·자동 재시도 기록) (`backtest/presentation/controller/BacktestController.java`, `backtest/application/service/BacktestRetryService.java`, `backtest/infrastructure/messaging/BacktestDeadLetterListener.java`, `backend/src/test/.../{BacktestRetryServiceTest,BacktestDeadLetterListenerIT}.java`)
  - `POST /backtests/{id}/retry`: 원 요청이 FAILED·CANCELLED일 때만(아니면 409 `NOT_RETRYABLE`), 같은 canonical 요청으로 `create()` 재호출(동일 버전 규칙 적용 → NEW/JOINED/REUSED), `retry_of_request_id` 기록, 202 `{backtestId, status, resultSource, retryOf}`
  - `@RabbitListener(queues = "backtest.jobs.dlq")`: 메시지의 runId로 run을 `FAILED(SYSTEM_FAILURE)` 종결(이미 종결이면 무시), 합류 요청 모두 FAILED로 보임
  - IT(Testcontainers RabbitMQ): quorum 큐 `x-delivery-limit=1` 선언 확인, 소비자가 nack-requeue 두 번 → DLQ 도착 → FAILED
- [ ] T021 [US3] [INFRA] quorum 큐 전환 실행과 워커 다중 인스턴스 장애 시나리오 확인 (`infra/scripts/migrate-jobs-queue.sh`, `infra/docker-compose.prod.yml`, `docs/guides/backtest-queue-migration.md`)
  - 개발 환경에서 T002 스크립트로 전환 후 `rabbitmqctl list_queues name type arguments`로 quorum·delivery-limit 확인
  - quickstart S6: 워커 2 replica에서 RUNNING 중 한 프로세스 `kill -9` → 다른 워커가 재실행·완료, 두 번째 kill → DLQ → FAILED(SYSTEM_FAILURE). 결과를 가이드에 기록
  - 운영 배포 절차에 1회 실행 단계 추가(미ACK 0 확인 조건)

**Checkpoint**: S2~S6 통과. 재사용·합류·취소·재시도·자동 재시도가 모두 상태 모델대로 동작한다.

---

## Phase 6: User Story 4 - 실행 한도를 실측 근거로 정한다 (Priority: P2)

**Goal**: 실행 유형별 대기 포함 소요·동시 처리 수·자원 사용·실패·자동 재시도·건당 원가(원단위)를 기록하고 p50/p95·실패율을 운영자가 조회한다. 상한 값은 정하지 않는다(PD-06).

**Independent Test**: quickstart S10. S1~S6 뒤 `GET /internal/execution-metrics?type=SINGLE`이 `count ≥ 6`, 0보다 큰 `failRate`·`cancelRate`·`autoRetryRate`, `waitP50Ms ≤ waitP95Ms`, `reuseCount ≥ 1`을 돌려주고 `v_run_metric_summary`와 값이 같다.

- [ ] T022 [US4] [BE] 실행 측정 기록·집계 뷰·운영자 조회 API (`backend/src/main/resources/db/migration/V6__backtest_run_metric.sql`, `backtest/application/service/ExecutionMetricRecorder.java`, `backtest/application/service/BacktestEventService.java`, `backtest/presentation/controller/ExecutionMetricsController.java`, `backtest/presentation/response/ExecutionMetricsResponse.java`, `backend/src/test/.../{ExecutionMetricRecorderTest,ExecutionMetricsApiIT}.java`)
  - `backtest_run_metric` 테이블(data-model)과 뷰 `v_run_metric_summary`(`percentile_cont(0.5/0.95)` 대기·소요, fail/cancel/auto-retry rate, `reuse_count`는 `backtest_reuse_event` 조인)
  - 종결 이벤트(COMPLETED·FAILED·CANCELLED·DLQ)마다 `ExecutionMetricRecorder.record(run)`: `wait_ms`, `run_ms`, `concurrent_running`(종결 시점 RUNNING 수), `peak_rss_mb`·`cpu_seconds`(FINISHED resource), `auto_retried = attempt > 1`, `result_bytes`
  - `GET /internal/execution-metrics?type=&from=&to=` 운영자 권한, 뷰 행 그대로 반환. 사용자 화면 노출 없음(FR-008 결제 단계 없음)
  - 원가는 원단위(cpu·메모리·바이트)만 기록. 금액 단가·상한 값은 넣지 않는다(PD-06·D19)

**Checkpoint**: 측정 기록이 쌓이고 PD-06 결정에 쓸 뷰가 있다.

---

## Phase 7: Polish & Cross-Cutting

- [ ] T023 [P] [DOCS] ADR-0005(요청/실행 분리·quorum 큐·제어 채널)와 README·CLAUDE.md 현재 구현 상태 갱신 (`docs/adr/0005-backtest-request-run-split.md`, `README.md`, `worker/CLAUDE.md`, `backend/CLAUDE.md`, `docs/specs/101-nautilus-momentum.md`)
  - ADR에 R-02·R-04·R-06 결정과 버린 대안 기록; README "현재 구현 상태"를 다종목·다섯 전략(세 개 실행 가능, 두 개 PD 대기)로 갱신; `101-nautilus-momentum.md`를 포트폴리오 엔진 계약으로 개정 또는 폐기 표시
- [ ] T024 [P] [BE] 레거시 제거와 워커 문서 정리 (`worker/src/worker/strategies/momentum.py`, `worker/src/worker/strategies/__init__.py`, `worker/src/worker/engine_support.py`, `worker/examples/momentum_fixture.csv`, `worker/tests/test_momentum_engine.py`, `worker/tests/test_engine_support.py`, `worker/pyproject.toml`)
  - 단일 종목 `MomentumStrategy`·`STRATEGIES` 레지스트리·yfinance 의존(`yfinance` 제거)·v1 전용 테스트 삭제. `psycopg`는 T007에서 사용하므로 유지, `SQLAlchemy` 미사용이면 제거
- [ ] T025 [DOCS] quickstart S1~S10 전체 실행과 동작 확인 기록 (`specs/004-backtest-execution/quickstart.md`, `docs/guides/backtest-execution-verification-2026-09.md`)
  - 각 시나리오의 실제 응답·DB 값·소요 시간을 기록. 통과하지 못한 항목은 실패로 남기고 원인 Task를 연다. "구현 완료"만 보고하고 사용성·효과 입증은 별도임을 명시(constitution 완료의 정의)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (T001, T002)**: 의존 없음. T001이 끝나면 T005·T006 픽스처 테스트가 가능하다.
- **Phase 2 (T003~T007)**: T003이 스키마 기준이라 T004·T005는 T003 병합 뒤 리베이스한다(마이그레이션 번호 V4·V5 순서). T006·T007은 워커 쪽이라 T003과 독립.
- **Phase 3 (US1)**: T008·T009는 T003·T004·T005 뒤. T010은 T006 뒤.
- **Phase 4 (US2)**: T011은 T007·T010 뒤. T012·T013은 T006 뒤 병렬. T014는 T011~T013 뒤. T015는 T014 뒤. T016은 T009 뒤(워커 v2 출력이 있어야 IT 가능하므로 T014 뒤 병합 권장).
- **Phase 5 (US3)**: T017은 T008 뒤. T018은 T009·T005 뒤. T019는 T010·T011 뒤(부분 결과 계산은 T014). T020은 T017·T005 뒤. T021은 T002·T020 뒤.
- **Phase 6 (US4)**: T022는 T009·T017·T020 뒤(모든 종결 경로가 있어야 기록 가능).
- **Phase 7**: T023·T024는 US2 완료 뒤 언제든. T025는 모든 스토리 뒤.

### User Story Dependencies

- **US1**: Foundational 뒤 시작. 다른 스토리에 의존하지 않지만, 실제 전략 실행 검증은 US2 뒤에 다시 한다.
- **US2**: Foundational 뒤 시작. 워커 중심이라 US1 백엔드 작업과 병렬 가능.
- **US3**: US1(접수·이벤트 처리)과 US2(부분 결과 계산)에 의존.
- **US4**: US1·US3의 종결 경로에 의존.

### Parallel Opportunities

- Phase 1: T001 ∥ T002
- Phase 2: T003 → (T004 ∥ T005) 와 동시에 (T006 ∥ T007)
- Phase 3·4 동시 진행: 백엔드 T008→T009, 워커 T010→T011, 그리고 T012 ∥ T013
- Phase 5: T017 ∥ T018 (백엔드 두 사람) ∥ T019 (워커)
- Phase 7: T023 ∥ T024

## Parallel Example: Phase 2

```text
BE(Java):   T003 ──▶ T004 ─┐
                   └─▶ T005 ─┤
BE(worker): T006 ──────────────┼─▶ Phase 3·4 시작
            T007 ──────────────┘
DOCS/INFRA: T001, T002 (선행·병렬)
```

## Implementation Strategy

### MVP First (US1 + 등록된 세 전략)

1. Phase 1·2 완료.
2. T008·T009·T010으로 접수·상태 흐름을 열고, T011~T016으로 세 전략 실행·결과 저장.
3. quickstart S1·S7·S8·S9로 검증 후 데모. 이 시점에 사용자는 세 전략을 접수·조회할 수 있고 두 전략은 "팀 결정 대기"로 거부된다.

### Incremental Delivery

1. US3(T017~T021)로 재사용·취소·재시도·자동 재시도 추가 → S2~S6.
2. US4(T022)로 측정 → S10. 이 뷰가 PD-06 결정 자료다.
3. PD-03·PD-04가 확정되면 별도 Task로 `allocation/momentum.py`·`allocation/min_variance.py`와 `strategy_rules` 행 갱신을 추가한다(이 tasks.md 범위 밖).

### Team Split

- BE(Java 백엔드): T003 → T004/T005 → T008 → T009 → T016 → T017/T018 → T020 → T022
- BE(Python 워커): T006/T007 → T010 → T011 → T012/T013 → T014 → T015 → T019 → T024
- INFRA/DOCS: T001, T002 → T021 → T023 → T025

## Notes

- 스토리 안에서 모델 → 서비스 → 엔드포인트 순서를 지키되, 한 Task가 그 셋을 함께 담는 것이 MR 단위로 맞다.
- PD-03·04·06·07 값은 어느 Task에서도 정하지 않는다. 등록표 행·상수 자리만 만든다.
- 각 Task 병합 전 AI 코드리뷰(CLAUDE.md 규칙)와 R-16 검증 명령 통과가 필요하다.
- 기존 DEMO 데이터는 V3에서 폐기 전제다. 보존이 필요하면 T003 리뷰에서 마이그레이션 방식을 바꾼다.
