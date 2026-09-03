# Tasks: 백테스트 결과 조회 API (US1·US2)

**Input**: Design documents from `specs/003-backtest-result-analysis/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: plan.md가 도메인·서비스·컨트롤러·RepositoryImpl 테스트를 명시적으로 요구하므로 테스트 태스크를 포함한다.
테스트 태스크는 구현 태스크와 **같은 Phase**에 둔다. 기존 테스트 스타일(`@DisplayName` 한글, `@MockitoBean`, `@ExtendWith(MockitoExtension)`)을 따른다.

**Organization**: Phase = 커밋 단위. 각 Phase 끝의 Checkpoint에서 `cd backend && ./gradlew spotlessApply build`를 통과해야 다음 Phase로 간다.

**범위**: US1(핵심 지표)·US2(자산 가치 시계열)만. US3·US4 태스크는 만들지 않는다. `worker/`·`frontend/`는 수정하지 않는다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완료 태스크에 의존하지 않음)
- **[Story]**: US1 / US2. Setup·Foundational·시더·Polish에는 붙이지 않는다
- 경로는 저장소 루트 기준 실제 경로

## Path Conventions

- 메인: `backend/src/main/java/com/ssafy/thorp/backtest/{presentation|application|domain|infrastructure}/...`, `backend/src/main/java/com/ssafy/thorp/common/...`
- 테스트: `backend/src/test/java/com/ssafy/thorp/...` (메인과 같은 패키지 구조)
- 계층 규칙: `domain/`에 Spring·JPA·Jackson import 금지. `@Entity`는 `infrastructure/persistence`에만. 컨트롤러 DTO(`request`/`response`)를 서비스 시그니처에 쓰지 않는다

---

## Phase 1: Setup (공통 코드 준비)

**Purpose**: 모든 Phase가 의존하는 `common/` 변경. 신규 의존성·프로젝트 초기화는 없다.

- [X] T001 `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java`에 `RESULT_NOT_READY(HttpStatus.CONFLICT, "백테스트 결과가 아직 준비되지 않았습니다.")` 추가 (`RESOURCE_NOT_FOUND` 다음 위치). Javadoc에 "COMPLETED가 아닌 Job의 결과 조회" 용도 명시
- [X] T002 `backend/src/test/java/com/ssafy/thorp/common/web/advice/GlobalExceptionHandlerTest.java`에 `RESULT_NOT_READY` BusinessException → 409 + `error.code == "RESULT_NOT_READY"` 케이스 추가 (`ThrowingController`에 `/test/not-ready` 엔드포인트 추가)

**Checkpoint**: `./gradlew spotlessApply build` 통과

---

## Phase 2: Foundational (도메인 모델·저장소, 모든 스토리의 전제)

**Purpose**: `BacktestJob.initialCash` 추가, `BacktestResult` 도메인·VO, `backtest_result` 저장소. US1·US2 모두 이 위에 얹힌다.

**⚠️ CRITICAL**: 이 Phase가 끝나기 전에는 US1·US2 작업을 시작하지 않는다.

### 2-A. `BacktestJob`에 `initialCash` 추가 (기존 코드·테스트 갱신)

- [X] T003 `backend/src/main/java/com/ssafy/thorp/backtest/domain/model/BacktestJob.java`에 `private final long initialCash` 추가. `create(String strategy, BacktestPeriod period, long initialCash)`(`initialCash <= 0`이면 `IllegalArgumentException("initialCash must be positive")`), `restore(Long id, String strategy, BacktestPeriod period, long initialCash, BacktestStatus status)`, `getInitialCash()` 추가. 기존 2-인자 `create`·4-인자 `restore`는 제거
- [X] T004 `backend/src/main/java/com/ssafy/thorp/backtest/application/command/CreateBacktestCommand.java`에 `long initialCash` 컴포넌트 추가 (`strategy, startDate, endDate, initialCash`)
- [X] T005 `backend/src/main/java/com/ssafy/thorp/backtest/presentation/request/BacktestJobRequest.java`에 `@Positive Long initialCash`(nullable) 추가. 상수 `DEFAULT_INITIAL_CASH = 10_000_000L`과 `long initialCashOrDefault()` 메서드 추가. Javadoc에 "생략 시 10,000,000 KRW" 명시
- [X] T006 `backend/src/main/java/com/ssafy/thorp/backtest/infrastructure/persistence/BacktestJobJpaEntity.java`에 `@Column(name = "initial_cash", nullable = false) @ColumnDefault("10000000") private long initialCash` 추가(`org.hibernate.annotations.ColumnDefault`), 생성자·getter 갱신. `backend/src/main/java/com/ssafy/thorp/backtest/infrastructure/persistence/BacktestJobMapper.java`의 `toEntity`/`toDomain`에 `initialCash` 전달
- [X] T007 `backend/src/main/java/com/ssafy/thorp/backtest/application/service/BacktestService.java`의 `create`에서 `BacktestJob.create(command.strategy(), new BacktestPeriod(...), command.initialCash())`로 변경. `backend/src/main/java/com/ssafy/thorp/backtest/presentation/controller/BacktestController.java`의 `create`에서 `new CreateBacktestCommand(request.strategy(), request.startDate(), request.endDate(), request.initialCashOrDefault())`로 변경 (컴파일 유지용 최소 수정, 응답 확장은 US1에서)
- [X] T008 [P] `backend/src/test/java/com/ssafy/thorp/backtest/domain/model/BacktestJobTest.java` 갱신: 새 시그니처 적용, "create는 initialCash가 0 이하이면 거부한다" 테스트 추가, `getInitialCash()` 검증
- [X] T009 [P] `backend/src/test/java/com/ssafy/thorp/backtest/application/service/BacktestServiceTest.java`·`backend/src/test/java/com/ssafy/thorp/backtest/presentation/controller/BacktestControllerTest.java`·`backend/src/test/java/com/ssafy/thorp/backtest/infrastructure/persistence/BacktestJobRepositoryImplTest.java`를 새 `create`/`restore`/`CreateBacktestCommand` 시그니처에 맞게 갱신. 컨트롤러 테스트에 "initialCash 생략 시 command에 10000000이 들어간다"와 "initialCash 지정 시 그대로 전달", "initialCash 0 → 400 INVALID_INPUT" 추가. RepositoryImpl 테스트에 `initialCash` 라운드트립 검증 추가

### 2-B. `BacktestResult` 도메인 모델·VO·예외

- [X] T010 [P] `backend/src/main/java/com/ssafy/thorp/backtest/domain/vo/EquityPoint.java` record 생성: `LocalDate date, long equity, double cumulativeReturn, double drawdown`. compact constructor에서 `date` null 금지, `equity < 0` 금지, `drawdown > 0` 금지 (`IllegalArgumentException`)
- [X] T011 [P] `backend/src/main/java/com/ssafy/thorp/backtest/domain/vo/PerformanceSummary.java` record 생성: data-model.md §1.3의 14필드(`long initialEquity, long finalEquity, Double totalReturn, Double cagr, Double maxDrawdown, LocalDate maxDrawdownPeakDate, LocalDate maxDrawdownTroughDate, LocalDate maxDrawdownRecoveryDate, Double volatility, Double sharpeRatio, int tradeCount, Double winRate, Double profitFactor, String metricDefinitionVersion`). 불변식: `initialEquity > 0`, `finalEquity >= 0`, `tradeCount >= 0`, `maxDrawdown == null || <= 0`, `volatility == null || >= 0`, `winRate == null || 0..1`, `profitFactor == null || >= 0`, `metricDefinitionVersion` not blank. 낙폭 구간(FR-008): `maxDrawdown < 0`이면 peak·trough 필수이고 `peak <= trough`; `maxDrawdown`이 null 또는 0이면 세 날짜 모두 null; recovery가 있으면 `recovery > trough`. Javadoc에 "nullable 지표의 null은 계산 불가(FR-011), 낙폭 구간의 null은 구간 없음/미회복" 명시
- [X] T012 `backend/src/main/java/com/ssafy/thorp/backtest/domain/vo/EquityCurve.java` record 생성(`List<EquityPoint> points`): compact constructor에서 null·빈 리스트 금지, `List.copyOf`로 불변화, 날짜 strictly ascending 검증(같거나 역순이면 `IllegalArgumentException`). `first()`, `last()`, `size()` 제공 (T010에 의존)
- [X] T013 `backend/src/main/java/com/ssafy/thorp/backtest/domain/model/BacktestResult.java` 생성: 필드 `Long id, long backtestJobId, PerformanceSummary summary, EquityCurve equityCurve`. private 생성자에서 `summary`·`equityCurve` null 금지, `equityCurve.first().equity() == summary.initialEquity()`, `equityCurve.last().equity() == summary.finalEquity()` 검증(위반 시 `IllegalArgumentException`, 메시지에 두 값 포함). `create(long backtestJobId, PerformanceSummary, EquityCurve)`(id null), `restore(Long id, long backtestJobId, PerformanceSummary, EquityCurve)`, getter 4개 (T011, T012에 의존)
- [X] T014 [P] `backend/src/main/java/com/ssafy/thorp/backtest/domain/exception/BacktestResultNotReadyException.java` 생성: `extends BusinessException`, 생성자 `(Long backtestId, BacktestStatus status)` → `super(ErrorCode.RESULT_NOT_READY, "백테스트 결과가 아직 준비되지 않았습니다: id=" + backtestId + ", status=" + status)` (T001에 의존)
- [X] T015 [P] `backend/src/test/java/com/ssafy/thorp/backtest/domain/vo/PerformanceSummaryTest.java` 생성: 정상 생성, `initialEquity` 0 거부, `maxDrawdown` 양수 거부, `winRate` 1.5 거부, 지표 전부 null 허용(계산 불가), `metricDefinitionVersion` 공백 거부, 낙폭 구간: `maxDrawdown < 0`인데 peak/trough null 거부, `peak > trough` 거부, `recovery <= trough` 거부, `maxDrawdown == 0`인데 날짜 있음 거부, 미회복(recovery null) 허용
- [X] T016 [P] `backend/src/test/java/com/ssafy/thorp/backtest/domain/vo/EquityCurveTest.java` 생성: 빈 리스트 거부, 날짜 역순·중복 거부, 오름차순 허용 + `first()/last()/size()`, 외부 리스트 변경이 반영되지 않음(불변 복사). `EquityPoint` 불변식(음수 equity, 양수 drawdown 거부)도 여기서 검증
- [X] T017 [P] `backend/src/test/java/com/ssafy/thorp/backtest/domain/model/BacktestResultTest.java` 생성: `create`는 id null, 첫 포인트 equity ≠ initialEquity면 거부, 마지막 포인트 equity ≠ finalEquity면 거부, `restore`도 같은 불변식 검증, 정상 케이스 getter 확인

### 2-C. `backtest_result` 저장소

- [X] T018 [P] `backend/src/main/java/com/ssafy/thorp/backtest/application/repository/BacktestResultRepository.java` 인터페이스 생성: `BacktestResult save(BacktestResult result)`, `Optional<BacktestResult> findByBacktestJobId(Long backtestJobId)`. Javadoc: "구현은 infrastructure.persistence. Worker가 쓰는 테이블을 읽는 포트" (T013에 의존)
- [X] T019 [P] `backend/src/main/java/com/ssafy/thorp/backtest/infrastructure/persistence/EquityPointJson.java` record 생성(package-private): `String date, long equity, double cumulativeReturn, double drawdown`. Javadoc에 "equity_curve JSON 배열 원소, date는 ISO-8601 문자열, 키 이름은 contracts/backtest-result-table.md와 일치" 명시
- [X] T020 `backend/src/main/java/com/ssafy/thorp/backtest/infrastructure/persistence/BacktestResultJpaEntity.java` 생성: `@Entity @Table(name = "backtest_result", uniqueConstraints = @UniqueConstraint(name = "uk_backtest_result_job", columnNames = "backtest_job_id")) extends BaseEntity`. 컬럼은 data-model.md §3.2(id IDENTITY, `backtest_job_id` not null, `initial_equity`/`final_equity` not null long, nullable `Double` 지표 7개, nullable `LocalDate` 낙폭 구간 3개(`max_drawdown_peak_date`, `max_drawdown_trough_date`, `max_drawdown_recovery_date`), `trade_count` int not null, `metric_definition_version` VARCHAR(20) not null, `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "equity_curve", nullable = false) List<EquityPointJson> equityCurve`). protected 기본 생성자 + package-private 전체 생성자 + getter (T019에 의존)
- [X] T021 `backend/src/main/java/com/ssafy/thorp/backtest/infrastructure/persistence/BacktestResultMapper.java` 생성(final, private 생성자): `static BacktestResultJpaEntity toEntity(BacktestResult)`(`LocalDate → date.toString()`), `static BacktestResult toDomain(BacktestResultJpaEntity)`(`LocalDate.parse`, `PerformanceSummary`·`EquityCurve` 조립 후 `BacktestResult.restore`) (T013, T020에 의존)
- [X] T022 [P] `backend/src/main/java/com/ssafy/thorp/backtest/infrastructure/persistence/BacktestResultJpaRepository.java` 생성(package-private interface): `extends JpaRepository<BacktestResultJpaEntity, Long>`, `Optional<BacktestResultJpaEntity> findByBacktestJobId(Long backtestJobId)` (T020에 의존)
- [X] T023 `backend/src/main/java/com/ssafy/thorp/backtest/infrastructure/persistence/BacktestResultRepositoryImpl.java` 생성: `@Repository implements BacktestResultRepository`, `save`/`findByBacktestJobId`를 Mapper로 위임 (T018, T021, T022에 의존)
- [X] T024 `backend/src/test/java/com/ssafy/thorp/backtest/infrastructure/persistence/BacktestResultRepositoryImplTest.java` 생성: `@DataJpaTest @Import({JpaAuditingConfig.class, BacktestResultRepositoryImpl.class})`. 테스트: (1) 20포인트 `EquityCurve` 포함 결과를 `save` → `flush/clear` → `findByBacktestJobId`로 읽어 summary 14필드(null 지표, 낙폭 구간 날짜 3개 포함)와 포인트 전부(날짜·equity·cumulativeReturn·drawdown) 일치 — **JSON 컬럼 H2 라운드트립 확정 지점(research.md §1)**, (2) 같은 `backtestJobId`로 두 번 저장하면 `DataIntegrityViolationException`, (3) 없는 jobId는 빈 Optional, (4) Auditing 필드 채워짐 (T023에 의존)

**Checkpoint**: `./gradlew spotlessApply build` 통과(ArchitectureTest 포함). T024가 실패하면 research.md §1 후퇴안(TEXT + 수동 직렬화)으로 T019~T021을 수정하고 research.md를 갱신한다. → **커밋 2** `feat(be): 백테스트 결과 도메인 모델·저장소 추가 (backtest_result)`

---

## Phase 3: User Story 1 - 주요 성과지표와 위험 지표 확인 (Priority: P1) 🎯 MVP

**Goal**: `GET /backtests/{id}` 응답에 `config`와 `summary`를 추가한다. COMPLETED이고 결과가 있으면 지표 11필드를 채우고, 그 외에는 `summary: null`. `POST /backtests` 응답도 같은 형태(summary null)로 확장한다.

**Independent Test**: dev 시더 없이도 `BacktestControllerTest`·`BacktestServiceTest`로 검증 가능. 서비스 mock이 COMPLETED Job + 결과를 돌려주면 응답 JSON에 `summary.totalReturn` 등이 실리고, QUEUED면 `summary`가 null이며, `config.initialCash`가 항상 실린다. contracts/examples/get-backtest-completed.json과 필드명이 일치한다.

### Implementation for User Story 1

- [ ] T025 [P] [US1] `backend/src/main/java/com/ssafy/thorp/backtest/application/result/BacktestConfigResult.java` record 생성: `String strategy, LocalDate startDate, LocalDate endDate, long initialCash`, `static from(BacktestJob)`
- [ ] T026 [P] [US1] `backend/src/main/java/com/ssafy/thorp/backtest/application/result/BacktestSummaryResult.java` record 생성: `PerformanceSummary`와 같은 14필드(낙폭 구간 날짜 3개 포함), `static from(PerformanceSummary)`
- [ ] T027 [US1] `backend/src/main/java/com/ssafy/thorp/backtest/application/result/BacktestJobResult.java`를 `record BacktestJobResult(Long id, BacktestStatus status, BacktestConfigResult config, BacktestSummaryResult summary)`로 변경. `static from(BacktestJob job)`(summary null), `static from(BacktestJob job, BacktestResult result)`. 기존 `strategy/startDate/endDate` 컴포넌트는 `config`로 이동 (T025, T026에 의존)
- [ ] T028 [US1] `backend/src/main/java/com/ssafy/thorp/backtest/application/service/BacktestService.java`: `BacktestResultRepository` 주입 추가(생성자). `get(Long)`을 `@Transactional(readOnly = true)`로 바꾸고, Job 조회(없으면 `BacktestNotFoundException`) → `status == COMPLETED`면 `resultRepository.findByBacktestJobId(id)`로 결과 조회해 `BacktestJobResult.from(job, result)`, 결과가 없으면 `log.warn` 후 `from(job)`, 그 외 상태는 `from(job)`. `create`의 반환은 `from(saved)` (T027에 의존)
- [ ] T029 [P] [US1] `backend/src/main/java/com/ssafy/thorp/backtest/presentation/response/BacktestConfigResponse.java` record 생성(`strategy, startDate, endDate, initialCash`, `from(BacktestConfigResult)`) 및 `backend/src/main/java/com/ssafy/thorp/backtest/presentation/response/BacktestSummaryResponse.java` record 생성(14필드, `from(BacktestSummaryResult)`; null 입력이면 null 반환하는 정적 메서드 포함)
- [ ] T030 [US1] `backend/src/main/java/com/ssafy/thorp/backtest/presentation/response/BacktestJobResponse.java`를 `record BacktestJobResponse(Long backtestId, BacktestStatus status, BacktestConfigResponse config, BacktestSummaryResponse summary)`로 확장. `from(BacktestJobResult)`에서 summary null 처리. Javadoc에 "summary는 COMPLETED일 때만 값, 그 외 null" 명시. `BacktestController`는 시그니처 변경 없이 그대로 동작해야 한다 (T027, T029에 의존)
- [ ] T031 [US1] `backend/src/test/java/com/ssafy/thorp/backtest/application/service/BacktestServiceTest.java` 갱신: `@Mock BacktestResultRepository resultRepository` 추가. 테스트 추가: "get은 COMPLETED Job이면 결과의 summary를 함께 돌려준다", "get은 COMPLETED이지만 결과가 없으면 summary null", "get은 RUNNING이면 결과 저장소를 조회하지 않고 summary null"(`verify(resultRepository, never())`), "create 결과의 config에 initialCash가 실린다" (T028에 의존)
- [ ] T032 [US1] `backend/src/test/java/com/ssafy/thorp/backtest/presentation/controller/BacktestControllerTest.java` 갱신: 새 `BacktestJobResult` 형태로 mock 구성. 테스트 추가: "GET /backtests/{id} COMPLETED → 200 + config 4필드 + summary 14필드(`winRate` null은 `jsonPath("$.data.summary.winRate").value(nullValue())`, 낙폭 구간 날짜는 `"2020-01-01"` 형식 문자열, 미회복이면 `maxDrawdownRecoveryDate` null)", "GET /backtests/{id} QUEUED → summary null, config 존재", "POST /backtests → 202 + data.config.initialCash 10000000 + data.summary null". 기존 404 테스트 유지 (T030에 의존)

**Checkpoint**: `./gradlew spotlessApply build` 통과. → **커밋 3** `feat(be): GET /backtests/{id} 응답에 config·summary 추가`

---

## Phase 4: User Story 2 - 기간에 따른 자산 가치 변화 확인 (Priority: P2)

**Goal**: `GET /backtests/{id}/timeseries`로 `{ backtestId, points[] }`를 제공한다. 없는 id 404, COMPLETED가 아니거나 결과가 없으면 409 `RESULT_NOT_READY`.

**Independent Test**: `BacktestControllerTest`에서 서비스 mock으로 200(포인트 필드 4개, 첫 equity = initialEquity), 404, 409(`error.code == RESULT_NOT_READY`)를 검증. `BacktestServiceTest`에서 상태별 분기(QUEUED/RUNNING/FAILED → 예외, COMPLETED+결과 없음 → 예외, COMPLETED+결과 → points)를 검증. contracts/examples/get-timeseries-completed.json·error-409-result-not-ready.json과 일치.

### Implementation for User Story 2

- [ ] T033 [P] [US2] `backend/src/main/java/com/ssafy/thorp/backtest/application/result/EquityPointResult.java` record 생성(`LocalDate date, long equity, double cumulativeReturn, double drawdown`, `from(EquityPoint)`) 및 `backend/src/main/java/com/ssafy/thorp/backtest/application/result/EquityCurveResult.java` record 생성(`Long backtestId, List<EquityPointResult> points`, `from(BacktestResult)`)
- [ ] T034 [US2] `backend/src/main/java/com/ssafy/thorp/backtest/application/service/BacktestService.java`에 `@Transactional(readOnly = true) EquityCurveResult getTimeseries(Long backtestId)` 추가: Job 없으면 `BacktestNotFoundException`, `status != COMPLETED`면 `BacktestResultNotReadyException(id, status)`, 결과 없으면 `log.warn` + 같은 예외, 있으면 `EquityCurveResult.from(result)` (T033에 의존)
- [ ] T035 [P] [US2] `backend/src/main/java/com/ssafy/thorp/backtest/presentation/response/EquityPointResponse.java` record 생성(`date, equity, cumulativeReturn, drawdown`, `from(EquityPointResult)`) 및 `backend/src/main/java/com/ssafy/thorp/backtest/presentation/response/BacktestTimeseriesResponse.java` record 생성(`Long backtestId, List<EquityPointResponse> points`, `from(EquityCurveResult)`)
- [ ] T036 [US2] `backend/src/main/java/com/ssafy/thorp/backtest/presentation/controller/BacktestController.java`에 `@GetMapping("/{backtestId}/timeseries") ResponseEntity<ApiResponse<BacktestTimeseriesResponse>> getTimeseries(@PathVariable Long backtestId)` 추가. 클래스 Javadoc에 엔드포인트·409 규칙 추가 (T034, T035에 의존)
- [ ] T037 [US2] `backend/src/test/java/com/ssafy/thorp/backtest/application/service/BacktestServiceTest.java`에 추가: "getTimeseries는 COMPLETED Job의 포인트를 날짜 순서대로 돌려준다", "getTimeseries는 없는 id면 BacktestNotFoundException", "getTimeseries는 QUEUED/RUNNING/FAILED면 BacktestResultNotReadyException"(`@ParameterizedTest`+`@EnumSource(names = {...})` 또는 3케이스), "getTimeseries는 COMPLETED이지만 결과가 없으면 BacktestResultNotReadyException" (T034에 의존)
- [ ] T038 [US2] `backend/src/test/java/com/ssafy/thorp/backtest/presentation/controller/BacktestControllerTest.java`에 추가: "GET /backtests/{id}/timeseries → 200 + backtestId + points[0]의 date/equity/cumulativeReturn/drawdown + points 길이", "없는 id → 404 RESOURCE_NOT_FOUND", "QUEUED → 409 + success false + data null + error.code RESULT_NOT_READY" (T036에 의존)

**Checkpoint**: `./gradlew spotlessApply build` 통과. → **커밋 4** `feat(be): GET /backtests/{id}/timeseries 자산 가치 시계열 조회 추가`

---

## Phase 5: dev 프로파일 시더 (임시, Worker 결과 저장 전까지)

**Purpose**: Worker의 결과 저장 경로가 붙기 전까지 dev 환경에서 예시 전략 3개의 COMPLETED Job + 결과를 채워 프론트 연동을 막지 않는다. 데이터 출처·환산 규칙은 research.md §7.

- [ ] T039 `backend/src/main/java/com/ssafy/thorp/backtest/infrastructure/persistence/DevSampleResultSeeder.java` 생성: `@Component @Profile("dev") implements ApplicationRunner`. 생성자 주입 `BacktestJobRepository`, `BacktestResultRepository`, `BacktestResultJpaRepository`. `run()`: `resultJpaRepository.count() > 0`이면 `log.info("[dev-seed] backtest_result에 이미 데이터가 있어 건너뜁니다")` 후 return. 아니면 전략 3개(`trend`, `dualMomentum`, `rsiMeanReversion`)에 대해 `BacktestJob.restore(null, strategy, new BacktestPeriod(2019-01-01, 2023-12-31), 10_000_000L, COMPLETED)` 저장 → 반환 id로 `BacktestResult.create(...)` 저장. 시계열은 `frontend/src/data/backtestMock.ts`의 `rawPoints` 20개를 `static final` 배열(날짜 문자열 + 지수 3열)로 옮기고 `equity = round(지수 × 100_000)`, `cumulativeReturn = 지수/100 − 1`, `drawdown = 지수/직전최고 − 1`을 시더 안에서 산출. summary: `initialEquity`/`finalEquity`는 첫·마지막 equity, `totalReturn` 1.248/0.846/0.624, `cagr` 0.184/0.132/0.108, `volatility` 0.124/0.142/0.181, `sharpeRatio` 1.42/1.05/0.78, `tradeCount` 156/89/234, `maxDrawdown`은 시계열 drawdown의 최솟값, 낙폭 구간 3필드도 시계열에서 산출(저점 = drawdown 최솟값 첫 지점, 고점 = 저점 이전 최고 equity의 마지막 지점, 회복 = 저점 이후 equity ≥ 고점 equity 첫 지점, 없으면 null; research.md §7 기대값: trend/dualMomentum 2020-01-01·2020-04-01·2020-07-01, rsiMeanReversion 2019-10-01·2020-04-01·2020-07-01), `winRate`/`profitFactor` null, version `"1.0"`. 클래스 Javadoc: "**임시**. `TODO(worker-result)`: Worker 결과 저장이 구현되면 이 클래스를 삭제한다. 삭제 조건을 해당 티켓 완료 조건에 포함". `@Transactional`로 전략 3개 삽입을 한 트랜잭션으로 묶는다
- [ ] T040 `backend/src/test/java/com/ssafy/thorp/backtest/infrastructure/persistence/DevSampleResultSeederTest.java` 생성(`@ExtendWith(MockitoExtension.class)`): (1) `count() > 0`이면 저장소 호출 없음, (2) 비어 있으면 Job 3개·결과 3개가 저장되고 각 결과의 `equityCurve.first().equity() == 10_000_000`, `summary.totalReturn`이 마지막 equity로 계산한 값과 1e-9 오차 내 일치(FR-010), `maxDrawdown <= 0`, trend의 낙폭 구간이 2020-01-01·2020-04-01·2020-07-01, 전략 이름 3개 일치. `BacktestJobRepository.save`는 인자에 id 1·2·3을 붙여 돌려주는 stub 사용 (T039에 의존)

**Checkpoint**: `./gradlew spotlessApply build` 통과. (선택, PostgreSQL 필요) `./gradlew bootRun --args='--spring.profiles.active=dev'` 두 번 기동 후 `backtest_result` 3행 유지 확인. → **커밋 5** `chore(be): dev 프로파일 예시 전략 3개 결과 시더 추가`

---

## Phase 6: Polish & 검증

**Purpose**: 계약 일치 확인, 문서 마무리. 코드 변경은 최소.

- [ ] T041 [P] `specs/003-backtest-result-analysis/quickstart.md` §1·§3 절차 실행(빌드 필수, curl은 PostgreSQL 기동 가능 시). 응답 JSON을 `specs/003-backtest-result-analysis/contracts/examples/*.json`과 필드명·타입 대조. 어긋나면 코드가 아니라 어느 쪽이 맞는지 판단해 contracts 또는 코드를 수정
- [ ] T042 [P] `backend/src/main/java/com/ssafy/thorp/backtest/presentation/controller/BacktestController.java`·`BacktestService.java` 클래스 Javadoc이 최종 엔드포인트·상태별 동작(research.md §5 표)과 일치하는지 확인·정리
- [ ] T043 `specs/003-backtest-result-analysis/research.md` §1에 JSON 컬럼 H2 라운드트립 결과(성공/후퇴)를 확정 문구로 기록. `specs/003-backtest-result-analysis/plan.md`「Worker 동기화 항목」이 최종 코드(필드명·기본값)와 맞는지 확인
- [ ] T044 최종 `cd backend && ./gradlew spotlessApply build` 통과 확인 후 tasks.md의 모든 항목을 `[X]`로 표시

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: 의존 없음
- **Phase 2 (Foundational)**: Phase 1 완료 후. **US1·US2를 모두 막는다**
  - 2-A(initialCash)와 2-B(도메인)는 서로 독립 → 병렬 가능. 2-C는 2-B와 T001에 의존
- **Phase 3 (US1)**: Phase 2 완료 후
- **Phase 4 (US2)**: Phase 2 완료 후. US1과 파일이 겹치므로(`BacktestService`, `BacktestController`, 두 테스트) 순서대로 진행한다(US1 → US2)
- **Phase 5 (시더)**: Phase 2 완료 후면 가능하지만, 커밋 순서를 위해 US2 뒤에 둔다
- **Phase 6 (Polish)**: 전부 완료 후

### User Story Dependencies

- **US1**: Foundational만 의존
- **US2**: Foundational만 의존. `BacktestService`·`BacktestController`에 메서드를 추가하는 방식이라 US1 코드와 충돌하지 않지만 같은 파일이므로 순차 작업

### Within Each Phase

- 도메인 → 저장소 → 서비스 → 컨트롤러 순서
- 테스트는 같은 Phase에서 구현 직후 작성하고 Checkpoint 빌드로 확인

### Parallel Opportunities

- Phase 2: T003~T007(initialCash 체인, 순차) ∥ T010·T011·T014(VO·예외) → T012 → T013 → T018·T019·T022 → T020 → T021 → T023 → T024. T008·T009·T015·T016·T017은 대응 구현 직후 병렬
- Phase 3: T025 ∥ T026 → T027 → T028 ∥ T029 → T030 → T031 ∥ T032
- Phase 4: T033 ∥ T035 → T034 → T036 → T037 ∥ T038
- Phase 6: T041 ∥ T042 → T043 → T044

---

## Parallel Example: Phase 2 (Foundational)

```bash
# 도메인 VO·예외 동시 생성:
Task: "EquityPoint record in backend/src/main/java/com/ssafy/thorp/backtest/domain/vo/EquityPoint.java"
Task: "PerformanceSummary record in backend/src/main/java/com/ssafy/thorp/backtest/domain/vo/PerformanceSummary.java"
Task: "BacktestResultNotReadyException in backend/src/main/java/com/ssafy/thorp/backtest/domain/exception/BacktestResultNotReadyException.java"

# 도메인 테스트 동시 작성:
Task: "PerformanceSummaryTest in backend/src/test/java/com/ssafy/thorp/backtest/domain/vo/PerformanceSummaryTest.java"
Task: "EquityCurveTest in backend/src/test/java/com/ssafy/thorp/backtest/domain/vo/EquityCurveTest.java"
Task: "BacktestResultTest in backend/src/test/java/com/ssafy/thorp/backtest/domain/model/BacktestResultTest.java"
```

## Parallel Example: User Story 1

```bash
Task: "BacktestConfigResult in backend/src/main/java/com/ssafy/thorp/backtest/application/result/BacktestConfigResult.java"
Task: "BacktestSummaryResult in backend/src/main/java/com/ssafy/thorp/backtest/application/result/BacktestSummaryResult.java"
# 이후
Task: "BacktestServiceTest 갱신 in backend/src/test/java/com/ssafy/thorp/backtest/application/service/BacktestServiceTest.java"
Task: "BacktestControllerTest 갱신 in backend/src/test/java/com/ssafy/thorp/backtest/presentation/controller/BacktestControllerTest.java"
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 → Phase 2 → **커밋 2**
2. Phase 3(US1) → **커밋 3** → 이 시점에 `GET /backtests/{id}`만으로 결과표 연동 가능(시더는 아직 없음)

### Incremental Delivery

1. Phase 4(US2) → **커밋 4** → 차트 연동 가능
2. Phase 5(시더) → **커밋 5** → dev 환경에서 프론트가 실제 호출로 확인 가능
3. Phase 6 → MR

### 커밋 규칙 (implplan §5)

- Phase 하나가 끝날 때마다 `./gradlew spotlessApply build` 통과 → tasks.md `[X]` 갱신 → 해당 Phase 커밋에 tasks.md 변경을 함께 포함
- 한 번에 끝까지 가지 않는다. Phase마다 멈춰 빌드 결과를 보고한다

---

## Notes

- `domain/`에 `org.springframework.*`, `jakarta.persistence.*`, `com.fasterxml.*` import가 들어가면 `ArchitectureTest`가 실패한다. `Objects`, `List`, `LocalDate`만 쓴다
- `EquityPointJson`(infrastructure)과 `EquityPoint`(domain)는 다른 타입이다. Mapper에서만 변환한다
- 응답 record의 nullable 필드(`summary`, 지표)는 Jackson 기본 설정에서 `null`로 직렬화된다(`@JsonInclude(NON_NULL)`을 붙이지 않는다 — 계약상 키가 항상 존재해야 한다)
- `BacktestJob.restore`의 인자 순서 변경(`initialCash`가 `status` 앞)에 유의. 컴파일 오류가 기존 테스트 4개 갱신 누락을 알려준다
- `worker/`·`frontend/` 파일은 이 tasks에 없다. 필요해 보이면 plan.md「Worker 동기화 항목」에 적고 넘어간다
