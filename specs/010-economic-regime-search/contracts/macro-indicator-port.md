# 계약: 008 경계 — `MacroIndicatorPort`(Backend)와 `MacroIndicatorSource`(`data/`)

**Spec**: [../spec.md](../spec.md) | **008**: [../../008-macro-indicator-registry/spec.md](../../008-macro-indicator-registry/spec.md)

> **2026-09-16 갱신**: 경계를 **둘로 나눠 각각의 필요에 맞췄다.** Backend는 표시용 등록표만 포트로
> 읽고, 시계열은 `data/` 배치만 읽는다. 기준월(T_g·T_all)은 어느 쪽도 실시간으로 읽지 않고 배치가
> 기록한 표에서 나온다(§0.2).

## 0. 경계가 어디에 몇 개인가

```
                    ┌──────────────────────────────────────────┐
008 (macro_indicator_*)                                        │
     │              │                                          │
     │  시계열·발표일·버전                          등록표(이름·단위·분야)
     ▼              │                                          ▼
data/ 배치 ─────────┴──▶ PostgreSQL(regime_*) ──▶ Backend ◀── MacroIndicatorPort
  MacroIndicatorSource                            (읽기만)      (표시용 조회)
```

| 경계 | 누가 | 무엇을 | 왜 |
|---|---|---|---|
| `MacroIndicatorSource` (Python) | `data/` 배치 | 시계열·발표일·자료 버전·변경 월 | 계산의 입력. Backend는 계산하지 않으므로 필요 없다 |
| `MacroIndicatorPort` (Java) | Backend | 지표·분야 **등록표**(이름·단위·순서·등록표 버전) | 화면이 분야 이름을 보여야 한다. 004 `MarketMetadataPort`·007 `ParameterRegistryPort`와 같은 모양 |

**포트를 두는 이유**는 저장소에 이미 같은 상황의 전례가 둘 있기 때문이다 — 001이 없어 004가
`MarketMetadataPort` + `FixtureMarketMetadataAdapter`로, 003이 없어 007이 `ParameterRegistryPort` +
`ProvisionalFileParameterRegistry`로 시작했다. 상류 spec의 등록 정보를 **읽는 유일한 통로**를 두고
완성되면 구현체만 바꾸는 방식이다.

### 0.1 포트에 시계열을 두지 않는 이유

`AGENTS.md`의 **"Backend는 계산하지 않는다"** 가 그대로 적용된다. 010의 계산은 전부 `data/` 배치에
있고, Backend는 적재된 행을 읽어 내보낼 뿐이다. 포트에 `monthlySeries()`를 두면 **호출자가 없는
메서드를 픽스처와 008 구현체 양쪽이 구현**하게 된다.

### 0.2 T_g·T_all을 포트로 읽지 않는 이유

008은 새 발표분이 들어오는 즉시 T_g를 올리지만 **배치는 그 뒤에 돈다.** 그 사이 화면이 008의 최신
T_g로 검색하면 아직 snapshot이 없는 달이라 **404**가 난다. 화면이 고를 수 있는 달은 **결과가 실제로
있는 달**이어야 하므로, 배치가 자기 적재와 함께 `regime_baseline_month`에 남긴 값을 읽는다
([../data-model.md](../data-model.md) §3).

`MAX(baseline_month)`로 대신할 수도 없다 — 배치는 `NOT_COMPUTABLE` 행도 만들기 때문에 어느 한
지표만 자료가 있는 달에도 행이 생기고, 그 최댓값은 008이 정의한 T_g보다 뒤로 간다.

---

## 1. `MacroIndicatorSource` — `data/` 배치가 쓰는 소스

`data/src/data_pipeline/regime/macro/source.py`의 프로토콜이다.

