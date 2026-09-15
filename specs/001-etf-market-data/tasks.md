# Tasks: 대표 ETF 자료 확보

**Input**: `specs/001-etf-market-data/` — plan.md, spec.md(Clarifications 5건), research.md(결정 1~8), data-model.md, contracts/{sector-api,evaluation-window,market-dataset}.md, quickstart.md(시나리오 1~8)

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
- **마이그레이션 번호**: T002에서 **타임스탬프 방식(`V{YYYYMMDDHHMMSS}__설명.sql`)으로 전환**했다(2026-09-11 확정, `backend/CLAUDE.md`). 여러 spec이 같은 번호를 적어 두던 충돌이 이로써 사라진다. 저장소에 적용된 `V1`~`V4`는 그대로 두며, Flyway가 버전을 숫자로 정렬하므로 타임스탬프가 뒤에 온다. 001이 쓰는 파일은 `V20260911114500__etf_market_data.sql`이다
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

- [x] T003 [BE] 자료 저장 스키마와 자료 버전 불변 규칙 (`backend/src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__etf_market_data.sql`, `backend/src/test/java/.../sector/EtfMarketDataSchemaIT.java`) — S15P21E205-177
  - 관계형 6개: `sector`, `representative_etf`, `sector_etf_map_version`, `sector_etf_map_entry`, `dataset_version`, `reconciliation_record`
  - 시계열 5개: `daily_price`, `corporate_action`, `daily_index`, `data_gap`, `suspension_period`. `daily_price` PK는 `(dataset_id, instrument_id, date)`이고 004가 요구한 조회 인덱스 `(instrument_id, dataset_id, date)`를 둔다. `daily_price`에 `trading_suspended`를, `corporate_action`에 `ex_date`·`pay_date`를, `dataset_version`에 `source`를 담는다 (T002 확정)
  - 불변식: `(map_version_id, sector_id)` UNIQUE, `leveraged`·`inverse` true 거부, `effective_to IS NULL` 행 최대 하나, `cause`↔`resolution` 조합 제한 (data-model.md)
  - 발행된 `dataset_version` 행과 딸린 시계열 행의 UPDATE·DELETE를 거부한다 (FR-017). quickstart 시나리오 3이 이 거부를 확인한다
  - 테스트는 `PostgresTestSupport`를 상속한다. Docker가 꺼져 있으면 skip되므로 `skipped` 수를 확인한다
- [x] T004 [DE] 자료 버전 발행과 서비스 저장소 적재 계층 (`data/src/data_pipeline/{dataset_version,repository}.py`, `data/tests/{test_dataset_version,test_repository}.py`) — S15P21E205-177
  - `dataset_id`는 `KRX_DAILY_{YYYYMMDD}` 형식이다. `data_deadline`·`collected_at`·`last_success_at`·`complete`·`failure_reason`·`is_sample`·`source`를 기록한다 (research.md 결정 4)
  - 적재 순서를 강제한다. 자료 버전 행을 먼저 만든 뒤 시계열을 넣고, `raw → processed → PostgreSQL` 한 방향만 허용한다 (research.md 결정 8)
  - 발행된 버전에 다시 적재하려는 시도를 거부하는 테스트를 포함한다

**Checkpoint**: 저장소와 자료 버전 체계가 준비되었다. 사용자 스토리를 병렬로 시작할 수 있다.

---

## Phase 3: User Story 1 - 섹터 이름만 확인하고 진단을 시작한다 (Priority: P1) 🎯 MVP

**목표**: 사용자가 섹터 이름과 그에 연결된 대표 ETF 이름만 확인하고 분석을 시작한다.

**Independent Test**: 섹터 목록을 조회해 모든 섹터에 대표 ETF 하나가 연결되어 있고 상장일·기준 지수가 등록되어 있는지 확인한다. 화면 없이 대응표만으로 검증할 수 있다.

- [x] T005 [US1] [DE] 섹터-ETF 대응표 파이프라인과 예시 대응표 (`data/src/data_pipeline/sector_map.py`, `data/scripts/ingest_sector_map.py`, `specs/001-etf-market-data/contracts/fixtures/sample-sector-map.json`, `data/tests/test_sector_map.py`) — S15P21E205-178
  - 항목은 섹터 이름·대표 ETF 이름·종목 식별자·상장일·기준 지수·운용사다. 국내 상장 비레버리지·비인버스 ETF만 허용한다 (FR-001)
  - 버전 발행: 내용이 바뀌면 새 버전을 만들고 기존 버전을 고치지 않는다 (FR-003)
  - 진입점은 `--sample`·`--map-version` 인자를 받고 재실행이 멱등하다 (`data/CLAUDE.md`)
  - 예시 대응표는 PD-01 확정 전까지 쓰며 `isSample` 표시가 붙는다 (FR-002)
  - 테스트: 섹터마다 대표 ETF 정확히 하나, 레버리지·인버스 거부, 상장일·기준 지수 필수
- [x] T006 [US1] [BE] 섹터 목록 조회 API (`sector/domain/model/{Sector,RepresentativeEtf}.java`, `sector/domain/vo/SectorCode.java`, `sector/infrastructure/persistence/{SectorJpaEntity,RepresentativeEtfJpaEntity,SectorEtfMapJpaEntity,SectorMapper,SectorRepositoryImpl}.java`, `sector/application/{repository/SectorRepository,service/SectorService,result/SectorListResult}.java`, `sector/presentation/{controller/SectorController,controller/SectorExceptionHandler,response/SectorListResponse}.java`, `backend/src/test/java/.../sector/SectorApiIT.java`) — S15P21E205-178
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

- [x] T007 [US2] [DE] 일봉·지수 수집과 이용 한도 처리 (`data/src/data_pipeline/{krx,index}.py`, `data/scripts/{ingest_krx,ingest_index,verify_listing_boundary}.py`, `data/tests/{test_krx_ingest,test_index}.py`) — S15P21E205-179
  - KRX OPEN API 연동으로 `fetch_ohlcv` 스캐폴딩을 완성한다. 컬럼을 `date, open, high, low, close, volume, value`로 표준화한다
  - 상장 전 합성 이력이나 미래 자료로 빈 값을 채우지 않는다. 상장일·상장폐지일을 함께 확보한다 (FR-005)
  - 이용 한도에 걸려 그날 수집을 끝내지 못하면 중단하고 `complete = false`와 `failure_reason`을 남긴다 (FR-014). `--simulate-quota-exhausted`로 재현한다
  - KOSPI 시리즈 일별 자료의 확보 기간이 비교 대상 대표 ETF의 상장 이후 전체를 포함하는지 검사한다 (FR-009)
  - `verify_listing_boundary.py`는 상장 전 가격 행을 센다. quickstart 시나리오 2가 쓴다
  - 테스트: 컬럼 표준 변환, 상장 전 행 부재(SC-003), 재실행 멱등성
