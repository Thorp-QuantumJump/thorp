# Implementation Plan: 전략 선택·상세 설정

**Spec 디렉터리**: `003-strategy-settings` | **Date**: 2026-09-15 | **Spec**: [spec.md](spec.md)

> 구현 Task의 Git 브랜치는 Jira Task마다 `{type}({position})/{JIRA_NUMBER}-{description}` 형식으로 따로 만든다.
> 브랜치 이름은 아래 "작업 묶음 구성"에 티켓 번호와 함께 적는다 — constitution이 묶음 구성을 plan에 두라고
> 요구하고, 묶음마다 브랜치가 하나씩 딸리기 때문이다.

**Input**: Feature specification from `/specs/003-strategy-settings/spec.md`

## Summary

사용자가 다섯 전략 카드에서 하나를 고르고, 그 전략에 해당하는 상세 설정만 허용 범위 안에서 조정한 뒤 분석을 시작하는 화면이다. 설정의 후보 값·허용 범위·기본값·유효성 제약은 서버가 보관하는 **파라미터 등록표**가 소유하며, 화면은 조회해 쓰기만 하고 서버가 실행 직전에 같은 검증을 다시 수행한다.

이 등록표는 새로 만드는 것이 아니다. spec 007이 자기 작업을 진행하려고 `ParameterRegistryPort`와 임시 구현 `ProvisionalFileParameterRegistry`를 세워 두었고, 그 클래스 주석이 "003이 완성되면 이 클래스를 003 구현체로 교체한다"고 적어 두었다. **이 spec의 구현은 그 교체를 수행하고 등록표를 화면이 읽을 수 있도록 여는 일이다.** 007은 포트를 통해 계속 같은 값을 읽는다. **007 운영 코드에서 바뀌는 것은 import 경로뿐이다** — `ParamSearchService`·`CandidateValidator`·`SearchTargetResolver` 셋 다 포트 타입만 주입받고 구현체를 직접 부르지 않는 것을 확인했다.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5 / React 19 (frontend)

**Primary Dependencies**: Spring Boot 3.5, Gradle, Jackson(YAML), Vite, Vitest, Testing Library

**Storage**: 등록표는 백엔드 자원 파일(YAML)로 유지한다. 전략 설정값은 서버에 저장하지 않고 브라우저 탭 수명 동안만 보관한다(FR-017). 근거는 [research.md](research.md) R1·R8.

**Testing**: backend `./gradlew spotlessApply build`(ArchUnit 포함), frontend `npm run lint && npm run build`와 `npm run test`.

등록표 검증은 둘이다. **라운드트립 테스트는 쓰지 않는다** — 그 방식은 자바와 파이썬이 같은 브로커 계약을 각자 구현하는 상황용이고(`BacktestMessageRoundTripTest`), 등록표는 YAML → 자바 → JSON → 화면 한 방향으로만 흘러 되돌아오는 경로가 없다.

1. **로더가 기동 시 파일 구조를 검증한다.** 지금 `ProvisionalFileParameterRegistryTest`가 하는 일이며, 여기에 새 제약(`searchValues ⊆ allowedRange.values`, 목록형과 입력형을 동시에 채우지 않음)을 더한다.
2. **계약 문서의 응답 예시를 픽스처로 두고 실제 응답과 대조한다.** `docs/specs/backtest-api/examples/`가 이미 쓰는 방식이다. 계약과 코드가 어긋나면 빌드가 실패한다.

**Target Platform**: 웹 브라우저(데스크톱 우선) + Spring Boot 서버

**Project Type**: 웹 애플리케이션 (frontend + backend)

**Performance Goals**: 정하지 않는다. 이 화면은 계산을 실행하지 않고 등록표를 한 번 조회할 뿐이라 지금 목표를 세울 근거가 없다. 필요해지면 그때 실측해 정한다.

**Constraints**: PD-02와 PD-07이 미확정이므로 등록표 버전은 `PROVISIONAL-`로 시작하는 상태를 유지하고, 그 사실을 화면에 계속 표시한다. 등록표 값이 바뀌면 행을 고치지 않고 버전을 올린다.

**Scale/Scope**: 전략 5개, 전략별 설정 항목 1~5개, 화면 1개(전략 선택·상세 설정·기간·금액·조건)

## Constitution Check

*GATE: Phase 0 연구 전에 통과해야 하고, Phase 1 설계 후 다시 확인한다.*

