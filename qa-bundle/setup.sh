#!/usr/bin/env bash
# .env 대화형 작성 — 묻는 값은 6개뿐이고 나머지는 전부 유도한다.
#
#   ./setup.sh              # 질문에 답하면 deploy/.env 가 완성된다
#   ./setup.sh --rp-keys    # 테넌트 생성 후 RP_TENANT_ID / RP_API_KEY 만 채운다
#
# 손편집(vi)을 없애는 것이 목적이다. 자리표시자를 남기거나, 도메인 4곳 중
# 한 곳만 고치거나, REDIS_HOST 에 127.0.0.1 을 넣는 사고가 여기서 사라진다.
set -euo pipefail
cd "$(dirname "$0")"

# 이 스크립트가 새로 만드는 파일(.env 최초 생성, .env.bak)은 시크릿을 담는다.
# umask 로 생성 시점부터 600 이 되게 한다. 단 umask 는 신규 파일에만 듣는다 —
# 기존 .env 를 sed -i 로 고칠 때는 원본 모드가 그대로 유지되므로(GNU sed 실측)
# 그 경로는 아래 chmod 600 이 실제 보장이다.
umask 077

TEMPLATE=deploy/.env.qa-template
ENV_FILE=deploy/.env

RED=$'\033[31m'; YEL=$'\033[33m'; GRN=$'\033[32m'; DIM=$'\033[2m'; RST=$'\033[0m'
[[ -t 1 ]] || { RED=; YEL=; GRN=; DIM=; RST=; }
ok(){ echo "${GRN}[OK]${RST} $*"; }
warn(){ echo "${YEL}[??]${RST} $*"; }
die(){ echo "${RED}[!!]${RST} $*" >&2; exit 1; }

# 값에 & | \ 가 섞여도 sed 가 깨지지 않게 이스케이프한다(gen-secrets.sh 와 동일).
esc(){ printf '%s' "$1" | sed -e 's/[&|\\]/\\&/g'; }

# 값이 홀수 개의 백슬래시로 끝나는가. compose 의 .env 파서는 작은따옴표 안의
# \ 도 이스케이프로 취급해, 홀수면 닫는 따옴표를 삼키고 파일 전체를 거부한다.
# 짝수(\\)는 양쪽 파서가 같은 값을 보므로 허용한다.
bad_trailing_backslash() {
  local t=$1 n=0
  while [[ $t == *\\ ]]; do t=${t%\\}; n=$((n+1)); done
  (( n % 2 == 1 ))
}

