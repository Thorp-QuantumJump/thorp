# 계약: 배우기 공개 조회 API

**Spec**: [spec.md](../spec.md) | **Data model**: [data-model.md](../data-model.md) | **Date**: 2026-09-16 · 2판 2026-10-05

배우기 화면과 013 도움말을 붙인 화면이 쓰는 읽기 계약이다. **로그인이 필요하지 않다**(FR-012, [014](../../014-user-auth/spec.md) FR-010). 모든 응답은 **공개 중인 판**과 공개 상태(단계의 공개 여부, 글의 공개 배치, 변경 기록)만 담는다. 초안의 내용은 어떤 경로로도 나가지 않는다(FR-017, SC-006). 사용자 식별을 싣지 않고 사용자별 기록을 남기지 않는다. 기록은 [나의 학습 계약](./learning-me-api.md)이 맡는다.

공통 규칙은 [README](./README.md)에 있다.

## 1. 커리큘럼 **(2판)**

```
GET /learning/curriculum
```

배우기 목록(커리큘럼 보기·목록 보기)과 '나의 학습' 화면의 변경 기록이 쓴다.

### 응답 200 — 1·2단계가 공개된 때

```json
{
  "releasedStageCount": 2,
  "allStagesReleased": false,
  "stages": [
    {
      "stageKey": "etf-market-basics",
      "number": 1,
      "name": "ETF와 국내 시장 기초",
      "levelLabel": "입문",
      "summary": "ETF가 무엇을 담고 있고 가격과 비용이 어떻게 정해지는지 봅니다.",
      "status": "OPEN",
      "releasedAt": "2026-11-12T01:00:00Z",
      "articles": [
        {
          "articleId": "sectors-and-etf",
          "number": "1-1",
          "title": "섹터와 ETF",
          "question": "...",
          "readingMinutes": 5,
          "termCount": 2,
          "hasExample": true
        }
      ],
      "previousEditionArticles": []
    },
    {
      "stageKey": "allocation-rules",
      "number": 3,
      "name": "배분 규칙과 설정",
      "levelLabel": "입문~초급",
      "summary": "다섯 가지 배분 규칙과 설정 항목이 결과를 어떻게 바꾸는지 봅니다.",
      "status": "PREPARING",
      "releasedAt": null,
      "articles": [],
      "previousEditionArticles": [
        { "articleId": "weights-and-rebalancing", "title": "비중과 리밸런싱", "question": "...", "readingMinutes": 2 }
      ]
    }
  ],
  "preReleaseArticles": null,
  "notices": [
    {
      "entryKey": "stage:returns-and-risk",
      "kind": "STAGE_RELEASED",
      "stageNumber": 2,
      "stageName": "수익과 위험 읽기",
      "occurredAt": "2026-11-19T01:00:00Z",
      "newCount": 3,
      "rewrittenCount": 1,
      "rewrittenTitles": ["수익과 손실"],
      "articleTitle": null
    }
  ],
  "changeLog": [
    { "entryKey": "stage:returns-and-risk", "kind": "STAGE_RELEASED", "stageNumber": 2, "stageName": "수익과 위험 읽기", "occurredAt": "2026-11-19T01:00:00Z", "newCount": 3, "rewrittenCount": 1, "rewrittenTitles": ["수익과 손실"], "articleTitle": null },
    { "entryKey": "stage:etf-market-basics", "kind": "STAGE_RELEASED", "stageNumber": 1, "stageName": "ETF와 국내 시장 기초", "occurredAt": "2026-11-12T01:00:00Z", "newCount": 3, "rewrittenCount": 1, "rewrittenTitles": ["섹터와 ETF"], "articleTitle": null }
  ]
}
```

| 필드 | 뜻 | 근거 |
|---|---|---|
| `stages` | 여섯 단계 전부, 번호 순서. 공개되지 않은 단계도 들어 있다 | FR-031 |
| `stages[].status` | `OPEN`(공개됨, 공개된 글 한 편 이상) · `PREPARING`(공개 전) · `REPREPARING`(공개됐지만 공개된 글 0편) | FR-031 |
| `stages[].articles` | `OPEN` 단계의 공개된 글, 읽는 순서. 그 밖의 상태는 빈 배열이다. 새 글 제목·편수를 미리 보이지 않는다 | FR-023, FR-031, SC-017 |
| `stages[].articles[].number` | 단계 안 번호(예: `2-3`) | FR-023 |
| `stages[].articles[].termCount` | 그 글의 공개 용어 수. 0이면 화면이 용어 수를 보이지 않는다 | FR-023 |
| `stages[].previousEditionArticles` | `PREPARING` 단계에 남은 LEGACY 판 글(이전 판). 번호·상태가 없다 | FR-031 |
| `preReleaseArticles` | 첫 단계를 공개하기 전에만 배열이다(아래). 공개한 뒤에는 `null` | FR-031 |
| `notices` | `changeLog` 가운데 `occurredAt`이 지금부터 7일 안인 줄, 최신 순서 | FR-031, R27 |
| `changeLog` | 모든 변경 기록, 최신 순서 | FR-031 |

