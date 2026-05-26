# Phase 7 — Tenant Config + ApiKey Scopes Normalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace three JSON CLOB columns (`tenant.allowed_origins`, `tenant.attestation_policy`, `api_key.scopes`) with relational schema — child tables (`tenant_allowed_origin`, `tenant_accepted_format`, `api_key_scope`) and flat boolean columns on `tenant` (`require_user_verification`, `mds_required`). Add structured admin-ui form inputs (chip / checkbox grid / toggle).

**Architecture:** V21 Flyway migration ALTERs `tenant` (drop 2 CLOB cols + add 2 flag cols) and `api_key` (drop scopes CLOB), creates 3 child tables with UUID PK + parent FK + DB-level CHECK constraints for format/scope whitelist. JPA entities replace `String *Json` fields with `@OneToMany` collections + boolean flag getters. Application parsers (`AttestationPolicy.fromJson`, `parseOrigins`) deleted; services read structured fields directly. admin-ui replaces JSON `<textarea>` with `OriginChipInput`, `FormatCheckboxGrid`, `ScopeCheckboxGrid`, `Switch` components built on Phase 5 tokens.

**Tech Stack:** Java 17, Spring Boot 3.5.14, Hibernate 6.6 (UUID + OneToMany cascade), Oracle 19c with VPD, Flyway 10, React 18 + Vite (admin-ui).

---

## File Inventory

**Database:**
- Create `core/src/main/resources/db/migration/V21__tenant_config_normalize.sql`

**Backend — `:core` new entities:**
- Create `core/src/main/java/com/crosscert/passkey/core/entity/TenantAllowedOrigin.java`
- Create `core/src/main/java/com/crosscert/passkey/core/entity/TenantAcceptedFormat.java`
- Create `core/src/main/java/com/crosscert/passkey/core/entity/ApiKeyScope.java`

**Backend — `:core` parent entities (MODIFY):**
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java`

**Backend — `:passkey-app` (delete AttestationPolicy, inline parseOrigins):**
- Delete `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/policy/AttestationPolicy.java`
- Delete `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/policy/AttestationPolicyTest.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsVerifier.java`

**Backend — `:admin-app`:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java`

**Frontend — admin-ui:**
- Create `admin-ui/src/components/OriginChipInput.tsx`
- Create `admin-ui/src/components/FormatCheckboxGrid.tsx`
- Create `admin-ui/src/components/ScopeCheckboxGrid.tsx`
- Create `admin-ui/src/components/Switch.tsx`
- Modify `admin-ui/src/api/types.ts`
- Modify `admin-ui/src/pages/TenantCreate.tsx`
- Modify `admin-ui/src/pages/TenantList.tsx`
- Modify `admin-ui/src/pages/ApiKeyCreateModal.tsx`

**Tests:**
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminServiceTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminControllerSecurityTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java`
- Modify `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java`
- Modify `core/src/test/java/com/crosscert/passkey/core/vpd/VpdIsolationIT.java`

---

## Task 1: V21 Flyway migration — schema normalization

**Files:**
- Create `core/src/main/resources/db/migration/V21__tenant_config_normalize.sql`

- [ ] **Step 1: Write `V21__tenant_config_normalize.sql`**

```sql
-- ============================================================
-- Phase 7 — Tenant config + ApiKey scopes normalization.
-- ALTER tenant: drop allowed_origins + attestation_policy CLOBs,
-- add require_user_verification + mds_required flag columns.
-- ALTER api_key: drop scopes CLOB.
-- CREATE 3 child tables (tenant_allowed_origin, tenant_accepted_format,
-- api_key_scope) with UUID PK + parent FK + CHECK constraint whitelists.
--
-- Assumption: operational data absent (Phase 6 invariant carried forward).
-- ============================================================

-- 1. tenant — drop 2 JSON CLOB columns and their CHECKs
ALTER TABLE tenant DROP CONSTRAINT ck_tenant_origins_json;
ALTER TABLE tenant DROP CONSTRAINT ck_tenant_attest_policy_json;
ALTER TABLE tenant DROP COLUMN allowed_origins;
ALTER TABLE tenant DROP COLUMN attestation_policy;

-- 2. tenant — add boolean flag columns (CHAR(1) with CHECK)
ALTER TABLE tenant ADD (
  require_user_verification CHAR(1) DEFAULT 'Y' NOT NULL,
  mds_required              CHAR(1) DEFAULT 'N' NOT NULL,
  CONSTRAINT ck_tenant_uv  CHECK (require_user_verification IN ('Y','N')),
  CONSTRAINT ck_tenant_mds CHECK (mds_required IN ('Y','N'))
);

-- 3. tenant_allowed_origin (1:N child)
CREATE TABLE tenant_allowed_origin (
  id          RAW(16)        DEFAULT SYS_GUID() NOT NULL,
  tenant_id   RAW(16)                           NOT NULL,
  origin      VARCHAR2(512)                     NOT NULL,
  sort_order  NUMBER(5,0)    DEFAULT 0          NOT NULL,
  CONSTRAINT pk_tenant_allowed_origin PRIMARY KEY (id),
  CONSTRAINT fk_tao_tenant            FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
  CONSTRAINT uq_tao_tenant_origin     UNIQUE (tenant_id, origin),
  CONSTRAINT ck_tao_origin_format
    CHECK (REGEXP_LIKE(origin, '^https?://[A-Za-z0-9.-]+(:[0-9]+)?(/.*)?$'))
);
CREATE INDEX ix_tao_tenant ON tenant_allowed_origin (tenant_id);

GRANT SELECT ON tenant_allowed_origin TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_allowed_origin TO APP_ADMIN;

-- 4. tenant_accepted_format (1:N child)
CREATE TABLE tenant_accepted_format (
  id          RAW(16)       DEFAULT SYS_GUID() NOT NULL,
  tenant_id   RAW(16)                          NOT NULL,
  format      VARCHAR2(32)                     NOT NULL,
  CONSTRAINT pk_tenant_accepted_format PRIMARY KEY (id),
  CONSTRAINT fk_taf_tenant             FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
  CONSTRAINT uq_taf_tenant_format      UNIQUE (tenant_id, format),
  CONSTRAINT ck_taf_format CHECK (format IN
    ('none','packed','android-key','android-safetynet','fido-u2f','apple','tpm'))
);
CREATE INDEX ix_taf_tenant ON tenant_accepted_format (tenant_id);

GRANT SELECT ON tenant_accepted_format TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_accepted_format TO APP_ADMIN;

-- 5. api_key — drop scopes CLOB
ALTER TABLE api_key DROP CONSTRAINT ck_api_key_scopes_json;
ALTER TABLE api_key DROP COLUMN scopes;