| 이름 | 돌려주는 것 | 008 근거 |
|---|---|---|
| `registry()` | 지표 목록(id·이름·단위·변환), 지표 → 분야 매핑, 등록표 버전 | FR-002·FR-003 |
| `monthly_series(indicator_id)` | 대상월 → **변환값** 전 구간 | FR-006 |
| `raw_series(indicator_id)` | 대상월 → **원값** 전 구간 | FR-006 |
| `baseline_months()` | 분야별 T_g, 17개 공통 T_all | FR-013 |
| `publication(indicator_id, month)` | 그 값의 대상월·발표일·개정 여부 | FR-007 |
| `data_version()` | 현재 자료 버전 | FR-009 |
| `changed_since(data_version)` | 그 버전 이후 값이 새로 들어오거나 개정된 **가장 오래된 대상월 M** | FR-009·FR-017 |
| `is_sample_data()` | 예시 자료인가 | FR-022 |

`changed_since()`가 spec FR-030의 재계산 범위를 정한다 — `t ≥ M`인 기준월만 다시 계산한다
(research R3). 008이 답할 수 없으면 `None`을 돌려주고, 그때는 계열 시작월을 M으로 삼아 전 구간을
계산한다.

`baseline_months()`가 돌려준 값은 계산에 쓰고 **`regime_baseline_month`에 기록한다**(§0.2).

## 2. `MacroIndicatorPort` — Backend가 쓰는 포트

`backend/src/main/java/com/ssafy/thorp/regime/application/port/MacroIndicatorPort.java`.

| 이름 | 돌려주는 것 | 008 근거 |
|---|---|---|
| `registryVersion()` | 현재 등록표 버전 | FR-003 |
| `fields()` | 분야 목록 — id·이름·표시 순서 | FR-002·FR-003 |
| `indicator(indicatorId)` | 지표 하나의 이름·단위. 등록되지 않았으면 empty | FR-002 |

**이 셋이 전부다.** 화면이 필요로 하는 것은 저장된 결과에 붙일 **이름과 단위**뿐이고, 값·기준월·
버전은 전부 `regime_*` 표에 있다.

`ParameterRegistryPort`와 같은 규칙을 따른다:

- **010은 이 포트가 돌려주는 값 외에 지표·분야에 대해 아무것도 알지 못한다.** 지표 목록 상수·분야
  이름 문자열·`fieldId` 분기를 어디에도 두지 않는다(spec FR-004). 008에 지표가 늘어도 010은
  고치지 않는다.
- **조회 실패를 대체하지 않는다.** 구현체가 예외를 던지면 이름 없이 화면을 그리지 않고 오류를
  그대로 올린다 — 지어낸 이름이 저장된 결과에 붙으면 그 결과가 무엇에 대한 것인지 알 수 없게 된다.
- 임시 구현의 등록표 버전은 `FIXTURE-`로 시작한다.

## 3. 규칙 (양쪽 공통)

**결측은 결측으로 온다.** 008 FR-008이 임의 보간을 금지하므로 빠진 달은 **키가 없다.** 0이나
직전 값으로 채워 오지 않는다. 010은 그 구멍을 보고 척도 연속 구간(spec FR-005)과 지표별 비교
시점(spec FR-001)을 판정한다.

**원값과 변환값을 모두 받는다.** 비슷한 점·다른 점 문장은 **원값과 단위**로 서술해야 하고
(spec US1 시나리오 4), 거리는 변환값으로 계산한다. 둘 중 하나만 받으면 한쪽을 만들어 내야 한다.

**버전 셋이 함께 온다.** 자료 버전·등록표 버전이 바뀌면 재계산 대상이 정해진다(spec FR-030).
어느 하나만 보고 판단하지 않는다.

## 4. 임시 대역 (008 완성 전) — 격리·표기·폐기

임시 대역은 **한 번에 지워지도록** 만든다. 008이 완성된 날 "어디를 지워야 하지"를 뒤지게 되면
가짜 자료가 운영에 남는다. 가장 오래 남는 것은 코드가 아니라 **DB에 적재된 사전 계산 행**이다.

