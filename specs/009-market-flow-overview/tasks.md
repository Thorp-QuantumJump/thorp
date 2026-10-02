# Tasks: 시장 흐름 개요

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Contracts**: [contracts/openapi.yaml](contracts/openapi.yaml) | **Date**: 2026-09-16

[plan.md](plan.md)의 작업 묶음 WB01~WB04를 그대로 따른다. 묶음 하나가 Jira Task 하나, 브랜치 하나,
MR 하나다. Jira 번호는 그 묶음의 브랜치를 만들 때 발급한다.

## 검증 명령

```bash
# backend
./gradlew spotlessApply build

# frontend
cd frontend && npm run lint && npm run build && npm test
```

**`npm test`는 통과 수만 보지 않는다. `skipped` 수와 수집된 파일 수를 함께 본다.** `vite.config.ts`의
`test.include`가 허용 목록이라 목록에 걸리지 않는 테스트 파일은 실패하지 않고 조용히 빠진다.
현재 값은 `['src/**/*.test.tsx', 'src/api/auth.test.ts', 'src/auth/**/*.test.ts']`이고, 이 tasks가 만드는
`src/api/marketFlow.test.ts`와 `src/marketflow/sectorLink.test.ts`는 **지금 목록에 걸리지 않는다.**
T015가 이 문제를 먼저 막는다.

## 묶음 한눈에 보기

| 묶음 | Jira Task 제목 | T항목 | 근거 요구사항 | Jira |
|---|---|---|---|---|
| WB01 | `[BE] 지표 스냅샷 포트와 예시 자료 어댑터` | T001~T006 | FR-007, FR-014, 008 FR-013·FR-014·FR-022, SC-004(재현 기반) | `S15P21E205-268` |
| WB02 | `[BE] 변화 요약 규칙과 시장 흐름 조회 API` | T007~T014 | FR-002, FR-004, FR-008, FR-011, FR-012, FR-013, FR-015, SC-002, SC-005, SC-007 | `S15P21E205-272` |
| WB03 | `[FE] 시장 흐름 화면 교체와 분야 요약·상세` | T015~T023 | FR-001, FR-003, FR-005, FR-006, FR-007, FR-008, FR-013, FR-014, SC-001, SC-002, SC-003, SC-005 | S15P21E205-314 |
| WB04 | `[FE] 섹터 연결과 참고 정보·연계 진입점` | T024~T030 | FR-009, FR-010, FR-016, FR-017, FR-018, FR-019, SC-004 | S15P21E205-322 |

**세 조건을 어떻게 만족하는가**

| 조건 | WB01 | WB02 | WB03 | WB04 |
|---|---|---|---|---|
| 1. 하나의 목적을 공유한다 | 008 자료를 읽는 경계 하나를 세운다 | 그 자료를 화면이 쓸 문장과 계약으로 바꾼다 | 시장 흐름 화면 한 벌을 교체한다 | 그 화면에서 바깥으로 나가는 길을 붙인다 |
| 2. 독립적으로 구현·검증·리뷰·롤백할 수 있다 | 포트 계약과 fixture 적재를 단위·Testcontainers 테스트로 검증한다. 되돌리면 새 패키지와 마이그레이션만 사라진다 | MockMvc로 계약을 검증한다. 되돌리면 API만 사라지고 WB01의 포트는 남는다 | 되돌리면 `/home` 목업 화면이 그대로 돌아온다 | 되돌리면 분야 요약·상세는 남고 섹터·연계 진입점만 사라진다 |
| 3. 리뷰 범위가 한 번에 읽히고 미완성 task가 없다 | 포트·모델·fixture·마이그레이션 | 규칙 두 벌과 엔드포인트 둘 | 화면 한 벌과 라우트·메뉴 정리 | 세션 보관 한 곳과 진입점 넷 |

**순서**: WB01 → WB02 → WB03 → WB04. WB03은 WB02 계약이 dev에 머지된 뒤 시작한다. 그 전에는
[contracts/examples/](contracts/examples/)의 예시 JSON으로 화면을 먼저 짤 수 있다.

## 이 tasks가 다루지 않는 것

