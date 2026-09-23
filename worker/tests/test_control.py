"""취소 제어 채널(spec 004 R-04, T011) — 워커 쪽.

백엔드가 fanout ``backtest.control`` 로 ``CANCEL{runId}`` 를 보내면 워커는 별도 스레드에서 받아
:class:`CancelRegistry` 에 표시하고, 엔진은 거래일마다(``_end_day``) 그 표시를 보고 그날
평가를 마친 뒤 멈춘다. 결과는 ``lastTradingDate`` 까지의 부분 ``EngineOutput`` 이고 소비자는
그것을 ``FINISHED{status: CANCELLED, partial: true}`` 로 발행한 뒤 ACK 한다.
"""

import json
from datetime import date
from typing import Any

import pytest
from conftest import THREE_ETF_DIR, engine_input_body
from test_consumer import FakeAcknowledger, FakePublisher

from worker.consumer import BacktestJobConsumer
from worker.contracts.control import ControlMessage, ControlType
from worker.contracts.engine_output import RunStatus
from worker.contracts.events import BacktestEventType
from worker.control import CancelRegistry, handle_control_body, parse_control
from worker.engine_runner import PortfolioBacktestEngine
from worker.market_data import CsvMarketDataProvider
from worker.messaging import CONTROL_EXCHANGE, SCHEMA_VERSION, ContractViolation
from worker.rabbitmq import bind_control_queue, run_consumer, run_control_listener

CANCEL = {
    "schemaVersion": SCHEMA_VERSION,
    "type": "CANCEL",
    "runId": "TEST",
    "requestedAt": "2026-09-07T01:00:02Z",
}
THREE = [
    {"sectorId": "SEMICONDUCTOR", "instrumentId": "091160.XKRX", "targetWeight": "0.4"},
    {"sectorId": "BATTERY", "instrumentId": "305720.XKRX", "targetWeight": "0.3"},
    {"sectorId": "BIO", "instrumentId": "244580.XKRX", "targetWeight": "0.3"},
]


def body(**overrides: Any) -> dict[str, Any]:
    return engine_input_body(
        run={"runId": "TEST", "start": "2024-01-02", "end": "2024-03-29"},
        universe={"items": THREE},
        **overrides,
    )


def engine(registry: CancelRegistry) -> PortfolioBacktestEngine:
    return PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR), cancel_registry=registry)


# --- 레지스트리·계약 -----------------------------------------------------------------------


def test_registry_only_records_cancels_for_runs_this_worker_is_executing() -> None:
    """fanout 이라 모든 워커가 모든 CANCEL 을 받는다. 남의 실행·끝난 실행의 표시는 쌓아 두지
    않는다."""
    registry = CancelRegistry()
    assert registry.request("SOMEONE-ELSE") is False
    assert not registry.is_cancelled("SOMEONE-ELSE")

    registry.begin("TEST")
    assert not registry.is_cancelled("TEST")
    assert registry.request("TEST") is True
    assert registry.is_cancelled("TEST")

    registry.end("TEST")
    assert not registry.is_cancelled("TEST")
    assert registry.request("TEST") is False, "끝난 실행에 늦게 온 취소는 남지 않는다"
    registry.end("TEST")  # 두 번 끝내도 조용하다


def test_registry_begin_clears_a_stale_mark_for_the_same_run_id() -> None:
    """같은 runId 가 재전달되면(R-06) 새 실행이다 — 이전 실행의 취소를 물려받지 않는다."""
    registry = CancelRegistry()
    registry.begin("TEST")
    registry.request("TEST")
    registry.end("TEST")
    registry.begin("TEST")
    assert not registry.is_cancelled("TEST")


def test_parse_control_reads_the_contract_fixture() -> None:
    message = parse_control(json.dumps(CANCEL))
    assert message == ControlMessage.model_validate(CANCEL)
    assert message.type is ControlType.CANCEL and message.run_id == "TEST"


@pytest.mark.parametrize(
    "broken",
    [
        {**CANCEL, "schemaVersion": 1},
        {**CANCEL, "type": "PAUSE"},
        {k: v for k, v in CANCEL.items() if k != "runId"},
        "not json",
    ],
)
def test_parse_control_rejects_contract_errors(broken: Any) -> None:
    payload = broken if isinstance(broken, str) else json.dumps(broken)
    with pytest.raises(ContractViolation):
        parse_control(payload)


