# Tasks: 대표 ETF 자료 확보

**Input**: Design documents from `/specs/001-etf-market-data/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/README.md), [quickstart.md](./quickstart.md)

**Tests**: 테스트 작업을 포함한다. spec이 요청했기 때문이 아니라 프로젝트 규약이 요구하기 때문이다. constitution 「기능·계산 확인」이 "대표 거래·현금·비용·분배금·공통 기간을 수기 또는 독립 계산으로 대조한다"와 "동일 버전 재실행이 같은 결과를 내는지 확인한다"를 완료 조건으로 두었고, `data/CLAUDE.md`·`worker/CLAUDE.md`가 `pytest`, `backend/CLAUDE.md`가 `./gradlew build`를 작업 후 필수 명령으로 정했다.

**Organization**: 사용자 스토리별로 묶어 각 스토리를 독립적으로 구현·검증할 수 있게 했다. PD 결정 작업은 본선과 의존 관계가 없는 별개 단계로 두었다(plan.md 「미확정 항목을 병렬 작업으로 처리하는 방식」).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 실행 가능 (다른 파일, 미완료 작업에 의존하지 않음)
- **[Story]**: 소속 사용자 스토리 (US1, US2, US3)
- 설명에 정확한 파일 경로를 포함한다

## Path Conventions

모노레포이며 파트별 경계를 따른다(plan.md Structure Decision).

- `data/` — DATA 파트. `src/data_pipeline/` 재사용 로직, `scripts/` argparse 진입점, `tests/`
- `worker/` — `src/worker/`, `tests/`
- `backend/` — `src/main/java/com/ssafy/thorp/sector/`, `src/main/resources/db/migration/`, `src/test/java/`
- `specs/001-etf-market-data/contracts/fixtures/` — Worker와 Backend가 공유하는 픽스처

## 파트별 검증 명령

각 파트 CLAUDE.md가 정한 명령이며 작업 후 반드시 실행한다.

```bash
cd data     && ruff check src scripts && pytest
cd worker   && ruff check src tests && pytest
cd backend  && ./gradlew spotlessApply build
```

---

## Phase 1: Setup (Shared Infrastructure)

- [ ] T001 `data/pyproject.toml`과 `data/requirements.txt`에 KRX OPEN API HTTP 클라이언트·pyarrow·PostgreSQL 드라이버 의존성을 추가하고 pykrx를 제거한다 (research.md 결정 1)
- [ ] T002 [P] `data/.env.example`을 만들어 `KRX_API_KEY`와 `DATABASE_URL`을 문서화한다. 실제 키는 `.env`에만 두고 코드에 하드코딩하지 않는다 (`data/CLAUDE.md`)
- [ ] T003 [P] `worker/pyproject.toml`과 `worker/requirements.txt`에 PostgreSQL 드라이버 의존성을 추가한다
- [ ] T004 [P] `data/CLAUDE.md`의 원천을 pykrx에서 KRX OPEN API로 고치고, 컬럼 표준에 `value`(거래대금, 원)를 추가한다 (research.md 결정 1·6). **DATA 파트 합의가 선행되어야 한다**
- [ ] T005 [P] `data/` 디렉터리에 `raw/`와 `tests/`를 만들고 `.gitignore`에 `raw/`가 이미 포함되어 있는지 확인한다

---

## Phase 2: Foundational (Blocking Prerequisites)

**목적**: 모든 사용자 스토리가 읽고 쓸 저장소와 자료 버전 체계를 먼저 세운다. 이 단계가 끝나기 전에는 어느 스토리도 시작할 수 없다.

- [ ] T006 `backend/src/main/resources/db/migration/V3__etf_market_data.sql`에 관계형 개체 6개를 만든다: `sector`, `representative_etf`, `sector_etf_map_version`, `sector_etf_map_entry`, `dataset_version`, `reconciliation_record` (data-model.md 「관계형 개체」)
- [ ] T007 같은 마이그레이션에 시계열 개체 5개를 만든다: `daily_price`, `corporate_action`, `daily_index`, `data_gap`, `suspension_period`. `daily_price`의 PK는 `(dataset_id, instrument_id, date)`다 (data-model.md 「시계열 개체」)
- [ ] T008 같은 마이그레이션에 불변식을 제약으로 넣는다: `sector_etf_map_entry`의 `(map_version_id, sector_id)` UNIQUE, `representative_etf`의 `leveraged`·`inverse`가 true인 행 거부, `sector_etf_map_version`의 `effective_to IS NULL` 행 최대 하나, `reconciliation_record`의 `cause`↔`resolution` 조합 제한 (data-model.md 불변식)
- [ ] T009 같은 마이그레이션에 발행된 `dataset_version` 행과 그에 딸린 시계열 행의 UPDATE·DELETE를 거부하는 규칙을 넣는다 (`backend/src/main/resources/db/migration/V3__etf_market_data.sql`). FR-017이 요구하는 불변성이며 quickstart 시나리오 3이 이 거부를 확인한다
- [ ] T010 [P] `data/src/data_pipeline/dataset_version.py`에 자료 버전 발행을 구현한다. `dataset_id`는 `KRX_DAILY_{YYYYMMDD}` 형식이고, `data_deadline`·`collected_at`·`last_success_at`·`complete`·`failure_reason`·`is_sample`을 기록한다 (research.md 결정 4)
- [ ] T011 [P] `data/src/data_pipeline/repository.py`에 서비스 저장소 적재 계층을 만든다. 자료 버전 행을 먼저 만든 뒤 시계열을 적재하는 순서를 강제한다 (data-model.md 「개체 관계」)
- [ ] T012 [P] `backend/src/test/java/.../support/` 아래 `V3` 스키마를 읽는 DB 테스트가 `PostgresTestSupport`를 상속해 동작하는지 확인하는 테스트를 추가한다. Docker가 꺼져 있으면 skip되므로 `skipped` 수를 확인한다 (`backend/CLAUDE.md`)
- [ ] T013 [P] `backend/src/main/java/com/ssafy/thorp/sector/` 아래 4계층 폴더(`presentation`·`application`·`domain`·`infrastructure`)를 만들고 `ArchitectureTest`가 통과하는지 확인한다 (ADR-0001, `backend/CLAUDE.md` 「새 도메인 추가 체크리스트」)

**Checkpoint**: 저장소와 자료 버전 체계가 준비되었다. 이제 사용자 스토리를 병렬로 시작할 수 있다.

---

## Phase 3: User Story 1 - 섹터 이름만 확인하고 진단을 시작한다 (Priority: P1) 🎯 MVP

**목표**: 사용자가 섹터 이름과 그에 연결된 대표 ETF 이름만 확인하고 분석을 시작할 수 있다. 자료를 입력하거나 공급원을 고르지 않는다.

**Independent Test**: 섹터 목록을 조회해 모든 섹터에 대표 ETF 하나가 연결되어 있고 상장일·기준 지수가 등록되어 있는지 확인한다. 화면 없이 대응표만으로 검증할 수 있다.

### Tests for User Story 1

- [ ] T014 [P] [US1] `data/tests/test_sector_map.py`에 대응표 검증 테스트를 작성한다: 섹터마다 대표 ETF가 정확히 하나, 레버리지·인버스 상품 거부, 상장일·기준 지수 필수
- [ ] T015 [P] [US1] `backend/src/test/java/.../sector/SectorApiIT.java`에 `GET /sectors` 통합 테스트를 작성한다. 응답이 `contracts/sector-api.md`의 예시와 동치임을 단언한다
- [ ] T016 [P] [US1] `backend/src/test/java/.../sector/SectorApiIT.java`에 SC-001 검증을 넣는다: `representativeEtf`가 없는 섹터가 0개다

### Implementation for User Story 1

- [ ] T017 [US1] `specs/001-etf-market-data/contracts/fixtures/sample-sector-map.json`에 예시 대응표를 만든다. PD-01 확정 전까지 쓰는 값이며 `isSample` 표시가 붙는다 (FR-002)
- [ ] T018 [US1] `data/src/data_pipeline/sector_map.py`에 대응표 로딩·검증을 구현한다. 항목은 섹터 이름·대표 ETF 이름·종목 식별자·상장일·기준 지수·운용사이며, 국내 상장 비레버리지·비인버스 ETF만 허용한다 (FR-001)
- [ ] T019 [US1] `data/src/data_pipeline/sector_map.py`에 대응표 버전 발행을 구현한다. 내용이 바뀌면 새 버전을 만들고 기존 버전을 고치지 않는다 (FR-003)
- [ ] T020 [US1] `data/scripts/ingest_sector_map.py`에 argparse 진입점을 만든다. `--sample`과 `--map-version` 인자를 받고 재실행이 멱등하다 (`data/CLAUDE.md`)
- [ ] T021 [P] [US1] `backend/.../sector/domain/model/Sector.java`·`RepresentativeEtf.java`와 `domain/vo/SectorCode.java`를 만든다. Spring·JPA·Jackson import를 두지 않는다 (ADR-0001)
- [ ] T022 [P] [US1] `backend/.../sector/infrastructure/persistence/`에 `SectorJpaEntity`·`RepresentativeEtfJpaEntity`·`SectorEtfMapJpaEntity`와 각 `*Mapper`(정적 `toEntity`/`toDomain`)를 만든다
- [ ] T023 [US1] `backend/.../sector/application/repository/SectorRepository.java`에 조회 인터페이스를 두고 `infrastructure/persistence/SectorRepositoryImpl.java`에 구현한다. **수정·생성 메서드를 두지 않는다** (research.md 결정 7)
- [ ] T024 [US1] `backend/.../sector/application/service/SectorService.java`와 `application/result/SectorListResult.java`를 만든다. 대응표가 발행되지 않았으면 빈 배열이 아니라 준비 안 됨을 구분해 알린다 (원칙 VI)
- [ ] T025 [US1] `backend/.../sector/presentation/controller/SectorController.java`와 `presentation/response/SectorListResponse.java`에 `GET /sectors`를 구현한다. `mapVersion`·`isSample`을 함께 반환한다 (contracts/sector-api.md)
- [ ] T026 [US1] `backend/.../sector/presentation/controller/SectorExceptionHandler.java`에 `SECTOR_MAP_NOT_READY`(409)와 `SECTOR_NOT_FOUND`(404)를 매핑한다 (contracts/sector-api.md 「오류」)

**Checkpoint**: 예시 대응표로 `GET /sectors`가 동작한다. quickstart 시나리오 1을 실행할 수 있다. 002 섹터 선택 화면이 이 응답으로 개발을 시작할 수 있다.

---

## Phase 4: User Story 2 - 실제 상장 이후 가격과 기업행위로 계산된 결과를 받는다 (Priority: P1)

**목표**: 백테스트가 실제 상장 이후 가격과 기업행위가 반영된 자료로 계산된다. 상장 전 값이 없고, 분배금과 내부 보수가 중복 반영되지 않으며, 자료가 불확실한 구간은 범위에서 빠진다.

**Independent Test**: 대표 ETF 하나를 골라 상장 전 날짜·분배금 지급일·분할일 전후의 원시 가격과 조정 자료를 조회해 운용사 공시와 대조한다.

### Tests for User Story 2

- [ ] T027 [P] [US2] `data/tests/test_krx_ingest.py`에 수집 테스트를 작성한다: 컬럼 표준 변환, 상장 전 행 부재(SC-003), 재실행 멱등성
- [ ] T028 [P] [US2] `data/tests/test_corporate_actions.py`에 조정 가격 테스트를 작성한다: 분배금·분할 계수 계산, 두 시점 비율이 이후 계수에 영향받지 않음, 내부 보수 중복 차감 없음 (research.md 결정 2)
- [ ] T029 [P] [US2] `specs/001-etf-market-data/contracts/fixtures/evaluation-window-cases.json`에 공통 평가 기간 픽스처를 만든다. `contracts/evaluation-window.md` 「경계 동작」의 7가지를 모두 담는다
- [ ] T030 [P] [US2] `worker/tests/test_evaluation_window.py`에 T029 픽스처를 읽는 테스트를 작성한다
- [ ] T031 [P] [US2] `backend/src/test/java/.../sector/EvaluationWindowTest.java`에 **같은 픽스처**를 읽는 테스트를 작성한다. 한쪽만 고치면 실패해야 한다 (contracts/README.md 「계약 변경 순서」)
- [ ] T032 [P] [US2] `worker/tests/test_repository_provider.py`에 provider 테스트를 작성한다: 같은 `datasetId` 두 번 조회 시 값 동일(SC-004), `datasetId` 불일치 시 `MarketDataError`, 상장 전 구간 요청 시 오류, 거래정지일의 `carriedForwardDates` 기록
- [ ] T033 [P] [US2] `data/tests/test_reconciliation.py`에 대조 테스트를 작성한다: 표본이 상장 첫날·분배금 지급일·분할일로만 구성되고 평범한 거래일이 없음, `cause`↔`resolution` 조합 제한

### Implementation for User Story 2

- [ ] T034 [US2] `data/src/data_pipeline/krx.py`의 `fetch_ohlcv` 스캐폴딩을 KRX OPEN API 연동으로 구현한다. 컬럼을 `date, open, high, low, close, volume, value`로 표준화한다
- [ ] T035 [US2] `data/src/data_pipeline/krx.py`에 이용 한도 처리를 넣는다. 한도에 걸려 그날 수집을 끝내지 못하면 중단하고 `dataset_version.complete = false`와 `failure_reason`을 남긴다 (FR-014, Edge Cases)
- [ ] T036 [US2] `data/src/data_pipeline/corporate_actions.py`에 분배금·분할 수집과 소급 조정 계수 산출을 구현한다. `adjusted_close`와 `adjustment_factor`를 함께 저장한다 (research.md 결정 2)
- [ ] T037 [US2] `data/src/data_pipeline/corporate_actions.py`에 상장일·상장폐지일 확보를 넣는다. 상장 전 합성 이력이나 미래 자료로 빈 값을 채우지 않는다 (FR-005)
- [ ] T038 [P] [US2] `data/src/data_pipeline/index.py`에 KOSPI 시리즈 일별 자료 수집을 구현한다. 확보 기간이 비교 대상 대표 ETF의 상장 이후 전체를 포함하는지 검사한다 (FR-009)
- [ ] T039 [US2] `data/src/data_pipeline/gaps.py`에 자료 부족 구간 산출을 구현한다. `reason`은 `AMBIGUOUS_PRICE`·`MISSING_CORPORATE_ACTION`·`RECONCILIATION_UNKNOWN`·`INDEX_HISTORY_SHORT`이며, **`SUSPENDED_TOO_LONG`은 PD-18 확정 전까지 쓰지 않는다** (FR-006, contracts/market-dataset.md)
- [ ] T040 [US2] `data/src/data_pipeline/gaps.py`에 거래정지 처리를 구현한다. 길이와 무관하게 직전 종가를 평가에만 쓰고 `price_carried_forward`를 표시하며, 정지 구간을 `suspension_period`에 시작일·종료일·거래일 수로 기록한다 (FR-006 임시 동작)
- [ ] T041 [US2] `data/scripts/ingest_krx.py` 스캐폴딩을 완성한다. `--map-version`·`--until`·`--sample`·`--simulate-quota-exhausted` 인자를 받고, Parquet 사본과 서비스 저장소에 함께 적재한다 (research.md 결정 8)
- [ ] T042 [P] [US2] `data/scripts/ingest_index.py`를 만들어 지수 자료 수집 진입점을 제공한다
- [ ] T043 [P] [US2] `data/scripts/verify_listing_boundary.py`를 만들어 상장 전 가격 행을 세는 검증 스크립트를 제공한다. quickstart 시나리오 2가 이 스크립트를 쓴다
- [ ] T044 [US2] `data/src/data_pipeline/reconciliation.py`에 공시 대조를 구현한다. 표본은 상장 첫날·모든 분배금 지급일·모든 분할일이며 평범한 거래일을 넣지 않는다 (FR-016)
- [ ] T045 [US2] `data/src/data_pipeline/reconciliation.py`에 불일치 처리를 구현한다. 개별 값을 수정하지 않고 원인별로 나눈다: 수집·가공 오류는 새 버전 재발행, 정의 차이는 기록만, 원인 불명은 구간 제외 (FR-017)
- [ ] T046 [P] [US2] `data/scripts/reconcile.py`를 만들어 대조 진입점을 제공한다
- [ ] T047 [US2] `worker/src/worker/evaluation_window.py`에 공통 평가 기간 산식을 구현한다. `contracts/evaluation-window.md`의 산식과 경계 동작을 따르며, **검증용 잠금 구간을 빼지 않는다** (FR-007, FR-008)
- [ ] T048 [US2] `worker/src/worker/market_data.py`에 `RepositoryDataProvider`를 추가한다. `MarketDataset`의 `source`는 `KRX_OPEN_API`이고 `metadata`에 `datasetId`·`dataDeadline`·`carriedForwardDates`·`dataGaps`·`mapVersion`·`provisionalSuspensionHandling`을 담는다 (contracts/market-dataset.md)
- [ ] T049 [US2] 같은 모듈에서 워밍업 자료를 거래일 기준으로 앞에서 읽도록 구현한다. 현재 `YFinanceDataProvider`가 쓰는 달력 일수 방식(`market_data.py:86`)은 연휴가 긴 해에 워밍업이 모자란다
- [ ] T050 [US2] `worker/src/worker/engine_runner.py:68`의 기본 provider를 `YFinanceDataProvider()`에서 `RepositoryDataProvider()`로 바꾼다. 클래스는 남기고 실제 Job 처리 경로에서 쓰지 않는 임시 수단임을 주석에 적는다 (research.md 결정 1)
- [ ] T051 [US2] `docs/specs/backtest-messaging/`의 픽스처와 `generate_fixtures.py`에서 `datasetId` 값을 `KRX_DAILY_V1`에서 `KRX_DAILY_{YYYYMMDD}` 형식으로 바꾼다. 스키마는 그대로이므로 round-trip 테스트가 통과해야 한다 (research.md 결정 4)
- [ ] T052 [P] [US2] `backend/.../sector/application/service/EvaluationWindowService.java`에 같은 산식을 구현한다. T029 픽스처로 검증한다
- [ ] T053 [US2] `backend/.../sector/presentation/controller/SectorController.java`에 `GET /sectors/evaluation-window`를 추가한다. `evaluable: false`를 오류가 아닌 정상 응답으로 반환한다 (contracts/sector-api.md)

**Checkpoint**: 백테스트가 001의 자료로 실행된다. quickstart 시나리오 2~6을 실행할 수 있다. 003·004가 공통 평가 기간과 가격 자료로 개발을 진행할 수 있다.

---

## Phase 5: User Story 3 - 대표 상품 정보에서 근거와 한계를 확인한다 (Priority: P2)

**목표**: 사용자가 결과의 근거를 확인하려고 '대표 상품 정보'를 열면 기준 지수·구성종목 중복·상장일·유동성·자료 출처와 한계가 보인다.

**Independent Test**: 대표 ETF 하나의 대표 상품 정보를 열어 필수 항목이 모두 보이고, 과거 구성 이력이 없는 상품에 현재 구성 기준 표시가 붙는지 확인한다.

### Tests for User Story 3

- [ ] T054 [P] [US3] `data/tests/test_instrument_profile.py`에 유동성 산출 테스트를 작성한다: 최근 3개월 일평균 거래대금이 `value` 컬럼 기준으로 계산되고, 거래량×종가 추정을 쓰지 않는다
- [ ] T055 [P] [US3] `backend/src/test/java/.../sector/InstrumentProfileApiIT.java`에 통합 테스트를 작성한다: 필수 항목 전부 존재, `asOfCurrentComposition` 표시, `sourceAttribution` 존재(SC-005의 필드 수준 검증), **추적 차이 필드 부재**

### Implementation for User Story 3

- [ ] T056 [US3] `data/src/data_pipeline/instrument_profile.py`에 대표 상품 정보 산출을 구현한다. 기준 지수·지수 방법론 요약·상장일·구성종목 중복·최근 3개월 일평균 거래대금·자료 출처·생존 상품 한계를 담는다 (FR-015)
- [ ] T057 [US3] `data/src/data_pipeline/instrument_profile.py`에서 과거 구성 이력이 없으면 `holdings_as_of_current`를 true로 표시한다. 당시 중복·집중도를 재현했다고 표현하지 않는다 (FR-015)
- [ ] T058 [P] [US3] `backend/.../sector/infrastructure/persistence/InstrumentProfileJpaEntity.java`와 `InstrumentProfileMapper.java`를 만든다. `avgDailyValue3m`은 금액이므로 문자열로 내보낸다
- [ ] T059 [US3] `backend/.../sector/presentation/controller/SectorController.java`에 `GET /sectors/{code}/instrument-profile`을 추가한다. `avgDailyValue3m`에 `@JsonSerialize(using = PlainBigDecimalSerializer.class)`를 **필드마다** 붙인다. 웹 ObjectMapper에 전역 등록하면 기존 `MarketResponse.value` 계약이 깨진다 (`backend/CLAUDE.md`)
- [ ] T060 [US3] `SectorExceptionHandler`에 `INSTRUMENT_PROFILE_NOT_READY`(409)를 추가한다 (contracts/sector-api.md)

**Checkpoint**: 세 사용자 스토리가 모두 독립적으로 동작한다. 005 결과 화면이 근거 패널을 붙일 수 있다.

---

## Phase 6: PD-01 확정 (본선과 독립)

**목적**: 예시 대응표를 실제 값으로 교체한다. 본선 구현과 의존 관계가 없으므로 Phase 1 이후 언제든 병행할 수 있다.

- [ ] T061 자료 이용 승인을 신청하고 진행 상태를 기록한다. 승인 기간과 갱신은 팀이 관리한다 (spec.md Assumptions)
- [ ] T062 `specs/001-etf-market-data/pd01-etf-candidates.md`에 섹터마다 대표 ETF 후보 3~5개를 정리한다. 국내 상장 비레버리지·비인버스 조건을 미리 걸러낸다 (FR-001)
- [ ] T063 `specs/001-etf-market-data/pd01-etf-candidates.md`에 후보별 확보 가능 기간·기업행위 이력 완전성·최근 3개월 일평균 거래대금을 실제 조회로 채운 비교표를 만든다. **후보끼리 비교하는 것이며 예시 자료 결과와 비교하는 것이 아니다**
- [ ] T064 비교 결과로 대표 ETF를 확정하고 `specs/README.md`의 PD-01과 `spec.md`의 결정 항목 표를 갱신한다
- [ ] T065 확정한 대응표를 `data/scripts/ingest_sector_map.py`로 새 버전 발행하고, `dataset_version.is_sample`과 조회 응답의 `isSample`을 false로 바꾼다 (FR-002)

**Checkpoint**: 실제 자료로 동작한다. 예시 자료 표시가 사라진다.

---

## Phase 7: PD-18 확정 (Phase 4 이후, 본선과 독립)

**목적**: 긴 거래정지의 처리 방식과 기준 일수를 정한다. Phase 4가 쌓아 둔 `suspension_period` 기록이 입력이므로 그 뒤에 시작한다.

- [ ] T066 `data/scripts/suspension_stats.py`를 만들어 `suspension_period`에서 정지 사례의 길이 분포를 집계한다: 건수, 중앙값, 최댓값, ETF별 분포
- [ ] T067 그 분포로 처리 방식 B(정지 이후만)·C(정지 이전만)·D(후보 제외) 각각을 적용했을 때 공통 평가 기간이 얼마나 줄어드는지 계산한다. 손실 규모를 수치로 비교한다 (research.md 「PD-18을 나중에 정하기 위한 준비」)
- [ ] T068 처리 방식과 기준 일수를 확정하고 `specs/README.md`의 PD-18a·PD-18b를 갱신한다. 정지 구간을 빼고 앞뒤를 이어 붙이는 방식은 후보에서 제외된 상태다
- [ ] T069 확정한 규칙을 `spec.md` FR-006과 `contracts/market-dataset.md` 「거래정지 처리」에 반영하고 `data/src/data_pipeline/gaps.py`에 `SUSPENDED_TOO_LONG` 기록을 켠다
- [ ] T070 `worker/src/worker/market_data.py`의 `metadata.provisionalSuspensionHandling`을 제거하고 `contracts/market-dataset.md`의 임시 처리 안내 문단을 걷는다

**Checkpoint**: 거래정지 처리가 확정된 규칙으로 동작한다.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T071 [P] quickstart.md의 시나리오 1~7을 순서대로 실행해 각 기대 결과를 확인하고 결과를 기록한다
- [ ] T072 [P] `specs/001-etf-market-data/quickstart.md` 시나리오 5의 수기 대조 절차를 수행한다. 분배금 지급일 하나를 골라 운용사 공시의 주당 분배금과 `corporate_action.amount`를 눈으로 확인하고 결과를 `reconciliation_record`에 남긴다. constitution 「기능·계산 확인」이 요구하는 절차다
- [ ] T073 [P] `docs/specs/market-api.md`와 `docs/adr/0004`가 001의 결정과 어긋나는 부분을 확인해 갱신 대상 목록을 남긴다. `specs/README.md:98`이 이미 갱신 대상으로 기록했으므로 001 범위에서 직접 고치지는 않는다
- [ ] T074 [P] `README.md`의 "현재 구현 상태"를 갱신한다. 자료 원천이 yfinance에서 KRX OPEN API로 바뀐 사실을 반영한다
- [ ] T075 `data/`·`worker/`·`backend/` 세 파트의 검증 명령을 모두 실행해 통과를 확인한다. `backend`의 DB 테스트는 Docker가 꺼져 있으면 skip되므로 `skipped` 수를 확인한다
- [ ] T076 AI 코드리뷰를 수행하고 발견된 문제를 해결한 뒤 MR을 올린다 (루트 `CLAUDE.md` 「AI 협업 규칙」)

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (Setup)
    ↓
Phase 2 (Foundational) ← 저장소와 자료 버전. 모든 스토리를 막는다
    ↓
    ├─ Phase 3 (US1) ─┐
    ├─ Phase 4 (US2) ─┤ 세 스토리는 서로 독립
    └─ Phase 5 (US3) ─┘   단 US3는 US2의 가격 자료가 있어야 유동성을 계산한다
    ↓
Phase 8 (Polish)

Phase 6 (PD-01)  Phase 1 이후 언제든. 본선과 의존 없음
Phase 7 (PD-18)  Phase 4 이후. suspension_period 기록이 필요하다
```

