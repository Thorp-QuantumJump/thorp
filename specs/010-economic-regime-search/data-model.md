# 데이터 모델: 경제환경 검색

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

저장 규칙은 `V2__backtest_result.sql`·`V3__param_search.sql`의 규약을 그대로 따른다.

> **질의(필터·정렬·조인)에 쓰는 값만 컬럼, 표시용 값은 JSONB. decimal은 예외 없이 JSONB다** —
> `NUMERIC`은 scale을 임의로 정해야 하고 그 자릿수를 넘는 값을 조용히 반올림하지만, JSON은 계산이
> 낸 값과 자릿수를 그대로 보존한다(research R4).

## 1. 전체 그림

```
data/ 파이프라인 (오프라인)                         PostgreSQL              backend (읽기 전용)
────────────────────────────────                  ──────────────          ──────────────────
008 수집 → 지표 시계열                             macro_indicator_*        ← 008 소유
                    │
                    ▼
        [사전 계산 배치]  기준월 t마다
          척도 m·s → z → 거리 → 순위 → 상위 10   regime_search_snapshot   → GET /regime-searches
                                                  regime_search_case
                                                          │
                                                          └─ (로그인 시) regime_search_log
```

**010이 소유하는 테이블은 셋**이다. 지표 시계열(`macro_indicator_*`)은 008 소유이고 010은 읽기만
한다(research R1).

## 2. `regime_search_snapshot` — 사전 계산 항목

기준월 × 모드 하나의 검색 결과. **이 행이 곧 "검색"이며 사용자 요청은 이 행을 읽을 뿐이다**
(spec FR-030).

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK | SEQUENCE |
| `snapshot_key` | VARCHAR(80) NOT NULL UNIQUE | 아래 식별 규칙으로 조립한 값 |
| `mode` | VARCHAR(16) NOT NULL | `SECTOR` \| `COMPOSITE` |
| `field_id` | VARCHAR(32) | 분야 id. `COMPOSITE`이면 NULL |
| `baseline_month` | DATE NOT NULL | 기준월 t. 월의 1일로 정규화 |
| `baseline_origin` | VARCHAR(16) NOT NULL | `T_G` \| `T_ALL` \| `USER_SPECIFIED` |
| `data_version` | VARCHAR(40) NOT NULL | 008 자료 버전. **임시 대역이 계산한 행은 `FIXTURE-`로 시작한다** |
| `registry_version` | VARCHAR(40) NOT NULL | 008 등록표 버전 |
| `model_version` | VARCHAR(40) NOT NULL | 010 모델 버전 |
| `status` | VARCHAR(24) NOT NULL | `NOT_COMPUTABLE` \| `NEAREST_ONLY` \| `THRESHOLD_APPLIED` |
| `status_reason` | VARCHAR(48) | `SCALE_WINDOW_TOO_SHORT` \| `SCALE_ZERO` \| `NO_CANDIDATE` \| `THRESHOLD_NOT_CALIBRATED` |
| `scale` | JSONB NOT NULL | 지표별 `{m, s, method, windowStart, windowEnd, months}`. `method`는 `MAD` \| `IQR` |
| `candidate_counts` | JSONB NOT NULL | `{evaluated, excluded: {MISSING_INPUT: n, WITHIN_12_MONTHS: n, NEIGHBOR_SKIPPED: n}}` |
| `data_asof` | JSONB NOT NULL | 지표별 `{targetMonth, publishedAt}`. 화면의 자료 기준 시점 표시(FR-031) |
| `computed_at` | TIMESTAMP NOT NULL | 배치가 계산한 시각 |
| `created_at` / `updated_at` | TIMESTAMP NOT NULL | |

**식별 규칙** (spec FR-029):

```
{mode}:{fieldId|ALL}:{baselineMonth}:{dataVersion}:{registryVersion}:{modelVersion}
예) SECTOR:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V1
```

`baseline_origin`은 키에 넣지 않는다. 같은 월을 T_g로 얻었든 사용자가 지정했든 **결과가 같아야
하기 때문**이다 — 넣으면 같은 계산이 두 벌 저장되고 SC-002의 "같은 식별이면 같은 결과"가 흐려진다.
근거는 표시용으로만 쓴다.

**검색 경로**(시장 흐름 / 전략 결과)도 키에 넣지 않는다. 경로가 달라도 결과가 같기 때문이며,
경로는 조회한 쪽이 기록한다(`regime_search_log`).

인덱스:
- `UNIQUE (snapshot_key)` — 조회 진입점이자 배치의 멱등성 근거
- `(data_version, model_version, registry_version)` — 버전이 바뀌면 전 구간을 다시 계산하므로 묶어서 읽는다
- `(mode, field_id, baseline_month)` — 화면이 기준월을 바꿔 가며 읽는 경로

## 3. `regime_search_case` — 표시 후보

한 snapshot에 최대 10행(spec FR-013). **표시 후보만 저장한다** — 그 밖의 후보 거리는 화면에
쓰이지 않고 재계산이 수 초라 저장할 값이 없다(research R3).

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK | SEQUENCE |
| `snapshot_id` | BIGINT NOT NULL → `regime_search_snapshot(id)` | |
| `rank` | SMALLINT NOT NULL | 1부터. 거리 오름차순, 동률은 오래된 종료월 우선(FR-012) |
| `end_month` | DATE NOT NULL | 사례의 관측 구간 종료월. **사례의 동일성은 이 값으로 판정한다** |
| `distance` | JSONB NOT NULL | `{value, byField: {...}, contribution: {...}}` — 전부 문자열 decimal |
| `indicator_values` | JSONB NOT NULL | 지표별 `{queryRaw, caseRaw, queryZ, caseZ, unit}` |
| `explanation` | JSONB NOT NULL | `{similar: [문장], different: [문장]}` (research R8 템플릿 조립) |
| `era_position` | JSONB | 시대별 평소 대비 위치(FR-017 보조). 이력 부족이면 NULL |
| `created_at` / `updated_at` | TIMESTAMP NOT NULL | |

