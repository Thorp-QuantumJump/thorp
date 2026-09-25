# 데이터 모델: 경제환경 검색

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

저장 규칙은 `V2__backtest_result.sql`·`V3__param_search.sql`의 규약을 그대로 따른다.

> **질의(필터·정렬·조인)에 쓰는 값만 컬럼, 표시용 값은 JSONB. decimal은 예외 없이 JSONB다** —
> `NUMERIC`은 scale을 임의로 정해야 하고 그 자릿수를 넘는 값을 조용히 반올림하지만, JSON은 계산이
> 낸 값과 자릿수를 그대로 보존한다(research R4).

> **2026-09-16 갱신**: spec Clarifications(Session 2026-09-16) 8건을 반영했다. 바뀐 것은
> ① 식별 키에서 검색 경로·기준월 근거 제거, ② 전체 유효 후보 목록 보존, ③ 결과가 달라진 때만
> 새 항목(revision), ④ 지표별 비교 시점, ⑤ 지표별 거리 기여, ⑥ 보관 규칙, ⑦ `FIELD` 명칭,
> ⑧ 역사 비교 표시다.

## 1. 전체 그림

```
data/ 파이프라인 (오프라인)                         PostgreSQL              backend (읽기 전용)
────────────────────────────────                  ──────────────          ──────────────────
008 수집 → 지표 시계열                             macro_indicator_*        ← 008 소유
                    │
                    ▼
        [사전 계산 배치]  기준월 t마다
          척도 m·s → z → 거리 → 순위 → 상위 10   regime_search_snapshot   → GET /regime-searches
          + 전체 유효 후보 목록                   regime_search_case
          + 기준월 기록                           regime_baseline_month
                                                          │
                                                          └─ (로그인 시) regime_search_log
```

**010이 소유하는 테이블은 넷**이다. 지표 시계열(`macro_indicator_*`)은 008 소유이고 **`data/`
파이프라인만 그것을 읽는다** — Backend는 시계열에 닿지 않는다. Backend가 008에서 직접 읽는 것은
지표·분야 **등록표**뿐이며, 그 통로가 `MacroIndicatorPort`다(004의 `MarketMetadataPort`, 007의
`ParameterRegistryPort`와 같은 모양).

## 2. `regime_search_snapshot` — 사전 계산 항목

기준월 × 모드 하나의 검색 결과. **이 행이 곧 "검색"이며 사용자 요청은 이 행을 읽을 뿐이다**
(spec FR-030).

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK | SEQUENCE |
| `snapshot_key` | VARCHAR(200) NOT NULL UNIQUE | 아래 식별 규칙으로 조립한 값 |
| `mode` | VARCHAR(16) NOT NULL | `FIELD` \| `COMPOSITE` |
| `field_id` | VARCHAR(32) | 분야 id. `COMPOSITE`이면 NULL |
| `baseline_month` | DATE NOT NULL | 기준월 t. 월의 1일로 정규화 |
| `revision` | INT NOT NULL | 같은 (모드·분야·기준월)에서 결과가 달라진 순번. 1부터 |
| `content_hash` | CHAR(64) NOT NULL | 공통 척도·사례·시대별 보조 설명을 포함한 결과 본문의 해시. 계산 시각은 제외하며 직전 revision과 같으면 새 행을 만들지 않는다 |
| `data_version` | VARCHAR(40) NOT NULL | 이 내용을 **처음 낸** 008 자료 버전. **임시 대역이 계산한 행은 `FIXTURE-`로 시작한다** |
| `registry_version` | VARCHAR(40) NOT NULL | 008 등록표 버전 |
| `model_version` | VARCHAR(40) NOT NULL | 010 모델 버전 |
| `status` | VARCHAR(24) NOT NULL | `NOT_COMPUTABLE` \| `NEAREST_ONLY` \| `THRESHOLD_APPLIED` |
| `status_reason` | VARCHAR(48) | 아래 사유 표(§2.1) |
| `scale` | JSONB NOT NULL | 지표별 `{m, s, method, windowStart, windowEnd, months}`. `method`는 `MAD` \| `IQR` |
| `comparison_months` | JSONB NOT NULL | 지표별 `{months: [...], omitted: [...]}` — 비교 시점과 빠진 달(spec FR-001) |
| `ranked_candidates` | JSONB NOT NULL | 전체 유효 후보 `[{endMonth, distance, rank}]`. 011의 가용성 필터 입력(spec FR-030) |
| `candidate_counts` | JSONB NOT NULL | `{evaluated, excluded: {MISSING_INPUT: n, WITHIN_12_MONTHS: n, NEIGHBOR_SKIPPED: n}}` |
| `data_asof` | JSONB NOT NULL | 지표별 `{targetMonth, publishedAt, revisedAt?}`. 화면의 자료 기준 시점 표시(FR-031) |
| `computed_at` | TIMESTAMP NOT NULL | 배치가 계산한 시각 |
| `created_at` / `updated_at` | TIMESTAMP NOT NULL | |

