# 단일 인스턴스 배포 가이드 (RP 서버 + Passkey 서버, 서브도메인 분리)

> **⚠️ 이 문서는 호스트에 JDK 를 설치하고 `gradlew bootRun` 으로 기동하는
> 단일 인스턴스 테스트 환경 절차입니다.**
> 컨테이너 기반 운영 배포는 [`deploy/README.md`](../deploy/README.md) 를 참고하세요.
> 서브도메인 분리 근거(WebAuthn rpId)와 환경변수 설명은 이 문서가 여전히 유효합니다.

하나의 서버 인스턴스에서 **RP 서버(rp-app)** 와 **Passkey 서버(passkey-app)** 를 함께 기동하되, nginx 리버스 프록시로 **서브도메인을 분리**해 둘 다 HTTPS(443)로 노출하는 테스트 환경 구성 문서입니다.

> **왜 포트가 아니라 서브도메인인가요?**
> 패스키(WebAuthn)는 `rpId`를 **도메인 기준**으로 묶고 포트는 무시합니다. 같은 도메인에 포트만 다르게 두면(예: `:443` vs `:8443`) RP 서버와 Passkey 서버가 같은 `rpId`를 공유하게 되어 신뢰 경계와 ID Token issuer가 모호해집니다. 서브도메인으로 나누면 둘 다 깔끔히 443으로 노출되고 역할이 분리됩니다.

## 0. 목표 구성

```
                          인터넷 (HTTPS 443)
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                      ▼
  https://rp-dev.crosscert.com          https://dev-passkey.crosscert.com
  (RP 서버 — 브라우저가 접속)            (Passkey 서버 — RP가 서버-투-서버 호출)
            │                                      │
            │          ── 같은 인스턴스 ──          │
            ▼  nginx 리버스 프록시 (TLS 종료)       ▼
     localhost:9090 (rp-app)            localhost:8080 (passkey-app)
            │                                      │
            └──────── X-API-Key (서버-투-서버) ─────┘
                               │
                    Oracle XE + Redis (Docker)
```

| 구성요소 | 외부 도메인 | 내부 포트 | 역할 |
|---|---|---|---|
| RP 서버 (rp-app) | `https://rp-dev.crosscert.com` | `9090` | 브라우저/앱이 접속. 패스키 `rpId`가 이 도메인. |
| Passkey 서버 (passkey-app) | `https://dev-passkey.crosscert.com` | `8080` | RP가 X-API-Key로 호출. ID Token issuer. |
| Oracle XE / Redis | (외부 비노출) | `1521` / `6379` | DB / 캐시 |
| admin 콘솔 (admin-app) | (선택, 내부망) | `8081` | 테넌트·API key 발급 |

이 문서에서 `rp-dev.crosscert.com` / `dev-passkey.crosscert.com`은 예시입니다. 실제 보유 도메인으로 바꾸세요.

---

## 1. 사전 준비

### 1.1 DNS

두 서브도메인이 **이 인스턴스의 공인 IP**를 가리키도록 A 레코드를 등록합니다.

```
rp-dev.crosscert.com.        A   <인스턴스 공인 IP>
dev-passkey.crosscert.com.   A   <인스턴스 공인 IP>
```

### 1.2 TLS 인증서

두 서브도메인 모두 인증서가 필요합니다. Let's Encrypt 기준:

```bash
sudo certbot certonly --nginx \
  -d rp-dev.crosscert.com \
  -d dev-passkey.crosscert.com
# 인증서: /etc/letsencrypt/live/rp-dev.crosscert.com/{fullchain,privkey}.pem
#         /etc/letsencrypt/live/dev-passkey.crosscert.com/{fullchain,privkey}.pem
```

### 1.3 필수 도구

| 도구 | 용도 |
|---|---|
| Docker | Oracle XE + Redis |
| JDK 17 | passkey-app / admin-app / rp-app |
| nginx | 리버스 프록시 + TLS 종료 |

---

## 2. 인프라 기동 (Oracle + Redis)

저장소 루트에서:

```bash
docker compose up -d
# 첫 부팅 시 스키마/유저 부트스트랩 (정본 러너 — PDB 명 주입을 대신 처리한다)
scripts/run-bootstrap.sh
# 기본 PDB 가 XEPDB1 이 아니면: ORA_SERVICE=<PDB명> scripts/run-bootstrap.sh
```

