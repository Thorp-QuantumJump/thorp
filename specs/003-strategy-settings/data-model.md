# Phase 1 자료 구조: 전략 선택·상세 설정

**Spec**: [spec.md](spec.md) | **Research**: [research.md](research.md) | **Date**: 2026-09-15

spec의 Key Entities를 구현할 수 있는 형태로 옮긴다. 기존 `paramsearch` 패키지에 있는 타입을 그대로 쓰는 곳과 새로 더하는 곳을 구분해 적는다.

## 1. 파라미터 등록표 (ParameterRegistry)

서버가 보관하는 진실의 원본이다. 기동 시 자원 파일에서 한 번 읽어 불변으로 들고 있는다.

| 필드 | 형 | 제약 | 비고 |
|---|---|---|---|
| `version` | String | 비어 있지 않음 | 값·축·순서가 바뀌면 새 버전. 007에서 이관한 미확정 값은 `PROVISIONAL-` 접두를 유지한다 |
| `provisional` | boolean | — | `version`의 접두로 판정한다. `true`면 화면과 요청 결과에 임시값임을 표시한다 |
| `baselineStrategyId` | String? | 활성 전략 중 하나이거나 없음 | 007의 손실 제약 기준 |
| `strategies` | List\<StrategyRegistration\> | 1개 이상 | 순서를 보존한다 |
| `analysisConditions` | AnalysisConditionRegistry | 필수 | 시작 금액·목표 변동성 등 전략 공통 조건 |

**불변식**: 같은 `version` 안에서 전략 순서와 축 순서, 축의 `searchValues` 순서를 바꾸지 않는다. 007이 저장한 그리드 좌표가 그 순서를 뜻으로 삼기 때문이다. 순서를 바꾸려면 버전을 올린다.

## 2. 전략 등록 정보 (StrategyRegistration) — 기존 타입 확장

기존 `com.ssafy.thorp.paramsearch.application.port.StrategyRegistration`을 `strategy` 패키지로 옮기고 세 필드를 더한다.

| 필드 | 형 | 상태 | 비고 |
|---|---|---|---|
| `strategyId` | String | 기존 | `EQUAL_WEIGHT` `FIXED_WEIGHT` `INVERSE_VOLATILITY` `SECTOR_MOMENTUM` `MINIMUM_VARIANCE` |
| `name` | String | 기존 | 화면 이름. spec FR-001의 '화면 이름' 열을 쓴다 |
| `description` | String? | 기존 | 카드의 한 줄 설명 |
| `axes` | List\<ParameterAxis\> | 기존 | 순서 보존 |
| **`active`** | boolean | **추가** | 생략 시 true. 비활성 전략은 카드에 보이지 않는다 (R5) |
| **`warmup`** | WarmupRule? | **추가** | 없으면 워밍업 0 (R4) |
| **`targetWeightSource`** | String? | **추가** | `USER` \| `EQUAL` \| `COMPUTED`. 목표 비중이 어디에서 오는지를 등록표가 말한다. `USER`면 화면이 002가 확정한 비중을 읽기 전용으로 보이고, `COMPUTED`면 FR-012의 자동 배분 설명을 보인다 |

**`targetWeightSource`를 두는 이유**: 이 값이 없으면 화면이 "고정 목표 비중이면 비중을 보이고 나머지는 자동 배분 설명을 보인다"를 전략 ID 분기로 적게 된다. 그것이 곧 전략 지식의 중복이고, 전략이 하나 늘 때마다 화면을 고쳐야 한다(FR-005, [research](research.md) R7-1). 조회 응답에도 같은 이름으로 실린다([계약](contracts/strategy-registry-api.md) §1).

**화면 이름 대조**: 등록표의 `name`이 곧 화면에 보이는 이름이다. 코드나 화면에 전략 이름을 따로 두지 않는다.

| strategyId | name (등록표) | spec FR-001의 전략 |
|---|---|---|
| `FIXED_WEIGHT` | 내가 정한 비중 | 고정 목표 비중 |
| `EQUAL_WEIGHT` | 똑같이 나누기 | 동일 비중 |
| `INVERSE_VOLATILITY` | 변동성에 따라 나누기 | 역변동성 |
| `SECTOR_MOMENTUM` | 흐름을 따라 배분하기 | 섹터 모멘텀 |
| `MINIMUM_VARIANCE` | 전체 변동성을 줄이도록 배분하기 | 최소분산 |

