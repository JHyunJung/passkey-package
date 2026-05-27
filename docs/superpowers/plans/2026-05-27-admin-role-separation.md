# Admin Role 분리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin-app 의 role 모델을 `ADMIN`/`VIEWER` 평면에서 `PLATFORM_OPERATOR`/`RP_ADMIN` + tenant scoping 으로 분리. PLATFORM_OPERATOR 는 무제한, RP_ADMIN 은 자기 tenant 만 접근.

**Architecture:** worktree 안에서 진행. DB(V23): admin_user.tenant_id FK + role CHECK 변경 + 결정적 demo tenant idempotent insert + bob → RP_ADMIN. Spring Security: AdminUserDetails (UserDetails 구현체) + TenantBoundary helper. 8 endpoint 의 @PreAuthorize 매트릭스 + service boundary 호출 패턴. admin-ui: MeContext 도입 + Login 후 role 라우팅 + Sidebar conditional + PlatformOnlyGuard. 자동 IT 2 개 (RpAdminBoundaryIT + PlatformOperatorUnrestrictedIT) + 5 ControllerSecurityTest 갱신.

**Tech Stack:**
- Backend: Spring Boot 3.5, Spring Security 6, Spring Data JPA, Oracle + Flyway
- Frontend: React 18 + Vite + TypeScript, react-router-dom 6, 기존 Toast/Dialog 재사용
- Testing: Testcontainers Oracle XE + Redis (기존 AdminFlowIT 패턴 재사용)

**Spec:** `docs/superpowers/specs/2026-05-27-admin-role-separation-design.md`

**Worktree note:** 모든 경로는 worktree 루트 `.claude/worktrees/admin-role-separation` 기준. 마지막 task 에서 `git merge --no-ff` 로 main 도착.

---

## Task 1: V23 마이그레이션 — admin_user tenant_id FK + role enum

**Files:**
- Create: `core/src/main/resources/db/migration/V23__admin_role_separation.sql`

- [ ] **Step 1: V23 SQL 작성**

`core/src/main/resources/db/migration/V23__admin_role_separation.sql`:

```sql
-- ============================================================
-- V23 — admin_user role 모델 확장 (ADMIN/VIEWER → PLATFORM_OPERATOR/RP_ADMIN)
-- + tenant_id FK 추가 (RP_ADMIN 의 자기 tenant 매핑)
-- ============================================================

-- 1. tenant_id 컬럼 추가 (RAW(16) NULL) — RP_ADMIN 만 채움
ALTER TABLE admin_user ADD (
  tenant_id RAW(16)
);

-- 2. FK to tenant (cascade restrict — tenant 삭제 전 admin 정리 필요)
ALTER TABLE admin_user ADD CONSTRAINT fk_admin_user_tenant
  FOREIGN KEY (tenant_id) REFERENCES tenant(id);

-- 3. 인덱스: RP_ADMIN 의 같은 tenant 조회 효율화
CREATE INDEX ix_admin_user_tenant ON admin_user (tenant_id);

-- 4. 기존 ADMIN/VIEWER → PLATFORM_OPERATOR 일괄 UPDATE
UPDATE admin_user SET role = 'PLATFORM_OPERATOR' WHERE role IN ('ADMIN', 'VIEWER');

-- 5. 기존 CHECK 제약 제거
ALTER TABLE admin_user DROP CONSTRAINT ck_admin_user_role;

-- 6. 신규 CHECK — role enum 2 종
ALTER TABLE admin_user ADD CONSTRAINT ck_admin_user_role
  CHECK (role IN ('PLATFORM_OPERATOR', 'RP_ADMIN'));

-- 7. role <-> tenant_id invariant
ALTER TABLE admin_user ADD CONSTRAINT ck_admin_user_role_tenant
  CHECK (
    (role = 'PLATFORM_OPERATOR' AND tenant_id IS NULL)
    OR
    (role = 'RP_ADMIN' AND tenant_id IS NOT NULL)
  );

-- 8. demo tenant 신규 — bob 이 소속될 RP. 결정적 UUID 로 idempotent.
--    NOT EXISTS 가드는 (id, slug) 둘 다 체크 — 운영 환경에 'demo-rp' slug 가
--    다른 id 로 이미 있으면 INSERT skip (unique 위반 방지).
DECLARE
  v_demo_tenant_id RAW(16) := HEXTORAW('0000000000000000000000000000C0DE');
BEGIN
  INSERT INTO tenant (
    id, slug, display_name, rp_id, rp_name, status,
    require_user_verification, mds_required,
    created_at, updated_at
  )
  SELECT
    v_demo_tenant_id, 'demo-rp', 'Demo RP', 'localhost', 'Demo RP',
    'active', 'Y', 'N',
    SYSTIMESTAMP, SYSTIMESTAMP
  FROM dual
  WHERE NOT EXISTS (
    SELECT 1 FROM tenant WHERE id = v_demo_tenant_id OR slug = 'demo-rp'
  );

  INSERT INTO tenant_allowed_origin (id, tenant_id, origin, sort_order)
  SELECT SYS_GUID(), v_demo_tenant_id, 'http://localhost:9090', 0
  FROM dual
  WHERE EXISTS (SELECT 1 FROM tenant WHERE id = v_demo_tenant_id)
    AND NOT EXISTS (
    SELECT 1 FROM tenant_allowed_origin WHERE tenant_id = v_demo_tenant_id
  );

  INSERT INTO tenant_accepted_format (id, tenant_id, format)
  SELECT SYS_GUID(), v_demo_tenant_id, 'none' FROM dual
  WHERE EXISTS (SELECT 1 FROM tenant WHERE id = v_demo_tenant_id)
    AND NOT EXISTS (SELECT 1 FROM tenant_accepted_format WHERE tenant_id = v_demo_tenant_id AND format='none');

  INSERT INTO tenant_accepted_format (id, tenant_id, format)
  SELECT SYS_GUID(), v_demo_tenant_id, 'packed' FROM dual
  WHERE EXISTS (SELECT 1 FROM tenant WHERE id = v_demo_tenant_id)
    AND NOT EXISTS (SELECT 1 FROM tenant_accepted_format WHERE tenant_id = v_demo_tenant_id AND format='packed');
END;
/

-- 9. bob 을 demo tenant 의 RP_ADMIN 으로 재지정.
--    slug 가 이미 있고 다른 id 인 경우에도 그 실제 id 를 가리키도록 SELECT.
UPDATE admin_user
   SET role = 'RP_ADMIN',
       tenant_id = (SELECT id FROM tenant WHERE slug = 'demo-rp')
 WHERE email = 'bob@crosscert.com'
   AND EXISTS (SELECT 1 FROM tenant WHERE slug = 'demo-rp');

COMMIT;
```

- [ ] **Step 2: Flyway 가 V23 을 적용하는지 확인 — admin-app 부팅**

전체 admin-app 컴파일 + 부팅 안 함 (T2 의 entity 변경 전이라 schema 검증 실패할 수 있음). 대신 V23 SQL 자체의 syntax 만 확인:

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation
ls -la core/src/main/resources/db/migration/V23__admin_role_separation.sql
```

Expected: 파일 존재.

(실제 적용은 T2 + 부팅 시점에 확인. 실패하면 V23 SQL 또는 entity 보정.)

- [ ] **Step 3: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add core/src/main/resources/db/migration/V23__admin_role_separation.sql
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "feat(core): V23 — admin_user tenant_id FK + role enum 변경 (T1)

ADMIN/VIEWER → PLATFORM_OPERATOR/RP_ADMIN. demo tenant 결정적 UUID
(...C0DE) 로 idempotent insert. bob → RP_ADMIN(demo-rp).
ck_admin_user_role_tenant CHECK 로 role↔tenant_id invariant 강제.
(id, slug) 둘 다 NOT EXISTS — 운영 환경 slug 충돌 방지."
```

## Reporting

DONE / BLOCKED with commit SHA. V23 적용은 T2 후 IT 가 부팅하며 검증.

---

## Task 2: AdminUser 엔티티 + tenantId 필드

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java`

- [ ] **Step 1: 기존 AdminUser 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation
cat core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java
```

- [ ] **Step 2: tenantId 필드 + helper 추가**

`AdminUser.java` 본문에 (기존 필드들 사이 적절 위치):

```java
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

// ... 기존 필드들 ...

@Column(name = "tenant_id", columnDefinition = "RAW(16)")
@JdbcTypeCode(SqlTypes.UUID)
private UUID tenantId;        // RP_ADMIN 은 NOT NULL, PLATFORM_OPERATOR 는 NULL (V23 CHECK 가 강제)

// ... 기존 getter/setter/recordLogin() 유지 ...

// 신규 helper
public boolean isPlatformOperator() { return "PLATFORM_OPERATOR".equals(role); }
public boolean isRpAdmin()          { return "RP_ADMIN".equals(role); }

public UUID getTenantId() { return tenantId; }
public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
```

기존 import 에 `JdbcTypeCode`, `SqlTypes`, `UUID` 가 이미 있으면 중복 추가 안 함.

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew :core:compileJava :admin-app:compileJava
```

Expected: BUILD SUCCESSFUL. AdminUser 변경이 다른 클래스 깨지 않음 (신규 필드만 추가).

- [ ] **Step 4: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "feat(core): AdminUser 에 tenantId 필드 + role helper (T2)

@JdbcTypeCode(UUID) + RAW(16) 컬럼 매핑. RP_ADMIN 만 non-null
(DB CHECK 가 강제). isPlatformOperator() / isRpAdmin() helper."
```