- [x] T008 [US2] [DE] 기업행위 반영 조정 가격과 자료 부족·정지 구간 (`data/src/data_pipeline/{corporate_actions,gaps}.py`, `data/tests/{test_corporate_actions,test_gaps}.py`) — S15P21E205-179
  - 분배금·분할 수집과 소급 조정 계수 산출. `adjusted_close`와 `adjustment_factor`를 함께 저장한다 (research.md 결정 2)
  - 운용보수를 다시 차감하지 않는다. 이미 가격에 반영되어 있다 (FR-004)
  - 자료 부족 구간의 `reason`은 `AMBIGUOUS_PRICE`·`MISSING_CORPORATE_ACTION`·`RECONCILIATION_UNKNOWN`·`INDEX_HISTORY_SHORT`다. **`SUSPENDED_TOO_LONG`은 PD-19·PD-20 확정 전까지 쓰지 않는다** (FR-006)
  - 거래정지는 길이와 무관하게 직전 종가를 평가에만 쓰고 표시를 남기며, 정지 구간을 `suspension_period`에 시작일·종료일·거래일 수로 기록한다. 이 기록이 T015의 입력이 된다
  - 테스트: 계수 계산, 과거 두 시점의 비율이 이후 계수에 영향받지 않음, 보수 중복 차감 없음
- [x] T009 [US2] [DE] 공시 대조와 원인별 처리 (`data/src/data_pipeline/reconciliation.py`, `data/scripts/reconcile.py`, `data/tests/test_reconciliation.py`) — S15P21E205-179
  - 표본은 상장 첫날·모든 분배금 지급일·모든 분할일이다. 평범한 거래일은 넣지 않는다 (FR-016)
  - 불일치가 나도 개별 값을 수정하지 않는다. 수집·가공 오류는 새 버전 재발행, 정의 차이는 기록만, 원인 불명은 구간 제외 (FR-017)
  - 테스트: 표본 구성 검증, `cause`↔`resolution` 조합 제한, SC-002 판정
- [x] T010 [US2] [BE] 공통 평가 기간 산식과 조회 API (`specs/001-etf-market-data/contracts/fixtures/evaluation-window-cases.json`, `worker/src/worker/evaluation_window.py`, `worker/tests/test_evaluation_window.py`, `sector/application/service/EvaluationWindowService.java`, `sector/presentation/controller/SectorController.java`, `backend/src/test/java/.../sector/EvaluationWindowTest.java`) — S15P21E205-180
  - 픽스처를 먼저 만든다. `contracts/evaluation-window.md` 「경계 동작」 7가지를 모두 담는다
  - 워커와 백엔드가 **같은 픽스처**를 읽는다. 한쪽만 고치면 실패해야 한다 (contracts/README.md 「계약 변경 순서」)
  - 검증용 잠금 구간을 빼지 않는다. 평가 분할은 007이 맡는다 (FR-008)
  - `GET /sectors/evaluation-window`는 `evaluable: false`를 오류가 아닌 정상 응답으로 반환한다
    - **2026-09-11 엔드포인트 보류.** 산식 입력 다섯 가지 중 네 가지를 백엔드가 읽을 경로가 없다. 특히 `warmupTradingDays`는 `evaluation-window.md` 입력표가 "전략 등록표(003 소유)에서 온다. **001은 이 값을 정하지 않고 받는다**"고 못 박아 001이 만들 수 없다. `delistedOn`도 대응표가 현재 상장 상품만 다루기로 해 도메인 모델에 없다. 값을 지어내는 표면을 만드느니 붙이지 않는 편이 원칙 VI에 맞다.
    - 산식과 공유 픽스처는 만들었다. `EvaluationWindowService`는 입력을 전부 인자로 받는 의존성 없는 산식이라, 재료가 생기면 **이 클래스를 고치지 않고 호출부만 붙이면 된다.**
    - 붙일 때 필요한 것: 003의 전략 등록표(워밍업 값), `RepresentativeEtf`에 `delistedOn` 필드, `data_gap`·`daily_index`·`dataset_version` 조회 경로, 그리고 `SectorExceptionHandler`의 `IllegalArgumentException` → 500 매핑 정리(빈 후보 목록은 계약이 400을 요구한다).
  - 양쪽을 한 묶음에 둔 이유: 계약이 두 구현을 묶고 있어 한쪽만 병합하면 픽스처가 반쪽이 된다
- [x] T011 [US2] [BE] 워커 자료 공급자 연결과 자료 버전 값 규칙 (`worker/src/worker/{market_data,engine_runner}.py`, `worker/tests/test_repository_provider.py`, `docs/specs/backtest-messaging/{README.md,examples/*.json,generate_fixtures.py}`) — S15P21E205-180
  - **선행**: 004 T004(`S15P21E205-134`)가 `market_data.py`에 공급자 골격과 CSV 픽스처를 먼저 넣는다
  - 저장소를 읽는 공급자를 추가하고 `engine_runner.py:68`의 기본값을 그것으로 바꾼다. `YFinanceDataProvider` 클래스는 남기되 실제 Job 처리 경로에서 쓰지 않는 임시 수단임을 주석에 적는다 (research.md 결정 1)
  - `metadata`에 `datasetId`·`dataDeadline`·`carriedForwardDates`·`dataGaps`·`mapVersion`·`provisionalSuspensionHandling`을 담는다 (contracts/market-dataset.md)
  - 워밍업 자료를 거래일 기준으로 앞에서 읽는다. 달력 일수 방식은 연휴가 긴 해에 모자란다
  - `datasetId` 값 규칙을 `KRX_DAILY_V1`에서 `KRX_DAILY_{YYYYMMDD}`로 바꾼다. **스키마는 그대로이므로 픽스처 값만 바뀌고 round-trip 테스트는 통과해야 한다** (research.md 결정 4)
    - **2026-09-11 보류.** 픽스처의 값은 백엔드 상수 `ExecutionPolicy.MARKET_DATA`에서 나오고 `EngineInputFixtureTest`가 둘의 동치를 단언한다. 픽스처만 고치면 그 테스트가 깨진다. 그렇다고 상수에 `KRX_DAILY_20260911`을 박으면 내일부터 거짓말이 된다 — 지금의 `KRX_DAILY_V1`은 누가 봐도 자리 표시인데 날짜가 박힌 값은 진짜 마감일처럼 보이면서 영원히 그 날에 멈춘다.
    - 값 규칙 자체는 이미 구현·검증되어 있다. 식별자를 **만드는** 쪽인 `data/src/data_pipeline/dataset_version.py`의 `build_dataset_id`가 규칙을 세우고, `DatasetVersion`이 식별자와 `data_deadline`의 불일치를 거부한다.
    - 올바른 해결은 백엔드가 값을 지어내지 않고 `dataset_version`에서 현재 유효한 묶음을 읽는 것이다. `ExecutionPolicy`의 `MARKET_DATA` 블록은 `dataVersion: null // 001 자료 계층 연동 전` 주석이 말하듯 통째로 자리 표시다. **004 연동 작업에서 푼다.**
  - 테스트: 같은 `datasetId`로 두 번 조회 시 값 동일(SC-004), 불일치 시 `MarketDataError`, 상장 전 구간 요청 시 오류, 정지일 기록

