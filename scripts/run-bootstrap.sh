#!/usr/bin/env bash
set -euo pipefail
# Resolve sibling SQL file relative to this script, so the runner works
# from any working directory (not just repo root).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORA_SERVICE="${ORA_SERVICE:-XEPDB1}"
# Prepend DEFINE so bootstrap-schema.sql 내의 &ora_service 가 채워진다.
# 셸이 DEFINE 을 override 하므로 SQL 파일 자체의 안전 기본값보다 우선한다.
{ echo "DEFINE ora_service = ${ORA_SERVICE}"; cat "${SCRIPT_DIR}/bootstrap-schema.sql"; } | \
  docker exec -i passkey-oracle \
    sqlplus -S sys/oracle@localhost:1521/${ORA_SERVICE} as sysdba
