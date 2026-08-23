#!/usr/bin/env bash
# 가이드 §6·§8 — Redis 와 앱 스택을 순서대로 기동한다.
#
#   ./start.sh                  # 선검증 → Redis → 앱 전체 기동
#   ./start.sh rp-app           # 테넌트/API 키를 채운 뒤 rp-app 만 재기동(§10.3)
#   ./start.sh --check          # 기동하지 않고 .env 검증만
#   ./start.sh --status         # 현재 상태만 출력
#   ./start.sh --stop           # 앱 스택 중지(Redis 는 유지)
#   ./start.sh --stop-all       # 앱 + Redis 모두 중지(볼륨은 보존)
#
# 손으로 치면 틀리기 쉬운 것들을 대신 처리한다.
#   - COMPOSE_PROFILES 누락 → nginx·rp-app 이 "에러 없이" 안 뜨는 사고 방지
#   - Redis 스택이 요구하는 REDIS_BIND_ADDR / REDIS_PASSWORD export
#   - MASTER_KEY base64 32바이트, ISSUER_BASE 의 https, 자리표시자 잔존 검사
#   - healthy 될 때까지 대기(admin-app 은 start_period 90초)
set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE=deploy/.env
COMPOSE_APP=(docker compose -f docker-compose.yml)
COMPOSE_REDIS=(docker compose -f docker-compose.redis.yml)

# QA 단일 호스트 기준. 운영 서버 A/B 는 README 의 프로파일 조합을 따른다.
PROFILES=${COMPOSE_PROFILES:-proxy,admin,qa}

RED=$'\033[31m'; YEL=$'\033[33m'; GRN=$'\033[32m'; RST=$'\033[0m'
[[ -t 1 ]] || { RED=; YEL=; GRN=; RST=; }

fail(){ echo "${RED}[!!]${RST} $*" >&2; }
warn(){ echo "${YEL}[??]${RST} $*"; }
ok(){   echo "${GRN}[OK]${RST} $*"; }

ERRORS=0
err(){ fail "$*"; ERRORS=$((ERRORS+1)); }

# ---------------------------------------------------------------------------
# .env 로드
#
# set -a 로 export 하되 서브셸이 아닌 현재 셸에서 읽는다. docker compose 는
# deploy/.env 를 자동으로 읽지만, redis 스택의 ${REDIS_BIND_ADDR:?} 와
# healthcheck 의 $$REDIS_PASSWORD 는 셸 환경에 있어야 하므로 여기서도 읽는다.
# ---------------------------------------------------------------------------
load_env() {
  [[ -f $ENV_FILE ]] || {
    fail "$ENV_FILE 이 없습니다."
    echo "     cd deploy && cp .env.qa-template .env && chmod 600 .env"
    echo "     그다음 ./gen-secrets.sh --write 로 시크릿을 채우세요."
    exit 1
  }
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
}

