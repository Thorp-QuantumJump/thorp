---

description: "내 기록(목록·다시 보기·설정 바꿔 비교·내보내기·정리)의 실행 작업 목록"
---

# Tasks: 내 기록

**Input**: `specs/006-my-records/`의 spec.md, plan.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: 분기·조인·판정이 있는 로직마다 실패 조건이 드러나는 테스트 하나를 둔다. backend는 기존 `IntegrationTestSupport`·`RepositoryTestSupport`·`Requesters`를 쓰고, frontend는 Vitest DOM 테스트를 쓴다. 검증 명령은 backend `./gradlew spotlessApply build`, frontend `npm run lint && npm run build && npm test`이며 새 FE 테스트 파일은 `vite.config.ts`의 `test.include`에 걸리는지 확인한다.

**Organization**: 작업은 사용자 스토리별로 구성한다. 개별 T번호는 실행 지시이며, 하단의 작업 묶음 하나가 Jira Task·Branch·MR 하나에 대응한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 선행 작업이 끝난 뒤 다른 파일에서 병렬 진행 가능
- **[Story]**: spec.md의 User Story 추적 라벨 (US1 다시 보기 / US2 설정 바꿔 비교 / US3 탐색 묶음·경제환경 / US4 내보내기 / US5 정리)
- 모든 경로는 저장소 루트 기준

## Path Conventions

- backend: `backend/src/main/java/com/ssafy/thorp/myrecord/` (ADR-0001 도메인별 4계층), 마이그레이션 `backend/src/main/resources/db/migration/`
- frontend: `frontend/src/pages/`, `frontend/src/components/records/`, `frontend/src/api/`, `frontend/src/types/`

## Existing Seams

이 기능을 기다리며 이미 저장소에 있는 코드다. 검색어: `006`, `내 기록`, `my_record`, `MyRecord`, `records`, `owner_user_id`, `regime_search_log`.

| Seam (file:symbol) | Current state | What this feature does | Task |
|---|---|---|---|
| `frontend/src/pages/MyRecordsEntryPage.tsx` | 014가 만든 자리 표시 화면. "기록 목록은 spec 006 범위" | 목록 화면으로 **교체** | T021 |
| `frontend/src/App.tsx` `/records/*` + `ProtectedRoute` | 보호 경로가 이미 있고 `MyRecordsEntryPage`를 그린다 | 요소만 새 페이지로 바꾼다. 경로·보호는 그대로 | T021 |
| `backend/.../regime/infrastructure/persistence/RegimeSearchLogJpaEntity.java`, `regime_search_log` 표 | 010이 표·엔티티·읽기 리포지토리(`isWithdrawn`)를 만들고 "행을 만드는 것은 006·014의 몫" | **행을 쓴다.** 006이 쓰기 포트를 두고 기존 JPA 리포지토리로 저장 | T012, T035 |
| `backend/.../regime/application/repository/RegimeSearchLogRepository.java` | 읽기 전용 인터페이스(철회 판정) | 손대지 않는다. 006은 자기 도메인에 `RegimeSearchLogWriter` 포트를 둔다 | T012 |
| `backend/src/main/resources/db/migration/V3__param_search.sql` `owner_user_id` 주석 | "인증 도입 전이라 NULL이다 … 소유자 필터는 006이 붙인다" — 007 접수가 이미 채우고 있어 **옛 문장** | 적용된 마이그레이션은 고칠 수 없으므로 새 마이그레이션에서 `COMMENT ON COLUMN`으로 갱신 | T004 |
| `backend/.../paramsearch/domain/model/ParamSearch.java` `ownedBy(userId)` | 소유자 판정이 이미 있다 | 재사용. 목록 조인에서 `owner_user_id = :owner`로 읽는다 | T017 |
| `frontend/src/pages/SectorSelectionPage.tsx:57,133`, `frontend/src/diagnosis/selectionStorage.ts:14,47,51` | 대응표에서 사라진 섹터(`notInCatalog`)를 알리기만 하고 "무엇을 제안할지는 006 FR-008이 소유" | **제안 문구와 동작을 채운다**(대체 섹터 고르기 안내, 자동 대체 금지) | T031 |
| `backend/.../backtest/presentation/support/CurrentRequester.java` | 세션에서 `users.id`를 읽는 부품. `paramsearch`·`result`가 이미 가져다 쓴다 | 그대로 가져다 쓴다. 옮기지 않는다 | T019 |
| `frontend/src/components/result/prefillStrategySettings.ts` `prefillStrategySettings`·`buildStrategySettings`·`buildSelectionSnapshot` | 005 T068이 만들고 docstring에 "006의 '설정 바꿔 비교'도 이 함수를 쓴다" | **그대로 부른다.** 006은 빌더를 만들지 않는다(research R4) | T029, T030 |
| `frontend/src/components/result/NextStepLinks.tsx:41` '기록으로 내보내기' 비활성 버튼 | "006 화면이 아직 없어 사용할 수 없습니다" | 로그인 사용자에게 이 결과의 기록으로 가는 연결을 켠다 | T045a |
| `frontend/src/pages/RegimeSearchPage.tsx` + `frontend/src/api/regime.ts` `getRegimeSearchById` | 010이 API 클라이언트·서버(`searchById`)는 만들었지만 화면은 URL 파라미터를 읽지 않는다 | `?searchId=`를 받아 저장된 revision을 고정 조회(research R17) | T039a |
| `frontend/src/components/navigation/AppHeader.tsx`, `frontend/src/components/landing/LandingHeader.tsx` '내 기록' 링크 | `/records`로 이미 연결 | 변경 없음 | None |
| `backend/src/test/.../paramsearch/ParamSearchRecordCompletenessTest.java` | "목록·펼침 화면은 006이 소유" 주석 | 변경 없음. 006이 그 값을 읽는다는 사실만 확인 | None |

경로 불일치 없음 — 계획이 만들 파일과 접점이 기대하는 위치가 같다.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 새 라이브러리는 없다. 공통 오류 코드·설정값·보안 경계만 준비한다.

- [x] T001 [P] Add `RECORD_LIMIT_REACHED`(409, "저장할 수 있는 탐색 묶음 개수에 도달했습니다. 기존 항목을 지운 뒤 다시 시작해 주세요.")와 `RECORD_WITHDRAWN`(410, "더 볼 수 없는 검색 결과입니다.") to `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java`
- [x] T002 [P] Add `thorp.my-records.search-bundle-limit: 20` with a comment that the value is a PD-13 임시값 and the 장치 is the deliverable, in `backend/src/main/resources/application.yml`; bind it in `backend/src/main/java/com/ssafy/thorp/myrecord/infrastructure/config/MyRecordProperties.java` (`@ConfigurationProperties("thorp.my-records")`, `@NotNull @Min(1)` so a missing value fails startup like `BacktestLimitProperties`)
- [x] T003 Add `.requestMatchers("/my-records/**").authenticated()` next to the `/auth/me` rule in `backend/src/main/java/com/ssafy/thorp/common/config/SecurityConfig.java` with a comment that 006 endpoints all require login (spec FR-020)

