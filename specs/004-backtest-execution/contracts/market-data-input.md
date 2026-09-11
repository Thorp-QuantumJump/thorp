# Contract: 시장 자료 입력 (004 → 001에 대한 요구)

004 워커의 `PostgresMarketDataProvider`(R-09)가 읽는 최소 계약이다. 001 plan이 저장 형식(PostgreSQL 테이블·Parquet)을 정할 때 이 열과 의미를 만족해야 한다.

**열 이름은 001 기준으로 통일했다**(2026-09-11, 001 T002). 이 계약이 처음부터 "열 이름은 제안이며 의미가 우선한다. 001이 다른 이름을 쓰면 provider 매핑만 바꾼다"고 허용했고, 004는 아직 구현 전이라 자료를 실제로 소유·적재하는 001의 이름을 따르는 편이 provider 매핑 계층을 아예 없앤다. 바뀐 이름은 `trade_date → date`, `data_version → dataset_id`, `adj_close → adjusted_close`, `halted → trading_suspended`이며, **의미는 바뀌지 않았다**. 브로커 메시지 계약의 `marketData.datasetId`는 그대로 두고 값 규칙만 `KRX_DAILY_{YYYYMMDD}`로 정한다(001 research.md 결정 4). 세부 정의는 [001 data-model.md](../../001-etf-market-data/data-model.md)가 원본이다.

## 필요한 자료

| 자료 | 001의 표 | 열 | 용도 |
|---|---|---|---|
| 일별 원시 가격 | `daily_price` | `instrument_id, date, open, high, low, close, volume, trading_suspended BOOL, dataset_id` | 체결(다음 거래일 원시 시가), 거래 불가 판정(`trading_suspended` 또는 volume 0) |
| 일별 조정 종가 | `daily_price` (같은 행) | `instrument_id, date, adjusted_close, dataset_id` | 신호·성과(분할·분배 일관 반영) |
| 분배금 이벤트 | `corporate_action` (`event_type = 'DISTRIBUTION'`) | `instrument_id, ex_date, pay_date, amount, dataset_id` | 지급일 현금 반영(한 번만) |
| 분할 이벤트 | `corporate_action` (`event_type = 'SPLIT'`) | `instrument_id, ex_date, amount, dataset_id` | 원시 수량 조정. `amount`가 비율이고 `pay_date`는 NULL이다 |
| 상품 정보 | `representative_etf` | `instrument_id, listed_on, delisted_on NULL, name, benchmark_index` | 가용 기간, 상장 전 자료 금지 |
| 섹터-ETF 대응표 | `sector_etf_map_version` + `sector_etf_map_entry` | `version, effective_from, effective_to NULL` / `map_version_id, sector_id, etf_id` | 요청 검증·키 계산 |
| 자료 버전 | `dataset_version` | `dataset_id, data_deadline, source, failure_reason` | 실행 키·결과 기록 |

001은 원시 가격과 조정 종가를 한 행(`daily_price`)에 담는다. 이 계약의 두 줄은 용도 구분이며 표 두 개를 요구하는 것이 아니다.

대응표는 001이 버전 표와 항목 표로 나눠 두었고, 항목이 가리키는 것은 `instrument_id`가 아니라 `representative_etf.id`다. provider는 `sector_etf_map_entry → representative_etf`를 조인해 `instrument_id`를 얻는다. 이 계약이 요구한 항목별 `effective_from`·`effective_to`는 버전 표에 있다 — 유효 기간이 버전 단위이기 때문이며, 의미는 같다.

자료 버전의 `cutoff_at`은 001의 `data_deadline`(데이터 마감 시각), `note`는 `failure_reason`에 대응한다. `source`는 이 계약의 요구로 001 `dataset_version`에 추가했다.

## 불변 조건 (004가 의존)

- 같은 `(instrument_id, date, dataset_id)`에 행이 하나다. 날짜는 KRX 거래일만 있고 오름차순 조회가 가능하다.
- 상장일 이전 행이 없다. 합성·백필 값이 없다(원칙 V).
- `dataset_id`가 바뀌어도 이전 버전 행은 유지된다(이전 실행의 재현, spec Edge Case).
- 조정 종가는 해당 `dataset_id` 기준으로 전체 기간에 일관되게 계산되어 있다. 원시 가격은 조정하지 않는다.
- 결측은 행 부재로 표현하고 0이나 보간값을 넣지 않는다(원칙 VI). 워커는 결측 거래일을 만나면 `DATA_INSUFFICIENT`로 실패한다.
- **거래정지일은 결측이 아니다.** 정지일에도 행이 있고 `trading_suspended = true`, OHLC 네 값이 모두 직전 종가, `volume = 0`이다. 워커는 이 행을 평가에만 쓰고 그날 주문을 만들지 않으며 `DATA_INSUFFICIENT`로 실패하지 않는다(001 FR-006, [001 contracts/market-dataset.md](../../001-etf-market-data/contracts/market-dataset.md) 「거래정지 처리」).

`trading_suspended`와 001의 `price_carried_forward`는 둘 다 있고 뜻이 다르다. 전자는 원인(거래정지), 후자는 처리(직전 종가를 썼음)다. 이번 범위에서는 항상 함께 true지만, 거래정지가 아닌 사유로 직전 종가를 쓰는 경우가 생기면 구분이 필요하다.

## 조회 패턴

실행마다 종목 ≤ 50개, 기간 ≤ 16년(≈4,000거래일)을 한 번에 읽는다. `(instrument_id, dataset_id, date)` 인덱스가 있으면 충분하다. `daily_price`의 PK는 `(dataset_id, instrument_id, date)`이므로 이 조회 인덱스를 따로 둔다. 워커는 읽기 전용 접속만 쓴다.

## 001 미완성 시

같은 열을 가진 CSV(`worker/examples/`)로 테스트하고, 결과 `versions.data`에 `FIXTURE-…`를 기록한다. 픽스처로 만든 결과는 사용자에게 제공하지 않는다.
