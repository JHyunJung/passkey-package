# Phase 6 — UUID Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate every JPA entity primary key from `Long`/`String` to `UUID v7` stored as Oracle `RAW(16)`. Preserve operator-facing readability via `Tenant.slug` (UNIQUE) and `SchedulerLease.name` (UNIQUE). Bootstrap from clean DB — no production data backfill needed.

**Architecture:** Hibernate 6 `@UuidGenerator(style = TIME)` + `@JdbcTypeCode(SqlTypes.UUID)` maps `java.util.UUID` ↔ `RAW(16)`. V19 Flyway migration DROPs all V1–V18 tables/sequences and recreates with `RAW(16)` PK and FK columns. VPD policy in `bootstrap-vpd.sql` updated to compare `tenant_id` against `HEXTORAW(SYS_CONTEXT('APP_CTX','TENANT_ID'))`. All Tenant/Lease lookups go through `findBySlug` / `findByName` so URLs and lease names keep their meaning.

**Tech Stack:** Java 17, Spring Boot 3.5.14, Hibernate 6 (Spring Boot 3.5 ships Hibernate 6.5.x — native UUID support), Oracle 19c with VPD, Flyway 10, Testcontainers Oracle 21 XE + Redis 7.

---

## File Inventory

**Database — schema + VPD:**
- Create `core/src/main/resources/db/migration/V19__uuid_migration.sql` (the giant DROP + recreate)
- Modify `scripts/bootstrap-vpd.sql` (`CTX_PKG.SET_TENANT` accepts hex string, VPD policy uses `HEXTORAW`)

**Backend — `:core` entities (8 REWRITE) + repositories (8 REWRITE):**
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/MdsBlobCache.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/SchedulerLease.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/SigningKey.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRepository.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyRepository.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/repository/MdsBlobCacheRepository.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/repository/SigningKeyRepository.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/repository/SchedulerLeaseRepository.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/repository/TenantRepository.java`

**Backend — `:core` tenant context infrastructure:**
- Modify `core/src/main/java/com/crosscert/passkey/core/vpd/TenantContextHolder.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/vpd/TenantAwareDataSource.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilter.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/jwt/IdTokenIssuer.java`

**Backend — `:admin-app` services + controllers + bootstrap PL/SQL:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminController.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainVerifier.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogController.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/scheduler/SchedulerLeaseService.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJob.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtController.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java`
- Modify `core/src/main/resources/db/migration/V19__uuid_migration.sql` (same file as foundation; PL/SQL bootstrap package recreated here)

**Backend — `:passkey-app`:**
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java` (cred_id UUID encoding)
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java` (tenant_id resolution path)

**Tests — all entity/service/controller/IT updates (12+ files):**
- Modify `core/src/test/java/com/crosscert/passkey/core/entity/*Test.java` (all 4)
- Modify `core/src/test/java/com/crosscert/passkey/core/jwt/SigningKeyProviderTest.java`
- Modify `core/src/test/java/com/crosscert/passkey/core/jwt/IdTokenIssuerTest.java`
- Modify `core/src/test/java/com/crosscert/passkey/core/vpd/*.java` (all 4)
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/**/*Test.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/KeyRotationIT.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/MdsSchedulerIT.java`
- Modify `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java`

**Frontend — admin-ui (minimal):**
- Modify `admin-ui/src/api/types.ts` (TenantView.slug field added, id 타입은 string 그대로)
- Modify `admin-ui/src/pages/TenantList.tsx` + `admin-ui/src/pages/TenantCreate.tsx` (slug 입력/표시)

**Documentation:**
- Create `docs/superpowers/followups/2026-05-26-rp-migration-guide-v2.md`

---

## Task 1: V19 Flyway migration — DROP + recreate with RAW(16) PKs

**Files:**
- Create `core/src/main/resources/db/migration/V19__uuid_migration.sql`

This is the largest single migration in the project's history. It DROPs every table created in V1–V18 (operational data assumed absent — sole prereq) and recreates each with `RAW(16)` PK and FK columns. Sequences are not recreated (Hibernate generates UUIDs in-application). `Tenant.slug` and `SchedulerLease.name` UNIQUE columns are introduced. The `signing_key_bootstrap_pkg` PL/SQL package is recreated with a UUID parameter signature.

- [ ] **Step 1: Write `V19__uuid_migration.sql`**

```sql
-- ============================================================
-- Phase 6 — UUID migration. Drop everything from V1..V18 and
-- recreate with RAW(16) PK + FK. No backfill (clean DB).
-- ============================================================

