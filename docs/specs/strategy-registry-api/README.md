# 전략·파라미터 등록표 조회 계약 (Backend ↔ Frontend)

**원본은 [`specs/003-strategy-settings/contracts/strategy-registry-api.md`](../../../specs/003-strategy-settings/contracts/strategy-registry-api.md)이다.**
이 문서는 화면과 서버가 함께 보는 요약이며, 필드의 뜻과 판정 규칙은 원본이 정한다.

007이 같은 등록표를 읽는 내부 포트 계약은
[`specs/003-strategy-settings/contracts/strategy-registry-internal.md`](../../../specs/003-strategy-settings/contracts/strategy-registry-internal.md)에 따로 있다.

**예시 파일이 계약의 기준이다.** 백엔드 계약 테스트(`StrategyRegistryContractTest`)가 이 파일을 읽어
실제 응답과 대조하므로, 계약과 구현이 어긋나면 빌드가 실패한다.

- 200 응답의 `data`: [`examples/registry-response.json`](examples/registry-response.json)

## 1. 등록표 조회

```
GET /strategies/registry?universeSize={n}
```

**로그인이 필요하지 않다.** 전략 진단은 비로그인 이용을 허용한다(spec 014).

| 이름 | 형 | 필수 | 규칙 |
|---|---|---|---|
| `universeSize` | `integer` | 선택 | 사용자가 spec 002에서 고른 섹터 수. 1 이상. 주면 유효성 제약을 미리 적용해 고를 수 없는 전략과 후보를 표시한다. 주지 않으면 제약을 적용하지 않는다 |

응답은 다른 API와 같은 `ApiResponse` 껍데기를 쓴다. 위 예시 파일은 그 안의 `data`다.

### 응답 200의 주요 필드

| 필드 | 형 | 설명 |
|---|---|---|
| `registryVersion` | `string` | 분석 요청에 그대로 실어 보낸다. 접수 시점과 다르면 409다 |
| `provisional` | `boolean` | `registryVersion`이 `PROVISIONAL-`로 시작하는가. `true`면 화면이 임시값임을 계속 표시한다 |
| `analysisConditions` | `object` | 전략과 무관한 공통 조건(시작 금액·목표 변동성·최소 평가 기간). 허용 범위와 기본값을 한 덩이로 담는다 |
| `strategies[]` | `array` | **활성 전략만.** 비활성 전략은 아예 실리지 않는다 |
| `strategies[].targetWeightSource` | `string` | `USER` \| `EQUAL` \| `COMPUTED`. 화면이 목표 비중을 읽기 전용으로 보일지 자동 배분 설명을 보일지 이 값으로 정한다 |
| `strategies[].selectable` | `boolean` | 활성이되 지금 조건에서 고를 수 있는가. `universeSize`를 주면 기본 설정으로 유효성 제약을 평가한 결과다 |
| `strategies[].unselectableReason` | `string?` | `selectable`이 `false`일 때만. 위반한 규칙의 `detail` 그대로다 |
| `strategies[].warmupTradingDays` | `integer` | 기본 설정 기준 워밍업 거래일 수 |
| `strategies[].warmupRule` | `object?` | 설정이 바뀔 때 화면이 다시 계산하는 규칙. 없으면 워밍업 0이다 |
| `axes[].description` | `string?` | 항목의 의미 한 문장. **화면이 축 이름으로 분기해 설명을 고르지 않게 하는 자리다** |
| `axes[].inputType` | `string` | `CHOICE` \| `NUMBER` |
| `axes[].choices[]` | `array` | `CHOICE`일 때만. 고를 수 없는 후보도 `selectable: false`로 **목록에 남는다** |
| `axes[].choices[].label` | `string` | 후보를 보일 문구. **화면이 만들지 않는다** — `TOP_N`이 무슨 뜻인지는 등록표만 안다. 등록표가 문구를 적지 않은 숫자 후보만 서버가 값과 `unit`으로 만든다 |
| `analysisConditions.*.choices[]` | `array` | 목록형 조건일 때만. `{ value, label }`이며 `selectable`이 없다 — 유효성 규칙은 전략 축에만 걸린다 |
| `axes[].range` | `object` | `NUMBER`일 때만. `min`·`max`·`step` |
| `validationRules[]` | `array` | 화면이 실행 전에 그 자리에서 평가할 조건. `universeSize`와 무관하게 항상 실린다 |

`searchValues`는 이 응답에 싣지 않는다. 자동 탐색 후보는 007이 내부 포트로 직접 읽는다.

### 응답 503

등록표를 읽지 못하면 `REGISTRY_UNAVAILABLE`이다. **임시 값이나 예비 등록표로 대신 동작하지 않는다.**
화면은 전략 카드를 모두 고를 수 없는 상태로 두고 다시 시도할 방법을 보인다.

## 2. 유효성 규칙을 화면이 평가한다

목록형 축은 서버가 `choices[].selectable`로 미리 걸러 주지만, 입력형 축은 사용자가 무엇을 넣을지
알 수 없어 서버가 미리 걸러 줄 수 없다. 그래서 규칙의 조건을 그대로 내리고 화면이 값이 바뀔 때마다
평가한다.

| 필드 | 설명 |
|---|---|
| `code` | 위반 사유 코드. 화면과 서버가 같은 값을 쓴다 |
| `detail` | 사용자에게 보일 한 문장. 화면이 문구를 만들지 않는다 |
| `left` / `right` | `{ axis }`·`{ context }`·`{ value }` 중 하나. `axis`에 `multiplyByAxis`(다른 축 이름)를 덧붙이면 두 축의 곱이고 곱할 축이 없는 설정에서는 판정하지 않는다(`1.4.0`). `multiplyByContext`를 덧붙여 곱할 수 있다 |
| `operator` | `GREATER_THAN` \| `GREATER_OR_EQUAL` \| `LESS_THAN` \| `LESS_OR_EQUAL` \| `EQUAL` \| `NOT_EQUAL` |

**조건이 참이면 위반이다.** 맥락 키는 현재 `UNIVERSE_SIZE` 하나다.

화면은 규칙의 **뜻을 해석하지 않고** 위 네 필드만 기계적으로 평가한다. 축 이름이나 코드로 분기하면
그것이 전략 지식의 중복이다.

**서버가 접수 시점에 같은 규칙으로 다시 판정한다.** 화면 평가는 사용자를 돕기 위한 것이므로,
화면 검증을 통과한 요청도 서버가 거절할 수 있다.
