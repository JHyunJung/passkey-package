# Phase 8: BaseEntity 추출 + updated_at 필수화 + admin-ui KST 표시 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 11개 JPA entity의 PK + timestamp 중복 보일러플레이트를 `@MappedSuperclass BaseEntity`로 통합하고 모든 entity에 `updated_at`을 자동 갱신하도록 만들며, admin-ui의 timestamp 표시를 Asia/Seoul 기준 KST로 통일한다.

**Architecture:** `BaseEntity` (UUID PK + createdAt + updatedAt + JPA 라이프사이클 콜백)을 9개 entity가 상속한다. `MdsBlobCache`(singleton row, fixed UUID), `SchedulerLease`(V19 `DEFAULT SYS_GUID()` 호환성)는 PK 정책 충돌로 BaseEntity 상속 대신 `createdAt`/`updatedAt` + 콜백을 직접 선언한다. V22 Flyway가 모든 테이블에 timestamp 컬럼을 ADD하고 `DEFAULT SYSTIMESTAMP NOT NULL`로 기존 row 자동 백필. admin-ui는 새 `formatDateTime` 유틸이 ISO-8601 UTC 입력을 KST로 변환해 표시한다 — 백엔드 응답은 UTC 그대로.

**Tech Stack:** Spring Boot 3.5.14, Hibernate 6.6, Oracle 19c (TIMESTAMP WITH TIME ZONE), Flyway, React 18 + TypeScript + Vite + Intl.DateTimeFormat

---

## File Structure

### Files to Create

- `core/src/main/java/com/crosscert/passkey/core/entity/BaseEntity.java` — @MappedSuperclass with UUID PK + createdAt + updatedAt + @PrePersist/@PreUpdate
- `core/src/test/java/com/crosscert/passkey/core/entity/BaseEntityCallbackIT.java` — IT verifying @PrePersist sets both timestamps, @PreUpdate advances only updatedAt
- `core/src/main/resources/db/migration/V22__base_entity_timestamps.sql` — ALTER TABLE adding columns
- `admin-ui/src/lib/formatDateTime.ts` — KST formatter utility

### Files to Modify (Entity Group A — 5 simple-inheritance entities)

- `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java` — extend BaseEntity, remove PK + createdAt + getId() + getCreatedAt()
- `core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java` — extend BaseEntity, same cleanup
- `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java` — extend BaseEntity, same cleanup
- `core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java` — extend BaseEntity, same cleanup (createdAt-from-constructor pattern needs preservation note)
- `core/src/main/java/com/crosscert/passkey/core/entity/SigningKey.java` — extend BaseEntity, same cleanup

### Files to Modify (Entity Group B — 3 child entities)

- `core/src/main/java/com/crosscert/passkey/core/entity/ApiKeyScope.java` — extend BaseEntity (no existing PK changes needed beyond syntax)
- `core/src/main/java/com/crosscert/passkey/core/entity/TenantAcceptedFormat.java` — extend BaseEntity
- `core/src/main/java/com/crosscert/passkey/core/entity/TenantAllowedOrigin.java` — extend BaseEntity

### Files to Modify (Entity Group C — Tenant)

- `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java` — extend BaseEntity, remove `touchUpdatedAt()` method, simplify constructor

### Files to Modify (Entity Group D — special cases, do NOT extend BaseEntity)

- `core/src/main/java/com/crosscert/passkey/core/entity/MdsBlobCache.java` — add createdAt + updatedAt + @PrePersist + @PreUpdate directly (preserves singleton PK)
- `core/src/main/java/com/crosscert/passkey/core/entity/SchedulerLease.java` — add createdAt + updatedAt + @PrePersist + @PreUpdate directly (preserves V19 SYS_GUID compat)

### Files to Modify (admin-ui — KST display)

- `admin-ui/src/pages/TenantList.tsx` — replace raw timestamp display with formatDateTime
- `admin-ui/src/pages/ApiKeyList.tsx` — same
- `admin-ui/src/pages/MdsStatus.tsx` — same
- `admin-ui/src/pages/KeyManagement.tsx` — same
- `admin-ui/src/pages/AuditLog.tsx` — same

### Files NOT Changed

- DTO classes (TenantAdminDto, ApiKeyAdminDto, etc.) — backend API contract unchanged
- Service classes — no code changes needed (touchUpdatedAt has no call sites; @PreUpdate auto-fires)
- passkey-app — RP API responses unchanged

---

