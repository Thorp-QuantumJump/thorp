# 계약: 내 기록 API

**Spec**: [../spec.md](../spec.md) | **Plan**: [../plan.md](../plan.md) | **Research**: [../research.md](../research.md)

006이 소유하는 서버 계약이다. 결과 조회 계약은 [005가 소유](../../005-result-comparison/contracts/result-api.md)하고,
탐색 묶음·경제환경 스냅샷 조회 계약은 007·010이 소유한다. 006은 그 계약을 다시 정의하지 않고
**목록·이름·삭제·내보내기**만 정한다.

## 공통

- 응답 봉투는 저장소 공통 형식이다: 성공 `{ success: true, data, error: null }`,
  실패 `{ success: false, data: null, error: { code, message } }`.
- 모든 경로는 **로그인 필요**다. `SecurityConfig`에 `/my-records/**` → `authenticated()`를 더한다.
  비로그인 요청은 `401 AUTHENTICATION_REQUIRED`.
- 소유자는 `CurrentRequester.requireRequesterId()`로 구한다(R5). **남의 기록은 존재를 알리지 않는다** —
  다른 사용자의 `recordId`는 `403`이 아니라 `404 RESOURCE_NOT_FOUND`다. `403`은 그 기록이 있다는
  사실을 알려 준다.
- 상태를 바꾸는 요청(`PATCH`·`DELETE`)은 CSRF 토큰을 받는다(014가 정한 `X-XSRF-TOKEN`).
- JSON 필드는 camelCase(루트 CLAUDE.md).
- DB PK는 노출하지 않는다. 식별자는 `recordId`(`MR-YYYYMMDD-NNNNNN`)다.

## GET /my-records

목록. 기본 보기는 **완료 항목**이고 저장일 최신순이다(FR-004).

### 질의

| 이름 | 값 | 기본 | 뜻 |
|---|---|---|---|
| `cursor` | 문자열 | 없음 | 이전 응답의 `nextCursor`. 없으면 첫 쪽 |
| `size` | 1~50 | 20 | 쪽 크기 |
| `sort` | `SAVED_AT_DESC` `SAVED_AT_ASC` `NAME_ASC` | `SAVED_AT_DESC` | **성과 정렬은 없다**(R7, 원칙 III) |
| `kind` | `ANALYSIS` `SEARCH_BUNDLE` `REGIME_SEARCH` | 전부 | 여러 번 줄 수 있다 |
| `status` | `COMPLETED` `RUNNING` `FAILED` `CANCELLED` `PARTIAL` | `COMPLETED` | 여러 번 줄 수 있다. 명시하면 기본 보기를 대체한다 |
| `sector` | 섹터 코드 | 전부 | 여러 번 줄 수 있다. 하나라도 겹치면 포함 |
| `strategy` | 전략 코드 | 전부 | 같음 |
| `periodFrom` `periodTo` | `YYYY-MM-DD` | 없음 | 분석 기간이 이 구간과 겹치는 항목 |

허용하지 않는 `sort`·`kind`·`status` 값은 `400 INVALID_INPUT`이다. 조용히 기본값으로 바꾸지 않는다
(원칙 VI).

