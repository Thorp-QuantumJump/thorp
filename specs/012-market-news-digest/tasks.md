# Tasks: 시장 흐름 뉴스 묶음

**Input**: `specs/012-market-news-digest/`의 spec.md, plan.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: spec의 독립 검증 기준과 quickstart에 따라 테스트를 각 스토리 구현 전에 작성한다.

**Organization**: 구현 범위는 `backend/`, `worker/`, `data/`, `docs/`로 제한한다. 이번 설계에서 실제 코드 변경은 `backend/`와 `docs/`만 필요하다. `frontend/`는 생성·수정하지 않으며 화면 요구는 `specs/012-market-news-digest/contracts/`로 전달한다. T001~T053은 실행 지시 단위이고, 1인 당일 개발을 위한 실제 Jira Task·브랜치·MR은 아래 `Work Batches` 4개를 추적 단위로 사용한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 선행 Task가 완료된 뒤 다른 파일에서 독립적으로 진행 가능
- **[Story]**: spec의 User Story 번호
- 각 실행 Task ID는 소속 작업 묶음의 MR 본문 `주요 변경 사항`에 기록한다. Jira 제목과 브랜치 형식은 개별 Task가 아니라 작업 묶음에 적용한다.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: news bounded context와 설정·문서 기준을 만든다.

