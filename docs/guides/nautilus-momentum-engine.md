# Nautilus 모멘텀 엔진 이해하기

이 문서는 Python과 NautilusTrader에 익숙하지 않은 Java 개발자가 Jira 101 구현을 빠르게
이해하고 안전하게 변경하기 위한 입문서다. 세부 JSON 규약은
[`docs/specs/101-nautilus-momentum.md`](../specs/101-nautilus-momentum.md), 설계 이유는
[`ADR-0003`](../adr/0003-nautilus-momentum-engine-boundary.md)를 기준으로 한다.

## 먼저 알아야 할 현재 상태

**완료된 것은 독립 실행 가능한 백테스트 엔진이고, 전체 서비스 연동은 아니다.**

| 영역 | 현재 상태 |
|---|---|
| EngineInput 검증 | 구현됨 |
| Yahoo Finance/CSV 데이터 로딩과 검증 | 구현됨 |
| Nautilus 이벤트 루프에서 모멘텀 주문·체결 | 구현됨 |
| 일별 평가, 거래 원장, 성과지표, 진행 콜백 | 구현됨 |
| 오프라인 회귀 테스트와 CLI 데모 | 구현됨 |
| Backend Job → 브로커 → 새 엔진 호출 | 미구현 |
| 결과 DB 저장·SSE 발행·메시지 ACK | 미구현 |
| 브로커 RabbitMQ/Kafka 최종 선택 | 미결정 |

따라서 `python -m worker.demo`는 실행되지만 `python -m worker.main`은 이 엔진을 호출하지 않는다.
`worker.main`과 `run_backtest`는 예전 Kafka 스캐폴딩이며, 후자는 아직
`NotImplementedError`를 발생시킨다.

## 1. 한 문장으로 보는 동작

과거 일봉을 날짜순 이벤트로 바꾼 뒤, **오늘 종가로 모멘텀 신호를 계산하고 만든 시장가 주문을
다음 거래일 시가 호가에서 Nautilus가 체결**하게 하며, 매일 종가 기준 자산과 최종 성과를
서비스 계약 형태로 반환한다.

## 2. Java 관점의 파일 지도

| Python 파일 | Java에서 비슷한 역할 | 책임 |
|---|---|---|
| `engine.py` | Facade | 외부 공개 진입점 `run_engine` |
| `engine_contracts.py` | request record + Bean Validation + Jackson 설정 | 입력 계약과 검증 |
| `contracts/engine_output.py` | response record | 결과 계약(브로커 메시지와 공용) |
| `engine_runner.py` | Application Service | `NautilusMomentumEngine`: 데이터 로드 → 실행 → 지표 → 결과 조립 |
| `market_data.py` | Port와 Adapter | `DataProvider` 계약, yfinance/CSV 구현, OHLCV 검증 |
| `strategies/momentum.py` | 순수 Domain Service | 종가 배열을 0/1 목표 포지션 신호로 변환 |
| `nautilus_engine.py` | 이벤트 핸들러 + Infrastructure Gateway | Nautilus 설정, 이벤트 수신, 주문·체결·일별 원장 |
| `performance.py` | 순수 계산기 | 서비스 규약에 맞는 CAGR/MDD/Sharpe 계산 |
| `demo.py` | CLI Adapter | JSON/CLI 입력으로 독립 실행 |
| `test_momentum_engine.py` | 통합·회귀 테스트 | 실제 Nautilus 이벤트 루프와 불변식 검증 |

추천 읽기 순서는 `engine.py` → `engine_runner.py` → `market_data.py` →
`strategies/momentum.py` → `nautilus_engine.py` → `performance.py` → 테스트다.
`engine_contracts.py`(입력)와 `contracts/engine_output.py`(결과)는 처음부터 전부 읽기보다,
흐름에서 만나는 모델을 찾아보는 편이 쉽다.

## 3. 전체 호출 흐름

```text
EngineInput(JSON/dict/Pydantic 객체)
        │
        ▼
engine.run_engine                         공개 Facade
        │
        ▼
engine_runner.NautilusMomentumEngine.run  실행 유스케이스
        │
        ├─ 1. EngineInput.model_validate  계약/날짜/고정 정책 검증
        ├─ 2. DataProvider.load           yfinance 또는 CSV 로드
        ├─ 3. validate_prices             OHLCV 무결성 검증
        ├─ 4. execute_nautilus             이벤트 루프 실행
        │       ├─ 09:00 QuoteTick        전날 주문 제출·체결
        │       └─ 15:30 Bar              신호 생성·평가·진행 콜백
        ├─ 5. calculate_performance       일별 원장에서 성과 계산
        └─ 6. EngineOutput                COMPLETED 또는 FAILED
```

### 하루의 시간 순서

예를 들어 월요일 종가에서 매수 신호가 생겼다면 다음 순서다.

