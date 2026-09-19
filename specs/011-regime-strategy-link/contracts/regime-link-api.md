# Contract: Regime Link REST API v1 (Backend ↔ Frontend)

봉투 `ApiResponse`, 외부 경로 `/api/regime-links`(프록시가 `/api` 제거), 소수는 plain string, 날짜는
거래일 `YYYY-MM-DD`, 월은 `YYYY-MM`, 시각은 UTC ISO-8601. `docs/specs/regime-link-api/README.md`를
이 문서의 요약본으로 만든다. 식별자는 전부 문자열이고 내부 PK는 노출하지 않는다.

## 0. 공통

- **사례 식별자** `caseId` = `{snapshotKey}~{endMonth}` (research R6). `snapshotKey`는 010의 6조각 키
  (`{mode}:{scope}:{baselineMonth}:{dataVersion}:{registryVersion}:{modelVersion}`)이며 011은 분해하지 않고
  문자열로만 다룬다. `endMonth`는 `YYYY-MM`이다. 서버는 **마지막** `~`로 나눠 010을 조회한다. 형식이
  틀리면 `400 INVALID_INPUT`. `caseId`는 경로 변수로 쓰지 않고 항상 질의 인자나 본문으로 보낸다.
- **검색 식별자 두 가지**: 010 API의 `searchId`는 snapshot 키(= 위 `snapshotKey`)이고, 011의
  `linkSearchId`(`RLS-…`)는 §1b가 만드는 연결 기록이다. 서로 다른 값이다.
- **비교 묶음**은 `base`(필수)·`compare`(선택) 두 공개 토큰(005 `publicToken`, 32자 소문자 16진)
  이다. 서버가 실행으로 푼다. 만료된 토큰은 `410 RESULT_EXPIRED`(005 코드 재사용).
- 모든 응답에 `basis: {kind: "HISTORICAL_REVISION"}`가 있다(FR-015). 화면은 이 값을 읽어 "최신
  개정자료를 이용한 역사 탐색"을 표시한다.
- **로그인**: 유효한 공개 주소에 의한 `GET`은 익명 허용. 검색 기록 생성 POST도 인증하며 익명 검색은 GET으로 계속 제공한다. `POST`는 요청자가 없으면 기존 `401 AUTHENTICATION_REQUIRED`(014 코드 재사용, FR-025). 계산은
  시작되지 않는다.

## 1. `GET /regime-links/availability` — 표시 후보에 가용성 판정을 붙인다

### 요청

| 질의 | 필수 | 뜻 |
|---|---|---|
| `base` | ✓ | 비교 기준 공개 토큰 |
| `compare` | | 비교 대상 공개 토큰 |
| `snapshotKey` | | 이전 검색 revision 고정. 있으면 mode·field·baselineMonth 대신 해당 snapshot을 조회 |
| `mode` | snapshotKey 없을 때 | `field` \| `composite` (010 §1과 같다) |
| `field` | snapshotKey 없이 mode=field | 분야 id |
| `baselineMonth` | | `YYYY-MM`. 없으면 010 규칙(T_g/T_all) |
| `entryPath` | ✓ | `MARKET_FLOW` \| `STRATEGY_RESULT`. `MARKET_FLOW`면 필터를 걸지 않고 판정만 붙인다(FR-002) |

### 응답 200

