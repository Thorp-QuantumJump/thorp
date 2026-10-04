# Tasks: 섹터·비중 선택

**Input**: `specs/002-sector-weight-selection/` 의 plan.md, spec.md(Clarifications 9건, FR-001~FR-017, SC-001~SC-013), research.md(결정 1~10), data-model.md, contracts/{README,selection-handoff}.md, quickstart.md(시나리오 0~11), checklists/{requirements,ux,boundaries}.md

**작업 단위 규칙** (constitution v3.2.0 「Jira 연계」): 아래 T항목 하나는 실행 지시의 최소 단위이며, 여러 T항목을 묶은 **작업 묶음 하나 = Jira Task 하나 = 브랜치 하나 = MR 하나**다. Jira Task 제목은 `[분야] 작업 내용` 형식이고 002는 `frontend/`만 고치므로 분야가 전부 `[FE]`다. 브랜치는 `feat(fe)/{JIRA_NUMBER}-{description}` 형식으로 만든다. 각 T항목 아래 들여쓴 줄은 구현 메모이며 Jira에 올리지 않는다. Task의 완료 조건은 constitution 「완료의 정의」 다섯 가지다.

**Jira 이슈는 아직 발급하지 않았다.** 묶음마다 Jira Task 하나를 사람이 먼저 만들고 그 번호로 브랜치를 판다. 아래 모든 T항목의 Jira 자리는 `Jira 미발급`으로 두었고, 발급한 뒤 그 자리를 이슈 키로 바꾼다. tasks.md의 개별 task 번호는 MR 본문의 "주요 변경 사항"에 나열한다.

## plan의 묶음 초안에서 바꾼 것

[plan.md](./plan.md) 「작업 묶음 구성 초안」은 묶음을 M1~M4 네 개로 두었다. T항목을 실제로 뽑아 보니 그 가운데 M2가 constitution이 정한 묶음 조건 두 가지를 만족하지 못해 **다섯 개로 늘렸다.** 아래가 바꾼 내용과 사유다. `plan.md`는 고치지 않았으며, constitution이 "구현 도중 범위가 달라지면 그 사유와 새 묶음 구성을 plan 또는 MR 본문에 남긴다"고 허용한 기록을 이 자리에 둔다.

| 바꾼 것 | 사유 |
|---|---|
| plan M2의 '선택 상태'와 '탭 세션 저장·복원'을 M3으로 옮겼다 | 조건 3(묶음 안에 미완성 상태로 남는 task가 없다)에 걸린다. 선택 상태는 `weightTenths`를 함께 갖는 구조이고([data-model.md](./data-model.md) 「SectorSelection」의 불변식 1), 그 값을 만드는 `distributeEven`은 plan이 M3에 두었다. 초안대로면 M2는 비중을 0으로 채우거나 임시 구현을 넣었다가 M3에서 다시 고쳐야 한다. 또 FR-005의 "섹터를 체크하면 그 자리에 비중 입력 칸이 생기고 초깃값이 채워진다"가 두 묶음으로 갈라져 어느 쪽도 혼자서는 그 요구사항을 만족하지 못한다. 조건 2(독립적으로 검증)도 같은 이유로 어긋난다. SC-006은 "섹터와 비중을 입력한 뒤"를 전제하므로 비중이 없는 상태에서는 세션 저장을 끝까지 검증할 수 없다 |
| plan M2의 '대표 상품 정보 패널'을 떼어 M5로 두었다 | plan이 같은 절에서 미리 허용한 분리다. "구현 중 변경 범위가 리뷰 한 번에 읽히지 않을 정도로 커지면 대표 상품 정보 패널(FR-010)을 떼어 다섯 번째 묶음으로 둔다." 그 패널은 목록 항목에서 펼쳐 보는 층이라 빠져도 목록·선택·저장이 완성된 상태로 남는다 |
| M1이 `/diagnosis/sectors` 경로를 '준비 중' 화면으로 먼저 연결한다 | plan은 M1을 "라우트 추가"까지로 두었으나 목적지 화면이 M2에 있다. M1만 병합된 상태에서 '전략 진단하기'가 아무 데도 가지 않거나 `path="*"` 규칙에 걸려 첫 화면으로 되돌아오면 FR-001이 요구한 "정상 화면으로 처리한다"를 어긴다. M1이 그 경로를 잠시 `NotReadyPage`로 받고 M2가 실제 화면으로 바꾼다 |

바뀐 것은 묶음의 경계뿐이고 **담을 일의 총량과 근거 요구사항은 plan 초안과 같다.** M1과 M4의 범위는 초안 그대로다.

## Format

`- [ ] {ID} [묶음] [P?] [Story?] [분야] 설명 (파일 경로) · Jira 미발급`

- `[묶음]`: `M1`~`M5`. 묶음 하나가 Jira Task 하나이며 절 제목과 같다
- `[P]`: 다른 파일을 건드리고 앞 task에 의존하지 않아 병렬로 진행할 수 있다
- `[Story]`: `US1`~`US4`. spec의 사용자 스토리 번호이며 둘 이상에 걸치면 함께 적는다. 스토리에 매이지 않는 공통 작업에는 붙이지 않는다
- `[분야]`: 002는 전부 `[FE]`다. `frontend/` 밖을 고치는 task가 없기 때문이다

## Path Conventions

- 화면: `frontend/src/pages/`, 재사용 컴포넌트: `frontend/src/components/`
- 서버 통신: `frontend/src/api/`, 백엔드 DTO 대응 타입: `frontend/src/types/`
- 진단 입력의 상태와 순수 규칙: `frontend/src/diagnosis/` (plan 「Structure Decision」이 014의 `src/auth/`를 따라 정한 자리다)
- 테스트는 대상 코드 옆에 둔다. `frontend/docs/frontend-guidelines.md`의 「15. 테스트 기준」과 같다

plan의 Project Structure에 없던 파일 두 개를 아래에서 새로 만든다. 묶음 구성을 바꾸는 변경이 아니므로 여기 적어 둔다.

| 파일 | 왜 필요한가 |
|---|---|
| `frontend/src/diagnosis/sectorOrder.ts` | research.md 결정 2의 표시 순서(업종 먼저, 그다음 한국어 이름 정렬)를 M2의 목록 표시와 M3의 `distributeEven`이 함께 쓴다. 두 곳에 같은 정렬을 두면 잔여분이 붙는 자리가 화면과 계산에서 갈라진다 |
| `frontend/src/diagnosis/attribution.ts` | research.md 결정 8이 정한 KRX 출처 문구 상수를 한 곳에만 둔다. `GET /sectors`가 나중에 `sourceAttribution`을 주면 이 파일을 지우고 응답 값으로 바꾼다 |

## 검증 명령

`frontend/package.json`을 직접 확인한 결과다. 묶음마다 아래 세 가지를 모두 통과해야 한다.

```bash
cd frontend
npm run lint          # eslint .
npm run build         # tsc -b && vite build
npm test              # test:dom(Vitest) 뒤에 test:legacy(node --test)를 차례로 돌린다
```

**통과 수만 보지 않는다.** 수집된 파일 수와 `skipped` 수를 함께 본다. 아래 T005가 이 확인을 자동으로 만든다.

**이 작업을 준비하며 FRONT 파트 문서 두 곳을 고쳤다.** `frontend/CLAUDE.md`의 검증 명령에 `npm test`가 빠져 있었고, `frontend/docs/frontend-guidelines.md`에 "현재 프로젝트에는 테스트 스크립트가 없다"는 낡은 문장이 남아 있었다. 실제로는 `package.json`에 `test`·`test:dom`·`test:legacy`가 있고 Vitest 테스트가 스물다섯 개 돌고 있다. 두 문서를 실제 상태에 맞추면서 아래 수집 함정 경고도 함께 넣었다. **이 변경은 FRONT 파트 전체에 적용되므로 파트원에게 알린다.**

`plan.md`와 `quickstart.md`도 같은 세 명령을 쓴다.

## 묶음 한눈에 보기

| 묶음 | Jira Task 제목 | T항목 | 근거 요구사항 | Jira |
|---|---|---|---|---|
| M1 | `[FE] 첫 화면 메뉴와 준비 중 화면` | T001~T004 | FR-001, FR-002, SC-001(진입부), SC-008(메뉴 쪽) | 미발급 |
| M2 | `[FE] 섹터 목록 조회와 표시` | T005~T010 | FR-003, FR-011(보이는 정보), FR-015, FR-017, SC-007, SC-008(목록 쪽) | 미발급 |
| M3 | `[FE] 선택 상태와 비중 규칙` | T011~T015 | FR-004, FR-005, FR-006, FR-007, FR-014, FR-016, SC-002, SC-006, SC-009, SC-010 | 미발급 |
| M4 | `[FE] 선택 단계 안내와 다음 단계 인계` | T016~T020 | FR-008, FR-009, FR-011, FR-012, FR-013, SC-003, SC-005, SC-011, SC-013 | 미발급 |
| M5 | `[FE] 대표 상품 정보 패널` | T021~T023 | FR-010, SC-012 | 미발급 |

**세 조건을 어떻게 만족하는가**

