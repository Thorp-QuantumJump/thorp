"""수집 실행 경로를 저장된 표본으로 끝까지 돌린다 (tasks.md T080·T081·T082).

**네트워크를 쓰지 않는다.** `Fetcher` 자리에 표본을 돌려주는 대역을 끼운다 — 그래서 인증키 없이도
어댑터·변환·정규화가 이어 붙는지 확인할 수 있다(FR-022).

여기서 보는 것은 값의 정확성이 아니다. 그건 어댑터·변환 테스트가 이미 본다. 이 파일이 보는 것은
**조각들이 실제로 이어지는가**다 — 지금까지 조각은 다 있었는데 부르는 코드가 없어 한 번도 연속으로
돌아본 적이 없었다.
"""

from __future__ import annotations

import json
import pathlib
from datetime import date

import pytest

from data_pipeline.macro import ingest, registry
from data_pipeline.macro.config import SourceConfig
from data_pipeline.macro.sources.base import (
    PermanentSourceError,
    QuotaExhaustedError,
    TransientSourceError,
)

FIXTURES = pathlib.Path(__file__).parent / "fixtures" / "macro"


class FixtureFetcher:
    """요청을 표본 파일로 바꿔 돌려주는 대역. 무엇을 불렀는지도 남긴다."""

    def __init__(self, bodies: dict[str, str], default: str | None = None) -> None:
        self._bodies = bodies
        self._default = default
        self.calls: list[tuple[str, dict[str, str] | None]] = []

    def get(
        self,
        url: str,
        params: dict[str, str] | None = None,
        headers: dict[str, str] | None = None,
    ) -> str:
        self.calls.append((url, params))
        for marker, body in self._bodies.items():
            if marker in url or (params and marker in str(params)):
                return body
        if self._default is None:
            raise AssertionError(f"표본이 없는 요청이다: {url} {params}")
        return self._default


class FailingFetcher:
    def __init__(self, error: Exception) -> None:
        self._error = error

    def get(
        self,
        url: str,
        params: dict[str, str] | None = None,
        headers: dict[str, str] | None = None,
    ) -> str:
        raise self._error


def _config(source_id: str) -> SourceConfig:
    """재시도 대기 없이 도는 설정. 테스트가 실제로 1·2·4초를 자지 않게 한다."""
    return SourceConfig(
        source_id=source_id,
        base_url="https://example.invalid",
        api_key_env=None,
        timeout_seconds=1.0,
        max_attempts=1,
        backoff_seconds=0.01,
        backoff_multiplier=1.0,
    )


def _indicator(indicator_id: str) -> registry.IndicatorDefinition:
    return next(i for i in registry.INDICATORS if i.indicator_id == indicator_id)


def _ecos_body() -> str:
    return (FIXTURES / "ecos" / "dubai_crude_monthly.json").read_text(encoding="utf-8")


def test_collects_and_saves_raw(tmp_path):
    """받은 본문이 파일로 남고 해시가 붙는다 (T080)."""
    indicator = _indicator("dubai_crude")
    fetcher = FixtureFetcher({}, default=_ecos_body())

    outcomes = ingest.collect(
        [indicator],
        fetcher=fetcher,
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 9, 20),
        configs={"ecos": _config("ecos")},
    )

    assert len(outcomes) == 1
    outcome = outcomes[0]
    assert outcome.succeeded
    assert outcome.observations, "관측이 하나도 안 나왔다"
    assert len(outcome.raw_documents) == 1
    document = outcome.raw_documents[0]
    assert document.path.exists()
    assert document.path.read_text(encoding="utf-8") == _ecos_body()
    assert len(document.sha256) == 64


def test_raw_path_is_under_data_root(tmp_path):
    """원본은 `raw/macro/{원천}/{지표}/{연도}/` 아래에 쌓인다."""
    fetcher = FixtureFetcher({}, default=_ecos_body())

    outcomes = ingest.collect(
        [_indicator("dubai_crude")],
        fetcher=fetcher,
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 9, 20),
        configs={"ecos": _config("ecos")},
    )

    path = outcomes[0].raw_documents[0].path
    assert path.is_relative_to(tmp_path / "raw" / "macro" / "ecos" / "dubai_crude")


def test_failure_does_not_stop_other_indicators(tmp_path):
    """한 지표가 실패해도 나머지를 계속 받는다. 실패는 그 지표에만 남는다."""
    good = _indicator("dubai_crude")
    bad = _indicator("treasury_bond_3y")

    class Selective:
        def get(self, url, params=None, headers=None):
            if "817Y002" in url:
                raise TransientSourceError("끊김")
            return _ecos_body()

    outcomes = ingest.collect(
        [good, bad],
        fetcher=Selective(),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 9, 20),
        configs={"ecos": _config("ecos")},
    )

    by_id = {outcome.indicator_id: outcome for outcome in outcomes}
    assert by_id["dubai_crude"].succeeded
    assert not by_id["treasury_bond_3y"].succeeded
    assert by_id["treasury_bond_3y"].status == ingest.STATUS_FAILED