**Checkpoint**: 백테스트가 001의 자료로 실행된다. quickstart 시나리오 2~6을 실행할 수 있다.

---

## Phase 5: User Story 3 - 대표 상품 정보에서 근거와 한계를 확인한다 (Priority: P2)

**목표**: 사용자가 결과의 근거와 한계를 '대표 상품 정보'에서 확인한다.

**Independent Test**: 대표 ETF 하나의 대표 상품 정보를 열어 필수 항목이 모두 보이고, 과거 구성 이력이 없는 상품에 현재 구성 기준 표시가 붙는지 확인한다.

- [x] T012 [US3] [DE] 대표 상품 정보 산출 (`data/src/data_pipeline/instrument_profile.py`, `data/tests/test_instrument_profile.py`) — S15P21E205-181
  - 기준 지수·지수 방법론 요약·상장일·구성종목 중복·최근 3개월 일평균 거래대금·자료 출처·생존 상품 한계를 담는다 (FR-015)
  - 유동성은 `daily_price.value` 기준이다. 거래량과 종가를 곱한 추정을 쓰지 않는다
  - 과거 구성 이력이 없으면 `holdings_as_of_current`를 true로 표시하고, 당시 중복·집중도를 재현했다고 표현하지 않는다
  - **추적 차이는 만들지 않는다.** 계산 정의가 PD-21로 남아 있다 (Out of Scope)
  - **2026-09-13 확인**: 지수 방법론 요약과 구성종목을 주는 원천 서비스가 없다. 인증키로 직접 조회해 `etp/etf_pdf`·`etp/etf_comp_isu`·`idx/idx_comp_isu`·`idx/idx_base_info`가 모두 404임을 봤고, 되는 `etp/etf_bydd_trd`는 기초지수 이름과 순자산총액까지다. 그래서 상장일·기업행위와 같은 선례를 따라 파일 입력으로 받는다(`ingest_krx.py --instrument-profiles`). 원천이 나중에 제공하면 열 구성은 그대로 두고 입력만 수집으로 바꾼다
  - **적재 경로 (2026-09-13 결정)**: 별도 진입점을 만들지 않고 `repository.py`에 `load_instrument_profiles`를 더했다. 이 표는 대조 기록과 같은 bigint 외래 키 두 개 구조라 식별자 변환·적재 순서 강제·재적재 거부가 이미 그 모듈에 있고, 무엇보다 일평균 거래대금의 근거가 `daily_price.value`인데 적재 계층은 DB에서 시계열을 읽어 되돌리는 경로를 두지 않는다. 별도 스크립트로는 그 값을 얻을 방법이 없다
- [x] T013 [US3] [BE] 대표 상품 정보 조회 API (`backend/src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__instrument_profile.sql`, `sector/infrastructure/persistence/{InstrumentProfileJpaEntity,InstrumentProfileMapper}.java`, `sector/application/service/SectorService.java`, `sector/presentation/{controller/SectorController,controller/SectorExceptionHandler,response/InstrumentProfileResponse}.java`, `backend/src/test/java/.../sector/InstrumentProfileApiIT.java`) — S15P21E205-181
  - **`instrument_profile` 표를 이 묶음에서 만든다 (2026-09-13 확정).** T003이 만든 표 11개에 이 표가 빠져 있었고 어느 묶음도 맡고 있지 않았다. 열 구성은 data-model.md 「instrument_profile」 그대로이며 새로 설계하지 않는다. 파일명은 타임스탬프 방식을 따른다
  - `GET /sectors/{code}/instrument-profile` (contracts/sector-api.md)
  - `avgDailyValue3m`에 `@JsonSerialize(using = PlainBigDecimalSerializer.class)`를 **필드마다** 붙인다. 웹 ObjectMapper에 전역 등록하면 기존 `MarketResponse.value` 계약이 깨진다 (`backend/CLAUDE.md`)
  - `INSTRUMENT_PROFILE_NOT_READY`(409)를 추가한다
  - 테스트: 필수 항목 전부 존재, `asOfCurrentComposition` 표시, `sourceAttribution` 존재, **추적 차이 필드 부재**
  - **어느 행을 보여줄지 (2026-09-13 결정)**: 문서가 정해 두지 않아 여기서 정했다. 열린 대응표 버전을 대상으로 수집된 자료 버전 중 `complete = true`이고 `data_deadline`이 가장 늦은 것이다. 물러난 대응표를 대상으로 만든 값이 현재 목록 옆에 나오지 않게 하고(원칙 IV), 부분 갱신 자료를 완전한 자료로 표시하지 않기 위함이다(FR-014). 응답의 `isSample`은 그 **자료 버전**의 값이며 대응표의 `isSample`과 근거가 다르다
  - **없는 섹터 코드 판정**: 경로 변수로 `SectorCode`를 만들지 않는다. 만들면 형식 위반이 `IllegalArgumentException`이 되고 기존 처리기가 그것을 저장 자료 결함으로 보아 500으로 옮긴다. 열린 대응표 목록에서 같은 문자열을 찾기만 하므로 형식 위반도 `SECTOR_NOT_FOUND`(404)다
  - `instrument_profile`에 UPDATE·DELETE 거부 트리거를 걸지 않았다. FR-017이 불변으로 못 박은 대상은 자료 버전과 시계열이고 `reconciliation_record`도 같은 이유로 트리거가 없다. 재현은 `(etf_id, dataset_version_id)` UNIQUE가 지킨다

**Checkpoint**: 세 사용자 스토리가 모두 독립적으로 동작한다.

---

## Phase 6: 결정 항목 확정 (본선과 독립)