```json
{
  "basis": {"kind": "HISTORICAL_REVISION"},
  "search": {"snapshotKey": "FIELD:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V1", "mode": "field", "field": "LABOR",
             "baselineMonth": "2026-07", "status": "NEAREST_ONLY"},
  "bundle": {
    "runs": [
      {"publicToken": "9f2c…", "role": "BASE", "strategyId": "FIXED_WEIGHT", "strategyName": "내가 정한 비중",
       "period": {"start": "2010-01-04", "end": "2025-12-30", "effectiveStart": "2010-04-02"}, "warmupTradingDays": 63},
      {"publicToken": "b41e…", "role": "COMPARE", "strategyId": "INVERSE_VOLATILITY", "strategyName": "흐름을 따라 배분하기",
       "period": {"start": "2010-01-04", "end": "2025-12-30", "effectiveStart": "2010-04-02"}, "warmupTradingDays": 63}
    ],
    "instruments": [{"sectorId": "SEMICONDUCTOR", "instrumentId": "091160.XKRX", "listedOn": "2006-06-27"}]
  },
  "filter": {
    "applied": true,
    "filterKey": "3a9f…(64자)",
    "availableRange": {"firstCaseEndMonth": "2006-10", "lastCaseEndMonth": "2026-05"},
    "excludedCount": 3,
    "minPostTradingDays": 21
  },
  "cases": [
    {"caseId": "FIELD:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V1~2012-03", "endMonth": "2012-03", "rank": 1, "distance": "0.412938571034",
     "postWindow": {"d0": "2012-04-02", "d21": "2012-05-02", "d63": "2012-07-03", "completedTradingDays": 63, "complete21": true, "complete63": true},
     "availability": {"state": "NORMAL"},
     "perRun": [{"publicToken": "9f2c…", "state": "NORMAL"}, {"publicToken": "b41e…", "state": "NORMAL"}],
     "shownInFiltered": true},
    {"caseId": "FIELD:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V1~2005-11", "endMonth": "2005-11", "rank": 2, "distance": "0.455…",
     "postWindow": {"d0": "2005-12-01", "d21": "2005-12-30", "d63": "2006-03-06", "completedTradingDays": 63, "complete21": true, "complete63": true},
     "availability": {"state": "DATA_INSUFFICIENT", "reason": "UNLISTED"},
     "perRun": [{"publicToken": "9f2c…", "state": "DATA_INSUFFICIENT", "reason": "UNLISTED"}, {"publicToken": "b41e…", "state": "DATA_INSUFFICIENT", "reason": "UNLISTED"}],
     "shownInFiltered": false},
    {"caseId": "FIELD:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V1~2026-05", "endMonth": "2026-05", "rank": 3, "distance": "0.47…",
     "postWindow": {"d0": "2026-06-01", "d21": "2026-06-30", "d63": null, "completedTradingDays": 34, "complete21": true, "complete63": false},
     "availability": {"state": "OUT_OF_PERIOD"},
     "perRun": [{"publicToken": "9f2c…", "state": "OUT_OF_PERIOD"}, {"publicToken": "b41e…", "state": "OUT_OF_PERIOD"}],
     "shownInFiltered": true}
  ]
}
```

- `cases[]`는 **010 표시 후보(≤10) 전부**를 010 순위 순서로 담는다. 필터로 빠진 사례도 담되
  `shownInFiltered=false`다 — 화면은 기본 목록에 `true`만 그리고 '전체 탐색 전환'에서 전부 그린다.
  아래 순위에서 채우지 않으므로 응답에 010 표시 후보 밖의 사례는 없다(Clarifications 09-17 Q4).
- `availability`는 비교 묶음 전체의 판정(가장 나쁜 실행)이고 `perRun`이 실행별이다. 우선순위:
  `DATA_INSUFFICIENT` > `POST_INCOMPLETE` > `OUT_OF_PERIOD` > `NORMAL`. `OUT_OF_PERIOD`는 **필터
  조건이 아니다** — `shownInFiltered`에 영향을 주지 않고 사례를 열 때 확인을 받는다(§2).
- `postWindow.d63`은 미완성이면 `null`이고 `completedTradingDays`가 진짜 길이다. 21거래일 미만이면
  `state=POST_INCOMPLETE`이고 기본 목록에서 빠진다(Clarifications 09-18 Q2).
- `entryPath=MARKET_FLOW`면 `filter.applied=false`, `filterKey`·`excludedCount` 없음, 모든 사례가
  `shownInFiltered=true`. 판정(`availability`)은 그대로 붙어 결과 자리에 '자료 부족'을 보이게 한다.
- 010 검색이 `NOT_COMPUTABLE`이면 `cases: []`와 `search.status`를 그대로 내보낸다(200). 빈 공통 가용
  범위도 200이다(FR-019).

## 1a. `GET /regime-links/case-window?caseId=`

실행 토큰 없이 사례의 postWindow(d0·d21·d63·lastAvailable·completedTradingDays)를 조회한다.
003이 실행 생성 전 기간 제약에 사용한다. 010 snapshotKey를 고정하며 404는 없는 사례,
미완성 d21/d63은 null이다. 공개 조회이며 basis를 포함한다.

## 1b. `POST /regime-link-searches` — 목록 확정·변경 기록 (로그인 필요)

요청: `{clientRequestId, snapshotKey, base, compare?, entryPath, fullSearchShown, previousLinkSearchId?}`.
snapshotKey는 사용자가 본 010 결과로 고정하며 새 revision으로 조용히 바꾸지 않는다.
서버가 §1과 같은 가용성 판정을 수행하고 목록이 비어도 기록한다. 응답 201은 §1 응답에
`linkSearchId`와 변경 시 `bundleChanged: {added, removed, excludedCount}`를 더한다. 유지율은 DB 전용이다.
이전 검색은 같은 요청자·snapshotKey·경로인지 검증하고 다르면 404로 거절한다. 다른 탭의 직전
기록을 임의 선택하지 않는다. 필터 키가 같으면 비교 묶음 변경 알림이 없다.
같은 요청자·clientRequestId의 같은 본문 재시도는 200으로 기존 결과를 반환하며 기록을 중복 생성하지
않는다. 같은 키에 다른 본문이면 `400 INVALID_INPUT`(멱등 키 재사용 불일치). 전체 탐색 전환은 새 clientRequestId로 기록한다.
익명은 §1 GET 응답과 현재 탐색 흐름의 이전 목록으로 변경을 표시하고, 로그인 후 동일 조건으로
이 POST를 호출한다. POST를 못 했다는 이유로 검색을 막지 않는다.

