# 계약: 배우기 관리 API

**Spec**: [spec.md](../spec.md) | **Data model**: [data-model.md](../data-model.md) | **Date**: 2026-09-16 · 2판 2026-10-05

서비스 운영자가 관리 화면에서 쓰는 계약이다(US5, FR-015~FR-018, **(2판)** FR-031). 공통 규칙은 [README](./README.md)에 있다. 관리 API는 나의 학습 테이블을 읽지 않는다(FR-020).

## 0. 인증·권한 (모든 엔드포인트)

| 요청 상태 | 응답 | 근거 |
|---|---|---|
| 로그인하지 않음·세션 만료 | `401 AUTHENTICATION_REQUIRED` | [research](../research.md) R6. 화면이 014 재로그인 안내로 넘긴다 |
| 로그인했으나 관리 권한 없음 | `404 RESOURCE_NOT_FOUND` | R6. 경로의 존재를 알리지 않는다 |
| 상태 변경 요청에 CSRF 토큰 없음·불일치 | `403 CSRF_TOKEN_INVALID` | 014 `SecurityConfig` |

- 경로는 모두 `/admin/learning`으로 시작한다. `SecurityConfig`가 이 경로를 `authenticated()`로 묶는다.
- 관리 권한 판정은 `LearningAdminPolicy` 한 곳이다(R5). 컨트롤러마다 판정을 다시 쓰지 않는다.
- 저장·게시·게시 취소·단계 공개는 `POST`·`PUT`이며 `X-XSRF-TOKEN` 헤더를 싣는다.

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
      },
      "format": "V1",
      "stage": { "stageKey": "returns-and-risk", "number": 2, "released": false },
      "stagePosition": 1,
      "publishedEdition": "LEGACY",
      "needsReview": null,
      "pendingTransfers": 0
    }
  ]
}
```

글은 초안 단계 번호, `stagePosition`, `articleId` 순서다.

| 필드 | 근거 |
|---|---|
| `hasUnpublishedChanges` | 공개 중인 판 이후 초안이 저장됐는지. 게시된 글을 고친 뒤 게시를 잊는 일을 막는다 |
| `needsUpdate` | 공개 중인 판의 예시 섹터가 지금 열린 대응표에 없다(spec Edge Cases, R8). 게시되지 않은 글은 `false`, 공개 중인 판에 예시가 없거나 대응표를 읽지 못하면 `null` |
| `lastPublication` | FR-018. 게시·게시 취소 기록 중 마지막 것. 기록이 없으면 `null` |
| `format` **(2판)** | 초안 형식 `V1`·`V2`(R16) |
| `stage`·`stagePosition` **(2판)** | 초안 배치. 공개 배치와 다르면 다시 게시해야 공개된다(FR-023) |
| `publishedEdition` **(2판)** | 공개 중인 판의 `LEGACY`·`CURRENT`. 게시되지 않았으면 `null` |
| `needsReview` **(2판)** | 공개 중인 판의 제도 안내 가운데 기준 시점이 설정 기간(기본 180일)보다 오래된 것이 있다. 제도 안내가 없거나 게시되지 않았으면 `null`(spec Edge Cases, R37) |
| `pendingTransfers` **(2판)** | 이 글의 초안에서 `transferFrom`이 있는 용어 수(R24) |

`actorUserId`는 사용자 식별자다. 이메일·닉네임을 싣지 않는다.

## 3. 글 만들기

```
POST /admin/learning/articles
```

### 요청

```json
{ "articleId": "etf-tax-basics", "title": "ETF를 팔면 세금은 어떻게 될까?", "stageKey": "etf-market-basics", "stagePosition": 5 }
```

### 응답 201

§4의 응답과 같은 모양이다. 새 글은 `DRAFT`, `draftVersion: 0`, **(2판)** `format: "V2"`다. `readingOrder`는 단계 번호 × 100 + `stagePosition`(999를 넘으면 999)으로 정한다(V2 글에서는 쓰지 않는다, [data-model](../data-model.md) §13-3).

### 실패

| 응답 | 조건 |
|---|---|
| `400 INVALID_INPUT` | `articleId` 형식·길이 위반, `stageKey`·`stagePosition` 누락이나 범위 밖 |
| `404 LEARNING_STAGE_NOT_FOUND` **(2판)** | 없는 `stageKey` |
| `409 LEARNING_ARTICLE_ID_TAKEN` | 이미 쓰인 `articleId` |

**(2판) 호환 예외**: 9월 판 요청(`readingOrder`, `stageKey` 없음)은 400이다. 새 글은 모두 단계에 속해야 하기 때문이다. 27편의 자리는 마이그레이션이 만들므로([data-model](../data-model.md) §13-3) B14 관리 화면이 병합되기 전에 글을 만들 일은 없다.

## 4. 초안 읽기

```
GET /admin/learning/articles/{articleId}
```

### 응답 200 — V1 초안 (9월 판 모양에 필드를 더함)

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
    ],
    "format": "V1",
    "stageKey": "returns-and-risk",
    "stagePosition": 1,
    "quiz": []
  },
  "publishedRevisionNo": 3,
  "publishedEdition": "LEGACY",
  "hasLegacyRevision": true
}
```

