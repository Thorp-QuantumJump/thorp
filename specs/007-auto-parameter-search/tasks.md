# Tasks: 설정 조합 자동 비교 (자동 파라미터 대량 탐색)

**Input**: Design documents from `specs/007-auto-parameter-search/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: 포함한다. constitution "완료의 정의" 조건 2가 "테스트 또는 동작 확인 완료"를 Task 완료 조건으로 못 박고 있어 선택 사항이 아니다. 각 테스트 태스크는 [quickstart.md](quickstart.md) "자동 테스트" 표의 대상과 1:1로 대응한다.

**Organization**: 사용자 스토리별로 묶고, 각 단계 안에서 **파트별로 다시 나눈다.**

## Format: `[ID] [P?] [Story] (파트) Description`

- **[P]**: 다른 파일을 만지고 선행 태스크가 없어 병렬 실행 가능
- **문자 접미사 ID**(`T020a`·`T098a`): `/speckit-analyze`가 찾은 누락을 메우려고 나중에 끼운 태스크다. 실행 순서를 유지하려고 앞 번호를 다시 매기지 않고, spec의 `FR-005a`·`FR-013a`와 같은 표기 방식을 따랐다
- **[US1]~[US4]**: spec.md의 사용자 스토리. Setup·Foundational·Polish에는 붙지 않는다
- **(파트)**: 아래 표의 파트. 작업 종류 표시이며 브랜치 단위가 아니다

| 파트 태그 | 대상 | 묶음의 브랜치 position |
|---|---|---|
| `(DB)` | Flyway 마이그레이션 | `be` |
| `(BE)` | 백엔드 Java 구현 | `be` |
| `(BE 테스트)` | 백엔드 JUnit·Testcontainers | `be` |
| `(FE)` | 프론트엔드 React·TS | `fe` |
| `(FE 테스트)` | 프론트엔드 Vitest | `fe` |
| `(INFRA)` | 설정·프로퍼티 | `infra` |
| `(DOCS)` | 문서·ADR·MR 템플릿·수동 종단 확인 기록 | `docs` |

Worker(`worker/`)는 이번 범위에서 **변경하지 않는다**. `de`·`ai` 파트 태스크도 없다.

**`T###`는 브랜치 단위가 아니다.** constitution v3.0.0 "Jira 연계"가 `작업 묶음 = 1 Branch = 1 MR`로
정하고 있어, 브랜치·MR·Jira Task 단위는 [plan.md](plan.md) "작업 묶음 구성"의 묶음(M1~M10) 10개다.
아래 157개는 실행 지시의 최소 단위이고, 각 묶음이 어떤 `T###`를 담는지는 그 표에 있다.
묶음은 초판의 24개(B01~B24)에서 10개로 합쳤다 — 사유는 plan.md "묶음 구성 개정 이력"에 있다.

**2026-09-11 개정**: 전략 비의존성 결정(spec FR-025~FR-029, US5)에 따라 23개를 더했고 기존 태스크
10여 개의 내용을 고쳤다. `/speckit-analyze`가 찾은 F1(기준 전략)·F4(등록표 경계 화면)도 함께 반영했다. 새 묶음은 만들지 않았다 — 늘어난 범위는 M2·M3·M4·M7이 나눠 가진다
(plan.md "2026-09-11 개정이 묶음에 주는 영향"). **T001~T026은 이미 머지된 M1이므로 손대지 않고,
그 중 개정이 필요한 세 건만 `T026a`~`T026c`로 새로 끼워 M2 브랜치에서 처리한다.**

## Path Conventions

- 백엔드: `backend/src/main/java/com/ssafy/thorp/paramsearch/...`, 테스트는 `backend/src/test/java/com/ssafy/thorp/paramsearch/...`
- 마이그레이션: `backend/src/main/resources/db/migration/V3__param_search.sql`
- 임시 등록표 데이터: `backend/src/main/resources/registry/parameter-registry-provisional.yml` (003 대역)
- 프론트엔드: `frontend/src/...`
- 커밋 전 `cd backend && ./gradlew spotlessApply` 필수 (CLAUDE.md 코드 컨벤션)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 패키지 골격과 설정값 자리 만들기

### Backend

- [X] T001 (BE) `paramsearch` 최상위 패키지와 4계층 하위 패키지 골격을 만들고 계층 규약을 `backend/src/main/java/com/ssafy/thorp/paramsearch/package-info.java`에 명시 (ADR-0001 계층 구조를 따르고 `backtest` 모듈과 같은 배치)
- [X] T002 [P] (BE) `ParamSearchProperties` 설정 클래스를 `backend/src/main/java/com/ssafy/thorp/paramsearch/infrastructure/config/ParamSearchProperties.java`에 작성 — `maxCandidates`·`dispatchWindow`·`timeLimitSeconds`·`consecutiveFailureLimit`와 임시값 이름 목록을 노출. **네 값 모두 `@NotNull`로 필수화하고 코드에 기본값을 박지 않는다** — PD-05가 미확정인 값을 코드가 임의로 정하면 constitution "AI 작업 방식"을 어기므로, 미설정이면 기동을 실패시켜 설정으로 드러낸다

### Infra

- [X] T003 [P] (INFRA) `backend/src/main/resources/application.yml`에 `thorp.param-search` 블록 추가 — `maxCandidates: 1000`만 채운다(clarify 확정, PD-06 실측 후 고정). `dispatchWindow`·`timeLimitSeconds`·`consecutiveFailureLimit`는 **키만 두고 값을 비운 채 PD-05 확정 대기임을 주석으로 남긴다**. 값을 추측해 채우지 않는다
- [X] T004 [P] (INFRA) `backend/src/main/resources/application-local.yml`에 개발용 값 4개를 모두 채운다 — 낮은 `maxCandidates`(상한 초과 거부를 로컬에서 재현, quickstart 시나리오 3)와 `dispatchWindow`·`timeLimitSeconds`·`consecutiveFailureLimit`. **개발 편의값이며 팀 확정값이 아님을 주석에 명시**하고, API 응답의 `limits.provisional`에 실린다

### Frontend

- [ ] T005 [P] (FE) `frontend/src/types/paramSearch.ts`에 계약 응답 타입 정의 ([contracts/param-search-api.md](contracts/param-search-api.md)의 응답 모양과 1:1, 금액·비율은 `string`)
- [ ] T006 [P] (FE) `frontend/src/api/paramSearch.ts` 빈 모듈과 `frontend/src/components/paramsearch/` 디렉터리 생성 (`frontend/src/api/client.ts`의 기존 호출 규약 재사용)

### Test 기반

