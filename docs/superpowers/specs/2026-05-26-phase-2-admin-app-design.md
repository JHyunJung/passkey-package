# Phase 2 — admin-app Design (멀티테넌트 관리 콘솔)

작성일: 2026-05-26
선행: Phase 0 (foundation), Phase 1 (passkey-app). 본 문서는 Phase 1 spec 후속.

## 0. 한 줄 요약

운영자가 브라우저(SPA)로 tenant CRUD, API Key 발급/회수, 감사 로그 조회를
수행하는 admin-app + admin-ui. Spring Security form login + Redis session + RBAC.
audit_log는 hash chain으로 tamper-evidence.

## 1. Phase 2 범위 (핵심 4)

- **admin UI** (tenant CRUD + API Key 발급/회수 + audit 조회) — React+Vite SPA
- **API Key 워크플로우** — 서버 발급, plainText one-time 표시
- **운영자 인증** — Spring Security form login + Redis session + RBAC (ADMIN / VIEWER)
- **audit_log** — hash chain (prev_hash → SHA-256)

### Phase 3 이후로 미루는 항목 (의식적 제외)

- ID Token 서명키 영구 저장 + 회전
- MDS 실제 BLOB 다운로드 + 검증
- audit_log 외부 export (S3 등 immutable archive)
- admin 첫 password 강제 변경, password rotation, 2FA

## 2. 합격 게이트 — AdminFlowIT

SpringBootTest + Testcontainers Oracle+Redis, 11 단계 시나리오:

```
① Flyway seed: admin_user "alice" / ADMIN + "bob" / VIEWER
② POST /admin/login (alice) → 302 + session cookie
③ POST /admin/api/tenants {id:"T_A", rp_id:"localhost", ...} → 201
④ POST /admin/api/api-keys {tenantId:"T_A", name:"primary"} → 201 + plainText
⑤ 발급된 plainText의 prefix를 ApiKeyLookupService.findByPrefix 호출 → 정상 lookup
⑥ GET /admin/api/audit → 3개 row (ADMIN_LOGIN, TENANT_CREATE, API_KEY_ISSUE)
⑦ GET /admin/api/audit/verify → {ok:true}
⑧ DELETE /admin/api/api-keys/{id} → 204 + audit row 4개
⑨ 동일 plainText prefix lookup → ApiKey.revoked_at 채워져 있어 isActive false
⑩ POST /admin/login (bob) → 302 ⇒ POST /admin/api/tenants → 403 (VIEWER)
⑪ DB에서 audit_log row 한 개의 payload 직접 변조 → /admin/api/audit/verify → {ok:false, brokenAt:N}
```

passkey-app HTTP 호출은 Phase 2 범위 밖 — `ApiKeyLookupService` 직접 호출로
"발급된 key가 lookup된다 / revoke된 key는 isActive=false" 만 검증. 진짜 HTTP
end-to-end는 Phase 4 sample-rp에서.

## 3. 기술 스택

- 백엔드: Spring Boot 3.5 (기존), Spring Security 6.x, Spring Session Redis
- 프론트엔드: React 18 + TypeScript + Vite, fetch-based API client
- 빌드 통합: `com.github.node-gradle.node` plugin → admin-ui `npm run build` →
  output `admin-app/src/main/resources/static/admin/`. `./gradlew :admin-app:bootJar`
  하나로 SPA 포함 fat JAR 생성.
- 인증: BCrypt cost=12 (Phase 1 spring-security-crypto 재사용)
- 세션: SPRING_SESSION cookie (HttpOnly, Secure, SameSite=Lax),
  Redis store, idle 30분, absolute 8시간
- CSRF: Spring Security 기본 — XSRF-TOKEN cookie (HttpOnly=false, SameSite=Strict)
  + `X-XSRF-TOKEN` header
- audit hash: SHA-256, canonical JSON (Jackson ORDER_MAP_ENTRIES_BY_KEYS)

## 4. 파일 구조

