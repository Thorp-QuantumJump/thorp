# Tasks: 대표 ETF 자료 확보

**Input**: `specs/001-etf-market-data/` — plan.md, spec.md(Clarifications 5건), research.md(결정 1~8), data-model.md, contracts/{sector-api,evaluation-window,market-dataset}.md, quickstart.md(시나리오 1~7)

**작업 단위 규칙** (constitution v3.0.0 "Jira 연계"): 아래 T항목 하나는 실행 지시의 최소 단위이며, 여러 T항목을 묶은 작업 묶음 하나 = Jira Task 하나 = 브랜치 하나 = MR 하나다. 제목은 `[분야] 작업 내용` 형식이며 분야는 BE/FE/AI/DE/INFRA/DOCS. **`data/` 수집 파이프라인은 DATA 파트라 `[DE]`, `backend/`와 `worker/`는 백엔드 파트라 `[BE]`로 표기한다**(워커의 파트 소속은 004 tasks가 2026-09-10에 확정). 2026-09-11에 개별 task 76개를 아래 16개 묶음으로 재편했다. 묶음 기준은 constitution의 세 조건(하나의 목적 공유 / 독립 구현·검증·롤백 / 리뷰어가 한 번에 읽을 범위)이며, 같은 파트가 연속으로 하는 작업과 한쪽만 병합하면 반쪽이 되는 작업을 묶었다. **2026-09-11에 T001~T013을 Jira Task 6개로 다시 묶고 이슈를 발급했다.** 묶음을 16개에서 6개로 줄인 까닭은 Jira Task 하나의 단위를 speckit task 여러 개로 두기로 정했고, 같은 파트가 연속으로 수행하며 함께 병합해야 뜻이 통하는 작업을 한 묶음에 담는 편이 리뷰에 유리하기 때문이다(constitution 「Jira 연계」가 요구하는 묶음 구성 변경 기록이다). 발급 결과는 Epic `S15P21E205-30 백테스트` 아래 Story 3개(US1 `-173`, US2 `-174`, US3 `-175`)와 Task 6개다: T001·T002 → `-176`(SP 3), T003·T004 → `-177`(SP 5), T005·T006 → `-178`(SP 5), T007·T008·T009 → `-179`(SP 8), T010·T011 → `-180`(SP 5), T012·T013 → `-181`(SP 3). Story에는 담당자와 스토리 포인트를 지정하지 않고 Task에만 지정한다. 브랜치는 `feat(be)/{JIRA_NUMBER}-{desc}` 형식이며, 서브에이전트가 쓰는 작업 브랜치에는 Jira 번호를 붙이지 않는다. T014~T016은 실제 자료 확보가 선행 조건이라 아직 발급하지 않았다. 각 항목 아래 들여쓴 체크리스트는 구현 메모이며 Jira에 올리지 않는다. 완료 조건은 constitution "완료의 정의" 다섯 가지다.

**Tests**: 각 묶음은 자기 변경의 테스트를 포함한다. 파트별 검증 명령은 `data/` `ruff check src scripts && pytest`, `worker/` `ruff check src tests && pytest`, `backend/` `./gradlew spotlessApply build`다. 재현성(SC-004)은 T011, 공시 대조(SC-002)는 T009, 수기 대조는 T016에 명시했다.

**Format**: `- [ ] [ID] [P?] [Story?] [분야] 설명 (파일 경로) — Jira 키` — `[P]` = 다른 파일·의존 없음, 병렬 가능.

## Path Conventions

- 파이프라인: `data/src/data_pipeline/`, 진입점 `data/scripts/`, 테스트 `data/tests/`, 저장 `data/raw/`·`data/processed/`
- 워커: `worker/src/worker/`, 테스트 `worker/tests/`
- 백엔드: `backend/src/main/java/com/ssafy/thorp/sector/`(이하 `sector/`), 마이그레이션 `backend/src/main/resources/db/migration/`, 테스트 `backend/src/test/java/com/ssafy/thorp/sector/`
- 공유 픽스처: `specs/001-etf-market-data/contracts/fixtures/`

