# 자동 파라미터 탐색 HTTP 계약 (Backend ↔ Frontend)

spec [007](../../../specs/007-auto-parameter-search/spec.md)의 프론트가 쓰는 HTTP 계약이다. 설계 근거와
결정 이력은 [`specs/007-auto-parameter-search/contracts/param-search-api.md`](../../../specs/007-auto-parameter-search/contracts/param-search-api.md)가
원본이고, **이 문서는 구현된 응답을 기준으로 적는다.** 둘이 어긋나면 코드와 이 문서를 먼저 맞춘 뒤 원본을 고친다.

[`../backtest-api/README.md`](../backtest-api/README.md)의 규약을 그대로 상속한다. 다시 정하지 않는 것:

- 응답 껍데기 `{ "success": bool, "data": ..., "error": ... }`
- 오류 포맷 `error: { code, message }`
- **금액·비율은 요청·응답 모두 문자열**(부동소수 오차 방지)
- 식별자는 문자열이며 DB PK(숫자)를 노출하지 않는다. 길이를 가정하지 않는다

식별자 형식은 둘이다.

- `searchId` — `PS-{yyyyMMdd}-{6자리 이상 순번}` (예: `PS-20260914-000011`)
- `candidateId` — `{searchId}-C{3자리 이상 순번}` (예: `PS-20260914-000011-C001`)

## 요청자 식별

`POST /param-searches`·`/cancel`·`/retry-failed`는 **요청자를 요구한다.** 실행이 004의 접수 유스케이스로
가는데 `backtest_request.requester_id`가 NOT NULL이기 때문이다(spec 004 R-12). 요청자는 spec 014 로그인 세션의
주체이고, 로그인하지 않은 요청은 `401 AUTHENTICATION_REQUIRED`다.

취소·재시도는 **소유자만** 한다. 남의 묶음은 403이 아니라 `404 RESOURCE_NOT_FOUND`로 숨긴다(spec 004 R-12와
같은 규칙) — 403은 그 식별자가 존재한다는 사실을 알려 주고 `searchId`는 날짜+순번이라 추측할 수 있다. 소유자가
없는 과거 묶음(인증 이전에 만들어진 행)도 404다.

미리보기와 조회는 요청자가 없어도 된다 — 아무것도 저장하지 않고 실행도 만들지 않는다.

---

## 1. 실행 전 확인 — `POST /param-searches/preview`

저장하지 않고 계산만 한다. 확인 화면의 네 항목(대상 전략·탐색 범위 요약·예상 작업량·적용 한도)을 채운다.
**사용자에게 후보 숫자를 입력받지 않는다.**

### 요청

| 경로 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `universe.instrumentIds` | `string[]` | 필수 | 1~50개. `{6자리}.XKRX`. 중복 금지 |
| `strategy.strategyIds` | `string[]` | 필수 | 1개 이상. 각 64자 이하 |
| `run.start` / `run.end` | `date` | 선택 | 생략 시 공통 평가 구간을 서버가 제시한다 |
| `portfolio.initialCash` | `string` | 선택 | 생략 시 서버 기본값 |
| `space` | `object` | 선택 | 전략 ID → (축 이름 → 후보 값 배열). 생략하면 등록표 전체 범위 |
| `registryVersion` | `string` | 실행만 필수 | 미리보기에서는 무시한다 |

`space`는 **전략별로 중첩**한다. 축 이름이 전략마다 겹칠 수 있기 때문이다.

```json
{ "space": { "INVERSE_VOLATILITY": { "lookbackDays": [21, 63] } } }
```

**`space`에는 등록표의 후보값만 넣을 수 있다.** `allowedRange` 안이라도 등록된 후보값이 아니면
`400 UNREGISTERED_PARAMETER_VALUE`다. `allowedRange`는 화면 표시용이며 입력 허용 범위가 아니다.

### 200 응답

