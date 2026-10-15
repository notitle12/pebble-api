#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/opt/pebble/runtime
retention_days=${PEBBLE_BACKUP_RETENTION_DAYS:-7}
if [[ ! "$retention_days" =~ ^[0-9]{1,3}$ ]]; then
  echo "PEBBLE_BACKUP_RETENTION_DAYS must be an integer from 1 to 365" >&2
  exit 2
fi
retention_days=$((10#$retention_days))
if (( retention_days < 1 || retention_days > 365 )); then
  echo "PEBBLE_BACKUP_RETENTION_DAYS must be an integer from 1 to 365" >&2
  exit 2
fi
install -d -m 0700 "$root/backups"
exec 9>"$root/backups/.backup.lock"
if ! flock -n 9; then
  echo "Another PostgreSQL backup is already running" >&2
  exit 1
fi
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target="$root/backups/pebble-$stamp.dump"
partial="$target.partial"
trap 'rm -f "$partial"' EXIT
docker exec -u postgres pebble-prod-postgres-1 pg_dump --format=custom --dbname=pebble > "$partial"
docker exec -i -u postgres pebble-prod-postgres-1 pg_restore --list < "$partial" > /dev/null
mv "$partial" "$target"
(cd "$root/backups" && sha256sum "pebble-$stamp.dump" > "pebble-$stamp.dump.sha256")
(cd "$root/backups" && sha256sum --check --status "pebble-$stamp.dump.sha256")
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
python3 "$script_dir/prune-backups.py" --directory "$root/backups" --retention-days "$retention_days"
echo "PostgreSQL backup completed: $target"
