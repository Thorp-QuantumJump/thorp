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
  이다. 서버가 005 `PublicResultService.resolve`와 같은 규칙(형식 → 존재 → 만료)으로 **분석 요청**과 그 실행으로
  푼다. 만료된 토큰은 `410 RESULT_EXPIRED`, 없는 토큰은 `404`(005 코드 재사용).
- **비교 묶음 조건 대조**: 두 토큰은 주소로 직접 들어올 수 있으므로 서버가 섹터 후보 집합·대응표 버전·
  시작 금액·비용 조건 버전·자료 버전이 같은지 확인하고, 다르면 계산도 기록도 만들지 않고
  `400 INVALID_INPUT`과 어긋난 항목 이름을 돌려준다(T093). §1과 §1b가 판정보다 먼저 이 관문을 지난다.
- 모든 응답에 `basis: {kind: "HISTORICAL_REVISION"}`가 있다(FR-015). 화면은 이 값을 읽어 "최신
  개정자료를 이용한 역사 탐색"을 표시한다.
- **로그인**: 유효한 공개 주소에 의한 `GET`은 익명 허용. 검색 기록 생성 POST도 인증하며 익명 검색은 GET으로 계속 제공한다. `POST`는 요청자가 없으면 기존 `401 AUTHENTICATION_REQUIRED`(014 코드 재사용, FR-025). 계산은
  시작되지 않는다.
- **공개 조회의 접근 근거**: 링크를 돌려주는 `GET`(§3·§4)은 그 링크가 붙은 분석의 공개 토큰을 함께 받는다.
  토큰이 없으면 `400`, 만료면 `410`, 토큰의 요청과 링크가 맞지 않으면 `404`다. 응답에는 제시된 토큰 외의
  토큰을 싣지 않는다. 011 화면 주소에 이미 `base`·`compare`가 있어 사용자가 따로 입력하지 않는다(research R14).

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
       "period": {"start": "2010-01-04", "end": "2025-12-30", "effectiveStart": "2010-04-02"}, "warmupTradingDays": 63,
       "costProfileVersion": "KR-ETF-2026-09", "dataVersion": "KRX-2026-09-09"},
      {"publicToken": "b41e…", "role": "COMPARE", "strategyId": "INVERSE_VOLATILITY", "strategyName": "흐름을 따라 배분하기",
       "period": {"start": "2010-01-04", "end": "2025-12-30", "effectiveStart": "2010-04-02"}, "warmupTradingDays": 63,
       "costProfileVersion": "KR-ETF-2026-09", "dataVersion": "KRX-2026-09-09"}
    ],
    "instruments": [{"sectorId": "SEMICONDUCTOR", "instrumentId": "091160.XKRX", "listedOn": "2006-06-27"},
                    {"sectorId": "BIO", "instrumentId": "244580.XKRX", "listedOn": "2006-06-27", "delistedOn": "2018-11-15"}]
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
- **`perRun`의 판정이 실행마다 갈릴 수 있는 길은 둘뿐이다**: 전략마다 다른 워밍업 길이에서 오는
  `WARMUP_SHORT`와, 기존 실행의 평가 시작·결과 종료가 사례 구간을 덮지 못해 생기는 `OUT_OF_PERIOD`다.
  나머지 사유(`UNLISTED`·`SECTOR_UNMAPPED`·`RECENT`·`DELISTED`)는 섹터 후보 집합·대응표 버전·자료
  버전에서 나오는데, 서버가 §0의 비교 묶음 조건 대조에서 그 셋이 다른 묶음을 **판정 전에 거절하므로**
  (`400 INVALID_INPUT`, T093) 두 실행이 같은 값을 갖는다. 그래도 `perRun`을 남기는 것은 위 두 사유와
  실행별 `completedTradingDays`를 화면이 실행 자리마다 따로 보여야 하기 때문이다.
- `bundle.runs[]`의 `costProfileVersion`·`dataVersion`은 그 실행이 고정한 비용 조건 버전과 자료 버전이다.
  화면이 결과 머리에 "같은 평가 날짜·비용 버전"을 보이는 근거이고(FR-011·SC-003·T061), 비교 묶음 조건 대조의 입력과
  같은 값이다. 004 등록표가 값을 소유하며 011은 옮겨 싣기만 한다.