### 응답

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "recordId": "MR-20260918-000042",
        "kind": "ANALYSIS",
        "name": "반도체 외 2 섹터 모멘텀 2026-09-18",
        "sectorCodes": ["SEMICONDUCTOR", "BATTERY", "SHIPBUILDING"],
        "strategyIds": ["SECTOR_MOMENTUM"],
        "period": { "start": "2019-01-02", "end": "2026-08-29" },
        "savedAt": "2026-09-18T01:12:03Z",
        "status": "COMPLETED",
        "statusReason": null,
        "resultToken": "3f1c…",
        "isCopy": false
      },
      {
        "recordId": "MR-20260917-000031",
        "kind": "SEARCH_BUNDLE",
        "name": "은행 역변동성 탐색 2026-09-17",
        "sectorCodes": ["BANK"],
        "strategyIds": ["INVERSE_VOLATILITY"],
        "period": { "start": "2019-01-02", "end": "2026-08-29" },
        "savedAt": "2026-09-17T08:40:00Z",
        "status": "PARTIAL",
        "statusReason": "TIME_LIMIT",
        "searchId": "PS-20260917-000009",
        "isCopy": false
      },
      {
        "recordId": "MR-20260916-000007",
        "kind": "REGIME_SEARCH",
        "name": "고용환경 2026-08 2026-09-16",
        "sectorCodes": [],
        "strategyIds": [],
        "period": null,
        "savedAt": "2026-09-16T22:05:11Z",
        "status": "THRESHOLD_APPLIED",
        "statusReason": null,
        "regime": { "searchId": "FIELD:EMPLOYMENT:2026-08:3", "mode": "FIELD", "fieldId": "EMPLOYMENT", "baselineMonth": "2026-08-01", "baselineOrigin": "T_G", "entryPath": "MARKET_FLOW", "withdrawn": false },
        "isCopy": false
      }
    ],
    "nextCursor": "eyJzYXZlZEF0…"
  },
  "error": null
}
```

| 필드 | 규칙 |
|---|---|
| `status` | 종류별 원본에서 조인해 읽은 값이다(data-model §1). `my_record`에 저장된 값이 아니다 |
| `statusReason` | 실패·취소·부분 완료·계산 불가의 사유 코드. **낮은 수익과 섞지 않는다**(FR-014) |
| `resultToken` | `ANALYSIS`에만 있다. '다시 보기' 링크(`/results/{token}`)의 열쇠(R3) |
| `searchId` | `SEARCH_BUNDLE`에만 있다. 007 상세 경로의 열쇠 |
| `regime` | `REGIME_SEARCH`에만 있다. `regime_search_log` 행에서 읽는다. 같은 스냅샷이라도 `entryPath`가 다르면 별도 항목이다. `withdrawn: true`는 010 FR-035 철회로 스냅샷 참조가 끊긴 것이며 '다시 보기'가 열리지 않는다 |
| `nextCursor` | 다음 쪽이 없으면 `null`. **전체 개수는 돌려주지 않는다**(R14) |

수익률·하락 폭·비용은 목록 응답에 **없다.** 목록에서 성과를 나란히 보이면 정렬을 제공하지 않아도
순위 화면이 된다(원칙 III).

## GET /my-records/{recordId}

한 항목의 상세. 목록 항목에 더해 **펼쳐 보는 층**의 값을 담는다(원칙 II 정보 세 층).

```json
{
  "success": true,
  "data": {
    "recordId": "MR-20260918-000042",
    "kind": "ANALYSIS",
    "name": "반도체 외 2 섹터 모멘텀 2026-09-18",
    "savedAt": "2026-09-18T01:12:03Z",
    "lastViewedAt": "2026-09-18T03:20:00Z",
    "status": "COMPLETED",
    "resultToken": "3f1c…",
    "referenceVersions": {
      "datasetVersion": "DS-2026-09-17-001",
      "sectorEtfMapVersion": "SEM-2026-09-16-001",
      "registryVersion": "PROVISIONAL-003",
      "costProfileVersion": "CP-2026-09-17-001",
      "engineVersion": "worker-0.9.3"
    },
    "mappingDrift": {
      "changed": true,
      "kind": "INSTRUMENT_REPLACED",
      "sectorCodes": ["BATTERY"],
      "message": "배터리 섹터의 대표 ETF가 저장 당시와 다릅니다."
    },
    "registryDrift": { "outOfRange": false, "fields": [] },
    "reopenSettings": { "available": true }
  },
  "error": null
}
```

| 필드 | 규칙 |
|---|---|
| `referenceVersions` | 저장 당시 참조 버전. 사용자에게 입력을 요구하지 않고 **상세에서만 보인다**(FR-005) |
| `mappingDrift` | UI-01 판정 결과(R8). **버전 문자열이 달라도 대응 내용이 같으면 `changed: false`다.** `kind`는 `INSTRUMENT_REPLACED` 또는 `SECTOR_REMOVED` |
| `registryDrift` | 저장된 설정값이 현재 등록표 허용 범위 밖인지(FR-008). `fields`에 항목 이름과 저장값·현재 범위 |
| `reopenSettings.available` | '설정 바꿔 비교'를 열 수 있는지. 경제환경 기록은 `false` |

`mappingDrift`·`registryDrift`는 **서버가 판정한다.** 화면이 버전 문자열을 비교하지 않는다
(원칙 II: 복잡한 계산은 시스템이 맡는다).

### `referenceVersions`의 출처

004가 남긴 `backtest_run.versions`(JSONB)와 요청 사본 `request_spec`에서 읽는다. 006은 이름만 바꿔
담고 값을 만들지 않는다.

| 006 응답 필드 | 출처 | 규칙 |
|---|---|---|
| `datasetVersion` | `versions.data` | |
| `sectorEtfMapVersion` | `versions.mapping` | 접수 경로를 거치지 않은 실행에는 없다. 없으면 키를 만들지 않는다 |
| `costProfileVersion` | `versions.costProfile.version` | `{version, provisional}` 꼴이라 **잠정 여부도 함께 보인다** |
| `rulesVersion` | `versions.rules.version` | 같음 |
| `engineVersion` | `versions.engine` | `"nautilus_trader … / worker … / uv.lock …"` **한 문자열**이다(`thorp.backtest.engine-version`). 006이 쪼개지 않는다 |
| `registryVersion` | `request_spec.registryVersion` | **`versions`에 없다.** 접수가 선택 항목으로 받아 사본에만 남긴다. 없으면 키를 만들지 않고 화면은 '기록되지 않음'을 보인다(원칙 VI) |

`versions.worker`·`versions.dependencies`(워커 자기 보고와 numpy·scipy 버전)는 **담지 않는다.**
운영 정보라 공개하지 않는다는 004·005의 판단(`PublicResultService.withoutWorkerReport`)을 006도
따른다 — 내보내기 파일에도 자리를 두지 않는다.

### 저장 당시 실행 조건 (FR-007a)

상세는 `reopenPayload`로 저장된 요청 사본(`backtest_run.request_spec`)을 그대로 돌려준다. 여기에
`warmupTradingDays`(접수가 확정한 **실효값**)와 `start`·`end`가 들어 있다. 006은 이 값을 다시 산정하지
않는다 — 새 분석의 워밍업 재산정은 003이, 비교할 때 긴 쪽 공통 워밍업 판정은 005가 한다(FR-007a,
[record-handoff.md](record-handoff.md)).

## POST /my-records/regime-searches

경제환경 검색 결과를 개인 기록으로 남긴다(010 FR-032, R15). 로그인 사용자가 검색 결과 화면을 열 때
화면이 부른다. 010의 `GET /regime-searches`는 바뀌지 않는다.

```json
{ "searchId": "FIELD:EMPLOYMENT:2026-08:3", "entryPath": "MARKET_FLOW" }
```

| 필드 | 규칙 |
|---|---|
| `searchId` | 010이 응답한 스냅샷 식별 키. 없는 키는 `404 RESOURCE_NOT_FOUND` |
| `entryPath` | `MARKET_FLOW` 또는 `STRATEGY_RESULT`. 그 밖의 값은 `400 INVALID_INPUT` |

| 동작 | 값 |
|---|---|
| 같은 소유자·같은 `searchId`·같은 `entryPath`의 살아 있는 항목이 있으면 | 새로 만들지 않고 그 항목을 돌려준다(`200`). `regime_search_log.viewed_at`만 갱신 |
| 없으면 | `regime_search_log` 행과 `my_record` 행을 한 트랜잭션에 만들고 `201` |
| 응답 | 목록 항목과 같은 본문 |

`baselineOrigin`(T_G/T_ALL/USER_SPECIFIED)은 서버가 `searchId`와 010 응답 규칙으로 정하며 화면이 보내지
않는다.

## PATCH /my-records/{recordId}

이름 변경. 이름만 바뀌고 저장된 결과·참조 버전은 변하지 않는다(FR-017, SC-001 검증 대상).

```json
{ "name": "반도체 비중 높인 안" }
```

| 규칙 | 값 |
|---|---|
| 길이 | 1~120자. 앞뒤 공백 제거 후 판정 |
| 빈 이름 | `400 INVALID_INPUT`. 자동 이름으로 되돌리지 않는다 |
| 중복 | **허용한다**(R12) |
| 다른 필드 | 이 요청으로 바꿀 수 없다. `name` 외의 키는 무시하지 않고 `400 INVALID_INPUT` |

응답은 갱신된 상세(`GET /my-records/{recordId}`와 같은 본문)다.

## DELETE /my-records/{recordId}

개인 항목 삭제. **공용 계산 결과는 지우지 않는다**(2026-09-18 명료화, R6).

| 규칙 | 값 |
|---|---|
| 확인 | 확인은 화면이 받는다. 서버는 이 요청을 받으면 바로 수행한다(FR-017) |
| 결과 | `deleted_at`을 채운다. 결과·거래 내역·재현 기록·`backtest_run`은 그대로 |
| 응답 | `200`, `data: { "recordId": "…", "deletedAt": "…" }` |
| 이미 삭제됨 | `404 RESOURCE_NOT_FOUND` |
| 복구 | **없다.** 되돌리는 경로를 만들지 않는다 |

삭제 뒤 그 항목은 같은 사용자의 목록·비교 대상·경제환경 연결 대상에서 사라진다. 같은 공용 결과를
가리키는 **다른 사용자의 항목은 그대로 열린다** — 이것이 SC-007의 판정 대상이다.

## GET /my-records/{recordId}/export

내보내기(FR-015·FR-016, R10).

| 질의 | 값 | 기본 |
|---|---|---|
| `format` | `json` `csv` | 없음(필수). 빠지면 `400 INVALID_INPUT` |

| 응답 | 값 |
|---|---|
| `Content-Type` | `application/json` 또는 `text/csv; charset=UTF-8` |
| `Content-Disposition` | `attachment; filename*=UTF-8''{이름}.{확장자}` |
| 인코딩 | CSV는 **UTF-8 BOM** + CRLF |

이 응답은 공통 봉투(`ApiResponse`)를 쓰지 않는다 — 파일 본문이다. 실패는 봉투를 쓴다.

### 담는 것

| 구획 | 담기는 값 | 근거 |
|---|---|---|
| `record` | `recordId`·`name`·`kind`·`savedAt`·`status` | FR-015 |
| `config` | 저장된 설정값 원문(섹터·비중·전략·파라미터·분석 조건·비용) | FR-015 |
| `referenceVersions` | 자료·대응표·등록표·비용·엔진 버전 | FR-010 |
| `summary` | 핵심 결과 | FR-015 |
| `timeseries` | 계산된 평가 시계열 | FR-015 |
| `trades` | 가상 거래 내역(진입·청산 묶음, `backtest_trade`) | FR-015 |
| `fills` | 개별 체결(`backtest_fill`). **`backtest_result.fills_recorded`가 `true`인 결과에만 담고 `false`면 키를 만들지 않는다** — 기록하지 않은 것이 체결 0건으로 읽히면 안 된다(원칙 VI, `V20260920080000__backtest_fill.sql` 주석) | FR-015 |
| `exceptions` | 예외 처리 기록 | FR-015 |
| `searchSpace` `candidates` | 탐색 공간·후보별 결과·상태·제외 사유·선정 규칙 (`SEARCH_BUNDLE`만) | FR-015, Story 4 시나리오 3 |
| `regime` | 모드·분야·기준월·전체/표시 후보·제외 이유·지표 원값과 표준화 값·분야별 거리·자료 개정 기준·모델 버전 (`REGIME_SEARCH`만) | FR-012 |
| `regime.link` | 연결한 섹터·ETF·전략·사후 평가 구간·가용성 필터. 011의 `regime_link_search`·`regime_link_filter`에서 읽는다. **그 표가 아직 없으면 키를 만들지 않는다**(원칙 VI) | FR-012 |
| `exclusions` | **빠진 항목과 그 이유.** 빠진 것이 없으면 빈 배열이지만 키는 반드시 있다 | FR-016 |

### 담지 않는 것

**스키마에 필드를 두지 않는다.** 값을 비우는 것이 아니라 자리가 없다(012
`NewsArticleExportResponse`와 같은 방식).

| 빠지는 것 | 이유 |
|---|---|
| 원천 일별 시세의 원값(체결가·조정가 일별 계열) | 재배포 금지(원칙 X, §6.2) |
| 뉴스 기사 본문 | 같음(§6.3, PD-15) |

`exclusions` 항목의 형태:

```json
{ "item": "dailyPriceRaw", "reason": "SOURCE_REDISTRIBUTION_FORBIDDEN",
  "message": "원천 시세의 일별 원값은 이용 조건상 내보낼 수 없습니다." }
