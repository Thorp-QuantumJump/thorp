#!/usr/bin/env bash
# backtest.jobs 큐를 classic → quorum(x-delivery-limit=1)으로 전환한다. 최초 1회.
#
# 왜 필요한가 (spec 004 R-06, 계약 README §2): 백엔드 v2(계약 schemaVersion 2)는 backtest.jobs를
# quorum으로 선언한다. classic 큐가 이미 있으면 인자가 달라 재선언이 PRECONDITION_FAILED로 실패하고,
# 큐는 classic으로 남아 x-delivery-limit(자동 재시도 1회)이 적용되지 않는다.
# 따라서 v2 백엔드를 띄우기 전에 기존 큐를 지워야 하며, 이 스크립트는 "지워도 되는 상태"를 확인한 뒤 지운다.
#
# 사용:
#   ./scripts/migrate-jobs-queue.sh          운영: docker-compose.prod.yml + .env.prod (infra/ 에서 실행)
#   ./scripts/migrate-jobs-queue.sh --dev    개발: 레포 루트 docker-compose.dev.yml. 백엔드·워커는 사람이 내린다
#
# 절차: 큐 상태 확인 → 접수 중단(backend·worker 정지) → ready·unacked 모두 0 확인 → 삭제 →
#       백엔드 기동으로 quorum 재선언 → 확인 → 워커 기동. 어느 단계든 실패하면 되돌리는 방법을 출력한다.
set -euo pipefail

cd "$(dirname "$0")/.."

QUEUE="backtest.jobs"
DEV=false
if [[ "${1:-}" == "--dev" ]]; then
    DEV=true
    COMPOSE="docker compose -f ../docker-compose.dev.yml --profile app"
else
    COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env.prod"
fi

rmq() { $COMPOSE exec -T rabbitmq rabbitmqctl "$@"; }
queue_field() {   # $1 = 컬럼 이름. 큐가 없으면 빈 문자열
    rmq list_queues name "$1" 2>/dev/null | awk -v q="$QUEUE" '$1==q{print $2}' || true
}

echo "[1/5] 현재 큐 상태"
TYPE="$(queue_field type)"
if [[ -z "$TYPE" ]]; then
    echo "큐 $QUEUE 가 없다. 전환할 것이 없다 — 백엔드를 띄우면 quorum으로 선언된다."
    exit 0
fi
if [[ "$TYPE" == "quorum" ]]; then
    echo "이미 quorum 큐다. 할 일이 없다."
    rmq list_queues name type arguments | grep "^$QUEUE" || true
    exit 0
fi
echo "type=$TYPE → 전환 대상"

echo "[2/5] 접수 중단"
if $DEV; then
    echo "개발 환경: 백엔드(bootRun 또는 compose)와 워커를 먼저 내려라. 내려진 상태라고 보고 진행한다."
else
    # 새 작업이 들어오지 않게 백엔드를, 남은 작업을 건드리지 않게 워커를 멈춘다.
    $COMPOSE stop backend worker
fi

echo "[3/5] 대기(ready)·미ACK(unacked) 메시지 확인 — 둘 다 0이어야 한다"
READY="$(queue_field messages_ready)"
UNACKED="$(queue_field messages_unacknowledged)"
echo "ready=$READY unacked=$UNACKED"
if [[ "$READY" != "0" || "$UNACKED" != "0" ]]; then
    echo "0이 아니다. 지금 지우면 접수된 작업을 잃는다."
    echo "  - 남은 작업을 끝내려면 워커만 올려 비운다(결과 이벤트는 backtest.events 큐에 쌓여 백엔드가 뒤에 처리한다)."
    if ! $DEV; then
        echo "      $COMPOSE up -d worker   # 비워지면 다시 이 스크립트를 실행"
        echo "되돌리기(전환 포기): $COMPOSE up -d backend worker"
        $COMPOSE up -d backend worker
    fi
    exit 1
fi

echo "[4/5] 큐 삭제"
rmq delete_queue "$QUEUE"

if $DEV; then
    echo "[5/5] 삭제 완료. 이제 백엔드를 띄우면 quorum으로 선언된다. 확인:"
    echo "  docker compose -f docker-compose.dev.yml exec rabbitmq rabbitmqctl list_queues name type arguments"
    exit 0
fi

echo "[5/5] 백엔드 기동 → quorum 재선언 확인"
$COMPOSE up -d backend
for _ in $(seq 1 30); do
    TYPE="$(queue_field type)"
    [[ "$TYPE" == "quorum" ]] && break
    sleep 2
done
if [[ "$TYPE" != "quorum" ]]; then
    echo "재선언 실패(type='${TYPE:-없음}'). 백엔드 로그를 본다: $COMPOSE logs --tail 100 backend"
    echo "되돌리기: 이전 버전 백엔드 이미지로 up -d 하면 classic 큐가 다시 선언된다. 워커는 올리지 않는다."
    exit 1
fi
rmq list_queues name type arguments | grep "^$QUEUE"
$COMPOSE up -d worker
echo "완료: $QUEUE 는 quorum, x-delivery-limit=1. 워커 기동."
