# Tasks: 경제환경 검색 (비슷했던 때 보기)

**Input**: Design documents from `specs/010-economic-regime-search/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: 포함한다. constitution "완료의 정의" 조건 2가 *"테스트 또는 동작 확인 완료"* 를 Task 완료
조건으로 못 박아 선택 사항이 아니다. 010은 **계산이 본체**라 시험의 무게중심도 `data/`에 있다.

## Format: `[ID] [P?] [Story] (파트) Description`

- **[P]**: 다른 파일을 만지고 선행 태스크가 없어 병렬 실행 가능
- **[US1]~[US4]**: spec.md의 사용자 스토리. Setup·Foundational·Polish에는 붙지 않는다
- **(파트)**: 작업 종류. 브랜치 position은 아래 묶음 표가 정한다

| 파트 태그 | 대상 | position |
|---|---|---|
| `(DE)` | `data/` 파이프라인 Python | `de` |
| `(DE 테스트)` | pytest | `de` |
| `(DB)` | Flyway 마이그레이션 | `be` |
| `(BE)` | 백엔드 Java | `be` |
| `(BE 테스트)` | JUnit·Testcontainers | `be` |
| `(FE)` | 프론트엔드 React·TS | `fe` |
| `(FE 테스트)` | Vitest | `fe` |
| `(DOCS)` | 문서·수동 확인 기록 | `docs` |

Worker(`worker/`)는 **변경하지 않는다.** 브로커도 쓰지 않는다 — 010은 동기 조회다.

---

## 브랜치 묶음 — 이 표가 티켓·브랜치·MR 단위다

**`T###`는 브랜치 단위가 아니다.** constitution "Jira 연계"가 `작업 묶음 = 1 Branch = 1 MR`로 정하므로
브랜치·MR·Jira Task 단위는 아래 **9개 묶음**이다. plan.md의 M1~M8에 마무리 묶음(M9) 하나를 더했다 —
quickstart 실행 기록과 폐기 예행연습은 파트가 DOCS라 구현 묶음에 섞지 않는다.

| 묶음 | 브랜치 | 파트 | Jira 제목(안) | 태스크 | 검증 명령 |
|---|---|---|---|---|---|
| **M1** | `feat(de)/{티켓}-regime-port-fixture` | DE·BE | `[DE] 경제환경 지표 포트와 격리된 임시 대역` | T001~T014 | `cd data && ruff check src scripts tests && pytest` + `cd backend && ./gradlew spotlessApply build` |
| **M2** | `feat(de)/{티켓}-regime-scale` | DE | `[DE] 공통 척도 — 연속 구간·중앙값·MAD·IQR` | T015~T024 | `cd data && ruff check src scripts tests && pytest` |
| **M3** | `feat(de)/{티켓}-regime-distance` | DE | `[DE] 거리와 기여도 계산` | T025~T033 | 〃 |
| **M4** | `feat(de)/{티켓}-regime-candidates` | DE | `[DE] 후보 제한·정렬과 설명 문장` | T034~T045 | 〃 |
| **M5** | `feat(de)/{티켓}-regime-precompute` | DE | `[DE] 전 구간 사전 계산 배치와 적재` | T046~T057 | 〃 |
| **M6** | `feat(be)/{티켓}-regime-search-api` | DB·BE | `[BE] 경제환경 검색 조회 API` | T058~T072 | `cd backend && ./gradlew spotlessApply build` |
| **M7** | `feat(fe)/{티켓}-regime-result` | FE | `[FE] 분야 검색 결과와 검색 상태 표시` | T073~T087 | `cd frontend && npm test && npm run lint && npm run build` |
| **M8** | `feat(fe)/{티켓}-regime-detail-composite` | FE | `[FE] 사례 상세와 Thorp 종합 비교` | T088~T099 | 〃 |
| **M9** | `docs/{티켓}-regime-verification` | DOCS | `[DOCS] quickstart 실행 기록과 임시 대역 폐기 예행연습` | T100~T105 | 수동 — quickstart.md S1~S9 |

**묶음 순서는 M1 → M9 직렬이다.** M2~M5는 앞 묶음의 함수를 직접 부르고, M6은 M5가 적재한 행이
있어야 조회할 것이 있으며, M7·M8은 M6의 응답을 읽는다. 병렬로 딸 수 있는 묶음은 없다 —
묶음 **안에서만** `[P]`로 병렬화한다.

### 묶음별 MR 체크포인트

