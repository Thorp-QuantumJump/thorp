# CLAUDE.md — worker (백테스트 워커)

Python 3.11 + pandas + NautilusTrader + pika. 엔진의 공개 실행 경로는
`worker.engine_runner.PortfolioBacktestEngine.run(engine_input, on_progress) -> EngineOutput`이고,
운영 엔트리포인트는 `python -m worker.main`(= RabbitMQ 실행 Consumer, `MARKET_DATA_URL` 필수)이다.
단일 종목 모멘텀 DEMO 엔진(`nautilus_engine.py`·`strategies/`·yfinance 공급자)은 spec 004 T006에서
삭제했다. Kafka 스캐폴딩
(`worker.main`의 Kafka 루프, `BacktestJob`, `run_backtest`)은 pika 교체와 함께 삭제했다.
워커는 **PostgreSQL에 쓰지 않고 SSE도 발행하지 않는다** — 결과 저장·SSE는 백엔드 결과
Consumer 몫이다(계약 §1). 현재 상태는 `worker/README.md`와 `portfolio_execution.py` 모듈 주석을,
경계 설계는 `docs/adr/0003-nautilus-momentum-engine-boundary.md`를 먼저 확인한다.

## 검증 명령 (작업 후 반드시 실행)

```bash
ruff check src tests scripts && ruff format --check src tests scripts && pytest
```

## 구조 규칙

- **실행은 `portfolio_execution.PortfolioRebalanceExecution` 하나다**(R-01). 배분 전략이 몇 개든
  Nautilus 전략은 이것 하나이고, 전략 차이는 `allocation/compute_weights`가 돌려주는 비중에만 있다.
  체결·현금·분배금·분할 회계는 여기, 지표 산출은 `performance.py`, 결과 조립은
  `engine_runner.assemble_output` — 이 분리를 깨지 말 것. 파생 지표(`analytics`)의 정의 원본은
  `calculate_analytics` docstring 이다(spec 은 이름만 정했다).
  하루 순서(09:00 틱: 기업행위 → 매도·매수 제출 / 15:30 봉: 평가 → 판단)와 Nautilus 제약(체결
  콜백은 핸들러 뒤에 온다, 계좌는 MARGIN, 장부가 원장이다)은 그 모듈 주석에 있다
- **엔진이 실행할 수 있는 전략은 `allocation/ALLOCATION_RULES`가 정한다.**
  `contracts/strategy_params/STRATEGY_PARAMETERS`("parameters 가 어떤 모양인가")와 대칭이다.
  미등록 `strategyId` 거절은 등록 여부로 자동 처리되므로 `engine_support.py`에 전략을 하드코딩하지 말 것
- `engine_support.py` 계약은 통과하지만 이 엔진이 계산할 수 없는 요청을 실행 전에 거절한다
  (`INVALID_REQUEST`). **이 검증을 `contracts/`에 넣지 말 것** — `contracts/`는 백엔드와 공유하는
  계약이고 "메시지만 보고 판단할 수 있는" 불변식만 본다. 엔진 지원 범위는 수신자마다 다르므로
  구현체 책임이며, 계약 픽스처(`examples/engine-input.json`)는 `contracts/` 검증을 계속 통과해야 한다.
  현재 거절: `fillPrice≠RAW`, `signalPrice≠ADJUSTED`, 규칙 사본 없음, 레버리지·공매도·부분 체결·
  KRW 외 통화, 시나리오 `COST_ESCALATION`(비용 프로필에 `escalation` 규칙 없음), `TARGET_RISK` 에
  `scenarioRules`·`scenarioParams.targetVolatility` 누락
- **목표 위험 맞춤은 `target_risk.py` 오버레이다**(PD-18). 배분 모듈이 낸 비중 위에 얹고 내리기만 한다.
  추정 대상은 실제 보유가 아니라 목표 비중 포트폴리오의 과거 수익률이고(첫 판단일에도 값이 있고
  결정적), 규칙은 `run.scenarioRules`·목표는 `run.scenarioParams` 에서만 읽는다. 수익률이 모자라면
  `ESTIMATION_FAILED` — 백엔드가 오버레이 관측 길이를 워밍업에 넣어 보낸다
