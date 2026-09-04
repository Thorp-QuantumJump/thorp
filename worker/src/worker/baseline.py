"""단일 프로세스 baseline 러너.

Kafka/DB 없이, Worker가 실제로 쓰는 것과 **동일한 engine**으로 Job을 순차 실행해
소요시간을 측정한다. 분산 처리 speedup 비교의 분모(대조군)를 만드는 것이 목적이다.

실행:
    python -m worker.baseline --synthetic --jobs 20 --repeat 5

서버(컨테이너):
    docker compose --profile app run --rm worker
        python -m worker.baseline --jobs 20 --repeat 5 --cooldown 30 --out /app/out/baseline.json

측정 방법론은 docs/specs/baseline-measurement.md 를 따른다.
"""

import argparse
import json
import logging
import os
import platform
import socket
import statistics
import subprocess
import sys
import time
from collections.abc import Mapping, Sequence
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from worker import data as market_data
from worker.engine import DEFAULT_COST_BPS, run_portfolio_backtest
from worker.models import BacktestJob, BacktestResult
from worker.strategies.base import Strategy
from worker.strategies.momentum import MomentumStrategy

logger = logging.getLogger("worker.baseline")

#: 결과 JSON 스키마 버전. 분산 측정 결과도 같은 스키마로 뱉는다.
SCHEMA_VERSION = 1

#: 기본 유니버스 (KRX 대형주). 측정 재현성을 위해 고정한다.
DEFAULT_TICKERS = [
    "005930",  # 삼성전자
    "000660",  # SK하이닉스
    "373220",  # LG에너지솔루션
    "207940",  # 삼성바이오로직스
    "005380",  # 현대차
    "000270",  # 기아
    "068270",  # 셀트리온
    "035420",  # NAVER
    "051910",  # LG화학
    "006400",  # 삼성SDI
]

#: numpy/BLAS가 몰래 멀티코어를 쓰면 "단일 프로세스" 전제가 깨진다.
THREAD_ENV_VARS = [
    "OMP_NUM_THREADS",
    "OPENBLAS_NUM_THREADS",
    "MKL_NUM_THREADS",
    "NUMEXPR_NUM_THREADS",
]