| 조건 | M1 | M2 | M3 | M4 | M5 |
|---|---|---|---|---|---|
| 1. 하나의 목적을 공유한다 | 진입 화면과 아직 줄 것이 없는 상태의 표현 방식 | 섹터 목록 한 벌을 받아 화면에 그리는 흐름 | 선택과 비중이라는 값 하나의 계산·입력·판정·보관 | 선택이 끝난 뒤의 안내와 인계 | 목록 항목에서 펼쳐 보는 층 하나 |
| 2. 독립적으로 구현·검증·리뷰·롤백할 수 있다 | 기존 라우트를 지우지 않고 더한다. 되돌리면 첫 화면만 이전으로 돌아간다 | 목록을 보이는 데까지가 완결이다. 되돌리면 M1의 준비 중 화면으로 되돌아간다 | 순수 함수 단위 테스트로 화면 없이 먼저 검증된다. 되돌리면 M2의 목록이 남는다 | 앞 세 묶음이 만든 상태를 읽기만 한다. 되돌리면 안내와 다음 단계만 사라진다 | 목록 항목에 펼침 하나를 더한다. 되돌려도 목록·선택·비중이 남는다 |
| 3. 리뷰 범위가 한 번에 읽히고 미완성 task가 없다 | 화면 둘과 공용 컴포넌트 하나 | 조회·타입·정렬·목록 표시 | 규칙 파일 하나와 입력 컴포넌트 묶음, 보관 한 곳 | 안내 문장 자리와 인계 경로 | 조회 하나와 패널 하나 |

## 이 tasks가 다루지 않는 것

| 항목 | 왜 다루지 않는가 |
|---|---|
| SC-004 사용성 관찰 | **사람이 직접 화면을 써 보는 것을 관찰하는 항목이라 구현 task로 다루지 않는다.** 코드로 판정할 수 있는 종류가 아니며 화면이 완성된 뒤 별도로 진행한다. 관찰 대상 흐름은 User Story 1·2이고 quickstart 시나리오 1~5가 같은 흐름을 밟으므로, 관찰 과제를 짤 때 그 시나리오를 그대로 쓰면 된다. 이 기준은 합격 비율을 두지 않고 결과를 기록하는 것과 두 명 이상이 같은 단계에서 막히면 화면을 고치는 것까지만 요구하므로, 관찰을 아직 하지 않았다고 해서 구현이 막히지 않는다. 관찰의 참여자·과제·일정은 PD-14가 정한다 |
| `GET /sectors` 계약 확장 세 가지 | 폐지일 필드·출처 표시 필드·배열 정렬 규칙은 001이 소유한다. 002는 그 셋 없이 동작하도록 plan과 research가 이미 우회책을 정해 두었다(결정 2·4·8, spec Out of Scope). 여기에 T항목을 두면 002의 MR이 001의 계약을 고치게 된다 |
| 화면 문구 확정 | research.md 「확정하지 않은 것」이 자리와 조건까지만 정했다. 각 task는 문장이 놓일 자리와 나타나는 조건을 만들고, 문구는 UI 결정 항목이 정해질 때 채운다 |
| `checklists/ux.md`·`boundaries.md`의 미표시 항목 | 리뷰어가 소유하는 요구사항 품질 검토 문서이며 구현 task가 아니다. 다만 접근성·반응형을 묻는 CHK033~CHK035는 구현이 대응할 수 있는 항목이라 T010·T014·T015·T020의 구현 메모로 옮겨 담았다 |

**SC-012와 SC-013에는 quickstart 시나리오가 없다.** 두 성공 기준은 spec 3차 판정 때 뒤에 추가되었고 quickstart는 시나리오 0~11까지만 갖고 있다. 이 tasks는 그 몫을 사람 손 절차가 아니라 **자동 테스트로 받는다.** SC-013은 T019, SC-012는 T022가 각각 자기 묶음 안에서 검증한다. `quickstart.md`에 시나리오 12·13을 더할지는 그 문서의 소유자가 판단할 일이라 여기서 고치지 않았다.

---

## M1: 첫 화면 메뉴와 준비 중 화면

**목표**: 처음 방문한 사용자가 네 메뉴를 모두 보고 '전략 진단하기' 한 번으로 다음 화면에 들어간다. 아직 만들지 않은 화면은 사라지지 않고 한 문장 안내가 있는 정상 화면으로 열린다.

**Independent Test**: 저장된 상태가 없는 새 탭으로 `/`에 들어가 메뉴 넷이 모두 보이고 행동 버튼이 하나인지 확인한다. 배우기를 열면 준비 중 한 문장이 보이고 행동 버튼이 없다.

- [X] T001 [M1] [P] [US4] [FE] 아직 줄 것이 없는 상태의 공용 표시 컴포넌트 (`frontend/src/components/common/NotReadyNotice.tsx`, `frontend/src/components/common/NotReadyNotice.test.tsx`) · Jira 미발급
  - 받는 것은 **한 문장과 선택적인 행동 하나**뿐이다. 행동을 주지 않으면 버튼을 그리지 않는다 (research.md 결정 6)
  - 이 컴포넌트 하나를 '아직 만들지 않은 메뉴'와 `409 SECTOR_MAP_NOT_READY`와 네트워크 실패가 함께 쓴다. 표시를 한 곳에 모아야 SC-008이 두 경우를 같은 기준으로 셀 수 있다
  - 오류 경계(Error Boundary)나 빈 화면으로 떨어지지 않는다. 렌더링 분기는 예외가 아니라 상태 값이다
  - 테스트: 행동을 주지 않으면 버튼 개수가 0, 주면 1. 문장이 언제나 하나 보인다
- [X] T002 [M1] [US4] [FE] 아직 만들지 않은 메뉴의 정상 화면 (`frontend/src/pages/NotReadyPage.tsx`, `frontend/src/pages/NotReadyPage.module.css`) · Jira 미발급
  - T001을 써서 한 문장만 보이고 행동 버튼은 두지 않는다. 다시 눌러도 바뀌지 않는 상태이기 때문이다 (research.md 결정 6)
  - 첫 화면으로 돌아가는 길은 기존 내비게이션이 준다. 이 화면이 행동 버튼을 새로 만들지 않는다 (원칙 II)
- [X] T003 [M1] [P] [US4] [FE] 첫 화면 개편 (`frontend/src/pages/LandingPage.tsx`, `frontend/src/pages/LandingPage.module.css`, `frontend/src/pages/LandingPage.test.tsx`) · Jira 미발급
  - 주요 메뉴 '전략 진단 / 시장 흐름 / 배우기'와 보조 메뉴 '내 기록'을 **모두** 보인다. 아직 화면이 없는 메뉴도 숨기지 않는다 (FR-001, SC-008)
  - 가장 중요한 행동 버튼을 '전략 진단하기' 하나로 줄인다. 지금 화면에 다른 행동 유도가 있으면 걷어낸다 (원칙 II, research.md 결정 5)
  - 설문·숙련도 분류·사전 학습 수강을 요구하는 단계를 두지 않는다 (FR-002)
  - 메뉴 목적지는 이미 화면이 있으면 그 경로로 잇는다. 시장 흐름은 `/home`, 내 기록은 `/records`, 배우기는 `/learn`이다. 최종 목적지 확정은 각 소유 spec의 몫이므로 여기서 정리하지 않는다 (research.md 결정 5)
  - `/`를 옮기지 않는다. 기존 `path="*"` 규칙이 모두 `/`로 보내고 있어 자리를 옮기면 그 규칙까지 흔들린다
  - 테스트: 메뉴 링크 네 개가 모두 있고, 가장 중요한 행동 버튼이 하나이며, 그 버튼이 `/diagnosis/sectors`로 간다
- [X] T004 [M1] [US4] [FE] 라우트 추가 (`frontend/src/App.tsx`) · Jira 미발급
  - `/learn`을 T002의 준비 중 화면에 잇는다
  - `/diagnosis/sectors`도 **잠시** 같은 준비 중 화면에 잇는다. M2가 실제 화면으로 바꾼다. 이 연결이 없으면 '전략 진단하기'가 `path="*"` 규칙에 걸려 첫 화면으로 되돌아오고, FR-001이 요구한 "정상 화면으로 처리한다"를 M1 혼자서는 만족하지 못한다
  - 기존 경로(`/home`·`/dashboard`·`/backtest`·`/param-search`·`/records/*`·`/login`·`/signup`)를 **지우지 않는다.** 각각 003·006·007·009가 소유할 자리다 (research.md 결정 5)
  - M2의 T010도 이 파일을 고친다. 두 묶음을 병렬로 두지 않는 까닭이다

**Checkpoint**: 첫 화면이 FR-001·FR-002를 만족하고 quickstart 시나리오 1의 1~3단계를 밟을 수 있다. 4단계(섹터 선택)는 M3까지 가야 완성되므로 이 시점에 SC-001은 아직 성립하지 않는다.

---

## M2: 섹터 목록 조회와 표시

**목표**: 섹터 목록을 받아 이름·대표 ETF 이름·업종/테마 구분과 함께 보이고, 아직 받을 수 없는 상태를 정상 화면으로 처리한다.

**Independent Test**: `/diagnosis/sectors`에 들어가 섹터 목록이 표시 순서대로 보이는지, 응답의 `isSample`이 true일 때만 예시 표시가 붙는지, 409와 네트워크 실패에서 빈 목록이나 오류 화면이 아니라 한 문장 안내와 다시 불러오기 하나가 보이는지 확인한다.

