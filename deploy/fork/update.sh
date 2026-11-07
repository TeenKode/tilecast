#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
image="${1:?Usage: update.sh ghcr.io/teenkode/tilecast-server:dev-COMMIT}"
case "$image" in
  ghcr.io/teenkode/tilecast-server:dev-*|ghcr.io/teenkode/tilecast-server@sha256:*) ;;
  *) echo 'Use a verified image from the TeenKode fork.' >&2; exit 1 ;;
esac
export TILECAST_FORK_IMAGE="$image"
project="${COMPOSE_PROJECT_NAME:-tilecast-power-test}"
compose=(docker compose -p "$project" --env-file deploy/docker/.env -f deploy/docker/compose.yml -f deploy/fork/compose.yml)
backup="${TILECAST_BACKUP_ROOT:-/opt/tilecast-backups}/$project-$(date -u +%Y%m%dT%H%M%SZ)"
install -d -m 700 "$backup"
"${compose[@]}" exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup/database.dump"
test -s "$backup/database.dump"
cp deploy/docker/.env "$backup/server.env"
chmod 600 "$backup"/*
"${compose[@]}" pull server
"${compose[@]}" up -d --no-build --wait server
printf '%s\n' "$image" > deploy/fork/installed-image
echo "Installed $image; database backup: $backup/database.dump"
