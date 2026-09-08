# ADR-0003: Nautilus 모멘텀 엔진의 경계와 체결 모델

- 상태: 구현됨 (2026-09-08, 입력 계약 통합 완료. 운영 Consumer 연동 제외)
- 대상: `worker/`
- 관련 이슈: Jira 101

## 배경

MVP는 단일 KRX 종목의 일봉 모멘텀 전략을 실행하고, Backend/Consumer가 사용할 안정적인
`EngineInput → EngineOutput` 계약을 필요로 한다. 원본 설계에는 장기 목표인 다종목, 벤치마크,
상장폐지 처리, 메시징과 API 응답까지 함께 들어 있어 이번 구현 범위와 실제 지원 수준을 구분해야
했다.

또한 “t일 종가로 판단하고 t+1 거래일 시가에 체결”한다는 정책은 단순 일봉 계산으로 구현하면
미래 시가 참조나 같은 봉 체결이 섞이기 쉽다. 데이터 공급자 오류를 숨기지 않으면서도 향후
공급자와 메시지 브로커를 교체할 수 있는 경계가 필요했다.

## 결정

### 1. 공개 엔진 경계

새 공개 진입점은 다음 하나다.

```python
NautilusMomentumEngine(data_provider=None).run(engine_input, on_progress) -> EngineOutput
```

데이터 공급자는 실행 인자가 아니라 엔진 인스턴스의 성질이므로 생성자로 주입하고, 같은
인스턴스를 여러 실행에서 재사용한다. 이 진입점은 엔진 실행과 결과 계산까지만 책임진다.
브로커 소비/ACK, 상태 저장, 결과 DB 저장,
SSE 발행은 호출하는 Consumer의 책임으로 둔다. 기존 `BacktestJob`, `BacktestResult`,
`run_backtest`, `worker.main`은 호환을 위해 남긴 레거시 스캐폴딩이며 새 경로와 연결하지 않는다.

**이유:** 연산을 메시징·저장 기술에서 분리해야 고정 fixture로 결정론적 테스트가 가능하고,
미결정인 RabbitMQ/Kafka 선택도 엔진 내부로 새지 않는다.

### 2. 이벤트 기반 next-open 체결

각 거래일에 09:00 `QuoteTick`과 15:30 `Bar`를 생성한다. Bar close에서 신호와 주문 수량을
결정하고, 주문은 다음 QuoteTick에서만 제출한다. `bar_execution`은 끈다. Quote의 ask/bid에
각각 +5/-5bps를 반영하며 Nautilus의 실제 주문·체결 이벤트를 통과시킨다.

**이유:** 판단 시점과 체결 시점을 서로 다른 이벤트로 표현하면 같은 봉 체결과 미래 시가 참조를
구조적으로 방지할 수 있다. 매수·매도 비용 방향도 호가로 명시된다.

### 3. 신호, 실행, 성과 계산 분리

- `MomentumStrategy`는 종가로 0/1 신호만 계산한다.
- `MomentumExecution`은 Nautilus 콜백, 주문, 체결, 일별 원장을 담당한다.
- `calculate_performance`는 완성된 일별 원장으로 서비스 지표를 계산한다.

**이유:** 전략 수식은 엔진 없이 테스트·교체할 수 있어야 하고, Nautilus 기본 분석 결과와 서비스가
정한 지표 정의가 다르므로 지표 공식을 애플리케이션이 소유해야 한다.

### 4. 교체 가능한 데이터 공급자와 fail-closed 검증

`DataProvider.load(EngineInput) -> MarketDataset`을 공급자 경계로 둔다. 운영 데모는 yfinance,
회귀 테스트는 CSV fixture를 사용한다. NaN, 중복/비정렬 날짜, 잘못된 OHLC 범위, 종목 identity
불일치는 보정하거나 대체하지 않고 실패시킨다.

**이유:** 조용한 데이터 보정이나 종목 fallback은 성공처럼 보이는 재현 불가능한 성과를 만든다.
공급자 교체 가능성은 유지하되 데이터 의미는 metadata와 hash로 남긴다.

### 5. 서비스 원장과 native 원장 대조

