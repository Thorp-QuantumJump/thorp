#!/usr/bin/env bash
# Let's Encrypt 인증서 최초 발급.
#
# nginx는 인증서가 없으면 뜨지 않고 certbot은 nginx가 떠야 검증할 수 있다.
# 이 닭-달걀 문제를 임시 자체서명 인증서로 먼저 띄운 뒤 교체하는 방식으로 푼다.
#
#   STAGING=1 ./scripts/init-letsencrypt.sh   # 연습 (발급 제한 회피)
#   ./scripts/init-letsencrypt.sh             # 실제 발급
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=".env.prod"
COMPOSE_FILE="docker-compose.prod.yml"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "오류: $ENV_FILE 이 없다. .env.prod.example을 복사해 채운다." >&2
    exit 1
fi

# 파일 전체를 source 하지 않는다. bash -x 로 실행하거나 CI에서 돌릴 때
# 비밀번호까지 추적 출력에 실린다. 필요한 값만 뽑아 쓴다.
read_env() { sed -n "s/^$1=//p" "$ENV_FILE" | head -1; }
DOMAIN=$(read_env DOMAIN)
CERTBOT_EMAIL=$(read_env CERTBOT_EMAIL)

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

make_dummy_cert() {
    mkdir -p "$CONF_DIR/live/$DOMAIN"
    docker run --rm -v "$CONF_DIR:/etc/letsencrypt" --entrypoint sh certbot/certbot -c "
        openssl req -x509 -nodes -newkey rsa:2048 -days 30 \
            -keyout '/etc/letsencrypt/live/$DOMAIN/privkey.pem' \
            -out    '/etc/letsencrypt/live/$DOMAIN/fullchain.pem' \
            -subj '/CN=$DOMAIN' 2>/dev/null"
}

# 4단계는 임시 인증서를 지운 뒤 실제 발급을 요청한다. 그 사이에 실패하면
# 인증서 디렉토리가 빈 채로 남아 다음 재시작 때 nginx가 아예 뜨지 못한다.
# 실패로 끝나면 임시 인증서를 되살려 웹서버만은 살아 있게 한다.
on_exit() {
    local code=$?
    [[ $code -eq 0 ]] && exit 0
    echo >&2
    echo "오류로 중단되었다 (exit $code)." >&2
    if [[ ! -s "$CONF_DIR/live/$DOMAIN/fullchain.pem" ]]; then
        echo "인증서가 비어 있어 임시 인증서를 복구한다. nginx는 뜨지만 브라우저 경고가 난다." >&2
        make_dummy_cert || echo "임시 인증서 복구 실패. 수동 확인이 필요하다." >&2
    fi
    exit $code
}
trap on_exit EXIT

echo "[1/5] 디렉토리 준비 ($DOMAIN)"
mkdir -p "$CONF_DIR/live/$DOMAIN" "$WWW_DIR"

echo "[2/5] nginx 기동용 임시 인증서 생성"
make_dummy_cert

echo "[3/5] nginx 기동 (80번 포트로 인증 요청을 받는다)"
# nginx 는 backend 에 의존하므로 새 서버처럼 backend 이미지가 없으면 compose 가 여기서 만든다. 운영 이미지는
# 미리 만든 JAR를 넣기만 하므로(docker-compose.prod.yml 의 target: prebuilt) 그때는 JAR부터 만든다.
if ! docker image inspect thorp-backend:latest >/dev/null 2>&1; then
    ./scripts/build-backend-jar.sh
fi
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
echo "자동 갱신 cron(renew-cert.sh) 등록을 잊지 말 것."