- [X] T001 Create the `news` bounded-context package boundaries and architecture documentation in `backend/src/main/java/com/ssafy/thorp/news/domain/package-info.java`, `backend/src/main/java/com/ssafy/thorp/news/application/package-info.java`, `backend/src/main/java/com/ssafy/thorp/news/infrastructure/package-info.java`, and `backend/src/main/java/com/ssafy/thorp/news/presentation/package-info.java`
- [X] T002 [P] Add typed server-only collection, exposure-mode, stale-threshold, source-limit, fixed-retention-date, and HTTP security-bound (HTTPS-only, host allowlist, max 3 redirects, 5s connect / 20s total, 10MiB body, allowed content-types) configuration plus versioned publisher/source registries carrying each publisher's registered domain and sector/topic registries that map source category values to topic keys without declaring sector IDs in `backend/src/main/java/com/ssafy/thorp/news/infrastructure/config/NewsProperties.java`, `NewsConfig.java`, `NewsPublisherRegistry.java`, `NewsLinkRegistry.java`, `backend/src/main/resources/news/news-publishers-v1.json`, `news-link-v1.json`, and `backend/src/test/java/com/ssafy/thorp/news/infrastructure/config/NewsConfigTest.java`
- [X] T003 [P] Document the external `/api/market-news/digests` and `/api/market-news/articles/export` contracts, state semantics, source restrictions, sector ID ownership by spec 001, the three independent period boundaries, secret-redaction rules, and opt-in live validation policy in `docs/specs/market-news-api.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 사용자 스토리가 공유하는 스키마, 도메인 불변식, port, persistence 기반을 만든다.

**⚠️ CRITICAL**: 이 단계가 끝나기 전에 사용자 스토리 구현을 시작하지 않는다.

- [X] T004 Add PostgreSQL tables, constraints, indexes, seed rule versions, publisher current permission state and registered domain, append-only permission history, collection state including the `INCOMPLETE` status, and a collection lease schema shared by ingestion sources and the `retention-cleanup` job in `backend/src/main/resources/db/migration/V4__market_news_digest.sql`, then extend schema expectations in `backend/src/test/java/com/ssafy/thorp/common/persistence/FlywayMigrationTest.java`
- [X] T005 Define pure Java enums and value objects for source type, permission event/status/allowed fields, publication-time certainty, collection status including `INCOMPLETE`, digest state, exposure mode, deterministically derived stable IDs, and normalized URLs in `backend/src/main/java/com/ssafy/thorp/news/domain/vo/NewsSourceType.java`, `PermissionEventType.java`, `PermissionStatus.java`, `NewsAllowedField.java`, `PublicationTimeStatus.java`, `NewsDigestState.java`, `NewsExposureMode.java`, `NewsArticleId.java`, `NewsDigestId.java`, and `CanonicalNewsUrl.java`
- [X] T006 Define article, digest, publisher, append-only permission-history, collection-state, and rule-version domain models with body-free invariants, deterministic external ID derivation from business keys, and permission-aware digest projection helpers in `backend/src/main/java/com/ssafy/thorp/news/domain/model/NewsArticle.java`, `NewsDigest.java`, `NewsPublisher.java`, `NewsPublisherPermissionHistory.java`, `NewsCollectionState.java`, and `NewsRuleVersion.java`, with invariant tests in `backend/src/test/java/com/ssafy/thorp/news/domain/model/NewsDomainModelTest.java`
- [X] T007 [P] Define source records and application ports without Spring/JPA/Jackson dependencies in `backend/src/main/java/com/ssafy/thorp/news/application/port/NewsSourceClient.java`, `RssNewsSource.java`, `GdeltNewsSource.java`, `NewsCollectionLease.java`, and `backend/src/main/java/com/ssafy/thorp/news/application/result/CollectedNewsItem.java`
- [X] T008 [P] Define repository interfaces for publishers, permission history, articles, digests, rule versions, and collection state in `backend/src/main/java/com/ssafy/thorp/news/application/repository/NewsPublisherRepository.java`, `NewsPublisherPermissionHistoryRepository.java`, `NewsArticleRepository.java`, `NewsDigestRepository.java`, `NewsRuleVersionRepository.java`, and `NewsCollectionStateRepository.java`
- [X] T009 Implement JPA entities and mappers matching V3 in `backend/src/main/java/com/ssafy/thorp/news/infrastructure/persistence/NewsPublisherJpaEntity.java`, `NewsPublisherPermissionHistoryJpaEntity.java`, `NewsArticleJpaEntity.java`, `NewsDigestJpaEntity.java`, `NewsDigestArticleJpaEntity.java`, `NewsArticleSectorJpaEntity.java`, `NewsArticleTopicJpaEntity.java`, `NewsRuleVersionJpaEntity.java`, `NewsCollectionStateJpaEntity.java`, and `NewsPersistenceMapper.java`
- [X] T010 Implement Spring Data repositories and application repository adapters, including canonical URL upsert, ID-collision rejection without overwrite, permission-history append/current-state update, permission-first projections that recompute representative, summary and publisher count from permitted articles only, period/sector queries, transactional cursor updates, and conditional lease claims for sources and the retention job in `backend/src/main/java/com/ssafy/thorp/news/infrastructure/persistence/` as one Spring Data interface per file (`PublisherJpaRepository.java`, `PermissionHistoryJpaRepository.java`, `ArticleJpaRepository.java`, `DigestJpaRepository.java`, `DigestArticleJpaRepository.java`, `ArticleSectorJpaRepository.java`, `ArticleTopicJpaRepository.java`, `RuleVersionJpaRepository.java`, `CollectionStateJpaRepository.java`; Spring Data does not register nested repository interfaces, so the planned single `NewsJpaRepositories.java` holder was split), plus `NewsPublisherRepositoryImpl.java`, `NewsPublisherPermissionHistoryRepositoryImpl.java`, `NewsArticleRepositoryImpl.java`, `NewsDigestRepositoryImpl.java`, `NewsRuleVersionRepositoryImpl.java`, and `NewsCollectionStateRepositoryImpl.java`
- [X] T011 Add PostgreSQL integration tests for unique keys, ID-collision rejection, cascades, append-only permission history, verified-time queries, permission-first projection including denied-representative fallback and digest exclusion, cursor transactionality, and lease expiry recovery in `backend/src/test/java/com/ssafy/thorp/news/infrastructure/persistence/NewsPersistenceIT.java`

**Checkpoint**: 데이터 모델과 저장 경계가 준비되어 US1~US5를 구현할 수 있다.

---

## Phase 3: User Story 1 - 시장 흐름에서 뉴스 묶음 3개를 본다 (Priority: P1) 🎯 MVP

**Goal**: RSS 중심의 메타데이터를 수집하고 GDELT로 보완하여, 본문 없이 결정론적으로 묶은 뉴스 0~3개를 현재 시장 흐름용 API로 제공한다.

**Independent Test**: fixture RSS와 GDELT 입력에서 같은 URL은 하나로 남고 같은 사건 보도는 규칙 v1로 묶이며, 응답의 각 묶음은 필수 다섯 표시 정보를 갖고 어떤 저장·응답 schema에도 기사 본문이 없다.

### Tests for User Story 1

- [X] T012 [P] [US1] Add RSS fixture and HTTP security tests for allowlisted hosts, redirect/time/size/content-type bounds, optional fields, opaque GUID, RFC-822 dates, conditional 304, ignored description, and XML entity rejection in `backend/src/test/java/com/ssafy/thorp/news/infrastructure/client/NewsHttpSecurityPolicyTest.java`, `RssNewsClientTest.java`, and `backend/src/test/resources/news/rss/`
- [X] T013 [P] [US1] Add GDELT DOC/file fixture and HTTP boundary tests for bounded windows, result caps, title/URL metadata, recorded-time separation, and non-displayable records in `backend/src/test/java/com/ssafy/thorp/news/infrastructure/client/GdeltNewsClientTest.java` and `backend/src/test/resources/news/gdelt/`
- [X] T014 [P] [US1] Add deterministic URL normalization, topic linking, duplicate fallback, grouping-boundary, transitive-similarity, summary exclusion, and importance-order tests in `backend/src/test/java/com/ssafy/thorp/news/application/service/NewsProcessingRulesTest.java`
- [X] T015 [P] [US1] Add MockMvc response-contract tests for READY and fewer-than-three results using `specs/012-market-news-digest/contracts/examples/current-ready.json` in `backend/src/test/java/com/ssafy/thorp/news/presentation/NewsDigestContractTest.java`

### Implementation for User Story 1

- [X] T016 [P] [US1] Implement the shared HTTP security boundary—publisher host allowlist, bounded redirects, response size/content-type and time budgets, URL sanitization, disabled XML external entities—and RSS 2.0 metadata parsing with conditional requests and no description/body persistence in `backend/src/main/java/com/ssafy/thorp/news/infrastructure/client/NewsHttpSecurityPolicy.java`, `NewsHttpClient.java` (interface; the real call lives in `JdkNewsHttpClient.java` and the response in `NewsHttpResponse.java` so clients can be verified from fixtures without a network), and `RssNewsClient.java`
- [X] T017 [US1] After T016, implement bounded GDELT DOC ArticleList retrieval and oldest-first required-file retrieval through the shared HTTP security boundary while keeping record time separate from publication time in `backend/src/main/java/com/ssafy/thorp/news/infrastructure/client/GdeltDocNewsClient.java`, `GdeltFileNewsClient.java`, `GdeltTimestamps.java` (shared time formats), and `CompositeNewsSourceClient.java` (implements the `NewsSourceClient` port, which covers both RSS and GDELT)
- [X] T018 [P] [US1] Implement versioned canonical URL, fallback duplicate key, sector/topic relevance, and conservative event grouping rules in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsUrlNormalizer.java`, `NewsRelevanceClassifier.java`, and `NewsGroupingPolicy.java`, reading topics through the new `application/port/NewsTopicCatalog.java` boundary (implemented by `infrastructure/config/RegistryNewsTopicCatalog.java`) so the application layer does not depend on the infrastructure registry
- [X] T019 [P] [US1] Implement deterministic representative selection, safe one-sentence metadata summary, exclusion fallback, and importance tuple ordering in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsSummaryPolicy.java` and `NewsImportancePolicy.java`
- [X] T020 [US1] Orchestrate period → relevance → deduplication → persistence → grouping with per-source commits, cursor-after-commit semantics, and idempotent reruns in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsIngestionService.java`, persisting sector/topic links through the new `application/repository/NewsArticleLinkRepository.java` boundary (implemented by `infrastructure/persistence/NewsArticleLinkRepositoryImpl.java` with `NewsTopicJpaEntity`/`NewsTopicJpaRepository`)
- [X] T021 [US1] Add the 15-minute due-source scheduler and database lease boundary without holding transactions during network calls in `backend/src/main/java/com/ssafy/thorp/news/infrastructure/scheduling/NewsCollectionScheduler.java` and `DatabaseNewsCollectionLease.java`
- [X] T022 [US1] Implement current digest query and result projection with maximum three items in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsDigestQueryService.java` and `backend/src/main/java/com/ssafy/thorp/news/application/result/NewsDigestListResult.java`
- [X] T023 [US1] Implement the body-free `GET /market-news/digests` response using `HttpApiResponse` in `backend/src/main/java/com/ssafy/thorp/news/presentation/controller/NewsDigestController.java` and `backend/src/main/java/com/ssafy/thorp/news/presentation/response/NewsDigestResponse.java`

**Checkpoint**: US1만으로 현재 뉴스 묶음 MVP를 fixture 기반으로 독립 시연할 수 있다.

---

## Phase 4: User Story 2 - 섹터를 선택해 관련 뉴스로 좁힌다 (Priority: P2)

**Goal**: 001의 canonical sector ID를 받아 관련 기사와 묶음만 반환하고, 관련 뉴스가 없으면 정상 EMPTY를 반환한다.

**Independent Test**: 동일한 저장 자료에서 `sectorId` 유무에 따라 뉴스만 좁혀지고, 미연결 해외 기사와 다른 섹터 기사는 포함되지 않으며, 없는 sector 결과는 다른 뉴스로 채워지지 않는다.

### Tests for User Story 2

- [X] T024 [P] [US2] Add sector/topic registry and overseas domestic-link classification tests in `backend/src/test/java/com/ssafy/thorp/news/application/service/NewsSectorLinkPolicyTest.java`
- [X] T025 [P] [US2] Add repository and MockMvc tests for canonical sector filtering, unknown sector validation, and EMPTY-without-fill behavior in `backend/src/test/java/com/ssafy/thorp/news/infrastructure/persistence/NewsSectorQueryIT.java` and `backend/src/test/java/com/ssafy/thorp/news/presentation/NewsSectorDigestContractTest.java`

### Implementation for User Story 2

- [X] T026 [P] [US2] Implement canonical sector validation and lookup against the foundational immutable registry without locally inventing sector IDs in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsSectorQueryPolicy.java`
- [X] T027 [US2] Implement domestic and overseas article link rules with recorded rule version and evidence key in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsSectorLinkPolicy.java`
- [X] T028 [US2] Extend digest query and controller validation for optional `sectorId` while preserving EMPTY semantics in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsDigestQueryService.java` and `backend/src/main/java/com/ssafy/thorp/news/presentation/controller/NewsDigestController.java`

