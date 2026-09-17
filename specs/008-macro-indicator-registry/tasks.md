---

description: "Task list for Spec 008 경제지표 등록·수집"
---

# Tasks: 경제지표 등록·수집 (Spec 008)

**Input**: `specs/008-macro-indicator-registry/`의 설계 문서

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [pd11-source-verification.md](pd11-source-verification.md)

**Tests**: 테스트 작업을 포함한다. research R8과 [quickstart.md](quickstart.md)가 계약·통합 검증을 요구하고, constitution "검증과 완료 기준"이 이를 완료 조건으로 둔다.

**Organization**: 사용자 스토리별로 묶어 각 스토리를 따로 구현하고 따로 검증할 수 있게 한다.

## Format: `[ID] [P?] [Story] 설명`

- **[P]**: 병렬 가능 (다른 파일, 선행 작업 없음)
- **[Story]**: 속한 사용자 스토리 (US1~US4)
- 설명에 정확한 파일 경로를 적는다

## Path Conventions

plan.md의 구조를 따른다. 수집·계산은 `data/`, 조회는 `backend/`다.

- `data/src/data_pipeline/macro/` 원천 어댑터·정규화·변환·발행
- `data/scripts/` 실행 진입점 (argparse)
- `data/tests/` 파이썬 테스트
- `backend/src/main/java/com/ssafy/thorp/macro/` 008 조회
- `backend/src/main/resources/db/migration/` Flyway
- `backend/src/test/` 백엔드 테스트

**검증 명령**: `data/`는 `ruff check src scripts tests`와 `pytest`, `backend/`는 `./gradlew spotlessApply build`. DB 통합 테스트는 `THORP_REQUIRE_DOCKER=true`로 실행하고 skip 건수를 확인한다.

## Existing Seams

008을 기다리며 이미 들어와 있는 코드다. 009·010이 임시 대역을 먼저 넣고 008이 오기를 기다리는 구조라, 이 표가 비면 교체 작업이 통째로 빠진다.

| 자리 (파일·심볼) | 지금 상태 | 008이 할 일 | 담당 작업 |
|---|---|---|---|
| `regime/application/port/MacroIndicatorPort` | 등록표 조회 포트. 구현체 없음 | 구현 | T070 |
| `regime/infrastructure/macro/PendingMacroIndicatorAdapter` | 부르면 실패하는 자리지킴 | 교체 | T070 |
| `data_pipeline.regime.macro.postgres` | **아직 없는 모듈.** `regime/macro/__init__.py`의 팩터리가 이 경로로 찾는다 | 구현 (`create_source()` 하나) | T068 |
| `marketflow/infrastructure/example/ExampleMacroIndicatorSnapshotAdapter` | 예시 자료 어댑터 | 008 스냅샷 조회로 교체 | T064 |
| `thorp.regime.macro.source: fixture` (`application.yml`·`application-local.yml`) | 공통 기본이 임시 대역 | `macro`로 전환 | T071 |
| `regime/infrastructure/macro/fixture/`, `data_pipeline/regime/macro/fixture/`, `marketflow/macro-indicators-example-v1.json` | 픽스처·예시 자료 | 폐기 | T071 |

`regime_baseline_month`는 010이 소유하며 008이 건드리지 않는다 — 검색 가능월은 008의 `T_g`가 아니라 010 배치 결과다(FR-013).

---

## Phase 1: Setup (공용 기반)

**Purpose**: 패키지 뼈대와 접속 정보를 준비한다

- [X] T001 `data/src/data_pipeline/macro/` 패키지를 만들고 `__init__.py`와 하위 모듈 자리(`sources/`, `transform/`, `publish.py`)를 둔다
- [X] T002 `data/.env.example`에 `ECOS_API_KEY`·`KOSIS_API_KEY`·`DATA_GO_KR_SERVICE_KEY` 이름을 추가한다. 키 값은 적지 않는다
- [X] T003 [P] `data/src/data_pipeline/macro/config.py`에 원천별 기본 URL·타임아웃·재시도 설정을 환경변수에서 읽는 구조로 둔다. 재시도 출발값은 최대 3회, 간격 1초에서 2배씩(1·2·4초)이고 원천별로 덮어쓸 수 있다. 실측 뒤 조정한다
- [X] T004 [P] `data/tests/fixtures/macro/`에 원천별 응답 표본을 둘 자리를 만들고 실제 키가 없어도 테스트가 돌도록 한다
- [X] T005 [P] `backend/src/main/java/com/ssafy/thorp/macro/` 패키지를 presentation·application·domain·infrastructure 계층으로 만들고 `package-info.java`에 계층과 소비자를 적는다. 계층 이름은 ADR-0001과 기존 12개 모듈을 따른다

