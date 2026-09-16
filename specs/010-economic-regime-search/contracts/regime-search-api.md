# 계약: 경제환경 검색 HTTP API

**Spec**: [../spec.md](../spec.md) | **Data model**: [../data-model.md](../data-model.md)

> **2026-09-16 갱신**: spec Clarifications(Session 2026-09-16) 반영. `sector` → `field` 개명,
> `baselineOrigin`을 조회 시 판단, 비교 시점·빠진 지표/월 노출, 역사 비교 표시(SC-009),
> 계산 불가 응답에도 `dataAsOf`·`versions` 포함, 특정 항목 조회(`searchId`) 추가,
> 전체 후보 목록 엔드포인트 추가.

## 0. 공통

- **동기 응답이다.** 접수·상태 조회가 없다. 요청은 사전 계산된 결과 조회이고 요청 처리 중에
  척도·거리를 계산하지 않는다(spec FR-030, SC-002a).
- 인증 불필요. 비로그인으로 전부 볼 수 있다(spec FR-032).
- JSON 필드는 camelCase. **decimal은 문자열**이다(004 결과 조회와 같은 규약) — 숫자로 내보내면
  클라이언트 JSON 파서가 자릿수를 깎는다.
- 응답 포맷은 `market` 도메인과 같은 `HttpApiResponse`를 쓴다(`{httpStatus, isSuccess, message, code, result}`).
  아래 예시는 `result`의 내용만 적는다.
- **분야를 `field`라고 부른다.** `sector`를 쓰지 않는다 — 이 프로젝트에서 '섹터'는 ETF 업종·테마
  분석 단위이고([002](../../002-sector-weight-selection/spec.md)), 경제 분야와 다른 개념이다.
  두 이름이 겹치면 [011](../../011-regime-strategy-link/spec.md) 화면에서 한 번에 드러난다.

## 1. `GET /regime-searches`

검색 하나를 읽는다.

### 요청

| 파라미터 | 필수 | 값 | 설명 |
|---|---|---|---|
| `mode` | ✅ | `field` \| `composite` | 분야 검색 / Thorp 종합 비교 |
| `field` | `mode=field`일 때 ✅ | 분야 id | 008 등록표의 분야 |
| `baselineMonth` | — | `YYYY-MM` | 생략하면 `mode`에 따라 T_g 또는 T_all |
| `limit` | — | `3` \| `10` (기본 `3`) | 처음 3개, '더 보기'면 10 |

**거리 함수·척도·지표 조합을 고르는 파라미터는 두지 않는다**(spec US1 시나리오 5). 넣으면 사용자가
모델을 고르는 화면이 생긴다.

`baselineOrigin`은 파라미터가 아니라 **서버가 이 요청을 보고 정한다** — `baselineMonth`가 있으면
`USER_SPECIFIED`, 없으면 `mode`에 따라 `T_G` / `T_ALL`이다. 결과의 성질이 아니라 요청의 성질이라
저장하지 않는다(spec FR-029, data-model §2).

### 응답 200 — 정상

```json
{
  "searchId": "FIELD:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V2",
  "mode": "FIELD",
  "field": {"id": "LABOR", "name": "노동"},
  "baselineMonth": "2026-07",
  "baselineOrigin": "T_G",
  "observationMonths": ["2026-05", "2026-06", "2026-07"],
  "comparisonMonths": [
    {"indicator": "실업률", "months": ["2026-05","2026-06","2026-07"], "omitted": []},
    {"indicator": "취업자 수 증가율", "months": ["2026-05","2026-07"], "omitted": ["2026-06"],
     "note": "2026년 6월은 취업자 수 자료가 없어 그 지표만 5월·7월로 비교했습니다."}
  ],
  "status": "NEAREST_ONLY",
  "statusReason": "THRESHOLD_NOT_CALIBRATED",
  "statusNote": "가까운 순서만 제공합니다. 유사성은 아직 판정하지 않습니다.",
  "caseLabel": "최근접 참고 사례",
  "basis": {
    "kind": "HISTORICAL_REVISION",
    "note": "최신 개정자료를 이용한 역사 비교입니다. 당시 발표값으로 재현한 분석이 아닙니다."
  },
  "dataAsOf": {
    "indicators": [
      {"indicator": "실업률", "targetMonth": "2026-07", "publishedAt": "2026-09-12"},
      {"indicator": "취업자 수 증가율", "targetMonth": "2026-07", "publishedAt": "2026-09-10"}
    ],
    "note": "노동 지표는 2026년 7월분까지 반영되어 있습니다.",
    "revisions": [],
    "newerReleases": []
  },
  "versions": {
    "data": "MACRO-20260915", "registry": "REG-3", "model": "REGIME-V2",
    "revision": 1, "sampleData": true
  },
  "counts": {"evaluated": 412, "eligible": 398, "stored": 10, "displayed": 3},
  "cases": [
    {
      "endMonth": "2012-03",
      "rank": 1,
      "observationMonths": ["2012-01", "2012-02", "2012-03"],
      "distance": "0.412938571034",
      "similar": ["실업률은 3.1%로 당시 3.2%와 비슷합니다."],
      "different": ["취업자 수 증가율은 지금이 1.2%로 당시 2.4%보다 낮습니다."],
      "detailAvailable": true
    }
  ]
}
```

