# Implementation Plan: 배우기

**Spec 디렉터리**: `013-learning-pages` | **Date**: 2026-09-16 | **Spec**: [spec.md](spec.md)

> 구현 Task의 Git 브랜치는 Jira Task마다 `{type}({position})/{JIRA_NUMBER}-{description}` 형식으로 따로 만든다.
> 이 plan 문서 작업은 [S15P21E205-273](https://ssafy.atlassian.net/browse/S15P21E205-273) · branch `docs(fe)/273-spec-013-plan`이다.
> B06 동기화·검증: [S15P21E205-302](https://ssafy.atlassian.net/browse/S15P21E205-302) · branch `docs(fe)/302-spec-013-wrapup`. 실행 결과와 남은 확인은 [validation.md](validation.md)에 둔다.

**Input**: `specs/013-learning-pages/spec.md` (Clarifications 10건 + 002 대조 정정 1건, FR-001~FR-019, SC-001~SC-008)

## Summary

완전 입문자가 전략 진단에 필요한 개념을 짧은 글로 익히고, 글의 예시로 곧바로 섹터·비중 선택(002)에 들어가게 하는 기능이다. 진단 화면(002·003·005)의 용어 도움말은 같은 설명을 단일 원본에서 받는다.

**서버**: 새 백엔드 도메인 `learning`이 글과 용어 설명을 PostgreSQL에 보관한다. 글마다 초안 작업본 한 벌을 두고, 게시할 때마다 그 시점 내용 전체를 **불변 게시 판**으로 복사한다. 일반 사용자 조회는 게시 판만 읽으므로, 운영자가 게시된 글을 고치는 동안에도 검사를 통과하지 않은 문장이 공개되지 않는다. 게시 시점에 서버가 아홉 가지 검사를 모두 돌리고 걸린 항목을 전부 돌려준다.

**본문은 구조화된 블록**이다. 용어 블록은 문장을 복사하지 않고 용어 설명을 가리키므로 도움말 문장과 글 본문이 어긋날 수 없다(SC-003). 가상 계산 예는 전용 블록에만 쓰게 해 '가상의 계산 예' 표시와 결과성 숫자 검사를 구조로 강제한다(FR-005).

**관리 권한**은 014의 실제 로그인 세션 위에서 포트 하나로 판정한다. 014에 역할이 아직 없으므로 지금 구현은 설정 허용 목록이고, 014가 역할을 제공하면 구현 한 클래스만 바꾼다.

**화면**: 배우기 목록·글 화면, 002·003·005가 함께 쓰는 공용 용어 도움말 컴포넌트, 관리 화면을 만든다. 도움말에서 글로는 같은 탭에서 이동하고, 글 화면이 '하던 진단으로 돌아가기'를 보인다.

결정 근거는 [research.md](research.md) R1~R13에 있다.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.8 / React 19 (frontend)

**Primary Dependencies**: Spring Boot 3.5(Web·Validation·Data JPA·Security·Session JDBC), Flyway, Jackson(YAML 포함). React Router 7, Axios, `radix-ui`, Vitest, Testing Library. **새 라이브러리를 추가하지 않는다.** 도움말 Popover는 이미 있는 `radix-ui`로 만든다([research](research.md) R13).

**Storage**: PostgreSQL 새 테이블 다섯(`learning_article`·`learning_term`·`learning_article_revision`·`learning_publication_event`·`learning_published_term`, [data-model](data-model.md) §3). 게시 전 검사 규칙은 자원 파일 `learning/publish-checks-v1.yml`이다. 브라우저에는 관리 화면의 저장하지 않은 편집 내용만 `sessionStorage`에 둔다. 일반 사용자 화면은 아무것도 저장하지 않는다(FR-012).

**Testing**: backend `./gradlew spotlessApply build`(ArchUnit·`PostgresTestSupport` 통합 테스트·계약 픽스처 대조), frontend `npm run lint && npm run build && npm test`. 계약 픽스처는 `docs/specs/learning-api/examples/`에 두고 통합 테스트가 실제 응답과 대조한다. 003의 `docs/specs/strategy-registry-api/examples/` 방식이다. 시나리오 검증은 [quickstart.md](quickstart.md)다.

**Target Platform**: 웹 브라우저(데스크톱 우선) + 기존 Spring Boot 서버

**Project Type**: 웹 애플리케이션 (frontend + backend)

**Performance Goals**: 정하지 않는다. 초기 글 아홉 편·용어 열넷이고 조회는 행 몇 개를 읽는 수준이라 지금 목표를 세울 근거가 없다. 대응표 대조는 조회마다 섹터 수십 행을 읽는다([research](research.md) R8). 글 수가 크게 늘거나 도움말 조회가 체감될 만큼 느려지면 그때 실측해 정한다(원칙 IX).

**Constraints**:
- 본문은 일반 텍스트 블록만 저장하고 화면은 텍스트 노드로만 그린다(R3).
- 관리 권한은 014 역할이 생기기 전까지 설정 허용 목록이다. 운영 설정에 기본값을 두지 않는다(R5).
- 예시 비중 단위는 002 계약과 같은 0.1%p 정수다([data-model](data-model.md) §5).
- 화면은 실패 시 조용히 재시도하지 않는다(FR-010).
- 로컬 프로필은 Flyway가 꺼져 있어 초기 자리가 생기지 않는다. 확인 방법은 [quickstart](quickstart.md) §0이다.

**Scale/Scope**: 글 아홉·용어 열넷(초기 자리), 관리 사용자 몇 명, 공개 경로 두 개(`/learn`, `/learn/:articleId`), 관리 경로 두 개(`/admin/learning`, `/admin/learning/:articleId`), 공용 컴포넌트 하나(`TermHelp`)

## Constitution Check

*GATE: Phase 0 연구 전에 통과해야 하고, Phase 1 설계 후 다시 확인한다.*

| 원칙 | 이 기능에서의 의미 | 판정 |
|---|---|---|
| I. 중심 약속은 전략 진단이다 | 배우기는 보조 기능이다. 학습을 요구하지 않고(FR-011), 글·도움말 조회가 실패해도 진단 진입과 입력을 막지 않는다(FR-010, FR-015) | 통과 |
| II. 첫 화면은 단순하게 | 글 화면의 행동 버튼은 '이 예시로 비중 비교하기' 하나다. 돌아가기는 보조 연결이다(FR-009). 계산식·서버 설정을 요구하지 않는다 | 통과 |
| III. 진단이지 추천이 아니다 | 금지 표현·결과성 숫자 게시 전 검사, 예시·가상 계산 예 표시(FR-005). 검사가 패턴 검사라는 한계를 적었다 | 통과 — 한계는 [research](research.md) R7 |
| IV. 비교는 같은 조건에서만 | 글에 실제 결과를 싣지 않는다(FR-005). 비교는 002 이후 진단이 맡는다 | 해당 없음 |
| V. 당시에 알 수 있던 정보만 | 이 기능은 시계열을 다루지 않는다 | 해당 없음 |
| VI. 없는 결과를 만들어내지 않는다 | '설명 준비 중'과 '불러오지 못함', 빈 목록과 조회 실패를 코드로 가른다. 대응표를 읽지 못하면 게시를 거절하고 통과로 세지 않는다. 입력 덮어쓰기는 002가 먼저 묻는다 | 통과 |
| VII. 탐색과 검증을 구분한다 | 해당 없음 | 해당 없음 |
| VIII. 재현할 수 있게 기록한다 | 게시 판은 불변이고 검사 규칙 버전·대응표 버전·게시자를 함께 남긴다(FR-018) | 통과 |
| IX. 실행 한도는 실측으로 정한다 | 백테스트를 실행하지 않는다 | 해당 없음 |
| X. 자료는 이용 조건 안에서만 | 글은 KRX 시세를 쓰지 않는다. 외부 인용은 `sources`로 출처를 싣는다(FR-007) | 통과 |
| "하지 않는 것" | 숙련 퀀트 전용 연구 화면·진도·수료·댓글을 만들지 않는다 | 통과 |
| 기록·개인정보 | 게시 기록에 사용자 식별자만 담고 이메일·닉네임을 담지 않는다 | 통과 |
| 스키마 변경 규칙 | 새 테이블 추가만 있고 기존 테이블을 바꾸지 않는다. 스키마·매핑·검증을 같은 묶음(B01)에 담는다 | 통과 |
| 합의되지 않은 영역(인증·인가, API 형식) | 선택의 근거를 [research](research.md) R5·R6과 [contracts](contracts/README.md)에 남겼다 | 통과 |

### 게이트 결과 (Phase 0 전)

차단 항목은 없다. 아래 교차 의존 가운데 014 개정은 해소되지 않았지만, 허용 목록 구현으로 013이 막히지 않는다.

## 교차 의존

013 혼자 닫을 수 없는 항목이다. **이 plan은 다른 spec 문서를 고치지 않고 티켓도 세우지 않는다.** 각 담당이 판단한다.

| 대상 | 필요한 일 | 담당 | 막는 범위 |
|---|---|---|---|
| [014 사용자 인증](../014-user-auth/spec.md) | Out of Scope의 '사용자 역할별 권한 관리와 관리자 계정'을 범위 안으로 옮겨 관리 권한 소유를 받아들인다 | 014 담당 | 막지 않는다. 그전까지 설정 허용 목록으로 동작한다([research](research.md) R5) |
| [003 전략 선택](../003-strategy-settings/tasks.md) | T097에서 등록표 `axes[].termId`와 `DetailSettingsPanel`의 공용 `components/learning/TermHelp.tsx` 연결을 구현했다. 실제 API는 `termId`로 조회하며 설명 없음·조회 실패를 구분한다. 축 설명은 003 등록표가 계속 소유한다. T057a·T063에 남은 옛 경로·실패 처리 문구의 정리는 003 담당이 한다 | 003 담당 | 공용 부품 연결은 완료. 화면 왕복·입력 유지의 실행 결과는 [검증 기록](validation.md)에 적는다 |
| [002 예시 진입](../002-sector-weight-selection/tasks.md) | T020의 예시 수신·덮어쓰기 확인이 구현됐다(`diagnosis/learningExample.ts`, `SectorSelectionLearningEntry.test.tsx`) | 002 담당 | 미구현 대기가 아니다. quickstart 4-3에서 013 인계와 002 수신을 함께 확인한다 |
| [002 섹터·비중](../002-sector-weight-selection/spec.md) | 도움말을 붙일 용어와 자리를 정한다. spec에 013 참조가 없다 | 002 담당 | 002의 도움말만. 013 구현은 막지 않는다 |
| [005 결과 이해·비교](../005-result-comparison/spec.md) | 도움말을 붙일 용어와 자리를 정한다. 013 US3 시나리오 4가 '가장 크게 줄었던 폭' 도움말을 전제한다 | 005 담당 | 005의 도움말만 |
| [001 대표 ETF 자료](../001-etf-market-data/contracts/sector-api.md) | 열린 대응표 조회. `SectorService.getSectors()`가 이미 있다 | — | 없음 |

## 게이트 재확인 (Phase 1 설계 후)

설계를 마친 뒤 다시 확인했고 새로 생긴 constitution 위반은 없다. 아래는 설계가 원칙을 지키는 방식이다.

| 원칙 | 설계에서 지키는 방식 |
|---|---|
| I | `TermHelp`의 세 상태 어디에서도 호출한 화면의 입력·진행을 건드리지 않는다([term-help-ui](contracts/term-help-ui.md) §2). 글 조회에서 대응표를 읽지 못해도 글은 200으로 보이고 연결만 막는다([learning-public-api](contracts/learning-public-api.md) §2) |
| II | 글 화면 행동 버튼은 하나다. 돌아가기는 `a` 보조 연결이다([term-help-ui](contracts/term-help-ui.md) §3) |
| III | `hypothetical` 블록 밖의 결과성 숫자와 금지 표현을 게시에서 거절한다([data-model](data-model.md) §6). 화면은 가상 계산 예에 표시를 붙인다 |
| VI | 공개 조회의 두 404를 다른 코드로 둔다. 게시 전 검사는 걸린 항목을 모두 돌려주고 아무것도 바꾸지 않는다. 저장 요청의 알 수 없는 블록을 조용히 버리지 않고 400으로 거절한다 |
| VIII | 판·게시 기록을 고치거나 지우는 저장소 메서드를 두지 않는다. 판에 `check_version`·`sector_map_version`·`published_by_user_id`를 남긴다([data-model](data-model.md) §3-3) |
| 개인정보 | 관리 목록의 게시 기록은 `actorUserId`만 싣는다([learning-admin-api](contracts/learning-admin-api.md) §2) |

**의존 방향은 규칙으로 막는다**: `learning`은 대응표를 포트로 읽으므로 `learning.infrastructure → sector.application` 방향이 생긴다. `ArchitectureTest`는 도메인 패키지 사이 방향을 검사하지 않는다. 003이 `strategy → paramsearch` 금지를 규칙 8로 세운 것처럼, **`sector`가 `learning`을 참조하지 않는다**는 규칙을 B01에서 더한다.

## Project Structure

### Documentation (this feature)

```text
specs/013-learning-pages/
├── plan.md              # 이 파일
├── research.md          # Phase 0 산출물
├── data-model.md        # Phase 1 산출물
├── quickstart.md        # Phase 1 산출물
├── validation.md        # B06 실행 결과와 미실행 범위
├── contracts/           # Phase 1 산출물
│   ├── README.md                  # 소유·비소유 계약, 새 오류 코드
│   ├── learning-public-api.md     # 글 목록·글·용어 설명 공개 조회
│   ├── learning-admin-api.md      # 관리 권한·초안 저장·게시·게시 취소
│   └── term-help-ui.md            # 공용 도움말 컴포넌트, 돌아가기, 002 예시 진입
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks 산출물 — 이 명령이 만들지 않는다
```

### Source Code (repository root)

```text
backend/src/main/java/com/ssafy/thorp/learning/          # 013 소유 도메인
├── presentation/
│   ├── controller/      # LearningController(공개), LearningAdminController(관리)
│   ├── request/         # 글 만들기·초안 저장·게시 요청
│   ├── response/
│   └── support/         # LearningAdminAccess, LearningAdminWebConfig, UniqueConstraintViolations
├── application/
│   ├── service/         # LearningQueryService, LearningAdminService
│   ├── command/ result/
│   ├── exception/       # 공개 조회·관리 유스케이스 예외
│   ├── repository/      # 글·용어·판·기록·게시 용어 색인 저장소 인터페이스
│   └── port/            # SectorMapPort, LearningAdminPolicy, PublishCheckRulesPort
├── domain/
│   ├── model/           # LearningArticle, LearningTerm, ArticleBlock(sealed), ArticleRevision,
│   │                    # ArticleSnapshot, PublicationEvent, PublishCheck,
│   │                    # PublishCheckRules, PublishCheckResult
│   ├── exception/       # DraftChangedException, PublishCheckFailedException
│   └── vo/              # ArticleId, TermId, ExampleAllocation
└── infrastructure/
    ├── persistence/     # *JpaEntity, *Mapper, *RepositoryImpl, LearningContentJson
    ├── sector/          # SectorMapPort 구현 — SectorService를 부른다
    ├── admin/           # ConfiguredLearningAdminPolicy — thorp.learning.admin-user-ids
    └── rules/           # YamlPublishCheckRulesLoader

backend/src/main/java/com/ssafy/thorp/common/
├── config/SecurityConfig.java      # /admin/learning/** 를 authenticated() 로 묶는 한 줄
├── exception/ErrorCode.java        # spec 013 블록
└── web/advice/GlobalExceptionHandler.java  # 검증 오류의 details.fields

backend/src/main/resources/
├── db/migration/V20260917101000__learning_content.sql  # 다섯 테이블 + 아홉 글·열네 용어 자리
└── learning/publish-checks-v1.yml

docs/specs/learning-api/examples/   # 계약 픽스처

frontend/src/
├── types/learning.ts, types/learningAdmin.ts
├── api/learning.ts                 # 공개 조회
├── api/learningGlossary.ts         # fetchGlossaryEntry — 공개 용어 API, 세 상태 구분
├── api/learningAdmin.ts            # 관리
├── pages/LearningListPage.tsx      # /learn
├── pages/LearningArticlePage.tsx   # /learn/:articleId
├── pages/AdminLearningListPage.tsx # /admin/learning
├── pages/AdminLearningEditPage.tsx # /admin/learning/:articleId
└── components/learning/
    ├── ArticleBlocks.tsx           # 블록을 텍스트 노드로 그린다
    ├── ExampleEntry.tsx            # '이 예시로 비중 비교하기'와 연결 불가 안내
    ├── ReturnToDiagnosisLink.tsx   # '하던 진단으로 돌아가기'
    ├── LearningLoadFailure.tsx     # 조회 실패와 명시적 다시 시도
    ├── TermHelp.tsx                # 002·003·005 공용 도움말
    └── admin/
        ├── BlockEditor.tsx
        ├── ExampleEditor.tsx
        ├── TermListEditor.tsx
        ├── PublishCheckResultList.tsx
        └── adminDraftStorage.ts    # 미저장 편집과 기반 draftVersion 보관
```

**구조 선택 근거**: 이 저장소는 `backend/`와 `frontend/`가 나뉜 웹 애플리케이션이다. 백엔드는 도메인별 패키지 안에서 `presentation → application → domain/infrastructure` 계층을 ArchUnit으로 강제한다([ADR-0001](../../docs/adr/0001-be-layered-package-architecture.md)). 글·용어 설명의 소유자는 013이므로 새 `learning` 도메인에 둔다. 도움말 컴포넌트는 세 화면이 함께 쓰므로 특정 화면 폴더(`components/strategy`)가 아니라 `components/learning`에 둔다.

**관리 권한 판정을 `common`에 두지 않는다**: 지금 관리 권한이 필요한 곳은 013 하나다. `common`에 일반 역할 판정을 두면 014가 정할 역할 모델을 013이 먼저 정하는 셈이다. 014가 역할을 제공하면 그때 공통 위치로 옮길지 정한다.

## 작업 묶음 구성 (티켓·Branch·MR 단위)

constitution "Jira 연계"가 **작업 묶음 = 1 Branch = 1 MR**이고 **묶음 구성은 plan을 시작할 때 정한다**고 요구한다. `tasks.md`의 개별 task는 실행 지시의 최소 단위이며 티켓 단위가 아니다. **Jira Task·브랜치·MR의 단위는 아래 묶음 여섯이다.**

**티켓은 이 plan에서 만들지 않는다.** AGENTS.md가 "티켓은 브랜치를 만들 때 만든다"고 정했으므로 각 묶음에 착수할 때 만든다. 브랜치 이름의 번호는 그때 정해진다.

| 묶음 | Jira Task 제목 | 브랜치 (번호는 착수 시) | 담는 것 | 스토리 | 선행 |
|---|---|---|---|---|---|
| B01 | `[BE] 배우기 글과 용어 설명을 보관하고 공개 조회한다` | `feat(be)/{번호}-learning-content-api` | 마이그레이션(다섯 테이블·초기 자리), 도메인 모델·식별자, JPA 매핑, `SectorMapPort`와 구현, 공개 조회 세 엔드포인트, 오류 코드 일곱(공개 둘과, 도메인 모델·게시 검사가 쓰는 관리 코드 다섯을 한 블록으로), 계약 픽스처, ArchUnit 규칙 하나, 통합 테스트 | US1·US3 서버 | 없음 |
| B02 | `[BE] 관리 계정이 초안을 저장하고 게시·게시 취소한다` | `feat(be)/{번호}-learning-admin-api` | `LearningAdminPolicy`와 허용 목록 구현, `SecurityConfig` 경로, 관리 일곱 엔드포인트, `PublishCheck`와 규칙 파일, 게시·게시 취소 트랜잭션, 운영 환경 변수(`infra/docker-compose.prod.yml`·`infra/.env.prod.example`), 400 응답 보강, 통합 테스트 | US5 서버 | B01 |
| B03 | `[FE] 배우기 목록과 글을 보이고 예시로 진단을 시작한다` | `feat(fe)/{번호}-learning-pages` | 공개 타입·API, `/learn`·`/learn/:articleId`, 블록 그리기, 예시 002 진입과 연결 불가 안내, 빈 목록·볼 수 없는 글·불러오지 못함, 접근성 | US1·US2·US4 | B01 계약 |
| B04 | `[FE] 진단 화면이 함께 쓰는 용어 도움말을 만든다` | `feat(fe)/{번호}-term-help` | `learningGlossary.ts`, 공용 `TermHelp`, 글 화면의 돌아가기 연결 | US3 | B03 |
| B05 | `[FE] 관리 화면에서 글을 고치고 게시한다` | `feat(fe)/{번호}-learning-admin` | 권한 확인, 관리 목록, 편집기(블록·예시 섹터·용어), 게시 검사 결과 표시, 게시 취소, 편집 중 만료 보관, 409 처리 | US5 화면 | B02, B03 |
| B06 · [302](https://ssafy.atlassian.net/browse/S15P21E205-302) | `[DOCS] spec 013 문서 동기화와 최종 검증` | `docs(fe)/302-spec-013-wrapup` | [quickstart](quickstart.md) 실행 기록, spec·plan과 구현 차이 반영, README 색인 확인 | — | 전부 |

```text
B01 ─┬─▶ B02 ─────────────┐
     │                    ├─▶ B05 ─┐
     └─▶ B03 ─┬───────────┘        ├─▶ B06
              └─▶ B04 ─────────────┘
```

**묶음 여섯 = 티켓 여섯 = 브랜치 여섯 = MR 여섯.**

### 묶음을 이렇게 자른 이유

- **B01과 B02를 나눴다.** 공개 조회만으로 US1·US3의 서버가 닫히고, 초기 자리가 있어 002·003·005 화면 작업이 `termId`를 쓸 수 있다. 관리 API는 게시 전 검사와 권한까지 포함해 크고, B01을 기다리게 할 이유가 없다.
- **스키마는 B01에만 있다.** 다섯 테이블을 한 마이그레이션에 담는다. B02가 테이블을 더하지 않으므로 "스키마·매핑·검증을 같은 MR에" 규칙이 B01 안에서 닫힌다.
- **B04를 B03에서 뺐다.** 도움말은 002·003·005가 쓰는 공용 부품이라 리뷰어와 영향 범위가 다르다. 003 T057a가 이 묶음을 기다린다.
- **B05는 가장 크다.** 편집기가 블록·예시·용어 세 부분을 가진다. 더 쪼개면 "저장은 되는데 게시할 수 없는" 중간 상태가 생겨 묶음 조건 3(미완성 task 없음)을 어긴다. 브랜치는 하나로 두고 안에서 커밋으로 나눈다.

### 묶음에 넣지 않은 것

- **014 역할 개정.** 014 담당의 몫이다([교차 의존](#교차-의존)).
- **002·005 화면의 도움말 붙이기와 003 T057a 경로 수정.** 각 spec의 작업이다. 013은 공용 컴포넌트와 쓸 수 있는 `termId` 목록([data-model](data-model.md) §8)까지 제공한다.
- **글 원고 작성.** 운영자가 관리 화면에서 한다. 코드 묶음이 아니다. 진단 화면과 같은 이름을 쓰는 것(FR-006)과 글 하나가 질문 하나·예시 하나에 집중하는 것(FR-002)은 패턴으로 검사할 수 없으므로 게시 전 검사에 넣지 않고 원고를 쓰고 검토하는 사람이 지킨다.
- **사용성 관찰(SC-005).** 코드 묶음이 아니다. constitution "완료의 정의"에 따라 구현 완료와 따로 보고하며, B06은 수행 여부만 적는다.

## Complexity Tracking

| 항목 | 왜 단순한 방법으로 끝내지 않았나 | 기각한 더 단순한 방법 |
|---|---|---|
| 초안 작업본과 불변 게시 판을 나눈다 | 게시된 글을 고치는 동안 검사를 거치지 않은 문장이 공개되면 FR-017·SC-006이 깨진다 | 한 행에 `status`만 두기 — 저장하는 순간 공개 내용이 바뀐다([research](research.md) R2) |
| 게시 용어 색인 테이블을 따로 둔다 | 도움말 조회가 판 JSON을 뒤지지 않게 한다. 게시·게시 취소와 같은 트랜잭션으로 판과 어긋나지 않게 한다 | 판 JSON에서 조회 시 찾기 — 모든 공개 판을 읽어야 `termId` 하나를 찾는다 |
| 본문을 구조화 블록으로 담는다 | 용어 문장 일치(SC-003)와 가상 계산 예 표시(FR-005)를 구조로 보장한다 | Markdown — 문장을 두 곳에 적고 게시 때 문자열로 대조해야 한다([research](research.md) R3) |
| 관리 권한을 포트와 설정 허용 목록으로 둔다 | 014에 역할이 없고 014 소유 스키마를 013이 바꾸지 않는다. 014가 역할을 주면 한 클래스만 바꾼다 | `users.role` 컬럼 추가 — 014 범위 밖 항목을 013이 구현한다([research](research.md) R5) |
| 예시 섹터를 조회할 때마다 대응표와 대조한다 | 대응표는 게시 뒤에도 바뀐다(FR-004) | 게시 시에만 판정 — 사라진 섹터로 002에 들어간다([research](research.md) R8) |
