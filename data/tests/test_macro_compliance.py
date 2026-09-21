"""이용 조건 준수 테스트 (T062) — FR-016·FR-016a·FR-016b·FR-017a·FR-018·FR-018a·FR-021.

네 가지를 본다. 한도 초과 요청이 만들어지지 않는가, 권리 미확인 지표가 공개 버전에 들어가지
않는가, 중단된 지표가 공개 버전과 조회 응답에 남지 않는가, 인증키 만기와 약관 개정이 재확인
대상으로 잡히는가.
"""

from __future__ import annotations

import os
import traceback
from dataclasses import replace
from datetime import date
from decimal import Decimal
from unittest import mock
from urllib.parse import quote

import pytest
import requests

from data_pipeline.macro import publish, registry
from data_pipeline.macro.config import API_KEY_ENV_NAMES
from data_pipeline.macro.fetch import RequestsFetcher, redact
from data_pipeline.macro.normalize import NormalizedObservation
from data_pipeline.macro.registry import (
    COMMERCIAL_RESTRICTED,
    COMMERCIAL_UNKNOWN,
    INDICATORS,
    LICENSE_ALLOWED,
    REVIEW_PENDING_RIGHTS,
    REVIEW_UNVERIFIED,
    RegistryError,
)
from data_pipeline.macro.sources.base import (
    RateLimiter,
    RateLimitExceeded,
    SourceError,
)

BY_ID = {indicator.indicator_id: indicator for indicator in INDICATORS}
SUSPENSION_REASON = "작성기관이 상업 이용을 제한한다고 회신했다"


def value(indicator_id: str, period: str = "2026-07") -> NormalizedObservation:
    sample = INDICATORS[0]
    return NormalizedObservation(
        indicator_id=indicator_id,
        period=period,
        transformed_value=Decimal("1"),
        missing_reason=None,
        raw_values=((period, Decimal("1")),),
        transform_rule=sample.transform_rule,
        raw_unit=sample.raw_unit,
        transformed_unit=sample.transformed_unit,
        source_updated_at=None,
    )


def all_filled() -> dict[str, list[NormalizedObservation]]:
    return {i.indicator_id: [value(i.indicator_id)] for i in INDICATORS}


class TestRecordedTerms:
    """지표마다 이용 조건이 적혀 있다 (FR-016, FR-016c, FR-018)."""

    def test_열일곱개_모두_조건이_비어_있지_않다(self) -> None:
        for indicator in INDICATORS:
            assert indicator.commercial_use in registry.COMMERCIAL_USE_STATES
            assert indicator.redistribution_rule.strip(), indicator.indicator_id
            assert indicator.export_notice.strip(), indicator.indicator_id
            assert indicator.license_verified_on is not None, indicator.indicator_id

    def test_내보내기_문구에_출처가_들어_있다(self) -> None:
        """006 이 파일로 내보낼 때 받는 사람이 출처를 알 수 있어야 한다 (FR-018)."""
        for indicator in INDICATORS:
            assert "출처" in indicator.export_notice, indicator.indicator_id

    def test_작성기관과_제공기관이_다른_지표가_있다(self) -> None:
        """이용 조건은 작성기관 쪽을 먼저 본다 (FR-016). 둘이 같다고 전제하면 안 된다."""
        assert BY_ID["dubai_crude"].agency == "국제통화기금(IMF)"
        assert "한국은행" in BY_ID["dubai_crude"].attribution
        assert BY_ID["housing_sale_price"].agency == "한국부동산원"


