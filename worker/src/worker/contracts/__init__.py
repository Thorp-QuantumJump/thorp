"""Backend ↔ Worker RabbitMQ 메시지 계약 (pydantic v2).

단일 기준은 ``docs/specs/backtest-messaging/README.md`` 와 ``examples/*.json`` 이다. 백엔드
``backtest/application/port/message`` record와 1:1로 대응하며, 필드를 바꾸면 픽스처 → 백엔드 DTO →
이 패키지 순서로 같은 MR에서 함께 고친다.

- ``engine_input``  작업 메시지 본문 (EngineInput)
- ``engine_output`` 엔진 결과 (EngineOutput)
- ``events``        이벤트 envelope (BacktestEvent) + payload 3종
- ``control``       백엔드 → 워커 제어 메시지 (ControlMessage, CANCEL)
- ``engine``        엔진 경계 (BacktestEngine Protocol, ProgressCallback)
- ``strategy_params`` 전략별 ``allocation.parameters`` 스키마 + ``strategyId`` 레지스트리

Kafka 시절 스캐폴딩(``worker.models`` 의 4필드 Job, ``worker.engine`` 의 미구현
``run_backtest``)은 pika 런타임 교체와 함께 제거했다. 작업 메시지 모델은 ``engine_input`` 뿐이다.
"""
