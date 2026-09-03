# Phase 1 Data Model: 전략 정의 카탈로그

**Feature**: `specs/012-strategy-definition-catalog` | **Date**: 2026-09-03

JSON 표현의 정본은 `contracts/`의 JSON Schema다. 이 문서는 그 구조의 의미, 스키마로 표현할 수 없는 규칙,
그리고 세 파트의 타입 대응을 정리한다. 모든 JSON 필드는 camelCase다(원칙 IV).

---

## 1. 엔티티 개요

```
StrategyCatalog
 └── StrategyDefinition (strategyId로 유일)
      ├── ParameterDefinition[]        개별 파라미터의 타입·범위·기본값
      │    ├── NumericRange?           INTEGER | DECIMAL 일 때만
      │    └── EnumOption[]?           ENUM 일 때만
      ├── ParameterConstraint[]        파라미터 사이의 관계 (개별 범위와 분리)
      ├── MinimumHistoryRequirement    bars 단위 이력 요구량 산출 규칙
      └── ChangeHistoryEntry[]         정의 버전별 호환성 등급

ParameterValueSet   사용자 입력 (부분 집합 가능)
 └── 판정 → ValidationOutcome
              ├── ParameterViolation[]     위반 목록 (전량 보고)
              ├── ResolvedParameters?      기본값이 채워진 확정 값 집합
              └── requiredHistoryBars?     확정 값 기준 이력 요구량
```

---

## 2. StrategyDefinition

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `schemaVersion` | string | O | 정의 **형식**의 버전. 개별 전략의 `definitionVersion`과 다른 축이다. 초기값 `"1.0.0"` |
| `strategyId` | string | O | 카탈로그 내 유일 식별자. `^[A-Z][A-Z0-9_]{2,49}$` |
| `definitionVersion` | integer ≥ 1 | O | 전략별 단조 증가 정수 (FR-019) |
| `displayName` | string | O | 사람이 읽는 이름 (1–60자) |
| `description` | string | O | 전략이 무엇을 하는지 (FR-002) |
| `entryCondition` | string | O | 진입 조건 **서술** (FR-003). 기계 해석 형태를 담지 않는다 |
| `exitCondition` | string | O | 청산 조건 **서술** (FR-003) |
| `executionScopes` | `ExecutionScope[]` | O | 이 전략이 유효한 실행 목적. 1개 이상, 중복 불가 |
| `parameters` | `ParameterDefinition[]` | O | 빈 배열 허용 (FR-004) |
| `parameterConstraints` | `ParameterConstraint[]` | O | 빈 배열 허용 |
| `minimumHistoryRequirement` | `MinimumHistoryRequirement` | O | FR-010 |
| `changeHistory` | `ChangeHistoryEntry[]` | O | 최신 우선, 1개 이상 |
| `deprecated` | boolean | – | 기본 `false`. `true`면 목록에서 선택 불가로 표시하되 기존 실행 기록 조회는 유지 |

`ExecutionScope` = `BACKTEST` | `LIVE` (닫힌 집합)

### 형식 위반 규칙 (JSON Schema로 표현 불가 — `StrategyDefinitionFormatValidator` 담당)

