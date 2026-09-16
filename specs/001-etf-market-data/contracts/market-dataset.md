# Contract: 서비스 저장소 자료 읽기

**근거**: [spec.md](../spec.md) FR-004·FR-005·FR-006·FR-013, [data-model.md](../data-model.md), [research.md](../research.md) 결정 1·2·4·8

Worker가 백테스트를 실행할 때 001이 적재한 자료를 읽는 계약이다. 기존 `MarketDataProvider` Protocol(`worker/src/worker/market_data.py:166`)에 구현을 하나 더 얹는다. 읽는 곳은 PostgreSQL이다. `data/processed/`의 Parquet은 파이프라인 산출물이며 Worker 컨테이너에 공유 볼륨이 없어 읽을 수 없다(research.md 결정 8).

## 구현 대상

```python
class RepositoryDataProvider:
    def load(self, request: EngineInput) -> MarketDataset: ...
```

**이 스케치는 채택되지 않았다.** `RepositoryDataProvider`라는 클래스는 끝내 만들지 않았고, 001이 적재한 표를 읽는 일은 spec 004가 만든 `PostgresMarketDataProvider`(`worker/src/worker/market_data.py:574`)가 맡는다. 아래의 필드 표와 읽기 규칙은 그 구현에 그대로 적용된다.

`MarketDataset`의 필드를 다음과 같이 채운다.

| 필드 | 값 |
|---|---|
| `prices` | 요청 구간의 일봉. `validate_prices`를 통과한 것 |
| `instrument_id` | `request.universe.instrument_ids[0]` |
| `ticker` | 6글자 단축코드(영숫자 대문자). `.KS` 접미사를 붙이지 않는다 — yfinance 표기였다 |
| `source` | `dataset_version.source`를 그대로 쓴다. `KRX_OPEN_API`, 예시 자료이면 `FIXED_TEST_DATA` |
| `adjustment` | `SPLIT_ADJUSTED` |
| `metadata` | 아래 표 |

### metadata

| 키 | 뜻 | 근거 |
|---|---|---|
| `datasetId` | `request.market_data.dataset_id`와 일치해야 한다 | FR-013, research.md 결정 4 |
| `dataDeadline` | 자료 마감 시각 | FR-014 |
| `notRealMarketData` | 예시 자료일 때만 true | FR-002, research.md 결정 5 |
| `carriedForwardDates` | 직전 종가를 쓴 날짜 목록 | FR-006, clarify 2 |
| `dataGaps` | 요청 구간과 겹치는 자료 부족 구간 | FR-006 |
| `mapVersion` | 대응표 버전 | FR-003 |

## 읽기 규칙

**요청한 `datasetId`가 적재된 자료의 `dataset_id`와 다르면 `MarketDataError`를 낸다.** 다른 버전으로 조용히 대체하지 않는다. `market_data.py:16`의 주석이 이미 "never silently substitute another symbol"로 같은 원칙을 담고 있다.

**요청 구간이 상장일보다 앞서면 그 앞 구간을 빈 값으로 채우지 않고 오류를 낸다.** SC-003이 "상장 전 날짜에 가격 값이 존재하는 사례가 0건"을 요구한다.

**워밍업 자료(신호를 계산하려면 있어야 하는 과거 구간)는 요청 구간보다 앞에서 읽되, 앞이 모자라면 있는 만큼만 읽는다.** 2026-09-15에 확정한 규칙이다.

얼마나 앞에서 읽을지는 달력 일수가 아니라 **거래일 수**로 센다(`warmup_trading_days`). 달력 일수로 잡으면 설·추석이 긴 해에 같은 일수가 더 적은 거래일을 뜻해 워밍업이 모자란다. 공급자는 그만큼 앞선 거래일부터 읽고, 앞에 그만큼의 자료가 없으면 거절하지 않고 가진 가장 이른 거래일부터 읽는다(`market_data._warmup_start_from_days`, PostgreSQL 구현은 `PostgresMarketDataProvider._warmup_start`). **읽기에서 거절하면 안 되는 이유**는 백엔드가 [evaluation-window.md](./evaluation-window.md)의 `firstEvaluable` 규칙대로 시작일을 밀어 이미 접수시킨 요청을 워커가 되돌리게 되어 두 계층이 서로 모순되기 때문이다. 자료 맨 앞이나 상장일 근처를 요청하면 워밍업을 채울 자료가 없는 것이 정상이고, 그것은 거절할 이유가 아니라 평가 시작일을 미룰 이유다.