# key=value 를 .env 에 기록한다. 이미 있으면 치환, 없으면 추가.
#
# 값은 작은따옴표로 감싼다. .env 를 읽는 쪽이 둘이기 때문이다.
#   - start.sh  : set -a; source .env   (셸 파서)
#   - compose   : --env-file 의 자체 파서
# 무인용으로 적으면 공백·$·백틱이 든 비밀번호를 셸이 해석해, source 는
# 명령 치환·구문오류를 내고 compose 는 리터럴로 읽어 **서로 다른 값**을 본다.
# 작은따옴표로 감싸면 양쪽이 같은 리터럴을 본다(공백·$·백틱·\·" 전수 확인).
#
# 단, 두 가지 값만은 두 파서가 합의하는 표기가 존재하지 않아 거부한다.
#   1) 작은따옴표 — 셸의 '\'' 는 compose 가 파일 전체를 거부하고
#      (unexpected character), compose 가 받는 '\' 나 "..." 는 셸이 다르게 읽는다.
#   2) 끝의 홀수 개 백슬래시 — compose 는 '…\' 의 \ 를 이스케이프로 보아
#      닫는 따옴표를 삼키고 "unterminated quoted value" 로 파일 전체를 거부한다.
#      (셸은 작은따옴표 안의 \ 를 리터럴로 읽어 정상 통과 → 조용한 불일치)
# 둘 다 조용한 불일치보다 입력 시점 거부가 안전하다(ask 에서도 미리 막는다).
put() {
  local k=$1 v=$2
  # 개행이 남아 있으면 sed 가 "unescaped newline" 으로 깨진다. 방어적으로 제거.
  v=${v//$'\n'/}; v=${v//$'\r'/}
  [[ $v == *"'"* ]] && die "$k 값에 작은따옴표(')는 쓸 수 없습니다."
  bad_trailing_backslash "$v" && die "$k 값은 백슬래시(\\)로 끝날 수 없습니다."
  if grep -qE "^${k}=" "$ENV_FILE"; then
    sed -i.tmp "s|^${k}=.*|${k}='$(esc "$v")'|" "$ENV_FILE" && rm -f "$ENV_FILE.tmp"
  else
    printf "%s='%s'\n" "$k" "$v" >> "$ENV_FILE"
  fi
}

# 필수 입력. 빈 값이면 다시 묻는다.
# 반환값은 stdout 으로만 내보낸다. 프롬프트·안내는 전부 stderr 로 보내야
# $( ) 치환에 섞이지 않는다(섞이면 값에 개행이 들어가 sed 가 깨진다).
ask() {
  local prompt=$1 default=${2:-} out
  while :; do
    if [[ -n $default ]]; then
      read -r -p "  $prompt [$default]: " out || die "입력이 중단되었습니다."
      out=${out:-$default}
    else
      read -r -p "  $prompt: " out || die "입력이 중단되었습니다."
    fi
    if [[ -z $out ]]; then
      echo "     ${DIM}값을 입력하세요.${RST}" >&2
    elif [[ $out == *"'"* ]]; then
      # put() 참고 — source 와 compose 가 합의하는 표기가 없다.
      echo "     ${DIM}작은따옴표(')는 쓸 수 없습니다. 다시 입력하세요.${RST}" >&2
    elif bad_trailing_backslash "$out"; then
      echo "     ${DIM}백슬래시(\\)로 끝날 수 없습니다. 다시 입력하세요.${RST}" >&2
    else
      printf '%s' "$out"; return
    fi
  done
}

# 비밀번호. 화면에 남지 않게 -s 로 받고 한 번 더 확인한다.
ask_secret() {
  local prompt=$1 a b
  while :; do
    read -r -s -p "  $prompt: " a || die "입력이 중단되었습니다."; echo >&2
    [[ -n $a ]] || { echo "     ${DIM}값을 입력하세요.${RST}" >&2; continue; }
    # put() 참고 — source 와 compose 가 합의하는 표기가 없다.
    [[ $a == *"'"* ]] && {
      echo "     ${DIM}작은따옴표(')는 쓸 수 없습니다. 다시 입력하세요.${RST}" >&2; continue; }
    bad_trailing_backslash "$a" && {
      echo "     ${DIM}백슬래시(\\)로 끝날 수 없습니다. 다시 입력하세요.${RST}" >&2; continue; }
    read -r -s -p "  $prompt (확인): " b || die "입력이 중단되었습니다."; echo >&2
    [[ $a == "$b" ]] && { printf '%s' "$a"; return; }
    echo "     ${DIM}일치하지 않습니다. 다시 입력하세요.${RST}" >&2
  done
}

# ---------------------------------------------------------------------------
# --rp-keys: 테넌트를 만든 뒤 RP 값 2개만 채운다
# ---------------------------------------------------------------------------
if [[ ${1:-} == --rp-keys ]]; then
  [[ -f $ENV_FILE ]] || die "$ENV_FILE 이 없습니다. 먼저 ./setup.sh 를 실행하세요."
  chmod 600 "$ENV_FILE"   # 손복사한 .env 가 644 일 수 있다 — 시크릿을 쓰기 전에 조인다
  echo
  echo "admin 콘솔에서 발급한 값을 입력합니다."
  echo "${DIM}  (API 키 평문은 발급 시 1회만 표시됩니다)${RST}"
  echo
  TENANT=$(ask "RP_TENANT_ID (테넌트 응답의 data.id)")
  APIKEY=$(ask_secret "RP_API_KEY (평문 키)")
  put RP_TENANT_ID "$TENANT"
  put RP_API_KEY   "$APIKEY"
  chmod 600 "$ENV_FILE"
  echo
  ok "기록했습니다."
  echo
  echo "다음:  ./start.sh rp-app"
  exit 0
fi

# ---------------------------------------------------------------------------
# 기본 흐름
# ---------------------------------------------------------------------------
[[ -f $TEMPLATE ]] || die "$TEMPLATE 이 없습니다."

if [[ -f $ENV_FILE ]]; then
  warn "$ENV_FILE 이 이미 있습니다."
  read -r -p "  덮어쓸까요? 기존 값은 사라집니다 [y/N]: " yn || true
  [[ ${yn:-} == [yY] ]] || { echo "취소했습니다."; exit 0; }
  cp "$ENV_FILE" "$ENV_FILE.bak"; chmod 600 "$ENV_FILE.bak"
  echo "  ${DIM}기존 파일을 $ENV_FILE.bak 으로 백업했습니다.${RST}"
fi

cp "$TEMPLATE" "$ENV_FILE"
chmod 600 "$ENV_FILE"

cat <<'INTRO'

===========================================================================
 QA 환경 설정 — 6가지만 입력하면 .env 가 완성됩니다
===========================================================================
INTRO

echo
echo "${DIM}--- 1/3. Oracle DB (DBA 에게 받은 값) ---${RST}"
ORA_HOST=$(ask "Oracle 호스트 또는 IP")
ORA_PORT=$(ask "Oracle 포트" "1521")
ORA_SVC=$(ask  "Oracle 서비스명 (SID 아님)")
echo
echo "  ${DIM}계정 3개의 비밀번호를 입력합니다(입력은 화면에 표시되지 않습니다).${RST}"
PW_OWNER=$(ask_secret   "PSK_APP_OWNER 비밀번호        ")
PW_ADMIN=$(ask_secret   "PSK_APP_ADMIN_USER 비밀번호   ")
PW_RUNTIME=$(ask_secret "PSK_APP_RUNTIME_USER 비밀번호 ")

echo
echo "${DIM}--- 2/3. 도메인 ---${RST}"
echo "  ${DIM}WebAuthn 은 HTTPS 가 필수입니다. 앞단 LB 가 TLS 를 종료해야 합니다.${RST}"
PASSKEY_FQDN=$(ask "passkey 서버 FQDN" "passkey-qa.example.internal")
RP_FQDN=$(ask      "RP 데모 FQDN     " "rp-qa.example.internal")

echo
echo "${DIM}--- 3/3. Redis ---${RST}"
# docker0 게이트웨이를 기본값으로 제시한다. 컨테이너에서 호스트를 가리키는
# 주소여야 하므로 127.0.0.1 은 쓸 수 없다.
DOCKER0=$(ip -4 addr show docker0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 || true)
echo "  ${DIM}컨테이너 안에서 127.0.0.1 은 자기 자신입니다. 호스트 IP 를 씁니다.${RST}"
REDIS_IP=$(ask "호스트 내부 IP" "${DOCKER0:-172.17.0.1}")
if [[ $REDIS_IP == 127.0.0.1 || $REDIS_IP == localhost ]]; then
  die "127.0.0.1 은 쓸 수 없습니다(컨테이너가 자기 자신을 봅니다). 호스트 IP 를 지정하세요."
fi

# ---------------------------------------------------------------------------
# 기록 — 입력 6종에서 13개 항목을 유도한다
# ---------------------------------------------------------------------------
echo
echo "${DIM}--- 기록 중 ---${RST}"

put DB_URL "jdbc:oracle:thin:@//${ORA_HOST}:${ORA_PORT}/${ORA_SVC}"
put DB_OWNER_PASSWORD   "$PW_OWNER"
put DB_ADMIN_PASSWORD   "$PW_ADMIN"
put DB_RUNTIME_PASSWORD "$PW_RUNTIME"

# FQDN 입력 2개에서 4곳이 유도된다 — passkey FQDN 하나가 아래 3곳
# (PASSKEY_SERVER_NAME / ISSUER_BASE / ADMIN_INVITE_BASE_URL)을 채우고,
# RP FQDN 은 별도 입력이라 RP_SERVER_NAME 1곳만 채운다.
# 손편집 시 한 곳만 고치는 사고를 막는다.
put PASSKEY_SERVER_NAME   "$PASSKEY_FQDN"
put ISSUER_BASE           "https://${PASSKEY_FQDN}"
put ADMIN_INVITE_BASE_URL "https://${PASSKEY_FQDN}"
put RP_SERVER_NAME        "$RP_FQDN"

put REDIS_HOST      "$REDIS_IP"
put REDIS_BIND_ADDR "$REDIS_IP"

chmod 600 "$ENV_FILE"
ok "deploy/.env 작성 완료"

# 시크릿 3종은 사람이 만들 이유가 없다. 여기서 바로 생성한다.
echo
echo "${DIM}--- 시크릿 생성 (MASTER_KEY / REDIS_PASSWORD / RP_RELAY_SECRET) ---${RST}"
# gen-secrets.sh 가 .env.bak(이전 세대 시크릿)을 스스로 삭제한다. 위에서
# "백업했습니다" 라고 알린 파일이 여기서 사라지므로 그 사실도 알린다.
HAD_BACKUP=0; [[ -f $ENV_FILE.bak ]] && HAD_BACKUP=1 || true
./gen-secrets.sh --write >/dev/null
chmod 600 "$ENV_FILE"
ok "시크릿 3종 기록"
(( HAD_BACKUP )) && ok "이전 세대 시크릿이 든 $ENV_FILE.bak 삭제" || true

# ---------------------------------------------------------------------------
echo
echo "==========================================================================="
if ./start.sh --check; then
  cat <<EOF

다음:  ./start.sh

  기동 후 admin 콘솔에서 테넌트와 API 키를 만든 뒤
      ./setup.sh --rp-keys      값 2개 입력
      ./start.sh rp-app         RP 데모 기동
EOF
else
  echo
  warn "검증에서 문제가 발견되었습니다. 위 [!!] 항목을 확인하세요."
  echo "     다시 설정하려면: ./setup.sh"
  exit 1
fi
