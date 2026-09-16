# Tasks: 전략 선택·상세 설정

**Input**: Design documents from `specs/003-strategy-settings/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: 포함한다. constitution "완료의 정의" 조건 2가 "테스트 또는 동작 확인 완료"를 Task 완료 조건으로 두고 있고, plan.md "Testing"이 등록표 검증 방식 둘(로더 기동 검사·계약 픽스처 대조)을 이미 정했다. 각 테스트 태스크는 [quickstart.md](quickstart.md)의 확인 항목 번호를 적는다.

**Organization**: 사용자 스토리별로 묶고, 각 단계 안에서 파트별로 다시 나눈다.

## Pre-Implementation Quality Gate

[S15P21E205-245](https://ssafy.atlassian.net/browse/S15P21E205-245)의
[요구사항 품질 체크리스트](checklists/review.md)와 `speckit-analyze` 결과를 먼저 반영한다. 이 문서 작업은
`docs(fe)/245-spec-003-quality-review`에서 수행하며 `docs(fe)/229-spec-003-tasks` 병합이 선행한다.

아래 항목이 해결되기 전에는 적은 범위의 구현 묶음을 완료·병합하지 않는다. 값과 무관한 구조·계약·합성 테스트는 먼저 수행할 수 있다.

- ~~FR-002의 행동 버튼 두 개와 constitution 원칙 II의 충돌~~ — [S15P21E205-253](https://ssafy.atlassian.net/browse/S15P21E205-253)에서 원칙 II 예외 조항이 2026-09-15 전체 회의 승인을 받아(3.4.0) 해제됐다. B05·B11 착수를 막지 않는다
- ~~PD-02 미확정~~ — [S15P21E205-254](https://ssafy.atlassian.net/browse/S15P21E205-254)에서 2026-09-15 확정([data-model](data-model.md) §4-2). T017·B02 착수를 막지 않는다. 최소분산 `estimationMethod`만 004 PD-04 대기로 후보 하나로 시작한다(003 쪽 구조는 막히지 않는다)
- ~~001 평가 기간 입력 계약과 최소 평가 길이 미확정~~ — [S15P21E205-254](https://ssafy.atlassian.net/browse/S15P21E205-254)에서 정정. 001이 이미 만든 `EvaluationWindowService`에 호출부만 붙이면 되고, 최소 평가 길이는 사용자가 고르는 항목(FR-011a)으로 확정했다. T042는 001이 호출부를 붙이는 대로 연결한다(001 작업 완료가 남은 선행 조건, 팀 결정 대기가 아니다)
- 004 규칙·비용 버전 조회 수단 미확정: B06·B10 실제 연결 차단. PD-07 자체는 `PROVISIONAL-DEMO-1` 표시로 차단하지 않음
- 004 상한 재배분 규칙과 워커 메시지 계약 미확정: FR-006·FR-019의 종단 완료 판정 보류

## Format: `[ID] [P?] [Story] (파트) Description`

- **[P]**: 다른 파일을 만지고 선행 태스크가 없어 병렬 실행 가능
- **문자 접미사 ID**(`T018a`): 초판을 코드와 대조하다 찾은 누락을 메우려고 나중에 끼운 태스크다. 실행 순서를 유지하려고 앞 번호를 다시 매기지 않았다(007 tasks.md의 `T020a`와 같은 표기)
- **[US1]~[US4]**: spec.md의 사용자 스토리. Setup·Foundational·Polish에는 붙지 않는다
- **(파트)**: 아래 표의 파트. 작업 종류 표시이며 브랜치 단위가 아니다

| 파트 태그 | 대상 | 묶음의 브랜치 position |
|---|---|---|
| `(BE)` | 백엔드 Java 구현 | `be` |
| `(BE 테스트)` | 백엔드 JUnit·ArchUnit·Testcontainers | `be` |
| `(FE)` | 프론트엔드 React·TS | `fe` |
| `(FE 테스트)` | 프론트엔드 Vitest | `fe` |
| `(INFRA)` | 자원 파일(YAML)·설정 | `be` (등록표는 백엔드 자원이다) |
| `(DOCS)` | 계약·데이터 모델·CLAUDE.md·수동 확인 기록 | `docs` |

Worker(`worker/`)와 `data/`는 이번 범위에서 **변경하지 않는다**. 스키마 변경(마이그레이션)도 없다 — 등록표는 자원 파일이고 설정값은 브라우저에만 둔다([research](research.md) R1·R8).

**`T###`는 티켓·브랜치 단위가 아니다.** constitution "Jira 연계"가 `작업 묶음 = 1 Branch = 1 MR`로 정한다.
아래 81개는 실행 지시의 최소 단위이고, **티켓·브랜치·MR의 단위는 묶음 B01~B12 열둘이다** —
"Dependencies & Execution Order"의 "작업 묶음" 표와 [plan.md](plan.md) "작업 묶음 구성"에 있다.

## Path Conventions

- 백엔드 003 소유 영역: `backend/src/main/java/com/ssafy/thorp/strategy/...`, 테스트는 `backend/src/test/java/com/ssafy/thorp/strategy/...`
- 백엔드 007 영역(import 경로와 T008의 포트 호출 인자 형이 바뀐다): `backend/src/main/java/com/ssafy/thorp/paramsearch/...`
- 백엔드 004 영역(접수 검증 연결 한 곳): `backend/src/main/java/com/ssafy/thorp/backtest/application/service/BacktestService.java`
- 등록표 자원 파일: `backend/src/main/resources/registry/parameter-registry.yml` (007의 `parameter-registry-provisional.yml`을 승격한다)
- 계약 픽스처: `docs/specs/strategy-registry-api/examples/registry-response.json`
- 프론트엔드: `frontend/src/...` — 화면 `pages/StrategySettingsPage.tsx`, 컴포넌트 `components/strategy/`, 훅 `hooks/`, API `api/`
- 커밋 전 `cd backend && ./gradlew spotlessApply` 필수. 프론트엔드는 `cd frontend && npm run lint && npm run build`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 003 소유 패키지 골격, 오류 코드, 계약 픽스처, 프론트엔드 타입 자리

- [X] T001 (BE) `strategy` 도메인 패키지 골격 생성 — `backend/src/main/java/com/ssafy/thorp/strategy/package-info.java`에 계층 규약(ADR-0001)과 "등록표의 소유자는 003이고 `paramsearch`는 포트로 읽는다"를 명시. 하위 패키지는 `application/{port,exception,result,service}`, `infrastructure/registry`, `presentation/{controller,response}`. **`domain/model`은 만들지 않는다** — 값 타입은 `application/port`에 둔다(plan.md "`domain/model`을 두지 않는다")
- [ ] T002 [P] (BE) `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java`에 spec 003 블록을 열고 `VALIDATION_RULE_VIOLATED(HttpStatus.UNPROCESSABLE_ENTITY, "선택한 설정 조합은 실행할 수 없습니다. 안내를 확인해 주세요.")` 추가. 주석에 계약 위치(`specs/003-strategy-settings/contracts/strategy-registry-api.md` §2)를 적는다. 다른 넷(`UNREGISTERED_PARAMETER_VALUE`·`STRATEGY_NOT_REGISTERED`·`REGISTRY_VERSION_CHANGED`·`REGISTRY_UNAVAILABLE`)은 007 블록에 이미 있어 재사용한다
- [X] T003 [P] (DOCS) 계약에 두 필드를 더한다 — `specs/003-strategy-settings/contracts/strategy-registry-api.md` §1 필드 표와 `specs/003-strategy-settings/data-model.md` §2·§3에 `axes[].description`(항목의 의미 한 문장. FR-003의 '의미'와 FR-019의 상한 재배분 안내 문장이 이 자리에 온다)과 `strategies[].targetWeightSource`(`USER` | `EQUAL` | `COMPUTED`. `USER`면 화면이 002 비중을 읽기 전용으로 보이고, `COMPUTED`면 FR-012의 자동 배분 설명을 보인다)를 추가. **이유**: 화면이 축 이름·전략 ID로 분기하지 않으려면(research R7-1) 그 문장과 구분이 등록표에서 와야 한다. 계약 §1 예시의 "둘째 전략은 `axes`가 비어 있다" 주석은 지우고 예시를 완전한 응답으로 고친다(T004의 픽스처와 같게)
- [X] T004 [P] (DOCS) `docs/specs/strategy-registry-api/README.md`(계약 요약과 원본 위치 링크, `docs/specs/backtest-api/README.md`와 같은 역할)와 `docs/specs/strategy-registry-api/examples/registry-response.json` 생성 — 계약 §1 예시를 바탕으로 두 전략 모두 `axes`를 채운 **완전한 200 응답**. T003의 두 필드를 포함한다. 이 파일을 T026의 계약 테스트가 읽는다. 여기 담는 전략·축 값은 실제 등록표와 같을 필요가 없다(테스트가 같은 내용의 합성 등록표를 만든다)
- [ ] T005 [P] (FE) `frontend/src/types/strategyRegistry.ts` — 계약 §1 응답과 1:1 타입. `RegistryResponse`·`StrategyView`(`selectable`·`unselectableReason`·`warmupTradingDays`·`warmupRule`·`validationRules`·`targetWeightSource`)·`AxisView`(`inputType: 'CHOICE' | 'NUMBER'`, `choices?`·`range?`·`defaultValue`·`description`)·`ChoiceView`·`RangeView`·`ValidationRule`·`Operand`·`RuleOperator`·`WarmupRule`·`AnalysisConditions`. 파일 머리 주석에 "전략 이름·축·기본값을 이 파일에 상수로 두지 않는다(FR-005)"를 적는다(`frontend/src/types/paramSearch.ts` 머리 주석과 같은 방식)
- [ ] T006 [P] (FE) `frontend/src/test/strategyRegistryFixtures.ts` — 화면 테스트용 응답 픽스처. **등록표에 없는 합성 전략 이름을 쓴다**(`frontend/src/test/paramSearchFixtures.ts`와 같은 이유: 응답만 보고 그린다는 것을 증명한다). 목록형 축·입력형 축·`selectable:false` 후보·`selectable:false` 전략·`validationRules` 하나·`warmupRule` `sum` 둘짜리를 모두 담는다. 같은 태스크에서 `frontend/src/api/strategyRegistry.ts` 빈 모듈과 `frontend/src/components/strategy/` 디렉터리를 만든다

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 007이 세워 둔 등록표를 003 소유로 옮기고, 화면이 읽을 항목을 등록표에 더한다. [contracts/strategy-registry-internal.md](contracts/strategy-registry-internal.md) §4의 순서(1→2→3→4)를 그대로 따른다.

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 어떤 사용자 스토리도 시작할 수 없다. **등록표는 지금 007이 실제로 쓰고 있어 옮기다 깨지면 자동 탐색이 먼저 멈춘다.** T011과 T021의 확인을 건너뛰지 않는다.

### §4 1단계 — 포트·값 타입·예외를 옮긴다 (로직 변경 없음)