---

## Phase 2: Foundational (모든 스토리의 선행 조건)

**Purpose**: 등록표와 저장소가 없으면 어느 스토리도 시작할 수 없다

**이 단계를 마쳐야 사용자 스토리에 들어갈 수 있다**

- [ ] T006 `backend/src/main/resources/db/migration/`에 Flyway 마이그레이션으로 `macro_calculation_registry_version`·`macro_indicator_definition` 테이블을 만든다. 컬럼은 data-model.md 2절을 따르고 `license_review_status`·`license_verified_at`·`license_suspended_at`·`license_suspended_reason`·`revision_history_available`·`trading_calendar`·`refresh_months_n`을 포함한다. 뒤의 권리 수명주기 세 컬럼은 T055·T057이 쓴다
- [ ] T007 `backend/src/main/resources/db/migration/`의 같은 마이그레이션 묶음에 `macro_display_version`·`macro_display_field`를 만든다. 계산 등록표와 별도 버전이다
- [ ] T008 `backend/src/main/resources/db/migration/`의 같은 마이그레이션 묶음에 `macro_collection_attempt`·`macro_collection_item_status`를 만든다. 인증키 미발급·승인 대기·권리 확인 대기·미확인을 구분하는 상태 컬럼을 둔다
- [ ] T009 `backend/src/main/resources/db/migration/`의 같은 마이그레이션 묶음에 `macro_raw_revision`·`macro_observation`·`macro_observation_lineage`를 만든다. 원값과 변환값을 모두 보존하고 결측 사유 코드를 둔다
- [ ] T010 `backend/src/main/resources/db/migration/`의 같은 마이그레이션 묶음에 `macro_dataset_version`·`macro_baseline`·`macro_current_publication`을 만든다. `macro_baseline`의 `scope_id`는 7개 분야 또는 ALL이다
- [ ] T011 `data/src/data_pipeline/macro/registry.py`에 등록표 적재기를 만든다. pd11-source-verification.md의 17개 지표 확정값(원천·통계표·항목·단위·작성기관·확보 구간)을 읽어 계산 등록표 버전 1을 발행한다. 한국은행과 국가통계포털이 겹치는 지표는 주 공급원을 하나만 적고 나머지는 등록하지 않는다 (FR-005)
- [ ] T012 `data/src/data_pipeline/macro/registry.py`의 등록표에 지표별 `refresh_months_n` 기본값 3개월을 잠정으로 채운다. 관세청 세 지표는 원천이 밝힌 매월 정정 주기보다 짧게 잡지 않는다 (FR-017)
- [ ] T013 [P] `data/src/data_pipeline/macro/sources/base.py`에 원천 어댑터 공통 규약을 둔다. 재시도·포기·요청 분할·응답 구조 검증을 여기서 다룬다 (FR-011b)
- [ ] T014 [P] `data/src/data_pipeline/macro/storage.py`에 raw에서 processed Parquet으로 가는 경로 규칙을 지표/연도 파티션으로 둔다. `data/CLAUDE.md`의 한 방향 적재 규칙을 지킨다
- [ ] T015 [P] `data/tests/test_macro_registry.py`에 등록표 적재 테스트를 둔다. 17개가 정확히 등록되고 각 지표가 7개 분야 중 하나에 속하는지, 지표마다 주 공급원이 정확히 하나인지 확인한다 (SC-001, FR-005)
- [ ] T016 [P] `backend/src/test/java/com/ssafy/thorp/macro/MacroSchemaIntegrationTest.java`에 스키마 통합 테스트를 둔다. Testcontainers로 PostgreSQL을 띄워 마이그레이션이 적용되는지 확인한다
- [ ] T017 `data/scripts/ingest_macro.py`에 실행 진입점을 argparse로 만든다. `--mode initial|regular`, `--indicator`, `--dry-run`을 받는다

**Checkpoint**: 등록표와 저장소가 준비되어 원천 수집을 붙일 수 있다

---

## Phase 3: User Story 1 - 공급원을 고르지 않고 실제 값·단위·출처가 있는 지표를 본다 (Priority: P1) 🎯 MVP

