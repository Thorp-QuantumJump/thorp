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
| 결과 없음 — 계산 중 | 409 | `RESULT_NOT_READY` | 005 하위 자원 `requireResultReady`와 같은 판정. 모델 무호출 |
| 결과 없음 — 실패 | 409 | `BACKTEST_FAILED` | 위와 같다 |
| 결과 없음 — 완료 거래일 없는 취소 | 409 | `NO_PARTIAL_RESULT` | 위와 같다 |
| CSRF 누락 | 403 | 저장소 공통 | — |
| 한도 초과(IP·토큰·하루) | 429 | `ASSISTANT_LIMIT` | `details.retryAfterSeconds`로 다시 가능한 시점 |
| 모델 장애·시간 초과·응답 검증 실패로 답을 만들 수 없음 | 503 | `ASSISTANT_UNAVAILABLE` | "지금은 질문에 답할 수 없어요. 잠시 후 다시 시도해 주세요." + 재시도 버튼 |
| 기능 비활성(질문 경로만) | 404 | `RESOURCE_NOT_FOUND` | 경로 자체를 노출하지 않음. `capabilities`는 항상 200 |

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

- 근거는 `Fn label (asOf) = value` 한 줄씩. **값은 원문 문자열이다**(모델이 표기를 바꾸지 않게 한다). 라벨은 아래 포함 표의 한국어 고정 문자열이고 결과 필드 이름을 쓰지 않는다.

#### 초기 팩 포함 표 (`getDetail` + `getWeights`)

| 원천 | 항목 | 라벨 | 표기 |
|---|---|---|---|
| `summary` | `initialEquity`·`finalEquity` | 시작 자산·종료 자산 | 금액 |
| `summary` | `totalReturn`·`netTotalReturn`·`cagr`·`maxDrawdown`·`volatility`·`winRate` | 전체 수익률·비용 차감 후 전체 수익률·연복리 수익률·가장 크게 줄었던 폭·변동성·승률 | 비율 |
| `summary` | `sharpeRatio` | 샤프 비율 | 소수 2자리 |
| `summary` | `tradeCount` | 거래 횟수 | 정수 |
| `summary` | `totalTradingCost`와 `costs`의 항목별 합계 | 거래비용 합계·수수료·세금·슬리피지 등(항목 이름은 004 `Costs` 갈래의 한국어) | 금액 |
| `summary.analytics` | `recoveryDays`·`turnover`·`weightError`·`method` | 회복 기간(거래일)·회전율·비중 오차·추정 방법 | 정수·비율·비율·문자 |
| `summary.analytics` | `riskContribution`·`lossContribution` | 섹터별 위험 기여·손실 기여(섹터 이름은 `SectorService.getSectors()`에서 `sectorId`로 찾고, 없으면 `sectorId` 원문) | 비율·비율. **60을 넘으면 이 둘부터 빼고** "일부 분석 값은 싣지 않음" 근거 하나를 남긴다 |
| `partial` | 부분 결과 여부. `true`면 완료 끝 날짜가 필요한데 상세에는 없으므로(005 화면도 시계열 마지막 점을 쓴다) **이때만** `getTimeseries`를 초기 조회에 더해 마지막 점의 날짜를 근거로 넣고 접두 문장에 쓴다 | 부분 결과·완료 끝 날짜 | 문자·날짜 |
| `config` | `start`·`end`·`effectiveStart`·`effectiveEnd`·`warmupTradingDays`·`initialCash` | 요청 시작일·요청 종료일·평가 시작일·평가 종료일·준비 기간(거래일)·시작 금액 | 날짜·정수·금액 |
| `config` | `strategyId`(→ `strategyName`)·`parameters`의 축마다 | 전략·(축 라벨은 003 등록표 `ParameterRegistryPort`의 한국어 라벨) | 문자·등록표 표기 |
| `config.items[]` | `instrumentId`·`targetWeight` (라벨에 섹터 이름을 넣는다: "{섹터 이름} 대표 ETF"·"{섹터 이름} 목표 비중") | 대표 ETF·목표 비중 | 문자·비율 |
| `weightsSource`, `getWeights` | 비중 출처, **마지막 점**의 `actual`(섹터별; `asOf`는 그 점의 날짜) | 비중 출처(사용자 입력/전략 계산)·"{섹터 이름} 실제 비중" | 문자·비율 |
| `exceptionKinds` | 예외 종류 목록 | 계산 중 예외 | 문자 |