| 원칙 | 이 기능에서의 의미 | 판정 |
|---|---|---|
| I. 중심 약속은 전략 진단이다 | 이 화면이 전략 진단의 두 번째 단계다 | 통과 |
| II. 첫 화면은 단순하게 | 입력은 전략 선택 하나뿐이고 상세 설정은 요청해야 열린다(SC-005). 다만 **행동 버튼이 '분석하기'와 '설정 조합 자동 비교' 둘이라 기획서 §4.1의 '버튼 하나'와 어긋난다**(FR-002) | **승인된 이탈** — 아래 설명 |
| III. 진단이지 추천이 아니다 | 기본값을 편의용 출발점으로 표기하고 최적값·투자 권고로 쓰지 않는다(FR-004) | 통과 |
| IV. 비교는 같은 조건에서만 | 분석 기간은 공통 평가 가능 범위에서만 고를 수 있고(FR-009), 수수료·세금·기간을 수익이 높아지는 방향으로 자동 최적화하는 선택지를 두지 않는다(FR-010) | 통과 |
| V. 당시에 알 수 있던 정보만 | 워밍업을 채우지 못하는 전략은 고를 수 없게 막는다(FR-011). 워밍업 계산은 등록표의 산출 규칙이 담당한다 | 통과 |
| VI. 사용자 입력을 몰래 바꾸지 않는다 | 허용 범위 밖 값을 잘라 넣지 않고 저장을 거부한다(FR-006). 목표 비중은 002가 확정한 값을 읽기만 한다(FR-003) | 통과 |
| VII. 탐색과 검증을 구분한다 | 해당 없음 — 자동 탐색은 [007](../007-auto-parameter-search/spec.md)이 맡고 003은 진입점만 제공한다 | 해당 없음 |
| VIII. 재현할 수 있게 기록한다 | 등록표 버전을 분석 요청에 실어 004·006으로 넘긴다(FR-014) | 통과 |
| IX. 실행 한도는 실측으로 정한다 | 해당 없음 — 이 화면은 계산을 실행하지 않는다 | 해당 없음 |
| X. 자료는 이용 조건 안에서만 쓴다 | 해당 없음 — 이 화면은 시장 자료를 직접 읽지 않는다 | 해당 없음 |

### 승인된 이탈: 행동 버튼 두 개

constitution 원칙 II가 인용하는 기획서 §4.1은 화면의 행동 버튼을 하나로 두라고 한다. spec 003은 FR-002에서 '분석하기'와 '설정 조합 자동 비교'를 같은 크기의 버튼 두 개로 두기로 정했다.

**이 이탈은 팀이 직접 판단해 승인한 것이다.** 명료화 과정에서 세 선택지(보조 링크로 두기 / 같은 크기 버튼 두 개 / 상세 설정 안에 넣기)를 놓고 두 번째를 골랐다. 자동 비교는 설정을 하나씩 바꾸는 수고를 대신하는 주된 경로이므로 보조 링크로 숨기지 않는다는 판단이다.

**따라서 구현은 두 버튼을 그대로 만든다.** 결정이 뒤집힐 것을 전제한 대비를 두지 않는다. 기획서 §4.1의 문구를 이 결정에 맞춰 고치는 일은 별도 작업으로 남긴다.

### 게이트 결과 (Phase 0 전)

PD-02·PD-07 미확정을 제외하면 막는 위반이 없다. 두 결정 항목은 값이 정해지지 않았을 뿐 구조를 막지 않으므로, 등록표 버전에 `PROVISIONAL-` 접두를 유지하는 방식으로 진행한다. 이는 007이 이미 쓰는 방식과 같다.

### 004와 대조한 결과 spec을 고쳤다

계획을 세우며 [004](../004-backtest-execution/spec.md)를 대조해 spec 003의 세 곳을 고쳤다. 계획 문서가 아니라 spec 본문을 고친 것이므로 여기에 남긴다.

| 무엇 | 왜 |
|---|---|
| FR-007에서 '휴장일이면 직전 거래일에 실행' 문장을 뺐다 | 004 FR-011이 "월말 정보 확인 후 **다음 거래일** 체결"로 이미 정해 두었다. 직전 거래일에 체결하면 아직 오지 않은 시점의 정보로 거래하는 셈이라 **원칙 V를 어긴다** |
| FR-019에서 최소분산을 뺐다 | 004 FR-025가 상한을 최적화 제약으로 넣어 풀기 때문에 잘리는 몫이 생기지 않는다. 역변동성·섹터 모멘텀에만 해당한다 |
| FR-010에 목표 변동성을 더했다 | 004의 PD-18(2026-09-10 확정)이 목표 변동성을 "003 파라미터 등록표 항목"으로 지정했는데 003이 받지 않고 있었다 |

