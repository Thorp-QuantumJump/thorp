# Data Model: 경제지표 등록·수집

**기준**: [spec.md](spec.md) FR-001~023. 아래 이름은 구현 설계안이며 Flyway 적용 시 실제 명명·제약을 계약 테스트로 고정한다. `macro_*`는 기존 ETF `dataset_version` 및 009 화면 요약 테이블과 독립이다.

## 1. 식별자와 시간

- `indicator_id`: 17개 지표의 안정적인 영문 snake_case ID. 이름·원천 코드는 바뀔 수 있어 PK로 사용하지 않는다. 009의 기존 예시 ID는 연동 시 대조하고, PD-11의 실제 항목 코드와 혼동하지 않는다.
- `field_id`: 009 계약과 같은 `production`, `consumption_investment`, `external_trade`, `labor`, `prices_commodities`, `finance_housing`, `overseas`. 010의 대문자 예시는 경계에서 정규화한다.
- `target_month`: ISO `YYYY-MM` (저장 시 매월 1일 `date` 또는 연·월 쌍 중 하나로 고정). `released_at`(원천 발표 시각/날짜), `received_at`(수신 시각), `source_revision_key`(원천 제공 시), `observed_revision`(자체 수신 순번)을 별개로 둔다. 원천에 발표일/개정 ID가 없으면 null 및 근거 상태로 기록하며 수신시각을 발표일로 위조하지 않는다.
- 숫자는 변환 전후 모두 고정밀 decimal로 보존하고 표시 반올림은 공표 정밀도에 따른다. 소수 자릿수/범위는 PD-11 실응답 확인 뒤 정한다. 결측은 값 null과 사유 코드로 기록하며 0과 구별한다.

## 2. 엔터티

| 엔터티 (예상 테이블) | 핵심 필드 | 제약/책임 |
|---|---|---|
| `macro_calculation_registry_version` | `id`, `created_at`, `value_rule_fingerprint`, `change_reason`, `status` | 발행 후 불변. 값 규칙과 매핑·명목 가중을 포함한 계산 등록표 버전 |
| `macro_indicator_definition` | `(registry_version_id, indicator_id)`, `field_id`, `primary_source`, `table_code`, `item_code`, `raw_unit`, `transformed_unit`, `transform_rule`, `release_rule`, `break_link_rule`, `missing_rule`, `agency`, `attribution`, `license_status`, `published_precision`, `minimum_effective_scale`, `refresh_months_n`, `nominal_weight` | 버전마다 정확히 17개, 각 지표는 정확히 한 분야. 미확인 PD-10/11은 값 대신 상태/근거로 구분; 공개 실자료 버전에는 필수 PD-11 검증 완료 |
| `macro_display_version` / `macro_display_field` | `id`, `field_id`, `display_name`, `display_order`, `description`; 지표 표시명/순서 | 버전마다 7개 분야와 17개 지표 표시. 계산 규칙·자료에 영향 없음 |
| `macro_collection_attempt` / `macro_collection_item_status` | `attempt_id`, 시작/종료 시각, 원천/지표, `status`, `last_success_at`, `last_error_at`, `error_class`, quota 상태, raw 위치 | mutable 운영 상태. 공개 버전과 별도. 원본은 일부 성공해도 보존 |
| `macro_raw_revision` | `indicator_id`, `source`, `target_month`, 원본 값/단위, `released_at`, `received_at`, `source_revision_key`, raw 경로·해시, 순번 | append-only 원천별·지표별 이력. 동일 응답 재수집은 해시/원천 키로 중복 제거하되 실제 개정은 새 행. 일별 원천은 월별 변환에 기여한 여러 행을 보존 |
| `macro_dataset_version` | `id`, `processing_registry_version_id`, `value_rule_fingerprint`, `published_at`, `complete_indicator_count=17`, `is_sample_data`, 봉인된 raw/processed manifest 해시 | 발행 후 불변. 처리 당시 등록표를 provenance로 기록하되, 같은 fingerprint의 이후 계산 등록표와도 결합 가능. 전체 가용 구간의 17개 스냅샷; 정기 수집의 최근 `N`월만 담지 않음 |
| `macro_observation` / `macro_observation_lineage` | `(dataset_version_id, indicator_id, target_month)`, `raw_value`, `transformed_value`, `released_at`, `received_at`, `break_segment`, `missing_reason`; 기여 원본 revision/processed manifest의 다대다 연결 | 버전·지표·월 당 최대 1개. 월 기말/평균·전년동월 대비의 입력 월 또는 일별 표본 계보를 남긴다. 변환 가능 월만 기준월·발행 게이트에 사용. 원값만 있는 월은 보존하지만 유효 건수 0에 포함 |
| `macro_baseline` | `(dataset_version_id, registry_version_id, scope_id)`, `reference_month`, `reference_month_held_back_by`, 산출 시각 | `scope_id`는 7개 분야 ID 또는 `ALL`인 NOT NULL 키. 7개 `T_g`와 1개 `T_all`을 담고 교집합이 비면 `reference_month`만 null. 분야별 held-back 지표 ID도 고정한다. 매핑만 바뀐 새 계산 등록표와 동일 자료 버전의 조합도 별도 저장 |
| `macro_current_publication` | singleton key, `dataset_version_id`(첫 발행 전 null), `registry_version_id`, `display_version_id`, `switched_at` | 포인터 교체는 발행 트랜잭션 마지막. 독자는 한 트랜잭션 스냅샷/한 SQL query에서 이 세 ID를 먼저 고정 |

