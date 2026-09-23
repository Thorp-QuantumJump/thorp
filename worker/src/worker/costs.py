"""비용 조건(``execution.costProfile``)을 체결에 적용한다(spec 004 T007, FR-019·FR-027).

엔진은 비용 수치를 **자체 기본값으로 갖지 않는다.** 전부 요청에 실려 온 프로필에서 읽는다. 값이
바뀌면 등록표에 새 버전이 생기고 옛 실행은 옛 버전을 계속 참조하므로(R-07), 코드가 기본값을 들고
있으면 그 재현이 깨진다.

**어느 쪽에 무엇을 매기는지는 여기서 정한다.** PD-07이 정하는 것은 *수치*이고, 그 수치를 쓰려면
적용 규칙이 먼저 있어야 한다. 규칙은 아래 세 줄이다. PD-07 확정값(``KR-ETF-2026-09``: 수수료 1.5 ·
호가차 3 · 슬리피지 5 · 세금 0bps, spec 004 T021)은 이 규칙을 전제로 정해졌다 — 호가차 3bps는
"한쪽 체결에 매기므로 호가 간격의 절반"이다. 그래서 사고팔 때 한쪽마다 9.5bps, 왕복 약 0.19%다.

1. **수수료·호가차·슬리피지는 사고팔 때 모두** 낸다. 거래를 성사시키는 데 드는 비용이다.
2. **세금은 팔 때만** 낸다. 거래세는 처분에 매기는 세금이다. (이번 대상인 국내주식형 투자신탁
   ETF는 매도 거래세가 없어 확정값도 0이다.)
3. 비용의 기준 금액은 **가격 충격을 반영하기 전** 수량 × 기준가다. 충격 후 금액으로 매기면 비용이
   비용을 늘리는 순환이 생긴다.

호가차와 슬리피지는 **가격**을 불리하게 움직이고, 수수료와 세금은 **현금**으로 빠진다. 둘을 섞어
한 숫자로 만들지 않는 이유는 결과 계약이 넷을 따로 보여 주기 때문이다(``costs{commission, spread,
slippage, tax, total}``).
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from enum import StrEnum

from worker.contracts.engine_input import CostProfile
from worker.contracts.engine_output import Costs

BPS = Decimal(10_000)
ZERO = Decimal(0)


class Side(StrEnum):
    """주문 방향.

    계약의 ``TradeSide`` 는 포지션 방향(LONG/SHORT)이라 다른 개념이다. 공매도가 없는 이 제품에서
    포지션은 늘 LONG 이고, 비용은 그 포지션을 **열 때와 닫을 때** 각각 붙는다.
    """

    BUY = "BUY"
    SELL = "SELL"


@dataclass(frozen=True)
class FillCost:
    """체결 한 건에 붙는 비용. 결과 계약의 ``costs`` 와 같은 네 갈래다."""

    commission: Decimal = ZERO
    spread: Decimal = ZERO
    slippage: Decimal = ZERO
    tax: Decimal = ZERO

    @property
    def total(self) -> Decimal:
        return self.commission + self.spread + self.slippage + self.tax

    @property
    def cash(self) -> Decimal:
        """현금으로 빠지는 몫.

        호가차·슬리피지는 체결가에 이미 들어가 있어 여기서 또 빼면 두 번 센다.
        """
        return self.commission + self.tax

    def __add__(self, other: FillCost) -> FillCost:
        return FillCost(
            commission=self.commission + other.commission,
            spread=self.spread + other.spread,
            slippage=self.slippage + other.slippage,
            tax=self.tax + other.tax,
        )


class CostModel:
    """한 실행에 고정된 비용 조건. 실행 중에 바뀌지 않는다."""

    def __init__(self, profile: CostProfile) -> None:
        self.profile = profile

    @property
    def version(self) -> str:
        return self.profile.version

    @property
    def provisional(self) -> bool:
        """팀 결정(PD-07) 전 임시값인가. 결과 ``versions`` 에 그대로 실린다."""
        return self.profile.provisional

    def _rate(self, bps: Decimal) -> Decimal:
        return bps / BPS

    def effective_price(self, side: Side, reference_price: Decimal) -> Decimal:
        """호가차와 슬리피지를 반영한 실제 체결가. 언제나 불리한 쪽으로 움직인다."""
        impact = self._rate(self.profile.spread_bps) + self._rate(self.profile.slippage_bps)
        if side is Side.BUY:
            return reference_price * (Decimal(1) + impact)
        return reference_price * (Decimal(1) - impact)

    def for_fill(self, side: Side, reference_price: Decimal, quantity: Decimal) -> FillCost:
        """체결 한 건의 네 갈래 비용. 기준 금액은 가격 충격 전 수량 × 기준가다."""
        notional = reference_price * quantity
        return FillCost(
            commission=notional * self._rate(self.profile.commission_bps),
            spread=notional * self._rate(self.profile.spread_bps),
            slippage=notional * self._rate(self.profile.slippage_bps),
            tax=(notional * self._rate(self.profile.tax_bps) if side is Side.SELL else ZERO),
        )

    def affordable_quantity(self, cash: Decimal, reference_price: Decimal) -> int:
        """주어진 현금으로 살 수 있는 정수 수량. 체결가와 현금 비용을 모두 감안한다.

        레버리지가 없으므로(FR-012) 비용까지 치르고도 현금이 음수가 되면 안 된다. 체결가만 보고
        나누면 수수료만큼 초과 매수가 나온다.
        """
        if reference_price <= ZERO:
            raise ValueError(f"reference price must be positive: {reference_price}")
        unit = self.effective_price(Side.BUY, reference_price) + reference_price * (
            self._rate(self.profile.commission_bps)
        )
        if unit <= ZERO:
            raise ValueError(f"effective unit cost must be positive: {unit}")
        return max(0, int(cash // unit))


def total_costs(fills: list[FillCost]) -> Costs:
    """결과 계약의 ``costs`` 블록. 비어 있으면 0으로 채운다 — 키를 빼지 않는다."""
    summed = FillCost()
    for fill in fills:
        summed = summed + fill
    return Costs(
        commission=summed.commission,
        spread=summed.spread,
        slippage=summed.slippage,
        tax=summed.tax,
        total=summed.total,
    )
