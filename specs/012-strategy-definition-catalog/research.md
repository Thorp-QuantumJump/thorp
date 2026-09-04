# Phase 0 Research: 전략 정의 카탈로그

**Feature**: `specs/012-strategy-definition-catalog` | **Date**: 2026-09-03

spec.md의 `## Clarifications`에서 확정된 5건은 여기서 다시 논의하지 않는다. 이 문서는 그 결정을
구현 가능한 형태로 옮기기 위해 plan 단계에서 추가로 정해야 했던 선택을 기록한다.

---

## R1. 정의 인스턴스의 단일 출처와 배포 경로

**Decision**: 전략 정의 JSON 파일은 `backend/src/main/resources/strategies/<strategyId>.json`에 두고,
Backend를 카탈로그의 유일한 런타임 제공자로 삼는다. FE는 API로 조회하고, Worker는 정의 파일을 읽지
않는다(R7 참조).

**Rationale**: `docker-compose.dev.yml:65,88`에서 확인된 것처럼 이미지 빌드 컨텍스트가 `./backend`와
`./worker`로 각각 분리되어 있다. 저장소 루트에 공유 디렉터리를 두면 두 이미지 어느 쪽도 그 경로를 볼 수
없어 빌드가 깨진다. 정의를 소비하는 런타임을 하나로 줄이면 이 문제가 사라지고, 카탈로그 제공(FR-001)과
값 판정(FR-011)이 같은 프로세스 안에서 같은 객체를 보게 되어 SC-002의 불일치 원인 하나가 구조적으로
제거된다.

**Alternatives considered**:
- *저장소 루트 `contracts/strategies/` 공유 디렉터리*: 빌드 컨텍스트 밖이라 Docker 빌드 실패. 컨텍스트를
  루트로 올리면 두 이미지 모두 전체 저장소를 복사하게 되어 빌드가 느려지고 불필요한 파일이 이미지에 들어간다.
- *DB 테이블*: 정의는 배포 단위로 바뀌는 코드성 자산인데 DB에 두면 마이그레이션과 배포 순서가 얽히고,
  "정의 변경은 MR로 리뷰한다"는 흐름이 깨진다. 정의를 읽기 위해 DB가 살아 있어야 하는 의존도 생긴다.
- *Worker가 소유하고 Backend가 조회*: 카탈로그 조회 API가 Worker 가용성에 묶인다. 원칙 I·II가 분리하려던
  요청 처리와 연산 경계를 오히려 다시 붙인다.

---

## R2. JSON Schema의 역할 범위

**Decision**: `contracts/*.schema.json`은 **계약 문서 + CI 검증 도구 + 타입 작성 기준**으로만 쓴다.
Backend 런타임은 JSON Schema 라이브러리를 쓰지 않고, Jackson으로 Java record에 역직렬화한 뒤
`StrategyDefinitionFormatValidator`(순수 도메인 코드)가 형식을 판정한다.

**Rationale**: FR-006(기본값이 자기 유효 범위 안), FR-010c(최소 이력 규칙의 대상 파라미터가 정의에 존재하고
INTEGER), FR-018(식별자 유일성), 제약 대상 파라미터가 항상 값을 갖는지 같은 규칙은 JSON Schema로 표현할 수
없거나 표현해도 위반 메시지가 쓸모없다. 사용자가 명시적으로 요구한 "FR-009·FR-010을 JSON Schema 하나로
표현하게 만들지 말 것"과도 같은 결론이다. 런타임 스키마 검증기를 추가하면 원칙 III("새 의존성은 지금
해결하는 문제를 명시")를 만족시킬 근거가 없다 — 형식 판정은 어차피 자체 검증기가 해야 한다.

**Alternatives considered**:
- *런타임 JSON Schema 검증 (networknt/json-schema-validator)*: 의존성 하나를 늘리고도 위 규칙들은 여전히
  자체 코드로 검사해야 한다. 검사 위치가 둘로 갈라져 위반 보고 형식도 둘이 된다.
- *스키마 없이 record만*: FE·Worker가 참조할 기계 판독 가능한 계약이 사라진다. 원칙 IV 위반.

---

## R3. 개별 검증과 상호 제약의 표현 분리

**Decision**: 개별 파라미터의 타입·범위·step·enum은 `ParameterDefinition` 안의 `numericRange` /
`enumValues`가, 파라미터 간 관계는 최상위 `parameterConstraints` 배열이 담는다. 제약의 양변은 **모두
파라미터 이름**이어야 하며 상수를 허용하지 않는다. 연산자는 `LT` `LTE` `GT` `GTE` `EQ` `NE` 6개로 닫는다.