- `postWindow.d63`은 미완성이면 `null`이고 `completedTradingDays`가 진짜 길이다. 21거래일 미만이면
  `state=POST_INCOMPLETE`이고 기본 목록에서 빠진다(Clarifications 09-18 Q2). `postWindow`는 시장 달력 기준이고,
  실행별 실제 확보 거래일 수는 `perRun[].completedTradingDays`다 — 구간 안 상장폐지면 폐지 전날까지로 줄고
  `reason`이 `DELISTED`(최근 사례는 `RECENT`)다. 21 이상이고 구간 안 폐지면 `state=NORMAL`·`partial=true`·
  `delistedOn`이다. 기준월이 자료 마감월보다 과거면 완성 판정 기준일은 그 기준월의 마지막 거래일이다(research R4).
- `bundle.instruments[]`의 `listedOn`·`delistedOn`은 ETF 이력 판정의 입력이자 화면이 사유를 설명할 근거다.
  `delistedOn`은 폐지된 종목에만 있다(키 생략 = 상장 유지). 사례별 `availability.delistedOn`은 그 사례의
  사후 구간 안에서 폐지가 일어났을 때만 나오므로 둘은 다른 값이다.
- 사유 값: `DATA_INSUFFICIENT` → `UNLISTED`\|`WARMUP_SHORT`\|`SECTOR_UNMAPPED`, `POST_INCOMPLETE` →
  `RECENT`\|`DELISTED`. 이 둘은 계산 작업을 만들지 않는 판정이며 '다시 시도'가 없다(research R13).
- `SECTOR_UNMAPPED`는 사례 관측 종료월에 **유효한 대응표 버전이 있을 때만** 판정한다. 대응표 이력이 그 앞까지 닿지 않으면 대응표 판정을 건너뛰고
  나머지 판정은 그대로 한다(research R4, T109).
- `entryPath=MARKET_FLOW`면 필터를 계산하지 않으므로 `filter.applied=false`이고 `filterKey`·
  `availableRange`·`excludedCount`가 **없다**(`minPostTradingDays`만 남는다). 모든 사례가
  `shownInFiltered=true`이며, 판정(`availability`)은 그대로 붙어 결과 자리에 '자료 부족'을 보이게 한다.
- 010 검색이 `NOT_COMPUTABLE`이면 `cases: []`와 `search.status`를 그대로 내보낸다(200). 빈 공통 가용
  범위도 200이다(FR-019). 그때 `availableRange`는 **키를 지우지 않고** 양끝을 `null`로 보낸다 — 키가 통째로
  없는 경우는 `MARKET_FLOW` 하나이며, 빈 범위에서도 지우면 "필터를 걸었는데 덮는 사례가 없다"와 "필터를 걸지
  않았다"가 같은 모양이 된다.
- `bundle.instruments[].sectorId`는 실행이 어느 섹터로 그 종목을 골랐는지 모를 때(접수 기록이 없는 옛 실행)
  생략된다. 종목에서 섹터를 거꾸로 찾아 채우지 않는다 — 그 실행이 고르지 않은 섹터를 판정에 넣게 된다.

## 1a. `GET /regime-links/case-window?caseId=`

실행 토큰 없이 사례의 postWindow(d0·d21·d63·lastAvailable·completedTradingDays)를 조회한다.
003이 실행 생성 전 기간 제약에 사용한다. 010 snapshotKey를 고정하며 404는 없는 사례,
미완성 d21/d63은 null이다. 공개 조회이며 basis를 포함한다. 예시는
`docs/specs/regime-link-api/examples/case-window.json`이다.

`lastAvailable`은 **여기에만** 있다. §1의 `cases[].postWindow`에는 싣지 않는다 — 그 값을 쓰는 것은
종료일 하한을 잡는 003뿐이고, 사례가 열 개까지 오는 목록에 화면이 읽지 않는 값을 담지 않는다.

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
- 서버는 비교 묶음의 실행마다 `regime_link` 행을 찾거나 만들고(`UNIQUE(run, case, scenario)`), 이 검색이
  그 계산을 열었다는 `regime_link_use`(원래 요청·역할)를 남긴다. 링크 행은 여러 사용자가 공유한다.
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

### 응답 200 — 확인 필요 (`*_CONFIRM_REQUIRED`)