### 응답 200 — V2 초안 **(2판)**

```json
{
  "articleId": "volatility-is-not-loss",
  "status": "DRAFT",
  "draftVersion": 4,
  "draft": {
    "title": "변동성은 손실일까?",
    "question": "...",
    "readingOrder": 204,
    "format": "V2",
    "stageKey": "returns-and-risk",
    "stagePosition": 4,
    "blocks": [
      { "type": "keySummary", "lines": [ [ { "text": "..." } ], [ { "text": "..." } ], [ { "text": "..." } ] ] },
      { "type": "paragraph", "content": [ { "text": "..." }, { "term": "max-drawdown", "text": "가장 크게 줄었던 폭" } ] },
      { "type": "term", "termId": "volatility" }
    ],
    "example": null,
    "sources": [],
    "terms": [
      { "termId": "volatility", "label": "변동성", "aliases": ["volatility"], "meaning": "...", "reading": "...", "caveat": "...", "transferFrom": "inverse-volatility" }
    ],
    "quiz": [
      {
        "questionId": "q1",
        "prompt": "...",
        "choices": [ { "choiceId": "c2", "text": "...", "correct": false }, { "choiceId": "c3", "text": "...", "correct": true } ],
        "explanation": "..."
      }
    ]
  },
  "publishedRevisionNo": null,
  "publishedEdition": null,
  "hasLegacyRevision": false
}
```

- 초안의 `term` 블록은 `termId`만 담는다. 문장은 `terms`에 있다([data-model](../data-model.md) §5).
- 초안의 언급 조각에는 `owned`·`aliases`가 없다. 그것은 공개 응답과 미리 보기(§8)에서 서버가 채운다.
- `publishedRevisionNo`는 공개 중인 판 번호다. 초안이면 `null`이다.
- **(2판)** `hasLegacyRevision`은 이 글에 9월 판(`LEGACY`) 판이 한 번이라도 있었는지다. 게시를 취소해도 `true`로 남는다. V2 초안을 V1로 되돌리는 저장(§5)은 이것이 `true`일 때만 받으므로 관리 화면은 이 값으로 되돌리기를 보인다.

### 실패

`404 LEARNING_ARTICLE_NOT_FOUND` — 없는 글.

## 5. 초안 저장

```
PUT /admin/learning/articles/{articleId}
```

### 요청

```json
{
  "expectedDraftVersion": 4,
  "title": "...",
  "question": "...",
  "readingOrder": 204,
  "blocks": [ "§4의 형식별 블록" ],
  "example": null,
  "sources": [],
  "terms": [ "§4의 형식별 용어" ],
  "format": "V2",
  "stageKey": "returns-and-risk",
  "stagePosition": 4,
  "quiz": [ "§4의 확인 문제. 새 문제·보기는 questionId·choiceId를 null로" ]
}
```

