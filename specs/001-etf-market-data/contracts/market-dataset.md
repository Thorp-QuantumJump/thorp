# Contract: 서비스 저장소 자료 읽기

**근거**: [spec.md](../spec.md) FR-004·FR-005·FR-006·FR-013, [data-model.md](../data-model.md), [research.md](../research.md) 결정 1·2·4·8

Worker가 백테스트를 실행할 때 001이 적재한 자료를 읽는 계약이다. 기존 `DataProvider` Protocol(`worker/src/worker/market_data.py:29`)에 구현을 하나 더 얹는다. 읽는 곳은 PostgreSQL이다. `data/processed/`의 Parquet은 파이프라인 산출물이며 Worker 컨테이너에 공유 볼륨이 없어 읽을 수 없다(research.md 결정 8).

## 구현 대상

```python
class RepositoryDataProvider:
    def load(self, request: EngineInput) -> MarketDataset: ...
```

`MarketDataset`의 필드를 다음과 같이 채운다.

| 필드 | 값 |
|---|---|
| `prices` | 요청 구간의 일봉. `validate_prices`를 통과한 것 |
| `instrument_id` | `request.universe.instrument_ids[0]` |
| `ticker` | 6자리 코드. `.KS` 접미사를 붙이지 않는다 — yfinance 표기였다 |
| `source` | `KRX_OPEN_API`, 예시 자료이면 `FIXED_TEST_DATA` |
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

**워밍업 자료는 요청 구간보다 앞에서 읽는다.** 현재 `YFinanceDataProvider`가 `request.run.start - timedelta(days=max(365, lookback * 3))`으로 여유를 잡는 방식(`market_data.py:86`)을 거래일 기준으로 바꾼다. 달력 일수로 잡으면 연휴가 긴 해에 워밍업이 모자란다.

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

```
체결 가격이 없는 거래일 (길이와 무관하게 모두)
  → 평가에는 직전 종가를 쓴다
  → 그날 매매 주문을 만들지 않는다
  → metadata.carriedForwardDates 에 날짜를 남긴다
  → 정지 구간을 suspension_periods 에 기록한다 (시작일·종료일·거래일 수)
  → metadata.provisionalSuspensionHandling = true 로 임시 처리 중임을 알린다
```

**확정 전에는 `SUSPENDED_TOO_LONG`을 `data_gaps`에 쓰지 않는다.** 자료를 버리지 않아야 다른 기능의 개발이 막히지 않고, 기록한 정지 구간이 곧 PD-19·PD-20 결정의 입력이 된다.

어떤 방식이 확정되더라도 **정지 구간을 빼고 앞뒤를 이어 붙이는 처리는 하지 않는다.** 정지 직전과 직후가 하루 차이처럼 계산되어 없던 수익률이 생긴다(원칙 VI).

`provisionalSuspensionHandling`이 true인 결과에는 정지 일수를 표시한다. 원칙 VI가 "자료 부족을 낮은 수익과 구분해 기록하고 표시한다"를 요구하므로, 긴 정지 동안 평가액이 굳은 채 계산된 결과를 그냥 내보내지 않는다.

## 기존 provider 정리

`DataProvider` Protocol과 `validate_prices`는 그대로 두어 `RepositoryDataProvider`가 같은 검증을 통과하게 한다.

| provider | 처리 |
|---|---|
| `RepositoryDataProvider` | 신규. `engine_runner.py:68`의 **기본값이 된다** |
| `CsvDataProvider` | 예시 자료 경로로 남긴다 (FR-002) |
| `YFinanceDataProvider` | 클래스는 남기되 기본값 자리에서 빼고, 실제 Job 처리 경로에서 쓰지 않는 임시 수단임을 주석에 남긴다 (research.md 결정 1) |

기본값을 바꾸는 것이 이 정리의 핵심이다. 현재 `engine_runner.py:68`이 `data_provider or YFinanceDataProvider()`이므로, provider를 주입하지 않는 경로는 이용 조건 밖 원천으로 조용히 넘어간다.