- `comparisonMonths`는 **지표마다** 따로 온다(spec FR-001). `omitted`가 비어 있지 않은 지표는
  화면이 `note`를 **숨길 수 없는 자리에** 표시한다(spec FR-031, SC-007). 남은 시점으로 계산했다는
  사실이 보이지 않으면 세 달로 비교한 결과와 구분되지 않는다.
- `basis.kind`는 **항상 `HISTORICAL_REVISION`** 이다. 당시 발표·수정값(빈티지)을 확보하기 전에는
  다른 값이 나오지 않는다(spec FR-027, SC-009, 원칙 V). 화면은 이 표시를 반드시 노출한다.
- `caseLabel`은 문턱 교정 전 고정 문구다(spec FR-020). `THRESHOLD_APPLIED`가 되면 사라진다.
- `dataAsOf.revisions`는 값이 개정된 경우에만 채운다(spec FR-031) —
  `[{"indicator": "실업률", "month": "2026-06", "revisedAt": "2026-09-10"}]`.
- `dataAsOf.newerReleases`는 **기준월보다 최신인 개별 발표**다(spec FR-016, US3 시나리오 3).
  종합 검색에서 T_all이 어느 분야 T_g보다 이를 때 채워지며, 별도 정보로 표시하고 질의값에 섞지
  않는다.
- `versions.revision`은 그 (모드·분야·기준월)에서 결과가 달라진 순번이다(data-model §2).
- `versions.sampleData`가 `true`면 008 미구현 상태의 예시 자료다(008 FR-022). 화면은 이 표시를
  반드시 노출한다.
- `distance`는 문자열이고 소수점 아래 12자리다(research R4).
- `counts`: `evaluated` 검토한 후보 수, `eligible` 비교 시점 입력이 완전하고 **자기 자를 만들 수 있는** 후보 수,
  `stored` 저장된 표시 후보 수(≤10), `displayed` 이번 응답에 담긴 수. **`stored`가 `displayed`보다
  크면 '더 보기'가 남아 있다** — 화면은 이 둘로 버튼 노출을 정하고 10을 가정하지 않는다.
  **`eligible`이 `stored`보다 크면 표시 상한에 걸린 것이다** — 화면은 *"가까운 순서로 10개까지
  보입니다"* 만 보이고 **`evaluated`·`eligible` 값 자체를 내보이지 않는다**(spec FR-013, SC-004).
  위 예시의 412는 기준월 2026-07처럼 과거가 긴 경우이며, 오래된 기준월은 수십 개로 줄어든다.
- **유지율(retention)을 내려보내지 않는다**(spec FR-031).

### 응답 200 — 계산 불가

빈 결과는 오류가 아니다(spec US2 시나리오 4). **200으로 내려보낸다.**

```json
{
  "searchId": "FIELD:CONSTRUCTION:2026-07-01:MACRO-20260915:REG-3:REGIME-V2",
  "mode": "FIELD",
  "field": {"id": "CONSTRUCTION", "name": "건설"},
  "baselineMonth": "2026-07",
  "baselineOrigin": "T_G",
  "status": "NOT_COMPUTABLE",
  "statusReason": "SCALE_ZERO",
  "statusNote": "이 분야는 지금 계산할 수 없습니다.",
  "basis": {"kind": "HISTORICAL_REVISION", "note": "…"},
  "dataAsOf": { "...": "정상 응답과 같은 모양" },
  "versions": { "...": "정상 응답과 같은 모양" },
  "counts": {"evaluated": 0, "eligible": 0, "stored": 0, "displayed": 0},
  "cases": []
}
```

**계산 불가 응답에도 `dataAsOf`·`versions`·`basis`가 있다.** SC-007이 *"검색 결과 화면의 100%"* 에
대상월과 발표일을 요구하고 SC-009가 역사 비교 표시를 요구하므로, 여기서 빠지면 계산 불가 화면만
100%에서 빠진다. 예시 자료 표시(008 FR-022)도 같이 사라진다.

