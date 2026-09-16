"""원천 응답만으로 분배락일과 주당 분배금을 되살린다 (pd01-etf-candidates.md §3).

**네트워크를 쓰지 않는다.** ``tests/fixtures/krx/``에 2026-09-11에 한 번 받아 저장해 둔 실제
응답만 읽는다. 추가 API 호출 없이 이미 받은 일봉에서 뽑아내는 것이 이 기능의 핵심이므로,
검증도 같은 응답 위에서 한다.

수식은 다음과 같다. ``CMPPREVDD_PRC``(전일 대비)는 거래소가 **기준가**에 대고 잰 값이라
분배락일에는 종가끼리 뺀 값과 어긋나고, 그 어긋난 크기가 곧 주당 분배금이다.

```
분배금(t) = CMPPREVDD_PRC(t) − ( TDD_CLSPRC(t) − TDD_CLSPRC(t−1) )
```
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from data_pipeline import krx
from data_pipeline.corporate_actions import (
    CorporateActionError,
    DistributionScan,
    observe_distributions,
)

FIXTURE_ROOT = Path(__file__).resolve().parent / "fixtures" / "krx"

INSTRUMENT_ID = "091160.XKRX"

#: 픽스처가 담은 거래일. 2026-09-05(토)는 받지 않았고 09-06(일)은 값이 빈 껍데기 행이다.
FIXTURE_DAYS: tuple[date, ...] = tuple(date(2026, 9, day) for day in (1, 2, 3, 4, 7, 8, 9, 10))

#: 픽스처의 세 종목. 실측 결과를 종목마다 고정한다.
FIXTURE_INSTRUMENTS: tuple[str, ...] = ("069500.XKRX", "091160.XKRX", "475270.XKRX")

#: 체결이 없던 날. 시가·고가·저가가 ``"0"``이고 종가 자리에 거래소 기준가가 온다 (475270).
UNTRADED_DAYS: tuple[date, ...] = (date(2026, 9, 3), date(2026, 9, 8), date(2026, 9, 10))


class FixtureSource:
    """저장해 둔 응답을 돌려주는 :class:`krx.DailyRowSource` 대역.

    **파일이 없으면 실패한다.** 없는 날짜에 빈 배열을 돌려주면 픽스처가 사라진 순간부터
    "아무 날도 없는 구간"을 검사하게 되어, 아무것도 확인하지 않으면서 초록색으로 남는다.
    """

    def rows(self, group: str, endpoint: str, bas_dd: date) -> list[Mapping[str, str]]:
        path = FIXTURE_ROOT / f"{endpoint}_{bas_dd:%Y%m%d}.json"
        assert path.exists(), f"픽스처가 없다: {path}. 건너뛰지 않고 실패시킨다"
        body = json.loads(path.read_text(encoding="utf-8"))
        return list(body[krx.RESPONSE_ROWS_KEY])


def quote(
    day: date,
    close: str,
    change: str | None,
    *,
    traded: bool = True,
    instrument_id: str = INSTRUMENT_ID,
) -> krx.DailyQuote:
    """손으로 만든 시세 한 건. ``traded=False``면 체결이 없던 날의 모양이다."""
    price = Decimal(close)
    return krx.DailyQuote(
        instrument_id=instrument_id,
        date=day,
        close=price,
        open=price if traded else None,
        high=price if traded else None,
        low=price if traded else None,
        volume=1_000 if traded else 0,
        value=10_000_000 if traded else 0,
        previous_day_change=None if change is None else Decimal(change),
    )


D1, D2, D3, D4 = (date(2026, 3, day) for day in (2, 3, 4, 5))


class TestQuoteCarriesTheChange:
    """``CMPPREVDD_PRC``를 수집 자료에 담는다. 없으면 분배를 되살릴 수 없다."""

    def test_normalize_keeps_the_change_including_zero_and_negative(self) -> None:
        # 픽스처의 실제 행으로 확인한다. 069500은 09-02에 내렸고(음수) 475270은 09-01에
        # 보합이다(0). 0을 결측으로 바꾸면 "변동이 없었다"가 "모른다"가 되어 버린다.
        rows = FixtureSource().rows("etp", "etf_bydd_trd", date(2026, 9, 2))
        by_code = {row["ISU_CD"]: row for row in rows}
        fell = krx.normalize_etf_row(by_code["069500"], expected_date=date(2026, 9, 2))
        flat = krx.normalize_etf_row(by_code["475270"], expected_date=date(2026, 9, 2))
        assert fell is not None and flat is not None
        assert fell.previous_day_change == Decimal("-4385")
        assert flat.previous_day_change == Decimal("0")

    def test_a_holiday_row_has_no_change_because_it_has_no_row(self) -> None:
        # 2026-09-06은 일요일이다. 가격 필드가 모두 빈 문자열이라 행 자체가 만들어지지 않는다.
        rows = FixtureSource().rows("etp", "etf_bydd_trd", date(2026, 9, 6))
        assert rows, "휴장일에도 행은 온다 — 값만 비어 있다"
        assert all(
            krx.normalize_etf_row(row, expected_date=date(2026, 9, 6)) is None for row in rows
        )

    def test_a_row_without_the_column_is_not_invented(self) -> None:
        # 원천이 이 열을 빼고 주면 값을 지어내지 않고 ``None``으로 둔다 (원칙 VI).
        row = {
            "BAS_DD": "20260302",
            "ISU_CD": "091160",
            "TDD_CLSPRC": "10000",
            "TDD_OPNPRC": "10000",
            "TDD_HGPRC": "10000",
            "TDD_LWPRC": "10000",
            "ACC_TRDVOL": "100",
            "ACC_TRDVAL": "1000000",
        }
        parsed = krx.normalize_etf_row(row, expected_date=D1)
        assert parsed is not None
        assert parsed.previous_day_change is None


class TestOrdinaryDays:
    def test_no_distribution_when_the_change_matches_the_close_difference(self) -> None:
        quotes = [
            quote(D1, "10000", "0"),
            quote(D2, "10200", "200"),
            quote(D3, "9900", "-300"),
        ]
        scan = observe_distributions(quotes)
        assert scan.distributions == ()

    def test_the_first_day_is_skipped_and_said_so(self) -> None:
        # 직전 종가가 없으면 종가 차이를 만들 수 없다. 건너뛰되 건너뛴 사실을 남긴다.
        scan = observe_distributions([quote(D1, "10000", "55"), quote(D2, "10000", "0")])
        assert scan.distributions == ()
        assert scan.undecidable == ((D1, "no_previous_close"),)


class TestDistributionDay:
    """분배락일의 금액이 수식대로 나온다. 값은 손으로 만든 것이다."""

    def test_amount_is_the_residual_between_change_and_close_difference(self) -> None:
        # 직전 종가 10,000원, 주당 분배금 55원이면 기준가는 9,945원이다. 종가가 10,000원으로
        # 그대로면 거래소가 말하는 전일 대비는 +55원인데 종가 차이는 0이다. 잔차 55원이
        # 곧 주당 분배금이다.
        quotes = [quote(D1, "10000", "0"), quote(D2, "10000", "55")]
        scan = observe_distributions(quotes)
        assert len(scan.distributions) == 1
        found = scan.distributions[0]
        assert found.instrument_id == INSTRUMENT_ID
        assert found.ex_date == D2
        assert found.amount == Decimal("55")
        assert found.previous_close == Decimal("10000")
        assert found.close == Decimal("10000")
        assert found.change == Decimal("55")

    def test_it_also_finds_a_distribution_on_a_day_the_price_fell(self) -> None:
        # 분배락과 하락이 겹쳐도 수식은 같다. 기준가 9,940원에서 9,900원으로 40원 내렸으니
        # 전일 대비는 −40원이고 종가 차이는 −100원이다. 잔차 60원이 분배금이다.
        quotes = [quote(D1, "10000", "0"), quote(D2, "9900", "-40")]
        scan = observe_distributions(quotes)
        assert [(found.ex_date, found.amount) for found in scan.distributions] == [
            (D2, Decimal("60"))
        ]

    def test_a_residual_smaller_than_the_rounding_boundary_is_not_a_distribution(self) -> None:
        # 원천이 소수 둘째 자리까지 반올림해 주므로 잔차에 0.01까지 남을 수 있다. 그 절반인
        # 0.005를 0의 경계로 둔다 (``DISTRIBUTION_RESIDUAL_EPSILON``).
        quotes = [quote(D1, "10000.00", "0"), quote(D2, "10000.00", "0.004")]
        assert observe_distributions(quotes).distributions == ()

    def test_a_residual_just_over_the_boundary_is_a_distribution(self) -> None:
        quotes = [quote(D1, "10000.00", "0"), quote(D2, "10000.00", "0.006")]
        assert [found.amount for found in observe_distributions(quotes).distributions] == [
            Decimal("0.006")
        ]


class TestNegativeResidual:
    """음수는 분배가 아니다. 조용히 버리지 않고 부르는 쪽에 알린다."""

    def test_a_negative_residual_is_raised_not_dropped(self) -> None:
        quotes = [quote(D1, "10000", "0"), quote(D2, "10000", "-40")]
        with pytest.raises(CorporateActionError, match="잔차가 음수다"):
            observe_distributions(quotes)


class TestUntradedDays:
    """체결이 없던 날은 종가 자리에 거래소 기준가가 온다. 그 값으로 판단하지 않는다."""

    def test_an_untraded_day_is_left_undecided(self) -> None:
        quotes = [quote(D1, "10000", "0"), quote(D2, "10050", "50", traded=False)]
        scan = observe_distributions(quotes)
        assert scan.distributions == ()
        assert scan.undecidable == ((D1, "no_previous_close"), (D2, "untraded_basis_price"))

    def test_the_day_after_an_untraded_day_is_also_left_undecided(self) -> None:
        # 직전 종가 자리에 기준가가 들어 있으면 종가 차이가 체결가끼리의 차이가 아니다.
        quotes = [
            quote(D1, "10000", "0"),
            quote(D2, "10050", "50", traded=False),
            quote(D3, "10100", "50"),
        ]
        scan = observe_distributions(quotes)
        assert scan.distributions == ()
        assert [day for day, _ in scan.undecidable] == [D1, D2, D3]

    def test_an_untraded_day_does_not_invent_a_distribution(self) -> None:
        # 기준가를 그대로 종가로 읽었다면 잔차 100원이 나와 없던 분배가 만들어졌을 모양이다.
        quotes = [quote(D1, "10000", "0"), quote(D2, "10000", "100", traded=False)]
        assert observe_distributions(quotes).distributions == ()


class TestMissingChange:
    def test_a_quote_without_the_change_is_not_silently_skipped(self) -> None:
        # 전일 대비가 없으면 판단할 수 없다. 조용히 넘어가면 그 날의 분배락이 영영 사라진다.
        quotes = [quote(D1, "10000", "0"), quote(D2, "10000", None)]
        with pytest.raises(CorporateActionError, match="전일 대비가 없어"):
            observe_distributions(quotes)

    def test_two_instruments_are_refused(self) -> None:
        quotes = [quote(D1, "10000", "0"), quote(D2, "10000", "0", instrument_id="069500.XKRX")]
        with pytest.raises(CorporateActionError, match="한 종목의 시세만"):
            observe_distributions(quotes)


class TestAgainstTheStoredResponse:
    """저장해 둔 실제 응답으로 한 번 돌려 본다. 관측한 그대로를 고정한다.

    pd01-etf-candidates.md §3의 실측은 2026-06-12~09-11 창의 것이고, 픽스처가 담은 것은
    **2026-09-01~09-10**이다. §3이 적어 둔 분배락일(06-29·07-30·08-28)은 모두 이 창 밖이고,
    픽스처의 세 종목에서 실제로 나온 분배도 **한 건도 없다.** 기대값을 억지로 만들지 않고
    관측한 대로 0건을 고정한다. 이 창 안에 분배가 없었다는 뜻이지 종목에 분배가 없다는
    뜻이 아니다.
    """

    def scans(self) -> dict[str, DistributionScan]:
        collection = krx.collect_daily_quotes(
            FixtureSource(), FIXTURE_DAYS, instrument_ids=FIXTURE_INSTRUMENTS
        )
        assert collection.complete
        return {
            instrument_id: observe_distributions(collection.for_instrument(instrument_id))
            for instrument_id in FIXTURE_INSTRUMENTS
        }

    def test_no_distribution_in_the_stored_window(self) -> None:
        assert {key: scan.distributions for key, scan in self.scans().items()} == {
            instrument_id: () for instrument_id in FIXTURE_INSTRUMENTS
        }

    def test_only_the_first_day_is_undecided_for_the_two_traded_instruments(self) -> None:
        scans = self.scans()
        for instrument_id in ("069500.XKRX", "091160.XKRX"):
            assert scans[instrument_id].undecidable == ((FIXTURE_DAYS[0], "no_previous_close"),)

    def test_the_untraded_days_of_the_bond_etf_are_left_undecided(self) -> None:
        # 475270은 09-03·09-08·09-10에 체결이 없었다. 그 날들과 **그 다음 거래일**이 함께
        # 판단 보류가 된다 — 다음 날의 직전 종가 자리에 기준가가 들어 있기 때문이다.
        scan = self.scans()["475270.XKRX"]
        assert [day for day, _ in scan.undecidable] == [
            FIXTURE_DAYS[0],
            date(2026, 9, 3),
            date(2026, 9, 4),
            date(2026, 9, 8),
            date(2026, 9, 9),
            date(2026, 9, 10),
        ]
        assert set(UNTRADED_DAYS) <= {day for day, _ in scan.undecidable}
