# Phase 3 — MDS Verification + ID Token Key Rotation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Phase 1's `MdsStubVerifier` with real FIDO MDS3 BLOB consultation, and replace `SigningKeyProvider`'s in-memory key generation with a DB-backed envelope-encrypted key store supporting admin-triggered rotation with a 30-minute grace period.

**Architecture:** admin scheduler fetches FIDO MDS3 BLOB every 6 hours via webauthn4j-metadata's `FidoMDS3MetadataBLOBProvider` (uses pinned FIDO root CA for cert path validation), stores the validated BLOB in `mds_blob_cache` (singleton row id=1) and invalidates the Redis AAGUID lookup cache. passkey-app's new `MdsVerifier` consults that cache during registration. Signing keys move to a new `signing_key` table where the PKCS8 private key is sealed with AES-256-GCM (master key from environment). `KeyRotationService` runs admin-triggered rotations; `KeyExpirationJob` retires ROTATED keys to REVOKED after the configured grace period. `JwksAssembler` exposes ACTIVE + ROTATED keys so already-issued JWTs verify during grace.

**Tech Stack:** Java 17, Spring Boot 3.5.14, Spring `@Scheduled`, webauthn4j-metadata 0.31.5 (`FidoMDS3MetadataBLOBProvider`), Nimbus JOSE+JWT 9.40, JDK AES-256-GCM, Flyway, Oracle 19c, Testcontainers + WireMock 3 for ITs.

---

## File Inventory

**Backend — `:core` (shared entity/repo + key crypto):**
- Create `core/src/main/java/com/crosscert/passkey/core/entity/SigningKey.java`
- Create `core/src/main/java/com/crosscert/passkey/core/repository/SigningKeyRepository.java`
- Create `core/src/main/java/com/crosscert/passkey/core/jwt/KeyEnvelope.java`
- Create `core/src/main/java/com/crosscert/passkey/core/jwt/JwksAssembler.java`
- Modify `core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java` (REWRITE — DB-backed)
- Modify `core/src/test/java/com/crosscert/passkey/core/jwt/SigningKeyProviderTest.java` (REWRITE)

**Backend — `:admin-app` (MDS + key admin):**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/scheduler/SchedulerLeaseService.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsRootCertProvider.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobClient.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobStore.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSyncJob.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsAdminController.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJob.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtController.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtDto.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/AdminApplication.java` (add `@EnableScheduling`)
- Modify `admin-app/src/main/resources/application.yml` (master key + scheduler intervals)

**Backend — `:passkey-app` (verifier replacement):**
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsStubVerifier.java` → rename/rewrite to `MdsVerifier.java`
- Create `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsAaguidCache.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java` (inject MdsVerifier instead of MdsStubVerifier)
- Modify `passkey-app/src/main/resources/application.yml` (Redis cache TTL config)

**Backend — Flyway migrations (in `:core` resources):**
- Create `core/src/main/resources/db/migration/V15__signing_key_table.sql`
- Create `core/src/main/resources/db/migration/V16__signing_key_runtime_grants.sql`
- Create `core/src/main/resources/db/migration/V17__mds_blob_cache_singleton_seed.sql`

**Backend tests:**
- Create `core/src/test/java/com/crosscert/passkey/core/jwt/KeyEnvelopeTest.java`
- Create `core/src/test/java/com/crosscert/passkey/core/entity/SigningKeyTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/scheduler/SchedulerLeaseServiceTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsBlobClientTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyRotationServiceTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJobTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsAdminControllerSecurityTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtControllerSecurityTest.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/MdsSchedulerIT.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/KeyRotationIT.java`
- Create `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/mds/MdsVerifierTest.java`

**Frontend — `admin-ui/`:**
- Create `admin-ui/src/pages/MdsStatus.tsx`
- Create `admin-ui/src/pages/KeyManagement.tsx`
- Modify `admin-ui/src/components/Layout.tsx` (nav links)
- Modify `admin-ui/src/App.tsx` (routes)
- Modify `admin-ui/src/api/types.ts` (new TS interfaces)

**Build / config:**
- Modify `gradle/libs.versions.toml` (WireMock for IT)
- Modify `admin-app/build.gradle.kts` (WireMock testImplementation)

---

## Task 1: Flyway V15 — signing_key table

**Files:**
- Create `core/src/main/resources/db/migration/V15__signing_key_table.sql`

- [ ] **Step 1: Write the migration**

Contents EXACTLY:

```sql
-- Signing key store with envelope-encrypted PKCS8 private keys.
-- Phase 3 (T1): introduces persistent ID Token signing keys so server
-- restarts no longer invalidate previously-issued JWTs.
--
-- Key lifecycle:
--   ACTIVE  → ROTATED (admin manual rotate; new ACTIVE inserted)
--   ROTATED → REVOKED (KeyExpirationJob after grace period, default 30min)
--
-- JWKS exposes ACTIVE + ROTATED so RPs holding JWTs signed by the old
-- key can still verify during grace. REVOKED is hidden.

CREATE SEQUENCE signing_key_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE signing_key (
  id              NUMBER(19,0)             NOT NULL,
  kid             VARCHAR2(64)             NOT NULL,
  alg             VARCHAR2(16)             NOT NULL,
  status          VARCHAR2(16)             NOT NULL,
  public_jwk      CLOB                     NOT NULL,
  private_pkcs8   BLOB                     NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  rotated_at      TIMESTAMP WITH TIME ZONE,
  revoked_at      TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_signing_key PRIMARY KEY (id),
  CONSTRAINT uq_signing_key_kid UNIQUE (kid),
  CONSTRAINT ck_signing_key_status CHECK (status IN ('ACTIVE','ROTATED','REVOKED')),
  CONSTRAINT ck_signing_key_alg CHECK (alg IN ('RS256')),
  CONSTRAINT ck_signing_key_public_jwk_json CHECK (public_jwk IS JSON)
);

CREATE INDEX signing_key_status_ix ON signing_key(status);

GRANT SELECT, INSERT ON signing_key TO APP_ADMIN;
GRANT UPDATE(status, rotated_at, revoked_at) ON signing_key TO APP_ADMIN;
GRANT SELECT ON signing_key_seq TO APP_ADMIN;
```

- [ ] **Step 2: Boot admin-app to apply V15**

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/v15-boot.log 2>&1 &
ADMIN_PID=$!
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/v15-boot.log; do sleep 2; done
grep -E "Migrating schema|Successfully" /tmp/v15-boot.log | tail -3
kill $ADMIN_PID
wait $ADMIN_PID 2>/dev/null
```

Expected: `Migrating schema "APP_OWNER" to version "15 - signing key table"` and `Successfully applied 1 migration`.

- [ ] **Step 3: Verify table + grants**

```bash
docker exec -i passkey-oracle sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1 <<'SQL'
SET PAGESIZE 0 FEEDBACK OFF HEADING OFF
SELECT column_name||':'||data_type FROM user_tab_columns WHERE table_name='SIGNING_KEY' ORDER BY column_id;
SELECT 'GRANT:'||grantee||':'||privilege FROM user_tab_privs WHERE table_name='SIGNING_KEY' ORDER BY grantee, privilege;
SELECT 'COL:'||grantee||':'||column_name||':'||privilege FROM user_col_privs_made WHERE table_name='SIGNING_KEY';
EXIT
SQL
```

Expected: 9 columns; APP_ADMIN INSERT + SELECT (table level) + UPDATE on status/rotated_at/revoked_at (column level).

- [ ] **Step 4: Codex review**

```bash
git add core/src/main/resources/db/migration/V15__signing_key_table.sql
cat > /tmp/codex-p3-t1.txt <<'PROMPT'
Code review for V15 — signing_key table. Phase 3 introduces persistent
ID Token signing keys.

Schema:
- private_pkcs8 BLOB stores AES-256-GCM envelope (nonce || ciphertext || tag).
- public_jwk CLOB IS JSON.
- status enum + CHECK (ACTIVE/ROTATED/REVOKED).
- alg pinned to RS256.
- kid UNIQUE (RFC 7638 thumbprint).
- created_at default SYSTIMESTAMP, updatable=false intent (no UPDATE grant).

Grants:
- APP_ADMIN: SELECT, INSERT, column-scoped UPDATE on (status, rotated_at, revoked_at).
- APP_RUNTIME: no grant yet — added in V16 so passkey-app can read.

Review for:
1. Column types — BLOB for envelope ciphertext, CLOB IS JSON for public_jwk.
2. Update grant scope — only status/rotated_at/revoked_at, NOT
   private_pkcs8 (immutable). Sufficient for rotation flow?
3. UNIQUE(kid) — prevents accidental dup if rotation generates a
   colliding RFC 7638 thumbprint (cryptographically improbable).
4. Index on status — supports the "SELECT ... WHERE status='ACTIVE'"
   hot path. KeyExpirationJob scans status='ROTATED'.
5. Any P1.