# ---------------------------------------------------------------------------
# 선검증 — 여기서 잡아야 컨테이너가 뜬 뒤 원인 모를 실패를 겪지 않는다
# ---------------------------------------------------------------------------
check_env() {
  echo "== .env 검증 =="

  # 권한. 시크릿이 들어 있으므로 600 이어야 한다.
  local perm
  perm=$(stat -c '%a' "$ENV_FILE" 2>/dev/null || stat -f '%Lp' "$ENV_FILE")
  if [[ $perm == 600 ]]; then ok ".env 권한 600"
  else warn ".env 권한이 $perm 입니다 → chmod 600 $ENV_FILE"; fi

  # 필수 변수가 비어 있지 않은지.
  local missing=()
  local required=(
    DB_URL DB_RUNTIME_USER DB_RUNTIME_PASSWORD
    DB_ADMIN_USER DB_ADMIN_PASSWORD DB_OWNER_USER DB_OWNER_PASSWORD
    REDIS_HOST REDIS_PASSWORD MASTER_KEY
    PASSKEY_SERVER_NAME ISSUER_BASE ADMIN_INVITE_BASE_URL
  )
  local v
  for v in "${required[@]}"; do
    [[ -n ${!v:-} ]] || missing+=("$v")
  done
  if ((${#missing[@]})); then
    err "값이 비어 있습니다: ${missing[*]}"
  else
    ok "필수 변수 ${#required[@]}개 채워짐"
  fi

  # 자리표시자 잔존. .env.qa-template 의 __XXX__ 를 안 바꾼 채 기동하는 사고가 잦다.
  local leftover
  leftover=$(grep -nE '__[A-Za-z가-힣]+__' "$ENV_FILE" || true)
  if [[ -n $leftover ]]; then
    err "템플릿 자리표시자가 남아 있습니다:"
    echo "${leftover//$'\n'/$'\n'       }" | sed '1s/^/       /'
  else
    ok "자리표시자 없음"
  fi

  # MASTER_KEY 는 base64 디코딩 시 정확히 32바이트여야 AES 키가 로드된다.
  if [[ -n ${MASTER_KEY:-} ]]; then
    local len
    len=$(printf '%s' "$MASTER_KEY" | base64 -d 2>/dev/null | wc -c | tr -d ' ') || len=0
    if [[ $len == 32 ]]; then ok "MASTER_KEY = 32바이트"
    else err "MASTER_KEY 가 ${len}바이트입니다(32 필요) → ./gen-secrets.sh --write"; fi
  fi

  # WebAuthn 은 localhost 가 아니면 HTTPS 필수다(§9).
  if [[ ${ISSUER_BASE:-} == https://* ]]; then
    ok "ISSUER_BASE 가 https"
  elif [[ -n ${ISSUER_BASE:-} ]]; then
    err "ISSUER_BASE 가 https:// 로 시작하지 않습니다: $ISSUER_BASE"
  fi

  # 컨테이너 안의 127.0.0.1 은 자기 자신이다. Redis 를 못 찾는 전형적 원인.
  if [[ ${REDIS_HOST:-} == 127.0.0.1 || ${REDIS_HOST:-} == localhost ]]; then
    err "REDIS_HOST 가 $REDIS_HOST 입니다. 컨테이너 안에서는 자기 자신을 가리킵니다."
    echo "       호스트 내부 IP 를 쓰세요 (ip -4 addr show docker0 → 보통 172.17.0.1)"
  elif [[ -n ${REDIS_HOST:-} ]]; then
    ok "REDIS_HOST = $REDIS_HOST"
  fi

  # rp-app 은 데모 기본값이면 부팅을 거부한다.
  if [[ -z ${RP_RELAY_SECRET:-} ]]; then
    err "RP_RELAY_SECRET 이 비어 있습니다 → ./gen-secrets.sh --write"
  fi

  # 이미지가 실제로 적재돼 있는지. 폐쇄망이라 pull 폴백이 없다.
  local tag=${IMAGE_TAG:-0.0.1-SNAPSHOT} img
  for img in passkey-app admin-app rp-app; do
    docker image inspect "$img:$tag" >/dev/null 2>&1 \
      || err "이미지 없음: $img:$tag → ./load-images.sh $img"
  done

  return 0
}

# RP_API_KEY / RP_TENANT_ID 는 테넌트를 만든 뒤에야 알 수 있다(§10).
# 최초 기동에는 비어 있는 것이 정상이므로 rp-app 만 빼고 진행한다.
rp_ready() { [[ -n ${RP_API_KEY:-} && -n ${RP_TENANT_ID:-} ]]; }

# ---------------------------------------------------------------------------
# Redis — 앱보다 먼저 떠 있어야 한다
# ---------------------------------------------------------------------------
start_redis() {
  echo
  echo "== Redis 기동 =="

  # 앱은 REDIS_HOST 로 호스트 IP 를 보므로, 바인딩도 그 주소여야 도달한다.
  # 0.0.0.0 은 외부 노출이라 금지. 미지정 시 REDIS_HOST 를 그대로 쓴다.
  export REDIS_BIND_ADDR=${REDIS_BIND_ADDR:-${REDIS_HOST}}
  if [[ $REDIS_BIND_ADDR == 0.0.0.0 ]]; then
    err "REDIS_BIND_ADDR 이 0.0.0.0 입니다. 내부 IP 로 지정하세요."
    return 1
  fi

  if (cd deploy && "${COMPOSE_REDIS[@]}" ps --status running 2>/dev/null | grep -q redis); then
    ok "이미 기동 중 (bind ${REDIS_BIND_ADDR}:${REDIS_HOST_PORT:-6379})"
    return 0
  fi

  (cd deploy && "${COMPOSE_REDIS[@]}" up -d)
  ok "bind ${REDIS_BIND_ADDR}:${REDIS_HOST_PORT:-6379}"
}

# ---------------------------------------------------------------------------
# 앱 스택
# ---------------------------------------------------------------------------
start_apps() {
  echo
  echo "== 앱 기동 (profiles: $PROFILES) =="

  local svcs=()
  if [[ $# -gt 0 ]]; then
    svcs=("$@")
  elif ! rp_ready; then
    # rp-app 을 뺀 나머지만. 빈 RP_API_KEY 로 띄워봐야 401 만 난다.
    warn "RP_API_KEY / RP_TENANT_ID 가 비어 있어 rp-app 은 제외합니다."
    echo "     테넌트·API 키 발급(README §9) 후: ./start.sh rp-app"
    svcs=(nginx passkey-app admin-app)
  fi

  (cd deploy && COMPOSE_PROFILES="$PROFILES" "${COMPOSE_APP[@]}" up -d "${svcs[@]}")
}

# ---------------------------------------------------------------------------
# healthy 대기 — admin-app 은 start_period 가 90초라 조급하게 판단하면 안 된다
# ---------------------------------------------------------------------------
wait_healthy() {
  echo
  echo "== 헬스체크 대기 (최대 ${WAIT_TIMEOUT:-180}초) =="
  local deadline=$(( SECONDS + ${WAIT_TIMEOUT:-180} ))
  local ids id name state health pending

  while :; do
    pending=()
    ids=$(cd deploy && COMPOSE_PROFILES="$PROFILES" "${COMPOSE_APP[@]}" ps -q 2>/dev/null || true)
    [[ -n $ids ]] || { warn "기동 중인 컨테이너가 없습니다."; return 1; }

    for id in $ids; do
      name=$(docker inspect -f '{{.Name}}' "$id" | sed 's|^/||')
      state=$(docker inspect -f '{{.State.Status}}' "$id")
      # healthcheck 가 없는 서비스(nginx, rp-app)는 running 이면 정상으로 본다.
      health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id")

      if [[ $state != running ]]; then
        pending+=("$name:$state")
      elif [[ $health == starting ]]; then
        pending+=("$name:starting")
      elif [[ $health == unhealthy ]]; then
        pending+=("$name:unhealthy")
      fi
    done

    if ((${#pending[@]} == 0)); then
      echo
      ok "전부 정상"
      return 0
    fi

    if (( SECONDS >= deadline )); then
      echo
      fail "시간 초과: ${pending[*]}"
      echo "     로그 확인: cd deploy && docker compose logs --tail=50 admin-app"
      return 1
    fi

    printf '\r  대기 중: %-60.60s' "${pending[*]}"
    sleep 5
  done
}

show_status() {
  echo
  echo "== 상태 =="
  (cd deploy && "${COMPOSE_REDIS[@]}" ps) || true
  echo
  (cd deploy && COMPOSE_PROFILES="$PROFILES" "${COMPOSE_APP[@]}" ps) || true
}

next_steps() {
  echo
  echo "== 다음 =="
  if ! rp_ready; then
    echo "  1) admin 콘솔 로그인 → 테넌트 생성 → API 키 발급 (README §9)"
    echo "     http://${ADMIN_BIND_ADDR:-127.0.0.1}:${ADMIN_HOST_PORT:-8081}/admin/"
    echo "  2) deploy/.env 의 RP_TENANT_ID, RP_API_KEY 를 채운다"
    echo "  3) ./start.sh rp-app"
  else
    echo "  브라우저에서 https://${RP_SERVER_NAME:-rp-qa} 접속 → 패스키 등록"
    echo "  (HTTPS 가 아니면 브라우저가 등록을 거부합니다 — README §9)"
  fi
  echo
  echo "  로그:  cd deploy && docker compose logs -f admin-app"
  echo "  중지:  ./start.sh --stop"
}

# ---------------------------------------------------------------------------
case "${1:-}" in
  --check)
    load_env; check_env
    echo
    if (( ERRORS == 0 )); then
      ok "검증 통과"
    else
      fail "$ERRORS 건을 해결하세요."; exit 1
    fi
    ;;

  --status)
    load_env
    export REDIS_BIND_ADDR=${REDIS_BIND_ADDR:-${REDIS_HOST:-127.0.0.1}}
    show_status
    ;;

  --stop)
    load_env
    (cd deploy && COMPOSE_PROFILES="$PROFILES" "${COMPOSE_APP[@]}" down)
    ok "앱 스택 중지 (Redis 는 유지)"
    ;;

  --stop-all)
    load_env
    export REDIS_BIND_ADDR=${REDIS_BIND_ADDR:-${REDIS_HOST:-127.0.0.1}}
    (cd deploy && COMPOSE_PROFILES="$PROFILES" "${COMPOSE_APP[@]}" down)
    (cd deploy && "${COMPOSE_REDIS[@]}" down)
    ok "전체 중지 (볼륨은 보존)"
    ;;

  -h|--help)
    # 파일 상단 주석 블록만 출력한다(첫 비주석 라인에서 중단).
    sed -n '2,/^[^#]/p' "$0" | sed -e '$d' -e 's/^# \?//'
    ;;

  *)
    load_env
    check_env
    if (( ERRORS > 0 )); then
      echo
      fail "$ERRORS 건을 먼저 해결하세요. (검증만: ./start.sh --check)"
      exit 1
    fi

    start_redis
    start_apps "$@"
    wait_healthy || exit 1
    show_status
    next_steps
    ;;
esac
