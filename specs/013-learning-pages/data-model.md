# 데이터 모델: 배우기

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md) | **Date**: 2026-09-16

글·용어 설명·게시 판·게시 기록의 구조와 규칙을 정한다. 테이블 이름과 컬럼은 구현의 기준이다. 마이그레이션 파일 이름(`V{타임스탬프}__…`)은 구현 묶음에서 작성 시각으로 정한다([backend/CLAUDE.md](../../backend/CLAUDE.md)).

## 1. 한눈에 보기

```text
learning_article (초안 작업본, 글 하나에 한 행)
 ├─ learning_term (초안 용어 설명, 글 1 : 용어 N)
 ├─ learning_article_revision (게시할 때마다 쌓이는 불변 판)
 │     └─ snapshot JSONB (그 시점의 글 + 용어 설명 전체)
 ├─ learning_publication_event (게시·게시 취소 기록, 추가만 한다)
 └─ published_revision_id ─▶ 지금 공개 중인 판 (없으면 초안)

learning_published_term (게시 용어 색인)
   termId → 공개 중인 판의 용어 이름·문장·글
```

일반 사용자 조회는 `published_revision_id`가 가리키는 판과 `learning_published_term`만 읽는다. 관리 화면은 초안 작업본을 읽고 쓴다([research](research.md) R2).

## 2. 식별자 규칙

| 식별자 | 형식 | 바뀌는가 | 근거 |
|---|---|---|---|
| `articleId` | `^[a-z0-9]+(-[a-z0-9]+)*$`, 3~64자 | 만든 뒤 바뀌지 않는다 | R4, FR-009 |
| `termId` | 같은 형식, 2~64자 | 만든 뒤 바뀌지 않고 다른 글로 옮길 수 없다 | R4, FR-008 |

- `articleId`는 모든 글 안에서 유일하다. `termId`는 **모든 글의 초안 용어 전체**에서 유일하다.
- DB 대리키(`id BIGINT`)는 응답에 내보내지 않는다.
- `articleId`가 [002 selection-handoff 계약](../002-sector-weight-selection/contracts/selection-handoff.md) §2의 `topicId`로 쓰인다.

## 3. 테이블

### 3-1. `learning_article` — 초안 작업본

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `id` | `BIGINT` | PK, identity | 내부 대리키 |
| `article_key` | `VARCHAR(64)` | NOT NULL, UNIQUE, 형식 CHECK | `articleId` |
| `title` | `VARCHAR(100)` | NOT NULL | 제목. 초안에서는 빈 문자열을 허용한다 |
| `question` | `VARCHAR(200)` | NOT NULL | 글이 답하는 질문 하나(FR-002). 초안에서는 빈 문자열을 허용한다 |
| `reading_order` | `INTEGER` | NOT NULL, `1..999` | 읽는 순서 제안(Key Entities '커리큘럼 순서'). 유일하지 않다 |
| `body_blocks` | `JSONB` | NOT NULL, 기본 `[]` | §4의 블록 목록 |
| `example` | `JSONB` | NULL 허용 | §5의 예시. 초안에서는 비울 수 있다 |
| `sources` | `JSONB` | NOT NULL, 기본 `[]` | 출처 목록(FR-007). `[{ "label": "...", "url": "https://..." }]`, `url`은 선택 |
| `status` | `VARCHAR(16)` | NOT NULL, `DRAFT`·`PUBLISHED` CHECK | 게시 상태 |
| `published_revision_id` | `BIGINT` | NULL 허용, FK → `learning_article_revision.id` | 공개 중인 판 |
| `draft_version` | `INTEGER` | NOT NULL, 기본 `0` | 초안을 저장할 때마다 1 오른다(R12) |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | NOT NULL | |

**불변식**: `status = 'PUBLISHED'`이면 `published_revision_id`가 있고, `'DRAFT'`이면 없다. 이 관계를 테이블 CHECK로 건다.

