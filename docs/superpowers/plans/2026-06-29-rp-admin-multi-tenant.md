# RP_ADMIN 다중 RP 운영 (운영자↔RP N:M + 스위처) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 한 명의 운영자(이메일 1개)가 여러 RP를 동일 RP_ADMIN 권한으로 운영하고, 상단 스위처로 단일 RP 컨텍스트를 전환할 수 있게 한다.

**Architecture:** `admin_user.tenant_id` 단일 컬럼을 `admin_user_tenant` 조인 테이블로 N:M 전환한다. 로그인 시 운영자의 허용 RP 집합(`allowedTenantIds`)을 principal에 싣고, "현재 활성 RP"는 HttpSession 속성(`ACTIVE_TENANT_ID`)으로 분리한다. `TenantBoundary`는 활성 RP를 `ActiveTenantResolver`를 통해 읽어 기존 격리 로직(Hibernate @Filter 포함)을 그대로 유지한다. 부여/회수는 PLATFORM_OPERATOR 전용.

**Tech Stack:** Spring Boot, Spring Security(stateful 세션), JPA/Hibernate, Oracle, Flyway, JUnit5 + Testcontainers, React/TypeScript(admin-ui).

## Global Constraints

- 운영 데이터 없음 → 과도기 컬럼 없이 `V1__baseline_schema.sql`을 직접 수정한다(새 마이그레이션 파일 추가 아님).
- 멀티테넌트 격리 강도는 현행 유지: TenantBoundary 게이팅 + `TenantContextAdminFilter` → Hibernate `@Filter`. RP_ADMIN은 "현재 활성 RP 하나"만 ORM에서 보인다.
- 이메일 전역 UNIQUE(`uq_admin_user_email`) 유지 — 한 사람 = 한 계정.
- PLATFORM_OPERATOR는 `admin_user_tenant`에 행 0개(전체 접근), RP_ADMIN은 1개 이상. 이 규칙은 DB CHECK가 아니라 앱 레벨(AdminUserService) 검증으로 강제.
- 부여/회수 API는 모두 `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")`.
- PK 전략: 엔티티는 `BaseEntity` 상속(UUID, `@UuidGenerator(TIME)`, `RAW(16)`). 단, `admin_user_tenant`는 복합 PK(admin_user_id, tenant_id)이므로 BaseEntity를 상속하지 않고 `@IdClass` 또는 `@EmbeddedId` 사용.
- 모든 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- 빌드 회귀 판정은 base worktree 대조로 pre-existing과 구분(`./gradlew build`는 SliceConfig/Oracle 경합으로 상시 빨갈 수 있음).

---

### Task 1: DB baseline — admin_user N:M 스키마 전환

**Files:**
- Modify: `core/src/main/resources/db/migration/V1__baseline_schema.sql` — admin_user CREATE TABLE(140-170), ix_admin_user_tenant 인덱스(408), v_admin_user 뷰(455-479), GRANT 블록(667-669 admin_user GRANT 근처)

**Interfaces:**
- Produces: `admin_user_tenant(admin_user_id RAW(16), tenant_id RAW(16), created_at, created_by)` 조인 테이블, PK(admin_user_id, tenant_id), FK 2개, GRANT(APP_ADMIN: SELECT/INSERT/UPDATE/DELETE, APP_RUNTIME: SELECT). `admin_user`에서 `tenant_id` 컬럼·`fk_admin_user_tenant`·`ck_admin_user_role_tenant`·`ix_admin_user_tenant` 제거. `v_admin_user` 뷰는 tenant 조인 제거(N:M이라 단일 tenant 표시 불가).

- [ ] **Step 1: admin_user 테이블에서 tenant_id 관련 항목 제거**

`V1__baseline_schema.sql`의 admin_user CREATE TABLE(140-170행)에서 다음 3개를 삭제한다:
1. 컬럼 `tenant_id           RAW(16),` 한 줄
2. `CONSTRAINT ck_admin_user_role_tenant CHECK (...)` 블록 전체(role↔tenant_id 강제)
3. `CONSTRAINT fk_admin_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)` 한 줄

남는 CHECK는 `ck_admin_user_role`(role IN (...)) 그대로 유지. 마지막 컬럼/제약 뒤 콤마 정합성 확인.

- [ ] **Step 2: ix_admin_user_tenant 인덱스 제거**

408행의 `CREATE INDEX ix_admin_user_tenant ON admin_user (tenant_id);` 한 줄을 삭제한다(컬럼이 사라졌으므로).

- [ ] **Step 3: admin_user_tenant 조인 테이블 추가**

admin_user CREATE TABLE 블록 바로 뒤에 다음을 삽입한다:

```sql
-- 운영자(admin_user) ↔ RP(tenant) N:M 매핑.
-- PLATFORM_OPERATOR 는 행 0개(전체 접근), RP_ADMIN 은 1개 이상(앱 레벨 검증).
CREATE TABLE admin_user_tenant (
    admin_user_id  RAW(16)                     NOT NULL,
    tenant_id      RAW(16)                     NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    created_by     VARCHAR2(255),
    CONSTRAINT pk_admin_user_tenant       PRIMARY KEY (admin_user_id, tenant_id),
    CONSTRAINT fk_aut_admin_user          FOREIGN KEY (admin_user_id) REFERENCES admin_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_aut_tenant              FOREIGN KEY (tenant_id)     REFERENCES tenant (id)
);

CREATE INDEX ix_aut_tenant ON admin_user_tenant (tenant_id);
```

- [ ] **Step 4: admin_user_tenant GRANT 추가 (사각지대 — 누락 시 런타임 ORA-00942/01031)**

GRANT 블록(`GRANT ... ON admin_user TO APP_ADMIN;` 근처, 약 667-669행)에 다음을 추가한다. APP_ADMIN은 매핑을 CRUD하고 APP_RUNTIME은 읽기만:

```sql
GRANT SELECT ON admin_user_tenant TO APP_ADMIN;
GRANT INSERT ON admin_user_tenant TO APP_ADMIN;
GRANT UPDATE ON admin_user_tenant TO APP_ADMIN;
GRANT DELETE ON admin_user_tenant TO APP_ADMIN;
GRANT SELECT ON admin_user_tenant TO APP_RUNTIME;
```

> 메모리 `project_flyway_squash_done` 교훈: 테이블/컬럼 GRANT는 덤프 사각지대 — 누락하면 로그인은 되지만 매핑 조회/저장 시점에 권한 오류가 난다.

- [ ] **Step 5: v_admin_user 뷰에서 tenant 조인 제거**

`v_admin_user` 뷰(455-479행)는 `u.tenant_id`와 `LEFT JOIN tenant ON t.id = u.tenant_id`를 쓴다. 컬럼이 사라지므로 깨진다. N:M에서는 운영자당 RP가 여러 개라 단일 tenant 표시가 불가능하므로, 뷰의 tenant 관련 3개 컬럼을 제거한다(디버깅용 뷰):

- 컬럼 목록(456행)에서 `tenant_slug, tenant_name, tenant_id,` 제거
- SELECT 절에서 `t.slug AS tenant_slug,`, `t.display_name AS tenant_name,`, `LOWER(...u.tenant_id...) AS tenant_id,` 세 줄 제거
- `LEFT JOIN tenant t ON t.id = u.tenant_id`(479행) 제거 → `FROM admin_user u`로 끝

(뷰 GRANT는 그대로 유지. 매핑까지 보고 싶으면 후속으로 `v_admin_user_tenant` 뷰를 별도 추가 가능 — 이번 범위 밖.)

- [ ] **Step 6: 변경 검증 (grep)**

Run: `grep -n "admin_user (tenant_id\|ck_admin_user_role_tenant\|fk_admin_user_tenant\|ix_admin_user_tenant\|u.tenant_id" core/src/main/resources/db/migration/V1__baseline_schema.sql`
Expected: 출력 없음(admin_user 블록의 tenant_id 컬럼·CHECK·FK·인덱스, v_admin_user의 u.tenant_id 참조가 모두 제거됨).
Run: `grep -n "admin_user_tenant" core/src/main/resources/db/migration/V1__baseline_schema.sql`
Expected: 새 테이블 정의 + `fk_aut_*` + `ix_aut_tenant` + GRANT 5줄이 보인다.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/db/migration/V1__baseline_schema.sql
git commit -m "$(cat <<'EOF'
feat(db): admin_user↔tenant N:M — admin_user_tenant 조인 테이블

admin_user.tenant_id 컬럼·FK·CHECK·인덱스 제거, 복합 PK 조인 테이블 신규.
운영DB 없음 → baseline 직접 수정.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: AdminUserTenant 엔티티 + 리포지토리

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/AdminUserTenant.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/AdminUserTenantId.java` (복합 PK)
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserTenantRepository.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/repository/AdminUserTenantRepositoryTest.java`

**Interfaces:**
- Consumes: 기존 `tenant`/`admin_user` 테이블(Task 1).
- Produces:
  - `AdminUserTenantRepository.findTenantIdsByAdminUserId(UUID adminUserId): List<UUID>`
  - `AdminUserTenantRepository.findAdminUserIdsByTenantId(UUID tenantId): List<UUID>`
  - `AdminUserTenantRepository.existsByAdminUserIdAndTenantId(UUID adminUserId, UUID tenantId): boolean`
  - `AdminUserTenantRepository.deleteByAdminUserIdAndTenantId(UUID adminUserId, UUID tenantId): long`
  - `AdminUserTenantRepository.countByAdminUserId(UUID adminUserId): long`
  - `AdminUserTenant.of(UUID adminUserId, UUID tenantId, String createdBy): AdminUserTenant` 팩토리

