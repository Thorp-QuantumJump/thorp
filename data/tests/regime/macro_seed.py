"""008 등록표와 발행된 자료 한 벌을 시험용으로 넣는다 (T068).

010 의 계약 시험이 008 구현체에도 돌려면 `macro_*` 표가 차 있어야 한다. 실제 수집을 돌릴 수는
없으므로(원천 호출) 같은 모양을 손으로 넣는다.

**17개를 다 넣는다.** 스키마가 발행된 자료 버전에 완성 지표 17개를 요구하고(`ck_macro_dataset_
version_complete`), 등록표 타입도 분야마다 지표가 하나 이상 있기를 요구한다. 줄여 넣으면 시험이
008 이 실제로 거절하는 상태를 통과시킨다.
"""

from __future__ import annotations

import json
from datetime import UTC, date, datetime, timedelta
from typing import Final

from sqlalchemy import text
from sqlalchemy.engine import Engine

#: 분야와 그 분야의 지표 수. 합이 17이다 (008 FR-001 표와 같은 구성).
FIELDS: Final[tuple[tuple[str, str, int, int], ...]] = (
    ("production", "생산", 1, 3),
    ("consumption_investment", "소비·투자", 2, 3),
    ("external_trade", "대외거래", 3, 3),
    ("labor", "노동", 4, 2),
    ("prices_commodities", "물가·원자재", 5, 2),
    ("finance_housing", "금융·주택", 6, 3),
    ("overseas", "해외 경기", 7, 1),
)

REGISTRY_VERSION: Final[str] = "macro-calc-test"
DISPLAY_VERSION: Final[str] = "macro-display-test"
DATASET_VERSION: Final[str] = "macro-test-20260921"

#: 관측이 든 달. 마지막 달이 기준월이고 그 앞 달 하나는 **일부러 비워** 결측 경로를 남긴다.
MONTHS: Final[tuple[date, ...]] = (
    date(2026, 4, 1),
    date(2026, 5, 1),
    date(2026, 6, 1),
    date(2026, 7, 1),
)

#: 값이 비는 (지표, 달). 계약이 "결측은 키 없음"이라고 한 것을 시험이 실제로 보게 한다.
MISSING: Final[tuple[tuple[str, date], ...]] = (("production_2", date(2026, 5, 1)),)

_PUBLISHED_AT: Final[datetime] = datetime(2026, 9, 21, 13, 11, 17, tzinfo=UTC)


def indicator_ids() -> list[str]:
    return [f"{field}_{n}" for field, _, _, size in FIELDS for n in range(1, size + 1)]


