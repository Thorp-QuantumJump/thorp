"""ETF 일봉 수집 진입점 (spec 001 T007, FR-004·FR-005·FR-009·FR-014·FR-017).

``raw/`` → ``processed/`` → PostgreSQL 한 방향으로 적재한다(``data/CLAUDE.md``). 이 스크립트가
그 세 단계를 순서대로 밟으며, 각 단계의 규칙은 ``src/data_pipeline/``이 갖고 있고 여기서는
순서와 사람이 읽을 요약만 맡는다.

**지수를 먼저 수집한다.** ETF 일별매매정보는 휴장일에도 행을 돌려주므로
(:mod:`data_pipeline.krx` 문서 2번) 거래일 달력 없이 ETF를 수집하면 휴장일 행을 만든다.
달력의 출처는 지수 자료뿐이다(:mod:`data_pipeline.index`).

**입력 파일의 열 이름은 snake_case다.** ``data_pipeline``의 ``*_from_records`` 계약과 같은
이름이라 변환을 한 겹 더 두지 않아도 되고, 같은 파일을 ``scripts/reconcile.py``에 그대로 넣을
수 있다. 루트 ``CLAUDE.md``의 camelCase 규칙은 API JSON을 대상으로 한 것이다.

**기업행위 목록이 담은 구간을 말할 수 없으면 그만큼을 자료 부족 구간으로 남긴다**
(:func:`resolve_actions_known_from`). 파일을 주지 않으면 전 구간이 그렇게 되고, 그래서
"기업행위가 없었다"와 "목록을 넣지 않았다"가 결과에서 구분된다 (FR-006).

**``--commit`` 없이는 DB를 건드리지 않는다.** 발행된 자료 버전은 트리거가 UPDATE·DELETE를
막아 되돌릴 수 없어서(FR-017), 적재를 기본 동작으로 두면 잘못 부른 한 번을 수습할 방법이 없다.

사용 예::

    python scripts/ingest_krx.py --map-version-id 1 \
        --instruments 069500.XKRX 091160 --start 2026-09-01 --until 2026-09-10 \
        --listing listing.json                       # 수집·정제만 하고 요약을 낸다
    python scripts/ingest_krx.py ... --commit        # 자료 버전 발행 뒤 시계열 적재

인증키는 ``.env``의 ``KRX_API_KEY``에서만 읽는다(FR-012). 키 값을 인자·로그로 받지 않는다.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Sequence
from datetime import UTC, date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Any

import pandas as pd

# `scripts/`를 직접 실행하면 `src/`가 sys.path에 없다. `data_pipeline`은 설치형 패키지가 아니고
# pytest의 `pythonpath` 설정으로만 잡히므로(`pyproject.toml`), 진입점이 직접 경로를 붙인다.
_DATA_ROOT = Path(__file__).resolve().parents[1]
if str(_DATA_ROOT / "src") not in sys.path:
    sys.path.insert(0, str(_DATA_ROOT / "src"))

from data_pipeline import gaps, index, krx, repository  # noqa: E402
from data_pipeline.corporate_actions import (  # noqa: E402
    ACTIONS_KNOWN_FROM_NEVER,
    CorporateAction,
    CorporateActionError,
    corporate_actions_from_records,
)
from data_pipeline.dataset_version import (  # noqa: E402
    SOURCE_KRX_OPEN_API,
    DatasetVersion,
    DatasetVersionError,
    build_dataset_id,
)
from data_pipeline.instrument_profile import (  # noqa: E402
    InstrumentProfile,
    InstrumentProfileError,
    profiles_from_records,
)

_RAW_ROOT = _DATA_ROOT / "raw"
_PROCESSED_ROOT = _DATA_ROOT / "processed"

#: 정규장 종료 시각과 그 시간대. ``dataset_version.data_deadline``이 timestamptz라 시각이
#: 필요하고, :func:`build_dataset_id` 는 시간대를 바꾸지 않고 그 시각의 날짜를 쓰므로
#: 마감일이 가리키는 거래일이 그대로 유지된다.
_KST: timezone = timezone(timedelta(hours=9))
_MARKET_CLOSE: time = time(15, 30)

#: ``processed/`` 파티션의 열 순서. :data:`krx.OHLCV_COLUMNS` 로 시작해 조정·정지 열을 뒤에
#: 붙인다. 표준 컬럼을 앞에 두는 이유는 추후 Spark가 같은 파티션을 읽을 때 컬럼 표준
#: (``data/CLAUDE.md``)이 먼저 오는 편이 읽는 쪽의 가정과 맞기 때문이다. ``instrument_id``는
#: 파티션 경로가 이미 담고 있어 열로 중복해 쓰지 않는다.
_PROCESSED_COLUMNS: tuple[str, ...] = (
    *krx.OHLCV_COLUMNS,
    "adjusted_close",
    "adjustment_factor",
    "trading_suspended",
    "price_carried_forward",
)

#: 적재하는 시계열 표. 자료 버전 다음에 오며 이 순서를 뒤집을 수 없다(:mod:`repository` 문서).
_TIMESERIES: tuple[str, ...] = (
    "daily_index",
    "daily_price",
    "corporate_action",
    "data_gap",
    "suspension_period",
)


class IngestError(RuntimeError):
    """수집 진입점이 더 진행할 수 없는 상태. 값이 아니라 실행 조건 문제다."""


# ---------------------------------------------------------------------------
# 인자
# ---------------------------------------------------------------------------


def _parse_day(text: str) -> date:
    try:
        return date.fromisoformat(text)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"YYYY-MM-DD 형식이어야 한다: {text!r}") from error


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "KRX OPEN API ETF 일봉을 수집해 raw/ → processed/ → PostgreSQL로 적재한다. "
            "거래일 달력은 지수 자료(KOSPI)에서 만든다. 원천이 휴장일에도 행을 주기 때문이다."
        )
    )
    parser.add_argument(
        "--map-version-id",
        required=True,
        type=int,
        help=(
            "이 자료가 어느 섹터-ETF 대응표 버전을 대상으로 수집됐는지 "
            "(dataset_version.map_version_id, 저장 스키마에서 NOT NULL)"
        ),
    )
    parser.add_argument(
        "--instruments",
        required=True,
        nargs="+",
        metavar="ID",
        help="종목 식별자({6글자}.XKRX) 또는 6글자 단축코드",
    )
    parser.add_argument("--start", required=True, type=_parse_day, help="수집 시작일 YYYY-MM-DD")
    parser.add_argument("--until", required=True, type=_parse_day, help="수집 종료일 YYYY-MM-DD")
    parser.add_argument(
        "--listing",
        required=True,
        type=Path,
        help=(
            "상장 구간 JSON 파일. "
            '[{"instrument_id": "069500.XKRX", "listed_on": "2002-10-14", "delisted_on": null}] '
            "모양이다. 열 이름은 snake_case이며 scripts/reconcile.py의 --listing과 같은 파일을 "
            "그대로 쓸 수 있다. 원천의 종목기본정보 서비스에 활용 승인이 없어 상장일을 받을 수 "
            "없으므로 대응표가 소유한 값을 밖에서 받는다 (FR-005)"
        ),
    )
    parser.add_argument(
        "--corporate-actions",
        type=Path,
        default=None,
        help=(
            "기업행위 JSON 파일(선택). "
            '[{"instrument_id": "069500.XKRX", "ex_date": "2026-04-29", '
            '"event_type": "DISTRIBUTION", "amount": "120", "pay_date": "2026-05-08"}] '
            "모양이며 열 이름은 snake_case다. scripts/reconcile.py의 --corporate-actions와 "
            "같은 파일을 그대로 쓴다. 주지 않으면 기업행위를 반영할 수 없으므로 각 종목의 "
            "전 구간을 MISSING_CORPORATE_ACTION 자료 부족 구간으로 기록한다 (FR-006)"
        ),
    )
    parser.add_argument(
        "--actions-known-from",
        type=_parse_day,
        default=None,
        help=(
            "기업행위 목록이 빠짐없다고 말할 수 있는 가장 이른 날 YYYY-MM-DD (선택). "
            "그날보다 앞 구간은 빠진 기업행위가 있을 수 있으므로 MISSING_CORPORATE_ACTION "
            "자료 부족 구간으로 기록한다 (FR-006). 기업행위는 원천 API로 받을 수 없어 공시를 "
            "옮긴 파일로 들어오고, 그 파일이 어느 날부터를 담는지는 파일이 알려 주는 값이지 "
            "코드가 알아낼 수 있는 값이 아니다. 주지 않으면 담은 구간을 말할 수 없는 것이므로 "
            "전 구간을 자료 부족 구간으로 기록한다. 전 구간을 담았다면 수집 시작일을 적는다"
        ),
    )
    parser.add_argument(
        "--instrument-profiles",
        type=Path,
        default=None,
        help=(
            "대표 상품 정보 JSON 파일(선택). "
            '[{"instrument_id": "069500.XKRX", "index_methodology_summary": "...", '
            '"holdings_overlap": {"as_of_current_composition": true, '
            '"top_holdings": [{"name": "삼성전자", "weight": "0.2431"}]}}] 모양이며 '
            "열 이름은 snake_case다. "
            "지수 방법론 요약과 구성종목은 원천 API가 주지 않아(2026-09-13 확인) 상장일·기업행위와 "
            "같이 파일로 받는다. 일평균 거래대금은 이 파일이 아니라 방금 만든 daily_price의 "
            "거래대금에서 계산한다 (FR-015)"
        ),
    )
    parser.add_argument(
        "--from-raw",
        action="store_true",
        help=(
            "원천을 부르지 않고 raw/의 보관본을 읽는다. 같은 구간을 다시 적재할 때 이미 받아 "
            "둔 자료를 다시 내려받지 않으려는 것이며, 보관을 둔 목적이 재수집 없는 재정제다 "
            "(research.md 결정 8). 보관본이 없는 날이 있으면 휴장일로 보지 않고 멈춘다. "
            "인증키를 읽지 않으므로 --simulate-quota-exhausted와 함께 쓸 수 없다"
        ),
    )
    parser.add_argument(
        "--simulate-quota-exhausted",
        action="store_true",
        help=(
            "이용 한도 도달을 재현한다 (FR-014, quickstart 시나리오 7). 실제 한도를 쓰지 않고 "
            "지수 수집을 끝낸 직후 ETF 수집이 끊기도록 허용 호출 수를 정한다"
        ),
    )
    parser.add_argument(
        "--commit",
        action="store_true",
        help=(
            "PostgreSQL에 적재한다. 없으면 수집·정제까지만 하고 요약만 낸다. "
            "발행된 자료 버전은 UPDATE·DELETE가 막혀 되돌릴 수 없어 기본 동작이 아니다 (FR-017)"
        ),
    )
    parser.add_argument(
        "--env-file",
        type=Path,
        default=None,
        help=".env 경로(선택). 지정하면 그 파일의 값이 이미 내보내진 환경 변수를 이긴다",
    )
    return parser


# ---------------------------------------------------------------------------
# 입력 파일
# ---------------------------------------------------------------------------


def _records_from_json(path: Path, what: str) -> list[dict[str, Any]]:
    """JSON 배열 파일을 읽는다. 모양이 다르면 지어내지 않고 알린다."""
    if not path.exists():
        raise IngestError(f"{what} 파일이 없다: {path}")
    try:
        body = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as error:
        raise IngestError(f"{what} 파일이 JSON이 아니다: {path} ({error})") from error
    if not isinstance(body, list):
        raise IngestError(f"{what} 파일은 배열이어야 한다: {path} ({type(body).__name__})")
    for position, record in enumerate(body):
        if not isinstance(record, dict):
            raise IngestError(f"{what} 파일 {position}번째 항목이 객체가 아니다: {path}")
    return list(body)


def _required_day(record: dict[str, Any], field: str, where: str) -> date:
    value = record.get(field)
    if not isinstance(value, str):
        raise IngestError(f"{where}의 {field}이(가) YYYY-MM-DD 문자열이 아니다: {value!r}")
    try:
        return date.fromisoformat(value)
    except ValueError as error:
        raise IngestError(f"{where}의 {field}이(가) 날짜가 아니다: {value!r}") from error


def _optional_day(record: dict[str, Any], field: str, where: str) -> date | None:
    return None if record.get(field) is None else _required_day(record, field, where)


def listing_windows(path: Path) -> dict[str, krx.ListingWindow]:
    """상장 구간 JSON을 :class:`krx.ListingWindow` 로 바꾼다.

    **열 이름은 snake_case다.** ``data_pipeline``의 ``*_from_records`` 계약과 같은 이름이라
    변환을 한 겹 더 두지 않아도 되고, 같은 파일을 ``scripts/reconcile.py``의 ``--listing``에
    그대로 넣을 수 있다. 루트 ``CLAUDE.md``의 camelCase 규칙은 API JSON을 대상으로 한 것이라
    파이프라인 입력 파일에는 해당하지 않는다.

    상장일이 없으면 그 종목의 수집 자체를 할 수 없으므로 필수로 요구한다 — 없는 값을 0이나
    수집 시작일로 채우면 상장 전 구간이 있는 것처럼 보여 SC-003의 판정이 무의미해진다.
    """
    windows: list[krx.ListingWindow] = []
    for position, record in enumerate(_records_from_json(path, "상장 구간")):
        where = f"상장 구간 {position}번째 항목"
        instrument_id = record.get("instrument_id")
        if not isinstance(instrument_id, str):
            raise IngestError(f"{where}의 instrument_id가 문자열이 아니다: {instrument_id!r}")
        windows.append(
            krx.ListingWindow(
                instrument_id=instrument_id.strip(),
                listed_on=_required_day(record, "listed_on", where),
                delisted_on=_optional_day(record, "delisted_on", where),
            )
        )
    return krx.windows_by_instrument(windows)


def corporate_actions(path: Path | None) -> list[CorporateAction]:
    """기업행위 JSON을 :class:`CorporateAction` 으로 바꾼다.

    **열 이름은 snake_case이며** ``scripts/reconcile.py``의 ``--corporate-actions``와 같은
    파일이다. 값 규칙 검사는 모두 :func:`corporate_actions_from_records` 가 맡고 여기서는
    날짜 문자열만 풀어 준다.

    파일이 없으면 빈 목록이다. **이때 계수가 전부 1이 되는 것을 기업행위가 없는 것으로 보아서는
    안 된다.** 목록이 담은 구간을 :func:`resolve_actions_known_from` 이 "말할 수 없음"으로 정해
    전 구간이 자료 부족 구간으로 남는다.
    """
    if path is None:
        return []
    records: list[dict[str, Any]] = []
    for position, record in enumerate(_records_from_json(path, "기업행위")):
        where = f"기업행위 {position}번째 항목"
        records.append(
            {
                "instrument_id": str(record.get("instrument_id")),
                "ex_date": _required_day(record, "ex_date", where),
                "event_type": str(record.get("event_type")),
                "amount": record.get("amount"),
                "pay_date": _optional_day(record, "pay_date", where),
            }
        )
    return corporate_actions_from_records(records)


def resolve_actions_known_from(path: Path | None, declared: date | None) -> date:
    """조정 계열에 넘길 "기업행위 목록이 빠짐없는 가장 이른 날"을 정한다 (FR-006).

    두 경우에 :data:`ACTIONS_KNOWN_FROM_NEVER` 를 돌려주고, 그러면 그 종목의 전 구간이
    ``MISSING_CORPORATE_ACTION`` 자료 부족 구간으로 남는다.

    * **기업행위 파일을 주지 않았다.** 계수가 전부 1이 되어 조정 종가가 원시 종가와 같아지는데,
      기록이 없으면 결과만 보아서는 기업행위가 없었던 것인지 목록을 넣지 않은 것인지 가릴 수
      없다. 적재 지시를 붙이면 불변 트리거가 걸려 되돌릴 수도 없다.
    * **파일은 주었으나 ``--actions-known-from``이 없다.** 그 파일이 어느 날부터를 담는지는
      파일이 알려 주는 값이라, 없으면 담은 구간을 말할 수 없다. 전 구간을 담았다고 보는 것은
      코드가 대신 하는 주장이다.

    인자를 필수로 만들지 않는 이유는 시험 수집을 막지 않기 위함이다. 기록이 남으므로 없는 것을
    있는 것처럼 내보내지는 않는다.

    **파일 없이 ``--actions-known-from``만 주면 거부한다.** 담을 목록이 없는데 그 목록이 어느
    날부터 빠짐없다고 선언하는 것이라 뜻이 성립하지 않는다. 조용히 무시하면 사용자가 준 값이
    아무 일도 하지 않은 채 결과만 달라지므로 원칙 VI("사용자 입력을 몰래 바꾸지 않는다")에
    어긋난다. 무시하지 않고 무시할 수 없음을 알린다.
    """
    if declared is not None and path is None:
        raise SystemExit(
            "--actions-known-from은 --corporate-actions와 함께 주어야 한다. "
            "기업행위 목록 없이 그 목록이 담은 구간을 선언할 수 없다. "
            "목록을 넣지 않을 생각이면 --actions-known-from도 빼라 "
            "(그 종목의 전 구간이 MISSING_CORPORATE_ACTION 자료 부족 구간으로 남는다)."
        )
    if path is None or declared is None:
        return ACTIONS_KNOWN_FROM_NEVER
    return declared


def instrument_profiles(
    path: Path | None,
    price_rows: Sequence[dict[str, Any]],
    instrument_ids: Sequence[str],
    deadline: date,
) -> list[InstrumentProfile]:
    """대표 상품 정보 JSON과 방금 만든 가격 행으로 대표 상품 정보를 만든다 (FR-015).

    **열 이름은 snake_case다.** ``--listing``·``--corporate-actions``와 같은 규약이며, 중첩된
    ``holdings_overlap`` 안의 이름도 마찬가지다. 루트 ``CLAUDE.md``의 camelCase 규칙은 API JSON을
    대상으로 한 것이라 파이프라인 입력 파일에는 해당하지 않는다.

    파일이 없으면 빈 목록이고, 그러면 ``instrument_profile``에 아무것도 넣지 않는다. 조회 API는
    그 상태를 빈 값이 아니라 ``INSTRUMENT_PROFILE_NOT_READY``(409)로 구분한다
    (contracts/sector-api.md) — "아직 만들지 않았다"와 "만들었는데 비었다"는 다른 사실이다.

    **여기서 만드는 이유**: 일평균 거래대금의 근거가 ``daily_price.value``인데, 적재 계층은
    DB에서 시계열을 읽어 되돌리는 경로를 두지 않는다(:mod:`data_pipeline.repository` 문서).
    그래서 별도 진입점을 만들면 그 값을 얻을 방법이 없고, 방금 가격 행을 만든 이 자리가
    유일하게 근거를 들고 있는 지점이다.
    """
    if path is None:
        return []
    known = set(instrument_ids)
    records = _records_from_json(path, "대표 상품 정보")
    unknown = sorted(
        {
            str(record.get("instrument_id"))
            for record in records
            if str(record.get("instrument_id")) not in known
        }
    )
    if unknown:
        raise IngestError(
            f"이번 수집에 없는 종목의 대표 상품 정보다: {unknown}. 그 종목의 거래대금이 없어 "
            "일평균을 낼 수 없다"
        )
    return profiles_from_records(records, price_rows, data_deadline=deadline)


def resolve_instrument_ids(values: Sequence[str]) -> list[str]:
    """6글자 단축코드와 ``{6글자}.XKRX``를 모두 받아 식별자로 맞춘다.

    같은 종목이 두 번 오면 거부한다. 두 번 담으면 ``daily_price``의 PK가 겹쳐 적재가 깨지고,
    그때는 절반만 들어간 표가 남는다.
    """
    resolved: list[str] = []
    for value in values:
        text = value.strip()
        instrument_id = (
            text if krx.INSTRUMENT_ID_PATTERN.match(text) else krx.instrument_id_for(text)
        )
        if instrument_id in resolved:
            raise IngestError(f"같은 종목이 두 번 들어 있다: {instrument_id}")
        resolved.append(instrument_id)
    return resolved


# ---------------------------------------------------------------------------
# 수집
# ---------------------------------------------------------------------------


def build_source(
    *, env_file: Path | None, allowed_calls: int | None, from_raw: bool = False
) -> krx.DailyRowSource:
    """원천 호출 경로를 만든다.

    ``RawArchivingSource``가 응답 원본을 ``raw/``에 남기고(research.md 결정 8), 한도 재현
    덮개는 **그 바깥**에 둔다. 한도에 걸린 호출은 응답이 오지 않은 호출이므로 ``raw/``에도
    남지 않아야 한다.

    ``from_raw``면 원천 대신 그 보관본을 읽는다. 같은 구간을 다시 적재할 때 이미 받아 둔 것을
    다시 내려받지 않기 위한 것이며, 보관을 둔 목적이 바로 그 "재수집 없는 재정제"다. 인증키를
    읽지 않으므로 키 없이도 돈다.

    **한도 재현과는 함께 쓸 수 없다.** 부르지 않는 호출에 한도를 모의하는 것은 뜻이 성립하지
    않는다. 조용히 무시하면 사용자가 준 인자가 아무 일도 하지 않은 채 결과만 달라진다.
    """
    if from_raw:
        if allowed_calls is not None:
            raise IngestError(
                "--from-raw는 원천을 부르지 않으므로 --simulate-quota-exhausted와 함께 쓸 수 "
                "없다. 모의할 호출 자체가 없다"
            )
        return krx.ArchiveReadingSource(root=_RAW_ROOT)
    client = krx.KrxOpenApiClient.from_env(env_file)
    source: krx.DailyRowSource = krx.RawArchivingSource(inner=client, root=_RAW_ROOT)
    if allowed_calls is not None:
        source = krx.QuotaLimitedSource(inner=source, allowed_calls=allowed_calls)
    return source


def data_deadline(etf: krx.KrxCollection, indices: index.IndexCollection) -> date:
    """데이터 마감일. **요청한 날짜가 아니라 실제로 수집한 마지막 거래일이다.**

    ETF 쪽 ``last_collected``와 지수 쪽 ``last_trading_day()`` 가운데 이른 쪽을 쓴다.
    한쪽이 먼저 끊기면 그 뒤 구간은
    한쪽 자료만 있는 상태라, 늦은 쪽을 마감일로 쓰면 없는 자료를 있다고 말하는 셈이다
    (quickstart 시나리오 7).
    """
    # 지수 쪽은 호출 목록의 끝이 휴장일일 수 있으므로 last_trading_day를 쓴다.
    collected = [day for day in (etf.last_collected, indices.last_trading_day()) if day is not None]
    if len(collected) < 2:
        raise IngestError(
            "지수와 ETF 가운데 한쪽도 수집하지 못해 데이터 마감일을 정할 수 없다. "
            "마감일은 두 자료가 모두 있는 마지막 거래일이라 한쪽만으로는 말할 수 없다"
        )
    return min(collected)


def failure_reason(etf: krx.KrxCollection, indices: index.IndexCollection) -> str | None:
    """중단 사유. 두 수집 가운데 어느 쪽이 끊겼는지 함께 적는다 (FR-014).

    한쪽만 적으면 뒤에 읽는 사람이 "지수는 다 모였는데 ETF만 끊긴 것"과 "둘 다 끊긴 것"을
    구분할 수 없고, 둘의 구분에 따라 다시 수집할 구간이 달라진다.
    """
    reasons: list[str] = []
    if not indices.complete:
        reasons.append(f"지수 수집 중단: {indices.failure_reason}")
    if not etf.complete:
        reasons.append(f"ETF 수집 중단: {etf.failure_reason}")
    return " / ".join(reasons) if reasons else None


# ---------------------------------------------------------------------------
# processed/ 저장
# ---------------------------------------------------------------------------


def processed_frame(price_rows: Sequence[dict[str, Any]]) -> pd.DataFrame:
    """일별 가격 행을 ``processed/`` 파티션에 쓸 프레임으로 만든다.

    ``to_dict``/``from_records`` 경로만 쓴다. ``DataFrame.iterrows()``는 수치 행을 float64로
    올려 ``volume``·``value`` 같은 큰 정수를 실수로 만든다(``repository._require_integer``).
    """
    frame = pd.DataFrame.from_records(
        list(price_rows), columns=["instrument_id", *_PROCESSED_COLUMNS]
    )
    return frame.drop(columns=["instrument_id"])


# ---------------------------------------------------------------------------
# 적재
# ---------------------------------------------------------------------------


def _load_all(
    store: repository.MarketDataRepository,
    dataset_id: str,
    tables: dict[str, list[dict[str, Any]]],
) -> dict[str, int]:
    """시계열을 적재한다. 자료 버전이 이미 발행된 뒤에만 부를 수 있다."""
    loaders = {
        "daily_index": store.load_daily_index,
        "daily_price": store.load_daily_price,
        "corporate_action": store.load_corporate_action,
        "data_gap": store.load_data_gap,
        "suspension_period": store.load_suspension_period,
    }
    return {name: loaders[name](dataset_id, tables[name]) for name in _TIMESERIES}


def rehearse(version: DatasetVersion, tables: dict[str, list[dict[str, Any]]]) -> None:
    """PostgreSQL을 건드리기 전에 같은 열 정의의 인메모리 SQLite에 한 번 넣어 본다.

    행 검사는 표 하나 단위로 일어나므로, 네 번째 표에서 걸리면 앞의 세 표는 이미 들어가 있다.
    그 상태는 발행본이라 DELETE도 거부되어 되돌릴 수 없다(:mod:`repository` 문서). 그래서
    전체 묶음을 먼저 통째로 넣어 보고, 통과한 뒤에만 실제 저장소를 건드린다.

    CHECK·트리거는 마이그레이션이 원본이고 여기 대역에는 없다. 이 예행은 파이썬 계층의 행
    검사와 적재 순서까지만 확인한다.
    """
    rehearsal = repository.MarketDataRepository.rehearsal()
    try:
        rehearsal.publish_dataset_version(version)
        _load_all(rehearsal, version.dataset_id, tables)
    finally:
        rehearsal.engine.dispose()


# ---------------------------------------------------------------------------
# 실행
# ---------------------------------------------------------------------------


def _collect(
    args: argparse.Namespace, windows: dict[str, krx.ListingWindow], instrument_ids: list[str]
) -> tuple[index.IndexCollection, krx.KrxCollection, list[date]]:
    """지수 → 달력 → ETF 순서로 수집한다. 이 순서를 뒤집을 수 없다."""
    candidates = index.calendar_day_range(args.start, args.until)
    # 한도 재현은 지수 수집을 끝낸 직후 ETF 수집이 끊기도록 허용 호출 수를 정한다. 그러면
    # 마감일이 "요청한 마지막 날짜"가 아니라 "실제로 수집한 마지막 거래일"이 되는 것을
    # 눈으로 확인할 수 있다 (quickstart 시나리오 7).
    allowed_calls = len(candidates) + 1 if args.simulate_quota_exhausted else None
    source = build_source(
        env_file=args.env_file, allowed_calls=allowed_calls, from_raw=args.from_raw
    )

    indices = index.collect_index_quotes(source, candidates)
    calendar = indices.trading_calendar()
    if not calendar:
        raise IngestError(
            f"{args.start} ~ {args.until} 구간에 거래일이 없다. 달력 없이 ETF를 수집하면 "
            "원천이 휴장일에도 주는 행을 그대로 담게 되므로 여기서 멈춘다"
        )
    etf = krx.collect_daily_quotes(
        source,
        calendar,
        instrument_ids=instrument_ids,
        windows=[windows[instrument_id] for instrument_id in instrument_ids],
    )
    return indices, etf, calendar


def _build_tables(
    etf: krx.KrxCollection,
    indices: index.IndexCollection,
    calendar: Sequence[date],
    windows: dict[str, krx.ListingWindow],
    instrument_ids: Sequence[str],
    actions: Sequence[CorporateAction],
    deadline: date,
    actions_known_from: date | None,
) -> tuple[dict[str, list[dict[str, Any]]], dict[str, gaps.InstrumentSeries]]:
    """종목별 시계열을 만들고 FR-009 검사 결과를 자료 부족 구간에 더한다."""
    series: dict[str, gaps.InstrumentSeries] = {}
    price_rows: list[dict[str, Any]] = []
    gap_rows: list[dict[str, Any]] = []
    suspension_rows: list[dict[str, Any]] = []
    for instrument_id in instrument_ids:
        built = gaps.build_instrument_series(
            etf.for_instrument(instrument_id),
            calendar,
            windows[instrument_id],
            actions,
            actions_known_from=actions_known_from,
        )
        series[instrument_id] = built
        price_rows.extend(dict(row) for row in built.price_rows)
        gap_rows.extend(dict(row) for row in built.gap_rows)
        suspension_rows.extend(dict(row) for row in built.suspension_rows)

    coverages = index.verify_index_coverage(
        [windows[instrument_id] for instrument_id in instrument_ids],
        calendar,
        data_deadline=deadline,
    )
    gap_rows.extend(index.index_history_gap_rows(coverages))

    tables = {
        "daily_index": indices.to_rows(),
        "daily_price": price_rows,
        "corporate_action": [
            action.to_row() for action in actions if action.instrument_id in set(instrument_ids)
        ],
        "data_gap": gap_rows,
        "suspension_period": suspension_rows,
    }
    return tables, series


def _report_corporate_actions(
    args: argparse.Namespace, known_from: date | None, gap_rows: Sequence[dict[str, Any]]
) -> None:
    """기업행위 목록이 어느 구간을 담았는지와 그로 생긴 자료 부족 구간을 낸다 (FR-006).

    조정 종가가 원시 종가와 같은 결과는 "기업행위가 없었다"와 "목록을 넣지 않았다"에서 똑같이
    나온다. 요약에서 둘을 가르지 않으면 읽는 사람이 결과만 보고 구분할 수 없다.
    """
    affected = sorted(
        {
            str(row["instrument_id"])
            for row in gap_rows
            if row["reason"] == gaps.REASON_MISSING_CORPORATE_ACTION
        }
    )
    if args.corporate_actions is None:
        print("기업행위 목록: 없음 (--corporate-actions를 주지 않았다)")
    elif known_from == ACTIONS_KNOWN_FROM_NEVER:
        print(f"기업행위 목록: {args.corporate_actions} (담은 구간 미상)")
        print("  --actions-known-from이 없어 목록이 어느 날부터를 담았는지 말할 수 없다")
    else:
        print(f"기업행위 목록: {args.corporate_actions} ({known_from}부터 빠짐없다)")
    if affected:
        scope = "전 구간" if known_from == ACTIONS_KNOWN_FROM_NEVER else f"{known_from} 이전 구간"
        print(
            f"  {gaps.REASON_MISSING_CORPORATE_ACTION} 자료 부족 구간: "
            f"{len(affected)}종목의 {scope} ({', '.join(affected)})"
        )


def _report(
    *,
    dataset_id: str,
    deadline: date,
    args: argparse.Namespace,
    indices: index.IndexCollection,
    etf: krx.KrxCollection,
    calendar: Sequence[date],
    tables: dict[str, list[dict[str, Any]]],
    reason: str | None,
    known_from: date | None,
    written: list[Path],
    loaded: dict[str, int] | None,
    profiles: Sequence[InstrumentProfile],
) -> None:
    print(f"자료 버전: {dataset_id}")
    print(f"데이터 마감일: {deadline} (요청 종료일 {args.until})")
    # 줄글에 em dash(U+2014)를 쓰지 않는다. 윈도 콘솔의 기본 부호화(cp949)가 그 글자를
    # 담지 못해 출력 자체가 UnicodeEncodeError로 죽는다. 주석·docstring은 출력되지 않아 무관하다.
    print(f"거래일 달력: {len(calendar)}일 ({calendar[0]} ~ {calendar[-1]}), 출처는 지수 자료다")
    _report_corporate_actions(args, known_from, tables["data_gap"])
    print(f"수집 완료 여부: {'완료' if reason is None else '부분 갱신'}")
    if reason is not None:
        print(f"중단 사유: {reason}")
        print(f"지수 미수집 날짜: {indices.uncalled_count}일")
        print(f"ETF 미수집 거래일: {len(etf.missed_dates)}일")
    for name in _TIMESERIES:
        print(f"  {name}: {len(tables[name])}행")
    if profiles:
        print(f"  instrument_profile: {len(profiles)}행 (최근 3개월 일평균 거래대금)")
        for profile in profiles:
            print(f"    {profile.instrument_id}: {profile.avg_daily_value_3m}원")
    else:
        print("  instrument_profile: 없음. --instrument-profiles로 지수 방법론·구성종목을 준다")
    if etf.unsupported_codes:
        # 저장 스키마가 담을 수 없는 종목코드다(영숫자 대문자 6글자가 아닌 표기). 모아 두기만
        # 하고 알리지 않으면 버린 것과 같으므로 요약에 반드시 낸다
        # (:func:`krx.collect_daily_quotes` 문서 참고).
        codes = sorted(etf.unsupported_codes)
        shown = ", ".join(codes[:5]) + (" …" if len(codes) > 5 else "")
        print(f"담을 수 없는 종목코드: {len(codes)}개 ({shown})")
        print("  영숫자 대문자 6글자가 아니라 저장 스키마의 instrument_id 형식에 맞지 않는다")
        print("  자리 수나 문자 집합을 보고 형식을 다시 넓힐지 정한다 (spec 001 T031)")
    print(f"processed/ 파티션: {len(written)}개")
    if loaded is None:
        print("적재하지 않았다. PostgreSQL에 넣으려면 --commit을 붙인다 (발행본은 되돌릴 수 없다)")
    else:
        print(f"적재 완료: {', '.join(f'{name} {count}행' for name, count in loaded.items())}")


def run(args: argparse.Namespace) -> int:
    windows = listing_windows(args.listing)
    instrument_ids = resolve_instrument_ids(args.instruments)
    missing = [value for value in instrument_ids if value not in windows]
    if missing:
        raise IngestError(
            f"상장 구간 파일에 없는 종목이다: {missing}. 상장일 없이 수집하면 상장 전 행을 "
            "가려낼 수 없다 (FR-005, SC-003)"
        )
    actions = corporate_actions(args.corporate_actions)
    known_from = resolve_actions_known_from(args.corporate_actions, args.actions_known_from)

    indices, etf, calendar = _collect(args, windows, instrument_ids)
    deadline = data_deadline(etf, indices)
    dataset_id = build_dataset_id(deadline)
    reason = failure_reason(etf, indices)
    tables, series = _build_tables(
        etf,
        indices,
        calendar,
        windows,
        instrument_ids,
        actions,
        deadline,
        known_from,
    )
    profiles = instrument_profiles(
        args.instrument_profiles, tables["daily_price"], instrument_ids, deadline
    )

    written: list[Path] = []
    for instrument_id, built in series.items():
        written.extend(
            krx.write_processed_parquet(
                processed_frame(built.price_rows),
                _PROCESSED_ROOT,
                dataset=dataset_id,
                instrument_id=instrument_id,
            )
        )

    loaded: dict[str, int] | None = None
    if args.commit:
        now = datetime.now(UTC)
        version = DatasetVersion(
            dataset_id=dataset_id,
            data_deadline=datetime.combine(deadline, _MARKET_CLOSE, tzinfo=_KST),
            collected_at=now,
            last_success_at=now,
            complete=reason is None,
            is_sample=False,
            source=SOURCE_KRX_OPEN_API,
            map_version_id=args.map_version_id,
            failure_reason=reason,
        )
        rehearse(version, tables)
        store = repository.MarketDataRepository.from_env(args.env_file)
        # 자료 버전 → 시계열. 이 순서를 뒤집으면 저장소가 거부한다 (FR-017).
        store.publish_dataset_version(version)
        loaded = _load_all(store, dataset_id, tables)
        if profiles:
            # 시계열 다음이다. 이 표는 대표 ETF와 자료 버전을 bigint 외래 키로 가리키므로
            # 예행(SQLite)에는 대응표 행이 없어 미리 넣어 볼 수 없다. 값 검사는
            # instrument_profiles()가 이미 끝냈고 남은 것은 식별자 조회뿐이며, 조회가
            # 실패하면 한 건도 넣지 않고 무엇이 없는지 말하는 오류가 난다.
            loaded["instrument_profile"] = store.load_instrument_profiles(dataset_id, profiles)

    _report(
        dataset_id=dataset_id,
        deadline=deadline,
        args=args,
        indices=indices,
        etf=etf,
        calendar=calendar,
        tables=tables,
        reason=reason,
        known_from=known_from,
        written=written,
        loaded=loaded,
        profiles=profiles,
    )
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return run(args)
    except (
        IngestError,
        krx.KrxError,
        gaps.GapError,
        CorporateActionError,
        InstrumentProfileError,
        DatasetVersionError,
        repository.RepositoryError,
    ) as error:
        # 예외 형만 보고 뜻을 알기 어려우므로 사람이 읽을 한 줄로 바꿔 낸다. 인증키 값은
        # 어느 예외 메시지에도 들어가지 않는다 (FR-012).
        print(f"수집을 중단했다: {type(error).__name__}: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
