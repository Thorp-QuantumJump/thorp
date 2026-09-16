"""작업 메시지 본문 EngineInput(schemaVersion 2). 기준: README §4.1, 픽스처 engine-input*.json.

구조(필드 이름·타입)뿐 아니라 값의 불변식도 역직렬화와 함께 검증한다(README §8).
검증은 "메시지만 보고 판단할 수 있고 수신자가 누구든 답이 같은 것"으로 한정한다.
엔진이 실제로 지원하는 값의 범위는 여기서 다루지 않는다 — 엔진 쪽 책임이다.
"""

import re
from datetime import date
from decimal import Decimal
from enum import StrEnum
from typing import Any

from pydantic import Field, PrivateAttr, field_validator, model_validator

from worker.contracts.base import ContractModel
from worker.contracts.strategy_params import STRATEGY_PARAMETERS

# 종목 코드 표기: 6글자 단축코드 + KRX 거래소 코드 (예 005930.XKRX, 0111J0.XKRX).
# 단축코드에는 영문 대문자가 섞일 수 있다 — ETF 1,168종 중 304종이 그런 코드다(PD-01 실측).
_INSTRUMENT_ID_PATTERN = re.compile(r"[0-9A-Z]{6}\.XKRX")
# 통화 코드 표기: 3글자 대문자 (예 KRW)
_CURRENCY_PATTERN = re.compile(r"[A-Z]{3}")


def _require_text(value: str, field: str) -> str:
    """빈 문자열·공백만 있는 문자열을 거부한다."""
    if not value.strip():
        raise ValueError(f"{field} must be a non-empty string")
    return value


def _require_instrument_id(value: str, field: str) -> str:
    if not _INSTRUMENT_ID_PATTERN.fullmatch(value):
        raise ValueError(f"{field} must look like '{{6-character code}}.XKRX'; got {value!r}")
    return value


class Interval(StrEnum):
    DAY_1 = "1D"


class Adjustment(StrEnum):
    SPLIT_ADJUSTED = "SPLIT_ADJUSTED"


class PriceType(StrEnum):
    LAST = "LAST"


class PriceSource(StrEnum):
    """체결(fillPrice)·신호(signalPrice)에 쓰는 가격 계열. 계약 고정: RAW / ADJUSTED."""

    RAW = "RAW"
    ADJUSTED = "ADJUSTED"


class DistributionPolicy(StrEnum):
    CASH_ON_PAY_DATE = "CASH_ON_PAY_DATE"


class Scenario(StrEnum):
    """주 분석 또는 별도 시나리오(spec 004 FR-016)."""

    FULL_PERIOD = "FULL_PERIOD"
    FRESH_CASH_FROM = "FRESH_CASH_FROM"
    COST_ESCALATION = "COST_ESCALATION"
    TARGET_RISK = "TARGET_RISK"


class PositionSizingType(StrEnum):
    STRATEGY_DEFINED = "STRATEGY_DEFINED"


class SignalTiming(StrEnum):
    BAR_CLOSE = "BAR_CLOSE"


class ExecutionTiming(StrEnum):
    NEXT_BAR_OPEN = "NEXT_BAR_OPEN"


class OrderType(StrEnum):
    MARKET = "MARKET"


class LiquidityModel(StrEnum):
    UNLIMITED = "UNLIMITED"


class TradingHaltPolicy(StrEnum):
    DEFER_TO_NEXT_TRADABLE = "DEFER_TO_NEXT_TRADABLE"


class DelistingPolicy(StrEnum):
    CAUSE_BASED = "CAUSE_BASED"


class RunSpec(ContractModel):
    run_id: str  # 이벤트 envelope의 runId와 같다
    start: date
    end: date
    effective_start: date | None = None  # None = 미산출(엔진이 자료로 정한다)
    random_seed: int | None = None  # None = 미지정
    scenario: Scenario
    scenario_params: dict[str, Any] | None = None  # TARGET_RISK: targetVolatility 등
    scenario_rules: dict[str, Any] | None = None  # 시나리오 규칙 사본(등록표)

    @field_validator("run_id")
    @classmethod
    def _run_id_present(cls, value: str) -> str:
        return _require_text(value, "run.runId")

    @model_validator(mode="after")
    def _dates_ordered(self) -> "RunSpec":
        if self.end < self.start:
            raise ValueError(f"run.end ({self.end}) must be on or after run.start ({self.start})")
        if self.effective_start is not None and not self.start <= self.effective_start <= self.end:
            raise ValueError(
                f"run.effectiveStart ({self.effective_start}) must lie within "
                f"[run.start, run.end] = [{self.start}, {self.end}]"
            )
        return self