| 항목 | 왜 다루지 않는가 |
|---|---|
| SC-006 사용성 관찰 | 사람이 화면을 써 보는 것을 관찰하는 항목이라 구현 task로 다루지 않는다. 관찰 절차는 [quickstart.md](quickstart.md) "사용성 확인"에 있다 |
| 008의 지표 수집·등록표 구현 | 008이 소유하며 다른 팀원이 병렬로 진행 중이다. 009는 `MacroIndicatorSnapshotPort`로 읽기만 하고, 합류 전에는 예시 자료 fixture를 쓴다([research.md](research.md) R2). 포트 모양 합의는 T001이 다룬다 |
| 경제환경 검색의 계산과 사례 표시 | 010이 소유한다. T027은 분야 g와 T_g를 넘기는 데까지만 한다 |
| 전략 비교 화면 | 011이 소유한다. T028은 섹터와 사례 식별자를 넘기는 데까지만 한다 |
| 뉴스 묶음의 수집·표시 규칙 | 012가 소유한다. T029는 이미 있는 `/api/market-news/digests`를 호출해 영역에 꽂는 데까지만 한다 |
| `DashboardPage.tsx`의 전략 추천 영역 | 전략 진단 화면은 003·005가 소유한다. **T023은 시장 흐름에서만 걷어내고 `DashboardPage`는 건드리지 않는다** |

---

## WB01: 지표 스냅샷 포트와 예시 자료 어댑터

**목표**: 008이 소유한 지표 관측값·기준월·수집 상태를 읽는 경계를 세우고, 008 구현이 없는 동안에도
예시 자료로 같은 값이 흐르게 한다.

**Independent Test**: 예시 자료 fixture를 넣고 포트가 7개 분야의 T_g와 지표별 3개월 경로를 돌려주는지,
결측이 결측으로 남는지 확인한다. 화면과 API 없이 검증할 수 있다.

- [x] T001 [WB01] [P] [US2] [BE] 지표 스냅샷 포트 정의 (`backend/src/main/java/com/ssafy/thorp/marketflow/application/port/MacroIndicatorSnapshotPort.java`) · Jira `S15P21E205-268`
  - 조회 넷을 둔다: `loadFieldReferenceMonths()`, `loadRecentPath(indicatorId, t)`, `loadIndicatorMeta(registryVersion)`, `loadCollectionState()` ([data-model.md](data-model.md) "읽기 포트")
  - **결측은 결측으로 돌려준다.** 포트가 빈 값을 채우면 008 FR-008과 원칙 VI를 009가 어기게 된다
  - `application/port`에는 인터페이스만 둔다. 구현체는 `infrastructure`에 둔다 (backend/CLAUDE.md, ArchUnit 규칙)
  - **시그니처를 확정하기 전에 008 담당자와 맞춘다.** 이 네 조회는 008 spec을 읽고 009가 혼자 정한 모양이라, 008이 다른 형태로 만들면 합류 시점에 어댑터 교체가 아니라 포트 재설계가 된다. 확인할 것은 ① T_g와 지표별 최신 확보월을 009가 계산하지 않고 받는가 ② 임의 기준월 t의 3개월 경로를 조회 단위로 주는가 ③ 수집 실패·예시 자료 여부를 **지표 단위로** 구분해 주는가 ([research.md](research.md) R2 합류 위험)
- [x] T002 [WB01] [P] [US2] [BE] 도메인 모델과 VO (`backend/src/main/java/com/ssafy/thorp/marketflow/domain/model/`, `backend/src/main/java/com/ssafy/thorp/marketflow/domain/vo/`) · Jira `S15P21E205-268`
  - `MarketFlowField`, `IndicatorDisplay`, `AfterReferenceMonthRelease`, `PathPoint`, `FieldStatus`를 만든다 ([data-model.md](data-model.md))
  - **분야 전체를 좋고 나쁨으로 요약하는 필드를 두지 않는다.** 점수·등급·신호등에 해당하는 값이 모델에 없어야 FR-003·SC-002를 구조로 막을 수 있다
  - `FieldStatus`는 `READY`·`EXAMPLE`·`EMPTY`·`COLLECTION_FAILED` 넷뿐이다. `STALE`·`DELAYED`를 두지 않는다 ([research.md](research.md) R6)
  - `domain`은 Spring·JPA·Jackson을 import하지 않는다 (backend/CLAUDE.md)
- [x] T003 [WB01] [P] [US3] [BE] 예시 자료 fixture (`backend/src/main/resources/marketflow/macro-indicators-example-v1.json`) · Jira `S15P21E205-268`
  - 008 FR-001의 7개 분야 17개 지표를 모두 담고, 지표마다 이름·단위·최근 3개월 값·대상월·발표일·출처·작성기관을 둔다
  - 분야마다 T_g가 다른 경우와 T_g 이후 발표가 있는 지표를 최소 한 건씩 넣는다. FR-008을 테스트할 자료가 여기 없으면 T013이 검증할 대상을 못 만든다
  - 파일 이름에 버전을 박는다. 008이 실제 등록표를 내면 이 파일은 지우지 않고 테스트 fixture로 남긴다
