"""`worker/examples/three_etf_24m/` 픽스처를 다시 만든다(spec 004 T004, quickstart S8).

**값은 합성이다.** 실제 시세가 아니며 결과에는 `versions.data = "FIXTURE-three_etf_24m"`
가 붙어 사용자에게 제공되지 않는다(계약 「001 미완성 시」). 종목 코드는 spec 004
contracts/rest-api.md 예시와 같은 것을 써서 문서와 픽스처가 같은 종목을 가리키게 했다.

결정적이다 — 같은 씨앗이면 같은 파일이 나온다. 픽스처를 손으로 고치지 말고 이 스크립트를
고친 뒤 다시 돌린다(수기 대조 기대값이 픽스처에 묶여 있다).

    python worker/scripts/make_three_etf_fixture.py
"""

from __future__ import annotations

import csv
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path

import numpy as np

DATASET_ID = "FIXTURE-three_etf_24m"
OUT = Path(__file__).resolve().parents[1] / "examples" / "three_etf_24m"

START = date(2023, 1, 2)
END = date(2024, 12, 31)

# 합성 달력: 주말만 뺀 평일이다. KRX 공휴일은 넣지 않았다 — 공휴일 목록을 지어내는 것보다
# "거래일 집합"이라는 성질만 만족시키는 편이 정확하고, 공급자·엔진 어느 쪽도 달력의 내용을
# 가정하지 않는다(달력은 자료에서 만든다).
INSTRUMENTS = [
    # instrument_id, 이름, 섹터, 상장일, 시작가, 씨앗
    ("091160.XKRX", "반도체 섹터 ETF(픽스처)", "SEMICONDUCTOR", date(2015, 1, 2), 30000, 4201),
    ("305720.XKRX", "2차전지 섹터 ETF(픽스처)", "BATTERY", date(2018, 9, 12), 18000, 4202),
    # 상장일이 기간 한가운데인 종목 — 공통 평가 구간이 줄어드는 경우를 만든다(S8).
    ("244580.XKRX", "바이오 섹터 ETF(픽스처)", "BIO", date(2023, 7, 3), 12000, 4203),
]

# 분배금 2건: 지급일 현금 반영이 한 번만 일어나는지 확인한다.
DISTRIBUTIONS = [
    ("091160.XKRX", date(2023, 4, 27), date(2023, 5, 2), Decimal("120")),
    ("305720.XKRX", date(2024, 4, 29), date(2024, 5, 2), Decimal("85")),
]
# 액면분할 1건: 원시 가격은 반토막이고 조정 종가는 이어져야 한다.
SPLITS = [("244580.XKRX", date(2024, 3, 4), Decimal("2"))]
# 거래정지 1일: 결측이 아니라 volume 0 · OHLC 전부 직전 종가인 행이다.
SUSPENSIONS = {("091160.XKRX", date(2024, 6, 11))}

MAP_VERSION = "SECTOR-ETF-2026-09"
MAP_EFFECTIVE_FROM = date(2023, 1, 1)


def trading_days(start: date, end: date) -> list[date]:
    day, days = start, []
    while day <= end:
        if day.weekday() < 5:
            days.append(day)
        day += timedelta(days=1)
    return days


def build_prices(
    instrument_id: str, days: list[date], base: int, seed: int
) -> list[dict[str, object]]:
    rng = np.random.default_rng(seed)
    splits = {ex: float(ratio) for i, ex, ratio in SPLITS if i == instrument_id}
    level = float(base)
    previous_close: int | None = None
    rows: list[dict[str, object]] = []
    for day in days:
        if (instrument_id, day) in SUSPENSIONS:
            assert previous_close is not None, "첫 거래일을 정지일로 두지 않는다"
            rows.append(
                {
                    "date": day,
                    "open": previous_close,
                    "high": previous_close,
                    "low": previous_close,
                    "close": previous_close,
                    "volume": 0,
                    "trading_suspended": True,
                }
            )
            continue
        if day in splits:
            # 분할은 장 시작 전에 반영된다 — 권리락일 시가도 직전 종가가 아니라 그 절반 근처다.
            level /= splits[day]
            if previous_close is not None:
                previous_close = max(1, int(round(previous_close / splits[day])))
        level *= float(np.exp(rng.normal(0.0003, 0.012)))
        close = max(100, int(round(level)))
        open_ = max(100, int(round((previous_close or close) * (1 + rng.normal(0, 0.004)))))
        span = abs(rng.normal(0, 0.006))
        high = max(open_, close, int(round(max(open_, close) * (1 + span))))
        low = min(open_, close, max(1, int(round(min(open_, close) * (1 - span)))))
        rows.append(
            {
                "date": day,
                "open": open_,
                "high": high,
                "low": low,
                "close": close,
                "volume": int(rng.integers(50_000, 400_000)),
                "trading_suspended": False,
            }
        )
        previous_close = close
    return rows