**식별 규칙** (spec FR-029):

```
{mode}:{fieldId|ALL}:{baselineMonth}:{dataVersion}:{registryVersion}:{modelVersion}
예) FIELD:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V1
```

`VARCHAR(200)`인 이유: 위 여섯 조각의 최대 길이 합이 16+32+10+40+40+40+5 = 183자다. 예시가 48자라고
`VARCHAR(80)`으로 잡으면 긴 버전 문자열에서 잘린다.

**키에 넣지 않는 것** (spec FR-029, Clarifications 2026-09-16):

| 무엇 | 왜 |
|---|---|
| 검색 경로(시장 흐름/전략 결과) | 경로가 달라도 같은 계산이다. 경로는 조회한 쪽이 기록한다(`regime_search_log`) |
| 기준월의 근거(T_g/T_all/지정월) | 요청의 성질이지 결과의 성질이 아니다. 같은 월이면 같은 결과이고, 요청을 모르는 배치는 근거를 정할 수도 없다. 응답의 `baselineOrigin`은 **조회 시점에 판단해** 만든다 |

**같은 (모드·분야·기준월)에 행이 여럿일 수 있다.** 자료 개정으로 결과가 실제로 달라질 때마다
`revision`이 하나 오른다. 내용이 같으면(= `content_hash`가 같으면) **새 행을 만들지 않는다**
(spec FR-030, SC-002).

- 보통 조회: 그 (모드·분야·기준월)의 **가장 큰 `revision`** 행
- 기록에서 다시 열기: [006](../006-my-records/spec.md)이 고정한 `id`의 행

인덱스:

- `UNIQUE (snapshot_key)` — 버전으로 지목해 읽는 경로
- `UNIQUE (mode, field_id, baseline_month, revision)` — "가장 최신" 판정의 근거
- `UNIQUE (mode, field_id, baseline_month, content_hash)` — 같은 내용이 두 번 들어가는 것을 스키마로 막는다(SC-002)
- `(mode, field_id, baseline_month, revision DESC)` — 화면이 기준월을 바꿔 가며 최신 행을 읽는 경로

### 2.1 검색 상태 사유

| `status_reason` | 뜻 | 근거 |
|---|---|---|
| `SCALE_WINDOW_TOO_SHORT` | 척도 연속 구간이 60개월 미만 | spec FR-005·FR-008 |
| `SCALE_ZERO` | MAD도 IQR도 0 | spec FR-006 |
| `QUERY_INPUT_MISSING` | **어느 지표의 비교 시점이 하나도 없다** | spec FR-001·FR-007 |
| `NO_CANDIDATE` | 유효 후보 0건 | spec FR-012 |
| `THRESHOLD_NOT_CALIBRATED` | 문턱 미교정(`NEAREST_ONLY`의 사유) | spec FR-020, research R6 |

`QUERY_INPUT_MISSING`이 따로 있는 이유: 질의 입력이 없는 것은 척도 문제도 후보 문제도 아니다.
다른 사유에 접어 넣으면 화면이 "척도가 60개월이 안 된다"고 잘못 말하게 된다.