- **통째로 바꾼다.** 요청에 없는 용어는 이 글의 초안에서 빠진다. 빠진 용어는 다음 게시 때 공개에서도 빠진다.
- **(2판)** `format`·`stageKey`·`stagePosition`·`quiz`는 선택이다. 없으면 지금 초안의 값을 그대로 둔다(9월 판 화면 호환). `format`을 바꾸면 요청 전체가 새 형식의 모양이어야 한다. V2에서 V1로 되돌리는 저장은 그 글에 LEGACY 판이 있을 때만 받는다(R16).
- **(2판)** 형식별 모양 검사([data-model](../data-model.md) §5·§6·§7, R16): V1 초안에 V2 블록·글자 조각·다른 이름·세 문장·확인 문제가 있거나, V2 초안에 `sentence`나 `sources`가 있으면 400이다.
- **(2판)** 새 문제·보기에 서버가 식별자를 준다. 응답의 `draft.quiz`에 채워져 돌아온다([data-model](../data-model.md) §6-2). 초안에 없는 식별자를 지어 보내면 400이다.
- `example`은 `null`을 허용한다. 초안은 게시 전 검사를 받지 않는다.
- 저장은 공개 내용을 바꾸지 않는다([data-model](../data-model.md) §10-1).

### 응답 200

§4와 같은 모양이며 `draftVersion`이 1 오른다.

### 실패

| 응답 | 조건 |
|---|---|
| `400 INVALID_INPUT` | 필드 길이·개수 위반, 알 수 없는 블록 `type`·조각 키, `termId` 형식, 섹터 코드 중복, `weightTenths` 범위 밖, `url`이 `http`·`https`가 아님, **(2판)** 형식 모양 위반(V1 용어의 `transferFrom` 포함), `articleId` 형식이 아닌 `transferFrom`, 지어 낸 문제·보기 식별자, 식별자를 모두 쓴 글의 새 문제·보기([data-model](../data-model.md) §6-2). `error.details.fields`에 위반 위치를 싣는다 |
| `404 LEARNING_ARTICLE_NOT_FOUND` | 없는 글 |
| `404 LEARNING_STAGE_NOT_FOUND` **(2판)** | 없는 `stageKey` |
| `409 LEARNING_DRAFT_CHANGED` | `expectedDraftVersion`이 서버 값과 다르다(R12). `error.details.currentDraftVersion`을 싣는다 |
| `409 LEARNING_TERM_ID_TAKEN` | `terms[].termId`를 다른 글이 초안에 두었거나 **게시 중이고**, 그 용어에 `transferFrom`이 없다([data-model](../data-model.md) §2). `error.details.termId`·`ownerArticleId`를 싣는다. 화면은 이 값으로 '이 글로 넘겨받기'를 제안할 수 있다 |
| `409 LEARNING_TERM_TRANSFER_INVALID` **(2판)** | `transferFrom`이 그 용어의 지금 소유 글이 아니거나(자기 글 포함), 다른 글이 이미 그 용어를 넘겨받는 중이다. `error.details.termId`·`ownerArticleId`를 싣는다. 지금 소유 글이 없으면(옛 소유 글이 그 용어를 초안에서 빼고 게시 취소함) `ownerArticleId` 키가 없다. 화면은 이때 `transferFrom`을 비우라고 안내할 수 있다 |
| `409 LEARNING_FORMAT_OUTDATED` **(2판)** | LEGACY 판이 없는 글을 V1로 되돌리려 했다 |

## 6. 글마다 게시

```
POST /admin/learning/articles/{articleId}/publish
```

### 요청

```json
{ "expectedDraftVersion": 5 }
```

**(2판) 게시 방식**: 서버가 초안 형식과 초안 단계의 공개 여부로 정한다([data-model](../data-model.md) §10-1, R16).

| 초안 | 결과 |
|---|---|
| V2, 초안 단계 공개됨 | v2 검사 → `CURRENT` 판. 공개된 단계에 처음 게시되면 `ARTICLE_ADDED` 변경 기록 |
| V2, 초안 단계 공개 전 | `409 LEARNING_STAGE_NOT_RELEASED` — §11 '단계 공개'로만 게시한다 |
| V1, 초안 단계 공개 전 | v1 검사 → `LEGACY` 판(이전 판 고치기) |
| V1, 초안 단계 공개됨 | `409 LEARNING_FORMAT_OUTDATED` |

### 응답 200

```json
{
  "articleId": "returns-and-losses",
  "status": "PUBLISHED",
  "revisionNo": 4,
  "checkVersion": "learning-publish-checks-v1",
  "sectorMapVersion": "SEM-2026-09-10-001",
  "publishedAt": "2026-09-21T02:00:00Z",
  "edition": "LEGACY",
  "renamedQuestions": [],
  "transferredTerms": []
}
```

