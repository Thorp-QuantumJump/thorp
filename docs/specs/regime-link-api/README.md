# 사례 연결 HTTP 계약 (Backend ↔ Frontend)

spec 011 사례 연결 화면이 쓰는 경로의 요약이다. 원본은
[`specs/011-regime-strategy-link/contracts/regime-link-api.md`](../../../specs/011-regime-strategy-link/contracts/regime-link-api.md)이고,
둘이 어긋나면 원본을 따르고 이 문서를 고친다. 진입 주소와 화면 사이 인계는
[`contracts/entry-handoff.md`](../../../specs/011-regime-strategy-link/contracts/entry-handoff.md),
백엔드와 워커가 주고받는 메시지 계약은 `../regime-link-messaging/`에 따로 둔다(spec 011 묶음 M1b에서 추가한다).

예시 파일이 계약의 기준이다. 백엔드 통합 테스트가 실제 응답과 필드 단위로 대조하고, 프론트엔드 사례
연결 화면 테스트가 같은 파일을 픽스처로 읽는다. 한 곳만 예외인데, `link-completed.json`의 `holdings`는
63거래일 전부가 아니라 앞·중간·끝 세 행으로 줄여 실었다. `trades`는 줄이지 않았고 개수가
`window63.tradeCount`와 같다.

| 파일 | 내용 |
|---|---|
| [`examples/availability-strategy-result.json`](examples/availability-strategy-result.json) | 진단 결과에서 들어온 가용성 조회. 필터가 걸리고 다섯 판정이 모두 나온다 |
| [`examples/availability-market-flow.json`](examples/availability-market-flow.json) | 시장 흐름에서 들어온 가용성 조회. 필터를 걸지 않고 판정만 붙는다 |
| [`examples/link-completed.json`](examples/link-completed.json) | 완료된 링크의 구간 요약 |
| [`examples/link-pending-run.json`](examples/link-pending-run.json) | 현금 시작으로 새 실행을 기다리는 링크. `runProgress`가 있다 |
| [`examples/link-data-insufficient.json`](examples/link-data-insufficient.json) | 자료가 없어 계산하지 않은 링크. 실패가 아니다 |
| [`examples/create-confirm-required.json`](examples/create-confirm-required.json) | 분석 기간이 사례를 덮지 않아 확인을 요구하는 응답 |
| [`examples/case-window.json`](examples/case-window.json) | 실행 없이 읽는 사례의 사후 구간(§1a). `lastAvailable`이 있다 |

검색 기록 생성(§1b)의 예시는 그 경로를 구현하는 묶음에서 함께 추가한다.

## 1. 공통 규칙

- 외부 경로는 `/api/regime-links`이고 프록시가 `/api`를 뗀다. 응답 봉투는 `ApiResponse`(`success`·`data`·`error`)다.
- 소수는 전부 문자열이고, 날짜는 거래일 `YYYY-MM-DD`, 월은 `YYYY-MM`, 시각은 UTC ISO-8601이다.
- **사례 식별자** `caseId`는 `{snapshotKey}~{endMonth}`다. `snapshotKey`는 010의 여섯 조각 키이고 011은
  분해하지 않고 문자열로만 다룬다. 서버는 **마지막** `~`로 나눈다. 형식이 틀리면 `400 INVALID_INPUT`이다.
  경로 변수로 쓰지 않고 항상 질의 인자나 본문으로 보낸다.
- **검색 식별자가 둘이라 이름이 다르다.** 010의 `searchId`는 snapshot 키(= `snapshotKey`)이고, 011의
  `linkSearchId`(`RLS-…`)는 이 spec이 만드는 연결 기록이다.
- **비교 묶음**은 `base`(필수)와 `compare`(선택) 두 공개 토큰이다. 005와 같은 규칙(형식 → 존재 → 만료)으로
  풀고, 만료는 `410 RESULT_EXPIRED`, 없는 토큰은 `404`다.
- 모든 응답에 `basis: {kind: "HISTORICAL_REVISION"}`가 있다. 화면은 이 값을 읽어 "최신 개정자료를 이용한
  역사 탐색"을 표시한다.
- **로그인**: 공개 주소로 여는 `GET`은 익명이 호출할 수 있다. 계산이나 기록을 만드는 `POST`는 요청자가
  없으면 `401 AUTHENTICATION_REQUIRED`이고 계산은 시작되지 않는다.
- 링크를 돌려주는 `GET`은 그 링크가 붙은 분석의 공개 토큰을 함께 받는다. 토큰이 없으면 `400`, 만료면
  `410`, 토큰과 링크가 맞지 않으면 `404`다. 응답에는 제시된 토큰 외의 토큰을 싣지 않는다.

## 2. 경로

