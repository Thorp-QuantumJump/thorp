# Phase 1 자료 구조: 전략 선택·상세 설정

**Spec**: [spec.md](spec.md) | **Research**: [research.md](research.md) | **Date**: 2026-09-15

spec의 Key Entities를 구현할 수 있는 형태로 옮긴다. 기존 `paramsearch` 패키지에 있는 타입을 그대로 쓰는 곳과 새로 더하는 곳을 구분해 적는다.

## 1. 파라미터 등록표 (ParameterRegistry)

서버가 보관하는 진실의 원본이다. 기동 시 자원 파일에서 한 번 읽어 불변으로 들고 있는다.

| 필드 | 형 | 제약 | 비고 |
|---|---|---|---|
| `version` | String | 비어 있지 않음 | PD-02 확정 전까지 `PROVISIONAL-`로 시작한다 |
| `provisional` | boolean | — | `version`의 접두로 판정한다. 화면이 임시값 표시에 쓴다 |
| `baselineStrategyId` | String? | 활성 전략 중 하나이거나 없음 | 007의 손실 제약 기준 |
| `strategies` | List\<StrategyRegistration\> | 1개 이상 | 순서를 보존한다 |
| `analysisConditions` | AnalysisConditionRegistry | 필수 | 시작 금액 등 전략 공통 조건 |

**불변식**: 같은 `version` 안에서 전략 순서와 축 순서, 축의 `searchValues` 순서를 바꾸지 않는다. 007이 저장한 그리드 좌표가 그 순서를 뜻으로 삼기 때문이다. 순서를 바꾸려면 버전을 올린다.

## 2. 전략 등록 정보 (StrategyRegistration) — 기존 타입 확장

기존 `com.ssafy.thorp.paramsearch.application.port.StrategyRegistration`을 `strategy` 패키지로 옮기고 두 필드를 더한다.

| 필드 | 형 | 상태 | 비고 |
|---|---|---|---|
| `strategyId` | String | 기존 | `EQUAL_WEIGHT` `FIXED_WEIGHT` `INVERSE_VOLATILITY` `SECTOR_MOMENTUM` `MINIMUM_VARIANCE` |
| `name` | String | 기존 | 화면 이름. spec FR-001의 '화면 이름' 열을 쓴다 |
| `description` | String? | 기존 | 카드의 한 줄 설명 |
| `axes` | List\<ParameterAxis\> | 기존 | 순서 보존 |
| **`active`** | boolean | **추가** | 생략 시 true. 비활성 전략은 카드에 보이지 않는다 (R5) |
| **`warmup`** | WarmupRule? | **추가** | 없으면 워밍업 0 (R4) |

**화면 이름 대조**: 등록표의 `name`이 곧 화면에 보이는 이름이다. 코드나 화면에 전략 이름을 따로 두지 않는다.

| strategyId | name (등록표) | spec FR-001의 전략 |
|---|---|---|
| `FIXED_WEIGHT` | 내가 정한 비중 | 고정 목표 비중 |
| `EQUAL_WEIGHT` | 똑같이 나누기 | 동일 비중 |
| `INVERSE_VOLATILITY` | 변동성에 따라 나누기 | 역변동성 |
| `SECTOR_MOMENTUM` | 흐름을 따라 배분하기 | 섹터 모멘텀 |
| `MINIMUM_VARIANCE` | 전체 변동성을 줄이도록 배분하기 | 최소분산 |

> 현재 임시 등록표의 `name`은 내부 전략명(`역변동성` 등)으로 적혀 있다. 정식 등록표에서는 화면 이름으로 바꾼다.

## 3. 파라미터 축 (ParameterAxis) — 기존 타입 유지

기존 필드를 그대로 쓴다. 형태 판정은 `allowedRange`가 담당한다.

| 필드 | 형 | 비고 |
|---|---|---|
| `name` | String | 조합과 좌표의 키 |
| `label` | String | 화면 표시 문구 |
| `unit` | String? | 예: `거래일`, `퍼센트` |
| `searchValues` | List\<Object\> | **007의 자동 탐색이 훑을 후보.** 인덱스가 그리드 좌표다 |
| `defaultValue` | Object | 기본 시작값. 최적값·추천으로 표시하지 않는다 |
| `allowedRange` | AllowedRange | **사용자가 고르거나 입력할 수 있는 범위** |

