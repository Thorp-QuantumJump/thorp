# Phase 1 Data Model: 대표 ETF 자료 확보

**Date**: 2026-09-10 | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

spec의 Key Entities를 저장 가능한 모형으로 옮긴다. 검증 규칙은 모두 FR로 근거를 밝힌다.

## 저장 위치 배분

모든 서비스 읽기 경로는 PostgreSQL이다. Worker 컨테이너에 공유 볼륨이 없어 Parquet 파일을 읽을 수 없다(research.md 결정 8).

| 개체 | 저장 위치 | 이유 |
|---|---|---|
| 섹터, 대표 ETF, 섹터-ETF 대응표 | PostgreSQL | 화면이 목록을 조회한다. 수십 행 규모 |
| 자료 버전 | PostgreSQL | 화면과 결과가 버전 메타데이터를 참조한다 |
| 가격 자료, 기업행위 이벤트, 지수 자료, 자료 부족 구간, 정지 구간 | PostgreSQL | Worker가 백테스트마다 읽는다. 섹터 20개에 일봉 4,000행이면 8만 행 규모 |
| 원천 대조 기록 | PostgreSQL | 감사용이며 조회·집계가 필요하다 |
| 대표 상품 정보 | PostgreSQL (파생 값 포함) | 화면 표시용 묶음 |
| 정제 산출물 사본 | Parquet (`data/processed/`, 자산/연도 파티션) | 대량 재처리와 추후 Spark 도입 대비. **서비스가 읽지 않는다** |
| 원천 응답 원본 | 파일 (`data/raw/`) | 원천을 다시 부르지 않고 재정제하기 위해 보관 |
| 공통 평가 기간 산출값 | 저장하지 않음 | 후보 집합 조합이 지수적이다. 요청 시 산출 (research.md 결정 3) |

적재 방향은 `raw → processed → PostgreSQL` 한 방향뿐이다. 발행된 자료 버전은 어느 쪽도 고치지 않는다(FR-017).

## 관계형 개체

### sector — 섹터

| 필드 | 타입 | 제약 | 근거 |
|---|---|---|---|
| `id` | bigint | PK. 외부 노출 금지 | ADR-0002 D2 |
| `code` | varchar | UNIQUE NOT NULL. 외부 식별자 | |
| `name` | varchar | NOT NULL | FR-001 |
| `classification` | varchar | NOT NULL. `INDUSTRY` \| `THEME` | FR-001 업종/테마 구분 |
| `description` | text | NULL 허용 | Key Entities |

### representative_etf — 대표 ETF

| 필드 | 타입 | 제약 | 근거 |
|---|---|---|---|
| `id` | bigint | PK | |
| `instrument_id` | varchar | UNIQUE NOT NULL. `{6자리}.XKRX` 형식 | `engine_input.py:20`의 기존 패턴과 일치시킨다 |
| `name` | varchar | NOT NULL | FR-001 |
| `listed_on` | date | NOT NULL | FR-001 |
| `delisted_on` | date | NULL = 상장 유지. NOT NULL이면 `> listed_on` | FR-004 |
| `benchmark_index` | varchar | NOT NULL | FR-001 |
| `manager` | varchar | NOT NULL. 운용사 | FR-001 |
| `leveraged` | boolean | NOT NULL DEFAULT false. **true를 허용하지 않는다** | FR-001 |
| `inverse` | boolean | NOT NULL DEFAULT false. **true를 허용하지 않는다** | FR-001 |

**불변식**: `leveraged`와 `inverse`는 모두 false여야 한다. 컬럼으로 두는 이유는 대응표를 만들 때 원천에서 읽은 값을 확인하고 거부한 근거를 남기기 위함이다. 값이 true인 행은 저장 자체를 거부한다.

### sector_etf_map_version — 대응표 버전

| 필드 | 타입 | 제약 | 근거 |
|---|---|---|---|
| `id` | bigint | PK | |
| `version` | varchar | UNIQUE NOT NULL | FR-003 |
| `effective_from` | timestamptz | NOT NULL | Key Entities 유효 기간 |
| `effective_to` | timestamptz | NULL = 현재 버전 | |
| `is_sample` | boolean | NOT NULL. true면 예시 대응표 | FR-002 |
| `published_at` | timestamptz | NOT NULL | |

