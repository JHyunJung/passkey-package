# Activity 페이지 + audit_log.tenant_id Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PLATFORM_OPERATOR 의 Activity 페이지 (cross-tenant KPI + Top 5 + 실시간 feed) + audit_log.tenant_id 컬럼 + RP_ADMIN 의 자기 tenant audit 접근 (TenantDetail 5번째 탭) 회복.

**Architecture:**
- DB: V24 멱등 migration (audit_log.tenant_id RAW(16) NULL + 인덱스, hash chain 입력 미변경)
- 백엔드: AuditLog 9-arg constructor + AuditAppendRequest 1 인자 추가 + 11 곳 호출 site 갱신 (10 append + AuditChainVerifier.recomputeHash) + 신규 ActivityController/Service/Repository + AuditLogController 의 tenant scope check
- 프런트: Activity 페이지 (5초 polling, PlatformOnlyGuard) + TenantActivityTab (5번째 탭) + AuditLog tenantId input + Sidebar PLATFORM_NAV
- 테스트 (축소판): 자동 IT 2 (ActivityControllerIT, AuditLogTenantScopingIT) + RpAdminBoundaryIT step 14 갱신

**Tech Stack:** Spring Boot 3.5 + Java 17 + Oracle 21 + Flyway + JPA/Hibernate + React 18 + Vite + TypeScript

**Worktree:** `.claude/worktrees/activity-page` (branch `feature/activity-page` from main `8d6ed45`)

**Spec:** [docs/superpowers/specs/2026-05-27-activity-page-design.md](../specs/2026-05-27-activity-page-design.md)

**Test-minimization:**
- 자동 IT 2 개만 — `ActivityControllerIT`, `AuditLogTenantScopingIT`
- `RpAdminBoundaryIT.step14_auditDenied` 의 의미 변경 (RP_ADMIN 도 GET 가능 → cross-tenant 만 차단) 반영
- 그 외 unit / repository slice / component test 자제. admin-ui 는 manual smoke
- 각 task 끝 `./gradlew :admin-app:compileJava :admin-app:compileTestJava` 로 fast feedback
- 각 task commit 후 codex review (`Agent` tool, `codex:codex-rescue` subagent_type, `--fresh` flag)

---

## Task 별 commit prefix 규칙

| Task | prefix | 이유 |
|---|---|---|
| 1 | `feat(db)` | V24 schema migration |
| 2 | `feat(core)` | AuditLog entity 9-arg + tenantId field |
| 3 | `feat(admin)` | AuditAppendRequest record + 11 곳 호출 갱신 |
| 4 | `feat(admin)` | ActivityRepository + ActivityView |
| 5 | `feat(admin)` | ActivityService + ActivityController |
| 6 | `feat(admin)` | AuditLogController/Service tenant scope + AuditLogView.tenantId |
| 7 | `test(admin)` | ActivityControllerIT 신규 |
| 8 | `test(admin)` | AuditLogTenantScopingIT 신규 + RpAdminBoundaryIT step 14 갱신 |
| 9 | `feat(admin-ui)` | types.ts + client.ts (Activity + AuditLog tenantId) |
| 10 | `feat(admin-ui)` | Activity 페이지 + Sidebar PLATFORM_NAV + App.tsx 라우트 |
| 11 | `feat(admin-ui)` | TenantActivityTab + TenantDetail 5번째 탭 + AuditLog tenantId column |
| 12 | `docs(followups)` | manual smoke checklist + 8 deferred items |

---

## Task 1: V24 멱등 migration — audit_log.tenant_id 컬럼 + 인덱스

**Files:**
- Create: `core/src/main/resources/db/migration/V24__audit_log_tenant_id.sql`

- [ ] **Step 1: Migration 파일 작성**

`core/src/main/resources/db/migration/V24__audit_log_tenant_id.sql`:
```sql
-- ============================================================
-- V24 — audit_log.tenant_id 컬럼 추가
--
-- 목적: PLATFORM_OPERATOR Activity 페이지 + RP_ADMIN audit 격리.
--
-- 결정: hash chain (V10 SHA-256) 의 입력 포맷 변경 없음 — tenant_id 는
-- 순수 metadata. payload 안의 'tenantId' 키가 이미 hash 에 포함되어 tamper
-- evidence 보존. 기존 row 의 tenant_id 는 NULL — backfill 안 함.
--
-- Idempotency: ALTER ADD / CREATE INDEX 는 재실행 시 ORA-01430 / ORA-00955
-- 로 실패. EXCEPTION 으로 감싸 멱등 (Flyway repair 외에도 안전).
-- ============================================================

-- 1. tenant_id 컬럼 추가 (RAW(16) NULL) — ORA-01430 (column already exists) swallow
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE audit_log ADD (tenant_id RAW(16))';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -1430 THEN NULL; -- column already exists
    ELSE RAISE;
    END IF;
END;
/

-- 2. 인덱스: tenant_id + created_at — ORA-00955 (name already used) swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX audit_log_tenant_ix ON audit_log (tenant_id, created_at)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; -- index already exists
    ELSE RAISE;
    END IF;
END;
/

-- 3. FK 안 둠 (의도) — tenant 가 삭제돼도 audit 은 forensic 으로 남아야 함.
--    tenant 삭제는 admin-role-separation phase 의 fk_admin_user_tenant 가
--    이미 막고 있어 dangling 위험 없음. 단순화 위해 FK 생략.
--
-- 4. 권한 변경 없음 — APP_USER 가 이미 audit_log 에 SELECT/INSERT 갖고 있음
--    (V01 grants). 컬럼 추가는 기존 grant 를 그대로 상속.
```

- [ ] **Step 2: 컴파일 (smoke — Flyway가 SQL 파싱)**

Run: `./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL (SQL 자체는 컴파일 안 되지만 build path에 있는 resource이라 빠른 검증).

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/db/migration/V24__audit_log_tenant_id.sql
git commit -m "$(cat <<'EOF'
feat(db): V24 audit_log.tenant_id 컬럼 + 멱등 migration

ALTER ADD + CREATE INDEX 를 PL/SQL EXCEPTION 으로 감싸 ORA-01430 / ORA-00955
swallow — Flyway repair / 수동 재실행 안전.

tenant_id 는 hash 입력 미포함 — V10 chain 보존. backfill 안 함 (24h
KPI 윈도우라 historical NULL 무관).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review the commit just made in worktree .claude/worktrees/activity-page (HEAD). This is Task 1 of plan 2026-05-27-activity-page.md: V24 migration adding audit_log.tenant_id RAW(16) column + index, wrapped in PL/SQL EXCEPTION for idempotency. Verify the SQLCODE swallow logic (-1430 column exists, -955 index exists), the / terminators after END;, that no grant changes are needed (APP_USER already has SELECT/INSERT on audit_log per V01), and that the index column order (tenant_id, created_at) is correct for Top 5 GROUP BY queries. Concise — 150 words max.")
```

---

## Task 2: AuditLog 엔티티 — tenantId 필드 + 9-arg constructor

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java`

- [ ] **Step 1: AuditLog 엔티티 갱신**

`core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java`:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "AUDIT_LOG")
public class AuditLog extends BaseEntity {

    @Column(name = "PREV_HASH", length = 32)
    private byte[] prevHash;

    @Column(name = "HASH", length = 32, nullable = false)
    private byte[] hash;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ACTOR_ID", columnDefinition = "RAW(16)")
    private UUID actorId;

    @Column(name = "ACTOR_EMAIL", length = 255, nullable = false)
    private String actorEmail;

    @Column(name = "ACTION", length = 64, nullable = false)
    private String action;

    @Column(name = "TARGET_TYPE", length = 32)
    private String targetType;

    @Column(name = "TARGET_ID", length = 64)
    private String targetId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)")
    private UUID tenantId;

    @Lob
    @Column(name = "PAYLOAD", nullable = false)
    private String payload;

    protected AuditLog() {}

    public AuditLog(byte[] prevHash, byte[] hash, UUID actorId, String actorEmail,
                    String action, String targetType, String targetId,
                    UUID tenantId,
                    String payload, Instant createdAtArg) {
        this.prevHash = prevHash;
        this.hash = hash;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.tenantId = tenantId;
        this.payload = payload;
        // Pre-set BaseEntity.createdAt + updatedAt so @PrePersist's null-check
        // preserves caller-supplied value for hash-chain integrity. AuditLog
        // is append-only — updatedAt always equals createdAt at insert and
        // never advances (no @PreUpdate path).
        seedTimestamps(createdAtArg);
    }

    /**
     * Reflectively seed BaseEntity's private createdAt and updatedAt fields.
     * Required because:
     *   1. AuditLog's hash chain depends on a caller-chosen createdAt that
     *      must survive @PrePersist (which uses Instant.now() by default).
     *   2. BaseEntity intentionally exposes no setters for createdAt/updatedAt
     *      to keep the lifecycle contract uniform across the other 9 entities.
     * @PrePersist's null-check ({@code if (createdAt == null) createdAt = now})
     * then leaves the seeded value alone.
     */
    private void seedTimestamps(Instant t) {
        try {
            java.lang.reflect.Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(this, t);
            java.lang.reflect.Field updatedAtField = BaseEntity.class.getDeclaredField("updatedAt");
            updatedAtField.setAccessible(true);
            updatedAtField.set(this, t);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to seed AuditLog timestamps", e);
        }
    }

    public byte[] getPrevHash() { return prevHash; }
    public byte[] getHash() { return hash; }
    public UUID getActorId() { return actorId; }
    public String getActorEmail() { return actorEmail; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public UUID getTenantId() { return tenantId; }
    public String getPayload() { return payload; }
}
```

- [ ] **Step 2: 컴파일 — 8-arg 사용처가 깨질 것 (의도된 실패)**

Run: `./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL — `:core` 안에는 AuditLog 직접 생성자 호출이 없으므로 통과.

Run: `./gradlew :admin-app:compileJava -q 2>&1 | head -30`
Expected: `AuditLogService.java` 의 `new AuditLog(...)` 호출이 8 → 9 인자 mismatch 로 FAIL. 정확한 메시지: `constructor AuditLog in class AuditLog cannot be applied to given types`.

이건 Task 3에서 함께 고친다 (entity + service 가 동시 PR 해야 안전).

- [ ] **Step 3: Commit (compileJava 깨진 상태 의도적 — Task 3에서 해결)**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java
git commit -m "$(cat <<'EOF'
feat(core): AuditLog 9-arg constructor + tenantId 필드 (RAW(16) UUID)

tenantId 는 nullable — ADMIN_LOGIN / SIGNING_KEY_ROTATE / MDS_BLOB_SYNC
같은 platform-wide 액션은 null. payload 의 'tenantId' 키가 이미 hash 에
포함돼 tamper evidence 보존 — entity 의 tenantId 는 metadata-only.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree .claude/worktrees/activity-page. Task 2 of plan 2026-05-27-activity-page.md: added tenantId UUID field + getter to AuditLog entity, and replaced 8-arg constructor with 9-arg (tenantId param inserted between targetId and payload). Verify @JdbcTypeCode(SqlTypes.UUID) + columnDefinition=RAW(16) match existing actorId. Confirm no other code change leaked into this commit (callers fixed in Task 3). 100 words max.")
```