**Goal**: 17개 지표를 공식 원천에서 받아 변환하고, 값·단위·대상월·출처·작성기관과 함께 조회할 수 있게 한다

**Independent Test**: 17개 지표 각각의 최신 확보값을 조회해 값·단위·대상월·변환·출처·작성기관이 모두 채워져 있고, 어느 응답에도 공급원 선택 항목이나 인증키 요구가 없는지 확인한다. 화면 없이 등록표와 자료만으로 검증된다

### Tests for User Story 1

- [ ] T018 [P] [US1] `data/tests/test_macro_sources_ecos.py`에 한국은행 어댑터 계약 테스트를 둔다. 저장된 응답 표본으로 국고채·환율·두바이유 세 계열의 파싱을 확인한다
- [ ] T019 [P] [US1] `data/tests/test_macro_sources_kosis.py`에 국가통계포털 어댑터 계약 테스트를 둔다. 10개 표의 항목·분류 코드 파싱을 확인한다
- [ ] T020 [P] [US1] `data/tests/test_macro_sources_customs.py`에 관세청 어댑터 테스트를 둔다. 연 단위 분할과 총계 행 제외를 확인한다 (FR-017a)
- [ ] T021 [P] [US1] `data/tests/test_macro_transform.py`에 변환 테스트를 둔다. 전년동월 대비·월 기말·월 평균·공식 금액 네 유형과 분모 결측·0, 계열 시작 구간, 휴장일, 부분 표본 경계를 다룬다 (FR-006, FR-006a, FR-006b)

### Implementation for User Story 1

- [ ] T022 [P] [US1] `data/src/data_pipeline/macro/sources/ecos.py`에 한국은행 어댑터를 만든다. 국고채는 통계표 817Y002 항목 010200000 일별, 환율은 731Y004 항목 0000001에 항목2 0000200 말일자료, 두바이유는 902Y003 항목 010102 월별이다
- [ ] T023 [P] [US1] `data/src/data_pipeline/macro/sources/kosis.py`에 국가통계포털 어댑터를 만든다. 10개 표의 기관·통계표·항목·분류는 pd11-source-verification.md 2절 표를 따른다
- [ ] T024 [P] [US1] `data/src/data_pipeline/macro/sources/customs.py`에 관세청 어댑터를 만든다. 기준 주소는 1220000/Newtrade이고 무역수지는 `balPayments`를 그대로 쓰며 수출에서 수입을 빼지 않는다
- [ ] T025 [P] [US1] `data/src/data_pipeline/macro/sources/oecd.py`에 경제협력개발기구 어댑터를 만든다. DSD_STES@DF_CLI의 G20.M.LI 중 AA/IX 지수를 받고 증가율은 직접 계산한다 (FR-019)
- [ ] T026 [US1] `data/src/data_pipeline/macro/sources/housing.py`에 주택가격 연결기를 만든다. 기준월별 표 5개를 받아 겹치는 구간의 비율로 이어 2000년 1월까지 만들고 연결 지점과 계수를 남긴다 (FR-010)
- [ ] T027 [US1] `data/src/data_pipeline/macro/transform/monthly.py`에 변환기를 만든다. 전년동월 대비는 비교월을 t-12로 고정하고, 분모가 결측이거나 0이면 결측으로 두고 사유를 변환 불가로 남긴다
- [ ] T028 [US1] `data/src/data_pipeline/macro/transform/monthly.py`에 월 기말과 월 평균을 넣는다. 월 기말은 그 달 마지막 거래일, 월 평균은 그 달 거래일이 모두 모였을 때만 산출한다. 원천이 월값을 직접 주면 그 값을 쓰고 등록표에 그 사실을 적는다
- [ ] T029 [US1] `data/src/data_pipeline/macro/normalize.py`에 원값·변환값 동시 보존과 계보 기록을 넣는다. 월 기말은 마지막 거래일 행을, 월 평균은 그 달 거래일 전부를 계보로 남긴다
- [ ] T030 [US1] `data/src/data_pipeline/macro/publish.py`에 발행기를 만든다. 17개 전부의 유효 변환값이 1건 이상이고 권리 상태가 미확인이 아닐 때만 스냅샷을 발행한다 (FR-011, FR-016a)
- [ ] T031 [US1] `data/src/data_pipeline/macro/publish.py`에 분야별 T_g와 전체 T_all 산출을 넣는다. 유효 변환값 대상월 집합의 교집합에서 최대월을 고르고 빈 교집합은 자료 없음으로 둔다 (FR-013)
- [ ] T032 [US1] `backend/src/main/java/com/ssafy/thorp/macro/`에 단일 스냅샷 조회를 만든다. 응답은 contracts/openapi.yaml을 따르고 현재 포인터의 세 버전을 먼저 고정한 뒤 조립한다. 자료 성격을 '최신 개정자료를 이용한 역사 비교'로 응답에 실어 당시 자료로 오인되지 않게 한다 (FR-023, FR-012)
- [ ] T033 [US1] `backend/src/main/java/com/ssafy/thorp/macro/`의 조회 응답에 지표별 출처와 작성기관을 싣는다. 두바이유의 작성기관은 국제통화기금, 주택가격은 한국부동산원처럼 제공기관과 다를 수 있다 (FR-016, FR-016c)
- [ ] T034 [US1] `backend/src/test/java/com/ssafy/thorp/macro/MacroSnapshotContractTest.java`에 조회 계약 테스트를 둔다. 17개 등록 정보가 모두 오고 값·단위·대상월·변환·출처·작성기관이 빈 지표가 0개인지 확인한다 (SC-008)
- [ ] T035 [US1] `data/tests/test_macro_publish.py`에 발행 테스트를 둔다. 17개 중 하나라도 유효 변환값이 0건이면 발행이 보류되는지 확인한다

