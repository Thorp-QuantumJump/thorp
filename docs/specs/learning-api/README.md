# 배우기 계약 (Backend ↔ Frontend)

**원본은 [`specs/013-learning-pages/contracts/`](../../../specs/013-learning-pages/contracts/README.md)이다.**
이 문서는 화면과 서버가 함께 보는 요약이며, 필드의 뜻과 판정 규칙은 원본이 정한다.

- 공개 조회: [learning-public-api.md](../../../specs/013-learning-pages/contracts/learning-public-api.md)
- 관리: [learning-admin-api.md](../../../specs/013-learning-pages/contracts/learning-admin-api.md)
- 화면 부품(도움말·돌아가기·002 예시 진입): [term-help-ui.md](../../../specs/013-learning-pages/contracts/term-help-ui.md)

**예시 파일이 계약의 기준이다.** 백엔드 통합 테스트(`LearningPublicApiIT`, B02의 `LearningAdminApiIT`)가 이 파일을
읽어 실제 응답과 JSON 동치로 대조하므로, 계약과 구현이 어긋나면 빌드가 실패한다. 값은 계약 형식을 보이는
합성 값이며 원고나 추천 예시가 아니다.

응답은 다른 API와 같은 `ApiResponse` 껍데기(`{ success, data, error }`)를 쓴다. 아래 파일은 달리 적지 않으면 그 안의 `data`다.

## 공개 조회 (로그인 불필요)

| 요청 | 200 `data` | 실패 |
|---|---|---|
| `GET /learning/articles` | [`articles-list.json`](examples/articles-list.json). 게시된 글이 없으면 `articles: []` | 5xx는 빈 배열로 대신하지 않는다 |
| `GET /learning/articles/{articleId}` | [`article.json`](examples/article.json) | 404 `LEARNING_ARTICLE_UNAVAILABLE` (없음·초안·게시 취소·형식 오류를 가르지 않는다) |
| `GET /learning/terms/{termId}` | [`term.json`](examples/term.json) | 404 `LEARNING_TERM_NOT_READY` (초안뿐·게시 취소·없음·형식 오류를 가르지 않는다) |

- `blocks[]`는 `type`마다 키가 다르다. `heading`·`paragraph`·`hypothetical`은 `text`, `term`은 `termId`·`label`·`sentence`다. 해당하지 않는 키는 싣지 않는다.
- `example.unavailableReason`은 `available=true`면 명시적 `null`이다. `example.sectors[].name`은 대응표에 없는 코드면 `null`이다.
- `sources[].url`은 없으면 키를 싣지 않는다.
- `weightTenths`는 0.1%p 단위 정수이고 `1000`이 100%다(002 selection-handoff 계약과 같다).

## 관리 (B02, 관리 계정만)

| 파일 | 원본 절 |
|---|---|
| [`admin-articles-list.json`](examples/admin-articles-list.json) | 관리 계약 §2 `GET /admin/learning/articles` 200 `data` |
| [`admin-article.json`](examples/admin-article.json) | §4 `GET /admin/learning/articles/{articleId}` 200 `data` |
| [`publish-success.json`](examples/publish-success.json) | §6 `POST …/publish` 200 `data` |
| [`publish-check-failed.json`](examples/publish-check-failed.json) | §6 422 — **`error` 전체**다 |
| [`unpublish.json`](examples/unpublish.json) | §7 `POST …/unpublish` 200 `data` |