- [x] T004 [WB01] [US3] [BE] 예시 자료 어댑터 (`backend/src/main/java/com/ssafy/thorp/marketflow/infrastructure/example/ExampleMacroIndicatorSnapshotAdapter.java`, 같은 경로의 테스트) · Jira `S15P21E205-268`
  - T001의 포트를 T003의 fixture로 구현한다
  - 이 어댑터가 돌려주는 자료에는 예시 표시가 붙는다. 예시 자료를 `READY`로 표현하지 않는다 (FR-014, 008 FR-022)
  - 008이 실제 저장소를 만들면 **이 클래스만 교체한다.** 포트 시그니처와 읽기 모델은 그대로 둔다
  - 테스트: 7개 분야가 모두 오는지, 결측 지표의 값이 null로 남는지, 분야별 T_g가 서로 다를 수 있는지
- [x] T005 [WB01] [US1] [BE] 읽기 모델 마이그레이션 (`backend/src/main/resources/db/migration/V20260916xxxxxx__market_flow_field_summary.sql`) · Jira `S15P21E205-268`
  - 컬럼 구성은 [data-model.md](data-model.md) "저장 — 읽기 모델 한 벌"을 따른다
  - PK에 `field_id`·`registry_version`·`dataset_version`을 넣는다. 버전이 PK에 없으면 같은 버전으로 다시 만들었을 때 같은 값이 나오는지 보장할 자리가 사라진다 (원칙 VIII)
  - 파일명의 타임스탬프는 만들 때의 실제 시각으로 정한다. 기존 마이그레이션 명명 규칙(`V20260916104500__...`)을 따른다
- [x] T006 [WB01] [US1] [BE] 읽기 모델 적재·조회 (`backend/src/main/java/com/ssafy/thorp/marketflow/infrastructure/persistence/`, `backend/src/main/java/com/ssafy/thorp/marketflow/application/repository/`) · Jira `S15P21E205-268`
  - `application/repository`에 인터페이스, `infrastructure/persistence`에 `@Entity`와 구현을 둔다 (backend/CLAUDE.md)
  - 갱신은 등록표 버전 또는 자료 버전이 바뀔 때만 한다. 사용자 요청이 재계산을 부르지 않는다 ([research.md](research.md) R7)
  - 테스트: Testcontainers PostgreSQL 16에서 같은 `(registry_version, dataset_version)`으로 두 번 적재하면 행이 같은지

---

## WB02: 변화 요약 규칙과 시장 흐름 조회 API

**목표**: 분야별 변화 요약 문장과 분야 상세를 계약대로 제공한다. 판정·전망 표현이 섞이지 않는 것을
사람 눈이 아니라 테스트로 막는다.

**Independent Test**: 예시 자료를 넣고 `/market-flow/fields`와 `/market-flow/fields/{fieldId}`를 호출해
[contracts/examples/](contracts/examples/)의 예시와 같은 모양이 오는지 확인한다.

- [x] T007 [WB02] [P] [US1] [BE] 서술 대상 지표 선정 규칙 (`backend/src/main/java/com/ssafy/thorp/marketflow/domain/model/NarrativeSubjectSelector.java`, 같은 경로의 테스트) · Jira `S15P21E205-272`
  - ① 방향 지속 길이 → ② 같은 단위끼리의 변화 폭 → ③ 등록표의 분야 내 고정 순서로 가른다. 최대 2개 ([research.md](research.md) R1, FR-004)
  - **단위가 다른 지표의 변화 폭을 비교하지 않는다.** 대외거래(수출 %, 무역수지 억달러)나 금융·주택(금리 %, 환율 원)에서 성립하지 않기 때문이다
  - **표준화 점수·거리를 계산에 쓰지 않는다.** 척도 선택은 010 PD-08의 몫이고, 009가 먼저 고르면 두 화면의 설명이 어긋난다
  - 1단계 값이 전부 0이면 빈 목록을 돌려준다. 억지로 하나를 고르지 않는다
  - 테스트: 단위가 섞인 분야에서 결과가 결정론적인지, 3개월 경로가 결측이면 빈 목록인지, 동률 입력에서 등록표 순서로 갈리는지
- [x] T008 [WB02] [P] [US1] [BE] 변화 요약 문장 formatter와 금지 어휘 검사 (`backend/src/main/java/com/ssafy/thorp/marketflow/domain/model/NarrativeFormatter.java`, 같은 경로의 테스트) · Jira `S15P21E205-272`
  - (방향 × 지속 길이) 조합으로 고정된 문장 틀에 지표 이름·값·단위·기간만 채운다. 규칙 버전은 `market-flow-narrative-v1` ([research.md](research.md) R8)
  - **자유 생성 문장과 LLM 호출을 쓰지 않는다** (spec Assumptions, 원칙 VI)
  - 3개월 자료가 없으면 '최근 경로 없음'을 뜻하는 문장만 만들고 지어내지 않는다 (Edge Case)
  - 테스트: 금지 어휘(좋다·나쁘다·유망·전망·기대·추천·비중 확대 등) 목록 전체에 대해 출력에 등장하지 않는지. **SC-002의 "0건"을 코드로 받는 자리다**
