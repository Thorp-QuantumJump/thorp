# Phase 1 Data Model: 섹터·비중 선택

**Date**: 2026-09-15 | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

spec의 Key Entities를 화면이 다루는 상태 구조로 옮긴다. 002는 서버에 아무것도 저장하지 않으므로 여기 나오는 것은 전부 브라우저 안의 값이다. 검증 규칙은 모두 FR로 근거를 밝힌다.

## 값이 머무는 자리

| 개체 | 사는 곳 | 수명 |
|---|---|---|
| 섹터 목록 (`SectorCatalog`) | React 상태 | 화면을 떠나면 사라진다. 다시 들어오면 `GET /sectors`를 새로 부른다 |
| 선택 상태 (`SectorSelection`) | React 상태 + `sessionStorage` 항목 하나 | 탭 세션 (FR-014) |
| 파생값 (합계·남은 비중·비교 시작일·안내 조건) | 계산해서 쓰고 버린다 | 렌더링마다 |
| 대표 상품 정보 (`InstrumentProfile`) | React 상태 | 패널을 연 동안 |
| 배우기 예시 페이로드 | 라우터 navigation state | 한 번 읽고 지운다 (research.md 결정 9) |

파생값을 저장하지 않는 까닭은 원본과 어긋날 창을 만들지 않기 위해서다. 합계는 언제나 `weightTenths`에서 다시 구한다.

## SectorCatalog: 서버에서 받은 섹터 목록

`GET /sectors` 응답을 화면 타입으로 옮긴 것이다. 필드 정의의 원본은 [001의 섹터 조회 계약](../001-etf-market-data/contracts/sector-api.md)이며 002는 재정의하지 않는다.

| 필드 | 타입 | 용도 | 근거 |
|---|---|---|---|
| `mapVersion` | string | 세션 저장과 다음 단계 인계에 함께 실어 보낸다 | FR-003, 원칙 VIII |
| `isSample` | boolean | true면 예시 대응표 표시를 붙인다 | FR-015, SC-007 |
| `sectors[].code` | string | 선택의 식별자 | FR-003 |
| `sectors[].name` | string | 목록 표시와 정렬 키 | FR-003 |
| `sectors[].classification` | `INDUSTRY` \| `THEME` | 업종/테마 구분 표시와 1차 정렬 키 | FR-003 |
| `sectors[].representativeEtf.name` | string | 목록에 섹터 이름과 함께 보인다 | FR-003, FR-011 |
| `sectors[].representativeEtf.listedOn` | date | 공통 비교 시작일 산출 | FR-008 |

**표시 순서**: `classification`(`INDUSTRY` 먼저) → `name`의 한국어 정렬. 서버 응답의 배열 순서에 기대지 않는다(research.md 결정 2).

**불변식**: 계약이 "모든 항목에 `representativeEtf`가 있다"를 보장하므로, 화면은 대표 ETF가 없는 섹터를 위한 분기를 두지 않는다(spec.md의 User Story 3 시나리오 1). 만약 없는 항목이 오면 그것은 계약 위반이며 화면이 숨겨서 넘기지 않는다.

## SectorSelection: 선택 상태

spec의 Key Entities '섹터 선택'과 '비중 초깃값 표시 상태'를 하나의 구조로 합쳤다. 둘은 같은 시점에 같은 이유로 바뀌기 때문이다.

| 필드 | 타입 | 제약 | 근거 |
|---|---|---|---|
| `mapVersion` | string | 값을 만들 당시의 대응표 버전 | FR-003, 원칙 VIII |
| `selectedCodes` | string[] | 표시 순서로 정규화. 중복 없음 | FR-004 |
| `weightTenths` | Record&lt;code, int&gt; | 키 집합이 `selectedCodes`와 정확히 같다. 값은 0 이상 1000 이하의 정수 | FR-005, FR-016 |
| `weightsTouched` | boolean | 사용자가 비중을 한 번이라도 고쳤는지 | FR-005, SC-009 |
| `droppedCodes` | string[] | 세션 복원 때 현재 목록에 없어 빠진 코드 | 원칙 VI, research.md 결정 3 |

