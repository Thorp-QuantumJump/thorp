"""baseline 러너 테스트."""

import json
from datetime import date
from pathlib import Path

import pytest

from worker.baseline import build_jobs, collect_environment, main, summarize
from worker.data import count_rows, generate_synthetic_prices


def test_build_jobs_sweeps_parameters() -> None:
    jobs = build_jobs(3, date(2024, 1, 1), date(2024, 12, 31))

    assert [job.job_id for job, _ in jobs] == [1, 2, 3]
    # 파라미터 조합이 서로 달라야 v0.1.2의 파라미터 스윕을 축소 재현한 것이 된다
    assert len({strategy.lookback_days for _, strategy in jobs}) == 3


def test_summarize_reports_median_and_spread() -> None:
    summary = summarize([1.0, 2.0, 3.0, 4.0, 100.0])

    assert summary["median"] == 3.0
    assert summary["min"] == 1.0
    assert summary["max"] == 100.0
    assert summary["p95"] > summary["median"]


def test_environment_records_comparison_keys() -> None:
    """다른 머신 수치가 섞였을 때 드러나야 하는 항목들."""
    env = collect_environment()

    for key in ("hostname", "cpu_count", "python", "pandas", "numpy", "thread_env"):
        assert key in env


def test_synthetic_data_is_reproducible() -> None:
    args = (["005930", "000660"], date(2024, 1, 1), date(2024, 6, 30))

    first = generate_synthetic_prices(*args)
    second = generate_synthetic_prices(*args)

    assert count_rows(first) == count_rows(second)
    for ticker in first:
        assert first[ticker]["close"].equals(second[ticker]["close"])


def test_synthetic_tickers_differ() -> None:
    prices = generate_synthetic_prices(["A", "B"], date(2024, 1, 1), date(2024, 6, 30))

    assert not prices["A"]["close"].equals(prices["B"]["close"])


def test_main_writes_report(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    out = tmp_path / "baseline.json"

    exit_code = main(
        [
            "--synthetic",
            "--start",
            "2024-01-01",
            "--end",
            "2024-06-30",
            "--tickers",
            "005930",
            "--jobs",
            "2",
            "--repeat",
            "2",
            "--warmup",
            "0",
            "--profile",
            "--out",
            str(out),
        ]
    )

    assert exit_code == 0
    report = json.loads(out.read_text(encoding="utf-8"))
    assert report["mode"] == "baseline"
    assert report["workload"]["jobs_per_run"] == 2
    assert len(report["timings"]["runs_sec"]) == 2
    assert report["timings"]["throughput_jobs_per_sec"] > 0
    assert report["projection"]["estimated_sec"] > 0
    assert set(report["phases"]) == {"signal_sec", "simulate_sec", "metrics_sec"}
    # 합성 데이터 / 컨테이너 밖 실행은 반드시 경고로 남는다
    assert report["warnings"]
    assert "baseline" in capsys.readouterr().out
