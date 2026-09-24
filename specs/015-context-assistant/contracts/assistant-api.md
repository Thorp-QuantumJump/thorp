# 화면 맥락 도우미 계약 (Backend ↔ Frontend, Backend ↔ 모델)

spec 015의 HTTP 계약과 서버↔모델 계약의 원본이다. 요약은 [`docs/specs/assistant-api/README.md`](../../../docs/specs/assistant-api/README.md)에 있고 둘이 어긋나면 이 문서를 따른다. 예시 JSON(`docs/specs/assistant-api/examples/`)이 IT의 대조 기준이다.

## 1. HTTP

공통: `ApiResponse` 봉투(`success`·`data`·`error`), `POST`는 저장소 CSRF 규약. 로그인 불필요(결과 화면이 공개이므로).

### GET /assistant/capabilities

`200 {enabled: boolean, scopes: string[]}`. `enabled=false`면 `scopes`는 빈 배열이고 화면은 버튼을 그리지 않는다. 키가 없거나 플래그가 꺼지면 `false`. 이 경로는 플래그와 무관하게 항상 200이다.

### POST /assistant/questions

요청:

```json
{ "question": "거래비용은 어떻게 구성됐어?", "context": { "kind": "RESULT", "publicToken": "0123456789abcdef0123456789abcdef" } }
```

- `question`: 공백 제외 1~300자.
- `context.kind`: 현재 페이지 종류. 1차는 `RESULT`만. 그 종류의 식별자 하나만 허용하고 나머지 필드가 있으면 400.
- `context.publicToken`: 32자 소문자 16진. 형식 판정은 005와 같다.

응답 `200`:

```json
{
  "status": "ANSWERED",
  "answer": "비용을 뺀 전체 수익률은 16.67%이고, 거래비용은 모두 17,078원입니다. 그중 수수료가 12,000원, 슬리피지가 5,078원입니다.",
  "evidence": [
    { "id": "F1", "label": "비용 차감 후 전체 수익률", "value": "16.67%", "asOf": "2024-12-31", "tab": "summary" },
    { "id": "F4", "label": "거래비용 합계", "value": "17,078원", "asOf": null, "tab": "settings" }
  ],
  "page": { "kind": "RESULT" }
}
```

- `status`: `ANSWERED` | `INSUFFICIENT_EVIDENCE` | `OUT_OF_SCOPE`. 뒤 둘은 `answer`가 고정 안내 문장이고 `evidence`는 빈 배열이다.
- `answer`: 자리표시자가 치환된 최종 문장. 안의 숫자·날짜는 모두 `evidence[].value`에서 왔다.
- `evidence[]`: 답에 실제로 쓰인 근거만. `id`는 이 응답 안에서만 뜻이 있다. `tab`은 결과 화면 탭 id(`summary`·`analysis`·`settings`·`trades`·`loss`) 중 하나이며 화면은 그 탭으로 이동한다.
- 부분 결과(`partial=true`)면 `answer` 첫 문장은 서버가 붙인 "이 결과는 {완료 끝 날짜}까지의 부분 결과입니다."다.

오류(봉투 `error.code`):

| 상황 | HTTP | code | 화면 |
|---|---|---|---|
| 질문 길이·공백, 낯선 `kind`, 식별자 불일치, 토큰 형식 | 400 | `INVALID_INPUT` | 입력 안내 |
| 토큰 없음 | 404 | `RESOURCE_NOT_FOUND` | 005와 같은 뜻 |
| 토큰 만료(묘비 포함) | 410 | `RESULT_EXPIRED` | 005와 같은 뜻 |
| 결과 없음(계산 중·실패·시작 전 취소) | 409 | `RESULT_NOT_READY` / `BACKTEST_FAILED` | 005와 같은 뜻. 모델 무호출 |
| CSRF 누락 | 403 | 저장소 공통 | — |
| 한도 초과(IP·토큰·하루) | 429 | `ASSISTANT_LIMIT` | `details.retryAfterSeconds`로 다시 가능한 시점 |
| 모델 장애·시간 초과·응답 검증 실패로 답을 만들 수 없음 | 503 | `ASSISTANT_UNAVAILABLE` | "지금은 질문에 답할 수 없어요. 잠시 후 다시 시도해 주세요." + 재시도 버튼 |
| 기능 비활성 | 404 | `RESOURCE_NOT_FOUND` | 경로 자체를 노출하지 않음 |