### 2.2 비교 시점 (`comparison_months`)

```json
{
  "UNEMPLOYMENT_RATE": {"months": ["2026-05","2026-06","2026-07"], "omitted": []},
  "EMPLOYED_GROWTH":   {"months": ["2026-05","2026-07"],           "omitted": ["2026-06"]}
}
```

**지표마다 따로 정한다**(spec FR-001). **모드가 이 규칙을 바꾸지 않는다** — 종합 snapshot의 `comparison_months`도 지표별이고, 그래서 같은 분야의 `D_g`가 분야 snapshot과 종합 snapshot에서 같은 값이 된다(SC-002). 같은 분야 안에서도 결측 월이 다를 수 있고, 멀쩡한 지표의
자료를 다른 지표의 구멍 때문에 버리지 않는다. `months`가 빈 지표가 하나라도 있으면 그 분야(종합이면
종합)는 `NOT_COMPUTABLE` / `QUERY_INPUT_MISSING`이다 — **그 지표를 빼고 나머지로 계산하지 않는다**
(spec FR-007). `omitted`가 비어 있지 않으면 화면이 지표·월 단위로 표기한다(spec FR-031, SC-007).

후보도 **같은 비교 시점**의 입력이 모두 있어야 한다(spec FR-012). 후보에게 질의보다 많은 시점을
요구하지 않는다 — 요구하면 같은 조건 비교가 깨진다(원칙 IV).

### 2.3 전체 유효 후보 (`ranked_candidates`)

```json
[{"endMonth": "2012-03", "distance": "0.412938571034", "rank": 1}]
```

표시 후보 10개 **밖의** 후보까지 순위와 거리를 담는다(약 190개). **±6개월 건너뛰기는 적용하지 않은 원본 순위다**(spec FR-012) — 건너뛰기가 하나씩 담을 때마다 적용되는 순차 규칙이라, 걸러 둔 목록에 011이 가용성 필터를 얹으면 어느 후보가 걸러졌어야 하는지 다시 계산할 수 없다. [011](../011-regime-strategy-link/spec.md)
FR-003의 ETF 가용성 필터가 표시 후보 밖 사례를 필요로 하는데, 그때 010이 순위를 다시 매기면 같은
기준월에 순위가 두 벌 생긴다(spec FR-030, Clarifications 2026-09-16).

**행이 아니라 JSONB 한 칼럼이다.** 사례로 펼치면 3,000 snapshot × 190 ≈ 570,000행이 되지만, 이 값은
정렬·조인 대상이 아니라 통째로 읽어 내보내는 표시용 자료다. research R3가 "전체 후보 저장"을 버린
이유는 행 수였고, 칼럼 하나에는 해당하지 않는다.

## 3. `regime_baseline_month` — 배치가 기록한 기준월

**화면이 고를 수 있는 기준월**이다. 배치가 008의 `baselineMonths()`를 읽어 자기 적재와 **같은
트랜잭션에서** 기록한다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `scope_id` | VARCHAR(32) PK | 분야 id, 또는 종합이면 `ALL` |
| `baseline_month` | DATE NOT NULL | 그 범위의 최신 기준월 — 분야면 T_g, `ALL`이면 T_all |
| `earliest_month` | DATE NOT NULL | 사전 계산이 있는 가장 오래된 기준월(`range.earliest`) |
| `registry_version` | VARCHAR(40) NOT NULL | 이 값을 만든 008 등록표 버전 |
| `data_version` | VARCHAR(40) NOT NULL | 이 값을 만든 008 자료 버전 |
| `updated_at` | TIMESTAMP NOT NULL | |

**T_g·T_all을 [contracts/macro-indicator-port.md](contracts/macro-indicator-port.md)의 포트로 읽지
않는 이유**: 008은 새 발표분이 들어오는 즉시 T_g를 올리지만 배치는 그 뒤에 돈다. 그 사이 화면이
008의 최신 T_g로 검색하면 **아직 snapshot이 없는 달이라 404**가 난다. 화면이 고를 수 있는 달은
**결과가 실제로 있는 달**이어야 하므로, 배치가 자기 적재와 함께 남긴 값이 맞다.