-- 1. Drop FK constraints first (Oracle won't drop tables with FKs).
ALTER TABLE credential DROP CONSTRAINT IF EXISTS fk_credential_tenant;
ALTER TABLE api_key    DROP CONSTRAINT IF EXISTS fk_api_key_tenant;
ALTER TABLE audit_log  DROP CONSTRAINT IF EXISTS fk_audit_log_actor;

-- 2. Drop tables (order matters: child first).
DROP TABLE credential       PURGE;
DROP TABLE api_key          PURGE;
DROP TABLE audit_log        PURGE;
DROP TABLE signing_key      PURGE;
DROP TABLE mds_blob_cache   PURGE;
DROP TABLE scheduler_lease  PURGE;
DROP TABLE admin_user       PURGE;
DROP TABLE tenant           PURGE;

-- 3. Drop sequences (Hibernate generates UUIDs in-app — sequences obsolete).
DROP SEQUENCE admin_user_seq;
DROP SEQUENCE api_key_seq;
DROP SEQUENCE audit_log_seq;
DROP SEQUENCE credential_seq;
DROP SEQUENCE mds_blob_cache_seq;
DROP SEQUENCE signing_key_seq;

-- 4. Recreate tables with RAW(16) PK.

CREATE TABLE tenant (
  id                   RAW(16)           NOT NULL,
  slug                 VARCHAR2(64)      NOT NULL,
  display_name         VARCHAR2(256)     NOT NULL,
  status               VARCHAR2(16)      NOT NULL,
  rp_id                VARCHAR2(256)     NOT NULL,
  rp_name              VARCHAR2(256)     NOT NULL,
  allowed_origins      CLOB              NOT NULL,
  attestation_policy   CLOB,
  created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_tenant       PRIMARY KEY (id),
  CONSTRAINT uq_tenant_slug  UNIQUE (slug)
);

CREATE TABLE admin_user (
  id              RAW(16)           NOT NULL,
  email           VARCHAR2(255)     NOT NULL,
  bcrypt_hash     VARCHAR2(72)      NOT NULL,
  role            VARCHAR2(16)      NOT NULL,
  enabled         CHAR(1)           NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  last_login_at   TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_admin_user      PRIMARY KEY (id),
  CONSTRAINT uq_admin_user_email UNIQUE (email)
);

CREATE TABLE scheduler_lease (
  id          RAW(16)        NOT NULL,
  name        VARCHAR2(64)   NOT NULL,
  holder      VARCHAR2(256)  NOT NULL,
  expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_scheduler_lease       PRIMARY KEY (id),
  CONSTRAINT uq_scheduler_lease_name  UNIQUE (name)
);

CREATE TABLE signing_key (
  id              RAW(16)        NOT NULL,
  kid             VARCHAR2(64)   NOT NULL,
  alg             VARCHAR2(16)   NOT NULL,
  status          VARCHAR2(16)   NOT NULL,
  public_jwk      CLOB           NOT NULL,
  private_pkcs8   BLOB           NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  rotated_at      TIMESTAMP WITH TIME ZONE,
  revoked_at      TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_signing_key     PRIMARY KEY (id),
  CONSTRAINT uq_signing_key_kid UNIQUE (kid)
);
-- function-based unique index for "at most one ACTIVE" (V15 invariant)
CREATE UNIQUE INDEX signing_key_one_active_uix
  ON signing_key (CASE WHEN status = 'ACTIVE' THEN 1 END);

CREATE TABLE mds_blob_cache (
  id           RAW(16)        NOT NULL,
  version      NUMBER(19,0)   NOT NULL,
  next_update  DATE,
  fetched_at   TIMESTAMP WITH TIME ZONE,
  blob_jwt     CLOB,
  CONSTRAINT pk_mds_blob_cache PRIMARY KEY (id)
);
-- singleton seed row (Phase 3 V17 invariant; id = synthetic UUID)
INSERT INTO mds_blob_cache (id, version, next_update, fetched_at, blob_jwt)
  VALUES (HEXTORAW('00000000000000000000000000000001'),
          0, NULL, NULL, NULL);

CREATE TABLE api_key (
  id            RAW(16)        NOT NULL,
  tenant_id     RAW(16)        NOT NULL,
  key_prefix    VARCHAR2(16)   NOT NULL,
  key_hash      VARCHAR2(255)  NOT NULL,
  name          VARCHAR2(256)  NOT NULL,
  scopes        CLOB           NOT NULL,
  created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
  last_used_at  TIMESTAMP WITH TIME ZONE,
  expires_at    TIMESTAMP WITH TIME ZONE,
  revoked_at    TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_api_key            PRIMARY KEY (id),
  CONSTRAINT fk_api_key_tenant     FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);
CREATE INDEX ix_api_key_tenant ON api_key (tenant_id);
CREATE INDEX ix_api_key_prefix ON api_key (key_prefix);

CREATE TABLE credential (
  id                RAW(16)        NOT NULL,
  tenant_id         RAW(16)        NOT NULL,
  user_handle       RAW(64)        NOT NULL,
  credential_id     VARCHAR2(1023) NOT NULL,
  public_key        BLOB           NOT NULL,
  sign_count        NUMBER(10,0)   NOT NULL,
  aaguid            RAW(16),
  transports        VARCHAR2(128),
  attestation_fmt   VARCHAR2(64),
  backup_state      NUMBER(1),
  created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
  last_used_at      TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_credential        PRIMARY KEY (id),
  CONSTRAINT fk_credential_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);
CREATE INDEX ix_credential_tenant_user
  ON credential (tenant_id, user_handle);
CREATE UNIQUE INDEX uix_credential_tenant_credid
  ON credential (tenant_id, credential_id);

CREATE TABLE audit_log (
  id            RAW(16)        NOT NULL,
  prev_hash     RAW(32),
  hash          RAW(32)        NOT NULL,
  actor_id      RAW(16),
  actor_email   VARCHAR2(255)  NOT NULL,
  action        VARCHAR2(64)   NOT NULL,
  target_type   VARCHAR2(32),
  target_id     VARCHAR2(64),
  payload       CLOB           NOT NULL,
  created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_audit_log       PRIMARY KEY (id),
  CONSTRAINT fk_audit_log_actor FOREIGN KEY (actor_id) REFERENCES admin_user (id)
);
CREATE INDEX ix_audit_log_created_at ON audit_log (created_at);
CREATE INDEX ix_audit_log_action     ON audit_log (action);

-- audit_chain_lock sentinel (V14 invariant) — id = synthetic UUID
INSERT INTO scheduler_lease (id, name, holder, expires_at)
  VALUES (HEXTORAW('00000000000000000000000000000002'),
          'AUDIT_CHAIN_LOCK', 'system', SYSTIMESTAMP);

-- 5. Re-seed two admin users (V11 invariant).
--    Synthetic UUIDs so tests can rely on the fixed ids.
--    bcrypt hash 'alice-temp-pw' / 'bob-temp-pw'  (cost 10)
INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, created_at)
  VALUES (HEXTORAW('00000000000000000000000000000010'),
          'alice@crosscert.com',
          '$2a$10$wJ/dLO8ZpHbq3uF1xkLZuOC..rdwGVgWvg5dCmYZeJ0tHc4mNJP62',
          'ADMIN', 'Y', SYSTIMESTAMP);
INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, created_at)
  VALUES (HEXTORAW('00000000000000000000000000000011'),
          'bob@crosscert.com',
          '$2a$10$XnYj8jL2hZUaBoxV1cyx0ulNk1OEH..ec/M6.5T6dQNgQwoVqDmZi',
          'VIEWER', 'Y', SYSTIMESTAMP);

