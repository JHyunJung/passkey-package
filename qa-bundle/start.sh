#!/usr/bin/env bash
# README "6. 기동" — Redis 와 앱 스택을 순서대로 기동한다.
#
#   ./start.sh                  # 선검증 → Redis → 앱 전체 기동
#   ./start.sh rp-app           # 테넌트/API 키를 채운 뒤 rp-app 만 재기동
#   ./start.sh --check          # 기동하지 않고 .env 검증만
#   ./start.sh --status         # 현재 상태만 출력
#   ./start.sh --stop           # 앱 스택 중지(Redis 는 유지)
#   ./start.sh --stop-all       # 앱 + Redis 모두 중지(볼륨은 보존)
#   ./start.sh --nginx-conf     # 호스트 nginx 설정 출력(PROXY_MODE=host 전용)
#
# deploy/.env 의 PROXY_MODE 로 nginx 방식을 고른다(README "nginx — 두 가지 방식").
#   host    : 서버에 이미 있는 nginx 가 TLS 종료 → 번들 nginx 를 빼고 앱 포트를 루프백에 연다
#   bundled : 번들 nginx 컨테이너가 80/443 과 TLS 를 맡는다 (값이 없을 때의 기본)
#
# 로그는 deploy/logs/ 에 남는다.
#   - 기동·중지 직전: 기존 컨테이너 로그를 <타임스탬프>/<컨테이너명>.log 로 보관
#   - 기동 후: live-<타임스탬프>.log 에 실시간으로 계속 기록(중지 시 종료)
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
# PROXY_MODE 가 .env 에 있으므로 load_env 뒤 apply_mode 가 정한다.
PROFILES=

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
  apply_mode
}