`MAX(baseline_month)`로 대신할 수도 없다 — 배치는 `NOT_COMPUTABLE` 행도 만들기 때문에(계산 불가를
정상 화면으로 처리해야 한다) 어느 한 지표만 자료가 있는 달에도 행이 생기고, 그 `MAX`는 008이 정의한
T_g(*"분야 지표들이 함께 확보된 최신월"*, 008 FR-013)보다 뒤로 간다.

**분야 이름은 이 표에 없다.** 이름·단위·순서는 `MacroIndicatorPort`가 돌려준다 — 표시용 참조 자료라
008의 최신 값을 그대로 보이는 편이 맞고, 화면 분류가 바뀌어도 계산 가중은 따라가지 않는다
(spec FR-011).

---

## 4. `regime_search_case` — 표시 후보

한 snapshot에 최대 10행(spec FR-013). 목록·상세 화면이 직접 읽는 값이라 행으로 펼친다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK | SEQUENCE |
| `snapshot_id` | BIGINT NOT NULL → `regime_search_snapshot(id)` | |
| `rank` | SMALLINT NOT NULL | 1부터. 거리 오름차순, 동률은 오래된 종료월 우선(FR-012) |
| `end_month` | DATE NOT NULL | 사례의 관측 구간 종료월. **사례의 동일성은 이 값으로 판정한다** |
| `distance` | JSONB NOT NULL | `{value, byField: {...}, contribution: {...}}` — 전부 문자열 decimal. 종합 v3는 `{value, byField: {}, blocks: [{id, name, gap, spread}], blockContribution: {...}}` (spec FR-044) |
| `indicator_values` | JSONB NOT NULL | 지표별 `{queryRaw, caseRaw, queryZ, caseZ, unit, e, nominalWeight, distanceShare}`. 종합 v3는 국면 입력 값이고 `distanceShare`가 없다 |
| `explanation` | JSONB NOT NULL | `{similar: [문장], different: [문장]}` (research R8 템플릿 조립). 종합 v3는 `unusual`(bool)과, 있으면 `queryNote`(지금 시점 안내)·`context`(당시 맥락 `[{kind, date, title, url, source}]`)를 더한다 (spec FR-042·FR-043) |
| `era_position` | JSONB | 시대별 평소 대비 위치(FR-017 보조). 지표별 `{sentence, method, windowStart, windowEnd, months}`. 이력이 부족하거나 보조 척도가 0인 지표는 키가 없고, 전부 계산 불가이면 NULL |
| `created_at` / `updated_at` | TIMESTAMP NOT NULL | |

- `CONSTRAINT uk_regime_case_rank UNIQUE (snapshot_id, rank)`
- `CONSTRAINT uk_regime_case_month UNIQUE (snapshot_id, end_month)` — ±6개월 건너뛰기가 같은 월을 두 번
  넣지 않음을 스키마로 고정한다
- `INDEX (snapshot_id, rank)`

**`indicator_values`의 `nominalWeight`·`distanceShare`가 spec FR-010이 기록하라는 값이다.**
`nominalWeight`는 명목 비중(분야 내부 동일가중 `1/n`), `distanceShare`는 실제 거리 기여
(`E_i ÷ 분야 안 ΣE_i`)다. 둘을 나란히 두는 것이 U02(분야·지표 중복과 가중 영향) 검증의 입력이다 —
명목 비중이 같아도 실제 기여는 다를 수 있고, 그 차이가 관측 대상이다.

**전체 거리가 0이면 `contribution`을 넣지 않는다**(spec Edge Cases) — 0으로 나눈 값을 만들지 않고
키를 생략한다. 키 없음이 "표시하지 않음"이다. 같은 이유로 분야 안 `ΣE_i`가 0이면 `distanceShare`
키도 생략한다.