| 필드 | 근거 |
|---|---|
| `edition` **(2판)** | 새 판의 `LEGACY`·`CURRENT` |
| `renamedQuestions` **(2판)** | 같은 문제 판정으로 새 식별자를 받은 문제 `[{ "from": "q2", "to": "q7" }]`([data-model](../data-model.md) §6-3). 운영자에게 "이 문제는 이미 답한 사용자에게도 새 문제로 보입니다"를 알린다 |
| `transferredTerms` **(2판)** | 넘겨받은 용어 `[{ "termId": "volatility", "fromArticleId": "inverse-volatility", "toArticleId": "volatility-is-not-loss" }]`. §11과 같은 모양이며 `toArticleId`는 게시한 이 글이다 |
| `sectorMapVersion` | 예시가 없는 V2 글은 `NOT_CHECKED`([data-model](../data-model.md) §3-4) |

게시가 끝나면 같은 트랜잭션 안에서 공개 조회(글·용어 설명·커리큘럼)가 새 판을 본다.

**(2판) 게시는 초안도 바꾼다.** `renamedQuestions`의 문제는 초안에서도 새 식별자가 되고, `transferredTerms`의 용어는 이 글 초안의 `transferFrom`이 비워진다. `draftVersion`은 그대로다([data-model](../data-model.md) §6-3·§7-2). 그래서 게시 전에 읽은 초안을 그대로 저장하면 옛 식별자는 400 `INVALID_INPUT`, 옛 `transferFrom`은 409 `LEARNING_TERM_TRANSFER_INVALID`다. 관리 화면은 게시에 성공하면 초안(§4)을 다시 읽고, 이 탭에 보관한 이 글의 편집 내용은 두 필드대로 고친다.

넘겨준 글(`transferredTerms[].fromArticleId`)의 초안도 바뀐다. 그 용어가 초안에서 빠지고 초안 번호가 1 오른다(용어를 여럿 넘겨도 한 번). 판과 초안의 다른 부분은 그대로다([data-model](../data-model.md) §7-2). 그 글을 열어 둔 편집 화면은 다음 저장에서 409 `LEARNING_DRAFT_CHANGED`를 받고 초안을 다시 읽는다.

### 응답 422 `LEARNING_PUBLISH_CHECK_FAILED`

**걸린 검사를 모두 싣는다.** 첫 항목에서 멈추지 않는다([research](../research.md) R7·R19). 아무것도 바뀌지 않는다.

```json
{
  "code": "LEARNING_PUBLISH_CHECK_FAILED",
  "message": "게시 전 검사에 걸린 항목이 있어 게시하지 않았습니다.",
  "details": {
    "checkVersion": "learning-publish-checks-v2",
    "failures": [
      {
        "check": "READING_TIME",
        "location": { "part": "block" },
        "message": "예상 읽기 시간이 3분입니다(본문 1,520자). 4~7분이어야 합니다."
      },
      {
        "check": "QUIZ",
        "location": { "part": "quiz", "questionId": "q2" },
        "message": "정답 보기가 하나가 아닙니다."
      },
      {
        "check": "RESULT_NUMBER_OUTSIDE_HYPOTHETICAL",
        "location": { "part": "block", "index": 4, "field": "content" },
        "matched": "-30%",
        "message": "실제 결과로 읽히는 숫자는 가상의 예 요소나 '(예: …)' 안에만 쓸 수 있습니다."
      }
    ]
  }
}
```

`check` 값은 V1 게시면 [data-model](../data-model.md) §8-1의 아홉 가지, V2 게시면 §8-2의 열일곱 가지다. 위치는 §8-4다. 화면은 `message`를 **그대로** 보이고 문장을 새로 짓지 않는다. `matched`는 걸린 표현·숫자이며 해당 검사일 때만 온다.

**대응표를 읽지 못하면** 예시가 있는 글은 `EXAMPLE_SECTOR_IN_MAP` 항목으로 거절하고 `message`에 대응표를 확인할 수 없다는 사실을 적는다. 판정할 수 없는 것을 통과로 세지 않는다(원칙 VI).

### 그 밖의 실패