**Checkpoint**: 빌드가 통과하고 `/my-records`가 401을 돌려준다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: `my_record` 표, 도메인 모델, 영속 계층, 접수 이벤트와 등록기. 모든 스토리가 이 위에 선다.

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 사용자 스토리 구현을 병합하지 않는다.

- [x] T004 Create Flyway migration `backend/src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__my_record.sql` per data-model §1: `my_record_seq`, `my_record` table (record_id `^MR-\d{8}-\d{6}$`, owner_id→users, kind CHECK, three nullable FKs `analysis_run_id`→backtest_run·`param_search_id`→param_search·`regime_log_id`→regime_search_log (요청은 가리키지 않는다 — data-model §1), `ck_my_record_ref_matches_kind`, `ck_my_record_period`, TEXT[] sector_codes/strategy_ids, saved_at, last_viewed_at, deleted_at, is_copy), indexes `idx_my_record_list`·`idx_my_record_sectors`(GIN)·`uk_my_record_analysis`·`uk_my_record_search`·`uk_my_record_regime`·`idx_my_record_kind_count`; plus `COMMENT ON COLUMN param_search.owner_user_id IS '접수 요청자(users.id). 007 접수가 채우고 006 내 기록이 소유자 필터에 쓴다. 비로그인 접수는 없다.'` and the same `COMMENT ON INDEX` for the owner index whose V3 comment still says "인증 도입 전에는 owner_user_id가 NULL이라 쓰이지 않는다" — 기존 마이그레이션은 고치지 않는다(constitution 스키마 변경 규칙)
- [x] T005 [P] Create domain model in `backend/src/main/java/com/ssafy/thorp/myrecord/domain/model/`: `MyRecord` (id, `RecordId`, ownerId, `RecordKind`, `RecordRef` sealed interface with `Analysis(runId)`·`SearchBundle(paramSearchId)`·`RegimeSearch(logId)`, `RecordName`, sectorCodes, strategyIds, `AnalysisPeriod`, savedAt, lastViewedAt, deletedAt, isCopy) with `rename(RecordName)`, `delete(Instant)`, `markViewed(Instant)`, `isDeleted()`, `ownedBy(long)`; `RecordId` value object with `MR-` format check; `RecordName` with 1~120자 trim rule. Spring·JPA import 금지
- [x] T006 [P] Create `backend/src/main/java/com/ssafy/thorp/myrecord/domain/model/RecordKind.java` (`ANALYSIS`, `SEARCH_BUNDLE`, `REGIME_SEARCH`) and `RecordStatus.java` (`COMPLETED`, `RUNNING`, `FAILED`, `CANCELLED`, `PARTIAL`, `NOT_COMPUTABLE`, `NEAREST_ONLY`, `THRESHOLD_APPLIED`, `WITHDRAWN`) with a comment that the last four are 010 상태를 그대로 옮긴 것이며 완료/실패로 바꿔 부르지 않는다(spec FR-004)
- [x] T007 Create repository interface `backend/src/main/java/com/ssafy/thorp/myrecord/application/repository/MyRecordRepository.java`: `save`, `findByRecordId(String)`, `findActiveByOwnerAndRun(long owner, long runId)`, `findActiveByOwnerAndParamSearch`, `findActiveByOwnerAndRegimeLog`, `countActiveByOwnerAndKind(long owner, RecordKind)`, `nextRecordId(LocalDate)`, and list query `findPage(MyRecordListQuery)` returning `MyRecordListPage` (rows already joined with status — see T017)
- [x] T008 Create JPA mapping in `backend/src/main/java/com/ssafy/thorp/myrecord/infrastructure/persistence/`: `MyRecordJpaEntity` (extends `BaseEntity`, `@Column(columnDefinition = "text[]")` for arrays via existing array type strategy used elsewhere or `@JdbcTypeCode(SqlTypes.ARRAY)`), `MyRecordMapper` (static `toEntity`/`toDomain`), `MyRecordJpaRepository`, `MyRecordRepositoryImpl` implementing T007 except `findPage` (T017). `record_id` 생성은 `MR-{yyyyMMdd}-{000001}` 일련번호로 `backtest_id`와 같은 방식
- [x] T009 [P] Add repository test `backend/src/test/java/com/ssafy/thorp/myrecord/infrastructure/MyRecordRepositoryIT.java` (extends `RepositoryTestSupport`): save/find round trip for three kinds, `ck_my_record_ref_matches_kind` rejects a row with two refs, `uk_my_record_analysis` rejects a second live non-copy row for the same owner+run but **allows** one when `is_copy = true` and one when the first is deleted
- [x] T010 Add application event `backend/src/main/java/com/ssafy/thorp/backtest/application/event/BacktestRequestAccepted.java` (record: requestId, runId, requesterId nullable, `ExecutionType`, sectorCodes, strategyId, periodStart, periodEnd, acceptedAt) and publish it via `events.publishEvent(...)` right after **both** `requestRepository.save(newRequest(...))` sites (새 실행·합류/재사용) in `backend/src/main/java/com/ssafy/thorp/backtest/application/service/BacktestService.java`. 004의 동작은 바뀌지 않는다
- [x] T011 Add application event `backend/src/main/java/com/ssafy/thorp/paramsearch/application/event/ParamSearchAccepted.java` (record: paramSearchId, ownerUserId, strategyIds, sectorCodes/instrumentIds, periodStart, periodEnd, acceptedAt) and publish it at the end of `create(...)` in `backend/src/main/java/com/ssafy/thorp/paramsearch/application/service/ParamSearchService.java` after the `ParamSearch` is saved **on the accepted path only** — the `rejected(...)` branch (CANDIDATE_LIMIT, saves a `REJECTED` row) does not publish; nothing ran, so there is nothing to reopen (data-model §4)
- [x] T012 Create port `backend/src/main/java/com/ssafy/thorp/myrecord/application/port/RegimeSearchLogWriter.java` (`long append(long ownerId, String snapshotKey, Long snapshotId, EntryPath, BaselineOrigin, Instant viewedAt)`, `void touch(long logId, Instant)`, `Optional<Long> findActive(long ownerId, String snapshotKey, EntryPath)`) and implement it in `backend/src/main/java/com/ssafy/thorp/myrecord/infrastructure/persistence/RegimeSearchLogWriterImpl.java` using the existing `com.ssafy.thorp.regime.infrastructure.persistence.RegimeSearchLogJpaRepository`. 010의 읽기 리포지토리는 손대지 않는다
- [x] T013 Create `backend/src/main/java/com/ssafy/thorp/myrecord/application/service/RecordNamer.java`: `{대표 섹터 이름}{ 외 N}? {전략 이름} {yyyy-MM-dd}` (비중 최대 섹터 우선, 탐색 묶음은 전략 여러 개면 "전략 N개 탐색", 경제환경은 `{분야 이름 또는 종합} {기준월 yyyy-MM}`), reading display names from `sector`/`strategy_rules` via existing repositories. 유일성을 강제하지 않는다(research R12)
- [x] T014 Create `backend/src/main/java/com/ssafy/thorp/myrecord/application/service/MyRecordRegistrar.java`: `@TransactionalEventListener(phase = BEFORE_COMMIT)` on `BacktestRequestAccepted` — skip when `requesterId == null` or `executionType != SINGLE`; find live non-copy row by owner+run, return it if present (FR-009), else create `ANALYSIS` row; listener on `ParamSearchAccepted` — create `SEARCH_BUNDLE` row (defensively skip if the search status is `REJECTED`). Both use `RecordNamer` and `Clock`
- [x] T015 Add service test `backend/src/test/java/com/ssafy/thorp/myrecord/application/MyRecordRegistrarTest.java`: anonymous request → no row; `SEARCH_BUNDLE` execution type → no `ANALYSIS` row; second `SINGLE` request on the same run → same record, row count unchanged; deleted record + same run → new row created
- [x] T016 [P] Add FE types `frontend/src/types/myRecords.ts` from `contracts/my-records-api.md` (`MyRecordKind`, `MyRecordStatus`, `MyRecordListItem` discriminated by `kind`, `MyRecordDetail` with `referenceVersions`·`mappingDrift`·`registryDrift`·`reopenSettings`·`reopenPayload: ResultConfig`(005 `frontend/src/types/result.ts` 재사용), `MyRecordListQuery`, `MyRecordListPage`, `RecordLimitError { current, limit }`) and API client `frontend/src/api/myRecords.ts` (`fetchMyRecords(query)`, `fetchMyRecord(recordId)`, `renameMyRecord`, `deleteMyRecord`, `recordRegimeSearch(searchId, entryPath)`, `exportMyRecordUrl(recordId, format)`; mutations through `runCsrfProtectedMutation`) with `frontend/src/api/myRecords.test.ts`