```json
{
  "success": true,
  "data": {
    "registryVersion": "PROVISIONAL-20260911",
    "strategies": [
      { "strategyId": "INVERSE_VOLATILITY", "name": "역변동성", "executable": true }
    ],
    "spaceSummary": [
      {
        "strategyId": "INVERSE_VOLATILITY",
        "strategyName": "역변동성",
        "axes": [
          {
            "axis": "lookbackDays", "label": "변동성 관측 기간", "unit": "거래일",
            "values": [21, 63, 126, 252], "defaultValue": 63,
            "allowedRange": { "min": 10, "max": 504, "step": 1, "values": null }
          }
        ],
        "combinations": 4
      }
    ],
    "workload": {
      "totalCombinations": 4, "validCombinations": 4, "excludedCombinations": 0,
      "exclusionReasons": []
    },
    "limits": {
      "maxCandidates": 100, "timeLimitSeconds": null,
      "dispatchWindow": 1, "consecutiveFailureLimit": 5,
      "provisional": ["maxCandidates", "dispatchWindow", "consecutiveFailureLimit"],
      "exceeded": false
    },
    "commonPeriod": { "start": "2015-12-22", "end": "2026-09-14", "shortenedBy": "lookbackDays" },
    "validationLabel": "UNVALIDATED",
    "validationNote": "평가 분할이 등록되지 않아 결과는 모두 미검증으로 표시됩니다"
  },
  "error": null
}
```

- **`strategies`·`spaceSummary`의 모든 값은 등록표 조회 결과다.** 화면은 전략 이름·축 라벨·단위·기본값·
  후보값·허용 범위를 자체적으로 갖지 않는다(FR-025·FR-027). `executable`은 004의 실행 지원 여부다.
- `axes` 순서가 그리드 좌표 축 순서이고, `values` 순서가 인접 판정의 근거다(FR-029).
- `defaultValue`는 등록된 기본 시작값이며 **추천값이나 최적값이 아니다.** '추천'·'최적' 문구를 붙이지 않는다.
- **`limits.timeLimitSeconds`는 `null`이면 실행시간 상한을 두지 않는다는 뜻이다**(2026-09-23). 묶음은
  후보를 다 돌거나 연속 실패 한도·취소로만 끝난다. 0으로 접지 말 것 — 화면이 없는 마감을 말하게 된다.
  묶음 크기를 막는 것은 `maxCandidates` 하나다.
- `limits.provisional`에 든 항목은 임시값 표시를 붙인다. 상한을 두지 않은 `timeLimitSeconds`는 이 목록에
  실리지 않는다 — 없는 한도에 임시값 표시를 붙일 수 없다.
- `limits.exceeded`가 `true`면 실행은 거부된다. 화면은 범위를 좁히라고 안내한다.
- `validationLabel`을 **실행 전에도** 준다 — 결과를 본 뒤에야 미검증을 알면 이미 오해가 생긴 뒤다(FR-015).

**등록표에 없는 전략은 `strategies` 목록에서 빠진다.** 실행 미지원 전략은 `executable: false`로 실린다.
(구현 기준. 원본 계약은 미리보기에서도 422를 적어 두었으나 코드는 200으로 답한다 — 아래 "알려진 차이" 참고.)

---

## 2. 탐색 실행 — `POST /param-searches`

요청 필드는 §1과 같고 `registryVersion`이 **필수**다. 미리보기 응답의 값을 그대로 되돌려 보낸다.
응답은 `202 Accepted`.

```json
{
  "success": true,
  "data": {
    "searchId": "PS-20260914-000011", "status": "RUNNING",
    "validationLabel": "UNVALIDATED",
    "validationNote": "평가 분할이 등록되지 않아 결과는 모두 미검증으로 표시됩니다"
  },
  "error": null
}
```

접수 직후 뜻이 있는 값은 `searchId`·`status`와 검증 표시뿐이다. 나머지 필드는 `null`이며 화면은
`searchId`로 §3 폴링을 시작한다.

### 거부 (실행하지 않음)

| HTTP | `error.code` | 언제 | 묶음 저장 |
|---|---|---|---|
| 422 | `CANDIDATE_LIMIT_EXCEEDED` | 유효 후보 수 > 후보 수 상한 | `REJECTED`로 저장 |
| 422 | `NO_VALID_COMBINATION` | 등록된 조합이 모두 유효성 제약 위반 | `REJECTED`로 저장 |
| 422 | `STRATEGY_NOT_REGISTERED` | 등록표 활성 전략에 없음 | `REJECTED`로 저장 |
| 422 | `STRATEGY_NOT_EXECUTABLE` | 등록됐으나 004가 실행을 지원하지 않음 | `REJECTED`로 저장 |
| 409 | `REGISTRY_VERSION_CHANGED` | 요청의 `registryVersion`이 접수 시점과 다름 | 저장하지 않음 |
| 503 | `REGISTRY_UNAVAILABLE` | 등록 정보·실행 지원 목록 조회 실패 | 저장하지 않음 |

