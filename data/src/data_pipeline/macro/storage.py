"""raw 에서 processed 로 가는 경로 규칙 (spec 008, data/CLAUDE.md 구조 규칙).

적재는 ``raw/ → processed/ → PostgreSQL`` 한 방향이다. 원본을 먼저 남기고 그것으로 Parquet 을
만들고 마지막에 DB 에 넣는다. **거꾸로 가지 않는다** — DB 에서 읽어 Parquet 을 고치면 원본과
어긋난 파생물이 생기고, 어느 쪽이 맞는지 판정할 근거가 사라진다.

**파티션은 지표/연도다.** ``data/CLAUDE.md`` 의 자산/연도 규칙을 이 도메인에 옮긴 것이며, Spark 를
도입하면 그대로 읽는 전제라 임의로 바꾸지 않는다. 지표를 앞에 두는 이유는 읽는 쪽이 늘 지표
하나의 시계열을 통째로 요구해서다(010 의 척도 계산이 그렇다).

**원본 파일 이름에 수신 시각을 넣는다.** 같은 대상월을 다시 받아도 옛 파일을 덮지 않는다 —
개정 이력은 원본이 남아 있어야 되짚을 수 있고(FR-009), 덮어쓴 원본은 되살릴 수 없다.
"""

from __future__ import annotations

import hashlib
import re
from datetime import datetime
from pathlib import Path
from typing import Final

#: `data/` 아래의 두 뿌리. 데이터 파일은 커밋하지 않는다(.gitignore).
RAW_ROOT: Final[str] = "raw/macro"
PROCESSED_ROOT: Final[str] = "processed/macro"

_SAFE_ID: Final[re.Pattern[str]] = re.compile(r"^[a-z0-9_]+$")


class StorageError(ValueError):
    """경로를 만들 수 없는 식별자나 시각이다."""


def _require_safe(value: str, field: str) -> str:
    if not _SAFE_ID.match(value):
        raise StorageError(
            f"{field}는 소문자·숫자·밑줄만 쓴다: {value!r}."
            " 경로에 들어가므로 구분자나 상위 경로 기호가 섞이면 안 된다"
        )
    return value


def raw_path(
    base_dir: Path, source_id: str, indicator_id: str, received_at: datetime, suffix: str
) -> Path:
    """원천 응답 원본을 둘 자리.

    ``raw/macro/{원천}/{지표}/{연도}/{수신시각}{확장자}`` 다. 연도는 수신 연도이지 대상월의
    연도가 아니다 — 한 응답이 여러 해를 담을 수 있어서다(관세청은 요청당 1년, 경제협력개발기구는
    한 번에 전 구간).
    """
    _require_safe(source_id, "원천 id")
    _require_safe(indicator_id, "지표 id")
    if not suffix.startswith("."):
        raise StorageError(f"확장자는 점으로 시작한다: {suffix!r}")
    stamp = received_at.strftime("%Y%m%dT%H%M%S%f")
    return (
        base_dir
        / RAW_ROOT
        / source_id
        / indicator_id
        / f"{received_at.year:04d}"
        / f"{stamp}{suffix}"
    )


def processed_path(base_dir: Path, indicator_id: str, year: int) -> Path:
    """정제된 월별 값을 둘 자리.

    ``processed/macro/indicator_id={지표}/year={연도}/part.parquet`` 이다. 여기의 연도는
    **대상월의 연도**다 — 읽는 쪽이 구간으로 자르기 때문이다.
    """
    _require_safe(indicator_id, "지표 id")
    if not 1900 <= year <= 2999:
        raise StorageError(f"연도가 범위 밖이다: {year}")
    return (
        base_dir
        / PROCESSED_ROOT
        / f"indicator_id={indicator_id}"
        / f"year={year:04d}"
        / "part.parquet"
    )


def content_hash(payload: bytes) -> str:
    """원본 내용 해시. 같은 응답을 다시 받은 것과 실제 개정을 가른다 (FR-009).

    해시가 같으면 새 개정이 아니므로 이력에 행을 늘리지 않는다. 그렇게 하지 않으면 정기 재조회를
    돌릴 때마다 바뀐 것이 없는 달도 개정된 것처럼 쌓인다.
    """
    return hashlib.sha256(payload).hexdigest()