**Rationale**: FR-009b가 요구하는 분리다. 양변을 파라미터 이름으로 제한하면 "상수와의 비교"는 자연히
`numericRange`로 밀려나 표현 경로가 하나로 정해진다. 상수를 허용하는 순간 같은 규칙을 두 군데로 쓸 수 있게
되어 정의 작성자마다 다른 곳에 쓰게 되고, FE 폼이 어느 쪽을 근거로 입력 힌트를 만들지 모호해진다.

**Additional rule**: 제약이 참조하는 파라미터는 `required: true`이거나 `defaultValue`를 가져야 한다.
그렇지 않으면 확정 값 집합에 값이 없어 제약을 판정할 수 없으므로 형식 위반으로 취급한다.

**Alternatives considered**:
- *JSON Schema `allOf`/`if-then`으로 상호 제약 표현*: 표현은 가능하나 "어떤 제약이 깨졌는지 특정"(spec US2
  시나리오 7)이 불가능하다. 스키마 검증 실패는 경로만 알려줄 뿐 사람이 읽을 이유를 주지 못한다.
- *제약 우변에 상수 허용*: 위 Rationale 참조.

---

## R4. 최소 이력 규칙의 계산 규약

**Decision**: `minimumHistoryRequirement`는 `{ rule, value?, parameters?, offset, unit: "bars" }` 형태이며
산출식은 다음과 같다.

| rule | 필수 필드 | 산출식 |
|---|---|---|
| `FIXED` | `value` | `value + offset` |
| `PARAMETER` | `parameters` (정확히 1개) | `p1 + offset` |
| `MAX_PARAMETERS` | `parameters` (1개 이상) | `max(p1..pn) + offset` |
| `SUM_PARAMETERS` | `parameters` (1개 이상) | `p1 + ... + pn + offset` |

`offset`은 생략 시 0이며 0 이상의 정수다. 대상 파라미터는 **`INTEGER` 타입만** 허용한다.

**Rationale**: spec FR-010c는 "수치형이 아니면 형식 위반"이라고만 규정하지만, `DECIMAL`을 허용하면
`2.5 bars`를 올릴지 내릴지를 파트마다 다르게 구현할 여지가 생기고 그것이 곧 SC-010 위반이다. bar 개수는
본질적으로 정수이므로 `INTEGER`로 좁히는 편이 규칙을 지키기 쉽고, spec의 요구(수치형 ⊇ INTEGER)를 어기지
않는다. `MAX_PARAMETERS`에 대상이 하나만 있는 경우는 `PARAMETER`와 같은 값을 내므로 유효하게 취급한다
(spec Edge Cases와 일치). 대상이 0개면 형식 위반이다.

**Alternatives considered**:
- *DECIMAL 허용 + 올림 규약 명시*: 규약을 세 파트가 각자 구현해야 하고 이득이 없다.
- *자유 수식(expression)*: spec에서 명시적으로 배제됐다.

---

## R5. `DECIMAL`의 step과 부동소수 처리

**Decision**: `min`·`max`·`step`·`defaultValue`·입력 값은 모두 **십진 기반 타입**으로 다룬다
(Java `BigDecimal`, Python `decimal.Decimal`, TypeScript는 스케일 정수 변환). JSON 상에서는 숫자 리터럴로
표기한다. `step`을 지정하려면 `min`이 있어야 하고 `minInclusive: true`여야 하며, 판정식은
`(value - min) % step == 0`이다.

**Rationale**: `0.1 + 0.2 != 0.3`을 세 언어에서 각자 만나면 SC-002가 바로 깨진다. step의 기준점을 `min`으로
고정하지 않으면 `0`을 기준으로 하는 구현과 `min`을 기준으로 하는 구현이 서로 다른 판정을 낸다. 기준점
모호성을 규칙으로 제거하는 편이 문서로 경고하는 것보다 싸다.

**Alternatives considered**:
- *`step`을 문자열로 표기*: JSON 숫자 표현과 어긋나 FE 입력 컴포넌트가 매번 파싱해야 한다.
- *배정밀도 부동소수 + 허용 오차(epsilon)*: 허용 오차 값 자체가 세 파트에서 합의해야 할 또 하나의 상수가 된다.

---