**워밍업이 모자란 채로 계산이 시작되는 것을 막는 곳은 한 군데뿐이다.** 성과를 집계하기 시작하는 날은 `max(앵커, calendar[warmup])`이며, 이 판정은 `engine_runner.evaluation_start()`가 단독으로 맡는다(`worker/src/worker/engine_runner.py:91`). 앵커는 자료를 읽기 전에 요청만 보고 정하고(`warmup_anchor()`, 같은 파일 69행), `FRESH_CASH_FROM`의 `scenarioParams.startDate` → `run.effectiveStart` → `run.start` 순으로 고른다. 앵커 앞에 워밍업이 넉넉하면 `calendar[warmup]`이 앵커와 같아져 이 바닥은 아무 일도 하지 않고, 앞이 모자라면 **모자란 만큼만** 평가 시작일이 뒤로 밀린다 — `DATA_INSUFFICIENT`가 아니다. 다만 워밍업 구간까지 읽어 온 달력 전체로도 워밍업을 채우지 못하면 "앞이 짧다"가 아니라 "이 자료로는 이 전략을 계산할 수 없다"는 뜻이므로 `MarketDataError`로 멈춘다.

**같은 `datasetId`로 두 번 읽으면 같은 값이 나와야 한다.** SC-004의 근거다. 발행된 자료 버전을 수정하지 않으므로(FR-017) 파일이 바뀌지 않는다.

## 체결과 신호의 계열 구분

FR-004가 정한 배분이다. 이 구분을 어기면 원칙 V 위반이다.

| 용도 | 컬럼 |
|---|---|
| 가상 체결 가격 | `open`·`high`·`low`·`close` (원시) |
| 신호 계산, 성과 계산 | `adjusted_close` |

`adjusted_close`의 **절대 수준을 판단 기준으로 쓰지 않는다.** 소급 조정 계열이라 나중에 생긴 분배금에 따라 수준이 바뀐다. 두 시점의 비율은 계수가 상쇄되어 안전하다(research.md 결정 2). 현재 다섯 전략은 모두 비율 기반이다.

**`adjusted_close`에 운용보수를 다시 차감하지 않는다.** 이미 가격에 반영되어 있다(FR-004의 중복 반영 금지).

## 거래정지 처리

PD-19·PD-20이 확정되기 전의 임시 동작이다.

### 저장소에 무엇이 들어 있는가

거래정지일에도 `daily_price` 행이 **있다**. 004 계약이 "결측은 행 부재로 표현하고 워커는 결측 거래일을 만나면 `DATA_INSUFFICIENT`로 실패한다"고 정했으므로, 정지일에 행을 만들지 않으면 워커가 001의 자료를 읽자마자 실패한다. **정지는 결측이 아니라 별도 상태**이며 다음과 같이 담는다(2026-09-11 결정, data-model.md `daily_price` 「거래정지일 표현」).

| 컬럼 | 정지일의 값 |
|---|---|
| `trading_suspended` | `true` |
| `open` `high` `low` `close` | 네 값 모두 직전 거래일의 `close` |
| `volume` `value` | 0 |
| `price_carried_forward` | `true` |
| `adjusted_close` | 직전 거래일의 `adjusted_close`(같은 조정 계수 적용) |

OHLC 네 값을 모두 채우는 것은 `validate_prices`(`worker/src/worker/market_data.py:33`)가 OHLC 양수와 `high >= max(open, low, close)`·`low <= min(open, high, close)`를 요구하기 때문이다. `close`만 채우면 검증을 통과하지 못한다. `volume = 0`은 "음수가 아닌 정수" 조건을 통과한다.

`trading_suspended`와 `price_carried_forward`는 **둘 다 둔다.** 전자는 원인(거래정지), 후자는 처리(직전 종가를 썼음)로 뜻이 다르다. 지금 범위에서는 항상 함께 true지만, 거래정지가 아닌 사유로 직전 종가를 쓰는 경우가 생기면 구분할 수 있어야 한다.

**진짜 결측(자료를 얻지 못한 거래일)에는 행을 만들지 않는다.** 이 경우 워커는 004 계약대로 `DATA_INSUFFICIENT`로 실패한다. 채울 값이 없는 상장 첫날의 정지도 행을 만들지 않고 `data_gap`(`AMBIGUOUS_PRICE`)으로 기록한다.

### 워커가 정지 행을 어떻게 다루는가

