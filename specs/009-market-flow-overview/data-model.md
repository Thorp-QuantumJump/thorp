# Phase 1 Data Model: 시장 흐름 개요

**Spec**: [spec.md](spec.md) | **Research**: [research.md](research.md) | **Date**: 2026-09-16

009는 지표 자료를 소유하지 않는다. 008이 소유한 지표 관측값·기준월·자료 버전을 읽어 **화면이 그대로
그릴 수 있는 읽기 모델**로 바꾸는 것까지가 009의 범위다. 아래 모델에서 008 소유 항목은 `(008)`로 표시한다.

## 읽기 포트 — 009가 008에 요구하는 것

`MacroIndicatorSnapshotPort` (application/port). 008 구현 전에는 예시 자료 fixture 어댑터가 구현한다(R2).

| 조회 | 입력 | 반환 | 근거 |
|---|---|---|---|
| `loadFieldReferenceMonths()` | 없음 | 분야별 T_g, 지표별 최신 확보월, 자료 버전 | 008 FR-013 |
| `loadRecentPath(indicatorId, t)` | 지표, 기준월 | t-2·t-1·t 값과 각 대상월 | 008 FR-014 |
| `loadIndicatorMeta(registryVersion)` | 등록표 버전 | 이름·단위·변환·출처·작성기관·공표 정밀도 | 008 FR-002 |
| `loadCollectionState()` | 없음 | 원천별 마지막 갱신 시각·성공/실패 | 008 FR-011 |

포트는 **결측을 결측으로** 돌려준다. 009는 빈 값을 채우지 않는다(008 FR-008, 원칙 VI).

## Entities

### MarketFlowField — 분야 요약 카드

| 필드 | 타입 | 규칙 |
|---|---|---|
| `fieldId` | string | 7개 고정: `production` `consumption_investment` `external_trade` `labor` `prices_commodities` `finance_housing` `overseas` |
| `displayName` | string | (008) 화면 분류 버전이 정한다. 009는 저장하지 않고 읽는다 |
| `displayOrder` | int | (008) FR-001 표 순서. 사용자 정렬 기능 없음 |
| `status` | enum | `READY` \| `EXAMPLE` \| `EMPTY` \| `COLLECTION_FAILED` (R6) |
| `narrative` | string? | 변화 요약 문장. `status=READY`이거나 `EXAMPLE`일 때만 존재 |
| `narrativeRuleVersion` | string | `market-flow-narrative-v1` (R8) |
| `narrativeSubjects` | string[] | 서술에 쓴 지표 id 1~2개. 비면 "뚜렷한 방향 변화 없음" 문장 |
| `referenceMonth` | `YYYY-MM`? | (008) 분야 기준월 T_g |
| `referenceMonthHeldBackBy` | string[] | T_g를 물러나게 한 지표 id. 비어 있지 않으면 화면이 한 문장으로 안내 |
| `lastUpdatedAt` | instant? | (008) 그 분야 지표들이 마지막으로 갱신된 시각 |
| `isExampleData` | bool | 예시 자료 포함 여부. 참이면 화면에 예시 표시 (FR-014) |
| `indicators` | MacroIndicatorDisplay[] | 분야를 열었을 때 보이는 목록 |

**불변식**

- `status=READY`이면 `referenceMonth`와 `lastUpdatedAt`이 모두 있다.
- `status=EMPTY`이거나 `COLLECTION_FAILED`이면 `narrative`는 null이고 화면은 상태 표시를 그린다.
  오류 화면으로 바꾸지 않는다 (FR-014, SC-005).
- `isExampleData=true`이면 `status`는 `EXAMPLE`이다. 예시 자료를 `READY`로 표현하지 않는다.
- 어느 상태에서도 분야 전체를 좋고 나쁨으로 요약한 필드를 두지 않는다 (FR-003). **점수·등급·신호등에
  해당하는 필드는 이 모델에 없다.**

**상태 전이** — 매 갱신마다 포트가 준 자료로 다시 판정한다. 시간 경과만으로는 전이하지 않는다(R6).

```
지표 메타 없음 ─────────────▶ EMPTY
예시 자료 fixture 사용 ──────▶ EXAMPLE
최근 수집 실패 기록 있음 ────▶ COLLECTION_FAILED
그 밖에 3개월 경로 확보 ─────▶ READY
3개월 경로 부족(신규·단절) ──▶ READY(단, narrative는 '최근 경로 없음')
```

### MacroIndicatorDisplay — 지표 표시 항목

