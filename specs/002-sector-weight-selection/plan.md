# Implementation Plan: 섹터·비중 선택

**Branch**: `docs(fe)/225-spec-002-plan-tasks` (spec 디렉터리 `002-sector-weight-selection`) | **Date**: 2026-09-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-sector-weight-selection/spec.md` (Clarifications 9건, FR-001~FR-017, SC-001~SC-013)

## Summary

전략 진단의 첫 입력을 받는 화면 하나를 만든다. 첫 화면에서 '전략 진단하기'를 누르면 섹터 목록이 열리고, 섹터를 체크하면 같은 화면 그 자리에 비중 입력 칸이 생기며 동일 비중 초깃값이 채워진다. 사용자는 비중을 직접 고칠 수 있고, 합계가 100%가 아니면 남은 비중을 그 자리에서 본다. 시스템은 사용자가 입력한 비중을 임의로 바꾸지 않는다. 선택한 섹터와 비중은 같은 탭 세션 동안 유지되고, 다음 단계인 [003 전략 선택](../003-strategy-settings/spec.md)으로 그대로 넘어간다.

기술 접근은 한 갈래다. **002는 `frontend/`만 고치고 백엔드 작업을 만들지 않는다.** 화면이 쓰는 서버 자원은 001이 이미 소유한 `GET /sectors`와 `GET /sectors/{code}/instrument-profile` 두 가지뿐이고([001 contracts/sector-api.md](../001-etf-market-data/contracts/sector-api.md)), 상장일 차이 안내에 필요한 공통 비교 시작일은 그 응답의 `listedOn` 최댓값으로 화면이 직접 구한다(research.md 결정 4). 비중은 부동소수 오차를 피하려고 화면 안에서 0.1%p 단위 정수로 다루고, 표시할 때만 소수 첫째 자리 문자열로 바꾼다(research.md 결정 1). 상태 유지 수단은 [014 plan](../014-user-auth/plan.md)이 복귀 위치에 이미 쓰고 있는 `sessionStorage`를 같은 목적으로 따라 쓴다(research.md 결정 3).

### spec 본문의 화면 용어와 계약 식별자의 대응

spec 본문은 사용자가 화면에서 보게 되는 말로 쓰고 엔드포인트 경로·응답 상태·오류 코드를 담지 않는다. 그 표현이 어느 계약의 무엇을 가리키는지는 이 표가 밝히며, 추적은 여기와 `contracts/`에서 한다.

| spec 본문의 표현 | 이 plan과 계약에서의 식별자 |
|---|---|
| 섹터 목록 (FR-003·FR-008) | [001 섹터 조회 계약](../001-etf-market-data/contracts/sector-api.md)의 `GET /sectors` |
| 섹터 목록을 아직 받을 수 없는 상태 (FR-017, SC-008) | 같은 계약의 `409 SECTOR_MAP_NOT_READY` |
| 섹터 목록을 다른 이유로 받아오지 못한 경우 (Edge Cases) | 같은 조회의 네트워크 실패와 5xx. `normalizeApiError`의 `network`·`unexpected`로 구분한다 (research.md 결정 6) |
| 예시 대응표에서 온 섹터 목록 (FR-015, SC-007) | 같은 응답의 `isSample`이 true인 경우 |
| 대표 상품 정보 (FR-010, SC-012) | 같은 계약의 `GET /sectors/{code}/instrument-profile` |
| 대표 상품 정보를 아직 받을 수 없는 상태 (Edge Cases) | 같은 조회의 `409 INSTRUMENT_PROFILE_NOT_READY` |
| 상장일만 반영한 공통 비교 시작일 (FR-008) | 같은 응답 `sectors[].representativeEtf.listedOn`의 최댓값 (research.md 결정 4) |
| 가공하지 않은 원본을 다음 단계에 넘김 (FR-013, SC-013) | [contracts/selection-handoff.md](./contracts/selection-handoff.md)의 탭 세션 보관 페이로드 |
| 값을 만들 당시의 대응표 버전 (FR-013) | 같은 페이로드의 `mapVersion` |

## Technical Context

**Language/Version**: TypeScript 5.8, React 19.1 (`frontend/`)

**Primary Dependencies**: react-router-dom 7.1(선언형 라우팅), Axios 1.7(`src/api/client.ts`의 공용 `apiClient`), radix-ui 1.6(덮어쓰기 확인 대화상자), motion 13(기존 화면 전환), Tailwind CSS 4. **새 런타임 의존성을 추가하지 않는다.** 소수 연산 라이브러리(decimal.js 등)도 추가하지 않으며 정수 연산으로 대신한다(research.md 결정 1)

**Storage**: 서버 저장소를 쓰지 않는다. 선택 섹터와 비중은 브라우저의 `sessionStorage` 항목 하나에만 둔다(탭 세션 범위, FR-014)

**Testing**: Vitest 5 + jsdom + React Testing Library(이미 `frontend/vite.config.ts`의 `test` 블록과 `src/test/setup.ts`에 설정되어 있다). 검증 명령은 `frontend/CLAUDE.md`가 정한 `npm run lint && npm run build`이고, 테스트는 `npm test`로 함께 돌린다

**Target Platform**: nginx 뒤의 동일 출처 React SPA. 데스크톱 브라우저 우선이며, 014가 이미 지키는 360px 이상 반응형 기준을 이 화면도 유지한다

**Project Type**: 모노레포의 프런트엔드 화면 하나. 백엔드·워커·데이터 파이프라인 변경 없음

**Performance Goals**: 화면 진입 시 `GET /sectors` 한 번만 호출한다. 비중 합계 판정과 동일 비중 재계산은 선택 섹터 수(기획서 §3.3 기준 10~20개)에 선형인 정수 연산이므로 입력마다 즉시 반영한다. 구체 수치 목표는 원칙 IX에 따라 실측 전에 정하지 않는다

**Constraints**: 표시 정밀도와 합계 판정 정밀도는 소수 첫째 자리 하나로 통일한다(research.md 결정 1). 사용자가 입력한 비중을 반올림·정규화·자동 조정하지 않는다(원칙 VI, FR-007). 화면의 가장 중요한 행동 버튼은 첫 화면이 '전략 진단하기' 하나, 섹터·비중 화면이 다음 단계 버튼 하나다(원칙 II). 계산식·자료 공급원·거리 함수·실험 식별자·서버 설정을 입력받지 않는다(FR-011)

**Scale/Scope**: 화면 2개(첫 화면 개편 1개, 신규 섹터·비중 화면 1개)와 '준비 중' 화면 1개. 섹터 수는 PD-01 확정 전이라 미정이며 10~20개 규모로 본다. 신규 API 없음

## Constitution Check

*GATE: Phase 0 research 전에 판정하고 Phase 1 설계 후 다시 판정한다. 기준 문서는 승인판 [constitution v3.2.0](../../.specify/memory/constitution.md)이다. 같은 파일 끝의 3.3.0은 전체 회의 승인 전 초안이므로 판정 기준으로 쓰지 않았다.*

| 원칙 | 이 기능에 적용되는 게이트 | 판정 |
|---|---|---|
| I. 중심 약속은 전략 진단 | 002는 전략 진단 흐름(001~005, P1)의 첫 입력이다. 시작 절차로 설문·숙련도 분류·사전 학습을 요구하지 않는다(FR-002) | 통과 |
| II. 첫 화면은 단순하게 | 첫 화면의 행동 버튼은 '전략 진단하기' 하나, 섹터·비중 화면은 다음 단계 하나다. 대표 상품 정보는 펼쳐 보는 층에 두고 다른 화면으로 보내지 않는다(FR-010). 0% 안내와 후보 집합 설명은 조건이 성립할 때만 한 문장으로 붙인다(research.md 결정 7) | 통과 |
| III. 진단이지 추천이 아니다 | 동일 비중 초깃값에 예시·출발점 표시를 붙이고 최적값으로 표기하지 않는다(FR-005). 추천 비중·순위·승자 표시를 두지 않는다 | 통과 |
| IV. 비교는 같은 조건에서만 | 상장일 차이로 공통 비교 기간이 줄어드는 사실을 선택 단계에서 알린다(FR-008). 후보마다 다른 기간을 쓰지 않는다 | 통과 |
| V. 당시에 알 수 있던 정보만 | 002는 가격을 다루지 않는다. 상장 전 구간을 비교에 넣지 않는다는 규칙은 공통 비교 시작일 안내로만 드러난다 | 해당 범위에서 통과 |
| VI. 없는 결과를 만들지 않고 사용자 입력을 몰래 바꾸지 않는다 | 합계가 맞지 않으면 그 자리에 남은 비중을 표시하고 자동 정규화하지 않는다(FR-007). 0%를 거부하거나 바꾸지 않는다(FR-016). 대응표 미발행(409)과 아직 만들지 않은 메뉴를 빈 목록이나 오류 화면이 아니라 한 문장 안내가 있는 정상 화면으로 처리한다(FR-001·FR-017) | 통과 |
| VII. 탐색과 검증을 구분 | 002는 검증 절차·평가 분할을 다루지 않는다 | 해당 없음 |
| VIII. 재현할 수 있게 기록 | 사용자에게 버전이나 실험 식별자를 입력받지 않는다(FR-011). 대신 화면이 받은 대응표 버전(`mapVersion`)을 세션 값과 다음 단계 인계 값에 함께 담아, 003·004가 재현 기록에 넣을 수 있게 한다([contracts/selection-handoff.md](./contracts/selection-handoff.md)) | 통과 |
| IX. 실행 한도는 실측으로 | 화면 진입마다 `GET /sectors` 한 번이다. 비중 계산은 브라우저 안의 정수 연산이라 서버 실행 한도와 무관하다 | 통과 |
| X. 자료는 이용 조건 안에서만 | 대표 상품 정보 패널은 응답의 `sourceAttribution`을 그대로 표시한다. 섹터 목록 화면 자체는 KRX에서 온 ETF 이름·상장일을 보이므로 같은 출처 문구를 함께 표시하되, `GET /sectors` 응답에 그 필드가 없어 이번에는 화면 상수로 둔다 | 조건부 통과. Complexity Tracking과 research.md 결정 8 참고 |

**추가 게이트 (루트 [AGENTS.md](../../AGENTS.md)·[CLAUDE.md](../../CLAUDE.md) 아키텍처 원칙)**

| 게이트 | 판정 |
|---|---|
| Backend는 계산하지 않는다 | 통과. 002는 백엔드에 새 계산을 요구하지 않는다. 공통 비교 시작일은 `GET /sectors`가 이미 주는 `listedOn`의 최댓값이며 화면이 구한다(research.md 결정 4) |
| 파트 규칙 준수 (`frontend/CLAUDE.md`) | 통과. 서버 통신은 `src/api/`의 `apiClient`만 거치고, 화면은 `src/pages/`, 재사용 요소는 `src/components/`, 타입은 `src/types/`에 둔다 |
| 다른 spec의 소유 규칙을 다시 정하지 않는다 ([specs/README.md](../README.md)) | 통과. 인증·상태 복원 방식은 [014](../014-user-auth/plan.md)를 따르고 다시 정하지 않았다. `GET /sectors` 계약은 001의 것이므로 002가 재정의하지 않는다 |

### Phase 1 설계 후 재판정

설계 산출물(research.md 결정 1~10, data-model.md, contracts/, quickstart.md)에 대해 위 표를 다시 확인했다. 판정은 그대로이며, 설계 과정에서 네 가지가 더 분명해졌다.

- **원칙 VI가 정밀도 결정에 직접 걸린다.** 표시 정밀도를 소수 첫째 자리로 정한 뒤, 사용자가 소수 둘째 자리 이하를 입력하면 어떻게 할지가 남는다. 조용히 반올림하면 "사용자 입력을 몰래 바꾼다"에 해당하므로, 입력 자체를 받지 않고 그 자리에 한 문장으로 알리는 쪽을 골랐다(research.md 결정 1). 거부는 변경이 아니다.
- **원칙 VI가 세션 복원에도 걸린다.** 탭 세션에 저장한 섹터 코드가 현재 대응표에 없으면 그 코드를 조용히 버릴 수 없다. 002는 그 코드를 선택에서 빼되 빠졌다는 사실을 한 문장으로 알리고, 무엇을 제안할지(기본값 복원·대체 선택)는 [006 FR-008](../006-my-records/spec.md)과 UI-01이 소유하므로 정하지 않는다(research.md 결정 3).
- **원칙 II가 안내 문장 수를 제한한다.** 이 화면이 요구받은 안내는 초깃값 예시 표시(FR-005), 예시 대응표 표시(FR-015), 상장일 안내(FR-008), 후보 집합 뜻(FR-009), 0% 뜻(FR-016), 합계 부족·초과(FR-007)로 여섯 가지다. 전부 상시 노출하면 첫 화면 단순함이 깨지므로, 조건이 성립할 때만 나타나는 것과 항상 보이는 것을 research.md 결정 7에서 나눴다.
- **결정 10은 위 재판정이 검토하지 않았다.** 이 절이 처음 쓰일 때 대상 목록이 "결정 1~9"였고 research.md에는 결정이 10개 있다. 빠진 결정 10('동일하게 나누기'를 누르면 사용자 수정 여부를 '고치지 않음'으로 되돌린다)은 원칙 VI에 걸리는 결정이므로 여기서 함께 판정한다. 판정은 통과다. 되돌린 뒤에 지켜야 할 사용자 값이 남아 있지 않고, 되돌리는 행동을 사용자가 직접 눌러 일으키므로 "사용자 입력을 몰래 바꾼 뒤 알리지 않는 처리"에 해당하지 않는다(research.md 결정 10의 근거와 같다). 다만 이 동작은 FR-005·FR-007 어디에도 적혀 있지 않던 상태 전이이고, SC-009가 세는 "비중을 한 번 이상 고친 뒤"의 뜻을 가르므로 spec에 근거가 있어야 한다. 그 근거는 spec의 Edge Cases에 "'동일하게 나누기'로 되돌린 뒤 섹터를 더하거나 빼면 전체를 동일 비중으로 다시 채우며, SC-009가 세는 '고친 뒤'는 되돌림 뒤에 다시 고친 경우를 뜻한다"로 추가되어 있다.

## Project Structure

### Documentation (this feature)

```text
specs/002-sector-weight-selection/
├── plan.md                      # 이 파일
├── research.md                  # Phase 0 산출물 (결정 1~10)
├── data-model.md                # Phase 1 산출물 (화면 상태 모형)
├── quickstart.md                # Phase 1 산출물 (검증 시나리오)
├── contracts/                   # Phase 1 산출물
│   ├── README.md
│   └── selection-handoff.md     # 002가 소유하는 인계·저장 계약
├── checklists/requirements.md   # /speckit-specify 산출물
└── tasks.md                     # /speckit-tasks 산출물 (이 명령이 만들지 않는다)
```

### Source Code (repository root)

```text
frontend/src/
├── api/
│   └── sectors.ts                       # 신규. GET /sectors, GET /sectors/{code}/instrument-profile
├── types/
│   └── sector.ts                        # 신규. 위 두 응답의 타입 (camelCase, 백엔드 DTO와 1:1)
├── diagnosis/                           # 신규. 진단 입력 상태와 순수 규칙
│   ├── weights.ts                       # 0.1%p 정수 모형, 동일 비중·잔여분·합계 판정
│   ├── selectionStorage.ts              # sessionStorage 읽기·쓰기와 페이로드 검증
│   ├── SectorSelectionProvider.tsx      # 화면 상태 소유 Context
│   └── listingWindow.ts                 # 공통 비교 시작일(max listedOn)과 원인 섹터
├── pages/
│   ├── LandingPage.tsx                  # 개편. 네 메뉴와 '전략 진단하기' 하나
│   ├── SectorSelectionPage.tsx          # 신규. 002의 본 화면
│   └── NotReadyPage.tsx                 # 신규. 아직 만들지 않은 메뉴의 정상 화면
├── components/
│   ├── common/NotReadyNotice.tsx        # 신규. '준비 중'과 409가 함께 쓰는 한 문장 표시
│   └── diagnosis/                       # 신규. 섹터 항목·비중 입력·합계 표시·덮어쓰기 확인
└── App.tsx                              # 라우트 추가

