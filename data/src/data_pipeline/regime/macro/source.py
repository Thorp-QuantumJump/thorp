"""008 경계 — `data/` 배치가 지표를 읽는 유일한 통로 (contracts/macro-indicator-port.md §1).

008(거시지표 등록표)이 아직 없다. 004가 001 없이 `MarketMetadataPort`로, 007이 003 없이
`ParameterRegistryPort`로 시작한 것과 같은 방식으로, **상류 spec의 자료를 읽는 통로를 하나만
두고** 완성되면 구현체만 바꾼다. 픽스처 구현은 `fixture/`에 격리돼 있고 008이 오면 그
디렉터리를 지우는 것으로 끝난다(계약 §4).

**시계열은 이쪽에만 있다.** Backend의 `MacroIndicatorPort`는 화면에 붙일 이름·단위·분야 순서만
읽는다 — 계산은 `data/`가 하므로 Backend가 시계열을 읽을 일이 없다(계약 §0.1, `AGENTS.md`).

**기준월은 이 소스가 알려 주지만 조회 쪽은 여기서 읽지 않는다.** 배치가
``regime_baseline_month``에 기록한 값을 읽는다 — 008이 새 발표분으로 T_g를 올려도 배치가 아직
돌지 않았으면 그 달에는 결과가 없기 때문이다(계약 §0.2).
"""

from __future__ import annotations

from datetime import date
from typing import Protocol, runtime_checkable

from data_pipeline.regime.macro.types import (
    BaselineMonths,
    IndicatorRegistry,
    MonthlyValue,
    Publication,
)


@runtime_checkable
class MacroIndicatorSource(Protocol):
    """배치가 쓰는 지표 소스.

    구현체는 픽스처(008 전)와 008 구현 둘이며, **같은 계약 테스트가 양쪽에 돈다**
    (계약 §5). 호출하는 쪽은 어느 구현체인지 알지 못한다 — 알게 되는 순간 픽스처를 지울 때
    운영 코드가 함께 깨진다.
    """

    def registry(self) -> IndicatorRegistry:
        """지표 목록·지표 → 분야 매핑·분야 이름과 순서·등록표 버전 (008 FR-002·FR-003)."""
        ...

    def monthly_series(self, indicator_id: str) -> dict[date, MonthlyValue]:
        """그 지표의 전 구간 월별 값 — 대상월 → 값 (008 FR-006).

        **결측 월은 키가 없다.** 0이나 직전 값으로 채워 돌려주지 않는다(008 FR-008). 010은
        그 구멍을 보고 척도 연속 구간(spec FR-005)과 지표별 비교 시점(spec FR-001)을 판정한다.
        """
        ...

    def raw_series(self, indicator_id: str) -> dict[date, float]:
        """그 지표의 변환 전 원값 — 대상월 → 원값 (008 FR-006).

        ⚠ **010의 계산·표시 경로에서 쓰지 않는다.** 거리도 설명 문장도 ``monthly_series()``의
        변환 후 값을 쓴다(spec FR-015, research R8). 008이 원값을 보존하는지 확인하는 용도다.
        """
        ...

    def baseline_months(self) -> BaselineMonths:
        """분야별 T_g와 전체 공통 T_all (008 FR-013).

        배치가 이 값으로 기준월을 정하고 ``regime_baseline_month``에 기록한다(계약 §0.2).
        """
        ...

    def publication(self, indicator_id: str, month: date) -> Publication | None:
        """그 값의 대상월·발표일·개정 여부 (008 FR-007).

        값이 없는 달이면 ``None``이다 — 없는 발표일을 지어내지 않는다.
        """
        ...

    def data_version(self) -> str:
        """현재 자료 버전 (008 FR-009).

        임시 대역의 값은 ``FIXTURE-``로 시작한다. 이 문자열이 식별 키·API 응답·기록에 실려
        다니므로 값이 어느 경로로 흘러가도 가짜임이 드러난다(계약 §4.2).
        """
        ...

    def changed_since(self, data_version: str) -> date | None:
        """그 버전 이후 값이 새로 들어오거나 개정된 **가장 오래된 대상월 M** (008 FR-009·FR-017).

        배치는 ``t ≥ M``인 기준월만 다시 계산한다(spec FR-030, research R3). 008이 답할 수
        없으면 ``None``이고, 그때는 계열 시작월을 M으로 삼아 전 구간을 계산한다 — 모르는 것을
        '바뀐 것 없음'으로 해석하지 않는다.
        """
        ...

    def is_sample_data(self) -> bool:
        """예시 자료인가 (008 FR-022). 화면이 이 사실을 숨길 수 없는 자리에 표시한다."""
        ...