def adjusted_closes(instrument_id: str, rows: list[dict[str, object]]) -> dict[date, str]:
    """분할·분배를 뒤에서부터 반영한 조정 종가.

    이 기간 전체가 같은 `dataset_id` 이므로 한 번에 일관되게 계산한다(계약 불변 조건).
    분할 비율 r 의 이전 구간은 1/r 배, 분배금 d 의 이전 구간은 (1 - d/직전종가) 배다.
    """
    events: list[tuple[date, str, Decimal]] = []
    events += [(ex, "SPLIT", ratio) for i, ex, ratio in SPLITS if i == instrument_id]
    events += [
        (ex, "DISTRIBUTION", amount) for i, ex, _pay, amount in DISTRIBUTIONS if i == instrument_id
    ]
    events.sort(key=lambda item: item[0], reverse=True)

    factor = Decimal(1)
    pending = 0
    adjusted: dict[date, str] = {}
    for row in sorted(rows, key=lambda r: r["date"], reverse=True):
        day: date = row["date"]  # type: ignore[assignment]
        close = Decimal(row["close"])  # type: ignore[arg-type]
        # 권리락일보다 앞선 날부터 배수를 적용한다. 권리락일 당일 가격에는 이미 반영돼 있다.
        while pending < len(events) and events[pending][0] > day:
            _ex, kind, amount = events[pending]
            factor = factor / amount if kind == "SPLIT" else factor * (close - amount) / close
            pending += 1
        adjusted[day] = f"{(close * factor).quantize(Decimal('0.000001'))}"
    return adjusted


def write_csv(name: str, header: list[str], rows: list[list[object]]) -> None:
    path = OUT / name
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)
    print(f"{path.relative_to(OUT.parents[2])}: {len(rows)} rows")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    calendar = trading_days(START, END)

    raw_rows: list[list[object]] = []
    adjusted_rows: list[list[object]] = []
    for instrument_id, _name, _sector, listed_on, base, seed in INSTRUMENTS:
        days = [day for day in calendar if day >= listed_on]
        rows = build_prices(instrument_id, days, base, seed)
        adjusted = adjusted_closes(instrument_id, rows)
        for row in rows:
            raw_rows.append(
                [
                    instrument_id,
                    row["date"],
                    row["open"],
                    row["high"],
                    row["low"],
                    row["close"],
                    row["volume"],
                    "true" if row["trading_suspended"] else "false",
                    DATASET_ID,
                ]
            )
            adjusted_rows.append([instrument_id, row["date"], adjusted[row["date"]], DATASET_ID])

    write_csv(
        "prices_raw.csv",
        [
            "instrument_id",
            "date",
            "open",
            "high",
            "low",
            "close",
            "volume",
            "trading_suspended",
            "dataset_id",
        ],
        raw_rows,
    )
    write_csv(
        "prices_adjusted.csv",
        ["instrument_id", "date", "adjusted_close", "dataset_id"],
        adjusted_rows,
    )
    write_csv(
        "distributions.csv",
        ["instrument_id", "ex_date", "pay_date", "amount", "dataset_id"],
        [[i, ex, pay, amount, DATASET_ID] for i, ex, pay, amount in DISTRIBUTIONS],
    )
    write_csv(
        "splits.csv",
        ["instrument_id", "ex_date", "amount", "dataset_id"],
        [[i, ex, ratio, DATASET_ID] for i, ex, ratio in SPLITS],
    )
    write_csv(
        "instruments.csv",
        ["instrument_id", "listed_on", "delisted_on", "name", "benchmark_index"],
        [
            [instrument_id, listed_on, "", name, f"{sector}_INDEX(픽스처)"]
            for instrument_id, name, sector, listed_on, _base, _seed in INSTRUMENTS
        ],
    )
    write_csv(
        "mapping.csv",
        ["version", "effective_from", "effective_to", "sector_id", "instrument_id"],
        [
            [MAP_VERSION, MAP_EFFECTIVE_FROM, "", sector, instrument_id]
            for instrument_id, _name, sector, _listed, _base, _seed in INSTRUMENTS
        ],
    )


if __name__ == "__main__":
    main()
