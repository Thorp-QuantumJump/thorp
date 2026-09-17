"""공통 척도 (spec FR-005·FR-006, research R5, tasks.md T023~T026).

여기서 고정하는 것은 넷이다 — 연속 구간을 어디서 끊는가, MAD가 0일 때 어디로 넘어가는가,
보정 상수를 무엇으로 쓰는가, 그리고 **척도 구간 밖 후보에도 같은 자를 대는가**.

입력은 008 대역이 아니라 이 파일이 짓는다. `SCALE_ZERO`나 정확한 동률은 실제 자료에 없을 수
있어서, 운영 픽스처가 폐기된 뒤에도 이 경로가 검증되려면 단위 시험이 제 입력을 가져야 한다
(contracts/macro-indicator-port.md §4.4).
"""

from __future__ import annotations

from datetime import date

import pytest
from conftest import make_series

from data_pipeline.regime.scale import (
    METHOD_IQR,
    METHOD_MAD,
    NORMAL_QUARTILE,
    SCALE_WINDOW_TOO_SHORT,
    SCALE_ZERO,
    WINDOW_MAX_MONTHS,
    WINDOW_MINIMUM_MONTHS,
    Scale,
    ScaleUnavailable,
    continuous_window,
    scale_for,
)

INDICATOR = "TEST_INDICATOR"
BASELINE = date(2026, 6, 1)


def rising(count: int, start: float = 1.0, step: float = 0.5) -> list[float]:
    """서로 다른 값 ``count``개. MAD가 0이 아니게 두어 기본 경로를 탄다."""
    return [start + step * i for i in range(count)]


class TestContinuousWindow:
    """T023 — 연속 구간은 t-3에서 과거로 거슬러 처음 끊기는 곳까지다 (spec FR-005)."""

    def test_구멍_뒤쪽이_짧으면_앞쪽_자료가_많아도_척도를_못_만든다(self) -> None:
        """**이 시험이 M2의 핵심이다.** 구멍을 건너뛰어 끌어오면 통계 개편 전후처럼 성격이
        다른 값이 한 분포에 섞이고, 척도를 "질의 구간 직전"에서 구하라는 규칙의 뜻이 사라진다.
        """
        # 오래된 쪽 100개월(2014-02~2022-05) → 구멍(2022-06) → 최근 45개월(2022-07~2026-03).
        # 최근 블록의 끝을 t-3에 맞춘다 — 여기서 과거로 거슬러 세기 시작하기 때문이다.
        values = rising(100) + [None] + rising(45, start=80.0)
        series = make_series((2014, 2), values)

        result = scale_for(INDICATOR, BASELINE, series)

        assert isinstance(result, ScaleUnavailable)
        assert result.reason == SCALE_WINDOW_TOO_SHORT
        assert result.window_length == 45
        assert len(series) == 145, "앞쪽에 자료가 남아 있는데도 못 만들어야 한다"

    def test_연속_구간은_최대_120개월에서_멈춘다(self) -> None:
        series = make_series((2000, 1), rising(400))
        window = continuous_window(series, BASELINE)

        assert len(window) == WINDOW_MAX_MONTHS
        assert window[-1][0] == date(2026, 3, 1), "t-3에서 시작한다"
        assert window[0][0] == date(2016, 4, 1), "t-122까지다"

    def test_구간이_오래된_달부터_온다(self) -> None:
        series = make_series((2000, 1), rising(400))
        months = [month for month, _ in continuous_window(series, BASELINE)]
        assert months == sorted(months)

    def test_정확히_60개월이면_만들어진다(self) -> None:
        """경계는 60**이상**이다. 한쪽으로 치우쳐 읽으면 한 달 차이로 결과가 갈린다."""
        series = make_series((2021, 4), rising(WINDOW_MINIMUM_MONTHS))
        result = scale_for(INDICATOR, BASELINE, series)
        assert isinstance(result, Scale)
        assert result.window_length == WINDOW_MINIMUM_MONTHS

    def test_59개월이면_만들어지지_않는다(self) -> None:
        series = make_series((2021, 5), rising(WINDOW_MINIMUM_MONTHS - 1))
        result = scale_for(INDICATOR, BASELINE, series)
        assert isinstance(result, ScaleUnavailable)
        assert result.reason == SCALE_WINDOW_TOO_SHORT

    def test_최근_세_달은_척도에_쓰이지_않는다(self) -> None:
        """구간은 `[t-122, t-3]`이다. t-2·t-1·t는 **질의 쪽** 값이라 자를 만드는 데 쓰지 않는다."""
        series = make_series((2016, 4), rising(WINDOW_MAX_MONTHS))
        window = continuous_window(series, BASELINE)
        assert date(2026, 4, 1) not in [month for month, _ in window]
        assert len(window) == WINDOW_MAX_MONTHS