---

## Task 3: AuditAppendRequest + 11 곳 호출 갱신 + AuditLogService.append()

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditAppendRequest.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogService.java:127`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainVerifier.java:81`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java:87` and `:126`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminService.java:101`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java:84` and `:103`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java:114` and `:134`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java:107`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJob.java:71`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java:111`
- Modify (tests): `core/src/test/java/com/crosscert/passkey/core/entity/AuditLogTest.java` (3 곳)
- Modify (tests): `core/src/test/java/com/crosscert/passkey/core/entity/BaseEntityCallbackIT.java:206`
- Modify (tests): `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogServiceTest.java` (4 append + 1 new AuditLog)
- Modify (tests): `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainVerifierTest.java` (2 new AuditLog + 1 new AuditAppendRequest)

- [ ] **Step 1: AuditAppendRequest record 갱신**

`admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditAppendRequest.java`:

```java
package com.crosscert.passkey.admin.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Caller-supplied data for AuditLogService.append. Timestamps and
 * hashes are computed by the service itself.
 *
 * <p>payload is a Map&lt;String,Object&gt; — service writes it as canonical
 * JSON (keys sorted alphabetically) so the hash is reproducible.
 *
 * <p>actorId is nullable — null represents a system/unknown actor (e.g.,
 * unauthenticated login failures). The hash input uses empty string for
 * null actorId, matching the null-collapse convention for other optional fields.
 *
 * <p>tenantId is nullable — null represents a platform-wide action
 * (ADMIN_LOGIN / signing-key rotation / MDS sync). Stored in
 * audit_log.tenant_id for filtering; NOT included in the SHA-256 hash
 * input (V10 chain compatibility — payload's 'tenantId' key already
 * provides tamper evidence).
 */
public record AuditAppendRequest(
        UUID actorId,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        UUID tenantId,
        Map<String, Object> payload
) {}
```

- [ ] **Step 2: AuditLogService.append() — req.tenantId() 를 entity 에 전달**

`admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogService.java` 의 `append()` 안의 `new AuditLog(...)` (127-131 line) 9-arg 로:

```java
        AuditLog row = new AuditLog(
                prevHash, hash, req.actorId(), req.actorEmail(),
                req.action(),
                req.targetType(), req.targetId(),
                req.tenantId(),
                payloadJson, now);
        return repo.save(row);