**2026-09-11: KRX OPEN API 키를 확보해 T014~T016의 착수 조건이 해소되었다.** 세 작업은 실자료 조회가 선행 조건이라 그때까지 Jira를 발급하지 않았는데, 키가 생겨 각각 Task로 발급했다(T014 `-186`, T015 `-187`, T016 `-188`). 착수 차례는 JT4(`-179`)가 수집 코드를 완성한 뒤 T014부터다. JT4가 만드는 조회 도구로 후보 비교를 하는 편이 자연스럽기 때문이다(2026-09-11 결정).

- [ ] T014 [DE] PD-01 대표 ETF 확정과 예시 자료 교체 (`specs/001-etf-market-data/pd01-etf-candidates.md`, `specs/README.md`, `specs/001-etf-market-data/spec.md`, `data/scripts/ingest_sector_map.py`) — S15P21E205-186
  - **선행 조건은 T001뿐이다.** 본선(T003~T013)과 의존 관계가 없어 병행할 수 있다
  - 자료 이용 승인을 신청하고 진행 상태를 기록한다
  - 섹터마다 후보 3~5개를 골라 확보 가능 기간·기업행위 이력 완전성·최근 3개월 일평균 거래대금을 실제 조회로 채운 비교표를 만든다. **후보끼리 비교하는 것이며 예시 자료 결과와 비교하는 것이 아니다**
  - 확정한 대응표를 새 버전으로 발행하고 `is_sample`·`isSample`을 false로 바꾼다. `specs/README.md`의 PD-01과 spec의 결정 항목 표를 갱신한다
  - **2026-09-15: 여덟 섹터를 실측으로 정했다.** 반도체·2차전지·은행·증권·화장품·건설·자동차·중공업이며 근거는 `pd01-etf-candidates.md` §7이다. 에너지화학은 유동성이 10억 미만이라 뺐고 건설이 그 자리를 대신한다. 방산은 이력이 짧아 미확정으로 남겼다
  - **2026-09-15: 종목 식별자 형식 제약이 풀렸다(T031).** 영숫자 단축코드를 담을 수 있게 되어 후보를 좁히던 제약이 사라졌다. 다만 §7에서 영숫자 코드 후보가 탈락한 까닭은 형식이 아니라 이력·유동성이었으므로 **여덟 섹터 선택은 그대로다**
  - **2026-09-15 결정: `listed_on`에는 운용사 공시에서 옮긴 실제 상장일을 적는다.** 자료가 있는 가장 이른 날을 적지 않는다. 까닭 셋은 `data-model.md`의 「상장일을 무엇으로 적는가」에 적었다 — 사실과 다르다는 것, SC-003의 상장 전 자료 검사가 조용히 꺼진다는 것, 자료 시작일은 `daily_price` 조회로 나오므로 같은 값을 두 곳에 두게 된다는 것이다
  - 남은 일은 셋이다. **⑴ 여덟 종목의 상장일과 운용사를 공시에서 확인한다**(같은 공시를 한 번 열면 둘 다 얻으므로 함께 한다). 2차전지·화장품·중공업은 관측 경계가 분기 범위로 알려져 있어 공시값이 그 범위에 드는지로 교차 확인이 된다. 나머지 다섯은 2010-01-04보다 앞이기만 하면 된다. **⑵ 대응표를 여덟 섹터로 갱신한다. ⑶ 새 버전으로 발행해 적재한다**
  - **적재할 때 「담을 수 없는 종목코드」 줄이 나오는지 확인한다.** 나오지 않으면 T031이 남긴 한계(304종 전부가 영숫자 여섯 글자인지 확인하지 못했다)도 함께 닫힌다
- [ ] T015 [DE] PD-19·PD-20 거래정지 규칙 확정 (`data/scripts/suspension_stats.py`, `data/src/data_pipeline/gaps.py`, `specs/README.md`, `specs/001-etf-market-data/{spec.md,contracts/market-dataset.md}`, `worker/src/worker/market_data.py`) — S15P21E205-187
  - **선행 조건은 T008이다.** 본선이 쌓아 둔 `suspension_period` 기록이 입력이다
  - 정지 사례의 길이 분포를 집계한다: 건수, 중앙값, 최댓값, ETF별 분포
  - 처리 방식 B(정지 이후만)·C(정지 이전만)·D(후보 제외) 각각이 공통 평가 기간을 얼마나 줄이는지 계산한다. 손실 규모를 수치로 비교한다 (research.md 「PD-19·PD-20을 나중에 정하기 위한 준비」)
  - 확정한 규칙을 FR-006과 계약에 반영하고 `gaps.py`의 `SUSPENDED_TOO_LONG` 기록을 켠다. `provisionalSuspensionHandling` 표시를 걷는다
  - 정지 구간을 빼고 앞뒤를 이어 붙이는 방식은 후보에서 제외된 상태다 (원칙 VI)

---

## Phase 7: Polish

- [ ] T016 [DOCS] 검증 기록과 문서 마무리 (`docs/guides/etf-market-data-verification-2026-09.md`, `specs/001-etf-market-data/quickstart.md`, `README.md`, `docs/specs/market-api.md`) — S15P21E205-188
  - quickstart 시나리오 1~8을 순서대로 실행하고 결과를 검증 기록 문서에 남긴다
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

---

## Phase 8: Convergence

**2026-09-13 `/speckit-converge` 산출.** 현재 코드를 spec.md·plan.md·contracts·data-model.md와 대조해 남은 일을 덧붙였다. 기존 항목은 하나도 고치지 않았다. T014~T016은 이미 열려 있으므로 다시 만들지 않는다. 판정: FR 17개·SC 7개·사용자 스토리 3개·plan 결정 10개·constitution 원칙 10개를 확인했고 MUST 위반은 없다. 유형은 missing 2·partial 9·contradicts 1이며 unrequested는 없다.

- [ ] T017 [BE] `GET /sectors/evaluation-window`를 붙인다 per contracts/sector-api.md·FR-007 (missing)
  - `EvaluationWindowService`는 공유 픽스처로 검증까지 끝났는데 HTTP 호출부가 없다. `SectorController`에는 `GET /sectors`와 `/{code}/instrument-profile`만 있다
  - **001이 지금 만들 수 없다.** 산식 입력 다섯 중 `warmupTradingDays`를 `evaluation-window.md` 입력표가 "전략 등록표(003 소유)에서 온다. 001은 이 값을 정하지 않고 받는다"로 못 박았고, `delistedOn`도 대응표가 현재 상장 상품만 다루기로 해 도메인 모델에 없다. T010의 2026-09-11 보류 판단이 그대로 유효하다
  - 붙일 때 필요한 것은 T010이 적어 둔 다섯 가지다. `EvaluationWindowService`는 입력을 전부 인자로 받으므로 고치지 않고 호출부만 더하면 된다
