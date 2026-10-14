#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/opt/pebble/runtime
install -d -m 0700 "$root/backups"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target="$root/backups/pebble-$stamp.dump"
partial="$target.partial"
trap 'rm -f "$partial"' EXIT
docker exec -u postgres pebble-prod-postgres-1 pg_dump --format=custom --dbname=pebble > "$partial"
docker exec -i -u postgres pebble-prod-postgres-1 pg_restore --list < "$partial" > /dev/null
mv "$partial" "$target"
sha256sum "$target" > "$target.sha256"
echo "PostgreSQL backup completed: $target"