| 묶음 | 머지 전에 이것이 참이어야 한다 |
|---|---|
| M1 | 픽스처 7가지 모양이 다 있고, `macro/fixture/` 밖에서 픽스처를 이름으로 참조하는 코드가 0건, prod 프로파일 + `source: fixture`면 기동 실패 |
| M2 | 구멍 뚫린 계열이 60개월 미만으로 판정된다. 상수 계열이 IQR로 넘어가고 완전 상수는 계산 불가다. 보정 상수가 `Φ⁻¹(0.75)` 실계산값이다 |
| M3 | 수기 계산과 정확히 일치한다. 전체 거리 0이면 기여도 **키가 없다** |
| M4 | 12개월 이내 제외·±6개월 건너뛰기·동률 오래된 월 우선이 고정됐다. 후보 부족 시 채우지 않는다. 설명 문장에 자유 생성이 없다 |
| M5 | 전 구간 배치가 돌고 **두 번 돌려도 같은 행**이 나온다(멱등). 자료 버전이 `FIXTURE-`로 시작한다 |
| M6 | 계약 예시와 응답이 일치한다. `NOT_COMPUTABLE`이 **200**이고 503을 쓰지 않는다 |
| M7 | 배지·확률 표현 0건. 후보 부족 시 빈자리가 없다. 예시 자료 표시가 보인다 |
| M8 | 모드 전환 시 한 문장 알림이 뜨고 기준월이 늘 보인다. 유지율이 화면에 0건 |
| M9 | quickstart S1~S9 실행 기록이 남고, 폐기 예행연습에서 운영 코드가 깨지지 않음을 확인했다 |

## Path Conventions

- 파이프라인: `data/src/data_pipeline/regime/…`, 테스트는 `data/tests/regime/…`
- 백엔드: `backend/src/main/java/com/ssafy/thorp/regime/…`, 테스트는 `backend/src/test/java/com/ssafy/thorp/regime/…`
- 마이그레이션: `backend/src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__regime_search.sql`
- 프론트엔드: `frontend/src/…`
- ⚠ 임시 대역(008 완성 시 통째 삭제): `data/src/data_pipeline/regime/fixture/`,
  `backend/src/main/java/com/ssafy/thorp/regime/infrastructure/macro/fixture/`,
  `backend/src/main/resources/fixtures/macro-indicator/`
- 커밋 전 `cd backend && ./gradlew spotlessApply` 필수

---

# Phase 1: Setup (공유 골격)

**Purpose**: 패키지 골격과 설정값 자리 만들기 · **묶음 M1**

- [ ] T001 (DE) `data/src/data_pipeline/regime/__init__.py`와 하위 모듈 자리(`scale.py`·`distance.py`·`candidates.py`·`explain.py`·`snapshot.py`·`load.py`)를 빈 파일로 만들고 각 모듈의 책임을 docstring에 적는다
- [ ] T002 [P] (BE) `regime` 최상위 패키지와 ADR-0001 4계층 하위 패키지 골격을 만들고 계층 규약을 `backend/src/main/java/com/ssafy/thorp/regime/package-info.java`에 명시 (`paramsearch` 모듈과 같은 배치)
- [ ] T003 [P] (BE) `RegimeProperties` 설정 클래스를 `backend/src/main/java/com/ssafy/thorp/regime/infrastructure/config/RegimeProperties.java`에 작성 — `macro.source`(`fixture` \| `macro`)를 노출하고 `@NotNull`로 필수화한다. **코드에 기본값을 박지 않는다** — 미설정이면 기동을 실패시켜 설정으로 드러낸다
- [ ] T004 [P] (DE) `data/scripts/precompute_regime.py` 진입점 골격을 argparse로 만든다 — `--fixture`, `--all`, `--baseline-month`, `--dry-run` 플래그만 정의하고 본체는 M5에서 채운다

**Checkpoint**: 골격이 서고 `ruff check`·`./gradlew build`가 통과한다

---

# Phase 2: Foundational (모든 스토리를 막는 선행 작업)

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 어떤 사용자 스토리도 시작할 수 없다. 010은 계산이 본체라
Foundational이 크다 — 화면과 API는 그 결과를 옮기는 일이다.

## 묶음 M1 — 지표 포트와 격리된 임시 대역

**Goal**: 008이 없어도 010이 돌 자리를 만들되, 008이 오면 **한 번에 지워지도록** 격리한다.

