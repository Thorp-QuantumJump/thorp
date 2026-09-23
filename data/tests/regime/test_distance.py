"""거리와 기여 (spec FR-009·FR-010, tasks.md T036·T037·T039~T042).

여기서 고정하는 것은 다섯이다 — 수기 계산과 **정확히** 같은가, 후보 z가 기준월마다 다시
계산되는가, 비교 시점이 둘인 지표도 평균으로 결합되는가, 같은 분야의 D_g가 두 모드에서 같은가,
그리고 나눌 수 없을 때 **키를 만들지 않는가**.

수기 대조 값은 `fixtures/manual_reconciliation.json`에 있고 구현을 부르지 않고 따로 계산한
값이다. 허용 오차는 0이다(SC-003) — 근사 비교를 쓰면 반올림 규칙이 바뀌어도 통과한다.
"""

from __future__ import annotations

import json
import math
from datetime import date
from pathlib import Path

import pytest
from conftest import make_registry, make_series

from data_pipeline.regime.comparison import QUERY_INPUT_MISSING
from data_pipeline.regime.distance import (
    CandidateInputMissingError,
    DistanceModel,
    IndicatorDistance,
    ModelUnavailable,
    UnknownScaleReasonError,
    build_model,
    months_between,
)
from data_pipeline.regime.macro.types import (
    FieldEntry,
    IndicatorEntry,
    IndicatorRegistry,
    parse_month,
)
from data_pipeline.regime.rounding import to_storage_text
from data_pipeline.regime.scale import SCALE_WINDOW_TOO_SHORT, ScaleUnavailable

FIXTURE = Path(__file__).parent / "fixtures" / "manual_reconciliation.json"

#: 단위 시험 공통 계열 — 2015-04부터 123개월이면 척도 구간 120개월과 질의 3개월이 함께 나온다.
START = (2015, 4)
MONTHS = 123
BASELINE = date(2025, 6, 1)


def ramp(
    period: int, step: float, base: float = 0.0, count: int = MONTHS, trend: float = 0.0
) -> list[float]:
    """주기적으로 오르내리는 계열. 주기가 120을 나누면 후보와 질의 값이 같아진다.

    ``trend``가 0이면 척도 구간을 한 달 옮겨도 분포가 그대로라 m·s가 변하지 않는다. 기준월이
    바뀌면 척도가 달라진다는 것을 보이려면 추세가 있어야 한다.
    """
    return [base + (index % period) * step + index * trend for index in range(count)]


def build(
    layout: dict[str, list[str]],
    values: dict[str, list[float | None]],
    *,
    baseline: date = BASELINE,
    field_ids: list[str] | None = None,
) -> DistanceModel | ModelUnavailable:
    return build_model(
        make_registry(layout),
        baseline,
        {key: make_series(START, series) for key, series in values.items()},
        field_ids,
    )


def indicator_of(distance, indicator_id: str) -> IndicatorDistance:
    return next(
        item
        for field in distance.fields
        for item in field.indicators
        if item.indicator_id == indicator_id
    )


def field_of(distance, field_id: str):
    return next(field for field in distance.fields if field.field_id == field_id)


@pytest.fixture(scope="module")
def payload() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def computed(payload: dict):
    registry = IndicatorRegistry(
        version=payload["registry"]["version"],
        fields=tuple(
            FieldEntry(id=item["id"], name=item["name"], display_order=item["displayOrder"])
            for item in payload["registry"]["fields"]
        ),
        indicators=tuple(
            IndicatorEntry(
                id=item["id"],
                name=item["name"],
                field_id=item["fieldId"],
                unit=item["unit"],
                transform=item["transform"],
                display_order=item["displayOrder"],
            )
            for item in payload["registry"]["indicators"]
        ),
    )
    start = parse_month(payload["series"]["start"])
    series = {
        key: make_series((start.year, start.month), values)
        for key, values in payload["series"]["values"].items()
    }
    model = build_model(registry, parse_month(payload["baselineMonth"]), series)
    assert isinstance(model, DistanceModel), "대조 픽스처는 계산 가능해야 한다"
    return model, model.distance_for(parse_month(payload["endMonth"]))


