#!/usr/bin/env bash
#
# init-db-external.sh — 도커 없이 "이미 떠 있는 Oracle"(로컬 설치 또는 원격 dev
# 서버)에 스키마+시드를 적용한다. docker compose 를 쓰지 않는다.
#
# 두 단계:
#   1. bootstrap-external.sql 을 SYSDBA 로 실행 — APP_OWNER 스키마 유저 +
#      APP_ADMIN/APP_RUNTIME role + VPD CTX_PKG + GRANT 생성.
#   2. admin-app 을 그 Oracle 의 JDBC URL 로 부팅 — Flyway 가 V1~ 마이그레이션
#      (테이블/VPD 정책/시퀀스) + 프로필 시드(R__) 를 적용. 마이그레이션이
#      끝나면 종료.
#
# 즉 "최종 스키마+시드"를 Flyway 가 직접 만들어 넣는다. Flyway 가 만드는 건
# 깨끗한 최종 스키마이고, flyway_schema_history 테이블이 함께 생기지만(무해,
# 오히려 이후 마이그레이션을 이어갈 때 안전) 운영상 신경 쓸 필요 없다.
#
# 필요한 것:
#   - 호스트에 sqlplus (Oracle Instant Client) — 단계 1 용
#   - JDK 17 + 이 repo (gradle) — 단계 2 용
#   - 대상 Oracle 의 SYSDBA 접속 정보(단계 1) + 네트워크 도달 가능
#
# 사용법(환경변수로 대상 지정):
#   ADMIN_PW=강한비번 APP_OWNER_PW=강한비번 RUNTIME_PW=강한비번 \
#   ORA_HOST=db.example.com ORA_PORT=1521 ORA_SERVICE=XEPDB1 \
#   ORA_SYS_PW=oracle PROFILE=dev \
#   scripts/init-db-external.sh
#
#   PROFILE: dev(기본) | local | qa.  대상 DB 가 비어 있다고 가정.
#
#   [이미 우리 잔재가 있는 외부 SE DB 를 비우고 재적용하는 절차]
#     1) DBeaver 에서 APP_OWNER 로 접속해 scripts/reset-app-owner-external.sql 실행
#        (스크립트 상단 c_confirm 을 RESET 으로 바꿔야 동작. 테이블·데이터 삭제,
#         CTX_PKG/APP_CTX 보존).
#     2) 아래처럼 SKIP_BOOTSTRAP=1 로 Flyway 만 재적용:
#        ADMIN_PW=강한비번 \
#        SKIP_BOOTSTRAP=1 PASSKEY_VPD_ENABLED=false \
#        ORA_HOST=db.example.com ORA_PORT=1521 ORA_SERVICE=ORCLPDB1 PROFILE=qa \
#        scripts/init-db-external.sh
#     SE(Standard Edition) 는 VPD 미지원 — PASSKEY_VPD_ENABLED=false 로 둔다.
#
# ⚠️ 멱등하지만 파괴적이지 않다 — Flyway 가 관리하는 테이블/시퀀스/정책/마이그레이션
#    이력(flyway_schema_history)은 비어 있어야 한다. bootstrap 산출물(APP_OWNER/role/
#    CTX_PKG/APP_CTX)은 남아 있어도 무방하다(SKIP_BOOTSTRAP 재적용 경로 전제).
#    빈 스키마든, reset-app-owner-external.sql 로 Flyway 객체만 비운 스키마든 적용 가능.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# 계정 비밀번호는 환경변수 필수 — 하드코딩 제거(보안 감사 #2).
# ADMIN_PW: APP_ADMIN_USER 비번 — 항상 필요 (runtime datasource).
# APP_OWNER_PW: Flyway datasource 비번 — 항상 필요 (bootstrap 및 Flyway).
: "${ADMIN_PW:?ADMIN_PW 환경변수 필요 (강한 비번)}"
: "${APP_OWNER_PW:?APP_OWNER_PW 환경변수 필요 (강한 비번)}"

ORA_HOST="${ORA_HOST:-localhost}"
ORA_PORT="${ORA_PORT:-1521}"
ORA_SERVICE="${ORA_SERVICE:-XEPDB1}"
ORA_SYS_PW="${ORA_SYS_PW:-oracle}"
PROFILE="${PROFILE:-dev}"
# SKIP_BOOTSTRAP=1 이면 단계 1(sqlplus bootstrap)을 건너뛴다. 이미 부트스트랩된
# (예: DBeaver 로 bootstrap-external.sql 을 실행한) 외부 DB 에 Flyway 만 재적용할 때.
SKIP_BOOTSTRAP="${SKIP_BOOTSTRAP:-0}"
# SE(Standard Edition) 는 VPD 미지원 — false 로 두면 app-level @Filter 격리.
PASSKEY_VPD_ENABLED="${PASSKEY_VPD_ENABLED:-false}"

JDBC_URL="jdbc:oracle:thin:@${ORA_HOST}:${ORA_PORT}/${ORA_SERVICE}"
SYS_CONN="sys/${ORA_SYS_PW}@${ORA_HOST}:${ORA_PORT}/${ORA_SERVICE} as sysdba"