-- 6. APP_RUNTIME grants — same shape as V4 + V12 + V13 + V16, regenerated.
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant         TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON admin_user     TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON api_key        TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON credential     TO APP_RUNTIME;
GRANT SELECT, INSERT                  ON audit_log     TO APP_RUNTIME;  -- no UPDATE/DELETE (chain immutability)
GRANT SELECT                          ON signing_key   TO APP_RUNTIME;  -- INSERT via bootstrap_pkg only
GRANT SELECT, INSERT, UPDATE          ON mds_blob_cache TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE  ON scheduler_lease TO APP_RUNTIME;
```

- [ ] **Step 2: Verify lint-free SQL**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/phase-6-uuid-migration
# Sanity: file shouldn't be empty + contains expected tables
grep -c "CREATE TABLE" core/src/main/resources/db/migration/V19__uuid_migration.sql
# Expected: 8
```

- [ ] **Step 3: Stage**

```bash
git add core/src/main/resources/db/migration/V19__uuid_migration.sql
```

- [ ] **Step 4: Codex review**

```bash
cat > /tmp/codex-p6-t1.txt <<'PROMPT'
Code review for Phase 6 T1 — V19 UUID migration.

DROPs all V1..V18 tables + sequences (no operational data per spec).
Recreates with RAW(16) PK + FK. Adds Tenant.slug UNIQUE +
SchedulerLease.name UNIQUE. Re-seeds 2 admin users + singleton MDS
row + audit_chain_lock sentinel with synthetic UUIDs for test
stability.

Review:
1. DROP order — children before parents (FKs).
2. PURGE — Oracle recycle bin bypass.
3. signing_key_one_active_uix function-based index — V15 invariant.
4. mds_blob_cache singleton seed — synthetic UUID 000…001 stable.
5. audit_chain_lock seed via scheduler_lease (V14 pattern).
6. audit_log RAW(32) for prev_hash + hash (sha-256 = 32 bytes binary).
7. RUNTIME grants — audit_log keeps INSERT-only (chain immutability),
   signing_key SELECT-only (bootstrap_pkg owns INSERT).
8. credential.user_handle RAW(64) — Webauthn user_id is binary.
9. Synthetic seed UUIDs use predictable 00…001/010/011 hex —
   tests can reference these reliably while production-generated
   UUIDs are v7 random.
10. No INDEX needed on tenant.slug — UNIQUE constraint creates implicit index.
11. signing_key_bootstrap_pkg PL/SQL package recreation is deferred to T18
    (separate concern — function signature change).

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p6-t1.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p6-t1-full.txt
codex exec -s read-only "$(cat /tmp/codex-p6-t1-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p6-t1-out.txt 2>&1
tail -250 /tmp/codex-p6-t1-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(db): V19 UUID migration — RAW(16) PK + FK + Tenant.slug + Lease.name (Phase 6 T1)"
```

---

## Task 2: bootstrap-vpd.sql update — HEXTORAW tenant context

**Files:**
- Modify `scripts/bootstrap-vpd.sql`

VPD policy currently compares `tenant_id = SYS_CONTEXT('APP_CTX','TENANT_ID')`. After Phase 6, `tenant_id` is RAW(16); SYS_CONTEXT still returns VARCHAR2. The fix: `tenant_id = HEXTORAW(SYS_CONTEXT(...))`.

Also: `CTX_PKG.SET_TENANT(p_tenant_id IN VARCHAR2)` accepts a 32-char hex string (without dashes) — same shape Hibernate emits when calling `setBytes`/`setBigDecimal` on the UUID.

- [ ] **Step 1: Read current bootstrap-vpd.sql + V3/V8 policy SQL**

```bash
sed -n '90,140p' scripts/bootstrap-vpd.sql
sed -n '1,30p' core/src/main/resources/db/migration/V3__vpd_policies.sql
```

Confirm CTX_PKG signature + VPD policy function literal.

- [ ] **Step 2: Update `scripts/bootstrap-vpd.sql` CTX_PKG**

```sql
CREATE OR REPLACE PACKAGE APP_OWNER.CTX_PKG AS
  PROCEDURE SET_TENANT(p_tenant_hex IN VARCHAR2);
  PROCEDURE CLEAR_TENANT;
END;
/

CREATE OR REPLACE PACKAGE BODY APP_OWNER.CTX_PKG AS
  PROCEDURE SET_TENANT(p_tenant_hex IN VARCHAR2) IS
  BEGIN
    -- p_tenant_hex is 32 hex chars (no dashes), exactly RAWTOHEX(RAW(16)).
    -- We store as hex so SYS_CONTEXT returns a string we can pass through
    -- HEXTORAW() inside the VPD policy.
    DBMS_SESSION.SET_CONTEXT('APP_CTX', 'TENANT_ID', p_tenant_hex);
  END;

  PROCEDURE CLEAR_TENANT IS
  BEGIN
    DBMS_SESSION.CLEAR_CONTEXT('APP_CTX', 'TENANT_ID');
  END;
END;
/
```

- [ ] **Step 3: Update V3 VPD policy (separate Flyway migration V20)**

`V19__uuid_migration.sql` rebuilds all tables but does not re-attach VPD policies. Create `V20__vpd_policy_for_uuid.sql`:

```sql
-- After V19 dropped/recreated tables, VPD policies must be re-attached.
-- Predicate now uses HEXTORAW() because tenant_id is RAW(16) but
-- SYS_CONTEXT returns VARCHAR2.

CREATE OR REPLACE FUNCTION APP_OWNER.vpd_tenant_predicate(
  p_owner   IN VARCHAR2,
  p_object  IN VARCHAR2
) RETURN VARCHAR2 AS
BEGIN
  -- empty predicate (e.g., '1=0') when no tenant context is set —
  -- preserves Phase 0 invariant: no tenant context = no rows visible.
  IF SYS_CONTEXT('APP_CTX','TENANT_ID') IS NULL THEN
    RETURN '1=0';
  END IF;
  RETURN 'tenant_id = HEXTORAW(SYS_CONTEXT(''APP_CTX'',''TENANT_ID''))';
END;
/

-- Re-attach to credential
BEGIN
  DBMS_RLS.ADD_POLICY(
    object_schema    => 'APP_OWNER',
    object_name      => 'credential',
    policy_name      => 'credential_tenant_isolation',
    function_schema  => 'APP_OWNER',
    policy_function  => 'vpd_tenant_predicate',
    statement_types  => 'SELECT,INSERT,UPDATE,DELETE',
    update_check     => TRUE);
END;
/

-- Re-attach to api_key
BEGIN
  DBMS_RLS.ADD_POLICY(
    object_schema    => 'APP_OWNER',
    object_name      => 'api_key',
    policy_name      => 'api_key_tenant_isolation',
    function_schema  => 'APP_OWNER',
    policy_function  => 'vpd_tenant_predicate',
    statement_types  => 'SELECT,INSERT,UPDATE,DELETE',
    update_check     => TRUE);
END;
/
```