## 다른 spec과의 경계

- **004가 001에 요구하는 계약**은 `specs/004-backtest-execution/contracts/market-data-input.md`다. 004 워커의 `PostgresMarketDataProvider`(R-09)가 그 열을 읽고, 워커용 읽기 전용 접속 `MARKET_DATA_URL`은 004 T002가 마련한다. 001의 저장 설계가 그 계약을 만족해야 한다
- **마이그레이션 번호**: 저장소에 V2까지 있고 004가 V3~V6을 쓴다. 001은 **V7**을 쓴다. 007(`V3__param_search`)과 014(`V3__spring_session`)도 V3을 적어 두었으므로 재배정이 필요하다(T002에서 결론)
- **`worker/src/worker/market_data.py`**: 004 T004(`S15P21E205-134`)가 CSV 픽스처 기반 공급자를 먼저 만들고, 001 T011이 실제 저장소 연결을 얹는다. 004가 먼저 병합되어야 한다

---

## Phase 1: Setup (환경·계약 정합)

**Purpose**: 수집 환경을 갖추고, 004가 요구한 계약과 001의 데이터 모형이 어긋나지 않게 맞춘다.

- [x] T001 [P] [DE] 수집 환경과 DATA 파트 규칙 정비 (`data/pyproject.toml`, `data/requirements.txt`, `data/.env.example`, `data/.gitignore`, `data/CLAUDE.md`) — S15P21E205-176
  - 의존성: KRX OPEN API HTTP 클라이언트·pyarrow·PostgreSQL 드라이버 추가, pykrx 제거 (research.md 결정 1)
  - `.env.example`에 `KRX_API_KEY`·`DATABASE_URL`을 문서화한다. 실제 키는 `.env`에만 두고 코드에 하드코딩하지 않는다
  - `data/CLAUDE.md`: 원천을 pykrx에서 KRX OPEN API로, 컬럼 표준에 `value`(거래대금, 원)를 추가한다 (research.md 결정 1·6). **DATA 파트 합의가 선행되어야 한다**
  - `raw/`·`tests/` 디렉터리를 만들고 `.gitignore`에 `raw/`가 있는지 확인한다
- [x] T002 [DOCS] 004 시장 자료 입력 계약과 001 데이터 모형 정합 (`specs/001-etf-market-data/{data-model.md,contracts/market-dataset.md,spec.md}`, `specs/README.md`) — S15P21E205-176
  - **열 이름 정합 (2026-09-11 확정)**: 001 기준(`date`·`dataset_id`·`adjusted_close`·`price_carried_forward`)으로 통일하고 004의 `market-data-input.md`를 그에 맞춘다. 004 계약이 "이름은 제안이며 의미가 우선한다. 001이 다른 이름을 쓰면 provider 매핑만 바꾼다"고 허용했고 004는 아직 구현 전이라 문서 수정만으로 끝난다. 메시지 계약 픽스처와 ADR-0002·0003은 고치지 않는다
  - **의미 충돌 해소 (2026-09-11 확정)**: 004는 "결측은 행 부재로 표현하고 워커는 결측 거래일을 만나면 `DATA_INSUFFICIENT`로 실패한다"고 하고, 001의 임시 동작은 거래정지일에 직전 종가를 채운다. 정지일에도 행을 만들고 `trading_suspended` 열로 표시해 해소한다. OHLC 네 값을 모두 직전 종가로 채우고 `volume`·`value`는 0이다. `trading_suspended`(원인)와 `price_carried_forward`(처리)를 둘 다 둔다. 진짜 결측에는 행을 만들지 않으므로 004의 규칙이 유지된다
  - 분배금 이벤트에 004가 요구한 `ex_date`(배당락일)와 `pay_date`(지급일)를 모두 담도록 `corporate_action`을 고친다. 현재 data-model.md에는 날짜가 하나뿐이다
  - 자료 버전에 004가 요구한 `source` 열을 추가한다
  - **Flyway 버전을 타임스탬프 방식으로 전환한다 (2026-09-11 확정)**. 형식은 `V{YYYYMMDDHHMMSS}__설명.sql`이며 적용된 `V1`~`V3`은 그대로 둔다. `backend/CLAUDE.md`에 규칙을 반영하고, 004·007·014 문서의 V 번호 표기가 갱신 대상임을 `specs/README.md`에 남긴다. 001은 다른 spec의 문서를 직접 고치지 않는다