echo "==> 외부 Oracle 초기화"
echo "    대상   : ${ORA_HOST}:${ORA_PORT}/${ORA_SERVICE}"
echo "    profile: ${PROFILE}"
echo "    JDBC   : ${JDBC_URL}"
echo ""

# ---- 단계 1: 부트스트랩 (SKIP_BOOTSTRAP=1 이면 건너뜀) ----
if [ "${SKIP_BOOTSTRAP}" = "1" ]; then
  echo "==> [1/2] 부트스트랩 건너뜀 (SKIP_BOOTSTRAP=1) — 이미 부트스트랩된 DB 전제."
  echo "    (APP_OWNER/role/CTX_PKG 가 이미 있어야 합니다. 없으면 DBeaver 로"
  echo "     bootstrap-external.sql 을 먼저 실행하세요.)"
else
  if ! command -v sqlplus >/dev/null 2>&1; then
    echo "ERROR: sqlplus 가 PATH 에 없습니다. Oracle Instant Client(SQL*Plus)를 설치하거나," >&2
    echo "       DBeaver 등으로 단계 1 SQL 을 수동 실행한 뒤 SKIP_BOOTSTRAP=1 로 재실행하세요:" >&2
    echo "         (DBeaver, SYSDBA 또는 적절 권한) < ${SCRIPT_DIR}/bootstrap-external.sql" >&2
    exit 1
  fi
  # RUNTIME_PW: bootstrap 전용 — SKIP_BOOTSTRAP=1 이면 불필요.
  : "${RUNTIME_PW:?RUNTIME_PW 환경변수 필요 (강한 비번)}"
  echo "==> [1/2] 부트스트랩 (SYSDBA): APP_OWNER 유저 + role + CTX_PKG"
  # ⚠️ 비번에 "(큰따옴표)가 있으면 heredoc DEFINE 줄이 파괴됨. 비번에 " 미사용 권장.
  sqlplus -S "${SYS_CONN}" <<SQL
DEFINE app_owner_pw = "${APP_OWNER_PW}"
DEFINE runtime_pw   = "${RUNTIME_PW}"
DEFINE admin_pw     = "${ADMIN_PW}"
@${SCRIPT_DIR}/bootstrap-external-body.sql
SQL
  echo "    부트스트랩 완료."
fi
echo ""

echo "==> [2/2] Flyway 마이그레이션 + ${PROFILE} 시드 (admin-app 부팅 후 자동 종료)"
# admin-app 을 외부 Oracle 을 가리키도록 환경변수 주입. qa/prod yml 이
# SPRING_DATASOURCE_* / PASSKEY_KEY_ENVELOPE_MASTER_KEY 를 환경변수로 받는다.
# dev/local 프로필을 쓰되 datasource 만 override 한다(시드는 프로필이 결정).
LOG="$(mktemp -t init-db-external.XXXXXX.log)"
cd "${REPO_ROOT}"
SPRING_DATASOURCE_URL="${JDBC_URL}" \
SPRING_DATASOURCE_USERNAME='APP_ADMIN_USER' \
SPRING_DATASOURCE_PASSWORD="${ADMIN_PW}" \
SPRING_FLYWAY_USER='APP_OWNER' \
SPRING_FLYWAY_PASSWORD="${APP_OWNER_PW}" \
SPRING_DATA_REDIS_HOST="${REDIS_HOST:-localhost}" \
PASSKEY_KEY_ENVELOPE_MASTER_KEY="${PASSKEY_KEY_ENVELOPE_MASTER_KEY:-jDKp21WXeDAwinZI91Hf+8L2zv4xlIQI15YPLhttyYM=}" \
PASSKEY_VPD_ENABLED="${PASSKEY_VPD_ENABLED}" \
./gradlew :admin-app:bootRun \
  --args="--spring.profiles.active=${PROFILE} --spring.datasource.url=${JDBC_URL}" \
  > "${LOG}" 2>&1 &
GRADLE_PID=$!

ok=false
for _ in $(seq 1 80); do
  if grep -q "Started AdminApplication" "${LOG}" 2>/dev/null; then ok=true; break; fi
  if grep -qE "APPLICATION FAILED TO START|BUILD FAILED|Migration.*failed|ORA-[0-9]" "${LOG}" 2>/dev/null; then break; fi
  sleep 3
done

kill "${GRADLE_PID}" 2>/dev/null || true
pkill -f "admin-app.*bootRun" 2>/dev/null || true

if [ "${ok}" = "true" ]; then
  echo "    ✅ 완료. ${ORA_HOST}:${ORA_PORT}/${ORA_SERVICE} 에 ${PROFILE} 스키마+시드 적용됨."
  echo "    로그: ${LOG}"
else
  echo "    ❌ 마이그레이션/부팅 실패. 로그: ${LOG}" >&2
  tail -30 "${LOG}" >&2 || true
  exit 1
fi
