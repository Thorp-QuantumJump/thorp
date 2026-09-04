# Implementation Plan: 전략 정의 카탈로그

**Branch**: `012-strategy-definition-catalog` (spec 디렉터리 기준. 작업 git 브랜치는 팀 컨벤션에 따라
`feat(be)/42-strategy_metadata_schema`) | **Date**: 2026-09-03 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/012-strategy-definition-catalog/spec.md`

## Summary

전략의 이름·설명·진입/청산 조건 서술·파라미터 정의(이름·타입·유효범위·기본값)를 하나의 문서화된 계약으로
확정하고, Spring BE·Python Worker·React FE가 그 계약을 같은 의미로 소비하게 만든다.

기술적 접근은 세 갈래다.

1. **정의는 데이터, 판정은 코드.** 전략 정의는 `backend/src/main/resources/strategies/<strategyId>.json`
   파일이고, 형식은 `docs/specs/strategy-definition/strategy-definition.schema.json`이 규정한다. Backend가 유일한 런타임
   제공자이며 FE는 API로, Worker는 실행 메시지로 필요한 것만 받는다.
2. **표현 수단을 역할별로 쪼갠다.** 개별 파라미터의 타입·범위·step·enum은 JSON Schema가 잡고, 파라미터 간
   제약과 최소 이력 규칙은 각각 닫힌 어휘의 **별도 선언 목록**으로 둔다. 스키마 하나에 전부 밀어 넣으면
   "어떤 파라미터가 왜 잘못됐는지"를 잃는다.
3. **동일성은 공유 테스트 벡터로 증명한다.** BE와 FE가 같은
   `test-vectors/parameter-validation.json`을 읽어 같은 판정(위반 코드·대상·순서)을 내야 통과한다.
   이것이 SC-002·SC-010의 실행 가능한 정의다.

4. **구현 쪽은 처음부터 Nautilus-native.** 전략 구현은 자체 추상화 없이
   `nautilus_trader`의 `Strategy` / `StrategyConfig`를 직접 쓴다. 계약은 엔진을 모른 채로 남고,
   `resolvedParameters` → `ImportableStrategyConfig` → `StrategyFactory.create()` 변환이
   `worker/src/worker/strategies/binding.py` **한 곳**에서만 일어난다.

매매 판단 로직은 이 기능에 포함되지 않는다. 정의는 메타데이터와 검증 계약이고, 매매 판단은 Nautilus
`Strategy` 구현이 소유한다(FR-003). 이번에는 설정 클래스와 클래스 골격까지 만들고 `on_bar` 내부는
후속 기능에서 채운다.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5.4) / **Python 3.14** (Worker, 3.11에서 상향) /
TypeScript 5.8 (React 19, Vite 6)

**Primary Dependencies**: Spring Web·Validation·Jackson, pydantic, axios (기존) +
**`nautilus_trader` 1.231.0** (Worker, 전략 실행 엔진 — 확정) + **`vitest`** (FE devDependency).
JSON Schema 런타임 검증 라이브러리는 도입하지 않는다(research.md R2). 근거는 Complexity Tracking 참조.

**Storage**: 없음. 전략 정의는 classpath 리소스이며 DB 테이블을 만들지 않는다. 이 도메인에는
`@Entity`도 `infrastructure/persistence`도 없다.

**Testing**: JUnit 5 + ArchUnit (BE) / pytest (Worker) / vitest (FE, 신규) / `jsonschema` 기반 CI 스크립트

**의존성 실측 (research.md R12)**: Python 3.14.6 환경에서 `nautilus_trader` 1.231.0과 Worker 기존
의존성 전체가 충돌 없이 해결됨을 확인했다(`confluent-kafka` 2.15.0, `psycopg[binary]` 3.3.5,
`pandas` 3.0.5, `pyarrow` 25.0.1, `msgspec` 0.21.1 포함). `python:3.14-slim` 이미지 존재와
`ruff`의 `target-version = "py314"` 지원도 확인했다.

**Target Platform**: Linux 컨테이너 (EC2, docker compose). 이미지 빌드 컨텍스트가 `./backend`, `./worker`로
분리되어 있다는 점이 정의 파일 위치 결정의 핵심 제약이었다(research.md R1).

**Project Type**: 모노레포 웹 애플리케이션 (frontend + backend + worker)

**Performance Goals**: 카탈로그는 기동 시 1회 로드 후 불변 맵. 값 판정은 파라미터 수에 선형이며 I/O가
없다. spec SC에 지연 목표가 없어 별도 수치 목표를 세우지 않는다.

**Constraints**: API JSON 필드 camelCase(MUST). 도메인 계층은 Spring·JPA·Jackson 참조 금지
(`ArchitectureTest`가 강제). 전략 하나 추가 시 소비 지점 변경 0곳(SC-001).

**Scale/Scope**: 초기 전략 4종, 정의 형식은 20종까지 변경 없이 확장. 전략당 파라미터 2~6개 수준.

## Constitution Check

*GATE: Phase 0 이전 통과 필수, Phase 1 이후 재확인.*

### Phase 0 이전

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. 연산의 독립 실행 경계 | PASS | 이 기능은 계산을 하지 않는다. 카탈로그 조회와 값 판정은 I/O 없는 선형 연산이며 API 요청 처리 안에서 끝난다. 백테스트 연산 경계를 건드리지 않는다 |
| II. 실행의 요청 생명주기 독립성 | PASS | 실행을 시작하지 않는다. 다만 실행 기록이 `strategyId + definitionVersion + resolvedParameters`를 보존하도록 실행 메시지 계약을 확장한다(FR-019d) |
| III. 빅데이터 기술 선도입 금지 / 의존성 정당화 | PASS (조건부) | Spark/Hadoop 무관. 신규 의존성 2건 모두 지금 해결하는 문제가 있다 — `vitest`는 "FE 판정이 BE와 같은지 검증할 수단이 없다", `nautilus_trader`는 "이번에 작성하는 전략 구현 4종과 계약 테스트가 곧 폐기될 자체 추상화에 묶인다"(R8). 후자는 팀이 확정한 아키텍처 결정이며 ADR-0002로 기록한다 |
| IV. 파트 경계 계약 명시 | PASS | 이 기능의 산출물 자체가 계약이다. `contracts/`에 JSON Schema로 문서화하고 필드는 camelCase. 실행 메시지 델타는 생산자·소비자를 같은 MR에서 함께 바꾼다 |
| V. 결과 신뢰성 | PASS | 재현성: 확정 값 집합 + 정의 버전이 실행 기록에 남는다(SC-009). 동일 기준: 최소 이력 산출을 하나의 선언 규칙에 묶는다(SC-010). look-ahead: 유효 범위가 음수 지연 같은 값을 표현 단계에서 배제한다. 비교 가능성: 성과 지표는 전략 정의에 넣지 않는다 |

### Phase 1 설계 이후 재확인

| 항목 | 결과 |
|---|---|
| 도메인 계층 순수성 | 유지. JSON 바인딩 DTO는 `infrastructure/catalog`에 격리했고 도메인은 record와 sealed interface뿐이다 |
| 계층 의존 방향 | 유지. `presentation → application → domain`, `infrastructure → application/domain`. 새 하위 패키지 `domain/policy`·`infrastructure/catalog`는 ArchUnit 패턴(`..domain..`, `..infrastructure..`)에 그대로 걸린다 |
| camelCase | 계약 전 필드 준수. `strategyId` 값만 대문자 스네이크(식별자 값이지 필드명이 아님) |
| `docs/specs/` 문서화 | 구현 첫 작업으로 `contracts/`를 `docs/specs/strategy-definition/`으로 승격 |
| 새 인프라 | 없음. DB 테이블·브로커·외부 서비스 추가 없음 |
| 계약의 엔진 비종속성 (FR-020) | 유지. `contracts/` 스키마 3종 어디에도 Nautilus 개념이 없다. 변환은 `worker/src/worker/strategies/binding.py` 한 곳에 격리했고 Backend·FE는 엔진을 모른다 |
| 문서화되지 않은 규약 도입 | `backend/CLAUDE.md` 계층 표에 `domain/policy`·`infrastructure/catalog` 추가 필요 (작업에 포함) |
| 기존 문서와의 충돌 | **`worker/CLAUDE.md`의 "전략은 `base.Strategy` 상속, `generate_signals`만 구현 / 체결·지표는 `engine.py` 담당" 규칙이 무효가 된다.** Nautilus 기준으로 같은 MR에서 고쳐야 한다 |

**위반 없음.** 정당화가 필요한 항목은 Complexity Tracking 3건이며 모두 "지금 해결하는 문제"를 명시했다.

## Project Structure

### Documentation (this feature)

```text
specs/012-strategy-definition-catalog/
├── plan.md                                    # 이 파일
├── spec.md
├── research.md                                # Phase 0 — 설계 결정 10건
├── data-model.md                              # Phase 1 — 엔티티·판정 순서·파트별 타입
├── quickstart.md                              # Phase 1 — 검증 절차
├── checklists/requirements.md
├── contracts/                                 # Phase 1 — 계약 정본 (docs/specs/로 승격 예정)
│   ├── README.md
│   ├── strategy-definition.schema.json
│   ├── strategy-catalog-api.schema.json
│   ├── strategy-execution-binding.schema.json
│   ├── examples/{SMA_CROSSOVER,RSI_REVERSION,MACD_SIGNAL,MOMENTUM}.json
│   └── test-vectors/parameter-validation.json
└── tasks.md                                   # /speckit-tasks 산출 (아직 없음)
```

### Source Code (repository root)

```text
docs/specs/strategy-definition/                # ★신규 — 계약의 최종 거처 (헌장 IV)
├── strategy-definition.schema.json
├── strategy-catalog-api.schema.json
├── strategy-execution-binding.schema.json
├── test-vectors/parameter-validation.json
└── scripts/validate_definitions.py

