#!/usr/bin/env bash
#
# reset-app-owner.sh — PSK_APP_OWNER 스키마만 완전 초기화한다(컨테이너/볼륨은 유지).
#
# init-dev-db.sh 와의 차이:
#   init-dev-db.sh  : docker compose down -v 로 Oracle 볼륨까지 날리고 재부트스트랩(분 단위).
#   reset-app-owner : 컨테이너는 그대로 두고 PSK_APP_OWNER 스키마 객체만 DROP(초 단위).
#                     "내가 원할 때만" 빠르게 데이터+스키마를 초기 상태로 되돌린다.
#
# 절차:
#   1. (가드) 프로필이 dev/local 인지 확인 — qa/prod 면 거부.
#   2. (가드) 'RESET' 을 직접 타이핑해야 진행 (--yes 로 우회).
#   3. reset-app-owner.sql  : SYS 세션에서 PSK_APP_OWNER 객체 전부 DROP(테이블·시퀀스·
#                             뷰·패키지·트리거 + 과거 VPD 잔재(정책·CTX_PKG·APP_CTX) 청소).
#   4. run-bootstrap.sh     : role/스키마 재생성(VPD 제거됨 — CTX_PKG/APP_CTX/VPD GRANT 안 만듦).
#   5. apply-schema.sh      : 스키마 전체 + ${PROFILE} 시드 적용.
#
# 결과: 컨테이너 재기동 없이, ${PROFILE} 시드가 들어간 깨끗한 PSK_APP_OWNER 스키마.
#
# ⚠️ 파괴적: PSK_APP_OWNER 의 모든 데이터(테넌트·계정·패스키·인증기록 전부)를 삭제한다.
#
# 사용법:
#   scripts/reset-app-owner.sh              # 'RESET' 타이핑 확인 후 실행 (profile=local)
#   scripts/reset-app-owner.sh --yes        # 프롬프트 없이 실행 (CI/스크립트용)
#   PROFILE=dev scripts/reset-app-owner.sh  # dev 시드로 초기화
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROFILE="${PROFILE:-local}"
ORACLE_CONTAINER="${ORACLE_CONTAINER:-passkey-oracle}"
ORA_SERVICE="${ORA_SERVICE:-XEPDB1}"

# init-dev-db.sh 와 동일하게 compose project name 을 고정(worktree 충돌 방지).
COMPOSE="docker compose -p passkey2"

# --yes 로 확인 생략
ASSUME_YES=false
[ "${1:-}" = "--yes" ] && ASSUME_YES=true

# --- 가드 1: 파괴적 초기화는 dev/local 프로필에서만 허용 ---
case "${PROFILE}" in
  dev|local) ;;
  *)
    echo "❌ reset-app-owner 는 dev/local 프로필에서만 허용됩니다 (현재: ${PROFILE})." >&2
    echo "   prod/qa 데이터를 보호하기 위한 안전장치입니다." >&2
    exit 1
    ;;
esac

# --- 가드 2: 컨테이너가 떠 있는지 확인 ---
if ! docker ps --format '{{.Names}}' | grep -qx "${ORACLE_CONTAINER}"; then
  echo "❌ Oracle 컨테이너 '${ORACLE_CONTAINER}' 가 실행 중이 아닙니다." >&2
  echo "   먼저 '${COMPOSE} up -d' 로 컨테이너를 띄우거나," >&2
  echo "   볼륨까지 새로 만들려면 scripts/init-dev-db.sh 를 쓰세요." >&2
  exit 1
fi

# --- 가드 2b: 이 컨테이너가 정말 우리 compose 스택(passkey2) 소유인지 확인 ---
# ORACLE_CONTAINER 는 caller 가 바꿀 수 있으므로, 이름만 믿고 SYS DDL 을 쏘면
# 무관한 dev Oracle(sys/oracle@XEPDB1 만 맞으면)을 초기화할 위험이 있다(codex P2).
# compose project 라벨로 우리 스택인지 검증한다.
CONTAINER_PROJECT="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "${ORACLE_CONTAINER}" 2>/dev/null || true)"
if [ "${CONTAINER_PROJECT}" != "passkey2" ]; then
  echo "❌ '${ORACLE_CONTAINER}' 는 passkey2 compose 스택 소유가 아닙니다 (project='${CONTAINER_PROJECT:-none}')." >&2
  echo "   엉뚱한 DB 를 초기화하지 않도록 막습니다. 컨테이너 이름을 확인하세요." >&2
  exit 1
fi

echo "==> PSK_APP_OWNER 스키마 초기화 (profile=${PROFILE}, container=${ORACLE_CONTAINER})"
echo "    repo: ${REPO_ROOT}"
echo "    ⚠️  PSK_APP_OWNER 의 모든 데이터(테넌트·계정·패스키·인증기록)가 삭제됩니다."
echo "    (컨테이너/볼륨/유저/role 은 유지됩니다.)"

# --- 가드 3: 'RESET' 타이핑 확인 ---
if [ "${ASSUME_YES}" != "true" ]; then
  read -r -p "계속하려면 RESET 을 입력하세요: " reply
  if [ "${reply}" != "RESET" ]; then
    echo "취소됨."
    exit 1
  fi
fi

cd "${REPO_ROOT}"

echo "==> [1/3] PSK_APP_OWNER 객체 DROP (SYS 세션)"
{ echo "DEFINE ora_service = ${ORA_SERVICE}"; cat "${SCRIPT_DIR}/reset-app-owner.sql"; } | \
  docker exec -i "${ORACLE_CONTAINER}" \
    sqlplus -S sys/oracle@localhost:1521/${ORA_SERVICE} as sysdba

echo "==> [2/3] role/스키마 재부트스트랩"
bash "${SCRIPT_DIR}/run-bootstrap.sh"

echo "==> [3/3] 스키마 + ${PROFILE} 시드 적용"
PROFILE="${PROFILE}" ORACLE_CONTAINER="${ORACLE_CONTAINER}" ORA_SERVICE="${ORA_SERVICE}" \
  bash "${SCRIPT_DIR}/apply-schema.sh"

echo "==> ✅ 완료. ${PROFILE} 시드가 적용된 깨끗한 PSK_APP_OWNER 스키마 준비됨."
echo "    이제 서버를 띄우세요:"
echo "      ./gradlew :admin-app:bootRun   --args='--spring.profiles.active=${PROFILE}'"
echo "      ./gradlew :passkey-app:bootRun --args='--spring.profiles.active=${PROFILE}'"
echo "      ./gradlew :rp-app:bootRun      --args='--spring.profiles.active=${PROFILE}'"