class UniverseItem(ContractModel):
    sector_id: str | None = None  # None = 대응표 없이 종목 직접 지정
    instrument_id: str  # "{6글자 단축코드}.XKRX"
    target_weight: Decimal | None = None  # FIXED_WEIGHT에서만 존재

    @field_validator("instrument_id")
    @classmethod
    def _krx_instrument_id(cls, value: str) -> str:
        return _require_instrument_id(value, "universe.items[].instrumentId")

    @field_validator("target_weight")
    @classmethod
    def _target_weight_is_a_ratio(cls, value: Decimal | None) -> Decimal | None:
        if value is not None and not 0 <= value <= 1:
            raise ValueError(
                f"universe.items[].targetWeight must be a ratio in [0, 1]; got {value}"
            )
        return value


class UniverseSpec(ContractModel):
    mapping_version: str | None = None  # None = 대응표 없이 종목 직접 지정(003 연동 전)
    items: list[UniverseItem]

    @field_validator("items")
    @classmethod
    def _items_present_and_unique(cls, value: list[UniverseItem]) -> list[UniverseItem]:
        if not value:
            raise ValueError("universe.items must contain at least one instrument")
        ids = [item.instrument_id for item in value]
        duplicated = sorted({item for item in ids if ids.count(item) > 1})
        if duplicated:
            raise ValueError(f"universe.items must not repeat an instrument: {duplicated}")
        return value

    @property
    def instrument_ids(self) -> list[str]:
        """종목 코드만 순서대로. 대응표(sectorId)는 엔진이 알 필요가 없다."""
        return [item.instrument_id for item in self.items]


class MarketDataSpec(ContractModel):
    dataset_id: str
    data_version: str | None = None  # None = 미지정(공급자가 정한다)
    interval: Interval
    adjustment: Adjustment
    price_type: PriceType
    fill_price: PriceSource
    signal_price: PriceSource
    distributions: DistributionPolicy

    @field_validator("dataset_id")
    @classmethod
    def _dataset_id_present(cls, value: str) -> str:
        return _require_text(value, "marketData.datasetId")


class AllocationSpec(ContractModel):
    """배분 전략 지정. v1의 ``strategy`` 블록에 규칙 사본·버전이 붙은 것이다."""

    strategy_id: str
    rules_version: str
    # 규칙 명세 사본(등록표). None = 등록표 연동 전. 엔진은 자체 기본값을 두지 않는다.
    rules: dict[str, Any] | None = None
    # 전략별 자유 형식. 정수는 int, 소수는 float로 그대로 통과 (문자열 변환 금지).
    # 봉투는 dict로 남기고, 어떤 키가 오는지는 worker.contracts.strategy_params 가 선언한다.
    parameters: dict[str, Any]

    # 레지스트리에 등록된 전략의 검증 결과. PrivateAttr이므로 model_dump에 나오지 않는다.
    _typed_parameters: ContractModel | None = PrivateAttr(default=None)

    @field_validator("strategy_id")
    @classmethod
    def _strategy_id_present(cls, value: str) -> str:
        return _require_text(value, "allocation.strategyId")

    @field_validator("rules_version")
    @classmethod
    def _rules_version_present(cls, value: str) -> str:
        return _require_text(value, "allocation.rulesVersion")

    @model_validator(mode="after")
    def _validate_registered_parameters(self) -> "AllocationSpec":
        """등록된 전략이면 parameters를 전략별 모델로 검증한다.

        미등록 strategyId는 통과시킨다 — 엔진이 지원하는 전략의 범위는 메시지만 보고
        판단할 수 없어서 계약 검증의 몫이 아니다.
        """
        model = STRATEGY_PARAMETERS.get(self.strategy_id)
        if model is not None:
            self._typed_parameters = model.model_validate(self.parameters)
        return self

    @property
    def typed_parameters(self) -> ContractModel:
        """전략별 모델로 검증된 parameters. 미등록 ``strategyId`` 면 오류를 낸다."""
        if self._typed_parameters is None:
            known = ", ".join(sorted(STRATEGY_PARAMETERS)) or "(none)"
            raise LookupError(
                f"allocation.strategyId {self.strategy_id!r} has no registered parameter "
                f"schema; known strategies: {known}"
            )
        return self._typed_parameters