- [X] T005 [M2] [FE] 테스트 수집 경로 확장과 수집 가드 (`frontend/vite.config.ts`, `frontend/src/diagnosis/testCollection.guard.test.tsx`) · Jira 미발급
  - **이 묶음의 다른 어떤 `.test.ts`보다 먼저 한다.** 현재 `test.include`는 `['src/**/*.test.tsx', 'src/api/auth.test.ts', 'src/auth/**/*.test.ts']`라서 새로 만드는 `.test.ts`는 **실패하지 않고 그냥 수집되지 않는다** (`frontend/vite.config.ts:17`, quickstart 시나리오 0)
  - `include`에 `'src/diagnosis/**/*.test.ts'`를 더한다. 앞으로 다른 폴더에 `.test.ts`가 생기면 같은 함정이 되풀이되므로 아래 가드가 그 사실을 알린다
  - 가드 테스트는 확장자를 **`.tsx`로 만든다.** 확장자 변경 없이도 반드시 수집되는 패턴은 `src/**/*.test.tsx` 하나뿐이고, 가드 자신이 수집되지 않으면 아무것도 지키지 못한다
  - 가드가 하는 일: `node:fs`로 `frontend/src`를 재귀 순회해 `*.test.ts` 파일을 모두 모으고, 그 가운데 `vite.config.ts`의 `test.include`와 `package.json`의 `test:legacy` 인자 목록 어느 쪽에도 걸리지 않는 파일이 하나라도 있으면 **실패한다**
  - `include` 값은 `vite.config.ts` 모듈을 가져와 `test.include`로 읽는다. 플러그인 실행이 문제가 되면 파일을 텍스트로 읽어 배열 리터럴을 뽑는 방식으로 바꾸되, **뽑지 못하면 건너뛰지 말고 실패시킨다**
  - 패턴 대조는 새 의존성 없이 처리한다. 지금 쓰는 표현은 `dir/**/*.ext`와 정확 경로 두 가지뿐이므로 그 두 형태만 해석하는 짧은 대조 함수를 같은 파일에 둔다. **해석할 수 없는 형태의 패턴이 들어오면 그 자체로 실패시킨다.** 모르는 패턴을 통과로 처리하면 검사가 조용히 사라진다
  - 파일을 하나도 찾지 못했을 때도 실패시킨다. 현재 저장소에 `*.test.ts`가 여섯 개 있으므로(`src/api/auth.test.ts`, `src/api/market.test.ts`, `src/auth/returnTarget.test.ts`, `src/utils/` 셋) 0개는 순회가 잘못되었다는 뜻이다
  - 확인 절차: 일부러 틀린 단언을 하나 넣어 실제로 실패가 나는지 보고 되돌린다. `npm test`의 `skipped` 수가 0인지 함께 본다 (quickstart 시나리오 0)
- [X] T006 [M2] [P] [US1] [FE] 섹터 응답 타입 (`frontend/src/types/sector.ts`) · Jira 미발급
  - `GET /sectors` 응답을 camelCase로 1:1 옮긴다. `mapVersion`·`isSample`·`sectors[].{code,name,classification,representativeEtf{name,listedOn}}` (data-model.md 「SectorCatalog」)
  - 필드 정의의 원본은 [001의 섹터 조회 계약](../001-etf-market-data/contracts/sector-api.md)이다. **002가 재정의하지 않는다**
  - 계약의 불변식("모든 항목에 `representativeEtf`가 있다")을 타입에서 선택 값으로 풀지 않는다. 없는 항목이 오면 그것은 계약 위반이며 화면이 숨겨서 넘기지 않는다
- [X] T007 [M2] [US1] [FE] 섹터 목록 조회 (`frontend/src/api/sectors.ts`, `frontend/src/api/sectors.test.ts`) · Jira 미발급
  - `src/api/client.ts`의 공용 `apiClient`만 거친다. 컴포넌트에서 axios를 직접 부르지 않는다 (`frontend/CLAUDE.md`)
  - 오류 분류는 014가 이미 만든 `normalizeApiError`의 `api | network | unexpected`를 그대로 쓴다. 002가 새 분류를 만들지 않는다 (research.md 결정 6)
  - `409 SECTOR_MAP_NOT_READY`를 예외가 아니라 화면이 분기할 수 있는 상태 값으로 돌려준다
  - 화면 진입마다 이 호출 **한 번**이다. `GET /sectors/evaluation-window`는 부르지 않는다 (research.md 결정 4, contracts/README.md)
  - 선행: T005(이 파일이 `.test.ts`라 include 확장 전에는 수집되지 않는다)
- [X] T008 [M2] [P] [US1] [FE] 표시 순서와 출처 문구 상수 (`frontend/src/diagnosis/sectorOrder.ts`, `frontend/src/diagnosis/sectorOrder.test.ts`, `frontend/src/diagnosis/attribution.ts`) · Jira 미발급
  - 표시 순서는 `classification`(`INDUSTRY` 먼저, `THEME` 다음) 다음에 `Intl.Collator('ko')`로 이름 정렬이다 (research.md 결정 2)
  - **서버 응답의 배열 순서에 기대지 않는다.** 001 계약이 `sectors` 배열의 정렬을 정하지 않아, 응답 순서에 기대면 백엔드 구현이 바뀔 때 화면 규칙이 조용히 따라 바뀐다
  - 이 순서가 M3의 잔여분 자리를 정한다. 같은 집합을 다른 순서로 골라도 결과가 같아야 한다
  - `attribution.ts`에 KRX 출처 문구 상수를 **한 곳에만** 둔다. 값을 지어내는 것이 아니라 001이 이미 고정 값처럼 내보내는 문구를 옮겨 적는 것이다 (research.md 결정 8, 원칙 X)
  - 선행: T005
- [X] T009 [M2] [US1] [FE] 섹터 목록 항목 (`frontend/src/components/diagnosis/SectorListItem.tsx`, `frontend/src/components/diagnosis/SectorListItem.test.tsx`) · Jira 미발급
  - 한 항목에 섹터 이름·대표 ETF 이름·업종/테마 구분을 함께 보인다 (FR-003)
  - 이 시점에는 표시만 한다. 체크박스는 M3의 T015가, 대표 상품 정보 펼침은 M5의 T022가 더한다
  - 계산식·자료 공급원·거리 함수·실험 식별자·서버 설정을 보이지 않는다 (FR-011)
- [X] T010 [M2] [US1] [US4] [FE] 섹터 선택 화면과 조회 상태 분기 (`frontend/src/pages/SectorSelectionPage.tsx`, `frontend/src/pages/SectorSelectionPage.module.css`, `frontend/src/pages/SectorSelectionPage.test.tsx`, `frontend/src/App.tsx`) · Jira 미발급
  - `App.tsx`의 `/diagnosis/sectors`를 T004의 임시 연결에서 이 화면으로 바꾼다
  - 상태 분기 네 가지를 모두 정상 화면으로 처리한다. 불러오는 중 / 목록 / `409 SECTOR_MAP_NOT_READY` / 네트워크 실패·5xx. 뒤의 둘은 T001의 컴포넌트에 다시 불러오기 행동 하나를 주어 표시한다 (FR-017, research.md 결정 6)
  - 409 상태에서는 섹터를 고를 수 없으므로 다음 단계 버튼 자체를 두지 않는다 (FR-017)
  - 응답의 `isSample`이 true일 때만 예시 대응표 표시를 목록 머리에 붙인다 (FR-015, SC-007). **이 표시는 FR-005의 초깃값 예시 표시와 가리키는 대상이 다르므로 한 문장으로 합치지 않는다.** 합치면 SC-007 위반이다
  - T008의 출처 문구를 목록 화면에 표시한다. 상품 정보 패널을 펼쳐야만 보이는 구조는 목록만 보는 사용자에게 미표시와 같다 (원칙 X)
  - 화면 폭 360px에서도 목록이 한 화면에 남는다 (plan Technical Context, `checklists/ux.md` CHK035)
  - 테스트: `isSample` true/false에서 표시 유무(SC-007), 409와 네트워크 실패에서 빈 목록도 처리되지 않은 오류 화면도 나오지 않는다(SC-008)

**Checkpoint**: quickstart 시나리오 7(목록 쪽)과 시나리오 9를 돌릴 수 있다. 섹터를 고르는 일은 아직 되지 않는다.

---

## M3: 선택 상태와 비중 규칙

**목표**: 섹터를 고르면 그 자리에 비중 입력 칸이 생기고 초깃값이 채워진다. 사용자가 비중을 고치면 시스템이 다른 값을 건드리지 않고 남은 비중만 알린다. 값은 탭 세션 동안 남는다.

**Independent Test**: 섹터 3개를 골라 `33.4 / 33.3 / 33.3`이 채워지는지, 한 섹터를 올렸을 때 나머지가 그대로이고 초과가 표시되는지, 새로고침 뒤에도 값이 남는지 확인한다. 비중 규칙은 화면 없이 단위 테스트로 먼저 검증한다.