- [x] T009 [WB02] [US3] [BE] 분야 상태 판정 (`backend/src/main/java/com/ssafy/thorp/marketflow/application/service/FieldStatusDecider.java`, 같은 경로의 테스트) · Jira `S15P21E205-272`
  - 판정 순서는 [data-model.md](data-model.md) "상태 전이"를 따른다
  - **시간 경과만으로 전이하지 않는다.** '얼마나 오래됐는지'로 지연을 판정하는 분기를 두지 않는다 (FR-011, 2026-09-16 clarify)
  - 테스트: 수집 실패 기록이 있으면 `COLLECTION_FAILED`, 예시 자료면 `EXAMPLE`, 지표 메타가 없으면 `EMPTY`. 오래된 대상월만으로는 상태가 바뀌지 않는다
- [x] T010 [WB02] [US1] [BE] 분야 요약 빌더 (`backend/src/main/java/com/ssafy/thorp/marketflow/application/service/FieldSummaryBuilder.java`, 같은 경로의 테스트) · Jira `S15P21E205-272`
  - T007·T008·T009를 조합해 읽기 모델 행을 만들고 T006으로 적재한다
  - T_g를 물러나게 한 원인 지표를 `referenceMonthHeldBackBy`에 담는다 (Edge Case)
  - 테스트: 같은 `(registryVersion, datasetVersion)`으로 두 번 만들면 문장·서술 대상 지표·기준월이 100% 같은지 (원칙 VIII)
- [x] T011 [WB02] [US1] [BE] 분야 요약 조회 API (`backend/src/main/java/com/ssafy/thorp/marketflow/presentation/controller/MarketFlowController.java`, `.../presentation/response/`, MockMvc 테스트) · Jira `S15P21E205-272`
  - `GET /market-flow/fields`. 외부 경로는 프록시가 `/api`를 제거하므로 `/market-flow`로 매핑한다 (docs/specs/market-api.md와 같은 규칙)
  - 응답 봉투는 `HttpApiResponse` 형식(`{httpStatus, isSuccess, message, code, result}`)을 따른다
  - **쿼리 파라미터를 하나도 받지 않는다. 이름과 무관하게 하나라도 붙으면 400이다.** 섹터가 경제환경 표시를 바꿀 수 없어야 하는데(FR-016, SC-004, 원칙 IV), 조용히 무시하면 호출자가 걸러진 줄 착각한다. 같은 화면이 쓰는 012 뉴스 API가 `sectorId`를 실제로 받아 필터하므로 그 호출을 복사해 오기 쉽다. `sectorId`라는 이름만 거부하면 `sector`·`sectorCode`가 통과하므로 허용 목록을 비워 둔다. 분야를 고르는 것은 경로 변수라 이 규칙과 무관하다
  - `historicalComparisonNotice`에 '최신 개정자료를 이용한 역사 비교' 고정 문구를 싣는다 (FR-012)
  - 인증을 요구하지 않는다 (FR-001, D02)
  - 테스트: 7개 분야가 항상 오는지, `EMPTY`·`COLLECTION_FAILED`도 HTTP 200인지, 값이 없는 필드가 키 누락이 아니라 명시적 null인지, 임의의 쿼리 파라미터가 400인지
- [x] T012 [WB02] [US2] [BE] 분야 상세 조회 API (`backend/src/main/java/com/ssafy/thorp/marketflow/presentation/controller/MarketFlowController.java`, MockMvc 테스트) · Jira `S15P21E205-272`
  - `GET /market-flow/fields/{fieldId}`. 7개 고정 분야 밖의 값은 400
  - 지표마다 실제 값·단위·3개월 경로·대상월·발표일·출처·작성기관을 싣는다 (FR-007, FR-013, FR-015)
  - **값을 표준화 점수나 거리로 바꾸지 않는다** (FR-007, SC-003)
  - 테스트: 작성기관이 빠진 지표가 0개인지, 결측 값이 null로 오는지, 잘못된 `fieldId`가 400인지
- [x] T013 [WB02] [US2] [BE] T_g 이후 발표 분리 (`backend/src/test/java/com/ssafy/thorp/marketflow/`) · Jira `S15P21E205-272`
  - `afterReferenceMonthRelease`가 있는 지표에서 그 값이 `path`의 t-2·t-1·t 어디에도 들어가지 않는지 검증한다 (FR-008, 2026-09-16 clarify)
  - **그 값이 T007의 선정과 T008의 문장에도 쓰이지 않는지** 함께 검증한다. 경로에서만 빼고 서술에 쓰면 clarify 답을 절반만 지킨 것이 된다
  - `referenceMonth`가 분야 T_g보다 반드시 뒤인지 검증한다