앞의 넷은 **표본을 자동으로 뽑거나 간격을 넓혀 실행하지 않고** 묶음을 `REJECTED`로 저장해 사유를 남긴다
(FR-020) — 재현 기록이므로 조용히 버리지 않는다. 후보 행은 만들지 않는다.

뒤의 둘은 묶음을 저장하지 않는다. 버전 드리프트는 다시 확인하면 해소되고 조회 실패는 일시적 장애다.
`REGISTRY_VERSION_CHANGED`를 받은 화면은 미리보기를 다시 호출해 바뀐 요약을 보이고 확인을 받은 뒤
다시 실행한다. **등록 정보를 캐시나 기본값으로 대체해 실행하지 않는다.**

---

## 3. 묶음 상태·결과 — `GET /param-searches/{searchId}`

폴링 대상이다. **실패해도 200**이며 `status`로 판단한다.

```json
{
  "success": true,
  "data": {
    "searchId": "PS-20260914-000011",
    "status": "PARTIAL",
    "stopReason": "CANCELLED",
    "counts": { "expected": 5, "valid": 5, "completed": 3, "failed": 1, "excluded": 0, "pending": 0, "skipped": 1 },
    "partial": true,
    "partialNote": "유효 후보 5개 중 4개까지 계산했습니다. 1개는 실행되지 않았습니다",
    "config": {
      "strategyIds": ["INVERSE_VOLATILITY", "EQUAL_WEIGHT"],
      "instrumentIds": ["244580.XKRX"],
      "registryVersion": "PROVISIONAL-20260911",
      "searchMethod": "FULL_GRID",
      "randomSeed": null,
      "space": {
        "INVERSE_VOLATILITY": {
          "strategyName": "역변동성",
          "axes": [
            { "name": "lookbackDays", "label": "변동성 관측 기간", "unit": "거래일", "values": [21, 63, 126, 252] }
          ]
        }
      },
      "selectionRule": {
        "drawdownFilter": {
          "type": "NOT_WORSE_THAN_BASELINE",
          "baseline": "EQUAL_WEIGHT",
          "baselineName": "동일 비중"
        },
        "stabilityFilter": { "type": "BATCH_PERCENTILE", "keepTopPercent": 50 },
        "sortMetric": "NET_RETURN",
        "displayCount": 5,
        "tieHandling": "SHOW_ALL_TIED",
        "stabilityFormula": { "neighbor": "SINGLE_AXIS_ONE_STEP", "value": "MEAN_ABS_NET_RETURN_DIFF" },
        "provisional": ["displayCount"]
      },
      "limits": { "maxCandidates": 100, "timeLimitSeconds": null, "dispatchWindow": 1, "consecutiveFailureLimit": 5, "provisional": ["..."] },
      "referenceVersions": { "marketData": "FIXTURE-three_etf_24m", "registry": "PROVISIONAL-20260911", "cost": "KR-ETF-2026-09", "engine": "nautilus_trader 1.219.0 / worker 2.0.0" },
      "dataVersionChanged": false
    },
    "baseline": {
      "strategyId": "EQUAL_WEIGHT",
      "strategyName": "동일 비중",
      "backtestId": "BT-20260914-000015",
      "status": "COMPLETED",
      "netReturn": "0.100000",
      "maxDrawdown": "-0.200000"
    },
    "validationLabel": "UNVALIDATED",
    "validationNote": "평가 분할이 등록되지 않아 결과는 모두 미검증으로 표시됩니다",
    "selection": {
      "displayed": ["PS-20260914-000011-C001", "PS-20260914-000011-C002"],
      "shortOfDisplayCount": true,
      "note": "기준을 충족한 후보가 2개입니다",
      "drawdownFilterApplied": false
    },
    "startedAt": "2026-09-14T02:11:04Z",
    "finishedAt": "2026-09-14T02:19:51Z"
  },
  "error": null
}
```

### 상태

```
QUEUED ─▶ RUNNING ─┬─▶ COMPLETED   모든 유효 후보가 완료 또는 실패로 끝남
                   ├─▶ PARTIAL     취소·한도 도달로 미실행 후보가 남음
                   └─▶ FAILED      접수 후 실행 자체가 불가
QUEUED ─▶ REJECTED                 접수 시점 거부
COMPLETED·PARTIAL ─▶ RUNNING       실패 후보 재시도로 다시 열림 (§5.1)
```

