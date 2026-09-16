# Quickstart: 경제환경 검색

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **계약**: [contracts/regime-search-api.md](contracts/regime-search-api.md)

010이 실제로 동작하는지 확인하는 절차다. 각 시나리오는 **무엇이 보여야 하는지**와 **무엇이 보이면
안 되는지**를 함께 적는다 — 010은 보이면 안 되는 것(확률·배지·채워진 빈자리)이 결함인 기능이다.

> **2026-09-17 (모델 v2)**: 후보도 자기 직전 구간의 자로 잰다. 상세에 `queryScale`·`caseScale`이
> 나란히 오고 `eraPosition`은 없어졌다.
>
> **2026-09-16 개정**: `sector` → `field` 개명, 엔드포인트 5개, 비교 시점·역사 비교 표시 확인 추가,
> 임시 대역이 두 디렉터리(시계열·등록표)로 정리된 것을 반영했다.

## 사전 준비

```bash
# 1) 자료 파이프라인
cd data
.venv/Scripts/python scripts/precompute_regime.py --source fixture --all
# 사전 계산 후 PostgreSQL 적재. 소요 시간과 다시 계산한 기준월 수가 출력된다(FR-033 기록 대상)

# 2) 백엔드
cd ../backend
./gradlew bootRun

# 3) 프론트
cd ../frontend
npm run dev
```

**지금은 008이 없어 픽스처 자료로 돕니다.** 모든 응답의 `versions.sampleData`가 `true`,
`versions.data`가 `FIXTURE-`로 시작해야 하고 화면에 예시 자료 표시가 있어야 한다(008 FR-022).
이 표시가 없으면 그 자체가 결함이다. 임시 대역의 격리는 S9에서 따로 본다.

## S1. 분야 검색이 거리순 3개를 보인다 (US1)

```bash
curl -s "localhost:8080/regime-searches?mode=field&field=LABOR" | jq '.result'
```

**보여야 하는 것**

- `cases`가 3개, 각각 `endMonth`·`observationMonths`(3개월)·`similar`·`different`
- `similar`·`different`가 **지표의 실제 값과 단위**로 서술 — 예: `"실업률은 3.1%로 당시 3.2%와 비슷합니다."`
- `status`가 `NEAREST_ONLY`, `caseLabel`이 `"최근접 참고 사례"`
- `basis.kind`가 `"HISTORICAL_REVISION"` — **역사 비교 표시**(SC-009)
- `distance`가 **문자열**이고 소수점 아래 12자리
- `counts`에 `evaluated`·`eligible`·`stored`·`displayed`가 모두 있음

**보이면 안 되는 것**

- `"% 유사"`, `"대표 유사국면"`, `"충분히 유사"`, `"지금도 잘될 전략"` (FR-018·FR-022)
- 원인 단정 — `"수출 부진으로"` 같은 표현 (US1 시나리오 4)
- 거리 함수·척도·지표 조합을 고르는 파라미터 (US1 시나리오 5)
- `sector` 라는 낱말 — 경제 분야는 `field`다

```bash
# '더 보기'
curl -s "localhost:8080/regime-searches?mode=field&field=LABOR&limit=10" | jq '.result.counts'
# displayed ≤ 10. stored > displayed 일 때만 '더 보기'가 남아 있다 (FR-013)
```

## S2. 후보가 부족하면 채우지 않는다 (FR-013, SC-004)

픽스처에서 시작 연도가 늦은 계열(모양 5)의 분야를 고른다. 후보가 3개 미만이어야 한다.

```bash
curl -s "localhost:8080/regime-searches?mode=field&field=<시작이늦은분야>" | jq '.result.counts'
```

`displayed < 3`이고 `cases` 길이가 그 값과 같아야 한다. **빈자리를 채운 사례가 0건**이어야 한다 —
거리가 먼 후보를 끌어와 3개를 맞추면 SC-004 위반이다.

## S3. **질의** 척도를 구할 수 없으면 '계산 불가'다 (US2, SC-005)

