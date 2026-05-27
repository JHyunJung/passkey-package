#!/usr/bin/env bash
set -euo pipefail

ADMIN_BASE="${ADMIN_BASE:-http://localhost:8081}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin}"
RP_ID="${RP_ID:-localhost}"
RP_NAME="${RP_NAME:-Sample RP}"
ORIGIN="${ORIGIN:-http://localhost:9090}"
ISSUER_BASE="${ISSUER_BASE:-http://localhost:8080}"
ENV_FILE="$(dirname "$0")/../.env"

command -v jq   >/dev/null || { echo "✗ jq 가 필요합니다 (brew install jq)"; exit 1; }
command -v curl >/dev/null || { echo "✗ curl 이 필요합니다"; exit 1; }

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

# 0. admin 로그인
echo "→ admin 로그인 (${ADMIN_BASE})"
curl -fsS -c "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  "$ADMIN_BASE/admin/api/auth/login" >/dev/null

# 1. demo tenant
echo "→ demo tenant 생성"
TENANT_PAYLOAD=$(jq -nc \
  --arg id "sample-rp-demo" --arg name "Sample RP Demo" \
  --arg rpId "$RP_ID" --arg rpName "$RP_NAME" --arg origin "$ORIGIN" \
  '{tenantId:$id, displayName:$name, rpId:$rpId, rpName:$rpName,
    allowedOrigins:[$origin],
    attestationPolicy:{acceptedFormats:["none","packed"],
                       requireUserVerification:true, mdsRequired:false}}')

TENANT_RESP=$(curl -fsS -b "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d "$TENANT_PAYLOAD" "$ADMIN_BASE/admin/api/tenants")
TENANT_ID=$(echo "$TENANT_RESP" | jq -r '.data.tenantId // .data.id')

# 2. demo API key
echo "→ demo API key 발급"
KEY_RESP=$(curl -fsS -b "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"sample-rp-bootstrap\",
       \"scopes\":[\"passkey:register\",\"passkey:authenticate\"]}" \
  "$ADMIN_BASE/admin/api/api-keys")
API_KEY=$(echo "$KEY_RESP" | jq -r '.data.plaintextKey')

# 3. .env
[ -f "$ENV_FILE" ] && cp "$ENV_FILE" "$ENV_FILE.bak"
cat > "$ENV_FILE" <<EOF
PASSKEY_BASE_URL=http://localhost:8080
PASSKEY_TENANT_ID=$TENANT_ID
PASSKEY_API_KEY=$API_KEY
PASSKEY_ISSUER_BASE=$ISSUER_BASE
SAMPLE_RP_ORIGIN=$ORIGIN
EOF

echo "✓ sample-rp/.env written"
echo "  tenantId    : $TENANT_ID"
echo "  apiKey      : ${API_KEY:0:8}…  (full value in .env, 1회만 노출)"
echo "  issuerBase  : $ISSUER_BASE"
echo ""
echo "Reminder: passkey-app 이 같은 issuer-base 로 떠 있어야 ID Token iss 검증이 통과합니다."
echo "  Passkey2: ./gradlew :passkey-app:bootRun --args=\"--passkey.id-token.issuer-base=$ISSUER_BASE\""
echo "  또는 passkey-app/application-local.yml 에 passkey.id-token.issuer-base 를 추가."

# 4. health check
echo "→ X-API-Key 헬스체크"
curl -fsS -H "X-API-Key: $API_KEY" \
  "http://localhost:8080/api/v1/rp/authentication/start" \
  -H 'Content-Type: application/json' -d '{}' \
  | jq -e '.success == true' >/dev/null \
  && echo "✓ X-API-Key validated against passkey-app" \
  || { echo "✗ X-API-Key validation failed — admin-app 응답 확인"; exit 1; }

echo
echo "다음: cd examples/sample-rp && set -a && source .env && set +a && ./gradlew bootRun"