backend/
├── src/main/resources/strategies/             # ★신규 — 전략 정의 SSOT
│   ├── SMA_CROSSOVER.json
│   ├── RSI_REVERSION.json
│   ├── MACD_SIGNAL.json
│   └── MOMENTUM.json
├── src/main/java/com/ssafy/thorp/strategy/    # ★신규 — 현재 빈 디렉터리
│   ├── presentation/{controller,request,response}/
│   ├── application/{service,command,result,repository}/
│   ├── domain/{model,vo,policy,exception}/    # policy = 순수 판정·계산
│   └── infrastructure/catalog/                # 정의 로딩 + Jackson DTO
└── src/test/java/com/ssafy/thorp/strategy/    # ★신규 — 벡터 테스트 포함

worker/                                        # Python 3.11 → 3.14, nautilus_trader 추가
├── pyproject.toml                             # 수정 — requires-python, ruff target-version
├── requirements.txt                           # 수정 — nautilus_trader 추가
├── src/worker/models.py                       # 수정 — BacktestJob.strategy 를 객체로
├── src/worker/strategies/
│   ├── base.py                                # ✂삭제 — 자체 Strategy ABC 폐기
│   ├── registry.py                            # ★신규 — strategyId → (strategy_path, config_path)
│   ├── binding.py                             # ★신규 — resolvedParameters → ImportableStrategyConfig
│   ├── naming.py                              # ★신규 — camelCase↔snake_case + 예약 필드 목록
│   ├── momentum.py                            # 수정 — Nautilus Strategy/StrategyConfig 로 재작성
│   └── {sma_crossover,rsi_reversion,macd_signal}.py   # ★신규 — Config + Strategy 골격
├── src/worker/engine.py                       # 유지(비활성) — Nautilus 로 대체 예정, 배선은 후속 기능
└── tests/test_strategy_definition_contract.py  # ★신규 — 정의 ↔ StrategyConfig 필드 일치 검사

