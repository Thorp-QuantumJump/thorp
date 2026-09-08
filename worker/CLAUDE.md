# CLAUDE.md — worker (백테스트 워커)

Python 3.11 + pandas + NautilusTrader. Jira 101의 공개 실행 경로는
`worker.engine_runner.NautilusMomentumEngine.run(engine_input, on_progress) -> EngineOutput`이며
현재 CLI/테스트로 독립 실행된다.
브로커·PostgreSQL·SSE에는 아직 연결되지 않았다. `worker.main`, `BacktestJob`, `run_backtest`는
Kafka 기반 레거시 스캐폴딩이고, 목표 아키텍처의 RabbitMQ와 최종 선택이 정리되기 전에는 새
엔진 연동 기준으로 사용하지 않는다. 자세한 현재 상태는
`docs/guides/nautilus-momentum-engine.md`와 `docs/adr/0003-nautilus-momentum-engine-boundary.md`를
먼저 확인한다.

## 검증 명령 (작업 후 반드시 실행)

```bash
ruff check src tests && ruff format --check src tests && pytest
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
  진행 콜백은 거래일 1개 단위, 20거래일 묶음 발행·벤치마크 계산은 실행 Consumer 책임
- `artifacts.py` 는 메시지 계약이 아니라 **로컬 진단 산출물**(`RunArtifacts`: 주문 원장 `orders`, 재현성 `metadata`)이다. 브로커로 나가지 않는다.
  메시지에 넣어야 할 값을 여기 두거나, 진단용 값을 `contracts/engine_output.py`에 넣지 말 것. 엔진은 `run_with_artifacts`로 둘을 함께 반환하고 `run`은 메시지만 반환한다
- `models.py`(구 Kafka Job 계약)와 `engine.py`(미구현 `run_backtest`) — 런타임을 pika로 교체할 때 `contracts/`·`engine_runner.py`로 대체·삭제 예정. 새 코드는 참조하지 않는다
- `config.py` 설정은 환경변수로만 주입, 코드에 접속 정보 하드코딩 금지

## 전략 추가 절차 (파일 2개 + 레지스트리 2줄)

1. `contracts/strategy_params/{전략}.py` 에 파라미터 모델(`ContractModel`, `extra="forbid"`,
   기본값 금지)을 만들고 `contracts/strategy_params/__init__.py` 의 `STRATEGY_PARAMETERS` 에 한 줄 등록
2. `strategies/{전략}.py` 에 `Strategy` 구현(`generate_signals` 만)을 만들고
   `strategies/__init__.py` 의 `STRATEGIES` 에 `StrategyEntry(versions=..., build=...)` 한 줄 등록.
   `build` 는 계약이 검증한 `typed_parameters` 를 받아 `PreparedStrategy(signal, required_history)` 를
   돌려준다 — `required_history` 는 첫 신호에 필요한 이전 거래일 종가 수다
   (`Strategy` ABC 는 `generate_signals` 만 요구하므로 준비 기간은 전략 클래스가 아니라
   레지스트리가 선언한다)

`StrategySpec`·백엔드 record·픽스처·`engine_support.py` 는 건드리지 않는다. 지원하지 않는
`version` 을 다른 버전 로직으로 계산해 놓고 그 버전의 결과라고 반환하면 안 되므로 `versions` 를
반드시 채운다.

## 규칙

- 타입 힌트 필수, 라인 100자 (ruff가 강제)
- 수치 계산은 pandas/numpy 벡터 연산 우선, Python 루프 지양
- 브로커 ACK/커밋은 처리와 필수 저장이 완료된 뒤 수행한다(at-least-once). 재전달되어도 안전한
  멱등 처리 규칙은 Consumer 연동 티켓에서 함께 확정한다.
- 미래 데이터 참조(look-ahead bias) 금지: 신호는 당일까지 데이터만 사용
