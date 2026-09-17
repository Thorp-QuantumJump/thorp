# Tasks: 경제환경 검색 (비슷했던 때 보기)

**Input**: Design documents from `specs/010-economic-regime-search/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: 포함한다. constitution "완료의 정의" 조건 2가 *"테스트 또는 동작 확인 완료"* 를 Task 완료
조건으로 못 박아 선택 사항이 아니다. 010은 **계산이 본체**라 시험의 무게중심도 `data/`에 있다.

> **2026-09-16 개정**: spec Clarifications(Session 2026-09-16) 8건과 그에 따른 설계 변경을 반영했다.
> 가장 크게 바뀐 것은 **M1이 DE 단독이 된 것**이다 — Backend의 `MacroIndicatorPort`(등록표 전용)를
> 쓰는 곳이 `baseline-months` 엔드포인트 하나뿐이라 M6 안에서 끝난다.
>
> **2026-09-16 기준 정리**: 구현 기준은 D21의 **모델 v1**이다. 공통 척도를 질의와 모든 후보에
> 동일하게 적용하고, `era.py`는 시대별 위치를 상세의 보조 설명으로만 만든다(FR-017).
> v2는 [d21-amendment.md](d21-amendment.md)의 **검증 대기 제안**이며 구현 선행 조건이 아니다.
> 기존 태스크 번호 T001~T155와 사용성 관찰 M10 분리는 유지한다.
>
> **2026-09-16 2차**: `/speckit-analyze`가 잡은 두 가지를 고쳤다 — ① 번호가 중복된 태스크를 없애고
> 당시 전체를 1부터 다시 매겼다(138개, 현재는 후속 보완을 포함해 154개), ② spec FR-034(계산 실패·조회 실패)에 대응하는 태스크 넷을
> M5·M6·M7에 넣었다. 원칙 VI가 *"계산 실패도 정상 화면 흐름으로 처리한다"* 를 요구하는데 그 경로가
> 비어 있었다.

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
| `(DOCS)` | 문서·수동 확인 기록 | `be` — 이 계획의 검증·관찰 문서 묶음 |

Worker(`worker/`)는 **변경하지 않는다.** 브로커도 쓰지 않는다 — 010은 동기 조회다.

---

## 브랜치 묶음 — 이 표가 티켓·브랜치·MR 단위다

**`T###`는 브랜치 단위가 아니다.** constitution "Jira 연계"가 `작업 묶음 = 1 Branch = 1 MR`로 정하므로
브랜치·MR·Jira Task 단위는 아래 **10개 묶음**이다.

| 묶음 | 브랜치 | 파트 | Jira 제목(안) | 태스크 | 검증 명령 |
|---|---|---|---|---|---|
| **M1** | `feat(de)/{티켓}-regime-macro-source` | DE | `[DE] 경제환경 지표 소스 계약과 격리된 임시 대역` | T001~T013 | `cd data && ruff check src scripts tests && pytest` |
| **M2** | `feat(de)/{티켓}-regime-scale` | DE | `[DE] 척도와 지표별 비교 시점` | T014~T028 | 〃 |
| **M3** | `feat(de)/{티켓}-regime-distance` | DE | `[DE] 거리·기여 계산과 시대별 보조 설명` | T029~T042 | 〃 |
| **M4** | `feat(de)/{티켓}-regime-candidates` | DE | `[DE] 후보 제한·전체 순위와 설명 문장` | T043~T057 | 〃 |
| **M5** | `feat(de)/{티켓}-regime-precompute` | DE | `[DE] 재계산 범위 판정과 사전 계산 적재` | T058~T079 | 〃 |
| **M6** | `feat(be)/{티켓}-regime-search-api` | DB·BE | `[BE] 경제환경 지표 등록표 포트와 검색 조회 API` | T080~T109 | `cd backend && ./gradlew spotlessApply build` |
| **M7** | `feat(fe)/{티켓}-regime-result` | FE | `[FE] 분야 검색 결과와 검색 상태 표시` | T110~T130 | `cd frontend && npm test && npm run lint && npm run build` |
| **M8** | `feat(fe)/{티켓}-regime-detail-composite` | FE | `[FE] 사례 상세와 Thorp 종합 비교` | T131~T148 | 〃 |
| **M9** | `docs(be)/{티켓}-regime-verification` | DOCS | `[DOCS] quickstart 실행 기록과 임시 대역 폐기 예행연습` | T149~T154 | 수동 — quickstart.md S1~S9 |
| **M10** | `docs(be)/{티켓}-regime-usability` | DOCS | `[DOCS] 경제환경 검색 사용성 관찰 6~8명` | T155 | 수동 — 관찰 기록 |

**묶음 순서는 M1 → M9 직렬이다.** M2~M5는 앞 묶음의 함수를 직접 부르고, M6은 M5가 적재한 행이
있어야 조회할 것이 있으며, M7·M8은 M6의 응답을 읽는다. 병렬로 딸 수 있는 묶음은 없다 —
묶음 **안에서만** `[P]`로 병렬화한다.

**M10은 M9와 같은 줄에 두지 않는다.** spec SC-008이 *"010 구현이 끝난 뒤"* 로 정해 **M9까지 끝나야 시작할 수 있고**, 사람 6~8명을 섭외·관찰·정리하는 일이라 기간이 주 단위다. M9에 넣으면 문서 작업이 끝난 뒤에도 관찰이 끝날 때까지 MR이 닫히지 않는다 — `1 티켓 = 1 브랜치 = 1 MR`이 깨진다. 그래서 **별도 티켓**이다.

### 묶음별 MR 체크포인트

| 묶음 | 머지 전에 이것이 참이어야 한다 |
|---|---|
| M1 | 시계열 픽스처 **열** 모양이 다 있고, `macro/fixture/` 밖에서 픽스처를 이름으로 참조하는 코드가 0건, `THORP_ENV=prod` + `--source fixture`면 적재 전에 실패 |
| M2 | 구멍 뚫린 계열이 60개월 미만으로 판정된다. 상수 계열이 IQR로 넘어가고 완전 상수인 공통 척도는 해당 분야·종합을 계산 불가로 만든다. 시대별 보조 척도가 없으면 그 설명만 제한한다. 한 달 결측이 그 **지표만** 두 달로 줄이고, 세 달 결측이 분야를 `QUERY_INPUT_MISSING`으로 만든다. 보정 상수가 `Φ⁻¹(0.75)` 실계산값이다 |
| M3 | 수기 계산과 정확히 일치한다. 전체 거리 0이면 기여 **키가 없다**. 명목 비중과 실제 거리 기여가 함께 나온다. `mode=FIELD`에 분야 기여도가 없다. **질의와 모든 후보에 같은 m·s가 적용된다. 시대별 보조 위치가 순위·거리에 들어간 자리는 0건이다** |
| M4 | 12개월 이내 제외·**순차** ±6개월 건너뛰기·동률 오래된 월 우선이 고정됐다. 걸러진 만큼 아래 순위가 올라와 표시 개수를 채운다. 후보 부족 시 채우지 않는다. **전체 유효 후보 목록에는 건너뛰기가 적용되지 않는다.** 설명 문장에 자유 생성이 없다 |
| M5 | `t ≥ M`만 다시 돈다. 배치를 **두 번 돌려도 행 수가 늘지 않는다**(`content_hash`). 결과가 달라지면 `revision`이 오른다. `regime_baseline_month`가 함께 적재되고 자료 버전이 `FIXTURE-`로 시작한다. **중간에 죽여도 부분 적재가 남지 않고 이전 `revision`이 그대로 조회된다** |
| M6 | 계약 예시와 응답이 일치한다. `NOT_COMPUTABLE`이 **200**이고 503을 쓰지 않는다. **조회 실패가 `NOT_COMPUTABLE`로 둔갑하지 않는다.** `regime` 패키지에 계산 코드도 지표 시계열 조회도 0건. prod 프로파일 + `source: fixture`면 기동 실패 |
| M7 | 배지·확률 표현 0건. 후보 부족 시 빈자리가 없다. 예시 자료 표시·역사 비교 표시·빠진 지표/월 표기가 보인다. **조회 실패 화면이 빈 상태 화면과 다르다.** 다섯 표시가 문구를 동반하고 명암비를 만족한다 |
| M8 | 모드 전환 시 한 문장 알림이 뜨고 기준월이 늘 보인다. 유지율이 화면에 0건. 지표 id·지표명·분야 이름이 화면 코드에 0건. **상세에 공통 척도가 보여 손계산이 되고, 시대별 위치는 보조 설명으로 구분된다. 키보드만으로 다섯 표시에 모두 도달하고 좁은 폭에서도 유지된다** |
| M9 | quickstart S1~S9 실행 기록이 남고, 폐기 예행연습에서 운영 코드가 깨지지 않음을 확인했다 |
| M10 | 6~8명 관찰을 **실제로 수행**했고, 참여자별 두 질문의 답과 막힌 지점이 기록으로 남았다. 관찰하지 않은 것을 수행한 것처럼 적은 항목이 0건이다(SC-008) |

