"""사례 연결 계약 픽스처 round-trip과 계약 오류 거부.

백엔드 ``RegimeLinkMessageContractTest``와 **같은 픽스처·같은 규칙**이다. 한쪽만
고치면 다른 쪽이 깨지는 것이 목적이라 픽스처를 테스트 리소스로 복사하지 않고
원본을 읽는다.
"""

import json
from typing import Any

import pytest
from fixture_paths import REGIME_LINK_FIXTURE_DIR
from pydantic import ValidationError

from worker.regime_link.contracts import (
    BasisKind,
    FillSide,
    RegimeLinkErrorCode,
    RegimeLinkEvent,
    RegimeLinkEventType,
    RegimeLinkFinishedPayload,
    RegimeLinkJob,
    RegimeLinkModel,
    RegimeLinkScenario,
    RegimeLinkStartedPayload,
    RegimeLinkStatus,
    TurnoverBasis,
)
from worker.regime_link.messaging import SCHEMA_VERSION

StartedEvent = RegimeLinkEvent[RegimeLinkStartedPayload]
FinishedEvent = RegimeLinkEvent[RegimeLinkFinishedPayload]

FIXTURES: list[tuple[str, type[RegimeLinkModel]]] = [
    ("job-continuous.json", RegimeLinkJob),
    ("job-fresh-cash.json", RegimeLinkJob),
    ("job-partial-delisted.json", RegimeLinkJob),
    ("event-started.json", StartedEvent),
    ("event-finished-completed.json", FinishedEvent),
    ("event-finished-incomplete63.json", FinishedEvent),
    ("event-finished-failed.json", FinishedEvent),
]


def load(name: str) -> str:
    return (REGIME_LINK_FIXTURE_DIR / name).read_text(encoding="utf-8")


def load_dict(name: str) -> dict[str, Any]:
    return json.loads(load(name))


# --- round-trip ---------------------------------------------------------------------


@pytest.mark.parametrize(("name", "model"), FIXTURES, ids=[f[0] for f in FIXTURES])
def test_roundtrip_exact(name: str, model: type[RegimeLinkModel]) -> None:
    """픽스처 → 모델 → JSON 이 원본과 정확 동치 (null 키 생략, 정규화 없음)."""
    original = load(name)
    written = model.model_validate_json(original).to_json()

    assert json.loads(written) == json.loads(original)


def test_job_continuous_keeps_the_window_shape() -> None:
    job = RegimeLinkJob.model_validate_json(load("job-continuous.json"))

    assert job.job.scenario is RegimeLinkScenario.CONTINUOUS
    # baselineDate 행이 하나 더 있어 행 수 = completedTradingDays + 1
    assert len(job.inputs.portfolio_series) == job.window.completed_trading_days + 1
    assert job.inputs.portfolio_series[0].date == job.window.baseline_date
    # 연속 운용은 시작 보유가 있다
    assert job.inputs.opening_positions[0].quantity == 179
    assert job.inputs.trades[0].side is FillSide.SELL
    assert job.inputs.cash_flows[0].type == "DISTRIBUTION"
    assert len(job.input_digest) == 64


def test_job_fresh_cash_starts_from_initial_cash() -> None:
    job = RegimeLinkJob.model_validate_json(load("job-fresh-cash.json"))

    assert job.job.scenario is RegimeLinkScenario.FRESH_CASH
    # 기준은 run.initialCash이고 시작 보유는 빈 목록이다(research R3)
    assert job.inputs.opening_positions == []
    assert str(job.run.initial_cash) == "10000000.00"
    assert job.inputs.trades[0].side is FillSide.BUY


def test_job_partial_delisted_is_already_shortened() -> None:
    job = RegimeLinkJob.model_validate_json(load("job-partial-delisted.json"))

    # 백엔드가 이미 폐지 전날까지로 줄여 보낸다 — 워커가 다시 줄이지 않는다
    assert job.window.last_available < job.window.d63
    assert job.window.completed_trading_days == 50
    assert len(job.inputs.portfolio_series) == 51
    delisted = [item for item in job.universe.items if item.delisted_on is not None]
    assert [item.instrument_id for item in delisted] == ["244580.XKRX"]
    # 폐지되지 않은 종목은 키가 없다 (null 규칙)
    items = load_dict("job-partial-delisted.json")["universe"]["items"]
    assert "delistedOn" not in items[0]


def test_started_event() -> None:
    event = StartedEvent.model_validate_json(load("event-started.json"))

    assert event.schema_version == SCHEMA_VERSION == 1
    assert event.type is RegimeLinkEventType.STARTED
    assert event.payload.resource is not None
    assert event.payload.resource.pid == 4242


