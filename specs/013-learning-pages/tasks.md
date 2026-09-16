# Tasks: 배우기

**Input**: Design documents from `specs/013-learning-pages/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

> 이 tasks 문서 작업은 [S15P21E205-289](https://ssafy.atlassian.net/browse/S15P21E205-289) · branch `docs(fe)/289-spec-013-tasks`다.
> plan([S15P21E205-273](https://ssafy.atlassian.net/browse/S15P21E205-273))이 dev에 병합된 판(`8717a9e`)을 기준으로 썼다.
> 구현 Task의 Git 브랜치는 Jira Task마다 `{type}({position})/{JIRA_NUMBER}-{description}` 형식으로 따로 만든다.

**Tests**: 포함한다. constitution "완료의 정의" 조건 2가 "테스트 또는 동작 확인 완료"를 Task 완료 조건으로 두고 있고, plan.md "Testing"이 검증 방식 셋(계약 픽스처 대조·`PostgresTestSupport` 통합 테스트·ArchUnit)을 이미 정했다. 각 테스트 태스크는 [quickstart.md](quickstart.md)의 확인 항목 번호를 적는다.

**Organization**: 사용자 스토리별로 묶고, 각 단계 안에서 파트별로 다시 나눈다.

## Format: `[ID] [P?] [Story] (파트) Description`

- **[P]**: 다른 파일을 만지고 선행 태스크가 없어 병렬 실행 가능
- **[US1]~[US5]**: spec.md의 사용자 스토리. Setup·Foundational·Polish에는 붙지 않는다
- **(파트)**: 아래 표의 파트. 작업 종류 표시이며 브랜치 단위가 아니다

| 파트 태그 | 대상 | 묶음의 브랜치 position |
|---|---|---|
| `(BE)` | 백엔드 Java 구현 | `be` |
| `(BE 테스트)` | 백엔드 JUnit·ArchUnit·Testcontainers | `be` |
| `(FE)` | 프론트엔드 React·TS | `fe` |
| `(FE 테스트)` | 프론트엔드 Vitest | `fe` |
| `(INFRA)` | 마이그레이션 SQL·자원 파일(YAML)·설정 | `be` (모두 백엔드 자원이다) |
| `(DOCS)` | 계약 픽스처·CLAUDE.md·spec 문서·수동 확인 기록 | `docs` |

Worker(`worker/`)와 `data/`는 이번 범위에서 **변경하지 않는다**. 새 라이브러리도 추가하지 않는다([research](research.md) R13).

**`T###`는 티켓·브랜치 단위가 아니다.** constitution "Jira 연계"가 `작업 묶음 = 1 Branch = 1 MR`로 정한다.
아래 78개는 실행 지시의 최소 단위이고, **티켓·브랜치·MR의 단위는 묶음 B01~B06 여섯이다** —
"Dependencies & Execution Order"의 "작업 묶음" 표와 [plan.md](plan.md) "작업 묶음 구성"에 있다.

## 착수 전에 알아둘 것

013 혼자 닫을 수 없는 항목이다. 여기서 티켓을 만들지 않고 각 담당이 판단한다([plan](plan.md) "교차 의존").

- ① **014 관리 권한** — 014에 역할이 아직 없어 관리 권한은 설정 허용 목록 `thorp.learning.admin-user-ids`로 판정한다([research](research.md) R5). 014 Out of Scope를 고치는 개정은 014 담당의 몫이며 013 구현을 막지 않는다. 014가 역할을 주면 `ConfiguredLearningAdminPolicy` 한 클래스만 바꾼다
- ② **002 T020 미구현** — 002 tasks.md의 T020(배우기 예시 진입과 덮어쓰기 확인, Jira 미발급)이 아직 없다. 013 US2는 navigation state `sectorSelectionExample`을 **싣는 데까지**(T030·T032)가 범위이고, 002 화면이 그 값을 받아 채우는 종단 확인(quickstart 4-3)은 002 T020 뒤에 한다
- ③ **003 T057a** — 003이 `components/strategy/TermHelp.tsx`로 계획한 자리를 013 공용 `components/learning/TermHelp.tsx`로 바꾸는 것은 003 담당의 몫이다. B04가 먼저 서면 된다. `frontend/src/api/learningGlossary.ts`의 실제 구현은 013이 소유하므로, 003이 그 파일에 대역을 먼저 두었으면 T036이 본문을 바꾼다
- ④ **002·005 도움말 자리** — 어느 화면의 어떤 용어에 도움말을 붙일지는 각 spec이 정한다(spec FR-008). 쓸 수 있는 `termId` 목록은 [data-model](data-model.md) §8이다
- ⑤ **로컬 프로필은 Flyway가 꺼져 있다** — 마이그레이션이 넣는 아홉 글·열네 용어 자리가 `local`에서는 생기지 않는다. 확인하려면 [quickstart](quickstart.md) §0의 명령으로 Flyway를 켠다
- ⑥ **PD-16 미확정** — [data-model](data-model.md) §8의 제목·순서·용어 구성은 출발안이다. 팀이 확정해 달라지면 관리 화면에서 고치거나 새 마이그레이션으로 자리를 더한다. 적용된 마이그레이션은 고치지 않는다(constitution "스키마 변경 규칙")
- ⑦ **JSONB 직렬화는 전역 설정을 탄다** — `backtest/infrastructure/messaging/MessageJsonPersistenceConfig`가 Hibernate JSON 매퍼를 메시지 ObjectMapper(null 키 생략, decimal 문자열)로 바꿔 두었다. 013의 JSONB 컬럼(`body_blocks`·`example`·`sources`·`snapshot`)도 그 매퍼로 저장되므로, 판 JSON을 읽는 record는 **키 없음을 null로** 받아야 한다

## Path Conventions

- 백엔드 013 소유 영역: `backend/src/main/java/com/ssafy/thorp/learning/...`, 테스트는 `backend/src/test/java/com/ssafy/thorp/learning/...`
- 백엔드 공통 영역(한 줄씩만 만진다): `common/exception/ErrorCode.java`, `common/config/SecurityConfig.java`, `common/web/advice/GlobalExceptionHandler.java`
- 마이그레이션: `backend/src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__learning_content.sql` — 버전은 작성 시각 타임스탬프다([backend/CLAUDE.md](../../backend/CLAUDE.md))
- 검사 규칙 파일: `backend/src/main/resources/learning/publish-checks-v1.yml`
- 계약 픽스처: `docs/specs/learning-api/examples/*.json` (003의 `docs/specs/strategy-registry-api/` 방식)
- 프론트엔드: `frontend/src/...` — 화면 `pages/Learning*Page.tsx`·`pages/AdminLearning*Page.tsx`, 컴포넌트 `components/learning/`(관리는 `components/learning/admin/`), API `api/learning*.ts`, 타입 `types/learning.ts`·`types/learningAdmin.ts`
- 커밋 전 `cd backend && ./gradlew spotlessApply` 필수. 프론트엔드는 `cd frontend && npm run lint && npm run build`
- 새 `.test.ts`는 `frontend/vite.config.ts`의 `test.include`에 정확 경로로 더한다. 빠지면 `src/diagnosis/testCollection.guard.test.tsx`가 실패시킨다

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 013 소유 패키지 골격, 오류 코드, 계약 픽스처, 프론트엔드 타입·픽스처 자리

- [ ] T001 (BE) `learning` 도메인 패키지 골격 생성 — `backend/src/main/java/com/ssafy/thorp/learning/package-info.java`에 계층 규약(ADR-0001), "글·용어 설명의 소유자는 013이다", 의존 방향 "`learning.infrastructure → sector.application`(대응표는 `SectorMapPort`로 읽는다)이며 `sector`는 `learning`을 참조하지 않는다"를 명시. 하위 패키지는 `presentation/{controller,request,response,support}`, `application/{service,command,result,repository,port,exception}`, `domain/{model,vo,exception}`, `infrastructure/{persistence,sector,admin,rules}`([plan](plan.md) "Source Code")
- [ ] T002 [P] (BE) `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java`에 "spec 013 배우기" 블록을 열고 일곱 코드를 더한다([contracts/README](contracts/README.md) "새 오류 코드"): `LEARNING_ARTICLE_UNAVAILABLE(NOT_FOUND, "지금은 볼 수 없는 글입니다.")`, `LEARNING_TERM_NOT_READY(NOT_FOUND, "설명을 준비하고 있습니다.")`, `LEARNING_ARTICLE_NOT_FOUND(NOT_FOUND, …)`, `LEARNING_ARTICLE_ID_TAKEN(CONFLICT, …)`, `LEARNING_TERM_ID_TAKEN(CONFLICT, …)`, `LEARNING_DRAFT_CHANGED(CONFLICT, …)`, `LEARNING_PUBLISH_CHECK_FAILED(UNPROCESSABLE_ENTITY, "게시 전 검사에 걸린 항목이 있어 게시하지 않았습니다.")`. 주석에 계약 위치를 적는다. 공개 조회의 두 404를 한 코드로 합치지 않는다([research](research.md) R10)
- [ ] T003 [P] (DOCS) `docs/specs/learning-api/README.md`(계약 요약과 원본 위치 링크, `docs/specs/strategy-registry-api/README.md`와 같은 역할)와 `docs/specs/learning-api/examples/` 픽스처 여덟 생성 — `articles-list.json`(공개 목록 `data`, [learning-public-api](contracts/learning-public-api.md) §1), `article.json`(§2, `term` 블록에 `label`·`sentence`가 채워진 완전한 응답, `example.available=true`), `term.json`(§3), `admin-articles-list.json`([learning-admin-api](contracts/learning-admin-api.md) §2), `admin-article.json`(§4), `publish-success.json`(§6 200), `publish-check-failed.json`(§6 422의 `error` 전체, 걸린 항목 셋), `unpublish.json`(§7). 값은 계약 예시의 합성 값이며 원고가 아니다. T018·T035·T059의 계약 테스트가 이 파일을 읽는다
- [ ] T004 [P] (FE) `frontend/src/types/learning.ts` — 공개 조회 응답과 1:1 타입([data-model](data-model.md) §10): `LearningArticleSummary`, `LearningArticle`, `ArticleBlock`(`heading`·`paragraph`·`term`·`hypothetical` 판별 union, `term`은 `termId`·`label`·`sentence`), `LearningExample`(`available`·`sectors[].{code,name,weightTenths}`·`missingSectorCodes`·`unavailableReason: 'SECTOR_NOT_IN_MAP' | 'SECTOR_MAP_NOT_READY' | null`), `LearningSource`, `LearningTermEntry`, `LearningReturnState = { path: string; label: string }`, `LearningApiErrorCode = 'LEARNING_ARTICLE_UNAVAILABLE' | 'LEARNING_TERM_NOT_READY'`. 같은 태스크에서 `frontend/src/types/api.ts`의 `ServiceApiErrorCode` union에 `LearningApiErrorCode`를 잇는다(그 파일 머리 주석의 규칙: 새 도메인은 자기 파일과 `api.ts` 둘만 고친다). 파일 머리 주석에 "글 본문·용어 문장을 이 코드에 상수로 두지 않는다(FR-015)"를 적는다
- [ ] T005 [P] (FE) `frontend/src/types/learningAdmin.ts` — 관리 응답과 1:1 타입: `AdminLearningArticleSummary`(`hasUnpublishedChanges`·`needsUpdate: boolean | null`·`lastPublication | null`), `AdminLearningArticle`(`draft`·`publishedRevisionNo: number | null`), `AdminLearningDraft`(초안 블록은 `termId`만 담는다), `SaveDraftRequest`, `PublishCheckFailure`(`check`·`location`·`matched?`·`message`), `PublishResult`, `UnpublishResult`, `LearningAdminApiErrorCode`(관리 오류 코드 다섯). `ServiceApiErrorCode` union에 잇는다
- [ ] T006 [P] (FE) `frontend/src/test/learningFixtures.ts` — 화면 테스트용 응답 픽스처. **[data-model](data-model.md) §8에 없는 합성 `articleId`·`termId`를 쓴다**(`frontend/src/test/strategyRegistryFixtures.ts`와 같은 이유: 응답만 보고 그린다는 것을 증명한다). 담는 것: 목록 둘, 네 블록 종류를 모두 가진 글 하나, `example.available=false`(`SECTOR_NOT_IN_MAP`) 글 하나, 용어 설명 하나, 관리 목록·초안·게시 실패(항목 셋) 응답

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 다섯 테이블과 초기 자리, 도메인 모델, JPA 매핑, 대응표 포트, 의존 방향 규칙. 스키마·매핑·검증은 같은 묶음(B01)에 담는다(constitution "스키마 변경 규칙").

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 어떤 사용자 스토리도 시작할 수 없다. 마이그레이션 버전은 작성 시각 타임스탬프이며 `infra/scripts/check-migration-version.sh`가 기준 브랜치 대비 역전을 잡는다 — 브랜치가 오래 열려 있었으면 병합 직전에 버전을 다시 확인한다.

