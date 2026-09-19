"""결과 재현 판정에 쓰는 해시 정의(spec 004 T029·T030).

골든 테스트(``tests/test_rules_goldens.py``)와 환경 간 비교 스크립트
(``scripts/reproducibility_report.py``)가 "같은 결과"를 같은 뜻으로 재도록 한 곳에 둔다.

- ``computed``: 실행 시각과 이름표(``versions``)를 뺀 계산 결과
- ``rounded``: 수(숫자 문자열 포함)를 유효숫자 12자리로 맞춘다(float 끝자리 흔들림 흡수)
- ``digest``: 반올림한 정규 JSON의 sha256 — 골든이 지키는 값
- ``exact_digest``: 반올림 없는 정규 JSON의 sha256 — 환경 간 비교에서 바이트 단위 일치를 본다
"""

from __future__ import annotations

import hashlib
import json
import re
from decimal import Context, Decimal
from typing import Any

from worker.contracts.engine_output import EngineOutput

#: 해시에 넣기 전에 수를 맞추는 유효숫자 자리수.
SIGNIFICANT_DIGITS = 12
_NUMBER = re.compile(r"^-?\d+(\.\d+)?([eE][-+]?\d+)?$")
_ROUNDING = Context(prec=SIGNIFICANT_DIGITS)


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def computed(output: EngineOutput) -> dict[str, Any]:
    """계산 결과만 남긴다. 실행 시각과 이름표(``versions``: 규칙·엔진·워커 버전 문자열)는 뺀다.

    이름표를 빼는 이유: 골든이 지키는 것은 "이 명세로 계산하면 이 숫자"이고, 워커 버전을 올릴 때마다
    모든 골든이 바뀌면 정말 숫자가 바뀐 경우를 가려낼 수 없다.
    """
    payload: dict[str, Any] = json.loads(output.to_json())
    payload["run"].pop("startedAt", None)
    payload["run"].pop("finishedAt", None)
    payload.pop("versions", None)
    return payload


def rounded(value: Any) -> Any:
    """수(숫자 문자열 포함)를 유효숫자 12자리로 맞춘다.

    식별자·날짜 같은 나머지 문자열은 그대로 둔다.
    """
    if isinstance(value, dict):
        return {key: rounded(item) for key, item in value.items()}
    if isinstance(value, list):
        return [rounded(item) for item in value]
    if isinstance(value, float):
        return _significant(Decimal(repr(value)))
    if isinstance(value, str) and _NUMBER.match(value):
        return _significant(Decimal(value))
    return value


def _significant(number: Decimal) -> str:
    """유효숫자로 반올림한 뒤 뒷자리 0을 떼어 같은 값이 언제나 같은 표기가 되게 한다."""
    return format(_ROUNDING.create_decimal(number).normalize(), "f")


def digest(payload: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_json(rounded(payload)).encode("utf-8")).hexdigest()


def exact_digest(payload: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()


def input_digest(body: dict[str, Any]) -> str:
    """기준 입력의 지문. 결과가 어긋났을 때 입력이 바뀐 것인지 엔진이 바뀐 것인지 가른다."""
    return hashlib.sha256(canonical_json(body).encode("utf-8")).hexdigest()
