# CLAUDE.md — worker (백테스트 워커)

Python 3.11 + pandas. Kafka `backtest.jobs`를 Consumer Group(`backtest-workers`)으로 소비해 백테스트 실행 후 PostgreSQL에 결과 저장. 같은 코드를 N개 실행해 수평 확장.

## 검증 명령 (작업 후 반드시 실행)

```bash
ruff check src tests && ruff format --check src tests && pytest
```

## 구조 규칙

- `strategies/` 전략은 `base.Strategy` 상속, `generate_signals(prices) -> Series`만 구현
  (전략은 신호만 출력, 체결·자산 계산·지표 산출은 `engine.py` 담당 — 이 분리를 깨지 말 것)
- `contracts/` Backend↔Worker RabbitMQ 메시지 계약(pydantic). 기준은 `docs/specs/backtest-messaging/`(README + `examples/*.json`)이며
  `tests/test_contracts.py`가 그 픽스처를 직접 읽어 round-trip을 검증한다. 스키마 변경은 픽스처 → 백엔드 `port/message` → `contracts/` 순서로 같은 MR에서 함께 고친다.
  JSON camelCase ↔ 내부 snake_case는 `ContractModel`의 alias, null은 키 생략(`to_json()` = `exclude_none`), 생략 가능 필드는 `T | None = None`
- `contracts/engine.py` `BacktestEngine` Protocol이 실행 Consumer와 엔진의 경계. 엔진은 `run(engine_input, on_progress) -> EngineOutput`만 구현하고 DB·브로커를 모른다.
  진행 콜백은 거래일 1개 단위, 20거래일 묶음 발행·벤치마크 계산은 실행 Consumer 책임
- `models.py` 구 Kafka Job 계약 — 런타임을 pika로 교체할 때 `contracts/`로 대체·삭제 예정. 새 코드는 참조하지 않는다
- `config.py` 설정은 환경변수로만 주입, 코드에 접속 정보 하드코딩 금지

## 규칙

- 타입 힌트 필수, 라인 100자 (ruff가 강제)
- 수치 계산은 pandas/numpy 벡터 연산 우선, Python 루프 지양
- Kafka 커밋은 처리 완료 후 수동 커밋 (at-least-once) — 백테스트는 재실행해도 안전하게(멱등) 작성
- 미래 데이터 참조(look-ahead bias) 금지: 신호는 당일까지 데이터만 사용