**분야 검색(`mode=FIELD`)에서는 `contribution` 키를 만들지 않는다.** 분야가 하나뿐이라 값이 늘
`1`이고, 화면에 "기여도 100%"로 읽히면 뜻 없는 숫자가 하나 는다.

**`era_position`은 문장만 담는다.** z 값·분위수를 저장해 두면 화면이 언젠가 그것을 내보내고, 그 순간
유사도처럼 읽힌다(spec FR-017·FR-022). 수기 대조에 필요한 값은 `method`·`windowStart`·`windowEnd`·
`months`로 충분하다 — 그 구간과 방법이 있으면 독립 계산으로 같은 판정을 다시 만들 수 있다.

**이 값은 같은 자료·등록표·모델 버전에서 기준월 t와 무관하다.** 후보 종료월 c의 `c-122 ~ c-3`에서만 나오므로 같은 후보월이 여러
snapshot에 등장해도 값이 같다 — 척도 m·s가 t마다 달라지는 질의 쪽(§2.3, research R3)과 반대 성질이며,
배치는 이 값을 **후보월마다 한 번만** 계산해 공유한다.

**사후 평가 구간(63/21거래일)은 여기 두지 않는다.** 사례 식별자(`snapshot_key` + `end_month`)와
평가 날짜로 [011](../011-regime-strategy-link/spec.md)이 붙인다(spec Assumptions).

## 5. `regime_search_log` — 로그인 사용자의 검색 기록

**비로그인 검색은 행을 만들지 않는다**(spec FR-032). 결과 자체는 snapshot에 이미 있으므로 재사용은
이 표 없이 성립한다. **[014](../014-user-auth/spec.md) 도입 전에는 이 표에 행이 생기지 않는다** —
그래서 010은 014를 기다리지 않는다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK | |
| `owner_user_id` | BIGINT NOT NULL | 014 도입 후에 채워진다 |
| `snapshot_id` | BIGINT → `regime_search_snapshot(id)` **ON DELETE SET NULL** | **특정 revision을 고정한다** — 나중에 결과가 달라져도 그때 본 것을 그대로 연다. 철회(FR-035)로 그 항목이 사라지면 NULL이 된다 |
| `snapshot_key` | VARCHAR(200) NOT NULL | 위 키의 **사본**. 철회로 `snapshot_id`가 NULL이 돼도 **무엇을 봤는지는 남는다**(FR-035) |
| `entry_path` | VARCHAR(24) NOT NULL | `MARKET_FLOW` \| `STRATEGY_RESULT` — 식별 키가 아니라 여기 기록된다 |
| `baseline_origin` | VARCHAR(16) NOT NULL | `T_G` \| `T_ALL` \| `USER_SPECIFIED` — 요청의 성질이라 snapshot이 아니라 여기 남는다 |
| `viewed_at` | TIMESTAMP NOT NULL | |

[006](../006-my-records/spec.md) FR-012가 요구하는 계산 항목은 **snapshot이 이미 들고 있다**(모드·
기준월·비교 시점·후보 집계·제외 이유·지표 원값과 표준화 값·분야별 거리·자료 버전·모델 버전).
여기에 복제하지 않는다 — 복제하면 배치가 재계산할 때 두 값이 갈린다. 요청의 성질(경로·근거)만
여기 있다.

**사용자 식별자는 이 표에만 있다**(spec FR-036). `regime_search_snapshot`·`regime_search_case`에는
사용자를 가리키는 컬럼이 없고 앞으로도 두지 않는다 — 계산 결과는 **여러 사용자가 공유하는 자원**이다.
사용자가 기록을 지우거나 탈퇴하면 **이 표의 행만 지우고** snapshot은 건드리지 않는다; 함께 지우면
같은 항목을 보던 다른 사용자의 검색까지 사라진다.

**가용성 필터 키와 전략 연결 정보는 이 표에 없다.** 010은 필터를 적용하지 않고 전체 후보 목록만
내주므로 그 값을 만들 수 없다. [011](../011-regime-strategy-link/spec.md) FR-004·FR-023이 채운다
(spec FR-032).

