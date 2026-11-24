#!/usr/bin/env bash
#
# init-db-external.sh — 도커 없이 "이미 떠 있는 Oracle"(로컬 설치 또는 원격 dev
# 서버)에 스키마+시드를 적용한다. docker compose 를 쓰지 않는다.
#
# 두 단계:
#   1. bootstrap-external.sql 을 SYSDBA 로 실행 — PSK_APP_OWNER 스키마 유저 +
#      PSK_APP_ADMIN/PSK_APP_RUNTIME role + GRANT 생성.
#      (VPD 제거됨 — CTX_PKG/APP_CTX/DBMS_RLS GRANT 는 더 이상 만들지 않는다.)
#   2. apply-schema.sh 를 PSK_APP_OWNER 로 실행 — 스키마 전체
#      (full-schema-part2-schema.sql)와 프로필 시드(scripts/seed/)를 적용.
#
# 앱은 스키마를 만들거나 바꾸지 않는다. 두 단계 모두 sqlplus 로만 한다.
#
# 필요한 것:
#   - 호스트에 sqlplus (Oracle Instant Client)
#   - 대상 Oracle 의 SYSDBA 접속 정보(단계 1) + 네트워크 도달 가능
#
# 사용법(환경변수로 대상 지정):
#   ADMIN_PW=강한비번 APP_OWNER_PW=강한비번 RUNTIME_PW=강한비번 \
#   ORA_HOST=db.example.com ORA_PORT=1521 ORA_SERVICE=XEPDB1 \
#   ORA_SYS_PW=oracle PROFILE=dev \
#   scripts/init-db-external.sh
#
#   PROFILE: dev(기본) | local | qa | none(시드 없음).  대상 DB 가 비어 있다고 가정.
#
#   [이미 우리 잔재가 있는 외부 SE DB 를 비우고 재적용하는 절차]
#     1) DBeaver 에서 PSK_APP_OWNER 로 접속해 scripts/reset-app-owner-external.sql 실행
#        (스크립트 상단 c_confirm 을 RESET 으로 바꿔야 동작. 테이블·데이터 삭제.
#         VPD 제거됨 — CTX_PKG 도 함께 정리되고 APP_CTX 는 DROP 권한 부재로 무해하게 잔존).
#     2) 아래처럼 SKIP_BOOTSTRAP=1 로 스키마·시드만 재적용:
#        APP_OWNER_PW=강한비번 \
#        SKIP_BOOTSTRAP=1 \
#        ORA_HOST=db.example.com ORA_PORT=1521 ORA_SERVICE=ORCLPDB1 PROFILE=qa \
#        scripts/init-db-external.sh
#     앱 격리는 앱 레벨(Hibernate @Filter). VPD 는 완전히 제거됐고(코드/설정/DB),
#     앱 런타임은 어떤 VPD 설정도 읽지 않는다.
#
# ⚠️ 파괴적이지 않다 — 스키마 적용은 빈 스키마가 전제라, 테이블이 이미 있으면 첫 CREATE 에서
#    멈춘다. bootstrap 산출물(PSK_APP_OWNER/role)은 남아 있어도 무방하다(SKIP_BOOTSTRAP 경로).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 계정 비밀번호는 환경변수 필수 — 하드코딩 제거(보안 감사 #2).
# APP_OWNER_PW: PSK_APP_OWNER 비번 — 항상 필요 (bootstrap 및 스키마 적용).
# ADMIN_PW·RUNTIME_PW: 런타임 계정 비번 — bootstrap 에서만 쓴다(SKIP_BOOTSTRAP=1 이면 불필요).
: "${APP_OWNER_PW:?APP_OWNER_PW 환경변수 필요 (강한 비번)}"

ORA_HOST="${ORA_HOST:-localhost}"
ORA_PORT="${ORA_PORT:-1521}"
ORA_SERVICE="${ORA_SERVICE:-XEPDB1}"
ORA_SYS_PW="${ORA_SYS_PW:-oracle}"
PROFILE="${PROFILE:-dev}"
# SKIP_BOOTSTRAP=1 이면 단계 1(sqlplus bootstrap)을 건너뛴다. 이미 부트스트랩된
# (예: DBeaver 로 bootstrap-external.sql 을 실행한) 외부 DB 에 스키마·시드만 적용할 때.
SKIP_BOOTSTRAP="${SKIP_BOOTSTRAP:-0}"

SYS_CONN="sys/${ORA_SYS_PW}@${ORA_HOST}:${ORA_PORT}/${ORA_SERVICE} as sysdba"

echo "==> 외부 Oracle 초기화"
echo "    대상   : ${ORA_HOST}:${ORA_PORT}/${ORA_SERVICE}"
echo "    profile: ${PROFILE}"
echo ""

# ---- 단계 1: 부트스트랩 (SKIP_BOOTSTRAP=1 이면 건너뜀) ----
if [ "${SKIP_BOOTSTRAP}" = "1" ]; then
  echo "==> [1/2] 부트스트랩 건너뜀 (SKIP_BOOTSTRAP=1) — 이미 부트스트랩된 DB 전제."
  echo "    (PSK_APP_OWNER/role 이 이미 있어야 합니다. 없으면 DBeaver 로"
  echo "     bootstrap-external.sql 을 먼저 실행하세요.)"
else
  if ! command -v sqlplus >/dev/null 2>&1; then
    echo "ERROR: sqlplus 가 PATH 에 없습니다. Oracle Instant Client(SQL*Plus)를 설치하거나," >&2
    echo "       DBeaver 등으로 단계 1 SQL 을 수동 실행한 뒤 SKIP_BOOTSTRAP=1 로 재실행하세요:" >&2
    echo "         (DBeaver, SYSDBA 또는 적절 권한) < ${SCRIPT_DIR}/bootstrap-external.sql" >&2
    exit 1
  fi
  : "${ADMIN_PW:?ADMIN_PW 환경변수 필요 (강한 비번)}"
  : "${RUNTIME_PW:?RUNTIME_PW 환경변수 필요 (강한 비번)}"
  echo "==> [1/2] 부트스트랩 (SYSDBA): PSK_APP_OWNER 유저 + role"
  # ⚠️ 비번에 "(큰따옴표)가 있으면 heredoc DEFINE 줄이 파괴됨. 비번에 " 미사용 권장.
  sqlplus -S "${SYS_CONN}" <<SQL
DEFINE app_owner_pw = "${APP_OWNER_PW}"
DEFINE runtime_pw   = "${RUNTIME_PW}"
DEFINE admin_pw     = "${ADMIN_PW}"
DEFINE ora_service  = ${ORA_SERVICE}
@${SCRIPT_DIR}/bootstrap-external-body.sql
SQL
  echo "    부트스트랩 완료."
fi
echo ""

echo "==> [2/2] 스키마 + ${PROFILE} 시드 적용 (PSK_APP_OWNER)"
ORA_HOST="${ORA_HOST}" ORA_PORT="${ORA_PORT}" ORA_SERVICE="${ORA_SERVICE}" \
APP_OWNER_PW="${APP_OWNER_PW}" PROFILE="${PROFILE}" \
  bash "${SCRIPT_DIR}/apply-schema.sh"

echo "    ✅ 완료. ${ORA_HOST}:${ORA_PORT}/${ORA_SERVICE} 에 ${PROFILE} 스키마+시드 적용됨."
