# 계약: 나의 학습 API (2판)

**Spec**: [spec.md](../spec.md) | **Data model**: [data-model.md](../data-model.md) §11·§12 | **Date**: 2026-10-05

로그인 사용자의 나의 학습 기록(완료·읽은 위치·첫 답·익힘·경험치·레벨·배지)을 읽고 쓰는 계약이다(FR-020~FR-025, FR-028~FR-030). 비로그인 사용자의 기록은 어디에도 남기지 않으므로 비로그인 화면은 이 계약을 부르지 않는다. 공통 규칙은 [README](./README.md)에 있다.

## 0. 인증과 공통 규칙

| 요청 상태 | 응답 |
|---|---|
| 로그인하지 않음·세션 만료 | `401 AUTHENTICATION_REQUIRED`. 화면은 다른 기록 실패와 같이 '기록되지 않았습니다'와 '다시 시도'를 보이고, 재로그인 안내는 014 FR-012를 따른다(spec Edge Cases) |
| 상태 변경 요청에 CSRF 토큰 없음·불일치 | `403 CSRF_TOKEN_INVALID` |

- 경로는 모두 `/learning/me`로 시작한다. `SecurityConfig`가 이 경로를 `authenticated()`로 묶는다([research](../research.md) R33).
- 모든 요청은 로그인 주체의 기록만 다룬다. 다른 사용자의 기록을 가리키는 경로나 매개변수가 없다.
- 상태 변경(`PUT`·`POST`·`DELETE`)은 `X-XSRF-TOKEN` 헤더를 싣는다. 탭을 닫을 때 보내는 읽은 위치(§3)도 같다.
- **기록할 수 있는 글**은 공개된 글(CURRENT 판이 게시 중인 글)이다. 그 밖의 게시된 글(PRE_RELEASE·PREVIOUS)에 기록 요청을 보내면 `409 LEARNING_NOT_RECORDABLE`, 없거나 게시되지 않은 글이면 `404 LEARNING_ARTICLE_UNAVAILABLE`이다.
- 화면은 기록 요청이 실패해도 글 읽기와 진단 진입을 막지 않는다. 실패를 완료나 '안 읽음'으로 꾸미지 않고 '기록되지 않았습니다'와 '다시 시도'를 보이며, 조용히 재시도하지 않는다(FR-020, FR-022, SC-010).

## 1. 나의 학습 전체 (정산 포함)

```
GET /learning/me
```

배우기 목록(요약 패널, 글별 상태, 단계별 완료 수, 떠 있는 '이어서 읽기')과 '나의 학습' 화면이 쓴다. **요청마다 정산을 한다**([data-model](../data-model.md) §12-5, [research](../research.md) R30). 운영자의 게시 취소나 단계 이동으로 채워진 단계 완주·배지가 이때 생긴다.

**첫 단계를 공개하기 전**에는 `409 LEARNING_NOT_RECORDABLE`이다. 나의 학습은 첫 단계를 공개할 때 켠다(FR-031). 화면은 커리큘럼의 `releasedStageCount = 0`이면 이 요청을 보내지 않고 나의 학습 요소를 그리지 않는다([research](../research.md) R33).

### 응답 200