- **일시 장애와 확정 오류를 섞지 말 것.** 일시 장애(DB 접속 실패 등)는 자료 적재
  (`MarketDataProvider.load`)에서만 나므로 러너는 그 호출만 try 밖에 두어 전파한다 — 소비자가 ACK 하지
  않고 재전달 경로로 넘긴다(계약 §7 ③). 결과로 바꾸면 FINISHED 가 발행·ACK 되어 일시 장애가 최종 실패로
  굳는다. **자료가 메모리에 오른 뒤의 예외는 전부 결정적**(같은 입력이면 다시 난다)이라 배분·자료·입력
  오류는 자기 코드로, 그 밖은 `ENGINE_ERROR` 결과로 돌려준다. 전파해 재전달시키면 같은 자리에서 또 죽어
  DLQ(SYSTEM_FAILURE)로 가는데 그건 장애가 아니라 결함이다
- 엔진 입력 모델은 계약 `contracts/engine_input.py`의 `EngineInput` 하나다. 과도기 모델
  `engine_contracts.py`는 제거했다(ADR-0004 반영). 새 입력 필드는 계약 쪽에 맞춘다

- `contracts/` Backend↔Worker RabbitMQ 메시지 계약(pydantic). 기준은 `docs/specs/backtest-messaging/`(README + `examples/*.json`)이며
  `tests/test_contracts.py`가 그 픽스처를 직접 읽어 round-trip을 검증한다. 스키마 변경은 픽스처 → 백엔드 `port/message` → `contracts/` 순서로 같은 MR에서 함께 고친다.
  JSON camelCase ↔ 내부 snake_case는 `ContractModel`의 alias, null은 키 생략(`to_json()` = `exclude_none`), 생략 가능 필드는 `T | None = None`
- `contracts/engine.py` `BacktestEngine` Protocol이 실행 Consumer와 엔진의 경계. 엔진은 `run(engine_input, on_progress) -> EngineOutput`만 구현하고 DB·브로커를 모른다.
  진행 콜백은 거래일 1개 단위, `progress.batchTradingDays` 묶음 발행은 실행 Consumer(`consumer.py`) 책임.
  `FINISHED` payload의 `status`·`partial`·`lastTradingDate`는 `run` 블록의 값을 그대로 옮긴다(백엔드가 일치를 검증).
  계약 v2(spec 004 T001)에 벤치마크는 없다 — `EngineInput.benchmark`도 `FINISHED.benchmark`도 없고 `strip_benchmark`는 제거했다.
  제어 메시지(`contracts/control.py`, CANCEL)는 `rabbitmq.run_control_listener`(별도 연결·데몬 스레드, exclusive·auto-delete 큐, auto-ack)가
  받아 `control.CancelRegistry`에 표시하고, 엔진은 `stop_after` 훅으로 거래일마다 자기 runId를 확인해 멈춘다(R-04). **레지스트리는 활성 실행만
  받는다** — 소비자가 STARTED 발행 앞에서 `begin(run_id)`, 끝나면 `end(run_id)`. fanout으로 오는 남의 실행·끝난 실행의 CANCEL은 버린다.
  `run_consumer`는 제어 큐가 바인딩된 뒤에야 작업을 받는다(30초 안에 안 되면 시작하지 않는다) — 그 전에 온 CANCEL은 이 워커에 닿지 않는다.
- **작업 큐는 둘이고 워커 역할은 `WORKER_ROLE`이다**(spec 004 R-20). `single`(기본)은 `backtest.jobs`만 `basic_consume`(prefetch 1),
  `flex`는 `backtest.jobs.bulk` → `backtest.jobs` 순서로 `basic_get` 한 건씩(`run_flex_loop`, 둘 다 비면 `connection.sleep` 1초 —
  `time.sleep`이면 heartbeat가 멈춘다). **flex가 두 큐를 `basic_consume`하지 않는 이유**: quorum 큐는 global QoS를 지원하지 않고, 컨슈머별
  prefetch 1이면 처리하지 않은 두 번째 메시지가 unacked로 잡혀 크래시 때 `x-delivery-limit=1`의 유일한 재시도를 실행 없이 태운다. 큐 이름 상수는
  백엔드 `BacktestRabbitConfig`의 `public static final String` 전부와 같은 값이어야 한다(`test_topology_constants_match_the_backend`)
  엔진·소비자·리스너가 같은 레지스트리를 봐야 하므로 `main.py`가 하나를 만들어 셋에 넘긴다