| 경로 | 로그인 | 하는 일 |
|---|---|---|
| `GET /regime-links/availability` | 익명 가능 | 010 표시 후보에 비교 묶음의 가용성 판정을 붙인다 |
| `GET /regime-links/case-window?caseId=` | 익명 가능 | 실행 없이 사례의 사후 구간 날짜만 돌려준다. 003 기간 제약이 쓴다 |
| `POST /regime-link-searches` | 필요 | 사용자가 본 목록을 확정해 기록하고 변경을 알린다 |
| `POST /regime-links` | 필요 | 사례의 전략 결과를 연다. 계산 작업을 만든다 |
| `GET /regime-links/{linkId}?token=` | 익명 가능 | 링크 하나의 상태와 구간 요약 |
| `GET /regime-links?base=&compare=&caseId=` | 익명 가능 | 사례 하나에 붙은 링크 묶음을 기준 → 비교 대상 순서로 |
| `POST /regime-links/{linkId}/retry` | 필요 | 실패한 링크를 다시 시도한다 |

## 3. 가용성 판정 — `GET /regime-links/availability`

`cases[]`는 **010 표시 후보(≤10) 전부**를 010 순위 그대로 담는다. 필터로 빠진 사례도 담되
`shownInFiltered=false`다. 아래 순위에서 채우지 않으므로 응답에 010 표시 후보 밖의 사례는 없다.

| 상태 | 뜻 | 사유 | 기본 목록 |
|---|---|---|---|
| `NORMAL` | 계산할 수 있다 | — | 보인다 |
| `DATA_INSUFFICIENT` | 계산 작업을 만들지 않는다 | `UNLISTED`·`WARMUP_SHORT`·`SECTOR_UNMAPPED` | 빠진다 |
| `POST_INCOMPLETE` | 확보 거래일이 21 미만이다 | `RECENT`·`DELISTED` | 빠진다 |
| `OUT_OF_PERIOD` | 분석 기간이 사례 구간을 덮지 않는다 | — | **보인다** |

- `availability`는 비교 묶음 전체의 판정(가장 나쁜 실행)이고 `perRun[]`이 실행별이다. 우선순위는
  `DATA_INSUFFICIENT` > `POST_INCOMPLETE` > `OUT_OF_PERIOD` > `NORMAL`이다.
- `OUT_OF_PERIOD`는 **필터 조건이 아니다.** `shownInFiltered`에 영향을 주지 않고 사례를 열 때 확인을 받는다.
- `bundle.instruments[]`의 `listedOn`·`delistedOn`은 ETF 이력 판정의 입력이고, 화면이 `UNLISTED`·
  `DELISTED` 사유를 설명할 근거다. `delistedOn`은 폐지된 종목에만 있다. 사례별 `availability.delistedOn`은
  그 사례의 사후 구간 안에서 폐지가 일어났을 때만 나오므로 둘은 다른 값이다.
- `postWindow`는 시장 달력 기준이라 미완성이면 `d21`·`d63`이 `null`이고 `completedTradingDays`가 진짜
  길이다. 목록의 `postWindow`에는 `lastAvailable`을 싣지 않는다 — 그 값이 필요한 것은 종료일 하한을 잡는
  §1a뿐이라, 사례마다 열 개가 오는 자리에 쓰지 않는 값을 담지 않는다. 실행별로 실제 확보한 거래일 수는 `perRun[].completedTradingDays`이며, 구간 안 상장폐지면
  폐지 전날까지로 줄어든다. 21거래일 이상이고 구간 안 폐지면 `state=NORMAL`·`partial=true`·`delistedOn`이다.
- `entryPath=MARKET_FLOW`면 필터를 계산하지 않는다. `filter.applied=false`이고 `filterKey`·
  `availableRange`·`excludedCount`가 없으며 모든 사례가 `shownInFiltered=true`다. 판정은 그대로 붙어
  결과 자리에 '자료 부족'을 보이게 한다.
- 010 검색이 `NOT_COMPUTABLE`이면 `cases: []`와 `search.status`를 그대로 내보낸다(`200`). 필터 후 0건도 `200`이다.

## 4. 링크 상태 — `GET /regime-links/{linkId}?token=`

| `status` | `result` | 함께 있는 것 |
|---|---|---|
| `PENDING_RUN` | 없다 | `runProgress`에 004의 `status`·`progress`·`queueWaitSeconds`를 그대로 싣는다 |
| `QUEUED`·`RUNNING` | 없다 | `queuedAt`·`startedAt` |
| `COMPLETED` | 있다 | `finishedAt` |
| `FAILED` | 없다 | `failure: {code, message, retryable}` |

