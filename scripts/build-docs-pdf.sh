#!/usr/bin/env bash
#
# build-docs-pdf.sh — docs/rp-server-api.md 를 PDF 로 변환한다.
#
# 기존 PDF 와 같은 엔진(wkhtmltopdf 0.12.6)·스타일(scripts/docs-pdf.css)을 쓴다.
# md → HTML(marked) → PDF(wkhtmltopdf) 파이프라인. 한글 폰트는 css 의
# 폰트 스택(Noto Sans KR → Apple SD Gothic Neo)으로 처리.
#
# 사전 요구:
#   - wkhtmltopdf (https://github.com/wkhtmltopdf/packaging/releases, 0.12.6 macos-cocoa.pkg)
#   - node/npx (marked 는 npx 로 자동)
#
# 사용법:
#   scripts/build-docs-pdf.sh                       # docs/rp-server-api.md → docs/rp-server-api.pdf
#   scripts/build-docs-pdf.sh docs/other.md         # 임의 md 변환(같은 디렉토리에 .pdf)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CSS="${SCRIPT_DIR}/docs-pdf.css"

SRC="${1:-docs/rp-server-api.md}"
cd "${REPO_ROOT}"
[ -f "${SRC}" ] || { echo "ERROR: 원본 md 없음: ${SRC}" >&2; exit 1; }
OUT="${SRC%.md}.pdf"

# --- 사전 점검 ---
if ! command -v wkhtmltopdf >/dev/null 2>&1; then
  echo "ERROR: wkhtmltopdf 가 PATH 에 없습니다." >&2
  echo "       https://github.com/wkhtmltopdf/packaging/releases 에서 0.12.6 macos-cocoa.pkg 설치 후 재시도." >&2
  exit 1
fi

TMP_HTML="$(mktemp -t docs-pdf.XXXXXX).html"
trap 'rm -f "${TMP_HTML}"' EXIT

echo "==> [1/2] md → HTML (marked)"
# marked 로 본문 HTML 생성 → css 를 인라인한 완전한 HTML 문서로 감싼다.
{
  printf '%s\n' '<!DOCTYPE html><html lang="ko"><head><meta charset="utf-8"><style>'
  cat "${CSS}"
  printf '%s\n' '</style></head><body>'
  npx --yes marked --gfm "${SRC}"
  printf '%s\n' '</body></html>'
} > "${TMP_HTML}"

echo "==> [2/2] HTML → PDF (wkhtmltopdf)"
wkhtmltopdf \
  --encoding utf-8 \
  --enable-local-file-access \
  --page-size A4 \
  --margin-top 18mm --margin-bottom 18mm --margin-left 16mm --margin-right 16mm \
  --print-media-type \
  "${TMP_HTML}" "${OUT}" 2>&1 | grep -vE "^Loading|^Counting|^Resolving|^Rendering|\[=*>?\s*\]" || true

echo "==> ✅ 완료: ${OUT}"
ls -la "${OUT}"
