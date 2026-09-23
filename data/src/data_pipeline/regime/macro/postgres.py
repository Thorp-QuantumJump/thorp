"""008 구현체 — 010 배치가 거시지표를 PostgreSQL 에서 읽는다 (T068, 계약 §1).

현재 발행 포인터가 고정한 **한 자료 버전**만 읽는다. 메서드마다 포인터를 다시 읽으면 배치가
도는 도중 새 자료가 발행됐을 때 한 계산 안에서 서로 다른 버전의 값이 섞인다 — 그렇게 만든
거리는 무엇과 무엇을 견준 것인지 말할 수 없다.

**결측은 결측으로 돌려준다.** 변환값이 없는 달은 매핑에 키를 두지 않는다(008 FR-008). 0 이나
직전 값으로 채우면 010 의 척도 연속 구간 판정이 구멍을 못 보고 지나간다.

**발표일 자리에 원천의 발표일이 오지 않는다.** 아래 :meth:`PostgresMacroIndicatorSource.publication`
의 설명을 함께 읽는다.
"""

from __future__ import annotations

import json
from datetime import date, datetime
from decimal import Decimal
from functools import cached_property
from typing import Any, Final

from sqlalchemy import text
from sqlalchemy.engine import Engine

from data_pipeline.regime.macro.types import (
    BaselineMonths,
    FieldEntry,
    IndicatorEntry,
    IndicatorRegistry,
    MacroSourceError,
    MonthlyValue,
    Publication,
)
from data_pipeline.repository import database_url_from_env

#: 008 이 전체 공통 기준월을 적는 범위 id. 나머지는 분야 id 다.
_SCOPE_ALL: Final[str] = "ALL"


