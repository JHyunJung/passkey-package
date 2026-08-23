#!/usr/bin/env bash
# 폐쇄망 Rocky Linux 9.7 (x86_64) 에 Docker 정적 바이너리 + Compose v2 를 설치한다.
# 가이드 §2 전체를 그대로 자동화한 것으로, 같은 서버에서 여러 번 실행해도 안전하다.
#
#   sudo ./install-docker.sh
set -euo pipefail

DOCKER_TGZ="${DOCKER_TGZ:-docker-29.7.2.tgz}"
COMPOSE_BIN="${COMPOSE_BIN:-docker-compose-linux-x86_64}"
cd "$(dirname "$0")"

[[ $EUID -eq 0 ]] || { echo "root 로 실행하세요: sudo $0" >&2; exit 1; }
[[ -f $DOCKER_TGZ ]]  || { echo "$DOCKER_TGZ 가 없습니다" >&2; exit 1; }
[[ -f $COMPOSE_BIN ]] || { echo "$COMPOSE_BIN 이 없습니다" >&2; exit 1; }

# iptables 는 정적 바이너리에 포함되지 않는다. 없으면 Docker 네트워킹이 죽는다.
if ! command -v iptables >/dev/null 2>&1; then
  echo "!! iptables 가 없습니다. 가이드 §1 의 ISO 로컬 저장소로 먼저 설치하세요." >&2
  exit 1
fi

# 반입 과정에서 파일이 깨졌는지 먼저 확인한다(load-images.sh 와 동일한 방식).
if [[ -f SHA256SUMS ]] && command -v sha256sum >/dev/null 2>&1; then
  echo "==> [0/5] 무결성 검증"
  sha256sum -c SHA256SUMS
fi

echo "==> [1/5] 바이너리 배치"
tar xzf "$DOCKER_TGZ"
install -m 0755 docker/* /usr/bin/
rm -rf docker

echo "==> [2/5] Compose v2 플러그인"
install -d /usr/local/lib/docker/cli-plugins
install -m 0755 "$COMPOSE_BIN" /usr/local/lib/docker/cli-plugins/docker-compose

echo "==> [3/5] 그룹 / 디렉터리 / daemon.json"
groupadd --system docker 2>/dev/null || true
install -d /etc/docker /var/lib/docker /var/lib/containerd
# 로그 로테이션이 없으면 컨테이너 로그가 무한히 쌓여 디스크를 채운다.
if [[ ! -f /etc/docker/daemon.json ]]; then
  cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "100m", "max-file": "5" }
}
JSON
else
  echo "    (기존 /etc/docker/daemon.json 유지)"
fi

echo "==> [4/5] systemd 유닛"
cat > /etc/systemd/system/containerd.service <<'UNIT'
[Unit]
Description=containerd container runtime
After=network.target local-fs.target

[Service]
ExecStartPre=-/sbin/modprobe overlay
ExecStart=/usr/bin/containerd
Type=notify
Delegate=yes
KillMode=process
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/systemd/system/docker.socket <<'UNIT'
[Unit]
Description=Docker Socket for the API

[Socket]
ListenStream=/run/docker.sock
SocketMode=0660
SocketUser=root
SocketGroup=docker

[Install]
WantedBy=sockets.target
UNIT

cat > /etc/systemd/system/docker.service <<'UNIT'
[Unit]
Description=Docker Application Container Engine
After=network-online.target docker.socket containerd.service
Wants=network-online.target
Requires=docker.socket containerd.service

[Service]
Type=notify
ExecStart=/usr/bin/dockerd -H fd:// --containerd=/run/containerd/containerd.sock
ExecReload=/bin/kill -s HUP $MAINPID
LimitNOFILE=infinity
TimeoutStartSec=0
Delegate=yes
KillMode=process
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
UNIT

modprobe overlay 2>/dev/null || true
systemctl daemon-reload
systemctl enable --now containerd
systemctl enable --now docker.socket
systemctl enable --now docker

echo "==> [5/5] 검증"
docker version --format 'docker {{.Server.Version}}' || true
docker compose version
docker info --format 'Storage: {{.Driver}} / Cgroup: {{.CgroupVersion}} / Root: {{.DockerRootDir}}'

STORAGE=$(docker info --format '{{.Driver}}')
[[ $STORAGE == overlay2 ]] || echo "!! Storage Driver 가 $STORAGE 입니다. overlay 모듈 확인 필요(가이드 §2.6)."

echo
echo "완료. sudo 없이 쓰려면: sudo usermod -aG docker <계정> 후 재로그인"
