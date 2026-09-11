# CLAUDE.md — worker (백테스트 워커)

Python 3.11 + pandas + NautilusTrader + pika. 엔진의 공개 실행 경로는
`worker.engine_runner.NautilusMomentumEngine.run(engine_input, on_progress) -> EngineOutput`이고,
운영 엔트리포인트는 `python -m worker.main`(= RabbitMQ 실행 Consumer)이다. Kafka 스캐폴딩
(`worker.main`의 Kafka 루프, `BacktestJob`, `run_backtest`)은 pika 교체와 함께 삭제했다.
워커는 **PostgreSQL에 쓰지 않고 SSE도 발행하지 않는다** — 결과 저장·SSE는 백엔드 결과
Consumer 몫이다(계약 §1). 자세한 현재 상태는
`docs/guides/nautilus-momentum-engine.md`와 `docs/adr/0003-nautilus-momentum-engine-boundary.md`를
먼저 확인한다.

## 검증 명령 (작업 후 반드시 실행)

```bash
ruff check src tests scripts && ruff format --check src tests scripts && pytest
```

## 구조 규칙

- `strategies/` 전략은 `base.Strategy` 상속, `generate_signals(prices) -> Series`만 구현
  (전략은 신호만 출력, 체결·자산 계산은 `nautilus_engine.py`, 지표 산출은 `performance.py`
  담당 — 이 분리를 깨지 말 것)
- `strategies/__init__.py` `STRATEGIES` 는 **엔진이 실행할 수 있는 전략의 레지스트리**다.
  `contracts/strategy_params/STRATEGY_PARAMETERS`("parameters 가 어떤 모양인가")와 대칭으로
  "이 엔진이 그 전략·버전을 실제로 실행할 수 있는가"를 선언한다. 미등록 `strategyId`·미지원
  `version` 거절은 여기 등록 여부로 자동 처리되므로 `engine_support.py`에 전략을 하드코딩하지 말 것
- `engine_support.py` 계약은 통과하지만 이 엔진이 계산할 수 없는 요청을 실행 전에 거절한다
  (`INVALID_REQUEST`). **이 검증을 `contracts/`에 넣지 말 것** — `contracts/`는 백엔드와 공유하는
  계약이고 "메시지만 보고 판단할 수 있는" 불변식만 본다. 엔진 지원 범위는 수신자마다 다르므로
  구현체 책임이며, 계약 픽스처(`examples/engine-input.json`)는 `contracts/` 검증을 계속 통과해야 한다.
  지원 범위의 근거는 ADR-0003 "7. DEMO 시장 모델"
- 엔진 입력 모델은 계약 `contracts/engine_input.py`의 `EngineInput` 하나다. 과도기 모델
  `engine_contracts.py`는 제거했다(ADR-0004 반영). 새 입력 필드는 계약 쪽에 맞춘다

- `contracts/` Backend↔Worker RabbitMQ 메시지 계약(pydantic). 기준은 `docs/specs/backtest-messaging/`(README + `examples/*.json`)이며
  `tests/test_contracts.py`가 그 픽스처를 직접 읽어 round-trip을 검증한다. 스키마 변경은 픽스처 → 백엔드 `port/message` → `contracts/` 순서로 같은 MR에서 함께 고친다.
  JSON camelCase ↔ 내부 snake_case는 `ContractModel`의 alias, null은 키 생략(`to_json()` = `exclude_none`), 생략 가능 필드는 `T | None = None`
- `contracts/engine.py` `BacktestEngine` Protocol이 실행 Consumer와 엔진의 경계. 엔진은 `run(engine_input, on_progress) -> EngineOutput`만 구현하고 DB·브로커를 모른다.
  진행 콜백은 거래일 1개 단위, `progress.batchTradingDays` 묶음 발행은 실행 Consumer(`consumer.py`) 책임.
  `FINISHED` payload의 `status`·`partial`·`lastTradingDate`는 `run` 블록의 값을 그대로 옮긴다(백엔드가 일치를 검증).
  계약 v2(spec 004 T001)에 벤치마크는 없다 — `EngineInput.benchmark`도 `FINISHED.benchmark`도 없고 `strip_benchmark`는 제거했다.
  제어 메시지(`contracts/control.py`, CANCEL) 수신 루프는 취소 티켓(T011)에서 붙인다
- `artifacts.py` 는 메시지 계약이 아니라 **로컬 진단 산출물**(`RunArtifacts`: 주문 원장 `orders`, 재현성 `metadata`)이다. 브로커로 나가지 않는다.
  메시지에 넣어야 할 값을 여기 두거나, 진단용 값을 `contracts/engine_output.py`에 넣지 말 것. 엔진은 `run_with_artifacts`로 둘을 함께 반환하고 `run`은 메시지만 반환한다
