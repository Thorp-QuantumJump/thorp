#!/usr/bin/env bash
# PostgreSQL 논리 백업. cron으로 매일 새벽에 돌린다.
#
# 덤프는 $DATA_DIR/backup(호스트 파일시스템)에 쌓인다. pg_dump만 컨테이너 안에서 돌고
# gzip과 리다이렉션은 호스트에서 실행되므로, 컨테이너를 내리거나 지워도 파일은 남는다.
#
# ⚠️ 다만 현재는 DB 원본(named volume)과 이 백업이 **같은 EBS 볼륨**에 있다.
#    볼륨이 통째로 사라지면(인스턴스 종료·초기화) 둘 다 함께 없어진다.
#    반드시 주기적으로 서버 밖(로컬 PC / 서버2 / S3)으로 회수할 것.
set -euo pipefail

cd "$(dirname "$0")/.."
set -a; source .env.prod; set +a

BACKUP_DIR="${DATA_DIR:-/data}/backup"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$BACKUP_DIR"

docker exec qj-postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    | gzip > "$BACKUP_DIR/${POSTGRES_DB}-${STAMP}.sql.gz"

# 오래된 백업 정리
find "$BACKUP_DIR" -name "${POSTGRES_DB}-*.sql.gz" -mtime "+${RETENTION_DAYS}" -delete

echo "백업 완료: $BACKUP_DIR/${POSTGRES_DB}-${STAMP}.sql.gz"
