# Quickstart: 경제환경 검색

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **계약**: [contracts/regime-search-api.md](contracts/regime-search-api.md)

010이 실제로 동작하는지 확인하는 절차다. 각 시나리오는 **무엇이 보여야 하는지**와 **무엇이 보이면
안 되는지**를 함께 적는다 — 010은 보이면 안 되는 것(확률·배지·채워진 빈자리)이 결함인 기능이다.

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

## S3. 척도를 구할 수 없으면 '계산 불가'다 (US2, SC-005)

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
`distanceShare`·`scale{median, spread, method, windowStart, windowEnd, months}`

이 값들로 손계산이 되어야 한다.

```
z = (raw − median) / spread
E_i = 그 지표의 비교 시점들의 |z_질의 − z_후보| 평균
D_g = 분야 내 E_i 의 산술평균
distanceShare = E_i ÷ 분야 안 ΣE_i
```

`data/tests/regime/fixtures/manual_reconciliation.json`에 고정한 스프레드시트 계산과 대조한다.
허용 오차는 PD-12 확정 전까지 **0(정확 일치)** 이다.

**`mode=field`에는 `contribution`이 없어야 한다** — 분야가 하나라 늘 1이고 뜻 없는 숫자다.
**거리가 0인 사례**가 있으면 `distanceShare` 키도 아예 없어야 한다.

**시대별 위치**(FR-017, SC-011):

```bash
curl -s "localhost:8080/regime-searches/cases/2012-03?mode=field&field=LABOR" | jq '.result.eraPosition'
# 지표별 {sentence, method, windowStart, windowEnd, months}
# windowEnd 가 2012-03 보다 3개월 이상 앞이어야 한다 (c-122 ~ c-3)
```

**보이면 안 되는 것**: `eraPosition` 안의 z 값·분위수·백분율 — **문장만** 온다.

**같은 자료·등록표·모델 버전에서 기준월을 바꿔도 같아야 한다** — 시대별 보조 위치는 기준월과 무관하다. 비교할 두 응답의 버전이 다르면 이 확인의 대상으로 삼지 않는다.

```bash
A=$(curl -s "localhost:8080/regime-searches/cases/2012-03?mode=field&field=LABOR&baselineMonth=2026-07" | jq -S '.result.eraPosition')
B=$(curl -s "localhost:8080/regime-searches/cases/2012-03?mode=field&field=LABOR&baselineMonth=2025-01" | jq -S '.result.eraPosition')
[ "$A" = "$B" ] && echo "동일"   # 다르면 SC-011 위반
```