@pytest.mark.parametrize(
    ("error", "expected"),
    [
        (QuotaExhaustedError("한도"), ingest.STATUS_QUOTA),
        (PermanentSourceError("거절"), ingest.STATUS_FAILED),
        (TransientSourceError("끊김"), ingest.STATUS_FAILED),
    ],
)
def test_failures_keep_their_kind(tmp_path, error, expected):
    """한도 도달과 그냥 실패를 한 덩어리로 묶지 않는다 (FR-011).

    사람이 할 다음 행동이 다르다 — 한도는 기다리면 되고 거절은 설정을 봐야 한다.
    """
    outcomes = ingest.collect(
        [_indicator("dubai_crude")],
        fetcher=FailingFetcher(error),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 9, 20),
        configs={"ecos": _config("ecos")},
    )

    assert outcomes[0].status == expected
    assert outcomes[0].error_class == type(error).__name__


def test_regular_mode_asks_for_a_shorter_window(tmp_path):
    """정기 수집은 전 구간이 아니라 최근 N개월만 부른다 (FR-017)."""
    fetcher = FixtureFetcher({}, default=_ecos_body())

    ingest.collect(
        [_indicator("dubai_crude")],
        fetcher=fetcher,
        data_root=tmp_path,
        mode="regular",
        today=date(2026, 9, 20),
        configs={"ecos": _config("ecos")},
    )

    url = fetcher.calls[0][0]
    assert "202606" in url, f"최근 3개월 시작월이 없다: {url}"
    assert "199201" not in url, "정기 수집인데 전 구간을 불렀다"


def test_initial_mode_asks_from_series_start(tmp_path):
    fetcher = FixtureFetcher({}, default=_ecos_body())

    ingest.collect(
        [_indicator("dubai_crude")],
        fetcher=fetcher,
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 9, 20),
        configs={"ecos": _config("ecos")},
    )

    assert "199201" in fetcher.calls[0][0]


def test_normalize_outcomes_turns_observations_into_values(tmp_path):
    """받은 관측이 변환값까지 이어진다 — 이 연결이 없던 것이 T081 이다."""
    indicator = _indicator("dubai_crude")
    outcomes = ingest.collect(
        [indicator],
        fetcher=FixtureFetcher({}, default=_ecos_body()),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 9, 20),
        configs={"ecos": _config("ecos")},
    )

    normalized = ingest.normalize_outcomes([indicator], outcomes)

    assert "dubai_crude" in normalized
    assert normalized["dubai_crude"], "변환 결과가 비었다"
    first = normalized["dubai_crude"][0]
    assert first.indicator_id == "dubai_crude"
    assert first.transform_rule == indicator.transform_rule


def test_customs_splits_requests_into_one_year_windows(tmp_path):
    """관세청은 조회 기간 1년 제한이라 나눠 부른다 (FR-017a)."""
    body = (FIXTURES / "customs" / "newtrade_2026.xml").read_text(encoding="utf-8")
    members = [
        _indicator("exports"),
        _indicator("imports"),
        _indicator("trade_balance"),
    ]
    fetcher = FixtureFetcher({}, default=body)

    outcomes = ingest.collect(
        members,
        fetcher=fetcher,
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 9, 20),
        configs={"customs": _config("customs")},
    )

    assert len(fetcher.calls) >= 37, f"연 단위로 안 나눴다: {len(fetcher.calls)}회"
    assert {outcome.indicator_id for outcome in outcomes} == {
        "exports",
        "imports",
        "trade_balance",
    }


def test_rights_status_separates_pending_from_unverified():
    """권리 확인 대기와 미확인을 가른다. 미확인만 발행을 막는다 (FR-016a)."""
    assert ingest.rights_status(_indicator("dubai_crude")) == ingest.STATUS_RIGHTS_PENDING
    assert ingest.rights_status(_indicator("treasury_bond_3y")) is None


def test_every_indicator_gets_an_outcome(tmp_path):
    """성공이든 아니든 지표마다 결과가 하나씩 남는다 — 빠지면 수집 기록에 구멍이 생긴다."""
    outcomes = ingest.collect(
        registry.INDICATORS,
        fetcher=FailingFetcher(TransientSourceError("끊김")),
        data_root=tmp_path,
        mode="regular",
        today=date(2026, 9, 20),
        configs={source: _config(source) for source in registry.used_sources()},
    )

    assert len(outcomes) == registry.EXPECTED_INDICATOR_COUNT
    assert {outcome.indicator_id for outcome in outcomes} == {
        indicator.indicator_id for indicator in registry.INDICATORS
    }


