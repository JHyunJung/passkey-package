#!/usr/bin/env bash
# README "5. 환경 설정" — 시크릿 3종 생성. 키 회전 시 다시 실행할 수 있다
# (재실행하면 이전 값은 무효가 되므로, 기동 중이면 앱도 함께 재기동해야 한다).
#   ./gen-secrets.sh                        # 화면 출력
#   ./gen-secrets.sh --write                # deploy/.env 의 해당 항목을 직접 채움
#   ./gen-secrets.sh --write --keep-backup  # 위와 같되 .env.bak 을 남긴다
set -euo pipefail
cd "$(dirname "$0")"

# 이 스크립트가 만드는 파일(.env.bak, sed 임시파일)은 시크릿을 담는다.
# umask 로 처음부터 600 으로 만들어, chmod 전까지의 노출 창을 없앤다.
umask 077

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

# 인자는 순서와 무관하게 받는다. --keep-backup 을 먼저 쓰면 --write 가 아닌
# 것으로 보여 시크릿이 화면에 출력되는 사고를 막는다.
WRITE=0
KEEP_BACKUP=0
for arg in "$@"; do
  case $arg in
    --write)       WRITE=1 ;;
    --keep-backup) KEEP_BACKUP=1 ;;
    *) echo "알 수 없는 옵션: $arg" >&2
       echo "사용법: ./gen-secrets.sh [--write [--keep-backup]]" >&2; exit 1 ;;
  esac
done
# --keep-backup 은 --write 의 부수효과를 고르는 옵션이다. 단독으로 쓰면
# 사용자는 파일 기록을 기대했는데 화면에 시크릿이 찍히게 된다.
if (( KEEP_BACKUP && ! WRITE )); then
  echo "--keep-backup 은 --write 와 함께 써야 합니다." >&2
  echo "사용법: ./gen-secrets.sh [--write [--keep-backup]]" >&2; exit 1
fi

if (( WRITE )); then
  ENV_FILE=deploy/.env
  [[ -f $ENV_FILE ]] || { echo "$ENV_FILE 이 없습니다. cp deploy/.env.qa-template deploy/.env 먼저 실행" >&2; exit 1; }
  # 백업과 sed 임시파일에는 "이전 세대" 시크릿이 그대로 남는다. 먼저 권한을
  # 좁히고, 중간에 실패해도 임시파일이 디스크에 남지 않도록 trap 을 건다.
  cp "$ENV_FILE" "$ENV_FILE.bak"
  chmod 600 "$ENV_FILE.bak"
  trap 'rm -f "$ENV_FILE.tmp"' EXIT
  # 값에 / 와 & 가 들어갈 수 있으므로 구분자를 | 로 두고 &, | 를 이스케이프한다.
  esc(){ printf '%s' "$1" | sed -e 's/[&|\\]/\\&/g'; }
  # setup.sh 의 put() 과 같은 형식으로 작은따옴표를 씌운다. base64 알파벳에는
  # 따옴표가 없으므로 이스케이프는 필요 없지만, 형식은 파일 전체에서 일관해야
  # start.sh 의 source 와 compose 가 같은 값을 본다.
  sed -i.tmp \
    -e "s|^MASTER_KEY=.*|MASTER_KEY='$(esc "$MASTER_KEY")'|" \
    -e "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD='$(esc "$REDIS_PASSWORD")'|" \
    -e "s|^RP_RELAY_SECRET=.*|RP_RELAY_SECRET='$(esc "$RP_RELAY_SECRET")'|" \
    "$ENV_FILE"
  rm -f "$ENV_FILE.tmp"
  chmod 600 "$ENV_FILE"

  # .bak 에는 "이전 세대" 시크릿 평문이 그대로 남는다. 사람에게 삭제를 안내만
  # 하면 키 회전 시 구·신 키가 같은 디렉터리에 무기한 공존한다. 기본은 자동
  # 삭제하고, 롤백 수단이 필요하면 --keep-backup 으로 명시적으로 남긴다.
  if (( KEEP_BACKUP )); then
    echo "deploy/.env 에 기록했습니다."
    echo "※ deploy/.env.bak 에 이전 값이 남아 있습니다. 확인 후 삭제하세요:"
    echo "     shred -u deploy/.env.bak 2>/dev/null || rm -f deploy/.env.bak"
  else
    # shred 는 coreutils 에 있으나 없는 환경도 있으므로 rm 으로 폴백한다.
    shred -u "$ENV_FILE.bak" 2>/dev/null || rm -f "$ENV_FILE.bak"
    echo "deploy/.env 에 기록했습니다(이전 값이 든 .env.bak 은 삭제)."
  fi

  # 값은 출력하지 않는다. 터미널 스크롤백·script/tee 로그·화면 캡처에
  # 마스터 키가 남는 것을 막는다. 값은 파일에서 직접 확인한다.
  echo "  MASTER_KEY · REDIS_PASSWORD · RP_RELAY_SECRET 3개 항목을 기록했습니다(값은 파일에서 확인)."
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
