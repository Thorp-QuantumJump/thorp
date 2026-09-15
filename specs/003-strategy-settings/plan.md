# Implementation Plan: 전략 선택·상세 설정

**Branch**: `docs(fe)/226-spec-003-plan` (spec 디렉터리 식별자는 `003-strategy-settings`) | **Date**: 2026-09-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-strategy-settings/spec.md`

## Summary

사용자가 다섯 전략 카드에서 하나를 고르고, 그 전략에 해당하는 상세 설정만 허용 범위 안에서 조정한 뒤 분석을 시작하는 화면이다. 설정의 후보 값·허용 범위·기본값·유효성 제약은 서버가 보관하는 **파라미터 등록표**가 소유하며, 화면은 조회해 쓰기만 하고 서버가 실행 직전에 같은 검증을 다시 수행한다.

이 등록표는 새로 만드는 것이 아니다. spec 007이 자기 작업을 진행하려고 `ParameterRegistryPort`와 임시 구현 `ProvisionalFileParameterRegistry`를 세워 두었고, 그 클래스 주석이 "003이 완성되면 이 클래스를 003 구현체로 교체한다"고 적어 두었다. **이 spec의 구현은 그 교체를 수행하고 등록표를 화면이 읽을 수 있도록 여는 일이다.** 007은 포트를 통해 계속 같은 값을 읽으므로 007 운영 코드는 바뀌지 않는다.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5 / React 19 (frontend)

**Primary Dependencies**: Spring Boot 3.5, Gradle, Jackson(YAML), Vite, Vitest, Testing Library

**Storage**: 등록표는 백엔드 자원 파일(YAML)로 유지한다. 전략 설정값은 서버에 저장하지 않고 브라우저 탭 수명 동안만 보관한다(FR-017). 근거는 [research.md](research.md) R1·R8.

**Testing**: backend `./gradlew test`(ArchUnit 포함), frontend `npm run test`. 등록표 파일과 계약의 일치는 라운드트립 테스트로 확인한다.

**Target Platform**: 웹 브라우저(데스크톱 우선) + Spring Boot 서버

**Project Type**: 웹 애플리케이션 (frontend + backend)

**Performance Goals**: 등록표 조회는 기동 시 한 번 읽어 메모리에 들고 있으므로 DB 왕복이 없다. 화면이 전략 선택 단계에서 한 번 조회한다.

**Constraints**: PD-02와 PD-07이 미확정이므로 등록표 버전은 `PROVISIONAL-`로 시작하는 상태를 유지하고, 그 사실을 화면에 계속 표시한다. 등록표 값이 바뀌면 행을 고치지 않고 버전을 올린다.

**Scale/Scope**: 전략 5개, 전략별 설정 항목 1~5개, 화면 1개(전략 선택·상세 설정·기간·금액·조건)

## Constitution Check

*GATE: Phase 0 연구 전에 통과해야 하고, Phase 1 설계 후 다시 확인한다.*

| 원칙 | 이 기능에서의 의미 | 판정 |
|---|---|---|
| II. 첫 화면은 단순하게 | 기본 화면에 입력은 전략 선택 하나뿐이고 상세 설정은 요청해야 열린다(SC-005) | **주의** — 아래 이탈 항목 참조 |
| III. 진단이지 추천이 아니다 | 기본값을 편의용 출발점으로 표기하고 최적값·투자 권고로 쓰지 않는다(FR-004) | 통과 |
| IV. 비교는 같은 조건에서만 | 분석 기간은 공통 평가 가능 범위에서만 고를 수 있고(FR-009), 수수료·세금·기간을 수익이 높아지는 방향으로 자동 최적화하는 선택지를 두지 않는다(FR-010) | 통과 |
| V. 당시에 알 수 있던 정보만 | 워밍업을 채우지 못하는 전략은 고를 수 없게 막는다(FR-011). 워밍업 계산은 등록표의 산출 규칙이 담당한다 | 통과 |
| VI. 사용자 입력을 몰래 바꾸지 않는다 | 허용 범위 밖 값을 잘라 넣지 않고 저장을 거부한다(FR-006). 목표 비중은 002가 확정한 값을 읽기만 한다(FR-003) | 통과 |
| VIII. 재현할 수 있게 기록한다 | 등록표 버전을 분석 요청에 실어 004·006으로 넘긴다(FR-014) | 통과 |

### 이탈 항목: 행동 버튼 두 개

constitution 원칙 II가 인용하는 기획서 §4.1은 화면의 행동 버튼을 하나로 두라고 한다. spec 003은 FR-002에서 '분석하기'와 '설정 조합 자동 비교'를 같은 크기의 버튼 두 개로 두기로 정했다. 명료화 과정에서 사용자가 선택한 결정이며 spec에도 벗어나는 지점임을 적어 두었다.

**이 계획은 spec을 따른다.** 다만 기획서를 고칠지 결정을 되돌릴지는 팀이 정해야 하고, 그 판단이 나오기 전까지 구현은 두 버튼을 같은 컴포넌트에서 나란히 렌더링해 한쪽을 지우는 변경이 한 곳에서 끝나도록 둔다.

### 게이트 결과 (Phase 0 전)

PD-02·PD-07 미확정을 제외하면 막는 위반이 없다. 두 결정 항목은 값이 정해지지 않았을 뿐 구조를 막지 않으므로, 등록표 버전에 `PROVISIONAL-` 접두를 유지하는 방식으로 진행한다. 이는 007이 이미 쓰는 방식과 같다.

### 게이트 재확인 (Phase 1 설계 후)

설계를 마친 뒤 다시 확인했고 새로 생긴 위반이 없다. 설계가 원칙을 어떻게 지키는지만 덧붙인다.

| 원칙 | 설계에서 지키는 방식 |
|---|---|
| III | 등록표의 `defaultValue`를 화면이 '기본 시작값'으로만 표시하고, 응답에 추천이나 순위를 담지 않는다 |
| IV | 등록표 버전을 요청에 싣고 접수 시점과 다르면 409로 거절한다. 보지 못한 규칙으로 실행되지 않는다 ([research](research.md) R9) |
| V | 워밍업 규칙을 등록표가 담고 설정이 바뀔 때마다 다시 계산한다. 고정 상수를 두지 않는다 ([data-model](data-model.md) 6) |
| VI | 허용 범위 밖 값은 400으로 거절하고 잘라 넣지 않는다. 화면 검증과 서버 검증이 같은 등록표를 본다 |
| VIII | `registryVersion`·`warmupTradingDays`를 분석 요청에 실어 004·006으로 넘긴다 |

**새로 확인한 사항**: 등록표를 `strategy` 패키지로 옮기면 `paramsearch → strategy` 한 방향 의존이 되어 [ADR-0001](../../docs/adr/0001-be-layered-package-architecture.md)의 계층 규칙과 어긋나지 않는다. 반대 방향이 생기면 ArchUnit이 빌드를 실패시킨다.

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
│   ├── port/            # ParameterRegistry 도메인 표현 (007 포트가 참조)
│   ├── result/          # 조회 결과 DTO
│   └── service/         # 등록표 조회·검증 서비스
├── domain/model/        # 등록표·축·허용 범위·워밍업 규칙
├── infrastructure/
│   └── registry/        # YAML 로더 (007의 Provisional 로더를 옮겨 온다)
└── presentation/
    ├── controller/      # GET /strategies/registry
    └── response/

backend/src/main/resources/registry/
└── parameter-registry.yml       # 007의 provisional 파일을 승격해 옮긴다

backend/src/main/java/com/ssafy/thorp/paramsearch/
└── infrastructure/registry/     # ProvisionalFileParameterRegistry 를 003 구현체 위임으로 교체

frontend/src/
├── api/strategyRegistry.ts      # 등록표 조회
├── pages/StrategySettingsPage.tsx
├── components/strategy/         # 전략 카드·상세 설정·기간금액조건
└── hooks/useStrategySettings.ts # 탭 수명 보관과 워밍업 재판정
```