## Path Conventions

- 파이프라인: `data/src/data_pipeline/regime/…`, 테스트는 `data/tests/regime/…`
- 백엔드: `backend/src/main/java/com/ssafy/thorp/regime/…`, 테스트는 `backend/src/test/java/com/ssafy/thorp/regime/…`
- 마이그레이션: `backend/src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__regime_search.sql`
- 프론트엔드: `frontend/src/…`
- ⚠ 임시 대역(008 완성 시 통째 삭제) **두 곳**:
  `data/src/data_pipeline/regime/macro/fixture/`(시계열),
  `backend/src/main/java/com/ssafy/thorp/regime/infrastructure/macro/fixture/`(등록표)
- 커밋 전 `cd backend && ./gradlew spotlessApply` 필수

---

# Phase 1: Setup (공유 골격)

**Purpose**: 패키지 골격과 진입점 자리 만들기 · **묶음 M1**

- [X] T001 (DE) `data/src/data_pipeline/regime/__init__.py`와 하위 모듈 자리(`rounding.py`·`scale.py`·`comparison.py`·`distance.py`·`era.py`·`candidates.py`·`explain.py`·`snapshot.py`·`watermark.py`·`load.py`, 그리고 `macro/` 패키지)를 빈 파일로 만들고 각 모듈의 책임을 docstring에 적는다
- [X] T002 [P] (DE) `data/scripts/precompute_regime.py` 진입점 골격을 argparse로 만든다 — `--source`(`fixture` 또는 `macro`, 기본 `macro`)·`--all`·`--since`·`--baseline-month`·`--dry-run` 플래그만 정의하고 본체는 M5에서 채운다

**Checkpoint**: 골격이 서고 `ruff check`가 통과한다

---

# Phase 2: Foundational (모든 스토리를 막는 선행 작업)

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 어떤 사용자 스토리도 시작할 수 없다. 010은 계산이 본체라
Foundational이 크다 — 화면과 API는 그 결과를 옮기는 일이다.

## 묶음 M1 — 지표 소스와 격리된 임시 대역

**Goal**: 008이 없어도 배치가 돌 자리를 만들되, 008이 오면 **`macro/fixture/` 한 디렉터리를 지우면 끝나도록** 격리한다. Backend 쪽 등록표 대역은 M6에 있다.

- [X] T003 (DE) `MacroIndicatorSource` 프로토콜을 `data/src/data_pipeline/regime/macro/source.py`에 작성 — `registry()`·`monthly_series()`·`raw_series()`·`baseline_months()`·`publication()`·`data_version()`·`changed_since()`·`is_sample_data()`. 시그니처와 뜻은 [contracts/macro-indicator-port.md](contracts/macro-indicator-port.md) §1
- [X] T004 [P] (DE) 소스가 돌려줄 값 타입을 `macro/types.py`에 dataclass로 작성 — `IndicatorRegistry`(분야 이름·순서 포함)·`MonthlyValue`·`BaselineMonths`·`Publication`. **결측월은 키가 없다**(0이나 직전 값으로 채우지 않는다)
- [X] T005 (DE) ⚠ 픽스처 지표 시계열을 `macro/fixture/indicators.json`에 작성 — 17개 지표 × 월별 원값·변환값. [contracts/macro-indicator-port.md](contracts/macro-indicator-port.md) §4.4의 **열 모양을 모두 담는다**. 모양 10은 관측 입력은 있지만 후보 직전 60개월은 없는 구간으로, **후보 유지·시대별 보조 설명 제한**을 검증한다(FR-012·FR-017)
- [X] T006 (DE) ⚠ `FixtureMacroIndicatorSource`를 `macro/fixture/source.py`에 작성 — `data_version()`은 **`FIXTURE-`로 시작하는 값**을 돌려주고 `is_sample_data()`는 `True`, `changed_since()`는 `None`(전 구간)
- [X] T007 (DE) 구현체 선택을 `macro/__init__.py`의 팩터리 한 곳에 모은다 — `--source` 플래그만 보고 고른다. **운영 코드가 픽스처 클래스를 이름으로 참조하지 않는다**
- [X] T008 (DE) 운영 적재 가드를 `data/scripts/precompute_regime.py`에 구현 — `THORP_ENV=prod`인데 `--source fixture`면 **적재 전에 종료**한다. 설정 실수로 가짜 자료가 운영에 나가는 경로를 없앤다
- [X] T009 [P] (DE 테스트) 픽스처 **열** 모양이 실제로 담겨 있는지 `data/tests/regime/test_fixture_shapes.py`에 고정 — 모양 하나가 빠지면 그 경로가 조용히 검증 밖으로 나간다
- [X] T010 [P] (DE 테스트) 소스 계약 테스트를 `data/tests/regime/test_macro_source.py`에 작성 — 결측월이 키 없음으로 오는지, 원값·변환값이 둘 다 오는지, 버전 셋이 함께 오는지, `registry()`에 분야 이름·순서가 있는지. **008 구현체가 와도 같은 테스트가 돈다**
- [X] T011 [P] (DE 테스트) prod 가드를 `data/tests/regime/test_guard.py`에 고정 — `THORP_ENV=prod` + `--source fixture`가 실패한다
- [X] T012 [P] (DE 테스트) 픽스처 격리 검사를 `data/tests/regime/test_fixture_isolation.py`에 작성 — `regime` 운영 코드에서 `macro/fixture/` 밖이 픽스처를 이름으로 참조하면 실패한다. 대상 파일은 **손 목록이 아니라 디렉터리를 훑어** 모은다
- [X] T013 [P] (DE 테스트) 지표 id·지표명·분야 이름이 `macro/fixture/` 밖 파이프라인 코드에 **0건**임을 `data/tests/regime/test_indicator_agnostic.py`에 고정(spec FR-004)

**Checkpoint**: 008 없이도 배치가 지표를 읽을 수 있고, 지울 곳이 `macro/fixture/` 하나로 모였다

## 묶음 M2 — 척도와 비교 시점

**Goal**: 질의 직전 자료로 공통 척도(m·s)를 만들고, 어느 달을 비교할지 정한다.
거리 계산에서는 모든 후보에 이 척도를 적용한다. 시대별 보조 설명만 같은 산출 함수를 후보월로 호출한다(FR-005·FR-017).

