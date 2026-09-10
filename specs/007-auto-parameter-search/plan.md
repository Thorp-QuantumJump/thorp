# Implementation Plan: 설정 조합 자동 비교 (자동 파라미터 대량 탐색)

**Branch**: `007-auto-parameter-search` (spec 디렉터리 식별자. 구현 Task의 Git 브랜치는 Jira Task마다 `{type}({position})/{JIRA_NUMBER}-{description}`로 따로 만든다.) | **Date**: 2026-09-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/007-auto-parameter-search/spec.md`

## Summary

전략군과 섹터 후보 집합을 고정한 뒤, 등록표에서 만든 파라미터 조합 전체를 상한 안에서 계산하고
사전 기준으로 고른 5개 후보를 비교하게 한다.

기술 접근은 **새 계산 경로를 만들지 않는 오케스트레이션**이다. 후보 1건 = 기존 `backtest_job` 1행 +
`backtest.jobs` 메시지 1건이고, Worker는 변경하지 않는다. 007이 새로 만드는 것은 그리드 전개와
유효성 사전 제외, 발행 창(window) 기반 실행 관리, 결과 집계, 완료 후 1회 선정 계산, 재현 기록이다.
004가 다섯 배분 전략을 워커에 추가하면 007은 코드 변경 없이 그 전략을 탐색 대상으로 얻는다.

## Technical Context

**Language/Version**: Java 21 (Backend), Python 3.11 (Worker — 이번 범위에서 변경 없음), TypeScript 5 / React 19 (Frontend)

**Primary Dependencies**: Spring Boot 3.5.4 (Web, Data JPA, AMQP, Validation), Flyway, PostgreSQL 드라이버, Jackson. 신규 라이브러리 도입 없음

**Storage**: PostgreSQL. Flyway 마이그레이션 `V3__param_search.sql` 신규. 저장 규칙은 `V2__backtest_result.sql`의 규약을 따른다 — 질의(필터·정렬·조인)에 쓰는 값만 컬럼, 표시용 값은 JSONB, decimal은 예외 없이 JSONB

**Testing**: JUnit 5 + Testcontainers(PostgreSQL) + Spring Boot Test (Backend), Vitest (Frontend). 워커 변경이 없으므로 pytest 신규 없음

**Target Platform**: Linux 서버(Docker), 데스크톱 브라우저 우선 한국어 웹

**Project Type**: 모노레포 웹 서비스 (frontend + backend + worker). 이번 범위는 backend 신규 모듈 + frontend 화면 2개

**Performance Goals**: 목표 수치를 제시하지 않는다. 처리량·지연은 미측정이며 원칙 IX가 미측정 처리량 제시를 금지한다. FR-023에 따라 실행 유형 '자동 탐색 묶음'으로 대기 포함 소요 시간·동시 처리 수·자원 사용·실패·건당 원가를 **측정해 기록하는 것**이 이번 범위다

**Constraints**: 후보 수 상한 120·실행시간 상한 120초(둘 다 임시값, PD-06 실측 후 고정) / 한 묶음이 `backtest.jobs`를 독점하지 않을 것 / 취소가 즉시 효과를 낼 것 / 절대 문턱 숫자를 새로 만들지 않을 것(FR-013) / 결제·포인트 단계 없음(FR-010)

**Scale/Scope**: 묶음당 유효 후보 최대 120건 + 동일 비중 기준 1건. Backend 신규 패키지 1개(`paramsearch`), 테이블 2개, HTTP 엔드포인트 6개, Frontend 화면 2개(실행 전 확인, 결과)

### 해소된 NEEDS CLARIFICATION

Phase 0에서 5건을 해소했다. 상세 근거는 [research.md](research.md).

| 항목 | 결론 |
|---|---|
| 파라미터 등록표 원천 (PD-02·003 미구현) | `ParameterRegistryPort` + 임시 구현체. 003 완성 시 구현체 교체 (R1) |
| 실행시간·동시성 상한·중단 규칙 (PD-05 잔여) | 값 미확정. 발행 창 구조로 값과 무관하게 동작 (R2) |
| 동률 처리 (PD-05 잔여) | spec의 대체 규칙 사용 — 전부 표시, 임의 순서 금지 (R3) |
| 묶음 소유자 (인증 미구현) | `owner_user_id` nullable, 현재 NULL (R4) |
| 평가 분할 (PD-12 미확정) | 미등록 상태로 구현, 전 후보 '미검증' (R5) |

## Constitution Check

*GATE: Phase 0 이전 통과 필수. Phase 1 이후 재확인.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. 중심 약속은 전략 진단 | PASS | 전략 진단 흐름의 확장이다. 검증 부족(PD-12 미확정)이 기능을 막지 않도록 '미검증' 표시로 처리한다 (R5) |
| II. 첫 화면 단순 | PASS | 확인 화면은 대상 전략·범위 요약·예상 작업량·한도 4항목, 행동 버튼 1개. 후보 숫자 입력 없음(SC-001). 계산식·엔진·서버 설정을 사용자에게 요구하지 않는다 |
| III. 진단이지 추천이 아니다 | **PASS (조건부)** | 자동 단일 승자·추천 비중·우승 배지를 만들지 않는다. 다만 선정이 성과순 상위 5개라 UI에서 순위로 읽힐 위험이 있어 아래 "설계 제약"에 리뷰 규칙을 고정한다 |
| IV. 비교는 같은 조건에서만 | PASS | 모든 후보와 동일 비중 기준을 같은 섹터 집합·공통 평가 구간·금액·비용·참조 버전으로 실행한다(FR-007, FR-013d) |
| V. 당시에 알 수 있던 정보만 | PASS | 004의 계산 규칙을 그대로 상속한다. 007은 계산하지 않는다 |
| VI. 없는 결과를 만들지 않는다 | PASS | 제외/실패/미실행/계산 불가를 낮은 수익과 구분해 상태로 저장한다. 안정성 계산 불가를 0으로 채우지 않는다(FR-013b) |
| VII. 탐색과 검증 구분, 기준은 결과 전 고정 | PASS | 선정 규칙을 묶음 접수 시점에 스냅샷으로 저장하고 이후 변경은 새 묶음이다(FR-013c). 잠금 구간 미등록이라 선정에 쓰일 수 없다 |
| VIII. 재현 기록 | PASS | 탐색 공간·방법·전체/미실행 후보·제외 사유·선정 규칙·기준 결과·참조 버전을 저장한다(FR-020). 사용자에게 버전·실험 ID 입력을 요구하지 않는다 |
| IX. 실행 한도는 실측으로, 과금과 구분 | PASS | 후보 수 상한 120·실행시간 상한 120초(둘 다 임시)로 무제한 탐색을 열지 않는다. 발행 창으로 큐 독점을 막는다. 분산 Worker·Spark를 도입하지 않는다. 결제·포인트 단계 없음 |
| X. 자료는 이용 조건 안에서만 | N/A | 자료 확보는 001·008 소유. 007은 새 원천을 추가하지 않는다 |
| "하지 않는 것" 목록 | PASS | 추천 비중·주문·랭킹·상시 알림·자유 코드 입력·결제 단계 없음 |
| AI 작업 방식 | PASS | PD-05 잔여 4개와 PD-12를 임의 확정하지 않고 팀 확정 대기로 남겼다 (R2·R3·R5) |
| 실행·캐시 (진행·취소·재시도) | PASS | 진행은 상태 조회, 취소는 FR-009, 재시도는 FR-024로 갖춘다. 동일 결과 재사용만 004 소유로 남았고 그 근거는 아래 "Phase 1 이후 재확인"에 있다 |
| Git·Jira 규칙 (v3.0.0) | PASS | `1 Plan = 1 Spec`을 지킨다 — 이 plan은 spec 007 하나를 다룬다. `작업 묶음 = 1 Branch = 1 MR`에 따라 아래 "작업 묶음 구성"에서 묶음을 정했다 |

**Gate 결과**: 통과. 정당화가 필요한 위반 없음(Complexity Tracking 비움).

**기준 판**: constitution **v3.0.0** (Ratified 2026-09-09, Last Amended 2026-09-10). v1.0.0 기준으로
쓴 초판을 v3.0.0으로 다시 판정했다. 원칙 I~X의 번호와 내용은 그대로이고, 이번 판이 바꾼 것은
"Jira 연계"의 추적 단위(`1 Task = 1 Branch = 1 MR` → `작업 묶음 = 1 Branch = 1 MR`), "문서 역할"의
plan / tasks 항목, "AI 작업 방식"의 브랜치·Task 생성 항목이다. 그래서 원칙 판정은 변동 없고
**작업 묶음 절이 새로 필요해졌다.**

### 설계 제약 (원칙 III 조건부 통과에 딸린 것)

선정이 "필터 통과 후보를 비용 차감 후 수익으로 정렬한 상위 5개"이므로 다음을 코드·리뷰에서 고정한다.

1. 응답과 화면에 순위 번호(1위·2위)·추천 문구·우승 배지를 넣지 않는다. 표시 후보는 순서 있는
   목록이 아니라 **정렬 지표를 밝힌 집합**으로 렌더링한다.
2. 각 후보 옆에 `탐색 결과 / 미사용 검증 결과 / 미검증` 상태를 항상 함께 표시한다(FR-015).
   현재 구현에서는 전부 '미검증'이다.
3. 정렬 지표 이름("비용 차감 후 수익")을 화면에 명시한다. 한 항목 기준 정렬은 FR-017이 허용한다.
4. MR 리뷰 체크리스트에 "순위 배지·추천 표현 0건"을 넣는다(공통 성공 기준).

## Project Structure

### Documentation (this feature)

```text
specs/007-auto-parameter-search/
├── spec.md              # 입력
├── plan.md              # 이 파일
├── research.md          # Phase 0 산출물
├── data-model.md        # Phase 1 산출물
├── quickstart.md        # Phase 1 산출물
├── contracts/           # Phase 1 산출물
│   ├── param-search-api.md      # Backend ↔ Frontend HTTP 계약
│   └── param-search-internal.md # 모듈 간 포트·인메모리 이벤트 계약
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks 산출물 (이 명령이 만들지 않는다)
```

### Source Code (repository root)

```text
backend/src/main/java/com/ssafy/thorp/paramsearch/     # 신규 모듈
├── application/
│   ├── command/      CreateParamSearchCommand, PreviewParamSearchCommand
│   ├── port/         ParameterRegistryPort, CandidateJobLauncher
│   ├── repository/   ParamSearchRepository, SearchCandidateRepository
│   ├── result/       ParamSearchPreviewResult, ParamSearchResult,
│   │                 ParamSearchDetailResult, CandidatePageResult
│   ├── service/      ParamSearchService          # 접수·취소·재시도 유스케이스 (FR-024)
│   │                 GridExpander                # 조합 전개 + 그리드 좌표
│   │                 CandidateValidator          # 유효성 사전 제외 (FR-006)
│   │                 CandidateDispatcher         # 발행 창 관리 (R2)
│   │                 ParamSearchProgressService  # 결과 이벤트 집계
│   │                 NeighborStabilityCalculator # 인접 안정성 (FR-013a)
│   │                 CandidateSelector           # 두 필터 + 정렬 + 상위 5 (FR-013)
│   │                 CommonPeriodResolver        # 공통 평가 구간 산출
│   ├── service/selection/
│   │                 DrawdownFilter, StabilityFilter
│   └── service/limit/
│                     TimeLimitGuard, FailureLimitGuard
├── domain/
│   ├── model/        ParamSearch, ParamSearchStatus, SearchCandidate,
│   │                 CandidateStatus, SelectionRule, ValidationLabel
│   └── vo/           ParamSearchId, SearchSpace, ParameterCombination, GridCoordinates
├── infrastructure/
│   ├── persistence/  ParamSearchJpaEntity, SearchCandidateJpaEntity, *JpaRepository,
│   │                 *Mapper, *RepositoryImpl
│   ├── registry/     FixedParameterRegistry      # 003 완성 전 임시 구현 (R1)
│   ├── launcher/     CandidateJobLauncherImpl    # 004 접수 유스케이스 호출
│   ├── config/       ParamSearchProperties       # 한도·발행 창 설정
│   └── listener/     BacktestFinishedListener    # 인메모리 이벤트 수신 (R6)
└── presentation/
    ├── controller/   ParamSearchController
    ├── request/      ParamSearchPreviewRequest, ParamSearchCreateRequest
    └── response/     ParamSearchPreviewResponse, ParamSearchResponse,
                      ParamSearchDetailResponse, CandidatePageResponse