직전 연속 60개월이 부족하거나 보조 척도가 0인 지표는 **키가 없어야** 하고, 전부 계산 불가이면 `eraPosition`이 `null`이다 —
`0`이나 `"-"`로 채우면 원칙 VI 위반이다. 픽스처 모양 10의 후보는 비교 입력이 있으므로 전체 후보 목록에 남아야 한다. 보조 설명 부족을 후보 제외 사유로 쓰면 안 된다.

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
| **사례가 실제로 그럴듯한가** | 픽스처 값이 지어낸 것이라 "2012년 3월이 가까웠다"가 경제적으로 맞는 말인지 알 수 없다 | 008 완성 후 |
| 실제 과거 자료 범위와 그에 따른 후보 수 | 통계표별 과거 범위를 인증키 조회로 확인해야 안다(008 PD-11) | 008 완성 후 |
| `changed_since()` 기반 증분 재계산 | 픽스처는 `None`을 돌려줘 늘 전 구간이다. `t ≥ M`만 도는 경로는 테스트로만 확인된다 | 008 완성 후 |
| **기준월이 008보다 뒤처지는 상황** | 픽스처는 배치와 같은 시점이라 어긋나지 않는다. `regime_baseline_month`가 008의 최신 T_g보다 뒤에 있어도 404가 안 나는지는 실제 운영에서만 확인된다(포트 계약 §0.2·§5) | 008 완성 후 |
| `THRESHOLD_APPLIED` 상태 | PD-09(U04) 미확정이라 그 상태에 도달할 수 없다. 화면·API 경로는 테스트로만 확인된다 | PD-09 확정 후 |
| FR-021 "충족 사례 없음" 안내 | 위와 같은 이유 | PD-09 확정 후 |
| **`regime_search_log` 기록** | 표만 만들고 쓰는 코드는 없다. 비로그인 검색은 기록을 남기지 않으므로 014 전에는 행이 생기지 않는다(FR-032) | 014 완성 후 |
| 보관 정리의 실제 효과 | 옛 revision이 쌓여야 지울 것이 생긴다. 픽스처 한 벌로는 revision이 1뿐이다 | 자료 개정이 한 번 이상 일어난 뒤 |
| **008 수집 실패와 "새 발표 없음"의 구별** | 010 화면은 자료 기준 시점만 보인다. 수집 상태 표시는 008·009 소관이다(spec Out of Scope) — **010만 보면 끝까지 구별되지 않는다** | 008·009 구현 후 |
| 사례 이후 시장·전략 결과 | 011 범위. `postPeriod.available`이 항상 `false` | 011 구현 후 |
| 가용성 필터가 걸린 목록 | 010은 `candidates` 목록만 내준다. 필터는 011이 건다 | 011 구현 후 |
| 지표별 최소 유효 척도에 의한 계산 불가 | PD-10(008 소유) 미확정. MAD→IQR→0 경로만 확인된다 | PD-10 확정 후 |
| 사용성 관찰(SC-008) | **010 범위 안이며 구현이 끝난 뒤 수행**한다. 구현 완료와 사용성 확인을 구분한다 | **M10(T155)** 별도 티켓 |
| **후보가 3개 미만인 화면**(SC-004) | 픽스처의 늦은 시작 계열이 하나뿐이고, 척도 60개월이 차는 달과 후보가 넉넉해지는 달이 겹쳐 `0 < displayed < 3`인 기준월이 없다(2026-09-17 실행 기록). 단위 테스트로만 확인된다 | 픽스처에 더 늦게 시작하는 계열을 더하거나 008 완성 후 |
| **임시 대역을 지웠을 때 무엇이 깨지는가**(T150) | 디렉터리 삭제·이동이 실행 환경에서 차단돼 예행연습을 하지 못했다. grep 결과로 격리를 추정했을 뿐이다 | 삭제 권한이 있는 환경에서 |
| v1·v2 중 어느 유사성이 사용자 목적에 맞는가 | 실제 자료 비교와 사용자 관찰 전에는 우열을 확정할 수 없다. [변경 제안서](d21-amendment.md)의 검증 계획이며 v1 구현 차단 조건이 아니다 | 비교 검증 후 재논의 |

**여기 나온 숫자를 성과로 인용하지 않는다.** 픽스처 위의 결과이며 경제에 대해 아무것도 말하지 않는다.

## 실행 기록

### 2026-09-17 · M9(T149~T154) · 임시 대역 `FIXTURE-20260915`

**환경**: Windows 11 / Java 21 / PostgreSQL 16·RabbitMQ·Kafka(도커) / 백엔드 `local` 프로파일 /
Python **3.12.10**(문서가 정한 3.11은 이 기기에 없다) · pandas 3.0.5 · `data` 테스트 836개 통과.

**여기 숫자는 픽스처 위의 측정값이다.** 목표치가 아니고 경제에 대해 아무것도 말하지 않는다.

#### 사전 준비가 문서와 달랐던 것 — 넷 다 quickstart를 고쳐야 한다

1. **순서가 반대다.** `local` 프로파일은 `flyway.enabled=false` + `ddl-auto=update`라 **백엔드가 먼저
   떠야** `regime_search_snapshot`이 생긴다. 빈 DB에 사전 계산을 먼저 돌리면 적재할 표가 없다.
2. **`MAIL_USERNAME`·`MAIL_PASSWORD`가 없으면 기동이 실패한다.** `application.yml`이 기본값 없이
   참조하는데 `application-local.yml`에도 없고 Spring은 `.env`를 읽지 않는다. 010과 무관한 회원가입
   메일 설정이지만 기동을 막는다. 실행에 쓴 명령:
   `SPRING_PROFILES_ACTIVE=local MAIL_USERNAME=dev@example.com MAIL_PASSWORD=dummy ./gradlew bootRun`
3. **`uv`가 없어 `python -m venv`로 만들었다.** `data/CLAUDE.md`는 `uv`를 전제한다.
4. **`jq`가 없어** 모든 조회를 Python으로 대신했다. quickstart 명령은 전부 `jq` 기반이다.

#### S1~S9 결과