## Reporting

DONE / BLOCKED with commit SHA.

---

## Task 3: AdminUserDetails 신규 — UserDetails 구현체

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetails.java`

- [ ] **Step 1: 작성**

```java
package com.crosscert.passkey.admin.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Spring Security principal — login 시 AdminUserDetailsService 가 채워서
 * SecurityContext 에 박는다. tenantId 가 null 이면 PLATFORM_OPERATOR.
 *
 * Service 계층은 SecurityContextHolder 에서 이 객체를 꺼내 tenant boundary 검사.
 */
public final class AdminUserDetails implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final String role;          // "PLATFORM_OPERATOR" | "RP_ADMIN"
    private final UUID tenantId;        // RP_ADMIN 은 non-null
    private final boolean enabled;

    public AdminUserDetails(UUID id, String email, String passwordHash,
                            String role, UUID tenantId, boolean enabled) {
        this.id = Objects.requireNonNull(id);
        this.email = Objects.requireNonNull(email);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.role = Objects.requireNonNull(role);
        this.tenantId = tenantId;
        this.enabled = enabled;
    }

    public UUID getId()       { return id; }
    public String getRole()   { return role; }
    public UUID getTenantId() { return tenantId; }

    public boolean isPlatformOperator() { return "PLATFORM_OPERATOR".equals(role); }
    public boolean isRpAdmin()          { return "RP_ADMIN".equals(role); }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public String getPassword()              { return passwordHash; }
    @Override public String getUsername()              { return email; }
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return enabled; }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew :admin-app:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetails.java
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "feat(admin): AdminUserDetails — UserDetails + tenantId (T3)

Spring Security principal. tenantId nullable: PLATFORM_OPERATOR 는 null,
RP_ADMIN 은 non-null. getAuthorities() 가 ROLE_<role> 로 변환."
```

## Reporting

DONE / BLOCKED with commit SHA. 다른 코드 변경 없음 — 신규 파일만.

---

## Task 4: AdminUserDetailsService 변경 — AdminUserDetails 반환

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetailsService.java`

- [ ] **Step 1: 기존 코드 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation
cat admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetailsService.java
```

기존: `User.builder()...build()` 로 plain UserDetails 반환.

- [ ] **Step 2: 전체 교체**

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository repo;

    public AdminUserDetailsService(AdminUserRepository repo) {
        this.repo = repo;
    }

    @Override
    public AdminUserDetails loadUserByUsername(String email) {
        AdminUser u = repo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("admin not found: " + email));
        return new AdminUserDetails(
                u.getId(),
                u.getEmail(),
                u.getBcryptHash(),
                u.getRole(),
                u.getTenantId(),
                "Y".equals(u.getEnabledFlag())
        );
    }
}
```

**중요**: `isEnabled()` 의 source 가 `u.getEnabledFlag()` 의 "Y" 비교임 확인. T1 explore 에서 String field "Y"/"N" 로 확인됨.

만약 AdminUser 에 `isEnabled()` boolean 메서드가 이미 있다면 그것을 호출 (`u.isEnabled()`).

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew :admin-app:compileJava
```

Expected: BUILD SUCCESSFUL.

흔한 오류:
- `getEnabledFlag()` 없음 → AdminUser entity 에 가서 메서드 이름 확인 (isEnabled() 일 수 있음). 호출 조정.
- `AdminUser.getRole()` 반환 타입 다름 — 그대로 String.

- [ ] **Step 4: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetailsService.java
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "feat(admin): AdminUserDetailsService 가 AdminUserDetails 반환 (T4)

기존 User.builder() plain UserDetails 대신 AdminUserDetails subclass
반환. tenantId 가 principal 에 박혀 service 계층에서 cast 로 추출 가능."
```

## Reporting

DONE / BLOCKED with commit SHA + AdminUser.isEnabled() 또는 getEnabledFlag() 어느 메서드를 호출했는지 보고.

---

## Task 5: MeController + MeView — tenantId 노출

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/MeController.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/MeView.java` (또는 MeController 내부 record)

- [ ] **Step 1: 기존 MeView 위치 + 형태 확인**

```bash
find admin-app/src/main/java -name "MeView.java"
cat admin-app/src/main/java/com/crosscert/passkey/admin/config/MeController.java
```

T1 explore 결과: MeView 는 별도 파일 (`MeView.java`) 또는 MeController 안의 inner record. 시그니처: `record MeView(String email, String role)`.

- [ ] **Step 2: MeView 시그니처 변경**

`MeView.java` 가 별도 파일이면:

```java
package com.crosscert.passkey.admin.config;

import java.util.UUID;

public record MeView(String email, String role, UUID tenantId) {}
```

`MeController.java` 안의 inner record 면 그 record 정의 변경.

- [ ] **Step 3: MeController.me() 변경**

```java
package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.core.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPA bootstrap call. AdminUserDetails 에서 직접 email/role/tenantId 추출.
 */
@RestController
@RequestMapping("/admin/api")
public class MeController {

    @GetMapping("/me")
    public ApiResponse<MeView> me(Authentication auth) {
        AdminUserDetails principal = (AdminUserDetails) auth.getPrincipal();
        return ApiResponse.ok(new MeView(
                principal.getUsername(),
                principal.getRole(),
                principal.getTenantId()
        ));
    }
}
```

기존 `auth.getAuthorities().stream()...` 패턴 제거 — `AdminUserDetails` 가 직접 가짐.

만약 `MeView` 가 `MeController` 의 inner record 였다면 같은 파일 안에 record 정의도 유지.

- [ ] **Step 4: 컴파일**

```bash
./gradlew :admin-app:compileJava
```

Expected: BUILD SUCCESSFUL.

흔한 오류:
- `(AdminUserDetails) auth.getPrincipal()` cast 실패 — 컴파일은 통과하지만 runtime 가능. 다른 endpoint 의 principal 접근도 같은 형태일 텐데, AdminUserDetails subclass 라 cast OK.
- MeView 호출처 (다른 controller) 가 있다면 깨질 수 있음. grep:
  ```bash
  grep -rn "new MeView\|MeView(" admin-app/src
  ```

- [ ] **Step 5: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add admin-app/src/main/java/com/crosscert/passkey/admin/config/
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "feat(admin): MeController/MeView — tenantId 노출 (T5)

MeView 에 UUID tenantId 추가. MeController 가 (AdminUserDetails) cast
하여 직접 가져옴. admin-ui 의 Login.tsx 가 이 값으로 role 기반 라우팅."
```

## Reporting

DONE / BLOCKED with commit SHA + MeView 가 별도 파일/inner record 였는지 + 호출처 영향 보고.

---

## Task 6: TenantBoundary helper 신규

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/TenantBoundary.java`

- [ ] **Step 1: BusinessException + ErrorCode 경로 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation
find core/src/main/java -name "BusinessException.java" -o -name "ErrorCode.java"
```

이전 phase 들에서 `com.crosscert.passkey.core.api` 로 확인됨.

- [ ] **Step 2: 작성**

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * RP_ADMIN 이 자기 tenant 외부에 접근하지 못하게 차단하는 단일 진입점.
 * 모든 admin service 의 첫 줄에서 호출.
 *
 *   tenantBoundary.assertCanAccessTenant(tenantId);
 *
 * PLATFORM_OPERATOR 는 무제한. RP_ADMIN 은 자기 tenantId 와 일치할 때만.
 */
@Component
public class TenantBoundary {

    /**
     * path 또는 entity 가 가리키는 tenantId 에 현재 로그인 운영자가 접근 가능한지 검사.
     * RP_ADMIN 인데 다른 tenant 면 ACCESS_DENIED throw.
     */
    public void assertCanAccessTenant(UUID tenantId) {
        AdminUserDetails me = currentPrincipal();
        if (me.isPlatformOperator()) return;
        if (me.isRpAdmin()) {
            if (!me.getTenantId().equals(tenantId)) {
                throw new BusinessException(ErrorCode.ACCESS_DENIED,
                        "RP_ADMIN cannot access tenant " + tenantId);
            }
            return;
        }
        throw new BusinessException(ErrorCode.ACCESS_DENIED, "unknown role: " + me.getRole());
    }

    /**
     * list 전체 — PLATFORM_OPERATOR 는 모든 tenant (empty), RP_ADMIN 은 자기 tenant.
     */
    public Optional<UUID> currentTenantScope() {
        AdminUserDetails me = currentPrincipal();
        if (me.isPlatformOperator()) return Optional.empty();
        if (me.isRpAdmin())          return Optional.of(me.getTenantId());
        throw new BusinessException(ErrorCode.ACCESS_DENIED, "unknown role: " + me.getRole());
    }

    /**
     * 현재 로그인 사용자가 PLATFORM_OPERATOR 임을 강제.
     */
    public void assertPlatformOperator() {
        AdminUserDetails me = currentPrincipal();
        if (!me.isPlatformOperator()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "platform-only operation");
        }
    }

    private AdminUserDetails currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AdminUserDetails p)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "not authenticated");
        }
        return p;
    }
}
```

- [ ] **Step 3: 컴파일**

```bash
./gradlew :admin-app:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add admin-app/src/main/java/com/crosscert/passkey/admin/auth/TenantBoundary.java
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "feat(admin): TenantBoundary helper — tenant scoping 단일 진입점 (T6)