| # | 규칙 | 근거 |
|---|---|---|
| D1 | `strategyId`가 카탈로그 안에서 유일 | FR-018 |
| D2 | `parameters[].name`이 정의 안에서 유일 | FR-005 |
| D3 | `defaultValue`가 있으면 그 파라미터의 `numericRange`/`enumValues`를 만족 | FR-006 |
| D4 | `required: true`인 파라미터는 `defaultValue`를 가질 수 없음 | spec Edge Cases |
| D5 | `parameterConstraints[].left`/`.right`가 `parameters`에 존재 | FR-009 |
| D6 | 제약이 참조하는 파라미터는 `required: true`이거나 `defaultValue` 보유 | R3 |
| D7 | `LT`/`LTE`/`GT`/`GTE` 제약의 양변은 INTEGER 또는 DECIMAL | R3 |
| D8 | `EQ`/`NE` 제약의 양변은 같은 `type` | R3 |
| D9 | `minimumHistoryRequirement.parameters[]`가 `parameters`에 존재하고 `type: INTEGER` | FR-010c, R4 |
| D10 | `changeHistory[0].definitionVersion == definitionVersion` | R6 |
| D11 | `changeHistory`의 `definitionVersion`이 내림차순이며 중복 없음 | R6 |
| D12 | `step`이 있으면 `min`이 있고 `minInclusive: true` | R5 |
| D13 | `numericRange`에 `min`과 `max`가 모두 있으면 `min <= max`, 경계 배타 조합으로 공집합이 되지 않음 | FR-008 |
| D14 | 파라미터 이름의 snake_case 형태가 `StrategyConfig` 예약 필드 16개와 겹치지 않음 | R11 |

D14의 예약 필드 목록: `strategy_id`, `order_id_tag`, `use_uuid_client_order_ids`,
`use_hyphens_in_client_order_ids`, `oms_type`, `external_order_claims`, `manage_contingent_orders`,
`manage_gtd_expiry`, `manage_stop`, `market_exit_interval_ms`, `market_exit_max_attempts`,
`market_exit_time_in_force`, `market_exit_reduce_only`, `log_events`, `log_commands`,
`log_rejected_due_post_only_as_warning`. 겹치면 사용자 값이 실행 엔진의 동작 설정을 덮어쓴다.
이 목록은 Backend 도메인의 상수로 두고 Worker 계약 테스트가 실제 클래스와 대조해 갱신 누락을 잡는다.

D1은 카탈로그 단위, 나머지는 정의 단위 검사다. **위반한 정의만 제외하고 나머지는 정상 제공한다**(FR-017).
D1 위반 시에는 충돌한 `strategyId`를 가진 정의를 **모두** 제외한다 — 어느 쪽이 옳은지 카탈로그가 임의로
고르지 않는다(spec Edge Cases).

---

## 3. ParameterDefinition

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `name` | string | O | 프로그램이 쓰는 이름. `^[a-z][a-zA-Z0-9]{0,39}$` (camelCase) |
| `displayName` | string | O | 화면에 보이는 이름 |
| `description` | string | O | 이 값이 무엇을 바꾸는지 (FR-005) |
| `type` | `ParameterType` | O | `INTEGER` \| `DECIMAL` \| `BOOLEAN` \| `ENUM` |
| `required` | boolean | O | |
| `defaultValue` | number \| boolean \| string \| null | – | `type`에 맞는 리터럴. `required: true`면 금지(D4) |
| `unit` | string \| null | – | 표시용 단위 (`bars`, `ratio`, `percent`, `KRW` 등). 판정에 쓰지 않는다 |
| `executionScopes` | `ExecutionScope[]` | O | 이 파라미터가 의미를 갖는 실행 목적 (FR-021) |
| `numericRange` | `NumericRange` | 조건부 | `INTEGER`/`DECIMAL`일 때 필수, 그 외 금지 |
| `enumValues` | `EnumOption[]` | 조건부 | `ENUM`일 때 필수(1개 이상), 그 외 금지 |

### NumericRange

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `min` | number | – | 하한. 생략 시 하한 없음 |
| `minInclusive` | boolean | – | 기본 `true` |
| `max` | number | – | 상한. 생략 시 상한 없음 |
| `maxInclusive` | boolean | – | 기본 `true` |
| `step` | number > 0 | – | 허용 간격. 지정 시 `min` 필수 + `minInclusive: true`(D12) |

한쪽 경계만 지정한 열린 범위는 유효하다(FR-008, spec Edge Cases). `INTEGER`의 `min`/`max`/`step`은 정수여야
한다. `step` 판정식은 `(value - min) % step == 0`이며 십진 연산으로 수행한다(R5).