class TestManualReconciliation:
    """수기 계산과 허용 오차 0으로 일치한다 (SC-003, T036·T042)."""

    def test_척도가_수기_계산과_같다(self, payload: dict, computed) -> None:
        model, _ = computed
        for indicator_id, expected in payload["expected"]["indicators"].items():
            scale = model.scales[indicator_id]
            assert scale.method == expected["scaleMethod"]
            assert to_storage_text(scale.median) == expected["median"]
            assert to_storage_text(scale.spread) == expected["spread"]

    def test_z가_수기_계산과_같다(self, payload: dict, computed) -> None:
        _, distance = computed
        for indicator_id, expected in payload["expected"]["indicators"].items():
            item = indicator_of(distance, indicator_id)
            assert [to_storage_text(value) for value in item.query_z] == expected["queryZ"]
            assert [to_storage_text(value) for value in item.case_z] == expected["caseZ"]

    def test_지표_거리가_수기_계산과_같다(self, payload: dict, computed) -> None:
        _, distance = computed
        for indicator_id, expected in payload["expected"]["indicators"].items():
            assert to_storage_text(indicator_of(distance, indicator_id).e) == expected["e"]

    def test_명목_비중과_거리_기여가_수기_계산과_같다(self, payload: dict, computed) -> None:
        _, distance = computed
        for indicator_id, expected in payload["expected"]["indicators"].items():
            item = indicator_of(distance, indicator_id)
            assert to_storage_text(item.nominal_weight) == expected["nominalWeight"]
            assert to_storage_text(item.distance_share) == expected["distanceShare"]

    def test_분야_거리와_기여도가_수기_계산과_같다(self, payload: dict, computed) -> None:
        _, distance = computed
        for field_id, expected in payload["expected"]["fields"].items():
            field = field_of(distance, field_id)
            assert to_storage_text(field.d) == expected["d"]
            assert to_storage_text(field.contribution) == expected["contribution"]

    def test_종합_거리가_수기_계산과_같다(self, payload: dict, computed) -> None:
        _, distance = computed
        assert to_storage_text(distance.value) == payload["expected"]["value"]


class TestSharedScale:
    """질의와 모든 후보가 기준월 t의 척도 하나를 쓴다 (FR-005·FR-009, T029·T030·T037)."""

    def test_후보_z는_기준월마다_다시_계산된다(self) -> None:
        values = {"A1": ramp(7, 0.5, 1.0, trend=0.01)}
        layout = {"FIELD_A": ["A1"]}
        early = build(layout, values, baseline=date(2025, 5, 1))
        late = build(layout, values, baseline=BASELINE)
        end_month = date(2015, 6, 1)
        early_case = indicator_of(early.distance_for(end_month), "A1").case_z
        late_case = indicator_of(late.distance_for(end_month), "A1").case_z
        assert early_case != late_case, "후보 z를 후보월만으로 캐시하면 안 된다(research R3)"

    def test_질의와_후보가_같은_자를_쓴다(self) -> None:
        model = build({"FIELD_A": ["A1"]}, {"A1": ramp(7, 0.5, 1.0)})
        scale = model.scales["A1"]
        item = indicator_of(model.distance_for(date(2015, 6, 1)), "A1")
        series = model.series["A1"]
        for month, case_month, query_z, case_z in zip(
            item.months, item.case_months, item.query_z, item.case_z, strict=True
        ):
            assert query_z == pytest.approx(scale.standardize(series[month].value))
            assert case_z == pytest.approx(scale.standardize(series[case_month].value))

    def test_후보_시점은_질의와_같은_간격이다(self) -> None:
        model = build({"FIELD_A": ["A1"]}, {"A1": ramp(7, 0.5, 1.0)})
        item = indicator_of(model.distance_for(date(2015, 6, 1)), "A1")
        offsets = [months_between(month, BASELINE) for month in item.months]
        case_offsets = [months_between(month, date(2015, 6, 1)) for month in item.case_months]
        assert offsets == case_offsets == [2, 1, 0]


