"""비용 조건 적용(spec 004 T007, FR-019·FR-027).

여기서 고정하는 것은 **수치가 아니라 규칙**이다. 수치는 PD-07이 정하고 등록표 버전으로 들어온다.
규칙이 흔들리면 같은 프로필로 돌린 두 실행이 다른 비용을 보고한다.
"""

from decimal import Decimal

import pytest

from worker.contracts.engine_input import CostProfile
from worker.costs import CostModel, FillCost, Side, total_costs


def profile(
    commission: str = "0", spread: str = "0", slippage: str = "0", tax: str = "0"
) -> CostProfile:
    return CostProfile(
        version="TEST-1",
        commissionBps=Decimal(commission),
        spreadBps=Decimal(spread),
        slippageBps=Decimal(slippage),
        taxBps=Decimal(tax),
        provisional=True,
    )


DEMO = profile(slippage="5")


def test_demo_profile_charges_slippage_only():
    model = CostModel(DEMO)

    cost = model.for_fill(Side.BUY, Decimal("10000"), Decimal("10"))

    assert cost.slippage == Decimal("50")  # 100,000 x 5bps
    assert cost.commission == 0
    assert cost.spread == 0
    assert cost.tax == 0
    assert cost.total == Decimal("50")


def test_confirmed_profile_charges_nine_and_a_half_bps_each_side():
    """PD-07 확정값(spec 004 T021). 기본 요청은 이 프로필로 계산된다.

    수수료 1.5 + 호가차 3 + 슬리피지 5 = 한쪽 9.5bps. 세금이 0이라 매도도 같다.
    """
    model = CostModel(
        CostProfile(
            version="KR-ETF-2026-09",
            commissionBps=Decimal("1.5000"),
            spreadBps=Decimal("3.0000"),
            slippageBps=Decimal("5.0000"),
            taxBps=Decimal("0.0000"),
            provisional=False,
        )
    )
    notional = Decimal("100000")  # 10,000 x 10

    buy = model.for_fill(Side.BUY, Decimal("10000"), Decimal("10"))
    sell = model.for_fill(Side.SELL, Decimal("10000"), Decimal("10"))

    assert buy.total / notional == sell.total / notional == Decimal("0.00095")
    assert (buy.commission, buy.spread, buy.slippage, buy.tax) == (
        Decimal("15"),
        Decimal("30"),
        Decimal("50"),
        Decimal("0"),
    )
    assert sell.tax == 0
    assert not model.provisional


def test_price_impact_is_always_unfavourable():
    model = CostModel(profile(spread="10", slippage="5"))

    buy = model.effective_price(Side.BUY, Decimal("10000"))
    sell = model.effective_price(Side.SELL, Decimal("10000"))

    assert buy == Decimal("10015")
    assert sell == Decimal("9985")


def test_commission_applies_to_both_sides_tax_only_to_sell():
    model = CostModel(profile(commission="15", tax="23"))

    buy = model.for_fill(Side.BUY, Decimal("10000"), Decimal("10"))
    sell = model.for_fill(Side.SELL, Decimal("10000"), Decimal("10"))

    assert buy.commission == sell.commission == Decimal("150")
    assert buy.tax == 0, "거래세는 처분에만 매긴다"
    assert sell.tax == Decimal("230")


def test_cost_base_is_the_price_before_impact():
    """충격 후 금액으로 매기면 비용이 비용을 늘린다."""
    model = CostModel(profile(commission="100", slippage="100"))

    cost = model.for_fill(Side.BUY, Decimal("10000"), Decimal("1"))

    assert cost.commission == Decimal("100")  # 10,000 x 100bps. 10,100 기준이 아니다
    assert cost.slippage == Decimal("100")


def test_cash_cost_excludes_price_impact():
    """호가차·슬리피지는 체결가에 이미 들어가 있다. 현금에서 또 빼면 두 번 센다."""
    model = CostModel(profile(commission="10", spread="10", slippage="10", tax="10"))

    cost = model.for_fill(Side.SELL, Decimal("10000"), Decimal("1"))

    assert cost.total == Decimal("40")
    assert cost.cash == Decimal("20")


def test_affordable_quantity_leaves_room_for_commission():
    """체결가만 보고 나누면 수수료만큼 초과 매수가 나온다 — 레버리지 금지(FR-012)를 어긴다."""
    model = CostModel(profile(commission="100", slippage="100"))

    quantity = model.affordable_quantity(Decimal("1000000"), Decimal("10000"))

    unit = model.effective_price(Side.BUY, Decimal("10000"))
    spent = unit * quantity + model.for_fill(Side.BUY, Decimal("10000"), quantity).cash
    assert spent <= Decimal("1000000")
    assert quantity == 98


@pytest.mark.parametrize("price", [Decimal("0"), Decimal("-1")])
def test_affordable_quantity_rejects_nonpositive_price(price):
    with pytest.raises(ValueError):
        CostModel(DEMO).affordable_quantity(Decimal("1000"), price)


def test_totals_sum_the_four_buckets():
    model = CostModel(profile(commission="10", spread="5", slippage="5", tax="20"))
    fills = [
        model.for_fill(Side.BUY, Decimal("10000"), Decimal("1")),
        model.for_fill(Side.SELL, Decimal("10000"), Decimal("1")),
    ]

    costs = total_costs(fills)

    assert costs.commission == Decimal("20")
    assert costs.spread == Decimal("10")
    assert costs.slippage == Decimal("10")
    assert costs.tax == Decimal("20")
    assert costs.total == Decimal("60")


def test_empty_totals_are_zero_not_missing():
    costs = total_costs([])

    assert costs.total == 0
    assert costs.commission == 0


def test_fill_costs_add_up():
    assert (FillCost(commission=Decimal("1")) + FillCost(tax=Decimal("2"))).total == Decimal("3")
