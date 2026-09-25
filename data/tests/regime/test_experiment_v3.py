"""v3 채택 실험의 정답 라벨과 판정 규칙 (v3-experiment.md §2·§5)."""

from __future__ import annotations

from datetime import date

import experiment_regime_v3 as experiment


class TestPhaseLabel:
    def test_전환점_달은_그_전환점까지의_국면이다(self) -> None:
        assert experiment.phase_label(date(2008, 1, 1)) == "확장"
        assert experiment.phase_label(date(2008, 2, 1)) == "수축"
        assert experiment.phase_label(date(2009, 2, 1)) == "수축"
        assert experiment.phase_label(date(2009, 3, 1)) == "확장"

    def test_정점이_정해지지_않은_2020년_6월_이후는_모른다(self) -> None:
        assert experiment.phase_label(date(2020, 5, 1)) == "수축"
        assert experiment.phase_label(date(2020, 6, 1)) is None

    def test_첫_전환점_이전은_모른다(self) -> None:
        assert experiment.phase_label(date(1998, 7, 1)) is None


class TestDecision:
    def test_세_조건을_모두_만족해야_채택한다(self) -> None:
        v1 = {"M1_evaluation": 0.50, "M2_evaluation": 60, "M3_evaluation": 0.80}
        ok = {"M1_evaluation": 0.56, "M2_evaluation": 70, "M3_evaluation": 0.76}
        assert experiment.decide(v1, ok)["adopt"] is True
        small = {**ok, "M1_evaluation": 0.54}
        assert experiment.decide(v1, small)["adopt"] is False
        closer = {**ok, "M2_evaluation": 50}
        assert experiment.decide(v1, closer)["adopt"] is False
        shaky = {**ok, "M3_evaluation": 0.70}
        assert experiment.decide(v1, shaky)["adopt"] is False

    def test_값이_없으면_채택하지_않는다(self) -> None:
        assert experiment.decide({}, {})["adopt"] is False
