# 데이터 모델: 배우기

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md) | **Date**: 2026-09-16 · 2판 2026-10-05

글·용어 설명·게시 판·게시 기록(9월 판, 구현됨)에 단계, 새 기준 글(V2)의 본문과 확인 문제, 단계 공개 기록, 로그인 사용자의 나의 학습을 더한다. 테이블 이름과 컬럼은 구현의 기준이다. 마이그레이션 파일 이름(`V{타임스탬프}__…`)은 구현 묶음에서 작성 시각으로 정한다([backend/CLAUDE.md](../../backend/CLAUDE.md)). 이미 적용된 `V20260917101000__learning_content.sql`은 고치지 않고 새 마이그레이션을 더한다(constitution "스키마 변경 규칙").

각 절의 **(2판)** 표시는 2026-10-05에 더한 것이다. 표시가 없는 것은 9월 판 그대로다.

## 1. 한눈에 보기

```text
learning_stage (고정 단계 여섯, 공개 여부·공개일)                              (2판)
 └─ learning_article (초안 작업본, 글 하나에 한 행)
     ├─ 초안 배치 stage_id·stage_position / 공개 배치 published_stage_*        (2판)
     ├─ learning_term (초안 용어 설명, 글 1 : 용어 N, 넘겨받는 중인 용어 포함)
     ├─ learning_article_revision (게시할 때마다 쌓이는 불변 판, edition)
     │     └─ snapshot JSONB (그 시점의 글 + 용어 설명 + 확인 문제 전체)
     ├─ learning_publication_event (게시·게시 취소 기록, 추가만 한다)
     └─ published_revision_id ─▶ 지금 공개 중인 판 (없으면 초안)

learning_published_term (게시 용어 색인: termId → 이름·다른 이름·도움말 문장·글)
learning_release_log (단계 공개·글 추가 기록, 추가만 한다)                      (2판)

users ─┬─ learning_progress    (사용자 × 글: 읽은 위치·완료·익힘)              (2판)
       ├─ learning_answer      (사용자 × 문제: 첫 답)                          (2판)
       ├─ learning_exp_entry   (경험치 장부)                                   (2판)
       └─ learning_badge_award (받은 배지)                                     (2판)
```

일반 사용자 조회는 `published_revision_id`가 가리키는 판, 글 행의 공개 배치, `learning_published_term`, `learning_stage`, `learning_release_log`만 읽는다. 관리 화면은 초안 작업본을 읽고 쓴다([research](research.md) R2, R15). 나의 학습 테이블은 그 사용자의 요청만 읽고 쓴다.

## 2. 식별자 규칙

| 식별자 | 형식 | 바뀌는가 | 근거 |
|---|---|---|---|
| `articleId` | `^[a-z0-9]+(-[a-z0-9]+)*$`, 3~64자 | 만든 뒤 바뀌지 않는다 | R4, FR-009 |
| `termId` | 같은 형식, 2~64자 | 바뀌지 않는다. 다른 글로는 넘기기(R24)로만 옮긴다 | R4, FR-008 |
| `stageKey` **(2판)** | `articleId`와 같은 형식, 3~64자 | 바뀌지 않는다. 마이그레이션만 만든다 | R14, FR-023 |
| `questionId`·`choiceId` **(2판)** | `^q[1-9][0-9]{0,3}$`·`^c[1-9][0-9]{0,3}$`, 글 안에서 유일 | 서버가 주고 바뀌지 않는다. 같은 문제 판정(§6-3)에서 새 문제가 되면 새 식별자를 받는다 | R21 |
| `badgeKey` **(2판)** | `articleId`와 같은 형식, 3~70자(가장 긴 키는 `stage-{stageKey}`로 6자 + 최대 64자) | 바뀌지 않는다. 코드 `BadgeCatalog`가 정한다 | R30 |
| 변경 기록 `entryKey` **(2판)** | `stage:{stageKey}` 또는 `article:{articleId}` | 바뀌지 않는다 | R27 |

- `articleId`는 모든 글 안에서 유일하다. 소유 중인 `termId`는 **모든 글의 초안 용어 전체**에서 유일하다.
- `termId`는 **다른 글이 게시 중인 용어**와도 겹칠 수 없다. 글 A가 초안에서 뺀 용어도 A를 다시 게시하거나 게시 취소하기 전까지는 A의 공개 판과 게시 용어 색인에 남아 있으므로, 다른 글이 그 `termId`를 초안에 넣거나 게시하면 409 `LEARNING_TERM_ID_TAKEN`이다. 게시 용어 색인의 기본키가 `term_key` 하나라서, 막지 않으면 다른 글의 게시가 A의 도움말 행을 덮어써 A 본문과 도움말 문장이 갈라진다(SC-003). **(2판)** 예외는 V2 초안이 `transferFrom`으로 지금 소유 글을 밝힌 경우다(§7-2).
- DB 대리키(`id BIGINT`)는 응답에 내보내지 않는다. 변경 기록도 `entryKey`로 가리킨다.
- `articleId`가 [002 selection-handoff 계약](../002-sector-weight-selection/contracts/selection-handoff.md) §2의 `topicId`로 쓰인다.

## 3. 콘텐츠 테이블

### 3-1. `learning_stage` — 고정 단계 **(2판)**

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `id` | `BIGINT` | PK, identity | 내부 대리키 |
| `stage_key` | `VARCHAR(64)` | NOT NULL, UNIQUE, 형식 CHECK | `stageKey` |
| `number` | `SMALLINT` | NOT NULL, UNIQUE, `≥ 1` | 단계 번호. 다시 쓰지 않는다 |
| `name` | `VARCHAR(40)` | NOT NULL | 단계 이름 |
| `level_label` | `VARCHAR(16)` | NOT NULL | 수준 표시(입문·입문~초급·초급) |
| `summary` | `VARCHAR(120)` | NOT NULL | 한 줄 설명(FR-001) |
| `released_at` | `TIMESTAMPTZ` | NULL 허용 | 공개일. '단계 공개'가 한 번 쓴다 |
| `released_by_user_id` | `BIGINT` | NULL 허용 | 공개한 관리 계정. `users`에 외래키를 걸지 않는다(§3-4와 같은 이유) |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | NOT NULL | |

- CHECK: `(released_at IS NULL) = (released_by_user_id IS NULL)`.
- 번호·이름·수준 표시·한 줄 설명은 마이그레이션만 바꾼다. 관리 API에 이 값을 바꾸는 경로가 없다(FR-023).
- `released_at`은 한 번 쓴 뒤 비우지 않는다. 단계 공개는 되돌리지 않는다(FR-031).

### 3-2. `learning_article` — 초안 작업본

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `id` | `BIGINT` | PK, identity | 내부 대리키 |
| `article_key` | `VARCHAR(64)` | NOT NULL, UNIQUE, 형식 CHECK | `articleId` |
| `title` | `VARCHAR(100)` | NOT NULL | 제목. 초안에서는 빈 문자열을 허용한다 |
| `question` | `VARCHAR(200)` | NOT NULL | 글이 답하는 질문 하나(FR-002). 초안에서는 빈 문자열을 허용한다 |
| `reading_order` | `INTEGER` | NOT NULL, `1..999` | 첫 단계 공개 전 목록의 순서(R15). V2 글에서는 쓰지 않는다 |
| `body_blocks` | `JSONB` | NOT NULL, 기본 `[]` | §5의 블록 목록 |
| `example` | `JSONB` | NULL 허용 | §9의 예시. 비울 수 있다 |
| `sources` | `JSONB` | NOT NULL, 기본 `[]` | V1 글의 출처 목록. V2 초안에서는 빈 배열이어야 한다(출처는 글자 조각, §5-2) |
| `status` | `VARCHAR(16)` | NOT NULL, `DRAFT`·`PUBLISHED` CHECK | 게시 상태 |
| `published_revision_id` | `BIGINT` | NULL 허용, FK → `learning_article_revision.id` | 공개 중인 판 |
| `draft_version` | `INTEGER` | NOT NULL, 기본 `0` | 초안을 저장할 때마다 1 오른다(R12) |
| `format` **(2판)** | `VARCHAR(4)` | NOT NULL, 기본 `V1`, `V1`·`V2` CHECK | 초안 형식(R16) |
| `stage_id` **(2판)** | `BIGINT` | NOT NULL, FK → `learning_stage.id` | 초안 배치: 넣을 단계 |
| `stage_position` **(2판)** | `SMALLINT` | NOT NULL, `1..99` | 초안 배치: 단계 안 순서. 유일하지 않다(같으면 `articleId` 순) |
| `published_stage_id` **(2판)** | `BIGINT` | NULL 허용, FK | 공개 배치: 공개 중인 판이 보이는 단계 |
| `published_stage_position` **(2판)** | `SMALLINT` | NULL 허용 | 공개 배치: 단계 안 순서 |
| `quiz` **(2판)** | `JSONB` | NOT NULL, 기본 `[]` | §6의 확인 문제 초안 |
| `quiz_seq` **(2판)** | `INTEGER` | NOT NULL, 기본 `0`, `≥ 0` | 문제·보기 식별자 카운터. 줄지 않는다(R21) |
| `first_released_at` **(2판)** | `TIMESTAMPTZ` | NULL 허용 | 공개된 단계에 처음 게시된 시각. 한 번 쓴 뒤 비우지 않는다(R27) |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | NOT NULL | |

**불변식**:
- `status = 'PUBLISHED'`이면 `published_revision_id`가 있고, `'DRAFT'`이면 없다(테이블 CHECK).
- **(2판)** `(published_stage_id IS NULL) = (published_revision_id IS NULL)`, `(published_stage_position IS NULL) = (published_stage_id IS NULL)`(테이블 CHECK).

