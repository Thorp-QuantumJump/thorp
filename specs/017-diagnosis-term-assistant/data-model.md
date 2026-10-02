# Data Model: 진단 화면 용어 도우미

새 저장소 테이블은 없다. 아래는 요청 안에서만 사는 값 타입과, 015 타입에서 바뀌는 부분이다. 015 타입의 원래 정의는 [015 spec Key Entities](../015-context-assistant/spec.md)와 코드(`assistant/application/result/`)에 있다.

## 1. PageKind (변경)

| 값 | 화면 | 식별자 | 근거 빌더 |
|---|---|---|---|
| `RESULT` | `/results/:token` | `publicToken`(필수) | `ResultEvidenceBuilder`(015) |
| `SECTOR_SELECTION` | `/diagnosis/sectors` | 없음 | `TermEvidenceBuilder` |
| `STRATEGY_SETTINGS` | `/strategy` | 없음 | `TermEvidenceBuilder` |

- `THORP_ASSISTANT_ENABLED_SCOPES`는 이 값들의 쉼표 목록이다. 값마다 따로 켠다.
- 모르는 값이면 기동에 실패한다(015와 같다).

## 2. PageContext (변경)

`PageContext(kind, publicToken)`이다. 진단 종류는 `publicToken == null`이다. 요청 `context`에 `kind` 밖의 키가 있으면 400이다(진단 종류에서는 `publicToken`도 금지).

## 3. Evidence (필드 추가)

| 필드 | 015 | 017 |
|---|---|---|
| `id`·`label`·`value`·`asOf`·`kind` | 그대로 | 그대로. 용어 종류의 `kind`는 항상 `TEXT`이고 `asOf`는 null |
| `tab` | 결과 탭 id | 용어 종류는 null |
| `source` | 없음(null) | `EvidenceSource`. 아래 표 |

### EvidenceSource

| `type` | 원천 | `articleId`·`articleTitle` |
|---|---|---|
| `TERM` | 013 게시 용어 설명(`learning_published_term`) | 그 용어가 속한 게시 글 |
| `ARTICLE` | 013 게시 글의 판 snapshot 블록 | 그 글 |
| `REGISTRY` | 003 파라미터 등록표 | null |
| `SECTOR` | 001 섹터 목록 | null |

규칙:
- `source`는 서버 안과 응답에만 있다. 모델에 가는 근거 줄(`Fn label = value`)에는 싣지 않는다. 출처 종류는 라벨 접두(`용어:`·`글 «제목»:`·`전략:`·`설정 항목:`·`섹터 목록`)로만 보인다.
- 근거 하나는 원문 필드 하나다. 여러 필드를 이어 붙이는 것은 섹터 목록 근거와 설정 항목 근거(설명 + 선택지 이름)뿐이다. 이어 붙일 때 원문 사이에 넣는 구분 문자열(`; `, `, 선택지: `)에는 숫자가 없다.

## 4. 근거 라벨 표 (용어 종류)

| 원천 | 라벨 | 값 |
|---|---|---|
| 게시 용어 | `용어: {label}` | `sentence` |
| 글 제목·질문 | `글 «{title}»: 제목` / `글 «{title}»: 질문` | `title` / `question` |
| 글 소제목·문단 | `글 «{title}»: 소제목 {순번}` / `글 «{title}»: 문단 {순번}` | 블록 `text` |
| 글 용어 블록 | `용어: {label}` | 블록 `sentence`(게시 용어와 같은 문장) |
| 전략 | `전략: {name}` | `description` |
| 설정 항목 | `설정 항목: {전략 이름} · {axis label}` | `description` + (선택지가 있으면) `, 선택지: {label1}, {label2}…` |
| 규칙 | `설정 규칙: {전략 이름}` | rule `detail` |
| 섹터 목록 | `섹터 목록` | `{name}({산업|테마}, 대표 ETF {ETF name}, 기초 지수 {benchmarkIndex})`를 `; `로 이음 |
| 잘림 표시 | `읽은 범위` | `일부 용어 설명은 싣지 않음` / 015의 `일부만 읽음 (…)` |