**Checkpoint**: 로그인 사용자가 단건 분석·탐색을 접수하면 `my_record` 행이 생기고, 같은 조건을 다시 접수해도 행이 늘지 않는다.

---

## Phase 3: User Story 1 - 완료한 분석을 목록에서 찾아 다시 본다 (Priority: P1) 🎯 MVP

**Goal**: `/records`에서 항목을 이름·섹터·전략·분석 기간·저장일로 찾고 '다시 보기'로 저장된 결과를 재계산 없이 연다. 참조 버전은 상세에서 보인다.

**Independent Test**: quickstart S1·S2·S5·S12·S13. 분석 두 건을 완료한 뒤 목록에서 각각을 다시 열어 저장 당시 결과가 재계산 없이 보이고, 자료 갱신 뒤에도 값이 같은지 확인한다.

### Tests for User Story 1

- [x] T017 [US1] Implement `findPage` in `backend/src/main/java/com/ssafy/thorp/myrecord/infrastructure/persistence/MyRecordRepositoryImpl.java` as one native query joining `backtest_request`+`backtest_run`, `param_search`, `regime_search_log`+`regime_search_snapshot` by kind to derive `status`/`statusReason`/`resultToken`/`searchId`/`regime` fields — `ANALYSIS` 의 요청은 저장돼 있지 않으므로 취소하지 않은 최신 요청을 LATERAL 서브쿼리로 고른다(data-model §1 "상태는 컬럼이 아니다"), with filters kind/status/sector(`&&` on TEXT[])/strategy/period overlap, sort `SAVED_AT_DESC|SAVED_AT_ASC|NAME_ASC`, keyset cursor `(saved_at, id)` encoded base64, `size` 1~50. **성과 수치 컬럼을 select에 넣지 않는다**(원칙 III)
- [x] T018 [P] [US1] Add `backend/src/test/java/com/ssafy/thorp/myrecord/infrastructure/MyRecordListQueryIT.java`: default view shows only COMPLETED analysis/bundle rows and THRESHOLD_APPLIED regime rows (contracts `status` 기본값); FAILED/CANCELLED appear only with status filter; RUNNING run maps to `RUNNING`; regime log with NULL snapshot_id maps to `WITHDRAWN`; sector filter matches when any code overlaps; cursor page 2 has no overlap/gap after a new row is inserted between requests; another owner's rows never appear
- [x] T019 [US1] Create `backend/src/main/java/com/ssafy/thorp/myrecord/application/service/MyRecordQueryService.java` (`list(ownerId, query)`, `detail(ownerId, recordId)` → 404 `RESOURCE_NOT_FOUND` for other owners/deleted, `markViewed`) and `backend/src/main/java/com/ssafy/thorp/myrecord/presentation/controller/MyRecordController.java` with `GET /my-records`, `GET /my-records/{recordId}` using `CurrentRequester.requireRequesterId()`; request params validated so unknown `sort|kind|status` → 400 `INVALID_INPUT` (원칙 VI, contracts 공통); responses in `backend/src/main/java/com/ssafy/thorp/myrecord/presentation/response/MyRecordListResponse.java`, `MyRecordDetailResponse.java`
- [x] T020 [US1] Create `backend/src/main/java/com/ssafy/thorp/myrecord/application/service/MyRecordMappingDriftChecker.java`: read the record's `sectorEtfMapVersion` from `backtest_run.versions.mapping` (접수가 확정한 값이라 FAILED 실행에도 있다; `backtest_result.versions`는 워커 보고라 실패 행에 없다) or `param_search.reference_versions`, compare each saved sector's `sector_etf_map_entry.etf_id` in that version with the current latest version; return `changed=false` when every entry matches even if the version string differs, else `INSTRUMENT_REPLACED` or `SECTOR_REMOVED` with sector codes and one Korean sentence (UI-01, research R8). Wire into `detail()` as `mappingDrift`; add `backend/src/test/java/com/ssafy/thorp/myrecord/application/MappingDriftCheckerTest.java` covering the three quickstart S5 cases
- [x] T021 [US1] Replace `frontend/src/pages/MyRecordsEntryPage.tsx` with `frontend/src/pages/MyRecordsPage.tsx` (+ `.module.css`, delete the old page and its CSS, update the import in `frontend/src/App.tsx` and `frontend/src/App.auth.test.tsx`): `AppHeader`, `useAuth().user` greeting, `useMyRecords` hook (`frontend/src/hooks/useMyRecords.ts`: query state, cursor "더 보기", loading/error), empty state "아직 저장된 기록이 없습니다" + '전략 검증하기' 링크 (spec FR-001); read `?resultToken=` from `useSearchParams` and, when present, scroll to/focus the matching row and open its '더 보기' menu (T045a가 이 주소로 보낸다)
- [x] T022 [P] [US1] Create `frontend/src/components/records/RecordFilters.tsx`: kind/status/sector/strategy/period controls and sort select limited to `SAVED_AT_DESC|SAVED_AT_ASC|NAME_ASC` — **성과 정렬 선택지를 두지 않는다**(research R7); sectors/strategies from `fetchSectorCatalog`/`fetchStrategyRegistry` (기존 `frontend/src/api/sectors.ts`, `frontend/src/api/strategyRegistry.ts`); keyboard operable, labelled controls (spec FR-021)
- [x] T023 [P] [US1] Create `frontend/src/components/records/RecordRow.tsx`: name, sector chips, strategy, period, savedAt, status badge with `statusReason` shown **apart from any return figure**, kind icon; **one primary button '다시 보기'** (`Link to /results/{resultToken}?from=records` for ANALYSIS) and '설정 바꿔 비교' as a secondary-styled button (research R7, constitution 원칙 II); '더 보기' menu trigger (`aria-haspopup`) for later phases. `withdrawn` regime rows disable '다시 보기' with reason text
- [x] T024 [US1] Add "동일 버전 조회" one-line notice to `frontend/src/pages/ResultPage.tsx` when `useSearchParams().get('from') === 'records'`, plus `mappingDrift` sentence fetched via `fetchMyRecord` when a `recordId` param is also present; do not change any other 005 behaviour (spec FR-006·FR-008)
- [x] T025 [P] [US1] Create `frontend/src/components/records/RecordDetailPanel.tsx`: expandable "펼쳐 보는 층" with `referenceVersions` (자료·대응표·등록표·비용·규칙·엔진; `costProfileVersion`·`rulesVersion`은 `provisional`이면 '잠정' 표시, `registryVersion`이 없으면 '기록되지 않음'), 저장 당시 실효 `warmupTradingDays`와 평가 기간(FR-007a), `mappingDrift`/`registryDrift` sentences, `lastViewedAt`; **`worker`·`dependencies`는 보이지 않는다**(운영 정보, contracts); opened from the row; Esc closes and focus returns to the row (spec FR-005, 원칙 II 정보 세 층)
- [x] T026 [US1] Add `frontend/src/pages/MyRecordsPage.test.tsx`: renders list from fixture, default status filter is COMPLETED, empty state renders CTA, primary button count per row is exactly one, sort select has no return/drawdown/cost option, unauthenticated render is handled by `ProtectedRoute` (reuse `frontend/src/test/authFixtures.ts`)