assertCanAccessTenant(tid): RP_ADMIN 이 다른 tenant 접근 시 403.
currentTenantScope(): list 분기용 — PLATFORM_OPERATOR 는 empty.
assertPlatformOperator(): platform-only 작업 보호."
```

## Reporting

DONE / BLOCKED with commit SHA.

---

## Task 7: TenantAdminService boundary 적용 + Controller @PreAuthorize 갱신

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java`

- [ ] **Step 1: TenantAdminService 에 TenantBoundary 주입 + 적용**

기존 service constructor 에 `TenantBoundary` 주입 추가. 각 메서드 첫 줄에 boundary 호출:

```java
// 상단 import
import com.crosscert.passkey.admin.auth.TenantBoundary;

// 클래스 필드 추가
private final TenantBoundary tenantBoundary;

// constructor 갱신 — 기존 (TenantRepository, AuditLogService) 에 추가
public TenantAdminService(TenantRepository tenants,
                          AuditLogService audit,
                          TenantBoundary tenantBoundary,
                          jakarta.persistence.EntityManager em) {  // ← em 도 admin-tenant-detail phase 에서 추가됨
    this.tenants = tenants;
    this.audit = audit;
    this.tenantBoundary = tenantBoundary;
    this.em = em;
}
```

EntityManager 인자 — admin-tenant-detail phase 의 T6 followup 에서 추가됨. 기존 constructor 시그니처 확인 후 `tenantBoundary` 만 추가.

**list 메서드 변경** (현재 `findAll()` 만):

```java
@Transactional(readOnly = true)
public List<TenantAdminDto.TenantView> list() {
    return tenantBoundary.currentTenantScope()
            .map(tid -> tenants.findById(tid)
                    .map(TenantAdminDto.TenantView::from)
                    .map(java.util.List::of)
                    .orElseGet(java.util.List::of))
            .orElseGet(() -> tenants.findAll().stream()
                    .map(TenantAdminDto.TenantView::from)
                    .toList());
}
```

**get 메서드 변경**:

```java
@Transactional(readOnly = true)
public TenantAdminDto.TenantView get(String idOrSlug) {
    Tenant t = lookup(idOrSlug);
    tenantBoundary.assertCanAccessTenant(t.getId());
    return TenantAdminDto.TenantView.from(t);
}
```

**update 메서드** — 기존 lookup 직후에:

```java
@Transactional
public TenantAdminDto.TenantView update(String idOrSlug,
                                        TenantAdminDto.TenantUpdateRequest req,
                                        UUID actorId,
                                        String actorEmail) {
    Tenant t = lookup(idOrSlug);
    tenantBoundary.assertCanAccessTenant(t.getId());   // ← 추가
    // ... 기존 로직 그대로 ...
}
```

**create** — boundary 호출 안 함 (@PreAuthorize hasRole('PLATFORM_OPERATOR') 가 충분).

- [ ] **Step 2: TenantAdminController @PreAuthorize 갱신**

```java
// POST (create) — 기존 hasRole('ADMIN') → hasRole('PLATFORM_OPERATOR')
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
@PostMapping
public ResponseEntity<ApiResponse<TenantAdminDto.TenantView>> create(...) { ... }

// PUT (update) — 기존 hasRole('ADMIN') → hasAnyRole(...)
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
@PutMapping("/{idOrSlug}")
public ApiResponse<TenantAdminDto.TenantView> update(...) { ... }
```

- [ ] **Step 3: 컴파일**

```bash
./gradlew :admin-app:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "feat(admin): TenantAdmin — tenant boundary + role 매트릭스 (T7)

- list: currentTenantScope 분기 (RP_ADMIN 자기 1개, PLATFORM 전체)
- get: assertCanAccessTenant
- update: assertCanAccessTenant (기존 로직 유지)
- create: @PreAuthorize hasRole('PLATFORM_OPERATOR')
- update: @PreAuthorize hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')"
```

## Reporting

DONE / BLOCKED with commit SHA + TenantAdminService constructor 실제 시그니처 (EntityManager 인자 유무) 보고.

---

## Task 8: CredentialAdminService boundary 적용 + Controller @PreAuthorize

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminController.java`

- [ ] **Step 1: CredentialAdminService 변경**

`TenantBoundary` 주입 + 각 메서드 첫 줄에 boundary:

```java
import com.crosscert.passkey.admin.auth.TenantBoundary;

private final TenantBoundary tenantBoundary;

public CredentialAdminService(CredentialRepository creds,
                              MdsAaguidCache mds,
                              AuditLogService audit,
                              TenantBoundary tenantBoundary) {
    this.creds = creds;
    this.mds = mds;
    this.audit = audit;
    this.tenantBoundary = tenantBoundary;
}

@Transactional(readOnly = true)
public PageView<CredentialView> list(UUID tenantId, int page, int size, String q) {
    tenantBoundary.assertCanAccessTenant(tenantId);   // ← 추가
    // ... 기존 로직 ...
}

@Transactional
public void revoke(UUID tenantId, String credentialIdB64, UUID actorId, String actorEmail) {
    tenantBoundary.assertCanAccessTenant(tenantId);   // ← 추가 (entity 비교는 기존 유지)
    // ... 기존 로직 ...
}
```

- [ ] **Step 2: CredentialAdminController @PreAuthorize 갱신**

```java
// DELETE — 기존 hasRole('ADMIN') → hasAnyRole(...)
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
@DeleteMapping("/{credentialId}")
public ResponseEntity<ApiResponse<Void>> revoke(...) { ... }
```

GET 은 변경 없음 (annotation 없음, service boundary 가 처리).

- [ ] **Step 3: 컴파일**

```bash
./gradlew :admin-app:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add admin-app/src/main/java/com/crosscert/passkey/admin/credential/
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "feat(admin): CredentialAdmin — tenant boundary + role 매트릭스 (T8)

- list/revoke: assertCanAccessTenant 추가
- DELETE: @PreAuthorize hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')
- entity.tenantId vs path 비교는 기존 유지 (이중 방어)"
```

## Reporting

DONE / BLOCKED with commit SHA.

---

## Task 9: ApiKeyAdminService boundary 적용 + Controller @PreAuthorize

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminController.java`

- [ ] **Step 1: ApiKeyAdminService 변경**

기존 코드 확인:
```bash
cat admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java
```

`TenantBoundary` 주입 + 3 메서드 (list, issue, revoke) 첫 줄에 boundary:

```java
import com.crosscert.passkey.admin.auth.TenantBoundary;

private final TenantBoundary tenantBoundary;

// constructor 에 추가

public List<ApiKeyAdminDto.ApiKeyView> list(String tenantId) {
    java.util.UUID tid = java.util.UUID.fromString(tenantId);
    tenantBoundary.assertCanAccessTenant(tid);   // ← 추가
    // ... 기존 로직: filter by tenantId ...
}

public ApiKeyAdminDto.ApiKeyCreateResponse issue(ApiKeyAdminDto.ApiKeyCreateRequest req,
                                                  UUID actorId, String actorEmail) {
    tenantBoundary.assertCanAccessTenant(req.tenantId());   // ← 추가
    // ... 기존 로직 ...
}

public void revoke(UUID id, UUID actorId, String actorEmail) {
    ApiKey k = repo.findById(id).orElseThrow(...);
    tenantBoundary.assertCanAccessTenant(k.getTenantId());   // ← 추가 — entity 의 tenantId 로
    // ... 기존 로직: delete + audit ...
}
```

**중요**: revoke 의 boundary 는 entity 의 tenantId 기준 — path 에 tenantId 가 없으므로 (id 만). RP_ADMIN 이 다른 tenant 의 api-key id 알아내서 DELETE 시도해도 entity 의 tenantId 비교로 차단.

- [ ] **Step 2: ApiKeyAdminController @PreAuthorize 갱신**

```java
// POST (issue) — 기존 hasRole('ADMIN') → hasAnyRole(...)
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
@PostMapping
public ResponseEntity<ApiResponse<ApiKeyAdminDto.ApiKeyCreateResponse>> issue(...) { ... }

// DELETE — 기존 hasRole('ADMIN') → hasAnyRole(...)
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
@DeleteMapping("/{id}")
public ResponseEntity<ApiResponse<Void>> revoke(...) { ... }
```

- [ ] **Step 3: 컴파일**

```bash
./gradlew :admin-app:compileJava
```

- [ ] **Step 4: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add admin-app/src/main/java/com/crosscert/passkey/admin/apikey/
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "feat(admin): ApiKeyAdmin — tenant boundary + role 매트릭스 (T9)

- list/issue: req.tenantId 로 boundary 검사
- revoke: entity.tenantId 로 boundary 검사 (path 에 tenantId 없음)
- POST/DELETE: @PreAuthorize hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')"
```

## Reporting

DONE / BLOCKED with commit SHA + ApiKeyAdminService 의 실제 constructor 시그니처 보고.

---

## Task 10: KeyMgmt + Mds + AuditLog Controllers — @PreAuthorize 갱신

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtController.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsAdminController.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogController.java`

이 3 controller 는 모두 platform-only — `hasRole('PLATFORM_OPERATOR')` 로 통일. GET 도 모두 PLATFORM_OPERATOR 만 (RP_ADMIN 접근 불가).

- [ ] **Step 1: KeyMgmtController — GET + POST 모두 PLATFORM_OPERATOR**

