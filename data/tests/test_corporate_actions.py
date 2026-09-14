"""기업행위 반영 조정 가격 (spec 001 FR-004, research.md 결정 2).

이 파일이 고정하는 것은 계수 산식 자체보다 **소급 조정 계열의 성질**이다. 절대 수준은 나중에
생긴 분배금에 따라 바뀌고 불변인 것은 두 시점의 비율뿐인데, 이 성질이 깨지면 "그때는 알 수
없었던 정보"로 계산한 결과가 조용히 만들어진다(원칙 V 위반). 숫자는 그럴듯하게 나오므로
테스트가 없으면 드러나지 않는다.
"""

from __future__ import annotations

import inspect
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from data_pipeline import corporate_actions as module
from data_pipeline.corporate_actions import (
    ADJUSTED_CLOSE_EXPONENT,
    ADJUSTMENT_FACTOR_EXPONENT,
    EVENT_DISTRIBUTION,
    EVENT_SPLIT,
    REASON_MISSING_CORPORATE_ACTION,
    CorporateAction,
    CorporateActionError,
    build_adjustment_series,
    corporate_actions_from_records,
    distribution_factor,
    quantize_adjusted_close,
    quantize_adjustment_factor,
    split_factor,
)

INSTRUMENT_ID = "091160.XKRX"

#: 기업행위가 없는 기준 계열. 값은 아무 ETF의 모양이면 되고, 중요한 것은 날짜 간격이다.
CLOSES: dict[date, Decimal] = {
    date(2026, 3, 2): Decimal("10000"),
    date(2026, 3, 3): Decimal("10200"),
    date(2026, 3, 4): Decimal("10100"),
    date(2026, 3, 5): Decimal("10400"),
    date(2026, 3, 6): Decimal("10500"),
}
DAYS = sorted(CLOSES)


def distribution(ex_date: date, amount: str, *, pay_date: date | None = None) -> CorporateAction:
    return CorporateAction(
        instrument_id=INSTRUMENT_ID,
        ex_date=ex_date,
        event_type=EVENT_DISTRIBUTION,
        amount=Decimal(amount),
        pay_date=pay_date or ex_date,
    )


def split(ex_date: date, ratio: str) -> CorporateAction:
    return CorporateAction(
        instrument_id=INSTRUMENT_ID,
        ex_date=ex_date,
        event_type=EVENT_SPLIT,
        amount=Decimal(ratio),
    )


class TestFactors:
    """계수 산식 (research.md 결정 2)."""

    def test_split_factor_is_the_inverse_ratio(self) -> None:
        # 1주가 2주가 되면 가격이 절반이 되므로 그 이전 구간에 1/2을 곱한다.
        assert split_factor(Decimal("2")) == Decimal("0.5")
        assert split_factor(Decimal("4")) == Decimal("0.25")

    def test_reverse_split_factor_is_greater_than_one(self) -> None:
        # 10주가 1주가 되는 병합의 비율은 0.1이고 계수는 10이다. 부호를 뒤집어 쓰면
        # 과거 가격이 100분의 1로 내려가 수익률이 통째로 틀린다.
        assert split_factor(Decimal("0.1")) == Decimal("10")

    def test_distribution_factor_removes_the_payout(self) -> None:
        assert distribution_factor(Decimal("10000"), Decimal("200")) == Decimal("0.98")

    def test_distribution_over_the_previous_close_is_rejected(self) -> None:
        # 계수가 0 이하가 되면 조정가가 양수 조건을 잃는다. 값을 깎아 맞추지 않고 알린다.
        with pytest.raises(CorporateActionError, match="직전 종가 이상"):
            distribution_factor(Decimal("10000"), Decimal("10000"))

    def test_non_positive_ratio_is_rejected(self) -> None:
        with pytest.raises(CorporateActionError):
            split_factor(Decimal("0"))

    def test_float_ratio_is_rejected(self) -> None:
        # 부동소수는 누적 곱셈에서 계수를 흔든다. Decimal만 받는다.
        with pytest.raises(CorporateActionError):
            split_factor(2.0)  # type: ignore[arg-type]


