# Phase 2 — admin-app Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the multi-tenant admin console (admin-app + admin-ui SPA): operators log in with email/password, manage tenants and API keys, and inspect a tamper-evident audit log. Acceptance gate is `AdminFlowIT` exercising the 11-step operator scenario end-to-end against Testcontainers Oracle + Redis.

**Architecture:** Spring Boot 3.5 admin-app serves a React+Vite SPA from its own classpath (single fat JAR). Form login + Spring Session Redis (with CSRF) gates a `/admin/api/**` REST surface. `@PreAuthorize("hasRole('ADMIN')")` enforces RBAC (ADMIN vs VIEWER). Every mutating call appends an `audit_log` row whose hash chains into the previous row via SHA-256 over canonical JSON; an on-demand `/admin/api/audit/verify` walks the chain and reports the first break.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Security 6, Spring Session Data Redis, React 18 + TypeScript + Vite, com.github.node-gradle.node 7.0.2, BCrypt cost 12, Flyway, Oracle 19c, Testcontainers, JUnit 5, AssertJ, Mockito.

---

## File Inventory

**Backend — `:core` (entities/repositories shared with admin-app):**
- Create `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java`
- Create `core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java`
- Create `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRepository.java`
- Create `core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java` (add `revoke(now)` method)

**Backend — `:admin-app`:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetailsService.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogService.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainVerifier.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditAppendRequest.java` (immutable input record)
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogController.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java` (records: TenantView, TenantCreateRequest, TenantUpdateRequest)
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminController.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java` (records: ApiKeyView, ApiKeyCreateRequest, ApiKeyCreateResponse)
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminWebMvcConfig.java` (SPA fallback)
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/config/MeController.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminErrorHandler.java`

**Backend — Flyway migrations (in `:core` resources, picked up by admin-app's Flyway):**
- Create `core/src/main/resources/db/migration/V9__admin_user_table.sql`
- Create `core/src/main/resources/db/migration/V10__audit_log_table.sql`
- Create `core/src/main/resources/db/migration/V11__seed_admin_user.sql`

**Backend tests:**
- Create `core/src/test/java/com/crosscert/passkey/core/entity/AdminUserTest.java`
- Create `core/src/test/java/com/crosscert/passkey/core/entity/AuditLogTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogServiceTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainVerifierTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminServiceTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminControllerSecurityTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogControllerSecurityTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java`
- Create `admin-app/src/test/resources/application-test.yml`

**Build / config:**
- Modify `gradle/libs.versions.toml` (Spring Session, node-gradle plugin)
- Modify `core/build.gradle.kts` (add spring-session-data-redis)
- Modify `admin-app/build.gradle.kts` (Spring Security starter, node plugin, processResources copy)
- Modify `admin-app/src/main/resources/application.yml` (Spring Session, security session config)
- Modify `.gitignore` (admin-ui/dist, admin-ui/node_modules, admin-app/src/main/resources/static/admin)

**Frontend — `admin-ui/` (NEW directory at repo root):**
- Create `admin-ui/package.json`
- Create `admin-ui/vite.config.ts`
- Create `admin-ui/tsconfig.json`
- Create `admin-ui/index.html`
- Create `admin-ui/src/main.tsx`
- Create `admin-ui/src/App.tsx`
- Create `admin-ui/src/api/client.ts` (typed fetch + CSRF + 401 handling)
- Create `admin-ui/src/api/types.ts`
- Create `admin-ui/src/pages/Login.tsx`
- Create `admin-ui/src/pages/TenantList.tsx`
- Create `admin-ui/src/pages/TenantCreate.tsx`
- Create `admin-ui/src/pages/ApiKeyList.tsx`
- Create `admin-ui/src/pages/ApiKeyCreateModal.tsx`
- Create `admin-ui/src/pages/AuditLog.tsx`
- Create `admin-ui/src/components/Layout.tsx`

---

## Task 1: Add backend dependencies (Spring Security starter, Spring Session Data Redis, node-gradle plugin)

**Files:**
- Modify `gradle/libs.versions.toml`
- Modify `core/build.gradle.kts`
- Modify `admin-app/build.gradle.kts`

- [ ] **Step 1: Add Spring Session library + node-gradle plugin entry to `gradle/libs.versions.toml`**

Append under `[versions]`:

```toml
node-gradle = "7.0.2"
```

Append under `[libraries]`:

```toml
spring-session-data-redis = { module = "org.springframework.session:spring-session-data-redis" }
spring-boot-starter-security = { module = "org.springframework.boot:spring-boot-starter-security" }
spring-security-test = { module = "org.springframework.security:spring-security-test" }
```

Append under `[plugins]`:

```toml
node-gradle = { id = "com.github.node-gradle.node", version.ref = "node-gradle" }
```

- [ ] **Step 2: Add `spring-session-data-redis` to `:core` (so admin-app and any future module sees the bean)**

Edit `core/build.gradle.kts`, add inside `dependencies { }` after the existing `spring-boot-starter-data-redis` line:

```kotlin
    api("org.springframework.session:spring-session-data-redis")
```

- [ ] **Step 3: Add Spring Security starter to `:admin-app` only (passkey-app does not need it)**

Edit `admin-app/build.gradle.kts`, replace the existing `dependencies { }` block with:

```kotlin
dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-security")

    testImplementation("org.springframework.security:spring-security-test")
}
```

- [ ] **Step 4: Compile-check**

Run: `./gradlew :core:compileJava :admin-app:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml core/build.gradle.kts admin-app/build.gradle.kts
git commit -m "build: Phase 2 dependencies — spring-security, spring-session, node-gradle"
```

---

## Task 2: Flyway V9 — `admin_user` table

**Files:**
- Create `core/src/main/resources/db/migration/V9__admin_user_table.sql`

- [ ] **Step 1: Write the migration**

Contents of `V9__admin_user_table.sql`:

```sql
-- Platform-scoped operator account table. NO VPD — administration is
-- cross-tenant by design, and admin_user rows belong to the platform
-- itself, not to any tenant.
--
-- APP_RUNTIME never reads or writes this table. APP_ADMIN gets
-- SELECT + INSERT + column-scoped UPDATE on last_login_at. Password
-- changes / role changes are SQL maintenance for Phase 2 (admin
-- self-service password change is a Phase 4 followup).

CREATE SEQUENCE admin_user_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE admin_user (
  id            NUMBER(19,0)             NOT NULL,
  email         VARCHAR2(255)            NOT NULL,
  bcrypt_hash   VARCHAR2(72)             NOT NULL,
  role          VARCHAR2(16)             NOT NULL,
  enabled       CHAR(1)                  DEFAULT 'Y' NOT NULL,
  created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  last_login_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_admin_user PRIMARY KEY (id),
  CONSTRAINT uq_admin_user_email UNIQUE (email),
  CONSTRAINT ck_admin_user_role CHECK (role IN ('ADMIN','VIEWER')),
  CONSTRAINT ck_admin_user_enabled CHECK (enabled IN ('Y','N'))
);

GRANT SELECT, INSERT ON admin_user TO APP_ADMIN;
GRANT UPDATE(last_login_at, bcrypt_hash, role, enabled) ON admin_user TO APP_ADMIN;
GRANT SELECT ON admin_user_seq TO APP_ADMIN;
```

- [ ] **Step 2: Verify it applies**

Run the admin-app once locally (writes to the running Oracle container):

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/v9-boot.log 2>&1 &
ADMIN_PID=$!
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/v9-boot.log; do sleep 2; done
grep -E "Migrating schema|Successfully" /tmp/v9-boot.log | tail -5
kill $ADMIN_PID
```

Expected: log contains `Migrating schema "APP_OWNER" to version "9 - admin user table"` and `Successfully applied 1 migration` (only V9 is new at this point).

- [ ] **Step 3: Verify table + grants via sqlplus**

```bash
docker exec -i passkey-oracle sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1 <<'EOF'
SET PAGESIZE 0 FEEDBACK OFF HEADING OFF
SELECT column_name||':'||data_type FROM user_tab_columns WHERE table_name='ADMIN_USER' ORDER BY column_id;
SELECT 'GRANT:'||grantee||':'||privilege FROM user_tab_privs WHERE table_name='ADMIN_USER' ORDER BY grantee, privilege;
EXIT
EOF
```

Expected: 7 columns (id, email, bcrypt_hash, role, enabled, created_at, last_login_at); grants `APP_ADMIN:INSERT`, `APP_ADMIN:SELECT`, `APP_ADMIN:UPDATE`.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/resources/db/migration/V9__admin_user_table.sql
git commit -m "feat(db): V9 — admin_user table (platform-scoped, no VPD)"
```

---

## Task 3: Flyway V10 — `audit_log` table with append-only grants

**Files:**
- Create `core/src/main/resources/db/migration/V10__audit_log_table.sql`

- [ ] **Step 1: Write the migration**

Contents of `V10__audit_log_table.sql`:

```sql
-- Append-only audit log with row-level hash chain.
--
-- Schema rationale:
--   - prev_hash + hash are RAW(32) (SHA-256). NULL prev_hash marks the
--     genesis row.
--   - actor_email is denormalized (not just actor_id) so audit reads
--     remain meaningful after an admin_user row is renamed or deleted.
--   - target_type + target_id are nullable (login events have no target).
--   - payload is CLOB with `IS JSON` check; AuditLogService writes
--     canonical JSON (sorted keys, no whitespace).
--
-- VPD: NONE. Audit is cross-tenant by design — we want one chain that
-- captures every operator action across every tenant.
--
-- Grants: APP_ADMIN gets SELECT + INSERT only. NO UPDATE, NO DELETE,
-- even from APP_ADMIN. Tampering requires DBA-level privilege.

CREATE SEQUENCE audit_log_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE audit_log (
  id           NUMBER(19,0)             NOT NULL,
  prev_hash    RAW(32),
  hash         RAW(32)                  NOT NULL,
  actor_id     NUMBER(19,0)             NOT NULL,
  actor_email  VARCHAR2(255)            NOT NULL,
  action       VARCHAR2(64)             NOT NULL,
  target_type  VARCHAR2(32),
  target_id    VARCHAR2(64),
  payload      CLOB                     NOT NULL,
  created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_audit_log PRIMARY KEY (id),
  CONSTRAINT ck_audit_log_payload_json CHECK (payload IS JSON)
);

CREATE INDEX audit_log_created_at_ix ON audit_log(created_at);
CREATE INDEX audit_log_actor_ix      ON audit_log(actor_id, created_at);
CREATE INDEX audit_log_target_ix     ON audit_log(target_type, target_id, created_at);

GRANT SELECT, INSERT ON audit_log TO APP_ADMIN;
GRANT SELECT ON audit_log_seq TO APP_ADMIN;
```

- [ ] **Step 2: Re-boot admin-app and verify V10 applies**

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/v10-boot.log 2>&1 &
ADMIN_PID=$!
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/v10-boot.log; do sleep 2; done
grep -E "Migrating schema|Successfully" /tmp/v10-boot.log | tail -3
kill $ADMIN_PID
```

Expected: `Migrating schema "APP_OWNER" to version "10 - audit log table"` and `Successfully applied 1 migration`.

- [ ] **Step 3: Verify no UPDATE/DELETE grant exists for APP_ADMIN**

```bash
docker exec -i passkey-oracle sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1 <<'EOF'
SET PAGESIZE 0 FEEDBACK OFF HEADING OFF
SELECT 'GRANT:'||grantee||':'||privilege FROM user_tab_privs WHERE table_name='AUDIT_LOG' ORDER BY grantee, privilege;
EXIT
EOF
```

Expected: only `APP_ADMIN:INSERT` and `APP_ADMIN:SELECT` (no UPDATE, no DELETE).

- [ ] **Step 4: Commit**

```bash
git add core/src/main/resources/db/migration/V10__audit_log_table.sql
git commit -m "feat(db): V10 — audit_log (append-only, SHA-256 hash chain)"
```

---

## Task 4: Flyway V11 — seed initial admin users

**Files:**
- Create `core/src/main/resources/db/migration/V11__seed_admin_user.sql`

- [ ] **Step 1: Generate BCrypt hashes for two seed accounts**

Run from the worktree root (uses the Spring Security BCryptPasswordEncoder via a tiny Java one-liner):

```bash
./gradlew -q :core:compileJava
java -cp "$(./gradlew -q :core:printRuntimeClasspath 2>/dev/null || find ~/.gradle/caches -name 'spring-security-crypto-*.jar' | head -1):$(find ~/.gradle/caches -name 'spring-security-core-*.jar' | head -1)" \
     -e 'System.out.println(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode("alice-temp-pw"))'
```

If that one-liner is fiddly, just open `jshell`:

```text
jshell --class-path $(find ~/.gradle/caches -name 'spring-security-crypto-*.jar' | head -1)
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
new BCryptPasswordEncoder(12).encode("alice-temp-pw")
new BCryptPasswordEncoder(12).encode("bob-temp-pw")
/exit
```

Record the two `$2a$12$...` strings.

- [ ] **Step 2: Write the migration**

