"""한 명령으로 EngineInput JSON 을 픽스처 자료로 실행해 EngineOutput 메시지와 산출물을 쓴다.

    python -m worker.demo --input engine-input.json --fixture worker/examples/three_etf_24m

실제 시세는 받지 않는다 — 운영 자료는 001 이 적재한 DB(``MARKET_DATA_URL``)에서만 읽고, 그 경로는
``worker.main`` 이다. 여기는 계약 메시지 모양과 픽스처 결과를 눈으로 확인하는 개발용이다.
"""

import argparse
import json
import sys
from pathlib import Path

from worker.contracts.engine_input import EngineInput
from worker.contracts.engine_output import RunStatus
from worker.contracts.events import ProgressPoint
from worker.engine_runner import PortfolioBacktestEngine
from worker.market_data import CsvMarketDataProvider

DEFAULT_FIXTURE = Path(__file__).resolve().parents[2] / "examples" / "three_etf_24m"


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Full EngineInput JSON")
    parser.add_argument(
        "--fixture",
        type=Path,
        default=DEFAULT_FIXTURE,
        help="Fixture directory in the market-data table layout (synthetic, not real prices)",
    )
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
    request = EngineInput.model_validate_json(args.input.read_text(encoding="utf-8"))

    def progress(point: ProgressPoint, done: int, total: int) -> None:
        # 계약의 진행 콜백 모양(거래일 1개 = ProgressPoint 1개). 20거래일 묶음은 사람이 읽는
        # 출력을 줄이려는 것이며, 실제 PROGRESS 발행 묶음은 실행 Consumer가 만든다.
        if done % 20 == 0 or done == total:
            print(f"Completed trading days: {done}/{total} ({point.date})", file=sys.stderr)

    engine = PortfolioBacktestEngine(CsvMarketDataProvider(args.fixture))
    output, artifacts = engine.run_with_artifacts(request, progress)
    # 메시지는 계약 규약(README §5)대로 ContractModel.to_json()으로 직렬화한다: camelCase에
    # 계산 불가·미지정 값은 명시 null이 아니라 키 자체를 생략한다. 산출물은 계약 밖 로컬 진단이다.
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