**Checkpoint**: US2는 US1 자료를 사용하지만 sector 필터 결과를 독립적으로 검증할 수 있다.

---

## Phase 5: User Story 3 - 과거 사례에는 당시 뉴스만 연결된다 (Priority: P2)

**Goal**: 최대 93일의 지정 구간에서 검증된 발행 시각을 가진 당시 뉴스만 반환하고 현재 뉴스와 계약상 구분한다.

**Independent Test**: 구간 경계 안의 VERIFIED 기사만 반환되며 UNKNOWN·GDELT recorded time 대체·범위 밖 기사는 0건이고, 결과가 없으면 HISTORICAL/EMPTY 응답이 반환된다.

### Tests for User Story 3

- [X] T029 [P] [US3] Add query validation tests for paired from/to, inclusive boundaries, reversed ranges, timezone offsets, and the 93-day maximum in `backend/src/test/java/com/ssafy/thorp/news/presentation/NewsHistoricalQueryValidationTest.java`
- [X] T030 [P] [US3] Add persistence and contract tests excluding UNKNOWN/out-of-range records and matching `specs/012-market-news-digest/contracts/examples/historical-empty.json` in `backend/src/test/java/com/ssafy/thorp/news/infrastructure/persistence/NewsHistoricalQueryIT.java` and `backend/src/test/java/com/ssafy/thorp/news/presentation/NewsHistoricalDigestContractTest.java`

### Implementation for User Story 3