> 현재 임시 등록표의 `name`은 내부 전략명(`역변동성` 등)으로 적혀 있다. 정식 등록표에서는 화면 이름으로 바꾼다.

## 3. 파라미터 축 (ParameterAxis) — 기존 타입 확장

형태 판정은 `allowedRange`가 담당한다. 기존 필드에 `description` 하나를 더한다.

| 필드 | 형 | 비고 |
|---|---|---|
| `name` | String | 조합과 좌표의 키 |
| `label` | String | 화면 표시 문구 |
| **`description`** | String? | **추가.** 항목의 의미 한 문장. FR-003의 '의미'와 FR-019의 상한 재배분 안내 문장이 이 자리에 온다. 화면이 축 이름으로 분기해 설명을 고르지 않게 하는 자리다 (R7-1) |
| `unit` | String? | 예: `거래일`, `퍼센트` |
| `searchValues` | List\<Object\> | **007의 자동 탐색이 훑을 후보.** 인덱스가 그리드 좌표다 |
| `defaultValue` | Object | 기본 시작값. 최적값·추천으로 표시하지 않는다 |
| `allowedRange` | AllowedRange | **사용자가 고르거나 입력할 수 있는 범위** |

**`searchValues`와 `allowedRange`는 뜻이 다르다**: 값이 겹칠 수 있으나 합치지 않는다. `allowedRange.values`는 사용자가 상세 설정에서 고르는 선택지이고, `searchValues`는 007 자동 비교가 하나씩 전부 돌려볼 값이다. 후자를 늘리면 실행 횟수가 곱으로 늘어난다 (R3).

**로더가 기동 시 검사하는 제약**:

| 제약 | 어기면 |
|---|---|
| 모든 축이 `allowedRange`를 갖는다 | 화면이 그릴 형태도, 입력값을 판정할 근거도 없다 |
| 목록형 축에서 `searchValues ⊆ allowedRange`의 후보 | 자동 탐색이 사용자가 고를 수 없는 값을 훑어 결과를 재현할 수 없다 |
| `defaultValue`가 `allowedRange` 안에 있다 | 화면이 열리자마자 허용 범위 밖 값을 보여 준다 |
| `allowedRange`가 목록형과 입력형을 동시에 채우지 않는다 | 화면이 목록을 그릴지 입력칸을 그릴지 정할 수 없다 |
| 목록형이 `values`와 `choices`를 동시에 채우지 않는다 | 어느 쪽이 후보 목록인지 정할 수 없다 |
| 숫자가 아닌 후보에 표시 문구가 있다 | 화면이 `TOP_N`을 그대로 보이거나 그 뜻을 코드에 적게 된다 (FR-005) |

하나라도 어기면 기동을 실패시킨다. 등록표가 잘못된 채로 서비스가 뜨면 사용자가 그 사실을 알 방법이 없다.

## 4. 허용 범위 (AllowedRange) — 기존 타입 유지

| 형태 | 채우는 필드 | 화면 | 검증 |
|---|---|---|---|
| 목록형 | `values` 또는 `choices` | 후보 목록에서 고른다 | 목록에 있는 값인가 |
| 입력형 | `min` `max` `step` | 값을 직접 입력한다 | 범위 안이고 간격에 맞는가 |

**둘을 동시에 채우지 않는다.** 로더가 막는다. 현재 임시 파일의 `lookbackDays`는 둘을 함께 가지므로 정식 등록표로 옮길 때 목록형으로 정리한다.

**목록형은 후보와 그 표시 문구를 함께 담는다** (`RegisteredChoice(value, label)`). 등록표가 적는 형태는 둘이다.

| 적는 법 | 쓰는 자리 | 화면 문구 |
|---|---|---|
| `values: [21, 63]` | 값과 축의 `unit`을 붙이면 그대로 읽히는 숫자 후보 | 서버가 `21` + `거래일`로 만든다 |
| `choices: [{value: TOP_N, label: 상위 몇 개}]` | 그 밖의 모든 후보 | 등록표가 적은 문구 그대로 |