class TestCumulativeProduct:
    """누적 계수가 ``ex_date`` **이전** 구간에만 곱해진다."""

    def test_factor_applies_strictly_before_the_ex_date(self) -> None:
        series = build_adjustment_series(
            INSTRUMENT_ID, CLOSES, [distribution(date(2026, 3, 5), "204")]
        )
        # 직전 종가는 3월 4일의 10100이므로 계수는 (10100 - 204) / 10100이다. 이 나눗셈은
        # 끝나지 않으므로 값 동치가 아니라 저장 자리수로 맞춰 대조한다 — 계열은 작업 정밀도
        # WORKING_PRECISION에서, 이 식은 기본 정밀도에서 반올림되기 때문이다.
        factor = (Decimal("10100") - Decimal("204")) / Decimal("10100")
        assert quantize_adjustment_factor(series.factor_on(date(2026, 3, 4))) == (
            quantize_adjustment_factor(factor)
        )
        assert series.factor_on(date(2026, 3, 2)) == series.factor_on(date(2026, 3, 4))
        # 배당락일 당일과 그 뒤에는 곱하지 않는다. 그날 이미 가격이 떨어졌기 때문이다.
        assert series.factor_on(date(2026, 3, 5)) == Decimal(1)
        assert series.factor_on(date(2026, 3, 6)) == Decimal(1)

    def test_two_events_multiply(self) -> None:
        actions = [distribution(date(2026, 3, 4), "102"), split(date(2026, 3, 6), "2")]
        series = build_adjustment_series(INSTRUMENT_ID, CLOSES, actions)
        # 102 / 10200 = 0.01이라 계수가 0.99로 딱 떨어진다. 유한소수를 고른 이유는 값
        # 동치로 단언하려면 반올림이 끼어들지 않아야 하기 때문이다.
        first = (Decimal("10200") - Decimal("102")) / Decimal("10200")
        assert first == Decimal("0.99")
        assert series.factor_on(date(2026, 3, 3)) == first * Decimal("0.5")
        assert series.factor_on(date(2026, 3, 5)) == Decimal("0.5")
        assert series.factor_on(date(2026, 3, 6)) == Decimal(1)

    def test_adjusted_close_is_the_raw_close_times_the_factor(self) -> None:
        series = build_adjustment_series(INSTRUMENT_ID, CLOSES, [split(date(2026, 3, 5), "2")])
        assert series.adjusted_on(date(2026, 3, 4)) == Decimal("10100") * Decimal("0.5")
        assert series.adjusted_on(date(2026, 3, 5)) == Decimal("10400")

    def test_raw_close_can_be_recovered(self) -> None:
        # 계수를 함께 저장하는 까닭이다 (research.md 결정 2 「재현 검증용」).
        # 101 / 10100 = 0.01이라 계수가 0.99로 떨어진다. 되짚는 나눗셈이 반올림 없이
        # 원시 종가로 돌아오는지 보려면 계수가 유한소수여야 한다.
        series = build_adjustment_series(
            INSTRUMENT_ID, CLOSES, [distribution(date(2026, 3, 5), "101")]
        )
        assert series.factor_on(date(2026, 3, 4)) == Decimal("0.99")
        for day in DAYS:
            assert series.adjusted_on(day) / series.factor_on(day) == CLOSES[day]

    def test_other_instruments_actions_are_ignored(self) -> None:
        other = CorporateAction(
            instrument_id="069500.XKRX",
            ex_date=date(2026, 3, 5),
            event_type=EVENT_SPLIT,
            amount=Decimal("2"),
        )
        series = build_adjustment_series(INSTRUMENT_ID, CLOSES, [other])
        assert all(series.factor_on(day) == Decimal(1) for day in DAYS)


