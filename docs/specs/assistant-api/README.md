# 화면 맥락 도우미 HTTP 계약 (Backend ↔ Frontend)

spec 015 결과 화면 질문 도우미가 쓰는 경로의 요약이다. 원본은 [`specs/015-context-assistant/contracts/assistant-api.md`](../../../specs/015-context-assistant/contracts/assistant-api.md)이고, 둘이 어긋나면 원본을 따르고 이 문서를 고친다. 서버↔모델 계약(도구·스키마·검증 규칙)도 원본에 있다.

예시 파일이 계약의 기준이다. `backend/src/test/java/com/ssafy/thorp/assistant/**`의 IT가 실제 응답과 필드 단위로 대조하고, 프론트엔드 말풍선 테스트가 같은 파일을 픽스처로 읽는다.

| 파일 | 내용 |
|---|---|
| [`examples/capabilities-enabled.json`](examples/capabilities-enabled.json) | 기능 켜짐. `scopes`에 `RESULT` |
| [`examples/capabilities-disabled.json`](examples/capabilities-disabled.json) | 키 없음·플래그 꺼짐 |
| [`examples/question-request.json`](examples/question-request.json) | 결과 페이지에서의 요청 본문 |
| [`examples/answer-answered.json`](examples/answer-answered.json) | 답변과 근거 카드. `answer`의 숫자는 모두 `evidence[].value` |
| [`examples/answer-insufficient.json`](examples/answer-insufficient.json) | 근거 부족 고정 안내 |
| [`examples/answer-out-of-scope.json`](examples/answer-out-of-scope.json) | 범위 밖(추천·비교 대상·다른 결과) 안내 |
| [`examples/error-limit.json`](examples/error-limit.json) | 한도 초과 `429 ASSISTANT_LIMIT` |
| [`examples/error-unavailable.json`](examples/error-unavailable.json) | 모델 장애·시간 초과 `503 ASSISTANT_UNAVAILABLE` |

## 1. 공통 규칙

- 봉투는 `ApiResponse`(`success`·`data`·`error`). `POST`는 저장소 CSRF 규약. 로그인 불필요.
- `context.kind`는 현재 **페이지 종류**다(1차 `RESULT`). 그 종류의 식별자 하나만 함께 보낸다. 다른 화면 데이터는 받지 않는다.
- 결과 토큰 판정(400/404/410)과 결과 없음(409)은 005 결과 계약과 같은 코드·뜻이고 **모델을 부르기 전에** 끝난다.
- 어느 응답·오류에도 `backtestId`가 없다(005 SC-009).

## 2. 경로

| 경로 | 뜻 | 응답 |
|---|---|---|
| `GET /assistant/capabilities` | 기능 켜짐 여부와 화면 범위 | `{enabled, scopes[]}`. 항상 200 |
| `POST /assistant/questions` | 질문 | `{status, answer, evidence[], page}` |

`status`는 `ANSWERED`·`INSUFFICIENT_EVIDENCE`·`OUT_OF_SCOPE`. `evidence[]`는 답에 실제로 쓰인 근거만이고 `tab`(`summary`·`analysis`·`settings`·`trades`·`loss`)으로 결과 탭에 간다. 부분 결과면 `answer` 첫 문장에 완료 끝 날짜까지의 부분 결과임이 붙는다.

## 3. 오류

| HTTP | code | 뜻 |
|---|---|---|
| 400 | `INVALID_INPUT` | 질문 길이·공백, 낯선 `kind`, 식별자 불일치, 토큰 형식 |
| 404 | `RESOURCE_NOT_FOUND` | 토큰 없음, 또는 기능 비활성(질문 경로만 비노출 — `capabilities`는 항상 200) |
| 409 | `RESULT_NOT_READY` / `BACKTEST_FAILED` | 결과 없음. 005와 같다 |
| 410 | `RESULT_EXPIRED` | 토큰 만료 |
| 429 | `ASSISTANT_LIMIT` | 한도 초과. `details.retryAfterSeconds` |
| 503 | `ASSISTANT_UNAVAILABLE` | 모델 장애·시간 초과. 자동 재시도 없음 |

## 4. 서버가 지키는 것

- 답변의 숫자·날짜는 모델이 적은 자리표시자(`{{Fn}}`)를 서버가 근거 값으로 채운 것이다. 모델이 직접 적은 숫자가 있으면 답변 전체를 버린다.
- 큰 자료(시계열·거래·보유)는 모델이 요청하면 서버가 검증 뒤 읽는 도구 다섯 개로만 읽는다. 도구는 날짜 인자만 받고 어느 결과인지는 서버가 묶어 둔다.
- 서버는 새 합계·비율·구간을 계산하지 않는다. 시계열에서 고르기만 한다.
- 한도(429): IP 8/분, 토큰 5/분, 하루 1,000회(설정값, [ADR-0008](../../adr/0008-external-llm-via-gms.md)). 질문 300자는 입력 검증(400), 서버 2분은 시간 예산(503)이다.