- [X] T007 [P] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/` 테스트 패키지 골격 생성과 `backtest` 통합 테스트의 Testcontainers PostgreSQL 설정 재사용 확인

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 사용자 스토리가 공유하는 스키마·모델·포트

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 어떤 사용자 스토리도 시작할 수 없다

### DB 스키마 (같은 파일이라 순차)

- [X] T008 (DB) `backend/src/main/resources/db/migration/V3__param_search.sql`에 `param_search_seq` 시퀀스와 `param_search` 테이블 작성 ([data-model.md](data-model.md) §2). 전략 파라미터 축(`search_space`)과 평가 시나리오 축(`evaluation_split`)을 별도 컬럼으로 둔다 — 경제환경 검색 설정 축은 010 소유라 만들지 않는다 (FR-021). `V2__backtest_result.sql`의 저장 규칙 주석 규약을 따른다 — 질의에 쓰는 값만 컬럼, 표시용은 JSONB, decimal은 예외 없이 JSONB
- [X] T009 (DB) 같은 파일에 `backtest_trade_seq`와 같은 방식의 `search_candidate_seq`(INCREMENT 50)와 `search_candidate` 테이블 작성 ([data-model.md](data-model.md) §4)
- [X] T010 (DB) 같은 파일에 인덱스·제약 추가 — `param_search.search_id` UNIQUE, `(owner_user_id, created_at DESC)`, `(param_search_id, candidate_no)` UNIQUE, `(param_search_id, status)`, `(param_search_id, selected)`, `search_candidate.backtest_job_id` UNIQUE(NULL 허용)

### 도메인 enum·VO (서로 독립)

- [X] T011 [P] (BE) `domain/model/ParamSearchStatus.java` — `QUEUED` `RUNNING` `COMPLETED` `PARTIAL` `FAILED` `REJECTED`와 전이 허용 규칙 ([data-model.md](data-model.md) §3)
- [X] T012 [P] (BE) `domain/model/CandidateStatus.java` — `PENDING` `DISPATCHED` `COMPLETED` `FAILED` `EXCLUDED` `SKIPPED`와 전이 허용 규칙 (FR-008, §4.1). `SKIPPED`는 되돌아오지 않는 확정 상태다
- [X] T013 [P] (BE) `domain/model/ValidationLabel.java` — `SEARCH_RESULT` `HELD_OUT_RESULT` `UNVALIDATED`
- [X] T014 [P] (BE) `domain/vo/ParamSearchId.java` — `PS-{yyyyMMdd}-{6자리 이상 순번}` 형식 생성·검증 (`BacktestId`와 같은 규약, DB PK 비노출)
- [X] T015 [P] (BE) `domain/vo/ParameterCombination.java` — 파라미터 조합 하나. 값에 `null` 금지
- [X] T016 [P] (BE) `domain/vo/GridCoordinates.java` — 축 이름 → 등록표 인덱스. 이웃 판정의 근거 (research R7)
- [X] T017 [P] (BE) `domain/vo/SearchSpace.java` — 축 이름 → 선택한 후보 값 목록

### 도메인 모델

- [X] T018 (BE) `domain/model/SelectionRule.java` — 선정 규칙 스냅샷과 JSON 직렬화 ([data-model.md](data-model.md) §5). 절대 문턱 숫자를 담지 않는다
- [X] T019 (BE) `domain/model/ParamSearch.java` — 묶음 애그리게이트와 상태 전이 (T011, T014, T017, T018 의존)
- [X] T020 (BE) `domain/model/SearchCandidate.java` — 후보 모델과 상태 전이 (T012, T013, T015, T016 의존)
- [X] T020a [P] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/domain/model/StatusTransitionTest.java` — 허용된 전이만 통과하고 나머지는 거부하는지 검증. `SKIPPED`가 되돌아오지 않는지, `FAILED`→`DISPATCHED`(재시도, FR-024)만 허용되는지 포함. 이 묶음이 스스로 검증 가능해야 한다(묶음 조건 2)

### 영속화

- [X] T021 [P] (BE) `infrastructure/persistence/ParamSearchJpaEntity.java` — T008 스키마와 1:1 (`ddl-auto=validate`)
- [X] T022 [P] (BE) `infrastructure/persistence/SearchCandidateJpaEntity.java` — T009 스키마와 1:1, `allocationSize = 50`
- [X] T023 [P] (BE) `infrastructure/persistence/ParamSearchJpaRepository.java`와 `SearchCandidateJpaRepository.java`
- [X] T024 (BE) `infrastructure/persistence/ParamSearchMapper.java`와 `SearchCandidateMapper.java` — 도메인 ↔ 엔티티 (T019~T022 의존)
- [X] T025 (BE) `application/repository/ParamSearchRepository.java`·`SearchCandidateRepository.java` 포트와 `infrastructure/persistence/*RepositoryImpl.java` 구현
- [X] T026 (BE 테스트) 엔티티와 V3 스키마 일치 검증 테스트 — `ddl-auto=validate`로 기동되는지 Testcontainers로 확인

### M1 재작업 (2026-09-11 개정. M2 브랜치에서 처리하고 MR 본문에 사유를 남긴다)

- [X] T026a (BE) `domain/vo/SearchSpace.java`를 `전략 → 축 → 값` 중첩 구조로 바꾼다 — 한 묶음이 전략 여러 개를 담으면 축 이름이 충돌한다. `combinationCount()`는 **전략별 (축별 값 개수의 곱)의 합**이 되고 넘침 검사는 유지한다. 축 순서와 축별 값 순서 보존은 그대로다 ([data-model.md](data-model.md) §2.1, FR-027)
- [X] T026b [P] (BE) `domain/model/StopReason.java`에 `STRATEGY_NOT_REGISTERED`·`STRATEGY_NOT_EXECUTABLE` 추가 — 미등록과 실행 미지원을 구분해 남긴다 (FR-026)
- [X] T026c (BE 테스트) `SearchSpace` JSONB 왕복 테스트 — **축을 배열로 저장해 순서가 보존되는지** 검증한다. `jsonb`는 객체 키 순서를 보존하지 않아 축을 객체 키로 두면 `grid_coordinates` 인덱스가 다른 값을 가리킨다 ([data-model.md](data-model.md) §2.1, FR-029). V3 마이그레이션은 다시 만들지 않는다 (JSONB 컬럼)

### 등록표 포트 (003 경계)

- [X] T027 [P] (BE) `application/port/ParameterRegistryPort.java`와 `StrategyRegistration`·`ParameterAxis`·`AllowedRange`·`ValidationVerdict`·`SearchContext` record 정의 — 포트가 **활성 전략 목록(`activeStrategies()`)·전략 하나 조회(`find()`)·전략명·표시 정보·축별 자동 탐색 후보값·기본 시작값·입력 허용 범위·단위·유효성 규칙 호출·손실 제약의 '기준 전략' 역할 조회(`baselineStrategyId()`)**를 모두 돌려준다. 007은 이 포트가 주는 값 외에 전략에 대해 아무것도 알지 못한다 ([contracts/param-search-internal.md](contracts/param-search-internal.md) §1, FR-025·FR-027)
- [X] T028 (BE) `infrastructure/registry/ProvisionalFileParameterRegistry.java` 임시 구현 — `version()`은 `PROVISIONAL-{날짜}`, 전략·축·값은 **자원 파일에서 읽고 Java 상수로 두지 않는다**. 로더는 파일 구조만 읽고 전략별 해석을 하지 않는다. **PD-02 팀 확정값이 아님을 클래스 주석과 버전 문자열로 명시** (research R1 개정, FR-025)
- [X] T028a [P] (INFRA) `backend/src/main/resources/registry/parameter-registry-provisional.yml` 작성 — 003 등록표의 대역. 전략별 ID·이름·축·자동 탐색 후보값·기본 시작값·허용 범위·단위·유효성 규칙 코드를 담는다. **이 파일이 유일한 전략 데이터 위치이고 007 코드에는 전략 이름이 나타나지 않는다.** 003 완성 시 이 파일과 로더를 003 구현체로 교체한다 (research R1 개정)
- [X] T029 (BE 테스트) 같은 `version()` 안에서 축 순서와 후보값 인덱스가 안정적인지, `defaultValue`·`allowedRange`가 자원 파일 값과 일치하는지 검증 — 인덱스가 흔들리면 그리드 좌표가 뜻을 잃는다 (FR-029)

### 공통 서비스

- [X] T030 (BE) `application/service/GridExpander.java` — **전략별로** 전체 그리드를 전개하고 그리드 좌표를 부여한다. 축 개수·축별 후보 개수가 전략마다 달라도 같은 절차로 처리하고, 전체 조합 수는 전략별 곱의 합이다. `strategyId` 분기를 두지 않는다 (T026a·T027 의존, FR-005·FR-027)
- [X] T031 (BE) `application/service/CandidateValidator.java` — 조합마다 `ParameterRegistryPort.validate()`를 호출하고 돌려받은 `reasonCode`·`reasonDetail`을 그대로 저장한다. **위반 규칙을 007이 갖지 않는다** — 위반 코드 목록을 상수로 두거나 전략별로 분기하지 않는다 (FR-006·FR-025)

### 004 경계