**Checkpoint**: 004와 열·의미가 맞고 마이그레이션 번호가 겹치지 않는다.

---

## Phase 2: Foundational (모든 스토리가 의존)

- [ ] T003 [BE] 자료 저장 스키마와 자료 버전 불변 규칙 (`backend/src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__etf_market_data.sql`, `backend/src/test/java/.../sector/EtfMarketDataSchemaIT.java`) — S15P21E205-177
  - 관계형 6개: `sector`, `representative_etf`, `sector_etf_map_version`, `sector_etf_map_entry`, `dataset_version`, `reconciliation_record`
  - 시계열 5개: `daily_price`, `corporate_action`, `daily_index`, `data_gap`, `suspension_period`. `daily_price` PK는 `(dataset_id, instrument_id, date)`이고 004가 요구한 조회 인덱스 `(instrument_id, dataset_id, date)`를 둔다. `daily_price`에 `trading_suspended`를, `corporate_action`에 `ex_date`·`pay_date`를, `dataset_version`에 `source`를 담는다 (T002 확정)
  - 불변식: `(map_version_id, sector_id)` UNIQUE, `leveraged`·`inverse` true 거부, `effective_to IS NULL` 행 최대 하나, `cause`↔`resolution` 조합 제한 (data-model.md)
  - 발행된 `dataset_version` 행과 딸린 시계열 행의 UPDATE·DELETE를 거부한다 (FR-017). quickstart 시나리오 3이 이 거부를 확인한다
  - 테스트는 `PostgresTestSupport`를 상속한다. Docker가 꺼져 있으면 skip되므로 `skipped` 수를 확인한다
- [ ] T004 [DE] 자료 버전 발행과 서비스 저장소 적재 계층 (`data/src/data_pipeline/{dataset_version,repository}.py`, `data/tests/{test_dataset_version,test_repository}.py`) — S15P21E205-177
  - `dataset_id`는 `KRX_DAILY_{YYYYMMDD}` 형식이다. `data_deadline`·`collected_at`·`last_success_at`·`complete`·`failure_reason`·`is_sample`·`source`를 기록한다 (research.md 결정 4)
  - 적재 순서를 강제한다. 자료 버전 행을 먼저 만든 뒤 시계열을 넣고, `raw → processed → PostgreSQL` 한 방향만 허용한다 (research.md 결정 8)
  - 발행된 버전에 다시 적재하려는 시도를 거부하는 테스트를 포함한다

**Checkpoint**: 저장소와 자료 버전 체계가 준비되었다. 사용자 스토리를 병렬로 시작할 수 있다.

---

## Phase 3: User Story 1 - 섹터 이름만 확인하고 진단을 시작한다 (Priority: P1) 🎯 MVP

**목표**: 사용자가 섹터 이름과 그에 연결된 대표 ETF 이름만 확인하고 분석을 시작한다.

**Independent Test**: 섹터 목록을 조회해 모든 섹터에 대표 ETF 하나가 연결되어 있고 상장일·기준 지수가 등록되어 있는지 확인한다. 화면 없이 대응표만으로 검증할 수 있다.