- [x] T014 [WB02] [US1] [BE] 조회 성능 측정 (`backend/src/test/java/com/ssafy/thorp/marketflow/`) · Jira `S15P21E205-272`
  - 17개 지표 × 120개월 fixture와 Testcontainers PostgreSQL 16에서 30회 warm-up 후 100회 측정한 `/market-flow/fields` p95를 기록한다 ([quickstart.md](quickstart.md) S5)
  - 측정값을 MR 본문에 남긴다. 목표를 못 맞추면 캐시를 더 얹기 전에 어디서 시간을 쓰는지부터 기록한다 (원칙 IX)

---

## WB03: 시장 흐름 화면 교체와 분야 요약·상세

**목표**: 목업 진단 화면을 걷어내고 spec에 맞는 시장 흐름 화면을 세운다.

**Independent Test**: `/market-flow`에 들어가 7개 분야 요약이 보이고, 경제 전체를 하나의 점수·등급으로
요약한 요소가 0개이며, 분야를 열면 지표 실제 값·단위·3개월 경로·대상월·출처가 보이는지 확인한다.

- [x] T015 [WB03] [FE] 테스트 수집 목록 확장 (`frontend/vite.config.ts`) · Jira S15P21E205-314
  - `test.include`에 `'src/api/*.test.ts'`와 `'src/marketflow/**/*.test.ts'`를 더한다
  - **이 task가 먼저다.** 지금 목록은 `src/api/auth.test.ts`와 `src/auth/**/*.test.ts`만 이름으로 집어 넣고 있어, T017과 T024가 만드는 `.ts` 테스트가 실패하지 않고 조용히 빠진다 (frontend/CLAUDE.md)
  - 확장 뒤 `npm test`의 수집 파일 수가 늘었는지 눈으로 확인한다
- [x] T016 [WB03] [P] [US1] [FE] 계약 대응 타입 (`frontend/src/types/marketFlow.ts`) · Jira S15P21E205-314
  - [contracts/openapi.yaml](contracts/openapi.yaml)의 schema와 1:1로 맞춘다. 필드는 camelCase (frontend/CLAUDE.md)
  - `FieldStatus`는 넷뿐이다. `STALE`을 타입에 두지 않는다
- [x] T017 [WB03] [P] [US1] [FE] 조회 함수 (`frontend/src/api/marketFlow.ts`, `frontend/src/api/marketFlow.test.ts`) · Jira S15P21E205-314
  - `apiClient`를 경유한다. 컴포넌트에서 axios를 직접 부르지 않고 URL을 하드코딩하지 않는다 (frontend/CLAUDE.md)
  - `EMPTY`·`COLLECTION_FAILED`는 오류가 아니라 정상 응답이다. 예외로 던지지 않는다
  - 테스트: 네 상태 예시 JSON을 모두 통과시키는지
- [x] T018 [WB03] [US1] [FE] 분야 요약 카드 (`frontend/src/components/marketflow/FieldSummaryCard.tsx`, `frontend/src/components/marketflow/FieldSummaryCard.test.tsx`) · Jira S15P21E205-314
  - 분야 이름·변화 요약 문장·기준월·상태·마지막 갱신 시각을 그린다
  - **점수·등급·신호등을 그리지 않는다.** 색으로 좋고 나쁨을 표현하는 것도 등급 표시다 (FR-003, SC-002)
  - 테스트: `narrative`가 null인 상태에서 빈 요약 대신 상태 표시가 보이는지
- [x] T019 [WB03] [US2] [FE] 분야 상세 (`frontend/src/components/marketflow/FieldDetail.tsx`, `frontend/src/components/marketflow/FieldDetail.test.tsx`) · Jira S15P21E205-314
  - 지표마다 실제 값·단위·3개월 경로(t-2·t-1·t)·대상월·발표일·출처·작성기관을 보인다 (FR-007, FR-013, FR-015)
  - **T_g 이후 발표는 그 지표 행 바로 아래 한 줄**로, 3개월 경로와 구분된 서식으로 그린다 (FR-008, 2026-09-16 clarify)
  - 분야 기준월과 "분야마다 기준월이 다를 수 있습니다" 한 문장 안내를 보인다
  - 테스트: `afterReferenceMonthRelease`가 있는 지표에서 그 값이 경로 3칸 안에 렌더되지 않는지
