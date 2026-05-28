# Phase F2 — Tenant KPI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Close Gap #1, #2, #4 from `docs/superpowers/specs/2026-05-28-admin-ui-feature-gap-audit-design.md`. TenantsList/TenantDetail KPI columns (credentials/apiKeys/lastEventAt) and the WebAuthn 요약 카드 (`userVerification`/`attestationConveyance`/`timeoutMs`) show real DB values instead of `fixtures/tenantKpi.ts` and hardcoded constants.

**Architecture:** (1) `TenantView` (admin-app DTO) gains 3 KPI fields populated by aggregate queries on `credential`/`api_key`/`audit_log`. (2) `tenant` schema gains 2 new columns (`attestation_conveyance` VARCHAR2, `webauthn_timeout_ms` NUMBER) via Migration V33, with entity + DTO + UI adapter wiring. **admin-ui design must not change.**

**Tech Stack:** Spring Boot, JPA/Hibernate (Oracle), Flyway (V33), React + TypeScript.

**Spec reference:** `docs/superpowers/specs/2026-05-28-admin-ui-server-gap-fill-design.md` § Phase F2.

---

## Execution policy (carries over from F1)

1. **Tests are minimal.** Compile/typecheck + at most one smoke per new persistence path. Trust frameworks; full regression at the final task.
2. **Per-task codex review** on staged diff before commit. Must-fix → apply → re-stage → commit.
3. **Autonomous decisions during execution** — pick the recommended option, surface non-obvious choices in commit messages.

---

## Schema discovery findings (informs plan)

Inspected during plan authoring:

- `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java` has columns: SLUG, DISPLAY_NAME, STATUS, RP_ID, RP_NAME, REQUIRE_USER_VERIFICATION, MDS_REQUIRED. **No** `attestation_conveyance`, **no** `webauthn_timeout_ms`.
- `admin-ui/src/api/webauthn.ts:17-18` hardcodes `attestationConveyance: 'NONE'` and `timeoutMs: 60000` as fixture defaults because the server doesn't supply them.
- `admin-ui/src/pages/tenant/TenantOverview.tsx:189-191` hardcodes `REQUIRED` / `DIRECT` / `60s` in the WebAuthn 요약 card — because the `Tenant` designType (`admin-ui/src/api/designTypes.ts:5-15`) does not include webauthn config fields at all.

**Resolution:** F2.2 adds V33 migration + entity columns + DTO fields + adapter wiring. Without this, Gap #4 cannot truly be closed.

---

## File Structure

### Backend — F2.1 KPI aggregation

```
admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java       # modify — extend TenantView with 3 KPI fields
admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java   # modify — list()/get() compute KPIs
admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantKpiIT.java          # new — single smoke verifying KPI fields appear
```

### Backend — F2.2 Webauthn config columns

```
core/src/main/resources/db/migration/V33__tenant_webauthn_extra.sql    # new — attestation_conveyance + webauthn_timeout_ms + defaults
core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java       # modify — add fields + accessors
admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java   # modify — extend TenantView with webauthn fields; extend TenantCreateRequest/TenantUpdateRequest
admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java # modify — wire new fields into create/update + read
```

### Frontend — F2.1 + F2.2

```
admin-ui/src/api/types.ts                          # modify — extend TenantView with KPI + webauthn fields
admin-ui/src/api/designTypes.ts                    # modify — extend Tenant with webauthn fields
admin-ui/src/api/tenants.ts                        # modify — remove getTenantKpi merge; map new fields
admin-ui/src/api/webauthn.ts                       # modify — read attestation/timeout from server (drop fixture defaults)
admin-ui/src/pages/tenant/TenantOverview.tsx       # modify — read 3 webauthn fields from tenant (drop hardcoded badges)
admin-ui/src/fixtures/tenantKpi.ts                 # delete
```

---

## Working directory & branch

Worktree: `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/gap-fill-f2/`, branch `worktree-gap-fill-f2` (forked from main at `8ac2352`).

---

## Task 1: V33 tenant webauthn extra columns

**Files:**
- Create: `core/src/main/resources/db/migration/V33__tenant_webauthn_extra.sql`

- [ ] **Step 1: Write migration (idempotent)**

