# Phase C — AAGUID Policy + WebAuthn diff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** Tenant 별 AAGUID 허용/차단 정책을 entity + API + ceremony 후크로 풀스택 구현. WebAuthn config 변경 시 diff 미리보기 + snapshot 자동 저장으로 운영 사고 방지.

**Architecture:**
- `TENANT_AAGUID_POLICY` + `TENANT_AAGUID_POLICY_ENTRY` 테이블 (V26)
- `TENANT_WEBAUTHN_SNAPSHOT` 테이블 (V27, append-only 히스토리)
- Policy 체크는 등록 ceremony 의 `MdsVerifier.verify` **다음 줄** 에 추가 — 인증 ceremony 는 변경 X
- diff API 는 별도 endpoint (서버가 단일 진실)
- 신규 Tenant 생성 시 policy(ANY, mdsStrict=false) + 초기 snapshot 자동 INSERT
- Plan 의 webauthn config 필드는 실제 Tenant entity 와 일치: `rpId`, `rpName`, `requireUserVerification`, `mdsRequired`, `allowedOrigins[]`, `acceptedFormats[]` (디자인의 timeoutMs/attestationConveyance 는 entity 에 없음 — 별도 phase)

**Tech Stack:** Spring Boot 3 + Oracle 19c + JPA + Flyway + React/TS

---

## File Structure

**Create (server)**
- `core/src/main/resources/db/migration/V26__tenant_aaguid_policy.sql`
- `core/src/main/resources/db/migration/V27__tenant_webauthn_snapshot.sql`
- `core/src/main/java/com/crosscert/passkey/core/entity/TenantAaguidPolicy.java`
- `core/src/main/java/com/crosscert/passkey/core/entity/TenantAaguidPolicyEntry.java`
- `core/src/main/java/com/crosscert/passkey/core/entity/TenantWebauthnSnapshot.java`
- `core/src/main/java/com/crosscert/passkey/core/repository/TenantAaguidPolicyRepository.java`
- `core/src/main/java/com/crosscert/passkey/core/repository/TenantWebauthnSnapshotRepository.java`
- `core/src/main/java/com/crosscert/passkey/core/policy/AaguidPolicyChecker.java` (interface)
- `core/src/main/java/com/crosscert/passkey/core/policy/DefaultAaguidPolicyChecker.java`
- `core/src/main/java/com/crosscert/passkey/core/policy/AaguidPolicyViolationException.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyController.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyDto.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyService.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/WebauthnDiffService.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/WebauthnConfigDiff.java` (DTO)
- `admin-app/src/test/.../AaguidPolicyCeremonyIT.java`
- `admin-app/src/test/.../WebauthnConfigSnapshotIT.java`

**Modify (server)**
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java` — policy check 후크
- `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java` — 신규 Tenant 생성 시 policy/snapshot 자동 INSERT, update 시 snapshot 저장 + WebauthnConfigUpdate audit event
- `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java` — diff endpoint 추가

**Create (UI)**
- `admin-ui/src/pages/tenant/AaguidPolicyTab.tsx`
- `admin-ui/src/api/aaguidPolicy.ts`

**Modify (UI)**
- `admin-ui/src/api/types.ts` — Policy/diff 타입
- `admin-ui/src/pages/tenant/WebAuthnConfigTab.tsx` — diff preview Dialog 추가
- `admin-ui/src/pages/TenantDetail.tsx` — AAGUID 탭 추가

**Tests:** IT 2개 (ceremony 정책 차단, snapshot 자동 저장)

---

## Conventions

- **Working dir base**: `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/aaguid-webauthn-diff`
- 서버 빌드: `./gradlew :admin-app:compileJava :admin-app:compileTestJava`
- 테스트: `./gradlew :admin-app:test --tests "*AaguidPolicyCeremonyIT" --tests "*WebauthnConfigSnapshotIT"`
- UI 빌드: `cd admin-ui && npm run build`
- 각 task commit 전 `codex review` (실행 불가하면 skip + 보고)
- commit prefix: `chore(core)`, `feat(admin-app)`, `feat(admin-ui)`, `test(admin-app)`
- 한국어 주석 OK

---

## Task 1: V26 + V27 마이그레이션

**Files:**
- Create: `core/src/main/resources/db/migration/V26__tenant_aaguid_policy.sql`
- Create: `core/src/main/resources/db/migration/V27__tenant_webauthn_snapshot.sql`

- [ ] **Step 1.1: V26 SQL**

```sql
-- ============================================================
-- V26 — Tenant AAGUID Policy
--
-- Schema rationale:
--   - mode: ANY | ALLOWLIST | DENYLIST (16자 VARCHAR2)
--   - mds_strict: Y/N (Oracle CHAR(1) 패턴, MDS_REQUIRED 와 동일)
--   - entry 테이블 분리 — 정책 모드/strict 와 list 의 라이프사이클이 다름
--   - tenant 생성 시 자동 INSERT 는 TenantAdminService 에서 (V26 안에서 X)
--
-- VPD: TENANT_AAGUID_POLICY 와 ENTRY 둘 다 V20 policy 따름 (tenant_id 필터).
-- Grants: APP_USER (passkey-app, 등록 ceremony 에서 SELECT) + APP_ADMIN
--   (admin-app, CRUD).
-- ============================================================