def test_control_body_marks_the_registry_and_ignores_broken_messages() -> None:
    """제어 메시지는 auto-ack 다 — 깨진 메시지는 로그만 남기고 스레드를 죽이지 않는다."""
    registry = CancelRegistry()
    registry.begin("TEST")
    handle_control_body(b"garbage", registry)
    assert not registry.is_cancelled("TEST")
    handle_control_body(json.dumps(CANCEL).encode(), registry)
    assert registry.is_cancelled("TEST")


# --- 엔진: 거래일 단위 정지 ------------------------------------------------------------------


def test_cancel_requested_before_the_run_stops_after_the_first_evaluation_day() -> None:
    """대기 중 취소된 실행의 STARTED 가 오면 백엔드가 CANCEL 을 다시 보낸다.

    첫 거래일 평가 뒤 멈춘다.
    """
    registry = CancelRegistry()
    registry.begin("TEST")
    registry.request("TEST")

    output = engine(registry).run(body())

    assert output.run.status is RunStatus.CANCELLED
    assert output.run.partial is True
    assert output.run.error is None
    assert len(output.portfolio_series) == 1
    assert output.run.last_trading_date == output.portfolio_series[0].date == date(2024, 1, 2)
    assert output.portfolio_series[-1].positions is not None, "멈춘 날은 종료일이라 보유가 있다"
    assert output.performance is not None and output.execution is not None


def test_cancel_during_the_run_stops_at_the_next_evaluation_day() -> None:
    """진행 콜백 5일째에 취소가 도착하면 6일째 평가를 마친 뒤 멈춘다.

    완료 범위 = 처리한 마지막 거래일.
    """
    registry = CancelRegistry()
    registry.begin("TEST")

    def on_progress(point: Any, completed: int, total: int) -> None:
        if completed == 5:
            registry.request("TEST")

    output = engine(registry).run(body(), on_progress)

    assert output.run.status is RunStatus.CANCELLED and output.run.partial is True
    assert len(output.portfolio_series) == 6
    assert output.run.last_trading_date == output.portfolio_series[-1].date
    assert output.run.effective_end == output.run.last_trading_date
    assert output.portfolio_series[-1].positions is not None


def test_other_run_ids_do_not_stop_this_run() -> None:
    registry = CancelRegistry()
    registry.begin("TEST")
    registry.begin("OTHER")
    registry.request("OTHER")

    output = engine(registry).run(body())

    assert output.run.status is RunStatus.COMPLETED and not output.run.partial
    assert registry.is_cancelled("OTHER"), "다른 실행의 취소 표시는 건드리지 않는다"


def test_engine_without_a_registry_never_stops_early() -> None:
    output = PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR)).run(body())
    assert output.run.status is RunStatus.COMPLETED


# --- 소비자: FINISHED(CANCELLED) 발행 뒤 ACK ------------------------------------------------


class CancelOnStarted(FakePublisher):
    """STARTED 가 브로커에 닿은 순간 백엔드가 CANCEL 을 되돌려 보내는 상황(대기 중 취소의
    늦은 STARTED)."""

    def __init__(self, log: list[str], registry: CancelRegistry) -> None:
        super().__init__(log, ())
        self.registry = registry
        self.accepted: bool | None = None

    def publish(self, event: Any) -> None:
        super().publish(event)
        if event.type is BacktestEventType.STARTED:
            self.accepted = self.registry.request(event.run_id)


def test_consumer_registers_the_run_before_started_so_an_immediate_cancel_lands() -> None:
    """등록이 STARTED 발행보다 앞서야 STARTED 에 대한 답으로 오는 CANCEL 을 놓치지 않는다."""
    registry = CancelRegistry()
    log: list[str] = []
    publisher = CancelOnStarted(log, registry)

    BacktestJobConsumer(engine(registry), publisher, registry=registry).handle(
        json.dumps(body()), None, FakeAcknowledger(log)
    )

    assert publisher.accepted is True, "STARTED 시점에 이미 활성 실행으로 등록돼 있어야 한다"
    finished = [e for e in publisher.events if e.type is BacktestEventType.FINISHED][-1]
    assert finished.payload.status is RunStatus.CANCELLED
    assert finished.payload.partial is True
    assert finished.payload.last_trading_date == date(2024, 1, 2)
    assert log[-1] == "ack" and log.index("publish:FINISHED") < log.index("ack")
    assert registry.request("TEST") is False, "끝난 실행은 등록이 풀려 늦은 취소가 남지 않는다"


