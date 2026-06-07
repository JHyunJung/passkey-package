# 단일 인스턴스 배포 가이드 (RP 서버 + Passkey 서버, 서브도메인 분리)

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
# 첫 부팅 시 VPD 스키마 부트스트랩
docker exec -i passkey-oracle sqlplus -s / as sysdba < scripts/bootstrap-vpd.sql
```

Oracle XE가 ready 되기까지 30~60초 걸립니다.

---

## 3. 애플리케이션 기동

세 서버를 **prod 프로파일**로 기동합니다. 핵심은 **각 서버에 자신의 외부 도메인을 알려주는 환경변수**입니다.

### 3.1 Passkey 서버 (passkey-app, 내부 8080)

```bash
SPRING_PROFILES_ACTIVE=prod \
SERVER_PORT=8080 \
SPRING_DATASOURCE_URL='jdbc:oracle:thin:@//localhost:1521/XEPDB1' \
SPRING_DATASOURCE_USERNAME=APP_RUNTIME_USER \
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

> ⚠️ **forward-headers 필수**: nginx 뒤에서 origin/스킴을 올바로 인식하려면 prod에서 `SERVER_FORWARD_HEADERS_STRATEGY=NATIVE`(또는 `FRAMEWORK`)를 함께 설정하세요. 프록시가 보내는 `X-Forwarded-Proto: https`를 신뢰해야 secure 쿠키·origin 판정이 맞습니다.

### 3.2 admin 콘솔 (admin-app, 내부 8081) — 테넌트/키 발급용

테넌트와 API key를 발급하려면 admin-app도 기동합니다(내부망에서만 접근, 외부 노출 불필요).

```bash
SPRING_PROFILES_ACTIVE=prod \
SERVER_PORT=8081 \
SPRING_DATASOURCE_URL='jdbc:oracle:thin:@//localhost:1521/XEPDB1' \
SPRING_DATASOURCE_USERNAME=APP_ADMIN_USER \
SPRING_DATASOURCE_PASSWORD='<admin 비번>' \
SPRING_DATA_REDIS_HOST=localhost \
PASSKEY_KEY_ENVELOPE_MASTER_KEY='<passkey-app 과 동일한 마스터키>' \
./gradlew :admin-app:bootRun
```

### 3.3 RP 서버 (rp-app, 내부 9090)

**§4에서 테넌트·API key를 발급한 뒤** 그 값으로 기동합니다.

```bash
SERVER_PORT=9090 \
PASSKEY_BASE_URL='https://dev-passkey.crosscert.com' \
PASSKEY_ISSUER_BASE='https://dev-passkey.crosscert.com' \
PASSKEY_TENANT_ID='<§4에서 만든 tenantId>' \
PASSKEY_API_KEY='<§4에서 발급한 X-API-Key>' \
SERVER_FORWARD_HEADERS_STRATEGY=NATIVE \
./gradlew :rp-app:bootRun
```

| 환경변수 | 값 | 설명 |
|---|---|---|
| `PASSKEY_BASE_URL` | `https://dev-passkey.crosscert.com` | Passkey 서버 호출 주소. |
| `PASSKEY_ISSUER_BASE` | `https://dev-passkey.crosscert.com` | ID Token `iss` 검증용. passkey-app의 `PASSKEY_ID_TOKEN_ISSUER_BASE`와 **동일**해야 함. |
| `PASSKEY_TENANT_ID` | tenantId | §4에서 만든 테넌트. **UUID 대시 형식**(`7f00dead-0000-...`) 권장 — ID Token 의 `iss`/`aud` 가 이 형식이라. RAW hex 도 rp-app 가 정규화해 받지만, 외부 검증 시스템은 UUID 형식을 기대한다. |
| `PASSKEY_API_KEY` | `pk_...` | §4에서 발급한 키. |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `NATIVE` | nginx 뒤에서 https origin·secure 쿠키 인식. |

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

```bash
# 1) admin 세션 쿠키 획득 (form 로그인)
curl -c cookies.txt -X POST http://localhost:8081/admin/login \
  -d 'email=alice@crosscert.com&password=<운영자 비번>'

# 2) 테넌트 생성 (rpId / allowedOrigins 가 핵심)
curl -b cookies.txt -X POST http://localhost:8081/admin/api/tenants \
  -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: <CSRF 토큰>" \
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
        proxy_set_header X-Forwarded-Proto $scheme;   # https — secure 쿠키·origin 판정에 필수
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

> **`X-Forwarded-Proto` 가 중요합니다.** 이 헤더가 있어야 두 서버가 "외부에서 https로 들어왔다"를 인식해 origin을 `https://rp-dev.crosscert.com`(443)으로 판정합니다. §3.1의 `SERVER_FORWARD_HEADERS_STRATEGY=NATIVE`와 함께 동작합니다. 이게 없으면 서버가 origin을 `http://...:9090`으로 오인해 등록이 `allowedOrigins` 불일치로 실패합니다.

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
| 등록이 `http://...:9090` origin으로 시도됨 | forward-headers 미설정 | §3.1 `SERVER_FORWARD_HEADERS_STRATEGY=NATIVE` + nginx `X-Forwarded-Proto` 확인. |
| 패스키가 다른 서브도메인에서 안 됨 | `rpId`를 좁게 설정함 | 여러 서브도메인을 쓰려면 `rpId`를 상위 도메인(`crosscert.com`)으로, `allowedOrigins`에 각 origin 추가. |
| JWKS 404/타임아웃 | nginx가 `dev-passkey` 서브도메인을 8080으로 프록시 안 함 | §5 nginx 설정·DNS 확인. |
| 401 (RP→Passkey 호출) | `PASSKEY_API_KEY` 형식/scope 오류 | prefix+secret 이어붙임 확인, 키에 registration·authentication scope 있는지 확인. |

---

## 참고: 더 간단한 로컬 테스트

도메인·TLS 없이 로컬에서만 빠르게 확인하려면 [dev-setup.md](dev-setup.md)를 따르세요(`localhost` 특례로 HTTP에서도 패스키가 동작합니다). 이 문서는 **공인 도메인 + HTTPS** 환경에서 RP/Passkey 서버를 한 인스턴스에 올릴 때 쓰는 구성입니다.