- [X] T011 [M3] [US2] [FE] 비중 정수 모형과 순수 규칙 (`frontend/src/diagnosis/weights.ts`, `frontend/src/diagnosis/weights.test.ts`) · Jira 미발급
  - 비중을 **0.1%p 단위 정수**로 다룬다. 100%가 `1000`이고 33.4%가 `334`다. 표시할 때만 10으로 나눈다 (research.md 결정 1)
  - 합계 판정은 `sum === 1000`이라는 정수 비교다. **허용 오차(epsilon)를 두지 않는다.** 원칙 VI가 임의 epsilon으로 판정을 만드는 처리를 금지한다
  - `distributeEven(codes)`: `q = floor(1000 / n)`, `r = 1000 - q * n`을 구해 `r`을 **표시 순서상 첫 번째** 섹터에 몰아준다. 초깃값 자동 채움과 '동일하게 나누기'가 같은 함수를 쓴다 (FR-005, FR-007, research.md 결정 2)
  - 입력 파싱은 data-model.md 「사용자 입력 파싱」 표 그대로다. `33.4`는 `334`, `33`은 `330`, `0`은 `0`으로 받는다. `33.45`처럼 소수 둘째 자리 이하와 음수·100 초과·숫자가 아닌 값은 **받지 않고** 그 자리에 한 문장으로 알린다. **반올림하거나 상한으로 잘라 넣지 않는다** (원칙 VI, FR-016)
  - 빈 칸은 0으로 보지 않고 "아직 입력 중"으로 다룬다. 확정 시점에 이전 값을 그대로 둔다
  - 테스트: 선택 수 2~20 전부에서 합계가 정확히 `1000`, 3개면 `334/333/333`, 6개면 `170/166×5`, 7개면 `148/142×6`. 같은 집합을 다른 순서로 넣어도 결과가 같다. 파싱 표의 행마다 한 건씩 (quickstart 시나리오 2)
  - 선행: T005(이 파일이 `.test.ts`다), T008(표시 순서)
- [X] T012 [M3] [P] [US1] [FE] 탭 세션 보관과 복원 검증 (`frontend/src/diagnosis/selectionStorage.ts`, `frontend/src/diagnosis/selectionStorage.test.ts`) · Jira 미발급
  - `sessionStorage` 항목 **하나**에 JSON으로 저장한다. 키는 `thorp.diagnosis.sectorSelection`이며 014의 `thorp.auth.returnTarget`과 같은 이름 규칙이다 (research.md 결정 3)
  - 페이로드 구조와 불변식의 원본은 [contracts/selection-handoff.md](./contracts/selection-handoff.md)다. `version`·`mapVersion`·`selected[]`·`weightsTouched`를 담는다
  - **읽어 온 값을 신뢰하지 않는다.** `sessionStorage`는 사용자가 편집할 수 있다. 섹터 코드는 현재 `GET /sectors` 응답에 있는 코드만, 비중은 0 이상 1000 이하 정수만 받아들인다
  - 검증에서 빠진 항목은 **조용히 버리지 않고** 빠졌다는 사실을 한 문장으로 알릴 수 있게 `droppedCodes`로 돌려준다. 무엇을 제안할지는 [006 FR-008](../006-my-records/spec.md)과 UI-01이 소유하므로 002가 정하지 않는다 (원칙 VI)
  - **합계를 저장 시점에 맞추지 않는다.** 합계가 1000이 아닌 상태도 그대로 보관한다. 맞추면 원칙 VI를 어긴다 (계약 불변식 3)
  - `version`이 읽는 쪽이 아는 값보다 크면 그 페이로드를 쓰지 않고 빈 상태로 시작하되 한 문장으로 알린다
  - 저장소 접근이 막힌 환경에서는 예외를 삼키고 메모리 상태로만 동작한다. 화면을 오류로 막지 않는다 (spec Edge Cases)
  - 테스트: 왕복 보존, 목록에 없는 코드 제거와 `droppedCodes` 보고, 음수·1000 초과·정수가 아닌 값 거부, 0 허용, 합계 불일치 보존, `version` 상향 시 빈 상태, 저장소 예외에서 화면이 죽지 않음
  - 선행: T005
- [X] T013 [M3] [US1] [US2] [FE] 선택 상태 Context와 상태 전이 (`frontend/src/diagnosis/SectorSelectionProvider.tsx`, `frontend/src/diagnosis/SectorSelectionProvider.test.tsx`) · Jira 미발급
  - data-model.md 「상태 전이」 표의 아홉 행을 그대로 구현한다. 분기값은 `weightsTouched`다
  - 아직 고치지 않았으면 섹터를 더하거나 뺄 때마다 **전체를** 동일 비중으로 다시 채운다. 한 번이라도 고쳤으면 기존 값을 건드리지 않고 새 섹터에만 0을 준다 (FR-005, SC-009)
  - 섹터를 뺄 때는 남은 값을 그대로 둔다. 합계가 100% 아래로 내려가는 것은 오류가 아니라 FR-007이 안내하는 정상 상태다
  - '동일하게 나누기'는 값을 다시 채우면서 `weightsTouched`를 **false로 되돌린다** (research.md 결정 10). 그 뒤 섹터를 더하면 다시 전체가 동일 비중으로 채워진다
  - 해제했다가 다시 고르는 것은 '섹터 체크'와 같다. 이전 비중을 기억해 두었다가 되살리지 않는다
  - 파생값(`totalTenths`·`isBalanced`·`remainderTenths`·`canProceed`·`hasZeroWeight`)을 저장하지 않고 매번 계산한다. 저장하면 원본과 어긋날 창이 생긴다
  - 상태가 바뀔 때마다 T012로 저장하고 화면 진입 때 읽는다 (FR-014)
  - 전역 상태 라이브러리를 도입하지 않는다. React Context로 충분하다 (plan 「Structure Decision」)
  - 테스트: 전이 표의 행마다 한 건씩. 특히 "고친 뒤 섹터 추가"에서 기존 값이 하나도 바뀌지 않고 새 섹터가 0인지(SC-009), 세션 복원 뒤 값이 그대로인지(SC-006)
  - 선행: T011, T012
- [X] T014 [M3] [US2] [FE] 비중 입력 칸 (`frontend/src/components/diagnosis/WeightInput.tsx`, `frontend/src/components/diagnosis/WeightInput.test.tsx`) · Jira 미발급
  - 섹터를 고르면 **같은 화면 그 자리에** 나타난다. 다른 화면으로 나누지 않는다 (spec 범위 요약, FR-005)
  - 값 검사는 T011의 파싱 규칙 하나만 쓴다. 컴포넌트가 따로 판정하지 않는다
  - `step`을 0.1로 두되 **붙여넣기처럼 `step`을 우회하는 경로도 같은 검사를 거친다** (research.md 결정 1)
  - 받지 않은 입력은 말없이 무시하지 않고 그 자리에 한 문장으로 알린다. 받지 않는 것도 사용자가 보게 되는 처리다 (spec Edge Cases)
  - 0%를 거부하지 않는다 (FR-016, SC-010)
  - 키보드만으로 입력과 확정을 끝낼 수 있어야 한다 (`checklists/ux.md` CHK033)
  - 테스트: `33.45`·음수·`120`·문자 입력이 거부되고 안내가 보인다. 거부된 뒤 이전 값이 그대로다. `0` 입력이 통과한다
- [X] T015 [M3] [US1] [US2] [FE] 합계 표시·동일하게 나누기·두 개 이상 제약 결선 (`frontend/src/components/diagnosis/WeightTotal.tsx`, `frontend/src/components/diagnosis/SectorListItem.tsx`, `frontend/src/pages/SectorSelectionPage.tsx`, `frontend/src/pages/SectorSelectionWeights.test.tsx`) · Jira 미발급
  - 목록 항목에 체크박스를 더하고 화면을 T013의 Context에 연결한다
  - 합계가 1000이 아니면 **그 위치에** 남은 비중을 부족·초과로 표시한다. 별도 알림 영역으로 빼지 않는다 (FR-007, 원칙 VI, research.md 결정 7)
  - 비중을 자동으로 정규화하거나 조정하지 않는다 (SC-002)
  - '동일하게 나누기' 버튼은 T013의 되돌리기를 부른다
  - 초깃값이 입력 편의를 위한 예시이며 투자 권고가 아니라는 표시를 비중 입력 묶음의 머리에 **항상** 둔다 (FR-005, 원칙 III, research.md 결정 7)
  - 섹터를 하나만 고르면 다음 단계로 넘어가지 않고 두 개 이상 필요하다는 안내를 보인다 (FR-004). 다음 단계 버튼 자체는 M4의 T019가 만든다
  - 합계 상태 변화와 조건부 안내가 보조 기술에 전달되게 한다 (`checklists/ux.md` CHK034)
  - 테스트: quickstart 시나리오 3과 4의 표를 그대로 밟는다. 3개 선택 뒤 값, 추가·수정·추가·해제·되돌리기 여섯 단계의 기대 결과
  - 선행: T013, T014

**Checkpoint**: quickstart 시나리오 1·2·3·4·5·8을 돌릴 수 있다. SC-001·SC-002·SC-006·SC-009·SC-010이 이 시점에 성립한다.

---

## M4: 선택 단계 안내와 다음 단계 인계

**목표**: 상장일 차이로 비교 기간이 줄어드는 사실을 선택 단계에서 알리고, 사용자가 입력한 원본을 가공 없이 다음 단계에 남긴다.

**Independent Test**: 상장일이 서로 다른 섹터를 골라 언제부터 비교할 수 있는지와 어느 섹터 때문인지 한 문장이 보이는지 확인한다. 합계를 맞춘 뒤 다음 단계로 넘겨 보관된 값이 마지막 입력과 한 글자도 다르지 않은지 확인한다.