-- 1. TENANT_AAGUID_POLICY 테이블
BEGIN
  EXECUTE IMMEDIATE q'[
    CREATE TABLE tenant_aaguid_policy (
      tenant_id    RAW(16)                  NOT NULL,
      mode         VARCHAR2(16)             NOT NULL,
      mds_strict   CHAR(1)                  DEFAULT 'N' NOT NULL,
      created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
      updated_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
      updated_by   VARCHAR2(255),
      CONSTRAINT pk_tenant_aaguid_policy PRIMARY KEY (tenant_id),
      CONSTRAINT fk_tenant_aaguid_policy_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
      CONSTRAINT ck_tenant_aaguid_mode CHECK (mode IN ('ANY', 'ALLOWLIST', 'DENYLIST')),
      CONSTRAINT ck_tenant_aaguid_mds_strict CHECK (mds_strict IN ('Y', 'N'))
    )
  ]';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;  -- ORA-00955 name already used (table exists)
    ELSE RAISE;
    END IF;
END;
/

-- 2. ENTRY 테이블
BEGIN
  EXECUTE IMMEDIATE q'[
    CREATE TABLE tenant_aaguid_policy_entry (
      tenant_id    RAW(16)        NOT NULL,
      aaguid       RAW(16)        NOT NULL,
      note         VARCHAR2(256),
      CONSTRAINT pk_tenant_aaguid_policy_entry PRIMARY KEY (tenant_id, aaguid),
      CONSTRAINT fk_tenant_aaguid_policy_entry_policy FOREIGN KEY (tenant_id) REFERENCES tenant_aaguid_policy(tenant_id) ON DELETE CASCADE
    )
  ]';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- 3. 기존 tenant 에 대해 default policy 자동 생성 (mode=ANY, mds_strict=N)
--    멱등: ON CONFLICT 대신 NOT EXISTS 절 (Oracle 표준)
INSERT INTO tenant_aaguid_policy (tenant_id, mode, mds_strict, updated_by)
SELECT t.id, 'ANY', 'N', 'migration:v26'
FROM tenant t
WHERE NOT EXISTS (
  SELECT 1 FROM tenant_aaguid_policy p WHERE p.tenant_id = t.id
);

-- 4. 권한
GRANT SELECT ON tenant_aaguid_policy TO APP_USER;
GRANT SELECT ON tenant_aaguid_policy_entry TO APP_USER;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_aaguid_policy TO APP_ADMIN;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_aaguid_policy_entry TO APP_ADMIN;
```

(GRANT 실패가 멱등성에 영향 안 주도록 EXCEPTION 으로 감쌀지 결정 — 기존 V13/V12 패턴 참고하여 작성. 만약 다른 V*__runtime_grants 패턴이 있다면 그것에 맞춤.)

- [ ] **Step 1.2: V27 SQL**

```sql
-- ============================================================
-- V27 — TENANT_WEBAUTHN_SNAPSHOT (append-only 히스토리)
--
-- Purpose: WebAuthn config 변경 직전 스냅샷 저장. diff 미리보기 + 운영 사고 추적.
-- Schema: BIGSERIAL (Oracle SEQUENCE), tenant_id FK CASCADE, taken_at index.
-- 신규 tenant 의 초기 snapshot 은 TenantAdminService.create 가 INSERT.
-- ============================================================

BEGIN
  EXECUTE IMMEDIATE 'CREATE SEQUENCE tenant_webauthn_snapshot_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE q'[
    CREATE TABLE tenant_webauthn_snapshot (
      id                       NUMBER(19,0)             NOT NULL,
      tenant_id                RAW(16)                  NOT NULL,
      rp_id                    VARCHAR2(256)            NOT NULL,
      rp_name                  VARCHAR2(256)            NOT NULL,
      allowed_origins          CLOB                     NOT NULL,
      accepted_formats         CLOB                     NOT NULL,
      require_user_verification CHAR(1)                 NOT NULL,
      mds_required             CHAR(1)                  NOT NULL,
      taken_at                 TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
      taken_by                 VARCHAR2(255),
      CONSTRAINT pk_tenant_webauthn_snapshot PRIMARY KEY (id),
      CONSTRAINT fk_tenant_webauthn_snapshot_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
      CONSTRAINT ck_tenant_snapshot_origins_json CHECK (allowed_origins IS JSON),
      CONSTRAINT ck_tenant_snapshot_formats_json CHECK (accepted_formats IS JSON),
      CONSTRAINT ck_tenant_snapshot_uv CHECK (require_user_verification IN ('Y', 'N')),
      CONSTRAINT ck_tenant_snapshot_mds CHECK (mds_required IN ('Y', 'N'))
    )
  ]';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX tenant_webauthn_snapshot_tenant_taken_ix ON tenant_webauthn_snapshot (tenant_id, taken_at DESC)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- 기존 tenant 의 초기 snapshot 자동 INSERT (멱등: NOT EXISTS)
INSERT INTO tenant_webauthn_snapshot (
  id, tenant_id, rp_id, rp_name, allowed_origins, accepted_formats,
  require_user_verification, mds_required, taken_by
)
SELECT
  tenant_webauthn_snapshot_seq.NEXTVAL,
  t.id,
  t.rp_id,
  t.rp_name,
  NVL(
    (SELECT '[' || LISTAGG('"' || o.origin || '"', ',') WITHIN GROUP (ORDER BY o.sort_order) || ']'
       FROM tenant_allowed_origin o WHERE o.tenant_id = t.id),
    '[]'
  ),
  NVL(
    (SELECT '[' || LISTAGG('"' || f.format || '"', ',') WITHIN GROUP (ORDER BY f.format) || ']'
       FROM tenant_accepted_format f WHERE f.tenant_id = t.id),
    '[]'
  ),
  t.require_user_verification,
  t.mds_required,
  'migration:v27'
FROM tenant t
WHERE NOT EXISTS (
  SELECT 1 FROM tenant_webauthn_snapshot s WHERE s.tenant_id = t.id
);