- [ ] T005 (BE) `MacroIndicatorPort` 인터페이스를 `backend/src/main/java/com/ssafy/thorp/regime/application/port/MacroIndicatorPort.java`에 작성 — `registry()`·`monthlySeries()`·`rawSeries()`·`baselineMonths()`·`publication()`·`dataVersion()`·`isSampleData()`. 시그니처와 뜻은 [contracts/macro-indicator-port.md](contracts/macro-indicator-port.md) §1
- [ ] T006 [P] (BE) 포트가 돌려줄 값 타입을 `regime/application/port/`에 record로 작성 — `IndicatorRegistry`·`MonthlyValue`·`BaselineMonths`·`Publication`. **결측월은 키가 없다**(0이나 직전 값으로 채우지 않는다)
- [ ] T007 (DE) ⚠ 픽스처 지표 시계열을 `backend/src/main/resources/fixtures/macro-indicator/indicators.json`에 작성 — 17개 지표 × 월별 원값·변환값. [contracts/macro-indicator-port.md](contracts/macro-indicator-port.md) §3.4의 **일곱 모양을 모두 담는다**
- [ ] T008 (BE) ⚠ `FixtureMacroIndicatorAdapter`를 `backend/src/main/java/com/ssafy/thorp/regime/infrastructure/macro/fixture/FixtureMacroIndicatorAdapter.java`에 작성 — `@ConditionalOnProperty(prefix="thorp.regime.macro", name="source", havingValue="fixture")`. `dataVersion()`은 **`FIXTURE-`로 시작하는 값**을 돌려주고 `isSampleData()`는 `true`
- [ ] T009 [P] (DE) ⚠ 파이프라인 쪽 픽스처 로더를 `data/src/data_pipeline/regime/fixture/loader.py`에 작성 — 같은 `indicators.json`을 읽는다. **자료가 두 벌로 갈리지 않게 파일 하나를 양쪽이 읽는다**
- [ ] T010 (BE 테스트) prod 프로파일에서 `thorp.regime.macro.source=fixture`면 **기동에 실패**함을 `backend/src/test/java/com/ssafy/thorp/regime/RegimeProfileGuardTest.java`에 고정 — 설정 실수로 가짜 자료가 운영에 나가는 경로를 없앤다(`ApplicationProfileConfigTest`와 같은 방식)
- [ ] T011 [P] (BE 테스트) 픽스처 격리 검사를 `backend/src/test/java/com/ssafy/thorp/regime/FixtureIsolationTest.java`에 작성 — `regime` 운영 코드에서 `macro/fixture/` 밖이 픽스처 클래스를 이름으로 참조하면 실패한다. 대상 파일은 **손 목록이 아니라 디렉터리를 훑어** 모은다
- [ ] T012 [P] (BE 테스트) 포트 계약 테스트를 `backend/src/test/java/com/ssafy/thorp/regime/MacroIndicatorPortContractTest.java`에 작성 — 결측월이 키 없음으로 오는지, 원값·변환값이 둘 다 오는지, 버전 셋이 함께 오는지. **008 구현체가 와도 같은 테스트가 돈다**
- [ ] T013 [P] (DE 테스트) 픽스처 일곱 모양이 실제로 담겨 있는지 `data/tests/regime/test_fixture_shapes.py`에 고정 — 모양 하나가 빠지면 그 경로가 조용히 검증 밖으로 나간다
- [ ] T014 (BE) `application-local.yml`에 `thorp.regime.macro.source: fixture`를, `application-prod.yml`에 `macro`를 둔다. prod는 `${ENV}` 참조에 **기본값을 두지 않는다**

**Checkpoint**: 008 없이도 지표를 읽을 수 있고, 지울 곳이 세 디렉터리로 모였다

## 묶음 M2 — 공통 척도

**Goal**: 질의와 모든 후보에 적용할 자(m·s)를 만든다. 010 계산의 첫 단추다.

- [ ] T015 (DE) 12자리 반올림 유틸을 `data/src/data_pipeline/regime/rounding.py`에 작성 — 저장·비교 직전에 한 번 적용한다. **순위와 동률 판정은 반올림된 값으로 한다**(research R4)
- [ ] T016 (DE) 정규분포 보정 상수를 `data/src/data_pipeline/regime/scale.py`에 `scipy.stats.norm.ppf(0.75)`로 계산해 둔다 — **1.4826·1.349 상수를 쓰지 않는다**(spec FR-005가 명시적으로 배제, research R5)
- [ ] T017 (DE) 척도 구간 연속성 판정을 `scale.py`에 구현 — `t-3`에서 과거로 거슬러 세어 **처음 결측을 만나는 곳까지**가 연속 구간이다. 구멍 건너편 자료를 끌어오지 않는다(spec FR-005)
- [ ] T018 (DE) 중앙값 m과 MAD 기반 산포 s를 `scale.py`에 구현 — `s = MAD / Φ⁻¹(0.75)`
- [ ] T019 (DE) MAD가 0일 때 IQR 대체를 `scale.py`에 구현 — IQR은 **선형 보간**의 75분위 − 25분위, `s = IQR / (2·Φ⁻¹(0.75))`. 방법을 바꾸면 새 모델 버전이다(spec FR-006)
- [ ] T020 (DE) 연속 구간 60개월 미만 / MAD·IQR 둘 다 0일 때 **계산 불가 사유**를 돌려주는 경로를 `scale.py`에 구현 — `SCALE_WINDOW_TOO_SHORT` / `SCALE_ZERO`. **임의 epsilon·클리핑을 넣지 않는다**(spec FR-007)
- [ ] T021 [P] (DE 테스트) 연속 구간 판정을 `data/tests/regime/test_scale.py`에 고정 — 구멍 뒤쪽이 60개월 미만이면 앞쪽에 자료가 더 있어도 계산 불가다
- [ ] T022 [P] (DE 테스트) MAD→IQR 전환과 둘 다 0인 경우를 `test_scale.py`에 고정
- [ ] T023 [P] (DE 테스트) 보정 상수가 근사값 1.4826과 **다름**을 `test_scale.py`에 고정 — 근사값으로 되돌아가면 실패한다
- [ ] T024 [P] (DE 테스트) 같은 기준월에 대해 두 번 계산하면 m·s가 비트 단위로 같음을 `test_scale.py`에 고정

**Checkpoint**: 자를 만들 수 있고, 만들 수 없을 때 그 사실을 사유와 함께 말한다