```java
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
@GetMapping
public ApiResponse<KeyMgmtDto.KeyList> list() { ... }

@PreAuthorize("hasRole('PLATFORM_OPERATOR')")     // 기존 hasRole('ADMIN') 에서 변경
@PostMapping("/rotate")
public ApiResponse<KeyMgmtDto.RotateResponse> rotate(...) { ... }
```

- [ ] **Step 2: MdsAdminController — GET + POST 모두 PLATFORM_OPERATOR**

```java
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
@GetMapping("/status")
public ApiResponse<MdsStatusView> status() { ... }

@PreAuthorize("hasRole('PLATFORM_OPERATOR')")     // 기존 hasRole('ADMIN') 에서 변경
@PostMapping("/sync")
public ApiResponse<MdsSchedulerService.SyncResult> sync() { ... }
```

- [ ] **Step 3: AuditLogController — GET + verify 모두 PLATFORM_OPERATOR**

```java
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
@GetMapping
public ApiResponse<List<AuditLogView>> list(...) { ... }

@PreAuthorize("hasRole('PLATFORM_OPERATOR')")     // 기존 hasRole('ADMIN') 에서 변경
@GetMapping("/verify")
public ApiResponse<AuditChainVerifier.Result> verify() { ... }
```

기존엔 GET list 가 annotation 없었음. RP_ADMIN 차단 위해 명시 추가.

- [ ] **Step 4: 컴파일**

```bash
./gradlew :admin-app:compileJava
```

- [ ] **Step 5: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/ \
        admin-app/src/main/java/com/crosscert/passkey/admin/mds/ \
        admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogController.java
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "feat(admin): KeyMgmt/Mds/AuditLog Controllers — PLATFORM_OPERATOR only (T10)

GET + mutation 모두 hasRole('PLATFORM_OPERATOR'). RP_ADMIN 차단.
audit_log 의 RP_ADMIN scoping 은 후속 phase (audit_log.tenant_id
컬럼 도입과 함께)."
```

## Reporting

DONE / BLOCKED with commit SHA.

---

## Task 11: 5 ControllerSecurityTest — VIEWER/ADMIN → RP_ADMIN/PLATFORM_OPERATOR 갱신

T1 explore 발견: 5 개 `*ControllerSecurityTest` 가 `@WithMockUser(roles="VIEWER"|"ADMIN")` 사용. 모두 갱신.

**Files:**
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java`
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/credential/CredentialAdminControllerSecurityTest.java` (admin-tenant-detail phase 후 생긴 경우. 없으면 skip)
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtControllerSecurityTest.java`
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogControllerSecurityTest.java`
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminControllerSecurityTest.java`
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsAdminControllerSecurityTest.java`

- [ ] **Step 1: 5 파일의 @WithMockUser grep**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation
grep -rn "@WithMockUser\|roles\s*=\s*\"VIEWER\"\|roles\s*=\s*\"ADMIN\"" admin-app/src/test/java
```

결과로 각 파일의 모든 occurrence 확인.

- [ ] **Step 2: role 매핑 일괄 변경**

| 기존 | 신규 | 영향 |
|---|---|---|
| `roles = "ADMIN"` | `roles = "PLATFORM_OPERATOR"` | 모든 mutation 통과 (현 ADMIN 의 의미 보존) |
| `roles = "VIEWER"` | `roles = "RP_ADMIN"` | platform-only 작업 (key rotate, mds sync, audit verify) 403; tenant mutation 200 (boundary 가 추가 검사) |

**중요**: VIEWER → RP_ADMIN 변환 후 **assertion 의 의미가 달라짐**. 기존 "VIEWER 가 PUT 403" 이었으면, RP_ADMIN 은 PUT 200 (자기 tenant 한정). 각 test method 의 assertion 을 새 의미에 맞게 갱신.

#### TenantAdminControllerSecurityTest (예시 — 다른 4 파일도 동일 패턴)

기존 패턴:
```java
@WithMockUser(roles = "VIEWER")
@Test
void create_isForbidden_forViewer() throws Exception {
    mvc.perform(post("/admin/api/tenants")...).andExpect(status().isForbidden());
}
```

신규: VIEWER 가 RP_ADMIN 으로 바뀌면서 의미가 달라짐. RP_ADMIN 도 create 는 못 함 (PLATFORM_OPERATOR 만). assertion 자체는 유지 가능 (여전히 403):
```java
@WithMockUser(roles = "RP_ADMIN")
@Test
void create_isForbidden_forRpAdmin() throws Exception {
    mvc.perform(post("/admin/api/tenants")...).andExpect(status().isForbidden());
}
```

PUT/DELETE 의 RP_ADMIN test 는 새로 추가하거나 — **여기서는 단순화**: SecurityTest 는 `@PreAuthorize` annotation 의 role 통과만 검증. tenant boundary 검사는 IT 가 담당.

따라서 SecurityTest 의 의미:
- PLATFORM_OPERATOR: 모든 endpoint 통과
- RP_ADMIN: hasRole('PLATFORM_OPERATOR') 가 붙은 endpoint 만 403. hasAnyRole(...) 가 붙은 곳은 200 통과 (boundary 검증은 별개).

매트릭스 (test 의 expected status):

| Endpoint | PLATFORM_OPERATOR | RP_ADMIN |
|---|---|---|
| POST /tenants | 200/201 | 403 |
| PUT /tenants/{id} | 200 (boundary 가 추가 검증) | 200 (test 의 `@WithMockUser` 만으로는 tenant 정보 없어서 boundary 가 cast 실패 — IT 가 cover) |
| GET /tenants | 200 | 200 |
| GET /tenants/{id} | 200 | 200 |
| POST /api-keys | 200/201 | 200 (boundary 는 IT 검증) |
| DELETE /api-keys/{id} | 200 | 200 |
| POST /keys/rotate | 200 | 403 |
| POST /mds/sync | 200 | 403 |
| GET /audit | 200 | 403 |
| GET /audit/verify | 200 | 403 |

**구현 단계 결정**: SecurityTest 는 mock principal 이라 `AdminUserDetails` 가 아님 → boundary 가 `currentPrincipal()` 에서 `UNAUTHORIZED` throw 가능. **boundary 호출 endpoint 의 SecurityTest 는 단순화**: 각 role 의 통과 여부만 검증. boundary 실제 동작은 IT (T13/T14) 가 검증.

따라서 SecurityTest 변경의 핵심:
1. `roles = "ADMIN"` → `roles = "PLATFORM_OPERATOR"` (sed)
2. `roles = "VIEWER"` → `roles = "RP_ADMIN"` (sed)
3. assertion 이 의미 잃은 곳 (예: `void mutation_isForbidden_forViewer` 가 `_forRpAdmin` 으로 바뀌면 의미 달라짐 — DELETE credential 은 RP_ADMIN 도 가능) → assertion 갱신 또는 test 제거.

각 SecurityTest 마다 grep 후 manual 갱신. 자동화는 위험.

- [ ] **Step 3: 자동 grep 으로 첫 패스 (역할 이름만)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation

# 1. ADMIN → PLATFORM_OPERATOR 일괄 (test 파일만)
find admin-app/src/test/java -name "*SecurityTest.java" -exec \
  sed -i '' 's/roles = "ADMIN"/roles = "PLATFORM_OPERATOR"/g' {} \;

# 2. VIEWER → RP_ADMIN 일괄
find admin-app/src/test/java -name "*SecurityTest.java" -exec \
  sed -i '' 's/roles = "VIEWER"/roles = "RP_ADMIN"/g' {} \;

# 3. method 이름의 _forAdmin / _forViewer 도 변경 (선택)
find admin-app/src/test/java -name "*SecurityTest.java" -exec \
  sed -i '' 's/_forAdmin/_forPlatformOperator/g; s/_forViewer/_forRpAdmin/g' {} \;