- [x] T020 [WB03] [US3] [FE] 자료 시점·상태 표시 (`frontend/src/components/marketflow/DataStateNotice.tsx`, `frontend/src/components/marketflow/DataStateNotice.test.tsx`) · Jira S15P21E205-314
  - `EXAMPLE`·`EMPTY`·`COLLECTION_FAILED`를 오류 화면이 아닌 상태 표시로 그린다 (FR-014, SC-005)
  - 대상월과 마지막 갱신 시각을 항상 함께 보인다. **'실시간'·'현재' 표현과 '지연' 판정 표시를 쓰지 않는다** (FR-011)
  - '최신 개정자료를 이용한 역사 비교' 표시를 화면에 둔다 (FR-012)
  - 테스트: 세 상태 각각에서 오류 경계로 떨어지지 않는지, 금지 표현이 렌더되지 않는지
- [x] T021 [WB03] [US1] [FE] 시장 흐름 화면 조립 (`frontend/src/pages/MarketFlowPage.tsx`, `frontend/src/pages/MarketFlowPage.module.css`, `frontend/src/pages/MarketFlowPage.test.tsx`) · Jira S15P21E205-314
  - 7개 분야 요약을 먼저 그리고, 뉴스·국내 시장 참고 정보 영역은 늦게 채워져도 요약 표시를 막지 않는다 (SC-007, FR-017)
  - 가장 중요한 행동 버튼은 하나다. 기본 화면은 분야 열기, 열린 분야에서는 '비슷했던 때 보기' (FR-005)
  - 자료 공급원·API 이름을 고르는 입력을 두지 않는다 (FR-006)
  - 테스트: 로그인하지 않은 상태로 7개 분야가 보이는지(SC-001), 그리고 **경제 전체를 하나의 점수·등급으로 요약한 요소가 0개인지(SC-002)**
- [x] T022 [WB03] [US1] [FE] 라우트와 메뉴 전환 (`frontend/src/App.tsx`, `frontend/src/components/landing/LandingHeader.tsx`, `frontend/src/components/navigation/AppHeader.tsx`, `frontend/src/pages/MyRecordsEntryPage.tsx`, `frontend/src/pages/LandingPage.test.tsx`) · Jira S15P21E205-314
  - `/market-flow` 라우트를 더하고 `/home`은 `/market-flow`로 영구 리다이렉트한다 ([research.md](research.md) R4)
  - 메뉴 링크를 바꾼다: `LandingHeader.tsx:13`의 `'시장 흐름' → '/home'`, `AppHeader.tsx`의 시장 흐름 항목, `MyRecordsEntryPage.tsx:29`의 '시장 흐름 보기'
  - `LandingPage.test.tsx:53`이 `{ label: '시장 흐름', href: '/home' }`을 기대하고 있으므로 함께 고친다. **이 테스트를 지우지 말고 새 경로로 바꾼다**
  - 리다이렉트를 먼저 넣어야 외부에 이미 퍼진 `/home` 링크가 깨지지 않는다
- [x] T023 [WB03] [US1] [FE] 목업 진단 화면 제거 (`frontend/src/pages/HomePage.tsx`, `frontend/src/pages/HomePage.module.css`, `frontend/src/components/home/MarketRegimeHero.tsx`, `frontend/src/components/home/MarketRegimeHero.module.css`, `frontend/src/components/home/SimilarMarketPanel.tsx`, `frontend/src/components/home/SimilarMarketPanel.module.css`, `frontend/src/data/marketMock.ts`) · Jira S15P21E205-314
  - **지우는 것**: `HomePage`(T022의 리다이렉트로 대체됨), `MarketRegimeHero`, `SimilarMarketPanel`. 세 파일 모두 `HomePage`만 쓰고 있어 함께 지울 수 있다
    - `MarketRegimeHero`는 `regime.headline`으로 "지금은 조심하되, 완전히 나쁜 시장은 아닙니다."라는 경제 전체 판정을 띄운다. **이것이 남아 있으면 SC-002를 통과할 수 없다** (FR-003, D08, 원칙 II)
    - `SimilarMarketPanel`은 010이 소유할 검색 결과를 목업으로 선점한다
  - **남기는 것**: `StrategyRecommendationSection`과 `MarketDataStatus`는 `frontend/src/pages/DashboardPage.tsx`도 쓰고 있다. **파일을 지우지 말고 시장 흐름 화면에서만 뺀다.** 전략 진단 대시보드는 003·005가 소유하므로 009가 건드리지 않는다
  - `marketMock.ts`에서는 `regime`·`similarPeriod`와 시장 흐름 전용 항목만 덜어낸다. `DashboardPage`가 참조하는 항목은 남긴다
  - 지우기 전에 `npm run build`로 남은 참조가 없는지 확인한다
  - `MarketRegimeChart`·`MarketOverviewCarousel`은 `MarketRegimeHero`가 쓰던 하위 컴포넌트다. 다른 참조가 없으면 함께 지우고, 있으면 남긴다