class TestSpread:
    """T024 — MAD에서 IQR로 넘어가는 전환과 둘 다 0인 경우 (spec FR-006)."""

    @staticmethod
    def _mad_zero_iqr_nonzero() -> list[float]:
        """120개월 중 61개월이 같은 값, 30개월이 작고 29개월이 크다.

        이러면 중앙값 편차의 중앙값은 0이고(MAD=0) 25백분위는 중앙값보다 작다(IQR≠0).
        """
        below = [1.0 + 0.01 * i for i in range(30)]
        above = [3.0 + 0.01 * i for i in range(29)]
        return below + [2.0] * 61 + above

    def test_보통은_MAD로_잰다(self) -> None:
        series = make_series((2016, 4), rising(WINDOW_MAX_MONTHS))
        result = scale_for(INDICATOR, BASELINE, series)
        assert isinstance(result, Scale)
        assert result.method == METHOD_MAD

    def test_MAD가_0이면_IQR로_넘어간다(self) -> None:
        series = make_series((2016, 4), self._mad_zero_iqr_nonzero())
        result = scale_for(INDICATOR, BASELINE, series)

        assert isinstance(result, Scale)
        assert result.method == METHOD_IQR
        assert result.spread > 0
        assert result.median == 2.0

    def test_IQR도_0이면_계산_불가다(self) -> None:
        """임의 epsilon을 넣으면 순위가 만들어지지만, 그 순위는 자료가 아니라 상수가 만든 것이다."""
        series = make_series((2016, 4), [7.0] * WINDOW_MAX_MONTHS)
        result = scale_for(INDICATOR, BASELINE, series)

        assert isinstance(result, ScaleUnavailable)
        assert result.reason == SCALE_ZERO
        assert result.window_length == WINDOW_MAX_MONTHS, "구간은 충분했다 — 짧아서가 아니다"

    def test_산포가_0인_척도는_돌려주지_않는다(self) -> None:
        """0으로 나누는 자리를 만들지 않는다. 표준화가 `inf`를 내면 순위는 조용히 망가진다."""
        series = make_series((2016, 4), [7.0] * WINDOW_MAX_MONTHS)
        result = scale_for(INDICATOR, BASELINE, series)
        assert not result.available

    def test_MAD_산포는_보정_상수로_나눈_값이다(self) -> None:
        values = [0.0] * 60 + [4.0] * 60  # 중앙값 2.0, 편차 2.0 → MAD 2.0
        series = make_series((2016, 4), values)
        result = scale_for(INDICATOR, BASELINE, series)

        assert isinstance(result, Scale)
        assert result.method == METHOD_MAD
        assert result.spread == pytest.approx(2.0 / NORMAL_QUARTILE)


class TestNormalQuartile:
    """T025 — 보정 상수는 근사값이 아니라 Φ⁻¹(0.75) 실계산값이다 (spec FR-005, research R5)."""

    def test_근사값_1_4826과_다르다(self) -> None:
        """근사값으로 되돌아가면 이 시험이 실패한다."""
        assert 1 / NORMAL_QUARTILE != 1.4826

    def test_차이가_저장_자릿수보다_위에_있다(self) -> None:
        """**이것이 근사값을 배제한 이유다.** 12자리 반올림(research R4)보다 아래에서 갈린다면
        순위에 닿지 않아 신경 쓸 일이 아니다. 실제로는 다섯째 자리에서 갈린다.
        """
        difference = abs(1 / NORMAL_QUARTILE - 1.4826)
        assert difference > 1e-12
        assert difference > 1e-6, f"다섯째 자리에서 갈려야 한다: 차이 {difference}"

    def test_IQR_대체_상수도_근사값_1_349와_다르다(self) -> None:
        assert 2 * NORMAL_QUARTILE != 1.349
        assert abs(2 * NORMAL_QUARTILE - 1.349) > 1e-6

    def test_상수가_scipy의_분위수다(self) -> None:
        from scipy.stats import norm

        assert NORMAL_QUARTILE == float(norm.ppf(0.75))


class TestCommonScaleAppliesToAllCandidates:
    """T026 — 질의의 m·s를 **모든** 후보에 적용한다 (spec FR-005·FR-009, 원칙 IV)."""

    def test_척도_구간보다_오래된_후보도_질의_척도로_표준화된다(self) -> None:
        """후보 직전 이력이 없어도 표준화는 된다.

        척도 구간은 **자를 만든 자리**이지 후보를 고르는 범위가 아니다(FR-012). 구간 밖이라고
        표준화를 거부하면 오래된 국면이 통째로 후보에서 사라진다.
        """
        series = make_series((2016, 4), rising(WINDOW_MAX_MONTHS))
        scale = scale_for(INDICATOR, BASELINE, series)
        assert isinstance(scale, Scale)

        old_value = 3.25  # 1970년대 어느 달의 값이라고 하자 — 척도 구간 한참 밖이다
        assert scale.standardize(old_value) == (old_value - scale.median) / scale.spread

    def test_질의와_후보가_같은_자를_쓴다(self) -> None:
        """같은 값이면 같은 z다. 후보마다 제 척도로 재면 이 등식이 깨진다."""
        series = make_series((2016, 4), rising(WINDOW_MAX_MONTHS))
        scale = scale_for(INDICATOR, BASELINE, series)
        assert isinstance(scale, Scale)

        assert scale.standardize(12.0) == scale.standardize(12.0)
        assert scale.standardize(12.0) - scale.standardize(10.0) == pytest.approx(
            (12.0 - 10.0) / scale.spread
        )

    def test_중앙값과_같은_값의_z는_0이다(self) -> None:
        series = make_series((2016, 4), rising(WINDOW_MAX_MONTHS))
        scale = scale_for(INDICATOR, BASELINE, series)
        assert isinstance(scale, Scale)
        assert scale.standardize(scale.median) == 0.0

    def test_척도가_쓴_구간을_기록한다(self) -> None:
        """구멍 때문에 짧아진 구간으로 계산했다는 사실이 기록에 있어야 값을 대조할 수 있다."""
        series = make_series((2016, 4), rising(WINDOW_MAX_MONTHS))
        scale = scale_for(INDICATOR, BASELINE, series)
        assert isinstance(scale, Scale)
        assert (scale.window_start, scale.window_end) == (date(2016, 4, 1), date(2026, 3, 1))
        assert scale.window_length == WINDOW_MAX_MONTHS