def seed(engine: Engine) -> None:
    """등록표·자료 버전·관측·기준월·원본 이력을 한 번에 넣는다."""
    with engine.begin() as connection:
        registry_id = connection.execute(
            text(
                "INSERT INTO macro_calculation_registry_version"
                " (version_key, value_rule_fingerprint, change_reason, status, created_at)"
                " VALUES (:key, repeat('a', 64), '시험', 'PUBLISHED', now()) RETURNING id"
            ),
            {"key": REGISTRY_VERSION},
        ).scalar_one()
        display_id = connection.execute(
            text(
                "INSERT INTO macro_display_version (version_key, change_reason, created_at)"
                " VALUES (:key, '시험', now()) RETURNING id"
            ),
            {"key": DISPLAY_VERSION},
        ).scalar_one()

        order = 0
        for field_id, field_name, field_order, size in FIELDS:
            connection.execute(
                text(
                    "INSERT INTO macro_display_field"
                    " (display_version_id, field_id, display_name, display_order)"
                    " VALUES (:display, :field, :name, :order)"
                ),
                {
                    "display": display_id,
                    "field": field_id,
                    "name": field_name,
                    "order": field_order,
                },
            )
            for n in range(1, size + 1):
                order += 1
                indicator_id = f"{field_id}_{n}"
                connection.execute(
                    text(
                        "INSERT INTO macro_indicator_definition"
                        " (registry_version_id, indicator_id, field_id, primary_source,"
                        "  raw_unit, transformed_unit, transform_rule, agency, attribution,"
                        "  license_status, license_review_status, refresh_months_n,"
                        "  nominal_weight, revision_history_available)"
                        " VALUES (:registry, :indicator, :field, 'kosis', '2020=100', '%',"
                        "         'YOY_GROWTH', '통계청', '국가통계포털(KOSIS)', 'ALLOWED',"
                        "         'VERIFIED', 3, CAST(:weight AS JSONB), true)"
                    ),
                    {
                        "registry": registry_id,
                        "indicator": indicator_id,
                        "field": field_id,
                        "weight": json.dumps("0.0476"),
                    },
                )
                connection.execute(
                    text(
                        "INSERT INTO macro_display_indicator"
                        " (display_version_id, indicator_id, display_name, display_order)"
                        " VALUES (:display, :indicator, :name, :order)"
                    ),
                    {
                        "display": display_id,
                        "indicator": indicator_id,
                        "name": f"지표 {order}",
                        "order": order,
                    },
                )

        dataset_id = connection.execute(
            text(
                "INSERT INTO macro_dataset_version"
                " (version_key, processing_registry_version_id, value_rule_fingerprint,"
                "  status, complete_indicator_count, published_at, created_at)"
                " VALUES (:key, :registry, repeat('a', 64), 'PUBLISHED', 17, :published, now())"
                " RETURNING id"
            ),
            {"key": DATASET_VERSION, "registry": registry_id, "published": _PUBLISHED_AT},
        ).scalar_one()

        for index, indicator_id in enumerate(indicator_ids()):
            for step, month in enumerate(MONTHS):
                missing = (indicator_id, month) in MISSING
                # 원값은 008 이 하는 대로 **계보 형태**로 넣는다 — 전년동월 대비는 대상월과
                # 기준월 둘이 온다. 그 모양을 읽지 못하면 원값이 열두 달 전 값이 된다.
                raw = json.dumps(
                    [
                        [f"{month.year - 1:04d}-{month.month:02d}", "100.0"],
                        [f"{month:%Y-%m}", str(100 + index + step)],
                    ]
                )
                connection.execute(
                    text(
                        "INSERT INTO macro_observation"
                        " (dataset_version_id, indicator_id, target_month, raw_value,"
                        "  transformed_value, released_at, received_at, missing_reason)"
                        " VALUES (:dataset, :indicator, :month, CAST(:raw AS JSONB),"
                        "         CAST(:transformed AS JSONB), NULL, now(), :reason)"
                    ),
                    {
                        "dataset": dataset_id,
                        "indicator": indicator_id,
                        "month": month,
                        "raw": raw,
                        # 008 은 발표일을 모른다 — released_at 은 늘 NULL 이다.
                        "transformed": None if missing else json.dumps(f"{1.0 + index * 0.1:.6f}"),
                        "reason": "NOT_TRANSFORMABLE" if missing else None,
                    },
                )

        reference = MONTHS[-1]
        for field_id, _, _, _ in FIELDS:
            connection.execute(
                text(
                    "INSERT INTO macro_baseline"
                    " (dataset_version_id, registry_version_id, scope_id, reference_month,"
                    "  reference_month_held_back_by, computed_at)"
                    " VALUES (:dataset, :registry, :scope, :month, '[]'::jsonb, now())"
                ),
                {
                    "dataset": dataset_id,
                    "registry": registry_id,
                    "scope": field_id,
                    "month": reference,
                },
            )
        connection.execute(
            text(
                "INSERT INTO macro_baseline"
                " (dataset_version_id, registry_version_id, scope_id, reference_month,"
                "  reference_month_held_back_by, computed_at)"
                " VALUES (:dataset, :registry, 'ALL', :month, '[]'::jsonb, now())"
            ),
            {"dataset": dataset_id, "registry": registry_id, "month": reference},
        )

        # 개정 판정은 원본 이력이 두 번 이상 쌓인 (지표, 달)이다. 한 지표에만 둘을 넣는다.
        for revision in (1, 2):
            connection.execute(
                text(
                    "INSERT INTO macro_raw_revision"
                    " (indicator_id, source, target_month, raw_value, raw_unit, received_at,"
                    "  observed_revision, raw_path, raw_hash)"
                    " VALUES ('production_1', 'kosis', :month, CAST(:raw AS JSONB), '2020=100',"
                    "         :received, :revision, :path, repeat('b', 64))"
                ),
                {
                    "month": reference,
                    "raw": json.dumps("101.0"),
                    "received": _PUBLISHED_AT - timedelta(days=revision),
                    "revision": revision,
                    "path": f"raw/kosis/production_1/{revision}.json",
                },
            )

        connection.execute(
            text(
                "INSERT INTO macro_current_publication"
                " (singleton_key, dataset_version_id, registry_version_id, display_version_id,"
                "  switched_at) VALUES ('X', :dataset, :registry, :display, now())"
            ),
            {"dataset": dataset_id, "registry": registry_id, "display": display_id},
        )