1. 월요일 15:30 `on_bar`: 월요일 종가까지의 정보만 보고 주문 객체와 수량을 만든다.
2. 화요일 09:00 `on_quote_tick`: 화요일 시가에 매수 5bps를 더한 ask 가격으로 주문을 제출한다.
3. Nautilus `on_order_filled`: 실제 체결 이벤트를 받아 현금·수량·거래 원장을 갱신한다.
4. 화요일 15:30 `on_bar`: 화요일 종가로 자산을 평가하고 진행 스냅샷을 보낸다.

이 순서를 지키는 이유는 월요일 판단에 아직 알 수 없는 화요일 시가를 사용하지 않기 위해서다.
백테스트에서 미래 정보를 사용하는 오류를 **look-ahead bias**라고 한다.

## 4. 최소 Python 번역표

이 코드에서 자주 만나는 문법만 알면 된다.

| Python | Java식 해석 |
|---|---|
| `EngineInput | dict` | `EngineInput` 또는 `Map<String, Object>` |
| `X | None`, 기본값 `None` | nullable `X` |
| `list[X]`, `dict[K, V]` | `List<X>`, `Map<K, V>` |
| `Decimal` | `BigDecimal`; 금액 계산에 `float/double`을 쓰지 않음 |
| `@dataclass` | 데이터 중심 record/POJO |
| `Protocol` | 구현 선언이 없어도 메서드 모양이 맞으면 되는 interface |
| `BaseModel` | DTO + 역직렬화 + Bean Validation |
| `raise` / `except` / `finally` | `throw` / `catch` / `finally` |
| `with pytest.raises(...)` | `assertThrows(...)` |
| `prices.loc[...]` | pandas 테이블에서 조건에 맞는 행 선택 |

Python 내부 필드는 `snake_case`, 외부 JSON은 `camelCase`다. `Contract`의 Pydantic 설정이
Jackson의 naming strategy처럼 양쪽을 변환한다. 숫자를 JSON decimal 문자열로 내보내는 것도
계약 모델이 담당한다.

## 5. Nautilus 개념 지도

| Nautilus 개념 | 이 구현에서 하는 일 |
|---|---|
| `BacktestEngine` | 시간순 이벤트를 전략과 모의 거래소에 전달하는 런타임 |
| `Venue("XKRX")` | 주문과 현금 계정을 가진 모의 거래소 |
| `Equity` | 가격 정밀도·통화·최소 수량을 정의한 거래 대상 |
| `Bar` | 15:30 일봉 이벤트. 신호 계산과 종가 평가를 촉발 |
| `QuoteTick` | 09:00 bid/ask 이벤트. 보류 주문 제출과 체결을 촉발 |
| `NautilusStrategy` | `on_start`, `on_bar`, `on_quote_tick`, `on_order_filled` 콜백의 소유자 |
| `FillModel` | 모의 체결 동작. 이 구현에서는 추가 랜덤 슬리피지를 끔 |

Nautilus가 알아서 전략을 선택하거나 수익률을 정의해 주는 것은 아니다. 이 구현은 Nautilus의
**이벤트 순서, 주문 상태, 체결 이벤트, 계정**을 사용하고, 서비스가 요구한 일별 결과와 지표는
직접 계산한다.

## 6. 헷갈리기 쉬운 설계와 이유

### Bar와 QuoteTick을 둘 다 만드는 이유

Bar만으로 다음 날 시가 체결을 켜면 이벤트 설정에 따라 같은 봉이나 이미 지난 가격에 체결되는
모호함이 생긴다. 그래서 Bar는 “판단”, 다음 날 QuoteTick은 “제출·체결”로 역할을 분리하고
`bar_execution=False`로 막았다.

### 주문 수량을 오늘 종가로 계산하는 이유

다음 시가로 수량을 계산하면 미래 가격을 미리 본 셈이다. 오늘 종가와 5bps 비용으로 보수적으로
수량을 정하고, 다음 날 갭 상승으로 실제 비용이 현금을 넘으면 주문 전체를 거절한다. 거절 후에는
다음 종가에서 다시 판단한다.

### Nautilus 계정 외에 현금·거래 원장을 다시 들고 있는 이유

서비스 계약은 기준 시가, 슬리피지 비용, OPEN/CLOSED 거래, 선택적 포지션 스냅샷처럼 Nautilus의
기본 리포트와 다른 형태를 요구한다. 그래서 애플리케이션 원장을 별도로 만들되, 실행 종료 때
Nautilus의 체결 건수와 현금 잔액이 같은지 검사해 이중 원장의 불일치를 탐지한다.

### `KRW_SIM`을 쓰는 이유

Nautilus의 실제 KRW 정의는 소수점이 없다. 그러나 배당·분할 보정 가격과 5bps 체결가는 소수점을
가질 수 있다. 프로세스 전체의 실제 KRW 정의를 덮어쓰지 않고 계산 정밀도를 보존하려고 1:1 모의
통화 `KRW_SIM`을 내부 계정에만 사용하며, 외부 결과 통화는 KRW로 유지한다.

### 잘못된 시세를 자동 보정하지 않는 이유