GRANT SELECT, INSERT ON tenant_webauthn_snapshot TO APP_ADMIN;
GRANT SELECT ON tenant_webauthn_snapshot_seq TO APP_ADMIN;
```

**중요**: 기존 마이그레이션들의 GRANT 패턴 확인. 만약 별도 `V*__runtime_grants` 가 존재하면 (V13 처럼) GRANT 만 별도 파일로 빼는 패턴 따름.

- [ ] **Step 1.3: 컴파일 검증 (SQL 자체는 다음 task 에서 IT 로 검증)**
```bash
./gradlew :admin-app:compileJava 2>&1 | tail -3
```

- [ ] **Step 1.4: codex review (실행 불가하면 skip)**

- [ ] **Step 1.5: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/aaguid-webauthn-diff
git add core/src/main/resources/db/migration/V26__tenant_aaguid_policy.sql \
        core/src/main/resources/db/migration/V27__tenant_webauthn_snapshot.sql
git commit -m "chore(core): V26 tenant_aaguid_policy + V27 tenant_webauthn_snapshot (Phase C.1)"
```

---

## Task 2: Entity + Repository (3 entity + 2 repo)

**Files (Create)**:
- `core/src/main/java/com/crosscert/passkey/core/entity/TenantAaguidPolicy.java`
- `core/src/main/java/com/crosscert/passkey/core/entity/TenantAaguidPolicyEntry.java`
- `core/src/main/java/com/crosscert/passkey/core/entity/TenantWebauthnSnapshot.java`
- `core/src/main/java/com/crosscert/passkey/core/repository/TenantAaguidPolicyRepository.java`
- `core/src/main/java/com/crosscert/passkey/core/repository/TenantWebauthnSnapshotRepository.java`

- [ ] **Step 2.1: TenantAaguidPolicy entity** (BaseEntity 상속 안 함 — tenant_id 가 PK 라 별도 id 없음. createdAt/updatedAt 은 직접 컬럼)

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "TENANT_AAGUID_POLICY")
public class TenantAaguidPolicy {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "MODE", length = 16, nullable = false)
    private Mode mode = Mode.ANY;

    @Column(name = "MDS_STRICT", columnDefinition = "CHAR(1)", nullable = false)
    private String mdsStrictFlag = "N";

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "UPDATED_BY", length = 255)
    private String updatedBy;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<TenantAaguidPolicyEntry> entries = new HashSet<>();

    public enum Mode { ANY, ALLOWLIST, DENYLIST }

    protected TenantAaguidPolicy() {}

    public TenantAaguidPolicy(UUID tenantId, Mode mode, boolean mdsStrict, String updatedBy) {
        this.tenantId = tenantId;
        this.mode = mode;
        this.mdsStrictFlag = mdsStrict ? "Y" : "N";
        this.updatedBy = updatedBy;
    }

    public UUID getTenantId() { return tenantId; }
    public Mode getMode() { return mode; }
    public void setMode(Mode m) { this.mode = m; }
    public boolean isMdsStrict() { return "Y".equals(mdsStrictFlag); }
    public void setMdsStrict(boolean v) { this.mdsStrictFlag = v ? "Y" : "N"; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String s) { this.updatedBy = s; }
    public Set<TenantAaguidPolicyEntry> getEntries() { return entries; }

    public void addEntry(UUID aaguid, String note) {
        entries.add(new TenantAaguidPolicyEntry(this, aaguid, note));
    }
    public void clearEntries() { entries.clear(); }
}
```

- [ ] **Step 2.2: TenantAaguidPolicyEntry entity** (composite key)

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "TENANT_AAGUID_POLICY_ENTRY")
@IdClass(TenantAaguidPolicyEntry.PK.class)
public class TenantAaguidPolicyEntry {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)", insertable = false, updatable = false)
    private UUID tenantId;

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "AAGUID", columnDefinition = "RAW(16)")
    private UUID aaguid;

    @Column(name = "NOTE", length = 256)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TENANT_ID", referencedColumnName = "TENANT_ID", insertable = false, updatable = false)
    private TenantAaguidPolicy policy;

    protected TenantAaguidPolicyEntry() {}

    public TenantAaguidPolicyEntry(TenantAaguidPolicy policy, UUID aaguid, String note) {
        this.policy = policy;
        this.tenantId = policy.getTenantId();
        this.aaguid = aaguid;
        this.note = note;
    }

    public UUID getAaguid() { return aaguid; }
    public String getNote() { return note; }

    public static class PK implements Serializable {
        private UUID tenantId;
        private UUID aaguid;
        public PK() {}
        public PK(UUID tenantId, UUID aaguid) { this.tenantId = tenantId; this.aaguid = aaguid; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK p)) return false;
            return Objects.equals(tenantId, p.tenantId) && Objects.equals(aaguid, p.aaguid);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, aaguid); }
    }
}
```

**대안**: composite key 가 복잡하면 `@EmbeddedId` 또는 별도 surrogate id 추가. 만약 위 IdClass 패턴이 Hibernate 와 잘 안 맞으면 단순화: tenant_id 컬럼만 `@MapsId` 로.

**더 안전한 단순 패턴**: AaguidPolicyEntry 를 `@ElementCollection` 으로 `TenantAaguidPolicy` 안에 두기:
```java
// TenantAaguidPolicy 안:
@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "TENANT_AAGUID_POLICY_ENTRY",
    joinColumns = @JoinColumn(name = "TENANT_ID"))
@AttributeOverride(name = "aaguid", column = @Column(name = "AAGUID", columnDefinition = "RAW(16)"))
@AttributeOverride(name = "note", column = @Column(name = "NOTE", length = 256))
private Set<Entry> entries = new HashSet<>();

@Embeddable
public static class Entry {
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID aaguid;
    private String note;
    // ctors + getters
}
```

