# Passkey2 — 개발자 환경 기동 가이드

앱 개발자 (RP 측) 가 패스키 등록 / 인증 테스트를 빠르게 시작할 수 있도록 작성한 문서.
**local 또는 dev profile** 로 띄우면 admin-ui 콘솔과 RP 측 API 가 모두 동작하고,
프로필별 시드 tenant 1개 + API key 1개로 즉시 호출할 수 있다.

작성: 2026-05-28
대상 commit: monorepo-merge 후
관련:
- [Activity 페이지 spec](superpowers/specs/2026-05-27-activity-page-design.md)
- [Activity followups](superpowers/followups/2026-05-28-activity-page-followups.md)
- [monorepo 통합 spec](superpowers/specs/2026-05-28-monorepo-merge-design.md)

---

## 1. 사전 준비

### 1.1 의존성 도구

| 도구 | 버전 | 용도 |
|---|---|---|
| Docker | recent | Oracle XE + Redis 컨테이너 |
| JDK | 17 | passkey-app / admin-app / sdk-java / sample-rp |
| Node.js | 18+ | admin-ui (선택, HMR 개발 시) |

### 1.2 저장소 구조 (monorepo)

```
/Users/jhyun/Git/10-work/crosscert/
└── Passkey2/        # 모든 코드 (단일 저장소)
    ├── core/        # JPA entity + repository + VPD + Flyway
    ├── passkey-app/ # FIDO2 server (port 8080)
    ├── admin-app/   # 운영 콘솔 API + admin-ui 정적 serving (port 8081)
    ├── admin-ui/    # React + Vite 콘솔 SPA
    ├── sdk-java/    # 패스키 SDK (Java) — sample-rp 가 의존
    └── sample-rp/   # 데모 RP 앱 (port 9090)
```

### 1.3 컨테이너 (Oracle + Redis)

Passkey2 루트에서:

```bash
docker compose up -d
docker ps  # passkey-oracle (1521) + passkey-redis (6379) 가 healthy
```

`docker-compose.yml` 은 인프라만 제공. 앱은 로컬에서 직접 실행.

---

## 2. 프로필별 시드가 제공하는 것

> **2026-05 변경 (프로필별 시드 분리)**: Flyway seed locations 가 프로필별로 분리됐다.
> - **local** (`SPRING_PROFILES_ACTIVE=local`): `db/migration,db/seed-common,db/local` — 완전 로컬 개발용. rpId=`localhost` 라 **로컬 브라우저에서 패스키 ceremony 가 그대로 동작**한다.
> - **dev** (`SPRING_PROFILES_ACTIVE=dev`): `db/migration,db/seed-common,db/dev` — 서버 배포(dev-passkey.crosscert.com)용. rpId=도메인이라 로컬 localhost 에서는 rpId 불일치로 ceremony 불가.
>
> 로컬 개발자가 패스키를 직접 테스트하려면 **`local` 프로필**을 사용한다.
> dev 가 datasource URL, credential, Redis host, dev key-envelope master 를
> self-contained 로 제공하는 구조는 동일.

admin-app 을 부팅하면 Flyway repeatable migration 이 자동 적용되어 다음 시드 데이터가
DB 에 들어간다.

### 2.1 시드 tenant (프로필별 1개)

| 프로필 | slug | rp_id | rp_name | tenant_id (RAW(16) hex) |
|---|---|---|---|---|
| `local` | `demo-rp` | localhost | Demo RP | `0000000000000000000000000000C0DE` |
| `dev` | `dev-passkey` | dev-passkey.crosscert.com | Dev Passkey | `7F00DEAD000000000000000DE7000001` |

- **local**: rpId=`localhost` → 완전 로컬(`http://localhost:9090`)에서 패스키 등록/인증 ceremony 동작.
  `allowed_origin` = `http://localhost:9090`.
- **dev**: rpId=`dev-passkey.crosscert.com` → 서버 배포 환경 전용.
  `allowed_origin` = `https://dev-passkey.crosscert.com`.
- `accepted_format` 은 `none` (passkey 기본).

### 2.2 시드 API key (프로필별 1개)

`prefix + secret` 를 콜론 없이 이어붙인 **X-API-Key 헤더 값**:

| 프로필 | Tenant | X-API-Key 헤더값 |
|---|---|---|
| `local` | demo-rp | `pk_devlocaldev_local_secret_known_plaintext_for_test_only` |
| `dev` | dev-passkey | `pk_devsrv01dev_server_secret_known_plaintext_for_test_only` |

