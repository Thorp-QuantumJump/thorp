# 계약: 배우기 관리 API

**Spec**: [spec.md](../spec.md) | **Data model**: [data-model.md](../data-model.md) | **Date**: 2026-09-16

서비스 운영자가 관리 화면에서 쓰는 계약이다(US5, FR-015~FR-018). 공통 규칙은 [README](./README.md)에 있다.

## 0. 인증·권한 (모든 엔드포인트)

| 요청 상태 | 응답 | 근거 |
|---|---|---|
| 로그인하지 않음·세션 만료 | `401 AUTHENTICATION_REQUIRED` | [research](../research.md) R6. 화면이 014 재로그인 안내로 넘긴다 |
| 로그인했으나 관리 권한 없음 | `404 RESOURCE_NOT_FOUND` | R6. 경로의 존재를 알리지 않는다 |
| 상태 변경 요청에 CSRF 토큰 없음·불일치 | `403 CSRF_TOKEN_INVALID` | 014 `SecurityConfig` |

- 경로는 모두 `/admin/learning`으로 시작한다. `SecurityConfig`가 이 경로를 `authenticated()`로 묶는다.
- 관리 권한 판정은 `LearningAdminPolicy` 한 곳이다(R5). 컨트롤러마다 판정을 다시 쓰지 않는다.
- 저장·게시·게시 취소는 `POST`·`PUT`이며 `X-XSRF-TOKEN` 헤더를 싣는다.

## 1. 관리 권한 확인

```
GET /admin/learning/access
```

### 응답 200

```json
{ "admin": true }
```

권한이 없으면 §0의 404다. **`admin: false`로 200을 돌려주지 않는다.** 화면은 200일 때만 관리 화면을 그린다.

## 2. 글 목록 (초안 포함)

```
GET /admin/learning/articles
```

### 응답 200

```json
{
  "articles": [
    {
      "articleId": "returns-and-losses",
      "title": "수익과 손실",
      "readingOrder": 8,
      "status": "PUBLISHED",
      "draftVersion": 5,
      "hasUnpublishedChanges": true,
      "needsUpdate": false,
      "lastPublication": {
        "action": "PUBLISH",
        "revisionNo": 3,
        "actorUserId": 12,
        "occurredAt": "2026-09-20T01:00:00Z"
      }
    }
  ]
}
```

| 필드 | 근거 |
|---|---|
| `hasUnpublishedChanges` | 공개 중인 판 이후 초안이 저장됐는지. 게시된 글을 고친 뒤 게시를 잊는 일을 막는다 |
| `needsUpdate` | 공개 중인 판의 예시 섹터가 지금 열린 대응표에 없다(spec Edge Cases, R8). 대응표를 읽지 못하면 `null` |
| `lastPublication` | FR-018. 게시·게시 취소 기록 중 마지막 것. 기록이 없으면 `null` |

`actorUserId`는 사용자 식별자다. 이메일·닉네임을 싣지 않는다.

## 3. 글 만들기

```
POST /admin/learning/articles
```

### 요청

```json
{ "articleId": "equal-weight-basics", "title": "똑같이 나누면 무엇이 같아지나", "readingOrder": 10 }
```

### 응답 201

§4의 응답과 같은 모양이다. 새 글은 `DRAFT`, `draftVersion: 0`이다.

### 실패

| 응답 | 조건 |
|---|---|
| `400 INVALID_INPUT` | `articleId` 형식·길이 위반, `readingOrder` 범위 밖 |
| `409 LEARNING_ARTICLE_ID_TAKEN` | 이미 쓰인 `articleId` |

## 4. 초안 읽기

```
GET /admin/learning/articles/{articleId}
```

### 응답 200

```json
{
  "articleId": "returns-and-losses",
  "status": "PUBLISHED",
  "draftVersion": 5,
  "draft": {
    "title": "수익과 손실",
    "question": "...",
    "readingOrder": 8,
    "blocks": [
      { "type": "paragraph", "text": "..." },
      { "type": "term", "termId": "max-drawdown" },
      { "type": "hypothetical", "text": "..." }
    ],
    "example": { "sectors": [ { "code": "SEMICONDUCTOR", "weightTenths": 500 }, { "code": "BATTERY", "weightTenths": 500 } ] },
    "sources": [],
    "terms": [
      { "termId": "max-drawdown", "label": "가장 크게 줄었던 폭", "sentence": "..." }
    ]
  },
  "publishedRevisionNo": 3
}
```

- 초안의 `term` 블록은 `termId`만 담는다. 문장은 `terms`에 있다([data-model](../data-model.md) §4).
- `publishedRevisionNo`는 공개 중인 판 번호다. 초안이면 `null`이다.

### 실패

`404 LEARNING_ARTICLE_NOT_FOUND` — 없는 글.

## 5. 초안 저장

```
PUT /admin/learning/articles/{articleId}
```

### 요청

```json
{
  "expectedDraftVersion": 5,
  "title": "...",
  "question": "...",
  "readingOrder": 8,
  "blocks": [ { "type": "paragraph", "text": "..." } ],
  "example": { "sectors": [ { "code": "SEMICONDUCTOR", "weightTenths": 500 } ] },
  "sources": [ { "label": "...", "url": "https://..." } ],
  "terms": [ { "termId": "max-drawdown", "label": "가장 크게 줄었던 폭", "sentence": "..." } ]
}
```

- **통째로 바꾼다.** 요청에 없는 용어는 이 글의 초안에서 빠진다. 빠진 용어는 다음 게시 때 공개에서도 빠진다.
- `example`은 `null`을 허용한다. 초안은 게시 전 검사를 받지 않는다.
- 저장은 공개 내용을 바꾸지 않는다([data-model](../data-model.md) §7).

