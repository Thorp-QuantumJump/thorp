"""One command: obtain OHLCV, run Nautilus, write the EngineOutput message and artifacts."""

import argparse
import json
import sys
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from worker.contracts.engine_output import RunStatus
from worker.contracts.events import ProgressPoint
from worker.engine_contracts import EngineInput
from worker.engine_runner import NautilusMomentumEngine
from worker.market_data import CsvDataProvider


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


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
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("engine-output.json"),
        help="EngineOutput message JSON: exactly what would be published to the broker",
    )
    parser.add_argument(
        "--artifacts",
        type=Path,
        help="Local diagnostics JSON (order ledger + metadata); not part of the message. "
        "Default: <output>-artifacts.json next to --output",
    )
    args = parser.parse_args()
    artifacts_path = args.artifacts or args.output.with_name(
        f"{args.output.stem}-artifacts{args.output.suffix}"
    )
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

    def progress(point: ProgressPoint, done: int, total: int) -> None:
        # 계약의 진행 콜백 모양(거래일 1개 = ProgressPoint 1개). 20거래일 묶음은 사람이 읽는
        # 출력을 줄이려는 것이며, 실제 PROGRESS 발행 묶음은 실행 Consumer가 만든다.
        if done % 20 == 0 or done == total:
            print(f"Completed trading days: {done}/{total} ({point.date})", file=sys.stderr)

    engine = NautilusMomentumEngine(CsvDataProvider(args.fixture) if args.fixture else None)
    output, artifacts = engine.run_with_artifacts(request, progress)
    # Two files on purpose: --output is the broker message contract, --artifacts is local
    # diagnostics that the contract deliberately excludes.
    # 메시지는 계약 규약(README §5)대로 ContractModel.to_json()으로 직렬화한다: camelCase에
    # 계산 불가·미지정 값은 명시 null이 아니라 키 자체를 생략한다.
    write_json(args.output, json.loads(output.to_json()))
    write_json(artifacts_path, artifacts.model_dump(mode="json", by_alias=True))
    print(
        f"{output.run.status}: message {args.output}, artifacts {artifacts_path}",
        file=sys.stderr,
    )
    if output.run.error:
        print(output.run.error.message, file=sys.stderr)
    return 0 if output.run.status is RunStatus.COMPLETED else 1


if __name__ == "__main__":
    raise SystemExit(main())