### 3-3. `learning_term` — 초안 용어 설명

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `id` | `BIGINT` | PK, identity | |
| `term_key` | `VARCHAR(64)` | NOT NULL, 형식 CHECK, 유일은 아래 부분 색인 | `termId` |
| `article_id` | `BIGINT` | NOT NULL, FK → `learning_article.id` | 이 용어가 속한 글 하나(글 1 : 용어 N) |
| `label` | `VARCHAR(50)` | NOT NULL | 화면에 보이는 용어 이름. 예: `가장 크게 줄었던 폭` |
| `sentence` | `VARCHAR(300)` | NOT NULL | V1 용어의 설명 문장. V2 용어는 빈 문자열 |
| `aliases` **(2판)** | `JSONB` | NOT NULL, 기본 `[]` | 다른 이름(통용어·약어·영어 이름) 0~4개(FR-006) |
| `meaning` **(2판)** | `VARCHAR(200)` | NOT NULL, 기본 `''` | 뜻 문장. 도움말 창에 보이는 문장(FR-008) |
| `reading` **(2판)** | `VARCHAR(200)` | NOT NULL, 기본 `''` | 읽는 법 문장 |
| `caveat` **(2판)** | `VARCHAR(200)` | NOT NULL, 기본 `''` | 한계 문장. 화면은 앞에 ⚠️를 붙인다 |
| `transfer_from_article_id` **(2판)** | `BIGINT` | NULL 허용, FK → `learning_article.id` | 넘겨받는 중이면 지금 소유 글(R24) |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | NOT NULL | |

**(2판) 유일 제약**: 9월 판의 `uq_learning_term_key`를 지우고 부분 유일 색인 둘로 바꾼다.
- `uq_learning_term_key_owned`: `UNIQUE (term_key) WHERE transfer_from_article_id IS NULL`
- `uq_learning_term_key_incoming`: `UNIQUE (term_key) WHERE transfer_from_article_id IS NOT NULL`

`presentation/support/UniqueConstraintViolations`의 제약 이름 대응도 함께 바꾼다. 초안에서 용어를 목록에서 빼면 행을 지운다. 이 행은 초안 작업본이며 게시 기록이 아니다. 게시 기록은 판과 이벤트에 남는다.

### 3-4. `learning_article_revision` — 불변 게시 판

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `id` | `BIGINT` | PK, identity | |
| `article_id` | `BIGINT` | NOT NULL, FK | |
| `revision_no` | `INTEGER` | NOT NULL, `(article_id, revision_no)` UNIQUE | 글마다 1부터 오른다 |
| `snapshot` | `JSONB` | NOT NULL | §4의 판 내용 |
| `check_version` | `VARCHAR(64)` | NOT NULL | 통과한 검사 규칙 파일 버전. 예: `learning-publish-checks-v2` |
| `sector_map_version` | `VARCHAR(128)` | NOT NULL | 게시 검사 때 대조한 대응표 버전(001의 `mapVersion`) |
| `published_by_user_id` | `BIGINT` | NOT NULL | 게시한 관리 계정의 사용자 식별자(FR-018) |
| `published_at` | `TIMESTAMPTZ` | NOT NULL | |
| `edition` **(2판)** | `VARCHAR(8)` | NOT NULL, 기본 `LEGACY`, `LEGACY`·`CURRENT` CHECK | V1 게시는 `LEGACY`, V2 게시는 `CURRENT`(R16). 2판 전 판은 모두 `LEGACY`다 |

**만든 뒤 고치거나 지우지 않는다.** 저장소에 수정 메서드를 두지 않는다. `published_by_user_id`는 사용자 식별자만 담고 이메일·닉네임을 담지 않는다(constitution "기록·개인정보·보관"). `users`에 외래키를 걸지 않는다. 사용자 행의 수명(014)과 게시 기록의 수명을 묶지 않기 위해서다.

**예시 없는 V2 글의 `sector_map_version`**: 예시 검사를 하지 않으므로 대응표를 읽지 않는다. 이때는 `NOT_CHECKED`를 쓴다. 판정하지 않은 것을 판정한 것처럼 적지 않는다(원칙 VI).

### 3-5. `learning_publication_event` — 게시·게시 취소 기록

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `id` | `BIGINT` | PK, identity | |
| `article_id` | `BIGINT` | NOT NULL, FK | |
| `action` | `VARCHAR(16)` | NOT NULL, `PUBLISH`·`UNPUBLISH` CHECK | |
| `revision_id` | `BIGINT` | NULL 허용, FK | `PUBLISH`면 새 판, `UNPUBLISH`면 내려간 판 |
| `actor_user_id` | `BIGINT` | NOT NULL | 수행한 관리 계정(FR-018) |
| `occurred_at` | `TIMESTAMPTZ` | NOT NULL | |

추가만 한다. 이미 초안인 글을 게시 취소하면 상태가 바뀌지 않으므로 행을 남기지 않는다. 단계 공개도 글마다 `PUBLISH` 행을 남긴다(2판, 바뀐 구조 없음).

### 3-6. `learning_published_term` — 게시 용어 색인

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `term_key` | `VARCHAR(64)` | PK | `termId` |
| `article_id` | `BIGINT` | NOT NULL, FK | |
| `revision_id` | `BIGINT` | NOT NULL, FK | 이 문장이 들어 있는 판 |
| `label` | `VARCHAR(50)` | NOT NULL | 판의 값 |
| `sentence` | `VARCHAR(300)` | NOT NULL | 도움말에 보일 문장. V1 판은 그 용어의 문장, **(2판)** V2 판은 뜻 문장 |
| `aliases` **(2판)** | `JSONB` | NOT NULL, 기본 `[]` | 판의 다른 이름. 다른 글이 이 용어를 언급할 때 처음 나오는 자리에 붙는다(R18) |

게시할 때 그 글의 행을 모두 지우고 새 판의 용어로 다시 채운다. 게시 취소할 때는 그 글의 행을 모두 지운다. **(2판)** 넘기기(R24)는 옛 글의 그 용어 행을 지운 뒤 새 글의 행을 쓴다. 세 작업은 글 상태 변경과 **같은 트랜잭션**이다. 색인과 판이 한순간이라도 어긋나면 SC-003이 깨진다.

### 3-7. `learning_release_log` — 단계 공개·글 추가 기록 **(2판)**

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `id` | `BIGINT` | PK, identity | |
| `entry_key` | `VARCHAR(100)` | NOT NULL, UNIQUE | §2의 `entryKey` |
| `kind` | `VARCHAR(16)` | NOT NULL, `STAGE_RELEASED`·`ARTICLE_ADDED` CHECK | |
| `stage_id` | `BIGINT` | NOT NULL, FK → `learning_stage.id` | 공개한 단계 또는 글을 더한 단계 |
| `article_id` | `BIGINT` | NULL 허용, FK | `ARTICLE_ADDED`일 때만 있다(CHECK) |
| `new_count` | `SMALLINT` | NOT NULL, `≥ 0` | 새 글 편수(이 공개로 처음 공개된 단계에 들어간 글 가운데 `LEGACY` 판이 없던 글). `ARTICLE_ADDED`는 1 |
| `rewritten_count` | `SMALLINT` | NOT NULL, `≥ 0` | 다시 쓴 글 편수. `ARTICLE_ADDED`는 0 |
| `rewritten_titles` | `JSONB` | NOT NULL, 기본 `[]` | 다시 쓴 글의 그때 제목 |
| `article_title` | `VARCHAR(100)` | NULL 허용 | `ARTICLE_ADDED`의 그때 제목 |
| `actor_user_id` | `BIGINT` | NOT NULL | 수행한 관리 계정 |
| `occurred_at` | `TIMESTAMPTZ` | NOT NULL | 단계 공개일 또는 글의 첫 게시 시각 |

추가만 한다. 단계 공개 트랜잭션이 `STAGE_RELEASED`를, 공개된 단계에 처음 게시되는 글의 게시 트랜잭션이 `ARTICLE_ADDED`를 쓴다. '다시 쓴 글'은 단계 공개 전에 그 글의 `LEGACY` 판이 있던 글이다. 공개된 단계에서 옮겨 온 글(`first_released_at`이 이미 있음)은 새 글에도 다시 쓴 글에도 세지 않는다. 그때의 제목을 적어 두므로 뒤에 제목을 고치거나 글을 옮겨도 지난 기록이 바뀌지 않는다(R27).

## 4. 판 내용(`snapshot`)의 모양

### 4-1. V1 판 (9월 판, `edition = LEGACY`)

```json
{
  "articleId": "returns-and-losses",
  "title": "수익과 손실",
  "question": "가장 크게 줄었던 폭은 왜 시작 금액 대비 손실과 다른가?",
  "readingOrder": 8,
  "draftVersion": 5,
  "bodyBlocks": [
    { "type": "paragraph", "text": "..." },
    { "type": "term", "termId": "max-drawdown" },
    { "type": "hypothetical", "text": "100만 원으로 시작해 120만 원까지 오른 뒤 90만 원으로 내려갔다면 ..." }
  ],
  "example": { "sectors": [ { "code": "SEMICONDUCTOR", "weightTenths": 500 }, { "code": "BATTERY", "weightTenths": 500 } ] },
  "sources": [],
  "terms": [
    { "termId": "max-drawdown", "label": "가장 크게 줄었던 폭", "sentence": "..." }
  ]
}
```

`format` 키가 없는 판은 V1 판이다. 2판 전에 쓰인 판에는 이 키가 없으므로, 이 규칙은 판 형식의 판별 규칙이며 빠진 필수 값을 채우는 것이 아니다(T079와 충돌하지 않는다).

### 4-2. V2 판 **(2판, `edition = CURRENT`)**