class TestRatioInvariance:
    """**과거 두 시점의 비율은 뒤에 생긴 기업행위에 영향받지 않는다.**

    이 묶음이 이 파일의 핵심이다. 뒤에 생긴 계수가 분자와 분모에 똑같이 들어가 상쇄되므로
    비율은 그대로여야 하고, 그 덕분에 비율 기반 신호(모멘텀·변동성)가 미래 정보를 쓰지 않는다.

    계수가 **유한소수**인 경우에는 **값 동치**로 단언한다. 곱셈이 정확하고 ``Decimal`` 나눗셈이
    정확한 몫을 올림하므로, 분자와 분모에 같은 값을 곱한 비율은 한 자리도 달라지지 않는다.
    계수가 무한소수인 경우에는 작업 정밀도(``WORKING_PRECISION`` = 50자리)에서 비롯한 오차가
    남으므로 그만큼의 차이로 단언하고, 아래 테스트에 그 자리수를 명시한다. 근사 비교를
    기본으로 두지 않는 이유는 실제로 어긋나기 시작해도 한동안 통과하기 때문이다.
    """

    EARLIER = date(2026, 3, 3)
    LATER = date(2026, 3, 4)

    def test_a_later_distribution_does_not_move_the_past_ratio(self) -> None:
        before = build_adjustment_series(INSTRUMENT_ID, CLOSES, [])
        after = build_adjustment_series(
            INSTRUMENT_ID, CLOSES, [distribution(date(2026, 3, 6), "104")]
        )
        # 배당락일 직전 거래일은 03-05이고 그날 종가가 10400이다. 104 / 10400 = 0.01이라
        # 계수가 0.99로 떨어진다. 값 동치로 단언할 수 있는 조건이다.
        assert after.factor_on(self.EARLIER) == Decimal("0.99")
        assert after.ratio(self.EARLIER, self.LATER) == before.ratio(self.EARLIER, self.LATER)

    def test_a_later_distribution_with_a_repeating_factor_keeps_the_ratio(self) -> None:
        # 204 / 10500은 끝나지 않는 소수라 계수가 작업 정밀도에서 반올림된다. 그래도 비율은
        # 50자리 가운데 마지막 몇 자리에서만 흔들리므로, 1e-40이면 충분히 좁은 잣대다.
        # 상쇄가 깨지면 차이가 분배금 비율만큼(1e-2 규모) 벌어져 이 잣대에 걸린다.
        before = build_adjustment_series(INSTRUMENT_ID, CLOSES, [])
        after = build_adjustment_series(
            INSTRUMENT_ID, CLOSES, [distribution(date(2026, 3, 6), "204")]
        )
        assert after.factor_on(self.EARLIER) != Decimal("0.99")
        difference = after.ratio(self.EARLIER, self.LATER) - before.ratio(self.EARLIER, self.LATER)
        assert abs(difference) < Decimal("1e-40")

    def test_a_later_split_does_not_move_the_past_ratio(self) -> None:
        before = build_adjustment_series(INSTRUMENT_ID, CLOSES, [])
        after = build_adjustment_series(INSTRUMENT_ID, CLOSES, [split(date(2026, 3, 6), "2")])
        assert after.factor_on(self.EARLIER) == Decimal("0.5")
        assert after.ratio(self.EARLIER, self.LATER) == before.ratio(self.EARLIER, self.LATER)

    def test_a_later_split_with_a_repeating_factor_keeps_the_ratio(self) -> None:
        # 1 / 3은 끝나지 않는다. 분할비율이 정수가 아닌 경우(예: 3주가 1주가 되는 병합의
        # 반대)에도 상쇄가 유지되는지 본다.
        before = build_adjustment_series(INSTRUMENT_ID, CLOSES, [])
        after = build_adjustment_series(INSTRUMENT_ID, CLOSES, [split(date(2026, 3, 6), "3")])
        difference = after.ratio(self.EARLIER, self.LATER) - before.ratio(self.EARLIER, self.LATER)
        assert abs(difference) < Decimal("1e-40")

    def test_events_added_one_after_another_leave_the_ratio_alone(self) -> None:
        # 분배금이 여러 번 더 생기는 상황이다. 실제 운영에서 자료를 새로 확보할 때마다
        # 벌어지는 일이며, 그때마다 과거 비율이 흔들리면 같은 백테스트의 결과가 달라진다.
        baseline = build_adjustment_series(INSTRUMENT_ID, CLOSES, [])
        expected = baseline.ratio(self.EARLIER, self.LATER)
        actions: list[CorporateAction] = []
        # 직전 거래일 종가가 각각 10100·10400이므로 101·104를 고르면 계수가 둘 다 0.99로
        # 떨어진다. 유한소수여야 값 동치로 단언할 수 있다.
        for ex_date, amount in (
            (date(2026, 3, 5), "101"),
            (date(2026, 3, 6), "104"),
        ):
            actions.append(distribution(ex_date, amount))
            series = build_adjustment_series(INSTRUMENT_ID, CLOSES, actions)
            assert series.ratio(self.EARLIER, self.LATER) == expected

    def test_an_event_between_the_two_dates_does_move_the_ratio(self) -> None:
        # 사이에 낀 기업행위는 상쇄되지 않는다. 상쇄되면 조정이 아무 일도 하지 않는다는 뜻이라,
        # 위 불변성이 "계수가 늘 1이어서" 성립하는 것이 아님을 이 대조로 확인한다.
        before = build_adjustment_series(INSTRUMENT_ID, CLOSES, [])
        after = build_adjustment_series(INSTRUMENT_ID, CLOSES, [split(date(2026, 3, 4), "2")])
        assert after.ratio(self.EARLIER, self.LATER) != before.ratio(self.EARLIER, self.LATER)

    def test_the_absolute_level_does_move(self) -> None:
        # 절대 수준은 바뀐다. 이것이 "조정가의 절대 수준을 판단 기준으로 쓰지 말라"는
        # 제약의 근거이며(contracts/market-dataset.md), 바뀌지 않는다면 그 제약이 필요 없다.
        before = build_adjustment_series(INSTRUMENT_ID, CLOSES, [])
        after = build_adjustment_series(
            INSTRUMENT_ID, CLOSES, [distribution(date(2026, 3, 6), "104")]
        )
        assert after.adjusted_on(self.EARLIER) < before.adjusted_on(self.EARLIER)

    def test_stored_precision_keeps_the_ratio_within_the_column_scale(self) -> None:
        # 저장 자리수로 깎은 뒤의 비율도 확인한다. adjusted_close가 NUMERIC(20, 8)이라
        # 소수 8자리에서 반올림되므로 값 동치가 아니라 그 자리수만큼의 차이로 단언한다.
        before = build_adjustment_series(INSTRUMENT_ID, CLOSES, [])
        after = build_adjustment_series(
            INSTRUMENT_ID, CLOSES, [distribution(date(2026, 3, 6), "104")]
        )

        def stored_ratio(series: module.AdjustmentSeries) -> Decimal:
            earlier = quantize_adjusted_close(series.adjusted_on(self.EARLIER))
            later = quantize_adjusted_close(series.adjusted_on(self.LATER))
            return later / earlier

        assert abs(stored_ratio(after) - stored_ratio(before)) < Decimal("1e-10")