frontend/vite.config.ts                  # test.include에 신규 테스트 경로를 추가한다 (아래 주의)
```

**`vite.config.ts`의 `test.include`를 함께 고쳐야 한다.** 현재 값은 `['src/**/*.test.tsx', 'src/api/auth.test.ts', 'src/auth/**/*.test.ts']`로, `.test.ts`는 열거된 두 경로만 수집한다. `src/diagnosis/weights.test.ts` 같은 순수 로직 테스트를 새로 만들면 Vitest가 수집하지 않고 `npm run test:legacy`의 파일 목록에도 없어서, 테스트가 **실패하지 않고 그냥 실행되지 않는다.** 묶음 M2에서 `src/diagnosis/**/*.test.ts`를 `include`에 넣고, 일부러 실패하는 단언을 한 번 넣어 실제로 수집되는지 확인한 뒤 되돌린다(quickstart.md 시나리오 0).

**Structure Decision**: FRONT 파트 한 곳만 고친다. `frontend/CLAUDE.md`의 폴더 규칙(`src/pages/` 라우트 화면, `src/components/` 재사용, `src/api/` 서버 통신 전용, `src/types/` 백엔드 DTO 대응, `src/hooks/` 훅)을 그대로 지키되, 진단 입력의 상태와 순수 규칙은 014가 인증 상태에 `src/auth/`를 쓴 것과 같은 방식으로 `src/diagnosis/` 한 곳에 모은다. 근거는 두 가지다. 첫째, 비중 계산 규칙은 화면 없이 단위 테스트할 수 있어야 SC-002·SC-009·SC-010을 값으로 검증할 수 있다. 둘째, 같은 상태를 003이 이어받으므로 화면 컴포넌트 안에 상태를 두면 003이 재사용할 자리가 없다.

전역 상태 라이브러리는 도입하지 않는다. 저장소에 zustand·redux 같은 도구가 없고, 014가 인증 상태 하나를 위해 새 의존성을 들이지 않기로 이미 정했다([014 research.md](../014-user-auth/research.md) 결정 6). 002의 상태도 화면 한 갈래에서만 쓰이므로 React Context로 충분하다.

## 작업 묶음 구성 초안

constitution 「Jira 연계」가 "묶음 구성은 plan을 시작할 때 정한다"고 요구하므로 여기서 정한다. 묶음은 네 개이고 모두 `[FE]`다. 002는 `frontend/`만 고치므로 `[BE]`·`[DE]`·`[INFRA]`·`[AI]` 묶음이 없고, 문서 갱신은 각 묶음의 MR 안에서 끝나므로 `[DOCS]` 묶음도 따로 두지 않는다.

001이 개별 task 76개를 묶음 16개로 모았다가 Jira Task 6개로 다시 줄인 것과 달리([001 tasks.md](../001-etf-market-data/tasks.md) 머리말), 002는 한 파트가 화면 하나를 만드는 spec이라 규모가 작다. 네 개로 정한 까닭은 아래 세 조건 중 셋째(리뷰어가 한 번에 읽을 수 있는 범위)가 실제 제약이기 때문이다. 화면 하나를 한 묶음으로 두면 라우트 개편·목록 조회·비중 규칙·안내 문장이 한 MR에 들어와 리뷰가 어려워지고, 여덟 개로 쪼개면 "합계 표시만 있고 입력이 없는" 같은 미완성 묶음이 생긴다.

| 묶음 | 제목(Jira Task) | 담는 범위 | 근거 요구사항 |
|---|---|---|---|
| M1 | `[FE] 첫 화면 메뉴와 준비 중 화면` | `LandingPage` 개편(주요 메뉴 세 개·보조 메뉴 하나·행동 버튼 '전략 진단하기' 하나), 아직 만들지 않은 메뉴의 '준비 중' 정상 화면과 공용 표시 컴포넌트, 라우트 추가 | FR-001, FR-002, SC-001(진입부), SC-008(메뉴 쪽) |
| M2 | `[FE] 섹터 목록 조회와 선택 상태` | `GET /sectors` 연동과 타입, 목록 표시(이름·대표 ETF·업종/테마), 예시 대응표 표시, 409 정상 화면, 두 개 이상 선택 제약, 대표 상품 정보 패널, 탭 세션 저장·복원, `vite.config.ts`의 테스트 수집 경로 확장 | FR-003, FR-004, FR-010, FR-014, FR-015, FR-017, SC-006, SC-007, SC-008(목록을 아직 받을 수 없는 쪽), SC-012 |
| M3 | `[FE] 비중 입력과 합계 규칙` | 0.1%p 정수 모형, 동일 비중 초깃값과 잔여분 배분, 사용자 수정 여부에 따른 분기, 남은 비중 표시, '동일하게 나누기', 0% 허용, 소수 둘째 자리 이하 입력 거부 | FR-005, FR-006, FR-007, FR-016, SC-002, SC-009, SC-010 |
| M4 | `[FE] 선택 단계 안내와 다음 단계 인계` | 상장일 차이 안내와 원인 섹터, 후보 집합 뜻과 '내가 정한 비중' 구분 안내, 0%의 뜻 안내, 다음 단계 버튼과 003 인계(사용자 입력 원본과 대응표 버전을 가공 없이 남기는 데까지), 배우기 예시 진입과 덮어쓰기 확인 | FR-008, FR-009, FR-011, FR-012, FR-013, SC-003, SC-005, SC-011, SC-013 |

**세 조건을 어떻게 만족하는가**

| 조건 | M1 | M2 | M3 | M4 |
|---|---|---|---|---|
| 1. 하나의 목적을 공유한다 | 진입 화면과 아직 줄 것이 없는 상태의 표현 방식 하나 | 섹터 목록이라는 자료 한 벌을 받아 화면 상태로 만드는 흐름 | 비중이라는 값 하나의 계산·입력·판정 | 선택이 끝난 뒤의 안내와 인계 |
| 2. 독립적으로 구현·검증·리뷰·롤백할 수 있다 | 기존 라우트를 지우지 않고 더하므로 되돌리면 첫 화면만 이전으로 돌아간다 | M1의 라우트 위에서 화면이 서고, 비중 없이도 목록·선택·세션 저장까지 검증된다 | 순수 함수 단위 테스트로 화면 없이 먼저 검증된다. 되돌려도 M2의 선택 기능은 남는다 | 앞 세 묶음이 만든 상태를 읽기만 한다. 되돌리면 안내와 다음 단계만 사라진다 |
| 3. 리뷰 범위가 한 번에 읽히고 미완성 task가 없다 | 화면 하나와 공용 컴포넌트 하나 | 네 묶음 중 가장 크다. 아래 주의 참고 | 규칙 파일 하나와 입력 컴포넌트 묶음 | 안내 문장 자리와 인계 경로 |

**M2가 가장 크다.** 조회·상태·저장·목록 표시·상품 정보 패널이 한 묶음에 있다. 구현 중 변경 범위가 리뷰 한 번에 읽히지 않을 정도로 커지면 대표 상품 정보 패널(FR-010)을 떼어 다섯 번째 묶음으로 둔다. 그 패널은 목록 항목에서 펼쳐 보는 층이라 빠져도 목록·선택·저장이 완성된 상태로 남기 때문이다. 묶음 구성을 바꾸면 constitution 「Jira 연계」에 따라 사유와 새 구성을 이 문서나 MR 본문에 남긴다.

**순서와 의존**: M1 → M2 → M3 → M4로 진행한다. M1과 M2가 모두 `App.tsx`의 라우트를 건드리므로 병렬로 두지 않는다. M3은 M2의 선택 상태 위에서 동작하고, M4는 M2·M3이 만든 값을 읽는다.

**Jira 연결**: 묶음마다 Jira Task 하나를 먼저 만들고 그 번호로 브랜치를 판다. 브랜치 형식은 `feat(fe)/{JIRA_NUMBER}-{description}`이다. tasks.md의 개별 task 번호는 MR 본문의 "주요 변경 사항"에 나열한다.

## Complexity Tracking

> Constitution Check에서 조건부 통과로 판정한 항목의 근거다.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 X의 출처 문구를 `GET /sectors` 응답이 아니라 화면 상수로 둔다 | 섹터 목록 화면은 KRX에서 온 대표 ETF 이름과 상장일을 보이므로 원칙 X가 요구하는 '한국거래소 통계정보' 표시 대상이다. 그런데 [001 계약](../001-etf-market-data/contracts/sector-api.md)의 `GET /sectors` 응답에는 `sourceAttribution`이 없고 `GET /sectors/{code}/instrument-profile`에만 있다 | 표시를 생략하면 원칙 X를 어긴다. 상품 정보 패널을 열어야만 출처가 보이게 두는 것도 목록 화면에서는 미표시와 같다. 값을 지어내는 것이 아니라 001이 이미 상수처럼 내보내는 같은 문구를 쓰는 것이므로 표시 쪽을 택했다. 후속 조치로 `GET /sectors`에 `sourceAttribution`을 더하도록 001에 제안하며, 그 계약 변경은 001이 소유하므로 002에서 고치지 않는다(research.md 결정 8) |
| 공통 비교 시작일 산출이 001의 `evaluation-window` 산식과 별도로 화면에 존재한다 | 002가 안내하는 값은 상장일만 반영한 시작일이고, `GET /sectors/evaluation-window`는 003이 소유하는 워밍업 거래일 수를 필수 입력으로 받는다. 전략을 고르기 전에는 그 엔드포인트를 부를 수 없다(spec.md의 Clarifications) | 003까지 안내를 미루면 SC-003("결과 화면에서 처음 알게 되는 사례 0건")과 spec의 User Story 3이 성립하지 않는다. 002가 쓰는 식은 `max(listedOn)` 하나뿐이고, 이는 001 산식에서 `warmupTradingDays = 0`인 경우와 같아 두 값이 어긋날 여지가 없다. 화면에는 상장일만 반영한 값임을 밝히고 워밍업 몫은 003이 알린다(research.md 결정 4) |