**`searchValues`와 `allowedRange`의 관계**: 목록형 축에서는 `searchValues ⊆ allowedRange.values`여야 한다. 로더가 기동 시 검사하고 위반하면 기동을 실패시킨다 (R3).

## 4. 허용 범위 (AllowedRange) — 기존 타입 유지

| 형태 | 채우는 필드 | 화면 | 검증 |
|---|---|---|---|
| 목록형 | `values` | 후보 목록에서 고른다 | 목록에 있는 값인가 |
| 입력형 | `min` `max` `step` | 값을 직접 입력한다 | 범위 안이고 간격에 맞는가 |

**둘을 동시에 채우지 않는다.** 로더가 막는다. 현재 임시 파일의 `lookbackDays`는 둘을 함께 가지므로 정식 등록표로 옮길 때 목록형으로 정리한다.

**전략별 항목의 형태** (spec FR-003과 Clarifications 기준):

| 전략 | 항목 | 형태 |
|---|---|---|
| 고정 목표 비중 | 목표 비중 | 축이 아님 — 002가 확정한 값을 읽기만 한다 |
| 고정 목표 비중 | 리밸런싱 주기 | 목록형 |
| 고정 목표 비중 | 비중 상한 | 입력형 |
| 동일 비중 | 리밸런싱 주기 | 목록형 |
| 역변동성 | 변동성 관측 기간 | 목록형 |
| 역변동성 | 리밸런싱 주기 | 목록형 |
| 역변동성 | 비중 상한 | 입력형 |
| 섹터 모멘텀 | 관측 기간 | 목록형 |
| 섹터 모멘텀 | 최근 구간 제외 여부 | 목록형 |
| 섹터 모멘텀 | 선정 수 또는 임계값 | 목록형 |
| 섹터 모멘텀 | 배분 방식 | 목록형 |
| 섹터 모멘텀 | 리밸런싱 주기 | 목록형 |
| 최소분산 | 공분산 관측 기간 | 목록형 |
| 최소분산 | 추정 방법 | 목록형 |
| 최소분산 | 비중 상한 | 입력형 |
| 최소분산 | 리밸런싱 주기 | 목록형 |

> 구체적 후보 값은 PD-02가 정한다. 확정 전까지는 임시 등록표의 값과 기획서 출발안(월말 리밸런싱, 역변동성 63거래일)을 쓴다.

## 5. 리밸런싱 주기 값 (RebalancePeriod)

목록형 축 `rebalancePeriod`의 후보 값 하나가 갖는 형태다 (R7).

| 방식 | 형태 | 예 |
|---|---|---|
| 달력 기준 | `{ mode: "CALENDAR", unit: "MONTH_END" \| "QUARTER_END" \| "HALF_END" }` | 월말 |
| 거래일 수 기준 | `{ mode: "TRADING_DAYS", days: <정수> }` | 21거래일 |

**값 비교**: `ParameterAxis.indexOf`가 `equals`로만 비교하므로 값 비교가 성립하는 형태여야 한다. `record`로 만들거나 역직렬화 결과 `Map`을 그대로 쓴다.

**한 설정에 한 방식만**: 값 자체가 방식을 담으므로 섞일 수 없다.

## 6. 워밍업 규칙 (WarmupRule)

| 필드 | 형 | 비고 |
|---|---|---|
| `basis` | String | 기준으로 삼을 축 이름. 그 축의 현재 값을 거래일 수로 읽는다 |
| `plus` | int | 더할 거래일 수. 기본 0 |

**계산**: `warmupTradingDays = <basis 축의 현재 값> + plus`. `basis`가 없으면 0이다.

