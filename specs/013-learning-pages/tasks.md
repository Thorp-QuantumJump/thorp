# Tasks: 배우기

**Input**: Design documents from `specs/013-learning-pages/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

> 이 tasks 문서 작업은 [S15P21E205-289](https://ssafy.atlassian.net/browse/S15P21E205-289) · branch `docs(fe)/289-spec-013-tasks`다.
> plan([S15P21E205-273](https://ssafy.atlassian.net/browse/S15P21E205-273))이 dev에 병합된 판(`8717a9e`)을 기준으로 썼다.
> 구현 Task의 Git 브랜치는 Jira Task마다 `{type}({position})/{JIRA_NUMBER}-{description}` 형식으로 따로 만든다.
>
> **2판(2026-10-05)**: spec 2026-10-02~05 개정을 구현할 task T098~T200은 문서 끝 [2판](#2판-2026-10-05) 절에 있다. 2판 묶음은 B07~B16이고 브랜치 이름은 Jira 번호 없이 [plan.md](plan.md) "작업 묶음"을 따른다. 아래 1판 절(T001~T097)은 구현 기록으로 그대로 둔다.

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
| `(DOCS)` | 계약 픽스처·CLAUDE.md·spec 문서·수동 확인 기록 | 담긴 묶음을 따른다(T003은 B01 `be`, B06은 `fe`). `docs`는 브랜치 type이고 position이 아니다 |

Worker(`worker/`)와 `data/`는 이번 범위에서 **변경하지 않는다**. 새 라이브러리도 추가하지 않는다([research](research.md) R13).

**`T###`는 티켓·브랜치 단위가 아니다.** constitution "Jira 연계"가 `작업 묶음 = 1 Branch = 1 MR`로 정한다.
아래 78개는 실행 지시의 최소 단위이고, **티켓·브랜치·MR의 단위는 묶음 B01~B06 여섯이다** —
"Dependencies & Execution Order"의 "작업 묶음" 표와 [plan.md](plan.md) "작업 묶음 구성"에 있다.

## 착수 전에 알아둘 것

013 혼자 닫을 수 없는 항목이다. 여기서 티켓을 만들지 않고 각 담당이 판단한다([plan](plan.md) "교차 의존").

- ① **014 관리 권한** — 014에 역할이 아직 없어 관리 권한은 설정 허용 목록 `thorp.learning.admin-user-ids`로 판정한다([research](research.md) R5). 014 Out of Scope를 고치는 개정은 014 담당의 몫이며 013 구현을 막지 않는다. 014가 역할을 주면 `ConfiguredLearningAdminPolicy` 한 클래스만 바꾼다
- ② **002 T020 구현됨** — 예시 수신과 덮어쓰기 확인이 현재 코드에 있다. B06은 T032만으로 대신하지 않고 실제 002 화면에서 예시 적용·거절 시 입력 보존을 확인했다([검증 기록](validation.md) 4-3)
- ③ **003 공용 도움말 연결 구현됨** — T097이 등록표 `axes[].termId`를 공용 `components/learning/TermHelp.tsx`에 연결한다. 실제 API가 설명 없음과 조회 실패를 구분한다. 003 T057a·T063의 옛 경로·실패 처리 문구 정리는 003 담당의 문서 동기화 대상이다. 실제 왕복·입력 유지 확인은 [검증 기록](validation.md) 5-1~5-4에 둔다
- ④ **002·005 도움말 자리** — 어느 화면의 어떤 용어에 도움말을 붙일지는 각 spec이 정한다(spec FR-008). 쓸 수 있는 `termId` 목록은 [data-model](data-model.md) §8이다
- ⑤ **로컬 프로필은 Flyway가 꺼져 있다** — 마이그레이션이 넣는 아홉 글·열네 용어 자리가 `local`에서는 생기지 않는다. 확인하려면 [quickstart](quickstart.md) §0의 명령으로 Flyway를 켠다
- ⑥ **PD-16 미확정** — [data-model](data-model.md) §8의 제목·순서·용어 구성은 출발안이다. 팀이 확정해 달라지면 관리 화면에서 고치거나 새 마이그레이션으로 자리를 더한다. 적용된 마이그레이션은 고치지 않는다(constitution "스키마 변경 규칙")
- ⑦ **JSONB 직렬화는 전역 설정을 탄다** — `backtest/infrastructure/messaging/MessageJsonPersistenceConfig`가 Hibernate JSON 매퍼를 메시지 ObjectMapper(null 키 생략, decimal 문자열)로 바꿔 두었다. 013의 JSONB 컬럼(`body_blocks`·`example`·`sources`·`snapshot`)도 그 매퍼로 저장되므로, 판 JSON을 읽는 record는 **키 없음을 null로** 받아야 한다

## Path Conventions

- 백엔드 013 소유 영역: `backend/src/main/java/com/ssafy/thorp/learning/...`, 테스트는 `backend/src/test/java/com/ssafy/thorp/learning/...`
- 백엔드 공통 영역: `common/exception/ErrorCode.java`(013 블록 추가)와 `common/config/SecurityConfig.java`(경로 한 줄)는 최소한으로만 만진다. `common/web/advice/GlobalExceptionHandler.java`는 예외다. T054가 메서드 하나를 override하고 모든 도메인의 400 응답에 `details.fields`를 더하므로 MR 리뷰 포인트에 적는다
- 마이그레이션: `backend/src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__learning_content.sql` — 버전은 작성 시각 타임스탬프다([backend/CLAUDE.md](../../backend/CLAUDE.md))
- 검사 규칙 파일: `backend/src/main/resources/learning/publish-checks-v1.yml`
- 계약 픽스처: `docs/specs/learning-api/examples/*.json` (003의 `docs/specs/strategy-registry-api/` 방식)
- 프론트엔드: `frontend/src/...` — 화면 `pages/Learning*Page.tsx`·`pages/AdminLearning*Page.tsx`, 컴포넌트 `components/learning/`(관리는 `components/learning/admin/`), API `api/learning*.ts`, 타입 `types/learning.ts`·`types/learningAdmin.ts`
- 커밋 전 `cd backend && ./gradlew spotlessApply` 필수. 프론트엔드는 `cd frontend && npm run lint && npm run build`
- 새 `.test.ts`는 `frontend/vite.config.ts`의 `test.include`에 정확 경로로 더한다. 빠지면 `src/diagnosis/testCollection.guard.test.tsx`가 실패시킨다

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 013 소유 패키지 골격, 오류 코드, 계약 픽스처, 프론트엔드 타입·픽스처 자리

- [X] T001 (BE) `learning` 도메인 패키지 골격 생성 — `backend/src/main/java/com/ssafy/thorp/learning/package-info.java`에 계층 규약(ADR-0001), "글·용어 설명의 소유자는 013이다", 의존 방향 "`learning.infrastructure → sector.application`(대응표는 `SectorMapPort`로 읽는다)이며 `sector`는 `learning`을 참조하지 않는다"를 명시. 하위 패키지는 `presentation/{controller,request,response,support}`, `application/{service,command,result,repository,port,exception}`, `domain/{model,vo,exception}`, `infrastructure/{persistence,sector,admin,rules}`([plan](plan.md) "Source Code")
- [X] T002 [P] (BE) `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java`에 "spec 013 배우기" 블록을 열고 일곱 코드를 더한다([contracts/README](contracts/README.md) "새 오류 코드"): `LEARNING_ARTICLE_UNAVAILABLE(NOT_FOUND, "지금은 볼 수 없는 글입니다.")`, `LEARNING_TERM_NOT_READY(NOT_FOUND, "설명을 준비하고 있습니다.")`, `LEARNING_ARTICLE_NOT_FOUND(NOT_FOUND, …)`, `LEARNING_ARTICLE_ID_TAKEN(CONFLICT, …)`, `LEARNING_TERM_ID_TAKEN(CONFLICT, …)`, `LEARNING_DRAFT_CHANGED(CONFLICT, …)`, `LEARNING_PUBLISH_CHECK_FAILED(UNPROCESSABLE_ENTITY, "게시 전 검사에 걸린 항목이 있어 게시하지 않았습니다.")`. 주석에 계약 위치를 적는다. 공개 조회의 두 404를 한 코드로 합치지 않는다([research](research.md) R10)
- [X] T003 [P] (DOCS) `docs/specs/learning-api/README.md`(계약 요약과 원본 위치 링크, `docs/specs/strategy-registry-api/README.md`와 같은 역할)와 `docs/specs/learning-api/examples/` 픽스처 여덟 생성 — `articles-list.json`(공개 목록 `data`, [learning-public-api](contracts/learning-public-api.md) §1), `article.json`(§2, `term` 블록에 `label`·`sentence`가 채워진 완전한 응답, `example.available=true`), `term.json`(§3), `admin-articles-list.json`([learning-admin-api](contracts/learning-admin-api.md) §2), `admin-article.json`(§4), `publish-success.json`(§6 200), `publish-check-failed.json`(§6 422의 `error` 전체, 걸린 항목 셋), `unpublish.json`(§7). 값은 계약 예시의 합성 값이며 원고가 아니다. T018·T035·T059의 계약 테스트가 이 파일을 읽는다
- [X] T004 [P] (FE) `frontend/src/types/learning.ts` — 공개 조회 응답과 1:1 타입([data-model](data-model.md) §10): `LearningArticleSummary`, `LearningArticle`, `ArticleBlock`(`heading`·`paragraph`·`term`·`hypothetical` 판별 union, `term`은 `termId`·`label`·`sentence`), `LearningExample`(`available`·`sectors[].{code,name,weightTenths}`·`missingSectorCodes`·`unavailableReason: 'SECTOR_NOT_IN_MAP' | 'SECTOR_MAP_NOT_READY' | null`), `LearningSource`, `LearningTermEntry`, `LearningReturnState = { path: string; label: string }`, `LearningApiErrorCode = 'LEARNING_ARTICLE_UNAVAILABLE' | 'LEARNING_TERM_NOT_READY'`. 같은 태스크에서 `frontend/src/types/api.ts`의 `ServiceApiErrorCode` union에 `LearningApiErrorCode`를 잇는다(그 파일 머리 주석의 규칙: 새 도메인은 자기 파일과 `api.ts` 둘만 고친다). 파일 머리 주석에 "글 본문·용어 문장을 이 코드에 상수로 두지 않는다(FR-015)"를 적는다
- [X] T005 [P] (FE) `frontend/src/types/learningAdmin.ts` — 관리 응답과 1:1 타입: `AdminLearningArticleSummary`(`hasUnpublishedChanges`·`needsUpdate: boolean | null`·`lastPublication | null`), `AdminLearningArticle`(`draft`·`publishedRevisionNo: number | null`), `AdminLearningDraft`(초안 블록은 `termId`만 담는다), `SaveDraftRequest`, `PublishCheckFailure`(`check`·`location`·`matched?`·`message`), `PublishResult`, `UnpublishResult`, `LearningAdminApiErrorCode`(관리 오류 코드 다섯). `ServiceApiErrorCode` union에 잇는다
- [X] T006 [P] (FE) `frontend/src/test/learningFixtures.ts` — 화면 테스트용 응답 픽스처. **[data-model](data-model.md) §8에 없는 합성 `articleId`·`termId`를 쓴다**(`frontend/src/test/strategyRegistryFixtures.ts`와 같은 이유: 응답만 보고 그린다는 것을 증명한다). 담는 것: 목록 둘, 네 블록 종류를 모두 가진 글 하나, `example.available=false`(`SECTOR_NOT_IN_MAP`) 글 하나, 용어 설명 하나, 관리 목록·초안·게시 실패(항목 셋) 응답. 관리 응답 픽스처는 그 타입(T005)과 함께 B05에서 더한다

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 다섯 테이블과 초기 자리, 도메인 모델, JPA 매핑, 대응표 포트, 의존 방향 규칙. 스키마·매핑·검증은 같은 묶음(B01)에 담는다(constitution "스키마 변경 규칙").

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 어떤 사용자 스토리도 시작할 수 없다. 마이그레이션 버전은 작성 시각 타임스탬프이며 `infra/scripts/check-migration-version.sh`가 기준 브랜치 대비 역전을 잡는다 — 브랜치가 오래 열려 있었으면 병합 직전에 버전을 다시 확인한다.

- [X] T007 (INFRA) `backend/src/main/resources/db/migration/V{작성 시각}__learning_content.sql` — [data-model](data-model.md) §3의 다섯 테이블과 §8의 초기 자리.
  - `learning_article`: `id BIGINT identity PK`, `article_key VARCHAR(64) NOT NULL UNIQUE` + 형식 CHECK(`^[a-z0-9]+(-[a-z0-9]+)*$`, 3~64자), `title VARCHAR(100) NOT NULL`, `question VARCHAR(200) NOT NULL`, `reading_order INTEGER NOT NULL CHECK 1..999`, `body_blocks JSONB NOT NULL DEFAULT '[]'`, `example JSONB NULL`, `sources JSONB NOT NULL DEFAULT '[]'`, `status VARCHAR(16) NOT NULL CHECK IN ('DRAFT','PUBLISHED')`, `published_revision_id BIGINT NULL`, `draft_version INTEGER NOT NULL DEFAULT 0`, `created_at`/`updated_at TIMESTAMPTZ NOT NULL`. **불변식 CHECK**: `(status='PUBLISHED') = (published_revision_id IS NOT NULL)`
  - `learning_term`: `term_key VARCHAR(64) NOT NULL UNIQUE` + 형식 CHECK(2~64자), `article_id BIGINT NOT NULL FK`, `label VARCHAR(50) NOT NULL`, `sentence VARCHAR(300) NOT NULL`, 시각 둘
  - `learning_article_revision`: `article_id FK`, `revision_no INTEGER NOT NULL`, `(article_id, revision_no) UNIQUE`, `snapshot JSONB NOT NULL`, `check_version VARCHAR(64) NOT NULL`, `sector_map_version VARCHAR(128) NOT NULL`, `published_by_user_id BIGINT NOT NULL`(`users`에 FK를 걸지 않는다 — §3-3), `published_at TIMESTAMPTZ NOT NULL`. `learning_article.published_revision_id`의 FK는 이 테이블을 만든 뒤 `ALTER TABLE … ADD CONSTRAINT`로 건다
  - `learning_publication_event`: `article_id FK`, `action VARCHAR(16) CHECK IN ('PUBLISH','UNPUBLISH')`, `revision_id BIGINT NULL FK`, `actor_user_id BIGINT NOT NULL`, `occurred_at TIMESTAMPTZ NOT NULL`
  - `learning_published_term`: `term_key VARCHAR(64) PK`, `article_id FK`, `revision_id FK`, `label VARCHAR(50)`, `sentence VARCHAR(300)`
  - 초기 자리: §8 표의 아홉 글(`article_key`·`title`·`reading_order`, `question=''`, `status='DRAFT'`)과 열네 용어(`term_key`·`label`, `sentence=''`). 본문·예시·문장은 비운다([research](research.md) R9). 파일 머리 주석에 "제목·순서·용어 구성은 PD-16 출발안이다. 적용된 뒤에는 이 파일을 고치지 않고 관리 화면이나 새 마이그레이션으로 바꾼다"를 적는다
  - `MigrationFileConventionTest`(Docker 없이 돈다)가 파일명 규칙을 검사한다
