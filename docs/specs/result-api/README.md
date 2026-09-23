# 결과 공개 열람 HTTP 계약 (Backend ↔ Frontend)

spec 005 결과 화면이 쓰는 공개 경로의 요약이다. 원본은 [`specs/005-result-comparison/contracts/result-api.md`](../../../specs/005-result-comparison/contracts/result-api.md)이고, 둘이 어긋나면 원본을 따르고 이 문서를 고친다. 분석 접수(`POST /backtests`)와 소유자 경로(`/backtests/**`)는 [`../backtest-api/README.md`](../backtest-api/README.md)에 있다.

예시 파일이 계약의 기준이다. `backend/src/test/java/com/ssafy/thorp/result/presentation/controller/ResultApiIT.java`가 실제 응답과 필드 단위로 대조하고, 프론트엔드 결과 화면 테스트가 같은 파일을 픽스처로 읽는다.

| 파일 | 내용 |
|---|---|
| [`examples/result-detail-completed.json`](examples/result-detail-completed.json) | 완료된 비로그인 결과의 상세 |
| [`examples/result-detail-running.json`](examples/result-detail-running.json) | 계산 중인 결과. `progress`·`queueWaitSeconds`가 있다 |
| [`examples/result-detail-cancelled-no-summary.json`](examples/result-detail-cancelled-no-summary.json) | 시작 전에 취소돼 요약이 없는 결과 |
| [`examples/result-expired.json`](examples/result-expired.json) | 만료 `410` 본문 |
| [`examples/result-timeseries.json`](examples/result-timeseries.json) | 129점 시계열 전체. 최대 하락 구간이 있다 |

## 1. 공통 규칙

- 경로 변수 `{token}`은 접수 응답의 `publicToken`이다. 32자 소문자 16진 문자열이고, 형식이 틀리면 조회하지 않고 `400 INVALID_INPUT`이다.
- **로그인하지 않아도 된다.** 토큰을 가진 사람이 결과를 본다. `POST`에는 저장소의 CSRF 규약이 그대로 적용된다.
- 응답 봉투는 `ApiResponse`(`success`·`data`·`error`)이고, 금액·비율은 문자열이다(004와 같다).
- 비로그인 결과에는 `expiresAt`이 있고 그 시각부터 모든 경로가 `410 RESULT_EXPIRED`다. 로그인 사용자의 결과는 `expiresAt`이 `null`이고 만료되지 않는다. 만료된 요청 행은 정리 작업이 주기적으로 지우지만, 토큰 해시를 묘비로 남겨 지운 뒤에도 `410`이다. 묘비 보관 기한(`thorp.result.tombstone-retention`)이 지나면 `404`다.
- **어느 응답에도 `backtestId`가 없다**(SC-009). 화면은 토큰만 들고 다닌다.

## 2. 경로 대응

| 공개 경로 | 감싸는 004 경로 | 응답 모양 |
|---|---|---|
| `GET /results/{token}` | `GET /backtests/{backtestId}` | 아래 §3 |
| `GET /results/{token}/timeseries` | `GET /backtests/{backtestId}/timeseries` | 004 응답에서 `backtestId`만 버린다 |
| `GET /results/{token}/trades` | `GET /backtests/{backtestId}/trades` | 위와 같다. `size`는 공개 상한으로 자른다(§4) |
| `GET /results/{token}/positions?date=` | `GET /backtests/{backtestId}/positions` | 위와 같다. `date` 필수 |
| `GET /results/{token}/weights` | `GET /backtests/{backtestId}/weights` | 위와 같다 |
| `GET /results/{token}/exceptions` | `GET /backtests/{backtestId}/exceptions` | 위와 같다 |
| `GET /results/{token}/index-comparison` | 001 기간 KOSPI 조회 포트 | 아래 §5. 같은 시작 금액으로 환산한 보조 계열 |
| `GET /results/{token}/progress` | 004 PROGRESS 이벤트(브로커) | 아래 §5.1. **봉투가 없는 SSE다** |
| `POST /results/{token}/retry` | `POST /backtests/{backtestId}/retry` | 아래 §6 |