픽스처의 완전 상수 계열(모양 4: MAD=0, IQR=0)이 속한 분야를 고른다.

```bash
curl -s -o /dev/null -w "%{http_code}\n" "localhost:8080/regime-searches?mode=field&field=<상수계열분야>"
# 200 이어야 한다. 503·500 이면 결함
curl -s "localhost:8080/regime-searches?mode=field&field=<상수계열분야>" \
  | jq '.result | {status, statusReason, cases, dataAsOf, basis, versions}'
# status=NOT_COMPUTABLE, statusReason=SCALE_ZERO, cases=[]
# dataAsOf·basis·versions 가 **모두 있어야 한다** (SC-007·SC-009)
```

**빈 결과는 오류가 아니다.** 오류 화면이 뜨면 US2 시나리오 4 위반이다.

구멍 뚫린 계열(모양 2, 연속 60개월 미만)도 같은 방식으로 확인한다 — `statusReason`이
`SCALE_WINDOW_TOO_SHORT`.

**후보 쪽은 다르다**(모델 v2) — 후보의 자를 못 만들면 **검색은 정상이고 그 후보만 빠진다.**
픽스처 모양 10(자료 시작 직후 구간)으로 확인한다.

```bash
curl -s "localhost:8080/regime-searches?mode=field&field=LABOR" | jq '.result.counts'
# NO_CASE_SCALE 이 0 이 아니어야 한다 — 0 이면 그 경로를 한 번도 안 밟은 것이다
curl -s "localhost:8080/regime-searches?mode=field&field=LABOR" | jq '.result.status'
# "NEAREST_ONLY" — 후보가 빠졌다고 계산 불가가 되지 않는다
```

## S4. 비교 시점이 지표별로 정해지고, 빠진 달이 표기된다 (FR-001, FR-031, SC-007)

픽스처 모양 8(한 지표만 한 달 결측)이 속한 분야를 고른다.

```bash
curl -s "localhost:8080/regime-searches?mode=field&field=<한달결측분야>" | jq '.result.comparisonMonths'
```

**보여야 하는 것**

- 결측이 있는 지표만 `months`가 2개, `omitted`에 그 달, `note`에 한 문장 설명
- **다른 지표는 `months`가 3개 그대로** — 구멍 하나가 분야 전체를 줄이지 않는다
- 화면에서 `note`가 **숨길 수 없는 자리에** 보인다

모양 9(어느 지표의 세 달이 전부 결측)이 속한 분야는 `NOT_COMPUTABLE` / `QUERY_INPUT_MISSING`이어야
한다. **그 지표를 빼고 나머지로 계산한 결과가 나오면 FR-007 위반이다.**

```bash
curl -s "localhost:8080/regime-searches?mode=field&field=<전부결측분야>" | jq '.result.statusReason'
# "QUERY_INPUT_MISSING"
```

## S5. 종합 비교는 기준월이 바뀐다 (US3)

```bash
curl -s "localhost:8080/regime-searches/baseline-months" | jq '.result | {byField, composite, range}'
# composite 가 byField 의 어느 값보다 이를 수 있다 (발표 시차)

curl -s "localhost:8080/regime-searches?mode=composite" | jq '.result | {baselineMonth, baselineOrigin, status}'
# baselineOrigin=T_ALL
```

**화면에서**: 분야 보기에서 'Thorp 종합 비교'를 누르면 *"전체 분야를 맞추기 위해 비교 기준월이
바뀝니다"* 한 문장이 보여야 한다(US3 시나리오 1).

**동일가중 표시**: 화면에 동일가중이 **Thorp의 설계 선택**이라는 표시가 있고, *"모든 지표의 중요성이
같다"* 는 표현은 없어야 한다(US3 시나리오 5, FR-010).

**더 최신인 개별 발표**: `dataAsOf.newerReleases`가 채워지면 화면에 **별도 정보로** 보이고 질의값에
섞이지 않아야 한다(FR-016, US3 시나리오 3).