class TestNoDoubleFeeDeduction:
    """운용보수를 다시 차감하지 않는다 (FR-004).

    ETF 내부 보수는 매일 순자산에서 차감되어 이미 종가에 녹아 있다. 백테스트에서 연 보수율을
    또 빼면 같은 비용을 두 번 계산한다.
    """

    def test_without_events_the_adjusted_series_equals_the_raw_series(self) -> None:
        series = build_adjustment_series(INSTRUMENT_ID, CLOSES, [])
        for day in DAYS:
            assert series.factor_on(day) == Decimal(1)
            # 근사가 아니라 값 동치다. 보수를 조금이라도 깎으면 여기서 드러난다.
            assert series.adjusted_on(day) == CLOSES[day]

    def test_without_events_the_adjusted_return_equals_the_raw_return(self) -> None:
        series = build_adjustment_series(INSTRUMENT_ID, CLOSES, [])
        for earlier, later in zip(DAYS, DAYS[1:], strict=False):
            assert series.ratio(earlier, later) == CLOSES[later] / CLOSES[earlier]

    def test_a_long_series_does_not_drift(self) -> None:
        # 기간이 길어질수록 커지는 차이가 보수 중복 차감의 모양이다. 같은 종가가 이어지는
        # 계열에서 첫날과 마지막 날의 비율이 정확히 1이어야 한다.
        flat = {date(2026, 1, 1 + offset): Decimal("10000") for offset in range(28)}
        series = build_adjustment_series(INSTRUMENT_ID, flat, [])
        days = sorted(flat)
        assert series.ratio(days[0], days[-1]) == Decimal(1)

    def test_the_module_takes_no_fee_argument(self) -> None:
        # 인자가 없으면 보수를 받아 깎는 경로가 생길 수 없다. 인자 이름으로 확인하는 이유는
        # 계산 결과만 보면 "보수율 0을 기본값으로 둔" 구현과 구분되지 않기 때문이다.
        forbidden = ("fee", "expense", "cost", "ter", "보수")
        for name, function in vars(module).items():
            if not callable(function) or name.startswith("_"):
                continue
            try:
                parameters = inspect.signature(function).parameters
            except (TypeError, ValueError):
                continue
            for parameter in parameters:
                assert not any(word in parameter.lower() for word in forbidden), (
                    f"{name}에 보수처럼 보이는 인자가 있다: {parameter}"
                )

    def test_the_source_has_no_annual_fee_constant(self) -> None:
        source = Path(module.__file__).read_text(encoding="utf-8")
        lowered = source.lower()
        for marker in ("expense_ratio", "annual_fee", "management_fee"):
            assert marker not in lowered, f"보수율 상수가 들어 있다: {marker}"