```

(macOS sed 는 `-i ''` BSD sed. Linux 면 `-i`.)

- [ ] **Step 4: 자동 변환 후 각 SecurityTest 의 assertion 의미 review**

매 파일 cat 으로 열어보고 각 `_forRpAdmin` test 가 RP_ADMIN 의 실제 권한 매트릭스 (위 표) 와 일치하는지 확인:

```bash
for f in admin-app/src/test/java/com/crosscert/passkey/admin/{tenant,keymgmt,audit,apikey,mds,credential}/*SecurityTest.java; do
    [ -f "$f" ] && echo "=== $f ===" && cat "$f" && echo
done
```

각 test 별로:
- `_forRpAdmin` test 가 403 expect 인데 endpoint 가 RP_ADMIN 도 허용한다면 → expect 를 200 또는 적절히 변경. 또는 test 자체 제거 (boundary 가 IT 에서 검증).
- platform-only endpoint (key rotate, mds sync, audit verify, audit list, tenant create) 는 RP_ADMIN 이 여전히 403 — assertion 유지.

**단순화 옵션**: SecurityTest 가 boundary 호출 없는 platform-only endpoint 만 의미 있게 검증하도록, boundary 호출 endpoint 의 RP_ADMIN test 는 제거 — IT 가 cover.

- [ ] **Step 5: 빌드**

```bash
./gradlew :admin-app:compileTestJava
```

Expected: BUILD SUCCESSFUL. 컴파일은 통과해야 함. 실제 test 실행은 다음 step.

- [ ] **Step 6: 5 SecurityTest 실행**

```bash
./gradlew :admin-app:test --tests *SecurityTest
```

Expected: 모두 통과 (또는 의미 변경된 test 가 fail 시 의도와 맞는지 확인 후 수정).

- [ ] **Step 7: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add admin-app/src/test/java/com/crosscert/passkey/admin/
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "test(admin): 5 ControllerSecurityTest — VIEWER→RP_ADMIN, ADMIN→PLATFORM_OPERATOR (T11)

@WithMockUser role 이름 일괄 변경. assertion 의 의미가 변한 경우
(예: 기존 VIEWER 가 PUT 403, RP_ADMIN 은 PUT 200) IT 가 cover —
SecurityTest 는 annotation 단위 통과 여부만 검증."
```

## Reporting

DONE / BLOCKED with commit SHA + 자동 변환 후 manual 조정한 test 의 목록 (있다면).

---

## Task 12: AdminFlowIT bob 시나리오 갱신

**Files:**
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java`

T1 explore 결과: ⑩ step (lines 409-429) 가 "Bob (VIEWER) — list 200, create 403" 가정. V23 후 bob 은 RP_ADMIN(demo-rp) 이므로:
- bob list → demo-rp 만 보임 (전체 아님)
- bob POST /tenants → 여전히 403 (RP_ADMIN 은 create 불가)

⑩ 의 list assertion 을 "1 개" 또는 "demo-rp 포함, beta tenant 미포함" 으로 갱신. create 403 assertion 은 유지.

- [ ] **Step 1: AdminFlowIT 의 ⑩ block 확인**

```bash
sed -n '409,440p' admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java
```

- [ ] **Step 2: bob list assertion 갱신**

기존:
```java
HttpHeaders bobAuth = loginAs("bob@crosscert.com", "bob-temp-pw");
ResponseEntity<String> bobList = rest.exchange(
        url("/admin/api/tenants"), HttpMethod.GET,
        new HttpEntity<>(bobAuth), String.class);
assertThat(bobList.getStatusCode().value()).isEqualTo(200);
```

갱신: list 결과 body 에 alpha tenant (그 전 step 이 만든 것) 가 **포함 안 됨** 검증 추가:

```java
HttpHeaders bobAuth = loginAs("bob@crosscert.com", "bob-temp-pw");
ResponseEntity<String> bobList = rest.exchange(
        url("/admin/api/tenants"), HttpMethod.GET,
        new HttpEntity<>(bobAuth), String.class);
assertThat(bobList.getStatusCode().value()).isEqualTo(200);

// bob 은 RP_ADMIN(demo-rp) — alpha tenant (alice 가 만든 것) 미포함, demo-rp 만 포함
JsonNode bobListData = om.readTree(bobList.getBody()).get("data");
assertThat(StreamSupport.stream(bobListData.spliterator(), false)
        .map(n -> n.get("slug").asText()).toList())
        .contains("demo-rp")
        .doesNotContain("alpha");
```

POST create assertion 은 그대로 (RP_ADMIN 도 403):

```java
ResponseEntity<String> bobCreate = rest.exchange(
        url("/admin/api/tenants"), HttpMethod.POST,
        new HttpEntity<>(betaTenantBody, bobAuth), String.class);
assertThat(bobCreate.getStatusCode().value()).isEqualTo(403);
```

**import 추가** (필요 시):
```java
import com.fasterxml.jackson.databind.JsonNode;
import java.util.stream.StreamSupport;
```

`om` (ObjectMapper) 는 AdminFlowIT 에 이미 있을 것 — `@Autowired ObjectMapper om`. 확인.

- [ ] **Step 3: 실행**

```bash
./gradlew :admin-app:test --tests AdminFlowIT
```

Expected: AdminFlowIT 통과.

- [ ] **Step 4: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "test(admin): AdminFlowIT — bob VIEWER→RP_ADMIN 시나리오 갱신 (T12)

bob 은 이제 RP_ADMIN(demo-rp). list 결과에 자기 tenant 만 보이고
alice 가 만든 alpha 는 안 보임 검증 추가. POST 403 은 유지
(RP_ADMIN 도 tenant create 불가)."
```

## Reporting

DONE / BLOCKED with commit SHA.

---

## Task 13: RpAdminBoundaryIT (자동 IT #1)

cross-tenant boundary 회귀 채널. spec § 6.2 의 14 assertion 시나리오.

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/RpAdminBoundaryIT.java`

- [ ] **Step 1: T6 인용 (admin-tenant-detail phase) 의 IT 패턴 참고**

```bash
ls admin-app/src/test/java/com/crosscert/passkey/admin/{tenant,credential}/*UpdateIT*.java \
   admin-app/src/test/java/com/crosscert/passkey/admin/{tenant,credential}/*SecurityIT*.java
```

T6 의 `TenantAdminControllerUpdateIT` 또는 T7 의 `CredentialAdminControllerSecurityIT` (admin-tenant-detail phase) 가 Testcontainers + admin 로그인 inline 패턴 보유. 그것 참고.

- [ ] **Step 2: RpAdminBoundaryIT 작성**

`admin-app/src/test/java/com/crosscert/passkey/admin/auth/RpAdminBoundaryIT.java`:

```java
package com.crosscert.passkey.admin.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RP_ADMIN 의 cross-tenant 차단 회귀 채널. admin-app 의 service boundary
 * 누락 시 200 으로 즉시 회귀.
 *
 * 시나리오 — alice 로 tenant_A/tenant_B 생성 후 bob (RP_ADMIN, demo-rp) 로 14 assertion.
 *
 * Testcontainers 셋업 + 로그인 helper 는 T6 (admin-tenant-detail) 의 IT 패턴 그대로 inline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RpAdminBoundaryIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper om;
    @Autowired TenantRepository tenants;

    private final RestTemplate http = new RestTemplate();

    @Test
    void rpAdmin_seesAndMutatesOnlyOwnTenant_andCannotPlatformOperations() throws Exception {
        // ── 사전 ── alice (PLATFORM_OPERATOR) 로 tenant_A 생성
        HttpHeaders aliceAuth = loginAsAdmin("alice@crosscert.com", "alice-temp-pw");
        String tenantAId = createTenantViaApi(aliceAuth, "boundary-it-a", "Tenant A");

        // bob (RP_ADMIN, demo-rp) 로그인
        HttpHeaders bobAuth = loginAsAdmin("bob@crosscert.com", "bob-temp-pw");

        // 1. GET /me → role=RP_ADMIN + tenantId
        ResponseEntity<JsonNode> me = http.exchange(
                url("/admin/api/me"), HttpMethod.GET, new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(me.getBody().get("data").get("role").asText()).isEqualTo("RP_ADMIN");
        String myTenantId = me.getBody().get("data").get("tenantId").asText();
        assertThat(myTenantId).isNotBlank();

        // 2. GET /tenants → demo-rp 만 포함
        ResponseEntity<JsonNode> tList = http.exchange(
                url("/admin/api/tenants"), HttpMethod.GET, new HttpEntity<>(bobAuth), JsonNode.class);
        List<String> slugs = StreamSupport.stream(tList.getBody().get("data").spliterator(), false)
                .map(n -> n.get("slug").asText()).toList();
        assertThat(slugs).contains("demo-rp").doesNotContain("boundary-it-a");

        // 3. GET /tenants/{my} → 200
        ResponseEntity<JsonNode> myT = http.exchange(
                url("/admin/api/tenants/" + myTenantId), HttpMethod.GET,
                new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(myT.getStatusCode().is2xxSuccessful()).isTrue();

        // 4. GET /tenants/{other} → 403
        assertOnTenantThrowsForbidden(bobAuth,
                () -> http.exchange(url("/admin/api/tenants/" + tenantAId), HttpMethod.GET,
                        new HttpEntity<>(bobAuth), JsonNode.class));

        // 5. PUT /tenants/{my} → 200
        Map<String, Object> updateBody = Map.of(
                "displayName", "Updated by Bob",
                "rpName", "Demo RP",
                "allowedOrigins", List.of("http://localhost:9090"),
                "acceptedFormats", List.of("none", "packed"),
                "requireUserVerification", true,
                "mdsRequired", false);
        ResponseEntity<JsonNode> putMy = http.exchange(
                url("/admin/api/tenants/" + myTenantId), HttpMethod.PUT,
                new HttpEntity<>(om.writeValueAsString(updateBody), bobAuth), JsonNode.class);
        assertThat(putMy.getStatusCode().is2xxSuccessful()).isTrue();

        // 6. PUT /tenants/{other} → 403
        assertForbidden(() -> http.exchange(
                url("/admin/api/tenants/" + tenantAId), HttpMethod.PUT,
                new HttpEntity<>(om.writeValueAsString(updateBody), bobAuth), JsonNode.class));

        // 7. GET /tenants/{my}/credentials → 200
        ResponseEntity<JsonNode> credsMy = http.exchange(
                url("/admin/api/tenants/" + myTenantId + "/credentials"),
                HttpMethod.GET, new HttpEntity<>(bobAuth), JsonNode.class);
        assertThat(credsMy.getStatusCode().is2xxSuccessful()).isTrue();

        // 8. GET /tenants/{other}/credentials → 403
        assertForbidden(() -> http.exchange(
                url("/admin/api/tenants/" + tenantAId + "/credentials"),
                HttpMethod.GET, new HttpEntity<>(bobAuth), JsonNode.class));

        // 9. POST /tenants → 403 (RP_ADMIN 은 tenant create 불가)
        Map<String, Object> newTenant = Map.of(
                "slug", "bob-attempt",
                "displayName", "Bob Attempt",
                "rpId", "localhost",
                "rpName", "X",
                "allowedOrigins", List.of("http://localhost:9090"),
                "acceptedFormats", List.of("none"),
                "requireUserVerification", true,
                "mdsRequired", false);
        assertForbidden(() -> http.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                new HttpEntity<>(om.writeValueAsString(newTenant), bobAuth), JsonNode.class));

        // 10. POST /api-keys (my tenant) → 200
        Map<String, Object> myKey = Map.of(
                "tenantId", myTenantId,
                "name", "bob-key-own",
                "scopes", List.of("registration", "authentication"));
        ResponseEntity<JsonNode> keyMy = http.exchange(
                url("/admin/api/api-keys"), HttpMethod.POST,
                new HttpEntity<>(om.writeValueAsString(myKey), bobAuth), JsonNode.class);
        assertThat(keyMy.getStatusCode().is2xxSuccessful()).isTrue();

        // 11. POST /api-keys (other tenant) → 403
        Map<String, Object> otherKey = Map.of(
                "tenantId", tenantAId,
                "name", "bob-key-other",
                "scopes", List.of("registration"));
        assertForbidden(() -> http.exchange(
                url("/admin/api/api-keys"), HttpMethod.POST,
                new HttpEntity<>(om.writeValueAsString(otherKey), bobAuth), JsonNode.class));

        // 12. POST /keys/rotate → 403
        assertForbidden(() -> http.exchange(
                url("/admin/api/keys/rotate"), HttpMethod.POST,
                new HttpEntity<>(bobAuth), JsonNode.class));

        // 13. POST /mds/sync → 403
        assertForbidden(() -> http.exchange(
                url("/admin/api/mds/sync"), HttpMethod.POST,
                new HttpEntity<>(bobAuth), JsonNode.class));

        // 14. GET /audit → 403
        assertForbidden(() -> http.exchange(
                url("/admin/api/audit"), HttpMethod.GET,
                new HttpEntity<>(bobAuth), JsonNode.class));
    }

    // ── helpers ──
    private String url(String path) { return "http://localhost:" + port + path; }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value())
                        .isEqualTo(403));
    }

    private void assertOnTenantThrowsForbidden(HttpHeaders auth, Runnable call) {
        assertForbidden(call);
    }

    private String createTenantViaApi(HttpHeaders auth, String slug, String displayName)
            throws Exception {
        Map<String, Object> body = Map.of(
                "slug", slug,
                "displayName", displayName,
                "rpId", "localhost",
                "rpName", "RP for " + slug,
                "allowedOrigins", List.of("http://localhost:9090"),
                "acceptedFormats", List.of("none", "packed"),
                "requireUserVerification", true,
                "mdsRequired", false);
        ResponseEntity<JsonNode> res = http.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                new HttpEntity<>(om.writeValueAsString(body), auth), JsonNode.class);
        return res.getBody().get("data").get("id").asText();
    }

    private HttpHeaders loginAsAdmin(String email, String password) {
        // ── T6 IT 의 loginAs inline 패턴 그대로 ──
        // 1) GET /admin/api/me 로 XSRF-TOKEN cookie 획득
        // 2) form POST /admin/login 으로 session 획득
        // 3) 새 XSRF-TOKEN 재추출
        // 4) HttpHeaders 에 Cookie + X-XSRF-TOKEN 박아 반환
        //
        // 실제 구현은 T6 의 동일 helper 를 복사 (sub-agent 가 cat 으로 가져옴).
        throw new UnsupportedOperationException("copy T6 loginAs inline pattern here");
    }

    // ── Testcontainers + DynamicPropertySource ──
    // T6 IT 와 동일 inline 셋업 — 컨테이너 시작 + bootstrap-vpd.sql 실행 + datasource 주입
    // (sub-agent 가 T6 IT 의 셋업 부분 그대로 복사)
}
```

**중요**: `loginAsAdmin` 및 Testcontainers 셋업은 T6 (admin-tenant-detail) 의 IT 에서 그대로 복사. sub-agent 가 그 파일을 cat 한 후 copy-paste.

- [ ] **Step 3: 실행**

```bash
./gradlew :admin-app:test --tests RpAdminBoundaryIT
```

Expected: 1 test passed.

흔한 실패:
- `loginAsAdmin` 의 inline 구현 누락 → T6 IT 의 helper 복사
- Testcontainers 셋업 없음 → T6 IT 의 셋업 복사
- assertion 403 인데 200 응답 → service boundary 누락 (T7/T8/T9 재확인)
- assertion 200 인데 403 응답 → @PreAuthorize 가 RP_ADMIN 차단 (T7/T8/T9 의 hasAnyRole 누락)

- [ ] **Step 4: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add admin-app/src/test/java/com/crosscert/passkey/admin/auth/RpAdminBoundaryIT.java
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "test(admin): RpAdminBoundaryIT — cross-tenant 차단 회귀 채널 (T13)

자동 IT 1 — bob (RP_ADMIN, demo-rp) 로 14 assertion. service boundary
누락 시 200 으로 즉시 회귀. Testcontainers + loginAsAdmin inline 패턴은
T6 (admin-tenant-detail) IT 에서 복사."
```

## Reporting

DONE / BLOCKED with commit SHA + Testcontainers 셋업 + loginAsAdmin 의 어느 IT 에서 복사했는지.

---

## Task 14: PlatformOperatorUnrestrictedIT (자동 IT #2)

PLATFORM_OPERATOR 의 무제한 동작 검증. spec § 6.3 의 10 assertion.

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/PlatformOperatorUnrestrictedIT.java`

- [ ] **Step 1: 작성 — T13 패턴 재사용**

```java
package com.crosscert.passkey.admin.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLATFORM_OPERATOR 의 모든 tenant 자유 접근 검증.
 * role 변경이 기존 운영 능력을 안 깨뜨림 보장.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PlatformOperatorUnrestrictedIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper om;

    private final RestTemplate http = new RestTemplate();

    @Test
    void platformOperator_canAccessAllTenants_andRunPlatformOperations() throws Exception {
        HttpHeaders auth = loginAsAdmin("alice@crosscert.com", "alice-temp-pw");

        // 1. GET /me → PLATFORM_OPERATOR, tenantId=null
        ResponseEntity<JsonNode> me = http.exchange(
                url("/admin/api/me"), HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(me.getBody().get("data").get("role").asText()).isEqualTo("PLATFORM_OPERATOR");
        assertThat(me.getBody().get("data").get("tenantId").isNull()).isTrue();

        // 2. tenant_A 생성
        String tenantAId = createTenantViaApi(auth, "platform-it-a", "Tenant A");
        // 3. GET /tenants → demo-rp + tenant_A 모두
        ResponseEntity<JsonNode> tList = http.exchange(
                url("/admin/api/tenants"), HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        List<String> slugs = StreamSupport.stream(tList.getBody().get("data").spliterator(), false)
                .map(n -> n.get("slug").asText()).toList();
        assertThat(slugs).contains("demo-rp", "platform-it-a");

        // 4. PUT /tenants/{tenant_A} → 200
        ResponseEntity<JsonNode> putA = http.exchange(
                url("/admin/api/tenants/" + tenantAId), HttpMethod.PUT,
                new HttpEntity<>(om.writeValueAsString(Map.of(
                        "displayName", "Updated A",
                        "rpName", "RP for platform-it-a",
                        "allowedOrigins", List.of("http://localhost:9090"),
                        "acceptedFormats", List.of("none", "packed"),
                        "requireUserVerification", true,
                        "mdsRequired", false)), auth),
                JsonNode.class);
        assertThat(putA.getStatusCode().is2xxSuccessful()).isTrue();

        // 5. PUT /tenants/{demo-rp} → 200 (RP 의 tenant 도 자유)
        String demoRpId = StreamSupport.stream(tList.getBody().get("data").spliterator(), false)
                .filter(n -> "demo-rp".equals(n.get("slug").asText()))
                .findFirst().orElseThrow()
                .get("id").asText();
        ResponseEntity<JsonNode> putDemo = http.exchange(
                url("/admin/api/tenants/" + demoRpId), HttpMethod.PUT,
                new HttpEntity<>(om.writeValueAsString(Map.of(
                        "displayName", "Demo RP (updated by alice)",
                        "rpName", "Demo RP",
                        "allowedOrigins", List.of("http://localhost:9090"),
                        "acceptedFormats", List.of("none", "packed"),
                        "requireUserVerification", true,
                        "mdsRequired", false)), auth),
                JsonNode.class);
        assertThat(putDemo.getStatusCode().is2xxSuccessful()).isTrue();

        // 6. GET /tenants/{demo-rp}/credentials → 200
        ResponseEntity<JsonNode> credsDemo = http.exchange(
                url("/admin/api/tenants/" + demoRpId + "/credentials"),
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(credsDemo.getStatusCode().is2xxSuccessful()).isTrue();

        // 7. POST /api-keys {tenantId: demo-rp} → 200
        ResponseEntity<JsonNode> keyDemo = http.exchange(
                url("/admin/api/api-keys"), HttpMethod.POST,
                new HttpEntity<>(om.writeValueAsString(Map.of(
                        "tenantId", demoRpId,
                        "name", "alice-key-for-demo",
                        "scopes", List.of("registration"))), auth),
                JsonNode.class);
        assertThat(keyDemo.getStatusCode().is2xxSuccessful()).isTrue();

        // 8. POST /keys/rotate → 200
        ResponseEntity<JsonNode> rotate = http.exchange(
                url("/admin/api/keys/rotate"), HttpMethod.POST,
                new HttpEntity<>(auth), JsonNode.class);
        assertThat(rotate.getStatusCode().is2xxSuccessful()).isTrue();

        // 9. GET /mds/status → 200
        ResponseEntity<JsonNode> mdsStatus = http.exchange(
                url("/admin/api/mds/status"), HttpMethod.GET,
                new HttpEntity<>(auth), JsonNode.class);
        assertThat(mdsStatus.getStatusCode().is2xxSuccessful()).isTrue();

        // 10. GET /audit → 200
        ResponseEntity<JsonNode> audit = http.exchange(
                url("/admin/api/audit"), HttpMethod.GET,
                new HttpEntity<>(auth), JsonNode.class);
        assertThat(audit.getStatusCode().is2xxSuccessful()).isTrue();
    }

    // ── helpers — T13 IT 와 동일 패턴 ──
    private String url(String path) { return "http://localhost:" + port + path; }

    private String createTenantViaApi(HttpHeaders auth, String slug, String displayName) throws Exception {
        // T13 helper 동일
        Map<String, Object> body = Map.of(
                "slug", slug,
                "displayName", displayName,
                "rpId", "localhost",
                "rpName", "RP for " + slug,
                "allowedOrigins", List.of("http://localhost:9090"),
                "acceptedFormats", List.of("none", "packed"),
                "requireUserVerification", true,
                "mdsRequired", false);
        ResponseEntity<JsonNode> res = http.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                new HttpEntity<>(om.writeValueAsString(body), auth), JsonNode.class);
        return res.getBody().get("data").get("id").asText();
    }

    private HttpHeaders loginAsAdmin(String email, String password) {
        // T6 / T13 IT 의 loginAs inline 패턴 그대로 — 복사
        throw new UnsupportedOperationException("copy T6 / T13 loginAs inline pattern here");
    }

    // Testcontainers 셋업도 T13 와 동일 — 복사
}
```

- [ ] **Step 2: 실행**

```bash
./gradlew :admin-app:test --tests PlatformOperatorUnrestrictedIT
```

Expected: 1 test passed.

- [ ] **Step 3: Commit**

```bash
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    add admin-app/src/test/java/com/crosscert/passkey/admin/auth/PlatformOperatorUnrestrictedIT.java
git -C /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation \
    commit -m "test(admin): PlatformOperatorUnrestrictedIT — PLATFORM_OPERATOR 무제한 검증 (T14)

자동 IT 2 — alice (PLATFORM_OPERATOR) 의 모든 tenant + platform-only
operation 통과 검증. role 변경이 기존 운영 능력을 안 깨뜨림 보장."
```

## Reporting

DONE / BLOCKED with commit SHA.

---

## Task 15: admin-ui — Me 확장 + MeContext + Login 라우팅 + Sidebar conditional + PlatformOnlyGuard + page guards

**Files (다수):**
- Modify: `admin-ui/src/api/types.ts`
- Modify: `admin-ui/src/api/client.ts`
- Create: `admin-ui/src/me/MeContext.tsx`
- Modify: `admin-ui/src/App.tsx`
- Modify: `admin-ui/src/pages/Login.tsx`
- Modify: `admin-ui/src/components/Sidebar.tsx`
- Modify: `admin-ui/src/components/Header.tsx`
- Create: `admin-ui/src/components/PlatformOnlyGuard.tsx`
- Modify: `admin-ui/src/pages/TenantList.tsx`
- Modify: `admin-ui/src/pages/TenantCreate.tsx`
- Modify: `admin-ui/src/pages/TenantDetail.tsx`
- Modify: `admin-ui/src/pages/AuditLog.tsx`
- Modify: `admin-ui/src/pages/KeyManagement.tsx`
- Modify: `admin-ui/src/pages/MdsStatus.tsx`

- [ ] **Step 1: types.ts — Me 확장**

`admin-ui/src/api/types.ts` 의 `Me` interface 변경:

```typescript
export interface Me {
    email: string;
    role: 'PLATFORM_OPERATOR' | 'RP_ADMIN';
    tenantId: string | null;
}
```

기존이 `'ADMIN' | 'VIEWER'` 였으면 교체.

- [ ] **Step 2: client.ts — getMe helper**

기존 client.ts 에 import + helper 추가:

```typescript
import type { Me } from './types';

export const getMe = () => api.get<Me>('/admin/api/me');
```

- [ ] **Step 3: MeContext 작성**

```bash
mkdir -p admin-ui/src/me
```

`admin-ui/src/me/MeContext.tsx`:

```typescript
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { getMe } from '../api/client';
import type { Me } from '../api/types';

interface MeContextValue {
    me: Me | null;
    loading: boolean;
    reload: () => Promise<void>;
}

const MeContext = createContext<MeContextValue>({
    me: null,
    loading: true,
    reload: async () => {},
});

export function MeProvider({ children }: { children: ReactNode }) {
    const [me, setMe] = useState<Me | null>(null);
    const [loading, setLoading] = useState(true);

    const reload = async () => {
        try {
            setMe(await getMe());
        } catch {
            setMe(null);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { reload(); }, []);

    return <MeContext.Provider value={{ me, loading, reload }}>{children}</MeContext.Provider>;
}

export const useMe = () => useContext(MeContext);
```

- [ ] **Step 4: App.tsx — MeProvider 로 Layout 감싸기**

기존 App.tsx 의 Routes 부분:

```typescript
import { MeProvider } from './me/MeContext';

// 기존 <Route element={<Layout />}> 를 다음으로 변경:
<Route element={<MeProvider><Layout /></MeProvider>}>
    <Route path="/tenants" element={<TenantList />} />
    {/* ... 기존 라우트들 ... */}
