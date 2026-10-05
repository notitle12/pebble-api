#!/usr/bin/env bash
set -euo pipefail
# 초기화된 볼륨에서는 공식 entrypoint가 이 스크립트를 재실행하지 않는다.
password=$(cat /run/secrets/app-password)
if [[ ! "$password" =~ ^[0-9a-f]{64}$ ]]; then
  echo 'Invalid generated application password' >&2
  exit 1
fi
psql -v ON_ERROR_STOP=1 --username postgres --dbname postgres <<SQL
CREATE ROLE pebble LOGIN PASSWORD '$password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE DATABASE pebble OWNER pebble;
SQL