scope: `registration` + `authentication` (양쪽 다 허용).

> ⚠️ **dev/local 전용 시드.** 위 plaintext 가 git 에 commit 되어 있다.
> 절대 prod 활성화 금지.

### 2.3 시드 관리자 계정 (seed-common)

| email | password | role | tenant |
|---|---|---|---|
| `alice@crosscert.com` | `alice-temp-pw` | PLATFORM_OPERATOR | (전체) |

`local`/`dev` profile 의 admin-ui 로그인 화면은 alice 계정을 자동 prefill 한다.

---

## 3. 기동 순서

### 3.1 컨테이너

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
docker compose up -d
```

### 3.2 admin-app (port 8081)

로컬 패스키 테스트 (권장):
```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :admin-app:bootRun
```

서버 배포 환경:
```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun
```

부팅 로그에서 다음을 확인:

```
INFO ... DbMigrate : Migrating schema "APP_OWNER" with repeatable migration "seed ..."
INFO ... DbMigrate : Successfully applied N migrations to schema "APP_OWNER"
INFO ... AdminApplication : Started AdminApplication in N seconds
```

### 3.3 passkey-app (port 8080)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :passkey-app:bootRun \
  --args="--passkey.id-token.issuer-base=http://localhost:8080"
```

(dev 환경이면 `local` → `dev` 로 교체)

```bash
curl -s http://localhost:8080/actuator/health
# {"status":"UP"}
```

### 3.4 admin-ui (선택)

#### 옵션 A — 빌드된 dist (port 8081)

브라우저에서 `http://localhost:8081/admin/` 접속.

#### 옵션 B — Vite dev server (HMR + port 5173)

```bash
cd admin-ui
npm install   # 최초 한 번
npm run dev
```

`http://localhost:5173/admin/` 로 접속.

### 3.5 sample-rp (port 9090) — monorepo 안에서 직접 실행

Passkey2 루트에서 단일 명령:

**local 프로필 (로컬 패스키 테스트 — 권장):**

```bash
PASSKEY_BASE_URL=http://localhost:8080 \
PASSKEY_TENANT_ID=0000000000000000000000000000C0DE \
PASSKEY_API_KEY=pk_devlocaldev_local_secret_known_plaintext_for_test_only \
PASSKEY_ISSUER_BASE=http://localhost:8080 \
SAMPLE_RP_ORIGIN=http://localhost:9090 \
./gradlew :sample-rp:bootRun
```

**dev 프로필 (서버 배포 환경):**

```bash
PASSKEY_BASE_URL=https://dev-passkey.crosscert.com \
PASSKEY_TENANT_ID=7F00DEAD000000000000000DE7000001 \
PASSKEY_API_KEY=pk_devsrv01dev_server_secret_known_plaintext_for_test_only \
PASSKEY_ISSUER_BASE=https://dev-passkey.crosscert.com \
SAMPLE_RP_ORIGIN=https://dev-passkey.crosscert.com \
./gradlew :sample-rp:bootRun
```

| 환경변수 | 값 |
|---|---|
| `PASSKEY_BASE_URL` | passkey-app URL |
| `PASSKEY_TENANT_ID` | tenant_id. RAW(16) hex 32자 또는 UUID 대시 형식 모두 가능(sample-rp 가 비교 전 UUID 로 정규화). ID Token 의 `iss`/`aud` 는 UUID 형식이라, **외부 시스템에서 직접 검증한다면 UUID 형식**을 권장 |
| `PASSKEY_API_KEY` | X-API-Key 헤더 값 (prefix + secret) |
| `PASSKEY_ISSUER_BASE` | ID token issuer (passkey-app 의 `id-token.issuer-base` 와 동일) |
| `SAMPLE_RP_ORIGIN` | RP 자기 origin — `allowed_origins` 에 들어 있어야 함 |

부팅 후 브라우저에서 `http://localhost:9090/` 접속.

프로필별 tenant/API key 요약:

| 프로필 | PASSKEY_TENANT_ID | PASSKEY_API_KEY |
|---|---|---|
| local | `0000000000000000000000000000C0DE` (UUID: `00000000-0000-0000-0000-00000000c0de`) | `pk_devlocaldev_local_secret_known_plaintext_for_test_only` |
| dev   | `7F00DEAD000000000000000DE7000001` (UUID: `7f00dead-0000-0000-0000-000de7000001`) | `pk_devsrv01dev_server_secret_known_plaintext_for_test_only` |