**한 분야가 계산 불가이면 종합도 계산 불가**여야 한다 — 남은 분야로 계산하면 US3 시나리오 6 위반.

```bash
curl -s "localhost:8080/regime-searches?mode=composite" | jq '.result.status'
# 상수 계열 분야가 있으므로 NOT_COMPUTABLE 이어야 한다
```

## S6. 사례 상세로 수기 대조가 된다 (FR-028, SC-003)

```bash
curl -s "localhost:8080/regime-searches/cases/2012-03?mode=field&field=LABOR" | jq '.result.indicators[0]'
```

**보여야 하는 것**: `queryRaw`·`caseRaw`·`queryZ`·`caseZ`·`comparisonMonths`·`e`·`nominalWeight`·
`distanceShare`·**`queryScale`·`caseScale`** 각각 `{median, spread, method, windowStart, windowEnd, months}`

이 값들로 손계산이 되어야 한다.

```
z_질의 = (질의 raw − queryScale.median) / queryScale.spread
z_후보 = (후보 raw − caseScale.median)  / caseScale.spread     ← 자가 다르다
E_i = 그 지표의 비교 시점들의 |z_질의 − z_후보| 평균
D_g = 분야 내 E_i 의 산술평균
distanceShare = E_i ÷ 분야 안 ΣE_i
```

`data/tests/regime/fixtures/manual_reconciliation.json`에 고정한 스프레드시트 계산과 대조한다.
허용 오차는 PD-12 확정 전까지 **0(정확 일치)** 이다.

**`mode=field`에는 `contribution`이 없어야 한다** — 분야가 하나라 늘 1이고 뜻 없는 숫자다.
**거리가 0인 사례**가 있으면 `distanceShare` 키도 아예 없어야 한다.

**두 척도가 나란히 온다**(FR-005, SC-011):

```bash
curl -s "localhost:8080/regime-searches/cases/2012-03?mode=field&field=LABOR"   | jq '.result.indicators[0] | {queryScale, caseScale}'
# queryScale.windowEnd 가 2026-04  (기준월 2026-07 의 3개월 앞)
# caseScale.windowEnd  가 2011-12  (후보 2012-03 의 3개월 앞)   ← 서로 다른 구간
```

**보이면 안 되는 것**: 두 척도가 같은 구간 — 후보에 질의 자를 쓴 것이고 SC-011 위반이다.

**기준월을 바꿔도 `caseScale`은 같아야 한다** — 후보 척도는 기준월과 무관하다.

```bash
A=$(curl -s "localhost:8080/regime-searches/cases/2012-03?mode=field&field=LABOR&baselineMonth=2026-07" | jq -S '.result.indicators[].caseScale')
B=$(curl -s "localhost:8080/regime-searches/cases/2012-03?mode=field&field=LABOR&baselineMonth=2025-01" | jq -S '.result.indicators[].caseScale')
[ "$A" = "$B" ] && echo "동일"   # 다르면 SC-011 위반
```

직전 연속 60개월로 자를 만들 수 없는 후보는 **목록에 아예 없어야** 한다.

## S7. 같은 검색은 늘 같은 결과다 (SC-001, SC-002)

```bash
# 두 번 호출해 완전히 같은지
A=$(curl -s "localhost:8080/regime-searches?mode=field&field=LABOR&limit=10")
B=$(curl -s "localhost:8080/regime-searches?mode=field&field=LABOR&limit=10")
diff <(echo "$A" | jq -S .) <(echo "$B" | jq -S .) && echo "동일"

# 배치를 다시 돌려도 행이 늘지 않는지 (content_hash)
psql -c "SELECT count(*) FROM regime_search_snapshot;"
cd data && .venv/Scripts/python scripts/precompute_regime.py --source fixture --all
psql -c "SELECT count(*) FROM regime_search_snapshot;"
# 두 값이 같아야 한다. 늘어나면 content_hash 에 시각 값이 섞인 것이다
```

