# 계약: '설정 바꿔 비교'의 화면 사이 인계

**Spec**: [../spec.md](../spec.md) FR-007·FR-007a | **Research**: [../research.md](../research.md) R4

서버 API가 아니라 **화면 사이의 계약**이다. 006의 기록 상세에서 '설정 바꿔 비교'를 누르면 저장된
조건이 003 전략 화면에 채워져 열린다.

**006은 인계 코드를 만들지 않는다.** 005가 006용으로 만들어 둔 함수를 그대로 부른다.

| 자원 | 소유 | 006의 역할 |
|---|---|---|
| `prefillStrategySettings(config, navigate, signal)` — `frontend/src/components/result/prefillStrategySettings.ts` | 005 (T068, "006의 '설정 바꿔 비교'도 이 함수를 쓴다") | **호출** |
| `thorp.diagnosis.sectorSelection` | [002 selection-handoff 계약](../../002-sector-weight-selection/contracts/selection-handoff.md) | 직접 쓰지 않는다. 위 함수가 `buildSelectionSnapshot`으로 채운다 |
| `thorp.strategySettings` | 003 (`frontend/src/hooks/useStrategySettings.ts`) | 직접 쓰지 않는다. 위 함수가 `buildStrategySettings`로 채운다 |

둘 다 `sessionStorage`이고 탭 수명 동안만 남는다. 기록을 여는 것은 저장된 결과를 바꾸는 일이 아니므로
서버에 초안 자원을 만들지 않는다.

## 흐름

```
GET /my-records/{recordId}                 기록 상세 — reopenPayload(005 ResultConfig 모양)·registryDrift·reopenSettings
        │
        └─ prefillStrategySettings(reopenPayload, navigate, signal)      005 소유
                 ├─ 등록표·섹터 목록을 읽는다
                 ├─ buildStrategySettings  → thorp.strategySettings
                 ├─ buildSelectionSnapshot → thorp.diagnosis.sectorSelection  (대응표가 같을 때만)
                 └─ navigate('/strategy')  또는  { status: 'reselectSectors' | 'failed' }
                          │
                          └─ 003이 등록표를 받아 보관값을 다시 판정한다 (이미 구현된 동작)
```

003의 `useStrategySettings`는 **보관된 값을 신뢰하지 않고 등록표로 다시 판정하며, 허용 범위 밖 값은
고치지 않고 비운 뒤 이유와 기본값 복원 선택을 돌려준다.** 그래서 FR-008("값을 자동으로 바꾸지 않는다")과
Story 2 시나리오 4(등록표 버전이 바뀌어 저장값이 범위 밖일 때 기본값 복원 제안)는 **006이 따로 구현할
것이 없다.**

## 006이 넘기는 값

`reopenPayload`는 저장된 요청 사본(`backtest_run.request_spec`)에 `currency`·`effectiveStart`·`effectiveEnd`를
얹은 것으로, 005 `GET /results/{token}`의 `config`(`ResultConfig`)와 **같은 모양**이다. 같은 모양이어야 005
함수에 그대로 들어간다. 006이 값을 고쳐 넣지 않는다.

| `ResultConfig` 필드 | 출처 |
|---|---|
| `mappingVersion`·`items[{sectorId, instrumentId, targetWeight?}]` | 사본 그대로 |
| `strategyId`·`rulesVersion`·`registryVersion?`·`parameters` | 사본 그대로. `registryVersion`은 저장 당시 등록표 버전 |
| `start`·`end`·`initialCash`·`scenario`·`scenarioParams?`·`evaluationSplitVersion` | 사본 그대로 |
| `warmupTradingDays` | 사본의 **실효값**. 함수는 이 값을 003으로 넘기지 않는다(아래 규칙 4) |
| `currency`·`effectiveStart`·`effectiveEnd` | 005 상세와 같은 방식으로 얹는다 |

## `PrefillOutcome` 처리

| 결과 | 006 화면의 동작 |
|---|---|
| `moved` | 이동됐다. 할 일 없음 |
| `reselectSectors` | 이동하지 않는다. 그 `message`를 `role="status"`로 보이고 섹터·비중 단계(`/diagnosis/sectors`)로 가는 연결을 같은 자리에 둔다. 전략·설정·분석 조건은 이미 보관됐다 |
| `failed` | 이동하지 않는다. `message`를 한 문장으로 보인다 |
| `canceled` | 아무것도 하지 않는다(화면을 떠났다) |

## 규칙

1. **값을 고쳐 넣지 않는다.** 허용 범위 밖이어도 저장된 값 그대로 넘긴다. 자르거나 기본값으로 바꾸는
   일은 006도 005 함수도 하지 않는다(원칙 VI, FR-008).
2. **대응표가 다르면 섹터를 넘기지 않는다.** 저장 당시 `mappingVersion`이 현재 대응표와 다르거나 지금
   고를 수 있는 섹터가 둘 미만이면 함수가 `reselectSectors`를 돌려준다. 003의 `useSectorSelection`이
   대응표 버전이 다른 선택을 이어받지 않으므로 저장 당시 버전을 넣어 넘겨도 되돌려질 뿐이다. 다른
   대응표에서 만든 선택을 지금 버전으로 옮기지 않는다(원칙 VIII, spec FR-007). 사라진 섹터의 대체
   선택 안내 문구는 002 화면에서 006이 채운다(T031).
3. **열 수 없는 기록은 버튼을 두지 않는다.** `reopenSettings.available`이 `false`인 기록 — 경제환경
   기록(전략과 독립적으로 결정되므로 옮길 조건이 없다, 원칙 IV)과 `scenario`가 `FULL_PERIOD`가 아닌
   결과(003 화면이 그 시나리오를 다루지 않아 함수가 `failed`를 돌려준다) — 에는 '설정 바꿔 비교'를
   보이지 않는다. 판정은 서버가 한다.
4. **워밍업과 평가 기간을 옮기지 않는다.** 저장 당시 실효 워밍업(`warmupTradingDays`)과 평가 기간은 기록
   상세에 **보존만** 한다. `StoredStrategySettings`에는 그 자리가 없고 003이 `evaluateWarmup`으로 새 설정에
   맞춰 다시 산정한다. 기존 결과와 비교할 때 긴 쪽의 공통 워밍업을 쓰는 판정과 평가 기간이 달라졌을
   때의 알림·확인은 005(`compareRequest.ts`·`CompareTargetPicker.tsx`)가 이미 소유한다(FR-007a).
5. **저장소 접근이 막히면 `failed`다.** 함수가 `STORAGE_FAILED` 문장을 돌려주고 이동하지 않는다. 006은 그
   문장을 보인다.
6. **이동 뒤 003에서 '분석하기'를 누르면 새 요청이 만들어지고 새 기록 항목이 생긴다.** 원본 기록과 그
   결과는 바뀌지 않는다(FR-007, SC-003). 조건이 저장된 것과 완전히 같으면 004의 동일 결과 재사용이
   판정하고 006은 기존 항목을 돌려준다(FR-009) — 006이 따로 같은지 비교하지 않는다.