인덱스: `(owner_user_id, viewed_at DESC)`

## 6. 모델 버전

`model_version`은 아래가 바뀌면 올린다(spec Key Entities). 값은 `REGIME-V1` 형태의 문자열이고
사전 계산 배치가 상수로 들고 있다. **범위마다 다를 수 있다** — 분야 검색은 `REGIME-V1`, 종합 비교는
설정 `data/config/regime-phase-v3.json`의 `version`(`REGIME-V3-EQ`)이다(spec FR-044). 종합은 국면 입력
규칙·블록 구성·블록 요인 방식·국면 거리·잔차 문턱이 바뀌어도 올린다. 당시 맥락 표(`regime-context-v1.json`)는
결과 본문에 들어가므로 표가 바뀌면 내용 해시가 바뀌어 새 revision이 된다(모델 버전은 그대로).

| 바뀌면 새 버전 | 근거 |
|---|---|
| 지표 집합·변환 | FR-004 |
| 척도 규칙(산출 구간, 구간 길이, 연속 판정, 분위수 방법, 보정 상수) | FR-005·FR-006, research R5 |
| **비교 시점 규칙**(결측 달 제외, 지표 단위 적용) | FR-001 |
| 거리식·가중 | FR-009·FR-010 |
| **시대별 위치 판정 경계값**(문장이 '높은/낮은/비슷한'으로 갈리는 z 기준) | FR-017, research R8 |
| 후보 제한(12개월·±6개월·동률) | FR-012 |
| 표시 개수 | FR-013 |
| 문턱 | FR-019·FR-020 |
| decimal 반올림 자릿수 | research R4 |

**버전을 올리면 이전 snapshot을 지우지 않는다.** 이전 검색이 참조하던 결과가 그대로 남아야
재현이 성립한다(spec FR-031, 원칙 VIII). 모델 버전이 바뀌면 재계산 범위의 M이 계열 시작월이 되어
전 구간이 다시 계산되고, 내용이 달라진 만큼 `revision`이 오른다. 배치는 **범위마다** 그 범위의 현재 모델 버전으로 적재한 항목이 있는지 보고, 없으면 그 범위만
M을 계열 시작월로 둔다 — 종합만 모델이 바뀌면 분야 검색은 다시 돌지 않는다.

### 6.1 임시 대역이 만든 행의 표시와 폐기

008 미구현 기간에 픽스처로 계산한 행은 `data_version`이 **`FIXTURE-`로 시작한다**(예:
`FIXTURE-20260915`). 008의 실제 자료 버전과 문자열이 절대 겹치지 않는다.

이 접두사가 하는 일은 둘이다.

1. **가짜임이 어디서나 드러난다.** 버전은 식별 키·API 응답(`versions.data`)·기록에 전부 실려 있어,
   값이 어느 경로로 흘러가도 `FIXTURE-`가 함께 간다. 화면은 추가로 `versions.sampleData`를 보고
   예시 자료 표시를 낸다(008 FR-022).
2. **폐기가 조건 한 줄로 끝난다.** 008이 완성되면 `data_version LIKE 'FIXTURE-%'` 행을 지우고 실제
   자료로 전 구간을 다시 계산한다. 절차는
   [contracts/macro-indicator-port.md](contracts/macro-indicator-port.md) §3.3.

**`FIXTURE-` 행과 실제 행이 같은 표에 섞여 있어도 안전하다** — 식별 키에 자료 버전이 들어 있어
조회가 서로를 집을 수 없고, 버전이 다르면 애초에 다른 검색이다(FR-029).

## 7. 보관

**어느 기록도 가리키지 않는 옛 항목은 정리한다**(spec FR-029). constitution "기록·개인정보·보관"이
*"무기한 보관을 전제하지 않는다"* 로 요구하는 정책이 이것이다.