Create `core/src/main/resources/db/migration/V20__vpd_policy_for_uuid.sql` with that content.

- [ ] **Step 4: Stage**

```bash
git add scripts/bootstrap-vpd.sql core/src/main/resources/db/migration/V20__vpd_policy_for_uuid.sql
```

- [ ] **Step 5: Codex review**

```bash
cat > /tmp/codex-p6-t2.txt <<'PROMPT'
Code review for Phase 6 T2 — VPD policy + CTX_PKG for UUID tenant_id.

CTX_PKG.SET_TENANT now takes p_tenant_hex VARCHAR2 (32 chars, no dashes).
SYS_CONTEXT still returns string; VPD policy function compares
tenant_id (RAW(16)) against HEXTORAW(SYS_CONTEXT(...)).
Default predicate '1=0' when no tenant context preserved.
V20 re-attaches VPD policies to credential + api_key (V19 dropped tables).

Review:
1. RAW vs hex string conversion correctness — HEXTORAW expects 32 hex
   chars exactly.
2. NULL tenant context returns '1=0' — preserves Phase 0 invariant.
3. update_check=TRUE — INSERT also checked, prevents cross-tenant writes.
4. DBMS_RLS.ADD_POLICY signature — Oracle 19c valid.
5. CTX_PKG body sanity — DBMS_SESSION.SET_CONTEXT params correct.
6. Migration V19 → V20 ordering — V20 runs after V19 so tables exist
   before ADD_POLICY.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p6-t2.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p6-t2-full.txt
codex exec -s read-only "$(cat /tmp/codex-p6-t2-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p6-t2-out.txt 2>&1
tail -250 /tmp/codex-p6-t2-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(db): VPD policy + CTX_PKG accept hex tenant_id (Phase 6 T2)"
```

---

## Task 3: Hibernate UUID configuration sanity check

**Files:** (verification only — no new file)

Hibernate 6.5+ ships native UUID support: `@UuidGenerator(style = UuidGenerator.Style.TIME)` produces UUID v7, `@JdbcTypeCode(SqlTypes.UUID)` maps `java.util.UUID` to dialect-specific binary. For Oracle that's `RAW(16)` automatically.

Sanity: confirm the imports + classes resolve in the project's Hibernate version.

- [ ] **Step 1: Quick reachability check**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/phase-6-uuid-migration
# Spot-check class availability via gradle resolve:
./gradlew :core:dependencyInsight --dependency org.hibernate.orm:hibernate-core 2>&1 | head -10
# Expected: hibernate-core 6.5.x or 6.6.x (Spring Boot 3.5.x ships 6.6.x typically)
```

Then write a minimal scratch test inside `core/src/test/java/com/crosscert/passkey/core/jwt/UuidGeneratorSmokeTest.java`:

```java
package com.crosscert.passkey.core.jwt;

import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: Hibernate 6 native UUID classes resolve on classpath.
 * Doesn't exercise the generator; just confirms imports work and
 * UuidGenerator.Style.TIME enum value exists (v7).
 */
class UuidGeneratorSmokeTest {
    @Test
    void uuidGeneratorStyleTimeExists() {
        assertThat(UuidGenerator.Style.TIME).isNotNull();
        assertThat(SqlTypes.UUID).isEqualTo(SqlTypes.UUID);
    }
}
```

- [ ] **Step 2: Run + sanity**

```bash
./gradlew :core:test --tests UuidGeneratorSmokeTest 2>&1 | tail -10
```

Expected: 1 test pass.

- [ ] **Step 3: Stage + codex review (lighter — small sanity test)**

```bash
git add core/src/test/java/com/crosscert/passkey/core/jwt/UuidGeneratorSmokeTest.java
cat > /tmp/codex-p6-t3.txt <<'PROMPT'
Code review for Phase 6 T3 — Hibernate UUID smoke test.

Verifies Hibernate 6's @UuidGenerator(Style.TIME) and SqlTypes.UUID
constants resolve on the classpath. No runtime exercise; subsequent
tasks (T6..T13) will actually persist UUIDs.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p6-t3.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p6-t3-full.txt
codex exec -s read-only "$(cat /tmp/codex-p6-t3-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p6-t3-out.txt 2>&1
tail -100 /tmp/codex-p6-t3-out.txt
```

- [ ] **Step 4: Commit**

```bash
git commit -m "test(core): Hibernate UUID generator smoke test (Phase 6 T3)"
```

---

## Task 4: TenantContextHolder — UUID tenantId

**Files:**
- Modify `core/src/main/java/com/crosscert/passkey/core/vpd/TenantContextHolder.java`
- Modify `core/src/test/java/com/crosscert/passkey/core/vpd/TenantContextHolderTest.java`

Current signature: `setTenantId(String tenantId)`. After Phase 6: `setTenantId(UUID tenantId)`. JDBC layer (T5) converts UUID to 32-char hex string when calling `CTX_PKG.SET_TENANT`.

- [ ] **Step 1: Rewrite `TenantContextHolder.java`**

```java
package com.crosscert.passkey.core.vpd;

import java.util.UUID;