### User Story Dependencies

- **US1 (P1)**: Phase 2만 필요하다. 완전히 독립적이며 MVP다
- **US2 (P1)**: Phase 2만 필요하다. US1과 병렬로 진행할 수 있다. 대응표 자체는 US1이 만들지만, US2는 예시 대응표만 있어도 가격 수집을 시작할 수 있다
- **US3 (P2)**: Phase 2와 **US2의 가격 자료**가 필요하다. 유동성이 `daily_price.value`에서 계산되기 때문이다. 나머지 항목은 독립적이다

### Within Each User Story

각 스토리 안에서는 이 순서를 따른다.

1. 테스트 작성 (병렬 가능)
2. 파이프라인 모듈 → 진입점 스크립트
3. Backend 도메인 모델 → 영속화 → 서비스 → 컨트롤러
4. 오류 매핑

### Parallel Opportunities

- **Phase 1**: T002·T003·T004·T005가 서로 다른 파일이라 모두 병렬
- **Phase 2**: T006~T009는 같은 마이그레이션 파일이라 순차. T010·T011·T012·T013은 병렬
- **Phase 3**: 테스트 T014~T016 병렬. 구현에서 T021·T022가 병렬
- **Phase 4**: 테스트 T027~T033 병렬. 구현에서 T038·T042·T043·T046·T052가 병렬
- **Phase 5**: T054·T055 병렬, T058 병렬
- **Phase 8**: T071~T074 병렬
- **파트 간**: DATA 파트의 파이프라인 작업과 BACK 파트의 도메인 작업이 스키마(Phase 2) 확정 후 병렬로 진행된다

