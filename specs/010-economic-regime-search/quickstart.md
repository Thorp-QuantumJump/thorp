# Quickstart: 경제환경 검색

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **계약**: [contracts/regime-search-api.md](contracts/regime-search-api.md)

010이 실제로 동작하는지 확인하는 절차다. 각 시나리오는 **무엇이 보여야 하는지**와 **무엇이 보이면
안 되는지**를 함께 적는다 — 010은 보이면 안 되는 것(확률·배지·채워진 빈자리)이 결함인 기능이다.

## 사전 준비

```bash
# 1) 자료 파이프라인
cd data
.venv/Scripts/python scripts/precompute_regime.py --fixture --all
# 전 구간 사전 계산 후 PostgreSQL 적재. 소요 시간이 출력된다(FR-033 기록 대상)

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
curl -s "localhost:8080/regime-searches?mode=sector&field=LABOR" | jq '.result'
```

**보여야 하는 것**
- `cases`가 3개, 각각 `endMonth`·`observationMonths`(3개월)·`similar`·`different`
- `similar`·`different`가 **지표의 실제 값과 단위**로 서술 — 예: `"실업률은 3.1%로 당시 3.2%와 비슷합니다."`
- `status`가 `NEAREST_ONLY`, `caseLabel`이 `"최근접 참고 사례"`
- `distance`가 **문자열**이고 소수점 아래 12자리

**보이면 안 되는 것**
- `"% 유사"`, `"대표 유사국면"`, `"충분히 유사"`, `"지금도 잘될 전략"` (FR-018·FR-022)
- 원인 단정 — `"수출 부진으로"` 같은 표현 (US1 시나리오 4)
- 거리 함수·척도·지표 조합을 고르는 파라미터 (US1 시나리오 5)

```bash
# '더 보기'
curl -s "localhost:8080/regime-searches?mode=sector&field=LABOR&limit=10" | jq '.result.counts'
# displayed ≤ 10. 후보가 부족하면 부족한 대로 (FR-013)
```

## S2. 후보가 부족하면 채우지 않는다 (FR-013, SC-004)

픽스처에서 시작 연도가 늦은 계열의 분야를 고른다. 후보가 3개 미만이어야 한다.

```bash
curl -s "localhost:8080/regime-searches?mode=sector&field=<시작이늦은분야>" | jq '.result.counts'
```

`displayed < 3`이고 `cases` 길이가 그 값과 같아야 한다. **빈자리를 채운 사례가 0건**이어야 한다 —
거리가 먼 후보를 끌어와 3개를 맞추면 SC-004 위반이다.

## S3. 척도를 구할 수 없으면 '계산 불가'다 (US2, SC-005)

픽스처의 완전 상수 계열(MAD=0, IQR=0)이 속한 분야를 고른다.

```bash
curl -s -o /dev/null -w "%{http_code}\n" "localhost:8080/regime-searches?mode=sector&field=<상수계열분야>"
# 200 이어야 한다. 503·500 이면 결함
curl -s "localhost:8080/regime-searches?mode=sector&field=<상수계열분야>" | jq '.result | {status, statusReason, cases}'
# status=NOT_COMPUTABLE, statusReason=SCALE_ZERO, cases=[]
```

**빈 결과는 오류가 아니다.** 오류 화면이 뜨면 US2 시나리오 4 위반이다.

구멍 뚫린 계열(연속 60개월 미만)도 같은 방식으로 확인한다 — `statusReason`이
`SCALE_WINDOW_TOO_SHORT`.

```bash
# 몰래 빼고 계산하지 않았는지 — 척도에 지표가 전부 있는지 본다
curl -s "localhost:8080/regime-searches?mode=sector&field=<정상분야>" | jq '.result.versions'
```

## S4. 종합 비교는 기준월이 바뀐다 (US3)

```bash
curl -s "localhost:8080/regime-searches/baseline-months" | jq '.result | {byField, composite}'
# composite 가 byField 의 어느 값보다 이를 수 있다 (발표 시차)

curl -s "localhost:8080/regime-searches?mode=composite" | jq '.result | {baselineMonth, baselineOrigin, status}'
# baselineOrigin=T_ALL
```

**화면에서**: 분야 보기에서 'Thorp 종합 비교'를 누르면 *"전체 분야를 맞추기 위해 비교 기준월이
바뀝니다"* 한 문장이 보여야 한다(US3 시나리오 1).

**동일가중 표시**: 화면에 동일가중이 **Thorp의 설계 선택**이라는 표시가 있고, *"모든 지표의 중요성이
같다"* 는 표현은 없어야 한다(US3 시나리오 5, FR-010).

**한 분야가 계산 불가이면 종합도 계산 불가**여야 한다 — 6개 분야로 계산하면 US3 시나리오 6 위반.

```bash
curl -s "localhost:8080/regime-searches?mode=composite" | jq '.result.status'
# 상수 계열 분야가 있으므로 NOT_COMPUTABLE 이어야 한다
```

## S5. 사례 상세로 수기 대조가 된다 (FR-028, SC-003)

```bash
curl -s "localhost:8080/regime-searches/cases/2012-03?mode=sector&field=LABOR" | jq '.result.indicators[0]'
```

**보여야 하는 것**: `queryRaw`·`caseRaw`·`queryZ`·`caseZ`·`scale{median, spread, method, windowStart, windowEnd, months}`

이 값들로 손계산이 되어야 한다.

```
z = (raw − median) / spread
E_i = 세 시점 |z_질의 − z_후보| 의 평균
D_g = 분야 내 E_i 의 산술평균
```

