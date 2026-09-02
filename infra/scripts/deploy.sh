#!/usr/bin/env bash
# 배포. Jenkins가 호출하지만 서버에서 수동으로 실행해도 된다.
#
# 주의: 이 스크립트는 반드시 호스트의 고정 경로($DEPLOY_DIR)에서 실행해야 한다.
# Jenkins가 docker.sock으로 도커를 부리면 bind mount 경로는 "호스트 기준"으로
# 해석된다. Jenkins 워크스페이스에서 실행하면 ./nginx/templates 같은 상대경로가
# 호스트에 없어서 빈 디렉토리가 마운트된다.
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=".env.prod"
COMPOSE="docker compose -f docker-compose.prod.yml --env-file $ENV_FILE"

echo "[1/4] 프론트엔드 빌드"
# 빌드 컨테이너는 docker.sock으로 호스트 데몬이 띄우므로 Jenkins의 메모리 상한이
# 적용되지 않는다. 명시하지 않으면 무제한이라 여기서 직접 건다.
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
