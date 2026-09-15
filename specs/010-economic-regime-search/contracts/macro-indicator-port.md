# 계약: 008 ↔ 010 경계 (`MacroIndicatorPort`)

**Spec**: [../spec.md](../spec.md) | **008**: [../../008-macro-indicator-registry/spec.md](../../008-macro-indicator-registry/spec.md)

008이 아직 없다. 010은 이 포트만 알고, 008이 완성되면 **어댑터만 교체**한다(research R1). 007이
003 없이 완성된 경로와 같다.

## 1. 포트가 돌려주는 것

| 이름 | 돌려주는 것 | 008 근거 |
|---|---|---|
| `registry()` | 지표 목록(id·이름·단위·변환), 지표 → 분야 매핑, 등록표 버전 | FR-002·FR-003 |
| `monthlySeries(indicatorId)` | 대상월 → **변환값** 전 구간 | FR-006 |
| `rawSeries(indicatorId)` | 대상월 → **원값** 전 구간 | FR-006 |
| `baselineMonths()` | 분야별 T_g, 17개 공통 T_all | FR-013 |
| `publication(indicatorId, month)` | 그 값의 대상월·발표일·개정 여부 | FR-007 |
| `dataVersion()` | 현재 자료 버전 | FR-009 |
| `isSampleData()` | 예시 자료인가 | FR-022 |

## 2. 규칙

**결측은 결측으로 온다.** 008 FR-008이 임의 보간을 금지하므로 빠진 달은 **키가 없다.** 0이나
직전 값으로 채워 오지 않는다. 010은 그 구멍을 보고 척도 연속 구간을 판정한다(spec FR-005).

**원값과 변환값을 모두 받는다.** 화면의 비슷한 점·다른 점 문장은 **원값과 단위**로 서술해야 하고
(spec US1 시나리오 4), 거리는 변환값으로 계산한다. 둘 중 하나만 받으면 한쪽을 만들어 내야 한다.

**지표 목록을 010이 갖지 않는다.** 17개가 무엇인지, 어느 분야인지는 전부 `registry()`가 답한다.
010 운영 코드에 지표 ID·지표명·분야 이름이 나타나면 안 된다 — 007의 FR-025(전략 비의존성)와 같은
제약이며, 008에 지표가 늘어도 010은 고치지 않는다.

**버전 셋이 함께 온다.** 자료 버전·등록표 버전이 바뀌면 사전 계산을 전 구간 다시 돌린다
(spec FR-030). 어느 하나만 보고 판단하지 않는다.

## 3. 임시 대역 (008 완성 전) — 격리·표기·폐기

임시 대역은 **한 번에 지워지도록** 만든다. 008이 완성된 날 "어디를 지워야 하지"를 뒤지게 되면
가짜 자료가 운영에 남는다. 가장 오래 남는 것은 코드가 아니라 **DB에 적재된 사전 계산 행**이다.

### 3.1 격리 — 지울 것이 세 디렉터리에 모인다

```
backend/src/main/java/com/ssafy/thorp/regime/infrastructure/macro/fixture/   ← 통째로 삭제
backend/src/main/resources/fixtures/macro-indicator/                         ← 통째로 삭제
data/src/data_pipeline/regime/fixture/                                       ← 통째로 삭제
```

임시 대역 코드는 이 밖으로 나가지 않는다. 운영 코드는 `MacroIndicatorPort`만 알고 픽스처 클래스를
이름으로 참조하지 않는다 — 참조하면 그 파일도 삭제 대상이 되고 경계가 무너진다.

전환 스위치는 프로퍼티 한 줄이다.

```yaml
thorp.regime.macro.source: fixture   # fixture | macro
```

`fixture`면 `FixtureMacroIndicatorAdapter`, `macro`면 008 구현체가 뜬다
(`@ConditionalOnProperty`). **prod 프로파일에서 `fixture`면 기동에 실패한다** — 설정 실수로 가짜
자료가 운영에 나가는 경로를 없앤다(`flyway clean-disabled`를 `ApplicationProfileConfigTest`가
단언하는 것과 같은 방식).

### 3.2 표기 — 세 겹으로 드러난다

| 층 | 표기 | 근거 |
|---|---|---|
| **자료 버전** | 값이 `FIXTURE-`로 시작한다 (`FIXTURE-20260915`) | 007의 `PROVISIONAL-` 전례 |
| **API** | `versions.sampleData: true`, `versions.data`가 `FIXTURE-…` | 008 FR-022 |
| **화면** | 결과 옆에 예시 자료 표시. 숨길 수 없는 자리에 둔다 | 008 FR-022 |