## 묶음 M3 — 거리와 기여도

**Goal**: 두 달이 얼마나 떨어져 있는지를 숫자로 만든다.

- [ ] T025 (DE) 표준화 z를 `data/src/data_pipeline/regime/distance.py`에 구현 — `z = (x − m) / s`. **질의와 모든 후보에 같은 m·s를 적용한다**(spec FR-005, 원칙 IV)
- [ ] T026 (DE) 지표 거리 `E_i`를 `distance.py`에 구현 — 세 시점 `|z_A − z_B|`의 평균
- [ ] T027 (DE) 분야 거리 `D_g`(분야 내 산술평균)와 종합 거리(7개 `D_g`의 산술평균)를 `distance.py`에 구현
- [ ] T028 (DE) 분야 기여도(`D_g` ÷ `ΣD_g`)를 `distance.py`에 구현 — **전체 거리가 0이면 기여도를 만들지 않고 키를 생략한다**(spec Edge Cases). `0`이나 `null`로 채우지 않는다
- [ ] T029 (DE) 모든 산출값에 T015의 12자리 반올림을 적용해 돌려준다 — 정렬 전에 고정해야 순위가 흔들리지 않는다
- [ ] T030 [P] (DE 테스트) z·E_i·D_g·종합이 수기 계산과 일치함을 `data/tests/regime/test_distance.py`에 고정
- [ ] T031 [P] (DE 테스트) 전체 거리 0일 때 기여도 **키가 없음**을 `test_distance.py`에 고정
- [ ] T032 [P] (DE 테스트) 같은 후보라도 기준월이 다르면 z가 다름을 `test_distance.py`에 고정 — 월별 z를 미리 계산해 두는 잘못된 최적화를 막는다(research R3)
- [ ] T033 [P] (DE 테스트) 수기 대조 픽스처 `data/tests/regime/fixtures/manual_reconciliation.json`을 만들고 허용 오차 **0(정확 일치)** 으로 비교한다 (PD-12 확정 전 임시, 004 research 결정과 같은 처리)

**Checkpoint**: 거리를 손으로 검산할 수 있다

## 묶음 M4 — 후보 제한·정렬과 설명 문장

**Goal**: 줄 세우고 솎아내고, 왜 비슷한지 사람 말로 적는다.

- [ ] T034 (DE) 후보 자격 판정을 `data/src/data_pipeline/regime/candidates.py`에 구현 — 분야 검색은 그 분야 3개월 입력이 완전한 후보, 종합은 **7개 분야가 모두 유효한** 후보만(spec FR-012)
- [ ] T035 (DE) 12개월 초과 이전 제한을 `candidates.py`에 구현. **아래쪽 경계는 두지 않는다** — 자료가 있는 전 구간이 후보다(spec FR-012, Clarification 2026-09-15)
- [ ] T036 (DE) 정렬과 동률 처리를 `candidates.py`에 구현 — 거리 오름차순, **정확한 동률이면 더 오래된 종료월 우선**. 동률은 T015의 반올림된 값이 같을 때다
- [ ] T037 (DE) ±6개월 건너뛰기를 `candidates.py`에 구현 — 뽑은 종료월 주변 ±6개월은 다음 후보에서 제외한다(spec FR-012·FR-014)
- [ ] T038 (DE) 상위 10개 추출과 **부족하면 부족한 대로** 돌려주기를 `candidates.py`에 구현 — 빈자리를 먼 후보로 채우지 않는다(spec FR-013)
- [ ] T039 (DE) 제외 사유별 집계를 `candidates.py`에서 돌려준다 — `MISSING_INPUT` / `WITHIN_12_MONTHS` / `NEIGHBOR_SKIPPED`. 전체 후보의 거리는 저장하지 않고 집계만 남긴다(research R3)
- [ ] T040 (DE) 비슷한 점·다른 점 문장 조립을 `data/src/data_pipeline/regime/explain.py`에 구현 — `|z_질의 − z_후보|` 큰 순으로 **다른 점 최대 2개**, 작은 순으로 **비슷한 점 최대 2개**. 템플릿은 research R8. **자유 생성 문장을 쓰지 않는다**
- [ ] T041 [P] (DE 테스트) 12개월 제한과 ±6개월 건너뛰기를 `data/tests/regime/test_candidates.py`에 고정 — 인접 월이 가까운 픽스처 구간에서 하나만 남는다
- [ ] T042 [P] (DE 테스트) 동률이 오래된 월 우선으로 풀리는지 `test_candidates.py`에 고정
- [ ] T043 [P] (DE 테스트) 후보가 2개뿐일 때 **3개로 채우지 않음**을 `test_candidates.py`에 고정(SC-004)
- [ ] T044 [P] (DE 테스트) 설명 문장에 원값과 단위가 들어가고 **확률·추천·원인 단정 낱말이 0건**임을 `data/tests/regime/test_explain.py`에 고정 — 금지 낱말 목록으로 검사한다
- [ ] T045 [P] (DE 테스트) 설명 문장이 "높다/낮다"만 쓰고 "좋다/나쁘다"를 쓰지 않음을 `test_explain.py`에 고정(research R8)