```json
{
  "format": "V2",
  "articleId": "volatility-is-not-loss",
  "title": "변동성은 손실일까?",
  "question": "...",
  "readingOrder": 204,
  "draftVersion": 7,
  "bodyBlocks": [ "§5의 V2 블록" ],
  "example": null,
  "sources": [],
  "terms": [
    { "termId": "volatility", "label": "변동성", "aliases": ["volatility"], "meaning": "...", "reading": "...", "caveat": "..." }
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
  "reading": { "bodyChars": 2480, "readingMinutes": 5, "minDwellSeconds": 100 }
}
```

- `reading`은 게시할 때 R20의 식으로 계산해 넣는다. 규칙 파일이 바뀌어도 이 판의 값은 그대로다.
- 단계와 단계 안 순서는 판에 넣지 않는다. 글 행의 공개 배치가 갖는다(R15).
- 위 문장·섹터는 모양을 보이는 자리표시이며 원고나 추천 예시가 아니다.

`draftVersion`은 이 판을 게시할 때의 초안 번호다. `LearningAdminService`는 공개 중인 판의 `snapshot.draftVersion`과 현재 초안의 `draftVersion`이 다르면 관리 목록에 `hasUnpublishedChanges: true`를 보낸다. 공개 판이 없는 초안 글은 `false`다. 이 값은 게시 횟수인 `revisionNo`와 다르며, 다시 게시할 때 새 판에 당시 초안 번호를 기록한다. 필수 저장 필드이므로 키가 빠진 판을 0으로 대신하지 않고 읽기 실패로 처리한다.

## 5. 본문 블록

### 5-1. V1 블록 (9월 판)

| `type` | 필드 | 저장 시 검사(400) | 화면 |
|---|---|---|---|
| `heading` | `text` 1~100자 | 길이 | `h2` |
| `paragraph` | `text` 1~2,000자 | 길이 | `p` |
| `term` | `termId` | 형식 | 용어 이름과 설명 문장. 문장은 용어 설명에서 온다 |
| `hypothetical` | `text` 1~1,000자 | 길이 | '가상의 계산 예' 표시가 붙은 상자 |

V1 블록은 한 글에 최대 60개다. V1 초안에는 V1 블록만 둘 수 있다(R16).

### 5-2. 글자 조각 **(2판)**

글자 조각 필드는 아래 조각의 배열이다([research](research.md) R17).

| 조각 | 모양 | 저장 시 검사(400) |
|---|---|---|
| 글자 | `{ "text": "..." }` | 1자 이상 |
| 용어 언급 | `{ "term": "max-drawdown", "text": "가장 크게 줄었던 폭" }` | `termId` 형식, `text` 1~50자 |
| 출처 표시 | `{ "source": { "label": "...", "url": "https://..." } }` | `label` 0~100자(게시 때 1자 이상), `url`은 `http`·`https`, 2,000자 이하. 배열의 첫 조각일 수 없다(인용한 글자 바로 옆, FR-007) |

- 조각 하나에는 위 키 가운데 하나만 있다. 알 수 없는 키는 400이다.
- 필드마다 허용하는 조각이 다르다(§5-3 '조각' 열). 목록과 비교 표는 글자와 출처 표시만 담는다(FR-026).
- 글자 수 상한은 필드 안 모든 조각의 `text` 합으로 센다.

### 5-3. V2 블록 **(2판)**

| `type` | 필드 | 조각 | 저장 시 검사(400) | 화면 |
|---|---|---|---|---|
| `keySummary` | `lines`: 조각 배열의 배열 | 글자·용어·출처 | 줄 0~3개, 줄마다 1~120자 | 글 맨 위 '핵심 요약' 상자 |
| `heading` | `text` | — | 1~100자 | `h2` |
| `paragraph` | `content` | 글자·용어·출처 | 1~2,000자, 조각 40개 이하 | `p` |
| `term` | `termId` | — | 형식 | 용어 이름(처음 나오는 자리면 다른 이름), '뜻'·'읽는 법' 이름표 문장, ⚠️ 한계 문장 |
| `hypothetical` | `content` | 글자·용어 | 1~1,000자 | '가상의 계산 예' 상자 |
| `misconception` | `wrong`, `correction` | 글자·용어·출처 | `wrong` 1~300자, `correction` 1~600자 | '자주 하는 오해' 상자. `wrong`에 '오해' 표시, '가상의 예' 표시 |
| `list` | `ordered`(참·거짓), `items`: 조각 배열의 배열 | 글자·출처 | 항목 1~8개, 항목마다 1~300자 | `ol`·`ul` |
| `table` | `caption`(0~100자), `columns`(2~5개, 칸마다 1~30자), `rows`: 행마다 조각 배열의 배열 | 글자·출처 | 행 1~8개, 행마다 칸 수 = 열 수, 칸마다 0~100자 | 머리 칸 `th scope="col"`이 있는 표, '가상의 예' 표시 |
| `diagram` | `kind`(`bar`·`line`), `title`(0~60자), `unit`(0~10자), `categories`(2~12개, 칸마다 1~12자), `series`(1~3개: `name` 1~20자, `values` 수 배열), `description`(0~300자) | — | `values` 길이 = `categories` 길이, 유한한 수, 절댓값 10⁹ 이하 | 가상 수치로 그린 막대·선, '가상의 예' 표시, 그림 아래 설명 문장. 그리지 못하면 설명 문장만 |
| `institutionNote` | `content`, `asOf`(`YYYY-MM-DD` 또는 `null`), `source`(`{label, url}` 또는 `null`) | 글자·용어·출처 | `content` 1~600자, `source.url`은 `http`·`https` | '제도 안내' 상자, 기준 시점과 '(*출처)' |

- V2 블록은 한 글에 최대 80개다.
- 블록과 조각의 글자는 일반 텍스트다. 화면은 텍스트 노드로만 그리고 HTML이나 마크업으로 해석하지 않는다(R3, FR-026).
- 알 수 없는 `type`은 저장에서 400으로 거절한다. 모르는 블록을 조용히 버리지 않는다(원칙 VI).
- 이 글에 없는 용어를 가리키는 `term` 블록과 어느 글에도 없는 용어를 가리키는 언급 조각은 저장은 허용하고 게시에서 거절한다(§8-2). 운영자가 용어를 나중에 쓰는 순서를 막지 않기 위해서다.
- 도식의 계열 수·점 수와 표의 크기 상한은 720px 미만 화면에서 가로 스크롤 없이 그릴 수 있는 출발값이다(FR-026).

### 5-4. 처음 나오는 자리 **(2판)**

화면의 `firstOccurrences`가 문서 순서(블록 순서 → 블록 안 필드 순서 → 조각 순서)로 훑어 정한다([research](research.md) R18).

| 대상 | 규칙 |
|---|---|
| 다른 이름(FR-006) | 용어마다 처음 나오는 용어 블록이나 언급 조각 한 곳에 `이름 (다른 이름1, 다른 이름2)`로 붙인다. 다른 이름이 없으면 붙이지 않는다 |
| 도움말 표시(FR-009) | 다른 글이 소유한 용어(`owned: false`)의 처음 나오는 언급 조각 한 곳에만 둔다. 이 글이 소유한 용어에는 두지 않는다 |

공개 응답은 언급 조각마다 `aliases`와 `owned`를 채운다. 이 글이 소유한 용어의 다른 이름은 판에서, 다른 글이 소유한 용어의 다른 이름은 게시 용어 색인에서 온다. 그 용어가 게시되어 있지 않으면 `aliases: []`이고 도움말은 '설명 준비 중'을 보인다.

## 6. 확인 문제 **(2판)**

### 6-1. 초안의 모양

```json
[
  {
    "questionId": "q1",
    "prompt": "...",
    "choices": [
      { "choiceId": "c1", "text": "...", "correct": false },
      { "choiceId": null, "text": "...", "correct": true }
    ],
    "explanation": "..."
  }
]
```

| 필드 | 저장 시 검사(400) | 게시 시 검사(§8-2 `QUIZ`) |
|---|---|---|
| 문제 수 | 0~3개 | 2~3개 |
| `questionId` | `null`(새 문제) 또는 **지금 초안에 있는** 식별자. 없는 식별자를 지어 보내면 400 | |
| `prompt` | 0~200자 | 1자 이상 |
| `choices` | 0~5개, 문제 안 `choiceId` 중복 없음 | 2~5개, 모두 1자 이상, `correct: true` 정확히 하나 |
| `choices[].text` | 0~100자 | |
| `explanation` | 0~300자 | 1자 이상(한두 문장은 원고 검토로 확인) |

O/X 문제는 보기 두 개로 쓴다. 관리 화면의 원고 가져오기는 지금 초안에 없는 식별자를 `null`로 바꿔 보낸다([research](research.md) R26).

### 6-2. 식별자 주기

- 저장할 때 `questionId`나 `choiceId`가 `null`인 항목에 서버가 `quiz_seq`를 1씩 올려 `q{n}`·`c{n}`을 준다. 문제와 보기가 같은 카운터를 쓴다. 지운 번호는 다시 쓰지 않는다.
- 식별자 형식(§2)은 `n`이 9999까지다. 줄 번호가 9999를 넘게 되면 그 저장과, 정답이 바뀐 문제에 새 식별자를 줘야 하는 게시(§6-3)는 `400 INVALID_INPUT`(`details.fields`의 `quiz`)으로 거절한다. 지운 번호를 다시 쓰지 않으므로 그 글에는 더 줄 식별자가 없다.
- 저장 응답은 식별자가 채워진 초안을 돌려준다. 화면은 다음 저장에 그 식별자를 그대로 보낸다.

### 6-3. 같은 문제 판정 (게시할 때)

게시하는 초안의 문제마다, 그 글의 **가장 최근 판**에 같은 `questionId`의 문제가 있으면 비교한다.