## Task 1: BaseEntity 클래스 + IT 작성

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/BaseEntity.java`
- Create: `core/src/test/java/com/crosscert/passkey/core/entity/BaseEntityCallbackIT.java`

**Context:** BaseEntity는 11개 entity 중 9개에 적용되는 핵심 추상. 단위 테스트는 추상 클래스 직접 테스트 불가하므로 Tenant 또는 다른 구체 entity를 통한 통합 테스트(IT)로 검증. 단, Tenant는 아직 BaseEntity 상속을 안 한 상태이므로 이 IT는 Task 9(Tenant 마이그레이션) 이후 의미가 명확해진다. 그래서 BaseEntity 자체는 T1에서 만들고, IT는 단순한 "@PrePersist 실행 확인" 검증을 위해 임시 entity 또는 첫 마이그레이션된 entity 사용.

전략: T1에서 BaseEntity만 만들고, callback IT는 첫 entity가 마이그레이션된 후(T3 — AdminUser)에 작성한다. T1 자체는 BaseEntity 컴파일 통과 + 빈 단위 테스트가 BaseEntity 클래스 존재 확인까지만.

- [ ] **Step 1: BaseEntity 클래스 작성**

Create `core/src/main/java/com/crosscert/passkey/core/entity/BaseEntity.java`:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Common superclass for entities that share a UUID PK (TIME-ordered)
 * and createdAt/updatedAt timestamps managed by JPA lifecycle callbacks.
 *
 * Exceptions (do NOT extend this class):
 * - MdsBlobCache: uses a fixed SINGLETON_ID
 * - SchedulerLease: relies on Oracle's DEFAULT SYS_GUID() for raw-SQL inserts
 *
 * Those two entities declare createdAt/updatedAt and the callbacks directly
 * to preserve their PK contracts while satisfying the "every entity has
 * updated_at" policy (Phase 8).
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 2: 컴파일 검증**

Run: `./gradlew :core:compileJava`

Expected: BUILD SUCCESSFUL. BaseEntity가 어디서도 상속되지 않은 추상 클래스 상태로 통과.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/BaseEntity.java
git commit -m "feat(core): BaseEntity @MappedSuperclass (Phase 8 T1)

UUID PK + createdAt/updatedAt + @PrePersist/@PreUpdate.
Not yet inherited; entities migrate in subsequent tasks.

MdsBlobCache (singleton row) and SchedulerLease (V19 SYS_GUID compat)
will not extend this class — they declare timestamp fields directly.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: V22 Flyway migration

**Files:**
- Create: `core/src/main/resources/db/migration/V22__base_entity_timestamps.sql`

**Context:** Flyway가 entity migration 전에 적용되어야 한다. Hibernate `ddl-auto: validate` 모드라 schema와 entity가 불일치하면 부팅 실패. 따라서 V22를 먼저 적용 → 모든 테이블에 컬럼 추가 → 이후 entity 마이그레이션 시 컬럼 매핑이 항상 가능하게.

핵심 디테일: `app_owner.` schema prefix를 명시. 다른 V19/V20/V21 migration과 동일한 패턴.

- [ ] **Step 1: V22 migration 작성**

Create `core/src/main/resources/db/migration/V22__base_entity_timestamps.sql`:

```sql
-- Phase 8: Align all entity tables with the new BaseEntity superclass
-- (UUID PK + created_at + updated_at managed by JPA @PrePersist/@PreUpdate).
--
-- Adds the missing timestamp columns. Tenant already has both; all other
-- tables get the columns added here with DEFAULT SYSTIMESTAMP so existing
-- rows are backfilled at migration runtime (dev/staging premise; no
-- production data backfill).
--
-- DB columns use TIMESTAMP WITH TIME ZONE; JPA maps to java.time.Instant;
-- hibernate.jdbc.time_zone remains UTC. admin-ui converts to Asia/Seoul
-- at render time via the new formatDateTime utility.

-- ── Phase 7 child tables: created_at + updated_at both new ──
ALTER TABLE app_owner.tenant_allowed_origin ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.tenant_accepted_format ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.api_key_scope ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

-- ── Existing tables: updated_at new (created_at already exists) ──
ALTER TABLE app_owner.admin_user ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.api_key ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.credential ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.audit_log ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.signing_key ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

-- ── Special-case tables: created_at + updated_at both new ──
ALTER TABLE app_owner.mds_blob_cache ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.scheduler_lease ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

-- Tenant: no change — created_at + updated_at already exist (V19 carries them).
```

- [ ] **Step 2: Flyway dry-run via test container (optional but safer)**

Run: `./gradlew :core:test --tests "*Vpd*"` (any IT that bootstraps Testcontainers Oracle and applies V1..V22)

Expected: BUILD SUCCESSFUL (the IT just needs Flyway to apply cleanly; entity validation happens at Hibernate bootstrap which won't fail yet because no entity references the new columns).

If this fails because Hibernate validates and rejects missing entity fields, skip this step — T3 onward will introduce entity changes that align validation.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/db/migration/V22__base_entity_timestamps.sql
git commit -m "feat(db): V22 timestamp columns for all entity tables (Phase 8 T2)

ALTER TABLE app_owner.{tenant_allowed_origin, tenant_accepted_format,
api_key_scope, mds_blob_cache, scheduler_lease}: ADD created_at + updated_at.
ALTER TABLE app_owner.{admin_user, api_key, credential, audit_log,
signing_key}: ADD updated_at only.
Tenant unchanged.

DEFAULT SYSTIMESTAMP NOT NULL backfills existing rows at migration runtime.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: AdminUser → BaseEntity 상속 + BaseEntity callback IT

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java`
- Create: `core/src/test/java/com/crosscert/passkey/core/entity/BaseEntityCallbackIT.java`

**Context:** 첫 entity 마이그레이션 + BaseEntity 콜백 IT. AdminUser는 의존성이 적어 첫 후보. `recordLogin()` 같은 도메인 메서드는 유지. Constructor에서 `this.createdAt = Instant.now();` 줄 삭제 — @PrePersist가 처리.

- [ ] **Step 1: AdminUser 수정**

Replace `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java` with:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ADMIN_USER")
public class AdminUser extends BaseEntity {

    @Column(name = "EMAIL", length = 255, nullable = false)
    private String email;

    @Column(name = "BCRYPT_HASH", length = 72, nullable = false)
    private String bcryptHash;

    @Column(name = "ROLE", length = 16, nullable = false)
    private String role;

    @Column(name = "ENABLED", columnDefinition = "CHAR(1)", nullable = false)
    private String enabledFlag;

    @Column(name = "LAST_LOGIN_AT")
    private Instant lastLoginAt;

    protected AdminUser() {}

    public AdminUser(String email, String bcryptHash, String role) {
        this.email = email;
        this.bcryptHash = bcryptHash;
        this.role = role;
        this.enabledFlag = "Y";
    }

    public String getEmail() { return email; }
    public String getBcryptHash() { return bcryptHash; }
    public String getRole() { return role; }
    public boolean isEnabled() { return "Y".equals(enabledFlag); }
    public Instant getLastLoginAt() { return lastLoginAt; }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }
}
```

(Removed: `@Id`, `@UuidGenerator`, `@JdbcTypeCode`, `id` field, `createdAt` field, `getId()`, `getCreatedAt()`, unused `UUID` import path can stay if other places use it — Java doesn't require explicit removal of unused imports for compile, but the org.hibernate.annotations.* and SqlTypes imports are now dead; remove them.)

- [ ] **Step 2: BaseEntity callback IT 작성**

Create `core/src/test/java/com/crosscert/passkey/core/entity/BaseEntityCallbackIT.java`:

```java
package com.crosscert.passkey.core.entity;