**Checkpoint**: US1 단독으로 동작한다. 목록·다시 보기·참조 버전·UI-01 알림이 quickstart S1·S2·S5로 확인된다.

---

## Phase 4: User Story 2 - 설정을 바꿔 새 분석으로 비교한다 (Priority: P1)

**Goal**: 기록 상세의 '설정 바꿔 비교'가 저장된 조건을 002·003 탭 세션 계약에 채우고 `/strategy`를 연다. 허용 범위 밖 값은 003의 기존 판정이 비운다. 새 분석은 새 항목이 되고 원본은 바뀌지 않는다.

**Independent Test**: quickstart S3·S4·S6. 리밸런싱 주기만 바꿔 분석한 뒤 원본과 새 항목이 별도로 남고 원본 결과가 그대로인지, 등록표 범위 밖 값이 비워지고 복원 제안이 보이는지 확인한다.

### Implementation for User Story 2

- [x] T027 [US2] Add `registryDrift` to `MyRecordQueryService.detail()` in `backend/src/main/java/com/ssafy/thorp/myrecord/application/service/MyRecordQueryService.java`: compare the saved `config` axis values against the **current** `strategy_rules` registry (reuse the 003 registry read used by `StrategyRegistryController`), return `outOfRange` and `fields[{axis, savedValue, currentRange}]`; add `reopenSettings.available = kind == ANALYSIS && config.scenario == FULL_PERIOD` (경제환경 기록과 003이 다루지 않는 시나리오는 `false`, contracts); expose `reopenPayload` in `MyRecordDetailResponse` **in exactly the shape of 005's `PublicResultDetail.config`** (`request_spec` + `currency`·`effectiveStart`·`effectiveEnd` — `BacktestDetailResult.config(run, currency)`는 `private static`이므로 복사하지 말고 접근 범위를 넓혀(package-private 정적 헬퍼 또는 `backtest/application/result`의 공용 조립기로 추출) 006이 부른다 — 두 벌이 되면 005·006 화면이 다른 조건을 보인다) so the FE can hand it to `prefillStrategySettings` unchanged (research R4, contracts/record-handoff.md); `referenceVersions`는 `backtest_run.versions`(`data`·`mapping`·`costProfile.version`·`rules.version`·`engine`)와 `request_spec.registryVersion`에서 읽고 `worker`·`dependencies`는 담지 않는다(contracts "referenceVersions의 출처")
- [x] T028 [P] [US2] Add `backend/src/test/java/com/ssafy/thorp/myrecord/application/RegistryDriftTest.java`: unchanged registry → `outOfRange=false`; narrowed range → the axis appears in `fields` with saved value untouched; regime record → `reopenSettings.available=false`
- [x] T029 [US2] **Do not create a 006 builder.** Type `MyRecordDetail.reopenPayload` as 005's `ResultConfig` (`frontend/src/types/result.ts`) in `frontend/src/types/myRecords.ts`, and add one thin adapter `frontend/src/components/records/reopenSettings.ts` exporting `reopenSettings(detail, navigate, signal)` that calls 005's `prefillStrategySettings(detail.reopenPayload, navigate, signal)` (`frontend/src/components/result/prefillStrategySettings.ts`) and returns its `PrefillOutcome`; update that file's docstring line "006의 '설정 바꿔 비교'도 이 함수를 쓴다" to name this adapter. Unit test `frontend/src/components/records/reopenSettings.test.ts`: payload is passed through unchanged (no clamping, `warmupTradingDays` untouched), `reselectSectors` outcome is surfaced when `mappingVersion` differs from the catalog (research R4, contracts/record-handoff.md)
- [x] T030 [US2] Wire '설정 바꿔 비교' in `frontend/src/components/records/RecordRow.tsx` and `RecordDetailPanel.tsx`: fetch detail if needed, call `reopenSettings(detail, navigate, signal)` (T029) with an `AbortController` tied to unmount; handle `PrefillOutcome` per contracts/record-handoff.md — `moved` nothing, `reselectSectors` show `message` in `role="status"` plus a link to `/diagnosis/sectors`, `failed` show `message`, `canceled` nothing; hide the button when `reopenSettings.available === false` (model on `frontend/src/components/result/OtherConditionsButton.tsx`)
- [x] T031 [US2] Fill the 006-owned proposal in `frontend/src/pages/SectorSelectionPage.tsx` (the `missingCount > 0` branch near line 57/133): keep the existing first sentence ("이전에 골랐던 섹터 N개가 지금 목록에 없어 선택에서 빠졌습니다." — the screen does not know whether the user came from a record, so "저장 당시"/"대응표" would be false for a plain session restore) and append the 006 proposal "아래 목록에서 대체 섹터를 직접 골라 주세요."; **do not auto-select a replacement**; keep the `unusableCount` sentence as is. Update the "006 FR-008이 소유" comments there and in `frontend/src/diagnosis/selectionStorage.ts` to point at this implementation. Add a case to `frontend/src/pages/SectorSelectionPage.test.tsx`
- [x] T032 [US2] Add `backend/src/test/java/com/ssafy/thorp/myrecord/MyRecordReopenIT.java` (extends `IntegrationTestSupport`): create record A, submit a second `SINGLE` request with one axis changed → new `my_record` row; submit identical conditions → no new row (FR-009); record A's `backtest_result` row unchanged (SC-003)