```json
{
  "level": {
    "current": 4,
    "exp": 720,
    "currentThreshold": 600,
    "nextThreshold": 1000,
    "remainingToNext": 280,
    "reachedAt": "2026-11-18T09:12:00Z",
    "isNew": true,
    "visibleMax": 4,
    "unreachableFrom": 5,
    "unreachableReason": "MORE_STAGES",
    "milestones": [
      { "level": 1, "threshold": 0 }, { "level": 2, "threshold": 100 }, { "level": 3, "threshold": 300 },
      { "level": 4, "threshold": 600 }, { "level": 5, "threshold": 1000 }
    ]
  },
  "counts": { "completedArticles": 3, "masteredTerms": 4, "badges": 5, "publishedArticles": 8, "publishedTerms": 12 },
  "continueReading": { "kind": "RESUME", "articleId": "recovery-period", "number": "2-3", "title": "떨어진 뒤 얼마나 걸려 돌아왔을까?", "percent": 62, "moreStagesPreparing": true },
  "articles": [
    { "articleId": "sectors-and-etf", "state": "COMPLETED", "percent": 100, "completedAt": "2026-11-13T02:00:00Z", "termCount": 2, "masteredTermCount": 2 },
    { "articleId": "recovery-period", "state": "READING", "percent": 62, "completedAt": null, "termCount": 1, "masteredTermCount": 0 }
  ],
  "stages": [
    { "stageKey": "etf-market-basics", "number": 1, "status": "OPEN", "completedCount": 2, "publishedCount": 4, "completed": false },
    { "stageKey": "allocation-rules", "number": 3, "status": "PREPARING", "completedCount": null, "publishedCount": null, "completed": false }
  ],
  "masteredTerms": [
    { "termId": "sector", "label": "섹터", "articleId": "sectors-and-etf", "articleNumber": "1-1" }
  ],
  "badges": [
    { "badgeKey": "read-1", "family": "READ", "name": "읽은 글 1편", "criterion": "글 1편을 끝까지 읽습니다", "status": "EARNED", "earnedAt": "2026-11-13T02:00:00Z", "isNew": false, "progress": null, "preparingReason": null, "stageNumber": null },
    { "badgeKey": "read-5", "family": "READ", "name": "읽은 글 5편", "criterion": "글 5편을 끝까지 읽습니다", "status": "NOT_YET", "earnedAt": null, "isNew": false, "progress": { "current": 3, "target": 5 }, "preparingReason": null, "stageNumber": null },
    { "badgeKey": "read-10", "family": "READ", "name": "읽은 글 10편", "criterion": "글 10편을 끝까지 읽습니다", "status": "PREPARING", "earnedAt": null, "isNew": false, "progress": null, "preparingReason": "MORE_STAGES", "stageNumber": null },
    { "badgeKey": "stage-allocation-rules", "family": "STAGE", "name": "3단계 완주", "criterion": "3단계의 글을 모두 읽습니다", "status": "PREPARING", "earnedAt": null, "isNew": false, "progress": null, "preparingReason": "STAGE_NOT_RELEASED", "stageNumber": 3 }
  ],
  "gained": { "exp": [], "expTotal": 0, "badges": [], "levelBefore": 4, "levelAfter": 4 }
}
```

| 필드 | 뜻 | 근거 |
|---|---|---|
| `level.visibleMax`·`unreachableFrom`·`unreachableReason` | 보이는 가장 높은 레벨, 그다음 레벨 N, 사유(`MORE_STAGES`·`MORE_ARTICLES`). 화면은 '레벨 N부터는 단계가(글이) 더 공개된 뒤 닿을 수 있습니다.'와 점선 레벨 길로 보인다 | FR-029, [data-model](../data-model.md) §12-3 |
| `level.milestones` | 레벨 1부터 `unreachableFrom`까지의 문턱. 화면이 레벨 길을 그린다 | FR-029 |
| `level.isNew` | 현재 레벨에 닿은 지 7일 안 | FR-030 |
| `counts` | 분모 없이 보이는 수와, 숫자 줄 아래 '지금 공개된 글은 8편입니다' 문장에 쓰는 공개 수 | FR-020 |
| `continueReading.kind` | `RESUME`(읽는 중 글을 기록된 위치에서) · `START`(첫 안 읽은 글을 맨 위에서, `percent: null`) · `ALL_DONE`(`articleId` 등이 `null`) | FR-020, [data-model](../data-model.md) §12-2 |
| `articles` | 공개된 글마다 상태(`UNREAD`·`READING`·`COMPLETED`)와 비율, 용어 수. `READING`의 `percent`는 1~99 | FR-020, FR-023 |
| `stages` | 여섯 단계 전부. `OPEN`은 두 수가 있고, `REPREPARING`은 `completedCount: 0`·`publishedCount: 0`, `PREPARING`은 둘 다 `null`이다 | FR-020, FR-031 |
| `badges` | 31개 전부, 갈래(`LEVEL`·`READ`·`CORRECT`·`PERFECT`·`STAGE`·`TERMS`·`SPECIAL`) 순서. `status`는 `EARNED`·`NOT_YET`·`PREPARING`이다. 배지의 `PREPARING`은 지금 공개된 콘텐츠로는 받을 수 없다는 뜻이고(사유는 `preparingReason`), `stages[].status`의 `PREPARING`(단계 공개 전)과 이름만 같다 | FR-024, [data-model](../data-model.md) §12-4 |
| `badges[].preparingReason` | `MORE_STAGES`·`MORE_ARTICLES`·`ALL_STAGES`·`STAGE_NOT_RELEASED`·`STAGE_EMPTY`. 화면이 [design-notes](../design-notes.md) §7 문구로 바꾼다 | FR-024 |
| `badges[].isNew` | 받은 지 7일 안 | FR-030 |
| `gained` | 이번 정산에서 새로 생긴 것(§8). 화면은 조용한 알림으로 보인다 | FR-030 |