- 라벨의 `{순번}`은 블록 순서이고 한글 수사가 아니라 아라비아 숫자다. 라벨은 검증 대상이 아니다. 답 `text`에만 숫자 규칙이 걸린다.
- 같은 문장이 게시 용어와 글 용어 블록 두 곳에서 들어오면, 글 쪽 근거는 빼고 처음 팩의 근거를 가리키게 한다. 같은 문장이 두 번 실리지 않게 하기 위해서다.

## 5. ToolSpec (변경)

| 필드 | 015 | 017 |
|---|---|---|
| `name`·`description` | 그대로 | 그대로. 열거 도구의 설명에 `id=이름` 대응을 적는다 |
| `dateArguments: List<String>` | 날짜 인자 이름 | `arguments: List<ToolArgument>`로 바꾼다 |

`ToolArgument(name, type, allowedValues)`:
- `type`은 `DATE` 또는 `ENUM`이다.
- `ENUM`은 `allowedValues`가 비어 있지 않다. 비어 있으면 그 도구를 만들지 않는다. 예를 들어 게시 글이 0개면 `readArticle`이 없다.
- 015 도구 다섯 개는 모두 `DATE` 인자로 옮긴다. 이름·검증은 같다.

`ToolLoop` 검증:
- 인자 이름 집합이 정의와 정확히 같아야 한다.
- `DATE`는 `YYYY-MM-DD` 형식이어야 한다.
- `ENUM`은 `allowedValues`에 있어야 한다.
- 실패하면 015와 같이 "사용할 수 없음: …"으로 돌려준다.

## 6. Conversation (필드 추가)

`Conversation(kind, question, exchanges)`이다. 어댑터는 `kind`로 프롬프트를 고르고(`RESULT` → `answer-ko.txt`, 진단 두 종류 → `term-ko.txt`), 다른 용도로는 쓰지 않는다.

## 7. ValidationPolicy (새 값 타입)

| 필드 | `RESULT` | 용어 종류 |
|---|---|---|
| 숫자 규칙 | `STRICT`: 자리표시자 밖 숫자 전부 거절(한글 수사는 허용) | `QUOTED`: 숫자 토큰과 한글 수사 + 수량 단위 토큰이 그 part `factIds` 근거 `value` 중 하나에 부분 문자열로 있어야 함(research R3, 계약 §2.4) |
| 자리표시자 규칙 | `EXACT`: 자리표시자 집합 = `factIds` | `SUBSET`: 자리표시자 ⊆ `factIds`, `factIds` 비어 있지 않음 |
| 고정 문장 | 015 `AnswerValidator`의 셋 | plan "답 검증"의 셋 |
| URL·경로·통화 기호 | 거절 | 거절 |

## 7a. EvidenceBundle (필드 추가)

`EvidenceBundle(facts, tools, partialUntil, registryProvisional)`. `registryProvisional`은 용어 빌더가 `ParameterRegistryPort.provisional()`로 채우고, `RESULT`는 항상 false다. 서비스는 이 값이 true이고 쓰인 근거에 `REGISTRY`가 있으면 임시 내용 접두 문장을 붙인다(spec FR-009b).

## 8. AssistantAnswer (필드 추가)

`AssistantAnswer(status, answer, evidence, kind, relatedArticles)`.

- `relatedArticles: List<RelatedArticle(articleId, title)>`
  - `ANSWERED`일 때 쓰인 근거 가운데 `source.articleId`가 있는 것을 중복 없이 처음 쓰인 순서로 담는다.
  - 상한은 3이다.
  - 그 밖의 경우는 빈 목록이다. `RESULT`는 항상 비어 있다.

## 9. 상태 전이

없다. 요청 하나 안에서 끝나고 저장하지 않는다. 콘텐츠의 게시 → 게시 취소 전이는 013이 소유한다. 이 기능은 요청 시점의 상태만 읽는다(spec FR-004).