**숫자가 아닌 후보는 `choices`로만 적을 수 있고 로더가 기동 시 그것을 요구한다.** `TOP_N`이나 `{mode: CALENDAR, unit: MONTH_END}`가 무슨 뜻인지는 등록표만 안다 — 문구를 적지 않으면 화면이 `TOP_N`을 그대로 보이거나, 무슨 말로 보일지 화면 코드에 적게 된다. 후자가 FR-005가 금지한 "화면 코드에 따로 적어 두는 안내"다.

**판정은 값만 본다.** `contains`·`indexOf`와 007의 그리드 좌표는 문구를 보지 않으므로, 문구를 고쳐도 저장된 좌표의 뜻은 흔들리지 않는다. 그래서 문구 수정은 `searchValues` 순서 변경과 달리 버전을 묶지 않는다 — 다만 응답에 실려 나가므로 값과 함께 버전을 올리는 편이 읽는 쪽에 분명하다.

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
| 섹터 모멘텀 | 최근 구간 제외 기간 (`skipRecentDays`) | 목록형 |
| 섹터 모멘텀 | 선정 방식 (`selectionMode`) | 목록형 — `TOP_N`\|`THRESHOLD`\|`TOP_PERCENT`\|`ALL` |
| 섹터 모멘텀 | 선정 수 (`topN`, `selectionMode=TOP_N`일 때만) | 목록형 |
| 섹터 모멘텀 | 임계값 (`threshold`, `selectionMode=THRESHOLD`일 때만) | 입력형 |
| 섹터 모멘텀 | 상위 비율 (`topPercent`, `selectionMode=TOP_PERCENT`일 때만) | 입력형 |
| 섹터 모멘텀 | 배분 방식 (`allocationMethod`) | 목록형 |
| 섹터 모멘텀 | 비중 상한 | 입력형 — spec FR-003 원표에 빠져 있었다. FR-019가 역변동성과 섹터 모멘텀 둘 다 상한 재배분 대상이라 명시해 이번에 추가했다 |
| 섹터 모멘텀 | 리밸런싱 주기 | 목록형 |
| 최소분산 | 공분산 관측 기간 | 목록형 |
| 최소분산 | 추정 방법 (`estimationMethod`) | 목록형 — 현재 후보 하나(표본공분산). 004 PD-04 확정 시 축소추정을 추가한다 |
| 최소분산 | 비중 상한 | 입력형 |
| 최소분산 | 리밸런싱 주기 | 목록형 |

`selectionMode`가 `ALL`이면 부속 값이 없다. 화면은 고른 `selectionMode`에 해당하는 값 하나만 상세 설정에 열고 나머지 값은 저장하지 않는다(spec FR-003).

**그 대응은 등록표가 적는다.** 축은 선택 필드 `appliesWhen: { axis, value }`를 가질 수 있고(`ParameterAxis.appliesWhen`, `AxisCondition`), 결정 축이 그 값일 때만 그 축을 쓴다. `topN`·`threshold`·`topPercent`가 각각 `selectionMode`의 `TOP_N`·`THRESHOLD`·`TOP_PERCENT`를 가리킨다. 화면·접수 관문(`StrategySettingsValidator`)·007 조합 생성이 모두 `StrategyRegistration.applicableAxes`로 같은 판정을 하고, 축 이름을 해석하지 않는다(FR-005). 로더는 결정 축이 같은 전략에 없거나 값이 그 축의 후보 밖이면 기동을 막는다.

**용어 도움말도 등록표가 가리킨다.** 축의 선택 필드 `termId`가 [013 data-model §8](../013-learning-pages/data-model.md)의 식별자를 담고, 화면은 그 값이 있는 축에만 도움말을 붙인다(FR-016).

## 4-2. 확정 후보 값 (PD-02, 2026-09-15, [S15P21E205-254](https://ssafy.atlassian.net/browse/S15P21E205-254))

**공통 `rebalancePeriod` 후보** (모든 전략 공통, §5 형태를 따른다):

