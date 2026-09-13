"""KRX OPEN API 일봉 수집 (spec 001 T007, FR-004·FR-005·FR-012·FR-014).

**네트워크를 쓰지 않는다.** ``tests/fixtures/krx/``에 2026-09-11에 실제 응답을 한 번 받아
저장해 두었고, 이 테스트는 그 파일만 읽는다. 원천의 성질(휴장일에도 행이 오고, 체결이 없던
날은 시가·고가·저가가 ``"0"``이라는 것)은 문서만 보고는 알 수 없어 실제 응답을 근거로 삼는
편이 맞고, 매번 호출하면 이용 한도를 쓰고 결과도 날마다 달라진다.

픽스처가 담은 것: 2026-09-01·02·03·04·06·07·08·09·10 (09-05는 토요일이라 받지 않았다),
종목은 069500(KODEX 200)·091160(KODEX 반도체)·475270(ACE 회사채 액티브) 셋.
"""

from __future__ import annotations

import json
import re
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from pathlib import Path

import pandas as pd
import pytest

from data_pipeline import krx

FIXTURE_ROOT = Path(__file__).resolve().parent / "fixtures" / "krx"

KODEX_200 = "069500.XKRX"
KODEX_SEMICONDUCTOR = "091160.XKRX"
BOND_ACTIVE = "475270.XKRX"

#: 지수 자료가 만든 거래일 달력과 같은 날짜다. 2026-09-05(토)·09-06(일)이 없다.
TRADING_DAYS: tuple[date, ...] = tuple(date(2026, 9, day) for day in (1, 2, 3, 4, 7, 8, 9, 10))

#: 달력 후보 날짜. 휴장일 처리를 확인할 때만 쓴다.
CANDIDATE_DAYS: tuple[date, ...] = tuple(date(2026, 9, day) for day in range(1, 11))

#: 응답을 저장하지 않은 날. 토요일이라 애초에 받지 않았다. 원천은 휴장일 지수 조회에 빈
#: 배열을 주므로(:mod:`data_pipeline.index` 문서) 대역도 그 모양으로 답한다.
CLOSED_DAYS: frozenset[date] = frozenset({date(2026, 9, 5)})

#: 체결이 없던 날. 시가·고가·저가가 ``"0"``이고 종가에 거래소 기준가가 온다 (475270).
UNTRADED_DAYS: tuple[date, ...] = (date(2026, 9, 3), date(2026, 9, 8), date(2026, 9, 10))

#: 인증키 자리에 넣는 가짜 값. 실제 키가 아니며 이 저장소에 실제 키를 적지 않는다 (FR-012).
PLACEHOLDER_KEY = "테스트용-가짜-키"


@dataclass(frozen=True)
class FixtureSource:
    """저장해 둔 응답을 돌려주는 :class:`krx.DailyRowSource` 대역.

    **파일이 없으면 실패한다.** 없는 날짜에 빈 배열을 돌려주면 픽스처가 사라지거나 이름
    규칙이 바뀐 순간부터 모든 테스트가 "휴장일뿐인 구간"을 검사하게 되어, 아무것도 확인하지
    않으면서 초록색으로 남는다.
    """

    root: Path = FIXTURE_ROOT

    def rows(self, group: str, endpoint: str, bas_dd: date) -> list[Mapping[str, str]]:
        if bas_dd in CLOSED_DAYS:
            return []
        path = self.root / f"{endpoint}_{bas_dd:%Y%m%d}.json"
        assert path.exists(), (
            f"픽스처가 없다: {path}. 건너뛰지 않고 실패시키는 이유는 위 docstring에 있다"
        )
        body = json.loads(path.read_text(encoding="utf-8"))
        rows = body[krx.RESPONSE_ROWS_KEY]
        assert isinstance(rows, list), f"{path}의 {krx.RESPONSE_ROWS_KEY}가 배열이 아니다"
        return list(rows)


def fixture_row(endpoint: str, day: date, issue_code: str) -> Mapping[str, str]:
    """픽스처에서 한 종목의 하루치 원본 행을 꺼낸다."""
    for row in FixtureSource().rows("etp", endpoint, day):
        if row.get("ISU_CD") == issue_code:
            return row
    raise AssertionError(f"{endpoint} {day}에 {issue_code} 행이 없다")