**왜 비중이 정수인가**: 1000이 100%다. 소수로 다루면 합계 판정에 부동소수 오차가 들어와 FR-006·FR-007이 무너진다(research.md 결정 1). 표시할 때만 10으로 나눈다.

**불변식**

1. `Object.keys(weightTenths)`와 `selectedCodes`의 집합이 같다. 비중 없는 선택도, 선택되지 않은 비중도 없다.
2. 모든 값이 `0 <= v <= 1000`인 정수다. 음수와 1000 초과는 입력 단계에서 막는다. 0은 허용한다(FR-016).
3. `selectedCodes`의 모든 코드가 현재 `SectorCatalog`에 있다. 없는 코드는 `droppedCodes`로 옮긴다.
4. 합계가 1000이라는 보장은 **없다.** 합계가 맞지 않는 상태는 오류가 아니라 FR-007이 안내하는 정상 상태다.

## 파생값

원본에서 매번 계산한다. 저장하지 않는다.

| 이름 | 식 | 쓰임 |
|---|---|---|
| `totalTenths` | `selectedCodes`의 `weightTenths` 합 | 합계 판정 |
| `isBalanced` | `totalTenths === 1000` | 다음 단계로 넘어갈 수 있는지 (FR-007) |
| `remainderTenths` | `1000 - totalTenths` | 남은 비중. 음수면 초과 (FR-007) |
| `canProceed` | `selectedCodes.length >= 2 && isBalanced` | 다음 단계 버튼의 활성 조건 (FR-004, FR-007) |
| `commonStartFromListing` | 선택 섹터 `listedOn`의 최댓값 | 상장일 안내 (FR-008) |
| `bindingSectorCode` | 그 최댓값을 만든 섹터 | 어느 섹터 때문인지 (FR-008) |
| `hasListingGap` | 선택 섹터가 둘 이상이고 `listedOn`이 서로 다름 | 상장일 안내를 낼지 (FR-008) |
| `hasZeroWeight` | `weightTenths`에 0이 하나 이상 | 0% 안내를 낼지 (FR-016) |

`commonStartFromListing`은 상장일만 반영한 값이다. 전략 워밍업이 반영된 값은 [003](../003-strategy-settings/spec.md)이 `GET /sectors/evaluation-window`로 받는다(research.md 결정 4).

## 비중 계산 규칙

순수 함수로 두어 화면 없이 단위 테스트한다(SC-002·SC-009·SC-010을 값으로 검증하기 위해서다).

### 동일 비중 채움

```
distributeEven(codes):            # codes는 표시 순서로 정렬되어 있다
  n = codes.length
  q = floor(1000 / n)
  r = 1000 - q * n
  결과: codes[0] -> q + r,  나머지 -> q
```

`r`을 표시 순서상 첫 번째 섹터에 몰아 합계를 정확히 1000으로 맞춘다(FR-005, research.md 결정 2). 초깃값 자동 채움과 '동일하게 나누기'가 같은 함수를 쓴다(FR-007).

| 선택 수 | 결과 (%) |
|---|---|
| 2 | 50.0 / 50.0 |
| 3 | 33.4 / 33.3 / 33.3 |
| 6 | 16.7 / 16.6 / 16.6 / 16.6 / 16.6 / 16.6 |
| 7 | 14.4 / 14.2 × 6 |

### 사용자 입력 파싱

| 입력 | 처리 | 근거 |
|---|---|---|
| `33.4` | `334`로 보관 | FR-006 |
| `33` | `330`으로 보관 | FR-006 |
| `0` | `0`으로 보관. 거부하지 않는다 | FR-016, SC-010 |
| `33.45` | 입력을 받지 않고 한 문장으로 알린다. 반올림하지 않는다 | 원칙 VI, research.md 결정 1 |
| 음수, 100 초과, 숫자가 아닌 값 | 입력을 받지 않는다 | 불변식 2 |
| 빈 값 | 0으로 보지 않고 "아직 입력 중"으로 다룬다. 확정 시점에 이전 값을 유지한다 | 원칙 VI |