# --------------------------------------------------------------------------
# 측정 환경 메타데이터
# --------------------------------------------------------------------------
def _total_memory_gb() -> float | None:
    try:
        return round(os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") / 1024**3, 1)
    except (ValueError, AttributeError, OSError):
        return None


def _load_average() -> list[float] | None:
    try:
        return [round(v, 2) for v in os.getloadavg()]
    except (AttributeError, OSError):
        return None


def _git_commit() -> str | None:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        return out.stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


def collect_environment() -> dict[str, Any]:
    """측정 환경을 기록한다. 다른 머신의 수치가 섞이면 바로 드러나게 하는 것이 목적이다."""
    return {
        "hostname": socket.gethostname(),
        "in_container": Path("/.dockerenv").exists(),
        "platform": platform.platform(),
        "processor": platform.processor() or None,
        "cpu_count": os.cpu_count(),
        "memory_gb": _total_memory_gb(),
        "python": platform.python_version(),
        "pandas": pd.__version__,
        "numpy": np.__version__,
        "thread_env": {name: os.environ.get(name) for name in THREAD_ENV_VARS},
        "git_commit": _git_commit(),
    }


# --------------------------------------------------------------------------
# 워크로드 구성
# --------------------------------------------------------------------------
def build_jobs(count: int, start: date, end: date) -> list[tuple[BacktestJob, Strategy]]:
    """파라미터 스윕으로 Job N건을 만든다.

    Job 1건 = 전략 1개 × 파라미터 1조합 × 유니버스 전체 × 기간 전체.
    v0.1.2의 '파라미터 전 조합'을 축소 재현한 형태다.
    """
    jobs: list[tuple[BacktestJob, Strategy]] = []
    for i in range(count):
        lookback = 20 + i * 10
        job = BacktestJob(
            job_id=i + 1,
            strategy=MomentumStrategy.name,
            start_date=start,
            end_date=end,
        )
        jobs.append((job, MomentumStrategy(lookback_days=lookback)))
    return jobs


def run_once(
    jobs: Sequence[tuple[BacktestJob, Strategy]],
    prices: Mapping[str, pd.DataFrame],
    cost_bps: float,
) -> tuple[float, list[BacktestResult]]:
    """Job 목록을 순차 실행하고 총 소요시간(초)과 결과를 반환한다."""
    results: list[BacktestResult] = []
    started = time.perf_counter()
    for job, strategy in jobs:
        results.append(run_portfolio_backtest(job, strategy, prices, cost_bps))
    return time.perf_counter() - started, results


def summarize(values: Sequence[float]) -> dict[str, float]:
    """반복 측정치 요약. 평균만 보면 튀는 값에 속으므로 median을 대표값으로 쓴다."""
    ordered = sorted(values)
    return {
        "median": round(statistics.median(ordered), 6),
        "mean": round(statistics.fmean(ordered), 6),
        "p95": round(float(np.percentile(ordered, 95)), 6),
        "min": round(ordered[0], 6),
        "max": round(ordered[-1], 6),
        "stdev": round(statistics.stdev(ordered), 6) if len(ordered) > 1 else 0.0,
    }


def profile_phases(
    job_and_strategy: tuple[BacktestJob, Strategy],
    prices: Mapping[str, pd.DataFrame],
    cost_bps: float,
) -> dict[str, float]:
    """Job 1건의 구간별 소요시간. 분산 시 어느 구간이 중복 비용이 되는지 보기 위함."""
    from worker.engine import compute_metrics, count_trades, simulate, to_positions

    _, strategy = job_and_strategy
    signal_sec = simulate_sec = 0.0

    per_ticker: dict[str, pd.Series] = {}
    trades = 0
    for ticker, frame in prices.items():
        t0 = time.perf_counter()
        signals = strategy.generate_signals(frame)
        t1 = time.perf_counter()
        positions = to_positions(signals)
        per_ticker[ticker] = simulate(frame, positions, cost_bps)
        trades += count_trades(positions)
        t2 = time.perf_counter()
        signal_sec += t1 - t0
        simulate_sec += t2 - t1

    t3 = time.perf_counter()
    compute_metrics(pd.DataFrame(per_ticker).mean(axis=1), trades)
    metrics_sec = time.perf_counter() - t3

    return {
        "signal_sec": round(signal_sec, 6),
        "simulate_sec": round(simulate_sec, 6),
        "metrics_sec": round(metrics_sec, 6),
    }


# --------------------------------------------------------------------------
# 리포트
# --------------------------------------------------------------------------
def _humanize(seconds: float) -> str:
    if seconds < 60:
        return f"{seconds:.1f}초"
    if seconds < 3600:
        return f"{seconds / 60:.1f}분"
    return f"{seconds / 3600:.2f}시간"


def render_report(report: dict[str, Any]) -> str:
    env = report["environment"]
    work = report["workload"]
    per_job = report["timings"]["per_job_sec"]
    per_run = report["timings"]["per_run_sec"]
    proj = report["projection"]
    sample = report["result_sample"]
    where = " (container)" if env["in_container"] else ""

    lines = [
        "",
        "=" * 62,
        f"  단일 프로세스 baseline 측정 결과  (mode={report['mode']})",
        "=" * 62,
        f"  측정 시각    : {report['measured_at']}",
        f"  호스트       : {env['hostname']}{where}, vCPU {env['cpu_count']}개",
        f"  라이브러리   : python {env['python']} / pandas {env['pandas']} / numpy {env['numpy']}",
        "-" * 62,
        f"  전략         : {work['strategy']}  (파라미터 {work['jobs_per_run']}조합)",
        f"  기간         : {work['start']} ~ {work['end']}",
        f"  유니버스     : {len(work['tickers'])}종목, 입력 {work['input_rows']:,}행",
        f"  데이터 출처  : {work['data_source']}",
        "-" * 62,
        f"  데이터 로드  : {report['timings']['data_load_sec']:.3f}초 (1회, 측정에서 제외)",
        f"  반복         : warmup {report['timings']['warmup']}회 + 측정 "
        f"{report['timings']['repeat']}회",
        "",
        f"  Job 1건      : median {per_job['median'] * 1000:.1f}ms  "
        f"(p95 {per_job['p95'] * 1000:.1f}ms, min {per_job['min'] * 1000:.1f}ms, "
        f"max {per_job['max'] * 1000:.1f}ms)",
        f"  {work['jobs_per_run']}건 순차   : median {per_run['median']:.3f}초",
        f"  처리량       : {report['timings']['throughput_jobs_per_sec']:.1f} jobs/sec",
        "-" * 62,
        f"  [추정] v0.1.2 예상 {proj['jobs']:,}건을 단일 프로세스로 처리 시",
        f"         -> 약 {_humanize(proj['estimated_sec'])}",
        "-" * 62,
        f"  결과 검증용 지표 (job_id={sample['job_id']}): "
        f"수익률 {sample['total_return']:.6f} / MDD {sample['mdd']:.6f} / "
        f"Sharpe {sample['sharpe']:.6f} / 거래 {sample['trade_count']}회",
        "=" * 62,
    ]
    if report["phases"]:
        ph = report["phases"]
        total = sum(ph.values()) or 1.0
        lines += [
            "  Job 1건 구간별 (참고):",
            f"    신호 생성 {ph['signal_sec'] * 1000:7.1f}ms ({ph['signal_sec'] / total:5.1%})",
            f"    체결 시뮬 {ph['simulate_sec'] * 1000:7.1f}ms ({ph['simulate_sec'] / total:5.1%})",
            f"    지표 계산 {ph['metrics_sec'] * 1000:7.1f}ms ({ph['metrics_sec'] / total:5.1%})",
            "=" * 62,
        ]
    return "\n".join(lines)


def _warn_on_risky_environment(env: dict[str, Any], args: argparse.Namespace) -> list[str]:
    """측정치를 신뢰할 수 없게 만드는 조건을 경고로 남긴다."""
    warnings: list[str] = []
    if not any(env["thread_env"].values()):
        warnings.append(
            "BLAS 스레드 수가 고정되지 않았다(OMP_NUM_THREADS 등 미설정). "
            "'단일 프로세스'가 멀티코어를 쓸 수 있으니 OMP_NUM_THREADS=1 로 실행할 것."
        )
    if args.data_source == "synthetic":
        warnings.append("합성 데이터로 측정했다. 공식 수치로 쓰지 말 것(실제 KRX 스냅샷 필요).")
    if not env["in_container"]:
        warnings.append("컨테이너 밖에서 측정했다. 공식 수치는 서버 컨테이너에서 측정할 것.")
    if args.cooldown == 0 and args.repeat > 3:
        warnings.append(
            "cooldown 없이 연속 반복했다. Lightsail은 버스터블 인스턴스라 "
            "CPU 크레딧 소진 시 스로틀링될 수 있다(--cooldown 권장)."
        )
    return warnings


# --------------------------------------------------------------------------
# 엔트리포인트
# --------------------------------------------------------------------------
def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="python -m worker.baseline",
        description="단일 프로세스 baseline 백테스트 러너 (분산 speedup 비교의 기준선)",
    )
    parser.add_argument("--start", default="2021-01-01", help="시작일 YYYY-MM-DD")
    parser.add_argument("--end", default="2025-12-31", help="종료일 YYYY-MM-DD")
    parser.add_argument("--tickers", nargs="*", default=None, help="종목코드 (기본: 대형주 10종)")
    parser.add_argument("--jobs", type=int, default=20, help="1회 측정에서 순차 실행할 Job 수")
    parser.add_argument("--repeat", type=int, default=5, help="측정 반복 횟수 (median 사용)")
    parser.add_argument("--warmup", type=int, default=1, help="측정에서 제외할 예열 실행 횟수")
    parser.add_argument(
        "--cooldown", type=float, default=0.0, help="반복 사이 대기(초). CPU 크레딧 회복용"
    )
    parser.add_argument(
        "--data-path",
        default=os.environ.get("MARKET_DATA_PATH", "/app/data"),
        help="Parquet 시세 스냅샷 경로 (env: MARKET_DATA_PATH)",
    )
    parser.add_argument(
        "--synthetic", action="store_true", help="합성 데이터로 실행(배선 확인용, 공식 수치 아님)"
    )
    parser.add_argument(
        "--cost-bps", type=float, default=DEFAULT_COST_BPS, help="편도 거래비용(bp)"
    )
    parser.add_argument(
        "--project-jobs", type=int, default=2400, help="v0.1.2 예상 Job 수(소요시간 추정용)"
    )
    parser.add_argument("--profile", action="store_true", help="Job 1건 구간별 소요시간도 측정")
    parser.add_argument("--out", default=None, help="결과 JSON 저장 경로")
    args = parser.parse_args(argv)

    if args.jobs < 1 or args.repeat < 1:
        parser.error("--jobs 와 --repeat 은 1 이상이어야 한다")
    args.data_source = "synthetic" if args.synthetic else f"parquet:{args.data_path}"
    return args