### 3-2. `learning_term` — 초안 용어 설명

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `id` | `BIGINT` | PK, identity | |
| `term_key` | `VARCHAR(64)` | NOT NULL, UNIQUE, 형식 CHECK | `termId` |
| `article_id` | `BIGINT` | NOT NULL, FK → `learning_article.id` | 이 용어가 속한 글 하나(글 1 : 용어 N) |
| `label` | `VARCHAR(50)` | NOT NULL | 화면에 보이는 용어 이름. 예: `가장 크게 줄었던 폭` |
| `sentence` | `VARCHAR(300)` | NOT NULL | 설명 문장 한두 개. 초안에서는 빈 문자열을 허용한다 |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | NOT NULL | |

초안에서 용어를 목록에서 빼면 행을 지운다. 이 행은 초안 작업본이며 게시 기록이 아니다. 게시 기록은 판과 이벤트에 남는다.

### 3-3. `learning_article_revision` — 불변 게시 판

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `id` | `BIGINT` | PK, identity | |
| `article_id` | `BIGINT` | NOT NULL, FK | |
| `revision_no` | `INTEGER` | NOT NULL, `(article_id, revision_no)` UNIQUE | 글마다 1부터 오른다 |
| `snapshot` | `JSONB` | NOT NULL | §3-6의 판 내용 |
| `check_version` | `VARCHAR(64)` | NOT NULL | 통과한 검사 규칙 파일 버전. 예: `learning-publish-checks-v1` |
| `sector_map_version` | `VARCHAR(128)` | NOT NULL | 게시 검사 때 대조한 대응표 버전(001의 `mapVersion`) |
| `published_by_user_id` | `BIGINT` | NOT NULL | 게시한 관리 계정의 사용자 식별자(FR-018) |
| `published_at` | `TIMESTAMPTZ` | NOT NULL | |

**만든 뒤 고치거나 지우지 않는다.** 저장소에 수정 메서드를 두지 않는다.

`published_by_user_id`는 사용자 식별자만 담고 이메일·닉네임을 담지 않는다(constitution "기록·개인정보·보관"). `users`에 외래키를 걸지 않는다. 사용자 행의 수명(014)과 게시 기록의 수명을 묶지 않기 위해서다.

### 3-4. `learning_publication_event` — 게시·게시 취소 기록

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `id` | `BIGINT` | PK, identity | |
| `article_id` | `BIGINT` | NOT NULL, FK | |
| `action` | `VARCHAR(16)` | NOT NULL, `PUBLISH`·`UNPUBLISH` CHECK | |
| `revision_id` | `BIGINT` | NULL 허용, FK | `PUBLISH`면 새 판, `UNPUBLISH`면 내려간 판 |
| `actor_user_id` | `BIGINT` | NOT NULL | 수행한 관리 계정(FR-018) |
| `occurred_at` | `TIMESTAMPTZ` | NOT NULL | |

추가만 한다. 이미 초안인 글을 게시 취소하면 상태가 바뀌지 않으므로 행을 남기지 않는다.

### 3-5. `learning_published_term` — 게시 용어 색인

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `term_key` | `VARCHAR(64)` | PK | `termId` |
| `article_id` | `BIGINT` | NOT NULL, FK | |
| `revision_id` | `BIGINT` | NOT NULL, FK | 이 문장이 들어 있는 판 |
| `label` | `VARCHAR(50)` | NOT NULL | 판의 값 |
| `sentence` | `VARCHAR(300)` | NOT NULL | 판의 값 |

게시할 때 그 글의 행을 모두 지우고 새 판의 용어로 다시 채운다. 게시 취소할 때는 그 글의 행을 모두 지운다. 두 작업은 글 상태 변경과 **같은 트랜잭션**이다. 색인과 판이 한순간이라도 어긋나면 SC-003이 깨진다.

### 3-6. 판 내용(`snapshot`)의 모양

```json
{
  "articleId": "returns-and-losses",
  "title": "수익과 손실",
  "question": "가장 크게 줄었던 폭은 왜 시작 금액 대비 손실과 다른가?",
  "readingOrder": 8,
  "bodyBlocks": [
    { "type": "paragraph", "text": "..." },
    { "type": "term", "termId": "max-drawdown" },
    { "type": "hypothetical", "text": "100만 원으로 시작해 120만 원까지 오른 뒤 90만 원으로 내려갔다면 ..." }
  ],
  "example": {
    "sectors": [
      { "code": "SEMICONDUCTOR", "weightTenths": 500 },
      { "code": "BATTERY", "weightTenths": 500 }
    ]
  },
  "sources": [],
  "terms": [
    { "termId": "max-drawdown", "label": "가장 크게 줄었던 폭", "sentence": "..." },
    { "termId": "net-total-return", "label": "비용 차감 후 전체 수익률", "sentence": "..." }
  ]
}
```

