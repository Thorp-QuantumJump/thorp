#!/usr/bin/env bash
# PostgreSQL 논리 백업. cron으로 매일 돌린다.
set -euo pipefail

cd "$(dirname "$0")/.."

# 파일 전체를 source 하지 않는다. 비밀번호가 이 셸 환경에 들어올 이유가 없다.
read_env() { sed -n "s/^$1=//p" .env.prod | head -1; }
POSTGRES_USER=$(read_env POSTGRES_USER)
POSTGRES_DB=$(read_env POSTGRES_DB)
DATA_DIR=$(read_env DATA_DIR)

BACKUP_DIR="${DATA_DIR:-/data}/backup"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/${POSTGRES_DB}-${STAMP}.sql.gz"
TMP="$BACKUP_DIR/.${POSTGRES_DB}-${STAMP}.sql.gz.part"

# 덤프에는 DB 전체 내용이 들어간다. 다른 계정이 읽지 못하게 한다.
umask 077
mkdir -p "$BACKUP_DIR"
trap 'rm -f "$TMP"' EXIT

# 임시 파일에 먼저 쓴다. 실패한 덤프가 정상 백업처럼 남는 것을 막는다.
docker exec thorp-postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" | gzip > "$TMP"

# pipefail이 pg_dump 실패를 잡지만 그것만으로는 부족하다.
# 압축 무결성과 내용 유무를 확인한 뒤에만 최종 이름을 부여한다.
gzip -t "$TMP"
[ -s "$TMP" ] || { echo "덤프가 비어 있다. 백업을 남기지 않는다." >&2; exit 1; }
mv "$TMP" "$OUT"

find "$BACKUP_DIR" -name "${POSTGRES_DB}-*.sql.gz" -mtime "+${RETENTION_DAYS}" -delete

echo "백업 완료: $OUT ($(du -h "$OUT" | cut -f1))"