- `artifacts.py` 는 메시지 계약이 아니라 **로컬 진단 산출물**(`RunArtifacts`: 주문 원장 `orders`, 재현성 `metadata`)이다. 브로커로 나가지 않는다.
  메시지에 넣어야 할 값을 여기 두거나, 진단용 값을 `contracts/engine_output.py`에 넣지 말 것. 엔진은 `run_with_artifacts`로 둘을 함께 반환하고 `run`은 메시지만 반환한다
- **시장 자료는 `market_data.py` 한 곳에서만 읽고, 공급자는 주입한다.**
  `MarketDataProvider` 구현은 둘 — 운영 `PostgresMarketDataProvider`(001 적재 표, 읽기 전용)와 픽스처
  `CsvMarketDataProvider`(같은 표 배치의 CSV 디렉터리). spec 004 계약
  (`specs/004-backtest-execution/contracts/market-data-input.md`)의 표를 읽고 원시 OHLCV는
  `validate_prices()`로 fail-closed 검증을 받는다
- **엔진 생성자에 공급자 기본값을 두지 말 것.** 예전 기본값(yfinance)은 주입을 잊은 경로가 조용히
  이용 조건 밖 원천(원칙 X)으로 넘어가게 했다. 외부 시세 공급자는 이제 없다 — `worker.main`은
  `MARKET_DATA_URL`이 없으면 시작하지 않고, `worker.demo`는 픽스처 디렉터리만 받는다
- **결측과 거래정지는 다른 사건이다.** 거래정지일에도 행이 있고(`trading_suspended=true`, volume 0,
  OHLC 전부 직전 종가) 실행은 계속된다. 거래일 행이 빠지면 보간하지 않고 `DataInsufficientError`로
  실패한다(원칙 VI). 거래일 달력은 읽어 온 자료의 합집합으로 만든다 — 워커는 KRX 달력을 갖고 있지 않다
- 픽스처 `examples/three_etf_24m/`는 **합성 자료**다. 손으로 고치지 말고
  `scripts/make_three_etf_fixture.py`를 고쳐 다시 만든다(수기 대조 기대값이 픽스처에 묶인다).
  다시 만들면 `docs/specs/backtest-messaging/examples/reconciliation/reconcile.py` 도 다시 돌려
  `tests/fixtures/manual_reconciliation.json` 을 갱신한다 — 이 스크립트는 워커를 import 하지 않는
  독립 계산이라 기대값을 엔진 출력으로 채우면 대조가 아니다