**Checkpoint**: US1+US2. 설정 바꿔 비교 → 새 항목, 동일 조건 → 기존 항목, 범위 밖 값 → 003이 비움.

---

## Phase 5: User Story 3 - 자동 탐색 묶음과 경제환경 탐색 기록을 함께 본다 (Priority: P2)

**Goal**: 탐색 묶음이 한 항목으로 보이고 펼치면 후보별 결과·상태·제외 사유·탐색 공간이 보인다. 경제환경 검색은 로그인 시 `regime_search_log`에 남고 진입 경로별로 별도 항목이 된다. 탐색 묶음 저장 상한은 접수 전에 판정한다.

**Independent Test**: quickstart S7·S10. 자동 탐색 한 번과 경제환경 검색 두 경로를 수행해 목록에서 묶음이 펼쳐지고 경제환경 항목이 둘로 남는지, 상한을 2로 낮춰 세 번째 탐색이 접수 전에 거절되는지 확인한다.

### Implementation for User Story 3

- [x] T033 [US3] Create `backend/src/main/java/com/ssafy/thorp/myrecord/application/service/MyRecordLimitPolicy.java`: `requireSaveSlot(long ownerId)`(007이 소유한 포트 `paramsearch.application.port.SaveSlotGuard`의 구현 — 직접 호출은 `paramsearch ↔ myrecord` 양방향이라 리뷰에서 뒤집었다. `ArchitectureTest` 규칙 11이 방향을 지킨다) counting live `SEARCH_BUNDLE` rows against the limit carried by `MyRecordLimits`(application/port — `MyRecordProperties`는 infrastructure라 application이 직접 참조할 수 없다). 사본(`is_copy`)도 센다 — 목록에서 한 줄을 차지하므로 저장 자리를 먹는다; throws `BusinessException(RECORD_LIMIT_REACHED)` carrying `{ current, limit }` for the error body (extend `ErrorResponse` details the same way 429 carries `retryAfterSeconds`). Call it at the **start** of `ParamSearchService.create(...)` in `backend/src/main/java/com/ssafy/thorp/paramsearch/application/service/ParamSearchService.java` before any job is launched (research R11)
- [x] T034 [P] [US3] Add `backend/src/test/java/com/ssafy/thorp/myrecord/MyRecordLimitIT.java` (model on `paramsearch/ParamSearchLimitReachedTest.java`): with limit 2, third `POST /param-searches` → 409 `RECORD_LIMIT_REACHED` with `current=2, limit=2`, no `param_search` row created, no message published, existing rows intact; deleting one record lets the next intake pass
- [x] T035 [US3] Add `POST /my-records/regime-searches` to `MyRecordController.java` + `backend/src/main/java/com/ssafy/thorp/myrecord/presentation/request/RecordRegimeSearchRequest.java` (`searchId`, `entryPath` validated `MARKET_FLOW|STRATEGY_RESULT`, `baselineOrigin` validated `T_G|T_ALL|USER_SPECIFIED` — **화면이 보낸다**; `SnapshotKey`에 근거가 없고 서버는 요청에서만 판정하므로 추정하지 않는다, research R15) and `MyRecordRegistrar.recordRegimeSearch(ownerId, searchId, entryPath, baselineOrigin)`: resolve snapshot via the regime read path (404 if unknown), `RegimeSearchLogWriter.append`(upsert — 있으면 열람 시각만 올리고 같은 id를 돌려준다. 찾아보고 나누면 동시에 두 번 열 때 양쪽이 "없음"을 읽는 틈이 생겨 리뷰에서 `findActive`·`touch`를 지우고 한 문장으로 줄였다) + `REGIME_SEARCH` row in one transaction; 200과 201은 **항목**이 이미 있는지로 가른다 (contracts, research R15). 010의 `GET /regime-searches`는 손대지 않는다
- [x] T036 [P] [US3] Add `backend/src/test/java/com/ssafy/thorp/myrecord/RegimeRecordIT.java`: logged-in POST creates one `regime_search_log` row + one `my_record` with the posted `baseline_origin`; missing `baselineOrigin` → 400; same searchId with the other `entryPath` creates a second pair; repeat POST returns 200 and only bumps `viewed_at`; anonymous → 401 and no rows (010 FR-032)
- [x] T037 [US3] In `frontend/src/pages/RegimeSearchPage.tsx`, after a search result renders and `useAuth().user` is present, call `recordRegimeSearch(result.searchId, entryPath, result.baselineOrigin)` once per `searchId` where `entryPath = location.state?.entryPath ?? 'MARKET_FLOW'` (이 화면은 시장 흐름 탐색이 기본 경로다; 005의 결과 진입 버튼이 생기면 `state.entryPath = 'STRATEGY_RESULT'`를 넘긴다 — 그 버튼은 005 소유). Failure of the record call must not block the search view; log nothing to the user beyond a non-blocking status line
- [x] T038 [P] [US3] Create `frontend/src/components/records/SearchBundlePanel.tsx`: for `SEARCH_BUNDLE` rows, expand to show `searchSpace`, counts (예상·유효·완료·실패·제외·미실행), selection rule, and the candidate table from existing `getParamSearch(searchId)`/`getParamSearchCandidates` in `frontend/src/api/paramSearch.ts` with per-candidate status (완료/실패/제외/취소/미실행) and 제외 사유; `PARTIAL` shows "부분 완료 — N/M 후보" from `counts`; 후보를 성과순으로 재정렬하는 컨트롤을 두지 않는다(원칙 III). '다시 보기' opens the existing `/param-search` route with the `searchId`
- [x] T039 [P] [US3] Create `frontend/src/components/records/RegimeRecordPanel.tsx`: mode/field/baselineMonth/baselineOrigin/entryPath, `withdrawn` notice, '다시 보기' → `/regime-search?searchId={regime.snapshotKey}` (T039a가 수신한다), and the fixed sentence "아직 연결 정보가 없습니다 — 전략 연결은 이후 기능에서 제공됩니다" whenever the detail has no `regime.link` (011의 `regime_link_search`·`regime_link_filter`가 아직 없어 키가 없는 상태; spec FR-012, research R9); never renders placeholder values
- [x] T039a [US3] In `frontend/src/pages/RegimeSearchPage.tsx` read `useSearchParams().get('searchId')`; when present, load with existing `getRegimeSearchById(searchId, limit, signal)` (`frontend/src/api/regime.ts`) instead of the form query, show a one-line "저장된 검색을 다시 봅니다(동일 버전 조회)" notice, and keep the form usable for a new search (which clears the param). Extend `useRegimeSearch` with an optional `searchId` branch rather than a second hook. Test in `frontend/src/pages/RegimeSearchPage.test.tsx`: with `?searchId=` the by-id API is called and the form query is not. **010 소유 파일**이므로 010 담당에게 알린다(research R17; 007 `ParamSearchPage`의 `?searchId=` 재열기와 같은 모양)
- [x] T040 [US3] Extend `frontend/src/pages/MyRecordsPage.test.tsx`: bundle row expands with candidate statuses and partial banner; regime rows with different `entryPath` render as two items; withdrawn regime row has disabled '다시 보기'; 409 `RECORD_LIMIT_REACHED` from intake renders "저장할 수 있는 탐색 묶음 N/M" in `frontend/src/pages/ParamSearchPage.tsx` before submit (add the error branch there)

