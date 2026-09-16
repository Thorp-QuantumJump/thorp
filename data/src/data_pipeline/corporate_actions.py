"""기업행위와 소급 조정 가격 (spec 001 FR-004·FR-006, research.md 결정 2).

분배금과 분할마다 조정 계수를 만들어 **그 이전 구간에 누적해서 곱한다.**

```
분할일  factor = 1 / 분할비율
분배일  factor = (직전 종가 - 주당 분배금) / 직전 종가
누적계수(t) = Π{ factor(e) : e.ex_date > t }
조정가(t)   = 원시 종가(t) × 누적계수(t)
```

## 이 계열의 성질을 왜 조심해야 하는가

소급 조정 계열의 **절대 수준은 나중에 생긴 분배금에 따라 바뀐다.** 오늘 분배금이 하나 생기면
어제까지의 조정가가 모두 내려간다. 불변인 것은 **두 시점 사이의 비율**뿐이다. 뒤에 생긴 계수가
분자와 분모에 똑같이 들어가 상쇄되기 때문이다.

```
조정가(t1)   원시종가(t1) × Π{f : ex > t1}
────────── = ──────────────────────────────  ← t1·t2보다 뒤의 계수는 위아래에서 상쇄된다
조정가(t2)   원시종가(t2) × Π{f : ex > t2}
```

그래서 **절대 수준을 판단 기준으로 쓰면 그때는 알 수 없었던 정보로 계산한 결과가 조용히
만들어진다**(원칙 V 위반). "가격이 N원 이하일 때 매수" 같은 규칙이 그렇다. 숫자는 그럴듯하게
나오므로 테스트가 없으면 드러나지 않는다. 현재 다섯 전략은 모두 비율 기반이라 문제되지
않지만, 계약(contracts/market-dataset.md 「체결과 신호의 계열 구분」)이 이를 조건으로 적어 두었고
:mod:`data.tests.test_corporate_actions` 가 비율 불변성을 테스트로 고정한다.

## 운용보수를 다시 차감하지 않는다 (FR-004)

ETF 내부 보수는 매일 순자산에서 차감되어 **이미 종가에 녹아 있다.** 백테스트에서 연 보수율을
또 빼면 같은 비용을 두 번 계산한다. 그래서 이 모듈에는 보수·비용을 받는 인자가 없고, 기업행위가
없는 구간의 조정 수익률은 원시 종가 수익률과 **정확히** 같다.
"""

from __future__ import annotations

import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from decimal import ROUND_HALF_UP, Decimal, localcontext
from typing import Any, Final

from data_pipeline.krx import DailyQuote

#: ``corporate_action.event_type``. 마이그레이션의 CHECK와 같은 두 값뿐이다.
EVENT_DISTRIBUTION: Final[str] = "DISTRIBUTION"
EVENT_SPLIT: Final[str] = "SPLIT"
EVENT_TYPES: Final[frozenset[str]] = frozenset({EVENT_DISTRIBUTION, EVENT_SPLIT})

#: ``data_gap.reason``. 기업행위를 반영할 수 없어 그 이전 구간을 쓸 수 없을 때다 (FR-006).
REASON_MISSING_CORPORATE_ACTION: Final[str] = "MISSING_CORPORATE_ACTION"

#: ``actions_known_from``에 넣는 "목록이 담은 구간을 말할 수 없다"는 값. 기업행위 파일 자체가
#: 없을 때가 그렇다. 모든 날이 이 값보다 앞이므로 :func:`build_adjustment_series` 는 계열
#: 전체를 ``MISSING_CORPORATE_ACTION`` 자료 부족 구간으로 남긴다. 값을 지어내지 않으면서도
#: "기업행위가 없다"와 "기업행위를 모른다"를 가르는 자리이며, 이 값은 구간의 날짜로 새어
#: 나가지 않는다 — 구간의 끝은 언제나 계열의 마지막 날이다.
ACTIONS_KNOWN_FROM_NEVER: Final[date] = date.max

INSTRUMENT_ID_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A[0-9A-Z]{6}\.XKRX\Z")