class PostgresMacroIndicatorSource:
    """`macro_*` 표를 읽는 :class:`MacroIndicatorSource` 구현."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    # -- 버전 고정 -----------------------------------------------------------------------

    @cached_property
    def _pointer(self) -> dict[str, Any]:
        """현재 발행 포인터. **한 번만 읽어 계산 내내 그대로 쓴다.**"""
        with self._engine.begin() as connection:
            row = connection.execute(
                text(
                    "SELECT p.dataset_version_id, d.version_key AS dataset_key,"
                    "       d.published_at, d.is_sample_data,"
                    "       p.registry_version_id, r.version_key AS registry_key,"
                    "       p.display_version_id"
                    " FROM macro_current_publication p"
                    " JOIN macro_calculation_registry_version r"
                    "   ON r.id = p.registry_version_id"
                    " LEFT JOIN macro_dataset_version d ON d.id = p.dataset_version_id"
                    " WHERE p.singleton_key = 'X'"
                )
            ).mappings().one_or_none()
        if row is None:
            raise MacroSourceError(
                "거시지표 등록표가 발행되지 않았다."
                " data/scripts/register_macro_registry.py 로 등록표를 먼저 세운다"
            )
        if row["dataset_version_id"] is None:
            # 등록표만 세워진 상태다. 010 은 값을 읽어야 하므로 여기서 멈춘다 — 빈 계열을
            # 돌려주면 "지표가 전부 결측인 자료"로 보여 계산이 그대로 진행된다.
            raise MacroSourceError(
                "발행된 거시지표 자료 버전이 없다."
                " data/scripts/ingest_macro.py 로 전 구간을 적재한 뒤 다시 돌린다"
            )
        return dict(row)

    # -- 등록표 -------------------------------------------------------------------------

    @cached_property
    def _registry(self) -> IndicatorRegistry:
        pointer = self._pointer
        with self._engine.begin() as connection:
            fields = connection.execute(
                text(
                    "SELECT field_id, display_name, display_order"
                    " FROM macro_display_field WHERE display_version_id = :display"
                    " ORDER BY display_order"
                ),
                {"display": pointer["display_version_id"]},
            ).mappings().all()
            definitions = connection.execute(
                text(
                    "SELECT d.indicator_id, d.field_id, d.transformed_unit, d.transform_rule,"
                    "       i.display_name, i.display_order"
                    " FROM macro_indicator_definition d"
                    " LEFT JOIN macro_display_indicator i"
                    "   ON i.indicator_id = d.indicator_id"
                    "  AND i.display_version_id = :display"
                    " WHERE d.registry_version_id = :registry"
                    " ORDER BY i.display_order NULLS LAST, d.indicator_id"
                ),
                {
                    "display": pointer["display_version_id"],
                    "registry": pointer["registry_version_id"],
                },
            ).mappings().all()

        indicators = tuple(
            IndicatorEntry(
                id=row["indicator_id"],
                # 화면 분류에 이름이 없으면 id 를 쓴다. 지어낸 문구로 덮으면 "이름이 아직
                # 없다"가 화면에서 사라진다.
                name=row["display_name"] or row["indicator_id"],
                field_id=row["field_id"],
                unit=row["transformed_unit"],
                transform=row["transform_rule"],
                display_order=row["display_order"] if row["display_order"] is not None else index,
            )
            for index, row in enumerate(definitions, start=1)
        )
        return IndicatorRegistry(
            version=pointer["registry_key"],
            fields=tuple(
                FieldEntry(
                    id=row["field_id"],
                    name=row["display_name"],
                    display_order=row["display_order"],
                )
                for row in fields
            ),
            indicators=indicators,
        )

    def registry(self) -> IndicatorRegistry:
        return self._registry

    def _require_registered(self, indicator_id: str) -> IndicatorEntry:
        for entry in self._registry.indicators:
            if entry.id == indicator_id:
                return entry
        raise MacroSourceError(f"등록되지 않은 지표다: {indicator_id!r}")

    # -- 시계열 -------------------------------------------------------------------------

    @cached_property
    def _series(self) -> dict[str, dict[date, MonthlyValue]]:
        """지표별 전 구간. 한 번에 읽어 두는 이유는 010 이 17개를 모두 훑기 때문이다."""
        with self._engine.begin() as connection:
            rows = connection.execute(
                text(
                    "SELECT indicator_id, target_month, raw_value, transformed_value"
                    " FROM macro_observation WHERE dataset_version_id = :dataset"
                    " ORDER BY indicator_id, target_month"
                ),
                {"dataset": self._pointer["dataset_version_id"]},
            ).mappings().all()

        series: dict[str, dict[date, MonthlyValue]] = {
            entry.id: {} for entry in self._registry.indicators
        }
        for row in rows:
            transformed = _decimal(row["transformed_value"])
            if transformed is None:
                # 결측이다. 사유는 008 이 따로 들고 있고 010 은 구멍 자체를 본다(FR-008).
                continue
            raw = _raw_of(row["raw_value"], row["target_month"])
            if raw is None:
                # 변환값은 있는데 원값이 없다. 설명 문장이 원값을 쓰므로(spec FR-015) 반쪽만
                # 넘기면 그쪽에서 만들어 내게 된다.
                continue
            bucket = series.get(row["indicator_id"])
            if bucket is None:
                continue
            bucket[row["target_month"]] = MonthlyValue(
                month=row["target_month"], value=float(transformed), raw=float(raw)
            )
        return series

    def monthly_series(self, indicator_id: str) -> dict[date, MonthlyValue]:
        self._require_registered(indicator_id)
        return dict(self._series[indicator_id])

    def raw_series(self, indicator_id: str) -> dict[date, float]:
        self._require_registered(indicator_id)
        return {month: value.raw for month, value in self._series[indicator_id].items()}

    # -- 기준월 -------------------------------------------------------------------------

    def baseline_months(self) -> BaselineMonths:
        """008 이 계산해 둔 T_g·T_all 을 읽는다 (FR-013).

        여기서 다시 세지 않는다. 008 의 완결 판정과 010 의 판정이 갈리면 화면이 고른 달에
        결과가 없는 일이 생기고, 그 어긋남은 두 곳을 모두 봐야 보인다.
        """
        with self._engine.begin() as connection:
            rows = connection.execute(
                text(
                    "SELECT scope_id, reference_month FROM macro_baseline"
                    " WHERE dataset_version_id = :dataset AND registry_version_id = :registry"
                ),
                {
                    "dataset": self._pointer["dataset_version_id"],
                    "registry": self._pointer["registry_version_id"],
                },
            ).mappings().all()

        by_field: dict[str, date] = {}
        composite: date | None = None
        for row in rows:
            if row["scope_id"] == _SCOPE_ALL:
                composite = row["reference_month"]
                continue
            if row["reference_month"] is not None:
                by_field[row["scope_id"]] = row["reference_month"]
        if not by_field:
            raise MacroSourceError(
                "자료 버전에 분야 기준월이 없다. 008 발행이 기준월을 함께 적는지 확인한다"
            )
        return BaselineMonths(by_field=by_field, composite=composite)

    # -- 발표와 버전 ---------------------------------------------------------------------

    def publication(self, indicator_id: str, month: date) -> Publication | None:
        """그 값의 대상월과 **자료 버전 발행 시각**, 개정 여부.

        ⚠ **``published_on`` 은 원천의 발표일이 아니다.** 008 은 17개 지표 어디에도 관측치별
        발표일을 갖고 있지 않다 — 원천 넷이 그것을 주지 않기 때문이고, 통계표 갱신일이나 수신
        시각을 그 자리에 넣는 것은 FR-007 이 금한다. 그래서 **우리가 그 자료를 발행한 시각**을
        싣는다. 이 값의 쓰임이 ``data_asof`` — "이 결과가 언제 자료인가" — 이므로, 모르는 원천
        발표일을 지어내는 대신 우리가 아는 사실로 답한다.

        원천 발표일이 필요한 계산을 여기 기대어 만들면 안 된다. 그런 계산은 008 이 발표일을
        갖게 된 뒤에야 성립한다.
        """
        self._require_registered(indicator_id)
        if month not in self._series[indicator_id]:
            return None
        published_at = self._pointer["published_at"]
        published_on = published_at.date() if isinstance(published_at, datetime) else published_at
        if published_on is None:
            raise MacroSourceError("발행된 자료 버전에 발행 시각이 없다")
        return Publication(
            indicator_id=indicator_id,
            month=month,
            published_on=published_on,
            revised=month in self._revised_months.get(indicator_id, frozenset()),
        )

    @cached_property
    def _revised_months(self) -> dict[str, frozenset[date]]:
        """원본 이력이 두 번 이상 쌓인 (지표, 대상월). 그것이 개정이다 (FR-009)."""
        with self._engine.begin() as connection:
            rows = connection.execute(
                text(
                    "SELECT indicator_id, target_month FROM macro_raw_revision"
                    " GROUP BY indicator_id, target_month HAVING count(*) > 1"
                )
            ).mappings().all()
        revised: dict[str, set[date]] = {}
        for row in rows:
            revised.setdefault(row["indicator_id"], set()).add(row["target_month"])
        return {key: frozenset(value) for key, value in revised.items()}

    def data_version(self) -> str:
        return self._pointer["dataset_key"]

    def changed_since(self, data_version: str) -> date | None:
        """그 버전 이후 값이 새로 들어오거나 개정된 가장 오래된 대상월.

        **모르면 ``None`` 이고 배치는 전 구간으로 물러난다.** 008 의 원본 이력은 수신 시각만
        갖고 자료 버전을 가리키지 않아, 주어진 버전 "이후"를 이력만으로 가릴 수 없다. 여기서
        추측하면 배치가 일부만 다시 계산하고 나머지는 낡은 채로 남는데, 그 사실이 어디에도
        드러나지 않는다.
        """
        return None

    def is_sample_data(self) -> bool:
        return bool(self._pointer["is_sample_data"])


def _decimal(stored: Any) -> Decimal | None:
    """JSONB 에 문자열로 저장된 소수를 읽는다. 없으면 ``None``."""
    if stored is None:
        return None
    if isinstance(stored, str):
        try:
            loaded = json.loads(stored)
        except json.JSONDecodeError:
            loaded = stored
    else:
        loaded = stored
    if loaded is None:
        return None
    if isinstance(loaded, (int, float, str)):
        try:
            return Decimal(str(loaded))
        except Exception:  # noqa: BLE001 — 값이 소수가 아니면 없는 것으로 다룬다
            return None
    return None


def _raw_of(stored: Any, month: date) -> Decimal | None:
    """그 달의 원값.

    008 의 ``raw_value`` 는 값 하나가 아니라 **그 변환값을 만든 원본들**이다 — 전년동월 대비는
    ``[[대상월, 값], [기준월, 값]]`` 처럼 둘이 온다. 그중 대상월의 값을 고른다. 아무거나 고르면
    증가율 지표의 원값이 열두 달 전 값이 되어 설명 문장이 다른 달을 말한다.
    """
    if stored is None:
        return None
    loaded = json.loads(stored) if isinstance(stored, str) else stored
    if isinstance(loaded, (int, float, str)):
        return _decimal(loaded)
    if isinstance(loaded, list):
        wanted = f"{month:%Y-%m}"
        for pair in loaded:
            if isinstance(pair, (list, tuple)) and len(pair) == 2 and str(pair[0])[:7] == wanted:
                return _decimal(pair[1])
        return None
    return None


def create_source() -> PostgresMacroIndicatorSource:
    """`macro/__init__.py` 의 팩터리가 부르는 이름. 구현 모듈마다 이 함수 하나를 내놓는다."""
    from sqlalchemy import create_engine

    return PostgresMacroIndicatorSource(create_engine(database_url_from_env()))
