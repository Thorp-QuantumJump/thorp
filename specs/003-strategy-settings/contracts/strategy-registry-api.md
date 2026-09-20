# 계약: 전략·파라미터 등록표 조회 API

**Spec**: [spec.md](../spec.md) | **Data model**: [data-model.md](../data-model.md) | **Date**: 2026-09-15

전략 선택 화면이 쓰는 HTTP 계약이다. 화면은 이 응답만으로 전략 카드·상세 설정 항목·허용 범위·기본값을 구성하고, **전략 이름이나 설정 항목을 코드에 따로 두지 않는다**(FR-005).

007이 쓰는 내부 포트 계약은 [strategy-registry-internal.md](strategy-registry-internal.md)에 따로 있다. 둘은 같은 등록표를 읽는다.

**이 문서의 응답 예시는 계약 형식만 검증하는 합성 픽스처다.** `docs/specs/strategy-registry-api/examples/registry-response.json`에 두고 합성 YAML을 읽은 응답과 대조한다. 아래 숫자와 `PROVISIONAL-20260911`은 제품 후보값·범위가 아니며 운영 등록표의 대체값으로 쓰지 않는다. `docs/specs/backtest-api/examples/`가 이미 쓰는 방식이며, 계약과 코드가 어긋나면 빌드가 실패한다.

## 1. 등록표 조회

```
GET /strategies/registry?universeSize={n}
```

로그인이 필요하지 않다. 전략 진단은 비로그인 이용을 허용한다([014](../../014-user-auth/spec.md)).

### 요청 파라미터

| 이름 | 형 | 필수 | 설명 |
|---|---|---|---|
| `universeSize` | 정수 | 선택 | 사용자가 [002](../../002-sector-weight-selection/spec.md)에서 고른 섹터 수. 주면 유효성 제약을 미리 적용해 고를 수 없는 후보를 표시한다 |

`universeSize`를 주지 않으면 제약을 적용하지 않은 전체 등록표를 돌려준다.

### 응답 200

**아래는 `universeSize=3`으로 조회한 응답이다.** 다른 API와 같은 `ApiResponse` 껍데기(`{ success, data,
error }`)를 쓰며, 이 예시는 그 안의 `data`다. 같은 내용이
[`docs/specs/strategy-registry-api/examples/registry-response.json`](../../../docs/specs/strategy-registry-api/examples/registry-response.json)에
픽스처로 있고 계약 테스트가 실제 응답과 대조한다.