def collect(
    *,
    days: tuple[date, ...] = TRADING_DAYS,
    instrument_ids: tuple[str, ...] = (KODEX_200,),
    windows: tuple[krx.ListingWindow, ...] = (),
    source: krx.DailyRowSource | None = None,
) -> krx.KrxCollection:
    return krx.collect_daily_quotes(
        source if source is not None else FixtureSource(),
        days,
        instrument_ids=instrument_ids,
        windows=windows,
    )


class TestColumnStandard:
    def test_frame_columns_match_the_standard(self) -> None:
        """컬럼 표준은 ``date, open, high, low, close, volume, value``다 (data/CLAUDE.md)."""
        frame = krx.quotes_to_frame(collect().for_instrument(KODEX_200))
        assert tuple(frame.columns) == krx.OHLCV_COLUMNS

    def test_korean_response_fields_move_to_standard_names(self) -> None:
        """``TDD_OPNPRC``→``open``, ``ACC_TRDVOL``→``volume``, ``ACC_TRDVAL``→``value``.

        값은 2026-09-10 픽스처의 069500 행 그대로다. 이름만 바꾸고 값을 손대지 않는다.
        """
        frame = krx.quotes_to_frame(collect().for_instrument(KODEX_200))
        # iterrows()를 쓰지 않는다. 수치 행을 float64로 올려 정수 열을 실수로 만든다.
        last = frame.to_dict("records")[-1]
        assert last == {
            "date": date(2026, 9, 10),
            "open": Decimal("111815"),
            "high": Decimal("112405"),
            "low": Decimal("109350"),
            "close": Decimal("111865"),
            "volume": 24_523_748,
            "value": 2_722_508_003_570,
        }

    def test_value_is_not_the_same_as_volume(self) -> None:
        """``value``는 거래대금(원)이고 ``volume``은 거래량(주식 수)이다 (research.md 결정 6)."""
        row = fixture_row("etf_bydd_trd", date(2026, 9, 10), "069500")
        quote = krx.normalize_etf_row(row, expected_date=date(2026, 9, 10))
        assert quote is not None
        assert quote.volume != quote.value

    def test_issue_code_becomes_an_instrument_id(self) -> None:
        assert krx.instrument_id_for("069500") == KODEX_200
        assert collect().instrument_ids == [KODEX_200]

    def test_only_the_requested_instruments_are_kept(self) -> None:
        """조회 단위가 날짜라 하루를 부르면 그날 상장된 ETF 전부가 함께 온다."""
        collection = collect(instrument_ids=(KODEX_200, KODEX_SEMICONDUCTOR))
        assert collection.instrument_ids == [KODEX_200, KODEX_SEMICONDUCTOR]


class TestClosedDays:
    def test_holiday_row_becomes_no_row(self) -> None:
        """휴장일(2026-09-06 일요일)에도 행이 오지만 가격 필드가 모두 빈 문자열이다.

        ``normalize_etf_row``가 ``None``을 돌려주고, 행을 만들지 않는 것이 결측의 표현이다
        (FR-005, 원칙 VI). 0으로 채우면 없던 거래일을 만든다.
        """
        row = fixture_row("etf_bydd_trd", date(2026, 9, 6), "069500")
        assert row["TDD_CLSPRC"] == ""
        assert krx.normalize_etf_row(row, expected_date=date(2026, 9, 6)) is None

    def test_holiday_is_absent_even_if_it_is_requested(self) -> None:
        # 달력 후보 날짜를 그대로 넣어도 휴장일 행이 만들어지지 않는다.
        collection = collect(days=CANDIDATE_DAYS)
        collected = [quote.date for quote in collection.for_instrument(KODEX_200)]
        assert date(2026, 9, 6) not in collected
        assert date(2026, 9, 5) not in collected
        assert collected == list(TRADING_DAYS)