하위 자원의 상태별 규칙(결과 전 `409 RESULT_NOT_READY`, 실패 `409 BACKTEST_FAILED`, 빈 배열을 만들어 내지 않음)은 004 §2.5와 같다.

## 3. 상세 — `GET /results/{token}`

004 상세(§2.1)에서 공개할 필드만 옮기고 네 칸을 더한다. `summary`·`progress`·`failure`의 모양은 004와 같다.

| 필드 | 004와의 차이 |
|---|---|
| `publicToken` | `backtestId` 대신 들어간다 |
| `expiresAt` | 추가. 비로그인 결과의 만료 시각, 로그인 결과는 `null` |
| `progress`·`queueWaitSeconds` | `status`가 `QUEUED`·`RUNNING`일 때만 있다 |
| `versions` | `worker`(워커 자기 보고)를 뺀다 |
| `validationTier` | 추가. PD-12 확정 전까지 항상 `UNVALIDATED`('말할 수 없음') |
| `strategyName` | 추가. 003 등록표의 전략 이름. 없거나 읽지 못하면 `null` |
| `weightsSource` | 추가. `USER`·`COMPUTED`. 볼 결과가 없으면 `null` |
| `exceptionKinds` | 추가. 예외 `kind`의 중복 없는 목록(처음 나온 순서). 볼 결과가 없으면 `null`, 빈 목록은 "예외 없음" |

`resultSource`·`partial`·`cancelRequested`·`queuedAt`·`startedAt`·`finishedAt`·`summaryUnavailable`·`failure`는 004 값을 그대로 통과시킨다. `config`에는 원 요청의 `executionType`을 더해 탐색 질의가 없는 주소에서도 자동 탐색 후보를 구분한다. 비교 요청의 비용 기준은 `versions.costProfile.version`에서 읽는다.

## 4. 거래 내역 상한

`size`를 생략하면 공개 상한(`thorp.result.trades-page-size`)이고, 상한보다 크면 상한으로 잘린다. 400으로 거절하지 않는 이유는 상한이 설정값이라 화면이 그 수를 모르기 때문이다. 실제 크기는 응답 `size`로 확인한다. 나머지 질의(`page`·`instrumentId`·`status`·`from`·`to`)와 검증(뒤집힌 기간·종목 형식 오류는 400)은 004 §2.3과 같다.

## 5. 지수 보조 비교 — `GET /results/{token}/index-comparison`

토큰 판정을 먼저 거친 뒤, 기간 KOSPI 조회 포트가 있으면 결과의 `effectiveStart`~`effectiveEnd` 범위와
`initialCash`로 서버가 환산한 `{available: true, reason: null, series: [{date, value}]}`를 돌려준다.
금액은 문자열이고 화면은 다시 계산하지 않는다.

현재 001에는 기간 계열 포트가 없으므로 `{available: false, reason: "INDEX_SERIES_UNAVAILABLE",
series: []}`다. 최신 관측치 한 점으로 기간 계열을 꾸미지 않는다.

## 5.1. 진행 시계열 구독 — `GET /results/{token}/progress`

계산이 도는 동안 거래일별 점을 흘려 화면이 그래프를 자라게 그리게 한다(004 R-05). 워커가 20거래일
묶음으로 보내는 PROGRESS 이벤트를 그대로 옮긴 것이며, **진행 시계열은 저장하지 않는다** — 저장하면
최종 `/timeseries`와 두 벌이 되고 둘이 어긋날 수 있다.

**이 경로만 응답 봉투가 없다.** `Content-Type: text/event-stream`이고 한 연결에 이벤트가 여러 번
흐르므로 `ApiResponse`를 씌울 자리가 없다. 토큰 판정은 다른 경로와 같아서 형식 오류 400·없음
404·만료 410은 연결을 열기 전에 평소의 봉투로 나간다.

이벤트는 둘이다.

