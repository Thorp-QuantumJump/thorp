"""Local diagnostic artifacts of one engine run — deliberately NOT a message contract.

Nothing in this module is published to the broker. The message contract is
``worker/contracts/engine_output.py`` (mirrored by the backend ``EngineOutput`` record) and
the backend ignores unknown fields, so anything defined here would silently disappear if it
were attached to ``EngineOutput``. It is kept out of the message on purpose: a full
parameter sweep would otherwise carry an order-level audit log per run.

Both payloads must survive somewhere, just not in the message:

- ``orders`` is the order-level audit log (signal, submission, fill or rejection) that
  explains how a strategy reached its numbers.
- ``metadata`` is the reproducibility record (engine version, input hash, data hash, metric
  version) that says which code and which data produced those numbers.

The engine returns these next to the message from ``run_with_artifacts``; callers persist
them locally.
"""

from datetime import datetime
from decimal import Decimal
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class Artifact(BaseModel):
    """Same camelCase serialization as the contract models, for readable local JSON."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class OrderRecord(Artifact):
    """One order's audit trail: close-time signal, next-open submission, fill or rejection."""

    order_id: str
    signal_time: datetime
    quantity: Decimal
    side: Literal["BUY", "SELL"]
    status: Literal["PENDING", "FILLED", "REJECTED"] = "PENDING"
    submitted_time: datetime | None = None
    fill_time: datetime | None = None
    reference_price: Decimal | None = None
    fill_price: Decimal | None = None
    reason: str | None = None


class RunArtifacts(Artifact):
    orders: list[OrderRecord] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)