- [X] T016 [M4] [P] [US3] [FE] 상장일 기준 공통 비교 시작일 (`frontend/src/diagnosis/listingWindow.ts`, `frontend/src/diagnosis/listingWindow.test.ts`) · Jira 미발급
  - `commonStartFromListing = max(선택 섹터의 representativeEtf.listedOn)`과 그 최댓값을 만든 `bindingSectorCode`를 구한다 (research.md 결정 4)
  - **`GET /sectors/evaluation-window`를 부르지 않는다.** 그 산식은 003이 소유하는 워밍업 거래일 수를 필수 입력으로 받아 전략을 고르기 전에는 채울 수 없다 (contracts/README.md)
  - 경계: 선택 섹터가 두 개 미만이거나 상장일이 모두 같으면 안내할 사실이 없다. `commonStartFromListing`이 오늘 이후면 비교할 기간이 남지 않은 것으로 본다. **이 판정은 근사치다.** 정확한 판정은 003이 `evaluable: false`로 받는다 (research.md 결정 4의 경계 동작)
  - 화면이 음수 기간을 계산해 이상한 문장을 만들지 않게 방어한다
  - 테스트: 상장일이 같을 때 안내 없음, 다를 때 최댓값과 원인 섹터, 선택 1개 이하, 최댓값이 오늘 이후인 경우
  - 선행: T005
- [X] T017 [M4] [US3] [FE] 상장일 차이 안내 (`frontend/src/components/diagnosis/ListingWindowNotice.tsx`, `frontend/src/components/diagnosis/ListingWindowNotice.test.tsx`) · Jira 미발급
  - 선택 섹터가 둘 이상이고 상장일이 서로 다를 때만 선택 요약 옆에 한 문장으로 나타난다 (research.md 결정 7)
  - 문장이 담을 것 셋: 언제부터 비교할 수 있는지, 어느 섹터의 상장일 때문인지, **이 값이 상장일만 반영한 것이며 전략 워밍업 몫은 003이 알린다는 사실** (FR-008)
  - 데이터 입력이나 자료 공급원 선택을 요구하지 않는다 (FR-008, SC-005)
  - 접어 두고 '자세히 보기'로 펼치지 않는다. 원칙 II가 결과 의미를 바꾸는 자료 부족 표시를 상세 안에 숨기지 못하게 한다
  - 대표 ETF가 없는 섹터는 001 계약의 불변식에 따라 목록에 실리지 않으므로 그 경우의 안내를 두지 않는다 (spec Clarifications)
  - 테스트: 상장일이 같으면 보이지 않고 다르면 보인다(SC-003). `GET /sectors` 외의 호출이 일어나지 않는다
  - 선행: T016
- [X] T018 [M4] [US3] [US2] [FE] 후보 집합과 0%의 뜻 안내 (`frontend/src/components/diagnosis/SelectionMeaningNotice.tsx`, `frontend/src/components/diagnosis/SelectionMeaningNotice.test.tsx`) · Jira 미발급
  - 섹터를 하나 이상 고른 뒤 **항상** 비중 입력 묶음의 꼬리에 한 문장을 둔다. 선택 섹터는 자동 배분 전략의 투자 후보 집합이며 매번 모두 보유한다는 뜻이 아니고, 사용자가 입력한 비중은 '내가 정한 비중'에 쓰여 자동 전략의 출력 비중과 구분된다 (FR-009, research.md 결정 7)
  - 비중이 0인 섹터가 하나 이상일 때만 같은 자리에 한 문장을 더한다. 0%인 섹터는 '내가 정한 비중'에는 담기지 않지만 후보 집합에는 그대로 남는다 (FR-016)
  - 0%인 섹터를 선택 목록에서 빼지 않는다. 003이 선택 섹터 개수로 유효성 제약을 계산하므로 빼면 003의 계산과 어긋난다 (SC-010)
  - 테스트: 0인 섹터가 없을 때 0% 안내가 보이지 않는다. 있을 때만 보인다
- [X] T019 [M4] [US1] [FE] 다음 단계 버튼과 003 인계 (`frontend/src/pages/SectorSelectionPage.tsx`, `frontend/src/diagnosis/selectionStorage.ts`, `frontend/src/pages/SectorSelectionHandoff.test.tsx`) · Jira 미발급
  - 다음 단계 버튼의 활성 조건은 `selectedCodes.length >= 2 && isBalanced`다 (data-model.md 파생값)
  - 합계가 맞지 않는 상태로는 넘어가지 않고 한 문장으로 안내한다. **그 자리에서 비중을 자동으로 조정하지 않는다** (FR-007)
  - **넘기기 전에 값을 손대지 않는다.** 합계를 100%로 맞추는 조정, 표시 정밀도를 넘어선 반올림, 0%인 섹터 제외를 모두 하지 않는다 (FR-013, SC-013)
  - 값을 만들 당시의 `mapVersion`을 함께 남긴다. 003·004가 재현 기록에 넣을 수 있게 하기 위해서다 (원칙 VIII)
  - 인계 수단은 T012의 `sessionStorage` 항목 하나다. URL이나 navigation state로 따로 싣지 않는다. 새로고침으로 화면이 다시 뜨는 경우와 같은 경로를 쓰기 위해서다 (contracts/selection-handoff.md)
  - 섹터 이름과 대표 ETF 이름을 페이로드에 싣지 않는다. 003이 `GET /sectors`로 직접 받는다
  - 비중을 백분율 소수로 바꾸지 않는다. 004 메시지 계약으로의 변환은 003의 몫이다
  - 화면 전체에 계산식·자료 공급원·거리 함수·실험 식별자·서버 설정 입력 항목이 0개인지 이 시점에 함께 확인한다 (FR-011, SC-005, quickstart 시나리오 11)
  - **테스트가 SC-013을 받는다.** quickstart에 SC-013 시나리오가 없기 때문이다. 마지막 입력값과 보관된 페이로드를 값으로 비교하고, 0%인 섹터가 빠지지 않았는지와 `mapVersion`이 함께 실렸는지 확인한다
  - 선행: T013, T015
- [X] T020 [M4] [US4] [FE] 배우기 예시 진입과 덮어쓰기 확인 (`frontend/src/diagnosis/learningExample.ts`, `frontend/src/components/diagnosis/OverwriteConfirmDialog.tsx`, `frontend/src/pages/SectorSelectionLearningEntry.test.tsx`) · Jira 미발급
  - 예시 값은 React Router의 navigation state로 받는다. 페이로드 형태는 [contracts/selection-handoff.md](./contracts/selection-handoff.md) 2절이다 (research.md 결정 9)
  - 받은 값은 T012와 **같은 검증**을 거친다. 현재 목록에 있는 코드, 0 이상 1000 이하 정수 비중
  - 아직 아무것도 입력하지 않았으면 **묻지 않고** 채운다. 이미 고른 섹터나 입력한 비중이 있으면 덮어쓸지 먼저 묻고, 거절하면 기존 값을 그대로 둔다 (FR-012, SC-011)
  - 예시 합계가 1000이 아니어도 그대로 채우고 FR-007의 안내로 처리한다. 자동으로 맞추지 않는다
  - 한 번 읽고 소비한 뒤 navigation state를 `replace`로 지운다. 지우지 않으면 뒤로 가기나 새로고침 때 확인이 다시 뜬다
  - 대화상자는 `radix-ui`의 요소를 쓴다. 이미 의존성에 있고 014가 같은 방식으로 초점 관리와 키보드 조작을 얻었다 (research.md 결정 7, `checklists/ux.md` CHK033)
  - 013은 아직 없으므로 테스트에서 navigation state를 직접 실어 확인한다 (quickstart 시나리오 10)
  - 예시 내용 자체(어떤 주제가 어떤 섹터·비중을 쓰는가)는 013이 소유한다. 002가 정하지 않는다
  - 테스트: quickstart 시나리오 10의 네 행. 입력이 하나도 없을 때 확인을 묻지 않는 경우가 0건인지 함께 본다(SC-011)
  - 선행: T013

**Checkpoint**: quickstart 시나리오 6·10·11을 돌릴 수 있다. SC-003·SC-005·SC-011·SC-013이 이 시점에 성립한다.

---

## M5: 대표 상품 정보 패널

**목표**: 목록 항목에서 대표 상품 정보를 그 자리에 펼쳐 본다. 자료를 아직 받을 수 없어도 빈 자리가 아니라 한 문장이 보인다.

**Independent Test**: 목록의 모든 항목에서 대표 상품 정보를 열 수 있는지, 여는 동안 화면을 떠나지 않는지, `409 INSTRUMENT_PROFILE_NOT_READY`에서 빈 패널이 아니라 한 문장이 보이는지 확인한다.

- [X] T021 [M5] [US1] [FE] 대표 상품 정보 조회 (`frontend/src/api/sectors.ts`, `frontend/src/types/sector.ts`, `frontend/src/api/sectors.test.ts`) · Jira 미발급
  - `GET /sectors/{code}/instrument-profile`을 T007과 같은 `apiClient` 경로로 더한다
  - `409 INSTRUMENT_PROFILE_NOT_READY`를 예외가 아니라 화면이 분기할 수 있는 상태 값으로 돌려준다 (research.md 결정 6)
  - 응답의 `isSample`은 **자료 버전 기준**이며 `GET /sectors`의 `isSample`(대응표 기준)과 다른 것을 가리킨다. 한쪽 값으로 다른 쪽을 짐작하지 않는다 (data-model.md)
  - M2가 만든 두 파일을 다시 고치므로 M2와 병행하지 않는다