⚠️ SDK 가 monorepo 안의 `:sdk-java` 모듈에 직접 의존하므로 `:sdk-java:publishToMavenLocal`
단계 불필요. sdk-java 코드 변경 즉시 sample-rp 가 반영.

---

## 4. 동작 확인

### 4.1 admin-ui 시점

alice 자동 prefill 로 로그인 후:
- 사이드바: Tenants / Activity / Signing Keys / MDS / Audit Log
- **Tenants** → local 프로필: `demo-rp` 1개 / dev 프로필: `dev-passkey` 1개
- **Activity** → 24h KPI + Top 5 + 이벤트 스트림
- **Audit Log** → tenantId UUID 로 필터 가능

### 4.2 RP 측 curl 시점

local 프로필 demo-rp tenant 의 register/start:

```bash
curl -X POST http://localhost:8080/api/v1/rp/registration/start \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: pk_devlocaldev_local_secret_known_plaintext_for_test_only' \
  -d '{
    "userHandle": "ZGV2LXVzZXItMDAx",
    "displayName": "Dev User 001",
    "username": "dev-user-001"
  }'
```

성공 시 응답에 `publicKeyCredentialCreationOptions` + `registrationToken` 포함.

### 4.3 sample-rp 시점

`http://localhost:9090/` 에서 회원가입 → 패스키 등록 → 로그아웃 → 패스키 인증
한 사이클이 모두 정상 동작하면 환경 검증 완료.

---

## 5. 자주 만나는 문제

### 5.1 `Schema-validation: missing column [...]`

admin-app 을 먼저 `local` (또는 `dev`) profile 로 띄워서 Flyway 가 versioned migration +
repeatable seed migration 까지 모두 적용하게 한다. 그 다음 passkey-app 을 띄운다.

### 5.2 `Detected failed repeatable migration: seed ...`

```bash
docker exec passkey-oracle bash -c 'echo "
DELETE FROM APP_OWNER.\"flyway_schema_history\"
 WHERE \"description\" LIKE '\''%seed%'\'' OR \"success\" = 0;
COMMIT;
EXIT;" | sqlplus -S system/oracle@//localhost:1521/XEPDB1'
```

그 후 admin-app 재기동.

### 5.3 `401 Unauthorized` (RP API 호출 시)

- X-API-Key 헤더에 콜론 없이 `prefix + secret` 가 이어붙어 있는지 확인
- prefix 가 정확히 11자 (`pk_` + 8자) 인지 확인
- API key 가 revoked 되지 않았는지

### 5.4 `ID token issuer mismatch`

passkey-app 의 `--passkey.id-token.issuer-base` 와 sample-rp 의 `PASSKEY_ISSUER_BASE`
가 동일한지 확인.

### 5.5 port 충돌

```bash
lsof -ti :8080 -ti :8081 -ti :5173 -ti :9090 | xargs kill
```

---

## 6. 시드 데이터 재설정 (clean slate)

```bash
docker compose down -v   # volumes 삭제 → Oracle 데이터 완전 초기화
docker compose up -d
SPRING_PROFILES_ACTIVE=local ./gradlew :admin-app:bootRun   # 또는 dev
```

⚠️ 컨테이너 시작 후 Oracle XE 가 ready 되기까지 30~60초 걸린다.

---

## 7. 참고

- **RP 서버 API 명세서**: [rp-server-api.md](rp-server-api.md) — RP 백엔드가 `/api/v1/rp/**` 를 직접 호출하기 위한 정식 레퍼런스(인증/scope/DTO/ID Token/에러/rate limit)
- 프로필별 시드 분리: `feat(seed): 프로필별 시드 분리` commit
- monorepo 통합: `Merge feature/monorepo-merge` commit
- 시드 SQL (local): [`core/src/main/resources/db/local/R__seed_local_tenant.sql`](../core/src/main/resources/db/local/R__seed_local_tenant.sql)
- 시드 SQL (dev): [`core/src/main/resources/db/dev/R__seed_dev_tenant.sql`](../core/src/main/resources/db/dev/R__seed_dev_tenant.sql)
- 공통 운영자 시드: [`core/src/main/resources/db/seed-common/R__seed_operators.sql`](../core/src/main/resources/db/seed-common/R__seed_operators.sql)
- ApiKey 인증 필터: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java`
- SDK 모듈: [`sdk-java/`](../sdk-java/) (monorepo 흡수 후 — `project(":sdk-java")` 직접 의존)
- 데모 RP: [`sample-rp/`](../sample-rp/) (monorepo 흡수 후 — `./gradlew :sample-rp:bootRun`)
