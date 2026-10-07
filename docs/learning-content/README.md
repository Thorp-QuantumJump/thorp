# 배우기 원고

배우기 글(spec 013)의 원고를 두는 자리다. 원고의 원본은 서버이고, 이 폴더의 원고는 MR로 검토하고 서버 초안에
넣기 위한 사본이다(spec 013 FR-016, research R25·R26). 1판 아홉 글의 첫 게시 원고는
[`docs/content/learning/`](../content/learning/README.md)에 기록으로 남아 있다.

```text
docs/learning-content/
├── README.md                # 이 문서: 원고 형식, 편집 문법, 자리, 검토 항목
├── current/{articleId}.json # 지금 게시 중인 내용과 같은 원고
└── next/{articleId}.json    # 단계 공개를 기다리는 새 기준 원고
```

## 1. 자리와 옮기는 때

| 자리 | 담는 것 | 언제 바뀌나 |
|---|---|---|
| `current/` | 지금 공개 중인 판과 같은 원고 | 이전 판을 고쳐 게시할 때, 단계를 공개해 `next/` 원고가 옮겨 올 때 |
| `next/` | 아직 공개되지 않은 단계의 새 기준(V2) 원고 | 단계별 원고 MR(`docs(fe)/learning-content-stage-{n}`) |

- 새 기준 원고는 `next/`에서 MR로 검토한다. 관리 화면에는 그 단계를 공개하기 직전에 가져온다.
- **단계를 공개하면** 그 단계 글의 원고를 `next/`에서 `current/`로 옮기는 MR을 낸다. 공개 뒤 원고와 서버가
  같아야 한다.
- **이전 판을 고쳐야 하면**(Thorp 동작이 바뀌어 옛 글의 설명이 틀리게 되는 등, FR-027) `current/`의 V1
  원고를 고쳐 가져와 게시한다. 그 글에 이미 새 원고를 가져와 두었다면, `current/`를 가져와 게시한 뒤
  `next/`를 다시 가져온다. 관리 화면의 'V1 형식으로 되돌리기'는 9월 판을 공개한 적이 있는 글에만 있다.
- 관리 화면에서 고친 내용은 '원고 내보내기'로 받아 같은 자리에 넣고 MR을 낸다. 저장한 뒤 내보낸다. 저장하지
  않은 편집을 내보내면 서버와 다른 원고가 된다.

## 2. 파일 모양

원고는 관리 편집기의 상태를 그대로 쓴 JSON이다. 모양은 초안 저장 요청
([learning-admin-api §5](../../specs/013-learning-pages/contracts/learning-admin-api.md))에서
`expectedDraftVersion`을 빼고 `articleId`를 더한 것이다. **글자 조각 필드만 조각 배열 대신 편집 문법 문자열(§3)로
쓴다.** 필드의 길이·개수 상한은 [data-model §5~§7](../../specs/013-learning-pages/data-model.md)을 따른다.

```json
{
  "articleId": "harbor-tide-basics",
  "format": "V2",
  "title": "물때는 왜 매일 달라질까?",
  "question": "같은 항구인데 물이 들어오는 시각이 날마다 다른 까닭은 무엇일까?",
  "readingOrder": 104,
  "stageKey": "etf-market-basics",
  "stagePosition": 4,
  "blocks": [
    { "type": "keySummary", "lines": ["첫째 줄", "{{용어:tide-gap|물때 간격}}을 먼저 본다", "셋째 줄"] },
    { "type": "heading", "text": "소제목" },
    { "type": "paragraph", "content": "문단 글자{{출처:가상의 항만 안내서|https://example.com/tide}}" },
    { "type": "term", "termId": "tide-gap" },
    { "type": "hypothetical", "content": "가상의 항구에서 물이 3칸 들어왔다가 1칸 빠졌다고 해 보자." },
    { "type": "misconception", "wrong": "오해 문장", "correction": "바로잡는 문장" },
    { "type": "list", "ordered": false, "items": ["항목 하나", "항목 둘"] },
    { "type": "table", "caption": "표 제목", "columns": ["구분", "값"], "rows": [["가", "12%"]] },
    {
      "type": "diagram", "kind": "bar", "title": "도식 제목", "unit": "%",
      "categories": ["가", "나"], "series": [{ "name": "계열", "values": [1, -2.5] }],
      "description": "그림이 말하는 바를 적은 문장"
    },
    {
      "type": "institutionNote", "content": "제도 안내 문장", "asOf": "2026-10-01",
      "source": { "label": "가상의 고시", "url": "https://example.com/rule" }
    }
  ],
  "example": null,
  "sources": [],
  "terms": [
    {
      "termId": "tide-gap", "label": "물때 간격", "aliases": ["tide gap"],
      "meaning": "뜻 문장", "reading": "읽는 법 문장", "caveat": "한계 문장", "transferFrom": null
    }
  ],
  "quiz": [
    {
      "questionId": "q1", "prompt": "질문",
      "choices": [
        { "choiceId": "c2", "text": "틀린 보기", "correct": false },
        { "choiceId": "c3", "text": "정답 보기", "correct": true }
      ],
      "explanation": "풀이 한두 문장"
    }
  ]
}
```