`data/tests/regime/fixtures/manual_reconciliation.json`에 고정한 스프레드시트 계산과 대조한다.
허용 오차는 PD-12 확정 전까지 **0(정확 일치)** 이다(004 research 결정과 같은 처리).

**거리가 0인 사례**(질의와 완전히 같은 후보)가 있으면 `contribution` **키가 아예 없어야** 한다 —
`0`이나 `null`로 채우면 Edge Case 위반이다.

## S6. 같은 검색은 늘 같은 결과다 (SC-001, SC-002)

```bash
# 두 번 호출해 완전히 같은지
A=$(curl -s "localhost:8080/regime-searches?mode=sector&field=LABOR&limit=10")
B=$(curl -s "localhost:8080/regime-searches?mode=sector&field=LABOR&limit=10")
diff <(echo "$A" | jq -S .) <(echo "$B" | jq -S .) && echo "동일"

# 배치를 다시 돌려도 같은지 (멱등)
cd data && .venv/Scripts/python scripts/precompute_regime.py --fixture --all
# 다시 호출해 위 A 와 비교
```

**전략을 바꿔도 순위가 같아야 한다**(SC-001). 010은 전략을 입력으로 받지 않으므로 이 성질은
구조적으로 성립하지만, **전략 관련 파라미터가 API에 없는지**로 확인한다.

## S7. 자료 기준 시점이 늘 보인다 (FR-031, SC-007)

```bash
curl -s "localhost:8080/regime-searches?mode=sector&field=LABOR" | jq '.result.dataAsOf'
# targetMonth, publishedAt, note 가 있어야 한다
```

**보이면 안 되는 것**: `retention`·`유지율`·`%` 형태의 겹침 비율 (FR-031 — 기록에만 남긴다)

```bash
curl -s "localhost:8080/regime-searches?mode=sector&field=LABOR" | jq 'tostring | test("retention|유지율")'
# false 여야 한다
```

## S8. 요청 처리 중에 계산하지 않는다 (SC-002a)

```bash
# 응답 시간이 조회 수준인지 (research R9: p95 300ms 는 목표이지 한도가 아니다)
for i in $(seq 1 20); do
  curl -s -o /dev/null -w "%{time_total}\n" "localhost:8080/regime-searches?mode=sector&field=LABOR"
done | sort -n | tail -1
```

시간보다 확실한 증거는 **로그**다. 요청 처리 경로에 척도·거리 계산이 남긴 로그가 0건이어야 하고,
`regime` 패키지에 계산 코드가 아예 없어야 한다(구조로 보장).

## S9. 임시 대역이 격리돼 있다 (008 완성 전 상시 확인)

픽스처는 008이 오면 **한 번에 지워져야** 한다. 그 조건이 지켜지는지 본다.

```bash
# 1) 가짜 자료임이 응답에 드러나는가
curl -s "localhost:8080/regime-searches?mode=sector&field=LABOR" | jq '.result.versions'
# {"data": "FIXTURE-20260915", ..., "sampleData": true}  ← data 가 FIXTURE- 로 시작

# 2) 픽스처가 세 디렉터리 밖으로 새지 않았는가
grep -rn "Fixture" backend/src/main/java/com/ssafy/thorp/regime   --include=*.java | grep -v "/macro/fixture/"
# 결과가 없어야 한다. 운영 코드는 포트만 알고 픽스처를 이름으로 참조하지 않는다

# 3) DB 행이 전부 표시돼 있는가
psql -c "SELECT DISTINCT data_version FROM regime_search_snapshot;"
# 008 전에는 FIXTURE- 로 시작하는 값만 나와야 한다
```

**prod 프로파일 가드**: `thorp.regime.macro.source: fixture`인 채로 prod 프로파일을 띄우면
**기동에 실패해야 한다.** 이 가드가 없으면 설정 실수 하나로 가짜 자료가 운영에 나간다.

**폐기 예행연습**(008이 오기 전 한 번 해 두면 좋다): 세 디렉터리를 지워 보고 컴파일·테스트가
어디서 깨지는지 본다. 깨지는 자리가 `macro/fixture/`를 쓰는 테스트뿐이면 격리가 성립한 것이고,
운영 코드가 깨지면 경계가 이미 무너진 것이다. 확인 후 되돌린다.

## 지금 확인할 수 없는 것

정직하게 적어 둔다. 이 목록이 줄어드는 것이 008·011 진행의 척도다.

| 무엇 | 왜 | 언제 |
|---|---|---|
| **사례가 실제로 그럴듯한가** | 픽스처 값이 지어낸 것이라 "2012년 3월이 가까웠다"가 경제적으로 맞는 말인지 알 수 없다 | 008 완성 후 |
| 실제 과거 자료 범위와 그에 따른 후보 수 | 통계표별 과거 범위를 인증키 조회로 확인해야 안다(008 PD-11) | 008 완성 후 |
| `THRESHOLD_APPLIED` 상태 | PD-09(U04) 미확정이라 그 상태에 도달할 수 없다. 화면·API 경로는 테스트로만 확인된다 | PD-09 확정 후 |
| FR-021 "충족 사례 없음" 안내 | 위와 같은 이유 | PD-09 확정 후 |
| 사례 이후 시장·전략 결과 | 011 범위. `postPeriod.available`이 항상 `false` | 011 구현 후 |
| 지표별 최소 유효 척도에 의한 계산 불가 | PD-10(008 소유) 미확정. MAD→IQR→0 경로만 확인된다 | PD-10 확정 후 |

**여기 나온 숫자를 성과로 인용하지 않는다.** 픽스처 위의 결과이며 경제에 대해 아무것도 말하지 않는다.