**넣지 않는 것**: `publicToken`, `expiresAt`, `versions`, `progress`, `cancelRequested`, `resultSource`, `queuedAt`·`startedAt`·`finishedAt`, `queueWaitSeconds`, `failure`, `validationTier`, `analytics.correlationMatrix`(N²라 60 안에 들지 않는다 — 1차는 싣지 않고, 물으면 근거 부족이다), `getWeights`의 마지막 점 이외 점과 `weightError`·`riskScale`·`estimatedVolatility`. 결과 타입 record를 통째로 옮기지 않고 위 표의 항목만 필드 단위로 옮긴다.

**크기**: 고정 항목 약 34개 + 섹터별 5개(대표 ETF·목표 비중·실제 비중·위험 기여·손실 기여)라 섹터 6개부터 60을 넘는다. 넘치면 위 표의 규칙대로 위험·손실 기여부터 빼고, 목표·실제 비중은 항상 싣는다. 초기 조회 순서는 `getWeights` → `getDetail`이다 — `getWeights`가 005 `requireResultReady`의 409(`RESULT_NOT_READY`·`BACKTEST_FAILED`·`NO_PARTIAL_RESULT`)를 그대로 던지므로 결과 없음 판정을 따로 만들지 않는다.
- `tools`에는 §2.2의 함수 5개만. 웹 검색·코드 실행 같은 내장 도구는 넣지 않는다.
- 도구 라운드: 응답이 `tool_calls`면 서버가 검증·실행하고 `assistant(tool_calls)`·`tool(content=새 근거 줄들 또는 "사용할 수 없음: 이유")` 메시지를 이력에 붙여 다시 보낸다. `store:false`라 이력은 서버가 매번 담는다. 최대 2라운드.
- 시간: **전체 데드라인 2분이 우선**이다. 라운드별 모델 호출 예산(첫 60초·이후 30초, 설정값)은 그 안에서 남은 시간과 작은 쪽을 쓴다(`MarketHttpClient`의 `deadline.remaining()` 방식). 데드라인은 페이지 판정부터 세므로 근거 팩 만들기·도구 실행·검증 시간도 든다. 넘기면 모델이 늦게 돌려준 초안도 버리고 503이다.

### 2.2 도구

도구는 요청 시작 때 풀린 결과에 묶여 만들어진다. **어느 도구도 식별자 인자를 받지 않는다.**

| 이름 | 인자 | 서버 검증 | 반환 근거(고르기만, 계산 없음) |
|---|---|---|---|
| `getEquityOn` | `date` | 결과 `effectiveStart~End` 안. 시계열에 없는 날은 직전 거래일로 바꾸고 라벨에 표시 | 그 날의 자산가치·누적 수익률·하락 폭 |
| `getEquityRange` | `from`, `to` | 기간 안, `from<=to`, 1년 이하 | 첫·끝·최고·최저 점 넷 |
| `getTradesBetween` | `from`, `to` | 기간 안, `from<=to` | `/trades?from&to` 첫 페이지(공개 상한). 잘리면 "일부 거래" 근거 추가 |
| `getPositionsOn` | `date` | 기간 안 | `/positions?date`의 보유 종목·비중 |
| `getDeepestDrawdownWindow` | 없음 | — | `drawdown`이 가장 낮은 첫날과 그 앞 고점 날(005 `drawdownWindow.ts`와 같은 정의). `summary.maxDrawdown`과 다르면 빈 결과 |

검증 실패·모르는 이름은 실행하지 않고 `tool` 메시지로 "사용할 수 없음: {이유}"를 돌려준다.