`bootstrap-schema.sql` 을 sqlplus 로 직접 파이프하지 마세요 — 이 SQL 은 `&ora_service`
치환에 의존하는데 in-file `DEFINE` 이 없어 호출측(`run-bootstrap.sh`)의 주입이 유일한
정의입니다. 컨테이너부터 새로 만드는 경우는 `scripts/init-dev-db.sh` 가 compose 재생성 →
healthy 대기 → 부트스트랩 → Flyway 시드까지 한 번에 처리합니다.

Oracle XE가 ready 되기까지 30~60초 걸립니다.

---

## 3. 애플리케이션 기동

세 서버를 **prod 프로파일**로 기동합니다. 핵심은 **passkey-app 에 자신의 외부 도메인을 알려주는 것**(`PASSKEY_ID_TOKEN_ISSUER_BASE` — ID Token `iss` 의 베이스)과, rp-app 이 그 값을 `PASSKEY_ISSUER_BASE` 로 동일하게 바라보게 하는 것입니다. **RP 서버의 origin 을 알려주는 환경변수는 없습니다** — RP origin 은 테넌트의 `allowedOrigins`(§4)로 제어됩니다.

### 3.1 Passkey 서버 (passkey-app, 내부 8080)

```bash
SPRING_PROFILES_ACTIVE=prod \
SERVER_PORT=8080 \
SPRING_DATASOURCE_URL='jdbc:oracle:thin:@//localhost:1521/XEPDB1' \
SPRING_DATASOURCE_USERNAME=PSK_APP_RUNTIME_USER \
SPRING_DATASOURCE_PASSWORD='<런타임 비번>' \
SPRING_DATA_REDIS_HOST=localhost \
PASSKEY_KEY_ENVELOPE_MASTER_KEY='<base64 32바이트 마스터키>' \
PASSKEY_ID_TOKEN_ISSUER_BASE='https://dev-passkey.crosscert.com' \
./gradlew :passkey-app:bootRun
```

| 환경변수 | 값 | 설명 |
|---|---|---|
| `PASSKEY_ID_TOKEN_ISSUER_BASE` | `https://dev-passkey.crosscert.com` | ID Token `iss`의 베이스. **Passkey 서버의 외부 도메인**(끝에 슬래시 없이). |
| `PASSKEY_KEY_ENVELOPE_MASTER_KEY` | base64 32바이트 | at-rest 암호화 키. `openssl rand -base64 32`로 생성. |

> ℹ️ **forward-headers 는 주입하지 않습니다**: `SERVER_FORWARD_HEADERS_STRATEGY` 를 따로 설정할 필요가 없습니다 — passkey-app/admin-app 의 base `application.yml` 에 `forward-headers-strategy: framework` 가 이미 있습니다. env 로 주입하면 오히려 그 값을 덮어씁니다. 필요한 것은 프록시가 `X-Forwarded-For` 와 `X-Forwarded-Proto` 를 보내는지(§5)뿐입니다 — 전자가 감사로그 클라이언트 IP·rate-limit IP 버킷의 근거(`request.getRemoteAddr()`)가 되고, 후자가 스킴(`request.isSecure()`) 판정으로 쿠키 Secure 를 결정합니다.

### 3.2 admin 콘솔 (admin-app, 내부 8081) — 테넌트/키 발급용

테넌트와 API key를 발급하려면 admin-app도 기동합니다(내부망에서만 접근, 외부 노출 불필요).

```bash
SPRING_PROFILES_ACTIVE=prod \
SERVER_PORT=8081 \
SPRING_DATASOURCE_URL='jdbc:oracle:thin:@//localhost:1521/XEPDB1' \
SPRING_DATASOURCE_USERNAME=PSK_APP_ADMIN_USER \
SPRING_DATASOURCE_PASSWORD='<admin 비번>' \
SPRING_DATA_REDIS_HOST=localhost \
PASSKEY_KEY_ENVELOPE_MASTER_KEY='<passkey-app 과 동일한 마스터키>' \
./gradlew :admin-app:bootRun
```

### 3.3 RP 서버 (rp-app, 내부 9090)

**§4에서 테넌트·API key를 발급한 뒤** 그 값으로 기동합니다.

```bash
SPRING_PROFILES_ACTIVE=prod \
SERVER_PORT=9090 \
PASSKEY_BASE_URL='https://dev-passkey.crosscert.com' \
PASSKEY_ISSUER_BASE='https://dev-passkey.crosscert.com' \
PASSKEY_TENANT_ID='<§4에서 만든 tenantId>' \
PASSKEY_API_KEY='<§4에서 발급한 X-API-Key>' \
RP_RELAY_SECRET='<openssl rand -base64 32 로 생성한 키>' \
./gradlew :rp-app:bootRun
```