- **시장 자료는 `market_data.py` 한 곳에서만 읽고, 공급자는 주입한다.** 두 층이 공존한다:
  DEMO 단일 종목 경로(`DataProvider`·`MarketDataset`·`CsvDataProvider`)와 다종목 경로
  (`MarketDataProvider`·`MarketData`·`PostgresMarketDataProvider`·`CsvMarketDataProvider`).
  뒤쪽이 spec 004 계약(`specs/004-backtest-execution/contracts/market-data-input.md`)의 표를 읽고,
  앞쪽은 T006이 다종목 엔진으로 갈아끼울 때 사라진다. 둘 다 `validate_prices()`로 같은 fail-closed
  검증을 받는다
- **엔진 생성자에 공급자 기본값을 두지 말 것.** 예전 기본값(yfinance)은 주입을 잊은 경로가 조용히
  이용 조건 밖 원천(원칙 X)으로 넘어가게 했다. yfinance 공급자는 `worker/scripts/`에 있고 배포되는
  `worker` 패키지에서 import할 수 없다 — 개발 데모(`worker.demo`)와 그 테스트만 `sys.path`에 넣어 쓴다
- **결측과 거래정지는 다른 사건이다.** 거래정지일에도 행이 있고(`trading_suspended=true`, volume 0,
  OHLC 전부 직전 종가) 실행은 계속된다. 거래일 행이 빠지면 보간하지 않고 `DataInsufficientError`로
  실패한다(원칙 VI). 거래일 달력은 읽어 온 자료의 합집합으로 만든다 — 워커는 KRX 달력을 갖고 있지 않다
- 픽스처 `examples/three_etf_24m/`는 **합성 자료**다. 손으로 고치지 말고
  `scripts/make_three_etf_fixture.py`를 고쳐 다시 만든다(수기 대조 기대값이 픽스처에 묶인다)
- **배분은 순수 함수다**(`allocation/`). 조정 종가 이력과 규칙 명세를 받아 목표 비중을 돌려주고 주문·현금·시각을 모른다. 같은 입력이면 언제 불러도 같은 답이 나와야 재사용 판정(R-03)이 성립한다. 전략 추가는 모듈 하나 + `ALLOCATION_RULES` 한 줄이다
- **규칙 값과 비용 수치를 코드에 두지 말 것.** 전부 `allocation.rules`·`execution.costProfile`에서 읽는다. 모듈이 기본값을 들고 있으면 등록표에 새 버전이 생겼을 때 옛 실행의 해석이 조용히 달라진다(R-07·R-08). 명세에 없는 값이 필요하면 지어내지 말고 `INVALID_REQUEST`로 멈춘다
- **팀 미확정 전략은 돌리지 않는다**(`rules_registry.py`). `SECTOR_MOMENTUM`·`MINIMUM_VARIANCE`는 PD-03·PD-04 대기이며, 그럴듯한 기본값으로 돌리면 나중에 규칙이 정해졌을 때 같은 요청이 다른 숫자를 낸다(FR-010). "팀이 안 정했다"와 "엔진이 구현 안 했다"는 메시지를 구분한다 — 사용자가 할 일이 다르다
- **`INVALID_REQUEST`와 `CONSTRAINT_VIOLATION`을 섞지 말 것.** 앞은 요청이 잘못됐다는 뜻이고 뒤는 요청은 말이 되지만 그 조건을 만족하는 배분이 없다는 뜻이다(상한 × 종목 수 < 투자 비중 등)
- **비용은 네 갈래를 따로 보고한다**(`costs.py`). 호가차·슬리피지는 체결가를 불리하게 움직이고 수수료·세금은 현금으로 빠진다 — 합쳐서 한 숫자로 만들면 결과 계약의 `costs{commission, spread, slippage, tax}`를 채울 수 없다. 적용 방향(양방향/매도만)과 기준 금액은 `costs.py` 모듈 주석에 모아 두었다
- **분배금은 권리락일에 자격이 생기고 지급일에 현금이 된다**(`corporate_actions.py`). 둘을 같은 날로 다루면 권리락일에 들고 있다가 판 사람의 몫이 사라지고 지급일에 산 사람이 남의 몫을 받는다. ETF 내부 보수는 차감하지 않는다(FR-013) — 이미 가격에 반영돼 있다
- 브로커 연동은 **3층으로 분리**한다. 이 분리를 깨면 브로커 없이 테스트할 수 없다
  - `messaging.py` 계약 상수(exchange·queue·routing key·control exchange, `SCHEMA_VERSION`, prefetch)와
    경계 Protocol(`EventPublisher`, `JobAcknowledger`) + 오류 분류(`ContractViolation`, `PublishError`).
    **pika를 import하지 않는다**
  - `consumer.py` `BacktestJobConsumer` — 수신·검증·엔진 호출·발행 순서·ACK 판단. 브로커 기술을 모른다
  - `rabbitmq.py` pika 구현체(발행 confirm·재시도, ACK/reject 매핑, 소비 루프)와 `main.py` 엔트리포인트