| 비교 결과 | 처리 |
|---|---|
| 정답 보기 식별자가 같고 보기 식별자 집합이 같다 | 같은 문제. 질문·보기·풀이 글자나 보기 순서만 바뀐 경우다. 사용자의 첫 답 기록이 그대로 보인다 |
| 정답 보기 식별자가 다르거나 보기가 더해지거나 빠졌다 | 새 문제. 서버가 새 `questionId`를 주어 판에 넣고 초안의 그 문제도 같은 식별자로 바꾼다(초안 번호는 그대로). 옛 식별자의 첫 답과 경험치는 남고 화면에서만 사라진다 |
| 가장 최근 판에 없다 | 새 문제 |

초안 번호를 올리지 않으므로 게시 전에 읽은 초안에는 옛 식별자가 남는다. 그 초안을 그대로 저장하면 옛 식별자는 지금 초안에 없는 식별자라 §6-1에 따라 400이다. 넘겨받기를 마친 글의 `transferFrom`도 게시가 비우므로 옛 값으로 저장하면 409다(§7-2). 그래서 관리 화면은 게시에 성공하면 초안을 다시 읽고, 이 탭에 보관한 편집 내용은 게시·단계 공개 응답의 `renamedQuestions`·`transferredTerms`대로 고친다([learning-admin-api](contracts/learning-admin-api.md) §6·§11).

보기의 글자를 고쳐 뜻을 바꾸지 않는다는 규칙(정답 보기와 틀린 보기의 글자를 맞바꾸는 일 포함)은 원고 MR 검토로 확인한다(spec Edge Cases).

## 7. 용어 설명 **(2판)**

### 7-1. 형식별 필드

| 형식 | 필드 | 저장 시 검사(400) | 게시 시 검사 |
|---|---|---|---|
| V1 | `termId`, `label`(≤50), `sentence`(≤300) | 길이 | v1 `TERM_SENTENCE` |
| V2 | `termId`, `label`(≤50), `aliases`(0~4개, 각 1~40자, 중복 없음), `meaning`·`reading`·`caveat`(각 ≤200), `transferFrom`(`articleId` 또는 `null`) | 길이, V2 초안에서 `sentence` 금지 | v2 `TERM_SENTENCES` |

### 7-2. 넘겨받기(`transferFrom`)

- V2 초안의 용어가 다른 글이 소유한(초안 또는 게시 색인) `termId`를 쓰려면 `transferFrom`에 지금 소유 글의 `articleId`를 적는다. 적지 않으면 지금처럼 409 `LEARNING_TERM_ID_TAKEN`이다.
- 저장할 때와 게시할 때 모두 `transferFrom`이 실제 소유 글과 같은지 확인한다. 다르면 409 `LEARNING_TERM_TRANSFER_INVALID`다.
- 같은 `termId`를 동시에 넘겨받으려는 글은 하나뿐이다(`uq_learning_term_key_incoming`).
- 넘기기는 넘겨받는 글이 게시될 때 같은 트랜잭션에서 한다. 옛 글의 초안 용어 행과 게시 색인 행을 지우고, 옛 글의 `draft_version`을 1 올리고, 새 글의 행에서 `transfer_from_article_id`를 비우고, 새 글의 판으로 색인을 쓴다. 옛 글의 판과 초안의 다른 부분은 바꾸지 않는다([research](research.md) R24).

### 7-3. 설정 항목 용어의 식별자 (예약)

3단계 설정 항목 글 네 편이 소유할 용어는 003 등록표가 가리킬 수 있도록 식별자를 미리 정한다(FR-008).

| `termId` | 이름(진단 화면 이름) | 소유 글 | 003 등록표 축 |
|---|---|---|---|
| `lookback-period` | 관측 기간 | `why-lookback-period` | '관측 기간' |
| `skip-recent-period` | 최근 구간 제외 | `skip-recent-month` | '최근 구간 제외 기간' |
| `selection-method` | 선정 방식 | `top-n-or-threshold` | '선정 방식' |
| `weight-cap` | 비중 상한 | `why-weight-cap` | '섹터당 최대 비중'(지금은 `weight`를 가리킨다) |

003 등록표의 축을 이 식별자로 바꾸는 일은 003의 별도 MR이다([plan](plan.md) 교차 의존).

## 8. 게시 전 검사

### 8-1. v1 검사 (9월 판, V1 이전 판 고치기)

`backend/src/main/resources/learning/publish-checks-v1.yml`과 아홉 검사를 그대로 쓴다([research](research.md) R7). 순서는 `REQUIRED_FIELDS`, `EXAMPLE_PRESENT`, `EXAMPLE_SECTOR_COUNT`, `EXAMPLE_SECTOR_IN_MAP`, `EXAMPLE_WEIGHT_SUM`, `TERM_BLOCKS_MATCH`, `TERM_SENTENCE`, `FORBIDDEN_EXPRESSION`, `RESULT_NUMBER_OUTSIDE_HYPOTHETICAL`이다.

**금지 표현(v1·v2 같음)**: 아래 목록은 구현을 시작하기 위한 출발안이다. 기획서가 확정한 목록이 아니므로 팀이 검토해 바꿀 수 있고, 바꾸면 파일 버전을 올린다.

| 무리 | 출발 목록 | 근거 |
|---|---|---|
| 추천·권고 | `추천 비중`, `추천합니다`, `권합니다`, `매수하세요`, `매도하세요`, `투자하세요`, `사세요`, `파세요` | FR-005, 원칙 III |
| 확률 표현 | `확률`, `가능성이 높`, `가능성이 큽`, `성공률` | FR-005 |
| 우월성 | `최고의 전략`, `가장 좋은 전략`, `이기는 전략`, `우승 전략`, `승자`, `더 우월` | FR-005, 원칙 III |
| 현재 유효성 | `지금도 유효`, `여전히 유효`, `앞으로도 통`, `지금 사도` | FR-005 |
| 수익 보장 | `수익 보장`, `손실 없`, `보장합니다` | FR-005 |

- 검사 전에 호환 문자를 표준 문자로 바꾸고(유니코드 NFKC) 공백 문자 연속을 공백 하나로 줄인다. 그 문자열에서 부분 일치로 찾고, 걸린 표현(`matched`)과 위치를 함께 돌려준다.
- 무리마다 규칙 파일에 거절 문장(`message`)이 있다. 화면은 이 문장을 그대로 보인다.
- 글 제목 `과거가 미래를 보장하지 않는 이유`가 걸리지 않도록 `보장` 단독은 목록에 넣지 않는다.

**결과성 숫자 패턴(v1·v2 같음)**: 텍스트는 위와 같이 NFKC로 바꾸고 공백을 줄인 뒤 본다.

| 패턴 | 예 | 걸지 않는 것 |
|---|---|---|
| 숫자 + `%` 또는 `%p` | `12.5%`, `-30%`, `3%p` | |
| 숫자 + `원`·`만 원`·`억 원`·`억` | `120만 원`, `1,000원` | `원칙`(`제1원칙`) |
| 숫자 + `배` (사이 공백 없음) | `2배` | `배분`·`배정`·`배치`·`배열`·`배당` |

허용 예외는 정확히 `100%`인 문자열이다. v1이 한 번이라도 판에 기록된 뒤에는 예외·패턴을 바꿀 때 파일 이름과 `version`을 함께 올리고 옛 파일은 지우지 않는다.

v1의 숫자 검사는 `hypothetical` 블록 **밖**의 텍스트(제목·질문·`heading`·`paragraph`·용어 이름과 문장)를 본다. `REQUIRED_FIELDS`의 "문단 없음"은 `text`가 공백뿐인 `paragraph`를 문단으로 세지 않는다.

v1 검사는 V1 초안만 받는다(§10-1 게시 방식 표). V1 초안에는 V1 블록만 있고(§5-1) 문단과 가상의 계산 예는 글자 조각 하나로 담기므로(§14), v1 검사는 그 조각의 글자를 9월 판의 `text`로 보고 판정과 결과를 1판과 같게 둔다. v1 검사가 V2 초안이나 V2에만 있는 블록을 받으면 호출하는 쪽의 오류로 보고 예외를 던진다. 건너뛰어 통과로 세지 않는다(원칙 VI).

### 8-2. v2 검사 **(2판, V2 글마다 게시와 단계 공개)**

`backend/src/main/resources/learning/publish-checks-v2.yml`. 열일곱 검사를 모두 실행하고 걸린 항목을 전부 돌려준다. 결과는 아래 순서, 같은 검사 안에서는 제목·질문·예시·블록·용어·확인 문제 순서다.