```
admin-app/
├ src/main/java/com/crosscert/passkey/admin/
│  ├ AdminApplication.java                    (기존)
│  ├ config/
│  │  ├ AdminSecurityConfig                   (form login, session, CSRF, RBAC)
│  │  └ AdminWebConfig                        (SPA fallback: GET /admin/** → index.html)
│  ├ auth/
│  │  ├ AdminUser entity
│  │  ├ AdminUserRepository
│  │  └ AdminUserDetailsService               (Spring Security UserDetails)
│  ├ tenant/
│  │  ├ TenantAdminController                 (REST: CRUD)
│  │  └ TenantAdminService                    (validate origins/policy JSON)
│  ├ apikey/
│  │  ├ ApiKeyAdminController                 (POST 발급/리스트/DELETE 회수)
│  │  └ ApiKeyAdminService                    (SecureRandom + BCrypt)
│  └ audit/
│     ├ AuditLog entity
│     ├ AuditLogRepository
│     ├ AuditLogService                       (append + chain hash)
│     ├ AuditLogController                    (read-only list/filter)
│     └ AuditChainVerifier                    (chain integrity check)
└ src/main/resources/
   ├ application.yml
   ├ static/admin/                            (Vite output destination, git-ignored)
   └ db/migration/
      ├ V9__admin_user_table.sql
      ├ V10__audit_log_table.sql
      └ V11__seed_admin_user.sql              (alice ADMIN + bob VIEWER, temp pw)

admin-ui/                                      (NEW directory at repo root)
├ package.json
├ vite.config.ts
├ tsconfig.json
└ src/
   ├ main.tsx, App.tsx
   ├ pages/
   │  ├ Login.tsx
   │  ├ TenantList.tsx, TenantDetail.tsx, TenantCreate.tsx
   │  ├ ApiKeyList.tsx, ApiKeyCreateModal.tsx
   │  └ AuditLog.tsx
   ├ api/                                     (typed fetch wrappers, CSRF token 처리)
   └ components/                              (form, table, modal)
```

`admin-ui/dist/` 와 `admin-app/src/main/resources/static/admin/`는 `.gitignore`
대상 (빌드 산출물).

## 5. 데이터 모델

### 5.1 admin_user (V9)

```sql
CREATE TABLE admin_user (
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email         VARCHAR2(255) NOT NULL UNIQUE,
  bcrypt_hash   VARCHAR2(72) NOT NULL,
  role          VARCHAR2(16) NOT NULL CHECK (role IN ('ADMIN','VIEWER')),
  enabled       CHAR(1) DEFAULT 'Y' NOT NULL CHECK (enabled IN ('Y','N')),
  created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  last_login_at TIMESTAMP WITH TIME ZONE
);
```

- VPD 없음 (platform-scoped).
- GRANT SELECT, UPDATE(last_login_at) TO APP_ADMIN_USER. APP_RUNTIME 권한 없음.

### 5.2 audit_log (V10)

```sql
CREATE TABLE audit_log (
  id           NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  prev_hash    RAW(32),
  hash         RAW(32) NOT NULL,
  actor_id     NUMBER NOT NULL,
  actor_email  VARCHAR2(255) NOT NULL,
  action       VARCHAR2(64) NOT NULL,
  target_type  VARCHAR2(32),
  target_id    VARCHAR2(64),
  payload      CLOB CHECK (payload IS JSON),
  created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX audit_log_created_at_ix ON audit_log(created_at);
CREATE INDEX audit_log_actor_ix      ON audit_log(actor_id, created_at);
CREATE INDEX audit_log_target_ix     ON audit_log(target_type, target_id, created_at);
```

- VPD 없음 (cross-tenant 운영 활동 한 chain).
- GRANT SELECT, INSERT TO APP_ADMIN_USER. UPDATE/DELETE 금지.

### 5.3 Hash chain 계산

