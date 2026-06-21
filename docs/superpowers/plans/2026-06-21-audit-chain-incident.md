# Audit Chain Incident 생성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Audit Chain 위변조 의심 시 'Incident 생성'(현재 disabled placeholder)을 OPEN→RESOLVED 워크플로를 가진 정식 incident 관리 기능으로 구현한다.

**Architecture:** 전용 `security_incident` 테이블(V50)을 신설하고, admin-app 의 `AuditChainMonitorController`에 incident 생성/목록/해결 3개 엔드포인트를 추가한다. 생성 시 `AuditChainVerifier`로 위변조를 재검증하고 audit 로그 + CRITICAL `SecurityAlertEvent`를 남긴다. 프론트는 `AuditChainPage` 의 disabled 버튼을 활성화하고 하단에 Incident 섹션을 추가한다.

**Tech Stack:** Java 17 / Spring Boot / JPA(Hibernate 6) / Oracle + Flyway / React 18 + TypeScript + Vite

## Global Constraints

- 권한: incident 관련 모든 엔드포인트는 `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")` (기존 audit chain 화면과 동일).
- 시간: 모든 timestamp 는 `OffsetDateTime` + Oracle `TIMESTAMP WITH TIME ZONE` (KST 패턴). `Clock` 빈 주입해서 `clock.instant()`/`OffsetDateTime.now(clock)` 사용 — `Instant.now()`/`new Date()` 직접 호출 금지.
- UUID PK: `@JdbcTypeCode(SqlTypes.UUID)` + `@Column(columnDefinition = "RAW(16)")`. id 는 앱에서 `UUID.randomUUID()` 부여.
- 마이그레이션: 다음 자유 번호는 **V50** (현재 최신 V49). `CREATE TABLE`은 `PRAGMA EXCEPTION_INIT(e, -955)` 로 래핑(idempotent), `GRANT`는 statement 당 한 줄.
- **API 엔벨로프 주의:** `AuditChainMonitorController`는 raw POJO 를 반환한다(ApiResponse 엔벨로프 미사용). incident 엔드포인트도 raw POJO 로 반환하고, 프론트는 `api.getRaw`/`api.postRaw`를 쓴다.
- DB 검증: Flyway DDL 변경은 **반드시 Testcontainers Oracle 로 실행 검증**(inspection 이 Oracle DDL 함정을 못 잡음).
- 회귀 판정: 전체 `./gradlew build` 는 pre-existing 함정이 많아 머지 게이트로 쓰지 말 것. 모듈 단위 test + base 대조로 확정.

---

### Task 1: V50 마이그레이션 — `security_incident` 테이블

**Files:**
- Create: `core/src/main/resources/db/migration/V50__security_incident.sql`

**Interfaces:**
- Produces: 테이블 `security_incident` (컬럼 아래 SQL 참조), 부분 유니크 인덱스 `ux_incident_open_per_tenant`, APP_ADMIN 그랜트.

- [ ] **Step 1: 마이그레이션 SQL 작성**

```sql
-- ============================================================
-- V50 — security_incident (audit chain 위변조 incident 추적)
--
-- AuditChainPage 의 "Incident 생성"을 정식 incident 로 영속화.
-- OPEN→RESOLVED 워크플로. 테넌트당 OPEN 1건 제한은 함수 기반 부분
-- 유니크 인덱스로 DB 레벨 강제.
--
-- Patterns: CREATE 를 ORA-00955 EXCEPTION 으로 래핑(idempotent),
-- GRANT 는 statement 당 한 줄(V31 패턴).
-- ============================================================

-- 1. Table
DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE security_incident (
    id                  RAW(16)                  NOT NULL,
    tenant_id           RAW(16)                  NOT NULL,
    tampered_entry_id   RAW(16),
    type                VARCHAR2(64)             NOT NULL,
    severity            VARCHAR2(16)             NOT NULL,
    status              VARCHAR2(16)             NOT NULL,
    detail              VARCHAR2(1024),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by          RAW(16)                  NOT NULL,
    resolved_at         TIMESTAMP WITH TIME ZONE,
    resolved_by         RAW(16),
    resolution_note     VARCHAR2(1024),
    CONSTRAINT pk_security_incident PRIMARY KEY (id),
    CONSTRAINT ck_security_incident_status CHECK (status IN (''OPEN'',''RESOLVED''))
  )';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

-- 2. 테넌트당 OPEN 1건 강제 (함수 기반 부분 유니크 인덱스)
DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955); -- ORA-00955 name already used
  e_index_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_index_exists, -1408);  -- ORA-01408 such column list already indexed
BEGIN
  EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX ux_incident_open_per_tenant
    ON security_incident (CASE WHEN status = ''OPEN'' THEN tenant_id END)';
EXCEPTION
  WHEN e_already_exists THEN NULL;
  WHEN e_index_exists THEN NULL;
END;
/

-- 3. Grants (one per statement; APP_ADMIN 런타임 계정)
GRANT SELECT ON security_incident TO APP_ADMIN;
GRANT INSERT ON security_incident TO APP_ADMIN;
GRANT UPDATE ON security_incident TO APP_ADMIN;
```

- [ ] **Step 2: Testcontainers Oracle 로 마이그레이션 실행 검증**