class TestMissingCorporateAction:
    """기업행위 목록이 담지 못한 구간을 자료 부족으로 남긴다 (FR-006).

    기업행위는 원천 API로 받을 수 없어(활용 승인이 없다) 운용사 공시를 옮긴 파일로 들어온다.
    그 파일이 어느 날부터를 담고 있는지는 파일이 알려 주는 값이며 코드가 알아낼 수 없다.
    그래서 ``actions_known_from``으로 받고, 그보다 앞 구간은 빠진 기업행위가 있을 수 있으므로
    쓰지 않는다.
    """

    def test_no_gap_when_the_list_covers_the_whole_series(self) -> None:
        series = build_adjustment_series(INSTRUMENT_ID, CLOSES, [], actions_known_from=DAYS[0])
        assert series.gaps == ()

    def test_no_gap_when_the_coverage_start_is_unknown(self) -> None:
        # 값을 주지 않으면 구간을 만들지 않는다. 모른다는 사실을 자료 부족으로 옮겨 적으면
        # 기업행위가 없는 종목까지 전 구간이 제외된다.
        assert build_adjustment_series(INSTRUMENT_ID, CLOSES, []).gaps == ()

    def test_the_range_before_the_coverage_start_is_marked(self) -> None:
        series = build_adjustment_series(
            INSTRUMENT_ID, CLOSES, [], actions_known_from=date(2026, 3, 5)
        )
        assert len(series.gaps) == 1
        gap = series.gaps[0]
        assert gap["instrument_id"] == INSTRUMENT_ID
        assert gap["reason"] == REASON_MISSING_CORPORATE_ACTION
        # 계열 시작부터 그날 직전 거래일까지 **전체**다. 누적 계수가 이전 구간 전체에
        # 곱해지는 값이라 하나를 빠뜨리면 그 앞의 조정가가 모두 틀린다.
        assert gap["start_date"] == DAYS[0]
        assert gap["end_date"] == date(2026, 3, 4)

    def test_a_coverage_start_after_the_series_marks_everything(self) -> None:
        series = build_adjustment_series(
            INSTRUMENT_ID, CLOSES, [], actions_known_from=date(2026, 4, 1)
        )
        assert series.gaps[0]["start_date"] == DAYS[0]
        assert series.gaps[0]["end_date"] == DAYS[-1]

    def test_an_empty_series_makes_no_gap(self) -> None:
        series = build_adjustment_series(INSTRUMENT_ID, {}, [], actions_known_from=date(2026, 3, 5))
        assert series.gaps == ()

    def test_a_distribution_before_the_first_close_is_skipped(self) -> None:
        # ex_date가 계열의 첫 날보다 앞이면 곱할 대상이 없다. 그날 이미 가격이 떨어진 뒤의
        # 자료만 갖고 있으므로 조정할 것이 없고, 이 건너뜀은 자료 부족이 아니다.
        series = build_adjustment_series(
            INSTRUMENT_ID, CLOSES, [distribution(date(2026, 3, 1), "100")]
        )
        assert series.gaps == ()
        assert series.event_factors == ()
        assert all(series.factor_on(day) == Decimal(1) for day in DAYS)

    def test_a_distribution_on_the_first_day_is_skipped(self) -> None:
        # 첫날에는 직전 종가가 없어 분배 계수를 만들 수 없다. 값을 지어내지 않는다.
        series = build_adjustment_series(INSTRUMENT_ID, CLOSES, [distribution(DAYS[0], "100")])
        assert series.event_factors == ()
        assert all(series.factor_on(day) == Decimal(1) for day in DAYS)

    def test_a_split_on_the_first_day_still_applies_nowhere(self) -> None:
        # 분할은 직전 종가가 필요 없으므로 계수가 만들어지지만, 첫날보다 앞에 곱할 날짜가
        # 없어 계열은 그대로다. 계수를 버리지 않는 이유는 대조(T009)가 그 근거를 쓰기 때문이다.
        series = build_adjustment_series(INSTRUMENT_ID, CLOSES, [split(DAYS[0], "2")])
        assert len(series.event_factors) == 1
        assert all(series.factor_on(day) == Decimal(1) for day in DAYS)

    def test_the_gap_reason_is_never_suspended_too_long(self) -> None:
        # SUSPENDED_TOO_LONG은 PD-19·PD-20 확정 전까지 쓰지 않는다 (FR-006).
        series = build_adjustment_series(
            INSTRUMENT_ID, CLOSES, [], actions_known_from=date(2026, 3, 5)
        )
        assert all(gap["reason"] != "SUSPENDED_TOO_LONG" for gap in series.gaps)

    def test_a_bad_coverage_start_is_rejected(self) -> None:
        with pytest.raises(CorporateActionError, match="actions_known_from"):
            build_adjustment_series(
                INSTRUMENT_ID,
                CLOSES,
                [],
                actions_known_from="2026-03-05",  # type: ignore[arg-type]
            )


