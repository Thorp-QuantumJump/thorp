"""작업 메시지 본문 EngineInput. 기준: README §4.1, 픽스처 engine-input.json.

구조(필드 이름·타입)뿐 아니라 값의 불변식도 역직렬화와 함께 검증한다(README §8).
검증은 "메시지만 보고 판단할 수 있고 수신자가 누구든 답이 같은 것"으로 한정한다.
엔진이 실제로 지원하는 값의 범위는 여기서 다루지 않는다 — 엔진 쪽 책임이다.
"""

import re
from datetime import date
from decimal import Decimal
from enum import StrEnum
from typing import Any

from pydantic import PrivateAttr, field_validator, model_validator

from worker.contracts.base import ContractModel
from worker.contracts.strategy_params import STRATEGY_PARAMETERS

# 종목 코드 표기: 6자리 숫자 + KRX 거래소 코드 (예 005930.XKRX)
_INSTRUMENT_ID_PATTERN = re.compile(r"\d{6}\.XKRX")
# 통화 코드 표기: 3글자 대문자 (예 KRW)
_CURRENCY_PATTERN = re.compile(r"[A-Z]{3}")


def _require_text(value: str, field: str) -> str:
    """빈 문자열·공백만 있는 문자열을 거부한다."""
    if not value.strip():
        raise ValueError(f"{field} must be a non-empty string")
    return value


class Interval(StrEnum):
    DAY_1 = "1D"


class Adjustment(StrEnum):
    SPLIT_ADJUSTED = "SPLIT_ADJUSTED"


class PriceType(StrEnum):
    LAST = "LAST"


class PositionSizingType(StrEnum):
    STRATEGY_DEFINED = "STRATEGY_DEFINED"


class SignalTiming(StrEnum):
    BAR_CLOSE = "BAR_CLOSE"


class ExecutionTiming(StrEnum):
    NEXT_BAR_OPEN = "NEXT_BAR_OPEN"


class OrderType(StrEnum):
    MARKET = "MARKET"


class CommissionModel(StrEnum):
    """거래비용 모델 (ADR-0004).

    ``ZERO`` 는 수수료·세금·제비용을 모두 0으로 계산하며 ``broker`` 를 쓰지 않는다. 임시
    우회값이 아니라 "거래비용을 제외한 순수 전략 성능"을 뜻하는 정식 모델이다.
    ``BROKER`` 는 증권사 요율표대로 계산하고 ``broker`` 가 필수다(요율표 구현은 전략 비교
    단계의 별도 티켓 — ADR-0004 D3).
    """

    BROKER = "BROKER"
    ZERO = "ZERO"


class SlippageModel(StrEnum):
    FIXED_BPS = "FIXED_BPS"


class LiquidityModel(StrEnum):
    UNLIMITED = "UNLIMITED"


class TradingHaltPolicy(StrEnum):
    WAIT_UNTIL_RESUME = "WAIT_UNTIL_RESUME"


class DelistingPolicy(StrEnum):
    CAUSE_BASED = "CAUSE_BASED"


class RunSpec(ContractModel):
    backtest_id: str
    start: date
    end: date
    random_seed: int | None = None  # None = 미지정

    @model_validator(mode="after")
    def _dates_ordered(self) -> "RunSpec":
        if self.end < self.start:
            raise ValueError(f"run.end ({self.end}) must be on or after run.start ({self.start})")
        return self


class UniverseSpec(ContractModel):
    instrument_ids: list[str]  # "{6자리코드}.XKRX"

    @field_validator("instrument_ids")
    @classmethod
    def _krx_instrument_ids(cls, value: list[str]) -> list[str]:
        if not value:
            raise ValueError("universe.instrumentIds must contain at least one instrument")
        malformed = [item for item in value if not _INSTRUMENT_ID_PATTERN.fullmatch(item)]
        if malformed:
            raise ValueError(
                "universe.instrumentIds entries must look like '{6-digit code}.XKRX'; "
                f"got {malformed}"
            )
        duplicated = sorted({item for item in value if value.count(item) > 1})
        if duplicated:
            raise ValueError(f"universe.instrumentIds must not repeat an instrument: {duplicated}")
        return value