| 값 | 화면 문구 (`label`) | 비고 |
|---|---|---|
| `{mode: CALENDAR, unit: MONTH_END}` | 월말 | 기본값 |
| `{mode: CALENDAR, unit: QUARTER_END}` | 분기말 | |
| `{mode: TRADING_DAYS, days: 21}` | 21거래일 | |
| `{mode: TRADING_DAYS, days: 63}` | 63거래일 | |

오른쪽 열은 설명이 아니라 **등록표가 담는 값**이다(§3의 `choices`). 화면이 `{mode: CALENDAR, unit: MONTH_END}`를 "월말"로 옮기는 규칙을 갖지 않는다.

**전략별 축**:

**두 목록을 한 칸에 적지 않는다.** `searchValues`(007이 훑을 값)와 `allowedRange`(사용자가 고를 범위)는 뜻이 다르고 §3이 그것을 이미 갈라 두었다. 한 칸에 "목록형 `[21, 63, 126, 252]`, 허용범위 10~504 간격 1"처럼 적으면 어느 쪽이 무엇인지 읽는 사람이 정해야 하고, **§4의 배타 규칙상 그렇게 적힌 축은 아예 만들 수 없다** — 목록형과 입력형을 동시에 채우면 로더가 기동을 실패시킨다.

| 전략 | 축 | 탐색 후보 (`searchValues`) | 허용 범위 (`allowedRange`) | 기본값 | 출처 |
|---|---|---|---|---|---|
| 고정 목표 비중 | `maxWeight` | `[30, 40, 50]` | 입력형 10~100, 5 간격, 단위 % | 40 | 신규 |
| 역변동성 | `lookbackDays` | `[21, 63, 126, 252]` | 목록형 `[21, 63, 126, 252]` | 63 | 007 이관 |
| 역변동성 | `maxWeight` | `[30, 40, 50]` | 입력형 10~100, 5 간격, 단위 % | 40 | 신규 |
| 섹터 모멘텀 | `lookbackDays` | `[20, 60, 120]` | 목록형 `[20, 60, 120]` | 60 | 007 이관 |
| 섹터 모멘텀 | `skipRecentDays` | `[0, 21]` | 목록형 `[0, 5, 10, 21]` | 0 | 신규 |
| 섹터 모멘텀 | `selectionMode` | `[TOP_N]` | 목록형 — 상위 몇 개 / 기준 수익률 이상 / 상위 몇 퍼센트 / 후보 전체 | `TOP_N` | 신규 (2-2 결정) |
| 섹터 모멘텀 | `topN` | `[2, 3, 4]` | 목록형 `[2, 3, 4]` | 3 | 007 이관 |
| 섹터 모멘텀 | `threshold` | `[0]` | 입력형 -20~50, 1 간격, 단위 %(수익률) | 0 | 신규 |
| 섹터 모멘텀 | `topPercent` | `[50]` | 입력형 10~100, 10 간격, 단위 % | 50 | 신규 |
| 섹터 모멘텀 | `allocationMethod` | `[EQUAL]` | 목록형 — 똑같이 나누기 / 변동성이 낮은 섹터에 더 나누기 | `EQUAL` | 신규, 004 PD-03 ⓪ 확정으로 후보 교체(등록표 `1.4.0`) |
| 섹터 모멘텀 | `maxWeight` | `[30, 40, 50]` | 입력형 10~100, 5 간격, 단위 % | 40 | 신규 |
| 최소분산 | `lookbackDays` | `[60, 120, 250]` | 목록형 `[60, 120, 250]` | 120 | 007 이관 |
| 최소분산 | `estimationMethod` | `[SAMPLE_COV]` | 목록형 — 표본공분산 (후보 하나) | `SAMPLE_COV` | 신규, 004 PD-04 대기 |
| 최소분산 | `maxWeight` | `[30, 40, 50]` | 입력형 10~100, 5 간격, 단위 % | 40 | 007 이관 |

숫자가 아닌 후보는 허용 범위 칸에 **화면 문구**로 적었다. 값(`TOP_N`·`EQUAL`·`SAMPLE_COV`)과 그 문구의 짝은 등록표의 `choices`가 소유한다(§3).

