# 대표 ETF 자료 수집 파이프라인 인계 (spec 001 JT4)

다른 컴퓨터에서 이 작업을 이어받는 사람을 위한 문서다. **무엇이 끝났고 무엇이 남았는지**,
**환경을 어떻게 갖추는지**, 그리고 **다시 논의하면 안 되는 결정이 무엇인지**를 담는다.

인계 문서를 `docs/handoffs/`가 아니라 여기에 둔 이유는 그 경로가 `.gitignore`에 있어
커밋되지 않기 때문이다(로컬 작업 메모용). 다른 컴퓨터로 옮겨야 하는 문서는 저장소에 남아야
한다.

| 항목 | 값 |
|---|---|
| Jira | `S15P21E205-179` (T007·T008·T009) |
| 브랜치 | `feat(be)/179-krx-ingest-adjustment-recon` |
| MR | `!60` (`dev` 대상, 리뷰 중) |
| 상태 | **구현 완료, AI 코드리뷰 반영 완료, 실제 적재 미수행** |
| 검증 | `pytest` 391 passed / 0 failed (`dev` 병합 후) |

---

## 1. 이 작업이 무엇인가

`raw → processed → PostgreSQL` **한 방향** 수집 파이프라인을 만들었다. 자료를 실제로
적재하지는 않았다(3절 참고).

```
KRX OPEN API
  │
  ├─ 지수 idx/kospi_dd_trd ──→ 거래일 달력을 만든다
  │     개별 ETF는 정지될 수 있지만 지수는 장이 열리면 언제나 산출되므로,
  │     지수에 그 날짜가 있다는 사실이 그날 장이 열렸다는 근거가 된다
  │
  └─ ETF etp/etf_bydd_trd ─ 달력의 거래일마다 한 번씩 호출
        │
        ├─ data/raw/            원천 응답 원본 (재수집 없이 다시 정제하려고 보관)
        │
        ├─ 종목별 처리
        │    ├─ 상장 구간 밖에 값이 있으면 멈춘다            (FR-005, SC-003)
        │    ├─ 체결 없는 날은 직전 종가로 채우고 정지 표시   (FR-006)
        │    ├─ 기업행위로 조정 계수를 만들어 과거에 누적 곱  (FR-004)
        │    └─ 자료 부족·정지 구간을 기록                   (FR-006)
        │
        ├─ data/processed/      Parquet, 자산/연도 파티션
        │
        └─ PostgreSQL           **`--commit`을 붙였을 때만**
```

### 파일 배치

| 파일 | 맡은 일 |
|---|---|
| `data/src/data_pipeline/krx.py` | 원천 호출, 컬럼 표준화, 상장 구간 검사, 이용 한도 판정 |
| `data/src/data_pipeline/index.py` | KOSPI 시리즈 수집, 거래일 달력, FR-009 확보 기간 검사 |
| `data/src/data_pipeline/corporate_actions.py` | 소급 조정 계수와 조정 가격 |
| `data/src/data_pipeline/gaps.py` | 거래정지·결측 처리, 자료 부족 구간, 정지 구간 |
| `data/src/data_pipeline/reconciliation.py` | 공시 대조 표본과 원인별 처리 |
| `data/src/data_pipeline/repository.py` | PostgreSQL 적재 (JT1이 만들고 JT4가 대조 기록을 더했다) |
| `data/scripts/ingest_krx.py` | 수집 진입점 |
| `data/scripts/ingest_index.py` | 지수 전용 진입점 |
| `data/scripts/verify_listing_boundary.py` | 상장 전 행을 세는 검증 (SC-003) |
| `data/scripts/reconcile.py` | 공시 대조 진입점 |

---

## 2. 새 컴퓨터에서 환경 갖추기

### 2.1 가상환경

**`data/.venv`가 DATA 파트 전용이다.** `worker/.venv`를 빌려 쓰지 않는다. PATH의 `python`은
Microsoft Store 스텁이라 잡히지 않으므로 `.venv/Scripts`를 앞에 붙인다.

```bash
cd data
uv venv --python 3.11 .venv
VIRTUAL_ENV="$PWD/.venv" uv pip install -r requirements.txt -r requirements-dev.txt
```