**기준월의 근거가 달라도 같은 결과여야 한다**(Clarifications 2026-09-16).

```bash
# T_g 로 자동으로 얻은 검색과, 같은 월을 직접 지정한 검색
curl -s "localhost:8080/regime-searches?mode=field&field=LABOR" | jq '.result.searchId'
curl -s "localhost:8080/regime-searches?mode=field&field=LABOR&baselineMonth=2026-07" | jq '.result.searchId'
# searchId 가 같아야 한다. baselineOrigin 만 T_G / USER_SPECIFIED 로 다르다
```

**전략을 바꿔도 순위가 같아야 한다**(SC-001). 010은 전략을 입력으로 받지 않으므로 이 성질은
구조적으로 성립하지만, **전략 관련 파라미터가 API에 없는지**로 확인한다.

## S8. 요청 처리 중에 계산하지 않는다 (SC-002a)

```bash
for i in $(seq 1 20); do
  curl -s -o /dev/null -w "%{time_total}\n" "localhost:8080/regime-searches?mode=field&field=LABOR"
done | sort -n | tail -1
# research R9: p95 300ms 는 목표이지 한도가 아니다
```

시간보다 확실한 증거는 **구조**다. `regime` 패키지에 계산 코드가 없고(`RegimeNoComputationTest`),
**지표 시계열을 읽는 코드도 없어야 한다**. Backend가 008에서 읽는 것은 `MacroIndicatorPort`의
등록표(이름·단위·분야 순서)뿐이다.

```bash
grep -rn "macro_indicator_value\|monthlySeries\|rawSeries" backend/src/main/java/com/ssafy/thorp/regime
# 결과가 없어야 한다 — 시계열은 data/ 배치만 읽는다 (포트 계약 §0.1)
```

## S9. 임시 대역이 격리돼 있다 (008 완성 전 상시 확인)

픽스처는 008이 오면 **디렉터리만 지우면 끝나야** 한다. 그 조건이 지켜지는지 본다.

임시 대역은 **두 곳**이고 담는 것이 다르다 — `data/…/macro/fixture/`는 시계열,
`backend/…/regime/infrastructure/macro/fixture/`는 등록표(이름·단위·순서)다. **겹치는 값이 없어
어긋날 자리가 없다** — 겹칠 뻔했던 T_g·T_all은 배치가 `regime_baseline_month`에 기록한다.

```bash
# 1) 가짜 자료임이 응답에 드러나는가
curl -s "localhost:8080/regime-searches?mode=field&field=LABOR" | jq '.result.versions'
# {"data": "FIXTURE-20260915", ..., "sampleData": true}  ← data 가 FIXTURE- 로 시작

# 2) 픽스처가 두 디렉터리 밖으로 새지 않았는가
grep -rn "Fixture" data/src/data_pipeline/regime | grep -v "/macro/fixture/"
grep -rn "Fixture" backend/src/main/java/com/ssafy/thorp/regime --include=*.java | grep -v "/macro/fixture/"
# 둘 다 결과가 없어야 한다. 운영 코드는 인터페이스만 알고 픽스처를 이름으로 참조하지 않는다

# 3) DB 행이 전부 표시돼 있는가
psql -c "SELECT DISTINCT data_version FROM regime_search_snapshot;"
# 008 전에는 FIXTURE- 로 시작하는 값만 나와야 한다
```

**가드 둘**: 설정 실수 하나로 가짜 자료가 운영에 나가는 경로를 양쪽에서 막는다.

```bash
# 배치 — 적재 전에 종료해야 한다
THORP_ENV=prod .venv/Scripts/python scripts/precompute_regime.py --source fixture --all
# 0이 아닌 종료 코드로 끝나야 하고, DB에 아무것도 쓰이지 않아야 한다

# Backend — prod 프로파일 + thorp.regime.macro.source=fixture 면 기동에 실패해야 한다
```