- [ ] T005 [US1] [DE] 섹터-ETF 대응표 파이프라인과 예시 대응표 (`data/src/data_pipeline/sector_map.py`, `data/scripts/ingest_sector_map.py`, `specs/001-etf-market-data/contracts/fixtures/sample-sector-map.json`, `data/tests/test_sector_map.py`) — S15P21E205-178
  - 항목은 섹터 이름·대표 ETF 이름·종목 식별자·상장일·기준 지수·운용사다. 국내 상장 비레버리지·비인버스 ETF만 허용한다 (FR-001)
  - 버전 발행: 내용이 바뀌면 새 버전을 만들고 기존 버전을 고치지 않는다 (FR-003)
  - 진입점은 `--sample`·`--map-version` 인자를 받고 재실행이 멱등하다 (`data/CLAUDE.md`)
  - 예시 대응표는 PD-01 확정 전까지 쓰며 `isSample` 표시가 붙는다 (FR-002)
  - 테스트: 섹터마다 대표 ETF 정확히 하나, 레버리지·인버스 거부, 상장일·기준 지수 필수
- [ ] T006 [US1] [BE] 섹터 목록 조회 API (`sector/domain/model/{Sector,RepresentativeEtf}.java`, `sector/domain/vo/SectorCode.java`, `sector/infrastructure/persistence/{SectorJpaEntity,RepresentativeEtfJpaEntity,SectorEtfMapJpaEntity,SectorMapper,SectorRepositoryImpl}.java`, `sector/application/{repository/SectorRepository,service/SectorService,result/SectorListResult}.java`, `sector/presentation/{controller/SectorController,controller/SectorExceptionHandler,response/SectorListResponse}.java`, `backend/src/test/java/.../sector/SectorApiIT.java`) — S15P21E205-178
  - 도메인 모델에 Spring·JPA·Jackson import를 두지 않는다. `*Mapper`는 정적 `toEntity`/`toDomain`이다 (ADR-0001)
  - 저장소 인터페이스에 **수정·생성 메서드를 두지 않는다.** 대응표는 파이프라인이 소유한다 (research.md 결정 7)
  - `GET /sectors`가 `mapVersion`·`isSample`을 함께 반환한다 (contracts/sector-api.md)
  - 대응표가 발행되지 않았으면 빈 배열이 아니라 `SECTOR_MAP_NOT_READY`(409)로 구분한다 (원칙 VI). 없는 섹터 코드는 `SECTOR_NOT_FOUND`(404)
  - 테스트: 응답이 계약 예시와 동치, `representativeEtf`가 없는 섹터 0개(SC-001)
  - `ArchitectureTest` 통과를 확인한다 (`backend/CLAUDE.md` 새 도메인 체크리스트)

**Checkpoint**: 예시 대응표로 `GET /sectors`가 동작한다. quickstart 시나리오 1을 실행할 수 있고, 002 섹터 선택 화면이 이 응답으로 개발을 시작할 수 있다.

---

## Phase 4: User Story 2 - 실제 상장 이후 가격과 기업행위로 계산된 결과를 받는다 (Priority: P1)

**목표**: 백테스트가 실제 상장 이후 가격과 기업행위가 반영된 자료로 계산된다.

**Independent Test**: 대표 ETF 하나를 골라 상장 전 날짜·분배금 지급일·분할일 전후의 원시 가격과 조정 자료를 조회해 운용사 공시와 대조한다.