| # | 검사 | 걸리는 조건 | spec |
|---|---|---|---|
| 1 | `REQUIRED_FIELDS` | 제목·질문이 비었다 | FR-002 |
| 2 | `KEY_SUMMARY` | 첫 블록이 `keySummary`가 아니거나, 줄이 세 개가 아니거나, 빈 줄이 있다 | FR-002 |
| 3 | `HEADING_COUNT` | `heading`이 세 개 미만이다 | FR-002 |
| 4 | `HYPOTHETICAL_PRESENT` | `hypothetical`이 없다 | FR-002 |
| 5 | `MISCONCEPTION_PRESENT` | `misconception`이 없다 | FR-002 |
| 6 | `READING_TIME` | 보이는 예상 읽기 분이 4~7이 아니다(R20). `message`에 계산한 분과 글자 수를 적는다 | FR-002 |
| 7 | `EXAMPLE_PRESENT` | `exampleRequired: true`인데 예시가 없다(v2 파일은 `false`) | FR-003 |
| 8 | `EXAMPLE_SECTOR_COUNT` | 예시가 있고 섹터가 두 개 미만이다 | 002 |
| 9 | `EXAMPLE_SECTOR_IN_MAP` | 예시가 있고 섹터가 열린 대응표에 없거나 대응표를 읽지 못했다 | FR-004 |
| 10 | `EXAMPLE_WEIGHT_SUM` | 예시가 있고 비중 합이 `1000`이 아니다 | FR-004 |
| 11 | `TERM_BLOCKS_MATCH` | 이 글의 용어가 `term` 블록으로 정확히 한 번씩 나오지 않거나, `term` 블록이 이 글에 없는 용어를 가리킨다 | FR-008, SC-003 |
| 12 | `TERM_SENTENCES` | 용어의 이름이나 뜻·읽는 법·한계 가운데 빈 것이 있다 | FR-008 |
| 13 | `TERM_MENTION_KNOWN` | 언급 조각의 `termId`가 어느 글의 초안 용어에도, 게시 용어 색인에도 없다 | FR-009 |
| 14 | `QUIZ` | §6-1의 게시 시 조건을 어겼다. 위치는 문제·보기 식별자 | FR-022 |
| 15 | `ELEMENT_FORMAT` | 도식의 `description`이 비었다. 제도 안내의 `asOf`나 `source`가 없거나 `asOf`가 미래다. 출처 표시의 `label`이 비었다 | FR-026, FR-007 |
| 16 | `FORBIDDEN_EXPRESSION` | 아래 '금지 표현 대상'에 §8-1 목록의 표현이 있다 | FR-005, FR-017 |
| 17 | `RESULT_NUMBER_OUTSIDE_HYPOTHETICAL` | 아래 '숫자 검사 대상'에 §8-1 패턴의 숫자가 있다 | FR-005, FR-026 |

**금지 표현 대상**: 제목, 질문, 모든 V2 블록의 글자(오해의 `wrong` 제외), 표의 머리 칸과 제목, 도식의 제목·계열 이름·설명, 용어의 이름·다른 이름·세 문장, 확인 문제의 질문·정답 보기·풀이. **틀린 보기와 오해의 `wrong`은 검사하지 않는다.** 화면이 그 자리에 '틀린 보기'·'오해' 표시를 붙인다(FR-017). 출처 표시의 `label`(외부 자료 제목)도 검사하지 않는다.

**숫자 검사 대상**: 제목, 질문, `heading`, `keySummary`, `paragraph`, `list`, 용어의 이름·다른 이름·세 문장. 다만 `paragraph`와 `list`에서 `(예:`로 시작해 다음 `)`로 닫히는 괄호 안에서 시작하는 숫자는 걸지 않는다. **가상의 계산 예·도식·오해·비교 표·확인 문제·제도 안내는 숫자 검사를 하지 않는다**(FR-005, FR-026).

### 8-3. v2 규칙 파일 (출발안)

```yaml
version: learning-publish-checks-v2
forbiddenExpressions: # §8-1과 같은 다섯 무리와 message
resultNumberPatterns: # §8-1과 같은 세 패턴
allowedExceptions: ['100%']
exampleMarker: '(예:'
exampleRequired: false      # constitution 6.2.0 초안 반려 시 true인 v3를 더한다(R19)
reading:
  charsPerMinute: 500
  minutesRange: [4, 7]
  minDwellDivisor: 3
  minDwellFloorSeconds: 30
quiz:
  questionsRange: [2, 3]
  choicesRange: [2, 5]
```

`reading` 값으로 계산하는 식은 [research](research.md) R20이다. 반올림 전 예상 읽기 초 = 본문 글자 수 ÷ `charsPerMinute` × 60이고, 최소 머문 시간 = max(`minDwellFloorSeconds`, 올림(반올림 전 예상 읽기 초 ÷ `minDwellDivisor`))이다.

### 8-4. 검사 결과의 위치

| `location.part` | 추가 필드 | 가리키는 곳 |
|---|---|---|
| `title` · `question` · `example` | 없음 | 해당 필드 |
| `block` | `index`(0부터), `field`(선택. 예: `lines[1]`, `wrong`, `items[2]`, `rows[0][1]`, `description`) | 본문 블록. 본문 전체를 가리킬 때(소제목 수, 읽기 시간 등)는 `index`가 없다 |
| `term` | `termId`, `field`(선택) | 용어 설명 |
| `quiz` **(2판)** | `questionId`(새 문제면 `index`), `choiceId`(선택), `field`(선택. 문제 안의 `prompt`·`explanation`) | 확인 문제. 질문과 풀이에 같은 표현이 있으면 `field`로 갈라 둘 다 싣는다 |
| `article` **(2판)** | 없음 | 글 전체. 단계 공개와 그 미리 검사의 `FORMAT`(V1 초안)·`PLACEMENT`(초안을 다른 단계로 옮김) 항목만 쓴다. 용어 소유 항목(`TERM_TRANSFER`·`TERM_ID_TAKEN`)은 `term` 위치다 |

## 9. 예시(`example`)

```json
{ "sectors": [ { "code": "SEMICONDUCTOR", "weightTenths": 500 }, { "code": "BATTERY", "weightTenths": 500 } ] }
```

- **값 표현은 002 계약을 그대로 따른다.** 비중은 0.1%p 단위 정수이고 `1000`이 100%다([selection-handoff](../002-sector-weight-selection/contracts/selection-handoff.md) "값 표현 규칙").
- 저장 시 검사(400): `code`는 001의 섹터 코드 형식이고 중복이 없다. `weightTenths`는 `0..1000` 정수다. 섹터는 최대 20개다.
- 게시 시 검사(422): 두 개 이상, 현재 대응표에 모두 있음, 합계 `1000`이다(R7). **(2판)** V2 글은 예시가 있을 때만 검사한다.
- 조회 시 판정: 현재 열린 대응표에 없는 코드가 하나라도 있으면 `available=false`다(R8). 판에 저장된 예시는 바꾸지 않는다.

## 10. 상태 전이

### 10-1. 글

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
| 저장 | `draftVersion` 일치, 형식에 맞는 모양(R16) | 초안 컬럼·`learning_term` 갱신, 새 문제·보기 식별자(§6-2), `draft_version + 1`. 공개 내용은 바뀌지 않는다 |
| 글마다 게시 | `draftVersion` 일치, 아래 게시 방식 표에서 허용, 해당 검사 전부 통과 | 새 판 추가(`edition`), 같은 문제 판정(§6-3), 넘기기(§7-2), `status=PUBLISHED`, `published_revision_id`, 공개 배치 ← 초안 배치, 게시 용어 색인 다시 쓰기, `PUBLISH` 이벤트. **(2판)** 공개된 단계에 처음 게시되면 `first_released_at`과 `ARTICLE_ADDED` 변경 기록 |
| 게시 실패 | 검사 하나 이상 걸림 | **아무것도 바뀌지 않는다.** 판·이벤트를 남기지 않는다 |
| 게시 취소 | 상태가 `PUBLISHED` | `status=DRAFT`, `published_revision_id=NULL`, **(2판)** 공개 배치 비움, 그 글의 게시 용어 색인 삭제, `UNPUBLISH` 이벤트. 사용자 기록은 지우지 않는다 |
| 게시 취소(이미 초안) | 상태가 `DRAFT` | 아무것도 바뀌지 않고 200을 돌려준다 |
| 단계 공개로 게시 **(2판)** | §10-3 | 글마다 위 '글마다 게시'와 같은 일. 다만 변경 기록은 단계에 한 줄 |

**(2판) 글마다 게시의 방식**([research](research.md) R16):

| 초안 형식 | 초안 단계 | 결과 |
|---|---|---|
| V2 | 공개됨 | v2 검사 → `CURRENT` 판 |
| V2 | 공개 전 | 409 `LEARNING_STAGE_NOT_RELEASED` |
| V1 | 공개 전 | v1 검사 → `LEGACY` 판(이전 판 고치기) |
| V1 | 공개됨 | 409 `LEARNING_FORMAT_OUTDATED` |

V1 초안은 기존 아홉 글에만 있다. 새 글은 V2로 만들고, V2에서 V1로 되돌리는 저장은 그 글에 `LEGACY` 판이 있을 때만 받는다(아니면 409 `LEARNING_FORMAT_OUTDATED`).

- 삭제 전이는 없다(spec Out of Scope, FR-017).
- 게시 취소한 뒤에도 초안 작업본은 그대로 남는다. 운영자는 고쳐서 다시 게시할 수 있다.
- 게시 판 번호는 게시 취소 후 다시 게시해도 이어서 오른다.

### 10-2. 단계 **(2판)**

```text
   공개 전 ──('단계 공개', 앞 단계 공개됨, 모든 글 v2 통과)──▶ 공개됨
```

되돌리는 전이는 없다. 공개된 단계의 게시 글이 0편이 되어도 공개됨으로 남고, 화면이 '준비 중'처럼 그린다(FR-031).

### 10-3. 단계 공개 트랜잭션 **(2판)**

[research](research.md) R23의 순서다.

1. `learning_stage` 행 잠금. 이미 공개됐으면 409 `LEARNING_STAGE_ALREADY_RELEASED`. 앞 번호 단계가 공개 전이면 409 `LEARNING_STAGE_ORDER`.
2. 공개 집합 = (초안 단계 = 이 단계인 글) ∪ (공개 배치 = 이 단계인 글). 비었으면 409 `LEARNING_STAGE_EMPTY`. 집합의 글 행을 `id` 순서로 잠근다.
3. 요청의 글 목록이 공개 집합과 같고 `expectedDraftVersion`이 모두 맞는지 본다. 아니면 409 `LEARNING_STAGE_CHANGED`.
4. 글마다 형식(V2인가, 아니면 `FORMAT`)·초안 단계(이 단계인가, 아니면 `PLACEMENT`)·용어 소유(넘겨받기 소유 불일치 `TERM_TRANSFER`, 다른 글이 게시 중인 용어 `TERM_ID_TAKEN`)·v2 검사(§8-2)를 돌린다. 하나라도 걸리면 아무것도 쓰지 않고 422 `LEARNING_STAGE_CHECK_FAILED`로 글마다 걸린 항목을 모두 돌려준다.
5. 시각 하나(`releasedAt`)로 글마다 게시(§10-1), 넘기기, 비어 있는 `first_released_at`에 `releasedAt`, `released_at`·`released_by_user_id`, `STAGE_RELEASED` 변경 기록 한 줄을 쓴다. 편수는 `first_released_at`이 비어 있던 글만 세고, 그 가운데 `LEGACY` 판이 있던 글이 다시 쓴 글이다. 공개된 단계에서 옮겨 온 글은 세지 않는다.