---

## WB04: 섹터 연결과 참고 정보·연계 진입점

**목표**: 섹터 선택과 바깥 화면으로 나가는 길을 붙인다. 섹터가 경제환경 표시를 바꾸지 않는 것을
테스트로 못 박는다.

**Independent Test**: 섹터 선택 전후로 분야 요약·지표 값·기준월이 같은지, 새로고침 뒤 선택이 남고
탭을 다시 열면 풀리는지 확인한다.

- [x] T024 [WB04] [P] [US4] [FE] 섹터 선택 세션 보관 (`frontend/src/marketflow/sectorLink.ts`, `frontend/src/marketflow/sectorLink.test.ts`) · Jira S15P21E205-322
  - `sessionStorage` 키 하나에 선택 섹터를 담는다. 계정·서버에 저장하지 않는다 (FR-016, [research.md](research.md) R5)
  - 읽기·쓰기를 `try/catch`로 감싼다. 저장소를 쓸 수 없어도 예외를 밖으로 던지지 않는다
  - 화면 없이 단위 테스트할 수 있게 순수 함수로 둔다. `src/auth/returnTarget.ts`와 같은 방식이다
  - 테스트: 저장 후 읽으면 같은 값인지, 저장소가 막힌 환경에서 읽기가 null을 돌려주고 예외가 새지 않는지
  - **T015가 먼저 끝나야 이 `.ts` 테스트가 수집된다**
- [x] T025 [WB04] [US4] [FE] 섹터 선택 UI와 경제환경 불변 검증 (`frontend/src/components/marketflow/SectorSelector.tsx`, `frontend/src/components/marketflow/SectorSelector.test.tsx`, `frontend/src/pages/MarketFlowPage.tsx`) · Jira S15P21E205-322
  - 섹터 목록은 002와 같은 목록을 쓴다 (spec Assumptions)
  - 선택은 연결할 전략·대표 상품·뉴스 범위만 좁힌다 (FR-016)
  - **섹터를 분야 요약·상세 요청에 넣지 않는다.** 계약에 파라미터가 없으므로 넣을 자리도 없어야 한다
  - 테스트: 섹터 선택 전후로 분야 요약·지표 값·기준월 렌더 결과가 같은지(SC-004), 새로고침 모사 후 선택이 남고 세션을 비우면 풀리는지
- [x] T026 [WB04] [US4] [FE] 국내 시장 참고 정보 (`frontend/src/components/marketflow/DomesticMarketReference.tsx`, `frontend/src/components/marketflow/DomesticMarketReference.test.tsx`) · Jira S15P21E205-322
  - 기존 `/api/markets/kospi`를 호출해 가격 추세·변동성을 **경제환경 지표와 구분된 별도 영역**에 그린다 (FR-017)
  - '한국거래소 통계정보' 출처를 표시한다 (FR-015)
  - 자료가 없으면 이 영역만 비우고 분야 요약은 정상 표시한다 (Edge Case)
  - 테스트: 이 영역의 값이 '비슷했던 때 보기' 요청에 실리지 않는지
- [x] T027 [WB04] [US2] [FE] '비슷했던 때 보기' 진입점 (`frontend/src/components/marketflow/FieldDetail.tsx`) · Jira S15P21E205-322
  - 열린 분야에서 분야 g와 그 분야의 기준월 T_g를 유지한 채 010으로 넘어간다 (FR-009)
  - 010이 아직 없으므로 **넘기는 값이 무엇인지까지만** 만든다. 목적지 화면을 여기서 만들지 않는다
  - 섹터를 골랐어도 넘기는 분야·기준월은 달라지지 않는다 (FR-016)
- [x] T028 [WB04] [US4] [FE] '이 기간으로 전략 비교' 진입점 (`frontend/src/components/marketflow/`) · Jira S15P21E205-322
  - 선택 섹터(있으면)와 사례 식별자를 011로 넘긴다 (FR-018)
  - 011이 아직 없으므로 넘기는 값까지만 만든다
- [x] T029 [WB04] [US1] [FE] 뉴스 묶음 영역 연결 (`frontend/src/pages/MarketFlowPage.tsx`, `frontend/src/components/marketflow/`) · Jira S15P21E205-322
  - 이미 있는 `/api/market-news/digests`를 호출해 같은 기본 화면에 꽂는다. 내용·수집·표시 규칙은 012가 정한다 (FR-019)
  - **뉴스가 없거나 늦어도 분야 요약은 정상 표시한다** (FR-019, SC-007)
  - 012의 `PREPARING`·`EMPTY`·`STALE` 상태를 그대로 받아 그린다. 009가 다시 판정하지 않는다