NaN, 중복 날짜, 역전된 OHLC 범위를 임의로 채우면 실행은 성공해도 결과의 근거가 사라진다.
현재 정책은 잘못된 데이터를 날짜와 함께 실패시키는 것이다. 종목 자동 대체도 같은 이유로 하지
않는다.

### 진행 콜백 예외를 실패 결과로 감싸지 않는 이유

콜백은 앞으로 메시지 발행/저장 경계가 된다. 발행 실패를 `COMPLETED` 결과로 숨기면 Consumer가
ACK해 작업을 잃을 수 있다. 따라서 `CallbackError`는 호출자까지 전파되어 재시도 정책을 적용하게
한다. 일반 데이터·엔진 오류만 `FAILED EngineOutput`으로 변환한다.

## 7. 중요한 불변식

코드를 바꿀 때 아래 조건은 테스트와 함께 유지해야 한다.

- 신호는 해당 날짜 종가까지의 데이터만 사용한다.
- 체결은 신호 다음의 실제 거래일 시가 이벤트에서만 일어난다.
- 현금은 음수가 되지 않고, 부분 체결·공매도·레버리지는 없다.
- 매일 `equity == cash + positionValue`다.
- 출력 체결 수와 Nautilus native 체결 수가 같다.
- 출력 현금과 Nautilus 계정 잔액이 같다.
- 콜백이 받은 객체를 변경해도 최종 결과 원장은 바뀌지 않는다.
- 계산할 수 없는 값과 OPEN 거래의 미실현 필드는 임의의 0이 아니라 없는 값이며, 메시지 JSON에는
  `null`이 아니라 키 자체가 빠진다(`ContractModel.to_json()`).
- 실패한 실행에는 가짜 성과나 체결 결과를 넣지 않는다.

## 8. 변경 목적별 시작점

| 하고 싶은 변경 | 먼저 볼 곳 | 함께 확인할 테스트 |
|---|---|---|
| 입력 필드 변경 | `engine_contracts.py` | JSON round-trip 테스트 |
| 출력 필드 변경 | `contracts/engine_output.py` | 계약 픽스처 테스트, JSON round-trip 테스트 |
| 전략 계산 변경 | `strategies/momentum.py` | 미래 데이터 불변 테스트, lookback 경계 테스트 |
| 체결 시점/슬리피지 변경 | `nautilus_engine.py` | next-open, gap reject, 양방향 가격 테스트 |
| 데이터 공급자 교체 | `market_data.py`의 `DataProvider` | 잘못된 OHLCV 거절 테스트 |
| 지표 공식 변경 | `performance.py` | 표준편차·달력 일수 테스트 |
| 메시지/DB/SSE 연동 | 새 Consumer adapter | 콜백 실패, 멱등성, ACK 순서 테스트 |

운영 Consumer를 붙일 때 `run_backtest`에 억지로 맞추기보다, 브로커 메시지를 `EngineInput`으로
변환해 `run_engine`을 호출하는 어댑터를 만드는 것이 현재 경계를 보존한다.

## 9. 남아 있는 부채와 다음 결정

| 우선순위 | 부채 | 해결 조건 |
|---|---|---|
| 높음 | 브로커가 문서상 RabbitMQ, 레거시 코드상 Kafka로 불일치 | 하나를 결정하고 설정·의존성·Consumer·문서를 한 티켓에서 정리 |
| 높음 | 새 엔진이 운영 Job 흐름과 단절 | 메시지→EngineInput 변환, 상태 전이, 결과 저장, ACK 순서를 구현하고 통합 테스트 |
| 높음 | 콜백 100%와 결과 저장 성공의 원자성 없음 | 재전달 시 중복을 허용하는 멱등 키/업서트 정책 결정 |
| 중간 | yfinance 데이터가 수정되거나 결측될 수 있음 | 원시 데이터 스냅샷과 dataset version을 보존하는 공급자 도입 |
| 중간 | native 원장과 출력 원장을 함께 유지 | 새 주문 유형마다 reconciliation 테스트 추가 또는 단일 원장 변환 구조 재검토 |
| 낮음 | 실제 KRX 틱·유동성·정지·상폐를 모델링하지 않음 | DEMO 범위를 벗어날 때 별도 시장 모델로 구현 |

저장소 루트의 추적되지 않은 `NautilusTrader 설계 ...md`는 문서 자체 메타데이터가
“취소/작성필요” 상태이고 구현 범위보다 넓다. 배경 자료로는 유용하지만 현재 코드의 기준 문서는
아니다. 필요한 내용이 확정되면 spec 또는 ADR로 옮긴 뒤 원본의 보존/삭제 여부를 팀이 결정한다.

## 10. 로컬 확인

네트워크 없이 전체 핵심 경로를 검증할 수 있다.

```bash
cd worker
uv run ruff check src tests
uv run ruff format --check src tests
uv run pytest
uv run python -m worker.demo \
  --fixture examples/momentum_fixture.csv \
  --start 2025-01-06 --end 2025-01-14 \
  --lookback-days 2 --output examples/fixture-output.json
```

테스트 fixture는 동작 확인용 가짜 시세이며 실제 성과 근거로 사용하면 안 된다.
