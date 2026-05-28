# Phase F1 — Critical Gap Fill (SecurityPolicy + AdminMFA) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the two Critical gaps from `docs/superpowers/specs/2026-05-28-admin-ui-feature-gap-audit-design.md` — make SecurityPolicy save actually persist to DB, and make AdminUsers' MFA column reflect real DB state. **admin-ui design must not change.**

**Architecture:** New `policy/SecurityPolicy*` backend (Controller + Service + Repository + Entity + Migration V31), and column extension to `admin_user` (Migration V32) for `mfa_enabled`. Frontend swaps fixture usage for real adapter calls; no JSX/className/structural changes.

**Tech Stack:** Spring Boot (admin-app), JPA/Hibernate (Oracle), Flyway (V31/V32 migrations — Flyway runs at admin-app boot, not via gradle task), React + TypeScript (admin-ui), Jackson, JUnit 5 + Testcontainers (existing IT pattern).

> **NOTE (post-Task 1 correction):** V30 slot was already taken by `V30__admin_user_invitation_runtime_grants.sql` (discovered during Task 1 execution). F1's two migrations shift to V31 (security_policy) and V32 (admin_user MFA). Subsequent phases F2/F4 numbering shifts accordingly.

**Spec reference:** `docs/superpowers/specs/2026-05-28-admin-ui-server-gap-fill-design.md` § Phase F1.

---

## Execution policy (user-defined, applies to every task)

1. **Tests are minimal — development speed first.** Per-task tests are limited to:
   - Compile/typecheck (must pass)
   - One smoke test only when the task introduces a new endpoint or persistence path
   - Existing test suite must still pass at Task 17 (full regression gate)
   - **No exhaustive unit tests, no edge-case test sweeps.** Trust the framework + manual smoke at Task 17.
2. **Per-task codex review.** Every task ends with a `/codex:review` step on the staged diff before commit. If codex flags must-fix issues, apply the fix and re-stage. Then commit. (Memory rule `feedback_codex_review_before_commit.md`.)
3. **Autonomous decisions during execution.** No clarifying questions during the task loop — pick the recommended option, surface non-obvious choices in the commit message, codex review is the safety net. (Memory rule `feedback_autonomous_decisions.md`.)

---

## File Structure (locked-in decomposition)

### Backend — F1.1 SecurityPolicy

```
core/src/main/resources/db/migration/V31__security_policy.sql      # new — table + seed row + grants
core/src/main/java/com/crosscert/passkey/core/entity/SecurityPolicy.java       # new — @Entity, single row id=1
core/src/main/java/com/crosscert/passkey/core/repository/SecurityPolicyRepository.java  # new
admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyDto.java       # new — View, UpdateRequest
admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyService.java   # new
admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyController.java # new — GET/PUT /admin/api/security-policy
admin-app/src/test/java/com/crosscert/passkey/admin/policy/SecurityPolicyIT.java        # new — IT
```

### Backend — F1.2 AdminMFA

```
core/src/main/resources/db/migration/V32__admin_user_mfa.sql       # new — ADD COLUMN + UPDATE seed
core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java          # modify — add mfaEnabled/mfaSecret
admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserDto.java  # modify — add mfaEnabled to View
admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserService.java # modify — populate mfaEnabled
```

### Frontend — F1.1 SecurityPolicy

```
admin-ui/src/api/securityPolicy.ts                                  # new — adapter
admin-ui/src/pages/settings/SecurityPolicyTab.tsx                   # modify — replace fixture with adapter
admin-ui/src/fixtures/securityPolicy.ts                             # delete
```

### Frontend — F1.2 AdminMFA

```
admin-ui/src/api/adminUsers.ts                                      # modify — add mfaEnabled to AdminUserView
admin-ui/src/pages/settings/AdminUsersTab.tsx                       # modify — use u.mfaEnabled instead of getMfa
admin-ui/src/fixtures/adminMfa.ts                                   # delete
```

---

## Working directory & branch

All work happens in worktree: `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/gap-fill-plan/` on branch `worktree-gap-fill-plan`.

Tests use existing IT scaffold (Testcontainers + Oracle, profile `test`).

---

## Task 1: V31 SecurityPolicy migration (table + seed + grants)

> Slot V30 was already taken (`V30__admin_user_invitation_runtime_grants.sql`); see note in plan header.

**Files:**
- Create: `core/src/main/resources/db/migration/V31__security_policy.sql`

- [ ] **Step 1: Write the migration**