**누가 부르나**: 화면이 설정을 바꿀 때마다 다시 계산해 카드를 판정하고(FR-011), 서버가 분석 요청을 받을 때 같은 규칙으로 다시 계산한다. 백테스트에서 실제로 구간을 건너뛰는 것은 [004](../004-backtest-execution/spec.md)다.

**전략별 기준 축**:

| 전략 | basis | plus |
|---|---|---|
| 고정 목표 비중 | 없음 | 0 |
| 동일 비중 | 없음 | 0 |
| 역변동성 | `lookbackDays` | 0 |
| 섹터 모멘텀 | `lookbackDays` | 최근 구간 제외 일수 (PD-02) |
| 최소분산 | `lookbackDays` | 0 |

## 7. 분석 조건 등록 정보 (AnalysisConditionRegistry)

| 필드 | 형 | 비고 |
|---|---|---|
| `initialAmount` | AllowedRange | 입력형. 최소·최대·간격 (R6) |
| `initialAmountDefault` | Object | 기본 시작 금액 |

**비용 가정**: 기본 수치는 PD-07이고 소유가 [004](../004-backtest-execution/spec.md)다. 003은 004가 등록한 비용 조건(`cost_profile`)의 현재 버전을 읽어 화면에 보이고, 등록표에 따로 담지 않는다.

## 8. 전략 설정값 (StrategySettings) — 화면 상태

서버에 저장하지 않는다. 브라우저 탭 수명 동안만 `sessionStorage`에 둔다 (R8).

| 필드 | 형 | 비고 |
|---|---|---|
| `registryVersion` | String | 조회한 등록표 버전. 다르면 저장값을 버린다 |
| `selectedStrategyId` | String? | 고른 전략 |
| `valuesByStrategy` | Map\<String, Map\<String, Object\>\> | 전략별로 따로 보관한다. 카드를 옮겨도 이전 전략의 값이 남는다 |
| `analysisCondition` | AnalysisCondition | 아래 9 |

**기본 설정 여부**: 저장하지 않고 매번 계산한다. 그 전략의 모든 축 값이 `defaultValue`와 같으면 '기본 설정', 하나라도 다르면 '설정을 바꿈'이다 (FR-002). 저장하면 값과 표시가 어긋날 수 있다.

## 9. 분석 조건 (AnalysisCondition) — 화면 상태

| 필드 | 형 | 비고 |
|---|---|---|
| `periodStart` / `periodEnd` | date | 001이 산출한 공통 평가 가능 범위 안에서만 (FR-009) |
| `initialAmount` | number | 등록표의 허용 범위 안에서만 |
| `costProfileVersion` | String | 004가 등록한 비용 조건의 버전. 읽기 전용 |
| `scenario` | String | 연속 운용 기본 |

## 10. 분석 요청 (AnalysisRequest) — 004로 넘기는 것

| 필드 | 출처 | 비고 |
|---|---|---|
| 섹터·비중 | [002](../002-sector-weight-selection/spec.md) | 목표 비중은 여기 담긴 값 하나뿐이다 |
| `strategyId` + 축 값 | 8 | |
| 분석 조건 | 9 | |
| `registryVersion` | 8 | 다르면 서버가 409로 거절한다 (R9) |
| `warmupTradingDays` | 6의 계산 결과 | 여러 전략을 비교할 때는 가장 긴 값을 공통으로 쓴다 (FR-014, 004 R-15) |

## 상태 전이

전략 카드의 선택 가능 여부만 상태를 갖는다.

```
등록표 조회 중        → 모든 카드 선택 불가
등록표 조회 실패      → 모든 카드 선택 불가 + 다시 시도 (FR-018)
등록표 조회 성공
  ├ 비활성 전략        → 카드에 보이지 않음 (R5)
  ├ 워밍업 미충족      → 카드 선택 불가 + 이유 한 문장 (FR-011)
  ├ 워밍업 충족·기간 축소 → 선택 가능 + 줄어든 기간 안내 (FR-011)
  └ 그 외              → 선택 가능
```

설정을 바꿀 때마다 워밍업을 다시 계산해 위 판정을 되풀이한다. 카드 목록에서는 각 전략의 기본 설정을 기준으로 판정한다.