**Checkpoint**: US1~US3. 세 종류가 한 목록에 있고 상한 장치가 접수 전에 동작한다.

---

## Phase 6: User Story 4 - 기록을 내보낸다 (Priority: P2)

**Goal**: CSV/JSON 내보내기. 설정·참조 버전·결과·시계열·거래·예외 처리 기록을 담고, 원천 일별 시세 원값·기사 본문은 스키마에 자리가 없으며, `exclusions`가 반드시 있다.

**Independent Test**: quickstart S8. 한 분석을 두 형식으로 받아 구획이 있는지, `exclusions` 키가 있는지, 금지 필드가 키째로 없는지, CSV가 한국어 Windows 표 도구에서 깨지지 않는지 확인한다.

### Implementation for User Story 4

- [x] T041 [US4] Create `backend/src/main/java/com/ssafy/thorp/myrecord/application/result/MyRecordExport.java` (record with `record`, `config`, `referenceVersions`, `summary`, `timeseries`, `trades`, optional `fills`, `exceptions`, optional `searchSpace`/`candidates`, optional `regime`, **required** `exclusions: List<Exclusion{item, reason, message}>`) — no field exists for daily raw prices or article bodies (원칙 X; 012 `NewsArticleExportResponse` 방식) — and `backend/src/main/java/com/ssafy/thorp/myrecord/application/service/MyRecordExportService.java` assembling it from `BacktestResultQueryService`/param search/regime read paths; `fills`는 `backtest_result.fills_recorded`가 `true`일 때만 채우고 `false`면 **키를 만들지 않는다**(원칙 VI); `referenceVersions`에 `worker`·`dependencies`를 담지 않는다; `RUNNING` → 409 `RESULT_NOT_READY`; FAILED/CANCELLED → `config`·`referenceVersions`·failure reason·`exclusions` only (spec FR-015); always appends `exclusions` entries `dailyPriceRaw`·`newsArticleBody` with `SOURCE_REDISTRIBUTION_FORBIDDEN`
- [x] T042 [P] [US4] Create `backend/src/main/java/com/ssafy/thorp/myrecord/application/service/CsvWriter.java`: minimal writer (quote when value contains `,` `"` `\r` `\n`, double inner quotes), CRLF line ends, UTF-8 BOM prefix, `# {section}` heading line before each table; unit test `backend/src/test/java/com/ssafy/thorp/myrecord/application/CsvWriterTest.java` for the three escape cases and BOM presence. 새 라이브러리를 추가하지 않는다
- [x] T043 [US4] Add `GET /my-records/{recordId}/export?format=json|csv` to `MyRecordController.java` returning `ResponseEntity<byte[]>` with `Content-Type` `application/json` / `text/csv; charset=UTF-8`, `Content-Disposition: attachment; filename*=UTF-8''{name}.{ext}`; missing/invalid `format` → 400 `INVALID_INPUT` in the normal envelope
- [x] T044 [P] [US4] Add `backend/src/test/java/com/ssafy/thorp/myrecord/MyRecordExportIT.java`: JSON has all sections and `exclusions` with ≥2 entries; serialized JSON contains no key `dailyPriceRaw`/`articleBody`/`rawPrices`/`worker`/`dependencies`; `fills_recorded=true` 결과는 `fills` 구획을 갖고 `false` 결과는 그 키가 아예 없다; CSV starts with BOM, uses CRLF, contains `# exclusions` table; bundle export contains `searchSpace`·`candidates`; `config.parameters`·`config.scenario`(+`scenarioParams`)·`regime` are separate keys (spec FR-013 별도 축); failed/cancelled record export contains only the allowed sections; other owner → 404
- [x] T045 [US4] Create `frontend/src/components/records/ExportMenu.tsx` inside the row's '더 보기' menu: two items "CSV로 내보내기"/"JSON으로 내보내기" that open `exportMyRecordUrl(recordId, format)` as a download (anchor with `download`), disabled with reason for `RUNNING`; keyboard operable; announce start/failure in a `role="status"` region (spec FR-021). Add a test case to `MyRecordsPage.test.tsx`

- [x] T045a [US4] In `frontend/src/components/result/NextStepLinks.tsx` replace the disabled '기록으로 내보내기' button and its "006 화면이 아직 없어" reason: when `useAuth().user` is present, render a link to `/records?resultToken={publicToken}` (MyRecordsPage focuses the matching row and opens its '더 보기' menu); when anonymous, keep it disabled with the 014 login guidance sentence. Add `frontend/src/components/result/NextStepLinks.test.tsx` (없음 — 새로 만든다; `src/**/*.test.tsx`는 vite `test.include`에 이미 걸린다) for both branches. 011 button은 그대로 둔다