**자료 버전 접두사가 핵심이다.** 008의 실제 자료 버전과 문자열이 절대 겹치지 않으므로,
① 화면·API·기록 어디에 값이 흘러가도 가짜임이 드러나고 ② 폐기가 조건 한 줄로 끝난다.

### 3.3 폐기 — 008 완성 시 한 번에

```bash
# 1) 코드·자원 삭제 (세 디렉터리)
rm -r backend/src/main/java/com/ssafy/thorp/regime/infrastructure/macro/fixture
rm -r backend/src/main/resources/fixtures/macro-indicator
rm -r data/src/data_pipeline/regime/fixture

# 2) 전환 스위치 제거 — 008 구현체가 유일한 구현이 되면 분기가 필요 없다
#    application*.yml 에서 thorp.regime.macro.source 키 삭제

# 3) 가짜 자료로 계산된 행 삭제
DELETE FROM regime_search_case
 WHERE snapshot_id IN (SELECT id FROM regime_search_snapshot WHERE data_version LIKE 'FIXTURE-%');
DELETE FROM regime_search_snapshot WHERE data_version LIKE 'FIXTURE-%';

# 4) 실제 자료로 전 구간 재계산
cd data && python scripts/precompute_regime.py --all
```

**남은 참조는 컴파일과 테스트가 드러낸다.** 픽스처 패키지를 지우면 그것을 쓰던 테스트가 깨지므로,
"지웠는데 어딘가 남아 있다"가 조용히 지나가지 않는다.

### 3.4 픽스처가 담는 모양

규칙 검증에 필요한 모양을 **일부러** 담는다. 값은 지어낸 것이다.

| # | 모양 | 무엇을 시험하나 |
|---|---|---|
| 1 | 정상 계열 | 기본 경로 |
| 2 | 중간에 결측 구멍 | 척도 연속 구간 판정 (spec FR-005) |
| 3 | 상수 계열 (MAD=0, IQR≠0) | IQR 대체 (spec FR-006) |
| 4 | 완전 상수 (MAD=0, IQR=0) | `NOT_COMPUTABLE` |
| 5 | 시작 연도가 늦은 계열 | 분야 T_g가 뒤로 밀림, T_all이 T_g보다 이름 |
| 6 | 인접 월이 서로 가까운 구간 | ±6개월 건너뛰기 (spec FR-012·FR-014) |
| 7 | 거리가 정확히 같은 두 후보 | 동률 → 오래된 월 우선 (spec FR-012) |

**이 일곱 모양은 008이 와도 사라지면 안 된다.** 실제 자료에 상수 계열이나 동률이 없을 수 있는데,
그러면 `NOT_COMPUTABLE`·동률 경로가 검증 밖으로 나간다. 그래서 **픽스처 자료 파일은 폐기하되
같은 일곱 모양을 담은 테스트 픽스처는 `data/tests/regime/fixtures/`에 남긴다** — 운영 어댑터가
아니라 단위 테스트의 입력이므로 §3.1의 삭제 대상이 아니다.

## 4. 교체 시점에 확인할 것

008이 붙으면 어댑터만 바뀌고 010 로직은 그대로여야 한다. 그것을 확인하는 방법:

1. **같은 테스트가 어댑터만 바꿔 통과하는가** — 포트 수준 계약 테스트를 픽스처와 실제 구현 양쪽에
   돌린다
2. **010 운영 코드에 지표 ID·지표명·분야 이름이 0건인가** — 정적 검사. 007이 전략명에 대해 하는 검사와
   같은 방식이며, 대상 파일은 손 목록이 아니라 훑어서 모은다
3. **과거 범위가 달라져도 결과가 성립하는가** — 픽스처 범위와 실제 범위가 다르므로, 후보 지평을
   "자료가 있는 전 구간"으로 정한 결정(spec FR-012)이 양쪽에서 같은 코드로 동작해야 한다

## 5. 010이 008에 요구하지 않는 것

| 무엇 | 왜 |
|---|---|
| 척도·거리·순위 계산 | 010 소유 (008 Out of Scope) |
| KOSPI·KOSDAQ 등 시장 지표 | spec FR-004 — 검색 입력이 아니다 |
| 당시 발표·수정값(빈티지) | 양쪽 Out of Scope. 최신 개정자료 역사 비교만 한다 |
| 지표별 최소 유효 척도 (PD-10) | 확정 전에는 010의 MAD→IQR→계산 불가로 대신한다 (research R7) |