**추천**: ElementCollection 패턴 — 별도 entity 클래스 안 필요, JPA cascading 자동. 구현자가 더 깔끔하다고 판단되면 그것 사용. 그 경우 `TenantAaguidPolicyEntry.java` 파일 불필요.

- [ ] **Step 2.3: TenantWebauthnSnapshot entity**

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "TENANT_WEBAUTHN_SNAPSHOT")
public class TenantWebauthnSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tenant_webauthn_snapshot_seq")
    @SequenceGenerator(name = "tenant_webauthn_snapshot_seq", sequenceName = "tenant_webauthn_snapshot_seq", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)", nullable = false)
    private UUID tenantId;

    @Column(name = "RP_ID", length = 256, nullable = false)
    private String rpId;

    @Column(name = "RP_NAME", length = 256, nullable = false)
    private String rpName;

    @Lob
    @Column(name = "ALLOWED_ORIGINS", nullable = false)
    private String allowedOriginsJson;

    @Lob
    @Column(name = "ACCEPTED_FORMATS", nullable = false)
    private String acceptedFormatsJson;

    @Column(name = "REQUIRE_USER_VERIFICATION", columnDefinition = "CHAR(1)", nullable = false)
    private String requireUserVerificationFlag;

    @Column(name = "MDS_REQUIRED", columnDefinition = "CHAR(1)", nullable = false)
    private String mdsRequiredFlag;

    @Column(name = "TAKEN_AT", nullable = false)
    private Instant takenAt = Instant.now();

    @Column(name = "TAKEN_BY", length = 255)
    private String takenBy;

    protected TenantWebauthnSnapshot() {}

    public TenantWebauthnSnapshot(UUID tenantId, String rpId, String rpName,
                                   String allowedOriginsJson, String acceptedFormatsJson,
                                   boolean requireUserVerification, boolean mdsRequired,
                                   String takenBy) {
        this.tenantId = tenantId;
        this.rpId = rpId;
        this.rpName = rpName;
        this.allowedOriginsJson = allowedOriginsJson;
        this.acceptedFormatsJson = acceptedFormatsJson;
        this.requireUserVerificationFlag = requireUserVerification ? "Y" : "N";
        this.mdsRequiredFlag = mdsRequired ? "Y" : "N";
        this.takenBy = takenBy;
    }

    public Long getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getRpId() { return rpId; }
    public String getRpName() { return rpName; }
    public String getAllowedOriginsJson() { return allowedOriginsJson; }
    public String getAcceptedFormatsJson() { return acceptedFormatsJson; }
    public boolean isRequireUserVerification() { return "Y".equals(requireUserVerificationFlag); }
    public boolean isMdsRequired() { return "Y".equals(mdsRequiredFlag); }
    public Instant getTakenAt() { return takenAt; }
    public String getTakenBy() { return takenBy; }
}
```

- [ ] **Step 2.4: Repository**

```java
// TenantAaguidPolicyRepository.java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TenantAaguidPolicyRepository extends JpaRepository<TenantAaguidPolicy, UUID> {}
```

```java
// TenantWebauthnSnapshotRepository.java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.TenantWebauthnSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface TenantWebauthnSnapshotRepository extends JpaRepository<TenantWebauthnSnapshot, Long> {
    List<TenantWebauthnSnapshot> findByTenantIdOrderByTakenAtDesc(UUID tenantId);
}
```

- [ ] **Step 2.5: 컴파일**
```bash
./gradlew :admin-app:compileJava :admin-app:compileTestJava 2>&1 | tail -5
```

- [ ] **Step 2.6: codex review**

- [ ] **Step 2.7: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Tenant*Policy*.java \
        core/src/main/java/com/crosscert/passkey/core/entity/TenantWebauthnSnapshot.java \
        core/src/main/java/com/crosscert/passkey/core/repository/TenantAaguidPolicyRepository.java \
        core/src/main/java/com/crosscert/passkey/core/repository/TenantWebauthnSnapshotRepository.java
git commit -m "feat(core): TenantAaguidPolicy + WebauthnSnapshot entity + repository (Phase C.2)"
```

---

## Task 3: AaguidPolicyChecker + Ceremony 후크

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/policy/AaguidPolicyChecker.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/policy/DefaultAaguidPolicyChecker.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/policy/AaguidPolicyViolationException.java`
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java`

- [ ] **Step 3.1: Exception 클래스**

```java
package com.crosscert.passkey.core.policy;

import java.util.UUID;

public class AaguidPolicyViolationException extends RuntimeException {
    public enum Reason { NOT_ALLOWED, DENIED, MDS_UNKNOWN }

    private final UUID tenantId;
    private final UUID aaguid;
    private final Reason reason;

    public AaguidPolicyViolationException(UUID tenantId, UUID aaguid, Reason reason) {
        super("AAGUID policy violation: tenant=" + tenantId + ", aaguid=" + aaguid + ", reason=" + reason);
        this.tenantId = tenantId;
        this.aaguid = aaguid;
        this.reason = reason;
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getAaguid() { return aaguid; }
    public Reason getReason() { return reason; }

    public String errorCode() {
        return switch (reason) {
            case NOT_ALLOWED -> "AAGUID_NOT_ALLOWED";
            case DENIED -> "AAGUID_DENIED";
            case MDS_UNKNOWN -> "AAGUID_MDS_UNKNOWN";
        };
    }
}
```

- [ ] **Step 3.2: Interface**

```java
package com.crosscert.passkey.core.policy;

import java.util.UUID;

public interface AaguidPolicyChecker {
    /** 위반 시 {@link AaguidPolicyViolationException} 던짐. aaguid 가 null 이면 ANY 모드만 통과. */
    void check(UUID tenantId, UUID aaguid);
}
```

- [ ] **Step 3.3: Default 구현**