- [X] T018 [BE] 워밍업 자료를 요청 구간 앞에서 읽을지 확정한다 per contracts/market-dataset.md 「읽기 규칙」·SC-004 (partial)
  - 계약은 "워밍업 자료는 요청 구간보다 앞에서 읽는다. 달력 일수로 잡으면 연휴가 긴 해에 워밍업이 모자란다"고 정했고 T011도 같은 문장을 담았다. `PostgresMarketDataProvider.load`에 `warmup_trading_days` 인자와 거래일 기준 `_warmup_start` 기계가 실제로 들어 있다
  - 그런데 `engine_runner.py:273`이 그 인자를 넘기지 않아 기본값 0으로 읽고, `evaluation_start`(engine_runner.py:47)가 워밍업을 **요청 구간 안에서** 소비한다. 즉 공급자의 워밍업 경로가 Job 처리 경로에서 죽어 있다
  - **001 혼자 못 고친다.** `evaluation_start`는 004가 `run.effectiveStart`·`evaluation.warmupTradingDays` 우선순위로 설계한 것이라, 호출을 바꾸면 모든 백테스트의 `evaluationStart`와 지표가 움직이고 004의 재현 픽스처가 함께 깨진다. 두 계약 중 어느 쪽을 기준으로 삼을지 팀이 정해야 한다
- [ ] T019 [BE] `datasetId` 값 규칙을 저장된 자료 버전에서 읽는다 per research.md 결정 4 (contradicts)
  - `ExecutionPolicy.java:43`과 `docs/specs/backtest-messaging/`의 픽스처가 아직 `KRX_DAILY_V1`이다. 규칙은 `KRX_DAILY_{YYYYMMDD}`다
  - T011이 적어 둔 대로 **004 연동에서 푼다.** 상수에 날짜를 박으면 내일부터 거짓말이 되고, 올바른 해결은 백엔드가 `dataset_version`에서 현재 유효한 묶음을 읽는 것이다. 값 규칙 자체는 `dataset_version.build_dataset_id`가 이미 세우고 검증한다
- [ ] T020 [DE] 대조 표본에 가격과 지수를 더한다 per FR-016·SC-002 (partial)
  - FR-016은 "대표 ETF**와 지수**의 조회값"을, SC-002는 "**가격**·분배금·분할·상장일"을 공시와 대조하라고 한다. 지금 `build_queried_values`는 `listed_on`·`amount`·`pay_date`·`ex_date`만 채우고, 자기 docstring이 "여기서는 가격을 채우지 않고, 가격까지 대조하려면 부르는 쪽이 그 항목을 더한다"고 적어 두었는데 `reconcile.py`가 더하지 않는다
  - 지수 표본은 아예 없다. `build_samples`는 `ListingWindow`와 `CorporateAction`만 받는다
  - **실자료가 있어야 뜻이 생긴다.** 공시값은 사람이 옮긴 `--disclosure` 파일이고, 대조할 가격·지수 원본은 T014가 대표 ETF를 확정한 뒤에야 쌓인다. 배관(가격 입력 인자 추가)만 먼저 놓을지, T014·T016과 함께 닫을지 정해야 한다
- [ ] T021 [BE] 공급자가 만든 자료 메타데이터를 결과까지 전달한다 per FR-006·FR-013·FR-014 (partial)
  - `PostgresMarketDataProvider.load`가 `carriedForwardDates`·`dataGaps`·`dataDeadline`·`mapVersion`·`provisionalSuspensionHandling`·`notRealMarketData`를 빠짐없이 채운다. 그런데 `engine_runner`와 `artifacts`가 `market.metadata`를 읽지 않아 `datasetId` 말고는 `EngineOutput`에 닿지 않는다
  - FR-006의 "결과에 정지 일수와 임시 처리 중임을 함께 표시한다"와 FR-014의 "내보내는 모든 자료에 데이터 마감 시각을 붙인다"가 공급자 경계에서 멈춘다
  - **결과 표면은 004·005 소유다.** 001의 자체 검증(quickstart 시나리오 6)은 공급자 수준에서 확인하도록 쓰여 있어 통과한다. 어느 spec이 이 전달을 맡을지 정해야 한다
- [X] T022 [DOCS] quickstart에 대표 상품 정보 시나리오를 더한다 per US3/AC1~AC4·FR-015 (missing)
  - 시나리오 1~7 어디에도 `GET /sectors/{code}/instrument-profile`을 실행해 보는 절차가 없다. 사용자 스토리 3만 실행 가능한 검증 절차가 비어 있다
  - 필수 항목 존재, `asOfCurrentComposition` 표시, `sourceAttribution`, 추적 차이 필드 부재, 409 구분을 확인한다
  - **딸린 어긋남**: 이 문서 맨 위 Input 줄과 T016이 quickstart를 "시나리오 1~7"로 센다. converge는 기존 항목을 고치지 않으므로 그대로 두었다. T016을 착수할 때 「시나리오 1~8」로 고쳐 새 시나리오가 검증 기록에서 빠지지 않게 한다
- [X] T023 [DOCS] plan.md의 마이그레이션 파일명을 실제와 맞춘다 per plan.md Storage·Project Structure (partial)
  - plan.md 19행·113행이 `V3__etf_market_data.sql`을 가리키는데 실제 파일은 `V20260911114500__etf_market_data.sql`이고, `V3__param_search.sql`은 **다른** 마이그레이션으로 이미 존재한다. 지금 문서대로 읽으면 남의 파일을 가리킨다
  - T013이 만든 `V20260913103000__instrument_profile.sql`도 plan.md에 없다
- [X] T024 [BE] 스키마 검사에 12번째 표를 넣는다 per data-model.md (partial)
  - `EtfMarketDataSchemaIT`의 표 검사가 11개만 세고 `instrument_profile`을 빼놓았다. 표시 문구도 "관계형 6개와 시계열 5개, 모두 11개 표"로 남아 있는데 data-model.md가 정의한 표는 12개다
  - T013이 그 표를 만들면서 검사를 함께 넓히지 않았다
- [X] T025 [DOCS] 일평균 거래대금의 저장 타입과 응답 타입 변환을 문서에 적는다 per data-model.md·contracts/sector-api.md (partial)
  - data-model.md는 `avg_daily_value_3m`을 "금액이므로 문자열로 둔다"고 하고, 계약은 `@JsonSerialize(using = PlainBigDecimalSerializer.class)`를 요구한다. 구현은 TEXT 열 → `BigDecimal` 도메인 → 지수 표기 없는 문자열 응답으로 양립시켰는데 **어느 문서도 그 변환을 말하지 않는다**