**Checkpoint**: 사례 목록이 나오고, 왜 비슷한지 오해 없는 문장으로 설명된다

## 묶음 M5 — 전 구간 사전 계산과 적재

**Goal**: 자료 전 구간을 미리 계산해 DB에 넣는다. 조회는 이 결과를 읽을 뿐이다.

- [ ] T046 (DE) 검색 상태 판정을 `data/src/data_pipeline/regime/snapshot.py`에 구현 — `NOT_COMPUTABLE` / `NEAREST_ONLY`. **문턱 값을 코드에 두지 않는다**(PD-09 미확정, research R6)
- [ ] T047 (DE) 한 분야가 `NOT_COMPUTABLE`이면 **종합도 `NOT_COMPUTABLE`** 로 만드는 규칙을 `snapshot.py`에 구현 — 6개 분야로 계산하지 않는다(spec US3 시나리오 6)
- [ ] T048 (DE) 기준월 하나의 snapshot 조립을 `snapshot.py`에 구현 — 척도·표시 후보·후보 집계·`data_asof`(대상월·발표일 **복사본**)·버전 셋
- [ ] T049 (DE) `snapshot_key` 조립을 `snapshot.py`에 구현 — `{mode}:{fieldId|ALL}:{baselineMonth}:{dataVersion}:{registryVersion}:{modelVersion}`. **`baseline_origin`과 검색 경로는 키에 넣지 않는다**(data-model §2)
- [ ] T050 (DE) 모델 버전 상수 `REGIME-V1`을 `snapshot.py`에 두고, 무엇이 바뀌면 올려야 하는지를 주석으로 적는다(data-model §5)
- [ ] T051 (DE) PostgreSQL 적재를 `data/src/data_pipeline/regime/load.py`에 구현 — `snapshot_key` 기준 upsert로 **멱등**하게 한다
- [ ] T052 (DE) 전 구간 순회를 `data/scripts/precompute_regime.py`에 구현 — 척도가 나오는 모든 기준월 × 모드 8. 소요 시간을 출력한다(spec FR-033 기록 대상, 목표로 제시하지 않는다)
- [ ] T053 (DE) 중간 산출을 `data/processed/`에 Parquet로 남긴다 — 파티션 규칙은 `data/CLAUDE.md`(임의 변경 금지)
- [ ] T054 [P] (DE 테스트) 배치를 두 번 돌려도 같은 행이 나옴을 `data/tests/regime/test_precompute.py`에 고정(멱등, SC-002)
- [ ] T055 [P] (DE 테스트) 자료 버전이 `FIXTURE-`로 시작함을 `test_precompute.py`에 고정 — 접두사가 빠지면 폐기 조건이 무너진다
- [ ] T056 [P] (DE 테스트) 한 분야 계산 불가가 종합으로 전파됨을 `test_precompute.py`에 고정
- [ ] T057 [P] (DE 테스트) `snapshot_key`에 `baseline_origin`이 들어가지 않음을 `test_precompute.py`에 고정 — 같은 월을 T_g로 얻든 지정하든 결과가 하나여야 한다

**Checkpoint**: DB에 검색 결과가 들어 있고, 다시 돌려도 같다

## 묶음 M6 — 조회 API

**Goal**: 적재된 행을 HTTP로 내준다. **계산 코드가 이 묶음에 없다.**