| 환경변수 | 값 | 설명 |
|---|---|---|
| `PASSKEY_BASE_URL` | `https://dev-passkey.crosscert.com` | Passkey 서버 호출 주소. |
| `PASSKEY_ISSUER_BASE` | `https://dev-passkey.crosscert.com` | ID Token `iss` 검증용. passkey-app의 `PASSKEY_ID_TOKEN_ISSUER_BASE`와 **동일**해야 함. |
| `PASSKEY_TENANT_ID` | tenantId | §4에서 만든 테넌트. **UUID 대시 형식**(`7f00dead-0000-...`) 권장 — ID Token 의 `iss`/`aud` 가 이 형식이라. RAW hex 도 rp-app 가 정규화해 받지만, 외부 검증 시스템은 UUID 형식을 기대한다. |
| `PASSKEY_API_KEY` | `pk_...` | §4에서 발급한 키. |
| `RP_RELAY_SECRET` | base64 32바이트 | 등록 relay 바인딩용 HMAC 키. `openssl rand -base64 32` 로 생성. **prod 에서 필수** — `application-prod.yml` 은 로깅 레벨만 정의하고 이 값은 base `application.yml` 의 `${RP_RELAY_SECRET:}` 폴백을 쓰므로, 주입하지 않으면 데모 기본 키가 되고 `RelayKeyGuard` 가 기동을 중단시킨다(`rp.relay.secret 이 데모 기본 키입니다`). 가드가 데모 키를 허용하는 프로필은 `dev`/`local` 뿐이다. |

> **origin 검증은 RP 서버가 아니라 Passkey 서버가 합니다.** RP 서버의 외부 origin(`https://rp-dev.crosscert.com`)이 패스키 등록/인증에서 검증되는데, 그 비교 기준은 **테넌트의 `allowedOrigins`**(§4)입니다. 즉 RP 서버 자체에는 origin을 알려줄 필요가 없고(설정 항목 없음), **테넌트 `allowedOrigins`를 RP 서버의 실제 origin과 정확히 맞추는 것이 핵심**입니다.

---

## 4. 테넌트 생성 — rpId / allowedOrigins 설정 (핵심)

패스키가 RP 서버 도메인에 올바로 묶이려면 테넌트의 `rpId`와 `allowedOrigins`를 **RP 서버의 외부 도메인 기준**으로 정확히 설정해야 합니다.

### 4.1 rpId / allowedOrigins 규칙

| 항목 | 값 | 규칙 |
|---|---|---|
| `rpId` | `rp-dev.crosscert.com` | **RP 서버의 도메인만**(스킴·포트 없음). 패스키가 이 도메인에 묶임. |
| `allowedOrigins` | `["https://rp-dev.crosscert.com"]` | **RP 서버의 전체 origin**(스킴 포함, 포트는 443이면 생략). 등록/인증 ceremony에서 브라우저 origin과 정확히 대조됨. |

**중요한 관계**:
- `rpId`는 **도메인**, `allowedOrigins`는 **`https://` + 도메인**(+ 비표준 포트면 `:포트`)입니다.
- nginx가 443으로 받으므로 origin은 `https://rp-dev.crosscert.com`(포트 표기 없음)입니다. **여기에 `:9090` 같은 내부 포트를 넣으면 안 됩니다** — 브라우저가 보는 건 외부 origin(443)입니다.
- **서브도메인 매칭**: `rpId`를 상위 도메인(`crosscert.com`)으로 잡으면 `rp-dev.crosscert.com`·`app.crosscert.com` 등 모든 서브도메인을 커버합니다. 반대로 `rp-dev.crosscert.com`으로 좁히면 그 서브도메인에서만 패스키가 동작합니다. **테스트 RP만 쓸 거면 `rp-dev.crosscert.com`으로 좁히는 것을 권장**합니다.
- `allowedOrigins`는 여러 개 등록 가능합니다(예: `www.` + `app.`을 모두 쓰는 RP).

### 4.2 admin 콘솔로 테넌트 생성

admin-app에 로그인(`https://<admin 내부주소>:8081/admin/`, form 로그인 — seed 운영자 계정)한 뒤, 테넌트를 생성합니다. API로 직접 만들려면:

`POST /admin/login` 을 포함해 모든 POST 는 CSRF 보호 대상입니다(면제는 가입 요청·비밀번호
재설정 두 경로뿐). 토큰은 `XSRF-TOKEN` 쿠키로 내려오고(HttpOnly 아님, path `/admin`),
같은 값을 `X-XSRF-TOKEN` 헤더로 되돌려줘야 합니다. 로그인 시 토큰이 회전하므로 로그인
직후 인증된 GET 을 한 번 끼워 확정 토큰을 받는 4단계가 됩니다(통합테스트
`AdminFlowIT` 가 쓰는 것과 같은 순서):

