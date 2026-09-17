"""⚠ [임시] 008 대역 — `indicators.json`을 읽는 `MacroIndicatorSource` 구현.

008(거시지표 등록표)이 완성되면 **이 디렉터리를 통째로 지운다**(계약 §4.3). 지울 것이 한 곳에
모이도록 운영 코드는 이 모듈을 이름으로 참조하지 않는다 — `macro/__init__.py`의 팩터리가 모듈
경로로만 부르고, 그 한 줄이 폐기 절차 2단계에서 함께 사라진다.

**기준월은 저장하지 않고 계열에서 센다.** T_g는 그 분야 지표가 함께 있는 최신월, T_all은 17개가
함께 있는 최신월이라는 정의(008 FR-013) 그대로 세면 계열을 고칠 때 기준월이 따라온다. 파일에
적어 두면 둘이 어긋나고, 어긋난 기준월은 "그 달에 결과가 없다"로만 드러나 원인을 찾기 어렵다.
"""

from __future__ import annotations

import json
from datetime import date, timedelta
from functools import cached_property
from pathlib import Path
from typing import Any, Final

from data_pipeline.regime.macro.types import (
    BaselineMonths,
    FieldEntry,
    IndicatorEntry,
    IndicatorRegistry,
    MacroSourceError,
    MonthlyValue,
    Publication,
    parse_month,
)

INDICATORS_PATH: Final[Path] = Path(__file__).resolve().parent / "indicators.json"


