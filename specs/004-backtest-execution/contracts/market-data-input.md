# Contract: 시장 자료 입력 (004 → 001에 대한 요구)

004 워커의 `PostgresMarketDataProvider`(R-09)가 읽는 최소 계약이다. 001 plan이 저장 형식(PostgreSQL 테이블·Parquet)을 정할 때 이 열과 의미를 만족해야 한다. 열 이름은 제안이며 의미가 우선한다. 001이 다른 이름을 쓰면 provider 매핑만 바꾼다.

## 필요한 자료

| 자료 | 열 | 용도 |
|---|---|---|
| 일별 원시 가격 | `instrument_id, trade_date, open, high, low, close, volume, halted BOOL, data_version` | 체결(다음 거래일 원시 시가), 거래 불가 판정(`halted` 또는 volume 0) |
| 일별 조정 종가 | `instrument_id, trade_date, adj_close, data_version` | 신호·성과(분할·분배 일관 반영) |
| 분배금 이벤트 | `instrument_id, ex_date, pay_date, amount_per_share, data_version` | 지급일 현금 반영(한 번만) |
| 분할 이벤트 | `instrument_id, effective_date, ratio, data_version` | 원시 수량 조정 |
| 상품 정보 | `instrument_id, listing_date, delisting_date NULL, name, benchmark_index` | 가용 기간, 상장 전 자료 금지 |
| 섹터-ETF 대응표 | `mapping_version, sector_id, instrument_id, effective_from, effective_to NULL` | 요청 검증·키 계산 |
| 자료 버전 | `data_version, cutoff_at, source, note` | 실행 키·결과 기록 |

## 불변 조건 (004가 의존)

- 같은 `(instrument_id, trade_date, data_version)`에 행이 하나다. 날짜는 KRX 거래일만 있고 오름차순 조회가 가능하다.
- 상장일 이전 행이 없다. 합성·백필 값이 없다(원칙 V).
- `data_version`이 바뀌어도 이전 버전 행은 유지된다(이전 실행의 재현, spec Edge Case).
- 조정 종가는 해당 `data_version` 기준으로 전체 기간에 일관되게 계산되어 있다. 원시 가격은 조정하지 않는다.
- 결측은 행 부재로 표현하고 0이나 보간값을 넣지 않는다(원칙 VI). 워커는 결측 거래일을 만나면 `DATA_INSUFFICIENT`로 실패한다.

## 조회 패턴

실행마다 종목 ≤ 50개, 기간 ≤ 16년(≈4,000거래일)을 한 번에 읽는다. `(instrument_id, data_version, trade_date)` 인덱스가 있으면 충분하다. 워커는 읽기 전용 접속만 쓴다.

## 001 미완성 시

같은 열을 가진 CSV(`worker/examples/`)로 테스트하고, 결과 `versions.data`에 `FIXTURE-…`를 기록한다. 픽스처로 만든 결과는 사용자에게 제공하지 않는다.