```sql
-- ============================================================
-- V33 — tenant.attestation_conveyance + tenant.webauthn_timeout_ms
--
-- 목적: TenantOverview WebAuthn 요약 카드 + WebauthnConfigTab attestation/timeout
--       의 fixture/하드코딩 (Gap #4) 제거.
--
-- attestation_conveyance: WebAuthn AttestationConveyancePreference enum string.
--   Valid: 'NONE' | 'INDIRECT' | 'DIRECT' | 'ENTERPRISE'. Default 'NONE' (spec default).
-- webauthn_timeout_ms: ceremony timeout in milliseconds (passkey-app uses for
--   PublicKeyCredentialCreationOptions.timeout). Default 60000 (60s).
--
-- Idempotent: ALTER wrapped in EXCEPTION (ORA-01430 = column exists).
-- ============================================================

DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE tenant ADD (attestation_conveyance VARCHAR2(16) DEFAULT ''NONE'' NOT NULL)';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/

DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE tenant ADD (webauthn_timeout_ms NUMBER(10,0) DEFAULT 60000 NOT NULL)';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/

DECLARE
  e_check_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_check_exists, -2264);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE tenant ADD CONSTRAINT ck_tenant_attestation CHECK (attestation_conveyance IN (''NONE'',''INDIRECT'',''DIRECT'',''ENTERPRISE''))';
EXCEPTION
  WHEN e_check_exists THEN NULL;
END;
/

DECLARE
  e_check_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_check_exists, -2264);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE tenant ADD CONSTRAINT ck_tenant_timeout_range CHECK (webauthn_timeout_ms BETWEEN 1000 AND 600000)';
EXCEPTION
  WHEN e_check_exists THEN NULL;
END;
/

COMMIT;
```

- [ ] **Step 2: Apply via sqlplus**

```bash
docker exec -i passkey-oracle sqlplus -s APP_OWNER/app_owner_pw@//localhost:1521/XEPDB1 < core/src/main/resources/db/migration/V33__tenant_webauthn_extra.sql
```

Expected: 4 anonymous PL/SQL blocks succeed, Commit complete.

- [ ] **Step 3: Verify**

```bash
docker exec -i passkey-oracle sqlplus -s APP_OWNER/app_owner_pw@//localhost:1521/XEPDB1 <<'SQL'
SET LINESIZE 200
SELECT column_name, data_type, data_default, nullable FROM user_tab_columns
  WHERE table_name = 'TENANT' AND column_name IN ('ATTESTATION_CONVEYANCE','WEBAUTHN_TIMEOUT_MS');
SELECT id, slug, attestation_conveyance, webauthn_timeout_ms FROM tenant ORDER BY slug;
SQL
```

Expected: two new columns. Existing tenant rows have `NONE` / `60000` (DEFAULT applied to existing rows on ADD COLUMN with DEFAULT NOT NULL in Oracle 12c+).

- [ ] **Step 4: Codex review + commit**

```bash
git add core/src/main/resources/db/migration/V33__tenant_webauthn_extra.sql
```

Dispatch codex on staged diff. Prompt: "codex review V33 migration. Focus: idempotency (ORA-01430 ADD COLUMN, ORA-02264 ADD CONSTRAINT), CHECK constraint values cover WebAuthn AttestationConveyancePreference spec, default values applied to existing rows. Must-fix only."

Commit:
```bash
git commit -m "feat(core): V33 tenant.attestation_conveyance + webauthn_timeout_ms (Gap #4)"
```

---

## Task 2: Extend Tenant entity

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java`

- [ ] **Step 1: Inspect current state**

```bash
sed -n '25,45p' core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java
```

You should see existing columns including `REQUIRE_USER_VERIFICATION` and `MDS_REQUIRED`.

- [ ] **Step 2: Add two new fields after MDS_REQUIRED**

After the `mdsRequired` field declaration (around line 30-31), add:

```java
    @Column(name = "ATTESTATION_CONVEYANCE", length = 16, nullable = false)
    private String attestationConveyance = "NONE";

    @Column(name = "WEBAUTHN_TIMEOUT_MS", nullable = false)
    private int webauthnTimeoutMs = 60000;
```

- [ ] **Step 3: Add accessors at the bottom of the class (mirror existing pattern)**

```java
    public String getAttestationConveyance() { return attestationConveyance; }
    public void setAttestationConveyance(String v) { this.attestationConveyance = v; }

    public int getWebauthnTimeoutMs() { return webauthnTimeoutMs; }
    public void setWebauthnTimeoutMs(int v) { this.webauthnTimeoutMs = v; }
```

- [ ] **Step 4: Compile**

```bash
./gradlew :core:compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Codex + commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java
```

Codex prompt: "codex review Tenant entity adding attestationConveyance + webauthnTimeoutMs fields. Verify column mapping matches V33 schema (VARCHAR2(16) NOT NULL DEFAULT 'NONE' / NUMBER NOT NULL DEFAULT 60000), accessor naming consistent with class style. Must-fix only."

```bash
git commit -m "feat(core): Tenant.attestationConveyance + webauthnTimeoutMs (Gap #4)"
```

---

## Task 3: Extend TenantView DTO with webauthn fields + KPI fields

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java`