### EnumOption

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `value` | string | O | 실제 값. 정의 안에서 유일 |
| `displayName` | string | O | 화면 표시용 |

---

## 4. ParameterConstraint

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `left` | string | O | 파라미터 이름 |
| `operator` | `ConstraintOperator` | O | `LT` \| `LTE` \| `GT` \| `GTE` \| `EQ` \| `NE` |
| `right` | string | O | 파라미터 이름. 상수 불가(R3) |
| `message` | string | O | 위반 시 사용자에게 보일 설명 (FR-009) |

판정은 **확정 값 집합(기본값 주입 후)** 기준이다. 예:

```json
{ "left": "shortPeriod", "operator": "LT", "right": "longPeriod",
  "message": "단기 이동평균 기간은 장기 이동평균 기간보다 짧아야 합니다." }
```

---

## 5. MinimumHistoryRequirement

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `rule` | `HistoryRule` | O | `FIXED` \| `PARAMETER` \| `MAX_PARAMETERS` \| `SUM_PARAMETERS` |
| `value` | integer ≥ 0 | 조건부 | `FIXED`일 때 필수, 그 외 금지 |
| `parameters` | string[] | 조건부 | `PARAMETER`는 정확히 1개, `MAX_`/`SUM_`은 1개 이상. `FIXED`일 때 금지 |
| `offset` | integer ≥ 0 | – | 기본 `0` |
| `unit` | `"bars"` | O | 상수. 다른 값 불가 |

산출식은 `research.md` R4의 표를 따른다. 결과는 항상 0 이상의 정수이며 단위는 `bars`다.
`bars`를 달력 일자로 환산하는 일은 이 기능의 범위 밖이다(spec Assumptions).

---

## 6. ChangeHistoryEntry

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `definitionVersion` | integer ≥ 1 | O | 이 변경으로 도달한 버전 |
| `changeType` | `ChangeType` | O | `INITIAL` \| `BACKWARD_COMPATIBLE` \| `INCOMPATIBLE` |
| `changeSummary` | string | O | 무엇을 바꿨는지 한 줄 |
| `changedAt` | string (date) | O | `YYYY-MM-DD` |

`definitionVersion: 1`의 `changeType`은 반드시 `INITIAL`이고, 그 외 버전에는 `INITIAL`을 쓸 수 없다.
`BACKWARD_COMPATIBLE`은 "직전 버전에서 유효했던 모든 값 집합이 이 버전에서도 유효함"을 뜻한다(FR-019b).

---

## 7. 값 판정 (ValidationOutcome)

### 위반 코드 (닫힌 집합)

| 코드 | 발생 조건 | 근거 |
|---|---|---|
| `UNKNOWN_STRATEGY` | `strategyId`가 카탈로그에 없음 | FR-001 |
| `UNKNOWN_PARAMETER` | 정의에 없는 파라미터 이름이 입력에 포함 | FR-013 |
| `REQUIRED_PARAMETER_MISSING` | `required: true`인 파라미터가 입력에 없음 | FR-011 |
| `TYPE_MISMATCH` | 값의 타입이 `type`과 다름 | FR-005 |
| `OUT_OF_RANGE` | `min`/`max` 경계 위반 | FR-008 |
| `STEP_MISMATCH` | `step` 배수가 아님 | FR-008 |
| `NOT_IN_ENUM` | `enumValues`에 없는 값 | FR-008 |
| `CONSTRAINT_VIOLATED` | `parameterConstraints` 위반 | FR-009 |

### ParameterViolation

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `code` | 위 코드 중 하나 | O | |
| `parameterName` | string \| null | O | `CONSTRAINT_VIOLATED`는 `left`를, `UNKNOWN_STRATEGY`는 `null` |
| `message` | string | O | 사용자에게 보일 한국어 설명 |
| `detail` | object \| null | – | 판정 근거. 예: `{"min":2,"max":120,"actual":300}` |