`stopReason`은 `CANCELLED` `CANDIDATE_LIMIT` `TIME_LIMIT` `FAILURE_LIMIT` `NO_VALID_CANDIDATE`
`STRATEGY_NOT_REGISTERED` `STRATEGY_NOT_EXECUTABLE` 중 하나이며 그 외에는 `null`이다.

**취소·한도로 멈춘 직후에도 `status`는 아직 `RUNNING`일 수 있다.** 이미 큐에 든 후보가 완주하는 동안은
그것이 사실이고, 그때 `stopReason`이 먼저 채워진다. 화면은 `stopReason != null`을 "멈추는 중"으로 읽는다.

### `selection`과 `baseline`

- `selection`은 후보가 완료될 때마다 현재 완료 후보 기준으로 갱신된다. 실행 중 값은 잠정값이며 새 결과에
  따라 표시 순서와 통과 여부가 바뀔 수 있고, `COMPLETED`·`PARTIAL`에서 확정된다.
- **`selection.displayed`는 순위가 아니다.** 정렬 지표(`sortMetric`)를 밝힌 집합이며 화면에 순위 번호를
  붙이지 않는다. 정렬 지표 이름을 함께 보인다.
- `shortOfDisplayCount`가 `true`면 기준을 충족한 후보가 `displayCount`에 못 미친 것이다. **문턱을 낮춰
  채우지 않는다**(원칙 VII). `note`에 몇 개가 충족했는지 문장으로 있다.
- `drawdownFilterApplied`가 `false`면 손실 제약을 걸지 않았다 — 등록표가 기준 전략을 지정하지 않았거나
  기준 실행이 끝나지 않았거나 실패한 경우다. 화면에 그 사실을 표시한다.
- `baseline`은 기준 실행을 만들지 않았으면 전체가 `null`이다. 만들었지만 아직 안 끝났으면 `netReturn`·
  `maxDrawdown`이 `null`이다. `backtestId`로 기준의 상세를 열 수 있다.

---

## 4. 후보 목록 — `GET /param-searches/{searchId}/candidates`

| 파라미터 | 기본값 | 규칙 |
|---|---|---|
| `page` / `size` | `0` / `50` | `size` 1~200. 벗어나면 400 |
| `status` | 없음(전체) | `PENDING` `DISPATCHED` `COMPLETED` `FAILED` `EXCLUDED` `SKIPPED`. 대소문자·앞뒤 공백을 흡수하고 모르는 값은 400 |
| `selected` | 없음(전체) | `true` / `false` |
| `sort` | `candidateNo` | `candidateNo` `netReturn` `maxDrawdown` `tradingCost` `stability`만 허용. 그 외 400 |

`sort`에 방향을 붙일 수 있다(`sort=netReturn,desc`). 생략하면 지표는 내림차순, 순번은 오름차순이다.
**`asc`·`desc` 외의 방향은 400이다** — 오타를 기본값으로 접으면 목록이 조용히 뒤집힌다.

```json
{
  "success": true,
  "data": {
    "page": 0, "size": 50, "totalElements": 5, "totalPages": 1,
    "content": [
      {
        "candidateId": "PS-20260914-000011-C001",
        "candidateNo": 1,
        "strategyId": "INVERSE_VOLATILITY",
        "parameters": { "lookbackDays": 21 },
        "status": "COMPLETED",
        "backtestId": "BT-20260914-000011",
        "netReturn": "0.300000",
        "maxDrawdown": "-0.100000",
        "tradingCost": "17078.49",
        "stability": { "calculable": true, "value": "0.05000000", "neighborCount": 1 },
        "retryCount": 0,
        "validationLabel": "UNVALIDATED",
        "selected": true,
        "selectionReason": { "outcome": "SELECTED", "tied": false, "passedFilters": ["DRAWDOWN", "STABILITY"] }
      },
      {
        "candidateId": "PS-20260914-000011-C005",
        "candidateNo": 5,
        "strategyId": "EQUAL_WEIGHT",
        "parameters": {},
        "status": "SKIPPED",
        "backtestId": null,
        "netReturn": null, "maxDrawdown": null, "tradingCost": null,
        "stability": null,
        "retryCount": 0,
        "validationLabel": "UNVALIDATED",
        "selected": false,
        "selectionReason": { "outcome": "NOT_EVALUATED", "code": "CANCELLED", "message": "사용자가 취소해 실행되지 않았습니다" }
      }
    ]
  },
  "error": null
}
```