**남은 빈자리**: 상한에 잘린 몫을 재배분하는 계산은 004가 해야 하나 004에 규칙이 없다. FR-020이 "처리 규칙을 적용한다"고만 두고 PD-02·PD-03 계열 미확정으로 남겼다. 004가 규칙을 등록하기 전까지 003은 상한 값 전달까지만 맡는다.

### 게이트 재확인 (Phase 1 설계 후)

설계를 마친 뒤 다시 확인했고 새로 생긴 위반이 없다. 설계가 원칙을 어떻게 지키는지만 덧붙인다.

| 원칙 | 설계에서 지키는 방식 |
|---|---|
| III | 등록표의 `defaultValue`를 화면이 '기본 시작값'으로만 표시하고, 응답에 추천이나 순위를 담지 않는다 |
| IV | 등록표 버전을 요청에 싣고 접수 시점과 다르면 409로 거절한다. 보지 못한 규칙으로 실행되지 않는다 ([research](research.md) R9) |
| V | 워밍업 규칙을 등록표가 담고 설정이 바뀔 때마다 다시 계산한다. 고정 상수를 두지 않는다 ([data-model](data-model.md) 6) |
| VI | 허용 범위 밖 값은 400으로 거절하고 잘라 넣지 않는다. 화면 검증과 서버 검증이 같은 등록표를 본다 |
| VIII | `registryVersion`·`warmupTradingDays`를 분석 요청에 실어 004·006으로 넘긴다 |

**의존 방향은 사람이 지켜야 한다**: 등록표를 `strategy` 패키지로 옮기면 `paramsearch → strategy` 한 방향 의존이 된다. 다만 `ArchitectureTest`의 규칙은 계층 사이(presentation → application → domain)만 검사하고 **도메인 패키지 사이의 방향은 검사하지 않는다**(규칙 4는 `common`만 다룬다). 따라서 `strategy`가 `paramsearch`를 참조해도 빌드가 통과한다. 이 방향을 강제하려면 ArchUnit 규칙을 하나 더해야 하며, 그 작업을 구현 Task에 포함한다.

## Project Structure

### Documentation (this feature)

```text
specs/003-strategy-settings/
├── plan.md              # 이 파일
├── research.md          # Phase 0 산출물
├── data-model.md        # Phase 1 산출물
├── quickstart.md        # Phase 1 산출물
├── contracts/           # Phase 1 산출물
│   ├── strategy-registry-api.md      # 화면이 쓰는 등록표 조회 계약
│   └── strategy-registry-internal.md # 007이 쓰는 포트 계약의 003 쪽 약속
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks 산출물 — 이 명령이 만들지 않는다
```

### Source Code (repository root)

```text
backend/src/main/java/com/ssafy/thorp/strategy/        # 새로 만드는 003 소유 영역
├── application/
│   ├── port/            # ParameterRegistryPort 와 값 타입 — 007이 옮겨 오는 그대로
│   ├── exception/       # RegistryUnavailableException — 007에서 옮겨 온다
│   ├── result/          # 조회 결과 DTO
│   └── service/         # 등록표 조회·검증 서비스
├── infrastructure/
│   └── registry/        # YAML 로더 (007의 Provisional 로더를 옮겨 온다)
└── presentation/
    ├── controller/      # GET /strategies/registry
    └── response/

backend/src/main/resources/registry/
└── parameter-registry.yml       # 007의 provisional 파일을 승격해 옮긴다

backend/src/main/java/com/ssafy/thorp/paramsearch/
└── infrastructure/registry/     # 비운다 — ProvisionalFileParameterRegistry 를 위로 옮기고 위임 계층을 남기지 않는다

frontend/src/
├── api/strategyRegistry.ts      # 등록표 조회
├── pages/StrategySettingsPage.tsx
├── components/strategy/         # 전략 카드·상세 설정·기간금액조건
└── hooks/useStrategySettings.ts # 탭 수명 보관과 워밍업 재판정
```