### 4.1 격리 — 지울 것이 두 디렉터리에 모인다

```
data/src/data_pipeline/regime/macro/fixture/                                  ← 시계열 대역
backend/src/main/java/com/ssafy/thorp/regime/infrastructure/macro/fixture/    ← 등록표 대역
```

**두 픽스처는 담는 것이 다르다** — 한쪽은 시계열, 다른 쪽은 이름·단위·순서다. 값이 겹치지 않으므로
두 벌이 어긋날 자리가 없다. 겹칠 뻔했던 T_g·T_all은 §0.2대로 배치가 기록하는 쪽으로 빼 두었다.

양쪽 모두 운영 코드는 인터페이스만 알고 픽스처 클래스를 이름으로 참조하지 않는다 — 참조하면 그
파일도 삭제 대상이 되고 경계가 무너진다.

**전환 스위치:**

```bash
python scripts/precompute_regime.py --source fixture   # 배치 (기본값은 macro)
```

```yaml
thorp.regime.macro.source: fixture   # Backend. fixture | macro
```

- 배치: `THORP_ENV=prod`인데 `--source fixture`면 **적재 전에 종료**한다.
- Backend: **prod 프로파일에서 `fixture`면 기동에 실패한다**(`ApplicationProfileConfigTest`가
  `flyway clean-disabled`를 단언하는 것과 같은 방식).

### 4.2 표기 — 세 겹으로 드러난다

| 층 | 표기 | 근거 |
|---|---|---|
| **자료 버전** | 값이 `FIXTURE-`로 시작한다 (`FIXTURE-20260915`) | 007의 `PROVISIONAL-` 전례 |
| **API** | `versions.sampleData: true`, `versions.data`가 `FIXTURE-…` | 008 FR-022 |
| **화면** | 결과 옆에 예시 자료 표시. 숨길 수 없는 자리에 둔다 | 008 FR-022 |

**자료 버전 접두사가 핵심이다.** 008의 실제 버전과 문자열이 절대 겹치지 않으므로, ① 화면·API·기록
어디에 값이 흘러가도 가짜임이 드러나고 ② 폐기가 조건 한 줄로 끝난다. Backend는 `data_version`의
접두사만 보고 `sampleData`를 만든다 — 008에 묻지 않는다.

### 4.3 폐기 — 008 완성 시 한 번에

```bash
# 1) 코드·자원 삭제 (두 디렉터리)
rm -r data/src/data_pipeline/regime/macro/fixture
rm -r backend/src/main/java/com/ssafy/thorp/regime/infrastructure/macro/fixture

# 2) 전환 스위치 제거 — 008 구현체가 유일한 구현이 되면 분기가 필요 없다
#    application*.yml 에서 thorp.regime.macro.source 키 삭제

# 3) 가짜 자료로 계산된 행 삭제
DELETE FROM regime_search_case
 WHERE snapshot_id IN (SELECT id FROM regime_search_snapshot WHERE data_version LIKE 'FIXTURE-%');
DELETE FROM regime_search_snapshot WHERE data_version LIKE 'FIXTURE-%';
DELETE FROM regime_baseline_month  WHERE data_version LIKE 'FIXTURE-%';

# 4) 실제 자료로 전 구간 재계산
cd data && python scripts/precompute_regime.py --source macro --all
```

**남은 참조는 컴파일과 테스트가 드러낸다.** 픽스처 패키지를 지우면 그것을 쓰던 테스트가 깨지므로,
"지웠는데 어딘가 남아 있다"가 조용히 지나가지 않는다.

### 4.4 시계열 픽스처가 담는 모양

규칙 검증에 필요한 모양을 **일부러** 담는다. 값은 지어낸 것이다.