class TestPendingRights:
    """권리 확인 대기와 미확인은 다른 상태다 (FR-016a)."""

    def test_두바이유는_확인_대기이며_상업_이용을_모른다(self) -> None:
        """제한된 것이 아니라 **모르는 것**이다. 둘을 섞으면 대응이 반대가 된다."""
        dubai = BY_ID["dubai_crude"]

        assert dubai.license_review_status == REVIEW_PENDING_RIGHTS
        assert dubai.commercial_use == COMMERCIAL_UNKNOWN

    def test_확인_대기는_발행을_막지_않는다(self) -> None:
        assert publish.plan_publication(INDICATORS, all_filled()).can_publish

    def test_권리_미확인_지표는_공개_버전에_들어가지_않는다(self) -> None:
        indicators = tuple(
            replace(i, license_review_status=REVIEW_UNVERIFIED)
            if i.indicator_id == "g20_cli"
            else i
            for i in INDICATORS
        )

        decision = publish.plan_publication(indicators, all_filled())

        assert not decision.can_publish
        assert decision.baselines == ()
        assert (
            publish.PublicationBlocker("g20_cli", publish.BLOCK_LICENSE_UNVERIFIED)
            in decision.blockers
        )

    def test_상업_이용이_제한됐는데_허락이_ALLOWED_면_등록이_거부된다(self) -> None:
        """내보낼 수 없는 것을 수집·발행만 계속하면 쓰지 못할 자료가 쌓인다 (FR-018)."""
        broken = tuple(
            replace(i, commercial_use=COMMERCIAL_RESTRICTED, license_status=LICENSE_ALLOWED)
            if i.indicator_id == "cpi" or i.indicator_id == "consumer_price_index"
            else i
            for i in INDICATORS
        )

        with pytest.raises(RegistryError, match="상업 이용이 제한됐는데"):
            registry.validate(broken)


class TestSuspension:
    """이용 조건 철회 (FR-016b)."""

    def suspended_registry(self) -> tuple[registry.IndicatorDefinition, ...]:
        return tuple(
            registry.suspend(i, reason=SUSPENSION_REASON, on=date(2026, 10, 1))
            if i.indicator_id == "dubai_crude"
            else i
            for i in INDICATORS
        )

    def test_중단된_지표는_새로_수집하지_않는다(self) -> None:
        live = registry.collectible(self.suspended_registry())

        assert "dubai_crude" not in {i.indicator_id for i in live}
        assert len(live) == len(INDICATORS) - 1

    def test_중단된_지표가_없어도_발행이_막히지_않는다(self) -> None:
        """FR-011 의 '17개 전부'를 글자대로 읽으면 중단이 플랫폼을 통째로 멈춘다."""
        observations = {k: v for k, v in all_filled().items() if k != "dubai_crude"}

        decision = publish.plan_publication(self.suspended_registry(), observations)

        assert decision.can_publish, decision.blockers

    def test_중단_사실과_사유가_판정에_함께_실린다(self) -> None:
        """조용히 사라지면 '원래 없던 지표'와 '빠진 지표'가 같아 보인다 (원칙 VI)."""
        observations = {k: v for k, v in all_filled().items() if k != "dubai_crude"}

        decision = publish.plan_publication(self.suspended_registry(), observations)

        assert decision.suspended == (("dubai_crude", SUSPENSION_REASON),)

    def test_중단된_지표가_기준월을_물리지_않는다(self) -> None:
        """이미 빠진 지표가 기준월을 뒤로 끌면 멀쩡한 분야까지 과거로 내려간다."""
        observations = {k: v for k, v in all_filled().items() if k != "dubai_crude"}

        decision = publish.plan_publication(self.suspended_registry(), observations)

        overall = decision.baseline_for(publish.SCOPE_ALL)
        assert overall is not None
        assert overall.reference_month == "2026-07"
        assert "dubai_crude" not in overall.held_back_by

    def test_사유_없는_중단은_만들_수_없다(self) -> None:
        with pytest.raises(RegistryError, match="중단에 사유가 없다"):
            registry.suspend(INDICATORS[0], reason="   ", on=date(2026, 10, 1))

    def test_중단_기록이_상태와_어긋나면_등록이_거부된다(self) -> None:
        dangling = tuple(
            replace(i, license_suspended_on=date(2026, 10, 1), license_suspended_reason="x")
            if i.indicator_id == "exports"
            else i
            for i in INDICATORS
        )

        with pytest.raises(RegistryError, match="중단이 아닌데 중단 기록"):
            registry.validate(dangling)

    def test_중단이어도_등록표_자체는_유효하다(self) -> None:
        """중단은 오류가 아니라 상태다. 등록을 막으면 중단을 기록할 수 없다."""
        registry.validate(self.suspended_registry())