### 응답 200

§4와 같은 모양이며 `draftVersion`이 1 오른다.

### 실패

| 응답 | 조건 |
|---|---|
| `400 INVALID_INPUT` | 필드 길이·개수 위반, 알 수 없는 블록 `type`, `termId` 형식, 섹터 코드 중복, `weightTenths` 범위 밖, `sources[].url`이 `http`·`https`가 아님([data-model](../data-model.md) §4·§5). `error.details.fields`에 위반 위치를 싣는다 |
| `404 LEARNING_ARTICLE_NOT_FOUND` | 없는 글 |
| `409 LEARNING_DRAFT_CHANGED` | `expectedDraftVersion`이 서버 값과 다르다(R12). `error.details.currentDraftVersion`을 싣는다 |
| `409 LEARNING_TERM_ID_TAKEN` | `terms[].termId`를 다른 글이 초안에 두었거나 **게시 중이다**(초안에서 뺐어도 그 글을 다시 게시하거나 게시 취소하기 전까지 게시 중이다, [data-model](../data-model.md) §2). `error.details.termId`·`ownerArticleId`를 싣는다. 게시(§6)도 같은 이유로 이 코드를 돌려줄 수 있다 |

## 6. 게시

```
POST /admin/learning/articles/{articleId}/publish
```

### 요청

```json
{ "expectedDraftVersion": 5 }
```

### 응답 200

```json
{
  "articleId": "returns-and-losses",
  "status": "PUBLISHED",
  "revisionNo": 4,
  "checkVersion": "learning-publish-checks-v1",
  "sectorMapVersion": "SEM-2026-09-10-001",
  "publishedAt": "2026-09-21T02:00:00Z"
}
```

게시가 끝나면 같은 트랜잭션 안에서 공개 조회(글·용어 설명)가 새 판을 본다([data-model](../data-model.md) §7).

### 응답 422 `LEARNING_PUBLISH_CHECK_FAILED`

**걸린 검사를 모두 싣는다.** 첫 항목에서 멈추지 않는다([research](../research.md) R7). 아무것도 바뀌지 않는다.

```json
{
  "code": "LEARNING_PUBLISH_CHECK_FAILED",
  "message": "게시 전 검사에 걸린 항목이 있어 게시하지 않았습니다.",
  "details": {
    "checkVersion": "learning-publish-checks-v1",
    "failures": [
      {
        "check": "EXAMPLE_WEIGHT_SUM",
        "location": { "part": "example" },
        "message": "예시 비중의 합계가 100%가 아닙니다. 지금 합계는 95.0%입니다."
      },
      {
        "check": "FORBIDDEN_EXPRESSION",
        "location": { "part": "block", "index": 2 },
        "matched": "추천 비중",
        "message": "추천으로 읽히는 표현이 있습니다."
      },
      {
        "check": "RESULT_NUMBER_OUTSIDE_HYPOTHETICAL",
        "location": { "part": "term", "termId": "max-drawdown" },
        "matched": "-30%",
        "message": "실제 결과로 읽히는 숫자는 가상의 계산 예 블록에만 쓸 수 있습니다."
      }
    ]
  }
}
```

| `location.part` | 추가 필드 | 가리키는 곳 |
|---|---|---|
| `title` · `question` · `example` | 없음 | 해당 필드 |
| `block` | `index` (0부터) | `blocks` 순번. 본문 전체를 가리킬 때(`REQUIRED_FIELDS`의 문단 없음)는 `index`가 없다 |
| `term` | `termId` | 용어 설명 |

`check` 값은 [research](../research.md) R7 표의 아홉 가지다. 화면은 `message`를 **그대로** 보이고 문장을 새로 짓지 않는다. `matched`는 걸린 표현·숫자이며 해당 검사일 때만 온다.

**대응표를 읽지 못하면** `EXAMPLE_SECTOR_IN_MAP` 항목으로 거절하고 `message`에 대응표를 확인할 수 없다는 사실을 적는다. 판정할 수 없는 것을 통과로 세지 않는다(원칙 VI).

### 그 밖의 실패

| 응답 | 조건 |
|---|---|
| `404 LEARNING_ARTICLE_NOT_FOUND` | 없는 글 |
| `409 LEARNING_DRAFT_CHANGED` | `expectedDraftVersion` 불일치. 운영자가 보지 않은 초안을 게시하지 않는다 |
| `409 LEARNING_TERM_ID_TAKEN` | 이 글의 `termId`를 다른 글이 게시 중이다(§5와 같은 규칙). `error.details.termId`·`ownerArticleId`를 싣는다 |

## 7. 게시 취소

```
POST /admin/learning/articles/{articleId}/unpublish
```

### 요청

본문이 없다.

### 응답 200

```json
{ "articleId": "returns-and-losses", "status": "DRAFT", "unpublishedRevisionNo": 4 }
```

- 글과 그 글의 용어 설명이 함께 공개에서 빠진다. 진단 화면 도움말은 그 용어에 대해 `404 LEARNING_TERM_NOT_READY`를 받는다(FR-017, [data-model](../data-model.md) §3-5).
- **이미 초안이면** 아무것도 바꾸지 않고 `unpublishedRevisionNo: null`로 200이다. 기록도 남기지 않는다.
- 삭제 엔드포인트는 없다(spec Out of Scope).

### 실패

`404 LEARNING_ARTICLE_NOT_FOUND` — 없는 글.