경험치를 언제 무엇으로 받았는지의 내역은 내보내지 않는다(FR-020).

## 2. 글 하나의 기록

```
GET /learning/me/articles/{articleId}
```

글 화면이 완료 카드와 확인 문제를 그릴 때 쓴다. 정산하지 않는다.

### 응답 200

```json
{
  "articleId": "recovery-period",
  "state": "COMPLETED",
  "percent": 100,
  "completedAt": "2026-11-20T03:10:00Z",
  "answers": [
    { "questionId": "q1", "choiceId": "c2", "correct": true, "answeredAt": "2026-11-20T03:11:00Z" }
  ],
  "quiz": { "total": 3, "answered": 1, "correct": 1, "allAnswered": false, "mastered": true, "masteredVia": "FIRST_ANSWER", "retryAvailable": false },
  "stageProgress": { "stageNumber": 2, "completedCount": 3, "publishedCount": 4, "stageCompleted": false },
  "continueReading": { "kind": "RESUME", "articleId": "volatility-is-not-loss", "number": "2-4", "title": "변동성은 손실일까?", "percent": 30, "moreStagesPreparing": true }
}
```

- `continueReading`은 §1과 같은 모양과 규칙이다. 글 응답의 `next`가 `null`일 때(이 글 뒤에 공개된 글이 없을 때) 화면은 이 값으로 '지금 공개된 글을 모두 읽었습니다'(`ALL_DONE`, `moreStagesPreparing`이면 '다음 단계는 준비 중입니다'를 덧붙임)와 '이어서 읽기'를 가른다(FR-021).
- `answers`는 공개 판에 있는 문제의 첫 답만 담는다. 지운 문제의 답은 화면에서 사라진다(spec Edge Cases). 풀이 문장은 글 응답의 `quiz`에서 가져온다.
- `state = UNREAD`면 `percent: 0`, `answers: []`다.
- 기록할 수 없는 글은 `409 LEARNING_NOT_RECORDABLE`이다.

## 3. 읽은 위치 기록

```
PUT /learning/me/articles/{articleId}/position
```

### 요청

```json
{ "percent": 62 }
```

- `percent`는 0~100 정수다. 서버는 기록된 값보다 클 때만 바꾸고, `last_opened_at`은 늘 지금으로 바꾼다. 행이 없으면 만든다(글이 '읽는 중'이 된다).
- 화면은 글을 떠날 때마다 한 번 보낸다. 비율이 그대로여도 보낸다(`last_opened_at`과 '읽는 중' 전환 때문이다). 화면 안 이동은 `apiClient`로, 탭 숨김·닫기는 `fetch(…, { keepalive: true, credentials: 'include' })`에 `X-XSRF-TOKEN` 헤더를 실어 보낸다([research](../research.md) R32).
- 이 기록이 실패해도 완료 기록에는 영향이 없다(FR-020). 화면은 이 실패를 따로 알리지 않는다. 떠나는 순간이라 보일 자리가 없다.

