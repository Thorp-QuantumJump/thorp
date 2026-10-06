# 진단 화면 용어 도우미 계약 (015 계약에 대한 차이)

spec 017의 HTTP·모델 계약 원본이다. **여기 적지 않은 것은 [015 계약](../../015-context-assistant/contracts/assistant-api.md)을 따른다.** 요약은 [`docs/specs/assistant-api/README.md`](../../../docs/specs/assistant-api/README.md)에 있고, 예시 JSON(`docs/specs/assistant-api/examples/term-*.json`)이 IT의 대조 기준이다.

## 1. HTTP

### GET /assistant/capabilities

모양은 같다. `scopes`에 `SECTOR_SELECTION`·`STRATEGY_SETTINGS`가 켜진 것만 들어간다. 순서는 enum 선언 순서(`RESULT`, `SECTOR_SELECTION`, `STRATEGY_SETTINGS`)다.

```json
{ "enabled": true, "scopes": ["STRATEGY_SETTINGS"] }
```

### POST /assistant/questions

요청:

```json
{ "question": "리밸런싱이 뭐야?", "context": { "kind": "STRATEGY_SETTINGS" } }
```

- `context.kind`: `SECTOR_SELECTION` 또는 `STRATEGY_SETTINGS`.
- 이 두 종류는 `kind` 밖의 키를 받지 않는다. `publicToken`이 있어도 400 `INVALID_INPUT`이다. 화면의 선택값·저장소 값은 어떤 키로도 보내지 않는다.

응답 `200`:

```json
{
  "status": "ANSWERED",
  "answer": "리밸런싱은 시간이 지나 달라진 비중을 처음 정한 비중으로 되돌리는 일이에요. 이 화면의 리밸런싱 주기는 그 되돌리기를 얼마마다 할지 정하는 값이에요.",
  "evidence": [
    { "id": "F3", "label": "용어: 리밸런싱", "value": "시간이 지나 달라진 비중을 처음 정한 비중으로 되돌리는 일이다.", "asOf": null, "tab": null,
      "source": { "type": "TERM", "articleId": "weights-and-rebalancing", "articleTitle": "비중과 리밸런싱" } },
    { "id": "F17", "label": "설정 항목: 동일 비중 · 리밸런싱 주기", "value": "같은 비중에서 벌어진 만큼을 얼마마다 되돌릴지 정한다.", "asOf": null, "tab": null,
      "source": { "type": "REGISTRY", "articleId": null, "articleTitle": null } }
  ],
  "relatedArticles": [ { "articleId": "weights-and-rebalancing", "title": "비중과 리밸런싱" } ],
  "page": { "kind": "STRATEGY_SETTINGS" }
}
```

- `evidence[].source`는 새 필드다. `RESULT` 응답에서는 null이다.
- `relatedArticles`는 새 필드다. `RESULT` 응답과 `ANSWERED`가 아닌 응답에서는 빈 배열이다. 최대 3개다. 화면은 `/learn/{articleId}` 연결로 그린다.
- 진단 종류에는 부분 결과 접두 문장이 없다. 대신 등록표가 임시값(`ParameterRegistryPort.provisional()`)이고 쓰인 근거에 `source.type=REGISTRY`가 하나라도 있으면, 서버가 `answer` 앞에 "이 설정 설명은 아직 확정 전의 임시 내용입니다. "를 붙인다(spec FR-009b). 섹터 대응표가 예시 자료(`SectorListResult.isSample()`)이고 쓰인 근거에 `source.type=SECTOR`가 있으면 "이 섹터 목록은 예시 대응표입니다. "도 붙인다(두 문장이 다 붙으면 등록표 → 섹터 순서). 검증·치환이 끝난 뒤 붙이므로 검증 대상이 아니다.
- `ANSWERED` 밖의 고정 문장:

| status | answer |
|---|---|
| `INSUFFICIENT_EVIDENCE` | 이 사이트 자료로는 확인할 수 없어요. |
| `OUT_OF_SCOPE`(고르기·성과·결과 값·뉴스 질문) | 이 도우미는 이 사이트에 나오는 용어와 설정의 뜻을 설명해요. 무엇을 고를지는 정해 드리지 않고, 백테스트 결과는 결과 화면에서 물어볼 수 있어요. |
| `GREETING` | 안녕하세요. 이 화면에 나오는 용어를 물어보세요. 예: 역변동성은 어떻게 비중을 정해? |

