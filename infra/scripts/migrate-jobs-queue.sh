#!/usr/bin/env bash
# backtest.jobs 큐를 classic → quorum(x-delivery-limit=1)으로 전환한다. 최초 1회.
#
# 왜 필요한가 (spec 004 R-06, 계약 README §2): 백엔드 v2(계약 schemaVersion 2)는 backtest.jobs를
# quorum으로 선언한다. classic 큐가 이미 있으면 인자가 달라 재선언이 PRECONDITION_FAILED로 실패하고,
# 큐는 classic으로 남아 x-delivery-limit(자동 재시도 1회)이 적용되지 않는다.
#
# 순서가 중요하다 (T002 MR 리뷰 1·2번):
#   1) 내리기 전에 v2 이미지를 빌드한다. 안 그러면 전환 뒤 `up -d`가 예전 v1 이미지를 띄워
#      classic 큐를 그대로 되살린다(deploy.sh가 다시 막아 무한 반복).
#   2) 작업 큐뿐 아니라 **결과 이벤트 큐(backtest.events)까지** v1 백엔드가 모두 처리한 뒤에 전환한다.
#      v2 백엔드는 v1 이벤트(schemaVersion 1)를 계약 오류로 거절해 DLQ로 보내므로, 남겨 두면 그
#      실행들의 결과가 DB에 저장되지 않는다. 그래서 드레인 동안 v1 백엔드를 내리지 않고,
#      신규 접수만 nginx를 멈춰 막는다.
#
# 사용 (레포가 배포할 버전으로 동기화된 상태에서 실행한다 — 빌드가 그 소스를 쓴다):
#   ./scripts/migrate-jobs-queue.sh          운영: docker-compose.prod.yml + .env.prod (infra/ 에서 실행)
#   ./scripts/migrate-jobs-queue.sh --dev    개발: 레포 루트 docker-compose.dev.yml
#
# 환경변수: DRAIN_TIMEOUT_SECONDS(기본 1800) — 드레인 대기 상한.
set -euo pipefail

cd "$(dirname "$0")/.."

JOBS_QUEUE="backtest.jobs"
EVENTS_QUEUE="backtest.events"
DRAIN_TIMEOUT_SECONDS="${DRAIN_TIMEOUT_SECONDS:-1800}"

DEV=false
if [[ "${1:-}" == "--dev" ]]; then
    DEV=true
    COMPOSE="docker compose -f ../docker-compose.dev.yml --profile app"
else
    COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env.prod"
fi

rmq() { $COMPOSE exec -T rabbitmq rabbitmqctl "$@"; }

q_field() {   # $1=큐 이름 $2=컬럼. 큐가 없으면 빈 문자열
    rmq list_queues name "$2" 2>/dev/null | awk -v q="$1" '$1==q{print $2}' || true
}

pending() {   # $1=큐 이름 → ready + unacked (큐가 없으면 0)
    local ready unacked
    ready="$(q_field "$1" messages_ready)"
    unacked="$(q_field "$1" messages_unacknowledged)"
    echo $(( ${ready:-0} + ${unacked:-0} ))
}

echo "[1/6] 현재 큐 상태"
TYPE="$(q_field "$JOBS_QUEUE" type)"
if [[ -z "$TYPE" ]]; then
    echo "큐 $JOBS_QUEUE 가 없다. 전환할 것이 없다 — 백엔드를 띄우면 quorum으로 선언된다."
    exit 0
fi
if [[ "$TYPE" == "quorum" ]]; then
    echo "이미 quorum 큐다. 할 일이 없다."
    rmq list_queues name type arguments | grep "^$JOBS_QUEUE" || true
    exit 0
fi
echo "type=$TYPE → 전환 대상"

if $DEV; then
    echo "[2/6] 개발 환경: 이미지 빌드를 건너뛴다(소스에서 직접 실행)."
else
    # 내리기 전에 빌드한다. 5단계의 `up -d backend`가 이 이미지를 쓴다.
    echo "[2/6] v2 백엔드·워커 이미지 빌드 (기존 서비스는 계속 실행)"
    $COMPOSE build backend worker
fi

echo "[3/6] 신규 접수 차단 — 기존 백엔드·워커는 남은 일을 끝내야 하므로 내리지 않는다"
if $DEV; then
    echo "개발 환경: 새 요청을 보내지 마라(POST /backtests). 백엔드·워커는 계속 띄워 둔다."
else
    $COMPOSE stop nginx   # 사용자 요청 차단. 서비스 점검 시간이다.
fi

echo "[4/6] 드레인 — $JOBS_QUEUE 와 $EVENTS_QUEUE 가 모두 0이 될 때까지 (최대 ${DRAIN_TIMEOUT_SECONDS}초)"
DEADLINE=$(( SECONDS + DRAIN_TIMEOUT_SECONDS ))
while :; do
    JOBS_LEFT="$(pending "$JOBS_QUEUE")"
    EVENTS_LEFT="$(pending "$EVENTS_QUEUE")"
    echo "  jobs=$JOBS_LEFT events=$EVENTS_LEFT"
    [[ "$JOBS_LEFT" == "0" && "$EVENTS_LEFT" == "0" ]] && break
    if (( SECONDS >= DEADLINE )); then
        echo "시간 안에 비지 않았다. 지금 지우면 접수된 작업과 저장되지 않은 결과를 잃는다."
        echo "  - 워커가 살아 있는지, 백엔드가 이벤트를 처리 중인지 확인한다:"
        echo "      $COMPOSE ps; $COMPOSE logs --tail 50 backend"
        echo "  - 더 기다리려면 DRAIN_TIMEOUT_SECONDS를 늘려 다시 실행한다."
        if ! $DEV; then
            echo "되돌리기(전환 포기): nginx를 다시 올린다."
            $COMPOSE up -d nginx
        fi
        exit 1
    fi
    sleep 10
done
echo "  두 큐가 비었다. v1 실행의 결과는 DB에 반영됐다."

echo "[5/6] 정지 → 큐 삭제 → v2 백엔드 기동"
$COMPOSE stop backend worker
rmq delete_queue "$JOBS_QUEUE"

if $DEV; then
    echo "[6/6] 삭제 완료. 백엔드(v2)를 띄우면 quorum으로 선언된다. 확인:"
    echo "  docker compose -f docker-compose.dev.yml exec rabbitmq rabbitmqctl list_queues name type arguments"
    exit 0
fi

$COMPOSE up -d backend
TYPE=""
for _ in $(seq 1 30); do
    TYPE="$(q_field "$JOBS_QUEUE" type)"
    [[ "$TYPE" == "quorum" ]] && break
    sleep 2
done
if [[ "$TYPE" != "quorum" ]]; then
    echo "재선언 실패(type='${TYPE:-없음}'). 백엔드 로그를 본다: $COMPOSE logs --tail 100 backend"
    echo "되돌리기: 이전 버전으로 체크아웃해 backend 이미지를 다시 빌드·기동하면 classic 큐가 다시"
    echo "          선언된다. 두 큐는 4단계에서 비운 상태이므로 잃은 메시지는 없다."
    exit 1
fi
rmq list_queues name type arguments | grep "^$JOBS_QUEUE"

echo "[6/6] v2 워커·nginx 기동"
$COMPOSE up -d worker nginx
echo "완료: $JOBS_QUEUE 는 quorum, x-delivery-limit=1. 백엔드·워커 모두 v2 이미지."