- [X] T007 (BE) `git mv`로 여섯 파일을 `backend/src/main/java/com/ssafy/thorp/paramsearch/application/port/`에서 `backend/src/main/java/com/ssafy/thorp/strategy/application/port/`로 옮기고 `package` 선언만 바꾼다 — `ParameterRegistryPort.java`·`StrategyRegistration.java`·`ParameterAxis.java`·`AllowedRange.java`·`ValidationVerdict.java`·`SearchContext.java`. 나머지 포트(`CandidateJobLauncher`·`StrategyExecutionSupportPort` 등)는 007 것이므로 두지 않는다
- [X] T008 (BE) `ParameterRegistryPort.validate()`의 둘째 인자 형을 `ParameterCombination`(007의 `paramsearch/domain/vo`)에서 `Map<String, Object>`로 바꾼다 — 그대로 두면 `strategy`가 `paramsearch` 도메인 VO를 참조해 없애려던 역방향 의존이 되살아난다. 호출부 `backend/src/main/java/com/ssafy/thorp/paramsearch/application/service/CandidateValidator.java:43`은 `point.parameters().values()`를 넘긴다. plan.md Summary에도 import 경로뿐 아니라 이 호출 인자 형이 바뀐다고 명시한다
- [X] T009 (BE) `git mv backend/src/main/java/com/ssafy/thorp/paramsearch/application/exception/RegistryUnavailableException.java backend/src/main/java/com/ssafy/thorp/strategy/application/exception/RegistryUnavailableException.java` 후 `package`만 바꾼다. 메시지·`ErrorCode.REGISTRY_UNAVAILABLE` 매핑은 그대로다(내부 계약 §2.2 "003과 007이 같은 예외를 쓴다")
- [X] T010 (BE) `paramsearch` 안의 import 경로를 전부 새 위치로 바꾼다. 대상은 `grep -rl "paramsearch\.application\.port\.\(ParameterRegistryPort\|StrategyRegistration\|ParameterAxis\|AllowedRange\|ValidationVerdict\|SearchContext\)\|paramsearch\.application\.exception\.RegistryUnavailableException" backend/src`로 찾는다 — 운영 코드(`ParamSearchService`·`CandidateValidator`·`SearchTargetResolver`·`GridExpander`·`ProvisionalFileParameterRegistry`·`RegistryRule` 등)와 테스트(`RegistryFixtures`·`ProvisionalFileParameterRegistryTest`·`CandidateValidatorTest`·`SearchTargetResolverTest`·`StrategyAgnosticSearchTest`·`StrategyIndependenceTest`·`ParamSearchErrorResponseTest` 등) 모두. 로더 두 파일은 이 단계에서는 `paramsearch/infrastructure/registry/`에 그대로 둔다(3단계에서 옮긴다)
- [X] T011 (BE 테스트) **§4 2단계 확인**: `cd backend && ./gradlew spotlessApply test --tests '*ParamSearch*' --tests '*Registry*' --tests '*ArchitectureTest*'` 통과. 옮기기 전과 결과가 같아야 한다(quickstart 1). 실패하면 다음으로 가지 않는다
- [X] T012 (BE 테스트) `backend/src/test/java/com/ssafy/thorp/ArchitectureTest.java`에 규칙 8 추가 — `noClasses().that().resideInAPackage("com.ssafy.thorp.strategy..").should().dependOnClassesThat().resideInAPackage("com.ssafy.thorp.paramsearch..")`. Javadoc에 "등록표의 소유자는 003이고 007이 읽는다. 방향은 `paramsearch → strategy` 하나다"를 적는다. **규칙이 동작하는지 일부러 어겨 확인한다**(quickstart 1): `strategy` 안의 아무 클래스에 `paramsearch` 타입 import 한 줄을 넣고 테스트가 **실패**하는 것을 본 뒤 그 줄을 지운다. 이 확인 결과를 MR 본문 "테스트" 절에 적는다

### §4 3단계 — 로더를 옮기고 등록표를 승격한다