| `statusReason` | 뜻 |
|---|---|
| `SCALE_WINDOW_TOO_SHORT` | **질의** 척도의 연속 구간이 60개월 미만 (spec FR-005·FR-008) |
| `SCALE_ZERO` | **질의** 척도의 MAD도 IQR도 0 (spec FR-006) |
| `QUERY_INPUT_MISSING` | 어느 지표의 비교 시점이 하나도 없다 (spec FR-001·FR-007) |
| `NO_CANDIDATE` | 유효 후보 0건 |

**후보 척도가 60개월 미만이거나 0인 것은 위 사유가 아니다** — 검색은 정상이고 그 후보만 빠지며, `counts.excluded`의 `NO_CASE_SCALE`로 센다(spec FR-005·FR-012).

**503을 쓰지 않는다.** 503은 "일시적이니 다시 오라"는 뜻인데, 척도가 0인 것은 다시 와도 같다.

### 오류

| 상태 | 언제 |
|---|---|
| 400 | `mode=field`인데 `field` 없음, `baselineMonth` 형식 오류, `limit`이 3·10이 아님 |
| 404 | 등록표에 없는 `field`, 또는 자료 범위 밖의 `baselineMonth` |

`baselineMonth`가 자료 범위 안이지만 척도가 안 나오는 경우는 404가 아니라 **200 + `NOT_COMPUTABLE`**
이다. "자료가 없다"와 "계산할 수 없다"는 다른 말이고, 후자는 사용자가 이해할 수 있는 결과다.

## 2. `GET /regime-searches/{searchId}`

**특정 항목을 지목해 읽는다.** [006 내 기록](../../006-my-records/spec.md)이 고정한 검색을 나중에
다시 열 때 쓴다 — §1은 늘 최신 `revision`을 주므로, 자료가 개정된 뒤에는 그때 본 결과를 열 수 없다
(006 SC-001 *"동일 버전으로 다시 열린다"*).

`searchId`는 §1 응답의 값 그대로다. 응답 모양은 §1과 같다. 그 항목이 정리되어 없으면 404이며,
정리 규칙은 [data-model.md](../data-model.md) §7이다.

## 3. `GET /regime-searches/cases/{endMonth}`

사례 상세. 거리·기여도·지표별 z 값·자료 기준이 여기 있다(spec FR-015: 목록은 관측 구간·비슷한 점·
다른 점을 먼저, 나머지는 상세).

### 요청

`mode`·`field`·`baselineMonth`를 §1과 같이 받는다 — 상세는 **어느 검색의 사례인지**가 있어야
의미가 있다. 기준월이 다르면 같은 `endMonth`라도 z와 거리가 다르다. 고정된 항목의 사례를 볼 때는
`searchId`를 대신 받는다.

### 응답 200

```json
{
  "endMonth": "2012-03",
  "distance": "0.412938571034",
  "byField": {"LABOR": "0.412938571034"},
  "indicators": [
    {
      "id": "UNEMPLOYMENT_RATE", "name": "실업률", "unit": "%",
      "queryRaw": "3.1", "caseRaw": "3.2",
      "queryZ": "-0.590219224283", "caseZ": "-0.612004881230",
      "comparisonMonths": ["2012-01", "2012-02", "2012-03"],
      "e": "0.168634064081",
      "nominalWeight": "0.500000000000",
      "distanceShare": "0.408372910114",
      "queryScale": {"median": "3.45", "spread": "0.593040000000", "method": "MAD",
                     "windowStart": "2016-05", "windowEnd": "2026-04", "months": 120},
      "caseScale":  {"median": "3.50", "spread": "0.490000000000", "method": "MAD",
                     "windowStart": "2002-01", "windowEnd": "2011-12", "months": 120}
    }
  ],
  "postPeriod": {"endMonth": "2012-03", "available": false,
                 "note": "사례 이후 결과는 아직 연결되지 않았습니다."}
}
```

- `nominalWeight`(명목 비중 `1/n`)와 `distanceShare`(실제 거리 기여 `E_i ÷ 분야 안 ΣE_i`)를 **나란히**
  내보낸다 — spec FR-010이 기록하라는 값이고, 둘의 차이가 U02 검증의 관측 대상이다. 명목 비중이
  같아도 실제 기여는 다를 수 있다.
- **`mode=field`에서는 `contribution`을 내보내지 않는다.** 분야가 하나뿐이라 값이 늘 `1`이고,
  "기여도 100%"로 읽히면 뜻 없는 숫자가 하나 는다. `mode=composite`에서만 분야별 `contribution`이
  온다.