```sql
-- ============================================================
-- V31 — security_policy (platform-wide singleton row, id = 1)
--
-- 목적: SecurityPolicyTab 저장 영속화 (Gap #20).
-- Single row, id=1 always. Idempotent — re-running V31 must succeed.
--
-- Patterns:
--   - CREATE wrapped in EXCEPTION (ORA-00955 = table exists)
--   - GRANT separated, one per statement (V29 pattern, line 44-45)
--   - Seed row INSERT skipped if id=1 already present
-- ============================================================

-- 1. Table
DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE security_policy (
    id                              NUMBER(1,0)              NOT NULL,
    session_idle_timeout_minutes    NUMBER(5,0)              NOT NULL,
    password_min_length             NUMBER(3,0)              NOT NULL,
    mfa_required                    CHAR(1)                  NOT NULL,
    cors_allowlist                  CLOB                     NOT NULL,
    updated_at                      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_by                      VARCHAR2(255),
    CONSTRAINT pk_security_policy PRIMARY KEY (id),
    CONSTRAINT ck_security_policy_singleton CHECK (id = 1),
    CONSTRAINT ck_security_policy_mfa CHECK (mfa_required IN (''Y'',''N''))
  )';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

-- 2. Seed singleton row (only if not present)
MERGE INTO security_policy t
USING (SELECT 1 AS id FROM dual) s
ON (t.id = s.id)
WHEN NOT MATCHED THEN INSERT (id, session_idle_timeout_minutes, password_min_length, mfa_required, cors_allowlist, updated_at, updated_by)
VALUES (1, 30, 12, 'Y', '[]', SYSTIMESTAMP, 'system');

COMMIT;

-- 3. Grants to APP_ADMIN (split per V29 pattern)
GRANT SELECT ON security_policy TO APP_ADMIN;
GRANT UPDATE ON security_policy TO APP_ADMIN;
```

- [ ] **Step 2: Apply migration**

There is no `:core:flywayMigrate` gradle task in this project — Flyway runs automatically on admin-app boot. Two ways to apply during development:

**Option A (preferred): restart admin-app**
```bash
# kill any running admin-app on :8081 then
./gradlew :admin-app:bootRun
```
Boot log should show: `Migrating schema "APP" to version "31 - security policy"`.

**Option B (faster, when admin-app is already running elsewhere): apply via sqlplus**
```bash
docker exec -i passkey-oracle sqlplus -s APP_OWNER/app_owner_pw@//localhost:1521/XEPDB1 < core/src/main/resources/db/migration/V31__security_policy.sql
```
Then verify Flyway records the version on next admin-app boot (idempotent guards ensure no duplicate execution error).

- [ ] **Step 3: Verify schema**

```bash
docker exec -i passkey-oracle sqlplus -s APP_OWNER/app_owner_pw@//localhost:1521/XEPDB1 <<'SQL'
SET PAGESIZE 50
SET LINESIZE 200
SELECT id, session_idle_timeout_minutes, password_min_length, mfa_required FROM security_policy;
SQL
```
Expected: one row — `(1, 30, 12, 'Y')`

- [ ] **Step 4: Per-task codex review + commit**

Stage and codex-review before commit (per Execution policy §2):
```bash
git add core/src/main/resources/db/migration/V31__security_policy.sql
```
Dispatch codex review via the `codex:codex-rescue` subagent on the staged diff. If must-fix issues are raised, fix and re-stage. Then:
```bash
git commit -m "feat(core): V31 security_policy singleton table (Gap #20 prep)"
```

---

## Task 2: SecurityPolicy entity + repository

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/SecurityPolicy.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/SecurityPolicyRepository.java`

- [ ] **Step 1: Write the entity**

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "SECURITY_POLICY")
public class SecurityPolicy {

    @Id
    @Column(name = "ID")
    private Long id = 1L;

    @Column(name = "SESSION_IDLE_TIMEOUT_MINUTES", nullable = false)
    private int sessionIdleTimeoutMinutes;

    @Column(name = "PASSWORD_MIN_LENGTH", nullable = false)
    private int passwordMinLength;

    @Column(name = "MFA_REQUIRED", columnDefinition = "CHAR(1)", nullable = false)
    private String mfaRequiredFlag;

    @Lob
    @Column(name = "CORS_ALLOWLIST", nullable = false)
    private String corsAllowlistJson;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    @Column(name = "UPDATED_BY", length = 255)
    private String updatedBy;

    protected SecurityPolicy() {}

    public Long getId() { return id; }

    public int getSessionIdleTimeoutMinutes() { return sessionIdleTimeoutMinutes; }
    public void setSessionIdleTimeoutMinutes(int v) { this.sessionIdleTimeoutMinutes = v; }

    public int getPasswordMinLength() { return passwordMinLength; }
    public void setPasswordMinLength(int v) { this.passwordMinLength = v; }

    public boolean isMfaRequired() { return "Y".equals(mfaRequiredFlag); }
    public void setMfaRequired(boolean v) { this.mfaRequiredFlag = v ? "Y" : "N"; }

    public String getCorsAllowlistJson() { return corsAllowlistJson; }
    public void setCorsAllowlistJson(String v) { this.corsAllowlistJson = v; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
}
```