- 화면은 `notices`를 한 줄로 합쳐 보인다. 로그인 사용자에게는 합친 줄에 `stageNumber`가 1이 아닌 `STAGE_RELEASED`가 있을 때만 '지금까지의 읽은 기록, 경험치, 배지는 그대로입니다.'를 덧붙인다. 변경 기록 항목에는 이 문장을 붙이지 않는다([design-notes](../design-notes.md) §7).
- 공개 경계 한 줄('지금 공개된 단계는 1·2단계입니다. 3~6단계는 준비 중입니다.')은 `status`로 화면이 만든다. `REPREPARING`도 공개된 단계로 센다.

### 응답 200 — 첫 단계를 공개하기 전

```json
{
  "releasedStageCount": 0,
  "allStagesReleased": false,
  "stages": [ { "stageKey": "etf-market-basics", "number": 1, "status": "PREPARING", "articles": [], "previousEditionArticles": [], "...": "..." } ],
  "preReleaseArticles": [
    { "articleId": "sectors-and-etf", "title": "섹터와 ETF", "question": "...", "readingOrder": 1 }
  ],
  "notices": [],
  "changeLog": []
}
```

`releasedStageCount = 0`이면 화면은 9월 판 목록과 같은 모습으로 `preReleaseArticles`만 보인다(FR-031 "지금 모습 그대로"). 이 배열은 §2의 응답과 같은 글·같은 순서다. 이때 `stages[].previousEditionArticles`는 비어 있다.

**게시된 글이 하나도 없어도 200이다.** 빈 목록은 정상 화면이다(spec Edge Cases). 서버가 응답을 만들지 못했으면 5xx이며 빈 배열로 대신하지 않는다(원칙 VI).

## 2. 글 목록 (9월 판)

```
GET /learning/articles
```

9월 판 응답 그대로다. 공개 중인 글(LEGACY·CURRENT 모두)을 판의 `readingOrder` 오름차순, 같으면 `articleId` 오름차순으로 돌려준다. **(2판)** 새 화면은 §1을 쓴다. 이 경로는 화면이 §1로 옮긴 묶음(B11)이 배포된 뒤 별도 묶음(B16, `refactor(be)`)에서 지운다. 2판에서 공개 API를 지우는 것은 이 경로 하나다.

```json
{ "articles": [ { "articleId": "sectors-and-etf", "title": "섹터와 ETF", "question": "...", "readingOrder": 1 } ] }
```

## 3. 글 하나

```
GET /learning/articles/{articleId}
```

응답의 `edition`이 글의 판을 알린다.

| `edition` | 뜻 | 블록 모양 |
|---|---|---|
| `CURRENT` **(2판)** | 새 기준 글(V2), 나의 학습에 기록한다 | §3-2 |
| `PRE_RELEASE` **(2판)** | 첫 단계 공개 전의 기존 글. 지금 모습 그대로 보인다 | §3-1(9월 판) |
| `PREVIOUS` **(2판)** | 첫 단계 공개 뒤 공개되지 않은 단계에 남은 기존 글(이전 판). 기록하지 않는다 | §3-1(9월 판) |

### 3-1. 응답 200 — `PRE_RELEASE`·`PREVIOUS` (9월 판 모양에 필드를 더함)

```json
{
  "articleId": "returns-and-losses",
  "edition": "PREVIOUS",
  "title": "수익과 손실",
  "question": "가장 크게 줄었던 폭은 왜 시작 금액 대비 손실과 다른가?",
  "readingOrder": 8,
  "stage": { "stageKey": "returns-and-risk", "number": 2, "name": "수익과 위험 읽기" },
  "number": null,
  "readingMinutes": 2,
  "minDwellSeconds": null,
  "blocks": [
    { "type": "heading", "text": "고점에서 얼마나 내려갔나" },
    { "type": "paragraph", "text": "..." },
    { "type": "term", "termId": "max-drawdown", "label": "가장 크게 줄었던 폭", "sentence": "..." },
    { "type": "hypothetical", "text": "..." }
  ],
  "quiz": [],
  "example": {
    "available": true,
    "sectors": [ { "code": "SEMICONDUCTOR", "name": "반도체", "weightTenths": 500 }, { "code": "BATTERY", "name": "2차전지", "weightTenths": 500 } ],
    "missingSectorCodes": [],
    "unavailableReason": null
  },
  "sources": [ { "label": "...", "url": "https://..." } ],
  "next": null,
  "stageArticleCount": null,
  "hasUnreleasedStages": true,
  "publishedAt": "2026-09-20T01:00:00Z"
}
```