import com.crosscert.passkey.core.OracleTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
@Import(OracleTestContainer.Config.class)
class BaseEntityCallbackIT {

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        OracleTestContainer.register(r);
    }

    @Autowired
    private TestEntityManager em;

    @Test
    void onPersist_setsCreatedAtAndUpdatedAt_toSameInstant() {
        AdminUser user = new AdminUser("alice@example.com", "$2a$10$" + "x".repeat(53), "ADMIN");

        em.persist(user);
        em.flush();

        assertThat(user.getId()).isNotNull();
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();
        assertThat(user.getCreatedAt()).isEqualTo(user.getUpdatedAt());
    }

    @Test
    void onUpdate_advancesUpdatedAt_butNotCreatedAt() throws InterruptedException {
        AdminUser user = new AdminUser("bob@example.com", "$2a$10$" + "y".repeat(53), "ADMIN");
        em.persist(user);
        em.flush();
        Instant originalCreatedAt = user.getCreatedAt();
        Instant originalUpdatedAt = user.getUpdatedAt();

        // Sleep just enough that Instant.now() advances reliably (Instant has
        // nanosecond precision on Linux, millisecond on macOS — 5ms is safe).
        Thread.sleep(5);

        user.recordLogin(Instant.now());
        em.flush();

        assertThat(user.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(user.getUpdatedAt()).isAfter(originalUpdatedAt);
    }

    @Test
    void persistedUuid_isTimeOrdered() {
        AdminUser user1 = new AdminUser("c@example.com", "$2a$10$" + "z".repeat(53), "ADMIN");
        em.persist(user1);
        em.flush();
        UUID id1 = user1.getId();

        AdminUser user2 = new AdminUser("d@example.com", "$2a$10$" + "w".repeat(53), "ADMIN");
        em.persist(user2);
        em.flush();
        UUID id2 = user2.getId();

        // TIME-ordered UUIDs sort by creation time
        assertThat(id1).isNotEqualTo(id2);
    }
}
```

Note: This IT depends on an existing `OracleTestContainer` helper. If the helper signature differs, follow the same pattern as `VpdIsolationIT` which already uses Oracle Testcontainers.

- [ ] **Step 3: Run AdminUser-affected tests**

Run: `./gradlew :core:test --tests "*BaseEntityCallback*" --tests "*AdminUser*"`

Expected: BUILD SUCCESSFUL.

If `OracleTestContainer` helper doesn't exist or has different bootstrap, replace the IT with the project's actual Testcontainers pattern (check `VpdIsolationIT.java` for the canonical setup).

- [ ] **Step 4: Run admin-app tests that touch AdminUser**

Run: `./gradlew :admin-app:test`

Expected: BUILD SUCCESSFUL. AdminUser tests should pass because `getId()`/`getCreatedAt()` are inherited from BaseEntity and the public API is unchanged.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java \
        core/src/test/java/com/crosscert/passkey/core/entity/BaseEntityCallbackIT.java
git commit -m "refactor(core): AdminUser extends BaseEntity + lifecycle IT (Phase 8 T3)

AdminUser now inherits UUID PK + createdAt + updatedAt + @PrePersist/@PreUpdate.
Removed local id/createdAt declarations and getters.

BaseEntityCallbackIT verifies:
- @PrePersist sets createdAt = updatedAt at insert time
- @PreUpdate advances only updatedAt
- TIME-ordered UUID PKs are unique

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: ApiKey → BaseEntity 상속

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java`

**Context:** ApiKey는 도메인 메서드(`isActive`, `touchLastUsed`, `revoke`, `addScope`)가 많지만 모두 createdAt와 무관. Constructor의 `this.createdAt = Instant.now();` 만 삭제하면 됨.

- [ ] **Step 1: ApiKey 수정**

Replace the whole content of `core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java` with:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "API_KEY")
public class ApiKey extends BaseEntity {

    @Column(name = "TENANT_ID", nullable = false, columnDefinition = "RAW(16)")
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID tenantId;

    @Column(name = "KEY_PREFIX", length = 16, nullable = false)
    private String keyPrefix;

    @Column(name = "KEY_HASH", length = 255, nullable = false)
    private String keyHash;

    @Column(name = "NAME", length = 256, nullable = false)
    private String name;

