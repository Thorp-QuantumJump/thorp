"""One command: obtain OHLCV, run Nautilus, write EngineOutput JSON."""

import argparse
import json
import sys
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from worker.engine import run_engine
from worker.engine_contracts import EngineInput, ProgressSnapshot
from worker.market_data import CsvDataProvider


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input", type=Path, help="Full EngineInput JSON; overrides CLI run defaults"
    )
    parser.add_argument("--start")
    parser.add_argument("--end")
    parser.add_argument("--ticker", default="000660.KS")
    parser.add_argument(
        "--fixture", type=Path, help="Explicit CSV test data (not real market data)"
    )
    parser.add_argument("--lookback-days", type=int, default=120)
    parser.add_argument("--output", type=Path, default=Path("engine-output.json"))
    args = parser.parse_args()
    if args.input:
        request = EngineInput.model_validate_json(args.input.read_text(encoding="utf-8"))
    else:
        now = datetime.now(ZoneInfo("Asia/Seoul"))
        end = now.date() if now.hour >= 16 else now.date() - timedelta(days=1)
        if args.end:
            end = datetime.strptime(args.end, "%Y-%m-%d").date()
        start = end - timedelta(days=365 * 5)
        if args.start:
            start = datetime.strptime(args.start, "%Y-%m-%d").date()
        if not args.ticker.endswith(".KS"):
            parser.error("--ticker must be an explicit KOSPI ticker such as 000660.KS")
        request = EngineInput.model_validate(
            {
                "run": {"backtestId": "BT-MOMENTUM-DEMO", "start": start, "end": end},
                "universe": {"instrumentIds": [args.ticker[:-3] + ".XKRX"]},
                "strategy": {"parameters": {"lookbackDays": args.lookback_days}},
            }
        )

    def progress(snapshot: ProgressSnapshot) -> None:
        done, total = snapshot.completed_trading_days, snapshot.total_trading_days
        if done % 20 == 0 or done == total:
            print(f"Completed trading days: {done}/{total}", file=sys.stderr)

    output = run_engine(
        request,
        data_provider=CsvDataProvider(args.fixture) if args.fixture else None,
        on_snapshot=progress,
    )
    # Preserve required null metrics/OPEN fields; omit only unchanged positions snapshots.
    payload = output.model_dump(mode="json", by_alias=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{output.run.status}: {args.output}", file=sys.stderr)
    if output.error:
        print(output.error.message, file=sys.stderr)
    return 0 if output.run.status == "COMPLETED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
