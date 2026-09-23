# three_etf_24m — 3종목 24개월 픽스처

> **실제 시세가 아니다.** 값은 고정 씨앗 난수로 만든 합성 자료다. 이 픽스처로 만든 결과는
> `versions.data = "FIXTURE-three_etf_24m"` 로 표시되며 사용자에게 제공하지 않는다
> (spec 004 [contracts/market-data-input.md](../../../specs/004-backtest-execution/contracts/market-data-input.md)
> 「001 미완성 시」).

001이 자료를 적재하기 전까지 워커가 다종목 경로를 시험하는 자료다. 파일 이름과 열은 001의
PostgreSQL 표와 같게 맞춰 두었다 — 001이 자료를 넣으면 `CsvMarketDataProvider` 를
`PostgresMarketDataProvider` 로 바꾸기만 하면 되고 위쪽 코드는 그대로다.

## 파일

| 파일 | 대응하는 001 표 | 행 수 |
|---|---|---|
| `prices_raw.csv` | `daily_price` (원시 OHLCV + `trading_suspended`) | 1,436 |
| `prices_adjusted.csv` | `daily_price` (같은 행의 `adjusted_close`) | 1,436 |
| `distributions.csv` | `corporate_action` (`DISTRIBUTION`) | 2 |
| `splits.csv` | `corporate_action` (`SPLIT`) | 1 |
| `instruments.csv` | `representative_etf` | 3 |
| `mapping.csv` | `sector_etf_map_version` + `sector_etf_map_entry` | 3 |

## 일부러 넣은 상황 (quickstart S8)

| 상황 | 어디 | 왜 |
|---|---|---|
| 상장일이 기간 한가운데인 종목 | `244580.XKRX`, 2023-07-03 | 공통 평가 구간이 줄어드는 경우 |
| 거래정지 1일 | `091160.XKRX`, 2024-06-11 | 결측이 아니다 — 행이 있고 `volume=0`, OHLC 전부 직전 종가 |
| 액면분할 1건 | `244580.XKRX`, 2024-03-04, 비율 2 | 원시 가격은 반토막, 조정 종가는 이어진다 |
| 분배금 2건 | `091160` 2023-04-27, `305720` 2024-04-29 | 지급일 현금 반영이 한 번만 일어나는지 |

기간은 2023-01-02 ~ 2024-12-31, 달력은 **주말만 뺀 평일**이다. KRX 공휴일은 넣지 않았다 —
공휴일 목록을 지어내는 것보다 "거래일 집합"이라는 성질만 만족시키는 편이 정확하고, 공급자도
엔진도 달력의 내용을 가정하지 않는다(달력은 자료에서 만든다).

## 다시 만들기

손으로 고치지 말 것. 수기 대조(SC-003) 기대값이 이 숫자들에 묶인다.

```bash
python worker/scripts/make_three_etf_fixture.py
```

같은 씨앗이면 같은 파일이 나온다. 상황을 바꾸려면 스크립트 상단의 `INSTRUMENTS`·
`DISTRIBUTIONS`·`SPLITS`·`SUSPENSIONS` 를 고치고 다시 돌린다.