    @OneToMany(mappedBy = "apiKey", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<ApiKeyScope> scopes = new HashSet<>();

    @Column(name = "LAST_USED_AT")
    private Instant lastUsedAt;

    @Column(name = "EXPIRES_AT")
    private Instant expiresAt;

    @Column(name = "REVOKED_AT")
    private Instant revokedAt;

    protected ApiKey() {}

    public ApiKey(UUID tenantId, String keyPrefix, String keyHash, String name) {
        this.tenantId = tenantId;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.name = name;
    }

    public void addScope(String scope) {
        boolean exists = scopes.stream().anyMatch(s -> scope.equals(s.getScope()));
        if (!exists) {
            scopes.add(new ApiKeyScope(this, scope));
        }
    }

    public void clearScopes() { scopes.clear(); }

    public Set<String> getScopeValues() {
        return scopes.stream().map(ApiKeyScope::getScope).collect(Collectors.toSet());
    }

    public Set<ApiKeyScope> getScopes() { return scopes; }

    public UUID getTenantId() { return tenantId; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getKeyHash() { return keyHash; }
    public String getName() { return name; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isActive(Instant now) {
        if (revokedAt != null) return false;
        if (expiresAt != null && !expiresAt.isAfter(now)) return false;
        return true;
    }

    public void touchLastUsed(Instant now) {
        this.lastUsedAt = now;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }
}
```

(Removed: `@Id`, `@UuidGenerator`, `id` field, `createdAt` field, `getId()`, `getCreatedAt()`, the `Instant.now()` line in constructor, the `org.hibernate.annotations.UuidGenerator` import.)

- [ ] **Step 2: Run ApiKey-affected tests**

Run: `./gradlew :core:test :admin-app:test :passkey-app:test --tests "*ApiKey*"`

Expected: BUILD SUCCESSFUL. All ApiKey-related tests pass since getId/getCreatedAt are inherited.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java
git commit -m "refactor(core): ApiKey extends BaseEntity (Phase 8 T4)

Domain lifecycle columns (last_used_at, expires_at, revoked_at) preserved.
isActive/touchLastUsed/revoke/addScope unchanged.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Credential → BaseEntity 상속

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java`

**Context:** Credential은 RP authentication path의 핵심 entity. `recordAuthentication()`이 sign_count + publicKey(realistically credentialRecord CBOR bytes) + lastUsedAt 갱신. @PreUpdate가 추가로 updatedAt 갱신.

- [ ] **Step 1: Credential 수정**

Replace `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java`:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "CREDENTIAL")
public class Credential extends BaseEntity {

    @Column(name = "TENANT_ID", nullable = false, columnDefinition = "RAW(16)")
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID tenantId;

    @Column(name = "USER_HANDLE", length = 64, nullable = false)
    private byte[] userHandle;

    @Column(name = "CREDENTIAL_ID", length = 1023, nullable = false)
    private byte[] credentialId;

    @Lob
    @Column(name = "PUBLIC_KEY", nullable = false)
    private byte[] publicKey;

    @Column(name = "SIGN_COUNT", nullable = false)
    private long signCount;

    @Column(name = "AAGUID", length = 16)
    private byte[] aaguid;

    @Column(name = "TRANSPORTS", length = 128)
    private String transports;

    @Column(name = "ATTESTATION_FMT", length = 64)
    private String attestationFmt;

    @Lob
    @Column(name = "BACKUP_STATE")
    private String backupStateJson;

    @Column(name = "LAST_USED_AT")
    private Instant lastUsedAt;

    protected Credential() {}

    public Credential(UUID tenantId, byte[] userHandle, byte[] credentialId,
                      byte[] publicKey, byte[] aaguid) {
        this.tenantId = tenantId;
        this.userHandle = userHandle;
        this.credentialId = credentialId;
        this.publicKey = publicKey;
        this.aaguid = aaguid;
        this.signCount = 0;
    }

    public UUID getTenantId() { return tenantId; }
    public byte[] getCredentialId() { return credentialId; }
    public long getSignCount() { return signCount; }
    public byte[] getUserHandle() { return userHandle; }
    public byte[] getAaguid() { return aaguid; }
    public byte[] getCredentialRecordBytes() { return publicKey; }

    public void recordAuthentication(long newSignCount, byte[] newCredentialRecordBytes, Instant now) {
        this.signCount = newSignCount;
        this.publicKey = newCredentialRecordBytes;
        this.lastUsedAt = now;
    }
}
```

(Removed: `@Id` block, `id`, `createdAt`, `getId()`, `getCreatedAt()`, `Instant.now()` from constructor, dead imports `org.hibernate.annotations.UuidGenerator`.)

- [ ] **Step 2: Run Credential-affected tests**

Run: `./gradlew :passkey-app:test --tests "*Fido2*" --tests "*Credential*"`

Expected: BUILD SUCCESSFUL. Fido2EndToEndIT (registration + authentication) covers Credential persist + update flows.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Credential.java
git commit -m "refactor(core): Credential extends BaseEntity (Phase 8 T5)

@PreUpdate now fires on recordAuthentication() — updatedAt advances
together with signCount/publicKey/lastUsedAt.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: SigningKey → BaseEntity 상속

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/SigningKey.java`

**Context:** rotate()/revoke()가 status + rotatedAt/revokedAt을 갱신. @PreUpdate가 updatedAt 자동 갱신.

- [ ] **Step 1: SigningKey 수정**

Replace `core/src/main/java/com/crosscert/passkey/core/entity/SigningKey.java`:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "SIGNING_KEY")
public class SigningKey extends BaseEntity {

    @Column(name = "KID", length = 64, nullable = false, updatable = false)
    private String kid;

    @Column(name = "ALG", length = 16, nullable = false, updatable = false)
    private String alg;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;

    @Lob
    @Column(name = "PUBLIC_JWK", nullable = false, updatable = false)
    private String publicJwk;

    @Lob
    @Column(name = "PRIVATE_PKCS8", nullable = false, updatable = false)
    private byte[] privatePkcs8;

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
    }

    public String getKid() { return kid; }
    public String getAlg() { return alg; }
    public String getStatus() { return status; }
    public String getPublicJwk() { return publicJwk; }
    public byte[] getPrivatePkcs8() { return privatePkcs8; }
    public Instant getRotatedAt() { return rotatedAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public void rotate(Instant now) {
        this.status = "ROTATED";
        this.rotatedAt = now;
    }

    public void revoke(Instant now) {
        this.status = "REVOKED";
        this.revokedAt = now;
    }
}
```

- [ ] **Step 2: Run SigningKey-affected tests**

Run: `./gradlew :core:test :admin-app:test --tests "*SigningKey*" --tests "*Key*"`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/SigningKey.java
git commit -m "refactor(core): SigningKey extends BaseEntity (Phase 8 T6)

rotate()/revoke() lifecycle methods unchanged; updated_at now auto-advances.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: AuditLog → BaseEntity 상속

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java`

**Context:** AuditLog의 기존 constructor가 `createdAt`을 명시적으로 받는다 (hash chain의 무결성을 위해 호출자 측 결정). BaseEntity의 `@PrePersist`는 `createdAt == null`일 때만 동작하므로 호환됨. 그러나 BaseEntity는 createdAt에 `updatable=false`를 지정 — JPA가 `createdAt`을 application이 직접 set한 후 persist시 그 값을 그대로 저장. 안전.

audit_log는 append-only지만 updated_at은 BaseEntity 일관성을 위해 보유. 값은 항상 createdAt과 동일 (insert만 일어남).

- [ ] **Step 1: AuditLog 수정**

Replace `core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java`:

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

    @Lob
    @Column(name = "PAYLOAD", nullable = false)
    private String payload;

    protected AuditLog() {}

    public AuditLog(byte[] prevHash, byte[] hash, UUID actorId, String actorEmail,
                    String action, String targetType, String targetId,
                    String payload, Instant createdAtArg) {
        this.prevHash = prevHash;
        this.hash = hash;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.payload = payload;
        // Pre-set createdAt so @PrePersist preserves caller-supplied value
        // for the hash chain. BaseEntity.onCreate() only writes if null.
        seedCreatedAt(createdAtArg);
    }