- [ ] T007 [US2] [DE] 일봉·지수 수집과 이용 한도 처리 (`data/src/data_pipeline/{krx,index}.py`, `data/scripts/{ingest_krx,ingest_index,verify_listing_boundary}.py`, `data/tests/{test_krx_ingest,test_index}.py`) — S15P21E205-179
  - KRX OPEN API 연동으로 `fetch_ohlcv` 스캐폴딩을 완성한다. 컬럼을 `date, open, high, low, close, volume, value`로 표준화한다
  - 상장 전 합성 이력이나 미래 자료로 빈 값을 채우지 않는다. 상장일·상장폐지일을 함께 확보한다 (FR-005)
  - 이용 한도에 걸려 그날 수집을 끝내지 못하면 중단하고 `complete = false`와 `failure_reason`을 남긴다 (FR-014). `--simulate-quota-exhausted`로 재현한다
  - KOSPI 시리즈 일별 자료의 확보 기간이 비교 대상 대표 ETF의 상장 이후 전체를 포함하는지 검사한다 (FR-009)
  - `verify_listing_boundary.py`는 상장 전 가격 행을 센다. quickstart 시나리오 2가 쓴다
  - 테스트: 컬럼 표준 변환, 상장 전 행 부재(SC-003), 재실행 멱등성
- [ ] T008 [US2] [DE] 기업행위 반영 조정 가격과 자료 부족·정지 구간 (`data/src/data_pipeline/{corporate_actions,gaps}.py`, `data/tests/{test_corporate_actions,test_gaps}.py`) — S15P21E205-179
  - 분배금·분할 수집과 소급 조정 계수 산출. `adjusted_close`와 `adjustment_factor`를 함께 저장한다 (research.md 결정 2)
  - 운용보수를 다시 차감하지 않는다. 이미 가격에 반영되어 있다 (FR-004)
  - 자료 부족 구간의 `reason`은 `AMBIGUOUS_PRICE`·`MISSING_CORPORATE_ACTION`·`RECONCILIATION_UNKNOWN`·`INDEX_HISTORY_SHORT`다. **`SUSPENDED_TOO_LONG`은 PD-19·PD-20 확정 전까지 쓰지 않는다** (FR-006)
  - 거래정지는 길이와 무관하게 직전 종가를 평가에만 쓰고 표시를 남기며, 정지 구간을 `suspension_period`에 시작일·종료일·거래일 수로 기록한다. 이 기록이 T015의 입력이 된다
  - 테스트: 계수 계산, 과거 두 시점의 비율이 이후 계수에 영향받지 않음, 보수 중복 차감 없음
- [ ] T009 [US2] [DE] 공시 대조와 원인별 처리 (`data/src/data_pipeline/reconciliation.py`, `data/scripts/reconcile.py`, `data/tests/test_reconciliation.py`) — S15P21E205-179
  - 표본은 상장 첫날·모든 분배금 지급일·모든 분할일이다. 평범한 거래일은 넣지 않는다 (FR-016)
  - 불일치가 나도 개별 값을 수정하지 않는다. 수집·가공 오류는 새 버전 재발행, 정의 차이는 기록만, 원인 불명은 구간 제외 (FR-017)
  - 테스트: 표본 구성 검증, `cause`↔`resolution` 조합 제한, SC-002 판정
- [ ] T010 [US2] [BE] 공통 평가 기간 산식과 조회 API (`specs/001-etf-market-data/contracts/fixtures/evaluation-window-cases.json`, `worker/src/worker/evaluation_window.py`, `worker/tests/test_evaluation_window.py`, `sector/application/service/EvaluationWindowService.java`, `sector/presentation/controller/SectorController.java`, `backend/src/test/java/.../sector/EvaluationWindowTest.java`) — S15P21E205-180
  - 픽스처를 먼저 만든다. `contracts/evaluation-window.md` 「경계 동작」 7가지를 모두 담는다
  - 워커와 백엔드가 **같은 픽스처**를 읽는다. 한쪽만 고치면 실패해야 한다 (contracts/README.md 「계약 변경 순서」)
  - 검증용 잠금 구간을 빼지 않는다. 평가 분할은 007이 맡는다 (FR-008)
  - `GET /sectors/evaluation-window`는 `evaluable: false`를 오류가 아닌 정상 응답으로 반환한다
  - 양쪽을 한 묶음에 둔 이유: 계약이 두 구현을 묶고 있어 한쪽만 병합하면 픽스처가 반쪽이 된다
