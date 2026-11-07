#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
image="${1:-}"
if [ -z "$image" ]; then
  image="$(python3 deploy/fork/latest-image.py)"
fi
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
python3 - "$image" <<'PY'
import pathlib, sys
path = pathlib.Path('deploy/docker/.env')
lines = [line for line in path.read_text().splitlines() if not line.startswith('TILECAST_FORK_IMAGE=')]
path.write_text('\n'.join(lines + ['TILECAST_FORK_IMAGE=' + sys.argv[1]]) + '\n')
PY
printf '%s\n' "$image" > deploy/fork/installed-image
echo "Installed $image; database backup: $backup/database.dump"
