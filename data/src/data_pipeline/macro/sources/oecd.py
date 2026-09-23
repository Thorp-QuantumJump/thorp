"""경제협력개발기구 어댑터 — G20 경기선행지수 (spec 008, pd11 §3).

인증키가 없고 전 구간 3,131행이 한 번에 오므로 분할도 필요 없다. 대신 SDMX 응답이 값과 대상월을
따로 두는 구조라 그 둘을 짝짓는 일이 이 모듈의 일이다.

**관측 키는 자리 번호이지 대상월이 아니다.** ``observations`` 의 키 ``"0"`` 은 첫 번째 대상월이
아니라 ``structures[0].dimensions.observation[0].values`` 의 0번째 값을 가리킨다. 그리고 그
목록은 시간순이 아니다 — 저장된 표본에서 0번이 2018-05, 103번이 2018-06 이다. 키를 대상월로
바로 읽거나 목록이 정렬돼 있다고 믿으면 값이 엉뚱한 달에 붙는데, 지수는 이웃한 달끼리 값이 비슷해
눈으로는 틀린 줄 모른다.

**계열 키의 차원을 다 적어야 한다.** DSD_STES 의 차원은 아홉이고 모자라면 자료 대신
``403 Not enough key values in query`` 가 온다. 등록표가 완성된 키를 들고 있다(FR-005).

**증가율은 우리가 지수에서 계산한다**(FR-019). 원천도 증가율 계열(``TRANSFORMATION=GY``)을 주지만
지수보다 여섯 달 늦어, 그걸 쓰면 해외 경기 분야의 기준월과 전체 기준월이 그만큼 물린다.

**``prepared`` 를 개정 식별자로 쓰지 않는다.** 그것은 응답을 만든 시각이라 같은 자료를 다시 받아도
매번 달라진다. 데이터셋 갱신일은 응답 헤더 ``Last-Modified`` 이고, 헤더는 본문에 없으므로 부르는
쪽이 넘겨준다.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any, Final

from data_pipeline.macro.registry import IndicatorDefinition
from data_pipeline.macro.sources.base import (
    SourceContractError,
    SourceObservation,
    parse_decimal,
    require_mapping,
)

#: 계열 키의 차원 순서. 자리 수가 이와 다르면 원천이 구조를 바꾼 것이다.
KEY_DIMENSIONS: Final[tuple[str, ...]] = (
    "REF_AREA",
    "FREQ",
    "MEASURE",
    "UNIT_MEASURE",
    "ACTIVITY",
    "ADJUSTMENT",
    "TRANSFORMATION",
    "TIME_HORIZ",
    "METHODOLOGY",
)

#: 대상월이 놓이는 차원.
_TIME_DIMENSION: Final[str] = "TIME_PERIOD"


def split_key(indicator: IndicatorDefinition) -> tuple[str, ...]:
    """등록표의 계열 키를 차원별로 나눈다. 빈 자리는 "아무 값이나"를 뜻한다."""
    if not indicator.item_code:
        raise SourceContractError(f"{indicator.indicator_id}: 등록표에 계열 키가 없다")
    parts = tuple(indicator.item_code.split("."))
    if len(parts) != len(KEY_DIMENSIONS):
        raise SourceContractError(
            f"{indicator.indicator_id}: 계열 키의 자리가 {len(parts)}개다."
            f" DSD_STES 의 차원은 {len(KEY_DIMENSIONS)}개이고 값을 지정하지 않는 자리도"
            " 점으로 남겨야 한다"
        )
    return parts


def build_path(indicator: IndicatorDefinition) -> str:
    """조회 경로를 만든다. 인증키가 없는 원천이다."""
    return f"{indicator.table_code},/{indicator.item_code}"


def _structure(payload: Any, context: str) -> Mapping[str, Any]:
    body = require_mapping(payload, context)
    data = require_mapping(body.get("data"), f"{context} data")
    structures = data.get("structures")
    if not isinstance(structures, Sequence) or not structures:
        raise SourceContractError(f"{context}: 응답에 structures 가 없다. 구조가 바뀌었는지 본다")
    return require_mapping(structures[0], f"{context} structures[0]")


def _periods(structure: Mapping[str, Any], context: str) -> tuple[str, ...]:
    """관측 자리 번호 순서대로 대상월을 늘어놓는다. **정렬하지 않는다** — 자리 번호가 색인이다."""
    dimensions = require_mapping(structure.get("dimensions"), f"{context} dimensions")
    observation = dimensions.get("observation")
    if not isinstance(observation, Sequence) or not observation:
        raise SourceContractError(f"{context}: 관측 차원이 없다")
    time_dimension = require_mapping(observation[0], f"{context} 관측 차원")
    if time_dimension.get("id") != _TIME_DIMENSION:
        raise SourceContractError(
            f"{context}: 관측 차원이 {_TIME_DIMENSION} 가 아니라"
            f" {time_dimension.get('id')} 다"
        )
    return tuple(str(value["id"]) for value in time_dimension["values"])


def _check_series_key(
    indicator: IndicatorDefinition, structure: Mapping[str, Any], context: str
) -> None:
    """응답이 등록표가 고른 그 계열인지 본다.

    진폭조정 지수(``AA``/``IX``)를 부르고 증가율(``TR``/``GY``)이 오면 값의 뜻이 통째로 다르다.
    """
    dimensions = require_mapping(structure.get("dimensions"), f"{context} dimensions")
    series = dimensions.get("series")
    if not isinstance(series, Sequence):
        raise SourceContractError(f"{context}: 계열 차원이 없다")
    actual = {str(d["id"]): [str(v["id"]) for v in d["values"]] for d in series}

    if tuple(actual) != KEY_DIMENSIONS:
        raise SourceContractError(
            f"{context}: 계열 차원이 {', '.join(actual)} 다."
            f" 아는 차원은 {', '.join(KEY_DIMENSIONS)}"
        )
    for dimension, expected in zip(KEY_DIMENSIONS, split_key(indicator), strict=True):
        if not expected:
            continue
        values = actual[dimension]
        if values != [expected]:
            raise SourceContractError(
                f"{context}: 등록표의 {dimension} 는 {expected} 인데 {', '.join(values)} 이(가)"
                " 왔다. 다른 계열이 섞였는지 본다"
            )


def parse(
    indicator: IndicatorDefinition, payload: Any, *, last_modified: str | None = None
) -> list[SourceObservation]:
    """저장된 응답이든 방금 받은 응답이든 같은 규칙으로 읽는다.

    ``last_modified`` 는 응답 헤더의 데이터셋 갱신일이다. 본문에 없으므로 부르는 쪽이 넘긴다.
    """
    context = f"oecd {indicator.indicator_id}"
    body = require_mapping(payload, context)
    structure = _structure(payload, context)
    _check_series_key(indicator, structure, context)
    periods = _periods(structure, context)

    data = require_mapping(body["data"], f"{context} data")
    datasets = data.get("dataSets")
    if not isinstance(datasets, Sequence) or not datasets:
        raise SourceContractError(f"{context}: 응답에 dataSets 가 없다")
    series = require_mapping(datasets[0], f"{context} dataSets[0]").get("series")
    if not isinstance(series, Mapping) or not series:
        # 조회 구간에 자료가 없을 때 온다. 실패가 아니라 빈 결과다.
        return []
    if len(series) != 1:
        raise SourceContractError(
            f"{context}: 계열이 {len(series)}개 왔다. 등록표는 하나를 고른다"
        )

    observations_by_index = require_mapping(
        next(iter(series.values())), f"{context} 계열"
    ).get("observations")
    if not isinstance(observations_by_index, Mapping):
        raise SourceContractError(f"{context}: 계열에 observations 가 없다")

    result: list[SourceObservation] = []
    for raw_index, cell in observations_by_index.items():
        # 자리 번호가 숫자가 아니면 원천이 관측 차원 구성을 바꾼 것이다. 맨 ValueError 로
        # 올리면 base 의 원천 오류 분류를 벗어나 재시도·자동 수집 중단 처리가 걸리지 않는다.
        try:
            index = int(raw_index)
        except (TypeError, ValueError):
            raise SourceContractError(
                f"{context}: 관측 자리 번호가 숫자가 아니다({raw_index!r})."
                " dimensionAtObservation 구성이 바뀌었는지 본다"
            ) from None
        if index >= len(periods):
            raise SourceContractError(
                f"{context}: 관측 자리 {index} 에 맞는 대상월이 없다."
                f" 대상월은 {len(periods)}개다"
            )
        period = periods[index]
        # 관측 칸은 `[값, 상태...]` 꼴이다. 다른 모양이면 결측으로 넘기지 않고 멈춘다 —
        # 결측으로 두면 사유가 '원천이 값을 안 줬다'로 남아 실제 원인(응답 구조 변경)을
        # 가린다. 그러면 원천을 잘못 지목한 채 전 구간이 비어 발행이 보류된다.
        if not isinstance(cell, Sequence) or isinstance(cell, str) or not cell:
            raise SourceContractError(
                f"{context} {period}: 관측 칸이 목록이 아니다({cell!r})."
                " 응답 구조가 바뀌었는지 본다"
            )
        value = cell[0]
        result.append(
            SourceObservation(
                indicator_id=indicator.indicator_id,
                period=period,
                value=parse_decimal(value, f"{context} {period}"),
                unit=indicator.raw_unit,
                # 응답 헤더의 데이터셋 갱신일이다. 본문의 prepared 는 응답 생성
                # 시각이라 쓰지 않는다.
                source_updated_at=last_modified,
            )
        )
    result.sort(key=lambda observation: observation.period)
    return result
