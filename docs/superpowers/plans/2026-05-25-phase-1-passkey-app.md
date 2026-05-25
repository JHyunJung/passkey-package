# Phase 1 — `:passkey-app` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Every implementation commit must run through `/codex:review` on the staged diff BEFORE the commit — per project rule (memory/feedback_codex_review_before_commit.md).**

**Goal:** Build the `:passkey-app` Phase 1 surface: FIDO2 ceremony (registration + authentication) over webauthn4j, 4 RP API endpoints protected by X-API-Key auth + Bucket4j rate limiting, ID Token issuance with RS256, and a `/.well-known/jwks.json` endpoint. All proven by a Testcontainers integration test covering 8 scenarios.

**Architecture:** webauthn4j drives the ceremony semantics; we own the HTTP surface, challenge lifecycle (Redis-backed 5-minute TTL), tenant-scoped attestation policy, and ID Token issuance. Every request enters through `RateLimitFilter` → `ApiKeyAuthFilter` (sets `TenantContextHolder`) → controller, which leverages Phase 0's `TenantAwareDataSource` for VPD enforcement.

**Tech Stack:** Java 17, Spring Boot 3.5, webauthn4j (Apache 2.0), Nimbus JOSE+JWT (Spring Security baseline), Bucket4j + Redis, BCrypt (cost 12), Flyway, Testcontainers Oracle XE 21.

**Reference spec:** `docs/superpowers/specs/2026-05-25-phase-1-passkey-app-design.md`

---

## File Structure

This is the full tree of files this plan creates or modifies. Every task targets a subset; this overview locks decomposition.

```
Passkey2/.worktrees/phase-1-passkey-app/
├ gradle/libs.versions.toml                    # MODIFY (webauthn4j, nimbus-jose-jwt, bucket4j versions)
├ core/
│  ├ build.gradle.kts                          # MODIFY (add webauthn4j, nimbus-jose-jwt, bucket4j, bouncycastle, bcrypt deps)
│  └ src/
│     ├ main/
│     │  ├ java/com/crosscert/passkey/core/
│     │  │  ├ entity/
│     │  │  │  ├ Tenant.java                   # MODIFY (add rp_id, rp_name, allowed_origins, attestation_policy)
│     │  │  │  └ ApiKey.java                   # CREATE
│     │  │  ├ repository/
│     │  │  │  └ ApiKeyRepository.java         # CREATE
│     │  │  └ jwt/
│     │  │     ├ SigningKeyProvider.java       # CREATE
│     │  │     └ IdTokenIssuer.java            # CREATE
│     │  └ resources/db/migration/
│     │     ├ V6__tenant_rp_columns.sql        # CREATE
│     │     ├ V7__api_key_table.sql            # CREATE
│     │     └ V8__api_key_vpd_policy.sql       # CREATE
│     └ test/
│        └ java/com/crosscert/passkey/core/jwt/
│           ├ SigningKeyProviderTest.java      # CREATE
│           └ IdTokenIssuerTest.java           # CREATE
└ passkey-app/
   ├ build.gradle.kts                          # MODIFY (webauthn4j, bucket4j-redis deps via core)
   └ src/
      ├ main/
      │  └ java/com/crosscert/passkey/app/
      │     ├ PasskeyApplication.java          # already exists from Phase 0
      │     ├ api/v1/rp/
      │     │  ├ dto/                          # CREATE — request/response records
      │     │  │  ├ RegistrationStartRequest.java
      │     │  │  ├ RegistrationStartResponse.java
      │     │  │  ├ RegistrationFinishRequest.java
      │     │  │  ├ RegistrationFinishResponse.java
      │     │  │  ├ AuthenticationStartRequest.java
      │     │  │  ├ AuthenticationStartResponse.java
      │     │  │  ├ AuthenticationFinishRequest.java
      │     │  │  └ AuthenticationFinishResponse.java
      │     │  ├ RegistrationController.java
      │     │  ├ AuthenticationController.java
      │     │  └ JwksController.java
      │     ├ fido2/
      │     │  ├ challenge/
      │     │  │  ├ ChallengeIssuer.java        # SecureRandom 32 bytes
      │     │  │  ├ ChallengeStore.java          # Redis JSON store
      │     │  │  ├ RegistrationChallenge.java   # record (DTO stored in Redis)
      │     │  │  └ AuthenticationChallenge.java # record
      │     │  ├ policy/
      │     │  │  └ AttestationPolicy.java       # record + tenant JSON parser
      │     │  ├ registration/
      │     │  │  ├ RegistrationStartService.java
      │     │  │  └ RegistrationFinishService.java
      │     │  ├ authentication/
      │     │  │  ├ AuthenticationStartService.java
      │     │  │  └ AuthenticationFinishService.java
      │     │  ├ mds/
      │     │  │  └ MdsStubVerifier.java         # Phase 3 placeholder
      │     │  └ Webauthn4jConfig.java           # WebAuthnManager bean
      │     └ security/
      │        ├ ApiKeyAuthFilter.java
      │        ├ ApiKeyCache.java
      │        └ RateLimitFilter.java
      └ test/
         ├ java/com/crosscert/passkey/app/
         │  ├ security/
         │  │  ├ ApiKeyAuthFilterTest.java
         │  │  └ RateLimitFilterTest.java
         │  ├ fido2/
         │  │  ├ challenge/ChallengeStoreTest.java
         │  │  ├ policy/AttestationPolicyTest.java
         │  │  └ Fido2EndToEndIT.java            # the Phase 1 acceptance test
         │  └ api/v1/rp/JwksControllerTest.java
         └ resources/
            └ application-test.yml              # extends core test config
```

Design principles:
- **Each file has one clear responsibility.** Controllers stay thin; services own the ceremony logic; DTOs are records.
- **Reusable infrastructure (`SigningKeyProvider`, `IdTokenIssuer`, `ApiKey`) lives in `:core`** because admin-app will need to issue/manage API keys in Phase 2 and read JWKS state. Domain logic specific to ceremony stays in `:passkey-app/fido2/`.
- **`:passkey-app/security` is the request entry point.** Filters are ordered: RateLimit → ApiKeyAuth → DevTenantHeaderFilter (legacy, dev-only).

---

## Task 1: Dependency additions (libs.versions.toml + build.gradle.kts)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `core/build.gradle.kts`

- [ ] **Step 1: Add versions to libs.versions.toml**

Add to `[versions]`:

```toml
webauthn4j = "0.29.0.RELEASE"
nimbus-jose-jwt = "9.40"
bucket4j = "8.10.1"
```

Add to `[libraries]`:

```toml
webauthn4j-core = { module = "com.webauthn4j:webauthn4j-core", version.ref = "webauthn4j" }
webauthn4j-test = { module = "com.webauthn4j:webauthn4j-test", version.ref = "webauthn4j" }
webauthn4j-metadata = { module = "com.webauthn4j:webauthn4j-metadata", version.ref = "webauthn4j" }
nimbus-jose-jwt = { module = "com.nimbusds:nimbus-jose-jwt", version.ref = "nimbus-jose-jwt" }
bucket4j-core = { module = "com.bucket4j:bucket4j_jdk17-core", version.ref = "bucket4j" }
bucket4j-redis = { module = "com.bucket4j:bucket4j_jdk17-redis-lettuce", version.ref = "bucket4j" }
spring-security-crypto = { module = "org.springframework.security:spring-security-crypto" }
```

`spring-security-crypto` has no version because Spring Boot BOM manages it.

- [ ] **Step 2: Modify core/build.gradle.kts to add new dependencies**

Add to the `dependencies { }` block after the existing `implementation("org.apache.commons:commons-pool2")`:

```kotlin
    api(rootProject.libs.webauthn4j.core)
    api(rootProject.libs.webauthn4j.metadata)
    api(rootProject.libs.nimbus.jose.jwt)
    api(rootProject.libs.spring.security.crypto)
    api(rootProject.libs.bucket4j.core)
    api(rootProject.libs.bucket4j.redis)

    testImplementation(rootProject.libs.webauthn4j.test)
```

`api` (not `implementation`) so both `:passkey-app` and `:admin-app` see the types — admin-app needs `ApiKey` entity, `SigningKeyProvider` interface, etc. in Phase 2.

- [ ] **Step 3: Verify build resolves**

Run:
```bash
./gradlew :core:dependencies --configuration runtimeClasspath 2>&1 | grep -E "webauthn4j|nimbus|bucket4j|spring-security-crypto" | head -10
```

Expected: each dependency resolves (no "FAILED" lines).

Run:
```bash
./gradlew :core:compileJava 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Stage**

```bash
git add gradle/libs.versions.toml core/build.gradle.kts
git status --short
```

**Do NOT commit.** Controller runs `/codex:review` on the staged diff.

---

## Task 2: Flyway V6 — tenant RP columns

**Files:**
- Create: `core/src/main/resources/db/migration/V6__tenant_rp_columns.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Add per-tenant WebAuthn RP configuration columns to the existing
-- tenant table. Phase 0 created tenant with just id/display_name/
-- status/created_at/updated_at; Phase 1 needs each tenant to declare
-- its WebAuthn RP identity (rp_id, rp_name), its allowed origins
-- whitelist, and an attestation policy JSON.
--
-- Columns are nullable so this migration is safe to apply to a
-- populated tenant table (Phase 0 test tenants). Backfill with
-- safe defaults in the same migration.

ALTER TABLE tenant ADD (
  rp_id              VARCHAR2(256),
  rp_name            VARCHAR2(256),
  allowed_origins    CLOB,
  attestation_policy CLOB
);

ALTER TABLE tenant ADD CONSTRAINT ck_tenant_origins_json
  CHECK (allowed_origins IS NULL OR allowed_origins IS JSON);
ALTER TABLE tenant ADD CONSTRAINT ck_tenant_attest_policy_json
  CHECK (attestation_policy IS NULL OR attestation_policy IS JSON);

-- Backfill: any pre-existing tenant rows get safe local-dev defaults.
-- New tenant inserts (admin-app, Phase 2) are responsible for setting
-- explicit values.
UPDATE tenant
SET rp_id = 'localhost',
    rp_name = display_name,
    allowed_origins = '["http://localhost:8080","http://localhost:8082"]',
    attestation_policy =
      '{"acceptedFormats":["none","packed","android-key","android-safetynet","fido-u2f","apple","tpm"],"requireUserVerification":true,"mdsRequired":false}'
WHERE rp_id IS NULL;

-- After backfill, make rp_id/rp_name/allowed_origins NOT NULL so
-- future inserts must populate them. attestation_policy stays
-- nullable so the application can fall back to a hardcoded default
-- when a tenant has no policy set.
ALTER TABLE tenant MODIFY (
  rp_id              NOT NULL,
  rp_name            NOT NULL,
  allowed_origins    NOT NULL
);
```

- [ ] **Step 2: Stage**

```bash
git add core/src/main/resources/db/migration/V6__tenant_rp_columns.sql
git status --short
```

**Do NOT commit.** Verification happens once V7+V8 land and admin-app actually applies them in Task 25.

---

## Task 3: Flyway V7 — api_key table

**Files:**
- Create: `core/src/main/resources/db/migration/V7__api_key_table.sql`

**Architectural note:** the inbound X-API-Key header has no tenant id; the auth filter must look up the row by `key_prefix` without yet knowing the tenant. With VPD active, a direct `SELECT` would return zero rows. To resolve this without leaking tenant data, V7 makes `key_prefix` **globally unique** (not per-tenant), and V8 adds a definer-rights PL/SQL package that bypasses VPD safely. APP_RUNTIME's `UPDATE` privilege is column-scoped to `last_used_at` only — preventing a hijacked runtime path from rotating `key_hash` or undoing revocation on a same-tenant row.

- [ ] **Step 1: Write the migration**

```sql
-- API Key table. Tenant-scoped via tenant_id FK; VPD policy on
-- this table is attached in V8.
--
-- Storage model (Phase 1 spec §6.2):
--   - key_prefix is the first 11 chars of the full key ("pk_" + 8B
--     base64url), GLOBALLY unique so a prefix-only lookup is
--     deterministic without tenant context.
--   - key_hash stores BCrypt hash of the secret portion.
--   - scopes is a JSON array (Phase 1 stores but does not enforce).

CREATE SEQUENCE api_key_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE api_key (
  id              NUMBER(19,0)             NOT NULL,
  tenant_id       VARCHAR2(64)             NOT NULL,
  key_prefix      VARCHAR2(16)             NOT NULL,
  key_hash        VARCHAR2(255)            NOT NULL,
  name            VARCHAR2(256)            NOT NULL,
  scopes          CLOB                     NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  last_used_at    TIMESTAMP WITH TIME ZONE,
  expires_at      TIMESTAMP WITH TIME ZONE,
  revoked_at      TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_api_key PRIMARY KEY (id),
  CONSTRAINT uq_api_key_prefix UNIQUE (key_prefix),
  CONSTRAINT fk_api_key_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
  CONSTRAINT ck_api_key_scopes_json CHECK (scopes IS JSON)
);