- [ ] T011 [US2] [BE] 워커 자료 공급자 연결과 자료 버전 값 규칙 (`worker/src/worker/{market_data,engine_runner}.py`, `worker/tests/test_repository_provider.py`, `docs/specs/backtest-messaging/{README.md,examples/*.json,generate_fixtures.py}`) — S15P21E205-180
  - **선행**: 004 T004(`S15P21E205-134`)가 `market_data.py`에 공급자 골격과 CSV 픽스처를 먼저 넣는다
  - 저장소를 읽는 공급자를 추가하고 `engine_runner.py:68`의 기본값을 그것으로 바꾼다. `YFinanceDataProvider` 클래스는 남기되 실제 Job 처리 경로에서 쓰지 않는 임시 수단임을 주석에 적는다 (research.md 결정 1)
  - `metadata`에 `datasetId`·`dataDeadline`·`carriedForwardDates`·`dataGaps`·`mapVersion`·`provisionalSuspensionHandling`을 담는다 (contracts/market-dataset.md)
  - 워밍업 자료를 거래일 기준으로 앞에서 읽는다. 달력 일수 방식은 연휴가 긴 해에 모자란다
  - `datasetId` 값 규칙을 `KRX_DAILY_V1`에서 `KRX_DAILY_{YYYYMMDD}`로 바꾼다. **스키마는 그대로이므로 픽스처 값만 바뀌고 round-trip 테스트는 통과해야 한다** (research.md 결정 4)
  - 테스트: 같은 `datasetId`로 두 번 조회 시 값 동일(SC-004), 불일치 시 `MarketDataError`, 상장 전 구간 요청 시 오류, 정지일 기록

**Checkpoint**: 백테스트가 001의 자료로 실행된다. quickstart 시나리오 2~6을 실행할 수 있다.

---

## Phase 5: User Story 3 - 대표 상품 정보에서 근거와 한계를 확인한다 (Priority: P2)

**목표**: 사용자가 결과의 근거와 한계를 '대표 상품 정보'에서 확인한다.

**Independent Test**: 대표 ETF 하나의 대표 상품 정보를 열어 필수 항목이 모두 보이고, 과거 구성 이력이 없는 상품에 현재 구성 기준 표시가 붙는지 확인한다.

- [ ] T012 [US3] [DE] 대표 상품 정보 산출 (`data/src/data_pipeline/instrument_profile.py`, `data/tests/test_instrument_profile.py`) — S15P21E205-181
  - 기준 지수·지수 방법론 요약·상장일·구성종목 중복·최근 3개월 일평균 거래대금·자료 출처·생존 상품 한계를 담는다 (FR-015)
  - 유동성은 `daily_price.value` 기준이다. 거래량과 종가를 곱한 추정을 쓰지 않는다
  - 과거 구성 이력이 없으면 `holdings_as_of_current`를 true로 표시하고, 당시 중복·집중도를 재현했다고 표현하지 않는다
  - **추적 차이는 만들지 않는다.** 계산 정의가 PD-21로 남아 있다 (Out of Scope)
- [ ] T013 [US3] [BE] 대표 상품 정보 조회 API (`sector/infrastructure/persistence/{InstrumentProfileJpaEntity,InstrumentProfileMapper}.java`, `sector/application/service/SectorService.java`, `sector/presentation/{controller/SectorController,controller/SectorExceptionHandler,response/InstrumentProfileResponse}.java`, `backend/src/test/java/.../sector/InstrumentProfileApiIT.java`) — S15P21E205-181
  - `GET /sectors/{code}/instrument-profile` (contracts/sector-api.md)
  - `avgDailyValue3m`에 `@JsonSerialize(using = PlainBigDecimalSerializer.class)`를 **필드마다** 붙인다. 웹 ObjectMapper에 전역 등록하면 기존 `MarketResponse.value` 계약이 깨진다 (`backend/CLAUDE.md`)
  - `INSTRUMENT_PROFILE_NOT_READY`(409)를 추가한다
  - 테스트: 필수 항목 전부 존재, `asOfCurrentComposition` 표시, `sourceAttribution` 존재, **추적 차이 필드 부재**