**성과 필드가 `null`인 것과 0인 것은 다르다.** `EXCLUDED` `FAILED` `SKIPPED` `PENDING` 후보의
`netReturn`·`maxDrawdown`·`tradingCost`는 항상 `null`이고 0으로 채우지 않는다(FR-011). `stability`도
선정 계산 전이면 `null`, 이웃이 없으면 `calculable: false`에 `value: null`이다. **화면은 이 값을 "0%"가
아니라 사유와 함께 표시한다.**

`backtestId`는 **요청**의 식별자다 — `GET /backtests/{id}`로 여는 값이 그것이다. 발행 전·제외 후보는 `null`이다.

### `selectionReason.outcome`

| 값 | 뜻 | 함께 오는 것 |
|---|---|---|
| `SELECTED` | 표시 후보 | `tied`, `passedFilters` |
| `NOT_IN_DISPLAY` | 두 필터를 통과했으나 표시 범위 밖 | `passedFilters` |
| `FILTERED_OUT` | 필터에서 탈락 | `failedFilter`(`DRAWDOWN`·`STABILITY`), `passedFilters` |
| `EXCLUDED_BEFORE_RUN` | 유효성 위반으로 계산 전 제외 | `code`, `message` |
| `NOT_EVALUATED` | 실패·미실행이라 비교 대상이 아님 | `code`, `message` |

선정 계산 전에는 `selectionReason`이 `null`이다. `FILTERED_OUT`은 탈락이고 `NOT_IN_DISPLAY`는
"떨어진 것이 아니라 표시하지 않은 것"이다 — 화면에서 두 문구를 구분한다.

---

## 5. 취소 — `POST /param-searches/{searchId}/cancel`

`202 Accepted`.

```json
{ "success": true, "data": { "searchId": "PS-20260914-000011", "status": "RUNNING", "skipped": 1, "inFlight": 4 }, "error": null }
```

발행하지 않은 후보를 `SKIPPED`('미실행')로 확정하고 더 발행하지 않는다. **이미 큐에 들어간 후보는
완주한다** — 발행된 메시지는 회수할 수 없다. 그래서 응답의 `status`는 아직 `RUNNING`일 수 있고,
`inFlight`가 몇 개가 끝까지 도는지 알려 준다. 그것이 다 끝나면 `PARTIAL`이 된다.

이미 끝난 묶음을 취소하면 `409 ALREADY_FINISHED`, 없는 묶음이면 `404`다.

## 5.1 실패 후보 재시도 — `POST /param-searches/{searchId}/retry-failed`

`202 Accepted`.

```json
{ "success": true, "data": { "searchId": "PS-20260914-000011", "status": "RUNNING", "retried": 1, "remainingFailed": 0 }, "error": null }
```

`FAILED` 후보만 다시 발행한다. **묶음 시작 시점의 참조 버전·탐색 공간·선정 규칙을 그대로 쓰고 새 묶음을
만들지 않는다**(FR-024). `SKIPPED`는 대상이 아니다 — 취소·한도로 빠진 것을 되살리면 완료 범위 표시가
뜻을 잃는다.

**발행 창을 넘겨 보내지 않는다.** 실패가 창보다 많으면 이번에 창만큼만 보내고 나머지는
`remainingFailed`로 알린다. 자동으로 이어 보내지 않으므로 화면은 그 수를 보고 다시 요청한다.

다시 열린 묶음은 `selection`이 `null`로 비워진다 — 후보가 더 붙으므로 이전 선정은 사실이 아니다.
재시도 후보가 끝날 때마다 잠정 선정을 갱신하고 다시 끝나면 확정한다. 실패 후보가 없으면
`409 NOTHING_TO_RETRY`다.

---

## 6. 오류 코드 (007 신규)