```json
{
  "registryVersion": "PROVISIONAL-20260911",
  "provisional": true,
  "analysisConditions": {
    "initialAmount": {
      "min": 1000000,
      "max": 1000000000,
      "step": 1000000,
      "default": 10000000,
      "unit": "원"
    },
    "targetVolatility": {
      "min": 5,
      "max": 20,
      "step": 1,
      "default": 10,
      "unit": "퍼센트(연)",
      "usedBy": "TARGET_RISK"
    },
    "minEvaluationYears": {
      "choices": [
        { "value": 0, "label": "완화(제한 없음)" },
        { "value": 1, "label": "기본(1년)" },
        { "value": 3, "label": "엄격(3년)" }
      ],
      "default": 1,
      "unit": "년"
    }
  },
  "strategies": [
    {
      "strategyId": "INVERSE_VOLATILITY",
      "name": "변동성에 따라 나누기",
      "description": "최근 변동성이 낮은 섹터에 더 큰 비중을 둔다.",
      "targetWeightSource": "COMPUTED",
      "selectable": true,
      "warmupTradingDays": 63,
      "axes": [
        {
          "name": "lookbackDays",
          "label": "변동성 관측 기간",
          "description": "변동성을 재는 데 쓸 과거 구간의 길이다. 길수록 최근 변화에 둔해진다.",
          "unit": "거래일",
          "inputType": "CHOICE",
          "choices": [
            { "value": 21, "label": "21거래일", "selectable": true },
            { "value": 63, "label": "63거래일", "selectable": true },
            { "value": 126, "label": "126거래일", "selectable": true },
            { "value": 252, "label": "252거래일", "selectable": true }
          ],
          "defaultValue": 63,
          "termId": "volatility"
        },
        {
          "name": "maxWeight",
          "label": "비중 상한",
          "description": "한 섹터에 넣을 수 있는 최대 비중이다. 잘린 몫은 여유가 있는 섹터에 비중 비율대로 다시 나눈다.",
          "unit": "퍼센트",
          "inputType": "NUMBER",
          "range": { "min": 10, "max": 100, "step": 5 },
          "defaultValue": 40,
          "termId": "weight"
        }
      ],
      "validationRules": [
        {
          "code": "MAX_WEIGHT_TOO_LOW_FOR_UNIVERSE",
          "detail": "섹터당 최대 비중이 낮아 후보 섹터만으로 전액을 배분할 수 없습니다.",
          "left": { "axis": "maxWeight", "multiplyByContext": "UNIVERSE_SIZE" },
          "operator": "LESS_THAN",
          "right": { "value": 100 }
        }
      ],
      "warmupRule": { "sum": ["lookbackDays"] }
    },
    {
      "strategyId": "SECTOR_MOMENTUM",
      "name": "흐름을 따라 배분하기",
      "description": "최근 상승 폭이 큰 섹터를 골라 배분한다.",
      "targetWeightSource": "COMPUTED",
      "selectable": false,
      "unselectableReason": "선정 섹터 수가 후보 섹터 집합의 크기보다 큽니다.",
      "warmupTradingDays": 81,
      "axes": [
        {
          "name": "lookbackDays",
          "label": "관측 기간",
          "description": "상승 폭을 재는 데 쓸 과거 구간의 길이다.",
          "unit": "거래일",
          "inputType": "CHOICE",
          "choices": [
            { "value": 20, "label": "20거래일", "selectable": true },
            { "value": 60, "label": "60거래일", "selectable": true },
            { "value": 120, "label": "120거래일", "selectable": true }
          ],
          "defaultValue": 60
        },
        {
          "name": "skipRecentDays",
          "label": "최근 구간 제외 기간",
          "description": "관측 구간의 맨 끝을 이만큼 빼고 본다. 직전 반등이 판단을 흔드는 것을 줄인다.",
          "unit": "거래일",
          "inputType": "CHOICE",
          "choices": [
            { "value": 0, "label": "0거래일", "selectable": true },
            { "value": 5, "label": "5거래일", "selectable": true },
            { "value": 10, "label": "10거래일", "selectable": true },
            { "value": 21, "label": "21거래일", "selectable": true }
          ],
          "defaultValue": 21
        },
        {
          "name": "selectionMode",
          "label": "선정 방식",
          "description": "어떤 기준으로 섹터를 추릴지 정한다.",
          "inputType": "CHOICE",
          "choices": [
            { "value": "TOP_N", "label": "상위 몇 개", "selectable": true },
            { "value": "THRESHOLD", "label": "기준 수익률 이상", "selectable": true },
            { "value": "TOP_PERCENT", "label": "상위 몇 퍼센트", "selectable": true }
          ],
          "defaultValue": "TOP_N"
        },
        {
          "name": "topN",
          "label": "선정 섹터 수",
          "description": "선정 방식이 '상위 몇 개'일 때 고를 섹터의 개수다.",
          "inputType": "CHOICE",
          "choices": [
            { "value": 2, "label": "2", "selectable": false },
            { "value": 3, "label": "3", "selectable": true },
            { "value": 4, "label": "4", "selectable": false }
          ],
          "defaultValue": 4,
          "appliesWhen": { "axis": "selectionMode", "value": "TOP_N" }
        },
        {
          "name": "topPercent",
          "label": "상위 비율",
          "description": "선정 방식이 '상위 몇 퍼센트'일 때 후보 중 고를 비율이다.",
          "unit": "퍼센트",
          "inputType": "NUMBER",
          "range": { "min": 10, "max": 100, "step": 10 },
          "defaultValue": 50,
          "appliesWhen": { "axis": "selectionMode", "value": "TOP_PERCENT" }
        },
        {
          "name": "allocationMethod",
          "label": "배분 방식",
          "description": "고른 섹터에 금액을 나누는 방식이다.",
          "inputType": "CHOICE",
          "choices": [
            { "value": "EQUAL", "label": "똑같이 나누기", "selectable": true },
            { "value": "INVERSE_VOLATILITY", "label": "변동성이 낮은 섹터에 더 나누기", "selectable": true }
          ],
          "defaultValue": "EQUAL"
        },
        {
          "name": "maxWeight",
          "label": "섹터당 최대 비중",
          "description": "한 섹터에 넣을 수 있는 최대 비중이다.",
          "unit": "퍼센트",
          "inputType": "NUMBER",
          "range": { "min": 10, "max": 100, "step": 5 },
          "defaultValue": 40
        }
      ],
      "validationRules": [
        {
          "code": "TOP_N_EXCEEDS_UNIVERSE",
          "detail": "선정 섹터 수가 후보 섹터 집합의 크기보다 큽니다.",
          "left": { "axis": "topN" },
          "operator": "GREATER_THAN",
          "right": { "context": "UNIVERSE_SIZE" }
        },
        {
          "code": "MAX_WEIGHT_TOO_LOW_FOR_SELECTION",
          "detail": "섹터당 최대 비중이 낮아 선정할 섹터 수만으로 전액을 배분할 수 없습니다.",
          "left": { "axis": "maxWeight", "multiplyByAxis": "topN" },
          "operator": "LESS_THAN",
          "right": { "value": 100 }
        },
        {
          "code": "MAX_WEIGHT_TOO_LOW_FOR_SELECTION",
          "detail": "섹터당 최대 비중이 낮아 선정할 섹터 수만으로 전액을 배분할 수 없습니다.",
          "left": {
            "axis": "maxWeight",
            "multiplyByCeil": {
              "context": "UNIVERSE_SIZE",
              "axis": "topPercent",
              "divisor": 100
            }
          },
          "operator": "LESS_THAN",
          "right": { "value": 100 }
        }
      ],
      "warmupRule": { "sum": ["lookbackDays", "skipRecentDays"] }
    }
  ]
}
```

