#!/usr/bin/env bash
# 단일 프로세스 baseline 측정 (서버용).
#
# 측정 조건은 docs/specs/baseline-measurement.md 2절을 따른다.
# - --no-deps: Kafka/PostgreSQL을 띄우지 않는다. 측정 중 CPU 경합을 줄이기 위함.
# - --cooldown: Lightsail 버스터블 인스턴스의 CPU 크레딧 회복 시간.
#
# 사용: infra/scripts/run-baseline.sh [추가 인자...]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

OUT_NAME="baseline-$(date +%Y%m%d-%H%M%S).json"

echo "== 측정 전 상태 =="
uptime || true
docker ps --format 'table {{.Names}}\t{{.Status}}' || true
echo
echo "위 컨테이너들이 CPU를 나눠 쓴다. 측정 정확도를 위해 불필요한 것은 멈추고 진행할 것."
echo

docker compose --profile app build worker

docker compose --profile app run --rm --no-deps worker \
    python -m worker.baseline \
    --start "${BASELINE_START:-2021-01-01}" \
    --end "${BASELINE_END:-2025-12-31}" \
    --jobs "${BASELINE_JOBS:-20}" \
    --repeat "${BASELINE_REPEAT:-5}" \
    --warmup 1 \
    --cooldown "${BASELINE_COOLDOWN:-30}" \
    --profile \
    --out "/app/out/${OUT_NAME}" \
    "$@"

echo
echo "결과: benchmarks/${OUT_NAME}"
echo "측정 후 Lightsail 콘솔의 CPU 사용률 그래프를 확인할 것."
echo "지속 가능 영역(sustainable zone)을 오래 벗어났다면 이 회차는 버린다."
