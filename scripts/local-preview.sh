#!/usr/bin/env bash
set -euo pipefail
mode="${1:-seed}"
case "$mode" in seed|clean) ;; *) echo '사용법: bash scripts/local-preview.sh [seed|clean]' >&2; exit 2;; esac
# 외부 Docker endpoint 및 운영 DB 지정 옵션을 받지 않는다.
case "${DOCKER_HOST:-unix://local}" in unix://*) ;; *) echo '로컬 Unix Docker만 지원합니다' >&2; exit 1;; esac
endpoint="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
case "$endpoint" in unix://*) ;; *) echo '로컬 Docker context만 지원합니다' >&2; exit 1;; esac
container=pebble-api-postgres-1
identity="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}/{{index .Config.Labels "com.docker.compose.service"}}/{{.Config.Image}}' "$container")"
if [[ "$identity" != 'pebble-api/postgres/postgres:17.11-alpine' ]]; then
  echo 'Pebble 로컬 Compose PostgreSQL 컨테이너가 아닙니다' >&2; exit 1
fi
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
{ printf 'BEGIN;\n'; cat "$script_dir/local-preview/guard.sql" "$script_dir/local-preview/$mode.sql"; printf 'COMMIT;\n'; } |
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U pebble -d pebble