## 11. 나의 학습 테이블 **(2판)**

모든 테이블의 `user_id`는 `BIGINT NOT NULL`이고 `users(id)`를 외래키로 가리키며 `ON DELETE CASCADE`다([research](research.md) R28). 일반 사용자 요청은 로그인 주체의 `user_id` 행만 읽고 쓴다. 관리 API와 집계 조회는 이 테이블을 읽지 않는다(FR-020).

### 11-1. `learning_progress` — 사용자 × 글

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `user_id` | `BIGINT` | PK 일부, FK | |
| `article_id` | `BIGINT` | PK 일부, FK → `learning_article.id` | |
| `furthest_percent` | `SMALLINT` | NOT NULL, `0..100` | 가장 멀리 읽은 위치(본문 길이에 대한 비율). 더 큰 값만 남긴다 |
| `last_opened_at` | `TIMESTAMPTZ` | NOT NULL | 마지막으로 연 시각(위치 기록·완료 때 갱신) |
| `completed_at` | `TIMESTAMPTZ` | NULL 허용 | 완료 시각. 한 번 쓴 뒤 '학습 초기화' 전에는 비우지 않는다 |
| `all_answered_at` | `TIMESTAMPTZ` | NULL 허용 | 처음 그 글의 문제에 모두 답한 시각(R29) |
| `mastered_at` | `TIMESTAMPTZ` | NULL 허용 | 익힘 시각 |
| `mastered_via` | `VARCHAR(16)` | NULL 허용, `FIRST_ANSWER`·`RETRY` CHECK | 익힘 경로. `(mastered_at IS NULL) = (mastered_via IS NULL)` CHECK |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | NOT NULL | |

완료 요청은 행이 없으면 만들고 `furthest_percent = 100`으로 둔다(본문 끝을 지났다).

### 11-2. `learning_answer` — 첫 답

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `user_id` | `BIGINT` | PK 일부, FK | |
| `article_id` | `BIGINT` | PK 일부, FK | |
| `question_key` | `VARCHAR(8)` | PK 일부 | `questionId` |
| `choice_key` | `VARCHAR(8)` | NOT NULL | 고른 보기 |
| `correct` | `BOOLEAN` | NOT NULL | 서버가 그때 공개 판으로 판정한 결과 |
| `answered_at` | `TIMESTAMPTZ` | NOT NULL | |

추가만 한다(`ON CONFLICT DO NOTHING` 뒤 있는 행을 읽는다). 고른 보기를 바꾸는 경로가 없다(FR-022). 다시 풀기의 답은 저장하지 않는다.

### 11-3. `learning_exp_entry` — 경험치 장부

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `id` | `BIGINT` | PK, identity | |
| `user_id` | `BIGINT` | NOT NULL, FK | |
| `kind` | `VARCHAR(24)` | NOT NULL, `ARTICLE_COMPLETED`·`ANSWER_CORRECT`·`ALL_CORRECT`·`STAGE_COMPLETED` CHECK | |
| `ref_key` | `VARCHAR(160)` | NOT NULL | 대상 키. `article:{articleId}`, `answer:{articleId}:{questionId}`, `all:{articleId}`, `stage:{stageKey}` |
| `article_id` | `BIGINT` | NULL 허용, FK | 글에 딸린 종류(완료·정답·모두 맞힘)는 있다. 정답 몫 상한 합계에 쓴다 |
| `stage_id` | `BIGINT` | NULL 허용, FK | `STAGE_COMPLETED`만 |
| `points` | `SMALLINT` | NOT NULL, `1..100` | |
| `earned_at` | `TIMESTAMPTZ` | NOT NULL | |

- UNIQUE `(user_id, kind, ref_key)`: 같은 일로 한 번(FR-028).
- 색인 `(user_id, article_id)`: 정답 몫 상한 계산.
- 추가만 한다. 지우는 경로는 '학습 초기화'뿐이다.

### 11-4. `learning_badge_award` — 받은 배지

| 컬럼 | 형 | 제약 | 뜻 |
|---|---|---|---|
| `user_id` | `BIGINT` | PK 일부, FK | |
| `badge_key` | `VARCHAR(70)` | PK 일부 | §12-4의 `badgeKey`(§2, 3~70자) |
| `earned_at` | `TIMESTAMPTZ` | NOT NULL | 획득 날짜 |

추가만 한다. 콘텐츠가 바뀌어도 지우지 않는다(FR-024).

### 11-5. 학습 초기화

`DELETE /learning/me`가 한 트랜잭션에서 네 테이블의 그 사용자 행을 모두 지운다. 되살리는 경로는 없다(FR-025). 초기화 뒤에는 모든 글이 '안 읽음', 레벨 1, 배지 없음이고, 같은 글·문제로 경험치와 배지를 처음부터 다시 받는다.

## 12. 계산 규칙 **(2판)**

### 12-1. 세는 범위

| 이름 | 정의 |
|---|---|
| 공개된 글 | `CURRENT` 판이 게시 중인 글. 공개 배치 단계는 늘 공개된 단계다(§10-1 표) |
| 공개된 용어 | 공개된 글의 게시 용어 색인 행 |
| 공개된 문제 | 공개된 글의 공개 판에 있는 문제 |
| 비어 있지 않은 공개 단계 | 공개된 단계 가운데 공개된 글이 한 편 이상인 단계 |
| 읽은 글 수 | 공개된 글 가운데 `completed_at`이 있는 글 |
| 익힌 용어 수 | 공개된 용어 가운데 그 용어의 글이 익힘인 것 |
| 첫 답 정답 수 | 공개된 문제 가운데 첫 답이 맞은 것 |
| 모두 맞힘 글 수 | 공개된 글 가운데 `ALL_CORRECT` 장부 줄이 있는 글 |
| 단계별 완료 | 공개된 단계마다 (그 단계의 공개된 글 가운데 완료한 수, 그 단계의 공개된 글 수) |
| 받은 배지 수 | `learning_badge_award` 행 수 |

게시 취소된 글과 지운 문제의 기록은 남기되 위 수에 넣지 않는다. 다시 게시되면 다시 센다(FR-020). `LEGACY` 판 글은 어디에도 세지 않는다(FR-031).

### 12-2. 글 상태·번호·순서

- 상태: `learning_progress` 행이 없으면 `UNREAD`, `completed_at`이 있으면 `COMPLETED`, 그 밖은 `READING`이다. `READING`의 비율은 `furthest_percent`를 1~99로 묶어 보인다.
- 읽는 순서: (단계 번호, `published_stage_position`, `articleId`).
- 글 번호: `{단계 번호}-{그 단계의 공개된 글 가운데 읽는 순서 순번}`. 이전 판 글에는 번호가 없다.
- 다음 글: 읽는 순서상 이 글 뒤의 첫 공개된 글. 없으면 `null`.
- 이어서 읽기: `READING` 글 가운데 `last_opened_at`이 가장 늦은 글을 기록된 위치에서 연다. 없으면 읽는 순서상 첫 `UNREAD` 글을 맨 위에서 연다. 공개된 글을 모두 완료했으면 '지금 공개된 글을 모두 읽었습니다'이고, 공개되지 않은 단계가 있으면 '다음 단계는 준비 중입니다'를 덧붙인다(FR-020).

### 12-3. 경험치와 레벨

| 종류 | 점수 | 언제 |
|---|---|---|
| `ARTICLE_COMPLETED` | 20 | 글을 처음 완료할 때 |
| `ANSWER_CORRECT` | min(60 ÷ 답할 때 문제 수, 60 − 그 글에서 이미 받은 정답 몫) | 첫 답이 맞을 때. 그 글의 `all_answered_at`이 이미 있으면 주지 않는다 |
| `ALL_CORRECT` | 20 | 처음 모든 문제에 답한 순간 그 문제를 모두 첫 답에서 맞혔을 때 |
| `STAGE_COMPLETED` | 50 | 비어 있지 않은 공개 단계의 공개된 글을 모두 완료했을 때(정산, §12-5) |

**익힘**(FR-022): 첫 답이 맞은 순간 그 글이 아직 익힘이 아니면 `mastered_at`을 지금으로, `mastered_via`를 `FIRST_ANSWER`로 둔다. 모든 문제에 답하기 전이어도 하나를 맞히면 익힘이다. 공개 판의 문제에 모두 답했고 그 첫 답이 하나도 맞지 않았으며 아직 익힘이 아닌 글만 다시 풀기를 받고, 다시 풀기의 답이 맞으면 `RETRY`로 익힘이 된다. 다시 풀기는 답을 저장하지 않고(§11-2) 경험치 장부 줄도 만들지 않는다(FR-028). 익힘은 문제가 바뀌거나 글이 게시 취소되어도 비우지 않고 '학습 초기화'로만 지운다(FR-020, spec Edge Cases).

레벨 문턱(FR-029):

| 레벨 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 이상 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 문턱 | 0 | 100 | 300 | 600 | 1,000 | 1,500 | 2,100 | 2,800 | 3,600 | 4,500 | 4,500 + 900 × (레벨 − 10) |

