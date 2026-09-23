#!/usr/bin/env bash
#
# 마이그레이션 버전 검사. CI가 병합 전에 돌린다.
#
# 두 가지를 본다.
#   1) 버전 중복  - 서로 다른 작업이 같은 번호를 쓰면 병합 결과가 기동하지 못한다.
#   2) 버전 역전  - 기준 브랜치에 이미 더 높은 버전이 있는데 낮은 번호를 새로 추가하면,
#                   그 버전은 운영에서 "이미 지나간 번호"가 되어 기동이 막힌다(out-of-order).
#
# 빈 DB에 전체 마이그레이션을 적용해 보는 테스트로는 2)를 잡지 못한다. 빈 DB에서는 번호 순서대로
# 전부 적용되기 때문이다. 역전은 "이미 높은 번호가 적용된 DB"에서만 드러나므로 여기서 따로 본다.
#
# 사용:
#   infra/scripts/check-migration-version.sh [기준 ref]      # 기본값 origin/dev
#
# constitution "스키마 변경 규칙": 병합해도 되는지는 사람의 확인이 아니라 자동 검증으로 판정한다.

set -euo pipefail

BASE_REF="${1:-origin/dev}"
MIGRATION_DIR="backend/src/main/resources/db/migration"

fail() {
    echo "[실패] $*" >&2
    exit 1
}

# 파일명에서 버전만 뽑는다. 규칙에 맞지 않는 이름은 빈 문자열이 되어 아래에서 걸린다.
version_of() {
    echo "$1" | sed -nE 's|^.*/?V([0-9]+)__[a-z0-9_]+\.sql$|\1|p'
}

# 10진수로 강제한다. 0으로 시작하는 번호를 bash가 8진수로 읽지 않게 한다.
as_number() {
    echo "$((10#$1))"
}

if ! git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
    fail "기준 ref '$BASE_REF' 를 찾지 못했다. 먼저 git fetch 하거나 기준 ref를 인자로 넘긴다."
fi

echo "기준: $BASE_REF"

# ---------------------------------------------------------------------------
# 1) 현재 트리의 버전 중복
# ---------------------------------------------------------------------------
current_files="$(git ls-files "$MIGRATION_DIR" | grep '\.sql$' || true)"
if [ -z "$current_files" ]; then
    fail "$MIGRATION_DIR 에서 마이그레이션을 찾지 못했다. 경로가 바뀌었는지 확인한다."
fi

current_versions=""
while IFS= read -r file; do
    [ -z "$file" ] && continue
    version="$(version_of "$file")"
    if [ -z "$version" ]; then
        fail "파일명이 규칙에 맞지 않는다: $file (규칙: V<숫자 버전>__<소문자·숫자·밑줄 설명>.sql)"
    fi
    current_versions="${current_versions}${version}"$'\n'
done <<< "$current_files"

duplicated="$(echo "$current_versions" | grep -v '^$' | sort | uniq -d || true)"
if [ -n "$duplicated" ]; then
    echo "[실패] 같은 버전을 가진 마이그레이션이 둘 이상이다:" >&2
    while IFS= read -r version; do
        [ -z "$version" ] && continue
        echo "  버전 $version:" >&2
        echo "$current_files" | grep -E "/V${version}__" | sed 's/^/    /' >&2
    done <<< "$duplicated"
    echo "아직 운영에 적용되지 않은 쪽의 버전을 올려 해소한다." >&2
    exit 1
fi
echo "  버전 중복 없음 ($(echo "$current_versions" | grep -cv '^$')개 파일)"

# ---------------------------------------------------------------------------
# 2) 기준 브랜치 대비 버전 역전
# ---------------------------------------------------------------------------
base_max=0
base_files="$(git ls-tree -r --name-only "$BASE_REF" -- "$MIGRATION_DIR" | grep '\.sql$' || true)"
while IFS= read -r file; do
    [ -z "$file" ] && continue
    version="$(version_of "$file")"
    [ -z "$version" ] && continue
    number="$(as_number "$version")"
    if [ "$number" -gt "$base_max" ]; then
        base_max="$number"
    fi
done <<< "$base_files"

echo "  기준 브랜치의 최대 버전: $base_max"

# 기준과 갈라진 지점 이후로 내 쪽에서 새로 추가한 파일만 본다.
added_files="$(git diff --name-only --diff-filter=A "$BASE_REF...HEAD" -- "$MIGRATION_DIR" | grep '\.sql$' || true)"

if [ -z "$added_files" ]; then
    echo "  새로 추가된 마이그레이션 없음"
    echo "[통과] 마이그레이션 버전 검사"
    exit 0
fi

violations=""
while IFS= read -r file; do
    [ -z "$file" ] && continue
    version="$(version_of "$file")"
    number="$(as_number "$version")"
    if [ "$number" -le "$base_max" ]; then
        violations="${violations}  $file (버전 $version ≤ 기준 최대 $base_max)"$'\n'
    else
        echo "  추가됨: $file (버전 $version)"
    fi
done <<< "$added_files"

if [ -n "$violations" ]; then
    echo "[실패] 기준 브랜치보다 낮거나 같은 버전을 새로 추가했다:" >&2
    printf '%s' "$violations" >&2
    echo "기준 브랜치가 먼저 배포되면 이 버전은 운영에서 '이미 지나간 번호'가 되어 기동이 막힌다." >&2
    echo "아직 운영에 적용되지 않았으므로 버전을 기준 최대값보다 크게 올린다." >&2
    exit 1
fi

echo "[통과] 마이그레이션 버전 검사"
