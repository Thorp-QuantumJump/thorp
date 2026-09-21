"""지표 등록표가 spec 008 의 불변식을 지키는지 본다 (tasks.md T015, SC-001·FR-005).

**네트워크를 쓰지 않는다.** 등록표는 코드에 있는 상수라 인증키도 필요 없다.

여기서 보는 것은 행 단위 DB 제약이 잡지 못하는 것들이다 — "17개", "7개 분야", "지표마다 주
공급원 하나"는 한 행만 봐서는 판정할 수 없다. 그래서 스키마의 ``CHECK`` 와 이 테스트가 서로 다른
층을 막는다.
"""

from __future__ import annotations

import dataclasses
from decimal import Decimal

import pytest

from data_pipeline.macro import config, registry


def test_has_exactly_seventeen_indicators():
    assert len(registry.INDICATORS) == registry.EXPECTED_INDICATOR_COUNT


def test_validate_passes_for_shipped_registry():
    registry.validate()


def test_every_indicator_belongs_to_exactly_one_field():
    grouped = registry.by_field()
    total = sum(len(members) for members in grouped.values())

    assert total == registry.EXPECTED_INDICATOR_COUNT
    assert set(grouped) == set(registry.FIELD_IDS)
    for field_id, members in grouped.items():
        assert members, f"{field_id}에 지표가 없다"


def test_indicator_ids_are_unique():
    ids = [indicator.indicator_id for indicator in registry.INDICATORS]

    assert len(set(ids)) == len(ids)


def test_each_indicator_has_exactly_one_primary_source():
    """FR-005. 한 지표를 여러 원천에서 받아 합치지 않는다.

    ``primary_source`` 가 스칼라 하나라는 것만으로는 부족하다 — 같은 지표를 다른 id 로 두 번
    등록해 두 원천에서 받으면 합산이 아니라 중복이 되고, 분야 가중이 그만큼 기운다.
    """
    by_source_and_table = [
        (indicator.primary_source, indicator.table_code, indicator.item_code)
        for indicator in registry.INDICATORS
    ]

    assert len(set(by_source_and_table)) == len(by_source_and_table)
    for indicator in registry.INDICATORS:
        assert isinstance(indicator.primary_source, str)
        assert indicator.primary_source


def test_nominal_weights_are_equal_within_field_and_one_seventh_across_fields():
    """FR-004. 분야 간 1/7, 분야 안에서 동일가중."""
    weights = registry.nominal_weights()
    grouped = registry.by_field()

    assert sum(weights.values()) == pytest.approx(Decimal(1))
    for members in grouped.values():
        shares = {weights[indicator.indicator_id] for indicator in members}
        assert len(shares) == 1, "분야 안에서 비중이 갈린다"
        field_total = sum(weights[indicator.indicator_id] for indicator in members)
        assert field_total == pytest.approx(Decimal(1) / Decimal(7))


def test_all_six_transform_rules_are_represented():
    """FR-001 표의 여섯이 모두 쓰인다. 하나라도 비면 그 변환은 검증 표본에서 빠진다(SC-007)."""
    used = {indicator.transform_rule for indicator in registry.INDICATORS}

    assert used == {
        registry.TRANSFORM_YOY,
        registry.TRANSFORM_MOM,
        registry.TRANSFORM_MONTH_END,
        registry.TRANSFORM_MONTH_AVERAGE,
        registry.TRANSFORM_OFFICIAL_AMOUNT,
        registry.TRANSFORM_SEASONAL_RATE,
    }


def test_only_dubai_crude_waits_on_rights():
    """두바이유만 권리 확인 대기이고, 발행을 막는 미확인은 없다 (FR-016a)."""
    pending = [
        indicator.indicator_id
        for indicator in registry.INDICATORS
        if indicator.license_review_status == registry.REVIEW_PENDING_RIGHTS
    ]
    unverified = [
        indicator.indicator_id
        for indicator in registry.INDICATORS
        if indicator.license_review_status == registry.REVIEW_UNVERIFIED
    ]

    assert pending == ["dubai_crude"]
    assert unverified == []