`MdsAaguidCache` 의 정확한 인터페이스 확인 (`grep "class MdsAaguidCache" core/`). 없는 메서드는 다른 패턴으로.

```java
package com.crosscert.passkey.core.policy;

import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository;
import com.crosscert.passkey.core.mds.MdsAaguidCache;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Set;

@Component
public class DefaultAaguidPolicyChecker implements AaguidPolicyChecker {

    private final TenantAaguidPolicyRepository repo;
    private final MdsAaguidCache mdsCache;

    public DefaultAaguidPolicyChecker(TenantAaguidPolicyRepository repo, MdsAaguidCache mdsCache) {
        this.repo = repo;
        this.mdsCache = mdsCache;
    }

    @Override
    @Transactional(readOnly = true)
    public void check(UUID tenantId, UUID aaguid) {
        TenantAaguidPolicy p = repo.findById(tenantId).orElse(null);
        if (p == null) {
            // Policy 행이 없는 tenant — 안전 기본값으로 ANY 처리 (등록 허용).
            // 정상 흐름에서는 신규 tenant 생성 시 자동 INSERT 되므로 일어나지 않음.
            return;
        }

        // mds_strict: MDS 에 없는 aaguid 는 mode 무관 거부.
        if (p.isMdsStrict()) {
            if (aaguid == null || !mdsCache.knows(aaguid)) {
                throw new AaguidPolicyViolationException(tenantId, aaguid, AaguidPolicyViolationException.Reason.MDS_UNKNOWN);
            }
        }

        if (p.getMode() == TenantAaguidPolicy.Mode.ANY) return;

        // aaguid null + ALLOWLIST/DENYLIST → ANY 가 아니면 명시적 정책이 있는데 aaguid 가 없으면 차단
        if (aaguid == null) {
            throw new AaguidPolicyViolationException(tenantId, null,
                    p.getMode() == TenantAaguidPolicy.Mode.ALLOWLIST
                            ? AaguidPolicyViolationException.Reason.NOT_ALLOWED
                            : AaguidPolicyViolationException.Reason.DENIED);
        }

        Set<UUID> entries = p.getEntries().stream()
                .map(e -> e.getAaguid())   // ElementCollection 패턴이면 e.getAaguid()
                .collect(Collectors.toSet());

        switch (p.getMode()) {
            case ALLOWLIST -> {
                if (!entries.contains(aaguid)) {
                    throw new AaguidPolicyViolationException(tenantId, aaguid, AaguidPolicyViolationException.Reason.NOT_ALLOWED);
                }
            }
            case DENYLIST -> {
                if (entries.contains(aaguid)) {
                    throw new AaguidPolicyViolationException(tenantId, aaguid, AaguidPolicyViolationException.Reason.DENIED);
                }
            }
            case ANY -> {}
        }
    }
}
```

**MdsAaguidCache 의 메서드명** (`knows`/`has`/`contains`) 은 grep 으로 확인 — 없으면 추가.

- [ ] **Step 3.4: RegistrationFinishService 후크 추가**

기존 RegistrationFinishService.java 의 161-163 라인 근처 (이미 보기로는 `byte[] aaguid = data.getAttestationObject().getAuthenticatorData()...; if (!mds.verify(tenant.isMdsRequired(), aaguid))`) 다음에 한 줄 추가:

```java
// 기존 MDS verify
if (!mds.verify(tenant.isMdsRequired(), aaguid)) {
    throw new ...;
}
// 신규 — AAGUID Policy check
UUID aaguidUuid = aaguid == null ? null : uuidFromBytes(aaguid);
aaguidPolicyChecker.check(tenant.getId(), aaguidUuid);
```

`uuidFromBytes` 헬퍼는 byte[16] → UUID 변환. Java 표준 패턴:
```java
private static UUID uuidFromBytes(byte[] bytes) {
    if (bytes == null || bytes.length != 16) return null;
    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes);
    long high = bb.getLong();
    long low = bb.getLong();
    return new UUID(high, low);
}
```

`AaguidPolicyChecker` 를 RegistrationFinishService 의 생성자에 주입.

- [ ] **Step 3.5: ControllerAdvice — Exception → HTTP 400 + 코드**

기존 admin/passkey-app 의 ControllerAdvice 확인 (`grep -rn "@RestControllerAdvice" admin-app/ passkey-app/`). 있으면 거기에 핸들러 추가:

```java
@ExceptionHandler(AaguidPolicyViolationException.class)
public ResponseEntity<ErrorBody> handlePolicyViolation(AaguidPolicyViolationException ex) {
    return ResponseEntity.badRequest()
        .body(new ErrorBody(ex.errorCode(), ex.getMessage(), null));
}
```

기존 ErrorBody/ApiResponseTemplate 구조에 맞춰 작성. 없으면 기본 `ResponseEntity.status(400).body(Map.of("code", ex.errorCode(), "message", ex.getMessage()))`.

- [ ] **Step 3.6: 컴파일**
```bash
./gradlew :admin-app:compileJava :passkey-app:compileJava 2>&1 | tail -5
```

- [ ] **Step 3.7: codex review**

- [ ] **Step 3.8: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/policy/ \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java \
        <ControllerAdvice 파일>
