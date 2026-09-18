"""전략 규칙이 확정됐는지 판정한다(spec 004 T007, R-08, FR-010).

"이 엔진이 구현하지 않았다"와 "팀이 아직 정하지 않았다"는 다른 사실이고 사용자가 할 일도 다르다.
앞은 워커를 고치면 되고, 뒤는 아무도 고칠 수 없다 — 결정이 날 때까지 그 전략은 돌 수 없다. 두
경우에 같은 말을 하면 사용자는 기다려야 하는지 신고해야 하는지 알 수 없다.

**미확정 전략을 그럴듯한 기본값으로 돌리지 않는다.** 돌리면 나중에 규칙이 정해졌을 때 같은 요청이
다른 숫자를 내고, 그사이 사용자가 본 결과는 아무 근거 없는 값이었던 것이 된다(FR-010).
"""

from __future__ import annotations

#: 팀 결정을 기다리는 전략 → 결정 항목. 값이 정해지면 등록표 행을 채우고 여기서 뺀다.
#:
#: **지금은 비어 있다** — 다섯 전략이 2026-09-17 PD-03·PD-04 확정으로 모두 등록됐다. 표를 지우지
#: 않는 이유는 규칙이 아직 없는 전략을 나중에 더할 때 같은 구분이 다시 필요하기 때문이다. 비어
#: 있으면 모르는 전략은 전부 "이 엔진이 구현하지 않았다"로 거절된다.
PENDING_DECISION: dict[str, str] = {}


def pending_decision_ref(strategy_id: str) -> str | None:
    """팀 결정 대기 중이면 그 결정 항목, 아니면 None."""
    return PENDING_DECISION.get(strategy_id)


def rules_not_registered_message(strategy_id: str) -> str:
    """거절 사유 한 문장. 왜 막혔고 언제 풀리는지를 담는다."""
    decision = pending_decision_ref(strategy_id)
    if decision is not None:
        return (
            f"RULES_NOT_REGISTERED: {strategy_id} rules are not decided yet ({decision});"
            " running it with a guessed rule would produce numbers nobody can defend"
        )
    return (
        f"RULES_NOT_REGISTERED: {strategy_id} has no registered allocation rule in this engine"
        " (spec 004 R-08)"
    )