9월 판 필드(`articleId`·`title`·`question`·`readingOrder`·`blocks`·`example`·`sources`·`publishedAt`)의 모양과 뜻은 바뀌지 않는다. `stage`는 공개 배치 단계다. `PREVIOUS` 글 머리에는 '이전 판' 표시를, 글 끝에는 완료 카드 대신 기록하지 않는다는 한 줄과 '배우기 목록으로'를 둔다(FR-031). `PRE_RELEASE` 글에는 그런 표시가 없다.

### 3-2. 응답 200 — `CURRENT` **(2판)**

```json
{
  "articleId": "volatility-is-not-loss",
  "edition": "CURRENT",
  "title": "변동성은 손실일까?",
  "question": "...",
  "readingOrder": 204,
  "stage": { "stageKey": "returns-and-risk", "number": 2, "name": "수익과 위험 읽기" },
  "number": "2-4",
  "readingMinutes": 5,
  "minDwellSeconds": 100,
  "blocks": [
    { "type": "keySummary", "lines": [ [ { "text": "..." } ], [ { "text": "..." } ], [ { "text": "..." } ] ] },
    { "type": "heading", "text": "..." },
    {
      "type": "paragraph",
      "content": [
        { "text": "..." },
        { "term": "max-drawdown", "text": "가장 크게 줄었던 폭", "owned": false, "aliases": ["최대 낙폭", "MDD", "maximum drawdown"] },
        { "text": "과 ..." },
        { "source": { "label": "...", "url": "https://..." } }
      ]
    },
    { "type": "term", "termId": "volatility", "label": "변동성", "aliases": ["volatility"], "meaning": "...", "reading": "...", "caveat": "..." },
    { "type": "hypothetical", "content": [ { "text": "..." } ] },
    { "type": "misconception", "wrong": [ { "text": "..." } ], "correction": [ { "text": "..." } ] },
    { "type": "list", "ordered": false, "items": [ [ { "text": "..." } ] ] },
    { "type": "table", "caption": "...", "columns": ["구분", "가", "나"], "rows": [ [ [ { "text": "..." } ], [ { "text": "..." } ], [ { "text": "..." } ] ] ] },
    { "type": "diagram", "kind": "line", "title": "...", "unit": "만 원", "categories": ["1월", "2월", "3월"], "series": [ { "name": "가", "values": [100, 92, 104] } ], "description": "..." },
    { "type": "institutionNote", "content": [ { "text": "..." } ], "asOf": "2026-07-01", "source": { "label": "...", "url": "https://..." } }
  ],
  "quiz": [
    {
      "questionId": "q1",
      "prompt": "...",
      "choices": [ { "choiceId": "c1", "text": "..." }, { "choiceId": "c2", "text": "..." } ],
      "correctChoiceId": "c2",
      "explanation": "..."
    }
  ],
  "example": null,
  "sources": [],
  "next": { "articleId": "weights-and-rebalancing", "number": "3-1", "title": "비중과 리밸런싱" },
  "stageArticleCount": 4,
  "hasUnreleasedStages": true,
  "publishedAt": "2026-11-19T01:00:00Z"
}
```

| 필드 | 근거 |
|---|---|
| `blocks` | V2 블록과 글자 조각([data-model](../data-model.md) §5-2·§5-3). 화면은 글자 그대로 그리고 마크업으로 해석하지 않는다(FR-026) |
| 언급 조각의 `owned`·`aliases` | 서버가 채운다. 화면이 처음 나오는 자리에 다른 이름과 도움말 표시를 둔다([data-model](../data-model.md) §5-4, R18) |
| `term` 블록의 `meaning` | 도움말(§4)의 `sentence`와 **같은 판의 같은 값**이다(FR-008, SC-003) |
| `misconception`·`table`·`diagram`·`hypothetical` | 화면이 '가상의 예'(오해의 `wrong`에는 '오해')를 자동으로 붙인다(FR-005, FR-017) |
| `quiz[].correctChoiceId`·`explanation` | 화면은 로그인 사용자에게는 기록이 저장된 뒤, 비로그인 사용자에게는 답한 뒤에만 보인다([research](../research.md) R22) |
| `readingMinutes`·`minDwellSeconds` | 판에 넣은 값(R20). 로그인 사용자의 완료 판정에 쓴다(FR-021) |
| `next` | 읽는 순서상 다음 공개된 글. 없으면 `null`이다. 그 자리에 로그인 사용자는 [나의 학습 계약](./learning-me-api.md) §2·§4의 `continueReading`으로 '이어서 읽기'와 '지금 공개된 글을 모두 읽었습니다'를 가르고, 비로그인 사용자는 '배우기 목록으로'를 본다(FR-021) |
| `stageArticleCount` | 그 단계의 공개된 글 수. 비로그인 화면의 단계 안내에 쓴다 |
| `hasUnreleasedStages` | '다음 단계는 준비 중입니다'를 덧붙일지(FR-020, FR-021) |
| `example` | 예시가 없는 글은 `null`이고 '이 예시로 전략 진단하기'가 없다(FR-003). 있으면 §3-1과 같은 모양이다 |