class TestPartialComparison:
    """비교 시점이 두 달인 지표도 평균으로 결합된다 (FR-009, T039)."""

    def test_두_달이면_두_값의_평균이다(self) -> None:
        values = ramp(7, 0.5, 1.0)
        holed = list(values)
        holed[MONTHS - 2] = None  # t-1 만 빠진다
        model = build({"FIELD_A": ["A1"]}, {"A1": holed})
        scale = model.scales["A1"]
        item = indicator_of(model.distance_for(date(2015, 6, 1)), "A1")

        assert len(item.months) == 2
        gaps = [
            abs(
                scale.standardize(values[MONTHS - 1 - offset])
                - scale.standardize(values[2 - offset])
            )
            for offset in (2, 0)
        ]
        assert item.e == pytest.approx(sum(gaps) / 2)

    def test_한_지표의_구멍이_다른_지표를_줄이지_않는다(self) -> None:
        holed = ramp(9, 1.25, 10.0)
        holed[MONTHS - 2] = None
        model = build(
            {"FIELD_A": ["A1", "A2"]}, {"A1": ramp(7, 0.5, 1.0), "A2": holed}
        )
        distance = model.distance_for(date(2015, 6, 1))
        assert len(indicator_of(distance, "A1").months) == 3
        assert len(indicator_of(distance, "A2").months) == 2


class TestModeIndependence:
    """같은 기준월·분야의 D_g는 어느 모드에서든 같다 (FR-001·FR-009, SC-002, T040)."""

    LAYOUT = {"FIELD_A": ["A1", "A2"], "FIELD_B": ["B1"]}
    VALUES = {
        "A1": ramp(7, 0.5, 1.0),
        "A2": ramp(9, 1.25, 10.0),
        "B1": ramp(11, 0.2),
    }

    def test_분야_거리가_두_모드에서_같다(self) -> None:
        end_month = date(2015, 6, 1)
        composite = build(self.LAYOUT, self.VALUES).distance_for(end_month)
        only_field = build(self.LAYOUT, self.VALUES, field_ids=["FIELD_A"]).distance_for(end_month)
        assert field_of(composite, "FIELD_A").d == field_of(only_field, "FIELD_A").d

    def test_분야_검색의_거리는_그_분야의_D_g다(self) -> None:
        end_month = date(2015, 6, 1)
        only_field = build(self.LAYOUT, self.VALUES, field_ids=["FIELD_A"]).distance_for(end_month)
        assert only_field.value == field_of(only_field, "FIELD_A").d

    def test_분야_검색은_분야_기여도를_만들지_않는다(self) -> None:
        only_field = build(self.LAYOUT, self.VALUES, field_ids=["FIELD_A"]).distance_for(
            date(2015, 6, 1)
        )
        assert only_field.contribution is None
        assert field_of(only_field, "FIELD_A").contribution is None

    def test_종합은_분야_기여도를_만든다(self) -> None:
        composite = build(self.LAYOUT, self.VALUES).distance_for(date(2015, 6, 1))
        assert set(composite.contribution) == {"FIELD_A", "FIELD_B"}

    def test_분야_개수를_상수로_갖지_않는다(self) -> None:
        layout = {f"FIELD_{index}": [f"I{index}"] for index in range(3)}
        values = {f"I{index}": ramp(7 + index, 0.5, 1.0) for index in range(3)}
        composite = build(layout, values).distance_for(date(2015, 6, 1))
        assert len(composite.fields) == 3
        assert len(composite.contribution) == 3