> 위 수치는 계약 형식을 보이려는 합성 값이다. 두 번째 전략은 기본 설정(`topN: 4`)이 섹터 셋이라는 조건에서
> 규칙을 어기므로 `selectable: false`이고, 그 축의 후보 `4`도 같은 이유로 `selectable: false`이되 목록에 남는다.
> 값이 없는 필드(`unit`이 없는 축, 워밍업이 없는 전략의 `warmupRule`)는 `null`로 싣지 않고 생략한다.

### 필드

| 필드 | 형 | 설명 |
|---|---|---|
| `registryVersion` | string | 분석 요청에 그대로 실어 보낸다 |
| `provisional` | boolean | true면 출처가 있는 기존 임시값 또는 합성 픽스처다. 화면과 분석 결과가 그 사실을 계속 표시하며 확정값으로 표현하지 않는다. 임시 여부만으로 차단하지 않고 다른 실행 조건을 모두 검증한다(FR-005a) |
| `strategies[].targetWeightSource` | `USER` \| `EQUAL` \| `COMPUTED` | 목표 비중이 어디에서 오는가. `USER`면 화면이 [002](../../002-sector-weight-selection/spec.md)가 확정한 비중을 읽기 전용으로 보이고, `COMPUTED`면 FR-012의 자동 배분 설명을 보인다. **화면이 전략 ID로 분기하지 않게 하는 값이다** |
| `strategies[].selectable` | boolean | 카드를 고를 수 있는가. `universeSize`를 주면 유효성 제약과 활성 여부를 반영한다 |
| `strategies[].unselectableReason` | string? | `selectable`이 false일 때만. 화면이 그대로 한 문장으로 보인다 |
| `strategies[].warmupTradingDays` | integer | 기본 설정 기준 워밍업. 카드 목록의 판정에 쓴다 |
| `strategies[].warmupRule` | object | 설정을 바꿀 때 화면이 다시 계산하는 규칙. `sum` 배열의 축 이름을 현재 값으로 바꿔 더한다 (data-model 6) |
| `axes[].description` | string? | 항목의 의미 한 문장. **화면이 축 이름으로 분기해 설명을 고르지 않게 하는 자리다**([research](../research.md) R7-1) — FR-019의 상한 재배분 안내 문장도 여기에 온다 |
| `axes[].inputType` | `CHOICE` \| `NUMBER` | 목록형인가 입력형인가 |
| `axes[].choices` | array | `CHOICE`일 때만. `selectable`이 false인 후보는 유효성 제약에 걸린 값이다 |
| `axes[].choices[].label` | string | 후보를 사람에게 보일 문구. **화면이 만들지 않는다** — `TOP_N`이나 `{mode: CALENDAR, unit: MONTH_END}`가 무슨 뜻인지는 등록표만 안다. 등록표가 문구를 적지 않은 숫자 후보만 서버가 값과 `unit`으로 만든다(`21` + `거래일`) |
| `analysisConditions.*.choices` | array | 목록형 조건일 때만. `{ value, label }`이며 전략 축의 `choices`와 달리 `selectable`이 없다 — 유효성 규칙은 전략 축에만 걸린다. `minEvaluationYears`의 `0`이 "0년"이 아니라 "제한 없음"이라는 것도 이 문구가 말한다(FR-011a) |
| `axes[].range` | object | `NUMBER`일 때만 |
| `axes[].defaultValue` | any | 등록표의 기본 시작값. 반드시 `choices` 또는 `range` 안의 값이다 |
| `axes[].appliesWhen` | object? | `{ axis, value }`. **같은 전략의 `axis`가 `value`일 때만 이 축을 쓴다**(FR-003). 없으면 늘 쓴다. 화면은 조건이 거짓인 축을 그리지도 요청에 싣지도 않고, 기본값도 채우지 않는다. 결정 축의 값이 바뀌어 조건이 참이 되면 그 축은 기본값에서 시작한다. 서버의 접수 관문도 같은 조건으로 "이 설정에서 뜻을 갖는 축"만 요구한다(§2). 비교는 `equals`뿐이며 화면이 축 이름을 해석하지 않는다 |
| `axes[].termId` | string? | [013](../../013-learning-pages/spec.md) 용어 설명의 식별자([013 data-model §8](../../013-learning-pages/data-model.md)). 있으면 화면이 그 항목 옆에 용어 도움말을 붙인다(FR-016). **화면이 축 이름으로 식별자를 고르지 않게 하는 자리다** |
| `analysisConditions.*.default` | any | 분석 조건의 기본값. 범위 안에 함께 싣는다 |
| `strategies[].validationRules` | array | 화면이 실행 전에 평가할 조건. 아래 절 참조 |
| `analysisConditions.targetVolatility.usedBy` | string | `TARGET_RISK` — '목표 위험 맞춤' 시나리오에서만 쓴다. 주 분석 화면에 보이지 않는다 (004 PD-18) |