backend/src/main/java/com/ssafy/thorp/backtest/         # 기존 모듈 최소 변경
└── application/service/BacktestEventService.java        # 결과 저장 후 도메인 이벤트 발행 추가

backend/src/main/resources/db/migration/
└── V3__param_search.sql                                # 신규

backend/src/test/java/com/ssafy/thorp/paramsearch/       # 단위 + 통합 테스트

frontend/src/
├── api/paramSearch.ts                                  # 신규
├── components/paramsearch/                             # 확인 화면·결과 화면 구성요소
└── pages/ParamSearchPage.tsx                           # 신규

worker/                                                 # 변경 없음
```

**Structure Decision**: 기존 `backtest` 모듈과 같은 4계층(`presentation → application → domain →
infrastructure`)을 그대로 쓰고, 007을 **별도 최상위 도메인 패키지 `paramsearch`** 로 둔다.

- `backtest` 안에 넣지 않는 이유: 007은 004의 실행을 소비하는 상위 유스케이스라 의존 방향이
  `paramsearch → backtest` 한쪽이어야 한다. 같은 패키지에 두면 004의 단일 실행 경로가 묶음 개념을
  알게 되어 방향이 섞인다.
- 이름을 `search`로 하지 않는 이유: 010 경제환경 검색이 같은 낱말을 쓴다. 충돌을 피한다.
- `backtest` 모듈 변경은 결과 저장 후 인메모리 이벤트 발행으로 제한한다. `backtest.events` 큐를
  다시 소비하지 않는다 — competing consumers라 메시지가 분배되어 두 리스너가 각자 일부만 받는다(R6).

### Phase 1 이후 재확인

설계가 새로 만든 것(발행 창, 인메모리 이벤트, 동일 비중 기준 1건, `REJECTED` 저장)을 다시 판정했다.

| 원칙 | 판정 | 설계가 바꾼 점 |
|---|---|---|
| III | PASS (조건부 유지) | 응답에 순위 필드를 두지 않았다. `selection.displayed`는 정렬 지표를 밝힌 집합이다 |
| IV | PASS 강화 | `CandidateJobLauncher`가 묶음 스냅샷만 보고 조립해 후보·기준의 조건 동일성이 구조로 보장된다 |
| VI | PASS 강화 | 미완료·제외·실패 후보의 성과 필드를 `null`로 고정했다. 0으로 채울 경로가 없다 |
| VIII | PASS | `REJECTED` 묶음도 저장해 실행하지 않은 이유가 남는다. `random_seed`는 현재 NULL이고 그 사실을 기록한다 |
| IX | PASS 강화 | 발행 창 불변식(한 묶음의 `DISPATCHED` ≤ 창 크기)이 큐 독점을 구조로 막는다 |

**`/speckit-analyze` 이후 해소한 사항** — constitution "실행·캐시"가 "진행·취소·**재시도**와 동일 결과
재사용을 제공한다"를 요구하는데 초판에는 재시도가 없었다. spec에 FR-024(실패 후보 재시도)와 SC-009를
추가하고 묶음 B17·B19에 구현·검증·화면 태스크를 넣어 해소했다. 재시도는 묶음 시작 시점 스냅샷을
그대로 써서 원칙 VII(기준은 결과 전 고정)·VIII(재현)과 충돌하지 않는다.

**남은 사항 하나** — constitution "실행·캐시"는 동일 결과 재사용도 요구하는데, 현재 004는
"같은 조건 재요청 시 항상 새 Job"이다(`docs/specs/backtest-api/README.md` §4). 후보 120건 중 이전
묶음과 겹치는 조합이 있어도 다시 계산한다. 이는 **004가 소유한 미구현 항목**이며 007이 대신 캐시를
넣지 않는다 — 후보 접수 경로가 004와 같으므로 004에 재사용이 붙는 순간 007이 자동으로 이득을 본다.
007에 별도 캐시를 만들면 같은 규칙이 두 곳에 생긴다. tasks 단계에서 004 쪽 항목으로 남긴다.

## 작업 묶음 구성 (Branch·MR 단위)

constitution v3.0.0 "Jira 연계"가 **`작업 묶음 = 1 Branch = 1 MR`** 이고 **"묶음 구성은 plan을 시작할
때 정한다"** 고 요구한다. [tasks.md](tasks.md)의 `T###`는 실행 지시의 최소 단위이며 브랜치 단위가
아니다. 브랜치·MR·Jira Task 단위는 아래 묶음(M##) 10개다.

묶음마다 constitution이 정한 세 조건을 확인했다 — (1) 하나의 목적(같은 계층·기능 흐름·배포 단위)
공유, (2) 독립 구현·검증·리뷰와 다른 묶음을 건드리지 않는 롤백, (3) 리뷰어가 한 번에 읽을 수 있는
범위이며 미완성 task 없음.

| 묶음 | Jira Task 요약 | 브랜치 | 포함 T### | 수 |
|---|---|---|---|---|
| M1 | `[BE] 자동 탐색 기반 — 골격·도메인·스키마·영속화` | `feat(be)/157-paramsearch-foundation` | T001~T004, T007~T026, T020a | 25 |
| M2 | `[BE] 경계 포트·오류 매핑과 그리드 전개·유효성` | `feat(be)/{N}-paramsearch-boundary-grid` | T027~T039, T089, T090 | 15 |
| M3 | `[BE] 탐색 접수 유스케이스와 HTTP API` | `feat(be)/{N}-paramsearch-intake` | T040~T057, T049a | 19 |
| M4 | `[FE] 탐색 실행 전 확인·상세 변경·진행 상태` | `feat(fe)/{N}-paramsearch-preflight` | T005, T006, T058~T064 | 9 |
| M5 | `[BE] 후보 선정 계산과 조회 API` | `feat(be)/{N}-candidate-selection` | T065~T081 | 17 |
| M6 | `[FE] 탐색 결과 화면과 비교 연결` | `feat(fe)/{N}-paramsearch-result` | T082~T088 | 7 |
| M7 | `[BE] 거부·취소·재시도·부분 결과와 실행 한도` | `feat(be)/{N}-paramsearch-reject-cancel-limits` | T091~T103, T098a, T098b, T098c, T101a | 17 |
| M8 | `[FE] 거부·부분 결과 표시와 검증 배지` | `feat(fe)/{N}-paramsearch-notices-badge` | T104~T107, T107a, T114~T116 | 8 |
| M9 | `[BE] 검증 라벨·선정 규칙 불변성과 실행 측정` | `feat(be)/{N}-validation-label-metrics` | T108~T113, T117, T117a | 8 |
| M10 | `[DOCS] 계약·ADR 동기화와 quickstart 종단 확인` | `docs(infra)/{N}-paramsearch-docs` | T120~T123, T125, T126 | 6 |

**묶음 10개 = 브랜치 10개 = MR 10개.** tasks.md의 134개 중 131개를 덮고, 아래 3개는 의도적으로 뺐다.

각 묶음의 Jira Task가 없으면 **브랜치·MR을 만들기 전에** Task를 먼저 만들고 그 Ticket 번호로
브랜치를 만든다. `{N}`이 그 자리다. 개별 `T###` 번호는 MR 본문의 "주요 변경 사항"에 나열한다.

### 묶음 구성 개정 이력 (24개 → 10개)

초판은 묶음을 24개(B01~B24)로 뒀다. 구현 착수 시점에 **10개(M1~M10)로 합쳤다.** constitution
"묶음 구성은 plan을 시작할 때 정하고, 구현 도중 범위가 달라지면 그 사유와 새 묶음 구성을 plan 또는
MR 본문에 남긴다"에 따라 사유를 남긴다. **T### 범위는 하나도 바뀌지 않았다** — 131개를 덮는 것과
제외한 3개가 초판과 같고, 묶음 경계만 굵어졌다.

| 새 묶음 | 합친 초판 묶음 |
|---|---|
| M1 | B01 + B03 + B04 |
| M2 | B05 + B06 + B07 + B08 |
| M3 | B09 + B10 |
| M4 | B02 + B11 + B12 |
| M5 | B13 + B14 |
| M6 | B15 + B16 |
| M7 | B17 + B18 |
| M8 | B19 + B21 |
| M9 | B20 + B22 |
| M10 | B23 + B24 |

합친 사유:

1. **초판이 커밋 단위를 브랜치 단위로 쪼갰다.** B16(T087 하나)·B02(2개)·B08(2개)처럼 task 1~3개인
   묶음은 브랜치·MR·Jira Task를 각각 하나씩 소모하면서 리뷰에 얻는 것이 없다. constitution의 묶음
   규칙이 막으려던 것("task 하나를 반드시 브랜치 하나로 나눌 필요는 없다")에 초판이 오히려 가까웠다.
   묶음 안의 진행은 커밋으로 쪼갠다.
2. **B03/B04 분리는 조건 2를 어겼다.** 초판은 "도메인 모델과 스키마·영속화는 롤백 단위가 다르다"고
   적었지만, 이 프로젝트는 `ddl-auto=validate`다 — V3 마이그레이션 없는 엔티티는 기동을 실패시킨다.
   엔티티(B04)와 도메인 모델(B03)은 같이 움직여야 하는 배포 단위이므로 M1로 합치는 것이 조건 2에
   더 맞다. 초판의 분리 근거는 철회한다.
3. **B05~B08은 같은 목적을 공유한다** — 기반(M1)과 유스케이스(M3) 사이의 경계·전개 계층이다.
   003 등록표 포트, 004 실행 포트, 오류 매핑, 그리드 전개·유효성이 모두 "접수 전에 필요한 재료"다.
4. **B09/B10 분리는 유지할 이유가 사라졌다.** 초판은 18개가 조건 3을 넘는다고 봤지만, 같은 기능
   흐름(preview·create)의 application과 presentation을 갈라 두 MR로 만들면 앞 MR이 호출자 없는
   유스케이스만 담는다. 조건 3의 "미완성 상태로 남는 task가 없다"에 어긋난다.

유지한 경계:

- **파트를 섞지 않았다.** 브랜치 이름의 `position`이 하나여야 하므로(Git Convention) BE와 FE를 한
  묶음에 넣을 수 없다. M3(BE)와 M4(FE)가 같은 US1을 다루면서도 갈라져 있는 이유다.
- **테스트를 구현과 같은 묶음에 넣었다.** Task 완료 조건이 "테스트 또는 동작 확인 완료"라 검증이
  다른 브랜치에 있으면 그 묶음 스스로 완료 조건을 만족하지 못한다. M2가 Foundational 구현
  (T030·T031)과 US3 단계에 있는 그 테스트(T089·T090)를 함께 가져가는 것이 그 예다.
- **M9는 검증 라벨(B20)과 실행 측정(B22)을 합쳤다.** 실행 측정은 네 스토리 전부에 걸쳐 어느 스토리
  묶음에도 붙지 않는데, 단독 2개 묶음으로 두는 것보다 마지막 BE 묶음에 얹는 편이 낫다. 둘 다
  "구현이 끝난 뒤 결과에 라벨·기록을 붙이는 일"이라는 목적을 공유한다.

### 남은 크기 위험

M1(25)·M3(19)·M5(17)·M7(17)은 조건 3(리뷰어가 한 번에 읽을 수 있는 범위)의 경계에 있다. M1은
T011~T017이 enum·VO 단발 파일이라 실질 분량이 작다고 보고 합쳤다. **리뷰에서 분량이 문제가 되면
첫 번째 후퇴선은 M1에서 B04(T008~T010, T021~T026)를 다시 떼는 것이고**, 그때는 이 절에 다시 기록한다.

### 묶음에 넣지 않은 task

`T118`(`./gradlew spotlessApply` + `test`), `T119`(`npm run lint` + `test`), `T124`(`pytest` 회귀)는
묶음이 아니라 **모든 묶음의 완료 조건**이다("완료의 정의" 조건 2). 각 MR의 테스트 체크박스에서
확인하고, tasks.md에 남긴 것은 마지막에 전체를 한 번 더 돌리라는 뜻이다.

## Complexity Tracking

> Constitution Check에 정당화가 필요한 위반이 없어 비운다.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

## 이 plan이 다루지 않는 것

- 후보 하나의 계산 규칙과 다섯 배분 전략 구현 — [004](../004-backtest-execution/spec.md) 소유.
  **007의 종단 검증은 004 없이는 momentum 단일 전략으로만 가능하다.**
- 파라미터 등록표의 실제 값(PD-02) — [003](../003-strategy-settings/spec.md) 소유
- 비교 화면 구성 — [005](../005-result-comparison/spec.md) 소유. 007은 후보를 비교 대상으로 넘기는
  연결만 만든다(FR-019)
- 내 기록 목록·보관 정책(PD-13) — [006](../006-my-records/spec.md) 소유
- 인증·인가 도입 — 소유 spec 없음. 007은 nullable 소유자 컬럼만 준비한다(R4)
- 실행 한도의 확정값(PD-06)과 검증 설계(PD-12) — 실측·팀 확정 대기

**PD-05 잔여 세 값이 확정될 때까지 자동 탐색 모듈은 기능 플래그로 꺼져 있다.**
`thorp.param-search.enabled`가 `false`면 `ParamSearchConfig`가 로드되지 않는다. 공통 설정의 기본값이
`false`이고 로컬만 `true`다. `ParamSearchProperties`의 네 한도가 `@NotNull`이라 플래그 없이 무조건
로드하면 값을 채우지 않은 환경에서 **애플리케이션 전체가 기동에 실패**한다 — 007이 아직 엔드포인트를
노출하지 않는 단계에서 기존 기능까지 내려가는 것은 받아들일 수 없다. 값을 추측해 코드 기본값으로 박는
것(constitution "AI 작업 방식" 위반)과 배포를 깨는 것 사이의 세 번째 선택이다.

`dispatch-window`·`time-limit-seconds`·`consecutive-failure-limit`가 정해지면 그 값을 설정에 넣고
플래그를 켠다. 스키마(V3)는 플래그와 무관하게 Flyway가 적용하므로 나중에 켜도 마이그레이션을 다시
할 일이 없다. `ParamSearchProperties`를 주입하는 빈은 같은 플래그 조건을 가져야 한다(M3 이후).
