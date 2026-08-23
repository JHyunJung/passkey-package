#!/usr/bin/env bash
# 가이드 §5 — 시크릿 3종 생성. 한 번만 실행하고 값을 안전하게 보관한다.
#   ./gen-secrets.sh            # 화면 출력
#   ./gen-secrets.sh --write    # deploy/.env 의 해당 항목을 직접 채움
set -euo pipefail
cd "$(dirname "$0")"

# MASTER_KEY 는 base64 디코딩 시 정확히 32바이트여야 한다. 아니면 AES 키
# 로드에 실패해 앱이 뜨지 않는다.
MASTER_KEY=$(openssl rand -base64 32)
# tr 로 base64 기호(+/=)를 걸러내므로 결과는 29~32자로 변동한다. Redis 비밀번호는
# 길이 고정이 필요 없고 29자(영숫자)여도 충분히 강하므로 그대로 쓴다.
REDIS_PASSWORD=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | cut -c1-32)
RP_RELAY_SECRET=$(openssl rand -base64 32)

# 자기검증: 디코딩 길이가 32바이트인지 확인한다.
LEN=$(printf '%s' "$MASTER_KEY" | base64 -d 2>/dev/null | wc -c | tr -d ' ')
[[ $LEN == 32 ]] || { echo "MASTER_KEY 길이 이상: ${LEN}바이트" >&2; exit 1; }

if [[ ${1:-} == --write ]]; then
  ENV_FILE=deploy/.env
  [[ -f $ENV_FILE ]] || { echo "$ENV_FILE 이 없습니다. cp deploy/.env.qa-template deploy/.env 먼저 실행" >&2; exit 1; }
  # 백업과 sed 임시파일에는 "이전 세대" 시크릿이 그대로 남는다. 먼저 권한을
  # 좁히고, 중간에 실패해도 임시파일이 디스크에 남지 않도록 trap 을 건다.
  cp "$ENV_FILE" "$ENV_FILE.bak"
  chmod 600 "$ENV_FILE.bak"
  trap 'rm -f "$ENV_FILE.tmp"' EXIT
  # 값에 / 와 & 가 들어갈 수 있으므로 구분자를 | 로 두고 &, | 를 이스케이프한다.
  esc(){ printf '%s' "$1" | sed -e 's/[&|\\]/\\&/g'; }
  sed -i.tmp \
    -e "s|^MASTER_KEY=.*|MASTER_KEY=$(esc "$MASTER_KEY")|" \
    -e "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=$(esc "$REDIS_PASSWORD")|" \
    -e "s|^RP_RELAY_SECRET=.*|RP_RELAY_SECRET=$(esc "$RP_RELAY_SECRET")|" \
    "$ENV_FILE"
  rm -f "$ENV_FILE.tmp"
  chmod 600 "$ENV_FILE"
  echo "deploy/.env 에 기록했습니다."
  echo "※ deploy/.env.bak 에는 이전 값이 남아 있습니다. 확인 후 삭제하세요:"
  echo "     shred -u deploy/.env.bak 2>/dev/null || rm -f deploy/.env.bak"
  grep -E '^(MASTER_KEY|REDIS_PASSWORD|RP_RELAY_SECRET)=' "$ENV_FILE"
  echo
  echo "※ Redis 스택 기동 시에도 같은 REDIS_PASSWORD 를 export 해야 합니다."
else
  echo "MASTER_KEY=$MASTER_KEY"
  echo "REDIS_PASSWORD=$REDIS_PASSWORD"
  echo "RP_RELAY_SECRET=$RP_RELAY_SECRET"
  echo
  echo "(MASTER_KEY base64 디코딩 = ${LEN}바이트 — 정상)"
  echo "deploy/.env 에 자동 반영하려면: ./gen-secrets.sh --write"
fi