- [ ] T007 (INFRA) `backend/src/main/resources/db/migration/V{작성 시각}__learning_content.sql` — [data-model](data-model.md) §3의 다섯 테이블과 §8의 초기 자리.
  - `learning_article`: `id BIGINT identity PK`, `article_key VARCHAR(64) NOT NULL UNIQUE` + 형식 CHECK(`^[a-z0-9]+(-[a-z0-9]+)*$`, 3~64자), `title VARCHAR(100) NOT NULL`, `question VARCHAR(200) NOT NULL`, `reading_order INTEGER NOT NULL CHECK 1..999`, `body_blocks JSONB NOT NULL DEFAULT '[]'`, `example JSONB NULL`, `sources JSONB NOT NULL DEFAULT '[]'`, `status VARCHAR(16) NOT NULL CHECK IN ('DRAFT','PUBLISHED')`, `published_revision_id BIGINT NULL`, `draft_version INTEGER NOT NULL DEFAULT 0`, `created_at`/`updated_at TIMESTAMPTZ NOT NULL`. **불변식 CHECK**: `(status='PUBLISHED') = (published_revision_id IS NOT NULL)`
  - `learning_term`: `term_key VARCHAR(64) NOT NULL UNIQUE` + 형식 CHECK(2~64자), `article_id BIGINT NOT NULL FK`, `label VARCHAR(50) NOT NULL`, `sentence VARCHAR(300) NOT NULL`, 시각 둘
  - `learning_article_revision`: `article_id FK`, `revision_no INTEGER NOT NULL`, `(article_id, revision_no) UNIQUE`, `snapshot JSONB NOT NULL`, `check_version VARCHAR(64) NOT NULL`, `sector_map_version VARCHAR(128) NOT NULL`, `published_by_user_id BIGINT NOT NULL`(`users`에 FK를 걸지 않는다 — §3-3), `published_at TIMESTAMPTZ NOT NULL`. `learning_article.published_revision_id`의 FK는 이 테이블을 만든 뒤 `ALTER TABLE … ADD CONSTRAINT`로 건다
  - `learning_publication_event`: `article_id FK`, `action VARCHAR(16) CHECK IN ('PUBLISH','UNPUBLISH')`, `revision_id BIGINT NULL FK`, `actor_user_id BIGINT NOT NULL`, `occurred_at TIMESTAMPTZ NOT NULL`
  - `learning_published_term`: `term_key VARCHAR(64) PK`, `article_id FK`, `revision_id FK`, `label VARCHAR(50)`, `sentence VARCHAR(300)`
  - 초기 자리: §8 표의 아홉 글(`article_key`·`title`·`reading_order`, `question=''`, `status='DRAFT'`)과 열네 용어(`term_key`·`label`, `sentence=''`). 본문·예시·문장은 비운다([research](research.md) R9). 파일 머리 주석에 "제목·순서·용어 구성은 PD-16 출발안이다. 적용된 뒤에는 이 파일을 고치지 않고 관리 화면이나 새 마이그레이션으로 바꾼다"를 적는다
  - `MigrationFileConventionTest`(Docker 없이 돈다)가 파일명 규칙을 검사한다