### 판정 순서와 누적 규칙

```
S0. 전략 조회        실패 → UNKNOWN_STRATEGY 하나만 반환하고 종료
S1. 미정의 파라미터   입력 키 - 정의 키 → UNKNOWN_PARAMETER (해당 키는 이후 단계에서 무시)
S2. 필수 누락         REQUIRED_PARAMETER_MISSING
S3. 타입 판정         TYPE_MISMATCH (실패한 파라미터는 S4·S6에서 제외)
S4. 개별 범위·step·enum   OUT_OF_RANGE / STEP_MISMATCH / NOT_IN_ENUM
S5. 기본값 주입       생략된 선택 파라미터에 defaultValue 채움 → ResolvedParameters
S6. 상호 제약         CONSTRAINT_VIOLATED (양변 값이 모두 확정된 제약만 판정)
S7. 이력 요구량 산출   위반이 하나도 없을 때만 requiredHistoryBars 계산
```

- S1~S6은 **중단 없이 모두 수행**하고 위반을 누적한다(FR-012).
- S3에서 타입이 깨진 값은 S4·S6에서 판정할 수 없으므로 제외한다. 값이 없는데 범위 위반을 보고하면
  사용자에게 거짓 정보를 준다.
- 반대로 **S4에서 범위를 위반한 값은 S6 판정에 그대로 사용한다.** 타입은 확정되어 있으므로 판정이
  가능하고, 그래야 사용자가 범위 위반과 제약 위반을 한 번의 시도로 모두 본다(SC-004).
- S2/S3에서 값이 확정되지 않은 파라미터를 참조하는 제약은 S6에서 건너뛴다.

### executionScope 필터링 (FR-021)

요청의 `executionScope`(기본 `BACKTEST`)에 해당하지 않는 파라미터는 **그 요청에서 존재하지 않는 것으로
취급한다.**

- 판정 대상에서 제외한다 — `required: true`여도 누락으로 보지 않는다.
- 확정 값 집합에 넣지 않는다 — 기본값이 있어도 주입하지 않는다.
- 입력에 그 이름이 들어오면 `UNKNOWN_PARAMETER`로 거부한다. 해당 목적에서는 정의에 없는 이름과 같다.
- 최소 이력 규칙이 범위 밖 파라미터를 참조하면 그 정의는 형식 위반이다(D9의 연장).

전략 자체의 `executionScopes`에 요청한 목적이 없으면 그 전략은 해당 목적의 카탈로그 목록에서 빠진다.
목록에 없는 전략을 지정하면 `UNKNOWN_STRATEGY`다.
- 위반이 하나라도 있으면 `resolvedParameters`와 `requiredHistoryBars`는 `null`이다.
- 위반 목록의 순서는 `S1 → S6` 단계 순, 같은 단계 안에서는 정의의 `parameters` 선언 순서다.
  **세 파트가 동일한 순서로 보고해야 한다** — 테스트 벡터가 순서까지 비교한다.

### ResolvedParameters

`{ "파라미터이름": 값 }` 형태의 평면 객체다. 전략이 사용하는 **모든** 파라미터가 빠짐없이 들어간다
(FR-014). 값의 JSON 타입은 `INTEGER`→number(정수), `DECIMAL`→number, `BOOLEAN`→boolean, `ENUM`→string.

---

## 8. 파트별 타입 대응

### 8.1 Spring Backend (`com.ssafy.thorp.strategy`)