class TestZeroDistance:
    """전체 거리가 0이면 기여 **키를 만들지 않는다** (spec Edge Cases, T041)."""

    #: 주기 12는 질의와 후보의 120개월 간격을 정확히 나눠 값이 같아진다.
    VALUES = {"A1": ramp(12, 0.5, 1.0), "A2": ramp(12, 1.25, 10.0), "B1": ramp(12, 0.2)}
    LAYOUT = {"FIELD_A": ["A1", "A2"], "FIELD_B": ["B1"]}

    def test_같은_값이면_거리가_0이다(self) -> None:
        distance = build(self.LAYOUT, self.VALUES).distance_for(date(2015, 6, 1))
        assert distance.value == 0.0
        assert all(item.e == 0.0 for field in distance.fields for item in field.indicators)

    def test_거리가_0이면_분야_기여도_키가_없다(self) -> None:
        distance = build(self.LAYOUT, self.VALUES).distance_for(date(2015, 6, 1))
        assert distance.contribution is None
        assert all(field.contribution is None for field in distance.fields)

    def test_분야_안_합이_0이면_거리_기여_키가_없다(self) -> None:
        distance = build(self.LAYOUT, self.VALUES).distance_for(date(2015, 6, 1))
        assert all(
            item.distance_share is None for field in distance.fields for item in field.indicators
        )

    def test_명목_비중은_거리가_0이어도_남는다(self) -> None:
        distance = build(self.LAYOUT, self.VALUES).distance_for(date(2015, 6, 1))
        assert indicator_of(distance, "A1").nominal_weight == 0.5
        assert indicator_of(distance, "B1").nominal_weight == 1.0


class TestCandidateInputs:
    """후보에게 질의보다 많은 시점을 요구하지 않고, 적은 시점으로 재지도 않는다 (FR-012)."""

    def test_대응_시점이_없으면_거리를_재지_않는다(self) -> None:
        values = ramp(7, 0.5, 1.0)
        values[1] = None  # 후보의 c-1
        model = build({"FIELD_A": ["A1"]}, {"A1": values})
        assert model.has_inputs_for(date(2015, 6, 1)) is False
        with pytest.raises(CandidateInputMissingError):
            model.distance_for(date(2015, 6, 1))

    def test_질의가_두_달이면_후보도_두_달만_본다(self) -> None:
        values = ramp(7, 0.5, 1.0)
        values[MONTHS - 2] = None  # 질의 t-1
        values[1] = None  # 후보 c-1 — 질의가 보지 않으므로 문제가 아니다
        model = build({"FIELD_A": ["A1"]}, {"A1": values})
        assert model.has_inputs_for(date(2015, 6, 1)) is True
        item = indicator_of(model.distance_for(date(2015, 6, 1)), "A1")
        assert item.case_months == (date(2015, 4, 1), date(2015, 6, 1))

    def test_입력이_다_있으면_통과한다(self) -> None:
        model = build({"FIELD_A": ["A1"]}, {"A1": ramp(7, 0.5, 1.0)})
        assert model.has_inputs_for(date(2015, 6, 1)) is True


