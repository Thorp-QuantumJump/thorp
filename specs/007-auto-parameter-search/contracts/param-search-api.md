# 설정 조합 자동 비교 HTTP 계약 (Backend ↔ Frontend)

**Date**: 2026-09-10 | **Spec**: [../spec.md](../spec.md) | **Plan**: [../plan.md](../plan.md)

`docs/specs/backtest-api/README.md`의 규약을 그대로 상속한다. 다시 정하지 않는 것:

- 응답 껍데기 `{ "success": bool, "data": ..., "error": ... }`
- 오류 포맷 `error: { code, message }`와 공통 코드(`INVALID_INPUT` `RESOURCE_NOT_FOUND` `BROKER_UNAVAILABLE` `INTERNAL_ERROR`)
- **금액·비율은 요청·응답 모두 문자열**(부동소수 오차 방지)
- 식별자는 문자열이며 DB PK(숫자)를 노출하지 않는다. 길이를 가정하지 않는다

`searchId`는 `PS-{yyyyMMdd}-{6자리 이상 순번}` 형식의 문자열이다(`BT-` 규약과 같은 방식).

---

## 1. 실행 전 확인 — `POST /param-searches/preview`

저장하지 않고 계산만 한다. 확인 화면의 네 항목(대상 전략·탐색 범위 요약·예상 작업량·적용 한도)을
채우는 용도다(FR-003). **사용자에게 후보 숫자를 입력받지 않는다** — 요청은 이미 고른 전략과 섹터,
그리고 선택 사항인 범위 좁히기만 담는다(SC-001).

### 요청 필드

| 경로 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `universe.instrumentIds` | `string[]` | 필수 | 1~50개. `{6자리}.XKRX`. 중복 금지 |
| `strategy.strategyIds` | `string[]` | 필수 | 1개 이상. 각 64자 이하 |
| `run.start` / `run.end` | `date` | 선택 | 생략 시 공통 평가 구간을 서버가 제시한다 |
| `portfolio.initialCash` | `string` (decimal) | 선택 | 생략 시 서버 기본값 |
| `space` | `object` | 선택 | 축 이름 → 후보 값 배열. **생략하면 등록표 전체 범위**를 쓴다 |

`space`에 등록표에 없는 값을 넣으면 `400 INVALID_INPUT`이다(FR-002, US1 시나리오 3). 범위를 좁히거나
넓히는 것은 등록표 안에서만 가능하다.

### 200 응답

```json
{
  "success": true,
  "data": {
    "registryVersion": "PROVISIONAL-2026-09-10",
    "strategies": [{ "strategyId": "sector-momentum", "name": "섹터 모멘텀" }],
    "spaceSummary": [
      { "axis": "lookbackDays", "label": "관측 기간", "values": [20, 60, 120], "unit": "거래일" },
      { "axis": "topN", "label": "선정 섹터 수", "values": [2, 3, 4] }
    ],
    "workload": {
      "totalCombinations": 9,
      "validCombinations": 7,
      "excludedCombinations": 2,
      "exclusionReasons": [
        { "code": "TOP_N_EXCEEDS_UNIVERSE", "message": "선정 섹터 수가 고른 섹터 수보다 큽니다", "count": 2 }
      ]
    },
    "limits": {
      "maxCandidates": 1000,
      "provisional": ["maxCandidates", "timeLimitSeconds", "dispatchWindow"],
      "exceeded": false
    },
    "commonPeriod": { "start": "2015-01-02", "end": "2026-08-31", "shortenedBy": null },
    "validationLabel": "UNVALIDATED",
    "validationNote": "평가 분할이 등록되지 않아 결과는 모두 미검증으로 표시됩니다"
  },
  "error": null
}
```

- `limits.provisional`은 임시값인 항목 이름이다. 화면은 이 항목에 임시값 표시를 붙인다(FR-005a).
- `limits.exceeded`가 `true`면 실행은 거부된다(§2). 화면은 범위를 좁히라고 안내한다(FR-005b).
- `commonPeriod.shortenedBy`는 긴 관측 기간을 요구하는 후보 때문에 공통 구간이 줄었을 때 그 축
  이름을 담는다(Edge Case). 모든 후보는 같은 공통 구간으로 평가한다.
- `validationLabel`은 실행 전에도 알려준다 — 결과를 본 뒤에야 '미검증'을 알게 되면 오해가 생긴다.

## 2. 탐색 실행 — `POST /param-searches`

요청 필드는 §1과 같다. 응답은 `202 Accepted`. 백엔드는 계산하지 않고 묶음과 후보를 저장한 뒤
발행 창만큼만 작업 큐에 넣는다.

```json
{ "success": true, "data": { "searchId": "PS-20260910-000001", "status": "QUEUED" }, "error": null }
```

### 거부 (실행하지 않음)