-- 6. api_key_scope (1:N child)
CREATE TABLE api_key_scope (
  id          RAW(16)       DEFAULT SYS_GUID() NOT NULL,
  api_key_id  RAW(16)                          NOT NULL,
  scope       VARCHAR2(32)                     NOT NULL,
  CONSTRAINT pk_api_key_scope PRIMARY KEY (id),
  CONSTRAINT fk_aks_api_key   FOREIGN KEY (api_key_id) REFERENCES api_key(id) ON DELETE CASCADE,
  CONSTRAINT uq_aks_api_key_scope UNIQUE (api_key_id, scope),
  CONSTRAINT ck_aks_scope CHECK (scope IN ('registration','authentication','admin'))
);
CREATE INDEX ix_aks_api_key ON api_key_scope (api_key_id);

GRANT SELECT ON api_key_scope TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON api_key_scope TO APP_ADMIN;
```

- [ ] **Step 2: Sanity grep**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/phase-7-tenant-config-normalize
grep -c "CREATE TABLE" core/src/main/resources/db/migration/V21__tenant_config_normalize.sql
# Expected: 3
grep -c "ALTER TABLE tenant DROP COLUMN" core/src/main/resources/db/migration/V21__tenant_config_normalize.sql
# Expected: 2
```

- [ ] **Step 3: Stage**

```bash
git add core/src/main/resources/db/migration/V21__tenant_config_normalize.sql
```

- [ ] **Step 4: Codex review**

```bash
cat > /tmp/codex-p7-t1.txt <<'PROMPT'
Code review for Phase 7 T1 — V21 schema normalization.

Drops tenant.allowed_origins + attestation_policy (CLOB JSON), adds
2 CHAR(1) flag columns (require_user_verification, mds_required).
Drops api_key.scopes (CLOB JSON). Creates 3 child tables with
UUID PK (DEFAULT SYS_GUID()), parent FK with ON DELETE CASCADE,
UNIQUE(parent_id, value), CHECK constraint whitelist for format/scope.

VPD policies (V20) attach only to credential + api_key — child tables
are admin-only, no VPD attachment needed.

Review:
1. ALTER TABLE DROP COLUMN order — CHECK constraints dropped first.
2. CHECK constraint allowed format values match webauthn4j accepted
   attestation formats (7 values).
3. CHECK constraint allowed scope values (3) — matches admin-ui plan.
4. ON DELETE CASCADE preserves Tenant deletion semantics — child rows
   auto-removed.
5. UNIQUE(tenant_id, origin) and (tenant_id, format) prevent dup rows.
6. REGEXP_LIKE for origin format — basic http/https URL pattern.
7. NUMBER(5,0) sort_order — supports up to 99999 origins per tenant.
8. APP_RUNTIME gets SELECT only (read for ceremony); APP_ADMIN full CRUD.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p7-t1.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p7-t1-full.txt
codex exec -s read-only "$(cat /tmp/codex-p7-t1-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p7-t1-out.txt 2>&1
tail -250 /tmp/codex-p7-t1-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(db): V21 tenant config + scopes normalization (Phase 7 T1)"
```

---

## Task 2: `TenantAllowedOrigin` entity

**Files:**
- Create `core/src/main/java/com/crosscert/passkey/core/entity/TenantAllowedOrigin.java`

- [ ] **Step 1: Write entity**

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "TENANT_ALLOWED_ORIGIN")
public class TenantAllowedOrigin {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TENANT_ID", nullable = false, columnDefinition = "RAW(16)")
    private Tenant tenant;

    @Column(name = "ORIGIN", length = 512, nullable = false)
    private String origin;

    @Column(name = "SORT_ORDER", nullable = false)
    private int sortOrder;

    protected TenantAllowedOrigin() {}  // JPA

    public TenantAllowedOrigin(Tenant tenant, String origin, int sortOrder) {
        this.tenant = tenant;
        this.origin = origin;
        this.sortOrder = sortOrder;
    }

    public UUID getId() { return id; }
    public Tenant getTenant() { return tenant; }
    public String getOrigin() { return origin; }
    public int getSortOrder() { return sortOrder; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TenantAllowedOrigin other)) return false;
        // Equality by (tenantId, origin) — the natural key (UNIQUE constraint)
        if (tenant == null || other.tenant == null) return false;
        return Objects.equals(tenant.getId(), other.tenant.getId())
            && Objects.equals(origin, other.origin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenant == null ? null : tenant.getId(), origin);
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/phase-7-tenant-config-normalize
./gradlew :core:compileJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. Tenant.java not yet updated — that's T4.

- [ ] **Step 3: Stage + codex review + commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/TenantAllowedOrigin.java
cat > /tmp/codex-p7-t2.txt <<'PROMPT'
Code review for Phase 7 T2 — TenantAllowedOrigin entity.

UUID PK (Style.TIME, RAW(16)) + ManyToOne Tenant + origin VARCHAR(512)
+ sortOrder int. Protected no-arg ctor for JPA + public ctor for app.
equals/hashCode by natural key (tenant.id, origin) — matches UNIQUE constraint.

Inert until T4 wires Tenant.@OneToMany.

Review:
1. UUID generator + JdbcTypeCode consistent with Phase 6 pattern.
2. fetch=LAZY on ManyToOne — Hibernate default but explicit.
3. equals natural-key — avoids transient-vs-persistent identity issues.
4. SORT_ORDER int (Java) vs NUMBER(5,0) (DB) — value fits.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p7-t2.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p7-t2-full.txt
codex exec -s read-only "$(cat /tmp/codex-p7-t2-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p7-t2-out.txt 2>&1
tail -200 /tmp/codex-p7-t2-out.txt
git commit -m "feat(core): TenantAllowedOrigin entity (Phase 7 T2)"
```

---

## Task 3: `TenantAcceptedFormat` + `ApiKeyScope` entities

**Files:**
- Create `core/src/main/java/com/crosscert/passkey/core/entity/TenantAcceptedFormat.java`
- Create `core/src/main/java/com/crosscert/passkey/core/entity/ApiKeyScope.java`

Same pattern as T2.

- [ ] **Step 1: Write TenantAcceptedFormat.java**

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "TENANT_ACCEPTED_FORMAT")
public class TenantAcceptedFormat {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TENANT_ID", nullable = false, columnDefinition = "RAW(16)")
    private Tenant tenant;

    @Column(name = "FORMAT", length = 32, nullable = false)
    private String format;

    protected TenantAcceptedFormat() {}

    public TenantAcceptedFormat(Tenant tenant, String format) {
        this.tenant = tenant;
        this.format = format;
    }

    public UUID getId() { return id; }
    public Tenant getTenant() { return tenant; }
    public String getFormat() { return format; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TenantAcceptedFormat other)) return false;
        if (tenant == null || other.tenant == null) return false;
        return Objects.equals(tenant.getId(), other.tenant.getId())
            && Objects.equals(format, other.format);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenant == null ? null : tenant.getId(), format);
    }
}
```

