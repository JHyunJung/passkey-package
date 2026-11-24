#!/usr/bin/env bash
#
# apply-schema.sh — 비어 있는 PSK_APP_OWNER 스키마에 스키마 전체와 개발용 시드를 적용한다.
#
# 앱은 스키마를 만들거나 바꾸지 않는다(기동 시 Hibernate 가 검증만 한다). 스키마의 단일 기준은
# scripts/full-schema-part2-schema.sql 이고, 이 스크립트는 그 파일과 scripts/seed/ 의 시드를
# PSK_APP_OWNER 로 sqlplus 에 흘려 넣는다.
#
# 선행 조건: 롤·계정이 있어야 한다(run-bootstrap.sh 또는 bootstrap-external.sql).
#
# 사용법:
#   scripts/apply-schema.sh                     # 로컬 Docker(passkey-oracle), dev 시드
#   PROFILE=local scripts/apply-schema.sh       # local 시드
#   SEED_ONLY=1 scripts/apply-schema.sh         # 스키마는 건너뛰고 시드만 다시 적용(시드는 멱등)
#   ORA_HOST=db.example.com APP_OWNER_PW=... PROFILE=qa scripts/apply-schema.sh
#                                               # 외부 Oracle — 호스트의 sqlplus 사용
#
# PROFILE 별 시드:
#   dev   : operators.sql(alice) + dev-tenant.sql(dev-passkey 테넌트, bob)
#   local : operators.sql(alice) + local-tenant.sql(demo-rp 테넌트, rpId=localhost)
#   qa    : operators.sql(alice)
#   none  : 시드 없음(스키마만)
#
# ⚠️ 스키마 적용은 빈 스키마가 전제다. 이미 테이블이 있으면 첫 CREATE 에서 멈춘다.
#    다시 만들려면 scripts/reset-app-owner.sh(로컬) 또는 reset-app-owner-external.sql(외부)로 먼저 비운다.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILE="${PROFILE:-dev}"
SEED_ONLY="${SEED_ONLY:-0}"
ORA_SERVICE="${ORA_SERVICE:-XEPDB1}"
ORA_PORT="${ORA_PORT:-1521}"
APP_OWNER_PW="${APP_OWNER_PW:-app_owner_pw}"
ORACLE_CONTAINER="${ORACLE_CONTAINER:-passkey-oracle}"

case "${PROFILE}" in
  dev)   SEEDS="operators dev-tenant" ;;
  local) SEEDS="operators local-tenant" ;;
  qa)    SEEDS="operators" ;;
  none)  SEEDS="" ;;
  *) echo "알 수 없는 PROFILE: ${PROFILE} (가능: dev | local | qa | none)" >&2; exit 2 ;;
esac

# sqlplus 로 보낼 SQL 을 표준 출력으로 낸다.
emit_sql() {
  if [ "${SEED_ONLY}" != "1" ]; then
    # 스키마 파일 끝의 EXIT 는 뗀다 — 남겨 두면 뒤의 시드가 실행되지 않는다.
    sed -E '/^[Ee][Xx][Ii][Tt];?[[:space:]]*$/d' "${SCRIPT_DIR}/full-schema-part2-schema.sql"
  fi
  echo "WHENEVER OSERROR EXIT FAILURE"
  echo "WHENEVER SQLERROR EXIT FAILURE ROLLBACK"
  # 시드는 평범한 SQL 이다. & 를 치환 변수로 읽지 않게 하고, 문장 안의 빈 줄을 허용한다.
  echo "SET DEFINE OFF"
  echo "SET SQLBLANKLINES ON"
  for seed in ${SEEDS}; do
    echo "PROMPT == seed: ${seed}.sql"
    cat "${SCRIPT_DIR}/seed/${seed}.sql"
    echo
  done
  echo "EXIT;"
}

if [ -n "${ORA_HOST:-}" ]; then
  if ! command -v sqlplus >/dev/null 2>&1; then
    echo "ERROR: sqlplus 가 PATH 에 없습니다. Oracle Instant Client(SQL*Plus)를 설치하거나," >&2
    echo "       DBeaver 등으로 PSK_APP_OWNER 로 접속해 아래 파일을 순서대로 직접 실행하세요:" >&2
    echo "         ${SCRIPT_DIR}/full-schema-part2-schema.sql" >&2
    for seed in ${SEEDS}; do echo "         ${SCRIPT_DIR}/seed/${seed}.sql" >&2; done
    exit 1
  fi
  echo "    대상: ${ORA_HOST}:${ORA_PORT}/${ORA_SERVICE} (PSK_APP_OWNER, 호스트 sqlplus)"
  emit_sql | sqlplus -S -L "PSK_APP_OWNER/${APP_OWNER_PW}@${ORA_HOST}:${ORA_PORT}/${ORA_SERVICE}"
else
  echo "    대상: Docker ${ORACLE_CONTAINER} /${ORA_SERVICE} (PSK_APP_OWNER)"
  emit_sql | docker exec -i "${ORACLE_CONTAINER}" \
    sqlplus -S -L "PSK_APP_OWNER/${APP_OWNER_PW}@localhost:1521/${ORA_SERVICE}"
fi