| HTTP | `error.code` | 언제 | 재시도 |
|---|---|---|---|
| 400 | `INVALID_INPUT` | 종목 수·형식, 페이징·정렬 파라미터 | 값을 고친 뒤 |
| 401 | `AUTHENTICATION_REQUIRED` | 요청자를 요구하는 경로를 로그인 없이 호출 | 로그인 후 |
| 400 | `UNREGISTERED_PARAMETER_VALUE` | `space`에 등록표 밖 값 | 등록표 안의 값으로 |
| 422 | `CANDIDATE_LIMIT_EXCEEDED` | 유효 후보 수가 상한 초과 | 범위를 좁힌 뒤 |
| 422 | `NO_VALID_COMBINATION` | 유효 조합 0건 | 섹터·범위를 바꾼 뒤 |
| 422 | `STRATEGY_NOT_REGISTERED` | 등록표 활성 전략에 없는 전략 | 등록 후 |
| 422 | `STRATEGY_NOT_EXECUTABLE` | 004가 실행을 지원하지 않는 전략 | 004 지원 후 |
| 409 | `REGISTRY_VERSION_CHANGED` | 미리보기와 접수 시점의 등록표 버전이 다름 | 미리보기를 다시 본 뒤 |
| 503 | `REGISTRY_UNAVAILABLE` | 등록 정보·지원 목록 조회 실패 | 잠시 후 |
| 409 | `ALREADY_FINISHED` | 끝난 묶음 취소 | 무의미 |
| 409 | `NOTHING_TO_RETRY` | 실패 후보가 없는 묶음에 재시도 요청 | 무의미 |
| 404 | `RESOURCE_NOT_FOUND` | 없는 `searchId` | 무의미 |

004에서 올라오는 오류도 접수 경로에서 그대로 보인다 — 예를 들어 규칙이 등록되지 않은 전략은
`422 RULES_NOT_REGISTERED`다.

---

## 7. 프론트가 알아야 할 것

- `searchId`·`candidateId`는 문자열이다. 길이를 가정하지 않는다.
- 같은 조건으로 다시 요청하면 **항상 새 묶음**이다. 이전 묶음의 결과와 상태 표시는 바뀌지 않는다
  (FR-013c). 기준을 바꿔 다시 실행하는 것은 새 실험이다.
- 폴링은 §3으로 한다. `selection`이 `null`이면 아직 선정 전이다.
- **표시 후보에 순위 번호를 붙이지 않는다.** 정렬 지표 이름을 함께 보이고, 후보마다 `validationLabel`을
  항상 함께 보인다(현재 값은 전부 `UNVALIDATED`).
- `limits.provisional`에 든 항목은 임시값 표시를 붙인다.
- **프론트에 전략 목록·파라미터 기본값·허용 범위·단위를 두지 않는다**(FR-025). 전략 이름·축 라벨·단위·
  기본값·후보값은 `spaceSummary`·`strategies`에서만 읽는다. 전략이 늘어도 화면 코드를 고치지 않는 것이
  이 계약의 목적이고, **`strategyId`로 분기하는 화면 코드는 리뷰에서 막는다.**
- 등록된 기본 시작값에 '추천'·'최적' 문구를 붙이지 않는다(원칙 III).
- `POST /param-searches`에는 미리보기에서 받은 `registryVersion`을 반드시 함께 보낸다.
- **성과가 `null`인 후보를 "0%"로 그리지 않는다.** 계산하지 않은 것과 0인 것은 다르다(FR-011).

## 8. 알려진 차이와 한계

구현이 원본 계약과 다르거나 아직 못 미치는 지점이다. 프론트가 이것을 모르면 없는 동작을 기대하게 된다.

- **미리보기의 전략 거부가 200이다.** 원본 계약 §6은 미등록·실행 미지원 전략을 422로 적었지만, 구현은
  미리보기에서 예외를 던지지 않는다 — 미등록 전략은 `strategies` 목록에서 빠지고 실행 미지원 전략은
  `executable: false`로 실린다. 접수(`POST /param-searches`)는 계약대로 422다. **어느 쪽으로 맞출지
  미정이다.**
- **`space`는 등록된 후보값만 받는다.** `allowedRange` 안의 임의 값은 400이다. 그래서 현재 등록표로는
  후보 수 상한을 넘기는 요청을 만들 수 없다.
- **현재 워커는 1종목만 계산한다.** `universe.instrumentIds`를 2개 이상 보내면 후보 Job은 접수되고
  실행 단계에서 실패한다. 004의 배분 전략이 붙기 전까지의 한계다.
- **실행 측정(FR-023)이 없다.** 004에 측정 저장소가 아직 없어 `AUTO_SEARCH_BATCH` 기록이 남지 않는다.
- **평가 분할이 미등록이라 `validationLabel`은 항상 `UNVALIDATED`다.** 이것은 "검증을 통과했다"가
  아니라 "검증 여부를 말할 수 없다"는 뜻이다. 분할이 등록되면 후보마다 라벨이 갈린다.
