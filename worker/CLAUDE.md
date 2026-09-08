# CLAUDE.md — worker (백테스트 워커)

Python 3.11 + pandas + NautilusTrader. Jira 101의 공개 실행 경로는
`worker.engine.run_engine(EngineInput) -> EngineOutput`이며 현재 CLI/테스트로 독립 실행된다.
브로커·PostgreSQL·SSE에는 아직 연결되지 않았다. `worker.main`, `BacktestJob`, `run_backtest`는
Kafka 기반 레거시 스캐폴딩이고, 목표 아키텍처의 RabbitMQ와 최종 선택이 정리되기 전에는 새
엔진 연동 기준으로 사용하지 않는다. 자세한 현재 상태는
`docs/guides/nautilus-momentum-engine.md`와 `docs/adr/0002-nautilus-momentum-engine-boundary.md`를
먼저 확인한다.

## 검증 명령 (작업 후 반드시 실행)

```bash
ruff check src tests && ruff format --check src tests && pytest
```

## 구조 규칙

- `strategies/` 전략은 `base.Strategy` 상속, `generate_signals(prices) -> Series`만 구현
  (전략은 신호만 출력, 체결·자산 계산·지표 산출은 `engine.py` 담당 — 이 분리를 깨지 말 것)
- `models.py` Job/Result 계약 — 백엔드 DTO와 필드 동기화 (JSON camelCase ↔ 내부 snake_case는 pydantic alias)
- `config.py` 설정은 환경변수로만 주입, 코드에 접속 정보 하드코딩 금지

## 규칙

- 타입 힌트 필수, 라인 100자 (ruff가 강제)
- 수치 계산은 pandas/numpy 벡터 연산 우선, Python 루프 지양
- 브로커 ACK/커밋은 처리와 필수 저장이 완료된 뒤 수행한다(at-least-once). 재전달되어도 안전한
  멱등 처리 규칙은 Consumer 연동 티켓에서 함께 확정한다.
- 미래 데이터 참조(look-ahead bias) 금지: 신호는 당일까지 데이터만 사용
