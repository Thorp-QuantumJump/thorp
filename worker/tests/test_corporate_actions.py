"""분배금과 액면분할 반영(spec 004 T007, FR-013).

분배금에서 틀리기 쉬운 것은 **언제 자격이 생기고 언제 현금이 들어오는가**다. 둘을 같은 날로
다루면 권리락일에 팔았거나 지급일에 산 사람의 몫이 어긋난다.
"""

from datetime import date
from decimal import Decimal

import pytest

from worker.corporate_actions import (
    DISTRIBUTION,
    SPLIT_FRACTION,
    DistributionLedger,
    apply_split,
    splits_on,
)
from worker.market_data import Distribution, Split

ETF = "091160.XKRX"
EX_DATE = date(2024, 4, 29)
PAY_DATE = date(2024, 5, 2)


def distribution(amount: str = "120") -> Distribution:
    return Distribution(
        instrument_id=ETF, ex_date=EX_DATE, pay_date=PAY_DATE, amount=Decimal(amount)
    )


def test_cash_arrives_on_the_pay_date_not_the_ex_date():
    ledger = DistributionLedger([distribution()])

    assert ledger.on_ex_date(EX_DATE, {ETF: Decimal(10)})
    assert ledger.on_pay_date(EX_DATE) == [], "권리락일에는 현금이 없다"

    flows = ledger.on_pay_date(PAY_DATE)

    assert len(flows) == 1
    assert flows[0].type == DISTRIBUTION
    assert flows[0].instrument_id == ETF
    assert flows[0].amount == Decimal("1200")


def test_entitlement_follows_the_ex_date_holding():
    """권리락일에 들고 있었으면 그 뒤에 팔아도 받는다."""
    ledger = DistributionLedger([distribution()])
    ledger.on_ex_date(EX_DATE, {ETF: Decimal(10)})

    # 지급일 전에 전량 매도했지만 자격은 남는다.
    flows = ledger.on_pay_date(PAY_DATE)

    assert flows[0].amount == Decimal("1200")


def test_buying_on_the_ex_date_earns_nothing():
    """권리락일 시작 시점 보유가 0이면 자격이 없다 — 그날 사도 마찬가지다."""
    ledger = DistributionLedger([distribution()])

    assert ledger.on_ex_date(EX_DATE, {ETF: Decimal(0)}) == []
    assert ledger.on_pay_date(PAY_DATE) == []


def test_distribution_is_paid_once():
    ledger = DistributionLedger([distribution()])
    ledger.on_ex_date(EX_DATE, {ETF: Decimal(10)})

    assert len(ledger.on_pay_date(PAY_DATE)) == 1
    assert ledger.on_pay_date(PAY_DATE) == [], "같은 분배금이 두 번 들어오면 안 된다"


def test_unheld_instrument_earns_nothing():
    ledger = DistributionLedger([distribution()])

    assert ledger.on_ex_date(EX_DATE, {"305720.XKRX": Decimal(10)}) == []


# --- 액면분할 -----------------------------------------------------------------


def split(ratio: str = "2") -> Split:
    return Split(instrument_id=ETF, ex_date=date(2024, 3, 4), ratio=Decimal(ratio))


def test_split_multiplies_quantity():
    assert apply_split(split("2"), Decimal(10)).quantity == Decimal(20)


@pytest.mark.parametrize(
    ("ratio", "held", "expected", "exact"),
    [
        ("1.5", 5, 7, "7.5"),  # 반올림이면 8 이 된다
        ("1.33", 5, 6, "6.65"),  # 반올림이면 7 이 된다
    ],
)
def test_fractional_split_floors_and_records_why(ratio, held, expected, exact):
    """정수 주만 다루므로 단주는 버린다. 조용히 버리면 자산이 준 이유를 알 수 없다."""
    outcome = apply_split(split(ratio), Decimal(held))

    assert outcome.quantity == Decimal(expected)
    assert outcome.exception is not None
    assert outcome.exception.kind == SPLIT_FRACTION
    assert outcome.exception.instrument_id == ETF
    assert exact in outcome.exception.action


def test_exact_split_has_no_exception():
    assert apply_split(split("2"), Decimal(10)).exception is None


def test_no_holding_means_nothing_to_split():
    assert apply_split(split("2"), Decimal(0)).quantity == 0


def test_negative_holding_is_rejected_not_zeroed():
    """공매도가 없어 생길 수 없는 상태다. 조용히 0으로 만들면 포지션이 사라진 이유를 알 수 없다."""
    with pytest.raises(ValueError, match="negative"):
        apply_split(split("2"), Decimal(-5))


def test_nonpositive_ratio_is_rejected():
    with pytest.raises(ValueError):
        apply_split(split("0"), Decimal(10))


def test_splits_on_filters_by_ex_date():
    events = [split("2"), Split(ETF, date(2024, 6, 3), Decimal(3))]

    assert splits_on(events, date(2024, 3, 4)) == [events[0]]
    assert splits_on(events, date(2024, 3, 5)) == []