</Route>
```

- [ ] **Step 5: Login.tsx — 로그인 후 role 라우팅**

```typescript
import { getMe } from '../api/client';

// submit 함수 안:
async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
        await fetch('/admin/api/me', { credentials: 'include' }).catch(() => null);
        const ok = await api.loginForm(email, password);
        if (!ok) {
            setError('이메일 또는 비밀번호가 올바르지 않습니다.');
            return;
        }
        const me = await getMe();
        if (me.role === 'RP_ADMIN' && me.tenantId) {
            nav(`/tenants/${me.tenantId}`);
        } else {
            nav('/tenants');
        }
    } finally {
        setBusy(false);
    }
}
```

- [ ] **Step 6: Sidebar.tsx — RP_ADMIN conditional**

```typescript
import { useMe } from '../me/MeContext';

const PLATFORM_NAV = [
    { to: '/tenants', label: 'Tenants',      icon: Building },
    { to: '/keys',    label: 'Signing Keys', icon: Key },
    { to: '/mds',     label: 'MDS',          icon: Refresh },
    { to: '/audit',   label: 'Audit Log',    icon: Receipt },
];

const RP_ADMIN_NAV = (tenantId: string) => [
    { to: `/tenants/${tenantId}`, label: 'My Tenant', icon: Building },
];