계산을 만들기 전에 사용자의 확인이 필요한 경우가 **둘**이고, `outcome`이 그 둘을 가른다. 어느 쪽이든
`confirmFullPeriod=false`면 **링크도 메시지도 만들지 않고** 아래 모양을 돌려주며, 확인하면
`confirmFullPeriod=true`로 같은 요청을 다시 보낸다(Clarifications 09-18 Q1).

| `outcome` | 언제 | 화면 문구의 뜻 |
|---|---|---|
| `OUT_OF_PERIOD_CONFIRM_REQUIRED` | 어느 실행의 분석 기간이 사례 사후 구간을 덮지 않는다 | "기간이 모자라 다시 계산한다" |
| `FILLS_MISSING_CONFIRM_REQUIRED` | 기간은 덮지만 그 실행에 개별 체결 기록이 없다(`backtest_result.fills_recorded=false`, 옛 워커의 결과) | "이 결과는 사후 구간을 보려면 다시 계산한다" |

둘을 한 이름으로 합치지 않는 것은 사용자가 읽을 이유가 다르기 때문이다. 기간 밖을 먼저 보고, 기간이
덮이는 실행만 체결 기록을 본다. 본문 모양(`requiredRange`·`affected`)은 둘이 같다.

```json
{"outcome": "OUT_OF_PERIOD_CONFIRM_REQUIRED",
 "requiredRange": {"start": "2006-10-02", "end": "2025-12-30"},
 "affected": [{"publicToken": "9f2c…", "period": {"start": "2022-01-03", "end": "2025-12-30"}}]}
```

`confirmFullPeriod=true`면 서버가 004 접수 서비스로 `FULL_PERIOD` 실행(공통 가용 범위 전체, 나머지
조건은 기준 실행 사본)을 **요청한 사용자 본인의 분석 요청으로** 접수하고 링크는 `PENDING_RUN`이다. 실행이
재사용돼도 004는 호출자마다 새 요청과 새 공개 토큰을 만든다. 004의 동일 버전 재사용이 작동해 이미
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

## 3. `GET /regime-links/{linkId}?token=` — 링크 상태와 결과

```json
{
  "basis": {"kind": "HISTORICAL_REVISION", "dataVersion": "KRX-2026-09-09"},
  "linkId": "RL-20260918-000101", "publicToken": "9f2c…",
  "caseId": "FIELD:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V1~2012-03", "scenario": "CONTINUOUS",
  "status": "COMPLETED",
  "postWindow": {"d0": "2012-04-02", "d21": "2012-05-02", "d63": "2012-07-03", "completedTradingDays": 63, "complete21": true, "complete63": true},
  "availability": {"state": "NORMAL"},
  "runProgress": null,
  "failure": null,
  "result": {
    "window63": {"tradingDays": 63, "return": "0.0421", "maxDrawdown": "-0.0587", "tradeCount": 6, "tradesPerMonth": "2.00",
                 "turnover": "0.3125", "turnoverBasis": "ANNUALIZED_252", "costs": {"commission": "0", "spread": "0", "slippage": "1523.00", "tax": "0", "total": "1523.00"},
                 "lossContribution": {"SEMICONDUCTOR": "1.20000000", "BATTERY": "0.32000000", "BIO": "-0.52000000"}, "complete": true},
    "window21": {"tradingDays": 21, "return": "0.0110", "maxDrawdown": "-0.0210", "tradeCount": 2, "tradesPerMonth": "2.00",
                 "turnover": "0.4211", "turnoverBasis": "ANNUALIZED_252", "costs": {"…": "…"}, "lossContribution": {"SEMICONDUCTOR": "0.84000000", "BATTERY": "0.38000000", "BIO": "-0.22000000"}, "complete": true},
    "holdings": [{"date": "2012-04-02", "actual": {"091160.XKRX": "0.3980", "305720.XKRX": "0.3010", "244580.XKRX": "0.3010"}}],
    "trades": [{"date": "2012-05-02", "instrumentId": "091160.XKRX", "side": "SELL", "quantity": 12, "price": "23150", "cost": "254.00", "reason": "REBALANCE"}],
    "kospi": {"window63": {"return": "0.0180", "maxDrawdown": "-0.0420"}, "window21": {"return": "0.0050", "maxDrawdown": "-0.0150"}}
  }
}
```