- [X] T014 (DE) 12자리 반올림 유틸을 `data/src/data_pipeline/regime/rounding.py`에 작성 — 저장·비교 직전에 한 번 적용한다. **순위와 동률 판정은 반올림된 값으로 한다**(research R4)
- [X] T015 (DE) 정규분포 보정 상수를 `scale.py`에 `scipy.stats.norm.ppf(0.75)`로 계산해 둔다 — **1.4826·1.349 상수를 쓰지 않는다**(spec FR-005, research R5)
- [X] T016 (DE) 척도 산출 함수 `scale_for(indicator_id, observation_end)`를 `scale.py`에 만든다 — 구간은 `[observation_end-122, observation_end-3]`. **거리 계산은 질의 기준월 t로 만든 공통 척도만 쓴다**(FR-005). 후보월 c 호출은 `era.py`의 보조 설명에만 허용한다(FR-017)
- [X] T017 (DE) 척도 구간 연속성 판정을 `scale_for`에 구현 — `end-3`에서 과거로 거슬러 세어 **처음 결측을 만나는 곳까지**가 연속 구간이다. 구멍 건너편 자료를 끌어오지 않는다(spec FR-005)
- [X] T018 (DE) 중앙값 m과 MAD 기반 산포 s를 `scale.py`에 구현 — `s = MAD / Φ⁻¹(0.75)`
- [X] T019 (DE) MAD가 0일 때 IQR 대체를 `scale.py`에 구현 — IQR은 **선형 보간**의 75분위 − 25분위, `s = IQR / (2·Φ⁻¹(0.75))`. 방법을 바꾸면 새 모델 버전이다(spec FR-006)
- [X] T020 (DE) 척도를 못 만들 때 사유 `SCALE_WINDOW_TOO_SHORT` / `SCALE_ZERO`를 돌려주는 경로를 `scale.py`에 구현 — 공통 척도 실패는 해당 분야·종합의 계산 불가로 처리하고, 시대별 보조 척도 실패는 설명만 제한한다(FR-005·FR-006·FR-017). **임의 epsilon·클리핑을 넣지 않는다**(FR-007)
- [X] T021 (DE) **지표별 비교 시점 판정**을 `comparison.py`에 구현 — `t-2·t-1·t` 중 자료가 있는 달만 남긴다. **지표마다 따로 정하고 검색 모드가 이 규칙을 바꾸지 않는다**. 값을 평균이나 직전 값으로 채우지 않는다(spec FR-001)
- [X] T022 (DE) 어느 지표의 비교 시점이 **하나도 없으면** 그 분야(종합이면 종합)를 `QUERY_INPUT_MISSING`으로 만드는 경로를 `comparison.py`에 구현 — **그 지표를 빼고 나머지로 계산하지 않는다**(spec FR-007)
- [X] T023 [P] (DE 테스트) 연속 구간 판정을 `data/tests/regime/test_scale.py`에 고정 — 구멍 뒤쪽이 60개월 미만이면 앞쪽에 자료가 더 있어도 못 만든다
- [X] T024 [P] (DE 테스트) MAD에서 IQR로 넘어가는 전환과 둘 다 0인 경우를 `test_scale.py`에 고정
- [X] T025 [P] (DE 테스트) 보정 상수가 근사값 1.4826과 **다름**을 `test_scale.py`에 고정 — 근사값으로 되돌아가면 실패한다
- [X] T026 [P] (DE 테스트) 질의 척도 구간보다 오래된 후보도 **질의의 m·s로 표준화**됨을 `test_scale.py`에 고정 — 후보 직전 이력이 없어도 공통 척도 적용은 가능해야 한다(FR-005·FR-012)
- [X] T027 [P] (DE 테스트) 지표별 비교 시점을 `data/tests/regime/test_comparison.py`에 고정 — 한 지표만 한 달이 빠졌을 때 **그 지표만** 두 달이 되고 다른 지표는 세 달 그대로다
- [X] T028 [P] (DE 테스트) 어느 지표의 세 달이 전부 비면 `QUERY_INPUT_MISSING`이고, 그 지표를 제외한 계산 결과가 나오지 **않음**을 `test_comparison.py`에 고정

**Checkpoint**: 질의 직전 구간의 공통 척도를 만들고, 만들 수 없을 때 사유를 말한다

## 묶음 M3 — 거리·기여와 시대별 보조 설명

**Goal**: 두 달이 얼마나 떨어져 있는지를 숫자로 만든다. **질의와 모든 후보는 같은 자로 잰다**. 시대별 위치는 순위와 분리한다(spec FR-005·FR-017).

- [X] T029 (DE) 기준월 t의 공통 척도를 `distance.py`에서 받아 모든 후보에 재사용한다 — `scale_for(지표, t)`의 결과이며, **후보월 c의 척도를 거리 계산에 넣지 않는다**(FR-005, research R3)
- [X] T030 (DE) 표준화 z를 `distance.py`에 구현 — `z_질의 = (x_질의 − m_t)/s_t`, `z_후보 = (x_후보 − m_t)/s_t`. 질의와 후보에 **같은 m_t·s_t**를 적용한다(FR-005·FR-009)
- [X] T031 (DE) 지표 거리 `E_i`를 `distance.py`에 구현 — **그 지표의 비교 시점들**의 `|z_질의 − z_후보|` 평균. 시점 수가 지표마다 달라도 평균이라 결합이 깨지지 않는다(spec FR-009)
- [X] T032 (DE) 분야 거리 `D_g`(분야 내 `E_i` 산술평균)와 종합 거리(분야 `D_g`의 산술평균)를 `distance.py`에 구현 — **분야 개수를 상수로 박지 않고 등록표가 준 수를 쓴다**. 같은 기준월·분야의 `D_g`는 어느 모드에서든 같아야 한다(spec FR-009, SC-002)
- [X] T033 (DE) 분야 기여도(`D_g` ÷ `ΣD_g`)를 `distance.py`에 구현 — **전체 거리가 0이면 키를 생략한다**(spec Edge Cases). `mode=FIELD`에서는 분야가 하나라 **아예 만들지 않는다**
- [X] T034 (DE) **지표별 명목 비중과 실제 거리 기여**를 `distance.py`에 구현 — `nominalWeight = 1/n`, `distanceShare = E_i ÷ 분야 안 ΣE_i`. 분야 안 `ΣE_i`가 0이면 키를 생략한다(spec FR-010)
- [X] T035 (DE) 모든 산출값에 12자리 반올림을 적용해 돌려준다 — 정렬 전에 고정해야 순위가 흔들리지 않는다
- [X] T036 [P] (DE 테스트) z·E_i·D_g·종합이 수기 계산과 일치함을 `data/tests/regime/test_distance.py`에 고정
- [X] T037 [P] (DE 테스트) 공통 척도가 서로 다른 두 기준월의 픽스처로 **같은 후보의 caseZ도 기준월에 따라 다시 계산됨**을 `test_distance.py`에 고정 — 후보 z를 후보월만으로 캐시하지 않는다(FR-005, research R3)
- [X] T038 (DE) 시대별 보조 위치를 `era.py`에 구현 — 후보월 c 직전 척도를 같은 규칙으로 구하고 research R8의 판정·문장 템플릿을 적용한다. 자료·등록표·모델 버전이 같은 동안 후보월별로 재사용한다. 연속 60개월 미만 또는 MAD·IQR 모두 0이면 **해당 지표 설명만 제한**하고 순위·거리·후보 자격은 바꾸지 않는다. 출력은 문장과 산출 구간·방법이며 시대별 z·분위수는 내보내지 않는다(FR-017·SC-011)
- [X] T039 [P] (DE 테스트) 비교 시점이 두 달인 지표의 `E_i`가 두 값의 평균임을 `test_distance.py`에 고정
- [X] T040 [P] (DE 테스트) 같은 분야의 `D_g`가 분야 모드와 종합 모드에서 같음을 `test_distance.py`에 고정(SC-002)
- [X] T041 [P] (DE 테스트) 전체 거리 0일 때 기여 **키가 없음**과 `mode=FIELD`에 분야 기여도가 없음을 `test_distance.py`에 고정
- [X] T042 [P] (DE 테스트) `data/tests/regime/fixtures/manual_reconciliation.json`에 공통 척도와 독립 계산값을 담아 `test_distance.py`에서 허용 오차 0으로 대조한다(SC-003). `test_era.py`에서는 보조 문장 판정, 이력 부족·0 척도, 같은 자료 버전의 기준월 간 보조 위치 동일성, 보조 설명 유무가 순위·거리에 영향을 주지 않음을 확인한다(FR-017·SC-011)

**Checkpoint**: 공통 척도로 거리를 검산할 수 있고, 시대별 보조 위치는 기준월과 무관하며 순위에 영향을 주지 않는다

## 묶음 M4 — 후보 제한·순위와 설명 문장

**Goal**: 줄 세우고 솎아내고, 왜 비슷한지 사람 말로 적는다.