```

`computeHash()` 변경 없음 — tenant_id 는 입력에 들어가지 않는다.

- [ ] **Step 3: AuditChainVerifier.recomputeHash — row.getTenantId() 를 req 에 전달**

`admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainVerifier.java` 의 `recomputeHash` (74-90 line):

```java
    private byte[] recomputeHash(AuditLog row) {
        try {
            // Parse stored payload back to Map so we can build the request.
            // We do NOT re-serialize — we pass row.getPayload() directly to
            // computeHash, which is the exact string that was hashed on append.
            Map<String, Object> payload = canonical.readValue(
                    row.getPayload(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            AuditAppendRequest req = new AuditAppendRequest(
                    row.getActorId(), row.getActorEmail(), row.getAction(),
                    row.getTargetType(), row.getTargetId(),
                    row.getTenantId(),
                    payload);
            return AuditLogService.computeHash(
                    row.getPrevHash(), req, row.getPayload(), row.getCreatedAt());
        } catch (Exception e) {
            // Malformed JSON in the payload column is itself tamper evidence.
            return new byte[0];
        }
    }
```

`tenantId` 는 `computeHash` 의 input 에 안 들어감 → V24 전후 hash 결과 동일 → AdminFlowIT 의 `verify()` 통과.

- [ ] **Step 4: 10 곳의 append() 호출 갱신**

각 파일의 `new AuditAppendRequest(...)` 호출에 7번째 인자 (payload 직전) 추가:

**TenantAdminService.java:87** (TENANT_CREATE):
```java
audit.append(new AuditAppendRequest(
        actorId, actorEmail, "TENANT_CREATE",
        "TENANT", tenant.getId().toString(),
        tenant.getId(),
        Map.of("slug", tenant.getSlug(), "displayName", tenant.getDisplayName())));
```
(`tenant.getId()` 가 새 7번째 인자. 기존 파일 readout 후 정확한 line 위치에서 한 줄 추가.)

**TenantAdminService.java:126** (TENANT_UPDATE):
```java
audit.append(new AuditAppendRequest(
        actorId, actorEmail, "TENANT_UPDATE",
        "TENANT", t.getId().toString(),
        t.getId(),
        diff));
```

**CredentialAdminService.java:101** (CREDENTIAL_REVOKE):
```java
audit.append(new AuditAppendRequest(
        actorId, actorEmail, "CREDENTIAL_REVOKE",
        "CREDENTIAL", credentialId.toString(),
        tenantId,
        Map.of("reason", reason)));
```
(`tenantId` 는 이미 method 인자로 받음 — `assertCanAccessTenant(tenantId)` 가 첫 줄에 있음.)

**ApiKeyAdminService.java:84** (API_KEY_ISSUE):
```java
audit.append(new AuditAppendRequest(
        actorId, actorEmail, "API_KEY_ISSUE",
        "API_KEY", saved.getId().toString(),
        req.tenantId(),
        Map.of("tenantId", req.tenantId().toString(), "scopes", new TreeSet<>(req.scopes()))));
```

**ApiKeyAdminService.java:103** (API_KEY_REVOKE):
```java
audit.append(new AuditAppendRequest(
        actorId, actorEmail, "API_KEY_REVOKE",
        "API_KEY", k.getId().toString(),
        k.getTenantId(),
        Map.of("tenantId", k.getTenantId().toString(), "reason", reason)));
```

**AdminSecurityConfig.java:114** (ADMIN_LOGIN):
```java
audit.append(new AuditAppendRequest(
        principal.getId(), principal.getEmail(), "ADMIN_LOGIN",
        "ADMIN_USER", principal.getId().toString(),
        null,
        Map.of()));
```

**AdminSecurityConfig.java:134** (ADMIN_LOGIN_FAILED):
```java
audit.append(new AuditAppendRequest(
        null, requestedEmail, "ADMIN_LOGIN_FAILED",
        "ADMIN_USER", null,
        null,
        Map.of("email", requestedEmail)));
```
(actorId null + tenantId null — unauthenticated failure)

**KeyRotationService.java:107** (SIGNING_KEY_ROTATE):
```java
audit.append(new AuditAppendRequest(
        null, "system", "SIGNING_KEY_ROTATE",
        "SIGNING_KEY", newKey.getId().toString(),
        null,
        Map.of("kid", newKey.getKid())));
```

**KeyExpirationJob.java:71** (SIGNING_KEY_REVOKE):
```java
audit.append(new AuditAppendRequest(
        null, "system", "SIGNING_KEY_REVOKE",
        "SIGNING_KEY", revoked.getId().toString(),
        null,
        Map.of("kid", revoked.getKid())));
```

**MdsSchedulerService.java:111** (MDS_BLOB_SYNC):
```java
audit.append(new AuditAppendRequest(
        null, "system", "MDS_BLOB_SYNC",
        "MDS_BLOB", String.valueOf(version),
        null,
        Map.of("version", version, "entries", entries)));
```

각 파일을 Read 한 다음 정확한 `new AuditAppendRequest(...)` 블록을 위 새 시그니처로 Edit. 기존 payload Map 내용은 보존.

- [ ] **Step 5: 테스트 파일 11 곳 일괄 갱신**

각 테스트의 `new AuditLog(...)` 와 `new AuditAppendRequest(...)` 호출에 새 인자를 추가:
- `core/src/test/java/com/crosscert/passkey/core/entity/AuditLogTest.java` — 3 곳의 `new AuditLog(...)` 에 `null` (UUID tenantId) 을 targetId 와 payload 사이에 끼움.
- `core/src/test/java/com/crosscert/passkey/core/entity/BaseEntityCallbackIT.java:206` — 동일.
- `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogServiceTest.java` — `new AuditAppendRequest(...)` 4 곳 + `new AuditLog(...)` 1 곳 모두 `null` 끼움.
- `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainVerifierTest.java` — `new AuditLog(...)` 2 곳 + `new AuditAppendRequest(...)` 1 곳 모두 `null` 끼움.

예시:
```java
// Before:
new AuditLog(null, hash, actorId, "x@y", "TEST", null, null, "{}", Instant.now());
// After:
new AuditLog(null, hash, actorId, "x@y", "TEST", null, null, null, "{}", Instant.now());
//                                                                ^^^^ tenantId
```

```java
// Before:
new AuditAppendRequest(actorId, "x@y", "TEST", null, null, Map.of("k", "v"));
// After:
new AuditAppendRequest(actorId, "x@y", "TEST", null, null, null, Map.of("k", "v"));
//                                                          ^^^^ tenantId
```

- [ ] **Step 6: 전체 컴파일 확인**

Run: `./gradlew :admin-app:compileJava :admin-app:compileTestJava :core:compileJava :core:compileTestJava -q`
Expected: BUILD SUCCESSFUL. 11 곳 모두 새 시그니처로 갱신됐다는 의미.

빠진 곳이 있으면: `cannot be applied to given types`. 메시지의 file:line 으로 가서 추가 후 재실행.

- [ ] **Step 7: 빠른 AuditLog 관련 IT 한 번**

Run: `./gradlew :admin-app:test --tests 'AuditChainVerifierTest' --tests 'AuditLogServiceTest' -q 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL. tenant_id 가 hash 입력에 없으므로 기존 chain 검증 통과.

(`Testcontainers` 가 필요 없는 단위/slice test 만 — IT 는 Task 7-8 에서.)

- [ ] **Step 8: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditAppendRequest.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainVerifier.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJob.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java \
        core/src/test/java/com/crosscert/passkey/core/entity/AuditLogTest.java \
        core/src/test/java/com/crosscert/passkey/core/entity/BaseEntityCallbackIT.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogServiceTest.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainVerifierTest.java
git commit -m "$(cat <<'EOF'
feat(admin): AuditAppendRequest tenantId + 11 곳 호출 갱신

10 곳의 audit.append + 1 곳의 AuditChainVerifier.recomputeHash 모두 새
시그니처로. TENANT_CREATE/UPDATE/CREDENTIAL_REVOKE/API_KEY_ISSUE/REVOKE 는
명시적 tenantId, ADMIN_LOGIN/FAILED/SIGNING_KEY/MDS 는 null (platform-wide).

computeHash 입력 미변경 — tenant_id 는 hash 안 들어감, V10 chain 보존.
verifier 의 recomputeHash 도 row.getTenantId() 를 새 req 인자로 넘기지만
computeHash 는 그것을 안 읽으므로 결과 동일.

테스트 파일 4 개 (AuditLogTest, BaseEntityCallbackIT, AuditLogServiceTest,
AuditChainVerifierTest) 의 8-arg new AuditLog + 5-payload-arg
new AuditAppendRequest 도 새 시그니처로 갱신 (tenantId=null).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 9: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree .claude/worktrees/activity-page. Task 3 of plan 2026-05-27-activity-page.md: AuditAppendRequest gained tenantId param, all 11 production constructor sites + 4 test files updated. Verify (a) all 10 audit.append() sites have correct tenantId mapping per spec §2.5 table — TENANT_CREATE→tenant.getId(), CREDENTIAL_REVOKE→method arg tenantId, API_KEY_ISSUE→req.tenantId(), API_KEY_REVOKE→k.getTenantId(), ADMIN_LOGIN/SIGNING/MDS→null; (b) AuditChainVerifier.recomputeHash passes row.getTenantId() into the new req, but computeHash itself wasn't touched (so V24 doesn't break the chain); (c) no audit.append call was missed (search for 'new AuditAppendRequest' returns exactly 11 production + N test occurrences). 200 words max.")
```

---

## Task 4: ActivityRepository + ActivityView DTO + TenantRow record

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/ActivityRepository.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityView.java`

- [ ] **Step 1: ActivityRepository 작성**

`core/src/main/java/com/crosscert/passkey/core/repository/ActivityRepository.java`:

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AuditLog;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only projection of AUDIT_LOG for the Activity dashboard.
 *
 * <p>This is a separate {@code Repository} interface (not extending
 * {@link AuditLogRepository}) to keep dashboard queries from polluting the
 * append/verify code path. {@code Repository} (not {@code JpaRepository}) so we
 * expose only what's needed.
 *
 * <p>The {@code feedRaw} / {@code feedFilteredRaw} WHERE clauses compare against
 * the {@code (createdAt, id)} tuple of {@code sinceId}'s row, NOT just createdAt.
 * This is required because Oracle TIMESTAMP precision is microseconds — two rows
 * can share a timestamp under heavy ADMIN_LOGIN bursts. ORDER BY uses the same
 * tuple so filter + sort are aligned, and the polling client never misses a row.
 */
public interface ActivityRepository extends Repository<AuditLog, UUID> {

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.createdAt >= :since")
    long countSince(@Param("since") Instant since);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.createdAt >= :since AND a.action IN :actions")
    long countByActionsSince(@Param("actions") Set<String> actions,
                              @Param("since") Instant since);

    @Query("""
        SELECT new com.crosscert.passkey.core.repository.ActivityRepository$TenantRow(a.tenantId, COUNT(a))
        FROM AuditLog a
        WHERE a.createdAt >= :since AND a.tenantId IS NOT NULL
        GROUP BY a.tenantId
        ORDER BY COUNT(a) DESC
    """)
    List<TenantRow> topTenantsSinceRaw(@Param("since") Instant since, Pageable limit);

    default List<TenantRow> topTenantsSince(Instant since, int n) {
        return topTenantsSinceRaw(since, PageRequest.of(0, n));
    }

    @Query("""
        SELECT a FROM AuditLog a
        WHERE :sinceId IS NULL
           OR a.createdAt > (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
           OR (a.createdAt = (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
               AND a.id > (SELECT b.id FROM AuditLog b WHERE b.id = :sinceId))
        ORDER BY a.createdAt DESC, a.id DESC
    """)
    List<AuditLog> feedRaw(@Param("sinceId") UUID sinceId, Pageable limit);

    default List<AuditLog> feed(UUID sinceId, int n) {
        return feedRaw(sinceId, PageRequest.of(0, n));
    }

    @Query("""
        SELECT a FROM AuditLog a
        WHERE a.action IN :actions
          AND (:sinceId IS NULL
               OR a.createdAt > (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
               OR (a.createdAt = (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
                   AND a.id > (SELECT b.id FROM AuditLog b WHERE b.id = :sinceId)))
        ORDER BY a.createdAt DESC, a.id DESC
    """)
    List<AuditLog> feedFilteredRaw(@Param("actions") Set<String> actions,
                                   @Param("sinceId") UUID sinceId, Pageable limit);

    default List<AuditLog> feedFiltered(Set<String> actions, UUID sinceId, int n) {
        return feedFilteredRaw(actions, sinceId, PageRequest.of(0, n));
    }

    record TenantRow(UUID tenantId, long count) {}
}
```

- [ ] **Step 2: ActivityView DTO 작성**

`admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityView.java`:

```java
package com.crosscert.passkey.admin.activity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response payload for GET /admin/api/activity.
 *
 * <p>kpi.p95Ms is intentionally {@code Long} (nullable) — null is rendered
 * as 'N/A' in the UI until Micrometer instrumentation is wired in a later
 * phase. The other three KPIs are always non-null counts.
 *
 * <p>top5 is at most 5 tenants ordered by 24h event count desc.
 * Rows with {@code tenant_id IS NULL} (ADMIN_LOGIN, signing-key, MDS sync)
 * are excluded from top5 by the repository query.
 *
 * <p>feed.category is one of: {@code "ops"} / {@code "security"} / {@code "system"}.
 */
public record ActivityView(
        Kpi kpi,
        List<TopTenant> top5,
        List<Event> feed
) {
    public record Kpi(
            long events24h,
            long ops24h,
            long security24h,
            Long p95Ms
    ) {}

    public record TopTenant(
            UUID tenantId,
            String slug,
            long count
    ) {}

    public record Event(
            UUID id,
            String action,
            String actorEmail,
            String targetType,
            String targetId,
            UUID tenantId,
            String tenantSlug,
            Instant createdAt,
            String category
    ) {}
}
```

- [ ] **Step 3: 컴파일**

Run: `./gradlew :admin-app:compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/repository/ActivityRepository.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityView.java
git commit -m "$(cat <<'EOF'
feat(admin): ActivityRepository + ActivityView DTO

ActivityRepository: countSince / countByActionsSince / topTenantsSinceRaw
(NULL tenant 제외) / feedRaw / feedFilteredRaw — 모두 (createdAt, id) tuple
비교 + ORDER BY 로 동일 ms timestamp 누락 0.

ActivityView: kpi (long counts + Long p95Ms nullable) / top5 (max 5
TopTenant) / feed (Event 의 category 는 ops/security/system).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree .claude/worktrees/activity-page. Task 4: created ActivityRepository (in core module, separate from AuditLogRepository) + ActivityView DTO (in admin-app activity package). Verify (a) JPQL syntax — Set<String> action IN :actions binding, constructor expression for TenantRow, sinceId tuple comparison correctness; (b) tenant_id IS NOT NULL filter in topTenantsSinceRaw excludes ADMIN_LOGIN rows from Top 5; (c) ActivityView records are immutable + p95Ms is Long (boxed) for nullability. 150 words max.")
```

---

## Task 5: ActivityService + ActivityController

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityService.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityController.java`

- [ ] **Step 1: ActivityService 작성**

`admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityService.java`:

```java
package com.crosscert.passkey.admin.activity;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.ActivityRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the snapshot served by GET /admin/api/activity.
 *
 * <p>OPS_ACTIONS / SECURITY_ACTIONS are closed sets of action constants. Any
 * action not in either set is categorized as {@code "system"} (e.g.,
 * SIGNING_KEY_ROTATE, MDS_BLOB_SYNC).
 */
@Service
public class ActivityService {

    static final Set<String> OPS_ACTIONS = Set.of(
            "TENANT_CREATE", "TENANT_UPDATE",
            "CREDENTIAL_REVOKE",
            "API_KEY_ISSUE", "API_KEY_REVOKE",
            "SIGNING_KEY_ROTATE",
            "ADMIN_LOGIN");

    static final Set<String> SECURITY_ACTIONS = Set.of(
            "ADMIN_LOGIN_FAILED");

    private static final int TOP_N = 5;
    private static final int FEED_PAGE = 50;
    private static final Duration WINDOW = Duration.ofHours(24);

    private final ActivityRepository activity;
    private final TenantRepository tenants;
    private final Clock clock;

    public ActivityService(ActivityRepository activity,
                            TenantRepository tenants,
                            Clock clock) {
        this.activity = activity;
        this.tenants = tenants;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ActivityView snapshot(UUID sinceId, String category) {
        Instant since = clock.instant().minus(WINDOW);

        long events24h    = activity.countSince(since);
        long ops24h       = activity.countByActionsSince(OPS_ACTIONS, since);
        long security24h  = activity.countByActionsSince(SECURITY_ACTIONS, since);

        List<ActivityView.TopTenant> top5 = activity.topTenantsSince(since, TOP_N)
                .stream()
                .map(row -> new ActivityView.TopTenant(
                        row.tenantId(),
                        tenants.findById(row.tenantId())
                                .map(Tenant::getSlug)
                                .orElse("(deleted)"),
                        row.count()))
                .toList();

        Set<String> actionFilter = switch (category == null ? "all" : category) {
            case "ops"      -> OPS_ACTIONS;
            case "security" -> SECURITY_ACTIONS;
            default         -> Set.of();
        };
        List<AuditLog> feed = actionFilter.isEmpty()
                ? activity.feed(sinceId, FEED_PAGE)
                : activity.feedFiltered(actionFilter, sinceId, FEED_PAGE);

        // distinct().toMap() avoids N+1 — 1 query per unique tenant in the page.
        Map<UUID, String> slugByTenant = feed.stream()
                .map(AuditLog::getTenantId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toMap(
                        Function.identity(),
                        tid -> tenants.findById(tid).map(Tenant::getSlug).orElse("(deleted)")));

        List<ActivityView.Event> events = feed.stream()
                .map(a -> new ActivityView.Event(
                        a.getId(),
                        a.getAction(),
                        a.getActorEmail(),
                        a.getTargetType(),
                        a.getTargetId(),
                        a.getTenantId(),
                        a.getTenantId() == null ? null : slugByTenant.get(a.getTenantId()),
                        a.getCreatedAt(),
                        categorize(a.getAction())))
                .toList();

        return new ActivityView(
                new ActivityView.Kpi(events24h, ops24h, security24h, null),
                top5, events);
    }

    private String categorize(String action) {
        if (OPS_ACTIONS.contains(action))      return "ops";
        if (SECURITY_ACTIONS.contains(action)) return "security";
        return "system";
    }
}
```

- [ ] **Step 2: ActivityController 작성**

`admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityController.java`:

```java
package com.crosscert.passkey.admin.activity;

import com.crosscert.passkey.core.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/api/activity")
public class ActivityController {

    private final ActivityService service;

    public ActivityController(ActivityService service) {
        this.service = service;
    }

    /**
     * PLATFORM_OPERATOR 전용. RP_ADMIN 은 TenantDetail 의 Activity 탭에서
     * /admin/api/audit?tenantId={self} 를 사용 — admin-role-separation 의
     * scope check 가 자기 tenant 강제.
     */
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping
    public ApiResponse<ActivityView> activity(
            @RequestParam(required = false) UUID sinceId,
            @RequestParam(required = false) String category) {
        return ApiResponse.ok(service.snapshot(sinceId, category));
    }
}
```

- [ ] **Step 3: 컴파일**

Run: `./gradlew :admin-app:compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityController.java
git commit -m "$(cat <<'EOF'
feat(admin): ActivityService + ActivityController — PLATFORM_OPERATOR 전용

snapshot(sinceId, category) — Clock 기반 24h KPI / Top 5 (NULL tenant
제외 + slug N+1 방지) / category 필터 feed. p95Ms = null (후속 phase).

GET /admin/api/activity?sinceId=&category= — @PreAuthorize PLATFORM_OPERATOR.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree .claude/worktrees/activity-page. Task 5: ActivityService (Clock 기반 24h window, OPS/SECURITY action sets, slug N+1 avoidance via distinct().toMap()) + ActivityController (PLATFORM_OPERATOR only). Verify (a) Clock injection is consistent with AuditLogService pattern; (b) categorize() default returns 'system' so unrecognized actions don't crash; (c) Set.of() literal allocation in feed branch returns activity.feed (no filter) vs feedFiltered (with filter) correctly; (d) @PreAuthorize('hasRole') prefix is correct (Spring Security adds ROLE_); (e) no leakage of internal Tenant entity in response. 200 words max.")
```

---

## Task 6: AuditLogController/Service tenant scope + AuditLogView.tenantId

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogController.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogService.java` 에 `search()` method 신규 (또는 AuditLogService 분리 — 기존 파일에 search 추가)
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java` — search 시그니처 갱신

⚠️ **결정**: spec §3.5 에서 `AuditLogService.search()` 가 호출되는데 현재 `AuditLogService` 는 append 만 가지고 controller 가 직접 `AuditLogRepository.search` 를 호출. 둘 중 하나:
- **(A)** controller 에 tenant scope check 직접 (TenantBoundary 호출). service 안 만듦.
- **(B)** service 에 search() 추가, controller 는 service 호출.

**(A) 선택** — `AuditLogService` 를 audit 도메인의 hash 책임으로 유지 (지금 append 만 하는 이유) + tenant scope 는 매우 얇은 layer 라 controller 안 inline 이 더 명확.

- [ ] **Step 1: AuditLogRepository.search 시그니처에 tenantId 추가**

`core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java` 의 `search` 메서드를:

```java
    /** Read API for the audit-log page. Filters are all optional. */
    @Query("select a from AuditLog a " +
           "where (:action is null or a.action = :action) " +
           "  and (:actorId is null or a.actorId = :actorId) " +
           "  and (:tenantId is null or a.tenantId = :tenantId) " +
           "  and (:from is null or a.createdAt >= :from) " +
           "  and (:to   is null or a.createdAt <  :to) " +
           "order by a.createdAt desc, a.id desc")
    Page<AuditLog> search(@Param("action") String action,
                          @Param("actorId") UUID actorId,
                          @Param("tenantId") UUID tenantId,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable page);
```

- [ ] **Step 2: AuditLogController.list 에 tenant scope check + tenantId param + AuditLogView 갱신**

`admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogController.java`:

```java
package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/admin/api/audit")
public class AuditLogController {

    private final AuditLogRepository repo;
    private final AuditChainVerifier verifier;
    private final TenantBoundary tenantBoundary;

    public AuditLogController(AuditLogRepository repo,
                              AuditChainVerifier verifier,
                              TenantBoundary tenantBoundary) {
        this.repo = repo;
        this.verifier = verifier;
        this.tenantBoundary = tenantBoundary;
    }

    /**
     * PLATFORM_OPERATOR 는 모든 tenant query 가능. RP_ADMIN 은 자기 tenant 만 —
     * tenantId 미지정이면 자동 scope, 명시한 tenantId 가 자기 것 아니면 403.
     */
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @GetMapping
    public ApiResponse<List<AuditLogView>> list(@RequestParam(required = false) String action,
                                                @RequestParam(required = false) UUID actorId,
                                                @RequestParam(required = false) UUID tenantId,
                                                @RequestParam(required = false) Instant from,
                                                @RequestParam(required = false) Instant to,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        Optional<UUID> scope = tenantBoundary.currentTenantScope();
        // PLATFORM_OPERATOR: scope=empty → 클라이언트 tenantId 그대로 쓴다 (null=all).
        // RP_ADMIN: scope=Optional.of(my) → tenantId 강제. 명시한 tenantId 가
        // 다른 것이면 403.
        UUID effectiveTenantId;
        if (scope.isPresent()) {
            if (tenantId != null && !scope.get().equals(tenantId)) {
                throw new BusinessException(ErrorCode.ACCESS_DENIED,
                        "RP_ADMIN cannot query audit for other tenant");
            }
            effectiveTenantId = scope.get();
        } else {
            effectiveTenantId = tenantId;
        }

        Page<AuditLog> p = repo.search(action, actorId, effectiveTenantId, from, to,
                PageRequest.of(page, Math.min(size, 200)));
        return ApiResponse.ok(p.getContent().stream().map(AuditLogView::from).toList());
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping("/verify")
    public ApiResponse<AuditChainVerifier.Result> verify() {
        return ApiResponse.ok(verifier.verify());
    }

    public record AuditLogView(
            UUID id, UUID actorId, String actorEmail, String action,
            String targetType, String targetId,
            UUID tenantId,
            String payload, Instant createdAt) {
        public static AuditLogView from(AuditLog a) {
            return new AuditLogView(
                    a.getId(), a.getActorId(), a.getActorEmail(), a.getAction(),
                    a.getTargetType(), a.getTargetId(),
                    a.getTenantId(),
                    a.getPayload(), a.getCreatedAt());
        }
    }
}
```

- [ ] **Step 3: 컴파일 + 빠른 회귀**

Run: `./gradlew :admin-app:compileJava :admin-app:compileTestJava -q`
Expected: BUILD SUCCESSFUL.

(`AuditLogServiceTest` 는 변경 없음 — `search` 는 controller 책임이라 service 안 건드림.)

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogController.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java
git commit -m "$(cat <<'EOF'
feat(admin): /admin/api/audit RP_ADMIN 접근 + tenantId scope check

GET /audit: hasAnyRole(PLATFORM_OPERATOR, RP_ADMIN) — admin-role-separation
에서 차단됐던 RP_ADMIN 접근 회복. RP_ADMIN 은 tenantBoundary 의 currentTenantScope
로 자기 tenant 강제, 명시한 tenantId 가 다른 것이면 403 ACCESS_DENIED.

AuditLogView 에 tenantId 노출. AuditLogRepository.search 에 tenantId
optional param.

GET /audit/verify 는 PLATFORM_OPERATOR 전용 그대로 (chain 전체 검증, scope
적용 불가능).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree .claude/worktrees/activity-page. Task 6: AuditLogController.list now allows RP_ADMIN with TenantBoundary scope check. Verify (a) the if (scope.isPresent()) branch correctly handles three sub-cases: RP_ADMIN with no tenantId (auto-scope), RP_ADMIN with matching tenantId (OK), RP_ADMIN with different tenantId (403); (b) PLATFORM_OPERATOR branch (scope empty) passes tenantId through unchanged (null = all tenants); (c) AuditLogRepository.search picked up the new tenantId param without breaking existing IT callers (PlatformOperatorUnrestrictedIT step 10 etc.); (d) AuditLogView.from now includes a.getTenantId(); (e) /verify endpoint is still PLATFORM_OPERATOR only (RP_ADMIN cannot verify chain). 200 words max.")
```

---

## Task 7: ActivityControllerIT 신규 (자동 IT 1/2)

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/activity/ActivityControllerIT.java`

기존 IT pattern (e.g., `AdminFlowIT`, `RpAdminBoundaryIT`) 따라 Testcontainers Oracle + alice/bob 시드.

- [ ] **Step 1: 기존 IT pattern 파악**

Run: `cat admin-app/src/test/java/com/crosscert/passkey/admin/auth/RpAdminBoundaryIT.java | head -80`
(or `wc -l` and read it fully) — `@SpringBootTest` annotation, `MockMvc` setup, `resetState` helper, `loginAs(alice/bob)`.

- [ ] **Step 2: ActivityControllerIT 작성 (5 assertion)**

`admin-app/src/test/java/com/crosscert/passkey/admin/activity/ActivityControllerIT.java`:

```java
package com.crosscert.passkey.admin.activity;

import com.crosscert.passkey.admin.testsupport.AdminIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AdminIntegrationTest
class ActivityControllerIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void activitySnapshotKpiTop5Feed() throws Exception {
        // ─── 1. alice (PLATFORM_OPERATOR) 로 tenant_A, tenant_B 생성 + tenant_A API key ───
        String aliceSession = loginAs("alice@crosscert.com", "alice-temp-pw");
        String tenantA = createTenant(aliceSession, "tenant-a");
        createTenant(aliceSession, "tenant-b");
        issueApiKey(aliceSession, tenantA);

        // ─── 2. bob 잘못된 비번 → ADMIN_LOGIN_FAILED row ───
        attemptLoginFailure("bob@demo-rp.com", "wrong-pw");

        // ─── 3. GET /admin/api/activity ───
        MvcResult res = mvc.perform(get("/admin/api/activity").session(aliceSession))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString()).get("data");

        // KPI: events >= 5 (tenant_A create, tenant_B create, apikey issue, alice login,
        //                    bob failure), ops >= 3, security >= 1, p95 == null
        assertThat(body.get("kpi").get("events24h").asLong()).isGreaterThanOrEqualTo(5L);
        assertThat(body.get("kpi").get("ops24h").asLong()).isGreaterThanOrEqualTo(3L);
        assertThat(body.get("kpi").get("security24h").asLong()).isGreaterThanOrEqualTo(1L);
        assertThat(body.get("kpi").get("p95Ms").isNull()).isTrue();

        // Top 5: tenant_A 와 tenant_B 가 보임. ADMIN_LOGIN/FAILED 는 tenant_id NULL 이라 미포함.
        JsonNode top5 = body.get("top5");
        assertThat(top5.size()).isBetween(1, 5);  // dogfood — alice 의 tenant_A 만 명확히 보임
        boolean tenantAFound = false;
        for (JsonNode t : top5) {
            if (t.get("tenantId").asText().equals(tenantA)) tenantAFound = true;
        }
        assertThat(tenantAFound).isTrue();

        // Feed: createdAt DESC ordering — 첫 row 가 가장 최근. 적어도 5 개 row.
        JsonNode feed = body.get("feed");
        assertThat(feed.size()).isGreaterThanOrEqualTo(5);
        for (int i = 1; i < feed.size(); i++) {
            String prev = feed.get(i - 1).get("createdAt").asText();
            String curr = feed.get(i).get("createdAt").asText();
            assertThat(prev.compareTo(curr)).isGreaterThanOrEqualTo(0);
        }

        // ─── 4. sinceId={feed[0].id} → 빈 feed ───
        String firstId = feed.get(0).get("id").asText();
        MvcResult res2 = mvc.perform(get("/admin/api/activity")
                        .param("sinceId", firstId)
                        .session(aliceSession))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body2 = json.readTree(res2.getResponse().getContentAsString()).get("data");
        assertThat(body2.get("feed").size()).isZero();

        // ─── 5. bob 으로 정상 로그인 → 새 ADMIN_LOGIN → 같은 sinceId → 새 row 보임 ───
        // (bob 은 RP_ADMIN — admin-role-separation 시드)
        loginAs("bob@demo-rp.com", "bob-temp-pw");
        MvcResult res3 = mvc.perform(get("/admin/api/activity")
                        .param("sinceId", firstId)
                        .session(aliceSession))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body3 = json.readTree(res3.getResponse().getContentAsString()).get("data");
        assertThat(body3.get("feed").size()).isGreaterThanOrEqualTo(1);
        // 새 row 는 ADMIN_LOGIN 또는 bob 액션 — category="ops" or "security"
    }

    // 헬퍼: 기존 AdminFlowIT / RpAdminBoundaryIT 의 패턴 그대로 차용.
    // (Read 한 다음 helper signature 맞춰서 inline 또는 testsupport import)

    private String loginAs(String email, String password) throws Exception { /* … */ }
    private String createTenant(String session, String slug) throws Exception { /* … */ }
    private void issueApiKey(String session, String tenantId) throws Exception { /* … */ }
    private void attemptLoginFailure(String email, String password) throws Exception { /* … */ }
}
```

⚠️ **헬퍼 구현 노트**: `RpAdminBoundaryIT` / `AdminFlowIT` 의 헬퍼를 그대로 inline 복사. resetState 도 동일. `@AdminIntegrationTest` (또는 직접 `@SpringBootTest` + `@AutoConfigureMockMvc`) — Read 해서 정확한 annotation 확인.

- [ ] **Step 3: IT 실행**

Run: `./gradlew :admin-app:test --tests 'ActivityControllerIT' -q 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. 5 assertion 통과.

FAIL 시:
- KPI count mismatch — 24h window 시간 계산 / Clock 의존성 검증
- top5 비어 있음 — tenant_id 가 entity에 실제로 저장됐는지 (Task 3 회귀)
- sinceId mismatch — feedRaw 의 tuple WHERE 가 (createdAt > X OR …) 문법 검증

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/activity/ActivityControllerIT.java
git commit -m "$(cat <<'EOF'
test(admin): ActivityControllerIT — KPI + Top5 + sinceId polling 5 assertion

1. alice 로 tenant_A/B + API key + bob login fail → audit row 5+ 개
2. KPI: events>=5, ops>=3, security>=1, p95==null
3. top5 에 tenant_A 포함, NULL tenant 제외
4. sinceId={feed[0].id} → 빈 feed (idempotent polling)
5. bob 로그인 후 같은 sinceId → 새 row >= 1

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree .claude/worktrees/activity-page. Task 7: ActivityControllerIT covers KPI/top5/feed/sinceId for the new Activity endpoint. Verify (a) the test correctly uses the existing AdminFlowIT/RpAdminBoundaryIT helper pattern (or @AdminIntegrationTest annotation); (b) the sinceId=feed[0].id step actually proves the tuple WHERE works (no rows newer than the latest); (c) 5 ADMIN_LOGIN_FAILED is the only security action expected — make sure attemptLoginFailure actually writes an audit row even on failure (check AdminSecurityConfig:134 path); (d) the GT-OR-EQ tolerance on KPI counts is appropriate (allows seed rows + concurrent test data). 200 words max.")
```

---

## Task 8: AuditLogTenantScopingIT 신규 + RpAdminBoundaryIT step 14 갱신 (자동 IT 2/2)

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogTenantScopingIT.java`
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/RpAdminBoundaryIT.java` — step 14 의미 변경

- [ ] **Step 1: AuditLogTenantScopingIT 작성 (5 assertion)**

`admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogTenantScopingIT.java`:

```java
package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.admin.testsupport.AdminIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AdminIntegrationTest
class AuditLogTenantScopingIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void rpAdminCanReadOwnTenantAuditOnly() throws Exception {
        // 1. alice (PLATFORM_OPERATOR) 로 tenant_A 생성 → TENANT_CREATE row 의 tenant_id=tenant_A.id
        String aliceSession = loginAs("alice@crosscert.com", "alice-temp-pw");
        String tenantA = createTenant(aliceSession, "tenant-a-scoping");

        // demo-rp tenant id (bob 의 자기 tenant — admin-role-separation 시드)
        String demoRp = getDemoRpTenantId(aliceSession);

        // 2. bob (RP_ADMIN, demo-rp) 로그인
        String bobSession = loginAs("bob@demo-rp.com", "bob-temp-pw");

        // 3. GET /audit?tenantId=demo-rp → 200, demo-rp row 만, tenant_A 미포함
        MvcResult res3 = mvc.perform(get("/admin/api/audit")
                        .param("tenantId", demoRp)
                        .session(bobSession))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body3 = json.readTree(res3.getResponse().getContentAsString()).get("data");
        for (JsonNode row : body3) {
            String tid = row.get("tenantId").isNull() ? null : row.get("tenantId").asText();
            // demo-rp row 만 — null 이거나 (platform-wide) 또는 demoRp 와 일치
            assertThat(tid == null || tid.equals(demoRp)).isTrue();
            assertThat(tid).isNotEqualTo(tenantA);
        }

        // 4. GET /audit (tenantId 생략) → 200, 자기 tenant 만 (service 자동 scope)
        MvcResult res4 = mvc.perform(get("/admin/api/audit")
                        .session(bobSession))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body4 = json.readTree(res4.getResponse().getContentAsString()).get("data");
        for (JsonNode row : body4) {
            String tid = row.get("tenantId").isNull() ? null : row.get("tenantId").asText();
            // 자동 scope = demo-rp. tenant_A 는 절대 안 나옴.
            assertThat(tid).isNotEqualTo(tenantA);
        }

        // 5. GET /audit?tenantId=tenant_A → 403 ACCESS_DENIED
        mvc.perform(get("/admin/api/audit")
                        .param("tenantId", tenantA)
                        .session(bobSession))
                .andExpect(status().isForbidden());
    }

    // 헬퍼는 RpAdminBoundaryIT / AdminFlowIT 패턴 그대로
    private String loginAs(String email, String pw) throws Exception { /* … */ }
    private String createTenant(String session, String slug) throws Exception { /* … */ }
    private String getDemoRpTenantId(String session) throws Exception { /* … */ }
}
```

- [ ] **Step 2: RpAdminBoundaryIT step 14 의미 변경**

`admin-app/src/test/java/com/crosscert/passkey/admin/auth/RpAdminBoundaryIT.java` 를 Read 해서 step 14 (audit GET 403) 의 정확한 line 위치 찾기. 패턴:

```java
// Before (step 14): GET /audit → 403 ROLE_PLATFORM_OPERATOR required
mvc.perform(get("/admin/api/audit").session(bobSession))
   .andExpect(status().isForbidden());

// After: GET /audit?tenantId={other} → 403, 자기 tenant 는 200
mvc.perform(get("/admin/api/audit")
        .param("tenantId", otherTenantId)  // tenant_A or alice tenant
        .session(bobSession))
   .andExpect(status().isForbidden());

// 자기 tenant 는 OK:
mvc.perform(get("/admin/api/audit").session(bobSession))
   .andExpect(status().isOk());
```

⚠️ Read 한 다음 정확한 step 14 패턴 (helper 메서드명, otherTenantId 가져오는 방법) 확인 후 그에 맞춰 변경.

- [ ] **Step 3: IT 2 개 실행**

Run: `./gradlew :admin-app:test --tests 'AuditLogTenantScopingIT' --tests 'RpAdminBoundaryIT' -q 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 전체 IT 회귀 (7 개)**

Run: `./gradlew :admin-app:test --tests '*IT' -q 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. AdminFlowIT 의 verify() 도 ok=true (chain 보존).

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogTenantScopingIT.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/auth/RpAdminBoundaryIT.java
git commit -m "$(cat <<'EOF'
test(admin): AuditLogTenantScopingIT + RpAdminBoundaryIT step 14 갱신

신규 AuditLogTenantScopingIT (5 assertion):
- RP_ADMIN bob 의 GET /audit?tenantId={self} → 200, demo-rp row 만
- GET /audit (생략) → 자기 scope 자동
- GET /audit?tenantId={tenant_A} → 403 ACCESS_DENIED

기존 RpAdminBoundaryIT step 14: '/audit → 403' 의 의미 변경 — 이제
RP_ADMIN 도 GET 가능, cross-tenant tenantId 만 403.

전체 7 IT 통과 확인.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree .claude/worktrees/activity-page. Task 8: AuditLogTenantScopingIT (5 assertions for RP_ADMIN cross-tenant blocking) + RpAdminBoundaryIT step 14 updated. Verify (a) the new IT correctly tests all three paths: explicit-own (200), implicit-own (200 with auto-scope), explicit-other (403); (b) the tenantId IS NULL handling in the assertion — platform-wide rows (ADMIN_LOGIN) with null tenantId are correctly allowed through RP_ADMIN's auto-scope query (or not — verify which behavior is intended); (c) RpAdminBoundaryIT step 14 changed semantics are documented in the test comment; (d) the 7 IT regression result (paste tail of gradle output) shows all green. 200 words max.")
```

⚠️ **AuditLog null tenant 노출 결정 짚어두기**: spec §3.5 의 `effectiveTenantId = scope.get()` 는 RP_ADMIN 의 query 에 `tenant_id = my_tenant` 를 강제하므로 `tenant_id IS NULL` row (ADMIN_LOGIN, MDS_BLOB_SYNC) 는 노출 안 됨 — 이건 의도. 위 assertion 4 의 "null 이거나 ..." 는 잘못된 가정. 수정:

```java
// Step 4 수정 (Step 1 의 코드 안):
for (JsonNode row : body4) {
    String tid = row.get("tenantId").isNull() ? null : row.get("tenantId").asText();
    // RP_ADMIN 의 자동 scope: tenant_id = demo-rp 만 — null tenant 도 미노출
    assertThat(tid).isEqualTo(demoRp);
}
```

step 3 의 `body3` 도 동일하게 `assertThat(tid).isEqualTo(demoRp)` 로 단순화. Task 8 시 코드 Edit 할 때 이 corrected 버전 쓸 것.

---

## Task 9: admin-ui types.ts + client.ts — Activity 타입 + AuditLog tenantId

**Files:**
- Modify: `admin-ui/src/api/types.ts` — Activity* 타입 + AuditLogView.tenantId
- Modify: `admin-ui/src/api/client.ts` — getActivity helper + getAuditLog 의 tenantId param

- [ ] **Step 1: types.ts 확장**

먼저 Read 해서 기존 구조 확인 (`AuditLogView` interface 어디 있는지).

`admin-ui/src/api/types.ts` 에 추가:

```typescript
export interface ActivityKpi {
    events24h: number;
    ops24h: number;
    security24h: number;
    p95Ms: number | null;
}

export interface ActivityTopTenant {
    tenantId: string;
    slug: string;
    count: number;
}

export interface ActivityEvent {
    id: string;
    action: string;
    actorEmail: string;
    targetType: string | null;
    targetId: string | null;
    tenantId: string | null;
    tenantSlug: string | null;
    createdAt: string;
    category: 'ops' | 'security' | 'system';
}

export interface ActivityView {
    kpi: ActivityKpi;
    top5: ActivityTopTenant[];
    feed: ActivityEvent[];
}

export type ActivityCategory = 'all' | 'ops' | 'security';
```

기존 `AuditLogView` 인터페이스에 `tenantId: string | null;` 한 줄 끼우기 — `actorEmail` 다음 (백엔드 record 순서와 맞추기). 정확한 위치는 Read 후 결정.

- [ ] **Step 2: client.ts 의 getActivity + getAuditLog**

먼저 Read 해서 기존 helper 패턴 확인 (`api.get<T>(url)` shape).

`admin-ui/src/api/client.ts` 에 추가/갱신:

```typescript
import type { ActivityView, ActivityCategory, AuditLogView } from './types';

// 기존 import 안에 ActivityView/ActivityCategory 추가, 또는 별도 import 라인

export const getActivity = (params: { sinceId?: string; category?: ActivityCategory }) => {
    const qs = new URLSearchParams();
    if (params.sinceId) qs.set('sinceId', params.sinceId);
    if (params.category && params.category !== 'all') qs.set('category', params.category);
    const q = qs.toString();
    return api.get<ActivityView>(`/admin/api/activity${q ? '?' + q : ''}`);
};

// 기존 getAuditLog 가 있으면 tenantId param 추가, 없으면 신규:
export const getAuditLog = (params: {
    action?: string;
    actorId?: string;
    tenantId?: string;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
}) => {
    const qs = new URLSearchParams();
    if (params.action)   qs.set('action', params.action);
    if (params.actorId)  qs.set('actorId', params.actorId);
    if (params.tenantId) qs.set('tenantId', params.tenantId);
    if (params.from)     qs.set('from', params.from);
    if (params.to)       qs.set('to', params.to);
    if (params.page !== undefined) qs.set('page', String(params.page));
    if (params.size !== undefined) qs.set('size', String(params.size));
    return api.get<AuditLogView[]>(`/admin/api/audit?${qs}`);
};
```

⚠️ 기존 `getAuditLog` 가 fetch 직접 호출 형식이면 (Read 후 결정), 같은 스타일 유지. `api.get<T>(...)` 는 envelope unwrap 후 `data` 반환 — `client.ts` 의 다른 helper 패턴 그대로.

- [ ] **Step 3: TS 컴파일**

Run: `cd admin-ui && npx tsc --noEmit 2>&1 | tail -10`
Expected: 에러 없음.

(admin-ui 의 다른 모든 페이지가 컴파일 통과해야 함 — AuditLog.tsx 의 기존 사용처가 tenantId 미사용이라도 추가 필드는 optional 이므로 OK.)

- [ ] **Step 4: Commit**

```bash
git add admin-ui/src/api/types.ts admin-ui/src/api/client.ts
git commit -m "$(cat <<'EOF'
feat(admin-ui): types + client — Activity 타입 + AuditLogView.tenantId

ActivityView/Kpi/TopTenant/Event/Category 타입. getActivity(sinceId, category)
helper. getAuditLog 에 tenantId param 추가.

AuditLogView 에 tenantId: string | null — backend AuditLogView record
와 맞춤.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree .claude/worktrees/activity-page. Task 9: admin-ui types.ts + client.ts gained Activity types and AuditLogView.tenantId. Verify (a) the TS shape of ActivityKpi matches backend ActivityView.Kpi exactly (long → number, Long p95Ms → number | null, not bigint); (b) ActivityEvent.category is the discriminated union 'ops'|'security'|'system', not just string; (c) the getActivity URL builds correctly when both sinceId and category are absent (no '?' suffix); (d) getAuditLog's existing call sites still work with tenantId as optional. 150 words max.")
```

---

## Task 10: Activity 페이지 + Sidebar PLATFORM_NAV + App.tsx 라우트

**Files:**
- Create: `admin-ui/src/pages/Activity.tsx`
- Modify: `admin-ui/src/components/Sidebar.tsx` — `PLATFORM_NAV` 에 Activity 메뉴
- Modify: `admin-ui/src/App.tsx` — `/activity` 라우트

- [ ] **Step 1: Activity.tsx 작성**

`admin-ui/src/pages/Activity.tsx`:

```tsx
import { useCallback, useEffect, useRef, useState } from 'react';
import PlatformOnlyGuard from '../components/PlatformOnlyGuard';
import { getActivity } from '../api/client';
import { formatDateTime } from '../lib/formatDateTime';
import type { ActivityView, ActivityCategory, ActivityEvent, ActivityTopTenant } from '../api/types';

const POLL_MS = 5000;
const FEED_MAX = 200;

export default function Activity() {
    return <PlatformOnlyGuard><ActivityInner /></PlatformOnlyGuard>;
}

function ActivityInner() {
    const [view, setView] = useState<ActivityView | null>(null);
    const [category, setCategory] = useState<ActivityCategory>('all');
    const [error, setError] = useState<string | null>(null);
    const lastIdRef = useRef<string | null>(null);

    const loadInitial = useCallback(async () => {
        setError(null);
        try {
            const v = await getActivity({ category });
            setView(v);
            lastIdRef.current = v.feed[0]?.id ?? null;
        } catch (e) {
            setError((e as Error)?.message ?? 'load failed');
        }
    }, [category]);

    const poll = useCallback(async () => {
        try {
            const v = await getActivity({
                category,
                sinceId: lastIdRef.current ?? undefined,
            });
            setView((prev) => {
                if (!prev) return v;
                const merged = [...v.feed, ...prev.feed].slice(0, FEED_MAX);
                return { kpi: v.kpi, top5: v.top5, feed: merged };
            });
            if (v.feed.length > 0) lastIdRef.current = v.feed[0].id;
        } catch { /* silent — 첫 로드 banner 외에는 무시 */ }
    }, [category]);

    useEffect(() => { loadInitial(); }, [loadInitial]);
    useEffect(() => {
        const tick = setInterval(poll, POLL_MS);
        return () => clearInterval(tick);
    }, [poll]);

    if (error) return <div className="banner banner--danger">{error}</div>;
    if (!view) return <div className="muted">불러오는 중…</div>;

    return (
        <div className="stack-4">
            <h1 style={{ margin: 0 }}>Activity</h1>

            <div className="row" style={{ gap: 12 }}>
                <KpiCard label="24h 활동량"      value={view.kpi.events24h} />
                <KpiCard label="운영 액션 24h"   value={view.kpi.ops24h} />
                <KpiCard label="보안 이벤트 24h" value={view.kpi.security24h} accent="danger" />
                <KpiCard label="p95 응답 (ms)"   value={view.kpi.p95Ms ?? 'N/A'} muted />
            </div>

            <div className="row" style={{ gap: 16, alignItems: 'flex-start' }}>
                <Top5Panel top5={view.top5} />
                <FeedPanel
                    events={view.feed}
                    category={category}
                    onCategoryChange={(c) => {
                        setCategory(c);
                        lastIdRef.current = null;
                    }}
                />
            </div>
        </div>
    );
}

function KpiCard({ label, value, accent, muted }:
                  { label: string; value: number | string; accent?: 'danger'; muted?: boolean }) {
    return (
        <div className="card" style={{ flex: 1, padding: 16 }}>
            <div className="muted" style={{ fontSize: 12, marginBottom: 4 }}>{label}</div>
            <div style={{
                fontSize: 28,
                fontWeight: 600,
                color: muted ? 'var(--text-mute)' :
                       accent === 'danger' ? 'var(--danger)' : 'var(--text)',
            }}>{value}</div>
        </div>
    );
}

function Top5Panel({ top5 }: { top5: ActivityTopTenant[] }) {
    return (
        <div className="card" style={{ width: 280, padding: 16 }}>
            <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 12 }}>활발한 Tenant Top 5</div>
            {top5.length === 0 && <div className="muted" style={{ fontSize: 12 }}>최근 24h 활동 없음</div>}
            {top5.map((t) => (
                <div key={t.tenantId} className="row" style={{
                    justifyContent: 'space-between', padding: '6px 0',
                    borderBottom: '1px solid var(--border-mute)',
                }}>
                    <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{t.slug}</span>
                    <span className="badge">{t.count}</span>
                </div>
            ))}
        </div>
    );
}