def test_consumer_without_a_registry_still_runs() -> None:
    log: list[str] = []
    publisher = FakePublisher(log, ())
    BacktestJobConsumer(engine(CancelRegistry()), publisher).handle(
        json.dumps(body()), None, FakeAcknowledger(log)
    )
    assert [e.type for e in publisher.events][-1] is BacktestEventType.FINISHED


# --- 제어 큐 바인딩 -------------------------------------------------------------------------


class FakeControlChannel:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def exchange_declare(self, **kwargs: Any) -> None:
        self.calls.append(("exchange_declare", kwargs))

    def queue_declare(self, **kwargs: Any) -> Any:
        self.calls.append(("queue_declare", kwargs))
        return type("R", (), {"method": type("M", (), {"queue": "amq.gen-worker-1"})()})()

    def queue_bind(self, **kwargs: Any) -> None:
        self.calls.append(("queue_bind", kwargs))


class FakeControlConnection:
    """바인딩 → ready → 소비 한 바퀴 → 종료를 흉내내는 pika 연결 대역."""

    def __init__(self, stop: Any, deliveries: list[bytes]) -> None:
        self.stop = stop
        self.deliveries = deliveries
        self.chan = FakeControlChannel()
        self.is_open = True

    def channel(self) -> Any:
        chan = self.chan

        def consume(queue: str, auto_ack: bool, inactivity_timeout: float) -> Any:
            assert auto_ack is True
            for body in self.deliveries:
                yield object(), None, body
            self.stop.set()
            yield None, None, None

        chan.consume = consume  # type: ignore[attr-defined]
        return chan

    def close(self) -> None:
        self.is_open = False


def test_control_listener_signals_ready_only_after_the_queue_is_bound() -> None:
    import threading

    registry = CancelRegistry()
    registry.begin("TEST")
    stop = threading.Event()
    ready = threading.Event()
    connection = FakeControlConnection(stop, [json.dumps(CANCEL).encode()])
    seen_before_bind: list[bool] = []
    original_bind = connection.chan.queue_bind

    def queue_bind(**kwargs: Any) -> None:
        seen_before_bind.append(ready.is_set())
        original_bind(**kwargs)

    connection.chan.queue_bind = queue_bind  # type: ignore[method-assign]

    run_control_listener("amqp://x", registry, stop, ready, connect=lambda url: connection)

    assert seen_before_bind == [False], "바인딩이 끝나기 전에는 ready 가 아니다"
    assert ready.is_set()
    assert registry.is_cancelled("TEST")
    assert not connection.is_open


def test_consumer_startup_waits_for_the_control_queue() -> None:
    """제어 큐가 준비되기 전에 작업을 시작하면 그 사이 온 CANCEL 을 잃는다. 준비되지 않으면
    시작하지 않는다."""

    class NeverReadyConnection:
        def __init__(self) -> None:
            self.is_open = True

        def channel(self) -> Any:
            raise AssertionError("작업 채널을 열기 전에 멈춰야 한다")

        def close(self) -> None:
            self.is_open = False

    with pytest.raises(RuntimeError, match="control"):
        run_consumer(
            "amqp://x",
            engine(CancelRegistry()),
            CancelRegistry(),
            connect=lambda url: NeverReadyConnection(),
            control_listener=lambda url, registry, stop, ready, connect: stop.wait(),
            ready_timeout=0.05,
        )


def test_control_queue_is_exclusive_auto_delete_and_bound_to_the_fanout() -> None:
    """토폴로지는 백엔드 몫이라 exchange 는 passive 확인만 한다.

    큐는 워커마다 하나씩 서버 이름으로 만든다.
    """
    channel = FakeControlChannel()

    queue = bind_control_queue(channel)  # type: ignore[arg-type]

    assert queue == "amq.gen-worker-1"
    names = [name for name, _ in channel.calls]
    assert names == ["exchange_declare", "queue_declare", "queue_bind"]
    exchange = channel.calls[0][1]
    assert exchange["exchange"] == CONTROL_EXCHANGE and exchange["passive"] is True
    declare = channel.calls[1][1]
    assert declare["exclusive"] is True and declare["auto_delete"] is True
    assert declare.get("queue", "") == ""
    bind = channel.calls[2][1]
    assert bind["queue"] == "amq.gen-worker-1" and bind["exchange"] == CONTROL_EXCHANGE