- [X] T026 [DOCS] 대표 상품 정보의 `isSample`이 무엇의 값인지 계약에 적는다 per contracts/sector-api.md (partial)
  - 계약의 응답 예시에 `isSample`이 있는데 대응표의 것인지 자료 버전의 것인지 말하지 않는다. 구현은 research.md 결정 5("자료 버전 레코드에도 같은 뜻의 표시를 둔다")를 따라 **자료 버전** 기준으로 정했고, `GET /sectors`의 `isSample`은 대응표 기준이라 두 값이 다를 수 있다
- [X] T027 [DOCS] 대표 상품 정보 표에 불변성 적용 여부를 적는다 per data-model.md·FR-017 (partial)
  - data-model.md 「instrument_profile」 불변식이 `(etf_id, dataset_version_id)` UNIQUE만 말하고 UPDATE·DELETE 거부를 적용하는지 침묵한다. 구현은 트리거를 걸지 않기로 정하고 그 근거를 마이그레이션 주석에만 남겼다(FR-017이 못 박은 대상은 자료 버전과 시계열이며 `reconciliation_record`도 같다)
- [X] T028 [DOCS] plan.md의 워커 공급자 이름을 실제와 맞춘다 per plan.md (partial)
  - plan.md가 `RepositoryDataProvider`를 더하고 `engine_runner.py:68`의 기본값을 바꾸라고 적었는데, 실제로는 004가 만든 `PostgresMarketDataProvider`가 그 자리를 맡고 `PortfolioBacktestEngine`은 공급자에 **기본값을 두지 않아** 주입을 잊은 경로 자체가 없다. `YFinanceDataProvider`는 코드에서 사라졌다(남은 것은 세 CLAUDE.md의 서술뿐)

---

## Phase 9: Convergence

**2026-09-14 `/speckit-converge` 산출.** `-204`·`-203`·`-202`·`-207` 네 갈래를 `dev`에 합친 나무에서 평가했다. 한 갈래씩 돌리면 나머지 셋의 작업을 "안 된 일"로 다시 찾아내므로 합친 상태에서 한 번만 돌렸다. 그 나무의 검증은 backend 920건·worker 332건·data 462건이 모두 통과하고 실패 0건이다. 기존 항목은 하나도 고치지 않았다.

**다만 `-202`(T018)와 `-203`(T019)은 이 평가 뒤에 보류하기로 정했다.** 코드리뷰가 양쪽 모두에서 회귀를 찾아냈다 — 아래 T029와 T030을 본다. 따라서 `dev`에 들어가는 것은 `-204`와 `-207` 둘이고, **T018과 T019는 `[ ]`로 남는다.**

**2026-09-15 둘 다 재개했다.** T029는 끝냈고(`S15P21E205-202`), T030은 곁가지만 닫고 본체를 자료 적재 뒤로 남겼다(`S15P21E205-203`). **따라서 T018은 `[X]`, T019는 `[ ]`로 남는다.** 각 항목 끝에 무엇이 달라졌는지 적었다.

두 건이 같은 뿌리를 공유한다. **001의 자료 계층이 아직 실행 경로에 이어져 있지 않은 채로 백엔드만 정직하게 만들면, 지금 도는 DEMO 경로가 끊긴다.** 자료가 실제로 적재되는 것과 함께 옮겨야 한다.

판정: FR 17개·SC 7개·사용자 스토리 3개·research 결정 8개·constitution 원칙 10개를 확인했다. MUST 위반은 없다. 유형은 partial 6·contradicts 2이며 missing과 unrequested는 없다. 이미 열려 있는 T014~T017·T020·T021이 덮는 범위는 다시 만들지 않았다.