- [X] T043 (DE) 후보 자격 판정을 `candidates.py`에 구현 — 해당 분야의 **지표별 비교 시점** 입력이 모두 있는 후보만 쓴다. 후보에게 질의보다 많은 시점을 요구하지 않고, **후보 직전 60개월 이력이나 시대별 보조 척도를 자격 조건으로 쓰지 않는다**(FR-012·FR-017)
- [X] T044 (DE) 12개월 초과 이전 제한을 `candidates.py`에 구현. **아래쪽 경계는 두지 않는다** — 자료가 있는 전 구간이 후보다(spec FR-012)
- [X] T045 (DE) 정렬과 동률 처리를 `candidates.py`에 구현 — 거리 오름차순, **정확한 동률**(반올림된 값이 같을 때)이면 더 오래된 종료월 우선
- [X] T046 (DE) ±6개월 건너뛰기를 `candidates.py`에 **순차로** 구현 — 1위를 담고 그 종료월 ±6개월을 **남은 후보에서 제외한 뒤 다시 1위를 담는** 식으로 표시 개수까지 채운다. 상위 10개를 먼저 뽑아 거르지 않는다(spec FR-012·FR-014)
- [X] T047 (DE) **±6개월 건너뛰기를 적용하지 않은 전체 유효 후보의 순위·거리 목록**을 `candidates.py`에서 돌려준다 — `[{endMonth, distance, rank}]`. 건너뛰기는 순차 규칙이라 걸러 두면 011이 가용성 필터 뒤에 다시 적용할 수 없다(spec FR-030, data-model §2.3)
- [X] T048 (DE) 상위 10개 추출과 **부족하면 부족한 대로** 돌려주기를 `candidates.py`에 구현 — 빈자리를 먼 후보로 채우지 않는다(spec FR-013)
- [X] T049 (DE) 제외 사유별 집계를 `candidates.py`에서 돌려준다 — `MISSING_INPUT` / `WITHIN_12_MONTHS` / `NEIGHBOR_SKIPPED` 세 가지다. 시대별 보조 설명 부족은 후보 제외 사유가 아니다(FR-012·FR-017)
- [X] T050 (DE) 설명 문장 조립을 `explain.py`에 구현 — 공통 척도로 계산한 지표 거리 큰 순으로 다른 점 최대 2개, 작은 순으로 비슷한 점 최대 2개를 골라 **실제 값과 단위**로 서술한다(research R8). **값은 `monthly_series()`의 변환 후 값이다 — 증가율 지표면 증가율이지 지수 원수준이 아니다**(spec FR-015). 시대별 위치는 `era.py`의 별도 보조 문장으로 두고 기본 검색 이유에 혼용하지 않는다. 자유 생성하지 않는다(FR-015·FR-017·FR-022)
- [X] T051 [P] (DE 테스트) 12개월 제한과 **순차** ±6개월 건너뛰기를 `data/tests/regime/test_candidates.py`에 고정 — 인접 월이 가까운 픽스처 구간에서 하나만 남고, **걸러진 만큼 그 아래 순위가 올라와 표시 개수를 채운다**
- [X] T052 [P] (DE 테스트) 동률이 오래된 월 우선으로 풀리는지 `test_candidates.py`에 고정
- [X] T053 [P] (DE 테스트) 후보가 2개뿐일 때 **3개로 채우지 않음**을 `test_candidates.py`에 고정(SC-004)
- [X] T054 [P] (DE 테스트) 전체 유효 후보 목록이 **상위 10개보다 길고 순위가 연속이며 건너뛰기가 적용되지 않았음**을 `test_candidates.py`에 고정
- [X] T055 [P] (DE 테스트) **후보 직전 이력이 없어도 비교 입력이 있으면 후보로 남고 보조 설명만 제한됨**을 `test_candidates.py`에 고정 — 픽스처 모양 10을 쓴다. 보조 척도가 0인 경우도 후보 자격·순위·거리에 영향이 없음을 확인한다(FR-012·FR-017)
- [X] T056 [P] (DE 테스트) 설명 문장에 실제 값과 단위가 들어가고 시대별 상대 위치를 기본 검색 이유로 섞지 않으며, **spec FR-022 ①의 13가지가 0건**임을 `data/tests/regime/test_explain.py`에 고정 — 금지 목록을 구현이나 태스크가 지어내지 않는다
- [X] T057 [P] (DE 테스트) **증가율 지표의 문장 값이 변환 후 값임**을 `test_explain.py`에 고정 — 지수 원수준이 들어가면 실패한다. 더해 `regime` 패키지 전체에서 **`raw_series` 호출이 0건**임을 정적으로 확인한다(spec FR-015, 포트 계약 §1)

**Checkpoint**: 사례 목록이 나오고, 왜 비슷한지 오해 없는 문장으로 설명된다

## 묶음 M5 — 재계산 범위·상태 판정과 적재

**Goal**: 바뀐 만큼만 다시 계산해 DB에 넣는다. 조회는 이 결과를 읽을 뿐이다.

- [X] T058 (DE) **재계산 범위 판정**을 `watermark.py`에 구현 — `changed_since()`가 준 가장 오래된 월 M에 대해 `t ≥ M`인 기준월만 대상이다. `None`이면 계열 시작월을 M으로 삼아 전 구간(spec FR-030, research R3)
- [X] T059 [P] (DE 테스트) `t ≥ M` 규칙을 `data/tests/regime/test_watermark.py`에 고정 — 질의 비교 시점 / 공통 척도 구간 / 후보 관측 구간 / **시대별 보조 척도 구간** 네 경로의 경계값을 확인한다. 마지막 경로는 순위가 같아도 보조 설명이 바뀔 수 있다(FR-017·FR-030, research R3)
- [X] T060 (DE) 검색 상태 판정을 `snapshot.py`에 구현 — `NOT_COMPUTABLE` / `NEAREST_ONLY`와 사유 네 가지. **문턱 값을 코드에 두지 않는다**(PD-09 미확정, research R6)
- [X] T061 (DE) 한 분야가 `NOT_COMPUTABLE`이면 **종합도 `NOT_COMPUTABLE`** 로 만드는 규칙과 **반대 방향**(종합 후보 0건이나 T_all 없음 → 종합만 불가, 분야는 정상)을 `snapshot.py`에 구현(spec FR-039, US3 시나리오 6)
- [X] T062 (DE) 기준월 하나의 snapshot 조립을 `snapshot.py`에 구현 — **공통 척도**·비교 시점·전체 유효 후보 목록·후보 집계·`data_asof`(대상월·발표일·개정 여부 복사본)·버전 셋(data-model §2)
- [X] T063 (DE) 사례 행 조립을 `snapshot.py`에 구현 — 거리·분야 기여·지표별 원값과 공통 척도 z·지표 기여·설명 문장·**시대별 보조 위치(`era_position`)**. 보조 설명을 만들 수 없는 지표는 키를 생략하고 전부 없으면 NULL이다(data-model §4)
- [X] T064 (DE) `snapshot_key` 조립을 `snapshot.py`에 구현 — `{mode}:{fieldId|ALL}:{baselineMonth}:{dataVersion}:{registryVersion}:{modelVersion}`. **검색 경로와 `baseline_origin`은 키에 넣지 않는다**(data-model §2)
- [X] T065 (DE) `content_hash` 계산을 `snapshot.py`에 구현 — 결과 본문만 해시한다. **공통 척도와 시대별 보조 설명을 포함**하고, `computed_at` 같은 매번 바뀌는 시각 값은 넣지 않는다. 순위가 같아도 보조 설명이 바뀌면 새 결과다(FR-030)
- [X] T066 (DE) 모델 버전 상수 `REGIME-V1`을 `snapshot.py`에 두고, 무엇이 바뀌면 올려야 하는지를 주석으로 적는다(data-model §6). v2 검증 제안을 기본 계산 경로에 넣지 않는다(PD-08)
- [X] T067 (DE) PostgreSQL 적재를 `load.py`에 구현 — 직전 `revision`과 `content_hash`가 같으면 **새 행을 만들지 않고**, 다르면 `revision + 1`로 넣는다(spec FR-030)
- [X] T068 (DE) **기준월 기록**을 `load.py`에 구현 — `baseline_months()`가 준 분야별 T_g와 T_all, 사전 계산이 있는 가장 오래된 기준월을 `regime_baseline_month`에 **적재와 같은 트랜잭션에서** 쓴다(data-model §3, 포트 계약 §0.2)
- [X] T069 (DE) **보관 정리**를 `load.py`에 구현 — **적재를 마친 뒤 같은 실행에서** 돌린다(spec FR-029). 각 (모드·분야·기준월)의 최신 revision과 `regime_search_log`가 가리키는 revision만 남긴다(data-model §7)
- [X] T070 (DE) **실패가 부분 적재를 남기지 않게** `load.py`에 구현 — 기준월 하나의 snapshot·사례·기준월 기록을 한 트랜잭션으로 쓰고, 실패하면 그 기준월은 **이전 `revision`이 그대로 남는다**. 실패한 범위와 사유를 기록한다(spec FR-034, SC-010)
- [X] T071 (DE) **철회 명령**을 `data/scripts/precompute_regime.py`에 `--withdraw`로 구현 — 사람이 지정한 범위의 `revision`을 지워 이전 것이 다시 최신이 되게 하고, **철회한 식별 키·범위·사유·시각을 기록**한다. 배치 본체는 철회를 스스로 판단하지 않는다(spec FR-035, SC-012)
- [X] T072 (DE) 순회를 `data/scripts/precompute_regime.py`에 구현 — 워터마크가 정한 기준월 × 모드 8. **소요 시간과 다시 계산한 기준월 수를 출력한다**(spec FR-033, 목표로 제시하지 않는다)
- [X] T073 (DE) 중간 산출을 `data/processed/`에 Parquet로 남긴다 — 파티션 규칙은 `data/CLAUDE.md`(임의 변경 금지)
- [X] T074 [P] (DE 테스트) 배치를 두 번 돌려도 **행 수가 늘지 않음**을 `data/tests/regime/test_precompute.py`에 고정(멱등, SC-002)
- [X] T075 [P] (DE 테스트) M월 지표 값을 바꾸면 **t ≥ M만 재계산하고 결과 내용이 달라진 항목만 revision이 오름**을 `test_precompute.py`에 고정 — 내용이 같은 항목과 t < M 항목은 그대로다. 순위는 같고 시대별 보조 설명만 바뀌는 경우도 포함한다(FR-017·FR-030)
- [X] T076 [P] (DE 테스트) `era_position`이 사례 행에 저장되고 같은 자료·등록표·모델 버전과 후보월이면 기준월이 달라도 같은 보조 문장임을 `test_precompute.py`에 고정. 설명 불가를 0으로 채우지 않는다(SC-011, data-model §4)
- [X] T077 [P] (DE 테스트) 자료 버전이 `FIXTURE-`로 시작함을 `test_precompute.py`에 고정 — 접두사가 빠지면 폐기 조건이 무너진다
- [X] T078 [P] (DE 테스트) 한 분야 계산 불가가 종합으로 전파되고, **반대 방향**(모든 분야 정상 + 종합만 불가)도 픽스처 모양 5로 성립함을 `test_precompute.py`에 고정(spec FR-039)
- [X] T079 [P] (DE 테스트) `snapshot_key`에 검색 경로·`baseline_origin`이 들어가지 않음을 `test_precompute.py`에 고정 — 같은 월을 T_g로 얻든 지정하든 결과가 하나여야 한다