class TestReverification:
    """이용 조건·인증 자격 재확인 (FR-018a)."""

    def test_한국은행은_인증키_만기_전에_걸린다(self) -> None:
        """이용기간은 발급일로부터 2년이고 만료되면 수집이 끊긴다 (pd11 §1)."""
        due = registry.reverification_due(on=registry.ECOS_REVERIFY_BY)

        ecos_ids = {i.indicator_id for i in INDICATORS if i.primary_source == "ecos"}
        assert ecos_ids <= {i.indicator_id for i in due}

    def test_만기가_없는_원천도_주기가_오면_걸린다(self) -> None:
        """약관 개정을 통보받을 경로가 없으므로 시한을 두고 사람이 들여다본다."""
        due = registry.reverification_due(on=registry.ANNUAL_REVERIFY_BY)

        assert {i.primary_source for i in due} == {"customs", "kosis", "oecd"}

    def test_시한_전에는_아무것도_걸리지_않는다(self) -> None:
        assert registry.reverification_due(on=date(2027, 9, 16)) == ()

    def test_시한을_적지_않은_지표는_늘_대상이다(self) -> None:
        """시한 없음을 '확인 안 해도 됨'으로 읽으면 그 지표만 영영 빠진다."""
        undated = tuple(
            replace(i, license_reverify_by=None) if i.indicator_id == "exports" else i
            for i in INDICATORS
        )

        due = registry.reverification_due(undated, on=date(2026, 1, 1))

        assert [i.indicator_id for i in due] == ["exports"]

    def test_재확인_시한이_지나도_수집을_막지_않는다(self) -> None:
        """막으면 확인이 하루 늦은 것이 자료를 끊는 일이 된다. 드러내는 데까지가 일이다."""
        overdue = tuple(replace(i, license_reverify_by=date(2020, 1, 1)) for i in INDICATORS)

        assert len(registry.collectible(overdue)) == len(INDICATORS)
        assert publish.plan_publication(overdue, all_filled()).can_publish


class TestRateLimits:
    """원천이 밝힌 한도를 넘는 요청을 만들지 않는다 (FR-017a)."""

    def limiter(self, source_id: str) -> tuple[RateLimiter, list[float]]:
        clock = [0.0]
        slept: list[float] = []

        def sleep(seconds: float) -> None:
            slept.append(seconds)
            clock[0] += seconds

        return (
            RateLimiter(
                registry.source_policy(source_id), now=lambda: clock[0], sleep=sleep
            ),
            slept,
        )

    def test_국가통계포털은_분당_이백회를_넘지_않는다(self) -> None:
        limiter, slept = self.limiter("kosis")

        for _ in range(200):
            limiter.acquire()
        assert slept == [], "한도 안에서 기다리면 그만큼 느려진다"

        limiter.acquire()
        assert slept, "201번째 호출이 한도를 넘었는데 기다리지 않았다"

    def test_요청당_셀_상한을_넘으면_만들지_않는다(self) -> None:
        limiter, _ = self.limiter("kosis")
        cap = registry.source_policy("kosis").max_cells_per_request
        assert cap == 40_000

        limiter.check_request_size(cap, "cpi")
        with pytest.raises(RateLimitExceeded, match="원천 한도"):
            limiter.check_request_size(cap + 1, "cpi")

    def test_수치_한도가_없는_원천도_간격을_둔다(self) -> None:
        """한도가 없다는 말은 마음껏 불러도 된다는 뜻이 아니다 (pd11)."""
        for source_id in ("ecos", "customs", "oecd"):
            limiter, slept = self.limiter(source_id)
            limiter.acquire()
            limiter.acquire()
            assert slept, f"{source_id}: 연달아 부르는데 간격을 두지 않았다"

    def test_관세청_분할_상한이_등록표에만_있다(self) -> None:
        """같은 수를 코드에도 두면 한쪽만 고쳐질 때 등록표가 기록용으로 전락한다."""
        assert registry.source_policy("customs").max_months_per_request == 12

    def test_모든_원천에_한도_근거가_적혀_있다(self) -> None:
        """수치 한도가 없는 것과 확인하지 못한 것은 다르다 (FR-017a)."""
        for source_id in registry.used_sources():
            policy = registry.source_policy(source_id)
            assert policy.rate_limit.strip(), source_id
            assert policy.basis.strip(), source_id


