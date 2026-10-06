"""분배금과 액면분할을 보유에 반영한다(spec 004 T007, FR-013).

**분배금은 권리락일에 자격이 생기고 지급일에 현금이 들어온다.** 둘은 며칠 떨어져 있고, 그
사이에 종목을 팔아도 자격은 남는다 — 권리락일에 들고 있었으면 받는다. 지급일 보유 수량으로
계산하면 그날 이미 판 사람에게 0을 주고, 그날 새로 산 사람에게 남의 몫을 준다.

**ETF 내부 보수는 차감하지 않는다**(FR-013). 기초지수 대비 추적오차의 상당 부분이 보수인데,
그것을 다시 빼면 이미 가격에 반영된 비용을 두 번 세게 된다.

액면분할은 수량만 바꾼다. 정수 주만 다루므로(``portfolio.integerLots``) 나누어떨어지지 않는
분할은 내림하고 그 사실을 예외로 남긴다 — 조용히 버리면 사용자는 자산이 줄어든 이유를 알 수 없다.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from worker.contracts.engine_output import CashFlow, ExceptionRecord
from worker.market_data import Distribution, Split

ZERO = Decimal(0)

#: 결과 계약 ``portfolioSeries[].cashFlows[].type``.
DISTRIBUTION = "DISTRIBUTION"

#: 예외 기록의 ``kind``. 개방 집합이라 새 값을 더해도 계약이 깨지지 않는다.
SPLIT_FRACTION = "SPLIT_FRACTION"


@dataclass(frozen=True)
class Entitlement:
    """권리락일에 확정된 분배금 자격. 지급일에 현금이 된다."""

    instrument_id: str
    ex_date: date
    pay_date: date
    amount_per_share: Decimal
    quantity: Decimal

    @property
    def cash(self) -> Decimal:
        return self.amount_per_share * self.quantity


class DistributionLedger:
    """권리락일에 자격을 적어 두고 지급일에 현금으로 돌려준다.

    한 번 지급한 자격은 장부에서 빼므로 <b>같은 분배금이 두 번 들어오지 않는다</b>. 지급일이
    실행 종료일 뒤면 끝내 지급되지 않는다 — 받지 못한 돈을 받은 것으로 치지 않는다.
    """

    def __init__(self, distributions: list[Distribution]) -> None:
        self._by_ex_date: dict[date, list[Distribution]] = {}
        for distribution in distributions:
            self._by_ex_date.setdefault(distribution.ex_date, []).append(distribution)
        self._pending: list[Entitlement] = []

    def on_ex_date(self, day: date, holdings: dict[str, Decimal]) -> list[Entitlement]:
        """이 거래일이 권리락일인 분배금의 자격을 확정한다.

        :param holdings: 이 거래일을 시작하는 시점의 보유 수량. 이날 체결될 주문은 아직 반영하지
            않는다 — 권리락일 당일에 산 주식은 자격이 없다.
        """
        created = []
        for distribution in self._by_ex_date.get(day, []):
            quantity = holdings.get(distribution.instrument_id, ZERO)
            if quantity <= ZERO:
                continue
            entitlement = Entitlement(
                instrument_id=distribution.instrument_id,
                ex_date=distribution.ex_date,
                pay_date=distribution.pay_date,
                amount_per_share=distribution.amount,
                quantity=quantity,
            )
            self._pending.append(entitlement)
            created.append(entitlement)
        return created

    def on_pay_date(self, day: date) -> list[CashFlow]:
        """지급일이 된 자격을 현금 흐름으로 바꾸고 장부에서 지운다."""
        due = [item for item in self._pending if item.pay_date == day]
        if not due:
            return []
        self._pending = [item for item in self._pending if item.pay_date != day]
        return [
            CashFlow(type=DISTRIBUTION, instrumentId=item.instrument_id, amount=item.cash)
            for item in due
        ]


@dataclass(frozen=True)
class SplitOutcome:
    quantity: Decimal
    exception: ExceptionRecord | None = None


def apply_split(split: Split, quantity: Decimal) -> SplitOutcome:
    """분할 비율만큼 수량을 늘린다. 정수로 떨어지지 않으면 내림하고 예외를 남긴다.

    내림은 <b>양수 보유에 대해서만</b> 뜻이 있다. 음수 보유는 이 제품에 존재할 수 없고, 있다면
    ``ROUND_FLOOR`` 가 절댓값을 키우는 쪽으로 돌아 더 이상한 수량이 된다 — 그래서 내림하기 전에
    거절한다.
    """
    if split.ratio <= ZERO:
        raise ValueError(f"split ratio must be positive: {split.ratio}")
    if quantity < ZERO:
        # 공매도가 없으므로(FR-012) 음수 보유는 생길 수 없다. 생겼다면 호출부가 망가진 것이고,
        # 조용히 0 으로 만들면 그 자리에서 포지션이 사라져 원인을 되짚을 수 없다.
        raise ValueError(f"holding must not be negative: {quantity}")
    if quantity == ZERO:
        return SplitOutcome(quantity=ZERO)
    exact = quantity * split.ratio
    whole = exact.to_integral_value(rounding="ROUND_FLOOR")
    if whole == exact:
        return SplitOutcome(quantity=whole)
    return SplitOutcome(
        quantity=whole,
        exception=ExceptionRecord(
            date=split.ex_date,
            instrumentId=split.instrument_id,
            kind=SPLIT_FRACTION,
            rule=f"ratio={split.ratio}",
            action=f"{exact} -> {whole} (단주 버림)",
        ),
    )


def splits_on(splits: list[Split], day: date) -> list[Split]:
    """이 거래일이 권리락일인 분할. 당일 시가부터 새 수량으로 본다."""
    return [split for split in splits if split.ex_date == day]