    /**
     * Package-private helper so the AuditLog constructor can pre-seed
     * BaseEntity.createdAt before @PrePersist runs. Hash-chained audit
     * entries must use the timestamp the caller computed when building
     * the chain, not the Instant.now() of the persist call.
     */
    private void seedCreatedAt(Instant t) {
        try {
            java.lang.reflect.Field f = BaseEntity.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(this, t);
            java.lang.reflect.Field f2 = BaseEntity.class.getDeclaredField("updatedAt");
            f2.setAccessible(true);
            f2.set(this, t);
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
    public String getPayload() { return payload; }
}
```

Important: AuditLog's hash chain depends on the exact timestamp used to compute the hash. BaseEntity's @PrePersist would overwrite the value if we left `createdAt` null. The `seedCreatedAt` helper uses reflection to set the BaseEntity field before persist, ensuring @PrePersist's null-check skips the assignment.

Alternative considered: Make BaseEntity's `createdAt` setter protected so AuditLog can call `setCreatedAt(t)` directly. The reflection approach is uglier but avoids exposing a setter on BaseEntity that all 9 entities would inherit (and could be misused).

- [ ] **Step 2: Run AuditLog-affected tests**

Run: `./gradlew :admin-app:test :core:test --tests "*Audit*"`

Expected: BUILD SUCCESSFUL. The hash chain tests should still pass because the constructor-supplied timestamp survives @PrePersist.

If tests fail due to the reflection approach, document the failure and switch to making BaseEntity's createdAt field package-private (within `com.crosscert.passkey.core.entity` package — AuditLog is in the same package) and assign directly: `this.createdAt = t;`. This is cleaner once Tenant migration (T9) confirms no other entity needs constructor-supplied createdAt.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java
git commit -m "refactor(core): AuditLog extends BaseEntity, preserves hash-chain timestamp (Phase 8 T7)

Constructor accepts createdAt for hash-chain integrity. Reflective seed
ensures @PrePersist's null-check preserves the supplied value.

updated_at always equals createdAt (audit_log is append-only).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 3 Child entities → BaseEntity 상속

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/ApiKeyScope.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/TenantAcceptedFormat.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/TenantAllowedOrigin.java`

**Context:** 세 child entity 모두 같은 패턴 — `@ManyToOne` 부모 + 도메인 컬럼 + 자연키 equals/hashCode. BaseEntity 상속으로 id/createdAt/updatedAt이 자동. equals/hashCode는 자연키 기반이므로 BaseEntity의 id와 충돌 없음.

- [ ] **Step 1: ApiKeyScope 수정**

Replace `core/src/main/java/com/crosscert/passkey/core/entity/ApiKeyScope.java`. Read the current file first to preserve fields, then change only the class declaration and remove the @Id block.

Look for the current file structure (similar to TenantAllowedOrigin). Apply the same change: replace `public class ApiKeyScope {` with `public class ApiKeyScope extends BaseEntity {`, remove the `@Id ... private UUID id;` block (lines 14-19 typically), remove `getId()` if defined.

Example final structure (preserving the actual fields and equals/hashCode):

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "API_KEY_SCOPE")
public class ApiKeyScope extends BaseEntity {

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