#: 누적 곱셈의 작업 정밀도. 저장 자리수(아래)보다 넉넉히 두어 중간 계산에서 깎이지 않게 한다.
#: 나눗셈이 끝나지 않는 계수(예: 204/10100)는 이 자리수에서 반올림되므로, 조정가의 비율
#: 불변성을 값 동치로 확인할 수 있는 것은 계수가 유한소수일 때다. 그 밖에는 이 자리수에서
#: 비롯한 오차만큼의 차이로 확인한다 (tests/test_corporate_actions.py).
WORKING_PRECISION: Final[int] = 50

#: 저장 자리수. 마이그레이션의 ``adjusted_close NUMERIC(20, 8)``·
#: ``adjustment_factor NUMERIC(20, 12)``와 같다. 여기서 맞춰 두지 않으면 DB가 조용히
#: 반올림한 값과 파이썬이 검증한 값이 달라진다.
ADJUSTED_CLOSE_EXPONENT: Final[Decimal] = Decimal("0.00000001")
ADJUSTMENT_FACTOR_EXPONENT: Final[Decimal] = Decimal("0.000000000001")

ONE: Final[Decimal] = Decimal(1)


class CorporateActionError(ValueError):
    """기업행위 값이나 조정 계산의 규칙 위반."""


@dataclass(frozen=True)
class CorporateAction:
    """기업행위 한 건. ``corporate_action`` 한 행에 대응한다.

    ``ex_date``는 조정 계수 산출에, ``pay_date``는 지급일 현금 반영에 쓴다(004 계약).
    날짜 열을 하나로 두면 워커가 분배금을 락일에 현금으로 넣거나 지급일에 조정하게 되어
    양쪽 모두 틀린다.

    ``amount``는 분배금이면 주당 금액, 분할이면 **분할비율**이다. 1주가 2주가 되는 분할의
    비율은 2이고 그때 계수는 1/2이다.
    """

    instrument_id: str
    ex_date: date
    event_type: str
    amount: Decimal
    pay_date: date | None = None

    def __post_init__(self) -> None:
        if INSTRUMENT_ID_PATTERN.match(self.instrument_id) is None:
            raise CorporateActionError(
                f"종목 식별자가 {{6글자}}.XKRX 형식이 아니다: {self.instrument_id!r}"
            )
        if not isinstance(self.ex_date, date):
            raise CorporateActionError(f"ex_date는 date여야 한다: {self.ex_date!r}")
        if self.event_type not in EVENT_TYPES:
            raise CorporateActionError(
                f"event_type은 {sorted(EVENT_TYPES)} 중 하나여야 한다: {self.event_type!r}"
            )
        if not isinstance(self.amount, Decimal):
            raise CorporateActionError(
                f"amount는 Decimal이어야 한다 (부동소수는 계수를 흔든다): {self.amount!r}"
            )
        if not self.amount.is_finite() or self.amount <= 0:
            raise CorporateActionError(f"amount는 양수여야 한다: {self.amount}")
        if self.event_type == EVENT_DISTRIBUTION:
            if self.pay_date is None:
                raise CorporateActionError(
                    f"분배금은 pay_date가 있어야 한다: {self.instrument_id} {self.ex_date}"
                )
            if not isinstance(self.pay_date, date):
                raise CorporateActionError(f"pay_date는 date여야 한다: {self.pay_date!r}")
            if self.pay_date < self.ex_date:
                raise CorporateActionError(
                    f"pay_date는 ex_date 이후여야 한다: {self.instrument_id} "
                    f"{self.ex_date} → {self.pay_date}"
                )
        elif self.pay_date is not None:
            raise CorporateActionError(
                f"분할은 pay_date가 없어야 한다: {self.instrument_id} {self.ex_date}"
            )

    @property
    def is_distribution(self) -> bool:
        return self.event_type == EVENT_DISTRIBUTION

    def to_row(self) -> dict[str, Any]:
        """``corporate_action`` 적재용 값 묶음. ``dataset_id``는 적재 인자로만 받는다."""
        return {
            "instrument_id": self.instrument_id,
            "ex_date": self.ex_date,
            "pay_date": self.pay_date,
            "event_type": self.event_type,
            "amount": self.amount,
        }


