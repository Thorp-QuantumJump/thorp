#!/usr/bin/env bash
# 인증서 갱신. cron으로 주 1회 실행한다.
# 만료 30일 전부터 실제 갱신이 일어나며, 갱신할 게 없으면 아무것도 하지 않고 종료한다.
set -euo pipefail

cd "$(dirname "$0")/.."

docker run --rm \
    -v "$PWD/certbot/conf:/etc/letsencrypt" \
    -v "$PWD/certbot/www:/var/www/certbot" \
    certbot/certbot renew --webroot -w /var/www/certbot --quiet

# 갱신 여부와 무관하게 reload — 새 인증서를 nginx가 다시 읽게 한다
docker exec qj-nginx nginx -s reload