```
strategy/
├── presentation/
│   ├── controller/StrategyController.java
│   ├── request/ParameterValidationRequest.java
│   └── response/
│       ├── StrategySummaryResponse.java
│       ├── StrategyDefinitionResponse.java
│       ├── ParameterValidationResponse.java
│       └── ParameterViolationResponse.java
├── application/
│   ├── service/StrategyCatalogService.java
│   ├── service/ParameterValidationService.java
│   ├── command/ValidateParametersCommand.java
│   ├── result/StrategyDefinitionResult.java
│   ├── result/ParameterValidationResult.java
│   └── repository/StrategyDefinitionRepository.java      (interface)
├── domain/
│   ├── model/StrategyDefinition.java
│   ├── model/ParameterDefinition.java
│   ├── model/ParameterConstraint.java
│   ├── model/MinimumHistoryRequirement.java
│   ├── model/ChangeHistoryEntry.java
│   ├── model/StrategyCatalog.java
│   ├── model/ValidationOutcome.java
│   ├── model/ParameterViolation.java
│   ├── vo/ParameterType.java          (enum)
│   ├── vo/ExecutionScope.java         (enum)
│   ├── vo/ConstraintOperator.java     (enum)
│   ├── vo/HistoryRule.java            (enum)
│   ├── vo/ChangeType.java             (enum)
│   ├── vo/ViolationCode.java          (enum)
│   ├── vo/NumericRange.java
│   ├── vo/EnumOption.java
│   ├── vo/ParameterValue.java         (sealed interface)
│   ├── vo/ResolvedParameters.java
│   ├── policy/StrategyDefinitionFormatValidator.java
│   ├── policy/ParameterSetValidator.java
│   ├── policy/HistoryRequirementCalculator.java
│   ├── exception/StrategyNotFoundException.java
│   └── exception/InvalidStrategyDefinitionException.java
└── infrastructure/
    └── catalog/
        ├── ClasspathStrategyDefinitionRepository.java
        ├── StrategyDefinitionJsonDto.java   (Jackson 바인딩 전용)
        └── StrategyDefinitionJsonMapper.java
```

핵심 타입 시그니처:

```java
public sealed interface ParameterValue
        permits IntegerValue, DecimalValue, BooleanValue, EnumValue {
    record IntegerValue(long value) implements ParameterValue {}
    record DecimalValue(BigDecimal value) implements ParameterValue {}
    record BooleanValue(boolean value) implements ParameterValue {}
    record EnumValue(String value) implements ParameterValue {}
}

public record NumericRange(
        BigDecimal min, boolean minInclusive,
        BigDecimal max, boolean maxInclusive,
        BigDecimal step) {}

public record ValidationOutcome(
        boolean valid,
        List<ParameterViolation> violations,
        ResolvedParameters resolvedParameters,   // 위반 있으면 null
        Integer requiredHistoryBars) {}          // 위반 있으면 null
```

- `domain/`은 Spring·JPA·Jackson을 import하지 않는다 — `ArchitectureTest.도메인은_프레임워크에_의존하지_않는다`가
  강제한다. JSON 바인딩은 `infrastructure/catalog`의 DTO가 전담하고 `StrategyDefinitionJsonMapper`가
  도메인 record로 옮긴다.
- `domain/policy`와 `infrastructure/catalog`는 `backend/CLAUDE.md` 계층 표에 없던 하위 패키지다. 문서 갱신을
  구현 작업에 포함한다(R10).
- 이 도메인은 DB를 쓰지 않으므로 `@Entity`와 `infrastructure/persistence`가 없다.

### 8.2 Python Worker (`worker/src/worker`) — Nautilus-native

Worker는 정의 파일을 읽지 않는다(R7). 실행 메시지 바인딩과 **Nautilus 전략 구성**만 다룬다.
자체 `BaseStrategy`를 두지 않는다 — 전략은 `nautilus_trader`의 `Strategy` / `StrategyConfig`를 직접
상속한다(R8).

```
worker/src/worker/
├── models.py                  # 실행 메시지 계약 (엔진 비종속)
└── strategies/
    ├── registry.py            # strategyId → (strategy_path, config_path)
    ├── binding.py             # resolvedParameters → ImportableStrategyConfig → Strategy
    ├── naming.py              # camelCase ↔ snake_case 기계 변환 + 예약 필드 목록
    ├── sma_crossover.py       # SmaCrossoverConfig(StrategyConfig) + SmaCrossover(Strategy)
    ├── rsi_reversion.py
    ├── macd_signal.py
    └── momentum.py            # 기존 파일을 Nautilus 구조로 재작성
```