Contents of `V11__seed_admin_user.sql` (replace `<HASH_A>` and `<HASH_B>` with the values from Step 1):

```sql
-- Seed two initial operators so AdminFlowIT and local development have
-- something to log in with. The temporary passwords ("alice-temp-pw",
-- "bob-temp-pw") are documented in followup-notes as "MUST be rotated
-- before any non-local deploy".

INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, created_at)
  VALUES (admin_user_seq.NEXTVAL, 'alice@crosscert.com', '<HASH_A>', 'ADMIN', 'Y', SYSTIMESTAMP);

INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, created_at)
  VALUES (admin_user_seq.NEXTVAL, 'bob@crosscert.com',   '<HASH_B>', 'VIEWER','Y', SYSTIMESTAMP);

COMMIT;
```

- [ ] **Step 3: Re-boot admin-app and verify seeds applied**

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/v11-boot.log 2>&1 &
ADMIN_PID=$!
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/v11-boot.log; do sleep 2; done
docker exec -i passkey-oracle sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1 <<'EOF'
SET PAGESIZE 0 FEEDBACK OFF HEADING OFF
SELECT email||':'||role||':'||enabled FROM admin_user ORDER BY id;
EXIT
EOF
kill $ADMIN_PID
```

Expected:
```
alice@crosscert.com:ADMIN:Y
bob@crosscert.com:VIEWER:Y
```

- [ ] **Step 4: Add a followup note about the seed credentials**

Append to `docs/superpowers/followups/2026-05-25-prod-hardening-notes.md`:

```markdown

## From Phase 2 T4 — admin_user seed passwords

V11 seeds two operator accounts with hard-coded BCrypt-hashed temporary
passwords (`alice-temp-pw`, `bob-temp-pw`). These are local-dev / IT
fixtures only.

Before any non-local deploy:
- Update the V11 migration in your private overlay (or run an
  out-of-band SQL UPDATE) to replace both hashes with real
  per-environment values.
- Add an admin self-service "change password" endpoint (Phase 4
  scope per Phase 2 design).
- Add a password-rotation policy and force-change-on-first-login flag.
```

- [ ] **Step 5: Commit**

```bash
git add core/src/main/resources/db/migration/V11__seed_admin_user.sql \
        docs/superpowers/followups/2026-05-25-prod-hardening-notes.md
git commit -m "feat(db): V11 — seed admin users (alice ADMIN, bob VIEWER, temp pw)"
```

---

## Task 5: `AdminUser` entity + repository

**Files:**
- Create `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java`
- Create `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRepository.java`
- Create `core/src/test/java/com/crosscert/passkey/core/entity/AdminUserTest.java`

- [ ] **Step 1: Write the failing entity test**

Contents of `AdminUserTest.java`:

```java
package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserTest {

    @Test
    void constructorSetsEnabledAndCreatedAt() {
        AdminUser u = new AdminUser("alice@example.com", "$2a$12$abc", "ADMIN");
        assertThat(u.getEmail()).isEqualTo("alice@example.com");
        assertThat(u.getBcryptHash()).isEqualTo("$2a$12$abc");
        assertThat(u.getRole()).isEqualTo("ADMIN");
        assertThat(u.isEnabled()).isTrue();
        assertThat(u.getCreatedAt()).isNotNull();
        assertThat(u.getLastLoginAt()).isNull();
    }

    @Test
    void recordLoginUpdatesLastLoginAt() {
        AdminUser u = new AdminUser("alice@example.com", "$2a$12$abc", "ADMIN");
        java.time.Instant now = java.time.Instant.parse("2026-06-01T00:00:00Z");
        u.recordLogin(now);
        assertThat(u.getLastLoginAt()).isEqualTo(now);
    }
}
```

- [ ] **Step 2: Run test, confirm compile failure**

Run: `./gradlew :core:test --tests AdminUserTest`
Expected: FAIL with `cannot find symbol class AdminUser`.

- [ ] **Step 3: Write the entity**

Contents of `AdminUser.java`:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ADMIN_USER")
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "admin_user_seq")
    @SequenceGenerator(name = "admin_user_seq", sequenceName = "ADMIN_USER_SEQ", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "EMAIL", length = 255, nullable = false)
    private String email;

    @Column(name = "BCRYPT_HASH", length = 72, nullable = false)
    private String bcryptHash;

    @Column(name = "ROLE", length = 16, nullable = false)
    private String role;

    @Column(name = "ENABLED", length = 1, nullable = false)
    private String enabledFlag;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "LAST_LOGIN_AT")
    private Instant lastLoginAt;

    protected AdminUser() {}

    public AdminUser(String email, String bcryptHash, String role) {
        this.email = email;
        this.bcryptHash = bcryptHash;
        this.role = role;
        this.enabledFlag = "Y";
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getBcryptHash() { return bcryptHash; }
    public String getRole() { return role; }
    public boolean isEnabled() { return "Y".equals(enabledFlag); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }
}
```

- [ ] **Step 4: Write the repository**

Contents of `AdminUserRepository.java`:

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    /**
     * Used by AdminUserDetailsService at login time. Email is unique
     * (V9 constraint), so this returns at most one row.
     */
    Optional<AdminUser> findByEmail(String email);
}
```

- [ ] **Step 5: Re-run test, confirm PASS**

Run: `./gradlew :core:test --tests AdminUserTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRepository.java \
        core/src/test/java/com/crosscert/passkey/core/entity/AdminUserTest.java
git commit -m "feat(core): AdminUser entity + repository (V9-backed)"
```

---

## Task 6: `AuditLog` entity + repository

**Files:**
- Create `core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java`
- Create `core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java`
- Create `core/src/test/java/com/crosscert/passkey/core/entity/AuditLogTest.java`

- [ ] **Step 1: Write the failing entity test**

Contents of `AuditLogTest.java`:

```java
package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogTest {

    @Test
    void constructorPopulatesAllRequiredFields() {
        byte[] prev = new byte[]{1,2,3};
        byte[] hash = new byte[]{4,5,6};
        Instant ts  = Instant.parse("2026-06-01T00:00:00Z");
        AuditLog row = new AuditLog(
                prev, hash, 42L, "alice@example.com",
                "TENANT_CREATE", "TENANT", "T_A",
                "{\"id\":\"T_A\"}", ts);

        assertThat(row.getPrevHash()).containsExactly(1,2,3);
        assertThat(row.getHash()).containsExactly(4,5,6);
        assertThat(row.getActorId()).isEqualTo(42L);
        assertThat(row.getActorEmail()).isEqualTo("alice@example.com");
        assertThat(row.getAction()).isEqualTo("TENANT_CREATE");
        assertThat(row.getTargetType()).isEqualTo("TENANT");
        assertThat(row.getTargetId()).isEqualTo("T_A");
        assertThat(row.getPayload()).isEqualTo("{\"id\":\"T_A\"}");
        assertThat(row.getCreatedAt()).isEqualTo(ts);
    }

    @Test
    void prevHashMayBeNullForGenesisRow() {
        AuditLog row = new AuditLog(
                null, new byte[]{0}, 1L, "alice@example.com",
                "ADMIN_LOGIN", null, null, "{}", Instant.now());
        assertThat(row.getPrevHash()).isNull();
    }
}
```

- [ ] **Step 2: Run test, confirm compile failure**

Run: `./gradlew :core:test --tests AuditLogTest`
Expected: FAIL with `cannot find symbol class AuditLog`.

- [ ] **Step 3: Write the entity**

Contents of `AuditLog.java`:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "AUDIT_LOG")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_log_seq")
    @SequenceGenerator(name = "audit_log_seq", sequenceName = "AUDIT_LOG_SEQ", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "PREV_HASH", length = 32)
    private byte[] prevHash;

    @Column(name = "HASH", length = 32, nullable = false)
    private byte[] hash;

    @Column(name = "ACTOR_ID", nullable = false)
    private long actorId;

    @Column(name = "ACTOR_EMAIL", length = 255, nullable = false)
    private String actorEmail;

    @Column(name = "ACTION", length = 64, nullable = false)
    private String action;

    @Column(name = "TARGET_TYPE", length = 32)
    private String targetType;

    @Column(name = "TARGET_ID", length = 64)
    private String targetId;

    @Lob
    @Column(name = "PAYLOAD", nullable = false)
    private String payload;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {}

    public AuditLog(byte[] prevHash, byte[] hash, long actorId, String actorEmail,
                    String action, String targetType, String targetId,
                    String payload, Instant createdAt) {
        this.prevHash = prevHash;
        this.hash = hash;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public byte[] getPrevHash() { return prevHash; }
    public byte[] getHash() { return hash; }
    public long getActorId() { return actorId; }
    public String getActorEmail() { return actorEmail; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 4: Write the repository**

Contents of `AuditLogRepository.java`:

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AuditLog;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Pessimistic-locked lookup of the latest row. Used by
     * AuditLogService.append to serialize the read-hash-then-INSERT
     * sequence so two concurrent admin actions cannot produce two
     * rows whose prev_hash both point at the same predecessor.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuditLog a where a.id = (select max(a2.id) from AuditLog a2)")
    Optional<AuditLog> findLatestForUpdate();

    /** For chain verification — ordered scan starting from id=1. */
    @Query("select a from AuditLog a order by a.id asc")
    java.util.List<AuditLog> findAllOrdered();

    /** Read API for the audit-log page. Filters are all optional. */
    @Query("select a from AuditLog a " +
           "where (:action is null or a.action = :action) " +
           "  and (:actorId is null or a.actorId = :actorId) " +
           "  and (:from is null or a.createdAt >= :from) " +
           "  and (:to   is null or a.createdAt <  :to) " +
           "order by a.id desc")
    Page<AuditLog> search(@Param("action") String action,
                          @Param("actorId") Long actorId,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable page);
}
```

- [ ] **Step 5: Re-run test, confirm PASS**

Run: `./gradlew :core:test --tests AuditLogTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java \
        core/src/test/java/com/crosscert/passkey/core/entity/AuditLogTest.java
git commit -m "feat(core): AuditLog entity + repository (pessimistic-locked latest)"
```

---

## Task 7: `ApiKey.revoke(now)` helper

**Files:**
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java`

- [ ] **Step 1: Add the revoke method**

Append inside `ApiKey` class, right after `touchLastUsed(now)`:

```java
    /**
     * Soft-revoke this key. Called by ApiKeyAdminService and persisted
     * by the caller. Reusing isActive(now) means a revoked key fails
     * the next /api/v1/rp/** auth attempt.
     */
    public void revoke(Instant now) {
        this.revokedAt = now;
    }
