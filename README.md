# Crosscert Passkey Platform

WebAuthn / FIDO2 패스키 인증 플랫폼. **Passkey 서버** 가 RP(Relying Party) 백엔드를 위한 ceremony API 를 제공하고, **Admin Console** 에서 멀티 테넌트 운영을 관리한다. 데모용 **Sample RP** 가 통합 시나리오를 보여준다.

```
┌─────────────┐    JSESSIONID    ┌──────────────┐  X-API-Key   ┌──────────────┐
│  Browser    │ ────────────────▶│   sample-rp  │ ────────────▶│  passkey-app │
│  (User)     │                  │   (RP 데모)  │              │  (Passkey 서버)│
└─────────────┘                  └──────────────┘  ◀── ID Token└──────┬───────┘
                                                     (RS256 JWT)      │
┌─────────────┐    JSESSIONID    ┌──────────────┐                     ▼
│  Browser    │ ────────────────▶│   admin-app  │             ┌──────────────┐
│  (Admin)    │                  │   + admin-ui │ ───────────▶│   Oracle XE  │
└─────────────┘                  └──────────────┘             │   + Redis    │
                                                              └──────────────┘
```

## 모듈 구성

| Module | Port | 역할 |
| --- | --- | --- |
| **`core`** | — | 공통 entity (Oracle JPA), JWT (RS256), VPD 컨텍스트, audit chain, MDC 로깅 인프라 |
| **`passkey-app`** | 8080 | WebAuthn 등록·인증 ceremony, JWKS, X-API-Key 인증, ID Token 발급 |
| **`admin-app`** | 8081 | 멀티 테넌트 관리 (tenant / API key / credential / audit / MDS / security policy) |
| **`admin-ui`** | (Vite dev: 5173) | React + TypeScript admin UI (Vite, 단일 페이지) |
| **`sample-rp`** | 9090 | RP 데모 — `/webauthn/{register,login}/{options,complete}` + ID Token 검증 |
| **`sdk-java`** | — | RP 백엔드용 Java SDK (Spring `RestTemplate` 기반 + JWKS 검증 + redaction) |

## 기술 스택

- **JVM**: Java 17, Spring Boot 3.x, JPA/Hibernate 6
- **DB**: Oracle XE 21 (Flyway 마이그레이션 V1~V34, VPD multi-tenant isolation)
- **Cache**: Redis 7 (rate limit, ceremony state)
- **Auth**:
  - 클라이언트 ↔ RP: HTTP Session (JSESSIONID)
  - RP ↔ Passkey 서버: `X-API-Key` (bcrypt 검증)
  - Passkey 서버 → RP: ID Token (RS256 JWT + JWKS)
- **Frontend**: React 18 + TypeScript + Vite (admin-ui), Thymeleaf (sample-rp)
- **Logging**: SLF4J + logback-classic, MDC (`traceId`/`tenantId`/`actorEmail`/`apiKeyPrefix`), SecretMaskingConverter
- **Audit**: 해시 체인 (tenant-scoped chain + global chain), 테넌트별 무결성 검증

## 시작하기

### 사전 요구

- Java 17
- Docker (Oracle + Redis 컨테이너 용)
- Node.js 18+ (admin-ui 개발 시)

### 1) 인프라 기동

```bash
docker compose up -d
# 첫 부팅 시: bootstrap-vpd.sql 로 APP_OWNER / APP_RUNTIME_USER / APP_ADMIN_USER 생성 필요
docker exec -i passkey-oracle sqlplus -s / as sysdba < scripts/bootstrap-vpd.sql
```

### 2) 3 서버 기동 (dev profile)

```bash
# 1) admin-app — schema migration 책임 (Flyway 자동 적용)
SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun

# 2) passkey-app — ID Token issuer-base 명시 필요
SPRING_PROFILES_ACTIVE=dev ./gradlew :passkey-app:bootRun \
  --args="--passkey.id-token.issuer-base=http://localhost:8080"

# 3) sample-rp — 시드된 acme-corp API key 주입
PASSKEY_API_KEY="pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only" \
PASSKEY_TENANT_ID="7f00dead-0000-0000-0000-00000ace0001" \
PASSKEY_BASE_URL="http://localhost:8080" \
PASSKEY_ISSUER_BASE="http://localhost:8080" \
./gradlew :sample-rp:bootRun
```

### 3) Admin UI 개발 서버

```bash
cd admin-ui
npm install
npm run dev      # Vite :5173 — admin-app :8081 으로 proxy
```

### 4) 시드 계정 (V11)

| Email | Password | Role |
| --- | --- | --- |
| `alice@crosscert.com` | `alice-temp-pw` | `ADMIN` |
| `bob@crosscert.com` | `bob-temp-pw` | `VIEWER` |

> Local 전용. 비프로덕션 배포 전 반드시 로테이션.

## 데이터 흐름

### 등록 (Registration)