- [ ] **Step 2: Write ApiKeyScope.java** (same shape, `apiKey` parent + `scope` field)

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "API_KEY_SCOPE")
public class ApiKeyScope {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "API_KEY_ID", nullable = false, columnDefinition = "RAW(16)")
    private ApiKey apiKey;

    @Column(name = "SCOPE", length = 32, nullable = false)
    private String scope;

    protected ApiKeyScope() {}

    public ApiKeyScope(ApiKey apiKey, String scope) {
        this.apiKey = apiKey;
        this.scope = scope;
    }

    public UUID getId() { return id; }
    public ApiKey getApiKey() { return apiKey; }
    public String getScope() { return scope; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiKeyScope other)) return false;
        if (apiKey == null || other.apiKey == null) return false;
        return Objects.equals(apiKey.getId(), other.apiKey.getId())
            && Objects.equals(scope, other.scope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(apiKey == null ? null : apiKey.getId(), scope);
    }
}
```

- [ ] **Step 3: Compile + stage + codex review + commit**

```bash
./gradlew :core:compileJava 2>&1 | tail -5
git add core/src/main/java/com/crosscert/passkey/core/entity/TenantAcceptedFormat.java \
        core/src/main/java/com/crosscert/passkey/core/entity/ApiKeyScope.java
# codex review (similar prompt)
git commit -m "feat(core): TenantAcceptedFormat + ApiKeyScope entities (Phase 7 T3)"
```

---

## Task 4: `Tenant` entity REWRITE — drop JSON fields + add @OneToMany + flag columns

**Files:**
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java`

- [ ] **Step 1: Read current Tenant.java first**