**Checkpoint**: 17개 지표를 받아 변환하고 한 응답으로 조회할 수 있다. 여기까지가 최소 완성본이다

---

## Phase 4: User Story 2 - 등록표가 버전으로 관리되어 계산이 화면 변경에 흔들리지 않는다 (Priority: P1)

**Goal**: 계산 등록표·화면 분류·자료 버전을 따로 올리고, 같은 버전 조합이면 같은 결과가 나오게 한다

**Independent Test**: 화면 분야 이름을 바꾼 뒤 계산 등록표와 자료 버전이 그대로인지, 변환 규칙을 바꾸면 새 자료 버전이 생기고 이전 검색 결과의 참조 버전은 유지되는지 확인한다

- [ ] T036 [P] [US2] `data/src/data_pipeline/macro/versioning.py`에 값 규칙 지문 산출을 만든다. 원천·항목·단위·변환·개편 연결·결측 규칙을 재료로 삼는다 (research R3)
- [ ] T037 [US2] `data/src/data_pipeline/macro/versioning.py`에 세 버전의 변경 경로를 나눈다. 화면 문구만 바뀌면 표시 버전, 매핑·가중만 바뀌면 계산 등록표 버전, 값 규칙이 바뀌면 계산 등록표와 전 구간 재처리 자료 버전을 함께 올린다 (FR-003)
- [ ] T038 [US2] `data/src/data_pipeline/macro/publish.py`에 원자적 발행을 넣는다. 파일 매니페스트를 먼저 봉인·해시하고 한 트랜잭션에서 스냅샷·기준월을 쓴 뒤 현재 포인터를 교체한다 (research R4)
- [ ] T039 [US2] `data/src/data_pipeline/macro/publish.py`에 동시 발행 직렬화를 넣는다. 두 수집 작업이 겹치면 하나만 반영되고 밀린 쪽은 실패로 기록된다 (FR-011a)
- [ ] T040 [US2] `data/src/data_pipeline/macro/registry.py`에 분야 내부 동일가중과 분야 간 7분의 1 동일가중을 명목 비중으로 등록하고, 이것이 Thorp의 설계 선택임을 함께 기록한다 (FR-004)
- [ ] T041 [P] [US2] `data/tests/test_macro_versioning.py`에 버전 독립 테스트를 둔다. 세 버전을 각각 바꿔 보고 나머지가 그대로인지 확인한다 (SC-005)
- [ ] T042 [P] [US2] `data/tests/test_macro_publish_atomic.py`에 원자성 테스트를 둔다. 포인터 교체 전 장애를 주입해 공개 버전이 섞이지 않는지, 미참조 파일이 공개되지 않는지 확인한다
- [ ] T043 [US2] `backend/src/test/java/com/ssafy/thorp/macro/MacroReproducibilityTest.java`에 재현성 테스트를 둔다. 같은 자료·등록표 버전으로 다시 조회하면 값과 기준월이 같은지 확인한다 (SC-004)