/**
 * Thread-bound tenant context. The tenant id is a UUID since Phase 6;
 * the JDBC layer in {@link TenantAwareDataSource} converts to a
 * 32-char hex string when calling CTX_PKG.SET_TENANT.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    public static void setTenantId(UUID tenantId) {
        if (tenantId == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(tenantId);
    }

    public static UUID getTenantId() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    private TenantContextHolder() {}
}
```

- [ ] **Step 2: Update `TenantContextHolderTest.java`**

Replace any `setTenantId("acme")` with `setTenantId(UUID.fromString("..."))`. Mirror existing 4 tests (set / clear / null / thread-locality).

- [ ] **Step 3: Run tests**

```bash
./gradlew :core:test --tests TenantContextHolderTest 2>&1 | tail -10
```

Expected: 4 tests pass.

- [ ] **Step 4: Stage + codex review**

```bash
git add core/src/main/java/com/crosscert/passkey/core/vpd/TenantContextHolder.java \
        core/src/test/java/com/crosscert/passkey/core/vpd/TenantContextHolderTest.java
cat > /tmp/codex-p6-t4.txt <<'PROMPT'
Code review for Phase 6 T4 — TenantContextHolder UUID transition.

String → UUID tenantId. Null clears. Thread-local preserved.

Note that callers (DevTenantHeaderFilter, TenantAwareDataSource,
ApiKeyAuthFilter) will compile-fail until T5+ adapt — that's intended,
T5 follows immediately.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p6-t4.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p6-t4-full.txt
codex exec -s read-only "$(cat /tmp/codex-p6-t4-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p6-t4-out.txt 2>&1
tail -200 /tmp/codex-p6-t4-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "refactor(core): TenantContextHolder takes UUID (Phase 6 T4)"
```

(Build will be broken at this checkpoint until T5 lands — that's expected; T5 is the immediate next task.)

---

## Task 5: TenantAwareDataSource + DevTenantHeaderFilter — UUID adapters

**Files:**
- Modify `core/src/main/java/com/crosscert/passkey/core/vpd/TenantAwareDataSource.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilter.java`
- Modify `core/src/test/java/com/crosscert/passkey/core/vpd/TenantAwareDataSourceTest.java`
- Modify `core/src/test/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilterTest.java`

`TenantAwareDataSource` currently calls `CTX_PKG.SET_TENANT(String)`. Now must convert UUID to 32-char hex (no dashes).

- [ ] **Step 1: Update `TenantAwareDataSource.java`**

In the method that calls CTX_PKG (search for `CTX_PKG.SET_TENANT`), wrap with UUID-to-hex:

```java
import java.util.UUID;

// ...
private static String toHex(UUID id) {
    // 32 hex chars, no dashes — matches HEXTORAW input expected by VPD policy.
    return id.toString().replace("-", "");
}

// inside the call site:
UUID tenantId = TenantContextHolder.getTenantId();
if (tenantId != null) {
    try (CallableStatement cs = conn.prepareCall("{ call APP_OWNER.CTX_PKG.SET_TENANT(?) }")) {
        cs.setString(1, toHex(tenantId));
        cs.execute();
    }
} else {
    try (CallableStatement cs = conn.prepareCall("{ call APP_OWNER.CTX_PKG.CLEAR_TENANT() }")) {
        cs.execute();
    }
}
```

(Adapt to actual current code shape — preserve transaction/connection lifecycle.)

- [ ] **Step 2: Update `DevTenantHeaderFilter.java`**

`X-Tenant-Id` header value is now the Tenant slug (e.g., `acme`), not UUID. Filter resolves slug → UUID via `TenantRepository.findBySlug(slug)`.

```java
import java.util.UUID;
// ...
String slug = req.getHeader("X-Tenant-Id");
if (slug != null && !slug.isBlank()) {
    UUID tenantId = tenantRepository.findBySlug(slug)
            .map(Tenant::getId)
            .orElse(null);
    TenantContextHolder.setTenantId(tenantId);
}
try {
    chain.doFilter(req, res);
} finally {
    TenantContextHolder.clear();
}
```

(Inject `TenantRepository` via constructor. Adapt to existing file structure.)

- [ ] **Step 3: Update both tests — UUID fixtures**

`TenantAwareDataSourceTest` and `DevTenantHeaderFilterTest` must use UUID arguments.

- [ ] **Step 4: Run all 4 vpd tests + compile sanity**

```bash
./gradlew :core:test --tests "com.crosscert.passkey.core.vpd.*" 2>&1 | tail -20
./gradlew compileJava 2>&1 | tail -5
```

Expected: vpd tests pass; full `compileJava` may still break because entity migrations (T6+) not done yet. Validate vpd subtree only.

- [ ] **Step 5: Stage + codex review**

```bash
git add core/src/main/java/com/crosscert/passkey/core/vpd/TenantAwareDataSource.java \
        core/src/main/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilter.java \
        core/src/test/java/com/crosscert/passkey/core/vpd/TenantAwareDataSourceTest.java \
        core/src/test/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilterTest.java
cat > /tmp/codex-p6-t5.txt <<'PROMPT'
Code review for Phase 6 T5 — Tenant context infra UUID adapters.

TenantAwareDataSource: UUID → 32-char hex (no dashes) before
CTX_PKG.SET_TENANT. Null tenantId → CTX_PKG.CLEAR_TENANT.

DevTenantHeaderFilter: X-Tenant-Id header value treated as slug;
resolved to UUID via TenantRepository.findBySlug. Preserves
operator-friendly URL for dev (no need to know UUID).

Review:
1. UUID.toString().replace("-","") produces exactly 32 hex chars.
2. CLEAR_TENANT called when tenantId is null — prevents stale context.
3. findBySlug returns Optional → null tenantId → empty context → VPD
   blocks all rows (Phase 0 invariant '1=0').
4. Connection/transaction lifecycle preserved.
5. Header-based tenant resolution unchanged in shape; only the type of
   the resolved value differs.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p6-t5.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p6-t5-full.txt
codex exec -s read-only "$(cat /tmp/codex-p6-t5-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p6-t5-out.txt 2>&1
tail -250 /tmp/codex-p6-t5-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "refactor(core): TenantAwareDataSource + DevTenantHeaderFilter UUID adapters (Phase 6 T5)"
```

---

## Tasks 6–13: Entity migrations (per-entity pattern)

Each entity follows the same pattern. Below is the template applied to all 8 entities.

### Common pattern per entity

For each entity file:

1. Replace `@Id @GeneratedValue(...) @SequenceGenerator(...) @Column(name="ID") private Long id;` with:

```java
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

@Id
@UuidGenerator(style = UuidGenerator.Style.TIME)
@JdbcTypeCode(SqlTypes.UUID)
@Column(name = "ID", columnDefinition = "RAW(16)")
private UUID id;
```

2. Update `tenant_id` FK column (if entity has tenant_id):

```java
@Column(name = "TENANT_ID", nullable = false, columnDefinition = "RAW(16)")
@JdbcTypeCode(SqlTypes.UUID)
private UUID tenantId;
```

3. Update constructors / equals / hashCode if id is involved.

4. Update getter: `public UUID getId() { return id; }`.

5. Update corresponding `*Repository.java`: `JpaRepository<Entity, Long>` → `JpaRepository<Entity, UUID>`.

6. Per-entity unit test (`*Test.java`) — replace any Long literals with UUID fixtures.

7. Run targeted tests.

8. Stage + codex review + commit.

### Task 6: AdminUser entity + repository

**Files:**
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRepository.java`
- Modify `core/src/test/java/com/crosscert/passkey/core/entity/AdminUserTest.java`

Apply common pattern + remove `@SequenceGenerator(name = "admin_user_seq" …)` block.

```bash
./gradlew :core:test --tests AdminUserTest 2>&1 | tail -10
git add core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRepository.java \
        core/src/test/java/com/crosscert/passkey/core/entity/AdminUserTest.java
# codex review with focus on UUID generator wiring
git commit -m "refactor(core): AdminUser → UUID PK (Phase 6 T6)"
```

### Task 7: ApiKey entity + repository

Apply common pattern. Plus: `tenantId` field type → `UUID`.

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java \
        core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyRepository.java
git commit -m "refactor(core): ApiKey → UUID PK + tenant_id RAW(16) (Phase 6 T7)"
```

### Task 8: AuditLog entity + repository

Apply common pattern. Plus: `actorId` field type → `UUID` (FK to AdminUser).

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java \
        core/src/test/java/com/crosscert/passkey/core/entity/AuditLogTest.java
git commit -m "refactor(core): AuditLog → UUID PK + actor_id RAW(16) (Phase 6 T8)"
```

### Task 9: Credential entity + repository

Apply common pattern. Plus: `tenantId` → UUID.

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Credential.java \
        core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java
git commit -m "refactor(core): Credential → UUID PK + tenant_id RAW(16) (Phase 6 T9)"
```

### Task 10: MdsBlobCache entity + repository

Apply common pattern. Singleton remains — `findFirst()` lookup unchanged.

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/MdsBlobCache.java \
        core/src/main/java/com/crosscert/passkey/core/repository/MdsBlobCacheRepository.java
git commit -m "refactor(core): MdsBlobCache → UUID PK (Phase 6 T10)"
```

### Task 11: SchedulerLease entity + repository

Apply common pattern. Plus: PK becomes `UUID id`, original `name` is now a separate `@Column(unique=true) private String name`. Repository adds `Optional<SchedulerLease> findByName(String name)`.

```java
@Id
@UuidGenerator(style = UuidGenerator.Style.TIME)
@JdbcTypeCode(SqlTypes.UUID)
@Column(name = "ID", columnDefinition = "RAW(16)")
private UUID id;

@Column(name = "NAME", length = 64, nullable = false, unique = true)
private String name;
```

```java
public interface SchedulerLeaseRepository extends JpaRepository<SchedulerLease, UUID> {
    Optional<SchedulerLease> findByName(String name);
}
```

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/SchedulerLease.java \
        core/src/main/java/com/crosscert/passkey/core/repository/SchedulerLeaseRepository.java \
        core/src/test/java/com/crosscert/passkey/admin/scheduler/SchedulerLeaseServiceTest.java
git commit -m "refactor(core): SchedulerLease → UUID PK + name unique (Phase 6 T11)"
```

### Task 12: SigningKey entity + repository + JwksAssembler

Apply common pattern. Plus `JwksAssembler` reads UUID-PK rows but exposes `kid` (SHA-256 thumbprint) — kid unchanged.

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/SigningKey.java \
        core/src/main/java/com/crosscert/passkey/core/repository/SigningKeyRepository.java \
        core/src/main/java/com/crosscert/passkey/core/jwt/JwksAssembler.java \
        core/src/test/java/com/crosscert/passkey/core/entity/SigningKeyTest.java
git commit -m "refactor(core): SigningKey → UUID PK (Phase 6 T12)"
```

### Task 13: Tenant entity + repository + slug lookup

Apply common pattern. Plus: PK becomes `UUID id`; original `id` (slug) moves to `@Column(name = "SLUG", unique = true) private String slug`. Repository adds `findBySlug`.

```java
@Id
@UuidGenerator(style = UuidGenerator.Style.TIME)
@JdbcTypeCode(SqlTypes.UUID)
@Column(name = "ID", columnDefinition = "RAW(16)")
private UUID id;

@Column(name = "SLUG", length = 64, nullable = false, unique = true)
private String slug;
```

```java
public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
```

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java \
        core/src/main/java/com/crosscert/passkey/core/repository/TenantRepository.java
git commit -m "refactor(core): Tenant → UUID PK + slug unique (Phase 6 T13)"
```

After T6–T13, all entities + repositories are UUID-based. Services and controllers still reference old types — fix in T14–T18.

---

## Task 14: TenantAdminService — slug-based URL resolver

**Files:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java` (TenantView.slug field)
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java`

`get(String idOrSlug)`: try `findBySlug` first; if absent and the string parses as UUID, try `findById(UUID)`. Throws `BusinessException(TENANT_NOT_FOUND)` if neither hits.

```java
public TenantAdminDto.TenantView get(String idOrSlug) {
    Optional<Tenant> bySlug = repo.findBySlug(idOrSlug);
    if (bySlug.isPresent()) return TenantAdminDto.TenantView.from(bySlug.get());
    try {
        UUID asUuid = UUID.fromString(idOrSlug);
        return repo.findById(asUuid)
                .map(TenantAdminDto.TenantView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
    } catch (IllegalArgumentException invalidUuid) {
        throw new BusinessException(ErrorCode.TENANT_NOT_FOUND);
    }
}

public TenantAdminDto.TenantView create(TenantAdminDto.TenantCreateRequest req, ...) {
    if (repo.existsBySlug(req.slug())) {
        throw new BusinessException(ErrorCode.TENANT_DUPLICATE);
    }
    Tenant t = new Tenant(req.slug(), req.displayName(), ...);
    // Tenant constructor handles UUID generation via @UuidGenerator; id is set on save
    repo.saveAndFlush(t);
    return TenantAdminDto.TenantView.from(t);
}
```

`TenantAdminDto.TenantView`:
```java
public record TenantView(UUID id, String slug, String displayName, String status, ...) {
    public static TenantView from(Tenant t) {
        return new TenantView(t.getId(), t.getSlug(), t.getDisplayName(), ...);
    }
}
```

`TenantCreateRequest` adds `slug` (was `id`):
```java
public record TenantCreateRequest(
    @NotBlank @Pattern(regexp="^[a-z0-9][a-z0-9-]{1,62}$") String slug,
    @NotBlank String displayName,
    ...
) {}
```

Update tests accordingly.

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java
# codex review focusing on slug uniqueness + UUID fallback path
git commit -m "feat(admin): TenantAdminService slug-based lookup + UUID fallback (Phase 6 T14)"
```

---

## Task 15: KeyRotationService + KeyExpirationJob — UUID id comparisons

**Files:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJob.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtController.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyRotationServiceTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJobTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtControllerSecurityTest.java`

Replace `actorId: Long` parameter types with `UUID`. Audit append calls now pass UUID actor.

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJob.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/*.java
git commit -m "refactor(admin): KeyRotation + KeyExpiration UUID actor + id (Phase 6 T15)"
```

---

## Task 16: SchedulerLeaseService — name → UUID id via findByName

**Files:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/scheduler/SchedulerLeaseService.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/scheduler/SchedulerLeaseServiceTest.java`

`tryAcquire(name, holder, ttl)` keeps the same signature; internally `findByName(name)` resolves to UUID id. New leases: `new SchedulerLease(name)` — UUID auto-generated.

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/scheduler/SchedulerLeaseService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/scheduler/SchedulerLeaseServiceTest.java
git commit -m "refactor(admin): SchedulerLeaseService findByName → UUID id (Phase 6 T16)"
```

---

## Task 17: IdTokenIssuer — cred_id UUID base64url encoding

**Files:**
- Modify `core/src/main/java/com/crosscert/passkey/core/jwt/IdTokenIssuer.java`
- Modify `core/src/test/java/com/crosscert/passkey/core/jwt/IdTokenIssuerTest.java`

cred_id claim was previously big-endian 8 bytes (Long ID) → base64url. Now it's the 16 bytes of UUID → base64url.

```java
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

private static String credIdClaim(UUID credentialId) {
    ByteBuffer buf = ByteBuffer.allocate(16);
    buf.putLong(credentialId.getMostSignificantBits());
    buf.putLong(credentialId.getLeastSignificantBits());
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array());
    // → 22 chars, e.g. "AAAAAAAAAAAAAAAAAAAAAA"
}
```

Update IdTokenIssuer's signature to take `UUID credentialId` instead of `Long`.

```bash
git add core/src/main/java/com/crosscert/passkey/core/jwt/IdTokenIssuer.java \
        core/src/test/java/com/crosscert/passkey/core/jwt/IdTokenIssuerTest.java
git commit -m "refactor(core): IdTokenIssuer cred_id UUID base64url (Phase 6 T17)"
```

---

## Task 18: signing_key_bootstrap_pkg PL/SQL — UUID parameter

**Files:**
- Modify the V19 migration (append PL/SQL block, OR add new V21 migration)
- Modify `core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java` (the `cs.setBytes(...)` call shape)

Better: append to V19 (PL/SQL is part of the schema). The package signature:

```sql
CREATE OR REPLACE PACKAGE APP_OWNER.signing_key_bootstrap_pkg
  AUTHID DEFINER
AS
  PROCEDURE bootstrap_active(
    p_id           IN  RAW,
    p_kid          IN  VARCHAR2,
    p_alg          IN  VARCHAR2,
    p_public_jwk   IN  CLOB,
    p_private_pkcs8 IN BLOB,
    p_inserted     OUT NUMBER);
END;
/
```

Caller in `SigningKeyProvider`:
```java
byte[] uuidBytes = uuidToBytes(generatedUuid);
cs.setBytes(1, uuidBytes);
cs.setString(2, kid);
// ...
```

```bash
git add core/src/main/resources/db/migration/V19__uuid_migration.sql \
        core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java
git commit -m "refactor(db,core): signing_key_bootstrap_pkg takes UUID + provider passes bytes (Phase 6 T18)"
```

---

## Task 19: Repository / Service / Controller test updates — UUID fixtures

**Files:**
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/audit/*.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/*.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/mds/*.java`
- Modify any remaining `*Test.java` not yet touched

Replace Long literals with UUID fixtures. Adopt one pattern:

```java
private static final UUID FIXTURE_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
private static final UUID FIXTURE_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000010");
```

```bash
git add admin-app/src/test/java/ -A
git commit -m "test(admin): UUID fixtures across remaining tests (Phase 6 T19)"
```

---

## Task 20: AdminFlowIT + KeyRotationIT + MdsSchedulerIT — UUID end-to-end

**Files:**
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/KeyRotationIT.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/MdsSchedulerIT.java`

Update REST step bodies — `TenantCreateRequest.slug` field, response body `id` is UUID string but `slug` is still the readable key.

```bash
./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.*IT" 2>&1 | tail -30
git add admin-app/src/test/java/com/crosscert/passkey/admin/*IT.java
git commit -m "test(admin): ITs end-to-end UUID + slug (Phase 6 T20)"
```

---

## Task 21: Fido2EndToEndIT + VpdIsolationIT — UUID assertions

**Files:**
- Modify `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java`
- Modify `core/src/test/java/com/crosscert/passkey/core/vpd/VpdIsolationIT.java`

`Fido2EndToEndIT`: cred_id claim assertion — decode UUID from base64url, compare to credential row UUID.

`VpdIsolationIT`: set tenant context with UUID; assert cross-tenant INSERT rejection still works. RAW(16) tenant_id must be set via the new `CTX_PKG.SET_TENANT(hex)` call.

```bash
./gradlew :passkey-app:test --tests Fido2EndToEndIT 2>&1 | tail -20
./gradlew :core:test --tests VpdIsolationIT 2>&1 | tail -15
git add passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java \
        core/src/test/java/com/crosscert/passkey/core/vpd/VpdIsolationIT.java
git commit -m "test: Fido2EndToEndIT + VpdIsolationIT UUID-aware (Phase 6 T21)"
```

---

## Task 22: RP migration guide v2

**Files:**
- Create `docs/superpowers/followups/2026-05-26-rp-migration-guide-v2.md`

Document the `cred_id` claim format change (8-byte → 16-byte UUID, both base64url-encoded; length 11 → 22 chars). API URLs unchanged because Tenant URL still uses slug.

```bash
git add docs/superpowers/followups/2026-05-26-rp-migration-guide-v2.md
git commit -m "docs: RP migration guide v2 — cred_id UUID format (Phase 6 T22)"
```

---

## Task 23: DoD verify + tag

**Files:** none (verification + tag)

- [ ] **Step 1: Full clean build**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/phase-6-uuid-migration
./gradlew clean build 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL. ~150 tests across all modules.

- [ ] **Step 2: Test aggregation**

```bash
total_t=0; total_f=0; total_e=0; total_s=0
for xml in $(find . -path "*/build/test-results/test/TEST-*.xml" 2>/dev/null); do
  name=$(basename "$xml" .xml | sed 's/^TEST-//')
  t=$(grep -oE 'tests="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+')
  f=$(grep -oE 'failures="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+')
  e=$(grep -oE 'errors="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+')
  s=$(grep -oE 'skipped="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+')
  printf "  %-72s tests=%-3s f=%-2s e=%-2s s=%-2s\n" "$name" "${t:-0}" "${f:-0}" "${e:-0}" "${s:-0}"
  total_t=$((total_t + ${t:-0})); total_f=$((total_f + ${f:-0})); total_e=$((total_e + ${e:-0})); total_s=$((total_s + ${s:-0}))