def test_json_body_that_is_not_json_stops_instead_of_emptying(tmp_path):
    """원천이 HTML 오류를 돌려주면 빈 결과로 넘기지 않는다."""
    outcomes = ingest.collect(
        [_indicator("dubai_crude")],
        fetcher=FixtureFetcher({}, default="<html>error</html>"),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 9, 20),
        configs={"ecos": _config("ecos")},
    )

    assert not outcomes[0].succeeded
    assert outcomes[0].observations == []


def test_fixture_bodies_are_valid_json():
    """표본이 깨지면 이 파일의 다른 시험이 엉뚱한 이유로 실패한다. 먼저 확인한다."""
    json.loads(_ecos_body())


def test_daily_series_is_asked_with_eight_digit_dates(tmp_path):
    """일별 계열은 ``YYYYMMDD`` 로 묻는다 (FR-005).

    운영에서 국고채가 `[ERROR-101] 주기와 다른 형식의 날짜 형식입니다` 로 거절당한 자리다.
    어댑터는 주기별 자릿수를 알고 있었고 부르는 쪽이 여섯 자리를 넘기고 있었다.
    """
    indicator = _indicator("treasury_bond_3y")
    assert indicator.source_cycle == "D", "표본 전제가 바뀌었다"
    fetcher = FixtureFetcher(
        {}, default=(FIXTURES / "ecos" / "treasury_bond_3y_daily.json").read_text(encoding="utf-8")
    )

    ingest.collect(
        [indicator],
        fetcher=fetcher,
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 9, 20),
        configs={"ecos": _config("ecos")},
    )

    url = fetcher.calls[0][0]
    assert "/19981101/20260930/" in url, url


def test_monthly_series_keeps_six_digit_dates(tmp_path):
    """월별 계열은 여섯 자리 그대로다. 일별 고치면서 같이 늘어나면 안 된다."""
    fetcher = FixtureFetcher({}, default=_ecos_body())

    ingest.collect(
        [_indicator("dubai_crude")],
        fetcher=fetcher,
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 9, 20),
        configs={"ecos": _config("ecos")},
    )

    url = fetcher.calls[0][0]
    assert "/199201/202609/" in url, url


def test_month_end_day_is_correct_for_february():
    """일별 조회의 끝은 그 달 말일이다. 좁히면 마지막 거래일을 놓친다 (FR-006a)."""
    from data_pipeline.macro.ingest import _ecos_period

    assert _ecos_period("2024-02", "D", end=True) == "20240229"  # 윤년
    assert _ecos_period("2026-02", "D", end=True) == "20260228"
    assert _ecos_period("2000-02", "D", end=True) == "20000229"  # 400 으로 나뉘는 해
    assert _ecos_period("1900-02", "D", end=True) == "19000228"  # 100 으로만 나뉘는 해
    assert _ecos_period("2026-04", "D", end=True) == "20260430"


def test_sdmx_is_asked_for_json(tmp_path):
    """경제협력개발기구는 달라고 해야 JSON 을 준다.

    기본 응답이 XML 이라 헤더 없이 부르면 "응답이 JSON 이 아니다"로 끝난다. 운영에서
    g20_cli 가 그렇게 실패했다.
    """
    captured: list[dict[str, str] | None] = []

    class HeaderSpy:
        def get(self, url, params=None, headers=None):
            captured.append(headers)
            return "{}"

    ingest.collect(
        [_indicator("g20_cli")],
        fetcher=HeaderSpy(),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 9, 20),
        configs={"oecd": _config("oecd")},
    )

    assert captured, "요청이 나가지 않았다"
    accept = (captured[0] or {}).get("Accept", "")
    assert "json" in accept.lower(), f"JSON 을 요청하지 않았다: {accept!r}"
    # 판본까지 못 박는다. 1.0 은 structure 하나를, 2.0 은 structures 배열을 준다 —
    # 어댑터는 2.0 을 읽으므로 판본을 비우면 원천이 1.0 을 주고 파싱이 실패한다.
    assert "2.0.0" in accept, f"SDMX 판본을 고정하지 않았다: {accept!r}"


def test_oecd_fixture_is_sdmx_json_2(tmp_path):
    """표본이 2.0 판본인지 확인한다. 이 전제가 깨지면 위 헤더 고정이 틀린 것이 된다."""
    import json

    payload = json.loads((FIXTURES / "oecd" / "g20_cli_aa_ix.json").read_text(encoding="utf-8"))

    assert "2.0.0" in payload["meta"]["schema"]
    assert "structures" in payload["data"]