class TestModelUnavailable:
    """만들 수 없으면 사유를 돌려주고, 그 지표를 빼고 계산하지 않는다 (FR-007·FR-008)."""

    def test_비교_시점이_없으면_QUERY_INPUT_MISSING이다(self) -> None:
        values = ramp(7, 0.5, 1.0)
        for index in (MONTHS - 3, MONTHS - 2, MONTHS - 1):
            values[index] = None
        model = build({"FIELD_A": ["A1", "A2"]}, {"A1": values, "A2": ramp(9, 1.25, 10.0)})
        assert isinstance(model, ModelUnavailable)
        assert model.reason == QUERY_INPUT_MISSING
        assert model.indicator_ids == ("A1",)

    def test_척도를_못_만들면_그_사유를_돌려준다(self) -> None:
        short = [None] * (MONTHS - 20) + ramp(7, 0.5, 1.0, count=20)
        model = build({"FIELD_A": ["A1"]}, {"A1": short})
        assert isinstance(model, ModelUnavailable)
        assert model.reason == SCALE_WINDOW_TOO_SHORT

    def test_질의_입력_없음을_척도_사유보다_먼저_말한다(self) -> None:
        both = [None] * (MONTHS - 20) + ramp(7, 0.5, 1.0, count=17) + [None, None, None]
        model = build({"FIELD_A": ["A1"]}, {"A1": both})
        assert isinstance(model, ModelUnavailable)
        assert model.reason == QUERY_INPUT_MISSING

    def test_등록표에_없는_분야를_요구하면_멈춘다(self) -> None:
        with pytest.raises(LookupError):
            build({"FIELD_A": ["A1"]}, {"A1": ramp(7, 0.5, 1.0)}, field_ids=["FIELD_X"])

    def test_하나라도_모르는_분야가_섞이면_멈춘다(self) -> None:
        """아는 것만 추려 통과시키면 부른 쪽이 다른 범위의 답을 제 것으로 믿는다."""
        with pytest.raises(LookupError, match="FIELD_X"):
            build(
                {"FIELD_A": ["A1"], "FIELD_B": ["B1"]},
                {"A1": ramp(7, 0.5, 1.0), "B1": ramp(11, 0.2)},
                field_ids=["FIELD_A", "FIELD_X"],
            )

    def test_분야를_하나도_주지_않으면_멈춘다(self) -> None:
        with pytest.raises(LookupError):
            build({"FIELD_A": ["A1"]}, {"A1": ramp(7, 0.5, 1.0)}, field_ids=[])

    def test_어휘에_없는_척도_사유는_이름_있는_오류다(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """research R7이 사유를 더할 때 순서 목록을 함께 안 고치면 여기서 잡힌다."""

        def unknown_reason(indicator_id: str, observation_end: date, series: object):
            return ScaleUnavailable(
                indicator_id=indicator_id,
                observation_end=observation_end,
                reason="SCALE_BELOW_MINIMUM",
                window_length=120,
            )

        monkeypatch.setattr("data_pipeline.regime.distance.scale_for", unknown_reason)
        with pytest.raises(UnknownScaleReasonError, match="SCALE_BELOW_MINIMUM"):
            build({"FIELD_A": ["A1"]}, {"A1": ramp(7, 0.5, 1.0)})


class TestNoiseIsNotDistance:
    """반올림하면 0인 거리에 기여도를 붙이지 않는다 (research R4, spec Edge Cases)."""

    LAYOUT = {"FIELD_A": ["A1", "A2"], "FIELD_B": ["B1"]}

    def values(self) -> dict[str, list[float | None]]:
        #: 주기 12라 질의와 후보의 값이 같다. 한 달만 1 ulp 어긋뜨려 '찌꺼기 거리'를 만든다.
        perturbed = ramp(12, 0.5, 1.0)
        perturbed[MONTHS - 1] = math.nextafter(perturbed[MONTHS - 1], math.inf)
        return {"A1": perturbed, "A2": ramp(12, 1.25, 10.0), "B1": ramp(12, 0.2)}

    def test_찌꺼기는_저장값이_0이다(self) -> None:
        distance = build(self.LAYOUT, self.values()).distance_for(date(2015, 6, 1))
        assert distance.value == 0.0
        assert indicator_of(distance, "A1").e == 0.0

    def test_찌꺼기_합에_기여도를_붙이지_않는다(self) -> None:
        distance = build(self.LAYOUT, self.values()).distance_for(date(2015, 6, 1))
        assert distance.contribution is None
        assert indicator_of(distance, "A1").distance_share is None

    def test_찌꺼기가_아니면_기여도가_나온다(self) -> None:
        distance = build(
            {"FIELD_A": ["A1", "A2"], "FIELD_B": ["B1"]},
            {"A1": ramp(7, 0.5, 1.0), "A2": ramp(9, 1.25, 10.0), "B1": ramp(11, 0.2)},
        ).distance_for(date(2015, 6, 1))
        assert distance.contribution is not None