- [ ] T008 [P] (BE) 도메인 값 타입 — `backend/src/main/java/com/ssafy/thorp/learning/domain/vo/ArticleId.java`(형식 검사를 생성자에서, 3~64자), `TermId.java`(2~64자), `ExampleAllocation.java`(`List<SectorWeight(String code, int weightTenths)>` — 코드 중복 없음, `weightTenths` 0..1000 정수, 최대 20개, 코드 형식은 001 `SectorCode`의 규칙과 같되 **`sector` 패키지를 import하지 않고** 자체 정규식으로 검사한다(도메인은 다른 도메인을 참조하지 않는다). 합계는 여기서 검사하지 않는다 — 합계 1000은 게시 전 검사의 몫이다([data-model](data-model.md) §5). 순수 Java, Spring·JPA·Jackson import 금지
- [ ] T009 [P] (BE) 도메인 모델([data-model](data-model.md) §9) — `backend/src/main/java/com/ssafy/thorp/learning/domain/model/`: `ArticleBlock.java`(`sealed interface`, 구현 record `HeadingBlock(text 1~100)`·`ParagraphBlock(text 1~2000)`·`TermBlock(TermId termId)`·`HypotheticalBlock(text 1~1000)`, 생성자에서 길이 검사), `LearningTerm.java`(`TermId`·`label`·`sentence`), `LearningArticle.java`(초안 작업본 — `ArticleId`·`title`·`question`·`readingOrder`·`blocks`(최대 60)·`example`(nullable)·`sources`·`status`·`publishedRevisionId`·`draftVersion`·`terms`; 전이 메서드 `saveDraft(expectedDraftVersion, …)`는 버전이 다르면 `DraftChangedException`을 던지고 같으면 내용을 통째로 바꾸며 `draftVersion + 1`; `publish(expectedDraftVersion, revisionId)`는 `PUBLISHED`로; `unpublish()`는 `PUBLISHED`일 때만 `DRAFT`로 바꾸고 `DRAFT`면 아무것도 하지 않는다; `status`와 `publishedRevisionId`의 불변식을 생성자에서 검사), `ArticleSnapshot.java`(§3-6의 판 내용 record — `articleId`·`title`·`question`·`readingOrder`·`bodyBlocks`·`example`·`sources`·`terms`, 그리고 `draftVersion`(게시 당시 초안 판 번호. 관리 목록의 `hasUnpublishedChanges` 판정 근거이며 T074가 §3-6에 적는다)), `ArticleRevision.java`(불변 판 — `revisionNo`·`snapshot`·`checkVersion`·`sectorMapVersion`·`publishedByUserId`·`publishedAt`, setter 없음), `PublicationEvent.java`(`PUBLISH`·`UNPUBLISH` enum `PublicationAction`, `revisionId`(nullable)·`actorUserId`·`occurredAt`). `backend/src/main/java/com/ssafy/thorp/learning/domain/exception/DraftChangedException.java` — `BusinessException(ErrorCode.LEARNING_DRAFT_CHANGED, …, Map.of("currentDraftVersion", n))`
- [ ] T010 [P] (BE) 대응표 포트 — `backend/src/main/java/com/ssafy/thorp/learning/application/port/SectorMapPort.java`: `Optional<SectorMapView> currentMap()`, `SectorMapView(String mapVersion, Map<String, String> sectorNamesByCode)` record. 대응표가 아직 없으면 `Optional.empty()`(빈 맵이 아니다 — 원칙 VI). 구현 `backend/src/main/java/com/ssafy/thorp/learning/infrastructure/sector/SectorServiceSectorMapAdapter.java`는 001 `sector/application/service/SectorService.getSectors()`를 부르고 `SectorMapNotReadyException`만 `Optional.empty()`로 바꾼다. 004의 `MarketMetadataPort`와 같은 방식([research](research.md) R8)
- [ ] T011 (BE) JPA 매핑과 저장소 — `backend/src/main/java/com/ssafy/thorp/learning/infrastructure/persistence/`: `LearningArticleJpaEntity`·`LearningTermJpaEntity`·`LearningArticleRevisionJpaEntity`·`LearningPublicationEventJpaEntity`·`LearningPublishedTermJpaEntity`(JSONB는 기존 `BacktestResultJpaEntity`처럼 `@JdbcTypeCode(SqlTypes.JSON)`; 시각은 `common/persistence/BaseEntity`), 각 `*JpaRepository`, `LearningArticleMapper`·`ArticleRevisionMapper`(정적 `toEntity`/`toDomain`), 구현 `LearningArticleRepositoryImpl`·`ArticleRevisionRepositoryImpl`·`PublicationEventRepositoryImpl`·`PublishedTermRepositoryImpl`. 인터페이스는 `backend/src/main/java/com/ssafy/thorp/learning/application/repository/`에: `LearningArticleRepository`(`findByArticleKey`, `findByArticleKeyForUpdate`(비관적 잠금 — 잠근 뒤 `refresh`, [backend/CLAUDE.md](../../backend/CLAUDE.md)), `findAllOrdered`(`reading_order`, `article_key` 오름차순), `findPublishedOrdered`, `save`, `existsByArticleKey`), `LearningTermRepository`(`findOwnerArticleKey(termKey)`, `replaceForArticle`), `ArticleRevisionRepository`(**`save`·`findById`·`findByArticleId`만. 수정·삭제 메서드를 두지 않는다** — §3-3, 원칙 VIII), `PublicationEventRepository`(**`append`·`findLastByArticleId`만**), `PublishedTermRepository`(`findPublished(termKey)` → `termId`·`label`·`sentence`·`articleId`(`article_key`)·`articleTitle`(색인 행이 가리키는 판 snapshot의 제목), `replaceForArticle(articleId, revisionId, terms)`, `deleteForArticle(articleId)`). 판 JSON record는 키 없음을 null로 읽는다(위 ⑦) (T007·T008·T009 의존)
- [ ] T012 (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/persistence/LearningMigrationTest.java`(`support/PostgresTestSupport` 상속, `@DataJpaTest`, `common/persistence/FlywayMigrationTest` 방식이며 **버전 번호를 테스트에 적지 않는다**) — 빈 DB 적용 뒤 `learning_article` 아홉 행이 모두 `DRAFT`·`question=''`·`body_blocks='[]'`·`example IS NULL`, `learning_term` 열네 행이 `sentence=''`이고 §8 표의 `term_key`가 모두 있다; `status='PUBLISHED'`인데 `published_revision_id`가 NULL인 INSERT가 CHECK로 거절된다; `article_key='Bad_Key'`가 거절된다; `(article_id, revision_no)` 중복이 거절된다(quickstart 1 "마이그레이션") (T007·T011 의존)
- [ ] T013 (BE 테스트) `backend/src/test/java/com/ssafy/thorp/ArchitectureTest.java`에 규칙 9 추가 — `noClasses().that().resideInAPackage("com.ssafy.thorp.sector..").should().dependOnClassesThat().resideInAPackage("com.ssafy.thorp.learning..")`. Javadoc에 "대응표의 소유자는 001이고 013이 포트로 읽는다. 방향은 `learning → sector` 하나다"를 적는다. **규칙이 동작하는지 일부러 어겨 확인한다**(003 T012 방식): `sector` 안의 아무 클래스에 `learning` 타입 import 한 줄을 넣고 테스트가 **실패**하는 것을 본 뒤 그 줄을 지운다. 결과를 MR 본문 "테스트" 절에 적는다(quickstart 1 "ArchitectureTest") (T001 의존)
- [ ] T014 [P] (BE 테스트) 도메인 단위 테스트 — `backend/src/test/java/com/ssafy/thorp/learning/domain/vo/ArticleIdTest.java`(경계 3·64자, 대문자·밑줄·연속 하이픈·앞뒤 하이픈 거절), `TermIdTest.java`(2·64자), `ExampleAllocationTest.java`(중복 코드·1001·21개 거절, 0은 허용), `backend/src/test/java/com/ssafy/thorp/learning/domain/model/LearningArticleTest.java`(`saveDraft`가 옛 버전이면 `DraftChangedException`이고 `details.currentDraftVersion`이 서버 값; 맞으면 `draftVersion + 1`; `publish` → `PUBLISHED`+`publishedRevisionId`; `unpublish` → `DRAFT`+null; 이미 `DRAFT`인 `unpublish`는 아무것도 바꾸지 않는다; `PUBLISHED`인데 `publishedRevisionId`가 없는 생성은 거절; 블록 61개 거절; 알 수 없는 블록 종류는 sealed라 컴파일 단계에서 막힌다) (T008·T009 의존)

**Checkpoint**: 빈 DB에 다섯 테이블과 초기 자리가 생기고, 매핑이 validate를 통과하며, `sector → learning` 참조가 규칙으로 막힌다. 사용자 스토리를 시작할 수 있다

---

## Phase 3: User Story 1 - 주제 목록에서 글을 골라 질문과 예시를 읽는다 (Priority: P1) 🎯 MVP

**Goal**: 공개 조회 두 엔드포인트(목록·글)를 열고, 화면이 그 응답만으로 목록과 글을 텍스트로 그린다. 게시된 글이 없으면 빈 목록이 정상 화면이고, 불러오지 못하면 빈 목록과 다르게 보인다.

**Independent Test**: 통합 테스트로 심은 게시 판 하나로 `/learn` 목록과 `/learn/{articleId}`가 보이고, 초안뿐인 글은 어디에도 나오지 않으며, 요청을 막으면 다시 시도 수단이 보이는지 확인한다(quickstart 2-1·2-2·4-1·4-2). 진단 기능 없이 검증할 수 있다.

### Backend — 공개 조회 (계약 §1·§2)

- [ ] T015 [P] [US1] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/result/LearningArticleSummaryResult.java`(`articleId`·`title`·`question`·`readingOrder`)와 `LearningArticleResult.java`(계약 §2 응답 모양 — `blocks[]`는 `term` 블록에 `label`·`sentence`가 채워진 형태, `example`은 `available`·`sectors[].{code,name,weightTenths}`·`missingSectorCodes`·`unavailableReason`, `sources`, `publishedAt`). 필드 이름은 계약 JSON과 1:1
- [ ] T016 [US1] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/service/LearningQueryService.java`(`@Transactional(readOnly = true)`) — `listPublished()`: `status='PUBLISHED'`인 글의 **공개 중인 판 snapshot**에서 제목·질문을 읽어 `readingOrder`, `articleId` 오름차순으로; `getPublished(String articleId)`: 형식이 틀리거나 없거나 게시되지 않은 글은 모두 `ArticleUnavailableException`(404 `LEARNING_ARTICLE_UNAVAILABLE`, 셋을 가르지 않는다 — 계약 §2 "응답 404"), 있으면 판 snapshot의 `term` 블록에 **같은 snapshot의 `terms`** 문장을 채운다(초안 `learning_term`을 읽지 않는다 — SC-003·SC-006). 예시 판정([research](research.md) R8): `SectorMapPort.currentMap()`이 비어 있으면 `available=false`·`SECTOR_MAP_NOT_READY`·`name=null`, 코드가 하나라도 없으면 `available=false`·`SECTOR_NOT_IN_MAP`·`missingSectorCodes`, 모두 있으면 `available=true`와 대응표의 이름. 판정 결과를 저장하지 않는다. `backend/src/main/java/com/ssafy/thorp/learning/application/exception/ArticleUnavailableException.java`(`BusinessException`) (T011·T015 의존)
- [ ] T017 [US1] (BE) `backend/src/main/java/com/ssafy/thorp/learning/presentation/response/LearningArticleListResponse.java`·`LearningArticleResponse.java`(result → response 변환 record)와 `backend/src/main/java/com/ssafy/thorp/learning/presentation/controller/LearningController.java` — `GET /learning/articles`, `GET /learning/articles/{articleId}`. 다른 컨트롤러와 같은 `ApiResponse` 껍데기. `SecurityConfig`는 `anyRequest().permitAll()`이라 추가 설정이 없다 — 확인만 하고 Javadoc에 "비로그인 허용(계약 머리말, FR-012)"을 적는다. 사용자 식별을 읽지 않는다 (T016 의존)

### Backend 테스트

- [ ] T018 [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearningPublicApiIT.java`(`@SpringBootTest` + `@AutoConfigureMockMvc` + `PostgresTestSupport`, `backtest/application/service/BacktestVersionsApiIT`와 같은 기동 방식)와 시드 도우미 `backend/src/test/java/com/ssafy/thorp/learning/support/LearningSeeds.java`(B02의 게시 서비스가 아직 없으므로 `JdbcTemplate`로 판·색인·글 상태를 직접 심는다. 001 대응표도 같은 방식으로 심는다) — 게시 전 `GET /learning/articles`가 200 `articles: []`(quickstart 2-1); 초안뿐인 `returns-and-losses`는 목록에 없고 단건은 404 `LEARNING_ARTICLE_UNAVAILABLE`(quickstart 2-2, SC-006); 형식이 틀린 `articleId`도 같은 404; 판을 심은 뒤 목록·단건 `data`가 `docs/specs/learning-api/examples/articles-list.json`·`article.json`과 JSON 동치(`BacktestResultApiIT`의 `EXAMPLES` 경로 방식); `term` 블록 문장이 심은 판의 문장과 같고 초안 `learning_term`을 그 뒤 바꿔도 응답이 그대로(SC-006, quickstart 3-5의 서버 쪽); 대응표가 없으면 200에 `example.available=false`·`SECTOR_MAP_NOT_READY`; 대응표에서 코드 하나를 빼면 `SECTOR_NOT_IN_MAP`과 `missingSectorCodes`(quickstart 4-4의 서버 쪽); 목록 순서가 `readingOrder`, `articleId` 순 (T003·T017 의존)
- [ ] T019 [P] [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/application/service/LearningQueryServiceTest.java` — 저장소·포트를 목으로: 없는 글·초안 글·형식 오류가 같은 예외; 예시 판정 세 갈래; `term` 블록 문장이 snapshot에서 오고 초안 용어에서 오지 않는다; 대응표 조회는 단건 조회당 한 번이다 (T016 의존)

### Frontend — 기반 모듈과 컴포넌트

- [ ] T020 [P] [US1] (FE) `frontend/src/api/learning.ts` — `fetchLearningArticles(signal?)` → `LearningListResult`: `{ status: 'loaded'; articles }` \| `{ status: 'empty' }` \| `{ status: 'failed'; kind; code; message }`; `fetchLearningArticle(articleId, signal?)` → `{ status: 'loaded'; article }` \| `{ status: 'unavailable' }`(404 `LEARNING_ARTICLE_UNAVAILABLE`) \| `{ status: 'failed'; … }`. **빈 목록과 실패를 이 층에서 가른다**(`frontend/src/api/sectors.ts`의 `SectorCatalogResult`와 같은 이유). 오류 분류는 `api/client.ts`의 `normalizeApiError`를 그대로 쓴다. 예외를 던지지 않고 재시도하지 않는다. `apiClient` 경유, URL 하드코딩 금지
- [ ] T021 [P] [US1] (FE) `frontend/src/components/learning/ArticleBlocks.tsx`·`ArticleBlocks.module.css` — 블록 목록을 **텍스트 노드로만** 그린다([research](research.md) R3·R13): `heading` → `h2`, `paragraph` → `p`, `term` → 용어 이름(`dt`)과 문장(`dd`), `hypothetical` → 눈에 보이는 '가상의 계산 예' 표시가 붙은 `aside`(FR-005). `dangerouslySetInnerHTML`을 쓰지 않는다. 알 수 없는 `type`은 아무것도 그리지 않고 개발 모드에서만 `console.warn`한다
- [ ] T022 [P] [US1] (FE) `frontend/src/components/learning/LearningLoadFailure.tsx` — `role="alert"`로 불러오지 못했다는 한 문장(서버 `message`가 있으면 그것, 없으면 고정 문장)과 '다시 시도' `button`(`onRetry`). 빈 목록 안내와 다른 모양이어야 하며(SC-007) 스스로 재시도하지 않는다(FR-015)
- [ ] T023 [US1] (FE) `frontend/src/pages/LearningListPage.tsx`·`LearningListPage.module.css` — `/learn`. `AppHeader` → 목록 조회 → 상태별 화면: `loaded`는 응답 순서 그대로(정렬하지 않는다) 제목·질문과 `/learn/{articleId}` `Link`; `empty`는 "아직 게시된 글이 없습니다" 한 문장(`alert`가 아니다)과 진단 시작 `Link`(`/diagnosis/sectors`); `failed`는 `LearningLoadFailure`와 **그대로 남는** 진단 시작 `Link`(spec Edge Cases). 설문·숙련도 질문·진도 표시가 없다(FR-011·FR-012). `sessionStorage`·`localStorage`에 아무것도 쓰지 않는다. 다시 시도는 버튼을 누를 때만 다시 부른다 (T020·T022 의존)
- [ ] T024 [US1] (FE) `frontend/src/pages/LearningArticlePage.tsx`·`LearningArticlePage.module.css` — `/learn/:articleId`. `loaded`: 제목(`h1`)·질문·`ArticleBlocks`·예시 절(섹터 이름 또는 코드와 비중을 `weightTenths / 10`을 소수 한 자리 %로 표시, "설명을 위한 예시이며 투자 권고가 아닙니다" 문장 — US1 시나리오 4)·출처 목록(`label`, `url`이 있으면 `a`)·`publishedAt`. 예시 절 아래에 US2의 `ExampleEntry` 자리, 머리에 US3의 `ReturnToDiagnosisLink` 자리를 비워 둔다. `unavailable`: 오류 화면이 아니라 "지금은 볼 수 없는 글입니다" 한 문장과 `/learn` `Link`(spec Edge Cases). `failed`: `LearningLoadFailure`. 글 안의 다른 링크(`/learn`)에는 받은 `location.state`를 그대로 넘긴다([term-help-ui](contracts/term-help-ui.md) §3). 저장소에 쓰지 않는다 (T020·T021·T022 의존)
- [ ] T025 [US1] (FE) `frontend/src/App.tsx` — `/learn`의 `NotReadyPage`를 lazy `LearningListPage`로 바꾸고 `/learn/:articleId`에 lazy `LearningArticlePage`를 더한다. `NotReadyPage`를 쓰는 경로가 더 없으면 그 lazy import를 지운다(파일은 남긴다 — 다른 spec의 준비 중 메뉴가 다시 쓸 수 있다). 주석에 "013 배우기. 글의 예시는 002 `/diagnosis/sectors`로 넘어간다"를 적는다. `AppHeader`의 '배우기' 항목은 이미 `/learn`이라 바꾸지 않는다 (T023·T024 의존)

### Frontend 테스트

- [ ] T026 [P] [US1] (FE 테스트) `frontend/src/api/learning.test.ts` — 200 목록 → `loaded`, 200 빈 배열 → `empty`, 404 `LEARNING_ARTICLE_UNAVAILABLE` → `unavailable`, 다른 404·5xx·네트워크 → `failed`이고 `code`가 서버 값 그대로; 예외를 던지지 않는다; 요청은 호출당 한 번. `frontend/vite.config.ts` `test.include`에 `'src/api/learning.test.ts'`를 더한다 (T020 의존)
- [ ] T027 [P] [US1] (FE 테스트) `frontend/src/components/learning/ArticleBlocks.test.tsx` — `heading`이 `h2`; `term` 블록에 이름과 문장이 **받은 그대로**; `hypothetical` 블록에 '가상의 계산 예' 텍스트가 보인다; `text`에 `<b>강조</b>`를 넣으면 태그 문자열이 그대로 보이고 `b` 요소가 생기지 않는다(R3) (T021 의존)
- [ ] T028 [US1] (FE 테스트) `frontend/src/pages/LearningListPage.test.tsx`(`frontend/src/test/renderWithRouter.tsx`, `learningFixtures`) — 응답 순서 그대로 보인다; `empty`면 `alert`가 없고 진단 시작 링크가 있다; `failed`면 `role="alert"`와 '다시 시도' 버튼이 있고 진단 시작 링크가 그대로 있으며 빈 목록 문장이 없다(SC-007, quickstart 4-2); 다시 시도를 누르기 전에는 두 번째 요청이 없고 누르면 한 번 더 부른다; 설문·숙련도 문구가 없다(시나리오 5) (T023·T025 의존)
- [ ] T029 [US1] (FE 테스트) `frontend/src/pages/LearningArticlePage.test.tsx` — 질문 하나와 블록이 보인다; 예시 비중이 % 표기로 보이고 투자 권고가 아니라는 문장이 있다(시나리오 4); 출처 `url`이 `a`로; `unavailable`이면 한 문장과 목록 링크가 있고 `alert`가 없다(quickstart 3-8 화면 쪽); `failed`면 `alert`와 다시 시도; 다시 시도 버튼이 `button`이고 `Tab`으로 닿는다(FR-019) (T024·T025 의존)

**Checkpoint**: 목록과 글이 응답만으로 그려지고, 초안은 어디에도 나오지 않으며, 실패와 빈 목록이 다르게 보인다

---

## Phase 4: User Story 2 - 예시로 바로 진단을 시작한다 (Priority: P1)

**Goal**: 글의 예시 섹터·비중을 002 계약 그대로 navigation state에 실어 `/diagnosis/sectors`로 같은 탭 이동한다. 예시 섹터가 대응표에 없으면 버튼 대신 안내와 빈 화면 연결을 보인다.

**Independent Test**: 글에서 '이 예시로 비중 비교하기'를 눌렀을 때 `navigate`가 002 계약 §2 모양의 state로 불리는지, `available=false`면 버튼이 없고 안내와 빈 섹터 화면 연결이 있는지 확인한다(quickstart 4-3의 013 쪽·4-4). 002가 그 값을 받아 채우는 확인은 002 T020 뒤다(위 ②).

- [ ] T030 [US2] (FE) `frontend/src/components/learning/ExampleEntry.tsx`·`ExampleEntry.module.css` — props `articleId`·`example: LearningExample`. `available=true`: '이 예시로 비중 비교하기' `button` 하나(글 화면의 유일한 행동 버튼, 원칙 II) → `navigate('/diagnosis/sectors', { state: { sectorSelectionExample: { source: 'learning', topicId: articleId, selected: sectors.map(({ code, weightTenths }) => ({ code, weightTenths })) } } })` — `weightTenths`를 **변환하지 않고** 싣고 `name`은 싣지 않는다([term-help-ui](contracts/term-help-ui.md) §4). `available=false`: 버튼을 그리지 않고 `unavailableReason`별 한 문장(`SECTOR_NOT_IN_MAP`은 `missingSectorCodes`를 함께, `SECTOR_MAP_NOT_READY`는 대응표를 확인할 수 없다는 문장)과 state 없는 `Link`(`/diagnosis/sectors`) "빈 섹터 선택 화면으로 가기"(US2 시나리오 3). 덮어쓰기 확인은 002가 한다 — 여기서 묻지 않는다(FR-003)
- [ ] T031 [US2] (FE) `frontend/src/pages/LearningArticlePage.tsx`의 예시 절 자리(T024)에 `ExampleEntry`를 넣는다. 예시가 없는 판(`example` 없음)은 게시 전 검사가 막으므로 화면은 방어 코드 없이 `example`을 필수로 다룬다 (T030 의존)
- [ ] T032 [US2] (FE 테스트) `frontend/src/components/learning/ExampleEntry.test.tsx` — 버튼을 누르면 `navigate`가 `/diagnosis/sectors`와 정확히 `{ sectorSelectionExample: { source: 'learning', topicId, selected: [{ code, weightTenths }] } }` 모양으로 불리고 `weightTenths`가 응답 값 그대로이며 `name` 키가 없다; `available=false`면 버튼이 없고 안내 문장과 `/diagnosis/sectors` 링크(state 없음)가 있다; `SECTOR_NOT_IN_MAP`이면 빠진 코드가 문장에 있다; 버튼은 `button` 요소다 (T030 의존)

**Checkpoint**: 글에서 예시가 002 계약 모양으로 넘어가고, 넘길 수 없을 때 화면이 그 사실을 말한다

---

## Phase 5: User Story 3 - 진단 화면의 용어 도움말이 같은 설명을 쓴다 (Priority: P2)

**Goal**: 용어 설명 단건 조회를 열고, 002·003·005가 함께 쓰는 `TermHelp`가 세 상태(준비됨·설명 준비 중·불러오지 못함)를 코드로 가르며, 글로 갔다가 '하던 진단으로 돌아가기'로 돌아온다.

**Independent Test**: 게시된 `termId`·초안뿐인 `termId`·요청 차단의 세 경우에 `TermHelp`가 다른 문장을 보이고 호출한 화면의 입력을 건드리지 않는지, `/strategy`에서 온 글에만 돌아가기 연결이 보이는지 확인한다(quickstart 5-1·5-2·5-4). 003 화면이 아직 공용 컴포넌트를 쓰지 않으면 컴포넌트 테스트로 확인한다(quickstart §5 머리말).

### Backend — 용어 설명 조회 (계약 §3)

- [ ] T033 [US3] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/result/LearningTermResult.java`(`termId`·`label`·`sentence`·`articleId`·`articleTitle`)와 `LearningQueryService.getPublishedTerm(String termId)` — `PublishedTermRepository.findPublished`로 **게시 용어 색인만** 읽는다. 형식 오류·초안뿐인 용어·게시 취소된 글의 용어·없는 용어를 가르지 않고 `TermNotReadyException`(404 `LEARNING_TERM_NOT_READY`)이다([research](research.md) R10). `backend/src/main/java/com/ssafy/thorp/learning/application/exception/TermNotReadyException.java` (T011 의존)
- [ ] T034 [US3] (BE) `LearningController`에 `GET /learning/terms/{termId}`와 `backend/src/main/java/com/ssafy/thorp/learning/presentation/response/LearningTermResponse.java`. 여러 `termId`를 한 번에 받는 조회는 두지 않는다(계약 §4) (T033 의존)
- [ ] T035 [US3] (BE 테스트) `LearningPublicApiIT` 확장 — 심은 판의 용어는 200이고 `data`가 `docs/specs/learning-api/examples/term.json`과 동치; 그 `sentence`가 같은 글 응답의 `term` 블록 문장과 같다(SC-003); 초안뿐인 `rebalancing`은 404 `LEARNING_TERM_NOT_READY`(quickstart 2-2); 없는 `termId`와 형식이 틀린 `termId`도 같은 코드; 색인 행을 지우면(게시 취소의 서버 결과) 같은 404 (T003·T034 의존)

### Frontend — 공용 도움말 (B04)

- [ ] T036 [P] [US3] (FE) `frontend/src/api/learningGlossary.ts` — `fetchGlossaryEntry(termId: string, signal?: AbortSignal): Promise<GlossaryLookup>`, `GlossaryLookup = { status: 'ready'; entry: LearningTermEntry } | { status: 'notReady' } | { status: 'failed' }`([term-help-ui](contracts/term-help-ui.md) §1). 404 `LEARNING_TERM_NOT_READY`만 `notReady`이고 그 밖의 모든 실패(다른 404 코드 포함)는 `failed`다. 예외를 던지지 않고 재시도하지 않는다. `apiClient` 경유. 003 T057a가 이 파일에 대역을 먼저 두었으면 **본문만 바꾸고 export 이름을 유지한다**(위 ③)
- [ ] T037 [P] [US3] (FE) `frontend/src/components/learning/ReturnToDiagnosisLink.tsx`와 순수 함수 `resolveLearningReturn(state: unknown): LearningReturnState | null` — 세 조건을 모두 만족할 때만 값을 돌려준다([term-help-ui](contracts/term-help-ui.md) §3): `state.learningReturn`이 있고 `path`·`label`이 문자열; `path`가 `/diagnosis/sectors`·`/strategy`이거나 `/results/`로 시작; `normalizeReturnTarget(path) === path`(`frontend/src/auth/returnTarget.ts`, 014 소유 — 고치지 않는다). 컴포넌트는 값이 있을 때만 보조 연결 `a`(React Router `Link`)를 그리고 누르면 `navigate(path)`한다. `history.back()`을 쓰지 않는다. '이 예시로 비중 비교하기'와 같은 크기의 버튼 스타일을 쓰지 않는다(FR-009, 원칙 II)
- [ ] T038 [US3] (FE) `frontend/src/components/learning/TermHelp.tsx`·`TermHelp.module.css` — props `termId`·`label`·`returnTo`(모두 필수). `radix-ui` 패키지의 `Popover`(`import { Popover } from 'radix-ui'`, 새 라이브러리 없음)로 만들고 트리거는 `label`을 보이는 `button`이다. **여는 행동이 요청을 시작한다**(마운트 때 부르지 않는다). 상태별 표시([term-help-ui](contracts/term-help-ui.md) §2): 여는 중은 짧은 텍스트; `ready`는 이름·문장(받은 그대로)·`Link`(`/learn/${articleId}`, `state: { learningReturn: returnTo }`, 같은 탭); `notReady`는 '설명 준비 중' 일반 텍스트, 연결 없음; `failed`는 `role="alert"` 한 문장과 '다시 시도' `button`(누를 때만 다시 부른다). Esc로 닫히고 닫으면 초점이 트리거로 돌아온다(radix 기본). 호출한 화면의 상태를 바꾸는 prop·callback을 두지 않는다(FR-009·FR-010) (T036 의존)
- [ ] T039 [US3] (FE) `frontend/src/pages/LearningArticlePage.tsx`의 머리 자리(T024)에 `ReturnToDiagnosisLink`를 넣고, 글 안의 `/learn` 링크가 `location.state`를 그대로 넘기는지 확인한다. 배우기 메뉴로 직접 들어오면 state가 없어 연결이 보이지 않는다(spec Edge Cases) (T037 의존)

### Frontend 테스트

- [ ] T040 [P] [US3] (FE 테스트) `frontend/src/api/learningGlossary.test.ts` — 200 → `ready`; 404 `LEARNING_TERM_NOT_READY` → `notReady`; 404 다른 코드·5xx·네트워크·시간 초과 → `failed`; 어느 경우에도 reject되지 않는다; 요청은 호출당 한 번. `test.include`에 `'src/api/learningGlossary.test.ts'` 추가 (T036 의존)
- [ ] T041 [P] [US3] (FE 테스트) `frontend/src/components/learning/ReturnToDiagnosisLink.test.tsx` — `resolveLearningReturn`: state 없음 → null; `path: '/login'` → null; `'//evil.example'` → null; `'/strategy'`·`'/diagnosis/sectors'`·`'/results/BT-1'` → 값; 컴포넌트는 값이 있을 때만 링크를 그리고 누르면 `navigate`가 그 `path`로 불린다; 요소가 `a`다 (T037 의존)
- [ ] T042 [US3] (FE 테스트) `frontend/src/components/learning/TermHelp.test.tsx`(`@testing-library/user-event`) — 열기 전에는 요청이 0건; 트리거를 누르면 한 번 부른다; `ready`면 문장이 받은 그대로 보이고 링크 `href`가 `/learn/{articleId}`이며 `navigate` state에 `learningReturn`이 실린다; `notReady`면 '설명 준비 중'과 링크 없음; `failed`면 `alert`와 다시 시도이고 누르기 전에는 두 번째 요청이 없다; `Escape`로 닫히고 초점이 트리거로 돌아온다(quickstart 5-4); 트리거는 `button`; 부모 컴포넌트의 입력 상태가 열고 닫는 동안 그대로다(US3 시나리오 3) (T038 의존)

**Checkpoint**: 도움말이 세 상태를 코드로 가르고, 글로 갔다가 돌아오는 길이 허용 목록 안에서만 열린다

---

## Phase 6: User Story 4 - 학습 없이도 진단을 시작할 수 있다 (Priority: P3)

**Goal**: 배우기가 진단의 전제가 아니고, 진도·열람 기록을 남기지 않는다는 것을 확인한다. 다른 화면의 동작이므로 확인 항목에 가깝다(spec "Why this priority").

**Independent Test**: 배우기 화면이 저장소에 아무것도 쓰지 않고, 진단 진입 경로 어디에도 학습 요구가 없는지 확인한다(quickstart 4-1 마지막 항목, 5-2).

- [ ] T043 [US4] (FE 테스트) `frontend/src/pages/LearningListPage.test.tsx`·`LearningArticlePage.test.tsx` 확장 — `Storage.prototype.setItem`을 스파이로 걸고 목록·글을 열고 닫는 동안 호출이 0건(FR-012, quickstart 4-1); "학습 완료"·"수강" 같은 문구와 진도 표시 요소가 없다(시나리오 1·3) (T028·T029 의존)
- [ ] T044 [US4] (FE) 진단 진입에 학습 요구가 없음을 확인만 한다 — `grep -rn "learn" frontend/src/pages/SectorSelectionPage.tsx frontend/src/pages/StrategySettingsPage.tsx frontend/src/auth/ProtectedRoute.tsx`가 배우기 상태를 읽는 코드 0건이고, `frontend/src/components/navigation/AppHeader.tsx`의 '배우기' 항목이 일반 메뉴 링크 그대로다(FR-011, SC-004). 고칠 것이 없으면 결과만 MR 본문 "테스트" 절에 적는다. 013이 002·003 화면을 고치지 않는다

**Checkpoint**: 네 스토리(US1·US2·US3·US4)가 관리 화면 없이 동작한다. 실제 글은 B02·B05가 서기 전까지 통합 테스트로 심은 판으로만 보인다

---

## Phase 7: User Story 5 - 서비스 운영자가 관리 계정으로 글과 용어 설명을 편집·게시한다 (Priority: P2)

**Goal**: 관리 권한 판정 한 곳, 관리 일곱 엔드포인트, 서버의 게시 전 검사 아홉 가지, 게시·게시 취소 트랜잭션, 관리 화면. 초안은 어떤 경로로도 공개되지 않는다.

**Independent Test**: 허용 목록의 계정으로 로그인해 `returns-and-losses`를 검사에 걸리게 저장·게시하면 항목 셋이 위치와 함께 돌아오고 아무것도 바뀌지 않으며, 고쳐서 게시하면 로그아웃한 탭의 목록·글·도움말에 함께 보이고, 게시 취소하면 셋에서 함께 사라지는지 확인한다(quickstart 3-1~3-8). 비로그인·권한 없는 로그인·CSRF 누락 요청이 각각 401·404·403인지 확인한다.

### Backend — 권한과 설정 (B02)

- [ ] T045 [P] [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/port/LearningAdminPolicy.java`(`boolean isAdmin(long userId)`)와 구현 `backend/src/main/java/com/ssafy/thorp/learning/infrastructure/admin/ConfiguredLearningAdminPolicy.java` — 설정 `thorp.learning.admin-user-ids`(쉼표 구분 사용자 ID 목록)를 읽는다. 비어 있으면 아무도 관리자가 아니다([research](research.md) R5). `backend/src/main/resources/application.yml`에 `thorp.learning.admin-user-ids: ${THORP_LEARNING_ADMIN_USER_IDS:}`(주석: 비어 있으면 관리자 없음. 014가 역할을 주면 이 구현 한 클래스만 바꾼다), `application-prod.yml`에는 `${THORP_LEARNING_ADMIN_USER_IDS}`로 **기본값 없이** 둔다(`thorp.backtest.limits.*`와 같은 이유 — 운영자가 값을 명시해야 한다. 명시한 빈 값은 관리자 없음이다). `backend/src/test/java/com/ssafy/thorp/common/config/ApplicationProfileConfigTest.java`의 prod 러너에 그 환경 변수를 더한다
- [ ] T046 [P] [US5] (BE) `backend/src/main/java/com/ssafy/thorp/common/config/SecurityConfig.java` — `/auth/me` 옆에 `.requestMatchers("/admin/learning/**").authenticated()` 한 줄. 비로그인은 기존 진입점이 401 `AUTHENTICATION_REQUIRED` 봉투를 낸다. 상태 변경 요청의 CSRF는 기존 설정이 그대로 적용된다([learning-admin-api](contracts/learning-admin-api.md) §0)
- [ ] T047 [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/presentation/support/LearningAdminAccess.java`(`@Component`) — `long requireAdminUserId()`: 인증 주체를 004 `backtest/presentation/support/CurrentRequester`와 **같은 규약**(세션의 `users.id` 문자열)으로 읽고, `LearningAdminPolicy.isAdmin`이 거짓이면 `BusinessException(ErrorCode.RESOURCE_NOT_FOUND)`(404, 경로의 존재를 알리지 않는다 — [research](research.md) R6). `CurrentRequester`를 import하지 않는다(`learning → backtest` 의존을 만들지 않는다). Javadoc에 "014가 역할을 제공하면 이 클래스와 `ConfiguredLearningAdminPolicy`만 바꾼다"를 적는다. 관리 컨트롤러의 모든 메서드가 첫 줄에서 이것을 부른다 (T045 의존)

### Backend — 게시 전 검사 (B02)

- [ ] T048 [P] [US5] (INFRA) `backend/src/main/resources/learning/publish-checks-v1.yml` — `version: learning-publish-checks-v1`, `forbiddenExpressions`(다섯 무리, [data-model](data-model.md) §6-1의 출발 목록 그대로. `보장` 단독은 넣지 않는다), `resultNumberPatterns`(§6-2의 셋: 숫자+`%`/`%p`, 숫자+`원`/`만 원`/`억 원`/`억`, 숫자+`배`), `allowedExceptions: ["100%"]`. 파일 머리 주석에 "출발안이다. 목록을 바꾸면 파일 이름과 `version`을 함께 올리고 옛 파일은 지우지 않는다(판이 버전을 기록한다)"를 적는다
- [ ] T049 [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/port/PublishCheckRulesPort.java`(`PublishCheckRules current()`)와 구현 `backend/src/main/java/com/ssafy/thorp/learning/infrastructure/rules/YamlPublishCheckRulesLoader.java` — 기동 시 클래스패스의 `learning/publish-checks-v1.yml`을 Jackson YAML(003 `FileParameterRegistry`와 같은 방식)로 읽어 `PublishCheckRules`를 만든다. 파일이 없거나 형식이 틀리면 **기동 실패**(빈 목록으로 대신하지 않는다 — 원칙 VI) (T048·T050 의존)
- [ ] T050 [P] [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/domain/model/PublishCheckRules.java`(`version`·금지 표현·숫자 패턴·허용 예외), `PublishCheckFailure.java`(`check`·`CheckLocation location`·`matched`(nullable)·`message`), `CheckLocation.java`(`part`: `title`·`question`·`example`·`block`(+`index`)·`term`(+`termId`)), `PublishCheckResult.java`(`failures` 목록, 비어 있으면 통과), `PublishCheck.java` — `PublishCheckResult run(LearningArticle draft, PublishCheckRules rules, Optional<Set<String>> openSectorCodes)`인 **순수 함수**. [research](research.md) R7 표의 아홉 검사를 **모두 실행하고 걸린 항목을 전부** 돌려준다: `REQUIRED_FIELDS`(제목·질문 비었거나 `paragraph` 없음), `EXAMPLE_PRESENT`, `EXAMPLE_SECTOR_COUNT`(< 2), `EXAMPLE_SECTOR_IN_MAP`(`openSectorCodes`가 비어 있으면 "대응표를 확인할 수 없다"는 `message`로 걸린다 — 통과로 세지 않는다), `EXAMPLE_WEIGHT_SUM`(≠ 1000, `message`에 지금 합계를 %로), `TERM_BLOCKS_MATCH`(이 글의 용어가 `term` 블록으로 정확히 한 번씩, 블록이 없는 용어를 가리키지 않음), `TERM_SENTENCE`(이름·문장 비었거나 300자 초과), `FORBIDDEN_EXPRESSION`(공백을 하나로 줄인 문자열에서 부분 일치, 위치와 `matched`), `RESULT_NUMBER_OUTSIDE_HYPOTHETICAL`(`hypothetical` 밖 텍스트의 숫자 패턴, `100%`는 예외). `message`는 계약 §6 예시 문장을 쓴다. `backend/src/main/java/com/ssafy/thorp/learning/domain/exception/PublishCheckFailedException.java` — `BusinessException(LEARNING_PUBLISH_CHECK_FAILED, …, Map.of("checkVersion", v, "failures", […]))`. 대응표 조회는 하지 않는다 (T009 의존)

### Backend — 관리 유스케이스와 엔드포인트 (B02)

- [ ] T051 [P] [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/command/`: `CreateArticleCommand`(`articleId`·`title`·`readingOrder`), `SaveDraftCommand`(`expectedDraftVersion`·`title`·`question`·`readingOrder`·`blocks`·`example`(nullable)·`sources`·`terms`), `PublishCommand`(`expectedDraftVersion`·`actorUserId`), `UnpublishCommand`(`actorUserId`); `backend/src/main/java/com/ssafy/thorp/learning/application/result/`: `AdminArticleSummaryResult`(계약 §2 필드), `AdminArticleResult`(§4), `PublishResult`(§6 200), `UnpublishResult`(§7)
- [ ] T052 [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/service/LearningAdminService.java` — `@Transactional` 유스케이스 여섯([learning-admin-api](contracts/learning-admin-api.md)):
  - `list()`: 모든 글을 `readingOrder`, `articleId` 순으로. `hasUnpublishedChanges = status == PUBLISHED && article.draftVersion != publishedRevision.snapshot.draftVersion`; `needsUpdate`는 공개 중인 판의 예시 코드를 `SectorMapPort`와 대조(초안 글은 `false`, 대응표를 읽지 못하면 `null`); `lastPublication`은 `PublicationEventRepository.findLastByArticleId`(없으면 `null`). `actorUserId`만 싣는다
  - `create(cmd)`: `existsByArticleKey`면 `ArticleIdTakenException`(409); 새 글은 `DRAFT`·`draftVersion 0`·빈 본문
  - `getDraft(articleId)`: 없으면 `ArticleNotFoundException`(404). `publishedRevisionNo`는 공개 판 번호 또는 `null`
  - `saveDraft(articleId, cmd)`: `findByArticleKeyForUpdate` → `refresh` → 요청 `terms[].termId`를 다른 글이 쓰면 `TermIdTakenException`(409, `details.termId`·`ownerArticleId`) → `article.saveDraft(...)`(버전 불일치는 `DraftChangedException`) → 용어 행을 통째로 바꾼다(`replaceForArticle`). 공개 내용은 바뀌지 않는다
  - `publish(articleId, cmd)`: 잠금 → 버전 확인 → `SectorMapPort.currentMap()` → `PublishCheck.run(...)` → 걸리면 `PublishCheckFailedException`(422, **아무것도 바꾸지 않는다** — 판·이벤트를 남기지 않는다) → 통과면 같은 트랜잭션에서: 새 `ArticleRevision`(`revisionNo = 마지막 + 1`, 게시 취소 뒤에도 이어서 오른다, snapshot에 `draftVersion` 포함, `checkVersion`·`sectorMapVersion`·`publishedByUserId`), `article.publish(...)`, `PublishedTermRepository.replaceForArticle`, `PUBLISH` 이벤트. `PublishResult` 반환
  - `unpublish(articleId, cmd)`: 잠금 → `PUBLISHED`면 `article.unpublish()`·`deleteForArticle`·`UNPUBLISH` 이벤트(`revisionId`는 내려간 판) → `unpublishedRevisionNo`; 이미 `DRAFT`면 아무것도 바꾸지 않고 `null`로 200, 이벤트 없음
  - `backend/src/main/java/com/ssafy/thorp/learning/application/exception/ArticleNotFoundException.java`·`ArticleIdTakenException.java`·`TermIdTakenException.java`(`BusinessException`). 삭제 유스케이스는 없다(spec Out of Scope) (T011·T047·T049·T050·T051 의존)
- [ ] T053 [US5] (BE) 요청·응답·컨트롤러 — `backend/src/main/java/com/ssafy/thorp/learning/presentation/request/`: `CreateArticleRequest`(`@Pattern` `articleId`, `@Size` `title` ≤100, `@Min(1) @Max(999)` `readingOrder`), `SaveDraftRequest`(`@NotNull expectedDraftVersion`, 길이·개수 검사, `blocks[]`는 `type`으로 판별하는 Jackson 다형 record(`@JsonTypeInfo(use = NAME, property = "type")` + `@JsonSubTypes` 넷) — 알 수 없는 `type`은 역직렬화 실패로 400이 된다(T054), `example`은 `null` 허용이고 `sectors[]` ≤20·`weightTenths` 0..1000·코드 중복 없음, `sources[].url`은 `http`·`https`만, `terms[].termId` 형식), `PublishRequest`(`@NotNull expectedDraftVersion`); `presentation/response/`: `AdminAccessResponse`(`{ admin: true }`), `AdminArticleListResponse`, `AdminArticleResponse`, `PublishResponse`, `UnpublishResponse`; `backend/src/main/java/com/ssafy/thorp/learning/presentation/controller/LearningAdminController.java` — `GET /admin/learning/access`(200 `{admin:true}`; 권한이 없으면 §0의 404이지 `admin:false`가 아니다), `GET /admin/learning/articles`, `POST /admin/learning/articles`(201), `GET /admin/learning/articles/{articleId}`, `PUT /admin/learning/articles/{articleId}`, `POST /admin/learning/articles/{articleId}/publish`, `POST /admin/learning/articles/{articleId}/unpublish`(본문 없음). 모든 메서드가 `LearningAdminAccess.requireAdminUserId()`로 시작한다. `@Valid` 필수 (T047·T052 의존)
- [ ] T054 [US5] (BE) `backend/src/main/java/com/ssafy/thorp/common/web/advice/GlobalExceptionHandler.java` 보강 둘 — (a) `handleMethodArgumentNotValid`의 400 봉투에 `details.fields`(`[{ field, message }]` 전체)를 더한다(계약 §5 "`error.details.fields`에 위반 위치"). `details`는 `NON_NULL`이라 다른 도메인의 400에는 키가 추가될 뿐이지만, `grep -rn "details" backend/src/test`로 400 본문 전체를 대조하는 테스트가 있는지 확인하고 있으면 기대값을 맞춘다; (b) `handleHttpMessageNotReadable`을 override해 `ApiResponse.fail(ErrorResponse.of(INVALID_INPUT, "본문을 읽을 수 없습니다: …"))` 400을 낸다 — 지금은 override가 없어 스프링 기본 ProblemDetail 본문이 나가므로 알 수 없는 블록 `type`이 봉투 없는 응답이 된다. 조용히 버리지 않고 거절한다는 것이 목적이다([data-model](data-model.md) §4, 원칙 VI). 두 변경 모두 MR 본문 "리뷰 포인트"에 공통 계층 변경임을 적는다

### Backend 테스트 (B02)

- [ ] T055 [P] [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/domain/model/PublishCheckTest.java` — 아홉 검사가 각각만 걸리는 초안 아홉과 모두 통과하는 초안 하나; quickstart 3-2의 초안(비중 50.0·45.0, `paragraph`에 `추천 비중`, 용어 문장에 `-30%`)에서 `EXAMPLE_WEIGHT_SUM`·`FORBIDDEN_EXPRESSION`·`RESULT_NUMBER_OUTSIDE_HYPOTHETICAL` 셋이 **모두** 돌아오고 위치가 계약 §6 예시와 같다; `openSectorCodes`가 비어 있으면 `EXAMPLE_SECTOR_IN_MAP`(quickstart 3-4); `hypothetical` 안의 `120만 원`은 걸리지 않고 `paragraph`의 `100%` 하나는 예외; 제목 `과거가 미래를 보장하지 않는 이유`는 걸리지 않는다; 공백을 둘 넣은 `추천  비중`도 걸린다 (T050 의존)
- [ ] T056 [P] [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/rules/YamlPublishCheckRulesLoaderTest.java` — 실제 파일이 읽히고 `version`이 `learning-publish-checks-v1`이며 표현·패턴 수가 파일과 같다; 합성 YAML(`ByteArrayResource`)에서 `version` 누락·패턴 문법 오류가 기동 실패로 이어진다 (T049 의존)
- [ ] T057 [P] [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/admin/ConfiguredLearningAdminPolicyTest.java` — 빈 값이면 모두 거짓; `"1, 2"`처럼 공백이 있어도 파싱; 숫자가 아닌 항목은 기동 실패 (T045 의존)
- [ ] T058 [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearningAdminAccessIT.java`(`@SpringBootTest` + `@AutoConfigureMockMvc` + `PostgresTestSupport`, `thorp.learning.admin-user-ids=7`) — 비로그인 `GET /admin/learning/access`·`GET /admin/learning/articles`가 401 `AUTHENTICATION_REQUIRED`; `support/Requesters.as(8)`(허용 목록에 없음)은 일곱 엔드포인트 모두 404 `RESOURCE_NOT_FOUND`이고 `admin:false` 200이 없다; `as(7)`의 `PUT`·`POST`에 `csrf()`가 없으면 403 `CSRF_TOKEN_INVALID`; `as(7)`의 `access`는 200 `{admin:true}`(quickstart 1 "권한", 3-1) (T046·T047·T053 의존)
- [ ] T059 [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearningAdminApiIT.java`(같은 기동 방식, `as(7)` + `csrf()`, `LearningSeeds`로 001 대응표를 심는다) — 목록이 초기 자리 아홉이고 모두 `DRAFT`·`lastPublication: null`; 만들기 201과 같은 `articleId` 409 `LEARNING_ARTICLE_ID_TAKEN`, `Bad_Key` 400; 없는 글 404 `LEARNING_ARTICLE_NOT_FOUND`; 저장 200에 `draftVersion` 1 증가, 옛 `expectedDraftVersion` 409 `LEARNING_DRAFT_CHANGED`와 `details.currentDraftVersion`(quickstart 1 "덮어쓰기 방지"), 다른 글의 `termId` 409 `LEARNING_TERM_ID_TAKEN`과 `details.termId`·`ownerArticleId`, 알 수 없는 블록 `type` 400 봉투, 요청에서 뺀 용어가 초안에서 빠진다; quickstart 3-2 초안 게시 → 422이고 `error`가 `docs/specs/learning-api/examples/publish-check-failed.json`과 동치, 판·이벤트 행이 0이고 공개 조회가 그대로 404; 고친 초안 게시 → 200이 `publish-success.json` 모양(`revisionNo 1`)이고 같은 요청 직후 공개 목록·글·용어가 새 판을 본다(quickstart 3-3, SC-003), 관리 목록 `lastPublication.actorUserId`가 7(FR-018); 게시 뒤 문단만 저장하면 공개 응답이 그대로이고 `hasUnpublishedChanges: true`(quickstart 3-5, SC-006); 대응표에서 예시 코드를 빼면 `needsUpdate: true`, 대응표 행을 지우면 `null`; 게시 취소 200 `unpublishedRevisionNo 1` 뒤 공개 글·용어가 404이고 이벤트가 `UNPUBLISH`(quickstart 3-8); 한 번 더 취소하면 200 `null`이고 이벤트 수가 그대로; 다시 게시하면 `revisionNo 2`; 대응표가 없는 DB에서 게시하면 `EXAMPLE_SECTOR_IN_MAP`(quickstart 3-4) (T003·T053·T054 의존)

### Frontend — 관리 API와 편집기 부품 (B05)

- [ ] T060 [P] [US5] (FE) `frontend/src/api/learningAdmin.ts` — `checkAdminAccess()` → `'admin' | 'denied'(404) | 'unauthenticated'(401) | 'failed'`; `fetchAdminArticles()`, `createArticle(req)`(409 → `{ status: 'idTaken' }`), `fetchAdminArticle(articleId)`, `saveDraft(articleId, req)` → `saved` \| `draftChanged`(409, `currentDraftVersion`) \| `termIdTaken`(409, `termId`·`ownerArticleId`) \| `invalid`(400, `details.fields`) \| `unauthenticated` \| `failed`; `publishArticle(articleId, expectedDraftVersion)` → `published(PublishResult)` \| `checkFailed(checkVersion, failures)`(422) \| `draftChanged` \| `unauthenticated` \| `failed`; `unpublishArticle(articleId)`. 서버 `message`·`details`를 그대로 보관한다. CSRF 헤더는 `apiClient`가 쿠키에서 붙인다. 401은 `api/client.ts`의 인증 필요 리스너(014)가 함께 받는다
- [ ] T061 [P] [US5] (FE) `frontend/src/components/learning/admin/adminDraftStorage.ts` — `sessionStorage` 키 `thorp.learning.adminDraft.{articleId}`([research](research.md) R12): `saveUnsentDraft`·`loadUnsentDraft`·`clearUnsentDraft`, 페이로드에 `version: 1`과 `savedAt`. 읽기·쓰기 모두 `try/catch`로 실패해도 되는 것으로 다룬다(002 `diagnosis/selectionStorage.ts`와 같은 태도)
- [ ] T062 [P] [US5] (FE) `frontend/src/components/learning/admin/BlockEditor.tsx`·`BlockEditor.module.css` — 블록 목록 편집: 네 종류 추가, `text`는 `textarea`, `term` 블록의 `termId`는 이 글의 용어 목록에서 고르는 `select`(용어 이름을 함께 보인다), 위·아래 이동, 삭제, 종류별 길이 안내([data-model](data-model.md) §4). 판정은 서버가 한다 — 화면은 안내만 하고 저장을 막지 않는다
- [ ] T063 [P] [US5] (FE) `frontend/src/components/learning/admin/ExampleEditor.tsx`·`ExampleEditor.module.css` — `frontend/src/api/sectors.ts`의 `fetchSectors`로 받은 열린 대응표에서 섹터를 고르고(`code`), 비중은 소수 한 자리 %로 입력받아 `weightTenths` 정수로 보관한다(002 계약 "값 표현 규칙"). 행 추가·삭제, 합계 표시, "합계가 100%가 아니면 게시에서 거절됩니다" 안내. 대응표가 `mapNotReady`·`failed`면 새로 고를 수 없다는 한 문장을 보이고 기존 행은 그대로 둔다. 예시 비우기(`null`)를 허용한다
- [ ] T064 [P] [US5] (FE) `frontend/src/components/learning/admin/TermListEditor.tsx`·`TermListEditor.module.css` — 용어 행: `termId`(새 행만 입력 가능하고 저장된 행은 읽기 전용 — 만든 뒤 바꾸지 않는다, [research](research.md) R4), `label`(≤50), `sentence`(≤300 안내), 삭제. 저장 요청은 목록을 통째로 보내므로 삭제한 행은 다음 게시 때 공개에서도 빠진다는 안내 한 줄
- [ ] T065 [P] [US5] (FE) `frontend/src/components/learning/admin/PublishCheckResultList.tsx` — `role="alert"` 목록. 항목마다 `check`, 위치(`title`/`question`/`example`은 그 이름, `block`은 "본문 {index + 1}번째 블록", `term`은 "용어 {termId}"), `matched`(있을 때), `message`를 **그대로**. 문장을 새로 짓지 않는다(계약 §6)

### Frontend — 관리 화면 (B05)

- [ ] T066 [US5] (FE) `frontend/src/pages/AdminLearningListPage.tsx`·`AdminLearningListPage.module.css` — `/admin/learning`. 마운트 때 `checkAdminAccess`: `admin`일 때만 화면을 그린다; `denied`면 목록 대신 "볼 수 없는 화면입니다" 한 문장과 `/learn` 링크(편집 수단을 보이지 않는다 — US5 시나리오 1); `unauthenticated`는 014 재로그인 안내로 넘긴다; `failed`는 `LearningLoadFailure`. 목록: 순서·제목·상태·`draftVersion`·'게시하지 않은 변경 있음'(`hasUnpublishedChanges`)·'갱신 필요'(`needsUpdate`, `null`이면 "대응표를 확인할 수 없음")·마지막 기록(`action`·`revisionNo`·`actorUserId`·`occurredAt`)·편집 링크. 글 만들기 폼(`articleId`·`title`·`readingOrder`) → 201이면 편집 화면으로, `idTaken`이면 그 자리에 안내. `AppHeader`에 관리 메뉴를 더하지 않는다(운영자는 주소로 들어온다) (T060 의존)
- [ ] T067 [US5] (FE) `frontend/src/pages/AdminLearningEditPage.tsx`·`AdminLearningEditPage.module.css` — `/admin/learning/:articleId`. 접근 확인은 T066과 같다. 초안을 불러온 뒤 `loadUnsentDraft`에 값이 있으면 복원할지 먼저 묻는다(R12, quickstart 3-7); 편집기(제목·질문·읽는 순서·출처·`BlockEditor`·`ExampleEditor`·`TermListEditor`)의 변경을 `saveUnsentDraft`로 보관; **저장**: `saveDraft(expectedDraftVersion = 마지막으로 읽은 값)` → `saved`면 `draftVersion` 갱신·`clearUnsentDraft`; `draftChanged`면 **입력을 화면에 남긴 채** "다른 곳에서 초안이 바뀌었습니다. 새로 불러온 뒤 다시 고쳐 주세요"와 '새로 불러오기' 버튼(자동 병합·자동 재전송 없음, quickstart 3-6); `termIdTaken`·`invalid`는 `details`를 그 자리에; `unauthenticated`면 보관본을 지우지 않고 재로그인 안내(014 FR-012); **게시**: `publishArticle` → `checkFailed`면 `PublishCheckResultList`(quickstart 3-2), `published`면 `revisionNo`·`checkVersion`·`sectorMapVersion`·`publishedAt` 표시(3-3); **게시 취소**: `PUBLISHED`일 때만 버튼, 확인 뒤 `unpublishArticle`(3-8). `publishedRevisionNo`를 보인다. 관리 화면은 FR-019 대상이 아니지만 폼 요소는 `label`을 가진다 (T060~T065 의존)
- [ ] T068 [US5] (FE) `frontend/src/App.tsx` — `/admin/learning`·`/admin/learning/:articleId`를 014 `ProtectedRoute`로 감싼 lazy 경로로 더한다(`/records`와 같은 방식). 비로그인은 `ProtectedRoute`가 로그인으로 보내고 돌아온다 (T066·T067 의존)

### Frontend 테스트 (B05)

- [ ] T069 [P] [US5] (FE 테스트) `frontend/src/api/learningAdmin.test.ts` — 401 → `unauthenticated`, 404 → `denied`(access) / `failed`(그 밖), 409 `LEARNING_DRAFT_CHANGED` → `draftChanged`이고 `currentDraftVersion`이 `details` 값, 409 `LEARNING_TERM_ID_TAKEN` → `termIdTaken`, 422 → `checkFailed`이고 `failures`가 서버 배열 그대로(`message` 변형 없음); 상태 변경 요청에 `X-XSRF-TOKEN`이 실린다(쿠키를 심고 확인). `test.include`에 `'src/api/learningAdmin.test.ts'` 추가 (T060 의존)
- [ ] T070 [P] [US5] (FE 테스트) `frontend/src/components/learning/admin/PublishCheckResultList.test.tsx` — 픽스처의 항목 셋이 모두 보이고 각 `message`가 그대로이며 `block` 위치가 "본문 3번째 블록", `term` 위치가 "용어 max-drawdown"으로 읽힌다; 목록이 `alert`다 (T065 의존)
- [ ] T071 [US5] (FE 테스트) `frontend/src/pages/AdminLearningEditPage.test.tsx` — `draftChanged` 응답 뒤 입력값이 화면에 남고 안내와 새로 불러오기 버튼이 있으며 두 번째 저장 요청이 없다(quickstart 3-6); `checkFailed` 응답의 항목 셋이 보인다(3-2); `unauthenticated` 응답 뒤 `sessionStorage`의 보관본이 남아 있고 재로그인 안내가 있다(3-7); 보관본이 있으면 복원 여부를 묻고 수락하면 폼에 채워진다; 저장 성공 뒤 보관본이 지워진다; `DRAFT` 글에는 게시 취소 버튼이 없다 (T067·T068 의존)
- [ ] T072 [US5] (FE 테스트) `frontend/src/pages/AdminLearningListPage.test.tsx` — `denied`면 목록·편집 링크·만들기 폼이 없고 `/learn` 링크가 있다(US5 시나리오 1); `admin`이면 아홉 행과 '갱신 필요'·'게시하지 않은 변경 있음' 표시가 픽스처대로; `needsUpdate: null`이면 "대응표를 확인할 수 없음"; 만들기 409에 안내 (T066·T068 의존)

**Checkpoint**: 검사에 걸린 게시는 아무것도 바꾸지 않고 항목별로 보이며, 통과한 게시는 목록·글·도움말에 함께 나타나고, 게시 취소는 셋에서 함께 지운다

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 문서와 코드를 계약에 맞추고, 잔재를 확인하고, 전체 검증을 돈다

### 문서

- [ ] T073 [P] (DOCS) `backend/CLAUDE.md` "규칙"에 세 줄 추가 — "배우기 판(`learning_article_revision`)·게시 기록(`learning_publication_event`)은 추가만 한다. 저장소에 수정·삭제 메서드를 두지 않는다"; "게시·게시 취소는 글 상태 변경과 게시 용어 색인(`learning_published_term`) 재작성을 같은 트랜잭션에서 한다 — 한순간이라도 어긋나면 도움말 문장과 글 본문이 갈라진다(013 SC-003)"; "관리 권한 판정은 `LearningAdminPolicy` 한 곳이다. 지금은 `thorp.learning.admin-user-ids` 허용 목록이고 014가 역할을 주면 `ConfiguredLearningAdminPolicy`만 바꾼다". `frontend/CLAUDE.md` "규칙"에 두 줄 — "용어 도움말은 `components/learning/TermHelp.tsx`(`termId`·`label`·`returnTo`) 하나를 쓴다. 글 본문·용어 문장을 화면 코드에 상수로 두지 않는다(013 FR-015)"; "`sessionStorage` 키 `thorp.learning.adminDraft.{articleId}`(관리 화면의 저장하지 않은 편집 내용, 저장 성공 시 지운다). 일반 사용자 배우기 화면은 저장소에 쓰지 않는다"
- [ ] T074 [P] (DOCS) spec 문서 동기화 — `specs/013-learning-pages/data-model.md` §3-6 snapshot 예시에 `draftVersion`을 더하고 `hasUnpublishedChanges` 판정 근거임을 적는다(T009·T052); `plan.md` "Source Code" 트리의 파일 이름을 구현과 맞춘다(`presentation/support/LearningAdminAccess`, `LearningQueryService`·`LearningAdminService`, `admin/adminDraftStorage.ts` 등); `research.md` R5·R11에 "착수 전에 알아둘 것" ①~③의 현재 상태를 반영한다; `specs/README.md` 013 행은 바뀐 것이 없으면 그대로 둔다
- [ ] T075 (DOCS) [quickstart.md](quickstart.md) 0~5 수동 확인을 돌리고 결과를 MR 본문 "테스트" 절에 항목별로 적는다. 002 T020이 아직 없으면 4-3은 T032로 대신하고 그 사실을 적는다; 003 화면이 공용 `TermHelp`를 아직 쓰지 않으면 5-1~5-4는 T042로 대신한다(quickstart §5 머리말); 로컬 확인은 §0의 Flyway 켜기 명령으로 한다. SC-002는 검사 통과로 증명되지 않는다는 것을 그대로 적는다(quickstart §6)

### 코드 정리

- [ ] T076 [P] (FE) 잔재·하드코딩 확인 — `grep -n "NotReadyPage" frontend/src/App.tsx`에서 `/learn` 경로가 0건; `grep -rn "dangerouslySetInnerHTML" frontend/src/components/learning frontend/src/pages/Learning*`가 0건(R3); `grep -rn "components/strategy/TermHelp" frontend/src`는 003 T057a의 몫이므로 결과만 MR에 적는다; `frontend/src/components/learning`·`frontend/src/pages/Learning*Page.tsx`에 [data-model](data-model.md) §8의 `termId`·제목 문자열이 상수로 없다(FR-015. 테스트·픽스처는 제외)

### 전체 검증

- [ ] T077 (BE 테스트) `cd backend && ./gradlew spotlessApply build` — ArchUnit(규칙 9 포함)·마이그레이션·계약·통합 테스트 전부. `skipped`가 Docker 부재 때문인지 확인하고 건너뛴 테스트를 통과로 세지 않는다([backend/CLAUDE.md](../../backend/CLAUDE.md), quickstart 1)
- [ ] T078 (FE 테스트) `cd frontend && npm run lint && npm run build && npm test` — Vitest include에 새 `.test.ts` 셋(`learning`·`learningGlossary`·`learningAdmin`)이 들어갔는지 결과 목록에서 확인한다. 빠졌으면 `src/diagnosis/testCollection.guard.test.tsx`가 먼저 실패한다

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**: 선행 없음. T002~T006은 전부 병렬
- **Phase 2 Foundational**: Setup 완료 후. **모든 사용자 스토리를 막는다.** 안에서 T008·T009·T010은 병렬, T011은 셋 뒤, T012~T014는 T011 뒤
- **Phase 3~7 User Stories**: Foundational 완료 후
- **Phase 8 Polish**: 원하는 스토리들이 끝난 뒤

### User Story Dependencies

- **US1 (P1)**: Foundational 후 바로. 다른 스토리에 의존하지 않는다 → **MVP**. 실제 글은 B02·B05가 서기 전까지 통합 테스트로 심은 판으로만 보인다
- **US2 (P1)**: US1의 `LearningArticlePage`(T024)에 붙는다. 종단 확인은 002 T020 뒤(위 ②)
- **US3 (P2)**: 서버는 Foundational만으로 진행한다(T033~T035). 화면(B04)은 US1의 `LearningArticlePage`(T024)와 `types/learning.ts`(T004)를 쓴다
- **US4 (P3)**: US1의 화면 테스트를 확장한다. 가장 작다
- **US5 (P2)**: 서버(B02)는 Foundational과 T003 픽스처만 있으면 시작할 수 있다. 화면(B05)은 B02의 계약과 US1의 `LearningLoadFailure`(T022)를 쓴다

### 작업 묶음 = 티켓·브랜치·MR 단위 (B01~B06)

**`T###`는 티켓 단위가 아니다.** constitution "Jira 연계"가 `작업 묶음 = 1 Branch = 1 MR`로 정한다.
아래 여섯이 Jira Task·브랜치·MR의 단위이고, 78개 태스크는 그 안에서 실행 지시의 최소 단위로 남는다.
같은 표가 [plan.md](plan.md) "작업 묶음 구성"에 있다. **티켓은 각 묶음에 착수할 때 만들고**(AGENTS.md "티켓은 브랜치를 만들 때 만든다") 번호를 이 표에 채운다.

| 묶음 · 티켓 | Jira Task 제목 | 포함 T### | 수 | position | 선행 |
|---|---|---|---|---|---|
| B01 · 미발급 | `[BE] 배우기 글과 용어 설명을 보관하고 공개 조회한다` | T001~T003, T007~T019, T033~T035 | 19 | `be` | 없음 |
| B02 · 미발급 | `[BE] 관리 계정이 초안을 저장하고 게시·게시 취소한다` | T045~T059 | 15 | `be` | B01 |
| B03 · 미발급 | `[FE] 배우기 목록과 글을 보이고 예시로 진단을 시작한다` | T004, T006, T020~T032, T043, T044 | 17 | `fe` | B01의 계약(T003) |
| B04 · 미발급 | `[FE] 진단 화면이 함께 쓰는 용어 도움말을 만든다` | T036~T042 | 7 | `fe` | B03 |
| B05 · 미발급 | `[FE] 관리 화면에서 글을 고치고 게시한다` | T005, T060~T072 | 14 | `fe` | B02, B03 |
| B06 · 미발급 | `[DOCS] spec 013 문서 동기화와 최종 검증` | T073~T078 | 6 | `fe` | 전부 |

```text
B01 ─┬─▶ B02 ─────────────┐
     │                    ├─▶ B05 ─┐
     └─▶ B03 ─┬───────────┘        ├─▶ B06
              └─▶ B04 ─────────────┘
```

**각 묶음이 끝나면 무엇이 달라지나.** 묶음 조건 1(하나의 목적)을 만족하는지 보는 기준이다.

| 묶음 | 끝나면 |
|---|---|
| B01 | 다섯 테이블과 초기 자리가 생기고, 공개 조회 세 주소가 열리며, 002·003·005가 쓸 `termId`가 정해진다 |
| B02 | 화면을 우회한 요청도 서버가 검사하고, 통과한 게시만 공개된다 |
| B03 | 목록과 글이 보이고, 글의 예시가 002 계약 모양으로 넘어간다 |
| B04 | 진단 화면이 같은 도움말 부품을 붙일 수 있다 |
| B05 | 운영자가 배포 없이 글을 고치고 게시·게시 취소한다 |
| B06 | 문서가 구현과 같고 양쪽 빌드가 돈다 |

- **B01이 나머지 백엔드 전부를 막는다.** 스키마·매핑·검증이 한 MR에 있어야 한다(constitution "스키마 변경 규칙"). B01 안의 T012·T013을 건너뛰고 올리지 않는다
- **B03은 B01의 계약 부분만 기다린다** — [contracts/learning-public-api.md](contracts/learning-public-api.md)가 응답 모양을 고정했고 T006 픽스처로 화면 테스트가 돈다. B01이 T003 픽스처를 먼저 커밋하면 머지 전에도 프런트엔드가 출발할 수 있다. 실제 서버와 붙여 보는 것만 B01 머지 뒤다
- **B02는 B03과 병렬이다** — 둘 다 B01만 기다리고 파일이 겹치지 않는다(`learning/application/service/` 안의 서로 다른 클래스, 공통 계층은 B02만 만진다)
- **B04를 B03에서 뺐다** — 002·003·005가 쓰는 공용 부품이라 리뷰어와 영향 범위가 다르다([plan](plan.md) "묶음을 이렇게 자른 이유")
- **B05는 가장 크다.** 편집기가 블록·예시·용어 세 부분을 가진다. 더 쪼개면 "저장은 되는데 게시할 수 없는" 중간 상태가 생겨 묶음 조건 3을 어긴다. 브랜치는 하나로 두고 안에서 T062~T065 → T066·T067 → 테스트 순으로 커밋을 나눈다
- **묶음마다 Jira Task를 먼저 만들고 그 번호로 브랜치를 만든다**(constitution). 브랜치는 `{type}({position})/{티켓번호}-{설명}`이며 B01~B05는 `feat`, B06은 `docs`다. plan의 브랜치 이름 제안: B01 `learning-content-api`, B02 `learning-admin-api`, B03 `learning-pages`, B04 `term-help`, B05 `learning-admin`, B06 `spec-013-wrapup`

### 파트 사이 순서

- `(INFRA)` 마이그레이션(T007) → `(BE)` JPA 매핑(T011) → `(BE 테스트)` 마이그레이션 검증(T012): 셋이 같은 MR(B01)에 있어야 prod `validate`가 통과한다
- `(DOCS)` 계약 픽스처(T003) → `(BE 테스트)` 계약 대조(T018·T035·T059)와 `(FE)` 타입·픽스처(T004~T006): 계약이 먼저다
- `(INFRA)` 검사 규칙 파일(T048) → `(BE)` 로더(T049) → `(BE)` 게시 유스케이스(T052): 로더가 읽을 형식이 먼저 정해져야 한다
- `(BE)` 응답 DTO → `(FE)` 화면: 계약 문서가 이미 응답 모양을 고정했으므로 **프론트는 백엔드를 기다리지 않고 병행**한다

### Within Each User Story

- 테스트를 먼저 쓰고 실패시킨 뒤 구현한다
- BE: 값 타입·모델 → 저장소 → 서비스 → 컨트롤러 → 계약 테스트
- FE: 타입·픽스처 → API 함수(상태 union) → 컴포넌트 → 페이지 조립 → 라우트 → 페이지 테스트

### Parallel Opportunities

- Phase 1: T002~T006 전부 병렬
- Phase 2: T008·T009·T010 병렬 → T011 → T012·T013·T014 병렬
- US1 BE: T015 → T016 → T017 → T018, T019는 T016 뒤 T018과 병렬
- US1 FE: T020·T021·T022 병렬 → T023·T024 → T025 → T026~T029(T026·T027은 더 일찍 가능)
- US3: 서버(T033~T035)와 화면(T036~T042)은 다른 담당이 동시에. 화면 안에서 T036·T037 병렬 → T038 → T039, 테스트 T040·T041 병렬
- US5 BE: T045·T046·T048·T050·T051 병렬 → T047·T049 → T052 → T053·T054 → T055~T057 병렬 → T058·T059
- US5 FE: T060~T065 전부 병렬 → T066·T067 → T068 → T069·T070 병렬 → T071·T072
- **BE와 FE는 스토리 안에서 병렬** — 계약이 고정되어 있다

---

## Parallel Example: User Story 1

```bash
# 백엔드 — 결과 DTO 뒤 서비스, 그 뒤 컨트롤러·테스트
Task: "T015 LearningArticleSummaryResult·LearningArticleResult"
Task: "T016 LearningQueryService (목록·단건·예시 판정)"      # T015 뒤
Task: "T017 LearningController + Response"                     # T016 뒤
Task: "T018 LearningPublicApiIT + LearningSeeds" / "T019 LearningQueryServiceTest"   # 병렬

# 프론트엔드 — 계약 문서 기준으로 백엔드와 동시에
Task: "T020 api/learning.ts" / "T021 ArticleBlocks" / "T022 LearningLoadFailure"   # 병렬
Task: "T023 LearningListPage" / "T024 LearningArticlePage"    # T020~T022 뒤, 서로 병렬
Task: "T025 App.tsx 라우트"                                    # T023·T024 뒤
Task: "T026" / "T027" / "T028" / "T029"                        # 테스트 병렬
```

---

## Implementation Strategy

### MVP First (User Story 1·2·4)

1. **B01** 글과 용어 설명을 보관하고 공개 조회한다 — **여기가 스키마다.** T012(마이그레이션 검증)·T013(의존 방향)을 건너뛰지 않는다. T003 픽스처를 먼저 커밋해 B03이 출발할 수 있게 한다
2. **B03** 목록과 글을 보이고 예시로 진단을 시작한다
3. **멈추고 검증**: quickstart 2-1·2-2·4-1·4-2, 4-3은 T032까지(002 T020 대기), 4-4는 통합 테스트로 심은 판
4. 데모 — 통합 테스트로 심은 판 하나로 목록 → 글 → '이 예시로 비중 비교하기'가 002 계약 모양으로 넘어가는 데까지. **실제 원고 게시는 B02·B05 뒤다**

**MVP = 묶음 둘(B01 + B03) = 티켓·브랜치·MR 둘.** T001~T035 가운데 T005·T036~T042를 뺀 것에 T043·T044를 더한 것이다.

### Incremental Delivery

1. B01 + B03 → 배우기 화면이 열린다(MVP)
2. B04 → 003 T057a가 공용 `TermHelp`로 갈아탈 수 있다(003 담당의 작업)
3. B02 → 서버가 게시 전 검사를 한다. `curl`과 통합 테스트로 확인할 수 있다
4. B05 → 운영자가 원고를 넣는다. 이때부터 일반 사용자에게 실제 글이 보인다
5. B06 → 문서와 구현이 같다

### Parallel Team Strategy

백엔드 한 사람, 프론트엔드 한 사람이면:

1. 백엔드가 B01을 올리는 동안 프론트엔드는 T003 픽스처 기준으로 B03을 시작한다
2. B01 머지 뒤 백엔드는 B02, 프론트엔드는 B03 마무리 → B04
3. B02 머지 뒤 프론트엔드가 B05
4. B06은 둘이 함께

---

## Notes

- [P] = 다른 파일, 선행 없음
- [Story] 라벨은 추적용이다. 묶음(B01~B06)이 티켓·브랜치·MR 단위다
- 각 스토리는 독립적으로 완료·검증할 수 있어야 한다. 실제 원고가 없는 동안은 통합 테스트로 심은 판이 검증 대상이다
- 테스트가 먼저 실패하는 것을 확인한 뒤 구현한다
- 태스크 하나 또는 논리적 묶음마다 커밋한다
- 피할 것: 모호한 태스크, 같은 파일 충돌, 스토리 독립성을 깨는 교차 의존, **판·이벤트를 고치는 저장소 메서드**, **초안 `learning_term`을 읽어 공개 응답을 만드는 경로**