`base.py`는 **삭제**한다.

#### 실행 메시지 바인딩 (엔진 비종속)

```python
# models.py — BacktestJob.strategy 를 str 에서 객체로 교체 (F1 계약 델타)
ParamValue = int | float | bool | str

class StrategyBinding(BaseModel):
    """strategy-execution-binding.schema.json 의 Python 표현. Nautilus 개념이 들어오지 않는다."""
    strategy_id: str = Field(alias="strategyId")
    definition_version: int = Field(alias="definitionVersion")
    resolved_parameters: dict[str, ParamValue] = Field(alias="resolvedParameters")
    required_history_bars: int = Field(alias="requiredHistoryBars")
    execution_scope: Literal["BACKTEST", "LIVE"] = Field(alias="executionScope")
    model_config = {"populate_by_name": True}
```

#### 등록 테이블

```python
# strategies/registry.py
#   값은 Nautilus 가 이해하는 "module:ClassName" 경로 문자열이다.
STRATEGY_PATHS: dict[str, tuple[str, str]] = {
    "SMA_CROSSOVER": (
        "worker.strategies.sma_crossover:SmaCrossover",
        "worker.strategies.sma_crossover:SmaCrossoverConfig",
    ),
    ...
}
```

#### 변환 (`binding.py`)

```python
def to_importable(binding: StrategyBinding, instrument_id: str) -> ImportableStrategyConfig:
    """resolvedParameters 를 Nautilus 설정 dict 로 옮긴다. 전략별 분기가 없다."""
    strategy_path, config_path = registry.resolve(binding.strategy_id)
    return ImportableStrategyConfig(
        strategy_path=strategy_path,
        config_path=config_path,
        config={
            "instrument_id": instrument_id,
            **{to_snake(k): to_config_value(v) for k, v in binding.resolved_parameters.items()},
        },
    )

# 실행은 Nautilus 가 한다
strategy = StrategyFactory.create(to_importable(binding, instrument_id))
```

변환 규칙(R11):

| 정의 `type` | 설정 클래스 필드 타입 | 전달 형태 |
|---|---|---|
| `INTEGER` | `int` | JSON 정수 그대로 |
| `DECIMAL` | `Decimal` | **문자열로 직렬화** — float을 넘기면 이진 오차가 `Decimal`에 그대로 들어온다 |
| `BOOLEAN` | `bool` | 그대로 |
| `ENUM` | `str` 또는 `Literal[...]` | 문자열 그대로 |

이름은 camelCase → snake_case 기계 변환이며 전략별 매핑 표를 두지 않는다.

#### 전략 구현의 형태

```python
# strategies/sma_crossover.py
class SmaCrossoverConfig(StrategyConfig, frozen=True):
    """정의 SMA_CROSSOVER.json 의 파라미터에 1:1 대응. 예약 필드는 상속으로 이미 있다."""
    instrument_id: InstrumentId
    short_period: int
    long_period: int
    allow_short: bool = False
    rebalance_period: str = "DAILY"


class SmaCrossover(Strategy):
    def __init__(self, config: SmaCrossoverConfig) -> None:
        super().__init__(config)
        # 지표 준비

    def on_start(self) -> None: ...
    def on_bar(self, bar: Bar) -> None: ...   # 매매 판단과 주문은 여기서 (이번 범위 밖)
```

- `instrument_id`는 정의의 파라미터가 아니라 백테스트 **조건**(F2)에서 온다. `binding.py`가 채운다.
- `on_bar` 안의 매매 로직은 이번 기능의 범위가 아니다. 설정 클래스와 클래스 골격까지만 만든다.
- Nautilus가 체결·포지션·성과 산출을 담당하므로 기존 `engine.py`(pandas 기반 스캐폴딩)의 역할은
  사라진다. 실제 제거·대체는 백테스트 실행 배선 기능에서 다룬다.