- [X] T032 [P] (BE) `application/port/CandidateJobLauncher.java` 포트 정의 — `launchCandidate()`와 **`launchBaseline()` 두 메서드**([contracts/param-search-internal.md](contracts/param-search-internal.md) §2). `launchBaseline()`은 스냅샷의 `baselineStrategyId`로 조립하고 기준 전략을 코드에 지정하지 않는다(FR-025). 이 메서드가 없으면 FR-013d의 하락 폭 필터 기준값이 만들어지지 않는다
- [X] T033 (BE) `infrastructure/launcher/CandidateJobLauncherImpl.java` — `backtest` 모듈 접수 유스케이스 호출로 두 메서드 구현. `launchBaseline()`은 스냅샷의 기준 전략 1건을 그 전략의 등록된 기본 시작값과 후보와 같은 섹터 집합·공통 구간·금액·비용·참조 버전으로 접수한다(FR-013d). **묶음 스냅샷만 보고 조립하고 현재 시각의 자료·비용 버전을 다시 읽지 않는다** (FR-007, FR-012)
- [X] T034 [P] (BE) `backtest/application/event/BacktestFinished.java` 이벤트 record 정의 — `backtest` 모듈은 `paramsearch`를 참조하지 않는다 ([contracts/param-search-internal.md](contracts/param-search-internal.md) §3)
- [X] T035 (BE) `backtest/application/service/BacktestEventService.java`에 결과 저장 커밋 후 `BacktestFinished` 발행 추가 (`@TransactionalEventListener(phase = AFTER_COMMIT)`)
- [X] T036 (BE 테스트) `backend/src/test/java/com/ssafy/thorp/backtest/application/service/BacktestEventServiceEventTest.java` — 커밋 전에는 이벤트가 발행되지 않는지 검증 — 커밋 전 발행이면 007이 없는 결과를 읽는다
- [X] T036a [P] (BE) `application/port/StrategyExecutionSupportPort.java` 정의 — `supportedStrategyIds()` 하나. "이 전략을 실행할 수 있는가"는 004의 사실이므로 004에 묻는다. 003이 대신 선언하지 않는다 ([contracts/param-search-internal.md](contracts/param-search-internal.md) §1.1, FR-026)
- [X] T036b (BE) `infrastructure/support/StrategyExecutionSupportAdapter.java` — `backtest` 모듈이 노출하는 지원 전략 목록을 읽어 포트를 구현한다. 004가 워커에 전략을 추가하고 목록에 넣으면 007은 코드 변경 없이 그 전략을 얻는다 (research R8)
- [X] T036c [P] (BE 테스트) 지원 목록에 없는 전략이 탐색 대상에서 빠지는지, 목록에 전략을 더하면 **007 코드 변경 없이** 대상이 되는지 검증 (SC-010, FR-026)
- [X] T036d (BE) `application/service/SearchTargetResolver.java` — 요청 전략을 `activeStrategies()` ∩ `supportedStrategyIds()`와 맞춰 탐색 대상을 정한다. 앞에 없으면 `STRATEGY_NOT_REGISTERED`, 뒤에 없으면 `STRATEGY_NOT_EXECUTABLE`로 **조합 생성 전에** 거부 사유를 만든다. 007 내부 전략 목록과 비교하지 않는다 (T027·T036a 의존, FR-026, research R8)

### 오류 처리

- [X] T037 [P] (BE) `common/exception/ErrorCode.java`에 `CANDIDATE_LIMIT_EXCEEDED`·`NO_VALID_COMBINATION`·`UNREGISTERED_PARAMETER_VALUE`·`ALREADY_FINISHED`·`NOTHING_TO_RETRY`·`STRATEGY_NOT_REGISTERED`·`STRATEGY_NOT_EXECUTABLE`·`REGISTRY_VERSION_CHANGED`·`REGISTRY_UNAVAILABLE` 추가
- [X] T038 (BE) `application/exception/` 예외 클래스와 `common/web/advice/GlobalExceptionHandler.java` 매핑 추가 — 미등록·실행 미지원은 422, 버전 드리프트는 409, 등록표 조회 실패는 503. HTTP 상태는 [contracts/param-search-api.md](contracts/param-search-api.md) §6과 일치 (FR-026·FR-028)

**Checkpoint**: 스키마·모델·포트 준비 완료. 사용자 스토리를 병렬로 시작할 수 있다

---

## Phase 3: User Story 1 - 실행 전 확인만 하고 한 동작으로 탐색을 실행한다 (Priority: P1) 🎯 MVP

**Goal**: 전략·섹터를 고정한 사용자가 값을 하나도 입력하지 않고 확인 화면을 보고 버튼 한 번으로 탐색을 접수하고 진행 상태를 본다

**Independent Test**: 전략 하나와 섹터를 고정한 뒤 확인 화면의 네 항목이 보이는지, 아무 값도 입력하지 않고 접수되는지, `GET /param-searches/{id}`로 진행이 보이는지 확인한다 (quickstart 시나리오 1)

### Tests for User Story 1

- [X] T039 [P] [US1] (BE 테스트) `GridExpander` 단위 테스트 — 조합 수 = 축 값 개수의 곱, 좌표가 등록표 인덱스와 일치
- [X] T040 [P] [US1] (BE 테스트) preview 작업량 집계 단위 테스트 — `totalCombinations` = `valid` + `excluded`, 사유별 건수 합이 `excluded`와 일치
- [X] T041 [P] [US1] (BE 테스트) `CandidateDispatcher` 단위 테스트 — `DISPATCHED` 후보 수가 발행 창을 넘지 않는다 (내부 계약 §4 불변식 1)
- [X] T042 [P] [US1] (BE 테스트) preview 계약 테스트 — 네 덩어리 존재, `limits.provisional`에 `maxCandidates` 포함, `validationLabel = UNVALIDATED`
- [X] T043 [P] [US1] (BE 테스트) 접수 → 발행 → `BacktestFinished` 수신 → `counts` 집계 통합 테스트 (Testcontainers)
- [X] T044 [P] [US1] (BE 테스트) 동시 `BacktestFinished` 수신에서 `counts`가 서로를 덮어쓰지 않는지 검증 (내부 계약 §4 동시 수신 경쟁)

### Implementation for User Story 1 — Backend 유스케이스

