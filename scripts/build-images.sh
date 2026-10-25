#!/usr/bin/env bash
# 앱 이미지(passkey-app, admin-app, rp-app)를 서버용 linux/amd64 로 빌드하고,
# 아키텍처를 확인한 뒤 QA 반입 번들(qa-bundle/images)에 tar.gz 로 저장하고
# SHA256SUMS 를 다시 계산한다.
#
#   scripts/build-images.sh                       # 3개 전부
#   scripts/build-images.sh passkey-app           # 특정 앱만 (재배포 시)
#   IMAGE_TAG=0.0.2 scripts/build-images.sh       # 태그 지정 (기본 0.0.1-SNAPSHOT)
#   OUT_DIR=/tmp/images scripts/build-images.sh   # 저장 위치 지정 (기본 qa-bundle/images)
#   NO_SAVE=1 scripts/build-images.sh             # 빌드와 아키텍처 확인만 (레지스트리로 push 할 때)
#
# infra.tar.gz(nginx + redis)는 만들지 않는다 — 그 버전을 바꿀 때만 따로 갱신한다.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

PLATFORM=linux/amd64
TAG="${IMAGE_TAG:-0.0.1-SNAPSHOT}"
OUT_DIR="${OUT_DIR:-qa-bundle/images}"
ALL=(passkey-app admin-app rp-app)

# 인자가 없으면 전부, 있으면 지정된 것만.
if [[ $# -eq 0 ]]; then
  TARGETS=("${ALL[@]}")
else
  TARGETS=("$@")
  for t in "${TARGETS[@]}"; do
    [[ " ${ALL[*]} " == *" $t "* ]] || {
      echo "알 수 없는 이름: $t (가능: ${ALL[*]})" >&2; exit 2; }
  done
fi

for app in "${TARGETS[@]}"; do
  echo "==> $app:$TAG 빌드 ($PLATFORM)"
  started=$SECONDS
  docker build --platform "$PLATFORM" -t "$app:$TAG" -f "$app/Dockerfile" .

  arch=$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$app:$TAG")
  if [[ $arch != "$PLATFORM" ]]; then
    echo "$app:$TAG 아키텍처가 $arch (기대: $PLATFORM) — 서버에서 exec format error 가 난다" >&2
    exit 1
  fi
  echo "  [OK]   $app:$TAG ($arch, $((SECONDS - started))초)"
done

if [[ -n ${NO_SAVE:-} ]]; then
  echo "==> NO_SAVE 지정 — tar.gz 저장 생략"
  exit 0
fi

mkdir -p "$OUT_DIR"
for app in "${TARGETS[@]}"; do
  echo "==> $OUT_DIR/$app.tar.gz 저장"
  # 중간에 실패해도 이전 tarball 이 반쯤 쓴 파일로 바뀌지 않도록 임시 파일에 쓴 뒤 옮긴다.
  # gzip -n: 시각을 넣지 않아 같은 이미지를 다시 저장하면 같은 파일이 된다.
  # (다시 빌드하면 캐시를 다 써도 이미지 ID 가 바뀌므로 해시도 바뀐다 — 정상)
  docker save "$app:$TAG" | gzip -n > "$OUT_DIR/$app.tar.gz.tmp"
  mv "$OUT_DIR/$app.tar.gz.tmp" "$OUT_DIR/$app.tar.gz"
done

# load-images.sh 가 sha256sum -c 로 검증한다. 이번에 빌드한 앱의 줄만 새 해시로 바꾸고,
# 나머지(infra 등) 줄은 그대로 둔다 — 로컬에 그 tarball 이 없어도 서버로 함께 가는 파일일 수 있다.
hash_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi
}
echo "==> $OUT_DIR/SHA256SUMS 갱신"
(
  cd "$OUT_DIR"
  for name in passkey-app admin-app rp-app infra; do
    if [[ " ${TARGETS[*]} " == *" $name "* ]]; then
      hash_of "$name.tar.gz"
    elif [[ -f SHA256SUMS ]]; then
      grep " $name.tar.gz\$" SHA256SUMS || true
    fi
  done > SHA256SUMS.tmp
  mv SHA256SUMS.tmp SHA256SUMS
  sed 's/^/  /' SHA256SUMS
)

# load-images.sh 는 태그를 직접 적어 두고 있다 — 다르면 서버에서 적재 검증이 실패한다.
for app in "${TARGETS[@]}"; do
  if ! grep -q "\"$app:$TAG\"" qa-bundle/load-images.sh; then
    echo "주의: qa-bundle/load-images.sh 에 $app:$TAG 가 없다. 태그를 바꿨다면 그 파일의 images_of 와" >&2
    echo "      qa-bundle/deploy/.env.qa-template 의 IMAGE_TAG 도 함께 고친다." >&2
  fi
done