Nautilus native 계정·주문 외에 서비스 계약용 현금, 주문, 거래, 일별 스냅샷을 유지한다. 종료 시
native 체결 수와 현금 잔액을 서비스 원장과 대조하고 다르면 실행을 실패시킨다.

**이유:** 기준 시가와 실제 체결가, 슬리피지 비용, OPEN 거래, 선택적 positions 등 필요한 출력은
native 리포트의 단순 노출로 만들 수 없다. 이중 원장의 위험은 실행 후 reconciliation으로
탐지한다.

### 6. 오류와 콜백 의미

- 입력 계약 오류는 Pydantic validation 예외다.
- 데이터/엔진 오류는 성과가 없는 `FAILED EngineOutput`이다.
- 진행 콜백 오류는 `CallbackError`로 호출자에게 전파한다.

**이유:** Consumer가 입력 거절, 실행 실패, 외부 발행 실패에 서로 다른 재시도·ACK 정책을 적용할
수 있어야 한다. 특히 발행 실패를 완료 결과로 숨기지 않는다.

### 7. DEMO 시장 모델

단일 XKRX 주식, 정수 수량, 현금 계정, 공매도/레버리지/부분 체결 없음, 수수료·세금 0,
고정 슬리피지 5bps, 무제한 유동성으로 제한한다. 조정 가격 소수점을 보존하기 위해 내부에서
`KRW_SIM`을 사용한다. 실제 KRX 호가 단위, 거래량, 정지 원인, 상장폐지는 모델링하지 않는다.

**이유:** 이번 목표는 운영 브로커 시뮬레이터가 아니라 next-open 규칙과 서비스 계약을 실행
가능하게 만드는 일봉 DEMO다. 지원하지 않는 현실성을 암묵적으로 흉내 내지 않고 범위 밖으로
명시한다.

## 결과

### 장점

- 고정 CSV만으로 실제 Nautilus 이벤트 경로를 빠르고 재현 가능하게 검증한다.
- 전략, 데이터 공급자, 메시지 브로커를 서로 독립적으로 바꿀 수 있다.
- 신호 시점과 체결 시점이 명시되어 look-ahead bias를 방지한다.
- 데이터와 체결의 이상을 성공 결과로 숨기지 않는다.

### 감수하는 비용

- native 상태와 서비스 원장을 함께 관리해 코드가 늘고 동기화 위험이 있다.
- 일봉 하나를 Quote와 Bar 두 이벤트로 변환하는 어댑터가 필요하다.
- 엄격한 시세 검증 때문에 yfinance 일부 기간은 실행이 실패할 수 있다.
- 현재 엔진이 독립되어 있어 운영 서비스가 end-to-end로 동작하는 것은 아니다.

## 메시지 계약과의 정합 상태 (2026-09-08 기준)

이 엔진과 공용 메시지 계약(`worker/src/worker/contracts/`, `docs/specs/backtest-messaging/`)의
현재 차이를 기록한다.

### 통합이 끝난 범위

결과 모델, 직렬화, 진행 콜백, 엔진 경계는 계약과 일치한다. 결과는
`worker.contracts.engine_output.EngineOutput` 하나만 쓰고, 실패 사유는 최상위가 아니라
`run.error {code, message}`에 담는다. 메시지 직렬화는 `ContractModel.to_json()`
(camelCase + 값 없는 키 생략)이며, 계약에 없는 주문 원장(`orders`)과 재현성 `metadata`는
`worker/src/worker/artifacts.py`의 `RunArtifacts`(로컬 진단 산출물)로 옮겨 메시지에서 뺐다.
`trades[]`의 `entryReferencePrice`/`exitReferencePrice`도 뺐다 — 기준가는 `grossPnl` 계산에만
쓰는 엔진 내부 값이고, 계산 방식과 `realizedPnl == grossPnl - commission - slippageCost`
항등식은 그대로다. 진행 콜백은 `contracts.engine.ProgressCallback` 모양
(`on_progress(ProgressPoint, completed_trading_days, total_trading_days)`, 거래일 1개 단위)이고,
20거래일 묶음 발행은 실행 Consumer 책임이다.

### 해소됨: 입력 모델도 계약을 직접 쓴다 (2026-09-08)

