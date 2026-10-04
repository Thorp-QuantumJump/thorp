"""경제 국면 모델 v3-eq (spec 010 v3 개정안 §3, S15P21E205-442).

합성 자료로 계산 정의를 확인한다. 실제 등록표와 설정 파일이 맞는지는 마지막 시험이 본다.
"""

from __future__ import annotations

import json
import math
from datetime import date
from pathlib import Path

import pytest

from data_pipeline.macro.registry import INDICATORS
from data_pipeline.regime.candidates import select_candidates
from data_pipeline.regime.distance import ModelUnavailable
from data_pipeline.regime.macro.types import MonthlyValue
from data_pipeline.regime.phase import (
    DEFAULT_CONFIG,
    PhaseConfigError,
    PhaseModel,
    build_phase_model,
    load_config,
    phase_inputs,
    phase_series,
)
from data_pipeline.regime.scale import shift_month

MONTHS = 240


def _months(count: int = MONTHS) -> list[date]:
    return [shift_month(date(2019, 12, 1), back) for back in reversed(range(count))]


def _series(values: dict[date, float]) -> dict[date, MonthlyValue]:
    return {
        month: MonthlyValue(month=month, value=value, raw=value) for month, value in values.items()
    }


def _config(tmp_path: Path, blocks: list[dict], inputs: dict[str, str]) -> Path:
    path = tmp_path / "phase.json"
    path.write_text(
        json.dumps(
            {
                "version": "TEST-V3",
                "inputs": {key: {"kind": kind, "unit": "%"} for key, kind in inputs.items()},
                "blocks": blocks,
            }
        ),
        encoding="utf-8",
    )
    return path


def _wave(
    months: list[date], period: int, phase: float = 0.0, scale: float = 1.0
) -> dict[date, float]:
    return {m: scale * math.sin(2 * math.pi * (i / period) + phase) for i, m in enumerate(months)}


TWO_BLOCKS = [
    {
        "id": "a",
        "name": "블록A",
        "members": [{"indicator": "x1", "sign": 1}, {"indicator": "x2", "sign": -1}],
    },
    {"id": "b", "name": "블록B", "members": [{"indicator": "y1", "sign": 1}]},
]
AS_IS = {"x1": "AS_IS", "x2": "AS_IS", "y1": "AS_IS"}


class TestPhaseInputs:
    def test_규칙마다_1년_변화를_만든다(self) -> None:
        months = _months(30)
        base = _series({m: float(i) for i, m in enumerate(months)})
        last = months[-1]
        assert phase_series("AS_IS", base)[last].value == 29.0
        assert phase_series("YOY_DIFF", base)[last].value == pytest.approx(12.0)
        assert phase_series("YOY_PCT", base)[last].value == pytest.approx((29 / 17 - 1) * 100)

    def test_12개월_전_값이_없으면_만들지_않는다(self) -> None:
        months = _months(20)
        base = _series({m: 1.0 for m in months})
        derived = phase_series("YOY_DIFF", base)
        assert months[11] not in derived
        assert months[12] in derived

    def test_전월비는_12개월_복리로_누적한다(self) -> None:
        months = _months(14)
        base = _series({m: 1.0 for m in months})
        derived = phase_series("CUM12_FROM_MOM", base)
        assert derived[months[-1]].value == pytest.approx((1.01**12 - 1) * 100)
        assert months[10] not in derived


class TestConfig:
    def test_등록표와_블록_구성이_다르면_시작하지_않는다(self, tmp_path: Path) -> None:
        path = _config(tmp_path, TWO_BLOCKS, AS_IS)
        with pytest.raises(PhaseConfigError):
            load_config(path, indicator_ids=["x1", "x2", "y1", "z9"])

    def test_부호는_1이나_마이너스1이다(self, tmp_path: Path) -> None:
        blocks = [{"id": "a", "name": "A", "members": [{"indicator": "x1", "sign": 2}]}]
        path = _config(tmp_path, blocks, {"x1": "AS_IS"})
        with pytest.raises(PhaseConfigError):
            load_config(path, indicator_ids=["x1"])

    def test_실제_설정은_008_등록표의_17개를_정확히_한_번씩_묶는다(self) -> None:
        config = load_config(DEFAULT_CONFIG, indicator_ids=[i.indicator_id for i in INDICATORS])
        assert len(config.indicator_ids) == len(INDICATORS) == 17
        assert len(config.blocks) == 4