- [X] T022 [M5] [US1] [FE] 대표 상품 정보 패널과 목록 연결 (`frontend/src/components/diagnosis/InstrumentProfilePanel.tsx`, `frontend/src/components/diagnosis/SectorListItem.tsx`, `frontend/src/components/diagnosis/InstrumentProfilePanel.test.tsx`) · Jira 미발급
  - **같은 화면에서 펼친다.** 상세를 보려고 다른 화면으로 이동시키지 않는다 (FR-010, 원칙 II)
  - 값을 가공하지 않고 그대로 표시한다. `sourceAttribution`도 응답 값을 쓴다(목록 화면의 상수와 달리 여기서는 계약이 값을 준다)
  - `holdingsOverlap.asOfCurrentComposition`이 true면 당시 구성을 재현했다고 표현하지 않는다 (원칙 V, 001 계약)
  - 409는 빈 패널이 아니라 T001의 한 문장 표시로 처리한다
  - 여는 방법·닫는 방법을 키보드로도 쓸 수 있게 한다 (`checklists/ux.md` CHK005·CHK033)
  - **테스트가 SC-012를 받는다.** quickstart에 SC-012 시나리오가 없기 때문이다. 목록의 모든 항목에서 열리는지, 여는 동안 경로가 `/diagnosis/sectors`에서 바뀌지 않는지, 409에서 빈 자리가 아니라 한 문장이 보이는지 확인한다
  - 선행: T021, T015(같은 `SectorListItem.tsx`를 고친다)
- [X] T023 [M5] [FE] 전체 흐름 검증 (`specs/002-sector-weight-selection/quickstart.md`의 시나리오 0~11 수행, 코드 수정은 발견된 어긋남에 한한다) · Jira 미발급
  - 각 묶음은 자기 몫의 시나리오를 이미 그 묶음 안에서 돌렸다. 여기서는 모두 병합된 상태로 처음부터 끝까지 한 번 더 밟는다
  - **시나리오 0을 먼저 한다.** `npm test`의 수집된 파일 수와 `skipped` 수를 보고, `src/diagnosis/`의 테스트가 목록에 있는지 확인한다. T005의 가드가 통과하는지도 함께 본다
  - `npm run lint && npm run build`와 `npm test`를 모두 돌린다
  - 시나리오 7의 409 상태는 백엔드를 그 상태로 띄우거나 테스트 스텁으로 확인한다
  - 화면 폭 360px과 키보드 전용 조작을 함께 확인한다 (`checklists/ux.md` CHK033~CHK035)
  - **`quickstart.md`를 고치지 않는다.** 시나리오와 어긋나는 점을 찾으면 코드를 고치고, 시나리오 자체가 틀렸다고 판단되면 고치지 말고 MR 본문에 적어 소유자가 판단하게 한다
  - SC-004(사용성 관찰)는 여기서 판정하지 않는다. 사람이 화면을 써 보는 것을 관찰하는 항목이라 코드로 판정할 수 없고, 화면이 완성된 뒤 별도로 진행한다

**Checkpoint**: quickstart 시나리오 0~11이 모두 통과한다. SC-004를 뺀 열두 개의 성공 기준이 성립한다.

---

## 의존과 실행 순서

```
M1 ──> M2 ──> M3 ──> M4 ──> M5
```

- **M1 → M2**: 둘 다 `frontend/src/App.tsx`를 고친다. 병렬로 두지 않는다
- **M2 → M3**: M3의 선택 상태가 M2의 섹터 목록과 표시 순서 위에서 동작한다
- **M3 → M4**: M4는 M2·M3이 만든 값을 읽기만 한다
- **M4 → M5**: M5의 T022가 M3의 T015와 같은 `SectorListItem.tsx`를 고친다. 순서를 지키면 충돌하지 않는다
- **M4와 M5를 병행하려면** `SectorSelectionPage.tsx`의 변경을 한쪽으로 몰아야 한다. M4는 안내와 버튼을, M5는 목록 항목과 패널을 고쳐 대부분 겹치지 않지만 페이지 파일만은 겹칠 수 있다

**묶음 안의 병렬 가능 조합**

| 묶음 | 함께 진행할 수 있는 것 | 까닭 |
|---|---|---|
| M1 | T001, T003 | 서로 다른 파일이고 의존이 없다. T002는 T001을, T004는 T002·T003을 기다린다 |
| M2 | T006, T008 | T005를 끝낸 뒤라면 서로 다른 파일이다. T007은 T006을, T009는 T006을, T010은 T007·T008·T009를 기다린다 |
| M3 | T012 | T011과 다른 파일이다. T013은 둘 다, T014는 T011, T015는 T013·T014를 기다린다 |
| M4 | T016, T018, T020 | 서로 다른 파일이다. T017은 T016을, T019는 M3을 기다린다 |
| M5 | 없음 | T021 → T022 → T023이 차례로 이어진다 |

**T005는 이 tasks 전체에서 가장 먼저 해야 할 실질적 작업이다.** M1은 `.test.tsx`만 만들어 영향을 받지 않지만, M2 이후의 모든 `.test.ts`가 이 task 없이는 조용히 수집되지 않는다.

## 요구사항 추적

### 기능 요구사항

| 요구사항 | 다루는 T항목 |
|---|---|
| FR-001 첫 화면 네 메뉴와 행동 버튼 하나, 준비 중 처리 | T001, T002, T003, T004 |
| FR-002 설문·숙련도 분류·사전 학습 없음 | T003 |
| FR-003 섹터 이름·대표 ETF 이름·업종/테마 표시 | T006, T009 |
| FR-004 두 개 이상 선택해야 다음 단계 | T013, T015, T019 |
| FR-005 그 자리 비중 칸, 동일 비중 초깃값, 잔여분 규칙, 수정 여부 분기 | T011, T013, T014, T015 |
| FR-006 비중 직접 수정과 합계 판정 | T011, T014 |
| FR-007 남은 비중 표시, 동일하게 나누기, 자동 조정 금지 | T011, T013, T015, T019 |
| FR-008 상장일 차이 안내 | T016, T017 |
| FR-009 후보 집합과 '내가 정한 비중' 구분 | T018 |
| FR-010 대표 상품 정보를 같은 화면에서 | T021, T022 |
| FR-011 보이는 정보 제한 | T009, T019 |
| FR-012 배우기 예시 진입과 덮어쓰기 확인 | T020 |
| FR-013 원본을 가공 없이 인계 | T012, T019 |
| FR-014 탭 세션 유지 | T012, T013 |
| FR-015 예시 대응표 표시 | T010 |
| FR-016 0% 허용과 그 뜻 안내 | T011, T014, T018 |
| FR-017 대응표 미발행을 정상 화면으로 | T001, T007, T010 |

열일곱 개 모두 다뤄진다.

### 성공 기준

| 성공 기준 | 다루는 T항목 | 검증 수단 |
|---|---|---|
| SC-001 두 번의 행동으로 다음 단계 | T003, T004, T010, T013, T019 | quickstart 시나리오 1 (T023) |
| SC-002 비중 자동 변경 0건 | T011, T014, T015 | 단위 테스트 + quickstart 시나리오 3 |
| SC-003 상장일 안내가 선택 단계에 100% | T016, T017 | T017 테스트 + quickstart 시나리오 6 |
| SC-004 사용성 관찰 기록과 조치 | **없음** | **코드로 판정하지 않는다.** 사람이 화면을 써 보는 것을 관찰하는 항목이며 화면 완성 뒤 별도로 진행한다. 위 「이 tasks가 다루지 않는 것」 참고 |
| SC-005 계산식·공급원 입력 0개 | T019 | quickstart 시나리오 11 |
| SC-006 새로고침·로그인 왕복 유지 100%, 새 탭 0건 | T012, T013 | 단위 테스트 + quickstart 시나리오 8 |
| SC-007 예시 표시가 조건대로만 | T010 | T010 테스트 + quickstart 시나리오 9 |
| SC-008 아직 줄 것이 없는 상태에 한 문장 | T001, T002, T010 | T010 테스트 + quickstart 시나리오 7 |
| SC-009 고친 뒤 추가해도 기존 값 불변, 새 섹터 0% | T011, T013 | 단위 테스트 + quickstart 시나리오 4 |
| SC-010 0% 거부·자동 변경·제외 0건 | T011, T014, T015, T018 | 단위 테스트 + quickstart 시나리오 5 |
| SC-011 덮어쓰기 확인 | T020 | T020 테스트 + quickstart 시나리오 10 |
| SC-012 모든 항목에서 상품 정보 열림, 화면 이탈 0건 | T022 | **T022의 자동 테스트.** quickstart에 해당 시나리오가 없다 |
| SC-013 인계 값이 마지막 입력과 동일 | T019 | **T019의 자동 테스트.** quickstart에 해당 시나리오가 없다 |

열세 개 가운데 열두 개를 다룬다. SC-004 하나가 남으며 까닭은 위 표에 적었다.

## 구현 전략

### 가장 작은 쓸모 있는 단위 (M1 + M2 + M3)