- [X] T029 [BE] 워밍업을 어디서 읽을지 세 곳에서 함께 정한다 per contracts/market-dataset.md 「읽기 규칙」·contracts/evaluation-window.md (contradicts)
  - **같은 규칙을 구현한 곳이 셋인데 두 가지 규약이 섞여 있다.** `contracts/market-dataset.md` 「읽기 규칙」은 **요청 구간보다 앞에서** 읽으라 하고, `contracts/evaluation-window.md`는 `firstEvaluable`을 "상장일 이후 warmup번째 거래일"로 정의해 **뒤로 밀라** 한다. 후자는 자바와 파이썬이 함께 읽는 공유 픽스처 `contracts/fixtures/evaluation-window-cases.json`이 붙박고 있다
  - 세 곳은 `worker/src/worker/engine_runner.py`, `backend/.../EffectivePeriodCalculator.java`(→ `MarketMetadataPort.evaluationStart`), 그리고 위 공유 픽스처와 `paramsearch/CommonPeriodResolver`다
  - **워커만 먼저 옮기면 회귀가 난다.** T018 시도에서 실측으로 드러났다 — ⑴ 백엔드가 민 `effectiveStart`를 워커가 그대로 받아 워밍업이 두 번 들어가고(요청 63일에 실제 130일), ⑵ 요청 시작일이 자료의 처음이나 상장일 근처면 **전에는 성공하던 실행이 `DATA_INSUFFICIENT`로 실패한다.** 백엔드는 `commonStart` 뒤쪽만 보장할 뿐 `run.start` 앞쪽에 자료가 있는지 보지 않는다
  - 화면이 사용자에게 보여 주는 기간과 워커가 평가하는 기간이 반대 규칙으로 계산되는 것도 같은 뿌리다
  - `effectiveStart`는 동일 버전 키에 들어가 재현을 보장하는 값이다(R-03). 바꾸면 같은 요청이 다른 키를 갖는다. **워밍업 부족 판정·재현 키·포트 이름을 함께 정해야 하며 004 계약에 닿는다.** Jira `S15P21E205-205`, 워커 쪽 시도는 `S15P21E205-202`에 보류되어 있다
  - **2026-09-15 해결. 위 진단 중 한 줄은 틀렸다** — 공유 픽스처 `contracts/fixtures/evaluation-window-cases.json`은 옳았고 고칠 것이 없었다. 두 계약은 서로 다른 것을 계산한다. `evaluation-window.md`의 `commonStart`는 **후보로 평가할 수 있는 가장 이른 날**이라 상장일에서 워밍업을 세는 것이 맞고, `market-dataset.md` 「읽기 규칙」은 **사용자가 고른 시작일**에 대한 규칙이다. 실제로 어긋난 것은 백엔드 구현 둘뿐이었다 — `EffectivePeriodCalculator`와 `CommonPeriodResolver`가 `max(요청 시작일, 상장일)`을 만든 뒤 거기서 워밍업을 세어, 계약이 기준점으로 삼지 않은 요청 시작일에서도 구간을 깎았다
  - 세 곳이 이제 한 문장을 구현한다 — **평가 시작일 = max(요청 시작일, 자료가 시작된 뒤 warmup번째 거래일)**. 요청 앞에 워밍업 자료가 있으면 밀지 않고, 앞이 모자랄 때만 모자란 만큼 밀린다. 요청 시작일이 이길 때는 거래일에 맞춰 준다 — 주말을 평가 첫날로 삼으면 워커가 고르는 첫 거래일과 값이 갈리고 그 값은 동일 버전 키에 들어간다
  - **"한 규칙"에는 단서가 있다.** 백엔드의 "자료가 시작된 뒤"는 가장 늦은 상장일이고 워커의 그것은 읽어 온 가격 행의 합집합 첫날이다. 세 곳의 주석·README에 그 차이를 적어 두었다
  - **뿌리: 백엔드가 데이터셋의 첫 거래일을 모른다.** `contracts/evaluation-window.md`의 `firstEvaluable` 기준점은 사실 `max(상장일, 데이터셋 첫 거래일)`이라야 하는데, `MarketMetadataPort`에 자료 시작일을 묻는 길이 없어 앞쪽 항만 쓴다. 픽스처에서 둘은 **8년** 어긋나 있다 — 어댑터 상장일은 2015-01-02·2018-09-12이고 워커가 읽는 `worker/examples/three_etf_24m`의 자료는 2023-01-02부터다. 옛 규칙은 `max(요청 시작일, 상장일)`에서 워밍업을 세어 이 구멍을 **우연히** 덮고 있었고, 덮개를 걷어내자 드러났다. 그 어긋남 자체를 `MarketFixtureAlignmentTest`가 붙박는다 — 두 값이 같아지면 빨개진다
  - **막는 곳은 워커 `evaluation_start`의 되밀기 하나이고, 세 갈래 모두에 얹어야 한다.** 발행 경로가 `EngineInputAssembler` 하나이고 `BacktestService.create`가 언제나 `plan.effectiveStart()`를 실으므로, 되밀기를 `run.start` 갈래에만 두면 **운영 경로에서 통째로 꺼진다.** 실측으로 확인했다 — 관측 63거래일이 필요한 요청을 자료 첫날 근처(2023-02-01, 앞 22거래일)에서 시작하면 워밍업 없이 첫 판단일을 맞아 `CONSTRAINT_VIOLATION`으로 죽었다. `dev`도 같은 입력에서 죽는다(회귀가 아니라 옛 구멍이 드러난 것이다). 세 갈래 모두에 얹으니 `COMPLETED`가 된다
  - **남는 한계: 화면에 보이는 기간과 실제 평가 기간이 갈릴 수 있다.** 되밀기가 일하면 워커는 백엔드가 준 `effectiveStart`보다 뒤에서 시작한다(위 예에서 2023-02-01 대신 2023-03-30). 실패하는 것보다 낫지만 완전한 해결은 아니다. **근본 해결은 포트가 자료 시작일을 알려 주고 백엔드가 `max(상장일, 데이터셋 첫 거래일)`에서 세는 것이며, 001의 자료 계층과 함께 옮긴다.** 그때 되밀기는 이중 안전장치로 남는다
  - **⑵의 `DATA_INSUFFICIENT` 회귀를 막는 것은 max 산식이 아니라 읽기 쪽 두 가지다.** 처음 고쳤을 때는 산식만 맞추고 `MarketDataProvider`에 요청 시작일을 앵커로 넘겨, 자료 맨 앞 근처를 요청한 실행이 **읽기 단계에서** 같은 회귀로 죽었다(`run.start=자료 첫날, warmup=5`도 죽었다). 둘을 함께 고쳤다 — ⑴ 앵커를 요청 첫날이 아니라 **평가가 실제로 시작될 날**(`scenarioParams.startDate` → `run.effectiveStart` → `run.start`)로 바꾸고, ⑵ 공급자가 앞이 모자라면 거절하지 않고 **있는 만큼** 읽게 했다. **읽기는 관대하게, 평가 시작일 판정은 엄격하게** — 되밀기는 `evaluation_start` 한 곳이 맡는다. 상장 전 구간을 지어내지 않는다는 계약(FR-005·SC-003)은 `_check_window`·`_check_prices`가 그대로 지킨다
  - **`effectiveStart`가 키에 없어 옛 결과가 재사용된다.** 동일 버전 키 입력(`start`·`end`·`warmupTradingDays`)은 하나도 안 바뀌는데 결과 숫자가 움직인다. 그래서 키에 들어가는 `thorp.backtest.engine-version`을 `worker 2.0.0 → 2.1.0`으로, 결과 `versions.worker`가 싣는 워커 `__version__`을 `0.1.1 → 0.2.0`으로 올렸다. 계산 규칙이 바뀌면 이 둘을 올린다
  - 004 재현 픽스처는 바뀌지 않았다. `EngineInputAssembler`가 저장된 계획값을 읽을 뿐 이 계산기를 부르지 않는다
- [ ] T030 [BE] 자료 버전을 접수 시점에 정해 실행에 고정하고 고르는 키와 기록을 하나로 만든다 per research.md 결정 4·FR-013·SC-004 (contradicts)
  - **`marketData.datasetId`는 지금 아무도 읽지 않는다.** `engine_runner.py`가 공급자에 넘기는 것은 `request.market_data.data_version`이고, 그 값은 `FixtureMarketMetadataAdapter`의 고정값이다. 그래서 기록과 선택이 서로 다른 것을 가리킨다
  - T019 시도에서 실측으로 드러난 곁가지 셋이 함께 풀려야 한다
    - **쓸 수 있는 자료 묶음이 없을 때 접수가 막히지 않는다.** 조립이 던진 예외를 `BacktestJobDispatcher.send`가 잡아 로그만 남기므로 API는 202를 돌려주고 실행은 `QUEUED`에 영원히 남는다. 오래된 `QUEUED`를 실패로 닫는 장치가 없다
    - **재발행이 시도 횟수를 헛되이 올린다.** `republish`가 `recordPublishAttempt`를 먼저 부르고 그다음 조립이 실패한다. 그 값이 DLQ 종료 판정의 분모라(`RunLifecycle`) 나중에 실패로 닫을 수도 없게 된다
    - **자료 버전이 실행에 고정되지 않는다.** 조립 시점에 새로 고르므로 재발행이 며칠 뒤 일어나면 다른 메시지가 나간다. `EngineInputAssembler`가 스스로 적어 둔 "조립 시점에 새로 고르는 값이 없다"와 어긋나고, 중복 판정 키와 `versions.data`에도 그 값이 없다
  - **접수 시점에 정해 실행 계획에 저장하는 것이 넷을 한 번에 푸는 설계다.** 쓸 수 있는 묶음이 없으면 접수를 거절하고, 워커가 고르는 키를 그 값으로 바꾸거나 두 칸을 합친다
  - `docs/adr/0002`와 `specs/004-backtest-execution/contracts/messaging.md`가 아직 옛 상수를 계약으로 적고 있어 함께 고쳐야 한다. Jira `S15P21E205-203`에 보류되어 있다
