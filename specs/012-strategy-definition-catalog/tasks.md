---

description: "Task list for 전략 정의 카탈로그"
---

# Tasks: 전략 정의 카탈로그

**Input**: Design documents from `/specs/012-strategy-definition-catalog/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: 테스트 작업을 포함한다. 이 기능의 성공 기준이 테스트로만 측정 가능하기 때문이다 —
SC-002·SC-010은 "BE와 FE가 같은 테스트 벡터로 같은 판정을 낸다"로, SC-001은 "새 전략 추가 후 소비 지점을
고치지 않고도 전 스위트가 통과한다"로 정의된다. 여기의 테스트는 개발 방법론이 아니라 산출물이다.

**Organization**: 작업은 사용자 스토리별로 묶여 있어 각 스토리를 독립적으로 구현·검증할 수 있다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 실행 가능 (다른 파일, 선행 작업 없음)
- **[Story]**: 해당 사용자 스토리 (US1~US4)

## Path Conventions

모노레포 웹 애플리케이션: `backend/src/`, `frontend/src/`, `worker/src/`, `docs/`, `infra/`.
Backend 패키지 루트는 `backend/src/main/java/com/ssafy/thorp/strategy/`이며 아래에서
**`.../strategy/`** 로 줄여 쓴다. 테스트 루트는 `backend/src/test/java/com/ssafy/thorp/strategy/`이며
**`.../test/strategy/`** 로 줄여 쓴다.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 계약 정본 이관, 런타임 버전 상향, 검증 도구·문서 정비

- [ ] T001 계약 정본을 `specs/012-strategy-definition-catalog/contracts/`에서 `docs/specs/strategy-definition/`으로 이동한다 — 스키마 3종(`strategy-definition.schema.json`, `strategy-catalog-api.schema.json`, `strategy-execution-binding.schema.json`)과 `test-vectors/parameter-validation.json`, `test-vectors/execution-scope.json`. 헌장 IV가 파트 경계 계약을 `docs/specs/`에 두도록 규정한다
- [ ] T002 [P] `docs/specs/strategy-definition/scripts/validate_definitions.py` 작성 — `jsonschema`로 `backend/src/main/resources/strategies/*.json`을 `strategy-definition.schema.json`에 검증하고 실패 시 exit 1
- [ ] T003 [P] `worker/pyproject.toml`에서 `requires-python = ">=3.14"`, `[tool.ruff] target-version = "py314"`로 상향
- [ ] T004 [P] `worker/requirements.txt`에 `nautilus_trader>=1.231` 추가
- [ ] T005 [P] `infra/docker/worker.Dockerfile`의 베이스를 `python:3.11-slim`에서 `python:3.14-slim`으로 변경
- [ ] T006 [P] `frontend/package.json`에 `vitest` devDependency와 `"test": "vitest run"` 스크립트를 추가하고 `frontend/vitest.config.ts`를 생성한다
- [ ] T007 `Jenkinsfile` 수정 — Worker 스테이지를 `uv venv --python 3.14` 기반으로 교체(시스템 `python3` 의존 제거), Frontend 스테이지에 `npm run test` 추가, T002 스크립트를 실행하는 계약 검증 스테이지 추가
- [ ] T008 [P] `docs/adr/0002-nautilus-trader-as-strategy-engine.md` 작성 — NautilusTrader 확정 결정, 자체 `BaseStrategy`를 두지 않는 이유, 계약을 엔진 비종속으로 유지하는 경계(`worker/src/worker/strategies/binding.py`) 기록
- [ ] T009 [P] `backend/CLAUDE.md` 계층 표에 `domain/policy`(순수 판정·계산)와 `infrastructure/catalog`(정의 로딩·Jackson DTO) 행 추가
- [ ] T010 [P] `worker/CLAUDE.md`의 "전략은 `base.Strategy` 상속, `generate_signals`만 구현 / 체결·지표는 `engine.py` 담당" 규칙을 Nautilus `Strategy`/`StrategyConfig` 기준으로 교체

**Checkpoint**: 계약이 `docs/specs/`에 있고 세 파트의 검증 명령이 새 런타임에서 돈다

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 의존하는 도메인 모델과 정의 로딩 경로

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 어떤 사용자 스토리도 시작할 수 없다

- [X] T011 `.../strategy/` 아래 `presentation/{controller,request,response}`, `application/{service,command,result,repository}`, `domain/{model,vo,policy,exception}`, `infrastructure/catalog` 디렉터리 생성
- [X] T012 [P] `.../strategy/domain/vo/`에 enum 6종 작성 — `ParameterType`(INTEGER/DECIMAL/BOOLEAN/ENUM), `ExecutionScope`(BACKTEST/LIVE), `ConstraintOperator`(LT/LTE/GT/GTE/EQ/NE), `HistoryRule`(FIXED/PARAMETER/MAX_PARAMETERS/SUM_PARAMETERS), `ChangeType`(INITIAL/BACKWARD_COMPATIBLE/INCOMPATIBLE), `ViolationCode`(8종)
- [X] T013 [P] `.../strategy/domain/vo/`에 값 타입 작성 — `NumericRange`(BigDecimal min/max + 경계 포함 여부 + step), `EnumOption`, `sealed interface ParameterValue` + `IntegerValue`/`DecimalValue`/`BooleanValue`/`EnumValue`, `ResolvedParameters`
- [X] T014 `.../strategy/domain/model/`에 정의 record 작성 — `ParameterDefinition`, `ParameterConstraint`, `MinimumHistoryRequirement`, `ChangeHistoryEntry`, `StrategyDefinition`, `StrategyCatalog` (T012·T013 필요). 필드는 data-model.md §2~§6을 따른다
- [ ] T015 [P] `.../strategy/domain/exception/`에 `StrategyNotFoundException`, `InvalidStrategyDefinitionException` 작성
- [ ] T016 [P] `.../strategy/application/repository/StrategyDefinitionRepository.java` 인터페이스 작성 — `findAll()`, `findById(String strategyId)`
- [ ] T017 `.../strategy/infrastructure/catalog/StrategyDefinitionJsonDto.java` 작성 — Jackson 바인딩 전용 DTO. 도메인은 Jackson을 참조할 수 없으므로 역직렬화는 여기서만 한다
- [ ] T018 `.../strategy/infrastructure/catalog/StrategyDefinitionJsonMapper.java` 작성 — DTO를 도메인 record로 변환. `numericRange`/`enumValues`의 타입별 배타성과 기본값 리터럴을 `ParameterValue`로 옮긴다
- [ ] T019 `.../strategy/infrastructure/catalog/ClasspathStrategyDefinitionRepository.java` 작성 — 기동 시 `classpath:strategies/*.json` 스캔 후 불변 맵 보관. 역직렬화 실패분은 `WARN` 로그 후 제외하고 나머지는 제공한다. 정의가 0건이어도 기동에 성공한다. 핫 리로드는 두지 않는다
- [ ] T020 [P] `contracts/examples/`의 정의 4종(`SMA_CROSSOVER`, `RSI_REVERSION`, `MACD_SIGNAL`, `MOMENTUM`)을 `backend/src/main/resources/strategies/`에 배치
- [ ] T021 [P] `.../test/strategy/infrastructure/catalog/ClasspathStrategyDefinitionRepositoryTest.java` 작성 — 4종 로드, 정의 0건일 때 빈 카탈로그, 깨진 JSON 하나가 나머지를 막지 않음
- [X] T022 `cd backend && ./gradlew test`로 `ArchitectureTest` 통과 확인 — `domain`이 Spring·JPA·Jackson을 참조하지 않고 계층 의존 방향이 지켜지는지

**Checkpoint**: 정의가 도메인 객체로 로드된다. 사용자 스토리 구현을 병렬로 시작할 수 있다

---

## Phase 3: User Story 1 - 전략 정의 조회 (Priority: P1) 🎯 MVP

**Goal**: 사용 가능한 전략 목록과 각 전략의 정의(이름·설명·진입/청산 조건·파라미터 정의)를 하나의
출처에서 조회한다. 이것만 있어도 전략 선택 화면과 파라미터 입력 폼을 만들 수 있다.

**Independent Test**: 전략 하나를 선택해 이름·설명·진입/청산 조건·파라미터 정의가 모두 제시되는지
확인하고, 다른 전략을 선택하면 그 전략의 정의로 바뀌는지 확인한다. 파라미터가 0개인 전략도 오류 없이
표현되는지 본다.

### Tests for User Story 1

- [ ] T023 [P] [US1] `.../test/strategy/presentation/controller/StrategyControllerTest.java` 작성 — `GET /strategies` 목록, `GET /strategies/{id}` 상세, 없는 전략 404 + `RESOURCE_NOT_FOUND`, 카탈로그가 비었을 때 `data: []`
- [ ] T024 [P] [US1] `.../test/strategy/application/service/StrategyCatalogServiceTest.java` 작성 — 목록 요약 변환과 단건 조회, 미존재 시 `StrategyNotFoundException`

### Implementation for User Story 1

- [ ] T025 [P] [US1] `.../strategy/application/result/`에 `StrategySummaryResult`, `StrategyDefinitionResult` 작성
- [ ] T026 [US1] `.../strategy/application/service/StrategyCatalogService.java` 작성 — `findAll()`, `findById()`. 미존재 시 `StrategyNotFoundException`
- [ ] T027 [P] [US1] `.../strategy/presentation/response/`에 `StrategySummaryResponse`, `StrategyDefinitionResponse`와 중첩 응답 record(파라미터·제약·최소이력·변경이력) 작성. 필드는 `strategy-catalog-api.schema.json`의 `strategySummary`/`getStrategyResponseData`와 일치해야 한다
- [ ] T028 [US1] `.../strategy/presentation/controller/StrategyController.java` 작성 — `@RequestMapping("/strategies")`, `GET /` 와 `GET /{strategyId}`. 응답은 `ApiResponse.ok(...)`로 감싼다
- [ ] T029 [US1] `backend/src/main/java/com/ssafy/thorp/common/web/advice/GlobalExceptionHandler.java`에 `StrategyNotFoundException` → 404 `RESOURCE_NOT_FOUND` 매핑 추가
- [ ] T030 [P] [US1] `frontend/src/types/strategy.ts` 작성 — `strategy-definition.schema.json`과 `strategy-catalog-api.schema.json`의 미러 타입
- [ ] T031 [P] [US1] `frontend/src/api/strategies.ts` 작성 — `apiClient` 경유 `fetchStrategies()`, `fetchStrategy(strategyId)`
- [ ] T032 [US1] `frontend/src/components/backtest/ParameterForm.tsx` 작성 — `parameters[]`의 `type`으로 입력 컴포넌트를 고른다. `INTEGER`/`DECIMAL`은 `min`·`max`·`step`이 붙은 숫자 입력, `BOOLEAN`은 스위치, `ENUM`은 `displayName`을 보여주는 셀렉트. 이 단계에서는 표시만 하고 검증은 US2에서 붙인다
- [ ] T033 [US1] `frontend/src/components/backtest/StrategySelector.tsx`를 `src/data/backtestMock` 대신 `fetchStrategies()` 기반으로 전환하고, 전략을 바꾸면 `ParameterForm`이 새 정의로 교체되게 연결
- [ ] T034 [US1] `quickstart.md` §2의 목록·상세·404 curl과 §3의 폼 렌더링 확인 수행

**Checkpoint**: 사용자가 전략을 고르고 그 전략이 무엇을 요구하는지 화면에서 볼 수 있다. 여기서 멈춰도
독립적으로 데모 가능하다

---

## Phase 4: User Story 2 - 정의를 단일 출처로 한 파라미터 값 판정 (Priority: P2)

**Goal**: 파라미터 값 집합이 정의에 부합하는지 정의만 보고 판정한다. 입력 화면과 서버가 같은 결론을
낸다.

**Independent Test**: 같은 파라미터 값 집합을 FE 미러와 BE API에 각각 넣어 동일한 판정(수용/거부,
위반 코드·대상·순서)이 나오는지 확인한다. 공유 벡터 20 케이스가 양쪽에서 통과하면 충족이다.

### Tests for User Story 2

- [ ] T035 [P] [US2] `.../test/strategy/support/TestVectorLoader.java` 작성 — `docs/specs/strategy-definition/test-vectors/*.json`을 읽어 케이스와 `definitionSources`(`ref`/`inline` 둘 다)를 로드하는 하네스
- [ ] T036 [P] [US2] `.../test/strategy/domain/policy/ParameterSetValidatorVectorTest.java` 작성 — `parameter-validation.json` 20 케이스 전부. `violations`는 `code` + `parameterName` + **배열 순서**까지 비교하고 `message`는 비교하지 않는다
- [ ] T037 [P] [US2] `.../test/strategy/domain/policy/HistoryRequirementCalculatorTest.java` 작성 — `FIXED`/`PARAMETER`/`MAX_PARAMETERS`/`SUM_PARAMETERS` 각각과 `offset` 가산
- [ ] T038 [P] [US2] `frontend/src/lib/strategyValidation.test.ts` 작성 — BE와 **같은** `parameter-validation.json`을 읽어 같은 비교를 수행

### Implementation for User Story 2

- [ ] T039 [P] [US2] `.../strategy/domain/model/`에 `ValidationOutcome`, `ParameterViolation` 작성. 위반이 있으면 `resolvedParameters`와 `requiredHistoryBars`는 null이다
- [ ] T040 [US2] `.../strategy/domain/policy/HistoryRequirementCalculator.java` 작성 — 확정 값 집합과 정의로부터 `bars` 산출. 대상 파라미터는 INTEGER만 받는다
- [ ] T041 [US2] `.../strategy/domain/policy/ParameterSetValidator.java` 작성 — data-model.md §7의 S0~S7 순서. 중단 없이 모든 위반을 누적하고, 타입이 깨진 값은 이후 단계에서 제외하되 **범위를 위반한 값은 제약 판정에 그대로 사용한다**. 위반 순서는 단계 순 → 정의의 `parameters` 선언 순서. 십진 연산(`BigDecimal`)으로 step을 판정한다
- [ ] T042 [P] [US2] `.../strategy/application/`에 `ValidateParametersCommand`(command), `ParameterValidationResult`(result) 작성
- [ ] T043 [US2] `.../strategy/application/service/ParameterValidationService.java` 작성 — 전략 조회 → 판정 → 이력 산출 조합
- [ ] T044 [P] [US2] `.../strategy/presentation/`에 `ParameterValidationRequest`(request), `ParameterValidationResponse`·`ParameterViolationResponse`(response) 작성
- [ ] T045 [US2] `StrategyController`에 `POST /strategies/{strategyId}/parameter-validation` 추가 — 판정 실패도 HTTP 200이며 결과는 본문에 담긴다
- [ ] T046 [US2] `frontend/src/lib/strategyValidation.ts` 작성 — BE와 동일한 S0~S7 판정 미러. 즉시 피드백용이며 권위는 서버에 있다
- [ ] T047 [US2] `ParameterForm.tsx`에 즉시 검증 피드백을 연결하고, 제출 시 `validateParameters()`로 서버 판정을 받아 결과를 표시
- [ ] T048 [US2] `quickstart.md` §2의 다중 위반·상호 제약 curl과 §3의 즉시 피드백 확인 수행

**Checkpoint**: US1과 US2가 각각 독립적으로 동작한다. 사용자가 잘못된 값을 넣으면 어디가 왜 틀렸는지
한 번에 안다

---

## Phase 5: User Story 3 - 새 전략 추가 (Priority: P3)

**Goal**: 개발자가 정의 파일 1개, 전략 구현 1개, 등록 한 줄만으로 새 전략을 추가한다. 소비 지점은
하나도 바뀌지 않는다.

**Independent Test**: 기존 전략과 파라미터 타입 구성이 다른 전략을 하나 추가하고, Backend 컨트롤러·서비스·
검증기와 Frontend 타입·폼·미러를 전혀 건드리지 않은 상태에서 목록 조회·폼 구성·값 판정이 동작하는지
확인한다. 형식을 위반한 정의를 넣었을 때 그 전략만 빠지고 나머지는 정상 제공되는지도 본다.

### Tests for User Story 3

- [ ] T049 [P] [US3] `.../test/strategy/domain/policy/StrategyDefinitionFormatValidatorTest.java` 작성 — data-model.md §2의 D1~D14 각각에 대해 위반 정의를 거부하고 위반 규칙을 특정하는지
- [ ] T050 [P] [US3] `worker/tests/test_strategy_definition_contract.py` 작성 — `../backend/src/main/resources/strategies/*.json`을 읽어 (a) 모든 정의가 `registry.STRATEGY_PATHS`에 등록됨 (b) 등록된 항목에 정의가 있음 (c) 정의 파라미터의 snake_case 집합이 `config_path` 설정 클래스의 자체 선언 필드 집합(예약 16개 제외)과 일치 (d) `type`이 필드 애노테이션과 대응. 경로가 없으면 skip
- [ ] T051 [P] [US3] `worker/tests/test_binding.py` 작성 — camelCase→snake_case 변환, `DECIMAL`을 문자열로 실어 `Decimal`로 정확히 복원(`0.1 + 0.2` → `Decimal('0.3')`, float으로 실으면 `Decimal('0.30000000000000004')`가 되므로 이 케이스가 회귀 방지선), `StrategyFactory.create()`가 실제 `Strategy` 인스턴스를 만드는지

### Implementation for User Story 3

- [ ] T052 [US3] `.../strategy/domain/vo/ReservedConfigFields.java` 작성 — `StrategyConfig` 예약 필드 16개 상수 목록 (D14 판정용). 목록은 research.md R11에 있다
- [ ] T053 [US3] `.../strategy/domain/policy/StrategyDefinitionFormatValidator.java` 작성 — D1~D14 검사. 위반 시 어떤 규칙을 어긴 어떤 항목인지 특정한다
- [ ] T054 [US3] `ClasspathStrategyDefinitionRepository`에 형식 검증을 연결 — 위반 정의는 `WARN` 로그 후 제외하고 나머지는 등재한다. `strategyId` 중복(D1) 시 충돌한 정의를 **모두** 제외한다
- [ ] T055 [P] [US3] `worker/src/worker/strategies/naming.py` 작성 — `to_snake()`/`to_camel()` 기계 변환과 `RESERVED_CONFIG_FIELDS` 상수
- [ ] T056 [P] [US3] `worker/src/worker/strategies/registry.py` 작성 — `STRATEGY_PATHS: dict[str, tuple[str, str]]` (strategyId → `"module:Class"`, `"module:ConfigClass"`)와 `resolve()`
- [ ] T057 [US3] `worker/src/worker/strategies/binding.py` 작성 — `to_importable(binding, instrument_id) -> ImportableStrategyConfig`. 이름은 snake_case로, `DECIMAL`은 문자열로 직렬화. 전략별 분기를 두지 않는다
- [ ] T058 [P] [US3] `worker/src/worker/strategies/sma_crossover.py` 작성 — `SmaCrossoverConfig(StrategyConfig, frozen=True)`와 `SmaCrossover(Strategy)`. 필드는 `SMA_CROSSOVER.json`의 파라미터에 1:1 대응하고 `instrument_id`를 추가로 받는다. `on_bar` 내부 매매 로직은 이번 범위가 아니다
- [ ] T059 [P] [US3] `worker/src/worker/strategies/rsi_reversion.py` 작성 — `RsiReversionConfig` + `RsiReversion`. `oversold_threshold`/`overbought_threshold`는 `Decimal`
- [ ] T060 [P] [US3] `worker/src/worker/strategies/macd_signal.py` 작성 — `MacdSignalConfig` + `MacdSignal`
- [ ] T061 [US3] `worker/src/worker/strategies/momentum.py`를 Nautilus 구조로 재작성하고 `worker/src/worker/strategies/base.py`를 삭제한다. `worker/src/worker/engine.py`는 남기되 Nautilus 배선 전까지 비활성임을 주석으로 명시
- [ ] T062 [US3] `quickstart.md` §4의 "의도적으로 깨뜨려 보기" 2종 수행 — 정의에 파라미터 추가 시 계약 테스트 실패, 설정 클래스에 `order_id_tag` 추가 시 D14 실패
- [ ] T063 [US3] `quickstart.md` §5 확장성 검증 수행 — 5번째 전략을 3개 파일만으로 추가해 전 스위트를 통과시키고, 확인 후 되돌린다. 다른 파일을 고쳐야 했다면 그 지점을 기록하고 어휘 확장으로 되돌린다

**Checkpoint**: 세 스토리가 각각 독립적으로 동작한다. 전략 추가 비용이 파일 3개로 고정됐다

---

## Phase 6: User Story 4 - 같은 정의로 과거 검증과 실시장 운용 (Priority: P4)

**Goal**: 정의 형식이 실행 목적에 종속되지 않는다. 같은 정의와 같은 확정 값이 양쪽에서 같게 해석되고,
실행 기록이 무엇으로 실행했는지 사후에 특정할 수 있다.

**Independent Test**: 같은 전략과 값으로 `BACKTEST`·`LIVE` 실행 구성을 각각 만들어 참조하는 정의와 확정
파라미터가 동일한지 확인한다. 실행 목적에 해당하지 않는 파라미터가 판정 대상에서 빠지는지 벡터로 본다.

### Tests for User Story 4

- [ ] T064 [P] [US4] `.../test/strategy/domain/policy/ExecutionScopeVectorTest.java` 작성 — `execution-scope.json` 3 케이스. T035의 하네스를 재사용한다
- [ ] T065 [P] [US4] `frontend/src/lib/strategyValidation.scope.test.ts` 작성 — 같은 `execution-scope.json`으로 FE 미러 검증

### Implementation for User Story 4

- [ ] T066 [US4] `ParameterSetValidator`에 `executionScope` 필터링 추가 — 범위 밖 파라미터는 필수여도 요구하지 않고, 확정 값 집합에 넣지 않으며, 입력에 들어오면 `UNKNOWN_PARAMETER`로 거부한다 (data-model.md §7)
- [ ] T067 [US4] `StrategyDefinitionFormatValidator`에 D9 연장 규칙 추가 — 최소 이력 규칙이 실행 목적 범위 밖 파라미터를 참조하면 형식 위반
- [ ] T068 [US4] `GET /strategies`에 `executionScope` 쿼리 파라미터 필터 추가 (기본 `BACKTEST`) — 해당 목적을 지원하지 않는 전략은 목록에서 제외한다
- [ ] T069 [US4] `worker/src/worker/models.py` 수정 — `StrategyBinding` pydantic 모델 추가하고 `BacktestJob.strategy`를 `str`에서 이 객체로 교체. `strategy-execution-binding.schema.json`과 필드를 맞춘다
- [ ] T070 [US4] `backend/src/main/java/com/ssafy/thorp/backtest/`의 실행 메시지 발행 경로(`application/port/BacktestJobPublisher`, `infrastructure/messaging/LoggingBacktestJobPublisher`)에 `strategyId`·`definitionVersion`·`resolvedParameters`·`requiredHistoryBars`·`executionScope`를 싣는다. **T069와 같은 MR에서 함께 변경해야 한다** — 헌장 IV가 비호환 상태의 Merge를 금지한다
- [ ] T071 [US4] `worker/src/worker/main.py`의 Job 수신 로깅을 새 바인딩 구조에 맞게 갱신 (`job.strategy.strategy_id`, `definition_version`)
- [ ] T072 [US4] `frontend/src/types/strategy.ts`와 `frontend/src/api/strategies.ts`에 `executionScope`를 반영 — 목록 조회 파라미터와 판정 요청 필드

**Checkpoint**: 네 스토리 모두 독립적으로 동작한다. 실행 기록만으로 무엇을 실행했는지 복원된다

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T073 [P] `cd backend && ./gradlew spotlessApply`와 `cd worker && uv run --python 3.14 ruff format src tests` 실행
- [ ] T074 [P] `specs/012-strategy-definition-catalog/contracts/README.md`를 `docs/specs/strategy-definition/`로의 포인터만 남기고 정리 — 정본이 두 곳에 있으면 어긋난다
- [ ] T075 [P] `docs/specs/strategy-definition/README.md` 정리 — 스키마 목록, 검증 명령, 스키마가 담지 않는 규칙(D1~D14)의 위치
- [ ] T076 `specs/012-strategy-definition-catalog/quickstart.md` 6개 절 전체 수행 — 계약 검증 → BE → FE → Worker → 확장성 → CI
- [ ] T077 CI 전체 통과 확인 — Frontend(lint·test·build), Backend(`clean test build`), Worker(ruff·pytest), 계약 검증 스테이지

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 선행 없음. 즉시 시작 가능
- **Foundational (Phase 2)**: Setup 완료 후. **모든 사용자 스토리를 막는다**
- **User Stories (Phase 3~6)**: Foundational 완료 후 우선순위 순 또는 병렬
- **Polish (Phase 7)**: 진행하기로 한 스토리가 모두 끝난 후

### User Story Dependencies

- **US1 (P1)**: Foundational 이후 바로 시작. 다른 스토리에 의존하지 않는다
- **US2 (P2)**: Foundational 이후 시작 가능. US1과 파일이 거의 겹치지 않아 병렬 작업이 쉽다.
  겹치는 곳은 `StrategyController`(T028 → T045)와 `ParameterForm.tsx`(T032 → T047) 둘뿐이다
- **US3 (P3)**: Foundational 이후 시작 가능. Worker 작업(T055~T061)은 Backend·Frontend와 완전히 독립이라
  다른 사람이 동시에 진행할 수 있다. T054는 T019(Foundational)를 고친다
- **US4 (P4)**: `ParameterSetValidator`(T041, US2)와 `StrategyDefinitionFormatValidator`(T053, US3)를
  수정하므로 **US2·US3 완료 후**에 하는 편이 충돌이 적다. 이 기능에서 유일하게 선행 스토리에 의존한다

### Within Each User Story

- 테스트를 먼저 쓰고 실패를 확인한 뒤 구현한다 — 특히 벡터 테스트(T036·T038)는 판정기가 없으면 반드시
  실패해야 한다
- 도메인 모델 → 정책(policy) → 애플리케이션 서비스 → 컨트롤러 순
- 계약 스키마와 어긋나는 응답 필드가 없는지 각 응답 record 작성 시 확인

### Parallel Opportunities

- Setup의 T002~T006, T008~T010은 서로 다른 파일이라 모두 병렬
- Foundational의 T012·T013은 병렬, T015·T016·T020·T021도 병렬
- US1의 테스트 T023·T024, 그리고 T025·T027·T030·T031이 병렬
- US2의 테스트 T035~T038 전부 병렬
- US3의 Worker 전략 파일 T058·T059·T060 병렬
- Foundational 완료 후 US1(BE+FE) / US2(BE+FE) / US3(Worker)를 세 사람이 동시에 진행 가능

---

## Parallel Example: User Story 2

```bash
# 테스트 4개를 먼저 함께 작성한다 (전부 실패해야 정상)
Task: ".../test/strategy/support/TestVectorLoader.java 하네스 작성"
Task: ".../test/strategy/domain/policy/ParameterSetValidatorVectorTest.java 작성"
Task: ".../test/strategy/domain/policy/HistoryRequirementCalculatorTest.java 작성"
Task: "frontend/src/lib/strategyValidation.test.ts 작성"

# 구현 착수 시 병렬 가능한 것들
Task: ".../strategy/domain/model/ValidationOutcome, ParameterViolation 작성"
Task: ".../strategy/application/ValidateParametersCommand, ParameterValidationResult 작성"
Task: ".../strategy/presentation/ParameterValidationRequest, Response record 작성"
```

## Parallel Example: User Story 3 (Worker)

```bash
Task: "worker/src/worker/strategies/sma_crossover.py 작성"
Task: "worker/src/worker/strategies/rsi_reversion.py 작성"
Task: "worker/src/worker/strategies/macd_signal.py 작성"
```

---

## Implementation Strategy

### MVP First (User Story 1만)

1. Phase 1 Setup 완료
2. Phase 2 Foundational 완료 (**모든 스토리를 막으므로 여기서 서두르지 말 것**)
3. Phase 3 US1 완료
4. **멈추고 검증**: quickstart.md §2·§3로 전략 목록과 동적 폼이 실제로 뜨는지 확인
5. 데모 가능

이 시점의 가치: 전략이 무엇이 있고 무엇을 입력해야 하는지 사용자가 화면에서 안다. 판정은 아직 없다.

### Incremental Delivery

1. Setup + Foundational → 정의가 로드된다
2. US1 추가 → 조회와 동적 폼 (**MVP**)
3. US2 추가 → 값 판정과 즉시 피드백. F2(백테스트 조건 설정)가 소비할 수 있는 상태가 된다
4. US3 추가 → 전략 추가 비용이 파일 3개로 고정. 여기서 20종 확장의 문이 열린다
5. US4 추가 → 실행 목적 분리와 실행 기록 추적성

각 단계가 이전 것을 깨지 않고 가치를 더한다.

### Parallel Team Strategy

Foundational 완료 후:

- 개발자 A: US1 (Backend 조회 + Frontend 표시)
- 개발자 B: US2 (판정기 + 벡터 테스트 + FE 미러)
- 개발자 C: US3 Worker 부분 (T055~T061, T050·T051) — Backend·Frontend와 파일이 전혀 겹치지 않는다

US3의 Backend 부분(T052~T054)은 `ClasspathStrategyDefinitionRepository`를 고치므로 Foundational을 만든
사람이 맡는 편이 낫다. US4는 US2·US3가 끝난 뒤 한 사람이 마무리한다.

---

## Notes

- `[P]`는 다른 파일이고 선행 작업이 없다는 뜻이다
- 커밋은 작업 단위 또는 논리적 묶음마다. 브랜치·커밋 컨벤션은 루트 `CLAUDE.md`를 따른다
- **T069와 T070은 반드시 같은 MR**이다. 실행 메시지 계약을 한쪽만 바꾸면 배포가 깨진다(헌장 IV)
- 판정 규칙을 고칠 때는 BE·FE 두 구현과 벡터 파일을 함께 본다. 한쪽만 고치면 SC-002가 깨진다
- 새 전략을 추가하다가 소비 지점을 고쳐야 하는 상황이 오면, 그 전략만을 위한 예외를 넣지 말고 정의
  형식의 어휘를 넓히는 쪽으로 되돌린다
- 이번 기능은 매매 로직을 포함하지 않는다. `on_bar` 내부는 후속 기능에서 채운다
