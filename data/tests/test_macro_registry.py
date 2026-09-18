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

from data_pipeline.macro import registry


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