**Checkpoint**: 버전이 독립적으로 움직이고 같은 조합이면 같은 결과가 나온다

---

## Phase 5: User Story 3 - 자료 지연·개정·결측이 숨겨지지 않는다 (Priority: P2)

**Goal**: 대상월·발표일·수신 시각을 구분하고, 결측과 개정과 수집 실패를 그대로 드러낸다

**Independent Test**: 어느 지표의 수집을 실패시켜 마지막 갱신 시각이 기록되는지, 결측월이 빈 값으로 남고 그 분야 기준월이 물러나는지 확인한다

- [ ] T044 [P] [US3] `data/src/data_pipeline/macro/normalize.py`에 대상월·발표일·수신 시각 분리를 넣는다. 발표일을 주지 않는 원천은 미제공으로 남기고 수신 시각으로 대체하지 않는다 (FR-007)
- [ ] T045 [US3] `data/src/data_pipeline/macro/sources/kosis.py`에서 `LST_CHN_DE`를 통계표 단위 갱신일로 기록한다. 관측치별 발표일로 쓰지 않는다
- [ ] T046 [US3] `data/src/data_pipeline/macro/sources/base.py`에 재시도와 포기를 넣는다. T003의 재시도 설정(기본 최대 3회, 1·2·4초)대로 시도하고, 인증 거부나 없는 경로는 재시도하지 않는다 (FR-011b)
- [ ] T047 [US3] `data/src/data_pipeline/macro/sources/base.py`에 원천 변경 감지를 넣는다. 응답 구조나 식별자가 등록표와 다르면 실패로 기록하고 그 지표의 자동 수집을 멈춘다. 실패 원인을 인증 문제로 단정하지 않는다
- [ ] T048 [US3] `data/src/data_pipeline/macro/ingest.py`에 재실행 안전성을 넣는다. 중간에 끊긴 뒤 다시 돌리면 중복 없이 이어받고, 같은 응답은 새 이력을 만들지 않으며 다르면 개정으로 기록한다
- [ ] T049 [US3] `data/src/data_pipeline/macro/ingest.py`에 전 구간 적재 완료 판정을 넣는다. 등록표의 기대 시작월과 기대 대상월 수에 미치지 못하면 완료로 보지 않는다
- [ ] T050 [US3] `backend/src/main/java/com/ssafy/thorp/macro/`의 조회 응답에 마지막 갱신 시각·수집 성공/실패/자료 없음 상태와 기준월을 물린 지표를 싣는다 (FR-023)
- [ ] T051 [P] [US3] `data/tests/test_macro_failure.py`에 실패 처리 테스트를 둔다. 부분 실패·한도 도달·원천 구조 변경 각각에서 이전 완결 버전이 유지되는지 확인한다. **연 단위로 나눠 호출하는 도중 한도에 걸린 경우를 따로 다룬다** — 관세청은 전 구간을 37회 넘게 나눠 받으므로 중간에 끊길 수 있다. 이때 이미 받은 연도의 원본은 보존되고, 새 완결 버전은 발행되지 않으며, 다시 실행하면 못 받은 연도부터 이어받는지 확인한다 (FR-011, FR-011b, FR-017a)
- [ ] T052 [P] [US3] `data/tests/test_macro_revision.py`에 개정 테스트를 둔다. 과거 값이 바뀌면 17개 전체로 새 불변 버전이 나오고 이전 버전 값은 그대로인지 확인한다 (FR-009)

**Checkpoint**: 지연·실패·결측·개정이 값에 섞이지 않고 상태로 드러난다

---

## Phase 6: User Story 4 - 이용 조건 안에서 수집한다 (Priority: P2)

**Goal**: 원천별 한도와 권리 조건을 지키고, 개발용 예시 자료가 운영에 새지 않게 한다

**Independent Test**: 인증키 없는 개발 환경에서 예시 자료로 지표가 제공되고 예시 표시가 붙는지, 운영에서는 자동 대체가 일어나지 않는지 확인한다