## 2. `POST /regime-links` — 사례의 전략 결과를 연다 (로그인 필요)

```json
{
  "linkSearchId": "RLS-20260918-000031",
  "caseId": "FIELD:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V1~2012-03",
  "scenario": "CONTINUOUS",
  "confirmFullPeriod": false
}
```

- `scenario`: `CONTINUOUS`(기본 화면) \| `FRESH_CASH`(그날 현금 시작, 사용자가 켤 때만).
- 서버는 요청자의 linkSearchId를 조회해 비교 묶음·snapshot·경로를 확정한다. 다른 사용자 기록은 404, 해당 snapshot 표시 후보 밖 사례도 404다. 검색 기록이나 변경 알림을 이 요청에서 생성하지 않는다.
- 서버는 비교 묶음의 실행마다 `regime_link` 행을 찾거나 만든다(`UNIQUE(run, case, scenario)`).
  이미 `COMPLETED`면 작업을 만들지 않고 즉시 결과를 돌려준다(FR-010 재사용).

### 응답 202 — 접수

```json
{
  "basis": {"kind": "HISTORICAL_REVISION"},
  "linkSearchId": "RLS-20260918-000031",
  "links": [
    {"linkId": "RL-20260918-000101", "publicToken": "9f2c…", "scenario": "CONTINUOUS", "status": "QUEUED"},
    {"linkId": "RL-20260918-000102", "publicToken": "b41e…", "scenario": "CONTINUOUS", "status": "COMPLETED"}
  ]
}
```

### 응답 200 — 확인 필요 (`OUT_OF_PERIOD_CONFIRM_REQUIRED`)

비교 묶음의 어느 실행이 사례 사후 구간을 덮지 않는데 `confirmFullPeriod=false`면 **계산을 만들지
않고** 아래를 돌려준다. 화면은 "이 사례를 보려면 전체 기간으로 다시 계산합니다"를 보이고 확인하면
`confirmFullPeriod=true`로 다시 보낸다(Clarifications 09-18 Q1).

```json
{"outcome": "OUT_OF_PERIOD_CONFIRM_REQUIRED",
 "requiredRange": {"start": "2006-10-02", "end": "2025-12-30"},
 "affected": [{"publicToken": "9f2c…", "period": {"start": "2022-01-03", "end": "2025-12-30"}}]}
```

`confirmFullPeriod=true`면 서버가 004 접수 서비스로 `FULL_PERIOD` 실행(공통 가용 범위 전체, 나머지
조건은 기준 실행 사본)을 요청하고 링크는 `PENDING_RUN`이다. 004의 동일 버전 재사용이 작동해 이미
있으면 즉시 `QUEUED`로 넘어간다.

### `scenario=FRESH_CASH`

서버가 004 접수 서비스로 `scenario=FRESH_CASH_FROM`, `scenarioParams.startDate=d0`, `end=d63`
(미완성이면 `lastAvailable`), `start=d0−워밍업`, 나머지는 기준 실행 사본으로 요청한다. 링크는
`PENDING_RUN` → 실행 완료 후 `QUEUED`. 응답에 004 진행 상태를 그대로 싣는다(§3).

### 오류

| code | HTTP | 뜻 |
|---|---|---|
| `AUTHENTICATION_REQUIRED` | 401 | 익명 요청. 계산 없음(014 코드 재사용) |
| `INVALID_INPUT` | 400 | 토큰 형식·`caseId` 형식·알 수 없는 `scenario` |
| `RESOURCE_NOT_FOUND` | 404 | 사례가 010 표시 후보에 없음(표시 후보 밖 사례는 열 수 없다) |
| `RESULT_EXPIRED` | 410 | 비로그인 결과 토큰 만료(005) |
| `RESULT_NOT_READY` | 409 | 기준·비교 실행이 아직 `COMPLETED`가 아님 |
| `EXECUTION_LIMIT` | 429 | 004 대기열 상한(현금 시작·전체 기간 재계산). 본문 `retryAfterSeconds` 그대로 |

## 3. `GET /regime-links/{linkId}` — 링크 상태와 결과