```bash
# 0) XSRF-TOKEN 쿠키 획득 (미인증 접근 가능한 엔드포인트)
curl -s -c cookies.txt http://localhost:8081/admin/api/profile > /dev/null
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' cookies.txt)

# 1) admin 세션 쿠키 획득 (form 로그인)
curl -b cookies.txt -c cookies.txt -X POST http://localhost:8081/admin/login \
  -H "X-XSRF-TOKEN: $XSRF" \
  -d 'email=alice@crosscert.com&password=<운영자 비번>'

# 2) 워밍업 GET — 로그인 시 CSRF 토큰이 회전하고 토큰 발급이 지연(deferred)되므로,
#    인증된 세션으로 한 번 왕복해 서버의 확정 토큰을 쿠키 jar 에 받아둔다
curl -s -b cookies.txt -c cookies.txt http://localhost:8081/admin/api/me > /dev/null
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' cookies.txt)

# 3) 테넌트 생성 (rpId / allowedOrigins 가 핵심)
curl -b cookies.txt -X POST http://localhost:8081/admin/api/tenants \
  -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $XSRF" \
  -d '{
    "slug": "rp-dev",
    "displayName": "RP Dev",
    "rpId": "rp-dev.crosscert.com",
    "rpName": "RP Dev",
    "allowedOrigins": ["https://rp-dev.crosscert.com"],
    "acceptedFormats": ["none", "packed"],
    "requireUserVerification": false,
    "mdsRequired": false,
    "attestationConveyance": "NONE",
    "webauthnTimeoutMs": 60000
  }'
# 응답 data.id 가 tenantId — rp-app 의 PASSKEY_TENANT_ID 로 사용
```

**테넌트 생성 요청 필드**:

| 필드 | 예시 | 설명 |
|---|---|---|
| `slug` | `rp-dev` | 소문자/숫자/하이픈, 2~63자. URL 식별자. |
| `displayName` / `rpName` | `RP Dev` | 표시 이름. |
| `rpId` | `rp-dev.crosscert.com` | **RP 서버 도메인** (§4.1). |
| `allowedOrigins` | `["https://rp-dev.crosscert.com"]` | **RP 서버 origin 목록** (§4.1). |
| `acceptedFormats` | `["none","packed"]` | 허용 attestation 포맷. |
| `requireUserVerification` | `false` | UV 필수 여부. |
| `mdsRequired` | `false` | MDS 검증 필수 여부. |
| `attestationConveyance` | `NONE` | `NONE`/`INDIRECT`/`DIRECT`/`ENTERPRISE`. |
| `webauthnTimeoutMs` | `60000` | ceremony 타임아웃(1000~600000). |

### 4.3 API key 발급

생성한 테넌트에 대해 `registration`·`authentication` scope를 가진 API key를 발급합니다(admin 콘솔의 API Keys 탭, 또는 `POST /admin/api/api-keys`). 발급 시 **평문 키는 1회만 표시**되므로 즉시 복사해 rp-app의 `PASSKEY_API_KEY`로 씁니다.

---

## 5. nginx 리버스 프록시 설정

두 서브도메인을 각각 내부 포트로 프록시합니다. `/etc/nginx/sites-available/passkey.conf`:

```nginx
# ── RP 서버 (rp-app) ──────────────────────────────────────
server {
    listen 443 ssl;
    http2 on;
    server_name rp-dev.crosscert.com;

    ssl_certificate     /etc/letsencrypt/live/rp-dev.crosscert.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/rp-dev.crosscert.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:9090;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;   # https — 쿠키 Secure 판정(isSecure)에 필수
        proxy_set_header X-Forwarded-Host  $host;
    }
}

# ── Passkey 서버 (passkey-app) ──────────────────────────────
server {
    listen 443 ssl;
    http2 on;
    server_name dev-passkey.crosscert.com;

    ssl_certificate     /etc/letsencrypt/live/dev-passkey.crosscert.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/dev-passkey.crosscert.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;
    }
}

# ── HTTP → HTTPS 리다이렉트 ─────────────────────────────────
server {
    listen 80;
    server_name rp-dev.crosscert.com dev-passkey.crosscert.com;
    return 301 https://$host$request_uri;
}
```

활성화:

```bash
sudo ln -s /etc/nginx/sites-available/passkey.conf /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

> **`X-Forwarded-For` 와 `X-Forwarded-Proto` 를 둘 다 보내야 합니다.** 역할이 다릅니다.
>
> - **`X-Forwarded-For`** → `request.getRemoteAddr()` 이 실제 클라이언트 IP 를 가리키게 합니다. 감사로그의 IP 와 rate-limit 의 IP 버킷 분리가 이 값을 씁니다. 없으면 모든 요청이 프록시 IP 하나로 뭉칩니다.
> - **`X-Forwarded-Proto`** → `request.isSecure()` 가 "외부에서 https로 들어왔다"를 인식하게 합니다. 세션/CSRF 쿠키의 Secure 자동판정이 여기에 달려 있습니다.
>
> 앱 쪽 설정은 base `application.yml` 의 `forward-headers-strategy: framework` 로 이미 되어 있어 별도 주입이 필요 없습니다(admin-app 은 `application-prod.yml` 에서 세션 쿠키 `secure: true` 를 명시해 헤더가 누락돼도 fail-closed).
>
> 다만 **패스키 origin 검증은 이 헤더와 무관합니다.** WebAuthn 은 브라우저가 서명한 `clientDataJSON` 의 origin 을 테넌트 `allowed_origins` 와 대조합니다(서버가 자기 요청 URL 에서 origin 을 유도하지 않습니다). 등록이 origin 문제로 실패하면 §4.1 의 `allowedOrigins` 를 브라우저 주소창 origin(443, 포트 표기 없음)과 정확히 맞추세요.

---

## 6. 동작 확인

1. **Passkey 서버 JWKS**(외부에서 접근 가능해야 함):
   ```bash
   curl -s https://dev-passkey.crosscert.com/.well-known/jwks.json
   # {"keys":[{"kty":"RSA",...}]}
   ```
2. **RP 서버 페이지**: 브라우저에서 `https://rp-dev.crosscert.com/register` 접속 → 패스키 등록 → `https://rp-dev.crosscert.com/login` 로그인.
3. **전체 사이클**: 등록 → 로그아웃 → 인증이 모두 성공하면 구성 완료입니다.

---

## 7. 자주 만나는 문제

| 증상 | 원인 | 해결 |
|---|---|---|
| 등록 시 `C001`(origin 관련) | 테넌트 `allowedOrigins`가 실제 origin과 불일치 | `allowedOrigins`를 정확히 `https://rp-dev.crosscert.com`(포트 없음)으로. 브라우저 주소창의 origin과 글자까지 일치해야 함. |
| `iss mismatch` 로그인 실패 | passkey-app `PASSKEY_ID_TOKEN_ISSUER_BASE` ≠ rp-app `PASSKEY_ISSUER_BASE` | 둘을 `https://dev-passkey.crosscert.com`으로 동일하게. |
| 등록이 origin 불일치로 실패 | 브라우저 `clientDataJSON` 의 origin 과 테넌트 `allowed_origins` 불일치 | §4.1 의 `allowedOrigins` 를 브라우저 주소창 origin(`https://rp-dev.crosscert.com`, 포트 표기 없음)과 정확히 맞춤. `SERVER_FORWARD_HEADERS_STRATEGY` 는 주입 대상이 아니다(base `application.yml` 이 `framework` 로 고정). |
| 패스키가 다른 서브도메인에서 안 됨 | `rpId`를 좁게 설정함 | 여러 서브도메인을 쓰려면 `rpId`를 상위 도메인(`crosscert.com`)으로, `allowedOrigins`에 각 origin 추가. |
| JWKS 404/타임아웃 | nginx가 `dev-passkey` 서브도메인을 8080으로 프록시 안 함 | §5 nginx 설정·DNS 확인. |
| 401 (RP→Passkey 호출) | `PASSKEY_API_KEY` 형식/scope 오류 | prefix+secret 이어붙임 확인, 키에 registration·authentication scope 있는지 확인. |
| rp-app 부팅 실패 — `rp.relay.secret 이 데모 기본 키입니다` | `RP_RELAY_SECRET` 미주입 (dev/local 외 프로필은 데모 키 거부) | §3.3 처럼 `RP_RELAY_SECRET='<openssl rand -base64 32>'` 를 주입. |

---

## 참고: 더 간단한 로컬 테스트

도메인·TLS 없이 로컬에서만 빠르게 확인하려면 [dev-setup.md](dev-setup.md)를 따르세요(`localhost` 특례로 HTTP에서도 패스키가 동작합니다). 이 문서는 **공인 도메인 + HTTPS** 환경에서 RP/Passkey 서버를 한 인스턴스에 올릴 때 쓰는 구성입니다.