### 공통 필드

| 필드 | 근거 |
|---|---|
| `example.available` | 조회 시점의 열린 대응표에 예시 섹터가 모두 있는가([research](../research.md) R8) |
| `example.sectors[].name` | 열린 대응표의 섹터 이름. 대응표에 없는 코드는 `name: null` |
| `example.missingSectorCodes` | 대응표에 없는 코드. `available=true`거나 `SECTOR_MAP_NOT_READY`면 빈 배열이다. 화면은 `unavailableReason`으로 안내 문장을 가른다 |
| `example.unavailableReason` | `available=false`일 때만 값이 있다. `SECTOR_NOT_IN_MAP` \| `SECTOR_MAP_NOT_READY` |

**`example.available=false`여도 200이다.** 글은 보여 주고 연결만 막는다(US2 시나리오 3).

**`weightTenths` 단위**: 0.1%p 정수, `1000`이 100%다. 002 계약과 같다.

### 응답 404 `LEARNING_ARTICLE_UNAVAILABLE`

없는 `articleId`, 게시되지 않은(초안·게시 취소) 글, 형식이 틀린 `articleId`를 **구분하지 않는다.** 화면은 오류 화면이 아니라 "지금은 볼 수 없는 글"이라는 한 문장과 배우기 목록 연결을 보인다(spec Edge Cases).

```json
{ "code": "LEARNING_ARTICLE_UNAVAILABLE", "message": "지금은 볼 수 없는 글입니다." }
```

## 4. 용어 설명 하나 (도움말)

```
GET /learning/terms/{termId}
```

### 응답 200

```json
{
  "termId": "max-drawdown",
  "label": "가장 크게 줄었던 폭",
  "sentence": "...",
  "articleId": "returns-and-losses",
  "articleTitle": "수익과 손실"
}
```

| 필드 | 근거 |
|---|---|
| `sentence` | 도움말 창에 보일 문장. LEGACY 판 용어는 그 문장, **(2판)** CURRENT 판 용어는 뜻 문장이다. 연결된 글의 공개 판 본문에 그대로 들어 있다(FR-008, SC-003). 읽는 법·한계·다른 이름은 싣지 않는다 |
| `articleId` | FR-009. 도움말의 '해당 주제 글로 이동' 대상. 넘기기(R24) 뒤에는 새 글을 가리킨다 |
| `articleTitle` | 연결 문구에 쓴다. 공개 중인 판의 제목이다 |

9월 판과 모양이 같다. 003 등 이미 쓰는 화면은 바꿀 것이 없다.

### 응답 404 `LEARNING_TERM_NOT_READY`

게시된 설명이 없다. 용어 자리만 있는 초안, 연결된 글이 게시 취소된 용어, 없는 `termId`를 가르지 않는다. **화면은 이 코드일 때만 '설명 준비 중'을 보인다**(FR-010).

```json
{ "code": "LEARNING_TERM_NOT_READY", "message": "설명을 준비하고 있습니다." }
```

**그 밖의 모든 실패는 '불러오지 못했다'다.** 연결 실패·시간 초과·5xx·예상하지 못한 코드가 여기에 든다. 화면은 다시 시도 수단을 보이고 조용히 재시도하지 않는다(FR-010, [research](../research.md) R10).

## 5. 이 계약이 하지 않는 것

- 사용자별 기록을 받거나 돌려주지 않는다. 조회 요청에 사용자 식별을 싣지 않으며, 서버도 조회를 사용자별로 남기지 않는다(FR-012). 기록은 [learning-me-api](./learning-me-api.md)다.
- 여러 용어를 한 번에 받는 조회를 두지 않는다(R10).
- 초안·게시 이력을 내보내지 않는다. 그것은 [관리 계약](./learning-admin-api.md)의 범위다.
- 공개되지 않은 단계의 새 글 제목·편수·공개 예정 날짜를 내보내지 않는다(FR-031).