def test_agency_differs_from_provider_where_verified():
    """작성기관과 제공기관이 다른 둘을 등록표가 구분해 들고 있다 (FR-016)."""
    indicators = {indicator.indicator_id: indicator for indicator in registry.INDICATORS}

    assert indicators["dubai_crude"].agency == "국제통화기금(IMF)"
    assert "한국은행" in indicators["dubai_crude"].attribution
    assert indicators["housing_sale_price"].agency == "한국부동산원"
    assert "국가통계포털" in indicators["housing_sale_price"].attribution


def test_every_indicator_has_attribution_and_agency():
    """SC-006. 출처와 작성기관이 빈 지표가 0개다."""
    for indicator in registry.INDICATORS:
        assert indicator.agency.strip(), indicator.indicator_id
        assert indicator.attribution.strip(), indicator.indicator_id


def test_refresh_months_default_is_not_shorter_than_customs_revision_cycle():
    """T012. 관세청은 매월 정정하므로 재조회 구간이 그보다 짧으면 정정을 놓친다 (FR-017)."""
    customs = [
        indicator
        for indicator in registry.INDICATORS
        if indicator.primary_source == "customs"
    ]

    assert len(customs) == 3
    for indicator in customs:
        assert indicator.refresh_months_n >= 1
        assert indicator.refresh_months_n == registry.DEFAULT_REFRESH_MONTHS_N


def test_minimum_effective_scale_is_left_to_spec_010():
    """FR-015. 008은 공표 정밀도만 기록하고 최소 유효 척도를 정하지 않는다."""
    for indicator in registry.INDICATORS:
        assert indicator.minimum_effective_scale is None, indicator.indicator_id


def test_validate_rejects_wrong_count():
    trimmed = registry.INDICATORS[:-1]

    with pytest.raises(registry.RegistryError, match="17개"):
        registry.validate(trimmed)


def test_validate_rejects_unknown_field():
    broken = dataclasses.replace(registry.INDICATORS[0], field_id="unknown_field")
    mutated = (broken,) + registry.INDICATORS[1:]

    with pytest.raises(registry.RegistryError, match="분야가 7개 밖"):
        registry.validate(mutated)


def test_validate_rejects_duplicate_indicator_id():
    duplicated = (registry.INDICATORS[0],) + registry.INDICATORS[:-1]

    with pytest.raises(registry.RegistryError, match="겹친다"):
        registry.validate(duplicated)


# ---------------------------------------------------------------------------
# 기대 대상월 수 (T077, FR-011b(3))
# ---------------------------------------------------------------------------
#: 2026-09-20 전 구간 수집이 실제로 넣은 지표별 대상월 수. **추정이 아니라 측정값이다**
#: (원칙 IX) — 리허설 DB 에서 ``SELECT indicator_id, count(*) FROM macro_observation
#: GROUP BY indicator_id`` 로 읽었다. 공표 지연을 잘못 적으면 여기서 어긋난다.
MEASURED_2026_09 = {
    "all_industry_production": 319,
    "construction_completed_constant": 349,
    "consumer_price_index": 560,
    "dubai_crude": 416,
    "employed_persons": 327,
    "exports": 453,
    "facility_investment_index": 378,
    "g20_cli": 788,
    "housing_sale_price": 319,
    "imports": 453,
    "mining_manufacturing_production": 559,
    "retail_sales_index": 379,
    "service_production": 319,
    "trade_balance": 453,
    "treasury_bond_3y": 335,
    "unemployment_rate": 327,
    "usd_krw_rate": 748,
}


def test_expected_month_count_matches_what_the_sources_actually_gave():
    """등록표의 공표 지연이 2026-09-20 실측과 맞는가.

    기대가 실제보다 크면 멀쩡한 적재가 늘 미달로 보이고, 작으면 무엇이 빠졌든 판정이 늘
    통과한다. 둘 다 판정을 쓸모없게 만든다.
    """
    for indicator in registry.INDICATORS:
        expected = registry.expected_month_count(indicator, "2026-09")
        measured = MEASURED_2026_09[indicator.indicator_id]
        assert expected <= measured, (
            f"{indicator.indicator_id}: 기대 {expected}개월인데 실제로는 {measured}개월만 왔다"
        )
        # 실제가 기대보다 많을 수 있는 경우는 하나뿐이다 — 아직 끝나지 않은 이번 달이 들어온
        # 것이고, 그 달은 결측으로 남는다. 두 달 이상 벌어지면 지연을 잘못 적은 것이다.
        assert measured - expected <= 1, (
            f"{indicator.indicator_id}: 기대 {expected}, 실제 {measured}."
            " 공표 지연이 실제보다 길게 적혀 있다"
        )