| 이벤트 이름 | 본문 |
|---|---|
| `progress` | `{stage, completedTradingDays, totalTradingDays, lastTradingDate, points: [{date, equity, cumulativeReturn, drawdown}]}` |
| `finished` | 종결 상태 이름 하나(`COMPLETED`·`FAILED`·`CANCELLED`). 이 뒤로 올 것이 없다 |

`points`에는 **이번 구간에서 새로 계산한 점만** 담긴다. 앞서 보낸 점은 다시 오지 않는다. 다만 이벤트가
재전달되면 같은 점이 두 번 올 수 있으므로, 받는 쪽은 마지막으로 그린 날짜보다 앞서지 않는 점을 버린다.
소수 표기는 `/timeseries`와 같다 — 두 곡선을 한 그래프에 이어 그릴 수 있어야 한다.

이미 끝난 실행에 구독을 걸면 이벤트 없이 바로 닫힌다. 아무것도 오지 않는 연결을 열어 두지 않는다.
연결 하나를 열어 두는 상한은 `thorp.result.progress-stream-timeout`이고, **배포 환경 프록시의 읽기
상한보다 짧아야 한다.** 프록시가 먼저 끊으면 화면은 서버 장애와 구분할 수 없는 오류를 받는다. 같은
이유로 프록시는 이 경로의 응답을 버퍼링하지 않아야 한다(`infra/nginx`의 `/api/` 블록).

**백엔드 인스턴스 하나를 전제한다.** 이벤트 Consumer 가 공유 큐의 competing consumer 라서, 여러 개를
띄우면 브라우저가 붙은 인스턴스와 이벤트를 받은 인스턴스가 달라질 수 있다. 그러면 곡선이 자라지 않을
뿐이고 진행 정도와 최종 결과는 폴링이 그대로 가져온다. 전파 장치는 ADR-0002 가 후순위로 두었다.

## 6. 재시도 — `POST /results/{token}/retry`

`202` `{publicToken, status, resultSource, retryOfToken}`. `publicToken`은 새 요청의 토큰, `retryOfToken`은 원 요청의 토큰이다. 새 요청의 요청자는 지금 호출한 사람이고(비로그인이면 없음), 원 요청의 요청자를 물려받지 않는다. 실패·취소가 아니면 `409 NOT_RETRYABLE`, 한도에 걸리면 `429 EXECUTION_LIMIT`(`details.retryAfterSeconds`)이다.

## 7. 오류

| code | HTTP | 뜻 |
|---|---|---|
| `INVALID_INPUT` | 400 | 토큰 형식 오류, 거래 질의 오류, `date` 누락 |
| `RESOURCE_NOT_FOUND` | 404 | 그런 토큰이 없다(발급된 적 없거나 묘비 보관 기한이 지났다) |
| `RESULT_EXPIRED` | 410 | 비로그인 결과의 유효 시간이 지났다 |
| `RESULT_NOT_READY` | 409 | 아직 계산 중이다 |
| `BACKTEST_FAILED` | 409 | 실패해 결과가 없다 |
| `NO_PARTIAL_RESULT` | 409 | 취소 시점까지 완료한 구간이 없다 |
| `NOT_RETRYABLE` | 409 | 실패·취소가 아니라 다시 시도할 수 없다 |
| `EXECUTION_LIMIT` | 429 | 실행 한도. `details.retryAfterSeconds` |

**004에서 온 오류의 `message`는 코드의 기본 문구다.** 004 예외 문구에는 `backtestId`가 들어 있어 공개 경로는 문구를 바꿔 내보낸다. 입력 검증의 400은 원인 문구를 그대로 싣는다. 화면은 문구가 아니라 `code`로 분기한다.

## 8. 공개 응답에서 버리는 필드

요청자 식별자, `backtestId`, 실행 식별자(`runId`), 워커 자기 보고(`worker`·`versions.worker`·`versions.dependencies`), 재시도 원본의 `backtestId`(`retryOf` → `retryOfToken`), 오류 문구 속 식별자. 이유와 목록의 원본은 계약 "공개 응답에서 버리는 필드"다. 004가 필드를 더하면 버릴 것만 원본 목록에 추가한다.