**불변식**: `effective_to`가 NULL인 행은 최대 하나다. 이미 발행된 버전의 내용은 변경하지 않는다(FR-017의 원칙을 대응표에도 적용).

### sector_etf_map_entry — 대응표 항목

| 필드 | 타입 | 제약 | 근거 |
|---|---|---|---|
| `id` | bigint | PK | |
| `map_version_id` | bigint | FK → `sector_etf_map_version` NOT NULL | |
| `sector_id` | bigint | FK → `sector` NOT NULL | |
| `etf_id` | bigint | FK → `representative_etf` NOT NULL | |

**불변식**: `(map_version_id, sector_id)`가 UNIQUE다. 한 버전 안에서 섹터마다 대표 ETF가 정확히 하나여야 한다(FR-001, SC-001). 같은 ETF가 여러 섹터에 붙는 것은 막지 않는다. 기획서가 금지하지 않았고, 구성종목 중복은 대표 상품 정보로 알린다(FR-015).

### dataset_version — 자료 버전

| 필드 | 타입 | 제약 | 근거 |
|---|---|---|---|
| `id` | bigint | PK | |
| `dataset_id` | varchar | UNIQUE NOT NULL. `KRX_DAILY_{YYYYMMDD}` | research.md 결정 4 |
| `data_deadline` | timestamptz | NOT NULL. 데이터 마감 시각 | FR-013 |
| `collected_at` | timestamptz | NOT NULL | FR-013 |
| `last_success_at` | timestamptz | NOT NULL. 마지막으로 성공한 갱신 시각 | FR-014 |
| `complete` | boolean | NOT NULL. false면 부분 갱신 | FR-014, 이용 한도 도달 시 |
| `failure_reason` | text | NULL 허용 | FR-014 |
| `is_sample` | boolean | NOT NULL. true면 예시 자료 | FR-002, research.md 결정 5 |
| `map_version_id` | bigint | FK → `sector_etf_map_version` NOT NULL | 이 자료가 어느 대응표를 대상으로 수집됐는지 |

**불변식**: 발행된 행은 수정하지 않는다(FR-017). 자료를 고쳐야 하면 새 `dataset_id`로 새 행을 만든다. `complete = false`인 버전을 완전한 자료로 표시하지 않는다.

### reconciliation_record — 원천 대조 기록

| 필드 | 타입 | 제약 | 근거 |
|---|---|---|---|
| `id` | bigint | PK | |
| `dataset_version_id` | bigint | FK → `dataset_version` NOT NULL | |
| `etf_id` | bigint | FK → `representative_etf` NOT NULL | |
| `target_date` | date | NOT NULL. 대조한 거래일 | FR-016 |
| `sample_reason` | varchar | NOT NULL. `LISTING` \| `DISTRIBUTION` \| `SPLIT` | FR-016 표본 구성 |
| `field_name` | varchar | NOT NULL. 대조한 항목 | |
| `queried_value` | text | NOT NULL. 조회값 | Key Entities |
| `disclosed_value` | text | NOT NULL. 공시값 | Key Entities |
| `difference` | text | NULL 허용 | Key Entities |
| `cause` | varchar | NOT NULL. `MATCH` \| `PIPELINE_ERROR` \| `DEFINITION_DIFF` \| `UNKNOWN` | FR-017 원인 분류 |
| `resolution` | varchar | NOT NULL. `NONE` \| `REPUBLISHED` \| `RECORDED_ONLY` \| `RANGE_EXCLUDED` | FR-017 처리 결과 |
| `checked_at` | timestamptz | NOT NULL | Key Entities |

**불변식**: `cause`와 `resolution`의 조합을 제한한다. `MATCH → NONE`, `PIPELINE_ERROR → REPUBLISHED`, `DEFINITION_DIFF → RECORDED_ONLY`, `UNKNOWN → RANGE_EXCLUDED`. 이 매핑이 FR-017의 처리 규칙이며, 다른 조합은 거부한다.

### instrument_profile — 대표 상품 정보

