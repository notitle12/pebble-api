#!/usr/bin/env bash
set -euo pipefail
backup=${1:?Specify an existing PostgreSQL custom-format backup}
expected_version=${2:?Specify the expected successful migration count for this backup}
if [[ ! "$expected_version" =~ ^[1-9][0-9]*$ ]]; then
  echo 'Expected migration count must be a positive integer' >&2
  exit 1
fi
database="pebble_restore_verify_$(date -u +%Y%m%d%H%M%S)_${RANDOM}"
created=false
cleanup() {
  if [[ "$created" == true ]]; then
    docker exec -u postgres pebble-prod-postgres-1 dropdb "$database"
  fi
}
trap cleanup EXIT
docker exec -u postgres pebble-prod-postgres-1 createdb --owner=pebble "$database"
created=true
docker exec -i -u postgres pebble-prod-postgres-1 pg_restore --exit-on-error --dbname="$database" < "$backup"
versions=$(docker exec -u postgres pebble-prod-postgres-1 psql -At -d "$database" -c 'select count(*) from flyway_schema_history where success')
if [[ "$versions" != "$expected_version" ]]; then
  echo 'Restored migration history did not match current release' >&2
  exit 1
fi
echo "Isolated backup restore verified; migration history = $expected_version; temporary database will be removed"