- [x] T030 [WB04] [FE] 외부 계약 문서 (`docs/specs/market-flow-api.md`) · Jira S15P21E205-322
  - `docs/specs/market-api.md`·`market-news-api.md`와 같은 형식으로 경로·응답·상태를 정리한다
  - 상태 집합에 `STALE`이 없는 이유(갱신 주기 차이)를 한 줄 적는다. 012 문서와 나란히 읽힐 문서라 다른 이유가 드러나 있어야 한다
  - **분야 요약 조회가 쿼리 파라미터를 하나도 받지 않고 붙으면 400이라는 점을 적는다.** 012는 `sectorId`를 받아 필터하므로, 두 문서가 나란히 읽힐 때 왜 다른지가 드러나야 한다

---

## 의존 관계

```
WB01 (T001~T006)
  └─▶ WB02 (T007~T014)
        └─▶ WB03 (T015~T023)
              └─▶ WB04 (T024~T030)
```

- 묶음 사이는 순차다. 앞 묶음이 dev에 머지된 뒤 다음 브랜치를 딴다.
- WB03은 WB02 계약이 머지되기 전에도 [contracts/examples/](contracts/examples/)로 화면을 먼저 짤 수 있다.
- **T015는 WB03의 첫 task다.** 뒤로 미루면 T017·T024의 테스트가 통과한 것처럼 보이면서 수집되지 않는다.

## 병렬로 할 수 있는 것

| 묶음 | 동시에 가능한 task | 왜 |
|---|---|---|
| WB01 | T001, T002, T003 | 서로 다른 파일이고 참조하지 않는다 |
| WB02 | T007, T008 | 선정 규칙과 문장 틀은 입력이 다르다. T010이 둘을 합친다 |
| WB03 | T016, T017 | 타입과 조회 함수는 T015가 끝난 뒤 각자 진행한다 |
| WB04 | T024는 T025보다 먼저 | T025가 T024의 보관 함수를 쓴다 |

## 구현 전략

**MVP는 WB01 + WB02 + WB03이다.** 여기까지면 전략을 정하지 않은 사용자가 시장 흐름에 들어가 7개 분야
요약을 보고 관심 분야를 열어 지표 실제 값을 확인할 수 있다. User Story 1(P1)과 2(P1), 3(P2)이 모두
동작하고 SC-001·SC-002·SC-003·SC-005·SC-007을 판정할 수 있다.

WB04는 User Story 4(P3)와 바깥 spec 연결이다. 010·011이 아직 없어 진입점이 넘기는 값까지만 만들 수
있으므로 마지막에 둔다.

**009가 끝나도 예시 자료로 동작한다.** 실제 지표 값은 병렬로 진행 중인 008이 합쳐지고 PD-11이 확정될 때
들어온다. 그때 바뀌는 것은 `ExampleMacroIndicatorSnapshotAdapter` 하나다 — **T001의 포트 합의가 되어
있다는 전제에서다.**

## 시장 흐름 카드 이동·숫자 표시 작업 묶음 (2026-10-01)

브랜치: `feat(fe)/market-flow-context`. 대상 spec: 009, 연계 요구사항 002 FR-018·FR-019와 012 FR-001을 함께 갱신한다.

- [x] MF-01 과거 사례·뉴스를 전략 진단에서 시장 흐름으로 이동하고 좁은 화면에서 세로 배치한다.
- [x] MF-02 요약 소수를 첫째 자리로 표시하고 달러 금액을 천만 단위로 축약한다. 원값 계산은 유지한다.
- [x] MF-03 프론트엔드 필수 검증과 AI 코드리뷰를 수행한다(lint 오류 0·기존 경고 26, build 통과, Vitest 1494·legacy 15 통과, skipped 0, 최종 수정 관련 회귀 검사 49 통과).
- [ ] MF-04 MR을 dev 대상으로 병합한다.

- [x] MF-05 단일 지표 분야를 두 지표 분야와 같은 폭의 왼쪽 한 칸에 배치한다. (2026-10-02 MF-07이 대신한다)
- [x] MF-06 과거 사례를 분야 상세 안으로 옮겨, 고른 과거 구간의 같은 지표 경로를 그래프에 겹치고 비슷한 점·다른 점을 그 지표 칸에 붙인다. 'Thorp 종합 비교'는 분야 메뉴의 여덟째 칸으로 둔다(FR-019).
- [x] MF-07 단일 지표 분야를 세 지표 분야와 같은 폭의 왼쪽 한 칸에 배치한다(MF-05를 대신한다).