| # | 모양 | 무엇을 시험하나 |
|---|---|---|
| 1 | 정상 계열 | 기본 경로 |
| 2 | 척도 구간 중간에 결측 구멍 | 척도 연속 구간 판정 (spec FR-005) |
| 3 | 상수 계열 (MAD=0, IQR≠0) | IQR 대체 (spec FR-006) |
| 4 | 완전 상수 (MAD=0, IQR=0) | `SCALE_ZERO` |
| 5 | 시작 연도가 늦은 계열 | 분야 T_g가 뒤로 밀림, T_all이 T_g보다 이름. `newerReleases` 표시 |
| 6 | 인접 월이 서로 가까운 구간 | ±6개월 건너뛰기 (spec FR-012·FR-014) |
| 7 | 거리가 정확히 같은 두 후보 | 동률 → 오래된 월 우선 (spec FR-012) |
| 8 | **질의 3개월 중 한 달만 결측** | 지표별 비교 시점, 빠진 지표·월 표기 (spec FR-001·FR-031) |
| 9 | **질의 3개월이 전부 결측인 지표** | `QUERY_INPUT_MISSING` — 그 지표를 빼고 계산하지 않음 (spec FR-007) |
| 10 | **자료 시작 직후 후보 구간**(비교 시점 입력은 있으나 그 직전 60개월은 없음), 질의 공통 척도는 유효 | 후보 자격은 유지하고 시대별 보조 설명만 제한한다(FR-012·FR-017) |

8·9는 2026-09-16 Clarification으로 추가됐다. 10은 v1의 후보 자격과 보조 설명 가용성을 구분한다. 이 경로가 픽스처에 없으면 검증 밖으로 나간다.

**이 모양들은 008이 와도 사라지면 안 된다.** 실제 자료에 상수 계열이나 정확한 동률이 없을 수
있는데, 그러면 `NOT_COMPUTABLE`·동률 경로가 검증 밖으로 나간다. 그래서 **운영 픽스처(§4.1)는
폐기하되 같은 모양을 담은 테스트 픽스처는 `data/tests/regime/fixtures/`에 남긴다** — 운영 구현체가
아니라 단위 테스트의 입력이므로 삭제 대상이 아니다.

## 5. 교체 시점에 확인할 것

008이 붙으면 **구현체만 바뀌고** 010 로직은 그대로여야 한다.

1. **같은 테스트가 구현체만 바꿔 통과하는가** — 소스·포트 양쪽에 계약 테스트를 두고 픽스처와 실제
   구현 모두에 돌린다
2. **010 코드에 지표 ID·지표명·분야 이름이 0건인가** — 배치·Backend·화면 전부. 정적 검사이며
   대상 파일은 손 목록이 아니라 훑어서 모은다
3. **과거 범위가 달라져도 결과가 성립하는가** — 픽스처 범위와 실제 범위가 다르므로, 후보 지평을
   "자료가 있는 전 구간"으로 정한 결정(spec FR-012)이 양쪽에서 같은 코드로 동작해야 한다
4. **`changed_since()`가 실제 개정을 잡는가** — 못 잡으면 전 구간 재계산으로 물러난다(§1)
5. **`regime_baseline_month`가 008의 T_g보다 뒤처져 있어도 화면이 404를 내지 않는가** — 배치가 아직
   안 돈 구간이 정상 동작임을 확인한다(§0.2)

## 6. 010이 008에 요구하지 않는 것

| 무엇 | 왜 |
|---|---|
| 척도·거리·순위 계산 | 010 소유 (008 Out of Scope) |
| KOSPI·KOSDAQ 등 시장 지표 | spec FR-004 — 검색 입력이 아니다 |
| 당시 발표·수정값(빈티지) | 양쪽 Out of Scope. 최신 개정자료 역사 비교만 한다(spec FR-027) |
| 지표별 최소 유효 척도 (PD-10) | 확정 전에는 010의 MAD→IQR→계산 불가로 대신한다 (research R7) |
| Backend가 읽을 시계열 조회 | Backend는 계산하지 않는다 (§0.1) |