- [X] T031 [US3] Add current-versus-historical query command validation and UTC boundary normalization in `backend/src/main/java/com/ssafy/thorp/news/application/command/NewsDigestQuery.java`
- [X] T032 [US3] Implement permission-aware verified-publication historical projection without using GDELT record time as publication time in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsDigestQueryService.java`
- [X] T033 [US3] Extend the endpoint response with HISTORICAL context, requested bounds, and historical empty message in `backend/src/main/java/com/ssafy/thorp/news/presentation/controller/NewsDigestController.java` and `backend/src/main/java/com/ssafy/thorp/news/presentation/response/NewsDigestResponse.java`

**Checkpoint**: 010/011의 화면 구현 없이도 날짜 계약만으로 당시 뉴스 API를 독립 검증할 수 있다.

---

## Phase 6: User Story 4 - 수집 실패·지연이 정상 화면으로 처리된다 (Priority: P2)

**Goal**: 소스별 실패와 rate limit을 격리하고 마지막 성공 자료, 갱신 시각, EMPTY/STALE 상태를 정상 응답으로 제공한다.

**Independent Test**: timeout·429·503·malformed source를 각각 주입해 bounded retry와 다음 시도가 기록되고, 허가 매체가 없으면 PREPARING, 허가 매체는 있지만 자료가 없으면 EMPTY, 기존 자료가 30분 이상 오래되면 STALE이 HTTP 200으로 반환되며 `/markets/*`와 전체 health는 영향받지 않는다.

### Tests for User Story 4

- [X] T034 [P] [US4] Add retry classification, Retry-After, jitter bounds, failure cooldown, and independent-source recovery tests in `backend/src/test/java/com/ssafy/thorp/news/application/service/NewsRetryPolicyTest.java` and `NewsIngestionFailureTest.java`
- [X] T035 [P] [US4] Add EMPTY/STALE/READY freshness tests using a fixed Clock, data presence, the 30-minute threshold, and the initial `lastUpdatedAt=null` plus '아직 수집되지 않음' message in `backend/src/test/java/com/ssafy/thorp/news/application/service/NewsFreshnessPolicyTest.java`
- [X] T036 [P] [US4] Add response-contract and health-isolation tests using `specs/012-market-news-digest/contracts/examples/current-stale.json` in `backend/src/test/java/com/ssafy/thorp/news/presentation/NewsStaleContractTest.java` and `backend/src/test/java/com/ssafy/thorp/news/NewsHealthIsolationIT.java`

### Implementation for User Story 4

- [X] T037 [P] [US4] Implement transient/terminal source-error classification, Retry-After precedence, and bounded exponential backoff with jitter in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsRetryPolicy.java` and `backend/src/main/java/com/ssafy/thorp/news/application/exception/NewsSourceException.java`
- [X] T038 [P] [US4] Implement freshness state from aggregate last-success time and configurable 30-minute default without marking Actuator health DOWN in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsFreshnessPolicy.java` and `backend/src/main/java/com/ssafy/thorp/news/infrastructure/config/NewsHealthIndicator.java`
- [X] T039 [US4] Integrate per-source failure persistence and READY/EMPTY/STALE response projection in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsIngestionService.java` and `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsDigestQueryService.java`

**Checkpoint**: 외부 뉴스원 장애를 정상적인 뉴스 상태로 독립 시연할 수 있다.

---

## Phase 7: User Story 5 - 매체 허가를 확인한 뒤 공개한다 (Priority: P3)

**Goal**: 매체별 요청·회신 이력을 기록하고 PUBLIC에서는 ALLOWED 기사와 허용 필드만으로 묶음·순위·JSON 내보내기를 제공하며, 허가 매체가 없으면 PREPARING을 반환한다.

**Independent Test**: PENDING·DENIED 기사가 mixed digest에 있어도 제목·요약·매체 수·정렬에 흔적이 없고, ALLOWED가 하나도 없을 때 공개 계약은 PREPARING이며 INTERNAL 모드는 클라이언트가 선택할 수 없다.

### Tests for User Story 5

- [X] T040 [P] [US5] Add append-only permission request/response history, allowed-field validation, and atomic current-state transition tests in `backend/src/test/java/com/ssafy/thorp/news/domain/model/NewsPublisherPermissionTest.java`
- [X] T041 [P] [US5] Add public projection tests for permission-before-ranking, mixed-digest representative replacement, denied-source removal, and all-pending state in `backend/src/test/java/com/ssafy/thorp/news/infrastructure/persistence/NewsPublicProjectionIT.java`
- [X] T042 [P] [US5] Add PUBLIC PREPARING contract and client-mode-override rejection tests using `specs/012-market-news-digest/contracts/examples/public-preparing.json` in `backend/src/test/java/com/ssafy/thorp/news/presentation/NewsPreparingContractTest.java`

### Implementation for User Story 5

- [X] T043 [P] [US5] Implement publisher current permission state plus append-only request/response history and validated atomic transitions in `backend/src/main/java/com/ssafy/thorp/news/domain/model/NewsPublisher.java`, `NewsPublisherPermissionHistory.java`, and `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsPublisherPermissionService.java`
- [X] T044 [US5] Implement server-owned INTERNAL/PUBLIC exposure policy and permission filtering before digest summary/count/ranking projection in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsExposurePolicy.java` and `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsDigestQueryService.java`
- [X] T045 [US5] Add PREPARING response projection without an externally selectable exposure parameter in `backend/src/main/java/com/ssafy/thorp/news/presentation/controller/NewsDigestController.java` and `backend/src/main/java/com/ssafy/thorp/news/presentation/response/NewsDigestResponse.java`
- [X] T046 [P] [US5] Add MockMvc and persistence tests for permission-filtered fields, PENDING/DENIED exclusion, stable cursor pagination, 500-item limit, and `specs/012-market-news-digest/contracts/examples/export-allowed.json` in `backend/src/test/java/com/ssafy/thorp/news/presentation/NewsArticleExportContractTest.java` and `backend/src/test/java/com/ssafy/thorp/news/infrastructure/persistence/NewsArticleExportIT.java`
- [X] T047 [US5] Implement body-free `GET /market-news/articles/export` with period/sector validation, stable cursor pagination, and allowed-field projection in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsArticleExportService.java`, `backend/src/main/java/com/ssafy/thorp/news/presentation/controller/NewsArticleExportController.java`, and `backend/src/main/java/com/ssafy/thorp/news/presentation/response/NewsArticleExportResponse.java`

**Checkpoint**: PD-15 회신 전에도 내부 검증은 가능하고 공개 데이터 누출은 차단된다.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 관측성, 종료 정리, 언어 정책, 성능, 계약 동치와 전체 검증을 마무리한다.

- [X] T048 [P] Add structured source-run metrics and sanitized logs for duration, processed/excluded/duplicate/grouped counts, retry, and freshness in `backend/src/main/java/com/ssafy/thorp/news/infrastructure/config/NewsObservability.java` and `backend/src/test/java/com/ssafy/thorp/news/infrastructure/config/NewsObservabilityTest.java`
- [X] T049 Add daily news-domain cleanup activated on/after 2026-11-07 with cascade deletion and repeat zero-row no-op semantics, without a long-term audit table, in `backend/src/main/java/com/ssafy/thorp/news/application/service/NewsRetentionService.java`, `backend/src/main/java/com/ssafy/thorp/news/infrastructure/scheduling/NewsRetentionScheduler.java`, and `backend/src/test/java/com/ssafy/thorp/news/infrastructure/persistence/NewsRetentionIT.java`
- [X] T050 [P] Add version-controlled grouping-quality fixtures that prove duplicate and wrong-event merges are 0 and same-event split rate is at most 10%, plus language-policy cases excluding forecast, judgment, investment implication, and return-causation language, in `backend/src/test/resources/news/grouping-quality.json`, `summary-policy.json`, `backend/src/test/java/com/ssafy/thorp/news/application/service/NewsGroupingQualityTest.java`, and `NewsSummaryLanguagePolicyTest.java`
- [X] T051 Synchronize API documentation and all response examples with implementation in `docs/specs/market-news-api.md`, `specs/012-market-news-digest/contracts/openapi.yaml`, and `specs/012-market-news-digest/contracts/examples/current-ready.json`, `current-stale.json`, `public-preparing.json`, `historical-empty.json`, `export-allowed.json`
- [ ] T052 Add repeatable PostgreSQL Testcontainers performance tests with 100,000 article fixtures, 30 warm-up/100 measured digest queries at p95 ≤500ms, and a local 20-RSS-plus-one-GDELT collection cycle ≤15 minutes in `backend/src/test/java/com/ssafy/thorp/news/performance/NewsPerformanceIT.java`
- [ ] T053 Run and record `backend/.\gradlew.bat spotlessApply build`, confirm PostgreSQL Testcontainers/Flyway tests were not skipped, execute every scenario in `specs/012-market-news-digest/quickstart.md`, and document verified scope and remaining PD-15 limitation in `docs/specs/market-news-api.md`

---

## Work Batches: Jira Task · Branch · MR Units

각 작업 묶음은 Constitution v3.0.0의 세 조건—하나의 목적, 독립 구현·검증·롤백, 리뷰 가능한 변경 범위—을 만족해야 한다. 아래 Jira 제목의 번호는 실제 Jira 발급 번호로 대체하며, 브랜치는 해당 번호를 사용한다.

| Batch | Included Tasks | Jira Task title | Branch example | Independent completion evidence |
|---|---|---|---|---|
| WB01 도메인·저장 기반 | T001~T011 | `[BE] 뉴스 도메인과 영속 저장 기반 구축` | `feat(be)/{JIRA}-news-foundation` | 설정·registry·Flyway V3·허가 이력·도메인 불변식·PostgreSQL repository IT; 후속 수집·API 없이 독립 검증·롤백 가능 |
| WB02 안전한 수집·가공 파이프라인 | T012~T014, T016~T021, T050 | `[BE] 안전한 뉴스 수집과 사건 묶음 파이프라인 구현` | `feat(be)/{JIRA}-news-ingestion-pipeline` | HTTP/XML 보안 경계, RSS/GDELT fixture, URL·관련성·중복·묶음·요약 규칙과 품질 기준, 멱등 ingestion과 lease; HTTP 조회 없이 독립 검증 가능 |
| WB03 뉴스 조회·상태 API | T015, T022~T039, T048, T052 | `[BE] 현재 섹터 과거 뉴스와 상태 API 구현` | `feat(be)/{JIRA}-news-digest-api` | 현재·섹터·과거 조회, 장애 격리, READY/EMPTY/STALE, 관측성과 성능 기준; 수집기는 fixture repository로 대체 가능 |
| WB04 공개·내보내기·종료 정책 | T040~T047, T049, T051, T053 | `[BE] 뉴스 공개 허가 내보내기와 종료 정책 구현` | `feat(be)/{JIRA}-news-publication-policy` | 허가 이력·PREPARING·허용 필드 JSON 내보내기·고정일 일괄 삭제·계약 동기화 및 전체 build |

### Work Batch Rules

- 한 실행 Task를 두 작업 묶음에 중복 배치하지 않는다. T001~T053은 위 표에 정확히 한 번씩 포함된다.
- 각 작업 묶음을 시작하기 전에 대응 Jira Task를 만들고 담당자·스프린트·Epic·Story·스토리 포인트를 확인한다.
- 한 작업 묶음 안의 실행 Task가 모두 완료되지 않으면 그 Jira Task와 MR을 완료 상태로 바꾸지 않는다.
- 작업 도중 묶음이 리뷰·롤백 가능한 크기를 넘으면 Jira Task와 브랜치를 추가하기 전에 plan과 이 표에 분리 사유와 새 구성을 기록한다.
- MR 본문의 `주요 변경 사항`에 포함된 실행 Task ID와 검증 결과를 나열한다.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 즉시 시작 가능
- **Foundational (Phase 2)**: Setup 완료 후 진행하며 모든 사용자 스토리를 차단
- **US1 (Phase 3)**: Foundational 완료 후 시작; MVP의 필수 기반
- **US2 (Phase 4)**: Foundational과 US1의 ingestion/query 기반에 의존
- **US3 (Phase 5)**: Foundational과 US1의 저장·조회 기반에 의존; US2와 병렬 가능
- **US4 (Phase 6)**: Foundational과 US1 ingestion/query 기반에 의존; US2·US3와 병렬 가능
- **US5 (Phase 7)**: Foundational과 US1 query 기반에 의존; US2~US4와 병렬 가능
- **Polish (Phase 8)**: 선택한 사용자 스토리 구현 후 진행; T051~T053은 전체 완료 뒤 수행

### User Story Dependency Graph

```text
Setup → Foundational → US1 (MVP)
                         ├── US2 sector filter
                         ├── US3 historical query
                         ├── US4 failure/freshness
                         └── US5 permission/publication gate
US1~US5 → Polish/Retention/Performance/Full validation
```

### Within Each User Story

- 테스트 Task를 먼저 작성하고 예상 이유로 실패하는지 확인한다.
- domain/port와 source adapter를 service보다 먼저 구현한다.
- persistence projection과 service를 controller/response보다 먼저 구현한다.
- 공개 필터는 top-3 순위 계산 전에 적용한다.
- 네트워크 호출 중 DB transaction을 유지하지 않고, batch commit 뒤에만 cursor를 전진한다.
- `frontend/`에 계약 타입이나 mock을 추가하지 않는다.

## Parallel Opportunities

- Setup에서 T002와 T003은 T001과 파일이 달라 병렬 가능하다.
- Foundational에서 T005를 먼저 완료하고 T006을 구현한 뒤, 서로 다른 파일을 수정하는 T007과 T008을 병렬 진행할 수 있다.
- US1 테스트 T012~T015는 병렬 가능하다. 구현은 T016 완료 후 T017을 진행하고, T018과 T019는 서로 다른 파일에서 병렬 진행할 수 있다.
- US1 완료 후 US2, US3, US4, US5를 서로 다른 담당자가 병렬 진행할 수 있다.
- US5의 허가 모델(T043) 이후 조회 정책(T044)과 내보내기 테스트(T046)는 다른 파일에서 병렬 진행할 수 있다.
- Polish의 관측성(T048)과 품질 fixture(T050)는 서로 병렬 가능하다.

## Parallel Example: User Story 1

```text
Developer A: T012 → T016 (RSS 계약과 adapter)
Developer B: T013 → T017 (GDELT 계약과 adapter)
Developer C: T014 → T018/T019 (중복·묶음·요약·정렬 규칙)
Developer D: T011 완료 후 T020/T021 (영속 ingestion과 scheduler)
Developer E: T015 → T022/T023 (조회 service와 HTTP 계약)
```

같은 파일을 수정하는 T020 이후 작업은 담당자가 순서대로 통합한다. `[P]` 표시는 선행 기반이 완료되었다는 전제이며 무조건 동시 시작한다는 뜻이 아니다.

## Implementation Strategy

### MVP First (US1 Only)

1. Phase 1 Setup 완료
2. Phase 2 Foundational 완료
3. Phase 3 US1의 fixture 기반 RSS/GDELT 수집·묶음·조회 완료
4. `current-ready.json` 계약 동치와 본문 비저장을 검증하고 중지
5. 내부 환경에서만 시연; PD-15 전에는 공개하지 않음

### Incremental Delivery

1. US1: 현재 뉴스 0~3묶음 API
2. US2: canonical sector 필터
3. US3: 검증 발행 시각 기반 당시 뉴스
4. US4: 실패·지연·복구 상태
5. US5: 매체 허가와 PUBLIC gate
6. Polish: 관측성, 고정일 삭제, 품질·성능 검증, 계약·문서 동기화

### Review Gates

- 각 Work Batch는 Jira Task·브랜치·MR 하나로 진행하고, 포함된 실행 Task ID를 MR 본문에 기록한다.
- 각 phase checkpoint에서 코드 리뷰와 해당 독립 테스트를 완료한다.
- `checklists/news-quality.md`의 미충족 요구사항 품질 항목은 구현 전에 원본 spec/plan/contract에서 해결한다.
- 최종적으로 quickstart 전체와 backend build를 통과한 뒤 `/speckit.converge`를 실행한다.

## Notes

- `[P]`는 파일과 미완료 의존성이 겹치지 않는 작업만 표시한다.
- 외부 실호출은 opt-in이며 일반 테스트는 fixture를 사용한다.
- `worker/`, `data/`, `frontend/`, `infra/` 변경을 요구하는 발견이 생기면 자동 확장하지 말고 plan 변경 승인을 먼저 받는다.
- 기사 본문, RSS description, 이미지, GDELT Tone은 저장·요약·응답 어디에도 추가하지 않는다.

---

## Phase 9: Convergence

구현 결과를 spec·plan·tasks와 대조해 남은 일을 덧붙인 목록이다. 기존 Task는 손대지 않는다.
이미 T015·T022~T049·T051~T053이 맡고 있는 일은 중복으로 만들지 않았다.

- [X] T054 CRITICAL: GDELT 기사에 실제 매체를 귀속시킨다. 지금은 `NewsCollectionScheduler`가 활성 매체 중 첫 번째를 넘겨 해외 기사가 임의의 국내 매체 식별자로 저장되고, 그 매체의 허가 상태를 그대로 물려받아 회신하지 않은 해외 매체의 기사가 공개될 수 있다. GDELT 응답의 domain으로 매체를 찾고 없으면 `PENDING` 상태로 등록한 뒤 그 식별자를 쓴다 per SC-005, FR-016 (contradicts)
- [X] T055 조건부 요청이 실제로 동작하도록 응답의 ETag·Last-Modified를 매체에 되돌려 저장한다. `NewsPublisher.withConditionalRequestState`가 호출되지 않아 저장된 ETag가 계속 비어 있고 304 경로에 도달하지 못한다. 저장은 수집 회차 트랜잭션 안에서 한다 per FR-013 (partial)
- [X] T056 RSS 채널의 `ttl`·`skipHours`·`skipDays` 힌트를 읽어 다음 시도 시각에 반영한다. 현재 파서가 이 요소들을 건너뛰어 매체가 요청한 호출 간격을 지키지 못한다 per plan "수집과 재시도" (missing)
- [X] T057 가림 정책을 시험으로 고정한다. 로그·저장된 `errorCategory`·운영 지표·API 응답 어디에도 URL query, 인증 정보, `rssUrl`, 외부 응답 원문이 실리지 않는지 단언하는 테스트를 둔다. 지금은 관례로만 지키고 있어 회귀를 잡지 못한다 per SC-009, FR-021 (missing)
- [X] T058 배포 파일에서 온 기사를 `GDELT_FILE`로 저장한다. `CompositeNewsSourceClient`가 DOC 결과와 합치면서 출처 구분이 사라져 `NewsIngestionService`가 전부 `GDELT_DOC`으로 기록한다 per data-model `NewsArticle.sourceType` (partial)

---

## Phase 10: Convergence

두 번째 수렴 검토다. Phase 9의 T054~T058은 아직 구현되지 않았고 그대로 유효하므로 중복으로 만들지 않았다.
이번에는 묶음 재생성 경로를 살펴 새로 찾은 것만 담는다. 기존 Task는 손대지 않는다.

- [X] T059 치명: 묶음 후보에 48시간 창 안의 기존 기사를 포함시킨다. 지금 `NewsIngestionService.regroup`은 이번 회차에 들어온 기사만 묶어서, 같은 사건 보도가 다른 회차에 들어오면 영영 묶이지 않는다. 15분 주기 수집에서는 같은 사건이 한 회차에 모두 들어오는 일이 드물어 묶음 기능이 사실상 동작하지 않는다. 선언만 되고 호출되지 않는 `NewsDigestRepository.findByArticleIds`와 `findRecent`가 그 용도다 per FR-011, US1/AC1, SC-002 (contradicts)
- [X] T060 대표 기사가 바뀔 때 옛 묶음이 남지 않게 한다. `digestId`가 대표 기사에서 유도되므로 더 이른 발행 시각의 기사가 나중 회차에 들어오면 새 식별자가 생기고 이전 묶음이 그대로 남아 같은 사건이 두 번 보인다. 재생성 범위 안의 옛 묶음을 정리하거나 새 묶음이 승계하게 한다 per FR-011, SC-002 (partial)
- [X] T061 사실 요약의 매체명을 대표 기사의 매체로 바꾼다. `NewsIngestionService` 285·366행이 회차를 돌린 매체의 이름을 쓰고 있어, 여러 매체가 섞인 묶음에서 대표 기사와 다른 매체가 문장에 실린다 per FR-003 (partial)
- [X] T062 호출되지 않는 공개 메서드를 정리한다. `NewsSummaryPolicy.displayNames`, `GdeltFileNewsClient.slotName`, `NewsIngestionService.GdeltCursor.parse`가 쓰이는 곳이 없다. 쓸 자리를 만들거나 지운다. `JdkNewsHttpClient.retryAfter`는 T037이 쓸 예정이라 제외한다 per plan 범위 (unrequested)

---

## Phase 11: Convergence

세 번째 수렴 검토다. Phase 9·10의 T054~T062는 코드가 그대로라 여전히 유효하므로 중복으로 만들지 않았다.
이번에는 GDELT 경로 끝단과 재현 근거 저장을 살폈다. 기존 Task는 손대지 않는다.

- [X] T063 치명: GDELT로 들어온 기사가 관련성 필터에서 전량 폐기되는 것을 고친다. `GdeltDocNewsClient`와 `GdeltFileNewsClient`가 분류 값을 빈 목록으로 채우는데 `NewsRelevanceClassifier`는 주제가 비면 무조건 거부하므로, 보완원으로 받은 기사가 한 건도 저장되지 않는다. 응답에서 주제를 끌어내거나 조회에 쓴 주제를 그 기사에 물려준다 per FR-009, FR-010, US1 (contradicts)
- [X] T064 원시 응답 해시를 재현 가능한 값으로 바꾼다. `NewsIngestionService` 304행이 `Integer.toHexString(item.hashCode())`를 쓰는데 32비트라 충돌이 잦고 원시 응답이 아니라 파싱 결과에서 나온 값이라 나중에 같은 응답인지 대조할 수 없다. 응답 바이트에서 SHA-256을 계산해 저장한다 per 원칙 VIII, data-model `NewsArticle.sourcePayloadHash` (partial)
- [X] T065 묶음 판정 근거를 저장한다. `NewsDigestRepositoryImpl` 79·80행이 제목 유사도와 공통 주제 키를 null로 넣어, 왜 그 기사들이 한 묶음이 됐는지 나중에 확인할 수 없다. 판정에 쓴 값을 그대로 남긴다 per 원칙 VIII, data-model `NewsDigestArticle` (partial)
- [X] T066 주제 연결 근거 키를 저장한다. `NewsArticleLinkRepositoryImpl` 60행이 `matchEvidenceKey`를 null로 넣어, 어떤 규칙 항목이 걸려 그 주제에 연결됐는지 남지 않는다. 매핑에 걸린 분류 값을 근거로 남기되 기사 본문 조각은 저장하지 않는다 per data-model `NewsArticleTopic` (partial)

---

## Phase 12: Convergence

네 번째 수렴 검토다. Phase 9~11의 T054~T066은 모두 구현됐고 전체 빌드가 통과한다.
이번에는 그 수정이 남긴 자리를 살폈다. 치명 격차는 없다. 기존 Task는 손대지 않는다.

- [X] T067 등록된 매체가 없어도 GDELT 회차를 돌린다. `NewsCollectionScheduler.runGdelt`가 활성 매체가 비면 회차를 통째로 건너뛰는데, 매체 registry는 공개 전까지 비어 있는 것이 정상이라 그동안 해외 보완 수집이 영영 돌지 않는다. T054로 GDELT가 기사마다 매체를 스스로 찾아 등록하게 됐으므로 국내 매체를 미리 요구할 이유가 사라졌다 per FR-009, plan "수집과 재시도" (contradicts)
- [X] T068 묶음의 공통 주제 근거를 저장한다. `NewsDigestRepositoryImpl.sharedTopicKey`가 항상 null을 돌려줘 제목 유사도만 남고 공통 주제는 비어 있다. 묶음을 만든 쪽이 판정에 쓴 공통 주제를 넘겨 그대로 저장한다. 저장소에서 주제를 다시 조회하면 판정 당시 값과 달라질 수 있어 근거가 되지 못한다 per 원칙 VIII, data-model `NewsDigestArticle` (partial)
- [X] T069 배포 파일 기사의 언어를 단정하지 않는다. `GdeltFileNewsClient`가 확인 없이 `en`으로 채우는데, 배포 파일에 언어 열이 없으면 그것은 모르는 값이다. 모르는 값을 채우면 나중에 언어로 거르는 판단이 틀어진다 per 원칙 VI, data-model `NewsArticle.language` (partial)

---

## Phase 13: Convergence

수집 경로의 바깥쪽 끝을 봤다. 앞선 회차가 우리 코드끼리의 이음매를 맞췄다면, 이번에 걸린 것은 외부 서비스의 실제 형식과 "표시해도 되는 기사"의 경계다. 기존 Task는 손대지 않는다.

- [X] T070 GDELT 배포 파일을 실제 배포 형식으로 받는다. `GdeltFileNewsClient`가 `{시각}.export.CSV`를 요청하고 `NewsHttpSecurityPolicy.EXPORT_FILE_CONTENT_TYPES`가 `text/csv` 계열만 허용하는데, GDELT 2.0이 실제로 배포하는 것은 `{시각}.export.CSV.zip`이다. 지금 상태로는 운영에서 배포 파일 보완이 매 회차 전량 실패하고, 로컬 fixture로만 돌아 테스트는 통과한다. 주소·압축 해제·허용 content-type을 실제 형식에 맞추고 fixture도 같은 형식으로 바꾼다 per FR-013, plan "수집과 재시도" (contradicts)
- [X] T071 검증된 발행 시각이 없는 배포 레코드를 표시 후보로 만들지 않는다. `NewsIngestionService.save`의 `displayEligible`이 요약을 만들 수 있는지만 보는데, plan은 배포 레코드로 검증된 발행 시각과 제목을 확보하지 못하면 그 레코드를 연결·중복 판정 보조로만 쓰라고 한다. 배포 파일에는 발행 시각이 없으므로 이 경로의 기사는 항상 표시 후보에서 빠져야 한다 per plan "수집과 재시도", FR-006·FR-012 (partial)
- [X] T072 표시할 수 없는 기사를 묶음 후보에서 뺀다. `NewsIngestionService.regroup`이 후보의 `displayEligible`을 보지 않아, 표시 불가 기사가 대표 기사로 뽑히거나 서로 다른 매체 수를 부풀린다. 대표가 표시되지 못하면 묶음 전체가 사라지고, 매체 수가 부풀면 주요도 순서가 틀어진다 per FR-001·FR-003·FR-004 (partial)
- [X] T073 매핑되지 않은 분류 값의 건수를 남긴다. `NewsRelevanceClassifier.Relevance.unmappedCategories`를 계산만 하고 아무도 읽지 않아, 등록표에 빠진 분류 값이 얼마나 버려지는지 알 방법이 없다. 원문 값은 저장하지 않고 건수만 회차 로그에 남긴다 per FR-010 (partial)

---

## Phase 14: Convergence

조회 경로가 생기고 나서 본 자리다. 앞선 회차들이 수집 안쪽을 다뤘다면 이번에 걸린 것은 화면이 실제로 보게 되는 값과, 운영자가 실제로 보게 되는 지표다. 기존 Task는 손대지 않는다.

- [X] T074 '준비 중' 문구를 계약과 맞춘다. `NewsFreshnessPolicy.PREPARING_MESSAGE`가 "매체 이용 조건을 확인하는 중입니다."인데 계약 예시 `public-preparing.json`은 "뉴스 제공을 준비하고 있습니다."다. 화면은 예시를 보고 만들어졌고, 지금 문구는 매체 회신을 기다린다는 내부 사정까지 사용자에게 말한다 per FR-016, contracts/examples/public-preparing.json (contradicts)
- [X] T075 발행 시각 미확인 묶음도 현재 조회 창으로 자른다. `DigestJpaRepository.findRecent`의 `latestPublishedAt IS NULL` 가지에 시간 하한이 없어, 검증 시각을 얻지 못한 묶음은 몇 달이 지나도 '현재 뉴스' 후보로 남는다. 정렬이 뒤로 밀어 주지만 볼 것이 적은 날에는 그대로 화면에 올라온다. 수집 시각 기준으로 같은 창을 적용한다 per FR-001·FR-004 (partial)
- [X] T076 뉴스 신선도 지표를 실제로 볼 수 있게 한다. `NewsHealthIndicator`를 만들었지만 `management.endpoint.health.show-details`가 기본값이라 `/actuator/health/newsFreshness`가 404이고, 전체 health 집계에서 분리하지도 않았다. plan은 이 지표를 전용으로 노출하고 전체 집계에서는 빼라고 한다 per plan "보관과 관측성", FR-024 (partial)
- [X] T077 뉴스원별 마지막 성공 경과 시간을 지표로 남긴다. plan이 이름까지 고정한 `news.source.last_success.age`가 없어, 어떤 뉴스원이 며칠째 못 들어오고 있는지 경보로 걸 수 없다. 수집이 조용히 멈춘 것은 실패보다 늦게 발견된다 per FR-023, plan "보관과 관측성" (partial)
- [X] T078 제외 사유별 건수를 남긴다. `NewsIngestionService`가 제외를 한 숫자로만 세서 `news.ingest.excluded{reason}`을 채우지 못한다. 관련성 미달과 중복 열쇠 실패와 식별자 충돌은 대응이 서로 다른데 지표에서는 구별되지 않는다 per FR-023 (partial)
- [X] T079 INTERNAL 모드에서는 저장된 사실 요약을 쓴다. `NewsDigestQueryService`가 모드와 무관하게 요약을 다시 만들어, 전체 멤버 기준으로 저장한 값을 내부 검증에서도 볼 수 없다. 공개 응답에서 다시 만드는 것은 그대로 두고 내부 모드만 저장 값을 쓴다 per plan "요약·정렬 규칙 v1", FR-003 (partial)
- [X] T080 과거·섹터 조회를 한 질의로 합친다. `NewsDigestRepositoryImpl.findVerifiedBetween`이 구간 전체를 읽은 뒤 섹터 목록과 메모리에서 교차한다. 결과는 맞지만 읽는 양이 섹터와 무관하게 구간 전체라, 기사가 쌓일수록 섹터 조회가 전체 조회만큼 비싸진다 per plan 성능 목표 (partial)
- [X] T081 노출 모드 enum을 하나로 모은다. `NewsProperties.ExposureMode`와 `NewsExposureMode`가 따로 있고 `NewsConfig`가 `valueOf`로 잇는다. 한쪽에만 값을 더하면 컴파일은 지나가고 기동에서 막히며, 그 사이 어디가 원인인지 보이지 않는다 per plan 설정 (unrequested)
