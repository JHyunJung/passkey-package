#!/usr/bin/env bash
set -euo pipefail
# Resolve sibling SQL file relative to this script, so the runner works
# from any working directory (not just repo root).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
docker exec -i passkey-oracle \
  sqlplus -S sys/oracle@localhost:1521/XEPDB1 as sysdba \
  < "${SCRIPT_DIR}/bootstrap-vpd.sql"