위 문장과 섹터는 모양을 보이는 자리표시이며 원고나 추천 예시가 아니다.

## 4. 본문 블록

| `type` | 필드 | 저장 시 검사(400) | 화면 |
|---|---|---|---|
| `heading` | `text` 1~100자 | 길이 | `h2` |
| `paragraph` | `text` 1~2,000자 | 길이 | `p` |
| `term` | `termId` | 형식 | 용어 이름과 설명 문장. 문장은 용어 설명에서 온다 |
| `hypothetical` | `text` 1~1,000자 | 길이 | '가상의 계산 예' 표시가 붙은 상자 |

- 블록은 한 글에 최대 60개다.
- `text`는 일반 텍스트다. 화면은 텍스트 노드로만 그리고 HTML로 해석하지 않는다(R3).
- 알 수 없는 `type`은 저장에서 400으로 거절한다. 모르는 블록을 조용히 버리지 않는다(원칙 VI).
- 이 글에 없는 용어를 가리키는 `term` 블록은 저장은 허용하고 게시에서 거절한다(`TERM_BLOCKS_MATCH`). 운영자가 용어를 먼저 쓰고 나중에 추가하는 순서를 막지 않기 위해서다.

## 5. 예시(`example`)

```json
{ "sectors": [ { "code": "SEMICONDUCTOR", "weightTenths": 500 }, { "code": "BATTERY", "weightTenths": 500 } ] }
```

- **값 표현은 002 계약을 그대로 따른다.** 비중은 0.1%p 단위 정수이고 `1000`이 100%다([selection-handoff](../002-sector-weight-selection/contracts/selection-handoff.md) "값 표현 규칙"). 013이 다른 단위를 쓰면 002로 넘길 때 변환이 생긴다.
- 저장 시 검사(400): `code`는 001의 섹터 코드 형식이고 중복이 없다. `weightTenths`는 `0..1000` 정수다. 섹터는 최대 20개다.
- 게시 시 검사(422): 두 개 이상, 현재 대응표에 모두 있음, 합계 `1000`이다(R7).
- 조회 시 판정: 현재 열린 대응표에 없는 코드가 하나라도 있으면 `available=false`다(R8). 판에 저장된 예시는 바꾸지 않는다.

## 6. 게시 전 검사 규칙 파일 (출발안)

`backend/src/main/resources/learning/publish-checks-v1.yml`에 둔다. **아래 목록은 구현을 시작하기 위한 출발안이다.** 기획서가 확정한 목록이 아니므로 팀이 검토해 바꿀 수 있고, 바꾸면 파일 버전을 올린다(R7).

### 6-1. 금지 표현

| 무리 | 출발 목록 | 근거 |
|---|---|---|
| 추천·권고 | `추천 비중`, `추천합니다`, `권합니다`, `매수하세요`, `매도하세요`, `투자하세요`, `사세요`, `파세요` | FR-005, 원칙 III |
| 확률 표현 | `확률`, `가능성이 높`, `가능성이 큽`, `성공률` | FR-005 |
| 우월성 | `최고의 전략`, `가장 좋은 전략`, `이기는 전략`, `우승 전략`, `승자`, `더 우월` | FR-005, constitution 원칙 III |
| 현재 유효성 | `지금도 유효`, `여전히 유효`, `앞으로도 통`, `지금 사도` | FR-005 |
| 수익 보장 | `수익 보장`, `손실 없`, `보장합니다` | FR-005 |

- 검사는 공백을 하나로 줄인 문자열에서 부분 일치로 한다. 걸린 표현과 위치(제목·질문·블록 순번·용어)를 함께 돌려준다.
- 글 제목 `과거가 미래를 보장하지 않는 이유`가 걸리지 않도록 `보장` 단독은 목록에 넣지 않는다.