This is the F2.1 + F2.2 backbone DTO change. We add 5 new fields to TenantView:
- F2.2: `attestationConveyance: String`, `webauthnTimeoutMs: int`
- F2.1: `credentials: long`, `apiKeys: long`, `lastEventAt: Instant?`

We also extend `TenantCreateRequest` and `TenantUpdateRequest` to accept the two webauthn fields so the UI can edit them.

- [ ] **Step 1: Replace TenantView record + update `from()`**

Find the current `TenantView` record (around line 38-58):

```java
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
```

Replace with:

```java
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
            String attestationConveyance,
            int webauthnTimeoutMs,
            long credentials,
            long apiKeys,
            Instant lastEventAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static TenantView from(Tenant t, long credentials, long apiKeys, Instant lastEventAt) {
            return new TenantView(
                    t.getId(), t.getSlug(), t.getDisplayName(), t.getStatus(),
                    t.getRpId(), t.getRpName(),
                    t.getAllowedOriginValues(),
                    t.getAcceptedFormatValues(),
                    t.isRequireUserVerification(),
                    t.isMdsRequired(),
                    t.getAttestationConveyance(),
                    t.getWebauthnTimeoutMs(),
                    credentials, apiKeys, lastEventAt,
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }
```

The `from(t)` overload is intentionally removed — every call site must supply KPI inputs. This is the right ergonomic for keeping the service layer the single place that computes counts.

- [ ] **Step 2: Extend TenantCreateRequest and TenantUpdateRequest**

Find `TenantCreateRequest` (lines 16-26) and `TenantUpdateRequest` (lines 28-36). For each, add two fields BEFORE the closing `) {}`:

For both records, append after `mdsRequired`:
```java
            @NotBlank @Pattern(regexp = "^(NONE|INDIRECT|DIRECT|ENTERPRISE)$") String attestationConveyance,
            @Min(1000) @Max(600000) int webauthnTimeoutMs
```

So `TenantCreateRequest` becomes:
```java
    public record TenantCreateRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$") String slug,
            @NotBlank @Size(max = 256) String displayName,
            @NotBlank @Size(max = 256) String rpId,
            @NotBlank @Size(max = 256) String rpName,
            @NotEmpty List<@NotBlank @Size(max = 512) String> allowedOrigins,
            @NotEmpty Set<@NotBlank @Size(max = 32) String> acceptedFormats,
            @NotNull Boolean requireUserVerification,
            @NotNull Boolean mdsRequired,
            @NotBlank @Pattern(regexp = "^(NONE|INDIRECT|DIRECT|ENTERPRISE)$") String attestationConveyance,
            @Min(1000) @Max(600000) int webauthnTimeoutMs
    ) {}
```

`TenantUpdateRequest` analogously (same two fields appended).

- [ ] **Step 3: Add Min/Max imports**

At top of file, the existing imports include `jakarta.validation.constraints.*` (wildcard), so `Min`/`Max`/`Pattern` are already available. Verify:

```bash
head -10 admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java
```

If imports are explicit (not wildcard), add the missing ones.

- [ ] **Step 4: Compile (will fail at every TenantView.from(t) callsite — fixed in Task 4)**

```bash
./gradlew :admin-app:compileJava 2>&1 | tail -30
```

Expected: compile errors at every `TenantView.from(t)` call (in `TenantAdminService` and possibly tests). Note the file:line of each to fix in Task 4.

- [ ] **Step 5: Codex review on staged DTO change ONLY**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java
```

Codex prompt: "codex review TenantAdminDto staged diff: added 5 fields to TenantView (attestationConveyance, webauthnTimeoutMs, credentials, apiKeys, lastEventAt), extended TenantCreateRequest/TenantUpdateRequest with 2 webauthn fields, removed from(t) single-arg overload. Verify: field shapes, validation annotations, pattern regex for AttestationConveyancePreference enum. Must-fix only."

- [ ] **Step 6: Defer commit to Task 4 (combined commit)**

Do NOT commit yet — Task 4 fixes the callsite breakage. Combined commit comes at the end of Task 4.

---

## Task 4: TenantAdminService — supply KPI args and webauthn fields

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`

- [ ] **Step 1: Inspect current service**

```bash
grep -n "TenantView.from\|public.*list\|public.*get\|public.*create\|public.*update" admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java
```