- [ ] T053 [P] [US4] `data/src/data_pipeline/macro/registry.py`에 지표별 이용 조건을 기록한다. 작성기관·출처 표기·상업 이용 가부·재배포 제한·권리 판정 상태를 넣는다. 내보내기 경로가 함께 실어야 할 이용 범위 문구도 지표별로 둔다 — 006이 파일로 내보낼 때 쓴다 (FR-016, FR-016c, FR-018)
- [ ] T054 [US4] `data/src/data_pipeline/macro/registry.py`에서 두바이유를 권리 확인 대기로 표시한다. 조건 자체를 모르는 미확인과 구분되며 발행을 보류하지 않는다 (FR-016a)
- [ ] T055 [US4] `data/src/data_pipeline/macro/registry.py`와 `publish.py`에 이용 조건 철회 처리를 넣는다. 지표의 권리 상태가 중단이 되면 그 지표의 새 수집과 새 자료 버전 편입을 멈추고 중단 사유·시점을 등록표에 남긴다. 이미 발행한 자료 버전은 지우지 않는다 (FR-016b)
- [ ] T056 [US4] `backend/src/main/java/com/ssafy/thorp/macro/`의 조회 응답에서 중단된 지표를 값 목록에서 빼고 중단 상태와 사유를 싣는다. 조용히 사라지지 않고 빠진 상태임이 드러난다 (FR-016b, 원칙 VI)
- [ ] T057 [US4] `data/src/data_pipeline/macro/registry.py`에 이용 조건·인증 자격 재확인 시점을 둔다. 인증키 이용기간 만료 전(한국은행은 발급일로부터 2년)과 원천의 약관·서비스 명세 개정 시 재확인하고 확인 일시를 등록표에 남긴다. 조건이 달라졌으면 T055의 중단 처리로 넘긴다 (FR-018a)
- [ ] T058 [US4] `data/src/data_pipeline/macro/sources/base.py`에 원천별 한도 준수를 넣는다. 국가통계포털은 분당 200회·요청당 40,000셀, 관세청은 조회 기간 1년, 한국은행과 경제협력개발기구는 수치 한도 없이 보수적 간격이다 (FR-017a)
- [ ] T059 [US4] `data/scripts/ingest_macro.py`에 개발 프로필을 분리한다. 예시 자료는 명시적으로 켠 개발 환경에서만 쓰이고 운영 경로에서는 선택되지 않는다 (FR-022)
- [ ] T060 [US4] `backend/src/main/java/com/ssafy/thorp/macro/`의 조회 응답에 예시 자료 여부를 싣고, 운영에서 수집 실패를 예시 자료로 자동 대체하지 않는다
- [ ] T061 [US4] `data/src/data_pipeline/macro/config.py`에서 인증키를 환경변수로만 읽고 로그·응답·오류 메시지에 남기지 않도록 한다 (FR-021)
- [ ] T062 [P] [US4] `data/tests/test_macro_compliance.py`에 이용 조건 테스트를 둔다. 한도 초과 요청이 만들어지지 않는지, 권리 미확인 지표가 공개 버전에 들어가지 않는지 확인한다. 중단된 지표가 공개 버전과 조회 응답에 남지 않는지, 인증키 만기 임박과 약관 개정이 재확인 대상으로 잡히는지도 확인한다
- [ ] T063 [P] [US4] `backend/src/test/java/com/ssafy/thorp/macro/MacroSampleDataIsolationTest.java`에 예시 자료 격리 테스트를 둔다. 운영 프로필에서 예시 어댑터가 선택되지 않는지 확인한다

**Checkpoint**: 이용 조건을 지키며 수집하고 개발 대역이 운영에 섞이지 않는다

---

## Phase 7: Polish & 연동

**Purpose**: 009·010 연동 정합화와 남은 결정 마무리