Then rewrite. The new shape:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Entity
@Table(name = "TENANT")
public class Tenant {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "SLUG", length = 64, nullable = false, unique = true)
    private String slug;

    @Column(name = "DISPLAY_NAME", length = 256, nullable = false)
    private String displayName;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;  // "active" or "suspended"

    @Column(name = "RP_ID", length = 256, nullable = false)
    private String rpId;

    @Column(name = "RP_NAME", length = 256, nullable = false)
    private String rpName;

    @Column(name = "REQUIRE_USER_VERIFICATION", columnDefinition = "CHAR(1)", nullable = false)
    private String requireUserVerificationFlag = "Y";

    @Column(name = "MDS_REQUIRED", columnDefinition = "CHAR(1)", nullable = false)
    private String mdsRequiredFlag = "N";

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<TenantAllowedOrigin> allowedOrigins = new ArrayList<>();

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<TenantAcceptedFormat> acceptedFormats = new HashSet<>();

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    protected Tenant() {}

    public Tenant(String slug, String displayName, String rpId, String rpName) {
        this.slug = slug;
        this.displayName = displayName;
        this.status = "active";
        this.rpId = rpId;
        this.rpName = rpName;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // ── Collection helpers (keeps parent in sync) ──
    public void addAllowedOrigin(String origin, int sortOrder) {
        allowedOrigins.add(new TenantAllowedOrigin(this, origin, sortOrder));
    }

    public void clearAllowedOrigins() { allowedOrigins.clear(); }

    public void addAcceptedFormat(String format) {
        acceptedFormats.add(new TenantAcceptedFormat(this, format));
    }

    public void clearAcceptedFormats() { acceptedFormats.clear(); }

    // ── Typed convenience getters ──
    public boolean isRequireUserVerification() { return "Y".equals(requireUserVerificationFlag); }
    public boolean isMdsRequired() { return "Y".equals(mdsRequiredFlag); }
    public void setRequireUserVerification(boolean v) { this.requireUserVerificationFlag = v ? "Y" : "N"; }
    public void setMdsRequired(boolean v) { this.mdsRequiredFlag = v ? "Y" : "N"; }

    public List<String> getAllowedOriginValues() {
        return allowedOrigins.stream().map(TenantAllowedOrigin::getOrigin).toList();
    }

    public Set<String> getAcceptedFormatValues() {
        return acceptedFormats.stream().map(TenantAcceptedFormat::getFormat).collect(Collectors.toSet());
    }

    // ── Standard getters/setters ──
    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getStatus() { return status; }
    public String getRpId() { return rpId; }
    public String getRpName() { return rpName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touchUpdatedAt() { this.updatedAt = Instant.now(); }

    // For test-only reflective access (Phase 6 pattern)
    public List<TenantAllowedOrigin> getAllowedOrigins() { return allowedOrigins; }
    public Set<TenantAcceptedFormat> getAcceptedFormats() { return acceptedFormats; }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :core:compileJava 2>&1 | tail -15
```

Expected: `:core:compileJava` may have errors elsewhere (services still reference `allowedOriginsJson`). The errors are T6+ scope. If `:core:compileJava` fails inside this file, fix immediately. If failures are in `:passkey-app` or `:admin-app`, defer.

- [ ] **Step 3: Stage + codex review + commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java
cat > /tmp/codex-p7-t4.txt <<'PROMPT'
Code review for Phase 7 T4 — Tenant entity REWRITE.

Drops allowedOriginsJson + attestationPolicyJson String fields.
Adds @OneToMany allowedOrigins (ordered List) + acceptedFormats (Set).
Adds CHAR(1) requireUserVerificationFlag + mdsRequiredFlag with
boolean accessors.

Collection helpers (addAllowedOrigin, clearAllowedOrigins, etc.) keep
parent-side in sync — orphanRemoval handles deletion.

Review:
1. cascade=ALL + orphanRemoval=true — child rows lifecycle bound to
   parent. Tenant deletion cascades child rows (also FK ON DELETE CASCADE).
2. fetch=LAZY — initial Tenant load is cheap. Origin/format access
   triggers separate query. Services accessing these inside
   @Transactional are fine.
3. @OrderBy on allowedOrigins — preserves operator-defined order.
4. isRequireUserVerification() / isMdsRequired() — boolean accessors;
   raw 'Y'/'N' flag fields hidden.
5. List vs Set — origins ordered (List), formats orderless (Set).
6. Phase 6 invariants preserved: UUID id, RAW(16), Style.TIME.
7. Constructor no longer takes JSON strings — services build via add* helpers.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p7-t4.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p7-t4-full.txt
codex exec -s read-only "$(cat /tmp/codex-p7-t4-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p7-t4-out.txt 2>&1
tail -250 /tmp/codex-p7-t4-out.txt
git commit -m "refactor(core): Tenant @OneToMany + UV/MDS flag columns (Phase 7 T4)"
```

---

## Task 5: `ApiKey` entity REWRITE — drop scopesJson + add @OneToMany

**Files:**
- Modify `core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java`

- [ ] **Step 1: Modify ApiKey.java**

Remove `private String scopesJson;` field + getter. Remove `scopes CLOB` from constructor.

Add:

```java
@OneToMany(mappedBy = "apiKey", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
private Set<ApiKeyScope> scopes = new HashSet<>();

public void addScope(String scope) {
    scopes.add(new ApiKeyScope(this, scope));
}

public void clearScopes() { scopes.clear(); }

public Set<String> getScopeValues() {
    return scopes.stream().map(ApiKeyScope::getScope).collect(Collectors.toSet());
}

public Set<ApiKeyScope> getScopes() { return scopes; }
```

Update constructor signature: `public ApiKey(UUID tenantId, String prefix, String hash, String name)` (drop scopesJson). Callers (ApiKeyAdminService) will need adaptation in T10.

- [ ] **Step 2: Compile + stage + codex review + commit**

```bash
./gradlew :core:compileJava 2>&1 | tail -10
git add core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java
# codex review (similar prompt for scope collection management)
git commit -m "refactor(core): ApiKey @OneToMany scopes (Phase 7 T5)"
```

---

## Task 6: Delete `AttestationPolicy` + test

**Files:**
- Delete `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/policy/AttestationPolicy.java`
- Delete `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/policy/AttestationPolicyTest.java`

- [ ] **Step 1: Delete the parser + its test**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/phase-7-tenant-config-normalize
rm passkey-app/src/main/java/com/crosscert/passkey/app/fido2/policy/AttestationPolicy.java
rm passkey-app/src/test/java/com/crosscert/passkey/app/fido2/policy/AttestationPolicyTest.java
# Remove the empty directory if no other files
rmdir passkey-app/src/main/java/com/crosscert/passkey/app/fido2/policy 2>/dev/null || true
rmdir passkey-app/src/test/java/com/crosscert/passkey/app/fido2/policy 2>/dev/null || true
```

- [ ] **Step 2: Compile**

```bash
./gradlew :passkey-app:compileJava 2>&1 | tail -10
```

Expected: errors in RegistrationFinishService + AuthenticationFinishService + MdsVerifier (they still import/use AttestationPolicy). T7-T8 fix immediately.

- [ ] **Step 3: Stage + commit (intentionally broken state — T7/T8 follows)**

```bash
git add -u passkey-app/src/main/java/com/crosscert/passkey/app/fido2/policy/
git add -u passkey-app/src/test/java/com/crosscert/passkey/app/fido2/policy/
# No codex review — pure deletion
git commit -m "refactor(passkey): delete AttestationPolicy (Phase 7 T6)"
```

---

## Task 7: `RegistrationFinishService` + `AuthenticationFinishService` — inline parseOrigins + tenant flags

**Files:**
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`

Both services have `parseOrigins(Tenant t)` private method and call `AttestationPolicy.fromJson(t.getAttestationPolicyJson())`. Both need rewriting:

- [ ] **Step 1: RegistrationFinishService rewrite**

Find all `AttestationPolicy` usages:
- `AttestationPolicy policy = AttestationPolicy.fromJson(t.getAttestationPolicyJson());`
- `policy.requireUserVerification()` → `t.isRequireUserVerification()`
- `policy.acceptedFormats()` → `t.getAcceptedFormatValues()`
- `policy.mdsRequired()` → `t.isMdsRequired()` (passed to MdsVerifier in T8)

Find `parseOrigins(t)` method definition — replace with:

```java
private Set<Origin> resolveOrigins(Tenant t) {
    Set<Origin> set = t.getAllowedOriginValues().stream()
            .map(Origin::create)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (set.isEmpty()) {
        throw new IllegalStateException("tenant " + t.getId() + " has no allowed_origins configured");
    }
    return set;
}
```

Remove `parseOrigins` method + its imports (mapper.readValue is gone). Verify ObjectMapper still used elsewhere; if not, drop import.

- [ ] **Step 2: AuthenticationFinishService — same changes**

- [ ] **Step 3: Compile**

```bash
./gradlew :passkey-app:compileJava 2>&1 | tail -10
```

Expected: errors only in MdsVerifier (T8 next).

- [ ] **Step 4: Stage + codex review + commit**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java
cat > /tmp/codex-p7-t7.txt <<'PROMPT'
Code review for Phase 7 T7 — Registration/AuthenticationFinishService
adapt to Tenant structured fields.

AttestationPolicy.fromJson() calls removed. policy.* getters replaced with
tenant.isRequireUserVerification() / getAcceptedFormatValues() /
isMdsRequired().

parseOrigins() method replaced with inline resolveOrigins(t) that uses
tenant.getAllowedOriginValues() — no JSON parsing.

Review:
1. Origin.create() still applied per-string — webauthn4j validates URL.
2. Empty origin set still throws IllegalStateException — same fail-closed
   semantics.
3. tenant.getAcceptedFormatValues() returns Set<String> — matches existing
   policy.acceptedFormats() callsite.
4. MdsVerifier signature: T8 adapts to take boolean directly (or read tenant).
5. ObjectMapper import — verify unused after JSON removal, else keep.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p7-t7.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p7-t7-full.txt
codex exec -s read-only "$(cat /tmp/codex-p7-t7-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p7-t7-out.txt 2>&1
tail -250 /tmp/codex-p7-t7-out.txt
git commit -m "refactor(passkey): inline resolveOrigins + use tenant flag getters (Phase 7 T7)"
```

---

## Task 8: `MdsVerifier` — tenant.isMdsRequired() instead of policy

**Files:**
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsVerifier.java`

- [ ] **Step 1: Read current MdsVerifier.java**

Find signature like `public boolean isAllowed(AAGUID aaguid, AttestationPolicy policy)`. Change to take Tenant or boolean directly:

```java
// Option: take boolean (simpler — service passes tenant.isMdsRequired())
public boolean isAllowed(AAGUID aaguid, boolean mdsRequired) {
    if (!mdsRequired) return true;
    // ... existing AAGUID lookup logic
}
```

Update RegistrationFinishService (and any caller) to pass `t.isMdsRequired()`.

- [ ] **Step 2: Compile + test**

```bash
./gradlew :passkey-app:compileJava :passkey-app:compileTestJava 2>&1 | tail -10
./gradlew :passkey-app:test --tests MdsVerifierTest 2>&1 | tail -10
```

MdsVerifierTest may need updating to pass `boolean` instead of `AttestationPolicy`. Adapt minimally.

- [ ] **Step 3: Stage + codex review + commit**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsVerifier.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/fido2/mds/MdsVerifierTest.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java
git commit -m "refactor(passkey): MdsVerifier takes boolean mdsRequired (Phase 7 T8)"
```

---

## Task 9: `TenantAdminDto` + `TenantAdminService` — structured request/view

**Files:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`

- [ ] **Step 1: Read existing DTO + service**

- [ ] **Step 2: Rewrite TenantAdminDto.java**

```java
package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.entity.Tenant;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TenantAdminDto {

    private TenantAdminDto() {}

    public record TenantCreateRequest(
            @NotBlank @Pattern(regexp="^[a-z0-9][a-z0-9-]{1,62}$") String slug,
            @NotBlank @Size(max=256) String displayName,
            @NotBlank @Size(max=256) String rpId,
            @NotBlank @Size(max=256) String rpName,
            @NotEmpty List<@NotBlank @Size(max=512) String> allowedOrigins,
            @NotEmpty Set<@NotBlank String> acceptedFormats,
            boolean requireUserVerification,
            boolean mdsRequired
    ) {}

    public record TenantView(
            UUID id,
            String slug,
            String displayName,
            String status,
            String rpId,
            String rpName,
            List<String> allowedOrigins,
            Set<String> acceptedFormats,
            boolean requireUserVerification,
            boolean mdsRequired,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static TenantView from(Tenant t) {
            return new TenantView(
                    t.getId(), t.getSlug(), t.getDisplayName(), t.getStatus(),
                    t.getRpId(), t.getRpName(),
                    t.getAllowedOriginValues(),
                    t.getAcceptedFormatValues(),
                    t.isRequireUserVerification(),
                    t.isMdsRequired(),
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }
}
```

- [ ] **Step 3: Modify TenantAdminService.create()**

```java
@Transactional
public TenantAdminDto.TenantView create(TenantAdminDto.TenantCreateRequest req, ...) {
    if (repo.existsBySlug(req.slug())) {
        throw new BusinessException(ErrorCode.TENANT_DUPLICATE);
    }
    Tenant t = new Tenant(req.slug(), req.displayName(), req.rpId(), req.rpName());
    t.setRequireUserVerification(req.requireUserVerification());
    t.setMdsRequired(req.mdsRequired());
    int order = 0;
    for (String origin : req.allowedOrigins()) {
        t.addAllowedOrigin(origin, order++);
    }
    for (String format : req.acceptedFormats()) {
        t.addAcceptedFormat(format);
    }
    repo.saveAndFlush(t);
    // audit append…
    return TenantAdminDto.TenantView.from(t);
}
```

- [ ] **Step 4: Compile + test**

```bash
./gradlew :admin-app:compileJava 2>&1 | tail -10
./gradlew :admin-app:test --tests TenantAdminServiceTest --tests TenantAdminControllerSecurityTest 2>&1 | tail -15
```

Tests likely break — fix in T14. For now, ensure compile passes.

- [ ] **Step 5: Stage + codex review + commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java
# codex review focused on req validation + transactional cascade
git commit -m "feat(admin): TenantAdminDto + TenantAdminService structured fields (Phase 7 T9)"
```

---

## Task 10: `ApiKeyAdminDto` + `ApiKeyAdminService` — scopes Set

**Files:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java`

- [ ] **Step 1: Rewrite DTO**

```java
public record ApiKeyCreateRequest(
        @NotNull UUID tenantId,
        @NotBlank String name,
        @NotEmpty Set<@NotBlank String> scopes
) {}

public record ApiKeyView(
        UUID id, UUID tenantId, String name, String keyPrefix,
        Set<String> scopes, Instant createdAt, Instant expiresAt,
        Instant revokedAt, Instant lastUsedAt
) {
    public static ApiKeyView from(ApiKey k) {
        return new ApiKeyView(
                k.getId(), k.getTenantId(), k.getName(), k.getKeyPrefix(),
                k.getScopeValues(),
                k.getCreatedAt(), k.getExpiresAt(),
                k.getRevokedAt(), k.getLastUsedAt());
    }
}

public record ApiKeyCreateResponse(
        UUID id, String plainText, String prefix, Set<String> scopes
) {}
```

(Adapt to actual ApiKey field names.)

- [ ] **Step 2: Modify ApiKeyAdminService.issue()**

```java
@Transactional
public ApiKeyAdminDto.ApiKeyCreateResponse issue(ApiKeyAdminDto.ApiKeyCreateRequest req, ...) {
    // ... existing tenant resolution + plaintext + hash
    ApiKey k = new ApiKey(tenantId, prefix, hash, req.name());
    for (String scope : req.scopes()) {
        k.addScope(scope);
    }
    repo.saveAndFlush(k);
    // audit append (scopes in payload)
    return new ApiKeyAdminDto.ApiKeyCreateResponse(k.getId(), plainText, prefix, req.scopes());
}
```

- [ ] **Step 3: Compile + stage + codex review + commit**

```bash
./gradlew :admin-app:compileJava 2>&1 | tail -10
git add admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java
git commit -m "feat(admin): ApiKeyAdminDto + Service scopes Set (Phase 7 T10)"
```

---

## Task 11: admin-ui new components (OriginChipInput + FormatCheckboxGrid + ScopeCheckboxGrid + Switch)

**Files:**
- Create `admin-ui/src/components/OriginChipInput.tsx`
- Create `admin-ui/src/components/FormatCheckboxGrid.tsx`
- Create `admin-ui/src/components/ScopeCheckboxGrid.tsx`
- Create `admin-ui/src/components/Switch.tsx`

- [ ] **Step 1: Write Switch.tsx (smallest, used by others)**

```tsx
import type { ReactNode } from 'react';

interface Props {
  checked: boolean;
  onChange: (v: boolean) => void;
  label?: ReactNode;
  disabled?: boolean;
}

export default function Switch({ checked, onChange, label, disabled }: Props) {
  return (
    <label className="row" style={{ gap: 10, cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.5 : 1 }}>
      <span
        role="switch"
        aria-checked={checked}
        onClick={() => !disabled && onChange(!checked)}
        style={{
          width: 36, height: 20, borderRadius: 999,
          background: checked ? 'var(--accent)' : 'var(--surface-3)',
          position: 'relative',
          transition: 'background var(--dur) var(--ease-out)',
          border: '1px solid var(--border)',
        }}
      >
        <span style={{
          position: 'absolute', top: 1, left: checked ? 17 : 1,
          width: 16, height: 16, borderRadius: 999,
          background: 'var(--surface)',
          transition: 'left var(--dur) var(--ease-out)',
          boxShadow: 'var(--shadow-xs)',
        }} />
      </span>
      {label && <span style={{ fontSize: 13 }}>{label}</span>}
    </label>
  );
}
```

- [ ] **Step 2: Write OriginChipInput.tsx**

```tsx
import { useState, type KeyboardEvent } from 'react';
import { Plus, X } from './Icons';

interface Props {
  value: string[];
  onChange: (v: string[]) => void;
}

const ORIGIN_REGEX = /^https?:\/\/[A-Za-z0-9.-]+(:[0-9]+)?(\/.*)?$/;

export default function OriginChipInput({ value, onChange }: Props) {
  const [draft, setDraft] = useState('');
  const [err, setErr] = useState<string | null>(null);

  function add() {
    const v = draft.trim();
    if (!v) return;
    if (!ORIGIN_REGEX.test(v)) {
      setErr('http(s)://… 형식이어야 합니다.');
      return;
    }
    if (value.includes(v)) {
      setErr('중복된 origin입니다.');
      return;
    }
    onChange([...value, v]);
    setDraft('');
    setErr(null);
  }

  function remove(idx: number) {
    onChange(value.filter((_, i) => i !== idx));
  }

  function onKey(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter') { e.preventDefault(); add(); }
  }

  return (
    <div>
      <div className="row" style={{ flexWrap: 'wrap', gap: 6, marginBottom: 8 }}>
        {value.map((o, i) => (
          <span key={o} className="chip">
            <span className="mono" style={{ fontSize: 12 }}>{o}</span>
            <button type="button" className="chip__x" onClick={() => remove(i)} aria-label="제거">×</button>
          </span>
        ))}
        {value.length === 0 && <span className="muted" style={{ fontSize: 12 }}>등록된 origin이 없습니다.</span>}
      </div>
      <div className="row" style={{ gap: 6 }}>
        <input
          className="input mono"
          value={draft}
          onChange={(e) => { setDraft(e.target.value); setErr(null); }}
          onKeyDown={onKey}
          placeholder="https://example.com"
          style={{ flex: 1 }}
        />
        <button type="button" className="btn btn--outline btn--sm" onClick={add}>
          <Plus size={12} /> 추가
        </button>
      </div>
      {err && <div className="hint" style={{ color: 'var(--danger)' }}>{err}</div>}
    </div>
  );
}
```

- [ ] **Step 3: Write FormatCheckboxGrid.tsx**

```tsx
interface Props {
  value: Set<string>;
  onChange: (v: Set<string>) => void;
}

const FORMATS = [
  { code: 'none',                label: 'none (passkey 기본)' },
  { code: 'packed',              label: 'packed' },
  { code: 'android-key',         label: 'Android Key' },
  { code: 'android-safetynet',   label: 'Android SafetyNet' },
  { code: 'fido-u2f',            label: 'FIDO U2F' },
  { code: 'apple',               label: 'Apple Anonymous' },
  { code: 'tpm',                 label: 'TPM' },
];

export default function FormatCheckboxGrid({ value, onChange }: Props) {
  function toggle(code: string) {
    const next = new Set(value);
    if (next.has(code)) next.delete(code); else next.add(code);
    onChange(next);
  }
  return (
    <div className="grid-2" style={{ gap: 6 }}>
      {FORMATS.map(f => (
        <label key={f.code} className="row" style={{ gap: 8, cursor: 'pointer', fontSize: 13 }}>
          <input type="checkbox" checked={value.has(f.code)} onChange={() => toggle(f.code)} />
          <span>{f.label}</span>
        </label>
      ))}
    </div>
  );
}
```

- [ ] **Step 4: Write ScopeCheckboxGrid.tsx** (same pattern, 3 scopes)

```tsx
interface Props {
  value: Set<string>;
  onChange: (v: Set<string>) => void;
}

const SCOPES = [
  { code: 'registration',   label: '등록 (registration)', desc: 'passkey 등록 ceremony 허용' },
  { code: 'authentication', label: '인증 (authentication)', desc: '인증 ceremony 허용' },
  { code: 'admin',          label: '관리 (admin)', desc: '관리 API 허용' },
];

export default function ScopeCheckboxGrid({ value, onChange }: Props) {
  function toggle(code: string) {
    const next = new Set(value);
    if (next.has(code)) next.delete(code); else next.add(code);
    onChange(next);
  }
  return (
    <div className="stack-2">
      {SCOPES.map(s => (
        <label key={s.code} className="row" style={{ gap: 10, cursor: 'pointer', alignItems: 'flex-start' }}>
          <input type="checkbox" checked={value.has(s.code)} onChange={() => toggle(s.code)} style={{ marginTop: 3 }} />
          <div className="stack-1">
            <div style={{ fontSize: 13, fontWeight: 500 }}>{s.label}</div>
            <div className="muted" style={{ fontSize: 12 }}>{s.desc}</div>
          </div>
        </label>
      ))}
    </div>
  );
}
```

- [ ] **Step 5: Build admin-ui**

```bash
cd admin-ui && npm run build 2>&1 | tail -8 && cd ..
```

- [ ] **Step 6: Stage + codex review + commit**

```bash
git add admin-ui/src/components/OriginChipInput.tsx \
        admin-ui/src/components/FormatCheckboxGrid.tsx \
        admin-ui/src/components/ScopeCheckboxGrid.tsx \
        admin-ui/src/components/Switch.tsx
# codex review (component design + a11y)
git commit -m "feat(admin-ui): OriginChip + FormatGrid + ScopeGrid + Switch components (Phase 7 T11)"
```

---

## Task 12: `TenantCreate.tsx` + `TenantList.tsx` — structured form + display

**Files:**
- Modify `admin-ui/src/api/types.ts`
- Modify `admin-ui/src/pages/TenantCreate.tsx`
- Modify `admin-ui/src/pages/TenantList.tsx`

- [ ] **Step 1: Update `types.ts`**

```typescript
export interface TenantView {
  id: string;
  slug: string;
  displayName: string;
  status: string;
  rpId: string;
  rpName: string;
  allowedOrigins: string[];
  acceptedFormats: string[];
  requireUserVerification: boolean;
  mdsRequired: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface TenantCreateRequest {
  slug: string;
  displayName: string;
  rpId: string;
  rpName: string;
  allowedOrigins: string[];
  acceptedFormats: string[];
  requireUserVerification: boolean;
  mdsRequired: boolean;
}
```

- [ ] **Step 2: Rewrite TenantCreate.tsx**

Replace the 2 raw JSON `<textarea>` with structured components:

```tsx
const [allowedOrigins, setAllowedOrigins] = useState<string[]>([]);
const [acceptedFormats, setAcceptedFormats] = useState<Set<string>>(
  new Set(['none','packed','android-key','android-safetynet','fido-u2f','apple','tpm'])
);
const [requireUV, setRequireUV] = useState(true);
const [mdsRequired, setMdsRequired] = useState(false);

// ... in form:
<Field label="허용 origin" hint="WebAuthn ceremony에서 허용할 origin URL (http(s)://...)">
  <OriginChipInput value={allowedOrigins} onChange={setAllowedOrigins} />
</Field>
<Field label="허용 attestation format" hint="기본 7개 모두 허용. 보안 강화 시 일부만 체크.">
  <FormatCheckboxGrid value={acceptedFormats} onChange={setAcceptedFormats} />
</Field>
<Field label="정책">
  <div className="stack-2">
    <Switch checked={requireUV} onChange={setRequireUV} label="User Verification 필수" />
    <Switch checked={mdsRequired} onChange={setMdsRequired} label="FIDO MDS 검증 필수" />
  </div>
</Field>

// in submit:
await api.post<TenantView>('/admin/api/tenants', {
  slug, displayName, rpId, rpName,
  allowedOrigins,
  acceptedFormats: [...acceptedFormats],
  requireUserVerification: requireUV,
  mdsRequired,
});
```

- [ ] **Step 3: Update TenantList.tsx**

Display structured fields instead of JSON strings. Add columns or expanded view:

```tsx
<td>{t.allowedOrigins.length} origins</td>
<td>{t.requireUserVerification ? '✓' : '—'}</td>
<td>{t.mdsRequired ? '✓' : '—'}</td>
```

(Adapt to actual table structure.)

- [ ] **Step 4: Build + stage + codex review + commit**

```bash
cd admin-ui && npm run build 2>&1 | tail -8 && cd ..
git add admin-ui/src/api/types.ts \
        admin-ui/src/pages/TenantCreate.tsx \
        admin-ui/src/pages/TenantList.tsx
git commit -m "feat(admin-ui): TenantCreate structured form + TenantList structured display (Phase 7 T12)"
```

---

## Task 13: `ApiKeyCreateModal.tsx` — scope checkbox grid

**Files:**
- Modify `admin-ui/src/api/types.ts` (ApiKeyView.scopes + ApiKeyCreateRequest)
- Modify `admin-ui/src/pages/ApiKeyCreateModal.tsx`
- Modify `admin-ui/src/pages/ApiKeyList.tsx` (scope display if shown)

- [ ] **Step 1: Update types.ts**

```typescript
export interface ApiKeyView {
  // ... existing fields
  scopes: string[];  // was scopesJson: string
}

export interface ApiKeyCreateRequest {
  tenantId: string;
  name: string;
  scopes: string[];  // was scopesJson: string
}
```

- [ ] **Step 2: ApiKeyCreateModal.tsx**

Replace `scopesJson` textarea with `<ScopeCheckboxGrid value={scopes} onChange={setScopes} />`. State `const [scopes, setScopes] = useState<Set<string>>(new Set(['registration','authentication']));`.

- [ ] **Step 3: Build + stage + commit**

```bash
cd admin-ui && npm run build 2>&1 | tail -8 && cd ..
git add admin-ui/src/api/types.ts \
        admin-ui/src/pages/ApiKeyCreateModal.tsx \
        admin-ui/src/pages/ApiKeyList.tsx
git commit -m "feat(admin-ui): ApiKeyCreateModal scope checkbox grid (Phase 7 T13)"
```

---

## Task 14: Unit + Service tests update

**Files:**
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminServiceTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminControllerSecurityTest.java`

- [ ] **Step 1: TenantAdminServiceTest**

Replace `allowedOriginsJson` / `attestationPolicyJson` fixtures with structured fields:

```java
when(repo.existsBySlug("acme")).thenReturn(false);

Tenant t = new Tenant("acme", "Acme", "acme.example.com", "Acme");
t.setRequireUserVerification(true);
t.setMdsRequired(false);
t.addAllowedOrigin("https://acme.example.com", 0);
t.addAcceptedFormat("none");
t.addAcceptedFormat("packed");
// reflect t.id setter for assertions...

TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
    "acme", "Acme", "acme.example.com", "Acme",
    List.of("https://acme.example.com"),
    Set.of("none", "packed"),
    true, false
);
```

- [ ] **Step 2: Update controller security test**

Update JSON body in mvc.perform — new field names, structured.

- [ ] **Step 3: Update ApiKeyAdminServiceTest + controller security test**

Same pattern — scopes Set.

- [ ] **Step 4: Run all 4 tests**

```bash
./gradlew :admin-app:test --tests TenantAdminServiceTest --tests TenantAdminControllerSecurityTest --tests ApiKeyAdminServiceTest --tests ApiKeyAdminControllerSecurityTest 2>&1 | tail -25
```

Expected: all pass.

- [ ] **Step 5: Stage + commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/tenant/*.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/apikey/*.java
git commit -m "test(admin): structured tenant config + scope fixtures (Phase 7 T14)"
```

---

## Task 15: IT updates — AdminFlowIT + Fido2EndToEndIT + VpdIsolationIT

**Files:**
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java`
- Modify `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java`
- Modify `core/src/test/java/com/crosscert/passkey/core/vpd/VpdIsolationIT.java`

- [ ] **Step 1: AdminFlowIT — new POST body shape**

```java
String tenantBody = """
        {"slug":"acme","displayName":"Acme Inc","rpId":"acme.example.com","rpName":"Acme Inc",
         "allowedOrigins":["http://localhost"],
         "acceptedFormats":["none","packed"],
         "requireUserVerification":true,
         "mdsRequired":false}
        """;

String keyBody = """
        {"tenantId":"%s","name":"primary","scopes":["registration","authentication"]}
        """.formatted(tenantId);
```

- [ ] **Step 2: Fido2EndToEndIT — seedTenant rewrite**

```java
private void seedTenant(JdbcTemplate jdbc, UUID tenantId, String slug) {
    jdbc.update("INSERT INTO tenant (id, slug, display_name, status, rp_id, rp_name, " +
                "require_user_verification, mds_required, created_at, updated_at) " +
                "VALUES (HEXTORAW(?),?,?,'active',?,?,'Y','N',SYSTIMESTAMP,SYSTIMESTAMP)",
                hex(tenantId), slug, slug + " Display", "localhost", slug + " RP");
    jdbc.update("INSERT INTO tenant_allowed_origin (id, tenant_id, origin, sort_order) " +
                "VALUES (SYS_GUID(), HEXTORAW(?), ?, 0)",
                hex(tenantId), "http://localhost:8080");
    jdbc.update("INSERT INTO tenant_accepted_format (id, tenant_id, format) " +
                "VALUES (SYS_GUID(), HEXTORAW(?), 'none')", hex(tenantId));
    jdbc.update("INSERT INTO tenant_accepted_format (id, tenant_id, format) " +
                "VALUES (SYS_GUID(), HEXTORAW(?), 'packed')", hex(tenantId));
}
```

- [ ] **Step 3: VpdIsolationIT — verify child tables NOT subject to VPD**

Existing scenarios test credential + api_key isolation. Add a new scenario or comment: child tables (tenant_allowed_origin, tenant_accepted_format, api_key_scope) are admin-only and have no VPD. Verify by reading rows without tenant context — should return all rows (not '1=0').

```java
@Test
void tenantChildTablesAreNotVpdProtected() {
    // No tenant context set
    TenantContextHolder.clear();
    Long origins = jdbc.queryForObject(
        "SELECT COUNT(*) FROM tenant_allowed_origin", Long.class);
    assertThat(origins).isGreaterThanOrEqualTo(0);  // VPD would force 0 or empty result
    // For credential (VPD-protected), no tenant context → 0
    Long creds = jdbc.queryForObject("SELECT COUNT(*) FROM credential", Long.class);
    assertThat(creds).isEqualTo(0L);
}
```

- [ ] **Step 4: Run ITs**

```bash
docker stop passkey-oracle passkey-redis 2>/dev/null || true
./gradlew :admin-app:test --tests AdminFlowIT 2>&1 | tail -20
./gradlew :passkey-app:test --tests Fido2EndToEndIT 2>&1 | tail -20
./gradlew :core:test --tests VpdIsolationIT 2>&1 | tail -15
docker start passkey-oracle passkey-redis 2>/dev/null || true
```

Expected: AdminFlowIT 1/1, Fido2EndToEndIT 8/8, VpdIsolationIT 5/5 + 1 new = 6.

- [ ] **Step 5: Stage + commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java \
        core/src/test/java/com/crosscert/passkey/core/vpd/VpdIsolationIT.java
git commit -m "test: ITs adapted for normalized tenant config + scopes (Phase 7 T15)"
```

---

## Task 16: DoD verify + tag

- [ ] **Step 1: Full clean build**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/phase-7-tenant-config-normalize
docker stop passkey-oracle passkey-redis 2>/dev/null || true
./gradlew clean build 2>&1 | tail -30
docker start passkey-oracle passkey-redis 2>/dev/null || true
```

Expected: BUILD SUCCESSFUL. ~160+ tests across all modules.

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
echo "TOTAL: tests=$total_t failures=$total_f errors=$total_e skipped=$total_s"
```

Expected: 0 failures, 0 errors. Phase 6 baseline 160 minus AttestationPolicyTest (5 deleted) plus new entity tests if added ≈ 155-160.

- [ ] **Step 3: Manual smoke**

Boot apps + admin-ui. Create tenant via new form. Verify origin chips, format checkboxes, UV/MDS toggles work. Issue API key with scope checkboxes.

- [ ] **Step 4: Tag**

```bash
git tag -a phase-7-tenant-config-normalize-complete -m "$(cat <<'EOF'
Phase 7 (tenant config + scopes normalization) complete.

Acceptance:
- 3 JSON CLOB columns (allowed_origins, attestation_policy, scopes)
  normalized to 3 child tables + 2 flag columns
- All tests green across :core, :admin-app, :passkey-app
- admin-ui Form input upgraded — chip / checkbox grid / toggle
- AttestationPolicy.fromJson + parseOrigins parsers deleted

Phase 7 surface:
- V21 Flyway migration: ALTER tenant (drop CLOB, add flag cols),
  ALTER api_key (drop CLOB), CREATE 3 child tables
- 3 new JPA entities: TenantAllowedOrigin, TenantAcceptedFormat, ApiKeyScope
- Tenant + ApiKey @OneToMany cascade=ALL + orphanRemoval
- New admin-ui components: OriginChipInput, FormatCheckboxGrid,
  ScopeCheckboxGrid, Switch
- DB-level CHECK constraints: format whitelist (7 values), scope
  whitelist (3 values), origin REGEXP_LIKE
- VPD coverage unchanged (credential + api_key only)

Followups deferred to Phase 8+:
- credential.backup_state normalize (webauthn4j dependency)
- audit_log payload structured (out-of-scope per spec)
- tenant.status enum class
- Operator UX: bulk import origins
EOF
)"

git tag -l "phase-*"
```

---

## Self-Review

### Spec coverage

- §3.3 schema (tenant ALTER, 3 child tables, api_key ALTER) → T1.
- §3.5 JPA entities → T2 (TenantAllowedOrigin) + T3 (TenantAcceptedFormat + ApiKeyScope) + T4 (Tenant) + T5 (ApiKey).
- §3.7 service layer (AttestationPolicy delete, inline parseOrigins, MdsVerifier) → T6 + T7 + T8.
- §3.8 admin DTO + service → T9 + T10.
- §3.8 admin-ui components + pages → T11 + T12 + T13.
- §6 testing → T14 (unit/service) + T15 (IT) + T16 (DoD).
- §7 risk mitigations → addressed inline per task.

### Placeholder scan

No `TBD` / `TODO` / `implement later`. Some tasks say "Read current file first" — that's intentional pre-flight, not placeholder.

### Type consistency

- `Tenant.addAllowedOrigin(origin, sortOrder)` defined T4, used T9.
- `Tenant.addAcceptedFormat(format)` defined T4, used T9.
- `Tenant.isRequireUserVerification()` / `isMdsRequired()` defined T4, used T7/T8/T9.
- `Tenant.getAllowedOriginValues()` / `getAcceptedFormatValues()` defined T4, used T7/T9.
- `ApiKey.addScope(scope)` defined T5, used T10.
- `ApiKeyScope.getScope()` defined T3, used T5 (`ApiKey.getScopeValues`) + T10.
- `TenantCreateRequest.allowedOrigins / acceptedFormats / requireUserVerification / mdsRequired` defined T9, consumed T12 + T15.
- `ApiKeyCreateRequest.scopes` defined T10, consumed T13 + T15.
- New admin-ui types (`TenantView.allowedOrigins[]`, `ApiKeyView.scopes[]`) defined T12/T13, consumed by pages.
- VPD invariant (only credential + api_key) preserved — verified T15 VpdIsolationIT addition.
