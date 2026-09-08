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
- `contracts/` Backend↔Worker RabbitMQ 메시지 계약(pydantic). 기준은 `docs/specs/backtest-messaging/`(README + `examples/*.json`)이며
  `tests/test_contracts.py`가 그 픽스처를 직접 읽어 round-trip을 검증한다. 스키마 변경은 픽스처 → 백엔드 `port/message` → `contracts/` 순서로 같은 MR에서 함께 고친다.
  JSON camelCase ↔ 내부 snake_case는 `ContractModel`의 alias, null은 키 생략(`to_json()` = `exclude_none`), 생략 가능 필드는 `T | None = None`
- `contracts/engine.py` `BacktestEngine` Protocol이 실행 Consumer와 엔진의 경계. 엔진은 `run(engine_input, on_progress) -> EngineOutput`만 구현하고 DB·브로커를 모른다.
  진행 콜백은 거래일 1개 단위, 20거래일 묶음 발행·벤치마크 계산은 실행 Consumer 책임
- `engine_contracts.py` 는 **입력 모델만 남은 과도기 파일**이다. 결과·진행 모델은 `contracts/`로 옮겼고, 입력은 계약 `contracts/engine_input.py`와 고정 실행 정책 세 곳(`marketData.adjustment`, `execution.delisting`, `execution.commission`)이 달라 남아 있다.
  수수료 정책이 결정되면 `contracts/engine_input.py`로 통합하고 이 파일은 제거한다. 새 입력 필드는 여기가 아니라 계약 쪽에 맞춘다. 근거는 `docs/adr/0003-nautilus-momentum-engine-boundary.md` "메시지 계약과의 정합 상태"
- `artifacts.py` 는 메시지 계약이 아니라 **로컬 진단 산출물**(`RunArtifacts`: 주문 원장 `orders`, 재현성 `metadata`)이다. 브로커로 나가지 않는다.
  메시지에 넣어야 할 값을 여기 두거나, 진단용 값을 `contracts/engine_output.py`에 넣지 말 것. 엔진은 `run_with_artifacts`로 둘을 함께 반환하고 `run`은 메시지만 반환한다
- `models.py`(구 Kafka Job 계약)와 `engine.py`(미구현 `run_backtest`) — 런타임을 pika로 교체할 때 `contracts/`·`engine_runner.py`로 대체·삭제 예정. 새 코드는 참조하지 않는다
- `config.py` 설정은 환경변수로만 주입, 코드에 접속 정보 하드코딩 금지

## 규칙

- 타입 힌트 필수, 라인 100자 (ruff가 강제)
- 수치 계산은 pandas/numpy 벡터 연산 우선, Python 루프 지양
- 브로커 ACK/커밋은 처리와 필수 저장이 완료된 뒤 수행한다(at-least-once). 재전달되어도 안전한
  멱등 처리 규칙은 Consumer 연동 티켓에서 함께 확정한다.
- 미래 데이터 참조(look-ahead bias) 금지: 신호는 당일까지 데이터만 사용
