#!/usr/bin/env bash
set -euo pipefail

# 환경 변수
ADMIN_BASE="${ADMIN_BASE:-http://localhost:8081}"
ADMIN_EMAIL="${ADMIN_EMAIL:-alice@crosscert.com}"
ADMIN_PASS="${ADMIN_PASS:-alice-temp-pw}"     # V11 시드 비밀번호. 운영 환경에선 반드시 회전.
RP_ID="${RP_ID:-localhost}"
RP_NAME="${RP_NAME:-Sample RP}"
ORIGIN="${ORIGIN:-http://localhost:9090}"
ISSUER_BASE="${ISSUER_BASE:-http://localhost:8080}"
TENANT_SLUG="${TENANT_SLUG:-rp-app-demo}"
ENV_FILE="$(dirname "$0")/../.env"

command -v jq   >/dev/null || { echo "✗ jq 가 필요합니다 (brew install jq)"; exit 1; }
command -v curl >/dev/null || { echo "✗ curl 이 필요합니다"; exit 1; }

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

# admin-app 은 CSRF + form login 을 사용한다. 로그인 직전에 GET /admin/api/me
# 같은 보호 엔드포인트를 한 번 쳐서 XSRF-TOKEN 쿠키를 발급받는다.
echo "→ CSRF 토큰 준비"
curl -fsS -o /dev/null -c "$COOKIE_JAR" "$ADMIN_BASE/admin/api/me" || true
CSRF=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR")
if [ -z "$CSRF" ]; then
    echo "✗ XSRF-TOKEN 쿠키를 얻지 못했습니다 — admin-app 이 ${ADMIN_BASE} 에 떠 있는지 확인"
    exit 1
fi

# 0. admin 로그인 (form POST, params email/password, CSRF 헤더 포함)
echo "→ admin 로그인 (${ADMIN_BASE} as ${ADMIN_EMAIL})"
curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -H "X-XSRF-TOKEN: $CSRF" \
  --data-urlencode "email=$ADMIN_EMAIL" \
  --data-urlencode "password=$ADMIN_PASS" \
  "$ADMIN_BASE/admin/login" >/dev/null

# 로그인 후 CSRF 토큰이 회전될 수 있으니 다시 추출
CSRF=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR")

# 1. demo tenant
echo "→ demo tenant 생성 (slug=$TENANT_SLUG)"
TENANT_PAYLOAD=$(jq -nc \
  --arg slug "$TENANT_SLUG" --arg name "Sample RP Demo" \
  --arg rpId "$RP_ID" --arg rpName "$RP_NAME" --arg origin "$ORIGIN" \
  '{slug:$slug, displayName:$name, rpId:$rpId, rpName:$rpName,
    allowedOrigins:[$origin],
    acceptedFormats:["none","packed"],
    requireUserVerification:true,
    mdsRequired:false}')

TENANT_RESP=$(curl -fsS -b "$COOKIE_JAR" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -H 'Content-Type: application/json' \
  -d "$TENANT_PAYLOAD" "$ADMIN_BASE/admin/api/tenants")
TENANT_ID=$(echo "$TENANT_RESP" | jq -r '.data.id')
if [ -z "$TENANT_ID" ] || [ "$TENANT_ID" = "null" ]; then
    echo "✗ tenant 생성 실패: $TENANT_RESP"
    exit 1
fi

# 2. demo API key  (tenantId 는 UUID)
echo "→ demo API key 발급"
KEY_RESP=$(curl -fsS -b "$COOKIE_JAR" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"rp-app-bootstrap\",
       \"scopes\":[\"registration\",\"authentication\"]}" \
  "$ADMIN_BASE/admin/api/api-keys")
API_KEY=$(echo "$KEY_RESP" | jq -r '.data.plainText')
if [ -z "$API_KEY" ] || [ "$API_KEY" = "null" ]; then
    echo "✗ API key 발급 실패: $KEY_RESP"
    exit 1
fi

# 3. .env 작성
# RP_RELAY_SECRET: 등록 relay 토큰 HMAC 서명 키. RelayKeyGuard 는 프로필 미지정(env-only)을
# 운영으로 간주해 데모 기본 키를 거부하므로, no-profile `./gradlew bootRun` 데모 경로에서도
# 부팅이 되도록 강한 무작위 키를 생성해 .env 에 박는다. (재실행 시 매번 새 키가 발급된다.)
RP_RELAY_SECRET="${RP_RELAY_SECRET:-$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 48)}"
[ -f "$ENV_FILE" ] && cp "$ENV_FILE" "$ENV_FILE.bak"
cat > "$ENV_FILE" <<EOF
PASSKEY_BASE_URL=http://localhost:8080
PASSKEY_TENANT_ID=$TENANT_ID
PASSKEY_API_KEY=$API_KEY
PASSKEY_ISSUER_BASE=$ISSUER_BASE
RP_APP_ORIGIN=$ORIGIN
RP_RELAY_SECRET=$RP_RELAY_SECRET
EOF

echo "✓ rp-app/.env written"
echo "  tenantId(UUID): $TENANT_ID"
echo "  tenant slug   : $TENANT_SLUG"
echo "  apiKey        : ${API_KEY:0:8}…  (전체값은 .env, 1회만 노출)"
echo "  issuerBase    : $ISSUER_BASE"
echo ""
echo "Reminder: passkey-app 의 passkey.id-token.issuer-base 가 같은 값이어야 합니다."
echo "  Passkey2: ./gradlew :passkey-app:bootRun --args=\"--passkey.id-token.issuer-base=$ISSUER_BASE\""
echo "  또는 Passkey2/passkey-app/src/main/resources/application-local.yml 에 추가."

# 4. health check — X-API-Key 가 실제 동작하는지 (envelope success=true 확인)
echo "→ X-API-Key 헬스체크"
HEALTH_RESP=$(curl -fsS -H "X-API-Key: $API_KEY" \
  -H 'Content-Type: application/json' -d '{}' \
  "http://localhost:8080/api/v1/rp/authentication/start")

if echo "$HEALTH_RESP" | jq -e '.success == true' >/dev/null 2>&1; then
    echo "✓ X-API-Key validated against passkey-app"
else
    echo "✗ X-API-Key validation failed:"
    echo "$HEALTH_RESP" | jq '.' 2>/dev/null || echo "$HEALTH_RESP"
    exit 1
fi

echo
echo "다음: cd examples/rp-app && set -a && source .env && set +a && ./gradlew bootRun"