- 현재 레벨 = 장부 합계 ≥ 문턱인 가장 높은 레벨. 합계가 정확히 100이면 레벨 2다.
- 경험치 최대치 = 100 × 공개된 글 수 + 50 × 비어 있지 않은 공개 단계 수.
- 보이는 가장 높은 레벨 = max(최대치로 닿는 레벨, 현재 레벨). 그 다음 레벨이 'N'이다.
- 사유: 공개되지 않은 단계가 있으면 `MORE_STAGES`, 여섯 단계를 모두 공개했으면 `MORE_ARTICLES`.
- 검산: 1·2단계 8편이 공개되면 최대치 900, 보이는 레벨 4, N = 5. 27편·여섯 단계면 3,000, 레벨 8, N = 9(spec FR-029의 예와 같다).

### 12-4. 배지 31개

| `badgeKey` | 갈래 | 이름 | 받는 조건 |
|---|---|---|---|
| `level-2` ~ `level-8` | 레벨 | 레벨 2 ~ 레벨 8 | 현재 레벨이 그 레벨 이상(7개) |
| `read-1`·`read-5`·`read-10`·`read-20` | 읽은 글 | 읽은 글 1편·5편·10편·20편 | 읽은 글 수 ≥ 기준 |
| `read-27` | 읽은 글 | 읽은 글 27편 | 여섯 단계가 모두 공개되었고 읽은 글 수 ≥ 27 |
| `correct-1`·`correct-10`·`correct-25`·`correct-50` | 첫 답 정답 | 첫 답 정답 1개·10개·25개·50개 | 첫 답 정답 수 ≥ 기준 |
| `perfect-1`·`perfect-5`·`perfect-15` | 모두 맞힘 | 모두 맞힘 1편·5편·15편 | 모두 맞힘 글 수 ≥ 기준 |
| `stage-{stageKey}` | 단계 완주 | {n}단계 완주 | 그 단계가 비어 있지 않은 공개 단계이고 공개된 글을 모두 완료(6개) |
| `terms-1`·`terms-10`·`terms-25` | 익힌 용어 | 익힌 용어 1개·10개·25개 | 익힌 용어 수 ≥ 기준 |
| `terms-all` | 익힌 용어 | 그때 게시된 용어 전체 | 여섯 단계가 모두 공개되었고, 공개된 용어가 하나 이상이며, 익힌 용어 수 = 공개된 용어 수 |
| `persistence` | 특별 | 끈기 | `mastered_via = RETRY`인 글이 하나 이상 |
| `exploration` | 특별 | 탐험 | 여섯 단계가 모두 공개되었고, 비어 있지 않은 공개 단계가 하나 이상이며, 그 단계마다 완료한 글이 하나 이상 |

**상태와 '준비 중' 사유**: 받은 배지는 `EARNED`다. 받지 않은 배지는 아래 표로 `PREPARING`인지 정하고, 아니면 `NOT_YET`(기준과 진행 n/m)이다. 배지의 `PREPARING`은 '지금 공개된 콘텐츠로는 받을 수 없음'이다. 커리큘럼·나의 학습의 단계 상태 `PREPARING`(단계 공개 전)과 이름만 같고, 단계가 공개된 뒤에도 배지는 `PREPARING`일 수 있다(예: `STAGE_EMPTY`, `MORE_ARTICLES`).

| 배지 | `PREPARING` 조건 | 사유 코드 |
|---|---|---|
| `level-N` | N > 보이는 가장 높은 레벨 | `MORE_STAGES` 또는 `MORE_ARTICLES`(§12-3) |
| 수를 세는 배지(`read-1`~`read-20`, `correct-*`, `perfect-*`, `terms-1`~`terms-25`) | 기준 > 지금 공개된 콘텐츠로 닿을 수 있는 최대(공개된 글 수·공개된 문제 수·공개된 글 수·공개된 용어 수) | `MORE_STAGES` 또는 `MORE_ARTICLES` |
| `read-27`·`terms-all`·`exploration` | 여섯 단계를 모두 공개하기 전 | `ALL_STAGES` |
| 〃 | 모두 공개한 뒤에도 채울 수 없음(예: 공개된 글이 27편 미만) | `MORE_ARTICLES` |
| `stage-{key}` | 그 단계가 공개 전 | `STAGE_NOT_RELEASED` |
| 〃 | 공개됐지만 공개된 글이 0편 | `STAGE_EMPTY` |
| `persistence` | 없음 | — |

화면은 사유 코드를 [design-notes](design-notes.md) §7의 문구로 바꾼다. 같은 갈래의 `PREPARING` 배지는 받을 수 있게 되는 때의 문구가 같은 것끼리만 한 줄로 묶을 수 있다(FR-024). 단계 완주 갈래는 문구가 단계마다 다르므로 묶지 않고 여섯 개를 낱장으로 보인다(design-notes §3.3).

### 12-5. 정산 순서

학습 행동 요청과 `GET /learning/me`가 같은 순서로 정산한다([research](research.md) R30).

1. 비어 있지 않은 공개 단계마다 단계 완주를 판정해 `STAGE_COMPLETED`를 더한다(`ON CONFLICT DO NOTHING`).
2. 장부 합계로 현재 레벨을 계산한다.
3. 배지 조건을 판정해 새로 채워진 배지를 더한다(`ON CONFLICT DO NOTHING`).
4. 이번 요청에서 새로 생긴 장부 줄·배지·레벨 변화를 응답의 `gained`로 돌려준다.

## 13. 초기 자리 (마이그레이션이 만든다)

### 13-1. 9월 판

본문·예시·용어 문장은 비운 초안 아홉 글과 용어 열네 개([research](research.md) R9). 전략 이름 용어의 이름은 003 전략 카드와 같은 이름을 쓴다(FR-006). 002 계약의 예시 `topicId: "equal-weight-basics"`는 계약 형식을 보이는 합성 값이다.

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

### 13-2. 2판: 여섯 단계 **(2판)**

| `stageKey` | 번호 | 이름 | 수준 | 한 줄 설명 |
|---|---|---|---|---|
| `etf-market-basics` | 1 | ETF와 국내 시장 기초 | 입문 | ETF가 무엇을 담고 있고 가격과 비용이 어떻게 정해지는지 봅니다. |
| `returns-and-risk` | 2 | 수익과 위험 읽기 | 입문 | 수익률, 하락 폭, 회복 기간, 변동성을 읽는 법을 익힙니다. |
| `allocation-rules` | 3 | 배분 규칙과 설정 | 입문~초급 | 다섯 가지 배분 규칙과 설정 항목이 결과를 어떻게 바꾸는지 봅니다. |
| `backtest-and-costs` | 4 | 백테스트와 비용 | 초급 | 같은 조건에서 비교하는 법과 거래비용, 회전율의 영향을 봅니다. |
| `pitfalls-and-validation` | 5 | 함정과 검증 | 초급 | 좋아 보이는 과거 결과를 믿기 전에 확인할 점을 봅니다. |
| `economy-and-regimes` | 6 | 경제환경과 국면 | 초급 | 시장 흐름의 분야와 비슷했던 때를 찾는 방법을 봅니다. |

모두 공개 전(`released_at = NULL`)으로 만든다.

### 13-3. 2판: 27편의 단계 배치 **(2판)**

기존 아홉 글은 `format = V1`을 그대로 두고 단계와 단계 안 순서만 정한다. 지금 게시 중인 글은 공개 배치도 같은 값으로 채운다. 새 글 18편은 `format = V2`, 빈 본문의 초안, `reading_order` = 단계 번호 × 100 + 단계 안 순서(999를 넘으면 999)로 만든다. V2 글에서 이 값은 쓰지 않으며 테이블 CHECK(`1..999`)를 지키기 위한 값이다. 제목은 FR-001 출발안이며 PD-16 확정에 따라 관리 화면에서 고칠 수 있다.

| 번호 | `articleId` | 제목 | 구분 |
|---|---|---|---|
| 1-1 | `sectors-and-etf` | 섹터와 ETF | 기존 |
| 1-2 | `etf-tracking-difference` | ETF 가격은 왜 지수와 똑같이 움직이지 않을까? | 새 글 |
| 1-3 | `etf-distributions` | 분배금을 받으면 돈이 늘어난 걸까? | 새 글 |
| 1-4 | `etf-fees` | ETF 보수는 어디로 빠져나갈까? | 새 글 |
| 2-1 | `returns-and-losses` | 수익과 손실 | 기존 |
| 2-2 | `same-return-different-feel` | 같은 수익률인데 왜 느낌이 다를까? | 새 글 |
| 2-3 | `recovery-period` | 떨어진 뒤 얼마나 걸려 돌아왔을까? | 새 글 |
| 2-4 | `volatility-is-not-loss` | 변동성은 손실일까? | 새 글 |
| 3-1 | `weights-and-rebalancing` | 비중과 리밸런싱 | 기존 |
| 3-2 | `diversification-and-correlation` | 나누기만 하면 분산일까? | 새 글 |
| 3-3 | `inverse-volatility` | 변동성에 따라 나누기 | 기존 |
| 3-4 | `sector-momentum` | 섹터 모멘텀 | 기존 |
| 3-5 | `minimum-variance` | 최소분산 | 기존 |
| 3-6 | `why-lookback-period` | 관측 기간은 왜 고르는 항목일까? | 새 글 |
| 3-7 | `skip-recent-month` | 왜 최근 한 달을 빼고 볼까? | 새 글 |
| 3-8 | `top-n-or-threshold` | 상위 몇 개와 기준 수익률 이상은 어떻게 다를까? | 새 글 |
| 3-9 | `why-weight-cap` | 비중 상한은 왜 둘까? | 새 글 |
| 4-1 | `backtest` | 백테스트 | 기존 |
| 4-2 | `same-conditions` | 같은 조건에서 비교하기란 무엇일까? | 새 글 |
| 4-3 | `transaction-cost` | 거래비용 | 기존 |
| 4-4 | `turnover` | 회전율이 높으면 왜 결과가 뒤집힐까? | 새 글 |
| 4-5 | `short-history` | 자료 기간이 짧으면 무엇을 알 수 없을까? | 새 글 |
| 5-1 | `past-is-not-future` | 과거가 미래를 보장하지 않는 이유 | 기존 |
| 5-2 | `best-of-many-settings` | 50개 설정 중 1등은 왜 믿기 어려울까? | 새 글 |
| 5-3 | `neighboring-settings` | 이웃 설정도 좋은가? | 새 글 |
| 6-1 | `market-flow-fields` | 시장 흐름의 7개 분야는 무엇을 보여줄까? | 새 글 |
| 6-2 | `similar-periods` | '비슷했던 때'는 어떻게 찾을까? | 새 글 |