class PositionSizing(ContractModel):
    type: PositionSizingType
    max_position_pct: Decimal | None = None  # None = 제한 없음

    @field_validator("max_position_pct")
    @classmethod
    def _max_position_pct_is_a_ratio(cls, value: Decimal | None) -> Decimal | None:
        if value is not None and not 0 < value <= 1:
            raise ValueError(
                f"portfolio.positionSizing.maxPositionPct must be a ratio in (0, 1]; got {value}"
            )
        return value


class PortfolioSpec(ContractModel):
    initial_cash: Decimal
    currency: str
    position_sizing: PositionSizing
    short_selling: bool
    leverage: Decimal
    integer_lots: bool

    @field_validator("initial_cash")
    @classmethod
    def _initial_cash_is_positive(cls, value: Decimal) -> Decimal:
        if not value > 0:
            raise ValueError(f"portfolio.initialCash must be greater than 0; got {value}")
        return value

    @field_validator("currency")
    @classmethod
    def _currency_is_three_letter_code(cls, value: str) -> str:
        if not _CURRENCY_PATTERN.fullmatch(value):
            raise ValueError(
                f"portfolio.currency must be a 3-letter uppercase code such as KRW; got {value!r}"
            )
        return value

    @field_validator("leverage")
    @classmethod
    def _leverage_at_least_one(cls, value: Decimal) -> Decimal:
        if not value >= 1:
            raise ValueError(f"portfolio.leverage must be 1 or greater; got {value}")
        return value


class CostProfile(ContractModel):
    """비용 조건 버전(``cost_profile``). bps 값은 plain 문자열 decimal.

    ``provisional=True`` 는 팀 결정(PD-07) 전 임시값이라는 표시이며 결과 ``versions`` 에 그대로
    남는다.
    """

    version: str
    commission_bps: Decimal = Field(ge=0)
    spread_bps: Decimal = Field(ge=0)
    slippage_bps: Decimal = Field(ge=0)
    tax_bps: Decimal = Field(ge=0)
    provisional: bool

    @field_validator("version")
    @classmethod
    def _version_present(cls, value: str) -> str:
        return _require_text(value, "execution.costProfile.version")


class LiquiditySpec(ContractModel):
    model: LiquidityModel


class ExecutionSpec(ContractModel):
    signal_timing: SignalTiming
    execution_timing: ExecutionTiming
    order_types: list[OrderType]
    cost_profile: CostProfile
    partial_fill: bool
    liquidity: LiquiditySpec
    trading_halt: TradingHaltPolicy
    delisting: DelistingPolicy

    @field_validator("order_types")
    @classmethod
    def _order_types_present(cls, value: list[OrderType]) -> list[OrderType]:
        if not value:
            raise ValueError("execution.orderTypes must contain at least one order type")
        return value


class EvaluationSpec(ContractModel):
    split_version: str | None = None  # None = 미지정
    # None = 엔진이 전략 관측 기간으로 정한다
    warmup_trading_days: int | None = Field(default=None, ge=0)
    liquidation_at_end: bool


class ProgressSpec(ContractModel):
    batch_trading_days: int = Field(gt=0)  # PROGRESS 발행 묶음 크기(거래일)


class EngineInput(ContractModel):
    """백엔드가 조립해 ``backtest.jobs`` 로 발행하는 작업 메시지. 엔진은 이것만 받는다."""

    run: RunSpec
    universe: UniverseSpec
    market_data: MarketDataSpec
    allocation: AllocationSpec
    portfolio: PortfolioSpec
    execution: ExecutionSpec
    evaluation: EvaluationSpec
    progress: ProgressSpec