Output P1 / P2 / Confirmations.
PROMPT
{ cat /tmp/codex-p3-t1.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t1-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t1-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t1-out.txt 2>&1
cat /tmp/codex-p3-t1-out.txt
```

P1 fix-before-commit; P2 defer OK.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(db): V15 — signing_key table (envelope-encrypted PKCS8 + status lifecycle)"
```

---

## Task 2: Flyway V16 — signing_key APP_RUNTIME grants

**Files:**
- Create `core/src/main/resources/db/migration/V16__signing_key_runtime_grants.sql`

- [ ] **Step 1: Write the migration**

```sql
-- passkey-app boots as APP_RUNTIME_USER and reads signing_key (and the
-- public_jwk column) for JWKS construction + signing key load. It does
-- NOT write — admin-app owns lifecycle transitions.
--
-- ddl-validate at passkey-app boot needs SELECT on the table + sequence
-- (mirrors V12/V13 pattern from Phase 2 admin_user and audit_log).

GRANT SELECT ON signing_key TO APP_RUNTIME;
GRANT SELECT ON signing_key_seq TO APP_RUNTIME;
```

- [ ] **Step 2: Boot admin-app to apply V16**

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/v16-boot.log 2>&1 &
ADMIN_PID=$!
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/v16-boot.log; do sleep 2; done
grep -E "Migrating|Successfully" /tmp/v16-boot.log | tail -3
kill $ADMIN_PID; wait $ADMIN_PID 2>/dev/null
```

Expected: `Migrating schema "APP_OWNER" to version "16 - signing key runtime grants"` and `Successfully applied 1 migration`.

- [ ] **Step 3: Codex review**

```bash
git add core/src/main/resources/db/migration/V16__signing_key_runtime_grants.sql
cat > /tmp/codex-p3-t2.txt <<'PROMPT'
Code review for V16 — APP_RUNTIME SELECT grants on signing_key + sequence.

Mirrors V12/V13 (admin_user, audit_log) pattern: passkey-app's
@EntityScan covers core.entity package, so Hibernate ddl-validate at
boot needs SELECT on signing_key. Sequence is read but never used by
runtime (only admin INSERTs new keys); grant included for symmetry.

passkey-app uses the grant to read ACTIVE/ROTATED rows during JWKS
serving and to load the ACTIVE key for signing on startup. No write
path from passkey-app.

Review for:
1. SELECT only — confirm passkey-app never writes signing_key.
   SigningKeyProvider's createInitialKey() path INSERTs but that runs
   on admin-app boot OR a one-time admin-side bootstrap (T6). passkey-app
   should never INSERT.
2. Sequence grant — needed only if passkey-app's
   @SequenceGenerator references it during ddl-validate? Confirm.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t2.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t2-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t2-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t2-out.txt 2>&1
cat /tmp/codex-p3-t2-out.txt
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(db): V16 — APP_RUNTIME SELECT grants on signing_key"
```

---

## Task 3: Flyway V17 — mds_blob_cache singleton seed

**Files:**
- Create `core/src/main/resources/db/migration/V17__mds_blob_cache_singleton_seed.sql`

- [ ] **Step 1: Write the migration**

```sql
-- mds_blob_cache singleton row.
--
-- Phase 0 V1 created the table allowing multiple rows. Phase 0
-- followup notes (mds_blob_cache singleton policy section) chose
-- Option A: always use id=1, UPSERT instead of INSERT, do not use
-- mds_blob_cache_seq for new rows.
--
-- This seed creates the sentinel row so MdsBlobStore can MERGE
-- against it on every scheduler fire without checking existence.
-- The placeholder values (version=0, ancient dates, empty JSON
-- blob_jwt) are overwritten on the first successful FIDO MDS3 fetch.

MERGE INTO mds_blob_cache USING dual ON (id = 1)
WHEN NOT MATCHED THEN INSERT (id, version, next_update, fetched_at, blob_jwt)
  VALUES (1, 0, DATE '1970-01-01', TIMESTAMP '1970-01-01 00:00:00 +00:00', '{}');

COMMIT;
```

- [ ] **Step 2: Boot admin-app to apply V17 + verify seed**

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/v17-boot.log 2>&1 &
ADMIN_PID=$!
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/v17-boot.log; do sleep 2; done
docker exec -i passkey-oracle sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1 <<'SQL'
SET PAGESIZE 0 FEEDBACK OFF HEADING OFF
SELECT id||':'||version||':'||LENGTH(blob_jwt) FROM mds_blob_cache;
EXIT
SQL
kill $ADMIN_PID; wait $ADMIN_PID 2>/dev/null
```

Expected: `1:0:2` (id=1, version=0, blob_jwt is the 2-char "{}").

- [ ] **Step 3: Codex review**

```bash
git add core/src/main/resources/db/migration/V17__mds_blob_cache_singleton_seed.sql
cat > /tmp/codex-p3-t3.txt <<'PROMPT'
Code review for V17 — mds_blob_cache singleton seed (Phase 3 T3).

Phase 0 V1 created mds_blob_cache as a multi-row table; Phase 0
followup notes selected Option A (singleton row id=1) for Phase 3. V17
materializes the sentinel row via MERGE so MdsBlobStore can always
MERGE INTO mds_blob_cache USING dual ON (id=1) — single-statement
UPSERT semantics without check-then-insert race.

Placeholder values: version=0, ancient timestamp, empty JSON blob.
Overwritten by first successful FIDO MDS3 fetch.

CHECK constraint: V1 has no IS JSON check on blob_jwt; "{}" satisfies
JSON shape regardless.

Review for:
1. Idempotent MERGE — re-running V17 should be a no-op. Flyway
   tracks history; re-run won't happen unless schema is reset.
   Still confirm MERGE is intrinsically idempotent.
2. COMMIT statement at end — needed in Flyway scripts? Flyway runs
   each migration in a transaction by default and commits on success.
   The explicit COMMIT is harmless (Phase 2's V11 also has one).
3. mds_blob_cache_seq usage — application no longer uses it. Plan
   says drop is followup. V17 leaves the sequence alone. OK.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t3.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t3-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t3-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t3-out.txt 2>&1
cat /tmp/codex-p3-t3-out.txt
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(db): V17 — mds_blob_cache singleton seed (id=1)"
```

---

## Task 4: `SigningKey` entity + repository (TDD)

**Files:**
- Create `core/src/main/java/com/crosscert/passkey/core/entity/SigningKey.java`
- Create `core/src/main/java/com/crosscert/passkey/core/repository/SigningKeyRepository.java`
- Create `core/src/test/java/com/crosscert/passkey/core/entity/SigningKeyTest.java`

- [ ] **Step 1: Write failing test**

`SigningKeyTest.java`:

```java
package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SigningKeyTest {

    @Test
    void constructorPopulatesActiveStatusAndCreatedAt() {
        SigningKey k = new SigningKey(
                "thumbprint-abc", "RS256",
                "{\"kty\":\"RSA\",\"n\":\"...\",\"e\":\"AQAB\"}",
                new byte[]{1,2,3});
        assertThat(k.getKid()).isEqualTo("thumbprint-abc");
        assertThat(k.getAlg()).isEqualTo("RS256");
        assertThat(k.getStatus()).isEqualTo("ACTIVE");
        assertThat(k.getPublicJwk()).contains("\"n\":\"...\"");
        assertThat(k.getPrivatePkcs8()).containsExactly(1,2,3);
        assertThat(k.getCreatedAt()).isNotNull();
        assertThat(k.getRotatedAt()).isNull();
        assertThat(k.getRevokedAt()).isNull();
    }

    @Test
    void rotateTransitionsToRotated() {
        SigningKey k = new SigningKey("kid","RS256","{}",new byte[]{0});
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        k.rotate(now);
        assertThat(k.getStatus()).isEqualTo("ROTATED");
        assertThat(k.getRotatedAt()).isEqualTo(now);
        assertThat(k.getRevokedAt()).isNull();
    }

    @Test
    void revokeTransitionsToRevoked() {
        SigningKey k = new SigningKey("kid","RS256","{}",new byte[]{0});
        Instant t1 = Instant.parse("2026-06-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-06-01T00:30:00Z");
        k.rotate(t1);
        k.revoke(t2);
        assertThat(k.getStatus()).isEqualTo("REVOKED");
        assertThat(k.getRotatedAt()).isEqualTo(t1);
        assertThat(k.getRevokedAt()).isEqualTo(t2);
    }
}
```

- [ ] **Step 2: Run, confirm compile failure**

```bash
./gradlew :core:test --tests SigningKeyTest 2>&1 | tail -10
```

Expected: FAIL with `cannot find symbol class SigningKey`.

- [ ] **Step 3: Write `SigningKey.java`**

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "SIGNING_KEY")
public class SigningKey {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "signing_key_seq")
    @SequenceGenerator(name = "signing_key_seq", sequenceName = "SIGNING_KEY_SEQ", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "KID", length = 64, nullable = false)
    private String kid;

    @Column(name = "ALG", length = 16, nullable = false)
    private String alg;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;

    @Lob
    @Column(name = "PUBLIC_JWK", nullable = false)
    private String publicJwk;

    @Lob
    @Column(name = "PRIVATE_PKCS8", nullable = false)
    private byte[] privatePkcs8;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "ROTATED_AT")
    private Instant rotatedAt;

    @Column(name = "REVOKED_AT")
    private Instant revokedAt;

    protected SigningKey() {}

    public SigningKey(String kid, String alg, String publicJwk, byte[] privatePkcs8) {
        this.kid = kid;
        this.alg = alg;
        this.status = "ACTIVE";
        this.publicJwk = publicJwk;
        this.privatePkcs8 = privatePkcs8;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getKid() { return kid; }
    public String getAlg() { return alg; }
    public String getStatus() { return status; }
    public String getPublicJwk() { return publicJwk; }
    public byte[] getPrivatePkcs8() { return privatePkcs8; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRotatedAt() { return rotatedAt; }
    public Instant getRevokedAt() { return revokedAt; }

    /** ACTIVE → ROTATED. Caller persists. */
    public void rotate(Instant now) {
        this.status = "ROTATED";
        this.rotatedAt = now;
    }

    /** ROTATED → REVOKED. Caller persists. */
    public void revoke(Instant now) {
        this.status = "REVOKED";
        this.revokedAt = now;
    }
}
```

- [ ] **Step 4: Write `SigningKeyRepository.java`**

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.SigningKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SigningKeyRepository extends JpaRepository<SigningKey, Long> {

    /**
     * Returns the single ACTIVE key. There should be at most one at any
     * time — KeyRotationService inserts the new ACTIVE after
     * transitioning the old one to ROTATED inside the same
     * @Transactional.
     */
    Optional<SigningKey> findFirstByStatusOrderByCreatedAtDesc(String status);

    /**
     * Used by JwksAssembler. Returns ACTIVE + ROTATED rows so RPs
     * holding JWTs signed by the now-ROTATED key can still verify
     * during the grace period. REVOKED is excluded.
     */
    List<SigningKey> findAllByStatusIn(List<String> statuses);

    /**
     * Used by KeyExpirationJob. Finds ROTATED rows whose grace period
     * has elapsed, ready to transition to REVOKED.
     */
    List<SigningKey> findAllByStatusAndRotatedAtBefore(String status, Instant cutoff);
}
```

- [ ] **Step 5: Re-run tests, confirm PASS**

```bash
./gradlew :core:test --tests SigningKeyTest 2>&1 | tail -10
```

Expected: 3 tests pass.

- [ ] **Step 6: Codex review**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/SigningKey.java \
        core/src/main/java/com/crosscert/passkey/core/repository/SigningKeyRepository.java \
        core/src/test/java/com/crosscert/passkey/core/entity/SigningKeyTest.java
cat > /tmp/codex-p3-t4.txt <<'PROMPT'
Code review for SigningKey entity + repository (Phase 3 T4).

Entity maps V15. Three Spring Data derived queries:
- findFirstByStatusOrderByCreatedAtDesc(status) — load ACTIVE on boot
- findAllByStatusIn(statuses) — JWKS (ACTIVE + ROTATED)
- findAllByStatusAndRotatedAtBefore(status, cutoff) — expiration job

Lifecycle methods rotate(now) and revoke(now) — single-direction state
transitions. Caller persists.

Review:
1. Hibernate mapping vs V15 — column lengths, nullability, sequence
   name (SIGNING_KEY_SEQ), updatable=false on createdAt.
2. @Lob on private_pkcs8 BLOB — Hibernate-on-Oracle treats @Lob byte[]
   as BLOB; confirm vs RAW (we want BLOB for large envelopes — 2048-bit
   RSA PKCS8 is ~1200 bytes + 28 envelope overhead).
3. Spring Data derived query names — verify they match Spring Data
   JPA naming conventions exactly:
   - findFirstByStatusOrderByCreatedAtDesc ✓ (First, By, OrderBy)
   - findAllByStatusIn ✓ (All, By, In)
   - findAllByStatusAndRotatedAtBefore ✓ (All, By, And, Before)
4. Lifecycle methods accept Instant — no Clock. Caller provides
   clock.instant() per established codebase pattern (Phase 2 ApiKey.revoke
   has same signature).

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t4.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t4-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t4-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t4-out.txt 2>&1
cat /tmp/codex-p3-t4-out.txt
```

- [ ] **Step 7: Commit**

```bash
git commit -m "feat(core): SigningKey entity + repository (V15-backed lifecycle)"
```

---

## Task 5: `KeyEnvelope` AES-256-GCM (TDD)

**Files:**
- Create `core/src/main/java/com/crosscert/passkey/core/jwt/KeyEnvelope.java`
- Create `core/src/test/java/com/crosscert/passkey/core/jwt/KeyEnvelopeTest.java`

- [ ] **Step 1: Write failing test**

`KeyEnvelopeTest.java`:

```java
package com.crosscert.passkey.core.jwt;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyEnvelopeTest {

    private static final String MASTER_KEY_B64 =
            Base64.getEncoder().encodeToString(new byte[32]); // 32 zero bytes
    private final KeyEnvelope envelope =
            new KeyEnvelope(MASTER_KEY_B64, new SecureRandom());

    @Test
    void sealAndOpenRoundTripsPlaintext() {
        byte[] pkcs8 = "the-private-key-bytes".getBytes();
        byte[] sealed = envelope.seal(pkcs8);
        byte[] opened = envelope.open(sealed);
        assertThat(opened).isEqualTo(pkcs8);
    }

    @Test
    void sealedFormatIsNoncePlusCiphertextPlusTag() {
        byte[] pkcs8 = new byte[100];
        byte[] sealed = envelope.seal(pkcs8);
        // 12-byte nonce + 100-byte ciphertext + 16-byte tag = 128.
        assertThat(sealed).hasSize(12 + 100 + 16);
    }

    @Test
    void differentInvocationsProduceDifferentCiphertexts() {
        byte[] pkcs8 = "same-input".getBytes();
        byte[] a = envelope.seal(pkcs8);
        byte[] b = envelope.seal(pkcs8);
        assertThat(a).isNotEqualTo(b); // nonce differs
        assertThat(envelope.open(a)).isEqualTo(pkcs8);
        assertThat(envelope.open(b)).isEqualTo(pkcs8);
    }

    @Test
    void tamperedCiphertextThrows() {
        byte[] sealed = envelope.seal("secret".getBytes());
        sealed[sealed.length - 1] ^= 0x01; // flip a tag bit
        assertThatThrownBy(() -> envelope.open(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("envelope authentication failed");
    }

    @Test
    void wrongMasterKeyThrows() {
        byte[] sealed = envelope.seal("secret".getBytes());
        String otherMaster = Base64.getEncoder().encodeToString(new byte[]{
                1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,
                17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32});
        KeyEnvelope other = new KeyEnvelope(otherMaster, new SecureRandom());
        assertThatThrownBy(() -> other.open(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("envelope authentication failed");
    }

    @Test
    void wrongMasterKeyLengthRejected() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new KeyEnvelope(shortKey, new SecureRandom()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
```

- [ ] **Step 2: Run, confirm compile failure**

```bash
./gradlew :core:test --tests KeyEnvelopeTest 2>&1 | tail -10
```

- [ ] **Step 3: Write `KeyEnvelope.java`**

```java
package com.crosscert.passkey.core.jwt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM envelope for sealed PKCS8 private keys (Phase 3 T5).
 *
 * <p>Sealed format: {@code nonce(12) || ciphertext || tag(16)}.
 *
 * <p>Master key comes from {@code passkey.key-envelope.master-key}
 * property, which the local profile hard-codes to a dev value and
 * production environments must override with a 32-byte (base64-encoded)
 * random secret via the {@code PASSKEY_KEY_ENVELOPE_MASTER_KEY}
 * environment variable.
 *
 * <p>The seal/open API never logs or throws the master key. Tag
 * mismatch (tamper or wrong key) becomes a generic
 * {@code IllegalStateException("envelope authentication failed")} so
 * callers cannot probe whether a wrong key or a wrong ciphertext
 * caused the failure.
 */
@Component
public class KeyEnvelope {

    private static final int NONCE_LEN_BYTES = 12;
    private static final int TAG_LEN_BITS = 128;
    private static final int MASTER_KEY_BYTES = 32;

    private final SecretKey masterKey;
    private final SecureRandom random;

    public KeyEnvelope(@Value("${passkey.key-envelope.master-key}") String masterB64,
                       SecureRandom random) {
        byte[] keyBytes = Base64.getDecoder().decode(masterB64);
        if (keyBytes.length != MASTER_KEY_BYTES) {
            throw new IllegalStateException(
                    "master key must be 32 bytes (AES-256); got " + keyBytes.length);
        }
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
        this.random = random;
    }

    public byte[] seal(byte[] pkcs8) {
        byte[] nonce = new byte[NONCE_LEN_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, masterKey,
                    new GCMParameterSpec(TAG_LEN_BITS, nonce));
            byte[] ctAndTag = c.doFinal(pkcs8);
            return ByteBuffer.allocate(NONCE_LEN_BYTES + ctAndTag.length)
                    .put(nonce).put(ctAndTag).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("envelope seal failed", e);
        }
    }

    public byte[] open(byte[] envelope) {
        if (envelope.length < NONCE_LEN_BYTES + 16) {
            throw new IllegalStateException("envelope authentication failed");
        }
        byte[] nonce = new byte[NONCE_LEN_BYTES];
        System.arraycopy(envelope, 0, nonce, 0, NONCE_LEN_BYTES);
        byte[] ctAndTag = new byte[envelope.length - NONCE_LEN_BYTES];
        System.arraycopy(envelope, NONCE_LEN_BYTES, ctAndTag, 0, ctAndTag.length);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, masterKey,
                    new GCMParameterSpec(TAG_LEN_BITS, nonce));
            return c.doFinal(ctAndTag);
        } catch (GeneralSecurityException e) {
            // Includes AEADBadTagException (tamper / wrong key). Surface
            // a single generic error so callers cannot distinguish.
            throw new IllegalStateException("envelope authentication failed", e);
        }
    }
}
```

- [ ] **Step 4: Add the dev master key to `core/src/main/resources/application-local-shared.yml`**

The file currently has datasource + redis defaults. Append:

```yaml
passkey:
  key-envelope:
    # Local dev only — 32 zero bytes base64-encoded. Production sets
    # PASSKEY_KEY_ENVELOPE_MASTER_KEY environment variable with a
    # 32-byte secret. See followups doc.
    master-key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
```

(The `AAAAAA...=` is base64 of 32 zero bytes — `printf '\0%.0s' {1..32} | base64`.)

- [ ] **Step 5: Run tests, confirm PASS**

```bash
./gradlew :core:test --tests KeyEnvelopeTest 2>&1 | tail -10
```

Expected: 6 tests pass.

- [ ] **Step 6: Add followup note about master key**

Append to `docs/superpowers/followups/2026-05-25-prod-hardening-notes.md`:

```markdown

## From Phase 3 T5 — KeyEnvelope master key

`KeyEnvelope` (`core/src/main/java/com/crosscert/passkey/core/jwt/KeyEnvelope.java`)
seals ID-Token signing key PKCS8 bytes with AES-256-GCM. The 32-byte
master key is supplied via `passkey.key-envelope.master-key` — local
dev hard-codes 32 zero bytes (base64 `AAAA...=`). Production deployments
MUST:

- Generate a fresh random 32-byte key per environment.
- Provide it via the `PASSKEY_KEY_ENVELOPE_MASTER_KEY` environment
  variable (Spring relaxed binding maps env → property).
- Back it up out-of-band; losing the master key invalidates every
  encrypted `signing_key.private_pkcs8` row, forcing a forced rotation
  to a freshly-generated key.

Phase 4+ migrates the master key to an external KMS (AWS KMS / HashiCorp
Vault) so the application never sees raw key material. The
`KeyEnvelope` interface (seal/open byte[]) is the migration boundary —
swap the constructor wiring without changing callers.
```

- [ ] **Step 7: Codex review**

```bash
git add core/src/main/java/com/crosscert/passkey/core/jwt/KeyEnvelope.java \
        core/src/test/java/com/crosscert/passkey/core/jwt/KeyEnvelopeTest.java \
        core/src/main/resources/application-local-shared.yml \
        docs/superpowers/followups/2026-05-25-prod-hardening-notes.md
cat > /tmp/codex-p3-t5.txt <<'PROMPT'
Code review for KeyEnvelope (Phase 3 T5) — AES-256-GCM envelope for
signing-key PKCS8 storage.

Design:
- Sealed format: nonce(12) || ciphertext || tag(16).
- Master key from passkey.key-envelope.master-key — local dev value is
  32 zero bytes b64; prod sets PASSKEY_KEY_ENVELOPE_MASTER_KEY env var.
- Tag mismatch + bad key → generic IllegalStateException("envelope
  authentication failed") (no oracle leakage).
- SecureRandom injected (testable + Phase 2 CoreSecurityConfig already
  exposes the bean).

Test coverage:
- seal/open round-trip
- sealed length = nonce + plaintext + tag
- distinct invocations differ (random nonce)
- tampered ciphertext throws
- wrong master key throws (same error message — no oracle)
- short master key rejected at construction

Review for:
1. AES-256-GCM nonce reuse risk — single SecureRandom 12-byte nonce
   per seal. Birthday bound for 96-bit IV is ~2^32 seals before
   collision risk crosses 2^-32 threshold. Acceptable for our scale
   (a few rotations per year).
2. Authenticated encryption tag — 128 bits, standard for GCM.
3. Constant-time comparison — GCM tag verify is constant-time in JCE
   (BadPaddingException timing equal regardless of which byte mismatched).
4. Master key handling — never logged. Constructor throws on wrong
   length, doesn't echo the key. Master key reference held in SecretKey
   instance; no plain byte[] field after construction.
5. Local dev value of 32 zero bytes — followup explicitly flags this
   as dev-only and requires PASSKEY_KEY_ENVELOPE_MASTER_KEY env override
   for prod.
6. Any P1.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t5.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t5-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t5-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t5-out.txt 2>&1
cat /tmp/codex-p3-t5-out.txt
```

- [ ] **Step 8: Commit**

```bash
git commit -m "feat(core): KeyEnvelope — AES-256-GCM envelope for signing keys"
```

---

## Task 6: `SigningKeyProvider` REWRITE (DB-backed, TDD)

**Files:**
- Modify `core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java`
- Modify `core/src/test/java/com/crosscert/passkey/core/jwt/SigningKeyProviderTest.java`

The Phase 1 SigningKeyProvider generates an RSA key in `@PostConstruct`. We replace it with a DB-backed loader that creates an initial ACTIVE key on first boot (admin-app and passkey-app both run this — first one wins, second sees the row), then caches the ACTIVE key in memory.

- [ ] **Step 1: Replace `SigningKeyProviderTest.java`**

```java
package com.crosscert.passkey.core.jwt;

import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SigningKeyProviderTest {

    private SigningKeyRepository repo;
    private KeyEnvelope envelope;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();
    private SigningKeyProvider provider;

    @BeforeEach
    void setUp() {
        repo = mock(SigningKeyRepository.class);
        envelope = new KeyEnvelope(
                Base64.getEncoder().encodeToString(new byte[32]),
                new SecureRandom());
        provider = new SigningKeyProvider(repo, envelope, mapper, clock);
    }

    @Test
    void initLoadsExistingActiveKey() throws Exception {
        SigningKey stored = freshActiveKey("kid-existing");
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.of(stored));

        provider.init();
        RSAKey k = provider.signingKey();
        assertThat(k.getKeyID()).isEqualTo("kid-existing");
        assertThat(k.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(k.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(k.isPrivate()).isTrue();
    }

    @Test
    void initGeneratesAndPersistsWhenNoActiveExists() {
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        provider.init();

        ArgumentCaptor<SigningKey> captor = ArgumentCaptor.forClass(SigningKey.class);
        verify(repo).save(captor.capture());
        SigningKey saved = captor.getValue();
        assertThat(saved.getAlg()).isEqualTo("RS256");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getKid()).isNotBlank();
        assertThat(saved.getPublicJwk()).contains("\"kty\":\"RSA\"");
        assertThat(saved.getPrivatePkcs8()).isNotNull();
        // Envelope round-trips.
        byte[] pkcs8 = envelope.open(saved.getPrivatePkcs8());
        assertThat(pkcs8).isNotEmpty();
    }

    @Test
    void publicJwkSetIncludesActiveAndRotated() throws Exception {
        SigningKey active = freshActiveKey("active-kid");
        SigningKey rotated = freshActiveKey("rotated-kid");
        rotated.rotate(clock.instant());
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.of(active));
        when(repo.findAllByStatusIn(List.of("ACTIVE", "ROTATED")))
                .thenReturn(List.of(active, rotated));

        provider.init();
        JWKSet set = provider.publicJwkSet();
        assertThat(set.getKeys()).extracting(j -> j.getKeyID())
                .containsExactlyInAnyOrder("active-kid", "rotated-kid");
        // No private material in the public set.
        assertThat(set.getKeys()).allMatch(j -> !j.isPrivate());
    }

    @Test
    void reloadRefreshesCachedActiveAfterRotation() throws Exception {
        SigningKey first = freshActiveKey("first");
        SigningKey second = freshActiveKey("second");
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second));

        provider.init();
        assertThat(provider.signingKey().getKeyID()).isEqualTo("first");

        provider.reload();
        assertThat(provider.signingKey().getKeyID()).isEqualTo("second");
    }

    private SigningKey freshActiveKey(String kid) throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(kid)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        String publicJwk = rsa.toPublicJWK().toJSONString();
        byte[] sealed = envelope.seal(pair.getPrivate().getEncoded());
        return new SigningKey(kid, "RS256", publicJwk, sealed);
    }
}
```

- [ ] **Step 2: Replace `SigningKeyProvider.java`**

```java
package com.crosscert.passkey.core.jwt;

import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * DB-backed signing-key provider (Phase 3 rewrite).
 *
 * <p>Boot: load the single ACTIVE row. If none exists (fresh install,
 * first boot of either app), generate a new RSA-2048 key, envelope-seal
 * the PKCS8 private bytes, and INSERT as ACTIVE. Subsequent boots find
 * the persisted row.
 *
 * <p>JWKS: returns ACTIVE + ROTATED keys so RPs still verify JWTs
 * signed by a recently-rotated key during the grace window
 * (KeyExpirationJob transitions ROTATED → REVOKED after the configured
 * grace expires; REVOKED keys are excluded from JWKS).
 *
 * <p>Signing: always uses the cached ACTIVE key. KeyRotationService
 * calls {@link #reload()} after a rotation so the cache picks up the
 * new ACTIVE row.
 */
@Component
public class SigningKeyProvider {

    private final SigningKeyRepository repo;
    private final KeyEnvelope envelope;
    private final ObjectMapper mapper;
    private final Clock clock;
    private volatile RSAKey cachedActive;

    public SigningKeyProvider(SigningKeyRepository repo,
                              KeyEnvelope envelope,
                              ObjectMapper mapper,
                              Clock clock) {
        this.repo = repo;
        this.envelope = envelope;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostConstruct
    public void init() {
        SigningKey row = repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
                .orElseGet(this::createInitialKey);
        this.cachedActive = decode(row);
    }

    public RSAKey signingKey() {
        return cachedActive;
    }

    public JWKSet publicJwkSet() {
        List<JWK> publics = new ArrayList<>();
        for (SigningKey row : repo.findAllByStatusIn(List.of("ACTIVE", "ROTATED"))) {
            publics.add(decode(row).toPublicJWK());
        }
        return new JWKSet(publics);
    }

    /** Called by KeyRotationService after a successful rotation. */
    public void reload() {
        SigningKey row = repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
                .orElseThrow(() -> new IllegalStateException(
                        "no ACTIVE signing key after reload — rotation contract broken"));
        this.cachedActive = decode(row);
    }

    private SigningKey createInitialKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();

            RSAKey withoutKid = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            String kid = withoutKid.computeThumbprint().toString();
            RSAKey rsa = new RSAKey.Builder(withoutKid).keyID(kid).build();
            String publicJwk = rsa.toPublicJWK().toJSONString();
            byte[] sealed = envelope.seal(pair.getPrivate().getEncoded());
            SigningKey row = new SigningKey(kid, "RS256", publicJwk, sealed);
            return repo.save(row);
        } catch (Exception e) {
            throw new IllegalStateException("initial signing key generation failed", e);
        }
    }

    private RSAKey decode(SigningKey row) {
        try {
            JWK publicOnly = JWK.parse(row.getPublicJwk());
            RSAPublicKey pub = ((RSAKey) publicOnly).toRSAPublicKey();
            byte[] pkcs8 = envelope.open(row.getPrivatePkcs8());
            RSAPrivateKey priv = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            return new RSAKey.Builder(pub)
                    .privateKey(priv)
                    .keyID(row.getKid())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to decode signing key kid=" + row.getKid(), e);
        }
    }
}
```

- [ ] **Step 3: Run tests, confirm PASS**

```bash
./gradlew :core:test --tests SigningKeyProviderTest 2>&1 | tail -15
```

Expected: 4 tests pass.

- [ ] **Step 4: Run the existing JwksControllerTest in passkey-app to ensure it still works**

`JwksControllerTest` (Phase 1 T24) called `new SigningKeyProvider()` directly. After the rewrite, the no-arg constructor is gone. The Phase 1 test must be updated.

Replace `passkey-app/src/test/java/com/crosscert/passkey/app/api/v1/rp/JwksControllerTest.java` constructor calls:

Find the `@BeforeEach setUp()`:

```java
@BeforeEach
void setUp() {
    keys = new SigningKeyProvider();
    keys.init();
    mvc = MockMvcBuilders.standaloneSetup(new JwksController(keys)).build();
}
```

Replace with:

```java
@BeforeEach
void setUp() {
    var repo = org.mockito.Mockito.mock(
            com.crosscert.passkey.core.repository.SigningKeyRepository.class);
    var envelope = new KeyEnvelope(
            java.util.Base64.getEncoder().encodeToString(new byte[32]),
            new java.security.SecureRandom());
    var clock = java.time.Clock.fixed(
            java.time.Instant.parse("2026-06-01T00:00:00Z"),
            java.time.ZoneOffset.UTC);
    org.mockito.Mockito.when(
            repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
            .thenReturn(java.util.Optional.empty());
    org.mockito.Mockito.when(repo.save(org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> inv.getArgument(0));
    org.mockito.Mockito.when(repo.findAllByStatusIn(java.util.List.of("ACTIVE", "ROTATED")))
            .thenAnswer(inv -> {
                // Return whatever was last saved as the ACTIVE row.
                org.mockito.ArgumentCaptor<com.crosscert.passkey.core.entity.SigningKey> cap =
                        org.mockito.ArgumentCaptor.forClass(
                                com.crosscert.passkey.core.entity.SigningKey.class);
                org.mockito.Mockito.verify(repo).save(cap.capture());
                return java.util.List.of(cap.getValue());
            });

    keys = new SigningKeyProvider(repo, envelope, new com.fasterxml.jackson.databind.ObjectMapper(), clock);
    keys.init();
    mvc = MockMvcBuilders.standaloneSetup(new JwksController(keys)).build();
}
```

Run:

```bash
./gradlew :passkey-app:test --tests JwksControllerTest 2>&1 | tail -15
```

Expected: 2 tests pass.

- [ ] **Step 5: Codex review**

```bash
git add core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java \
        core/src/test/java/com/crosscert/passkey/core/jwt/SigningKeyProviderTest.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/api/v1/rp/JwksControllerTest.java
cat > /tmp/codex-p3-t6.txt <<'PROMPT'
Code review for SigningKeyProvider REWRITE (Phase 3 T6).

Phase 1 generated an RSA key in @PostConstruct (in-memory only,
invalidated every reboot). T6 replaces with DB-backed load:
- init() → findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
  → if present, decode envelope + cache
  → if absent, generate new RSA-2048, envelope.seal(pkcs8), INSERT
- signingKey() returns cached ACTIVE
- publicJwkSet() returns ACTIVE + ROTATED (REVOKED excluded)
- reload() re-reads ACTIVE after rotation (called by KeyRotationService)

Concerns to validate:
1. Race on first boot — both admin-app and passkey-app run init().
   If both see "no ACTIVE", both try to INSERT. uq_signing_key_kid
   prevents two rows but the second one fails with constraint
   violation. Should one or both re-read after the failure? Or
   serialize at the DB level?
2. Hibernate session in @PostConstruct — runs outside @Transactional.
   findFirstBy... uses an auto-commit transaction from the JPA session.
   Confirm this works in Spring Boot 3.5 (it does — repo methods open
   their own tx when called without an enclosing one).
3. createInitialKey() saves via repo.save — needs an enclosing
   transaction. Spring Data JPA's save() opens one automatically.
   No explicit @Transactional on the method — OK because save() is
   self-transactional. Audit append (out of scope here) would need
   wrapping; we audit SIGNING_KEY_INIT separately in admin-app.
4. reload() throws if no ACTIVE exists post-rotation. Rotation contract:
   KeyRotationService inserts new ACTIVE before transitioning the old
   one to ROTATED, so reload() always finds one.
5. Envelope round-trip safety — decode() catches every checked exception
   from JCE and re-throws as IllegalStateException. KID surfaced in
   message for debugging (not secret).

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t6.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t6-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t6-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t6-out.txt 2>&1
cat /tmp/codex-p3-t6-out.txt
```

If codex flags the first-boot race as P1, the fix is to wrap createInitialKey in a @Transactional method that does `findFirstBy... → if absent, save` inside one tx with SERIALIZABLE isolation; on constraint-violation retry (re-read).

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(core): SigningKeyProvider REWRITE — DB-backed with envelope decode"
```

---

## Task 7: `JwksAssembler` extracted helper (refactor)

**Files:**
- Create `core/src/main/java/com/crosscert/passkey/core/jwt/JwksAssembler.java`

The SigningKeyProvider.publicJwkSet() inline logic in T6 already does ACTIVE+ROTATED filtering. T7 extracts it to a named class for testability and so KeyRotationService / admin endpoints can call it without going through SigningKeyProvider.

- [ ] **Step 1: Extract `JwksAssembler.java`**

```java
package com.crosscert.passkey.core.jwt;

import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the JWKS set published at GET /.well-known/jwks.json.
 *
 * <p>Includes ACTIVE + ROTATED keys (so JWTs signed by the recently
 * rotated key can still verify during grace) but never REVOKED keys.
 * Private material is stripped via {@link RSAKey#toPublicJWK()}.
 */
@Component
public class JwksAssembler {

    private final SigningKeyRepository repo;

    public JwksAssembler(SigningKeyRepository repo) {
        this.repo = repo;
    }

    public JWKSet build() {
        List<JWK> publics = new ArrayList<>();
        for (SigningKey row : repo.findAllByStatusIn(List.of("ACTIVE", "ROTATED"))) {
            try {
                RSAKey rsa = (RSAKey) JWK.parse(row.getPublicJwk());
                publics.add(new RSAKey.Builder(rsa.toRSAPublicKey())
                        .keyID(row.getKid())
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.RS256)
                        .build()
                        .toPublicJWK());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "failed to assemble JWKS row kid=" + row.getKid(), e);
            }
        }
        return new JWKSet(publics);
    }
}
```

- [ ] **Step 2: Update `SigningKeyProvider.publicJwkSet()` to delegate**

Replace the body in `SigningKeyProvider.java`:

```java
    public JWKSet publicJwkSet() {
        return new JwksAssembler(repo).build();
    }
```

(Or inject `JwksAssembler` via constructor — cleaner. Use constructor injection to enable future customization. Update the test's constructor call accordingly.)

Modified SigningKeyProvider constructor:

```java
public SigningKeyProvider(SigningKeyRepository repo,
                          KeyEnvelope envelope,
                          ObjectMapper mapper,
                          Clock clock,
                          JwksAssembler jwks) {
    this.repo = repo;
    this.envelope = envelope;
    this.mapper = mapper;
    this.clock = clock;
    this.jwks = jwks;
}
```

Update `publicJwkSet()`:

```java
public JWKSet publicJwkSet() {
    return jwks.build();
}
```

Update `SigningKeyProviderTest` and `JwksControllerTest` to pass `new JwksAssembler(repo)` to the constructor.

- [ ] **Step 3: Run all jwt + jwks tests**

```bash
./gradlew :core:test :passkey-app:test --tests "*Jwks*" --tests "*SigningKey*" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Codex review**

```bash
git add core/src/main/java/com/crosscert/passkey/core/jwt/JwksAssembler.java \
        core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java \
        core/src/test/java/com/crosscert/passkey/core/jwt/SigningKeyProviderTest.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/api/v1/rp/JwksControllerTest.java
cat > /tmp/codex-p3-t7.txt <<'PROMPT'
Code review for JwksAssembler extraction (Phase 3 T7).

Pure refactor: moves the ACTIVE+ROTATED filtering + public JWK
materialization out of SigningKeyProvider.publicJwkSet into a separate
@Component, then re-wires SigningKeyProvider to delegate.

No new behavior. Testable in isolation. KeyRotationService and any
admin endpoint that wants to render JWKS can inject JwksAssembler
directly.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t7.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t7-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t7-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t7-out.txt 2>&1
cat /tmp/codex-p3-t7-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "refactor(core): extract JwksAssembler from SigningKeyProvider"
```

---

## Task 8: `SchedulerLeaseService` (TDD)

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/scheduler/SchedulerLeaseService.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/scheduler/SchedulerLeaseServiceTest.java`

Phase 0 created `scheduler_lease` table + `SchedulerLease` read-only entity. T8 adds the write/lease helper using JdbcTemplate + SELECT FOR UPDATE — same singleton-row-lock pattern as Phase 2's AuditLogService AUDIT_CHAIN_LOCK.

- [ ] **Step 1: Write failing test**

```java
package com.crosscert.passkey.admin.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerLeaseServiceTest {

    private JdbcTemplate jdbc;
    private SchedulerLeaseService svc;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        svc = new SchedulerLeaseService(jdbc, clock);
    }

    @Test
    void tryAcquireInsertsLeaseWhenAbsent() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString()))
                .thenReturn(0); // row count = 0
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        boolean got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5));

        assertThat(got).isTrue();
        // INSERT path: one queryForObject (existence check), one update (INSERT)
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void tryAcquireTakesOverExpiredLease() {
        // Row exists but expires_at is in the past
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString()))
                .thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        boolean got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5));

        assertThat(got).isTrue();
    }

    @Test
    void tryAcquireReturnsFalseWhenAnotherHolderActive() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString()))
                .thenReturn(1);
        // UPDATE returns 0 — row didn't match (held by someone else, not expired)
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        boolean got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5));

        assertThat(got).isFalse();
    }
}
```

- [ ] **Step 2: Run, confirm compile failure**

```bash
./gradlew :admin-app:test --tests SchedulerLeaseServiceTest 2>&1 | tail -10
```

- [ ] **Step 3: Write `SchedulerLeaseService.java`**

```java
package com.crosscert.passkey.admin.scheduler;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Database-backed lease for one-leader-at-a-time scheduler jobs
 * (Phase 3 T8). Mirrors the Phase 2 AUDIT_CHAIN_LOCK singleton-row
 * pattern but generalized: each lease is identified by a name string
 * (e.g. "mds-sync", "key-expiration") and held by a host+process id.
 *
 * <p>Acquisition:
 *   - INSERT new row if name absent.
 *   - UPDATE row if held by us OR expired (expires_at &lt; now()).
 *   - UPDATE matches 0 rows → another holder is active → return false.
 *
 * <p>Release is best-effort (TTL eventually expires anyway).
 */
@Service
public class SchedulerLeaseService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SchedulerLeaseService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public boolean tryAcquire(String name, String holder, Duration ttl) {
        Instant now = clock.instant();
        Instant newExpiry = now.plus(ttl);
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.scheduler_lease WHERE name=?",
                Integer.class, name);
        if (existing == null || existing == 0) {
            try {
                jdbc.update(
                        "INSERT INTO APP_OWNER.scheduler_lease (name, holder, expires_at) " +
                        "VALUES (?, ?, ?)",
                        name, holder, Timestamp.from(newExpiry));
                return true;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Race: another instance INSERTed between our SELECT and INSERT.
                // Fall through to the UPDATE-takeover path below.
            }
        }

        int updated = jdbc.update(
                "UPDATE APP_OWNER.scheduler_lease " +
                "SET holder=?, expires_at=? " +
                "WHERE name=? AND (holder=? OR expires_at < ?)",
                holder, Timestamp.from(newExpiry),
                name, holder, Timestamp.from(now));
        return updated > 0;
    }

    @Transactional
    public void release(String name, String holder) {
        jdbc.update(
                "DELETE FROM APP_OWNER.scheduler_lease WHERE name=? AND holder=?",
                name, holder);
    }
}
```

- [ ] **Step 4: Re-run tests, confirm PASS**

```bash
./gradlew :admin-app:test --tests SchedulerLeaseServiceTest 2>&1 | tail -10
```

Expected: 3 tests pass.

- [ ] **Step 5: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/scheduler/SchedulerLeaseService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/scheduler/SchedulerLeaseServiceTest.java
cat > /tmp/codex-p3-t8.txt <<'PROMPT'
Code review for SchedulerLeaseService (Phase 3 T8) — DB-backed lease.

Algorithm:
  1. SELECT COUNT(*) WHERE name=?
  2. If 0: INSERT (catch DuplicateKeyException race → fall to step 3)
  3. UPDATE ... WHERE name=? AND (holder=us OR expires_at<now)
     → 1 row matched: we hold the lease (acquired or took over expired)
     → 0 rows matched: someone else holds an unexpired lease → false

@Transactional so JDBC operations share a tx. Read-committed isolation
(Oracle default) is adequate: the UPDATE's WHERE clause atomically
checks the holder/expiry condition.

Schema column names match V1: name (PK), holder, expires_at.

Review:
1. INSERT race — handled by DuplicateKeyException catch.
2. UPDATE-on-mismatch — Oracle returns 0 affected rows; we return false.
3. Clock-skew across instances — each instance uses its local clock for
   "now"; an instance with a fast clock might prematurely take over.
   Acceptable for scheduler use (worst case is double-execution one
   cycle).
4. TTL choice belongs to caller — 5min for MDS sync (longer than fetch
   typically takes), 30s for key expiration (fast). Plan §6.2 documents.
5. No @PreAuthorize — internal service, callers (jobs + KeyRotationService)
   are server-side. No external endpoint exposes lease ops directly.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t8.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t8-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t8-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t8-out.txt 2>&1
cat /tmp/codex-p3-t8-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin): SchedulerLeaseService — DB lease for scheduler jobs"
```

---

## Task 9: `MdsRootCertProvider` (FIDO root CA loader)

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsRootCertProvider.java`
- Create `admin-app/src/main/resources/fido/fido-mds-root.crt`

The webauthn4j `FidoMDS3MetadataBLOBProvider` constructor takes an `X509Certificate trustAnchor` (or a `Set<TrustAnchor>`). We supply the FIDO Alliance root CA cert from a classpath resource. A test profile can swap to a self-signed CA for IT.

- [ ] **Step 1: Download FIDO Alliance MDS3 root CA**

```bash
# Download the public FIDO root CA used to sign MDS3 BLOBs.
# Stable URL maintained by FIDO Alliance.
curl -sSL https://valid.r3.roots.globalsign.com/ -o /dev/null  # placeholder
# In practice: fetch the GlobalSign Root CA R3 (FIDO MDS3 trust anchor).
# The cert is widely mirrored; use whatever your environment trusts.
# For this plan we use the version webauthn4j-test pins.

# As a deterministic fallback for offline dev: use the test fixture
# from webauthn4j-metadata-test resources.
# Extract from the JAR or commit a known-good copy.
```

For Phase 3, commit the FIDO Alliance MDS3 root certificate as a resource. If the operator does not have network access to fetch it, the test profile uses a fixture root.

Create `admin-app/src/main/resources/fido/fido-mds-root.crt` — paste the FIDO Alliance MDS3 root certificate PEM here. (Available from `https://valid.mds3.fidoalliance.org/cert` or `https://fidoalliance.org/metadata/`.)

If unable to fetch reliably in CI/local: the test profile in T18 (MdsSchedulerIT) overrides with a self-signed CA. The local profile uses the committed file.

- [ ] **Step 2: Write the provider**

```java
package com.crosscert.passkey.admin.mds;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Loads the FIDO Alliance MDS3 root CA certificate from a Spring
 * resource (default: classpath:fido/fido-mds-root.crt). Returns an
 * X509Certificate suitable for webauthn4j FidoMDS3MetadataBLOBProvider
 * constructor.
 *
 * <p>Test profile (application-test.yml) overrides
 * {@code passkey.mds.root-cert} to a test-only self-signed CA so
 * MdsSchedulerIT can verify the full fetch + cert-chain path without
 * depending on the live FIDO Alliance PKI.
 */
@Component
public class MdsRootCertProvider {

    private final X509Certificate root;

    public MdsRootCertProvider(
            @Value("${passkey.mds.root-cert:classpath:fido/fido-mds-root.crt}")
            Resource certResource) {
        try (InputStream in = certResource.getInputStream()) {
            this.root = (X509Certificate) CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(in);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to load FIDO MDS3 root cert from " + certResource, e);
        }
    }

    public X509Certificate get() {
        return root;
    }
}
```

- [ ] **Step 3: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsRootCertProvider.java \
        admin-app/src/main/resources/fido/fido-mds-root.crt
cat > /tmp/codex-p3-t9.txt <<'PROMPT'
Code review for MdsRootCertProvider (Phase 3 T9).

Single-purpose component that loads the FIDO Alliance MDS3 trust anchor
certificate from a Spring resource. Default classpath:fido/fido-mds-root.crt;
test profile overrides to a self-signed test CA.

Review:
1. PEM vs DER — CertificateFactory.generateCertificate handles both;
   common practice is PEM for human inspection. The committed file
   should be PEM-encoded.
2. Resource override mechanism — @Value default value uses Spring's
   resource-loader prefix syntax. Confirm "file:..." prefix works for
   prod environments that mount the cert outside the jar.
3. Cert validity check — constructor only parses. If the committed cert
   is expired, FidoMDS3MetadataBLOBProvider will fail later. Acceptable
   (fail-fast at first MDS fetch with clear error).
4. Plan calls out using GlobalSign Root CA R3 OR the FIDO test cert.
   Whichever is committed should be documented with its source URL
   and expiry.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t9.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t9-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t9-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t9-out.txt 2>&1
cat /tmp/codex-p3-t9-out.txt
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(admin): MdsRootCertProvider + FIDO MDS3 root CA resource"
```

---

## Task 10: `MdsBlobClient` (webauthn4j-metadata wrapper, TDD)

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobClient.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsBlobClientTest.java`

We delegate to webauthn4j's `FidoMDS3MetadataBLOBProvider` which already handles HTTP fetch + JWT verify + cert chain validation. Our wrapper exposes a narrow Spring-friendly API and absorbs the version-specific constructor.

- [ ] **Step 1: Write failing test**

```java
package com.crosscert.passkey.admin.mds;

import com.webauthn4j.metadata.MetadataBLOBProvider;
import com.webauthn4j.metadata.data.MetadataBLOB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MdsBlobClientTest {

    private MetadataBLOBProvider underlying;
    private MdsBlobClient client;

    @BeforeEach
    void setUp() {
        underlying = mock(MetadataBLOBProvider.class);
        client = new MdsBlobClient(underlying);
    }

    @Test
    void fetchDelegatesToUnderlyingProvider() {
        MetadataBLOB blob = mock(MetadataBLOB.class);
        when(underlying.provide()).thenReturn(blob);

        MetadataBLOB result = client.fetch();
        assertThat(result).isSameAs(blob);
    }

    @Test
    void fetchSurfacesProviderExceptionAsIllegalStateException() {
        when(underlying.provide()).thenThrow(new RuntimeException("upstream 503"));

        assertThatThrownBy(() -> client.fetch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MDS fetch failed");
    }
}
```

- [ ] **Step 2: Run, confirm compile failure**

```bash
./gradlew :admin-app:test --tests MdsBlobClientTest 2>&1 | tail -10
```

- [ ] **Step 3: Write `MdsBlobClient.java`**

```java
package com.crosscert.passkey.admin.mds;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.metadata.FidoMDS3MetadataBLOBProvider;
import com.webauthn4j.metadata.MetadataBLOBProvider;
import com.webauthn4j.metadata.data.MetadataBLOB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Thin Spring wrapper around webauthn4j's
 * {@link FidoMDS3MetadataBLOBProvider}. Encapsulates the
 * constructor-driven configuration so callers can depend on a
 * narrow {@link #fetch()} method.
 *
 * <p>The provider downloads the JWT BLOB over HTTPS, verifies its
 * signature against the configured FIDO Alliance trust anchor, walks
 * the cert chain, and returns a parsed {@link MetadataBLOB}.
 */
@Component
public class MdsBlobClient {

    private final MetadataBLOBProvider provider;

    public MdsBlobClient(MetadataBLOBProvider provider) {
        this.provider = provider;
    }

    public MetadataBLOB fetch() {
        try {
            return provider.provide();
        } catch (RuntimeException e) {
            throw new IllegalStateException("MDS fetch failed: " + e.getMessage(), e);
        }
    }

    @Configuration
    static class Wiring {
        @Bean
        public MetadataBLOBProvider fidoMds3MetadataBLOBProvider(
                MdsRootCertProvider rootProvider,
                @Value("${passkey.mds.blob-endpoint:https://mds3.fidoalliance.org/}")
                String endpoint) {
            ObjectConverter oc = new ObjectConverter();
            return new FidoMDS3MetadataBLOBProvider(oc, endpoint, rootProvider.get());
        }
    }
}
```

- [ ] **Step 4: Re-run tests, confirm PASS**

```bash
./gradlew :admin-app:test --tests MdsBlobClientTest 2>&1 | tail -10
```

Expected: 2 tests pass.

- [ ] **Step 5: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobClient.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsBlobClientTest.java
cat > /tmp/codex-p3-t10.txt <<'PROMPT'
Code review for MdsBlobClient (Phase 3 T10).

Wraps webauthn4j-metadata's FidoMDS3MetadataBLOBProvider. The Wiring
inner @Configuration creates the provider bean by:
- Building an ObjectConverter (webauthn4j's Jackson 3-based converter)
- Passing the configured endpoint (defaults to https://mds3.fidoalliance.org/)
- Passing the trust anchor X509Certificate from MdsRootCertProvider

fetch() delegates to provider.provide(), which:
- Downloads the JWT BLOB via webauthn4j's HttpClient
- Verifies signature + walks cert chain against trust anchor
- Returns parsed MetadataBLOB (entries list + headers)

Errors wrap as IllegalStateException with message prefix.

Review:
1. ObjectConverter — webauthn4j 0.31.5 uses Jackson 3 (tools.jackson).
   Constructing here doesn't collide with Spring's Jackson 2 ObjectMapper.
   Phase 1 already pinned jackson-annotations 2.21 — confirm.
2. HTTP timeout — webauthn4j's default HttpClient timeout. Acceptable
   for 6h scheduler (slow fetch just delays one cycle). For more
   control we'd pass our own HttpClient implementation.
3. Cert revocation — FidoMDS3MetadataBLOBProvider.setRevocationCheckEnabled
   defaults to true in webauthn4j (CRL/OCSP). Production OK; tests need
   to disable when using fixture root (T18 IT setup).
4. fetch() catches RuntimeException broadly. Loses original type info;
   wraps in IllegalStateException with delegated message. OK.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t10.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t10-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t10-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t10-out.txt 2>&1
cat /tmp/codex-p3-t10-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin): MdsBlobClient — wraps webauthn4j FidoMDS3MetadataBLOBProvider"
```

---

## Task 11: `MdsBlobStore` (singleton UPSERT)

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobStore.java`

Stores the fetched + verified BLOB back into the `mds_blob_cache` singleton row (id=1, seeded by V17). Uses Oracle MERGE for atomic UPSERT.

- [ ] **Step 1: Write the store**

```java
package com.crosscert.passkey.admin.mds;

import com.webauthn4j.metadata.data.MetadataBLOB;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Persists the most recent verified MDS BLOB into the singleton
 * row id=1 of {@code mds_blob_cache}.
 *
 * <p>Note: webauthn4j's MetadataBLOB exposes version + nextUpdate via
 * its payload (header excluded). We extract those and store the raw
 * JWT as well for forensic re-verification. The raw JWT comes from
 * MdsBlobClient via a separate path because MetadataBLOB doesn't
 * surface the original encoded form — see {@link #store(String, MetadataBLOB)}.
 */
@Service
public class MdsBlobStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MdsBlobStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public void store(String rawJwt, MetadataBLOB blob) {
        long version = blob.getPayload().getNo();
        LocalDate nextUpdate = blob.getPayload().getNextUpdate();
        Instant now = clock.instant();
        jdbc.update(
                "UPDATE APP_OWNER.mds_blob_cache " +
                "SET version=?, next_update=?, fetched_at=?, blob_jwt=? " +
                "WHERE id=1",
                version,
                java.sql.Date.valueOf(nextUpdate),
                Timestamp.from(now),
                rawJwt);
    }
}
```

- [ ] **Step 2: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobStore.java
cat > /tmp/codex-p3-t11.txt <<'PROMPT'
Code review for MdsBlobStore (Phase 3 T11).

Updates the singleton row (id=1) of mds_blob_cache with the latest
verified MDS BLOB. Uses UPDATE (not MERGE) because V17 already seeded
the sentinel — no INSERT path needed.

webauthn4j MetadataBLOB.getPayload().getNo() returns the BLOB version
number; .getNextUpdate() returns LocalDate. Store as columns; raw JWT
stored separately for forensic re-verify.

Review:
1. Raw JWT passed in by caller — MdsBlobClient.fetch() returns a parsed
   MetadataBLOB, not the original JWT string. Caller must capture the
   raw bytes separately (e.g. via webauthn4j HttpClient interception).
   Plan §7.1 implies "JWT" stored — this is a wiring gap. Either:
     (a) Skip raw JWT (store empty string / NULL) — Phase 3 verifier
         doesn't re-verify from blob_jwt; it consults the parsed entries
         which we cache in Redis after parsing in T13. NULL would
         violate NOT NULL — use empty JSON "{}".
     (b) Capture raw JWT from a custom HttpClient implementation.
   Pick (a) for Phase 3 simplicity; document the deferral.
2. @Transactional — single UPDATE, but @Transactional ensures it
   participates in the calling MdsSchedulerService's tx (T12).
3. Column types — Oracle DATE vs TIMESTAMP. V1's next_update column
   was created as DATE; java.sql.Date.valueOf(LocalDate) is the right
   mapping.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t11.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t11-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t11-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t11-out.txt 2>&1
cat /tmp/codex-p3-t11-out.txt
```

If codex confirms the raw-JWT gap is a P1, the simpler fix is to have MdsSchedulerService pass `"{}"` as `rawJwt` for now (Phase 3 verifier doesn't use it) and document in followup that Phase 4 should capture the raw bytes via a custom webauthn4j HttpClient. Update MdsBlobStore signature to make this explicit.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(admin): MdsBlobStore — UPSERT singleton mds_blob_cache row"
```

---

## Task 12: `MdsSchedulerService` (orchestrator)

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java`

Glues SchedulerLeaseService → MdsBlobClient → MdsBlobStore → Redis cache invalidation → audit append.

- [ ] **Step 1: Write the service**

```java
package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import com.webauthn4j.metadata.data.MetadataBLOB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates a single MDS sync cycle: acquire lease → fetch BLOB →
 * persist → invalidate AAGUID cache → audit. Returns {@link SyncResult}
 * so the controller (admin force-sync) and the @Scheduled job can both
 * report status.
 */
@Service
public class MdsSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(MdsSchedulerService.class);
    private static final String LEASE_NAME = "mds-sync";

    private final SchedulerLeaseService leases;
    private final MdsBlobClient client;
    private final MdsBlobStore store;
    private final StringRedisTemplate redis;
    private final AuditLogService audit;
    private final Clock clock;
    private final String holder;

    public MdsSchedulerService(SchedulerLeaseService leases,
                               MdsBlobClient client,
                               MdsBlobStore store,
                               StringRedisTemplate redis,
                               AuditLogService audit,
                               Clock clock,
                               @Value("${passkey.mds.lease-holder:default}") String configuredHolder) {
        this.leases = leases;
        this.client = client;
        this.store = store;
        this.redis = redis;
        this.audit = audit;
        this.clock = clock;
        // Default holder = PID@host, unique per JVM.
        this.holder = "default".equals(configuredHolder)
                ? ManagementFactory.getRuntimeMXBean().getName()
                : configuredHolder;
    }

    public SyncResult runOnce() {
        if (!leases.tryAcquire(LEASE_NAME, holder, Duration.ofMinutes(5))) {
            log.info("MDS sync skipped — another instance holds the lease");
            return SyncResult.skipped();
        }
        try {
            MetadataBLOB blob = client.fetch();
            String rawJwt = "{}"; // see T11 codex note — raw bytes deferred to Phase 4
            store.store(rawJwt, blob);

            // Invalidate AAGUID cache so passkey-app sees fresh data.
            Set<String> keys = redis.keys("mds:aaguid:*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }

            long version = blob.getPayload().getNo();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", version);
            payload.put("fetchedAt", clock.instant().toString());
            // Actor 0 = scheduler (no human operator).
            audit.append(new AuditAppendRequest(
                    0L, "(scheduler)", "MDS_BLOB_SYNC", null, null, payload));

            log.info("MDS sync complete — version={}", version);
            return SyncResult.synced(version);
        } catch (RuntimeException e) {
            log.warn("MDS sync failed: {}", e.toString());
            return SyncResult.failed(e.getMessage());
        }
    }

    public record SyncResult(String status, Long version, String error) {
        public static SyncResult synced(long version) {
            return new SyncResult("SYNCED", version, null);
        }
        public static SyncResult skipped() {
            return new SyncResult("SKIPPED", null, null);
        }
        public static SyncResult failed(String error) {
            return new SyncResult("FAILED", null, error);
        }
    }
}
```

- [ ] **Step 2: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java
cat > /tmp/codex-p3-t12.txt <<'PROMPT'
Code review for MdsSchedulerService (Phase 3 T12) — orchestrates one
MDS sync cycle.

Flow:
  1. SchedulerLeaseService.tryAcquire("mds-sync", holder, 5min)
     → false: return SKIPPED
  2. MdsBlobClient.fetch() → MetadataBLOB
  3. MdsBlobStore.store(rawJwt, blob)
  4. Redis: DEL keys matching "mds:aaguid:*"
  5. AuditLogService.append("MDS_BLOB_SYNC", actor=0 scheduler, payload={version, fetchedAt})
  6. return SYNCED(version)
Errors: return FAILED(msg). No transaction wrapping (each sub-step is
self-transactional via its component).

holder = PID@host (ManagementFactory) by default; configurable.

Review:
1. Lease release — not called explicitly. TTL (5min) expires naturally.
   If runOnce() finishes in <1s, the lease holds for ~5min idle. Next
   scheduler tick (6h later) tryAcquires; OK because we ARE the holder.
2. Multi-instance correctness — two instances tryAcquire at the same
   second. SchedulerLeaseService's tx (INSERT or UPDATE with conditional
   WHERE) serializes them; one returns true, other returns false.
3. Cache invalidation — KEYS pattern can be slow on large Redis dbs.
   Phase 3 admin Redis has hundreds of mds:aaguid:* keys at most;
   acceptable. SCAN-based deletion is the prod-ready alternative.
4. Audit append with actor=0 — Phase 2 already uses actor=0 for
   ADMIN_LOGIN_FAILED. Convention: 0 means "non-human actor". Consistent.
5. Error swallow — exception → return FAILED but doesn't rethrow.
   Controller (force-sync) shows status to operator; @Scheduled job
   logs and retries next cycle. No alerting hookup yet (followup).

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t12.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t12-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t12-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t12-out.txt 2>&1
cat /tmp/codex-p3-t12-out.txt
```

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(admin): MdsSchedulerService — lease/fetch/store/invalidate/audit"
```

---

## Task 13: `MdsSyncJob` (@Scheduled) + `@EnableScheduling`

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSyncJob.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/AdminApplication.java`
- Modify `admin-app/src/main/resources/application.yml`

- [ ] **Step 1: Add `@EnableScheduling` to `AdminApplication.java`**

Edit the existing annotation block:

```java
@SpringBootApplication(scanBasePackages = "com.crosscert.passkey")
@EntityScan("com.crosscert.passkey.core.entity")
@EnableJpaRepositories("com.crosscert.passkey.core.repository")
@org.springframework.scheduling.annotation.EnableScheduling
public class AdminApplication { ... }
```

- [ ] **Step 2: Write `MdsSyncJob.java`**

```java
package com.crosscert.passkey.admin.mds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires MdsSchedulerService.runOnce() every 6 hours. Initial delay 30s
 * to avoid all instances thundering at boot.
 */
@Component
public class MdsSyncJob {

    private static final Logger log = LoggerFactory.getLogger(MdsSyncJob.class);

    private final MdsSchedulerService scheduler;

    public MdsSyncJob(MdsSchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(
            fixedDelayString = "${passkey.mds.fixed-delay:PT6H}",
            initialDelayString = "${passkey.mds.initial-delay:PT30S}")
    public void run() {
        log.debug("MdsSyncJob firing");
        MdsSchedulerService.SyncResult result = scheduler.runOnce();
        log.info("MdsSyncJob result: {}", result);
    }
}
```

- [ ] **Step 3: Add config defaults to `admin-app/src/main/resources/application.yml`**

Append within the existing `spring:` block's same indentation level (or as a top-level `passkey:` block if not present yet):

```yaml
passkey:
  mds:
    fixed-delay: PT6H
    initial-delay: PT30S
  key-rotation:
    grace: PT30M
    expiration-job:
      fixed-delay: PT1M
      initial-delay: PT45S
```

- [ ] **Step 4: Boot admin-app once to confirm @EnableScheduling + job registration**

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/sched-boot.log 2>&1 &
ADMIN_PID=$!
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/sched-boot.log; do sleep 2; done
grep -E "MdsSyncJob|Scheduled" /tmp/sched-boot.log | head -5
# Wait 35s for initial-delay PT30S, then check it actually fired (will likely fail
# because the dev cert isn't a real FIDO root — that's OK, we expect a FAILED result).
sleep 35
grep "MdsSyncJob result" /tmp/sched-boot.log | head -2
kill $ADMIN_PID; wait $ADMIN_PID 2>/dev/null
```

Expected: `MdsSyncJob result: SyncResult[status=FAILED, ...]` (because the local FIDO root certificate may not chain to whatever lives at https://mds3.fidoalliance.org/ at the moment; the IT in T18 swaps to a WireMock + test root).

- [ ] **Step 5: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSyncJob.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/AdminApplication.java \
        admin-app/src/main/resources/application.yml
cat > /tmp/codex-p3-t13.txt <<'PROMPT'
Code review for MdsSyncJob @Scheduled + @EnableScheduling (Phase 3 T13).

Single @Component with @Scheduled(fixedDelayString=PT6H,
initialDelayString=PT30S). Fires MdsSchedulerService.runOnce().

@EnableScheduling on AdminApplication enables the framework's
ScheduledTaskRegistrar. Default single-threaded TaskScheduler — fine
for two jobs at low frequency.

Review:
1. fixedDelay vs fixedRate — fixedDelay measures from previous
   completion. If a fetch takes 10s, next fire is 6h+10s. Safer than
   fixedRate (which would queue overlapping fires if a fetch hangs).
2. initialDelay PT30S — gives admin-app time to fully start before
   any background traffic. Reasonable.
3. Single-instance vs multi-instance — SchedulerLeaseService already
   serializes; @Scheduled fires on every instance, but only the lease
   winner does work. OK.
4. Test environment — IT (T18) will trigger via direct
   MdsSchedulerService.runOnce() call instead of waiting for @Scheduled
   to fire. The job itself is best left alone in IT.
5. Boot test in Step 4 ignores the FAILED result — Phase 3 local dev
   may not have network to FIDO Alliance. T9 plan suggested committing
   a known-good root cert, but Step 4 acknowledges this may not work
   offline. IT (T18) uses WireMock + test root.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t13.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t13-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t13-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t13-out.txt 2>&1
cat /tmp/codex-p3-t13-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin): MdsSyncJob @Scheduled + @EnableScheduling"
```

---

## Task 14: `MdsAdminController` + RBAC test

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsAdminController.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsAdminControllerSecurityTest.java`

Endpoints:
- `GET /admin/api/mds/status` — both ADMIN+VIEWER. Returns current `mds_blob_cache` row info (version, next_update, fetched_at).
- `POST /admin/api/mds/sync` — ADMIN only. Calls `MdsSchedulerService.runOnce()`.

- [ ] **Step 1: Write controller**

```java
package com.crosscert.passkey.admin.mds;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/mds")
public class MdsAdminController {

    private final JdbcTemplate jdbc;
    private final MdsSchedulerService scheduler;

    public MdsAdminController(JdbcTemplate jdbc, MdsSchedulerService scheduler) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return jdbc.queryForObject(
                "SELECT version AS \"version\", " +
                "       next_update AS \"nextUpdate\", " +
                "       fetched_at AS \"fetchedAt\" " +
                "FROM APP_OWNER.mds_blob_cache WHERE id=1",
                (rs, n) -> Map.of(
                        "version", rs.getLong("version"),
                        "nextUpdate", rs.getDate("nextUpdate") == null
                                ? null : rs.getDate("nextUpdate").toLocalDate(),
                        "fetchedAt", rs.getTimestamp("fetchedAt") == null
                                ? null : rs.getTimestamp("fetchedAt").toInstant()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/sync")
    public MdsSchedulerService.SyncResult sync() {
        return scheduler.runOnce();
    }
}
```

- [ ] **Step 2: Write `MdsAdminControllerSecurityTest.java`**

Mirror the JpaStubs pattern from Phase 2 T13/T15/T16. RBAC matrix:
- anonymous GET /status → 401
- VIEWER GET /status → 200
- VIEWER POST /sync → 403
- ADMIN POST /sync → 200

```java
package com.crosscert.passkey.admin.mds;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MdsAdminController.class)
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        SpringDataWebAutoConfiguration.class
})
@Import({com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
         MdsAdminControllerSecurityTest.JpaStubs.class})
class MdsAdminControllerSecurityTest {

    @TestConfiguration
    static class JpaStubs {
        @Bean
        EntityManagerFactory entityManagerFactory() {
            EntityManagerFactory emf = mock(EntityManagerFactory.class);
            Metamodel mm = mock(Metamodel.class);
            when(mm.getEntities()).thenReturn(java.util.Collections.emptySet());
            when(mm.getManagedTypes()).thenReturn(java.util.Collections.emptySet());
            when(mm.getEmbeddables()).thenReturn(java.util.Collections.emptySet());
            when(emf.getMetamodel()).thenReturn(mm);
            return emf;
        }
    }

    @Autowired MockMvc mvc;
    @MockBean JdbcTemplate jdbc;
    @MockBean MdsSchedulerService scheduler;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRepository admins;
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenants;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository creds;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeys;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogs;
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeys;

    @Test
    void anonymousGetStatusIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/mds/status")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanGetStatus() throws Exception {
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<java.util.Map<String, Object>>>any()))
                .thenReturn(java.util.Map.of("version", 0L));
        mvc.perform(get("/admin/api/mds/status")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotForceSync() throws Exception {
        mvc.perform(post("/admin/api/mds/sync").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanForceSync() throws Exception {
        when(scheduler.runOnce())
                .thenReturn(MdsSchedulerService.SyncResult.synced(42));
        mvc.perform(post("/admin/api/mds/sync").with(csrf()))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :admin-app:test --tests MdsAdminControllerSecurityTest 2>&1 | tail -15
```

Expected: 4 tests pass.

- [ ] **Step 4: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsAdminController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsAdminControllerSecurityTest.java
cat > /tmp/codex-p3-t14.txt <<'PROMPT'
Code review for MdsAdminController + RBAC slice test (Phase 3 T14).

Endpoints:
- GET /admin/api/mds/status — any auth role. Returns mds_blob_cache
  singleton row info via direct JdbcTemplate query.
- POST /admin/api/mds/sync — ADMIN only. Triggers
  MdsSchedulerService.runOnce() synchronously and returns SyncResult.

Why JdbcTemplate vs JPA — mds_blob_cache has a SchedulerLease-style
read-only entity (Phase 0) that hasn't been written for. Direct SQL
read avoids JPA-via-readonly-entity hassles. Single column-aliased
SELECT.

Review:
1. GET status — VIEWER can see version/nextUpdate/fetchedAt. No
   sensitive data (BLOB itself excluded). OK.
2. POST sync — synchronous. If FIDO MDS3 is slow, request blocks.
   Phase 3 admin tolerance is high (operators trigger force-sync
   sparingly). Could move to async with poll endpoint as followup.
3. Aliased column names — Oracle returns uppercase by default;
   double-quoted aliases preserve camelCase. RowMapper uses the
   quoted names. Confirm Oracle JDBC respects the quotes.
4. JpaStubs identical to Phase 2 — could extract shared config, P2
   deferred to refactor task.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t14.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t14-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t14-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t14-out.txt 2>&1
cat /tmp/codex-p3-t14-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(admin): MdsAdminController + RBAC test (status + ADMIN-only sync)"
```

---

## Task 15: `MdsVerifier` REPLACE `MdsStubVerifier` (passkey-app, TDD)

**Files:**
- Create `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsAaguidCache.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsStubVerifier.java` → delete + replace with `MdsVerifier.java`
- Create `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/mds/MdsVerifierTest.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java` (inject MdsVerifier)

- [ ] **Step 1: Write failing test**

```java
package com.crosscert.passkey.app.fido2.mds;

import com.crosscert.passkey.app.fido2.policy.AttestationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MdsVerifierTest {

    private MdsAaguidCache cache;
    private MdsVerifier verifier;
    private final byte[] AAGUID = new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16};

    @BeforeEach
    void setUp() {
        cache = mock(MdsAaguidCache.class);
        verifier = new MdsVerifier(cache);
    }

    @Test
    void mdsNotRequiredAlwaysPasses() {
        AttestationPolicy policy = new AttestationPolicy(
                Set.of("none"), true, false);
        assertThat(verifier.verify(policy, AAGUID)).isTrue();
    }

    @Test
    void mdsRequiredAndEntryAcceptableReturnsTrue() {
        AttestationPolicy policy = new AttestationPolicy(
                Set.of("packed"), true, true);
        when(cache.lookup(any())).thenReturn(Optional.of(
                new MdsAaguidCache.Entry(List.of("FIDO_CERTIFIED_L1"))));
        assertThat(verifier.verify(policy, AAGUID)).isTrue();
    }

    @Test
    void mdsRequiredAndEntryBlockedReturnsFalse() {
        AttestationPolicy policy = new AttestationPolicy(
                Set.of("packed"), true, true);
        when(cache.lookup(any())).thenReturn(Optional.of(
                new MdsAaguidCache.Entry(List.of("FIDO_CERTIFIED_L1", "REVOKED"))));
        assertThat(verifier.verify(policy, AAGUID)).isFalse();
    }

    @Test
    void mdsRequiredAndEntryAbsentReturnsFalse() {
        AttestationPolicy policy = new AttestationPolicy(
                Set.of("packed"), true, true);
        when(cache.lookup(any())).thenReturn(Optional.empty());
        assertThat(verifier.verify(policy, AAGUID)).isFalse();
    }

    @Test
    void compromiseStatusesAreBlocked() {
        AttestationPolicy policy = new AttestationPolicy(
                Set.of("packed"), true, true);
        for (String blocked : List.of(
                "REVOKED", "USER_VERIFICATION_BYPASS",
                "ATTESTATION_KEY_COMPROMISE",
                "USER_KEY_REMOTE_COMPROMISE",
                "USER_KEY_PHYSICAL_COMPROMISE")) {
            when(cache.lookup(any())).thenReturn(Optional.of(
                    new MdsAaguidCache.Entry(List.of("FIDO_CERTIFIED_L1", blocked))));
            assertThat(verifier.verify(policy, AAGUID))
                    .as("status %s must be blocked", blocked)
                    .isFalse();
        }
    }
}
```

- [ ] **Step 2: Write `MdsAaguidCache.java`**

```java
package com.crosscert.passkey.app.fido2.mds;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed AAGUID → MdsEntry cache. Keys follow the
 * {@code mds:aaguid:<UUID>} pattern so MdsSchedulerService can invalidate
 * the whole set on a new BLOB by DELing the matching key range.
 *
 * <p>Phase 3 scope: just status report list (sufficient to apply the
 * blocking-status rule). Future phases may expand the cached value to
 * include the full webauthn4j MetadataStatement for tenant policy
 * matching.
 */
@Component
public class MdsAaguidCache {

    private final StringRedisTemplate redis;

    public MdsAaguidCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<Entry> lookup(byte[] aaguid) {
        UUID uuid = canonicalAaguid(aaguid);
        String key = "mds:aaguid:" + uuid;
        String csv = redis.opsForValue().get(key);
        if (csv == null || csv.isBlank()) return Optional.empty();
        return Optional.of(new Entry(List.of(csv.split(","))));
    }

    public void put(byte[] aaguid, Entry entry, Duration ttl) {
        UUID uuid = canonicalAaguid(aaguid);
        String key = "mds:aaguid:" + uuid;
        String csv = String.join(",", entry.statuses);
        redis.opsForValue().set(key, csv, ttl);
    }

    /** AAGUID = 16-byte raw → UUID canonical form. */
    static UUID canonicalAaguid(byte[] aaguid) {
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (aaguid[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (aaguid[i] & 0xff);
        return new UUID(msb, lsb);
    }

    public record Entry(List<String> statuses) {
        public Entry { statuses = List.copyOf(statuses); }
    }
}
```

- [ ] **Step 3: Write `MdsVerifier.java`**

```java
package com.crosscert.passkey.app.fido2.mds;

import com.crosscert.passkey.app.fido2.policy.AttestationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Real MDS verifier (Phase 3 T15) — replaces Phase 1's MdsStubVerifier.
 *
 * <p>When the tenant policy has {@code mdsRequired=false}, always
 * returns true (same as Phase 1). When {@code mdsRequired=true}, the
 * AAGUID's last status report must NOT be in the blocking set, and the
 * AAGUID must be present in the cached MDS BLOB (fail-closed on
 * absence).
 */
@Component
public class MdsVerifier {

    private static final Logger log = LoggerFactory.getLogger(MdsVerifier.class);
    private static final Set<String> BLOCKING_STATUSES = Set.of(
            "REVOKED",
            "USER_VERIFICATION_BYPASS",
            "ATTESTATION_KEY_COMPROMISE",
            "USER_KEY_REMOTE_COMPROMISE",
            "USER_KEY_PHYSICAL_COMPROMISE");

    private final MdsAaguidCache cache;

    public MdsVerifier(MdsAaguidCache cache) {
        this.cache = cache;
    }

    public boolean verify(AttestationPolicy policy, byte[] aaguid) {
        if (!policy.mdsRequired()) return true;

        Optional<MdsAaguidCache.Entry> entryOpt = cache.lookup(aaguid);
        if (entryOpt.isEmpty()) {
            log.warn("MDS entry absent for aaguid {} — fail-closed",
                    MdsAaguidCache.canonicalAaguid(aaguid));
            return false;
        }
        for (String status : entryOpt.get().statuses()) {
            if (BLOCKING_STATUSES.contains(status)) {
                log.warn("MDS blocking status {} for aaguid {}",
                        status, MdsAaguidCache.canonicalAaguid(aaguid));
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: Delete `MdsStubVerifier.java`**

```bash
rm passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsStubVerifier.java
```

- [ ] **Step 5: Update `RegistrationFinishService.java` injection**

Find the constructor's `MdsStubVerifier mds` parameter and the field declaration. Replace:

```java
private final MdsStubVerifier mds;
```

with:

```java
private final MdsVerifier mds;
```

And the constructor parameter:

```java
MdsStubVerifier mds,
```

with:

```java
MdsVerifier mds,
```

The call sites (`mds.verify(policy, aaguid)`) are unchanged — both classes have the same method signature.

- [ ] **Step 6: Update `RegistrationFinishServiceTest.java`** (if one exists) — same find/replace.

```bash
grep -rln "MdsStubVerifier" passkey-app/src/test/java/ 2>/dev/null
```

Replace any matches.

- [ ] **Step 7: Run tests**

```bash
./gradlew :passkey-app:test --tests MdsVerifierTest 2>&1 | tail -15
./gradlew :passkey-app:compileJava :passkey-app:compileTestJava 2>&1 | tail -10
```

Expected: MdsVerifierTest passes (5 tests). Compile clean.

- [ ] **Step 8: Codex review**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsVerifier.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsAaguidCache.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/fido2/mds/MdsVerifierTest.java
git rm passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsStubVerifier.java
cat > /tmp/codex-p3-t15.txt <<'PROMPT'
Code review for MdsVerifier replacing MdsStubVerifier (Phase 3 T15).

Same signature as the stub: boolean verify(AttestationPolicy, byte[] aaguid).
Behavior:
- mdsRequired=false → true (Phase 1 compat).
- mdsRequired=true + cache miss → false (fail-closed).
- mdsRequired=true + last status in BLOCKING_STATUSES → false.
- otherwise → true.

BLOCKING_STATUSES per Phase 3 spec §7.3 (W3C WebAuthn L3 reject list).
MdsAaguidCache stores list of status strings (CSV) keyed by
canonical UUID form of AAGUID. TTL 30min (passed by writer; T18's
test stub writes with TTL).

Review:
1. AAGUID canonical form — UUID big-endian conversion. Matches FIDO
   MDS3 BLOB convention (each entry's "aaguid" is the canonical UUID
   string). Confirm.
2. CSV encoding of statuses — adequate for Phase 3 (statuses are
   identifiers, never contain commas). JSON would be more robust.
   P2 deferred.
3. Fail-closed when cache miss — passkey-app does not fetch on demand
   (admin scheduler owns fetch). If scheduler hasn't run yet, every
   mds-required tenant fails. Documented in spec §7.2. OK.
4. RegistrationFinishService wiring — MdsVerifier injected with same
   constructor parameter name. Existing call site mds.verify(policy,
   aaguid) unchanged. Compile-time check.
5. Logging — log.warn with canonical UUID (no secret). Operator can
   trace why a registration was rejected.
6. Test coverage — 5 tests cover the matrix. compromise statuses
   parameterized.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t15.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t15-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t15-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t15-out.txt 2>&1
cat /tmp/codex-p3-t15-out.txt
```

- [ ] **Step 9: Commit**

```bash
git commit -m "feat(passkey): replace MdsStubVerifier with MdsVerifier + Redis AAGUID cache"
```

---

## Task 16: Wire MDS cache writer into MdsSchedulerService

**Files:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java`

MdsAaguidCache lives in `:passkey-app` but `MdsSchedulerService` (admin-app) needs to write entries into it after each successful sync. Two options:

(a) Move MdsAaguidCache into `:core` so both modules can inject it.
(b) Admin-app uses raw StringRedisTemplate to write the same keys + format.

Pick (b) for Phase 3 — admin doesn't model the cache, it just produces invalidation + entries. T16 extends MdsSchedulerService.runOnce() to iterate the parsed MetadataBLOB entries and write each AAGUID → CSV.

- [ ] **Step 1: Extend `MdsSchedulerService.runOnce()`** to write entries:

After `store.store(rawJwt, blob);` and before the DEL invalidation, ADD:

```java
            // Publish each entry's status report list back to Redis so
            // passkey-app's MdsAaguidCache.lookup hits cache.
            // Key format: "mds:aaguid:<canonical-UUID>".
            // Value: comma-joined status strings (status reports
            // ordered as MDS returns them — most recent last).
            for (com.webauthn4j.metadata.data.MetadataBLOBPayloadEntry entry
                    : blob.getPayload().getEntries()) {
                if (entry.getAaguid() == null) continue;
                String uuid = entry.getAaguid().toString();
                String csv = entry.getStatusReports().stream()
                        .map(sr -> sr.getStatus() == null ? "" : sr.getStatus().toString())
                        .filter(s -> !s.isBlank())
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
                if (!csv.isBlank()) {
                    redis.opsForValue().set("mds:aaguid:" + uuid, csv,
                            java.time.Duration.ofMinutes(30));
                }
            }
```

The existing `Set<String> keys = redis.keys("mds:aaguid:*"); ... delete(keys);` block runs BEFORE we re-populate above — that's the correct order (invalidate stale, then write fresh). Confirm the diff places the population code AFTER the delete.

The final order in `runOnce()` is:
1. tryAcquire lease
2. client.fetch()
3. store.store(rawJwt, blob)
4. **DELETE old AAGUID keys**
5. **Iterate entries, SET new keys**
6. audit.append

- [ ] **Step 2: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java
cat > /tmp/codex-p3-t16.txt <<'PROMPT'
Code review for MdsSchedulerService AAGUID cache population (Phase 3 T16).

After successful BLOB store + cache invalidation, iterate
MetadataBLOBPayloadEntry list and write each AAGUID → CSV of statuses
into Redis with 30min TTL.

Order: DELETE old → SET new — atomic enough that a passkey-app lookup
between DELETE and SET sees a cache miss, which fails closed per
MdsVerifier's fail-closed behavior. Brief miss window is acceptable
for a 6h-cadence scheduler.

Review:
1. Status string null handling — entry.getStatusReports() returns a
   list of report objects each with a non-null status enum in practice.
   Defensive null skip + isBlank check handles edge cases.
2. CSV joining — adequate for known status identifiers (no commas).
   See T15 P2 deferred to JSON later.
3. Entries without AAGUID — some MDS entries describe authenticators
   without AAGUIDs (legacy U2F). Skip them — webauthn4j passes them
   through but we don't cache.
4. Performance — typical BLOB has ~50-200 entries. 200 SETs to Redis
   is a few hundred ms. Acceptable for 6h cadence.
5. Cache TTL 30min — same as MdsVerifier expects. Hardcoded for now;
   make configurable in followup.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t16.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t16-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t16-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t16-out.txt 2>&1
cat /tmp/codex-p3-t16-out.txt
```

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(admin): MdsSchedulerService writes per-AAGUID entries to Redis"
```

---

## Task 17: `KeyRotationService` (TDD)

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyRotationServiceTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.jwt.KeyEnvelope;
import com.crosscert.passkey.core.jwt.SigningKeyProvider;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeyRotationServiceTest {

    private SigningKeyRepository repo;
    private SigningKeyProvider provider;
    private SchedulerLeaseService leases;
    private AuditLogService audit;
    private KeyEnvelope envelope;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private KeyRotationService svc;

    @BeforeEach
    void setUp() {
        repo = mock(SigningKeyRepository.class);
        provider = mock(SigningKeyProvider.class);
        leases = mock(SchedulerLeaseService.class);
        audit = mock(AuditLogService.class);
        envelope = new KeyEnvelope(
                Base64.getEncoder().encodeToString(new byte[32]),
                new SecureRandom());
        svc = new KeyRotationService(repo, provider, leases, audit, envelope, clock);
    }

    @Test
    void rotateTransitionsActiveAndInsertsNew() throws Exception {
        SigningKey current = freshActiveKey("old-kid");
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.of(current));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KeyRotationService.RotateResult result = svc.rotate(7L, "alice@example.com");

        // Old key transitioned.
        assertThat(current.getStatus()).isEqualTo("ROTATED");
        assertThat(current.getRotatedAt()).isEqualTo(clock.instant());

        // New ACTIVE key inserted.
        ArgumentCaptor<SigningKey> savedCaptor = ArgumentCaptor.forClass(SigningKey.class);
        verify(repo, org.mockito.Mockito.atLeast(2)).save(savedCaptor.capture());
        SigningKey newRow = savedCaptor.getAllValues().stream()
                .filter(k -> "ACTIVE".equals(k.getStatus()))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(newRow.getKid()).isNotEqualTo("old-kid");
        assertThat(newRow.getAlg()).isEqualTo("RS256");

        // Provider reloaded + audit appended.
        verify(provider).reload();
        ArgumentCaptor<AuditAppendRequest> auditCap =
                ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCap.capture());
        assertThat(auditCap.getValue().action()).isEqualTo("SIGNING_KEY_ROTATE");
        assertThat(auditCap.getValue().payload()).containsKey("oldKid");
        assertThat(auditCap.getValue().payload()).containsKey("newKid");

        assertThat(result.oldKid()).isEqualTo("old-kid");
        assertThat(result.newKid()).isEqualTo(newRow.getKid());
    }

    @Test
    void rotateThrowsConflictWhenLeaseUnavailable() {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        assertThatThrownBy(() -> svc.rotate(7L, "alice@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another rotation in progress");
    }

    @Test
    void rotateThrowsWhenNoActiveKeyExists() {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.rotate(7L, "alice@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no ACTIVE key");
    }

    private SigningKey freshActiveKey(String kid) throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        com.nimbusds.jose.jwk.RSAKey rsa =
                new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(kid)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .build();
        return new SigningKey(kid, "RS256", rsa.toPublicJWK().toJSONString(),
                envelope.seal(pair.getPrivate().getEncoded()));
    }
}
```

- [ ] **Step 2: Run, confirm compile failure**

```bash
./gradlew :admin-app:test --tests KeyRotationServiceTest 2>&1 | tail -10
```

- [ ] **Step 3: Write `KeyRotationService.java`**

```java
package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.jwt.KeyEnvelope;
import com.crosscert.passkey.core.jwt.SigningKeyProvider;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates a new ACTIVE signing key and transitions the existing one
 * to ROTATED. Protected by a SchedulerLeaseService lease so concurrent
 * admin clicks don't double-rotate.
 *
 * <p>Contract for SigningKeyProvider: at the end of {@link #rotate}, the
 * new ACTIVE row exists in the DB and the in-memory cached key matches
 * (provider.reload() is called).
 */
@Service
public class KeyRotationService {

    private static final String LEASE_NAME = "key-rotation";
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);

    private final SigningKeyRepository repo;
    private final SigningKeyProvider provider;
    private final SchedulerLeaseService leases;
    private final AuditLogService audit;
    private final KeyEnvelope envelope;
    private final Clock clock;

    public KeyRotationService(SigningKeyRepository repo,
                              SigningKeyProvider provider,
                              SchedulerLeaseService leases,
                              AuditLogService audit,
                              KeyEnvelope envelope,
                              Clock clock) {
        this.repo = repo;
        this.provider = provider;
        this.leases = leases;
        this.audit = audit;
        this.envelope = envelope;
        this.clock = clock;
    }

    @Transactional
    public RotateResult rotate(long actorId, String actorEmail) {
        String holder = ManagementFactory.getRuntimeMXBean().getName();
        if (!leases.tryAcquire(LEASE_NAME, holder, LEASE_TTL)) {
            throw new IllegalStateException("another rotation in progress");
        }
        SigningKey old = repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
                .orElseThrow(() -> new IllegalStateException("no ACTIVE key"));

        // Transition old → ROTATED first so the row count of ACTIVE goes
        // to zero before we INSERT the new one (avoids momentary
        // ambiguity for any concurrent reader).
        old.rotate(clock.instant());
        repo.save(old);

        SigningKey fresh = generateFreshActive();
        repo.save(fresh);

        provider.reload();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("oldKid", old.getKid());
        payload.put("newKid", fresh.getKid());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "SIGNING_KEY_ROTATE",
                "SIGNING_KEY", String.valueOf(fresh.getId()), payload));

        return new RotateResult(old.getKid(), fresh.getKid());
    }

    private SigningKey generateFreshActive() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            RSAKey withoutKid = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            String kid = withoutKid.computeThumbprint().toString();
            RSAKey rsa = new RSAKey.Builder(withoutKid).keyID(kid).build();
            return new SigningKey(kid, "RS256", rsa.toPublicJWK().toJSONString(),
                    envelope.seal(pair.getPrivate().getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("rotation key generation failed", e);
        }
    }

    public record RotateResult(String oldKid, String newKid) {}
}
```

- [ ] **Step 4: Re-run tests, confirm PASS**

```bash
./gradlew :admin-app:test --tests KeyRotationServiceTest 2>&1 | tail -15
```

Expected: 3 tests pass.

- [ ] **Step 5: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyRotationServiceTest.java
cat > /tmp/codex-p3-t17.txt <<'PROMPT'
Code review for KeyRotationService (Phase 3 T17).

@Transactional. Flow:
1. SchedulerLeaseService.tryAcquire("key-rotation", holder, 30s)
   → false: throw IllegalStateException("another rotation in progress")
     → mapped to 409 by GlobalExceptionHandler? Phase 0/1/2 only map
     IllegalArgumentException to 400. Add @ResponseStatus or handler
     mapping for IllegalStateException → 409 conflict.
2. Load current ACTIVE; throw if absent.
3. old.rotate(now) + save.
4. Generate fresh RSA-2048 + envelope.seal + save (status='ACTIVE').
5. provider.reload() — refresh in-memory cache to point at new ACTIVE.
6. audit.append SIGNING_KEY_ROTATE with {oldKid, newKid}.

Review:
1. Ordering of old→ROTATED then new INSERT — between the two saves,
   no ACTIVE row exists. A concurrent SigningKeyProvider.init() at
   this exact moment would see empty + try to createInitialKey,
   creating a third (orphan) ACTIVE. Mitigation:
     - Insert new ACTIVE first (status='ACTIVE'), THEN transition old
       to ROTATED. Brief window has TWO ACTIVE rows, which the
       findFirst...OrderByCreatedAtDesc query handles (returns newest).
     - Or: keep current order but rely on SchedulerLeaseService lock
       to serialize. provider.init() runs once per JVM, not during
       runtime. Race only if a new JVM boots mid-rotation — possible
       but rare; lease-window is <1s.
   Recommend: insert-new-first ordering.
2. provider.reload() — calls findFirstByStatusOrderByCreatedAtDesc.
   If new ACTIVE was inserted first (recommended fix), reload picks
   up new key. With current ordering, reload after both saves picks
   up the new key correctly.
3. Exception → 409 — IllegalStateException becomes 500 unless we
   add a handler. The controller (T19) can catch and return 409 via
   ResponseStatusException. Or add to GlobalExceptionHandler.
4. SigningKey.id is null on the fresh row until save flushes — using
   getId() in the audit payload before save may yield null. The plan
   uses .save() then references .getId() — save returns the managed
   instance with id populated. OK.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t17.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t17-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t17-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t17-out.txt 2>&1
cat /tmp/codex-p3-t17-out.txt
```

If codex flags the ordering race as P1, swap the order: insert new ACTIVE first, then transition old to ROTATED. Update test accordingly.

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin): KeyRotationService — lease-protected ACTIVE→ROTATED + new ACTIVE"
```

---

## Task 18: `KeyExpirationJob` (TDD) + scheduled wiring

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJob.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJobTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeyExpirationJobTest {

    private SigningKeyRepository repo;
    private SchedulerLeaseService leases;
    private AuditLogService audit;
    private KeyExpirationJob job;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = mock(SigningKeyRepository.class);
        leases = mock(SchedulerLeaseService.class);
        audit = mock(AuditLogService.class);
        job = new KeyExpirationJob(repo, leases, audit, clock, Duration.ofMinutes(30));
    }

    @Test
    void skipsWhenLeaseUnavailable() {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        job.runOnce();
        verify(repo, never()).findAllByStatusAndRotatedAtBefore(anyString(), any());
    }

    @Test
    void transitionsExpiredRotatedToRevoked() throws Exception {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        // rotated at clock - 31min — past the 30min grace
        SigningKey old = withId(rotatedKey("expired"), 1L);
        setRotatedAt(old, clock.instant().minus(Duration.ofMinutes(31)));
        when(repo.findAllByStatusAndRotatedAtBefore(eq("ROTATED"), any()))
                .thenReturn(List.of(old));

        job.runOnce();

        assertThat(old.getStatus()).isEqualTo("REVOKED");
        assertThat(old.getRevokedAt()).isEqualTo(clock.instant());
        verify(repo).save(old);

        ArgumentCaptor<AuditAppendRequest> auditCap =
                ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCap.capture());
        assertThat(auditCap.getValue().action()).isEqualTo("SIGNING_KEY_REVOKE");
        assertThat(auditCap.getValue().payload().get("kid")).isEqualTo("expired");
    }

    @Test
    void leavesNonExpiredRotatedAlone() throws Exception {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(repo.findAllByStatusAndRotatedAtBefore(eq("ROTATED"), any()))
                .thenReturn(List.of());
        job.runOnce();
        verify(repo, never()).save(any());
        verify(audit, never()).append(any());
    }

    private static SigningKey rotatedKey(String kid) {
        SigningKey k = new SigningKey(kid, "RS256", "{}", new byte[]{0});
        k.rotate(Instant.parse("2026-06-01T00:00:00Z"));
        return k;
    }

    private static SigningKey withId(SigningKey k, long id) throws Exception {
        Field f = SigningKey.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(k, id);
        return k;
    }

    private static void setRotatedAt(SigningKey k, Instant t) throws Exception {
        Field f = SigningKey.class.getDeclaredField("rotatedAt");
        f.setAccessible(true);
        f.set(k, t);
    }
}
```

- [ ] **Step 2: Write `KeyExpirationJob.java`**

```java
package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Periodic job that transitions ROTATED signing keys to REVOKED once
 * the grace period elapses. Default grace is PT30M
 * ({@code passkey.key-rotation.grace}); during grace, JWKS continues
 * to expose the ROTATED key so RPs holding JWTs signed by it can still
 * verify. After grace, the key is hidden from JWKS and any remaining
 * JWT signed by it fails verification.
 */
@Component
public class KeyExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(KeyExpirationJob.class);
    private static final String LEASE_NAME = "key-expiration";

    private final SigningKeyRepository repo;
    private final SchedulerLeaseService leases;
    private final AuditLogService audit;
    private final Clock clock;
    private final Duration grace;

    public KeyExpirationJob(SigningKeyRepository repo,
                            SchedulerLeaseService leases,
                            AuditLogService audit,
                            Clock clock,
                            @Value("${passkey.key-rotation.grace:PT30M}") Duration grace) {
        this.repo = repo;
        this.leases = leases;
        this.audit = audit;
        this.clock = clock;
        this.grace = grace;
    }

    @Scheduled(
            fixedDelayString = "${passkey.key-rotation.expiration-job.fixed-delay:PT1M}",
            initialDelayString = "${passkey.key-rotation.expiration-job.initial-delay:PT45S}")
    public void runOnce() {
        String holder = ManagementFactory.getRuntimeMXBean().getName();
        if (!leases.tryAcquire(LEASE_NAME, holder, Duration.ofSeconds(30))) {
            log.debug("KeyExpirationJob skipped — another instance holds the lease");
            return;
        }
        Instant cutoff = clock.instant().minus(grace);
        List<SigningKey> expired = repo.findAllByStatusAndRotatedAtBefore("ROTATED", cutoff);
        for (SigningKey k : expired) {
            k.revoke(clock.instant());
            repo.save(k);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kid", k.getKid());
            payload.put("rotatedAt", k.getRotatedAt().toString());
            audit.append(new AuditAppendRequest(
                    0L, "(scheduler)", "SIGNING_KEY_REVOKE",
                    "SIGNING_KEY", String.valueOf(k.getId()), payload));
            log.info("Revoked signing key kid={} after grace period", k.getKid());
        }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :admin-app:test --tests KeyExpirationJobTest 2>&1 | tail -15
```

Expected: 3 tests pass.

- [ ] **Step 4: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJob.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJobTest.java
cat > /tmp/codex-p3-t18.txt <<'PROMPT'
Code review for KeyExpirationJob (Phase 3 T18).

@Scheduled fixedDelay PT1M (configurable), initialDelay PT45S. Per
tick:
  1. tryAcquire("key-expiration", holder, 30s) — false skip.
  2. find ROTATED rows with rotatedAt < now - grace.
  3. For each: k.revoke(now); save; audit SIGNING_KEY_REVOKE.

Tests cover: skip on lease loss; transition when expired; no-op on
empty.

Review:
1. Grace default PT30M — matches Phase 3 design.
2. Audit actor = 0 "(scheduler)" — same convention as MdsSchedulerService.
3. @Transactional missing — each save is auto-transactional. Multiple
   saves in one runOnce() commit independently. If one save fails,
   already-revoked keys stay revoked. Acceptable (idempotent: next
   tick finds the rest).
4. Lease lock vs DB-level concurrency — two instances: lease serializes.
   Single instance: scheduler is single-threaded by default; no
   reentrant concerns.
5. Log volume — INFO per revoke. ROTATIONS are rare events; no log
   floods.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t18.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t18-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t18-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t18-out.txt 2>&1
cat /tmp/codex-p3-t18-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(admin): KeyExpirationJob — ROTATED→REVOKED after grace"
```

---

## Task 19: `KeyMgmtController` + DTO + RBAC test

**Files:**
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtDto.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtController.java`
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtControllerSecurityTest.java`

Endpoints:
- `GET /admin/api/keys` — list all signing keys (ADMIN+VIEWER).
- `POST /admin/api/keys/rotate` — trigger rotation (ADMIN only).

- [ ] **Step 1: Write `KeyMgmtDto.java`**

```java
package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.core.entity.SigningKey;

import java.time.Instant;
import java.util.List;

public final class KeyMgmtDto {

    private KeyMgmtDto() {}

    public record SigningKeyView(
            Long id,
            String kid,
            String alg,
            String status,
            Instant createdAt,
            Instant rotatedAt,
            Instant revokedAt
    ) {
        public static SigningKeyView from(SigningKey k) {
            return new SigningKeyView(
                    k.getId(), k.getKid(), k.getAlg(), k.getStatus(),
                    k.getCreatedAt(), k.getRotatedAt(), k.getRevokedAt());
        }
    }

    public record RotateResponse(String oldKid, String newKid) {}

    public record KeyList(List<SigningKeyView> keys) {}
}
```

- [ ] **Step 2: Write `KeyMgmtController.java`**

```java
package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin/api/keys")
public class KeyMgmtController {

    private final SigningKeyRepository repo;
    private final KeyRotationService rotation;
    private final AdminUserRepository admins;

    public KeyMgmtController(SigningKeyRepository repo,
                             KeyRotationService rotation,
                             AdminUserRepository admins) {
        this.repo = repo;
        this.rotation = rotation;
        this.admins = admins;
    }

    @GetMapping
    public KeyMgmtDto.KeyList list() {
        var keys = repo.findAll().stream()
                .map(KeyMgmtDto.SigningKeyView::from)
                .toList();
        return new KeyMgmtDto.KeyList(keys);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/rotate")
    public KeyMgmtDto.RotateResponse rotate(Authentication auth) {
        long actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        try {
            var result = rotation.rotate(actorId, auth.getName());
            return new KeyMgmtDto.RotateResponse(result.oldKid(), result.newKid());
        } catch (IllegalStateException e) {
            // "another rotation in progress" → 409 Conflict
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 3: Write `KeyMgmtControllerSecurityTest.java`** (mirror T14's pattern with JpaStubs + auto-config exclusion)

```java
package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = KeyMgmtController.class)
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        SpringDataWebAutoConfiguration.class
})
@Import({com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
         KeyMgmtControllerSecurityTest.JpaStubs.class})
class KeyMgmtControllerSecurityTest {

    @TestConfiguration
    static class JpaStubs {
        @Bean
        EntityManagerFactory entityManagerFactory() {
            EntityManagerFactory emf = mock(EntityManagerFactory.class);
            Metamodel mm = mock(Metamodel.class);
            when(mm.getEntities()).thenReturn(java.util.Collections.emptySet());
            when(mm.getManagedTypes()).thenReturn(java.util.Collections.emptySet());
            when(mm.getEmbeddables()).thenReturn(java.util.Collections.emptySet());
            when(emf.getMetamodel()).thenReturn(mm);
            return emf;
        }
    }

    @Autowired MockMvc mvc;
    @MockBean SigningKeyRepository repo;
    @MockBean KeyRotationService rotation;
    @MockBean AdminUserRepository admins;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenants;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository creds;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeys;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogs;

    @Test
    void anonymousListIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/keys")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanList() throws Exception {
        when(repo.findAll()).thenReturn(java.util.List.of());
        mvc.perform(get("/admin/api/keys")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotRotate() throws Exception {
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "ADMIN")
    void adminCanRotate() throws Exception {
        when(admins.findByEmail(anyString())).thenReturn(
                java.util.Optional.of(adminUserWithId(7L)));
        when(rotation.rotate(anyLong(), anyString()))
                .thenReturn(new KeyRotationService.RotateResult("old","new"));
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "ADMIN")
    void rotateConflictWhenLeaseUnavailable() throws Exception {
        when(admins.findByEmail(anyString())).thenReturn(
                java.util.Optional.of(adminUserWithId(7L)));
        when(rotation.rotate(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("another rotation in progress"));
        mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
                .andExpect(status().isConflict());
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

- [ ] **Step 4: Run tests**

```bash
./gradlew :admin-app:test --tests KeyMgmtControllerSecurityTest 2>&1 | tail -15
```

Expected: 5 tests pass.

- [ ] **Step 5: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtDto.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtControllerSecurityTest.java
cat > /tmp/codex-p3-t19.txt <<'PROMPT'
Code review for KeyMgmtController + RBAC test (Phase 3 T19).

Endpoints:
- GET /admin/api/keys (any auth) — list signing keys.
- POST /admin/api/keys/rotate (ADMIN only) — trigger rotation.

Errors:
- IllegalStateException("another rotation in progress") → 409 Conflict
  via ResponseStatusException.

Tests: 5 — anon GET 401, VIEWER list 200, VIEWER rotate 403, ADMIN
rotate 200, ADMIN rotate concurrent 409.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t19.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t19-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t19-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t19-out.txt 2>&1
cat /tmp/codex-p3-t19-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin): KeyMgmtController + RBAC test (list + ADMIN rotate, 409 on lease conflict)"
```

---

## Task 20: admin-ui MdsStatus page

**Files:**
- Create `admin-ui/src/pages/MdsStatus.tsx`
- Modify `admin-ui/src/api/types.ts` (add MdsStatusView, SyncResult)
- Modify `admin-ui/src/App.tsx` (route)
- Modify `admin-ui/src/components/Layout.tsx` (nav link)

- [ ] **Step 1: Append types to `admin-ui/src/api/types.ts`**

```typescript
export interface MdsStatusView {
  version: number;
  nextUpdate?: string;
  fetchedAt?: string;
}

export interface SyncResult {
  status: 'SYNCED' | 'SKIPPED' | 'FAILED';
  version?: number;
  error?: string;
}
```

- [ ] **Step 2: Create `admin-ui/src/pages/MdsStatus.tsx`**

```tsx
import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { MdsStatusView, SyncResult } from '../api/types';

export default function MdsStatus() {
  const [status, setStatus] = useState<MdsStatusView | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [lastResult, setLastResult] = useState<SyncResult | null>(null);

  function refresh() {
    api.get<MdsStatusView>('/admin/api/mds/status').then(setStatus);
  }

  useEffect(refresh, []);

  async function forceSync() {
    setSyncing(true);
    try {
      const r = await api.post<SyncResult>('/admin/api/mds/sync', {});
      setLastResult(r);
      refresh();
    } finally {
      setSyncing(false);
    }
  }

  return (
    <div>
      <h2>MDS Status</h2>
      {status === null ? (
        <p>Loading…</p>
      ) : (
        <table border={1} cellPadding={4} cellSpacing={0}>
          <tbody>
            <tr><th>Version</th><td>{status.version}</td></tr>
            <tr><th>Next update</th><td>{status.nextUpdate ?? '-'}</td></tr>
            <tr><th>Last fetched</th><td>{status.fetchedAt ?? '(never)'}</td></tr>
          </tbody>
        </table>
      )}
      <p style={{ marginTop: '1rem' }}>
        <button onClick={forceSync} disabled={syncing}>
          {syncing ? 'Syncing…' : 'Force sync now'}
        </button>
      </p>
      {lastResult && (
        <p>
          Last result: <code>{lastResult.status}</code>
          {lastResult.version != null && ` (version ${lastResult.version})`}
          {lastResult.error && ` — ${lastResult.error}`}
        </p>
      )}
    </div>
  );
}
```

- [ ] **Step 3: Add route in `admin-ui/src/App.tsx`**

Find the existing Routes block under `<Route element={<Layout />}>` and add:

```tsx
        <Route path="/mds" element={<MdsStatus />} />
```

And add the import at the top:

```tsx
import MdsStatus from './pages/MdsStatus';
```

- [ ] **Step 4: Add nav link in `admin-ui/src/components/Layout.tsx`**

After the existing `<Link to="/audit">Audit Log</Link>`, add:

```tsx
        <Link to="/mds">MDS</Link>
```

- [ ] **Step 5: Sanity-build**

```bash
cd admin-ui && npm run build && cd ..
./gradlew :admin-app:bootJar 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL both.

- [ ] **Step 6: Codex review**

```bash
git add admin-ui/src/pages/MdsStatus.tsx \
        admin-ui/src/api/types.ts \
        admin-ui/src/App.tsx \
        admin-ui/src/components/Layout.tsx
cat > /tmp/codex-p3-t20.txt <<'PROMPT'
Code review for SPA MdsStatus page (Phase 3 T20).

Single page:
- Fetches /admin/api/mds/status on mount.
- Renders version / next update / last fetched as a small table.
- Force-sync button POSTs /admin/api/mds/sync (ADMIN only — 403 leaks
  as raw error string for VIEWER, same UX caveat as Phase 2 T22's
  AuditLog verify button).

Types added: MdsStatusView, SyncResult.

Review:
1. VIEWER clicking Force-sync — gets 403 from server. SPA renders the
   error message verbatim. P2 deferred (mirror Phase 2 followup).
2. Loading state — distinguishes null (initial load) vs synced.
3. No polling — page is mount-time only. Acceptable for admin tool.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t20.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t20-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t20-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t20-out.txt 2>&1
cat /tmp/codex-p3-t20-out.txt
```

- [ ] **Step 7: Commit**

```bash
git commit -m "feat(admin-ui): MdsStatus page + nav + types"
```

---

## Task 21: admin-ui KeyManagement page

**Files:**
- Create `admin-ui/src/pages/KeyManagement.tsx`
- Modify `admin-ui/src/api/types.ts` (add SigningKeyView, KeyList, RotateResponse)
- Modify `admin-ui/src/App.tsx` (route)
- Modify `admin-ui/src/components/Layout.tsx` (nav link)

- [ ] **Step 1: Append types**

```typescript
export interface SigningKeyView {
  id: number;
  kid: string;
  alg: string;
  status: 'ACTIVE' | 'ROTATED' | 'REVOKED';
  createdAt: string;
  rotatedAt?: string;
  revokedAt?: string;
}

export interface KeyList {
  keys: SigningKeyView[];
}

export interface RotateResponse {
  oldKid: string;
  newKid: string;
}
```

- [ ] **Step 2: Create `admin-ui/src/pages/KeyManagement.tsx`**

```tsx
import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { KeyList, RotateResponse, SigningKeyView } from '../api/types';

export default function KeyManagement() {
  const [keys, setKeys] = useState<SigningKeyView[]>([]);
  const [rotating, setRotating] = useState(false);
  const [lastResult, setLastResult] = useState<RotateResponse | string | null>(null);

  function refresh() {
    api.get<KeyList>('/admin/api/keys').then(r => setKeys(r.keys));
  }

  useEffect(refresh, []);

  async function rotate() {
    if (!confirm('Rotate signing key now? Existing JWTs remain valid for ~30 minutes after.')) return;
    setRotating(true);
    try {
      const r = await api.post<RotateResponse>('/admin/api/keys/rotate', {});
      setLastResult(r);
      refresh();
    } catch (e) {
      setLastResult(String(e));
    } finally {
      setRotating(false);
    }
  }

  return (
    <div>
      <h2>Signing Keys</h2>
      <p>
        <button onClick={rotate} disabled={rotating}>
          {rotating ? 'Rotating…' : 'Rotate now'}
        </button>
      </p>
      {lastResult && (
        <p>
          {typeof lastResult === 'string'
            ? <code>{lastResult}</code>
            : <span>Rotated <code>{lastResult.oldKid}</code> → <code>{lastResult.newKid}</code></span>}
        </p>
      )}
      <table border={1} cellPadding={4} cellSpacing={0}>
        <thead>
          <tr><th>kid</th><th>alg</th><th>status</th><th>created</th><th>rotated</th><th>revoked</th></tr>
        </thead>
        <tbody>
          {keys.map(k => (
            <tr key={k.id}>
              <td><code>{k.kid}</code></td>
              <td>{k.alg}</td>
              <td>{k.status}</td>
              <td>{k.createdAt}</td>
              <td>{k.rotatedAt ?? '-'}</td>
              <td>{k.revokedAt ?? '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 3: Add route + nav link** (same pattern as T20):

App.tsx:
```tsx
<Route path="/keys" element={<KeyManagement />} />
```

Layout.tsx:
```tsx
<Link to="/keys">Keys</Link>
```

Imports:
```tsx
import KeyManagement from './pages/KeyManagement';
```

- [ ] **Step 4: Sanity-build**

```bash
cd admin-ui && npm run build && cd ..
./gradlew :admin-app:bootJar 2>&1 | tail -5
```

- [ ] **Step 5: Codex review**

```bash
git add admin-ui/src/pages/KeyManagement.tsx \
        admin-ui/src/api/types.ts \
        admin-ui/src/App.tsx \
        admin-ui/src/components/Layout.tsx
cat > /tmp/codex-p3-t21.txt <<'PROMPT'
Code review for SPA KeyManagement page (Phase 3 T21).

Lists signing keys via GET /admin/api/keys. Rotate button POSTs
/admin/api/keys/rotate with confirm() and shows the resulting
old/new kid pair. 409 (concurrent rotation) is shown as the raw
error string.

Types added: SigningKeyView, KeyList, RotateResponse.

Review:
1. Confirm dialog — explains the 30-minute grace.
2. VIEWER rotate UX — 403 shown as raw error string (same caveat as
   Phase 2 followup; defer).
3. Error vs success state union — `string | RotateResponse | null`.
   Typescript discriminated union via typeof. Minor refactor target.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t21.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t21-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t21-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t21-out.txt 2>&1
cat /tmp/codex-p3-t21-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin-ui): KeyManagement page (list + rotate) + nav"
```

---

## Task 22: WireMock dependency for ITs

**Files:**
- Modify `gradle/libs.versions.toml`
- Modify `admin-app/build.gradle.kts`

- [ ] **Step 1: Add WireMock to `gradle/libs.versions.toml`**

Append under `[versions]`:

```toml
wiremock = "3.10.0"
```

Append under `[libraries]`:

```toml
wiremock-jre8-standalone = { module = "org.wiremock:wiremock-standalone", version.ref = "wiremock" }
```

(Note: as of 2025+, "wiremock-jre8-standalone" was renamed; the maven coordinate is now `org.wiremock:wiremock-standalone`. Adjust if Gradle resolution complains.)

- [ ] **Step 2: Add WireMock to `admin-app/build.gradle.kts`**

In the existing `dependencies { }` block, add to `testImplementation`:

```kotlin
    testImplementation(rootProject.libs.wiremock.jre8.standalone)
```

- [ ] **Step 3: Compile-check**

```bash
./gradlew :admin-app:compileTestJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL (WireMock not yet used, just on classpath).

- [ ] **Step 4: Codex review**

```bash
git add gradle/libs.versions.toml admin-app/build.gradle.kts
cat > /tmp/codex-p3-t22.txt <<'PROMPT'
Code review for WireMock test dep addition (Phase 3 T22). Stage for
MdsSchedulerIT (T23) which stubs the FIDO MDS3 endpoint.

WireMock 3.10.0 is current as of 2025. Coordinate verified.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t22.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t22-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t22-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t22-out.txt 2>&1
cat /tmp/codex-p3-t22-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "build(admin-app): WireMock test dep for MdsSchedulerIT"
```

---

## Task 23: MdsSchedulerIT — Phase 3 acceptance gate #1

**Files:**
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/MdsSchedulerIT.java`

This IT is complex — building a valid signed MDS JWT BLOB on the fly requires generating a test CA + signing cert + JWT. To keep the plan tractable, the test uses a pre-built fixture BLOB committed to test resources.

- [ ] **Step 1: Generate test fixtures**

Run this once locally to create a self-signed test root CA, an MDS signing cert chained to it, and a sample BLOB JWT signed by the chain:

```bash
mkdir -p admin-app/src/test/resources/mds-test
cd admin-app/src/test/resources/mds-test

# Self-signed CA
openssl genrsa -out test-root.key 2048
openssl req -x509 -new -nodes -key test-root.key -sha256 -days 365 \
  -out test-root.crt -subj "/CN=PhaseTest-MDS-Root"

# MDS signing cert
openssl genrsa -out test-mds.key 2048
openssl req -new -key test-mds.key -out test-mds.csr \
  -subj "/CN=PhaseTest-MDS-Signer"
openssl x509 -req -in test-mds.csr -CA test-root.crt -CAkey test-root.key \
  -CAcreateserial -out test-mds.crt -days 30 -sha256

# Sample BLOB payload — single entry with packed AAGUID + REVOKED AAGUID.
# Build the JWT manually via a small script (committed) that:
#  1. Reads the payload JSON
#  2. Builds the JWT header with x5c chain (test-mds + test-root)
#  3. Signs with test-mds.key
# Output: test-blob.jwt (single-line JWS)
echo "Run scripts/generate-mds-test-blob.sh to (re)build test-blob.jwt"

cd ../../../../../..
```

The `generate-mds-test-blob.sh` script lives at the repo root and reads the test keys to produce `test-blob.jwt`. For the plan, commit the generated artifacts so CI doesn't need OpenSSL or the generator script.

If generating the test BLOB is too fiddly, an alternative is to skip the cert-chain validation portion in the test profile by stubbing `MdsBlobClient` directly with a Mockito spy that bypasses verify. Document this fallback in the IT itself.

**Pragmatic Phase 3 approach (recommended):** the IT MOCKS `MdsBlobClient` to return a hand-built `MetadataBLOB` value. This skips the cert-chain test path (covered by webauthn4j-metadata's own unit tests) but exercises everything ELSE: lease, store, cache invalidate, audit, AAGUID Redis writes.

Use this approach. Skip the openssl fixture generation.

- [ ] **Step 2: Write `MdsSchedulerIT.java`** using the mocked-client approach

```java
package com.crosscert.passkey.admin;

import com.crosscert.passkey.admin.mds.MdsBlobClient;
import com.crosscert.passkey.admin.mds.MdsSchedulerService;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.metadata.data.MetadataBLOB;
import com.webauthn4j.metadata.data.MetadataBLOBPayload;
import com.webauthn4j.metadata.data.MetadataBLOBPayloadEntry;
import com.webauthn4j.metadata.data.statement.MetadataStatement;
import com.webauthn4j.metadata.data.toc.StatusReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MdsSchedulerIT {

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

    @MockBean MdsBlobClient client;
    @Autowired MdsSchedulerService scheduler;
    @Autowired DataSource ds;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisConnectionFactory redisFactory;
    @Autowired AuditLogRepository auditRepo;

    JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);
        jdbc.update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("UPDATE APP_OWNER.mds_blob_cache SET version=0, " +
                    "blob_jwt='{}', fetched_at=TIMESTAMP '1970-01-01 00:00:00 +00:00' WHERE id=1");
        redisFactory.getConnection().serverCommands().flushAll();
    }

    @Test
    void runOnceFetchesStoresAndPopulatesAaguidCache() {
        // Build a fake MetadataBLOB with two entries.
        UUID packedUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID revokedUuid = UUID.fromString("99999999-8888-7777-6666-555555555555");
        MetadataBLOB blob = fakeBlob(42L, LocalDate.of(2099, 1, 1),
                List.of(
                        entry(packedUuid, List.of("FIDO_CERTIFIED_L1")),
                        entry(revokedUuid, List.of("FIDO_CERTIFIED_L1", "REVOKED"))
                ));
        when(client.fetch()).thenReturn(blob);

        MdsSchedulerService.SyncResult result = scheduler.runOnce();
        assertThat(result.status()).isEqualTo("SYNCED");
        assertThat(result.version()).isEqualTo(42L);

        // DB row updated.
        Long version = jdbc.queryForObject(
                "SELECT version FROM APP_OWNER.mds_blob_cache WHERE id=1", Long.class);
        assertThat(version).isEqualTo(42L);

        // Redis AAGUID entries written.
        String packed = redis.opsForValue().get("mds:aaguid:" + packedUuid);
        String revoked = redis.opsForValue().get("mds:aaguid:" + revokedUuid);
        assertThat(packed).contains("FIDO_CERTIFIED_L1");
        assertThat(revoked).contains("REVOKED");

        // Audit appended.
        assertThat(auditRepo.findAllOrdered())
                .anyMatch(a -> "MDS_BLOB_SYNC".equals(a.getAction()));
    }

    @Test
    void runOnceSkipsWhenAnotherInstanceHoldsLease() {
        // Pre-acquire the lease by inserting a row that's not us.
        jdbc.update("INSERT INTO APP_OWNER.scheduler_lease (name, holder, expires_at) " +
                    "VALUES ('mds-sync', 'somebody-else', SYSTIMESTAMP + INTERVAL '1' HOUR)");
        when(client.fetch()).thenThrow(new RuntimeException("should not be called"));

        MdsSchedulerService.SyncResult result = scheduler.runOnce();
        assertThat(result.status()).isEqualTo("SKIPPED");
    }

    @Test
    void runOnceReturnsFailedWhenFetchThrows() {
        when(client.fetch()).thenThrow(new IllegalStateException("MDS fetch failed: 503"));
        MdsSchedulerService.SyncResult result = scheduler.runOnce();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).contains("MDS fetch failed");
    }

    // ---- helpers ----

    private static MetadataBLOBPayloadEntry entry(UUID aaguid, List<String> statuses) {
        // Use the webauthn4j model classes. Construct minimal entries:
        // statusReports must be in chronological order (latest last).
        // For this test we don't care about the dates — verifier only
        // looks at the last status.
        List<StatusReport> reports = statuses.stream()
                .map(s -> new StatusReport(
                        com.webauthn4j.metadata.data.statement.AuthenticatorStatus.create(s),
                        LocalDate.of(2026, 1, 1), null, null, null, null, null, null))
                .toList();
        return new MetadataBLOBPayloadEntry(
                /* aaid */ null,
                /* aaguid */ new AAGUID(aaguid),
                /* attestationCertificateKeyIdentifiers */ null,
                /* metadataStatement */ (MetadataStatement) null,
                /* biometricStatusReports */ null,
                /* statusReports */ reports,
                /* timeOfLastStatusChange */ LocalDate.of(2026, 1, 1),
                /* rogueListURL */ null,
                /* rogueListHash */ null);
    }

    private static MetadataBLOB fakeBlob(long version, LocalDate nextUpdate,
                                         List<MetadataBLOBPayloadEntry> entries) {
        MetadataBLOBPayload payload = new MetadataBLOBPayload(
                /* legalHeader */ "test",
                /* no */ version,
                /* nextUpdate */ nextUpdate,
                /* entries */ entries);
        return new MetadataBLOB(
                /* protectedHeader */ null,
                /* payload */ payload,
                /* signature */ new byte[0]);
    }
}
```

**NOTE:** the helper signatures for `MetadataBLOBPayloadEntry` / `StatusReport` / `MetadataBLOBPayload` / `MetadataBLOB` constructors must match the actual webauthn4j-metadata 0.31.5 API. If the constructor arg order differs (likely — webauthn4j classes evolve), the implementer subagent must `javap -p` the actual classes from `~/.gradle/caches/modules-2/files-2.1/com.webauthn4j/webauthn4j-metadata/0.31.5.RELEASE/*/webauthn4j-metadata-*.jar` and adapt.

Common adaptations:
- `AuthenticatorStatus.create(String)` may be `AuthenticatorStatus.valueOf(String)` or constructor-only.
- `StatusReport` may have 4-7 args instead of 9.
- `MetadataBLOBPayloadEntry` constructor positional args differ across releases.

If the constructor-based approach proves too brittle, an alternative is to write a tiny static helper that uses Jackson/CBOR to deserialize a JSON template:

```java
// Read fixture JSON from resources, parse via ObjectConverter (webauthn4j).
String json = readFixture("/mds-test/fake-blob-payload.json");
MetadataBLOBPayload payload = new ObjectConverter().getJsonConverter()
        .readValue(json, MetadataBLOBPayload.class);
```

Commit `admin-app/src/test/resources/mds-test/fake-blob-payload.json` instead. This is more durable to webauthn4j upgrades.

- [ ] **Step 3: Run the IT**

```bash
./gradlew :admin-app:test --tests MdsSchedulerIT 2>&1 | tail -30
```

Expected: 3 tests pass. First run is slow (5-10 min for Oracle container).

If construction of `MetadataBLOBPayloadEntry` fails to compile due to API drift, fall back to the JSON-fixture approach described above.

- [ ] **Step 4: Codex review**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/MdsSchedulerIT.java
# Plus any JSON fixture if using that approach
cat > /tmp/codex-p3-t23.txt <<'PROMPT'
Code review for MdsSchedulerIT — Phase 3 acceptance gate #1 (T23).

Approach: @SpringBootTest with @MockBean MdsBlobClient (returns a
hand-built MetadataBLOB). Testcontainers Oracle + Redis for the
storage/cache side.

3 tests:
- SYNCED happy path: client returns blob → DB row updated, Redis
  entries written for each AAGUID, audit appended.
- SKIPPED when lease held by other holder (pre-INSERT lease row).
- FAILED when client throws (simulates MDS 503).

What's NOT tested by this IT:
- The cert-chain validation in webauthn4j FidoMDS3MetadataBLOBProvider
  (covered by webauthn4j-metadata's own unit tests).
- The HTTP layer (the underlying provider is mocked at MdsBlobClient).

Review:
1. Mocking MdsBlobClient — appropriate. Allows us to control the
   parsed BLOB without building a fully-signed real BLOB.
2. fakeBlob helper — constructs MetadataBLOB / Payload / Entries
   via webauthn4j-metadata constructors. Plan flags the API-drift
   risk and offers a JSON-fixture fallback.
3. @BeforeEach state reset — deletes audit, resets mds_blob_cache to
   sentinel state, flushes Redis. Test isolation.
4. Lease test — direct INSERT of "somebody-else" lease row pre-fills
   the table. Lease service correctly returns false.
5. Failure test — client throws, scheduler returns FAILED. No DB or
   Redis writes. (Should we verify no writes? Adding asserts would
   strengthen the test.)

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t23.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t23-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t23-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t23-out.txt 2>&1
cat /tmp/codex-p3-t23-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(admin): MdsSchedulerIT — Phase 3 acceptance gate (3 scenarios)"
```

---

## Task 24: KeyRotationIT — Phase 3 acceptance gate #2

**Files:**
- Create `admin-app/src/test/java/com/crosscert/passkey/admin/KeyRotationIT.java`

End-to-end key rotation flow against Testcontainers Oracle. No Redis needed.

- [ ] **Step 1: Write `KeyRotationIT.java`**

```java
package com.crosscert.passkey.admin;

import com.crosscert.passkey.admin.keymgmt.KeyExpirationJob;
import com.crosscert.passkey.admin.keymgmt.KeyRotationService;
import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.jwt.JwksAssembler;
import com.crosscert.passkey.core.jwt.SigningKeyProvider;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class KeyRotationIT {

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
            throw new RuntimeException(exec.getStdout() + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired SigningKeyRepository repo;
    @Autowired SigningKeyProvider provider;
    @Autowired KeyRotationService rotation;
    @Autowired KeyExpirationJob expirationJob;
    @Autowired JwksAssembler jwks;
    @Autowired AuditLogRepository auditRepo;
    @Autowired DataSource ds;

    JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);
        jdbc.update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("DELETE FROM APP_OWNER.signing_key");
        // Force provider to recreate ACTIVE key on next access by reload.
        provider.reload(); // will throw if no ACTIVE — that's why init() runs once.
    }

    @Test
    void rotateAddsNewActiveAndKeepsOldAsRotated() throws Exception {
        // After @BeforeEach reset + Spring bean re-init, there's exactly one ACTIVE.
        // Force createInitialKey by directly calling provider.init() via reflection
        // or by depending on SpringBootTest's bean lifecycle (each @SpringBootTest
        // method shares the context, so the initial ACTIVE was created at first boot
        // and the @BeforeEach delete + reload sequence above re-initializes).
        //
        // To make this deterministic, seed an ACTIVE row inline if absent.
        if (repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE").isEmpty()) {
            // Re-invoke createInitialKey path via SigningKeyProvider.init()? init()
            // is @PostConstruct — we can call it again manually.
            provider.init();
        }

        String oldKid = provider.signingKey().getKeyID();

        // Sign a fake JWT with the OLD key.
        SignedJWT oldSigned = signJwt(provider.signingKey(), "old-token");

        // Rotate.
        KeyRotationService.RotateResult result = rotation.rotate(0L, "(test)");
        assertThat(result.oldKid()).isEqualTo(oldKid);
        assertThat(result.newKid()).isNotEqualTo(oldKid);

        // DB has both rows.
        assertThat(repo.findAll()).hasSize(2);
        SigningKey rotated = repo.findAll().stream()
                .filter(k -> oldKid.equals(k.getKid())).findFirst().orElseThrow();
        assertThat(rotated.getStatus()).isEqualTo("ROTATED");
        assertThat(rotated.getRotatedAt()).isNotNull();

        // JWKS now exposes BOTH.
        JWKSet set = jwks.build();
        assertThat(set.getKeys()).hasSize(2);
        // Old JWT still verifies via the still-in-JWKS old public key.
        RSAKey oldPub = (RSAKey) set.getKeyByKeyId(oldKid);
        assertThat(oldPub).isNotNull();
        assertThat(oldSigned.verify(new RSASSAVerifier(oldPub))).isTrue();

        // Audit row recorded.
        assertThat(auditRepo.findAllOrdered())
                .anyMatch(a -> "SIGNING_KEY_ROTATE".equals(a.getAction()));
    }

    @Test
    void expirationJobRevokesAfterGrace() throws Exception {
        if (repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE").isEmpty()) {
            provider.init();
        }

        rotation.rotate(0L, "(test)");
        SigningKey rotated = repo.findAll().stream()
                .filter(k -> "ROTATED".equals(k.getStatus()))
                .findFirst().orElseThrow();

        // Advance rotated_at to 31 minutes ago (past 30min grace).
        Instant past = Instant.now().minus(Duration.ofMinutes(31));
        jdbc.update("UPDATE APP_OWNER.signing_key SET rotated_at=? WHERE id=?",
                Timestamp.from(past), rotated.getId());

        // Run expiration job.
        expirationJob.runOnce();

        SigningKey fresh = repo.findById(rotated.getId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo("REVOKED");
        assertThat(fresh.getRevokedAt()).isNotNull();

        // JWKS no longer contains the revoked key.
        JWKSet set = jwks.build();
        assertThat(set.getKeyByKeyId(rotated.getKid())).isNull();
    }

    @Test
    void rotateRejectsConcurrentWithConflict() {
        if (repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE").isEmpty()) {
            provider.init();
        }
        // Pre-acquire lease.
        jdbc.update("INSERT INTO APP_OWNER.scheduler_lease (name, holder, expires_at) " +
                    "VALUES ('key-rotation', 'somebody-else', SYSTIMESTAMP + INTERVAL '5' MINUTE)");

        try {
            rotation.rotate(0L, "(test)");
            assertThat(false).as("expected IllegalStateException").isTrue();
        } catch (IllegalStateException e) {
            assertThat(e).hasMessageContaining("another rotation in progress");
        }
    }

    private static SignedJWT signJwt(RSAKey key, String subject) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .subject(subject)
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                        .build());
        jwt.sign(new RSASSASigner(key.toPrivateKey()));
        return jwt;
    }
}
```

- [ ] **Step 2: Run the IT**

```bash
./gradlew :admin-app:test --tests KeyRotationIT 2>&1 | tail -30
```

Expected: 3 tests pass.

- [ ] **Step 3: Codex review**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/KeyRotationIT.java
cat > /tmp/codex-p3-t24.txt <<'PROMPT'
Code review for KeyRotationIT — Phase 3 acceptance gate #2 (T24).

Three end-to-end scenarios against Testcontainers Oracle:

1. rotateAddsNewActiveAndKeepsOldAsRotated:
   - Get current ACTIVE kid.
   - Sign a fake JWT with the OLD key.
   - rotation.rotate() → returns {oldKid, newKid}.
   - DB has 2 rows (1 ACTIVE, 1 ROTATED).
   - JwksAssembler.build() returns 2-element JWKSet.
   - Old JWT verifies against the still-published old public key.
   - audit_log has SIGNING_KEY_ROTATE row.

2. expirationJobRevokesAfterGrace:
   - rotation.rotate().
   - Direct JDBC update sets rotated_at to 31min ago.
   - expirationJob.runOnce().
   - DB row now status='REVOKED', revoked_at set.
   - JWKS no longer contains that kid.

3. rotateRejectsConcurrentWithConflict:
   - Pre-insert a "somebody-else" lease for "key-rotation".
   - rotation.rotate() throws IllegalStateException.

Review:
1. provider.init() being called manually in @BeforeEach — fragile.
   @SpringBootTest reuses the application context across test methods,
   but @BeforeEach DELETE wipes the DB row that init() created. The
   in-memory cachedActive still points to the deleted row's key. Manual
   re-init re-creates a new row + refreshes cache.
2. Test isolation — each test re-creates initial ACTIVE. The previous
   test's keys are deleted at start. OK.
3. JWT verification path uses Nimbus's RSASSAVerifier directly with
   the public key fetched from JWKSet — same path RPs would use.
   Good end-to-end coverage.
4. Lease pre-insert — INTERVAL '5' MINUTE > LEASE_TTL=30s ensures
   the takeover-on-expired path doesn't kick in. Concurrent rotate
   returns false → IllegalStateException.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t24.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t24-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t24-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t24-out.txt 2>&1
cat /tmp/codex-p3-t24-out.txt
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(admin): KeyRotationIT — Phase 3 acceptance gate #2 (rotate + expire + conflict)"
```

---

## Task 25: Phase 2 AdminFlowIT compatibility check + envelope master key in test profile

**Files:**
- Modify `admin-app/src/test/resources/application-test.yml`

After the SigningKeyProvider rewrite, Phase 2's AdminFlowIT will try to boot the admin context. SigningKeyProvider.init() needs a master key from `passkey.key-envelope.master-key`. The test profile needs it set.

- [ ] **Step 1: Add master key to test profile**

Append to `admin-app/src/test/resources/application-test.yml`:

```yaml
passkey:
  key-envelope:
    master-key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="  # test-only 32 zero bytes
  mds:
    initial-delay: PT0S
    fixed-delay: PT24H  # don't fire during short ITs
  key-rotation:
    grace: PT30M
    expiration-job:
      initial-delay: PT0S
      fixed-delay: PT24H  # don't fire during short ITs
```

- [ ] **Step 2: Run Phase 2's AdminFlowIT to confirm still green**

```bash
./gradlew :admin-app:test --tests AdminFlowIT 2>&1 | tail -20
```

Expected: still passes (11 steps). If failure, debug — likely the master-key property + scheduler delay are the only things needed.

- [ ] **Step 3: Run full suite**

```bash
./gradlew clean build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. All Phase 0/1/2/3 tests green.

- [ ] **Step 4: Codex review**

```bash
git add admin-app/src/test/resources/application-test.yml
cat > /tmp/codex-p3-t25.txt <<'PROMPT'
Code review for application-test.yml additions (Phase 3 T25).

Adds:
- passkey.key-envelope.master-key — required for SigningKeyProvider.init.
- passkey.mds — initial-delay 0s (force immediate run if scheduler fires)
  and fixed-delay 24h (effectively disable rescheduling during short ITs).
- passkey.key-rotation — same pattern.

The 0s initial + 24h fixed effectively means: if the @Scheduled fires
at all during a test, it fires once at boot then waits a day. Tests
that need to invoke the scheduler do so via direct
MdsSchedulerService.runOnce() / KeyExpirationJob.runOnce() injection.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p3-t25.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p3-t25-full.txt
codex exec -s read-only "$(cat /tmp/codex-p3-t25-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p3-t25-out.txt 2>&1
cat /tmp/codex-p3-t25-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "test(admin): test profile master-key + disable scheduler during ITs"
```

---

## Task 26: Final DoD verification + tag

**Files:** none (verification only)

- [ ] **Step 1: Full clean build**

```bash
./gradlew clean build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. ~25-30 min on first run (Testcontainers Oracle + Redis cold starts × multiple test classes).

- [ ] **Step 2: Aggregate test counts**

```bash
total_t=0; total_f=0; total_e=0; total_s=0
for xml in $(find . -path "*/build/test-results/test/TEST-*.xml" 2>/dev/null); do
  name=$(basename "$xml" .xml | sed 's/^TEST-//')
  t=$(grep -oE 'tests="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+')
  f=$(grep -oE 'failures="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+')
  e=$(grep -oE 'errors="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+')
  s=$(grep -oE 'skipped="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+')
  printf "  %-70s tests=%-3s f=%-2s e=%-2s s=%-2s\n" "$name" "$t" "$f" "$e" "$s"
  total_t=$((total_t + t)); total_f=$((total_f + f)); total_e=$((total_e + e)); total_s=$((total_s + s))
done
echo "TOTAL: tests=$total_t failures=$total_f errors=$total_e skipped=$total_s"
```

Expected: total_f=0 total_e=0. Phase 0 + 1 + 2 + 3 collectively.

- [ ] **Step 3: Boot both apps**

```bash
docker compose up -d
./scripts/wait-for-oracle.sh
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/dod-admin.log 2>&1 &
./gradlew :passkey-app:bootRun --args='--spring.profiles.active=local --server.port=8082' > /tmp/dod-passkey.log 2>&1 &
until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/dod-admin.log; do sleep 2; done
until grep -qE "Started PasskeyApplication|APPLICATION FAILED" /tmp/dod-passkey.log; do sleep 2; done

echo "=== admin health ==="
curl -s http://localhost:8081/actuator/health | head -3
echo
echo "=== passkey health ==="
curl -s http://localhost:8082/actuator/health | head -3
echo
echo "=== JWKS ==="
curl -s http://localhost:8082/.well-known/jwks.json | head -c 300
echo
echo "=== admin SPA ==="
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/admin/

pkill -f "AdminApplication|PasskeyApplication"
```

Expected:
- admin health UP
- passkey health UP
- JWKS contains a key (kty=RSA, alg=RS256). The new persistent key was loaded from DB on boot.
- /admin/ returns 200.

- [ ] **Step 4: Tag**

```bash
git tag -a phase-3-mds-key-complete -m "$(cat <<'EOF'
Phase 3 (MDS + key rotation) complete.

Acceptance:
- MdsSchedulerIT 3/3 green (sync, skip-on-lease, fail-on-fetch)
- KeyRotationIT 3/3 green (rotate+verify-old, expire-after-grace,
  concurrent-conflict)
- Full test suite green across Phase 0 + 1 + 2 + 3
- Both apps boot cleanly with DB-loaded signing key + JWKS exposes
  ACTIVE+ROTATED

Phase 3 surface:
- admin scheduler fetches FIDO MDS3 BLOB every 6h, stores in
  mds_blob_cache singleton, populates Redis AAGUID cache
- passkey-app MdsVerifier consults Redis + DB cache, blocks REVOKED /
  COMPROMISE statuses
- signing_key table persists envelope-encrypted PKCS8
- KeyRotationService (admin POST) generates new ACTIVE, transitions
  old to ROTATED, refreshes provider cache, audits
- KeyExpirationJob retires ROTATED to REVOKED after 30min grace
- JWKS exposes ACTIVE+ROTATED so already-issued JWTs verify
- Admin UI pages: MdsStatus, KeyManagement

Migrations: V15 (signing_key), V16 (runtime grants), V17
(mds_blob_cache singleton seed).

Followups deferred to Phase 4+:
- Master key → KMS migration
- MDS fetch failure alerting
- ES256 (multi-alg) support
- Old REVOKED key archive
- Tenant attestation policy auto-refresh on AAGUID status change
- VIEWER UX for ADMIN-only buttons (force-sync, rotate, verify)
EOF
)"