- `availability.state`가 `DATA_INSUFFICIENT`·`POST_INCOMPLETE`면 `status=COMPLETED`이고 `result`가
  `null`이다. **실패와 구분한다** — 화면은 '자료 부족'으로 보이고 '다시 시도'를 두지 않는다. 계산 작업을
  만들지 않았으므로 `queuedAt`·`startedAt`이 없고 판정 시각만 `finishedAt`에 남는다.
- `retryable=false`면 '다시 시도'를 두지 않는다. `INPUT_INCOMPLETE`·`MARKET_DATA_MISSING`이
  `attempt ≥ 2`에서 다시 나오면 `retryable=false`다.
- `lossContribution`은 **종목 구간 손익 ÷ 전체 구간 손익**을 섹터별로 합산한 몫이고 합이 1이다.
  이름이 '손실'이지만 **이익 종목도 뺀 것 없이 들어간다** — 전체 손익과 반대로 움직인 섹터가 음수
  몫으로 나온다. 전체 구간 손익이 0일 때만 이 키를 생략한다.
- `turnover`는 값만으로 뜻이 정해지지 않으므로 `turnoverBasis`(`ANNUALIZED_252`)를 함께 싣는다.
  **두 구간 모두 연환산한 값이라** 구간이 짧다고 작아지지 않고, `window21`과 `window63`을 같은 단위로
  나란히 놓을 수 있다. 수익률과 최대 하락 폭은 연환산하지 않는다.
- `holdings`는 구간의 모든 거래일을, `trades`는 구간의 모든 거래를 담는다. 화면이 보유 비중 시계열과
  거래 목록을 그리려면 둘 다 빠짐없이 있어야 한다.
- `tradesPerMonth`는 `tradeCount ÷ (tradingDays ÷ 21)`을 소수 둘째 자리로 맞춘 값이다.
- `scenario=FRESH_CASH`의 결과는 시작 금액 기준이고 `originPublicToken`으로 기준 실행을 가리킨다.

## 5. 사례 열기 — `POST /regime-links`

- 서버는 비교 묶음의 실행마다 `regime_link` 행을 찾거나 만들고, 이 검색이 그 계산을 열었다는 이용
  기록을 남긴다. 링크 행은 여러 사용자가 공유하므로 이미 `COMPLETED`면 작업을 만들지 않고 즉시
  결과를 돌려준다.
- 어느 실행의 분석 기간이 사례 사후 구간을 덮지 않는데 `confirmFullPeriod=false`면 **계산을 만들지 않고**
  `200 OUT_OF_PERIOD_CONFIRM_REQUIRED`를 돌려준다. 확인하면 `confirmFullPeriod=true`로 다시 보내고,
  서버가 004에 전체 기간 실행을 **요청한 사용자 본인의 분석 요청으로** 접수하며 링크는 `PENDING_RUN`이다.
- `scenario=FRESH_CASH`도 004 접수를 거친다. 사용자가 토글을 켤 때만 만들어진다.

## 6. 오류

| code | HTTP | 뜻 |
|---|---|---|
| `AUTHENTICATION_REQUIRED` | 401 | 익명 쓰기 요청. 행도 메시지도 만들지 않는다 |
| `INVALID_INPUT` | 400 | 토큰 형식·`caseId` 형식·알 수 없는 `scenario`·비교 묶음 조건 불일치 |
| `RESOURCE_NOT_FOUND` | 404 | 010 표시 후보에 없는 사례, 다른 사용자의 검색 기록, 링크와 맞지 않는 토큰 |
| `RESULT_EXPIRED` | 410 | 비로그인 결과 토큰 만료(005 코드 재사용) |
| `RESULT_NOT_READY` | 409 | 기준·비교 실행이 아직 `COMPLETED`가 아니다 |
| `NOT_RETRYABLE` | 409 | 재시도할 수 없는 링크에 재시도를 요청했다 |
| `EXECUTION_LIMIT` | 429 | 004 대기열 상한. 본문 `retryAfterSeconds`를 그대로 전달한다 |

## 7. 내보내지 않는 것

| 값 | 왜 |
|---|---|
| 유지율(`retention`) | 기록 전용이다. 응답에 넣으면 신뢰도처럼 읽힌다 |
| 전략과 지수의 차이, 초과 수익, 승패 | 타입에 필드를 두지 않아 화면이 만들 수 없게 한다 |
| 사후 성과순 정렬 키·점수 | 비교 묶음은 005 순서 그대로이고 순위는 010 것만 쓴다 |
| 연율화 수익률 | 63거래일 값을 네 배로 늘리면 "더 벌 수 있다"로 읽힌다 |
| 내부 PK(실행·snapshot 식별자), 요청자 식별자 | 외부 식별자는 공개 토큰과 `RL-`·`RLS-` 뿐이다 |
| "지금과 같은 여건"·"지금도 잘될"·"추천" 문구 | 서버 고정 문구에도 두지 않는다 |
