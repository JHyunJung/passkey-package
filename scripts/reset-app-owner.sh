#!/usr/bin/env bash
#
# reset-app-owner.sh — APP_OWNER 스키마만 완전 초기화한다(컨테이너/볼륨은 유지).
#
# init-dev-db.sh 와의 차이:
#   init-dev-db.sh  : docker compose down -v 로 Oracle 볼륨까지 날리고 재부트스트랩(분 단위).
#   reset-app-owner : 컨테이너는 그대로 두고 APP_OWNER 스키마 객체만 DROP(초 단위).
#                     "내가 원할 때만" 빠르게 데이터+스키마를 초기 상태로 되돌린다.
#
# 절차:
#   1. (가드) 프로필이 dev/local 인지 확인 — qa/prod 면 거부.
#   2. (가드) 'RESET' 을 직접 타이핑해야 진행 (--yes 로 우회).
#   3. reset-app-owner.sql  : SYS 세션에서 APP_OWNER 객체 전부 DROP(테이블·시퀀스·
#                             뷰·패키지·트리거 + 과거 VPD 잔재(정책·CTX_PKG·APP_CTX) 청소).
#   4. run-bootstrap.sh     : role/스키마 재생성(VPD 제거됨 — CTX_PKG/APP_CTX/VPD GRANT 안 만듦).
#   5. admin-app(Flyway)    : V1~ 마이그레이션 + ${PROFILE} 시드(R__) 적용 후 종료.
#
# 결과: 컨테이너 재기동 없이, ${PROFILE} 시드가 들어간 깨끗한 APP_OWNER 스키마.
#
# ⚠️ 파괴적: APP_OWNER 의 모든 데이터(테넌트·계정·패스키·인증기록 전부)를 삭제한다.
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

echo "==> APP_OWNER 스키마 초기화 (profile=${PROFILE}, container=${ORACLE_CONTAINER})"
echo "    repo: ${REPO_ROOT}"
echo "    ⚠️  APP_OWNER 의 모든 데이터(테넌트·계정·패스키·인증기록)가 삭제됩니다."
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

echo "==> [1/3] APP_OWNER 객체 DROP (SYS 세션)"
{ echo "DEFINE ora_service = ${ORA_SERVICE}"; cat "${SCRIPT_DIR}/reset-app-owner.sql"; } | \
  docker exec -i "${ORACLE_CONTAINER}" \
    sqlplus -S sys/oracle@localhost:1521/${ORA_SERVICE} as sysdba

echo "==> [2/3] role/스키마 재부트스트랩"
bash "${SCRIPT_DIR}/run-bootstrap.sh"

echo "==> [3/3] Flyway 마이그레이션 + ${PROFILE} 시드 적용 (admin-app 부팅 후 자동 종료)"
LOG="$(mktemp -t reset-app-owner.XXXXXX.log)"
# bootRun 전 8081 을 이미 점유한 PID 를 기록해 둔다. cleanup 단계에서 이 baseline
# 에 없던 '새' 리스너만 종료해, 우리가 띄우지 않은 무관한 프로세스를 죽이지 않는다
# (codex P2). 공백 구분 문자열로 보관하고 매칭 시 단어 경계로 비교한다.
# lsof 는 매칭이 없으면 exit 1 을 내는데, set -euo pipefail 하에서 명령치환 실패는
# 스크립트를 죽인다. 8081 이 비어 있는 게 정상(보통 그렇다)이므로 '|| true' 로 흡수.
PRE_8081_PIDS=" $(lsof -nP -iTCP:8081 -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ' || true)"
# --no-daemon 은 쓰지 않는다: 이 프로젝트는 org.gradle.jvmargs 가 설정돼 있어
# --no-daemon 이어도 Gradle 이 "single-use daemon" 을 fork 한다("To honour the JVM
# settings ... a single-use Daemon process will be forked"). 그러면 bootRun JVM 이
# wrapper 자식 트리 밖으로 빠져 종료 추적이 더 꼬이고, 부팅 자체가 폴링과 엉켜
# Flyway 가 안 도는 회귀가 났다. 대신 종료는 아래 'baseline-aware 8081 cleanup'
# 에 맡긴다 — daemon 이 띄운 JVM 이라도 8081 리스너를 정확히 잡아 정리한다.
# 새 프로세스 그룹(setsid)으로 띄워 그룹째 종료한다. 'pkill -f admin-app.*bootRun'
# 같은 전역 패턴 kill 은 다른 개발자의 admin-app 실행까지 죽일 수 있어 안 쓴다.
# setsid 가 없으면(macOS 기본) 단일 PID kill 로 폴백한다.
if command -v setsid >/dev/null 2>&1; then
  setsid ./gradlew :admin-app:bootRun --args="--spring.profiles.active=${PROFILE}" > "${LOG}" 2>&1 &