```

이용 범위가 다른 자료가 섞여 있으면 **허용된 부분만 담고 제외 항목을 명시한다**(Edge Cases). 파일을
아예 만들지 않는 처리는 하지 않는다.

## 오류

| 상황 | status · code |
|---|---|
| 비로그인 | `401 AUTHENTICATION_REQUIRED` |
| 남의 기록 · 없는 기록 · 이미 삭제 | `404 RESOURCE_NOT_FOUND` |
| 질의·본문 값 위반 | `400 INVALID_INPUT` |
| 진행 중(결과가 아직 없는) 항목의 내보내기 | `409 RESULT_NOT_READY`. 실패·취소 항목은 거절하지 않고 `config`·`referenceVersions`·`exclusions`와 실패 사유만 담아 내보낸다(spec FR-015) |
| CSRF 토큰 없음·불일치 | `403 CSRF_TOKEN_INVALID` (014가 정한 코드) |

## 007 접수 경로에 요청하는 변경

006이 소유하는 규칙이지만 구현 자리가 007의 접수 경로에 있다. `param_search.owner_user_id`는 접수가
**이미 채우고 있다**(`ParamSearchService` → `command.requesterId()`) — 요청할 것은 아래 둘이다.

| 변경 | 이유 |
|---|---|
| 접수 **전에** 소유자의 살아 있는 탐색 묶음 항목 수를 `MyRecordLimitPolicy`로 세어 상한을 판정한다 | FR-018·Edge Cases. 걸리면 `429`가 아니라 **`409 RECORD_LIMIT_REACHED`**로 거절하고 본문에 `{ current, limit }`을 담는다. 한도(429)는 실행 자원 이야기이고 이것은 저장 자리 이야기라 코드를 나눈다(spec FR-018). 기존 항목은 지우지 않는다 |
| 접수 성공 시 애플리케이션 이벤트를 발행한다 | `MyRecordRegistrar`가 듣고 `SEARCH_BUNDLE` 항목을 만든다(R16) |

## 004 접수 경로에 요청하는 변경

| 변경 | 이유 |
|---|---|
| `BacktestService`가 요청을 저장하는 두 자리(새 실행·합류/재사용) 뒤에 요청 접수 이벤트를 발행한다 | `MyRecordRegistrar`가 듣고 `executionType`이 단건일 때만 `ANALYSIS` 항목을 만든다(R16). 직접 호출은 순환 참조가 된다 |