class MarketDataSpec(ContractModel):
    dataset_id: str
    interval: Interval
    adjustment: Adjustment
    price_type: PriceType

    @field_validator("dataset_id")
    @classmethod
    def _dataset_id_present(cls, value: str) -> str:
        return _require_text(value, "marketData.datasetId")


class StrategySpec(ContractModel):
    strategy_id: str
    version: str
    # 전략별 자유 형식. 정수는 int, 소수는 float로 그대로 통과 (문자열 변환 금지).
    # 봉투는 dict로 남기고, 어떤 키가 오는지는 worker.contracts.strategy_params 가 선언한다.
    # 여기를 전략별 union으로 바꾸면 전략을 추가할 때마다 이 파일·백엔드 record·픽스처를
    # 함께 고쳐야 한다.
    parameters: dict[str, Any]

    # 레지스트리에 등록된 전략의 검증 결과. PrivateAttr이므로 model_dump에 나오지 않는다.
    _typed_parameters: ContractModel | None = PrivateAttr(default=None)

    @field_validator("strategy_id")
    @classmethod
    def _strategy_id_present(cls, value: str) -> str:
        return _require_text(value, "strategy.strategyId")

    @field_validator("version")
    @classmethod
    def _version_present(cls, value: str) -> str:
        return _require_text(value, "strategy.version")

    @model_validator(mode="after")
    def _validate_registered_parameters(self) -> "StrategySpec":
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
        """전략별 모델로 검증된 parameters.

        미등록 ``strategyId`` 라 검증된 값이 없으면 오류를 낸다.
        """
        if self._typed_parameters is None:
            known = ", ".join(sorted(STRATEGY_PARAMETERS)) or "(none)"
            raise LookupError(
                f"strategy.strategyId {self.strategy_id!r} has no registered parameter schema; "
                f"known strategies: {known}"
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


class CommissionSpec(ContractModel):
    model: CommissionModel
    broker: str | None = None  # model=BROKER일 때 필수 (예 KIWOOM)
    version: str | None = None  # 요율표 버전 (예 2026-09)

    @model_validator(mode="after")
    def _broker_required_for_broker_model(self) -> "CommissionSpec":
        if self.model is CommissionModel.BROKER and not (self.broker or "").strip():
            raise ValueError(
                "execution.commission.broker is required when commission.model is BROKER"
            )
        return self


class SlippageSpec(ContractModel):
    model: SlippageModel
    bps: int | None = None  # model=FIXED_BPS일 때 필수

    @model_validator(mode="after")
    def _bps_required_for_fixed_bps_model(self) -> "SlippageSpec":
        if self.model is not SlippageModel.FIXED_BPS:
            return self
        if self.bps is None:
            raise ValueError("execution.slippage.bps is required when slippage.model is FIXED_BPS")
        if self.bps < 0:
            raise ValueError(f"execution.slippage.bps must not be negative; got {self.bps}")
        return self


class LiquiditySpec(ContractModel):
    model: LiquidityModel


class ExecutionSpec(ContractModel):
    signal_timing: SignalTiming
    execution_timing: ExecutionTiming
    order_types: list[OrderType]
    commission: CommissionSpec
    slippage: SlippageSpec
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


class BenchmarkSpec(ContractModel):
    instrument_id: str


class EngineInput(ContractModel):
    """백엔드가 조립해 ``backtest.jobs`` 로 발행하는 작업 메시지. 엔진은 이것만 받는다."""

    run: RunSpec
    universe: UniverseSpec
    market_data: MarketDataSpec
    strategy: StrategySpec
    portfolio: PortfolioSpec
    execution: ExecutionSpec
    benchmark: BenchmarkSpec | None = None  # None = 벤치마크 미지정