- [ ] **Step 2: Write the repository**

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.SecurityPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityPolicyRepository extends JpaRepository<SecurityPolicy, Long> {
    // Single-row table: always use findById(1L).
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/SecurityPolicy.java \
        core/src/main/java/com/crosscert/passkey/core/repository/SecurityPolicyRepository.java
git commit -m "feat(core): SecurityPolicy entity + repository (Gap #20)"
```

---

## Task 3: SecurityPolicyDto

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyDto.java`

- [ ] **Step 1: Write the DTO**

```java
package com.crosscert.passkey.admin.policy;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class SecurityPolicyDto {
    private SecurityPolicyDto() {}

    public record View(
            int sessionIdleTimeoutMinutes,
            int passwordMinLength,
            boolean mfaRequired,
            List<String> corsAllowlist,
            Instant updatedAt,
            String updatedBy
    ) {}

    public record UpdateRequest(
            @Min(1) int sessionIdleTimeoutMinutes,
            @Min(1) int passwordMinLength,
            @NotNull Boolean mfaRequired,
            @NotNull @Size(max = 64) List<@Size(max = 256) String> corsAllowlist
    ) {}
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :admin-app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyDto.java
git commit -m "feat(admin-app): SecurityPolicyDto (View + UpdateRequest) (Gap #20)"
```

---

## Task 4: SecurityPolicyService

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyService.java`

- [ ] **Step 1: Write the service**

```java
package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.SecurityPolicy;
import com.crosscert.passkey.core.repository.SecurityPolicyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class SecurityPolicyService {

    private static final Long SINGLETON_ID = 1L;

    private final SecurityPolicyRepository repo;
    private final ObjectMapper objectMapper;

    public SecurityPolicyService(SecurityPolicyRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SecurityPolicyDto.View get() {
        SecurityPolicy p = repo.findById(SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("security_policy singleton row missing — V30 not applied?"));
        return toView(p);
    }

    @Transactional
    public SecurityPolicyDto.View update(SecurityPolicyDto.UpdateRequest req, String updatedBy) {
        SecurityPolicy p = repo.findById(SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("security_policy singleton row missing — V30 not applied?"));
        p.setSessionIdleTimeoutMinutes(req.sessionIdleTimeoutMinutes());
        p.setPasswordMinLength(req.passwordMinLength());
        p.setMfaRequired(Boolean.TRUE.equals(req.mfaRequired()));
        p.setCorsAllowlistJson(serialize(req.corsAllowlist()));
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(updatedBy);
        repo.save(p);
        return toView(p);
    }

    private SecurityPolicyDto.View toView(SecurityPolicy p) {
        return new SecurityPolicyDto.View(
                p.getSessionIdleTimeoutMinutes(),
                p.getPasswordMinLength(),
                p.isMfaRequired(),
                deserialize(p.getCorsAllowlistJson()),
                p.getUpdatedAt(),
                p.getUpdatedBy()
        );
    }

    private String serialize(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? List.of() : list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize cors_allowlist failed", e);
        }
    }

    private List<String> deserialize(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("deserialize cors_allowlist failed (corrupt JSON: " + json + ")", e);
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :admin-app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyService.java
git commit -m "feat(admin-app): SecurityPolicyService (get/update, JSON allowlist) (Gap #20)"
```

---

## Task 5: SecurityPolicyController + audit append

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyController.java`

- [ ] **Step 1: Write the controller**

```java
package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/api/security-policy")
public class SecurityPolicyController {

    private final SecurityPolicyService service;
    private final AuditLogService auditService;

    public SecurityPolicyController(SecurityPolicyService service, AuditLogService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    public SecurityPolicyDto.View get() {
        return service.get();
    }

    @PutMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public SecurityPolicyDto.View update(@Valid @RequestBody SecurityPolicyDto.UpdateRequest req,
                                         Authentication auth) {
        String actor = auth.getName();
        SecurityPolicyDto.View view = service.update(req, actor);
        auditService.append(new AuditAppendRequest(
                null,
                actor,
                "SECURITY_POLICY_UPDATED",
                "security_policy",
                "1",
                null,
                Map.of(
                        "sessionIdleTimeoutMinutes", view.sessionIdleTimeoutMinutes(),
                        "passwordMinLength", view.passwordMinLength(),
                        "mfaRequired", view.mfaRequired(),
                        "corsAllowlistSize", view.corsAllowlist().size())
        ));
        return view;
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :admin-app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyController.java
git commit -m "feat(admin-app): SecurityPolicyController GET/PUT + audit append (Gap #20)"
```

---

## Task 6: SecurityPolicy smoke IT (1 happy-path test only)

> Per execution policy §1, only ONE smoke test is written here — the GET-after-PUT round-trip. RBAC (403) and other branches are validated by manual smoke at Task 17 and by codex review of the controller code.

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/policy/SecurityPolicyIT.java`

- [ ] **Step 1: Inspect existing IT pattern**

Run:
```bash
head -90 admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java
```
Expected: shows self-contained `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`, Testcontainers (OracleContainer + Redis GenericContainer) declared via `@DynamicPropertySource`, `TestRestTemplate` for HTTP, and an inline `loginAs(email, password)` helper at line 279. **This codebase does not have an `AbstractAdminIT` base class — each IT is self-contained.** Copy the boot/Testcontainers/`loginAs` scaffold pattern verbatim.

- [ ] **Step 2: Write the IT — self-contained scaffold + 3 tests**

Use AdminFlowIT (lines 1-90 for class header + `@DynamicPropertySource` + Testcontainers fields, and lines 279-330 for the `loginAs` helper) as the source. The full new file:

```java
package com.crosscert.passkey.admin.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SecurityPolicyIT {

    // === Copy the OracleContainer + Redis GenericContainer + @DynamicPropertySource ===
    // === block verbatim from AdminFlowIT.java lines ~50-100. ============================
    // (Omitted here to avoid drift; engineer copies the exact block.)

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper om;

    private HttpHeaders loginAs(String email, String password) {
        // Copy verbatim from AdminFlowIT.java line 279 — returns HttpHeaders with the
        // session cookie + CSRF token already populated.
        // (Omitted here.)
        throw new UnsupportedOperationException("copy from AdminFlowIT.loginAs");
    }

    @Test
    void getThenPutThenGetRoundtrip() {
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");
        auth.setContentType(MediaType.APPLICATION_JSON);

        // 1. GET — seeded defaults
        ResponseEntity<JsonNode> initial = rest.exchange(
                "http://localhost:" + port + "/admin/api/security-policy",
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(initial.getStatusCode().value()).isEqualTo(200);
        assertThat(initial.getBody().get("sessionIdleTimeoutMinutes").asInt()).isEqualTo(30);

        // 2. PUT — new values
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionIdleTimeoutMinutes", 15);
        body.put("passwordMinLength", 16);
        body.put("mfaRequired", false);
        body.put("corsAllowlist", List.of("https://a.example.com"));
        ResponseEntity<JsonNode> putRes = rest.exchange(
                "http://localhost:" + port + "/admin/api/security-policy",
                HttpMethod.PUT, new HttpEntity<>(body, auth), JsonNode.class);
        assertThat(putRes.getStatusCode().value()).isEqualTo(200);

        // 3. GET — values are persisted
        ResponseEntity<JsonNode> readback = rest.exchange(
                "http://localhost:" + port + "/admin/api/security-policy",
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(readback.getBody().get("sessionIdleTimeoutMinutes").asInt()).isEqualTo(15);
        assertThat(readback.getBody().get("passwordMinLength").asInt()).isEqualTo(16);
        assertThat(readback.getBody().get("mfaRequired").asBoolean()).isFalse();
    }
}
```

> **The two `// === Copy verbatim ===` comments are intentional handoffs.** The engineer opens `AdminFlowIT.java` and copies the OracleContainer+Redis declaration block and the `loginAs` method body. Do not retype from memory — the dynamic property keys (`spring.datasource.url`, `spring.flyway.locations`, etc.) and CSRF cookie extraction details must match exactly.

- [ ] **Step 3: Run IT — verify all pass**

Run: `./gradlew :admin-app:test --tests SecurityPolicyIT`
Expected: 3 tests pass.

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/policy/SecurityPolicyIT.java
git commit -m "test(admin-app): SecurityPolicyIT (GET/PUT/RBAC) (Gap #20)"
```

---

## Task 7: Frontend SecurityPolicy adapter

**Files:**
- Create: `admin-ui/src/api/securityPolicy.ts`

- [ ] **Step 1: Write the adapter**

```typescript
import { api } from './client';

export type SecurityPolicyView = {
  sessionIdleTimeoutMinutes: number;
  passwordMinLength: number;
  mfaRequired: boolean;
  corsAllowlist: string[];
  updatedAt: string;
  updatedBy: string | null;
};

export type SecurityPolicyUpdateRequest = {
  sessionIdleTimeoutMinutes: number;
  passwordMinLength: number;
  mfaRequired: boolean;
  corsAllowlist: string[];
};

export const securityPolicyApi = {
  get: (): Promise<SecurityPolicyView> =>
    api.get<SecurityPolicyView>('/admin/api/security-policy'),

  update: (req: SecurityPolicyUpdateRequest): Promise<SecurityPolicyView> =>
    api.put<SecurityPolicyView>('/admin/api/security-policy', req),
};
```

- [ ] **Step 2: TypeScript compile check**

Run: `cd admin-ui && npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add admin-ui/src/api/securityPolicy.ts
git commit -m "feat(admin-ui): securityPolicyApi adapter (Gap #20)"
```

---

## Task 8: SecurityPolicyTab — replace fixture with adapter (DESIGN UNCHANGED)

**Files:**
- Modify: `admin-ui/src/pages/settings/SecurityPolicyTab.tsx`

- [ ] **Step 1: Open SecurityPolicyTab.tsx and verify current state**

Run: `head -10 admin-ui/src/pages/settings/SecurityPolicyTab.tsx`
Expected: imports include `import { securityPolicyFixture } from '@/fixtures/securityPolicy';`

- [ ] **Step 2: Replace the import block + state init + add useEffect + wire save/cancel**

Find the existing import (line ~3) and the SecurityPolicyTab function (line ~74). Replace as follows.

Original imports section:
```typescript
import { useState } from 'react';
import { Icons } from '@/icons/Icons';
import { securityPolicyFixture } from '@/fixtures/securityPolicy';
import { useToast } from '@/shell/ToastHost';
```

Replace with:
```typescript
import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { securityPolicyApi, type SecurityPolicyView } from '@/api/securityPolicy';
import { useToast } from '@/shell/ToastHost';
```

Original function body start (around line 74):
```typescript
export default function SecurityPolicyTab() {
  const [sessionMin, setSessionMin] = useState(securityPolicyFixture.sessionIdleTimeoutMinutes);
  const [pwMin, setPwMin] = useState(securityPolicyFixture.passwordMinLength);
  const [reqMfa, setReqMfa] = useState(securityPolicyFixture.mfaRequired);
  const [corsAllowlist] = useState(securityPolicyFixture.corsAllowlist);
  const toast = useToast();
```

Replace with:
```typescript
export default function SecurityPolicyTab() {
  const [sessionMin, setSessionMin] = useState(30);
  const [pwMin, setPwMin] = useState(12);
  const [reqMfa, setReqMfa] = useState(true);
  const [corsAllowlist, setCorsAllowlist] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  function applyView(v: SecurityPolicyView) {
    setSessionMin(v.sessionIdleTimeoutMinutes);
    setPwMin(v.passwordMinLength);
    setReqMfa(v.mfaRequired);
    setCorsAllowlist(v.corsAllowlist);
  }

  useEffect(() => {
    setLoading(true);
    securityPolicyApi.get()
      .then(applyView)
      .catch((e: unknown) => {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: '보안 정책 로드 실패', message: msg });
      })
      .finally(() => setLoading(false));
  }, []);

  async function handleSave() {
    setSaving(true);
    try {
      const v = await securityPolicyApi.update({
        sessionIdleTimeoutMinutes: sessionMin,
        passwordMinLength: pwMin,
        mfaRequired: reqMfa,
        corsAllowlist,
      });
      applyView(v);
      toast({ kind: 'ok', title: '보안 정책 저장됨', traceId: 'tr_sec_001' });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '보안 정책 저장 실패', message: msg });
    } finally {
      setSaving(false);
    }
  }

  async function handleCancel() {
    try {
      const v = await securityPolicyApi.get();
      applyView(v);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '되돌리기 실패', message: msg });
    }
  }
```

- [ ] **Step 3: Wire the existing buttons (DO NOT modify their className, label, or position)**

Find the two buttons near the end (around line 109-117):
```typescript
<button className="btn">취소</button>
<button
  className="btn btn--primary"
  onClick={() => toast({ kind: 'ok', title: '보안 정책 저장됨', traceId: 'tr_sec_001' })}
>
  저장
</button>
```

Replace with:
```typescript
<button className="btn" onClick={handleCancel} disabled={saving || loading}>취소</button>
<button
  className="btn btn--primary"
  onClick={handleSave}
  disabled={saving || loading}
>
  저장
</button>
```

> **Design invariant:** the className, label text (`취소`, `저장`), button order, and surrounding `<div>` structure stay exactly as-is. Only `onClick` (and `disabled` — boolean attribute, no visual impact when both states render naturally) is added.

- [ ] **Step 4: TypeScript compile check**

Run: `cd admin-ui && npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 5: Smoke test in browser**

Run (background): `./gradlew :admin-app:bootRun` and `cd admin-ui && npm run dev`
Navigate to `http://localhost:5173/settings`, click "보안 정책" tab.
Verify:
- Initial values match DB seed (30 / 12 / MFA on / empty allowlist)
- Change a value, click 저장 → toast 표시 → reload page → value persists
- 취소 button reverts to DB value

- [ ] **Step 6: Commit**

```bash
git add admin-ui/src/pages/settings/SecurityPolicyTab.tsx
git commit -m "feat(admin-ui): SecurityPolicyTab uses real adapter (Gap #20)"
```

---

## Task 9: Delete fixtures/securityPolicy.ts

**Files:**
- Delete: `admin-ui/src/fixtures/securityPolicy.ts`

- [ ] **Step 1: Verify no other references**

Run: `grep -rn "fixtures/securityPolicy\|securityPolicyFixture" admin-ui/src/`
Expected: zero matches (only the file itself, which we're deleting).

- [ ] **Step 2: Delete**

Run: `git rm admin-ui/src/fixtures/securityPolicy.ts`

- [ ] **Step 3: TypeScript compile check**

Run: `cd admin-ui && npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(admin-ui): delete fixtures/securityPolicy.ts (Gap #20)"
```

---

## Task 10: V32 admin_user MFA columns + seed update

**Files:**
- Create: `core/src/main/resources/db/migration/V32__admin_user_mfa.sql`

- [ ] **Step 1: Write the migration**

```sql
-- ============================================================
-- V32 — admin_user MFA columns
--
-- 목적: AdminUsersTab MFA 컬럼 실데이터화 (Gap #15).
-- Adds mfa_enabled (CHAR Y/N, default 'N') + mfa_secret (nullable VARCHAR2(64)).
-- Updates V11 seed rows: alice → 'Y', bob → 'N'.
-- Idempotent: ALTER wrapped in EXCEPTION (ORA-01430 = column exists).
-- ============================================================

DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE admin_user ADD (mfa_enabled CHAR(1) DEFAULT ''N'' NOT NULL)';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/

DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE admin_user ADD (mfa_secret VARCHAR2(64))';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/

DECLARE
  e_check_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_check_exists, -2264);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE admin_user ADD CONSTRAINT ck_admin_user_mfa_enabled CHECK (mfa_enabled IN (''Y'',''N''))';
EXCEPTION
  WHEN e_check_exists THEN NULL;
END;
/

-- V11 seed UPDATE (idempotent — overwrites unconditionally)
UPDATE admin_user SET mfa_enabled = 'Y' WHERE email = 'alice@crosscert.com';
UPDATE admin_user SET mfa_enabled = 'N' WHERE email = 'bob@crosscert.com';

COMMIT;
```

- [ ] **Step 2: Apply migration**

Same two options as Task 1 Step 2 (admin-app boot or direct sqlplus). On admin-app boot, log shows `Migrating schema "APP" to version "32 - admin user mfa"`.

- [ ] **Step 3: Verify**

```bash
docker exec -i passkey-oracle sqlplus -s APP_OWNER/app_owner_pw@//localhost:1521/XEPDB1 <<'SQL'
SELECT email, mfa_enabled FROM admin_user ORDER BY email;
SQL
```
Expected: `alice@crosscert.com → Y`, `bob@crosscert.com → N`.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/resources/db/migration/V32__admin_user_mfa.sql
git commit -m "feat(core): V32 admin_user.mfa_enabled + mfa_secret columns (Gap #15)"
```

---

## Task 11: AdminUser entity — mfaEnabled field

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java`

- [ ] **Step 1: Add fields and accessors**

Open the file. After the existing `suspendedBy` column declaration (around line 41), add:

```java
    @Column(name = "MFA_ENABLED", columnDefinition = "CHAR(1)", nullable = false)
    private String mfaEnabledFlag = "N";

    @Column(name = "MFA_SECRET", length = 64)
    private String mfaSecret;
```

At the bottom of the class (after `setSuspendedBy`), add:

```java
    public boolean isMfaEnabled() { return "Y".equals(mfaEnabledFlag); }
    public void setMfaEnabled(boolean v) { this.mfaEnabledFlag = v ? "Y" : "N"; }

    public String getMfaSecret() { return mfaSecret; }
    public void setMfaSecret(String v) { this.mfaSecret = v; }
```

- [ ] **Step 2: Compile**

Run: `./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java
git commit -m "feat(core): AdminUser.mfaEnabled + mfaSecret accessors (Gap #15)"
```

---

## Task 12: AdminUserDto.View — add mfaEnabled

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserDto.java`

- [ ] **Step 1: Add mfaEnabled to View record**

Open the file. The `View` record (lines 13-17) currently reads:

```java
    public record View(
            UUID id, String email, String role, String status,
            UUID tenantId, Instant createdAt, Instant lastLoginAt,
            Instant suspendedAt, String createdBy
    ) {}
```

Replace with:

```java
    public record View(
            UUID id, String email, String role, String status,
            UUID tenantId, Instant createdAt, Instant lastLoginAt,
            Instant suspendedAt, String createdBy, boolean mfaEnabled
    ) {}
```

- [ ] **Step 2: Compile (will fail at View construction sites)**

Run: `./gradlew :admin-app:compileJava`
Expected: failure pointing at `new AdminUserDto.View(...)` calls in `AdminUserService.java` — that's expected and fixed in Task 13.

- [ ] **Step 3: Commit (deferred — combined with Task 13)**

Do not commit yet. Move on to Task 13.

---

## Task 13: AdminUserService — populate mfaEnabled

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserService.java`

- [ ] **Step 1: Inspect all `new AdminUserDto.View(...)` callsites**

Run: `grep -n "new AdminUserDto.View" admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserService.java`
Expected: 1–3 callsites where `View` is constructed.

- [ ] **Step 2: Add `u.isMfaEnabled()` as the last argument at each callsite**

For each `new AdminUserDto.View(...)` found in `AdminUserService.java`, append `, u.isMfaEnabled()` (or the corresponding entity reference — replace `u` with whatever local variable holds the `AdminUser` at that callsite).

Example (pattern only — exact code depends on AdminUserService's current contents):

Before:
```java
return new AdminUserDto.View(
        u.getId(), u.getEmail(), u.getRole(), u.getStatus(),
        u.getTenantId(), u.getCreatedAt(), u.getLastLoginAt(),
        u.getSuspendedAt(), u.getCreatedBy());
```

After:
```java
return new AdminUserDto.View(
        u.getId(), u.getEmail(), u.getRole(), u.getStatus(),
        u.getTenantId(), u.getCreatedAt(), u.getLastLoginAt(),
        u.getSuspendedAt(), u.getCreatedBy(), u.isMfaEnabled());
```

- [ ] **Step 3: Compile**

Run: `./gradlew :admin-app:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run existing AdminUser tests**

Run: `./gradlew :admin-app:test --tests AdminUser*`
Expected: existing tests pass (no behavioural change, only an extra field).

- [ ] **Step 5: Commit (combined with Task 12)**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserDto.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserService.java
git commit -m "feat(admin-app): AdminUserDto.View includes mfaEnabled (Gap #15)"
```

---

## Task 14: AdminUserView typescript type — add mfaEnabled

**Files:**
- Modify: `admin-ui/src/api/adminUsers.ts`

- [ ] **Step 1: Add mfaEnabled to AdminUserView type**

Open the file. Find the `AdminUserView` type (around line 70):

```typescript
export type AdminUserView = {
  id: string;
  email: string;
  role: 'PLATFORM_OPERATOR' | 'RP_ADMIN';
  status: 'ACTIVE' | 'PENDING' | 'SUSPENDED';
  tenantId: string | null;
  createdAt: string;
  lastLoginAt: string | null;
  suspendedAt: string | null;
  createdBy: string | null;
};
```

Append one field:

```typescript
export type AdminUserView = {
  id: string;
  email: string;
  role: 'PLATFORM_OPERATOR' | 'RP_ADMIN';
  status: 'ACTIVE' | 'PENDING' | 'SUSPENDED';
  tenantId: string | null;
  createdAt: string;
  lastLoginAt: string | null;
  suspendedAt: string | null;
  createdBy: string | null;
  mfaEnabled: boolean;
};
```

- [ ] **Step 2: TypeScript compile check (will fail at getMfa callsite — fixed in Task 15)**

Run: `cd admin-ui && npx tsc --noEmit`
Expected: no error here yet (`getMfa` is fine since it's still imported in Task 15 prep).

- [ ] **Step 3: Commit**

```bash
git add admin-ui/src/api/adminUsers.ts
git commit -m "feat(admin-ui): AdminUserView includes mfaEnabled (Gap #15)"
```

---

## Task 15: AdminUsersTab — swap getMfa for u.mfaEnabled (DESIGN UNCHANGED)

**Files:**
- Modify: `admin-ui/src/pages/settings/AdminUsersTab.tsx`

- [ ] **Step 1: Remove the getMfa import**

Find (around line 6):
```typescript
import { getMfa } from '@/fixtures/adminMfa';
```

Delete that line.

- [ ] **Step 2: Replace the getMfa(u.id) callsite**

Find (around line 206):
```typescript
{getMfa(u.id) ? (
  <span className="badge badge--success" style={{ fontSize: 10 }}>
    <Icons.Check size={10} /> ON
  </span>
) : (
  <span className="badge badge--warning" style={{ fontSize: 10 }}>
    <Icons.Alert size={10} /> OFF
  </span>
)}
```

Replace with:
```typescript
{u.mfaEnabled ? (
  <span className="badge badge--success" style={{ fontSize: 10 }}>
    <Icons.Check size={10} /> ON
  </span>
) : (
  <span className="badge badge--warning" style={{ fontSize: 10 }}>
    <Icons.Alert size={10} /> OFF
  </span>
)}
```

> **Design invariant:** the JSX tree (`<span className="badge badge--success">`, `<Icons.Check size={10}>`, label `ON`/`OFF`, ternary structure) is identical. Only the condition expression changes from a function call on fixture data to a field on the server response.

- [ ] **Step 3: TypeScript compile check**

Run: `cd admin-ui && npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 4: Smoke test**

With admin-app + admin-ui running, navigate to `/settings → 운영자` tab.
Verify: alice 행의 MFA 컬럼 = ON 뱃지, bob 행 = OFF 뱃지.

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/pages/settings/AdminUsersTab.tsx
git commit -m "feat(admin-ui): AdminUsersTab MFA column uses u.mfaEnabled (Gap #15)"
```

---

## Task 16: Delete fixtures/adminMfa.ts

**Files:**
- Delete: `admin-ui/src/fixtures/adminMfa.ts`

- [ ] **Step 1: Verify no other references**

Run: `grep -rn "fixtures/adminMfa\|adminMfaFixture\|\\bgetMfa\\b" admin-ui/src/`
Expected: zero matches (only the file itself).

- [ ] **Step 2: Delete**

Run: `git rm admin-ui/src/fixtures/adminMfa.ts`

- [ ] **Step 3: TypeScript compile check**

Run: `cd admin-ui && npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(admin-ui): delete fixtures/adminMfa.ts (Gap #15)"
```

---

## Task 17: Phase F1 acceptance verification + codex review + merge to main

**Files:**
- (none — verification step)

- [ ] **Step 1: Run full backend test suite**

Run: `./gradlew :admin-app:test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Run admin-ui type + lint**

Run: `cd admin-ui && npx tsc --noEmit && npm run lint`
Expected: no errors.

> **Per-task codex review reminder (applies retroactively).** Each of Tasks 1-16 should have had a `/codex:review` step run on the staged diff before that task's commit. If any task was committed without a codex pass, run codex review now against the cumulative diff (`git diff main..HEAD`) and apply any must-fix corrections as a follow-up commit before proceeding.

- [ ] **Step 3: Manual smoke verify F1 Acceptance criteria from spec**

For Gap #20 (SecurityPolicy):
- [ ] Navigate to /settings → 보안 정책 tab. Change session idle to 15, save. Refresh. Value is still 15.
- [ ] Log in as bob (RP_ADMIN/VIEWER role from V11 seed). Try PUT — verify 403 in network tab.
- [ ] Audit page shows `SECURITY_POLICY_UPDATED` event with current actor email.

For Gap #15 (AdminMFA):
- [ ] /settings → 운영자 tab. alice 행 = MFA ON, bob 행 = MFA OFF.
- [ ] In DB, `UPDATE admin_user SET mfa_enabled = 'Y' WHERE email = 'bob@crosscert.com'; COMMIT;`. Refresh page. bob 행이 ON 으로 바뀜.
- [ ] Revert: `UPDATE admin_user SET mfa_enabled = 'N' WHERE email = 'bob@crosscert.com'; COMMIT;`.

- [ ] **Step 4: Codex review on the F1 commits**

Run codex review against `main..HEAD` diff:

```bash
git diff main..HEAD --stat
```

Then trigger codex review via the rescue subagent (per memory rule `feedback_codex_review_before_commit.md`). Pass criteria: no `must-fix` flagged by codex.

- [ ] **Step 5: Merge worktree back to main with `--no-ff`**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git merge --no-ff worktree-gap-fill-plan -m "Merge Phase F1 — Critical gap fill (SecurityPolicy + AdminMFA)"
```

> Per memory rule `feedback_per_phase_worktree.md`, each phase merges back via `--no-ff` to preserve the phase as a topology branch.

- [ ] **Step 6: Verify post-merge**

Run: `git log --oneline -5`
Expected: top commit is the merge commit; prior commits are the 16 task commits.

---

## Phase F1 Summary

**What shipped:**
- New backend module: `policy/SecurityPolicy*` (Controller + Service + DTO + Entity + Repository + IT)
- DB: `security_policy` table seeded singleton, `admin_user.mfa_enabled` + `mfa_secret` columns
- Frontend: `securityPolicy` adapter; `SecurityPolicyTab` + `AdminUsersTab` switched to real data
- Deleted: `fixtures/securityPolicy.ts`, `fixtures/adminMfa.ts`
- New audit event type: `SECURITY_POLICY_UPDATED`

**Design impact:** zero. Only `onClick` handlers, `disabled` attribute, conditional expressions, and adapter calls changed.

**Next phase:** F2 — Tenant KPI (`docs/superpowers/specs/2026-05-28-admin-ui-server-gap-fill-design.md` § Phase F2). Plan to be written when F1 is merged and verified.