## R6. `definitionVersion`과 변경 이력

**Decision**: `definitionVersion`은 전략별 단조 증가 정수다. 정의 파일은 `changeHistory` 배열(최신 우선)을
가지며 각 항목은 `{ definitionVersion, changeType, changeSummary, changedAt }`이다. `changeType`은
`INITIAL` | `BACKWARD_COMPATIBLE` | `INCOMPATIBLE`. `changeHistory[0].definitionVersion`은 최상위
`definitionVersion`과 같아야 한다(형식 위반 검사 대상).

**Rationale**: FR-019b는 변경마다 호환성 등급을 요구한다. 등급을 최신 변경 하나만 들고 있으면 "v2에 저장된
전략이 현재 v5에서도 유효한가"를 답할 수 없다 — v3에서 비호환 변경이 있었는지 알 방법이 없기 때문이다.
F9(전략 저장·공유)가 저장본에 경고를 띄우려면 구간 조회가 필요하므로 배열이 최소 형태다. 값 자체의 유효
여부는 FR-019c대로 현재 정의에 재판정해서 얻고, `changeHistory`는 "왜 깨졌는지"를 사람에게 설명하는 데만
쓴다. 두 용도를 섞지 않는다.

**Semantic versioning을 쓰지 않는 이유**: MAJOR/MINOR/PATCH 세 자리 중 PATCH는 spec FR-019a가 "문구 수정은
버전을 올리지 않는다"고 정해 쓸 일이 없고, MAJOR/MINOR 구분은 `changeType`이 이미 담는다. 정수 하나로 충분하다.

---

## R7. Worker의 정의 소비 방식과 F1 계약 델타

**Decision**: Worker는 전략 정의 파일을 읽지 않는다. 백테스트 Job 메시지에 실린
`strategyId` + `definitionVersion` + `resolvedParameters` + `requiredHistoryBars`만 소비한다. 구현과 정의의
일치는 런타임이 아니라 **CI 계약 테스트**로 보증한다.

**Rationale**: R1의 빌드 컨텍스트 제약 때문에 Worker 이미지에 정의 파일을 넣으려면 파일을 두 곳에 복사해야
하고, 그 순간 두 사본이 어긋날 수 있다. 값 판정은 Backend가 요청 접수 시점에 이미 끝냈고(FR-011~FR-014),
Worker가 필요로 하는 것은 판정 결과인 확정 값과 이력 요구량뿐이다. Worker에서 다시 판정하면 같은 규칙의
세 번째 구현이 생겨 SC-002의 위험만 늘어난다.

**계약 델타 (원칙 IV — 함께 바꿔야 함)**: 현재 `worker/src/worker/models.py:13`의 `BacktestJob.strategy`는
`str` 한 필드다. 이 기능은 그것을 위 네 필드를 가진 객체로 바꾼다. `backend`의
`BacktestJobRequest`/발행 메시지와 `worker`의 `BacktestJob`을 같은 MR에서 함께 바꾸지 않으면 배포가 깨진다.
tasks 단계에서 이 둘을 하나의 작업 묶음으로 처리해야 한다.

**CI 계약 테스트**: `worker/tests/test_strategy_definition_contract.py`가
`../backend/src/main/resources/strategies/*.json`을 읽어 다음을 검사한다.

- (a) 모든 정의의 `strategyId`가 `registry.py`에 등록되어 있다
- (b) 등록된 모든 항목에 대응하는 정의가 있다
- (c) 정의의 파라미터 이름을 snake_case로 바꾼 집합이, `config_path`가 가리키는 `StrategyConfig`
  서브클래스의 **자체 선언 필드 집합**(예약 필드 16개 제외)과 정확히 같다
- (d) 각 파라미터의 `type`이 설정 클래스의 필드 타입 애노테이션과 대응한다
  (`INTEGER`→`int`, `DECIMAL`→`Decimal`, `BOOLEAN`→`bool`, `ENUM`→`str`/`Literal`)

(c)·(d)는 설정 클래스를 `__struct_fields__`/애노테이션으로 들여다봐서 얻는다. **구현 쪽에 파라미터
이름을 다시 적어두는 선언이 필요 없다** — 진실의 원천이 설정 클래스 하나뿐이라 어긋날 여지가 그만큼
줄어든다.

경로가 없으면 skip한다(Worker 이미지 안에는 backend 디렉터리가 없다). Jenkinsfile의 Worker 스테이지는
`dir('worker')` 안에서 실행되므로 모노레포 체크아웃에서는 상대 경로가 성립한다.