**구조 선택 근거**: 이 저장소는 `backend/`와 `frontend/`가 나뉜 웹 애플리케이션이고, 백엔드는 도메인별 패키지 안에서 `presentation → application → domain/infrastructure` 계층을 두는 구조를 ArchUnit으로 강제한다([ADR-0001](../../docs/adr/0001-be-layered-package-architecture.md)). 등록표는 `paramsearch`가 아니라 새 `strategy` 도메인에 둔다 — 등록표의 소유자는 003이고 007은 읽는 쪽이기 때문이다.

**`domain/model`을 두지 않는다**: 등록표의 값 타입(`StrategyRegistration`·`ParameterAxis`·`AllowedRange`)은 지금 `paramsearch/application/port`에 있고, 옮길 때도 같은 자리에 둔다. 도메인 모델 층을 따로 만들면 같은 것이 두 곳에 생기고, 007이 참조하는 타입이 무엇인지 흐려진다.

## 작업 묶음 구성 (티켓·Branch·MR 단위)

constitution "Jira 연계"가 **`작업 묶음 = 1 Branch = 1 MR`**이고 **"묶음 구성은 plan을 시작할 때
정한다"**고 요구한다. [tasks.md](tasks.md)의 `T###` 79개는 실행 지시의 최소 단위이며 티켓 단위가
아니다. **Jira Task·브랜치·MR의 단위는 아래 묶음 열둘이다.**

묶음마다 constitution이 정한 세 조건을 확인했다. 하나의 목적을 공유하는가, 독립적으로
구현·검증·리뷰하고 다른 묶음을 건드리지 않고 되돌릴 수 있는가, 리뷰어가 한 번에 읽을 수 있고
안에 미완성 task가 없는가.