**시작 금액** (`analysisConditions.initialAmount`): 입력형, 최소 1,000,000원, 최대 1,000,000,000원, 간격 1,000,000원, 기본 10,000,000원, 단위 원.

**"007 이관"** 값은 `parameter-registry-provisional.yml`에 이미 있던 수치를 그대로 확정값으로 승격한 것이다. **"신규"** 값은 이번에 새로 정했으며, 리밸런싱 주기·비중 상한처럼 범용적인 값은 기존 007 값(예: 최소분산 `maxWeight` 30/40/50)과 같은 폭·간격으로 맞췄다.

**임시 등록표가 목록형과 입력형을 함께 갖던 축은 목록형으로 정리했다**(§4). `lookbackDays`의 "10~504 간격 1" 같은 폭은 그 시절의 흔적이며 정식 등록표에는 없다 — 관측 기간처럼 후보가 정해진 축에 입력칸을 열면 등록되지 않은 값을 고를 경로가 생긴다(FR-006).

**`estimationMethod`가 후보 하나뿐인 이유**: PD-04(004 소유)가 "공분산 관측 기간·추정 방법(표본/축소)·해법"을 아직 확정하지 않았다. 004가 축소추정(shrinkage)을 실제로 구현·확정하기 전에 003이 후보로 얹으면 화면이 004가 계산할 수 없는 선택지를 제공하는 셈이라(원칙 VI), 표본공분산 하나로 시작한다. PD-04가 확정되면 등록표 버전을 올려 후보를 늘린다 — 기존 버전의 값은 고치지 않는다.

## 4-1. 유효성 규칙 (ValidationRule)

전략마다 0개 이상 갖는다. 등록표가 소유하고 화면과 서버가 **같은 규칙을 각자 평가한다**.

| 필드 | 형 | 비고 |
|---|---|---|
| `code` | String | 위반 사유 코드 |
| `detail` | String | 사용자에게 보일 한 문장 |
| `left` / `right` | Operand | `{axis}`·`{context}`·`{value}` 중 하나. `axis`에 `multiplyByContext`를 덧붙일 수 있다 |
| `operator` | Enum | `GREATER_THAN` `GREATER_OR_EQUAL` `LESS_THAN` `LESS_OR_EQUAL` `EQUAL` `NOT_EQUAL` |

**조건이 참이면 위반이다.** 맥락 키는 현재 `UNIVERSE_SIZE` 하나다.

**포트가 규칙 목록을 돌려준다**: 기존 `validate()`는 판정 결과만 주므로 화면이 조건을 알 수 없다. `rules(strategyId)`를 더해 조회 API가 규칙을 받아 응답에 싣는다([계약](contracts/strategy-registry-internal.md) §3).

**화면도 이 규칙을 받아 평가한다**: 목록형 축은 서버가 `choices[].selectable`로 미리 걸러 주지만, 입력형 축(비중 상한 등)은 화면이 값이 바뀔 때마다 직접 평가해야 실행 전에 안내할 수 있다(FR-008). 화면은 축 이름이나 코드로 분기하지 않고 위 네 필드만 기계적으로 평가한다.

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
| `sum` | List\<String \| int\> | 축 이름 또는 상수. 축 이름은 그 축의 현재 값으로 바꿔 읽는다 |

**계산**: `sum` 배열의 모든 항목을 더한 값이 `warmupTradingDays`다. `warmup`이 없으면 0이다.

**상수만 더하는 형태로 두지 않는다**: 섹터 모멘텀의 '최근 구간 제외'는 사용자가 고르는 축이므로, 그 값이 바뀌면 워밍업도 바뀌어야 한다([research](research.md) R4).

**누가 부르나**: 화면이 설정을 바꿀 때마다 다시 계산해 카드를 판정하고(FR-011), 서버가 분석 요청을 받을 때 같은 규칙으로 다시 계산한다. 백테스트에서 실제로 구간을 건너뛰는 것은 [004](../004-backtest-execution/spec.md)다.

**전략별 `sum` 구성**:

004 FR-018이 "가장 긴 관측 기간과 **최근 제외 구간**을 감안한 공통 워밍업"을 요구한다. `sum` 배열이 그 둘을 모두 담는다.

| 전략 | `sum` |
|---|---|
| 고정 목표 비중 | `warmup` 없음 → 0 |
| 동일 비중 | `warmup` 없음 → 0 |
| 역변동성 | `[lookbackDays]` |
| 섹터 모멘텀 | `[lookbackDays, skipRecentDays]` — 둘 다 축이다 |
| 최소분산 | `[lookbackDays]` |

## 7. 분석 조건 등록 정보 (AnalysisConditionRegistry)

| 필드 | 형 | 비고 |
|---|---|---|
| `initialAmount` | AllowedRange + 기본값 | 입력형. 최소 100만·최대 10억·간격 100만·기본 1,000만 원 (PD-02, 2026-09-15 확정, R6) |
| `targetVolatility` | AllowedRange + 기본값 | 입력형. 004 PD-18이 지정한 등록표 항목 (연 5~20%, 1% 간격, 기본 연 10%) |
| `minEvaluationYears` | AllowedRange + 기본값 | 목록형 `[0, 1, 3]`, 기본 1. 문구 `0`=완화(제한 없음), `1`=기본(1년), `3`=엄격(3년)를 **등록표가 `choices`로 담는다** — 숫자지만 값만으로는 뜻이 읽히지 않아(`0`은 "0년"이 아니라 "제한 없음"이다) 화면이 그 대응을 코드에 적지 않게 한다 (spec FR-011a, PD-02 2026-09-15 확정) |

기본값을 별도 필드로 빼지 않고 범위와 한 덩이로 둔다. 조회 응답도 같은 모양이다([계약](contracts/strategy-registry-api.md) §1).

**최소 평가 기간(`minEvaluationYears`)**: 사용자가 '기간·금액·조건'에서 고르며, 워밍업과 별개로 FR-011의 "평가할 구간이 남으면" 판정에 쓰인다. 001의 `GET /sectors/evaluation-window`가 돌려준 공통 평가 가능 범위에서 워밍업을 뺀 뒤 남는 기간이 이 값(연 단위)보다 짧으면 그 전략을 고를 수 없다. 값을 바꾸면 카드 판정과 실행 전 안내가 다시 계산된다(§4.2.2, spec FR-011a).

**목표 변동성은 '목표 위험 맞춤' 시나리오에서만 쓴다.** 주 분석(연속 운용)에서는 화면에 보이지 않는다. 등록표에 자리를 두는 것은 004 PD-18이 그렇게 지정했기 때문이며, 화면에 언제 보일지는 그 시나리오를 만들 때 정한다.

**비용 가정**: 기본 수치는 PD-07이고 소유가 [004](../004-backtest-execution/spec.md)다. 003은 004가 등록한 비용 조건(`cost_profile`)의 현재 버전·수치·`provisional` 여부를 읽기 전용으로 보이고, 파라미터 등록표에 따로 담지 않는다. PD-07 확정 전에는 004가 이미 등록한 `PROVISIONAL-DEMO-1`을 그대로 표시하므로 미확정 자체가 분석 차단 사유는 아니다.

> **확인된 빈 구멍**: 지금 백엔드에 비용 조건을 조회하는 API가 없다. `CostProfileService`와 `CostProfileRepository`는 `backtest` 도메인 안에 있고, 밖으로는 `BacktestController`가 결과에 `costProfileVersion`을 실어 내보낼 뿐이다. 003 화면이 FR-010의 수치·버전·임시 여부를 보이려면 004가 조회 API를 열거나, 004가 소유한 값을 003 등록표 조회 응답에 함께 싣는 계약이 필요하다. 단순히 '적용됨/적용 안 됨'만 보이는 안은 FR-010을 충족하지 않는다. **어느 조회 표면을 쓸지는 004와 함께 정하며 이 계획이 단독으로 정하지 않는다.**

## 8. 전략 설정값 (StrategySettings) — 화면 상태

서버에 저장하지 않는다. 브라우저 탭 수명 동안만 `sessionStorage`에 둔다 (R8).