**Checkpoint**: DB에 검색 결과가 들어 있고, 다시 돌려도 늘지 않으며, 바뀐 것만 새 행이 된다

## 묶음 M6 — 등록표 포트와 조회 API

**Goal**: 적재된 행을 HTTP로 내준다. **계산 코드도 지표 시계열 조회도 이 묶음에 없다.**

- [X] T080 (BE) `MacroIndicatorPort` 인터페이스를 `backend/src/main/java/com/ssafy/thorp/regime/application/port/MacroIndicatorPort.java`에 작성 — `registryVersion()`·`fields()`·`indicator(indicatorId)` **셋뿐**. 시계열 메서드를 두지 않는다(포트 계약 §2)
- [X] T081 [P] (BE) 포트가 돌려줄 값 타입을 `regime/application/port/`에 record로 작성 — `FieldRegistration`(id·이름·표시 순서)·`IndicatorRegistration`(이름·단위)
- [X] T082 (BE) ⚠ `FixtureMacroIndicatorAdapter`를 `regime/infrastructure/macro/fixture/`에 작성 — `@ConditionalOnProperty(prefix="thorp.regime.macro", name="source", havingValue="fixture")`. `registryVersion()`은 **`FIXTURE-`로 시작하는 값**을 돌려주고, 담는 것은 **이름·단위·순서뿐**이라 `data/` 픽스처와 겹치지 않는다
- [X] T083 (BE) `RegimeProperties`(`macro.source`, `@NotNull`)를 `regime/infrastructure/config/`에 작성하고 `application-local.yml`에 `fixture`, `application-prod.yml`에 `macro`를 둔다. **코드에 기본값을 박지 않는다**
- [X] T084 [P] (BE 테스트) 포트 계약 테스트를 `MacroIndicatorPortContractTest.java`에 작성 — 등록되지 않은 지표가 empty로 오는지, 분야 순서가 오는지, 버전이 오는지, 조회 실패를 이름 없이 대체하지 않는지. **008 구현체가 와도 같은 테스트가 돈다**
- [X] T085 [P] (BE 테스트) prod 프로파일 + `thorp.regime.macro.source=fixture`면 **기동에 실패**함을 `RegimeProfileGuardTest.java`에, 운영 코드가 `macro/fixture/` 밖에서 픽스처를 이름으로 참조하지 않음을 `FixtureIsolationTest.java`에 고정
- [X] T086 (DB) Flyway 마이그레이션 `backend/src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__regime_search.sql` 작성 — `regime_search_snapshot`·`regime_baseline_month`·`regime_search_case`·`regime_search_log`. 컬럼·제약·인덱스는 [data-model.md](data-model.md) §2~§5. decimal은 JSONB, `snapshot_key`는 `VARCHAR(200)`, snapshot의 공통 `scale`과 사례의 nullable `era_position`을 두고, `regime_search_log.snapshot_id`는 **ON DELETE SET NULL** + `snapshot_key` 사본
- [X] T087 [P] (BE) 도메인 모델을 `regime/domain/`에 작성 — `SearchStatus`·`StatusReason`·`SearchMode`(`FIELD`/`COMPOSITE`)·`BaselineOrigin` enum과 `SnapshotKey` VO. Spring·JPA·Jackson import 금지(ADR-0001)
- [X] T088 [P] (BE) JPA 엔티티 `RegimeSnapshotJpaEntity`·`RegimeBaselineMonthJpaEntity`·`RegimeCaseJpaEntity`를 `regime/infrastructure/persistence/`에 작성 — JSONB 컬럼은 `@JdbcTypeCode(JSON)`
- [X] T089 (BE) `*Mapper`(정적 `toDomain`)와 저장소 구현을 `regime/infrastructure/persistence/`에, 포트는 `regime/application/repository/`에 작성 — 조회 전용이라 저장 메서드를 두지 않는다
- [X] T090 (BE) `RegimeSearchService`를 `regime/application/service/`에 작성 — (모드·분야·기준월)의 **최신 `revision`** 을 읽고 없으면 404. **척도·거리를 계산하는 코드가 없어야 하고**, 008에서 읽는 것은 포트의 등록표뿐이어야 한다
- [X] T091 (BE) `baselineOrigin`을 **요청을 보고 판단**하는 로직을 서비스에 구현 — `baselineMonth`가 오면 `USER_SPECIFIED`, 없으면 `mode`에 따라 `T_G`/`T_ALL`. 저장값을 읽지 않는다(계약 §1)
- [X] T092 (BE) `GET /regime-searches`를 `regime/presentation/controller/RegimeSearchController.java`에 작성 — 계약 §1. `limit`은 3 또는 10만 허용
- [X] T093 (BE) `GET /regime-searches/{searchId}`를 같은 컨트롤러에 작성 — 계약 §2. **006 기록이 고정한 항목을 그대로 연다**. **철회되어 없는 경우와 정리되어 없는 경우를 응답에서 구분**한다(spec FR-035)
- [X] T094 (BE) `GET /regime-searches/cases/{endMonth}`를 같은 컨트롤러에 작성 — 계약 §3. `mode`·`field`·`baselineMonth` 또는 `searchId`를 함께 받는다
- [X] T095 (BE) `GET /regime-searches/candidates`를 같은 컨트롤러에 작성 — 계약 §4. **010은 이 목록에 필터도 ±6개월 건너뛰기도 적용하지 않는다**
- [X] T096 (BE) `GET /regime-searches/baseline-months`를 같은 컨트롤러에 작성 — 계약 §5. **분야 이름은 포트에서, 기준월·범위는 `regime_baseline_month` 표에서** 만든다. 기준월을 008에 직접 묻지 않는다(포트 계약 §0.2)
- [X] T097 [P] (BE) 응답 DTO를 `regime/presentation/response/`에 record로 작성 — `comparisonMonths`·`basis`·`dataAsOf.newerReleases`·`counts{evaluated,eligible,stored,displayed}`와 사례 상세의 공통 `scale`·보조 `eraPosition`을 포함한다. decimal은 `@JsonSerialize(using = PlainBigDecimalSerializer.class)`로 문자열. 전역 등록하지 않는다(계약 §3)
- [X] T098 (BE) `NOT_COMPUTABLE`을 **200**으로 내보내는 경로를 구현하고, 그 응답에도 `dataAsOf`·`versions`·`basis`를 채운다 — 503·500을 쓰지 않는다(계약 §1, SC-007·SC-009). 자료 범위 밖 기준월만 404다
- [X] T099 (BE) **조회 실패를 '계산 불가'와 구분해** 내보내는 경로를 구현 — 저장된 결과를 읽지 못하면 실패로 알리고 빈 결과나 `NOT_COMPUTABLE`로 바꾸지 않는다(spec FR-034, 계약 §1)
- [X] T100 (BE) `sampleData`를 `data_version`의 `FIXTURE-` 접두사로 판정한다 — 008에 묻지 않는다. 등록표 버전도 `FIXTURE-`로 시작하면 함께 표시한다
- [X] T101 [P] (BE 테스트) 계약 예시와 응답의 동치를 `RegimeSearchApiIT.java`에 고정 — 다섯 엔드포인트 전부
- [X] T102 [P] (BE 테스트) 사례 상세의 **공통 scale로 queryZ·caseZ를 모두 대조할 수 있음**과 `eraPosition`이 별도 문장으로만 제공됨을 `RegimeSearchApiIT`에 고정 — 보조 위치의 z·분위수는 없고, 보조 설명을 계산할 수 없어도 사례가 조회된다(FR-017·SC-003·SC-011)
- [X] T103 [P] (BE 테스트) `NOT_COMPUTABLE`이 200이고 `cases`가 빈 배열이며 `dataAsOf`·`basis`가 있음을 고정. **네 사유가 각각 `statusReason`으로 구분돼 나오는지**를 함께 고정한다(SC-005·SC-007·SC-009)
- [X] T104 [P] (BE 테스트) 조회 실패가 `NOT_COMPUTABLE`로 둔갑하지 않고, 중단된 배치가 남긴 부분 적재 항목이 조회되지 않음을 `RegimeSearchApiIT`에 고정(SC-010)
- [X] T105 [P] (BE 테스트) 응답에 **spec FR-022 ①의 13가지가 0건**이고 요청 파라미터에 전략·성과 항목이 0건임을 `RegimeSearchApiIT`에 고정(SC-006, FR-024·SC-001)
- [X] T106 [P] (BE 테스트) 마이그레이션 구조 검증을 `RegimeMigrationTest.java`에 작성 — 제약·인덱스, `uk_regime_case_month`, 내용 중복 방지, 공통 `scale`과 nullable `era_position`을 확인한다. 사전 계산 snapshot·case·baseline 표에 개인 식별 컬럼이 없고, `regime_search_log.user_id`만 006·014가 관리하는 비식별 참조 키임을 확인한다(FR-036·SC-013, data-model §5)
- [X] T107 [P] (BE 테스트) 같은 요청을 두 번 보내면 응답이 **완전히 같음**을 `RegimeSearchApiIT`에 고정(SC-002)
- [X] T108 [P] (BE 테스트) **`regime` 패키지에 계산 코드가 0건**임을 `RegimeNoComputationTest.java`에 정적 검사로 고정(SC-002a) — 산술 연산·`Math.`·`BigDecimal` 산술이 나오면 실패
- [X] T109 [P] (BE 테스트) **`regime` 패키지가 지표 시계열(`macro_indicator_value` 등)을 읽지 않음**을 같은 테스트에 고정 — 008에서 읽는 것은 포트의 등록표뿐이다(포트 계약 §0.1)

