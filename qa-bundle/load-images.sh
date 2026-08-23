#!/usr/bin/env bash
# 반입한 이미지 tarball 을 로드하고 아키텍처가 맞는지 검증한다.
#
#   ./load-images.sh                     # 4개 전부 로드
#   ./load-images.sh passkey-app         # 특정 앱만 로드(재배포 시)
#   ./load-images.sh passkey-app admin-app
#
# 앱을 따로 재배포할 때는 해당 tar.gz 하나만 서버로 옮겨 그 이름으로 실행하면 된다.
set -euo pipefail
cd "$(dirname "$0")"

ALL=(passkey-app admin-app rp-app infra)

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

# 이름 -> 그 tarball 이 담고 있는 이미지 태그
images_of() {
  case "$1" in
    passkey-app) echo "passkey-app:0.0.1-SNAPSHOT" ;;
    admin-app)   echo "admin-app:0.0.1-SNAPSHOT" ;;
    rp-app)      echo "rp-app:0.0.1-SNAPSHOT" ;;
    infra)       echo "nginx:1.27-alpine redis:7-alpine" ;;
  esac
}

echo "==> 무결성 검증"
if [[ -f images/SHA256SUMS ]]; then
  # 지정된 것만 골라 검증한다(전체 파일이 없을 수도 있으므로).
  ( cd images
    for t in "${TARGETS[@]}"; do
      grep " $t.tar.gz\$" SHA256SUMS | sha256sum -c -
    done )
else
  echo "  (images/SHA256SUMS 없음 — 검증 건너뜀)"
fi

echo "==> 이미지 로드"
for t in "${TARGETS[@]}"; do
  f="images/$t.tar.gz"
  [[ -f $f ]] || { echo "$f 없음" >&2; exit 1; }
  echo "  - $f"
  gunzip -c "$f" | docker load
done

echo "==> 검증"
FAIL=0
for t in "${TARGETS[@]}"; do
  for img in $(images_of "$t"); do
    if ARCH=$(docker image inspect "$img" --format '{{.Architecture}}' 2>/dev/null); then
      if [[ $ARCH == amd64 ]]; then echo "  [OK]   $img ($ARCH)"
      else echo "  [!!]   $img 아키텍처가 $ARCH — x86_64 서버에서 exec format error 발생"; FAIL=1; fi
    else
      echo "  [!!]   $img 없음"; FAIL=1
    fi
  done
done
[[ $FAIL -eq 0 ]] || { echo; echo "누락/불일치가 있습니다. 기동하면 실패합니다." >&2; exit 1; }

echo
if [[ ${#TARGETS[@]} -eq ${#ALL[@]} ]]; then
  echo "이미지 5개 모두 정상(amd64)."
else
  echo "지정한 이미지 정상(amd64): ${TARGETS[*]}"
fi