---

## R8. NautilusTrader를 전략 구현의 기준 구조로 확정

**Decision**: NautilusTrader를 이 프로젝트의 전략 실행 엔진으로 확정한다(팀 결정). 자체 `BaseStrategy`
추상 클래스를 만들지 않는다. 모든 전략 구현은 `nautilus_trader.trading.strategy.Strategy`를 상속하고,
파라미터는 `nautilus_trader.config.StrategyConfig` 서브클래스로 선언한다. 기존
`worker/src/worker/strategies/base.py`의 자체 `Strategy` ABC는 삭제한다.

**Rationale**: 지금 결정해야 하는 이유가 있다 — 이 기능에서 전략 정의 4종과 함께 "정의 ↔ 구현" 계약
테스트를 작성한다. 자체 인터페이스를 먼저 세우면 4종 구현과 그 테스트가 곧 폐기될 추상화에 묶여 두 번
쓰게 된다. 원칙 III이 새 의존성에 요구하는 "지금 해결하는 문제"는 이 재작업의 회피이며, 미래 대비가
아니다.

**엔진 비종속성은 계약 쪽에서 지킨다**: `contracts/`의 스키마 어디에도 Nautilus 개념이 없다.
`strategy-execution-binding.schema.json`은 `strategyId` · `definitionVersion` · `resolvedParameters` ·
`requiredHistoryBars` · `executionScope`만 담고, Nautilus로의 변환은 Worker 내부의
`strategies/binding.py` 한 곳에서만 일어난다. Backend와 FE는 Nautilus를 모른다. 엔진을 바꾸더라도
바뀌는 범위는 `worker/src/worker/strategies/` 안이다.

**실측으로 확인한 API (`nautilus_trader` 1.231.0, Python 3.14.6에 설치해 확인)**

| 요소 | 확인 결과 |
|---|---|
| `StrategyConfig` | `msgspec.Struct` 기반(`NautilusConfig`). 예약 필드 16개를 이미 갖고 있고 전부 기본값이 있어 서브클래스는 자기 필드만 선언하면 된다 |
| `ImportableStrategyConfig` | 필드는 `strategy_path`, `config_path`, `config: dict[str, Any]` 셋뿐 |
| `StrategyFactory.create` | `nautilus_trader.trading.config`에 있고 내부 동작은 `resolve_path(strategy_path)` → `resolve_config_path(config_path)` → `msgspec.json.encode(config)` → `config_cls.parse(json)` → `strategy_cls(config=config)` |
| `Strategy` 핸들러 | `on_start` · `on_stop` · `on_bar` · `on_quote_tick` · `on_trade_tick` · `on_order_filled` 등 |
| 백테스트/라이브 설정 | `BacktestRunConfig` · `BacktestEngineConfig` · `BacktestDataConfig` · `BacktestVenueConfig` / `TradingNodeConfig` |

`ImportableStrategyConfig` + `StrategyFactory.create`가 **정확히 우리가 필요한 매핑 지점**이다.
`resolvedParameters`를 `config` dict에 넣으면 Nautilus가 알아서 설정 객체를 만든다. 별도 팩토리를 손으로
쓸 필요가 없다.

**이번 범위**: `nautilus_trader` 의존성 추가, `StrategyConfig` 기반 전략 정의 4종, `resolvedParameters`
→ `ImportableStrategyConfig` 매핑, 정의 ↔ 설정 클래스 계약 테스트.

**범위 밖**: `BacktestNode`/`BacktestRunConfig`로 실제 백테스트를 돌리는 배선, `TradingNode` 라이브 실행,
KRX 데이터를 Nautilus 데이터 카탈로그로 적재, 각 전략의 `on_bar` 매매 로직. 이번에는 설정 클래스와
클래스 골격까지만 만든다.

**Alternatives considered**:
- *자체 ABC를 먼저 만들고 나중에 Nautilus로 이전*: 4종 구현과 계약 테스트를 두 번 쓰게 된다. 확정된
  엔진이 있는 상황에서 중간 추상화를 두는 이득이 없다.
- *자체 ABC와 Nautilus 어댑터를 함께 두기*: 추상화가 이중이 되고, 전략 작성자가 어느 쪽에 코드를 쓸지
  매번 판단해야 한다.

---

## R11. `resolvedParameters` → `StrategyConfig` 매핑 규칙