토큰 판정(400/404/410/409)은 **모델 호출 전에** 끝난다. 어느 오류에서도 다른 자료로 대체 답변하지 않는다.

## 2. 서버 ↔ 모델

### 2.1 요청 (Chat Completions, GMS 게이트웨이)

`POST {THORP_ASSISTANT_BASE_URL}/v1/chat/completions`, `Authorization: Bearer {THORP_ASSISTANT_API_KEY}`.

```json
{
  "model": "gpt-5-mini",
  "store": false,
  "parallel_tool_calls": false,
  "tool_choice": "auto",
  "messages": [
    { "role": "developer", "content": "<answer-ko.txt 프롬프트>" },
    { "role": "user", "content": "질문:\n거래비용은 어떻게 구성됐어?\n\n근거:\nF1 비용 차감 후 전체 수익률 (2024-12-31) = 0.166652\nF2 ...\n" }
  ],
  "tools": [ { "type": "function", "function": { "name": "getEquityOn", "strict": true, "parameters": { "type": "object", "properties": { "date": { "type": "string", "description": "YYYY-MM-DD" } }, "required": ["date"], "additionalProperties": false } } }, "..." ],
  "response_format": { "type": "json_schema", "json_schema": { "name": "assistant_answer", "strict": true, "schema": { "...": "§2.3" } } }
}
```

- 근거는 `Fn label (asOf) = value` 한 줄씩. **값은 원문 문자열이다**(모델이 표기를 바꾸지 않게 한다). 라벨에 결과 필드 이름을 쓰지 않는다.
- `tools`에는 §2.2의 함수 5개만. 웹 검색·코드 실행 같은 내장 도구는 넣지 않는다.
- 도구 라운드: 응답이 `tool_calls`면 서버가 검증·실행하고 `assistant(tool_calls)`·`tool(content=새 근거 줄들 또는 "사용할 수 없음: 이유")` 메시지를 이력에 붙여 다시 보낸다. `store:false`라 이력은 서버가 매번 담는다. 최대 2라운드.
- 시간: 전체 15초. 첫 호출 7초, 이후 호출 4초(설정값).

### 2.2 도구

도구는 요청 시작 때 풀린 결과에 묶여 만들어진다. **어느 도구도 식별자 인자를 받지 않는다.**

| 이름 | 인자 | 서버 검증 | 반환 근거(고르기만, 계산 없음) |
|---|---|---|---|
| `getEquityOn` | `date` | 결과 `effectiveStart~End` 안. 시계열에 없는 날은 직전 거래일로 바꾸고 라벨에 표시 | 그 날의 자산가치·누적 수익률·하락 폭 |
| `getEquityRange` | `from`, `to` | 기간 안, `from<=to`, 1년 이하 | 첫·끝·최고·최저 점 넷 |
| `getTradesBetween` | `from`, `to` | 기간 안, `from<=to` | `/trades?from&to` 첫 페이지(공개 상한). 잘리면 "일부 거래" 근거 추가 |
| `getPositionsOn` | `date` | 기간 안 | `/positions?date`의 보유 종목·비중 |
| `getDeepestDrawdownWindow` | 없음 | — | `drawdown`이 가장 낮은 첫날과 그 앞 고점 날(005 `drawdownWindow.ts`와 같은 정의). `summary.maxDrawdown`과 다르면 빈 결과 |

