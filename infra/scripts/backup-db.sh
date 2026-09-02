#!/usr/bin/env bash
# PostgreSQL 논리 백업. cron으로 매일 돌린다.
set -euo pipefail

cd "$(dirname "$0")/.."
set -a; source .env.prod; set +a

BACKUP_DIR="${DATA_DIR:-/data}/backup"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$BACKUP_DIR"

docker exec qj-postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    | gzip > "$BACKUP_DIR/${POSTGRES_DB}-${STAMP}.sql.gz"

find "$BACKUP_DIR" -name "${POSTGRES_DB}-*.sql.gz" -mtime "+${RETENTION_DAYS}" -delete

echo "백업 완료: $BACKUP_DIR/${POSTGRES_DB}-${STAMP}.sql.gz"
