"""유사성 문턱 교정 실험의 계산 규칙 (threshold-calibration.md §4·§5, S15P21E205-448)."""

from __future__ import annotations

import math
from datetime import date

from calibrate_regime_threshold import Pair, judge, measure, meets, tune


def pair(
    distance: float,
    same: bool,
    *,
    query: date = date(2010, 1, 1),
    gap: float = 0.5,
    evidence: bool = True,
    scope: str = "ALL",
) -> Pair:
    return Pair(
        scope=scope,
        query=query,
        case=date(2000, 1, 1),
        distance=distance,
        max_gap=gap,
        evidence=evidence,
        same_phase=same,
    )


class TestMeets:
    def test_거리와_큰_차이와_근거량을_모두_만족해야_충족이다(self) -> None:
        assert meets(pair(1.0, True), tau=1.0, delta=1.0)
        assert not meets(pair(1.1, True), tau=1.0, delta=math.inf)
        assert not meets(pair(0.5, True, gap=1.2), tau=1.0, delta=1.0)
        assert not meets(pair(0.5, True, evidence=False), tau=1.0, delta=math.inf)


class TestMeasure:
    def test_설명력_차이는_충족과_미충족의_국면_일치율_차이다(self) -> None:
        pairs = [pair(0.5, True), pair(0.6, True), pair(2.0, False), pair(2.1, True)]
        result = measure(pairs, tau=1.0, delta=math.inf)
        assert result["aMet"] == 1.0
        assert result["aRest"] == 0.5
        assert result["lift"] == 0.5

    def test_포괄률은_범위와_기준월_단위로_센다(self) -> None:
        # 같은 달이라도 분야가 다르면 다른 질의다.
        month = date(2010, 1, 1)
        pairs = [
            pair(0.5, True, query=month, scope="A"),
            pair(3.0, True, query=month, scope="B"),
        ]
        assert measure(pairs, tau=1.0, delta=math.inf)["coverage"] == 0.5


class TestTune:
    def test_제약을_만족하는_조합만_고른다(self) -> None:
        # 가까운 쪽이 국면을 더 잘 맞히는 표본 — 충족 쌍이 30개를 넘도록 만든다.
        pairs = []
        for i in range(200):
            month = date(2000 + i // 12, i % 12 + 1, 1)
            pairs.append(pair(0.1 + i * 0.001, True, query=month))
            pairs.append(pair(5.0 + i * 0.001, i % 2 == 0, query=month))
        chosen = tune(pairs)["chosen"]
        assert chosen is not None
        assert chosen["lift"] > 0.4
        assert chosen["coverage"] >= 0.2

    def test_조건을_만족하는_조합이_없으면_교정_불가다(self) -> None:
        pairs = [pair(1.0, True, evidence=False) for _ in range(100)]
        assert tune(pairs)["chosen"] is None


class TestJudge:
    def test_세_조건을_모두_만족해야_통과다(self) -> None:
        ok = {"lift": 0.05, "coverage": 0.2, "nMet": 20}
        assert judge(ok)["pass"] is True
        assert judge({**ok, "lift": 0.049})["pass"] is False
        assert judge({**ok, "coverage": 0.19})["pass"] is False
        assert judge({**ok, "nMet": 19})["pass"] is False
        assert judge({**ok, "lift": None})["pass"] is False