| | 결과 | 확인한 것 |
|---|---|---|
| S1 | **통과** | `LABOR` 3개, `NEAREST_ONLY`/`최근접 참고 사례`, `basis=HISTORICAL_REVISION`, `distance` 문자열 12자리, `counts` 넷 다 있음. 금지 표현·`sector` **0건**. `limit=10`에서 `stored=displayed=10`이라 '더 보기'가 사라진다 |
| S2 | **부분** | 빈자리를 채운 사례 **0건**(`displayed == len(cases)`). 다만 **후보 3개 미만 상황이 재현되지 않았다** — 아래 참조 |
| S3 | **통과** | `FINANCE_HOUSING`@2011-06 → `SCALE_ZERO`, `EXTERNAL_TRADE`@2015-01 → `SCALE_WINDOW_TOO_SHORT`. 둘 다 HTTP 200·`cases=[]`이고 `dataAsOf`·`basis`·`versions`가 모두 있다 |
| S4 | **통과** | `PRODUCTION` 기본 기준월에서 결측 지표만 `months=2`·`omitted=[2026-05]`·`note` 있음, 나머지 둘은 `months=3` 그대로. `CONSUMPTION_INVESTMENT`@2015-03 → `QUERY_INPUT_MISSING`(남은 지표로 계산한 결과가 나오지 않는다) |
| S5 | **통과** | `composite=2026-04`가 `byField`(2026-06)보다 이르다. `baselineOrigin=T_ALL`, `newerReleases` 16건이 질의값과 분리돼 나온다. 한 분야가 계산 불가인 2011-06에서 종합도 `NOT_COMPUTABLE` |
| S6 | **통과(정확 일치)** | 사례 2014-04에서 `z=(raw−median)/spread`, `E_i`, `D_g`를 손계산해 응답과 **12자리까지 같다**. `ΣdistanceShare=1`, `mode=field`에 `contribution` 없음. `eraPosition`은 문장만이고 `windowEnd=2014-01`(c−3). 기준월 2026-06/2026-05에서 `eraPosition` 동일(SC-011) |
| S7 | **통과** | 두 번 호출 결과 완전 동일. `searchId`가 T_g 경로와 지정 경로에서 같고 `baselineOrigin`만 `T_G`/`USER_SPECIFIED`로 다르다. **배치 재실행: 새 revision 0개·같은 내용 2984개 건너뜀·행 수 2984 그대로** |
| S8 | **통과** | `regime` 패키지에 시계열을 읽는 코드 0건(주석 제외). 응답 시간은 아래 측정값 |
| S9 | **부분** | 가드 둘 다 동작. 격리 검사 명령은 **오탐을 낸다** — 아래 참조. **폐기 예행연습(T150)은 미실행** |

#### 측정값 (T151·T152)

| 항목 | 값 |
|---|---|
| 사전 계산 1회차(빈 DB) | **235.1초** · 다시 계산한 기준월 **2984개** · 새 revision **2984개** · 중간 산출 파티션 253개 |
| 사전 계산 2회차(같은 자료) | **66.2초** · 새 revision **0개** · 같은 내용 건너뜀 2984개 · 보관 정리 0개 |
| `regime_search_snapshot` 행 수 | 2984 (재실행 후에도 같음) |
| 조회 응답 시간 (`mode=field&field=LABOR`, n=20) | 최소 11.9ms · 중앙값 13.6ms · p95 **19.0ms** · 최대 20.6ms |
| 조회 실패 | **0건** |

research R9의 p95 300ms는 **측정 목표이지 한도가 아니다**. 004 FR-007의 실행 유형 기록에 붙이는 일은
004 완성 후로 미룬다 — 지금 붙일 자리가 없다.

#### S2가 부분인 이유 — 후보 3개 미만이 재현되지 않는다

시작이 늦은 계열은 `G20_LEADING_INDEX`(2005-01) 하나이고 그 분야는 `GLOBAL_ECONOMY`다. 기준월을
훑으면 **2010-02까지 `SCALE_WINDOW_TOO_SHORT`(eligible 0)였다가 2010-03에서 곧바로 eligible 48**이
된다. 척도 구간 60개월이 차는 시점이 후보가 넉넉해지는 시점보다 늦어, `0 < displayed < 3`인 기준월이
없다. **SC-004의 "부족하면 부족한 대로"는 API 경로로 확인되지 않았고 단위 테스트로만 확인된다.**
이 모양을 API로 보려면 픽스처에 더 늦게 시작하는 계열이 하나 더 있어야 한다.

#### S9 격리 검사 명령이 오탐을 낸다

