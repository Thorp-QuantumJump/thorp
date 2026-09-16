"""분배 기업행위 입력 파일 만들기 (spec 001 T014, FR-004·FR-006).

``ingest_krx.py --corporate-actions``가 먹는 JSON을 **손으로 적지 않고** 만든다. 새로운 계산은
여기에 없다. 이미 있는 두 함수의 결과를 락일로 맞춰 붙일 뿐이다.

- :func:`data_pipeline.corporate_actions.observe_distributions` 가 일봉에서 **분배락일과 주당
  분배금**을 되살린다. 지급일은 가격에 흔적을 남기지 않아 여기서 얻을 수 없다.
- :func:`data_pipeline.kind_distributions.collect_distribution_histories` 가 KIND 공시에서
  **지급기준일·지급예정일·공시 분배금**을 받아 온다. 분배락일은 공시에 없다.

두 쪽이 서로 없는 값을 갖고 있어서, 붙여야 ``corporate_action`` 한 행이 된다.

## 무엇을 열쇠로 붙이는가

**락일이다.** 분배락일은 지급기준일 **직전 거래일**이다. 기준일에 주주명부에 오르려면 결제가
그날 끝나야 하고 결제는 이틀 걸리므로(T+2), 기준일 하루 전 거래일에 사면 늦는다. 그날부터
권리가 떨어져 기준가가 내려가고, 그 내려간 자리가 일봉에 남는다.

2026-04-30 기준일 여덟 건을 2026-04-29 관측과 대조해 이 대응을 확인했다(2026-09-16). 거래일
달력은 지수 자료가 소유하므로(:mod:`data_pipeline.index`) "직전 거래일"도 달력에서 읽는다.

**금액으로 붙이지 않는다.** 두 금액은 같지 않기 때문이다 (아래).

## 두 금액이 다르다 — 관측값은 공시값을 호가단위로 내림한 값이다

2026-04-30 기준일 여덟 건에서 공시 금액과 관측 금액이 이렇게 어긋났다(2026-09-16 실측).

===========  =========  =========
종목          공시 금액   관측 금액
===========  =========  =========
091160.XKRX        115        115
091170.XKRX         59         55
091180.XKRX        496        495
102970.XKRX        340        340
117700.XKRX         15         15
305720.XKRX         18         15
228790.XKRX         20         20
===========  =========  =========

관측 금액은 거래소 **기준가**에서 되살린 값이고 기준가는 ETF 호가단위인 5원의 배수라, 공시
금액이 5원의 배수가 아니면 그만큼 깎인 값이 관측된다. 최근 2년 관측 46건이 모두 5원의 배수였다.

그래서 **출력에 적는 금액은 공시 금액이다.** 투자자가 실제로 받는 금액이 그쪽이고, 관측값은
거래소가 기준가를 호가단위에 맞추며 생긴 부산물이다. 다만 둘의 차이가 호가단위 안에 있는지를
붙일 때마다 확인하고, 벗어나면 **붙이지 않고 알린다** — 그 경우 락일 대응 자체가 틀렸을
가능성이 더 크다.

## 일봉을 ``raw/``에서 읽는다

원천을 다시 부르지 않는다. ``raw/``는 재수집 없이 다시 정제하려고 남긴 보관본이며
(research.md 결정 8) 분배를 되살리는 일이 바로 그 "다시 정제"다. 이용 한도를 쓰지 않는다.

**거래일 하루라도 보관본이 없으면 진행하지 않는다.** 되살리는 식이 직전 거래일의 종가를 쓰는데
그 하루가 비면 엉뚱한 날의 종가와 견주게 되어, 없던 분배가 만들어지거나 있던 분배가 가려진다.
건너뛰고 진행하면 그 사실이 결과에 드러나지 않으므로 멈춘다.

사용 예::

    python scripts/build_distributions.py --start 2024-09-19 --until 2026-09-15 \
        --out config/corporate-actions-2026-09.json

종료 코드는 셋이다. ``0`` 모두 붙었다 / ``1`` 붙이지 못한 것이 남았다(파일은 쓴다) /
``2`` 실행 조건이 갖춰지지 않아 아무것도 하지 못했다.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Any

# `scripts/`를 직접 실행하면 `src/`가 sys.path에 없다. `ingest_krx.py`와 같은 이유다.
_DATA_ROOT = Path(__file__).resolve().parents[1]
if str(_DATA_ROOT / "src") not in sys.path:
    sys.path.insert(0, str(_DATA_ROOT / "src"))

from data_pipeline import index, krx  # noqa: E402
from data_pipeline.corporate_actions import (  # noqa: E402
    EVENT_DISTRIBUTION,
    ObservedDistribution,
    observe_distributions,
)
from data_pipeline.kind_distributions import (  # noqa: E402
    DistributionHistory,
    DistributionRecord,
    KindHttpSource,
    KindPageSource,
    collect_distribution_histories,
    load_fund_isins,
    load_fund_names,
)

_RAW_ROOT = _DATA_ROOT / "raw"
_DEFAULT_FUND_NAMES = _DATA_ROOT / "config" / "fund-names.json"

#: ETF 호가단위(원). 거래소 기준가가 이 배수라 관측 금액은 공시 금액을 이 단위로 내림한 값이
#: 된다(모듈 설명의 표). 공시 금액과 관측 금액의 차이가 이 값보다 작아야 같은 분배로 본다.
REFERENCE_PRICE_TICK: Decimal = Decimal("5")


class BuildError(RuntimeError):
    """진입점이 더 진행할 수 없는 상태. 값이 아니라 실행 조건 문제다."""


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
            "raw/ 보관본의 일봉에서 분배락일·주당 분배금을 되살리고 KIND 공시에서 지급일을 받아, "
            "ingest_krx.py --corporate-actions가 먹는 JSON으로 합친다."
        )
    )
    parser.add_argument("--start", required=True, type=_parse_day, help="시작일 YYYY-MM-DD")
    parser.add_argument("--until", required=True, type=_parse_day, help="종료일 YYYY-MM-DD")
    parser.add_argument(
        "--out",
        required=True,
        type=Path,
        help="기업행위 JSON을 쓸 경로. ingest_krx.py --corporate-actions에 그대로 넣는다",
    )
    parser.add_argument(
        "--instruments",
        nargs="+",
        metavar="ID",
        default=None,
        help=(
            "종목 식별자({6글자}.XKRX) 또는 6글자 단축코드. 주지 않으면 대응표 파일의 종목 전부다"
        ),
    )
    parser.add_argument(
        "--fund-names",
        type=Path,
        default=_DEFAULT_FUND_NAMES,
        help=(
            "펀드 정식명 대응표 JSON. 종목 식별자와 ISIN의 대응도 이 파일이 갖고 있다. "
            "2019년 이전 공시에는 종목코드 칸이 없어 이름으로만 맞출 수 있다"
        ),
    )
    parser.add_argument(
        "--raw-root",
        type=Path,
        default=_RAW_ROOT,
        help="원천 응답 보관 폴더. 기본값은 data/raw다",
    )
    parser.add_argument(
        "--page-size",
        type=int,
        default=None,
        help="KIND 목록 한 쪽의 행 수(선택). 기본값은 kind_distributions의 값이다",
    )
    return parser


# ---------------------------------------------------------------------------
# raw/ 보관본에서 일봉 읽기
# ---------------------------------------------------------------------------


def trading_calendar(source: krx.DailyRowSource, start: date, until: date) -> list[date]:
    """보관된 지수 응답에서 거래일 달력을 만든다 (FR-009).

    원천은 휴장일에도 ETF 행을 주므로 달력 없이는 거래일을 가릴 수 없고, 그 달력을 소유한 것은
    지수 자료뿐이다(contracts/market-dataset.md ※1). 휴장일은 응답이 비어 있어 저절로 빠진다.
    """
    collection = index.collect_index_quotes(
        source, index.calendar_day_range(start, until), index_codes=[index.CALENDAR_INDEX_CODE]
    )
    calendar = collection.trading_calendar()
    if not calendar:
        raise BuildError(
            f"{start} ~ {until} 구간의 지수 보관본에서 거래일을 하나도 읽지 못했다. "
            "scripts/ingest_index.py로 그 구간을 먼저 수집해야 한다"
        )
    return calendar


def quotes_from_archive(
    source: krx.DailyRowSource, instrument_ids: Iterable[str], calendar: Sequence[date]
) -> dict[str, list[krx.DailyQuote]]:
    """보관된 ETF 응답에서 종목별 일봉을 거래일마다 읽는다."""
    wanted = list(dict.fromkeys(instrument_ids))
    collection = krx.collect_daily_quotes(source, calendar, instrument_ids=wanted)
    return {instrument_id: collection.for_instrument(instrument_id) for instrument_id in wanted}


# ---------------------------------------------------------------------------
# 락일로 붙이기
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PairedDistribution:
    """관측과 공시가 락일로 붙은 분배 한 건. ``corporate_action`` 한 행이 된다."""

    instrument_id: str
    ex_date: date
    pay_date: date
    amount: Decimal
    observed_amount: Decimal
    record_date: date
    acpt_no: str
    matched_by: str

    def to_record(self) -> dict[str, Any]:
        """``ingest_krx.py --corporate-actions``가 읽는 모양. 열 이름은 snake_case다."""
        return {
            "instrument_id": self.instrument_id,
            "ex_date": f"{self.ex_date:%Y-%m-%d}",
            "event_type": EVENT_DISTRIBUTION,
            "amount": str(self.amount),
            "pay_date": f"{self.pay_date:%Y-%m-%d}",
        }


@dataclass(frozen=True)
class UnpairedItem:
    """붙이지 못한 한 건. 어느 쪽에서 왔고 왜 못 붙었는지를 함께 남긴다."""

    instrument_id: str
    side: str
    day: date
    detail: str


#: :attr:`UnpairedItem.side`. 일봉에서 되살렸으나 공시를 찾지 못한 것이다. 지급일이 없어
#: ``corporate_action``으로 적재할 수 없다.
SIDE_OBSERVED: str = "observed"

#: :attr:`UnpairedItem.side`. 공시는 있으나 그 락일에 관측된 분배가 없는 것이다.
SIDE_DISCLOSED: str = "disclosed"


def previous_trading_day(calendar: Sequence[date], day: date) -> date | None:
    """달력에서 ``day`` **직전** 거래일을 찾는다. 달력 앞에 없으면 ``None``이다."""
    found: date | None = None
    for candidate in calendar:
        if candidate >= day:
            break
        found = candidate
    return found


def pair_by_ex_date(
    instrument_id: str,
    observed: Sequence[ObservedDistribution],
    records: Sequence[DistributionRecord],
    calendar: Sequence[date],
) -> tuple[list[PairedDistribution], list[UnpairedItem]]:
    """관측과 공시를 락일로 맞춘다. 붙지 않은 것은 버리지 않고 함께 돌려준다.

    공시의 락일은 지급기준일 직전 거래일이다(모듈 설명). 같은 락일을 두 공시가 가리키면 어느
    쪽인지 정할 수 없으므로 **둘 다 붙이지 않는다** — 하나를 골라 두면 고른 근거가 남지 않는다.
    """
    by_ex_date = {item.ex_date: item for item in observed}
    if len(by_ex_date) != len(observed):
        raise BuildError(f"{instrument_id}: 같은 락일의 관측이 두 번 있다")

    claims: dict[date, list[DistributionRecord]] = {}
    unpaired: list[UnpairedItem] = []
    for record in records:
        ex_date = previous_trading_day(calendar, record.record_date)
        if ex_date is None:
            unpaired.append(
                UnpairedItem(
                    instrument_id=instrument_id,
                    side=SIDE_DISCLOSED,
                    day=record.record_date,
                    detail=(
                        f"지급기준일 {record.record_date} 직전 거래일이 달력({calendar[0]} 이후)에 "
                        "없어 락일을 정할 수 없다"
                    ),
                )
            )
            continue
        claims.setdefault(ex_date, []).append(record)

    paired: list[PairedDistribution] = []
    for ex_date, claimants in sorted(claims.items()):
        if len(claimants) > 1:
            numbers = ", ".join(record.acpt_no for record in claimants)
            unpaired.extend(
                UnpairedItem(
                    instrument_id=instrument_id,
                    side=SIDE_DISCLOSED,
                    day=ex_date,
                    detail=f"같은 락일을 공시 {len(claimants)}건이 가리킨다 ({numbers})",
                )
                for _ in claimants
            )
            continue
        record = claimants[0]
        observation = by_ex_date.get(ex_date)
        if observation is None:
            unpaired.append(
                UnpairedItem(
                    instrument_id=instrument_id,
                    side=SIDE_DISCLOSED,
                    day=ex_date,
                    detail=(
                        f"공시({record.acpt_no}, 기준일 {record.record_date}, 금액 "
                        f"{record.amount})가 가리키는 락일에 관측된 분배가 없다"
                    ),
                )
            )
            continue
        difference = record.amount - observation.amount
        if not 0 <= difference < REFERENCE_PRICE_TICK:
            unpaired.append(
                UnpairedItem(
                    instrument_id=instrument_id,
                    side=SIDE_DISCLOSED,
                    day=ex_date,
                    detail=(
                        f"공시 금액 {record.amount}과 관측 금액 {observation.amount}의 차이가 "
                        f"호가단위({REFERENCE_PRICE_TICK}원) 안에 없다. 락일 대응이 틀렸을 수 "
                        f"있어 붙이지 않는다 (공시 {record.acpt_no})"
                    ),
                )
            )
            continue
        paired.append(
            PairedDistribution(
                instrument_id=instrument_id,
                ex_date=ex_date,
                pay_date=record.pay_date,
                amount=record.amount,
                observed_amount=observation.amount,
                record_date=record.record_date,
                acpt_no=record.acpt_no,
                matched_by=record.matched_by,
            )
        )

    matched_ex_dates = {item.ex_date for item in paired}
    unpaired.extend(
        UnpairedItem(
            instrument_id=instrument_id,
            side=SIDE_OBSERVED,
            day=item.ex_date,
            detail=(
                f"일봉에서 되살린 분배(금액 {item.amount})에 맞는 공시가 없다. "
                "지급일이 없어 적재할 수 없다"
            ),
        )
        for item in observed
        if item.ex_date not in matched_ex_dates
    )
    return paired, sorted(unpaired, key=lambda item: (item.day, item.side, item.detail))


# ---------------------------------------------------------------------------
# 실행
# ---------------------------------------------------------------------------


def resolve_instruments(
    requested: Sequence[str] | None, isins: Mapping[str, str]
) -> dict[str, str]:
    """요청한 종목을 ``{종목 식별자: ISIN}``으로 좁힌다. 대응표에 없으면 알린다."""
    if requested is None:
        return dict(isins)
    resolved: dict[str, str] = {}
    unknown: list[str] = []
    for value in requested:
        item = value.strip().upper()
        instrument_id = item if "." in item else krx.instrument_id_for(item)
        if instrument_id not in isins:
            unknown.append(value)
            continue
        resolved[instrument_id] = isins[instrument_id]
    if unknown:
        raise BuildError(
            f"대응표에 없는 종목이다: {unknown}. ISIN을 모르면 공시를 맞출 수 없으므로 "
            "대응표 파일에 먼저 등록해야 한다"
        )
    return resolved


def _print_summary(
    paired: Sequence[PairedDistribution],
    unpaired: Sequence[UnpairedItem],
    histories: Mapping[str, DistributionHistory],
    instruments: Mapping[str, str],
    calendar: Sequence[date],
) -> None:
    print(f"거래일 {len(calendar)}일 ({calendar[0]} ~ {calendar[-1]})")
    any_history = next(iter(histories.values()), None)
    if any_history is not None:
        print(f"KIND 분배 공시 {any_history.disclosures_read}건을 읽었다")
    print(f"붙은 분배 {len(paired)}건, 붙이지 못한 것 {len(unpaired)}건")

    print("\n종목별")
    for instrument_id, isin in sorted(instruments.items()):
        mine = [item for item in paired if item.instrument_id == instrument_id]
        left = [item for item in unpaired if item.instrument_id == instrument_id]
        span = f"{mine[0].ex_date} ~ {mine[-1].ex_date}" if mine else "없음"
        print(f"  {instrument_id} ({isin}) 붙음 {len(mine)}건 [{span}] 남음 {len(left)}건")

    if unpaired:
        print("\n붙이지 못한 것")
        for item in unpaired:
            print(f"  {item.instrument_id} {item.day} [{item.side}] {item.detail}")

    unkeyed = any_history.unkeyed if any_history is not None else ()
    if unkeyed:
        rows = sum(item.row_count for item in unkeyed)
        print(
            f"\n어느 종목의 것인지 맞추지 못한 공시 {len(unkeyed)}건({rows}행)이 있다. "
            "그 안에 우리 종목의 분배가 섞여 있을 수 있으므로 "
            "'분배가 이게 전부다'라고 말할 수 없다"
        )
        for item in unkeyed[:10]:
            names = ", ".join(item.names[:3])
            print(f"  {item.disclosed_on} {item.acpt_no} {item.row_count}행 ({names})")
        if len(unkeyed) > 10:
            print(f"  … 외 {len(unkeyed) - 10}건")


def run(args: argparse.Namespace, source: KindPageSource | None = None) -> int:
    isins = load_fund_isins(args.fund_names)
    fund_names = load_fund_names(args.fund_names)
    instruments = resolve_instruments(args.instruments, isins)
    if not instruments:
        raise BuildError("대상 종목이 하나도 없다")

    archive = krx.ArchiveReadingSource(root=args.raw_root)
    calendar = trading_calendar(archive, args.start, args.until)
    quotes = quotes_from_archive(archive, instruments, calendar)

    page_size: dict[str, int] = {} if args.page_size is None else {"page_size": args.page_size}
    histories = collect_distribution_histories(
        source if source is not None else KindHttpSource(),
        sorted(instruments.values()),
        args.start,
        args.until,
        fund_names=fund_names,
        **page_size,
    )

    paired: list[PairedDistribution] = []
    unpaired: list[UnpairedItem] = []
    for instrument_id, isin in sorted(instruments.items()):
        scan = observe_distributions(quotes[instrument_id])
        mine, left = pair_by_ex_date(
            instrument_id, scan.distributions, histories[isin].records, calendar
        )
        paired.extend(mine)
        unpaired.extend(left)
        for day, reason in scan.undecidable:
            if reason == "untraded_basis_price":
                unpaired.append(
                    UnpairedItem(
                        instrument_id=instrument_id,
                        side=SIDE_OBSERVED,
                        day=day,
                        detail="체결이 없어 그날 분배가 있었는지 판단하지 못했다",
                    )
                )

    paired.sort(key=lambda item: (item.instrument_id, item.ex_date))
    unpaired.sort(key=lambda item: (item.instrument_id, item.day))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps([item.to_record() for item in paired], ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"기업행위 {len(paired)}건을 {args.out}에 썼다\n")
    _print_summary(paired, unpaired, histories, instruments, calendar)
    return 1 if unpaired else 0


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return run(args)
    except (BuildError, krx.KrxError) as error:
        # 보관본이 없거나 모양이 다른 것도 실행 조건 문제다. 역추적을 쏟아내지 않고 알린다.
        print(f"오류: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