검증 실패·모르는 이름은 실행하지 않고 `tool` 메시지로 "사용할 수 없음: {이유}"를 돌려준다. 라운드당 근거 30개, 팩 전체 60개. 넘으면 뒤를 자르고 "일부만 읽음" 근거를 남긴다.

### 2.3 응답 스키마 (strict)

```json
{
  "type": "object",
  "properties": {
    "status": { "type": "string", "enum": ["ANSWERED", "INSUFFICIENT_EVIDENCE", "OUT_OF_SCOPE"] },
    "answerParts": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "text": { "type": "string" },
          "factIds": { "type": "array", "items": { "type": "string" } }
        },
        "required": ["text", "factIds"],
        "additionalProperties": false
      }
    }
  },
  "required": ["status", "answerParts"],
  "additionalProperties": false
}
```

`text`의 값 자리는 `{{Fn}}`만이다. 예: `"비용을 뺀 전체 수익률은 {{F1}}이고, 거래비용은 모두 {{F4}}입니다."`

### 2.4 서버 검증 규칙

하나라도 걸리면 답변 전체를 버리고 `INSUFFICIENT_EVIDENCE` 고정 안내로 바꾼다(503이 아니다 — 모델은 응답했으나 쓸 수 없는 경우).

| 규칙 | 판정 |
|---|---|
| `factIds`가 모두 팩에 있다 | 아니면 거절 |
| `text`의 모든 `{{Fn}}`이 그 part의 `factIds`에 있고, `factIds`의 ID가 모두 `text`에 등장 | 아니면 거절 |
| `text`에서 자리표시자를 뺀 나머지에 아라비아 숫자, `%`, 통화 단위 뒤 숫자, `YYYY-MM-DD`·`N월 N일` 형식, URL·경로가 없다 | 있으면 거절. 한글 수사("두 번", "세 종목", "첫째")는 허용 |
| `ANSWERED`인 part는 `factIds`가 비어 있지 않다 | 아니면 거절 |
| 전체 길이 ≤ 600자(설정값), part ≤ 5(설정값) | 넘으면 거절 |
| 금지 표현 목록(설정 파일: 권고·확률·원인 단정 어구) | 걸리면 거절. **보조 검사다** — 실제 방어는 프롬프트와 평가 표본 |

통과하면 `{{Fn}}`을 `Evidence.value`의 표기 함수로 치환한다: 금액 `formatMoney`, 비율 `formatRatio`, 날짜 `YYYY년 M월 D일`, 정수 천 단위 구분. 표기 함수는 근거를 만들 때 종류로 정해 둔다.

## 3. 넘기지 않는 것

프롬프트·도구 인자·이력·응답·로그 어디에도: 공개 토큰, `backtestId`(`BT-…`), 계정·이메일, 브라우저 화면 상태, 비교 대상·KOSPI 참고선 값. 사용자가 친 질문 원문은 그대로 모델에 간다(화면 안내로 갈음).

## 4. 설정

| 변수 | 뜻 | prod 기본값 |
|---|---|---|
| `THORP_ASSISTANT_API_KEY` | GMS 키 | 없음(필수) |
| `THORP_ASSISTANT_BASE_URL` | `https://gms.ssafy.io/gmsapi/api.openai.com` | 없음(필수) |
| `THORP_ASSISTANT_MODEL` | `gpt-5-mini` | 없음(필수) |
| `THORP_ASSISTANT_ENABLED_SCOPES` | 쉼표 목록, 1차 `RESULT` | 빈 값(비활성) |
| `thorp.assistant.limits.per-ip-per-minute` 등 | IP 8, 토큰 5, 하루 1000, 질문 300자 | 설정값 |
| `thorp.assistant.timeout.*` | 전체 15초, 첫 호출 7초, 이후 4초 | 설정값 |

키·모델·주소 중 하나라도 없으면 `capabilities.enabled=false`이고 기동은 정상이다.