def main(argv: Sequence[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args = parse_args(argv)
    start = date.fromisoformat(args.start)
    end = date.fromisoformat(args.end)
    tickers = args.tickers or DEFAULT_TICKERS

    # 1) 데이터 로드 (I/O). Job 반복 측정에서는 제외하고 따로 기록한다.
    #    분산 시 이 비용은 워커마다 중복 발생하므로 계산 시간과 섞으면 안 된다.
    t0 = time.perf_counter()
    if args.synthetic:
        prices = market_data.generate_synthetic_prices(tickers, start, end)
    else:
        prices = market_data.load_prices(args.data_path, tickers, start, end)
    data_load_sec = time.perf_counter() - t0
    input_rows = market_data.count_rows(prices)
    logger.info("데이터 로드 완료: %d종목 %d행 (%.3f초)", len(prices), input_rows, data_load_sec)

    jobs = build_jobs(args.jobs, start, end)

    # 2) 예열 (import 지연, 캐시 워밍 등을 측정에서 배제)
    for _ in range(args.warmup):
        run_once(jobs, prices, args.cost_bps)

    # 3) 본 측정
    load_before = _load_average()
    durations: list[float] = []
    results: list[BacktestResult] = []
    for i in range(args.repeat):
        if i > 0 and args.cooldown > 0:
            logger.info("cooldown %.0f초 대기 (CPU 크레딧 회복)", args.cooldown)
            time.sleep(args.cooldown)
        elapsed, results = run_once(jobs, prices, args.cost_bps)
        durations.append(elapsed)
        logger.info("[%d/%d] %d건 %.3f초", i + 1, args.repeat, args.jobs, elapsed)
    load_after = _load_average()

    per_job = [d / args.jobs for d in durations]
    per_job_summary = summarize(per_job)
    env = collect_environment()

    report: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "mode": "baseline",
        "measured_at": datetime.now(UTC).astimezone().isoformat(timespec="seconds"),
        "environment": env,
        "workload": {
            "strategy": MomentumStrategy.name,
            "start": args.start,
            "end": args.end,
            "tickers": tickers,
            "input_rows": input_rows,
            "jobs_per_run": args.jobs,
            "cost_bps": args.cost_bps,
            "data_source": args.data_source,
        },
        "timings": {
            "data_load_sec": round(data_load_sec, 6),
            "warmup": args.warmup,
            "repeat": args.repeat,
            "cooldown_sec": args.cooldown,
            "runs_sec": [round(d, 6) for d in durations],
            "per_run_sec": summarize(durations),
            "per_job_sec": per_job_summary,
            "throughput_jobs_per_sec": round(1.0 / per_job_summary["median"], 3),
            "load_average_before": load_before,
            "load_average_after": load_after,
        },
        "projection": {
            "jobs": args.project_jobs,
            "estimated_sec": round(per_job_summary["median"] * args.project_jobs, 3),
        },
        "result_sample": results[0].model_dump(),
        "phases": profile_phases(jobs[0], prices, args.cost_bps) if args.profile else None,
        "warnings": _warn_on_risky_environment(env, args),
    }

    print(render_report(report))
    for warning in report["warnings"]:
        logger.warning("%s", warning)

    if args.out:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        logger.info("결과 저장: %s", out_path)

    return 0


if __name__ == "__main__":
    sys.exit(main())