class TestCorporateActionValue:
    """``corporate_action`` 행의 불변식. 마이그레이션의 CHECK와 같은 조건이다."""

    def test_distribution_requires_a_pay_date(self) -> None:
        with pytest.raises(CorporateActionError, match="pay_date가 있어야"):
            CorporateAction(INSTRUMENT_ID, date(2026, 3, 4), EVENT_DISTRIBUTION, Decimal("100"))

    def test_pay_date_before_the_ex_date_is_rejected(self) -> None:
        with pytest.raises(CorporateActionError, match="ex_date 이후"):
            CorporateAction(
                INSTRUMENT_ID,
                date(2026, 3, 4),
                EVENT_DISTRIBUTION,
                Decimal("100"),
                pay_date=date(2026, 3, 3),
            )

    def test_split_must_not_carry_a_pay_date(self) -> None:
        with pytest.raises(CorporateActionError, match="pay_date가 없어야"):
            CorporateAction(
                INSTRUMENT_ID,
                date(2026, 3, 4),
                EVENT_SPLIT,
                Decimal("2"),
                pay_date=date(2026, 3, 6),
            )

    def test_pay_date_may_equal_the_ex_date(self) -> None:
        action = CorporateAction(
            INSTRUMENT_ID,
            date(2026, 3, 4),
            EVENT_DISTRIBUTION,
            Decimal("100"),
            pay_date=date(2026, 3, 4),
        )
        assert action.pay_date == action.ex_date

    def test_unknown_event_type_is_rejected(self) -> None:
        with pytest.raises(CorporateActionError, match="event_type"):
            CorporateAction(INSTRUMENT_ID, date(2026, 3, 4), "MERGER", Decimal("2"))

    def test_non_positive_amount_is_rejected(self) -> None:
        with pytest.raises(CorporateActionError, match="양수"):
            CorporateAction(INSTRUMENT_ID, date(2026, 3, 4), EVENT_SPLIT, Decimal("0"))

    def test_bad_instrument_id_is_rejected(self) -> None:
        with pytest.raises(CorporateActionError, match="XKRX"):
            CorporateAction("091160", date(2026, 3, 4), EVENT_SPLIT, Decimal("2"))

    def test_trailing_newline_does_not_slip_through(self) -> None:
        # 정규식을 `$`로 닫으면 끝에 붙은 줄바꿈 앞에서도 맞아 이 값이 통과한다.
        with pytest.raises(CorporateActionError, match="XKRX"):
            CorporateAction("091160.XKRX\n", date(2026, 3, 4), EVENT_SPLIT, Decimal("2"))

    def test_row_columns_match_the_migration(self) -> None:
        row = split(date(2026, 3, 4), "2").to_row()
        assert set(row) == {"instrument_id", "ex_date", "pay_date", "event_type", "amount"}
        # dataset_id는 적재 인자로만 받는다 (repository.py의 관례).
        assert "dataset_id" not in row

    def test_records_are_converted_and_deduplicated(self) -> None:
        records = [
            {
                "instrument_id": INSTRUMENT_ID,
                "ex_date": date(2026, 3, 4),
                "event_type": EVENT_SPLIT,
                "amount": "2",
            }
        ]
        assert corporate_actions_from_records(records)[0].amount == Decimal("2")
        with pytest.raises(CorporateActionError, match="두 번"):
            corporate_actions_from_records(records * 2)

    def test_missing_column_is_reported(self) -> None:
        with pytest.raises(CorporateActionError, match="없는 열"):
            corporate_actions_from_records([{"instrument_id": INSTRUMENT_ID}])


