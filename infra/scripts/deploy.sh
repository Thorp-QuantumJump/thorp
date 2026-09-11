#!/usr/bin/env bash
# 배포. Jenkins가 호출하고 수동 실행도 가능하다.
#
# 주의: 반드시 호스트의 고정 경로($DEPLOY_DIR)에서 실행한다. Jenkins 워크스페이스에서
# 실행하면 상대경로 bind mount가 호스트 기준으로 해석되어 빈 디렉토리가 마운트된다.
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=".env.prod"
COMPOSE="docker compose -f docker-compose.prod.yml --env-file $ENV_FILE"

echo "[0/4] 작업 큐 타입 확인 (spec 004 R-06)"
# 계약 v2 백엔드는 backtest.jobs를 quorum으로 선언한다. classic 큐가 남아 있으면 재선언이
# PRECONDITION_FAILED로 실패하고 delivery-limit이 적용되지 않으므로, 먼저 1회 전환해야 한다.
if [[ -n "$($COMPOSE ps -q --status running rabbitmq 2>/dev/null)" ]]; then
    QUEUE_TYPE="$($COMPOSE exec -T rabbitmq rabbitmqctl list_queues name type 2>/dev/null \
        | awk '$1=="backtest.jobs"{print $2}' || true)"
    if [[ "$QUEUE_TYPE" == "classic" ]]; then
        echo "backtest.jobs 가 classic 큐다. 배포를 멈춘다. 먼저 1회 전환:"
        echo "    ./scripts/migrate-jobs-queue.sh     (docs/guides/backtest-queue-migration.md)"
        exit 1
    fi
fi

echo "[1/4] 프론트엔드 빌드"
# 호스트 데몬이 띄우므로 Jenkins의 메모리 상한이 적용되지 않는다. 여기서 직접 건다.
docker run --rm --memory=2g --cpus=2 \
    -v "$PWD/../frontend:/app" \
    -w /app \
    node:20-alpine sh -c "npm ci && npm run build"

echo "[2/4] 정적 파일 교체"
rm -rf nginx/html/*
cp -r ../frontend/dist/. nginx/html/

echo "[3/4] 백엔드/워커 이미지 빌드 및 기동"
$COMPOSE build backend worker
$COMPOSE up -d

echo "[4/4] nginx 재적용 및 정리"
# bind mount된 템플릿 변경은 컨테이너 재시작 없이 자동 반영되지 않는다.
# 공식 nginx entrypoint 스크립트로 설정을 다시 생성한 뒤 유효한 경우에만 reload한다.
$COMPOSE exec -T nginx /docker-entrypoint.d/20-envsubst-on-templates.sh
$COMPOSE exec -T nginx nginx -t
$COMPOSE exec -T nginx nginx -s reload
docker image prune -f

echo
$COMPOSE ps