```
prev_hash = (SELECT hash FROM audit_log ORDER BY id DESC FETCH FIRST 1 ROW ONLY)
            // 첫 row는 NULL → 빈 byte[]

canonical_payload = ObjectMapper
  .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
  .writeValueAsBytes(payload)   // no whitespace, sorted keys

input = prev_hash_bytes
     || actor_id_as_bytes
     || action.getBytes(UTF8)
     || (target_type==null ? "" : target_type).getBytes(UTF8)
     || (target_id==null ? "" : target_id).getBytes(UTF8)
     || timestamp.toString(ISO_8601).getBytes(UTF8)
     || canonical_payload

hash = SHA-256(input)
```

동시 append race 방지: `AuditLogService.append(...)` 는 `@Transactional` +
`SELECT FOR UPDATE` 로 마지막 row 잠금 → INSERT → commit. 또는 더 단순히
hint 없는 `@Transactional(SERIALIZABLE)` (audit이 hot path 아님).

### 5.4 ApiKey 테이블 변경 (필요 시 V12)

Phase 1의 api_key 테이블에 `revoked_at TIMESTAMP WITH TIME ZONE` 컬럼 추가
(soft-delete). `isActive(now)` 메서드가 `revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now)` 검사하도록 보강.

Phase 1 api_key 테이블에 이미 `expires_at` 있음. `revoked_at` 도 함께 가능했으나
Phase 1 plan에서 빠짐 — Phase 2 V12로 추가.

## 6. RBAC matrix

| Method | Path | ADMIN | VIEWER |
|---|---|---|---|
| GET | /admin/api/me | ✓ | ✓ |
| POST | /admin/login | public | public |
| POST | /admin/logout | ✓ | ✓ |
| GET | /admin/api/tenants[/{id}] | ✓ | ✓ |
| POST/PUT/DELETE | /admin/api/tenants[/{id}] | ✓ | 403 |
| GET | /admin/api/api-keys?tenantId= | ✓ | ✓ (hash redacted) |
| POST/DELETE | /admin/api/api-keys[/{id}] | ✓ | 403 |
| GET | /admin/api/audit | ✓ | ✓ |
| GET | /admin/api/audit/verify | ✓ | 403 (chain verifier는 ADMIN만) |
| GET | /admin/api/admin-users | ✓ | 403 |

Enforcement: `@PreAuthorize("hasRole('ADMIN')")` 메서드 어노테이션 (Spring
Security `@EnableMethodSecurity`).

## 7. 인증 흐름 (요청 진입 → 운영자 동작)

### 7.1 Form login

```
브라우저: POST /admin/login
  Content-Type: application/x-www-form-urlencoded
  email=alice@crosscert.com&password=secret123
admin-app:
  1. Spring Security UsernamePasswordAuthenticationFilter
  2. AdminUserDetailsService.loadByUsername("alice@...") → AdminUser 조회
       - 없으면 DUMMY_BCRYPT_HASH로 BCrypt.matches 한 번 실행 (timing equalization)
       - 있으면 row.bcrypt_hash로 BCrypt.matches(password, hash)
  3. SUCCESS: SecurityContext SET, AuditLogService.append("ADMIN_LOGIN", payload={ip,user-agent})
  4. SPRING_SESSION cookie SET (HttpOnly+Secure+SameSite=Lax)
  5. Redis: spring:session:sessions:<id> = serialized SecurityContext
  6. 302 Location: /admin/
FAIL: 401, audit append "ADMIN_LOGIN_FAILED" (actor_id=0, payload={email,ip})
```

### 7.2 모든 mutating 호출 (POST/PUT/DELETE)

```
브라우저 SPA fetch:
  fetch('/admin/api/tenants', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': document.cookie의 XSRF-TOKEN 값
    },
    credentials: 'include',
    body: JSON.stringify(req)
  })

admin-app:
  1. CsrfFilter — XSRF-TOKEN cookie == X-XSRF-TOKEN header 검증
  2. SessionAuthenticationFilter — SPRING_SESSION cookie → Redis → SecurityContext load
  3. @PreAuthorize("hasRole('ADMIN')") 통과
  4. controller → service → entity INSERT/UPDATE/DELETE
  5. AuditLogService.append(...)
  6. 200/201/204
```

### 7.3 Logout