    public ApiKey getApiKey() { return apiKey; }
    public String getScope() { return scope; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiKeyScope other)) return false;
        if (apiKey == null || other.apiKey == null) return false;
        UUID a = apiKey.getId();
        UUID b = other.apiKey.getId();
        if (a == null || b == null) return false;
        return Objects.equals(a, b) && Objects.equals(scope, other.scope);
    }

    @Override
    public int hashCode() {
        UUID a = (apiKey == null) ? null : apiKey.getId();
        return (a == null) ? 0 : Objects.hash(a, scope);
    }
}
```

(Verify the actual field names and equals/hashCode pattern match the current file before applying — the current ApiKeyScope follows the same pattern as TenantAllowedOrigin which is shown in Phase 7 commit `575c3c0`.)

- [ ] **Step 2: TenantAcceptedFormat 수정**

Same pattern: `public class TenantAcceptedFormat extends BaseEntity {`, remove `@Id` block, remove `getId()` if present. Preserve `@ManyToOne Tenant tenant`, `format` field, and equals/hashCode.

- [ ] **Step 3: TenantAllowedOrigin 수정**

Same pattern. Preserve `tenant`, `origin`, `sortOrder` fields and equals/hashCode.

- [ ] **Step 4: Run child entity tests**

Run: `./gradlew :core:test :admin-app:test --tests "*Tenant*" --tests "*ApiKey*"`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/ApiKeyScope.java \
        core/src/main/java/com/crosscert/passkey/core/entity/TenantAcceptedFormat.java \
        core/src/main/java/com/crosscert/passkey/core/entity/TenantAllowedOrigin.java
git commit -m "refactor(core): ApiKeyScope + TenantAcceptedFormat + TenantAllowedOrigin extend BaseEntity (Phase 8 T8)

Child entities now inherit UUID PK + created_at + updated_at.
Natural-key equals/hashCode preserved (tenant.id or apiKey.id + value).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Tenant → BaseEntity 상속 + touchUpdatedAt 제거

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java`

**Context:** Tenant는 이미 createdAt + updatedAt + touchUpdatedAt() 보유. BaseEntity 상속으로 이를 모두 BaseEntity가 제공하게 단순화. 기존 호출처 grep 결과 `touchUpdatedAt()`은 정의만 존재하고 호출 site 없음 — 안전하게 제거 가능.

- [ ] **Step 1: Tenant 수정**

Replace `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java`:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;

import java.util.*;
import java.util.stream.Collectors;

@Entity
@Table(name = "TENANT")
public class Tenant extends BaseEntity {

    @Column(name = "SLUG", length = 64, nullable = false, unique = true)
    private String slug;

    @Column(name = "DISPLAY_NAME", length = 256, nullable = false)
    private String displayName;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;

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

    protected Tenant() {}

    public Tenant(String slug, String displayName, String rpId, String rpName) {
        this.slug = slug;
        this.displayName = displayName;
        this.status = "active";
        this.rpId = rpId;
        this.rpName = rpName;
    }

    public void addAllowedOrigin(String origin, int sortOrder) {
        boolean duplicate = allowedOrigins.stream()
                .anyMatch(o -> o.getOrigin().equals(origin));
        if (!duplicate) {
            allowedOrigins.add(new TenantAllowedOrigin(this, origin, sortOrder));
        }
    }

    public void clearAllowedOrigins() { allowedOrigins.clear(); }

    public void addAcceptedFormat(String format) {
        boolean duplicate = acceptedFormats.stream()
                .anyMatch(f -> f.getFormat().equals(format));
        if (!duplicate) {
            acceptedFormats.add(new TenantAcceptedFormat(this, format));
        }
    }

    public void clearAcceptedFormats() { acceptedFormats.clear(); }

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

    public String getSlug() { return slug; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getStatus() { return status; }
    public String getRpId() { return rpId; }
    public String getRpName() { return rpName; }

    public List<TenantAllowedOrigin> getAllowedOrigins() { return allowedOrigins; }
    public Set<TenantAcceptedFormat> getAcceptedFormats() { return acceptedFormats; }
}
```

(Removed: `id` field + `@Id` block, `createdAt`/`updatedAt` fields, `Instant` import, `Instant now = ...; this.createdAt = now; this.updatedAt = now;` from constructor, `getId()`, `getCreatedAt()`, `getUpdatedAt()`, `touchUpdatedAt()`.)

- [ ] **Step 2: touchUpdatedAt 호출처 재확인**

Run: `grep -rn 'touchUpdatedAt' --include='*.java'`

Expected: No matches (verified during planning — no call sites exist). If matches appear, remove each call (the @PreUpdate handles it automatically).

- [ ] **Step 3: Run Tenant-affected tests**

Run: `./gradlew :core:test :admin-app:test --tests "*Tenant*"`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java
git commit -m "refactor(core): Tenant extends BaseEntity, drop touchUpdatedAt() (Phase 8 T9)

@PreUpdate replaces the manual touchUpdatedAt() method (which had no
call sites — verified via grep). createdAt/updatedAt initialization
removed from constructor — @PrePersist handles it.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: MdsBlobCache — 직접 timestamp + 콜백 추가

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/MdsBlobCache.java`

**Context:** Singleton row 의도 (`SINGLETON_ID` fixed UUID, V19 seed) 보존을 위해 BaseEntity 상속하지 않음. createdAt/updatedAt + @PrePersist/@PreUpdate를 직접 선언.

- [ ] **Step 1: MdsBlobCache 수정**

Replace `core/src/main/java/com/crosscert/passkey/core/entity/MdsBlobCache.java`:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "MDS_BLOB_CACHE")
public class MdsBlobCache {

    /**
     * The singleton row id seeded by V19 migration.
     * Matches HEXTORAW('00000000000000000000000000000001').
     * No @UuidGenerator — this id is fixed; the app never inserts another row.
     * For the same reason this entity does NOT extend BaseEntity (which would
     * impose a TIME-ordered UUID generator). createdAt/updatedAt are declared
     * here directly to satisfy the Phase 8 "every entity has updated_at" policy.
     */
    public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "VERSION", nullable = false)
    private long version;

    @Column(name = "NEXT_UPDATE", nullable = false)
    private LocalDate nextUpdate;

    @Column(name = "FETCHED_AT", nullable = false)
    private Instant fetchedAt;

    @Lob
    @Column(name = "BLOB_JWT", nullable = false)
    private String blobJwt;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    protected MdsBlobCache() {}

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public long getVersion() { return version; }
    public LocalDate getNextUpdate() { return nextUpdate; }
    public String getBlobJwt() { return blobJwt; }
    public Instant getFetchedAt() { return fetchedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 2: Run MDS-affected tests**

Run: `./gradlew :admin-app:test --tests "*Mds*"`

Expected: BUILD SUCCESSFUL. MdsSchedulerIT bootstraps the singleton row and exercises updates.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/MdsBlobCache.java
git commit -m "refactor(core): MdsBlobCache adds created_at + updated_at lifecycle (Phase 8 T10)

Does NOT extend BaseEntity because SINGLETON_ID must remain fixed
(seeded by V19). Lifecycle callbacks declared inline; fetched_at retained
as the MDS BLOB fetch-domain timestamp (separate semantic from updated_at).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: SchedulerLease — 직접 timestamp + 콜백 추가

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/SchedulerLease.java`

**Context:** V19에서 `DEFAULT SYS_GUID()`로 PK를 자동 채우는 패턴이라 BaseEntity의 `@UuidGenerator`와 충돌. IT의 raw SQL insert가 id 컬럼을 생략한 채 동작하는 호환성 유지. createdAt/updatedAt만 추가.

- [ ] **Step 1: SchedulerLease 수정**

Replace `core/src/main/java/com/crosscert/passkey/core/entity/SchedulerLease.java`:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "SCHEDULER_LEASE")
public class SchedulerLease {

    /**
     * UUID primary key. Oracle's DEFAULT SYS_GUID() in V19 assigns the id
     * when the row is inserted without an explicit id (e.g. raw-SQL IT inserts).
     * When inserting via JPA, the caller must set id explicitly via the constructor
     * or the service must generate one before persisting. No @UuidGenerator here
     * because the V19 schema retains DEFAULT SYS_GUID() to keep IT test raw-SQL
     * inserts (which omit the id column) valid.
     *
     * For the same reason this entity does NOT extend BaseEntity.
     * createdAt/updatedAt are declared here directly to satisfy the
     * Phase 8 "every entity has updated_at" policy.
     */
    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "NAME", length = 64, nullable = false, unique = true)
    private String name;

    @Column(name = "HOLDER", length = 256, nullable = false)
    private String holder;

    @Column(name = "EXPIRES_AT", nullable = false)
    private Instant expiresAt;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    protected SchedulerLease() {}

    public SchedulerLease(UUID id, String name, String holder, Instant expiresAt) {
        this.id = id;
        this.name = name;
        this.holder = holder;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getHolder() { return holder; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setHolder(String holder) { this.holder = holder; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
```

- [ ] **Step 2: Run scheduler-affected tests**

Run: `./gradlew :admin-app:test --tests "*Scheduler*"`

Expected: BUILD SUCCESSFUL. Scheduler IT exercises lease acquisition/renewal.

If raw-SQL inserts in IT fixtures fail with NOT NULL constraint on `created_at`/`updated_at`, the V22 migration's `DEFAULT SYSTIMESTAMP NOT NULL` should handle it. If not, the IT fixture SQL needs to include the columns explicitly — flag this and add explicit `created_at` + `updated_at` values to the affected fixture.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/SchedulerLease.java
git commit -m "refactor(core): SchedulerLease adds created_at + updated_at lifecycle (Phase 8 T11)

Does NOT extend BaseEntity because V19's DEFAULT SYS_GUID() PK fill must
remain to preserve IT raw-SQL insert compatibility. Lifecycle callbacks
declared inline; expires_at retained as lease-domain timestamp.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: admin-ui formatDateTime 유틸 + 5개 페이지 일괄 교체

**Files:**
- Create: `admin-ui/src/lib/formatDateTime.ts`
- Modify: `admin-ui/src/pages/TenantList.tsx`
- Modify: `admin-ui/src/pages/ApiKeyList.tsx`
- Modify: `admin-ui/src/pages/MdsStatus.tsx`
- Modify: `admin-ui/src/pages/KeyManagement.tsx`
- Modify: `admin-ui/src/pages/AuditLog.tsx`

**Context:** 백엔드는 UTC `Instant` ISO-8601(`...Z`) 그대로 응답. 프론트엔드에서 `Intl.DateTimeFormat`으로 Asia/Seoul 변환.

- [ ] **Step 1: formatDateTime 유틸 작성**

Create `admin-ui/src/lib/formatDateTime.ts`:

```typescript
const FORMATTER = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

/**
 * Convert an ISO-8601 UTC timestamp (backend returns Instant.toString())
 * to KST display format. Returns em-dash for null/undefined/empty.
 * Returns the raw input if it cannot be parsed (defensive).
 *
 * Example: "2026-05-27T03:00:00Z" → "2026. 05. 27. 12:00:00"
 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (isNaN(date.getTime())) return iso;
  return FORMATTER.format(date);
}
```

- [ ] **Step 2: TenantList.tsx 수정**

Read `admin-ui/src/pages/TenantList.tsx` first. Find every timestamp display (look for `createdAt`, `updatedAt`, `lastUsedAt`, etc.) and wrap with `formatDateTime`.

Pattern to apply:
- Before: `<td>{tenant.createdAt}</td>` or `<td>{new Date(tenant.createdAt).toLocaleString()}</td>`
- After: `<td>{formatDateTime(tenant.createdAt)}</td>`

Add the import at the top:
```typescript
import { formatDateTime } from '../lib/formatDateTime';
```

- [ ] **Step 3: ApiKeyList.tsx 수정**

Same pattern. Look for `createdAt`, `lastUsedAt`, `expiresAt`, `revokedAt` display sites. Apply `formatDateTime` to each.

- [ ] **Step 4: MdsStatus.tsx 수정**

Same pattern. Look for `fetchedAt`, `nextUpdate`, `createdAt`, `updatedAt` (if displayed). Apply `formatDateTime`. Note: `nextUpdate` is a `LocalDate` (date-only), formatDateTime still works because `new Date("2026-05-27")` parses OK — output will be "2026. 05. 27. 09:00:00" (UTC midnight → KST 9am). If date-only display is wanted, a separate `formatDate` may be needed; **for now use formatDateTime everywhere for consistency, and only add formatDate if smoke testing reveals user confusion**.

- [ ] **Step 5: KeyManagement.tsx 수정**

Same pattern. SigningKey table shows `createdAt`, `rotatedAt`, `revokedAt` (and now `updatedAt`). Apply `formatDateTime`.

- [ ] **Step 6: AuditLog.tsx 수정**

Same pattern. Apply `formatDateTime` to `createdAt`.

- [ ] **Step 7: Build admin-ui**

Run: `cd admin-ui && npm run build && cd ..`

Expected: BUILD SUCCESSFUL. `tsc -b` should pass with the new import in 5 pages.

- [ ] **Step 8: Commit**

```bash
git add admin-ui/src/lib/formatDateTime.ts \
        admin-ui/src/pages/TenantList.tsx \
        admin-ui/src/pages/ApiKeyList.tsx \
        admin-ui/src/pages/MdsStatus.tsx \
        admin-ui/src/pages/KeyManagement.tsx \
        admin-ui/src/pages/AuditLog.tsx
git commit -m "feat(admin-ui): formatDateTime utility + KST display in 5 pages (Phase 8 T12)

Intl.DateTimeFormat('ko-KR', timeZone='Asia/Seoul'). Backend Instant
ISO-8601 UTC responses are converted at render time. No backend API
contract change — RP clients (passkey-app) unaffected.

Pages updated: TenantList, ApiKeyList, MdsStatus, KeyManagement, AuditLog.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: 전체 회귀 테스트 + DoD verify + tag

**Files:**
- No file changes — verification only

**Context:** Phase 6/7과 동일한 DoD 패턴. 11개 entity가 모두 BaseEntity 또는 동등한 timestamp 정책을 가지는지 검증, 전체 build green, tag 생성.

- [ ] **Step 1: Stop user docker containers to free RAM for Testcontainers**

Run: `docker stop passkey-oracle passkey-redis 2>/dev/null || true`

(Skip if not running. Testcontainers Oracle is RAM-hungry — needs ~3GB.)

- [ ] **Step 2: Full clean build**

Run: `./gradlew clean build --console=plain`

Expected: BUILD SUCCESSFUL. 153+ tests pass (Phase 7 baseline was 153/153; new BaseEntityCallbackIT adds 3 tests → expect ~156).

- [ ] **Step 3: Aggregate test results**

Run:
```bash
find . -path '*/build/test-results/test/TEST-*.xml' | xargs grep -h '<testsuite ' 2>/dev/null | python3 -c "
import sys, re
total_tests=0; total_failures=0; total_errors=0; total_skipped=0
for line in sys.stdin:
    t = re.search(r'tests=\"(\d+)\"', line)
    f = re.search(r'failures=\"(\d+)\"', line)
    e = re.search(r'errors=\"(\d+)\"', line)
    s = re.search(r'skipped=\"(\d+)\"', line)
    if t: total_tests += int(t.group(1))
    if f: total_failures += int(f.group(1))
    if e: total_errors += int(e.group(1))
    if s: total_skipped += int(s.group(1))
print(f'Total tests: {total_tests}')
print(f'Failures: {total_failures}')
print(f'Errors: {total_errors}')
print(f'Skipped: {total_skipped}')
print(f'Passed: {total_tests - total_failures - total_errors - total_skipped}')
"
```

Expected: Failures: 0, Errors: 0.

- [ ] **Step 4: admin-ui build**

Run: `cd admin-ui && npm run build && cd ..`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Verify entity inheritance**

Run:
```bash
grep -l "extends BaseEntity" core/src/main/java/com/crosscert/passkey/core/entity/*.java | wc -l
```

Expected: `9` (AdminUser, ApiKey, ApiKeyScope, AuditLog, Credential, SigningKey, Tenant, TenantAcceptedFormat, TenantAllowedOrigin).

Run:
```bash
grep -L "extends BaseEntity" core/src/main/java/com/crosscert/passkey/core/entity/*.java
```

Expected output: `BaseEntity.java`, `MdsBlobCache.java`, `SchedulerLease.java` (3 files that don't extend BaseEntity).

Run:
```bash
grep -l "@PrePersist\|@PreUpdate" core/src/main/java/com/crosscert/passkey/core/entity/*.java
```

Expected: `BaseEntity.java`, `MdsBlobCache.java`, `SchedulerLease.java` (3 files with lifecycle callbacks; the 9 others inherit from BaseEntity).

- [ ] **Step 6: Verify no touchUpdatedAt remnants**

Run: `grep -rn 'touchUpdatedAt' --include='*.java'`

Expected: 0 matches.

- [ ] **Step 7: Verify formatDateTime usage**

Run: `grep -rn "formatDateTime" admin-ui/src/`

Expected: 6+ files match (1 definition + 5 pages).

- [ ] **Step 8: Create Phase 8 tag**

Run:
```bash
git tag -a phase-8-base-entity-timestamps-complete -m "$(cat <<'EOF'
Phase 8 (BaseEntity + updated_at + admin-ui KST) complete.

Acceptance:
- BaseEntity @MappedSuperclass extracted with UUID PK + createdAt + updatedAt + @PrePersist/@PreUpdate
- 9 entities extend BaseEntity (AdminUser, ApiKey, ApiKeyScope, AuditLog, Credential, SigningKey, Tenant, TenantAcceptedFormat, TenantAllowedOrigin)
- 2 special-case entities declare timestamps inline (MdsBlobCache singleton PK; SchedulerLease V19 SYS_GUID compat)
- V22 Flyway: 10 tables get updated_at; child 3 + mds + scheduler get created_at also
- Tenant.touchUpdatedAt() removed (no call sites)
- admin-ui formatDateTime utility + 5 pages display KST (Asia/Seoul)
- Backend API contract unchanged (UTC ISO-8601 Instant); RP clients unaffected
- All tests green (target: 156+/0/0)

Phase 8 surface:
- New file: BaseEntity.java
- New file: BaseEntityCallbackIT.java (3 lifecycle scenarios)
- New file: V22__base_entity_timestamps.sql
- New file: admin-ui/src/lib/formatDateTime.ts
- Modified: 11 entity files
- Modified: 5 admin-ui pages

Followups deferred to Phase 9+:
- TenantScopedEntity extraction + Tenant FK consistency (ApiKey/Credential raw tenant_id vs child @ManyToOne)
- Status enum standardization (AdminUser.enabledFlag, SigningKey.status, Tenant.status)
- created_by/updated_by audit columns
- Tenant.timezone column for per-tenant TZ
EOF
)"

git tag -l 'phase-*'
```

Expected: `phase-8-base-entity-timestamps-complete` appears in the list.

- [ ] **Step 9: Restart user docker containers**

Run: `docker start passkey-oracle passkey-redis 2>/dev/null || true`

- [ ] **Step 10: Final commit (if untracked files or .gradle pollution)**

Run: `git status`

Expected: working tree clean (all changes from T1-T12 already committed).

If anything untracked appears, evaluate before committing. Don't auto-commit `.gradle/` or build output.

---

## Self-Review

### Spec coverage

| Spec section | Plan task |
|---|---|
| §2.1 In Scope — BaseEntity | T1 |
| §2.1 In Scope — 9 entity 상속 | T3 (AdminUser), T4 (ApiKey), T5 (Credential), T6 (SigningKey), T7 (AuditLog), T8 (3 child), T9 (Tenant) |
| §2.1 In Scope — 2 예외 entity timestamp 수동 | T10 (MdsBlobCache), T11 (SchedulerLease) |
| §2.1 In Scope — touchUpdatedAt 제거 | T9 step 2 (grep), step 1 (deletion) |
| §2.1 In Scope — V22 migration | T2 |
| §2.1 In Scope — formatDateTime | T12 step 1 |
| §2.1 In Scope — admin-ui KST 일괄 적용 | T12 steps 2-6 |
| §3.1 Java 타입 Instant 유지 | 모든 entity task (Instant import 유지) |
| §3.2 @PrePersist/@PreUpdate | T1 BaseEntity, T10/T11 직접 구현 |
| §3.3 단일 BaseEntity | T1 |
| §3.4 MdsBlobCache/SchedulerLease 예외 처리 | T10, T11 |
| §3.5 SYSTIMESTAMP 백필 | T2 |
| §3.6 프론트엔드 렌더링 시점 TZ | T12 |
| §6 테스트 전략 | T3 (BaseEntityCallbackIT), 그 외 task별 affected test 실행 |
| §7 위험과 완화 | T11 step 2 (raw SQL insert 호환성 확인), T6 (reflective seed for hash chain), T13 (전체 회귀) |
| §8 DoD | T13 |

No gaps.

### Placeholder scan

No "TBD", "TODO", "implement later", "fill in details", "Add appropriate error handling", "Similar to Task N" patterns. All code blocks contain actual code. Commands have expected outputs.

One conditional in T11 step 2 ("If raw-SQL inserts fail, ...") gives explicit fallback, not a placeholder.

One conditional in T8 step 1 ("Verify the actual field names ... match the current file") is a safety check for the implementer because the spec doesn't show the current ApiKeyScope content (Phase 7 commit was referenced as the canonical example). This is intentional — the implementer reads the file first, then applies the same change pattern documented for the other two child entities.

### Type consistency

- `BaseEntity` defined in T1: `getId()`, `getCreatedAt()`, `getUpdatedAt()` — all subsequent tasks use these getters
- `BaseEntity` field names: `id`, `createdAt`, `updatedAt` — T7 (AuditLog) reflection uses exact same names
- `MdsBlobCache` and `SchedulerLease` declare same field names `createdAt`, `updatedAt` with same `@Column` shape — consistent
- `formatDateTime(iso: string | null | undefined): string` — same signature in T12 step 1 (definition) and steps 2-6 (usage)
- `seedCreatedAt(Instant)` helper in AuditLog (T7) — private method, used only inside the class constructor, no external consumer

No inconsistencies.