- [X] T008 [P] (BE) 도메인 값 타입 — `backend/src/main/java/com/ssafy/thorp/learning/domain/vo/ArticleId.java`(형식 검사를 생성자에서, 3~64자), `TermId.java`(2~64자), `ExampleAllocation.java`(`List<SectorWeight(String code, int weightTenths)>` — 코드 중복 없음, `weightTenths` 0..1000 정수, 최대 20개, 코드 형식은 001 `SectorCode`의 규칙과 같되 **`sector` 패키지를 import하지 않고** 자체 정규식으로 검사한다(도메인은 다른 도메인을 참조하지 않는다). 합계는 여기서 검사하지 않는다 — 합계 1000은 게시 전 검사의 몫이다([data-model](data-model.md) §5). 순수 Java, Spring·JPA·Jackson import 금지
- [X] T009 [P] (BE) 도메인 모델([data-model](data-model.md) §9) — `backend/src/main/java/com/ssafy/thorp/learning/domain/model/`: `ArticleBlock.java`(`sealed interface`, 구현 record `HeadingBlock(text 1~100)`·`ParagraphBlock(text 1~2000)`·`TermBlock(TermId termId)`·`HypotheticalBlock(text 1~1000)`, 생성자에서 길이 검사), `LearningTerm.java`(`TermId`·`label`·`sentence`), `LearningArticle.java`(초안 작업본 — `ArticleId`·`title`·`question`·`readingOrder`·`blocks`(최대 60)·`example`(nullable)·`sources`·`status`·`publishedRevisionId`·`draftVersion`·`terms`; 전이 메서드 `saveDraft(expectedDraftVersion, …)`는 버전이 다르면 `DraftChangedException`을 던지고 같으면 내용을 통째로 바꾸며 `draftVersion + 1`; `publish(expectedDraftVersion, revisionId)`는 `PUBLISHED`로; `unpublish()`는 `PUBLISHED`일 때만 `DRAFT`로 바꾸고 `DRAFT`면 아무것도 하지 않는다; `status`와 `publishedRevisionId`의 불변식을 생성자에서 검사), `ArticleSnapshot.java`(§3-6의 판 내용 record — `articleId`·`title`·`question`·`readingOrder`·`bodyBlocks`·`example`·`sources`·`terms`, 그리고 `draftVersion`(게시 당시 초안 판 번호. 관리 목록의 `hasUnpublishedChanges` 판정 근거이며 T074가 §3-6에 적는다)), `ArticleRevision.java`(불변 판 — `revisionNo`·`snapshot`·`checkVersion`·`sectorMapVersion`·`publishedByUserId`·`publishedAt`, setter 없음), `PublicationEvent.java`(`PUBLISH`·`UNPUBLISH` enum `PublicationAction`, `revisionId`(nullable)·`actorUserId`·`occurredAt`). `backend/src/main/java/com/ssafy/thorp/learning/domain/exception/DraftChangedException.java` — `BusinessException(ErrorCode.LEARNING_DRAFT_CHANGED, …, Map.of("currentDraftVersion", n))`
- [X] T010 [P] (BE) 대응표 포트 — `backend/src/main/java/com/ssafy/thorp/learning/application/port/SectorMapPort.java`: `Optional<SectorMapView> currentMap()`, `SectorMapView(String mapVersion, Map<String, String> sectorNamesByCode)` record. 대응표가 아직 없으면 `Optional.empty()`(빈 맵이 아니다 — 원칙 VI). 구현 `backend/src/main/java/com/ssafy/thorp/learning/infrastructure/sector/SectorServiceSectorMapAdapter.java`는 001 `sector/application/service/SectorService.getSectors()`를 부르고 `SectorMapNotReadyException`만 `Optional.empty()`로 바꾼다. 004의 `MarketMetadataPort`와 같은 방식([research](research.md) R8)
- [X] T011 (BE) JPA 매핑과 저장소 — `backend/src/main/java/com/ssafy/thorp/learning/infrastructure/persistence/`: `LearningArticleJpaEntity`·`LearningTermJpaEntity`·`LearningArticleRevisionJpaEntity`·`LearningPublicationEventJpaEntity`·`LearningPublishedTermJpaEntity`(JSONB는 기존 `BacktestResultJpaEntity`처럼 `@JdbcTypeCode(SqlTypes.JSON)`; 시각은 `common/persistence/BaseEntity`), 각 `*JpaRepository`, `LearningArticleMapper`·`ArticleRevisionMapper`(정적 `toEntity`/`toDomain`), 구현 `LearningArticleRepositoryImpl`·`ArticleRevisionRepositoryImpl`·`PublicationEventRepositoryImpl`·`PublishedTermRepositoryImpl`. 인터페이스는 `backend/src/main/java/com/ssafy/thorp/learning/application/repository/`에: `LearningArticleRepository`(`findByArticleKey`, `findByArticleKeyForUpdate`(비관적 잠금 — 잠근 뒤 `refresh`, [backend/CLAUDE.md](../../backend/CLAUDE.md)), `findAllOrdered`(`reading_order`, `article_key` 오름차순), `findPublishedOrdered`, `save`, `existsByArticleKey`), `LearningTermRepository`(`findOwnerArticleKey(termKey)`, `replaceForArticle`), `ArticleRevisionRepository`(**`save`·`findById`·`findByArticleId`만. 수정·삭제 메서드를 두지 않는다** — §3-3, 원칙 VIII), `PublicationEventRepository`(**`append`·`findLastByArticleId`만**), `PublishedTermRepository`(`findPublished(termKey)` → `termId`·`label`·`sentence`·`articleId`(`article_key`)·`articleTitle`(색인 행이 가리키는 판 snapshot의 제목), `replaceForArticle(articleId, revisionId, terms)`, `deleteForArticle(articleId)`). 판 JSON record는 키 없음을 null로 읽는다(위 ⑦) (T007·T008·T009 의존)
- [X] T012 (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/persistence/LearningMigrationTest.java`(`support/PostgresTestSupport` 상속, `@DataJpaTest`, `common/persistence/FlywayMigrationTest` 방식이며 **버전 번호를 테스트에 적지 않는다**) — 빈 DB 적용 뒤 `learning_article` 아홉 행이 모두 `DRAFT`·`question=''`·`body_blocks='[]'`·`example IS NULL`, `learning_term` 열네 행이 `sentence=''`이고 §8 표의 `term_key`가 모두 있다; `status='PUBLISHED'`인데 `published_revision_id`가 NULL인 INSERT가 CHECK로 거절된다; `article_key='Bad_Key'`가 거절된다; `(article_id, revision_no)` 중복이 거절된다(quickstart 1 "마이그레이션") (T007·T011 의존)
- [X] T013 (BE 테스트) `backend/src/test/java/com/ssafy/thorp/ArchitectureTest.java`에 규칙 9 추가 — `noClasses().that().resideInAPackage("com.ssafy.thorp.sector..").should().dependOnClassesThat().resideInAPackage("com.ssafy.thorp.learning..")`. Javadoc에 "대응표의 소유자는 001이고 013이 포트로 읽는다. 방향은 `learning → sector` 하나다"를 적는다. **규칙이 동작하는지 일부러 어겨 확인한다**(003 T012 방식): `sector` 안의 아무 클래스에 `learning` 타입 import 한 줄을 넣고 테스트가 **실패**하는 것을 본 뒤 그 줄을 지운다. 결과를 MR 본문 "테스트" 절에 적는다(quickstart 1 "ArchitectureTest") (T001 의존)
- [X] T014 [P] (BE 테스트) 도메인 단위 테스트 — `backend/src/test/java/com/ssafy/thorp/learning/domain/vo/ArticleIdTest.java`(경계 3·64자, 대문자·밑줄·연속 하이픈·앞뒤 하이픈 거절), `TermIdTest.java`(2·64자), `ExampleAllocationTest.java`(중복 코드·1001·21개 거절, 0은 허용), `backend/src/test/java/com/ssafy/thorp/learning/domain/model/LearningArticleTest.java`(`saveDraft`가 옛 버전이면 `DraftChangedException`이고 `details.currentDraftVersion`이 서버 값; 맞으면 `draftVersion + 1`; `publish` → `PUBLISHED`+`publishedRevisionId`; `unpublish` → `DRAFT`+null; 이미 `DRAFT`인 `unpublish`는 아무것도 바꾸지 않는다; `PUBLISHED`인데 `publishedRevisionId`가 없는 생성은 거절; 블록 61개 거절; 알 수 없는 블록 종류는 sealed라 컴파일 단계에서 막힌다) (T008·T009 의존)

**Checkpoint**: 빈 DB에 다섯 테이블과 초기 자리가 생기고, 매핑이 validate를 통과하며, `sector → learning` 참조가 규칙으로 막힌다. 사용자 스토리를 시작할 수 있다

---

## Phase 3: User Story 1 - 주제 목록에서 글을 골라 질문과 예시를 읽는다 (Priority: P1) 🎯 MVP

**Goal**: 공개 조회 두 엔드포인트(목록·글)를 열고, 화면이 그 응답만으로 목록과 글을 텍스트로 그린다. 게시된 글이 없으면 빈 목록이 정상 화면이고, 불러오지 못하면 빈 목록과 다르게 보인다.

**Independent Test**: 통합 테스트로 심은 게시 판 하나로 `/learn` 목록과 `/learn/{articleId}`가 보이고, 초안뿐인 글은 어디에도 나오지 않으며, 요청을 막으면 다시 시도 수단이 보이는지 확인한다(quickstart 2-1·2-2·4-1·4-2). 진단 기능 없이 검증할 수 있다.

### Backend — 공개 조회 (계약 §1·§2)

- [X] T015 [P] [US1] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/result/LearningArticleSummaryResult.java`(`articleId`·`title`·`question`·`readingOrder`)와 `LearningArticleResult.java`(계약 §2 응답 모양 — `blocks[]`는 `term` 블록에 `label`·`sentence`가 채워진 형태, `example`은 `available`·`sectors[].{code,name,weightTenths}`·`missingSectorCodes`·`unavailableReason`, `sources`, `publishedAt`). 필드 이름은 계약 JSON과 1:1
- [X] T016 [US1] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/service/LearningQueryService.java`(`@Transactional(readOnly = true)`) — `listPublished()`: `status='PUBLISHED'`인 글의 **공개 중인 판 snapshot**에서 제목·질문을 읽어 `readingOrder`, `articleId` 오름차순으로; `getPublished(String articleId)`: 형식이 틀리거나 없거나 게시되지 않은 글은 모두 `ArticleUnavailableException`(404 `LEARNING_ARTICLE_UNAVAILABLE`, 셋을 가르지 않는다 — 계약 §2 "응답 404"), 있으면 판 snapshot의 `term` 블록에 **같은 snapshot의 `terms`** 문장을 채운다(초안 `learning_term`을 읽지 않는다 — SC-003·SC-006). 예시 판정([research](research.md) R8): `SectorMapPort.currentMap()`이 비어 있으면 `available=false`·`SECTOR_MAP_NOT_READY`·`name=null`, 코드가 하나라도 없으면 `available=false`·`SECTOR_NOT_IN_MAP`·`missingSectorCodes`, 모두 있으면 `available=true`와 대응표의 이름. 판정 결과를 저장하지 않는다. `backend/src/main/java/com/ssafy/thorp/learning/application/exception/ArticleUnavailableException.java`(`BusinessException`) (T011·T015 의존)
- [X] T017 [US1] (BE) `backend/src/main/java/com/ssafy/thorp/learning/presentation/response/LearningArticleListResponse.java`·`LearningArticleResponse.java`(result → response 변환 record)와 `backend/src/main/java/com/ssafy/thorp/learning/presentation/controller/LearningController.java` — `GET /learning/articles`, `GET /learning/articles/{articleId}`. 다른 컨트롤러와 같은 `ApiResponse` 껍데기. `SecurityConfig`는 `anyRequest().permitAll()`이라 추가 설정이 없다 — 확인만 하고 Javadoc에 "비로그인 허용(계약 머리말, FR-012)"을 적는다. 사용자 식별을 읽지 않는다 (T016 의존)

### Backend 테스트

- [X] T018 [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearningPublicApiIT.java`(`@SpringBootTest` + `@AutoConfigureMockMvc` + `PostgresTestSupport`, `backtest/application/service/BacktestVersionsApiIT`와 같은 기동 방식)와 시드 도우미 `backend/src/test/java/com/ssafy/thorp/learning/support/LearningSeeds.java`(B02의 게시 서비스가 아직 없으므로 `JdbcTemplate`로 판·색인·글 상태를 직접 심는다. 001 대응표도 같은 방식으로 심는다) — 게시 전 `GET /learning/articles`가 200 `articles: []`(quickstart 2-1); 초안뿐인 `returns-and-losses`는 목록에 없고 단건은 404 `LEARNING_ARTICLE_UNAVAILABLE`(quickstart 2-2, SC-006); 형식이 틀린 `articleId`도 같은 404; 판을 심은 뒤 목록·단건 `data`가 `docs/specs/learning-api/examples/articles-list.json`·`article.json`과 JSON 동치(`BacktestResultApiIT`의 `EXAMPLES` 경로 방식); `term` 블록 문장이 심은 판의 문장과 같고 초안 `learning_term`을 그 뒤 바꿔도 응답이 그대로(SC-006, quickstart 3-5의 서버 쪽); 대응표가 없으면 200에 `example.available=false`·`SECTOR_MAP_NOT_READY`; 대응표에서 코드 하나를 빼면 `SECTOR_NOT_IN_MAP`과 `missingSectorCodes`(quickstart 4-4의 서버 쪽); 목록 순서가 `readingOrder`, `articleId` 순 (T003·T017 의존)
- [X] T019 [P] [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/application/service/LearningQueryServiceTest.java` — 저장소·포트를 목으로: 없는 글·초안 글·형식 오류가 같은 예외; 예시 판정 세 갈래; `term` 블록 문장이 snapshot에서 오고 초안 용어에서 오지 않는다; 대응표 조회는 단건 조회당 한 번이다 (T016 의존)

### Frontend — 기반 모듈과 컴포넌트

- [X] T020 [P] [US1] (FE) `frontend/src/api/learning.ts` — `fetchLearningArticles(signal?)` → `LearningListResult`: `{ status: 'loaded'; articles }` \| `{ status: 'empty' }` \| `{ status: 'failed'; kind; code; message }`; `fetchLearningArticle(articleId, signal?)` → `{ status: 'loaded'; article }` \| `{ status: 'unavailable' }`(404 `LEARNING_ARTICLE_UNAVAILABLE`) \| `{ status: 'failed'; … }`. **빈 목록과 실패를 이 층에서 가른다**(`frontend/src/api/sectors.ts`의 `SectorCatalogResult`와 같은 이유). 오류 분류는 `api/client.ts`의 `normalizeApiError`를 그대로 쓴다. 예외를 던지지 않고 재시도하지 않는다. `apiClient` 경유, URL 하드코딩 금지
- [X] T021 [P] [US1] (FE) `frontend/src/components/learning/ArticleBlocks.tsx`·`ArticleBlocks.module.css` — 블록 목록을 **텍스트 노드로만** 그린다([research](research.md) R3·R13): `heading` → `h2`, `paragraph` → `p`, `term` → 용어 이름(`dt`)과 문장(`dd`), `hypothetical` → 눈에 보이는 '가상의 계산 예' 표시가 붙은 `aside`(FR-005). `dangerouslySetInnerHTML`을 쓰지 않는다. 알 수 없는 `type`은 아무것도 그리지 않고 개발 모드에서만 `console.warn`한다
- [X] T022 [P] [US1] (FE) `frontend/src/components/learning/LearningLoadFailure.tsx` — `role="alert"`로 불러오지 못했다는 한 문장(서버 `message`가 있으면 그것, 없으면 고정 문장)과 '다시 시도' `button`(`onRetry`). 빈 목록 안내와 다른 모양이어야 하며(SC-007) 스스로 재시도하지 않는다(FR-015)
- [X] T023 [US1] (FE) `frontend/src/pages/LearningListPage.tsx`·`LearningListPage.module.css` — `/learn`. `AppHeader` → 목록 조회 → 상태별 화면: `loaded`는 응답 순서 그대로(정렬하지 않는다) 제목·질문과 `/learn/{articleId}` `Link`; `empty`는 "아직 게시된 글이 없습니다" 한 문장(`alert`가 아니다)과 진단 시작 `Link`(`/diagnosis/sectors`); `failed`는 `LearningLoadFailure`와 **그대로 남는** 진단 시작 `Link`(spec Edge Cases). 설문·숙련도 질문·진도 표시가 없다(FR-011·FR-012). `sessionStorage`·`localStorage`에 아무것도 쓰지 않는다. 다시 시도는 버튼을 누를 때만 다시 부른다 (T020·T022 의존)
- [X] T024 [US1] (FE) `frontend/src/pages/LearningArticlePage.tsx`·`LearningArticlePage.module.css` — `/learn/:articleId`. `loaded`: 제목(`h1`)·질문·`ArticleBlocks`·예시 절(섹터 이름 또는 코드와 비중을 `weightTenths / 10`을 소수 한 자리 %로 표시, "설명을 위한 예시이며 투자 권고가 아닙니다" 문장 — US1 시나리오 4)·출처 목록(`label`, `url`이 있으면 `a`)·`publishedAt`. 예시 절 아래에 US2의 `ExampleEntry` 자리, 머리에 US3의 `ReturnToDiagnosisLink` 자리를 비워 둔다. `unavailable`: 오류 화면이 아니라 "지금은 볼 수 없는 글입니다" 한 문장과 `/learn` `Link`(spec Edge Cases). `failed`: `LearningLoadFailure`. 글 안의 다른 링크(`/learn`)에는 받은 `location.state`를 그대로 넘긴다([term-help-ui](contracts/term-help-ui.md) §3). 저장소에 쓰지 않는다 (T020·T021·T022 의존)
- [X] T025 [US1] (FE) `frontend/src/App.tsx` — `/learn`의 `NotReadyPage`를 lazy `LearningListPage`로 바꾸고 `/learn/:articleId`에 lazy `LearningArticlePage`를 더한다. `NotReadyPage`를 쓰는 경로가 더 없으면 그 lazy import를 지운다(파일은 남긴다 — 다른 spec의 준비 중 메뉴가 다시 쓸 수 있다). 주석에 "013 배우기. 글의 예시는 002 `/diagnosis/sectors`로 넘어간다"를 적는다. `AppHeader`의 '배우기' 항목은 이미 `/learn`이라 바꾸지 않는다 (T023·T024 의존)

### Frontend 테스트

- [X] T026 [P] [US1] (FE 테스트) `frontend/src/api/learning.test.ts` — 200 목록 → `loaded`, 200 빈 배열 → `empty`, 404 `LEARNING_ARTICLE_UNAVAILABLE` → `unavailable`, 다른 404·5xx·네트워크 → `failed`이고 `code`가 서버 값 그대로; 예외를 던지지 않는다; 요청은 호출당 한 번. `frontend/vite.config.ts` `test.include`에 `'src/api/learning.test.ts'`를 더한다 (T020 의존)
- [X] T027 [P] [US1] (FE 테스트) `frontend/src/components/learning/ArticleBlocks.test.tsx` — `heading`이 `h2`; `term` 블록에 이름과 문장이 **받은 그대로**; `hypothetical` 블록에 '가상의 계산 예' 텍스트가 보인다; `text`에 `<b>강조</b>`를 넣으면 태그 문자열이 그대로 보이고 `b` 요소가 생기지 않는다(R3) (T021 의존)
- [X] T028 [US1] (FE 테스트) `frontend/src/pages/LearningListPage.test.tsx`(`frontend/src/test/renderWithRouter.tsx`, `learningFixtures`) — 응답 순서 그대로 보인다; `empty`면 `alert`가 없고 진단 시작 링크가 있다; `failed`면 `role="alert"`와 '다시 시도' 버튼이 있고 진단 시작 링크가 그대로 있으며 빈 목록 문장이 없다(SC-007, quickstart 4-2); 다시 시도를 누르기 전에는 두 번째 요청이 없고 누르면 한 번 더 부른다; 설문·숙련도 문구가 없다(시나리오 5) (T023·T025 의존)
- [X] T029 [US1] (FE 테스트) `frontend/src/pages/LearningArticlePage.test.tsx` — 질문 하나와 블록이 보인다; 예시 비중이 % 표기로 보이고 투자 권고가 아니라는 문장이 있다(시나리오 4); 출처 `url`이 `a`로; `unavailable`이면 한 문장과 목록 링크가 있고 `alert`가 없다(quickstart 3-8 화면 쪽); `failed`면 `alert`와 다시 시도; 다시 시도 버튼이 `button`이고 `Tab`으로 닿는다(FR-019) (T024·T025 의존)

**Checkpoint**: 목록과 글이 응답만으로 그려지고, 초안은 어디에도 나오지 않으며, 실패와 빈 목록이 다르게 보인다

---

## Phase 4: User Story 2 - 예시로 바로 진단을 시작한다 (Priority: P1)

**Goal**: 글의 예시 섹터·비중을 002 계약 그대로 navigation state에 실어 `/diagnosis/sectors`로 같은 탭 이동한다. 예시 섹터가 대응표에 없으면 버튼 대신 안내와 빈 화면 연결을 보인다.

**Independent Test**: 글에서 '이 예시로 전략 진단하기'를 눌렀을 때 `navigate`가 002 계약 §2 모양의 state로 불리는지, `available=false`면 버튼이 없고 안내와 빈 섹터 화면 연결이 있는지 확인한다(quickstart 4-3의 013 쪽·4-4). 002가 그 값을 받아 채우는 확인은 002 T020 뒤다(위 ②).

- [X] T030 [US2] (FE) `frontend/src/components/learning/ExampleEntry.tsx`·`ExampleEntry.module.css` — props `articleId`·`example: LearningExample`. `available=true`: '이 예시로 전략 진단하기' `button` 하나(글 화면의 유일한 행동 버튼, 원칙 II) → `navigate('/diagnosis/sectors', { state: { sectorSelectionExample: { source: 'learning', topicId: articleId, selected: sectors.map(({ code, weightTenths }) => ({ code, weightTenths })) } } })` — `weightTenths`를 **변환하지 않고** 싣고 `name`은 싣지 않는다([term-help-ui](contracts/term-help-ui.md) §4). `available=false`: 버튼을 그리지 않고 `unavailableReason`별 한 문장(`SECTOR_NOT_IN_MAP`은 `missingSectorCodes`를 함께, `SECTOR_MAP_NOT_READY`는 대응표를 확인할 수 없다는 문장)과 state 없는 `Link`(`/diagnosis/sectors`) "빈 섹터 선택 화면으로 가기"(US2 시나리오 3). 덮어쓰기 확인은 002가 한다 — 여기서 묻지 않는다(FR-003)
- [X] T031 [US2] (FE) `frontend/src/pages/LearningArticlePage.tsx`의 예시 절 자리(T024)에 `ExampleEntry`를 넣는다. 예시가 없는 판(`example` 없음)은 게시 전 검사가 막으므로 화면은 방어 코드 없이 `example`을 필수로 다룬다 (T030 의존)
- [X] T032 [US2] (FE 테스트) `frontend/src/components/learning/ExampleEntry.test.tsx` — 버튼을 누르면 `navigate`가 `/diagnosis/sectors`와 정확히 `{ sectorSelectionExample: { source: 'learning', topicId, selected: [{ code, weightTenths }] } }` 모양으로 불리고 `weightTenths`가 응답 값 그대로이며 `name` 키가 없다; `available=false`면 버튼이 없고 안내 문장과 `/diagnosis/sectors` 링크(state 없음)가 있다; `SECTOR_NOT_IN_MAP`이면 빠진 코드가 문장에 있다; 버튼은 `button` 요소다 (T030 의존)

**Checkpoint**: 글에서 예시가 002 계약 모양으로 넘어가고, 넘길 수 없을 때 화면이 그 사실을 말한다

---

## Phase 5: User Story 3 - 진단 화면의 용어 도움말이 같은 설명을 쓴다 (Priority: P2)

**Goal**: 용어 설명 단건 조회를 열고, 002·003·005가 함께 쓰는 `TermHelp`가 세 상태(준비됨·설명 준비 중·불러오지 못함)를 코드로 가르며, 글로 갔다가 '하던 진단으로 돌아가기'로 돌아온다.

**Independent Test**: 게시된 `termId`·초안뿐인 `termId`·요청 차단의 세 경우에 `TermHelp`가 다른 문장을 보이고 호출한 화면의 입력을 건드리지 않는지, `/strategy`에서 온 글에만 돌아가기 연결이 보이는지 확인한다(quickstart 5-1·5-2·5-4). 003 화면이 아직 공용 컴포넌트를 쓰지 않으면 컴포넌트 테스트로 확인한다(quickstart §5 머리말).

### Backend — 용어 설명 조회 (계약 §3)

- [X] T033 [US3] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/result/LearningTermResult.java`(`termId`·`label`·`sentence`·`articleId`·`articleTitle`)와 `LearningQueryService.getPublishedTerm(String termId)` — `PublishedTermRepository.findPublished`로 **게시 용어 색인만** 읽는다. 형식 오류·초안뿐인 용어·게시 취소된 글의 용어·없는 용어를 가르지 않고 `TermNotReadyException`(404 `LEARNING_TERM_NOT_READY`)이다([research](research.md) R10). `backend/src/main/java/com/ssafy/thorp/learning/application/exception/TermNotReadyException.java` (T011 의존)
- [X] T034 [US3] (BE) `LearningController`에 `GET /learning/terms/{termId}`와 `backend/src/main/java/com/ssafy/thorp/learning/presentation/response/LearningTermResponse.java`. 여러 `termId`를 한 번에 받는 조회는 두지 않는다(계약 §4) (T033 의존)
- [X] T035 [US3] (BE 테스트) `LearningPublicApiIT` 확장 — 심은 판의 용어는 200이고 `data`가 `docs/specs/learning-api/examples/term.json`과 동치; 그 `sentence`가 같은 글 응답의 `term` 블록 문장과 같다(SC-003); 초안뿐인 `rebalancing`은 404 `LEARNING_TERM_NOT_READY`(quickstart 2-2); 없는 `termId`와 형식이 틀린 `termId`도 같은 코드; 색인 행을 지우면(게시 취소의 서버 결과) 같은 404 (T003·T034 의존)

### Frontend — 공용 도움말 (B04)

- [X] T036 [P] [US3] (FE) `frontend/src/api/learningGlossary.ts` — `fetchGlossaryEntry(termId: string, signal?: AbortSignal): Promise<GlossaryLookup>`, `GlossaryLookup = { status: 'ready'; entry: LearningTermEntry } | { status: 'notReady' } | { status: 'failed' }`([term-help-ui](contracts/term-help-ui.md) §1). 404 `LEARNING_TERM_NOT_READY`만 `notReady`이고 그 밖의 모든 실패(다른 404 코드 포함)는 `failed`다. 예외를 던지지 않고 재시도하지 않는다. `apiClient` 경유. 003 T057a가 이 파일에 대역을 먼저 두었으면 **본문만 바꾸고 export 이름을 유지한다**(위 ③)
- [X] T037 [P] [US3] (FE) `frontend/src/components/learning/ReturnToDiagnosisLink.tsx`와 순수 함수 `resolveLearningReturn(state: unknown): LearningReturnState | null` — 세 조건을 모두 만족할 때만 값을 돌려준다([term-help-ui](contracts/term-help-ui.md) §3): `state.learningReturn`이 있고 `path`·`label`이 문자열; `path`가 `/diagnosis/sectors`·`/strategy`이거나 `/results/`로 시작; `normalizeReturnTarget(path) === path`(`frontend/src/auth/returnTarget.ts`, 014 소유 — 고치지 않는다). 컴포넌트는 값이 있을 때만 보조 연결 `a`(React Router `Link`)를 그리고 누르면 `navigate(path)`한다. `history.back()`을 쓰지 않는다. '이 예시로 전략 진단하기'와 같은 크기의 버튼 스타일을 쓰지 않는다(FR-009, 원칙 II)
- [X] T038 [US3] (FE) `frontend/src/components/learning/TermHelp.tsx`·`TermHelp.module.css` — props `termId`·`label`·`returnTo`(모두 필수). `radix-ui` 패키지의 `Popover`(`import { Popover } from 'radix-ui'`, 새 라이브러리 없음)로 만들고 트리거는 `button`이다. 버튼 글자는 '?'이고 접근 가능한 이름이 `${label} 도움말`이다 — 붙이는 화면이 용어 이름을 이미 옆에 보이므로 이름을 두 번 보이지 않는다. **여는 행동이 요청을 시작한다**(마운트 때 부르지 않는다). 상태별 표시([term-help-ui](contracts/term-help-ui.md) §2): 여는 중은 짧은 텍스트; `ready`는 이름·문장(받은 그대로)·`Link`(`/learn/${articleId}`, `state: { learningReturn: returnTo }`, 같은 탭); `notReady`는 '설명 준비 중' 일반 텍스트, 연결 없음; `failed`는 `role="alert"` 한 문장과 '다시 시도' `button`(누를 때만 다시 부른다). Esc로 닫히고 닫으면 초점이 트리거로 돌아온다(radix 기본). 호출한 화면의 상태를 바꾸는 prop·callback을 두지 않는다(FR-009·FR-010) (T036 의존)
- [X] T039 [US3] (FE) `frontend/src/pages/LearningArticlePage.tsx`의 머리 자리(T024)에 `ReturnToDiagnosisLink`를 넣고, 글 안의 `/learn` 링크가 `location.state`를 그대로 넘기는지 확인한다. 배우기 메뉴로 직접 들어오면 state가 없어 연결이 보이지 않는다(spec Edge Cases) (T037 의존)

### Frontend 테스트

- [X] T040 [P] [US3] (FE 테스트) `frontend/src/api/learningGlossary.test.ts` — 200 → `ready`; 404 `LEARNING_TERM_NOT_READY` → `notReady`; 404 다른 코드·5xx·네트워크·시간 초과 → `failed`; 어느 경우에도 reject되지 않는다; 요청은 호출당 한 번. `test.include`에 `'src/api/learningGlossary.test.ts'` 추가 (T036 의존)
- [X] T041 [P] [US3] (FE 테스트) `frontend/src/components/learning/ReturnToDiagnosisLink.test.tsx` — `resolveLearningReturn`: state 없음 → null; `path: '/login'` → null; `'//evil.example'` → null; `'/strategy'`·`'/diagnosis/sectors'`·`'/results/BT-1'` → 값; 컴포넌트는 값이 있을 때만 링크를 그리고 누르면 `navigate`가 그 `path`로 불린다; 요소가 `a`다 (T037 의존)
- [X] T042 [US3] (FE 테스트) `frontend/src/components/learning/TermHelp.test.tsx`(`@testing-library/user-event`) — 열기 전에는 요청이 0건; 트리거를 누르면 한 번 부른다; `ready`면 문장이 받은 그대로 보이고 링크 `href`가 `/learn/{articleId}`이며 `navigate` state에 `learningReturn`이 실린다; `notReady`면 '설명 준비 중'과 링크 없음; `failed`면 `alert`와 다시 시도이고 누르기 전에는 두 번째 요청이 없다; `Escape`로 닫히고 초점이 트리거로 돌아온다(quickstart 5-4); 트리거는 `button`; 부모 컴포넌트의 입력 상태가 열고 닫는 동안 그대로다(US3 시나리오 3) (T038 의존)

**Checkpoint**: 도움말이 세 상태를 코드로 가르고, 글로 갔다가 돌아오는 길이 허용 목록 안에서만 열린다

---

## Phase 6: User Story 4 - 학습 없이도 진단을 시작할 수 있다 (Priority: P3)

**Goal**: 배우기가 진단의 전제가 아니고, 진도·열람 기록을 남기지 않는다는 것을 확인한다. 다른 화면의 동작이므로 확인 항목에 가깝다(spec "Why this priority").

**Independent Test**: 배우기 화면이 저장소에 아무것도 쓰지 않고, 진단 진입 경로 어디에도 학습 요구가 없는지 확인한다(quickstart 4-1 마지막 항목, 5-2).

- [X] T043 [US4] (FE 테스트) `frontend/src/pages/LearningListPage.test.tsx`·`LearningArticlePage.test.tsx` 확장 — `Storage.prototype.setItem`을 스파이로 걸고 목록·글을 열고 닫는 동안 호출이 0건(FR-012, quickstart 4-1); "학습 완료"·"수강" 같은 문구와 진도 표시 요소가 없다(시나리오 1·3) (T028·T029 의존)
- [X] T044 [US4] (FE) 진단 진입에 학습 요구가 없음을 확인만 한다 — `grep -rn "learn" frontend/src/pages/SectorSelectionPage.tsx frontend/src/pages/StrategySettingsPage.tsx frontend/src/auth/ProtectedRoute.tsx`가 배우기 상태를 읽는 코드 0건이고, `frontend/src/components/navigation/AppHeader.tsx`의 '배우기' 항목이 일반 메뉴 링크 그대로다(FR-011, SC-004). 고칠 것이 없으면 결과만 MR 본문 "테스트" 절에 적는다. 013이 002·003 화면을 고치지 않는다

**Checkpoint**: 네 스토리(US1·US2·US3·US4)가 관리 화면 없이 동작한다. 실제 글은 B02·B05가 서기 전까지 통합 테스트로 심은 판으로만 보인다

---

## Phase 7: User Story 5 - 서비스 운영자가 관리 계정으로 글과 용어 설명을 편집·게시한다 (Priority: P2)

**Goal**: 관리 권한 판정 한 곳, 관리 일곱 엔드포인트, 서버의 게시 전 검사 아홉 가지, 게시·게시 취소 트랜잭션, 관리 화면. 초안은 어떤 경로로도 공개되지 않는다.

**Independent Test**: 허용 목록의 계정으로 로그인해 `returns-and-losses`를 검사에 걸리게 저장·게시하면 항목 셋이 위치와 함께 돌아오고 아무것도 바뀌지 않으며, 고쳐서 게시하면 로그아웃한 탭의 목록·글·도움말에 함께 보이고, 게시 취소하면 셋에서 함께 사라지는지 확인한다(quickstart 3-1~3-8). 비로그인·권한 없는 로그인·CSRF 누락 요청이 각각 401·404·403인지 확인한다.

### Backend — 권한과 설정 (B02)

- [X] T045 [P] [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/port/LearningAdminPolicy.java`(`boolean isAdmin(long userId)`)와 구현 `backend/src/main/java/com/ssafy/thorp/learning/infrastructure/admin/ConfiguredLearningAdminPolicy.java` — 설정 `thorp.learning.admin-user-ids`(쉼표 구분 사용자 ID 목록)를 읽는다. 비어 있으면 아무도 관리자가 아니다([research](research.md) R5). `backend/src/main/resources/application.yml`에 `thorp.learning.admin-user-ids: ${THORP_LEARNING_ADMIN_USER_IDS:}`(주석: 비어 있으면 관리자 없음. 014가 역할을 주면 이 구현 한 클래스만 바꾼다), `application-prod.yml`에는 `${THORP_LEARNING_ADMIN_USER_IDS}`로 **기본값 없이** 둔다(`thorp.backtest.limits.*`와 같은 이유 — 운영자가 값을 명시해야 한다. 명시한 빈 값은 관리자 없음이다). `backend/src/test/java/com/ssafy/thorp/common/config/ApplicationProfileConfigTest.java`의 prod 러너에 그 환경 변수를 더한다. 운영 배포가 이 변수를 넘기도록 `infra/docker-compose.prod.yml`의 backend `environment`에 `THORP_LEARNING_ADMIN_USER_IDS: ${THORP_LEARNING_ADMIN_USER_IDS}`를, `infra/.env.prod.example`에 빈 값 예시와 주석(쉼표 구분 사용자 ID, 비우면 관리자 없음)을 더한다(`THORP_LIMIT_*`와 같은 방식). 빠지면 prod 기동이 자리표시자를 풀지 못해 실패하므로, 운영 `.env`에 값을 넣어야 한다는 것을 MR 본문 "리뷰 포인트"에 적는다
- [X] T046 [P] [US5] (BE) `backend/src/main/java/com/ssafy/thorp/common/config/SecurityConfig.java` — `/auth/me` 옆에 `.requestMatchers("/admin/learning/**").authenticated()` 한 줄. 비로그인은 기존 진입점이 401 `AUTHENTICATION_REQUIRED` 봉투를 낸다. 상태 변경 요청의 CSRF는 기존 설정이 그대로 적용된다([learning-admin-api](contracts/learning-admin-api.md) §0)
- [X] T047 [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/presentation/support/LearningAdminAccess.java`(`@Component`) — `long requireAdminUserId()`: 인증 주체를 004 `backtest/presentation/support/CurrentRequester`와 **같은 규약**(세션의 `users.id` 문자열)으로 읽고, `LearningAdminPolicy.isAdmin`이 거짓이면 `BusinessException(ErrorCode.RESOURCE_NOT_FOUND)`(404, 경로의 존재를 알리지 않는다 — [research](research.md) R6). `CurrentRequester`를 import하지 않는다(`learning → backtest` 의존을 만들지 않는다). Javadoc에 "014가 역할을 제공하면 이 클래스와 `ConfiguredLearningAdminPolicy`만 바꾼다"를 적는다. 관리 컨트롤러의 모든 메서드가 첫 줄에서 이것을 부른다 (T045 의존)

### Backend — 게시 전 검사 (B02)

- [X] T048 [P] [US5] (INFRA) `backend/src/main/resources/learning/publish-checks-v1.yml` — `version: learning-publish-checks-v1`, `forbiddenExpressions`(다섯 무리, [data-model](data-model.md) §6-1의 출발 목록 그대로. `보장` 단독은 넣지 않는다), `resultNumberPatterns`(§6-2의 셋: 숫자+`%`/`%p`, 숫자+`원`/`만 원`/`억 원`/`억`, 숫자+`배`), `allowedExceptions: ["100%"]`. 파일 머리 주석에 "출발안이다. 목록을 바꾸면 파일 이름과 `version`을 함께 올리고 옛 파일은 지우지 않는다(판이 버전을 기록한다)"를 적는다
- [X] T049 [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/port/PublishCheckRulesPort.java`(`PublishCheckRules current()`)와 구현 `backend/src/main/java/com/ssafy/thorp/learning/infrastructure/rules/YamlPublishCheckRulesLoader.java` — 기동 시 클래스패스의 `learning/publish-checks-v1.yml`을 Jackson YAML(003 `FileParameterRegistry`와 같은 방식)로 읽어 `PublishCheckRules`를 만든다. 파일이 없거나 형식이 틀리면 **기동 실패**(빈 목록으로 대신하지 않는다 — 원칙 VI) (T048·T050 의존)
- [X] T050 [P] [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/domain/model/PublishCheckRules.java`(`version`·금지 표현·숫자 패턴·허용 예외), `PublishCheckFailure.java`(`check`·`CheckLocation location`·`matched`(nullable)·`message`), `CheckLocation.java`(`part`: `title`·`question`·`example`·`block`(+`index`)·`term`(+`termId`)), `PublishCheckResult.java`(`failures` 목록, 비어 있으면 통과), `PublishCheck.java` — `PublishCheckResult run(LearningArticle draft, PublishCheckRules rules, Optional<Set<String>> openSectorCodes)`인 **순수 함수**. [research](research.md) R7 표의 아홉 검사를 **모두 실행하고 걸린 항목을 전부** 돌려준다: `REQUIRED_FIELDS`(제목·질문 비었거나 `paragraph` 없음), `EXAMPLE_PRESENT`, `EXAMPLE_SECTOR_COUNT`(< 2), `EXAMPLE_SECTOR_IN_MAP`(`openSectorCodes`가 비어 있으면 "대응표를 확인할 수 없다"는 `message`로 걸린다 — 통과로 세지 않는다), `EXAMPLE_WEIGHT_SUM`(≠ 1000, `message`에 지금 합계를 %로), `TERM_BLOCKS_MATCH`(이 글의 용어가 `term` 블록으로 정확히 한 번씩, 블록이 없는 용어를 가리키지 않음), `TERM_SENTENCE`(이름·문장 비었거나 300자 초과), `FORBIDDEN_EXPRESSION`(공백을 하나로 줄인 문자열에서 부분 일치, 위치와 `matched`), `RESULT_NUMBER_OUTSIDE_HYPOTHETICAL`(`hypothetical` 밖 텍스트의 숫자 패턴, `100%`는 예외). `message`는 계약 §6 예시 문장을 쓴다. `backend/src/main/java/com/ssafy/thorp/learning/domain/exception/PublishCheckFailedException.java` — `BusinessException(LEARNING_PUBLISH_CHECK_FAILED, …, Map.of("checkVersion", v, "failures", […]))`. 대응표 조회는 하지 않는다 (T009 의존)

### Backend — 관리 유스케이스와 엔드포인트 (B02)

- [X] T051 [P] [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/command/`: `CreateArticleCommand`(`articleId`·`title`·`readingOrder`), `SaveDraftCommand`(`expectedDraftVersion`·`title`·`question`·`readingOrder`·`blocks`·`example`(nullable)·`sources`·`terms`), `PublishCommand`(`expectedDraftVersion`·`actorUserId`), `UnpublishCommand`(`actorUserId`); `backend/src/main/java/com/ssafy/thorp/learning/application/result/`: `AdminArticleSummaryResult`(계약 §2 필드), `AdminArticleResult`(§4), `PublishResult`(§6 200), `UnpublishResult`(§7)
- [X] T052 [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/service/LearningAdminService.java` — `@Transactional` 유스케이스 여섯([learning-admin-api](contracts/learning-admin-api.md)):
  - `list()`: 모든 글을 `readingOrder`, `articleId` 순으로. `hasUnpublishedChanges = status == PUBLISHED && article.draftVersion != publishedRevision.snapshot.draftVersion`; `needsUpdate`는 공개 중인 판의 예시 코드를 `SectorMapPort`와 대조(초안 글은 `false`, 대응표를 읽지 못하면 `null`); `lastPublication`은 `PublicationEventRepository.findLastByArticleId`(없으면 `null`). `actorUserId`만 싣는다
  - `create(cmd)`: `existsByArticleKey`면 `ArticleIdTakenException`(409); 새 글은 `DRAFT`·`draftVersion 0`·빈 본문
  - `getDraft(articleId)`: 없으면 `ArticleNotFoundException`(404). `publishedRevisionNo`는 공개 판 번호 또는 `null`
  - `saveDraft(articleId, cmd)`: `findByArticleKeyForUpdate` → `refresh` → 요청 `terms[].termId`를 다른 글이 쓰면 `TermIdTakenException`(409, `details.termId`·`ownerArticleId`) → `article.saveDraft(...)`(버전 불일치는 `DraftChangedException`) → 용어 행을 통째로 바꾼다(`replaceForArticle`). 공개 내용은 바뀌지 않는다
  - `publish(articleId, cmd)`: 잠금 → 버전 확인 → `SectorMapPort.currentMap()` → `PublishCheck.run(...)` → 걸리면 `PublishCheckFailedException`(422, **아무것도 바꾸지 않는다** — 판·이벤트를 남기지 않는다) → 통과면 같은 트랜잭션에서: 새 `ArticleRevision`(`revisionNo = 마지막 + 1`, 게시 취소 뒤에도 이어서 오른다, snapshot에 `draftVersion` 포함, `checkVersion`·`sectorMapVersion`·`publishedByUserId`), `article.publish(...)`, `PublishedTermRepository.replaceForArticle`, `PUBLISH` 이벤트. `PublishResult` 반환
  - `unpublish(articleId, cmd)`: 잠금 → `PUBLISHED`면 `article.unpublish()`·`deleteForArticle`·`UNPUBLISH` 이벤트(`revisionId`는 내려간 판) → `unpublishedRevisionNo`; 이미 `DRAFT`면 아무것도 바꾸지 않고 `null`로 200, 이벤트 없음
  - `backend/src/main/java/com/ssafy/thorp/learning/application/exception/ArticleNotFoundException.java`·`ArticleIdTakenException.java`·`TermIdTakenException.java`(`BusinessException`). 삭제 유스케이스는 없다(spec Out of Scope) (T011·T047·T049·T050·T051 의존)
- [X] T053 [US5] (BE) 요청·응답·컨트롤러 — `backend/src/main/java/com/ssafy/thorp/learning/presentation/request/`: `CreateArticleRequest`(`@Pattern` `articleId`, `@Size` `title` ≤100, `@Min(1) @Max(999)` `readingOrder`), `SaveDraftRequest`(`@NotNull expectedDraftVersion`, 길이·개수 검사, `blocks[]`는 `type`으로 판별하는 Jackson 다형 record(`@JsonTypeInfo(use = NAME, property = "type")` + `@JsonSubTypes` 넷) — 알 수 없는 `type`은 역직렬화 실패로 400이 된다(T054), `example`은 `null` 허용이고 `sectors[]` ≤20·`weightTenths` 0..1000·코드 중복 없음, `sources[].url`은 `http`·`https`만, `terms[].termId` 형식), `PublishRequest`(`@NotNull expectedDraftVersion`); `presentation/response/`: `AdminAccessResponse`(`{ admin: true }`), `AdminArticleListResponse`, `AdminArticleResponse`, `PublishResponse`, `UnpublishResponse`; `backend/src/main/java/com/ssafy/thorp/learning/presentation/controller/LearningAdminController.java` — `GET /admin/learning/access`(200 `{admin:true}`; 권한이 없으면 §0의 404이지 `admin:false`가 아니다), `GET /admin/learning/articles`, `POST /admin/learning/articles`(201), `GET /admin/learning/articles/{articleId}`, `PUT /admin/learning/articles/{articleId}`, `POST /admin/learning/articles/{articleId}/publish`, `POST /admin/learning/articles/{articleId}/unpublish`(본문 없음). 모든 메서드가 `LearningAdminAccess.requireAdminUserId()`로 시작한다. `@Valid` 필수 (T047·T052 의존)
- [X] T054 [US5] (BE) `backend/src/main/java/com/ssafy/thorp/common/web/advice/GlobalExceptionHandler.java` 보강 둘 — (a) `handleMethodArgumentNotValid`의 400 봉투에 `details.fields`(`[{ field, message }]` 전체)를 더한다(계약 §5 "`error.details.fields`에 위반 위치"). `details`는 `NON_NULL`이라 다른 도메인의 400에는 키가 추가될 뿐이지만, `grep -rn "details" backend/src/test`로 400 본문 전체를 대조하는 테스트가 있는지 확인하고 있으면 기대값을 맞춘다; (b) `handleHttpMessageNotReadable`을 override해 `ApiResponse.fail(ErrorResponse.of(INVALID_INPUT, "본문을 읽을 수 없습니다: …"))` 400을 낸다 — 지금은 override가 없어 스프링 기본 ProblemDetail 본문이 나가므로 알 수 없는 블록 `type`이 봉투 없는 응답이 된다. 조용히 버리지 않고 거절한다는 것이 목적이다([data-model](data-model.md) §4, 원칙 VI). 두 변경 모두 MR 본문 "리뷰 포인트"에 공통 계층 변경임을 적는다. **구현 메모**: (b)는 override하지 않았다. 병합된 `GlobalExceptionHandler.handleExceptionInternal`이 이미 읽을 수 없는 본문을 `INVALID_INPUT` 400 봉투로 감싸고 있다(`GlobalExceptionHandlerTest.malformedJsonBody`, `LearningAdminApiIT.unknownBlockTypeIsRejected`). 파서 메시지를 싣지 않는 기존 방침도 그대로 둔다

### Backend 테스트 (B02)

- [X] T055 [P] [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/domain/model/PublishCheckTest.java` — 아홉 검사가 각각만 걸리는 초안 아홉과 모두 통과하는 초안 하나; quickstart 3-2의 초안(비중 50.0·45.0, `paragraph`에 `추천 비중`, 용어 문장에 `-30%`)에서 `EXAMPLE_WEIGHT_SUM`·`FORBIDDEN_EXPRESSION`·`RESULT_NUMBER_OUTSIDE_HYPOTHETICAL` 셋이 **모두** 돌아오고 위치가 계약 §6 예시와 같다; `openSectorCodes`가 비어 있으면 `EXAMPLE_SECTOR_IN_MAP`(quickstart 3-4); `hypothetical` 안의 `120만 원`은 걸리지 않고 `paragraph`의 `100%` 하나는 예외; 제목 `과거가 미래를 보장하지 않는 이유`는 걸리지 않는다; 공백을 둘 넣은 `추천  비중`도 걸린다 (T050 의존)
- [X] T056 [P] [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/rules/YamlPublishCheckRulesLoaderTest.java` — 실제 파일이 읽히고 `version`이 `learning-publish-checks-v1`이며 표현·패턴 수가 파일과 같다; 합성 YAML(`ByteArrayResource`)에서 `version` 누락·패턴 문법 오류가 기동 실패로 이어진다 (T049 의존)
- [X] T057 [P] [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/admin/ConfiguredLearningAdminPolicyTest.java` — 빈 값이면 모두 거짓; `"1, 2"`처럼 공백이 있어도 파싱; 숫자가 아닌 항목은 기동 실패 (T045 의존)
- [X] T058 [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearningAdminAccessIT.java`(`@SpringBootTest` + `@AutoConfigureMockMvc` + `PostgresTestSupport`, `thorp.learning.admin-user-ids=7`) — 비로그인 `GET /admin/learning/access`·`GET /admin/learning/articles`가 401 `AUTHENTICATION_REQUIRED`; `support/Requesters.as(8)`(허용 목록에 없음)은 일곱 엔드포인트 모두 404 `RESOURCE_NOT_FOUND`이고 `admin:false` 200이 없다; `as(7)`의 `PUT`·`POST`에 `csrf()`가 없으면 403 `CSRF_TOKEN_INVALID`; `as(7)`의 `access`는 200 `{admin:true}`(quickstart 1 "권한", 3-1) (T046·T047·T053 의존)
- [X] T059 [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearningAdminApiIT.java`(같은 기동 방식, `as(7)` + `csrf()`, `LearningSeeds`로 001 대응표를 심는다) — 목록이 초기 자리 아홉이고 모두 `DRAFT`·`lastPublication: null`; 만들기 201과 같은 `articleId` 409 `LEARNING_ARTICLE_ID_TAKEN`, `Bad_Key` 400; 없는 글 404 `LEARNING_ARTICLE_NOT_FOUND`; 저장 200에 `draftVersion` 1 증가, 옛 `expectedDraftVersion` 409 `LEARNING_DRAFT_CHANGED`와 `details.currentDraftVersion`(quickstart 1 "덮어쓰기 방지"), 다른 글의 `termId` 409 `LEARNING_TERM_ID_TAKEN`과 `details.termId`·`ownerArticleId`, 알 수 없는 블록 `type` 400 봉투, 요청에서 뺀 용어가 초안에서 빠진다; quickstart 3-2 초안 게시 → 422이고 `error`가 `docs/specs/learning-api/examples/publish-check-failed.json`과 동치, 판·이벤트 행이 0이고 공개 조회가 그대로 404; 고친 초안 게시 → 200이 `publish-success.json` 모양(`revisionNo 1`)이고 같은 요청 직후 공개 목록·글·용어가 새 판을 본다(quickstart 3-3, SC-003), 관리 목록 `lastPublication.actorUserId`가 7(FR-018); 게시 뒤 문단만 저장하면 공개 응답이 그대로이고 `hasUnpublishedChanges: true`(quickstart 3-5, SC-006); 대응표에서 예시 코드를 빼면 `needsUpdate: true`, 대응표 행을 지우면 `null`; 대응표를 되돌린 뒤 글 제목과 용어 `label`·`sentence`를 바꿔 다시 게시하면(`revisionNo 2`) 같은 `articleId`·`termId`의 공개 글·용어가 200이고 새 제목·문장을 보인다(US3 시나리오 7, SC-003, quickstart 5-3의 서버 쪽); 게시 취소 200 `unpublishedRevisionNo 2` 뒤 공개 글·용어가 404이고 이벤트가 `UNPUBLISH`(quickstart 3-8); 한 번 더 취소하면 200 `null`이고 이벤트 수가 그대로; 다시 게시하면 `revisionNo 3`; 대응표가 없는 DB에서 게시하면 `EXAMPLE_SECTOR_IN_MAP`(quickstart 3-4) (T003·T053·T054 의존)

### Frontend — 관리 API와 편집기 부품 (B05)

- [X] T060 [P] [US5] (FE) `frontend/src/api/learningAdmin.ts` — `checkAdminAccess()` → `'admin' | 'denied'(404) | 'unauthenticated'(401) | 'failed'`; `fetchAdminArticles()`, `createArticle(req)`(409 → `{ status: 'idTaken' }`), `fetchAdminArticle(articleId)`, `saveDraft(articleId, req)` → `saved` \| `draftChanged`(409, `currentDraftVersion`) \| `termIdTaken`(409, `termId`·`ownerArticleId`) \| `invalid`(400, `details.fields`) \| `unauthenticated` \| `failed`; `publishArticle(articleId, expectedDraftVersion)` → `published(PublishResult)` \| `checkFailed(checkVersion, failures)`(422) \| `draftChanged` \| `unauthenticated` \| `failed`; `unpublishArticle(articleId)`. 서버 `message`·`details`를 그대로 보관한다. CSRF 헤더는 `apiClient`가 쿠키에서 붙인다. 401은 `api/client.ts`의 인증 필요 리스너(014)가 함께 받는다
- [X] T061 [P] [US5] (FE) `frontend/src/components/learning/admin/adminDraftStorage.ts` — `sessionStorage` 키 `thorp.learning.adminDraft.{articleId}`([research](research.md) R12): `saveUnsentDraft`·`loadUnsentDraft`·`clearUnsentDraft`, 페이로드에 `version: 1`과 `savedAt`. 읽기·쓰기 모두 `try/catch`로 실패해도 되는 것으로 다룬다(002 `diagnosis/selectionStorage.ts`와 같은 태도)
- [X] T062 [P] [US5] (FE) `frontend/src/components/learning/admin/BlockEditor.tsx`·`BlockEditor.module.css` — 블록 목록 편집: 네 종류 추가, `text`는 `textarea`, `term` 블록의 `termId`는 이 글의 용어 목록에서 고르는 `select`(용어 이름을 함께 보인다), 위·아래 이동, 삭제, 종류별 길이 안내([data-model](data-model.md) §4). 판정은 서버가 한다 — 화면은 안내만 하고 저장을 막지 않는다
- [X] T063 [P] [US5] (FE) `frontend/src/components/learning/admin/ExampleEditor.tsx`·`ExampleEditor.module.css` — `frontend/src/api/sectors.ts`의 `fetchSectors`로 받은 열린 대응표에서 섹터를 고르고(`code`), 비중은 소수 한 자리 %로 입력받아 `weightTenths` 정수로 보관한다(002 계약 "값 표현 규칙"). 행 추가·삭제, 합계 표시, "합계가 100%가 아니면 게시에서 거절됩니다" 안내. 대응표가 `mapNotReady`·`failed`면 새로 고를 수 없다는 한 문장을 보이고 기존 행은 그대로 둔다. 예시 비우기(`null`)를 허용한다
- [X] T064 [P] [US5] (FE) `frontend/src/components/learning/admin/TermListEditor.tsx`·`TermListEditor.module.css` — 용어 행: `termId`(새 행만 입력 가능하고 저장된 행은 읽기 전용 — 만든 뒤 바꾸지 않는다, [research](research.md) R4), `label`(≤50), `sentence`(≤300 안내), 삭제. 저장 요청은 목록을 통째로 보내므로 삭제한 행은 다음 게시 때 공개에서도 빠진다는 안내 한 줄
- [X] T065 [P] [US5] (FE) `frontend/src/components/learning/admin/PublishCheckResultList.tsx` — `role="alert"` 목록. 항목마다 `check`, 위치(`title`/`question`/`example`은 그 이름, `block`은 "본문 {index + 1}번째 블록", `term`은 "용어 {termId}"), `matched`(있을 때), `message`를 **그대로**. 문장을 새로 짓지 않는다(계약 §6)

### Frontend — 관리 화면 (B05)

- [X] T066 [US5] (FE) `frontend/src/pages/AdminLearningListPage.tsx`·`AdminLearningListPage.module.css` — `/admin/learning`. 마운트 때 `checkAdminAccess`: `admin`일 때만 화면을 그린다; `denied`면 목록 대신 "볼 수 없는 화면입니다" 한 문장과 `/learn` 링크(편집 수단을 보이지 않는다 — US5 시나리오 1); `unauthenticated`는 014 재로그인 안내로 넘긴다; `failed`는 `LearningLoadFailure`. 목록: 순서·제목·상태·`draftVersion`·'게시하지 않은 변경 있음'(`hasUnpublishedChanges`)·'갱신 필요'(`needsUpdate`, `null`이면 "대응표를 확인할 수 없음")·마지막 기록(`action`·`revisionNo`·`actorUserId`·`occurredAt`)·편집 링크. 글 만들기 폼(`articleId`·`title`·`readingOrder`) → 201이면 편집 화면으로, `idTaken`이면 그 자리에 안내. `AppHeader`에 관리 메뉴를 더하지 않는다(운영자는 주소로 들어온다) (T060 의존)
- [X] T067 [US5] (FE) `frontend/src/pages/AdminLearningEditPage.tsx`·`AdminLearningEditPage.module.css` — `/admin/learning/:articleId`. 접근 확인은 T066과 같다. 초안을 불러온 뒤 `loadUnsentDraft`에 값이 있으면 복원할지 먼저 묻는다(R12, quickstart 3-7); 편집기(제목·질문·읽는 순서·출처·`BlockEditor`·`ExampleEditor`·`TermListEditor`)의 변경을 `saveUnsentDraft`로 보관; **저장**: `saveDraft(expectedDraftVersion = 마지막으로 읽은 값)` → `saved`면 `draftVersion` 갱신·`clearUnsentDraft`; `draftChanged`면 **입력을 화면에 남긴 채** "다른 곳에서 초안이 바뀌었습니다. 새로 불러온 뒤 다시 고쳐 주세요"와 '새로 불러오기' 버튼(자동 병합·자동 재전송 없음, quickstart 3-6); `termIdTaken`·`invalid`는 `details`를 그 자리에; `unauthenticated`면 보관본을 지우지 않고 재로그인 안내(014 FR-012); **게시**: `publishArticle` → `checkFailed`면 `PublishCheckResultList`(quickstart 3-2), `published`면 `revisionNo`·`checkVersion`·`sectorMapVersion`·`publishedAt` 표시(3-3); **게시 취소**: `PUBLISHED`일 때만 버튼, 확인 뒤 `unpublishArticle`(3-8). `publishedRevisionNo`를 보인다. 관리 화면은 FR-019 대상이 아니지만 폼 요소는 `label`을 가진다 (T060~T065 의존)
- [X] T068 [US5] (FE) `frontend/src/App.tsx` — `/admin/learning`·`/admin/learning/:articleId`를 014 `ProtectedRoute`로 감싼 lazy 경로로 더한다(`/records`와 같은 방식). 비로그인은 `ProtectedRoute`가 로그인으로 보내고 돌아온다 (T066·T067 의존)

### Frontend 테스트 (B05)

- [X] T069 [P] [US5] (FE 테스트) `frontend/src/api/learningAdmin.test.ts` — 401 → `unauthenticated`, 404 → `denied`(access) / `failed`(그 밖), 409 `LEARNING_DRAFT_CHANGED` → `draftChanged`이고 `currentDraftVersion`이 `details` 값, 409 `LEARNING_TERM_ID_TAKEN` → `termIdTaken`, 422 → `checkFailed`이고 `failures`가 서버 배열 그대로(`message` 변형 없음); 상태 변경 요청에 `X-XSRF-TOKEN`이 실린다(쿠키를 심고 확인). `test.include`에 `'src/api/learningAdmin.test.ts'` 추가 (T060 의존)
- [X] T070 [P] [US5] (FE 테스트) `frontend/src/components/learning/admin/PublishCheckResultList.test.tsx` — 픽스처의 항목 셋이 모두 보이고 각 `message`가 그대로이며 `block` 위치가 "본문 3번째 블록", `term` 위치가 "용어 max-drawdown"으로 읽힌다; 목록이 `alert`다 (T065 의존)
- [X] T071 [US5] (FE 테스트) `frontend/src/pages/AdminLearningEditPage.test.tsx` — `draftChanged` 응답 뒤 입력값이 화면에 남고 안내와 새로 불러오기 버튼이 있으며 두 번째 저장 요청이 없다(quickstart 3-6); `checkFailed` 응답의 항목 셋이 보인다(3-2); `unauthenticated` 응답 뒤 `sessionStorage`의 보관본이 남아 있고 재로그인 안내가 있다(3-7); 보관본이 있으면 복원 여부를 묻고 수락하면 폼에 채워진다; 저장 성공 뒤 보관본이 지워진다; `DRAFT` 글에는 게시 취소 버튼이 없다 (T067·T068 의존)
- [X] T072 [US5] (FE 테스트) `frontend/src/pages/AdminLearningListPage.test.tsx` — `denied`면 목록·편집 링크·만들기 폼이 없고 `/learn` 링크가 있다(US5 시나리오 1); `admin`이면 아홉 행과 '갱신 필요'·'게시하지 않은 변경 있음' 표시가 픽스처대로; `needsUpdate: null`이면 "대응표를 확인할 수 없음"; 만들기 409에 안내 (T066·T068 의존)

**Checkpoint**: 검사에 걸린 게시는 아무것도 바꾸지 않고 항목별로 보이며, 통과한 게시는 목록·글·도움말에 함께 나타나고, 게시 취소는 셋에서 함께 지운다

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 문서와 코드를 계약에 맞추고, 잔재를 확인하고, 전체 검증을 돈다

### 문서

- [X] T073 [P] (DOCS) `backend/CLAUDE.md` "규칙"에 세 줄 추가 — "배우기 판(`learning_article_revision`)·게시 기록(`learning_publication_event`)은 추가만 한다. 저장소에 수정·삭제 메서드를 두지 않는다"; "게시·게시 취소는 글 상태 변경과 게시 용어 색인(`learning_published_term`) 재작성을 같은 트랜잭션에서 한다 — 한순간이라도 어긋나면 도움말 문장과 글 본문이 갈라진다(013 SC-003)"; "관리 권한 판정은 `LearningAdminPolicy` 한 곳이다. 지금은 `thorp.learning.admin-user-ids` 허용 목록이고 014가 역할을 주면 `ConfiguredLearningAdminPolicy`만 바꾼다". `frontend/CLAUDE.md` "규칙"에 두 줄 — "용어 도움말은 `components/learning/TermHelp.tsx`(`termId`·`label`·`returnTo`) 하나를 쓴다. 글 본문·용어 문장을 화면 코드에 상수로 두지 않는다(013 FR-015)"; "`sessionStorage` 키 `thorp.learning.adminDraft.{articleId}`(관리 화면의 저장하지 않은 편집 내용, 저장 성공 시 지운다). 일반 사용자 배우기 화면은 저장소에 쓰지 않는다"
- [X] T074 [P] (DOCS) spec 문서 동기화 — `specs/013-learning-pages/data-model.md` §3-6 snapshot 예시에 `draftVersion`을 더하고 `hasUnpublishedChanges` 판정 근거임을 적는다(T009·T052); `plan.md` "Source Code" 트리의 파일 이름을 구현과 맞춘다(`presentation/support/LearningAdminAccess`, `LearningQueryService`·`LearningAdminService`, `admin/adminDraftStorage.ts` 등); `research.md` R5·R11에 "착수 전에 알아둘 것" ①~③의 현재 상태를 반영한다; `specs/README.md` 013 행은 바뀐 것이 없으면 그대로 둔다
- [ ] T075 (DOCS) [quickstart.md](quickstart.md) 0~5 수동 확인을 돌리고 결과를 MR 본문 "테스트" 절에 항목별로 적는다. 002 T020이 아직 없으면 4-3은 T032로 대신하고 그 사실을 적는다; 003 화면이 공용 `TermHelp`를 아직 쓰지 않으면 5-1~5-4는 T042로 대신한다(quickstart §5 머리말); 로컬 확인은 §0의 Flyway 켜기 명령으로 한다. SC-002는 검사 통과로 증명되지 않는다는 것을 그대로 적는다(quickstart §6). SC-005 사용성 관찰은 이 태스크가 수행하지 않으며, constitution "완료의 정의"에 따라 구현 완료와 구분해 "사용성 확인: 미수행"(또는 수행 결과)으로 따로 적는다

> B06 실행 기록: [validation.md](validation.md). T075는 HTTP·자동 검증과 일부 실제 화면 확인까지 수행했다. 남은 화면·스크린 리더 확인은 완료로 세지 않으며 T075를 열린 상태로 둔다.

### 코드 정리

- [X] T076 [P] (FE) 잔재·하드코딩 확인 — `grep -n "NotReadyPage" frontend/src/App.tsx`에서 `/learn` 경로가 0건; `grep -rn "dangerouslySetInnerHTML" frontend/src/components/learning frontend/src/pages/Learning*`가 0건(R3); `grep -rn "components/strategy/TermHelp" frontend/src`는 003 T057a의 몫이므로 결과만 MR에 적는다; `frontend/src/components/learning`·`frontend/src/pages/Learning*Page.tsx`에 [data-model](data-model.md) §8의 `termId`·제목 문자열이 상수로 없다(FR-015. 테스트·픽스처는 제외)

### 전체 검증

- [X] T077 (BE 테스트) `cd backend && ./gradlew spotlessApply build` — ArchUnit(규칙 9 포함)·마이그레이션·계약·통합 테스트 전부. `skipped`가 Docker 부재 때문인지 확인하고 건너뛴 테스트를 통과로 세지 않는다([backend/CLAUDE.md](../../backend/CLAUDE.md), quickstart 1)
- [X] T078 (FE 테스트) `cd frontend && npm run lint && npm run build && npm test` — Vitest include에 새 `.test.ts` 셋(`learning`·`learningGlossary`·`learningAdmin`)이 들어갔는지 결과 목록에서 확인한다. 빠졌으면 `src/diagnosis/testCollection.guard.test.tsx`가 먼저 실패한다

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
| B01 · [S15P21E205-294](https://ssafy.atlassian.net/browse/S15P21E205-294) | `[BE] 배우기 글과 용어 설명을 보관하고 공개 조회한다` | T001~T003, T007~T019, T033~T035 | 19 | `be` | 없음 |
| B02 · [S15P21E205-295](https://ssafy.atlassian.net/browse/S15P21E205-295) | `[BE] 관리 계정이 초안을 저장하고 게시·게시 취소한다` | T045~T059 | 15 | `be` | B01 |
| B03 · [S15P21E205-321](https://ssafy.atlassian.net/browse/S15P21E205-321) | `[FE] 배우기 목록과 글을 보이고 예시로 진단을 시작한다` | T004, T006, T020~T032, T043, T044 | 17 | `fe` | B01의 계약(T003) |
| B04 · [S15P21E205-328](https://ssafy.atlassian.net/browse/S15P21E205-328) | `[FE] 진단 화면이 함께 쓰는 용어 도움말을 만든다` | T036~T042 | 7 | `fe` | B03 |
| B05 · [S15P21E205-299](https://ssafy.atlassian.net/browse/S15P21E205-299) | `[FE] 관리 화면에서 글을 고치고 게시한다` | T005, T060~T072 | 14 | `fe` | B02, B03 |
| B06 · [S15P21E205-302](https://ssafy.atlassian.net/browse/S15P21E205-302) | `[DOCS] spec 013 문서 동기화와 최종 검증` | T073~T078 | 6 | `fe` | 전부 |

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

- 테스트를 먼저 쓰고 실패시킨 뒤 구현한다. 테스트 태스크에 적힌 "(T### 의존)"은 대상 클래스·경로·계약이 정해져 있어야 한다는 뜻이며, 구현을 끝낸 뒤에 테스트를 쓰라는 뜻이 아니다
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
4. 데모 — 통합 테스트로 심은 판 하나로 목록 → 글 → '이 예시로 전략 진단하기'가 002 계약 모양으로 넘어가는 데까지. **실제 원고 게시는 B02·B05 뒤다**

**MVP = 묶음 둘(B01 + B03) = 티켓·브랜치·MR 둘.** B01의 19개와 B03의 17개, 합계 36개다(T001~T035에서 T005를 빼고 T043·T044를 더한 것과 같다).

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

---

## Phase 9: Convergence

- [X] T079 (BE) `backend/src/main/java/com/ssafy/thorp/learning/infrastructure/persistence/LearningContentJson.java`가 판·초안 JSON에서 필수 키가 빠졌을 때 조용히 채우지 않고 실패하게 한다 — `SnapshotJson.readingOrder`·`draftVersion`, `SectorWeightJson.weightTenths`는 원시 `int`라 키가 없으면 0이 되고(메시지 ObjectMapper는 `FAIL_ON_MISSING_CREATOR_PROPERTIES`를 켜지 않는다), `blocksFromJson`·`sourcesFromJson`·`snapshotFromJson`은 null 목록을 `List.of()`로 바꾼다. 선택 값(`example`, 블록의 `text`/`termId`, 출처 `url`)은 "키 없음 = null" 그대로 두고, 필수 값은 래퍼 타입으로 받아 null이면 `IllegalStateException`을 던진다. `LearningRepositoryTest`에 `readingOrder`·`weightTenths`·`bodyBlocks` 키를 뺀 판 JSON을 SQL로 넣고 읽으면 실패하는 경우를 더한다 per Constitution VI, tasks "착수 전에 알아둘 것" ⑦ (partial)

## Phase 10: Convergence

- [X] T080 (BE) `backend/src/main/java/com/ssafy/thorp/learning/infrastructure/persistence/PublishedTermRepositoryImpl.java`의 `findPublished`가 판 JSON에서 꺼낸 글 제목(`articleTitle`, `r.snapshot ->> 'title'`)을 `LearningContentJson.required`로 확인해, 판에 `title` 키가 없으면 `GET /learning/terms/{termId}`가 200에 `articleTitle: null`을 내보내지 않고 실패하게 한다. 같은 판을 읽는 목록 투영·단건 조회(T079)와 규칙을 맞춘다. `LearningRepositoryTest`에 `title` 키를 뺀 판과 그 판을 가리키는 게시 용어 색인 행을 SQL로 넣고 `findPublished`가 실패하는 경우를 더한다 per Constitution VI, T079 (partial)

## Phase 11: Convergence

- [X] T081 (FE) `frontend/src/pages/LearningArticlePage.tsx`의 `failed` 상태(글을 불러오지 못함)에서도 `/diagnosis/sectors`로 가는 진단 진입 `Link`를 `LearningLoadFailure`·주제 목록 연결과 함께 남긴다. `LearningListPage`의 실패 상태와 같은 규칙이다. `LearningArticlePage.test.tsx`의 실패 테스트에 그 링크의 `href`가 `/diagnosis/sectors`인지 확인을 더한다 per US1/AC7, Edge Cases (partial)
- [X] T082 (FE) `frontend/src/pages/LearningListPage.tsx`가 목록을 불러오는 동안(`loading`)에도 진단 진입 `Link`를 보이게 한다. 지금은 응답이 올 때까지(최대 `apiClient` timeout 10초) 숨긴다. `LearningListPage.test.tsx`에 응답을 보류한 채 링크가 보이는지 확인을 더한다 per FR-015, Edge Cases (partial)


## Phase 12: Convergence

- [X] T083 (INFRA) `infra/docker-compose.prod.yml`의 backend `environment`에서 `THORP_LEARNING_ADMIN_USER_IDS: ${THORP_LEARNING_ADMIN_USER_IDS}`를 `${THORP_LEARNING_ADMIN_USER_IDS?운영 .env에 배우기 관리 계정 목록을 넣으세요(비우면 관리자 없음)}`로 바꿔, 운영 `.env`에 변수가 **없으면** compose가 배포를 멈추고 **빈 값으로 명시하면** "관리자 없음"으로 뜨게 한다. 지금은 compose가 없는 변수를 경고만 남기고 빈 문자열로 넘겨 `application-prod.yml`의 기본값 없는 참조가 풀려 버리므로, T045가 말한 "빠지면 prod 기동이 실패"가 성립하지 않고 설정 누락이 "관리자 없음"과 구분되지 않는다. `infra/.env.prod.example`의 주석에 "변수를 지우면 배포가 멈춘다"를 더하고, 운영 `.env`에 값을 넣어야 한다는 사실을 MR 리뷰 포인트에 적는다 per T045, research R5 (partial)
- [X] T084 (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/service/LearningAdminService.java`의 `create`·`saveDraft`가 동시에 들어온 같은 새 `articleId`·`termId` 때문에 UNIQUE 제약(`uq_learning_article_key`·`uq_learning_term_key`)에 걸리면 500 `INTERNAL_ERROR`가 아니라 계약의 409(`LEARNING_ARTICLE_ID_TAKEN`·`LEARNING_TERM_ID_TAKEN`)로 답하게 한다. 트랜잭션 안에서 예외를 삼키지 말고, 제약 이름으로 가른 `DataIntegrityViolationException`을 트랜잭션 경계 밖(컨트롤러 또는 서비스 호출부)에서 409로 바꾼다. `LearningAdminApiIT`에 같은 식별자로 두 요청을 동시에 보내 하나는 201/200, 하나는 409인지 확인하는 테스트를 더한다 per learning-admin-api §3·§5 (partial)
- [X] T085 (DOCS) `specs/013-learning-pages/data-model.md` §2와 `contracts/learning-admin-api.md` §5 `409 LEARNING_TERM_ID_TAKEN` 행에, `termId`는 초안 용어뿐 아니라 **다른 글이 게시 중인 용어**와도 겹칠 수 없다는 규칙(초안에서 뺀 용어도 그 글을 다시 게시하거나 게시 취소하기 전까지는 다른 글이 가져가지 못한다)과 그 이유(게시 용어 색인의 `term_key`가 PK라 다른 글의 도움말을 덮어쓴다, SC-003)를 적는다. 구현(`LearningAdminService.saveDraft`·`publish`)은 이미 이 규칙을 따른다 per FR-008, SC-003 (partial)

## Phase 13: Convergence

- [X] T086 (DOCS) `specs/013-learning-pages/data-model.md` §6과 `research.md` R7을 구현된 게시 전 검사 규칙에 맞춘다. 구현(`backend/src/main/java/com/ssafy/thorp/learning/domain/model/PublishCheck.java`, `backend/src/main/resources/learning/publish-checks-v1.yml`)에는 있는데 문서에는 없는 규칙은 다섯 가지다. (1) 검사 전에 호환 문자를 표준 문자로 바꾼다(NFKC). 줄바꿈 없는 공백·전각 숫자·`％`도 잡기 위해서다. (2) 금지 표현과 결과성 숫자는 용어 **이름**도 본다. 문서에는 "용어 문장"만 적혀 있다. (3) `숫자+배` 패턴은 숫자와 `배` 사이 공백을 허용하지 않고 `배분·배정·배치·배열·배당`을 제외한다. (4) `숫자+원` 패턴은 `원칙`을 제외한다. (5) 공백만 담은 `paragraph`는 `REQUIRED_FIELDS`에서 문단으로 세지 않는다. 금지 표현마다 무리별 `message`가 붙는다는 점과, 문단이 없을 때 `block` 위치에 `index`가 없다는 점도 §6에 적는다. §6-2의 "그 밖의 예외를 더하려면 파일 버전을 올린다"와 (3)·(4)의 관계도 적는다: 이 규칙들은 `learning-publish-checks-v1`이 어느 판에도 기록되기 전(운영 미배포)에 정했으므로 버전을 올리지 않았다 per research R7, data-model §6 (partial)

## Phase 14: Convergence

- [X] T087 (BE 테스트) `backend/build.gradle`의 `tasks.named('test')`에 `inputs.dir(file('../docs/specs/learning-api/examples'))`(`withPropertyName('learningApiFixtures')`, `PathSensitivity.RELATIVE`)을 더한다. `LearningPublicApiIT`·`LearningAdminApiIT`가 이 폴더의 예시 파일을 읽어 대조하는데 입력으로 선언되지 않아, 예시 파일만 바꾸면 `:test`가 UP-TO-DATE로 건너뛰어 계약과 구현이 어긋나도 로컬 빌드가 통과한다. 같은 파일의 주석이 `backtest-api/examples`에 대해 이미 적어 둔 실패 방식이다 per plan "Testing"(계약 픽스처 대조), T059 (partial)
- [X] T088 (DOCS) `docs/specs/learning-api/README.md`의 "예시 파일이 계약의 기준이다" 문단을 실제 대조 방식에 맞춘다. 지금은 두 IT가 모든 예시를 "JSON 동치로 대조"한다고 적혀 있다. 그러나 `LearningAdminApiIT`는 `publish-check-failed.json`만 동치로 비교하고, `admin-articles-list.json`·`admin-article.json`·`publish-success.json`·`unpublish.json`은 **키 구성**(`assertSameShape`)만 비교한다. 시각·대응표 버전 같은 값이 실행마다 달라지기 때문이다. 관리 예시 표의 파일마다 대조 방식(동치/키 구성)을 적는다 per T059, contracts/learning-admin-api.md (partial)

## Phase 15: Convergence

- [X] T089 (FE) CRITICAL `frontend/src/components/learning/admin/ExampleEditor.tsx`의 비중 칸이 입력한 글자를 몰래 바꾸지 않게 한다. 지금은 `value={formatWeightTenths(sector.weightTenths)}`라 글자를 칠 때마다 다시 그려져 "50"을 치면 "5.0"이 되고(커서가 끝으로 가서 두 번째 글자가 "5.00"으로 해석된다), 해석되지 않은 입력은 알리지 않고 버린다. 002 `SectorSelectionPage`처럼 행마다 입력 문자열을 따로 보관하고 `parseWeightInput`이 `parsed`일 때만 `weightTenths`를 올리며, `rejected`면 그 칸 옆에 거절 이유를 한 문장으로 보인다. 칸을 벗어나면(blur) 표시를 `formatWeightTenths`로 맞춘다. `frontend/src/components/learning/admin/ExampleEditor.test.tsx`를 만들어 "50"·"12.5"를 쳐서 `onChange`가 500·125를 받는지, 두 자리 소수가 거절 안내를 보이는지 확인한다 per Constitution VI, T063 (contradicts)
- [X] T090 (FE) `frontend/src/pages/AdminLearningEditPage.tsx`에서 보관본을 복원하면 저장·게시가 **보관본의 `draftVersion`**을 싣게 한다. 지금은 복원해도 서버의 현재 번호로 저장해, 다른 탭·다른 운영자가 그 사이 저장한 초안을 409 없이 덮는다(R12가 막으려는 말없는 덮어쓰기). 보관본 번호가 서버 번호와 다르면 복원 안내에 "보관한 뒤 다른 곳에서 초안이 바뀌었습니다"를 함께 보인다. 복원 여부에 답하기 전에는 편집기와 저장·게시 버튼을 막아 보관본이 새 입력으로 덮이지 않게 한다. `frontend/src/components/learning/admin/adminDraftStorage.ts`의 `UnsentDraft.draftVersion` 주석("이 값으로 저장을 다시 보내지 않는다")을 새 동작에 맞게 고친다. `AdminLearningEditPage.test.tsx`에 "보관본 v5·서버 v6에서 복원 후 저장하면 `expectedDraftVersion`이 5"와 "답하기 전에는 제목 칸이 비활성"을 더한다 per plan: R12, quickstart 3-6·3-7 (partial)
- [X] T091 (FE) `frontend/src/pages/AdminLearningEditPage.tsx`가 저장하지 않은 편집이 있을 때 게시를 막는다. 지금은 화면에서 고친 내용이 있어도 `publishArticle`이 서버에 저장된 초안을 게시해, 고친 뒤 게시를 누르면 옛 초안으로 같은 422를 다시 받는다. 마지막으로 불러오거나 저장한 초안과 지금 초안이 다르면 '게시'를 비활성으로 두고 "저장한 뒤 게시할 수 있습니다"를 보인다. 자동으로 저장한 뒤 게시하지 않는다(운영자가 저장 결과를 보고 게시를 정한다). `AdminLearningEditPage.test.tsx`에 "제목을 고치면 게시 버튼이 비활성이고 저장 성공 뒤 다시 활성"을 더한다 per contract §6 (partial)
- [X] T092 (FE 테스트) `frontend/src/api/learningAdmin.test.ts`의 XSRF 테스트가 실제로 헤더를 확인하게 한다. 지금은 `apiClient.put`을 대역으로 바꿔 axios가 쿠키에서 헤더를 붙이는 단계를 거치지 않는다. `apiClient.defaults.adapter`를 요청 설정을 받아 두는 대역으로 바꾸고(끝나면 되돌린다), `saveDraft`·`publishArticle`·`unpublishArticle`의 요청에 `X-XSRF-TOKEN: synthetic-csrf-token`이 실리는지 확인한다 per T069 (partial)
- [X] T093 (FE) `frontend/src/api/learningAdmin.ts`의 `fetchAdminArticles`가 404를 T069대로 `failed`로 돌려주게 하고(권한 판정은 `checkAdminAccess` 한 곳이다) 결과 타입에서 `denied`를 뺀다. `frontend/src/pages/AdminLearningListPage.tsx`에서 목록의 `unauthenticated`를 "목록을 볼 권한이 없습니다"로 보이지 않고 `setAccess({ status: 'unauthenticated' })`로 재로그인 상태에 보낸다. `learningAdmin.test.ts`의 목록 404 기대값을 `failed`로 고친다 per T069, T066 (contradicts)
- [X] T094 (FE) `frontend/src/pages/AdminLearningListPage.tsx`에서 글 만들기가 201이면 `useNavigate`로 `/admin/learning/{articleId}`에 바로 보낸다. 지금은 "글을 만들었습니다" 중간 화면과 링크를 보인다(`createdArticleId` 상태를 지운다). `AdminLearningListPage.test.tsx`에 "만들기 201 뒤 편집 화면 경로로 간다"를 더한다 per T066 (partial)
- [X] T095 (FE) `frontend/src/components/learning/admin/TermListEditor.tsx`의 행 `key`를 편집 중에 바뀌지 않는 값으로 바꾼다. 지금은 `key={term.termId || index}`라 새 행의 식별자를 고칠 때마다 행이 다시 만들어져 초점을 잃고, 같은 식별자 두 행이 키를 다툰다. 행을 추가할 때 붙인 화면 전용 키를 쓰거나 순번을 쓴다(이 키는 저장 요청에 싣지 않는다) per T064 (partial)

## Phase 16: Convergence

- [X] T096 (FE) `frontend/src/components/learning/admin/ExampleEditor.tsx`에서 대응표가 `emptyCatalog`(발행됐지만 섹터가 0개)일 때도 새 섹터를 고를 수 없다는 한 문장을 보인다. 지금은 `canPickSector`가 거짓이라 '섹터 추가'와 기존 행의 `select`가 막히는데, 안내 문장은 `mapNotReady`·`failed`일 때만 나와 이유 없이 막힌다. `emptyCatalog`는 대응표가 없는 것과 다른 사실이므로 "열린 대응표에 섹터가 없어 새로 고를 수 없습니다. 이미 넣은 행은 그대로 둡니다"처럼 구분해 적는다. `ExampleEditor.test.tsx`에 `emptyCatalog` 응답에서 이 문장이 보이고 '섹터 추가'가 막히는지 더한다 per T063 (partial)

## Phase 17: Convergence

- [ ] T097 (DOCS) [validation.md](validation.md)에 남은 quickstart 0~5 수동 확인을 실제 화면에서 마친다 — 빈 목록·게시 취소 글 화면, 일반 계정의 관리 화면 차단, 관리 목록의 미게시 변경·갱신 필요 표시, 게시 취소 뒤 공개 목록·글·기록, 공개 화면의 브라우저 저장소 미사용, 실패 화면의 자동 재요청 없음, 대응표에서 사라진 예시의 대체 안내, 키보드 Tab 경로와 NVDA 또는 VoiceOver 읽기, 실제 003 도움말의 실패·재시도와 제목 변경 뒤 연결을 확인한다. 각 결과를 `validation.md`와 MR 본문 "테스트" 절에 성공·실패·미수행으로 구분해 기록하고 모두 확인된 뒤 T075와 이 태스크를 완료한다. SC-002 패턴 검사의 한계와 SC-005 사용성 확인 미수행 표시는 유지한다 per T075, FR-019, SC-008 (partial)

---

# 2판 (2026-10-05)

> 이 절의 tasks 문서 작업은 branch `docs(fe)/spec-013-tasks`다. 2판 plan이 dev에 병합된 판(`09ef6d25`)을 기준으로 썼다. 2026-09-29부터 Jira 티켓을 쓰지 않으므로 구현 묶음의 브랜치는 `{type}({position})/{description}` 형식이며 이름은 [plan.md](plan.md) "작업 묶음"의 표를 따른다.
> 1판 task(T001~T097)는 위에 그대로 둔다. 1판의 남은 수동 확인 T075·T097은 1판 묶음의 마무리이며 2판 묶음과 섞지 않는다(plan "묶음에 넣지 않은 것").

**Input**: 2판 [plan.md](plan.md)·[research.md](research.md) R14~R40·[data-model.md](data-model.md)·[contracts/](contracts/README.md)·[quickstart.md](quickstart.md) §6~§13, spec 2026-10-05판(FR-001~FR-031, SC-001~SC-017, US1~US6)

**Tests**: 포함한다. 1판과 같은 이유다(constitution "완료의 정의" 조건 2, plan "Testing"). 각 테스트 태스크는 [quickstart.md](quickstart.md)의 확인 항목 번호를 적는다.

**Organization**: 1판과 같이 사용자 스토리별로 묶고 단계 안에서 파트별로 나눈다. 태스크 끝의 `‹B##›`는 그 태스크가 들어가는 작업 묶음(= 브랜치 하나 = MR 하나)이다. 스토리와 묶음은 서로 다른 축이다. 한 스토리의 태스크가 백엔드·프런트엔드 묶음에 나뉘어 들어간다.

## 2판 Format: `[ID] [P?] [Story] (파트) Description ‹묶음›`

- 파트 태그와 `[P]`의 뜻은 1판 Format 절과 같다. 스토리는 [US1]~[US6]이다(US6 '나의 학습'은 2판에서 생겼다).
- `‹B07›`~`‹B16›`은 [plan.md](plan.md) "작업 묶음"의 묶음이다. 같은 묶음의 태스크만 한 브랜치·MR에 담는다.

## 2판 착수 전에 알아둘 것

013 혼자 닫을 수 없는 항목이다. 각 담당이 판단하고 별도 MR로 한다([plan](plan.md) "교차 의존").

- ⑧ **017 용어 도우미가 013을 읽는다** — spec 017 tasks T030(미완)이 `PublishedTermRepository.findAllPublished()`와 `LearningQueryService.listPublishedTerms()`를 013 코드에 더할 예정이고, 017의 근거 원천 `EvidenceSourceType.ARTICLE`은 013 게시 글의 판 블록을 읽는다. 2판은 CURRENT 판의 블록을 글자 조각 모양으로 바꾸므로, B07이 공개 글 결과 블록에 평문을 내는 메서드를 둔다(T122). 017 T030이 먼저 병합되면 B07이 그 메서드에 `aliases`를 더할지 017 담당과 정하고, B07이 먼저면 017 T030이 2판 모양을 그대로 받는다. 어느 쪽이 먼저든 017 테스트가 깨지지 않는지 B07 MR 리뷰 포인트에 적는다. 017의 `readArticle` 도구는 게시 글 열거에 `LearningQueryService.listPublished()`를 쓰므로(017 plan '근거 팩과 도구') B16(T197)은 이 조회를 지우지 않는다
- ⑨ **003 설정 항목 네 개** — 003 등록표 축의 `termId`를 예약 식별자(`lookback-period`·`skip-recent-period`·`selection-method`·`weight-cap`)로 바꾸는 일은 003의 별도 MR이다. 013은 B10에서 `TermHelp`의 `fallbackMeaning`·`articleLinkLabel`만 제공한다([term-help-ui](contracts/term-help-ui.md) §5). 등록표 `description` 한 필드에 섞인 뜻 문장과 나머지 안내(예: 모멘텀 `maxWeight`의 상한 처리 안내)를 나눠 `fallbackMeaning`과 `note`로 따로 넘기는 방법도 003이 같은 MR에서 정한다. 이름 차이('최근 구간 제외'와 '최근 구간 제외 기간', '비중 상한'과 '섹터당 최대 비중')는 [checklists/review.md](checklists/review.md) CHK017로 검토 중이다
- ⑩ **014 갱신** — 배우기 로그인 복귀와 '내 기록과 배우기의 나의 학습' 공통 가정은 014·README의 별도 MR이다. 013은 014의 `useLoginReturn`·`normalizeReturnTarget`을 그대로 쓰므로 그 갱신을 기다리지 않는다(research R34)
- ⑪ **constitution 초안** — 예시 선택(6.2.0)과 탭 단위 화면 상태(6.1.0)는 미승인 초안을 앞서 적용한다. 6.2.0이 반려되면 `exampleRequired: true`인 v3 규칙 파일을 더하는 태스크를 그때 추가한다(research R19)
- ⑫ **요구사항 점검표** — [checklists/review.md](checklists/review.md)(38개)가 검토 중이다. 검토 결과 spec·plan이 바뀌면 이 태스크를 그 판에 맞춰 고친다. 지금 태스크는 spec 2026-10-05판과 2판 plan 그대로다
- ⑬ **첫 단계 공개는 코드 태스크가 아니다** — B07~B14가 배포되고 plan "첫 단계 공개 조건"을 모두 확인한 뒤 운영자가 관리 화면에서 한다. 원고 27편은 단계별 원고 MR(`docs(fe)/learning-content-stage-{n}`)로 쓴다

## 2판 Path Conventions

- 백엔드 2판 도메인 타입: `backend/src/main/java/com/ssafy/thorp/learning/domain/model/`·`domain/vo/`([data-model](data-model.md) §14)
- 2판 마이그레이션 셋: `backend/src/main/resources/db/migration/V{작성 시각}__learning_stages_and_v2_content.sql`(B07), `…__learning_stage_release.sql`(B08), `…__learning_records.sql`(B09). 버전은 작성 시각 타임스탬프이며, 브랜치가 오래 열려 있었으면 병합 직전에 `infra/scripts/check-migration-version.sh` 기준으로 다시 확인한다
- 규칙 파일: `backend/src/main/resources/learning/publish-checks-v2.yml`(v1 파일은 그대로)
- 계약 픽스처: `docs/specs/learning-api/examples/`에 2판 파일을 더하고, 대조 방식은 `docs/specs/learning-api/README.md` 표에 적는다
- 프런트엔드 하위 폴더: `frontend/src/components/learning/article/`·`curriculum/`·`me/`·`admin/`(plan "Source Code")
- 2판의 새 `.test.ts`는 T104가 더하는 `src/components/learning/**/*.test.ts` 한 줄로 수집된다. 그 밖의 위치에 `.test.ts`를 두면 `vite.config.ts`에 정확 경로를 더한다

## Existing Seams

2판이 대체·확장·제거해야 하는, 이미 저장소에 있는 코드다(2026-10-05 `09ef6d25` 기준 검색).

| Seam (file:symbol) | Current state | What this feature does | Task |
|---|---|---|---|
| `frontend/src/components/learning/ReturnToDiagnosisLink.tsx:resolveLearningReturn` | 허용 경로 셋(`/diagnosis/sectors`·`/strategy`·`/results/`)과 정확 일치만 받는다. 007·009·010·011과 쿼리 문자열 경로를 거절한다 | `returnTargets.ts` 표와 `ReturnLink.tsx`로 대체한다 | T148 |
| `frontend/src/pages/LearningListPage.test.tsx`·`LearningArticlePage.test.tsx`의 "진도 문구 없음"·"저장소에 쓰지 않음" 단언 | 1판 FR-012(기록 없음)를 단언한다. 2판 FR-020·FR-023과 맞지 않는다 | 비로그인 문구 없음·학습 기록 미저장·허용 키 둘(로그인 연결을 누르면 014 `thorp.auth.returnTarget` 하나 더)로 바꾼다 | T134, T141, T190 |
| `frontend/CLAUDE.md`의 "일반 사용자 배우기 화면은 저장소에 쓰지 않는다" | 1판 규칙 | 2판 저장 키 둘과 '학습 기록은 쓰지 않는다'로 고친다 | T142 |
| `frontend/vite.config.ts` `test.include` | `src/components/learning/**/*.test.ts`가 없다 | 한 줄 더한다 | T104 |
| `frontend/src/api/learning.ts:fetchLearningArticles` → `GET /learning/articles` | 목록 화면의 유일한 소비자 | 화면을 커리큘럼 조회로 옮기고(T135·T140), 경로를 지운다(T197) | T140, T197 |
| `frontend/src/test/learningFixtures.ts`의 `tideArticle`·`harborArticle`(`LearningArticle` 타입) | 1판 응답 모양이다. `tsconfig.app.json`이 `src` 전체를 검사하므로(`npm run build`의 `tsc -b`) 타입에 필수 필드가 생기면 이 파일에서 빌드가 실패한다. 이 파일을 쓰는 테스트는 아홉 개다 | 2판 필드를 채우고 예시 없는 `CURRENT` 글 픽스처를 더한다. `example`이 `null`일 수 있게 되어 깨지는 `ExampleEntry.test.tsx`를 맞춘다 | T128 |
| `frontend/src/components/strategy/DetailSettingsPanel.tsx`의 `TermHelp`(`note`·`on="hover"`) | 003이 공용 도움말을 쓴다 | 새 선택 prop을 더해도 그대로 동작하게 회귀를 지킨다 | T149, T150 |
| `frontend/src/pages/AccountProfilePage.tsx` | 닉네임 수정만 있다 | '학습 기록' 구역(학습 초기화)을 더한다 | T195 |
| `frontend/src/App.tsx` 배우기 라우트 | `/learn`·`/learn/:articleId`만 있다 | `/learn/me`를 더한다 | T194 |
| `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/persistence/LearningMigrationTest.java` `hasSize(9)` | 1판 아홉 글을 단언한다. 거절 테스트 셋은 예외 종류만 보고, 그중 둘(`rejectsPublishedWithoutRevision`·`rejectsInvalidArticleKey`)은 `stage_id`·`stage_position` 없이 넣는다 | 단계·27편 배치·새 열과 나의 학습 네 테이블의 제약을 단언하도록 넓힌다. 거절 테스트는 INSERT에 단계 배치를 채우고 걸린 제약 이름으로 단언한다(B07 뒤 NOT NULL 위반으로 통과하지 않게) | T116, T121, T201 |
| `LearningQueryServiceTest.java:revision`, `LearningRepositoryTest.java`의 판 저장·`revisionsAreAppendOnly` | `ArticleSnapshot`·`ArticleRevision`의 9월 판 생성자로 판을 만든다 | 2판 필드를 더한 생성자에 맞춰 V1 판으로 고친다 | T113 |
| `backend/src/test/java/com/ssafy/thorp/learning/support/LearningSeeds.java:reset`·`publish` | 1판 테이블만 되돌리고 아홉 편 밖의 글을 지운다. 게시 도우미는 `published_revision_id`만 채운다 | 27편을 남기고 2판 열·테이블을 되돌리며 나의 학습 네 테이블을 비우고, 게시 도우미가 공개 배치를 함께 채우게 하고, 단계 공개·CURRENT 게시 도우미를 더한다 | T117, T121, T201 |
| `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/persistence/LearningRepositoryTest.java:insertPublishedRevision` | 판을 SQL로 걸 때 `published_revision_id`만 채운다 | 공개 배치를 함께 채운다([data-model](data-model.md) §3-2 CHECK) | T117 |
| `backend/src/main/java/com/ssafy/thorp/learning/presentation/support/UniqueConstraintViolations.java` | `uq_learning_term_key`를 409로 바꾼다 | 부분 유일 색인 두 이름으로 바꾼다 | T120 |
| `backend/src/main/java/com/ssafy/thorp/common/config/SecurityConfig.java` 인증 경로 | `/learning/me/**`가 없다 | `authenticated()` 한 줄을 더한다 | T180 |
| `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java` spec 013 블록 | 1판 일곱 코드 | 2판 열세 코드를 묶음마다 더한다 | T101, T102, T103 |
| `backend/src/main/resources/learning/publish-checks-v1.yml` 머리 주석 | `data-model §6`을 가리킨다(2판에서 §8-1) | 주석 참조만 고친다. 규칙은 바꾸지 않는다 | T152 |
| `backend/src/main/resources/registry/parameter-registry.yml` 주석·축 `termId` | 주석이 `data-model.md §8`(2판 §13)을, '섹터당 최대 비중'이 `weight`를 가리킨다 | 주석 참조는 013이 고친다. 축의 `termId` 교체는 003의 별도 MR이다(⑨) | T198 |
| `docs/specs/learning-api/README.md`와 `examples/` | 1판 픽스처 여덟 개 | 2판 픽스처와 대조 방식을 더하고, 1판 관리 픽스처 셋에 2판 키를 더하며, `article.json`은 9월 판 필드 대조의 기준으로 둔다. 지우는 경로의 픽스처를 뺀다 | T098, T099, T100, T197 |
| `backend/src/test/java/com/ssafy/thorp/learning/LearningPublicApiIT.java:articleMatchesExample`, `LearningAdminApiIT.java`의 1판 경우 | `article.json`과 완전 동치, 관리 픽스처와 키 집합 정확 비교(`assertSameShape`), 목록 아홉 편·`readingOrder` 1..9, `readingOrder`만 싣는 만들기 요청, `stage_id` 없는 직접 `INSERT` | 9월 판 필드만 비교하고, 2판 키를 더한 픽스처·27편·`stageKey` 만들기로 고친다 | T124, T160 |
| spec 017 T030 예정 `PublishedTermRepository.findAllPublished`·`LearningQueryService.listPublishedTerms`, 017 `EvidenceSourceType.ARTICLE` | 017이 013 게시 용어와 판 블록을 근거로 읽는다(미완) | CURRENT 판 블록의 평문 메서드를 제공하고 순서를 조율한다(⑧) | T122 |

---

## Phase 18: 2판 Setup

**Purpose**: 2판 계약 픽스처, 오류 코드, 테스트 수집 설정

- [X] T098 [P] (DOCS) B07 계약 픽스처 — `docs/specs/learning-api/examples/`에 `article-current.json`([learning-public-api](contracts/learning-public-api.md) §3-2), `article-previous.json`·`article-pre-release.json`(§3-1, `edition`만 다름), `admin-article-v2.json`([learning-admin-api](contracts/learning-admin-api.md) §4 V2), `admin-preview.json`(§8), `publish-check-failed-v2.json`(§6 422 `error` 전체)을 만들고, `docs/specs/learning-api/README.md` 표에 각 파일의 대조 방식(JSON 동치 또는 키 구성)을 더한다. 픽스처는 계약 예시와 같은 모양으로 쓰되 예시가 `...`로 줄인 값을 모두 채운 전체 응답으로 만든다(`admin-preview.json`은 계약에 예시가 없으므로 §8대로 §3 글 응답 모양에 `preview: true`를 더한다). `article-previous.json`·`article-pre-release.json`의 9월 판 필드는 `article.json`의 값을 그대로 쓰고 `readingMinutes`는 그 본문으로 계산한 값을 쓴다. `admin-article-v2.json`은 §4 V2 예시대로 용어의 `transferFrom` 키를 싣고 키 구성으로 대조한다(B07 응답은 이 키를 늘 `null`로 싣는다, T156). 기존 `article.json`(1판 V1 응답)은 값을 바꾸지 않고 9월 판 필드의 기준으로 둔다. README 머리 문단의 대조 방식 설명에 이 파일은 응답 가운데 이 파일에 있는 키만 JSON 동치로 비교한다고 적고(T124), 계약 예시가 줄여 보인 자리를 픽스처가 채운다는 문장을 더한다([contracts/README](contracts/README.md) 공통 규칙). 1판 관리 픽스처 `admin-articles-list.json`·`admin-article.json`·`publish-success.json`에는 [learning-admin-api](contracts/learning-admin-api.md) §2·§4(V1 초안)·§6 예시의 2판 키를 더하고 1판 키와 값은 그대로 둔다. `admin-articles-list.json`의 첫 글(`sectors-and-etf`, 초안)에 더하는 값은 [data-model](data-model.md) §13-3 배치를 따른다(T160) ‹B07›
- [X] T099 [P] (DOCS) B08 계약 픽스처 — `curriculum.json`([learning-public-api](contracts/learning-public-api.md) §1 1·2단계 공개 예), `curriculum-pre-release.json`(§1 첫 단계 공개 전 예), `admin-stages.json`([learning-admin-api](contracts/learning-admin-api.md) §9), `stage-readiness.json`(§10), `stage-release.json`(§11 200), `stage-check-failed.json`(§11 422 `error` 전체)과 README 표. T098과 같이 계약 예시가 줄여 보인 자리(`stages`의 여섯 단계 전부와 공개된 단계의 글 전부, `"...": "..."` 키, `...` 값)를 모두 채운 전체 응답으로 만든다 ‹B08›
- [X] T100 [P] (DOCS) B09 계약 픽스처 — `me.json`([learning-me-api](contracts/learning-me-api.md) §1), `me-article.json`(§2), `me-completion.json`(§4), `me-answer.json`(§5), `me-retry.json`(§6)과 README 표. 시각이 들어가는 필드는 키 구성 대조로 적는다. T098과 같이 `me.json`은 계약 예시가 줄여 보인 배열(`stages` 여섯 단계, `badges` 31개, 공개된 글마다의 `articles`, `masteredTerms`)을 필드 표와 `counts`의 수에 맞게 채운 전체 응답으로 만든다 ‹B09›
- [X] T101 (BE) `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java` spec 013 블록에 `LEARNING_STAGE_NOT_FOUND`(404)·`LEARNING_STAGE_NOT_RELEASED`(409)·`LEARNING_FORMAT_OUTDATED`(409)를 더한다. 메시지는 [contracts/README](contracts/README.md) "오류 코드" 표의 뜻을 한 문장으로 쓴다 ‹B07›
- [X] T102 (BE) `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java`의 같은 블록에 `LEARNING_STAGE_ORDER`·`LEARNING_STAGE_ALREADY_RELEASED`·`LEARNING_STAGE_EMPTY`·`LEARNING_STAGE_CHANGED`(409), `LEARNING_STAGE_CHECK_FAILED`(422), `LEARNING_TERM_TRANSFER_INVALID`(409)를 더한다 ‹B08›
- [X] T103 (BE) `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java`의 같은 블록에 `LEARNING_NOT_RECORDABLE`·`LEARNING_COMPLETION_REQUIRED`·`LEARNING_RETRY_NOT_AVAILABLE`(409), `LEARNING_QUESTION_NOT_FOUND`(404)를 더한다 ‹B09›
- [X] T104 [P] (FE) `frontend/vite.config.ts`의 `test.include`에 `'src/components/learning/**/*.test.ts'`를 더하고, `src/diagnosis/testCollection.guard.test.tsx`가 이 경로의 파일을 수집 대상으로 보는지 확인한다. 2판 배우기 화면은 `useAuth`를 부르므로(비로그인 끝 T183, 목록의 비로그인 한 줄 T140, 로그인 끝 T189) 배우기 화면 테스트는 `AuthContext.Provider`로 감싸 그리고, 기본 값은 `frontend/src/test/authFixtures.ts`의 `anonymousAuthContext`로 둔다. 지금 `LearningListPage.test.tsx`·`LearningArticlePage.test.tsx`는 `AppHeader`만 대역으로 바꿔 제공자 없이 그리므로 화면이 `useAuth`를 부르는 순간 던진다 ‹B10›

---

## Phase 19: 2판 Foundational (Blocking Prerequisites)

**Purpose**: 단계·글 형식·배치·확인 문제·용어 v2의 스키마와 도메인 모델(B07), 단계 공개 기록·넘겨받기의 스키마(B08). 스키마·매핑·검증은 각 묶음 안에서 닫는다(constitution "스키마 변경 규칙").

**⚠️ CRITICAL**: B07 부분이 끝나기 전에는 어떤 2판 스토리도 시작할 수 없다. B08 부분은 커리큘럼(T125)·단계 공개(T162)·넘기기(T145)의 선행이다.

### B07 — 스키마와 도메인

- [X] T105 (INFRA) `backend/src/main/resources/db/migration/V{작성 시각}__learning_stages_and_v2_content.sql` — [data-model](data-model.md) §3-1 `learning_stage`와 여섯 행(§13-2, 모두 공개 전), §3-2 `learning_article`의 `format`(기본 `V1`)·`stage_id`·`stage_position`·`published_stage_id`·`published_stage_position`·`quiz`·`quiz_seq` 열과 CHECK, 기존 아홉 글의 단계·단계 안 순서(§13-3), `status='PUBLISHED'`인 글의 공개 배치 채우기, `stage_id` NOT NULL, 새 글 18편 자리(`format='V2'`, 빈 초안, `reading_order` = min(999, 단계 번호 × 100 + 순서)), §3-3 `learning_term`의 `aliases`·`meaning`·`reading`·`caveat`, §3-4 `learning_article_revision.edition`(기본 `LEGACY`), §3-6 `learning_published_term.aliases`. `first_released_at`·`transfer_from_article_id`·`learning_release_log`는 B08이다. 새 CHECK 제약에는 1판처럼 `ck_learning_…` 이름을 붙인다(T116이 이름으로 단언한다). 적용된 `V20260917101000__learning_content.sql`은 고치지 않는다 ‹B07›
- [X] T106 [P] (BE) 값 타입 `backend/src/main/java/com/ssafy/thorp/learning/domain/vo/StageKey.java`(`ArticleId`와 같은 형식, 3~64자), `QuestionId.java`(`^q[1-9][0-9]{0,3}$`), `ChoiceId.java`(`^c[1-9][0-9]{0,3}$`) — 형식 검사는 생성자에서 한다([data-model](data-model.md) §2) ‹B07›
- [X] T107 [P] (BE) `backend/src/main/java/com/ssafy/thorp/learning/domain/model/`에 `ArticleFormat.java`(`V1`·`V2`), `Edition.java`(`LEGACY`·`CURRENT`), `Placement.java`(단계 키와 단계 안 순서 1..99), `LearningStage.java`(고정 식별자·번호·이름·수준 표시·한 줄 설명·`releasedAt`·`releasedByUserId`, 두 공개 필드는 함께 있거나 함께 없음)를 만든다([data-model](data-model.md) §3-1·§14) ‹B07›
- [X] T108 [P] (BE) `domain/model/RichText.java`와 `sealed interface Run`(`TextRun`·`TermMentionRun`·`SourceRun`) — [data-model](data-model.md) §5-2의 저장 시 검사(글자 1자 이상, 언급 `text` 1~50자, 출처 `url`은 `http`·`https`이고 2,000자 이하, 출처 조각은 첫 조각일 수 없음), 필드별 허용 조각 종류를 인자로 받는 검사, 글자 수 합 계산 ‹B07›
- [X] T109 (BE) `domain/model/ArticleBlock.java`를 넓힌다 — `KeySummaryBlock`·`MisconceptionBlock`·`ListBlock`·`TableBlock`·`DiagramBlock`·`InstitutionNoteBlock`를 더하고, `ParagraphBlock`·`HypotheticalBlock`은 `RichText`를 담게 한다. V1은 글자 조각 하나로 읽고 쓴다. 형식별 허용 블록과 상한(V1 60개, V2 80개, 블록마다 §5-3 표의 길이·개수·`values` 길이 = `categories` 길이)을 생성 시 검사한다. 블록을 패턴 switch하거나 문단·가상의 계산 예의 글자를 읽는 기존 코드 가운데 맡은 task가 없는 곳은 같은 task에서 V1 결과가 바뀌지 않게 고친다. 대상은 `learning/domain/model/PublishCheck.java`(문단 없음 판정과 블록 switch, V2 블록은 [data-model](data-model.md) §8-1대로 예외), `learning/presentation/response/AdminArticleResponse.java`의 블록 변환, `learning/presentation/controller/LearningAdminController.java`의 `toBlock`, 블록을 만드는 기존 테스트(`LearningArticleTest`·`PublishCheckTest`·`LearningQueryServiceTest`·`LearningRepositoryTest`)다. `LearningContentJson`·`LearningQueryService`의 같은 자리는 T114·T122가, `AdminArticleResponse`·`LearningAdminController`의 V2 모양은 T156이 맡는다. T108 뒤 ‹B07›
- [X] T110 [P] (BE) `domain/model/QuizQuestion.java`·`QuizChoice.java`·`QuizIdentity.java` — 저장 시 검사(문제 0~3개, 보기 0~5개, 문제 안 `choiceId` 중복 없음, 길이 §6-1), `quiz_seq`로 새 문제·보기 식별자 주기(문제와 보기가 같은 카운터, 지운 번호 재사용 없음), 지금 초안에 없는 식별자 거절, 가장 최근 판과의 같은 문제 판정(§6-3 세 경우)과 새 식별자 대응표 `renamed`를 돌려주는 순수 함수 ‹B07›
- [X] T111 [P] (BE) `domain/model/ReadingEstimate.java` — 본문 글자 수(R20의 대상 블록, 공백 제외), 반올림한 분(최소 1), 최소 머문 시간 = max(최저 초, ceil(반올림 전 초 ÷ 나눗수))를 규칙 값(분당 글자 수·나눗수·최저 초)을 인자로 받아 계산한다 ‹B07›
- [X] T112 (BE) `domain/model/LearningTerm.java`에 `aliases`(0~4개, 각 1~40자, 중복 없음)·`meaning`·`reading`·`caveat`(각 ≤200)을 더하고 V1(`sentence`)·V2 모양을 가른다. `domain/model/LearningArticle.java`에 `format`, 초안 배치, 공개 배치, `quiz`, `quizSeq`를 더하고 `saveDraft`가 형식별 모양 검사·식별자 주기(T110)를 하게 한다. V2→V1 전환은 인자로 받은 "LEGACY 판 있음"이 참일 때만 허용하고 아니면 `FormatOutdatedException`(409)을 던진다. `publish`는 공개 배치를 초안 배치로 바꾸고 `unpublish`는 비운다. 불변식 `(publishedPlacement == null) == (publishedRevisionId == null)`을 생성자에 둔다. `LearningTerm`·`LearningArticle`의 생성자와 용어 `sentence()`를 쓰는 기존 코드 가운데 맡은 task가 없는 곳은 같은 task에서 V1 결과가 바뀌지 않게 고친다. 대상은 `LearningAdminController.toCommand`의 `new LearningTerm`, `AdminArticleResponse`의 용어 변환, `PublishCheck`의 용어 문장 검사, 용어·글을 만드는 기존 테스트(`LearningArticleTest`·`PublishCheckTest`·`LearningQueryServiceTest`·`LearningRepositoryTest`)다. `LearningContentJson`·`LearningArticleMapper`·`LearningAdminService.create`·`LearningQueryService`·`PublishedTermRepositoryImpl`의 같은 자리는 T114·T115·T156·T122·T157이 맡는다. T107~T110 뒤 ‹B07›
- [X] T113 (BE) `domain/model/ArticleSnapshot.java`·`ArticleRevision.java`에 `format`·`edition`·`quiz`·`reading`(V2)을 더한다. V1 판은 `format`이 없고 `quiz`·`reading`이 비어 있다([data-model](data-model.md) §4). 두 생성자를 쓰는 기존 코드 가운데 맡은 task가 없는 곳은 같은 task에서 V1 결과가 바뀌지 않게 고친다. 대상은 판을 만드는 기존 테스트 둘이다: `backend/src/test/java/com/ssafy/thorp/learning/application/service/LearningQueryServiceTest.java`의 `revision`, `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/persistence/LearningRepositoryTest.java`의 판 저장 경우와 `revisionsAreAppendOnly`. `LearningContentJson`·`ArticleRevisionMapper`·`LearningAdminService.publish`의 같은 자리는 T114·T115·T157이 맡고, 그 task가 V2 값을 채우기 전까지는 V1 값(`format` 없음·`LEGACY`·빈 확인 문제·`reading` 없음)으로 컴파일되게 둔다. T112 뒤 ‹B07›
- [X] T114 (BE) `backend/src/main/java/com/ssafy/thorp/learning/infrastructure/persistence/LearningContentJson.java` — V2 블록·글자 조각·확인 문제·용어(다른 이름·세 문장)·`reading`의 읽기와 쓰기. V1 판은 지금 모양 그대로 읽고 쓴다(`format` 키 없음 = V1). 알 수 없는 블록 `type`·조각 키는 지금처럼 `IllegalStateException`이다. 필수 키가 빠진 V2 판을 0이나 빈 값으로 채우지 않는다(T079와 같은 원칙). T109·T110·T113 뒤 ‹B07›
- [X] T115 (BE) JPA 매핑 — `LearningStageJpaEntity`와 `LearningStageJpaRepository`·`learning/application/repository/LearningStageRepository.java`·`…/persistence/LearningStageRepositoryImpl.java`, `LearningArticleJpaEntity`·`LearningTermJpaEntity`·`LearningArticleRevisionJpaEntity`·`LearningPublishedTermJpaEntity`의 새 열, `LearningArticleMapper`·`ArticleRevisionMapper`. `RepositoryTestSupport`의 import 목록에 새 저장소 구현을 더한다. T105·T114 뒤 ‹B07›
- [X] T116 (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/persistence/LearningMigrationTest.java`를 넓힌다 — 단계 여섯 행(번호·이름·수준·한 줄 설명, 모두 공개 전), 27편의 단계·단계 안 순서(§13-3), 기존 아홉 글의 제목·`format=V1` 유지, 새 18편 `format=V2`·빈 초안, 게시 중인 글의 공개 배치, 공개 배치 CHECK 위반 삽입 거절, 단계의 `released_at`·`released_by_user_id` 가운데 하나만 채우는 갱신 거절(§3-1 CHECK), `edition` 기본값 `LEGACY`(quickstart 1 "2판 마이그레이션"). **거절 테스트는 걸린 제약의 이름까지 단언한다.** 지금 단언은 예외 종류(`DataIntegrityViolationException`)뿐이라, 다른 제약이 먼저 걸려도 통과한다. 1판 거절 테스트 둘(`rejectsPublishedWithoutRevision`·`rejectsInvalidArticleKey`)은 `stage_id`·`stage_position` 없이 `learning_article`에 넣으므로, T105가 두 열에 NOT NULL을 건 뒤에는 검사하려던 CHECK(`ck_learning_article_published_revision`·`ck_learning_article_key_format`)가 아니라 NOT NULL 위반으로 통과한다. 두 INSERT에 단계와 단계 안 순서를 채워 대상 CHECK만 걸리게 하고, 예외의 가장 안쪽 원인 메시지에 그 제약 이름이 있는지 본다(`presentation/support/UniqueConstraintViolations`와 같은 판별). `rejectsDuplicateRevisionNo`(`uq_learning_article_revision_no`)와 이 task가 더하는 2판 거절 테스트도 같은 방식으로 단언하며, 2판 CHECK의 이름은 T105가 마이그레이션에서 붙인 이름이다 ‹B07›
- [X] T117 (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/support/LearningSeeds.java` — `reset()`이 마이그레이션이 만든 27편([data-model](data-model.md) §13-3)은 남기고 그 밖의 글만 지우며, 27편의 초안 열(제목·`reading_order` 포함)과 2판 열(배치·형식·확인 문제·`quiz_seq`), 용어 v2 열, 단계의 `released_at`·`released_by_user_id`를 마이그레이션 직후 값으로 되돌리게 한다. 게시를 비우는 갱신은 `published_revision_id`와 공개 배치(`published_stage_id`·`published_stage_position`)를 한 문장에서 함께 비운다. 기존 게시 도우미(`publishFromResponse`·`publishMinimal`이 거치는 `publish`)는 판을 걸 때 공개 배치를 그 글의 초안 배치로 함께 채운다([data-model](data-model.md) §3-2 CHECK). `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/persistence/LearningRepositoryTest.java`의 `insertPublishedRevision`도 같은 CHECK에 맞춰 공개 배치를 함께 채운다. 도우미 `markStageReleased(stageKey)`(테스트 전용 직접 갱신), `publishCurrent(articleId, …)`(V2 판을 공개 배치와 함께 심음)를 더한다. T115 뒤 ‹B07›
- [X] T118 [P] (BE 테스트) 도메인 단위 테스트 `backend/src/test/java/com/ssafy/thorp/learning/domain/model/` — `RichTextTest`(조각 규칙), `ArticleBlockV2Test`(블록별 상한·형식별 허용), `QuizIdentityTest`(식별자 주기, 지어 낸 식별자 거절, 같은 문제 판정 세 경우, 지운 번호 재사용 없음), `ReadingEstimateTest`(1,749·1,750·3,749·3,750자의 분, 1,750자 70초·2,480자 100초·3,749자 150초의 최소 머문 시간(올림), 최소 머문 시간 최저 30초), `LearningArticleV2Test`(V2→V1 전환 규칙, 공개 배치 불변식) ‹B07›

### B08 — 단계 공개 기록과 넘겨받기의 스키마

- [X] T119 (INFRA) `backend/src/main/resources/db/migration/V{작성 시각}__learning_stage_release.sql` — [data-model](data-model.md) §3-7 `learning_release_log`(UNIQUE `entry_key`, `kind` CHECK, `ARTICLE_ADDED`일 때만 `article_id`), §3-2 `learning_article.first_released_at`, §3-3 `learning_term.transfer_from_article_id`와 `uq_learning_term_key` 삭제·부분 유일 색인 둘(`uq_learning_term_key_owned`·`uq_learning_term_key_incoming`). 이 마이그레이션은 데이터를 지우거나 기존 값을 바꾸지 않고, 새 색인은 기존 행이 어길 수 없다(`transfer_from_article_id`가 모두 NULL이고 `term_key`는 지금 유일 제약으로 이미 유일하다). 그래서 constitution "스키마 변경 규칙"의 되돌릴 수 없는 변경이 아니며(plan Constitution Check), 이 판정과 영향 범위(지우는 것은 제약 하나이고 409로 바꾸는 제약 이름이 바뀐다, T120)를 B08 MR 본문에 적는다 ‹B08›
- [X] T120 (BE) `domain/model/ReleaseLogEntry.java`, `LearningArticle`의 `firstReleasedAt`(한 번 쓴 뒤 비우지 않음), `LearningTerm`의 `transferFrom`, JPA 매핑(`LearningReleaseLogJpaEntity`와 저장소, 새 열), `learning/application/repository/ReleaseLogRepository.java`(추가와 조회만), `learning/presentation/support/UniqueConstraintViolations.java`의 제약 이름을 두 부분 색인으로 바꾼다. T119 뒤 ‹B08›
- [X] T121 (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/persistence/LearningMigrationTest.java`에 B08 열·테이블·부분 색인(같은 `term_key`를 소유 행 둘로 넣으면 거절, 소유 하나와 넘겨받기 하나는 허용, 넘겨받기 둘은 거절)을 더하고, `backend/src/test/java/com/ssafy/thorp/learning/support/LearningSeeds.java`의 `reset()`이 변경 기록·`first_released_at`·`transfer_from_article_id`를 되돌리게 한다. 단계 공개 상태와 27편 유지는 B07의 T117이 맡는다 ‹B08›

**Checkpoint**: 빈 DB에 2판 스키마가 생기고 매핑이 `validate`를 통과하며, 도메인 모델이 V1·V2를 함께 다룬다

---

## Phase 20: User Story 1 — 단계별 커리큘럼에서 글을 골라 읽는다 (Priority: P1) 🎯 MVP

**Goal**: 첫 단계 공개 전에는 1판 목록·글을 그대로 보이고, 공개 뒤에는 단계별 커리큘럼(여정 지도·목록 보기)과 새 기준 글(V2 요소·다른 이름·출처 표시·이전 판)을 보인다.

**Independent Test**: 테스트로 1·2단계를 공개 상태로 심은 DB에서 `/learn`이 여정 지도·목록 보기·공개 경계·준비 중 단계·이전 판 카드·공개 안내를 보이고, CURRENT 글이 새 요소를 텍스트로 그리며, 단계를 하나도 공개하지 않은 DB에서는 1판과 같은 목록·글이 보이는지 확인한다(quickstart 6-1, 8-4, 9-1~9-3, 9-5, 11-1, 11-2).

### Backend — 공개 글과 커리큘럼

- [X] T122 [US1] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/service/LearningQueryService.java`의 글 하나 조회를 2판으로 넓힌다 — `edition`(LEGACY 판은 공개된 단계가 하나도 없으면 `PRE_RELEASE`, 있으면 `PREVIOUS`), 공개 배치의 `stage`, `number`(공개된 글만), `readingMinutes`(V2는 판 값, LEGACY는 조회 때 계산), `minDwellSeconds`(LEGACY는 `null`), V2 블록과 언급 조각의 `owned`·`aliases`(소유 용어는 판에서, 다른 글의 용어는 게시 색인에서), 용어 블록의 세 문장과 다른 이름, `quiz`(정답·풀이 포함, R22), `next`, `stageArticleCount`, `hasUnreleasedStages`. CURRENT 판에 예시가 없으면 `judgeExample`이 예외를 던지지 않고 결과의 `example`을 `null`로 둔다(V2는 예시가 선택이다, [data-model](data-model.md) §9, [learning-public-api](contracts/learning-public-api.md) §3-2). 예시 없는 LEGACY 판은 v1 검사가 막는 상태라 지금처럼 실패시킨다. LEGACY 판의 블록은 1판 모양 그대로다. `LearningArticleResult.Block`에 글자 조각을 이어 붙인 평문을 내는 메서드를 두어 017처럼 글을 근거로 읽는 소비자가 조각 구조를 몰라도 되게 한다(⑧). 다른 글이 소유한 용어의 다른 이름은 `PublishedTermRepository`에 새로 두는 여러 용어 읽기 메서드(예: `findPublishedAliases(Collection<TermId>)`, 게시되지 않은 용어는 결과에 없음)로 한 번에 읽는다. 지금 이 저장소의 읽기는 용어 하나씩(`findPublished`·`findOwnerArticleKey`)이다. 미리 보기(T159)도 이 메서드를 쓴다. 생성자에 의존(단계 저장소·규칙 포트 등)이 늘면 `backend/src/test/java/com/ssafy/thorp/learning/application/service/LearningQueryServiceTest.java`의 세 인자 생성자 호출도 같은 task에서 고친다 ‹B07›
- [X] T123 [US1] (BE) `learning/presentation/response/LearningArticleResponse.java`와 `LearningController`를 [learning-public-api](contracts/learning-public-api.md) §3에 맞춘다 — 필드 추가만 하고 1판 필드의 모양과 `NON_NULL` 규칙은 바꾸지 않는다(배포 순서 호환). `LearningArticleResponse.from`은 결과의 `example`이 `null`이면 그 필드를 읽지 않고 키를 남긴 `"example": null`로 싣는다(§3-2) ‹B07›
- [X] T124 [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearningPublicApiIT.java`에 2판 경우를 더한다 — `article-current.json`·`article-previous.json`·`article-pre-release.json`과 JSON 동치, 1판 `articles-list.json`·`term.json`은 여전히 JSON 동치, 1판 `article.json`은 응답 가운데 그 파일에 있는 키(9월 판 필드)만 골라 JSON 동치(배포 순서 호환. 지금 `articleMatchesExample`의 완전 동치 단언과 머리 주석의 대조 설명을 이렇게 바꾼다), 소유하지 않은 언급의 `aliases`가 게시 색인에서 오고 그 용어가 게시되지 않았으면 빈 배열(quickstart 6-1, 9-2 서버 부분) ‹B07›
- [X] T125 [US1] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/service/LearningQueryService.java`에 커리큘럼 조회를 더한다 — 여섯 단계와 `status`(`OPEN`·`PREPARING`·`REPREPARING`), `OPEN` 단계의 공개된 글(읽는 순서, 번호, 읽기 분, 공개 용어 수, `hasExample`), `PREPARING` 단계의 이전 판 글(첫 단계를 공개한 뒤에만 채우며, `releasedStageCount = 0`이면 모든 단계에서 빈 배열이다), `releasedStageCount = 0`일 때만 `preReleaseArticles`(1판 목록과 같은 글·순서), `changeLog`(최신 순서), `notices`(`occurredAt`이 지금부터 7일 안). 공개되지 않은 단계의 새 글 제목·편수·날짜는 어떤 필드로도 내보내지 않는다(SC-017). 생성자에 더한 의존(변경 기록 저장소·`Clock` 등)에 맞춰 `LearningQueryServiceTest.java`의 생성자 호출을 같은 task에서 고친다. T120 뒤 ‹B08›
- [X] T126 [US1] (BE) `LearningController`에 `GET /learning/curriculum`과 `learning/presentation/response/CurriculumResponse.java`를 더한다([learning-public-api](contracts/learning-public-api.md) §1) ‹B08›
- [X] T127 [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearningCurriculumIT.java` — `curriculum.json`·`curriculum-pre-release.json`과 JSON 동치(시각 고정), 세 `status`, 이전 판 목록, 공개 안내 7일 경계(고정 시계로 7일 직전·직후), 공개 전 단계에 새 글 초안이 있어도 응답에 없음(quickstart 6-1, 8-4, 8-7, 11-2 서버 부분) ‹B08›

### Frontend — 글 화면 2판 (B10)

- [X] T128 [P] [US1] (FE) `frontend/src/types/learning.ts`에 2판 타입(`Edition`, `Run` 판별 합집합, V2 블록 합집합, `QuizQuestion`, `stage`·`number`·`next`·`readingMinutes`·`minDwellSeconds` 등)을 더하고, `LearningArticle`은 `edition`으로 가르는 판별 합집합으로 두고, `CURRENT` 글의 `example`만 `LearningExample | null`이다. 같은 `type` 이름의 블록이 판마다 필드가 다르므로(9월 판 `paragraph`·`hypothetical`의 `text`와 `term`의 `sentence`, V2 `paragraph`·`hypothetical`의 `content`와 `term`의 `aliases`·`meaning`·`reading`·`caveat`) `frontend/src/api/learning.ts`의 `readArticle`이 `edition`을 먼저 읽고, `CURRENT`면 [learning-public-api](contracts/learning-public-api.md) §3-2의 V2 블록과 조각으로, `PRE_RELEASE`·`PREVIOUS`면 §3-1의 9월 판 블록으로 `readBlock`을 가른다. `edition`이 없거나 이 세 값이 아니면 읽지 못한 응답으로 둔다. `CURRENT` 글의 `example: null`은 그대로 `null`로 받고 객체면 지금처럼 `readExample`로 검사한다. 9월 판 글의 `example`은 지금처럼 객체여야 한다. 알 수 없는 블록은 지금처럼 통과시켜 글 전체가 실패하지 않게 한다. 오류 코드 합집합은 `frontend/src/types/api.ts`와 맞춘다. 타입에 필수 필드가 생겨 `npm run build`가 깨지지 않게 같은 태스크에서 `frontend/src/test/learningFixtures.ts`의 `tideArticle`·`harborArticle`에 2판 필드를 채우고(9월 판 블록이므로 `edition: 'PRE_RELEASE'`), 예시 없는 `CURRENT` 글 픽스처 하나를 더한다. `example` 타입이 넓어져 `frontend/src/components/learning/ExampleEntry.test.tsx`가 깨지지 않게 픽스처 타입이나 그 사용을 맞춘다 ‹B10›
- [X] T129 [US1] (FE) `frontend/src/components/learning/article/RichText.tsx`와 `firstOccurrences.ts` — 조각을 텍스트 노드로 그린다. 출처 표시는 바로 앞 글자 옆의 '(*출처)' 링크(새 탭, `rel="noopener noreferrer"`, 마우스·초점에 출처 이름, 스크린 리더는 출처 이름과 외부 링크임을 읽음), 용어 언급은 처음 나오는 자리에 `이름 (다른 이름…)`, 다른 글이 소유한 용어의 처음 자리에 `TermHelp`(용어 글자 자체는 누를 수 없음, `returnTo`는 받은 `learningReturn`). `firstOccurrences`는 문서 순서(블록 → 필드 → 조각)로 한 번만 정한다([data-model](data-model.md) §5-4, R18) ‹B10›
- [X] T130 [P] [US1] (FE) `frontend/src/components/learning/article/`에 `KeySummary.tsx`, `Misconception.tsx`(틀린 생각 칸에 '오해' 표시, '가상의 예'), `ComparisonTable.tsx`(`th scope="col"`, '가상의 예'), `InstitutionNote.tsx`(기준 시점과 출처), `TermDefinition.tsx`('뜻'·'읽는 법' 이름표, 한계 앞 ⚠️를 `aria-label="주의"`로), `HypotheticalDiagram.tsx`(SVG와 `@visx/scale`, 그림은 `aria-hidden`, '가상의 예'와 설명 문장, 수치가 그릴 수 없는 모양이면 설명 문장만) — 빨강·파랑 토큰을 쓰지 않는다 ‹B10›
- [X] T131 [US1] (FE) `frontend/src/components/learning/article/ArticleBody.tsx` — V1·V2 블록을 모두 그리며 1판 `ArticleBlocks.tsx`를 대신한다. `frontend/src/components/learning/`의 `ArticleBlocks.tsx`·`ArticleBlocks.module.css`·`ArticleBlocks.test.tsx`를 지우고, 1판 테스트의 단언(태그 문자열을 태그로 해석하지 않음, 모르는 블록 종류는 그리지 않고 나머지는 그림)은 T134의 `ArticleBody.test.tsx`가 이어받는다. 본문 마지막 블록 바로 뒤에 완료 판정용 표지 요소(`data-article-end`)를 둔다. T129·T130 뒤 ‹B10›
- [X] T132 [P] [US1] (FE) `frontend/src/components/learning/article/EditionNotice.tsx` — `PREVIOUS` 글의 머리 '이전 판' 표시와 글 끝 한 줄('이 글은 새 기준으로 다시 쓰기 전의 판입니다. 그래서 읽은 기록과 확인 문제를 남기지 않습니다.')과 '배우기 목록으로'. `PRE_RELEASE`에는 아무것도 붙이지 않는다([design-notes](design-notes.md) §7) ‹B10›
- [X] T133 [US1] (FE) `frontend/src/pages/LearningArticlePage.tsx`를 2판으로 다시 쓴다 — 단계·번호·예상 읽기 시간 머리, `ArticleBody`, 예시와 '이 예시로 전략 진단하기'(예시가 있는 글만, 표지 뒤. `example`이 `null`이면 섹터 목록·버튼·투자 권고가 아니라는 문장을 담은 예시 구역 전체를 그리지 않는다), 글 끝 자리(비로그인 끝은 T183, 로그인 끝은 T189가 채운다), `EditionNotice`, 다음 글 연결(`learningReturn`을 넘김), 1판의 불러오지 못함·볼 수 없는 글 처리는 그대로 ‹B10›
- [X] T134 [US1] (FE 테스트) `frontend/src/components/learning/article/ArticleBody.test.tsx`(블록마다 표시와 이름표, `dangerouslySetInnerHTML` 없음), `firstOccurrences.test.ts`(다른 이름 한 번, 도움말 표시 한 번, 소유 용어에는 표시 없음), `HypotheticalDiagram.test.tsx`(설명 문장 대체), `RichText.test.tsx`(출처 링크 속성과 읽는 이름), `frontend/src/pages/LearningArticlePage.test.tsx` 갱신(1판 '진도 문구 없음' 단언을 '비로그인 화면에 학습 기록 문구 없음'으로 바꾸고, `PREVIOUS`·`PRE_RELEASE`·V2 글 화면을 더함), `frontend/src/api/learning.test.ts` 갱신(`CURRENT` 응답의 V2 블록과 `PRE_RELEASE`·`PREVIOUS` 응답의 9월 판 블록이 각각 `loaded`, `CURRENT` 응답에 `text`만 있는 `paragraph`가 오거나 9월 판 응답에 `content`만 있는 `paragraph`가 오면 `failed`, `example: null`인 `CURRENT` 글은 `loaded`이고 9월 판 글은 `failed`, `edition`이 없거나 모르는 값이면 `failed`, 모르는 블록 종류는 두 판 모두 계속 통과) (quickstart 9-1~9-3, 9-5) ‹B10›

### Frontend — 커리큘럼과 목록 (B11)

- [X] T135 [P] [US1] (FE) `frontend/src/api/learning.ts`에 `fetchCurriculum()`(`loaded`·`failed`, 예외를 던지지 않음)과 `frontend/src/types/learning.ts`의 `Curriculum`·`StageView`·`ReleaseLogEntry` 타입 ‹B11›
- [X] T136 [P] [US1] (FE) `frontend/src/components/learning/curriculum/viewStorage.ts`(`thorp.learning.view`, `curriculum`·`list`)와 `noticeStorage.ts`(`thorp.learning.dismissedNotices`, `entryKey` 목록) — `sessionStorage`만 쓰고 모든 접근을 try/catch로 감싸며 내용 버전을 둔다(002 `selectionStorage.ts`와 같은 방식) ‹B11›
- [X] T137 [US1] (FE) `frontend/src/components/learning/curriculum/`에 `StageLandscape.tsx`(여섯 풍경 `symbol`, 2색 선화, `aria-hidden`), `ArticleNode.tsx`(노드는 `frontend/src/components/learning/me/ArticleStamp.tsx`로 그리고 노드와 카드가 한 링크, 상태는 prop으로 받고 없으면 중립 모양. `ArticleStamp`는 완료·읽는 중·안 읽음·준비 중·중립 다섯 모양이고 `aria-hidden`, `useId`로 그라디언트 식별자, 강제 색 모드에서 점선·실선 구분 유지), `StageBand.tsx`(`OPEN`·`PREPARING`·`REPREPARING`, 준비 중은 점선 테두리와 사유 문장), `JourneyMap.tsx`(노드 위치를 재서 그리는 SVG 길, `ResizeObserver`, 1280px 완만한 S자·720px 미만 곧은 세로 길, 길 그리기 700ms는 `prefers-reduced-motion: no-preference`에서만), `ReleaseBoundary.tsx`(공개 경계 한 줄), `PreviousEditionCards.tsx`(모서리 접힌 쪽지 카드, 번호·상태 없음) — 자물쇠·잠금·예고 어휘를 쓰지 않는다([design-notes](design-notes.md) §3.1·§4·§5) ‹B11›
- [X] T138 [US1] (FE) `frontend/src/components/learning/curriculum/ListView.tsx`(1280px 표: 단계마다 `<tbody>`와 `th scope="rowgroup"` 구분 줄, 맨 왼쪽 노선 열 / 720px 미만 `<ol>`, 가로 스크롤 없음)와 `ViewToggle.tsx`(`aria-pressed` 두 단추, 고른 보기를 `viewStorage`에 기억) ‹B11›
- [X] T139 [US1] (FE) `frontend/src/components/learning/curriculum/ReleaseNotice.tsx`(7일 안 줄을 한 줄로 합침, 로그인 사용자에게만 1단계가 아닌 단계 공개가 있을 때 '지금까지의 읽은 기록, 경험치, 배지는 그대로입니다.' 덧붙임, '○단계 보기'·'변경 기록' 연결, [닫기]는 `noticeStorage`에 기록, 움직임 없음, 상태 알림으로 두지 않음)와 `ChangeLog.tsx`(`<details>`, 날짜·공개한 단계나 더한 글·편수, 기록 그대로 문장 없음) ‹B11›
- [X] T140 [US1] (FE) `frontend/src/pages/LearningListPage.tsx`를 커리큘럼 조회로 다시 쓴다 — `releasedStageCount = 0`이면 `preReleaseArticles`로 1판과 같은 목록만(보기 전환·공개 안내·나의 학습 요소 없음), 공개 뒤에는 보기 전환·공개 안내·여정 지도 또는 목록 보기·공개 경계·변경 기록, 비로그인 사용자에게 `frontend/src/components/learning/me/GuestSample.tsx`('로그인하면 학습 기록이 남습니다' 한 줄과 로그인 연결(`useLoginReturn`), 숫자·상태 모양·윤곽선·점선 없는 견본 그림(흐린 길과 도장·메달 실루엣, `aria-hidden`), [design-notes](design-notes.md) §3.5. 일곱 갈래 메달 실루엣 경로는 `me/medalShapes.ts`에 두어 T193·T194가 같은 모양을 쓴다), 불러오지 못함·빈 목록은 1판 처리 그대로. `fetchLearningArticles` 사용을 없애고 옛 목록 조회의 프런트엔드 코드도 지운다. 지울 것은 `frontend/src/api/learning.ts`의 `fetchLearningArticles`·`readList`와 쓰는 곳이 없어진 `LearningListResult`, `frontend/src/types/learning.ts`의 `LearningArticleList`, `frontend/src/api/learning.test.ts`의 '배우기 글 목록 조회' 경우들, `frontend/src/pages/LearningArticlePage.test.tsx`의 `fetchLearningArticles` 대역이다. `LearningArticleSummary`·`readSummary`는 글 하나 조회(`readArticle`)가 쓰므로 남기고(`preReleaseArticles` 항목도 같은 네 필드다), 그 타입 주석의 'GET /learning/articles 목록의 한 항목'을 글 하나 응답과 `preReleaseArticles` 항목의 공통 필드로 고친다. B11을 마칠 때 `frontend/src`에서 `fetchLearningArticles`·`LearningArticleList` 검색 결과가 0건이어야 한다(T197의 전제) ‹B11›
- [X] T141 [US1] (FE 테스트) `frontend/src/pages/LearningListPage.test.tsx` 갱신(1판 '저장소에 쓰지 않음' 단언을 "학습 기록을 쓰지 않고 `thorp.learning.view`·`thorp.learning.dismissedNotices`만 쓴다. 로그인 연결을 누른 경우에만 014 `thorp.auth.returnTarget`이 더해진다"로, 첫 단계 공개 전 화면이 1판과 같음)과 `frontend/src/components/learning/curriculum/*.test.tsx`(보기 기억과 새 탭 기본값, 안내 [닫기]의 탭 범위, 합친 안내 문구와 로그인 덧붙임 조건, `PREPARING` 단계에 제목·편수·날짜 없음, 금지 어휘 없음, 좁은 화면 목록) (quickstart 4-1, 6-1, 8-4, 11-1, 11-2) ‹B11›
- [X] T142 [US1] (DOCS) `frontend/CLAUDE.md`의 저장 키 목록에 `thorp.learning.view`·`thorp.learning.dismissedNotices`를 더하고 "일반 사용자 배우기 화면은 저장소에 쓰지 않는다"를 "학습 기록은 브라우저 저장소에 쓰지 않는다. 배우기 화면이 쓰는 키는 화면 상태 키 둘(탭 단위)이고, 로그인 연결을 누르면 014 `useLoginReturn`이 `thorp.auth.returnTarget`을 쓴다"로 고친다 ‹B11›

**Checkpoint**: 비로그인으로 첫 단계 공개 전·뒤 목록과 글이 spec대로 보인다

---

## Phase 21: User Story 2 — 예시로 바로 진단을 시작한다 (Priority: P1)

**Goal**: 예시는 글마다 고르며, 예시가 있는 글만 본문 바로 뒤에 '이 예시로 전략 진단하기'를 둔다. 002 계약은 바뀌지 않는다.

**Independent Test**: 예시가 있는 V2 글과 없는 V2 글에서 버튼의 유무와 자리(본문 끝 표지 뒤, 글 끝 자리 앞)를 확인한다(quickstart 9-1, 4-3 회귀).

- [X] T143 [US2] (FE 테스트) `frontend/src/pages/LearningArticlePage.test.tsx`에 예시 경우를 더한다 — `example: null`이면 버튼도 안내도 없음, 있으면 버튼이 `data-article-end` 표지 뒤·글 끝 자리 앞, 누르면 002 계약 §2 모양 그대로(1판 T032 단언 유지) (quickstart 9-1) ‹B10›
- [X] T144 [US2] (BE 테스트) `LearningAdminApiIT`에 예시 경우를 더한다 — V2 글을 예시 없이 게시하면 예시 검사 항목이 없고 판의 `sector_map_version`이 `NOT_CHECKED`, 예시가 있으면 v2의 `EXAMPLE_SECTOR_*`·`EXAMPLE_WEIGHT_SUM`이 1판과 같이 걸림, 공개 응답의 `example`이 `null`([data-model](data-model.md) §3-4, §8-2 #7~#10) ‹B07›

**Checkpoint**: 예시 선택이 서버 검사와 화면에서 같은 규칙으로 동작한다

---

## Phase 22: User Story 3 — 도움말이 같은 설명을 쓴다 (Priority: P2)

**Goal**: CURRENT 판 용어의 도움말은 뜻 문장을 보이고, 용어는 단계 공개와 같은 트랜잭션에서 새 글로 넘어간다. 다른 화면에서 온 경우 그 화면 이름의 돌아가기 연결을 보인다. 설정 항목 네 개는 등록표 뜻 문장으로 대체할 수 있다.

**Independent Test**: V2 판 용어의 도움말 문장이 그 글 본문의 뜻 문장과 같고, 넘기기 전후로 도움말 연결이 옛 글에서 새 글로 끊김 없이 바뀌며, 돌아가기 표의 각 경로가 맞는 문구로 보이는지 확인한다(quickstart 8-5, 9-6, 12-1, 12-2 컴포넌트 부분).

- [X] T145 [US3] (BE) 넘겨받기 — `LearningAdminService`의 저장에서 V2 초안 용어의 `transferFrom`을 받아 지금 소유 글(초안 또는 게시 색인)과 같은지 확인하고(아니면 `LEARNING_TERM_TRANSFER_INVALID`), 같으면 `transfer_from_article_id`를 둔 행으로 저장한다. 게시(글마다 게시와 단계 공개 모두) 트랜잭션에서 같은 확인을 다시 하고(글마다 게시는 409 `LEARNING_TERM_TRANSFER_INVALID`, 단계 공개는 422의 `TERM_TRANSFER` 항목이며 어느 쪽이든 아무것도 쓰지 않는다), 통과하면 넘기기를 실행한다: 옛 글의 초안 용어 행과 게시 색인 행 삭제, 옛 글 `draft_version + 1`, 새 글 행의 `transfer_from_article_id` 비움, 새 판으로 색인 쓰기. 관리 목록의 `pendingTransfers`와 초안 응답 용어의 `transferFrom`(B07에서는 늘 `null`)을 채운다([data-model](data-model.md) §7-2, R24). 옛 글의 게시 색인 행 하나만 지우는 용어 단위 삭제 메서드(예: `PublishedTermRepository.deleteTerm(ArticleId, TermId)`)를 더한다. 지금 이 저장소의 쓰기는 글 단위(`replaceForArticle`·`deleteForArticle`)뿐이다. T120 뒤 ‹B08›
- [X] T146 [US3] (BE 테스트) `LearningPublicApiIT`에 V2 용어 도움말을 더한다 — `GET /learning/terms/{termId}`의 `sentence`가 그 글 CURRENT 판 용어 블록의 `meaning`과 같고 응답 모양은 1판 `term.json`과 같음(SC-003) ‹B07›
- [X] T147 [US3] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearningTermTransferIT.java` — 넘겨받기 저장의 소유 확인 409, 같은 용어를 두 글이 넘겨받으려 하면 409, 저장 뒤 소유가 달라진 넘겨받기(옛 소유 글이 그 용어를 초안에서 빼고 게시 취소함)의 글마다 게시가 409 `LEARNING_TERM_TRANSFER_INVALID`이고 판·색인·두 글의 초안이 바뀌지 않음, 같은 경우의 단계 공개가 422 `TERM_TRANSFER` 항목, 단계 공개 전 도움말은 옛 글·옛 문장, 공개 뒤 새 글·뜻 문장, 그 사이 `LEARNING_TERM_NOT_READY`가 없음, 옛 글 `draft_version`이 오르고 `hasUnpublishedChanges: true`, 옛 글의 다음 이전 판 고치기 게시가 `TERM_BLOCKS_MATCH`로 걸림(quickstart 8-5) ‹B08›
- [X] T148 [US3] (FE) `frontend/src/components/learning/returnTargets.ts`(R35 표: pathname → 문구. `/results/`(뒤에 한 글자 이상)와 `/regime-link`(그 자체와 `/regime-link/` 아래)만 앞부분 일치이고 나머지 다섯 경로는 정확 일치, 쿼리 문자열 허용, `normalizeReturnTarget` 통과 필수, navigation state의 `label`은 요구하지도 쓰지도 않음)와 `ReturnLink.tsx`(보조 `a` 연결, `navigate(path)`)를 만들고 `frontend/src/components/learning/`의 `ReturnToDiagnosisLink.tsx`·`ReturnToDiagnosisLink.module.css`·`ReturnToDiagnosisLink.test.tsx`를 지운다(테스트를 남기면 지운 파일을 import해 `npm run build`가 실패한다). 허용 경로와 연결의 단언은 T150의 `returnTargets.test.ts`·`ReturnLink.test.tsx`가 이어받는다. `LearningArticlePage`와 다음 글·글 안 도움말·목록 연결이 받은 `learningReturn`을 그대로 넘기게 한다([term-help-ui](contracts/term-help-ui.md) §3) ‹B10›
- [X] T149 [US3] (FE) `frontend/src/components/learning/TermHelp.tsx` — `returnTo`를 선택으로 바꾸고(없으면 글 연결에 state를 싣지 않음) `fallbackMeaning`(`notReady`일 때 '설명 준비 중' 대신 그 문장, 연결 없음. 받으면 뜻 자리·`note`·글 연결 순서로 둔다)과 `articleLinkLabel`(기본 `‘{articleTitle}’ 글 읽기`)을 더한다. `fallbackMeaning`이 없을 때의 `note` 표시(맨 앞, 모든 상태, `notReady`에서 '설명 준비 중'을 덧붙이지 않음)와 003 `DetailSettingsPanel`의 `on="hover"` 사용은 9월 판 그대로 동작해야 한다([term-help-ui](contracts/term-help-ui.md) §2·§5). 같은 묶음에서 `frontend/CLAUDE.md` 규칙 절의 `TermHelp.tsx`(`termId`·`label`·`returnTo`) 줄을 `termId`·`label`만 필수이고 나머지 prop은 선택이라는 뜻으로 고친다(term-help-ui §2 Props 표) ‹B10›
- [X] T150 [US3] (FE 테스트) `frontend/src/components/learning/returnTargets.test.ts`(표의 다섯 경우, 쿼리 문자열, 외부 주소·`//`·`/login` 거절, 정확 일치 경로에 덧붙은 경로(`/strategy-x`·`/strategy/x`·`/param-search/x`) 거절, `/regime-link`·`/regime-link/start` 허용과 `/regime-linkx` 거절, 뒤가 빈 `/results/` 거절, state에 `label`이 없어도 경로가 맞으면 연결), `ReturnLink.test.tsx`, `TermHelp.test.tsx` 갱신(`fallbackMeaning`과 `note`를 함께 준 경우의 상태별 표시와 순서: `ready`는 013 뜻 문장·`note`·글 연결이고 `fallbackMeaning` 없음, `notReady`는 `fallbackMeaning`·`note`이고 '설명 준비 중'과 연결 없음, `failed`는 다시 시도와 `note`. `note`만 준 9월 판 경우는 그대로. `articleLinkLabel`, `returnTo` 없음), `frontend/src/components/strategy/DetailSettingsPanel.test.tsx` 회귀 통과 (quickstart 9-6, 12-2 컴포넌트 부분) ‹B10›

**Checkpoint**: 도움말이 2판 용어와 넘기기, 다른 화면의 돌아가기를 지원한다

---

## Phase 23: User Story 4 — 학습 없이도 진단을 시작할 수 있다 (Priority: P3)

**Goal**: 나의 학습·경험치·배지가 배우기 안에만 있고 진단의 조건이 되지 않는다는 것을 확인한다.

**Independent Test**: 진단 화면(002·003·005)과 다른 메뉴에서 나의 학습 요청·알림·문구가 없는지 확인한다(quickstart 11-2 배우기 밖 알림, 11-6).

- [ ] T151 [US4] (FE 테스트) `frontend/src/pages/LearningOutsideScreens.test.tsx` — 로그인 사용자로 `/diagnosis/sectors`·`/strategy`·`/results/:token`·`/market-flow`를 그렸을 때 `/learning/me` 요청이 없고, 경험치·배지·레벨·공개 안내·'로그인하면 학습 기록이 남습니다' 문구가 없으며, 진단 진입에 학습 완료 조건이 없음(US4 시나리오 1~3, FR-030, SC-010·SC-016) ‹B13›

**Checkpoint**: 배우기 기록이 진단 흐름에 끼어들지 않는다

---

## Phase 24: User Story 5 — 운영자가 새 기준 글을 편집하고 단계를 공개한다 (Priority: P2)

**Goal**: V1 이전 판 고치기와 V2 글의 저장·검사·미리 보기, 단계 미리 검사와 단계 공개(전부 아니면 무, 번호 순서, 되돌리지 않음), 원고 가져오기·내보내기를 갖춘 관리 화면.

**Independent Test**: 허용 목록 계정으로 V2 원고를 가져와 저장·미리 보기를 하고, 검사에 걸린 단계의 공개가 아무것도 바꾸지 않으며, 고친 뒤 1단계 공개가 글·변경 기록·공개 상태를 함께 바꾸는지, 2단계를 먼저 공개하려 하면 거절되는지 확인한다(quickstart 6-2, 7-1~7-6, 8-1~8-7).

### Backend — 검사와 게시 v2 (B07)

- [X] T152 [US5] (INFRA) `backend/src/main/resources/learning/publish-checks-v2.yml`을 [data-model](data-model.md) §8-3대로 만든다(금지 표현·숫자 패턴·예외는 v1과 같고 `exampleMarker`·`exampleRequired: false`·`reading`·`quiz` 값을 더함). 같은 묶음에서 `publish-checks-v1.yml` 머리 주석의 참조를 `data-model §8-1`로 고친다(규칙 값은 바꾸지 않음) ‹B07›
- [X] T153 [US5] (BE) `domain/model/PublishCheckRulesV2.java`와 `learning/infrastructure/rules/YamlPublishCheckRulesLoader.java`가 v1·v2 두 파일을 시작할 때 읽게 하고(하나라도 없거나 틀리면 기동 실패), `learning/application/port/PublishCheckRulesPort.java`가 둘을 각각 돌려주게 한다. 지금 포트에는 `current()` 하나뿐이므로 v1·v2를 가르는 두 메서드(예: `v1()`·`v2()`)로 바꾸고, 같은 task에서 호출처를 고친다. 대상은 `LearningAdminService`의 게시, `backend/src/test/java/com/ssafy/thorp/learning/domain/model/PublishCheckTest.java`의 규칙 읽기, `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/rules/YamlPublishCheckRulesLoaderTest.java`의 기본 생성자·패키지 생성자 `YamlPublishCheckRulesLoader(Resource)`·`RESOURCE_PATH` 사용이다. 이 테스트에 v2 실제 파일 읽기와, v2 파일이 없거나 틀리면 로더 생성이 실패하는 경우를 더한다 ‹B07›
- [X] T154 [US5] (BE) `domain/model/PublishCheckV2.java` — [data-model](data-model.md) §8-2의 열일곱 검사를 모두 실행하고 순서대로 돌려주는 순수 함수. 입력은 초안, v2 규칙, 열린 대응표 섹터 집합(예시가 있을 때만 쓰임), 알려진 `termId` 집합(초안 용어 전체 ∪ 게시 색인), 검사 기준 날짜다. 기준 날짜는 `ELEMENT_FORMAT`이 제도 안내 `asOf`가 미래인지 가르는 오늘이며, 호출하는 T157·T162가 주입한 `Clock`(Asia/Seoul)의 날짜로 정한다. 금지 표현·숫자 검사 대상과 예외(오해 `wrong`, 틀린 보기, 출처 이름, 숫자 허용 요소, `(예:` 괄호), NFKC와 공백 줄이기, 위치(§8-4: `block`의 `field`, `quiz`의 `questionId`·`choiceId`), `READING_TIME` 메시지의 분과 글자 수를 정한다. 결과 모델도 같은 task에서 넓힌다. `PublishCheckFailure.Check`에 §8-2의 v2 이름(`KEY_SUMMARY`·`HEADING_COUNT`·`HYPOTHETICAL_PRESENT`·`MISCONCEPTION_PRESENT`·`READING_TIME`·`TERM_SENTENCES`·`TERM_MENTION_KNOWN`·`QUIZ`·`ELEMENT_FORMAT`)을 더하고 v1의 `TERM_SENTENCE`는 그대로 둔다. `CheckLocation`에 `Part.QUIZ`와 `field`(`block`·`term`)·`questionId`·`choiceId`(`quiz`)를 더하고, `index`를 `block`과 `quiz`(식별자 없는 새 문제)에 허용한다. `PublishCheckFailedException`의 `details` 변환은 값이 있는 새 키만 싣는다. 새 키가 없는 v1 항목의 응답은 지금 모양 그대로다(1판 `publish-check-failed.json` JSON 동치). T111·T153 뒤 ‹B07›
- [X] T155 [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/domain/model/PublishCheckV2Test.java` — 열일곱 검사가 각각 걸리는 초안과 모두 통과하는 초안, 여러 항목 동시 반환과 순서, 예외 자리(오해 `wrong`의 `확률`, 틀린 보기의 `추천합니다`, `(예: 왕복 약 0.19%)`, 표·확인 문제·제도 안내의 숫자), 같은 표현이 바로잡기 칸·정답 보기에 있으면 걸림, `(예:` 없는 괄호 안 숫자는 걸림, 전각 숫자, 제도 안내 `asOf`가 기준 날짜와 같으면 통과하고 다음 날이면 `ELEMENT_FORMAT`, 항목마다 채운 §8-4 위치(`field`·`questionId`·`choiceId`) (quickstart 7-2, 7-3) ‹B07›
- [X] T156 [US5] (BE) 관리 저장·만들기·게시 취소·목록 2판 — `LearningAdminService`·`learning/presentation/request/SaveDraftRequest.java`(V2 블록·조각·확인 문제·용어 v2의 Jackson 다형 읽기, 알 수 없는 `type`·조각 키 400)·`CreateArticleRequest.java`(`stageKey`·`stagePosition` 필수, `readingOrder` 자동, 새 글 V2)·응답 DTO. 저장은 `format`·`stageKey`·`stagePosition`·`quiz`가 없으면 지금 값을 유지하고(1판 화면 호환), 형식별 모양 위반은 `details.fields`와 함께 400, V2→V1은 LEGACY 판이 있을 때만(아니면 409 `LEARNING_FORMAT_OUTDATED`), 없는 `stageKey`는 404. 게시 취소는 공개 배치를 비운다. 목록에 `format`·`stage`·`stagePosition`·`publishedEdition`·`needsReview`·`pendingTransfers`(B07에서는 0)를 더하고, 목록 순서를 초안 단계 번호 → `stagePosition` → `articleId`로 바꾼다(지금 `LearningArticleRepository.findAllOrdered`는 `reading_order`·`article_key` 순서다). 초안 응답의 V2 용어에는 `transferFrom`을 싣는다(B07에서는 늘 `null`, T145가 채운다). 응답 DTO 작업에는 `learning/presentation/response/AdminArticleResponse.java`의 V2 블록·글자 조각·용어 v2·확인 문제·배치 필드와 `AdminArticleListResponse.java`의 목록 필드가 들어간다. 요청을 도메인으로 바꾸는 `learning/presentation/controller/LearningAdminController.java`의 `toCommand`·`toBlock`에는 V2 블록·조각·용어·확인 문제·배치 변환이 들어간다([learning-admin-api](contracts/learning-admin-api.md) §2~§5·§7) ‹B07›
- [X] T157 [US5] (BE) `domain/model/PublishMode.java`와 `LearningAdminService.publish` 2판 — [data-model](data-model.md) §10-1 게시 방식 표(V2·공개됨 → v2 검사와 `CURRENT` 판, V2·공개 전 → 409 `LEARNING_STAGE_NOT_RELEASED`, V1·공개 전 → v1 검사와 `LEGACY` 판, V1·공개됨 → 409 `LEARNING_FORMAT_OUTDATED`). V2 게시는 같은 문제 판정(T110)으로 새 식별자를 주고 초안의 문제 식별자도 같게 바꾸되 `draft_version`은 그대로 두며, 판에 `reading`을 넣고, 게시 색인에 `sentence`(뜻 문장)와 `aliases`를 쓰고, 예시가 없으면 `sector_map_version = NOT_CHECKED`다. 두 방식 모두 공개 배치를 초안 배치로 바꾼다. 응답에 `edition`·`renamedQuestions`·`transferredTerms`(B07에서는 빈 배열)를 싣는다 ‹B07›
- [X] T158 [US5] (BE) '확인 필요' — `learning/infrastructure/config/LearningProperties.java`(`thorp.learning.institution-note-review-days`, 기본 180)를 `backend/src/main/resources/application.yml`에 두고, `learning/infrastructure/config/LearningConfig.java`(`@EnableConfigurationProperties(LearningProperties.class)`)로 등록한다. `ThorpApplication`에 `@ConfigurationPropertiesScan`이 없으므로 `myrecord/infrastructure/config/MyRecordConfig.java`와 같은 방식이다. application 계층은 infrastructure를 참조할 수 없으므로(`ArchitectureTest` 계층 규칙) `LearningConfig`가 기간을 담은 불변 값(`learning/application/port/` 아래, `MyRecordLimits`와 같은 자리)을 빈으로 만들어 `LearningAdminService`에 넘기고, 관리 목록의 `needsReview`를 공개 판의 제도 안내 `asOf`와 주입한 `Clock`으로 계산한다. 제도 안내가 없거나 게시되지 않았으면 `null`(R37) ‹B07›
- [X] T159 [US5] (BE) 미리 보기 — `LearningAdminController`에 `GET /admin/learning/articles/{articleId}/preview`를 더하고, 초안으로 공개 글 응답 모양을 만든다(`edition`은 지금 게시하면 될 값, `preview: true`, `number`·`next`는 `null`, `reading`은 지금 v2 규칙, 언급 조각의 다른 이름은 초안 용어나 게시 색인, 예시 `available`은 지금 대응표). 검사하지 않고 아무것도 쓰지 않는다([learning-admin-api](contracts/learning-admin-api.md) §8) ‹B07›
- [X] T160 [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearningAdminApiIT.java` 2판 — `admin-article-v2.json`·`admin-preview.json`·`publish-check-failed-v2.json` 대조, 형식별 저장 400, V2→V1 409, 게시 방식 네 경우, `renamedQuestions`와 게시 뒤 읽은 초안(새 식별자로 바뀌고 `draftVersion`은 그대로이며, 그 초안을 같은 `expectedDraftVersion`으로 저장하면 200, 게시 전 초안의 옛 식별자로 저장하면 400), `NOT_CHECKED`, `stageKey` 없는 만들기 400, `needsReview` 179·181일(고정 시계), 1판 관리 흐름 테스트는 2판 계약에 맞춰 고친 뒤 전부 통과(`listShowsNineDrafts`의 목록 아홉 편과 `readingOrder` 1..9 단언은 T105의 27편과 T156의 새 순서(초안 단계 번호 → `stagePosition` → `articleId`)로, `readingOrder`만 싣는 만들기 요청은 `stageKey`·`stagePosition`으로, 동시 만들기 경우의 직접 `INSERT`는 `stage_id`·`stage_position`을 넣고, 관리 픽스처 셋과의 키 구성 대조는 T098이 2판 키를 더한 파일로 한다) (quickstart 1 표, 6-2, 7-4~7-6) ‹B07›
- [X] T161 [US5] (DOCS) B07 배포 전 확인 — 배포 직전 운영 DB의 `learning_article.article_key` 목록이 마이그레이션의 아홉 글과 같은지 확인한다. 10-02 덤프는 그 뒤에 관리 화면에서 만든 글을 담지 않으므로 대신 쓰지 않는다. 다른 글이 있으면 아직 적용되지 않은 T105 마이그레이션(`backend/src/main/resources/db/migration/V{작성 시각}__learning_stages_and_v2_content.sql`)에 그 글의 단계 배치를 정하는 대응을 더한다. 행을 지우거나 기존 값을 바꿔야만 풀리면 그 대응은 되돌릴 수 없는 변경이므로 배포 전에 운영 DB를 백업하고 팀 승인을 받는다. 확인 결과, 영향 범위(기존 행에 NOT NULL을 거는 `stage_id`, 제약 추가), plan Constitution Check의 판정(되돌릴 수 없는 변경이 아니고, 실패하면 마이그레이션 전체가 되돌아간다)을 B07 MR 본문에 적는다(R15, constitution "스키마 변경 규칙") ‹B07›

### Backend — 단계 공개 (B08)

- [X] T162 [US5] (BE) `learning/application/service/LearningStageAdminService.java`와 `domain/model/StageRelease.java` — 단계 목록(초안 단계 글, 공개 배치만 이 단계인 글, `releasable`), 미리 검사(쓰기 없음: `blockers` `ALREADY_RELEASED`·`ORDER`·`EMPTY`, 글마다 `FORMAT`·`PLACEMENT`·`TERM_TRANSFER`·`TERM_ID_TAKEN`·v2 검사), 단계 공개 트랜잭션([data-model](data-model.md) §10-3: 단계 행 잠금 → 순서·이미 공개·빈 단계 409 → 공개 집합 글 행을 `id` 순서로 잠금 → 목록·초안 번호 불일치 409 `LEARNING_STAGE_CHANGED` → 글마다 항목 하나라도 걸리면 422 `LEARNING_STAGE_CHECK_FAILED`로 아무것도 쓰지 않음 → 시각 하나로 글마다 게시(T157)·넘기기(T145)·비어 있는 `first_released_at`·`released_at`·`released_by_user_id`·`STAGE_RELEASED` 변경 기록). 편수는 `first_released_at`이 비어 있던 글만 세고 LEGACY 판이 있던 글이 다시 쓴 글이다(그때 제목 저장). T145·T157 뒤 ‹B08›
- [X] T163 [US5] (BE) `backend/src/main/java/com/ssafy/thorp/learning/application/service/LearningAdminService.java`의 글마다 게시가 공개된 단계에 처음 게시되는 글이면 `first_released_at`과 `ARTICLE_ADDED` 변경 기록(그때 제목)을 같은 트랜잭션에서 쓰게 한다. 다시 게시·게시 취소·공개된 단계 사이 이동은 기록하지 않는다. 넘기기 전 `transferFrom` 재확인(T145)에 걸리면 409 `LEARNING_TERM_TRANSFER_INVALID`이고 판·색인·초안·변경 기록을 쓰지 않는다. 응답의 `transferredTerms`를 단계 공개와 같은 `termId`·`fromArticleId`·`toArticleId` 모양으로 채운다 ‹B08›
- [X] T164 [US5] (BE) `learning/presentation/controller/LearningStageAdminController.java` — `GET /admin/learning/stages`, `GET /admin/learning/stages/{stageKey}/readiness`, `POST /admin/learning/stages/{stageKey}/release`와 요청·응답 DTO. 경로가 `LearningAdminWebConfig` 가로채기와 `SecurityConfig`의 `/admin/learning/**`에 들어가는지 확인한다([learning-admin-api](contracts/learning-admin-api.md) §9~§11) ‹B08›
- [X] T165 [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearningStageReleaseIT.java` — 픽스처 넷 대조, 2단계 먼저 409, 빈 단계 409, 목록·번호 불일치 409, 글 하나 실패 시 422와 아무 변화 없음(판·이벤트·색인·`released_at`·변경 기록), 미리 검사가 아무것도 쓰지 않음, 성공 시 모든 쓰기가 같은 시각, 다시 쓴 글 수와 제목, 공개된 단계에서 옮겨 온 글은 편수에 없음, `ARTICLE_ADDED` 한 번(다시 게시해도 늘지 않음), 모든 글 게시 취소 뒤 `REPREPARING`과 공개일 유지, 비로그인 401·권한 없음 404·CSRF 403(quickstart 8-1~8-4, 8-6, 8-7) ‹B08›
- [X] T166 [US5] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearningStageReleaseIT.java`에 동시성 경우를 더한다 — 같은 단계 공개 둘이 동시에 오면 하나만 성공하고 다른 하나는 `LEARNING_STAGE_ALREADY_RELEASED`, 단계 공개와 그 단계 글의 저장이 겹치면 저장이 먼저면 `LEARNING_STAGE_CHANGED`·공개가 먼저면 저장이 `LEARNING_DRAFT_CHANGED` 없이 정상 저장(공개 뒤 초안 저장. 공개가 그 글의 초안을 바꾸지 않은 경우다). 공개가 그 글의 `transferFrom`을 비웠으면 공개 전에 읽은 초안의 저장은 409 `LEARNING_TERM_TRANSFER_INVALID`이고 초안이 바뀌지 않으며, 공개 뒤 다시 읽은 초안의 저장은 정상 저장이다(문제 식별자가 바뀌는 경우는 T160) ‹B08›

### Frontend — 관리 화면 2판 (B14)

- [ ] T167 [P] [US5] (FE) `frontend/src/types/learningAdmin.ts`와 `frontend/src/api/learningAdmin.ts` 2판 — 초안 v2 필드, 미리 보기, 단계 목록·미리 검사·단계 공개(`runCsrfProtectedMutation`), 2판 오류 코드를 결과 합집합으로(예외를 던지지 않음), `frontend/src/types/api.ts` 합집합 갱신. 검사 위치도 2판으로 넓힌다. `PublishCheckLocation`의 `part`에 `quiz`를, 선택 필드로 `field`·`questionId`·`choiceId`를 더하고, `frontend/src/api/learningAdmin.ts`의 `CHECK_LOCATION_PARTS`와 `readLocation`이 이를 읽게 한다. `readFailures`는 모르는 `part`가 하나라도 있으면 목록 전체를 비우므로, 이 확장이 없으면 v2 게시 422의 항목이 하나도 보이지 않는다. 단계 미리 검사와 단계 공개 422의 글별 `failures`도 같은 `readFailures`로 읽는다. 공용 타입에 `quiz`를 더하면 `frontend/src/components/learning/admin/PublishCheckResultList.tsx`의 `describeLocation` switch가 깨지므로, 같은 task에서 `quiz` 위치(문제 식별자와 있으면 보기 식별자, 식별자 없는 새 문제는 순번, 둘 다 없으면 확인 문제 전체)와 `field`를 사람이 읽는 위치로 더한다. 단계 미리 검사에서 글마다 그려도 제목 `id`가 겹치지 않게 한다 ‹B14›
- [ ] T168 [P] [US5] (FE) `frontend/src/components/learning/admin/richTextSyntax.ts`(`{{용어:termId|글자}}`·`{{출처:이름|https://…}}`·`\{{`를 조각 배열로, 조각 배열을 문법 문자열로. 틀린 문법은 줄·열 위치를 가진 오류)와 `RichTextField.tsx`(문법 문자열을 편집하고 저장 때 조각으로 바꿈, 오류 위치 표시) — 서버와 독자 화면은 이 문법을 모른다(R26) ‹B14›
- [ ] T169 [US5] (FE) `frontend/src/components/learning/admin/BlockEditor.tsx` 2판(V2 블록 열 종류 추가·순서 바꾸기, 표·도식·제도 안내 전용 입력, V1 형식이면 1판 네 종류만)과 `PlacementEditor.tsx`(단계 선택·단계 안 순서·형식 표시, V2→V1 전환은 LEGACY 판이 있는 글에서만 보임). T168 뒤 ‹B14›
- [ ] T170 [US5] (FE) `frontend/src/components/learning/admin/QuizEditor.tsx`(문제 2~3개, 보기 2~5개, 정답 하나를 고르는 라디오, 풀이, 받은 식별자 유지·새 항목은 `null`)와 `TermListEditor.tsx` 2판(다른 이름·뜻·읽는 법·한계, 저장 응답이 `LEARNING_TERM_ID_TAKEN`이면 `ownerArticleId`로 '이 글로 넘겨받기' 제안 → `transferFrom` 설정, 넘겨받는 중 표시) ‹B14›
- [ ] T171 [US5] (FE) `frontend/src/components/learning/admin/ManuscriptTransfer.tsx`(원고 가져오기: `JSON.parse`와 편집 문법 해석, 지금 초안에 없는 `questionId`·`choiceId`는 `null`로 바꿈, 모양이 틀리면 위치와 함께 거절하고 편집 상태를 바꾸지 않음 / 원고 내보내기: 지금 편집 상태를 원고 JSON으로)와 `docs/learning-content/README.md`(원고 형식, 편집 문법, `current/`·`next/` 자리와 옮기는 때, 원고 MR 검토 항목: FR-006 이름, FR-027 동작 확인, SC-013 언급 표시, SC-014 출처, 보기 글자로 뜻 바꾸지 않기) ‹B14›
- [ ] T172 [US5] (FE) `frontend/src/components/learning/admin/ArticlePreview.tsx`(미리 보기 응답을 B10의 `ArticleBody`와 비로그인 확인 문제로 그림)를 `frontend/src/pages/AdminLearningEditPage.tsx`에 붙이고, 게시 응답의 `renamedQuestions`('이미 답한 사용자에게도 새 문제로 보입니다')·`transferredTerms` 안내, `LEARNING_STAGE_NOT_RELEASED`·`LEARNING_FORMAT_OUTDATED` 안내를 더한다. 게시에 성공하면 초안(계약 §4)을 다시 읽어 화면 초안과 기준 번호를 바꾸고 게시 안내는 그대로 둔다(게시가 바꾼 문제 식별자와 비운 `transferFrom`을 받기 위해서다. 다시 읽지 못하면 '새로 불러오기'를 보인다). `adminDraftStorage.ts` 내용 버전을 2로 올리고 버전 1 보관본은 복원하지 않는다는 안내와 함께 버리며, 같은 파일에 보관본의 문제 식별자를 `renamedQuestions`대로 바꾸고 `transferredTerms` 가운데 `toArticleId`가 그 글인 용어의 `transferFrom`을 비우는 함수(초안 번호는 그대로)를 더해 게시 성공 뒤 이 글의 보관본에 적용한다 ‹B14›
- [ ] T173 [US5] (FE) `frontend/src/components/learning/admin/StageReleasePanel.tsx`를 `frontend/src/pages/AdminLearningListPage.tsx`에 붙인다 — 단계 목록과 공개 상태, '미리 검사' 결과(막는 것과, 글마다 항목을 `PublishCheckResultList`로 그림, `message` 그대로), '단계 공개'는 되돌릴 수 없다는 확인 창(공개될 글 목록)에서 확인할 때만 요청, 409·422 처리, 공개에 성공하면 `published[].renamedQuestions`와 `transferredTerms`로 그 글들의 보관본을 T172의 함수로 고침. 관리 목록에 형식·단계·판·'확인 필요'·넘겨받는 중 열을 더한다 ‹B14›
- [ ] T174 [US5] (FE 테스트) `frontend/src/components/learning/admin/richTextSyntax.test.ts`(왕복, 이스케이프, 오류 위치), `ManuscriptTransfer.test.tsx`(왕복 동일, 모르는 식별자 `null`, 틀린 원고 거절 시 편집 상태 그대로), `QuizEditor.test.tsx`, `StageReleasePanel.test.tsx`(확인 전 요청 없음, 취소 시 요청 없음, 실패 항목 그대로 표시, 공개 성공 뒤 그 글 보관본의 문제 식별자·`transferFrom`이 바뀌고 초안 번호는 그대로), `frontend/src/pages/AdminLearningEditPage.test.tsx` 2판(게시 직후 저장: 게시 성공 뒤 초안을 다시 읽고, 바로 한 저장 요청이 같은 `expectedDraftVersion`과 `renamedQuestions`의 새 식별자, 비운 `transferFrom`을 싣는다)·`frontend/src/api/learningAdmin.test.ts` 2판(단계 공개 요청의 `X-XSRF-TOKEN`, `quiz` 위치와 `field`가 있는 422 항목을 읽어 목록이 비지 않음), `frontend/src/components/learning/admin/PublishCheckResultList.test.tsx` 2판(`quiz` 위치와 `field` 표시, v2 검사 이름을 그대로 보임) (quickstart 7-1, 7-4, 7-5, 8-2) ‹B14›

**Checkpoint**: 운영자가 원고를 넣고 검사하고 단계를 공개할 수 있다

---

## Phase 25: User Story 6 — 나의 학습으로 어디까지 읽었는지 보고 이어서 읽는다 (Priority: P2)

**Goal**: 로그인 사용자의 완료·읽은 위치·첫 답·익힘·경험치·레벨·배지를 서버에 기록하고, 글 끝 완료 카드와 '나의 학습' 화면, 회원정보의 학습 초기화를 제공한다. 비로그인 사용자는 확인 문제를 풀 수 있지만 아무것도 남지 않는다.

**Independent Test**: 로그인 계정으로 1단계를 모두 읽고 문제를 풀어 경험치·단계 완주·배지·레벨이 계산대로 생기고, 다른 기기에서 같은 기록이 보이며, 학습 초기화로 모두 사라지는지, 비로그인으로는 아무 기록도 생기지 않는지 확인한다(quickstart 9-4, 10-1~10-11, 11-3~11-6).

### Backend — 나의 학습 기록 (B09)

- [X] T175 [US6] (INFRA) `backend/src/main/resources/db/migration/V{작성 시각}__learning_records.sql` — [data-model](data-model.md) §11의 `learning_progress`·`learning_answer`·`learning_exp_entry`·`learning_badge_award`(모든 `user_id`는 `users(id)` 외래키 `ON DELETE CASCADE`, CHECK, UNIQUE `(user_id, kind, ref_key)`, 색인 `(user_id, article_id)`). 네 테이블은 JPA 엔티티 없이 JdbcTemplate으로만 쓰므로(T178) Flyway를 끄고 `ddl-auto: update`로 도는 `local` 프로필(`backend/src/main/resources/application-local.yml`)에서는 생기지 않는다. B09 MR 본문의 테스트 절에 '나의 학습은 Flyway를 켠 로컬([quickstart](quickstart.md) §0 명령)에서만 동작한다'를 적는다 ‹B09›
- [X] T201 [US6] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/persistence/LearningMigrationTest.java`에 B09 네 테이블을 더한다 — 네 테이블이 비어 있음, `learning_progress`의 `furthest_percent` 범위·`mastered_via` 값·`(mastered_at IS NULL) = (mastered_via IS NULL)`과 `learning_exp_entry`의 `kind`·`points` CHECK 위반 삽입 거절, 같은 `(user_id, kind, ref_key)` 장부 줄·같은 `(user_id, article_id, question_key)` 첫 답·같은 `(user_id, badge_key)` 배지의 두 번째 삽입 거절, 네 테이블 `user_id` 외래키의 삭제 규칙이 `CASCADE`(`RegimeLinkMigrationTest`의 `deleteRule`과 같은 information_schema 조회, FR-025). 네 테이블은 JPA 엔티티가 없어 `ddl-auto=validate`가 보지 않으므로 구조는 이 테스트가 지킨다. 사용자 행은 `TestUsers.ensure`로 준비한다. 또 `backend/src/test/java/com/ssafy/thorp/learning/support/LearningSeeds.java`의 `reset()`이 이 네 테이블을 맨 먼저 비우게 한다. 남은 기록은 다음 테스트의 경험치·배지 계산에 섞이고, `learning_article`을 가리키는 외래키 때문에 관리 API가 만든 글을 지우는 문장을 막는다(quickstart 1 "2판 마이그레이션"). T175 뒤 ‹B09›
- [X] T176 [US6] (BE) 도메인 `backend/src/main/java/com/ssafy/thorp/learning/domain/` — `vo/BadgeKey.java`(`ArticleId`와 같은 형식, 3~70자, [data-model](data-model.md) §2), `model/LearnerProgress.java`·`FirstAnswer.java`·`ExpEntry.java`·`ExpLedger.java`(정답 몫 상한, R29)·`Level.java`(문턱 표와 11 이상 900씩, 합계 ≥ 문턱, 보이는 가장 높은 레벨과 N·사유)·`BadgeCatalog.java`(31개: 키·갈래·이름·기준 문장, [data-model](data-model.md) §12-4)·`BadgeStatus.java`·`PreparingReason.java`·`ContentScope.java`(공개된 글·문제·용어·단계)·`LearnerFacts.java`·`Settlement.java`(단계 완주와 배지를 찾는 순수 함수, §12-5) ‹B09›
- [X] T177 [P] [US6] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/domain/model/` — `LevelTest`(합계 100 → 레벨 2, 1·2단계 8편 → 보이는 4·N 5·`MORE_STAGES`, 27편 여섯 단계 → 8·N 9·`MORE_ARTICLES`, 게시 취소로 현재 레벨이 더 높은 경우), `ExpLedgerTest`(quickstart 10-5 표의 네 경우), `BadgeCatalogTest`(31개 키, 이름에 '고수'·'전문가'·'상위'·'수익'·'순위' 없음), `SettlementTest`(운영자 게시 취소로 단계 완주 충족, `exploration`의 빈 조건, `terms-all`·`read-27`은 여섯 단계 공개 뒤, `PREPARING` 사유 다섯 가지와 묶음 기준) ‹B09›
- [X] T178 [US6] (BE) 저장 — `learning/application/repository/LearnerProgressRepository.java`·`LearnerAnswerRepository.java`·`ExpLedgerRepository.java`·`BadgeAwardRepository.java`와 `learning/infrastructure/persistence/`의 구현(JdbcTemplate): 위치 upsert(`GREATEST`, `last_opened_at` 갱신), 완료 upsert(`furthest_percent = 100`), 진행 행 `FOR UPDATE` 잠금, 첫 답·장부·배지의 `ON CONFLICT DO NOTHING` 뒤 읽기, `deleteAllForUser`(네 테이블 한 트랜잭션). 장부·답·배지에는 수정·개별 삭제 메서드를 두지 않는다 ‹B09›
- [X] T179 [US6] (BE) `learning/application/service/LearnerService.java` — [learning-me-api](contracts/learning-me-api.md) §1~§7: 전체(정산, 첫 단계 공개 전 409 `LEARNING_NOT_RECORDABLE`), 글 기록(+`continueReading`), 위치, 완료(+`ARTICLE_COMPLETED`·정산·`continueReading`), 첫 답(완료 필요, 공개 판 문제·보기 확인, 공개 판으로 판정, 저장 뒤 응답, 이미 답했으면 저장된 답, 정답 몫 상한, `all_answered_at`과 `ALL_CORRECT`, 첫 답 익힘(맞은 첫 답이 하나 생기면 모든 문제에 답하기 전이어도 `FIRST_ANSWER`, [data-model](data-model.md) §12-3), 정산), 다시 풀기(모든 문제에 답했고 첫 답 정답이 0개이며 익힘이 아닐 때만, 하나 맞히면 `RETRY` 익힘, 경험치 없음), 학습 초기화. 기록할 수 있는 글은 CURRENT 판이 게시 중인 글뿐이다. `gained`를 요청마다 만든다. T176·T178 뒤 ‹B09›
- [X] T180 [US6] (BE) `learning/presentation/controller/LearnerController.java`(`/learning/me` 일곱 엔드포인트)·`learning/presentation/support/LearnerAccess.java`(로그인 주체에서 사용자 ID, `backtest`를 참조하지 않음)·요청·응답 DTO, `backend/src/main/java/com/ssafy/thorp/common/config/SecurityConfig.java`에 `/learning/me/**` `authenticated()` 한 줄 ‹B09›
- [X] T181 [US6] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/learning/LearnerApiIT.java` — 픽스처 다섯 대조, 비로그인 401·CSRF 403, `PREVIOUS`·`PRE_RELEASE` 글 409, 첫 단계 공개 전 `GET /learning/me` 409, 읽은 위치 기록(더 작은 값을 보내도 기록된 비율이 줄지 않음, 다른 `READING` 글을 연 뒤 앞 글에 같은 값을 다시 보내면 `last_opened_at`이 바뀌어 앞 글이 `RESUME` 대상이 됨, 행이 없는 글에 0을 보내도 `READING`이고 `percent`는 1, [learning-me-api](contracts/learning-me-api.md) §3, quickstart 10-2 서버 부분), `continueReading` 세 경우(`last_opened_at`이 가장 늦은 `READING` 글의 `RESUME`, `READING` 글이 없으면 읽는 순서상 첫 `UNREAD` 글의 `START`와 `percent: null`, 공개된 글을 모두 완료하면 `ALL_DONE`이고 공개되지 않은 단계가 있으면 `moreStagesPreparing: true`·여섯 단계를 모두 공개했으면 `false`, [data-model](data-model.md) §12-2), 완료 멱등, 완료 전 답 409, 첫 답 저장 뒤 정답 공개와 다른 보기의 재요청에 저장된 답, 문제 바뀐 뒤 `LEARNING_QUESTION_NOT_FOUND`, 운영자가 문제를 바꾸거나 지워 게시한 뒤 `GET /learning/me/articles/{articleId}`의 `answers`에 공개 판에 남은 문제의 첫 답만 있고 경험치 합계가 줄지 않음([learning-me-api](contracts/learning-me-api.md) §2, quickstart 7-5 사용자 쪽), 다시 풀기 조건과 `persistence` 배지, quickstart 10-5 경험치 표, 운영자 게시 취소 뒤 `GET`에서 단계 완주·배지(10-7), 레벨 N(10-8), 학습 초기화가 그 사용자 행만 지움(10-9), 같은 문제 동시 두 요청에 행 하나(SC-009), `TestUsers.ensure`로 사용자 행 준비 ‹B09›
- [X] T182 [US6] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/ArchitectureTest.java`에 규칙 "`learning`은 `backtest`를 참조하지 않는다"를 더한다(R33, plan 게이트 재확인) ‹B09›

### Frontend — 비로그인 확인 문제 (B10)

- [X] T183 [US6] (FE) `frontend/src/components/learning/article/QuizPanel.tsx` 비로그인 모드와 글 끝 자리 — 답하면 바로 맞음·틀림과 정답·풀이, 틀린 보기에 '틀린 보기' 표시, 다음 문제로, 모두 답하면 'n문제 중 m개', 어떤 요청·저장도 하지 않고 글을 다시 열면 처음 상태. 글 끝은 확인 문제, 다음 글(없으면 '배우기 목록으로'와 공개되지 않은 단계가 있으면 '다음 단계는 준비 중입니다'), '로그인하면 학습 기록이 남습니다'와 로그인 연결(`useLoginReturn`). 첫 단계 공개 전 글(`PRE_RELEASE`)과 이전 판에는 확인 문제가 없다 ‹B10›
- [X] T184 [US6] (FE 테스트) `frontend/src/components/learning/article/QuizPanel.test.tsx` 비로그인 경우 — `/learning/me` 요청 없음(`apiClient`와 전역 `fetch` 감시), 답하고 다음 문제로 넘기는 동안 `Storage.prototype.setItem` 호출 없음(로그인 연결을 누르면 014 `useLoginReturn`이 `thorp.auth.returnTarget`만 쓴다), 다시 그리면 답하지 않은 상태, 오답 표시에 빨강 토큰 없음(quickstart 9-4, 10-11 화면 부분) ‹B10›

### Frontend — 글 끝 기록 (B12)

- [ ] T185 [P] [US6] (FE) `frontend/src/types/learningMe.ts`와 `frontend/src/api/learningMe.ts` — 글 기록·위치·완료·첫 답·다시 풀기·초기화·전체 조회를 결과 합집합으로(401은 `unauthenticated`, `LEARNING_NOT_RECORDABLE`·`LEARNING_QUESTION_NOT_FOUND`는 따로). 나의 학습 오류 코드(`LEARNING_NOT_RECORDABLE`·`LEARNING_COMPLETION_REQUIRED`·`LEARNING_RETRY_NOT_AVAILABLE`·`LEARNING_QUESTION_NOT_FOUND`)를 합집합 `LearningMeApiErrorCode`로 `frontend/src/types/learningMe.ts`에 두고 `frontend/src/types/api.ts`의 `ServiceApiErrorCode`에 잇는다(`ApiClientError.is`가 이 닫힌 합집합만 받는다). 화면 안에서 보내는 상태 변경 요청(완료·첫 답·다시 풀기·초기화, 화면 이동 때의 위치)은 `frontend/src/api/client.ts`의 `runCsrfProtectedMutation`으로 감싼다. 위치의 탭 숨김·닫기 경로는 `apiClient`와 같은 기준 주소(`import.meta.env.VITE_API_BASE_URL ?? '/api'`, `frontend/src/api/progressStream.ts`와 같은 식)를 앞에 붙인 `/learning/me/articles/{articleId}/position`으로 `fetch(…, { method: 'PUT', keepalive: true, credentials: 'include' })`를 보내고, `Content-Type: application/json`과 쿠키 `XSRF-TOKEN`에서 읽은 `X-XSRF-TOKEN` 헤더를 싣는다(R32) ‹B12›
- [ ] T186 [US6] (FE) `frontend/src/components/learning/article/useArticleCompletion.ts`(`data-article-end` 표지의 `IntersectionObserver`와 탭이 보이는 동안만 세는 시간, `minDwellSeconds`, 두 조건이 함께 참인 첫 순간에 한 번)와 `useReadingPosition.ts`(가장 큰 비율, 떠날 때마다 한 번: 화면 이동은 `apiClient`, `pagehide`·`visibilitychange` hidden은 keepalive, 한 번 떠날 때 겹치면 한 번만). 로그인 상태나 `userId`가 바뀌면 둘 다 처음부터 한다. 재확인(`checking`)과 `error`는 바뀐 것으로 보지 않으므로 그 앞뒤가 같은 상태·같은 `userId`면 센 시간과 가장 큰 비율을 이어 가고, 그동안 두 조건이 참이 되면 완료 요청은 확인이 끝나 바뀌지 않은 것을 안 뒤에 보낸다(R33) ‹B12›
- [ ] T187 [US6] (FE) `frontend/src/components/learning/article/CompletionCard.tsx` — 완료 날짜, 익힐 용어(용어가 있는 글만), 확인 문제, 이번에 받은 경험치 목록과 새 배지·레벨의 조용한 알림(미리 있는 `role="status"` 영역에 넣음, 초점을 옮기지 않음), 다음 글 카드 또는 `continueReading`에 따른 문구('이어서 읽기' / '지금 공개된 글을 모두 읽었습니다'와 '다음 단계는 준비 중입니다'), `continueReading.kind`가 `RESUME`일 때 '이어서 읽기'가 싣는 navigation state `learningResume`(T189가 받는다), 단계 진행('n편 중 m편 읽음', 다 읽었으면 '완주 · n편'). 그림 장면(단계 도장 줄과 단계 완주 메달, 레벨 표장과 레벨 길, 다음 배지 메달)과 그 움직임은 T193(B13)이 더한다. 이 묶음의 카드는 글자와 조용한 알림만으로 완결하고 빈 그림 자리를 두지 않는다. 폭죽·소리 없음([design-notes](design-notes.md) §3.4) ‹B12›
- [ ] T188 [US6] (FE) `QuizPanel.tsx` 로그인 모드 — 고른 보기를 잠그고 저장 응답 뒤에만 정답·풀이, 실패면 '기록되지 않았습니다'와 같은 보기를 다시 보내는 '다시 시도', `alreadyAnswered`면 저장된 답으로 표시, `LEARNING_QUESTION_NOT_FOUND`면 글을 다시 불러오라는 한 줄과 '다시 불러오기', 다시 들어오면 첫 답 결과와 풀이, 첫 답에서 하나도 못 맞히면 '다시 풀기'(한 문제씩, 경험치 없음, `persistence` 알림) ‹B12›
- [ ] T189 [US6] (FE) `frontend/src/pages/LearningArticlePage.tsx` 로그인 연결 — `useAuth().status`별 표시(R33 표: 확인 중(`checking`)과 `error` 동안에는 자리를 둔 채 보이지 않게만 하고 고른 보기·잠근 답·받은 글 기록과 완료 응답을 버리지 않으며 앞뒤가 같은 상태·같은 `userId`면 그대로 다시 보임, 만료는 '기록되지 않았습니다'·'다시 시도'와 014 재로그인 안내, 로그아웃 시작 즉시 숨김), 글 기록 조회와 실패 줄, 완료 실패 시 완료 카드·확인 문제 대신 '기록되지 않았습니다'와 '다시 시도', `ResumeLine.tsx`('62%까지 읽었습니다 · 이어서 읽기' 한 줄, 완료한 글에는 없음), navigation state `learningResume`이면 기록된 위치로 스크롤, CURRENT 글에서만 기록 ‹B12›
- [ ] T190 [US6] (FE 테스트) `useArticleCompletion.test.ts`(가짜 시계·`IntersectionObserver` 대역, 숨긴 시간 제외, 두 조건 순서 무관, 한 번만, `checking`이나 `error`를 거쳐 같은 `userId`로 돌아오면 센 시간을 이어 가고 다른 `userId`나 `anonymous`면 처음부터), `useReadingPosition.test.ts`(비율이 그대로여도 전송, keepalive 요청의 `X-XSRF-TOKEN`, `checking`이나 `error`를 거쳐 같은 `userId`면 가장 큰 비율을 이어 감), `CompletionCard.test.tsx`(문구 분기, 상태 알림이 초점을 옮기지 않음), `QuizPanel.test.tsx` 로그인 경우(저장 전 정답 없음, 실패 시 같은 보기 재전송), `frontend/src/api/learningMe.test.ts`(keepalive 위치 기록의 실제 주소가 `apiClient.defaults.baseURL`(테스트 기본값 `/api`)을 앞에 붙인 `/learning/me/articles/{articleId}/position`이고 `Content-Type: application/json`과 `X-XSRF-TOKEN`이 실림), `LearningArticlePage.test.tsx`의 로그인 상태별 경우(완료 훅이 `IntersectionObserver`를 쓰므로 이 파일에도 대역을 파일 안에서 둔다. `src/test/setup.ts`에 전역으로 두면 `src/hooks/useNearViewport.test.tsx`의 '없는 환경' 경우가 깨진다. 재확인 동안 완료 카드·확인 문제를 숨겼다가 같은 `userId`면 글 기록을 다시 요청하지 않고 잠근 답과 완료 카드를 그대로 보임, 비로그인에서는 떠날 때 `fetch`도 `apiClient`도 부르지 않음)와 1판 '저장소에 쓰지 않음' 단언 갱신 (quickstart 10-1~10-4, 10-10) ‹B12›

### Frontend — 나의 학습 화면 (B13)

- [ ] T191 [US6] (FE) `frontend/src/components/learning/me/useLearnerOverview.ts` — 로그인 상태이고 커리큘럼 `releasedStageCount > 0`일 때만 `GET /learning/me`, 사용자가 바뀌거나 로그아웃하면 버림. 재확인(`checking`)·`error` 동안에는 `user`가 `null`이어도 사용자가 바뀐 것으로 보지 않아 받아 둔 응답을 그대로 두고, 같은 `userId`로 확인되면 다시 요청하지 않음(R33), 실패 상태와 사용자가 누르는 다시 시도(조용한 재시도 없음), 브라우저 저장소에 쓰지 않음 ‹B13›
- [ ] T192 [US6] (FE) `frontend/src/components/learning/me/SummaryPanel.tsx`(레벨 표장·레벨에 닿은 지 7일 안이면 레벨 옆 '새' 알약(`level.isNew`)·다음 레벨까지 남은 경험치·읽은 글·익힌 용어(분모 없음)·받은 지 7일 안의 배지가 있을 때만 '새 배지: 1단계 완주'와 '새' 알약 한 줄(`badges[].isNew`, 레벨 갈래 배지는 레벨 옆 '새'와 겹치므로 빼고 여럿이면 가장 최근 배지 이름에 '외 n개')·'이어서 읽기'·'모두 보기', 실패면 '학습 기록을 불러오지 못했습니다'와 '다시 시도' 한 번)와 `FloatingContinue.tsx`(커리큘럼 보기에서만, 패널의 '이어서 읽기'가 화면 밖이면 오른쪽 아래, 실패·모두 읽음이면 없음, 깜빡이거나 맥동하는 움직임 없음)를 `LearningListPage`에 붙이고, 패널과 떠 있는 단추의 '이어서 읽기'는 `continueReading.kind`가 `RESUME`이면 navigation state `learningResume`을 실어 열고, 글별 상태(`ArticleNode`에 넘기는 상태와 `ListView` 줄: '완료'·'62% 읽는 중'·'안 읽음', 용어 n/m)와 단계별 완료 수(커리큘럼 보기의 띠에는 글 도장 줄과 `StageMedal`을 함께 둔다, [design-notes](design-notes.md) §3.1)를 채운다. 실패면 그 자리들을 비우고 '안 읽음'·0편으로 채우지 않는다. 재확인(`checking`)과 `error` 동안에는 이 자리들을 접지 않고 숨겼다가 같은 `userId`로 확인되면 다시 요청하지 않고 그대로 보인다(R33). T193 뒤 ‹B13›
- [ ] T193 [P] [US6] (FE) `frontend/src/components/learning/me/`에 `LevelEmblem.tsx`(둥근 팔각 초록 금속, 레벨에 따라 고리·눈금만 늚), `LevelPath.tsx`(이정표, 보이는 가장 높은 레벨 뒤 점선과 사유별 문장, '도착' 없음), `StageMedal.tsx`(단계 색 스테인드글라스), `BadgeMedal.tsx`(일곱 실루엣 × 받음·아직·준비 중, 진행 호) — 모두 `aria-hidden`, `useId`로 그라디언트 식별자, 강제 색 모드에서 점선·실선 구분 유지, 메달 실루엣 경로는 T140의 `me/medalShapes.ts`를 쓴다([design-notes](design-notes.md) §3.3·§4). 이 부품과 `ArticleStamp`(T137)로 `frontend/src/components/learning/article/CompletionCard.tsx`(T187)에 단계 도장 줄과 단계 완주 메달, 레벨 표장과 레벨 길, 다음 배지 메달을 더한다. 마지막 도장·색유리·레벨 숫자·레벨 길 점의 움직임은 `no-preference`에서만 950ms 안에 끝나고 폭죽·소리는 없으며, 그림은 `aria-hidden`이고 글자 정보는 T187 그대로다([design-notes](design-notes.md) §3.4·§5) ‹B13›
- [ ] T194 [US6] (FE) `frontend/src/pages/LearningMePage.tsx`와 `frontend/src/components/learning/me/BadgeFamilies.tsx`, `frontend/src/App.tsx`의 `/learn/me` 라우트(`/learn/:articleId`보다 먼저) — FR-020 순서(레벨 카드, 숫자 줄과 '지금 공개된 글은 n편입니다', 단계별 진행, 이어서 읽기(`continueReading.kind`가 `RESUME`이면 navigation state `learningResume`을 싣는다), 익힌 용어(누르면 그 글), 배지, 변경 기록), 배지 상태 글자와 `preparingReason` → [design-notes](design-notes.md) §7 문구, 같은 문구의 준비 중 기준만 한 줄로 묶고 단계 완주는 낱장, 7일 안에 닿은 레벨과 받은 배지의 '새' 알약(`level.isNew`·`badges[].isNew`), 경험치 내역 없음. 비로그인은 `GuestSample`(T140)의 한 줄·로그인 연결·견본 그림과 그 아래 '나의 학습에 쌓이는 것' 세 칸(숫자·상태 없음), 첫 단계 공개 전에는 `/learn`으로 보냄 ‹B13›
- [ ] T195 [US6] (FE) `frontend/src/components/learning/me/LearningResetSection.tsx`를 `frontend/src/pages/AccountProfilePage.tsx` 아래 '학습 기록' 구역으로 붙인다 — '학습 초기화'는 지워지는 것(완료·읽은 위치·첫 답·익힘·경험치·배지)과 되살릴 수 없다는 안내의 확인 창에서 확인할 때만 `DELETE /learning/me`, 성공·실패 문장. '나의 학습' 화면에는 두지 않는다(FR-025, R36). 개발 전용 `preview` 모드(`/dev/account-profile`)에서는 닉네임 저장의 미리보기 처리와 같이 확인해도 `DELETE /learning/me`를 부르지 않고 지우지 않았다는 안내만 보인다 ‹B13›
- [ ] T196 [US6] (FE 테스트) `SummaryPanel.test.tsx`·`FloatingContinue.test.tsx`(실패 시 빈 자리, 모두 읽음이면 단추 없음, 재확인 동안 숨겼다가 같은 `userId`면 `GET /learning/me`를 다시 부르지 않고 그대로 보이며 다른 `userId`면 버리고 다시 부름), `frontend/src/pages/LearningMePage.test.tsx`(순서, 배지 세 상태의 글자, 준비 중 묶음과 단계 완주 낱장, '새', 비로그인 견본, 공개 전 이동), `LearningResetSection.test.tsx`(확인 전 요청 없음, 취소 시 요청 없음, CSRF 헤더), `frontend/src/components/learning/learningCopy.test.ts`(배우기 화면 문구·`aria-label`에 '잠김'·'해금'·'열립니다'·'공개 예정'·'Coming soon'·'NEW' 없음, 배우기 상태 표시에 `--thorp-up`·`--thorp-down` 없음, SC-017) (quickstart 10-6~10-9, 11-3~11-7) ‹B13›

**Checkpoint**: 로그인 사용자의 나의 학습이 글 끝·목록·'나의 학습' 화면·회원정보에서 spec대로 동작하고, 비로그인은 아무것도 남지 않는다

---

## Phase 26: 2판 Polish & Cross-Cutting Concerns

**Purpose**: 화면이 옮겨 간 경로 정리, 문서 동기화, 전체 검증

- [ ] T197 (BE) `GET /learning/articles`를 지운다 — `LearningController`의 메서드와 응답 DTO, `LearningPublicApiIT`의 해당 경우, `docs/specs/learning-api/examples/articles-list.json`과 README 표의 줄. `LearningQueryService.listPublished()`와 저장소 조회 `findPublishedOrdered()`는 커리큘럼의 `preReleaseArticles`나 017 `readArticle` 도구(게시 글 열거, 017 plan '근거 팩과 도구')가 쓰면 남긴다. 017 코드가 아직 병합되지 않았어도 017 plan이 이 조회를 가리키면 지우지 않는다([plan](plan.md) "교차 의존"). 프런트엔드에 `fetchLearningArticles`·`LearningArticleList` 참조가 0건인지 먼저 확인한다(T140이 지운다). B11이 운영에 배포된 뒤에만 병합한다 ‹B16›
- [ ] T198 (DOCS) 2판 문서 동기화 — `backend/src/main/resources/registry/parameter-registry.yml`의 주석 참조(`data-model.md §8` → `§13`), `docs/specs/learning-api/README.md` 최종 표, spec·plan·contracts와 구현의 차이 반영, [specs/README.md](../README.md) 색인 확인, 1판 `tasks.md` 머리의 Jira 문장은 기록으로 두고 고치지 않음 ‹B15›
- [ ] T199 (DOCS) [quickstart.md](quickstart.md) §6~§13을 실제 화면과 HTTP로 돌려 [validation.md](validation.md)에 '2판' 절로 기록한다 — 성공·실패·미수행을 구분하고, HTTP나 자동 확인만 있는 항목을 화면 확인으로 세지 않으며, 375px 확인은 iframe 하네스로 잰다. SC-002는 패턴 검사 통과로 증명되지 않는다는 문장과 SC-005 '사용성 확인: 미수행'(또는 결과)을 따로 적는다 ‹B15›
- [ ] T200 (DOCS) [plan.md](plan.md) "첫 단계 공개 조건" 여섯 항목의 확인 상태(누가, 언제, 결과)를 `validation.md` 2판 절에 표로 둔다. 코드 묶음의 완료와 첫 단계 공개를 섞어 보고하지 않는다 ‹B15›

---

## 2판 Dependencies & Execution Order

### Phase Dependencies

- **Phase 18 Setup**: 선행 없음. 픽스처(T098~T100)는 해당 묶음의 서버·화면 작업보다 먼저 커밋한다
- **Phase 19 Foundational**: B07 부분(T105~T118)이 모든 2판 스토리를 막는다. B08 부분(T119~T121)은 커리큘럼·넘기기·단계 공개를 막는다
- **Phase 20~25 스토리**: Foundational 뒤. 백엔드 묶음은 B07 → B08 → B09 순서, 프런트엔드 묶음은 계약 픽스처만으로 시작할 수 있다
- **Phase 26 Polish**: T197은 B11 배포 뒤, T198~T200은 B07~B14·B16 뒤

### User Story Dependencies

- **US1**: 서버 글 응답(B07) → 화면 글(B10), 서버 커리큘럼(B08) → 화면 목록(B11). 첫 단계 공개 전 모습은 B07만으로 지켜진다
- **US2**: US1의 글 화면(T133) 위에 얹는다
- **US3**: 넘기기(T145)는 B08 스키마와 게시 2판(T157)이 선행. 화면(T148~T150)은 B07 계약만 있으면 된다
- **US4**: US6 화면(B13)과 같은 묶음에서 확인한다
- **US5**: 검사·게시 2판(B07)이 단계 공개(B08)의 선행. 관리 화면(B14)은 B07·B08 계약과 B10의 `ArticleBody`가 선행
- **US6**: 서버(B09)는 B08의 공개 상태를 읽는다. 비로그인 확인 문제(T183)는 B10, 글 끝 기록(B12)은 B09 계약과 B10, '나의 학습' 화면(B13)은 B09 계약·B11·B12가 선행

### Within Each Story

- 테스트를 먼저 쓰고 실패를 확인한 뒤 구현한다. "T### 뒤"는 대상 타입·경로·계약이 정해져 있어야 한다는 뜻이다
- BE: 값 타입·모델 → 저장소 → 서비스 → 컨트롤러 → 계약 테스트
- FE: 타입·픽스처 → API 함수(결과 합집합) → 부품 → 페이지 조립 → 라우트 → 페이지 테스트

### 작업 묶음 = 브랜치·MR 단위 (B07~B16)

| 묶음 | 브랜치 | 태스크 | 수 |
|---|---|---|---|
| B07 | `feat(be)/learning-v2-content` | T098, T101, T105~T118, T122~T124, T144, T146, T152~T161 | 31 |
| B08 | `feat(be)/learning-stage-release` | T099, T102, T119~T121, T125~T127, T145, T147, T162~T166 | 15 |
| B09 | `feat(be)/learning-records` | T100, T103, T175~T182, T201 | 11 |
| B10 | `feat(fe)/learning-article-v2` | T104, T128~T134, T143, T148~T150, T183, T184 | 14 |
| B11 | `feat(fe)/learning-curriculum` | T135~T142 | 8 |
| B12 | `feat(fe)/learning-article-records` | T185~T190 | 6 |
| B13 | `feat(fe)/learning-journal` | T151, T191~T196 | 7 |
| B14 | `feat(fe)/learning-admin-v2` | T167~T174 | 8 |
| B16 | `refactor(be)/learning-articles-endpoint` | T197 | 1 |
| B15 | `docs(fe)/spec-013-v2-wrapup` | T198~T200 | 3 |

**합계 104개(T098~T201)**. 묶음 안의 태스크 번호는 MR 본문 "주요 변경 사항"에 적어 추적한다(constitution "작업 묶음").

**각 묶음이 끝나면 무엇이 달라지나**
- B07: V2 원고를 저장·검사·미리 보기 할 수 있고, 기존 글은 이전 판 고치기로만 게시된다. 첫 단계 공개 전 화면은 그대로다
- B08: 운영자가 단계를 차례로 공개하고, 커리큘럼 조회가 공개 상태·이전 판·공개 안내·변경 기록을 준다. 용어가 단계 공개와 함께 새 글로 넘어간다
- B09: 로그인 사용자의 기록·경험치·배지·레벨을 서버가 계산하고 학습 초기화로 지울 수 있다
- B10: 글 화면이 새 요소·다른 이름·출처·이전 판·비로그인 확인 문제를 보이고, 다른 화면에서 온 돌아가기를 받는다
- B11: 목록이 첫 단계 공개 전 1판 모습과 공개 뒤 여정 지도·목록 보기를 가른다
- B12: 글 끝에서 완료·읽은 위치·확인 문제가 기록되고 완료 카드가 보인다
- B13: 요약 패널·떠 있는 '이어서 읽기'·'나의 학습' 화면·학습 초기화가 생기고, 완료 카드에 그림 장면이 더해진다
- B14: 관리 화면에서 원고를 가져와 편집·미리 보기·단계 공개를 한다
- B16: 화면이 쓰지 않는 옛 목록 경로가 사라진다
- B15: 문서와 구현이 같고 2판 검증 기록이 남는다

### Parallel Opportunities

- Phase 18: T098~T100, T104 병렬. T101~T103은 같은 파일이라 묶음 순서대로
- Phase 19 B07: T106·T107·T108·T110·T111 병렬 → T109 → T112 → T113 → T114 → T115 → T116·T117·T118 병렬
- US1: 서버(T122~T127)와 화면(T128~T142)은 다른 담당이 동시에. B10 안에서 T128·T130·T132 병렬, B11 안에서 T135·T136 병렬
- US5: T152~T155(검사)와 T156·T158(저장·확인 필요) 병렬 → T157 → T159 → T160. B14 안에서 T167·T168 병렬
- US6: T176·T178 병렬(서로 다른 계층) → T179 → T180 → T181·T182 병렬. T201은 T175 뒤 T176·T178과 병렬. B13 안에서 T193은 다른 부품과 병렬
- **BE와 FE는 스토리 안에서 병렬** — 계약과 픽스처가 고정되어 있다

---

## 2판 Parallel Example: User Story 6

```bash
# 백엔드 — 도메인과 저장을 따로, 그 뒤 서비스·컨트롤러·테스트
Task: "T176 도메인 Level·ExpLedger·BadgeCatalog·Settlement"
Task: "T178 LearnerProgress·Answer·ExpLedger·BadgeAward 저장소(JdbcTemplate)"   # T176과 병렬
Task: "T201 LearningMigrationTest 나의 학습 네 테이블·LearningSeeds 정리"   # T175 뒤, T176과 병렬
Task: "T177 LevelTest·ExpLedgerTest·BadgeCatalogTest·SettlementTest"           # T176 뒤
Task: "T179 LearnerService" → "T180 LearnerController·LearnerAccess·SecurityConfig"
Task: "T181 LearnerApiIT" / "T182 ArchitectureTest 규칙"                         # 병렬

# 프런트엔드 — 계약 픽스처(T100) 기준으로 백엔드와 동시에
Task: "T185 learningMe 타입·API" → "T186 완료·위치 훅" / "T187 CompletionCard"   # T186·T187 병렬
Task: "T188 QuizPanel 로그인 모드" → "T189 글 화면 연결" → "T190 테스트"
```

---

## 2판 Implementation Strategy

### MVP First (US1 + US5의 서버 B07)

1. **B07** — 여기가 2판 스키마의 시작이다. T105 마이그레이션, T116 마이그레이션 검증, T161 배포 전 확인을 건너뛰지 않는다. T098 픽스처를 먼저 커밋해 B10이 출발할 수 있게 한다
2. **B10** — 글 화면이 V1·V2를 모두 그린다
3. **멈추고 검증**: 단계를 공개하지 않은 DB에서 1판 목록·글이 그대로인지(quickstart 6-1, 2~5 회귀), 테스트로 심은 CURRENT 판이 새 요소로 보이는지(9-1~9-3)
4. 이 시점에서 운영자는 V2 원고를 넣고 미리 볼 수 있지만(B07 API) 공개는 아직 할 수 없다. 원고 MR 작업이 이때부터 시작된다

### Incremental Delivery

1. B07 + B10 → 원고 작업 시작, 운영 화면은 그대로
2. B08 + B11 → 단계 공개와 커리큘럼(관리 화면 없이 API로 시험 DB에서 확인)
3. B14 → 운영자가 관리 화면으로 원고를 넣고 단계를 공개할 수 있다
4. B09 + B12 + B13 → 나의 학습. **첫 단계 공개는 여기까지 배포된 뒤**(plan "첫 단계 공개 조건")
5. B16 → 옛 목록 경로 정리(B11 배포 뒤)
6. B15 → 문서와 2판 검증 기록

### Parallel Team Strategy

백엔드 한 사람, 프런트엔드 한 사람이면:

1. 백엔드 B07 동안 프런트엔드는 T098 픽스처로 B10
2. 백엔드 B08 동안 프런트엔드는 B10 마무리 → B11(T099 픽스처)
3. 백엔드 B09 동안 프런트엔드는 B14(B07·B08 계약)
4. B09 병합 뒤 프런트엔드 B12 → B13, 백엔드는 B16·원고 검토 지원
5. B15는 둘이 함께

---

## 2판 Notes

- `[P]` = 다른 파일, 선행 없음. `‹B##›` = 브랜치·MR 단위. `[US#]` = 추적용 스토리
- 테스트가 먼저 실패하는 것을 확인한 뒤 구현한다. 태스크 하나 또는 논리적 묶음마다 커밋한다
- 피할 것: 판·이벤트·변경 기록·장부·답·배지를 고치는 저장소 메서드, 초안을 읽어 공개 응답을 만드는 경로(미리 보기 T159 제외), 학습 기록을 브라우저 저장소에 쓰는 코드, 배우기 밖 화면의 경험치·배지 알림, 조용한 재시도
- 1판 Notes의 규칙(모호한 태스크, 같은 파일 충돌, 스토리 독립성을 깨는 교차 의존 피하기)도 그대로 따른다

---

## Phase 27: Convergence

**Purpose**: B10 converge(2026-10-06)에서 찾은 빈자리 가운데 다른 묶음에 속하는 것. B10 MR에 있어야 했던 빈자리는 B10 브랜치(`feat(fe)/learning-article-v2`)에서 바로 채웠다. 아래 두 태스크는 B11 묶음(`feat(fe)/learning-curriculum`)에 담는다.

- [X] T202 [US6] (FE) `frontend/src/components/learning/article/ArticleEnd.tsx`의 비로그인 글 끝에 있는 '로그인하면 학습 기록이 남습니다.' 한 줄과 로그인 연결을 T140이 만드는 `frontend/src/components/learning/me/GuestSample.tsx`(견본 그림·한 줄·로그인 연결)로 바꾼다. 확인 문제 → 다음 글 → 견본 순서와, 로그인 상태를 다시 확인하는 동안 부품을 내리지 않고 숨기기만 하는 처리(`concealed`, research R33)를 그대로 둔다. `frontend/src/pages/LearningArticlePage.test.tsx`에 비로그인 글 끝의 견본(그림은 `aria-hidden`, 숫자·상태 모양 없음)을 더한다 per design-notes §3.5, 목업 '글 끝'의 `guestSample('end')`, FR-031 (missing) ‹B11›
- [X] T203 [US1] (FE) `frontend/src/pages/LearningArticlePage.tsx` 글 머리 경로의 단계 연결('n단계 · 이름', B10에서는 `/learn`으로 간다)을 커리큘럼 보기의 그 단계 자리로 잇고, 받은 `learningReturn`을 그대로 싣는다. 단계 자리를 가리키는 방식은 T139의 '○단계 보기' 연결과 같게 한다 per 목업 '글 끝'의 경로(`data-to="stage-n"`), FR-031 (partial) ‹B11›

## Phase 28: Convergence

**Purpose**: B09 converge(2026-10-07)에서 찾은 빈자리 가운데 다른 묶음에 속하는 것. B09 MR에 있어야 했던 빈자리는 B09 브랜치(`feat(be)/learning-records`)에서 바로 채웠다. 아래 태스크는 B13 묶음에 담는다.

- [ ] T204 [US6] (FE) 완료 카드의 그림 장면(T193의 레벨 표장·레벨 길·다음 배지 메달)이 쓸 값을 어디서 받을지 정하고 그대로 잇는다. 완료 응답([learning-me-api](contracts/learning-me-api.md) §4)에는 `gained.levelBefore`·`levelAfter`와 단계 진행만 있고, 경험치 합계·레벨 문턱(`milestones`)·보이는 가장 높은 레벨·다음 배지의 진행은 `GET /learning/me`(§1)에만 있다. 완료 응답을 받은 뒤 `GET /learning/me`를 한 번 더 불러 그리는 방법과, §4 응답에 레벨과 다음 배지 조각을 더하는 방법 가운데 하나를 고른다. 뒤쪽이면 서버 응답·`docs/specs/learning-api/examples/me-completion.json`·`LearnerApiIT`를 같은 MR에서 고친다. 앞쪽이면 그 요청이 실패해도 T187의 글자 정보는 그대로 두고 그림 자리만 비운다. 고른 방법을 계약 §4와 T187·T193에 적는다 per FR-030, T193 (missing) ‹B13›
