# 계약: 경제환경 검색 HTTP API

**Spec**: [../spec.md](../spec.md) | **Data model**: [../data-model.md](../data-model.md)

## 0. 공통

- **동기 응답이다.** 접수·상태 조회가 없다. 요청은 사전 계산된 결과 조회이고 요청 처리 중에
  척도·거리를 계산하지 않는다(spec FR-030, SC-002a).
- 인증 불필요. 비로그인으로 전부 볼 수 있다(spec FR-032).
- JSON 필드는 camelCase. **decimal은 문자열**이다(004 결과 조회와 같은 규약) — 숫자로 내보내면
  클라이언트 JSON 파서가 자릿수를 깎는다.
- 응답 포맷은 `market` 도메인과 같은 `HttpApiResponse`를 쓴다(`{httpStatus, isSuccess, message, code, result}`).
  아래 예시는 `result`의 내용만 적는다.

## 1. `GET /regime-searches`

검색 하나를 읽는다.

### 요청

| 파라미터 | 필수 | 값 | 설명 |
|---|---|---|---|
| `mode` | ✅ | `sector` \| `composite` | 분야 검색 / Thorp 종합 비교 |
| `field` | `mode=sector`일 때 ✅ | 분야 id | 008 등록표의 분야 |
| `baselineMonth` | — | `YYYY-MM` | 생략하면 `mode`에 따라 T_g 또는 T_all |
| `limit` | — | `3` \| `10` (기본 `3`) | 처음 3개, '더 보기'면 10 |

**거리 함수·척도·지표 조합을 고르는 파라미터는 두지 않는다**(spec US1 시나리오 5). 넣으면 사용자가
모델을 고르는 화면이 생긴다.

### 응답 200 — 정상

```json
{
  "searchId": "SECTOR:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V1",
  "mode": "SECTOR",
  "field": {"id": "LABOR", "name": "노동"},
  "baselineMonth": "2026-07",
  "baselineOrigin": "T_G",
  "observationMonths": ["2026-05", "2026-06", "2026-07"],
  "status": "NEAREST_ONLY",
  "statusNote": "가까운 순서만 제공합니다. 유사성은 아직 판정하지 않습니다.",
  "caseLabel": "최근접 참고 사례",
  "dataAsOf": {
    "targetMonth": "2026-07",
    "publishedAt": "2026-09-12",
    "note": "노동 지표는 2026년 7월분까지 반영되어 있습니다.",
    "revisions": []
  },
  "versions": {
    "data": "MACRO-20260915", "registry": "REG-3", "model": "REGIME-V1",
    "sampleData": true
  },
  "counts": {"evaluated": 187, "displayed": 3, "total": 10},
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

- `caseLabel`은 문턱 교정 전 고정 문구다(spec FR-020). `THRESHOLD_APPLIED`가 되면 사라진다.
- `dataAsOf.revisions`는 값이 개정된 경우에만 채운다(spec FR-031) —
  `[{"indicator": "실업률", "month": "2026-06", "revisedAt": "2026-09-10"}]`.
- `versions.sampleData`가 `true`면 008 미구현 상태의 예시 자료다(008 FR-022). 화면은 이 표시를
  반드시 노출한다.
- `distance`는 문자열이고 소수점 아래 12자리다(research R4).
- **유지율(retention)을 내려보내지 않는다**(spec FR-031).

### 응답 200 — 계산 불가

빈 결과는 오류가 아니다(spec US2 시나리오 4). **200으로 내려보낸다.**

```json
{
  "searchId": "SECTOR:CONSTRUCTION:2026-07-01:...",
  "status": "NOT_COMPUTABLE",
  "statusReason": "SCALE_ZERO",
  "statusNote": "이 분야는 지금 계산할 수 없습니다.",
  "counts": {"evaluated": 0, "displayed": 0, "total": 0},
  "cases": []
}
```

| `statusReason` | 뜻 |
|---|---|
| `SCALE_WINDOW_TOO_SHORT` | 척도 연속 구간이 60개월 미만 (spec FR-005·FR-008) |
| `SCALE_ZERO` | MAD도 IQR도 0 (spec FR-006) |
| `NO_CANDIDATE` | 유효 후보 0건 |

**503을 쓰지 않는다.** 503은 "일시적이니 다시 오라"는 뜻인데, 척도가 0인 것은 다시 와도 같다.

### 오류

| 상태 | 언제 |
|---|---|
| 400 | `mode=sector`인데 `field` 없음, `baselineMonth` 형식 오류, `limit`이 3·10이 아님 |
| 404 | 등록표에 없는 `field`, 또는 자료 범위 밖의 `baselineMonth` |

`baselineMonth`가 자료 범위 안이지만 척도가 안 나오는 경우는 404가 아니라 **200 + `NOT_COMPUTABLE`**
이다. "자료가 없다"와 "계산할 수 없다"는 다른 말이고, 후자는 사용자가 이해할 수 있는 결과다.

## 2. `GET /regime-searches/cases/{endMonth}`

사례 상세. 거리·기여도·지표별 z 값·자료 기준이 여기 있다(spec FR-015: 목록은 관측 구간·비슷한 점·
다른 점을 먼저, 나머지는 상세).

### 요청

`mode`·`field`·`baselineMonth`를 §1과 같이 받는다 — 상세는 **어느 검색의 사례인지**가 있어야
의미가 있다. 기준월이 다르면 같은 `endMonth`라도 z와 거리가 다르다.

### 응답 200

```json
{
  "endMonth": "2012-03",
  "distance": "0.412938571034",
  "byField": {"LABOR": "0.412938571034"},
  "contribution": {"LABOR": "1.000000000000"},
  "indicators": [
    {
      "id": "UNEMPLOYMENT_RATE", "name": "실업률", "unit": "%",
      "queryRaw": "3.1", "caseRaw": "3.2",
      "queryZ": "-0.590219224283", "caseZ": "-0.421585160202",
      "scale": {"median": "3.45", "spread": "0.593040000000", "method": "MAD",
                "windowStart": "2016-05", "windowEnd": "2026-04", "months": 120}
    }
  ],
  "eraPosition": null,
  "postPeriod": {"endMonth": "2012-03", "available": false,
                 "note": "사례 이후 결과는 아직 연결되지 않았습니다."}
}
```

- `contribution`은 **전체 거리가 0이면 키 자체를 생략한다**(spec Edge Cases).
- `eraPosition`은 보조 설명이며 이력이 부족하면 `null`이다(spec FR-017). **0으로 채우지 않는다.**
- `postPeriod.available`은 011이 붙기 전까지 `false`다. 빈 값을 만들어 내지 않는다.
- `indicators[].scale`이 SC-003의 수기 대조 입력이다 — 중앙값·산포·방법·기준 구간이 전부 있어
  독립 계산으로 z를 다시 만들 수 있다(spec FR-028).

## 3. `GET /regime-searches/baseline-months`

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

## 4. 내보내지 않는 것

| 무엇 | 왜 |
|---|---|
| 유사도 퍼센트, 신뢰도 | spec FR-022 |
| '충분히 유사'·'대표 유사국면' 배지 | spec FR-018·FR-020 |
| 유지율 | spec FR-031 (기록에만) |
| 전체 후보의 거리 목록 | 화면에 쓰이지 않는다. 집계(`counts`)만 |
| 거리 함수·척도 선택 파라미터 | spec US1 시나리오 5 |
| KOSPI·전략 성과 | spec FR-004 — 검색 입력이 아니다 |