class TestKeySecrecy:
    """인증키는 환경변수에서만 읽고 어디에도 남기지 않는다 (FR-021)."""

    KEY = "7o7oLDcGaDuBkmvrCDRKWzDq5sD/9k4/pMurtSqgRZNtX9Cm"

    def test_환경변수_이름을_한자리에_모아_둔다(self) -> None:
        assert set(API_KEY_ENV_NAMES) == {
            "DATA_GO_KR_SERVICE_KEY",
            "ECOS_API_KEY",
            "KOSIS_API_KEY",
        }

    def test_키의_원문과_인코딩된_형태를_모두_지운다(self, monkeypatch) -> None:
        """관세청 키는 %2F 를 품은 채 주소에 들어간다. 원문만 지우면 인코딩된 쪽이 남는다."""
        monkeypatch.setenv("DATA_GO_KR_SERVICE_KEY", self.KEY)

        cleaned = redact(f"url?serviceKey={quote(self.KEY, safe='')}&k={self.KEY}")

        assert self.KEY not in cleaned
        assert quote(self.KEY, safe="") not in cleaned

    def test_요청이_실패해도_키가_오류에_남지_않는다(self, monkeypatch) -> None:
        """requests 는 실패한 주소를 메시지에 통째로 담는다. 그 주소에 키가 있다."""
        monkeypatch.setenv("DATA_GO_KR_SERVICE_KEY", self.KEY)
        leaky = f"403 for url: https://x/api?serviceKey={quote(self.KEY, safe='')}"

        with mock.patch("requests.get", side_effect=requests.ConnectionError(leaky)):
            with pytest.raises(SourceError) as caught:
                RequestsFetcher(5.0).get("https://x/api", {"serviceKey": self.KEY})

        assert self.KEY not in str(caught.value)
        assert quote(self.KEY, safe="") not in str(caught.value)

    def test_원인_연쇄로도_새지_않는다(self, monkeypatch) -> None:
        """우리 메시지를 깨끗이 써도 __cause__ 가 매달리면 트레이스백에 키가 찍힌다."""
        monkeypatch.setenv("DATA_GO_KR_SERVICE_KEY", self.KEY)
        leaky = f"403 for url: https://x/api?serviceKey={quote(self.KEY, safe='')}"

        with mock.patch("requests.get", side_effect=requests.ConnectionError(leaky)):
            try:
                RequestsFetcher(5.0).get("https://x/api", {"serviceKey": self.KEY})
            except SourceError as error:
                printed = "".join(
                    traceback.format_exception(type(error), error, error.__traceback__)
                )

        assert self.KEY not in printed
        assert quote(self.KEY, safe="") not in printed

    def test_진단에_필요한_것은_남긴다(self, monkeypatch) -> None:
        """다 지우면 무엇 때문에 실패했는지도 사라진다. 키만 지우고 나머지는 남긴다."""
        monkeypatch.setenv("DATA_GO_KR_SERVICE_KEY", self.KEY)
        leaky = f"403 for url: https://x/api?serviceKey={quote(self.KEY, safe='')}"

        with mock.patch("requests.get", side_effect=requests.ConnectionError(leaky)):
            with pytest.raises(SourceError) as caught:
                RequestsFetcher(5.0).get("https://x/api", {"serviceKey": self.KEY})

        assert "ConnectionError" in str(caught.value)
        assert "403" in str(caught.value)

    def test_키가_설정되지_않은_환경에서도_지우기가_돈다(self, monkeypatch) -> None:
        for name in API_KEY_ENV_NAMES:
            monkeypatch.delenv(name, raising=False)

        assert redact("아무 문자열") == "아무 문자열"

    def test_등록표와_설정이_키_값을_들고_있지_않다(self) -> None:
        """설정 객체를 로그에 그대로 찍는 일이 흔한데 거기 키가 있으면 안 된다."""
        from data_pipeline.macro.config import load_source_config

        for source_id in registry.used_sources():
            config = load_source_config(source_id)
            rendered = repr(config)
            for name in API_KEY_ENV_NAMES:
                assert os.environ.get(name, "___없음___") not in rendered
