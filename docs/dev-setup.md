# Passkey2 — 개발자 환경 기동 가이드

앱 개발자 (RP 측) 가 패스키 등록 / 인증 테스트를 빠르게 시작할 수 있도록 작성한 문서.
**local 또는 dev profile** 로 띄우면 admin-ui 콘솔과 RP 측 API 가 모두 동작하고,
프로필별 시드 tenant 1개 + API key 1개로 즉시 호출할 수 있다.

최종 갱신: 2026-09-13
관련:
- [백로그](backlog.md) — Activity 관련 미해소 항목은 §1 기능 개발 참조
- Activity 페이지 spec·monorepo 통합 spec 은 저장소에서 정리됨 (git 이력의 `docs/superpowers/` 참조)

---

## 1. 사전 준비

### 1.1 의존성 도구

| 도구 | 버전 | 용도 |
|---|---|---|
| Docker | recent | Oracle XE + Redis 컨테이너 |
| JDK | 17 | passkey-app / admin-app / sdk-java / rp-app |
| Node.js | 18+ | admin-ui (선택, HMR 개발 시) |

### 1.2 저장소 구조 (monorepo)

```
/Users/jhyun/Git/10-work/crosscert/
└── Passkey2/        # 모든 코드 (단일 저장소)
    ├── core/        # JPA entity + repository + 테넌트 격리(Hibernate @Filter) + Flyway
    ├── webauthn/    # WebAuthn 검증 코어 (attestation 포맷 + MDS) — 자체 구현
    ├── passkey-app/ # FIDO2 server (port 8080)
    ├── admin-app/   # 운영 콘솔 API + admin-ui 정적 serving (port 8081)
    ├── admin-ui/    # React + Vite 콘솔 SPA
    ├── sdk-java/    # 패스키 SDK (Java) — rp-app 가 의존
    ├── rp-app/      # 데모 RP 앱 (port 9090)
    ├── scripts/     # DB 부트스트랩·초기화·리셋 스크립트 (Docker 로컬 + 외부 Oracle)
    ├── deploy/      # 컨테이너 배포 (docker-compose + nginx) — deploy/README.md
    └── qa-bundle/   # 폐쇄망 QA 반입 번들 (amd64 이미지 + 설치 스크립트)
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

Flyway seed locations 는 프로필별로 분리돼 있다.

- **local** (`SPRING_PROFILES_ACTIVE=local`): `db/migration,db/seed-common,db/local` — 완전 로컬 개발용. rpId=`localhost` 라 **로컬 브라우저에서 패스키 ceremony 가 그대로 동작**한다.
- **dev** (`SPRING_PROFILES_ACTIVE=dev`): `db/migration,db/seed-common,db/dev` — 서버 배포(dev-passkey.crosscert.com)용. rpId=도메인이라 로컬 localhost 에서는 rpId 불일치로 ceremony 불가.

로컬 개발자가 패스키를 직접 테스트하려면 **`local` 프로필**을 사용한다.
dev 도 datasource URL, credential, Redis host, dev key-envelope master 를 self-contained 로
제공한다. 다만 이 값들은 `${SPRING_DATASOURCE_URL:jdbc:oracle:thin:@localhost:1521/XEPDB1}`
처럼 **기존 값을 기본값으로 둔 플레이스홀더**라, 아무것도 주입하지 않으면 로컬 기본값으로
동작하고 서버 배포 시에는 환경변수로 덮어쓸 수 있다. 해당 환경변수를 셸에 export 해 둔
상태면 로컬에서도 그 값이 우선하니 주의한다.

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
- `accepted_format` 은 `none` + `packed` 두 건이 시드된다(양쪽 프로필 동일).

### 2.2 시드 API key (프로필별 1개)

`prefix + secret` 를 콜론 없이 이어붙인 **X-API-Key 헤더 값**:

| 프로필 | Tenant | X-API-Key 헤더값 |
|---|---|---|
| `local` | demo-rp | `pk_devlocaldev_local_secret_known_plaintext_for_test_only` |
| `dev` | dev-passkey | `pk_devsrv01dev_server_secret_known_plaintext_for_test_only` |

scope: `registration` + `authentication` (양쪽 다 허용).

> ⚠️ **dev/local 전용 시드.** 위 plaintext 가 git 에 commit 되어 있다.
> 절대 prod 활성화 금지.

### 2.3 시드 관리자 계정

| email | password | role | tenant | MFA | 출처 |
|---|---|---|---|---|---|
| `alice@crosscert.com` | `alice-temp-pw` | PLATFORM_OPERATOR | (전체) | 꺼짐(비번만, 설정에서 TOTP 등록 가능) | `db/seed-common` (local·dev 공통) |
| `bob@crosscert.com` | `bob-temp-pw` | RP_ADMIN | `dev-passkey`※ | 꺼짐(비번만) | `db/dev` (**dev 프로필 한정**) |

bob 은 `dev` 프로필에서만 시드된다 — `local` 프로필에는 없다.

> ※ `admin_user` 에는 tenant 컬럼이 없다. 운영자 ↔ RP 는 **N:M 매핑 테이블
> `admin_user_tenant`** 로 연결되며, bob 의 소속은 시드 SQL 이 넣는 매핑 행
> (`R__seed_dev_tenant.sql` 의 `INSERT INTO admin_user_tenant`) 하나로 결정된다.
> `PLATFORM_OPERATOR`(alice)는 매핑 행이 0개이고 전체 테넌트에 접근한다.

**두 계정 모두 MFA 가 꺼진 상태로 시드**되어 비밀번호만으로 콘솔에 들어갈 수 있다.
bob 은 시드 SQL 이 `mfa_enabled='N'` 을 명시하고, alice 는 INSERT 에 그 컬럼이 아예 없어
스키마 기본값(`DEFAULT 'N'`)이 적용된다. 로그인 후 MFA 를 요구할지는 이 값 하나로
결정되므로(`AdminSecurityConfig` 의 `isMfaEnabled()`), 시드 직후에는 둘 다 TOTP 단계가
없다. TOTP 를 써 보려면 로그인 후 **설정 → 계정**에서 직접 등록한다.

`local`/`dev` profile 의 admin-ui 로그인 화면은 "데모 계정으로 로그인" 카드를 띄우는데,
자동 prefill 대상은 alice(PLATFORM_OPERATOR) 뿐이다. bob 은 직접 입력한다.

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
INFO ... DbMigrate : Migrating schema "PSK_APP_OWNER" with repeatable migration "seed ..."
INFO ... DbMigrate : Successfully applied N migrations to schema "PSK_APP_OWNER"
INFO ... AdminApplication : Started AdminApplication in N seconds
```