function FeedPanel({ events, category, onCategoryChange }:
                    { events: ActivityEvent[]; category: ActivityCategory;
                      onCategoryChange: (c: ActivityCategory) => void }) {
    return (
        <div className="card" style={{ flex: 1, padding: 16 }}>
            <div className="row" style={{ justifyContent: 'space-between', marginBottom: 12 }}>
                <div style={{ fontSize: 13, fontWeight: 600 }}>이벤트 스트림</div>
                <CategoryChips current={category} onChange={onCategoryChange} />
            </div>
            <div style={{ maxHeight: 480, overflowY: 'auto' }}>
                {events.length === 0 && (
                    <div className="muted" style={{ textAlign: 'center', padding: 24 }}>
                        이벤트 없음
                    </div>
                )}
                {events.map((e) => (
                    <div key={e.id} style={{
                        padding: '8px 0',
                        borderBottom: '1px solid var(--border-mute)',
                    }}>
                        <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                            <span className={'badge badge--' + e.category}>{e.action}</span>
                            <span className="muted" style={{ fontSize: 11 }}>
                                {e.tenantSlug ?? 'platform'}
                            </span>
                            <span className="muted" style={{ fontSize: 11, marginLeft: 'auto' }}>
                                {formatDateTime(e.createdAt)}
                            </span>
                        </div>
                        <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>
                            {e.actorEmail}
                            {e.targetType && <> · {e.targetType}{e.targetId && '/' + e.targetId.slice(0, 8)}…</>}
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
}

function CategoryChips({ current, onChange }:
                        { current: ActivityCategory; onChange: (c: ActivityCategory) => void }) {
    const chips: { key: ActivityCategory; label: string }[] = [
        { key: 'all',      label: '전체' },
        { key: 'ops',      label: '운영' },
        { key: 'security', label: '보안' },
    ];
    return (
        <div className="row" style={{ gap: 6 }}>
            {chips.map((c) => (
                <button
                    key={c.key}
                    className={'btn btn--sm ' + (current === c.key ? 'btn--primary' : 'btn--ghost')}
                    onClick={() => onChange(c.key)}
                >{c.label}</button>
            ))}
        </div>
    );
}
```

- [ ] **Step 2: Sidebar.tsx — PLATFORM_NAV 에 Activity 메뉴**

먼저 Read 해서 기존 `PLATFORM_NAV` 위치 + icon import 확인 (`Activity` icon 이 `Icons.tsx` 에 있는지). 없으면 inline svg 또는 `lucide-react` 활용 (admin-ui 의 기존 패턴 확인).

`PLATFORM_NAV` 배열의 `Tenants` 와 `Signing Keys` 사이에 추가:

```typescript
{ to: '/activity', label: 'Activity', icon: ActivityIcon },
```

`ActivityIcon` 은 `Icons.tsx` 의 기존 export 와 동일 패턴 (또는 inline SVG):
```typescript
// Icons.tsx 끝에 export 추가 (필요 시):
export const ActivityIcon = ({ size = 16 }: { size?: number }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />
    </svg>
);
```

- [ ] **Step 3: App.tsx — /activity 라우트**

먼저 Read 해서 라우트 패턴 확인 (`Layout` wrapper 안 어떻게 들어가는지).

추가:
```tsx
import Activity from './pages/Activity';
// …
<Route path="/activity" element={<Activity />} />
```

`/tenants` 라우트 옆에. RP_ADMIN 이 직접 URL 진입해도 `PlatformOnlyGuard` 가 차단.

- [ ] **Step 4: TS 컴파일 + 빌드**

Run: `cd admin-ui && npx tsc --noEmit -q 2>&1 | tail -10`
Expected: 에러 없음.

Run: `cd admin-ui && npm run build 2>&1 | tail -15`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/pages/Activity.tsx \
        admin-ui/src/components/Sidebar.tsx \
        admin-ui/src/components/Icons.tsx \
        admin-ui/src/App.tsx
git commit -m "$(cat <<'EOF'
feat(admin-ui): Activity 페이지 + Sidebar PLATFORM_NAV + /activity 라우트

PlatformOnlyGuard wrap → ActivityInner: 첫 로드 snapshot, 5초 polling
sinceId 기반 incremental (FEED_MAX=200), category chip (전체/운영/보안)
변경 시 lastIdRef reset.

KPI 4 카드 — p95Ms null 은 'N/A'. Top 5 패널 (slug + count). 이벤트
스트림 (badge by category, tenantSlug fallback 'platform', actor + target).

Sidebar PLATFORM_NAV 에 Activity 메뉴 (Tenants 와 Signing Keys 사이).
RP_ADMIN 사이드바는 변경 없음 — /activity URL 진입 시 PlatformOnlyGuard 차단.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree .claude/worktrees/activity-page. Task 10: Activity.tsx + Sidebar + App.tsx route added. Verify (a) the polling effect cleanup runs setInterval clearInterval properly (no leak on unmount or category change); (b) lastIdRef reset on category change actually causes the next poll to re-fetch the full window for the new filter; (c) PlatformOnlyGuard wraps the entire page (RP_ADMIN gets redirected, no flash of KPI); (d) the merged feed dedup — does prev.feed already contain v.feed[0]? Spec says 'prepend v.feed onto prev.feed and slice', which can duplicate if poll catches the same sinceId boundary row. 200 words max.")
```

⚠️ Codex 가 dedup 문제 제기 가능성 — Activity.tsx 의 merge 로직은 `lastIdRef` 가 가장 최근 본 row 의 ID 라 다음 poll 의 `sinceId` 가 그것이고, 백엔드는 `WHERE id > sinceId tuple` 이라 중복 없음. dedup 불필요. Codex 가 우려를 제기하면 그 답변 그대로.

---

## Task 11: TenantActivityTab + TenantDetail 5번째 탭 + AuditLog tenantId column

**Files:**
- Create: `admin-ui/src/pages/tenant/TenantActivityTab.tsx`
- Modify: `admin-ui/src/pages/TenantDetail.tsx` — TABS 배열 + 렌더 분기
- Modify: `admin-ui/src/pages/AuditLog.tsx` — tenantId input + 테이블 column

- [ ] **Step 1: TenantActivityTab 작성**

`admin-ui/src/pages/tenant/TenantActivityTab.tsx`:

```tsx
import { useCallback, useEffect, useState } from 'react';
import { getAuditLog } from '../../api/client';
import { formatDateTime } from '../../lib/formatDateTime';
import type { AuditLogView } from '../../api/types';

// Activity 페이지와 동일 5초 polling — 전역 일관성 유지. dogfood scale 에서 한
// tenant 의 audit 조회 부하는 무시할 수준 (단순 indexed query).
const POLL_MS = 5000;

interface Props {
    tenantId: string;
}

export default function TenantActivityTab({ tenantId }: Props) {
    const [rows, setRows] = useState<AuditLogView[]>([]);
    const [error, setError] = useState<string | null>(null);

    const refresh = useCallback(async () => {
        try {
            const r = await getAuditLog({ tenantId, size: 100 });
            setRows(r);
            setError(null);
        } catch (e) {
            setError((e as Error)?.message ?? 'load failed');
        }
    }, [tenantId]);

    useEffect(() => { refresh(); }, [refresh]);
    useEffect(() => {
        const tick = setInterval(refresh, POLL_MS);
        return () => clearInterval(tick);
    }, [refresh]);

    if (error) return <div className="banner banner--danger">{error}</div>;

    return (
        <div className="stack-3">
            <div className="row" style={{ justifyContent: 'space-between' }}>
                <div className="muted">{rows.length} events</div>
                <button className="btn btn--ghost btn--sm" onClick={refresh}>새로고침</button>
            </div>
            <table className="table">
                <thead>
                    <tr><th>action</th><th>actor</th><th>target</th><th>at</th></tr>
                </thead>
                <tbody>
                    {rows.length === 0 && (
                        <tr><td colSpan={4} className="muted" style={{ textAlign: 'center', padding: 24 }}>
                            audit 이벤트 없음
                        </td></tr>
                    )}
                    {rows.map(r => (
                        <tr key={r.id}>
                            <td><span className="badge">{r.action}</span></td>
                            <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{r.actorEmail}</td>
                            <td className="muted" style={{ fontSize: 12 }}>
                                {r.targetType ?? '—'}
                                {r.targetId && <> / {r.targetId.slice(0, 12)}…</>}
                            </td>
                            <td className="muted" style={{ fontSize: 12 }}>{formatDateTime(r.createdAt)}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}
```

- [ ] **Step 2: TenantDetail.tsx — 5번째 탭 통합**

먼저 Read 해서 기존 TABS 배열 정의 위치 + 렌더 분기 위치 확인.

```tsx
import TenantActivityTab from './tenant/TenantActivityTab';
// 기존 import 아래

type TabKey = 'overview' | 'webauthn' | 'credentials' | 'apikeys' | 'activity';

const TABS: { key: TabKey; label: string }[] = [
    { key: 'overview',    label: 'Overview' },
    { key: 'webauthn',    label: 'WebAuthn Configuration' },
    { key: 'credentials', label: 'Credentials' },
    { key: 'apikeys',     label: 'API Keys' },
    { key: 'activity',    label: 'Activity' },
];

// 렌더 분기에 추가 (마지막):
{tab === 'activity' && <TenantActivityTab tenantId={tenant.id} />}
```

- [ ] **Step 3: AuditLog.tsx — tenantId input + 테이블 column**

먼저 Read 해서 기존 filter input + table 구조 확인.

input 행에 추가:
```tsx
<input className="input" placeholder="tenantId (UUID)"
       value={tenantId} onChange={(e) => setTenantId(e.target.value)} />
```

`tenantId` state 추가:
```tsx
const [tenantId, setTenantId] = useState('');
```

`getAuditLog` 호출에 포함:
```tsx
const rows = await getAuditLog({ action, actorId, tenantId: tenantId || undefined, page, size });
```

테이블 헤더 + body 에 column:
```tsx
<th>TENANT</th>
// …
<td className="muted" style={{ fontSize: 12 }}>
    {r.tenantId ? r.tenantId.slice(0, 8) + '…' : '—'}
</td>
```

⚠️ PlatformOnlyGuard 그대로 — 이 페이지는 PLATFORM_OPERATOR 전용 유지.

- [ ] **Step 4: TS 컴파일 + 빌드**

Run: `cd admin-ui && npx tsc --noEmit -q && npm run build 2>&1 | tail -5`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/pages/tenant/TenantActivityTab.tsx \
        admin-ui/src/pages/TenantDetail.tsx \
        admin-ui/src/pages/AuditLog.tsx
git commit -m "$(cat <<'EOF'
feat(admin-ui): TenantActivityTab (5번째 탭) + AuditLog tenantId column

TenantActivityTab: getAuditLog({tenantId, size:100}) — RP_ADMIN bob 의
자기 tenant audit / alice 의 임의 tenant 둘 다 동일 endpoint. 5초 polling.

TenantDetail TABS 배열에 'Activity' 추가 (5번째). 렌더 분기.

AuditLog 페이지: tenantId input + TENANT 컬럼 (PLATFORM_OPERATOR 전용 유지).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree .claude/worktrees/activity-page. Task 11: TenantActivityTab (TenantDetail 5번째 탭) + AuditLog tenantId column. Verify (a) the tab uses getAuditLog({tenantId}) which means RP_ADMIN bob sees his own tenant (auto-scope agrees) and alice sees any tenant by passing it explicitly; (b) TenantDetail's TABS array and conditional render add Activity as the LAST tab (not inserted middle, which would break existing nav behavior); (c) AuditLog.tsx tenantId input state initializes to '' and is correctly passed as undefined when empty (not as the literal 'undefined' string); (d) PlatformOnlyGuard wrap on AuditLog.tsx is preserved. 200 words max.")
```

---

## Task 12: Manual smoke followup 문서 + 8 deferred items

**Files:**
- Create: `docs/superpowers/followups/2026-05-27-activity-page-followups.md`

- [ ] **Step 1: Followup 문서 작성**

`docs/superpowers/followups/2026-05-27-activity-page-followups.md`:

```markdown
# Activity 페이지 Phase — Followups

Phase: activity-page (2026-05-27)
Plan: [docs/superpowers/plans/2026-05-27-activity-page.md](../plans/2026-05-27-activity-page.md)
Spec: [docs/superpowers/specs/2026-05-27-activity-page-design.md](../specs/2026-05-27-activity-page-design.md)

## Manual Smoke Checklist (반드시 실행)

브랜치 머지 전 / dogfood 환경에서 다음 8 단계 모두 확인:

- [ ] **1.** alice@crosscert.com 로그인 → 사이드바에 'Activity' 메뉴 보임 → 클릭 시 페이지 로드 (3초 안에 KPI 표시)
- [ ] **2.** Activity 페이지에서 KPI 4 카드 표시 — events24h / ops24h / security24h 는 숫자, p95 응답 (ms) 은 'N/A'
- [ ] **3.** 5초 polling — alice 가 별 탭에서 tenant 생성 → 5초 이내에 Activity 페이지 feed 상단에 새 TENANT_CREATE row 추가
- [ ] **4.** 카테고리 chip — '운영' 선택 시 TENANT_CREATE/UPDATE/CREDENTIAL_REVOKE/API_KEY/SIGNING/ADMIN_LOGIN 만, '보안' 선택 시 ADMIN_LOGIN_FAILED 만, '전체' 로 복귀
- [ ] **5.** bob@demo-rp.com (RP_ADMIN) 로그인 → 사이드바에 Activity 메뉴 없음. URL 에 `/activity` 직접 입력 → `/tenants/{demo-rp-id}` 로 리다이렉트 (PlatformOnlyGuard)
- [ ] **6.** bob 의 TenantDetail 5번째 탭 'Activity' 클릭 → demo-rp 의 audit 이벤트만 표시. 5초 polling 도 동작
- [ ] **7.** alice 가 임의 tenant 의 detail Activity 탭 → 해당 tenant 의 audit 만 보임 (PLATFORM_OPERATOR 가 명시 tenantId 호출)
- [ ] **8.** alice 가 /audit 페이지 (PLATFORM_OPERATOR) → tenantId input 으로 임의 tenant 필터 가능. TENANT 컬럼에 8자 prefix 표시

## Deferred Items (후속 Phase)

이번 phase 에서 의도적으로 미루기로 결정한 것 — 별도 brainstorm/plan 으로 진행:

### 1. p95 응답 metric (Micrometer/Actuator)
- **왜 미룸**: audit_log 에 latency 칼럼 없음. Micrometer 의 `http.server.requests` percentile 을 별도 wiring 필요
- **scope**: Spring Boot Actuator + Micrometer percentiles + Activity 페이지 KPI 의 p95Ms 가 실제 값으로 표시

### 2. WebSocket / SSE 실시간 push
- **왜 미룸**: 5초 polling 으로 dogfood 충분. SSE 는 별도 endpoint + admin-ui 의 EventSource wiring 필요
- **scope**: ActivitySseController + Activity.tsx 의 EventSource subscription

### 3. 기존 row tenant_id backfill
- **왜 미룸**: 24h 윈도우라 historical NULL 무관. payload.tenantId 추출 + UPDATE 권한 필요
- **scope**: V25 migration — payload JSON 의 tenantId 키를 audit_log.tenant_id 컬럼으로 추출 (NULL 만 대상)

### 4. Audit Chain Monitor 페이지 (sparkline, 월간 PDF)
- **왜 미룸**: 별도 phase — chain verify 가시화, 월간 PDF 리포트
- **scope**: `/admin/api/audit/chain-status` daily summary + 페이지 + PDF export

### 5. 운영자 관리 UI (admin-role-separation 의 deferred)
- **왜 미룸**: admin-role-separation phase 에서 이미 deferred 로 캡처됨
- **scope**: PLATFORM_OPERATOR 가 다른 운영자/RP_ADMIN 추가/삭제/role 변경

### 6. ADMIN_LOGIN tenantId 분류 옵션
- **왜 미룸**: 현재는 ADMIN_LOGIN 의 tenantId 가 null — RP_ADMIN 의 자기 로그인이 자기 tenant audit 에 안 보임
- **scope**: AdminSecurityConfig 의 login success handler 에서 principal.getTenantId() 로 fill (RP_ADMIN 만, PLATFORM_OPERATOR 는 여전히 null)

### 7. Activity 페이지 시간 윈도우 선택 (24h/7d/30d)
- **왜 미룸**: 현재 24h 고정. 드롭다운 + ActivityService.snapshot 의 WINDOW 파라미터화 필요
- **scope**: GET /activity?windowHours= 추가 + UI 드롭다운

### 8. action 분류 i18n + 색상 토큰 분리
- **왜 미룸**: 현재 OPS_ACTIONS/SECURITY_ACTIONS 가 Service 안에 hardcoded. dogfood UI fidelity 후순위
- **scope**: action → metadata (display name, icon, color, category) registry 추출 + 백엔드 enum + i18n

---
*문서 갱신 책임: 각 followup 시작 시 brainstorm/plan 링크 추가, 완료 시 status [DONE] 표시*
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/followups/2026-05-27-activity-page-followups.md
git commit -m "$(cat <<'EOF'
docs(followups): Activity 페이지 manual smoke 8 + deferred 8

Manual smoke checklist — alice/bob 로그인부터 KPI/Top5/feed/category
chip/PlatformOnlyGuard/TenantDetail Activity 탭/AuditLog tenantId 입력
까지 8 단계.

Deferred 8: p95 Micrometer, SSE, backfill, Chain Monitor, 운영자 관리 UI,
ADMIN_LOGIN tenantId 분류, 시간 윈도우 드롭다운, action i18n + 색상 토큰.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Codex review (마지막 — 전체 phase 회고)**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review the entire phase: 12 commits on worktree .claude/worktrees/activity-page (feature/activity-page branch). This phase: (1) added audit_log.tenant_id RAW(16) column via idempotent V24, (2) extended AuditLog entity + AuditAppendRequest with tenantId at 11 sites, (3) created ActivityRepository + ActivityService + ActivityController for PLATFORM_OPERATOR cross-tenant snapshot, (4) extended AuditLogController to allow RP_ADMIN with tenant scope check, (5) added Activity page + TenantDetail Activity tab in admin-ui. Tests: ActivityControllerIT + AuditLogTenantScopingIT + RpAdminBoundaryIT step 14 update. Verify the overall design coherence — any tenant_id leak path missed? Any IT that would catch a real regression that's now not covered? Run git log --oneline -12 to see the commit list. 250 words max.")
```

---

## Task 13: 머지 main 으로 (수동, 사용자 승인 후)

**이건 task 가 아니라 phase 종료 step** — 사용자가 manual smoke 8 단계 통과 확인 후:

```bash
cd ../..  # back to main worktree (Passkey2 root)
git merge --no-ff feature/activity-page -m "Merge feature/activity-page — Activity 페이지 + audit_log.tenant_id + RP_ADMIN audit 회복"
git worktree remove .claude/worktrees/activity-page
git branch -d feature/activity-page  # optional cleanup
```

---

## Self-Review

**1. Spec coverage:**
- §1.1 DoD 9 항목 → Task 1 (V24), Task 2 (entity), Task 3 (AppendRequest + 10 + verifier), Task 4-5 (ActivityRepository/Service/Controller), Task 6 (AuditLogController), Task 9-11 (admin-ui), Task 7-8 (IT)
- §1.2 deferred 6 → Task 12 followup 문서 (8 항목 — admin-role-separation deferred 운영자 UI 포함)
- §2.1 V24 idempotent → Task 1
- §2.2 entity 9-arg → Task 2
- §2.3 AuditAppendRequest 11 사이트 → Task 3
- §2.4 append() hash 미변경 → Task 3 step 2
- §2.5 10 곳 mapping table → Task 3 step 4 (모든 sites 명시)
- §3.1 ActivityController → Task 5
- §3.2 ActivityService → Task 5
- §3.3 ActivityRepository (tuple WHERE) → Task 4
- §3.4 ActivityView → Task 4
- §3.5 AuditLogController + scope → Task 6
- §3.6 AuditLogView.tenantId → Task 6
- §4.1-4.6 admin-ui → Task 9-11
- §5.1-5.3 IT 2 → Task 7-8
- §5.4 기존 IT 갱신 → Task 8 step 2 (RpAdminBoundaryIT)
- §5.7 manual smoke → Task 12
- §6.1 위험 → 각 task 의 codex review 가 회귀 감지 채널
- §6.2 followup 8 → Task 12

**2. Placeholder scan:** Plan 전체 검토 — Task 3 step 4 의 헬퍼 위치 ("Read 후 정확한 line 위치에서 한 줄 추가") 는 plan 작성 시점에 실제 라인이 알려져 있고 spec 의 §2.5 테이블 매핑이 완전하므로 placeholder 가 아닌 명시. Task 7-8 의 helper 메서드 ("기존 패턴 그대로") 는 RpAdminBoundaryIT/AdminFlowIT Read 후 inline 가능한 구체 행동. 패스.

**3. Type consistency:**
- `AuditAppendRequest`: 7-arg (UUID actorId, String actorEmail, String action, String targetType, String targetId, UUID tenantId, Map payload) — Task 3 step 1 정의 + step 4 의 10 곳 호출 일치
- `AuditLog` constructor: 9-arg (prevHash, hash, actorId, actorEmail, action, targetType, targetId, tenantId, payload, createdAtArg) — Task 2 정의 + Task 3 step 2 호출 일치 (AuditLogService.append 안)
- `ActivityView.Kpi(long, long, long, Long)` — Task 4 정의, Task 5 의 `new Kpi(events24h, ops24h, security24h, null)` 일치, Task 9 의 TS `number | null` 일치
- `feedRaw / feedFilteredRaw` 명명 — Task 4 정의, Task 5 service 의 `activity.feed` (default 메서드) 호출 일치, spec §6.1 risk table 의 명명 일치 (검토된)
- `getActivity / getAuditLog` TS helper — Task 9 정의, Task 10 (Activity.tsx) + Task 11 (TenantActivityTab/AuditLog) 호출 일치

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-27-activity-page.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