1. `Browser → sample-rp` : `POST /webauthn/register/options { username, displayName }`
2. `sample-rp → passkey-app` : `POST /api/v1/rp/registration/start` (X-API-Key)
3. `passkey-app → sample-rp` : `PublicKeyCredentialCreationOptions` + ceremony token
4. `Browser : navigator.credentials.create(...)`
5. `Browser → sample-rp` : `POST /webauthn/register/complete { publicKeyCredential }`
6. `sample-rp → passkey-app` : `POST /api/v1/rp/registration/finish`
7. attestation 검증 + AAGUID 정책 적용 + `credential` 테이블 저장
8. `sample-rp` 가 user 와 credential mapping 을 in-memory store 에 commit

### 인증 (Authentication)

1. `Browser → sample-rp` : `POST /webauthn/login/options { username }` (또는 discoverable)
2. `sample-rp → passkey-app` : `POST /api/v1/rp/authentication/start`
3. `Browser : navigator.credentials.get(...)`
4. `Browser → sample-rp` : `POST /webauthn/login/complete`
5. `sample-rp → passkey-app` : `POST /api/v1/rp/authentication/finish`
6. signature 검증 + counter 단조 증가 확인 → **ID Token (RS256 JWT)** 발급
7. `sample-rp` 가 SDK 의 `IdTokenVerifier` 로 JWKS 검증 (`iss` / `aud` / `sub` / `exp`)
8. `sample-rp` 가 HTTP session 발급 (JSESSIONID)

## 멀티 테넌트 격리

- **VPD (Virtual Private Database)** — Oracle 의 row-level security 로 모든 tenant-scoped 테이블에 `tenant_id` predicate 자동 적용
- **APP_CTX** — `APP_OWNER.CTX_PKG.set_tenant(...)` 가 session 컨텍스트 설정. `APP_RUNTIME` role 이 read 시 자동 필터.
- **APP_ADMIN** role 은 `EXEMPT ACCESS POLICY` — admin-app + Flyway 만 부여

## Audit Chain

- 모든 mutating action 이 `audit_log` 에 적재 (해시 체인 형식)
- 두 개의 chain 동시 유지: **global chain** (이전 행 `hash` 기반) + **tenant chain** (tenant 별 독립 chain)
- admin-ui `/audit-chain` 에서 무결성 검증 + 월간 PDF 보고서 생성 (`openhtmltopdf`)

## 디렉토리 구조

```
.
├── core/                # 공통 entity + repository + JWT + VPD + audit chain
├── passkey-app/         # WebAuthn ceremony (RegistrationStart/Finish, AuthenticationStart/Finish)
├── admin-app/           # 관리 API (tenant / api-key / credential / audit / mds / system / security-policy)
├── admin-ui/            # React + TypeScript admin SPA (Vite)
├── sample-rp/           # 데모 RP — Thymeleaf 페이지 + WebAuthnController
├── sdk-java/            # Java SDK (PasskeyClient + IdTokenVerifier + Redacting/TracePropagation interceptors)
├── scripts/             # bootstrap-vpd.sql 등 DB 초기화
├── docs/
│   ├── logging-operations.md          # 운영자용 검색 cookbook + alert + trouble-shooting
│   ├── logging-conventions.md         # 개발자용 로깅 컨벤션
│   └── superpowers/                   # spec + implementation plan 모음
└── docker-compose.yml   # Oracle XE 21 + Redis 7
```

## 개발 워크플로우

이 프로젝트는 **brainstorming → spec → plan → subagent-driven-development** 사이클로 phase 별 진행되었다. 각 phase 는 별도 worktree 에서 작업되고 main 에 `--no-ff` merge.

- **F1~F5** — admin-ui 디자인 vs 백엔드 gap audit + 5 phase 로 20건 gap 모두 닫음
- **G1~G4** — 3 서버 로깅 인프라 (logback-spring.xml + MDC + RequestLoggingFilter + SecretMaskingConverter + 운영 가이드)

자세한 spec/plan 은 `docs/superpowers/{specs,plans}/` 참고.

## API 레퍼런스

- **RP 클라이언트** (sample-rp 가 노출하는 API): [`rp-client-api-quickref.md`](./rp-client-api-quickref.md)
- **Passkey 서버 ceremony API** (RP 백엔드가 호출): `passkey-app` 의 `/api/v1/rp/{registration,authentication}/{start,finish}` + `/api/v1/rp/.well-known/jwks.json`
- **Admin API**: `/admin/api/**` — UI 가 호출. Spring Security session 인증.

## 로깅 포맷

```
HH:mm:ss.SSS LEVEL [traceId] [tenantId] [actorEmail] [apiKeyPrefix] logger - msg
```

예 (admin 의 보안 정책 변경):
```
08:55:52.973 INFO  [cfb5331993aa4c68] [] [alice@crosscert.com] [] c.c.p.admin.policy.SecurityPolicyService - security policy updated: sessionIdle=30 pwMin=12 mfa=true corsAllowlistSize=0
```

운영자용 검색·alert 가이드: [`docs/logging-operations.md`](./docs/logging-operations.md)
개발자용 로깅 컨벤션: [`docs/logging-conventions.md`](./docs/logging-conventions.md)

## License

Proprietary — Crosscert.