### 3.3 passkey-app (port 8080)

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew :passkey-app:bootRun \
  --args="--passkey.id-token.issuer-base=http://localhost:8080"
```

passkey-app 에는 `local` 프로필이 없다(`application-local.yml` 부재) — 프로필별 시드가
없어 `dev` 하나가 local/dev 를 겸한다(시드 분리는 admin-app 의 Flyway locations 담당).
base `application.yml` 에는 datasource·Redis 설정이 없으므로 `local` 로 띄우면 기동에
실패한다.

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

### 3.5 rp-app (port 9090) — monorepo 안에서 직접 실행

Passkey2 루트에서 단일 명령:

**local 프로필 (로컬 패스키 테스트 — 권장):**

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :rp-app:bootRun
```

`application-local.yml` 이 `passkey.base-url`·`api-key`·`tenant-id`·`issuer-base` 네 값을
모두 갖고 있어 환경변수 주입 없이 뜬다. 다른 서버를 가리키려면 아래처럼 덮어쓴다:

```bash
SPRING_PROFILES_ACTIVE=local \
PASSKEY_BASE_URL=http://localhost:8080 \
PASSKEY_TENANT_ID=0000000000000000000000000000C0DE \
PASSKEY_API_KEY=pk_devlocaldev_local_secret_known_plaintext_for_test_only \
PASSKEY_ISSUER_BASE=http://localhost:8080 \
./gradlew :rp-app:bootRun
```

프로필 지정은 필수다. `RelayKeyGuard` 는 active profile 에 `dev`/`local` 이 없으면
relay 데모 기본 키를 거부하고 기동을 중단시킨다(프로필 미지정 = 운영 취급).

**dev 프로필 (서버 배포 환경):**

```bash
SPRING_PROFILES_ACTIVE=dev \
PASSKEY_BASE_URL=https://dev-passkey.crosscert.com \
PASSKEY_TENANT_ID=7F00DEAD000000000000000DE7000001 \
PASSKEY_API_KEY=pk_devsrv01dev_server_secret_known_plaintext_for_test_only \
PASSKEY_ISSUER_BASE=https://dev-passkey.crosscert.com \
RP_RELAY_SECRET="$(openssl rand -base64 32)" \
./gradlew :rp-app:bootRun
```

`dev` 프로필에서는 가드가 relay 데모 기본 키를 허용하지만, 서버에 올리는 구성이므로
`RP_RELAY_SECRET` 으로 강한 키를 주입한다(미주입 시 base `application.yml` 의
`${RP_RELAY_SECRET:}` 폴백으로 데모 키가 쓰인다).

| 환경변수 | 값 |
|---|---|
| `PASSKEY_BASE_URL` | passkey-app URL |
| `PASSKEY_TENANT_ID` | tenant_id. RAW(16) hex 32자 또는 UUID 대시 형식 모두 가능(rp-app 가 비교 전 UUID 로 정규화). ID Token 의 `iss`/`aud` 는 UUID 형식이라, **외부 시스템에서 직접 검증한다면 UUID 형식**을 권장 |
| `PASSKEY_API_KEY` | X-API-Key 헤더 값 (prefix + secret) |
| `PASSKEY_ISSUER_BASE` | ID token issuer (passkey-app 의 `id-token.issuer-base` 와 동일) |

RP 자신의 origin 을 rp-app 에 알려주는 설정은 없다 — 등록/인증에서 대조되는 origin 은
테넌트의 `allowed_origins` 로 결정된다.

