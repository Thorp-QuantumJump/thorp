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
from worker.rabbitmq import bind_control_queue

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


def test_registry_marks_and_forgets_run_ids() -> None:
    registry = CancelRegistry()
    assert not registry.is_cancelled("TEST")
    registry.request("TEST")
    assert registry.is_cancelled("TEST") and not registry.is_cancelled("OTHER")
    registry.discard("TEST")
    assert not registry.is_cancelled("TEST")
    registry.discard("TEST")  # 모르는 runId 는 조용히 무시한다


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
    registry.request("TEST")

    output = engine(registry).run(body())

    assert output.run.status is RunStatus.CANCELLED
    assert output.run.partial is True
    assert output.run.error is None
    assert len(output.portfolio_series) == 1
    assert output.run.last_trading_date == output.portfolio_series[0].date == date(2024, 1, 2)
    assert output.portfolio_series[-1].positions is not None, "멈춘 날은 종료일이라 보유가 있다"
    assert output.performance is not None and output.execution is not None
    assert not registry.is_cancelled("TEST"), "끝난 runId 는 레지스트리에서 지운다"


def test_cancel_during_the_run_stops_at_the_next_evaluation_day() -> None:
    """진행 콜백 5일째에 취소가 도착하면 6일째 평가를 마친 뒤 멈춘다.

    완료 범위 = 처리한 마지막 거래일.
    """
    registry = CancelRegistry()

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
    registry.request("SOMEONE-ELSE")

    output = engine(registry).run(body())

    assert output.run.status is RunStatus.COMPLETED and not output.run.partial
    assert registry.is_cancelled("SOMEONE-ELSE"), "다른 실행의 취소 표시는 건드리지 않는다"


def test_engine_without_a_registry_never_stops_early() -> None:
    output = PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR)).run(body())
    assert output.run.status is RunStatus.COMPLETED


# --- 소비자: FINISHED(CANCELLED) 발행 뒤 ACK ------------------------------------------------


def test_consumer_publishes_cancelled_finished_and_acks() -> None:
    registry = CancelRegistry()
    registry.request("TEST")
    log: list[str] = []
    publisher = FakePublisher(log, ())

    BacktestJobConsumer(engine(registry), publisher).handle(
        json.dumps(body()), None, FakeAcknowledger(log)
    )

    finished = [e for e in publisher.events if e.type is BacktestEventType.FINISHED][-1]
    assert finished.payload.status is RunStatus.CANCELLED
    assert finished.payload.partial is True
    assert finished.payload.last_trading_date == date(2024, 1, 2)
    assert log[-1] == "ack" and log.index("publish:FINISHED") < log.index("ack")


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