| 응답 | 조건 |
|---|---|
| `404 LEARNING_ARTICLE_NOT_FOUND` | 없는 글 |
| `409 LEARNING_DRAFT_CHANGED` | `expectedDraftVersion` 불일치. 운영자가 보지 않은 초안을 게시하지 않는다 |
| `409 LEARNING_TERM_ID_TAKEN` | 이 글의 `termId`를 다른 글이 게시 중이고 `transferFrom`이 없다 |
| `409 LEARNING_TERM_TRANSFER_INVALID` **(2판)** | `transferFrom`이 지금 소유 글과 다르다(그사이 소유가 바뀌었다). `error.details`는 §5와 같다. 아무것도 바꾸지 않는다 |
| `409 LEARNING_STAGE_NOT_RELEASED` · `409 LEARNING_FORMAT_OUTDATED` **(2판)** | 위 게시 방식 표 |
| `400 INVALID_INPUT` **(2판)** | 정답이 바뀐 문제에 줄 새 식별자가 남지 않았다([data-model](../data-model.md) §6-2). `error.details.fields`에 `quiz`를 싣고 아무것도 바꾸지 않는다 |

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

- 글과 그 글의 용어 설명이 함께 공개에서 빠진다. 도움말은 그 용어에 대해 `404 LEARNING_TERM_NOT_READY`를 받는다(FR-017, [data-model](../data-model.md) §3-6).
- **(2판)** 공개 배치도 비운다. 공개된 단계의 글이면 그 단계의 공개된 글 수가 줄고, 0편이 되면 커리큘럼이 그 단계를 `REPREPARING`으로 보인다(FR-031). 사용자 기록은 지우지 않는다(FR-020). 변경 기록에 올리지 않는다.
- **이미 초안이면** 아무것도 바꾸지 않고 `unpublishedRevisionNo: null`로 200이다. 기록도 남기지 않는다.
- 삭제 엔드포인트는 없다(spec Out of Scope).

### 실패

`404 LEARNING_ARTICLE_NOT_FOUND` — 없는 글.

## 8. 미리 보기 **(2판)**

```
GET /admin/learning/articles/{articleId}/preview
```

지금 초안을 독자 화면과 똑같이 그리기 위한 응답이다(FR-016). 모양은 [공개 계약](./learning-public-api.md) §3의 글 응답과 같다. 화면은 같은 글 부품으로 그리고, 학습 기록·완료 카드 없이 확인 문제만 비로그인 모드로 보인다.

- `edition`은 이 초안을 지금 게시하면 될 값(`V2` → `CURRENT`, `V1` → 첫 단계 공개 전이면 `PRE_RELEASE`, 뒤면 `PREVIOUS`)이다. `preview: true`가 더 붙는다.
- `number`·`next`·`stageArticleCount`·`publishedAt`은 `null`이다. 게시 전이라 정해지지 않는다.
- `readingMinutes`·`minDwellSeconds`는 지금 규칙 파일로 계산한다. V1 초안의 `minDwellSeconds`는 `null`이다. 예시의 `available`은 지금 대응표로 판정한다.
- 정답 보기가 정확히 하나가 아닌 문제는 `correctChoiceId: null`이다. 게시 전 검사 `QUIZ`가 막는 상태라 공개 응답에는 나오지 않는다.
- 예시 없는 V1 초안은 9월 판 모양이어도 `example: null`이다.
- 언급 조각의 `aliases`는 그 용어가 이 초안의 용어면 초안에서, 다른 글의 용어면 게시 용어 색인에서 온다.
- 게시 전 검사는 하지 않는다. 검사 결과는 게시(§6)나 단계 미리 검사(§10)로 본다.

### 실패

`404 LEARNING_ARTICLE_NOT_FOUND` — 없는 글.

## 9. 단계 목록 **(2판)**

```
GET /admin/learning/stages
```

### 응답 200

```json
{
  "stages": [
    {
      "stageKey": "etf-market-basics",
      "number": 1,
      "name": "ETF와 국내 시장 기초",
      "levelLabel": "입문",
      "summary": "...",
      "releasedAt": null,
      "releasedByUserId": null,
      "releasable": true,
      "articles": [
        { "articleId": "sectors-and-etf", "title": "섹터와 ETF", "format": "V2", "status": "PUBLISHED", "publishedEdition": "LEGACY", "draftVersion": 9, "stagePosition": 1 },
        { "articleId": "etf-tracking-difference", "title": "...", "format": "V2", "status": "DRAFT", "publishedEdition": null, "draftVersion": 3, "stagePosition": 2 }
      ],
      "placementOnlyArticles": []
    }
  ]
}
```