class TestModel:
    def _model(
        self, tmp_path: Path, series: dict[str, dict[date, float]], t: date
    ) -> PhaseModel | ModelUnavailable:
        config = load_config(_config(tmp_path, TWO_BLOCKS, AS_IS), indicator_ids=list(AS_IS))
        inputs = phase_inputs(config, {k: _series(v) for k, v in series.items()})
        return build_phase_model(config, t, inputs)

    def test_척도_구간이_60개월보다_짧으면_계산_불가다(self, tmp_path: Path) -> None:
        months = _months(50)
        model = self._model(
            tmp_path,
            {"x1": _wave(months, 12), "x2": _wave(months, 17), "y1": _wave(months, 23)},
            months[-1],
        )
        assert isinstance(model, ModelUnavailable)
        assert model.reason == "SCALE_WINDOW_TOO_SHORT"

    def test_질의와_같은_후보는_거리_0이다(self, tmp_path: Path) -> None:
        months = _months()
        # 주기 12개월 파동이면 12개월 전과 같은 값이다.
        series = {
            "x1": _wave(months, 12),
            "x2": _wave(months, 12, 1.0),
            "y1": _wave(months, 12, 2.0),
        }
        model = self._model(tmp_path, series, months[-1])
        assert isinstance(model, PhaseModel)
        distance = model.distance_for(shift_month(months[-1], 24))
        assert distance.value == pytest.approx(0.0, abs=1e-9)

    def test_부호를_반대로_둔_지표는_반대_방향으로_센다(self, tmp_path: Path) -> None:
        months = _months()
        series = {"x1": _wave(months, 12), "x2": _wave(months, 19), "y1": _wave(months, 13)}
        model = self._model(tmp_path, series, months[-1])
        assert isinstance(model, PhaseModel)
        month = months[-1]
        z1 = model.scales["x1"].standardize(model.series["x1"][month].value)
        z2 = model.scales["x2"].standardize(model.series["x2"][month].value)
        assert model.block_raw(model.config.blocks[0], month) == pytest.approx((z1 - z2) / 2)

    def test_블록_지표가_늘_서로_상쇄되면_계산_불가다(self, tmp_path: Path) -> None:
        months = _months()
        same = _wave(months, 12)
        model = self._model(tmp_path, {"x1": same, "x2": same, "y1": _wave(months, 13)}, months[-1])
        assert isinstance(model, ModelUnavailable)
        assert model.reason == "SCALE_ZERO"

    def test_거리는_블록_점수_벡터의_유클리드_거리다(self, tmp_path: Path) -> None:
        months = _months()
        series = {"x1": _wave(months, 11), "x2": _wave(months, 17), "y1": _wave(months, 23)}
        model = self._model(tmp_path, series, months[-1])
        assert isinstance(model, PhaseModel)
        case = shift_month(months[-1], 40)
        result = model.distance_for(case)
        expected = []
        for q, c in zip(model.months, model.case_months_for(case), strict=True):
            qv, cv = model.vector(q), model.vector(c)
            expected.append(math.sqrt(sum((a - b) ** 2 for a, b in zip(qv, cv, strict=True))))
        assert result.value == pytest.approx(sum(expected) / len(expected), abs=1e-9)
        assert set(result.by_block) == {"a", "b"}

    def test_후보_제한은_v1과_같은_규칙을_쓴다(self, tmp_path: Path) -> None:
        months = _months()
        series = {"x1": _wave(months, 11), "x2": _wave(months, 17), "y1": _wave(months, 23)}
        model = self._model(tmp_path, series, months[-1])
        assert isinstance(model, PhaseModel)
        selection = select_candidates(model)
        assert selection.displayed
        assert all(
            (months[-1].year - c.end_month.year) * 12 + months[-1].month - c.end_month.month > 12
            for c in selection.displayed
        )