**폐기 예행연습**(008이 오기 전 한 번 해 두면 좋다): 두 디렉터리를 지워 보고 컴파일·테스트가 어디서
깨지는지 본다. 깨지는 자리가 픽스처를 쓰는 테스트뿐이면 격리가 성립한 것이고, 운영 코드가 깨지면
경계가 이미 무너진 것이다. 확인 후 되돌린다.

## 지금 확인할 수 없는 것

정직하게 적어 둔다. 이 목록이 줄어드는 것이 008·011·014 진행의 척도다.

| 무엇 | 왜 | 언제 |
|---|---|---|
| **모델 v2 자체를 써도 되는가** | 기획서 D21과 constitution 세 문장이 아직 "모든 후보에 같은 척도"를 정하고 있다. 개정 전까지 010은 **차단 상태**이며 M2 착수 불가([d21-amendment.md](d21-amendment.md)) | 전체 회의 승인 후 |
| **사례가 실제로 그럴듯한가** | 픽스처 값이 지어낸 것이라 "2012년 3월이 가까웠다"가 경제적으로 맞는 말인지 알 수 없다 | 008 완성 후 |
| 실제 과거 자료 범위와 그에 따른 후보 수 | 통계표별 과거 범위를 인증키 조회로 확인해야 안다(008 PD-11) | 008 완성 후 |
| `changed_since()` 기반 증분 재계산 | 픽스처는 `None`을 돌려줘 늘 전 구간이다. `t ≥ M`만 도는 경로는 테스트로만 확인된다 | 008 완성 후 |
| **자료 시작 연도에 따른 실제 탐색 범위** | 모델 v2는 각 지표 **자료 시작 + 5년**부터 탐색한다(후보도 자기 자가 필요하므로). 실제 과거 범위는 008 PD-11 확인 후에야 안다 | 008 완성 후 |
| **기준월이 008보다 뒤처지는 상황** | 픽스처는 배치와 같은 시점이라 어긋나지 않는다. `regime_baseline_month`가 008의 최신 T_g보다 뒤에 있어도 404가 안 나는지는 실제 운영에서만 확인된다(포트 계약 §0.2·§5) | 008 완성 후 |
| `THRESHOLD_APPLIED` 상태 | PD-09(U04) 미확정이라 그 상태에 도달할 수 없다. 화면·API 경로는 테스트로만 확인된다 | PD-09 확정 후 |
| FR-021 "충족 사례 없음" 안내 | 위와 같은 이유 | PD-09 확정 후 |
| **`regime_search_log` 기록** | 표만 만들고 쓰는 코드는 없다. 비로그인 검색은 기록을 남기지 않으므로 014 전에는 행이 생기지 않는다(FR-032) | 014 완성 후 |
| 보관 정리의 실제 효과 | 옛 revision이 쌓여야 지울 것이 생긴다. 픽스처 한 벌로는 revision이 1뿐이다 | 자료 개정이 한 번 이상 일어난 뒤 |
| **008 수집 실패와 "새 발표 없음"의 구별** | 010 화면은 자료 기준 시점만 보인다. 수집 상태 표시는 008·009 소관이다(spec Out of Scope) — **010만 보면 끝까지 구별되지 않는다** | 008·009 구현 후 |
| 사례 이후 시장·전략 결과 | 011 범위. `postPeriod.available`이 항상 `false` | 011 구현 후 |
| 가용성 필터가 걸린 목록 | 010은 `candidates` 목록만 내준다. 필터는 011이 건다 | 011 구현 후 |
| 지표별 최소 유효 척도에 의한 계산 불가 | PD-10(008 소유) 미확정. MAD→IQR→0 경로만 확인된다 | PD-10 확정 후 |
| 사용성 관찰(SC-008) | **010 범위 안이지만 티켓이 따로다.** 구현이 끝나야 시작할 수 있고(spec SC-008) 사람 6~8명 섭외·관찰이라 기간이 주 단위다 | **M10(T154)** 별도 티켓 |

**여기 나온 숫자를 성과로 인용하지 않는다.** 픽스처 위의 결과이며 경제에 대해 아무것도 말하지 않는다.
