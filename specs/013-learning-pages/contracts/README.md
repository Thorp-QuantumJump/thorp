# Contracts: 배우기

**Plan**: [../plan.md](../plan.md) | **Data model**: [../data-model.md](../data-model.md)

013이 소유하는 계약은 셋이다.

| 계약 | 소비자 | 문서 |
|---|---|---|
| 글 목록·글·용어 설명 공개 조회 | FRONT (013 배우기 화면, 002·003·005 도움말) | [learning-public-api.md](./learning-public-api.md) |
| 관리 권한 확인·초안 저장·게시·게시 취소 | FRONT (013 관리 화면) | [learning-admin-api.md](./learning-admin-api.md) |
| 용어 도움말 컴포넌트와 '하던 진단으로 돌아가기' | FRONT (002·003·005 화면) | [term-help-ui.md](./term-help-ui.md) |

## 공통 규칙

[backend/CLAUDE.md](../../../backend/CLAUDE.md)를 따른다.

- JSON 필드는 camelCase다.
- 응답 봉투는 `common/web`의 `ApiResponse`(`{ success, data, error }`)다. 아래 예시는 그 안의 `data` 또는 `error`다.
- 외부 경로는 `/api` 접두사가 붙고, Vite·nginx가 접두사를 떼어 백엔드에 넘긴다(014 plan). 이 문서는 백엔드 경로로 적는다.
- DB 대리키를 내보내지 않는다. 식별자는 `articleId`·`termId` 문자열이다([data-model](../data-model.md) §2).
- 응답 예시는 계약 형식만 보이는 합성 값이다. 원고나 추천 예시가 아니다. 구현 묶음 B01·B02가 `docs/specs/learning-api/examples/`에 같은 내용을 픽스처로 두고 통합 테스트가 실제 응답과 대조한다(003의 `docs/specs/strategy-registry-api/examples/` 방식).

## 013이 쓰기만 하는 계약

아래는 013이 쓰는 계약이다. **이 폴더에서 다시 정의하지 않는다.** 바꿔야 할 일이 생기면 소유 spec에서 바꾼다.

| 계약 | 소유 | 013의 쓰임 |
|---|---|---|
| `GET /sectors`의 열린 대응표 | [001](../../001-etf-market-data/contracts/sector-api.md) | 예시 섹터 판정(게시 시·조회 시). 서버 안에서 `SectorService`를 포트로 부른다 |
| 배우기 예시 진입(navigation state `sectorSelectionExample`) | [002](../../002-sector-weight-selection/contracts/selection-handoff.md) §2 | '이 예시로 전략 진단하기'가 싣는 값. `topicId`에 `articleId`를 넣는다 |
| 로그인 세션·CSRF·`401 AUTHENTICATION_REQUIRED` | [014](../../014-user-auth/plan.md) | 관리 경로의 인증. 상태 변경 요청은 `X-XSRF-TOKEN`을 싣는다 |
| `normalizeReturnTarget` | 014 FE (`frontend/src/auth/returnTarget.ts`) | 돌아가기 경로 검사([term-help-ui](./term-help-ui.md) §3) |

## 새 오류 코드

`common/exception/ErrorCode`에 "spec 013 배우기" 블록으로 더한다.

| 코드 | HTTP | 뜻 | 계약 |
|---|---|---|---|
| `LEARNING_ARTICLE_UNAVAILABLE` | 404 | 없거나 게시되지 않은 글 | 공개 |
| `LEARNING_TERM_NOT_READY` | 404 | 게시된 설명이 없는 용어 | 공개 |
| `LEARNING_ARTICLE_NOT_FOUND` | 404 | 관리 대상 글이 없다 | 관리 |
| `LEARNING_ARTICLE_ID_TAKEN` | 409 | 이미 쓰인 `articleId` | 관리 |
| `LEARNING_TERM_ID_TAKEN` | 409 | 다른 글이 쓰고 있는 `termId` | 관리 |
| `LEARNING_DRAFT_CHANGED` | 409 | 읽은 뒤 초안이 바뀌었다 | 관리 |
| `LEARNING_PUBLISH_CHECK_FAILED` | 422 | 게시 전 검사에 걸렸다 | 관리 |

공개 조회의 두 404를 한 코드로 합치지 않는다. 화면이 '볼 수 없는 글'과 '설명 준비 중'을 코드로 가른다([research](../research.md) R10).
