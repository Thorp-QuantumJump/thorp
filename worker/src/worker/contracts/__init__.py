"""Backend ↔ Worker RabbitMQ 메시지 계약 (pydantic v2).

단일 기준은 ``docs/specs/backtest-messaging/README.md`` 와 ``examples/*.json`` 이다. 백엔드
``backtest/application/port/message`` record와 1:1로 대응하며, 필드를 바꾸면 픽스처 → 백엔드 DTO →
이 패키지 순서로 같은 MR에서 함께 고친다.

- ``engine_input``  작업 메시지 본문 (EngineInput)
- ``engine_output`` 엔진 결과 (EngineOutput) + 벤치마크 (BenchmarkResult)
- ``events``        이벤트 envelope (BacktestEvent) + payload 3종
- ``engine``        엔진 경계 (BacktestEngine Protocol, ProgressCallback)

기존 ``worker.models`` (Kafka 시절 4필드 Job)는 워커 런타임을 pika로 교체할 때 제거한다.
"""