오류: 015 표에서 **토큰·결과 관련 행(404 토큰 없음·410·409)은 진단 종류에 해당하지 않는다**. 나머지는 같다.

| 상황 | HTTP | code |
|---|---|---|
| 질문 길이·공백, 낯선 `kind`, `kind` 밖의 `context` 키 | 400 | `INVALID_INPUT` |
| 그 종류가 꺼짐(질문 경로만) | 404 | `RESOURCE_NOT_FOUND` |
| 한도 초과(IP·화면 종류별 IP·하루) | 429 | `ASSISTANT_LIMIT`, `details.reason`은 `PER_IP`·`PER_PAGE`·`PER_DAY` |
| 모델 장애·시간 초과 | 503 | `ASSISTANT_UNAVAILABLE` |
| 콘텐츠 조회 실패(등록표·섹터 목록·용어 색인을 읽지 못함) | 503 | `ASSISTANT_UNAVAILABLE`. 빈 근거로 모델을 부르지 않는다 |

게시된 용어·글이 0개인 것은 오류가 아니다. 처음 팩에 용어 근거가 없을 뿐이다.

## 2. 서버 ↔ 모델

### 2.1 요청

015 §2.1과 같은 형식이다. 다른 점은 다음과 같다.

- `developer` 내용은 `assistant/prompts/term-ko.txt`다.
- 사용자 메시지의 근거 줄은 015와 같이 `Fn label = value` 형식이다. `asOf`가 없으므로 괄호는 없다.

처음 팩:

| 종류 | 포함 | 순서 |
|---|---|---|
| 공통 | `읽을 수 있는 항목` 1개 = `글: {제목}, {제목}…; 전략: {name}, …; 설정 항목: {axis label}({전략 name}), …` | 맨 앞 |
| 공통 | 게시 용어마다 `용어: {label}` = `sentence` | 글 읽는 순서 → 용어 키 |
| `STRATEGY_SETTINGS` | 활성 전략마다 `전략: {name}` = `description` | 등록표 순서 |
| `SECTOR_SELECTION` | `섹터 목록` 1개 | — |

- 처음 팩 상한은 40이다.
- 넘으면 `STRATEGY_SETTINGS`는 등록표 축의 `termId`에 걸린 용어를 먼저, `SECTOR_SELECTION`은 `sectors-and-etf`·`weights-and-rebalancing` 글의 용어를 먼저 싣는다. 나머지는 `읽은 범위 = 일부 용어 설명은 싣지 않음` 근거 하나로 남긴다.

### 2.2 도구

| 이름 | 인자 | 열거 집합(요청 시점) | 반환 근거 |
|---|---|---|---|
| `readArticle` | `articleId: enum` | 게시된 글의 `articleId` | `글 «제목»: 제목`·`질문`·`소제목 n`·`문단 n`, 용어 블록은 `용어: {label}`. `hypothetical`·`example`·`sources`는 넣지 않는다. 처음 팩에 이미 있는 용어 문장은 다시 싣지 않는다 |
| `readStrategy` | `strategyId: enum` | 활성 전략의 `strategyId` | `전략: {name}` 1개, `설정 항목: {전략} · {label}` 축마다 1개, `설정 규칙: {전략}` 규칙마다 1개. 선택지는 `allowedRange.choices()`의 `label`만 쓴다. `allowedRange`의 최소·최대·간격과 선택지 `value`, `unit`·`inputType`·`appliesWhen`은 넣지 않는다 |
| `readSectorList` | 없음 | — | `섹터 목록` 1개. 처음 팩에 있으면 "사용할 수 없음: 이미 근거에 있습니다" |

