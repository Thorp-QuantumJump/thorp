"""환경 간 재현 비교 보고서(spec 004 T030).

골든 테스트(``tests/test_rules_goldens.py``)와 같은 기준 입력·규칙 스냅샷으로 엔진을 돌려, 전략마다
반올림 없는 해시(``exactSha256``)와 골든 해시(``roundedSha256``, 유효숫자 12자리)를 환경 정보와 함께
JSON으로 낸다. 두 환경의 보고서를 나란히 놓으면 어느 전략이 어느 수준에서 갈리는지 보인다.

기준 입력은 ``docs/specs/backtest-messaging/examples`` 와 ``worker/tests/fixtures`` 에 있으므로
저장소 전체가 보이는 곳에서 ``worker/`` 를 작업 디렉터리로 두고 실행한다::

    uv run --no-sync python scripts/reproducibility_report.py --out report.json

컨테이너에서는 저장소 전체를 마운트하고 이미지의 가상환경으로 돌린다::

    docker run --rm -v "<저장소>:/repo" -w /repo/worker -e PYTHONPATH=/repo/worker/src \\
        -e THORP_IMAGE=<이미지 digest> <워커 이미지> \\
        python scripts/reproducibility_report.py --out /repo/worker/report.json
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import sys
from importlib import metadata
from pathlib import Path
from typing import Any

WORKER_DIR = Path(__file__).resolve().parents[1]
# 기준 입력을 만드는 방법은 골든 테스트 하나가 정한다 — 여기서 다시 쓰면 둘이 어긋난다.
sys.path.insert(0, str(WORKER_DIR / "tests"))

from golden_inputs import (  # noqa: E402
    BASE_INPUTS,
    engine_input_for,
    golden_path,
    load_json,
    rule_snapshots,
    run,
)

from worker.reproducibility import (  # noqa: E402
    computed,
    digest,
    exact_digest,
    input_digest,
)

PACKAGES = ("numpy", "scipy", "pandas", "nautilus_trader", "pydantic")


def cpu_model() -> str:
    cpuinfo = Path("/proc/cpuinfo")
    if cpuinfo.exists():
        for line in cpuinfo.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.lower().startswith(("model name", "cpu model", "hardware")):
                return line.split(":", 1)[1].strip()
    return platform.processor()


def blas_info() -> dict[str, Any]:
    """numpy 가 쓰는 BLAS/LAPACK. float 끝자리 차이는 대개 여기서 난다(커널이 CPU마다 다르다)."""
    import numpy

    try:
        config: dict[str, Any] = numpy.show_config(mode="dicts")  # type: ignore[no-untyped-call]
    except TypeError:
        return {}
    dependencies = config.get("Build Dependencies", {})
    keys = ("name", "version", "openblas configuration")
    return {
        name: {key: dependencies.get(name, {}).get(key) for key in keys}
        for name in ("blas", "lapack")
    }


def environment() -> dict[str, Any]:
    return {
        "label": os.environ.get("THORP_ENV_LABEL"),
        "image": os.environ.get("THORP_IMAGE"),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "cpu": cpu_model(),
        "python": platform.python_version(),
        "packages": {name: metadata.version(name) for name in PACKAGES},
        "blas": blas_info(),
    }


def report(dump_dir: Path | None) -> dict[str, Any]:
    results: dict[str, Any] = {}
    for snapshot_path in rule_snapshots():
        snapshot = load_json(snapshot_path)
        body = engine_input_for(snapshot)
        payload = computed(run(body))
        golden_file = golden_path(snapshot_path)
        golden = load_json(golden_file) if golden_file.exists() else None
        rounded_sha = digest(payload)
        results[snapshot_path.stem] = {
            "engineInput": BASE_INPUTS[snapshot["strategyId"]],
            "inputSha256": input_digest(body),
            "exactSha256": exact_digest(payload),
            "roundedSha256": rounded_sha,
            "goldenMatch": None if golden is None else golden["sha256"] == rounded_sha,
        }
        if dump_dir is not None:
            dump_dir.mkdir(parents=True, exist_ok=True)
            text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
            (dump_dir / f"{snapshot_path.stem}.json").write_text(
                text, encoding="utf-8", newline="\n"
            )
    return {"environment": environment(), "results": results}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, help="보고서 JSON 경로(없으면 표준 출력)")
    parser.add_argument("--dump-dir", type=Path, help="전략별 계산 결과 전문을 남길 디렉터리")
    args = parser.parse_args()

    text = json.dumps(report(args.dump_dir), ensure_ascii=False, indent=2) + "\n"
    if args.out is None:
        sys.stdout.write(text)
    else:
        args.out.write_text(text, encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
