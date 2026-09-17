"""지표 id·지표명·분야 이름이 운영 코드에 0건인가 (spec FR-004, tasks.md T013).

010은 등록표가 돌려주는 것 외에 지표·분야에 대해 아무것도 알지 못한다. 지표 목록 상수나
분야 이름 문자열이 코드에 스며들면 **008에 지표가 하나 늘 때 010을 고쳐야 하고**, 고치지 않은
자리가 조용히 옛 목록으로 계산한다.

007이 같은 문제를 겪어 같은 정적 검사를 붙였다. 대상 파일은 손 목록이 아니라 디렉터리를 훑어
모은다(계약 §5의 두 번째 확인 항목).

여기서 보는 것은 `data/` 배치다. Backend·화면의 같은 검사는 각각 M6·M7에 있다.
"""

from __future__ import annotations

import re

from conftest import production_sources

from data_pipeline.regime.macro import SOURCE_FIXTURE, create_source


def _forbidden_terms() -> dict[str, list[str]]:
    """등록표가 소유한 값들 — 이 문자열이 운영 코드에 나타나면 안 된다."""
    registry = create_source(SOURCE_FIXTURE).registry()
    return {
        "지표 id": [indicator.id for indicator in registry.indicators],
        "지표 이름": [indicator.name for indicator in registry.indicators],
        "분야 id": [field.id for field in registry.fields],
        "분야 이름": [field.name for field in registry.fields],
    }


class TestIndicatorAgnostic:
    def test_검사할_말이_실제로_있다(self) -> None:
        """등록표에서 말을 못 얻으면 아래 시험이 전부 공짜로 통과한다."""
        terms = _forbidden_terms()
        assert all(values for values in terms.values())
        assert sum(len(values) for values in terms.values()) >= 40

    def test_운영_코드에_지표와_분야의_이름이_없다(self) -> None:
        offenders: list[str] = []
        terms = _forbidden_terms()
        for path in production_sources():
            text = path.read_text(encoding="utf-8")
            for kind, values in terms.items():
                offenders.extend(
                    f"{path.name}: {kind} {value!r}"
                    for value in values
                    if re.search(rf"(?<!\w){re.escape(value)}(?!\w)", text)
                )
        assert not offenders, (
            f"운영 코드가 지표·분야를 이름으로 알고 있다: {offenders}. "
            "값은 등록표에서만 와야 008에 지표가 늘어도 010을 고치지 않는다 (spec FR-004)"
        )
