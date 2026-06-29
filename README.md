# Crosscert Passkey Platform

WebAuthn / FIDO2 패스키 인증 플랫폼. **Passkey 서버** 가 RP(Relying Party) 백엔드를 위한 ceremony API 를 제공하고, **Admin Console** 에서 멀티 테넌트 운영을 관리한다. 데모용 **Sample RP** 가 통합 시나리오를 보여준다.

```
┌─────────────┐    JSESSIONID    ┌──────────────┐  X-API-Key   ┌──────────────┐
│  Browser    │ ────────────────▶│   rp-app  │ ────────────▶│  passkey-app │
│  (User)     │                  │   (RP 데모)  │              │  (Passkey 서버)│
└─────────────┘                  └──────────────┘  ◀── ID Token└──────┬───────┘
                                                     (RS256 JWT)      │
┌─────────────┐    JSESSIONID    ┌──────────────┐                     ▼
│  Browser    │ ────────────────▶│   admin-app  │             ┌──────────────┐
│  (Admin)    │                  │   + admin-ui │ ───────────▶│   Oracle XE  │
└─────────────┘                  └──────────────┘             │   + Redis    │
                                                              └──────────────┘
```

## 배포 모드

SaaS 멀티테넌트가 기본 (`passkey.deployment.mode=saas`). 설치형 싱글테넌트는 `passkey.deployment.mode=onprem` — 자세한 절차는 [docs/onprem-deployment.md](docs/onprem-deployment.md).

## 모듈 구성

| Module | Port | 역할 |
| --- | --- | --- |
| **`core`** | — | 공통 entity (Oracle JPA), JWT (RS256), VPD 컨텍스트, audit chain, MDC 로깅 인프라 |
| **`passkey-app`** | 8080 | WebAuthn 등록·인증 ceremony, JWKS, X-API-Key 인증, ID Token 발급 |
| **`admin-app`** | 8081 | 멀티 테넌트 관리 (tenant / API key / credential / audit / MDS / security policy) |
| **`admin-ui`** | (Vite dev: 5173) | React + TypeScript admin UI (Vite, 단일 페이지) |
| **`rp-app`** | 9090 | RP 데모 — `/webauthn/{register,login}/{options,complete}` + ID Token 검증 |
| **`sdk-java`** | — | RP 백엔드용 Java SDK (Spring `RestTemplate` 기반 + JWKS 검증 + redaction) |

## 기술 스택

- **JVM**: Java 17, Spring Boot 3.x, JPA/Hibernate 6
- **DB**: Oracle XE 21 (Flyway 마이그레이션 V1~V38, VPD multi-tenant isolation)
- **Cache**: Redis 7 (rate limit, ceremony state)
- **Auth**:
  - 클라이언트 ↔ RP: HTTP Session (JSESSIONID)
  - RP ↔ Passkey 서버: `X-API-Key` (bcrypt 검증)
  - Passkey 서버 → RP: ID Token (RS256 JWT + JWKS)
- **Frontend**: React 18 + TypeScript + Vite (admin-ui), Thymeleaf (rp-app)
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
# 첫 부팅 시: bootstrap-schema.sql 로 APP_OWNER / APP_RUNTIME_USER / APP_ADMIN_USER 생성 필요
docker exec -i passkey-oracle sqlplus -s / as sysdba < scripts/bootstrap-schema.sql
```

> 한 번에 깨끗이 초기화하려면 위 두 줄 대신 `scripts/init-dev-db.sh` 를 쓴다.
> 컨테이너·볼륨 재생성부터 부트스트랩까지 자동화한다 ([5) DB 스크립트](#5-db-스크립트) 참고).

### 프로필

플랫폼 운영을 위해 세 가지 Spring 프로필이 정의되어 있다:

| Profile | 용도 | 시크릿 |
|---|---|---|
| `dev` | 로컬 개발 (localhost Oracle/Redis + 시드 데이터) | yml 하드코딩 (dev 전용) |
| `qa` | 내부 QA / 통합 테스트 | 환경변수 필수 |
| `prod` | 프로덕션 (SaaS 또는 on-prem) | 환경변수 필수 + Flyway 안전장치 |

`deployment.mode` (saas/onprem) 는 프로필과 독립적인 별도 옵션
(`PASSKEY_DEPLOYMENT_MODE` 환경변수, [docs/onprem-deployment.md](docs/onprem-deployment.md) 참고).

### 2) 3 서버 기동 (dev profile)

```bash
# 1) admin-app — schema migration 책임 (Flyway 자동 적용)
SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun

# 2) passkey-app — ID Token issuer-base 명시 필요
SPRING_PROFILES_ACTIVE=dev ./gradlew :passkey-app:bootRun \
  --args="--passkey.id-token.issuer-base=http://localhost:8080"

