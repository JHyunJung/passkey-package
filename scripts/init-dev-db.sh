#!/usr/bin/env bash
#
# init-dev-db.sh — dev 프로필용 로컬 DB 를 처음부터 깨끗이 초기화한다.
#
# 절차(수동 4단계를 자동화):
#   1. docker compose down -v && up -d  — Oracle/Redis 컨테이너 + 볼륨 재생성
#      (gvenzl/oracle-xe 가 PSK_APP_OWNER 스키마/유저만 자동 생성)
#   2. wait-for-oracle               — healthcheck 가 healthy 될 때까지 대기
#   3. run-bootstrap.sh              — PSK_APP_ADMIN/PSK_APP_RUNTIME role + 스키마 생성
#                                      (VPD 제거됨 — CTX_PKG/APP_CTX/VPD GRANT 안 만듦)
#   4. admin-app(dev) 부팅           — Flyway 가 V1~ 마이그레이션 + dev 시드(R__) 적용 후 종료
#
# 결과: dev 프로필 시드(테넌트 dev-passkey, 운영자 alice, API key pk_devsrv01)가
#       들어간 깨끗한 DB. 이후 ./gradlew :admin-app:bootRun --args='--spring.profiles.active=dev'
#       또는 passkey-app/rp-app 를 띄우면 된다.
#
# ⚠️ 파괴적: 기존 DB 데이터(테넌트·계정·패스키·초대 전부)를 삭제한다.
#
# 사용법:
#   scripts/init-dev-db.sh           # 확인 프롬프트 후 실행
#   scripts/init-dev-db.sh --yes     # 프롬프트 없이 실행 (CI/스크립트용)
#
# git worktree 에서도 메인 체크아웃과 같은 개발 볼륨(passkey2_oracle-data)을 지운다.
# worktree 에서 --yes 로 돌리려면 INIT_DEV_DB_FORCE=1 을 함께 지정해야 한다.
#
# 다른 프로필(local/qa)로 초기화하려면 PROFILE 환경변수로:
#   PROFILE=local scripts/init-dev-db.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROFILE="${PROFILE:-dev}"

# 컨테이너는 container_name 으로 고정(passkey-oracle/redis)되어 있고 1521/6379
# 포트를 공유한다. git worktree 에서 실행하면 compose project name 이 경로 기반
# 으로 달라져 'container name already in use' 충돌이 난다. project name 을
# 고정해 worktree 든 메인 체크아웃이든 같은 스택을 제어하게 한다.
COMPOSE="docker compose -p passkey2"

# --yes 로 확인 생략
ASSUME_YES=false
[ "${1:-}" = "--yes" ] && ASSUME_YES=true

ORACLE_VOLUME="passkey2_oracle-data"

# git worktree 안에서 실행해도 위 고정 project name 때문에 메인 체크아웃과 같은
# (공유) 개발 볼륨을 지운다. 프롬프트 없이(--yes) worktree 에서 돌리는 실수를
# 막기 위해 그 조합은 INIT_DEV_DB_FORCE=1 을 명시해야만 진행한다.
IN_WORKTREE=false
GIT_DIR_ABS="$(git -C "${REPO_ROOT}" rev-parse --path-format=absolute --git-dir 2>/dev/null || true)"
GIT_COMMON_ABS="$(git -C "${REPO_ROOT}" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
if [ -n "${GIT_DIR_ABS}" ] && [ "${GIT_DIR_ABS}" != "${GIT_COMMON_ABS}" ]; then
  IN_WORKTREE=true
fi

echo "==> dev DB 초기화 (profile=${PROFILE})"
echo "    repo: ${REPO_ROOT}"
echo "    compose project: passkey2 — 볼륨 ${ORACLE_VOLUME} 을(를) 삭제 후 재생성"
if docker volume inspect "${ORACLE_VOLUME}" >/dev/null 2>&1; then
  echo "    ⚠️  기존 볼륨 ${ORACLE_VOLUME} 이(가) 있습니다 — 그 안의 모든 데이터가 삭제됩니다."
fi
if [ "${IN_WORKTREE}" = "true" ]; then
  echo "    ⚠️  git worktree 에서 실행 중입니다. worktree 전용 DB 가 아니라 메인 체크아웃과 공유하는 개발 DB 를 지웁니다."
  if [ "${ASSUME_YES}" = "true" ] && [ "${INIT_DEV_DB_FORCE:-}" != "1" ]; then
    echo "    worktree 에서 --yes 로 실행하려면 INIT_DEV_DB_FORCE=1 을 함께 지정하세요. 중단합니다." >&2
    exit 2
  fi
fi
if [ "${ASSUME_YES}" != "true" ]; then
  read -r -p "계속하시겠습니까? [y/N] " reply
  case "${reply}" in
    y|Y|yes|YES) ;;
    *) echo "취소됨."; exit 1 ;;
  esac
fi

cd "${REPO_ROOT}"

echo "==> [1/4] 컨테이너 + 볼륨 재생성"
${COMPOSE} down -v --remove-orphans
${COMPOSE} up -d

echo "==> [2/4] Oracle healthy 대기"
bash "${SCRIPT_DIR}/wait-for-oracle.sh"

echo "==> [3/4] role/스키마 부트스트랩"
bash "${SCRIPT_DIR}/run-bootstrap.sh"

echo "==> [4/4] Flyway 마이그레이션 + ${PROFILE} 시드 적용 (admin-app 부팅 후 자동 종료)"
# admin-app 을 부팅하면 Flyway 가 마이그레이션+시드를 적용한다. 마이그레이션이
# 끝나고 'Started AdminApplication' 이 뜨면 목적 달성 — 백그라운드로 띄우고
# 그 로그를 확인한 뒤 종료시킨다.
LOG="$(mktemp -t init-dev-db.XXXXXX.log)"
./gradlew :admin-app:bootRun --args="--spring.profiles.active=${PROFILE}" > "${LOG}" 2>&1 &
GRADLE_PID=$!

# 부팅 완료(Started) 또는 실패 신호를 폴링
ok=false
for _ in $(seq 1 80); do
  if grep -q "Started AdminApplication" "${LOG}" 2>/dev/null; then ok=true; break; fi
  if grep -qE "APPLICATION FAILED TO START|BUILD FAILED|Migration.*failed|ORA-[0-9]" "${LOG}" 2>/dev/null; then break; fi
  sleep 3
done

# admin-app 종료(마이그레이션은 이미 끝남)
kill "${GRADLE_PID}" 2>/dev/null || true
pkill -f "admin-app.*bootRun" 2>/dev/null || true

if [ "${ok}" = "true" ]; then
  echo "==> ✅ 완료. ${PROFILE} 시드가 적용된 깨끗한 DB 준비됨."
  echo "    로그: ${LOG}"
  echo "    이제 서버를 띄우세요:"
  echo "      ./gradlew :admin-app:bootRun  --args='--spring.profiles.active=${PROFILE}'"
  echo "      ./gradlew :passkey-app:bootRun --args='--spring.profiles.active=${PROFILE}'"
else
  echo "==> ❌ 마이그레이션/부팅 실패. 로그 확인: ${LOG}" >&2
  tail -30 "${LOG}" >&2 || true
  exit 1
fi