def corporate_actions_from_records(
    records: Iterable[Mapping[str, Any]],
) -> list[CorporateAction]:
    """사전 목록을 :class:`CorporateAction` 으로 바꾼다.

    원천의 종목기본정보·분배금 서비스에 활용 승인이 없어(:mod:`data_pipeline.krx` 문서 5번)
    기업행위는 운용사 공시를 옮긴 파일로 들어온다. 그 파일이 어디서 왔는지와 무관하게
    같은 규칙으로 검사하려고 변환을 한 곳에 둔다. 대조는 T009가 맡는다(FR-016).
    """
    actions: list[CorporateAction] = []
    seen: set[tuple[str, date, str]] = set()
    for position, record in enumerate(records):
        if not isinstance(record, Mapping):
            raise CorporateActionError(
                f"{position}번째 기업행위가 매핑이 아니다: {type(record).__name__}"
            )
        missing = sorted({"instrument_id", "ex_date", "event_type", "amount"} - set(record))
        if missing:
            raise CorporateActionError(f"{position}번째 기업행위에 없는 열: {missing}")
        amount = record["amount"]
        action = CorporateAction(
            instrument_id=str(record["instrument_id"]),
            ex_date=record["ex_date"],
            event_type=str(record["event_type"]),
            amount=amount if isinstance(amount, Decimal) else Decimal(str(amount)),
            pay_date=record.get("pay_date"),
        )
        identity = (action.instrument_id, action.ex_date, action.event_type)
        if identity in seen:
            # corporate_action의 PK가 (dataset_id, instrument_id, ex_date, event_type)다.
            # 같은 키가 두 번 있으면 계수를 두 번 곱하게 되므로 적재 전에 막는다.
            raise CorporateActionError(f"같은 기업행위가 두 번 들어 있다: {identity}")
        seen.add(identity)
        actions.append(action)
    return actions


# ---------------------------------------------------------------------------
# 조정 계수
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class EventFactor:
    """한 기업행위가 만든 조정 계수와 그 근거.

    ``previous_close``는 분배 계수의 분모가 된 직전 종가다. 근거를 함께 남기는 이유는
    T009의 공시 대조가 "그 분배금으로 이 계수가 나왔는가"를 확인해야 하기 때문이다.
    """

    action: CorporateAction
    factor: Decimal
    previous_close: Decimal | None

    @property
    def ex_date(self) -> date:
        return self.action.ex_date


def split_factor(ratio: Decimal) -> Decimal:
    """분할 계수. ``1 / 분할비율``이다.

    1주가 2주가 되면 가격이 절반이 되므로 그 이전 구간에 1/2을 곱해 이어 붙인다.
    """
    if not isinstance(ratio, Decimal) or not ratio.is_finite() or ratio <= 0:
        raise CorporateActionError(f"분할비율은 양수 Decimal이어야 한다: {ratio!r}")
    return ONE / ratio


def distribution_factor(previous_close: Decimal, amount_per_share: Decimal) -> Decimal:
    """분배 계수. ``(직전 종가 - 주당 분배금) / 직전 종가``다."""
    if not isinstance(previous_close, Decimal) or not previous_close.is_finite():
        raise CorporateActionError(f"직전 종가는 Decimal이어야 한다: {previous_close!r}")
    if previous_close <= 0:
        raise CorporateActionError(f"직전 종가는 양수여야 한다: {previous_close}")
    if amount_per_share >= previous_close:
        # 분배금이 직전 종가 이상이면 계수가 0 이하가 되어 조정가가 양수 조건을 잃는다.
        # 실제로는 자료가 틀린 경우이므로 값을 깎아 맞추지 않고 알린다(원칙 VI).
        raise CorporateActionError(
            f"주당 분배금이 직전 종가 이상이다: {amount_per_share} >= {previous_close}. "
            "값을 깎아 맞추지 않고 자료를 확인해야 한다"
        )
    return (previous_close - amount_per_share) / previous_close