**입력 모델 통합을 마쳤다.** 엔진은 이제 `worker.contracts.engine_input.EngineInput`을 직접
받고, 과도기 입력 모델 `worker/src/worker/engine_contracts.py`는 **삭제했다.**

| 경로 | 계약 고정값 | 엔진 지원값 | 결과 |
|---|---|---|---|
| `marketData.adjustment` | `SPLIT_ADJUSTED` | `SPLIT_ADJUSTED` | **해소 (아래 A-보정방식)**: 공급자를 `auto_adjust=False`로 바꿔 적용 완료 |
| `execution.delisting` | `CAUSE_BASED` | 분류 미지원 | **해소 (아래 D-상장폐지)**: 요청을 받고 `DELISTING_CLASSIFICATION_UNSUPPORTED` 경고를 실행당 1건 남긴다 (`engine_runner._run_level_warnings`) |
| `execution.commission` | `model=ZERO` | 수수료·세금 0 | **해소 ([ADR-0004](0004-backtest-commission-model.md))**: 계약에 `ZERO`를 추가하고 §4.1 고정값을 `ZERO`로 바꿨다. `BROKER` 요청은 엔진이 `INVALID_REQUEST`로 거절한다 |

`Adjustment`·`DelistingPolicy` enum은 그대로 하나씩만 허용하고, `CommissionModel`은
`BROKER`·`ZERO` 둘이 된다.