### 6-2. 결과성 숫자 패턴

`hypothetical` 블록 **밖**의 텍스트(제목·질문·`heading`·`paragraph`·용어 문장)에서 아래 패턴을 찾는다.

| 패턴 | 예 |
|---|---|
| 숫자 + `%` 또는 `%p` | `12.5%`, `-30%`, `3%p` |
| 숫자 + `원`·`만 원`·`억 원`·`억` | `120만 원` |
| 숫자 + `배` | `2배` |

**허용 예외**: 비중 합계를 설명하는 `100%` 하나는 걸리지 않는다. 그 밖의 예외를 더하려면 파일 버전을 올린다.

이 검사는 패턴 검사이며 한계가 있다([research](research.md) R7 "한계를 적어 둔다").

## 7. 상태 전이

```text
          저장(초안만 바뀜)
        ┌───────────────┐
        ▼               │
   ┌─────────┐  게시(검사 통과)   ┌───────────┐
   │  DRAFT  │ ────────────────▶ │ PUBLISHED │ ◀─┐
   └─────────┘                   └───────────┘   │ 다시 게시(검사 통과, 새 판)
        ▲       게시 취소              │   └──────┘
        └──────────────────────────────┘
```

| 전이 | 조건 | 같은 트랜잭션에서 일어나는 일 |
|---|---|---|
| 저장 | `draftVersion` 일치 | 초안 컬럼·`learning_term` 갱신, `draft_version + 1`. 공개 내용은 바뀌지 않는다 |
| 게시 | `draftVersion` 일치, §6·R7 검사 전부 통과 | 새 판 추가, `status=PUBLISHED`, `published_revision_id` 갱신, 게시 용어 색인을 새 판으로 다시 씀, `PUBLISH` 이벤트 |
| 게시 실패 | 검사 하나 이상 걸림 | **아무것도 바뀌지 않는다.** 판·이벤트를 남기지 않는다 |
| 게시 취소 | 상태가 `PUBLISHED` | `status=DRAFT`, `published_revision_id=NULL`, 그 글의 게시 용어 색인 삭제, `UNPUBLISH` 이벤트 |
| 게시 취소(이미 초안) | 상태가 `DRAFT` | 아무것도 바뀌지 않고 200을 돌려준다 |

- 삭제 전이는 없다(spec Out of Scope, FR-017).
- 게시 취소한 뒤에도 초안 작업본은 그대로 남는다. 운영자는 고쳐서 다시 게시할 수 있다.
- 게시 판 번호는 게시 취소 후 다시 게시해도 이어서 오른다.

## 8. 초기 자리 (마이그레이션이 만든다)

본문·예시·용어 문장은 비운 초안이다([research](research.md) R9). **제목·순서·용어 구성은 PD-16 출발안이다.**

| 순서 | `articleId` | 제목 | 용어(`termId` — 이름) |
|---|---|---|---|
| 1 | `sectors-and-etf` | 섹터와 ETF | `sector` — 섹터, `representative-etf` — 대표 ETF |
| 2 | `weights-and-rebalancing` | 비중과 리밸런싱 | `weight` — 비중, `rebalancing` — 리밸런싱, `fixed-weight` — 내가 정한 비중, `equal-weight` — 똑같이 나누기 |
| 3 | `inverse-volatility` | 변동성에 따라 나누기 | `volatility` — 변동성, `inverse-volatility` — 변동성에 따라 나누기 |
| 4 | `sector-momentum` | 섹터 모멘텀 | `sector-momentum` — 섹터 모멘텀 |
| 5 | `minimum-variance` | 최소분산 | `minimum-variance` — 최소분산 |
| 6 | `backtest` | 백테스트 | `backtest` — 백테스트 |
| 7 | `transaction-cost` | 거래비용 | `transaction-cost` — 거래비용 |
| 8 | `returns-and-losses` | 수익과 손실 | `net-total-return` — 비용 차감 후 전체 수익률, `max-drawdown` — 가장 크게 줄었던 폭 |
| 9 | `past-is-not-future` | 과거가 미래를 보장하지 않는 이유 | 없음 |