- [ ] **Step 1: 복합 PK 클래스 작성**

`AdminUserTenantId.java`:

```java
package com.crosscert.passkey.core.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** admin_user_tenant 복합 PK (admin_user_id, tenant_id). */
public class AdminUserTenantId implements Serializable {
    private UUID adminUserId;
    private UUID tenantId;

    public AdminUserTenantId() {}
    public AdminUserTenantId(UUID adminUserId, UUID tenantId) {
        this.adminUserId = adminUserId;
        this.tenantId = tenantId;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AdminUserTenantId that)) return false;
        return Objects.equals(adminUserId, that.adminUserId)
            && Objects.equals(tenantId, that.tenantId);
    }
    @Override public int hashCode() { return Objects.hash(adminUserId, tenantId); }
}
```

- [ ] **Step 2: 엔티티 작성**

`AdminUserTenant.java`:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 운영자 ↔ RP N:M 매핑 행. 복합 PK(admin_user_id, tenant_id). */
@Entity
@Table(name = "ADMIN_USER_TENANT")
@IdClass(AdminUserTenantId.class)
public class AdminUserTenant {

    @Id
    @Column(name = "ADMIN_USER_ID", columnDefinition = "RAW(16)")
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID adminUserId;

    @Id
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)")
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID tenantId;

    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "CREATED_BY", length = 255)
    private String createdBy;

    protected AdminUserTenant() {}

    public static AdminUserTenant of(UUID adminUserId, UUID tenantId, String createdBy) {
        AdminUserTenant m = new AdminUserTenant();
        m.adminUserId = adminUserId;
        m.tenantId = tenantId;
        m.createdBy = createdBy;
        return m;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public UUID getAdminUserId() { return adminUserId; }
    public UUID getTenantId()    { return tenantId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
```

- [ ] **Step 3: 리포지토리 작성**

`AdminUserTenantRepository.java`:

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.entity.AdminUserTenantId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface AdminUserTenantRepository
        extends JpaRepository<AdminUserTenant, AdminUserTenantId> {

    @Query("select m.tenantId from AdminUserTenant m where m.adminUserId = :adminUserId")
    List<UUID> findTenantIdsByAdminUserId(@Param("adminUserId") UUID adminUserId);

    @Query("select m.adminUserId from AdminUserTenant m where m.tenantId = :tenantId")
    List<UUID> findAdminUserIdsByTenantId(@Param("tenantId") UUID tenantId);

    boolean existsByAdminUserIdAndTenantId(UUID adminUserId, UUID tenantId);

    long countByAdminUserId(UUID adminUserId);

    @Transactional
    long deleteByAdminUserIdAndTenantId(UUID adminUserId, UUID tenantId);
}
```

- [ ] **Step 4: 리포지토리 테스트 작성 (실패 확인)**

`AdminUserTenantRepositoryTest.java` — **이 프로젝트의 core 리포지토리 테스트는 `@DataJpaTest`가 아니라 `@SpringBootTest + @ActiveProfiles("test") + @Testcontainers`** 패턴이다(예: `core/src/test/java/com/crosscert/passkey/core/repository/CredentialAuthEventRepositoryTest.java`). 그 파일을 열어 다음을 그대로 복사한다: 내부 `@SpringBootApplication TestApp`(EntityScan/EnableJpaRepositories), `OracleContainer` + `bootstrap-schema.sql` + `@DynamicPropertySource`(APP_ADMIN_USER/admin_pw), `@AfterEach` cleanup, tenant/credential을 만드는 `seed...` 픽스처 헬퍼.

FK 제약(`admin_user_id`→admin_user, `tenant_id`→tenant) 때문에 매핑 INSERT 전에 부모 행이 있어야 한다. CredentialAuthEventRepositoryTest의 `seedCredential`이 tenant를 만드는 방식을 참고해 admin_user + tenant 부모 행을 먼저 저장한다:

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.entity.Tenant;
import org.junit.jupiter.api.Test;
// ↓ 어노테이션/컨테이너/TestApp/@DynamicPropertySource 는 CredentialAuthEventRepositoryTest 에서 복사

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// @SpringBootTest @ActiveProfiles("test") @Testcontainers  (복사)
class AdminUserTenantRepositoryTest {

    // static class TestApp { } + OracleContainer ORACLE + @DynamicPropertySource  (복사)

    // @Autowired AdminUserTenantRepository repo;
    // @Autowired AdminUserRepository adminUsers;
    // @Autowired TenantRepository tenants;
    // @AfterEach cleanup: repo.deleteAll(); adminUsers.deleteAll(); tenants.deleteAll();

    @Test
    void saveAndQueryByAdminUser() {
        // 부모 행 선행 (FK)
        Tenant t1e = tenants.save(new Tenant("aut-t1", /* 기존 Tenant 생성자 인자 복사 */));
        Tenant t2e = tenants.save(new Tenant("aut-t2", /* ... */));
        AdminUser admin = AdminUser.create();
        admin.setEmail("aut-rp@x.com"); admin.setRole("RP_ADMIN"); admin.setBcryptHash("h");
        admin = adminUsers.save(admin);
        UUID adminId = admin.getId(), t1 = t1e.getId(), t2 = t2e.getId();

        repo.save(AdminUserTenant.of(adminId, t1, "tester"));
        repo.save(AdminUserTenant.of(adminId, t2, "tester"));

        assertThat(repo.findTenantIdsByAdminUserId(adminId)).containsExactlyInAnyOrder(t1, t2);
        assertThat(repo.countByAdminUserId(adminId)).isEqualTo(2);
        assertThat(repo.existsByAdminUserIdAndTenantId(adminId, t1)).isTrue();
        assertThat(repo.deleteByAdminUserIdAndTenantId(adminId, t1)).isEqualTo(1);
        assertThat(repo.findTenantIdsByAdminUserId(adminId)).containsExactly(t2);
    }
}
```

> 구현자 주의: `new Tenant(...)`의 정확한 인자(slug + allowedOrigin/acceptedFormat 등)는 CredentialAuthEventRepositoryTest.seedCredential의 Tenant 생성 코드를 복사한다. `AdminUser.create()`는 enabled='Y'/status='ACTIVE'로 시작하므로 email/role/bcryptHash만 채우면 INSERT된다.

- [ ] **Step 5: 테스트 실행 (실패 확인)**

Run: `./gradlew :core:test --tests '*AdminUserTenantRepositoryTest'`
Expected: 컴파일은 되고 테스트가 RED(또는 엔티티 미완성 시 컴파일 에러). 엔티티가 맞으면 GREEN.

- [ ] **Step 6: 테스트 실행 (통과 확인)**

Run: `./gradlew :core:test --tests '*AdminUserTenantRepositoryTest'`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/AdminUserTenant.java \
        core/src/main/java/com/crosscert/passkey/core/entity/AdminUserTenantId.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AdminUserTenantRepository.java \
        core/src/test/java/com/crosscert/passkey/core/repository/AdminUserTenantRepositoryTest.java
git commit -m "$(cat <<'EOF'
feat(core): AdminUserTenant 엔티티·리포지토리 (운영자↔RP N:M)

복합 PK(@IdClass) 매핑, tenantIds/adminUserIds 조회·존재·삭제·카운트.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: AdminUser 엔티티에서 tenant_id 제거

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java:25-30` (tenantId 필드), `:99-100` (getter/setter)
- Test: 기존 `core` 컴파일 + 관련 테스트

**Interfaces:**
- Produces: `AdminUser`에서 `tenantId` 필드·`getTenantId()`·`setTenantId()` 제거. `isPlatformOperator()`/`isRpAdmin()`는 유지.

- [ ] **Step 1: tenantId 필드·주석 제거**

`AdminUser.java` 25-30행의 주석 3줄과 `@Column(name="tenant_id") ... private UUID tenantId;`를 삭제한다.

- [ ] **Step 2: getter/setter 제거**

99-100행의 `public UUID getTenantId()` 와 `public void setTenantId(UUID tenantId)` 두 메서드를 삭제한다. `import java.util.UUID;`가 다른 곳에서 안 쓰이면 함께 제거(쓰이면 유지).

- [ ] **Step 3: core 컴파일 (참조 끊김 확인)**

Run: `./gradlew :core:compileJava`
Expected: core 모듈 내에서는 PASS(엔티티는 core에서 tenantId를 직접 안 씀). 만약 core 내 참조가 남아 에러나면 그 지점을 이 태스크 범위에서 수정.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java
git commit -m "$(cat <<'EOF'
refactor(core): AdminUser.tenantId 제거 — N:M 매핑으로 이관

격리/매핑은 admin_user_tenant 조인 테이블이 담당.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: AdminUserDetails — 단일 tenantId → allowedTenantIds(Set)

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetails.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetailsService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/MeController.java:53`, `admin-app/src/main/java/com/crosscert/passkey/admin/config/MeView.java` (`/me`가 `principal.getTenantId()`를 응답에 실음 → allowedTenantIds로 교체)
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/AdminUserDetailsTest.java` (신규 또는 기존 확장)

**Interfaces:**
- Consumes: `AdminUserTenantRepository.findTenantIdsByAdminUserId(UUID)` (Task 2), `AdminUser` (Task 3).
- Produces:
  - `AdminUserDetails` 생성자: `... String role, Set<UUID> allowedTenantIds, boolean enabled, ...` (기존 `UUID tenantId` 자리를 `Set<UUID> allowedTenantIds`로 교체)
  - `AdminUserDetails.getAllowedTenantIds(): Set<UUID>` (불변 복사본)
  - 기존 `getTenantId()` 제거.
  - `MeView`에 `tenantIds: List<UUID>` (기존 `tenantId` 교체) — admin-ui가 소비.

- [ ] **Step 1: AdminUserDetails 테스트 작성 (실패 확인)**

`AdminUserDetailsTest.java`:

```java
package com.crosscert.passkey.admin.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserDetailsTest {

    private AdminUserDetails rpAdmin(Set<UUID> tenants) {
        return new AdminUserDetails(UUID.randomUUID(), "rp@x.com", "hash",
                "RP_ADMIN", tenants, true, null, Clock.systemUTC());
    }

    @Test
    void rpAdminExposesAllowedTenants() {
        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID();
        AdminUserDetails me = rpAdmin(Set.of(t1, t2));
        assertThat(me.isRpAdmin()).isTrue();
        assertThat(me.getAllowedTenantIds()).containsExactlyInAnyOrder(t1, t2);
    }

    @Test
    void platformOperatorHasEmptyAllowedTenants() {
        AdminUserDetails me = new AdminUserDetails(UUID.randomUUID(), "ops@x.com", "hash",
                "PLATFORM_OPERATOR", Set.of(), true, null, Clock.systemUTC());
        assertThat(me.isPlatformOperator()).isTrue();
        assertThat(me.getAllowedTenantIds()).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `./gradlew :admin-app:test --tests '*AdminUserDetailsTest'`
Expected: 컴파일 에러(생성자 시그니처 불일치 / getAllowedTenantIds 없음).

- [ ] **Step 3: AdminUserDetails 수정**

`AdminUserDetails.java`에서 `private final UUID tenantId;` → `private final java.util.Set<UUID> allowedTenantIds;`. 생성자 파라미터와 본문 교체, getter 교체:

```java
    private final java.util.Set<UUID> allowedTenantIds;   // PLATFORM_OPERATOR 는 빈 Set

    public AdminUserDetails(UUID id, String email, String passwordHash,
                            String role, java.util.Set<UUID> allowedTenantIds, boolean enabled,
                            java.time.OffsetDateTime lockedUntil, java.time.Clock clock) {
        this.id = Objects.requireNonNull(id);
        this.email = Objects.requireNonNull(email);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.role = Objects.requireNonNull(role);
        this.allowedTenantIds = java.util.Set.copyOf(Objects.requireNonNull(allowedTenantIds));
        this.enabled = enabled;
        this.lockedUntil = lockedUntil;
        this.clock = Objects.requireNonNull(clock);
    }

    public java.util.Set<UUID> getAllowedTenantIds() { return allowedTenantIds; }
```

기존 `public UUID getTenantId() { return tenantId; }` 라인을 삭제한다. `isPlatformOperator()`/`isRpAdmin()`는 그대로.

- [ ] **Step 4: AdminUserDetailsService 수정**

`AdminUserDetailsService.java`에 리포지토리 주입 + tenantIds 로드. 클래스에 필드 추가:

```java
    private final AdminUserTenantRepository mappingRepo;
```

(생성자 주입이면 파라미터 추가, `@RequiredArgsConstructor`면 final 필드 추가만으로 충분 — 기존 패턴을 따른다. import: `com.crosscert.passkey.core.repository.AdminUserTenantRepository`.)

`loadUserByUsername` 본문 교체:

```java
    @Override
    public AdminUserDetails loadUserByUsername(String email) {
        AdminUser u = repo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("admin not found: " + email));
        java.util.Set<UUID> allowed = new java.util.HashSet<>(
                mappingRepo.findTenantIdsByAdminUserId(u.getId()));
        return new AdminUserDetails(
                u.getId(),
                u.getEmail(),
                u.getBcryptHash(),
                u.getRole(),
                allowed,          // PLATFORM_OPERATOR 는 빈 Set, RP_ADMIN 은 1개 이상
                u.isEnabled(),
                u.getLockedUntil(),
                clock
        );
    }
```

(import: `java.util.UUID`)

- [ ] **Step 5: MeController + MeView — getTenantId() 참조 교체**

`MeView.java`의 `tenantId` 컴포넌트(record)를 `java.util.List<UUID> tenantIds`로 바꾼다. `MeController.java:50-57`에서 `principal.getTenantId()` → `new java.util.ArrayList<>(principal.getAllowedTenantIds())`로 교체(MeView 생성 인자). import 정리(`java.util.List`/`java.util.UUID`).

> 이 단계가 빠지면 `principal.getTenantId()`가 컴파일 에러(Task 4 Step 3에서 getter 제거됨). admin-ui의 MeView 소비측(`appRoute.ts`, `roles.ts` 등 `tenantId` 참조)은 Task 10에서 함께 정리.

- [ ] **Step 6: 테스트 실행 (통과 확인)**

Run: `./gradlew :admin-app:test --tests '*AdminUserDetailsTest'`
Expected: PASS (MeController 컴파일 포함).

- [ ] **Step 7: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetails.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetailsService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/MeController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/MeView.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/auth/AdminUserDetailsTest.java
git commit -m "$(cat <<'EOF'
feat(admin): AdminUserDetails 단일 tenantId → allowedTenantIds(Set)

로그인 시 admin_user_tenant 에서 허용 RP 집합 로드. PLATFORM_OPERATOR 는 빈 Set.
MeController/MeView 의 /me 응답도 tenantId→tenantIds 로 교체.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: ActiveTenantResolver — 세션 기반 활성 RP 해석

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/ActiveTenantResolver.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/ActiveTenantResolverTest.java`

**Interfaces:**
- Consumes: `AdminUserDetails.getAllowedTenantIds()` (Task 4), 현재 요청의 `HttpSession`.
- Produces:
  - 상수 `ActiveTenantResolver.ACTIVE_TENANT_ATTR = "ACTIVE_TENANT_ID"`
  - `resolve(AdminUserDetails me): UUID` — RP_ADMIN의 현재 활성 RP를 반환(세션에 없거나 허용범위 밖이면 allowedTenantIds 정렬 후 첫 번째로 재설정 후 반환). allowedTenantIds가 비면 `null`.
  - `setActive(AdminUserDetails me, UUID tenantId): void` — tenantId가 allowedTenantIds에 있으면 세션에 기록, 아니면 `BusinessException(ACCESS_DENIED)`.

- [ ] **Step 1: 테스트 작성 (실패 확인)**

`ActiveTenantResolverTest.java` — `MockHttpServletRequest` + `RequestContextHolder`로 세션을 모킹한다:

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.api.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveTenantResolverTest {

    private final ActiveTenantResolver resolver = new ActiveTenantResolver();

    private void bindRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @AfterEach
    void clear() { RequestContextHolder.resetRequestAttributes(); }

    private AdminUserDetails rpAdmin(Set<UUID> tenants) {
        return new AdminUserDetails(UUID.randomUUID(), "rp@x.com", "hash",
                "RP_ADMIN", tenants, true, null, Clock.systemUTC());
    }

    @Test
    void defaultsToFirstSortedTenantWhenSessionEmpty() {
        bindRequest();
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        AdminUserDetails me = rpAdmin(Set.of(b, a));
        UUID expectedFirst = new TreeSet<>(Set.of(a, b)).first();
        assertThat(resolver.resolve(me)).isEqualTo(expectedFirst);
    }

    @Test
    void setActiveWithinAllowedPersists() {
        bindRequest();
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        AdminUserDetails me = rpAdmin(Set.of(a, b));
        resolver.setActive(me, b);
        assertThat(resolver.resolve(me)).isEqualTo(b);
    }

    @Test
    void setActiveOutsideAllowedRejected() {
        bindRequest();
        UUID a = new UUID(0, 1);
        AdminUserDetails me = rpAdmin(Set.of(a));
        assertThatThrownBy(() -> resolver.setActive(me, new UUID(0, 9)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void staleSessionValueOutsideAllowedFallsBackToDefault() {
        bindRequest();
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        AdminUserDetails me = rpAdmin(Set.of(a, b));
        // 세션에 허용범위 밖 값을 강제로 심어도 resolve 가 기본값으로 복구
        RequestContextHolder.getRequestAttributes()
                .setAttribute(ActiveTenantResolver.ACTIVE_TENANT_ATTR, new UUID(0, 99),
                        ServletRequestAttributes.SCOPE_SESSION);
        assertThat(me.getAllowedTenantIds()).contains(resolver.resolve(me));
    }
}
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `./gradlew :admin-app:test --tests '*ActiveTenantResolverTest'`
Expected: 컴파일 에러(ActiveTenantResolver 없음).

- [ ] **Step 3: 구현 작성**

`ActiveTenantResolver.java`:

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * RP_ADMIN 의 "현재 활성 RP" 를 HttpSession 속성으로 관리한다.
 * 활성 RP 는 항상 allowedTenantIds 범위 안에서만 선택 가능(세션 변조 방어).
 * 세션에 없거나 허용범위를 벗어난 값이면 정렬상 첫 RP 로 복구한다(결정적 기본값).
 */
@Component
public class ActiveTenantResolver {

    public static final String ACTIVE_TENANT_ATTR = "ACTIVE_TENANT_ID";

    /** RP_ADMIN 의 현재 활성 RP. allowedTenantIds 가 비면 null. */
    public UUID resolve(AdminUserDetails me) {
        Set<UUID> allowed = me.getAllowedTenantIds();
        if (allowed.isEmpty()) return null;
        UUID fromSession = (UUID) getAttr(ACTIVE_TENANT_ATTR);
        if (fromSession != null && allowed.contains(fromSession)) {
            return fromSession;
        }
        UUID fallback = new TreeSet<>(allowed).first();
        setAttr(ACTIVE_TENANT_ATTR, fallback);
        return fallback;
    }

    /** 활성 RP 변경. allowedTenantIds 밖이면 ACCESS_DENIED. */
    public void setActive(AdminUserDetails me, UUID tenantId) {
        if (!me.getAllowedTenantIds().contains(tenantId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "tenant not in allowed set: " + tenantId);
        }
        setAttr(ACTIVE_TENANT_ATTR, tenantId);
    }

    private Object getAttr(String name) {
        ServletRequestAttributes attrs = current();
        return attrs == null ? null
                : attrs.getAttribute(name, ServletRequestAttributes.SCOPE_SESSION);
    }

    private void setAttr(String name, Object value) {
        ServletRequestAttributes attrs = current();
        if (attrs != null) {
            attrs.setAttribute(name, value, ServletRequestAttributes.SCOPE_SESSION);
        }
    }

    private ServletRequestAttributes current() {
        var ra = RequestContextHolder.getRequestAttributes();
        return (ra instanceof ServletRequestAttributes sra) ? sra : null;
    }
}
```

- [ ] **Step 4: 테스트 실행 (통과 확인)**

Run: `./gradlew :admin-app:test --tests '*ActiveTenantResolverTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/ActiveTenantResolver.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/auth/ActiveTenantResolverTest.java
git commit -m "$(cat <<'EOF'
feat(admin): ActiveTenantResolver — 세션 기반 활성 RP(스위처 백엔드)

활성 RP 는 allowedTenantIds 범위 안에서만 선택, 세션 stale 값은 정렬 첫 RP 로 복구.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: TenantBoundary — allowedTenantIds + 활성 RP 반영

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/TenantBoundary.java:43-85`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/TenantBoundaryTest.java` (신규 또는 기존 확장)

**Interfaces:**
- Consumes: `AdminUserDetails.getAllowedTenantIds()` (Task 4), `ActiveTenantResolver.resolve(AdminUserDetails)` (Task 5).
- Produces:
  - `assertCanAccessTenant(UUID)` — RP_ADMIN은 `allowedTenantIds.contains(tenantId)`로 판정.
  - `currentTenantScope()` — RP_ADMIN은 `Optional.of(activeTenant)` 반환(활성 RP 하나).

- [ ] **Step 1: 테스트 작성 (실패 확인)**

`TenantBoundaryTest.java` — `ActiveTenantResolver`와 `ApplicationEventPublisher`를 모킹하고, `SecurityContextHolder`에 principal을 심는다:

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.api.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantBoundaryTest {

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ActiveTenantResolver activeTenant = mock(ActiveTenantResolver.class);
    private final TenantBoundary boundary = new TenantBoundary(publisher, activeTenant);

    private void login(AdminUserDetails me) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(me, "x", me.getAuthorities()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private AdminUserDetails rpAdmin(Set<UUID> tenants) {
        return new AdminUserDetails(UUID.randomUUID(), "rp@x.com", "hash",
                "RP_ADMIN", tenants, true, null, Clock.systemUTC());
    }

    @Test
    void rpAdminCanAccessAnyAllowedTenant() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        login(rpAdmin(Set.of(a, b)));
        boundary.assertCanAccessTenant(a);   // no throw
        boundary.assertCanAccessTenant(b);   // no throw
    }

    @Test
    void rpAdminCannotAccessTenantOutsideAllowed() {
        UUID a = UUID.randomUUID();
        login(rpAdmin(Set.of(a)));
        assertThatThrownBy(() -> boundary.assertCanAccessTenant(UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void scopeReturnsActiveTenantForRpAdmin() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        AdminUserDetails me = rpAdmin(Set.of(a, b));
        login(me);
        when(activeTenant.resolve(me)).thenReturn(b);
        assertThat(boundary.currentTenantScope()).isEqualTo(Optional.of(b));
    }

    @Test
    void scopeEmptyForPlatformOperator() {
        AdminUserDetails me = new AdminUserDetails(UUID.randomUUID(), "ops@x.com", "hash",
                "PLATFORM_OPERATOR", Set.of(), true, null, Clock.systemUTC());
        login(me);
        assertThat(boundary.currentTenantScope()).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `./gradlew :admin-app:test --tests '*TenantBoundaryTest'`
Expected: 컴파일 에러(생성자에 ActiveTenantResolver 없음 / getTenantId 참조).

- [ ] **Step 3: TenantBoundary 수정 — 의존성 + 두 메서드**

`TenantBoundary.java`에 필드 추가(`@RequiredArgsConstructor`이므로 final 필드만 추가; 선언 순서 = 생성자 인자 순서 주의):

```java
    private final ApplicationEventPublisher eventPublisher;
    private final ActiveTenantResolver activeTenantResolver;
```

`assertCanAccessTenant`의 RP_ADMIN 분기(47행) 교체 — `!me.getTenantId().equals(tenantId)` → `!me.getAllowedTenantIds().contains(tenantId)`:

```java
        if (me.isRpAdmin()) {
            if (!me.getAllowedTenantIds().contains(tenantId)) {
                log.warn("tenant boundary violation: actor={} role={} requested={} allowed={}",
                        CryptoUtils.maskEmail(me.getUsername()), me.getRole(), tenantId, me.getAllowedTenantIds());
                publishViolation(Map.of(
                        "actor", CryptoUtils.maskEmail(me.getUsername()),
                        "role", String.valueOf(me.getRole()),
                        "requested", String.valueOf(tenantId),
                        "allowed", String.valueOf(me.getAllowedTenantIds())));
                throw new BusinessException(ErrorCode.ACCESS_DENIED,
                        "RP_ADMIN cannot access tenant " + tenantId);
            }
            return;
        }
```

`currentTenantScope`의 RP_ADMIN 분기(76행) 교체 — `Optional.of(me.getTenantId())` → 활성 RP:

```java
        if (me.isRpAdmin()) {
            UUID active = activeTenantResolver.resolve(me);
            return active == null ? Optional.empty() : Optional.of(active);
        }
```

- [ ] **Step 4: 테스트 실행 (통과 확인)**

Run: `./gradlew :admin-app:test --tests '*TenantBoundaryTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/TenantBoundary.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/auth/TenantBoundaryTest.java
git commit -m "$(cat <<'EOF'
feat(admin): TenantBoundary 다중 RP — allowedTenantIds + 활성 RP scope

assertCanAccessTenant 는 허용집합 contains 판정, currentTenantScope 는
ActiveTenantResolver 의 활성 RP 하나만 반환(Hibernate @Filter 격리 유지).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: AdminUserService — 복수 tenant 초대 + 매핑 추가/회수

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserService.java` (명시적 생성자 — `@RequiredArgsConstructor` 아님; `toView`는 `static` + `list()`에서 `AdminUserService::toView` 메서드 참조)
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserDto.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationService.java:76-77` (`InvitationCheck`를 `user.getTenantId()`로 생성 — N:M으로 교체)
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/operator/AdminUserServiceTest.java` (신규 또는 기존 확장)

**Interfaces:**
- Consumes: `AdminUserTenantRepository` (Task 2).
- Produces:
  - `InviteRequest(String email, String role, List<UUID> tenantIds)` — 단수 `tenantId` 제거.
  - `View(... List<UUID> tenantIds ...)` — 단수 `tenantId` → 복수.
  - `InvitationCheck(... List<UUID> tenantIds ...)`.
  - `AdminUserService.addTenant(UUID adminUserId, UUID tenantId, String actor): void`
  - `AdminUserService.removeTenant(UUID adminUserId, UUID tenantId, String actor): void` (마지막 매핑 회수 차단)
  - `toView`는 `static` → **인스턴스 메서드**로 전환(매핑 조회 필요). `list()`의 `AdminUserService::toView` → `this::toView`.

- [ ] **Step 1: DTO 변경**

`AdminUserDto.java`:
- `InviteRequest`: `UUID tenantId` → `java.util.List<UUID> tenantIds` (record 컴포넌트 교체).
- `View`: `UUID tenantId` → `java.util.List<UUID> tenantIds`.
- `InvitationCheck`: `UUID tenantId` → `java.util.List<UUID> tenantIds`.

(import `java.util.List`, `java.util.UUID` 확인)

- [ ] **Step 2: 서비스 테스트 작성 (실패 확인)**

`AdminUserServiceTest.java` — 리포지토리를 모킹한 순수 단위 테스트(검증 로직 위주):

```java
package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminUserServiceTest {

    private final AdminUserRepository userRepo = mock(AdminUserRepository.class);
    private final AdminUserTenantRepository mappingRepo = mock(AdminUserTenantRepository.class);
    // 나머지 협력자(invitationService 등)는 기존 생성자 시그니처에 맞춰 mock 주입.

    // 주의: 실제 AdminUserService 생성자 인자에 맞게 아래를 구성한다.
    // private final AdminUserService service = new AdminUserService(userRepo, ..., mappingRepo, clock);

    @Test
    void rpAdminInviteRequiresAtLeastOneTenant() {
        // role=RP_ADMIN, tenantIds=빈 리스트 → IllegalArgumentException
        // when(userRepo.findByEmail(any())).thenReturn(Optional.empty());
        // assertThatThrownBy(() -> service.invite(
        //         new AdminUserDto.InviteRequest("rp@x.com", "RP_ADMIN", List.of()), "alice"))
        //     .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void platformOperatorInviteMustHaveNoTenant() {
        // role=PLATFORM_OPERATOR, tenantIds 비어있지 않음 → IllegalArgumentException
    }

    @Test
    void removeLastTenantOfRpAdminRejected() {
        // countByAdminUserId == 1 인 RP_ADMIN 의 removeTenant → IllegalStateException
    }
}
```

> 구현자 주의: `AdminUserService`의 실제 생성자 인자(invitationService 등)를 확인해 모든 협력자를 mock으로 주입하라. 위 본문 주석을 실제 호출로 채운다. 핵심은 세 가지 검증 분기다.

- [ ] **Step 3: invite() 교체**

`AdminUserService.invite()` 본문을 복수 tenant로 교체:

```java
    @Transactional
    public AdminUserDto.InviteResponse invite(AdminUserDto.InviteRequest req, String invitedBy) {
        if (!"PLATFORM_OPERATOR".equals(req.role()) && !"RP_ADMIN".equals(req.role())) {
            throw new IllegalArgumentException("Invalid role: " + req.role());
        }
        java.util.List<UUID> tenantIds = req.tenantIds() == null
                ? java.util.List.of() : req.tenantIds();
        if ("RP_ADMIN".equals(req.role()) && tenantIds.isEmpty()) {
            throw new IllegalArgumentException("RP_ADMIN requires at least one tenant");
        }
        if ("PLATFORM_OPERATOR".equals(req.role()) && !tenantIds.isEmpty()) {
            throw new IllegalArgumentException("PLATFORM_OPERATOR must not have tenant");
        }
        if (userRepo.findByEmail(req.email()).isPresent()) {
            throw new IllegalStateException("Email already exists: " + req.email());
        }

        AdminUser user = AdminUser.create();
        user.setEmail(req.email());
        user.setRole(req.role());
        user.setStatus("PENDING");
        user.setCreatedBy(invitedBy);
        AdminUser saved = userRepo.save(user);

        for (UUID tid : new java.util.LinkedHashSet<>(tenantIds)) {   // 중복 멱등
            mappingRepo.save(com.crosscert.passkey.core.entity.AdminUserTenant.of(
                    saved.getId(), tid, invitedBy));
        }

        var inv = invitationService.createInvitation(saved.getId(), invitedBy, req.email());
        log.info("admin invite issued: emailMasked={} role={} tenantCount={}",
                mask(req.email()), req.role(), tenantIds.size());
        return new AdminUserDto.InviteResponse(toView(saved), inv);
    }
```

**중요 — AdminUserService는 명시적 생성자(`@RequiredArgsConstructor` 아님)다.** `private final AdminUserTenantRepository mappingRepo;` 필드를 추가하고, **생성자 시그니처와 본문에도 직접 추가**한다:

```java
    private final AdminUserRepository userRepo;
    private final AdminUserInvitationRepository invitationRepo;
    private final InvitationService invitationService;
    private final AdminUserTenantRepository mappingRepo;   // ← 추가
    private final Clock clock;

    public AdminUserService(AdminUserRepository userRepo,
                            AdminUserInvitationRepository invitationRepo,
                            InvitationService invitationService,
                            AdminUserTenantRepository mappingRepo,   // ← 추가
                            Clock clock) {
        this.userRepo = userRepo;
        this.invitationRepo = invitationRepo;
        this.invitationService = invitationService;
        this.mappingRepo = mappingRepo;                  // ← 추가
        this.clock = clock;
    }
```

(import: `com.crosscert.passkey.core.repository.AdminUserTenantRepository`)

- [ ] **Step 4: addTenant / removeTenant 추가**

```java
    @Transactional
    public void addTenant(UUID adminUserId, UUID tenantId, String actor) {
        AdminUser u = userRepo.findById(adminUserId)
                .orElseThrow(() -> new IllegalStateException("admin not found: " + adminUserId));
        if (!"RP_ADMIN".equals(u.getRole())) {
            throw new IllegalArgumentException("only RP_ADMIN can be mapped to a tenant");
        }
        if (mappingRepo.existsByAdminUserIdAndTenantId(adminUserId, tenantId)) {
            return; // 멱등
        }
        mappingRepo.save(com.crosscert.passkey.core.entity.AdminUserTenant.of(adminUserId, tenantId, actor));
        log.info("admin tenant added: adminId={} tenantId={} by={}", adminUserId, tenantId, mask(actor));
    }

    @Transactional
    public void removeTenant(UUID adminUserId, UUID tenantId, String actor) {
        AdminUser u = userRepo.findById(adminUserId)
                .orElseThrow(() -> new IllegalStateException("admin not found: " + adminUserId));
        if ("RP_ADMIN".equals(u.getRole()) && mappingRepo.countByAdminUserId(adminUserId) <= 1) {
            throw new IllegalStateException("cannot remove last tenant of RP_ADMIN");
        }
        mappingRepo.deleteByAdminUserIdAndTenantId(adminUserId, tenantId);
        log.info("admin tenant removed: adminId={} tenantId={} by={}", adminUserId, tenantId, mask(actor));
    }
```

- [ ] **Step 5: toView() static → instance 전환 + 호출부 수정**

현재 `toView`는 `static`이고 `list()`(36행)에서 `userRepo.findAll().stream().map(AdminUserService::toView)`로 **메서드 참조** 호출된다. 매핑 조회가 필요하므로 인스턴스 메서드로 바꾸고, 메서드 참조도 `this::toView`로 바꾼다:

```java
    AdminUserDto.View toView(AdminUser u) {                       // static 제거
        java.util.List<UUID> tids = mappingRepo.findTenantIdsByAdminUserId(u.getId());
        return new AdminUserDto.View(
                u.getId(), u.getEmail(), u.getRole(),
                u.getStatus() != null ? u.getStatus() : "ACTIVE",
                tids,
                u.getCreatedAt(), u.getLastLoginAt(),
                u.getSuspendedAt(), u.getCreatedBy(), u.isMfaEnabled());
    }
```

`list()`의 메서드 참조 교체:

```java
        return userRepo.findAll().stream().map(this::toView).toList();   // AdminSerivce::toView → this::toView
```

(`invite()` 안의 `toView(saved)` 호출은 이미 인스턴스 컨텍스트라 그대로 동작.)

- [ ] **Step 6: InvitationService — InvitationCheck를 매핑 기준으로**

`InvitationService.java:76-77`은 `new AdminUserDto.InvitationCheck(user.getEmail(), user.getRole(), user.getTenantId(), inv.getExpiresAt())`로 단수 tenantId를 쓴다. 컬럼이 사라지므로 매핑 조회로 바꾼다. `InvitationService`에 `AdminUserTenantRepository mappingRepo`를 주입(이 클래스가 `@RequiredArgsConstructor`인지 명시적 생성자인지 파일 상단을 확인하고 같은 방식으로 추가)하고:

```java
        java.util.List<UUID> tids = mappingRepo.findTenantIdsByAdminUserId(user.getId());
        return new AdminUserDto.InvitationCheck(
                user.getEmail(), user.getRole(), tids, inv.getExpiresAt());
```

(import: `com.crosscert.passkey.core.repository.AdminUserTenantRepository`, `java.util.List`, `java.util.UUID`)

- [ ] **Step 8: 테스트 실행 (통과 확인)**

Run: `./gradlew :admin-app:test --tests '*AdminUserServiceTest'`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserDto.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/operator/AdminUserServiceTest.java
git commit -m "$(cat <<'EOF'
feat(admin): 복수 tenant 초대 + 매핑 추가/회수(마지막 회수 차단)

InviteRequest/View/InvitationCheck tenantId→tenantIds, addTenant/removeTenant.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: 컨트롤러 — 매핑 추가/회수 + 활성 RP 스위처 API

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserController.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/ActiveTenantController.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/operator/AdminUserControllerTest.java` (기존 슬라이스 패턴 확장; 없으면 생략 가능하고 Task 11 IT가 커버)

**Interfaces:**
- Consumes: `AdminUserService.addTenant/removeTenant` (Task 7), `ActiveTenantResolver.setActive` (Task 5), `TenantBoundary` (현재 principal).
- Produces:
  - `POST /admin/api/admin-users/{id}/tenants` body `{ "tenantId": "<uuid>" }`
  - `DELETE /admin/api/admin-users/{id}/tenants/{tenantId}`
  - `POST /admin/api/active-tenant` body `{ "tenantId": "<uuid>" }` (RP_ADMIN/PLATFORM_OPERATOR 본인 세션)
  - `GET /admin/api/active-tenant` → `{ "activeTenantId": "<uuid|null>", "allowedTenantIds": [...] }`

- [ ] **Step 1: AdminUserController에 매핑 엔드포인트 추가**

클래스가 이미 `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")`이므로 메서드는 추가만:

```java
    public record TenantRef(@jakarta.validation.constraints.NotNull java.util.UUID tenantId) {}

    @PostMapping("/{id}/tenants")
    public void addTenant(@PathVariable java.util.UUID id,
                          @org.springframework.web.bind.annotation.RequestBody TenantRef body,
                          Authentication auth) {
        service.addTenant(id, body.tenantId(), auth.getName());
    }

    @DeleteMapping("/{id}/tenants/{tenantId}")
    public void removeTenant(@PathVariable java.util.UUID id,
                             @PathVariable java.util.UUID tenantId,
                             Authentication auth) {
        service.removeTenant(id, tenantId, auth.getName());
    }
```

(import: `org.springframework.web.bind.annotation.DeleteMapping`, `PathVariable` 등 기존에 없으면 추가)

- [ ] **Step 2: ActiveTenantController 작성**

```java
package com.crosscert.passkey.admin.auth;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 로그인 운영자 본인의 "현재 활성 RP" 조회/변경(스위처).
 * 본인 세션 한정 — 별도 role 게이트 없이 allowedTenantIds 범위로만 제한된다.
 */
@RestController
@RequestMapping("/admin/api/active-tenant")
public class ActiveTenantController {

    private final ActiveTenantResolver resolver;

    public ActiveTenantController(ActiveTenantResolver resolver) {
        this.resolver = resolver;
    }

    public record SwitchRequest(UUID tenantId) {}
    public record ActiveTenantView(UUID activeTenantId, List<UUID> allowedTenantIds) {}

    @GetMapping
    public ActiveTenantView current() {
        AdminUserDetails me = principal();
        return new ActiveTenantView(resolver.resolve(me), List.copyOf(me.getAllowedTenantIds()));
    }

    @PostMapping
    public ActiveTenantView switchTenant(@RequestBody SwitchRequest req) {
        AdminUserDetails me = principal();
        resolver.setActive(me, req.tenantId());   // 허용범위 밖이면 ACCESS_DENIED
        return new ActiveTenantView(resolver.resolve(me), List.copyOf(me.getAllowedTenantIds()));
    }

    private AdminUserDetails principal() {
        return (AdminUserDetails) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
    }
}
```

- [ ] **Step 3: SecurityConfig 인가 경로 확인 (매처 추가 불필요 — 검증 완료)**

`AdminSecurityConfig.java`는 `permitAll` 화이트리스트 뒤에 `.requestMatchers("/admin/api/**").authenticated()` 포괄 규칙(약 117행)과 `.anyRequest().denyAll()`을 둔다. 따라서 새 `/admin/api/active-tenant`·`/admin/api/admin-users/{id}/tenants/**`는 **자동으로 authenticated 보호**된다. 세부 권한은 컨트롤러의 `@PreAuthorize`(매핑 추가/회수=PLATFORM_OPERATOR)와 `ActiveTenantResolver`(본인 allowedTenantIds 범위)가 담당. **별도 매처 추가 불필요.**

CSRF: 면제는 `/admin/api/invitations/**`·`/admin/api/password-reset/**`에만 걸려 있고 새 엔드포인트는 CSRF 보호 대상이다(쿠키 세션 + POST/DELETE). admin-ui가 `X-XSRF-TOKEN` 헤더를 보내는지는 Task 10에서 보장(기존 `adminFetch`/`client.ts`가 이미 처리).

Run: `grep -n "admin/api" admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java`
Expected: `/admin/api/**` → `.authenticated()` 포괄 규칙 확인. 새 경로용 매처가 없어도 정상.

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :admin-app:compileJava`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/auth/ActiveTenantController.java
git commit -m "$(cat <<'EOF'
feat(admin): 매핑 추가/회수 API + 활성 RP 스위처 API

POST/DELETE /admin-users/{id}/tenants (PLATFORM_OPERATOR),
GET/POST /admin/api/active-tenant (본인 세션, allowedTenantIds 범위).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: 시드 수정 — dev/test bob을 매핑 테이블 기준으로

**Files:**
- Modify: `core/src/main/resources/db/dev/R__seed_dev_tenant.sql:144-154`
- Modify: `admin-app/src/test/resources/db/testfix/V9001__test_seed_operators.sql:26-31`
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java:198-204`

**Interfaces:**
- Consumes: `admin_user_tenant` 테이블 (Task 1).
- Produces: bob(RP_ADMIN)이 `admin_user.tenant_id` 대신 `admin_user_tenant` 행으로 dev-passkey/demo-rp에 매핑.

- [ ] **Step 1: dev 시드 — bob INSERT에서 tenant_id 제거 + 매핑 INSERT 추가**

`R__seed_dev_tenant.sql`의 bob INSERT(144-154행)에서 컬럼 목록의 `tenant_id`와 SELECT의 `(SELECT id FROM tenant WHERE slug = 'dev-passkey'),`를 제거한다(나머지는 그대로). 그 아래에 매핑 INSERT를 추가:

```sql
-- bob(RP_ADMIN) ↔ dev-passkey 매핑 (admin_user_tenant)
INSERT INTO admin_user_tenant (admin_user_id, tenant_id, created_at, created_by)
SELECT HEXTORAW('00000000000000000000000000000011'),
       (SELECT id FROM tenant WHERE slug = 'dev-passkey'),
       SYSTIMESTAMP, 'seed'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'dev-passkey')
  AND EXISTS (SELECT 1 FROM admin_user WHERE email = 'bob@crosscert.com')
  AND NOT EXISTS (
    SELECT 1 FROM admin_user_tenant
     WHERE admin_user_id = HEXTORAW('00000000000000000000000000000011')
       AND tenant_id = (SELECT id FROM tenant WHERE slug = 'dev-passkey')
  );
```

(bob INSERT 자체의 role은 'RP_ADMIN' 그대로 유지)

- [ ] **Step 2: test 시드 — 변화 없음 확인**

`V9001__test_seed_operators.sql`의 bob은 이미 PLATFORM_OPERATOR(tenant_id 미지정)로 INSERT되므로 변경 불필요. 단, 혹시 `tenant_id` 컬럼을 명시하는 줄이 있으면 제거(현재 alice/bob 모두 미지정이라 그대로 둔다). 검증:

Run: `grep -n "tenant_id" admin-app/src/test/resources/db/testfix/V9001__test_seed_operators.sql`
Expected: 출력 없음(시드에 tenant_id 컬럼 미사용).

- [ ] **Step 3: AdminFlowIT.resetState() — UPDATE를 매핑 INSERT로 교체**

198-204행의 bob RP_ADMIN UPDATE를 다음으로 교체(role만 UPDATE, tenant는 매핑 INSERT):

```java
        // bob 을 demo-rp 의 RP_ADMIN 으로 재할당 (role + admin_user_tenant 매핑)
        jdbc.update("""
                UPDATE APP_OWNER.admin_user
                   SET role = 'RP_ADMIN'
                 WHERE email = 'bob@crosscert.com'
                """);
        jdbc.update("""
                MERGE INTO APP_OWNER.admin_user_tenant t
                USING (SELECT HEXTORAW('00000000000000000000000000000011') AS aid,
                              HEXTORAW('0000000000000000000000000000C0DE') AS tid FROM dual) s
                   ON (t.admin_user_id = s.aid AND t.tenant_id = s.tid)
                 WHEN NOT MATCHED THEN
                   INSERT (admin_user_id, tenant_id, created_at, created_by)
                   VALUES (s.aid, s.tid, SYSTIMESTAMP, 'it')
                """);
```

> 구현자 주의: `resetState()`가 매 테스트마다 호출된다면, bob의 기존 매핑을 먼저 정리(`DELETE FROM admin_user_tenant WHERE admin_user_id = ...`)한 뒤 INSERT하는 편이 멱등적이다. 기존 resetState의 다른 정리 패턴을 따른다. demo-rp의 고정 UUID(`0000...C0DE`)는 기존 코드에서 가져온 값 그대로 사용.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/resources/db/dev/R__seed_dev_tenant.sql \
        admin-app/src/test/resources/db/testfix/V9001__test_seed_operators.sql \
        admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java
git commit -m "$(cat <<'EOF'
chore(seed): bob(RP_ADMIN) 매핑을 admin_user_tenant 기준으로 전환

dev 시드·IT resetState 의 tenant_id 직접지정 → 조인 테이블 INSERT.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: admin-ui — 다중 tenant 선택 + RP 스위처

**Files:**
- Modify: `admin-ui/src/pages/settings/AdminUsersTab.tsx` (초대 폼 tenant 다중 선택, 목록에 RP 수 표시)
- Modify: `admin-ui/src/api/adminUsers.ts` (`invite` body `tenantId?` → `tenantIds: string[]`, `AdminUserView.tenantId` → `tenantIds`, addTenant/removeTenant 추가)
- Modify: `admin-ui/src/shell/Header.tsx` (RP 스위처 드롭다운)
- Modify: MeView 소비측 — `admin-ui/src/api/adminUsers.ts`/`appRoute.ts`/`me/roles.ts` 등 `tenantId`(단수) 참조를 `tenantIds`로 (Task 4의 MeView 변경에 맞춤). `grep -rn "tenantId" admin-ui/src`로 전수 확인.
- Create: `admin-ui/src/api/activeTenant.ts` (active-tenant API 클라이언트)
- Test: 기존 admin-ui 테스트 패턴(`*.test.tsx`)이 있으면 따르고, 없으면 수동 검증(Task 11 IT + 브라우저 dogfooding)으로 대체

**Interfaces:**
- Consumes: `POST /admin/api/admin-users` (tenantIds 배열), `GET/POST /admin/api/active-tenant`, `POST/DELETE /admin/api/admin-users/{id}/tenants/**` (Task 8).
- Produces: 초대 폼이 `tenantIds: string[]` 전송, 헤더 스위처가 활성 RP를 전환.

- [ ] **Step 1: API 클라이언트 작성**

**중요 — 헬퍼 선택:** 새 컨트롤러(`ActiveTenantController`, `AdminUserController` 추가분)는 `@RestController`로 **raw JSON**을 반환한다(ApiResponse 엔벨로프 아님). admin-ui에서 raw JSON을 다루는 헬퍼는 `client.ts`의 `api.*`(엔벨로프 언래핑)가 아니라 **`adminUsers.ts`의 `adminFetch`**(raw + `credentials:'include'` + `X-XSRF-TOKEN`)다. active-tenant 클라이언트도 같은 `adminFetch` 패턴으로 만든다. `adminUsers.ts`를 열어 `adminFetch` 시그니처를 그대로 재사용(또는 export해서 import).

`admin-ui/src/api/activeTenant.ts`:

```typescript
// adminUsers.ts 의 adminFetch 패턴 재사용 (raw JSON, credentials include, XSRF 헤더).
// adminFetch 를 adminUsers.ts 에서 export 하거나, 동일 구현을 복사한다.
import { adminFetch } from './adminUsers';   // adminFetch 를 export 했다고 가정

export interface ActiveTenantView {
  activeTenantId: string | null;
  allowedTenantIds: string[];
}

export const activeTenantApi = {
  get: (): Promise<ActiveTenantView> =>
    adminFetch<ActiveTenantView>('GET', '/admin/api/active-tenant'),

  switch: (tenantId: string): Promise<ActiveTenantView> =>
    adminFetch<ActiveTenantView>('POST', '/admin/api/active-tenant', { tenantId }),
};
```

`adminUsers.ts`의 `adminUsersApi`에 매핑 추가/회수도 더한다:

```typescript
  addTenant: (id: string, tenantId: string): Promise<void> =>
    adminFetch<void>('POST', `/admin/api/admin-users/${id}/tenants`, { tenantId }),

  removeTenant: (id: string, tenantId: string): Promise<void> =>
    adminFetch<void>('DELETE',
      `/admin/api/admin-users/${id}/tenants/${encodeURIComponent(tenantId)}`),
```

> 구현자 주의: `adminFetch`가 현재 모듈 내부 함수(비-export)면 `export`로 바꾸고, `invite` body 타입의 `tenantId?: string`을 `tenantIds: string[]`로, `AdminUserView`의 `tenantId`를 `tenantIds: string[]`로 바꾼다.

- [ ] **Step 2: 초대 폼 — 단일 tenant 선택 → 다중 선택**

`AdminUsersTab.tsx`의 운영자 초대 폼에서 RP_ADMIN 선택 시 노출되는 tenant 선택을 단일 → 다중(체크박스 목록 또는 멀티셀렉트)으로 바꾸고, 제출 payload를 `{ email, role, tenantIds: string[] }`로 변경한다. PLATFORM_OPERATOR면 tenant 선택 숨김 + `tenantIds: []`. 운영자 목록 행에 "운영 RP 수"(tenantIds.length) 표시.

> 구현자 주의: 기존 폼이 `tenantId` 단일 상태였다면 `tenantIds: string[]` 상태로 바꾼다. tenant 목록은 기존 tenant 조회 API를 재사용.

- [ ] **Step 3: 헤더 RP 스위처**

`Header.tsx`에서 마운트 시 `getActiveTenant()` 호출. `allowedTenantIds.length >= 2`이면 드롭다운(현재 활성 RP 표시 + 목록)을 렌더, 선택 시 `switchActiveTenant(id)` 호출 후 화면 데이터 새로고침(라우터 reload 또는 전역 쿼리 무효화 — 기존 데이터 패칭 방식에 맞춤). `length < 2`이면 스위처 숨김. PLATFORM_OPERATOR(allowedTenantIds 빈 배열)도 숨김.

> 구현자 주의: tenant id만으로는 이름 표시가 안 되므로, 기존 tenant 목록 API로 id→이름 매핑을 만들어 드롭다운 라벨에 사용한다. RP_ADMIN이 tenant 목록 API를 호출할 권한이 있는지 확인하고, 없으면 active-tenant 응답을 `{id,name}` 쌍으로 확장하는 것을 Task 8 백엔드에 반영(이 경우 Task 8 ActiveTenantView에 name 포함하도록 보강).

- [ ] **Step 4: 빌드 확인**

Run: `cd admin-ui && npm run build` (또는 기존 빌드 스크립트)
Expected: 타입/빌드 PASS.

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/pages/settings/AdminUsersTab.tsx \
        admin-ui/src/shell/Header.tsx \
        admin-ui/src/api/activeTenant.ts
git commit -m "$(cat <<'EOF'
feat(admin-ui): 다중 RP 초대 + 헤더 RP 스위처

초대 폼 tenantIds 다중 선택, allowedTenantIds>=2 일 때 헤더 스위처 노출.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: IT cleanup SQL 일괄 전환 — `admin_user SET tenant_id=NULL` 제거

**Files (전수 — `admin_user.tenant_id`를 SQL로 직접 SET하는 23개 위치):**
- `admin-app/src/test/java/.../AdminFlowIT.java:175`, `tenant/WebauthnConfigSnapshotIT.java:145`, `tenant/TenantAdminControllerUpdateIT.java:149`, `credential/CredentialAdminControllerSecurityIT.java:162`, `auth/PlatformOperatorUnrestrictedIT.java:144`, `auth/RpAdminBoundaryIT.java:151`, `activity/ActivityControllerIT.java:140`, `audit/AuditChainBackfillIT.java:140`, `audit/SecurityIncidentIT.java:152`, `audit/AuditChainPerTenantIT.java:140`, `audit/AuditLogTenantScopingIT.java:158`, `operator/AdminUserInvitationFlowIT.java:164`, `policy/AaguidPolicyCeremonyIT.java:158`
- `core/src/test/java/.../tenant/TenantFilterAspectIT.java:129`, `tenant/AppLevelIsolationIT.java:209`, `tenant/TenantFilterBindingIT.java:106`, `db/TenantAllowedOriginAndroidCheckIT.java:149`
- `passkey-app/src/test/java/.../fido2/Fido2EndToEndIT.java:217,258`

**Interfaces:**
- Consumes: `admin_user_tenant` 테이블 (Task 1).
- Produces: 모든 IT cleanup이 컬럼 대신 조인 테이블을 비운다.

**배경:** 이 SQL들의 의도는 "tenant DELETE 전에 FK(`fk_admin_user_tenant`)를 끊는 것"이다. 컬럼·FK가 사라지면 `UPDATE ... SET tenant_id = NULL`은 `ORA-00904 invalid identifier`로 전부 실패한다. N:M에서는 FK가 `admin_user_tenant`로 옮겨갔으므로, "매핑 행을 먼저 비우면" tenant DELETE가 가능하다.

- [ ] **Step 1: 전수 grep으로 현재 위치 재확인**

Run: `grep -rn "admin_user SET tenant_id" admin-app/src/test core/src/test passkey-app/src/test --include="*.java"`
Expected: 위 19개 라인(파일 23참조)이 보인다. 이 목록이 교체 대상이다.

- [ ] **Step 2: 두 가지 패턴으로 일괄 치환**

각 위치는 두 형태 중 하나다. 치환 규칙:

(A) `UPDATE APP_OWNER.admin_user SET tenant_id = NULL, role = 'PLATFORM_OPERATOR' WHERE tenant_id IS NOT NULL` (단일 라인, 대부분)
→ `DELETE FROM APP_OWNER.admin_user_tenant`로 교체. role 강제 다운그레이드가 cleanup에 필요했던 테스트는 별도로 `UPDATE APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'`를 한 줄 더 둔다(컬럼 참조 없음 → 안전).

```java
        jdbc.update("DELETE FROM APP_OWNER.admin_user_tenant");
        jdbc.update("UPDATE APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'");
```

(B) `AdminUserInvitationFlowIT.java:164`처럼 `SET tenant_id = NULL, role = 'PLATFORM_OPERATOR', ...`로 다른 컬럼도 함께 SET하는 멀티라인:
→ `tenant_id = NULL,` 부분만 제거하고 나머지 SET절은 유지. 그 앞 줄에 `jdbc.update("DELETE FROM APP_OWNER.admin_user_tenant");` 추가.

> 구현자 주의: `passkey-app/.../Fido2EndToEndIT.java`는 같은 SQL이 2곳(217,258)에 있고 별도 JdbcTemplate(`adminJdbc`)을 쓴다 — 둘 다 교체. 일부 파일은 위에 `// V23 ... FK ...` 주석이 있는데, 주석도 `// admin_user_tenant 매핑을 먼저 비운 뒤 tenant DELETE` 식으로 갱신(선택).

- [ ] **Step 3: 교체 누락 검증**

Run: `grep -rn "admin_user SET tenant_id\|SET tenant_id = NULL" admin-app/src/test core/src/test passkey-app/src/test --include="*.java"`
Expected: 출력 없음(전부 제거됨).

- [ ] **Step 4: 영향 IT 일부 스모크 실행**

Run: `./gradlew :admin-app:test --tests '*RpAdminBoundaryIT' --tests '*PlatformOperatorUnrestrictedIT'`
Expected: PASS(또는 base worktree 대조 시 동일 상태). 컬럼 참조 오류(ORA-00904)가 사라졌는지 확인.

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/test core/src/test passkey-app/src/test
git commit -m "$(cat <<'EOF'
test: IT cleanup 의 admin_user.tenant_id SET 제거 — 매핑 테이블 DELETE 로 전환

컬럼/FK 제거로 ORA-00904 나던 23개 cleanup SQL 을 DELETE FROM admin_user_tenant
(+ role 다운그레이드)로 치환.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: 통합 테스트 — 멀티 RP 운영자 격리/전환

**Files:**
- Modify/Create: `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java` (멀티 RP 시나리오 추가) 또는 신규 `MultiTenantOperatorIT.java`

**Interfaces:**
- Consumes: 전체 스택(Task 1~11).
- Produces: bob을 2개 RP 운영자로 만들고 스위처 전환·격리·@Filter를 검증하는 IT.

- [ ] **Step 1: IT 시나리오 작성**

기존 `AdminFlowIT`의 셋업(Testcontainers, MockMvc/RestTemplate, bob 로그인 헬퍼)을 재사용한다. 시나리오:

1. bob(RP_ADMIN)을 demo-rp + 두 번째 RP(예: 새 tenant 생성 또는 기존 두 번째 시드 RP)에 매핑(`admin_user_tenant` 2행).
2. bob 로그인 → `GET /admin/api/active-tenant` → `allowedTenantIds`에 2개, `activeTenantId`는 둘 중 정렬 첫 번째.
3. `POST /admin/api/active-tenant {두 번째 RP}` → 200, activeTenantId가 두 번째로 바뀜.
4. 활성 RP가 두 번째일 때 tenant-scoped 목록 조회(예: credentials/RP 목록 엔드포인트)가 두 번째 RP 데이터만 반환(Hibernate @Filter 격리 확인).
5. `POST /admin/api/active-tenant {허용 안 된 임의 UUID}` → 4xx(ACCESS_DENIED).
6. `assertCanAccessTenant` 경유 엔드포인트로 허용 밖 RP 직접 접근 → 4xx.

```java
    @Test
    void multiTenantOperatorCanSwitchAndIsIsolated() {
        // 1. 매핑 2건 보장 (resetState + 두 번째 RP 매핑 INSERT)
        // 2. 로그인 후 GET active-tenant → allowedTenantIds.size()==2
        // 3. POST active-tenant(secondRp) → activeTenantId==secondRp
        // 4. scoped list 가 secondRp 데이터만 반환
        // 5. POST active-tenant(randomUuid) → 4xx
        // 실제 호출은 기존 IT 의 mockMvc/perform 패턴으로 작성
    }
```

> 구현자 주의: 두 번째 RP는 기존 시드에 있는 RP를 재사용하거나, 테스트 셋업에서 tenant + 매핑을 INSERT한다. scoped 목록 엔드포인트는 기존 IT가 이미 호출하는 것(RP_ADMIN이 접근 가능한 tenant-scoped 조회)을 사용.

- [ ] **Step 2: IT 실행**

Run: `./gradlew :admin-app:test --tests '*AdminFlowIT' --tests '*MultiTenantOperatorIT'`
Expected: PASS (Testcontainers Oracle 기동 필요).

- [ ] **Step 3: Commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/
git commit -m "$(cat <<'EOF'
test(admin): 멀티 RP 운영자 스위처·격리 IT

2개 RP 매핑 → 활성 RP 전환 → @Filter 격리 → 허용 밖 접근 차단 검증.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: 회귀 정리 — tenantId 잔여 참조 + 전체 빌드 대조

**Files:**
- 전 모듈에 걸쳐 `AdminUser.getTenantId()`/`AdminUser.setTenantId()`/`AdminUserDetails.getTenantId()` 잔여 참조 정리.

**Interfaces:**
- Produces: 컴파일/테스트 그린(또는 pre-existing만 남음).

- [ ] **Step 1: 잔여 참조 검색 (AdminUser/AdminUserDetails 한정)**

`Credential.getTenantId()`·`ApiKey.getTenantId()` 등 **다른 엔티티의 getTenantId는 정상**(제거 대상 아님). AdminUser/AdminUserDetails의 tenantId만 사라졌으므로, 컴파일러가 잡아주는 게 가장 확실하다. 우선 컴파일:

Run: `./gradlew :admin-app:compileJava :admin-app:compileTestJava :core:compileJava :core:compileTestJava :passkey-app:compileTestJava`
Expected: PASS. 만약 `cannot find symbol: method getTenantId()`가 `AdminUser`/`AdminUserDetails` 수신자에서 나면 그 호출부를 `getAllowedTenantIds()` 또는 `mappingRepo.findTenantIdsByAdminUserId(...)` 또는 `activeTenantResolver.resolve(...)`로 고친다(흐름에 맞게).

보조 grep(수동 확인용):
Run: `grep -rn "principal.getTenantId\|me.getTenantId\|\.getTenantId()" admin-app/src/main | grep -iv "credential\|apikey\|api_key"`
Expected: AdminUserDetails 수신자 호출이 0건(Task 4·6·5에서 모두 교체됨).

- [ ] **Step 2: admin-app 단위/슬라이스 테스트**

Run: `./gradlew :admin-app:test`
Expected: 새 테스트 GREEN. 기존 슬라이스 테스트가 RED면, 그것이 이번 변경 때문인지 base worktree 대조로 확인(메모리 `project_full_build_preexisting_traps`·`project_slice_test_mockbean_regression` 참고). 이번 변경이 원인이면 해당 슬라이스의 mock/생성자 시그니처(AdminUserTenantRepository, ActiveTenantResolver 추가)를 보강.

- [ ] **Step 3: core 테스트**

Run: `./gradlew :core:test`
Expected: GREEN(또는 pre-existing ORA-01031만).

- [ ] **Step 4: Commit (정리 변경이 있으면)**

```bash
git add -A
git commit -m "$(cat <<'EOF'
fix(admin): N:M 전환 잔여 참조 정리 + 슬라이스 빈 보강

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## 자체 검토 (Self-Review)

**스펙 커버리지:**
- 데이터 모델(§3) → Task 1(스키마+GRANT+뷰), 2(매핑 엔티티/리포), 3(AdminUser 컬럼 제거) ✅
- Tenant 바인딩/스위처(§4) → Task 4(allowedTenantIds + MeView), 5(ActiveTenantResolver), 6(TenantBoundary), 8(스위처 API) ✅
- 부여 API(§5) → Task 7(invite/add/remove + InvitationService), 8(엔드포인트) ✅
- UI(§6) → Task 10 ✅
- 에러 처리(§7) → Task 5(범위 밖 거부), 6(허용 밖 접근), 7(빈 tenantIds·마지막 회수·중복 멱등) ✅
- 시드(§8) → Task 9 ✅
- 테스트(§9) → Task 2·4·5·6·7 단위 + Task 11(IT cleanup SQL 전환) + Task 12 IT 시나리오 + Task 13 회귀 ✅
- 영향 범위(§10) → 모든 파일 태스크에 매핑됨. 검증으로 추가 발견한 지점(MeController/MeView, InvitationService, v_admin_user 뷰, admin_user_tenant GRANT, 23개 IT cleanup SQL)도 각 태스크에 흡수 ✅
- 감사 로그(§5 끝): addTenant/removeTenant `log.info` 기록(Task 7). 별도 audit_log 영속이 필요하면 후속 — 현 코드의 운영자 초대도 log 기반인지 기존 패턴 확인 후 동일하게.

**Placeholder 스캔:** 코드 스텝은 실제 코드 포함. Task 2(테스트 셋업 복사)·7 Step 2(서비스 생성자 mock)·10(adminFetch export)·11(IT 치환)·12(IT 시나리오)의 일부는 "기존 파일에서 복사/확인" 주석이 있으나, 복사 출처 파일과 검증 기준(grep/컴파일)이 명시되어 있어 실행 가능하다.

**타입 일관성:** `allowedTenantIds: Set<UUID>`(principal) ↔ `tenantIds: List<UUID>`(DTO/API/MeView) ↔ `findTenantIdsByAdminUserId: List<UUID>`(repo) 일관. `ACTIVE_TENANT_ATTR` 상수는 Task 5에서 정의·Task 6에서 resolve 경유로만 사용. 컨트롤러 경로(`/admin/api/active-tenant`, `/admin/api/admin-users/{id}/tenants`)는 Task 8·10·12에서 일치. `AdminUserService` 생성자는 명시적(Task 7에서 인자 직접 추가), `AdminUserDetailsService`는 `@RequiredArgsConstructor`(Task 4 필드만 추가) — 둘을 구분해 반영.

**검증으로 바로잡은 가정:**
- ✅ `new TenantBoundary(` 직접 생성처 0건 → Task 6 생성자 변경 안전.
- ✅ `/admin/api/**` → `.authenticated()` 포괄 → Task 8 매처 추가 불필요.
- ✅ core 리포 테스트는 `@DataJpaTest`가 아니라 `@SpringBootTest + @Testcontainers + bootstrap-schema.sql` → Task 2 반영.
- ✅ admin-ui는 raw-JSON 엔드포인트에 `adminFetch`(엔벨로프 X) 사용 → Task 10 반영.

**미해결(구현 중 결정):** Task 10에서 스위처 라벨에 RP 이름을 보이려면 tenant 목록 API 접근 권한이 필요하다. RP_ADMIN이 그 권한이 없으면 `ActiveTenantView`(컨트롤러)에 `{id,name}` 쌍을 포함하도록 Task 8 응답을 보강한다 — 구현자가 그 자리에서 권한을 확인해 결정.