class TestUntradedDays:
    """체결이 없던 날. 원천이 시가·고가·저가를 ``"0"``으로 주고 종가에 기준가를 낸다."""

    def test_zero_prices_mean_no_trade(self) -> None:
        collection = collect(instrument_ids=(BOND_ACTIVE,))
        by_date = {quote.date: quote for quote in collection.for_instrument(BOND_ACTIVE)}
        for day in UNTRADED_DAYS:
            quote = by_date[day]
            assert quote.traded is False
            assert (quote.open, quote.high, quote.low) == (None, None, None)
            # 종가 자리에는 거래소 기준가가 있다. 값이 없는 것은 아니다.
            assert quote.close > 0

    def test_untraded_days_are_absent_from_the_frame(self) -> None:
        """표준 컬럼에는 "체결이 없었다"를 적을 자리가 없다.

        0으로 채우면 OHLC 양수 조건을 어기고 결측으로 채우면 값을 지어내는 셈이므로 그날은
        프레임에 담지 않는다. 그날의 처리는 :mod:`data_pipeline.gaps` 가 직전 종가로 맡는다.
        """
        collection = collect(instrument_ids=(BOND_ACTIVE,))
        frame = krx.quotes_to_frame(collection.for_instrument(BOND_ACTIVE))
        dates = [record["date"] for record in frame.to_dict("records")]
        assert set(UNTRADED_DAYS).isdisjoint(dates)
        assert dates == [day for day in TRADING_DAYS if day not in UNTRADED_DAYS]


class TestListingWindow:
    """상장 전·상장폐지 후 행 부재 (FR-005, SC-003)."""

    def test_a_priced_row_before_listing_stops_the_collection(self) -> None:
        """상장 전 날짜에 **값이 있는** 행이 오면 걸러 넘어가지 않고 수집을 멈춘다.

        대응표의 상장일이 틀렸거나 원천이 다른 종목을 준 상태다. 조용히 버리면 그 사실이
        드러나지 않고, 상장일이 틀린 채로 자료 버전이 발행되면 트리거가 UPDATE·DELETE를
        막아 되돌릴 수 없다 (FR-005, SC-003).
        """
        window = krx.ListingWindow(KODEX_200, listed_on=date(2026, 9, 7))
        with pytest.raises(krx.ListingBoundaryError, match="상장 전"):
            collect(windows=(window,))

    def test_a_priced_row_after_delisting_stops_the_collection(self) -> None:
        window = krx.ListingWindow(
            KODEX_200, listed_on=date(2026, 9, 1), delisted_on=date(2026, 9, 4)
        )
        with pytest.raises(krx.ListingBoundaryError, match="상장폐지"):
            collect(windows=(window,))

    def test_a_window_that_matches_the_source_collects_everything(self) -> None:
        # 상장 구간이 원천과 맞으면 모순이 없으므로 전 거래일이 담긴다. 위 두 테스트가
        # "상장 구간을 좁히면 늘 실패한다"는 뜻이 아님을 이 대조로 확인한다.
        window = krx.ListingWindow(KODEX_200, listed_on=date(2002, 10, 14))
        collection = collect(windows=(window,))
        collected = [quote.date for quote in collection.for_instrument(KODEX_200)]
        assert collected == list(TRADING_DAYS)

    def test_a_holiday_shell_row_outside_the_window_is_not_a_violation(self) -> None:
        # 휴장일에는 원천이 가격 필드가 빈 껍데기 행을 준다(2026-09-06 확인). 값이 없는 행은
        # 가격이 아니므로 상장 구간 밖에 있어도 위반이 아니다. 이것까지 위반으로 보면
        # 상장일 뒤에 시작하는 수집이 휴장일 때문에 멈춘다.
        window = krx.ListingWindow(KODEX_200, listed_on=date(2026, 9, 7))
        collection = krx.collect_daily_quotes(
            FixtureSource(),
            [date(2026, 9, 6), date(2026, 9, 7)],
            instrument_ids=(KODEX_200,),
            windows=(window,),
        )
        assert [quote.date for quote in collection.quotes] == [date(2026, 9, 7)]

    def test_pre_listing_quote_is_rejected(self) -> None:
        """빼 버리지 않고 오류를 낸다. 상장일이 틀린 채로 발행되면 되돌릴 수 없다."""
        quotes = collect().for_instrument(KODEX_200)
        window = krx.ListingWindow(KODEX_200, listed_on=date(2026, 9, 7))
        with pytest.raises(krx.ListingBoundaryError, match="상장 전"):
            krx.enforce_listing_window(quotes, window)

    def test_post_delisting_quote_is_rejected(self) -> None:
        quotes = collect().for_instrument(KODEX_200)
        window = krx.ListingWindow(
            KODEX_200, listed_on=date(2026, 9, 1), delisted_on=date(2026, 9, 4)
        )
        with pytest.raises(krx.ListingBoundaryError, match="상장폐지"):
            krx.enforce_listing_window(quotes, window)

    def test_another_instrument_is_rejected(self) -> None:
        quotes = collect(instrument_ids=(KODEX_SEMICONDUCTOR,)).for_instrument(KODEX_SEMICONDUCTOR)
        window = krx.ListingWindow(KODEX_200, listed_on=date(2026, 9, 1))
        with pytest.raises(krx.ListingBoundaryError, match="다른 종목"):
            krx.enforce_listing_window(quotes, window)

    def test_observed_boundaries_are_not_the_listing_dates(self) -> None:
        """관측 경계는 조회 구간 안의 첫·마지막 날짜일 뿐이다. 대조에만 쓴다."""
        collection = collect(instrument_ids=(KODEX_200, KODEX_SEMICONDUCTOR))
        assert krx.observe_listing_boundaries(collection.quotes) == {
            KODEX_200: (TRADING_DAYS[0], TRADING_DAYS[-1]),
            KODEX_SEMICONDUCTOR: (TRADING_DAYS[0], TRADING_DAYS[-1]),
        }