**Decision**: Worker의 `strategies/binding.py`가 다음 규칙으로 변환한다.

| 항목 | 규칙 |
|---|---|
| 이름 | camelCase → snake_case **기계 변환**. 전략별 매핑 표를 두지 않는다 (`shortPeriod` → `short_period`) |
| `INTEGER` | JSON number(정수) 그대로 |
| `DECIMAL` | **문자열로 직렬화**해서 넘기고 설정 클래스는 `Decimal`로 선언 |
| `BOOLEAN` | JSON boolean 그대로 |
| `ENUM` | 문자열 그대로. 설정 클래스는 `str` 또는 `Literal[...]` |

**`DECIMAL`을 문자열로 넘기는 근거 (실측)**: `StrategyFactory.create`가 `msgspec.json.encode` →
`config_cls.parse`를 타므로 값이 JSON을 한 번 왕복한다. 이 경로에 Python float을 넣으면 이진 오차가
`Decimal`로 그대로 들어온다.

```
threshold = 0.1 + 0.2  (float) → Decimal('0.30000000000000004')
threshold = "0.3"      (str)   → Decimal('0.3')
```

R5에서 세 파트에 십진 연산을 요구한 이유가 이 지점에서 실제로 드러난다. 문자열 직렬화는 이 문제를
경로에서 없앤다.

**예약 필드 충돌 금지 (형식 검증 규칙 D14)**: 파라미터 이름의 snake_case 형태가 `StrategyConfig`의 예약
필드 16개(`strategy_id`, `order_id_tag`, `use_uuid_client_order_ids`,
`use_hyphens_in_client_order_ids`, `oms_type`, `external_order_claims`, `manage_contingent_orders`,
`manage_gtd_expiry`, `manage_stop`, `market_exit_interval_ms`, `market_exit_max_attempts`,
`market_exit_time_in_force`, `market_exit_reduce_only`, `log_events`, `log_commands`,
`log_rejected_due_post_only_as_warning`)와 겹치면 정의를 형식 위반으로 거부한다. 겹치면 사용자 값이
Nautilus 동작 설정을 덮어쓰게 된다.

**Nautilus가 제공하는 2차 방어선 (실측)**: 설정 클래스에 없는 키를 넣으면
`ValidationError: Object contains unknown field ...`로, `int` 필드에 소수를 넣으면
`Expected int, got float`으로 거부된다. Backend 판정을 통과한 값이 여기서 걸리면 그것은 정의와 설정
클래스가 어긋났다는 신호이고, CI 계약 테스트(R7)가 그 어긋남을 배포 전에 잡는다.

**Alternatives considered**:
- *전략별 명시적 매핑 함수*: 새 전략을 추가할 때 손댈 파일이 늘어나 SC-001이 깨진다.
- *정의 JSON에 `configField` 필드를 추가해 이름을 명시*: 계약에 엔진 개념이 새는 첫걸음이 된다.
  기계 변환으로 충분하다.

---

## R12. 런타임 Python 버전과 의존성 (실측)

**Decision**: Worker의 Python을 **3.14**로 올린다. `nautilus_trader` 1.231.0을 사용한다.

**실측 결과**: Python 3.14.6 환경에서 Worker의 기존 의존성 전체 + `nautilus_trader`가 충돌 없이
해결된다.

```
nautilus-trader 1.231.0, msgspec 0.21.1, pyarrow 25.0.1, pandas 3.0.5, numpy 2.5.2,
confluent-kafka 2.15.0, psycopg[binary] 3.3.5, SQLAlchemy 2.0.52,
pydantic 2.13.5, pydantic-settings 2.15.0, python-dotenv 1.2.3, pytest 9.1.1, ruff 0.16.5
```

- `python:3.14-slim` 베이스 이미지 존재 확인 (`docker manifest inspect` 성공)
- `ruff` 0.16.5가 `target-version = "py314"` 수용 확인
- `nautilus_trader`가 `pandas` 3.x와 `pyarrow`를 끌어온다. 기존 `requirements.txt`의
  `pandas>=2.2`/`numpy>=1.26`은 상한이 없어 충돌하지 않는다.

**영향 받는 파일**