| 필드 | 타입 | 제약 | 근거 |
|---|---|---|---|
| `id` | bigint | PK | |
| `etf_id` | bigint | FK → `representative_etf` NOT NULL | |
| `dataset_version_id` | bigint | FK → `dataset_version` NOT NULL | 어느 자료 버전 기준인지 |
| `index_methodology_summary` | text | NOT NULL | FR-015 |
| `holdings_overlap` | jsonb | NOT NULL. 상위 종목과 비중 | FR-015 |
| `holdings_as_of_current` | boolean | NOT NULL. 현재 구성 기준임을 표시 | FR-015 |
| `avg_daily_value_3m` | text | NOT NULL. 최근 3개월 일평균 거래대금 | FR-015, clarify 4 |
| `source_attribution` | varchar | NOT NULL. 예: `한국거래소 통계정보` | FR-011 |
| `survivorship_note` | text | NOT NULL. 현재 생존 상품만 고른 한계 한 문장 | FR-015 |

**불변식**: `(etf_id, dataset_version_id)`가 UNIQUE다. `holdings_as_of_current`가 true면 화면은 "당시 구성을 재현했다"고 표현하지 않는다(FR-015). `avg_daily_value_3m`은 금액이므로 문자열로 둔다. `backend/CLAUDE.md`가 "decimal은 예외 없이 JSONB, `NUMERIC` 컬럼은 scale을 임의로 정해야 하고 넘는 값을 조용히 반올림한다"고 정한 규칙을 따른다. **추적 차이 필드는 두지 않는다** — 이번 범위에서 제외했다(Out of Scope).

## 시계열 개체

PostgreSQL 테이블이며, 같은 컬럼 구성이 `data/processed/`의 Parquet 사본에도 쓰인다. Parquet 쪽만 파티션(자산/연도)을 갖는다.

### daily_price — 가격 자료

| 컬럼 | 타입 | 뜻 | 근거 |
|---|---|---|---|
| `instrument_id` | varchar | | |
| `date` | date | | `data/CLAUDE.md` 컬럼 표준 |
| `open` `high` `low` `close` | numeric | 원시 가격. 체결용 | FR-004 |
| `volume` | bigint | 거래량(주식 수) | 컬럼 표준 |
| `value` | bigint | 거래대금(원) | research.md 결정 6 |
| `adjusted_close` | numeric | 기업행위 반영. 신호·성과용 | FR-004, research.md 결정 2 |
| `adjustment_factor` | numeric | 누적 조정 계수. 재현 검증용 | research.md 결정 2 |
| `price_carried_forward` | boolean | 직전 종가를 쓴 날 | FR-006, clarify 2 |
| `dataset_id` | varchar | 자료 버전 | FR-013 |

**불변식**: `(dataset_id, instrument_id, date)`가 PK다. 자료 버전이 키에 들어가므로 새 버전을 발행해도 옛 버전 행이 그대로 남는다(FR-017, SC-004).

가격 컬럼에 `numeric`을 쓰는 것은 `backend/CLAUDE.md`가 결과 저장에 적용한 판단("decimal은 예외 없이 JSONB")과 다르다. 그 규칙은 **표시용 값**을 대상으로 하며 임의 scale이 조용히 반올림하는 문제를 막기 위한 것이다. 여기 가격은 Worker가 계산에 쓰는 입력이고 국내 ETF 호가 단위가 정수 원 단위라 scale을 근거 있게 고정할 수 있다. 조정 가격은 계수 곱셈 결과이므로 소수 자리를 넉넉히 잡는다.

**검증 규칙** (`worker/src/worker/market_data.py:33`의 `validate_prices`를 재사용한다)

- 날짜는 유일하고 오름차순이어야 한다
- OHLC는 양수여야 하고 `high >= max(open, low, close)`, `low <= min(open, high, close)`
- `volume`은 음수가 아닌 정수
- 결측·비유한 값을 거부한다. 채우지 않는다 (FR-005, 원칙 VI)
- `date < listed_on`인 행은 존재할 수 없다 (SC-003)
- `delisted_on`이 있으면 `date > delisted_on`인 행은 존재할 수 없다 (Edge Case)