| 대상 | 처리 |
|---|---|
| 각 (모드·분야·기준월)의 **가장 큰 revision** | 보존 — 보통 조회가 읽는 행 |
| `regime_search_log`가 가리키는 revision | 보존 — 기록에서 다시 열어야 한다(006 SC-001) |
| 그 밖의 옛 revision | 정리 대상 |
| `data_version LIKE 'FIXTURE-%'` | 008 완성 시 일괄 삭제(§6.1) |
| **철회된 revision**(FR-035) | 기록이 가리키고 있어도 **삭제** — 계산 오류가 확인된 경우에 한한다. 기록 행은 남고 `snapshot_id`만 NULL이 되며 `snapshot_key` 사본으로 무엇이었는지 드러난다 |

정리는 **사전 계산 배치가 적재를 마친 뒤 같은 실행에서** 수행한다(spec FR-029) — 사람이 따로 돌리는 잡을 두지 않는다.
**철회는 정리와 다르다**(FR-035) — 정리는 배치가 자동으로 하고 보존 대상을 건드리지 않지만, 철회는
**사람이 범위를 지정해 지시할 때만** 하고 기록이 가리키는 항목까지 지운다. 철회한 식별 키·범위·사유·
시각은 따로 기록한다. **행이 거의 늘지 않으므로 정리할 것도 거의 없다** —
새 발표분 하나에 새로 생기는 행은 기준월 1개 × 모드 8 = 8행이고, 개정이 있으면 최근 몇 달치가 더
붙는 정도다(spec FR-030).

## 8. 상태 전이

검색 상태는 **계산 시점에 정해져 저장되고 조회 시점에 다시 판정하지 않는다**(research R6).

```
                    척도 연속 구간 < 60개월        ─┐
                    MAD=0 이고 IQR=0              ─┤
                    어느 지표의 비교 시점이 0개     ─┼─▶  NOT_COMPUTABLE
                    유효 후보 0건                  ─┘

                    그 밖 + 문턱 미교정     ───▶  NEAREST_ONLY        ← 현재 전부 여기
                    그 밖 + 문턱 교정됨     ───▶  THRESHOLD_APPLIED   ← PD-09 확정 후
```

- 분야 하나가 `NOT_COMPUTABLE`이면 그 분야를 포함하는 **종합도 `NOT_COMPUTABLE`**이다(US3 시나리오 6).
  6개 분야로 계산하지 않는다.
- 문턱이 교정돼 `THRESHOLD_APPLIED`가 생겨도 **이미 `NEAREST_ONLY`로 저장된 snapshot은 그대로 둔다**
  (spec Edge Cases). 새 모델 버전으로 다시 계산한 것이 새 revision이 된다.

## 9. 값의 소유 경계

| 값 | 소유 | 010이 하는 일 |
|---|---|---|
| 지표 월별 값(원값·변환값) | 008 | **`data/` 배치만** 읽는다 |
| 지표 → 분야 매핑, 이름·단위, 등록표 버전 | 008 | Backend가 `MacroIndicatorPort`로 읽는다(표시용). 배치는 계산용으로 읽는다 |
| T_g · T_all | 008 | 배치가 읽어 기준월을 정하고 **`regime_baseline_month`에 기록**한다. 조회는 그 표를 읽는다 |
| 발표일·개정 여부 | 008 | 읽어서 `data_asof`에 복사 (표시용 스냅샷) |
| 비교 시점, 척도 m·s, z, 거리, 지표·분야 기여, 순위, 전체 후보 목록 | **010** | 계산·저장 |
| 검색 상태 | **010** | 계산·저장 |
| 가용성 필터 키, 전략 연결 정보 | 011 | 만들지 않는다 |
| 사후 구간 시장·전략 결과 | 011 | 사례 식별자만 제공 |

`data_asof`가 008 값의 복사본인 것은 의도적이다 — 화면이 "이 결과가 언제 자료인가"를 말하려면
**계산 당시의 발표일**이어야 하고, 지금 008을 다시 읽으면 그 뒤에 개정된 값이 나온다.