- 전략 이름 용어(`fixed-weight`·`equal-weight`·`inverse-volatility`·`sector-momentum`·`minimum-variance`)의 이름은 003 전략 카드와 같은 이름을 쓴다(FR-006).
- 어느 화면의 어느 자리에 어떤 `termId`를 붙일지는 002·003·005가 정한다(FR-008). 이 표는 쓸 수 있는 식별자 목록이다.
- 002 계약의 예시 `topicId: "equal-weight-basics"`는 계약 형식을 보이는 합성 값이다. 실제로 넘어가는 값은 이 표의 `articleId`다.

## 9. 도메인 모델 (백엔드)

`com.ssafy.thorp.learning.domain` 아래 순수 Java로 둔다(ADR-0001).

| 타입 | 위치 | 역할 |
|---|---|---|
| `ArticleId`, `TermId` | `domain/vo` | 형식 검사를 생성자에서 한다 |
| `LearningArticle` | `domain/model` | 초안 작업본. 저장·게시·게시 취소 전이 메서드와 불변식을 가진다 |
| `LearningTerm` | `domain/model` | 초안 용어 설명 |
| `ArticleBlock` | `domain/model` | `sealed interface`. 구현은 `HeadingBlock`·`ParagraphBlock`·`TermBlock`·`HypotheticalBlock` |
| `ExampleAllocation` | `domain/vo` | 섹터 코드와 `weightTenths` 목록 |
| `ArticleRevision` | `domain/model` | 불변 판 |
| `PublishCheckRules` | `domain/model` | 금지 표현·숫자 패턴·예외. 파일에서 읽어 만든다 |
| `PublishCheckResult` | `domain/model` | 걸린 검사 목록. 비어 있으면 통과다 |
| `PublishCheck` | `domain/model` | 초안·규칙·현재 대응표 섹터 코드 집합을 받아 `PublishCheckResult`를 돌려주는 순수 함수. 대응표 조회는 하지 않는다 |

대응표 조회(`SectorMapPort`)·관리 권한(`LearningAdminPolicy`)·규칙 파일 읽기(`PublishCheckRulesPort`)는 `application/port` 인터페이스다. 구현은 `infrastructure`에 둔다.

## 10. 프런트엔드 타입과 저장소 키

| 이름 | 위치 | 뜻 |
|---|---|---|
| `LearningArticleSummary`, `LearningArticle`, `ArticleBlock`, `LearningExample`, `LearningTermEntry` | `frontend/src/types/learning.ts` | 공개 조회 응답과 1:1 |
| `AdminLearningArticleSummary`, `AdminLearningArticle`, `PublishCheckFailure` | `frontend/src/types/learningAdmin.ts` | 관리 응답과 1:1 |
| `LearningReturnState` | `frontend/src/types/learning.ts` | navigation state `learningReturn: { path, label }` (R11) |
| `thorp.learning.adminDraft.{articleId}` | `sessionStorage` | 저장하지 않은 관리 편집 내용(R12). 저장 성공 시 지운다 |

일반 사용자 화면은 저장소에 아무것도 쓰지 않는다. 진도·완료·열람 기록을 두지 않는다(FR-012).

## 11. 요구사항 추적

| 요구사항 | 이 문서의 자리 |
|---|---|
| FR-001 커리큘럼·순서 | §3-1 `reading_order`, §8 |
| FR-002 질문 하나·예시 하나 | §3-1 `question`·`example`, §6 `REQUIRED_FIELDS` |
| FR-003 예시로 002 진입 | §5 |
| FR-004 대응표·비중 합계 | §5, R7·R8 |
| FR-005 금지 표현·실제 결과 없음 | §4 `hypothetical`, §6 |
| FR-007 출처 | §3-1 `sources` |
| FR-008 단일 원본·글 1 : 용어 N·고정 식별자 | §2, §3-2, §3-5, §4 `term` |
| FR-012 진도 기록 없음 | §10 |
| FR-015 서버 보관 | §3 전체 |
| FR-016 관리 계정만 | §9 `LearningAdminPolicy`, [research](research.md) R5 |
| FR-017 초안·게시·게시 취소·검사 | §6, §7 |
| FR-018 게시 기록 | §3-3 `published_by_user_id`, §3-4 |