Run: `./gradlew :admin-app:test --tests "*MdsSchedulerIT" --tests "*KeyRotationIT"` (Flyway 가 부팅 시 V50 적용 — 기존 IT 가 Testcontainers Oracle 로 전체 마이그레이션을 돌리므로 V50 DDL 오류가 있으면 여기서 터진다)
Expected: PASS (V50 이 깨끗이 적용됨). 만약 ORA 에러면 SQL 수정.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/db/migration/V50__security_incident.sql
git commit -m "feat(audit): V50 security_incident 테이블 + 테넌트당 OPEN 1건 부분 유니크 인덱스"
```

---

### Task 2: `SecurityIncident` 엔티티 + 리포지토리

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/SecurityIncident.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/SecurityIncidentRepository.java`

**Interfaces:**
- Consumes: 테이블 `security_incident` (Task 1).
- Produces:
  - `SecurityIncident` 엔티티 — getters: `getId():UUID`, `getTenantId():UUID`, `getTamperedEntryId():UUID`, `getType():String`, `getSeverity():String`, `getStatus():String`, `getDetail():String`, `getCreatedAt():OffsetDateTime`, `getCreatedBy():UUID`, `getResolvedAt():OffsetDateTime`, `getResolvedBy():UUID`, `getResolutionNote():String`. 정적 팩토리 `SecurityIncident.open(UUID tenantId, UUID tamperedEntryId, String detail, UUID createdBy, OffsetDateTime now):SecurityIncident`. 인스턴스 메서드 `resolve(UUID resolvedBy, String note, OffsetDateTime now):void`.
  - `SecurityIncidentRepository extends JpaRepository<SecurityIncident, UUID>` — `List<SecurityIncident> findAllByOrderByCreatedAtDesc()`, `boolean existsByTenantIdAndStatus(UUID tenantId, String status)`, `Optional<SecurityIncident> findByIdAndStatus(UUID id, String status)`.

- [ ] **Step 1: 엔티티 작성**

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * audit chain 위변조 incident. OPEN 으로 생성되어 RESOLVED 로 종결된다.
 * 테넌트당 OPEN 1건은 DB 부분 유니크 인덱스(V50)로 강제된다.
 */
@Entity
@Table(name = "SECURITY_INCIDENT")
public class SecurityIncident {