| 필드 | 타입 | 규칙 |
|---|---|---|
| `indicatorId` | string | (008) 17개 중 하나 |
| `name` | string | (008) |
| `unit` | string | (008) `%` `억달러` `달러/배럴` `원` 등 표시용 단위 |
| `value` | decimal? | t월 실제 값. 표준화 점수·거리로 바꾸지 않는다 (FR-007, SC-003) |
| `path` | PathPoint[3] | t-2·t-1·t. 각 원소는 `{ month, value }`이고 `value`는 결측 시 null |
| `referenceMonth` | `YYYY-MM` | 대상월 |
| `releaseDate` | date? | 발표일. 대상월과 구분해 보인다 (FR-013) |
| `source` | string | (008) 원천 |
| `agency` | string | (008) 작성기관. 표시 필수 (FR-015, SC-003) |
| `afterReferenceMonthRelease` | AfterTgRelease? | T_g 이후 발표. 아래 참조 |

**AfterTgRelease** (FR-008, 2026-09-16 clarify)

| 필드 | 타입 | 규칙 |
|---|---|---|
| `referenceMonth` | `YYYY-MM` | 반드시 분야 `referenceMonth`보다 뒤 |
| `value` | decimal | |
| `unit` | string | 상위 지표와 같은 단위 |

- 이 값은 `path`에 **넣지 않는다**. 화면은 지표 행 바로 아래 한 줄로 그린다.
- 변화 요약 계산의 입력으로 **쓰지 않는다**. R1의 방향 지속 길이는 `path`만 본다.

### NarrativeRule — 변화 요약 서술 규칙 (버전)

| 필드 | 타입 | 규칙 |
|---|---|---|
| `version` | string | `market-flow-narrative-v1` |
| `templates` | map | (방향 × 지속 길이) → 문장 틀. 지표 이름·값·단위·기간만 채운다 |
| `forbiddenTerms` | string[] | 판정·전망·투자 시사점 어휘. 출력 검사에 쓴다 (SC-002) |

- 화면 분류 버전(008 FR-003)과 계산 가중 버전과 **별도로** 관리한다. 문장 틀을 고쳐도 계산 가중이
  바뀌지 않는다.

### SectorLinkState — 섹터 연결 상태 (클라이언트 전용)

| 필드 | 타입 | 규칙 |
|---|---|---|
| `selectedSectorId` | string? | (002와 같은 섹터 목록) |

- **서버에 저장하지 않는다.** `sessionStorage` 키 하나에 보관하며 탭을 닫으면 사라진다 (FR-016, R5).
- 저장소를 쓸 수 없으면 화면 안 상태로만 동작하고 화면을 막지 않는다.
- 이 값은 분야 요약·지표 값·기준월 요청에 **포함되지 않는다**. 섹터가 경제환경 표시를 바꿀 수 없게
  요청 단계에서 차단한다 (FR-016, SC-004, 원칙 IV).

### DomesticMarketReference — 국내 시장 참고 정보

| 필드 | 타입 | 규칙 |
|---|---|---|
| `code` | string | `KOSPI` 고정 |
| `series` | point[] | 가격 추세·변동성 |
| `source` | string | '한국거래소 통계정보' 표시 (FR-015) |

- 009는 이 자료를 저장하지 않고 기존 `/api/markets/kospi`를 화면에서 호출한다 (R3).
- 경제환경 검색 입력으로 쓰지 않는다 (FR-017). 분야 요약과 다른 영역에 그린다.
- 자료가 없으면 이 영역만 비우고 분야 요약은 정상 표시한다 (Edge Case).

## 저장 — 읽기 모델 한 벌

SC-007(2초)을 위해 분야 요약을 미리 만들어 둔다(R7). 마이그레이션 파일 하나를 더한다.

```
V20260916xxxxxx__market_flow_field_summary.sql
```

| 컬럼 | 비고 |
|---|---|
| `field_id` | PK의 일부 |
| `registry_version`, `dataset_version` | (008) PK의 일부. 같은 버전 조합이면 같은 값이 나온다 |
| `narrative`, `narrative_rule_version`, `narrative_subjects` | |
| `status`, `is_example_data` | |
| `reference_month`, `reference_month_held_back_by` | |
| `last_updated_at`, `built_at` | |

- 갱신은 **등록표 버전 또는 자료 버전이 바뀔 때만** 한다. 사용자 요청이 재계산을 부르지 않는다.
- 같은 `(registry_version, dataset_version)`으로 다시 만들면 같은 결과가 나온다 (원칙 VIII, 008 SC-004).
- 지표 단위 표시 항목은 저장하지 않고 조회 시점에 포트에서 읽는다. 저장을 늘리면 008의 자료 버전과
  이중 관리가 된다.