- 새 글의 용어는 원고와 함께 정한다. 그 가운데 설정 항목 네 개는 §7-3의 예약 식별자를 쓴다.
- 기존 용어를 처음 설명하는 글이 새 글로 바뀌면(예: `volatility` → `volatility-is-not-loss`) 그 새 글의 원고가 `transferFrom`으로 넘겨받는다(§7-2).
- 운영 DB에 이 표 밖의 글이 있으면 마이그레이션이 실패한다. 배포 전 확인은 [research](research.md) R15다.

## 14. 도메인 모델 (백엔드)

`com.ssafy.thorp.learning.domain` 아래 순수 Java로 둔다(ADR-0001). 9월 판 타입은 그대로 두고 아래를 더하거나 넓힌다.

| 타입 | 위치 | 역할 |
|---|---|---|
| `ArticleId`, `TermId`, `ExampleAllocation` | `domain/vo` | 9월 판 |
| `StageKey`, `QuestionId`, `ChoiceId`, `BadgeKey` **(2판)** | `domain/vo` | 형식 검사를 생성자에서 한다 |
| `LearningArticle` | `domain/model` | 초안 작업본. **(2판)** `format`, 초안·공개 배치, 확인 문제, 식별자 주기(§6-2) |
| `LearningTerm` | `domain/model` | 초안 용어 설명. **(2판)** 다른 이름·세 문장·`transferFrom` |
| `ArticleBlock` | `domain/model` | `sealed interface`. 9월 판 네 종류에 **(2판)** `KeySummaryBlock`·`MisconceptionBlock`·`ListBlock`·`TableBlock`·`DiagramBlock`·`InstitutionNoteBlock`. `ParagraphBlock`·`HypotheticalBlock`은 `RichText`를 담고, V1은 글자 조각 하나로 읽고 쓴다 |
| `RichText`, `Run`(`TextRun`·`TermMentionRun`·`SourceRun`) **(2판)** | `domain/model` | 글자 조각(§5-2) |
| `ArticleFormat`, `Edition`, `Placement` **(2판)** | `domain/model` | R15·R16 |
| `QuizQuestion`, `QuizChoice`, `QuizIdentity` **(2판)** | `domain/model` | 확인 문제와 같은 문제 판정(§6-3) |
| `ReadingEstimate` **(2판)** | `domain/model` | 본문 글자 수·분·최소 머문 시간(R20) |
| `ArticleRevision`, `ArticleSnapshot` | `domain/model` | 불변 판. **(2판)** `edition`, 확인 문제, `reading` |
| `PublishCheck`, `PublishCheckRules`, `PublishCheckResult` | `domain/model` | v1 검사(9월 판) |
| `PublishCheckV2`, `PublishCheckRulesV2` **(2판)** | `domain/model` | v2 검사(§8-2). 대응표·용어 존재 여부는 인자로 받는다 |
| `PublishMode` **(2판)** | `domain/model` | 글마다 게시의 방식 판정(§10-1 표) |
| `LearningStage`, `StageRelease`, `ReleaseLogEntry` **(2판)** | `domain/model` | 단계, 단계 공개의 전제 판정(§10-3), 변경 기록 |
| `LearnerProgress`, `FirstAnswer`, `ExpEntry`, `ExpLedger` **(2판)** | `domain/model` | 나의 학습 사실과 정답 몫 상한(R29) |
| `Level` **(2판)** | `domain/model` | 문턱과 보이는 레벨(§12-3) |
| `BadgeCatalog`, `BadgeDefinition`, `BadgeStatus`, `PreparingReason` **(2판)** | `domain/model` | 배지 31개와 상태(§12-4) |
| `ContentScope`, `LearnerFacts`, `Settlement` **(2판)** | `domain/model` | 공개된 콘텐츠의 크기, 사용자의 사실, 정산 순수 함수(§12-5) |

대응표 조회(`SectorMapPort`)·관리 권한(`LearningAdminPolicy`)·규칙 파일 읽기(`PublishCheckRulesPort`, **(2판)** v1·v2 둘 다)는 `application/port` 인터페이스다. **(2판)** 새 저장소 인터페이스는 `LearningStageRepository`, `ReleaseLogRepository`, `LearnerProgressRepository`, `LearnerAnswerRepository`, `ExpLedgerRepository`, `BadgeAwardRepository`다. 장부·답·배지·변경 기록 저장소에는 수정 메서드가 없고, 사용자 기록 저장소의 삭제 메서드는 `deleteAllForUser` 하나다.

## 15. 프런트엔드 타입과 저장소 키

| 이름 | 위치 | 뜻 |
|---|---|---|
| `LearningArticleSummary`, `LearningArticle`, `ArticleBlock`, `LearningExample`, `LearningTermEntry` | `frontend/src/types/learning.ts` | 공개 조회 응답과 1:1. **(2판)** `Curriculum`, `StageView`, `ReleaseLogEntry`, `Run`, `QuizQuestion`, `edition`을 더한다 |
| `LearnerOverview`, `LearnerArticleRecord`, `CompletionResult`, `AnswerResult`, `BadgeView`, `LevelView`, `PreparingReason` **(2판)** | `frontend/src/types/learningMe.ts` | 나의 학습 응답과 1:1 |
| `AdminLearningArticleSummary`, `AdminLearningArticle`, `PublishCheckFailure` | `frontend/src/types/learningAdmin.ts` | 관리 응답과 1:1. **(2판)** `format`, 배치, 확인 문제 초안, `StageAdminView`, `StageReadiness`, `ManuscriptFile` |
| `LearningReturnState` | `frontend/src/types/learning.ts` | navigation state `learningReturn: { path, label }` (R11). **(2판)** `label`은 더 쓰지 않고 문구는 `returnTargets.ts` 표가 정한다(R35). 모양은 호환을 위해 그대로 둔다 |
| `learningResume` **(2판)** | navigation state | '이어서 읽기'로 연 글은 기록된 위치로 간다(FR-020). 값은 `true`이고, `continueReading.kind`가 `RESUME`일 때 완료 카드·요약 패널·떠 있는 '이어서 읽기'·'나의 학습' 화면의 '이어서 읽기'가 싣는다. 글 화면은 받은 글 기록의 `percent` 위치로 간다 |
| `thorp.learning.adminDraft.{articleId}` | `sessionStorage` | 저장하지 않은 관리 편집 내용(R12). **(2판)** 내용 버전 2(형식·배치·확인 문제 포함) |
| `thorp.learning.view` **(2판)** | `sessionStorage` | 고른 보기(`curriculum`·`list`). 로그인 여부와 관계없다(FR-023) |
| `thorp.learning.dismissedNotices` **(2판)** | `sessionStorage` | 닫은 공개 안내의 `entryKey` 목록(FR-031, R27) |

일반 사용자 화면이 브라우저에 쓰는 것은 위 두 화면 상태뿐이다. 예외로 로그인 연결을 누르면 014의 `useLoginReturn`이 돌아올 위치를 014 키 `thorp.auth.returnTarget`에 쓴다(R34). 013이 만드는 키가 아니고 학습 기록도 아니다. 학습 기록(완료·위치·답·경험치·배지)은 브라우저 저장소에 쓰지 않는다. 로그인 사용자의 기록은 서버에만 있고, 비로그인 사용자의 기록은 어디에도 남기지 않는다(FR-020, constitution '기록·개인정보·보관').

## 16. 요구사항 추적

| 요구사항 | 이 문서의 자리 |
|---|---|
| FR-001 단계·순서 | §3-1, §12-2, §13-2·§13-3 |
| FR-002 최소 구성 | §5-3, §8-2 #1~#6, R20 |
| FR-003·FR-004 예시 | §9, §8-2 #7~#10 |
| FR-005·FR-026 금지 표현·숫자·요소 | §5-3, §8-2 #15~#17 |
| FR-006 다른 이름 | §3-3 `aliases`, §5-4 |
| FR-007 출처 | §5-2 출처 표시 |
| FR-008 단일 원본·세 문장·고정 식별자 | §2, §3-3, §3-6, §7 |
| FR-009 글 안 도움말 | §5-4 |
| FR-015~FR-018 보관·게시·기록 | §3, §8, §10 |
| FR-020 나의 학습 기록·세는 범위 | §11, §12-1·§12-2 |
| FR-021 완료 | §11-1, R32 |
| FR-022 확인 문제 | §6, §11-2 |
| FR-023 보기·단계 고정·번호 | §3-1, §12-2, §15 |
| FR-024 배지 | §11-4, §12-4 |
| FR-025 학습 초기화 | §11-5 |
| FR-027 Thorp 동작 설명 | 검사 대상 아님. 원고 검토(plan "첫 단계 공개 조건") |
| FR-028 경험치 | §11-3, §12-3 |
| FR-029 레벨 | §12-3 |
| FR-030 알림 | §12-5 `gained` |
| FR-031 단계 공개 | §3-1, §3-7, §10-2·§10-3, R27 |