- [X] T031 [DE] 종목 식별자 형식이 배제하는 영숫자 단축코드를 어떻게 다룰지 정한다 per data-model.md 「representative_etf」·FR-001·FR-002 (contradicts)
  - `data-model.md`가 `instrument_id`를 `{6자리}.XKRX` 형식으로 정했고 마이그레이션의 CHECK와 `krx.py`의 형식 검사가 그것을 강제한다
  - **실제 원천에는 그 형식을 벗어나는 ETF가 많다.** 2026-09-11 하루치를 실제로 불러 세어 보니 1,168종 중 **304종(26%)**이 `0184E0`·`0182R0` 같은 영숫자 단축코드다. 이 종목들은 대표 ETF로도 비교 대상으로도 넣을 수 없다
  - PD-01(T014)이 후보를 고르는 일이라 **이 제약이 그 선택지를 먼저 좁힌다.** 형식을 넓힐지, 좁은 채로 두고 그 사실을 문서에 적을지 정한다
  - **2026-09-15 결정: 넓힌다.** 형식은 `[0-9A-Z]{6}\.XKRX`다. 자리 수는 여섯으로 그대로 두고 허용 문자에 영문 대문자만 더했다. 원천의 4분의 1을 후보에서 빼는 대가가, 좁은 채로 두고 그 사실만 적어 두는 이득보다 크다
  - **소문자는 받지 않는다.** 원천이 대문자로만 주고, 둘 다 받으면 같은 종목이 `0111J0`과 `0111j0` 두 값으로 저장될 수 있다. 자리 수가 어긋난 코드도 그대로 거부한다
  - 고친 곳은 정규식 열세 군데다 — `data/` 일곱(`corporate_actions`·`gaps`·`instrument_profile`·`krx` 둘·`reconciliation`·`repository`), `worker/` 둘(`contracts/engine_input`·`evaluation_window`), `backend/` 넷(`InstrumentId`·`RepresentativeEtf`·`ParamSearchPreviewRequest`·`EvaluationWindowService`). `sector_map.py`와 `index.py`는 `repository.py`의 패턴을 가져다 쓰므로 따라온다
  - DB 제약 다섯 개는 새 마이그레이션 `V20260915163000__widen_instrument_id_format.sql`로 넓혔다. **기존 파일을 고치지 않은 까닭**은 그것이 이미 `dev`에 병합되어 팀원의 로컬 DB에 적용되었기 때문이다. 적용된 파일을 고치면 Flyway 체크섬이 어긋나 그 DB에서 기동이 막힌다
  - **`krx.py`의 `unsupported_codes` 장치는 지우지 않고 남겼다.** 이제 영숫자 코드는 그 갈래에 오지 않지만, 원천이 아직 본 적 없는 모양을 보내오면 수집이 멈추지 않으면서도 무엇이 빠졌는지 드러나야 한다. 담지 못하는 것을 조용히 버리면 그 순간 검사가 사라진다
  - **확인하지 못한 것: 304종 전부가 영숫자 여섯 글자 대문자인지는 검증하지 못했다.** 근거는 문서에 남은 예시 세 개(`0111J0`·`0182R0`·`0005D0`)와 `0184E0`뿐이다. T014에서 실제로 적재할 때 `unsupported_codes`가 비어 있는지 확인한다 — 비어 있지 않으면 그 모양에 맞춰 형식을 다시 본다
- [X] T032 [DOCS] contracts/market-dataset.md의 「기존 provider 정리」를 실제와 맞춘다 per plan.md·004 T004 (partial)
  - 표가 아직 `RepositoryDataProvider`를 "`engine_runner.py:68`의 기본값이 된다"로 적는데 그 클래스는 만들어지지 않았고 004의 `PostgresMarketDataProvider`가 그 자리를 맡는다
  - 덧붙임이 "`YFinanceDataProvider`는 `worker/scripts/yfinance_provider.py`로 옮겼고"라고 하는데 **그 파일은 없다.** 004 T006에서 삭제했다
  - 같은 덧붙임의 "001 담당자 확인 필요"가 아직 달려 있다. 이제 확인되었다
  - 42행이 워밍업 방식을 없어진 클래스 기준으로 설명한다. T018이 바꾼 실제 동작과 맞춘다
- [ ] T033 [BE] 대조 생성기와 워커 픽스처의 자료 버전 이름을 규칙에 맞춘다 per research.md 결정 4 (partial)
  - `docs/specs/backtest-messaging/examples/reconciliation/reconcile.py`가 아직 `KRX_DAILY_V1`을 박아 넣고, 그 산출물 `worker/tests/fixtures/manual_reconciliation.json`에 통째로 들어가 있다
  - 생성기만 고치면 산출물과 갈라진다. **둘을 함께 고치고 재생성해 검증할 수 있는 작업에서 닫는다**
  - 그 자리의 `dataVersion`이 `FIXTURE-three_etf_24m`이라 정직한 값이 KRX 날짜가 아니라 픽스처 식별자일 수 있다. T030과 함께 정하는 편이 낫다
- [X] T034 [DOCS] research.md의 워커 공급자 이름을 실제와 맞춘다 per research.md 결정 1 (partial)
  - 26행과 198행이 아직 `RepositoryDataProvider`를 기본 공급자로 적는다. T028이 `plan.md`에서 고친 것과 같은 어긋남이다
- [X] T035 [DOCS] tasks.md가 세는 quickstart 시나리오 수를 8로 맞춘다 per quickstart.md (partial)
  - T022가 시나리오를 하나 더해 `quickstart.md`에 여덟 개가 있는데, 이 문서 3행의 Input 줄과 T016의 첫 항목이 아직 "시나리오 1~7"이다
  - **T016을 적힌 대로 수행하면 새 시나리오가 검증 기록에서 빠진다.** T016 착수 전에 고쳐야 한다
- [ ] T036 [BE] engine-input-target-risk.json의 dataVersion 값 규칙을 정한다 per research.md 결정 4 (contradicts)
  - `"KRX-2026-09-09"`는 `KRX_DAILY_{YYYYMMDD}`도 아니고 픽스처 식별자도 아니다. 어느 규칙에도 맞지 않는다
  - T030이 두 칸의 뜻을 정한 뒤 그 규칙에 맞춘다