```
trading_suspended = true 인 거래일 (정지 길이와 무관하게 모두)
  → 행이 있으므로 DATA_INSUFFICIENT 로 실패하지 않는다
  → 평가(보유 자산 가치 계산)에만 쓴다. 값은 직전 종가다
  → 그날 매매 주문을 만들지 않는다. 리밸런싱일이 정지일과 겹치면 주문을 다음 거래 가능일로 미룬다
  → 신호 계산에는 들어간다. 직전 종가가 반복되므로 그날의 수익률은 0이다
  → metadata.carriedForwardDates 에 날짜를 남긴다
  → 정지 구간을 suspension_periods 에 기록한다 (시작일·종료일·거래일 수)
  → metadata.provisionalSuspensionHandling = true 로 임시 처리 중임을 알린다
```

`volume = 0`도 거래 불가 판정의 근거가 되지만(004 계약), **판정의 기준은 `trading_suspended`다.** 정지가 아니면서 거래량이 0인 날이 있을 수 있고, 그날은 호가가 있어 체결이 가능하다. 두 조건을 같은 것으로 다루면 정지가 아닌 날의 주문을 잘못 막는다.

**확정 전에는 `SUSPENDED_TOO_LONG`을 `data_gaps`에 쓰지 않는다.** 자료를 버리지 않아야 다른 기능의 개발이 막히지 않고, 기록한 정지 구간이 곧 PD-19·PD-20 결정의 입력이 된다.

어떤 방식이 확정되더라도 **정지 구간을 빼고 앞뒤를 이어 붙이는 처리는 하지 않는다.** 정지 직전과 직후가 하루 차이처럼 계산되어 없던 수익률이 생긴다(원칙 VI).

`provisionalSuspensionHandling`이 true인 결과에는 정지 일수를 표시한다. 원칙 VI가 "자료 부족을 낮은 수익과 구분해 기록하고 표시한다"를 요구하므로, 긴 정지 동안 평가액이 굳은 채 계산된 결과를 그냥 내보내지 않는다.

## 004 불변 조건 대조표

[004 market-data-input.md](../../004-backtest-execution/contracts/market-data-input.md)의 「불변 조건」 여섯 항목을 001 데이터 모형이 만족하는지 항목별로 대조한다. 이 정합 작업에는 자동 검증이 없으므로 **이 표가 검증을 대신한다.** 어느 쪽 문서를 고치면 이 표를 다시 맞춘다.

| # | 004의 불변 조건 | 001의 근거 | 판정 |
|---|---|---|---|
| 1 | 같은 `(instrument_id, date, dataset_id)`에 행이 하나다. 날짜는 KRX 거래일만 있고 오름차순 조회가 가능하다 | `daily_price` PK `(dataset_id, instrument_id, date)`. 검증 규칙 "날짜는 유일하고 오름차순". 조회 인덱스 `(instrument_id, dataset_id, date)` | **만족.** 다만 "KRX 거래일만"은 아래 ※1 참고 |
| 2 | 상장일 이전 행이 없다. 합성·백필 값이 없다(원칙 V) | 검증 규칙 `date < listed_on`인 행 불가(SC-003), `delisted_on` 이후 행 불가. 상장 전 합성 ETF 이력은 spec Out of Scope이자 specs/README 「범위 밖」 | **만족** |
| 3 | `dataset_id`가 바뀌어도 이전 버전 행은 유지된다 | `dataset_id`가 PK 선두. FR-017 "발행된 버전은 수정하지 않는다". T003이 발행된 `dataset_version`과 딸린 시계열의 UPDATE·DELETE를 거부한다 | **만족** |
| 4 | 조정 종가는 해당 `dataset_id` 기준으로 전체 기간에 일관되게 계산되어 있다. 원시 가격은 조정하지 않는다 | `daily_price`가 원시 `open/high/low/close`와 `adjusted_close`·`adjustment_factor`를 같은 행에 나눠 담는다(research.md 결정 2). 이 문서 「체결과 신호의 계열 구분」이 용도를 고정한다 | **만족** |
| 5 | 결측은 행 부재로 표현하고 0이나 보간값을 넣지 않는다(원칙 VI). 워커는 결측 거래일을 만나면 `DATA_INSUFFICIENT`로 실패한다 | 검증 규칙 "결측·비유한 값을 거부한다. 채우지 않는다"(FR-005). 처리 불명확 구간은 값을 만들지 않고 `data_gap`에 기록한다(FR-006) | **만족.** 정지일은 결측이 아니므로 이 항목의 대상이 아니다(6번) |
| 6 | 거래정지일은 결측이 아니다. 행이 있고 `trading_suspended = true`, OHLC 네 값이 직전 종가, `volume = 0`이다. 워커는 평가에만 쓰고 주문을 만들지 않는다 | data-model.md `daily_price` 「거래정지일 표현」과 이 문서 「거래정지 처리」. FR-006과 clarify 2의 임시 동작을 그대로 담는다 | **만족** (2026-09-11에 이 항목을 추가하며 양쪽을 맞췄다) |