**Checkpoint**: 세 사용자 스토리가 모두 독립적으로 동작한다.

---

## Phase 6: 결정 항목 확정 (본선과 독립)

- [ ] T014 [DE] PD-01 대표 ETF 확정과 예시 자료 교체 (`specs/001-etf-market-data/pd01-etf-candidates.md`, `specs/README.md`, `specs/001-etf-market-data/spec.md`, `data/scripts/ingest_sector_map.py`) — Jira 미발급
  - **선행 조건은 T001뿐이다.** 본선(T003~T013)과 의존 관계가 없어 병행할 수 있다
  - 자료 이용 승인을 신청하고 진행 상태를 기록한다
  - 섹터마다 후보 3~5개를 골라 확보 가능 기간·기업행위 이력 완전성·최근 3개월 일평균 거래대금을 실제 조회로 채운 비교표를 만든다. **후보끼리 비교하는 것이며 예시 자료 결과와 비교하는 것이 아니다**
  - 확정한 대응표를 새 버전으로 발행하고 `is_sample`·`isSample`을 false로 바꾼다. `specs/README.md`의 PD-01과 spec의 결정 항목 표를 갱신한다
- [ ] T015 [DE] PD-19·PD-20 거래정지 규칙 확정 (`data/scripts/suspension_stats.py`, `data/src/data_pipeline/gaps.py`, `specs/README.md`, `specs/001-etf-market-data/{spec.md,contracts/market-dataset.md}`, `worker/src/worker/market_data.py`) — Jira 미발급
  - **선행 조건은 T008이다.** 본선이 쌓아 둔 `suspension_period` 기록이 입력이다
  - 정지 사례의 길이 분포를 집계한다: 건수, 중앙값, 최댓값, ETF별 분포
  - 처리 방식 B(정지 이후만)·C(정지 이전만)·D(후보 제외) 각각이 공통 평가 기간을 얼마나 줄이는지 계산한다. 손실 규모를 수치로 비교한다 (research.md 「PD-19·PD-20을 나중에 정하기 위한 준비」)
  - 확정한 규칙을 FR-006과 계약에 반영하고 `gaps.py`의 `SUSPENDED_TOO_LONG` 기록을 켠다. `provisionalSuspensionHandling` 표시를 걷는다
  - 정지 구간을 빼고 앞뒤를 이어 붙이는 방식은 후보에서 제외된 상태다 (원칙 VI)

---

## Phase 7: Polish

- [ ] T016 [DOCS] 검증 기록과 문서 마무리 (`docs/guides/etf-market-data-verification-2026-09.md`, `specs/001-etf-market-data/quickstart.md`, `README.md`, `docs/specs/market-api.md`) — Jira 미발급
  - quickstart 시나리오 1~7을 순서대로 실행하고 결과를 검증 기록 문서에 남긴다
  - constitution 「기능·계산 확인」이 요구하는 수기 대조를 한 건 수행한다. 분배금 지급일 하나를 골라 운용사 공시의 주당 분배금과 `corporate_action.amount`를 눈으로 확인하고 `reconciliation_record`에 남긴다
  - `README.md`의 "현재 구현 상태"에서 자료 원천이 바뀐 사실을 반영한다
  - `docs/specs/market-api.md`와 `docs/adr/0004`가 001의 결정과 어긋나는 부분을 확인해 갱신 대상 목록을 남긴다. `specs/README.md`가 이미 갱신 대상으로 기록했으므로 001 범위에서 직접 고치지는 않는다
  - 세 파트 검증 명령을 모두 실행한다. `backend`의 DB 테스트는 Docker가 꺼져 있으면 skip되므로 `skipped` 수를 확인한다