- **섹터 모멘텀은 네 단계다**(`allocation/momentum.py`, PD-03). 점수(`P(t-S)/P(t-S-L)-1`, 조정 종가) → 선정(`selectionMode` **하나만**) → 배분(`EQUAL` 또는 역변동성) → 공통 출력. 필요한 이력은 **`L+S+1` 거래일**로 독립 역변동성의 `L+1`과 다르다 — 최근 구간을 건너뛴 만큼 더 앞을 봐야 한다. 모멘텀 안 역변동성 배분은 관측 길이만 물려받고 **`skipRecentDays`는 적용하지 않는다**(점수는 단기 반전을 피하려 최근을 빼지만 변동성은 위험 추정이라 최근이 가장 중요하다). 변동성 계산은 `base.volatility_of` 하나를 독립 역변동성과 함께 쓴다 — 두 곳에서 따로 재면 같은 종목의 위험이 화면마다 달라진다
- **배분은 순수 함수다**(`allocation/`). 조정 종가 이력과 규칙 명세를 받아 목표 비중을 돌려주고 주문·현금·시각을 모른다. 같은 입력이면 언제 불러도 같은 답이 나와야 재사용 판정(R-03)이 성립한다. 전략 추가는 모듈 하나 + `ALLOCATION_RULES` 한 줄이다
- **규칙 버전마다 골든을 나란히 둔다**(`tests/test_rules_goldens.py`, spec 004 T029). 백엔드와 같은 명세 스냅샷(`docs/specs/backtest-messaging/examples/rules/{전략}-{버전}.json`)을 규칙 사본으로 실어 돌린 결과가 `tests/goldens/{전략}-{버전}.json`의 해시와 같아야 한다. 새 규칙 버전이 생기면 `THORP_WRITE_RULES_GOLDENS=1 pytest tests/test_rules_goldens.py`로 **없는 골든만** 만든다(있는 것은 덮어쓰지 않는다). 해시는 수를 유효숫자 12자리로 맞춘 뒤에 재고(float 끝자리 흔들림 흡수, 아래 환경 간 재현 항목), 골든에 기준 입력의 지문(`inputSha256`)을 함께 남겨 입력이 바뀐 것과 엔진이 바뀐 것을 가른다. 옛 버전의 골든은 지우지 않는다 — 옛 버전으로 접수된 실행이 재시도·재발행에서 옛 명세로 계산되기 때문이다. 이미 있는 골든이 깨지면 "같은 버전이 다른 숫자를 낸다"는 뜻이라, 의존성·엔진 변경이 원인일 때만 파일을 지우고 다시 만들며 MR에 이유를 적는다. **엔진은 `allocation.rulesVersion`으로 분기하지 않는다** — 버전 문자열은 결과 이름표이고, 버전 사이의 계산 차이는 명세 키(`capOverflow`·`weightUnit` 등)의 값 차이로만 생긴다
- **환경 간 재현은 골든과 같은 12자리 해시로 판정한다**(spec 004 T030, research R-21). 해시 정의는 `reproducibility.py` 하나이고 골든 테스트와 `scripts/reproducibility_report.py`가 함께 쓴다 — 둘이 따로 정의하면 스크립트는 같다는데 골든은 깨지는 일이 생긴다. 기준 입력과 실행은 `tests/golden_inputs.py`에 두고 **pytest를 import하지 않는다**(운영 이미지는 `--no-dev`라 pytest가 없는데 스크립트는 그 안에서 돌아야 한다). 반올림 전 해시(`exactSha256`)는 판정에 쓰지 않는다. 2026-09-19에 Windows·Linux x86_64·Linux aarch64에서 반올림 전까지 같았지만, 다른 CPU·BLAS 빌드에서 float 지표 끝자리가 흔들리는 것을 실패로 만들지 않으려는 것이다. **12자리 해시가 못 잡는 차이가 있다**: 유효숫자 기준이라 정수부가 긴 금액은 소수 자리가 먼저 잘린다(1천만 원대 `finalEquity`는 소수 넷째 자리까지만 들어간다). 비용·체결·현금처럼 금액 계산을 고치는 MR은 고치기 전과 후에 비교 스크립트를 돌려 `exactSha256`까지 확인한다
- **골든 재생성 정책**: ① `uv.lock`이나 기반 이미지 digest를 바꾸는 MR은 새 이미지 안에서 비교 스크립트를 돌린다(`worker/README.md` "환경 간 재현 확인") ② `goldenMatch`가 모두 참이면 골든을 건드리지 않는다 ③ 깨지면 먼저 `inputSha256`이 같은지 본다. 다르면 입력(기준 입력·규칙 스냅샷)이 바뀐 것이지 엔진 회귀가 아니다 ④ 입력이 같은데 깨졌으면 `--dump-dir` 결과를 옛 환경 것과 diff해 갈린 필드와 자리수를 찾는다. 원인이 의존성·이미지·엔진 변경으로 확인될 때만 그 골든 파일을 지우고 `THORP_WRITE_RULES_GOLDENS=1 pytest tests/test_rules_goldens.py`로 다시 만든다 ⑤ MR 본문에 원인, 바뀐 필드와 자리수, 이전·이후 `summary`를 적는다. 골든이 바뀐다는 것은 저장된 과거 결과를 재시도하면 다른 숫자가 나온다는 뜻이다 ⑥ 원인을 설명하지 못하면 골든을 바꾸지 않고 의존성·이미지 변경을 되돌린다
- **규칙 값과 비용 수치를 코드에 두지 말 것.** 전부 `allocation.rules`·`execution.costProfile`에서 읽는다. 모듈이 기본값을 들고 있으면 등록표에 새 버전이 생겼을 때 옛 실행의 해석이 조용히 달라진다(R-07·R-08). 명세에 없는 값이 필요하면 지어내지 말고 `INVALID_REQUEST`로 멈춘다
- **팀 미확정 전략은 돌리지 않는다**(`rules_registry.py`). 그럴듯한 기본값으로 돌리면 나중에 규칙이 정해졌을 때 같은 요청이 다른 숫자를 낸다(FR-010). "팀이 안 정했다"와 "엔진이 구현 안 했다"는 메시지를 구분한다 — 사용자가 할 일이 다르다. **표는 지금 비어 있다**(2026-09-17 PD-03·PD-04 확정으로 다섯 전략이 모두 등록됐다). 규칙 없는 전략을 더할 때 다시 쓴다
- **`INVALID_REQUEST`와 `CONSTRAINT_VIOLATION`을 섞지 말 것.** 앞은 요청이 잘못됐다는 뜻이고 뒤는 요청은 말이 되지만 그 조건을 만족하는 배분이 없다는 뜻이다
- **상한을 못 채우는 것은 실패가 아니다**(PD-03 ⑥, `rules.capOverflow: CASH`). 상한 × 종목 수가 투자 비중에 못 미치면 재분배하고 남는 몫을 현금으로 두고 정상 완료한다 — 상한을 몰래 풀어 사용자가 고른 조건과 다른 포트폴리오를 돌리지 않는다. 접수 때 이미 알 수 있는 모순은 백엔드가 422 `VALIDATION_RULE_VIOLATED`로 거절한다
- **비중의 마지막 단계는 언제나 `base.quantise`다**(`rules.weightUnit`). 0.000001 단위로 내리고 잃은 단위를 소수부 큰 순으로 돌려준 뒤 합·비음수·상한을 다시 확인한다. 이 재확인을 건너뛰면 부동소수 오차가 상한을 넘긴 비중이 결과로 나간다
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