- `CONSTRAINT uk_regime_case_rank UNIQUE (snapshot_id, rank)`
- `CONSTRAINT uk_regime_case_month UNIQUE (snapshot_id, end_month)` — ±6개월 건너뛰기가 같은 월을 두 번
  넣지 않음을 스키마로 고정한다
- `INDEX (snapshot_id, rank)`

**전체 거리가 0이면 `contribution`을 넣지 않는다**(spec Edge Cases) — 0으로 나눈 값을 만들지 않고
키를 생략한다. 키 없음이 "표시하지 않음"이다.

**사후 평가 구간(63/21거래일)은 여기 두지 않는다.** 사례 식별자(`snapshot_key` + `end_month`)와
평가 날짜로 [011](../011-regime-strategy-link/spec.md)이 붙인다(spec Assumptions).

## 4. `regime_search_log` — 로그인 사용자의 검색 기록

**비로그인 검색은 행을 만들지 않는다**(spec FR-032). 결과 자체는 snapshot에 이미 있으므로 재사용은
이 표 없이 성립한다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK | |
| `owner_user_id` | BIGINT NOT NULL | 014 도입 전에는 이 표에 행이 생기지 않는다 |
| `snapshot_id` | BIGINT NOT NULL → `regime_search_snapshot(id)` | |
| `entry_path` | VARCHAR(24) NOT NULL | `MARKET_FLOW` \| `STRATEGY_RESULT` |
| `viewed_at` | TIMESTAMP NOT NULL | |

[006](../006-my-records/spec.md) FR-012가 요구하는 항목은 **snapshot이 이미 들고 있다**(모드·기준월·
후보 집계·제외 이유·지표 원값과 표준화 값·분야별 거리·자료 버전·모델 버전·가용성 필터). 여기에
복제하지 않는다 — 복제하면 배치가 재계산할 때 두 값이 갈린다.

인덱스: `(owner_user_id, viewed_at DESC)`

## 5. 모델 버전

`model_version`은 아래가 바뀌면 올린다(spec Key Entities). 값은 `REGIME-V1` 형태의 문자열이고
사전 계산 배치가 상수로 들고 있다.

| 바뀌면 새 버전 | 근거 |
|---|---|
| 지표 집합·변환 | FR-004 |
| 척도 규칙(구간 길이, 연속 판정, 분위수 방법, 보정 상수) | FR-005·FR-006, research R5 |
| 거리식·가중 | FR-009·FR-010 |
| 후보 제한(12개월·±6개월·동률) | FR-012 |
| 표시 개수 | FR-013 |
| 문턱 | FR-019·FR-020 |
| decimal 반올림 자릿수 | research R4 |

**버전을 올리면 이전 snapshot을 지우지 않는다.** 이전 검색이 참조하던 결과가 그대로 남아야
재현이 성립한다(spec FR-031, 원칙 VIII).

### 5.1 임시 대역이 만든 행의 표시와 폐기

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

## 6. 상태 전이

검색 상태는 **계산 시점에 정해져 저장되고 조회 시점에 다시 판정하지 않는다**(research R6).

```
                    척도 연속 구간 < 60개월 ─┐
                    MAD=0 이고 IQR=0      ─┼─▶  NOT_COMPUTABLE
                    유효 후보 0건          ─┘

                    그 밖 + 문턱 미교정     ───▶  NEAREST_ONLY        ← 현재 전부 여기
                    그 밖 + 문턱 교정됨     ───▶  THRESHOLD_APPLIED   ← PD-09 확정 후
```

- 분야 하나가 `NOT_COMPUTABLE`이면 그 분야를 포함하는 **종합도 `NOT_COMPUTABLE`**이다(US3 시나리오 6).
  6개 분야로 계산하지 않는다.
- 문턱이 교정돼 `THRESHOLD_APPLIED`가 생겨도 **이미 `NEAREST_ONLY`로 저장된 snapshot은 그대로 둔다**
  (spec Edge Cases). 새 모델 버전으로 다시 계산한 것이 새 행이 된다.

## 7. 값의 소유 경계

| 값 | 소유 | 010이 하는 일 |
|---|---|---|
| 지표 월별 값(원값·변환값) | 008 | 읽기만 |
| 지표 → 분야 매핑, 등록표 버전 | 008 | 읽기만 |
| T_g · T_all | 008 | 읽어서 `baseline_origin`에 기록 |
| 발표일 | 008 | 읽어서 `data_asof`에 복사 (표시용 스냅샷) |
| 척도 m·s, z, 거리, 기여도, 순위 | **010** | 계산·저장 |
| 검색 상태 | **010** | 계산·저장 |
| 사후 구간 시장·전략 결과 | 011 | 사례 식별자만 제공 |

`data_asof`가 008 값의 복사본인 것은 의도적이다 — 화면이 "이 결과가 언제 자료인가"를 말하려면
**계산 당시의 발표일**이어야 하고, 지금 008을 다시 읽으면 그 뒤에 개정된 값이 나온다.