class TestIdempotency:
    """재실행 안전(멱등). 기간을 다시 수집해도 중복 없이 같은 값이어야 한다 (data/CLAUDE.md)."""

    def test_collecting_twice_gives_the_same_result(self) -> None:
        first = collect(instrument_ids=(KODEX_200, BOND_ACTIVE))
        second = collect(instrument_ids=(KODEX_200, BOND_ACTIVE))
        assert first.quotes == second.quotes
        assert first.collected_dates == second.collected_dates
        assert first.complete == second.complete

    def test_writing_the_same_partition_twice_does_not_multiply_files(self, tmp_path: Path) -> None:
        frame = krx.quotes_to_frame(collect().for_instrument(KODEX_200))
        first = krx.write_processed_parquet(
            frame, tmp_path, dataset="KRX_DAILY_20260910", instrument_id=KODEX_200
        )
        after_first = sorted(path.relative_to(tmp_path) for path in tmp_path.rglob("*.parquet"))
        stored = pd.read_parquet(first[0])

        second = krx.write_processed_parquet(
            frame, tmp_path, dataset="KRX_DAILY_20260910", instrument_id=KODEX_200
        )
        after_second = sorted(path.relative_to(tmp_path) for path in tmp_path.rglob("*.parquet"))

        assert first == second
        assert after_first == after_second
        assert pd.read_parquet(second[0]).equals(stored)

    def test_partition_layout_is_instrument_then_year(self, tmp_path: Path) -> None:
        """파티션 구조는 추후 Spark가 그대로 읽는 전제라 임의로 바꾸지 않는다."""
        frame = krx.quotes_to_frame(collect().for_instrument(KODEX_200))
        written = krx.write_processed_parquet(
            frame, tmp_path, dataset="KRX_DAILY_20260910", instrument_id=KODEX_200
        )
        assert written[0].relative_to(tmp_path).parts == (
            "KRX_DAILY_20260910",
            f"instrument_id={KODEX_200}",
            "year=2026",
            "part-0.parquet",
        )


class TestQuotaLimit:
    """이용 한도 (FR-014, quickstart 시나리오 7)."""

    def test_collection_stops_and_keeps_what_it_has(self) -> None:
        """예외가 밖으로 새어 나가지 않는다.

        그대로 올리면 그때까지 모은 시세가 사라지고, 사라지면 어디까지 모았는지 기록할 수
        없어 부분 갱신으로 발행할 수 없다.
        """
        source = krx.QuotaLimitedSource(inner=FixtureSource(), allowed_calls=2)
        collection = collect(source=source)

        assert collection.complete is False
        assert collection.failure_reason
        assert collection.collected_dates == list(TRADING_DAYS[:2])
        assert collection.quotes
        last = collection.last_collected
        assert last is not None
        assert last < TRADING_DAYS[-1]
        assert collection.missed_dates == list(TRADING_DAYS[2:])

    def test_data_deadline_is_the_last_collected_day(self) -> None:
        """마감일은 요청한 날짜가 아니라 실제로 수집한 마지막 거래일이다."""
        source = krx.QuotaLimitedSource(inner=FixtureSource(), allowed_calls=3)
        collection = collect(source=source)
        assert collection.last_collected == TRADING_DAYS[2]

    def test_fetch_ohlcv_refuses_a_partial_collection(self) -> None:
        """단일 종목 조회는 부분 수집물을 프레임으로 내놓지 않는다.

        프레임만 받는 쪽은 어디까지 모은 것인지 알 수 없어 모자란 구간을 있는 것으로 읽는다.
        """
        source = krx.QuotaLimitedSource(inner=FixtureSource(), allowed_calls=2)
        with pytest.raises(krx.KrxQuotaExhaustedError):
            krx.fetch_ohlcv(
                "069500",
                TRADING_DAYS[0],
                TRADING_DAYS[-1],
                source=source,
                trading_days=list(TRADING_DAYS),
            )