**비활성 전략은 목록에 실리지 않는다.** `selectable: false`는 활성이되 지금 조건에서 고를 수 없다는 뜻이다.

**`choices[].selectable`**: 예를 들어 섹터를 둘 골랐으면 모멘텀 선정 수의 후보 3·4는 `selectable: false`로 실린다. 화면은 이 값을 흐리게 보이거나 감춘다. 목록에서 아예 빼지 않는 것은 사용자가 왜 고를 수 없는지 알 수 있어야 하기 때문이다.

**후보 하나를 판정하는 방법**: 기본 설정에서 **그 축의 값 하나만** 후보로 바꾼 조합을 만들고, **그 축을 피연산자로 참조하는 규칙만** 평가한다. 그 축과 무관한 규칙까지 함께 보면, 다른 축 때문에 생긴 위반이 이 축의 후보를 고를 수 없는 것처럼 보인다 — 선정 수가 후보 섹터보다 많다는 이유로 관측 기간 후보까지 전부 잠기면 사용자는 틀린 안내를 받는다. 전략 카드 자체의 `selectable`은 기본 설정 전체를 모든 규칙으로 판정한 결과이며, 이 둘은 서로 다른 판정이다.

**`searchValues`는 이 응답에 싣지 않는다.** 자동 탐색 후보는 007이 내부 포트로 직접 읽고, 003 화면은 쓸 일이 없다. 뜻과 비용이 다른 두 목록을 화면에 함께 내리면 혼동만 생긴다([research](../research.md) R3).