**Checkpoint**: US4. 두 형식의 파일이 계약대로 나오고 금지 필드가 스키마에 없다.

---

## Phase 7: User Story 5 - 기록을 정리한다 (Priority: P3)

**Goal**: 이름 변경과 개인 항목 삭제. 공용 계산 결과는 남고, 다른 사용자의 같은 결과는 그대로 열린다.

**Independent Test**: quickstart S9. 이름을 바꾼 뒤 결과·참조 버전이 그대로인지, 삭제 후 `backtest_result`·`backtest_trade`가 남는지, 다른 사용자의 같은 run 항목이 열리는지(SC-007) 확인한다.

### Implementation for User Story 5

- [x] T046 [US5] Create `backend/src/main/java/com/ssafy/thorp/myrecord/application/service/MyRecordCommandService.java` (`rename(ownerId, recordId, name)` → `RecordName` validation, `delete(ownerId, recordId)` → `deleted_at = now`, both 404 for other owners/deleted) and add `PATCH /my-records/{recordId}` (`backend/src/main/java/com/ssafy/thorp/myrecord/presentation/request/RenameMyRecordRequest.java`, reject unknown keys with 400) and `DELETE /my-records/{recordId}` (200 `{recordId, deletedAt}`) to `MyRecordController.java`. **삭제는 `my_record` 행만 건드린다** — 다른 표에 DELETE를 보내지 않는다(research R6)
- [x] T047 [P] [US5] Add `backend/src/test/java/com/ssafy/thorp/myrecord/MyRecordCommandIT.java`: rename changes only `name` (`backtest_result` row hash unchanged); delete sets `deleted_at`, list no longer returns it, `GET` returns 404, second DELETE → 404; **another user's record on the same run still resolves and its `resultToken` still opens** (SC-007); blank name → 400
- [x] T048 [US5] Create `frontend/src/components/records/RenameDialog.tsx` and `DeleteConfirmDialog.tsx` (native `<dialog>`; confirm text names the record and states "저장된 계산 결과는 남고 내 목록에서만 사라집니다"; no undo offered), wired from the row's '더 보기' menu; on success update the list in place, announce in `role="status"`; focus returns to the menu trigger (spec FR-017·FR-021)
- [x] T049 [US5] Extend `frontend/src/pages/MyRecordsPage.test.tsx`: rename updates the row name only; delete requires confirmation, removes the row, and no "복구" control exists; unknown-key/blank rename errors surface as field errors

**Checkpoint**: 다섯 스토리가 모두 독립적으로 동작한다.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 검증 명령 통과, 문서 정합, 자동 삭제 부재 확인.

- [ ] T050 Run `./gradlew spotlessApply build` in `backend/` and `npm run lint && npm run build && npm test` in `frontend/`; confirm `ArchitectureTest` passes for `myrecord` and that `npm test` reports the new test files as collected (not skipped) — add `src/components/records/*.test.ts` to `test.include` in `frontend/vite.config.ts` if needed
- [ ] T051 [P] Run quickstart S11: `grep -rn "my_record" --include=*.java backend/src/main/java | grep -i "schedul\|expire\|cleanup\|purge"` must return nothing; confirm `saved_at`/`last_viewed_at` are populated and `last_viewed_at` updates on '다시 보기' (T019 `markViewed`)
- [ ] T052 [P] Update `backend/CLAUDE.md` 규칙 절 with one line: "내 기록(`myrecord`)은 계산 결과를 복제하지 않고 `backtest_request`·`param_search`·`regime_search_log`를 가리킨다. 삭제는 `my_record.deleted_at`만 바꾼다." and `frontend/CLAUDE.md` 폴더 규칙에 `components/records/`를 추가
- [ ] T053 Run quickstart S1~S13 end to end against a local stack and record outcomes in `specs/006-my-records/verification/quickstart-2026-MM-DD.md` (pass/fail per scenario, exact commands used, "확인하지 않는 것" left as documented). S14 사용성 관찰은 별도 항목이며 여기서 통과로 쓰지 않는다

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 선행 없음. T001·T002 병렬, T003은 독립
- **Foundational (Phase 2)**: Phase 1 뒤. T004(마이그레이션) → T008(JPA) → T009(IT); T005·T006 병렬 → T007 → T008; T010·T011 독립; T012는 T008과 병렬; T013 → T014 → T015; T016은 계약만 보므로 처음부터 병렬 가능
- **US1 (Phase 3)**: Phase 2 뒤. T017 → T018·T019; T020은 T019와 병렬 개발 후 합류; FE T021 → T022·T023·T025 병렬 → T024·T026
- **US2 (Phase 4)**: US1의 T019·T021·T023 뒤. T027 → T028; T029 → T030; T031은 독립(002 화면); T032는 T014 뒤 언제든
- **US3 (Phase 5)**: Phase 2 뒤 시작 가능(US1과 병렬). T033 → T034; T035 → T036 → T037; T038·T039는 T023 뒤; T039a는 독립(010 화면); T040 마지막
- **US4 (Phase 6)**: US1의 T019 뒤. T041·T042 병렬 → T043 → T044; T045는 T023 뒤; T045a는 T021 뒤
- **US5 (Phase 7)**: US1의 T019·T023 뒤. T046 → T047; T048 → T049
- **Polish (Phase 8)**: 선택한 스토리가 모두 끝난 뒤

### User Story Dependencies

- **US1**: Foundational만 필요. MVP
- **US2**: US1의 상세 API와 행 컴포넌트를 확장한다. 독립 검증 가능(S3·S4·S6)
- **US3**: Foundational만 필요하지만 FE 패널은 US1의 행 위에 얹는다. 상한 장치(T033·T034)는 US1 없이도 검증 가능(S10)
- **US4**: US1의 상세 서비스가 있어야 한다. 독립 검증 가능(S8)
- **US5**: US1의 행 메뉴 위에 얹는다. 독립 검증 가능(S9)

### Parallel Opportunities

- Phase 2에서 BE(T004~T015)와 FE 타입/클라이언트(T016)는 완전히 병렬
- Phase 3에서 BE 목록 질의(T017~T020)와 FE 화면(T021~T026)은 계약(contracts/my-records-api.md)만 공유하므로 병렬. FE는 fixture로 먼저 만든다
- US3의 상한 장치(T033·T034)는 다른 모든 스토리와 독립
- US4·US5는 서로 독립

---

## Parallel Execution Examples

### Foundational

```text
Task: "T005 domain model in backend/.../myrecord/domain/model/"
Task: "T006 RecordKind/RecordStatus"
Task: "T010 BacktestRequestAccepted event in backtest/"
Task: "T011 ParamSearchAccepted event in paramsearch/"
Task: "T016 FE types + api client in frontend/src/types/myRecords.ts, frontend/src/api/myRecords.ts"
```