## 상태 전이

| 사건 | `selectedCodes` | `weightTenths` | `weightsTouched` | 근거 |
|---|---|---|---|---|
| 섹터 체크 (`weightsTouched === false`) | 추가 후 재정렬 | 전체를 `distributeEven`으로 다시 채움 | 그대로 false | FR-005, SC-009 |
| 섹터 체크 (`weightsTouched === true`) | 추가 후 재정렬 | 기존 값 유지, 새 섹터만 0 | 그대로 true | FR-005, SC-009 |
| 섹터 해제 (`weightsTouched === false`) | 제거 | 남은 섹터를 `distributeEven`으로 다시 채움 | 그대로 false | FR-005 |
| 섹터 해제 (`weightsTouched === true`) | 제거 | 남은 값 그대로. 합계가 1000 아래로 내려가면 FR-007이 안내 | 그대로 true | FR-005, FR-007 |
| 비중 수정 | 그대로 | 그 섹터만 바뀜 | true로 | FR-006 |
| '동일하게 나누기' | 그대로 | 전체를 `distributeEven`으로 | **false로 되돌린다** | FR-007, research.md 결정 10 |
| 배우기 예시 적용 | 예시 값으로 교체 | 예시 값으로 교체 | true로 | FR-012 |
| 배우기 예시 거절 | 그대로 | 그대로 | 그대로 | FR-012, SC-011 |
| 세션 복원 | 검증을 통과한 코드만 | 함께 복원 | 함께 복원 | FR-014, SC-006 |

해제했던 섹터를 다시 고르는 것은 '섹터 체크'와 같다. `weightsTouched`가 true면 이전 비중을 기억하지 않고 0을 준다. 기억해 두었다가 되살리면 사용자가 지운 값이 되돌아오는 것이므로 놀람이 크고, FR-005가 정한 "새로 추가한 섹터에만 0%"와도 어긋난다.

## 세션 저장 페이로드와 다음 단계 인계

두 값 모두 [contracts/selection-handoff.md](./contracts/selection-handoff.md)가 소유한다. 여기서는 구조만 가리킨다.

- **세션 저장**: `SectorSelection`에서 `droppedCodes`를 뺀 나머지. 화면에 표시하기 위한 일시 값은 저장하지 않는다.
- **003 인계**: 같은 값에 선택 섹터의 이름과 대표 ETF 이름을 더하지 않는다. 003이 자기 화면에서 `GET /sectors`를 다시 부르면 되고, 이름을 실어 보내면 대응표 버전이 바뀌었을 때 어느 쪽이 기준인지 흐려진다.

## 대표 상품 정보

`GET /sectors/{code}/instrument-profile` 응답을 그대로 표시한다. 화면은 값을 가공하지 않는다.

| 규칙 | 근거 |
|---|---|
| 패널은 같은 화면에서 펼친다. 다른 화면으로 이동시키지 않는다 | FR-010, 원칙 II |
| `holdingsOverlap.asOfCurrentComposition`이 true면 당시 구성을 재현했다고 표현하지 않는다 | 001 계약, 원칙 V |
| `sourceAttribution`을 그대로 표시한다 | 원칙 X |
| `409 INSTRUMENT_PROFILE_NOT_READY`는 빈 패널이 아니라 한 문장 안내로 처리한다 | 원칙 VI, research.md 결정 6 |
| 이 응답의 `isSample`은 자료 버전 기준이며 `GET /sectors`의 `isSample`(대응표 기준)과 다른 것을 가리킨다. 한쪽 값으로 다른 쪽을 짐작하지 않는다 | 001 계약 |