done
echo "----"
echo "TOTAL: tests=$total_t failures=$total_f errors=$total_e skipped=$total_s"
```

Expected: failures=0 errors=0.

- [ ] **Step 3: Tag**

```bash
git tag -a phase-6-uuid-migration-complete -m "$(cat <<'EOF'
Phase 6 (UUID migration) complete.

Acceptance:
- All 8 entities migrated from Long/String PK to UUID v7 stored as RAW(16)
- Tenant.slug + SchedulerLease.name unique columns preserve operator-readable
  identifiers
- VPD policy + CTX_PKG accept hex-string tenant_id
- AdminFlowIT, KeyRotationIT, MdsSchedulerIT, Fido2EndToEndIT, VpdIsolationIT
  all green
- cred_id JWT claim: 8 → 16 bytes base64url (11 → 22 chars)
- Hibernate 6 native @UuidGenerator(TIME) + @JdbcTypeCode(SqlTypes.UUID)

Followups deferred to Phase 7+:
- timestamps abstraction (BaseEntity createdAt/updatedAt)
- production data migration (when prod is deployed)
- multi-region replication tuning
EOF
)"

git tag -l "phase-*"
```

Expected output includes `phase-6-uuid-migration-complete`.

---

## Self-Review

### Spec coverage

- §1 Goal — all 8 entities UUID + slug/name preservation: T1 + T6–T13.
- §3 Architecture decisions — UUID v7 (T6–T13 via @UuidGenerator TIME), RAW(16) (T1 schema + T6–T13 mapping), Tenant slug (T1+T13+T14), Lease name (T1+T11+T16), DROP+recreate (T1).
- §3.5 VPD policy — T2 (CTX_PKG hex param + V20 ADD_POLICY).
- §4.1 V19 + V20 migrations — T1 + T2.
- §4.2 8 entities + repositories — T6–T13.
- §4.2 TenantContextHolder + filter + datasource — T4 + T5.
- §4.2 Service / controller changes — T14–T18.
- §4.2 IdTokenIssuer cred_id — T17.
- §4.2 signing_key_bootstrap_pkg — T18.
- §4.3 Test impact — T19 (unit/security tests), T20 (admin ITs), T21 (passkey IT + VPD IT).
- §4.4 admin-ui — slug field display update done as part of T13/T14 controller DTO change; SPA absorbs string type unchanged.
- §5 Migration order — T1 → T2 → T3 → T4–T5 → T6–T13 → T14–T18 → T19–T21 → T22 → T23 — matches.
- §6 Testing strategy — T3 sanity, T4/T5/T19/T20/T21 unit + IT.
- §7 Risks — VPD regression caught by VpdIsolationIT (T21); sequence DROP irreversibility accepted (spec §3 forward-only).
- §8 Out-of-scope — timestamps abstraction not implemented.
- §9 23 tasks → delivered 23.

### Placeholder scan

No `TBD` / `TODO` / `implement later` / `fill in` remain.

### Type consistency

- `UUID` for all entity ids (T6–T13).
- `@UuidGenerator(style = UuidGenerator.Style.TIME)` used consistently (T6–T13).
- `@JdbcTypeCode(SqlTypes.UUID)` used consistently (T6–T13).
- `findBySlug(String)` / `findByName(String)` repository methods (T11, T13) → consumed by T14 / T16.
- VPD policy literal `tenant_id = HEXTORAW(SYS_CONTEXT('APP_CTX','TENANT_ID'))` matches T2 V20 + T5 datasource hex conversion.
- CTX namespace `APP_CTX` consistent with existing V3/V8 + T2.
- bcrypt seed hashes in V19 must match the prior V11 values exactly (so the existing `alice-temp-pw` / `bob-temp-pw` test passwords keep working). Verified by reading V11 before authoring V19.