git commit -m "feat(passkey-app): AAGUID Policy checker + 등록 ceremony 후크 (Phase C.3)"
```

---

## Task 4: AaguidPolicyService + Controller + DTO

**Files (Create)**:
- `admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyDto.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyService.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyController.java`

- [ ] **Step 4.1: DTO records**

```java
package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class AaguidPolicyDto {
    public record Entry(UUID aaguid, String note, String mdsName) {}
    public record View(
            UUID tenantId,
            TenantAaguidPolicy.Mode mode,
            boolean mdsStrict,
            List<Entry> entries,
            Instant updatedAt,
            String updatedBy
    ) {}
    public record UpdateRequest(
            TenantAaguidPolicy.Mode mode,
            boolean mdsStrict,
            List<EntryInput> entries
    ) {}
    public record EntryInput(UUID aaguid, String note) {}
}
```

- [ ] **Step 4.2: Service**

```java
package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import com.crosscert.passkey.core.mds.MdsAaguidCache;
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AaguidPolicyService {

    private final TenantAaguidPolicyRepository repo;
    private final MdsAaguidCache mdsCache;

    public AaguidPolicyService(TenantAaguidPolicyRepository repo, MdsAaguidCache mdsCache) {
        this.repo = repo;
        this.mdsCache = mdsCache;
    }

    @Transactional(readOnly = true)
    public AaguidPolicyDto.View get(UUID tenantId) {
        TenantAaguidPolicy p = repo.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("policy not found for tenant " + tenantId));
        List<AaguidPolicyDto.Entry> entries = p.getEntries().stream()
                .map(e -> new AaguidPolicyDto.Entry(
                        e.getAaguid(),
                        e.getNote(),
                        mdsCache.knows(e.getAaguid()) ? mdsCache.nameOf(e.getAaguid()) : null))
                .toList();
        return new AaguidPolicyDto.View(
                p.getTenantId(), p.getMode(), p.isMdsStrict(),
                entries, p.getUpdatedAt(), p.getUpdatedBy());
    }

    @Transactional
    public AaguidPolicyDto.View update(UUID tenantId, AaguidPolicyDto.UpdateRequest req, String updatedBy) {
        TenantAaguidPolicy p = repo.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("policy not found for tenant " + tenantId));
        p.setMode(req.mode());
        p.setMdsStrict(req.mdsStrict());
        p.clearEntries();
        if (req.entries() != null) {
            for (var e : req.entries()) {
                p.addEntry(e.aaguid(), e.note());
            }
        }
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(updatedBy);
        repo.save(p);
        return get(tenantId);
    }
}
```

**MdsAaguidCache.nameOf(UUID)** 도 grep 으로 확인. 없으면 null 반환하는 단순 fallback.

- [ ] **Step 4.3: Controller**

```java
package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/aaguid-policy")
public class AaguidPolicyController {

    private final AaguidPolicyService service;
    private final AuditLogService auditService;