-- Grants. APP_RUNTIME has SELECT (VPD-filtered) + column-scoped
-- UPDATE on last_used_at only. INSERT/DELETE and broader UPDATE
-- are admin-app jobs.
GRANT SELECT ON api_key TO APP_RUNTIME;
GRANT UPDATE(last_used_at) ON api_key TO APP_RUNTIME;
GRANT SELECT ON api_key_seq TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON api_key TO APP_ADMIN;
GRANT SELECT ON api_key_seq TO APP_ADMIN;
```

- [ ] **Step 2: Stage**

```bash
git add core/src/main/resources/db/migration/V7__api_key_table.sql
git status --short
```

---

## Task 4: Flyway V8 — VPD policy on api_key + definer-rights lookup package

**Files:**
- Create: `core/src/main/resources/db/migration/V8__api_key_vpd_policy.sql`

V8 does two things:

1. Attach the standard VPD policy `API_KEY_TENANT_ISOLATION` to api_key, mirroring V3's credential policy. Normal queries are tenant-isolated; cross-tenant writes are rejected with ORA-28115.

2. Add a definer-rights PL/SQL package `APP_OWNER.api_key_lookup_pkg` that solves the chicken-and-egg problem: the auth filter needs to look up an api_key row by `key_prefix` BEFORE TenantContextHolder is set. The package runs as `APP_OWNER` (which gets `EXEMPT ACCESS POLICY` here), so its `SELECT` bypasses VPD. The function returns `(found, id, tenant_id, key_hash, expires_at, revoked_at)` via OUT params — JDBC `CallableStatement` binds cleanly.

`touch_last_used` in the package explicitly constrains its `UPDATE` with `WHERE id = p_id AND tenant_id = SYS_CONTEXT('APP_CTX','TENANT_ID')` — even though APP_OWNER's `EXEMPT ACCESS POLICY` would technically let it touch any row, this layered guard means a hijacked package call cannot silently update another tenant's key.

- [ ] **Step 1: Write the migration**

See the committed file `core/src/main/resources/db/migration/V8__api_key_vpd_policy.sql` for the verbatim content. The structure is:

```sql
-- 1. Standard VPD policy via DBMS_RLS.ADD_POLICY (same shape as V3
--    credential policy, on API_KEY).
-- 2. GRANT EXEMPT ACCESS POLICY TO APP_OWNER so the next step's
--    package can bypass VPD inside its body.
-- 3. CREATE OR REPLACE PACKAGE APP_OWNER.api_key_lookup_pkg with
--    AUTHID DEFINER (Oracle default for packages). Spec:
--      PROCEDURE find_by_prefix(p_prefix, OUT p_found, OUT p_id,
--                               OUT p_tenant_id, OUT p_key_hash,
--                               OUT p_expires_at, OUT p_revoked_at);
--      PROCEDURE touch_last_used(p_id, p_now);
-- 4. CREATE OR REPLACE PACKAGE BODY: find_by_prefix SELECTs WHERE
--    key_prefix = p_prefix, catches NO_DATA_FOUND, sets p_found=0.
--    touch_last_used UPDATEs WHERE id=p_id AND tenant_id matches
--    SYS_CONTEXT (defense-in-depth).
-- 5. GRANT EXECUTE on the package to APP_RUNTIME and APP_ADMIN.
```

- [ ] **Step 2: Stage**

```bash
git add core/src/main/resources/db/migration/V8__api_key_vpd_policy.sql
git status --short
```

---

## Task 5: Tenant entity extension

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java`

- [ ] **Step 1: Read current Tenant.java to confirm baseline**

```bash
cat core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java
```

Expected: the Phase 0 minimal entity (id/displayName/status/createdAt/updatedAt).

- [ ] **Step 2: Replace with Phase 1 expanded version**

Replace the entire file with:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "TENANT")
public class Tenant {

    @Id
    @Column(name = "ID", length = 64, nullable = false)
    private String id;

    @Column(name = "DISPLAY_NAME", length = 256, nullable = false)
    private String displayName;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;

    @Column(name = "RP_ID", length = 256, nullable = false)
    private String rpId;

    @Column(name = "RP_NAME", length = 256, nullable = false)
    private String rpName;

    @Lob
    @Column(name = "ALLOWED_ORIGINS", nullable = false)
    private String allowedOriginsJson;

    @Lob
    @Column(name = "ATTESTATION_POLICY")
    private String attestationPolicyJson;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    protected Tenant() {}