### 응답 204

### 실패

| 응답 | 조건 |
|---|---|
| `400 INVALID_INPUT` | `percent`가 없거나 범위 밖 |
| `404 LEARNING_ARTICLE_UNAVAILABLE` · `409 LEARNING_NOT_RECORDABLE` | §0 |

## 4. 완료

```
POST /learning/me/articles/{articleId}/completion
```

본문이 없다. 화면이 본문 끝과 최소 머문 시간 두 조건이 함께 참이 되는 첫 순간에 보낸다(FR-021). 서버는 머문 시간을 다시 재지 않는다(R32).

### 응답 200

```json
{
  "completedAt": "2026-11-20T03:10:00Z",
  "alreadyCompleted": false,
  "stageProgress": { "stageNumber": 2, "completedCount": 4, "publishedCount": 4, "stageCompleted": true },
  "continueReading": { "kind": "ALL_DONE", "articleId": null, "number": null, "title": null, "percent": null, "moreStagesPreparing": true },
  "gained": {
    "exp": [ { "kind": "ARTICLE_COMPLETED", "points": 20 }, { "kind": "STAGE_COMPLETED", "points": 50, "stageNumber": 2 } ],
    "expTotal": 70,
    "badges": [ { "badgeKey": "stage-returns-and-risk", "family": "STAGE", "name": "2단계 완주" } ],
    "levelBefore": 3,
    "levelAfter": 4
  }
}
```

- 이미 완료한 글이면 `alreadyCompleted: true`, 그때의 `completedAt`, 빈 `gained`로 200이다. 완료를 되돌리는 경로는 없다(FR-021).
- 완료와 함께 `ARTICLE_COMPLETED` 20과 정산(단계 완주·배지)을 한 트랜잭션에서 한다.
- `continueReading`은 이 완료를 반영한 값이다(§2와 같다). 완료 카드의 '다음 글' 자리 문구에 쓴다.

### 실패

§0의 404·409.

## 5. 첫 답

```
POST /learning/me/articles/{articleId}/answers
```

### 요청

```json
{ "questionId": "q2", "choiceId": "c5" }
```

### 응답 200

```json
{
  "questionId": "q2",
  "choiceId": "c5",
  "correct": false,
  "correctChoiceId": "c4",
  "explanation": "...",
  "alreadyAnswered": false,
  "quiz": { "total": 3, "answered": 2, "correct": 1, "allAnswered": false, "mastered": true, "masteredVia": "FIRST_ANSWER", "retryAvailable": false },
  "gained": { "exp": [], "expTotal": 0, "badges": [], "levelBefore": 4, "levelAfter": 4 }
}
```

- 서버가 공개 판으로 맞음·틀림을 판정하고 첫 답을 저장한 뒤 응답한다. 화면은 이 응답을 받은 뒤에만 맞았는지와 정답·풀이를 보인다(FR-022).
- **이미 답한 문제**면 저장된 첫 답을 `alreadyAnswered: true`로 돌려준다. 요청의 `choiceId`와 달라도(다른 탭에서 먼저 답함) 저장된 값을 돌려주고 바꾸지 않는다. '다시 시도'가 같은 보기를 다시 보내도 같은 결과다.
- 경험치·익힘·모두 맞힘은 [data-model](../data-model.md) §12-3의 규칙대로 같은 트랜잭션에서 정한다. 첫 답이 맞으면 모든 문제에 답하기 전이어도 그 글이 익힘(`FIRST_ANSWER`)이 되고(이미 익힘이면 그대로), 응답의 `quiz.mastered`·`masteredVia`에 바로 보인다(FR-022). 그 글의 `learning_progress` 행을 잠근다(R29).

### 실패

