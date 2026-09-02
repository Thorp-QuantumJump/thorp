#!/usr/bin/env bash
# 배포. Jenkins가 호출하고 수동 실행도 가능하다.
#
# 주의: 반드시 호스트의 고정 경로($DEPLOY_DIR)에서 실행한다. Jenkins 워크스페이스에서
# 실행하면 상대경로 bind mount가 호스트 기준으로 해석되어 빈 디렉토리가 마운트된다.
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=".env.prod"
COMPOSE="docker compose -f docker-compose.prod.yml --env-file $ENV_FILE"

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
$COMPOSE exec -T nginx nginx -s reload
docker image prune -f

echo
$COMPOSE ps