def _next_month(month: date) -> date:
    return date(month.year + month.month // 12, month.month % 12 + 1, 1)


def _add_months(month: date, count: int) -> date:
    total = month.year * 12 + (month.month - 1) + count
    return date(total // 12, total % 12 + 1, 1)


class FixtureMacroIndicatorSource:
    """지어낸 시계열로 008 자리를 채우는 소스.

    `is_sample_data()`가 참이고 `data_version()`이 `FIXTURE-`로 시작한다 — 이 값이 식별 키와
    API 응답·기록에 실려 다니므로 화면·DB 어디에서도 가짜임이 드러난다(계약 §4.2).

    `changed_since()`는 늘 `None`이다. 픽스처에는 개정 이력이 없어 "어느 달부터 바뀌었나"에
    답할 수 없고, **모르는 것을 '바뀐 것 없음'으로 답하면 낡은 결과가 남는다.** `None`을
    돌려주면 배치가 전 구간 재계산으로 물러난다(계약 §1).
    """

    def __init__(self, path: Path | None = None) -> None:
        self._path = path or INDICATORS_PATH
        if not self._path.exists():
            raise MacroSourceError(f"픽스처 시계열 파일이 없다: {self._path}")
        self._document: dict[str, Any] = json.loads(self._path.read_text(encoding="utf-8"))

    # -- 등록표 -------------------------------------------------------------------------

    @cached_property
    def _registry(self) -> IndicatorRegistry:
        document = self._document
        fields = tuple(
            FieldEntry(id=entry["id"], name=entry["name"], display_order=entry["displayOrder"])
            for entry in document["fields"]
        )
        indicators = tuple(
            IndicatorEntry(
                id=entry["id"],
                name=entry["name"],
                field_id=entry["fieldId"],
                unit=entry["unit"],
                transform=entry["transform"],
                display_order=entry["displayOrder"],
            )
            for entry in document["indicators"]
        )
        return IndicatorRegistry(
            version=document["registryVersion"], fields=fields, indicators=indicators
        )

    def registry(self) -> IndicatorRegistry:
        return self._registry

    # -- 시계열 -------------------------------------------------------------------------

    @cached_property
    def _entries(self) -> dict[str, dict[str, Any]]:
        return {entry["id"]: entry for entry in self._document["indicators"]}

    def _entry(self, indicator_id: str) -> dict[str, Any]:
        try:
            return self._entries[indicator_id]
        except KeyError:
            raise MacroSourceError(f"등록되지 않은 지표다: {indicator_id!r}") from None

    @cached_property
    def _series(self) -> dict[str, dict[date, MonthlyValue]]:
        """대상월 → 값. **결측 월은 키를 만들지 않는다**(계약 §3).

        파일에서는 결측이 `null`이다. 자리를 맞춰 읽어야 하므로 배열이 되고, 그 `null`을
        여기서 **키 없음으로 바꾼다** — 이 경계를 넘은 뒤에는 결측이 값인 척할 자리가 없다.
        """
        series: dict[str, dict[date, MonthlyValue]] = {}
        for indicator_id, entry in self._entries.items():
            start = parse_month(entry["startMonth"])
            values = entry["value"]
            raws = entry["raw"]
            if len(values) != len(raws):
                raise MacroSourceError(
                    f"{indicator_id}의 변환값과 원값 길이가 다르다: {len(values)} ≠ {len(raws)}"
                )
            months: dict[date, MonthlyValue] = {}
            for offset, (value, raw) in enumerate(zip(values, raws, strict=True)):
                if value is None or raw is None:
                    # 한쪽만 비는 일은 없다. 같은 관측이 빠진 것이므로 둘 다 비어야 한다.
                    if value is not None or raw is not None:
                        raise MacroSourceError(
                            f"{indicator_id} {offset}번째 달에 원값·변환값 한쪽만 있다"
                        )
                    continue
                month = _add_months(start, offset)
                months[month] = MonthlyValue(month=month, value=value, raw=raw)
            series[indicator_id] = months
        return series

    def monthly_series(self, indicator_id: str) -> dict[date, MonthlyValue]:
        self._entry(indicator_id)
        return dict(self._series[indicator_id])

    def raw_series(self, indicator_id: str) -> dict[date, float]:
        self._entry(indicator_id)
        return {month: value.raw for month, value in self._series[indicator_id].items()}

    # -- 기준월 -------------------------------------------------------------------------

    def baseline_months(self) -> BaselineMonths:
        """분야별 T_g와 공통 T_all을 계열에서 센다 (008 FR-013)."""
        registry = self._registry
        by_field: dict[str, date] = {}
        for field in registry.fields:
            indicators = registry.indicators_of(field.id)
            month = self._latest_common_month(indicator.id for indicator in indicators)
            if month is not None:
                by_field[field.id] = month
        composite = self._latest_common_month(
            indicator.id for indicator in registry.indicators
        )
        return BaselineMonths(by_field=by_field, composite=composite)

    def _latest_common_month(self, indicator_ids: Any) -> date | None:
        """그 지표들이 **함께** 값을 가진 가장 최근 달. 하나도 없으면 ``None``이다."""
        common: set[date] | None = None
        for indicator_id in indicator_ids:
            months = set(self._series[indicator_id])
            common = months if common is None else common & months
        if not common:
            return None
        return max(common)

    # -- 발표·버전 ----------------------------------------------------------------------

    def publication(self, indicator_id: str, month: date) -> Publication | None:
        entry = self._entry(indicator_id)
        if month not in self._series[indicator_id]:
            return None
        published_on = _next_month(month) + timedelta(days=int(entry["publicationLagDays"]))
        return Publication(
            indicator_id=indicator_id,
            month=month,
            published_on=published_on,
            revised=f"{month:%Y-%m}" in entry["revisedMonths"],
        )

    def data_version(self) -> str:
        return self._document["dataVersion"]

    def changed_since(self, data_version: str) -> date | None:
        """늘 ``None`` — 픽스처는 개정 이력을 모른다. 배치는 전 구간 재계산으로 물러난다."""
        return None

    def is_sample_data(self) -> bool:
        return True


def create_source() -> FixtureMacroIndicatorSource:
    """`macro/__init__.py`의 팩터리가 부르는 이름. 구현 모듈마다 이 함수 하나를 내놓는다."""
    return FixtureMacroIndicatorSource()