```
POST /admin/logout
  → SecurityContext clear
  → Spring Session: Redis 키 삭제
  → SPRING_SESSION cookie expire
  → AuditLogService.append("ADMIN_LOGOUT")
  → 302 /admin/login
```

## 8. SPA 빌드 통합

```kotlin
// admin-app/build.gradle.kts (Phase 2에서 추가)
plugins {
    alias(libs.plugins.spring.boot)
    id("com.github.node-gradle.node") version "7.0.2"
}

node {
    version.set("18.20.0")
    download.set(true)
    nodeProjectDir.set(rootProject.file("admin-ui"))
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("buildUi") {
    dependsOn("npmInstall")
    workingDir.set(rootProject.file("admin-ui"))
    args.set(listOf("run", "build"))
    inputs.dir(rootProject.file("admin-ui/src"))
    inputs.file(rootProject.file("admin-ui/package.json"))
    outputs.dir(rootProject.file("admin-ui/dist"))
}

tasks.named<Copy>("processResources") {
    dependsOn("buildUi")
    from(rootProject.file("admin-ui/dist")) {
        into("static/admin")
    }
}
```

## 9. 위험 & 완화

| 위험 | 완화 |
|---|---|
| Spring Session Redis와 Bucket4j Redis 공유 | 같은 인스턴스 OK — key prefix(`spring:session:`, `ratelimit:`, `challenge:`)로 namespace. prod는 logical DB 분리 권장 (followup) |
| admin login timing oracle (존재 user vs 미존재) | Phase 1 DUMMY_HASH 패턴 재사용. 미존재 email도 BCrypt.matches 1회 |
| audit_log payload에 secret 평문 누출 | `ApiKeyAdminService` 가 API_KEY_ISSUE audit 시 prefix만 payload 포함. plainText는 HTTP response body에만 존재. unit test로 enforce |
| hash chain 동시 append race | `@Transactional` + `SELECT ... FOR UPDATE` 로 마지막 row 잠금 → INSERT |
| Vite build에 Node.js 의존 | `com.github.node-gradle.node` plugin이 Node 18 자동 download |
| CSRF token SPA refresh 후 분실 | XSRF-TOKEN cookie의 Path=/admin, fetch wrapper가 cookie 매번 읽음 |
| 첫 admin user 부트스트랩 chicken-and-egg | Flyway V11에 seed alice/bob 운영자. temp password는 followup에서 강제 변경 정책 추가 |
| admin-ui dist를 git에 포함하는 사고 | `.gitignore` 에 `admin-ui/dist/` + `admin-app/src/main/resources/static/admin/` 추가 |
| Spring Session Redis 데이터의 Lettuce codec | Spring Boot autoconfig 기본 사용 (JdkSerializationRedisSerializer). 변경 시 followup |

## 10. 후속 Phase로 넘기는 결정

- **Phase 3**: ID Token 서명키 영구 저장 + 회전 (admin이 trigger), MDS BLOB
  다운로드/검증 (admin이 업로드 + 자동 fetch), audit log 외부 archive
- **Phase 4**: admin 자체 인증을 passkey로 dogfooding (SDK 활용), sample-rp가
  발급된 API Key 사용
- **Phase 4+**: admin 첫 password 강제 변경, password rotation, 외부 IdP
  (Keycloak/OIDC) 연동, audit log를 immutable storage (S3 + Object Lock)로 archive

## 11. Plan task 예상 수

약 30개:

- V9/V10/V11/V12 migration: 4
- AdminUser entity + Repository + DetailsService: 3
- AuditLog entity + Repository + Service (TDD) + ChainVerifier (TDD): 5
- AdminSecurityConfig (form login, CSRF, session, RBAC): 2
- TenantAdminController + Service + RBAC test: 3
- ApiKeyAdminController + Service + RBAC test: 3
- AuditLogController + verify endpoint: 2
- admin-ui Vite project init + node-gradle 통합: 3
- SPA pages (Login, TenantList/Create, ApiKeyList/CreateModal, AuditLog): 5
- AdminFlowIT (11-step acceptance): 1
- DoD verify + tag: 1

Phase 1과 비슷한 규모 (27 tasks).