1. M1을 병합한다. 첫 화면이 FR-001·FR-002를 만족한다
2. M2를 병합한다. 섹터 목록이 보이고 아직 줄 것이 없는 상태가 정상 화면으로 처리된다
3. M3을 병합한다. **여기까지가 002의 최소 완성이다.** 섹터를 고르고 비중을 맞춰 값이 탭 세션에 남는다. User Story 1과 2(둘 다 P1)가 성립한다
4. 멈추고 quickstart 시나리오 1~5와 8을 확인한다

### 이어 붙이기

5. M4를 병합한다. User Story 3(P2)과 배우기 진입이 붙는다
6. M5를 병합한다. 대표 상품 정보 패널이 붙고 T023이 전체를 한 번 더 확인한다

각 단계는 앞 단계를 깨지 않는다. 되돌릴 때도 그 묶음만 빠지고 앞 단계가 남는다.

## Notes

- `[P]`는 다른 파일을 건드리고 의존이 없다는 뜻이다. 같은 파일을 고치는 task에는 붙이지 않았다
- 묶음 하나를 끝낼 때마다 `npm run lint && npm run build`와 `npm test`를 모두 돌린다. **`skipped` 수를 함께 본다**
- 002는 `frontend/` 밖을 고치지 않는다. `backend/`·`worker/`·`data/`·`infra/`에 변경이 없다 (research.md 결정 4)
- 구현 중 묶음 범위가 달라지면 그 사유와 새 구성을 MR 본문에 남긴다 (constitution 「Jira 연계」)
- 화면 문구는 이 tasks가 정하지 않는다. 각 task는 문장이 놓일 자리와 나타나는 조건까지 만든다

---

## Phase 6: Convergence (M6: 앞 묶음이 남긴 정리)

`/speckit-converge`가 M1·M2가 병합·커밋된 상태의 코드를 spec·plan·tasks와 맞대어 본 결과다. **M3~M5가 아직 없는 것은 예정된 순서이므로 여기 담지 않는다.** 아래 두 항목은 M1·M2를 하면서 tasks.md 밖에서 함께 넣은 변경이 남긴 뒷정리이며, 근거를 항목마다 적었다. 기존 T001~T023의 번호와 내용은 건드리지 않았다.

검증 기준선(2026-09-16, 이 브랜치): `npm run lint` 오류 0(경고 19, 모두 기존 차트·UI 파일), `npm run build` 통과, `npm test` Vitest 29파일 189건 통과·`skipped` 0, `test:legacy` 13건 통과·`skipped` 0. `src/diagnosis/`의 테스트가 수집 목록에 있다(quickstart 시나리오 0 충족). 아래 두 항목을 마친 뒤에도 이 숫자가 줄지 않아야 한다.

- [X] T024 [M6] [US4] [FE] 첫 화면 소개 섹션의 행동 유도 목적지를 헤더 메뉴와 맞춘다 (`frontend/src/components/landing/LandingSections.tsx`, `frontend/src/components/landing/AlgorithmOrbit.tsx`, `frontend/src/pages/LandingPage.test.tsx`) · FR-001 / research.md 결정 5 (contradicts) · Jira 미발급
  - **남은 일이라고 판단한 근거**: 이 브랜치의 커밋 `80e1900`이 `AppHeader`에서 '대시보드'와 '백테스트'를 뺐고 그 사유를 "003·005가 완성되면 대체될 임시 화면"이라고 적었다. 그런데 같은 첫 화면이 여전히 그 두 화면을 소개 경로로 안내한다. `LandingSections.tsx:171`이 `/dashboard`로("시장 분석 보러 가기"), `LandingSections.tsx:256`과 `AlgorithmOrbit.tsx:203`이 `/backtest`로 보낸다. 메뉴에서 뺀 화면을 첫 화면이 계속 권하면 두 판단이 서로를 무효로 만든다
  - 더 좁게 어긋난 자리가 하나 있다. 헤더의 '시장 흐름' 메뉴는 `/home`인데 첫 화면의 "시장 분석 보러 가기"는 `/dashboard`다. **같은 성격의 목적지가 둘로 갈렸다.** research.md 결정 5가 "메뉴의 목적지는 이미 화면이 있으면 그 경로로 연결한다(시장 흐름은 `/home`)"고 정한 자리다
  - 세 링크를 지울지, 목적지를 메뉴와 같은 곳으로 옮길지, 그대로 둘지는 이 task가 미리 정하지 않는다. **다만 그대로 두기로 하면 왜 메뉴와 다른 곳을 가리켜도 되는지를 MR 본문에 남긴다.** 커밋 `398748f`이 "머리 영역을 지나야 닿는 자리라 '전략 진단하기'와 자리를 다투지 않는다"고 이미 판단한 것은 **링크를 남길지**에 대한 판단이고, 이 task가 묻는 것은 **어디를 가리킬지**다. 둘은 다른 질문이다
  - `/dashboard`·`/backtest` 경로 자체는 지우지 않는다. 003·005가 소유할 자리다 (research.md 결정 5)
  - 테스트: `LandingPage.test.tsx`의 "소개 섹션의 링크는 머리 영역을 지나야 나오므로 그대로 둔다"가 지금 목적지를 고정하고 있다. 목적지를 옮기면 이 검사도 함께 옮긴다. 검사를 지우기만 하면 다음에 같은 어긋남이 조용히 돌아온다
  - **2026-09-17 판단: 지금은 그대로 둔다. 다만 의문은 닫지 않았으므로 이 task를 열어 둔다.** 세 링크를 지우지도 옮기지도 않았다. 003·005가 `/dashboard`·`/backtest`를 실제로 대체하면 그 화면들이 어디로 가야 하는지가 그때 정해지므로, 지금 옮겨 두면 두 번 고치게 된다. **답하지 못한 물음은 이것이다 — 메뉴에서 뺀 화면을 첫 화면 본문이 계속 권해도 되는가.** 특히 헤더의 '시장 흐름'이 `/market-flow`인데 본문의 "시장 분석 보러 가기"는 `/dashboard`라, 같은 성격의 목적지가 둘로 갈린 상태가 그대로 남았다. 009가 들어오면서 헤더 쪽 경로가 `/home`에서 `/market-flow`로 바뀌어 간격이 처음 적었을 때보다 더 벌어졌다
  - **이 task를 닫는 조건**: 003·005가 각자 화면을 내어 `/dashboard`·`/backtest`의 자리를 대신할 때, 또는 그 전에 첫 화면의 안내 구조를 다시 볼 때다. 둘 중 무엇이 먼저든 그 시점에 지울지 옮길지 정한다
  - **2026-09-22 판단: 목적지를 메뉴와 같은 곳으로 옮겼다.** 닫는 조건이 충족됐다 — 003·005가
    화면을 내어 `/diagnosis/sectors`·`/market-flow` 가 그 자리를 대신하고 있고, 사용자가 첫
    화면에서 옛 데모 화면으로 떨어지는 것이 실제로 보고됐다. 세 링크를 옮겼다: "시장 분석 보러
    가기"→`/market-flow`, "백테스트 시작하기"·"이 전략 백테스트하기"→`/diagnosis/sectors`.
    백테스트를 진단 출발점으로 보내는 것은 백테스트가 섹터·전략을 고른 뒤에 돌리는 것이기
    때문이다. `/dashboard`·`/backtest` 경로 자체는 그대로 둔다(research 결정 5).
  - 테스트가 목적지를 보지 않고 링크 존재만 보고 있었다. 목적지를 단언으로 바꾸고, 전략을 골라야
    나오는 링크는 화면으로 닿지 않으므로 `src/components/landing` 소스를 훑어 옛 경로가 없는지
    함께 본다.
- [X] T025 [M6] [FE] 서비스 공용이 된 응답 봉투·오류 이름 타입을 인증 전용 파일에서 분리한다 (`frontend/src/types/api.ts` 신설, `frontend/src/types/auth.ts`, `frontend/src/types/sector.ts`, `frontend/src/api/client.ts`, `frontend/src/api/sectors.ts`) · plan 「Structure Decision」 / `frontend/CLAUDE.md` 폴더 규칙 (partial) · Jira 미발급
  - **남은 일이라고 판단한 근거**: 커밋 `1a5f483`이 오류 이름 목록을 인증 전용에서 서비스 공용으로 넓히면서 `ServiceApiErrorCode`·`ApiErrorCode`·`ApiEnvelope`·`ApiSuccessEnvelope`·`ApiErrorEnvelope`를 `src/types/auth.ts`에 그대로 두었다. 그 결과 **의존 방향이 꼬였다.** `types/auth.ts`가 `types/sector.ts`를 가져오고(`types/auth.ts:1`), 섹터 조회는 봉투를 얻으려고 `@/types/auth`를 가져온다(`api/sectors.ts:2`). 섹터 도메인이 인증 도메인 파일을 거쳐야 공용 봉투에 닿는다
  - `frontend/CLAUDE.md`의 폴더 규칙은 `src/types/`를 "백엔드 DTO와 1:1 대응 타입"으로 정했고, 봉투는 특정 도메인의 DTO가 아니라 `common/web`의 `ApiResponse`다([001 contracts/sector-api.md](../001-etf-market-data/contracts/sector-api.md)의 「공통 규칙」). 지금 자리는 규칙이 가리키는 자리가 아니다
  - **지금 고치는 편이 싸다.** M5의 T021이 `INSTRUMENT_PROFILE_NOT_READY`로 같은 경로를 한 번 더 쓰고, 003·005도 각자 조회를 만들 때 같은 import를 되풀이한다. 도메인이 늘수록 되돌리는 비용만 커진다
  - 옮기는 것은 **자리뿐이고 동작은 그대로 둔다.** 열린 `ApiErrorCode`와 닫힌 `ServiceApiErrorCode`를 나눈 구조(`1a5f483`이 세운 것)를 흔들지 않는다. `AuthApiErrorCode`·`AuthFieldError`·`AuthApiError`는 인증의 것이므로 `types/auth.ts`에 남긴다
  - 옮긴 뒤 `types/auth.ts`가 `types/sector.ts`를 가져오지 않게 된다. 새 도메인이 오류 이름을 더할 때 인증 파일을 고치지 않아도 되는지 함께 확인한다
  - 테스트: 새 검사를 만들기보다 기존 `src/api/sectors.test.ts`와 `src/api/auth.test.ts`가 그대로 통과하는지로 받는다. 타입 자리 이동이라 `npm run build`(`tsc -b`)가 실질적인 검사다