# 3) rp-app — 시드된 acme-corp API key 주입
PASSKEY_API_KEY="pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only" \
PASSKEY_TENANT_ID="7f00dead-0000-0000-0000-00000ace0001" \
PASSKEY_BASE_URL="http://localhost:8080" \
PASSKEY_ISSUER_BASE="http://localhost:8080" \
./gradlew :rp-app:bootRun
```

### 3) Admin UI 개발 서버

```bash
cd admin-ui
npm install
npm run dev      # Vite :5173 — admin-app :8081 으로 proxy
```

### 4) 시드 계정 (dev 프로필)

| Email | Password | Role | Tenant | MFA |
| --- | --- | --- | --- | --- |
| `alice@crosscert.com` | `alice-temp-pw` | `PLATFORM_OPERATOR` | — (플랫폼 전역) | 켜짐(로그인 후 TOTP) |
| `bob@crosscert.com` | `bob-temp-pw` | `RP_ADMIN` | `dev-passkey` | 꺼짐(비번만) |

> `alice` 는 `db/seed-common`, `bob` 은 `db/dev`(dev-passkey 테넌트에 바인딩)에서
> 시드된다. dev/local 전용 — 비프로덕션 배포 전 반드시 로테이션.

### 5) DB 스크립트

`scripts/` 의 초기화 스크립트는 환경에 따라 두 갈래다. 상세 절차·환경변수는 각 스크립트
헤더 주석에 있다. **모든 reset/init 은 dev·qa 전용 — prod 에는 쓰지 않는다.**

**Docker 로컬 dev** (`docker compose` 로 Oracle 을 띄운 경우)

| 스크립트 | 용도 | 언제 |
|---|---|---|
| `init-dev-db.sh` | 컨테이너·볼륨 재생성 → 부트스트랩까지 풀 초기화 (분 단위) | 처음부터 깨끗이 / 볼륨까지 갈아엎을 때 |
| `reset-app-owner.sh` | 컨테이너는 두고 APP_OWNER 스키마 객체만 DROP+재생성 (초 단위) | 데이터만 비우고 빠르게 다시 시작할 때 |
| `bootstrap-schema.sql` | APP_OWNER / APP_RUNTIME_USER / APP_ADMIN_USER + role 부트스트랩. **Testcontainers IT 의 single source of truth** | 위 스크립트가 내부 호출 (수동 실행은 1) 인프라 기동 참고) |
| `run-bootstrap.sh` · `wait-for-oracle.sh` · `reset-app-owner.sql` | 부트스트랩 실행 러너 / healthcheck 대기 / APP_OWNER 객체 DROP(SYS) | 위 스크립트가 내부 호출 (직접 실행 불필요) |

```bash
scripts/init-dev-db.sh            # 확인 프롬프트 후 풀 초기화 (--yes 로 생략)
scripts/reset-app-owner.sh        # 'RESET' 타이핑 확인 후 스키마만 리셋 (--yes 로 생략)
PROFILE=dev scripts/reset-app-owner.sh   # dev 시드로 리셋
```

**외부/원격 Oracle** (Docker 없이 이미 떠 있는 Oracle — 로컬 설치 또는 원격 dev 서버)

| 스크립트 | 용도 | 언제 |
|---|---|---|
| `init-db-external.sh` | sqlplus 로 부트스트랩 + Flyway 적용 (도커 미사용) | 외부 Oracle 에 스키마를 처음 올릴 때 |
| `bootstrap-external.sql` (+ `bootstrap-external-body.sql`) | DBeaver 등 DEFINE 미지원 클라이언트용 부트스트랩 wrapper | sqlplus 없이 GUI 로 부트스트랩할 때 |
| `reset-app-owner-external.sql` | 외부 SE 의 APP_OWNER 스키마를 DBeaver 에서 APP_OWNER 계정으로 비움 (SYSDBA·sqlplus 불필요) | 외부 dev DB 를 리셋할 때 |

```bash
# 외부 Oracle 초기화. 부트스트랩 비번 3종(ADMIN_PW/APP_OWNER_PW/RUNTIME_PW) 필수.
# ORA_SYS_PW 는 기본 'oracle', ORA_HOST/PORT/SERVICE 는 원격일 때만 (기본 localhost:1521/XEPDB1).
ADMIN_PW=... APP_OWNER_PW=... RUNTIME_PW=... \
  ORA_HOST=db.example.com ORA_SERVICE=XEPDB1 scripts/init-db-external.sh

# 이미 부트스트랩된 외부 DB 에 Flyway 만 재적용 (bootstrap 비번 불필요)
SKIP_BOOTSTRAP=1 APP_OWNER_PW=... scripts/init-db-external.sh
```

## 데이터 흐름

### 등록 (Registration)

1. `Browser → rp-app` : `POST /webauthn/register/options { username, displayName }`
2. `rp-app → passkey-app` : `POST /api/v1/rp/registration/start` (X-API-Key)
3. `passkey-app → rp-app` : `PublicKeyCredentialCreationOptions` + ceremony token
4. `Browser : navigator.credentials.create(...)`
5. `Browser → rp-app` : `POST /webauthn/register/complete { publicKeyCredential }`
6. `rp-app → passkey-app` : `POST /api/v1/rp/registration/finish`
7. attestation 검증 + AAGUID 정책 적용 + `credential` 테이블 저장
8. `rp-app` 가 user 와 credential mapping 을 in-memory store 에 commit

### 인증 (Authentication)

1. `Browser → rp-app` : `POST /webauthn/login/options { username }` (또는 discoverable)
2. `rp-app → passkey-app` : `POST /api/v1/rp/authentication/start`
3. `Browser : navigator.credentials.get(...)`
4. `Browser → rp-app` : `POST /webauthn/login/complete`
5. `rp-app → passkey-app` : `POST /api/v1/rp/authentication/finish`
6. signature 검증 + counter 단조 증가 확인 → **ID Token (RS256 JWT)** 발급
7. `rp-app` 가 SDK 의 `IdTokenVerifier` 로 JWKS 검증 (`iss` / `aud` / `sub` / `exp`)
8. `rp-app` 가 HTTP session 발급 (JSESSIONID)

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
├── rp-app/           # 데모 RP — Thymeleaf 페이지 + WebAuthnController
├── sdk-java/            # Java SDK (PasskeyClient + IdTokenVerifier + Redacting/TracePropagation interceptors)
├── scripts/             # DB 부트스트랩·초기화·리셋 (Docker 로컬 + 외부 Oracle) — 5) DB 스크립트 참고
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

- **RP 클라이언트** (rp-app 가 노출하는 API): [`rp-client-api-quickref.md`](./docs/rp-client-api-quickref.md)
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