class TestClosesInput:
    """조정 계산의 입력 검사. 값을 지어내지 않는다 (원칙 VI)."""

    def test_float_close_is_rejected(self) -> None:
        with pytest.raises(CorporateActionError, match="Decimal"):
            build_adjustment_series(INSTRUMENT_ID, {date(2026, 3, 2): 10000.0}, [])  # type: ignore[dict-item]

    def test_non_positive_close_is_rejected(self) -> None:
        with pytest.raises(CorporateActionError, match="양수"):
            build_adjustment_series(INSTRUMENT_ID, {date(2026, 3, 2): Decimal("0")}, [])

    def test_duplicate_date_is_rejected(self) -> None:
        with pytest.raises(CorporateActionError, match="두 번"):
            build_adjustment_series(
                INSTRUMENT_ID,
                [(date(2026, 3, 2), Decimal("1")), (date(2026, 3, 2), Decimal("2"))],
                [],
            )

    def test_empty_series_is_allowed(self) -> None:
        # 상장 직후라 아직 거래일이 없는 종목이 있을 수 있다. 빈 계열은 오류가 아니다.
        series = build_adjustment_series(INSTRUMENT_ID, {}, [])
        assert series.factors == {}

    def test_missing_day_is_reported(self) -> None:
        series = build_adjustment_series(INSTRUMENT_ID, CLOSES, [])
        with pytest.raises(CorporateActionError, match="누적 계수가 없다"):
            series.factor_on(date(2026, 3, 7))


class TestStoredScale:
    """저장 자리수를 마이그레이션과 맞춘다."""

    def test_exponents_match_the_migration(self) -> None:
        migrations = sorted(
            (Path(__file__).resolve().parents[2] / "backend/src/main/resources/db/migration").glob(
                "V*__etf_market_data.sql"
            )
        )
        assert migrations, (
            "V*__etf_market_data.sql을 찾지 못했다. 이름 규칙이 바뀌었다면 이 대조를 건너뛰지"
            " 말고 여기를 고쳐야 한다"
        )
        sql = migrations[-1].read_text(encoding="utf-8")
        assert "adjusted_close        NUMERIC(20, 8)" in sql
        assert "adjustment_factor     NUMERIC(20, 12)" in sql
        assert ADJUSTED_CLOSE_EXPONENT == Decimal("1e-8")
        assert ADJUSTMENT_FACTOR_EXPONENT == Decimal("1e-12")

    def test_quantize_keeps_the_column_scale(self) -> None:
        assert quantize_adjusted_close(Decimal("1.123456789")) == Decimal("1.12345679")
        assert quantize_adjustment_factor(Decimal("0.1234567890123456")) == Decimal(
            "0.123456789012"
        )