quickstart는 `grep -rn "Fixture" backend/…/regime | grep -v "/macro/fixture/"`의 **결과가 없어야
한다**고 적었지만 셋이 나오고 **셋 다 있어야 하는 코드**다.

| 파일 | 왜 이름을 알아야 하나 |
|---|---|
| `domain/model/RegimeSnapshot.java` | `FIXTURE-` 접두사로 `versions.sampleData`를 판정한다. 008이 없어서 쓰는 대역인데 그 판정을 008에 맡기면 순환이다(계약 §4.2) |
| `infrastructure/config/RegimeFixtureGuard.java` | S9가 요구하는 prod 가드 자신이다 |
| `infrastructure/config/RegimeProperties.java` | `source: fixture|macro` 설정값 |

`data` 쪽은 결과가 없어 격리가 성립한다. 검사 문장을 "이 셋 외에 0건"으로 고쳐야 한다.

#### 가드 둘

- **배치**: `THORP_ENV=prod … --source fixture` → 종료 코드 **1**, 적재 없음(행 수 2984 유지)
- **Backend**: `RegimeProfileGuardTest` 통과. 실제 prod 기동은 운영 환경변수 전체가 필요해 돌리지 않았다

#### 하지 못한 것

- **T150 폐기 예행연습 — 미실행.** 두 디렉터리를 지우거나 옮기는 조작이 이 실행 환경에서 차단됐다.
  격리 여부는 위의 grep 결과로만 추정했을 뿐 **컴파일·테스트가 어디서 깨지는지는 확인하지 않았다.**
- **화면 확인(S5의 문구·표시)** — 프론트를 띄워 눈으로 보지 않았다. 해당 문구·금지 표현·키보드 도달은
  M7·M8의 프론트 테스트가 고정하고 있으나, **그것은 테스트가 통과했다는 뜻이지 화면을 봤다는 뜻이 아니다.**

#### T154 · 문서 상태와 008 계약 재확인

**`specs/README.md`는 고치지 않았다.** 그 표에는 구현 상태 열이 없고 범위·§8.1 항목·우선순위만 적는다.
PD-08은 `기획서 확정`인데 이는 **기획 결정의 상태**이지 구현 상태가 아니므로 그대로 둔다 — M9가 끝났다고
올릴 칸이 아니다. 010의 모델 v1 기준도 그대로다.

**008이 약속한 `MacroIndicatorSource`와 010이 요구하는 것이 아직 어긋난다.** 지금 구현된 프로토콜
(`data/src/data_pipeline/regime/macro/source.py`)은 010 계약 쪽 형태다.

| | 008 [`macro-indicator-source.md`](../008-macro-indicator-registry/contracts/macro-indicator-source.md) | 010 [`macro-indicator-port.md`](contracts/macro-indicator-port.md) · 현재 구현 |
|---|---|---|
| 버전 인자 | `registry(version)`·`monthly_series(id, data_version)`·`baseline_months(data_version, registry_version)` 등 **호출마다 버전을 고정** | `registry()`·`monthly_series(id)`·`baseline_months()` — **인자 없음** |
| 자료 버전 | current 포인터를 한 번 읽어 고정 (`data_version()` 없음) | `data_version()`이 따로 있다 |
| `changed_since` | `(previous, target)` 두 버전 | `(data_version)` 하나 |
| 결측 사유 | 네 가지(원천 미발표·수집 실패·변환 불가·개편 제외)를 값과 함께 | 계약 표에는 사유 언급 없음 |
| `T_all=null` | 010이 `composite:null`·분야 `baselineMonth:null`을 명시하고 `NOT_COMPUTABLE`로 처리하도록 **010 계약을 갱신하라**고 요구 | 현재 픽스처는 `composite`가 늘 있어 **이 경로가 실행되지 않았다** |

008 문서도 *"기존 010 계약의 인자 없는 `registry()`·`data_version()`과 현재 표시명 조회는 버전 고정
규칙에 맞춰 정합화해야 한다"* 고 스스로 적어 두었다. **어느 쪽으로 맞출지는 정해지지 않았고, M9에서
정하지 않는다** — 008 구현이 시작될 때 두 spec이 함께 결정할 일이다. 여기서는 어긋나 있다는 사실만
남긴다. 정합화 전까지 010은 현재 프로토콜로 돌고, 008 완성 시 구현체 교체만으로 끝나지 않는다.