| 필드 | 형 | 비고 |
|---|---|---|
| `registryVersion` | String | 조회한 등록표 버전. 다르면 보관값을 새 범위·규칙으로 재검증해 유효한 값만 유지하고, 나머지는 복원 선택을 보인다 |
| `selectedStrategyId` | String? | 고른 전략 |
| `valuesByStrategy` | Map\<String, Map\<String, Object\>\> | 전략별로 따로 보관한다. 카드를 옮겨도 이전 전략의 값이 남는다 |
| `analysisCondition` | AnalysisCondition | 아래 9 |

**기본 설정 여부**: 저장하지 않고 매번 계산한다. 그 전략의 모든 축 값이 `defaultValue`와 같으면 '기본 설정', 하나라도 다르면 '설정을 바꿈'이다 (FR-002). 저장하면 값과 표시가 어긋날 수 있다.

## 9. 분석 조건 (AnalysisCondition) — 화면 상태

| 필드 | 형 | 비고 |
|---|---|---|
| `periodStart` / `periodEnd` | date | 001이 산출한 공통 평가 가능 범위 안에서만 (FR-009) |
| `initialAmount` | number | 등록표의 허용 범위 안에서만 |
| `minEvaluationYears` | number | 등록표의 `[0, 1, 3]` 중 하나. 기본 1 (FR-011a) |
| `costProfileVersion` | String | 004가 등록한 비용 조건의 버전. 수치·임시 여부와 함께 읽기 전용 |
| `scenario` | String | 연속 운용 기본 |
| `targetVolatility` | number? | '목표 위험 맞춤' 시나리오일 때만 |

## 10. 분석 요청 (AnalysisRequest) — 004로 넘기는 것

| 필드 | 출처 | 비고 |
|---|---|---|
| 섹터·비중 | [002](../002-sector-weight-selection/spec.md) | 목표 비중은 여기 담긴 값 하나뿐이다 |
| `strategyId` + 축 값 | 8 | |
| 분석 조건 | 9 | |
| `registryVersion` | 8 | 다르면 서버가 409로 거절한다 (R9) |
| `warmupTradingDays` | 6의 계산 결과 | 여러 전략을 비교할 때는 가장 긴 값을 공통으로 쓴다 (FR-014, 004 R-15) |
| `minEvaluationYears` | 9 | **004 계약에 자리 없음 (착수 전에 알아둘 것 ⑧)** — 합의되면 서버가 `periodEnd - periodStart`와 산술로 재확인한다. 그 전까지는 화면의 `useEvaluationWindow` 판정만으로 막는다 (FR-011a) |

`minEvaluationYears`는 원칙 VI(화면이 이미 막았어도 서버가 같은 검증을 다시 한다)에 따라 이 요청에도 실어 서버가 `periodEnd - periodStart` 간격을 산술로 재확인하는 것이 목표 설계다(001을 다시 호출하지 않는다, `0`=완화는 검사 생략). **다만 004의 `POST /backtests` 계약(`conditions`)에 아직 이 필드 자리가 없어 004와 합의가 먼저다**("착수 전에 알아둘 것" ⑧). 합의 전에는 화면의 `useEvaluationWindow` 판정(FR-011a)만으로 막고, 서버 재검증은 합의 뒤 더한다.

## 상태 전이

전략 카드의 선택 가능 여부만 상태를 갖는다.

```
등록표 조회 중        → 모든 카드 선택 불가
등록표 조회 실패      → 모든 카드 선택 불가 + 다시 시도 (FR-018)
등록표 조회 성공
  ├ 비활성 전략        → 카드에 보이지 않음 (R5)
  ├ 워밍업 미충족      → 카드 선택 불가 + 이유 한 문장 (FR-011)
  ├ 워밍업 충족·최소 평가 기간 미달 → 카드 선택 불가 + 이유 한 문장 (FR-011a)
  ├ 워밍업 충족·기간 축소 → 선택 가능 + 줄어든 기간 안내 (FR-011)
  └ 그 외              → 선택 가능
```

설정을 바꿀 때마다 워밍업을 다시 계산해 위 판정을 되풀이한다. 카드 목록에서는 각 전략의 기본 설정을 기준으로 판정한다.
