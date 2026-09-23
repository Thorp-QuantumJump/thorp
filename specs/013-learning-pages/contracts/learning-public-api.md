# 계약: 배우기 공개 조회 API

**Spec**: [spec.md](../spec.md) | **Data model**: [data-model.md](../data-model.md) | **Date**: 2026-09-16

배우기 화면과 진단 화면 도움말이 쓰는 읽기 계약이다. **로그인이 필요하지 않다**(FR-012, [014](../../014-user-auth/spec.md) FR-010). 모든 응답은 **공개 중인 판**만 담는다. 초안의 내용은 어떤 경로로도 나가지 않는다(FR-017, SC-006).

공통 규칙은 [README](./README.md)에 있다.

## 1. 글 목록

```
GET /learning/articles
```

### 응답 200

공개 중인 글을 `readingOrder` 오름차순, 같으면 `articleId` 오름차순으로 돌려준다.

```json
{
  "articles": [
    {
      "articleId": "sectors-and-etf",
      "title": "섹터와 ETF",
      "question": "섹터와 대표 ETF는 무엇이 같고 무엇이 다른가?",
      "readingOrder": 1
    },
    {
      "articleId": "returns-and-losses",
      "title": "수익과 손실",
      "question": "가장 크게 줄었던 폭은 왜 시작 금액 대비 손실과 다른가?",
      "readingOrder": 8
    }
  ]
}
```

| 필드 | 근거 |
|---|---|
| `readingOrder` | FR-001 읽는 순서 제안. 화면은 이 순서로 보이되 강제하지 않는다 |

**게시된 글이 없으면 `articles: []`로 200이다.** spec Edge Cases가 빈 목록을 정상 화면으로 정했다. 오류가 아니다. 반대로 서버가 목록을 만들지 못했으면 5xx이며 빈 배열로 대신하지 않는다(원칙 VI). 화면은 5xx·연결 실패를 '불러오지 못했다'로 보이고 빈 목록과 다르게 그린다(FR-015, SC-007).

## 2. 글 하나

```
GET /learning/articles/{articleId}
```

### 응답 200

```json
{
  "articleId": "returns-and-losses",
  "title": "수익과 손실",
  "question": "가장 크게 줄었던 폭은 왜 시작 금액 대비 손실과 다른가?",
  "readingOrder": 8,
  "blocks": [
    { "type": "heading", "text": "고점에서 얼마나 내려갔나" },
    { "type": "paragraph", "text": "..." },
    {
      "type": "term",
      "termId": "max-drawdown",
      "label": "가장 크게 줄었던 폭",
      "sentence": "..."
    },
    { "type": "hypothetical", "text": "..." }
  ],
  "example": {
    "available": true,
    "sectors": [
      { "code": "SEMICONDUCTOR", "name": "반도체", "weightTenths": 500 },
      { "code": "BATTERY", "name": "2차전지", "weightTenths": 500 }
    ],
    "missingSectorCodes": [],
    "unavailableReason": null
  },
  "sources": [
    { "label": "...", "url": "https://..." }
  ],
  "publishedAt": "2026-09-20T01:00:00Z"
}
```

| 필드 | 근거 |
|---|---|
| `blocks[].type=term` | 서버가 판의 용어 설명에서 `label`·`sentence`를 채워 보낸다. 도움말 조회(§3)가 돌려주는 문장과 **같은 판의 같은 값**이다(FR-008, SC-003) |
| `blocks[].type=hypothetical` | 화면이 '가상의 계산 예' 표시를 붙인다(FR-005) |
| `example.available` | 조회 시점의 열린 대응표에 예시 섹터가 모두 있는가([research](../research.md) R8) |
| `example.sectors[].name` | 열린 대응표의 섹터 이름. 대응표에 없는 코드는 `name: null` |
| `example.missingSectorCodes` | 대응표에 없는 코드. `available=true`면 빈 배열이다. 대응표를 확인할 수 없는 `SECTOR_MAP_NOT_READY`도 판정하지 못했으므로 빈 배열이다. 화면은 이 배열의 길이가 아니라 `unavailableReason`으로 안내 문장을 가른다 |
| `example.unavailableReason` | `available=false`일 때만 값이 있다. `SECTOR_NOT_IN_MAP` \| `SECTOR_MAP_NOT_READY` |
| `sources` | FR-007. `url`은 없을 수 있고, 있으면 `http`·`https`만 온다 |

**`example.available=false`여도 200이다.** 글은 보여 주고 연결만 막는다. 화면은 연결 버튼 대신 한 문장 안내와 빈 섹터 선택 화면으로 가는 연결을 보인다(US2 시나리오 3). 판에 저장된 예시 값은 바꾸지 않고 그대로 보낸다.

**`weightTenths` 단위**: 0.1%p 정수, `1000`이 100%다. 002 계약과 같다. 화면은 이 값을 그대로 002 navigation state에 싣는다([term-help-ui](./term-help-ui.md) §4).

### 응답 404 `LEARNING_ARTICLE_UNAVAILABLE`

없는 `articleId`와 게시되지 않은(초안·게시 취소) 글을 **구분하지 않는다.** 둘을 가르면 초안 글의 존재가 드러난다. 화면은 오류 화면이 아니라 "지금은 볼 수 없는 글"이라는 한 문장과 주제 목록 연결을 보인다(spec Edge Cases).

```json
{ "code": "LEARNING_ARTICLE_UNAVAILABLE", "message": "지금은 볼 수 없는 글입니다." }
```

`articleId` 형식이 틀린 경우도 같은 404다. 형식 오류를 400으로 따로 알릴 실익이 없다.

## 3. 용어 설명 하나 (진단 화면 도움말)

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
| `sentence` | FR-008. 연결된 글의 공개 중인 판 본문에 들어 있는 문장과 같다 |
| `articleId` | FR-009. 도움말의 '해당 주제 글로 이동' 대상 |
| `articleTitle` | 연결 문구에 쓴다. 공개 중인 판의 제목이다 |

### 응답 404 `LEARNING_TERM_NOT_READY`

게시된 설명이 없다. 용어 자리만 있는 초안, 연결된 글이 게시 취소된 용어, 없는 `termId`를 가르지 않는다. **화면은 이 코드일 때만 '설명 준비 중'을 보인다**(FR-010).

```json
{ "code": "LEARNING_TERM_NOT_READY", "message": "설명을 준비하고 있습니다." }
```

**그 밖의 모든 실패는 '불러오지 못했다'다.** 연결 실패·시간 초과·5xx·예상하지 못한 코드가 여기에 든다. 화면은 다시 시도 수단을 보이고 조용히 재시도하지 않는다(FR-010, [research](../research.md) R10).

## 4. 이 계약이 하지 않는 것

- 진도·열람 기록을 받지 않는다. 조회 요청에 사용자 식별을 싣지 않으며, 서버도 조회를 사용자별로 남기지 않는다(FR-012).
- 여러 용어를 한 번에 받는 조회를 두지 않는다([research](../research.md) R10).
- 초안·게시 이력을 내보내지 않는다. 그것은 [관리 계약](./learning-admin-api.md)의 범위다.
