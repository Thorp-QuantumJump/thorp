# 계약: '설정 바꿔 비교'의 화면 사이 인계

**Spec**: [../spec.md](../spec.md) FR-007 | **Research**: [../research.md](../research.md) R4

서버 API가 아니라 **화면 사이의 계약**이다. 006의 기록 상세에서 '설정 바꿔 비교'를 누르면 저장된
조건이 003 전략 화면에 채워져 열린다.

**006은 새 저장 형식을 만들지 않는다.** 이미 있는 두 계약에 값을 쓰는 쪽에 선다.

| 저장소 키 | 소유 | 006의 역할 |
|---|---|---|
| `thorp.diagnosis.sectorSelection` | [002 selection-handoff 계약](../../002-sector-weight-selection/contracts/selection-handoff.md) | 쓰기 |
| `thorp.strategySettings` | 003 (`frontend/src/hooks/useStrategySettings.ts`) | 쓰기 |

둘 다 `sessionStorage`이고 탭 수명 동안만 남는다. 기록을 여는 것은 저장된 결과를 바꾸는 일이 아니므로
서버에 초안 자원을 만들지 않는다.

## 흐름

```
GET /my-records/{recordId}        기록 상세(저장된 config·registryDrift)
        │
        ├─ writeStoredSelection(...)            002 계약 형식으로 섹터·비중
        ├─ writeStoredStrategySettings(...)     003 형식으로 전략·파라미터·분석 조건
        │
        └─ navigate('/strategy')
                 │
                 └─ 003이 등록표를 받아 보관값을 다시 판정한다 (이미 구현된 동작)
```

003의 `useStrategySettings`는 **보관된 값을 신뢰하지 않고 등록표로 다시 판정하며, 허용 범위 밖 값은
고치지 않고 비운 뒤 이유와 기본값 복원 선택을 돌려준다.** 006이 채운 값도 같은 절차를 지난다. 그래서
FR-008("값을 자동으로 바꾸지 않는다")과 Story 2 시나리오 4(등록표 버전이 바뀌어 저장값이 범위 밖일 때
기본값 복원 제안)는 **006이 따로 구현할 것이 없다** — 기존 판정을 타면 된다.

## 006이 쓰는 값

### `thorp.diagnosis.sectorSelection`

002 계약의 형식과 불변식을 그대로 따른다. 비중은 **0.1%p 단위 정수**(`1000` = 100%)다.

| 필드 | 006이 넣는 값 |
|---|---|
| `version` | 002가 정한 현재 형식 번호 |
| `mapVersion` | **기록이 저장 당시 참조한 대응표 버전**(`referenceVersions.sectorEtfMapVersion`). 현재 최신 버전을 넣지 않는다 |
| `selected` | 저장된 섹터 코드와 비중. 저장된 순서를 유지한다 |
| `weightsTouched` | 저장 당시 사용자가 비중을 고쳤는지. 알 수 없으면 `false` |

`mapVersion`에 저장 당시 버전을 넣는 이유: 002·003의 읽는 쪽이 현재 대응표에 없는 섹터 코드를
발견했을 때 "저장 당시 버전과 다르다"를 말할 수 있어야 한다. 최신 버전을 넣으면 그 판정이 사라진다.

### `thorp.strategySettings`

`StoredStrategySettings` 형식이다.

| 필드 | 006이 넣는 값 |
|---|---|
| `registryVersion` | **저장 당시 등록표 버전**(`referenceVersions.registryVersion`) |
| `universeSize` | 저장된 섹터 개수 |
| `selectedStrategyId` | 저장된 전략 |
| `valuesByStrategy` | 저장된 전략의 축 값만 채운다. 다른 전략의 자리는 비워 둔다 |
| `analysisCondition` | 저장된 분석 조건. `periodStart`·`periodEnd`·`initialAmount`는 **저장된 값이 있을 때만** 넣는다(003의 규칙: 사용자가 바꿨을 때만 담는다) |
| `warmupTradingDays` | **넣지 않는다.** `StoredStrategySettings`에 자리가 없고 003이 새 설정으로 다시 산정한다(아래 규칙 4) |
| `invalidValues` | 빈 배열. 판정은 003이 등록표를 받은 뒤에 한다 — 006이 미리 판정해 넣으면 두 곳이 같은 일을 하고 어긋날 수 있다 |

## 규칙

1. **값을 고쳐 넣지 않는다.** 허용 범위 밖이어도 저장된 값 그대로 쓴다. 자르거나 기본값으로 바꾸는
   일은 006이 하지 않는다(원칙 VI, FR-008).
2. **경제환경 기록에는 이 동작이 없다.** `reopenSettings.available: false`이며 '설정 바꿔 비교'
   버튼을 두지 않는다. 경제환경 검색은 전략과 독립적으로 결정되므로(원칙 IV) 전략 설정으로 옮길
   조건이 없다.
3. **저장소 접근이 막히면 화면을 막지 않는다.** `writeStoredSelection`·`writeStoredStrategySettings`는
   둘 다 실패를 `false`로 돌려준다. 실패하면 조건이 채워지지 않은 채 003이 열린다는 사실을 한 문장으로
   알리고 이동은 진행한다(002 계약의 "예외를 삼키고 메모리 상태로만 동작한다"와 같은 태도).
4. **워밍업과 평가 기간을 006이 옮기지 않는다.** 저장 당시 실효 워밍업(`request_spec.warmupTradingDays`)과
   평가 기간은 기록 상세에 **보존만** 하고 003으로 넘기지 않는다. 새 설정에 필요한 워밍업은 003이
   `evaluateWarmup`으로 다시 산정하고, 기존 결과와 비교할 때 긴 쪽의 공통 워밍업을 쓰는 판정과 평가
   기간이 달라졌을 때의 알림·확인은 005(`components/result/compareRequest.ts`·`CompareTargetPicker.tsx`)가
   이미 소유한다. 006이 같은 계산을 두 번 하지 않는다(FR-007a).
5. **이동 뒤 003에서 '분석하기'를 누르면 새 요청이 만들어지고 새 기록 항목이 생긴다.** 원본 기록과 그
   결과는 바뀌지 않는다(FR-007, SC-003). 조건이 저장된 것과 완전히 같으면 004의 동일 결과 재사용이
   판정하고 006은 기존 항목을 돌려준다(FR-009) — 006이 따로 같은지 비교하지 않는다.