    public static final String TYPE_AUDIT_CHAIN_TAMPER = "AUDIT_CHAIN_TAMPER";
    public static final String SEVERITY_CRITICAL = "CRITICAL";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)", nullable = false, updatable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TAMPERED_ENTRY_ID", columnDefinition = "RAW(16)", updatable = false)
    private UUID tamperedEntryId;

    @Column(name = "TYPE", length = 64, nullable = false, updatable = false)
    private String type;

    @Column(name = "SEVERITY", length = 16, nullable = false, updatable = false)
    private String severity;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;

    @Column(name = "DETAIL", length = 1024, updatable = false)
    private String detail;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "CREATED_BY", columnDefinition = "RAW(16)", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "RESOLVED_AT")
    private OffsetDateTime resolvedAt;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "RESOLVED_BY", columnDefinition = "RAW(16)")
    private UUID resolvedBy;

    @Column(name = "RESOLUTION_NOTE", length = 1024)
    private String resolutionNote;

    protected SecurityIncident() {}

    public static SecurityIncident open(UUID tenantId, UUID tamperedEntryId,
                                        String detail, UUID createdBy, OffsetDateTime now) {
        SecurityIncident i = new SecurityIncident();
        i.id = UUID.randomUUID();
        i.tenantId = tenantId;
        i.tamperedEntryId = tamperedEntryId;
        i.type = TYPE_AUDIT_CHAIN_TAMPER;
        i.severity = SEVERITY_CRITICAL;
        i.status = STATUS_OPEN;
        i.detail = detail;
        i.createdAt = now;
        i.createdBy = createdBy;
        return i;
    }

    /** OPEN → RESOLVED. 호출 전 상태 검증은 서비스 책임. */
    public void resolve(UUID resolvedByUser, String note, OffsetDateTime now) {
        this.status = STATUS_RESOLVED;
        this.resolvedBy = resolvedByUser;
        this.resolutionNote = note;
        this.resolvedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getTamperedEntryId() { return tamperedEntryId; }
    public String getType() { return type; }
    public String getSeverity() { return severity; }
    public String getStatus() { return status; }
    public String getDetail() { return detail; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public UUID getResolvedBy() { return resolvedBy; }
    public String getResolutionNote() { return resolutionNote; }
}
```

- [ ] **Step 2: 리포지토리 작성**

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.SecurityIncident;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SecurityIncidentRepository extends JpaRepository<SecurityIncident, UUID> {
    List<SecurityIncident> findAllByOrderByCreatedAtDesc();
    boolean existsByTenantIdAndStatus(UUID tenantId, String status);
    Optional<SecurityIncident> findByIdAndStatus(UUID id, String status);
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/SecurityIncident.java \
        core/src/main/java/com/crosscert/passkey/core/repository/SecurityIncidentRepository.java
git commit -m "feat(audit): SecurityIncident 엔티티 + 리포지토리"
```

---

### Task 3: `SecurityAlertEvent` 에 AUDIT_CHAIN_TAMPERING 타입 추가

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/alert/SecurityAlertEvent.java` (AlertType enum)

**Interfaces:**
- Produces: `SecurityAlertEvent.AlertType.AUDIT_CHAIN_TAMPERING` enum 상수.

- [ ] **Step 1: enum 에 상수 추가**

`AlertType` enum(현재: API_KEY_BRUTE_FORCE, COUNTER_REGRESSION, TENANT_BOUNDARY_VIOLATION, ADMIN_LOGIN_FAILURE, MDS_SYNC_FAILURE)에 한 줄 추가:

```java
    public enum AlertType {
        API_KEY_BRUTE_FORCE,
        COUNTER_REGRESSION,
        TENANT_BOUNDARY_VIOLATION,
        ADMIN_LOGIN_FAILURE,
        MDS_SYNC_FAILURE,
        AUDIT_CHAIN_TAMPERING
    }
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/alert/SecurityAlertEvent.java
git commit -m "feat(alert): AUDIT_CHAIN_TAMPERING alert 타입 추가"
```

---

### Task 4: `SecurityIncidentService` — 생성/해결/목록 (단위 테스트 우선)

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/SecurityIncidentService.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/IncidentConflictException.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/IncidentNotTamperedException.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/audit/SecurityIncidentServiceTest.java`

**Interfaces:**
- Consumes: `SecurityIncidentRepository` (Task 2), `SecurityIncident` 정적 팩토리/resolve (Task 2), `AuditChainVerifier.verifyTenant(UUID):TenantResult` (with `.ok():boolean`), `AuditLogService.append(AuditAppendRequest)`, `SecurityAlertEvent` + `ApplicationEventPublisher`, `Clock`, `TenantRepository.findById`.
- Produces:
  - `SecurityIncidentService.create(UUID tenantId, UUID tamperedEntryId, UUID actorId, String actorEmail):SecurityIncident`
  - `SecurityIncidentService.resolve(UUID incidentId, String note, UUID actorId, String actorEmail):SecurityIncident`
  - `SecurityIncidentService.list():List<SecurityIncident>`
  - `IncidentConflictException` (→ 409), `IncidentNotTamperedException` (→ 422), 둘 다 `RuntimeException`.

- [ ] **Step 1: 예외 클래스 2개 작성**

```java
// IncidentConflictException.java
package com.crosscert.passkey.admin.audit;

/** 테넌트에 이미 OPEN incident 가 있을 때. → HTTP 409. */
public class IncidentConflictException extends RuntimeException {
    public IncidentConflictException(String message) { super(message); }
}
```

```java
// IncidentNotTamperedException.java
package com.crosscert.passkey.admin.audit;

/** incident 생성 요청 시 해당 테넌트가 실제로는 위변조 상태가 아닐 때. → HTTP 422. */
public class IncidentNotTamperedException extends RuntimeException {
    public IncidentNotTamperedException(String message) { super(message); }
}
```

- [ ] **Step 2: 실패하는 단위 테스트 작성**

```java
package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.alert.SecurityAlertEvent;
import com.crosscert.passkey.core.entity.SecurityIncident;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.SecurityIncidentRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.*;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SecurityIncidentServiceTest {

    SecurityIncidentRepository repo;
    AuditChainVerifier verifier;
    AuditLogService audit;
    ApplicationEventPublisher events;
    TenantRepository tenants;
    Clock clock;
    SecurityIncidentService svc;

    final UUID TENANT = UUID.randomUUID();
    final UUID ENTRY = UUID.randomUUID();
    final UUID ACTOR = UUID.randomUUID();

    @BeforeEach
    void setup() {
        repo = mock(SecurityIncidentRepository.class);
        verifier = mock(AuditChainVerifier.class);
        audit = mock(AuditLogService.class);
        events = mock(ApplicationEventPublisher.class);
        tenants = mock(TenantRepository.class);
        clock = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.ofHours(9));
        svc = new SecurityIncidentService(repo, verifier, audit, events, tenants, clock);

        Tenant t = mock(Tenant.class);
        when(t.getDisplayName()).thenReturn("Acme Corp");
        when(tenants.findById(TENANT)).thenReturn(Optional.of(t));
        when(repo.save(any(SecurityIncident.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubTampered(boolean tampered) {
        AuditChainVerifier.TenantResult r = mock(AuditChainVerifier.TenantResult.class);
        when(r.ok()).thenReturn(!tampered);
        when(verifier.verifyTenant(TENANT)).thenReturn(r);
    }

    @Test
    void create_whenTampered_savesAuditsAndPublishesAlert() {
        stubTampered(true);
        when(repo.existsByTenantIdAndStatus(TENANT, "OPEN")).thenReturn(false);

        SecurityIncident i = svc.create(TENANT, ENTRY, ACTOR, "alice@crosscert.com");

        assertThat(i.getStatus()).isEqualTo("OPEN");
        assertThat(i.getTenantId()).isEqualTo(TENANT);
        verify(repo).save(any(SecurityIncident.class));
        verify(audit).append(any());
        verify(events).publishEvent(any(SecurityAlertEvent.class));
    }

    @Test
    void create_whenAlreadyOpen_throwsConflict() {
        stubTampered(true);
        when(repo.existsByTenantIdAndStatus(TENANT, "OPEN")).thenReturn(true);

        assertThatThrownBy(() -> svc.create(TENANT, ENTRY, ACTOR, "alice@crosscert.com"))
                .isInstanceOf(IncidentConflictException.class);
        verify(repo, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void create_whenNotActuallyTampered_throwsUnprocessable() {
        stubTampered(false);

        assertThatThrownBy(() -> svc.create(TENANT, ENTRY, ACTOR, "alice@crosscert.com"))
                .isInstanceOf(IncidentNotTamperedException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void resolve_whenOpen_setsResolvedFields() {
        SecurityIncident open = SecurityIncident.open(TENANT, ENTRY, "{}", ACTOR,
                OffsetDateTime.now(clock));
        when(repo.findByIdAndStatus(open.getId(), "OPEN")).thenReturn(Optional.of(open));

        SecurityIncident resolved = svc.resolve(open.getId(), "DBA 복구 완료", ACTOR, "alice@crosscert.com");

        assertThat(resolved.getStatus()).isEqualTo("RESOLVED");
        assertThat(resolved.getResolutionNote()).isEqualTo("DBA 복구 완료");
        assertThat(resolved.getResolvedAt()).isNotNull();
        verify(audit).append(any());
    }

    @Test
    void resolve_whenNotOpen_throwsConflict() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndStatus(id, "OPEN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.resolve(id, "note", ACTOR, "alice@crosscert.com"))
                .isInstanceOf(IncidentConflictException.class);
    }
}
```

- [ ] **Step 3: 테스트 실행 → 실패 확인**

Run: `./gradlew :admin-app:test --tests "*SecurityIncidentServiceTest"`
Expected: FAIL (SecurityIncidentService 없음 — 컴파일 에러)

- [ ] **Step 4: 서비스 구현**

```java
package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.alert.SecurityAlertEvent;
import com.crosscert.passkey.core.entity.SecurityIncident;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.SecurityIncidentRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SecurityIncidentService {

    private final SecurityIncidentRepository repo;
    private final AuditChainVerifier verifier;
    private final AuditLogService audit;
    private final ApplicationEventPublisher events;
    private final TenantRepository tenants;
    private final Clock clock;

    public SecurityIncidentService(SecurityIncidentRepository repo,
                                   AuditChainVerifier verifier,
                                   AuditLogService audit,
                                   ApplicationEventPublisher events,
                                   TenantRepository tenants,
                                   Clock clock) {
        this.repo = repo;
        this.verifier = verifier;
        this.audit = audit;
        this.events = events;
        this.tenants = tenants;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SecurityIncident> list() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public SecurityIncident create(UUID tenantId, UUID tamperedEntryId,
                                   UUID actorId, String actorEmail) {
        // 1. 위변조 실재 재검증 — 위조 요청으로 가짜 incident 생성 방지.
        var r = verifier.verifyTenant(tenantId);
        if (r.ok()) {
            throw new IncidentNotTamperedException(
                    "tenant chain is intact; cannot create incident: " + tenantId);
        }
        // 2. 테넌트당 OPEN 1건 선체크(친절한 409). DB 유니크 인덱스가 최종 방어.
        if (repo.existsByTenantIdAndStatus(tenantId, SecurityIncident.STATUS_OPEN)) {
            throw new IncidentConflictException("open incident already exists for tenant: " + tenantId);
        }
        // 3. 스냅샷 detail + 저장
        String tenantName = tenants.findById(tenantId).map(Tenant::getDisplayName).orElse(tenantId.toString());
        String detail = "{\"tenantName\":\"" + tenantName.replace("\"", "\\\"")
                + "\",\"tamperedEntryId\":\"" + (tamperedEntryId == null ? "" : tamperedEntryId) + "\"}";
        OffsetDateTime now = OffsetDateTime.now(clock);
        SecurityIncident incident = SecurityIncident.open(tenantId, tamperedEntryId, detail, actorId, now);
        try {
            repo.save(incident);
        } catch (DataIntegrityViolationException e) {
            // 부분 유니크 인덱스 위반(동시 생성 경쟁) → 409 로 변환.
            throw new IncidentConflictException("open incident already exists for tenant: " + tenantId);
        }
        // 4. audit append
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "AUDIT_CHAIN_INCIDENT_CREATED",
                "SECURITY_INCIDENT", incident.getId().toString(), tenantId,
                Map.of("tamperedEntryId", tamperedEntryId == null ? "" : tamperedEntryId.toString())));
        // 5. CRITICAL 알림
        events.publishEvent(new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.AUDIT_CHAIN_TAMPERING,
                SecurityAlertEvent.Severity.CRITICAL,
                "audit chain incident created",
                Map.of("tenantId", tenantId.toString(),
                       "incidentId", incident.getId().toString())));
        return incident;
    }

    @Transactional
    public SecurityIncident resolve(UUID incidentId, String note, UUID actorId, String actorEmail) {
        SecurityIncident incident = repo.findByIdAndStatus(incidentId, SecurityIncident.STATUS_OPEN)
                .orElseThrow(() -> new IncidentConflictException(
                        "no open incident with id: " + incidentId));
        incident.resolve(actorId, note, OffsetDateTime.now(clock));
        repo.save(incident);
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "AUDIT_CHAIN_INCIDENT_RESOLVED",
                "SECURITY_INCIDENT", incident.getId().toString(), incident.getTenantId(),
                Map.of("note", note)));
        return incident;
    }
}
```

> **주의:** `SecurityAlertEvent`/`SecurityAlertEvent.Severity` 의 정확한 생성자 시그니처는 Task 시작 시 `SecurityAlertEvent.java` 를 열어 확인하고, 위 `publishEvent(new SecurityAlertEvent(type, severity, summary, contextMap))` 호출을 실제 생성자에 맞춰 조정한다(현재 record: `AlertType type, Severity severity, ...`). `AuditAppendRequest` 7-필드 시그니처는 본 plan §Task4 Interfaces 와 일치.

> **Codex 리뷰(Task 2) 반영 — 이 Task에서 반드시 처리할 것:**
> 1. **note 검증 (서비스 레이어 가드):** `resolve()` 진입 시 `note` 가 null 또는 blank 이면
>    `IllegalArgumentException`(또는 400 매핑되는 검증 예외)을 던져라. 엔티티 `resolve()` 가
>    그대로 RESOLVED 로 바꾸면 `ck_security_incident_resolution`(RESOLVED 면 resolution_note
>    NOT NULL)이 flush 시점에 ORA-02290 으로 터지므로, 서비스에서 선제 차단한다. 컨트롤러 DTO
>    `@NotBlank` 와 **이중 방어**(서비스 직접 호출/테스트 경로 대비).
> 2. **resolve 동시성 (race) — 단일 원자 UPDATE:** 위 `findByIdAndStatus`→`save` 2단계는 두
>    운영자가 동시에 같은 OPEN incident 를 resolve 하면 나중 flush 가 앞 값을 덮어쓴다.
>    이를 막기 위해 리포지토리에 **조건부 원자 UPDATE** 를 추가하고 resolve 가 이를 쓰게 한다:
>    ```java
>    // SecurityIncidentRepository 에 추가
>    @Modifying
>    @Query("update SecurityIncident i set i.status='RESOLVED', i.resolvedBy=:by, " +
>           "i.resolutionNote=:note, i.resolvedAt=:at " +
>           "where i.id=:id and i.status='OPEN'")
>    int resolveIfOpen(@Param("id") UUID id, @Param("by") UUID by,
>                      @Param("note") String note, @Param("at") OffsetDateTime at);
>    ```
>    서비스 `resolve()` 는 `int updated = repo.resolveIfOpen(...)` 후 `updated == 0` 이면
>    `IncidentConflictException`("no open incident ...") 을 던진다(이미 RESOLVED 됐거나 없음 →
>    race 의 패자도 여기로 떨어져 안전). UPDATE 가 1행이면 그 후 `findById` 로 다시 읽어 audit
>    append + 반환에 쓴다. 이렇게 하면 `WHERE status='OPEN'` 가 DB 레벨에서 race 를 직렬화한다.
>    (참고: 생성 경로의 race 는 V50 부분 유니크 인덱스가 이미 막으므로 추가 조치 불필요 — create
>    의 `DataIntegrityViolationException`→409 변환이 그 방어다.)
> 3. **거부된 Codex 지적(기록):** Codex 가 P1 으로 든 "리포 메서드에 tenantId 없음"(전역 조회)은
>    spec §VPD(운영자가 전 테넌트 incident 를 봐야 함, PLATFORM_OPERATOR 전용 플랫폼 테이블)에
>    따라 **의도된 동작**이라 수정하지 않는다. RP_ADMIN 노출 경로는 없다(컨트롤러 전체
>    `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")`).

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `./gradlew :admin-app:test --tests "*SecurityIncidentServiceTest"`
Expected: PASS (5 tests). 실패하면 alert 생성자 시그니처/타입 불일치 우선 의심.

- [ ] **Step 6: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/audit/SecurityIncidentService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/audit/IncidentConflictException.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/audit/IncidentNotTamperedException.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/audit/SecurityIncidentServiceTest.java
git commit -m "feat(audit): SecurityIncidentService — 생성(위변조 재검증)/해결/목록 + 단위 테스트"
```

---

### Task 5: 컨트롤러 엔드포인트 3개 + DTO + 예외 매핑

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainMonitorController.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/IncidentDto.java`
- Modify: admin-app 의 글로벌 예외 핸들러 (있으면) — `IncidentConflictException`→409, `IncidentNotTamperedException`→422 매핑. 없으면 컨트롤러에 `@ExceptionHandler` 추가.
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/audit/IncidentControllerSecurityTest.java`

**Interfaces:**
- Consumes: `SecurityIncidentService` (Task 4), `AdminUserRepository.findByEmail(...).getId()`, `Authentication`.
- Produces:
  - `GET  /admin/api/audit/chain/incidents` → `List<IncidentDto.IncidentView>`
  - `POST /admin/api/audit/chain/incidents` (body `IncidentDto.CreateRequest{tenantId:String, tamperedEntryId:String?}`) → `IncidentDto.IncidentView`
  - `POST /admin/api/audit/chain/incidents/{id}/resolve` (body `IncidentDto.ResolveRequest{note:String @NotBlank}`) → `IncidentDto.IncidentView`
  - `IncidentDto.IncidentView` 필드: `id, tenantId, tenantName, tamperedEntryId, type, severity, status, detail, createdAt, createdByEmail, resolvedAt, resolvedByEmail, resolutionNote` (모두 String 또는 nullable String; createdAt/resolvedAt 은 ISO 문자열).

- [ ] **Step 1: DTO 작성**

```java
package com.crosscert.passkey.admin.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class IncidentDto {
    private IncidentDto() {}

    public record CreateRequest(@NotBlank String tenantId, String tamperedEntryId) {}

    public record ResolveRequest(@NotBlank @Size(max = 1024) String note) {}

    public record IncidentView(
            String id, String tenantId, String tenantName, String tamperedEntryId,
            String type, String severity, String status, String detail,
            String createdAt, String createdByEmail,
            String resolvedAt, String resolvedByEmail, String resolutionNote) {}
}
```

- [ ] **Step 2: 권한/happy-path 통합 테스트 작성 (실패 확인)**

기존 audit chain 통합 테스트 패턴(예: 같은 디렉토리의 컨트롤러 테스트)을 찾아 그 부트 구성을 그대로 따른다. 핵심 단언:
- 익명 → `POST /admin/api/audit/chain/incidents` 403
- RP_ADMIN → 403
- PLATFORM_OPERATOR → 위변조 없는 테넌트면 422, 있으면 생성 후 목록에 1건, resolve 후 RESOLVED

```java
// IncidentControllerSecurityTest — 기존 audit chain 통합/슬라이스 테스트의
// @SpringBootTest 또는 @WebMvcTest 구성(있는 MockBean 세트 포함)을 복제해 작성.
// 최소: 권한 게이팅 3케이스 + 생성→목록→해결 happy path 1케이스.
// (구체 부트 애너테이션은 Task 시작 시 같은 패키지의 기존 테스트에서 확인해 맞춘다.)
```

> **주의:** admin-app 슬라이스 테스트는 `AdminSecurityConfig` 의 새 빈 누락으로 컨텍스트 로드가 깨지기 쉽다(메모리). 기존 통과하는 audit chain 테스트의 MockBean 세트를 그대로 복제할 것.

- [ ] **Step 3: 컨트롤러에 엔드포인트 + 매핑 헬퍼 추가**

`AuditChainMonitorController` 에 `SecurityIncidentService incidents` 와 `AdminUserRepository admins` 를 생성자 주입에 추가하고, 아래 메서드 추가:

```java
    @GetMapping("/incidents")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public List<IncidentDto.IncidentView> listIncidents() {
        return incidents.list().stream().map(this::toView).toList();
    }

    @PostMapping("/incidents")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public IncidentDto.IncidentView createIncident(@Valid @RequestBody IncidentDto.CreateRequest req,
                                                   Authentication auth) {
        UUID actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        UUID tenantId = UUID.fromString(req.tenantId());
        UUID entryId = (req.tamperedEntryId() == null || req.tamperedEntryId().isBlank())
                ? null : UUID.fromString(req.tamperedEntryId());
        return toView(incidents.create(tenantId, entryId, actorId, auth.getName()));
    }

    @PostMapping("/incidents/{id}/resolve")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public IncidentDto.IncidentView resolveIncident(@PathVariable String id,
                                                    @Valid @RequestBody IncidentDto.ResolveRequest req,
                                                    Authentication auth) {
        UUID actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        return toView(incidents.resolve(UUID.fromString(id), req.note(), actorId, auth.getName()));
    }

    @ExceptionHandler(IncidentConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> onConflict(IncidentConflictException e) {
        return Map.of("error", "conflict", "message", e.getMessage());
    }

    @ExceptionHandler(IncidentNotTamperedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> onNotTampered(IncidentNotTamperedException e) {
        return Map.of("error", "not_tampered", "message", e.getMessage());
    }

    private IncidentDto.IncidentView toView(com.crosscert.passkey.core.entity.SecurityIncident i) {
        String tenantName = tenantRepo.findById(i.getTenantId())
                .map(com.crosscert.passkey.core.entity.Tenant::getDisplayName)
                .orElse(i.getTenantId().toString());
        String createdByEmail = admins.findById(i.getCreatedBy()).map(a -> a.getEmail()).orElse("—");
        String resolvedByEmail = i.getResolvedBy() == null ? null
                : admins.findById(i.getResolvedBy()).map(a -> a.getEmail()).orElse("—");
        return new IncidentDto.IncidentView(
                i.getId().toString(), i.getTenantId().toString(), tenantName,
                i.getTamperedEntryId() == null ? null : i.getTamperedEntryId().toString(),
                i.getType(), i.getSeverity(), i.getStatus(), i.getDetail(),
                i.getCreatedAt().toString(), createdByEmail,
                i.getResolvedAt() == null ? null : i.getResolvedAt().toString(),
                resolvedByEmail, i.getResolutionNote());
    }
```

필요한 import 추가: `org.springframework.http.HttpStatus`, `org.springframework.security.core.Authentication`, `jakarta.validation.Valid`, `com.crosscert.passkey.core.repository.AdminUserRepository`. `AdminUserRepository` 의 `getEmail()`/`getId()` 접근자명은 엔티티에서 확인.

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew :admin-app:test --tests "*IncidentControllerSecurityTest" --tests "*SecurityIncidentServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainMonitorController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/audit/IncidentDto.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/audit/IncidentControllerSecurityTest.java
git commit -m "feat(audit): incident 생성/목록/해결 엔드포인트 + 409/422 매핑 + 권한 테스트"
```

---

### Task 6: 프론트엔드 — API 클라이언트 + 버튼 활성화 + Incident 섹션

**Files:**
- Modify: `admin-ui/src/api/auditChainMonitor.ts` (타입 + 메서드 3개)
- Modify: `admin-ui/src/pages/AuditChainPage.tsx` (버튼 활성화 + 확인 다이얼로그 + Incident 섹션 + 해결 다이얼로그)

**Interfaces:**
- Consumes: 백엔드 엔드포인트 3개 (Task 5), `api.getRaw`/`api.postRaw`, 기존 `Dialog`(shell), `useToast`, `Icons`.
- Produces:
  - `auditChainMonitorApi.listIncidents():Promise<IncidentView[]>`
  - `auditChainMonitorApi.createIncident(tenantId:string, tamperedEntryId?:string|null):Promise<IncidentView>`
  - `auditChainMonitorApi.resolveIncident(id:string, note:string):Promise<IncidentView>`
  - `IncidentView` 타입(필드: Task 5 IncidentView 와 1:1).

- [ ] **Step 1: API 클라이언트 타입 + 메서드 추가**

`auditChainMonitor.ts` 의 `auditChainMonitorApi` 객체에 추가하고, 타입을 파일에 추가:

```ts
export type IncidentView = {
  id: string;
  tenantId: string;
  tenantName: string;
  tamperedEntryId: string | null;
  type: string;
  severity: string;
  status: 'OPEN' | 'RESOLVED';
  detail: string | null;
  createdAt: string;
  createdByEmail: string;
  resolvedAt: string | null;
  resolvedByEmail: string | null;
  resolutionNote: string | null;
};

// auditChainMonitorApi 객체 안에 추가:
//   listIncidents: () => api.getRaw<IncidentView[]>('/admin/api/audit/chain/incidents'),
//   createIncident: (tenantId, tamperedEntryId) =>
//       api.postRaw<IncidentView>('/admin/api/audit/chain/incidents',
//           { tenantId, tamperedEntryId: tamperedEntryId ?? null }),
//   resolveIncident: (id, note) =>
//       api.postRaw<IncidentView>(`/admin/api/audit/chain/incidents/${id}/resolve`, { note }),
```

실제 추가 코드:
```ts
  listIncidents: async (): Promise<IncidentView[]> => {
    return api.getRaw<IncidentView[]>('/admin/api/audit/chain/incidents');
  },
  createIncident: async (tenantId: string, tamperedEntryId?: string | null): Promise<IncidentView> => {
    return api.postRaw<IncidentView>('/admin/api/audit/chain/incidents',
      { tenantId, tamperedEntryId: tamperedEntryId ?? null });
  },
  resolveIncident: async (id: string, note: string): Promise<IncidentView> => {
    return api.postRaw<IncidentView>(`/admin/api/audit/chain/incidents/${id}/resolve`, { note });
  },
```

- [ ] **Step 2: 타입체크**

Run: `cd admin-ui && npx tsc -b`
Expected: exit 0

- [ ] **Step 3: AuditChainPage — incident state + 로딩**

`AuditChainPage` 컴포넌트에:
- import: `import { auditChainMonitorApi, type IncidentView } from '@/api/auditChainMonitor';` (이미 일부 import 됐으면 병합)
- state 추가: `const [incidents, setIncidents] = useState<IncidentView[]>([]);`, `const [creating, setCreating] = useState(false);`, `const [confirmCreate, setConfirmCreate] = useState(false);`, `const [resolving, setResolving] = useState<IncidentView | null>(null);`, `const [resolveNote, setResolveNote] = useState('');`
- `load()` 안에서 overview 로드 후 `auditChainMonitorApi.listIncidents().then(setIncidents).catch(()=>setIncidents([]))` 호출(또는 별도 effect).

- [ ] **Step 4: "Incident 생성" 버튼 활성화 (AuditChainPage.tsx:250)**

기존:
```tsx
<button className="btn btn--danger btn--sm" disabled title="향후 지원 예정"><Icons.Alert size={12} /> Incident 생성</button>
```
교체:
```tsx
<button className="btn btn--danger btn--sm" onClick={() => setConfirmCreate(true)} disabled={creating}>
  <Icons.Alert size={12} /> Incident 생성
</button>
```

확인 다이얼로그(페이지 JSX 하단, 기존 Dialog 패턴 사용. `tamperedTenant` 가 null 이면 렌더 안 함):
```tsx
{confirmCreate && tamperedTenant && (
  <Dialog title="Incident 생성" onClose={() => setConfirmCreate(false)}>
    <div style={{ fontSize: 13 }}>
      <p><strong>{tamperedTenant.tenantName}</strong> tenant 의 audit chain 위변조를 정식 incident 로 등록합니다.</p>
      {tamperedTenant.tamperedEntryId && (
        <p className="muted mono" style={{ fontSize: 12 }}>대상 row: {tamperedTenant.tamperedEntryId}</p>
      )}
    </div>
    <div className="row" style={{ justifyContent: 'flex-end', gap: 8, marginTop: 16 }}>
      <button className="btn" onClick={() => setConfirmCreate(false)}>취소</button>
      <button className="btn btn--danger" disabled={creating} onClick={async () => {
        if (!tamperedTenant.tenantId) return;
        setCreating(true);
        try {
          await auditChainMonitorApi.createIncident(tamperedTenant.tenantId, tamperedTenant.tamperedEntryId);
          toast({ kind: 'ok', title: 'Incident 생성됨' });
          setConfirmCreate(false);
          await load();
        } catch (e: unknown) {
          const msg = e instanceof Error ? e.message : '오류';
          toast({ kind: 'err', title: 'Incident 생성 실패', message: msg });
        } finally { setCreating(false); }
      }}>생성</button>
    </div>
  </Dialog>
)}
```
> `Dialog` 의 정확한 props(title/onClose 시그니처)는 기존 사용처(예: AuditChainPage 의 월간보고서 모달, 또는 `@/shell/Dialog`)에서 확인해 맞춘다.

- [ ] **Step 5: Incident 섹션 (페이지 하단, tenant 목록 카드 아래)**

```tsx
<div className="card" style={{ marginTop: 20 }}>
  <div className="card__head"><h3 className="card__title">Incident</h3></div>
  {incidents.length === 0 ? (
    <div style={{ padding: 16, color: 'var(--text-mute)', fontSize: 13 }}>등록된 incident가 없습니다.</div>
  ) : (
    <table className="table">
      <thead><tr><th>상태</th><th>테넌트</th><th>유형</th><th>생성</th><th>생성자</th><th></th></tr></thead>
      <tbody>
        {incidents.map((inc) => (
          <tr key={inc.id}>
            <td>{inc.status === 'OPEN'
              ? <span className="badge badge--danger badge--dot">OPEN</span>
              : <span className="badge badge--dot">RESOLVED</span>}</td>
            <td>{inc.tenantName}</td>
            <td className="muted">{inc.type}</td>
            <td className="muted" style={{ fontSize: 12 }}>{new Date(inc.createdAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}</td>
            <td className="muted" style={{ fontSize: 12 }}>{inc.createdByEmail}</td>
            <td style={{ textAlign: 'right' }}>
              {inc.status === 'OPEN' && (
                <button className="btn btn--xs" onClick={() => { setResolving(inc); setResolveNote(''); }}>해결</button>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )}
</div>
```

- [ ] **Step 6: 해결 다이얼로그 (메모 필수)**

```tsx
{resolving && (
  <Dialog title="Incident 해결" onClose={() => setResolving(null)}>
    <div style={{ fontSize: 13 }}>
      <p><strong>{resolving.tenantName}</strong> incident 를 해결 처리합니다.</p>
      <label className="label">해결 메모 (필수)</label>
      <textarea className="input" rows={3} value={resolveNote}
        onChange={(e) => setResolveNote(e.target.value)}
        placeholder="조치 내용 / 해결 사유" />
    </div>
    <div className="row" style={{ justifyContent: 'flex-end', gap: 8, marginTop: 16 }}>
      <button className="btn" onClick={() => setResolving(null)}>취소</button>
      <button className="btn btn--primary" disabled={!resolveNote.trim()} onClick={async () => {
        try {
          await auditChainMonitorApi.resolveIncident(resolving.id, resolveNote.trim());
          toast({ kind: 'ok', title: 'Incident 해결됨' });
          setResolving(null);
          await load();
        } catch (e: unknown) {
          const msg = e instanceof Error ? e.message : '오류';
          toast({ kind: 'err', title: '해결 실패', message: msg });
        }
      }}>해결 처리</button>
    </div>
  </Dialog>
)}
```

- [ ] **Step 7: 타입체크 + 린트 + 빌드**

Run: `cd admin-ui && npx tsc -b && npx eslint src/pages/AuditChainPage.tsx src/api/auditChainMonitor.ts && npm run build`
Expected: tsc exit 0 / eslint 에러 0(기존 warning 제외) / build 성공

- [ ] **Step 8: Commit**

```bash
git add admin-ui/src/api/auditChainMonitor.ts admin-ui/src/pages/AuditChainPage.tsx
git commit -m "feat(audit-ui): Incident 생성 버튼 활성화 + Incident 섹션(목록/해결)"
```

---

### Task 7: 통합 검증 (실DB e2e + dogfooding)

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: admin-app 모듈 테스트 전체**

Run: `./gradlew :admin-app:test --tests "*Incident*" --tests "*AuditChain*"`
Expected: PASS. base worktree 와 대조해 신규 실패만 판정.

- [ ] **Step 2: 실DB 기동 + e2e (수동/dogfooding)**

- admin-app dev 프로필 기동(8081) + admin-ui(5173).
- audit_log 한 행을 의도적으로 변조(hash 깨기)해 위변조 상태 유발 → AuditChainPage 빨간 경고 카드 확인.
- "Incident 생성" → 확인 다이얼로그 → 생성 → 하단 Incident 섹션에 OPEN 1건.
- 같은 테넌트에 다시 "Incident 생성" → 409 ("이미 진행 중") 토스트 확인.
- "해결" → 메모 입력 → RESOLVED 전환 확인. 메모 빈값이면 버튼 비활성 확인.
- audit_log 에 `AUDIT_CHAIN_INCIDENT_CREATED` / `_RESOLVED` 기록 확인.
- 로그에 `SECURITY_ALERT ... AUDIT_CHAIN_TAMPERING ... CRITICAL` 확인.

- [ ] **Step 3: 마무리 — finishing-a-development-branch 스킬로 머지/PR 결정**

---

## Self-Review

**1. Spec coverage:**
- §3 데이터 모델 → Task 1 (테이블) + Task 2 (엔티티). 부분 유니크 인덱스 ✓
- §4 백엔드(서비스/엔드포인트/alert/재검증/에러) → Task 3,4,5 ✓
- §5 프론트(API/버튼/섹션/해결) → Task 6 ✓
- §6 테스트(OPEN중복/가짜차단/권한/DDL) → Task 4(단위), Task 5(권한), Task 1(Testcontainers DDL) ✓
- §7 범위경계(YAGNI) → 추가 상태/assignee/ITSM 없음, 계획에 미포함 ✓

**2. Placeholder scan:** 코드 스텝은 전부 실제 코드 포함. "기존 패턴 확인" 주석이 2곳(Task5 테스트 부트 구성, Task6 Dialog props) — 이는 코드베이스 의존 사실 확인 지시로, 추측 방지 목적. 나머지 placeholder 없음.

**3. Type consistency:**
- `SecurityIncident.open(tenantId, tamperedEntryId, detail, createdBy, now)` / `.resolve(resolvedBy, note, now)` — Task 2 정의 ↔ Task 4 사용 일치 ✓
- 서비스 시그니처 `create(tenantId, tamperedEntryId, actorId, actorEmail)` / `resolve(incidentId, note, actorId, actorEmail)` — Task 4 ↔ Task 5 호출 일치 ✓
- `IncidentView` 13필드 — Task 5(백엔드) ↔ Task 6(프론트) 1:1 일치 ✓
- 상태 문자열 "OPEN"/"RESOLVED" — 엔티티 상수 ↔ 리포지토리 쿼리 인자 일치 ✓

**알려진 확인 지점(구현 시 코드베이스에서 확정):**
- `SecurityAlertEvent` 생성자 시그니처(summary/context 인자 형태) — Task 4 Step 4 주석에 명시.
- admin-app 글로벌 예외 핸들러 존재 여부 — 있으면 거기, 없으면 컨트롤러 `@ExceptionHandler`(Task 5).
- `AdminUserRepository` 의 `getEmail()` 접근자명.
- `Dialog` shell 컴포넌트 props.