    public Tenant(String id, String displayName, String rpId, String rpName,
                  String allowedOriginsJson, String attestationPolicyJson) {
        this.id = id;
        this.displayName = displayName;
        this.status = "active";
        this.rpId = rpId;
        this.rpName = rpName;
        this.allowedOriginsJson = allowedOriginsJson;
        this.attestationPolicyJson = attestationPolicyJson;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getStatus() { return status; }
    public String getRpId() { return rpId; }
    public String getRpName() { return rpName; }
    public String getAllowedOriginsJson() { return allowedOriginsJson; }
    public String getAttestationPolicyJson() { return attestationPolicyJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 3: Verify compile**

```bash
./gradlew :core:compileJava 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Stage**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java
```

---

## Task 6: ApiKey entity

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java`

- [ ] **Step 1: Write the entity**

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "API_KEY")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "api_key_seq")
    @SequenceGenerator(name = "api_key_seq", sequenceName = "API_KEY_SEQ", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "TENANT_ID", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "KEY_PREFIX", length = 16, nullable = false)
    private String keyPrefix;

    @Column(name = "KEY_HASH", length = 255, nullable = false)
    private String keyHash;

    @Column(name = "NAME", length = 256, nullable = false)
    private String name;

    @Lob
    @Column(name = "SCOPES", nullable = false)
    private String scopesJson;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "LAST_USED_AT")
    private Instant lastUsedAt;

    @Column(name = "EXPIRES_AT")
    private Instant expiresAt;

    @Column(name = "REVOKED_AT")
    private Instant revokedAt;

    protected ApiKey() {}

    public ApiKey(String tenantId, String keyPrefix, String keyHash,
                  String name, String scopesJson) {
        this.tenantId = tenantId;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.name = name;
        this.scopesJson = scopesJson;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getKeyHash() { return keyHash; }
    public String getName() { return name; }
    public String getScopesJson() { return scopesJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }

    /** Active = not expired AND not revoked. Used by ApiKeyAuthFilter. */
    public boolean isActive(Instant now) {
        if (revokedAt != null) return false;
        if (expiresAt != null && !expiresAt.isAfter(now)) return false;
        return true;
    }

    /** Called after a successful authentication. Caller saves the entity. */
    public void touchLastUsed(Instant now) {
        this.lastUsedAt = now;
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :core:compileJava 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Stage**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java
```

---

## Task 7: ApiKeyRepository

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyRepository.java`

- [ ] **Step 1: Write the repository**

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /**
     * Look up an API key by its prefix. VPD filters by tenant_id =
     * SYS_CONTEXT, so this call only returns a row when the calling
     * session has already set TenantContextHolder to the same tenant
     * that owns the key. Useful from admin endpoints (Phase 2) and
     * test fixtures (where APP_ADMIN bypasses VPD).
     *
     * <p>Phase 1 auth filter does NOT use this — auth runs before
     * tenant context exists. See {@code ApiKeyLookupService} (T16)
     * which calls a definer-rights PL/SQL package
     * ({@code APP_OWNER.api_key_lookup_pkg.find_by_prefix}) that
     * bypasses VPD safely.
     */
    Optional<ApiKey> findByKeyPrefix(String keyPrefix);
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :core:compileJava 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Stage**

```bash
git add core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyRepository.java
```

---

## Task 8: SigningKeyProvider — TDD

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/jwt/SigningKeyProviderTest.java`

- [ ] **Step 1: Write the failing test FIRST**

```java
package com.crosscert.passkey.core.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

import java.text.ParseException;

import static org.assertj.core.api.Assertions.assertThat;

class SigningKeyProviderTest {

    private final SigningKeyProvider provider = new SigningKeyProvider();

    @Test
    void initGeneratesRsa2048KeyPairWithKid() throws Exception {
        provider.init();
        RSAKey key = provider.signingKey();
        assertThat(key.getKeyID()).isNotBlank();
        assertThat(key.size()).isEqualTo(2048);
        assertThat(key.isPrivate()).isTrue();
    }

    @Test
    void publicJwkSetOmitsPrivateMaterial() throws Exception {
        provider.init();
        JWKSet publicSet = provider.publicJwkSet();
        assertThat(publicSet.getKeys()).hasSize(1);
        // publicJwkSet must contain only the public key — no private exponent.
        assertThat(publicSet.getKeys().get(0).isPrivate()).isFalse();
    }

    @Test
    void kidIsDeterministicAcrossReadsButChangesBetweenInits() throws Exception {
        provider.init();
        String kid1 = provider.signingKey().getKeyID();
        String kidAgain = provider.signingKey().getKeyID();
        assertThat(kidAgain).isEqualTo(kid1);

        SigningKeyProvider other = new SigningKeyProvider();
        other.init();
        // Different RSA keypair → different SHA-256 thumbprint → different kid.
        assertThat(other.signingKey().getKeyID()).isNotEqualTo(kid1);
    }
}
```

- [ ] **Step 2: Run, confirm compile failure**

```bash
./gradlew :core:test --tests SigningKeyProviderTest 2>&1 | tail -10
```

Expected: compilation failure — `SigningKeyProvider` cannot be resolved.

- [ ] **Step 3: Write minimal implementation**

```java
package com.crosscert.passkey.core.jwt;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Phase 1 ID Token signing key. Generates a fresh RSA-2048 keypair on
 * application boot and keeps it in memory. The kid is the RFC 7638
 * thumbprint of the public key, so callers can verify which key
 * signed a given JWT against the JWKS endpoint.
 *
 * <p>Re-generated on every restart — fine for Phase 1's stated scope
 * (issued JWTs become unverifiable after a server bounce). Permanent
 * storage and rotation are Phase 2/3 work.
 */
@Component
public class SigningKeyProvider {

    private volatile RSAKey signingKey;

    @PostConstruct
    public void init() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();

            RSAKey withoutKid = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .build();
            // RFC 7638 thumbprint is deterministic for the public key.
            String kid = withoutKid.computeThumbprint().toString();

            this.signingKey = new RSAKey.Builder(withoutKid)
                    .keyID(kid)
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA not available", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute key thumbprint", e);
        }
    }

    /** Full keypair — caller signs with this. */
    public RSAKey signingKey() {
        return signingKey;
    }

    /** Public-only JWK set. Safe to return from /.well-known/jwks.json. */
    public JWKSet publicJwkSet() {
        return new JWKSet(signingKey.toPublicJWK());
    }
}
```

- [ ] **Step 4: Run tests, confirm pass**

```bash
./gradlew :core:test --tests SigningKeyProviderTest 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Stage**

```bash
git add core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java \
        core/src/test/java/com/crosscert/passkey/core/jwt/SigningKeyProviderTest.java
```

---

## Task 9: IdTokenIssuer — TDD

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/jwt/IdTokenIssuer.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/jwt/IdTokenIssuerTest.java`

- [ ] **Step 1: Write the failing test FIRST**

```java
package com.crosscert.passkey.core.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class IdTokenIssuerTest {

    private SigningKeyProvider signingKeys;
    private IdTokenIssuer issuer;
    private final Instant fixedNow = Instant.parse("2026-05-25T08:00:00Z");

    @BeforeEach
    void setUp() {
        signingKeys = new SigningKeyProvider();
        signingKeys.init();
        Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        issuer = new IdTokenIssuer(
                signingKeys,
                "https://passkey.example.test",
                Duration.ofMinutes(15),
                clock);
    }

    @Test
    void issuesSignedJwtWithExpectedClaims() throws Exception {
        byte[] userHandle = new byte[]{1, 2, 3, 4};
        byte[] aaguid = new byte[]{(byte)0xab, (byte)0xcd};
        String jwt = issuer.issue("T_A", userHandle, 42L, aaguid);

        SignedJWT parsed = SignedJWT.parse(jwt);
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(parsed.getHeader().getKeyID()).isEqualTo(signingKeys.signingKey().getKeyID());

        assertThat(parsed.getJWTClaimsSet().getIssuer())
                .isEqualTo("https://passkey.example.test/T_A");
        assertThat(parsed.getJWTClaimsSet().getSubject())
                .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle));
        assertThat(parsed.getJWTClaimsSet().getAudience()).containsExactly("T_A");
        assertThat(parsed.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(fixedNow);
        assertThat(parsed.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(fixedNow.plus(Duration.ofMinutes(15)));
        assertThat(parsed.getJWTClaimsSet().getClaim("amr"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("webauthn");
        assertThat(parsed.getJWTClaimsSet().getClaim("aaguid")).isEqualTo("abcd");
    }

    @Test
    void aaguidClaimIsOmittedWhenNull() throws Exception {
        String jwt = issuer.issue("T_A", new byte[]{0}, 1L, null);
        SignedJWT parsed = SignedJWT.parse(jwt);
        assertThat(parsed.getJWTClaimsSet().getClaim("aaguid")).isNull();
    }

    @Test
    void signatureVerifiesAgainstPublicJwk() throws Exception {
        String jwt = issuer.issue("T_A", new byte[]{0}, 1L, null);
        SignedJWT parsed = SignedJWT.parse(jwt);
        JWSVerifier verifier = new RSASSAVerifier(signingKeys.signingKey().toPublicJWK());
        assertThat(parsed.verify(verifier)).isTrue();
    }
}
```

- [ ] **Step 2: Run, confirm compile failure**

```bash
./gradlew :core:test --tests IdTokenIssuerTest 2>&1 | tail -10
```

Expected: compile failure — `IdTokenIssuer` not found.

- [ ] **Step 3: Write the implementation**

```java
package com.crosscert.passkey.core.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

/**
 * Issues RS256 ID Tokens against the in-memory key from
 * {@link SigningKeyProvider}. Claim layout matches Phase 1 spec §5.
 */
@Component
public class IdTokenIssuer {

    private final SigningKeyProvider signingKeys;
    private final String issuerBase;
    private final Duration tokenTtl;
    private final Clock clock;

    public IdTokenIssuer(SigningKeyProvider signingKeys,
                         @Value("${passkey.id-token.issuer-base:https://passkey.crosscert.com}")
                         String issuerBase,
                         @Value("${passkey.id-token.ttl:PT15M}")
                         Duration tokenTtl,
                         Clock clock) {
        this.signingKeys = signingKeys;
        this.issuerBase = issuerBase;
        this.tokenTtl = tokenTtl;
        this.clock = clock;
    }

    /**
     * @param tenantId the tenant the API Key belongs to (audience).
     * @param userHandle the WebAuthn user.id bytes (becomes sub claim,
     *                   base64url-no-pad).
     * @param credentialId the internal credential.id PK.
     * @param aaguid the authenticator's AAGUID, or null if unknown.
     */
    public String issue(String tenantId, byte[] userHandle, Long credentialId, byte[] aaguid) {
        Instant now = clock.instant();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuerBase + "/" + tenantId)
                .subject(Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle))
                .audience(tenantId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(tokenTtl)))
                .claim("amr", List.of("webauthn"))
                .claim("cred_id",
                        Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(longToBytes(credentialId)));
        if (aaguid != null) {
            claims.claim("aaguid", HexFormat.of().formatHex(aaguid));
        }

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKeys.signingKey().getKeyID())
                .build();
        SignedJWT jwt = new SignedJWT(header, claims.build());
        try {
            jwt.sign(new RSASSASigner(signingKeys.signingKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign ID Token", e);
        }
        return jwt.serialize();
    }

    private static byte[] longToBytes(long v) {
        return new byte[]{
                (byte)(v >>> 56), (byte)(v >>> 48), (byte)(v >>> 40), (byte)(v >>> 32),
                (byte)(v >>> 24), (byte)(v >>> 16), (byte)(v >>> 8),  (byte) v
        };
    }
}
```

- [ ] **Step 4: Add a Clock bean to :core config so IdTokenIssuer can autowire**

Add to a new file `core/src/main/java/com/crosscert/passkey/core/config/CoreClockConfig.java`:

```java
package com.crosscert.passkey.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CoreClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 5: Run tests, confirm pass**

```bash
./gradlew :core:test --tests IdTokenIssuerTest 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 6: Stage**

```bash
git add core/src/main/java/com/crosscert/passkey/core/jwt/IdTokenIssuer.java \
        core/src/main/java/com/crosscert/passkey/core/config/CoreClockConfig.java \
        core/src/test/java/com/crosscert/passkey/core/jwt/IdTokenIssuerTest.java
```

---

## Task 10: AttestationPolicy record + parser — TDD

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/policy/AttestationPolicy.java`
- Test: `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/policy/AttestationPolicyTest.java`

- [ ] **Step 1: Add testImplementation Jackson if missing (it isn't; verified via spring-boot-starter-web)**

Skip — already on classpath.

- [ ] **Step 2: Write the failing test**

```java
package com.crosscert.passkey.app.fido2.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttestationPolicyTest {

    @Test
    void parsesAllFieldsFromJson() {
        String json = """
            {
              "acceptedFormats": ["none","packed","apple"],
              "requireUserVerification": true,
              "mdsRequired": false
            }
            """;
        AttestationPolicy p = AttestationPolicy.fromJson(json);
        assertThat(p.acceptedFormats()).containsExactlyInAnyOrder("none","packed","apple");
        assertThat(p.requireUserVerification()).isTrue();
        assertThat(p.mdsRequired()).isFalse();
    }

    @Test
    void nullJsonReturnsConservativeDefault() {
        AttestationPolicy p = AttestationPolicy.fromJson(null);
        assertThat(p.acceptedFormats()).contains("none","packed");
        assertThat(p.requireUserVerification()).isTrue();
        assertThat(p.mdsRequired()).isFalse();
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> AttestationPolicy.fromJson("{not valid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attestation_policy JSON");
    }
}
```

- [ ] **Step 3: Run, confirm compile failure**

```bash
./gradlew :passkey-app:test --tests AttestationPolicyTest 2>&1 | tail -5
```

Expected: compile failure.

- [ ] **Step 4: Write the implementation**

```java
package com.crosscert.passkey.app.fido2.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

/**
 * Tenant-scoped attestation policy parsed from {@code tenant.attestation_policy}.
 * The JSON shape is documented in Phase 1 spec §6.1.
 */
public record AttestationPolicy(
        Set<String> acceptedFormats,
        boolean requireUserVerification,
        boolean mdsRequired
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SAFE_DEFAULT_FORMATS = Set.of(
            "none","packed","android-key","android-safetynet","fido-u2f","apple","tpm");

    public static AttestationPolicy fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new AttestationPolicy(SAFE_DEFAULT_FORMATS, true, false);
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            Set<String> formats = new HashSet<>();
            JsonNode fmtArray = root.path("acceptedFormats");
            if (fmtArray.isArray()) {
                for (JsonNode n : fmtArray) formats.add(n.asText());
            }
            if (formats.isEmpty()) formats.addAll(SAFE_DEFAULT_FORMATS);
            boolean uv = root.path("requireUserVerification").asBoolean(true);
            boolean mds = root.path("mdsRequired").asBoolean(false);
            return new AttestationPolicy(formats, uv, mds);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "attestation_policy JSON could not be parsed: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: Run tests, confirm pass**

```bash
./gradlew :passkey-app:test --tests AttestationPolicyTest 2>&1 | tail -5
```

Expected: 3 tests pass.

- [ ] **Step 6: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/policy/AttestationPolicy.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/fido2/policy/AttestationPolicyTest.java
```

---

## Task 11: ChallengeIssuer (SecureRandom)

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/challenge/ChallengeIssuer.java`

- [ ] **Step 1: Write the implementation**

```java
package com.crosscert.passkey.app.fido2.challenge;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates cryptographically-strong 32-byte challenges (WebAuthn
 * minimum is 16; we use 32 to match Stripe/Linear/GitHub patterns).
 * Output is base64url-no-pad — directly embeddable in a JSON response.
 */
@Component
public class ChallengeIssuer {

    private static final int CHALLENGE_BYTES = 32;
    private final SecureRandom rng = new SecureRandom();

    public byte[] newChallengeBytes() {
        byte[] buf = new byte[CHALLENGE_BYTES];
        rng.nextBytes(buf);
        return buf;
    }

    public String newToken() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(newChallengeBytes());
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :passkey-app:compileJava 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/challenge/ChallengeIssuer.java
```

---

## Task 12: ChallengeStore + records — TDD

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/challenge/RegistrationChallenge.java`
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/challenge/AuthenticationChallenge.java`
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/challenge/ChallengeStore.java`
- Test: `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/challenge/ChallengeStoreTest.java`

- [ ] **Step 1: Write the challenge records**

`RegistrationChallenge.java`:

```java
package com.crosscert.passkey.app.fido2.challenge;

import java.time.Instant;

public record RegistrationChallenge(
        String tenantId,
        byte[] challenge,
        byte[] userHandle,
        String displayName,
        String username,
        Instant issuedAt
) {}
```

`AuthenticationChallenge.java`:

```java
package com.crosscert.passkey.app.fido2.challenge;

import java.time.Instant;

public record AuthenticationChallenge(
        String tenantId,
        byte[] challenge,
        byte[] userHandle, // nullable for usernameless flows
        Instant issuedAt
) {}
```

- [ ] **Step 2: Write the failing test FIRST**

```java
package com.crosscert.passkey.app.fido2.challenge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChallengeStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ChallengeStore store;

    @BeforeEach
    void setup() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        store = new ChallengeStore(redis, new ObjectMapper());
    }

    @Test
    void putRegistrationStoresJsonWithTtl() {
        RegistrationChallenge rc = new RegistrationChallenge(
                "T_A", new byte[]{1,2,3}, new byte[]{4,5}, "Alice", "alice@example",
                Instant.parse("2026-05-25T08:00:00Z"));
        store.putRegistration("tok_x", rc);
        verify(ops).set(eq("challenge:reg:tok_x"), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void takeRegistrationReturnsAndDeletes() {
        String json = """
            {"tenantId":"T_A","challenge":"AQID","userHandle":"BAU=",
             "displayName":"Alice","username":"alice@example",
             "issuedAt":"2026-05-25T08:00:00Z"}
            """;
        when(ops.get("challenge:reg:tok_x")).thenReturn(json);
        when(redis.delete("challenge:reg:tok_x")).thenReturn(true);

        RegistrationChallenge taken = store.takeRegistration("tok_x").orElseThrow();
        assertThat(taken.tenantId()).isEqualTo("T_A");
        assertThat(taken.username()).isEqualTo("alice@example");
        verify(redis).delete("challenge:reg:tok_x");
    }

    @Test
    void takeMissingReturnsEmpty() {
        when(ops.get(anyString())).thenReturn(null);
        assertThat(store.takeRegistration("missing")).isEmpty();
    }
}
```

- [ ] **Step 3: Confirm compile failure**

```bash
./gradlew :passkey-app:test --tests ChallengeStoreTest 2>&1 | tail -10
```

Expected: `ChallengeStore` not found.

- [ ] **Step 4: Write ChallengeStore**

```java
package com.crosscert.passkey.app.fido2.challenge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed one-shot store for registration and authentication
 * challenges. Keys are namespaced by ceremony type so the same opaque
 * token cannot be mis-used between flows. TTL is 5 minutes (WebAuthn
 * spec recommendation).
 */
@Component
public class ChallengeStore {

    static final String REG_PREFIX = "challenge:reg:";
    static final String AUTH_PREFIX = "challenge:auth:";
    static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ChallengeStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void putRegistration(String token, RegistrationChallenge value) {
        redis.opsForValue().set(REG_PREFIX + token, serialize(value), TTL);
    }

    public Optional<RegistrationChallenge> takeRegistration(String token) {
        String json = redis.opsForValue().get(REG_PREFIX + token);
        if (json == null) return Optional.empty();
        redis.delete(REG_PREFIX + token); // one-shot semantics
        return Optional.of(deserialize(json, RegistrationChallenge.class));
    }

    public void putAuthentication(String token, AuthenticationChallenge value) {
        redis.opsForValue().set(AUTH_PREFIX + token, serialize(value), TTL);
    }

    public Optional<AuthenticationChallenge> takeAuthentication(String token) {
        String json = redis.opsForValue().get(AUTH_PREFIX + token);
        if (json == null) return Optional.empty();
        redis.delete(AUTH_PREFIX + token);
        return Optional.of(deserialize(json, AuthenticationChallenge.class));
    }

    private String serialize(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("ChallengeStore JSON serialize failed", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("ChallengeStore JSON deserialize failed", e);
        }
    }
}
```

- [ ] **Step 5: Run tests, confirm pass**

```bash
./gradlew :passkey-app:test --tests ChallengeStoreTest 2>&1 | tail -5
```

Expected: 3 tests pass.

- [ ] **Step 6: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/challenge/
git add passkey-app/src/test/java/com/crosscert/passkey/app/fido2/challenge/ChallengeStoreTest.java
```

---

## Task 13: Webauthn4jConfig — WebAuthnManager bean

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/Webauthn4jConfig.java`

- [ ] **Step 1: Write the configuration**

```java
package com.crosscert.passkey.app.fido2;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.metadata.WebAuthnMetadataManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * webauthn4j configuration. Phase 1 uses the non-strict manager so we
 * can decide attestation acceptance per-tenant from our own
 * {@link com.crosscert.passkey.app.fido2.policy.AttestationPolicy}.
 *
 * <p>MDS metadata source is left empty: {@code MdsStubVerifier} stands
 * in for Phase 1, and Phase 3 will replace this with a real
 * metadata service backed by {@code mds_blob_cache}.
 */
@Configuration
public class Webauthn4jConfig {

    @Bean
    public WebAuthnManager webAuthnManager() {
        // createNonStrictWebAuthnManager() accepts all attestation
        // formats by default; per-tenant policy enforcement happens in
        // RegistrationFinishService.
        return WebAuthnManager.createNonStrictWebAuthnManager();
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :passkey-app:compileJava 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/Webauthn4jConfig.java
```

---

## Task 14: MdsStubVerifier (Phase 3 placeholder)

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsStubVerifier.java`

- [ ] **Step 1: Write the stub**

```java
package com.crosscert.passkey.app.fido2.mds;

import com.crosscert.passkey.app.fido2.policy.AttestationPolicy;
import org.springframework.stereotype.Component;

/**
 * Phase 1 stub for MDS-backed authenticator metadata verification.
 * Always returns {@code true} when {@code policy.mdsRequired() == false}
 * (Phase 1 default), throws {@link UnsupportedOperationException} when
 * a tenant explicitly opts in.
 *
 * <p>Phase 3 replaces this with a real implementation reading
 * {@code mds_blob_cache} populated by the admin scheduler.
 */
@Component
public class MdsStubVerifier {

    public boolean verify(AttestationPolicy policy, byte[] aaguid) {
        if (!policy.mdsRequired()) return true;
        throw new UnsupportedOperationException(
                "mdsRequired=true but MDS integration lands in Phase 3");
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :passkey-app:compileJava 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsStubVerifier.java
```

---

## Task 15: DTOs for RP API (records)

**Files:**
- Create: 8 record files under `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/dto/`

- [ ] **Step 1: Create all 8 DTO records**

`RegistrationStartRequest.java`:

```java
package com.crosscert.passkey.app.api.v1.rp.dto;

import jakarta.validation.constraints.NotBlank;

public record RegistrationStartRequest(
        @NotBlank String userHandle,    // base64url
        @NotBlank String displayName,
        @NotBlank String username
) {}
```

`RegistrationStartResponse.java`:

```java
package com.crosscert.passkey.app.api.v1.rp.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record RegistrationStartResponse(
        String registrationToken,
        JsonNode publicKeyCredentialCreationOptions
) {}
```

`RegistrationFinishRequest.java`:

```java
package com.crosscert.passkey.app.api.v1.rp.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegistrationFinishRequest(
        @NotBlank String registrationToken,
        @NotNull JsonNode publicKeyCredential
) {}
```

`RegistrationFinishResponse.java`:

```java
package com.crosscert.passkey.app.api.v1.rp.dto;

import java.time.Instant;

public record RegistrationFinishResponse(
        String credentialId,         // base64url
        String aaguid,               // hex, may be null
        String attestationFormat,
        Instant createdAt
) {}
```

`AuthenticationStartRequest.java`:

```java
package com.crosscert.passkey.app.api.v1.rp.dto;

public record AuthenticationStartRequest(
        String userHandle            // optional — null for usernameless flow
) {}
```

`AuthenticationStartResponse.java`:

```java
package com.crosscert.passkey.app.api.v1.rp.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record AuthenticationStartResponse(
        String authenticationToken,
        JsonNode publicKeyCredentialRequestOptions
) {}
```

`AuthenticationFinishRequest.java`:

```java
package com.crosscert.passkey.app.api.v1.rp.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AuthenticationFinishRequest(
        @NotBlank String authenticationToken,
        @NotNull JsonNode publicKeyCredential
) {}
```

`AuthenticationFinishResponse.java`:

```java
package com.crosscert.passkey.app.api.v1.rp.dto;

public record AuthenticationFinishResponse(
        String idToken,
        String tokenType,            // always "Bearer"
        long expiresIn               // seconds
) {}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :passkey-app:compileJava 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/dto/
```

---

## Task 16: ApiKeyAuthFilter + ApiKeyLookupService — TDD

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyLookupService.java`
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java`
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyCache.java`
- Test: `passkey-app/src/test/java/com/crosscert/passkey/app/security/ApiKeyLookupServiceIT.java` (Testcontainers — exercises the real V8 PL/SQL package)
- Test: `passkey-app/src/test/java/com/crosscert/passkey/app/security/ApiKeyAuthFilterTest.java` (Mockito — exercises filter logic)

**Architectural note (Codex review caught this — chicken-and-egg):**
The inbound `X-API-Key` header carries no tenant id. The auth filter looks up the key by its prefix to discover the tenant. But VPD on `api_key` filters by `tenant_id = SYS_CONTEXT(...)`, which is unset at the start of the request. A plain `ApiKeyRepository.findByKeyPrefix(...)` would return zero rows.

Phase 1 fix (V7 + V8): `key_prefix` is GLOBALLY unique, and V8 defines a definer-rights PL/SQL package `APP_OWNER.api_key_lookup_pkg.find_by_prefix(...)` that bypasses VPD safely (`APP_OWNER` has `EXEMPT ACCESS POLICY`, granted in V8). The filter invokes that package via JdbcTemplate against the SAME `TenantAwareDataSource` — the wrapper's `set_tenant` is a no-op when TenantContextHolder is null, so the call sees `APP_CTX` unset and the package's APP_OWNER context bypasses the policy.

After lookup and BCrypt verify, the filter calls `TenantContextHolder.set(tenantId)` and the chain proceeds with VPD active for the resolved tenant.

- [ ] **Step 1: Write ApiKeyCache (simple in-memory; Redis cache deferred to followups)**

```java
package com.crosscert.passkey.app.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny in-memory cache for verified API-key lookups, keyed by prefix.
 * Avoids running BCrypt on every authenticated request. Records expire
 * after 30 seconds; absence is also cached briefly to dampen invalid-
 * key brute force without leaking timing info.
 *
 * <p>A future iteration will move this to Redis (shared across instances);
 * Phase 1 in-memory is acceptable because Phase 0 followups already
 * track the Redis-shared cache as future work.
 */
@Component
public class ApiKeyCache {

    record Entry(Long apiKeyId, String tenantId, String keyHash, Instant expiresAt) {}

    private static final Duration TTL = Duration.ofSeconds(30);
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public Optional<Entry> get(String prefix) {
        Entry e = entries.get(prefix);
        if (e == null) return Optional.empty();
        if (Instant.now().isAfter(e.expiresAt())) {
            entries.remove(prefix);
            return Optional.empty();
        }
        return Optional.of(e);
    }

    public void put(String prefix, Long apiKeyId, String tenantId, String keyHash) {
        entries.put(prefix, new Entry(apiKeyId, tenantId, keyHash, Instant.now().plus(TTL)));
    }
}
```

- [ ] **Step 2: Write the failing test FIRST**

```java
package com.crosscert.passkey.app.security;

import com.crosscert.passkey.core.vpd.TenantContextHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthFilterTest {

    private ApiKeyLookupService lookup;
    private ApiKeyCache cache;
    private PasswordEncoder encoder;
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setup() {
        lookup = mock(ApiKeyLookupService.class);
        cache = new ApiKeyCache();
        encoder = new BCryptPasswordEncoder(4); // low cost for test speed
        filter = new ApiKeyAuthFilter(lookup, cache, encoder);
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
    }

    @Test
    void rejectsRequestWithoutHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/rp/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void rejectsUnknownPrefix() throws Exception {
        when(lookup.findByPrefix("pk_unknwn")).thenReturn(Optional.empty());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/rp/x");
        req.addHeader("X-API-Key", "pk_unknwnSECRETSECRETSECRET");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsWrongSecret() throws Exception {
        String fullKey = "pk_abcd1234SECRETPART01234567890ABCDEF";
        String prefix = "pk_abcd1234";
        String hash = encoder.encode("DIFFERENT_SECRET");
        when(lookup.findByPrefix(prefix)).thenReturn(Optional.of(
                new ApiKeyLookupService.ApiKeyAuthRow(
                        1L, "T_A", hash, /* expiresAt */ null, /* revokedAt */ null)));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/rp/x");
        req.addHeader("X-API-Key", fullKey);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void acceptsValidKeyAndSetsTenantContextDuringChain() throws Exception {
        String secret = "SECRET_PART_OK_01234567890ABCDEF";
        String fullKey = "pk_abcd1234" + secret;
        String prefix = "pk_abcd1234";
        String hash = encoder.encode(secret);
        when(lookup.findByPrefix(prefix)).thenReturn(Optional.of(
                new ApiKeyLookupService.ApiKeyAuthRow(7L, "T_A", hash, null, null)));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/rp/x");
        req.addHeader("X-API-Key", fullKey);
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] tenantSeenInChain = new String[1];
        FilterChain chain = (r, s) -> tenantSeenInChain[0] = TenantContextHolder.get();

        filter.doFilter(req, res, chain);

        // Implementations may return 200 explicitly or leave the default
        // 200 from MockHttpServletResponse; verify the chain ran.
        assertThat(tenantSeenInChain[0]).isEqualTo("T_A");
        assertThat(TenantContextHolder.get()).isNull();
        // touch_last_used was called after BCrypt success.
        verify(lookup).touchLastUsed(eq(7L), any(Instant.class));
    }

    @Test
    void rejectsRevokedKey() throws Exception {
        String secret = "SECRET";
        String prefix = "pk_revoked0";
        when(lookup.findByPrefix(prefix)).thenReturn(Optional.of(
                new ApiKeyLookupService.ApiKeyAuthRow(
                        99L, "T_A", encoder.encode(secret),
                        /* expiresAt */ null,
                        /* revokedAt */ Instant.now().minusSeconds(60))));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/rp/x");
        req.addHeader("X-API-Key", prefix + secret);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }
}
```

(Add `import static org.mockito.ArgumentMatchers.eq;` at the top of the test file.)

- [ ] **Step 3: Confirm compile failure**

```bash
./gradlew :passkey-app:test --tests ApiKeyAuthFilterTest 2>&1 | tail -10
```

Expected: `ApiKeyAuthFilter` / `ApiKeyLookupService` not found.

- [ ] **Step 4: Write ApiKeyLookupService (NEW — definer-rights PL/SQL caller)**

```java
package com.crosscert.passkey.app.security;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;

/**
 * Calls the V8-installed PL/SQL package APP_OWNER.api_key_lookup_pkg
 * for the two operations the auth filter needs:
 *
 * <ul>
 *   <li>{@link #findByPrefix(String)} — definer-rights SELECT that
 *       bypasses VPD because the calling session has no tenant
 *       context yet. Returns the row data needed to BCrypt-verify
 *       and set TenantContextHolder.</li>
 *   <li>{@link #touchLastUsed(long, Instant)} — UPDATE last_used_at
 *       after a successful authentication. The package internally
 *       constrains the UPDATE with WHERE tenant_id = SYS_CONTEXT(),
 *       so the caller must have TenantContextHolder set first.</li>
 * </ul>
 *
 * <p>Uses the primary {@link TenantAwareDataSource} bean for both
 * calls. set_tenant is a no-op when TenantContextHolder is null
 * (findByPrefix path), and behaves normally for the touchLastUsed
 * path (TenantContextHolder is set by ApiKeyAuthFilter before it
 * runs).
 */
@Service
public class ApiKeyLookupService {

    private final JdbcTemplate jdbc;

    public ApiKeyLookupService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public Optional<ApiKeyAuthRow> findByPrefix(String keyPrefix) {
        return jdbc.execute((ConnectionCallback<Optional<ApiKeyAuthRow>>) conn -> {
            try (CallableStatement cs = conn.prepareCall(
                    "{ call APP_OWNER.api_key_lookup_pkg.find_by_prefix(?, ?, ?, ?, ?, ?, ?) }")) {
                cs.setString(1, keyPrefix);
                cs.registerOutParameter(2, Types.NUMERIC);     // p_found
                cs.registerOutParameter(3, Types.NUMERIC);     // p_id
                cs.registerOutParameter(4, Types.VARCHAR);     // p_tenant_id
                cs.registerOutParameter(5, Types.VARCHAR);     // p_key_hash
                cs.registerOutParameter(6, Types.TIMESTAMP_WITH_TIMEZONE); // p_expires_at
                cs.registerOutParameter(7, Types.TIMESTAMP_WITH_TIMEZONE); // p_revoked_at
                cs.execute();

                if (cs.getInt(2) == 0) return Optional.empty();
                long id = cs.getLong(3);
                String tenantId = cs.getString(4);
                String keyHash = cs.getString(5);
                Timestamp expTs = cs.getTimestamp(6);
                Timestamp revTs = cs.getTimestamp(7);
                Instant expiresAt = expTs == null ? null : expTs.toInstant();
                Instant revokedAt = revTs == null ? null : revTs.toInstant();
                return Optional.of(new ApiKeyAuthRow(id, tenantId, keyHash, expiresAt, revokedAt));
            }
        });
    }

    public void touchLastUsed(long apiKeyId, Instant now) {
        // Best-effort: a touch failure must NOT turn a valid auth into a
        // 500. Catch DataAccessException at the OUTER call site — Spring's
        // JdbcTemplate translates SQLException to DataAccessException
        // AFTER the ConnectionCallback returns, so an inner catch would
        // never see it (connection-borrow failures also throw outside
        // the callback).
        try {
            jdbc.execute((ConnectionCallback<Void>) conn -> {
                try (CallableStatement cs = conn.prepareCall(
                        "{ call APP_OWNER.api_key_lookup_pkg.touch_last_used(?, ?) }")) {
                    cs.setLong(1, apiKeyId);
                    cs.setTimestamp(2, Timestamp.from(now));
                    cs.execute();
                    return null;
                }
            });
        } catch (DataAccessException e) {
            // TODO Phase 2: emit a metric for touch failure rate.
            // For Phase 1 silent-swallow is acceptable; the request still
            // completes successfully and last_used_at becomes stale by at
            // most one transaction.
        }
    }

    /** Minimal row data the filter needs from V8's package. */
    public record ApiKeyAuthRow(
            long id, String tenantId, String keyHash,
            Instant expiresAt, Instant revokedAt) {

        public boolean isActive(Instant now) {
            if (revokedAt != null) return false;
            if (expiresAt != null && !expiresAt.isAfter(now)) return false;
            return true;
        }
    }
}
```

- [ ] **Step 5: Write ApiKeyAuthFilter (uses ApiKeyLookupService, NOT ApiKeyRepository)**

```java
package com.crosscert.passkey.app.security;

import com.crosscert.passkey.core.vpd.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Filters every {@code /api/v1/rp/**} request. Parses {@code X-API-Key},
 * looks up the row through {@link ApiKeyLookupService} (which calls
 * the definer-rights V8 PL/SQL package — VPD bypass is safe and scoped),
 * BCrypt-verifies the secret, and on success sets
 * {@link TenantContextHolder} for the duration of the request.
 *
 * <p>Order: {@code HIGHEST_PRECEDENCE + 5} — runs after RateLimitFilter
 * (which lives at {@code HIGHEST_PRECEDENCE}) and before the legacy
 * DevTenantHeaderFilter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";
    private static final int PREFIX_LEN = 11; // "pk_" + 8 chars

    private final ApiKeyLookupService lookup;
    private final ApiKeyCache cache;
    private final PasswordEncoder encoder;

    public ApiKeyAuthFilter(ApiKeyLookupService lookup,
                            ApiKeyCache cache,
                            PasswordEncoder encoder) {
        this.lookup = lookup;
        this.cache = cache;
        this.encoder = encoder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/rp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader(HEADER);
        if (header == null || header.length() <= PREFIX_LEN) {
            unauthorized(res, "missing X-API-Key");
            return;
        }

        String prefix = header.substring(0, PREFIX_LEN);
        String secret = header.substring(PREFIX_LEN);

        Optional<ApiKeyLookupService.ApiKeyAuthRow> opt = lookup.findByPrefix(prefix);
        if (opt.isEmpty()) {
            unauthorized(res, "unknown key");
            return;
        }
        ApiKeyLookupService.ApiKeyAuthRow row = opt.get();

        Instant now = Instant.now();
        if (!row.isActive(now)) {
            unauthorized(res, "key revoked or expired");
            return;
        }
        if (!encoder.matches(secret, row.keyHash())) {
            unauthorized(res, "key mismatch");
            return;
        }

        cache.put(prefix, row.id(), row.tenantId(), row.keyHash());
        TenantContextHolder.set(row.tenantId());
        try {
            // touchLastUsed runs WITH tenant context active so the
            // V8 package's WHERE tenant_id = SYS_CONTEXT predicate
            // matches the calling tenant exactly.
            lookup.touchLastUsed(row.id(), now);
            chain.doFilter(req, res);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void unauthorized(HttpServletResponse res, String reason) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/problem+json");
        res.getWriter().write(
                "{\"type\":\"about:blank\",\"status\":401,\"title\":\"Unauthorized\",\"detail\":\""
                        + reason + "\"}");
    }
}
```

Note: the test calls `filter.doFilter(req, res, chain)` directly. `OncePerRequestFilter.doFilter` checks the once-per-request flag; the test paths start with `/api/v1/rp/` so `shouldNotFilter` returns false.

- [ ] **Step 5: Wire BCryptPasswordEncoder bean**

Add to `core/src/main/java/com/crosscert/passkey/core/config/CoreSecurityConfig.java`:

```java
package com.crosscert.passkey.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class CoreSecurityConfig {

    /**
     * BCrypt cost 12 (~250ms/op on Apple Silicon under emulation,
     * tolerable for API key auth which is gated by a 30s cache).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
```

- [ ] **Step 6: Run tests, confirm pass**

```bash
./gradlew :passkey-app:test --tests ApiKeyAuthFilterTest 2>&1 | tail -5
```

Expected: 5 tests pass.

- [ ] **Step 7: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyLookupService.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyCache.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/security/ApiKeyAuthFilterTest.java \
        core/src/main/java/com/crosscert/passkey/core/config/CoreSecurityConfig.java
```

---

## Task 17: RateLimitFilter (Bucket4j + Redis)

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitFilter.java`
- Test: `passkey-app/src/test/java/com/crosscert/passkey/app/security/RateLimitFilterTest.java`

- [ ] **Step 1: Write the failing test FIRST**

```java
package com.crosscert.passkey.app.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private ProxyManager<String> proxy;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        proxy = mock(ProxyManager.class);
        filter = new RateLimitFilter(proxy);
    }

    @Test
    void allowsWhenBucketHasTokens() throws Exception {
        Bucket bucket = mock(Bucket.class);
        when(bucket.tryConsume(1L)).thenReturn(true);
        when(proxy.builder().build(any(), any())).thenReturn(bucket);
        // (real test uses local bucket and full Bucket4j integration —
        //  this test only verifies filter wiring + 200/429 dispatch)

        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/api/v1/rp/registration/start");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(req, res);
    }

    // Note: a Redis-backed RateLimit smoke test lives in Fido2EndToEndIT
    // (Task 26) — that's where we actually verify the distributed bucket.
}
```

Because Bucket4j 8.10's distributed bucket API requires a Redis-backed `ProxyManager` and mocking is fragile, this test exercises only the request-level wiring. The integration test (Task 26 scenario 8) is the real proof.

- [ ] **Step 2: Confirm compile failure**

```bash
./gradlew :passkey-app:test --tests RateLimitFilterTest 2>&1 | tail -10
```

Expected: `RateLimitFilter` not found.

- [ ] **Step 3: Write RateLimitFilter**

```java
package com.crosscert.passkey.app.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Token-bucket rate limit over Redis. Limits per (tenant, endpoint)
 * pair. The tenant id is taken from the X-API-Key header (parsing
 * happens here before ApiKeyAuthFilter validates the key — by design,
 * we want to drop floods before BCrypt runs).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int PREFIX_LEN = 11;
    private static final Map<String, BucketConfiguration> LIMITS = Map.of(
            "/api/v1/rp/registration/start",
            tokenBucket(60, Duration.ofMinutes(1)),
            "/api/v1/rp/registration/finish",
            tokenBucket(60, Duration.ofMinutes(1)),
            "/api/v1/rp/authentication/start",
            tokenBucket(300, Duration.ofMinutes(1)),
            "/api/v1/rp/authentication/finish",
            tokenBucket(300, Duration.ofMinutes(1)));

    private final ProxyManager<String> proxy;

    public RateLimitFilter(ProxyManager<String> proxy) {
        this.proxy = proxy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LIMITS.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("X-API-Key");
        String tenantKeyPart = (header == null || header.length() < PREFIX_LEN)
                ? "anonymous-" + req.getRemoteAddr()
                : header.substring(0, PREFIX_LEN);
        String bucketKey = "ratelimit:" + req.getRequestURI() + ":" + tenantKeyPart;

        BucketConfiguration config = LIMITS.get(req.getRequestURI());
        var bucket = proxy.builder().build(bucketKey, () -> config);

        if (bucket.tryConsume(1L)) {
            chain.doFilter(req, res);
        } else {
            res.setStatus(429);
            res.setHeader("Retry-After", "60");
            res.setContentType("application/problem+json");
            res.getWriter().write(
                    "{\"type\":\"about:blank\",\"status\":429,\"title\":\"Too Many Requests\"}");
        }
    }

    private static BucketConfiguration tokenBucket(long capacity, Duration window) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.greedy(capacity, window)))
                .build();
    }
}
```

- [ ] **Step 4: Wire ProxyManager bean**

Add to `passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitConfig.java`:

```java
package com.crosscert.passkey.app.security;

import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient bucket4jRedisClient(RedisProperties props) {
        return RedisClient.create("redis://" + props.getHost() + ":" + props.getPort());
    }

    @Bean
    public StatefulRedisConnection<String, byte[]> bucket4jRedisConnection(RedisClient client) {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    public ProxyManager<String> bucket4jProxyManager(
            StatefulRedisConnection<String, byte[]> connection) {
        return LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(
                        io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
                                .basedOnTimeForRefillingBucketUpToMax(Duration.ofHours(1)))
                .build();
    }
}
```

- [ ] **Step 5: Run tests, confirm pass**

```bash
./gradlew :passkey-app:test --tests RateLimitFilterTest 2>&1 | tail -5
```

Expected: 1 test passes (and pin a TODO that the real distributed-bucket coverage is in Task 26).

- [ ] **Step 6: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitFilter.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitConfig.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/security/RateLimitFilterTest.java
```

---

## Task 18: RegistrationStartService

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java`

- [ ] **Step 1: Write the implementation**

```java
package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Base64;

@Service
public class RegistrationStartService {

    private final TenantRepository tenants;
    private final ChallengeIssuer challenges;
    private final ChallengeStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public RegistrationStartService(TenantRepository tenants,
                                    ChallengeIssuer challenges,
                                    ChallengeStore store,
                                    ObjectMapper mapper,
                                    Clock clock) {
        this.tenants = tenants;
        this.challenges = challenges;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    public RegistrationStartResponse start(RegistrationStartRequest req) {
        String tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "registration/start invoked without tenant context — ApiKeyAuthFilter "
                            + "must have set it");
        }
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "tenant " + tenantId + " not found"));

        byte[] userHandle = Base64.getUrlDecoder().decode(req.userHandle());
        byte[] challenge = challenges.newChallengeBytes();
        String token = challenges.newToken();

        store.putRegistration(token, new RegistrationChallenge(
                tenantId, challenge, userHandle, req.displayName(), req.username(),
                clock.instant()));

        ObjectNode options = mapper.createObjectNode();
        options.put("challenge", b64url(challenge));
        ObjectNode rp = options.putObject("rp");
        rp.put("id", tenant.getRpId());
        rp.put("name", tenant.getRpName());
        ObjectNode user = options.putObject("user");
        user.put("id", req.userHandle());
        user.put("displayName", req.displayName());
        user.put("name", req.username());
        ArrayNode params = options.putArray("pubKeyCredParams");
        params.addObject().put("type", "public-key").put("alg", -7);    // ES256
        params.addObject().put("type", "public-key").put("alg", -257);  // RS256
        options.put("timeout", 60000);
        options.put("attestation", "indirect");
        ObjectNode sel = options.putObject("authenticatorSelection");
        sel.put("userVerification", "required");
        sel.put("residentKey", "preferred");

        return new RegistrationStartResponse(token, options);
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :passkey-app:compileJava 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java
```

---

## Task 19: RegistrationFinishService

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java`

webauthn4j 0.29.x API (verified via context7): `parseRegistrationResponseJSON(json)` → `RegistrationData`, then `verify(data, params)` for validation. The persisted artifact is a `CredentialRecord` built via `CredentialRecordImpl(attestationObject, collectedClientData, clientExtensions, transports)`. We store its CBOR-serialized form in our `credential.public_key` BLOB and reconstruct on authentication.

- [ ] **Step 1: Write the implementation**

```java
package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishResponse;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge;
import com.crosscert.passkey.app.fido2.mds.MdsStubVerifier;
import com.crosscert.passkey.app.fido2.policy.AttestationPolicy;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
public class RegistrationFinishService {

    private final ChallengeStore store;
    private final WebAuthnManager manager;
    private final TenantRepository tenants;
    private final CredentialRepository credentials;
    private final MdsStubVerifier mds;
    private final ObjectMapper mapper;
    private final ObjectConverter objectConverter;
    private final Clock clock;

    public RegistrationFinishService(ChallengeStore store,
                                     WebAuthnManager manager,
                                     TenantRepository tenants,
                                     CredentialRepository credentials,
                                     MdsStubVerifier mds,
                                     ObjectMapper mapper,
                                     Clock clock) {
        this.store = store;
        this.manager = manager;
        this.tenants = tenants;
        this.credentials = credentials;
        this.mds = mds;
        this.mapper = mapper;
        this.objectConverter = new ObjectConverter();
        this.clock = clock;
    }

    @Transactional
    public RegistrationFinishResponse finish(RegistrationFinishRequest req) {
        RegistrationChallenge ch = store.takeRegistration(req.registrationToken())
                .orElseThrow(() -> new IllegalArgumentException(
                        "registration token missing or expired"));

        Tenant tenant = tenants.findById(ch.tenantId())
                .orElseThrow(() -> new IllegalStateException(
                        "tenant " + ch.tenantId() + " missing"));
        AttestationPolicy policy = AttestationPolicy.fromJson(tenant.getAttestationPolicyJson());

        // The RP forwarded the entire PublicKeyCredential JSON from the
        // browser. webauthn4j parses that directly.
        String publicKeyCredentialJson;
        try {
            publicKeyCredentialJson = mapper.writeValueAsString(req.publicKeyCredential());
        } catch (Exception e) {
            throw new IllegalArgumentException("publicKeyCredential JSON invalid", e);
        }

        RegistrationData data;
        try {
            data = manager.parseRegistrationResponseJSON(publicKeyCredentialJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("attestation parse failed: " + e.getMessage(), e);
        }

        ServerProperty serverProperty = ServerProperty.builder()
                .origin(Origin.create(allowedOriginsAsList(tenant).get(0)))
                .rpId(tenant.getRpId())
                .challenge(new DefaultChallenge(ch.challenge()))
                .build();

        RegistrationParameters wParams = new RegistrationParameters(
                serverProperty,
                /* pubKeyCredParams */ null,
                /* userVerificationRequired */ policy.requireUserVerification(),
                /* userPresenceRequired */ true);

        try {
            manager.verify(data, wParams);
        } catch (Exception e) {
            throw new IllegalArgumentException("attestation verify failed: " + e.getMessage(), e);
        }

        String fmt = data.getAttestationObject().getFormat();
        if (!policy.acceptedFormats().contains(fmt)) {
            throw new IllegalArgumentException("attestation format " + fmt
                    + " not in tenant policy");
        }

        byte[] aaguid = data.getAttestationObject().getAuthenticatorData()
                .getAttestedCredentialData().getAaguid().getValue();
        if (!mds.verify(policy, aaguid)) {
            throw new IllegalArgumentException("MDS verification failed");
        }

        // Build the CredentialRecord webauthn4j-style, then serialize it
        // to CBOR for storage. On authentication we reverse this.
        CredentialRecord record = new CredentialRecordImpl(
                data.getAttestationObject(),
                data.getCollectedClientData(),
                data.getClientExtensions(),
                data.getTransports());
        byte[] credentialRecordBytes = objectConverter.getCborConverter().writeValueAsBytes(record);

        byte[] credentialId = data.getAttestationObject().getAuthenticatorData()
                .getAttestedCredentialData().getCredentialId();
        Credential cred = new Credential(
                ch.tenantId(),
                ch.userHandle(),
                credentialId,
                credentialRecordBytes,
                aaguid);
        credentials.saveAndFlush(cred);

        return new RegistrationFinishResponse(
                b64url(credentialId),
                aaguid == null ? null : HexFormat.of().formatHex(aaguid),
                fmt,
                clock.instant());
    }

    private List<String> allowedOriginsAsList(Tenant t) {
        try {
            return List.of(mapper.readValue(t.getAllowedOriginsJson(), String[].class));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "tenant " + t.getId() + " allowed_origins JSON invalid", e);
        }
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
```

Subagent note: webauthn4j 0.29.x's CborConverter exposes `writeValueAsBytes(...)` and `readValue(byte[], Class)`. Confirm the exact method names against the installed library — if they shifted to `serialize(...)` in a sub-patch, adjust accordingly and surface in the report. The shape and semantics are stable; only the method name might drift one notch.

- [ ] **Step 2: Verify compile**

```bash
./gradlew :passkey-app:compileJava 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`. If a webauthn4j method differs from the snippet above, prefer the library's documented name over the snippet (the snippet matches docs as of `0.29.0.RELEASE`).

- [ ] **Step 3: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java
```

---

## Task 20: AuthenticationStartService

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java`

- [ ] **Step 1: Write the implementation**

```java
package com.crosscert.passkey.app.fido2.authentication;

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartResponse;
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Base64;
import java.util.List;

@Service
public class AuthenticationStartService {

    private final TenantRepository tenants;
    private final CredentialRepository credentials;
    private final ChallengeIssuer challenges;
    private final ChallengeStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AuthenticationStartService(TenantRepository tenants,
                                      CredentialRepository credentials,
                                      ChallengeIssuer challenges,
                                      ChallengeStore store,
                                      ObjectMapper mapper,
                                      Clock clock) {
        this.tenants = tenants;
        this.credentials = credentials;
        this.challenges = challenges;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    public AuthenticationStartResponse start(AuthenticationStartRequest req) {
        String tenantId = TenantContextHolder.get();
        Tenant tenant = tenants.findById(tenantId).orElseThrow();

        byte[] userHandle = req.userHandle() == null ? null
                : Base64.getUrlDecoder().decode(req.userHandle());

        // VPD-filtered: only this tenant's credentials returned.
        List<Credential> userCreds = credentials.findAll();
        if (userHandle != null) {
            userCreds = userCreds.stream()
                    .filter(c -> java.util.Arrays.equals(
                            getUserHandle(c), userHandle))
                    .toList();
        }

        byte[] challenge = challenges.newChallengeBytes();
        String token = challenges.newToken();
        store.putAuthentication(token, new AuthenticationChallenge(
                tenantId, challenge, userHandle, clock.instant()));

        ObjectNode options = mapper.createObjectNode();
        options.put("challenge", b64url(challenge));
        options.put("rpId", tenant.getRpId());
        options.put("timeout", 60000);
        options.put("userVerification", "required");
        ArrayNode allow = options.putArray("allowCredentials");
        for (Credential c : userCreds) {
            ObjectNode entry = allow.addObject();
            entry.put("type", "public-key");
            entry.put("id", b64url(c.getCredentialId()));
        }
        return new AuthenticationStartResponse(token, options);
    }

    private byte[] getUserHandle(Credential c) {
        // Credential entity has no public getUserHandle today —
        // expose it via a package-private accessor before Phase 1.
        // Phase 0 left it as a private field — add the getter as part of T20.
        try {
            java.lang.reflect.Field f = Credential.class.getDeclaredField("userHandle");
            f.setAccessible(true);
            return (byte[]) f.get(c);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
```

The reflective access to `userHandle` is a placeholder — actually expose it as part of T20 by editing Credential.java. Subagent: when implementing T20, also add `public byte[] getUserHandle()` to `Credential.java` and remove the reflection.

- [ ] **Step 2: Add `getUserHandle()` to Credential entity**

Modify `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java`: in the getters block, add:

```java
    public byte[] getUserHandle() { return userHandle; }
```

Then remove the reflection block from `AuthenticationStartService.getUserHandle(...)` — call `c.getUserHandle()` directly.

- [ ] **Step 3: Verify compile**

```bash
./gradlew :passkey-app:compileJava 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java \
        core/src/main/java/com/crosscert/passkey/core/entity/Credential.java
```

---

## Task 21: AuthenticationFinishService

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java` (add `getAaguid`, `recordAuthentication`, `getCredentialRecordBytes`)
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`

webauthn4j 0.29.x exposes `parseAuthenticationResponseJSON(json) → AuthenticationData`, then `verify(data, params)`. The `AuthenticationParameters` second argument is a `CredentialRecord` (NOT the old `Authenticator` type) — we deserialize the CBOR bytes stored at registration time via `ObjectConverter.getCborConverter().readValue(bytes, CredentialRecordImpl.class)`. After verification we update both our DB row and the deserialized `CredentialRecord`'s sign count (re-serialize and persist).

- [ ] **Step 1: Modify Credential entity — add proper accessors/mutators**

Edit `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java`. In the getters block, add (or replace existing getters):

```java
    public byte[] getAaguid() { return aaguid; }
    public byte[] getCredentialRecordBytes() { return publicKey; } // BLOB holds CBOR of CredentialRecord
    public Instant getLastUsedAt() { return lastUsedAt; }

    /**
     * Atomic state mutation after a successful authentication. signCount
     * must be strictly increasing (replay defense); callers verify that
     * before calling this.
     */
    public void recordAuthentication(long newSignCount, byte[] newCredentialRecordBytes, Instant now) {
        this.signCount = newSignCount;
        this.publicKey = newCredentialRecordBytes;
        this.lastUsedAt = now;
    }
```

- [ ] **Step 2: Write AuthenticationFinishService**

```java
package com.crosscert.passkey.app.fido2.authentication;

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishResponse;
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.jwt.IdTokenIssuer;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

@Service
public class AuthenticationFinishService {

    private final ChallengeStore store;
    private final WebAuthnManager manager;
    private final TenantRepository tenants;
    private final CredentialRepository credentials;
    private final IdTokenIssuer idTokens;
    private final ObjectMapper mapper;
    private final ObjectConverter objectConverter;
    private final Clock clock;

    public AuthenticationFinishService(ChallengeStore store,
                                       WebAuthnManager manager,
                                       TenantRepository tenants,
                                       CredentialRepository credentials,
                                       IdTokenIssuer idTokens,
                                       ObjectMapper mapper,
                                       Clock clock) {
        this.store = store;
        this.manager = manager;
        this.tenants = tenants;
        this.credentials = credentials;
        this.idTokens = idTokens;
        this.mapper = mapper;
        this.objectConverter = new ObjectConverter();
        this.clock = clock;
    }

    @Transactional
    public AuthenticationFinishResponse finish(AuthenticationFinishRequest req) {
        AuthenticationChallenge ch = store.takeAuthentication(req.authenticationToken())
                .orElseThrow(() -> new IllegalArgumentException(
                        "authentication token missing or expired"));

        Tenant tenant = tenants.findById(ch.tenantId()).orElseThrow();

        String publicKeyCredentialJson;
        try {
            publicKeyCredentialJson = mapper.writeValueAsString(req.publicKeyCredential());
        } catch (Exception e) {
            throw new IllegalArgumentException("publicKeyCredential JSON invalid", e);
        }

        AuthenticationData data;
        try {
            data = manager.parseAuthenticationResponseJSON(publicKeyCredentialJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("assertion parse failed: " + e.getMessage(), e);
        }

        byte[] credentialId = data.getCredentialId();

        // VPD-filtered: only this tenant's credentials are visible. Phase 2
        // adds a derived findByCredentialId query for efficiency.
        Credential cred = credentials.findAll().stream()
                .filter(c -> Arrays.equals(c.getCredentialId(), credentialId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("credential not registered"));

        // Deserialize stored CredentialRecord (CBOR) and prepare for verify.
        CredentialRecord record = objectConverter.getCborConverter()
                .readValue(cred.getCredentialRecordBytes(), CredentialRecordImpl.class);

        ServerProperty serverProperty = ServerProperty.builder()
                .origin(Origin.create(allowedOriginsAsList(tenant).get(0)))
                .rpId(tenant.getRpId())
                .challenge(new DefaultChallenge(ch.challenge()))
                .build();

        AuthenticationParameters wParams = new AuthenticationParameters(
                serverProperty,
                record,
                /* allowCredentials */ List.of(credentialId),
                /* userVerificationRequired */ true,
                /* userPresenceRequired */ true);

        try {
            manager.verify(data, wParams);
        } catch (Exception e) {
            throw new IllegalArgumentException("assertion verify failed: " + e.getMessage(), e);
        }

        long newCounter = data.getAuthenticatorData().getSignCount();
        if (newCounter <= cred.getSignCount()) {
            throw new IllegalArgumentException(
                    "signCount did not advance — possible replay (was "
                            + cred.getSignCount() + ", got " + newCounter + ")");
        }

        // Mutate the in-memory CredentialRecord, re-serialize, and persist.
        record.setCounter(newCounter);
        byte[] updatedBytes = objectConverter.getCborConverter().writeValueAsBytes(record);
        cred.recordAuthentication(newCounter, updatedBytes, clock.instant());
        credentials.saveAndFlush(cred);

        String jwt = idTokens.issue(
                ch.tenantId(),
                cred.getUserHandle(),
                cred.getId(),
                cred.getAaguid());
        return new AuthenticationFinishResponse(jwt, "Bearer", 900);
    }

    private List<String> allowedOriginsAsList(Tenant t) {
        try {
            return List.of(mapper.readValue(t.getAllowedOriginsJson(), String[].class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

Subagent note: `CredentialRecord.setCounter(long)` exists on `CredentialRecordImpl`; if the installed version exposes it under a different name (e.g. `updateCounter`), adjust accordingly. Likewise the `CborConverter.readValue/writeValueAsBytes` pair — webauthn4j keeps these stable but verify against the actual classpath.

- [ ] **Step 3: Verify compile**

```bash
./gradlew :passkey-app:compileJava 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Stage**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Credential.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java
```

---

## Task 22: RegistrationController

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/RegistrationController.java`

- [ ] **Step 1: Write the controller**

```java
package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishResponse;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse;
import com.crosscert.passkey.app.fido2.registration.RegistrationFinishService;
import com.crosscert.passkey.app.fido2.registration.RegistrationStartService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rp/registration")
public class RegistrationController {

    private final RegistrationStartService start;
    private final RegistrationFinishService finish;

    public RegistrationController(RegistrationStartService start,
                                  RegistrationFinishService finish) {
        this.start = start;
        this.finish = finish;
    }

    @PostMapping("/start")
    public RegistrationStartResponse start(@Valid @RequestBody RegistrationStartRequest req) {
        return start.start(req);
    }

    @PostMapping("/finish")
    public RegistrationFinishResponse finish(@Valid @RequestBody RegistrationFinishRequest req) {
        return finish.finish(req);
    }
}
```

- [ ] **Step 2: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/RegistrationController.java
```

---

## Task 23: AuthenticationController

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/AuthenticationController.java`

- [ ] **Step 1: Write the controller**

```java
package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishResponse;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartResponse;
import com.crosscert.passkey.app.fido2.authentication.AuthenticationFinishService;
import com.crosscert.passkey.app.fido2.authentication.AuthenticationStartService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rp/authentication")
public class AuthenticationController {

    private final AuthenticationStartService start;
    private final AuthenticationFinishService finish;

    public AuthenticationController(AuthenticationStartService start,
                                    AuthenticationFinishService finish) {
        this.start = start;
        this.finish = finish;
    }

    @PostMapping("/start")
    public AuthenticationStartResponse start(@Valid @RequestBody AuthenticationStartRequest req) {
        return start.start(req);
    }

    @PostMapping("/finish")
    public AuthenticationFinishResponse finish(@Valid @RequestBody AuthenticationFinishRequest req) {
        return finish.finish(req);
    }
}
```

- [ ] **Step 2: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/AuthenticationController.java
```

---

## Task 24: JwksController — TDD

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/JwksController.java`
- Test: `passkey-app/src/test/java/com/crosscert/passkey/app/api/v1/rp/JwksControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.core.jwt.SigningKeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class JwksControllerTest {

    @Autowired TestRestTemplate rest;
    @Autowired SigningKeyProvider keys;

    @Test
    void returnsJwksWithSinglePublicKey() {
        String body = rest.getForObject("/.well-known/jwks.json", String.class);
        assertThat(body).contains("\"keys\"");
        assertThat(body).contains("\"kty\":\"RSA\"");
        assertThat(body).contains("\"alg\":\"RS256\"");
        assertThat(body).contains(keys.signingKey().getKeyID());
        // No private exponent leaks.
        assertThat(body).doesNotContain("\"d\":");
    }
}
```

- [ ] **Step 2: Confirm compile failure**

```bash
./gradlew :passkey-app:test --tests JwksControllerTest 2>&1 | tail -10
```

Expected: `JwksController` not found.

- [ ] **Step 3: Write the controller**

```java
package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.core.jwt.SigningKeyProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class JwksController {

    private final SigningKeyProvider keys;

    public JwksController(SigningKeyProvider keys) {
        this.keys = keys;
    }

    @GetMapping(value = "/.well-known/jwks.json",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return keys.publicJwkSet().toJSONObject();
    }
}
```

- [ ] **Step 4: Stage**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/JwksController.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/api/v1/rp/JwksControllerTest.java
```

---

## Task 25: Apply migrations + verify both apps still boot

**Files:** (no code changes — only verification)

- [ ] **Step 1: Verify Phase 0 apps still build with Phase 1 code added**

```bash
./gradlew clean build 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. (Many tests now exist; integration tests in Task 26 will follow.)

- [ ] **Step 2: Boot admin-app to apply V6/V7/V8**

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/admin-v8.log 2>&1 &
ADMIN_PID=$!
```

Wait for `Started AdminApplication` (max 90s):

```bash
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/admin-v8.log; do sleep 3; done
grep -E "migration|Successfully" /tmp/admin-v8.log | head -10
```

Expected: `Successfully applied 3 migrations` (V6, V7, V8) or `Successfully validated 8 migrations`.

- [ ] **Step 3: sqlplus verification**

```bash
docker exec -i passkey-oracle sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1 <<'EOF'
SELECT table_name FROM user_tables ORDER BY table_name;
SELECT policy_name, object_name FROM user_policies ORDER BY object_name;
EOF
```

Expected: tables include `API_KEY`, `CREDENTIAL`, `MDS_BLOB_CACHE`, `SCHEDULER_LEASE`, `TENANT`, `flyway_schema_history`. Policies: `API_KEY_TENANT_ISOLATION` on `API_KEY`, `CREDENTIAL_TENANT_ISOLATION` on `CREDENTIAL`.

- [ ] **Step 4: Boot passkey-app, confirm ddl-validate passes for new ApiKey + extended Tenant**

```bash
kill $ADMIN_PID; wait $ADMIN_PID 2>/dev/null
./gradlew :passkey-app:bootRun --args='--spring.profiles.active=local --server.port=8082' > /tmp/passkey-v8.log 2>&1 &
PASSKEY_PID=$!
until grep -qE "Started PasskeyApplication|APPLICATION FAILED" /tmp/passkey-v8.log; do sleep 3; done
tail -5 /tmp/passkey-v8.log
kill $PASSKEY_PID; wait $PASSKEY_PID 2>/dev/null
```

Expected: `Started PasskeyApplication` and no `Schema-validation` errors.

- [ ] **Step 5: Stage (no files; this task is verification only). Skip commit-staging — task is checkpoint.**

---

## Task 26: Fido2EndToEndIT — the Phase 1 acceptance gate

**Files:**
- Create: `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java`
- Create: `passkey-app/src/test/resources/application-test.yml` — extends core test config
- Create: helper that copies `bootstrap-vpd.sql` into passkey-app test resources (via gradle Sync, mirroring Phase 0 T16)

- [ ] **Step 1: Set up test resources copy**

In `passkey-app/build.gradle.kts`, add at the bottom:

```kotlin
tasks.named<Copy>("processTestResources") {
    from(rootProject.file("scripts/bootstrap-vpd.sql"))
}
tasks.named<Test>("test") {
    systemProperty("api.version", "1.43")
}
```

- [ ] **Step 2: Write application-test.yml**

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: APP_OWNER
    default-schema: APP_OWNER
    baseline-on-migrate: true
    baseline-version: 0
  jpa:
    hibernate.ddl-auto: validate
    properties:
      hibernate.dialect: org.hibernate.dialect.OracleDialect
      hibernate.jdbc.time_zone: UTC
      hibernate.default_schema: APP_OWNER
  datasource:
    driver-class-name: oracle.jdbc.OracleDriver

passkey:
  id-token:
    issuer-base: https://passkey.test
    ttl: PT15M
```

- [ ] **Step 3: Helper — Fido2TestAuthenticator (webauthn4j-test wrapper)**

`passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2TestAuthenticator.java`:

```java
package com.crosscert.passkey.app.fido2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.test.EmulatorUtil;
import com.webauthn4j.test.client.ClientPlatform;
import com.webauthn4j.test.client.PublicKeyCredentialCreationOptions;
import com.webauthn4j.test.client.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.client.Origin;

import java.util.Base64;

/**
 * Thin wrapper around webauthn4j-test's {@link ClientPlatform} that
 * speaks JSON the same way a real browser would. Used by Fido2EndToEndIT
 * to simulate a passkey authenticator without any real hardware.
 *
 * <p>Single platform instance per test gives stable credentialId + private
 * key across registration → authentication within a scenario.
 */
public class Fido2TestAuthenticator {

    private final ClientPlatform clientPlatform;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectConverter cbor = new ObjectConverter();

    public Fido2TestAuthenticator(String origin) {
        this.clientPlatform = EmulatorUtil.createClientPlatform(Origin.create(origin));
    }

    /** Drive an attestation against a server-issued options blob. */
    public JsonNode register(JsonNode serverOptions) throws Exception {
        PublicKeyCredentialCreationOptions options = json.treeToValue(
                serverOptions, PublicKeyCredentialCreationOptions.class);
        PublicKeyCredential<?, ?> credential = clientPlatform.create(options);
        // ClientPlatform produces a PublicKeyCredential whose response is
        // already encoded as raw bytes; we serialize to the WebAuthn JSON
        // shape (Base64URL fields) the server expects.
        return json.valueToTree(WebAuthnJsonShape.fromAttestationCredential(credential));
    }

    public JsonNode authenticate(JsonNode serverRequestOptions) throws Exception {
        PublicKeyCredentialRequestOptions options = json.treeToValue(
                serverRequestOptions, PublicKeyCredentialRequestOptions.class);
        PublicKeyCredential<?, ?> credential = clientPlatform.get(options);
        return json.valueToTree(WebAuthnJsonShape.fromAssertionCredential(credential));
    }

    /** Helper to materialize the JSON shape the server expects. */
    static final class WebAuthnJsonShape {
        // Implementation note: webauthn4j-test exposes the credential's
        // raw bytes (attestationObject, clientDataJSON, authenticatorData,
        // signature, userHandle) — we Base64URL-encode and place them at
        // the JSON paths the server parses in Tasks 19/21. Subagent fills
        // in the exact field plumbing per webauthn4j-test API.
        static Object fromAttestationCredential(PublicKeyCredential<?, ?> c) { return c; }
        static Object fromAssertionCredential(PublicKeyCredential<?, ?> c) { return c; }
    }
}
```

Subagent note: webauthn4j-test's `ClientPlatform.create/get` returns a typed `PublicKeyCredential`. The serialization shape the server parses in Tasks 19/21 is the standard WebAuthn Level 3 JSON (e.g. `{ rawId, response: { attestationObject, clientDataJSON, transports } }`). Use `objectConverter.getJsonConverter().writeValueAsString(credential)` to get the canonical JSON, then parse into a `JsonNode` for the test request body. If the conversion shape differs, hand-build the JSON in `WebAuthnJsonShape` — the exact API path here is the implementation detail with the most drift between webauthn4j versions.

- [ ] **Step 4: Write Fido2EndToEndIT (8 scenarios)**

```java
package com.crosscert.passkey.app.fido2;

import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class Fido2EndToEndIT {

    private static final String APP_USER_PASSWORD = "app_owner_pw";

    @Container
    static final OracleContainer ORACLE = new OracleContainer(
            "gvenzl/oracle-xe:21-slim-faststart")
            .withUsername("APP_OWNER")
            .withPassword(APP_USER_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("bootstrap-vpd.sql"),
                    "/tmp/bootstrap-vpd.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        var exec = ORACLE.execInContainer("bash", "-c",
                "sqlplus -S sys/" + APP_USER_PASSWORD
                        + "@localhost:1521/XEPDB1 as sysdba @/tmp/bootstrap-vpd.sql");
        if (exec.getExitCode() != 0) {
            throw new RuntimeException("bootstrap failed: " + exec.getStdout() + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
    }

    @Autowired TestRestTemplate rest;
    @Autowired TenantRepository tenants;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired PasswordEncoder encoder;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;

    final String tenantA = "T_A";
    final String tenantB = "T_B";
    final String secretA = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAA1";
    final String secretB = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBB2";
    final String apiKeyA = "pk_aaaaaaaa" + secretA;
    final String apiKeyB = "pk_bbbbbbbb" + secretB;

    @BeforeEach
    void seed() {
        TenantContextHolder.clear();
        seedTenant(tenantA, "pk_aaaaaaaa", secretA, "http://localhost");
        seedTenant(tenantB, "pk_bbbbbbbb", secretB, "http://localhost");
    }

    private void seedTenant(String id, String prefix, String secret, String origin) {
        if (tenants.findById(id).isPresent()) return;
        tenants.save(new Tenant(id, id, "localhost", id,
                "[\"" + origin + "\"]",
                "{\"acceptedFormats\":[\"none\",\"packed\"],\"requireUserVerification\":true,\"mdsRequired\":false}"));
        apiKeys.save(new ApiKey(id, prefix, encoder.encode(secret), "primary", "[]"));
    }

    private HttpHeaders authHeaders(String apiKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-API-Key", apiKey);
        return h;
    }

    // ─────────────── Scenario 4: missing API key → 401 ───────────────
    @Test
    @DisplayName("missing API Key → 401")
    void missingApiKey() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var resp = rest.exchange("/api/v1/rp/registration/start",
                HttpMethod.POST, new HttpEntity<>("{}", h), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // ─────────────── Scenario 5: wrong API key → 401 ───────────────
    @Test
    @DisplayName("wrong secret → 401")
    void wrongApiKey() {
        HttpHeaders h = authHeaders("pk_aaaaaaaaWRONG_SECRET_VALUE_THAT_IS_LONG_ENOUGH");
        var resp = rest.exchange("/api/v1/rp/registration/start",
                HttpMethod.POST, new HttpEntity<>("{}", h), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // ─────────────── Scenario 1+2: register + authenticate happy path ───
    @Test
    @DisplayName("register + authenticate happy path; JWT verifies vs JWKS")
    void happyPath() throws Exception {
        Fido2TestAuthenticator authn = new Fido2TestAuthenticator("http://localhost");

        // /registration/start
        ObjectNode startBody = json.createObjectNode()
                .put("userHandle", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[]{1, 2, 3, 4}))
                .put("displayName", "Alice")
                .put("username", "alice@example");
        var startResp = rest.exchange("/api/v1/rp/registration/start",
                HttpMethod.POST,
                new HttpEntity<>(startBody.toString(), authHeaders(apiKeyA)),
                JsonNode.class);
        assertThat(startResp.getStatusCode().is2xxSuccessful()).isTrue();
        String regToken = startResp.getBody().get("registrationToken").asText();
        JsonNode options = startResp.getBody().get("publicKeyCredentialCreationOptions");

        // Drive the authenticator simulator.
        JsonNode pkCred = authn.register(options);

        // /registration/finish
        ObjectNode finishBody = json.createObjectNode()
                .put("registrationToken", regToken)
                .set("publicKeyCredential", pkCred);
        var finishResp = rest.exchange("/api/v1/rp/registration/finish",
                HttpMethod.POST,
                new HttpEntity<>(finishBody.toString(), authHeaders(apiKeyA)),
                JsonNode.class);
        assertThat(finishResp.getStatusCode().is2xxSuccessful()).isTrue();

        // /authentication/start
        ObjectNode authStartBody = json.createObjectNode()
                .put("userHandle", startBody.get("userHandle").asText());
        var authStart = rest.exchange("/api/v1/rp/authentication/start",
                HttpMethod.POST,
                new HttpEntity<>(authStartBody.toString(), authHeaders(apiKeyA)),
                JsonNode.class);
        String authToken = authStart.getBody().get("authenticationToken").asText();
        JsonNode reqOptions = authStart.getBody().get("publicKeyCredentialRequestOptions");

        JsonNode assertion = authn.authenticate(reqOptions);

        ObjectNode authFinishBody = json.createObjectNode()
                .put("authenticationToken", authToken)
                .set("publicKeyCredential", assertion);
        var authFinish = rest.exchange("/api/v1/rp/authentication/finish",
                HttpMethod.POST,
                new HttpEntity<>(authFinishBody.toString(), authHeaders(apiKeyA)),
                JsonNode.class);
        assertThat(authFinish.getStatusCode().is2xxSuccessful()).isTrue();
        String idToken = authFinish.getBody().get("idToken").asText();

        // Verify the JWT against /.well-known/jwks.json
        var jwksResp = rest.getForObject("/.well-known/jwks.json", String.class);
        JWKSet jwks = JWKSet.parse(jwksResp);
        RSAKey pub = (RSAKey) jwks.getKeys().get(0);
        SignedJWT signed = SignedJWT.parse(idToken);
        assertThat(signed.verify(new RSASSAVerifier(pub))).isTrue();
        assertThat(signed.getJWTClaimsSet().getAudience()).contains(tenantA);
    }

    // ─────────────── Scenario 3: cross-tenant isolation ───────────────
    @Test
    @DisplayName("credential registered under T_A is invisible to T_B")
    void crossTenantIsolation() throws Exception {
        // Register a credential under tenant A with userHandle U.
        Fido2TestAuthenticator authn = new Fido2TestAuthenticator("http://localhost");
        registerOneCredential(authn, apiKeyA, new byte[]{9, 9, 9, 9});

        // Call /authentication/start with tenant B's API key,
        // asking for the same userHandle. allowCredentials should be empty.
        ObjectNode body = json.createObjectNode()
                .put("userHandle", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[]{9, 9, 9, 9}));
        var resp = rest.exchange("/api/v1/rp/authentication/start",
                HttpMethod.POST,
                new HttpEntity<>(body.toString(), authHeaders(apiKeyB)),
                JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode allow = resp.getBody()
                .get("publicKeyCredentialRequestOptions").get("allowCredentials");
        assertThat(allow.isArray() && allow.size() == 0)
                .as("VPD must hide T_A's credentials from T_B").isTrue();
    }

    private void registerOneCredential(Fido2TestAuthenticator authn, String apiKey, byte[] userHandle)
            throws Exception {
        ObjectNode startBody = json.createObjectNode()
                .put("userHandle", Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle))
                .put("displayName", "X")
                .put("username", "x@example");
        var start = rest.exchange("/api/v1/rp/registration/start",
                HttpMethod.POST,
                new HttpEntity<>(startBody.toString(), authHeaders(apiKey)),
                JsonNode.class);
        JsonNode pk = authn.register(start.getBody().get("publicKeyCredentialCreationOptions"));
        ObjectNode finishBody = json.createObjectNode()
                .put("registrationToken", start.getBody().get("registrationToken").asText())
                .set("publicKeyCredential", pk);
        rest.exchange("/api/v1/rp/registration/finish",
                HttpMethod.POST,
                new HttpEntity<>(finishBody.toString(), authHeaders(apiKey)),
                JsonNode.class);
    }

    // ─────────────── Scenario 6: expired challenge → 400 ─────────────
    @Test
    @DisplayName("expired challenge (manually-deleted Redis key) → 400")
    void expiredChallenge() throws Exception {
        Fido2TestAuthenticator authn = new Fido2TestAuthenticator("http://localhost");
        ObjectNode startBody = json.createObjectNode()
                .put("userHandle", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[]{1, 1, 1, 1}))
                .put("displayName", "X").put("username", "x@example");
        var start = rest.exchange("/api/v1/rp/registration/start",
                HttpMethod.POST,
                new HttpEntity<>(startBody.toString(), authHeaders(apiKeyA)),
                JsonNode.class);
        String regToken = start.getBody().get("registrationToken").asText();

        // Force-expire by deleting the Redis key.
        redis.delete("challenge:reg:" + regToken);

        JsonNode pk = authn.register(start.getBody().get("publicKeyCredentialCreationOptions"));
        ObjectNode finishBody = json.createObjectNode()
                .put("registrationToken", regToken)
                .set("publicKeyCredential", pk);
        var resp = rest.exchange("/api/v1/rp/registration/finish",
                HttpMethod.POST,
                new HttpEntity<>(finishBody.toString(), authHeaders(apiKeyA)),
                String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    // ─────────────── Scenario 7: signCount replay ──────────────────
    @Test
    @DisplayName("replayed signCount → 400")
    void signCountReplay() throws Exception {
        Fido2TestAuthenticator authn = new Fido2TestAuthenticator("http://localhost");
        registerOneCredential(authn, apiKeyA, new byte[]{2, 2, 2, 2});

        // First authentication — counter advances normally.
        // ... (see happyPath structure). After it completes,
        // replay the SAME assertion body and assert the server
        // rejects with 400. The webauthn4j-test ClientPlatform
        // increments its internal counter on each get(), so we
        // capture the assertion body and resubmit verbatim.
        // (Subagent implements the capture-and-resubmit; structure
        // is the same as Scenario 1+2 but with two POSTs of the
        // same finish body.)
    }

    // ─────────────── Scenario 8: rate limit 429 ────────────────────
    @Test
    @DisplayName("rapid burst on /authentication/start → 429 from one tenant")
    void rateLimit429() {
        int over = 320; // /authentication/start limit is 300/min
        HttpStatusCode worst = HttpStatusCode.valueOf(200);
        for (int i = 0; i < over; i++) {
            var resp = rest.exchange("/api/v1/rp/authentication/start",
                    HttpMethod.POST,
                    new HttpEntity<>("{}", authHeaders(apiKeyA)),
                    String.class);
            if (resp.getStatusCode().value() > worst.value()) worst = resp.getStatusCode();
            if (resp.getStatusCode().value() == 429) break;
        }
        assertThat(worst.value()).isEqualTo(429);
    }
}
```

Subagent notes for Scenario 7 (signCount replay): webauthn4j-test's `ClientPlatform.get(options)` internally advances the signCount counter. To reproduce a replay you can call `get()` once, capture the assertion JSON, post `/authentication/finish` twice — the second call hits our explicit `if (newCounter <= cred.getSignCount())` guard in Task 21's service. Adjust if the simulator's counter API differs.

Subagent notes for Scenario 8: the rate limit response on first hammer attempt may exceed 60 seconds wall time. If CI tightness is needed, lower the per-endpoint limit in `application-test.yml` via a `passkey.rate-limit.*` override (introduce one in Task 17 if necessary). Default Phase 1 keeps 300/min as in spec.

- [ ] **Step 4: Run**

```bash
./gradlew :passkey-app:test --tests Fido2EndToEndIT 2>&1 | tail -20
```

Expected: all 8 scenarios pass.

- [ ] **Step 5: Stage**

```bash
git add passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java \
        passkey-app/src/test/resources/application-test.yml \
        passkey-app/build.gradle.kts
```

---

## Task 27: Final DoD verification

**Files:** none (verification only)

- [ ] **Step 1: Full clean build + all tests**

```bash
./gradlew clean build 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`, all unit + integration tests pass, including Phase 0's 21 tests + Phase 1's new ones.

- [ ] **Step 2: Verify both apps still boot**

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/dod-admin.log 2>&1 &
./gradlew :passkey-app:bootRun --args='--spring.profiles.active=local --server.port=8082' > /tmp/dod-passkey.log 2>&1 &
sleep 30
curl -s http://localhost:8081/actuator/health | head -2
curl -s http://localhost:8082/actuator/health | head -2
curl -s http://localhost:8082/.well-known/jwks.json | head -3
pkill -f "AdminApplication|PasskeyApplication"
```

Expected: both health UP; JWKS responds with a single RSA key.

- [ ] **Step 3: Tag Phase 1 completion**

```bash
git tag -a phase-1-passkey-app-complete -m "Phase 1 (passkey-app) complete. Fido2EndToEndIT 8/8 passing."
```

- [ ] **Step 4: Commit nothing; tag is the artifact.**

---

## Self-Review

### Spec coverage

- §1.1 DoD: covered by Task 27 (full build + boot + JWKS).
- §2 tech stack: Task 1 (dependencies).
- §3 deferred items: noted in Tasks 8, 14, 25.
- §4 RP API endpoints: Tasks 18-23 (controllers + services).
- §4.5 JWKS: Task 24.
- §5 ID Token: Task 9.
- §6 data model: Tasks 2-7.
- §7 components: Tasks 8-24 collectively.
- §8 auth flow: Tasks 16-17 (filters).
- §9 rate limit policy: Task 17.
- §10 attestation policy: Tasks 10, 19.
- §11 ID Token impl: Task 9.
- §12 Fido2EndToEndIT: Task 26.
- §13 risks: addressed across tasks; followups remain in docs/superpowers/followups.

### Placeholder scan

No structural placeholders remain. Two API-name verification points remain (the subagent confirms against the installed `webauthn4j 0.29.0.RELEASE` artifact and adjusts if a method name drifted):

1. Task 19's `objectConverter.getCborConverter().writeValueAsBytes(record)` — name verified against the README quick-start of `/webauthn4j/webauthn4j`. If the installed version uses a different method (e.g. `serialize`), substitute the equivalent and surface the swap in the commit message.
2. Task 21's `record.setCounter(newCounter)` — same caveat. `CredentialRecordImpl` exposes this setter in 0.29.x; if renamed, use the equivalent and document.

Task 26's `Fido2TestAuthenticator.WebAuthnJsonShape.from*Credential` is a translation point: it materializes webauthn4j-test's `PublicKeyCredential` into the WebAuthn Level 3 JSON wire shape our server parses (Tasks 19/21). The expected path is `objectConverter.getJsonConverter().writeValueAsString(credential)` then re-read as `JsonNode`; if that doesn't round-trip cleanly, hand-build the JSON from the credential's raw components. Either approach is fine; pick the one that compiles against the actual classpath.

These are not "decision blockers" — they are name-verification moments. Subagent decides autonomously per the project rule.

### Type consistency

- `TenantContextHolder.set/get/clear` used identically across all filters and services.
- `Tenant.getRpId()`, `getRpName()`, `getAllowedOriginsJson()`, `getAttestationPolicyJson()` — Task 5 defines, Tasks 18-21 consume.
- `ApiKey.isActive(Instant)` — Task 6 defines, Task 16 consumes.
- `ChallengeStore.putRegistration/takeRegistration/putAuthentication/takeAuthentication` — Task 12 defines, Tasks 18-21 consume.
- `IdTokenIssuer.issue(tenantId, userHandle, credentialId, aaguid)` — Task 9 defines, Task 21 consumes.

### Scope check

Phase 1 single plan, 27 tasks. Comparable to Phase 0's 17. Larger but within single-plan range.

Done.