### corporate_action — 기업행위 이벤트

| 컬럼 | 타입 | 뜻 | 근거 |
|---|---|---|---|
| `instrument_id` | varchar | | |
| `date` | date | | Key Entities |
| `event_type` | varchar | `DISTRIBUTION` \| `SPLIT` | Key Entities |
| `amount` | numeric | 분배금은 주당 금액, 분할은 비율 | Key Entities |
| `dataset_id` | varchar | | FR-013 |

### daily_index — 지수 자료

가격 자료와 같은 컬럼을 쓰되 `adjusted_close`·`adjustment_factor`·`price_carried_forward`를 두지 않는다. 지수에는 기업행위가 없다. 키는 `instrument_id` 대신 `index_code`다. FR-009의 용도 제한(결과 보조 보기와 경제환경 참고 정보에만 사용, 검색 입력 금지)은 이 자료를 읽는 쪽이 지킨다.

### data_gap — 자료 부족 구간

| 컬럼 | 타입 | 뜻 | 근거 |
|---|---|---|---|
| `instrument_id` | varchar | | |
| `start_date` `end_date` | date | | |
| `reason` | varchar | `AMBIGUOUS_PRICE` \| `MISSING_CORPORATE_ACTION` \| `SUSPENDED_TOO_LONG` \| `RECONCILIATION_UNKNOWN` \| `INDEX_HISTORY_SHORT` | FR-006, FR-017 |
| `dataset_id` | varchar | | FR-013 |

이 구간은 공통 평가 기간 산출의 입력이 된다(research.md 결정 3).

`SUSPENDED_TOO_LONG`은 PD-19·PD-20이 확정된 뒤에만 쓴다. 확정 전에는 거래정지를 이 표에 넣지 않고 아래 `suspension_period`에만 기록한다.

### suspension_period — 거래정지 구간

PD-19·PD-20을 나중에 정하기 위해 근거를 모으는 표다(research.md 「PD-19·PD-20을 나중에 정하기 위한 준비」).

| 컬럼 | 타입 | 뜻 |
|---|---|---|
| `instrument_id` | varchar | |
| `start_date` `end_date` | date | 정지 시작일과 마지막 날 |
| `trading_days` | integer | 그 구간의 거래일 수 |
| `dataset_id` | varchar | 자료 버전 |

**이 표는 분석 범위를 줄이지 않는다.** 확정 전 임시 동작은 모든 정지를 직전 종가 유지로 처리하므로, 이 표는 기록과 표시에만 쓰인다. PD-19·PD-20 결정 작업이 이 표에서 길이 분포를 집계한다.

## 상태 전이

이 기능에 사용자가 진행시키는 상태 기계는 없다. 자료 버전의 수명만 다음과 같다.

```
수집 시작 → COLLECTING
  ├─ 전 범위 수집 완료 → 발행 (complete = true)
  └─ 이용 한도 도달 또는 실패 → 발행 (complete = false, failure_reason 기록)

발행된 버전은 수정되지 않는다. 자료를 고쳐야 하면 새 버전을 발행한다.
```

`COLLECTING` 상태는 저장소에 두지 않는다. 수집이 끝나야 행이 생기므로, 중간 상태가 화면에 보일 일이 없다.

## 개체 관계

```
sector ──┐
         ├─< sector_etf_map_entry >── representative_etf ──┬─< instrument_profile
         │            │                                     │
sector_etf_map_version ┘                                    └─< reconciliation_record
         │                                                            │
         └──< dataset_version ─────────────────────────────────────────┘
                     │
                     └──< daily_price / corporate_action / daily_index
                          / data_gap / suspension_period
```

시계열 표는 모두 `dataset_id`를 갖고 `dataset_version`을 외래 키로 참조한다. 파이프라인은 자료 버전 행을 먼저 만든 뒤 시계열을 적재한다.

`data/processed/`의 Parquet 사본도 같은 `dataset_id`를 컬럼으로 갖는다. 외래 키를 걸 수 없으므로 대조가 필요할 때 그 문자열로 맞춘다. 서비스는 Parquet을 읽지 않으므로 이 연결은 재처리와 검증에만 쓰인다.