else
  ./gradlew :admin-app:bootRun --args="--spring.profiles.active=${PROFILE}" > "${LOG}" 2>&1 &
fi
GRADLE_PID=$!

ok=false
for _ in $(seq 1 80); do
  if grep -q "Started AdminApplication" "${LOG}" 2>/dev/null; then ok=true; break; fi
  if grep -qE "APPLICATION FAILED TO START|BUILD FAILED|Migration.*failed|ORA-[0-9]" "${LOG}" 2>/dev/null; then break; fi
  sleep 3
done

# admin-app 종료(마이그레이션은 이미 끝남). 스폰한 프로세스 그룹만 정확히 종료.
# setsid 로 띄웠으면 GRADLE_PID 가 그룹 리더이므로 -PGID 로 그룹 전체를 죽인다.
if command -v setsid >/dev/null 2>&1; then
  kill -TERM "-${GRADLE_PID}" 2>/dev/null || true
else
  kill -TERM "${GRADLE_PID}" 2>/dev/null || true
fi
wait "${GRADLE_PID}" 2>/dev/null || true

# 스폰한 admin-app 이 실제로 종료됐는지 확인(codex P2). wrapper 에 보낸 SIGTERM 이
# 자식 JVM 으로 전파돼 종료되기까지 몇 초 걸릴 수 있어(race), 'baseline 에 없던
# 새 8081 리스너' 해제를 잠깐 폴링한다. baseline(PRE_8081_PIDS)에 이미 있던 PID 는
# 우리가 띄운 게 아니므로 절대 건드리지 않는다(전역 패턴 kill 금지 + 무관 프로세스 보호).
new_8081_pids() {
  local pid
  # lsof 미매칭 exit 1 은 '|| true' 로 흡수(set -e 보호). 함수는 항상 0 으로 끝낸다.
  for pid in $(lsof -nP -iTCP:8081 -sTCP:LISTEN -t 2>/dev/null || true); do
    case "${PRE_8081_PIDS}" in
      *" ${pid} "*) : ;;          # baseline 에 있던 PID → 건너뜀
      *) printf '%s ' "${pid}" ;;  # 새로 생긴 PID
    esac
  done
  return 0
}
for _ in $(seq 1 6); do
  if [ -z "$(new_8081_pids)" ]; then break; fi
  sleep 2
done
STALE_PIDS="$(new_8081_pids)"
if [ -n "${STALE_PIDS}" ]; then
  echo "==> 잔존 admin-app(8081, pid=${STALE_PIDS}) 종료"
  # shellcheck disable=SC2086
  kill -TERM ${STALE_PIDS} 2>/dev/null || true
  for _ in $(seq 1 5); do
    if [ -z "$(new_8081_pids)" ]; then break; fi
    sleep 1
  done
  REMAIN="$(new_8081_pids)"
  # shellcheck disable=SC2086
  [ -n "${REMAIN}" ] && kill -KILL ${REMAIN} 2>/dev/null || true
fi

if [ "${ok}" = "true" ]; then
  echo "==> ✅ 완료. ${PROFILE} 시드가 적용된 깨끗한 APP_OWNER 스키마 준비됨."
  echo "    로그: ${LOG}"
  echo "    이제 서버를 띄우세요:"
  echo "      ./gradlew :admin-app:bootRun   --args='--spring.profiles.active=${PROFILE}'"
  echo "      ./gradlew :passkey-app:bootRun --args='--spring.profiles.active=${PROFILE}'"
  echo "      ./gradlew :rp-app:bootRun      --args='--spring.profiles.active=${PROFILE}'"
else
  echo "==> ❌ 마이그레이션/부팅 실패. 로그 확인: ${LOG}" >&2
  tail -30 "${LOG}" >&2 || true
  exit 1
fi