| HTTP | `error.code` | 언제 |
|---|---|---|
| 422 | `CANDIDATE_LIMIT_EXCEEDED` | 유효 후보 수 > 후보 수 상한. `message`에 유효 수·상한·좁히는 방법 (FR-005b) |
| 422 | `NO_VALID_COMBINATION` | 등록된 조합이 모두 유효성 제약 위반. `message`에 어느 제약인지 한 문장 (Edge Case) |

둘 다 표본을 자동으로 뽑거나 간격을 넓혀 실행하지 않는다. **묶음은 `REJECTED` 상태로 저장되어
사유가 남는다**(FR-020) — 재현 기록이므로 조용히 버리지 않는다.

## 3. 묶음 상태·결과 — `GET /param-searches/{searchId}`

폴링 대상이다. 실패해도 200이며 `status`로 판단한다(`GET /backtests/{id}`와 같은 규약).

```json
{
  "success": true,
  "data": {
    "searchId": "PS-20260910-000001",
    "status": "PARTIAL",
    "stopReason": "CANCELLED",
    "counts": { "expected": 9, "valid": 7, "completed": 5, "failed": 1, "excluded": 2, "pending": 1 },
    "partial": true,
    "partialNote": "취소되어 7개 중 6개까지 계산했습니다. 1개는 실행되지 않았습니다",
    "config": {
      "strategyIds": ["sector-momentum"],
      "instrumentIds": ["069500.XKRX", "091170.XKRX"],
      "registryVersion": "PROVISIONAL-2026-09-10",
      "searchMethod": "FULL_GRID",
      "randomSeed": null,
      "space": { "lookbackDays": [20, 60, 120], "topN": [2, 3, 4] },
      "selectionRule": {
        "drawdownFilter": "NOT_WORSE_THAN_BASELINE",
        "stabilityFilter": "BATCH_PERCENTILE_TOP_50",
        "sortMetric": "NET_RETURN",
        "displayCount": 5,
        "tieHandling": "SHOW_ALL_TIED"
      },
      "limits": { "maxCandidates": 1000, "provisional": ["maxCandidates"] },
      "referenceVersions": { "marketData": "2026-08-31", "cost": "v1", "engine": "nautilus-0.1" },
      "dataVersionChanged": false
    },
    "baseline": {
      "kind": "EQUAL_WEIGHT",
      "backtestId": "BT-20260910-000042",
      "status": "COMPLETED",
      "netReturn": "0.1421",
      "maxDrawdown": "-0.2310"
    },
    "selection": {
      "displayed": ["PS-20260910-000001-C003", "PS-20260910-000001-C007"],
      "shortOfDisplayCount": true,
      "note": "기준을 충족한 후보가 2개입니다",
      "drawdownFilterApplied": true
    },
    "startedAt": "2026-09-10T02:11:04Z",
    "finishedAt": "2026-09-10T02:19:51Z"
  },
  "error": null
}
```

**`selection.displayed`는 순위가 아니다.** 정렬 지표(`sortMetric`)를 밝힌 집합이며 화면에 순위 번호를
붙이지 않는다(plan "설계 제약"). `drawdownFilterApplied`가 `false`면 기준 백테스트가 실패해 하락 폭
필터를 적용하지 못한 경우다(Edge Case) — 화면에 그 사실을 표시한다.

`selection`은 `status`가 `COMPLETED`·`PARTIAL`일 때만 채워진다. 그 전에는 `null`이다.

## 4. 후보 목록 — `GET /param-searches/{searchId}/candidates`

전체 후보를 본다(FR-018 상세). 표시 후보만 보려면 `selected=true`.

| 파라미터 | 기본값 | 규칙 |
|---|---|---|
| `page` / `size` | `0` / `50` | `size` 최대 200 |
| `status` | 없음(전체) | `COMPLETED` `FAILED` `EXCLUDED` `SKIPPED` `PENDING` `DISPATCHED` |
| `selected` | 없음(전체) | `true` / `false` |
| `sort` | `candidateNo,asc` | `netReturn` `maxDrawdown` `tradingCost` `stability` 허용 |

```json
{
  "success": true,
  "data": {
    "page": 0, "size": 50, "totalElements": 9, "totalPages": 1,
    "content": [
      {
        "candidateId": "PS-20260910-000001-C003",
        "candidateNo": 3,
        "strategyId": "sector-momentum",
        "parameters": { "lookbackDays": 60, "topN": 3 },
        "status": "COMPLETED",
        "backtestId": "BT-20260910-000045",
        "netReturn": "0.1873",
        "maxDrawdown": "-0.1902",
        "tradingCost": "0.0121",
        "stability": { "calculable": true, "value": "0.0204", "neighborCount": 4 },
        "retryCount": 0,
        "validationLabel": "UNVALIDATED",
        "selected": true,
        "selectionReason": { "outcome": "SELECTED", "tied": false, "passedFilters": ["DRAWDOWN", "STABILITY"] }
      },
      {
        "candidateId": "PS-20260910-000001-C005",
        "candidateNo": 5,
        "parameters": { "lookbackDays": 120, "topN": 4 },
        "status": "EXCLUDED",
        "backtestId": null,
        "netReturn": null,
        "maxDrawdown": null,
        "tradingCost": null,
        "stability": null,
        "validationLabel": "UNVALIDATED",
        "selected": false,
        "selectionReason": {
          "outcome": "EXCLUDED_BEFORE_RUN",
          "code": "TOP_N_EXCEEDS_UNIVERSE",
          "message": "선정 섹터 수 4가 고른 섹터 수 2보다 큽니다"
        }
      }
    ]
  },
  "error": null
}
```