- **큐·exchange·routing key는 설정이 아니라 계약 상수**다. `config.py`(환경변수)로 빼지 말고
  `messaging.py`에 두고 백엔드 `BacktestRabbitConfig` 상수와 같은 값을 유지한다
  (`tests/test_rabbitmq.py`가 그 Java 파일을 직접 읽어 대조한다)
- **토폴로지(exchange·queue·binding)를 워커가 선언하지 않는다.** 선언은 백엔드(Spring `Declarables`)
  책임이므로(계약 §2) 큐가 없으면 만들지 않고 "백엔드를 먼저 띄워라"라는 오류로 종료한다.
  속성이 어긋난 큐를 워커가 만들면 백엔드 선언과 충돌해 훨씬 찾기 어려운 문제가 된다
- **실행 Consumer는 `EngineOutput` 내용을 해석하지 않는다.** `run.status`가 COMPLETED든 FAILED든
  같은 `FINISHED` 이벤트에 그대로 담아 발행하고 ACK한다. 엔진 실패는 계약대로 답한 정상 결과이지 DLQ가 아니다
- `config.py` 설정은 환경변수로만 주입, 코드에 접속 정보 하드코딩 금지. 접속 정보는 브로커
  `RABBITMQ_URL`과 시장 자료 DB `MARKET_DATA_URL` 둘이며 이름·형태는 `docker-compose.dev.yml`이
  주입하는 것과 같다. `MARKET_DATA_URL`에는 기본값을 두지 않는다 — 값이 없으면 조용히 로컬 DB를
  읽는 대신 실패해야 한다

## 전략 추가 절차 (파일 2개 + 레지스트리 2줄)

1. `contracts/strategy_params/{전략}.py` 에 파라미터 모델(`ContractModel`, `extra="forbid"`,
   기본값 금지)을 만들고 `contracts/strategy_params/__init__.py` 의 `STRATEGY_PARAMETERS` 에 한 줄 등록
2. `strategies/{전략}.py` 에 `Strategy` 구현(`generate_signals` 만)을 만들고
   `strategies/__init__.py` 의 `STRATEGIES` 에 `StrategyEntry(versions=..., build=...)` 한 줄 등록.
   `build` 는 계약이 검증한 `typed_parameters` 를 받아 `PreparedStrategy(signal, required_history)` 를
   돌려준다 — `required_history` 는 첫 신호에 필요한 이전 거래일 종가 수다
   (`Strategy` ABC 는 `generate_signals` 만 요구하므로 준비 기간은 전략 클래스가 아니라
   레지스트리가 선언한다)

`AllocationSpec`·백엔드 record·픽스처·`engine_support.py` 는 건드리지 않는다. 지원하지 않는
`version` 을 다른 버전 로직으로 계산해 놓고 그 버전의 결과라고 반환하면 안 되므로 `versions` 를
반드시 채운다.

## 규칙

- 타입 힌트 필수, 라인 100자 (ruff가 강제)
- 수치 계산은 pandas/numpy 벡터 연산 우선, Python 루프 지양
- 브로커 ACK는 처리와 필수 발행이 완료된 뒤 수행한다(at-least-once). 구체적으로 **FINISHED
  발행이 브로커에서 확인된 뒤에만 작업 메시지를 ACK한다**(계약 §7). 이 순서를 뒤집으면 결과를
  잃고 ACK한 상태가 생긴다. 발행 실패는 완료로 숨기지 않는다(ADR-0003 §6)
- **멱등성: 같은 `runId`가 재전달되면 그냥 다시 실행하고 `executionId`만 새로 발급한다.**
  워커는 결과 DB에 쓰지 않는 게 계약(§1)이라 처리 이력을 남길 곳이 없고, 백테스트는 고정 입력에
  대해 결정론적이라 재실행이 안전하다. 중복 방어는 계약 §7이 백엔드 결과 Consumer 쪽에 요구하며
  ("이미 최종 상태인 Job의 늦은 이벤트는 덮어쓰지 않고 ACK") `eventId`가 그 키다. 그래서
  `eventId`는 이벤트마다 고유하고 **재발행 시에는 유지**해야 한다
- 오류는 세 가지로만 갈린다(계약 §7·§8). ① 계약 오류(JSON 파싱 실패, `ValidationError`, 미지원
  `schemaVersion`) → requeue 없이 reject → DLQ ② 엔진이 FAILED `EngineOutput` 반환 → 정상 결과로
  FINISHED 발행 + ACK ③ 발행 실패·브로커 일시 장애 → 제한적 재시도 후 ACK하지 않고 requeue
- 미래 데이터 참조(look-ahead bias) 금지: 신호는 당일까지 데이터만 사용