export default function Sidebar() {
    const { me } = useMe();
    const nav = me?.role === 'RP_ADMIN' && me.tenantId
        ? RP_ADMIN_NAV(me.tenantId)
        : (me ? PLATFORM_NAV : []);   // me=null (401) 이면 빈 nav
    // 기존 렌더 로직에 위 nav 배열 사용
    ...
}
```

기존 Sidebar 의 hardcoded NAV 배열을 위 변수로 교체.

- [ ] **Step 7: Header.tsx — role badge + tenant 식별**

```typescript
import { useMe } from '../me/MeContext';

// JSX 안:
{me && (
    <span className="muted">
        {me.email} · <span className="badge">{me.role}</span>
        {me.tenantId && <span className="muted"> · {me.tenantId.slice(0, 8)}…</span>}
    </span>
)}
```

기존 me.email 표시 영역을 위로 교체. me=null 면 표시 안 함.

- [ ] **Step 8: PlatformOnlyGuard.tsx 작성**

```typescript
import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMe } from '../me/MeContext';

/**
 * 페이지 최상단에 두면 RP_ADMIN 을 자기 tenant detail 로 강제 라우팅.
 */
export default function PlatformOnlyGuard() {
    const { me } = useMe();
    const nav = useNavigate();
    useEffect(() => {
        if (me?.role === 'RP_ADMIN' && me.tenantId) {
            nav(`/tenants/${me.tenantId}`, { replace: true });
        }
    }, [me, nav]);
    return null;
}
```

- [ ] **Step 9: 4 페이지에 PlatformOnlyGuard 적용**

`TenantCreate.tsx`, `AuditLog.tsx`, `KeyManagement.tsx`, `MdsStatus.tsx` 의 컴포넌트 return 첫 줄에:

```typescript
import PlatformOnlyGuard from '../components/PlatformOnlyGuard';

return (
    <>
        <PlatformOnlyGuard />
        {/* 기존 JSX */}
    </>
);
```

- [ ] **Step 10: TenantList.tsx — RP_ADMIN 자기 detail 로 리다이렉트**

```typescript
import { useMe } from '../me/MeContext';
import { useEffect } from 'react';

export default function TenantList() {
    const { me } = useMe();
    const nav = useNavigate();

    useEffect(() => {
        if (me?.role === 'RP_ADMIN' && me.tenantId) {
            nav(`/tenants/${me.tenantId}`, { replace: true });
        }
    }, [me, nav]);

    // 기존 로직
}
```

- [ ] **Step 11: TenantDetail.tsx — 다른 tenant 차단**

기존 useEffect 안에 가드 추가:

```typescript
import { useMe } from '../me/MeContext';