근거 알갱이: 시계열 점 1개는 근거 3개(자산가치·누적 수익률·하락 폭 — 값의 종류가 달라 따로 치환한다), 거래 1건은 근거 1개(한 문장, 안의 금액은 화면과 같은 표기), 보유 종목 1개는 근거 1개(비중)다. 라운드 30개면 점 10개 또는 보유 30종목이다. 라운드당 30개, 팩 전체 60개. 넘으면 **도구 결과의 뒤를 자르고 초기 팩은 보존**하며 "일부만 읽음" 근거를 남긴다(자리가 아예 없으면 그 호출은 "사용할 수 없음: 근거 자리가 가득 참"으로 돌려준다 — 빈 성공으로 보이면 모델이 자료가 없었다고 읽는다)(거래 첫 페이지는 공개 상한 100건이라 거의 항상 잘린다 — 그래서 `getTradesBetween`은 구간을 좁혀 다시 부르라는 안내를 프롬프트에 둔다). 도구 결과는 결과 record를 통째로 옮기지 않고 필드 단위로 만든다 — `backtestId`, 시계열 점의 `benchmarkReturn`·`cashFlows`는 넣지 않는다.

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
| 전체 길이 ≤ 600자(설정값), part 수 ≤ 5(설정값) | 넘으면 거절 |
| 금지 표현 목록(`backend/src/main/resources/assistant/forbidden-phrases.txt`, 한 줄에 하나, `#` 주석: 권고·확률·원인 단정 어구) | 걸리면 거절. **보조 검사다** — 실제 방어는 프롬프트와 평가 표본 |

통과하면 `{{Fn}}`을 `Evidence.value`의 표기 함수로 치환한다. 표기는 프론트 `frontend/src/components/result/formatResult.ts`와 같은 규칙이어야 근거 카드 값과 탭의 값이 어긋나지 않는다:

| 종류 | 규칙 | 예 |
|---|---|---|
| 금액 | 정수로 반올림(HALF_UP), 천 단위 구분, 뒤에 `원` (`formatMoney`) | `17078.49` → `17,078원` |
| 비율 | ×100, 소수 2자리(HALF_UP), 뒤에 `%`, `-0.00`은 `0.00` (`formatRatio`) | `0.166652` → `16.67%`, `0.12345` → `12.35%` |
| 소수 2자리 | 반올림 소수 2자리 | `1.2345` → `1.23` |
| 정수 | 천 단위 구분 | `1234` → `1,234` |
| 날짜 | ISO 원문 그대로 — 결과 탭이 `2024-12-31` 형식으로 보이므로 카드도 같게 | `2024-12-31` → `2024-12-31` |
| 문자 | 그대로 | — |

표기 함수는 근거를 만들 때 종류로 정해 둔다.

## 3. 넘기지 않는 것

프롬프트·도구 인자·이력·응답·로그 어디에도: 공개 토큰, `backtestId`(`BT-…`), 계정·이메일, 브라우저 화면 상태, 비교 대상·KOSPI 참고선 값. 사용자가 친 질문 원문은 그대로 모델에 간다(화면 안내로 갈음).

## 4. 설정

| 변수 | 뜻 | prod 기본값 |
|---|---|---|
| `THORP_ASSISTANT_API_KEY` | GMS 키 | 빈 값(`${THORP_ASSISTANT_API_KEY:}`) → 비활성 |
| `THORP_ASSISTANT_BASE_URL` | `https://gms.ssafy.io/gmsapi/api.openai.com` | 빈 값 → 비활성 |
| `THORP_ASSISTANT_MODEL` | `gpt-5-mini` | 빈 값 → 비활성 |
| `THORP_ASSISTANT_ENABLED_SCOPES` | 쉼표 목록, 1차 `RESULT` | 빈 값(비활성) |
| `thorp.assistant.limits.per-ip-per-minute` 등 | IP 8, 토큰 5, 하루 1000, 질문 300자 | 설정값 |
| `thorp.assistant.timeout.*` | 전체 2분, 첫 호출 60초, 이후 30초 | 설정값 |

키·모델·주소 중 하나라도 비어 있으면 `capabilities.enabled=false`이고 기동은 정상이다. 다른 prod 설정(`${THORP_LEARNING_ADMIN_USER_IDS}`)처럼 기본값 없이 두면 미설정 시 기동이 실패하므로, 이 셋은 예외로 **빈 기본값**을 둔다 — "없으면 기능만 꺼진다"가 이 기능의 요구다.