설치되는 것은 pandas, numpy, requests, pyarrow, SQLAlchemy, psycopg[binary],
python-dotenv, pytest, ruff뿐이다. **새 의존성을 추가하지 않는다.**

### 2.2 검증 명령

```bash
cd data
PATH="$PWD/.venv/Scripts:$PATH" ruff check src scripts tests
PATH="$PWD/.venv/Scripts:$PATH" ruff format src scripts tests
PATH="$PWD/.venv/Scripts:$PATH" pytest -q
```

기준선은 **391 passed / 0 failed**이다. 실패가 생기면 그 컴퓨터의 변경 때문이다.

**테스트는 네트워크를 쓰지 않는다.** `data/tests/fixtures/krx/`에 2026-09-11에 실제 응답을
한 번 받아 저장해 두었고 테스트는 그 파일만 읽는다. 그러므로 **인증키 없이도 전체 테스트가
통과한다.** 키는 실제 수집을 돌릴 때만 필요하다.

### 2.3 인증키와 접속 정보

`data/.env`는 `.gitignore`에 있어 저장소에 없다. 새 컴퓨터에서는 직접 만든다.

```bash
cd data
cp .env.example .env
```

| 변수 | 어디서 얻는가 |
|---|---|
| `KRX_API_KEY` | [KRX OPEN API 이용방법](https://openapi.krx.co.kr/contents/OPP/INFO/OPPINFO003.jsp)에서 인증키를 발급받고 **서비스별로 활용 승인**을 따로 받는다. 팀이 이미 쓰는 키는 저장소 루트 `.env`에 있으므로 그 파일을 가진 팀원에게 받는다 |
| `DATABASE_URL` | 로컬 개발용은 `.env.example`의 예시 그대로 쓴다. 컨테이너 안에서 돌릴 때는 호스트를 `localhost` 대신 `postgres`로 바꾼다 |

**키 값을 코드·로그·커밋 메시지·MR 본문·테스트 픽스처에 적지 않는다** (FR-012). 이 저장소의
어떤 커밋에도 키 값이 없다.

**활용 승인은 서비스 단위다.** 현재 승인된 것은 `etp/etf_bydd_trd`와 `idx/kospi_dd_trd`
둘뿐이고, 종목기본정보(`sto/stk_isu_base_info`)는 승인이 없어 401을 돌려준다. 이 사실이
설계에 영향을 주었다(4.5절).

---

## 3. 무엇이 끝났고 무엇이 남았는가

### 끝난 것

- T007 일봉·지수 수집과 이용 한도 처리
- T008 기업행위 반영 조정 가격, 자료 부족·정지 구간
- T009 공시 대조와 원인별 처리
- AI 코드리뷰(`/code-review high`) 지적 6건 반영
- 실제 API로 수집 경로를 끝까지 확인 (정상 수집, 이용 한도 재현, FR-009 검사)

### 끝나지 않은 것: **실제 적재**

`--commit`을 붙인 실행을 하지 않았다. 세 가지 때문이다.

| # | 걸림돌 | 지금 상태 |
|---|---|---|
| 1 | **발행본은 되돌릴 수 없다.** 마이그레이션의 트리거가 발행된 자료 버전과 딸린 시계열의 `UPDATE`·`DELETE`를 모두 거부한다 (FR-017) | 해소되지 않는다. 이것이 `--commit`을 기본 동작이 아니라 명시 인자로 둔 이유다 |
| 2 | 개발 DB에 001 마이그레이션이 적용되지 않았다 | `dataset_version`·`daily_price` 표가 없다. backend를 띄우면 Flyway가 적용한다 |
| 3 | `dataset_version.map_version_id`가 외래 키라 `sector_etf_map_version` 행이 먼저 있어야 한다 | **해소되었다.** T005(`ingest_sector_map.py`)가 `dev`에 병합되었다 |

즉 **남은 것은 2번뿐이다.**

### 실제로 적재하는 순서

```bash
# 1) 마이그레이션 적용. backend를 띄우면 Flyway가 돌린다
docker compose -f docker-compose.dev.yml up -d postgres
cd backend && ./gradlew bootRun          # 또는 컨테이너로 띄운다

# 2) 대응표 발행 (T005). map_version_id를 만드는 단계다
#    이 진입점에는 --commit이 없다. 기본이 적재이고 --dry-run이 검증만 하는 쪽이다
cd data
PATH="$PWD/.venv/Scripts:$PATH" python scripts/ingest_sector_map.py \
  --sample --map-version SEM-SAMPLE-001 --dry-run     # 먼저 검증만 한다
PATH="$PWD/.venv/Scripts:$PATH" python scripts/ingest_sector_map.py \
  --sample --map-version SEM-SAMPLE-001               # 실제 발행

# 발행된 대응표 버전의 id를 확인해 다음 단계의 --map-version-id에 넣는다
docker exec thorp-postgres psql -U thorp -d thorp -c \
  "SELECT id, version, is_sample FROM sector_etf_map_version ORDER BY id"

# 3) 수집. 먼저 --commit 없이 돌려 요약을 확인한다
PATH="$PWD/.venv/Scripts:$PATH" python scripts/ingest_krx.py \
  --map-version-id 1 --instruments 091160 091170 \
  --start 2026-09-01 --until 2026-09-10 \
  --listing listing.json

# 4) 요약이 맞으면 --commit을 붙인다
PATH="$PWD/.venv/Scripts:$PATH" python scripts/ingest_krx.py ... --commit

# 5) 상장 전 행이 없는지 확인한다 (SC-003)
PATH="$PWD/.venv/Scripts:$PATH" python scripts/verify_listing_boundary.py \
  --dataset KRX_DAILY_20260910
```

`--listing`은 상장 구간 JSON이다. 상장일을 원천에서 받을 수 없어 밖에서 받는다(4.5절).

```json
[{"instrumentId": "091160.XKRX", "listedOn": "2006-06-27", "delistedOn": null}]
```

**호출 수에 유의한다.** 조회 단위가 날짜라 2010년부터 16년치를 받으면 거래일이 약 4,000일이고
**ETF·지수 각 4,000번, 합쳐 약 8,000번**을 부른다. 종목을 몇 개 고르든 호출 수는 같다
(하루를 부르면 그날 상장된 ETF 전부가 함께 오기 때문이다). 한도에 걸리면 거기까지
부분 갱신으로 발행되고 마감일이 실제 수집한 마지막 거래일로 내려간다.

---

## 4. 원천의 성질: 문서만 보고 추측하면 틀린다

2026-09-11에 실제 응답을 받아 확인한 것이다. 이 여섯 가지가 설계를 결정했다. 스펙 문서에는
어느 것도 적혀 있지 않았다.

### 4.1 조회 단위가 종목이 아니라 날짜다

`basDd` 하루를 부르면 그날 상장된 ETF 전부가 온다(2026-09-10 기준 1,168행). 한 종목의 긴
이력을 얻으려면 거래일 수만큼 호출해야 하고, 여러 종목을 함께 모으는 편이 호출을 아낀다.

### 4.2 휴장일에도 행이 온다

일요일(2026-09-06)에도 HTTP 200과 1,167행이 오고 `BAS_DD`는 요청한 날짜를 그대로 돌려준다.
가격 필드만 빈 문자열이다. **행 수만 보고 거래일을 판정하면 휴장일에 행을 만든다.**

반면 지수 엔드포인트는 휴장일에 **빈 배열**을 준다. 거래일 달력을 `daily_index`가 맡는
근거가 문서의 약속이 아니라 이 실측이다.

### 4.3 체결이 없던 날은 시가·고가·저가가 `"0"`이다

종가 자리에는 거래소 기준가가 온다(475270 종목의 2026-09-08·09-10에서 확인). 확정된 규칙은
그날 OHLC 네 값을 모두 **직전 종가**로 채우는 것이므로 이 기준가를 버린다.

**버리는 값의 규모가 작지 않다.** 2026-08-03~09-10에 체결 없는 날 221건 가운데 기준가와
직전 종가가 **다른 사례가 180건(81%)**이었다. 결정을 되돌리자는 뜻이 아니라, T015가 그
규모를 알고 판단해야 한다는 뜻이다.

### 4.4 자료는 2010-01-04까지 거슬러 올라간다

그 앞은 이 원천으로 얻을 수 없다.

### 4.5 종목기본정보 서비스에 활용 승인이 없다

`sto/stk_isu_base_info`가 `{"respCode": "401", "respMsg": "Unauthorized API Call"}`을
돌려준다. **그래서 상장일·상장폐지일을 원천에서 받을 수 없고**, `ListingWindow`로 대응표에서
받는다. 승인을 받으면 이 경로를 원천에서 채울 수 있다.

### 4.6 종목코드의 26%가 6자리 숫자가 아니다

2026-09-10 기준 값이 있는 ETF 1,168종목 가운데 **304종목(26%)**의 코드가 `0184E0`처럼 숫자와
영문이 섞인 6글자다. 2025년부터 나타나 빠르게 늘고 있다.

| 2024-01 | 2025-01 | 2026-01 | 2026-09-10 |
|---|---|---|---|
| 0종목 | 6종목 | 178종목 | 304종목 |

마이그레이션의 `ck_daily_price_instrument_id_format`과 `engine_input.py`가 모두
`{6자리}.XKRX`만 허용하므로 **이 종목들은 담을 수 없다.** 이 묶음은 마이그레이션을 고치지
않고 수집이 멈추지도 조용히 버리지도 않게 모아 보고만 한다(`KrxCollection.unsupported_codes`).
형식을 넓힐지는 저장 스키마를 소유한 T003의 판단이다. 섹터 후보로 보는 18종목은 모두 6자리
숫자라 현재 범위는 막히지 않는다.

---

## 5. 다시 논의하지 않는 결정

이미 확정된 것들이다. 바꾸려면 spec과 계약 문서를 먼저 고쳐야 한다.

| 결정 | 근거 |
|---|---|
| 열 이름은 001 기준이다: `date`·`dataset_id`·`adjusted_close`·`price_carried_forward` | tasks T002 (2026-09-11 확정) |
| 거래정지일에도 `daily_price` 행을 만들고 `trading_suspended = true`로 표시한다. OHLC 네 값은 직전 종가라 서로 같고 `volume`·`value`는 0이며 `price_carried_forward`도 true다 | contracts/market-dataset.md 「거래정지 처리」 |
| **판정 기준은 `trading_suspended`이며 `volume = 0`이 아니다.** 정지가 아니면서 거래량이 0인 날은 호가가 있어 체결이 가능하다 | 같은 문서 |
| 진짜 결측에는 행을 만들지 않는다 | 004 계약 「결측은 행 부재」 |
| 자료 부족 구간의 `reason`은 네 가지뿐이다: `AMBIGUOUS_PRICE`·`MISSING_CORPORATE_ACTION`·`RECONCILIATION_UNKNOWN`·`INDEX_HISTORY_SHORT` | FR-006 |
| **`SUSPENDED_TOO_LONG`은 PD-19·PD-20 확정 전까지 쓰지 않는다** | FR-006. `gaps.SUSPENSION_RULE_CONFIRMED`가 그 스위치이며 켜기 전에는 `validate_gap_rows`가 거부한다 |
| 거래일 달력은 `daily_index`의 날짜 집합이다. 별도 표를 만들지 않는다 | contracts/market-dataset.md ※1 |
| `corporate_action`에 `ex_date`와 `pay_date`를 모두 담는다 | 004 계약. 락일은 조정 계수에, 지급일은 현금 반영에 쓰는 다른 날이다 |
| 적재 방향은 `raw → processed → PostgreSQL` 한 방향뿐이다. DB에서 읽어 파일로 되돌리는 경로를 만들지 않는다 | research.md 결정 8 |
| 운용보수를 다시 차감하지 않는다. 이미 가격에 반영되어 있다 | FR-004 |

### 소급 조정의 성질: 가장 조심할 곳

```
분할일  factor = 1 / 분할비율
분배일  factor = (직전 종가 − 주당 분배금) / 직전 종가
누적계수(t) = Π{ factor(e) : e.ex_date > t }
조정가(t)   = 원시 종가(t) × 누적계수(t)
```

**절대 수준은 나중에 생긴 분배금에 따라 바뀌고, 불변인 것은 두 시점의 비율뿐이다.** 뒤에 생긴
계수가 분자와 분모에 똑같이 들어가 상쇄되기 때문이다. 이 성질이 깨지면 "그때는 알 수 없었던
정보"로 계산한 결과가 조용히 만들어진다(원칙 V 위반). **숫자는 그럴듯하게 나오므로 테스트가
없으면 드러나지 않는다.**

`data/tests/test_corporate_actions.py`의 `TestRatioInvariance`가 이것을 고정한다. 고칠 때
다음을 유의한다.

- 계수가 **유한소수**인 경우에만 값 동치로 단언할 수 있다. `Decimal` 나눗셈이 정확한 몫을
  올림하므로, 분자와 분모에 같은 값을 곱한 비율은 한 자리도 달라지지 않는다
- 계수가 무한소수이면 작업 정밀도(`WORKING_PRECISION` = 50자리)에서 비롯한 오차가 남으므로
  `1e-40`으로 단언한다
- **처음에는 이 구분 없이 값 동치로만 단언했고 우연히 통과하고 있었다.** 고른 분배금 금액이
  마침 유한소수 계수를 만들었기 때문이다. 금액을 바꾸면 깨진다

---

## 6. 이어서 할 일

이 묶음에 담기지 않은 것들이다. 키가 없어 미뤄 두었던 것이라 이제 착수할 수 있다.

### T014 대표 ETF 확정 (PD-01)

수집해 둔 관찰이 입력이 된다. 연 1회 조회로 잡은 최초 관측 연도다(분기 이하 정밀도 없음).

| 최초 관측 | 종목 |
|---|---|
| 2010 | 091160 KODEX 반도체, 091230 TIGER 반도체, 091170 KODEX 은행, 102970 KODEX 증권, 091180 KODEX 자동차, 117460 KODEX 에너지화학, 117680 KODEX 철강, 117700 KODEX 건설 |
| 2012 | 143860 TIGER 헬스케어, 139230 TIGER 200 중공업 |
| 2013 | 157490 TIGER 소프트웨어 |
| 2016 | 227540 TIGER 200 헬스케어, 228790 TIGER 화장품, 227550 TIGER 200 산업재 |
| 2018 | 266360 KODEX K콘텐츠 |
| 2019 | 305720 KODEX 2차전지산업 |
| 2021 | 364980 TIGER 2차전지TOP10 |
| 2024 | 466920 SOL 조선TOP3플러스 |

**공통 평가 기간은 가장 늦게 상장된 후보가 정한다.** 2차전지·조선을 섹터에 넣으면 공통
시작일이 2019년(또는 2024년) 이후로 밀린다. 2010년부터 있는 여덟 섹터만 고르면 16년치를 쓴다.

종목명이 바뀐 사례가 둘 있다(364980·266360). 대응표에 상장 당시 이름을 쓸지 현재 이름을 쓸지
정해야 한다.

### T015 거래정지 규칙 확정 (PD-19·PD-20)

`suspension_period` 기록이 입력이다. 아래는 2026-08-03~09-10(거래일 28일), 관측된 ETF
1,171종목 전부를 대상으로 미리 집계한 것이다.

- 구간 132건, 해당 47종목(4.0%)
- 중앙값 1거래일, 평균 1.96거래일, 최댓값 28거래일
- 길이별: 1일 94건 · 2일 19건 · 3일 7건 · 4일 3건 · 5일 3건 · 6일 1건 · 8일 1건 · 9일 1건 ·
  12일 1건 · 16일 1건 · 28일 1건
- 관측 창 전체가 체결 없는 종목 1개: 265690 ACE 러시아MSCI(합성)
- 긴 구간은 채권형·합성형에 몰려 있다
- **섹터 후보 18종목은 이 창에서 체결 없는 날이 0건이다**

긴 정지는 업종형 주식 ETF의 문제가 아니라 채권형·합성형의 문제로 보인다. 후보를 업종형으로
한정하면 방식 B·C·D 가운데 무엇을 고르든 잃는 기간이 없을 수 있다. **다만 관측 창이 28거래일
뿐이므로 더 긴 창으로 다시 집계해야 한다.**

확정하면 `gaps.SUSPENSION_RULE_CONFIRMED`를 켜고 `SUSPENDED_TOO_LONG` 기록을 시작한다.

### T016 검증 기록

quickstart 시나리오 1~7을 순서대로 실행하고 결과를 남긴다. 실제 적재(3절)가 선행 조건이다.

**quickstart의 명령줄이 현재 인자와 어긋난다.** 시나리오 1의 `ingest_krx.py --sample`은
만들지 않았다(예시 대응표 발행은 T005의 `ingest_sector_map.py`가 소유하므로 여기에 두면
발행 경로가 두 곳이 되고 `is_sample`·`map_version`의 주인이 갈린다). 시나리오 2의
`--map-version`도 실제로는 `--map-version-id`이고 `verify_listing_boundary.py`에는
`--dataset`이 필요하다. T016에서 함께 고친다.

---

## 7. 알려진 것: 다시 조사하지 않는다

| 항목 | 상태 |
|---|---|
| `instrument_profile` 표가 어느 마이그레이션에도 없다 | T013 전에 정할 사항 |
| 대응표 네 표에 불변 트리거가 없다 | 알려진 상태 |
| `quickstart.md` 117행이 표 이름을 복수형 `corporate_actions`로 적는다 | 실제 표는 단수 |
| `is_sample`과 `source`의 정합을 아무도 강제하지 않는다 | 알려진 상태 |
| `repository.DATA_GAP_REASONS`에 `SUSPENDED_TOO_LONG`이 남아 있다 | 확정 전 금지를 지키는 곳은 `gaps.validate_gap_rows` 하나다. 파이프라인은 모두 그곳을 거치지만 `load_data_gap`을 직접 부르면 우회된다 |
| `reconciliation_record`에 표본 구분자를 담을 열이 없다 | 지급일이 같은 분배금이 둘인 종목에서 같은 `target_date`·`field_name`에 행이 둘 생긴다. 값으로는 구분되지만 표만 봐서는 어느 기업행위의 것인지 모른다 |
| **두 진입점의 적재 기본값이 반대다** | `ingest_krx.py`·`ingest_index.py`·`reconcile.py`는 `--commit`을 붙여야 적재한다. T005의 `ingest_sector_map.py`는 기본이 적재이고 `--dry-run`이 검증만 한다. 발행본을 되돌릴 수 없으므로 어느 쪽으로든 맞추는 편이 낫다 |

---

## 8. 작업 관례

- 검증 명령은 2.2절이 전부다. **`ruff format`을 반드시 적용한다.** 타입 힌트는 필수다
- 주석과 docstring은 한국어로 쓰고 **무엇을 하는지보다 왜 그렇게 했는지**를 적는다
- 정규식은 `\A...\Z`로 닫는다. 파이썬의 `$`는 끝에 붙은 줄바꿈 앞에서도 맞아
  `"091160.XKRX\n"`이 통과한다
- `DataFrame.iterrows()`를 쓰지 않는다. 수치 행을 float64로 올려 정수 열을 실수로 만든다.
  `to_dict("records")`나 `itertuples()`를 쓴다
- 숫자 검사는 numpy 스칼라를 받아야 한다. `numpy.int64`는 `int`의 하위형이 아니다
- 접속 문자열은 반드시 `repository.database_url_from_env()`를 거친다. `.env`의
  `postgresql://`을 그대로 `create_engine`에 넘기면 SQLAlchemy가 psycopg2로 해석하는데 이
  파트에 설치된 것은 psycopg 3뿐이라 `ModuleNotFoundError`다
- **검사 장치가 조용히 꺼지지 않게 한다.** "파일이 없으면 테스트를 건너뛴다" 같은 구조를
  쓰지 않는다. 못 찾으면 실패해야 한다
- DB를 건드리기 전에 행을 모두 검사한다. 절반만 들어가면 발행본은 삭제도 거부되어 되돌릴 수
  없다. 진입점은 `MarketDataRepository.rehearsal()`로 인메모리 예행을 먼저 거친다
- 윈도 콘솔의 기본 부호화(cp949)가 엠대시(U+2014)를 담지 못한다. **출력 문자열에 쓰지
  않는다.** 주석·docstring은 출력되지 않으므로 무관하다
- MR 전에 AI 코드리뷰를 수행하고 발견된 문제를 해결한 뒤 병합한다 (루트 `CLAUDE.md`)
