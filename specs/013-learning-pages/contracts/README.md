# Contracts: 배우기

**Plan**: [../plan.md](../plan.md) | **Data model**: [../data-model.md](../data-model.md) | **Date**: 2026-09-16 · 2판 2026-10-05

013이 소유하는 계약은 넷이다.

| 계약 | 소비자 | 문서 |
|---|---|---|
| 커리큘럼·글·용어 설명 공개 조회 | FRONT (013 배우기 화면, 013 도움말을 붙인 화면) | [learning-public-api.md](./learning-public-api.md) |
| 나의 학습 기록(로그인 사용자) **(2판)** | FRONT (013 배우기 화면, 회원정보 화면의 학습 초기화) | [learning-me-api.md](./learning-me-api.md) |
| 관리 권한 확인·초안 저장·게시·게시 취소·단계 공개 | FRONT (013 관리 화면) | [learning-admin-api.md](./learning-admin-api.md) |
| 용어 도움말 컴포넌트, 돌아가기, 002 예시 진입 | FRONT (002·003·005와 013 도움말을 붙인 화면) | [term-help-ui.md](./term-help-ui.md) |

## 공통 규칙

[backend/CLAUDE.md](../../../backend/CLAUDE.md)를 따른다.

- JSON 필드는 camelCase다.
- 응답 봉투는 `common/web`의 `ApiResponse`(`{ success, data, error }`)다. 아래 예시는 그 안의 `data` 또는 `error`다.
- 외부 경로는 `/api` 접두사가 붙고, Vite·nginx가 접두사를 떼어 백엔드에 넘긴다(014 plan). 이 문서는 백엔드 경로로 적는다.
- DB 대리키를 내보내지 않는다. 식별자는 `articleId`·`termId`·`stageKey`·`questionId`·`choiceId`·`badgeKey`·`entryKey` 문자열이다([data-model](../data-model.md) §2).
- 응답 예시는 계약 형식만 보이는 합성 값이다. 원고나 추천 예시가 아니다. 구현 묶음이 `docs/specs/learning-api/examples/`에 같은 내용을 픽스처로 두고 통합 테스트가 실제 응답과 대조한다(003의 `docs/specs/strategy-registry-api/examples/` 방식).
- **(2판) 배포 순서 호환**: 2판 묶음은 이미 있는 공개 응답에 필드를 더하기만 한다. LEGACY 판 글의 블록은 9월 판 모양 그대로 나간다. 그래서 백엔드 묶음이 먼저 병합되어도 9월 판 화면이 깨지지 않는다. 관리 API의 저장·게시도 9월 판 요청을 그대로 받는다. 예외는 관리 API의 글 만들기 하나다([learning-admin-api](./learning-admin-api.md) §3). 화면이 옮겨 간 뒤 지우는 옛 목록 경로는 [learning-public-api](./learning-public-api.md) §2다.

## 013이 쓰기만 하는 계약

아래는 013이 쓰는 계약이다. **이 폴더에서 다시 정의하지 않는다.** 바꿔야 할 일이 생기면 소유 spec에서 바꾼다.

| 계약 | 소유 | 013의 쓰임 |
|---|---|---|
| `GET /sectors`의 열린 대응표 | [001](../../001-etf-market-data/contracts/sector-api.md) | 예시 섹터 판정(게시 시·조회 시). 서버 안에서 `SectorService`를 포트로 부른다 |
| 배우기 예시 진입(navigation state `sectorSelectionExample`) | [002](../../002-sector-weight-selection/contracts/selection-handoff.md) §2 | '이 예시로 전략 진단하기'가 싣는 값. `topicId`에 `articleId`를 넣는다 |
| 로그인 세션·CSRF·`401 AUTHENTICATION_REQUIRED` | [014](../../014-user-auth/plan.md) | 관리 경로와 **(2판)** 나의 학습 경로의 인증. 상태 변경 요청은 `X-XSRF-TOKEN`을 싣는다 |
| `normalizeReturnTarget`, **(2판)** `useLoginReturn`, `useAuth().status` | 014 FE (`frontend/src/auth/`) | 돌아가기 경로 검사([term-help-ui](./term-help-ui.md) §3), 배우기에서 로그인했다 돌아오기, 기록 표시 여부([research](../research.md) R33·R34) |
| `users(id)` | 014 스키마 | **(2판)** 나의 학습 테이블의 외래키(`ON DELETE CASCADE`). 013은 `users`를 고치지 않는다 |
| 003 등록표의 `axes[].termId` | [003](../../003-strategy-settings/spec.md) | 설정 항목 도움말이 가리키는 용어. 예약 식별자는 [data-model](../data-model.md) §7-3 |

