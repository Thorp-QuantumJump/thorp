"""화면·계산 등록표·자료 버전의 변경 경계를 판정한다 (FR-003)."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping, Sequence
from dataclasses import asdict, dataclass
from decimal import Decimal

from sqlalchemy import Engine, text

from data_pipeline.macro.registry import (
    FIELD_IDS,
    NOMINAL_WEIGHT_POLICY,
    IndicatorDefinition,
    nominal_weights,
    validate,
)


@dataclass(frozen=True)
class VersionNumbers:
    display: int
    calculation: int
    dataset: int

    def __post_init__(self) -> None:
        if min(self.display, self.calculation, self.dataset) < 1:
            raise ValueError("버전 번호는 모두 1 이상이어야 한다")


@dataclass(frozen=True)
class VersionChange:
    versions: VersionNumbers
    reprocess_all_months: bool
    recompute_baselines: bool
    reuse_observations: bool
    value_rule_fingerprint: str


def _value_rule(indicator: IndicatorDefinition) -> dict[str, object]:
    """값 또는 사용 가능 구간을 바꾸는 필드만 직렬화한다.

    원천 계열 선택에 필요한 org/classification/source_cycle과 계열 시작도 포함한다.
    연결 사슬의 coverage_end는 조회 시각마다 달라지는 관찰 기록이므로 제외한다.
    """
    links = [
        {key: value for key, value in asdict(link).items() if key != "coverage_end"}
        for link in indicator.link_chain
    ]
    return {
        "indicator_id": indicator.indicator_id,
        "primary_source": indicator.primary_source,
        "table_code": indicator.table_code,
        "item_code": indicator.item_code,
        "org_id": indicator.org_id,
        "classification": indicator.classification,
        "source_cycle": indicator.source_cycle,
        "raw_unit": indicator.raw_unit,
        "transformed_unit": indicator.transformed_unit,
        "transform_rule": indicator.transform_rule,
        "series_start": indicator.series_start,
        "break_link_rule": indicator.break_link_rule,
        "link_chain": links,
        "missing_rule": indicator.missing_rule,
        "trading_calendar": indicator.trading_calendar,
    }


def value_rule_fingerprint(indicators: Sequence[IndicatorDefinition]) -> str:
    """17개 값 규칙의 순서 독립 SHA-256 지문."""
    validate(tuple(indicators))
    payload = [_value_rule(i) for i in sorted(indicators, key=lambda i: i.indicator_id)]
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def plan_change(
    current: VersionNumbers,
    previous: Sequence[IndicatorDefinition],
    proposed: Sequence[IndicatorDefinition],
    previous_weights: Mapping[str, Decimal],
    proposed_weights: Mapping[str, Decimal],
    previous_display: Mapping[str, str],
    proposed_display: Mapping[str, str],
) -> VersionChange:
    """변경 종류별로 필요한 버전만 올린다. 이 함수는 DB나 시계열을 바꾸지 않는다."""
    old_fingerprint = value_rule_fingerprint(previous)
    new_fingerprint = value_rule_fingerprint(proposed)
    value_changed = old_fingerprint != new_fingerprint
    definition_changed = {i.indicator_id: i for i in previous} != {
        i.indicator_id: i for i in proposed
    }
    weights_changed = previous_weights != proposed_weights
    calculation_changed = definition_changed or weights_changed
    display_changed = previous_display != proposed_display
    return VersionChange(
        versions=VersionNumbers(
            current.display + int(display_changed),
            current.calculation + int(calculation_changed),
            current.dataset + int(value_changed),
        ),
        reprocess_all_months=value_changed,
        recompute_baselines=calculation_changed,
        reuse_observations=calculation_changed and not value_changed,
        value_rule_fingerprint=new_fingerprint,
    )


def register_calculation_version(
    engine: Engine,
    version_key: str,
    change_reason: str,
    indicators: tuple[IndicatorDefinition, ...],
    weights: Mapping[str, Decimal] | None = None,
    weight_policy: str | None = None,
) -> int:
    """17개 정의와 명목 비중을 새 불변 계산 등록표 버전으로 기록한다."""
    fingerprint = value_rule_fingerprint(indicators)
    chosen_weights = dict(weights) if weights is not None else nominal_weights(indicators)
    default_weights = nominal_weights(indicators)
    if chosen_weights != default_weights and not weight_policy:
        raise ValueError("기본 명목 비중을 바꾸면 새 가중 정책 설명이 필요하다")
    if set(chosen_weights) != {indicator.indicator_id for indicator in indicators}:
        raise ValueError("명목 비중은 지표 17개 모두에 정확히 하나씩 있어야 한다")
    if any(weight <= 0 for weight in chosen_weights.values()):
        raise ValueError("명목 비중은 양수여야 한다")
    weight_expression = (
        "CAST(:weight AS JSONB)" if engine.dialect.name == "postgresql" else ":weight"
    )
    classification_expression = (
        "CAST(:classification AS JSONB)"
        if engine.dialect.name == "postgresql" else ":classification"
    )
    links_expression = (
        "CAST(:link_chain AS JSONB)"
        if engine.dialect.name == "postgresql" else ":link_chain"
    )
    with engine.begin() as connection:
        registry_id = connection.execute(
            text("INSERT INTO macro_calculation_registry_version "
                 "(version_key, value_rule_fingerprint, change_reason, "
                 "nominal_weight_policy, status, created_at) "
                 "VALUES (:key, :fingerprint, :reason, :policy, 'PUBLISHED', CURRENT_TIMESTAMP) "
                 "RETURNING id"),
            {"key": version_key, "fingerprint": fingerprint, "reason": change_reason,
             "policy": weight_policy or NOMINAL_WEIGHT_POLICY},
        ).scalar_one()
        for indicator in indicators:
            connection.execute(
                text("INSERT INTO macro_indicator_definition "
                     "(registry_version_id, indicator_id, field_id, primary_source, "
                     "table_code, item_code, org_id, classification, source_cycle, "
                     "series_start, link_chain, raw_unit, transformed_unit, transform_rule, "
                     "release_rule, break_link_rule, missing_rule, agency, attribution, "
                     "license_status, license_review_status, published_precision, "
                     "refresh_months_n, nominal_weight, trading_calendar, "
                     "revision_history_available) VALUES "
                     "(:registry, :indicator, :field, :source, :table_code, :item_code, "
                     f":org_id, {classification_expression}, :source_cycle, :series_start, "
                     f"{links_expression}, :raw_unit, :transformed_unit, :transform_rule, "
                     ":release_rule, "
                     ":break_link_rule, :missing_rule, :agency, :attribution, :license_status, "
                     f":license_review_status, :precision, :refresh, {weight_expression}, "
                     ":trading_calendar, :revision_history)"),
                {"registry": registry_id, "indicator": indicator.indicator_id,
                 "field": indicator.field_id, "source": indicator.primary_source,
                 "table_code": indicator.table_code, "item_code": indicator.item_code,
                 "org_id": indicator.org_id,
                 "classification": json.dumps(indicator.classification),
                 "source_cycle": indicator.source_cycle,
                 "series_start": indicator.series_start,
                 "link_chain": json.dumps([asdict(link) for link in indicator.link_chain]),
                 "raw_unit": indicator.raw_unit,
                 "transformed_unit": indicator.transformed_unit,
                 "transform_rule": indicator.transform_rule,
                 "release_rule": indicator.release_rule,
                 "break_link_rule": indicator.break_link_rule,
                 "missing_rule": indicator.missing_rule, "agency": indicator.agency,
                 "attribution": indicator.attribution,
                 "license_status": indicator.license_status,
                 "license_review_status": indicator.license_review_status,
                 "precision": indicator.published_precision,
                 "refresh": indicator.refresh_months_n,
                 "weight": json.dumps(str(chosen_weights[indicator.indicator_id])),
                 "trading_calendar": indicator.trading_calendar,
                 "revision_history": indicator.revision_history_available},
            )
    return registry_id


def register_display_version(
    engine: Engine,
    version_key: str,
    change_reason: str,
    fields: Mapping[str, tuple[str, int, str | None]],
    indicators: Mapping[str, tuple[str, int]],
    registered_indicators: tuple[IndicatorDefinition, ...],
) -> int:
    """분야·지표 표시명/순서만 새 표시 버전으로 기록한다."""
    validate(registered_indicators)
    if set(fields) != set(FIELD_IDS) or set(indicators) != {
        indicator.indicator_id for indicator in registered_indicators
    }:
        raise ValueError("표시 버전은 7개 분야와 17개 지표를 모두 담아야 한다")
    if len({field[1] for field in fields.values()}) != len(FIELD_IDS):
        raise ValueError("분야 표시 순서가 중복된다")
    with engine.begin() as connection:
        display_id = connection.execute(
            text("INSERT INTO macro_display_version "
                 "(version_key, change_reason, created_at) "
                 "VALUES (:key, :reason, CURRENT_TIMESTAMP) RETURNING id"),
            {"key": version_key, "reason": change_reason},
        ).scalar_one()
        for field_id, (name, order, description) in fields.items():
            connection.execute(
                text("INSERT INTO macro_display_field "
                     "(display_version_id, field_id, display_name, display_order, description) "
                     "VALUES (:version, :field, :name, :order, :description)"),
                {"version": display_id, "field": field_id, "name": name,
                 "order": order, "description": description},
            )
        for indicator_id, (name, order) in indicators.items():
            connection.execute(
                text("INSERT INTO macro_display_indicator "
                     "(display_version_id, indicator_id, display_name, display_order) "
                     "VALUES (:version, :indicator, :name, :order)"),
                {"version": display_id, "indicator": indicator_id,
                 "name": name, "order": order},
            )
    return display_id


def initialize_current_publication(
    engine: Engine, registry_version_id: int, display_version_id: int
) -> None:
    """첫 자료 발행 전에도 등록/표시 버전을 조회할 수 있게 빈 포인터를 한 번 만든다.

    이미 포인터가 있으면 건드리지 않는다. 호출자의 버전과 다르면 조용히 덮지 않고
    거절해 다른 작업이 진행한 버전을 보존한다.
    """
    with engine.begin() as connection:
        connection.execute(
            text("INSERT INTO macro_current_publication "
                 "(singleton_key, dataset_version_id, registry_version_id, "
                 "display_version_id, switched_at) VALUES "
                 "('X', NULL, :registry, :display, CURRENT_TIMESTAMP) "
                 "ON CONFLICT (singleton_key) DO NOTHING"),
            {"registry": registry_version_id, "display": display_version_id},
        )
        pointer = connection.execute(
            text("SELECT dataset_version_id, registry_version_id, display_version_id "
                 "FROM macro_current_publication WHERE singleton_key='X'")
        ).one()
        if pointer != (None, registry_version_id, display_version_id):
            raise ValueError("현재 발행 포인터가 이미 다른 버전을 가리킨다")
