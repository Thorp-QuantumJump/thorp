"""공표 정밀도가 실제 원천 응답과 맞는가 (T069, spec 008 FR-015).

FR-015 는 "원천이 몇 자리까지 공표하는지는 **자료를 받는 쪽이 아는 사실**"이라고 정했다. 그래서
이 값은 기관 문서에서 옮겨 적는 것이 아니라 **받은 응답에서 잰다**. 표본은 2026-09-17 에 17개
지표를 실조회해 저장한 것이다(`tests/fixtures/macro/`).

**값을 적어 두기만 하면 근거가 코드 밖에 남는다.** 원천이 자릿수를 바꾸거나 표본을 갱신했을 때
등록표만 옛 값으로 남고, 그 어긋남은 아무도 보지 못한다. 그래서 여기서 다시 재어 대조한다.

<b>세는 것은 최대 자릿수다.</b> 응답은 값을 문자열로 주면서 뒤따르는 0 을 떼므로(``100`` 은
``100.0`` 일 수 있다) 자릿수 분포의 최댓값이 곧 공표 정밀도다. 평균이나 최빈값을 쓰면 정수로
떨어지는 달이 많은 지표에서 실제보다 낮게 잡힌다.
"""

from __future__ import annotations

import collections
import json
import re
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Final

import pytest

from data_pipeline.macro.registry import INDICATORS

FIXTURES: Final[Path] = Path(__file__).parent / "fixtures" / "macro"

_NUMBER: Final[re.Pattern[str]] = re.compile(r"-?\d+(\.\d+)?")

#: 관세청은 한 응답에 세 지표가 함께 온다. 지표 id → 응답 필드.
_CUSTOMS_FIELDS: Final[dict[str, str]] = {
    "exports": "expDlr",
    "imports": "impDlr",
    "trade_balance": "balPayments",
}

_ECOS_FILES: Final[dict[str, str]] = {
    "dubai_crude": "dubai_crude_monthly.json",
    "treasury_bond_3y": "treasury_bond_3y_daily.json",
    "usd_krw_rate": "usd_krw_monthly.json",
}

#: 표본 파일 이름이 지표 id 와 다른 것들.
_KOSIS_FILES: Final[dict[str, str]] = {
    "consumer_price_index": "cpi.json",
    "housing_sale_price": "housing_sale_price_current.json",
}


def _decimals(values: list[object]) -> collections.Counter[int]:
    counted: collections.Counter[int] = collections.Counter()
    for value in values:
        text = str(value).strip()
        if not _NUMBER.fullmatch(text):
            continue
        counted[len(text.split(".")[1]) if "." in text else 0] += 1
    return counted


def _kosis_values(path: Path) -> list[object]:
    document = json.loads(path.read_text(encoding="utf-8"))
    return [row["DT"] for row in document if isinstance(row, dict) and row.get("DT") is not None]


def _ecos_values(path: Path) -> list[object]:
    document = json.loads(path.read_text(encoding="utf-8"))
    rows = document.get("StatisticSearch", {}).get("row", [])
    return [row.get("DATA_VALUE") for row in rows]


def _customs_values(field: str) -> list[object]:
    root = ElementTree.fromstring(
        (FIXTURES / "customs" / "newtrade_2026.xml").read_text(encoding="utf-8")
    )
    values: list[object] = []
    for item in root.findall("./body/items/item"):
        if (item.findtext("year") or "").strip() == "총계":
            continue
        text = item.findtext(field)
        if text:
            values.append(text)
    return values


def _oecd_values() -> list[object]:
    document = json.loads(
        (FIXTURES / "oecd" / "g20_cli_aa_ix.json").read_text(encoding="utf-8")
    )
    values: list[object] = []

    def walk(node: object) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "observations" and isinstance(value, dict):
                    for entry in value.values():
                        if isinstance(entry, list) and entry and isinstance(entry[0], (int, float)):
                            # SDMX 는 수로 준다. 뒤따르는 0 이 없으므로 repr 이 곧 공표 표기다.
                            values.append(repr(entry[0]))
                else:
                    walk(value)
        elif isinstance(node, list):
            for entry in node:
                walk(entry)

    walk(document)
    return values


def _sample_values(indicator_id: str) -> list[object]:
    if indicator_id in _CUSTOMS_FIELDS:
        return _customs_values(_CUSTOMS_FIELDS[indicator_id])
    if indicator_id in _ECOS_FILES:
        return _ecos_values(FIXTURES / "ecos" / _ECOS_FILES[indicator_id])
    if indicator_id == "g20_cli":
        return _oecd_values()
    name = _KOSIS_FILES.get(indicator_id, f"{indicator_id}.json")
    return _kosis_values(FIXTURES / "kosis" / name)


@pytest.mark.parametrize("indicator", INDICATORS, ids=lambda i: i.indicator_id)
def test_등록표의_공표_정밀도가_원천_응답과_같다(indicator) -> None:
    values = _sample_values(indicator.indicator_id)
    assert values, f"{indicator.indicator_id}: 표본에 값이 없다"
    measured = max(_decimals(values))
    assert indicator.published_precision == measured, (
        f"{indicator.indicator_id}: 등록표는 {indicator.published_precision} 자리인데"
        f" 표본은 {measured} 자리까지 준다. 원천이 표기를 바꿨는지 본다"
    )


def test_주택가격은_원천이_여덟_자리로_준다() -> None:
    """눈에 띄는 값이라 따로 고정한다.

    지수인데 소수 여덟 자리다. 화면에 그대로 내보낼 값이 아니라는 뜻이지만, 원천이 그렇게 주는
    것이 사실이므로 등록표는 사실을 적는다 — 보기 좋게 줄여 적으면 그 반올림이 어디서 일어났는지
    나중에 되짚을 수 없다.
    """
    housing = next(i for i in INDICATORS if i.indicator_id == "housing_sale_price")
    assert housing.published_precision == 8
    values = _sample_values("housing_sale_price")
    assert any("." in str(v) and len(str(v).split(".")[1]) == 8 for v in values)