| 파일 | 변경 |
|---|---|
| `worker/pyproject.toml` | `requires-python = ">=3.14"`, `[tool.ruff] target-version = "py314"` |
| `worker/requirements.txt` | `nautilus_trader` 추가 |
| `infra/docker/worker.Dockerfile` | `FROM python:3.11-slim` → `python:3.14-slim` |
| `Jenkinsfile` (Worker 스테이지) | `python3 -m venv` → `uv venv --python 3.14`. Jenkins 에이전트의 시스템 `python3`에 의존하지 않게 한다 |

`data/` 파트는 이 변경의 대상이 아니다. Jenkins의 Data 스테이지가 같은 시스템 `python3`을 쓰므로,
Worker 스테이지만 `uv`로 바꾸면 Data 파트는 영향을 받지 않는다.

**Alternatives considered**:
- *Python 3.11 유지*: 1.231.0이 3.11에서도 동작할 가능성이 높지만, 팀이 최신 Nautilus를 쓰기로 정했고
  3.14에서 전체 의존성이 문제없이 해결되는 것을 확인했으므로 굳이 낮출 이유가 없다.
- *Jenkins 에이전트 이미지에 Python 3.14 설치*: `uv`가 인터프리터를 직접 관리하면 에이전트 이미지를
  건드리지 않아도 된다. 저장소는 이미 spec-kit 스크립트에서 `uv`를 쓰고 있다.

---

## R9. FE 판정 미러의 검증 수단

**Decision**: FE에 `vitest`를 devDependency로 추가하고, BE·FE가 **같은 테스트 벡터 파일**
(`docs/specs/strategy-definition/test-vectors/parameter-validation.json`)을 읽어 동일한 판정 결과를 내는지 각자 검증한다.
Jenkinsfile의 Frontend 스테이지에 `npm run test`를 추가한다.

**Rationale**: SC-002와 SC-010은 "서로 다른 소비 지점이 다른 판정을 낸 사례 0건"을 요구하는데, FE에 테스트
러너가 없으면(`frontend/package.json`에 test 스크립트 없음) 이 요구는 검증 불가능한 문장으로 남는다.
원칙 III이 요구하는 "지금 해결하는 문제"는 이것이다. Vite가 이미 있으므로 vitest는 설정 비용이 거의 없다.

**Alternatives considered**:
- *FE에서 판정하지 않고 매 입력마다 서버에 물어보기*: 왕복 지연으로 입력 폼 UX가 나빠지고, 오프라인
  입력 검증이 불가능하다. 서버 판정은 어차피 제출 시점에 한 번 더 수행되므로 FE 미러는 보조 수단이다.
- *JSON Schema 코드 생성으로 타입만 맞추기*: 타입은 맞아도 판정 로직(step, 제약, 기본값 주입)은 여전히
  손으로 쓰는 부분이라 검증 문제가 그대로 남는다.

---

## R10. Backend 패키지 배치

**Decision**: `com.ssafy.thorp.strategy` 아래에 4계층을 두되, 두 개의 새 하위 패키지를 쓴다.

- `domain/policy/` — 순수 판정·계산 코드(`ParameterSetValidator`, `HistoryRequirementCalculator`,
  `StrategyDefinitionFormatValidator`)
- `infrastructure/catalog/` — 정의 JSON 로딩과 Jackson DTO

`backend/CLAUDE.md`의 계층 표에 이 두 하위 패키지를 추가하는 것을 구현 작업에 포함한다.

**Rationale**: `ArchitectureTest`(`backend/src/test/java/com/ssafy/thorp/ArchitectureTest.java`)는
`..domain..`, `..infrastructure..` 패턴으로 검사하므로 두 패키지 모두 규칙 위반이 아니고, 도메인이 Jackson을
참조하지 않게 하려면(`도메인은_프레임워크에_의존하지_않는다` 규칙) JSON DTO는 반드시 infrastructure에 있어야
한다. 다만 `backend/CLAUDE.md`가 하위 패키지를 `persistence`/`messaging`, `model`/`vo`/`exception`으로
열거하고 있어, 문서를 갱신하지 않으면 다음 사람이 규칙 위반으로 오해한다. 이 도메인은 DB를 쓰지 않으므로
`persistence`에 넣는 것은 이름이 거짓이 된다.

**Alternatives considered**:
- *판정 코드를 `application/service`에 두기*: ArchUnit은 통과하지만 판정 규칙은 FE·(장래) Worker가 그대로
  옮겨 쓰는 순수 로직이므로 도메인에 있는 편이 이식 단위가 명확하다.
- *`infrastructure/persistence`에 카탈로그 로더 두기*: 영속화가 아니다. 이름과 내용이 어긋난다.