### 유효성 규칙 (`validationRules`)

**화면이 판정할 수 있도록 조건을 그대로 내린다.** `code`와 `detail`만 주면 목록형 축은 서버가 미리 걸러 주니 괜찮지만, 입력형 축은 화면이 위반을 알 방법이 없다. 사용자가 비중 상한에 30을 입력하고 섹터가 셋이면 위반인데, 조건이 없으면 '분석하기'를 누른 뒤에야 알게 된다. spec FR-008은 **실행 전**에 안내하라고 요구한다.

| 필드 | 설명 |
|---|---|
| `code` | 위반 사유 코드. 화면과 서버가 같은 값을 쓴다 |
| `detail` | 사용자에게 보일 한 문장. 화면이 문구를 만들지 않는다 |
| `left` / `right` | 피연산자. `{ axis: <축 이름> }`에 `multiplyByAxis: <다른 축 이름>`을 덧붙이면 두 축의 곱이다(예: `maxWeight × topN`, 등록표 `1.4.0`). `multiplyByCeil: { context, axis, divisor }`를 덧붙이면 `축 값 × ceil(맥락 값 × 다른 축 값 ÷ divisor)`다(예: `maxWeight × ceil(UNIVERSE_SIZE × topPercent ÷ 100)`, 등록표 `1.5.0`). 곱할 축이 그 설정에 없으면 판정하지 않는다. `{ context: <맥락 키> }`·`{ value: <상수> }` 중 하나. `axis`에는 `multiplyByContext: <맥락 키>`를 덧붙여 곱할 수 있다. 세 곱셈 형식은 함께 쓰지 않는다 |
| `operator` | `GREATER_THAN` \| `GREATER_OR_EQUAL` \| `LESS_THAN` \| `LESS_OR_EQUAL` \| `EQUAL` \| `NOT_EQUAL` |

**조건이 참이면 위반이다**(그 설정으로 분석을 시작하지 않는다). 맥락 키는 현재 `UNIVERSE_SIZE`(섹터 후보 집합의 크기) 하나다.

이 문법은 등록표가 이미 쓰는 것과 같다. 화면은 규칙의 **뜻을 해석하지 않고** 위 네 필드만 기계적으로 평가한다 — 축 이름이나 코드로 분기하면 그것이 전략 지식의 중복이다.

**서버도 같은 규칙으로 다시 판정한다.** 화면 평가는 사용자를 돕기 위한 것이고, 접수 시점의 판정은 서버가 등록표로 수행한다.

### 응답 503

아래는 `ApiResponse`의 `error`에 담기는 내용이다.

```json
{ "code": "REGISTRY_UNAVAILABLE", "message": "전략 설정을 불러오지 못했습니다." }
```

화면은 전략 카드를 모두 고를 수 없는 상태로 두고 다시 시도할 방법을 보인다(FR-018). **임시 값이나 예비 등록표로 대신 동작하지 않는다.**

## 2. 분석 요청에 실리는 필드

분석 요청 자체는 [004](../../004-backtest-execution/spec.md)의 계약이다(`docs/specs/backtest-api/README.md` §1). 003이 채워 보내는 필드만 **004 요청의 경로 이름으로** 적는다. 섹터 배분(`sectorAllocation`)은 002 값을 옮긴 것이라 여기 적지 않는다.

