#!/usr/bin/env bash
# 설치 전 사전 점검 (README "1. 사전 점검"). 읽기 전용이며 아무것도 바꾸지 않는다.
#   ./preflight.sh [Oracle호스트] [Oracle포트]
set -uo pipefail

ORA_HOST="${1:-}"; ORA_PORT="${2:-1521}"
ok(){ echo "  [OK]   $*"; }; warn(){ echo "  [!!]   $*"; }; info(){ echo "  [ -- ] $*"; }

echo "== 1. 플랫폼 =="
ARCH=$(uname -m)
[[ $ARCH == x86_64 ]] && ok "arch=$ARCH" || warn "arch=$ARCH — 이 번들의 이미지/바이너리는 x86_64 전용입니다"
[[ -f /etc/rocky-release ]] && ok "$(cat /etc/rocky-release)" || info "Rocky 아님: $(uname -sr)"

echo "== 2. iptables (없으면 Docker 네트워킹 불가) =="
command -v iptables >/dev/null 2>&1 && ok "$(iptables --version 2>/dev/null | head -1)" \
  || warn "미설치 — README \"2. Docker 설치\" 의 ISO 로컬 저장소로 iptables 설치 필요"

echo "== 3. SELinux =="
SE=""   # 8. 호스트 nginx 점검에서도 쓴다
if command -v getenforce >/dev/null 2>&1; then
  SE=$(getenforce)
  if [[ $SE == Enforcing ]]; then
    rpm -q container-selinux >/dev/null 2>&1 \
      && ok "Enforcing + container-selinux 설치됨" \
      || warn "Enforcing 인데 container-selinux 없음 — 볼륨 마운트 permission denied 발생(README \"2. Docker 설치\")"
  else ok "SELinux=$SE"; fi
else info "getenforce 없음"; fi

echo "== 4. firewalld =="
if systemctl is-active --quiet firewalld 2>/dev/null; then
  warn "활성 — docker0 을 trusted zone 에 넣으세요(README \"2. Docker 설치\")"
else ok "비활성"; fi

echo "== 5. 디스크 (/var 20GB+ 권장) =="
# GNU coreutils 의 --output 이 없는 환경(busybox 등)을 대비해 POSIX df 로 폴백한다.
AVAIL=$(df -BG --output=avail /var 2>/dev/null | tail -1 | tr -dc '0-9')
if [[ -z ${AVAIL:-} ]]; then
  # POSIX df: 4번째 컬럼이 available(1K 블록). GB 로 환산한다.
  AVAIL=$(df -Pk /var 2>/dev/null | awk 'NR==2 {print int($4/1024/1024)}')
fi
if [[ -n ${AVAIL:-} ]]; then
  [[ $AVAIL -ge 20 ]] && ok "/var 여유 ${AVAIL}GB" || warn "/var 여유 ${AVAIL}GB — 20GB 이상 권장"
else
  info "/var 여유 공간 확인 실패 — df 출력을 직접 확인하세요"
fi

echo "== 6. Oracle 도달성 =="
if [[ -n $ORA_HOST ]]; then
  timeout 5 bash -c ": < /dev/tcp/$ORA_HOST/$ORA_PORT" 2>/dev/null \
    && ok "$ORA_HOST:$ORA_PORT 연결됨" || warn "$ORA_HOST:$ORA_PORT 연결 실패"
else info "인자 없음 — ./preflight.sh <OracleIP> [포트] 로 확인 가능"
fi

echo "== 7. MDS 아웃바운드 (선택, README 문제 해결) =="
timeout 6 bash -c ": < /dev/tcp/mds3.fidoalliance.org/443" 2>/dev/null \
  && ok "mds3.fidoalliance.org:443 열림" \
  || info "차단됨 — mdsRequired=false 로 QA 진행 가능(README 문제 해결)"

echo "== 8. 호스트 nginx (PROXY_MODE=host — 서버에 있는 nginx 가 TLS 종료) =="
if command -v nginx >/dev/null 2>&1; then
  ok "$(nginx -v 2>&1)"
  # Enforcing 이면 nginx → 앱 루프백 포트 연결이 기본 차단돼 502 가 난다.
  if [[ ${SE:-} == Enforcing ]]; then
    getsebool httpd_can_network_connect 2>/dev/null | grep -q -- '--> on' \
      && ok "SELinux httpd_can_network_connect = on" \
      || warn "SELinux httpd_can_network_connect = off — nginx 가 앱으로 못 넘겨 502 (sudo setsebool -P httpd_can_network_connect 1)"
  fi
  # 앱이 잡을 호스트 포트(.env.qa-template 기본값). 기존 서버라 다른 서비스가 쓰고 있을 수 있다.
  for P in 8080 19090 8081; do
    if ss -Hltn "sport = :$P" 2>/dev/null | grep -q .; then
      warn "포트 $P 사용 중 — deploy/.env 의 PASSKEY_/RP_/ADMIN_HOST_PORT 를 비어 있는 값으로"
    else ok "포트 $P 비어 있음"; fi
  done
else
  info "nginx 없음 — 번들 nginx(PROXY_MODE=bundled)를 쓸 때는 무관"
fi

echo; echo "점검 완료. [!!] 항목을 해결한 뒤 install-docker.sh 를 실행하세요."