※1 **거래일 달력은 `daily_index`의 날짜 집합이 맡는다.** "행이 있는 날짜가 모두 KRX 거래일"은 적재 쪽이 지키면 되지만, 워커가 "거래일인데 행이 없다"(=결측)와 "휴일이라 행이 없다"(=정상)를 구분하려면 거래일 달력이 있어야 한다. 구분하지 못하면 결측을 휴일로 착각해 건너뛰고, 자료가 빠진 채 계산된 결과가 정상처럼 나온다.

이 달력의 출처는 [evaluation-window.md](./evaluation-window.md)가 이미 정해 두었다. 공통 평가 기간 산식의 입력 `tradingCalendar`가 "확보한 지수 자료의 날짜 집합"이며, 같은 집합을 결측 판정에도 쓴다. **개별 ETF는 거래정지될 수 있지만 KOSPI 지수는 장이 열리면 언제나 산출되므로**, 지수 자료에 그 날짜의 행이 있다는 사실이 곧 그날 장이 열렸다는 증거가 된다. 별도 달력 표를 두지 않는 이유가 이것이다. 지수도 `dataset_id`에 묶여 있어 같은 자료 버전으로 다시 조회하면 같은 달력이 나온다(SC-004).

`daily_price`에 행이 있는 날짜의 합집합으로 달력을 대신하지 않는다. 모든 종목이 같은 날 정지·결측이면 그 날을 휴일로 오인하기 때문이다.

남는 한계는 지수 자료가 대표 ETF의 상장 이후 기간을 다 채우지 못하는 구간이다. 그 구간에는 달력도 없다. FR-009가 지수 확보 기간에 그 전체를 포함하도록 요구하므로 원칙적으로는 생기지 않지만, spec Edge Case가 이미 UI-02로 등록해 둔 항목이다.

## 기존 provider 정리

`MarketDataProvider` Protocol(`worker/src/worker/market_data.py:166`)과 `validate_prices`(`worker/src/worker/market_data.py:19`)는 그대로 둔다. 001의 자료를 읽는 공급자도 같은 Protocol을 구현하고 같은 검증을 통과한다.

| provider | 처리 |
|---|---|
| `PostgresMarketDataProvider` | 001이 적재한 표를 읽는 운영 공급자(`worker/src/worker/market_data.py:574`). spec 004가 이미 만들었으므로 001은 새 클래스를 만들지 않고 이 구현의 SQL이 실제 스키마와 맞는지 확인한다 |
| `CsvMarketDataProvider` | 예시 자료 경로로 남긴다 (FR-002). 파일 이름과 열이 PostgreSQL 표와 같아서 공급자만 바꾸면 위쪽 코드는 그대로다(`worker/src/worker/market_data.py:412`) |
| `YFinanceDataProvider` | **삭제했다.** spec 004 T006이 단일 종목 DEMO 엔진과 함께 지웠고 저장소에 남아 있지 않다 |

**기본값을 다른 공급자로 바꾼 것이 아니라 기본값 자체를 없앤 것이 이 정리의 핵심이다.** 기본값이 남아 있는 한 "주입을 잊은 경로"가 계속 생기고, 그 경로가 어느 원천을 읽었는지는 결과에 드러나지 않는다. 그래서 엔진은 공급자를 생성자의 **필수 인자**로 받고 기본값을 두지 않는다(`PortfolioBacktestEngine.__init__`, `worker/src/worker/engine_runner.py:302`). 공급자를 넘기지 않으면 엔진이 아예 만들어지지 않으므로, 이용 조건 밖 원천으로 조용히 넘어갈 자리가 사라진다.

> **2026-09-11에 spec 004가 이 표의 세 줄을 모두 처리했고, 2026-09-15에 001 쪽에서 실제 코드와 대조해 확인을 마쳤다.**
> `YFinanceDataProvider`는 다른 경로로 옮긴 것이 아니라 **삭제되었다** — 004 T006이 단일 종목 DEMO
> 엔진과 함께 지웠고, `worker/scripts/`에는 `make_three_etf_fixture.py` 하나만 남아 있다.
> 001이 적재한 표를 읽는 일은 004가 추가한 `PostgresMarketDataProvider`가 맡으며, 그 구현은
> [004 contracts/market-data-input.md](../../004-backtest-execution/contracts/market-data-input.md)의
> 표를 읽는다.
