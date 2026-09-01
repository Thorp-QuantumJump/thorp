#!/usr/bin/env bash
# Let's Encrypt 인증서 최초 발급.
#
# 닭-달걀 문제를 푼다: nginx는 인증서가 없으면 뜨지 않고,
# certbot은 nginx가 떠 있어야 도메인 소유를 검증할 수 있다.
# 그래서 임시 자체서명 인증서로 nginx를 먼저 띄운 뒤 진짜 인증서로 교체한다.
#
#   ./scripts/init-letsencrypt.sh          # 실제 발급
#   STAGING=1 ./scripts/init-letsencrypt.sh # 연습 (횟수 제한 없음)
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=".env.prod"
COMPOSE_FILE="docker-compose.prod.yml"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "오류: $ENV_FILE 이 없다. .env.prod.example을 복사해 채운다." >&2
    exit 1
fi

set -a; source "$ENV_FILE"; set +a

if [[ -z "${DOMAIN:-}" || -z "${CERTBOT_EMAIL:-}" ]]; then
    echo "오류: $ENV_FILE 의 DOMAIN 과 CERTBOT_EMAIL 을 채워야 한다." >&2
    exit 1
fi

COMPOSE="docker compose -f $COMPOSE_FILE --env-file $ENV_FILE"
CONF_DIR="$PWD/certbot/conf"
WWW_DIR="$PWD/certbot/www"

STAGING_ARG=""
if [[ "${STAGING:-0}" == "1" ]]; then
    STAGING_ARG="--staging"
    echo "[i] STAGING 모드 — 브라우저에서 신뢰되지 않는 연습용 인증서를 받는다."
fi

echo "[1/5] 디렉토리 준비 ($DOMAIN)"
mkdir -p "$CONF_DIR/live/$DOMAIN" "$WWW_DIR"

echo "[2/5] nginx 기동용 임시 인증서 생성"
docker run --rm -v "$CONF_DIR:/etc/letsencrypt" --entrypoint sh certbot/certbot -c "
    openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
        -keyout '/etc/letsencrypt/live/$DOMAIN/privkey.pem' \
        -out    '/etc/letsencrypt/live/$DOMAIN/fullchain.pem' \
        -subj '/CN=localhost' 2>/dev/null"

echo "[3/5] nginx 기동 (80번 포트로 인증 요청을 받는다)"
$COMPOSE up -d nginx
sleep 5

echo "[4/5] 임시 인증서 삭제 후 실제 인증서 발급"
docker run --rm -v "$CONF_DIR:/etc/letsencrypt" --entrypoint sh certbot/certbot -c "
    rm -rf /etc/letsencrypt/live/$DOMAIN \
           /etc/letsencrypt/archive/$DOMAIN \
           /etc/letsencrypt/renewal/$DOMAIN.conf"

docker run --rm \
    -v "$CONF_DIR:/etc/letsencrypt" \
    -v "$WWW_DIR:/var/www/certbot" \
    certbot/certbot certonly --webroot -w /var/www/certbot \
    -d "$DOMAIN" \
    --email "$CERTBOT_EMAIL" \
    --agree-tos --no-eff-email --non-interactive \
    $STAGING_ARG

echo "[5/5] nginx 재적용"
$COMPOSE exec -T nginx nginx -s reload

echo
echo "완료. https://$DOMAIN 접속을 확인한다."
echo "자동 갱신 cron 등록을 잊지 말 것 (infra/README.md 9단계)."