---

## Parallel Example: User Story 2

```bash
# 테스트를 함께 작성한다 (서로 다른 파일)
T027 data/tests/test_krx_ingest.py
T028 data/tests/test_corporate_actions.py
T029 contracts/fixtures/evaluation-window-cases.json
T030 worker/tests/test_evaluation_window.py
T031 backend/src/test/java/.../EvaluationWindowTest.java
T032 worker/tests/test_repository_provider.py
T033 data/tests/test_reconciliation.py

# 구현에서 서로 건드리지 않는 파일
T038 data/src/data_pipeline/index.py
T042 data/scripts/ingest_index.py
T043 data/scripts/verify_listing_boundary.py
T046 data/scripts/reconcile.py
T052 backend/.../EvaluationWindowService.java
```

T029는 T030·T031의 선행 작업이다. 픽스처가 없으면 두 테스트가 읽을 대상이 없다.

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 → Phase 2 → Phase 3을 완료한다
2. quickstart 시나리오 1로 검증한다
3. **여기까지가 002 섹터 선택 화면의 개발 시작점이다.** 예시 대응표로 동작하며 화면에 예시 표시가 붙는다

### Incremental Delivery

1. **US1 완료** → 002가 섹터 목록으로 화면을 만들 수 있다
2. **US2 완료** → 004 백테스트가 실제 자료로 돌고 003이 공통 평가 기간 안내를 만들 수 있다. 이 시점에 yfinance 의존이 사라진다
3. **US3 완료** → 005가 결과의 근거 패널을 붙일 수 있다
4. **Phase 6 완료** → 예시 자료 표시가 사라지고 실제 대표 ETF로 동작한다
5. **Phase 7 완료** → 거래정지 처리가 임시에서 확정으로 바뀐다

