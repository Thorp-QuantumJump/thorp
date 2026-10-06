# 배우기 계약 (Backend ↔ Frontend)

**원본은 [`specs/013-learning-pages/contracts/`](../../../specs/013-learning-pages/contracts/README.md)이다.**
이 문서는 화면과 서버가 함께 보는 요약이며, 필드의 뜻과 판정 규칙은 원본이 정한다.

- 공개 조회: [learning-public-api.md](../../../specs/013-learning-pages/contracts/learning-public-api.md)
- 관리: [learning-admin-api.md](../../../specs/013-learning-pages/contracts/learning-admin-api.md)
- 화면 부품(도움말·돌아가기·002 예시 진입): [term-help-ui.md](../../../specs/013-learning-pages/contracts/term-help-ui.md)

**예시 파일이 계약의 기준이다.** 백엔드 통합 테스트(`LearningPublicApiIT`, `LearningCurriculumIT`, `LearningAdminApiIT`, `LearningStageReleaseIT` 등)가 이 파일을
읽어 실제 응답과 대조하므로, 계약과 구현이 어긋나면 빌드가 실패한다. 대조 방식은 파일마다 다르고 아래 표에 적는다.
**JSON 동치**는 응답 전체가 파일과 같아야 한다. **키 구성**은 시각·대응표 버전처럼 실행마다 달라지는 값이 있어
객체의 키 집합(배열은 첫 원소)만 비교하고, 값은 테스트가 따로 단언한다. 9월 판 글 응답 `article.json`은 2판에서
필드가 더해졌으므로 **응답 가운데 이 파일에 있는 키만** 골라 JSON 동치로 비교한다(배포 순서 호환: 9월 판 필드의 모양과
값이 그대로인지 본다). 값은 계약 형식을 보이는 합성 값이며 원고나 추천 예시가 아니다. **계약 문서의 예시는 발췌일 수
있다**(값을 `...`로 줄이거나 배열 일부만 보인다). 픽스처는 줄인 자리를 모두 채운 전체 응답이다. 예시 파일만 고쳐도
테스트가 다시 돌도록 이 폴더는 `backend/build.gradle`의 테스트 입력으로 선언되어 있다.

응답은 다른 API와 같은 `ApiResponse` 껍데기(`{ success, data, error }`)를 쓴다. 아래 파일은 달리 적지 않으면 그 안의 `data`다.

## 공개 조회 (로그인 불필요)

| 요청 | 200 `data` | 실패 |
|---|---|---|
| `GET /learning/curriculum` | 첫 단계를 공개한 뒤: [`curriculum.json`](examples/curriculum.json)(1·2단계 공개), 공개하기 전: [`curriculum-pre-release.json`](examples/curriculum-pre-release.json). 게시된 글이 없어도 200 | 5xx는 빈 배열로 대신하지 않는다 |
| `GET /learning/articles` | [`articles-list.json`](examples/articles-list.json). 게시된 글이 없으면 `articles: []` | 5xx는 빈 배열로 대신하지 않는다 |
| `GET /learning/articles/{articleId}` | `edition`마다 하나: [`article-current.json`](examples/article-current.json)(`CURRENT`), [`article-previous.json`](examples/article-previous.json)(`PREVIOUS`), [`article-pre-release.json`](examples/article-pre-release.json)(`PRE_RELEASE`). [`article.json`](examples/article.json)은 9월 판 필드의 기준이다 | 404 `LEARNING_ARTICLE_UNAVAILABLE` (없음·초안·게시 취소·형식 오류를 가르지 않는다) |
| `GET /learning/terms/{termId}` | [`term.json`](examples/term.json) | 404 `LEARNING_TERM_NOT_READY` (초안뿐·게시 취소·없음·형식 오류를 가르지 않는다) |

| 파일 | `LearningPublicApiIT`·`LearningCurriculumIT` 대조 방식 |
|---|---|
| `curriculum-pre-release.json` | JSON 동치(실제 요청) |
| `curriculum.json` | JSON 동치. `notices`가 지금 시각에 따라 달라지므로 시계를 2026-11-20T00:00:00Z로 고정한 조회 결과와 비교한다. 실제 요청은 `notices` 밖의 값을 같은지 본다 |
| `articles-list.json`, `term.json` | JSON 동치 |
| `article.json` | 응답 가운데 이 파일에 있는 키만 JSON 동치(9월 판 필드) |
| `article-current.json`, `article-previous.json`, `article-pre-release.json` | JSON 동치 |