---

## Dependencies & Execution Order

```
T001 ─┬─ T002 ─ T003 ─┬─ T004 ─┬─ T005 ─ T006
      │               │        ├─ T007 ─┬─ T008 ─ T015
      │               │        │        └─ T009
      │               │        ├─ T010
      │               │        ├─ T011  ← 004 T004 선행
      │               │        └─ T012 ─ T013
      │               │                        ↓
      │               │                      T016
      └─ T014  (본선과 독립)
```

### 묶음 의존

- **T002 → T003**: 열 이름과 의미가 정해져야 스키마를 쓸 수 있다
- **T003 → 나머지 전부**: 저장소가 없으면 어느 묶음도 검증할 수 없다
- **T007 → T008 → T015**: 가격이 있어야 조정 가격을 만들고, 정지 기록이 쌓여야 규칙을 정한다
- **T012 → T013**: 산출값이 없으면 API가 409만 반환한다
- **T011은 004 T004에 의존한다.** 같은 파일을 고치므로 004가 먼저 병합되어야 한다
- **T014는 T001 이후 언제든** 병행할 수 있다

### 병렬 기회

- T001과 T002는 다른 파일이라 병렬
- T004 이후 DATA 파트(T005·T007·T009·T012)와 BACK 파트(T006·T010·T013)가 갈라져 동시 진행
- T014는 전 구간에 걸쳐 병행

---

## Implementation Strategy

### MVP First

T001 → T002 → T003 → T004 → T005 → T006. 예시 대응표로 `GET /sectors`가 동작하고 quickstart 시나리오 1로 검증한다. **002 섹터 선택 화면의 개발 시작점이다.**

### Incremental Delivery

1. **T006까지** → 002가 섹터 목록으로 화면을 만든다
2. **T011까지** → 004 백테스트가 실제 자료로 돌고 003이 기간 안내를 만든다. Yahoo Finance 의존이 사라진다
3. **T013까지** → 005가 결과의 근거 패널을 붙인다
4. **T014** → 예시 자료 표시가 사라지고 실제 대표 ETF로 동작한다
5. **T015** → 거래정지 처리가 임시에서 확정으로 바뀐다

### Parallel Team Strategy

- **DATA**: T001, T004, T005, T007, T008, T009, T012, T014, T015
- **BACK**: T003, T006, T010, T011, T013
- **공통**: T002, T016

T003의 스키마가 두 파트의 접점이므로 먼저 합의하고 고정한다.

---

## Notes

- **Jira 이슈가 아직 없다.** constitution v3.0.0이 "묶음의 Branch·MR을 만들기 전에 Task를 먼저 생성하고 그 Ticket 번호로 브랜치를 만든다"고 정했으므로, 착수 전에 Epic·Story·Task를 발급하고 이 문서의 `— Jira 미발급`을 키로 바꾼다
- **T001의 `data/CLAUDE.md` 변경은 DATA 파트 합의가 선행되어야 한다.** 원천과 컬럼 표준을 바꾸는 일이라 한 사람이 정할 수 없다
- **T002는 004와의 접점이다.** 004가 이미 `market-data-input.md`로 요구 사항을 문서화하고 Jira 이슈까지 발급한 상태이므로, 001이 그 계약에 맞추는 방향으로 정합한다
- **마이그레이션 번호 V3을 네 spec(001·004·007·014)이 함께 적어 두었다.** 001은 V7을 쓰되 나머지 재배정 결론은 T002에서 남긴다
- **T015 확정 전 임시 동작을 함부로 바꾸지 않는다.** `suspension_period` 기록이 결정의 입력이므로, 정지를 제외하도록 미리 바꾸면 근거가 사라진다
- 각 묶음의 MR 본문 "주요 변경 사항"에 그 묶음이 담은 구현 메모 항목을 나열해 추적한다 (constitution v3.0.0)