@dataclass(frozen=True)
class AdjustmentSeries:
    """한 종목의 소급 조정 결과.

    ``factors``는 날짜별 **누적** 계수이고 ``adjusted``는 조정 종가다. 둘을 함께 저장하면
    나중에 ``adjusted_close / adjustment_factor``로 원시 종가를 되짚어 계산을 재현할 수 있다
    (research.md 결정 2 「재현 검증용」).

    ``gaps``는 기업행위를 반영할 수 없어 쓸 수 없는 구간이다(``MISSING_CORPORATE_ACTION``).
    """

    instrument_id: str
    factors: Mapping[date, Decimal]
    adjusted: Mapping[date, Decimal]
    event_factors: tuple[EventFactor, ...]
    gaps: tuple[dict[str, Any], ...]

    def factor_on(self, day: date) -> Decimal:
        try:
            return self.factors[day]
        except KeyError as error:
            raise CorporateActionError(f"{self.instrument_id} {day}의 누적 계수가 없다") from error

    def adjusted_on(self, day: date) -> Decimal:
        try:
            return self.adjusted[day]
        except KeyError as error:
            raise CorporateActionError(f"{self.instrument_id} {day}의 조정 종가가 없다") from error

    def ratio(self, earlier: date, later: date) -> Decimal:
        """두 시점의 조정가 비율. 이 값만 신호·성과 계산에 써도 안전하다 (research.md 결정 2)."""
        return self.adjusted_on(later) / self.adjusted_on(earlier)


def _closes_by_date(
    closes: Mapping[date, Decimal] | Sequence[tuple[date, Decimal]],
) -> dict[date, Decimal]:
    items = closes.items() if isinstance(closes, Mapping) else closes
    mapped: dict[date, Decimal] = {}
    for day, close in items:
        if not isinstance(day, date):
            raise CorporateActionError(f"종가의 날짜가 date가 아니다: {day!r}")
        if not isinstance(close, Decimal):
            raise CorporateActionError(
                f"{day}의 종가가 Decimal이 아니다 (부동소수는 계수를 흔든다): {close!r}"
            )
        if not close.is_finite() or close <= 0:
            raise CorporateActionError(f"{day}의 종가가 양수가 아니다: {close}")
        if day in mapped:
            raise CorporateActionError(f"같은 날짜의 종가가 두 번 들어 있다: {day}")
        mapped[day] = close
    return mapped