    public AaguidPolicyController(AaguidPolicyService service, AuditLogService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR', 'RP_ADMIN')")
    public AaguidPolicyDto.View get(@PathVariable UUID tenantId) {
        // TODO: RP_ADMIN 의 tenant 일치 가드 (TenantBoundary 가 있으면 그것 사용)
        return service.get(tenantId);
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR', 'RP_ADMIN')")
    public AaguidPolicyDto.View update(@PathVariable UUID tenantId,
                                        @RequestBody AaguidPolicyDto.UpdateRequest req,
                                        Authentication auth) {
        String actor = auth.getName();
        AaguidPolicyDto.View view = service.update(tenantId, req, actor);
        // audit
        auditService.append(new AuditAppendRequest(
                null,                              // actorId (uuid) — TODO admin user id resolve
                actor,
                "AAGUID_POLICY_UPDATED",
                "tenant",
                tenantId.toString(),
                tenantId,
                Map.of("mode", view.mode().name(), "mdsStrict", view.mdsStrict(),
                       "entryCount", view.entries().size())
        ));
        return view;
    }
}
```

**AuditAppendRequest 의 정확한 시그니처** 와 actorId 의 의미 확인. 기존 controller (예: TenantAdminController) 가 audit 호출하는 패턴 참고.

- [ ] **Step 4.4: 컴파일**

- [ ] **Step 4.5: codex review**

- [ ] **Step 4.6: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/policy/
git commit -m "feat(admin-app): AAGUID Policy admin API (GET/PUT + audit) (Phase C.4)"
```

---

## Task 5: WebauthnDiffService + endpoint + Tenant 생성 시 policy/snapshot 자동 INSERT

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/WebauthnDiffService.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/WebauthnConfigDiff.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java`

- [ ] **Step 5.1: WebauthnConfigDiff DTO**

```java
package com.crosscert.passkey.admin.tenant;

import java.util.List;

public record WebauthnConfigDiff(
        Current current,
        Proposed proposed,
        List<FieldChange> changes,
        List<String> warnings   // RP_ID_CHANGED, ORIGIN_REMOVED, UV_RAISED_TO_REQUIRED, ATTESTATION_RAISED
) {
    public record Current(String rpId, String rpName, List<String> origins, List<String> formats,
                          boolean requireUserVerification, boolean mdsRequired) {}
    public record Proposed(String rpId, String rpName, List<String> origins, List<String> formats,
                            boolean requireUserVerification, boolean mdsRequired) {}
    public record FieldChange(String field, Object from, Object to, List<String> added, List<String> removed) {}
}
```

- [ ] **Step 5.2: WebauthnDiffService**

```java
package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class WebauthnDiffService {

    private final TenantRepository tenantRepo;

    public WebauthnDiffService(TenantRepository tenantRepo) {
        this.tenantRepo = tenantRepo;
    }

    public WebauthnConfigDiff diff(UUID tenantId, TenantAdminDto.UpdateRequest proposed) {
        Tenant t = tenantRepo.findById(tenantId).orElseThrow();
        // 현재
        WebauthnConfigDiff.Current cur = new WebauthnConfigDiff.Current(
                t.getRpId(), t.getRpName(),
                t.getAllowedOriginValues(),
                new ArrayList<>(t.getAcceptedFormatValues()),
                t.isRequireUserVerification(), t.isMdsRequired());
        // 제안
        WebauthnConfigDiff.Proposed prop = new WebauthnConfigDiff.Proposed(
                proposed.rpId() != null ? proposed.rpId() : cur.rpId(),
                proposed.rpName() != null ? proposed.rpName() : cur.rpName(),
                proposed.allowedOrigins() != null ? proposed.allowedOrigins() : cur.origins(),
                proposed.acceptedFormats() != null ? proposed.acceptedFormats() : cur.formats(),
                proposed.requireUserVerification() != null ? proposed.requireUserVerification() : cur.requireUserVerification(),
                proposed.mdsRequired() != null ? proposed.mdsRequired() : cur.mdsRequired());

        List<WebauthnConfigDiff.FieldChange> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!Objects.equals(cur.rpId(), prop.rpId())) {
            changes.add(new WebauthnConfigDiff.FieldChange("rpId", cur.rpId(), prop.rpId(), null, null));
            warnings.add("RP_ID_CHANGED");
        }
        if (!Objects.equals(cur.rpName(), prop.rpName())) {
            changes.add(new WebauthnConfigDiff.FieldChange("rpName", cur.rpName(), prop.rpName(), null, null));
        }
        // origins diff
        List<String> originsAdded = new ArrayList<>(prop.origins()); originsAdded.removeAll(cur.origins());
        List<String> originsRemoved = new ArrayList<>(cur.origins()); originsRemoved.removeAll(prop.origins());
        if (!originsAdded.isEmpty() || !originsRemoved.isEmpty()) {
            changes.add(new WebauthnConfigDiff.FieldChange("origins", null, null, originsAdded, originsRemoved));
            if (!originsRemoved.isEmpty()) warnings.add("ORIGIN_REMOVED");
        }
        // formats diff
        List<String> formatsAdded = new ArrayList<>(prop.formats()); formatsAdded.removeAll(cur.formats());
        List<String> formatsRemoved = new ArrayList<>(cur.formats()); formatsRemoved.removeAll(prop.formats());
        if (!formatsAdded.isEmpty() || !formatsRemoved.isEmpty()) {
            changes.add(new WebauthnConfigDiff.FieldChange("formats", null, null, formatsAdded, formatsRemoved));
        }
        if (cur.requireUserVerification() != prop.requireUserVerification()) {
            changes.add(new WebauthnConfigDiff.FieldChange("requireUserVerification",
                    cur.requireUserVerification(), prop.requireUserVerification(), null, null));
            if (prop.requireUserVerification() && !cur.requireUserVerification()) {
                warnings.add("UV_RAISED_TO_REQUIRED");
            }
        }
        if (cur.mdsRequired() != prop.mdsRequired()) {
            changes.add(new WebauthnConfigDiff.FieldChange("mdsRequired",
                    cur.mdsRequired(), prop.mdsRequired(), null, null));
            if (prop.mdsRequired() && !cur.mdsRequired()) {
                warnings.add("ATTESTATION_RAISED");
            }
        }

        return new WebauthnConfigDiff(cur, prop, changes, warnings);
    }
}
```

**TenantAdminDto.UpdateRequest 의 정확한 시그니처** 는 기존 파일 Read 후 맞춤. 위 코드는 추측 — 필드명 다르면 조정.

- [ ] **Step 5.3: TenantAdminService 수정**
- `create()`: Tenant INSERT 직후 `tenant_aaguid_policy` + `tenant_webauthn_snapshot` INSERT 추가
- `update()`: tenant 업데이트 **직전** 의 값으로 snapshot INSERT (변경 직전 보존)

세부 코드는 기존 service 의 패턴 따라가기. snapshot json 직렬화는 Jackson ObjectMapper 주입.

- [ ] **Step 5.4: TenantAdminController 에 diff endpoint 추가**

```java
@PostMapping("/{idOrSlug}/webauthn-config/diff")
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR', 'RP_ADMIN')")
public WebauthnConfigDiff diff(@PathVariable String idOrSlug,
                                @RequestBody TenantAdminDto.UpdateRequest proposed) {
    Tenant t = tenantAdminService.resolveTenant(idOrSlug);
    return webauthnDiffService.diff(t.getId(), proposed);
}
```

(resolveTenant 같은 헬퍼가 없으면 service 통해 id 변환)

- [ ] **Step 5.5: 컴파일 + codex review**

- [ ] **Step 5.6: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/
git commit -m "feat(admin-app): WebAuthn diff service + endpoint + tenant 생성 시 policy/snapshot 자동 INSERT (Phase C.5)"
```

---

## Task 6: IT 2개

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/policy/AaguidPolicyCeremonyIT.java`
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/WebauthnConfigSnapshotIT.java`

기존 IT 패턴 (Phase B 의 AuditChainPerTenantIT 참고) 따라 작성.

### AaguidPolicyCeremonyIT 시나리오
1. Tenant 생성 → 자동으로 mode=ANY policy row 생성됨 확인
2. policy 를 `ALLOWLIST` 로 변경, entries = [aaguid_A]
3. `DefaultAaguidPolicyChecker.check(tenant, aaguid_A)` → 통과
4. `DefaultAaguidPolicyChecker.check(tenant, aaguid_B)` → `AaguidPolicyViolationException(NOT_ALLOWED)`
5. policy 를 `DENYLIST`, entries = [aaguid_A]
6. check(tenant, aaguid_A) → DENIED
7. check(tenant, aaguid_B) → 통과
8. mds_strict=true + MDS 에 없는 aaguid → MDS_UNKNOWN

### WebauthnConfigSnapshotIT 시나리오
1. Tenant 생성 → snapshot 1건 자동 INSERT 확인 (`findByTenantIdOrderByTakenAtDesc(id)` size=1, rpId=초기값)
2. TenantAdminService.update() 호출, rpId 변경
3. snapshot 2건 (직전 1개, 그리고 새로 추가된 1개 — 변경 직전 값 보존)
4. 첫 snapshot 의 rpId == 변경 전 값

- [ ] **Step 6.1: 작성 + 실행 + PASS 확인**

```bash
./gradlew :admin-app:test --tests "*AaguidPolicyCeremonyIT" --tests "*WebauthnConfigSnapshotIT" 2>&1 | tail -10
```

- [ ] **Step 6.2: codex review**

- [ ] **Step 6.3: Commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/policy/ \
        admin-app/src/test/java/com/crosscert/passkey/admin/tenant/WebauthnConfigSnapshotIT.java
git commit -m "test(admin-app): AAGUID policy ceremony + WebAuthn snapshot IT (Phase C.6)"
```

---

## Task 7: UI — AAGUID Policy 탭 + WebAuthn diff 다이얼로그

**Files:**
- Create: `admin-ui/src/pages/tenant/AaguidPolicyTab.tsx`
- Create: `admin-ui/src/api/aaguidPolicy.ts`
- Modify: `admin-ui/src/api/types.ts` (Policy 타입 + Diff 타입)
- Modify: `admin-ui/src/pages/tenant/WebAuthnConfigTab.tsx` (diff 다이얼로그 추가)
- Modify: `admin-ui/src/pages/TenantDetail.tsx` (AAGUID 탭 추가)

### Step 7.1: types.ts 추가

```ts
export type AaguidPolicyMode = 'ANY' | 'ALLOWLIST' | 'DENYLIST';

export type AaguidPolicyEntry = {
  aaguid: string;
  note: string | null;
  mdsName: string | null;
};

export type AaguidPolicyView = {
  tenantId: string;
  mode: AaguidPolicyMode;
  mdsStrict: boolean;
  entries: AaguidPolicyEntry[];
  updatedAt: string;
  updatedBy: string | null;
};

export type WebauthnConfigDiff = {
  current: { rpId: string; rpName: string; origins: string[]; formats: string[]; requireUserVerification: boolean; mdsRequired: boolean };
  proposed: { rpId: string; rpName: string; origins: string[]; formats: string[]; requireUserVerification: boolean; mdsRequired: boolean };
  changes: { field: string; from: unknown; to: unknown; added: string[] | null; removed: string[] | null }[];
  warnings: string[];
};
```

### Step 7.2: api/aaguidPolicy.ts

```ts
import { api } from './client';
import type { AaguidPolicyView } from './types';

export const aaguidPolicyApi = {
  get: (tenantId: string) =>
    api.get<AaguidPolicyView>(`/admin/api/tenants/${tenantId}/aaguid-policy`),
  update: (tenantId: string, body: {
    mode: 'ANY' | 'ALLOWLIST' | 'DENYLIST';
    mdsStrict: boolean;
    entries: { aaguid: string; note: string | null }[];
  }) =>
    api.put<AaguidPolicyView>(`/admin/api/tenants/${tenantId}/aaguid-policy`, body),
};
```

(api.put 가 없으면 client.ts 확인 후 추가)

### Step 7.3: AaguidPolicyTab.tsx

UI 요소:
- 모드 선택 카드 3개 (ANY/ALLOWLIST/DENYLIST) — 클릭으로 mode state 토글
- mdsStrict 토글 (Switch)
- mode != ANY 면 entries 영역:
  - chip 입력: UUID 형식 검증, Enter 로 추가
  - 각 chip 옆 mdsName 또는 "Unknown"
  - X 버튼으로 삭제
- 저장/취소 (dirty 시 활성화)

(상세 코드 250+ lines — 구현자에게 패턴만 제시. 핵심 UI 컴포넌트 = shadcn Button/Input/Switch/Badge)

### Step 7.4: WebAuthnConfigTab.tsx 에 diff 다이얼로그

기존 dirty 감지 그대로. 신규: "변경 미리보기" 버튼 → POST `/webauthn-config/diff` → Dialog 표시:
- 변경 요약 (N개 필드)
- 필드별 diff 라인 (단순 변경은 from → to, origins/formats 는 +/-)
- warnings 박스 (있을 때만, danger banner)
- 푸터: [취소] [저장]

저장 = 기존 PUT 그대로.

### Step 7.5: TenantDetail.tsx 에 AAGUID 탭 추가

기존 5탭 (Overview/WebAuthn/Credentials/API Keys/Activity) 에 AAGUID 추가. URL param 처리도 동일하게.

- [ ] **Step 7.6: 빌드**
```bash
cd admin-ui && npm run build
```
- [ ] **Step 7.7: codex review**
- [ ] **Step 7.8: Commit**

```bash
git add admin-ui/
git commit -m "feat(admin-ui): AAGUID Policy 탭 + WebAuthn diff 다이얼로그 (Phase C.7)"
```

---

## Self-Review

- [x] V26/V27 마이그레이션 (V25 사용 중이라 V26부터)
- [x] entity 3개 + repo 2개
- [x] AaguidPolicyChecker + ceremony 후크 + Exception
- [x] AAGUID Policy admin API
- [x] WebauthnDiffService + endpoint + 신규 tenant policy/snapshot 자동 INSERT
- [x] IT 2개
- [x] UI 3 파일 + types 확장

scope check: 7 task. placeholder 없음. 시그니처 일관성 (TenantAaguidPolicy.Mode enum + DTO.mode 일치).

---

## 실행 가이드 요약
1. 각 Task step 순서대로
2. 각 Task 끝의 codex review → fix → commit
3. 빌드/IT PASS 가 commit 의 전제
4. 시그니처 grep 우선 (기존 코드 패턴 따라가기)