def test_finished_completed_event() -> None:
    event = FinishedEvent.model_validate_json(load("event-finished-completed.json"))
    payload = event.payload
    result = payload.result

    assert payload.status is RegimeLinkStatus.COMPLETED
    assert payload.partial is False
    assert payload.delisted_on is None
    assert payload.error is None
    assert result is not None
    assert result.window63.complete is True
    assert result.window63.trading_days == 63
    assert result.window63.turnover_basis is TurnoverBasis.ANNUALIZED_252
    # 시계열과 목록은 구간 전체를 담는다
    assert len(result.holdings) == result.window63.trading_days
    assert len(result.trades) == result.window63.trade_count
    # 이익 종목도 빠지지 않는다 — 보유 섹터 셋이 모두 들어간다
    assert result.window63.loss_contribution is not None
    assert set(result.window63.loss_contribution) == {
        "SEMICONDUCTOR",
        "BATTERY",
        "BIO",
    }
    assert result.basis.kind is BasisKind.HISTORICAL_REVISION


def test_finished_incomplete63_event() -> None:
    event = FinishedEvent.model_validate_json(load("event-finished-incomplete63.json"))
    payload = event.payload

    # 계산은 끝났고(COMPLETED) 63을 못 채웠을 뿐이다
    assert payload.status is RegimeLinkStatus.COMPLETED
    assert payload.partial is True
    assert payload.delisted_on is not None
    assert payload.delisted_on.isoformat() == "2012-06-11"
    assert payload.result is not None
    assert payload.result.window63.complete is False
    assert payload.result.window63.trading_days == 50
    # window21은 언제나 완성이다 — 21 미만이면 애초에 발행되지 않는다
    assert payload.result.window21.complete is True
    assert payload.result.window21.trading_days == 21


def test_finished_failed_event() -> None:
    event = FinishedEvent.model_validate_json(load("event-finished-failed.json"))
    payload = event.payload

    assert payload.status is RegimeLinkStatus.FAILED
    assert payload.result is None
    assert payload.error is not None
    assert payload.error.code is RegimeLinkErrorCode.INPUT_INCOMPLETE


# --- 표기 규칙 -----------------------------------------------------------------------


def test_return_uses_the_contract_key() -> None:
    """계약이 정한 키는 ``return``이다.

    예약어라 필드 이름은 ``window_return``이고 alias가 둘을 맞춘다.
    """
    event = FinishedEvent.model_validate_json(load("event-finished-completed.json"))
    written = json.loads(event.to_json())["payload"]["result"]

    assert "windowReturn" not in written["window63"]
    assert written["window63"]["return"] == "0.0276"
    assert written["kospi"]["window21"]["return"] == "0.0050"
    # 소수는 문자열, 거래일 수는 숫자
    assert isinstance(written["window63"]["turnover"], str)
    assert isinstance(written["window63"]["tradingDays"], int)


def test_job_body_has_no_schema_version() -> None:
    """작업은 헤더로만 버전을 싣고 이벤트만 본문 첫 필드에 싣는다."""
    assert "schemaVersion" not in load_dict("job-continuous.json")
    assert "schemaVersion" in load_dict("event-started.json")


# --- 계약 오류 거부 -------------------------------------------------------------------


def test_unknown_field_is_rejected() -> None:
    """모르는 키는 양쪽 계약이 어긋났다는 신호라 조용히 넘기지 않는다."""
    body = load_dict("job-continuous.json")
    body["inputs"]["weightsSeries"] = []

    with pytest.raises(ValidationError):
        RegimeLinkJob.model_validate(body)


def test_missing_required_field_is_rejected() -> None:
    """빠진 값을 기본값으로 채우지 않는다."""
    body = load_dict("job-continuous.json")
    del body["window"]["baselineDate"]

    with pytest.raises(ValidationError):
        RegimeLinkJob.model_validate(body)


def test_fraction_in_an_integer_slot_is_rejected() -> None:
    """수량은 정수다. 소수를 반올림해 통과시키지 않는다."""
    body = load_dict("job-continuous.json")
    body["inputs"]["openingPositions"][0]["quantity"] = 1.5

    with pytest.raises(ValidationError):
        RegimeLinkJob.model_validate(body)


def test_unknown_enum_value_is_rejected() -> None:
    """자료 부족은 발행 전 백엔드 판정이라 워커의 실패 사유가 아니다(R13)."""
    body = load_dict("event-finished-failed.json")
    body["payload"]["error"]["code"] = "DATA_INSUFFICIENT"

    with pytest.raises(ValidationError):
        FinishedEvent.model_validate(body)


def test_naive_datetime_is_rejected() -> None:
    """시간대 없는 시각은 백엔드 ``Instant``가 읽지 못하므로 먼저 막는다."""
    body = load_dict("event-started.json")
    body["payload"]["startedAt"] = "2026-09-18T02:00:00"

    with pytest.raises(ValidationError):
        StartedEvent.model_validate(body)