**Checkpoint**: API가 계약대로 답한다. 여기까지가 Foundational이고, 이제 화면을 붙일 수 있다

**포트(앞 여섯 태스크)가 먼저다** — `baseline-months`가 분야 이름을 포트에서 가져온다.

---

# Phase 3: User Story 1 — 분야 기준으로 비슷했던 때를 찾는다 (P1) 🎯 MVP

**Goal**: 관심 분야에서 거리순 사례를 처음 3개(더 보기 최대 10개) 관측 구간·비슷한 점·다른 점과 함께 본다.

**Independent Test**: 분야 하나를 골라 검색해 모델 v1 규칙대로 정렬되는지, 3개/10개가 보이며 후보가
부족하면 채우지 않는지, 각 사례에 관측 구간·비슷한 점·다른 점이 **먼저** 보이는지 확인한다.

**묶음 M7** · `feat(fe)/{티켓}-regime-result`

- [X] T110 [P] [US1] (FE) 타입 정의를 `frontend/src/types/regime.ts`에 작성 — 계약 응답 그대로. decimal은 `string`
- [X] T111 [P] [US1] (FE) API 클라이언트를 `frontend/src/api/regime.ts`에 작성 — 다섯 엔드포인트. 오류를 화면 문구로 바꾸지 않고 그대로 올린다
- [X] T112 [US1] (FE) `useRegimeSearch` 훅을 `frontend/src/hooks/useRegimeSearch.ts`에 작성 — 늦게 온 응답이 최신 것을 덮지 않도록 순번으로 막는다(`useSearchCandidates`와 같은 방식)
- [X] T113 [US1] (FE) 사례 목록을 `frontend/src/components/regime/CaseList.tsx`에 작성 — 관측 구간·비슷한 점·다른 점을 **먼저** 보이고 거리·기여는 상세로 넘긴다(spec FR-015)
- [X] T114 [US1] (FE) '더 보기' 동작을 `CaseList.tsx`에 붙인다 — `limit=10`으로 다시 부른다. **버튼 노출은 `counts.stored > counts.displayed`로 정하고 10을 가정하지 않는다**. 후보가 부족하면 부족한 대로 보이고 빈자리를 만들지 않는다. **`eligible > stored`면 상한 안내**(*\"가까운 순서로 10개까지 보입니다\"*)를 보이고 **후보 수는 표시하지 않는다**(spec FR-013, SC-004)
- [X] T115 [P] [US1] (FE) 검색 페이지를 `frontend/src/pages/RegimeSearchPage.tsx`에 작성 — 분야 선택(`baseline-months` 응답의 분야 목록)과 결과 영역. **거리 함수·척도·지표 조합을 고르는 항목을 두지 않는다**(US1 시나리오 5)
- [X] T116 [P] [US1] (FE) CSS Module을 컴포넌트마다 작성 — `--thorp-*` 토큰만 쓴다(`frontend/docs/design-system.md`)
- [X] T117 [P] [US1] (FE 테스트) 사례 3개 표시와 '더 보기' 10개를 `CaseList.test.tsx`에 고정. 상한에 걸린 경우 **안내가 보이고 `evaluated`·`eligible` 숫자가 화면에 0건**임을 함께 고정한다(SC-004)
- [X] T118 [P] [US1] (FE 테스트) 후보 2개일 때 **빈자리가 렌더링되지 않고 '더 보기'가 없음**을 `CaseList.test.tsx`에 고정(SC-004)

**Checkpoint**: US1이 독립적으로 동작한다 — 분야를 고르면 사례가 보인다

---

# Phase 4: User Story 2 — 검색 상태와 참고 사례 표시를 이해한다 (P1)

**Goal**: 결과 옆에서 어느 상태인지 보고, 문턱 교정 전에는 '최근접 참고 사례'임을 안다.

**Independent Test**: 문턱 미교정 상태에서 '최근접 참고 사례' 표시와 '유사성 미판정' 상태가 보이는지,
상수 계열 분야에서 '계산 불가'가 되는지, `'87% 유사'` 같은 표현이 없는지 확인한다.

**묶음 M7 (이어서)** · 같은 브랜치

- [X] T119 [US2] (FE) 검색 상태 표시를 `frontend/src/components/regime/SearchStatusBadge.tsx`에 작성 — 세 상태와 사유를 **첫 사례와 같은 화면에** 둔다(spec FR-019). 상태 문구는 응답의 `statusNote`를 쓰고 화면에서 지어내지 않는다
- [X] T120 [US2] (FE) '최근접 참고 사례' 표시를 `CaseList.tsx`에 붙인다 — 응답의 `caseLabel`이 있을 때만(spec FR-020)
- [X] T121 [US2] (FE) **조회 실패 안내**를 `frontend/src/components/regime/LoadError.tsx`에 작성 — 빈 상태(`EmptyResult`)와 **다른 화면**으로 그리고 다시 시도할 수 있게 한다. 실패를 빈 결과처럼 보이지 않는다(spec FR-034)
- [X] T122 [US2] (FE) FR-037의 **다섯 표시에 공통 규약**을 `frontend/src/components/regime/Disclosure.tsx`로 묶는다 — 문구를 반드시 동반하고, 어느 결과에 대한 것인지 보조기술이 알 수 있게 연결하며, 상태가 바뀌면 그 사실이 전달된다. **접히는 영역에 넣지 않는다**(spec FR-037)
- [X] T123 [US2] (FE) `NOT_COMPUTABLE`·후보 0건을 **정상 화면 + 한 문장 안내**로 그리는 빈 상태를 `frontend/src/components/regime/EmptyResult.tsx`에 작성 — **오류 화면을 쓰지 않는다**(US2 시나리오 4). 네 사유(`SCALE_WINDOW_TOO_SHORT`·`SCALE_ZERO`·`QUERY_INPUT_MISSING`·`NO_CANDIDATE`)를 모두 그린다
- [X] T124 [US2] (FE) **빠진 지표·월 표기**를 `frontend/src/components/regime/ComparisonMonthsNotice.tsx`에 작성 — `comparisonMonths[].omitted`가 비어 있지 않으면 그 `note`를 **숨길 수 없는 자리에** 보인다(spec FR-031, SC-007)
- [X] T125 [US2] (FE) **역사 비교 표시**를 `frontend/src/components/regime/BasisNotice.tsx`에 작성 — `basis.note`를 결과 옆에 상시 표시한다. *"당시 재현"* 으로 읽힐 문구를 화면에서 만들지 않는다(spec FR-027, SC-009)
- [X] T126 [US2] (FE) 예시 자료 표시를 `RegimeSearchPage.tsx`에 붙인다 — `versions.sampleData`가 `true`면 **숨길 수 없는 자리에** 표시한다(008 FR-022)
- [X] T127 [P] [US2] (FE 테스트) 세 상태의 표시와 `'% 유사'`·`'충분히 유사'`·`'대표 유사국면'` 낱말 **0건**을 `SearchStatusBadge.test.tsx`에 고정(SC-006)
- [X] T128 [P] [US2] (FE 테스트) 다섯 표시가 **문구 없이 색·아이콘·호버로만 구별되지 않고**, 보조기술이 읽을 수 있으며, `--thorp-*` 토큰 조합이 **명암비 4.5:1**(굵은 큰 글자 3:1)을 만족함을 고정한다(SC-014). 토큰 기준은 `frontend/docs/design-system.md`
- [X] T129 [P] [US2] (FE 테스트) 계산 불가에서 오류 화면이 뜨지 않고 네 사유가 각각 한 문장으로 그려짐을 `EmptyResult.test.tsx`에 고정(SC-005)
- [X] T130 [P] [US2] (FE 테스트) 빠진 달이 있으면 표기가 보이고 없으면 안 보임, `basis` 표시는 **항상** 보임을 `ComparisonMonthsNotice.test.tsx`·`BasisNotice.test.tsx`에 고정(SC-007·SC-009)

**Checkpoint**: 결과를 과신할 수 없게 상태가 늘 함께 보인다

---

# Phase 5: User Story 3 — Thorp 종합 비교를 선택해서 연다 (P2)

**Goal**: 분야 거리를 같은 비중으로 결합한 사례와 분야별 거리·기여도를 함께 본다.

**Independent Test**: 분야 보기에서 종합을 열어 기준월이 T_all로 바뀔 때 알림이 보이는지, 분야별
거리와 기여도가 함께 보이는지, 한 분야가 계산 불가일 때 종합도 계산 불가인지 확인한다.

**묶음 M8** · `feat(fe)/{티켓}-regime-detail-composite`

- [X] T131 [US3] (FE) 모드 전환을 `RegimeSearchPage.tsx`에 붙인다 — 'Thorp 종합 비교'를 누르면 `mode=composite`로 다시 부른다
- [X] T132 [US3] (FE) 기준월 변경 한 문장 알림을 `frontend/src/components/regime/BaselineChangeNotice.tsx`에 작성 — 문구는 `baseline-months` 응답의 `note`를 쓴다(US3 시나리오 1)
- [ ] T133 [US3] (FE) 종합 사례의 분야별 거리·기여도 표시를 `frontend/src/components/regime/FieldBreakdown.tsx`에 작성 — 닮은 점과 차이를 **함께** 보인다(US3 시나리오 2)
- [ ] T134 [US3] (FE) 동일가중 설명을 `FieldBreakdown.tsx`에 붙인다 — **"Thorp의 설계 선택"** 이라고 적고 *"모든 지표의 중요성이 같다"* 고 쓰지 않는다(FR-010, US3 시나리오 5)
- [X] T135 [US3] (FE) **더 최신인 개별 발표 표시**를 `frontend/src/components/regime/NewerReleases.tsx`에 작성 — `dataAsOf.newerReleases`를 **별도 정보로** 보이고 질의값에 섞지 않는다(spec FR-016, US3 시나리오 3)
- [ ] T136 [P] [US3] (FE 테스트) 종합이 `NOT_COMPUTABLE`일 때 남은 분야로 그리지 않음을 `FieldBreakdown.test.tsx`에 고정(US3 시나리오 6). **종합만 불가일 때 분야 검색이 막히지 않음**도 함께 고정한다(FR-039)
- [X] T137 [P] [US3] (FE 테스트) 동일가중 문구와 금지 문구, `newerReleases`가 질의값과 분리돼 보임을 `FieldBreakdown.test.tsx`·`NewerReleases.test.tsx`에 고정

---

# Phase 6: User Story 4 — 과거 기준월과 자료 기준 시점을 안다 (P2)

**Goal**: 과거 기준월을 지정해 검색하고, 결과가 어느 시점 자료에 기반하는지 늘 본다.

**Independent Test**: 과거 기준월을 지정한 뒤 그 월이 화면에 유지되는지, 결과 옆에 대상월과 발표일이
늘 보이는지, 유지율이 **보이지 않는지** 확인한다.

**묶음 M8 (이어서)** · 같은 브랜치

- [ ] T138 [US4] (FE) 기준월 지정을 `RegimeSearchPage.tsx`에 붙인다 — 지정한 월을 유지하고 **화면에 항상 표시한다**(spec FR-003). 범위는 `baseline-months`의 `range`
- [ ] T139 [US4] (FE) 자료 기준 시점 표시를 `frontend/src/components/regime/DataAsOf.tsx`에 작성 — **지표별** 대상월 + 발표일. 값이 개정된 경우에만 개정 안내를 한 줄 덧붙인다. **수집 시각을 쓰지 않는다**(spec FR-002·FR-031)
- [ ] T140 [US4] (FE) 사례 상세를 `frontend/src/components/regime/CaseDetail.tsx`에 작성 — 거리·분야 기여·**지표별 명목 비중과 실제 거리 기여**·z·척도(중앙값·산포·방법·구간)·비교 시점. 손계산 대조가 가능해야 한다(spec FR-028)
- [ ] T141 [US4] (FE) 설명 문장을 `CaseList.tsx`·`CaseDetail.tsx`에 표시 — 응답의 `similar`·`different`를 그대로 쓴다. **실제 값과 단위**를 보여주고 시대별 상대 위치를 기본 검색 이유로 섞지 않는다(FR-015·FR-017)
- [ ] T142 [US4] (FE) 사례 상세에 `eraPosition`을 **당시 평소 대비 위치라는 별도 보조 설명**으로 표시한다 — 문장만 보이고 시대별 z·분위수·백분율로 바꾸지 않는다. 설명이 없는 지표는 보조 설명을 계산할 수 없다고 안내하되 사례와 순위는 유지한다(FR-017·SC-011)
- [ ] T143 [P] [US4] (FE 테스트) 유지율·`retention`·겹침 비율이 화면에 **0건**임을 `DataAsOf.test.tsx`에 고정(FR-031, SC-007)
- [ ] T144 [P] [US4] (FE 테스트) 대상월·발표일이 늘 보이고, 개정이 없으면 개정 안내가 없으며, 수집 시각이 0건임을 `DataAsOf.test.tsx`에 고정
- [ ] T145 [P] [US4] (FE 테스트) 상세에 명목 비중·실제 거리 기여와 공통 척도가 보여 손계산이 가능함을 `CaseDetail.test.tsx`에 고정(SC-003). 시대별 위치는 별도 보조 문장으로 표시되고, 그 영역의 z·분위수·백분율이 0건이며 보조 설명이 없어도 사례가 유지됨을 확인한다(SC-011)
- [ ] T146 (FE) 검색·더 보기·모드 전환·기준월 지정·상세 열기를 **키보드만으로** 할 수 있게 한다 — 포커스 순서가 화면 순서와 맞고, 다섯 표시가 그 경로에서 모두 도달 가능하다(spec FR-037)
- [ ] T147 [P] (FE 테스트) 키보드만으로 다섯 표시에 도달함과, **좁은 폭에서 잘리거나 접히지 않음**을 고정한다(SC-014)
- [ ] T148 [P] (FE 테스트) 010 화면 코드에 **지표 ID·지표명·분야 이름이 0건**임을 `frontend/src/components/regime/indicatorAgnostic.test.tsx`에 고정 — 007의 전략명 검사와 같은 방식이고, 대상 파일은 **손 목록이 아니라 `src`를 훑어** 모은다. **결과·상태·안내 영역에 하드코딩된 문구가 0건**임도 함께 고정한다 — 그 자리 문장은 전부 API 응답에서 와야 한다(spec FR-022 ②, SC-006)

---

# Phase 7: Polish & 교차 관심사

**묶음 M9** · `docs(be)/{티켓}-regime-verification`

- [ ] T149 [P] (DOCS) quickstart S1~S9를 실제로 실행하고 결과를 [quickstart.md](quickstart.md) 하단 "실행 기록"에 남긴다 — 통과·실패·미실행을 그대로 적는다
- [ ] T150 (DOCS) **폐기 예행연습**: 임시 대역 **두 디렉터리**(`data/…/macro/fixture/`, `backend/…/macro/fixture/`)를 지워 보고 어디가 깨지는지 확인한 뒤 되돌린다. 픽스처를 쓰는 테스트만 깨져야 한다(quickstart S9). 결과를 실행 기록에 남긴다
- [ ] T151 [P] (DOCS) 사전 계산 배치 소요 시간·다시 계산한 기준월 수·새로 생긴 행 수를 실행 기록에 남긴다 — **목표로 제시하지 않고 측정값으로만 적는다**(spec FR-033, 원칙 IX)
- [ ] T152 [P] (DOCS) 조회 응답 시간·실패 건수를 실행 기록에 남긴다 — research R9의 p95 300ms는 **측정 목표이지 한도가 아니다**. 004 FR-007의 실행 유형 기록에 붙이는 일은 004 완성 후로 미루고 그 사실을 적는다
- [ ] T153 [P] (DOCS) 확인할 수 없었던 항목을 quickstart "지금 확인할 수 없는 것" 표와 대조해 갱신한다 — 008·011·PD-09·PD-10·FR-032(014 대기)·실제 과거 자료 범위·v2 비교 실험. **v2 검증 대기는 v1 구현 차단 조건이 아니다**
- [ ] T154 (DOCS) `specs/README.md`의 010 행 상태를 실제 완료 범위에 맞게 갱신하고, 008에 요구하는 `MacroIndicatorSource` 계약을 다시 확인한다 — **010·PD-08의 모델 v1 기준을 유지**하며, 미확인 상태를 완료로 올리지 않는다

---

# Phase 8: 사용성 확인 — 구현이 끝나야 시작한다

**묶음 M10** · `docs(be)/{티켓}-regime-usability`

constitution "완료의 정의"는 **기능 단위** 완료를 `구현 완료 / 사용성 확인 / 효과 입증` 셋으로 나누고
*"하나를 다른 것의 증거로 쓰지 않는다"* 고 정한다. M9까지는 **구현 완료**일 뿐이며, 이 묶음이 끝나야
010을 **사용성 확인까지 된 기능**으로 보고할 수 있다.

- [ ] T155 (DOCS) **사용성 관찰**을 6~8명에게 수행하고 결과를 실행 기록에 남긴다 — 과제와 확인 질문은 spec SC-008(constitution §7.2가 정한 경제환경 탐색 과제). 참여자별로 ① 종합 비교를 열지 않고 끝냈는가 ② 분야 유사성을 경제 전체의 일치로 오해했는가를 적고, **막힌 지점을 그대로 적는다**(고쳤는지는 별도 판단). 이 인원은 **오류 발견을 위한 규모이지 수요를 입증하는 표본이 아니다**

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
   ↓
Phase 8 사용성 확인 (M10)   ← 구현이 끝나야 시작한다. 별도 티켓
```

**스토리 독립성**: US1이 MVP이고 혼자 성립한다. US2는 US1의 화면에 상태 표시를 얹으므로 US1에
의존한다. US3·US4는 US1·US2와 독립이지만 같은 페이지를 만지므로 브랜치를 나누면 충돌한다 —
그래서 M8 하나로 묶었다.

**병렬 기회**: 묶음 안에서만 있다.

- M1: T002·T004·T009~T013 병렬
- M2: T023~T028 병렬
- M3: T036·T037·T039~T042 검증 파일별 병렬 가능. T042의 보조 설명 검증은 T038 구현 이후
- M4: T051~T057 병렬
- M5: T059 / T074~T079 병렬
- M6: T081·T084·T085 / T087·T088·T097 / T101~T109 병렬
- M7: T110·T111·T115·T116 / T117·T118 / T127~T130 병렬
- M8: T136·T137 / T143·T144·T145·T147·T148 병렬
- M9: T149·T151·T152·T153 병렬
- M10: 태스크 하나라 병렬 없음

## 요구사항 ↔ 태스크

빠지기 쉬운 것만 적는다. 나머지는 각 태스크 설명의 FR 참조로 따라간다.

| 요구 | 태스크 |
|---|---|
| FR-001 비교 시점 | T021·T022·T027·T028 |
| FR-010 지표별 비중·기여 | T034·T140·T145 |
| FR-016 더 최신 개별 발표 | T135·T137 |
| FR-017 · SC-011 시대별 보조 설명과 순위 분리 | T038 · T042 · T055 · T063 · T076 · T102 · T142 · T145 |
| FR-021 교정 후 안내 | PD-09 확정 후. 태스크 없음(quickstart에 명시) |
| FR-025·FR-026 검증 분리·대안 유지 | 구현이 아니라 운영 규칙. 태스크 없음 |
| FR-027 역사 탐색 표시 | T098·T103·T125·T130 (SC-009) |
| FR-029 보관 | T069 |
| FR-030 재계산 범위·중복 방지 | T058 · T059(네 경로) · T065(`content_hash`) · T067 · T074 · T075 |
| **FR-037 신뢰 범위 표시 전달** | T122(공통 규약) · T128(색·명암비·보조기술) · T146(키보드) · T147(키보드·좁은 폭) |
| SC-014 다섯 표시 접근성 | T128 · T147 |
| FR-008 60개월 미만 계산 불가 | T017 · T020 · T023 · T060 |
| **FR-005 공통 척도** | T016·T017·T020 · T026(오래된 후보도 같은 척도) · T029·T030 · T036·T037·T042 · T062·T065 · T086·T097·T102 · T140·T145 |
| FR-011 등록표 버전 참조 | T062 · T064 |
| FR-013 표시 상한 안내 | T114 · T117 |
| FR-039 종합만 계산 불가 | T061 · T078 · T136 |
| FR-022 금지 표현 두 층 | T056(산출물) · T105(응답) · T148(화면 하드코딩 0건) |
| FR-038 예시 자료 표시 | T100(`sampleData` 판정) · T126(표시) · T128(숨길 수 없는 안내) |
| FR-023 U01~U06 검증 전 동작 | T060(`NEAREST_ONLY` 고정) · T019·T020(MAD→IQR→계산 불가) · T120(최근접 참고 사례) — 표 전체를 덮는 단일 태스크는 없다 |
| FR-024 · SC-001 전략 독립성 | T105(요청 파라미터에 전략 항목 0건) · quickstart S7 |
| FR-036 · SC-013 개인정보 분리 | T106(사전 계산 표의 개인 식별 컬럼 0개 검사) · 삭제 동작은 **006·014 구현 시점** |
| FR-032 로그인 기록 | 표만 만든다(T086). **쓰는 코드는 014 이후** — 그 사실을 T153에 적는다 |
| FR-033 측정 | T072·T151·T152 |
| **FR-034 실패 처리** | **T070**(부분 적재 금지) · **T099**(조회 실패 구분) · **T104**(테스트) · **T121**(실패 화면) |
| SC-010 실패와 계산 불가 구분 | T104 |
| **FR-035 철회** | T071(`--withdraw` + 기록) · T093(철회와 정리를 응답에서 구분) |
| FR-012 후보 제외 사유 셋 | T043~T049(자격·집계) · T051~T055(후보 직전 이력 불필요 포함) |
| FR-018 등급 매기기 금지 | 별도 태스크 없음 — 금지 낱말 검사 T056·T105·T148이 spec FR-022 ① 표(`대표 유사국면` → FR-018)로 덮는다 |
| SC-002a 요청 중 계산 0건 | T108 (정적 검사) · T109(시계열 미조회) |
| SC-008 사용성 관찰 | **T155 (M10 — 별도 티켓)**. M9까지는 '구현 완료'일 뿐이다 |

## Implementation Strategy

**MVP = Phase 1 + Phase 2 + Phase 3(US1)**. 여기까지면 "분야를 고르면 비슷했던 과거가 보인다"가
성립한다. 다만 **Phase 4(US2)까지 함께 내보내는 것을 권한다** — 상태 표시 없이 사례만 보이면
사용자가 '가까운 순서'를 '충분히 비슷함'으로 읽고, 그것이 spec이 가장 경계하는 오독이다.

**계산을 먼저 만드는 이유**: M2~M5가 010의 전부다. 화면과 API는 그 결과를 옮기는 일이고, 틀렸을 때
드러나는 곳도 계산이다. 007이 화면부터 만들어 워커 없이 숫자를 주입하게 된 전철을 밟지 않는다.

**이 태스크로 확인할 수 없는 것**: 픽스처 위의 결과라 "사례가 경제적으로 그럴듯한가"는 008 완성
후에야 말할 수 있다. quickstart "지금 확인할 수 없는 것" 표가 그 목록이며, 여기 나온 숫자를 성과로
인용하지 않는다.