`minimum_effective_scale`은 PD-10 확정 전 null이고, 계산은 010의 임시 MAD→IQR→계산 불가 정책에 맡긴다. `source_revision_key`를 원천이 주지 않으면 `(원천, 지표, 월, 원본 값/메타 해시, 수신 순번)`으로 이력을 구별한다. 발표일 미제공 시 `released_at`을 수신 시각으로 대체하지 않는다.

## 3. 관계와 불변식

```text
calculation_registry_version 1─17 indicator_definition ─1 field_id
display_version              1─7 display_field (+ 17 indicator labels)
collection_attempt           1─N collection_item_status ─N raw_revision
dataset_version              1─N observation ─N lineage ─N raw_revision/processed_manifest
dataset_version + calculation_registry_version ─8 baseline
current_publication          ─1 dataset_version + calculation_registry_version + display_version
```

1. 값 규칙 fingerprint가 다른 계산 등록표와 자료 버전은 current 포인터에서 결합하지 않는다. `processing_registry_version_id`는 처리 당시의 provenance이지 유일한 호환 등록표가 아니다. 매핑·가중만 바뀌면 fingerprint는 유지하고 새 `(dataset, registry)` 기준월 조합을 재산출한다. 표시 버전 변경은 관측값/계산 등록표/자료 버전을 변경하지 않는다. 이 결합을 DB/통합 테스트로 검증한다.
2. 새 자료 버전 후보는 17개 지표 전부의 수집·정규화가 완료되고 지표마다 사용 가능한 월별 **변환값이 ≥1건**이어야 한다. 이는 행별 DB `CHECK`만으로 보장되지 않으므로 발행 서비스의 검증 + 트랜잭션/통합 테스트(필요하면 deferred trigger)로 강제한다.
3. `T_g = max(∩ 해당 분야 지표의 유효 변환값 대상월 집합)`, `T_all = max(∩ 17개 지표 집합)`. 빈 교집합은 null. 원값만 있는 월, 개편 연결 규칙이 없어 제외한 월, 결측 월은 집합에 넣지 않는다. `reference_month_held_back_by`는 그 분야 지표별 최신 유효월 중 최대인 후보 월에 유효 변환값이 없는 지표 ID다. 후보 월이 곧 `T_g`이면 빈 배열이고 `T_g=null`이어도 후보 월이 있으면 부족 지표를 기록한다.
4. 불변 스냅샷 생성 중 기존 버전의 행을 update/delete하지 않는다. raw/processed 파일을 먼저 완성·봉인하고 해시 manifest를 기록한 뒤 DB 발행을 시작한다. DB가 rollback되면 미참조 파일은 공개되지 않고 재시도/정리 대상으로 남긴다. 공개 스냅샷이 참조하는 파일은 보존한다. 같은 입력+규칙에서 같은 월별 값/기준월을 재현하며, 공개 후 원천 개정을 발견하면 17개 전체 새 버전을 발행한다.
5. 현재 포인터를 통한 단일 읽기에서 지표/기준월/버전이 섞이지 않는다. 수집 시도 상태는 그 시점의 운영 상태라 공개 자료 버전보다 새 실패를 나타낼 수 있으며 응답에서 자료 상태와 수집 상태를 구분한다.

## 4. 발행 상태 전이

```text
COLLECTING → NORMALIZING → VALIDATING → PUBLISHING → PUBLISHED
     │             │            │              │
     └─────────────┴────────────┴──────────────┴──▶ FAILED / HELD
```

- `FAILED`: 원천·인증·한도·정규화 오류. 성공한 raw는 남기고 현재 포인터 유지.
- `HELD`: 예정 처리는 끝났지만 17개 중 변환 유효값 0건 등 공개 조건 미충족. 현재 포인터 유지. `T_all=null` 자체는 HELD 조건이 아니다.
- `PUBLISHING`: 봉인된 raw/Parquet manifest를 참조해 새 완결 스냅샷 전체와 8개 기준월을 쓰고 fingerprint/건수를 검증한 뒤 같은 DB 트랜잭션에서 current 포인터를 교체한다. 파일 쓰기는 이 트랜잭션 밖이므로 포인터 이전 장애 복구와 미참조 파일 정리를 별도로 검증한다. 잠금/낙관적 버전으로 두 수집 작업의 동시 교체를 직렬화한다.
- `PUBLISHED`: 이전 스냅샷은 보존해 010 기존 결과의 참조가 변하지 않는다. 원본/Parquet 수명·저장 비용 정책은 구현 시 보관 요건과 원천 이용 조건에 맞춰 정한다.

## 5. 소비자 투영

- **008 REST**: current 포인터의 세 버전을 고정해 17개 지표·7개 분야·`T_g/T_all`·지표별 최신월/값·출처/작성기관을 한 응답으로 조립한다. 009 변화 요약용 경로는 분야 `T_g`에, 지표 자체의 최근 3개월 경로는 그 지표의 최신 유효월에 각각 고정한다. `T_g=null`이어도 지표별 경로는 제공한다. 첫 공개 전에는 등록표/표시 버전만 사용하고 자료 버전과 모든 값/기준월을 null, 경로를 빈 배열로 보낸다. 수집 실패는 별도 상태로 보낸다.
- **009**: 이 투영만 소비한다. 화면 전용 두 API의 서술/검색 링크는 009 소유이며 검색 링크는 010의 실제 완료월을 참조한다. 009의 `market_flow_field_summary`는 008의 정규화 시계열 저장소가 아니다.
- **010 `data/`**: 선택한 자료 버전·계산 등록표 버전의 전 구간 원값/변환값, 발표·개정 메타, `changed_since`를 사용한다. 기존 계산 결과는 고정된 버전 키를 보유한다. Backend의 010 표시용 등록표 포트는 008 등록표 조회를 사용하며 시계열 계산을 하지 않는다.