def build_adjustment_series(
    instrument_id: str,
    closes: Mapping[date, Decimal] | Sequence[tuple[date, Decimal]],
    actions: Iterable[CorporateAction],
    *,
    actions_known_from: date | None = None,
) -> AdjustmentSeries:
    """원시 종가와 기업행위로 소급 조정 계열을 만든다.

    ``closes``는 **원시 종가**다. 거래정지일처럼 직전 종가를 이어 쓴 날도 그 이어 쓴 값을
    넣는다 — 조정은 원시 계열 위에 한 번만 곱하는 연산이라 어느 날의 원시 종가가 어떻게
    정해졌는지와 무관하게 같은 규칙이 적용된다.

    계산은 날짜를 **뒤에서 앞으로** 훑으며 누적 계수를 곱해 나간다. 앞에서부터 나누는 방식은
    같은 값을 얻지만 나눗셈이 반복되어 정밀도가 떨어진다.

    ``actions_known_from``은 **기업행위 목록이 빠짐없다고 말할 수 있는 가장 이른 날**이다.
    기업행위는 원천 API로 받을 수 없어 운용사 공시를 옮긴 파일로 들어오고(이 모듈의
    :func:`corporate_actions_from_records` 참고), 그 파일이 어느 날부터를 담고 있는지는 파일이
    알려 주는 값이지 코드가 알아낼 수 있는 값이 아니다. 그날보다 앞 구간은 빠진 기업행위가
    있을 수 있으므로 ``MISSING_CORPORATE_ACTION`` 자료 부족 구간으로 남긴다(FR-006).
    누적 계수는 이전 구간 **전체**에 곱해지는 값이라, 하나를 빠뜨리면 그 앞의 조정가가
    모두 틀린다. 그래서 구간도 계열 시작부터 그날 직전까지 전체다.

    **목록 자체가 없으면** :data:`ACTIONS_KNOWN_FROM_NEVER` 를 넣는다. 그러면 계열 전체가
    자료 부족 구간이 된다. 목록이 없는 것과 기업행위가 없는 것은 다른 상태인데, 둘을 같게
    다루면 누적 계수가 전부 1이 되어 조정 종가가 원시 종가와 같아진 채로, 그 사실을 알리는
    기록도 없이 발행된다. ``None``은 "목록이 계열 전 구간을 담았다"는 **부르는 쪽의 주장**이며
    기본값이 아닌 선언으로 쓴다.

    ``ex_date``가 계열의 첫 날보다 앞이거나 같은 분배금은 **곱할 대상이 없어** 건너뛴다.
    그날 이미 가격이 떨어진 뒤의 자료만 갖고 있으므로 조정할 것이 없고, 직전 종가가 없어
    계수를 만들 수도 없다. 이 건너뜀은 자료 부족이 아니다.
    """
    if INSTRUMENT_ID_PATTERN.match(instrument_id) is None:
        raise CorporateActionError(f"종목 식별자가 {{6글자}}.XKRX 형식이 아니다: {instrument_id!r}")
    close_by_date = _closes_by_date(closes)
    days = sorted(close_by_date)
    if actions_known_from is not None and not isinstance(actions_known_from, date):
        raise CorporateActionError(f"actions_known_from은 date여야 한다: {actions_known_from!r}")

    mine = [action for action in actions if action.instrument_id == instrument_id]
    mine.sort(key=lambda action: (action.ex_date, action.event_type))

    with localcontext() as context:
        context.prec = WORKING_PRECISION
        event_factors: list[EventFactor] = []
        for action in mine:
            if action.event_type == EVENT_SPLIT:
                event_factors.append(
                    EventFactor(
                        action=action,
                        factor=split_factor(action.amount),
                        previous_close=None,
                    )
                )
                continue
            previous = _previous_close(days, close_by_date, action.ex_date)
            if previous is None:
                continue
            event_factors.append(
                EventFactor(
                    action=action,
                    factor=distribution_factor(previous, action.amount),
                    previous_close=previous,
                )
            )

        factors: dict[date, Decimal] = {}
        adjusted: dict[date, Decimal] = {}
        descending = sorted(event_factors, key=lambda item: item.ex_date, reverse=True)
        cumulative = ONE
        pointer = 0
        for day in reversed(days):
            # 이 날짜보다 뒤에 ex_date가 있는 계수를 모두 곱해 둔다.
            while pointer < len(descending) and descending[pointer].ex_date > day:
                cumulative *= descending[pointer].factor
                pointer += 1
            factors[day] = +cumulative
            adjusted[day] = +(close_by_date[day] * cumulative)

    gaps = _unknown_action_gaps(instrument_id, days, actions_known_from)
    return AdjustmentSeries(
        instrument_id=instrument_id,
        factors=factors,
        adjusted=adjusted,
        event_factors=tuple(sorted(event_factors, key=lambda item: item.ex_date)),
        gaps=tuple(gaps),
    )


def _previous_close(
    days: Sequence[date], close_by_date: Mapping[date, Decimal], ex_date: date
) -> Decimal | None:
    """``ex_date`` 직전 거래일의 종가. 없으면 ``None``이다."""
    candidate: date | None = None
    for day in days:
        if day >= ex_date:
            break
        candidate = day
    return close_by_date[candidate] if candidate is not None else None