### User Story 1

```text
# BE
Task: "T017 findPage native query"        → Task: "T018 MyRecordListQueryIT" / "T019 QueryService + Controller"
Task: "T020 MappingDriftChecker + test"
# FE (fixture 기반, 병렬)
Task: "T022 RecordFilters" | Task: "T023 RecordRow" | Task: "T025 RecordDetailPanel"
```

### User Stories 3~5 (US1 뒤, 서로 병렬)

```text
Task: "T033 MyRecordLimitPolicy + intake hook"   Task: "T041 MyRecordExport + service"   Task: "T046 CommandService + PATCH/DELETE"
Task: "T035 POST /my-records/regime-searches"    Task: "T042 CsvWriter"                  Task: "T048 Rename/Delete dialogs"
```

---

## Recommended Work Batches and Jira Tasks

개별 T번호가 아니라 아래 묶음 하나를 Jira Task·Branch·MR 하나로 사용한다. 실제 Jira 번호를 받은 뒤 constitution의 `{type}({position})/{JIRA_NUMBER}-{description}` 형식으로 브랜치를 만든다. 스키마 변경(T004)과 그 매핑·검증(T008·T009)은 같은 MR에 있어야 한다(constitution 스키마 변경 규칙).

| Work Batch | Recommended Jira Task | Included Tasks | Depends On |
|---|---|---|---|
| WB-BE-1 | `[BE] 내 기록 저장 구조와 접수 연동` | T001–T015 | 없음 |
| WB-BE-2 | `[BE] 내 기록 목록·상세 API와 대응표 변경 판정` | T017–T020 | WB-BE-1 |
| WB-FE-1 | `[FE] 내 기록 목록 화면과 다시 보기` | T016, T021–T026 | WB-BE-2 계약(fixture로 선행 가능) |
| WB-BE-3 | `[BE] 설정 바꿔 비교 판정과 동일 조건 재사용 검증` | T027–T028, T032 | WB-BE-2 |
| WB-FE-2 | `[FE] 설정 바꿔 비교 인계와 대체 섹터 제안` | T029–T031 | WB-FE-1, WB-BE-3 계약 |
| WB-BE-4 | `[BE] 탐색 묶음 저장 상한과 경제환경 기록 API` | T033–T036 | WB-BE-1 |
| WB-FE-3 | `[FE] 탐색 묶음·경제환경 기록 표시` | T037–T040 (T039a 포함) | WB-FE-1, WB-BE-4 계약 |
| WB-BE-5 | `[BE] 내 기록 CSV/JSON 내보내기` | T041–T044 | WB-BE-2 |
| WB-FE-4 | `[FE] 내 기록 내보내기·이름 변경·삭제` | T045, T045a, T048–T049 | WB-FE-1, WB-BE-5·WB-BE-6 계약 |
| WB-BE-6 | `[BE] 내 기록 이름 변경·삭제` | T046–T047 | WB-BE-2 |
| WB-DOCS-1 | `[DOCS] 내 기록 통합 검증과 파트 문서 갱신` | T050–T053 | 선택한 구현 묶음 완료 |

**전달만 하는 것(티켓 아님)**: 007 담당에게 `ParamSearchService.create` 앞의 상한 판정 호출(T033)과 접수 이벤트(T011)가 들어간다는 사실, 002 담당에게 "UI-01이 소유한다" 문장이 "006 FR-008이 정했다"로 바뀌어야 한다는 사실(T031이 코드 주석은 고친다). 005 담당에게는 결과 화면의 경제환경 진입 버튼이 생기면 `state.entryPath = 'STRATEGY_RESULT'`를 넘겨야 한다는 사실, 그리고 `prefillStrategySettings`를 006이 부르므로 시그니처를 바꾸면 006이 깨진다는 사실(T029). 010 담당에게 `RegimeSearchPage`에 `?searchId=` 수신이 들어간다는 사실(T039a). 005·011 담당에게 내 기록을 선택 목록으로 쓰게 되면 `my_record.deleted_at IS NULL`을 따라야 한다는 사실(spec FR-017).

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 → Phase 2 (WB-BE-1) — 접수하면 항목이 생기는 것까지
2. Phase 3 (WB-BE-2 + WB-FE-1) — 목록·다시 보기·참조 버전·UI-01
3. **멈추고 quickstart S1·S2·S5·S12·S13으로 검증**
4. 여기까지가 spec의 P1 절반이다. US2까지 해야 P1이 끝난다

### Incremental Delivery

1. US1 → US2 (P1 완성) → 검증 S3·S4·S6
2. US3 상한 장치(T033·T034)를 먼저 — 운영 위험을 줄이는 작은 묶음
3. US3 나머지 → US4 → US5 순. 각 스토리는 이전 스토리를 깨지 않는다
4. Phase 8로 마무리. S14 사용성 관찰은 구현 완료와 별개 항목으로 남긴다

### Team Execution

- BE 한 명: WB-BE-1 → WB-BE-2 → (WB-BE-4 | WB-BE-3) → WB-BE-5 → WB-BE-6
- FE 한 명: WB-FE-1(fixture) → WB-FE-2 → WB-FE-3 → WB-FE-4
- 계약은 contracts/my-records-api.md 하나라 FE는 BE 완료를 기다리지 않는다

---

## Notes

- [P] = 다른 파일·선행 완료. 같은 파일을 두 사람이 동시에 고치지 않는다
- 상태는 `my_record`에 저장하지 않는다. 컬럼을 추가하고 싶어지면 research R2를 먼저 읽는다
- 목록 API 응답과 화면 어디에도 수익률·하락 폭·비용을 두지 않는다(원칙 III). 넣고 싶어지면 research R7
- 삭제는 `my_record.deleted_at` 한 컬럼이다. 다른 표에 DELETE가 생기면 2026-09-18 명료화 위반이다
- 자동 삭제 배치를 만들지 않는다(PD-13). T051이 확인한다
- 완료 표시는 기능 구현, 테스트 또는 동작 확인, 코드 리뷰, MR 병합, 필요한 문서 수정이 모두 끝난 뒤에만 한다

---

## Phase 9: Convergence

- [ ] T054 [US1] Add a '거래 내역' item to the row's '더 보기' menu in `frontend/src/components/records/RecordActionsMenu.tsx` for `ANALYSIS` records that links to the saved result's trade section (`reviewHref(item)` + the 005 trade-history anchor; disabled with reason for `RUNNING`), so 거래 내역 is reachable from the 추가 메뉴 without first opening '다시 보기'; add one case to `frontend/src/pages/MyRecordsPage.test.tsx` per FR-002 (partial)