**Checkpoint**: 첫 화면의 메뉴와 소개 섹션이 같은 곳을 가리키고, 공용 봉투 타입이 도메인 파일 밖에 있다. 위 검증 기준선의 숫자가 그대로다.

---

## Phase 7: Convergence (M7: M3까지 맞대어 본 결과)

`/speckit-converge`가 M1·M2·M3이 구현된 상태의 코드를 spec·plan·data-model·contracts와 맞대어 본
결과다. **M4·M5가 아직 없는 것은 예정된 순서이므로 여기 담지 않는다.** M6의 T025(공용 봉투 타입
분리)는 이미 처리되어 `frontend/src/types/api.ts`가 있고 `api/sectors.ts`가 그 파일에서 봉투를
가져오므로 다시 세우지 않는다. M6의 T024는 아직 그대로 남아 있으므로(첫 화면 소개 섹션이 여전히
`/dashboard`·`/backtest`를 가리킨다) 여기서 되풀이하지 않는다. 기존 T001~T025의 번호와 내용은
건드리지 않았다.

검증 기준선(2026-09-16, 이 브랜치에서 직접 돌린 값): `npm run lint` 오류 0(경고 19, 모두 기존
차트·UI 파일), `npm run build` 통과, `npm test`의 Vitest 34파일 301건 통과·`skipped` 0,
`test:legacy` 13건 통과·`skipped` 0. `src/diagnosis/`의 테스트가 수집 목록에 있고 T005의 수집
가드가 통과한다(quickstart 시나리오 0 충족). 아래 항목을 마친 뒤에도 이 숫자가 줄지 않아야 한다.

- [x] T026 [M7] [US1] [FE] 보관된 값이 '아직 모르는 새 형식'일 때 쓰는 쪽의 실제 동작을 적어 둔 것과 맞춘다 (`frontend/src/diagnosis/SectorSelectionProvider.tsx`, `frontend/src/diagnosis/selectionStorage.ts`, `frontend/src/diagnosis/SectorSelectionProvider.test.tsx`) · FR-014 / [contracts/selection-handoff.md](./contracts/selection-handoff.md) 「형식이 바뀔 때」 / 원칙 VI (contradicts) · Jira 미발급
  - **남은 일이라고 판단한 근거**: `selectionStorage.ts`의 `newerFormat` 분기 주석이 "**이 값을 지우지도 않는다.** 보관된 값을 지키는 것이 이 분기의 목적이라 **쓰는 쪽도 함께 막는다**"고 적었는데, 쓰는 쪽에는 그 검사가 없다. `SectorSelectionProvider.tsx`의 보관 효과는 `restored.problem`을 보지 않으므로, 사용자가 섹터를 하나 고르는 순간 `writeStoredSelection`이 `version: 1` 페이로드로 그 값을 덮어쓴다. **적어 둔 설명과 실제 동작이 다르다**
  - 지금 있는 검사가 이것을 잡지 못한다. `SectorSelectionProvider.test.tsx`의 "화면이 뜨는 것만으로는 보관된 값을 건드리지 않는다"는 **렌더 직후만** 본다. 사용자가 한 번이라도 고친 뒤의 저장소를 보는 검사가 없어 어긋남이 조용히 남았다
  - 어느 쪽으로 맞출지는 이 task가 미리 정하지 않는다. **(가)** 주석대로 `newerFormat`인 동안 쓰기를 막는다. **(나)** 막지 않기로 하고 "쓰는 쪽도 함께 막는다"는 문장을 지운다. 계약이 요구하는 것은 "그 페이로드를 쓰지 않고 빈 상태로 시작하되 한 문장으로 알린다"까지이고 보존까지는 요구하지 않으므로 **(나)도 계약 위반이 아니다.** 다만 틀린 설명을 남겨 두는 것은 설명이 없는 것보다 나쁘다
  - (가)를 고르면 그 탭에서 사용자가 넣은 값은 메모리에만 남는다. 저장소가 막힌 환경과 같은 동작이고 spec Edge Cases가 이미 허용한 상태이므로 새 요구사항이 생기지 않는다. 그 사실을 화면의 `restoreProblem` 문장에 담을지는 문구가 확정될 때 함께 본다
  - **처리함 (2026-09-16, M3 묶음 안에서). (나)를 골랐다.** 지키려는 것은 화면이 열리는 것만으로 남의 값이 지워지는 일이지 사용자가 방금 한 행동이 아니다. (가)를 고르면 그 탭에서 사용자가 넣은 값이 새로고침에 조용히 사라지는데, 사용자 입력을 말없이 잃는 쪽이 더 나쁘다. 주석을 실제 동작에 맞게 고치고, "읽지 못한 형식이라도 사용자가 값을 넣으면 그 값을 보관한다"를 `SectorSelectionProvider.test.tsx`의 검사로 고정했다. 이 판단이 뒤집히면 그 검사가 실패한다
  - 테스트: 어느 쪽을 고르든 "보관된 값이 새 형식일 때 사용자가 섹터를 고른 뒤 저장소가 어떻게 되는가"를 값으로 고정하는 검사를 하나 둔다. 검사 없이 주석만 고치면 다음에 같은 어긋남이 조용히 돌아온다
  - 선행 없음. M4·M5와 파일이 겹치지 않으므로 그 묶음과 병행할 수 있다

**Checkpoint**: 보관된 값의 형식이 앞설 때 쓰는 쪽이 어떻게 움직이는지가 코드와 설명에서 같은 말을 하고, 그 동작을 고정하는 검사가 하나 있다. 위 검증 기준선의 숫자가 그대로다.

## 국내 시장 지수 배치 수정 (2026-10-01)

- [x] DM-01 국내 시장 지수 띠를 비교 가능 기간 바로 위 같은 폭으로 이동하고 제목 기준선을 맞춘다.
- [x] DM-02 프론트엔드 필수 검증과 AI 코드리뷰·화면 확인을 수행한다. lint 오류 0(기존 경고 26), build·전체 테스트 통과(skipped 0), 같은 박스 폭과 모바일 가로 넘침 없음을 확인했다.
- [ ] DM-03 dev 대상 MR을 병합한다.

## 지금 시장 뉴스 구역 (2026-10-02)

브랜치: `feat(fe)/news-diagnosis-section`. 대상 요구사항은 002 FR-019와 012 FR-001이고, 시장 흐름에서 뉴스를 빼는 009 FR-016·FR-019를 함께 갱신한다. 백엔드 조회 변경은 012 tasks의 NQ 항목이며 같은 MR에 담는다.

- [x] NS-01 지금 시장 뉴스를 시장 흐름에서 빼고 투자 대상 고르기 하단의 전체 폭 구역으로 옮긴다. 주요 뉴스(1위 묶음을 크게, 그다음 넷)·고른 섹터 뉴스(섹터마다 둘)·분류별 뉴스(분류마다 셋)를 이 순서로 두고, 한 묶음은 먼저 나오는 부분에만 보인다. 1위 묶음의 보도는 넷까지 보이고 나머지는 펼쳐 본다.
- [x] NS-02 같은 조회(섹터 목록과 받을 수)는 다시 하지 않고, 구역이 화면 가까이 왔을 때 받으며, 받지 못한 칸만 다시 시도한다. 처음 다 그린 뒤 투자 대상을 더 고르면 그 섹터 칸만 받는 중으로 둔다. 뉴스가 하나도 없는 상태(EMPTY·PREPARING)에서는 012의 문장만 두고, 받은 묶음이 모두 앞 부분에 보인 칸은 '없다'고 말하지 않는다. 섹터 칩으로 좁혀 보기는 그대로 둔다.
- [x] NS-03 프론트엔드 필수 검증과 AI 코드리뷰·화면 확인을 수행한다. lint 오류 0(기존 경고 26), build 통과, Vitest 160파일 1574건·legacy 15건 통과(skipped 0). 배포 서버 도메인이 조회되지 않아(2026-10-04) 화면은 고정 자료로 확인했다: 고른 섹터 칸과 분류 칸이 같은 세로줄에 서고, 375px 폭에서 가로 넘침이 없다.
- [ ] NS-04 dev 대상 MR을 병합한다.