- `edition`이 `PRE_RELEASE`·`PREVIOUS`(LEGACY 판)인 글의 `blocks[]`는 9월 판 모양이다. `type`마다 키가 다르다. `heading`·`paragraph`·`hypothetical`은 `text`, `term`은 `termId`·`label`·`sentence`다. 해당하지 않는 키는 싣지 않는다.
- `CURRENT` 글의 `blocks[]`는 V2 블록과 글자 조각이다. 언급 조각은 `owned`·`aliases`를 싣는다. 이 글의 용어면 판에서, 다른 글의 용어면 게시 용어 색인에서 오고, 게시되지 않은 용어면 `aliases: []`다. 용어 블록은 `label`·`aliases`·`meaning`·`reading`·`caveat`을 싣고 `meaning`이 도움말 `sentence`와 같은 값이다.
- 2판 필드 `number`·`minDwellSeconds`·`next`·`stageArticleCount`는 값이 없으면 명시적 `null`이다. `example`은 예시가 없는 V2 글이면 명시적 `null`이다.
- `example.unavailableReason`은 `available=true`면 명시적 `null`이다. `example.sectors[].name`은 대응표에 없는 코드면 `null`이다.
- `example.missingSectorCodes`는 `SECTOR_MAP_NOT_READY`일 때도 빈 배열이다(판정하지 못했다). 안내 문장은 `unavailableReason`으로 가른다.
- `sources[].url`은 없으면 키를 싣지 않는다.
- `weightTenths`는 0.1%p 단위 정수이고 `1000`이 100%다(002 selection-handoff 계약과 같다).
- 커리큘럼의 `stages[].releasedAt`·`preReleaseArticles`·`notices[].articleTitle`·`changeLog[].articleTitle`은 값이 없으면 명시적 `null`이다. 공개되지 않은 단계는 `articles: []`이고, 그 단계의 새 글 제목·편수·날짜는 어디에도 싣지 않는다(SC-017).

## 관리 (관리 계정만)

| 파일 | 원본 절 | `LearningAdminApiIT`·`LearningAdminV2ApiIT` 대조 방식 |
|---|---|---|
| [`admin-article-v2.json`](examples/admin-article-v2.json) | §4 V2 초안 200 `data` | 키 구성. 초안의 값은 이 파일의 `draft`(문제·보기 식별자를 비운 것)를 저장 요청으로 보낸 뒤 응답 초안과 같은지 따로 본다 (`transferFrom` 값은 `LearningTermTransferIT`가 따로 단언) |
| [`admin-preview.json`](examples/admin-preview.json) | §8 `GET …/preview` 200 `data`. §3 글 응답 모양에 `preview: true`를 더하고 `number`·`next`·`stageArticleCount`·`publishedAt`은 `null`이다 | JSON 동치 (`admin-article-v2.json`의 초안을 저장한 뒤 부른다) |
| [`publish-check-failed-v2.json`](examples/publish-check-failed-v2.json) | §6 422 V2 게시 — **`error` 전체**다 | JSON 동치 |
| [`admin-articles-list.json`](examples/admin-articles-list.json) | 관리 계약 §2 `GET /admin/learning/articles` 200 `data`. 첫 글은 §13-3 배치의 초안이다 | 키 구성 |
| [`admin-article.json`](examples/admin-article.json) | §4 `GET /admin/learning/articles/{articleId}` 200 `data`(V1 초안 저장 200도 같은 모양. 만들기 201은 새 글이 V2라 `admin-article-v2.json` 모양이다) | 키 구성 |
| [`publish-success.json`](examples/publish-success.json) | §6 `POST …/publish` 200 `data` | 키 구성 (`publishedAt`·`sectorMapVersion`이 실행마다 다르다) |
| [`publish-check-failed.json`](examples/publish-check-failed.json) | §6 422 — **`error` 전체**다 | JSON 동치 |
| [`unpublish.json`](examples/unpublish.json) | §7 `POST …/unpublish` 200 `data` | 키 구성 (값은 `unpublishedRevisionNo` 등을 따로 단언) |
| [`admin-stages.json`](examples/admin-stages.json) | §9 `GET /admin/learning/stages` 200 `data`. 1단계의 `sectors-and-etf`는 LEGACY 판이 게시 중인 V2 초안, 4단계의 `backtest`는 초안을 5단계로 옮긴 공개 배치만 있는 글이다 | JSON 동치 (`LearningStageReleaseIT`) |
| [`stage-readiness.json`](examples/stage-readiness.json) | §10 `GET …/stages/{stageKey}/readiness` 200 `data`. 1단계가 공개된 뒤 2단계를 미리 검사한다 | JSON 동치 |
| [`stage-release.json`](examples/stage-release.json) | §11 `POST …/stages/{stageKey}/release` 200 `data` | `releasedAt` 밖의 키는 JSON 동치. `releasedAt`은 실행마다 다르므로 단계의 공개일·판의 게시 시각·변경 기록의 시각과 같은지 따로 본다 |
| [`stage-check-failed.json`](examples/stage-check-failed.json) | §11 422 — **`error` 전체**다. 걸린 글만 싣는다 | JSON 동치 |