| 묶음 · 티켓 | Jira Task 제목 | 브랜치 | 포함 T### | 수 | 선행 |
|---|---|---|---|---|---|
| B01 · [S15P21E205-233](https://ssafy.atlassian.net/browse/S15P21E205-233) | `[BE] 등록표를 003 소유로 옮긴다` | `refactor(be)/233-registry-move` | T001, T007~T012 | 7 | 없음 |
| B02 · [S15P21E205-234](https://ssafy.atlassian.net/browse/S15P21E205-234) | `[BE] 등록표가 화면에 필요한 항목을 담는다` | `feat(be)/234-registry-fields` | T013~T022(T018a 포함) | 11 | B01 |
| B03 · [S15P21E205-235](https://ssafy.atlassian.net/browse/S15P21E205-235) | `[BE] 전략·파라미터 등록표 조회 API` | `feat(be)/235-registry-api` | T003, T004, T023~T028 | 8 | B02 |
| B04 · [S15P21E205-236](https://ssafy.atlassian.net/browse/S15P21E205-236) | `[FE] 전략 카드 다섯을 보인다` | `feat(fe)/236-strategy-cards` | T005, T006, T029, T030, T033~T035, T043 | 8 | B03의 계약 |
| B05 · [S15P21E205-237](https://ssafy.atlassian.net/browse/S15P21E205-237) | `[FE] 카드를 골라 분석을 시작한다` | `feat(fe)/237-strategy-select-run` | T031, T032, T036~T038, T040, T041, T044~T046 | 10 | B04 |
| B06 · [S15P21E205-238](https://ssafy.atlassian.net/browse/S15P21E205-238) | `[FE] 분석 요청과 평가 기간을 연결한다` | `feat(fe)/238-analysis-wiring` | T039, T042 | 2 | B05 + 외부 결정 |
| B07 · [S15P21E205-239](https://ssafy.atlassian.net/browse/S15P21E205-239) | `[BE] 접수 시점에 설정을 다시 검증한다` | `feat(be)/239-settings-revalidation` | T002, T047~T051 | 6 | B02 |
| B08 · [S15P21E205-240](https://ssafy.atlassian.net/browse/S15P21E205-240) | `[FE] 상세 설정을 열어 값을 바꾼다` | `feat(fe)/240-detail-settings` | T052~T057, T060~T063 | 10 | B05 |
| B09 · [S15P21E205-241](https://ssafy.atlassian.net/browse/S15P21E205-241) | `[FE] 바꾼 값을 그 자리에서 판정한다` | `feat(fe)/241-live-verdict` | T058, T059, T064 | 3 | B08 |
| B10 · [S15P21E205-242](https://ssafy.atlassian.net/browse/S15P21E205-242) | `[FE] 기간·금액·조건을 바꾼다` | `feat(fe)/242-analysis-conditions` | T065~T069 | 5 | B09 |
| B11 · [S15P21E205-243](https://ssafy.atlassian.net/browse/S15P21E205-243) | `[FE] 설정 조합 자동 비교로 넘어간다` | `feat(fe)/243-autocompare-entry` | T070, T071 | 2 | B05 |
| B12 · [S15P21E205-244](https://ssafy.atlassian.net/browse/S15P21E205-244) | `[DOCS] 문서 동기화와 최종 검증` | `docs(fe)/244-spec-003-wrapup` | T072~T078 | 7 | 전부 |

**묶음 열둘 = 티켓 열둘 = 브랜치 열둘 = MR 열둘.** 79개 태스크를 빠짐도 중복도 없이 덮는다.
**티켓 열둘과 브랜치 열둘을 2026-09-15에 만들었다.** 표의 링크가 그 티켓이다. 개별 `T###`
번호는 MR 본문의 "주요 변경 사항"에 나열한다.

```text
B01 ─▶ B02 ─┬─▶ B03 ─▶ B04 ─▶ B05 ─┬─▶ B08 ─▶ B09 ─▶ B10 ─┐
            │                       ├─▶ B11                 ├─▶ B12
            └─▶ B07                 └─▶ B06 (외부 결정 대기) ┘
```

### 묶음 이름을 목적으로 붙인 이유

초판은 계층으로 잘라 열여섯이었다. "값 타입 확장"·"화면 컴포넌트" 같은 이름은 그 MR이 머지돼도
무엇이 달라지는지 말해 주지 않고, 조각마다 브랜치를 따는 동안 아무것도 완결되지 않는다. 묶음 조건
1이 요구하는 "하나의 목적"을 크기에 맞추려다 잃은 것이다.

지금 이름은 그대로 Jira Task 제목이 되고 "이게 끝나면 무엇이 되나"를 한 줄로 답한다. 대신 크기가
둘에서 열하나까지 벌어지는데, 그것을 받아들였다. B02를 "값 타입"과 "로더"로 다시 자르면 앞 조각이
혼자서는 아무 일도 하지 않아 묶음 조건 3의 "미완성 task 없음"을 어긴다.

### 크기 위험

B02와 B08이 열하나·열이라 가장 크다. 둘 다 더 쪼개면 목적이 흩어지므로 브랜치는 하나로 두고
**안에서는 커밋으로 나눈다.** B01·B02의 007 회귀 확인(T011·T021)도 커밋 경계로 둔다.

### 묶음에 넣지 않은 것

없다. T001~T078과 T018a 전부가 어느 묶음엔가 속한다. 다만 **B06은 외부 결정을 기다린다** —
[004](../004-backtest-execution/spec.md)의 규칙·비용 버전 조회 수단과 [001](../001-etf-market-data/spec.md)의
평가 기간 엔드포인트가 정해지기 전에는 시작할 수 없다. 막히지 않은 열하나가 함께 서지 않도록
따로 뺀 것이다.

## Complexity Tracking

| 항목 | 왜 단순한 방법으로 끝내지 않았나 | 기각한 더 단순한 방법 |
|---|---|---|
| 등록표를 새 `strategy` 도메인으로 옮긴다 | 지금 등록표가 `paramsearch` 안에 있어 소유 관계가 뒤집혀 있다. 007이 제 것을 003에 빌려준 상태이고, 그대로 두면 003 화면이 007 패키지를 참조하게 된다 | 현재 위치에 그대로 두고 조회 API만 얹기 — 의존 방향이 003 → 007이 되어 007이 003을 읽는다는 계약과 어긋난다 |
| 워밍업 산출 규칙을 등록표에 담는다 | 설정값에 따라 워밍업이 달라지므로 고정값으로는 카드 판정을 할 수 없다(FR-011) | 전략별 고정 워밍업 상수 — 관측 기간을 늘리면 판정이 틀린다 |
| 목록형·입력형 구분을 등록표가 담는다 | 항목마다 다르기로 정했고(Clarifications), 화면이 항목 이름으로 분기하면 그것이 전략 지식의 중복이다 | 화면이 항목 이름으로 판단 — 등록표에 항목이 추가될 때마다 화면을 고쳐야 한다 |