부팅 후 브라우저에서 `http://localhost:9090/` 접속.

프로필별 tenant/API key 요약:

| 프로필 | PASSKEY_TENANT_ID | PASSKEY_API_KEY |
|---|---|---|
| local | `0000000000000000000000000000C0DE` (UUID: `00000000-0000-0000-0000-00000000c0de`) | `pk_devlocaldev_local_secret_known_plaintext_for_test_only` |
| dev   | `7F00DEAD000000000000000DE7000001` (UUID: `7f00dead-0000-0000-0000-000de7000001`) | `pk_devsrv01dev_server_secret_known_plaintext_for_test_only` |

rp-app 은 저장소 안의 `:sdk-java` 모듈에 직접 의존한다. SDK 를 로컬 Maven 저장소에 먼저
배포하는 단계는 필요 없고, sdk-java 코드를 고치면 rp-app 빌드에 즉시 반영된다.

---

## 4. 동작 확인

### 4.1 admin-ui 시점

alice 자동 prefill 로 로그인 후. 사이드바는 **어디에 있느냐로 두 벌**이 뜬다
(`admin-ui/src/shell/Sidebar.tsx`):

- **플랫폼 스코프** (테넌트 밖): Tenants / 활동 / 감사 체인 / 설정.
  `deployment.mode=onprem` 이면 감사 체인과 설정 사이에 **라이선스** 가 끼어든다.
  RP_ADMIN(bob) 으로 들어가면 이 중 **설정** 하나만 보인다(자기 계정·MFA 용).
- **테넌트 스코프** (테넌트 하나를 열면 그 이름 아래로 교체): 개요 / WebAuthn /
  AAGUID 정책 / API 키 / Credentials / 감사 로그 / 퍼널.

- **Tenants** → local 프로필: `demo-rp` 1개 / dev 프로필: `dev-passkey` 1개
- **활동** → 24h KPI + Top 5 + 이벤트 스트림
- **감사 로그**(테넌트 스코프) → 이벤트 유형·시간창 필터. 테넌트는 이미 경로로 고정돼
  있어 별도 tenantId 입력은 없다

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

성공 시 응답은 `ApiResponse` 래퍼(`success`/`code`/`message`/`data`/`traceId`/`timestamp`)이고,
`data` 안에 `registrationToken` + `publicKeyCredentialCreationOptions` 가 들어 있다.

### 4.3 rp-app 시점

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
DELETE FROM PSK_APP_OWNER.\"flyway_schema_history\"
 WHERE \"description\" LIKE '\''%seed%'\'' OR \"success\" = 0;
COMMIT;
EXIT;" | sqlplus -S system/"${ORACLE_PASSWORD:-oracle}"@//localhost:1521/"${ORA_SERVICE:-XEPDB1}"'
```

`ORACLE_PASSWORD` 는 루트 `docker-compose.yml` 이 컨테이너에 넣어주는 이름 그대로라
(`environment: ORACLE_PASSWORD: ${ORACLE_PASSWORD:-oracle}`), 컨테이너 안에서 자동으로
해석된다. 반면 `ORA_SERVICE` 는 컨테이너 환경에 없으므로 PDB 명이 `XEPDB1` 이 아니면
`docker exec -e ORA_SERVICE=<PDB명> ...` 로 직접 넘겨야 한다(안 넘기면 `XEPDB1` 폴백).

그 후 admin-app 재기동.

### 5.3 `401 Unauthorized` (RP API 호출 시)

- X-API-Key 헤더에 콜론 없이 `prefix + secret` 가 이어붙어 있는지 확인
- prefix 가 정확히 11자 (`pk_` + 8자) 인지 확인
- API key 가 revoked 되지 않았는지

### 5.4 `ID token issuer mismatch`

passkey-app 의 `--passkey.id-token.issuer-base` 와 rp-app 의 `PASSKEY_ISSUER_BASE`
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

- **RP 서버 API 명세서**: [rp-server-api.md](rp-server-api.md) — 클라이언트(웹/앱)가 RP 서버(rp-app)의 `/passkey/**` 를 호출하는 정식 레퍼런스(무상태 릴레이 토큰/DTO/에러). RP 서버 구현자용 절에 Passkey 서버 `/api/v1/rp/**` 온보딩·ID Token 검증도 포함
- 시드 SQL (local): [`core/src/main/resources/db/local/R__seed_local_tenant.sql`](../core/src/main/resources/db/local/R__seed_local_tenant.sql)
- 시드 SQL (dev): [`core/src/main/resources/db/dev/R__seed_dev_tenant.sql`](../core/src/main/resources/db/dev/R__seed_dev_tenant.sql)
- 공통 운영자 시드: [`core/src/main/resources/db/seed-common/R__seed_operators.sql`](../core/src/main/resources/db/seed-common/R__seed_operators.sql)
- ApiKey 인증 필터: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java`
- SDK 모듈: [`sdk-java/`](../sdk-java/) — `project(":sdk-java")` 로 직접 의존
- 데모 RP: [`rp-app/`](../rp-app/) — `./gradlew :rp-app:bootRun`