- [ ] T058 (DB) Flyway 마이그레이션 `backend/src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__regime_search.sql` 작성 — `regime_search_snapshot`·`regime_search_case`·`regime_search_log`. 컬럼·제약·인덱스는 [data-model.md](data-model.md) §2~§4. **decimal은 예외 없이 JSONB**
- [ ] T059 [P] (BE) 도메인 모델을 `regime/domain/`에 작성 — `SearchStatus`·`StatusReason`·`SearchMode`·`BaselineOrigin` enum과 `SnapshotKey` VO. Spring·JPA·Jackson import 금지(ADR-0001)
- [ ] T060 [P] (BE) JPA 엔티티 `RegimeSnapshotJpaEntity`·`RegimeCaseJpaEntity`를 `regime/infrastructure/persistence/`에 작성 — JSONB 컬럼은 `@JdbcTypeCode(JSON)`
- [ ] T061 (BE) `*Mapper`(정적 `toDomain`)와 저장소 구현을 `regime/infrastructure/persistence/`에, 포트는 `regime/application/repository/`에 작성 — 조회 전용이라 저장 메서드를 두지 않는다
- [ ] T062 (BE) `RegimeSearchService`를 `regime/application/service/RegimeSearchService.java`에 작성 — `snapshot_key`로 조회하고 없으면 404. **척도·거리를 계산하는 코드가 없어야 한다**
- [ ] T063 (BE) `GET /regime-searches`를 `regime/presentation/controller/RegimeSearchController.java`에 작성 — 계약 [contracts/regime-search-api.md](contracts/regime-search-api.md) §1. `limit`은 3 또는 10만 허용
- [ ] T064 (BE) `GET /regime-searches/cases/{endMonth}`를 같은 컨트롤러에 작성 — 계약 §2. `mode`·`field`·`baselineMonth`를 함께 받는다
- [ ] T065 (BE) `GET /regime-searches/baseline-months`를 같은 컨트롤러에 작성 — 계약 §3
- [ ] T066 [P] (BE) 응답 DTO를 `regime/presentation/response/`에 record로 작성 — decimal은 `@JsonSerialize(using = PlainBigDecimalSerializer.class)`로 **문자열**. 전역 등록하지 않는다
- [ ] T067 (BE) `NOT_COMPUTABLE`을 **200**으로 내보내는 경로를 서비스·컨트롤러에 구현 — 503·500을 쓰지 않는다(계약 §1). 자료 범위 밖 기준월만 404다
- [ ] T068 [P] (BE 테스트) 계약 예시와 응답의 동치를 `backend/src/test/java/com/ssafy/thorp/regime/presentation/RegimeSearchApiIT.java`에 고정 — 세 엔드포인트 전부
- [ ] T069 [P] (BE 테스트) `NOT_COMPUTABLE`이 200이고 `cases`가 빈 배열임을 `RegimeSearchApiIT`에 고정(SC-005)
- [ ] T070 [P] (BE 테스트) 응답에 `retention`·유지율·`% 유사`·배지 낱말이 **0건**임을 `RegimeSearchApiIT`에 고정(FR-018·FR-022·FR-031)
- [ ] T071 [P] (BE 테스트) 마이그레이션 구조 검증을 `backend/src/test/java/com/ssafy/thorp/regime/infrastructure/persistence/RegimeMigrationTest.java`에 작성 — 제약·인덱스 존재, `uk_regime_case_month`가 같은 월을 두 번 막는다
- [ ] T072 [P] (BE 테스트) 같은 요청을 두 번 보내면 응답이 **완전히 같음**을 `RegimeSearchApiIT`에 고정(SC-002)

**Checkpoint**: API가 계약대로 답한다. 여기까지가 Foundational이고, 이제 화면을 붙일 수 있다

---

# Phase 3: User Story 1 — 분야 기준으로 비슷했던 때를 찾는다 (P1) 🎯 MVP

**Goal**: 관심 분야에서 거리순 사례를 처음 3개(더 보기 최대 10개) 관측 구간·비슷한 점·다른 점과 함께 본다.

**Independent Test**: 분야 하나를 골라 검색해 모델 v1 규칙대로 정렬되는지, 3개/10개가 보이며 후보가
부족하면 채우지 않는지, 각 사례에 관측 구간·비슷한 점·다른 점이 **먼저** 보이는지 확인한다.

**묶음 M7** · `feat(fe)/{티켓}-regime-result`

- [ ] T073 [P] [US1] (FE) 타입 정의를 `frontend/src/types/regime.ts`에 작성 — 계약 응답 그대로. decimal은 `string`
- [ ] T074 [P] [US1] (FE) API 클라이언트를 `frontend/src/api/regime.ts`에 작성 — 세 엔드포인트. 오류를 화면 문구로 바꾸지 않고 그대로 올린다
- [ ] T075 [US1] (FE) `useRegimeSearch` 훅을 `frontend/src/hooks/useRegimeSearch.ts`에 작성 — 늦게 온 응답이 최신 것을 덮지 않도록 순번으로 막는다(`useSearchCandidates`와 같은 방식)
- [ ] T076 [US1] (FE) 사례 목록을 `frontend/src/components/regime/CaseList.tsx`에 작성 — 관측 구간·비슷한 점·다른 점을 **먼저** 보이고 거리·기여도는 상세로 넘긴다(spec FR-015)
- [ ] T077 [US1] (FE) '더 보기' 동작을 `CaseList.tsx`에 붙인다 — `limit=10`으로 다시 부른다. **후보가 부족하면 부족한 대로** 보이고 빈자리를 만들지 않는다
- [ ] T078 [P] [US1] (FE) 검색 페이지를 `frontend/src/pages/RegimeSearchPage.tsx`에 작성 — 분야 선택과 결과 영역. **거리 함수·척도·지표 조합을 고르는 항목을 두지 않는다**(US1 시나리오 5)
- [ ] T079 [P] [US1] (FE) CSS Module을 컴포넌트마다 작성 — `--thorp-*` 토큰만 쓴다(`frontend/docs/design-system.md`). Tailwind는 레지스트리 UI 전용
- [ ] T080 [P] [US1] (FE 테스트) 사례 3개 표시와 '더 보기' 10개를 `frontend/src/components/regime/CaseList.test.tsx`에 고정
- [ ] T081 [P] [US1] (FE 테스트) 후보 2개일 때 **빈자리가 렌더링되지 않음**을 `CaseList.test.tsx`에 고정(SC-004)

**Checkpoint**: US1이 독립적으로 동작한다 — 분야를 고르면 사례가 보인다

---

# Phase 4: User Story 2 — 검색 상태와 참고 사례 표시를 이해한다 (P1)

**Goal**: 결과 옆에서 어느 상태인지 보고, 문턱 교정 전에는 '최근접 참고 사례'임을 안다.