def _unknown_action_gaps(
    instrument_id: str, days: Sequence[date], actions_known_from: date | None
) -> list[dict[str, Any]]:
    """기업행위 목록이 담지 못한 앞 구간을 자료 부족으로 남긴다 (FR-006).

    ``actions_known_from``이 :data:`ACTIONS_KNOWN_FROM_NEVER` 면 모든 날이 그보다 앞이라
    구간이 계열 전체가 된다. 목록이 없을 때 쓰는 길이 이 한 갈래로 모인다.
    """
    if not days or actions_known_from is None:
        return []
    end = max((day for day in days if day < actions_known_from), default=None)
    if end is None:
        # 기업행위 목록이 계열의 첫 날부터를 담고 있다. 모자란 구간이 없다.
        return []
    return [
        {
            "instrument_id": instrument_id,
            "start_date": days[0],
            "end_date": end,
            "reason": REASON_MISSING_CORPORATE_ACTION,
        }
    ]


def quantize_adjusted_close(value: Decimal) -> Decimal:
    """조정 종가를 저장 자리수로 맞춘다 (``NUMERIC(20, 8)``)."""
    return value.quantize(ADJUSTED_CLOSE_EXPONENT, rounding=ROUND_HALF_UP)


def quantize_adjustment_factor(value: Decimal) -> Decimal:
    """누적 계수를 저장 자리수로 맞춘다 (``NUMERIC(20, 12)``)."""
    return value.quantize(ADJUSTMENT_FACTOR_EXPONENT, rounding=ROUND_HALF_UP)


# ---------------------------------------------------------------------------
# 원천 응답에서 분배락 되살리기
# ---------------------------------------------------------------------------

#: 0으로 볼 잔차의 경계(원). 이 값 **이하**는 분배가 아니다.
#:
#: 근거는 둘이다. ⑴ 값은 :class:`~decimal.Decimal` 로 읽으므로 뺄셈 자체에는 오차가 없다.
#: 부동소수로 읽었다면 0.1 + 0.2 ≠ 0.3 같은 어긋남이 그대로 잔차에 섞이지만, 이 파트는 가격을
#: 모두 ``Decimal``로 다루므로(``krx.parse_optional_decimal``) 그 갈래가 없다. ⑵ 남는 것은
#: **원천이 반올림해 준 값끼리 빼면서 생기는 차이**다. 이 서비스의 가격 필드는 소수 둘째 자리
#: 까지 오므로 두 값이 각각 최대 0.005씩 어긋날 수 있고, 잔차에는 최대 0.01이 남는다. 그 절반인
#: 0.005를 경계로 두면 "반올림 때문에 남은 잔차"와 "가장 작은 실제 분배금(원 단위로 오는 1원)"이
#: 섞이지 않는다.
DISTRIBUTION_RESIDUAL_EPSILON: Final[Decimal] = Decimal("0.005")


@dataclass(frozen=True)
class ObservedDistribution:
    """원천 응답만으로 되살린 분배 한 건. ``ex_date``와 금액뿐이고 **지급일은 없다.**

    수식이 주는 것이 분배락일과 주당 분배금 둘뿐이라, 지급일을 요구하는
    :class:`CorporateAction` 으로 바로 바꿀 수 없다. 지급일을 어디서 받을지는 사람이 정할
    문제이므로 여기서 지어내지 않는다(원칙 VI).

    ``previous_close``와 ``change``는 그 금액이 어떻게 나왔는지를 되짚기 위한 근거다.
    """

    instrument_id: str
    ex_date: date
    amount: Decimal
    previous_close: Decimal
    close: Decimal
    change: Decimal


@dataclass(frozen=True)
class DistributionScan:
    """:func:`observe_distributions` 의 결과. 찾은 것과 **판단하지 못한 날**을 함께 담는다.

    ``undecidable``에 담기는 날은 값이 이상해서 버린 날이 아니라 **이 수식으로는 판단할 수 없는
    날**이다(아래 함수 설명의 두 갈래). 결과에 함께 실어 두지 않으면 "분배가 없었다"와 "판단하지
    못했다"가 같은 모양이 되어, 판단하지 못한 날이 늘어도 아무도 모른다.
    """

    instrument_id: str
    distributions: tuple[ObservedDistribution, ...]
    undecidable: tuple[tuple[date, str], ...]