Identify:
- All `TenantView.from(t)` callsites (likely in `list()`, `get(id)`, `create()`, `update()`)
- The repositories injected (TenantRepository at minimum; we'll need CredentialRepository, ApiKeyRepository, AuditLogRepository)

- [ ] **Step 2: Inject required repositories**

Find the constructor. Add three more repositories to the field list and constructor signature:

```java
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import com.crosscert.passkey.admin.audit.AuditLogRepository;  // (or wherever it lives — grep)
```

Then in the class:
```java
    private final CredentialRepository credentialRepo;
    private final ApiKeyRepository apiKeyRepo;
    private final AuditLogRepository auditLogRepo;
```

Add to constructor parameters and `this.X = X` assignments. **IMPORTANT:** before assuming class names, run:

```bash
find core/src/main/java admin-app/src/main/java -name "CredentialRepository.java" -o -name "ApiKeyRepository.java" -o -name "AuditLogRepository.java" 2>/dev/null
```

Use exact discovered names. If repositories are missing methods like `countByTenantId`, add them in their respective interfaces (Spring Data JPA derives implementation from method name).

- [ ] **Step 3: Add a private toView(Tenant) helper**

```java
    private TenantView toView(Tenant t) {
        long credentials = credentialRepo.countByTenantId(t.getId());
        long apiKeys = apiKeyRepo.countByTenantIdAndStatus(t.getId(), "ACTIVE");
        Instant lastEventAt = auditLogRepo
                .findFirstByTenantIdOrderByCreatedAtDesc(t.getId())
                .map(row -> row.getCreatedAt())
                .orElse(null);
        return TenantView.from(t, credentials, apiKeys, lastEventAt);
    }
```

Replace `AuditLog` with the actual entity class name returned by `findFirstByTenantIdOrderByCreatedAtDesc` (could be `AuditLogEntity`, `AuditLog`, or similar — grep first).

**IMPORTANT for `list()`:** if there are many tenants, looping `toView(t)` causes N+1 (3 queries per tenant). For the current seed (4 tenants) this is fine; **note in the commit message** that an aggregate optimization is deferred until tenant count grows. If you can do it ergonomically with one grouped query, do so — but the simple approach is acceptable here.

- [ ] **Step 4: Wire repository method names**

If `CredentialRepository` does not yet have `countByTenantId(UUID)`, add it. Same for `ApiKeyRepository.countByTenantIdAndStatus(UUID, String)` and `AuditLogRepository.findFirstByTenantIdOrderByCreatedAtDesc(UUID)`. Spring Data JPA derives these.

If method already exists with similar signature, reuse it.

- [ ] **Step 5: Replace every `TenantView.from(t)` callsite**

Use the new `toView(Tenant)` private helper at every callsite. Update `list()` to map through `toView`, `get(id)` to call `toView`, `create()` and `update()` to call `toView(savedTenant)`.

- [ ] **Step 6: For create()/update(), also wire the two new webauthn fields**

`TenantCreateRequest` and `TenantUpdateRequest` now have `attestationConveyance` and `webauthnTimeoutMs`. Set them on the Tenant entity in create() and update():

```java
    // inside create()
    t.setAttestationConveyance(req.attestationConveyance());
    t.setWebauthnTimeoutMs(req.webauthnTimeoutMs());

    // inside update()
    t.setAttestationConveyance(req.attestationConveyance());
    t.setWebauthnTimeoutMs(req.webauthnTimeoutMs());
```

Place these next to the existing requireUserVerification/mdsRequired setters.

- [ ] **Step 7: Compile**

```bash
./gradlew :admin-app:compileJava
```

If compile errors at unrelated callsites (e.g., tests calling `TenantView.from(t)` with one arg), fix them by passing `0L, 0L, null` for KPIs (tests don't usually verify KPI shape — minimal fix).

- [ ] **Step 8: Codex + combined commit (Task 3 + Task 4)**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminDto.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java \
        admin-app/src/main/java/com/crosscert/passkey/core/entity/Tenant.java
# Also add any repository interface changes:
git status --short
```

Codex prompt: "codex review TenantAdminService + DTO combined diff. Verify: every TenantView.from callsite supplies all 16 args, repository queries (countByTenantId, countByTenantIdAndStatus, findFirstByTenantIdOrderByCreatedAtDesc) are correctly named for Spring Data derivation, create/update set the new webauthn fields, N+1 risk noted. Must-fix only."

```bash
git commit -m "feat(admin-app): TenantView KPI + webauthn fields (Gap #1/#2/#4)"
```

---

## Task 5: TenantKpi smoke IT

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantKpiIT.java`

Per execution policy §1: one smoke per new persistence path. We verify GET tenant returns the 5 new fields.

- [ ] **Step 1: Inspect existing IT scaffolding**

Look at `SecurityPolicyIT.java` (committed in F1 Task 6) for the established self-contained Testcontainers + loginAs pattern. Copy that scaffold.

- [ ] **Step 2: Write IT**

```java
package com.crosscert.passkey.admin.tenant;

// === Copy imports + class-level @SpringBootTest + Testcontainers + loginAs ===
// === from SecurityPolicyIT.java VERBATIM. ===

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class TenantKpiIT {

    // copy OracleContainer + Redis + @DynamicPropertySource fields verbatim

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    // copy loginAs helper verbatim

    @Test
    void getTenantReturnsKpiAndWebauthnFields() {
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");

        // List tenants — pick first one
        ResponseEntity<JsonNode> listRes = rest.exchange(
                "http://localhost:" + port + "/admin/api/tenants",
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(listRes.getStatusCode().value()).isEqualTo(200);
        JsonNode arr = listRes.getBody();
        assertThat(arr.isArray()).isTrue();
        assertThat(arr).isNotEmpty();

        JsonNode first = arr.get(0);
        // 5 new fields exist
        assertThat(first.has("credentials")).isTrue();
        assertThat(first.has("apiKeys")).isTrue();
        assertThat(first.has("lastEventAt")).isTrue();
        assertThat(first.has("attestationConveyance")).isTrue();
        assertThat(first.has("webauthnTimeoutMs")).isTrue();

        // KPI fields are numeric (counts may be 0 for fresh test DB — that's fine)
        assertThat(first.get("credentials").isNumber()).isTrue();
        assertThat(first.get("apiKeys").isNumber()).isTrue();

        // Webauthn fields have default values
        assertThat(first.get("attestationConveyance").asText()).isEqualTo("NONE");
        assertThat(first.get("webauthnTimeoutMs").asInt()).isEqualTo(60000);
    }
}
```

- [ ] **Step 3: Run**

```bash
./gradlew :admin-app:test --tests TenantKpiIT
```
Expected: 1 test passes. First run is slow (~60-120s).

- [ ] **Step 4: Codex + commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantKpiIT.java
```

Codex prompt: "codex review TenantKpiIT smoke. Verify field-name assertions match TenantView JSON property names exactly. Must-fix only."

```bash
git commit -m "test(admin-app): TenantKpiIT verifies new KPI + webauthn fields (Gap #1/#2/#4)"
```

---

## Task 6: Frontend types — extend TenantView (server) and Tenant (designType)

**Files:**
- Modify: `admin-ui/src/api/types.ts` — add 5 fields to server `TenantView`
- Modify: `admin-ui/src/api/designTypes.ts` — add 3 webauthn fields to `Tenant`

- [ ] **Step 1: Inspect both files**

```bash
grep -nE "(export type TenantView|export type Tenant\b|attestation|userVerification|webauthnTimeoutMs|credentials)" admin-ui/src/api/types.ts admin-ui/src/api/designTypes.ts
```

- [ ] **Step 2: Modify `api/types.ts` — add to server `TenantView`**

Append 5 fields to the server TenantView shape (matching the Java DTO order):
```typescript
  attestationConveyance: 'NONE' | 'INDIRECT' | 'DIRECT' | 'ENTERPRISE';
  webauthnTimeoutMs: number;
  credentials: number;
  apiKeys: number;
  lastEventAt: string | null;
```

Also extend `TenantCreateRequest` and `TenantUpdateRequest` (in `types.ts`) with:
```typescript
  attestationConveyance: 'NONE' | 'INDIRECT' | 'DIRECT' | 'ENTERPRISE';
  webauthnTimeoutMs: number;
```

- [ ] **Step 3: Modify `api/designTypes.ts` — add 3 webauthn fields to `Tenant`**

Current Tenant type:
```typescript
export type Tenant = {
  id: string;
  name: string;
  slug: string;
  rpId: string;
  status: 'ACTIVE' | 'SUSPENDED';
  credentials: number;
  apiKeys: number;
  lastEventAt: string | null;
  createdAt: string;
};
```

Append:
```typescript
  userVerification: 'REQUIRED' | 'PREFERRED';
  attestationConveyance: 'NONE' | 'INDIRECT' | 'DIRECT' | 'ENTERPRISE';
  webauthnTimeoutMs: number;
```

- [ ] **Step 4: tsc check**

```bash
cd admin-ui && npx tsc --noEmit
```

Expected failures at:
- `api/tenants.ts:adaptTenant` (missing new fields in returned object)
- `api/webauthn.ts:adaptConfig` (fixture defaults can now read from t.attestationConveyance / t.webauthnTimeoutMs)
- `pages/TenantsListPage.tsx` and `pages/tenant/TenantsList*.tsx` (using designType Tenant — should still work since we only added fields)
- `pages/tenant/TenantOverview.tsx` (uses designType Tenant — accesses requireUserVerification but designType only has userVerification — need verify)

Fix only the actual breakage in tasks 7-9.

- [ ] **Step 5: Codex on TYPE changes only + commit**

```bash
git add admin-ui/src/api/types.ts admin-ui/src/api/designTypes.ts
```

Codex prompt: "codex review TS type extensions for tenants. Verify field shapes match Java DTOs (attestationConveyance enum, webauthnTimeoutMs number, credentials/apiKeys number, lastEventAt nullable string). Must-fix only."

```bash
git commit -m "feat(admin-ui): TenantView + Tenant types extended (Gap #1/#2/#4 prep)"
```

---

## Task 7: Update `api/tenants.ts` adapter — drop fixture KPI merge + map new fields

**Files:**
- Modify: `admin-ui/src/api/tenants.ts`

- [ ] **Step 1: Read current**

```bash
cat admin-ui/src/api/tenants.ts
```

You'll see:
```typescript
import { getTenantKpi } from '@/fixtures/tenantKpi';

function adaptTenant(s: TenantView): Tenant {
  const kpi = getTenantKpi(s.id);
  return {
    id: s.id, name: s.displayName, slug: s.slug, rpId: s.rpId,
    status: ...,
    credentials: kpi.credentials,
    apiKeys: kpi.apiKeys,
    lastEventAt: kpi.lastEventAt,
    createdAt: s.createdAt,
  };
}
```

- [ ] **Step 2: Remove the fixture import and switch to server fields**

Replace the import line:
```typescript
import { getTenantKpi } from '@/fixtures/tenantKpi';
```

with nothing (delete the line).

Replace the `adaptTenant` function body:
```typescript
function adaptTenant(s: TenantView): Tenant {
  return {
    id: s.id,
    name: s.displayName,
    slug: s.slug,
    rpId: s.rpId,
    status: s.status?.toUpperCase() === 'ACTIVE' || s.status === 'active' ? 'ACTIVE' : 'SUSPENDED',
    credentials: s.credentials,
    apiKeys: s.apiKeys,
    lastEventAt: s.lastEventAt,
    createdAt: s.createdAt,
    userVerification: s.requireUserVerification ? 'REQUIRED' : 'PREFERRED',
    attestationConveyance: s.attestationConveyance,
    webauthnTimeoutMs: s.webauthnTimeoutMs,
  };
}
```

- [ ] **Step 3: Update tenant create() body to include new fields**

In the existing `create()` body, the `TenantCreateRequest` now requires `attestationConveyance` and `webauthnTimeoutMs`. Append sensible defaults:

```typescript
const body: TenantCreateRequest = {
  slug: input.slug,
  displayName: input.name,
  rpId: input.slug + '.example.com',
  rpName: input.name,
  allowedOrigins: ['https://' + input.slug + '.example.com'],
  acceptedFormats: ['none', 'packed'],
  requireUserVerification: true,
  mdsRequired: false,
  attestationConveyance: 'NONE',
  webauthnTimeoutMs: 60000,
};
```

- [ ] **Step 4: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```

If tsc still errors at other files (TenantOverview, webauthn.ts), that's expected — leave for Tasks 8-9.

- [ ] **Step 5: Codex + commit**

```bash
git add admin-ui/src/api/tenants.ts
```

Codex prompt: "codex review tenants.ts adapter. Verify fixture removed, server fields mapped 1:1, create() body has new required fields. Must-fix only."

```bash
git commit -m "feat(admin-ui): tenants adapter uses server KPI + webauthn (Gap #1/#2/#4)"
```

---

## Task 8: Update `api/webauthn.ts` — read attestation/timeout from server

**Files:**
- Modify: `admin-ui/src/api/webauthn.ts`

- [ ] **Step 1: Replace the fixture defaults**

Find `adaptConfig` (lines 10-21):

```typescript
function adaptConfig(t: TenantView): ConfigWithMds {
  return {
    rpId: t.rpId,
    rpName: t.rpName,
    origins: t.allowedOrigins,
    formats: t.acceptedFormats,
    userVerification: t.requireUserVerification ? 'REQUIRED' : 'PREFERRED',
    attestationConveyance: 'NONE',   // 서버에 없음 — fixture default
    timeoutMs: 60000,                 // 서버에 없음 — fixture default
    _mdsRequired: t.mdsRequired,
  };
}
```

Replace the two fixture-default lines:
```typescript
    attestationConveyance: t.attestationConveyance,
    timeoutMs: t.webauthnTimeoutMs,
```

Resulting function:
```typescript
function adaptConfig(t: TenantView): ConfigWithMds {
  return {
    rpId: t.rpId,
    rpName: t.rpName,
    origins: t.allowedOrigins,
    formats: t.acceptedFormats,
    userVerification: t.requireUserVerification ? 'REQUIRED' : 'PREFERRED',
    attestationConveyance: t.attestationConveyance,
    timeoutMs: t.webauthnTimeoutMs,
    _mdsRequired: t.mdsRequired,
  };
}
```

- [ ] **Step 2: Also extend update() and diff() to send the new fields**

In `update()`, the `TenantUpdateRequest` now requires `attestationConveyance` + `webauthnTimeoutMs`. Add them from the WebauthnConfig `body`:

```typescript
const updateReq: TenantUpdateRequest = {
  displayName,
  rpId: body.rpId,
  rpName: body.rpName,
  allowedOrigins: body.origins,
  acceptedFormats: body.formats,
  requireUserVerification: body.userVerification === 'REQUIRED',
  mdsRequired,
  attestationConveyance: body.attestationConveyance,
  webauthnTimeoutMs: body.timeoutMs,
};
```

In `diff()`, similarly extend the `WebauthnDiffRequest`:
```typescript
const body: WebauthnDiffRequest = {
  rpId: proposed.rpId,
  rpName: proposed.rpName,
  allowedOrigins: proposed.origins,
  acceptedFormats: proposed.formats,
  requireUserVerification: proposed.userVerification === 'REQUIRED',
  mdsRequired,
  attestationConveyance: proposed.attestationConveyance,
  webauthnTimeoutMs: proposed.timeoutMs,
};
```

The `WebauthnDiffRequest` TS type (in `api/types.ts`) also needs the 2 new fields. Verify and add if missing.

- [ ] **Step 3: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```

Expected: should pass at this point if `WebauthnDiffRequest` type has the 2 fields. If not, add them to `types.ts` first.

- [ ] **Step 4: Codex + commit**

```bash
git add admin-ui/src/api/webauthn.ts admin-ui/src/api/types.ts  # if types.ts touched
```

Codex prompt: "codex review webauthn.ts — fixture defaults replaced with server fields, update/diff send new attestation/timeout. Verify TenantView/UpdateRequest/DiffRequest TS types match Java DTOs. Must-fix only."

```bash
git commit -m "feat(admin-ui): webauthn adapter uses server attestation/timeout (Gap #4)"
```

---

## Task 9: TenantOverview — drop hardcoded badges (DESIGN UNCHANGED)

**Files:**
- Modify: `admin-ui/src/pages/tenant/TenantOverview.tsx`

## CRITICAL: ZERO design changes

The badge JSX (`<span className="badge badge--accent">`), label structure, KV component, surrounding card all stay identical. Only the expression inside the `v={…}` props changes from string literals to `tenant.X` reads.

- [ ] **Step 1: Read the current WebAuthn 요약 카드 block**

```bash
sed -n '180,200p' admin-ui/src/pages/tenant/TenantOverview.tsx
```

You should see:
```typescript
<KV k="userVerification" v={<span className="badge badge--accent">REQUIRED</span>} />
<KV k="attestation" v={<span className="badge">DIRECT</span>} />
<KV k="timeout" v="60s" />
```

- [ ] **Step 2: Replace the 3 hardcoded values with `tenant.X`**

Find:
```typescript
<KV k="userVerification" v={<span className="badge badge--accent">REQUIRED</span>} />
<KV k="attestation" v={<span className="badge">DIRECT</span>} />
<KV k="timeout" v="60s" />
```

Replace with:
```typescript
<KV k="userVerification" v={<span className="badge badge--accent">{tenant.userVerification}</span>} />
<KV k="attestation" v={<span className="badge">{tenant.attestationConveyance}</span>} />
<KV k="timeout" v={`${tenant.webauthnTimeoutMs / 1000}s`} />
```

JSX tree, className, KV, span all identical. Only text content sources change.

- [ ] **Step 3: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```

Expected: pass. If `tenant.userVerification` errors, check Task 6 actually added it to designType `Tenant`.

- [ ] **Step 4: Codex + commit**

```bash
git add admin-ui/src/pages/tenant/TenantOverview.tsx
```

Codex prompt: "codex review TenantOverview.tsx — CRITICAL: verify ZERO design changes. Only the 3 hardcoded badge contents (REQUIRED / DIRECT / 60s) replaced with tenant.X expressions. JSX, className, KV component, surrounding card structure byte-identical. Must-fix only."

```bash
git commit -m "feat(admin-ui): TenantOverview shows real WebAuthn config (Gap #4)"
```

---

## Task 10: Delete `fixtures/tenantKpi.ts`

**Files:**
- Delete: `admin-ui/src/fixtures/tenantKpi.ts`

- [ ] **Step 1: Verify zero references**

```bash
grep -rn "fixtures/tenantKpi\|getTenantKpi\|tenantKpiFixture" admin-ui/src/
```

Expected: zero matches outside the file itself.

- [ ] **Step 2: Delete**

```bash
git rm admin-ui/src/fixtures/tenantKpi.ts
```

- [ ] **Step 3: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```

Expected: pass.

- [ ] **Step 4: Codex + commit**

```bash
git diff --cached --stat
```

Codex prompt: "codex review the staged deletion of fixtures/tenantKpi.ts. Verify no remaining references in admin-ui/src/. Must-fix only."

```bash
git commit -m "chore(admin-ui): delete fixtures/tenantKpi.ts (Gap #1/#2)"
```

---

## Task 11: Phase F2 regression gate + cumulative codex + `--no-ff` merge to main

**Files:** (none — verification step)

- [ ] **Step 1: Backend test suite**

```bash
./gradlew :admin-app:test
```

Expected: BUILD SUCCESSFUL. New `TenantKpiIT` passes. Existing tests should still pass — `TenantCreateRequest` and `TenantUpdateRequest` got 2 new fields, so any test that constructs these requests needs to be updated.

If tests fail, look for `TenantCreateRequest(` or `TenantUpdateRequest(` constructions in tests and add `, "NONE", 60000` to them. Note: these are the 20 pre-existing `*ControllerSecurityTest` failures from F1 — DO NOT confuse those with F2 regressions.

To get a clean compare: `git stash && ./gradlew :admin-app:test --tests "Tenant*"`; then `git stash pop`. If main has the same failures, they're not F2 regressions.

- [ ] **Step 2: TS check**

```bash
cd admin-ui && npx tsc --noEmit
```

Expected: pass.

- [ ] **Step 3: Cumulative codex review**

```bash
git diff main..HEAD --stat
```

Dispatch codex on the cumulative diff. Prompt: "codex review the cumulative F2 diff. Focus: integration consistency (every server field has TS mirror), V33 migration safety, no N+1 surprise in TenantAdminService.list (4 tenants OK), TenantOverview design unchanged (only expression sources). Must-fix only. APPROVED for merge if clean."

Apply must-fix (single follow-up commit `fix(f2): codex final review feedback`).

- [ ] **Step 4: Merge `--no-ff` to main**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git merge --no-ff worktree-gap-fill-f2 -m "Merge Phase F2 — Tenant KPI + WebAuthn config (Gap #1/#2/#4)"
git log --oneline -5
```

- [ ] **Step 5: Manual smoke checklist for user**

- TenantsList: each row's `등록 Credential` / `유효 API Key` / `최근 활동` columns show real values (probably 0/0/null in fresh test DB — that's correct).
- TenantDetail → Overview: KPI cards 등록 Credential / 유효 API Key show real counts.
- TenantDetail → Overview → WebAuthn 요약: rpId/rpName show real values (already worked), userVerification/attestation/timeout show DB-backed values, not hardcoded REQUIRED/DIRECT/60s.
- Change attestation_conveyance for a tenant in DB: `UPDATE tenant SET attestation_conveyance='DIRECT' WHERE slug='acme-corp'; COMMIT;`. Refresh — Overview now shows DIRECT instead of NONE.

---

## Phase F2 Summary

**What ships:**
- V33 migration: `tenant.attestation_conveyance` + `tenant.webauthn_timeout_ms` columns
- `Tenant` entity: 2 new accessors
- `TenantView` DTO: +5 fields (3 KPI + 2 webauthn)
- `TenantCreateRequest`/`UpdateRequest`: +2 webauthn fields (validation)
- `TenantAdminService`: aggregate queries for KPI computation
- Frontend types extended; `tenants.ts` and `webauthn.ts` adapters drop fixture/hardcoded defaults
- `TenantOverview.tsx`: 3 hardcoded badges → real tenant fields
- Deleted: `fixtures/tenantKpi.ts`
- New IT: `TenantKpiIT` (single round-trip smoke)

**Design impact:** zero. Only adapter calls and expression sources changed.

**Closed gaps:** #1 (TenantsList KPI), #2 (TenantOverview KPI cards), #4 (TenantOverview WebAuthn 요약 card).

**Next phase:** F3 — Funnel + Activity hook.