# PROXY_MODE 에 따라 프로파일과 compose 파일을 정한다.
#   host    : 번들 nginx(proxy) 를 빼고, 덧씌움 파일로 passkey-app·rp-app 을 루프백 포트에 연다
#   bundled : 번들 nginx 가 compose 내부망으로 앱에 닿으므로 앱 포트를 열지 않는다
# 값이 없으면 bundled — PROXY_MODE 도입 전에 만든 .env 의 동작을 그대로 둔다.
apply_mode() {
  PROXY_MODE=${PROXY_MODE:-bundled}
  case $PROXY_MODE in
    host)
      COMPOSE_APP+=(-f docker-compose.host-nginx.yml)
      PROFILES=${COMPOSE_PROFILES:-admin,qa}
      ;;
    bundled)
      PROFILES=${COMPOSE_PROFILES:-proxy,admin,qa}
      ;;
    *)
      fail "PROXY_MODE 는 host 또는 bundled 여야 합니다: $PROXY_MODE ($ENV_FILE)"
      exit 1
      ;;
  esac
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
    DB_ADMIN_USER DB_ADMIN_PASSWORD
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

  # nginx 방식별 필수값. 비어 있으면 호스트 nginx 설정이나 RP 주소를 만들 수 없다.
  local mode_required=() mode_missing=()
  if [[ $PROXY_MODE == host ]]; then
    mode_required=(ADMIN_SERVER_NAME RP_PUBLIC_PORT PASSKEY_HOST_PORT RP_HOST_PORT TLS_CERT_FILE TLS_KEY_FILE)
  else
    mode_required=(RP_SERVER_NAME)
  fi
  for v in "${mode_required[@]}"; do
    [[ -n ${!v:-} ]] || mode_missing+=("$v")
  done
  if ((${#mode_missing[@]})); then
    err "PROXY_MODE=$PROXY_MODE 에 필요한 값이 비어 있습니다: ${mode_missing[*]}"
  else
    ok "PROXY_MODE=$PROXY_MODE (profiles: $PROFILES)"
  fi

  if [[ $PROXY_MODE == host ]]; then
    # RP 는 passkey 와 같은 도메인의 다른 포트다. 443 이면 passkey-app 블록과 겹친다.
    if [[ -n ${RP_PUBLIC_PORT:-} && ! $RP_PUBLIC_PORT =~ ^[0-9]+$ ]]; then
      err "RP_PUBLIC_PORT 가 숫자가 아닙니다: $RP_PUBLIC_PORT"
    elif [[ ${RP_PUBLIC_PORT:-} == 443 ]]; then
      err "RP_PUBLIC_PORT 가 443 입니다. passkey 와 같은 도메인이므로 다른 포트여야 합니다(예: 8443)"
    fi

    # 메일 링크가 admin 도메인을 가리켜야 한다. 다른 주소를 일부러 쓸 수도 있어 경고만.
    if [[ -n ${ADMIN_SERVER_NAME:-} && ${ADMIN_INVITE_BASE_URL:-} != "https://${ADMIN_SERVER_NAME}" ]]; then
      warn "ADMIN_INVITE_BASE_URL($ADMIN_INVITE_BASE_URL) 이 https://$ADMIN_SERVER_NAME 과 다릅니다 — 메일 링크 주소 확인"
    fi

    # Enforcing 이면 nginx → 앱 포트 연결이 기본 차단돼 원인 모를 502 가 난다.
    # 스택 기동 자체와는 무관하므로 경고만 한다.
    if command -v getenforce >/dev/null 2>&1 && [[ $(getenforce 2>/dev/null) == Enforcing ]]; then
      if getsebool httpd_can_network_connect 2>/dev/null | grep -q -- '--> on'; then
        ok "SELinux httpd_can_network_connect = on"
      else
        warn "SELinux httpd_can_network_connect 가 off — 호스트 nginx 가 502 를 냅니다"
        echo "       sudo setsebool -P httpd_can_network_connect 1"
      fi
    fi
  fi

  # 자리표시자 잔존. .env.qa-template 의 __XXX__ 를 안 바꾼 채 기동하는 사고가 잦다.
  # 문자 클래스에 _ 와 숫자가 있어야 __ORACLE_HOST__ 처럼 내부에 _ 가 든 것도
  # 잡힌다. 빠뜨리면 같은 줄의 다른 자리표시자에 우연히 걸리는지에 검출이 의존한다.
  local leftover
  leftover=$(grep -nE '__[A-Za-z0-9_가-힣]+__' "$ENV_FILE" || true)
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

  # WebAuthn 은 localhost 가 아니면 HTTPS 필수다
  # (README "반드시 먼저 해결해야 하는 것 — HTTPS").
  if [[ ${ISSUER_BASE:-} == https://* ]]; then
    ok "ISSUER_BASE 가 https"
  elif [[ -n ${ISSUER_BASE:-} ]]; then
    err "ISSUER_BASE 가 https:// 로 시작하지 않습니다: $ISSUER_BASE"
  fi

  # nginx 가 TLS 를 종료한다. 인증서가 없으면 nginx 가 기동에 실패한다.
  if [[ ,$PROFILES, == *,proxy,* ]]; then
    if [[ -s deploy/cert/fullchain.pem && -s deploy/cert/key.pem ]]; then
      ok "TLS 인증서 deploy/cert/{fullchain,key}.pem"
    else
      err "deploy/cert/fullchain.pem, key.pem 이 없습니다 (README \"반드시 먼저 해결해야 하는 것 — HTTPS\")"
    fi
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

  # compose 의 PASSKEY_BASE_URL 은 기본값이 없다. 비면 rp-app 이 빈 base URL 로
  # 떠서 런타임에야 실패한다. rp-app 을 실제로 띄우는 경우에만 검사한다
  # (start_apps 의 기동 조건과 같은 판정 — rp_ready 이거나 인자로 지정).
  if rp_will_start && [[ -z ${RP_PASSKEY_BASE_URL:-} ]]; then
    err "RP_PASSKEY_BASE_URL 이 비어 있습니다 (예: http://passkey-app:8080)"
  fi

  # 이미지가 실제로 적재돼 있는지. 폐쇄망이라 pull 폴백이 없다.
  local tag=${IMAGE_TAG:-0.0.1-SNAPSHOT} img
  for img in passkey-app admin-app rp-app; do
    docker image inspect "$img:$tag" >/dev/null 2>&1 \
      || err "이미지 없음: $img:$tag → ./load-images.sh $img"
  done

  return 0
}

# RP_API_KEY / RP_TENANT_ID 는 테넌트를 만든 뒤에야 알 수 있다
# (README "8. 테넌트 생성 → API 키 → rp-app 재기동").
# 최초 기동에는 비어 있는 것이 정상이므로 rp-app 만 빼고 진행한다.
rp_ready() { [[ -n ${RP_API_KEY:-} && -n ${RP_TENANT_ID:-} ]]; }

# 이번 실행에서 rp-app 이 실제로 뜨는가. start_apps 의 서비스 선택과 같은 판정이다.
# 인자가 있으면 그 목록에 rp-app 이 있을 때만, 없으면 rp_ready 일 때만 뜬다.
RUN_ARGS=()
rp_will_start() {
  if ((${#RUN_ARGS[@]})); then
    local s
    for s in "${RUN_ARGS[@]}"; do [[ $s == rp-app ]] && return 0; done
    return 1
  fi
  rp_ready
}

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
    # ERRORS 는 check_env 직후에만 읽히므로 여기서 세어봐야 아무도 안 본다.
    # 중단은 return 1 이 한다.
    fail "REDIS_BIND_ADDR 이 0.0.0.0 입니다. 내부 IP 로 지정하세요."
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
# 로그 보관 — 앱 로그는 stdout(docker json-file)뿐이라 컨테이너가 삭제·재생성
# (down, 설정 변경 후 up -d)되면 함께 사라진다. 그 직전에 파일로 떠 둔다.
#   deploy/logs/<타임스탬프>/<컨테이너명>.log
# ---------------------------------------------------------------------------
archive_logs() {
  local compose=("$@") dir id name ids
  # 컨테이너 단위로 뜬다 — --scale 로 여러 개인 passkey-app 도 인스턴스별로 남는다.
  ids=$(cd deploy && COMPOSE_PROFILES="$PROFILES" "${compose[@]}" ps -a -q 2>/dev/null) || true
  [[ -n $ids ]] || return 0

  dir=deploy/logs/$(date +%Y%m%d-%H%M%S)
  mkdir -p "$dir"
  for id in $ids; do
    name=$(docker inspect --format '{{.Name}}' "$id" 2>/dev/null) || continue
    docker logs -t "$id" > "$dir/${name#/}.log" 2>&1 || true
  done
  ok "기존 로그 보관: $dir"
}

# ---------------------------------------------------------------------------
# 실시간 로그 기록 — 기동 후 `compose logs -f` 를 백그라운드로 띄워
# deploy/logs/live-<타임스탬프>.log 에 계속 쌓는다. 컨테이너 재시작·재생성도
# 따라가며, start.sh 로 다시 기동하면 이전 기록기를 끄고 새 파일로 이어 쓴다.
# --since 로 기동 시점 이후만 받아 archive_logs 가 뜬 내용과 겹치지 않게 한다.
# ---------------------------------------------------------------------------
FOLLOW_PID=deploy/logs/.follow.pid

stop_log_follow() {
  [[ -f $FOLLOW_PID ]] || return 0
  kill "$(cat "$FOLLOW_PID")" 2>/dev/null || true
  rm -f "$FOLLOW_PID"
}

start_log_follow() {
  local since=$1 file
  stop_log_follow
  mkdir -p deploy/logs
  file=deploy/logs/live-$(date +%Y%m%d-%H%M%S).log
  COMPOSE_PROFILES="$PROFILES" nohup docker compose -f deploy/docker-compose.yml \
    logs -f -t --no-color --since "$since" >> "$file" 2>&1 < /dev/null &
  echo $! > "$FOLLOW_PID"
  ok "실시간 로그 기록: $file"
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
    echo "     테넌트·API 키 발급(README \"8. 테넌트 생성\") 후: ./start.sh rp-app"
    svcs=(passkey-app admin-app)
    # 번들 nginx 는 proxy profile 일 때만 있다(host 모드에는 서비스가 없다).
    [[ ,$PROFILES, == *,proxy,* ]] && svcs=(nginx "${svcs[@]}")
  fi

  stop_log_follow
  archive_logs "${COMPOSE_APP[@]}"
  local since
  since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  (cd deploy && COMPOSE_PROFILES="$PROFILES" "${COMPOSE_APP[@]}" up -d "${svcs[@]}")
  start_log_follow "$since"
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

# 브라우저가 보는 RP origin. 테넌트 allowedOrigins 에 글자 그대로 들어가야 한다.
rp_origin() {
  if [[ $PROXY_MODE == host ]]; then
    echo "https://${PASSKEY_SERVER_NAME}:${RP_PUBLIC_PORT}"
  else
    echo "https://${RP_SERVER_NAME}"
  fi
}

# 테넌트 rpId — 포트를 뺀 RP 도메인. host 모드는 RP 가 passkey 도메인을 쓴다.
rp_id() {
  if [[ $PROXY_MODE == host ]]; then
    echo "$PASSKEY_SERVER_NAME"
  else
    echo "$RP_SERVER_NAME"
  fi
}

admin_url() {
  if [[ $PROXY_MODE == host ]]; then
    echo "https://${ADMIN_SERVER_NAME}/admin/"
  else
    echo "http://${ADMIN_BIND_ADDR:-127.0.0.1}:${ADMIN_HOST_PORT:-8081}/admin/"
  fi
}

next_steps() {
  echo
  echo "== 다음 =="
  if [[ $PROXY_MODE == host ]]; then
    echo "  호스트 nginx 설정 (최초 1회, 도메인·포트를 바꿨을 때도 — README \"nginx — 두 가지 방식\")"
    echo "     ./start.sh --nginx-conf | sudo tee /etc/nginx/conf.d/passkey-qa.conf > /dev/null"
    echo "     sudo nginx -t && sudo systemctl reload nginx"
    echo
  fi
  if ! rp_ready; then
    echo "  1) admin 콘솔 로그인 → 테넌트 생성 → API 키 발급 (README \"8. 테넌트 생성\")"
    echo "     $(admin_url)"
    echo "     rpId = $(rp_id)"
    echo "     allowedOrigins = [\"$(rp_origin)\"]"
    echo "  2) ./setup.sh --rp-keys 로 RP_TENANT_ID, RP_API_KEY 를 채운다"
    echo "  3) ./start.sh rp-app"
  else
    echo "  브라우저에서 $(rp_origin) 접속 → 패스키 등록"
    echo "  (HTTPS 가 아니면 브라우저가 등록을 거부합니다 — README \"반드시 먼저 해결해야 하는 것\")"
  fi
  echo
  echo "  로그:  cd deploy && docker compose logs -f admin-app"
  echo "  중지:  ./start.sh --stop"
}

# ---------------------------------------------------------------------------
# 호스트 nginx 설정 출력 (PROXY_MODE=host) — 템플릿의 ${VAR} 를 .env 값으로 채운다.
#   ./start.sh --nginx-conf | sudo tee /etc/nginx/conf.d/passkey-qa.conf > /dev/null
#
# envsubst(gettext)는 최소 설치 Rocky 에 없을 수 있어 셸 치환으로 한다. 치환할 이름을
# 목록으로 고정하므로 nginx 변수($host 등)는 건드리지 않는다. stdout 이 그대로 파일이
# 되므로 오류는 stderr 로만 낸다.
# ---------------------------------------------------------------------------
NGINX_TEMPLATE=deploy/host-nginx/passkey-qa.conf.template

render_nginx_conf() {
  if [[ $PROXY_MODE != host ]]; then
    fail "PROXY_MODE=$PROXY_MODE 입니다. --nginx-conf 는 host 모드 전용입니다"
    echo "     (bundled 모드는 번들 nginx 가 deploy/nginx/nginx.conf 를 씁니다)" >&2
    exit 1
  fi

  # .env 에 없어도 되는 값은 compose 와 같은 기본값을 둔다. admin 인증서를 따로
  # 지정하지 않으면 passkey 도메인과 같은 인증서를 쓴다.
  : "${PASSKEY_BIND_ADDR:=127.0.0.1}" "${RP_BIND_ADDR:=127.0.0.1}" "${ADMIN_BIND_ADDR:=127.0.0.1}"
  : "${PASSKEY_HOST_PORT:=8080}" "${RP_HOST_PORT:=19090}" "${ADMIN_HOST_PORT:=8081}"
  : "${ADMIN_TLS_CERT_FILE:=${TLS_CERT_FILE:-}}" "${ADMIN_TLS_KEY_FILE:=${TLS_KEY_FILE:-}}"

  local vars=(
    PASSKEY_SERVER_NAME ADMIN_SERVER_NAME RP_PUBLIC_PORT
    PASSKEY_BIND_ADDR PASSKEY_HOST_PORT RP_BIND_ADDR RP_HOST_PORT ADMIN_BIND_ADDR ADMIN_HOST_PORT
    TLS_CERT_FILE TLS_KEY_FILE ADMIN_TLS_CERT_FILE ADMIN_TLS_KEY_FILE
  )
  local v bad=() out
  for v in "${vars[@]}"; do
    # 빈 값이나 템플릿 자리표시자(__…__)가 들어가면 nginx -t 를 통과하고도 엉뚱하게 동작한다.
    [[ -n ${!v:-} && ${!v} != *__*__* ]] || bad+=("$v")
  done
  if ((${#bad[@]})); then
    fail "비어 있거나 자리표시자가 남은 값: ${bad[*]} ($ENV_FILE)"
    exit 1
  fi

  out=$(<"$NGINX_TEMPLATE")
  for v in "${vars[@]}"; do
    out=${out//"\${$v}"/"${!v}"}
  done
  # 목록 밖의 ${…} 가 남았다면 템플릿과 위 목록이 어긋난 것이다.
  if [[ $out =~ \$\{[A-Z_]+\} ]]; then
    fail "치환되지 않은 값이 템플릿에 남았습니다: ${BASH_REMATCH[0]}"
    exit 1
  fi
  printf '%s\n' "$out"
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
    archive_logs "${COMPOSE_APP[@]}"
    (cd deploy && COMPOSE_PROFILES="$PROFILES" "${COMPOSE_APP[@]}" down)
    stop_log_follow
    ok "앱 스택 중지 (Redis 는 유지)"
    ;;

  --stop-all)
    load_env
    export REDIS_BIND_ADDR=${REDIS_BIND_ADDR:-${REDIS_HOST:-127.0.0.1}}
    archive_logs "${COMPOSE_APP[@]}"
    (cd deploy && COMPOSE_PROFILES="$PROFILES" "${COMPOSE_APP[@]}" down)
    stop_log_follow
    (cd deploy && "${COMPOSE_REDIS[@]}" down)
    ok "전체 중지 (볼륨은 보존)"
    ;;

  --nginx-conf)
    load_env
    render_nginx_conf
    ;;

  -h|--help)
    # 파일 상단 주석 블록만 출력한다(첫 비주석 라인에서 중단).
    sed -n '2,/^[^#]/p' "$0" | sed -e '$d' -e 's/^# \?//'
    ;;

  *)
    load_env
    RUN_ARGS=("$@")   # check_env 의 rp_will_start 판정에 쓰인다
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