- `contribution`은 전체 거리가 0이면 **키 자체를 생략한다**(spec Edge Cases). `distanceShare`도
  분야 안 `ΣE_i`가 0이면 같다.
- `postPeriod.available`은 011이 붙기 전까지 `false`다. 빈 값을 만들어 내지 않는다.
- `queryScale`·`caseScale`과 `comparisonMonths`가 SC-003의 수기 대조 입력이다 — **두 척도**의
  중앙값·산포·방법·기준 구간과 비교 시점이 전부 있어 독립 계산으로 z와 `E_i`를 다시 만들 수 있다
  (spec FR-028).
- **`caseScale`은 기준월과 무관하다**(spec FR-005). 같은 `endMonth`면 어느 검색에서 열어도 같은 값이
  온다(SC-011).

## 4. `GET /regime-searches/candidates`

**전체 유효 후보의 순위·거리 목록.** [011](../../011-regime-strategy-link/spec.md)이 ETF 가용성
필터를 적용할 때 쓴다(spec FR-030).

요청은 §1과 같고(`mode`·`field`·`baselineMonth` 또는 `searchId`), 응답은 표시 후보 10개 밖까지
담는다.

```json
{
  "searchId": "FIELD:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V2",
  "candidates": [{"endMonth": "2012-03", "distance": "0.412938571034", "rank": 1}]
}
```

**010은 이 목록에 필터도 ±6개월 건너뛰기도 적용하지 않는다** — 걸러지지 않은 원본 순위다(spec FR-012). 건너뛰기는 순차 규칙이라 011이 가용성 필터를 건 **뒤에** 적용해야 같은 결과가 나온다. 필터는 011이 걸고, 걸었다는 사실과 제외 수를 011이
표시한다. 010이 요청마다 필터를 받아 순위를 다시 매기면 같은 기준월에 순위가 두 벌 생기고, 그것이
constitution 원칙 VI가 금지한 *"가용성 필터 결과로 전체 경제환경 순위를 조용히 대체"* 다.

이 엔드포인트는 화면 목록용이 아니다 — 사용자 화면은 §1의 `cases`(최대 10개)를 쓴다.

## 5. `GET /regime-searches/baseline-months`

화면이 기준월 선택과 모드 전환 안내를 만들 때 쓴다.

```json
{
  "byField": [{"id": "LABOR", "name": "노동", "baselineMonth": "2026-07"}],
  "composite": "2026-05",
  "range": {"earliest": "1995-04", "latest": "2026-07"},
  "note": "전체 분야를 맞추기 위해 비교 기준월이 바뀝니다."
}
```

`composite`가 `byField`의 어느 값보다 이를 수 있다 — 발표 시차가 가장 긴 지표를 따르기 때문이다
(008 Assumptions). `note`는 모드 전환 시 한 문장 알림의 고정 문구다(spec US3 시나리오 1).

**값의 출처가 둘이다**: 분야 **이름**은 `MacroIndicatorPort`가(008 등록표), **기준월과 범위**는
`regime_baseline_month` 표가 준다. 기준월을 008에서 직접 읽지 않는 이유는
[contracts/macro-indicator-port.md](macro-indicator-port.md) §0.2 — 008이 올린 최신 T_g는 배치가 아직
계산하지 않았을 수 있고, 그 달로 검색하면 404가 난다. **화면이 고를 수 있는 달은 결과가 실제로 있는
달뿐이다.**

## 6. 내보내지 않는 것

| 무엇 | 왜 |
|---|---|
| 유사도 퍼센트, 신뢰도 | spec FR-022 |
| z 값·분위수를 화면 문구로 | spec FR-015 — 설명은 원값과 '평소 대비' 말로만 |
| '충분히 유사'·'대표 유사국면' 배지 | spec FR-018·FR-020 |
| 유지율 | spec FR-031 (기록에만) |
| 가용성 필터가 적용된 순위 | spec FR-030 — 010은 필터를 적용하지 않는다. §4의 목록 위에서 011이 건다 |
| 거리 함수·척도 선택 파라미터 | spec US1 시나리오 5 |
| `baselineOrigin`을 고르는 파라미터 | 요청을 보고 서버가 정한다(§1) |
| KOSPI·전략 성과 | spec FR-004 — 검색 입력이 아니다 |
| 수집 시각(자료 배치가 돈 시각) | spec FR-002·FR-031 — 값이 그대로여도 배치는 돌기 때문에 새 정보처럼 읽힌다 |