export default function TenantDetail() {
    const { id } = useParams<{ id: string }>();
    const { me } = useMe();
    const nav = useNavigate();
    // ...

    useEffect(() => {
        if (!id) return;
        if (me?.role === 'RP_ADMIN' && me.tenantId && me.tenantId !== id) {
            nav(`/tenants/${me.tenantId}`, { replace: true });
            return;
        }
        // 기존 fetch
        api.get<TenantView>(`/admin/api/tenants/${id}`).then(setTenant).catch(...);
    }, [id, me]);

    // ...
}
```

- [ ] **Step 12: typecheck + build**

```bash
cd admin-ui
npx tsc --noEmit
npm run build
```

Expected: 둘 다 에러 없음.

흔한 오류:
- 기존 import 에 `Me` 가 다른 모양 — types.ts 변경 후 모든 호출처가 새 형태로
- Sidebar 의 기존 NAV 배열 형태가 다름 — 그 형태에 맞춰 변환
- PlatformOnlyGuard 가 적용 안 된 페이지에서 RP_ADMIN 이 접근 가능 — backend 가 어차피 403 줄 거라 functional 문제는 없지만 UX 거슬림

- [ ] **Step 13: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation
git add admin-ui/src/api/types.ts admin-ui/src/api/client.ts \
        admin-ui/src/me/ \
        admin-ui/src/App.tsx \
        admin-ui/src/pages/Login.tsx \
        admin-ui/src/components/Sidebar.tsx \
        admin-ui/src/components/Header.tsx \
        admin-ui/src/components/PlatformOnlyGuard.tsx \
        admin-ui/src/pages/TenantList.tsx \
        admin-ui/src/pages/TenantCreate.tsx \
        admin-ui/src/pages/TenantDetail.tsx \
        admin-ui/src/pages/AuditLog.tsx \
        admin-ui/src/pages/KeyManagement.tsx \
        admin-ui/src/pages/MdsStatus.tsx
git commit -m "feat(admin-ui): Me 확장 + MeContext + role 라우팅 + Sidebar conditional (T15)

- Me 에 tenantId 추가, role enum 변경
- MeContext 도입 — sidebar/header/page guards 공유
- Login 후 RP_ADMIN → /tenants/{myTenantId} 자동 라우팅
- Sidebar: RP_ADMIN 은 'My Tenant' 단일 메뉴
- Header: role badge + tenant id 8 char 표시
- PlatformOnlyGuard: TenantCreate/AuditLog/KeyManagement/MdsStatus 보호
- TenantList: RP_ADMIN 은 자기 detail 로 리다이렉트
- TenantDetail: RP_ADMIN 의 다른 tenant 접근 차단"
```

## Reporting

DONE / BLOCKED with commit SHA + 기존 Sidebar NAV 배열 형태 + 기존 Header me 표시 영역 형태 보고.

---

## Task 16: manual smoke + followup + branch codex review + main merge

**Files:**
- Create: `docs/superpowers/followups/2026-05-27-admin-role-separation-followups.md`

- [ ] **Step 1: 전체 빌드**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-role-separation
./gradlew :core:build :admin-app:build -x test
cd admin-ui && npm run build && cd ..
```

Expected: 모두 BUILD SUCCESSFUL.

- [ ] **Step 2: 자동 IT 5 개 재실행**

```bash
./gradlew :admin-app:test --tests *IT
```

Expected: AdminFlowIT + TenantAdminControllerUpdateIT + CredentialAdminControllerSecurityIT + RpAdminBoundaryIT + PlatformOperatorUnrestrictedIT 모두 통과.

- [ ] **Step 3: followup 파일 작성**

`docs/superpowers/followups/2026-05-27-admin-role-separation-followups.md`:

```markdown
# Admin Role 분리 followups

Spec: `docs/superpowers/specs/2026-05-27-admin-role-separation-design.md`
Plan: `docs/superpowers/plans/2026-05-27-admin-role-separation.md`

## Manual smoke result

운영자가 8 단계 manual smoke 후 체크박스 채움.

- [ ] M.1 alice 로그인 → /tenants 목록 + create 버튼 + Signing Keys/MDS/Audit 메뉴 모두 보임
- [ ] M.2 alice 가 bob 의 demo-rp 클릭 → detail 4 탭 모두 동작
- [ ] M.3 logout → bob 로그인 → 자동 /tenants/{demo-rp.id} 라우팅. 사이드바 'My Tenant' 단일
- [ ] M.4 bob 이 URL /tenants 직접 입력 → /tenants/{demo-rp.id} 강제
- [ ] M.5 bob 이 URL /tenants/{tenant_A.id} 입력 → 자기 tenant 로
- [ ] M.6 bob 이 URL /audit 입력 → 자기 tenant detail 로
- [ ] M.7 bob detail 에서 WebAuthn 설정 변경 + 저장 → 200 + audit row
- [ ] M.8 bob detail 에서 credential 회수 → 200 + audit row

## Deferred (spec § 7.2)

1. 운영자 관리 UI (추가/정지/초대 다이얼로그, MFA 옵션)
2. PENDING status + 초대 이메일 (SMTP)
3. audit_log.tenant_id 컬럼 + RP_ADMIN scope
4. suspended tenant 의 RP_ADMIN 정책
5. AdminUser.lastLoginAt RP_ADMIN 추가 후 재확인
6. role enum 의 Java enum 화 (현재 String)
7. 사이드바 UX 재디자인 (RP_ADMIN 전용 페이지 — Funnel / AAGUID / Overview)
8. AAGUID Attestation Policy (ANY/ALLOWLIST/DENYLIST)
9. Tenant suspend/activate workflow

## In-loop findings

phase 진행 중 codex review 가 잡은 항목 + 의도된 결정 / 후속 / 누적 결함을 기록.
```

- [ ] **Step 4: followup commit**

```bash
git add docs/superpowers/followups/2026-05-27-admin-role-separation-followups.md
git commit -m "docs(followups): admin-role-separation manual smoke + deferred 9 (T16)"
```

- [ ] **Step 5: branch 전체 codex review**

```bash
git log --stat feature/admin-role-separation ^main
```

이 출력을 codex 에 final review 요청. 결과를 followup 의 'In-loop findings' 에 추가.

- [ ] **Step 6: main merge**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git checkout main
git merge --no-ff feature/admin-role-separation -m "Merge feature/admin-role-separation — PLATFORM_OPERATOR / RP_ADMIN 분리

16 task — DB migration + AdminUserDetails + TenantBoundary + 8 endpoint
권한 매트릭스 + admin-ui MeContext + 자동 IT 2 + 5 SecurityTest 갱신."
```

- [ ] **Step 7: worktree 정리**

```bash
git worktree remove .claude/worktrees/admin-role-separation
git branch -d feature/admin-role-separation
```

## Reporting

DONE / BLOCKED with merge commit SHA. main 의 새 HEAD 보고.

---

## Self-Review

### 1. Spec coverage

| Spec § | task |
|---|---|
| § 1.1 DoD 8 항목 | T1-T15 |
| § 1.2 deferred | T16 followup |
| § 2.1 V23 migration | T1 |
| § 2.2 AdminUser entity | T2 |
| § 2.3 Repository (변경 없음) | (변경 없음) |
| § 3.1 AdminUserDetails | T3 |
| § 3.2 AdminUserDetailsService | T4 |
| § 3.3 MeController + MeView | T5 |
| § 3.4 TenantBoundary | T6 |
| § 4 8 endpoint 매트릭스 | T7 (Tenant) + T8 (Credential) + T9 (ApiKey) + T10 (KeyMgmt/Mds/AuditLog) |
| § 5.1 types.ts | T15 |
| § 5.2 client.ts getMe | T15 |
| § 5.3 Login 라우팅 | T15 |
| § 5.4 MeContext | T15 |
| § 5.5 Sidebar | T15 |
| § 5.6 Header | T15 |
| § 5.7 PlatformOnlyGuard | T15 |
| § 5.8 TenantList 리다이렉트 | T15 |
| § 5.9 TenantDetail 차단 | T15 |
| § 6 자동 IT 2 | T13, T14 |
| § 6.4 기존 IT 갱신 | T11 (SecurityTest), T12 (AdminFlowIT) |
| § 6.7 manual smoke | T16 followup |
| § 7 위험 / 후속 | T16 followup |

모든 spec 항목 task 매핑됨.

### 2. Placeholder scan

- "TBD" / "TODO" / "implement later" / "fill in" — 없음
- 모든 step 에 실 코드 또는 명령
- T13/T14 의 `loginAsAdmin` + Testcontainers 셋업은 "T6 IT 복사" 명시 — sub-agent 가 `cat` 후 copy

### 3. Type consistency

- `AdminUserDetails(UUID id, String email, String passwordHash, String role, UUID tenantId, boolean enabled)` constructor — T3 정의, T4 호출, T6 cast — 일치
- `TenantBoundary.assertCanAccessTenant(UUID)` / `currentTenantScope(): Optional<UUID>` / `assertPlatformOperator()` — T6 정의, T7-T9 호출 — 일치
- `MeView(String email, String role, UUID tenantId)` — T5 정의, admin-ui Me interface 와 1:1
- `Me.role: 'PLATFORM_OPERATOR' | 'RP_ADMIN'` + `tenantId: string | null` — T15 정의, MeContext / Login / Sidebar / Header / Guards 사용 모두 일치