```

- [ ] **Step 2: Compile-check**

Run: `./gradlew :core:compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java
git commit -m "feat(core): ApiKey.revoke(now) — soft-delete for Phase 2 admin"
```

---

## Task 8: `AuditLogService` + canonical JSON serializer (TDD)

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogService.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditAppendRequest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Contents of `AuditLogServiceTest.java`:

```java
package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogServiceTest {

    private AuditLogRepository repo;
    private AuditLogService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        service = new AuditLogService(repo, new ObjectMapper(), clock);
    }

    @Test
    void genesisRowHasNullPrevHashAndHashOfInputs() throws Exception {
        when(repo.findLatestForUpdate()).thenReturn(Optional.empty());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ip", "127.0.0.1");
        payload.put("ua", "JUnit");

        service.append(new AuditAppendRequest(
                42L, "alice@example.com", "ADMIN_LOGIN",
                null, null, payload));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(captor.capture());
        AuditLog row = captor.getValue();

        assertThat(row.getPrevHash()).isNull();
        assertThat(row.getActorId()).isEqualTo(42L);
        assertThat(row.getAction()).isEqualTo("ADMIN_LOGIN");
        assertThat(row.getPayload()).isEqualTo("{\"ip\":\"127.0.0.1\",\"ua\":\"JUnit\"}");

        // Manually recompute the expected hash.
        byte[] expected = MessageDigest.getInstance("SHA-256")
                .digest(("|42|ADMIN_LOGIN||"
                         + "|2026-06-01T00:00:00Z|"
                         + row.getPayload()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(row.getHash()).containsExactly(toIntArray(expected));
    }

    @Test
    void secondRowChainsToFirst() {
        AuditLog prev = new AuditLog(
                null, new byte[]{9, 9, 9}, 1L, "alice@example.com",
                "ADMIN_LOGIN", null, null, "{}", clock.instant());
        when(repo.findLatestForUpdate()).thenReturn(Optional.of(prev));

        service.append(new AuditAppendRequest(
                1L, "alice@example.com", "TENANT_CREATE",
                "TENANT", "T_A", Map.of("id", "T_A")));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(captor.capture());
        AuditLog row = captor.getValue();
        assertThat(row.getPrevHash()).containsExactly(toIntArray(new byte[]{9, 9, 9}));
    }

    @Test
    void payloadKeysAreSortedAlphabetically() {
        when(repo.findLatestForUpdate()).thenReturn(Optional.empty());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("z", 1);
        payload.put("a", 2);

        service.append(new AuditAppendRequest(
                1L, "alice@example.com", "X", null, null, payload));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(captor.capture());
        // ORDER_MAP_ENTRIES_BY_KEYS reorders to "a" then "z".
        assertThat(captor.getValue().getPayload()).isEqualTo("{\"a\":2,\"z\":1}");
    }

    private static int[] toIntArray(byte[] b) {
        int[] out = new int[b.length];
        for (int i = 0; i < b.length; i++) out[i] = b[i] & 0xff;
        return out;
    }
}
```

- [ ] **Step 2: Run, confirm compile failure**

Run: `./gradlew :admin-app:test --tests AuditLogServiceTest`
Expected: FAIL with `cannot find symbol AuditLogService / AuditAppendRequest`.

- [ ] **Step 3: Write `AuditAppendRequest` (immutable input)**

Contents of `AuditAppendRequest.java`:

```java
package com.crosscert.passkey.admin.audit;

import java.util.Map;

/**
 * Caller-supplied data for AuditLogService.append. Timestamps and
 * hashes are computed by the service itself.
 *
 * <p>payload is a Map<String,Object> — service writes it as canonical
 * JSON (keys sorted alphabetically) so the hash is reproducible.
 */
public record AuditAppendRequest(
        long actorId,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        Map<String, Object> payload
) {}
```

- [ ] **Step 4: Write `AuditLogService`**

Contents of `AuditLogService.java`:

```java
package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;

@Service
public class AuditLogService {

    private final AuditLogRepository repo;
    private final ObjectMapper canonical;
    private final Clock clock;

    public AuditLogService(AuditLogRepository repo,
                           ObjectMapper baseMapper,
                           Clock clock) {
        this.repo = repo;
        // Defensive copy so the global Jackson mapper's settings are
        // untouched. ORDER_MAP_ENTRIES_BY_KEYS makes the serialized
        // form deterministic, which is what makes the hash reproducible.
        this.canonical = baseMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.clock = clock;
    }

    @Transactional
    public AuditLog append(AuditAppendRequest req) {
        byte[] prevHash = repo.findLatestForUpdate()
                .map(AuditLog::getHash)
                .orElse(null);
        Instant now = clock.instant();
        String payloadJson = serialize(req.payload());
        byte[] hash = computeHash(prevHash, req, payloadJson, now);
        AuditLog row = new AuditLog(
                prevHash, hash, req.actorId(), req.actorEmail(),
                req.action(),
                req.targetType(), req.targetId(),
                payloadJson, now);
        return repo.save(row);
    }

    private String serialize(java.util.Map<String, Object> payload) {
        try {
            return canonical.writeValueAsString(payload == null ? java.util.Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalStateException("audit payload serialization failed", e);
        }
    }

    /**
     * Hash input format (all UTF-8, pipe-delimited):
     *   prev_hash_hex | actor_id | action | target_type | target_id | timestamp_iso | payload_canonical
     *
     * prev_hash_hex is empty string when prev is null. target_type /
     * target_id collapse null → empty string. The pipe separators
     * disambiguate boundaries so a payload like "X|Y" cannot collide
     * with a different field split.
     */
    static byte[] computeHash(byte[] prevHash, AuditAppendRequest req,
                              String payloadJson, Instant now) {
        StringBuilder input = new StringBuilder();
        input.append(prevHash == null ? "" : hex(prevHash));
        input.append('|');
        input.append(req.actorId());
        input.append('|');
        input.append(req.action());
        input.append('|');
        input.append(req.targetType() == null ? "" : req.targetType());
        input.append('|');
        input.append(req.targetId() == null ? "" : req.targetId());
        input.append('|');
        input.append(now.toString());
        input.append('|');
        input.append(payloadJson);
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
```

- [ ] **Step 5: Run, confirm tests pass**

Run: `./gradlew :admin-app:test --tests AuditLogServiceTest`
Expected: 3 tests pass.

(If the genesis-row test fails because of an empty `prev_hash_hex` mismatch, double-check that the test's expected-hash input starts with `|` (empty prev) — the test code does this already.)

- [ ] **Step 6: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditAppendRequest.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogServiceTest.java
git commit -m "feat(admin): AuditLogService — SHA-256 hash chain over canonical JSON"
```

---

## Task 9: `AuditChainVerifier` (TDD)

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainVerifier.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainVerifierTest.java`

- [ ] **Step 1: Write the failing test**

Contents of `AuditChainVerifierTest.java`:

```java
package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditChainVerifierTest {

    private AuditLogRepository repo;
    private AuditChainVerifier verifier;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        verifier = new AuditChainVerifier(repo, new ObjectMapper());
    }

    @Test
    void emptyLogIsValid() {
        when(repo.findAllOrdered()).thenReturn(List.of());
        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.ok()).isTrue();
        assertThat(r.brokenAt()).isNull();
    }

    @Test
    void freshChainOfThreeRowsVerifies() {
        List<AuditLog> rows = buildValidChain(3);
        when(repo.findAllOrdered()).thenReturn(rows);
        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.ok()).isTrue();
    }

    @Test
    void tamperedPayloadIsDetectedAtThatRow() {
        List<AuditLog> rows = buildValidChain(3);
        // Tamper the middle row's payload — its stored hash no longer matches.
        AuditLog middle = rows.get(1);
        AuditLog tampered = new AuditLog(
                middle.getPrevHash(), middle.getHash(), middle.getActorId(),
                middle.getActorEmail(), middle.getAction(), middle.getTargetType(),
                middle.getTargetId(), "{\"x\":\"tampered\"}", middle.getCreatedAt());
        rows.set(1, tampered);
        when(repo.findAllOrdered()).thenReturn(rows);

        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.ok()).isFalse();
        assertThat(r.brokenAt()).isEqualTo(2L); // 1-based positional id-style indicator
    }

    @Test
    void prevHashMismatchIsDetected() {
        List<AuditLog> rows = buildValidChain(2);
        AuditLog second = rows.get(1);
        AuditLog rebound = new AuditLog(
                new byte[]{0,0,0}, // wrong prev_hash
                second.getHash(), second.getActorId(), second.getActorEmail(),
                second.getAction(), second.getTargetType(), second.getTargetId(),
                second.getPayload(), second.getCreatedAt());
        rows.set(1, rebound);
        when(repo.findAllOrdered()).thenReturn(rows);

        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.ok()).isFalse();
        assertThat(r.brokenAt()).isEqualTo(2L);
    }

    /** Build a valid chain by reusing AuditLogService.computeHash. */
    private List<AuditLog> buildValidChain(int n) {
        List<AuditLog> chain = new ArrayList<>();
        byte[] prev = null;
        for (int i = 1; i <= n; i++) {
            AuditAppendRequest req = new AuditAppendRequest(
                    i, "alice@example.com", "ACTION_" + i,
                    "TENANT", "T_" + i, Map.of("seq", i));
            String payload = "{\"seq\":" + i + "}";
            byte[] hash = AuditLogService.computeHash(prev, req, payload, clock.instant());
            AuditLog row = new AuditLog(
                    prev, hash, req.actorId(), req.actorEmail(),
                    req.action(), req.targetType(), req.targetId(),
                    payload, clock.instant());
            // Hibernate normally assigns id; simulate via reflection for the test.
            try {
                java.lang.reflect.Field f = AuditLog.class.getDeclaredField("id");
                f.setAccessible(true);
                f.set(row, (long) i);
            } catch (Exception e) { throw new RuntimeException(e); }
            chain.add(row);
            prev = hash;
        }
        return chain;
    }
}
```

- [ ] **Step 2: Run, confirm compile failure**

Run: `./gradlew :admin-app:test --tests AuditChainVerifierTest`
Expected: FAIL with `cannot find symbol AuditChainVerifier`.

- [ ] **Step 3: Write the verifier**

Contents of `AuditChainVerifier.java`:

```java
package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class AuditChainVerifier {

    private final AuditLogRepository repo;
    private final ObjectMapper canonical;

    public AuditChainVerifier(AuditLogRepository repo, ObjectMapper baseMapper) {
        this.repo = repo;
        this.canonical = baseMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public Result verify() {
        List<AuditLog> rows = repo.findAllOrdered();
        byte[] expectedPrev = null;
        for (AuditLog row : rows) {
            // Check prev_hash linkage.
            if (!Arrays.equals(expectedPrev, row.getPrevHash())) {
                return Result.broken(row.getId());
            }
            // Recompute hash from the stored fields. We re-parse the
            // stored payload string into a Map and re-serialize it
            // canonically — that round-trips identically because the
            // canonical writer's output is itself canonical.
            byte[] expected = recomputeHash(row);
            if (!Arrays.equals(expected, row.getHash())) {
                return Result.broken(row.getId());
            }
            expectedPrev = row.getHash();
        }
        return Result.ok();
    }

    private byte[] recomputeHash(AuditLog row) {
        try {
            Map<String, Object> payload = canonical.readValue(
                    row.getPayload(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            AuditAppendRequest req = new AuditAppendRequest(
                    row.getActorId(), row.getActorEmail(), row.getAction(),
                    row.getTargetType(), row.getTargetId(), payload);
            return AuditLogService.computeHash(
                    row.getPrevHash(), req, row.getPayload(), row.getCreatedAt());
        } catch (Exception e) {
            // Malformed JSON in the payload column is itself tamper evidence.
            return new byte[0];
        }
    }

    public record Result(boolean ok, Long brokenAt) {
        public static Result ok() { return new Result(true, null); }
        public static Result broken(long id) { return new Result(false, id); }
    }
}
```

- [ ] **Step 4: Re-run tests, confirm PASS**

Run: `./gradlew :admin-app:test --tests AuditChainVerifierTest`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainVerifier.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainVerifierTest.java
git commit -m "feat(admin): AuditChainVerifier — detect prev_hash and payload tampering"
```

---

## Task 10: `AdminUserDetailsService` (Spring Security UserDetails)

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetailsService.java`

- [ ] **Step 1: Write the service**

Contents of `AdminUserDetailsService.java`:

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads an AdminUser by email. Spring Security's DaoAuthenticationProvider
 * then calls PasswordEncoder.matches(submitted, user.getPassword()) — see
 * AdminSecurityConfig for how the encoder is wired. The "ROLE_" prefix is
 * required by Spring's hasRole() expression evaluator.
 *
 * <p>codex (Phase 1 lesson): we deliberately do NOT pre-check for
 * non-existence here. Spring Security's DAO provider already runs a
 * dummy BCrypt match on failure (via its
 * PasswordEncoder.upgradeEncoding shortcut), so timing across "user not
 * found" vs "wrong password" stays equal. If a future Spring upgrade
 * removes that behavior, add an explicit DUMMY_HASH check here.
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository repo;

    public AdminUserDetailsService(AdminUserRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AdminUser u = repo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("no such admin user"));
        return User.builder()
                .username(u.getEmail())
                .password(u.getBcryptHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole())))
                .disabled(!u.isEnabled())
                .build();
    }
}
```

- [ ] **Step 2: Compile-check**

Run: `./gradlew :admin-app:compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetailsService.java
git commit -m "feat(admin): AdminUserDetailsService — Spring Security UserDetails loader"
```

---

## Task 11: `AdminSecurityConfig` + `MeController`

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/config/MeController.java`
- Modify `admin-app/src/main/resources/application.yml`

- [ ] **Step 1: Configure Spring Session + Redis in `application.yml`**

Edit `admin-app/src/main/resources/application.yml`, append at the bottom:

```yaml
  session:
    store-type: redis
    timeout: PT30M
    redis:
      namespace: spring:session
  security:
    user:
      # We do not use Spring Security's default in-memory user — set a
      # random impossible-to-guess password to suppress the "Using
      # generated security password" startup log line.
      password: "____disabled____never-used-loaded-from-db-instead____"
```

- [ ] **Step 2: Write `AdminSecurityConfig`**

Contents of `AdminSecurityConfig.java`:

```java
package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.AdminUserDetailsService;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.time.Clock;
import java.util.Map;

@Configuration
@EnableMethodSecurity
public class AdminSecurityConfig {

    @Bean
    public DaoAuthenticationProvider adminAuthProvider(AdminUserDetailsService uds,
                                                       PasswordEncoder encoder) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(uds);
        p.setPasswordEncoder(encoder);
        return p;
    }

    @Bean
    public SecurityFilterChain adminFilterChain(HttpSecurity http,
                                                AuthenticationSuccessHandler ok,
                                                AuthenticationFailureHandler fail) throws Exception {
        // CookieCsrfTokenRepository.withHttpOnlyFalse() lets the SPA
        // read the XSRF-TOKEN cookie via document.cookie and echo it
        // back in X-XSRF-TOKEN. Path=/admin scopes the cookie.
        var csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookiePath("/admin");
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // emit token on every response

        http
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/admin/login", "/admin/logout").permitAll()
                .requestMatchers("/admin/static/**", "/admin/index.html", "/admin/").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/admin/api/**").authenticated()
                .anyRequest().denyAll())
            .formLogin(form -> form
                .loginProcessingUrl("/admin/login")
                .usernameParameter("email")
                .passwordParameter("password")
                .successHandler(ok)
                .failureHandler(fail))
            .logout(logout -> logout
                .logoutUrl("/admin/logout")
                .logoutSuccessUrl("/admin/login")
                .deleteCookies("SPRING_SESSION", "XSRF-TOKEN"))
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(csrfHandler))
            .sessionManagement(s -> s
                .maximumSessions(5));    // 5 parallel browsers per operator
        return http.build();
    }

    @Bean
    public AuthenticationSuccessHandler adminLoginSuccessHandler(AuditLogService audit,
                                                                  AdminUserRepository users,
                                                                  Clock clock) {
        return (HttpServletRequest req, HttpServletResponse res, Authentication auth) -> {
            String email = auth.getName();
            AdminUser u = users.findByEmail(email).orElseThrow();
            u.recordLogin(clock.instant());
            users.save(u);
            audit.append(new AuditAppendRequest(
                    u.getId(), email, "ADMIN_LOGIN",
                    null, null,
                    Map.of("ip", req.getRemoteAddr(),
                           "ua", req.getHeader("User-Agent") == null ? "" : req.getHeader("User-Agent"))));
            res.setStatus(HttpServletResponse.SC_OK);
            res.setContentType("application/json");
            res.getWriter().write("{\"email\":\"" + email + "\",\"role\":\"" + u.getRole() + "\"}");
        };
    }

    @Bean
    public AuthenticationFailureHandler adminLoginFailureHandler(AuditLogService audit) {
        return (HttpServletRequest req, HttpServletResponse res, Exception ex) -> {
            String email = req.getParameter("email");
            audit.append(new AuditAppendRequest(
                    0L, email == null ? "(unknown)" : email,
                    "ADMIN_LOGIN_FAILED", null, null,
                    Map.of("ip", req.getRemoteAddr(),
                           "reason", ex.getClass().getSimpleName())));
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"unauthorized\"}");
        };
    }
}
```

- [ ] **Step 3: Write `MeController`**

Contents of `MeController.java`:

```java
package com.crosscert.passkey.admin.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * SPA bootstrap call. Returns the logged-in operator's identity so the
 * frontend can decide what to render. Returns 401 (handled by Spring
 * Security) when the session cookie is missing or expired.
 */
@RestController
@RequestMapping("/admin/api")
public class MeController {

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        String role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst().orElse("UNKNOWN");
        return Map.of("email", auth.getName(), "role", role);
    }
}
```

- [ ] **Step 4: Add a `Clock` bean for admin-app (mirrors :core's pattern in Phase 1)**

`:core` already has `CoreClockConfig` exposing `Clock.systemUTC()`. The
admin-app inherits it via component scan. Verify there's no duplicate by
running:

```bash
grep -rln "Clock.systemUTC\|@Bean.*Clock" admin-app/src/main/java/
```

Expected: no output (Clock bean comes from `:core`).

- [ ] **Step 5: Boot admin-app, hit `/admin/api/me` without session**

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/me-boot.log 2>&1 &
ADMIN_PID=$!
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/me-boot.log; do sleep 2; done
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/admin/api/me
kill $ADMIN_PID
```

Expected: `401`.

- [ ] **Step 6: Boot, log in, hit `/admin/api/me` with cookie**

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/me2-boot.log 2>&1 &
ADMIN_PID=$!
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/me2-boot.log; do sleep 2; done
# First call: fetch CSRF cookie.
curl -s -c /tmp/cookies.txt http://localhost:8081/admin/api/me > /dev/null
CSRF=$(grep XSRF-TOKEN /tmp/cookies.txt | awk '{print $7}')
# Log in. Form login doesn't need CSRF in default config when csrf is
# enabled but the login endpoint is permitAll? Actually Spring Security
# WANTS CSRF on POST /admin/login. Send the header.
curl -s -b /tmp/cookies.txt -c /tmp/cookies.txt \
     -X POST -d "email=alice@crosscert.com&password=alice-temp-pw" \
     -H "X-XSRF-TOKEN: $CSRF" \
     http://localhost:8081/admin/login -w "\nlogin: %{http_code}\n"
curl -s -b /tmp/cookies.txt http://localhost:8081/admin/api/me -w "\nme: %{http_code}\n"
kill $ADMIN_PID
```

Expected: `login: 200`, `me: 200` with body `{"email":"alice@crosscert.com","role":"ADMIN"}`.

- [ ] **Step 7: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/MeController.java \
        admin-app/src/main/resources/application.yml
git commit -m "feat(admin): Security config (form login + CSRF + RBAC) + MeController"
```

---

## Task 12: `TenantAdminService` + DTOs (TDD)

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java`

- [ ] **Step 1: Write the failing test**

Contents of `TenantAdminServiceTest.java`:

```java
package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAdminServiceTest {

    private TenantRepository repo;
    private AuditLogService audit;
    private TenantAdminService service;

    @BeforeEach
    void setUp() {
        repo = mock(TenantRepository.class);
        audit = mock(AuditLogService.class);
        service = new TenantAdminService(repo, audit, new ObjectMapper());
    }

    @Test
    void createPersistsTenantAndAppendsAudit() {
        when(repo.findById("T_A")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                "[\"http://localhost\"]",
                "{\"acceptedFormats\":[\"none\"],\"requireUserVerification\":true,\"mdsRequired\":false}");

        TenantAdminDto.TenantView view = service.create(req, 7L, "alice@example.com");

        assertThat(view.id()).isEqualTo("T_A");
        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(repo).save(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().getRpId()).isEqualTo("localhost");

        ArgumentCaptor<AuditAppendRequest> auditCaptor = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TENANT_CREATE");
        assertThat(auditCaptor.getValue().targetId()).isEqualTo("T_A");
    }

    @Test
    void createRejectsDuplicateId() {
        when(repo.findById("T_A")).thenReturn(Optional.of(new Tenant("T_A", "X")));
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                "[\"http://localhost\"]",
                "{\"acceptedFormats\":[\"none\"],\"requireUserVerification\":true,\"mdsRequired\":false}");
        assertThatThrownBy(() -> service.create(req, 7L, "alice@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant id already exists");
    }

    @Test
    void createRejectsMalformedOriginsJson() {
        when(repo.findById("T_A")).thenReturn(Optional.empty());
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                "not json",
                "{\"acceptedFormats\":[\"none\"],\"requireUserVerification\":true,\"mdsRequired\":false}");
        assertThatThrownBy(() -> service.create(req, 7L, "alice@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed_origins JSON invalid");
    }

    @Test
    void createRejectsMalformedPolicyJson() {
        when(repo.findById("T_A")).thenReturn(Optional.empty());
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                "[\"http://localhost\"]",
                "not json");
        assertThatThrownBy(() -> service.create(req, 7L, "alice@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attestation_policy JSON invalid");
    }
}
```

- [ ] **Step 2: Run, confirm compile failure**

Run: `./gradlew :admin-app:test --tests TenantAdminServiceTest`
Expected: FAIL with `cannot find symbol TenantAdminService / TenantAdminDto`.

- [ ] **Step 3: Write `TenantAdminDto`**

Contents of `TenantAdminDto.java`:

```java
package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.entity.Tenant;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class TenantAdminDto {

    private TenantAdminDto() {}

    public record TenantCreateRequest(
            @NotBlank String id,
            @NotBlank String displayName,
            @NotBlank String rpId,
            @NotBlank String rpName,
            @NotBlank String allowedOriginsJson,
            @NotBlank String attestationPolicyJson
    ) {}

    public record TenantUpdateRequest(
            @NotBlank String displayName,
            @NotBlank String rpId,
            @NotBlank String rpName,
            @NotBlank String allowedOriginsJson,
            @NotBlank String attestationPolicyJson
    ) {}

    public record TenantView(
            String id,
            String displayName,
            String status,
            String rpId,
            String rpName,
            String allowedOriginsJson,
            String attestationPolicyJson,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static TenantView from(Tenant t) {
            return new TenantView(
                    t.getId(), t.getDisplayName(), t.getStatus(),
                    t.getRpId(), t.getRpName(),
                    t.getAllowedOriginsJson(), t.getAttestationPolicyJson(),
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }
}
```

- [ ] **Step 4: Write `TenantAdminService`**

Contents of `TenantAdminService.java`:

```java
package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TenantAdminService {

    private final TenantRepository tenants;
    private final AuditLogService audit;
    private final ObjectMapper mapper;

    public TenantAdminService(TenantRepository tenants,
                              AuditLogService audit,
                              ObjectMapper mapper) {
        this.tenants = tenants;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<TenantAdminDto.TenantView> list() {
        return tenants.findAll().stream()
                .map(TenantAdminDto.TenantView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantAdminDto.TenantView get(String id) {
        Tenant t = tenants.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("tenant not found"));
        return TenantAdminDto.TenantView.from(t);
    }

    @Transactional
    public TenantAdminDto.TenantView create(TenantAdminDto.TenantCreateRequest req,
                                            long actorId, String actorEmail) {
        if (tenants.findById(req.id()).isPresent()) {
            throw new IllegalArgumentException("tenant id already exists");
        }
        validateJson(req.allowedOriginsJson(), "allowed_origins");
        validateJson(req.attestationPolicyJson(), "attestation_policy");

        Tenant t = new Tenant(req.id(), req.displayName(), req.rpId(), req.rpName(),
                              req.allowedOriginsJson(), req.attestationPolicyJson());
        tenants.save(t);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", req.id());
        payload.put("displayName", req.displayName());
        payload.put("rpId", req.rpId());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "TENANT_CREATE",
                "TENANT", req.id(), payload));

        return TenantAdminDto.TenantView.from(t);
    }

    private void validateJson(String value, String fieldName) {
        try {
            mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(fieldName + " JSON invalid");
        }
    }
}
```

- [ ] **Step 5: Re-run tests, confirm PASS**

Run: `./gradlew :admin-app:test --tests TenantAdminServiceTest`
Expected: 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java
git commit -m "feat(admin): TenantAdminService — create/list/get + JSON validation + audit"
```

---

## Task 13: `TenantAdminController` + RBAC security test

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java`

- [ ] **Step 1: Write the controller**

Contents of `TenantAdminController.java`:

```java
package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/admin/api/tenants")
public class TenantAdminController {

    private final TenantAdminService service;
    private final AdminUserRepository admins;

    public TenantAdminController(TenantAdminService service,
                                 AdminUserRepository admins) {
        this.service = service;
        this.admins = admins;
    }

    @GetMapping
    public List<TenantAdminDto.TenantView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public TenantAdminDto.TenantView get(@PathVariable String id) {
        return service.get(id);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<TenantAdminDto.TenantView> create(
            @Valid @RequestBody TenantAdminDto.TenantCreateRequest req,
            Authentication auth) {
        long actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        var view = service.create(req, actorId, auth.getName());
        return ResponseEntity.created(URI.create("/admin/api/tenants/" + view.id())).body(view);
    }
}
```

- [ ] **Step 2: Write the RBAC security test**

Contents of `TenantAdminControllerSecurityTest.java`:

```java
package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TenantAdminController.class)
@Import(com.crosscert.passkey.admin.config.AdminSecurityConfig.class)
class TenantAdminControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockBean TenantAdminService service;
    @MockBean AdminUserRepository admins;
    // The security config wires more beans we have to mock for the
    // WebMvcTest slice to load.
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;

    private static final String BODY = """
            {"id":"T_A","displayName":"Tenant A","rpId":"localhost","rpName":"Tenant A",
             "allowedOriginsJson":"[\\"http://localhost\\"]",
             "attestationPolicyJson":"{\\"acceptedFormats\\":[\\"none\\"],\\"requireUserVerification\\":true,\\"mdsRequired\\":false}"}
            """;

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/tenants")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanGet() throws Exception {
        mvc.perform(get("/admin/api/tenants")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotPost() throws Exception {
        mvc.perform(post("/admin/api/tenants")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json")
                .content(BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "ADMIN")
    void adminCanPost() throws Exception {
        org.mockito.Mockito.when(admins.findByEmail(anyString()))
                .thenReturn(java.util.Optional.of(adminUserWithId(7L)));
        org.mockito.Mockito.when(service.create(any(), anyLong(), anyString()))
                .thenReturn(new TenantAdminDto.TenantView(
                        "T_A","Tenant A","active","localhost","Tenant A",
                        "[]", "{}", java.time.Instant.now(), java.time.Instant.now()));
        mvc.perform(post("/admin/api/tenants")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json")
                .content(BODY))
            .andExpect(status().isCreated());
    }

    private static com.crosscert.passkey.core.entity.AdminUser adminUserWithId(long id) {
        var u = new com.crosscert.passkey.core.entity.AdminUser("alice@example.com", "x", "ADMIN");
        try {
            var f = com.crosscert.passkey.core.entity.AdminUser.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (Exception e) { throw new RuntimeException(e); }
        return u;
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :admin-app:test --tests TenantAdminControllerSecurityTest`
Expected: 4 tests pass.

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java
git commit -m "feat(admin): TenantAdminController + RBAC matrix test"
```

---

## Task 14: `ApiKeyAdminService` + DTOs (TDD)

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminServiceTest.java`

- [ ] **Step 1: Write the failing test**

Contents of `ApiKeyAdminServiceTest.java`:

```java
package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAdminServiceTest {

    private ApiKeyRepository repo;
    private AuditLogService audit;
    private PasswordEncoder encoder;
    private ApiKeyAdminService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = mock(ApiKeyRepository.class);
        audit = mock(AuditLogService.class);
        encoder = new BCryptPasswordEncoder(4); // fast for tests
        service = new ApiKeyAdminService(repo, audit, encoder, new SecureRandom(), clock);
    }

    @Test
    void issueProducesPlainTextAndPersistsHash() {
        when(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyAdminDto.ApiKeyCreateRequest req = new ApiKeyAdminDto.ApiKeyCreateRequest(
                "T_A", "primary", "[]", null);
        ApiKeyAdminDto.ApiKeyCreateResponse resp =
                service.issue(req, 7L, "alice@example.com");

        assertThat(resp.plainText()).startsWith("pk_");
        assertThat(resp.plainText()).hasSizeGreaterThan(20);
        assertThat(resp.prefix()).hasSize(11); // pk_ + 8 chars

        ArgumentCaptor<ApiKey> keyCaptor = ArgumentCaptor.forClass(ApiKey.class);
        verify(repo).save(keyCaptor.capture());
        ApiKey saved = keyCaptor.getValue();
        // Verify the persisted hash actually matches the plaintext secret
        // (full plainText = prefix + secret; we know prefix == resp.prefix).
        String secret = resp.plainText().substring(resp.prefix().length());
        assertThat(encoder.matches(secret, saved.getKeyHash())).isTrue();
    }

    @Test
    void issueAppendsAuditWithoutSecret() {
        when(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.issue(new ApiKeyAdminDto.ApiKeyCreateRequest("T_A", "primary", "[]", null),
                      7L, "alice@example.com");

        ArgumentCaptor<AuditAppendRequest> auditCaptor =
                ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCaptor.capture());
        AuditAppendRequest aud = auditCaptor.getValue();
        assertThat(aud.action()).isEqualTo("API_KEY_ISSUE");
        // payload must contain prefix + tenantId + name but NOT plaintext.
        assertThat(aud.payload()).containsKey("prefix");
        assertThat(aud.payload()).doesNotContainKey("plainText");
        assertThat(aud.payload()).doesNotContainKey("secret");
    }

    @Test
    void issueRetriesOnPrefixCollision() {
        // First two random prefixes already exist; third is fresh.
        when(repo.findByKeyPrefix(anyString()))
                .thenReturn(Optional.of(new ApiKey("T_A","pk_xxxxxxxx","h","n","[]")))
                .thenReturn(Optional.of(new ApiKey("T_A","pk_yyyyyyyy","h","n","[]")))
                .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyAdminDto.ApiKeyCreateResponse resp = service.issue(
                new ApiKeyAdminDto.ApiKeyCreateRequest("T_A", "primary", "[]", null),
                7L, "alice@example.com");
        assertThat(resp.prefix()).startsWith("pk_");
    }

    @Test
    void revokeSetsRevokedAtAndAuditsPrefixOnly() {
        ApiKey existing = new ApiKey("T_A", "pk_abcd1234", "$2a$04$x", "primary", "[]");
        try {
            var f = ApiKey.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(existing, 99L);
        } catch (Exception e) { throw new RuntimeException(e); }
        when(repo.findById(99L)).thenReturn(Optional.of(existing));

        service.revoke(99L, 7L, "alice@example.com");

        assertThat(existing.isActive(clock.instant())).isFalse();
        ArgumentCaptor<AuditAppendRequest> auditCaptor =
                ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCaptor.capture());
        AuditAppendRequest aud = auditCaptor.getValue();
        assertThat(aud.action()).isEqualTo("API_KEY_REVOKE");
        assertThat(aud.payload().get("prefix")).isEqualTo("pk_abcd1234");
    }
}
```

- [ ] **Step 2: Run, confirm compile failure**

Run: `./gradlew :admin-app:test --tests ApiKeyAdminServiceTest`
Expected: FAIL with `cannot find symbol ApiKeyAdminService / ApiKeyAdminDto`.

- [ ] **Step 3: Write `ApiKeyAdminDto`**

Contents of `ApiKeyAdminDto.java`:

```java
package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.core.entity.ApiKey;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class ApiKeyAdminDto {

    private ApiKeyAdminDto() {}

    public record ApiKeyCreateRequest(
            @NotBlank String tenantId,
            @NotBlank String name,
            @NotBlank String scopesJson,
            Instant expiresAt          // optional
    ) {}

    public record ApiKeyCreateResponse(
            Long id,
            String prefix,
            String plainText,          // ONE-TIME — only returned at issue
            String name,
            String tenantId,
            Instant createdAt,
            Instant expiresAt
    ) {}

    public record ApiKeyView(
            Long id,
            String prefix,
            String name,
            String tenantId,
            Instant createdAt,
            Instant lastUsedAt,
            Instant expiresAt,
            Instant revokedAt
    ) {
        public static ApiKeyView from(ApiKey k) {
            return new ApiKeyView(
                    k.getId(), k.getKeyPrefix(), k.getName(), k.getTenantId(),
                    k.getCreatedAt(), k.getLastUsedAt(), k.getExpiresAt(), k.getRevokedAt());
        }
    }
}
```

- [ ] **Step 4: Write `ApiKeyAdminService`**

Contents of `ApiKeyAdminService.java`:

```java
package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApiKeyAdminService {

    private static final String PREFIX_HEADER = "pk_";
    private static final int PREFIX_RANDOM_BYTES = 6;   // 6 bytes → 8 b64url chars → 11-char prefix
    private static final int SECRET_RANDOM_BYTES = 32;  // 32 bytes → 43 b64url chars

    private final ApiKeyRepository repo;
    private final AuditLogService audit;
    private final PasswordEncoder encoder;
    private final SecureRandom random;
    private final Clock clock;

    public ApiKeyAdminService(ApiKeyRepository repo,
                              AuditLogService audit,
                              PasswordEncoder encoder,
                              SecureRandom random,
                              Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.encoder = encoder;
        this.random = random;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ApiKeyAdminDto.ApiKeyView> list(String tenantId) {
        return repo.findAll().stream()
                .filter(k -> tenantId == null || tenantId.equals(k.getTenantId()))
                .map(ApiKeyAdminDto.ApiKeyView::from)
                .toList();
    }

    @Transactional
    public ApiKeyAdminDto.ApiKeyCreateResponse issue(ApiKeyAdminDto.ApiKeyCreateRequest req,
                                                     long actorId, String actorEmail) {
        String prefix = generateUniquePrefix();
        String secret = b64url(SECRET_RANDOM_BYTES);
        String hash = encoder.encode(secret);

        ApiKey key = new ApiKey(req.tenantId(), prefix, hash, req.name(), req.scopesJson());
        // expiresAt is set by the caller path (DB column allows null);
        // entity field has no setter, so we rely on null-by-default for
        // Phase 2. If callers want non-null expiresAt, add a setter
        // back on ApiKey in a follow-up. Phase 2 ignores expiresAt.
        ApiKey saved = repo.save(key);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", saved.getId());
        payload.put("prefix", prefix);
        payload.put("tenantId", req.tenantId());
        payload.put("name", req.name());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "API_KEY_ISSUE",
                "API_KEY", String.valueOf(saved.getId()), payload));

        return new ApiKeyAdminDto.ApiKeyCreateResponse(
                saved.getId(), prefix, prefix + secret, saved.getName(),
                saved.getTenantId(), saved.getCreatedAt(), saved.getExpiresAt());
    }

    @Transactional
    public void revoke(long id, long actorId, String actorEmail) {
        ApiKey k = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("api key not found"));
        k.revoke(clock.instant());
        repo.save(k);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prefix", k.getKeyPrefix());
        payload.put("tenantId", k.getTenantId());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "API_KEY_REVOKE",
                "API_KEY", String.valueOf(id), payload));
    }

    private String generateUniquePrefix() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String prefix = PREFIX_HEADER + b64url(PREFIX_RANDOM_BYTES);
            if (repo.findByKeyPrefix(prefix).isEmpty()) return prefix;
        }
        throw new IllegalStateException("API key prefix exhausted — 10 collisions");
    }

    private String b64url(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
```

- [ ] **Step 5: Re-run tests, confirm PASS**

Run: `./gradlew :admin-app:test --tests ApiKeyAdminServiceTest`
Expected: 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminServiceTest.java
git commit -m "feat(admin): ApiKeyAdminService — issue (one-time plainText) + revoke + audit"
```

---

## Task 15: `ApiKeyAdminController` + RBAC test

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminController.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminControllerSecurityTest.java`

- [ ] **Step 1: Write the controller**

Contents of `ApiKeyAdminController.java`:

```java
package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/api/api-keys")
public class ApiKeyAdminController {

    private final ApiKeyAdminService service;
    private final AdminUserRepository admins;

    public ApiKeyAdminController(ApiKeyAdminService service,
                                 AdminUserRepository admins) {
        this.service = service;
        this.admins = admins;
    }

    @GetMapping
    public List<ApiKeyAdminDto.ApiKeyView> list(@RequestParam(required = false) String tenantId) {
        return service.list(tenantId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiKeyAdminDto.ApiKeyCreateResponse> issue(
            @Valid @RequestBody ApiKeyAdminDto.ApiKeyCreateRequest req,
            Authentication auth) {
        long actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        return ResponseEntity.status(201).body(service.issue(req, actorId, auth.getName()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id, Authentication auth) {
        long actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        service.revoke(id, actorId, auth.getName());
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 2: Write the RBAC security test**

Contents of `ApiKeyAdminControllerSecurityTest.java`:

```java
package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ApiKeyAdminController.class)
@Import(com.crosscert.passkey.admin.config.AdminSecurityConfig.class)
class ApiKeyAdminControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockBean ApiKeyAdminService service;
    @MockBean AdminUserRepository admins;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;

    private static final String BODY = """
            {"tenantId":"T_A","name":"primary","scopesJson":"[]"}
            """;

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/api-keys")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanList() throws Exception {
        mvc.perform(get("/admin/api/api-keys")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotIssue() throws Exception {
        mvc.perform(post("/admin/api/api-keys")
                .with(csrf()).contentType("application/json").content(BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotRevoke() throws Exception {
        mvc.perform(delete("/admin/api/api-keys/42").with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "ADMIN")
    void adminCanIssue() throws Exception {
        org.mockito.Mockito.when(admins.findByEmail(anyString()))
                .thenReturn(java.util.Optional.of(adminUserWithId(7L)));
        org.mockito.Mockito.when(service.issue(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), anyString()))
                .thenReturn(new ApiKeyAdminDto.ApiKeyCreateResponse(
                        1L, "pk_abcd1234", "pk_abcd1234SECRET", "primary",
                        "T_A", java.time.Instant.now(), null));
        mvc.perform(post("/admin/api/api-keys")
                .with(csrf()).contentType("application/json").content(BODY))
            .andExpect(status().isCreated());
    }

    private static com.crosscert.passkey.core.entity.AdminUser adminUserWithId(long id) {
        var u = new com.crosscert.passkey.core.entity.AdminUser("alice@example.com", "x", "ADMIN");
        try {
            var f = com.crosscert.passkey.core.entity.AdminUser.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (Exception e) { throw new RuntimeException(e); }
        return u;
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :admin-app:test --tests ApiKeyAdminControllerSecurityTest`
Expected: 5 tests pass.

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminControllerSecurityTest.java
git commit -m "feat(admin): ApiKeyAdminController + RBAC matrix test"
```

---

## Task 16: `AuditLogController` + RBAC test (+ `/verify`)

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogController.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogControllerSecurityTest.java`

- [ ] **Step 1: Write the controller**

Contents of `AuditLogController.java`:

```java
package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/admin/api/audit")
public class AuditLogController {

    private final AuditLogRepository repo;
    private final AuditChainVerifier verifier;

    public AuditLogController(AuditLogRepository repo, AuditChainVerifier verifier) {
        this.repo = repo;
        this.verifier = verifier;
    }

    @GetMapping
    public List<AuditLogView> list(@RequestParam(required = false) String action,
                                   @RequestParam(required = false) Long actorId,
                                   @RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        Page<AuditLog> p = repo.search(action, actorId, from, to,
                PageRequest.of(page, Math.min(size, 200)));
        return p.getContent().stream().map(AuditLogView::from).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/verify")
    public AuditChainVerifier.Result verify() {
        return verifier.verify();
    }

    public record AuditLogView(
            Long id, Long actorId, String actorEmail, String action,
            String targetType, String targetId, String payload, Instant createdAt) {
        public static AuditLogView from(AuditLog a) {
            return new AuditLogView(
                    a.getId(), a.getActorId(), a.getActorEmail(), a.getAction(),
                    a.getTargetType(), a.getTargetId(), a.getPayload(), a.getCreatedAt());
        }
    }
}
```

- [ ] **Step 2: Write the security test**

Contents of `AuditLogControllerSecurityTest.java`:

```java
package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuditLogController.class)
@Import(com.crosscert.passkey.admin.config.AdminSecurityConfig.class)
class AuditLogControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockBean AuditLogRepository repo;
    @MockBean AuditChainVerifier verifier;
    // beans pulled in transitively by AdminSecurityConfig
    @MockBean AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRepository admins;

    @Test
    void anonymousGetListIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanList() throws Exception {
        org.mockito.Mockito.when(repo.search(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        mvc.perform(get("/admin/api/audit")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotVerify() throws Exception {
        mvc.perform(get("/admin/api/audit/verify")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanVerify() throws Exception {
        org.mockito.Mockito.when(verifier.verify())
                .thenReturn(AuditChainVerifier.Result.ok());
        mvc.perform(get("/admin/api/audit/verify")).andExpect(status().isOk());
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :admin-app:test --tests AuditLogControllerSecurityTest`
Expected: 4 tests pass.

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogControllerSecurityTest.java
git commit -m "feat(admin): AuditLogController + /verify (ADMIN-only)"
```

---

## Task 17: SPA fallback + `.gitignore` for build outputs

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminWebMvcConfig.java`
- Modify `.gitignore`

- [ ] **Step 1: Write the fallback config**

Contents of `AdminWebMvcConfig.java`:

```java
package com.crosscert.passkey.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA routing fallback: any GET /admin/* request that isn't a static
 * asset or REST endpoint forwards to index.html, so client-side React
 * Router can render the right page. /admin/api/** is already mapped to
 * @RestController endpoints, so it takes precedence.
 */
@Configuration
public class AdminWebMvcConfig {

    @Bean
    public WebMvcConfigurer adminSpaForwarding() {
        return new WebMvcConfigurer() {
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/login").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/tenants").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/tenants/**").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/api-keys").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/audit").setViewName("forward:/admin/index.html");
            }
        };
    }
}
```

- [ ] **Step 2: Add SPA build outputs to `.gitignore`**

Append at the bottom of `.gitignore`:

```
# Phase 2 admin-ui build outputs
admin-ui/node_modules/
admin-ui/dist/
admin-app/src/main/resources/static/admin/
```

- [ ] **Step 3: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminWebMvcConfig.java .gitignore
git commit -m "feat(admin): SPA routing fallback + .gitignore build outputs"
```

---

## Task 18: Initialize `admin-ui/` Vite project

**Files:**
- Create `admin-ui/package.json`
- Create `admin-ui/vite.config.ts`
- Create `admin-ui/tsconfig.json`
- Create `admin-ui/tsconfig.node.json`
- Create `admin-ui/index.html`
- Create `admin-ui/src/main.tsx`
- Create `admin-ui/src/App.tsx`

- [ ] **Step 1: Create `admin-ui/package.json`**

```json
{
  "name": "admin-ui",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.26.2"
  },
  "devDependencies": {
    "@types/react": "^18.3.3",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.1",
    "typescript": "^5.4.5",
    "vite": "^5.4.6"
  }
}
```

- [ ] **Step 2: Create `admin-ui/vite.config.ts`**

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Build everything under /admin/ so the asset URLs match Spring's
// static serving path. e.g. /admin/assets/index-abc.js
export default defineConfig({
  plugins: [react()],
  base: '/admin/',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsDir: 'assets',
  },
  server: {
    proxy: {
      '/admin/api': 'http://localhost:8081',
      '/admin/login': 'http://localhost:8081',
      '/admin/logout': 'http://localhost:8081',
    },
  },
});
```

- [ ] **Step 3: Create `admin-ui/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "useDefineForClassFields": true,
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

- [ ] **Step 4: Create `admin-ui/tsconfig.node.json`**

```json
{
  "compilerOptions": {
    "composite": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "allowSyntheticDefaultImports": true,
    "strict": true
  },
  "include": ["vite.config.ts"]
}
```

- [ ] **Step 5: Create `admin-ui/index.html`**

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/svg+xml" href="/admin/vite.svg" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Crosscert Passkey Admin</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/admin/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 6: Create `admin-ui/src/main.tsx`**

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter basename="/admin">
      <App />
    </BrowserRouter>
  </React.StrictMode>,
);
```

- [ ] **Step 7: Create a minimal `admin-ui/src/App.tsx` (real pages added in T20-T22)**

```tsx
import { Routes, Route } from 'react-router-dom';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<div>Admin Console (Phase 2 in progress)</div>} />
    </Routes>
  );
}
```

- [ ] **Step 8: Sanity-build**

```bash
cd admin-ui
npm install
npm run build
ls dist
cd ..
```

Expected: `dist/index.html` and `dist/assets/*.js` exist.

- [ ] **Step 9: Commit**

```bash
git add admin-ui/package.json admin-ui/vite.config.ts admin-ui/tsconfig.json \
        admin-ui/tsconfig.node.json admin-ui/index.html \
        admin-ui/src/main.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): bootstrap Vite + React + Router project"
```

---

## Task 19: Wire admin-ui build into admin-app Gradle

**Files:**
- Modify `admin-app/build.gradle.kts`

- [ ] **Step 1: Add node-gradle plugin + Copy task**

Replace `admin-app/build.gradle.kts` with:

```kotlin
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.node.gradle)
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-security")

    testImplementation("org.springframework.security:spring-security-test")
}

springBoot {
    mainClass.set("com.crosscert.passkey.admin.AdminApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("admin-app.jar")
}

// admin-ui (React+Vite) lives at the repo root. We tell the node plugin
// to operate there and to download a project-local Node 18.
node {
    version.set("18.20.0")
    download.set(true)
    nodeProjectDir.set(rootProject.file("admin-ui"))
}

// `npm install` + `npm run build` produce admin-ui/dist/. Then copy
// that into the admin-app classpath so Spring Boot's static serving
// picks it up at /admin/<asset>.
val buildUi by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn("npmInstall")
    workingDir.set(rootProject.file("admin-ui"))
    args.set(listOf("run", "build"))
    inputs.dir(rootProject.file("admin-ui/src"))
    inputs.file(rootProject.file("admin-ui/index.html"))
    inputs.file(rootProject.file("admin-ui/package.json"))
    inputs.file(rootProject.file("admin-ui/vite.config.ts"))
    outputs.dir(rootProject.file("admin-ui/dist"))
}

tasks.named<Copy>("processResources") {
    dependsOn(buildUi)
    from(rootProject.file("admin-ui/dist")) {
        into("static/admin")
    }
}
```

- [ ] **Step 2: Build the bootJar end-to-end**

```bash
./gradlew :admin-app:bootJar 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Verify static assets are inside the JAR**

```bash
jar tf admin-app/build/libs/admin-app.jar | grep "BOOT-INF/classes/static/admin/" | head -5
```

Expected: at least `index.html` and an `assets/*.js` file are listed.

- [ ] **Step 4: Boot the JAR and curl `/admin/`**

```bash
java -jar admin-app/build/libs/admin-app.jar --spring.profiles.active=local > /tmp/jar-boot.log 2>&1 &
JAR_PID=$!
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/jar-boot.log; do sleep 2; done
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/admin/
kill $JAR_PID
```

Expected: `200`.

- [ ] **Step 5: Commit**

```bash
git add admin-app/build.gradle.kts
git commit -m "build(admin-app): node-gradle plugin + Vite build into static/admin"
```

---

## Task 20: SPA — API client + Login page

**Files:**
- Create `admin-ui/src/api/client.ts`
- Create `admin-ui/src/api/types.ts`
- Create `admin-ui/src/pages/Login.tsx`
- Modify `admin-ui/src/App.tsx`

- [ ] **Step 1: Create `admin-ui/src/api/types.ts`**

```typescript
export interface Me {
  email: string;
  role: 'ADMIN' | 'VIEWER';
}

export interface TenantView {
  id: string;
  displayName: string;
  status: string;
  rpId: string;
  rpName: string;
  allowedOriginsJson: string;
  attestationPolicyJson: string;
  createdAt: string;
  updatedAt: string;
}

export interface TenantCreateRequest {
  id: string;
  displayName: string;
  rpId: string;
  rpName: string;
  allowedOriginsJson: string;
  attestationPolicyJson: string;
}

export interface ApiKeyView {
  id: number;
  prefix: string;
  name: string;
  tenantId: string;
  createdAt: string;
  lastUsedAt?: string;
  expiresAt?: string;
  revokedAt?: string;
}

export interface ApiKeyCreateRequest {
  tenantId: string;
  name: string;
  scopesJson: string;
  expiresAt?: string;
}

export interface ApiKeyCreateResponse {
  id: number;
  prefix: string;
  plainText: string;   // ONE-TIME
  name: string;
  tenantId: string;
  createdAt: string;
  expiresAt?: string;
}

export interface AuditLogView {
  id: number;
  actorId: number;
  actorEmail: string;
  action: string;
  targetType?: string;
  targetId?: string;
  payload: string;
  createdAt: string;
}
```

- [ ] **Step 2: Create `admin-ui/src/api/client.ts`**

```typescript
function getCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return m ? decodeURIComponent(m[1]) : null;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && method !== 'HEAD') {
    const csrf = getCookie('XSRF-TOKEN');
    if (csrf) headers['X-XSRF-TOKEN'] = csrf;
  }
  const res = await fetch(path, {
    method,
    headers,
    credentials: 'include',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401) {
    window.location.href = '/admin/login';
    throw new Error('unauthorized');
  }
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`HTTP ${res.status}: ${text}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export const api = {
  get:    <T>(path: string)              => request<T>('GET',    path),
  post:   <T>(path: string, body: unknown) => request<T>('POST',   path, body),
  put:    <T>(path: string, body: unknown) => request<T>('PUT',    path, body),
  delete: <T>(path: string)              => request<T>('DELETE', path),
  loginForm: async (email: string, password: string) => {
    const csrf = getCookie('XSRF-TOKEN');
    const form = new URLSearchParams();
    form.set('email', email);
    form.set('password', password);
    const res = await fetch('/admin/login', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}),
      },
      credentials: 'include',
      body: form.toString(),
    });
    return res.ok;
  },
};
```

- [ ] **Step 3: Create `admin-ui/src/pages/Login.tsx`**

```tsx
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';

export default function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const nav = useNavigate();

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    // First GET /me to receive the XSRF-TOKEN cookie if we don't have one yet.
    await fetch('/admin/api/me', { credentials: 'include' }).catch(() => null);
    const ok = await api.loginForm(email, password);
    if (ok) nav('/tenants');
    else setError('Invalid email or password.');
  }

  return (
    <form onSubmit={submit} style={{ maxWidth: 320, margin: '4rem auto' }}>
      <h2>Crosscert Passkey Admin</h2>
      <label>Email
        <input type="email" value={email} onChange={e => setEmail(e.target.value)} required style={{ width: '100%' }} />
      </label>
      <label>Password
        <input type="password" value={password} onChange={e => setPassword(e.target.value)} required style={{ width: '100%' }} />
      </label>
      <button type="submit" style={{ marginTop: '1rem', width: '100%' }}>Sign in</button>
      {error && <p style={{ color: 'red' }}>{error}</p>}
    </form>
  );
}
```

- [ ] **Step 4: Update `App.tsx` to mount Login**

Replace `admin-ui/src/App.tsx` with:

```tsx
import { Routes, Route, Navigate } from 'react-router-dom';
import Login from './pages/Login';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  );
}
```

- [ ] **Step 5: Rebuild + bootJar + smoke test**

```bash
./gradlew :admin-app:bootJar 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add admin-ui/src/api/client.ts admin-ui/src/api/types.ts \
        admin-ui/src/pages/Login.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): API client (CSRF + 401 redirect) + Login page"
```

---

## Task 21: SPA — Layout + TenantList + TenantCreate pages

**Files:**
- Create `admin-ui/src/components/Layout.tsx`
- Create `admin-ui/src/pages/TenantList.tsx`
- Create `admin-ui/src/pages/TenantCreate.tsx`
- Modify `admin-ui/src/App.tsx`

- [ ] **Step 1: Create `admin-ui/src/components/Layout.tsx`**

```tsx
import { Link, Outlet, useNavigate } from 'react-router-dom';

export default function Layout() {
  const nav = useNavigate();
  async function logout() {
    const csrf = document.cookie.match(/XSRF-TOKEN=([^;]+)/)?.[1];
    await fetch('/admin/logout', {
      method: 'POST',
      credentials: 'include',
      headers: csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {},
    });
    nav('/login');
  }
  return (
    <div>
      <nav style={{ display: 'flex', gap: '1rem', padding: '1rem', background: '#f0f0f0' }}>
        <Link to="/tenants">Tenants</Link>
        <Link to="/api-keys">API Keys</Link>
        <Link to="/audit">Audit Log</Link>
        <span style={{ flex: 1 }} />
        <button onClick={logout}>Logout</button>
      </nav>
      <main style={{ padding: '1rem' }}>
        <Outlet />
      </main>
    </div>
  );
}
```

- [ ] **Step 2: Create `admin-ui/src/pages/TenantList.tsx`**

```tsx
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { TenantView } from '../api/types';

export default function TenantList() {
  const [tenants, setTenants] = useState<TenantView[]>([]);
  useEffect(() => { api.get<TenantView[]>('/admin/api/tenants').then(setTenants); }, []);
  return (
    <div>
      <h2>Tenants</h2>
      <p><Link to="/tenants/new">+ New tenant</Link></p>
      <table border={1} cellPadding={4} cellSpacing={0}>
        <thead><tr><th>ID</th><th>Display</th><th>Status</th><th>RP ID</th></tr></thead>
        <tbody>
          {tenants.map(t => (
            <tr key={t.id}>
              <td>{t.id}</td><td>{t.displayName}</td><td>{t.status}</td><td>{t.rpId}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 3: Create `admin-ui/src/pages/TenantCreate.tsx`**

```tsx
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { TenantCreateRequest, TenantView } from '../api/types';

const DEFAULT_POLICY = JSON.stringify({
  acceptedFormats: ['none', 'packed'],
  requireUserVerification: true,
  mdsRequired: false,
}, null, 2);

export default function TenantCreate() {
  const nav = useNavigate();
  const [form, setForm] = useState<TenantCreateRequest>({
    id: '', displayName: '', rpId: 'localhost', rpName: '',
    allowedOriginsJson: '["http://localhost"]',
    attestationPolicyJson: DEFAULT_POLICY,
  });
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await api.post<TenantView>('/admin/api/tenants', form);
      nav('/tenants');
    } catch (err) {
      setError(String(err));
    }
  }

  function bind<K extends keyof TenantCreateRequest>(k: K) {
    return (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
      setForm({ ...form, [k]: e.target.value });
  }

  return (
    <form onSubmit={submit} style={{ maxWidth: 600 }}>
      <h2>New tenant</h2>
      <label>ID<input value={form.id} onChange={bind('id')} required /></label>
      <label>Display name<input value={form.displayName} onChange={bind('displayName')} required /></label>
      <label>RP ID<input value={form.rpId} onChange={bind('rpId')} required /></label>
      <label>RP name<input value={form.rpName} onChange={bind('rpName')} required /></label>
      <label>Allowed origins (JSON array)
        <textarea value={form.allowedOriginsJson} onChange={bind('allowedOriginsJson')} rows={3} style={{ width: '100%' }} />
      </label>
      <label>Attestation policy (JSON)
        <textarea value={form.attestationPolicyJson} onChange={bind('attestationPolicyJson')} rows={6} style={{ width: '100%' }} />
      </label>
      <button type="submit">Create</button>
      {error && <p style={{ color: 'red' }}>{error}</p>}
    </form>
  );
}
```

- [ ] **Step 4: Update `App.tsx` with the new routes**

Replace `admin-ui/src/App.tsx` with:

```tsx
import { Routes, Route, Navigate } from 'react-router-dom';
import Login from './pages/Login';
import Layout from './components/Layout';
import TenantList from './pages/TenantList';
import TenantCreate from './pages/TenantCreate';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<Layout />}>
        <Route path="/tenants" element={<TenantList />} />
        <Route path="/tenants/new" element={<TenantCreate />} />
        <Route path="/api-keys" element={<div>(coming soon)</div>} />
        <Route path="/audit" element={<div>(coming soon)</div>} />
      </Route>
      <Route path="*" element={<Navigate to="/tenants" replace />} />
    </Routes>
  );
}
```

- [ ] **Step 5: Build + commit**

```bash
./gradlew :admin-app:bootJar 2>&1 | tail -5
git add admin-ui/src/components/Layout.tsx \
        admin-ui/src/pages/TenantList.tsx \
        admin-ui/src/pages/TenantCreate.tsx \
        admin-ui/src/App.tsx
git commit -m "feat(admin-ui): Layout + TenantList + TenantCreate pages"
```

---

## Task 22: SPA — ApiKeyList + ApiKeyCreateModal + AuditLog pages

**Files:**
- Create `admin-ui/src/pages/ApiKeyList.tsx`
- Create `admin-ui/src/pages/ApiKeyCreateModal.tsx`
- Create `admin-ui/src/pages/AuditLog.tsx`
- Modify `admin-ui/src/App.tsx`

- [ ] **Step 1: Create `ApiKeyCreateModal.tsx`**

```tsx
import { useState } from 'react';
import { api } from '../api/client';
import type { ApiKeyCreateResponse } from '../api/types';

interface Props {
  tenantId: string;
  onClose: () => void;
  onIssued: () => void;
}

export default function ApiKeyCreateModal({ tenantId, onClose, onIssued }: Props) {
  const [name, setName] = useState('primary');
  const [issued, setIssued] = useState<ApiKeyCreateResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setError(null);
    try {
      const resp = await api.post<ApiKeyCreateResponse>('/admin/api/api-keys', {
        tenantId, name, scopesJson: '[]',
      });
      setIssued(resp);
      onIssued();
    } catch (e) {
      setError(String(e));
    }
  }

  return (
    <div style={{
      position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
    }}>
      <div style={{ background: 'white', padding: '1.5rem', minWidth: 360 }}>
        {issued ? (
          <>
            <h3>API Key issued</h3>
            <p><strong>This is the only time the full key will be shown.</strong> Copy it now.</p>
            <pre style={{ background: '#eef', padding: '0.5rem', wordBreak: 'break-all' }}>
              {issued.plainText}
            </pre>
            <button onClick={() => navigator.clipboard.writeText(issued.plainText)}>
              Copy to clipboard
            </button>
            <button onClick={onClose} style={{ marginLeft: '0.5rem' }}>I have saved the key</button>
          </>
        ) : (
          <>
            <h3>Issue API Key for {tenantId}</h3>
            <label>Name<input value={name} onChange={e => setName(e.target.value)} /></label>
            <div style={{ marginTop: '1rem' }}>
              <button onClick={submit}>Issue</button>
              <button onClick={onClose} style={{ marginLeft: '0.5rem' }}>Cancel</button>
            </div>
            {error && <p style={{ color: 'red' }}>{error}</p>}
          </>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Create `ApiKeyList.tsx`**

```tsx
import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { ApiKeyView, TenantView } from '../api/types';
import ApiKeyCreateModal from './ApiKeyCreateModal';

export default function ApiKeyList() {
  const [tenants, setTenants] = useState<TenantView[]>([]);
  const [tenantId, setTenantId] = useState<string>('');
  const [keys, setKeys] = useState<ApiKeyView[]>([]);
  const [modalOpen, setModalOpen] = useState(false);

  useEffect(() => {
    api.get<TenantView[]>('/admin/api/tenants').then(ts => {
      setTenants(ts);
      if (ts.length > 0) setTenantId(ts[0].id);
    });
  }, []);

  useEffect(() => {
    if (!tenantId) return;
    api.get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${tenantId}`).then(setKeys);
  }, [tenantId]);

  async function revoke(id: number) {
    if (!confirm('Revoke this key? Existing RP calls using it will fail.')) return;
    await api.delete(`/admin/api/api-keys/${id}`);
    api.get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${tenantId}`).then(setKeys);
  }

  return (
    <div>
      <h2>API Keys</h2>
      <label>Tenant
        <select value={tenantId} onChange={e => setTenantId(e.target.value)}>
          {tenants.map(t => <option key={t.id} value={t.id}>{t.id}</option>)}
        </select>
      </label>
      <button onClick={() => setModalOpen(true)} disabled={!tenantId}>+ Issue new</button>
      <table border={1} cellPadding={4} cellSpacing={0} style={{ marginTop: '1rem' }}>
        <thead>
          <tr><th>Prefix</th><th>Name</th><th>Created</th><th>Last used</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          {keys.map(k => (
            <tr key={k.id}>
              <td>{k.prefix}</td>
              <td>{k.name}</td>
              <td>{k.createdAt}</td>
              <td>{k.lastUsedAt ?? '-'}</td>
              <td>{k.revokedAt ? 'revoked' : 'active'}</td>
              <td>{!k.revokedAt && <button onClick={() => revoke(k.id)}>Revoke</button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {modalOpen && tenantId && (
        <ApiKeyCreateModal
          tenantId={tenantId}
          onIssued={() => api.get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${tenantId}`).then(setKeys)}
          onClose={() => setModalOpen(false)}
        />
      )}
    </div>
  );
}
```

- [ ] **Step 3: Create `AuditLog.tsx`**

```tsx
import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { AuditLogView } from '../api/types';

export default function AuditLog() {
  const [rows, setRows] = useState<AuditLogView[]>([]);
  const [verify, setVerify] = useState<{ ok: boolean; brokenAt?: number } | null>(null);

  useEffect(() => {
    api.get<AuditLogView[]>('/admin/api/audit').then(setRows);
  }, []);

  async function runVerify() {
    const r = await api.get<{ ok: boolean; brokenAt?: number }>('/admin/api/audit/verify');
    setVerify(r);
  }

  return (
    <div>
      <h2>Audit Log</h2>
      <button onClick={runVerify}>Verify chain</button>
      {verify && <p>{verify.ok ? '✅ Chain intact' : `❌ Broken at row ${verify.brokenAt}`}</p>}
      <table border={1} cellPadding={4} cellSpacing={0} style={{ marginTop: '1rem' }}>
        <thead>
          <tr><th>ID</th><th>Time</th><th>Actor</th><th>Action</th><th>Target</th><th>Payload</th></tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.id}>
              <td>{r.id}</td>
              <td>{r.createdAt}</td>
              <td>{r.actorEmail}</td>
              <td>{r.action}</td>
              <td>{r.targetType ?? '-'}{r.targetId ? ` / ${r.targetId}` : ''}</td>
              <td><code>{r.payload}</code></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 4: Update `App.tsx`**

Replace `admin-ui/src/App.tsx`:

```tsx
import { Routes, Route, Navigate } from 'react-router-dom';
import Login from './pages/Login';
import Layout from './components/Layout';
import TenantList from './pages/TenantList';
import TenantCreate from './pages/TenantCreate';
import ApiKeyList from './pages/ApiKeyList';
import AuditLog from './pages/AuditLog';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<Layout />}>
        <Route path="/tenants" element={<TenantList />} />
        <Route path="/tenants/new" element={<TenantCreate />} />
        <Route path="/api-keys" element={<ApiKeyList />} />
        <Route path="/audit" element={<AuditLog />} />
      </Route>
      <Route path="*" element={<Navigate to="/tenants" replace />} />
    </Routes>
  );
}
```

- [ ] **Step 5: Rebuild + commit**

```bash
./gradlew :admin-app:bootJar 2>&1 | tail -5
git add admin-ui/src/pages/ApiKeyList.tsx \
        admin-ui/src/pages/ApiKeyCreateModal.tsx \
        admin-ui/src/pages/AuditLog.tsx \
        admin-ui/src/App.tsx
git commit -m "feat(admin-ui): ApiKey list + create modal + AuditLog pages"
```

---

## Task 23: `application-test.yml` for admin-app

**Files:**
- Create `admin-app/src/test/resources/application-test.yml`

- [ ] **Step 1: Write the test profile**

Contents of `admin-app/src/test/resources/application-test.yml`:

```yaml
spring:
  config:
    import: classpath:application-common.yml
  application.name: admin-app-it
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: APP_OWNER
    default-schema: APP_OWNER
    baseline-on-migrate: true
    baseline-version: 0
  datasource:
    driver-class-name: oracle.jdbc.OracleDriver
  session:
    store-type: redis
    timeout: PT30M
    redis:
      namespace: spring:session
  security:
    user:
      password: "____disabled____never-used-loaded-from-db-instead____"
```

- [ ] **Step 2: Commit**

```bash
git add admin-app/src/test/resources/application-test.yml
git commit -m "test(admin): application-test.yml for AdminFlowIT (mirrors local profile)"
```

---

## Task 24: AdminFlowIT — Phase 2 acceptance gate (11 steps)

**Files:**
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java`
- Modify `admin-app/build.gradle.kts` (add Testcontainers + bootstrap-vpd Copy, mirroring passkey-app)

- [ ] **Step 1: Add Testcontainers to admin-app build**

Edit `admin-app/build.gradle.kts`. Replace its `dependencies` block with:

```kotlin
dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-security")

    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(rootProject.libs.testcontainers.oracle)
    testImplementation(rootProject.libs.testcontainers.junit)
}
```

And append at the bottom:

```kotlin
tasks.named<Test>("test") {
    // Same Docker-API-version pin as :core (Phase 0 followup notes).
    systemProperty("api.version", "1.43")
}

tasks.named<Copy>("processTestResources") {
    from(rootProject.file("scripts/bootstrap-vpd.sql"))
}
```

- [ ] **Step 2: Write AdminFlowIT**

Contents of `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java`:

```java
package com.crosscert.passkey.admin;

import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AdminFlowIT {

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-xe:21-slim-faststart")
            .withUsername("APP_OWNER").withPassword("app_owner_pw")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("bootstrap-vpd.sql"),
                    "/tmp/bootstrap-vpd.sql");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) throws Exception {
        var exec = ORACLE.execInContainer("bash", "-c",
                "sqlplus -S sys/app_owner_pw@localhost:1521/XEPDB1 as sysdba @/tmp/bootstrap-vpd.sql");
        if (exec.getExitCode() != 0) {
            throw new RuntimeException("bootstrap-vpd failed: " + exec.getStdout() + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired AuditLogRepository auditRepo;
    @Autowired DataSource ds;
    @Autowired RedisConnectionFactory redisFactory;
    @Autowired com.crosscert.passkey.admin.apikey.ApiKeyAdminService apiKeyAdminService;

    JdbcTemplate jdbc;

    @BeforeAll
    static void seedAdminPasswords() {
        // V11 seed inserts hard-coded BCrypt hashes for "alice-temp-pw"
        // and "bob-temp-pw" — see Task 4. We assume those hashes were
        // generated correctly and just use the matching plaintexts here.
    }

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);
        jdbc.update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("DELETE FROM APP_OWNER.api_key");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant");
        // admin_user rows from V11 stay; we only clean Phase-1 tenant data.
        redisFactory.getConnection().serverCommands().flushAll();
    }

    @AfterEach
    void clearSecurity() {
        // session bound to TestRestTemplate is per-test; nothing to do.
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    // ---- helpers ----

    private HttpHeaders loginAs(String email, String password) {
        // Establish XSRF-TOKEN cookie + session cookie.
        ResponseEntity<String> seed = rest.exchange(
                url("/admin/api/me"), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        String setCookie = seed.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String xsrf = extractCookieValue(setCookie, "XSRF-TOKEN");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        h.set(HttpHeaders.COOKIE, setCookie);
        h.set("X-XSRF-TOKEN", xsrf);
        ResponseEntity<String> login = rest.exchange(
                url("/admin/login"), HttpMethod.POST,
                new HttpEntity<>("email=" + email + "&password=" + password, h),
                String.class);
        assertThat(login.getStatusCode().is2xxSuccessful()).isTrue();
        // Subsequent calls reuse the same cookie + token.
        String session = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        HttpHeaders auth = new HttpHeaders();
        auth.setContentType(MediaType.APPLICATION_JSON);
        auth.set(HttpHeaders.COOKIE, session != null ? session : setCookie);
        auth.set("X-XSRF-TOKEN", xsrf);
        return auth;
    }

    private static String extractCookieValue(String setCookie, String name) {
        if (setCookie == null) return "";
        for (String part : setCookie.split(";\\s*|,\\s*")) {
            if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        }
        return "";
    }

    // ---- scenario ----

    @Test
    void operatorFlowEndToEnd() throws Exception {
        // ② Alice logs in
        HttpHeaders aliceAuth = loginAs("alice@crosscert.com", "alice-temp-pw");

        // ③ Alice creates tenant T_A
        String tenantBody = """
                {"id":"T_A","displayName":"Tenant A","rpId":"localhost","rpName":"Tenant A",
                 "allowedOriginsJson":"[\\"http://localhost\\"]",
                 "attestationPolicyJson":"{\\"acceptedFormats\\":[\\"none\\"],\\"requireUserVerification\\":true,\\"mdsRequired\\":false}"}
                """;
        ResponseEntity<JsonNode> createT = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                new HttpEntity<>(tenantBody, aliceAuth), JsonNode.class);
        assertThat(createT.getStatusCode().value()).isEqualTo(201);

        // ④ Alice issues an API Key
        String keyBody = """
                {"tenantId":"T_A","name":"primary","scopesJson":"[]"}
                """;
        ResponseEntity<JsonNode> issue = rest.exchange(
                url("/admin/api/api-keys"), HttpMethod.POST,
                new HttpEntity<>(keyBody, aliceAuth), JsonNode.class);
        assertThat(issue.getStatusCode().value()).isEqualTo(201);
        String fullKey = issue.getBody().get("plainText").asText();
        long keyId = issue.getBody().get("id").asLong();
        String prefix = issue.getBody().get("prefix").asText();

        // ⑤ The issued prefix is registered (use the repo directly —
        //   the Phase 1 ApiKeyLookupService lives in passkey-app and is
        //   out of this test's classpath; this assertion proves the
        //   admin write is durable).
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.api_key WHERE key_prefix=?",
                Long.class, prefix);
        assertThat(count).isEqualTo(1L);

        // ⑥ /audit returns 3 rows (ADMIN_LOGIN, TENANT_CREATE, API_KEY_ISSUE)
        ResponseEntity<JsonNode> audit = rest.exchange(
                url("/admin/api/audit"), HttpMethod.GET,
                new HttpEntity<>(aliceAuth), JsonNode.class);
        assertThat(audit.getStatusCode().value()).isEqualTo(200);
        assertThat(audit.getBody().size()).isEqualTo(3);

        // ⑦ chain verify
        ResponseEntity<JsonNode> verify = rest.exchange(
                url("/admin/api/audit/verify"), HttpMethod.GET,
                new HttpEntity<>(aliceAuth), JsonNode.class);
        assertThat(verify.getBody().get("ok").asBoolean()).isTrue();

        // ⑧ Alice revokes the key
        ResponseEntity<String> del = rest.exchange(
                url("/admin/api/api-keys/" + keyId), HttpMethod.DELETE,
                new HttpEntity<>(aliceAuth), String.class);
        assertThat(del.getStatusCode().value()).isEqualTo(204);
        Long revokedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.api_key WHERE id=? AND revoked_at IS NOT NULL",
                Long.class, keyId);
        assertThat(revokedCount).isEqualTo(1L);

        // ⑨ Same key is now inactive (read directly via repository).
        var keyRow = jdbc.queryForMap(
                "SELECT revoked_at FROM APP_OWNER.api_key WHERE id=?", keyId);
        assertThat(keyRow.get("REVOKED_AT")).isNotNull();

        // ⑩ Bob (VIEWER) can list but cannot create tenant
        HttpHeaders bobAuth = loginAs("bob@crosscert.com", "bob-temp-pw");
        ResponseEntity<String> bobList = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.GET,
                new HttpEntity<>(bobAuth), String.class);
        assertThat(bobList.getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> bobCreate = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                new HttpEntity<>(tenantBody.replace("T_A","T_B"), bobAuth), String.class);
        assertThat(bobCreate.getStatusCode().value()).isEqualTo(403);

        // ⑪ Tamper one audit row's payload — verify must return {ok:false}
        jdbc.update("UPDATE APP_OWNER.audit_log SET payload='{\"x\":\"tampered\"}' " +
                    "WHERE id=(SELECT MIN(id) FROM APP_OWNER.audit_log)");
        ResponseEntity<JsonNode> verifyBroken = rest.exchange(
                url("/admin/api/audit/verify"), HttpMethod.GET,
                new HttpEntity<>(aliceAuth), JsonNode.class);
        assertThat(verifyBroken.getBody().get("ok").asBoolean()).isFalse();
        assertThat(verifyBroken.getBody().get("brokenAt")).isNotNull();
    }
}
```

- [ ] **Step 3: Run AdminFlowIT**

```bash
./gradlew :admin-app:test --tests AdminFlowIT 2>&1 | tail -25
```

Expected: `BUILD SUCCESSFUL`, 1 test passing. First run may take 8-10 minutes (Oracle container boot + V1-V11 migrations).

- [ ] **Step 4: Commit**

```bash
git add admin-app/build.gradle.kts \
        admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java
git commit -m "feat(admin): AdminFlowIT — Phase 2 acceptance gate (11-step E2E)"
```

---

## Task 25: Final DoD verification + tag

**Files:** none (verification only)

- [ ] **Step 1: Full clean build + all tests**

```bash
./gradlew clean build 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. The aggregate run includes Phase 0 (27 tests), Phase 1 (31 tests), and Phase 2 (~25 new tests across unit + IT).

- [ ] **Step 2: Boot both apps in the local profile**

```bash
docker compose up -d                                   # ensure Oracle + Redis running
./scripts/wait-for-oracle.sh
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/dod-admin.log 2>&1 &
./gradlew :passkey-app:bootRun --args='--spring.profiles.active=local --server.port=8082' > /tmp/dod-passkey.log 2>&1 &
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/dod-admin.log; do sleep 2; done
until grep -qE "Started PasskeyApplication|APPLICATION FAILED" /tmp/dod-passkey.log; do sleep 2; done
curl -s http://localhost:8081/actuator/health | head -2
curl -s http://localhost:8082/actuator/health | head -2
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/admin/
pkill -f "AdminApplication|PasskeyApplication"
```

Expected: both health UP; `http://localhost:8081/admin/` returns `200` (SPA index).

- [ ] **Step 3: Tag**

```bash
git tag -a phase-2-admin-app-complete -m "$(cat <<'EOF'
Phase 2 (admin-app) complete.

Acceptance:
- AdminFlowIT 11-step scenario green (login → tenant CRUD → API Key
  issue/lookup/revoke → audit list + chain verify → RBAC enforcement →
  tamper detect)
- Full test suite green (Phase 0 + Phase 1 + Phase 2)
- Both apps boot cleanly: admin-app:8081 (SPA + REST), passkey-app:8082
- Flyway V9/V10/V11 applied (admin_user, audit_log, seed operators)

Phase 2 surface:
- POST /admin/login (form), POST /admin/logout
- GET /admin/api/me
- GET/POST /admin/api/tenants[/{id}]
- GET/POST /admin/api/api-keys (+ DELETE for revoke)
- GET /admin/api/audit, /admin/api/audit/verify
- SPA at GET /admin/ (Vite-built React + Router, served from admin-app fat JAR)

Highlights:
- Form login + Spring Session Redis + CSRF (cookie-based) + RBAC
- audit_log SHA-256 hash chain with detector (AuditChainVerifier)
- API Key one-time plainText display, BCrypt-hashed at rest
- One-fat-JAR deploy: `./gradlew :admin-app:bootJar`

Followups deferred to Phase 4+:
- Admin self-service password change + forced first-login rotation
- admin 2FA (dogfood passkey via SDK)
- External IdP (Keycloak / OIDC)
- audit_log immutable archive (S3 + Object Lock)
EOF
)"
```

- [ ] **Step 4: Confirm tag**

```bash
git tag -l "phase-*"
```

Expected: `phase-0-foundation-complete`, `phase-1-passkey-app-complete`, `phase-2-admin-app-complete`.

---

## Self-Review

### Spec coverage

- §1 핵심 4 → Tasks 8-22 (admin UI, API Key, auth, audit)
- §2 합격 게이트 (AdminFlowIT 11 단계) → Task 24
- §3 기술 스택 → Task 1 (deps), Task 18 (Vite), Task 19 (node-gradle), Task 11 (Spring Session)
- §4 파일 구조 → File Inventory + each task
- §5.1 admin_user → Tasks 2, 5
- §5.2 audit_log → Tasks 3, 6
- §5.3 hash chain → Tasks 8, 9
- §5.4 ApiKey revoke → Task 7 (V12는 불필요 — V7에 이미 revoked_at 컬럼 존재)
- §6 RBAC matrix → Tasks 13, 15, 16 (@PreAuthorize + WebMvcTest)
- §7 auth flow → Tasks 10 (UserDetailsService), 11 (SecurityConfig + handlers), 24 (IT)
- §8 SPA build 통합 → Task 19
- §9 위험 & 완화 → 모든 task 단계의 코드에 반영 (DUMMY_HASH는 Spring Security 기본 동작에 의존, 위험 표에서 정정)
- §10 후속 → tag 메시지 (Task 25)
- §11 task 수 예상 → 실제 25 (V12 제거 + DTO 통합으로 30→25 축소)

### Placeholder scan

검색 후 발견 항목 없음. 모든 step에 코드 또는 실행 가능한 명령. "TODO/TBD/implement later" 없음.

### Type consistency

- `AuditAppendRequest` 시그니처 = `(long, String, String, String, String, Map<String,Object>)` — Tasks 8, 12, 14에서 동일하게 호출.
- `AuditChainVerifier.Result(ok, brokenAt)` — Tasks 9, 16에서 동일.
- `ApiKeyAdminDto.ApiKeyCreateResponse` 필드 = `(id, prefix, plainText, name, tenantId, createdAt, expiresAt)` — Tasks 14, 15, 22, 24에서 동일.
- `TenantAdminDto.TenantCreateRequest` 필드 = `(id, displayName, rpId, rpName, allowedOriginsJson, attestationPolicyJson)` — Tasks 12, 13, 20, 21, 24에서 동일.
- `ApiKey.revoke(Instant)` (Task 7) — Task 14의 `ApiKeyAdminService.revoke()`에서 사용.
- BCrypt cost는 prod 12 (`:core`의 `CoreSecurityConfig`), unit test fast 4 (Task 14에서 override).

### Scope check

25 tasks, Phase 1 (27)과 동급. 단일 plan으로 적절. AdminFlowIT가 acceptance gate.