### Parallel Team Strategy

Phase 2가 끝나면 파트별로 갈라진다.

- **DATA**: Phase 3의 T017~T020, Phase 4의 파이프라인 작업, Phase 5의 T056·T057, Phase 6·7 전체
- **BACK**: Phase 3의 T021~T026, Phase 4의 T052·T053, Phase 5의 T058~T060
- **Worker(BACK 겸임)**: Phase 4의 T047~T051

Phase 2의 스키마(T006~T009)가 두 파트의 접점이므로 먼저 합의하고 고정한다.

---

## Notes

- **[P] 표시는 다른 파일이고 미완료 작업에 의존하지 않는다는 뜻이다.** 같은 파일을 고치는 작업은 [P]를 붙이지 않았다
- **T004는 DATA 파트 합의가 선행되어야 한다.** `data/CLAUDE.md`의 원천과 컬럼 표준을 바꾸는 일이라 AI나 한 사람이 정할 수 없다
- **T051은 세 곳을 함께 고치는 작업이 아니다.** `datasetId`의 값 규칙만 바꾸므로 스키마는 그대로이고 픽스처 값 하나가 바뀐다. 만약 필드를 추가해야 하는 상황이 되면 `backend/CLAUDE.md`가 정한 순서(픽스처 → `port/message` → `worker/contracts/`)를 지킨다
- **T029는 Worker와 Backend가 공유하는 픽스처다.** 산식을 고칠 때는 `contracts/README.md` 「계약 변경 순서」를 따른다
- **PD-18 확정 전 임시 동작을 함부로 바꾸지 않는다.** `suspension_period` 기록이 Phase 7의 입력이므로, 정지를 제외하도록 미리 바꾸면 결정 근거가 사라진다
- 커밋은 작업 단위로 하고, MR 전에 AI 코드리뷰를 수행한다 (루트 `CLAUDE.md`)