def observe_distributions(quotes: Sequence[DailyQuote]) -> DistributionScan:
    """한 종목의 일봉에서 분배락일과 주당 분배금을 되살린다 (pd01-etf-candidates.md §3).

    ```
    분배금(t) = CMPPREVDD_PRC(t) − ( TDD_CLSPRC(t) − TDD_CLSPRC(t−1) )
    ```

    거래소가 말하는 전일 대비(``CMPPREVDD_PRC``)는 **기준가**에 대고 잰 값이다. 분배락일의
    기준가는 ``직전 종가 − 주당 분배금``이라 종가끼리 뺀 값과 어긋나고, 어긋난 크기가 곧 주당
    분배금이다. 분배가 없던 날은 두 값이 같아 잔차가 0이 된다.

    ``quotes``는 한 종목의 :class:`~data_pipeline.krx.DailyQuote` 나열이며 날짜 오름차순으로
    정렬해 쓴다. **순수 함수다** — DB·파일·네트워크를 건드리지 않는다.

    판단하지 못하는 날은 ``undecidable``로 돌려준다.

    ``no_previous_close``
        계열의 첫날이다. 직전 종가가 없으면 종가 차이를 만들 수 없다.
    ``untraded_basis_price``
        그날 또는 직전 거래일에 **체결이 없었다.** 원천은 체결이 없던 날 종가 자리에 거래소
        기준가를 낸다(``krx`` 모듈 문서 3번). 기준가는 체결가가 아니므로 이 수식의 종가 자리에
        그대로 넣으면 없던 분배가 만들어지거나 있던 분배가 가려진다. 값을 손보는 것은 지어내는
        일이라(원칙 VI) 판단을 미루고, **미뤘다는 사실을 결과에 남긴다.**

    ``CMPPREVDD_PRC``가 없는 날(휴장일의 빈 문자열)은 애초에 행이 만들어지지 않으므로 여기에
    오지 않는다. 그래도 값이 ``None``이면 조용히 넘기지 않고 알린다.

    잔차가 **음수**면 분배가 아니다. 분배금은 양수이고 저장 스키마의
    ``ck_corporate_action_amount_positive``도 그것을 요구한다. 음수는 이 수식의 전제가 깨졌다는
    뜻이므로 버리지 않고 :class:`CorporateActionError` 로 알린다 — 조용히 버리면 원천이 바뀌어도
    아무 일도 일어나지 않는 것처럼 보인다.
    """
    ordered = sorted(quotes, key=lambda quote: quote.date)
    instrument_ids = {quote.instrument_id for quote in ordered}
    if len(instrument_ids) > 1:
        raise CorporateActionError(f"한 종목의 시세만 받는다. 섞여 있다: {sorted(instrument_ids)}")
    instrument_id = next(iter(instrument_ids), "")

    found: list[ObservedDistribution] = []
    undecidable: list[tuple[date, str]] = []
    previous: DailyQuote | None = None
    for quote in ordered:
        if previous is None:
            undecidable.append((quote.date, "no_previous_close"))
            previous = quote
            continue
        if not quote.traded or not previous.traded:
            undecidable.append((quote.date, "untraded_basis_price"))
            previous = quote
            continue
        change = quote.previous_day_change
        if change is None:
            raise CorporateActionError(
                f"{instrument_id} {quote.date}: 전일 대비가 없어 분배를 판단할 수 없다"
            )
        residual = change - (quote.close - previous.close)
        if residual < -DISTRIBUTION_RESIDUAL_EPSILON:
            raise CorporateActionError(
                f"{instrument_id} {quote.date}: 분배금 잔차가 음수다 ({residual}). "
                f"전일 대비 {change}, 종가 {previous.close} → {quote.close}. "
                "분배금은 양수여야 하므로 값을 고치지 않고 자료를 확인해야 한다"
            )
        if residual > DISTRIBUTION_RESIDUAL_EPSILON:
            found.append(
                ObservedDistribution(
                    instrument_id=instrument_id,
                    ex_date=quote.date,
                    amount=residual,
                    previous_close=previous.close,
                    close=quote.close,
                    change=change,
                )
            )
        previous = quote

    return DistributionScan(
        instrument_id=instrument_id,
        distributions=tuple(found),
        undecidable=tuple(undecidable),
    )