infra/docker/worker.Dockerfile                 # 수정 — python:3.11-slim → python:3.14-slim
Jenkinsfile                                    # 수정 — Worker 스테이지를 uv venv --python 3.14 로
                                               #        Frontend 스테이지에 npm run test 추가
docs/adr/0002-nautilus-trader-as-strategy-engine.md   # ★신규 — 엔진 확정 기록
worker/CLAUDE.md                               # 수정 — base.Strategy/generate_signals 규칙 교체
backend/CLAUDE.md                              # 수정 — domain/policy·infrastructure/catalog 추가

frontend/
├── src/types/strategy.ts                      # ★신규
├── src/api/strategies.ts                      # ★신규
├── src/lib/strategyValidation.ts              # ★신규 — 판정 미러
├── src/lib/strategyValidation.test.ts         # ★신규 — 공유 벡터
└── src/components/backtest/ParameterForm.tsx  # ★신규 — 정의 기반 동적 폼
```

**Structure Decision**: 기존 모노레포의 파트 경계를 그대로 쓴다. Backend는 이미 존재하는(비어 있는)
`com.ssafy.thorp.strategy` 패키지에 ADR-0001의 4계층을 채우고, Worker와 Frontend는 각 `CLAUDE.md`가
규정한 폴더 규칙(`strategies/`, `src/types/`·`src/api/`·`src/lib/`)을 따른다. 새 최상위 디렉터리는
`docs/specs/strategy-definition/`과 `backend/src/main/resources/strategies/` 둘뿐이다.

---

## 설계 산출물 요약

세부는 각 문서에 있고 여기서는 결정만 짚는다.

### 1. 데이터 모델 → [data-model.md](./data-model.md)

`StrategyDefinition`은 `schemaVersion` · `strategyId` · `definitionVersion` · `displayName` ·
`description` · `entryCondition` · `exitCondition` · `executionScopes` · `parameters` ·
`parameterConstraints` · `minimumHistoryRequirement` · `changeHistory` · `deprecated`로 구성된다.
요청하신 "호환성 정보"는 단일 필드가 아니라 `changeHistory` 배열로 넣었다 — 등급이 최신 변경 하나만
있으면 "v2에 저장한 전략이 v5에서도 유효한가"를 답할 수 없기 때문이다(R6).

`ParameterDefinition`은 `name` · `displayName` · `description` · `type` · `required` · `defaultValue` ·
`unit` · `executionScopes`와, 타입에 따라 `numericRange`(INTEGER/DECIMAL) 또는 `enumValues`(ENUM)를 갖는다.
경계 포함 여부는 `minInclusive`/`maxInclusive`로 분리했고 한쪽만 지정한 열린 범위를 허용한다.

### 2. 계약 → [contracts/](./contracts/)

JSON Schema 3종 + 정의 예시 4종 + 공유 테스트 벡터. **설계 시점에 실제로 검증했다**: 정의 7종
(예시 4 + 벡터 내 합성 3)이 스키마를 통과하고, 스키마가 잡아야 할 형태 10가지(`required`+`defaultValue`,
`ENUM`+`numericRange`, `min` 없는 `step`, 배타 하한 + `step`, `FIXED`+`parameters`, `PARAMETER`에 대상 2개,
타입에 안 맞는 `defaultValue`, 버전 1인데 `INITIAL`이 아닌 경우, `unit`이 `bars`가 아닌 경우, 제약 우변에
상수)를 전부 거부한다.

### 3~5. 파트별 타입 → [data-model.md §8](./data-model.md)

- **Spring**: `domain/model`의 record + `domain/vo`의 enum·`sealed interface ParameterValue`
  (`IntegerValue`/`DecimalValue`/`BooleanValue`/`EnumValue`). 수치는 전부 `BigDecimal`.
- **Worker**: 엔진 비종속 경계는 `StrategyBinding` pydantic 모델 하나뿐이고, 그 안쪽은 전부
  Nautilus-native다. 전략은 `nautilus_trader.trading.strategy.Strategy`를 상속하고 파라미터는
  `StrategyConfig` 서브클래스로 선언한다. 자체 `BaseStrategy`는 만들지 않으며 기존 `base.py`는 삭제한다.
  `resolvedParameters` → `ImportableStrategyConfig.config` → `StrategyFactory.create()`가 실행 경로다.
  Worker는 정의 파일을 읽지 않는다.
  - **이름 매핑**: camelCase → snake_case 기계 변환. 전략별 매핑 표 없음.
  - **`DECIMAL` 매핑**: 값을 **문자열로 직렬화**해 넘기고 설정 필드는 `Decimal`로 선언한다.
    `StrategyFactory.create`가 `msgspec.json.encode` → `config_cls.parse`를 타므로 float을 넘기면
    이진 오차가 그대로 들어온다 — 실측에서 `0.1+0.2`가 `Decimal('0.30000000000000004')`이 됐다.
  - **예약 필드 충돌 금지(D14)**: 파라미터 이름의 snake_case 형태가 `StrategyConfig`의 예약 필드
    16개와 겹치면 정의를 형식 위반으로 거부한다. 겹치면 사용자 값이 엔진 동작 설정을 덮어쓴다.
- **FE**: `src/types/strategy.ts`의 미러 타입. 코드 생성 도구를 쓰지 않고 수기 작성 후 공유 벡터로 검증한다.

### 6. 판정 흐름 → [data-model.md §7](./data-model.md)

`S0 전략 조회 → S1 미정의 파라미터 → S2 필수 누락 → S3 타입 → S4 범위·step·enum → S5 기본값 주입 →
S6 상호 제약 → S7 이력 산출`. 중단 없이 전부 수행하고 위반을 누적한다. 타입이 깨진 값은 이후 단계에서
제외하되 범위를 위반한 값은 제약 판정에 그대로 쓴다 — 그래야 사용자가 한 번에 전부 본다. 위반 배열의
순서까지 세 파트가 같아야 하며 테스트 벡터가 순서를 비교한다.

### 7. 등록·조회 구조 → [data-model.md §9](./data-model.md)

기동 시 `classpath:strategies/*.json` 스캔 → Jackson 역직렬화 → 도메인 매핑 → D1~D18 형식 검사 →
통과분만 불변 카탈로그에 등재. 실패분은 WARN 로그 후 제외하며 나머지 전략 제공에 영향을 주지 않는다.
정의가 0건이어도 앱은 기동하고 빈 목록을 반환한다. 핫 리로드는 두지 않는다.

**API**

| 메서드 | 경로 | 용도 |
|---|---|---|
| `GET` | `/strategies` | 목록 (요약) |
| `GET` | `/strategies/{strategyId}` | 정의 전체. 없으면 404 `RESOURCE_NOT_FOUND` |
| `POST` | `/strategies/{strategyId}/parameter-validation` | 값 판정. 판정 실패도 HTTP 200이며 결과는 본문에 담긴다 |

응답은 모두 기존 `ApiResponse` 봉투(`{success, data, error}`)를 쓴다.

### 8. 새 전략 추가 시 변경 파일 범위

**추가 시 건드리는 파일 (3개)**

| 파일 | 내용 |
|---|---|
| `backend/src/main/resources/strategies/<STRATEGY_ID>.json` | 정의 |
| `worker/src/worker/strategies/<name>.py` | `XxxConfig(StrategyConfig)` + `Xxx(Strategy)` |
| `worker/src/worker/strategies/registry.py` | `STRATEGY_PATHS`에 한 줄 |

설정 클래스의 필드는 정의의 파라미터와 이름(snake_case)·타입이 대응해야 하며, 그 대응은 Worker 계약
테스트가 검사한다. **구현 쪽에 파라미터 이름 목록을 따로 적어두지 않는다** — 진실의 원천은 설정 클래스
하나이므로 어긋날 여지가 그만큼 줄어든다.

**절대 건드리지 않아야 하는 곳** — 여기를 고쳐야 했다면 확장 설계가 실패한 것이다.

Backend: `StrategyController` / `StrategyCatalogService` / `ParameterValidationService` /
`ParameterSetValidator` / `HistoryRequirementCalculator` / `StrategyDefinitionFormatValidator` /
`domain/model`·`domain/vo` 전부
Worker: `binding.py` / `naming.py` / `models.py`
Frontend: `types/strategy.ts` / `api/strategies.ts` / `lib/strategyValidation.ts` / `ParameterForm.tsx`
계약: 스키마 3종

**형식 어휘를 넓혀야 하는 경우** (새 파라미터 타입, 새 비교 연산자, 새 이력 규칙)에만 스키마·enum·판정기를
함께 바꾼다. 전략 하나를 위한 예외 경로는 만들지 않는다. quickstart.md §5가 이 성질을 실제로 시험한다.

### 9. spec·헌장 적합성 확인

| spec 요구 | 설계상 위치 |
|---|---|
| FR-001/002 목록·설명 | `GET /strategies`, `strategySummary` |
| FR-003/003a 조건 서술 | `entryCondition`/`exitCondition` 문자열. 판단 로직은 Worker 구현 |
| FR-004~008 파라미터 정의 | `ParameterDefinition` + `numericRange`/`enumValues` |
| FR-009/009a/009b 상호 제약 분리 | 최상위 `parameterConstraints`, 닫힌 연산자 6종, 정의만으로 판정 |
| FR-010~010c 최소 이력 | `minimumHistoryRequirement` 4규칙 + `offset`, 대상은 INTEGER 한정 |
| FR-011~014 판정과 확정 값 | 판정 순서 S0~S7, 위반 전량 보고, 기본값 주입 |
| FR-015/016 계약·확장 | `contracts/` + 새 전략 3파일 규칙 |
| FR-017/018 형식 위반·유일성 | D1~D18, 위반분만 제외 |
| FR-019~019d 버전·호환성 | `definitionVersion` + `changeHistory` + 실행 바인딩 |
| FR-020/021 실행 목적 | `executionScopes`(전략·파라미터 양쪽) + scope 필터링 규칙 |
| FR-022 사용자는 값만 변경 | 정의는 리소스 파일이며 API에 쓰기 경로가 없다 |

| 헌장 | 확인 |
|---|---|
| I·II | 계산·실행을 하지 않음. 실행 기록 추적성은 실행 바인딩으로 강화. Nautilus 실행은 Worker 경계 안에 있어 원칙 I과 어긋나지 않는다 |
| III | 신규 의존성 2건(`vitest`, `nautilus_trader`) 모두 지금 해결하는 문제를 명시. Spark/Hadoop 무관 |
| IV | 계약을 `docs/specs/`로 승격, camelCase, 실행 메시지 델타는 동시 변경. 엔진 개념이 계약에 새지 않음 |
| V | 재현성(SC-009)·동일 기준(SC-010)·look-ahead 차단 근거 명시. `DECIMAL` 문자열 전달로 실행 단계의 부동소수 오차 유입 차단 |

미해결로 남기는 것: **`bars` → 거래일 환산**은 F2와 데이터 제공 범위의 몫이며 이 계획에 없다(spec 합의).

---

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| FE 신규 의존성 `vitest` | SC-002·SC-010이 "FE와 BE의 판정이 같다"를 요구하는데 `frontend/package.json`에 테스트 러너가 없어 검증할 방법이 없다 | *FE에서 판정하지 않고 매 입력마다 서버 왕복*: 입력 폼 UX가 무너지고, 서버 판정은 어차피 제출 시 다시 수행되므로 왕복이 이득을 주지 않는다 |
| 문서화되지 않은 하위 패키지 2개 (`domain/policy`, `infrastructure/catalog`) | 판정 로직은 순수 도메인이라 `domain`에 있어야 이식 단위가 명확하고, Jackson DTO는 ArchUnit 규칙상 `infrastructure`에만 둘 수 있다 | *`application/service`에 판정 배치*: ArchUnit은 통과하나 FE로 그대로 옮겨 쓰는 순수 규칙이 유스케이스 계층에 섞인다. *`infrastructure/persistence` 재사용*: 영속화가 아니라 이름이 거짓이 된다 |
| F1 실행 메시지 계약 변경 (`BacktestJob.strategy`: `str` → 객체) | FR-019d와 SC-009가 "실행이 사용한 전략·버전·확정 파라미터를 사후에 특정"을 요구한다. 문자열 하나로는 불가능하다 | *별도 조회 API로 실행 시점 정의를 되찾기*: 정의가 그 사이 개정되면 당시 값을 복원할 수 없어 재현성(헌장 V)이 깨진다 |
| Worker 신규 의존성 `nautilus_trader` 1.231.0 | 이 기능에서 전략 구현 4종과 "정의 ↔ 구현" 계약 테스트를 함께 작성한다. 자체 추상화를 먼저 두면 그 4종과 테스트를 두 번 쓴다. 엔진은 팀이 확정했다 | *자체 ABC 후 이전*: 재작업 확정. *자체 ABC + Nautilus 어댑터 병행*: 추상화가 이중이 되고 전략 작성자가 매번 어느 쪽에 쓸지 판단해야 한다 |
| Worker 런타임 Python 3.11 → 3.14 | 최신 Nautilus를 쓰기로 한 팀 결정. 3.14.6에서 Worker 전체 의존성이 충돌 없이 해결됨을 실측 확인했다 | *3.11 유지*: 동작할 가능성이 높지만 최신 엔진을 쓰기로 한 결정과 어긋나고, 낮춰야 할 근거가 실측에서 나오지 않았다 |

다섯 항목 모두 헌장 위반은 아니며, 원칙 III·IV가 요구하는 "이유 명시"를 충족하기 위해 기록한다.

## 다음 단계에서 결정할 것 (이 계획의 범위 밖)

- **Nautilus 백테스트 실행 배선**: `BacktestNode`/`BacktestRunConfig`/`BacktestDataConfig`로 실제
  백테스트를 돌리는 부분, KRX 데이터를 Nautilus 데이터 카탈로그로 적재하는 부분. 이번에는 전략 구성까지만
  만든다. 이 배선이 들어올 때 `worker/src/worker/engine.py`(pandas 스캐폴딩)를 제거한다.
- **`TradingNode` 라이브 실행**: `executionScope: LIVE`가 실제로 무엇을 하는지는 별도 기능.
- **각 전략의 매매 로직**: 이번 기능은 설정 클래스와 `Strategy` 골격까지다. `on_bar` 안의 판단과 주문은
  별도 기능에서 채운다.
- **F2 연계**: 조건 설정 화면이 `parameter-validation`과 `requiredHistoryBars`를 어떻게 소비할지,
  `instrumentId`를 어떻게 전달할지는 F2의 plan에서 다룬다.