**구조 선택 근거**: 이 저장소는 `backend/`와 `frontend/`가 나뉜 웹 애플리케이션이고, 백엔드는 도메인별 패키지 안에서 `presentation → application → domain/infrastructure` 계층을 두는 구조를 ArchUnit으로 강제한다([ADR-0001](../../docs/adr/0001-be-layered-package-architecture.md)). 등록표는 `paramsearch`가 아니라 새 `strategy` 도메인에 둔다 — 등록표의 소유자는 003이고 007은 읽는 쪽이기 때문이다.

## Complexity Tracking

| 항목 | 왜 단순한 방법으로 끝내지 않았나 | 기각한 더 단순한 방법 |
|---|---|---|
| 등록표를 새 `strategy` 도메인으로 옮긴다 | 지금 등록표가 `paramsearch` 안에 있어 소유 관계가 뒤집혀 있다. 007이 제 것을 003에 빌려준 상태이고, 그대로 두면 003 화면이 007 패키지를 참조하게 된다 | 현재 위치에 그대로 두고 조회 API만 얹기 — 의존 방향이 003 → 007이 되어 007이 003을 읽는다는 계약과 어긋난다 |
| 워밍업 산출 규칙을 등록표에 담는다 | 설정값에 따라 워밍업이 달라지므로 고정값으로는 카드 판정을 할 수 없다(FR-011) | 전략별 고정 워밍업 상수 — 관측 기간을 늘리면 판정이 틀린다 |
| 목록형·입력형 구분을 등록표가 담는다 | 항목마다 다르기로 정했고(Clarifications), 화면이 항목 이름으로 분기하면 그것이 전략 지식의 중복이다 | 화면이 항목 이름으로 판단 — 등록표에 항목이 추가될 때마다 화면을 고쳐야 한다 |