- 도구 정의는 strict다. 열거 인자는 `{"type":"string","enum":[…]}`이고, `description`에 `weights-and-rebalancing=비중과 리밸런싱, …` 대응을 적는다.
- 열거 집합이 비면 그 도구를 `tools`에 넣지 않는다.
- 열거 밖의 값이나 모르는 인자 이름은 실행하지 않고 "사용할 수 없음: …"으로 돌려준다(015 §2.2).
- 라운드 2회, 라운드당 근거 30개, 전체 60개, 잘림 규칙은 015와 같다.

### 2.3 응답 스키마

015 §2.3과 같다.

### 2.4 서버 검증 규칙 (용어 종류)

015 §2.4 표에서 두 행을 바꾼다.

| 규칙 | 015(`RESULT`) | 017 용어 종류 |
|---|---|---|
| 자리표시자 ↔ `factIds` | 집합이 같다 | 자리표시자 ⊆ `factIds`, `ANSWERED` part는 `factIds`가 비어 있지 않다 |
| 자리표시자 밖 숫자 | 아라비아 숫자·`%`·통화 단위·날짜 형식 모두 거절 | 숫자 토큰(`[0-9０-９][0-9０-９.,]*` + 바로 붙거나 한 칸 뒤의 **수량 단위 목록** `%`·`원`·`개`·`가지`·`번`·`종목`·`섹터`·`일`·`거래일`·`주`·`개월`·`년` 중 하나. 긴 단위부터 맞춘다: `거래일`→`일`, `개월`→`개`)이 그 part `factIds` 근거 `value` 하나에 부분 문자열로 있어야 한다. 단위가 한 칸 뒤에 올 때는 그 단위 뒤가 끝·한글 아닌 글자·조사일 때만 단위로 읽는다("40 원칙"을 "40원"으로 읽지 않는다). 전각은 반각으로 바꿔 비교한다(NFKC). 없으면 거절 |
| 한글 수사 + 수량 단위 | 허용(015 그대로) | 어절 첫머리의 고유어 수사(`한`·`두`·`세`·`네`·`다섯`~`아홉`, `열`·`스물`(`스무`)·`서른`…`아흔`과 그 겹친 꼴 `열다섯`·`스물두`) 바로 뒤나 한 칸 뒤에 수량 단위(위 목록에서 `%`·`원`·`가지`를 뺀 것)가 오면 수량 토큰으로 보고, 위 숫자와 같이 인용 원문에 같은 표기가 있어야 한다. 서수(`두 번째`)와 관용 표현 `한 번`, 단위 없는 표현(`첫째`·`한편`)은 허용한다. `가지`는 "두 가지 방식"처럼 문장 구조로 쓰이는 일이 대부분이라 뺀다. 한자어 수사(`일`·`이`·`삼`…)는 `이번`·`일주` 같은 일반 낱말과 겹쳐 대상에서 뺀다 |

URL·경로·`₩`·`$`는 두 종류 모두 거절한다. 길이 상한 600자는 서버가 붙일 수 있는 진단 화면 접두(임시 등록표·예시 대응표)의 길이를 미리 뺀 값으로 검사한다 — 접두를 붙인 최종 답도 600자를 넘지 않는다. 모르는 `factId`, 길이·part 수 상한, 금지 표현은 015와 같다. 치환은 `{{Fn}}`이 있을 때만 하고, 용어 근거는 `TEXT`라 원문 그대로 들어간다.

## 3. 넘기지 않는 것

015 §3에 더해, 사용자의 선택값(섹터·비중·전략·설정값·분석 조건), 브라우저 `sessionStorage`·`localStorage` 값, 관리 화면 자료, 초안 콘텐츠를 넘기지 않는다.

## 4. 설정

| 키 | 뜻 | 초안 |
|---|---|---|
| `THORP_ASSISTANT_ENABLED_SCOPES` | 쉼표 목록. `RESULT`·`SECTOR_SELECTION`·`STRATEGY_SETTINGS` | 빈 값(비활성) |
| `thorp.assistant.limits.per-page-per-ip-per-minute` | 진단 종류의 화면 종류별 IP 분당 | 5 |
| `thorp.assistant.limits.term-initial-facts` | 용어 종류 처음 팩 상한 | 40 |

나머지 키는 015와 같고 공유한다. IP 분당과 하루 전체는 모든 종류가 함께 센다.