```json
{
  "basis": {"kind": "HISTORICAL_REVISION", "dataVersion": "KRX-2026-09-09"},
  "linkId": "RL-20260918-000101", "publicToken": "9f2c…", "originPublicToken": "9f2c…",
  "caseId": "FIELD:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V1~2012-03", "scenario": "CONTINUOUS",
  "status": "COMPLETED",
  "postWindow": {"d0": "2012-04-02", "d21": "2012-05-02", "d63": "2012-07-03", "completedTradingDays": 63, "complete21": true, "complete63": true},
  "availability": {"state": "NORMAL"},
  "runProgress": null,
  "failure": null,
  "result": {
    "window63": {"tradingDays": 63, "return": "0.0421", "maxDrawdown": "-0.0587", "tradeCount": 6, "tradesPerMonth": "2.00",
                 "turnover": "0.3125", "turnoverBasis": "ANNUALIZED_252", "costs": {"commission": "0", "spread": "0", "slippage": "1523.00", "tax": "0", "total": "1523.00"},
                 "lossContribution": {"SEMICONDUCTOR": "1.20000000", "BIO": "-0.20000000"}, "complete": true},
    "window21": {"tradingDays": 21, "return": "0.0110", "maxDrawdown": "-0.0210", "tradeCount": 2, "tradesPerMonth": "2.00",
                 "turnover": "0.1040", "turnoverBasis": "ANNUALIZED_252", "costs": {"…": "…"}, "lossContribution": {"SEMICONDUCTOR": "1.00000000"}, "complete": true},
    "holdings": [{"date": "2012-04-02", "actual": {"091160.XKRX": "0.3980", "305720.XKRX": "0.3010", "244580.XKRX": "0.3010"}}],
    "trades": [{"date": "2012-05-02", "instrumentId": "091160.XKRX", "side": "SELL", "quantity": 12, "price": "23150", "cost": "254.00", "reason": "REBALANCE"}],
    "kospi": {"window63": {"return": "0.0180", "maxDrawdown": "-0.0420"}, "window21": {"return": "0.0050", "maxDrawdown": "-0.0150"}}
  }
}
```

- `status=PENDING_RUN`이면 `runProgress`에 004 `GET /backtests/{id}`의 `status`·`progress`·
  `queueWaitSeconds`를 그대로 싣는다(화면은 005 비교 대상과 같은 진행 표시).
- `status=QUEUED|RUNNING`이면 `result=null`, `queuedAt`·`startedAt`.
- `status=FAILED`면 `failure: {code, message}`. 화면은 그 자리에 한 문장과 '다시 시도'.
- `availability.state=DATA_INSUFFICIENT`면 `status=COMPLETED`, `result=null`, 화면은 사유
  (`UNLISTED`/`WARMUP_SHORT`)를 '자료 부족'으로. **실패와 구분한다.**
- `availability.partial=true`(구간 안 상장폐지)면 `result`는 `delistedOn` 전날까지고 화면이 부분
  구간임을 표시한다.
- `scenario=FRESH_CASH`의 `result.window63.return`은 `initialCash` 기준이다. 화면은 연속 운용과 다른
  이유 한 문장을 고정 문구로 붙인다(FR-010).

## 4. `GET /regime-links?base=&compare=&caseId=` — 사례 하나의 링크 묶음

`[ {§3 응답} ]`을 비교 묶음 순서(기준 → 비교 대상)로, 시나리오별로 담는다. 화면이 사례를 열 때 한
번 부른다. 없는 조합은 요소가 없다(빈 값을 만들지 않는다).

## 5. `POST /regime-links/{linkId}/retry` — 실패한 링크 다시 시도 (로그인 필요)

`202 {linkId, status}`. `FAILED`에서만, 아니면 `409 NOT_RETRYABLE`. `RUN_FAILED`면 004 `retry`를
호출해 새 실행을 만들고 `PENDING_RUN`. 자동으로 다시 실행하지 않는다(Edge Cases).

## 6. 내보내지 않는 것

| 값 | 왜 |
|---|---|
| 유지율(`retention`) | 기록 전용(FR-021, Clarifications 09-17 Q5). 신뢰도처럼 읽힌다 |
| 전략 − KOSPI 차이, 초과 수익, 승패 | FR-013·원칙 III. 타입에 필드를 두지 않는다 |
| 사후 성과순 정렬 키·점수 | 비교 묶음은 005 순서 그대로. 순위는 010 것만 |
| 연율화 수익률 | 63거래일 값을 4배 확대해 "더 벌 수 있다"로 읽힌다(research R3) |
| 내부 PK(`run_id`·`snapshot_id`), 요청자 id | ADR-0002 D2, 원칙 VIII |
| "지금과 같은 여건", "지금도 잘될", "추천" 문구 | FR-014·FR-016·SC-007. 서버 고정 문구에도 두지 않는다 |