**대신 생긴 것: 엔진 지원 범위 검증(`worker/src/worker/engine_support.py`).** 삭제한 과도기
모델은 `Literal` 타입으로 미지원 값을 자동 거절해 왔다. 계약 모델은 그 값들을 전부 통과시키므로
(계약 `engine_input.py` docstring: "엔진이 실제로 지원하는 값의 범위는 여기서 다루지 않는다 —
엔진 쪽 책임이다") 같은 거절을 엔진 쪽에 새로 만들었다. 종목 2개 이상, `leverage != 1`,
`shortSelling`, `currency != KRW`, `commission.model != ZERO`, `slippage.bps != 5`,
`partialFill`, `benchmark` 지정이 대상이고, 예외가 아니라
`EngineOutput(run.status=FAILED, run.error.code=INVALID_REQUEST)`로 반환한다(§6 "오류와 콜백
의미"의 입력 계약 오류와 구분된다 — 계약 자체를 위반한 메시지는 여전히 pydantic 예외다).

**이 검증을 `contracts/`에 두지 않는다.** `contracts/`는 백엔드와 공유하는 계약이고, 계약
픽스처 `docs/specs/backtest-messaging/examples/engine-input.json`(MA_CROSSOVER·2종목·benchmark
있음)은 구조 검증용이라 계약 검증을 계속 통과해야 한다(백엔드 `BacktestMessageRoundTripTest`와
워커 `tests/test_contracts.py`가 같은 파일을 읽는다). 엔진 실행 입력은
`worker/examples/momentum-demo-input.json`이다.

**전략 지원 범위는 하드코딩하지 않고 레지스트리로 둔다.** `worker/strategies/__init__.py`의
`STRATEGIES`가 `strategyId` → (지원 `version` 집합, `typed_parameters`로 전략을 만드는 builder)를
선언한다. 계약 쪽 `contracts/strategy_params/STRATEGY_PARAMETERS`와 대칭이며, 미지원 전략·버전
거절은 "레지스트리에 없음"으로 자동 처리된다. v0.1.2에서 알고리즘이 20개 이상 늘어날 때
거절 코드를 전략마다 고치지 않으려는 조치다. 준비 기간(`required_history`)도 레지스트리가
선언한다 — `Strategy` ABC는 `generate_signals`만 요구한다(위 3번).

### A-보정방식. 공급자를 `auto_adjust=False`로 바꿔 `SPLIT_ADJUSTED`에 맞췄다

**결정·적용 (2026-09-08).** 계약을 바꾸지 않고 공급자를 계약에 맞췄다.

이전 공급자는 yfinance `auto_adjust=True`라서 분할뿐 아니라 배당까지 반영했다(설치된 0.2.65의
`yfinance.utils.auto_adjust`가 `Adj Close / Close` 비율을 Open/High/Low/Close에 곱한다 —
소스에서 확인). 즉 `TOTAL_RETURN_ADJUSTED`였고 계약 고정값과 어긋났다. `auto_adjust=False`로
두면 Yahoo 원본 시계열, 즉 액면분할만 반영된 값이 되어 `SPLIT_ADJUSTED`의 의미와 맞는다.
(`auto_adjust=False` 응답에는 `Adj Close` 컬럼이 추가로 들어오지만 `validate_prices`가
OHLCV만 골라 쓰므로 검증에 영향이 없다.)

- **이점 1 — 데이터 원본 교체와 무관하다.** pykrx가 제공하는 수정주가도 분할 계열 조정이므로
  나중에 KRX 데이터로 갈아탈 때 계약을 다시 건드릴 필요가 없다.
- **이점 2 — 재현성이 개선된다.** 배당 보정은 현재 시점 기준으로 과거를 역산하므로 새 배당이
  발생할 때마다 과거 가격이 다시 계산된다. 초기자본이 고정돼 있어 가격이 미세하게 달라지면
  매수 가능 주식 수(정수)가 바뀌고 최종 자산이 달라진다. 실제로 같은 날 몇 분 사이에 라이브
  실행 결과가 달라지는 것이 관측됐다(아래 `worker/README.md` 기록). 분할은 배당보다 훨씬
  드물어 값이 안정적이다.
- **대가 — 배당 재투자 수익이 빠진다.** 라이브 데모의 수익률·최종 자산 숫자가 낮아지고, 매수
  가능 수량이 달라져 체결·거절 건수도 바뀐다. **팀이 승인한 변경이다.** 그 대신 이전에 기록해
  둔 라이브 검증 숫자는 더 이상 유효하지 않다. 고정 데이터(`worker/examples/momentum_fixture.csv`)
  기반 회귀 기준값은 정적 파일이라 보정 방식과 무관하며 변하지 않는다.

### D-상장폐지. `CAUSE_BASED` 요청을 받고, 분류 미지원을 경고로 남긴다

**결정 (2026-09-08).** 계약 변경 없이 엔진 동작만 정한다.

`CAUSE_BASED`는 상장폐지를 사유별로 다르게 처리하라는 정책이다 — 합병·자발적 폐지는 인수가나
정리매매가로 청산하고, 부도·감사의견 거절 등은 손실로 처리하는 식이다. 이 구분이 필요한 이유는
**생존 편향**이다. 폐지된 종목을 데이터에서 빼면 살아남은 종목만 남아 전략 성과가 실제보다
좋아 보인다.

현재 임시 공급자(yfinance)는 폐지 사유를 제공하지 않는다. 데이터가 없는 세션은 그냥 건너뛴다.
그래서 엔진은 사유 기반 처리를 구현할 수 없다.

- **결정**: `delisting=CAUSE_BASED` 요청을 거부하지 않고 받는다. 대신 사유 분류를 지원하지
  않는다는 사실을 `diagnostics.warnings`에 남겨 결과를 읽는 쪽이 알 수 있게 한다.
- **이유**: 요청 기간에 해당 종목의 상장폐지가 없으면 이 정책은 결과 숫자에 영향을 주지 않는다.
  현재 데모는 상장폐지되지 않은 단일 종목(`000660.XKRX`)이므로 지금 범위에서 숫자가 달라지지
  않는다. 미지원을 이유로 실행을 거부하면 아무 영향도 없는 상황에서 데모가 막힌다.
  반대로 경고 없이 조용히 통과시키면 나중에 다종목으로 넓혔을 때 생존 편향을 눈치채지 못한다.

**구현했다 (2026-09-08).** 정한 대로 두 가지가 이렇게 되었다.

1. **경고 코드**: `DELISTING_CLASSIFICATION_UNSUPPORTED`. `warnings[].code`는 개방 집합이다
   (계약 §4.3, 기존 예 `TRADING_HALTED`·`INSUFFICIENT_CASH`).
2. **경고의 거래일**: 계약 `Warning.date`는 필수인데 이 경고는 특정 거래일이 아니라 실행 전체에
   해당한다. **평가 구간의 첫 거래일**을 쓴다. 엔진이 현재 만드는 경고 네 종류
   (`INSUFFICIENT_CASH`·`ORDER_REJECTED`·`INSUFFICIENT_HISTORY`·`NO_NEXT_TRADING_DAY`)는 모두
   실제 거래일을 갖기 때문에 실행 단위 경고는 이번이 처음이다. 실행마다 1건만 남긴다.

구현 위치는 `worker/src/worker/engine_runner.py`의 `_run_level_warnings`이며, 실행 단위 경고를
거래일 단위 경고(`MomentumExecution.warnings`) 앞에 붙인다. `DelistingPolicy` enum은 계약
변경 없이 `CAUSE_BASED` 하나로 그대로 두었다. 실패 결과에는 붙이지 않는다 — 평가 구간이 없으면
경고에 넣을 거래일도 없다.

**이 결정이 유효하지 않게 되는 시점**: 유니버스를 다종목으로 넓히거나, 상장폐지된 종목이 포함될
수 있는 기간을 다루게 되면 경고로는 부족하다. 그때는 공급자가 폐지 사유를 제공해야 하고
(KRX 데이터 파이프라인의 책임), 엔진이 사유별 청산을 구현해야 한다.

**`execution.commission`** — 이것만은 경고로 덮을 수 없다. 수수료·세금은 모든 수익률을 바꾸고,
특히 회전율이 높은 전략을 과대평가하게 만든다. **[ADR-0004](0004-backtest-commission-model.md)에서
결정되었고 2026-09-08에 반영했다** — 계약의 `CommissionModel`에 `ZERO`를 정식 모델로 추가하고
데모 단계 고정값을 `model=ZERO`로 바꿨다(픽스처 → 백엔드 enum → 워커 계약 순서, 같은 MR).
키움 요율표는 전략 비교 단계에서 별도 티켓으로 구현한다. 그때까지 `model=BROKER` 요청은
엔진이 `INVALID_REQUEST`로 거절한다 — 수수료 0으로 계산한 숫자를 요율표 결과로 저장하지 않는다.

### 함께 정리한 부수 차이 (2026-09-08 완료)

판단이 필요한 항목은 아니었고 입력 모델을 옮기면서 같이 처리했다.

- `marketData.datasetId`: 계약 고정값은 `KRX_DAILY_V1`, 실제 공급자는 yfinance라 데모 입력은
  `YFINANCE_DAILY`를 쓴다. 계약에서 이 필드는 enum이 아니라 자유 문자열이라 역직렬화를 막지
  않으므로 **엔진은 이 값을 검증하지 않는다.** 실제 출처는 산출물 `metadata.dataSource`에
  기록된다. KRX 데이터 연결은 별도 티켓이다.
- 계약 `StrategySpec.parameters`는 `dict[str, Any]` 봉투이므로 엔진 호출부를
  `request.strategy.typed_parameters`로 바꿨다(`nautilus_engine.py`, `market_data.py`).
  전략과 준비 기간 조립은 `strategies/__init__.py` 레지스트리가 맡는다.
- 계약 `EngineInput`은 6개 섹션이 모두 필수이므로 부분 dict를 넘길 수 없다. `demo.py`는
  `build_engine_input`으로, 테스트는 `tests/conftest.py`의 `ENGINE_INPUT_BODY`·
  `engine_input_body()`로 전체 필드를 채운다.
- 계약이 `T | None`으로 둔 두 필드의 의미를 엔진 쪽에서 정했다.
  `positionSizing.maxPositionPct` 생략 = 제한 없음(= 1),
  `run.randomSeed` 생략 = 고정 기본값 `42`를 쓰고 실제 사용값을 `metadata.randomSeed`에 남긴다
  (`FillModel`에 `None`을 넘기면 실행마다 시드가 달라질 수 있어 재현성이 깨진다).

## 다루지 않는 것

- RabbitMQ/Kafka 선택과 Consumer 구현
- DB/SSE 저장의 원자성, 멱등성, 재시도와 ACK 순서
- 원시 시장 데이터 장기 보관과 dataset version 정책
- 다종목, 벤치마크, 최적화, 실제 KRX 미시구조
- 기업행동·거래정지·상장폐지의 원인별 처리

필드별 세부 규약과 구현에서 확정한 예외는
[`docs/specs/101-nautilus-momentum.md`](../specs/101-nautilus-momentum.md)를 따른다.