- [X] T045 [P] [US1] (BE) `application/command/PreviewParamSearchCommand.java`와 `CreateParamSearchCommand.java` (record 우선)
- [X] T046 [P] [US1] (BE) `application/result/ParamSearchPreviewResult.java`와 `ParamSearchResult.java` — `spaceSummary`는 **전략별 배열**이고 축마다 `values`·`defaultValue`·`allowedRange`·`unit`을, 전략마다 `name`·`executable`을 담는다. 모두 등록표·지원 목록 조회 결과를 옮긴 값이다 ([contracts/param-search-api.md](contracts/param-search-api.md) §1, FR-027)
- [X] T047 [US1] (BE) `application/service/CommonPeriodResolver.java` — 후보 공통 평가 구간 산출과 `shortenedBy` 계산. 모든 후보를 같은 구간으로 평가한다 (Edge Case)
- [X] T048 [US1] (BE) `application/service/ParamSearchService.preview()` — 등록 정보·실행 지원 조회 → `SearchTargetResolver` 판정 → 그리드 전개·유효성 집계·한도·공통 구간·검증 라벨 반환. 등록표 버전과 전략별 축·기본값·허용 범위를 응답에 담는다. **저장하지 않는다** (T036d 의존, FR-001·FR-003·FR-027)
- [X] T049 [US1] (BE) `ParamSearchService.create()` — 요청의 `registryVersion`이 접수 시점 버전과 다르면 `409 REGISTRY_VERSION_CHANGED`로 멈추고, 같으면 그 버전과 **실제 사용한 탐색 후보값**을 묶음에 고정해 저장한 뒤 기준 1건과 발행 창만큼 발행한다. 저장과 발행을 한 트랜잭션으로 묶는다 (`BacktestService`와 같은 규약, FR-028)
- [X] T049b [P] [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/RegistrySnapshotTest.java` — (1) 미리보기와 다른 `registryVersion`으로 접수하면 409이고 묶음이 저장되지 않는지, (2) 등록표 조회가 실패하면 503이고 캐시·기본값으로 대체 실행하지 않는지, (3) 저장된 `search_space`로 후보 집합을 재구성할 수 있는지 검증 (FR-028, research R9)
- [X] T049c [US1] (BE) 접수 시 `baselineStrategyId()`를 조회해 `selection_rule` 스냅샷에 기준 전략 ID·표시명을 고정한다. 역할 지정이 없으면 기준 실행을 만들지 않고 `drawdownFilterApplied = false`로 두며 사유를 남긴다. **기준 전략 ID를 007 코드에 두지 않는다** (FR-013d·FR-025)
- [X] T050 [US1] (BE) `application/service/CandidateDispatcher.java` — 발행 창 관리와 묶음 행 비관적 락. 후보 실행끼리 병렬화한다 (FR-008, research R2, 내부 계약 §4)
- [X] T051 [US1] (BE) `application/service/ParamSearchProgressService.java` — 후보 상태 갱신과 예상·유효·완료·실패·제외·미실행 `counts` 집계 (FR-008)
- [X] T049a [P] [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/ParamSearchReproducibilityTest.java` — 같은 등록표 버전·같은 선택 범위·같은 자료 버전으로 두 번 접수하면 후보 집합과 그리드 좌표가 완전히 같은지, `random_seed`가 두 묶음 모두 NULL인지 검증 (SC-004, 원칙 VIII)
- [X] T052 [US1] (BE) `infrastructure/listener/BacktestFinishedListener.java` — `backtestJobId`로 후보 조회, 못 찾으면 단일 실행 Job이므로 경고 없이 무시, 찾으면 다음 후보 발행 요청

### Implementation for User Story 1 — Backend API

- [X] T053 [P] [US1] (BE) `presentation/request/ParamSearchPreviewRequest.java`·`ParamSearchCreateRequest.java`와 Bean Validation — 종목 1~50개·`{6자리}.XKRX`·중복 금지, `space`는 **전략 ID → (축 → 값 배열)** 중첩이며 생략 허용. 실행 요청은 `registryVersion` 필수(미리보기 응답 값을 되돌려 보낸다), 미리보기에서는 무시 (FR-028)
- [X] T054 [P] [US1] (BE) `presentation/response/ParamSearchPreviewResponse.java`·`ParamSearchResponse.java`
- [X] T055 [US1] (BE) `presentation/controller/ParamSearchController.java`에 `POST /param-searches/preview`(200)와 `POST /param-searches`(202) 추가
- [X] T056 [US1] (BE) 같은 컨트롤러에 `GET /param-searches/{searchId}` 추가 — `status`·`counts`·`config` 반환, `selection`은 아직 `null`. 실패해도 200이다
- [X] T057 [US1] (BE) `space`에 등록표 밖 값이 오면 `400 UNREGISTERED_PARAMETER_VALUE` 처리 (FR-002)

### Implementation for User Story 1 — Frontend

- [ ] T058 [P] [US1] (FE) `frontend/src/api/paramSearch.ts`에 `previewParamSearch`·`createParamSearch`·`getParamSearch` 구현
- [ ] T059 [P] [US1] (FE) `frontend/src/components/paramsearch/ProvisionalBadge.tsx` — `limits.provisional`에 든 항목에 임시값 표시 (FR-005a)
- [ ] T060 [US1] (FE) `frontend/src/components/paramsearch/PreflightPanel.tsx` — 대상 전략·탐색 범위 요약·예상 작업량·적용 한도 **네 항목만**, 행동 버튼 1개, 후보 숫자 입력 없음. 전략 이름은 `strategies[].name`에서만 읽고 화면에 전략 목록을 두지 않는다 (원칙 II, SC-001, FR-025)
- [ ] T061 [US1] (FE) `frontend/src/components/paramsearch/SpaceEditor.tsx` — 상세 변경. 축 라벨·단위·기본 시작값·허용 범위·후보값을 **`spaceSummary`에서만** 읽어 전략별로 렌더하고, 범위 밖 값은 입력할 수 없다. 기본값에 '추천'·'최적' 문구를 붙이지 않는다. **수수료·세금·평가 기간을 탐색 변수로 노출하지 않는다** (FR-004·FR-025·FR-027, 원칙 III)
- [ ] T062 [US1] (FE) `frontend/src/hooks/useParamSearchStatus.ts` — 진행 상태 폴링 훅 (`GET /param-searches/{id}`)
- [ ] T063 [US1] (FE) `frontend/src/pages/ParamSearchPage.tsx`와 라우팅 추가, 003 전략 화면의 '설정 조합 자동 비교' 진입점 연결
- [ ] T063a [US1] (FE) `frontend/src/components/paramsearch/RegistryChangedDialog.tsx` — `409 REGISTRY_VERSION_CHANGED`를 받으면 미리보기를 다시 호출해 **바뀐 후보값·조합 수**를 보이고, 사용자가 확인하기 전에는 실행을 다시 보내지 않는다 (FR-028)
- [ ] T063b [P] [US1] (FE 테스트) 등록표 경계 흐름 테스트 — 409에서 확인 없이 재실행 요청이 나가지 않고, `503 REGISTRY_UNAVAILABLE`은 '잠시 후 다시' 안내로 끝나며 캐시된 요약으로 실행되지 않는지 (FR-028)
- [ ] T064 [P] [US1] (FE 테스트) `PreflightPanel` 테스트 — 값을 하나도 입력하지 않고 실행 버튼이 활성인지 (SC-001)

**Checkpoint**: 탐색을 접수해 진행 상태까지 볼 수 있다. 선정 결과는 아직 없다

---

## Phase 4: User Story 2 - 소수 후보의 수익·큰 손실·비용을 비교하고 근거를 본다 (Priority: P1)

**Goal**: 완료된 묶음에서 사전 기준으로 고른 5개 후보의 세 숫자를 먼저 보고, 상세에서 안정성·선정 이유·탈락 사유·전체 후보를 본다

**Independent Test**: 완료된 묶음을 열어 표시 후보의 세 숫자가 먼저 보이고, 상세에 안정성·선정/탈락 사유·전체 후보가 있고, '추천'·'1위' 배지가 없는지 확인한다 (quickstart 시나리오 5)

**Depends on**: US1 (완료된 후보가 있어야 선정할 대상이 생긴다)

### Tests for User Story 2

- [X] T065 [P] [US2] (BE 테스트) `NeighborStabilityCalculator` 단위 테스트 — 1축 ±1만 이웃, **전략이 다른 후보는 이웃이 아님**, 경계 후보는 있는 이웃만, 이웃 0개면 `calculable = false`이고 `value`는 `null`. 숫자가 아닌 축에서도 인덱스만으로 판정되는지 포함 (FR-013a·FR-029)
- [X] T066 [P] [US2] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/selection/DrawdownFilterTest.java` — 스냅샷의 기준 전략 결과보다 큰 후보만 탈락
- [X] T067 [P] [US2] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/selection/StabilityFilterTest.java` — 계산 불가 후보는 통과 처리 (FR-013b)
- [X] T068 [P] [US2] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/selection/CandidateSelectorSortTest.java` — 동률은 5개를 넘겨 전부 표시, 임의 순서 없음 (research R3)
- [X] T069 [P] [US2] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/selection/CandidateSelectorShortfallTest.java` — 문턱을 낮춰 채우지 않는다 (원칙 VII)
- [X] T070 [P] [US2] (BE 테스트) 기준 백테스트 실패 시 하락 폭 필터 미적용 + `drawdownFilterApplied = false` 테스트
- [X] T071 [P] [US2] (BE 테스트) 후보 목록 계약 테스트 — `EXCLUDED`·`FAILED`·`SKIPPED`·`PENDING` 후보의 성과 필드가 **`null`이고 0이 아니다** (FR-011)
- [X] T072 [P] [US2] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/ParamSearchSelectionOnceTest.java` — 선정 계산이 묶음당 정확히 1회인지 검증 (내부 계약 §4 불변식 3)

### Implementation for User Story 2 — Backend 계산

- [X] T073 [US2] (BE) `application/service/NeighborStabilityCalculator.java` — 그리드 좌표로 이웃 판정, 이웃과의 비용 차감 후 수익 차 절댓값 평균. 완료 후보만 대상이고 **이웃은 같은 `strategy_id` 안에서만** 찾는다. 인덱스 비교만 하므로 축의 종류를 구분하지 않는다 (FR-013a·FR-029)
- [X] T074 [US2] (BE) `application/service/selection/DrawdownFilter.java` — 가장 크게 줄었던 폭이 **스냅샷의 기준 전략 결과**보다 크지 않은 후보만 통과. 기준 전략이 무엇인지 알지 못한 채 값만 쓴다 (FR-013·FR-013d)
- [X] T074a [P] [US2] (BE 테스트) 기준 전략 역할이 지정되지 않은 묶음에서 하락 폭 필터가 빠지고 `drawdownFilterApplied = false`가 응답에 나오는지, 선정 코드에 기준 전략 ID 리터럴이 없는지 검증 (FR-013d, SC-010)
- [X] T075 [US2] (BE) `application/service/selection/StabilityFilter.java` — 묶음 내 유효 후보의 상위 50%만 통과, 계산 불가는 통과
- [X] T076 [US2] (BE) `application/service/CandidateSelector.java` — 두 필터 적용 → 비용 차감 후 수익 정렬 → 상위 5 → 동률 전부. 후보별 선정·탈락 사유 기록 (T073~T075 의존)
- [X] T077 [US2] (BE) `ParamSearchProgressService`에 묶음 종료 감지 추가 — `PENDING` 0 && `DISPATCHED` 0이면 안정성 → 선정 순서로 1회 실행
- [X] T078 [US2] (BE) 선정 결과를 `param_search.selection` 스냅샷으로 저장 ([data-model.md](data-model.md) §2)

### Implementation for User Story 2 — Backend API

- [X] T079 [P] [US2] (BE) `application/result/CandidatePageResult.java`와 `presentation/response/CandidatePageResponse.java`
- [X] T080 [US2] (BE) `GET /param-searches/{searchId}/candidates` — 페이징(`size` 최대 200), `status`·`selected` 필터, 허용 정렬 목록(`candidateNo`·`netReturn`·`maxDrawdown`·`tradingCost`·`stability`)만 수용
- [X] T081 [US2] (BE) `GET /param-searches/{searchId}` 응답에 `selection`과 `baseline` 채우기 — `selection.displayed`는 **순위가 아니라 정렬 지표를 밝힌 집합**이다 (plan 설계 제약 1)

### Implementation for User Story 2 — Frontend

- [ ] T082 [P] [US2] (FE) `frontend/src/components/paramsearch/CandidateSummaryCard.tsx` — 비용 차감 후 수익·가장 크게 줄었던 폭·거래비용 세 숫자 (FR-018)
- [ ] T083 [US2] (FE) `frontend/src/components/paramsearch/SelectionResult.tsx` — 표시 후보 집합. **순위 번호·추천 문구·우승 배지 금지**, 정렬 지표 이름("비용 차감 후 수익") 명시 (plan 설계 제약 1·3)
- [ ] T084 [US2] (FE) `frontend/src/components/paramsearch/CandidateDetail.tsx` — 인접 설정 안정성·선정 이유·탈락 사유
- [ ] T085 [US2] (FE) `frontend/src/components/paramsearch/CandidateTable.tsx` — 전체 후보 목록과 한 항목 기준 정렬 (FR-017이 허용하는 범위)
- [ ] T086 [P] [US2] (FE) 계산 불가·미완료 후보 표시 — `null` 성과를 "0%"가 아니라 사유와 함께 보인다 (FR-011, 원칙 VI)
- [ ] T087 [US2] (FE) `frontend/src/components/paramsearch/CompareHandoff.tsx` — 후보를 005 비교 대상으로 넘기는 연결과 '자동 탐색 후보 비교' 표시 (FR-019). 005 미구현이면 진입 지점과 표시만 만든다
- [ ] T088 [P] [US2] (FE 테스트) `frontend/src/components/paramsearch/SelectionResult.test.tsx` — 순위 번호·추천·우승 표현이 0건인지 검증 (SC-006)

**Checkpoint**: US1과 US2가 각각 독립적으로 동작한다

---

## Phase 5: User Story 3 - 유효하지 않은 조합은 계산 전에 걸러지고, 중단되면 부분 결과로 남는다 (Priority: P2)

**Goal**: 제약 위반 조합이 계산 전에 사유와 함께 제외되고, 취소·한도 도달 시 부분 결과임과 완료 범위가 보인다

**Independent Test**: 등록표 유효성 규칙이 거부하는 조합을 포함해 실행해 그 조합이 `EXCLUDED` + 규칙이 돌려준 사유로 남는지, 실행 중 취소하면 부분 결과와 완료 범위가 보이는지 확인한다 (quickstart 시나리오 2·3·4)

**Depends on**: Foundational의 `CandidateValidator`(T031). US1의 접수 경로에 거부·취소를 덧붙인다

### Tests for User Story 3

- [X] T089 [P] [US3] (BE 테스트) `CandidateValidator` 단위 테스트 — fixture 등록표가 거부하는 조합이 100% 제외되고 **규칙이 돌려준 사유 코드가 그대로** 남는지, 위반 규칙이 007 코드에 없는지 검증 (SC-002, FR-025)
- [X] T090 [P] [US3] (BE 테스트) `EXCLUDED` 후보에 `backtest_job` 행이 만들어지지 않는지 검증 — 계산 전 제외다
- [ ] T091 [P] [US3] (BE 테스트) 상한 초과 접수 통합 테스트 — `422 CANDIDATE_LIMIT_EXCEEDED`, 묶음은 `REJECTED`로 저장, 후보·Job 0건, **표본 추출 없음**
- [ ] T092 [P] [US3] (BE 테스트) 유효 조합 0건 통합 테스트 — `422 NO_VALID_COMBINATION`과 어느 제약인지 한 문장
- [ ] T093 [P] [US3] (BE 테스트) 취소 통합 테스트 — `PENDING` → `SKIPPED`, 취소 후 추가 발행 0건, `PARTIAL` 전이 (내부 계약 §4 불변식 2·4)
- [ ] T094 [P] [US3] (BE 테스트) 끝난 묶음 취소 시 `409 ALREADY_FINISHED`

### Implementation for User Story 3 — Backend 거부

- [ ] T095 [US3] (BE) `ParamSearchService.create()`에 상한 초과 거부 추가 — 묶음을 `REJECTED`로 저장하고 `stop_reason` 기록. 유효 후보 수·상한·좁히는 방법을 메시지에 담는다 (FR-005b)
- [ ] T096 [US3] (BE) 같은 지점에 유효 조합 0건 거부 추가 — `REJECTED` 저장과 제약 사유 한 문장 (Edge Case)

### Implementation for User Story 3 — Backend 중단·취소

- [ ] T097 [US3] (BE) `ParamSearchService.cancel()` — `PENDING` 후보 전부 `SKIPPED`, 추가 발행 중단. 발행된 후보는 완주한다 (research R2)
- [ ] T098 [US3] (BE) `POST /param-searches/{searchId}/cancel` 엔드포인트와 `skipped`·`inFlight` 응답, 끝난 묶음은 409
- [ ] T099 [P] [US3] (BE) `application/service/limit/TimeLimitGuard.java` — 묶음 시작 시각 기준 실행시간 한도 판정
- [ ] T100 [P] [US3] (BE) `application/service/limit/FailureLimitGuard.java` — 연속 실패 한도 판정
- [ ] T101 [US3] (BE) 한도 도달 시 `stop_reason`(`CANDIDATE_LIMIT`·`TIME_LIMIT`·`FAILURE_LIMIT`) 기록과 발행 중단 (T099, T100 의존)
- [ ] T098a [US3] (BE) `ParamSearchService.retryFailed()` — `FAILED` 후보만 `DISPATCHED`로 되돌려 재발행하고 `retry_count`를 올린다. 묶음 시작 시점 스냅샷(참조 버전·탐색 공간·선정 규칙)을 그대로 쓰고 새 묶음을 만들지 않는다. `SKIPPED` 후보는 대상이 아니다 (FR-024)
- [ ] T098b [US3] (BE) `POST /param-searches/{searchId}/retry-failed` 엔드포인트와 응답 DTO — 재시도 대상 수·재발행 수 반환, 실패 후보가 없으면 `409 NOTHING_TO_RETRY` ([contracts/param-search-api.md](contracts/param-search-api.md) §5.1)
- [ ] T098c [P] [US3] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/ParamSearchRetryTest.java` — 재시도가 새 묶음을 만들지 않고 스냅샷을 재사용하는지, `SKIPPED`가 제외되는지, 재시도 완료 후 선정이 다시 계산되는지 검증 (SC-009)
- [ ] T101a [P] [US3] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/ParamSearchLimitReachedTest.java` — 실행시간·연속 실패 한도 도달 시 발행이 멈추고 `PARTIAL` + `stop_reason`이 남으며 부분 결과 문장이 나오는지 검증 (SC-003)
- [ ] T102 [US3] (BE) `GET /param-searches/{searchId}` 응답에 `partial`·`partialNote`·`stopReason` 채우기 — 완료·미실행 후보 수를 문장으로 (FR-009)
- [ ] T103 [US3] (BE) 후보 계산 실패·자료 부족을 `FAILED` + `reason_code`로 기록하고 성과 필드를 `null`로 유지 (FR-011)

### Implementation for User Story 3 — Frontend

- [ ] T104 [P] [US3] (FE) `frontend/src/components/paramsearch/RejectionNotice.tsx` — 접수 거부 안내 **5종**(상한 초과·유효 조합 0건·미등록 전략·실행 미지원 전략·등록표 조회 실패)을 오류 화면 없이 한 문장으로. 미등록과 실행 미지원은 다른 문장으로 구분한다 (FR-026, 공통 성공 기준)
- [ ] T105 [US3] (FE) `frontend/src/components/paramsearch/PartialResultNotice.tsx` — 부분 결과임과 완료·미실행 후보 수
- [ ] T106 [US3] (FE) `frontend/src/components/paramsearch/CandidateReason.tsx` — 후보별 제외·실패·미실행 사유 표시. 낮은 수익과 구분 (FR-011)
- [ ] T107 [US3] (FE) `frontend/src/components/paramsearch/CancelButton.tsx` — 취소 버튼과 취소 후 상태 반영
- [ ] T107a [P] [US3] (FE) `frontend/src/components/paramsearch/RetryFailedButton.tsx` — 실패 후보가 있을 때만 보이는 재시도 버튼과 재시도 후 상태 반영 (FR-024)

**Checkpoint**: 계산 낭비와 중단 처리가 모두 동작한다

---

## Phase 6: User Story 4 - 탐색 성과와 미사용 검증을 구분해서 본다 (Priority: P2)

**Goal**: 후보마다 '탐색 결과 / 미사용 검증 결과 / 미검증'이 구분 표시되고, 기준을 바꿔 다시 실행하면 새 실험으로 기록된다

**Independent Test**: 평가 분할이 등록되지 않은 탐색에서 모든 후보가 '미검증'으로 보이는지, 기준을 바꿔 재실행하면 새 묶음이 되고 이전 묶음 표시가 그대로인지 확인한다

**Depends on**: US1(묶음 접수), US2(후보 표시)

### Tests for User Story 4

- [ ] T108 [P] [US4] (BE 테스트) 평가 분할 미등록이면 전 후보가 `UNVALIDATED`인지 검증하고, **이를 검증 통과로 보고하지 않는다는 주석**을 테스트에 남긴다 (research R5, 완료의 구분)
- [ ] T109 [P] [US4] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/selection/HeldOutExclusionTest.java` — 잠금 구간 성과가 선정 계산에 입력되지 않는지 검증 (SC-005)
- [ ] T110 [P] [US4] (BE 테스트) 선정 기준·범위를 바꿔 재실행하면 새 `searchId`가 생기고 이전 묶음의 `selection`·상태가 불변인지 통합 테스트 (FR-013c)

### Implementation for User Story 4 — Backend

- [ ] T111 [P] [US4] (BE) 후보 생성 시 `validation_label` 기본값 `UNVALIDATED` 적용과 `period_performance` JSONB 자리 예약 (FR-014, research R5)
- [ ] T112 [US4] (BE) preview·상태·후보 응답에 `validationLabel`과 `validationNote` 노출 — 실행 전에도 미검증임을 알린다. 탐색 성과와 미사용 구간 성과를 섞지 않는다 (FR-015)
- [ ] T113 [US4] (BE) 선정 규칙 스냅샷 불변성 보장 — 기존 묶음의 `selection_rule`을 수정하는 경로를 만들지 않고, 변경은 새 묶음으로만 (FR-013c, 원칙 VII)

### Implementation for User Story 4 — Frontend

- [ ] T114 [P] [US4] (FE) `frontend/src/components/paramsearch/ValidationBadge.tsx` — 후보마다 검증 상태 상시 표시 (plan 설계 제약 2)
- [ ] T115 [US4] (FE) `frontend/src/components/paramsearch/ValidationNote.tsx` — 미검증 안내 문구. '미검증·개선 근거 없음·판단 보류'를 결과에 표시하고 기능을 막지 않는다 (FR-016)
- [ ] T116 [P] [US4] (FE 테스트) `frontend/src/components/paramsearch/ValidationBadge.test.tsx` — 검증 상태 배지 누락이 0건인지 검증

**Checkpoint**: 네 사용자 스토리가 모두 독립적으로 동작한다

---

## Phase 7: User Story 5 - 전략이 추가돼도 007을 고치지 않고 탐색 대상이 된다 (Priority: P1)

**Goal**: 003 등록표에 활성 전략을 추가하고 004가 실행을 지원하면 007 코드 변경 없이 그 전략이 미리보기와 후보 조합 생성에 나타난다

**파일 순서는 스토리 번호 순이고 착수는 Foundational 직후다.** 테스트 T127~T133은 M2, 화면 정리 T135·T136은 M4, 거부 마무리 T134는 M7이 가져간다 (Incremental Delivery 2단계).

**Independent Test**: 테스트 등록표에 여섯 번째 전략을 넣고 007 운영 코드를 한 줄도 바꾸지 않은 채 미리보기와 후보 조합 생성이 동작하는지 확인한다 (quickstart 시나리오 7)

**Depends on**: Foundational의 T027·T028·T036d·T036a (포트와 판정). 거부 저장은 US3의 `REJECTED` 경로(T095·T096)에 덧붙는다

### Tests for User Story 5

- [X] T127 [P] [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/registry/RegistryFixtures.java` — 테스트용 등록표 fixture 구현. 전략 수·축 수·후보 개수를 자유롭게 구성하고, 운영 코드가 아니므로 전략 값을 직접 담는다(FR-025의 예외)
- [X] T128 [P] [US5] (BE 테스트) 활성 전략 전부가 **같은 등록표 계약 경로**로 처리되는지 검증 — 어느 전략도 예외 경로·전용 분기를 타지 않는다 (spec US5 시나리오 1)
- [X] T129 [P] [US5] (BE 테스트) fixture에 여섯 번째 전략을 더하면 미리보기와 후보 조합 생성이 **운영 코드 변경 없이** 동작하는지 검증 (SC-010, spec US5 시나리오 2)
- [X] T130 [P] [US5] (BE 테스트) 축 개수와 축별 후보 개수가 서로 다른 전략을 섞어 실행했을 때 전체 조합 수·유효 후보 수·제외 후보 수가 등록값으로 계산한 기대치와 일치하는지 검증 (SC-011, spec US5 시나리오 3)
- [X] T131 [P] [US5] (BE 테스트) 미등록 전략은 `422 STRATEGY_NOT_REGISTERED`, 실행 미지원 전략은 `422 STRATEGY_NOT_EXECUTABLE`로 **조합 생성 전에** 거부되고 두 사유가 구분돼 저장되는지 검증 (SC-012, spec US5 시나리오 4)
- [X] T132 [P] [US5] (BE 테스트) 같은 등록표 버전·같은 선택 범위가 같은 후보 집합을 만드는지 **전략 여러 개를 섞어** 검증 — T049a의 단일 전략 재현 테스트를 다중 전략으로 넓힌다 (spec US5 시나리오 5, SC-004)
- [X] T133 [P] [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/StrategyIndependenceTest.java` — `paramsearch` 운영 소스에 전략 ID 문자열 리터럴·전략 목록 상수·`strategyId` 분기가 없는지 정적으로 검사한다(ArchUnit 또는 소스 스캔). 테스트 fixture와 자원 파일은 대상에서 뺀다 (SC-010, FR-025)

### Implementation for User Story 5

- [ ] T134 [US5] (BE) `SearchTargetResolver`를 `preview()`·`create()` 경로에 연결해 거부를 마무리한다 — 미등록·실행 미지원을 조합 생성 전에 끊고, 묶음을 `REJECTED`로 저장하며 `stop_reason`에 두 사유를 구분해 남긴다. 미리보기에서는 저장 없이 사유만 알린다 (T036d·T095·T096 의존, FR-026)
- [ ] T135 [US5] (FE) `SpaceEditor`·`PreflightPanel`에서 전략 목록·파라미터 기본값·허용 범위·단위를 쓰는 지점을 모두 응답 기반으로 정리하고, `frontend/src/`에 남은 전략 상수를 제거한다 (FR-025)
- [ ] T136 [P] [US5] (FE 테스트) `frontend/src/components/paramsearch/SpaceEditor.test.tsx` — fixture 응답에 없던 전략·축을 넣어도 화면 코드 변경 없이 렌더되고, `strategyId` 분기가 없는지 검증 (SC-010)

**Checkpoint**: 다섯 사용자 스토리가 모두 독립적으로 동작하고, 전략 추가가 007을 건드리지 않는다

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 여러 스토리에 걸친 기록·문서·검증

### Backend

- [ ] T117 (BE) 실행 측정 기록 — 실행 유형 `AUTO_SEARCH_BATCH`로 대기 포함 소요 시간·동시 처리 수·실패·건당 원가 기록. 계산·데이터·저장 원가를 구분한다 (FR-010, FR-023, [contracts/param-search-internal.md](contracts/param-search-internal.md) §5). **측정값을 목표로 제시하지 않는다**
- [ ] T117a (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/ParamSearchRecordCompletenessTest.java` — 완료·부분 완료 묶음이 FR-020 기록 항목 전체(탐색 공간·탐색 방법·전체 후보·미실행 수·제외 사유·평가 분리·정렬 및 선정 규칙·동일 비중 기준 결과·참조 버전)를 갖는지, 제외 후보도 사유를 남기는지 검증. 이것이 FR-022가 요구하는 '한 묶음으로 다시 열리는' 저장 상태이며 006 화면 구성은 범위 밖이다 (SC-007, FR-022, 원칙 VIII)
- [ ] T118 (BE) `./gradlew spotlessApply` 적용과 `./gradlew test` 통과 확인

### Frontend

- [ ] T119 (FE) `npm run lint`와 `npm test` 통과 확인

### 문서

- [ ] T120 [P] (DOCS) `docs/specs/param-search-api/README.md` 신설 — [contracts/param-search-api.md](contracts/param-search-api.md)를 `docs/specs/` 계약 형식으로 옮긴다 (specs/README 공통 가정: 기존 API 스펙과 같은 위치에 둔다)
- [ ] T121 [P] (DOCS) `docs/adr/0005-param-search-dispatch-window.md` — 발행 창 결정과 전량 발행·전용 큐 대안을 남긴다 (research R2)
- [ ] T122 [P] (DOCS) `.gitlab/merge_request_templates/Default.md` 리뷰 포인트에 "순위 배지·추천 표현 0건", "결제·포인트·상품 선택 단계 0건", **"전략 ID 상수·전략별 분기 0건"** 항목 추가 (plan 설계 제약, FR-010·FR-025)
- [ ] T123 [P] (DOCS) `specs/007-auto-parameter-search/spec.md`의 PD-05 표와 `specs/README.md`의 상태 열을 확정 결과로 동기화

### 검증

- [ ] T124 (BE 테스트) `cd worker && pytest` 회귀 확인 — 워커를 바꾸지 않았음을 증명한다
- [ ] T125 (DOCS) [quickstart.md](quickstart.md) 시나리오 7개(전략 비의존성 시나리오 7 포함)를 수동 실행하고 결과를 `specs/007-auto-parameter-search/quickstart.md` 아래에 기록
- [ ] T126 (DOCS) T117의 측정값을 PD-06(실행 한도) 입력으로 정리해 [004](../004-backtest-execution/spec.md) 담당에게 전달

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**: 선행 없음. 즉시 시작
- **Phase 2 Foundational**: Setup 완료 후. **모든 사용자 스토리를 막는다**
- **Phase 3~7 User Stories**: Foundational 완료 후
- **Phase 8 Polish**: 원하는 스토리들이 끝난 뒤

### User Story Dependencies

- **US1 (P1)**: Foundational 후 바로 시작. 다른 스토리에 의존하지 않는다 → **MVP**
- **US2 (P1)**: US1 필요. 선정할 완료 후보가 있어야 한다. 다만 `NeighborStabilityCalculator`·필터·`CandidateSelector`(T073~T076)는 US1과 **병렬로 개발**하고 고정 입력으로 단위 테스트할 수 있다
- **US3 (P2)**: Foundational의 T031 필요. 거부·취소는 US1의 접수 경로에 덧붙는다. `CandidateValidator` 테스트(T089·T090)는 US1 없이도 가능
- **US4 (P2)**: US1·US2 필요. 작업량이 가장 작다(라벨·표시 중심)
- **US5 (P1)**: Foundational의 포트(T027·T028·T036d·T036a) 필요. 테스트 대부분은 fixture만으로 US1 없이 돌아가고, 거부 마무리(T134)만 US1·US3의 접수·`REJECTED` 경로 뒤다. **다른 스토리를 막지는 않지만 전략이 늘어난 뒤에 붙이면 이미 하드코딩이 퍼져 있어 회수 비용이 커진다** — Foundational 직후에 함께 닫는다

### 묶음(브랜치·MR) 진행 순서

브랜치·MR 단위는 [plan.md](plan.md) "작업 묶음 구성"의 M1~M10이다. 아래는 그 묶음들의 선후 관계다.

```text
M1 ─▶ M2 ─▶ M3 ─┬─▶ M5 ─▶ M6           선정 계산 → 조회 API → 결과 화면·비교 연결
                 ├─▶ M7 ─▶ M8           거부·취소·한도 → FE 표시·배지
                 └─▶ M9                 검증 라벨·실행 측정 (M5 뒤)
M4                                       (FE, 계약 문서로 M1~M3과 병행 가능)
M10                                      (DOCS, 어느 시점에서든 병렬)
```

- **M1이 나머지 전부를 막는다.** 도메인·스키마·영속화가 없으면 어느 묶음도 컴파일되지 않는다.
- **M2는 M1 뒤다** — 그리드 전개가 도메인 VO(`SearchSpace`·`GridCoordinates`)를 쓴다.
- **M3은 M2 뒤다** — 접수 유스케이스가 등록표 포트·그리드 전개·유효성 판정을 모두 호출한다.
- **M4는 선행이 없다** — 계약(`contracts/param-search-api.md`)이 응답 모양을 고정했으므로 FE는 M1~M3을
  기다리지 않는다. 화면 연동 확인만 M3 이후다.
- **M5는 M3 뒤다** — 선정 계산 자체(T073~T076)는 고정 입력으로 단위 검증되지만, 조회 API(T079~T081)가
  접수된 묶음을 전제한다. 계산만 먼저 열려면 M5를 다시 쪼개야 하므로 이 판에서는 M3 뒤로 둔다.
- **M7은 M3 뒤다** — 거부·취소가 접수 경로에 덧붙는다.
- **M8은 M7·M9 뒤다** — 거부 표시는 M7, 검증 배지는 M9의 라벨을 쓴다.
- **M9는 M5 뒤다** — 검증 라벨이 선정 결과에 붙는다.
- **US5(전략 비의존성)는 새 묶음을 만들지 않는다** — 포트·전개·정적 검사(T026a~T026c, T028a, T036d, T036a~T036c, T127~T133)는 M2, 접수 경로 연결(T134)과 거부 저장 테스트는 M7, 화면 정리(T135·T136)는 M4가 가져간다 (plan.md "2026-09-11 개정이 묶음에 주는 영향").
- 한 묶음이 곧 한 MR이므로 **묶음 안의 task를 절반만 올리지 않는다**(묶음 조건 3). 묶음 안의 진행은
  커밋으로 쪼갠다.

### 파트 사이 순서

- `(DB)` T008~T010 → `(BE)` 영속화 T021~T026: 스키마가 엔티티보다 먼저다 (`ddl-auto=validate`)
- `(BE)` 응답 DTO → `(FE)` 화면: 계약 문서가 이미 응답 모양을 고정했으므로 **프론트는 백엔드 구현을 기다리지 않고 `contracts/param-search-api.md`로 병행**할 수 있다
- `(DOCS)` T120~T123은 어느 단계에서든 병렬

### Within Each User Story

- 테스트를 먼저 쓰고 실패시킨 뒤 구현한다
- enum·VO → 도메인 모델 → 영속화 → 유스케이스 → 컨트롤러 → 화면
- 계산 서비스(T073~T076)는 API보다 먼저

### Parallel Opportunities

- Phase 1의 T002~T007 전부 병렬
- Phase 2의 enum·VO T011~T017 전부 병렬 (서로 다른 파일)
- Phase 2의 T021·T022·T023, T027, T028a, T032, T036a, T036c, T034, T037 병렬
- Phase 7(US5)의 테스트 T127~T133 전부 병렬 — fixture만 쓰므로 다른 스토리 구현을 기다리지 않는다
- 각 스토리의 테스트 태스크는 전부 병렬
- **BE와 FE는 스토리 안에서 병렬** — 계약이 고정되어 있다
- Foundational 완료 후 US1·US3의 검증 부분을 다른 담당이 동시에 진행 가능
- **묶음 수준 병렬**: 묶음을 10개로 합친 뒤 동시에 열 수 있는 조합은 **M1~M3(BE 직렬)과 M4(FE), M10(DOCS)** 세 갈래다. M3이 끝나면 M5·M7이 갈라져 병렬이 된다. 초판의 B03·B05·B07·B08 병렬은 M1·M2 안으로 들어가 커밋 병렬이 되었다

---

## Parallel Example: User Story 1

```bash
# 테스트 먼저 (전부 병렬)
Task: "T039 GridExpander 단위 테스트"
Task: "T040 preview 작업량 집계 단위 테스트"
Task: "T041 CandidateDispatcher 발행 창 불변식 테스트"
Task: "T042 preview 계약 테스트"
Task: "T043 접수→발행→이벤트→집계 통합 테스트"
Task: "T044 동시 이벤트 counts 정합 테스트"

# command·result·DTO (병렬)
Task: "T045 PreviewParamSearchCommand / CreateParamSearchCommand"
Task: "T046 ParamSearchPreviewResult / ParamSearchResult"
Task: "T053 Request DTO + Bean Validation"
Task: "T054 Response DTO"

# BE 유스케이스와 FE 화면을 다른 담당이 동시에
Task: "T048~T052 preview·create·dispatcher·listener"      # BE 담당
Task: "T058~T064 api 모듈·확인 화면·상세 변경·폴링"          # FE 담당 (계약 문서 기준)
```

---

## Implementation Strategy

### MVP First (User Story 1만)

1. Phase 1 Setup + Phase 2 도메인·영속화 (T001~T004, T007~T026) = 묶음 **M1** — **여기가 가장 크다. 나머지 전부를 막는다**
2. Phase 2 경계·전개 (T026a~T026c, T027~T039, T089, T090, T127~T133) = 묶음 **M2**
3. Phase 3 User Story 1 Backend (T040~T057) = 묶음 **M3**, Frontend (T005·T006, T058~T064) = 묶음 **M4**
4. **멈추고 검증**: quickstart 시나리오 1로 US1 단독 확인
5. 데모 가능 — 탐색을 접수해 진행 상태까지 보인다

**MVP = 묶음 4개(M1~M4) = 브랜치·MR 4개.** T### 기준으로는 T001~T064에 T026a~T026c·T089·T090·
T127~T133·T135·T136을 더한 것이다 (M2가 US3 단계의 유효성 테스트와 US5의 비의존성 검증을 함께
가져가고, M4가 화면 정리를 가져간다).

### Incremental Delivery

1. Setup + Foundational (M1·M2) → 기반 완료
2. **US5 검증 (M2에 포함) → 시나리오 7 → 전략 비의존성을 기반 단계에서 먼저 고정**
3. US1 (M3·M4, US5 화면 정리 포함) → 시나리오 1 → **MVP 데모**
4. US2 (M5·M6) → 시나리오 5 → 결과 비교 데모
5. US3 (M7 + US5 거부 마무리 T134, M8 앞부분) → 시나리오 2·3·4 → 낭비 차단과 중단 처리
6. US4 (M9·M8 배지) → 검증 상태 표시
7. Polish (M10) → 문서·회귀 (실행 측정은 M9가 가져갔다)

각 단계가 MR 몇 개인지는 위 묶음 개수와 같다. 릴리즈는 매주 금요일이므로(constitution Git 규칙)
한 주에 닫을 묶음을 골라 쌓는다.

### 착수 전에 알아둘 것

- **004 없이는 종단 확인이 momentum 단일 종목으로만 된다.** 섹터 2개 이상은 후보 Job이 `FAILED`가 된다. quickstart가 이 전제로 쓰여 있다
- `registryVersion`이 `PROVISIONAL-*`인 동안의 결과는 PD-02 확정 등록표의 결과가 아니다
- **전략 데이터는 `parameter-registry-provisional.yml` 한 곳에만 있다.** 007 운영 코드(백엔드·프론트)에 전략 ID·전략명·파라미터값·허용 범위를 쓰는 순간 T133·T136이 실패한다 (FR-025)
- 등록표 조회 실패(503)와 버전 드리프트(409)는 묶음을 저장하지 않는다. 미등록·실행 미지원 거부는 `REJECTED`로 저장한다 (research R9)
- 전 후보 `UNVALIDATED`라 SC-005는 자동 만족이며 이를 검증 통과로 보고하지 않는다
- **동일 결과 재사용(캐시)은 004 소유 미구현 항목이다.** 007에 별도 캐시를 만들지 않는다 (plan Phase 1 이후 재확인). 진행·취소·재시도는 007이 갖췄다(FR-024)
- **SC-008(사용성 관찰)에는 구현 태스크가 없다.** PD-14의 관찰 항목이며 코드로 만들 수 있는 일이 아니다. specs/README "공통 검증·완료 규칙"의 관찰에 포함된다