#### 문서 충돌 (해소 필요)

`worker/CLAUDE.md`의 "전략은 `base.Strategy` 상속, `generate_signals(prices) -> Series`만 구현
(체결·자산 계산·지표 산출은 `engine.py` 담당 — 이 분리를 깨지 말 것)"은 이 결정과 정면으로 어긋난다.
같은 MR에서 Nautilus 기준으로 고쳐야 한다.

### 8.3 Frontend (`frontend/src`)

```
src/types/strategy.ts          계약 미러 타입 (수기 작성, 테스트 벡터로 검증)
src/api/strategies.ts          fetchStrategies / fetchStrategy / validateParameters
src/lib/strategyValidation.ts  판정 미러 (즉시 피드백용, 권위는 서버)
src/components/backtest/ParameterForm.tsx   parameters[]로 입력 폼 동적 생성
```

```ts
export type ParameterType = 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'ENUM'
export type ExecutionScope = 'BACKTEST' | 'LIVE'
export type ConstraintOperator = 'LT' | 'LTE' | 'GT' | 'GTE' | 'EQ' | 'NE'
export type HistoryRule = 'FIXED' | 'PARAMETER' | 'MAX_PARAMETERS' | 'SUM_PARAMETERS'
export type ViolationCode =
  | 'UNKNOWN_STRATEGY' | 'UNKNOWN_PARAMETER' | 'REQUIRED_PARAMETER_MISSING'
  | 'TYPE_MISMATCH' | 'OUT_OF_RANGE' | 'STEP_MISMATCH' | 'NOT_IN_ENUM'
  | 'CONSTRAINT_VIOLATED'

export type ParameterValue = number | boolean | string
export interface ParameterValueSet { [name: string]: ParameterValue }
```

`ParameterForm`은 `type`에 따라 컴포넌트를 고른다: `INTEGER`/`DECIMAL` → number input(`min`/`max`/`step`
속성 그대로 전달), `BOOLEAN` → switch, `ENUM` → select(`enumValues[].displayName` 표시). 이 매핑이
"새 전략을 추가해도 FE를 고치지 않는다"(SC-001)의 실체다.

---

## 9. 전략 등록·조회 구조

**등록(개발자)** — 새 전략 하나에 손대는 파일은 셋이다.

1. `backend/src/main/resources/strategies/<STRATEGY_ID>.json` — 정의
2. `worker/src/worker/strategies/<name>.py` — `XxxConfig(StrategyConfig)` + `Xxx(Strategy)`
3. `worker/src/worker/strategies/registry.py` — `STRATEGY_PATHS`에 한 줄

2번의 설정 클래스 필드는 1번의 파라미터와 이름·타입이 대응해야 하고, 그 대응은 Worker 계약 테스트가
검사한다. `binding.py`·`naming.py`·Backend·FE는 건드리지 않는다.

**로딩(기동 시)**: `ClasspathStrategyDefinitionRepository`가 `classpath:strategies/*.json`을 스캔 →
Jackson으로 DTO 역직렬화 → 도메인 record로 매핑 → `StrategyDefinitionFormatValidator`로 D1~D13 검사 →
통과분만 `StrategyCatalog`에 등재. 실패분은 `WARN` 로그에 `strategyId`와 위반 규칙 번호를 남기고 제외한다
(FR-017). **정의가 하나도 없거나 전부 실패해도 애플리케이션은 기동한다** — `GET /strategies`가 빈 목록을
반환할 뿐이다(spec Edge Cases).

**조회(런타임)**: 카탈로그는 기동 시 1회 로드해 불변 맵으로 보관한다. 파일 변경 감지나 핫 리로드는 두지
않는다 — 정의는 배포 단위로 바뀌는 자산이다.