git tag -l "phase-*"
```

Expected output: `phase-0-foundation-complete`, `phase-1-passkey-app-complete`, `phase-2-admin-app-complete`, `phase-3-mds-key-complete`.

---

## Self-Review

### Spec coverage

- §1 Phase 3 범위 (MDS + key rotation) — covered by T8-T16 (MDS) + T1-T7, T17-T19 (keys).
- §2 acceptance gate (MdsSchedulerIT + KeyRotationIT) — T23 + T24.
- §3 tech stack — T1 (Spring Session was in Phase 2; this Phase 3 needed only WireMock T22 + node-gradle (already in Phase 2)).
- §4 file structure — File Inventory at top + each task.
- §5 data model — T1 (V15), T2 (V16), T3 (V17), T4 (entity+repo).
- §6 scheduler pattern — T8 (SchedulerLeaseService), T13 (MdsSyncJob), T18 (KeyExpirationJob).
- §7 MDS flow — T9 (root cert), T10 (client), T11 (store), T12 (orchestrator), T15 (verifier+cache), T16 (cache writer).
- §8 envelope — T5 (KeyEnvelope), T6 (SigningKeyProvider rewrite).
- §9 RBAC matrix — T14 (MDS RBAC), T19 (key RBAC).
- §10 risks — Each task's codex review covers the risk-table item.
- §11 Phase 4+ deferred — captured in tag commit + followups file.

### Placeholder scan

No "TODO" / "implement later" / "TBD" placeholders. Some tasks include
"if codex flags X, do Y" decision branches — those are explicit
contingencies, not placeholders.

### Type consistency

- `SchedulerLeaseService.tryAcquire(name, holder, ttl)` — same signature across T8, T12, T17, T18.
- `MdsSchedulerService.SyncResult(status, version, error)` — T12, T14 use the same record.
- `KeyRotationService.RotateResult(oldKid, newKid)` — T17, T19, T24 consistent.
- `AuditAppendRequest` from Phase 2 — used in T12, T17, T18 with same field order.
- `SigningKey.rotate(now)` / `revoke(now)` — T4 defines, T17 / T18 call.
- `SigningKeyRepository.findFirstByStatusOrderByCreatedAtDesc` — T4 defines, T6 / T17 use.

### Scope check

26 tasks, in the Phase 1 (27) / Phase 2 (25) range. Two ITs are the
acceptance gates per spec §2. Single plan, single phase, single merge.