def test_expected_month_count_grows_with_the_calendar():
    """기대 개수는 상수가 아니다. 다음 달이 오면 하나 는다."""
    cpi = next(i for i in registry.INDICATORS if i.indicator_id == "consumer_price_index")
    assert (
        registry.expected_month_count(cpi, "2026-10")
        == registry.expected_month_count(cpi, "2026-09") + 1
    )


def test_expected_end_month_steps_back_by_the_publication_lag():
    production = next(
        i for i in registry.INDICATORS if i.indicator_id == "all_industry_production"
    )
    assert production.publication_lag_months == 2
    assert registry.expected_end_month(production, "2026-09") == "2026-07"
    # 해를 넘어가도 물러선다.
    assert registry.expected_end_month(production, "2026-01") == "2025-11"


def test_negative_publication_lag_is_refused():
    broken = dataclasses.replace(registry.INDICATORS[0], publication_lag_months=-1)
    with pytest.raises(registry.RegistryError, match="공표 지연"):
        registry.validate((broken,) + registry.INDICATORS[1:])


# ---------------------------------------------------------------------------
# 거래일 달력 (T078, FR-006b)
# ---------------------------------------------------------------------------
def test_no_shipped_indicator_reaches_the_month_average_fold():
    """달력이 비어 있어도 결측이 생기지 않는 이유를 고정한다.

    월 평균을 **일별 원천에서** 접을 때만 달력이 필요하다. 두바이유는 변환이 월 평균이지만
    한국은행이 월 평균을 이미 내 주므로 주기가 월별이고 그대로 통과한다.
    """
    reaching = [
        indicator.indicator_id
        for indicator in registry.INDICATORS
        if indicator.transform_rule == registry.TRANSFORM_MONTH_AVERAGE
        and indicator.source_cycle == registry.CYCLE_DAILY
    ]
    assert reaching == []


def test_daily_month_average_without_a_calendar_is_refused():
    """달력이 필요한 지표가 생기면 등록 자체가 거부된다.

    막지 않으면 그 지표는 매달 조용히 결측이 되고, 화면에는 "자료 없음"으로만 보인다.
    """
    broken = dataclasses.replace(
        registry.INDICATORS[0],
        transform_rule=registry.TRANSFORM_MONTH_AVERAGE,
        source_cycle=registry.CYCLE_DAILY,
        trading_calendar=None,
    )
    with pytest.raises(registry.RegistryError, match="거래일 달력"):
        registry.validate((broken,) + registry.INDICATORS[1:])


# ---------------------------------------------------------------------------
# 원천별 호출 규칙 (T079, FR-011b(1)·FR-017a)
# ---------------------------------------------------------------------------
def test_every_used_source_has_a_call_policy():
    for source_id in registry.used_sources():
        policy = registry.source_policy(source_id)
        assert policy.rate_limit
        assert policy.basis, f"{source_id}: 한도의 출처가 없으면 확인값인지 추측인지 갈리지 않는다"


def test_call_policy_reads_retry_numbers_from_config():
    """재시도 수치는 `config` 가 들고 등록표는 참조한다 — 두 벌이 되면 언젠가 갈라진다."""
    policy = registry.source_policy(registry.SOURCE_KOSIS)
    assert policy.max_attempts == config.DEFAULT_MAX_ATTEMPTS
    # 3회 시도면 사이 간격은 둘이다 (1·2초).
    assert policy.backoff_seconds == (1.0, 2.0)


def test_unknown_source_has_no_policy():
    with pytest.raises(registry.RegistryError, match="호출 규칙이 없는 원천"):
        registry.source_policy("wikipedia")