- `format`은 `V1`(9월 형식) 또는 `V2`(새 기준 형식)다. V1 원고는 블록이 `heading`·`paragraph`·`term`·
  `hypothetical` 네 종류이고 글자 필드가 `text`이며, 용어는 `termId`·`label`·`sentence`이고, `quiz`는 빈 배열이다.
  V2 원고는 `sources`가 빈 배열이다. 출처는 글자 옆 출처 표시로 둔다.
- `example`은 예시가 없으면 `null`이다. 있으면 `{ "sectors": [{ "code": "…", "weightTenths": 500 }] }`이고
  `weightTenths`는 0.1%p 정수(1000이 100%)다.
- `readingOrder`는 V2 글에서 쓰지 않지만 저장 요청에 있어야 하므로 그대로 둔다.
- **문제·보기 식별자.** 새 문제·보기는 `questionId`·`choiceId`를 `null`로 쓴다. 서버가 저장할 때 `q{n}`·`c{n}`을
  준다. 가져올 때 지금 서버 초안에 없는 식별자는 `null`로 바뀌어 새 문제·보기가 된다. 공개된 글의 원고는
  내보낸 그대로 가져와야 식별자가 이어져 사용자의 첫 답 기록이 남는다.
- **넘겨받기.** 다른 글이 소유한 용어를 이 글로 옮길 때만 `transferFrom`에 지금 소유 글의 `articleId`를 쓴다
  (data-model §7-2). 넘기기는 이 글이 게시될 때 일어나고, 그 뒤 서버가 이 값을 비운다.
- 모르는 키가 있으면 가져오기가 거절한다. 오타 난 키의 내용이 말없이 사라지지 않게 하려는 것이다.
- `articleId`가 지금 편집 중인 글과 다르면 가져오기가 거절한다.

## 3. 편집 문법

글자 조각 필드(핵심 요약의 줄, 문단·가상의 계산 예·제도 안내의 `content`, 오해의 `wrong`·`correction`, 목록
항목, 표 칸)는 아래 문법을 쓴 문자열이다. 관리 화면만 이 문법을 해석해 글자 조각으로 바꾼다. 서버와 독자
화면은 문법을 모른다(R26).

| 쓰는 것 | 문법 | 바뀌는 조각 |
|---|---|---|
| 용어 언급 | `{{용어:termId\|보이는 글자}}` | `{ "term": "termId", "text": "보이는 글자" }` |
| 출처 표시 | `{{출처:출처 이름\|https://…}}` | `{ "source": { "label": "출처 이름", "url": "https://…" } }` |
| 글자 `{{` | `\{{` | 글자 `{{` |
| 그 밖의 글자 | 그대로 | `{ "text": "…" }` |

- 원고 JSON 안에서는 역슬래시를 한 번 더 쓴다. 글자 `{{`는 `"\\{{"`로 적는다. 관리 화면의 칸에서는 `\{{`로
  친다.
- 출처 표시는 인용한 글자 **바로 뒤**에 둔다. 필드의 첫머리에 둘 수 없다(FR-007).
- 필드마다 쓸 수 있는 조각이 다르다. 가상의 계산 예는 용어 언급만, 목록과 비교 표는 출처 표시만 쓸 수 있다
  (data-model §5-3).
- 닫히지 않은 `{{`, `용어:`·`출처:`가 아닌 표시, `|`가 없는 표시, 비어 있는 식별자·글자·주소는 오류다. 관리
  화면은 그 칸의 줄·열과 함께 오류를 보이고, 오류가 남아 있으면 저장하지 않는다. 원고 가져오기도 같은 오류로
  원고 전체를 거절하고 편집 상태를 바꾸지 않는다.
- 표시 안의 글자에는 `}}`를, 출처 이름에는 `|`를 쓸 수 없다.

## 4. 원고 MR 검토 항목

원고 MR은 다른 팀원 한 명이 검토한다(FR-016). 서버의 게시 전 검사가 잡지 못하는 것을 본다.

- [ ] **이름(FR-006).** 진단 화면과 같은 이름을 쓴다. 다른 이름(통용어·약어·영어 이름)은 용어의 `aliases`에
  두고 본문에 따로 적지 않는다. 화면이 처음 나오는 자리 한 번에만 붙인다.
- [ ] **동작 확인(FR-027, SC-012).** Thorp의 동작(비용 가정, 분배금 반영, 체결 가격, 자료 기간 등)을 설명하는
  문장은 쓰기 전에 실제 동작을 확인했고 기준 시점을 함께 적었다.
- [ ] **용어 언급 표시(SC-013).** 다른 글이 소유한 용어가 나오는 자리를 `{{용어:…}}`로 표시했다. 화면이 자동으로
  찾지 않으므로 표시하지 않은 자리에는 다른 이름과 도움말이 붙지 않는다.
- [ ] **출처(SC-014).** 출처가 필요한 세 경우(제도·규정 수치, 연구 결과나 특정 주장, 빌려 온 표현·예시)에
  `{{출처:…}}`나 문장 안 책 이름이 있다. 인용한 글자 자체에 링크를 걸지 않는다.
- [ ] **보기 글자(spec Edge Cases).** 공개된 문제의 보기 글자를 고쳐 뜻을 바꾸지 않았다. 정답 보기와 틀린 보기의
  글자를 맞바꾸지 않았다. 뜻을 바꿔야 하면 보기를 새로 만든다(`choiceId`를 `null`로).
- [ ] 글마다 질문 하나와 예시 하나를 중심으로 쓴다(FR-002). 실제 과거 성과나 진단 결과를 넣지 않는다(FR-005).