- `token`(필수)은 이 링크를 연 분석의 공개 토큰이다. 응답의 `publicToken`은 제시된 값 그대로이고 다른 토큰은
  싣지 않는다. '전체 기간 결과 보기'는 이 토큰의 `/results/{token}`이다(현금 시작·전체 기간 재계산 링크도 같다).
- `status=PENDING_RUN`이면 `runProgress`에 004 `GET /backtests/{id}`의 `status`·`progress`·
  `queueWaitSeconds`를 그대로 싣는다(화면은 005 비교 대상과 같은 진행 표시).
- `status=QUEUED|RUNNING`이면 `result=null`, `queuedAt`·`startedAt`.
- 계산 작업을 만들지 않은 판정(`DATA_INSUFFICIENT`·`POST_INCOMPLETE`)은 `status=COMPLETED`이지만 큐에
  들어간 적이 없으므로 `queuedAt`·`startedAt`이 `null`이고 판정 시각만 `finishedAt`에 있다.
- `lossContribution`을 생략할 때는 값을 `null`로 두지 않고 **키 자체가 없다.** 워커가 키를 빼고 보내면
  저장된 결과에도 응답에도 그 키가 없으며, 화면은 키의 유무로 표시 여부를 가른다. 생략하는 경우는
  전체 구간 손익이 0일 때 하나뿐이다.
- `turnover`는 두 구간 모두 `ANNUALIZED_252`로 환산한 값이다. 구간이 짧다고 값이 작아지지 않으므로
  `window21`과 `window63`을 같은 단위로 나란히 놓을 수 있다.
- `holdings`는 구간의 **모든 거래일**을, `trades`는 구간의 **모든 거래**를 담는다 — `trades`의 개수는
  `window63.tradeCount`와 같다. **위 예시는 지면 때문에 둘 다 한 행만 싣는다.** 전체 모양은
  `docs/specs/regime-link-api/examples/link-completed.json`을 본다(그 파일은 `trades`를 줄이지 않는다).
- `status=FAILED`면 `failure: {code, message, retryable}`. 화면은 그 자리에 한 문장을 보이고 `retryable=true`일
  때만 '다시 시도'를 둔다. `INPUT_INCOMPLETE`·`MARKET_DATA_MISSING`이 `attempt ≥ 2`에서 다시 나오면
  `retryable=false`다. 실패 문구는 사용자의 조건을 원인으로 말하지 않는다(research R13).
- `availability.state=DATA_INSUFFICIENT`면 `status=COMPLETED`, `result=null`, 화면은 사유
  (`UNLISTED`/`WARMUP_SHORT`/`SECTOR_UNMAPPED`)를 '자료 부족'으로. **실패와 구분한다.**
- `availability.partial=true`(구간 안 상장폐지, 확보 21거래일 이상)면 `result`는 `delistedOn` 전날까지고
  `window63.tradingDays`가 그 길이다. `window21`은 완성이다. 화면이 부분 구간임을 표시한다.
- `availability.state=POST_INCOMPLETE`(확보 21거래일 미만)면 `status=COMPLETED`, `result=null`이고
  `completedTradingDays`와 사유(`RECENT`/`DELISTED`)만 있다.
- `scenario=FRESH_CASH`의 `result.window63.return`은 `initialCash` 기준이다. 화면은 연속 운용과 다른
  이유 한 문장을 고정 문구로 붙인다(FR-010).

## 4. `GET /regime-links?base=&compare=&caseId=` — 사례 하나의 링크 묶음

`[ {§3 응답} ]`을 비교 묶음 순서(기준 → 비교 대상)로, 시나리오별로 담는다. 화면이 사례를 열 때 한
번 부른다. 없는 조합은 요소가 없다(빈 값을 만들지 않는다). `base`·`compare` 토큰은 §0의 규칙으로 검증하고, 각
요소의 `publicToken`은 그 요소가 속한 쪽의 제시된 토큰이다.

## 5. `POST /regime-links/{linkId}/retry` — 실패한 링크 다시 시도 (로그인 필요)

`202 {linkId, status}`. `FAILED`이고 `retryable=true`일 때만, 아니면 `409 NOT_RETRYABLE`. `RUN_FAILED`면 004 `retry`를
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