- [X] T013 [P] (BE) 값 타입 확장 — `backend/src/main/java/com/ssafy/thorp/strategy/application/port/`에: `StrategyRegistration`에 `boolean active`(생략 시 true)·`WarmupRule warmup`(nullable)·`String targetWeightSource` 컴포넌트를 더한다. **record 컴포넌트를 더하면 정식 생성자가 바뀌므로 호출부를 함께 고친다 — 전수 조사 결과 두 곳뿐이다**(`ProvisionalFileParameterRegistry.toRegistration`과 `RegistryFixtures:104`. 후자는 T020이 맡는다). `ParameterAxis`도 같다(`ProvisionalFileParameterRegistry:116`·`RegistryFixtures:37`). 호환용 정적 팩터리를 남기지 않는다 — 두 곳을 고치는 편이 짧고, 팩터리를 남기면 새 필드를 빠뜨린 생성 경로가 계속 남는다. `ParameterAxis`에 `String description`(nullable); 새 record `WarmupRule(List<Object> sum)` + `int evaluate(Map<String, Object> values)`(문자열 항목은 축 이름 → 그 값, 숫자 항목은 상수, 모두 정수로 더한다. 축 값이 없으면 예외 — 값을 지어내지 않는다)([data-model](data-model.md) §6); `ValidationRule(String code, String detail, Operand left, Operator operator, Operand right)` 공개 record와 `Operand(String axis, String context, Object value, String multiplyByContext)`·`Operator` enum(여섯)([data-model](data-model.md) §4-1); `AnalysisCondition(AllowedRange range, Object defaultValue, String unit, String usedBy)`와 `AnalysisConditionRegistry(AnalysisCondition initialAmount, AnalysisCondition targetVolatility, AnalysisCondition minEvaluationYears)`([data-model](data-model.md) §7, FR-011a)
- [X] T014 [P] (BE) `backend/src/main/java/com/ssafy/thorp/strategy/application/port/AllowedRange.java` 확장 — 생성자에서 `values`와 `min`/`max`/`step`을 동시에 채우면 `IllegalArgumentException`(목록형·입력형 배타); `boolean contains(Object value)` 추가 — 목록형은 `values.contains`(`equals`), 입력형은 `BigDecimal`로 `min ≤ v ≤ max`이고 `(v − min) mod step == 0`. `double`을 거치지 않는다(`RegistryRule.toNumber`와 같은 이유 — 등록표 값이 그대로 기록에 남는다)
- [X] T015 (BE) `backend/src/main/java/com/ssafy/thorp/strategy/application/port/ParameterRegistryPort.java` 확장(내부 계약 §3) — `List<ValidationRule> rules(String strategyId)`(등록되지 않은 전략은 빈 목록이 아니라 `Optional.empty()`와 같은 뜻이 되도록 `find`와 함께 쓰라고 주석), `AnalysisConditionRegistry analysisConditions()`, `boolean provisional()`(`version()`이 `PROVISIONAL-`로 시작하는가). `activeStrategies()` Javadoc을 "활성만"으로, `find()`를 "비활성 포함"으로 고친다(§2.3). `validate()`는 그대로 둔다(T013·T014 의존)
- [X] T016 (BE) 로더를 **옮기고·이름을 바꾸고·읽는 항목을 늘린다**(내부 계약 §1의 셋) — `git mv backend/src/main/java/com/ssafy/thorp/paramsearch/infrastructure/registry/ProvisionalFileParameterRegistry.java backend/src/main/java/com/ssafy/thorp/strategy/infrastructure/registry/FileParameterRegistry.java`와 `RegistryRule.java`도 같은 패키지로. `RESOURCE_PATH`를 `registry/parameter-registry.yml`로. **기존 검사(전략 중복·축 이름 중복·빈 `searchValues`·`null` 값·`baselineStrategyId` 등록 여부)는 한 줄도 빼지 않는다.** 새로 읽는 것: 전략 `active`(생략=true)·`warmup.sum`·`targetWeightSource`, 축 `description`·`allowedRange.values`, 최상위 `analysisConditions.{initialAmount,targetVolatility,minEvaluationYears}.{min,max,step,default,unit,usedBy}` — `minEvaluationYears`는 입력형이 아니라 목록형이므로 `values`(`[0, 1, 3]`)를 읽는다. 새 검사 셋(기동 실패): 목록형 축 `searchValues ⊆ allowedRange.values`, `defaultValue`가 `allowedRange.contains`, 목록형·입력형 배타(T014 예외를 잡아 어느 축인지 붙여 다시 던진다). `analysisConditions`는 필수이고 `default`가 범위 안. `activeStrategies()`는 `active` 필터, `find()`는 전체 색인. `rules()`는 `RegistryRule` → `ValidationRule` 변환(또는 `RegistryRule`이 `ValidationRule`을 들고 평가만 맡는다). `requireProvisionalVersion`은 지우고 `provisional()`로 대체한다 — PD-02 확정 뒤 접두를 떼는 날 코드가 바뀌면 안 된다. **클래스 이름을 직접 단언하는 테스트를 함께 고친다**: `backend/src/test/java/com/ssafy/thorp/paramsearch/ParamSearchWiringIT.java:57`의 `isInstanceOf(ProvisionalFileParameterRegistry.class)`와 그 import(고치지 않으면 컴파일이 깨진다). 끝나면 `backend/src/main/java/com/ssafy/thorp/paramsearch/infrastructure/registry/`는 비어 있어야 하며 디렉터리를 지운다(위임 계층을 남기지 않는다) (T013·T014·T015 의존)
- [X] T017 (INFRA) `git mv backend/src/main/resources/registry/parameter-registry-provisional.yml backend/src/main/resources/registry/parameter-registry.yml` 후 내용을 개정한다([data-model](data-model.md) §2~§7·§4-2, spec FR-001·FR-003·FR-007·FR-011a). PD-02가 2026-09-15 확정됐으므로([S15P21E205-254](https://ssafy.atlassian.net/browse/S15P21E205-254)) 팀이 승인한 새 제품 버전을 부여하고 `provisional:false`로 둔다(내부 계약 §2.1).
  - `name`을 화면 이름으로, `description`을 FR-001 표의 한 줄 설명으로: `FIXED_WEIGHT` 내가 정한 비중 / `EQUAL_WEIGHT` 똑같이 나누기 / `INVERSE_VOLATILITY` 변동성에 따라 나누기 / `SECTOR_MOMENTUM` 흐름을 따라 배분하기 / `MINIMUM_VARIANCE` 전체 변동성을 줄이도록 배분하기. 전략 순서는 FR-001 표 순서
  - `targetWeightSource`: `FIXED_WEIGHT` `USER`, `EQUAL_WEIGHT` `EQUAL`, 나머지 셋 `COMPUTED`. 모든 전략에 `active: true`를 명시한다
  - 축 구성과 확정 후보값은 [data-model](data-model.md) §4-2 표를 그대로 옮긴다:
    - `FIXED_WEIGHT`: `rebalancePeriod`(공통 후보, 기본 월말)·`maxWeight`(10~100, 5 간격, 기본 40)
    - `EQUAL_WEIGHT`: `rebalancePeriod`만
    - `INVERSE_VOLATILITY`: `lookbackDays`(`[21,63,126,252]`, 허용범위 10~504/1, 기본 63)·`rebalancePeriod`·`maxWeight`(10~100/5, 기본 40)
    - `SECTOR_MOMENTUM`: `lookbackDays`(`[20,60,120]`, 허용범위 10~252/1, 기본 60)·`skipRecentDays`(`[0,5,10,21]`, 허용범위 0~63/1, 기본 0)·`selectionMode`(`[TOP_N,THRESHOLD,TOP_PERCENT,ALL]`, 기본 `TOP_N`)·`topN`(`[2,3,4]`, 허용범위 1~20/1, 기본 3)·`threshold`(-20~50/1, 기본 0, 단위 %)·`topPercent`(10~100/10, 기본 50, 단위 %)·`allocationMethod`(`[EQUAL,SCORE_WEIGHTED]`, 기본 `EQUAL`)·`maxWeight`(10~100/5, 기본 40)·`rebalancePeriod`
    - `MINIMUM_VARIANCE`: `lookbackDays`(`[60,120,250]`, 허용범위 20~504/1, 기본 120)·`estimationMethod`(`[SAMPLE_COV]`, 기본 `SAMPLE_COV` — 004 PD-04 확정 전까지 후보 하나)·`maxWeight`(`[30,40,50]`, 허용범위 10~100/5, 기본 40)·`rebalancePeriod`
  - `rebalancePeriod` 공통 후보(달력·거래일 수 혼합, §5): `{CALENDAR, MONTH_END}`(기본)·`{CALENDAR, QUARTER_END}`·`{TRADING_DAYS, 21}`·`{TRADING_DAYS, 63}`. `searchValues`는 이 네 값의 부분집합으로 둔다
  - `warmup.sum`: `INVERSE_VOLATILITY` `[lookbackDays]`, `SECTOR_MOMENTUM` `[lookbackDays, skipRecentDays]`, `MINIMUM_VARIANCE` `[lookbackDays]`. 고정·동일 비중은 `warmup` 없음([data-model](data-model.md) §6)
  - 축 `description`: 항목마다 의미 한 문장. `INVERSE_VOLATILITY`·`SECTOR_MOMENTUM`의 `maxWeight`에는 FR-019 재배분 문장("잘린 몫은 여유가 있는 섹터에 비중 비율대로 다시 나누고, 더 나눌 곳이 없을 때만 남긴다")을, `MINIMUM_VARIANCE`의 `maxWeight`에는 "계산의 제약으로 들어가 잘리는 몫이 생기지 않는다"를 적는다
  - `analysisConditions.initialAmount`: 100만~10억, 100만 간격, 기본 1,000만, 단위 원. `targetVolatility`는 `min 5`·`max 20`·`step 1`·`default 10`·`unit 퍼센트(연)`·`usedBy TARGET_RISK`(004 PD-18 확정값). `minEvaluationYears`: 목록형 `[0, 1, 3]`, 기본 1, 라벨 완화/기본/엄격(FR-011a)
  - `estimationMethod`가 후보 하나뿐인 것은 오류가 아니다 — 004 PD-04(공분산 추정 방법)가 확정되기 전까지 003이 화면에 004가 계산할 수 없는 선택지를 얹지 않기 위함이다(원칙 VI). PD-04가 확정되면 등록표 버전을 올려 후보를 늘인다. 파일 머리에는 소유자 003, 007은 포트로 읽음, `searchValues`를 늘리면 007 조합 수가 곱으로 는다(R3 경고), `allowedRange.values` 순서는 자유이고 `searchValues` 순서는 버전에 묶임(§2.1), 값이 바뀌면 기존 버전을 덮어쓰지 않음을 적는다
  - **파일 이름을 문자열로 들고 있는 테스트 둘을 함께 고친다.** `backend/src/test/java/com/ssafy/thorp/paramsearch/selection/BaselineAbsenceTest.java:109`의 `Path.of("src/main/resources/registry/parameter-registry-provisional.yml")`와 `backend/src/test/java/com/ssafy/thorp/paramsearch/StrategyIndependenceTest.java:38`의 `REGISTRY_RESOURCE` 상수다. **둘 다 컴파일은 통과하고 실행할 때 파일을 찾지 못해 실패한다** — 이름만 바꾸고 지나가면 T021에서야 드러난다
  - **워커 정합 확인(고치지 않는다)**: 워커는 `allocation.parameters.rebalance == "MONTH_END"` 문자열(`worker/src/worker/engine_support.py:50`)과 `allocation.rules.lookbackDays`(`worker/src/worker/engine_runner.py:79`)만 읽는다. 등록표 축 이름·값 형태(`rebalancePeriod` 객체, `maxWeight`·`topN`·`skipRecentDays`·`allocationMethod`·`estimationMethod`)가 워커에 닿는 길은 004 소유다. 어긋난 곳을 표로 MR 본문에 적는다("착수 전에 알아둘 것" ③)
- [X] T018 (BE 테스트) `git mv backend/src/test/java/com/ssafy/thorp/paramsearch/infrastructure/registry/ProvisionalFileParameterRegistryTest.java backend/src/test/java/com/ssafy/thorp/strategy/infrastructure/registry/FileParameterRegistryTest.java`. 기존 단언(자원 파일을 따로 읽어 포트 결과와 대조하는 방식)은 유지하고 다음을 더한다: (a) 합성 YAML에서 `active: false` 전략이 `activeStrategies()`에 없고 `find()`에는 있다(quickstart 3-1의 로더 쪽); (b) 새 제약 셋을 각각 어긴 합성 YAML 3건이 기동 실패한다; (c) `analysisConditions`가 파일과 같다; (d) `rules(strategyId)` 개수가 파일의 `validationRules` 개수와 같다; (e) 합성 버전 `PROVISIONAL-FIXTURE`는 `provisional() == true`, 승인된 제품 버전은 `false`; (f) **합성 픽스처**의 `SECTOR_MOMENTUM` `warmup`에 `{lookbackDays: 60, skipRecentDays: 21}`을 넣으면 `WarmupRule.evaluate`가 **81**이다(quickstart 7-1), `warmup` 없는 전략은 0 (T016·T017 의존)
- [X] T018a (BE 테스트) **옮기면서 조용히 사라지는 보장을 되살린다** — `backend/src/test/java/com/ssafy/thorp/paramsearch/StrategyIndependenceTest.java`의 `SOURCE_ROOT`가 `src/main/java/com/ssafy/thorp/paramsearch` 하나라서, 로더가 `strategy`로 옮겨가면 **"로더는 파일 구조만 읽고 전략 이름을 알아서는 안 된다"는 검사(그 클래스 Javadoc이 명시한 대상)가 검사 범위에서 빠진다.** 테스트는 여전히 초록이므로 아무도 알아채지 못한다. `SOURCE_ROOT`를 목록으로 바꿔 `com/ssafy/thorp/strategy`를 더하거나, 같은 검사를 `strategy` 쪽 테스트로 옮긴다. 어느 쪽이든 **옮긴 뒤 로더에 전략 ID를 한 줄 넣어 실패하는 것을 확인하고 지운다**(T012의 ArchUnit 규칙과 같은 확인 방식). 007 FR-025의 보장이 003 이관으로 약해지지 않아야 한다 (T016·T017 의존)
- [X] T019 [P] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/strategy/application/port/AllowedRangeTest.java` — `contains`: 목록형은 `equals`(`63`은 있고 `63.0`은 없다 — §2.4), 입력형 10~100 step 5에서 `5` 밖·`33` 간격 위반·`35` 통과·경계 `10`·`100` 통과; 목록형·입력형 동시 채움 거절 (T014 의존)
- [X] T020 (BE 테스트) `backend/src/test/java/com/ssafy/thorp/paramsearch/registry/RegistryFixtures.java` builder에 `active(boolean)`·`warmup(List<Object>)`·`targetWeightSource(String)` 지정 메서드를 더한다(기본 활성·워밍업 없음·`COMPUTED`). 기존 호출부는 바꾸지 않고 컴파일된다 (T013 의존)
- [X] T021 (BE 테스트) **§4 4단계 확인**: `cd backend && ./gradlew spotlessApply build`. 007 테스트가 새 필드에 영향받지 않아야 한다. `ParamSearchWiringIT`가 `FileParameterRegistry` 빈을 주입받는지 확인한다(T016~T020 의존)
- [X] T022 (DOCS) 위치가 바뀐 사실을 읽는 쪽 문서에 반영 — `specs/007-auto-parameter-search/contracts/param-search-internal.md` §1의 포트·예외 위치 표기를 `strategy/application/...`으로; `backend/CLAUDE.md` "규칙"에 한 줄 추가: "전략·파라미터 등록표(`registry/parameter-registry.yml`)는 `strategy` 도메인이 소유하고 `paramsearch`는 `ParameterRegistryPort`로만 읽는다. 값이 바뀌면 행을 고치지 말고 버전을 올린다(`cost_profile`·`strategy_rules`와 같은 규칙)". 007 `tasks.md`는 건드리지 않는다

**Checkpoint**: 등록표가 `strategy` 소유로 옮겨졌고 007이 그대로 돈다. 사용자 스토리를 시작할 수 있다

---

## Phase 3: User Story 1 - 전략 카드를 고르고 기본 설정으로 분석한다 (Priority: P1) 🎯 MVP

**Goal**: 등록표 조회 API를 열고, 화면이 그 응답만으로 다섯 카드를 그려 카드 선택 한 번과 '분석하기' 한 번으로 기본값 분석 요청을 만든다. 등록표를 받지 못하면 아무것도 시작하지 않는다.

**Independent Test**: 섹터·비중이 정해진 상태에서 전략 카드 하나를 선택하고 상세 설정을 열지 않은 채 '분석하기'가 동작하는지 확인한다(quickstart 4). 등록표 조회 실패 시 두 버튼이 모두 막히는지 확인한다(quickstart 9).

### Backend — 조회 API

- [X] T023 [P] [US1] (BE) `backend/src/main/java/com/ssafy/thorp/strategy/application/result/StrategyRegistryResult.java` — 계약 §1 응답 모양의 record 집합(`RegistryView`·`StrategyView`·`AxisView`·`ChoiceView`·`RangeView`·`RuleView`·`OperandView`·`WarmupRuleView`·`AnalysisConditionsView`). 필드 이름은 계약 JSON과 1:1. `searchValues`는 담지 않는다(계약 §1 "이 응답에 싣지 않는다")
- [X] T024 [US1] (BE) `backend/src/main/java/com/ssafy/thorp/strategy/application/service/StrategyRegistryQueryService.java` — `RegistryView query(Integer universeSize)`: 활성 전략만; 축 `inputType`은 `allowedRange.isEnumerated()`로(`CHOICE`/`NUMBER`); 목록형 `choices[].selectable`은 `universeSize`가 있을 때 그 축 값 하나만 바꾼 기본 조합으로 `rules`를 평가해 위반이면 `false`(목록에서 빼지 않는다); `choices[].label`은 값의 형태만 보고 만든다(숫자는 값+`unit`, `{mode: CALENDAR}`는 `unit`을 월말/분기말/반기말로, `{mode: TRADING_DAYS}`는 `days`+"거래일"); `warmupTradingDays`는 기본값으로 `WarmupRule.evaluate`; `selectable`은 기본 조합이 규칙 위반 없음이고 위반이면 첫 규칙 `detail`을 `unselectableReason`에; `universeSize`가 없으면 제약을 적용하지 않는다. `RegistryUnavailableException`은 잡지 않고 전파한다(503) (T023 의존)
- [X] T025 [US1] (BE) `backend/src/main/java/com/ssafy/thorp/strategy/presentation/response/StrategyRegistryResponse.java`(result → response 변환 record)와 `backend/src/main/java/com/ssafy/thorp/strategy/presentation/controller/StrategyRegistryController.java` — `GET /strategies/registry?universeSize=`(`@Min(1)`, 선택), 다른 컨트롤러와 같은 `ApiResponse` 껍데기. `SecurityConfig`는 `anyRequest().permitAll()`이라 추가 설정이 없다 — 확인만 하고 Javadoc에 "비로그인 허용(계약 §1)"을 적는다 (T024 의존)

### Backend 테스트

- [X] T026 [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/strategy/presentation/StrategyRegistryContractTest.java` — `@WebMvcTest`에 픽스처와 같은 내용의 **합성 YAML로 만든 `FileParameterRegistry`**(`ByteArrayResource`)를 주입해 `GET /strategies/registry?universeSize=3` 응답의 `data`가 `docs/specs/strategy-registry-api/examples/registry-response.json`과 JSON 동치임을 단언(`BacktestResultApiIT`의 `EXAMPLES` 경로 방식). 포트가 `RegistryUnavailableException`을 던지면 503 `REGISTRY_UNAVAILABLE`(계약 §1 "응답 503"). 계약과 코드가 어긋나면 여기서 빌드가 깨진다(plan.md "Testing" 2) (T004·T025 의존)
- [X] T027 [P] [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/strategy/application/service/StrategyRegistryQueryServiceTest.java` — `RegistryFixtures` 합성 등록표로: `universeSize=2`에서 선정 수 후보 3·4가 `selectable:false`이되 목록에 남는다(quickstart 3); `active:false` 전략은 목록에 없다(quickstart 3-1); `universeSize` 없으면 전부 `selectable`; 기본 조합이 규칙을 어기는 전략은 `selectable:false`이고 `unselectableReason`이 규칙 `detail` 그대로; `warmupTradingDays`가 기본값 합; `validationRules`는 `universeSize`와 무관하게 항상 실린다(quickstart 3 "규칙 목록이지 위반 판정이 아니다") (T024 의존)
- [X] T028 [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/strategy/StrategyRegistryApiIT.java`(`support/PostgresTestSupport` 상속, 다른 IT와 같은 기동 방식) — 결정 뒤 완성한 실제 `parameter-registry.yml`로 `GET /strategies/registry?universeSize=3`: 200, `registryVersion`이 승인된 제품 버전과 같고 `provisional:false`, 활성 전략 다섯이 FR-001 순서로 실린다, 요청자 헤더 없이도 200(quickstart 2) (T025 의존)

### Frontend — 기반 모듈

- [ ] T029 [P] [US1] (FE) `frontend/src/api/strategyRegistry.ts` — `fetchStrategyRegistry(universeSize?: number)` → `GET /strategies/registry`, `ApiResponse` 껍데기를 벗겨 `RegistryResponse` 반환. `StrategyRegistryError`(`code`·`status`, `REGISTRY_UNAVAILABLE`과 네트워크 실패를 구분)는 `frontend/src/api/paramSearch.ts`의 `ParamSearchError`와 같은 방식. `apiClient` 경유, URL 하드코딩 금지
- [ ] T030 [P] [US1] (FE) `frontend/src/hooks/useSectorSelection.ts` — **002 경계.** 002가 확정한 섹터·비중(`mappingVersion`·`items[].{sectorId, instrumentId, targetWeight}`)을 읽는 유일한 통로다. 002 화면이 아직 없으므로 지금은 `sessionStorage` 키 `thorp.sectorSelection`과 질의 문자열 `?sectors=&instruments=`(007 `ParamSearchPage`와 같은 방식)에서 읽는 어댑터로 두고, **002가 구현되면 이 파일만 바꾼다**고 머리 주석에 적는다. 없으면 `null`을 돌려주고 화면이 "섹터·비중 단계를 먼저 마쳐 주세요" 안내를 보인다("착수 전에 알아둘 것" ⑤)
- [ ] T031 [P] [US1] (FE) `frontend/src/utils/warmupRule.ts` — `evaluateWarmup(rule: WarmupRule, values: Record<string, unknown>): number`. `sum` 항목이 문자열이면 축 값, 숫자면 상수, 정수로 더한다. 축 값이 없거나 정수가 아니면 예외(값을 지어내지 않는다). 서버 `WarmupRule.evaluate`와 같은 규약
- [ ] T032 [US1] (FE) `frontend/src/hooks/useStrategySettings.ts` — 화면 상태([data-model](data-model.md) §8·§9): `registryVersion`·`selectedStrategyId`·`valuesByStrategy`·`analysisCondition`. `sessionStorage` 키 `thorp.strategySettings`(research R8): 읽기·쓰기 모두 `try/catch`로 실패해도 되는 것으로 다룬다. 저장된 `registryVersion`이 조회한 버전과 다르거나 섹터 수가 바뀌면 각 보관값을 새 범위·규칙으로 재검증해 유효한 값은 유지하고, 유효하지 않은 값은 자동 보정하지 않은 채 이유와 기본값 복원 선택을 돌려준다. 이 로직을 T059의 409 처리도 재사용한다. 전략을 처음 고르면 그 전략 축의 `defaultValue`로 값을 채운다. `isDefault(strategyId)`는 저장하지 않고 매번 축 값 전부가 `defaultValue`와 같은지로 계산한다(FR-002). `analysisCondition.minEvaluationYears`는 등록표 `analysisConditions.minEvaluationYears.default`(1)로 시작한다(FR-011a). 제공 함수: `select`·`setValue`·`resetAxis`·`resetAll`·`setAnalysisCondition`. 현재 워밍업은 `evaluateWarmup`으로 파생한다 (T029·T031 의존)

### Frontend — 컴포넌트

- [ ] T033 [P] [US1] (FE) `frontend/src/components/strategy/StrategyCard.tsx`·`StrategyCard.module.css` — `name`·`description`만 보인다(축·설정값은 절대 보이지 않는다). 키보드 선택이 가능하고 선택 상태를 `aria-pressed`, 선택 불가는 `aria-disabled`와 `unselectableReason` 한 문장으로 전달한다. 워밍업 판정(`'blocked' | 'reduced' | 'ok'`와 문장)을 prop으로 받아 표시하며 색만으로 알리지 않는다(NFR-001·NFR-002)
- [ ] T034 [US1] (FE) `frontend/src/components/strategy/StrategyCardList.tsx` — 응답 `strategies` 순서 그대로 카드를 그린다. 등록표 조회 중이면 카드 전부 선택 불가(skeleton), 실패면 카드 대신 `RegistryUnavailableNotice`. 카드 목록의 워밍업 판정은 각 전략의 `warmupTradingDays`(기본 설정 기준, FR-011) (T033·T035 의존)
- [ ] T035 [P] [US1] (FE) `frontend/src/components/strategy/RegistryUnavailableNotice.tsx` — FR-018: "전략 설정을 불러오지 못했습니다" + '다시 시도' 버튼(`onRetry`). 오류를 `role="alert"`로 알리고 재시도 성공 시 새 카드 영역, 실패 시 안내를 읽은 뒤 재시도 버튼으로 초점이 돌아오게 한다(NFR-001). 임시 값·예비 등록표로 대신 동작하는 경로를 두지 않는다
- [ ] T036 [P] [US1] (FE) `frontend/src/components/strategy/SettingsStatusBadge.tsx` — `isDefault ? '기본 설정' : '설정을 바꿈'`. 누를 수 없는 상태 표시이며 토글이 아니다(FR-002). 옆에 "기본값은 편의용 출발점이며 최적값이 아닙니다"(FR-004, 원칙 III)
- [ ] T037 [P] [US1] (FE) `frontend/src/components/strategy/ConditionSummaryLine.tsx` — 분석 기간·비교 대상(섹터 수)·비용 적용 여부 한 줄(FR-002). 값은 props로만 받는다
- [ ] T038 [P] [US1] (FE) `frontend/src/components/strategy/ActionButtons.tsx` — constitution 3.4.0 승인([S15P21E205-253](https://ssafy.atlassian.net/browse/S15P21E205-253), 2026-09-15)으로 spec의 두 버튼 안이 확정됐다. '분석하기'·'설정 조합 자동 비교'를 같은 크기·같은 variant로 두고, 비활성 조건(등록표 없음·전략 미선택·규칙 위반·워밍업 미충족·분석 조건 버전 없음)을 의미 있는 `disabled` 상태와 사유로 전달한다(NFR-001)
- [ ] T039 [US1] (FE) `frontend/src/api/backtest.ts` — `createBacktest(request)` → `POST /backtests`(`docs/specs/backtest-api/README.md` §1). 003이 채우는 필드(계약 §2): `executionType: 'SINGLE'`, `sectorAllocation`(002 값; `targetWeight`는 `targetWeightSource === 'USER'`일 때만 실어 보낸다), `strategy.{strategyId, rulesVersion, registryVersion, parameters}`, `conditions.{start, end, initialCash(문자열), costProfileVersion, warmupTradingDays, scenario: 'FULL_PERIOD'}`. `minEvaluationYears`는 004 계약에 아직 자리가 없어 이 요청에 싣지 않는다("착수 전에 알아둘 것" ⑧) — 그 값은 T042의 화면 판정에서만 쓰고 조용히 버린다. 거절 코드 400·409·422·503을 `BacktestRequestError`로 정규화. **`rulesVersion`(필수)과 `costProfileVersion`의 출처는 미결이다**("착수 전에 알아둘 것" ②) — `frontend/src/api/analysisVersions.ts` 한 파일의 `fetchAnalysisVersions()`에서만 읽도록 격리하고, 결정 전에는 이 함수가 `null`을 돌려주며 화면은 '분석하기'를 "분석 조건 버전을 확인할 수 없습니다"로 막는다. 값을 지어내 보내지 않는다
- [ ] T040 [US1] (FE) `frontend/src/pages/StrategySettingsPage.tsx`·`StrategySettingsPage.module.css` — 조립: `AppHeader` → 섹터 선택 확인(T030) → 등록표 조회(`universeSize` = 섹터 수) → `StrategyCardList` → 선택 시 `SettingsStatusBadge`·`ConditionSummaryLine`·`ActionButtons`. 상세 설정·기간금액조건 영역은 접힌 자리만 둔다(US2·US3에서 채운다). '분석하기'는 현재 값(처음엔 기본값)·`registryVersion`·현재 워밍업으로 요청을 조립해 `createBacktest` → 202면 `backtestId`와 상태를 보인다(결과 화면 이동은 005가 붙인다). `provisional:true`면 `frontend/src/components/paramsearch/ProvisionalBadge.tsx`를 재사용해 상시 표시하고 확정값으로 표현하지 않는다. 기본 화면의 입력은 전략 선택 하나다(SC-005) (T032~T039 의존)
- [ ] T041 [US1] (FE) `frontend/src/App.tsx` — `/strategy` 경로에 lazy `StrategySettingsPage` 추가. 주석에 "002 섹터·비중 → 003 전략 선택 → 004 분석 / 007 자동 비교" 흐름을 적는다 (T040 의존)
- [ ] T042 [US1] (FE) 워밍업·최소 평가 기간 판정 연결(FR-011, FR-011a) — `frontend/src/api/sectors.ts`에 `fetchEvaluationWindow({sectors, strategy})` → `GET /sectors/evaluation-window`(001 계약 `specs/001-etf-market-data/contracts/sector-api.md`); `frontend/src/hooks/useEvaluationWindow.ts` — 카드 목록은 전략별로, 선택 전략은 현재 설정으로 조회. 판정: `evaluable:false` → 카드 선택 불가 + 이유 한 문장; `evaluable:true`이고 `commonStart`가 워밍업 없이 계산한 값보다 늦으면, 워밍업을 뺀 뒤 남는 기간을 현재 `analysisCondition.minEvaluationYears`(기본 1, T032·T067)와 비교한다 — 못 미치면 `'blocked'` + "최소 평가 기간(N년)에 못 미칩니다" 한 문장, 충족하면 `'reduced'` + 줄어든 기간과 `bindingCandidate` 안내. `minEvaluationYears`가 바뀔 때마다 다시 판정한다(quickstart 7-2). **이 엔드포인트는 아직 없다** — 001 `tasks.md` T017이 "워밍업이 003 등록표에서 와야 한다"며 보류했었으나 003이 등록표를 연 지금은 001의 구현 작업만 남았다("착수 전에 알아둘 것" ①). 404·미구현·실패면 카드에 "공통 평가 기간을 확인할 수 없어 분석을 시작할 수 없습니다"를 보이고 '분석하기'를 막는다. 값을 지어내지 않는다(원칙 IV·VI) (T032·T034 의존)

### Frontend 테스트

- [ ] T043 [P] [US1] (FE 테스트) `frontend/src/components/strategy/StrategyCardList.test.tsx` — 픽스처 응답으로: 카드에 `name`·`description`만 있고 어떤 축 `label`도 보이지 않는다; 키보드로 카드를 고를 수 있고 `aria-pressed`가 바뀐다; `selectable:false` 카드는 눌러도 선택되지 않고 `aria-disabled`와 `unselectableReason`이 보인다; 조회 실패 상태면 카드 목록 대신 `RegistryUnavailableNotice`와 '다시 시도' 버튼이 보이며 재시도 성공·실패 뒤 초점이 NFR-001대로 이동한다(quickstart 9) (T034 의존)
- [ ] T044 [P] [US1] (FE 테스트) `frontend/src/hooks/useStrategySettings.test.tsx` — 전략을 고르면 축 `defaultValue`로 채워진다; 값 하나를 바꾸면 `isDefault=false`, 모두 되돌리면 `true`(quickstart 6); 다른 전략으로 옮겨도 이전 전략 값이 남고 새 전략에 적용되지 않는다(Edge case); `sessionStorage`의 `registryVersion` 또는 섹터 수가 다르면 새 규칙으로 재검증해 유효한 값은 유지하고 유효하지 않은 값은 이유·복원 선택으로 돌려주며 자동 보정하지 않는다; `analysisCondition.minEvaluationYears`가 초기값 1로 시작하고 `setAnalysisCondition`으로 바꿀 수 있다(FR-011a); `sessionStorage` 접근이 예외를 던져도 훅이 동작한다(research R8) (T032 의존)
- [ ] T045 [P] [US1] (FE 테스트) `frontend/src/utils/warmupRule.test.ts` — `{sum: ['lookbackDays', 'skipRecentDays']}`에 60·21을 넣으면 **81**(quickstart 7-1), 상수 항목 더하기, 축 값 없으면 예외. `frontend/vite.config.ts`의 `test.include`에 `'src/utils/warmupRule.test.ts'`를 더한다(지금 include는 `*.test.tsx`와 `src/api/auth.test.ts`·`src/auth/**`만이라 `.test.ts`가 잡히지 않는다) (T031 의존)
- [ ] T046 [US1] (FE 테스트) `frontend/src/pages/StrategySettingsPage.test.tsx` — SC-001: 카드 한 번 + '분석하기' 한 번으로 `createBacktest`(mock)가 기본값 `parameters`·`registryVersion`·`warmupTradingDays`로 호출된다(quickstart 4); 상세 설정을 열지 않았다; 기본 화면의 입력은 전략 선택 하나다(SC-005); 등록표 조회가 실패하면 게이트에서 확정된 행동 버튼이 모두 의미 있는 `disabled` 상태다(SC-008, quickstart 9); 배지가 '기본 설정'; `provisional:true`면 문구가 있는 임시값 배지가 보이고 색상만으로 구분하지 않는다(NFR-002) (T040~T042 의존)

**Checkpoint**: 카드 선택 한 번 + '분석하기' 한 번으로 기본값 요청이 만들어지고, 등록표 없이는 아무것도 시작되지 않는다. **US1 종단 데모는 "착수 전에 알아둘 것" ①·②가 풀린 뒤에 가능하다** — 그 전까지 화면은 정직하게 막힌 상태를 보인다

---

## Phase 4: User Story 2 - 상세 설정을 허용 범위 안에서 조정한다 (Priority: P2)

**Goal**: 선택한 전략의 축만 열어 목록형은 등록 후보에서 고르고 입력형은 범위 안 값만 받으며, 유효성 규칙을 화면이 그 자리에서 평가하고 서버가 접수 시점에 같은 규칙으로 다시 판정한다.

**Independent Test**: '변동성에 따라 나누기'를 선택해 상세 설정을 열고 목록형 항목에 등록 후보만 보이는지, 입력형 항목인 비중 상한에 범위 밖 값과 규칙 위반 값을 넣었을 때 각각 어떻게 동작하는지, 기본값 복원이 되는지 확인한다(quickstart 5·5-1·6). 서버에 화면을 거치지 않고 위반 요청을 보내 거절되는지 확인한다(quickstart 5-1 뒷부분).

### Backend — 접수 시점 재검증 (FR-006, 계약 §2)

- [ ] T047 [P] [US2] (BE) `backend/src/main/java/com/ssafy/thorp/strategy/application/exception/StrategySettingsRejectedException.java` — `BusinessException` 파생. `ErrorCode` 넷(`UNREGISTERED_PARAMETER_VALUE`·`STRATEGY_NOT_REGISTERED`·`VALIDATION_RULE_VIOLATED`·`REGISTRY_VERSION_CHANGED`) 중 하나와 상세(위반 규칙 `code`·`detail`, 또는 축 이름·허용 범위, 또는 요청 버전·현재 버전)를 담는다. 화면이 `detail`을 그대로 보이므로 문장은 등록표 것을 쓴다
- [ ] T048 [US2] (BE) `backend/src/main/java/com/ssafy/thorp/strategy/application/service/StrategySettingsValidator.java` — `Verified verify(String strategyId, Map<String, Object> parameters, String registryVersion, int universeSize, BigDecimal initialAmount)`. 순서: 요청 `registryVersion` ≠ `port.version()`이면 409 → 활성 전략에 없으면 422 `STRATEGY_NOT_REGISTERED` → 등록되지 않은 축 이름이 있거나 등록된 축이 빠져 있으면 400(빠진 축을 기본값으로 채우지 않는다 — 원칙 VI) → 각 값 `allowedRange.contains` 아니면 400(예외에 허용 범위를 담는다) → `port.validate(strategyId, parameters, new SearchContext(universeSize))`가 위반이면 422 `VALIDATION_RULE_VIOLATED` + `reasonCode`·`reasonDetail` → `initialAmount`가 `analysisConditions().initialAmount().range().contains` 아니면 400 → `Verified(int warmupTradingDays, String registryVersion)` 반환(`WarmupRule.evaluate`, 규칙 없으면 0). 잘라 넣거나 고쳐 통과시키는 경로는 없다 (T047 의존)
- [ ] T049 [US2] (BE) **[PARTIAL: 004 워커 메시지 정합 전]** `backend/src/main/java/com/ssafy/thorp/backtest/application/service/BacktestService.java`의 `create()`에 검증기 호출을 연결 — `strategyRulesService.requireRegistered(...)` 직후, `command.registryVersion()`이 **있는 요청에만** `validator.verify(command.strategyId(), command.parameters(), command.registryVersion(), command.items().size(), command.initialCash())`를 부르고, 돌려받은 워밍업을 `command.warmupTradingDays()`가 `null`일 때 대신 넘긴다(요청값이 있으면 004 기존 규칙대로 — 작으면 422 `INSUFFICIENT_HISTORY`). **`registryVersion`이 없는 요청은 종전처럼 통과시키고 접수 로그에 남긴다** — 004 픽스처와 워커가 아직 등록표 축 이름을 쓰지 않아 관문을 전부에 걸면 기존 접수가 막힌다("착수 전에 알아둘 것" ③). 이 호환 경로는 FR-006·SC-003 완료로 계산하지 않는다. 004가 워커 계약을 맞춘 뒤 모든 요청에 `registryVersion`과 등록표 검증을 필수화하는 후속 변경까지 끝나야 요구사항이 닫힌다. 의존 방향은 `backtest → strategy` 한 방향이고 `strategy`는 `backtest`를 참조하지 않는다. `BacktestJobRequest.java`와 `docs/specs/backtest-api/README.md`에도 현재의 조건부 검증과 미완료 상태를 그대로 적는다 (T048 의존)

### Backend 테스트

- [ ] T050 [P] [US2] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/strategy/application/service/StrategySettingsValidatorTest.java` — `RegistryFixtures` 합성 등록표로: 버전 불일치 409; 비활성 전략 422 `STRATEGY_NOT_REGISTERED`(`find`로는 찾히는 전략); 범위 밖 400이고 예외 메시지에 허용 범위가 있으며 값이 고쳐지지 않는다; 미등록 축·빠진 축 400; `maxWeight 30 × universeSize 3 < 100` 위반 422 `VALIDATION_RULE_VIOLATED`이고 `detail`이 규칙 문장 그대로, `34`는 통과(quickstart 5-1); 시작 금액 범위 밖 400; 워밍업 `sum` 둘짜리 81 (T048 의존)
- [ ] T051 [US2] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/backtest/presentation/controller/BacktestIntakeRegistryIT.java`(`PostgresTestSupport`) — `registryVersion`을 실은 요청이 400/409/422로 거절되고 `backtest_request`·`backtest_run` 행이 생기지 않는다; `registryVersion` 없는 기존 픽스처는 과도기 호환을 명시한 채 202이고 경고 로그가 남는다. 이 202를 FR-006·SC-003 통과로 보고하지 않는다. 응답 매핑은 `ParamSearchErrorResponseTest` 방식으로 `VALIDATION_RULE_VIOLATED`가 422 `$.error.code`로 나가는지 함께 본다(quickstart 5-1 "서버도 막는지") (T049 의존)

### Frontend — 규칙 평가기와 입력 컴포넌트

- [ ] T052 [P] [US2] (FE) `frontend/src/utils/validationRules.ts` — `evaluateRules(rules, values, context: {UNIVERSE_SIZE: number}): ValidationRule[]`(위반 규칙 목록). 피연산자 `{axis}`·`{context}`·`{value}`와 `multiplyByContext`, 연산자 여섯, **조건이 참이면 위반**. 피연산자의 축 값이 없으면 그 규칙은 판정하지 않는다(서버 `RegistryRule.isViolatedBy`와 같은 규약). 축 이름·코드로 분기하는 코드를 두지 않는다(research R7-1)
- [ ] T053 [P] [US2] (FE) `frontend/src/components/strategy/AxisChoiceField.tsx` — `inputType: 'CHOICE'` 축. 등록된 `choices`만 라디오/셀렉트로; `selectable:false` 후보는 흐리게 보이되 고를 수 없다(목록에서 빼지 않는다 — 계약 §1 `choices[].selectable`); 객체값(`rebalancePeriod`)은 `label`로 표시하고 값은 그대로 보관; `description`·`unit`·'기본값 복원' 버튼. 등록되지 않은 값을 넣을 경로가 없다(FR-006)
- [ ] T054 [P] [US2] (FE) `frontend/src/components/strategy/AxisNumberField.tsx` — `inputType: 'NUMBER'` 축. 입력값을 `range`(`min`·`max`·`step`)로 검사해 밖이면 허용 범위를 안내하고 **상태에 반영하지 않는다**(입력칸엔 남기되 잘라 넣지 않는다, FR-006); 안이면 반영. `description`·`unit`·'기본값 복원'. `range`·`defaultValue`·`label`·`unit` props에만 의존해 US3의 시작 금액에도 그대로 쓴다
- [ ] T055 [P] [US2] (FE) `frontend/src/components/strategy/ReadOnlyTargetWeights.tsx` — `targetWeightSource === 'USER'`일 때 002 비중(`useSectorSelection`)을 읽기 전용 표로 보이고 "고치려면 섹터·비중 단계로 돌아가세요" 링크(FR-003). 이 화면에서 고칠 수단을 두지 않는다
- [ ] T056 [US2] (FE) `frontend/src/components/strategy/DetailSettingsPanel.tsx` — 접힌 상태가 기본이고 열면 선택 전략의 `axes`만 `inputType`으로 T053/T054를 그린다(다른 전략 축은 렌더하지 않는다). 상단에 FR-004 문구. `targetWeightSource`가 `USER`면 `ReadOnlyTargetWeights`, `COMPUTED`면 "출력 비중은 선택한 규칙의 계산 결과이며 입력한 비중과 다릅니다"(FR-012), `EQUAL`이면 둘 다 없음. '모두 기본값 복원' 버튼. `evaluateRules` 결과를 `RuleViolationNotice`로 (T052~T055·T057 의존)
- [ ] T057 [P] [US2] (FE) `frontend/src/components/strategy/RuleViolationNotice.tsx` — 위반 규칙의 `detail`을 **그대로** 보인다(화면이 문장을 짓지 않는다, quickstart 5-1). `role="alert"`
- [ ] T057a [P] [US2] (FE) `frontend/src/components/strategy/TermHelp.tsx` — FR-016의 용어 도움말이다. 전략 카드 설명과 상세 설정 축 `description` 옆에서 [013](../013-learning-pages/spec.md) FR-008~FR-010이 소유하는 단일 원본을 읽어 보인다. 013 화면이 아직 없으므로 조회를 `frontend/src/api/learningGlossary.ts` 한 파일의 `fetchGlossaryEntry(term)`에 격리하고 **013이 구현되면 이 파일만 바꾼다**고 머리 주석에 적는다(T030 `useSectorSelection`과 같은 경계 방식). 대응하는 설명이 없거나 조회가 실패하면 즉석 문장을 만들지 않고 '설명 준비 중'을 보인다(013 FR-010). 도움말에는 해당 주제 글 연결을 두고, 도움말을 열거나 그 연결을 눌러도 `useStrategySettings` 상태가 초기화되지 않는다(013 FR-009, FR-017). 키보드로 열고 닫을 수 있으며 닫으면 초점이 호출한 자리로 돌아온다(NFR-001)
- [ ] T058 [US2] (FE) `StrategySettingsPage.tsx`에 상세 설정 연결 — 값이 바뀔 때마다 `evaluateRules` 재평가·`evaluateWarmup` 재계산·선택 카드 재판정(`useEvaluationWindow`에 현재 워밍업 전달)·배지 갱신. 위반이 있거나 워밍업 미충족이면 '분석하기' `disabled`. **값을 만질 때 서버 요청이 나가지 않는다**(research R7-1 기각 대안) (T056 의존)
- [ ] T059 [US2] (FE) 등록표 버전 변경 처리(research R9, quickstart 10) — `createBacktest`가 `REGISTRY_VERSION_CHANGED`(409)를 돌려주면 등록표를 다시 조회하고 T032의 공통 재검증 로직으로 유효한 값은 유지한 채 "확인하신 사이에 등록표가 바뀌었습니다"를 알리며, 유효하지 않은 값은 이유와 기본값 복원을 제안한다 → `frontend/src/components/strategy/RegistryChangedNotice.tsx`. 자동 보정하거나 다시 보내지 않는다 (T058 의존)

### Frontend 테스트

- [ ] T060 [P] [US2] (FE 테스트) `frontend/src/utils/validationRules.test.ts` — 연산자 여섯; `multiplyByContext`: `maxWeight 30 × UNIVERSE_SIZE 3 < 100` 위반, `34 × 3` 통과(quickstart 5-1); `{context}` 피연산자(`topN > UNIVERSE_SIZE`); 축 값 없으면 판정 보류. `vite.config.ts` `test.include`에 `'src/utils/validationRules.test.ts'` 추가 (T052 의존)
- [ ] T061 [P] [US2] (FE 테스트) `frontend/src/components/strategy/AxisNumberField.test.tsx` — 10~100 step 5에서 `5` 입력 → 허용 범위 안내, `onChange` 미호출, `10`으로 잘리지 않음(quickstart 5); `35` → 반영; `33` → 간격 안내; '기본값 복원' → `defaultValue` (T054 의존)
- [ ] T062 [P] [US2] (FE 테스트) `frontend/src/components/strategy/AxisChoiceField.test.tsx` — 등록 후보만 렌더; `selectable:false` 후보는 보이되 선택되지 않음; 객체값은 `label`로 보이고 선택 시 원래 객체가 전달됨 (T053 의존)
- [ ] T063 [US2] (FE 테스트) `frontend/src/components/strategy/DetailSettingsPanel.test.tsx` — 선택 전략 축만 보이고 다른 전략 축 `label`이 없다; `USER`면 읽기 전용 비중과 이동 안내, `COMPUTED`면 FR-012 설명; 값을 바꾸면 규칙 위반 `detail`이 **즉시** 보이고 `fetch` mock 호출이 0건; 모두 복원하면 위반이 사라지고 배지가 '기본 설정'(quickstart 6); 축 도움말을 열면 013 원본 문구가 보이고 대응 항목이 없는 용어는 '설명 준비 중'이며 도움말을 열고 닫아도 바꾼 값이 남는다(FR-016) (T056·T057a·T058 의존)
- [ ] T064 [US2] (FE 테스트) `frontend/src/pages/StrategySettingsPage.test.tsx` 확장 — 관측 기간을 늘리면 선택 카드의 워밍업 안내가 그 자리에서 갱신된다(quickstart 7, `useEvaluationWindow` mock); 409 응답 뒤 등록표를 다시 조회하고 알림이 보이며 자동 재전송이 없다(quickstart 10) (T058·T059 의존)

**Checkpoint**: 목록형은 등록 후보만, 입력형은 범위 안 값만 받고, 규칙 위반은 입력 즉시 안내되며 서버도 같은 요청을 거절한다

---

## Phase 5: User Story 3 - 기간·금액·조건을 확인하고 바꾼다 (Priority: P2)

**Goal**: '기간·금액·조건'에서 공통 평가 가능 범위 안의 분석 기간, 등록표 범위 안의 시작 금액, 비용 가정 적용 여부를 확인하고 바꾼다. 공통 구간이 줄면 실행 전에 알린다.

**Independent Test**: 상장일이 다른 섹터를 섞어 선택한 뒤 자동 제시된 분석 기간이 공통 평가 가능 범위와 같은지, 시작 금액을 범위 안·밖으로 바꿨을 때 동작을 확인한다(quickstart 7의 기간 부분, 5의 입력형 부분). 수익을 높이는 방향의 자동 최적화 선택지가 어디에도 없는지 확인한다.

시작 금액의 서버 검증은 T048에 이미 들어 있어 이 단계에 백엔드 태스크가 없다.

### Frontend

- [ ] T065 [US3] (FE) **[BLOCKED: 004 비용 버전 조회 계약]** `frontend/src/components/strategy/AnalysisConditionsPanel.tsx` — 접힌 상태가 기본. 열면: 분석 기간 시작·종료(`useEvaluationWindow`의 `commonStart`~`commonEnd`를 `min`/`max`로 두어 **범위 밖은 선택할 수 없다**, FR-009); 시작 금액(`AxisNumberField` 재사용, `analysisConditions.initialAmount`의 `range`·`default`·`unit`); 비용 가정은 004에서 조회한 수수료·호가 차이·슬리피지·세금 수치와 버전·`provisional` 여부를 한 줄의 읽기 전용 값으로 보인다. 조회 계약이 없을 때 일반 문구로 대체하지 않고 "비용 조건을 확인할 수 없습니다"로 분석을 막는다. 최소 평가 기간은 등록표 `analysisConditions.minEvaluationYears`의 후보(`[0,1,3]`)를 완화·기본·엄격 라벨로 보이는 라디오/셀렉트이며, 바뀌면 `setAnalysisCondition`으로 반영해 T042의 카드 판정이 다시 돈다(FR-011a). 시나리오는 '연속 운용' 고정 표시하고 목표 변동성은 이 화면에 보이지 않는다. 수수료·세금·기간을 사용자가 바꾸거나 최적화하는 선택지는 두지 않는다(FR-010, 원칙 IV) (T032·T042·T054 의존)
- [ ] T066 [US3] (FE) `frontend/src/components/strategy/ReducedPeriodNotice.tsx` — 실행 전 안내(FR-011, SC-004): 워밍업 때문에 `commonStart`가 밀렸으면 줄어든 구간과 원인(`bindingCandidate` 섹터, 현재 관측 기간·최근 제외 기간) 한 문장. '분석하기' 바로 위와 선택 카드에 함께 보인다. 줄지 않았으면 렌더하지 않는다 (T042 의존)
- [ ] T067 [US3] (FE) `frontend/src/hooks/useStrategySettings.ts`의 `analysisCondition` 기본값 채움과 연결 — 처음 `periodStart`/`periodEnd`는 evaluation window의 `commonStart`/`commonEnd`, `initialAmount`는 등록표 `default`, `minEvaluationYears`는 등록표 `default`(1), `scenario`는 `FULL_PERIOD`. `ConditionSummaryLine`(T037)이 이 값을 읽고, `createBacktest`(T039)가 `conditions`에 싣는다. 사용자가 바꾼 기간은 `sessionStorage`에 함께 남는다(FR-017) (T065 의존)

### Frontend 테스트

- [ ] T068 [P] [US3] (FE 테스트) `frontend/src/components/strategy/AnalysisConditionsPanel.test.tsx` — 기간 입력의 `min`/`max`가 evaluation window와 같고 범위 밖 값은 반영되지 않는다; 시작 금액의 빈 값·비숫자·최소 미만·최대 초과·간격 불일치는 저장되지 않고 경계값은 허용된다(SC-002); 수수료·세금 편집과 "최적화"·"수익 극대화" 컨트롤이 없다(FR-010); 비용 수치·버전·임시 문구가 함께 보이며 조회 실패 때 분석 차단 사유가 보인다; 최소 평가 기간을 완화·기본·엄격 중 바꾸면 `setAnalysisCondition`이 그 값으로 호출된다(FR-011a, quickstart 7-2); 목표 변동성 컨트롤이 없다 (T065 의존)
- [ ] T069 [P] [US3] (FE 테스트) `frontend/src/components/strategy/ReducedPeriodNotice.test.tsx` — 줄어든 구간과 `bindingCandidate`가 문장에 있다; 줄지 않았으면 렌더되지 않는다 (T066 의존)

**Checkpoint**: 기간·금액·비용이 요약 한 줄과 패널에 보이고, 기간은 공통 범위 안에서만, 금액은 등록표 범위 안에서만 바뀐다

---

## Phase 6: User Story 4 - 설정 조합 자동 비교로 넘어간다 (Priority: P3)

**Goal**: 전략 하나와 섹터 후보 집합을 고정해 007의 실행 전 확인 화면으로 넘긴다. 진입점만 003의 범위다.

**Independent Test**: 전략 하나를 선택한 뒤 '설정 조합 자동 비교'를 눌러 그 전략과 섹터 집합이 유지된 채 `/param-search`가 열리는지, 다른 전략이 함께 넘어가지 않는지 확인한다(quickstart 11).

- [ ] T070 [US4] (FE) `ActionButtons`의 둘째 버튼 동작을 `StrategySettingsPage.tsx`에 연결 — `navigate('/param-search?strategies=' + selectedStrategyId + '&instruments=' + instrumentIds.join(','))`. 007 `ParamSearchPage`가 읽는 질의 문자열 그대로이며 **전략은 하나만** 싣는다(FR-013, Clarifications). 전략 미선택·등록표 없음이면 `disabled`. 후보 숫자를 사용자에게 입력받지 않는다 (T040 의존)
- [ ] T071 [US4] (FE 테스트) `frontend/src/pages/StrategySettingsPage.test.tsx` 확장 — 전략 하나 선택 후 자동 비교 클릭 → 이동 경로의 `strategies`에 그 전략 하나만, `instruments`에 섹터 집합 전부; 미선택이면 버튼 `disabled`; 등록표 실패 상태면 `disabled`(quickstart 11) (T070 의존)

**Checkpoint**: 네 스토리가 모두 독립적으로 동작한다

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 문서와 코드를 계약에 맞추고, 잔재를 지우고, 전체 검증을 돈다

### 문서

- [ ] T072 [P] (DOCS) `specs/003-strategy-settings/data-model.md`·`quickstart.md`·`plan.md`의 클래스 이름·경로(`ProvisionalFileParameterRegistry` → `FileParameterRegistry`, 로더 위치)를 구현과 맞춘다. `research.md` "남은 의존과 미확정" 표에 이 tasks.md "착수 전에 알아둘 것"의 ①~③ 상태를 반영한다
- [ ] T073 [P] (DOCS) `frontend/CLAUDE.md` "규칙"에 두 줄 추가 — "전략 이름·축·기본값·허용 범위는 `GET /strategies/registry` 응답에서만 읽고 코드에 상수로 두지 않는다(spec 003 FR-005)"와 "`sessionStorage` 키: `thorp.strategySettings`(003 설정값, 탭 수명), `thorp.sectorSelection`(002 경계 어댑터)". `backend/CLAUDE.md` "규칙"에 접수 관문 한 줄 — "`POST /backtests`는 `registryVersion`이 있는 요청만 003 등록표로 검증한다(400/409/422). 관문을 모든 요청에 걸려면 워커가 등록표 축 이름을 받는 것이 먼저다"
- [ ] T074 (DOCS) [quickstart.md](quickstart.md) 1~11 수동 확인을 돌리고 결과를 MR 본문 "테스트" 절에 항목별로 적는다. 001 `GET /sectors/evaluation-window`가 아직 없으면 7·7-1은 훅·유틸 테스트(T045·T064)로 대신하고 그 사실을 적는다. ②가 미결이면 4의 '분석하기'는 "분석 조건 버전을 확인할 수 없습니다"로 막히는 것이 기대 동작이다
- [ ] T074a (DOCS) SC-006 사용성 관찰 — 002 SC-004와 같은 회차에서 전략을 검증하려는 개인 6~8명에게 진행자의 화면 설명 없이 전략 카드 하나를 고르고 분석 요청을 접수하게 한 뒤 단계별로 막힌 인원을 기록한다. 같은 단계에서 두 명 이상이 다음 행동을 찾지 못해 설명을 요청하면 화면을 고치고 같은 시나리오를 다시 관찰해 두 명 미만일 때 끝낸다. 관찰 기록과 조치 내역을 `specs/003-strategy-settings/` 아래 결과 문서로 남기고 MR 본문에 요약한다. **이것은 코드 구현이 아니라 관찰 절차이므로 B12 병합을 막지 않으며, 회차 일정은 002와 함께 잡는다**

### 코드 정리

- [ ] T075 [P] (BE) 잔재 정리 — `grep -rn "Provisional\|parameter-registry-provisional" backend/src`가 클래스·파일 이름 잔재 0건(`PROVISIONAL-` 버전 접두 문자열과 `provisional()` 메서드는 제외); `backend/src/main/java/com/ssafy/thorp/paramsearch/infrastructure/registry/` 디렉터리가 없다; `strategy/package-info.java`와 각 클래스 Javadoc이 "003 대역"이 아니라 "003 정식 구현"으로 읽힌다
- [ ] T076 [P] (FE) `frontend/src/components/strategy/*.tsx`에 전략 ID·축 이름 문자열 리터럴이 없는지 `grep -rn "FIXED_WEIGHT\|EQUAL_WEIGHT\|INVERSE_VOLATILITY\|SECTOR_MOMENTUM\|MINIMUM_VARIANCE\|lookbackDays\|maxWeight\|topN" frontend/src/components/strategy frontend/src/pages/StrategySettingsPage.tsx frontend/src/hooks/useStrategySettings.ts`로 확인 — 0건이어야 한다(FR-005·FR-015). 테스트·픽스처는 제외

### 전체 검증

- [ ] T077 (BE 테스트) `cd backend && ./gradlew spotlessApply build` — ArchUnit(규칙 8 포함)·007·003 전부. `skipped`가 Docker 부재 때문인지 확인한다(backend/CLAUDE.md)
- [ ] T078 (FE 테스트) `cd frontend && npm run lint && npm run build && npm run test` — Vitest include에 새 `.test.ts` 둘이 들어갔는지 결과 목록에서 확인한다

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**: 선행 없음. T002~T006은 전부 병렬
- **Phase 2 Foundational**: Setup 완료 후. **모든 사용자 스토리를 막는다.** 안에서도 §4 순서가 있다 — 1단계(T007~T010) → 2단계 확인(T011·T012) → 3단계(T013~T020) → 4단계 확인(T021) → T022
- **Phase 3~6 User Stories**: Foundational 완료 후
- **Phase 7 Polish**: 원하는 스토리들이 끝난 뒤

### User Story Dependencies

- **US1 (P1)**: Foundational 후 바로. 다른 스토리에 의존하지 않는다 → **MVP**. 단, 종단 데모는 "착수 전에 알아둘 것" ①·② 뒤다
- **US2 (P2)**: US1의 `useStrategySettings`(T032)·`StrategySettingsPage`(T040)·`useEvaluationWindow`(T042)를 쓴다. 백엔드 검증기(T047~T051)는 US1 없이도 Foundational만으로 진행·검증할 수 있다
- **US3 (P2)**: US1의 T032·T037·T039·T042와 US2의 `AxisNumberField`(T054)를 쓴다. 백엔드 태스크 없음
- **US4 (P3)**: US1의 T040만. 가장 작다

### 작업 묶음 = 티켓·브랜치·MR 단위 (B01~B12)

**`T###`는 티켓 단위가 아니다.** constitution "Jira 연계"가 `작업 묶음 = 1 Branch = 1 MR`로 정한다.
아래 열둘이 Jira Task·브랜치·MR의 단위이고, 81개 태스크는 그 안에서 실행 지시의 최소 단위로 남는다.
같은 표가 [plan.md](plan.md) "작업 묶음 구성"에 있다.

**묶음 이름을 계층이 아니라 목적으로 붙였다.** "값 타입 확장" 같은 이름은 그 MR이 끝나도 무엇이
달라지는지 말해 주지 않는다. 아래 이름은 그대로 Jira Task 제목이 되고, MR을 열어 본 사람이
"이게 머지되면 무엇이 되나"를 한 줄로 안다.

| 묶음 · 티켓 | Jira Task 제목 | 포함 T### | 수 | position | 선행 |
|---|---|---|---|---|---|
| B01 · [233](https://ssafy.atlassian.net/browse/S15P21E205-233) | `[BE] 등록표를 003 소유로 옮긴다` | T001, T007~T012 | 7 | `be` | 없음 |
| B02 · [234](https://ssafy.atlassian.net/browse/S15P21E205-234) | `[BE] 등록표가 화면에 필요한 항목을 담는다` | T013~T022(T018a 포함) | 11 | `be` | B01 |
| B03 · [235](https://ssafy.atlassian.net/browse/S15P21E205-235) | `[BE] 전략·파라미터 등록표 조회 API` | T003, T004, T023~T028 | 8 | `be` | B02 |
| B04 · [236](https://ssafy.atlassian.net/browse/S15P21E205-236) | `[FE] 전략 카드 다섯을 보인다` | T005, T006, T029, T030, T033~T035, T043 | 8 | `fe` | B03의 계약(T003·T004) |
| B05 · [237](https://ssafy.atlassian.net/browse/S15P21E205-237) | `[FE] 카드를 골라 분석을 시작한다` | T031, T032, T036~T038, T040, T041, T044~T046 | 10 | `fe` | B04 |
| B06 · [238](https://ssafy.atlassian.net/browse/S15P21E205-238) | `[FE] 분석 요청과 평가 기간을 연결한다` | T039, T042 | 2 | `fe` | B05 + 001 구현 + **004 결정** |
| B07 · [239](https://ssafy.atlassian.net/browse/S15P21E205-239) | `[BE] 접수 시점에 설정을 다시 검증한다` | T002, T047~T051 | 6 | `be` | B02 |
| B08 · [240](https://ssafy.atlassian.net/browse/S15P21E205-240) | `[FE] 상세 설정을 열어 값을 바꾼다` | T052~T057(T057a 포함), T060~T063 | 11 | `fe` | B05 |
| B09 · [241](https://ssafy.atlassian.net/browse/S15P21E205-241) | `[FE] 바꾼 값을 그 자리에서 판정한다` | T058, T059, T064 | 3 | `fe` | B08 |
| B10 · [242](https://ssafy.atlassian.net/browse/S15P21E205-242) | `[FE] 기간·금액·조건을 바꾼다` | T065~T069 | 5 | `fe` | B09 |
| B11 · [243](https://ssafy.atlassian.net/browse/S15P21E205-243) | `[FE] 설정 조합 자동 비교로 넘어간다` | T070, T071 | 2 | `fe` | B05 |
| B12 · [244](https://ssafy.atlassian.net/browse/S15P21E205-244) | `[DOCS] 문서 동기화와 최종 검증` | T072~T078(T074a 포함) | 8 | `fe` | 전부 |

```text
B01 ─▶ B02 ─┬─▶ B03 ─▶ B04 ─▶ B05 ─┬─▶ B08 ─▶ B09 ─▶ B10 ─┐
            │                       ├─▶ B11                 ├─▶ B12
            └─▶ B07                 └─▶ B06 (001 구현 + 004 결정 대기) ┘
```

**각 묶음이 끝나면 무엇이 달라지나.** 이것이 묶음 조건 1(하나의 목적)을 만족하는지 보는 기준이다.

| 묶음 | 끝나면 |
|---|---|
| B01 | 등록표의 소유가 003으로 넘어오고 007이 그대로 돈다. 의존 방향이 규칙으로 선다 |
| B02 | 등록표가 활성 여부·워밍업 규칙·전략별 축·분석 조건을 담는다 |
| B03 | 화면이 읽을 수 있는 조회 주소가 열린다 |
| B04 | 다섯 카드가 이름과 한 줄 설명으로 보이고, 못 고르는 카드는 이유가 함께 보인다 |
| B05 | 카드 한 번과 '분석하기' 한 번으로 요청이 만들어진다 |
| B06 | 그 요청이 실제로 서버로 나간다 |
| B07 | 화면을 우회한 요청도 서버가 거절한다 |
| B08 | 상세 설정에서 등록 범위 밖 값이 저장되지 않는다 |
| B09 | 규칙 위반과 워밍업 재판정이 입력 즉시 보인다 |
| B10 | 분석 기간과 시작 금액을 허용 범위 안에서 바꾼다 |
| B11 | 고른 전략 하나가 007의 확인 화면으로 넘어간다 |
| B12 | 임시 대역의 잔재가 없고 양쪽 빌드가 돈다 |

**묶음을 계층으로 더 쪼개지 않는다.** B02를 "값 타입"과 "로더"로 나누면 앞 조각이 혼자서는
아무것도 이루지 않는다. 묶음 조건 3의 "미완성 task가 없다"를 어긴다. 크기를 고르게 맞추는 것보다
목적이 하나인 편이 중요하다 — 그래서 크기가 둘에서 열하나까지 벌어진다.

- **B01이 나머지 백엔드 전부를 막는다.** 등록표가 옮겨지기 전에는 `strategy` 패키지에 조회 서비스를 둘 수 없다
- **B04는 B03의 계약 부분만 기다린다** — [contracts/strategy-registry-api.md](contracts/strategy-registry-api.md)가
  응답 모양을 고정했고 T006 픽스처로 화면 테스트가 돈다. B03이 계약(T003·T004)을 먼저 커밋하면
  머지 전에도 프런트엔드가 출발할 수 있다. 실제 서버와 붙여 보는 것만 B03 머지 뒤다
- **B07은 B03과 병렬이다** — 둘 다 B02의 포트만 쓰고 파일이 겹치지 않는다
  (`strategy/application/service/` 안의 서로 다른 클래스, `backtest/application/service/BacktestService.java`는 B07만)
- **B06은 001의 엔드포인트 구현과 004의 조회 계약 결정을 기다린다.** 따로 뺀 이유는 막히지 않은 것이 막힌 것 때문에 함께 서지 않게 하려는 것이다
- **묶음 안의 태스크를 절반만 올리지 않는다.** 특히 B01·B02는 §4의 2단계·4단계 확인(T011·T021)이
  통과한 상태로만 MR을 올린다. 그 확인은 브랜치를 나누지 말고 커밋으로 나눈다
- **묶음마다 Jira Task를 먼저 만들고 그 번호로 브랜치를 만든다**(constitution). 브랜치는
  `{type}({position})/{티켓번호}-{설명}`이며 B01은 이동이라 `refactor`, B12는 `docs`, 나머지는 `feat`다

### 파트 사이 순서

- `(BE)` 포트·값 타입(T007~T015) → `(INFRA)` 등록표 파일(T017) → `(BE)` 로더(T016): 로더가 읽을 필드가 먼저 정해져야 한다. T016과 T017은 같은 사람이 이어서 한다
- `(DOCS)` 계약 필드 추가(T003)와 픽스처(T004) → `(BE 테스트)` 계약 테스트(T026)와 `(FE)` 타입(T005): 계약이 먼저다
- `(BE)` 응답 DTO → `(FE)` 화면: 계약 문서가 이미 응답 모양을 고정했으므로 **프론트는 백엔드를 기다리지 않고 병행**한다

### Within Each User Story

- 테스트를 먼저 쓰고 실패시킨 뒤 구현한다
- BE: 값 타입 → 서비스 → 컨트롤러 → 계약 테스트
- FE: 타입·픽스처 → 유틸(순수 함수) → 훅 → 컴포넌트 → 페이지 조립 → 페이지 테스트

### Parallel Opportunities

- Phase 1: T002~T006 전부 병렬
- Phase 2: T013·T014 병렬(다른 파일), T019·T020 병렬. 나머지는 §4 순서
- US1 BE: T023 → T024 → T025 직렬, T027은 T024 뒤 T026·T028과 병렬
- US1 FE: T029·T030·T031 병렬 → T032 → T033·T035~T038 병렬 → T034 → T039 → T040 → T041·T042 → T043~T046 병렬
- US2: BE(T047~T051)와 FE(T052~T064)는 다른 담당이 동시에. FE 안에서 T052·T053·T054·T055·T057 병렬
- US3: T068·T069 병렬
- **BE와 FE는 스토리 안에서 병렬** — 계약이 고정되어 있다

---

## Parallel Example: User Story 1

```bash
# 백엔드 — 결과 DTO 뒤 서비스, 그 뒤 컨트롤러·테스트
Task: "T023 StrategyRegistryResult record 집합"
Task: "T024 StrategyRegistryQueryService"            # T023 뒤
Task: "T025 StrategyRegistryController + Response"     # T024 뒤
Task: "T026 계약 픽스처 대조 테스트" / "T027 서비스 단위 테스트" / "T028 실제 등록표 IT"   # 병렬

# 프론트엔드 — 계약 문서 기준으로 백엔드와 동시에
Task: "T029 api/strategyRegistry.ts" / "T030 hooks/useSectorSelection.ts" / "T031 utils/warmupRule.ts"   # 병렬
Task: "T032 hooks/useStrategySettings.ts"             # T029·T031 뒤
Task: "T033 StrategyCard" / "T035 RegistryUnavailableNotice" / "T036 SettingsStatusBadge" / "T037 ConditionSummaryLine" / "T038 ActionButtons"   # 병렬
Task: "T034 StrategyCardList" → "T039 api/backtest.ts" → "T040 StrategySettingsPage" → "T041 라우트" / "T042 useEvaluationWindow"
Task: "T043" / "T044" / "T045" / "T046"               # 테스트 병렬
```

---

## Implementation Strategy

### MVP First (User Story 1만)

1. **B01** 등록표를 003 소유로 옮긴다 — **여기가 가장 위험하다. 007이 실제로 쓰는 등록표를 건드린다.** T011·T012의 확인을 건너뛰지 않는다
2. **B02** 등록표가 화면에 필요한 항목을 담는다 — T021의 확인으로 007이 그대로 도는지 다시 본다
3. **B03** 조회 API를 연다. 계약(T003·T004)을 먼저 커밋해 B04가 출발할 수 있게 한다
4. **B04** 다섯 카드를 보인다
5. **B05** 카드를 골라 분석을 시작한다
6. **멈추고 검증**: quickstart 2·3·3-1·4·9. ①·②가 미결이면 4는 "막힌 상태가 정직하게 보이는가"까지
7. 데모 — 카드를 고르고 기본값으로 분석 요청을 조립하는 데까지

**MVP = 묶음 다섯(B01~B05) = 티켓·브랜치·MR 다섯.** T001~T046 가운데 T039·T042를 뺀 것이다(그 둘은 B06).

### Incremental Delivery

1. B01·B02 → 등록표가 003 소유가 되고 007이 그대로 돈다
2. B03·B04·B05 → quickstart 2·3·4·9 → **MVP 데모**
3. B07 → quickstart 5-1 뒷부분 → 화면을 우회한 요청도 서버가 막는다
4. B08·B09 → quickstart 5·6·7·10 → 범위·규칙·재판정
5. B10·B11 → quickstart 8·11 → 조건 확인과 007 진입
6. B06 → 001이 붙이고 004가 결정하면 분석 요청을 실제로 내보낸다
7. B12 → 문서·잔재·전체 검증

릴리즈는 매주 금요일이므로(constitution Git 규칙) 한 주에 닫을 묶음을 골라 쌓는다.

### 착수 전에 알아둘 것

구현을 막거나 방향을 바꿀 수 있는 사실이다. 티켓으로 만들지 않고 여기와 MR 본문에 적는다(AGENTS.md "곁가지는 티켓으로 만들지 않는다").

① **001의 `GET /sectors/evaluation-window`가 아직 없다.** 001 `tasks.md` T017이 "워밍업이 003 등록표에서 와야 하는데 003이 없어 만들 수 없다"며 보류했었다. **003이 등록표를 열고([S15P21E205-254](https://ssafy.atlassian.net/browse/S15P21E205-254)) `warmupTradingDays` 산출 방식을 확정했으므로 이 보류 사유는 해소됐다** — 001은 자신의 T017에서 계산 로직(`EvaluationWindowService`)에 호출부만 붙이면 된다. 남은 것은 **001의 구현 작업**이지 팀 결정 대기가 아니다. 그 전까지 T042는 카드를 막힌 상태로 두고 값을 지어내지 않는다.

② **분석 요청의 `rulesVersion`(004 필수 필드)과 `costProfileVersion`을 화면이 알 길이 없다.** [data-model](data-model.md) §7 "확인된 빈 구멍"의 세 갈래(004가 조회 API를 연다 / 003 등록표 응답에 싣는다 / 화면은 적용 여부만 보인다) 중 어느 쪽인지 **004와 함께 정한다.** T039는 이 둘을 `analysisVersions.ts` 한 파일에 격리했으므로 결정 뒤 그 파일만 바뀐다. 결정 전에는 '분석하기'가 막힌다 — US1 종단 데모가 이 결정 뒤라는 뜻이다.

③ **등록표 축 이름이 워커에 닿지 않는다.** 워커는 `parameters.rebalance == "MONTH_END"` 문자열과 `rules.lookbackDays`만 읽는다. 003의 `rebalancePeriod` 객체값, `maxWeight`·`topN`·`skipRecentDays`·`allocationMethod`·`estimationMethod`는 004 메시지 계약(`docs/specs/backtest-messaging/`)과 워커 어느 쪽에도 자리가 없다. **004 소유다.** 그래서 T049의 접수 관문은 `registryVersion`이 있는 요청에만 걸고, 전부에 걸어 FR-006을 완전히 닫는 시점은 워커 정합 뒤로 남긴다. T017이 어긋난 곳을 표로 남긴다.

④ **포트 시그니처가 한 곳 바뀐다.** `validate()`의 `ParameterCombination`(007 도메인 VO)을 그대로 두면 `strategy → paramsearch` 역방향 의존이 생겨 T012의 ArchUnit 규칙에 걸린다. T008이 `Map<String, Object>`로 바꾸고 `CandidateValidator` 한 줄이 따라 바뀌며, plan.md Summary도 이 범위를 명시한다.

⑤ **002 화면이 없다.** 002는 spec만 있고 plan·tasks·코드가 없다. T030이 `useSectorSelection` 어댑터로 경계를 만들어 002가 들어올 때 그 파일만 바꾸게 했다. 지금은 질의 문자열·`sessionStorage`로 섹터 집합을 넣어 확인한다.

⑥ **007 spec은 "하나 또는 여러 전략"을 넘긴다고 적었고 003은 하나만 넘기기로 정했다**([research](research.md) "남은 의존과 미확정"). T070은 003 결정대로 하나만 싣는다. 문서 차이는 팀이 정한다.

⑦ **계약에 필드 둘을 더했다**(T003): `axes[].description`과 `strategies[].targetWeightSource`. 화면이 전략 ID·축 이름으로 분기하지 않으려면(FR-005·research R7-1) FR-003의 '의미'·FR-012의 자동 배분 설명·FR-019의 재배분 안내가 등록표에서 와야 하기 때문이다. 리뷰에서 다른 방법이 나오면 T003·T005·T017·T055·T056이 함께 바뀐다.

⑧ **FR-011a(최소 평가 기간)를 서버가 다시 검증하려면 004의 백테스트 요청 계약에 필드가 하나 늘어야 한다.** 003은 `warmupTradingDays`처럼 `minEvaluationYears`도 요청에 실어 [StrategySettingsValidator](contracts/strategy-registry-internal.md)가 `periodEnd - periodStart` 간격을 산술로 재확인하게 했다([data-model](data-model.md) §10). 하지만 `docs/specs/backtest-api/README.md`의 `conditions` 스키마는 003이 단독으로 고칠 문서가 아니다 — **004와 합의해 그 계약에 `minEvaluationYears`(또는 같은 뜻의 필드)를 추가해야 한다.** 합의 전에는 T048의 이 검증 단계를 구현하지 않고 화면 쪽 차단(T042의 `useEvaluationWindow`)만으로 막는다 — 화면을 거치지 않은 직접 API 요청까지는 원칙 VI를 완전히 만족하지 못한다는 뜻이며, T049의 과도기 호환 경로(③)와 같은 성격의 빈틈이다.

---

## Notes

- [P] = 다른 파일, 선행 없음
- [Story] 라벨은 spec.md 사용자 스토리와의 추적용이다
- 등록표 값을 코드·화면·테스트 기대값에 옮겨 적지 않는다. 운영 코드는 응답·포트에서 읽고, 테스트는 합성 등록표(`RegistryFixtures`·`strategyRegistryFixtures.ts`)나 파일 재독으로 대조한다
- 태스크마다 커밋하고, 체크포인트마다 멈춰 해당 quickstart 항목을 돈다
- 등록표 값을 바꿀 일이 생기면 행을 고치지 말고 `version`을 올린다(내부 계약 §2.1)

---

## Phase 8: Convergence

**Purpose**: 완료한 묶음(B01~B03)을 spec·plan과 대조해 남은 간극을 메운다. 아직 착수하지 않은 묶음(B04~B12)은
위 단계가 이미 추적하므로 여기 옮겨 적지 않는다.

- [X] T079 (INFRA·BE) 등록표가 목록형 후보의 표시 문구를 소유하게 한다 per FR-005·FR-011a (partial) — 지금은 그 문구가 세 곳에 흩어져 있다: `parameter-registry.yml`의 `minEvaluationYears`는 완화/기본/엄격을 **주석으로만** 적어 두었고(`AnalysisCondition`의 컴포넌트가 `range`·`defaultValue`·`unit`·`usedBy` 넷뿐이라 담을 자리 자체가 없다), `StrategyRegistryQueryService.label`은 `MONTH_END`→월말·`TRADING_DAYS`→"N거래일" 같은 문구를 서버 코드 상수로 들고 있으며, 문자열 후보(`TOP_N`·`THRESHOLD`·`TOP_PERCENT`·`ALL`·`EQUAL`·`SCORE_WEIGHTED`·`SAMPLE_COV`)는 값 그대로 `choices[].label`이 되어 화면에 `TOP_N`이 보인다. **라벨이 등록표에서 오지 않으면 화면이 `0`·`1`·`3`을 무슨 말로 보일지 코드에 적어야 하고, 그것이 FR-005가 금지한 "화면 코드에 따로 적어 두는 안내"다.** 축 `allowedRange.values`와 `analysisConditions.*.values`의 후보마다 라벨을 적을 자리를 등록 구조에 두고, 로더가 읽어 조회 응답이 그대로 싣게 한다. **`minEvaluationYears`만 따로 푸는 형태를 고르지 않는다** — `rebalancePeriod`의 `{mode: CALENDAR, unit: MONTH_END}`도 같은 필요가 있어 축 쪽에서 같은 문제를 다시 풀게 된다. 계약 §1의 `ChoiceView`와 같은 모양으로 맞춘다. 그러면 `label` 메서드의 상수가 사라지고 T065가 화면더러 만들라고 한 완화/기본/엄격 라벨도 등록표에서 온다. **등록표 값이 바뀌므로 `version`을 올린다**(내부 계약 §2.1, 현재 `1.0.0`). 계약 §1 필드 표와 `docs/specs/strategy-registry-api/examples/registry-response.json` 픽스처, `backend/src/test/resources/registry/contract-registry.yml`을 함께 고친다 — 셋이 어긋나면 `StrategyRegistryContractTest`가 깨진다
- [X] T080 `allowedRange`가 없는 축으로는 기동하지 못하게 막는다 per plan "Testing" 1 (partial) — `FileParameterRegistry.toAllowedRange`가 `allowedRange` 절이 없는 축에 null을 돌려주고 `requireWithinAllowedRange`도 null이면 그냥 지나간다. 그런 축은 화면이 목록을 그릴지 입력칸을 그릴지 정할 수 없어 `StrategyRegistryQueryService.toAxisView`가 조회 시점에 `IllegalStateException`(500)을 던진다. **잘못 적힌 등록표는 기동에서 걸러야 한다**(data-model §3, 로더 Javadoc이 스스로 말하는 원칙). 로더에 검사를 더하고 `FileParameterRegistryTest`의 "새 제약을 어긴 합성 YAML이 기동 실패한다" 묶음에 한 건 더한다. 검사가 생기면 조회 쪽 방어는 그대로 두되 Javadoc에서 "로더가 이미 막는다"로 고친다
- [X] T081 [P] `data-model.md` §4-2의 후보·허용범위 표기를 구현과 맞춘다 per plan: data-model §4-2 (contradicts) — §4-2 표가 `lookbackDays`를 "목록형 `[21,63,126,252]`, 허용범위 10~504 간격 1"로, 최소분산 `maxWeight`를 "목록형 `[30,40,50]`, 허용범위 10~100 간격 5"로 적었다. **`AllowedRange`는 목록형(`values`)과 입력형(`min`/`max`/`step`)을 동시에 채우는 것을 생성자로 막으므로**(§4, T014) 그대로는 구현할 수 없고, 실제 등록표는 관측 기간을 목록형으로, 최소분산 상한을 입력형으로 하나씩만 채웠다. 표의 "후보/범위" 열이 `searchValues`와 `allowedRange`를 한 칸에 섞어 적은 것이 원인이므로 두 열로 가르거나 어느 쪽인지 밝혀 적는다. T072가 클래스 이름·경로만 다루므로 이 표기는 그 태스크가 덮지 않는다