- [ ] T064 `backend/src/main/java/com/ssafy/thorp/marketflow/`의 지표 조회 경로를 008 스냅샷 한 번 읽기로 바꾼다. 기존 4회 분리 조회는 포인터 교체 중 버전이 섞인다
- [ ] T065 `specs/009-market-flow-overview/`의 spec과 계약에서 지표·분야 식별자를 008과 맞춘다. 두바이유 식별자 표기 불일치와 분야 식별자 대소문자를 정리한다
- [ ] T066 `specs/009-market-flow-overview/spec.md`의 검색 진입 기준월을 010의 계산 완료월로 바꾼다. 008의 T_g를 검색 가능월로 쓰지 않는다 (FR-013)
- [ ] T067 `specs/009-market-flow-overview/contracts/`에서 자료가 없을 때의 응답을 008과 맞춘다. 자료 버전과 기준월이 비어 있을 수 있고 경로가 빈 배열일 수 있다
- [ ] T068 [P] `data/src/data_pipeline/regime/macro/postgres.py`에 010이 쓸 시계열 읽기를 만든다. `regime/macro/source.py`의 `MacroIndicatorSource` 프로토콜을 구현하고 `create_source()` 하나를 내놓는다 — `regime/macro/__init__.py`의 팩터리가 바로 이 모듈 경로로 찾으므로 다른 자리에 두면 010 배치가 구현체를 못 찾는다. contracts/macro-indicator-source.md를 따르고 결측 사유를 함께 돌려준다
- [ ] T069 [P] `data/src/data_pipeline/macro/registry.py`의 등록표에 지표별 공표 정밀도를 채운다. 최소 유효 척도는 010 소유이므로 008이 정하지 않는다 (FR-015)
- [ ] T070 `backend/src/main/java/com/ssafy/thorp/regime/infrastructure/macro/`의 `PendingMacroIndicatorAdapter`를 008 등록표를 읽는 구현으로 바꾼다. 010 contracts/macro-indicator-port.md §2의 `registryVersion`·`fields`·`indicator` 세 메서드를 채우고, 픽스처 대역이 운영 프로필에서 선택되지 않는지 확인한다
- [ ] T071 008이 실제 자료를 내보내기 시작하면 임시 대역을 걷어낸다. `backend/src/main/resources/application.yml`·`application-local.yml`의 `thorp.regime.macro.source`를 `macro`로 돌리고, `regime/infrastructure/macro/fixture/`와 `data/src/data_pipeline/regime/macro/fixture/`, `marketflow/infrastructure/example/`와 `marketflow/macro-indicators-example-v1.json`을 지운다. 지우고 나서 기동과 계약 테스트가 통과해야 한다 (010 contracts/macro-indicator-port.md §4.3)
- [ ] T072 quickstart.md의 검증 시나리오를 처음부터 끝까지 수행하고 결과를 기록한다. 원값 대조 표본은 지표마다 3개월, 네 변환 유형과 개편 연결 구간·결측 직후 달을 포함한다. 허용 오차는 PD-12가 확정되기 전까지 정확 일치(오차 0)다 (SC-007, PD-12)
- [ ] T073 정기 수집을 몇 달 돌린 뒤 `data/src/data_pipeline/macro/registry.py`의 지표별 재조회 구간을 실제 개정 관행에 맞춰 조정하고 근거를 등록표에 남긴다 (FR-017)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 선행 없음
- **Foundational (Phase 2)**: Setup 완료 후. 모든 사용자 스토리를 막는다
- **User Stories (Phase 3~6)**: Foundational 완료 후 시작
- **Polish (Phase 7)**: US1이 끝나야 의미가 있다. 009·010 연동은 조회가 있어야 붙는다

### User Story Dependencies

- **US1 (P1)**: Foundational만 필요하다. 혼자 완결된다
- **US2 (P1)**: US1의 발행기를 확장한다. US1 뒤에 온다
- **US3 (P2)**: US1의 수집·정규화를 확장한다. US2와 병렬 가능하다
- **US4 (P2)**: US1의 어댑터를 확장한다. US2·US3과 병렬 가능하다

### Within Each User Story

어댑터에서 변환으로, 정규화로, 발행으로, 조회로 간다. 테스트는 해당 구현과 같은 작업 묶음에 둔다

### Parallel Opportunities

- Phase 1의 T003·T004·T005는 함께 진행할 수 있다
- Phase 2의 T013·T014·T015·T016은 스키마 작업(T006~T010) 뒤에 함께 진행할 수 있다
- Phase 3의 네 어댑터 T022·T023·T024·T025는 서로 다른 파일이라 함께 진행할 수 있다
- US2·US3·US4는 US1이 끝난 뒤 세 갈래로 나눠 진행할 수 있다

## Parallel Example: User Story 1

```text
T018·T019·T020·T021 (테스트 네 개, 서로 다른 파일)
  다음
T022·T023·T024·T025 (어댑터 네 개, 서로 다른 파일)
  다음
T026 → T027 → T028 → T029 (연결·변환·정규화는 같은 흐름이라 순차)
  다음
T030 → T031 (발행과 기준월)
  다음
T032 → T033 → T034·T035
```

## 작업 묶음과 병합 요청 경계

