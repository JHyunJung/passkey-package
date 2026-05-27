# Passkey2 — 개발자 환경 기동 가이드

앱 개발자 (RP 측) 가 패스키 등록 / 인증 테스트를 빠르게 시작할 수 있도록 작성한 문서.
**dev profile** 로 띄우면 admin-ui 콘솔과 RP 측 API 가 모두 동작하고, 시드된 tenant
3개 + API key 3개로 즉시 호출할 수 있다.

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

## 2. dev profile 이 제공하는 것

**`SPRING_PROFILES_ACTIVE=dev`** 로 admin-app 을 부팅하면 Flyway repeatable migration
`core/src/main/resources/db/dev/R__dev_seed.sql` 가 자동 적용되어 다음 시드 데이터가
DB 에 들어간다.

### 2.1 시드 tenant (3개)

| slug | rp_id | rp_name | tenant_id (RAW(16) hex) |
|---|---|---|---|
| `acme-corp` | localhost | Acme Corp | `7F00DEAD00000000000000000ACE0001` |
| `foo-corp` | localhost | Foo Corp | `7F00DEAD0000000000000000F00C0001` |
| `bar-corp` | localhost | Bar Corp | `7F00DEAD0000000000000000BA1C0001` |

모두 `localhost` 를 rpId 로 사용 — 로컬 브라우저 / 로컬 RP 앱이 그대로 사용 가능.
각 tenant 의 `allowed_origin` 은 `http://localhost:9090` (sample-rp port).
`accepted_format` 은 `none` (passkey 기본).

### 2.2 시드 API key (3개)

`prefix + secret` 를 콜론 없이 이어붙인 **X-API-Key 헤더 값**:

| Tenant | X-API-Key 헤더값 |
|---|---|
| acme-corp | `pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only` |
| foo-corp | `pk_devfoo01dev_foo_secret_known_plaintext_for_local_test_only_x` |
| bar-corp | `pk_devbar02dev_bar_secret_known_plaintext_for_local_test_only_xy` |

scope: `registration` + `authentication` (양쪽 다 허용).

> ⚠️ **dev/local 전용 시드.** 위 plaintext 가 git 에 commit 되어 있다.
> 절대 prod 활성화 금지.

### 2.3 시드 관리자 계정 (V11 + V23 시드 — local profile 에서도 동일)

| email | password | role | tenant |
|---|---|---|---|
| `alice@crosscert.com` | `alice-temp-pw` | PLATFORM_OPERATOR | (전체) |
| `bob@crosscert.com` | `bob-temp-pw` | RP_ADMIN | demo-rp |

`local` profile 의 admin-ui 로그인 화면은 alice 계정을 자동 prefill 한다.

---

## 3. 기동 순서

### 3.1 컨테이너

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
docker compose up -d
```

### 3.2 admin-app (port 8081)

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun
```

부팅 로그에서 다음을 확인:

```
INFO ... DbMigrate : Migrating schema "APP_OWNER" with repeatable migration "dev seed"
INFO ... DbMigrate : Successfully applied 1 migration to schema "APP_OWNER"
INFO ... AdminApplication : Started AdminApplication in N seconds
```

### 3.3 passkey-app (port 8080)

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew :passkey-app:bootRun \
  --args="--passkey.id-token.issuer-base=http://localhost:8080"
```

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

```bash
PASSKEY_BASE_URL=http://localhost:8080 \
PASSKEY_TENANT_ID=7F00DEAD00000000000000000ACE0001 \
PASSKEY_API_KEY=pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only \
PASSKEY_ISSUER_BASE=http://localhost:8080 \
SAMPLE_RP_ORIGIN=http://localhost:9090 \
./gradlew :sample-rp:bootRun
```

| 환경변수 | 값 |
|---|---|
| `PASSKEY_BASE_URL` | passkey-app URL (8080) |
| `PASSKEY_TENANT_ID` | tenant_id (RAW(16) hex, 32자) — dev seed 의 acme-corp |
| `PASSKEY_API_KEY` | X-API-Key 헤더 값 (prefix + secret) |
| `PASSKEY_ISSUER_BASE` | ID token issuer (passkey-app 의 `id-token.issuer-base` 와 동일) |
| `SAMPLE_RP_ORIGIN` | RP 자기 origin — `allowed_origins` 에 들어 있어야 함 |

부팅 후 브라우저에서 `http://localhost:9090/` 접속.

다른 tenant 로 시험하려면 `PASSKEY_TENANT_ID` + `PASSKEY_API_KEY` 만 바꿔서 재기동:

| Tenant | PASSKEY_TENANT_ID | PASSKEY_API_KEY |
|---|---|---|
| acme | `7F00DEAD00000000000000000ACE0001` | `pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only` |
| foo  | `7F00DEAD0000000000000000F00C0001` | `pk_devfoo01dev_foo_secret_known_plaintext_for_local_test_only_x` |
| bar  | `7F00DEAD0000000000000000BA1C0001` | `pk_devbar02dev_bar_secret_known_plaintext_for_local_test_only_xy` |

⚠️ SDK 가 monorepo 안의 `:sdk-java` 모듈에 직접 의존하므로 `:sdk-java:publishToMavenLocal`
단계 불필요. sdk-java 코드 변경 즉시 sample-rp 가 반영.

---

## 4. 동작 확인

### 4.1 admin-ui 시점

alice 자동 prefill 로 로그인 후:
- 사이드바: Tenants / Activity / Signing Keys / MDS / Audit Log
- **Tenants** → acme-corp / foo-corp / bar-corp / demo-rp 4개 이상
- **Activity** → 24h KPI + Top 5 + 이벤트 스트림
- **Audit Log** → tenantId UUID 로 필터 가능

### 4.2 RP 측 curl 시점

acme-corp tenant 의 register/start:

```bash
curl -X POST http://localhost:8080/api/v1/rp/registration/start \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only' \
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

admin-app 을 먼저 `dev` 또는 `local` profile 로 띄워서 Flyway 가 V0~V24 +
R__dev_seed 까지 모두 적용하게 한다. 그 다음 passkey-app 을 띄운다.

### 5.2 `Detected failed repeatable migration: dev seed`

```bash
docker exec passkey-oracle bash -c 'echo "
DELETE FROM APP_OWNER.\"flyway_schema_history\"
 WHERE \"description\" LIKE '\''%dev seed%'\'' OR \"success\" = 0;
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
SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun
```

⚠️ 컨테이너 시작 후 Oracle XE 가 ready 되기까지 30~60초 걸린다.

---

## 7. 참고

- dev profile 추가: `feat(dev): dev profile + R__dev_seed` commit
- monorepo 통합: `Merge feature/monorepo-merge` commit
- 시드 SQL: [`core/src/main/resources/db/dev/R__dev_seed.sql`](../core/src/main/resources/db/dev/R__dev_seed.sql)
- ApiKey 인증 필터: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java`
- SDK 모듈: [`sdk-java/`](../sdk-java/) (monorepo 흡수 후 — `project(":sdk-java")` 직접 의존)
- 데모 RP: [`sample-rp/`](../sample-rp/) (monorepo 흡수 후 — `./gradlew :sample-rp:bootRun`)