**Independent Test**: 문턱 미교정 상태에서 '최근접 참고 사례' 표시와 '유사성 미판정' 상태가 보이는지,
상수 계열 분야에서 '계산 불가'가 되는지, `'87% 유사'` 같은 표현이 없는지 확인한다.

**묶음 M7 (이어서)** · 같은 브랜치

- [ ] T082 [US2] (FE) 검색 상태 표시를 `frontend/src/components/regime/SearchStatusBadge.tsx`에 작성 — 세 상태와 사유를 **첫 사례와 같은 화면에** 둔다(spec FR-019). 상태 문구는 응답의 `statusNote`를 쓰고 화면에서 지어내지 않는다
- [ ] T083 [US2] (FE) '최근접 참고 사례' 표시를 `CaseList.tsx`에 붙인다 — 응답의 `caseLabel`이 있을 때만(spec FR-020)
- [ ] T084 [US2] (FE) `NOT_COMPUTABLE`·후보 0건을 **정상 화면 + 한 문장 안내**로 그리는 빈 상태를 `frontend/src/components/regime/EmptyResult.tsx`에 작성 — **오류 화면을 쓰지 않는다**(US2 시나리오 4)
- [ ] T085 [P] [US2] (FE 테스트) 세 상태의 표시와 `'% 유사'`·`'충분히 유사'`·`'대표 유사국면'` 낱말 **0건**을 `frontend/src/components/regime/SearchStatusBadge.test.tsx`에 고정(SC-006)
- [ ] T086 [P] [US2] (FE 테스트) 계산 불가에서 오류 화면이 뜨지 않음을 `frontend/src/components/regime/EmptyResult.test.tsx`에 고정
- [ ] T087 [P] [US2] (FE) 예시 자료 표시를 `RegimeSearchPage.tsx`에 붙인다 — `versions.sampleData`가 `true`면 **숨길 수 없는 자리에** 표시한다(008 FR-022)

**Checkpoint**: 결과를 과신할 수 없게 상태가 늘 함께 보인다

---

# Phase 5: User Story 3 — Thorp 종합 비교를 선택해서 연다 (P2)

**Goal**: 7개 분야 거리를 같은 비중으로 결합한 사례와 분야별 거리·기여도를 함께 본다.

**Independent Test**: 분야 보기에서 종합을 열어 기준월이 T_all로 바뀔 때 알림이 보이는지, 분야별
거리와 기여도가 함께 보이는지, 한 분야가 계산 불가일 때 종합도 계산 불가인지 확인한다.

**묶음 M8** · `feat(fe)/{티켓}-regime-detail-composite`

- [ ] T088 [US3] (FE) 모드 전환을 `RegimeSearchPage.tsx`에 붙인다 — 'Thorp 종합 비교'를 누르면 `mode=composite`로 다시 부른다
- [ ] T089 [US3] (FE) 기준월 변경 한 문장 알림을 `frontend/src/components/regime/BaselineChangeNotice.tsx`에 작성 — 문구는 `baseline-months` 응답의 `note`를 쓴다(US3 시나리오 1)
- [ ] T090 [US3] (FE) 종합 사례의 분야별 거리·기여도 표시를 `frontend/src/components/regime/FieldBreakdown.tsx`에 작성 — 닮은 점과 차이를 **함께** 보인다(US3 시나리오 2)
- [ ] T091 [US3] (FE) 동일가중 설명을 `FieldBreakdown.tsx`에 붙인다 — **"Thorp의 설계 선택"** 이라고 적고 *"모든 지표의 중요성이 같다"* 고 쓰지 않는다(FR-010, US3 시나리오 5)
- [ ] T092 [P] [US3] (FE 테스트) 종합이 `NOT_COMPUTABLE`일 때 6개 분야로 그리지 않음을 `frontend/src/components/regime/FieldBreakdown.test.tsx`에 고정(US3 시나리오 6)
- [ ] T093 [P] [US3] (FE 테스트) 동일가중 문구와 금지 문구를 `FieldBreakdown.test.tsx`에 고정

---

# Phase 6: User Story 4 — 과거 기준월과 자료 기준 시점을 안다 (P2)

**Goal**: 과거 기준월을 지정해 검색하고, 결과가 어느 시점 자료에 기반하는지 늘 본다.

**Independent Test**: 과거 기준월을 지정한 뒤 그 월이 화면에 유지되는지, 결과 옆에 대상월과 발표일이
늘 보이는지, 유지율이 **보이지 않는지** 확인한다.

**묶음 M8 (이어서)** · 같은 브랜치