| 응답 | 조건 |
|---|---|
| `400 INVALID_INPUT` | `questionId`나 `choiceId`가 없거나 비었다. 형식이 틀린 값은 공개 판에 없는 값과 같이 아래 404다 |
| `409 LEARNING_COMPLETION_REQUIRED` | 그 글을 아직 완료하지 않았다. 로그인 사용자에게 확인 문제는 완료 뒤에 보인다(FR-022) |
| `404 LEARNING_QUESTION_NOT_FOUND` | 공개 판에 그 문제가 없거나 그 문제에 그 보기가 없다(운영자가 문제를 바꿨다). 화면은 글을 다시 불러오라는 한 문장과 '다시 불러오기'를 보인다 |
| §0의 404·409 | |

## 6. 다시 풀기

```
POST /learning/me/articles/{articleId}/retry-answers
```

### 요청

```json
{ "questionId": "q1", "choiceId": "c3" }
```

### 응답 200

```json
{
  "questionId": "q1",
  "choiceId": "c3",
  "correct": true,
  "correctChoiceId": "c3",
  "explanation": "...",
  "quiz": { "total": 2, "answered": 2, "correct": 0, "allAnswered": true, "mastered": true, "masteredVia": "RETRY", "retryAvailable": false },
  "gained": { "exp": [], "expTotal": 0, "badges": [ { "badgeKey": "persistence", "family": "SPECIAL", "name": "끈기" } ], "levelBefore": 3, "levelAfter": 3 }
}
```

- 다시 풀기의 답은 저장하지 않는다. 맞히면 그 글을 익힘(`RETRY`)으로 바꾸고 경험치는 주지 않는다(FR-022, FR-028).
- `quiz.correct`는 첫 답 기준이라 다시 풀어도 바뀌지 않는다.

### 실패

| 응답 | 조건 |
|---|---|
| `400 INVALID_INPUT` | §5와 같다 |
| `409 LEARNING_RETRY_NOT_AVAILABLE` | 아직 답하지 않은 문제가 있거나, 첫 답에서 하나 이상 맞혔거나, 이미 익힘이다. 이 판정을 문제·보기 확인(404)보다 먼저 한다 |
| `404 LEARNING_QUESTION_NOT_FOUND` · §0의 404·409 | |

## 7. 학습 초기화

```
DELETE /learning/me
```

회원정보 화면(`/account/profile`)의 '학습 초기화'가 확인을 받은 뒤 부른다(FR-025, R36). 그 사용자의 완료·읽은 위치·첫 답·익힘·경험치 장부·배지를 한 트랜잭션에서 모두 지운다([data-model](../data-model.md) §11-5).

### 응답 204

기록이 하나도 없어도 204다. 되살리는 경로는 없다.

## 8. `gained` 모양

학습 행동 응답(§4·§5·§6)과 §1이 같은 모양을 쓴다.

| 필드 | 뜻 |
|---|---|
| `exp[]` | 이번 요청에서 새로 생긴 장부 줄. `kind`·`points`, 단계 완주는 `stageNumber` |
| `expTotal` | 위 점수 합 |
| `badges[]` | 이번 요청에서 새로 받은 배지의 `badgeKey`·`family`·`name` |
| `levelBefore`·`levelAfter` | 요청 전후 레벨. 다르면 화면이 새 레벨 알림을 보인다 |

화면은 이 값으로 완료 카드와 '나의 학습'에 조용한 알림(`role="status"`, 초점을 옮기지 않음)을 보인다. 폭죽·소리를 쓰지 않고 배우기 밖 화면에는 보이지 않는다(FR-030, FR-019).

## 9. 이 계약이 하지 않는 것

- 다른 사용자의 기록, 여러 사용자의 집계(문제별 정답률, 단계 완주율 등)를 내보내지 않는다(FR-020, Out of Scope).
- 글마다 기록을 지우는 경로를 두지 않는다. 지우는 것은 §7 하나다(FR-025).
- 진단·백테스트·자동 탐색·결과 화면의 행동을 받지 않는다. 경험치와 배지는 배우기 안의 글 읽기와 확인 문제로만 생긴다(FR-028).
- 비로그인으로 읽거나 답한 것을 받지 않는다. 로그인 전 기록을 계정에 합치는 경로가 없다(FR-020).
- 경험치 내역(언제 무엇으로 받았는지)을 내보내지 않는다(FR-020).