class TestRawArchiving:
    def test_response_body_is_kept_under_the_raw_root(self, tmp_path: Path) -> None:
        """재수집 없이 다시 정제하기 위한 보관이다 (research.md 결정 8)."""
        source = krx.RawArchivingSource(inner=FixtureSource(), root=tmp_path)
        collect(days=(date(2026, 9, 10),), source=source)
        target = tmp_path / "etp" / "etf_bydd_trd" / "20260910.json"
        assert target.exists()
        kept = json.loads(target.read_text(encoding="utf-8"))[krx.RESPONSE_ROWS_KEY]
        assert [row["ISU_CD"] for row in kept] == ["475270", "069500", "091160"]


class TestApiKey:
    """인증키는 ``.env``의 ``KRX_API_KEY``에서만 읽는다 (FR-012)."""

    def test_blank_key_is_reported(self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        # setenv로 먼저 기록해 두면 load_dotenv가 환경을 바꿔도 테스트가 끝날 때 되돌아온다.
        monkeypatch.setenv("KRX_API_KEY", "자리표시자")
        env_file = tmp_path / ".env"
        env_file.write_text("KRX_API_KEY=\n", encoding="utf-8")
        with pytest.raises(krx.KrxConfigError, match="KRX_API_KEY"):
            krx.api_key_from_env(env_file)

    def test_missing_env_file_is_reported(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """없는 경로를 조용히 무시하면 셸에 걸린 다른 키로 호출한다."""
        monkeypatch.setenv("KRX_API_KEY", "자리표시자")
        with pytest.raises(krx.KrxConfigError, match="환경 파일이 없다"):
            krx.api_key_from_env(Path("없는파일.env"))

    def test_client_refuses_a_blank_key(self) -> None:
        with pytest.raises(krx.KrxConfigError):
            krx.KrxOpenApiClient(api_key="   ")

    def test_no_credentials_are_hardcoded(self) -> None:
        """``test_repository.py``의 같은 이름 테스트와 같은 방식이다."""
        source = (Path(__file__).resolve().parents[1] / "src/data_pipeline/krx.py").read_text(
            encoding="utf-8"
        )
        assert re.findall(r'"[A-Za-z0-9]{20,}"', source) == []
        assert 'AUTH_KEY": "' not in source
        assert "KRX_API_KEY=" not in source


@dataclass
class FakeResponse:
    """``requests.Response`` 대역. 응답 모양 검사에 필요한 것만 갖는다."""

    status_code: int
    body: object

    def json(self) -> object:
        if isinstance(self.body, ValueError):
            raise self.body
        return self.body


@dataclass
class FakeSession:
    response: FakeResponse

    def get(self, url: str, **kwargs: object) -> FakeResponse:
        headers = kwargs.get("headers")
        # 키는 헤더로만 보낸다. 질의 문자열에 실리면 서버 접근 기록에 남는다.
        assert isinstance(headers, dict) and "AUTH_KEY" in headers
        assert "AUTH_KEY" not in str(kwargs.get("params"))
        return self.response


def client_for(status_code: int, body: object) -> krx.KrxOpenApiClient:
    return krx.KrxOpenApiClient(
        api_key=PLACEHOLDER_KEY, session=FakeSession(FakeResponse(status_code, body))
    )


class TestResponseShape:
    """응답이 약속한 모양이 아닐 때 값을 지어내지 않고 알린다."""

    def test_missing_rows_key_is_reported(self) -> None:
        client = client_for(200, {"respCode": "00", "respMsg": "정상"})
        with pytest.raises(krx.KrxResponseError, match=krx.RESPONSE_ROWS_KEY):
            client.rows("etp", "etf_bydd_trd", date(2026, 9, 10))

    def test_rows_key_must_be_a_list(self) -> None:
        client = client_for(200, {krx.RESPONSE_ROWS_KEY: {"ISU_CD": "069500"}})
        with pytest.raises(krx.KrxResponseError, match="배열이 아니다"):
            client.rows("etp", "etf_bydd_trd", date(2026, 9, 10))

    def test_row_must_be_a_mapping(self) -> None:
        client = client_for(200, {krx.RESPONSE_ROWS_KEY: ["069500"]})
        with pytest.raises(krx.KrxResponseError, match="매핑이 아니다"):
            client.rows("etp", "etf_bydd_trd", date(2026, 9, 10))

    def test_unapproved_service_is_reported_as_an_auth_error(self) -> None:
        """서비스별로 활용 승인이 따로 필요하다 (krx.py 문서 5번). 종목기본정보가 그 상태다."""
        client = client_for(401, {"respCode": "401", "respMsg": "Unauthorized API Call"})
        with pytest.raises(krx.KrxAuthError, match="활용 승인"):
            client.rows("sto", "stk_isu_base_info", date(2026, 9, 10))

    def test_too_many_requests_is_a_quota_error(self) -> None:
        client = client_for(429, {"respCode": "429", "respMsg": "Too Many Requests"})
        with pytest.raises(krx.KrxQuotaExhaustedError):
            client.rows("etp", "etf_bydd_trd", date(2026, 9, 10))

    def test_basis_date_mismatch_is_reported(self) -> None:
        """원천은 요청한 날짜를 그대로 되돌려 준다. 다르면 엉뚱한 날의 자료를 적재하는 셈이다."""
        row = fixture_row("etf_bydd_trd", date(2026, 9, 10), "069500")
        with pytest.raises(krx.KrxResponseError, match="BAS_DD"):
            krx.normalize_etf_row(row, expected_date=date(2026, 9, 9))

    def test_missing_issue_code_is_reported(self) -> None:
        @dataclass(frozen=True)
        class CodelessSource:
            def rows(self, group: str, endpoint: str, bas_dd: date) -> list[Mapping[str, str]]:
                return [{"BAS_DD": f"{bas_dd:%Y%m%d}", "TDD_CLSPRC": "100"}]

        with pytest.raises(krx.KrxResponseError, match="ISU_CD"):
            collect(days=(date(2026, 9, 10),), source=CodelessSource())

    def test_non_numeric_price_is_reported(self) -> None:
        row = dict(fixture_row("etf_bydd_trd", date(2026, 9, 10), "069500"))
        row["TDD_CLSPRC"] = "많이 올랐다"
        with pytest.raises(krx.KrxResponseError, match="TDD_CLSPRC"):
            krx.normalize_etf_row(row, expected_date=date(2026, 9, 10))


class TestUnsupportedIssueCodes:
    """6자리 숫자가 아닌 종목코드를 멈추지도 버리지도 않는다.

    2026-09-11에 실제 응답에서 확인했다. 그날 값이 있는 ETF 1,168종목 가운데 304종목(26%)의
    코드가 ``0184E0``처럼 숫자와 영문이 섞인 6글자였고, 2025년부터 빠르게 늘고 있다. 저장
    스키마의 ``instrument_id`` 형식이 ``{6자리}.XKRX``라 담을 수 없으므로, 수집이 그 종목
    때문에 멈추지 않으면서도 어떤 코드가 빠졌는지는 드러나야 한다.
    """

    class MixedSource:
        """숫자 코드 하나와 영문 섞인 코드 하나를 함께 주는 원천."""

        def rows(self, group: str, endpoint: str, bas_dd: date) -> list[dict[str, str]]:
            template = {
                "BAS_DD": f"{bas_dd:%Y%m%d}",
                "TDD_CLSPRC": "10000",
                "TDD_OPNPRC": "10000",
                "TDD_HGPRC": "10000",
                "TDD_LWPRC": "10000",
                "ACC_TRDVOL": "100",
                "ACC_TRDVAL": "1000000",
            }
            return [
                {**template, "ISU_CD": "069500", "ISU_NM": "KODEX 200"},
                {**template, "ISU_CD": "0184E0", "ISU_NM": "1Q 200채권혼합50액티브"},
            ]

    def test_collection_does_not_stop(self) -> None:
        collection = krx.collect_daily_quotes(self.MixedSource(), [date(2026, 9, 10)])
        assert collection.complete is True
        assert collection.instrument_ids == [KODEX_200]

    def test_the_code_is_reported_not_dropped(self) -> None:
        collection = krx.collect_daily_quotes(self.MixedSource(), [date(2026, 9, 10)])
        assert collection.unsupported_codes == {"0184E0"}

    def test_the_code_is_reported_even_when_another_instrument_is_requested(self) -> None:
        """찾는 종목을 지정해도 담을 수 없는 코드는 보고된다.

        진입점은 ``--instruments``를 필수로 받고 그 값은 이미 6자리로 검사되므로, 형식 검사가
        걸러내기 **뒤에** 오면 이 목록이 늘 비어 보고도 나오지 않는다. 담을 수 없는 종목이
        원천의 26%라는 사실은 후보를 고르는 쪽에 필요한 정보다.
        """
        collection = krx.collect_daily_quotes(
            self.MixedSource(), [date(2026, 9, 10)], instrument_ids=(KODEX_200,)
        )
        assert collection.unsupported_codes == {"0184E0"}
        # 그러면서도 수집은 멈추지 않고 찾는 종목만 담는다.
        assert collection.instrument_ids == [KODEX_200]

    def test_a_six_digit_code_is_never_reported_as_unsupported(self) -> None:
        assert collect().unsupported_codes == set()


class TestErrorClassification:
    """응답 오류를 어느 종류로 읽는지가 되돌릴 수 없는 결과를 만든다.

    :class:`krx.KrxQuotaExhaustedError` 는 수집이 **일부러 삼켜** 중단으로 처리하므로
    (FR-014), 한도가 아닌 오류를 한도로 분류하면 ``--commit``이 붙은 실행이 "이용 한도로
    중단했다"는 거짓 사유를 달고 되돌릴 수 없는 부분 갱신본을 발행한다. 그래서 분류를
    좁게 두고 여기서 고정한다.
    """

    class Fake:
        """``requests.Response`` 대역. 상태와 본문만 흉내 낸다."""

        def __init__(self, status: int, body: object) -> None:
            self.status_code = status
            self._body = body

        def json(self) -> object:
            if self._body is None:
                raise ValueError("본문이 JSON이 아니다")
            return self._body

    def classify(self, status: int, message: str, resp_code: str | None = None) -> type | None:
        response = self.Fake(status, {"respCode": resp_code or str(status), "respMsg": message})
        try:
            krx._rows_from_response("etp", "etf_bydd_trd", date(2026, 9, 10), response)  # type: ignore[arg-type]
        except Exception as error:  # noqa: BLE001 - 어느 종류인지가 검사 대상이다
            return type(error)
        return None

    def test_a_parameter_error_is_not_a_quota_error(self) -> None:
        # 이 문구에는 "초과"가 들어 있다. 넓은 낱말로 판정하면 한도로 잘못 읽힌다.
        for status in (200, 400):
            assert self.classify(status, "조회 기간을 초과하였습니다") is krx.KrxResponseError

    def test_unapproved_service_is_an_auth_error(self) -> None:
        # 2026-09-11에 실제로 받아 본 응답이다. 인증 판정이 가장 먼저 와야 한다.
        assert self.classify(401, "Unauthorized API Call") is krx.KrxAuthError

    def test_a_bad_key_is_an_auth_error(self) -> None:
        assert self.classify(401, "Unauthorized Key") is krx.KrxAuthError

    def test_too_many_requests_is_a_quota_error(self) -> None:
        assert self.classify(429, "Too Many Requests") is krx.KrxQuotaExhaustedError

    def test_a_quota_word_on_an_error_response_is_a_quota_error(self) -> None:
        assert self.classify(403, "일일 허용량을 모두 사용했습니다") is krx.KrxQuotaExhaustedError

    def test_a_quota_word_on_a_normal_response_is_not_trusted(self) -> None:
        # 200으로 오는 본문의 낱말만으로는 한도로 보지 않는다. 한도 응답은 200일 수 없다.
        assert self.classify(200, "이용 한도") is krx.KrxResponseError

    def test_a_server_error_is_a_response_error(self) -> None:
        assert self.classify(500, "server error") is krx.KrxResponseError

    def test_a_normal_response_needs_the_rows_key(self) -> None:
        assert self.classify(200, "") is krx.KrxResponseError

    def test_auth_is_checked_before_the_quota_words(self) -> None:
        # 401 본문에 한도 낱말이 섞여 있어도 인증 오류다. 순서가 뒤바뀌면 활용 승인이 없는
        # 상태가 한도로 읽혀 조용히 부분 갱신본이 발행된다.
        assert self.classify(401, "이용 한도") is krx.KrxAuthError