## 오류 코드

`common/exception/ErrorCode`의 "spec 013 배우기" 블록이다.

| 코드 | HTTP | 뜻 | 계약 |
|---|---|---|---|
| `LEARNING_ARTICLE_UNAVAILABLE` | 404 | 없거나 게시되지 않은 글 | 공개·나의 학습 |
| `LEARNING_TERM_NOT_READY` | 404 | 게시된 설명이 없는 용어 | 공개 |
| `LEARNING_ARTICLE_NOT_FOUND` | 404 | 관리 대상 글이 없다 | 관리 |
| `LEARNING_ARTICLE_ID_TAKEN` | 409 | 이미 쓰인 `articleId` | 관리 |
| `LEARNING_TERM_ID_TAKEN` | 409 | 다른 글이 쓰고 있는 `termId` | 관리 |
| `LEARNING_DRAFT_CHANGED` | 409 | 읽은 뒤 초안이 바뀌었다 | 관리 |
| `LEARNING_PUBLISH_CHECK_FAILED` | 422 | 게시 전 검사에 걸렸다 | 관리 |
| `LEARNING_NOT_RECORDABLE` **(2판)** | 409 | 기록하지 않는 글이다(이전 판, 첫 단계 공개 전 글) | 나의 학습 |
| `LEARNING_COMPLETION_REQUIRED` **(2판)** | 409 | 완료하지 않은 글의 확인 문제에 답했다 | 나의 학습 |
| `LEARNING_QUESTION_NOT_FOUND` **(2판)** | 404 | 공개 판에 없는 문제나 그 문제에 없는 보기다 | 나의 학습 |
| `LEARNING_RETRY_NOT_AVAILABLE` **(2판)** | 409 | 다시 풀기를 할 수 없는 글이다 | 나의 학습 |
| `LEARNING_STAGE_NOT_FOUND` **(2판)** | 404 | 없는 단계 | 관리 |
| `LEARNING_STAGE_NOT_RELEASED` **(2판)** | 409 | 공개 전 단계의 V2 글은 '단계 공개'로만 게시한다 | 관리 |
| `LEARNING_FORMAT_OUTDATED` **(2판)** | 409 | V1 초안은 공개 전 단계에서 이전 판 고치기로만 게시한다. LEGACY 판이 없는 글은 V1로 되돌릴 수 없다 | 관리 |
| `LEARNING_STAGE_ORDER` **(2판)** | 409 | 앞 단계가 공개되지 않았다 | 관리 |
| `LEARNING_STAGE_ALREADY_RELEASED` **(2판)** | 409 | 이미 공개한 단계다 | 관리 |
| `LEARNING_STAGE_EMPTY` **(2판)** | 409 | 단계에 글이 없다 | 관리 |
| `LEARNING_STAGE_CHANGED` **(2판)** | 409 | 단계의 글 목록이나 초안 번호가 요청과 다르다 | 관리 |
| `LEARNING_STAGE_CHECK_FAILED` **(2판)** | 422 | 단계의 글 가운데 검사에 걸린 글이 있다 | 관리 |
| `LEARNING_TERM_TRANSFER_INVALID` **(2판)** | 409 | `transferFrom`이 그 용어의 지금 소유 글이 아니다 | 관리 |

공개 조회의 두 404를 한 코드로 합치지 않는다. 화면이 '볼 수 없는 글'과 '설명 준비 중'을 코드로 가른다([research](../research.md) R10).