| 경로 | 형 | 출처와 비고 |
|---|---|---|
| `executionType` | string | 이 화면은 늘 `SINGLE` |
| `strategy.strategyId` | string | 003 등록표의 활성 전략 |
| `strategy.rulesVersion` | string | 004 `GET /backtests/registry`의 `strategyRules`에서 고른 전략의 `REGISTERED` 행 버전. `PENDING_DECISION`이거나 행이 없거나 등록된 행이 둘 이상이면 요청을 보내지 않는다. 대체된 옛 버전이면 409가 목표 계약이나 서버의 버전 수명주기는 004 Phase 10 T029 미구현이다. 현재는 REGISTERED 행을 허용한다 |
| `strategy.registryVersion` | string | 조회한 003 등록표 버전. 접수 시점과 다르면 409 |
| `strategy.parameters` | object | 축 이름 → 값. 등록표의 허용 범위 안이어야 한다. **`appliesWhen`이 거짓인 축은 싣지 않는다** — 서버는 이 설정에서 뜻을 갖는 축의 집합과 정확히 같아야 받는다(FR-003) |
| `conditions.start` / `conditions.end` | date | 001이 산출한 공통 평가 가능 범위 안. 사용자가 고르지 않았으면 범위 전체 |
| `conditions.initialCash` | string (decimal) | 등록표 `analysisConditions.initialAmount`의 허용 범위 안. 소수점 둘째 자리까지의 문자열 |
| `conditions.costProfileVersion` | string | 004 `GET /backtests/registry`의 `defaultCostProfile.version`을 **명시해** 싣는다. 비우면 조회와 접수 사이에 기본값이 바뀌었을 때 사용자가 본 적 없는 비용 조건이 적용된다 (2026-09-17 004 결정) |
| `conditions.warmupTradingDays` | integer | 워밍업 규칙의 계산 결과 |
| `conditions.scenario` | string | 연속 운용(`FULL_PERIOD`)이 기본 |
| `conditions.scenarioParams.targetVolatility` | string? | `scenario`가 '목표 위험 맞춤'일 때만 (004 PD-18). 주 분석 화면은 싣지 않는다 |

`minEvaluationYears`는 004 요청에 자리가 없어 싣지 않는다("착수 전에 알아둘 것" ⑧, data-model §10).

### 거절 응답

| 상태 | 코드 | 언제 | 화면 처리 |
|---|---|---|---|
| 400 | `UNREGISTERED_PARAMETER_VALUE` | 축 값이 허용 범위 밖 | 허용 범위를 안내하고 값을 저장하지 않는다 |
| 409 | `REGISTRY_VERSION_CHANGED` | 요청의 등록표 버전이 현재와 다름. 규칙 버전(004)의 대체 거절은 Phase 10 T029 구현 후 적용 | 등록표와 004 규칙 등록표를 모두 다시 조회하고 바뀐 사실을 알린다. 자동으로 다시 보내지 않는다 (research R9, 2026-09-17 004 결정) |
| 422 | `STRATEGY_NOT_REGISTERED` | 등록표 활성 전략에 없음 | 전략 선택으로 되돌린다 |
| 422 | `VALIDATION_RULE_VIOLATED` | 유효성 제약 위반 | 위반한 규칙의 `detail`을 보인다 |
| 503 | `REGISTRY_UNAVAILABLE` | 등록표를 읽지 못함 | 다시 시도를 제안한다 |

**화면의 안내는 사용자를 돕기 위한 것이고, 서버가 실행 직전에 같은 검증을 다시 한다**(FR-006). 화면 검증을 통과한 요청도 서버가 거절할 수 있다.

## 3. 이 계약이 지키는 것

| spec 요구사항 | 이 계약에서 |
|---|---|
| FR-005 등록표는 서버가 보관하는 진실의 원본 | 화면이 전략·축·범위를 코드에 두지 않고 이 응답만 쓴다 |
| FR-006 목록형·입력형 구분, 서버 재검증 | `inputType`과 거절 응답 |
| FR-011 워밍업 판정 | `warmupTradingDays`와 `warmupRule` |
| FR-018 조회 실패 시 막는다 | 503과 그 처리 |
| FR-008 유효성 위반을 실행 전에 안내 | `validationRules`의 조건을 화면이 평가하고, 서버가 접수 시점에 다시 본다 |
| FR-014 등록표 버전을 요청에 싣는다 | `registryVersion`과 409 |

### 조회 실패 복구 (FR-018a·FR-018b)

003 등록표 조회·재시도 중에는 두 행동 버튼을 막고, 성공 후 보관값을 재검증한다. 004 규칙·비용 조회 실패는 분석만 막으며 '분석 조건 다시 불러오기'를 제공한다. 재시도 중 중복 조회를 막고 성공해도 분석 요청은 자동 전송하지 않는다. 자동 비교 진입은 FR-013의 별도 조건을 따른다.