- [ ] T094 [US4] (FE) 기준월 지정을 `RegimeSearchPage.tsx`에 붙인다 — 지정한 월을 유지하고 **화면에 항상 표시한다**(spec FR-003)
- [ ] T095 [US4] (FE) 자료 기준 시점 표시를 `frontend/src/components/regime/DataAsOf.tsx`에 작성 — 대상월 + 발표일. 값이 개정된 경우에만 개정 안내를 한 줄 덧붙인다(spec FR-031)
- [ ] T096 [US4] (FE) 사례 상세를 `frontend/src/components/regime/CaseDetail.tsx`에 작성 — 거리·기여도·지표별 z·척도(중앙값·산포·방법·구간). 손계산 대조가 가능해야 한다(spec FR-028)
- [ ] T097 [US4] (FE) `eraPosition`이 `null`이면 **그 자리를 비운다** — `0`이나 `"-"`로 채우지 않는다(spec FR-017, 원칙 VI)
- [ ] T098 [P] [US4] (FE 테스트) 유지율·`retention`·겹침 비율이 화면에 **0건**임을 `frontend/src/components/regime/DataAsOf.test.tsx`에 고정(FR-031, SC-007)
- [ ] T099 [P] [US4] (FE 테스트) 대상월·발표일이 늘 보이고, 개정이 없으면 개정 안내가 없음을 `DataAsOf.test.tsx`에 고정

---

# Phase 7: Polish & 교차 관심사

**묶음 M9** · `docs/{티켓}-regime-verification`

- [ ] T100 [P] (FE 테스트) 010 화면 코드에 **지표 ID·지표명·분야 이름이 0건**임을 `frontend/src/components/regime/indicatorAgnostic.test.tsx`에 고정 — 007의 전략명 검사와 같은 방식이고, 대상 파일은 **손 목록이 아니라 `src`를 훑어** 모은다
- [ ] T101 [P] (DOCS) quickstart S1~S8을 실제로 실행하고 결과를 `specs/010-economic-regime-search/quickstart.md` 하단 "실행 기록"에 남긴다 — 통과·실패·미실행을 그대로 적는다
- [ ] T102 (DOCS) **폐기 예행연습**: 임시 대역 세 디렉터리를 지워 보고 어디가 깨지는지 확인한 뒤 되돌린다. `macro/fixture/`를 쓰는 테스트만 깨져야 한다(quickstart S9). 결과를 실행 기록에 남긴다
- [ ] T103 [P] (DOCS) 사전 계산 배치 소요 시간·처리 건수를 실행 기록에 남긴다 — **목표로 제시하지 않고 측정값으로만 적는다**(spec FR-033, 원칙 IX)
- [ ] T104 [P] (DOCS) 확인할 수 없었던 항목을 quickstart "지금 확인할 수 없는 것" 표와 대조해 갱신한다 — 008·011·PD-09·PD-10 대기 항목
- [ ] T105 (DOCS) `specs/README.md`의 010 행 상태를 갱신하고, 008에 요구하는 것(`MacroIndicatorPort` 계약)을 008 spec에서 다시 확인한다

---

## Dependencies & 실행 순서

```
Phase 1 Setup (M1 일부)
   ↓
Phase 2 Foundational  M1 → M2 → M3 → M4 → M5 → M6     ← 직렬. 앞 묶음의 함수를 뒤가 부른다
   ↓
Phase 3 US1 (M7) ──┐
Phase 4 US2 (M7) ──┘ 같은 브랜치. US1 먼저 끝내고 US2를 얹는다
   ↓
Phase 5 US3 (M8) ──┐
Phase 6 US4 (M8) ──┘ 같은 브랜치
   ↓
Phase 7 Polish (M9)
```

**스토리 독립성**: US1이 MVP이고 혼자 성립한다. US2는 US1의 화면에 상태 표시를 얹으므로 US1에
의존한다. US3·US4는 US1·US2와 독립이지만 같은 페이지를 만지므로 브랜치를 나누면 충돌한다 —
그래서 M8 하나로 묶었다.

**병렬 기회**: 묶음 안에서만 있다.

- M1: T006·T009·T011·T012·T013이 병렬
- M2: T021~T024 (테스트 4개) 병렬
- M3: T030~T033 병렬
- M4: T041~T045 병렬
- M5: T054~T057 병렬
- M6: T059·T060·T066과 T068~T072 병렬
- M7: T073·T074·T078~T081 / T085~T087 병렬
- M8: T092·T093 / T098·T099 병렬

## Implementation Strategy

**MVP = Phase 1 + Phase 2 + Phase 3(US1)**. 여기까지면 "분야를 고르면 비슷했던 과거가 보인다"가
성립한다. 다만 **Phase 4(US2)까지 함께 내보내는 것을 권한다** — 상태 표시 없이 사례만 보이면
사용자가 '가까운 순서'를 '충분히 비슷함'으로 읽고, 그것이 spec이 가장 경계하는 오독이다.

**계산을 먼저 만드는 이유**: M2~M5가 010의 전부다. 화면과 API는 그 결과를 옮기는 일이고, 틀렸을 때
드러나는 곳도 계산이다. 007이 화면부터 만들어 워커 없이 숫자를 주입하게 된 전철을 밟지 않는다.

**이 태스크로 확인할 수 없는 것**: 픽스처 위의 결과라 "사례가 경제적으로 그럴듯한가"는 008 완성
후에야 말할 수 있다. quickstart "지금 확인할 수 없는 것" 표가 그 목록이며, 여기 나온 숫자를 성과로
인용하지 않는다.