**성과 필드가 `null`인 것과 0인 것은 다르다.** `EXCLUDED` `FAILED` `SKIPPED` `PENDING` 후보의
`netReturn`·`maxDrawdown`·`tradingCost`는 항상 `null`이고 0으로 채우지 않는다(FR-011, 원칙 VI).
`stability.calculable`이 `false`일 때도 `value`는 `null`이다(FR-013b). 화면은 이 값들을 "0%"가 아니라
사유와 함께 표시한다.

## 5. 취소 — `POST /param-searches/{searchId}/cancel`

`202 Accepted`. 발행하지 않은 후보를 `SKIPPED`로 바꾸고 더 발행하지 않는다. **이미 큐에 들어간 후보는
완주한다** — prefetch 1 + manual ACK 구조에서 발행된 메시지는 회수할 수 없다(research R2).

```json
{ "success": true, "data": { "searchId": "PS-20260910-000001", "status": "RUNNING", "skipped": 4, "inFlight": 2 }, "error": null }
```

응답 시점의 `status`는 아직 `RUNNING`일 수 있다. 실행 중 후보가 모두 끝나면 `PARTIAL`이 된다.
이미 끝난 묶음을 취소하면 `409 ALREADY_FINISHED`다.

## 5.1 실패 후보 재시도 — `POST /param-searches/{searchId}/retry-failed`

`202 Accepted`. `FAILED` 후보만 다시 발행한다. **묶음 시작 시점의 참조 버전·탐색 공간·선정 규칙을
그대로 쓰고 새 묶음을 만들지 않는다**(FR-024). `SKIPPED`('미실행') 후보는 대상이 아니다 — 취소·한도로
빠진 것을 재시도로 되살리면 그 묶음의 완료 범위 표시가 뜻을 잃는다.

```json
{ "success": true, "data": { "searchId": "PS-20260910-000001", "status": "RUNNING", "retried": 1, "remainingFailed": 0 }, "error": null }
```

재시도가 끝나 묶음이 다시 종료되면 **선정을 다시 계산한다.** 선정 규칙은 스냅샷을 그대로 쓰므로
기준이 바뀌지 않는다(FR-013c). 실패 후보가 없으면 `409 NOTHING_TO_RETRY`다.

같은 사유로 또 실패하면 `retryCount`와 마지막 사유만 남고 자동으로 반복하지 않는다. 반복 재시도는
사용자가 다시 요청할 때만 일어난다.

## 6. 오류 코드 (007 신규)

| HTTP | `error.code` | 언제 | 재시도 |
|---|---|---|---|
| 422 | `CANDIDATE_LIMIT_EXCEEDED` | 유효 후보 수가 상한 초과 | 범위를 좁힌 뒤 |
| 422 | `NO_VALID_COMBINATION` | 유효 조합 0건 | 섹터·범위를 바꾼 뒤 |
| 400 | `UNREGISTERED_PARAMETER_VALUE` | `space`에 등록표 밖 값 | 등록표 안의 값으로 |
| 409 | `ALREADY_FINISHED` | 끝난 묶음 취소 | 무의미 |
| 409 | `NOTHING_TO_RETRY` | 실패 후보가 없는 묶음에 재시도 요청 | 무의미 |
| 404 | `RESOURCE_NOT_FOUND` | 없는 `searchId` | 무의미 |

## 7. 프론트가 알아야 할 것

- `searchId`·`candidateId`는 문자열이다. 길이를 가정하지 않는다.
- 같은 조건으로 다시 요청하면 **항상 새 묶음**이다(FR-013c: 기준을 바꿔 다시 실행하면 새 실험).
  이전 묶음의 결과와 상태 표시는 바뀌지 않는다.
- 폴링은 §3으로 한다. `selection`이 `null`이면 아직 선정 전이다.
- **표시 후보에 순위 번호를 붙이지 않는다.** 정렬 지표 이름을 함께 보이고, 후보마다
  `validationLabel`을 항상 함께 보인다(현재 값은 전부 `UNVALIDATED`).
- `limits.provisional`에 든 항목은 임시값 표시를 붙인다.
- **현재 워커는 1종목만 계산한다.** `universe.instrumentIds`를 2개 이상 보내면 후보 Job은 접수되고
  실행 단계에서 `FAILED`가 된다(`backtest-api` §4와 같은 한계). 004의 배분 전략이 붙기 전까지 007의
  종단 확인은 1종목·momentum으로만 가능하다.