| 필드 | 뜻 |
|---|---|
| `releasable` | 공개 전이고 앞 단계가 공개되었다(1단계는 앞 단계 조건 없음). 검사 통과 여부는 §10 |
| `articles` | 초안 단계가 이 단계인 글. 단계 공개에서 게시할 글이다 |
| `placementOnlyArticles` | 공개 배치만 이 단계인 글(초안을 다른 단계로 옮겼지만 아직 다시 게시하지 않음). 공개 집합에 들어가며, 단계 공개에서 `PLACEMENT` 항목으로 걸린다([data-model](../data-model.md) §10-3) |
| `articles[].stagePosition` · `placementOnlyArticles[].stagePosition` | 이 단계 안 순서다. `articles`는 초안 배치의 값, `placementOnlyArticles`는 공개 배치의 값이다. 두 목록 모두 이 값, `articleId` 순서다 |

## 10. 단계 미리 검사 **(2판)**

```
GET /admin/learning/stages/{stageKey}/readiness
```

단계 공개(§11)와 같은 판정을 하되 **아무것도 쓰지 않는다**. 단계 공개의 422 항목도 이 모양이다. 되돌릴 수 없는 공개 전에 확인하는 용도다(R23).

### 응답 200

```json
{
  "stageKey": "returns-and-risk",
  "ready": false,
  "blockers": [],
  "articles": [
    { "articleId": "returns-and-losses", "draftVersion": 9, "failures": [] },
    {
      "articleId": "recovery-period",
      "draftVersion": 4,
      "failures": [
        { "check": "KEY_SUMMARY", "location": { "part": "block", "index": 0 }, "message": "글 맨 위에 세 줄 핵심 요약이 있어야 합니다." }
      ]
    }
  ],
  "checkVersion": "learning-publish-checks-v2"
}
```

| 필드 | 뜻 |
|---|---|
| `blockers` | 글 검사와 별개로 공개를 막는 것: `ALREADY_RELEASED`, `ORDER`(앞 단계 공개 전), `EMPTY`(글 없음) |
| `articles` | 공개 집합의 글 전부(§9의 `articles`와 `placementOnlyArticles`) |
| `articles[].failures` | v2 검사([data-model](../data-model.md) §8-2)에 더해 `FORMAT`(V1 초안), `PLACEMENT`(초안을 다른 단계로 옮긴 글. 옮긴 단계로 다시 게시하거나 게시 취소한 뒤 공개한다), `TERM_TRANSFER`(넘겨받기 소유 불일치, 또는 같은 공개의 다른 글이 넘겨받을 용어를 이 글이 아직 초안에 둠. 이 글의 초안에서 그 용어와 용어 블록을 빼야 한다), `TERM_ID_TAKEN`(다른 글이 게시 중인 용어를 넘겨받기 없이 씀) 항목이 올 수 있다. 순서는 `FORMAT`·`PLACEMENT`, 용어 소유 항목(초안 용어 순서), 그다음 v2 검사다. `FORMAT`·`PLACEMENT`의 위치는 `{ "part": "article" }`, 용어 소유 항목의 위치는 `{ "part": "term", "termId": … }`다([data-model](../data-model.md) §8-4). V1 초안은 v2 검사를 받지 않으므로 앞의 두 갈래(`FORMAT`과 해당하면 `PLACEMENT`, 용어 소유 항목)만 싣는다 |
| `articles[].draftVersion` | 화면이 §11 요청에 그대로 싣는다 |
| `ready` | `blockers`와 모든 `failures`가 비었다 |

### 실패

`404 LEARNING_STAGE_NOT_FOUND`.

## 11. 단계 공개 **(2판)**

```
POST /admin/learning/stages/{stageKey}/release
```

### 요청

```json
{
  "articles": [
    { "articleId": "returns-and-losses", "expectedDraftVersion": 9 },
    { "articleId": "same-return-different-feel", "expectedDraftVersion": 5 },
    { "articleId": "recovery-period", "expectedDraftVersion": 6 },
    { "articleId": "volatility-is-not-loss", "expectedDraftVersion": 7 }
  ]
}
```