단계는 의존 순서이고, 아래 묶음이 티켓·브랜치·병합 요청의 단위다. 규약이 1 티켓 = 1 브랜치 = 1 병합 요청이므로 묶음 수가 곧 병합 요청 수다. 각 묶음은 따로 검증되고 따로 되돌릴 수 있어야 한다.

| 묶음 | 작업 | 파트 | 왜 여기서 끊나 |
|---|---|---|---|
| A. 스키마와 등록표 | T001~T017 | DE | 마이그레이션과 그것을 쓰는 코드·검증은 같은 병합 요청에 둔다(헌장 스키마 변경 규칙). 17개 등록값이 여기서 확정된다 |
| B. 원천 수집과 변환 | T018~T029 | DE | 어댑터 넷과 연결기와 변환기. 저장에 붙기 전이라 표본 응답만으로 검증된다 |
| C. 발행과 기준월 | T030·T031·T035 | DE | 불변 스냅샷과 `T_g`·`T_all`. 앞 묶음의 변환값이 있어야 의미가 있다 |
| D. 008 조회 | T032·T033·T034 | BE | 백엔드 단일 스냅샷 응답. 009가 소비할 계약이 여기서 고정된다 |
| E. 버전 독립 | T036~T043 | DE·BE | US2 전체. 세 버전 분리와 원자적 발행 |
| F. 상태 드러내기 | T044~T052 | DE·BE | US3 전체. 지연·실패·결측·개정 |
| G. 이용 조건 | T053~T063 | DE·BE | US4 전체. 한도 준수와 권리 철회 대응과 예시 자료 격리 |
| H. 009 연동 | T064~T067 | BE | 009의 코드와 문서를 함께 고친다. 008 단독으로 끝나지 않으므로 009 담당자와 먼저 합의하고 연다 |
| I. 010 연동과 마무리 | T068~T072 | DE·BE | 시계열 읽기와 구현체 교체와 임시 대역 폐기와 최종 검증 |
| J. 재조회 구간 확정 | T073 | DE | 정기 수집을 몇 달 관찰해야 정해진다. 묶음 I에 두면 I가 그동안 병합되지 못한다 |

열 묶음이다. 단계가 일곱인데 묶음이 더 많은 이유는 US1이 작업 18개로 너무 커서다. 어댑터 넷과 변환과 발행과 조회를 한 병합 요청에 담으면 리뷰가 되지 않아 B·C·D로 나눴다.

### 더 쪼갤 수 있는 곳

B는 어댑터별로 넷(T022·T023·T024·T025)으로 더 나눠도 각각 표본 응답으로 검증된다. 사람이 여럿 붙으면 그렇게 나눈다. 다만 T026~T029의 연결·변환·정규화는 한 흐름이라 쪼개면 중간 상태가 검증되지 않는다.

### 순서

A가 모든 묶음을 막는다. B·C·D는 순서대로 가야 하고, 여기까지가 최소 완성본이다. E·F·G는 D가 끝나면 세 갈래로 나뉜다. H는 D 뒤, I는 C 뒤면 시작할 수 있다. J는 정기 수집이 몇 달 돌아간 뒤라 나머지와 같은 릴리즈에 들어가지 않는다.

---

## Implementation Strategy

**최소 완성본은 US1이다.** Phase 1·2·3을 마치면 17개 지표를 받아 변환하고 한 응답으로 조회할 수 있다. 009 화면과 010 검색이 소비할 자료가 이 시점에 생긴다.

그 뒤는 US2로 버전 독립과 재현성을 세우고, US3과 US4를 병렬로 붙인다. Polish의 009·010 연동은 마지막에 둔다. 양쪽 문서를 함께 고쳐야 해서 혼자 끝나지 않는다.

**Jira 티켓과 브랜치는 실제로 착수할 묶음에 대해서만 만든다.** 1 티켓 = 1 브랜치 = 1 MR이다.

## 주의

- 남은 미확정은 둘이다. 지표별 재조회 구간은 갱신을 관찰해야 정해지고(T073), 주택가격 연결 계수는 겹침 구간에서 산출해야 한다(T026)
- 두바이유는 권리 확인 대기 상태로 진행한다. 나중에 상업 이용 제한 대상으로 밝혀지면 그 지표만 중단하고 과거 자료 버전은 보존한다 (FR-016b)
- 문서 작성이나 표본 자료 통과를 구현 완료로 세지 않는다. 테스트가 skip되면 통과로 세지 않는다