## 배분 전략 추가 절차 (파일 1~2개 + 레지스트리 1~2줄)

1. `allocation/{전략}.py` 에 `compute(universe, history, parameters, rules, as_of) -> AllocationResult`
   순수 함수를 만들고 `allocation/__init__.py` 의 `ALLOCATION_RULES` 에 한 줄 등록. 규칙 값은
   `rules`에서만 읽고 없으면 `INVALID_REQUEST`로 멈춘다
2. `parameters` 모양이 공통(`{"rebalance": "MONTH_END"}`)과 다르면
   `contracts/strategy_params/{전략}.py` 에 모델(`ContractModel`, `extra="forbid"`, 기본값 금지)을 만들고
   `STRATEGY_PARAMETERS` 에 등록. 같으면 `RebalanceParameters`를 그대로 등록한다

`portfolio_execution.py`·`AllocationSpec`·백엔드 record·픽스처·`engine_support.py` 는 건드리지 않는다.
팀 미확정 전략(`rules_registry.PENDING_DECISION`)은 규칙이 확정되면 그 표에서 빼고 여기 절차를 따른다.

## 규칙

- 타입 힌트 필수, 라인 100자 (ruff가 강제)
- **의존성 버전은 `uv.lock` 하나가 정한다**(spec 004 T027). 운영 이미지는 `uv sync --frozen`, CI는
  `uv sync --locked --python 3.11`로 같은 lock에서 설치한다. `requirements.txt`를 다시 만들지 말 것 — 두 곳에서
  관리하면 운영만 다른 numpy·scipy로 돌아 결과가 마지막 자리에서 갈리는데 테스트는 통과한다. `pyproject.toml`을
  고치면 `uv lock`으로 lock을 함께 갱신한다 — 고정 버전(`==`)과 lock이 어긋나면
  `tests/test_dependency_pins.py`가 실패한다. 기반 이미지·uv digest 갱신 절차는 `worker/README.md` "실행환경 잠금"
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
