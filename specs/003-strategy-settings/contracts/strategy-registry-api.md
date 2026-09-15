# 계약: 전략·파라미터 등록표 조회 API

**Spec**: [spec.md](../spec.md) | **Data model**: [data-model.md](../data-model.md) | **Date**: 2026-09-15

전략 선택 화면이 쓰는 HTTP 계약이다. 화면은 이 응답만으로 전략 카드·상세 설정 항목·허용 범위·기본값을 구성하고, **전략 이름이나 설정 항목을 코드에 따로 두지 않는다**(FR-005).

007이 쓰는 내부 포트 계약은 [strategy-registry-internal.md](strategy-registry-internal.md)에 따로 있다. 둘은 같은 등록표를 읽는다.

**이 문서의 응답 예시는 픽스처로 뺀다.** `docs/specs/strategy-registry-api/examples/registry-response.json`에 두고 테스트가 그것을 읽어 실제 응답과 대조한다. `docs/specs/backtest-api/examples/`가 이미 쓰는 방식이며, 계약과 코드가 어긋나면 빌드가 실패한다.

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
    }
  },
  "strategies": [
    {
      "strategyId": "INVERSE_VOLATILITY",
      "name": "변동성에 따라 나누기",
      "description": "최근 변동성이 낮은 섹터에 더 큰 비중을 둔다.",
      "selectable": true,
      "warmupTradingDays": 63,
      "axes": [
        {
          "name": "lookbackDays",
          "label": "변동성 관측 기간",
          "unit": "거래일",
          "inputType": "CHOICE",
          "choices": [
            { "value": 21, "label": "21거래일", "selectable": true },
            { "value": 63, "label": "63거래일", "selectable": true },
            { "value": 126, "label": "126거래일", "selectable": true },
            { "value": 252, "label": "252거래일", "selectable": true }
          ],
          "defaultValue": 63
        },
        {
          "name": "maxWeight",
          "label": "비중 상한",
          "unit": "퍼센트",
          "inputType": "NUMBER",
          "range": { "min": 10, "max": 100, "step": 5 },
          "defaultValue": 40
        }
      ],
      "validationRules": [
        {
          "code": "MAX_WEIGHT_TOO_LOW_FOR_UNIVERSE",
          "detail": "섹터당 최대 비중이 낮아 후보 섹터만으로 전액을 배분할 수 없습니다."
        }
      ],
      "warmupRule": { "sum": ["lookbackDays"] }
    }
  ]
}
```

### 필드

| 필드 | 형 | 설명 |
|---|---|---|
| `registryVersion` | string | 분석 요청에 그대로 실어 보낸다 |
| `provisional` | boolean | true면 팀 확정 전 임시값이다. 화면이 그 사실을 계속 표시한다 |
| `strategies[].selectable` | boolean | 카드를 고를 수 있는가. `universeSize`를 주면 유효성 제약과 활성 여부를 반영한다 |
| `strategies[].unselectableReason` | string? | `selectable`이 false일 때만. 화면이 그대로 한 문장으로 보인다 |
| `strategies[].warmupTradingDays` | integer | 기본 설정 기준 워밍업. 카드 목록의 판정에 쓴다 |
| `strategies[].warmupRule` | object | 설정을 바꿀 때 화면이 다시 계산하는 규칙. `sum` 배열의 축 이름을 현재 값으로 바꿔 더한다 (data-model 6) |
| `axes[].inputType` | `CHOICE` \| `NUMBER` | 목록형인가 입력형인가 |
| `axes[].choices` | array | `CHOICE`일 때만. `selectable`이 false인 후보는 유효성 제약에 걸린 값이다 |
| `axes[].range` | object | `NUMBER`일 때만 |
| `axes[].defaultValue` | any | 등록표의 기본 시작값. 반드시 `choices` 또는 `range` 안의 값이다 |
| `analysisConditions.targetVolatility.usedBy` | string | `TARGET_RISK` — '목표 위험 맞춤' 시나리오에서만 쓴다. 주 분석 화면에 보이지 않는다 (004 PD-18) |

**비활성 전략은 목록에 실리지 않는다.** `selectable: false`는 활성이되 지금 조건에서 고를 수 없다는 뜻이다.

**`choices[].selectable`**: 예를 들어 섹터를 둘 골랐으면 모멘텀 선정 수의 후보 3·4는 `selectable: false`로 실린다. 화면은 이 값을 흐리게 보이거나 감춘다. 목록에서 아예 빼지 않는 것은 사용자가 왜 고를 수 없는지 알 수 있어야 하기 때문이다.

### 응답 503

```json
{ "code": "REGISTRY_UNAVAILABLE", "message": "전략 설정을 불러오지 못했습니다." }
```

화면은 전략 카드를 모두 고를 수 없는 상태로 두고 다시 시도할 방법을 보인다(FR-018). **임시 값이나 예비 등록표로 대신 동작하지 않는다.**

## 2. 분석 요청에 실리는 필드

분석 요청 자체는 [004](../../004-backtest-execution/spec.md)의 계약이다. 003이 채워 보내는 필드만 적는다.

| 필드 | 형 | 비고 |
|---|---|---|
| `strategyId` | string | 등록표의 활성 전략 |
| `parameters` | object | 축 이름 → 값. 등록표의 허용 범위 안이어야 한다 |
| `registryVersion` | string | 조회한 버전. 접수 시점과 다르면 409 |
| `warmupTradingDays` | integer | 워밍업 규칙의 계산 결과 |
| `initialAmount` | number | 등록표의 허용 범위 안 |
| `periodStart` / `periodEnd` | date | 001이 산출한 공통 평가 가능 범위 안 |

### 거절 응답

| 상태 | 코드 | 언제 | 화면 처리 |
|---|---|---|---|
| 400 | `UNREGISTERED_PARAMETER_VALUE` | 축 값이 허용 범위 밖 | 허용 범위를 안내하고 값을 저장하지 않는다 |
| 409 | `REGISTRY_VERSION_CHANGED` | 요청의 버전이 현재와 다름 | 등록표를 다시 조회하고 바뀐 사실을 알린다 (research R9) |
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
| FR-014 등록표 버전을 요청에 싣는다 | `registryVersion`과 409 |