화면은 §10을 보이고 운영자의 확인(되돌릴 수 없다는 안내)을 받은 뒤 보낸다.

### 응답 200

```json
{
  "stageKey": "returns-and-risk",
  "releasedAt": "2026-11-19T01:00:00Z",
  "published": [
    { "articleId": "returns-and-losses", "revisionNo": 4, "renamedQuestions": [] },
    { "articleId": "volatility-is-not-loss", "revisionNo": 1, "renamedQuestions": [] }
  ],
  "transferredTerms": [ { "termId": "volatility", "fromArticleId": "inverse-volatility", "toArticleId": "volatility-is-not-loss" } ],
  "logEntryKey": "stage:returns-and-risk",
  "checkVersion": "learning-publish-checks-v2"
}
```

한 트랜잭션에서 모든 글을 게시하고 단계 공개일·변경 기록을 쓴다([data-model](../data-model.md) §10-3). 공개일은 이 요청의 시각 하나다.

글마다 §6처럼 초안의 문제 식별자와 `transferFrom`이 바뀔 수 있고 초안 번호는 그대로다. 관리 화면은 `published[].renamedQuestions`와 `transferredTerms`(`toArticleId`로 글을 가른다)로 이 탭에 보관한 그 글들의 편집 내용을 고친다. 넘겨준 글(`fromArticleId`)은 §6처럼 그 용어가 초안에서 빠지고 초안 번호가 1 오른다. 한 공개에서 여러 글에 용어를 넘겨도 한 번만 오른다. 넘겨준 글이 같은 단계에서 함께 게시되면 넘겨받는 글보다 먼저 게시되고, 그 글의 초안 번호도 이 공개 안에서 오른다.

### 응답 422 `LEARNING_STAGE_CHECK_FAILED`

글 하나라도 걸리면 **아무것도 바뀌지 않는다.** 글마다 걸린 항목을 모두 싣는다. 모양은 §10의 `articles`에서 `draftVersion`을 뺀 것이고, 걸린 글만 싣는다.

```json
{
  "code": "LEARNING_STAGE_CHECK_FAILED",
  "message": "검사에 걸린 글이 있어 단계를 공개하지 않았습니다.",
  "details": {
    "checkVersion": "learning-publish-checks-v2",
    "articles": [
      { "articleId": "recovery-period", "failures": [ { "check": "QUIZ", "location": { "part": "quiz", "questionId": "q5" }, "message": "정답 보기가 하나가 아닙니다." } ] }
    ]
  }
}
```

### 그 밖의 실패

| 응답 | 조건 |
|---|---|
| `400 INVALID_INPUT` | `articles`가 없거나, 항목의 `articleId`가 비었거나 `expectedDraftVersion`이 없다 |
| `404 LEARNING_STAGE_NOT_FOUND` | 없는 단계 |
| `409 LEARNING_STAGE_ALREADY_RELEASED` | 이미 공개했다 |
| `409 LEARNING_STAGE_ORDER` | 앞 번호 단계가 공개되지 않았다(US5 시나리오 7) |
| `409 LEARNING_STAGE_EMPTY` | 단계에 글이 없다 |
| `409 LEARNING_STAGE_CHANGED` | 요청의 글 목록이 공개 집합과 다르거나 초안 번호가 다르다(같은 글을 두 번 실어도 다르다). `error.details.articles`에 서버가 읽은 공개 집합(잠그기 전에 이미 다르면 그때 읽은 값, 아니면 잠근 뒤 다시 읽은 값)을 §10의 `articles` 순서로 `{ "articleId", "draftVersion" }` 목록으로 싣는다 |

단계 공개에서는 용어 소유 문제(넘겨받기 소유 불일치, 넘겨받기 없이 쓴 다른 글의 게시 용어)를 409로 따로 돌려주지 않고 422의 글별 항목(`TERM_TRANSFER`·`TERM_ID_TAKEN`)으로 함께 싣는다. 운영자가 한 번에 모든 문제를 보게 하려는 것이다. 판정 뒤 먼저 커밋한 다른 저장·게시가 같은 용어를 가져가 용어 제약에 걸려도, 지금 상태로 다시 판정해 같은 422로 돌려준다.

단계 공개를 되돌리는 엔드포인트는 없다(FR-031).
