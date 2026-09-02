# 어드민 가입 요청·승인 흐름 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인 페이지에서 가입을 요청하고 PLATFORM_OPERATOR 가 승인하는 온보딩 흐름을 만들고, 동작하지 않는 초대 기능을 제거한다.

**Architecture:** 새 테이블 `admin_signup_request` 에 요청(이메일·bcrypt·사유)을 보관하고, 승인 시 `admin_user` 를 ACTIVE 로 생성하며 요청 행을 삭제한다. 공개 POST 하나만 permitAll 이고 나머지는 PLATFORM_OPERATOR 전용이다. 초대 서비스·컨트롤러·엔티티·테이블·UI 는 전부 제거한다.

**Tech Stack:** Spring Boot 3.5 / Spring Security 6.5 / Spring Data JPA (Oracle, Flyway) / React 18 + Vite + vitest / Testcontainers Oracle

**Spec:** `docs/superpowers/specs/2026-09-03-admin-signup-request-approval-design.md`

## Global Constraints

- 비밀번호 정책은 기존과 동일: 12자 이상 128자 이하 (`@Size(min = 12, max = 128)`)
- 요청 사유는 500자 이하, NULL 허용
- 대기 요청 상한 `MAX_PENDING = 100`
- 보존 속성 이름은 `passkey.retention.signup-request`, 기본 `P90D`
- 공개 POST 응답은 결과와 무관하게 항상 `202 {"accepted": true}`
- 404·409 는 `ResponseStatusException` 으로 던진다 (`IllegalStateException` 은 전역 핸들러가 500 으로 매핑하므로 사용 금지). 400 은 `IllegalArgumentException`.
- 감사 액션 이름: `ADMIN_SIGNUP_APPROVE`, `ADMIN_SIGNUP_REJECT`
- 시각은 `OffsetDateTime`, 생성은 `OffsetDateTime.now(clock)`
- 로그에 이메일은 반드시 `CryptoUtils.maskEmail()` 을 거친다
- 프론트에서 브라우저 `confirm()`/`alert()` 사용 금지 (앱 내 Dialog 사용)
- 모든 명령은 worktree 루트 `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-signup-request` 에서 실행한다. 커밋 전 `git branch --show-current` 가 `worktree-admin-signup-request` 인지 확인한다.
- 커밋 메시지 끝에 붙인다:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01G68EqS24TSNbxB5AAsKYtr
  ```

---

## File Structure

| 경로 | 책임 |
|---|---|
| `core/src/main/resources/db/migration/V5__admin_signup_request.sql` | 신규 테이블·GRANT, 초대 테이블·시퀀스 DROP |
| `core/src/main/java/com/crosscert/passkey/core/entity/AdminSignupRequest.java` | 요청 엔티티 (UUID PK, 독립 선언) |
| `core/src/main/java/com/crosscert/passkey/core/repository/AdminSignupRequestRepository.java` | 존재 확인, 정렬 목록, 건수 반환 삭제, 보존 정리 |
| `admin-app/.../operator/SignupRequestDto.java` | Create / View / Approve 레코드 |
| `admin-app/.../operator/SignupRequestService.java` | request / list / approve / reject |
| `admin-app/.../operator/SignupRequestController.java` | 공개 POST + 관리 GET/approve/reject |
| `admin-app/.../config/AdminSecurityConfig.java` | 공개 POST permitAll + CSRF 예외, 초대 경로 제거 |
| `admin-app/.../retention/RetentionPurgeService.java`, `RetentionPurgeJob.java` | 초대 정리 → 요청 정리 |
| `admin-ui/src/api/signupRequests.ts` | 공개 요청 API + 관리 API |
| `admin-ui/src/pages/SignupRequestPage.tsx` | 공개 가입 요청 페이지 |
| `admin-ui/src/pages/settings/AdminUsersTab.tsx` | 가입 요청 섹션·승인 다이얼로그·거절 확인, 초대 UI 제거 |
| `admin-ui/src/App.tsx`, `admin-ui/src/api/client.ts`, `admin-ui/src/api/adminUsers.ts`, `admin-ui/src/pages/LoginPage.tsx` | 공개 경로 등록, 401 예외, 초대 API 제거, 링크 |

---

### Task 1: 마이그레이션 V5 + 엔티티 + 리포지토리 (core)

**Files:**
- Create: `core/src/main/resources/db/migration/V5__admin_signup_request.sql`
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/AdminSignupRequest.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/AdminSignupRequestRepository.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/entity/AdminSignupRequestTest.java`

**Interfaces:**
- Produces: `AdminSignupRequest(String email, String bcryptHash, String reason, OffsetDateTime requestedAt)` 생성자, `getId()/getEmail()/getBcryptHash()/getReason()/getRequestedAt()`
- Produces: `AdminSignupRequestRepository` — `boolean existsByEmail(String)`, `List<AdminSignupRequest> findAllByOrderByRequestedAtAsc()`, `int deleteIfPresent(UUID id)`, `int deleteRequestedBefore(OffsetDateTime cutoff, int batchSize)`, 상속 `count()`, `findById(UUID)`, `save()`

- [ ] **Step 1: 엔티티 단위 테스트 작성**

```java
// core/src/test/java/com/crosscert/passkey/core/entity/AdminSignupRequestTest.java
package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSignupRequestTest {

    @Test
    void constructorKeepsAllFieldsAndIdIsAssignedByPersistence() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-03T10:00:00+09:00");
        AdminSignupRequest r = new AdminSignupRequest("new@x.com", "$2a$12$hash", "RP 담당자입니다", now);

        assertThat(r.getEmail()).isEqualTo("new@x.com");
        assertThat(r.getBcryptHash()).isEqualTo("$2a$12$hash");
        assertThat(r.getReason()).isEqualTo("RP 담당자입니다");
        assertThat(r.getRequestedAt()).isEqualTo(now);
        assertThat(r.getId()).as("id 는 persist 시 Hibernate 가 채운다").isNull();
    }

    @Test
    void reasonMayBeNull() {
        AdminSignupRequest r = new AdminSignupRequest("a@x.com", "h", null, OffsetDateTime.now());
        assertThat(r.getReason()).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :core:test --tests '*AdminSignupRequestTest*' -q`
Expected: 컴파일 실패 (`AdminSignupRequest` 없음)

- [ ] **Step 3: 마이그레이션 작성**

```sql
-- core/src/main/resources/db/migration/V5__admin_signup_request.sql
-- ============================================================
-- V5 — 어드민 가입 요청·승인 흐름 도입 + 초대 기능 제거.
--
-- 배경: 초대 메일이 가리키는 /accept-invite 프론트 페이지가 admin-ui src 폐기
-- (71ecb005) 때 사라진 뒤 복원되지 않아 초대 흐름이 실제로 동작하지 않았다.
-- 온보딩을 "로그인 페이지 가입 요청 → PLATFORM_OPERATOR 승인" 으로 바꾸고
-- 가입 경로를 하나로 단일화한다.
--
-- admin_signup_request: 승인 전 요청만 보관한다. 승인 시 admin_user 를 ACTIVE 로
-- 생성하고 이 행을 삭제하며, 거절도 삭제다(이력은 audit_log 의
-- ADMIN_SIGNUP_APPROVE / ADMIN_SIGNUP_REJECT 로 남는다). admin_user 에는 승인된
-- 계정만 존재하므로 role NOT NULL 제약과 로그인 조회가 미승인 행을 볼 일이 없다.
--
-- GRANT: PSK_APP_ADMIN 에 SELECT/INSERT/DELETE 만. UPDATE 경로는 없다.
-- PSK_APP_RUNTIME 에는 부여하지 않는다 — passkey-app 의 @EntityScan 은
-- core.entity 패키지를 통째로 스캔하므로 이 엔티티도 ddl-auto: validate 대상이
-- 된다. V4 의 security_incident 와 같은 이유로 SELECT 한 건은 부여한다.
--
-- admin_user_invitation 은 DROP 한다. 미수락 초대가 남아 있어도 어차피 수락할
-- 수 없었으므로 복구 대상이 아니다. admin_user.status CHECK 의 'PENDING' 값은
-- 더 이상 생성되지 않을 뿐이며 제약 자체는 건드리지 않는다.
-- ============================================================

CREATE TABLE admin_signup_request (
    id            RAW(16)                     DEFAULT SYS_GUID() NOT NULL,
    email         VARCHAR2(255)               NOT NULL,
    bcrypt_hash   VARCHAR2(72)                NOT NULL,
    reason        VARCHAR2(500),
    requested_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_admin_signup_request       PRIMARY KEY (id),
    CONSTRAINT uq_admin_signup_request_email UNIQUE (email)
);

GRANT SELECT ON admin_signup_request TO PSK_APP_ADMIN;
GRANT INSERT ON admin_signup_request TO PSK_APP_ADMIN;
GRANT DELETE ON admin_signup_request TO PSK_APP_ADMIN;
GRANT SELECT ON admin_signup_request TO PSK_APP_RUNTIME;

DROP TABLE admin_user_invitation;
DROP SEQUENCE admin_user_invitation_seq;
```

- [ ] **Step 4: 엔티티 작성**

```java
// core/src/main/java/com/crosscert/passkey/core/entity/AdminSignupRequest.java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 어드민 가입 요청. 승인 전 상태만 보관한다 — 승인되면 admin_user 로 옮겨지고
 * 이 행은 삭제된다(거절도 삭제). 그래서 갱신 경로가 없고 모든 컬럼이 updatable=false.
 *
 * <p>BaseEntity 를 상속하지 않는다 — 테이블에 created_at/updated_at 이 없고
 * requested_at 하나만 있다(갱신되지 않는 행에 updated_at 은 의미가 없다).
 */
@Entity
@Table(name = "ADMIN_SIGNUP_REQUEST")
public class AdminSignupRequest {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "EMAIL", length = 255, nullable = false, unique = true, updatable = false)
    private String email;

    @Column(name = "BCRYPT_HASH", length = 72, nullable = false, updatable = false)
    private String bcryptHash;

    @Column(name = "REASON", length = 500, updatable = false)
    private String reason;

    @Column(name = "REQUESTED_AT", nullable = false, updatable = false)
    private OffsetDateTime requestedAt;

    protected AdminSignupRequest() {}

    public AdminSignupRequest(String email, String bcryptHash, String reason, OffsetDateTime requestedAt) {
        this.email = email;
        this.bcryptHash = bcryptHash;
        this.reason = reason;
        this.requestedAt = requestedAt;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getBcryptHash() { return bcryptHash; }
    public String getReason() { return reason; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
}
```

- [ ] **Step 5: 리포지토리 작성**

```java
// core/src/main/java/com/crosscert/passkey/core/repository/AdminSignupRequestRepository.java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminSignupRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AdminSignupRequestRepository extends JpaRepository<AdminSignupRequest, UUID> {

    boolean existsByEmail(String email);

    List<AdminSignupRequest> findAllByOrderByRequestedAtAsc();

    /**
     * 건수 반환 삭제 — 승인/거절의 경합 판정용. 두 관리자가 같은 요청을 동시에
     * 처리하면 DB 가 직렬화해 한쪽만 1 을 받는다. 0 이면 이미 처리된 요청이므로
     * 호출측은 409 로 거부한다(계정 이중 생성 방지).
     *
     * <p>flushAutomatically/clearAutomatically: 호출측(SignupRequestService.approve)
     * 은 이 삭제를 admin_user INSERT 보다 **먼저** 실행한다. clearAutomatically 가
     * 영속성 컨텍스트를 비우므로, 삭제 뒤에 만든 엔티티는 영향받지 않지만 삭제
     * 전에 save() 만 하고 flush 되지 않은 엔티티는 유실된다 — 순서를 바꾸지 말 것.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminSignupRequest r where r.id = :id")
    int deleteIfPresent(@Param("id") UUID id);

    /**
     * 보존 정리: requested_at 이 cutoff 이전인 미처리 요청을 배치 삭제.
     * 방치된 요청이 대기 상한(100)을 영구히 점유하지 못하게 한다.
     * ROWNUM 캡 + 반복 호출 패턴은 다른 retention 쿼리와 동일.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM {h-schema}admin_signup_request WHERE id IN ("
         + "SELECT id FROM {h-schema}admin_signup_request WHERE requested_at < :cutoff "
         + "AND ROWNUM <= :batchSize)", nativeQuery = true)
    int deleteRequestedBefore(@Param("cutoff") OffsetDateTime cutoff, @Param("batchSize") int batchSize);
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :core:test --tests '*AdminSignupRequestTest*' -q`
Expected: PASS (2 tests)

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/resources/db/migration/V5__admin_signup_request.sql \
        core/src/main/java/com/crosscert/passkey/core/entity/AdminSignupRequest.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AdminSignupRequestRepository.java \
        core/src/test/java/com/crosscert/passkey/core/entity/AdminSignupRequestTest.java
git commit -m "feat(core): admin_signup_request 테이블·엔티티·리포지토리 (V5, 초대 테이블 DROP)"
```

---

### Task 2: SignupRequestService + DTO (TDD)

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/SignupRequestDto.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/SignupRequestService.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/operator/SignupRequestServiceTest.java`

**Interfaces:**
- Consumes: Task 1 의 엔티티·리포지토리, 기존 `AdminUserRepository.findByEmail`, `AdminUserTenantRepository.save`, `AdminUserTenant.of`, `AuditLogService.append`, `MailSender.send`, `BaseUrlValidation.assertNotLocalhostFallbackInProd`, `CryptoUtils.maskEmail`
- Produces:
  - `SignupRequestDto.Create(String email, String password, String reason)`
  - `SignupRequestDto.View(UUID id, String email, String reason, OffsetDateTime requestedAt)`
  - `SignupRequestDto.Approve(String role, List<UUID> tenantIds)`
  - `SignupRequestService.request(Create)` → void
  - `SignupRequestService.list()` → `List<View>`
  - `SignupRequestService.approve(UUID id, Approve, UUID actorId, String actorEmail)` → `AdminUserDto.View`
  - `SignupRequestService.reject(UUID id, UUID actorId, String actorEmail)` → void
  - 상수 `SignupRequestService.MAX_PENDING = 100`

- [ ] **Step 1: DTO 작성** (테스트가 참조하므로 먼저)

```java
// admin-app/src/main/java/com/crosscert/passkey/admin/operator/SignupRequestDto.java
package com.crosscert.passkey.admin.operator;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class SignupRequestDto {
    private SignupRequestDto() {}

    /** 공개 가입 요청 본문. 비밀번호 정책은 초대 수락 때와 같은 12~128자. */
    public record Create(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @Size(max = 500) String reason
    ) {}

    /** 관리자 목록 뷰. bcrypt 해시는 절대 노출하지 않는다. */
    public record View(UUID id, String email, String reason, OffsetDateTime requestedAt) {}

    /** 승인 본문. 역할·테넌트 규칙은 서비스에서 검증한다. */
    public record Approve(@NotBlank String role, List<UUID> tenantIds) {}
}
```

- [ ] **Step 2: 실패하는 서비스 테스트 작성**

```java
// admin-app/src/test/java/com/crosscert/passkey/admin/operator/SignupRequestServiceTest.java
package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.AdminSignupRequest;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminSignupRequestRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignupRequestServiceTest {

    private final AdminSignupRequestRepository requests = mock(AdminSignupRequestRepository.class);
    private final AdminUserRepository users = mock(AdminUserRepository.class);
    private final AdminUserTenantRepository mappings = mock(AdminUserTenantRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final MailSender mail = mock(MailSender.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final Environment env = mock(Environment.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-03T01:00:00Z"), KstTime.ZONE);

    private final SignupRequestService service = new SignupRequestService(
            requests, users, mappings, audit, mail, encoder, clock, env);

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String ACTOR = "alice@crosscert.com";

    private AdminSignupRequest pending(UUID id, String email) {
        AdminSignupRequest r = mock(AdminSignupRequest.class);
        when(r.getId()).thenReturn(id);
        when(r.getEmail()).thenReturn(email);
        when(r.getBcryptHash()).thenReturn("$2a$12$stored");
        when(r.getReason()).thenReturn("reason");
        when(r.getRequestedAt()).thenReturn(OffsetDateTime.now(clock));
        return r;
    }

    /** userRepo.save() 가 돌려줄 "저장된" 계정 — id 는 persist 시 채워지므로 mock. */
    private AdminUser savedUser(UUID id, String email, String role) {
        AdminUser u = mock(AdminUser.class);
        when(u.getId()).thenReturn(id);
        when(u.getEmail()).thenReturn(email);
        when(u.getRole()).thenReturn(role);
        when(u.getStatus()).thenReturn("ACTIVE");
        when(u.isMfaEnabled()).thenReturn(false);
        return u;
    }

    // ── request ────────────────────────────────────────────────────────────

    @Test
    void request_newEmail_savesHashedRequest() {
        when(users.findByEmail("new@x.com")).thenReturn(Optional.empty());
        when(requests.existsByEmail("new@x.com")).thenReturn(false);
        when(requests.count()).thenReturn(0L);
        when(encoder.encode("password-12chars")).thenReturn("$2a$12$hashed");

        service.request(new SignupRequestDto.Create("New@X.com ", "password-12chars", "RP 담당"));

        ArgumentCaptor<AdminSignupRequest> cap = ArgumentCaptor.forClass(AdminSignupRequest.class);
        verify(requests).save(cap.capture());
        assertThat(cap.getValue().getEmail()).as("소문자·trim 정규화").isEqualTo("new@x.com");
        assertThat(cap.getValue().getBcryptHash()).isEqualTo("$2a$12$hashed");
        assertThat(cap.getValue().getReason()).isEqualTo("RP 담당");
        assertThat(cap.getValue().getRequestedAt()).isEqualTo(OffsetDateTime.now(clock));
    }

    @Test
    void request_emailAlreadyAnAdminUser_silentlySkips() {
        when(users.findByEmail("alice@crosscert.com")).thenReturn(Optional.of(AdminUser.create()));

        assertThatCode(() -> service.request(
                new SignupRequestDto.Create("alice@crosscert.com", "password-12chars", null)))
                .doesNotThrowAnyException();

        verify(requests, never()).save(any());
    }

    @Test
    void request_emailAlreadyPending_silentlySkips() {
        when(users.findByEmail("dup@x.com")).thenReturn(Optional.empty());
        when(requests.existsByEmail("dup@x.com")).thenReturn(true);

        service.request(new SignupRequestDto.Create("dup@x.com", "password-12chars", null));

        verify(requests, never()).save(any());
    }

    @Test
    void request_pendingCapReached_silentlySkips() {
        when(users.findByEmail("cap@x.com")).thenReturn(Optional.empty());
        when(requests.existsByEmail("cap@x.com")).thenReturn(false);
        when(requests.count()).thenReturn((long) SignupRequestService.MAX_PENDING);

        service.request(new SignupRequestDto.Create("cap@x.com", "password-12chars", null));

        verify(requests, never()).save(any());
        verify(encoder, never()).encode(anyString());
    }

    @Test
    void request_uniqueViolationOnConcurrentInsert_isSwallowed() {
        when(users.findByEmail("race@x.com")).thenReturn(Optional.empty());
        when(requests.existsByEmail("race@x.com")).thenReturn(false);
        when(requests.count()).thenReturn(0L);
        when(encoder.encode(anyString())).thenReturn("h");
        when(requests.save(any())).thenThrow(new DataIntegrityViolationException("uq_admin_signup_request_email"));

        assertThatCode(() -> service.request(
                new SignupRequestDto.Create("race@x.com", "password-12chars", null)))
                .doesNotThrowAnyException();
    }

    // ── approve ────────────────────────────────────────────────────────────

    @Test
    void approve_rpAdminWithTenant_createsActiveUserMappingAuditAndMail() {
        UUID reqId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID newUserId = UUID.randomUUID();
        when(requests.findById(reqId)).thenReturn(Optional.of(pending(reqId, "new@x.com")));
        when(users.findByEmail("new@x.com")).thenReturn(Optional.empty());
        when(requests.deleteIfPresent(reqId)).thenReturn(1);
        when(users.save(any())).thenReturn(savedUser(newUserId, "new@x.com", "RP_ADMIN"));

        AdminUserDto.View view = service.approve(reqId,
                new SignupRequestDto.Approve("RP_ADMIN", List.of(tenantId, tenantId)), ACTOR_ID, ACTOR);

        ArgumentCaptor<AdminUser> userCap = ArgumentCaptor.forClass(AdminUser.class);
        verify(users).save(userCap.capture());
        AdminUser created = userCap.getValue();
        assertThat(created.getEmail()).isEqualTo("new@x.com");
        assertThat(created.getBcryptHash()).as("요청의 해시를 그대로 복사").isEqualTo("$2a$12$stored");
        assertThat(created.getRole()).isEqualTo("RP_ADMIN");
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getCreatedBy()).isEqualTo(ACTOR);

        ArgumentCaptor<AdminUserTenant> mapCap = ArgumentCaptor.forClass(AdminUserTenant.class);
        verify(mappings).save(mapCap.capture());   // 중복 tenantId 는 1건으로 접힘
        assertThat(mapCap.getValue().getAdminUserId()).isEqualTo(newUserId);
        assertThat(mapCap.getValue().getTenantId()).isEqualTo(tenantId);

        ArgumentCaptor<AuditAppendRequest> auditCap = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCap.capture());
        assertThat(auditCap.getValue().action()).isEqualTo("ADMIN_SIGNUP_APPROVE");
        assertThat(auditCap.getValue().targetType()).isEqualTo("ADMIN_USER");
        assertThat(auditCap.getValue().targetId()).isEqualTo(newUserId.toString());
        assertThat(auditCap.getValue().payload()).containsEntry("role", "RP_ADMIN").containsEntry("tenantCount", 1);

        verify(mail).send(eq("new@x.com"), anyString(), anyString());
        assertThat(view.tenantIds()).containsExactly(tenantId);
    }

    @Test
    void approve_rpAdminWithoutTenant_rejected400() {
        UUID reqId = UUID.randomUUID();
        assertThatThrownBy(() -> service.approve(reqId,
                new SignupRequestDto.Approve("RP_ADMIN", List.of()), ACTOR_ID, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RP_ADMIN requires at least one tenant");
        verify(requests, never()).deleteIfPresent(any());
    }

    @Test
    void approve_platformOperatorWithTenant_rejected400() {
        UUID reqId = UUID.randomUUID();
        assertThatThrownBy(() -> service.approve(reqId,
                new SignupRequestDto.Approve("PLATFORM_OPERATOR", List.of(UUID.randomUUID())), ACTOR_ID, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLATFORM_OPERATOR must not have tenant");
    }

    @Test
    void approve_unknownRole_rejected400() {
        assertThatThrownBy(() -> service.approve(UUID.randomUUID(),
                new SignupRequestDto.Approve("ROOT", null), ACTOR_ID, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid role");
    }

    @Test
    void approve_missingRequest_404() {
        UUID reqId = UUID.randomUUID();
        when(requests.findById(reqId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(reqId,
                new SignupRequestDto.Approve("PLATFORM_OPERATOR", List.of()), ACTOR_ID, ACTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void approve_emailNowExistsInAdminUser_409() {
        UUID reqId = UUID.randomUUID();
        when(requests.findById(reqId)).thenReturn(Optional.of(pending(reqId, "dup@x.com")));
        when(users.findByEmail("dup@x.com")).thenReturn(Optional.of(AdminUser.create()));

        assertThatThrownBy(() -> service.approve(reqId,
                new SignupRequestDto.Approve("PLATFORM_OPERATOR", List.of()), ACTOR_ID, ACTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(users, never()).save(any());
    }

    @Test
    void approve_alreadyHandledByConcurrentAdmin_409_andNoUserCreated() {
        UUID reqId = UUID.randomUUID();
        when(requests.findById(reqId)).thenReturn(Optional.of(pending(reqId, "race@x.com")));
        when(users.findByEmail("race@x.com")).thenReturn(Optional.empty());
        when(requests.deleteIfPresent(reqId)).thenReturn(0);

        assertThatThrownBy(() -> service.approve(reqId,
                new SignupRequestDto.Approve("PLATFORM_OPERATOR", List.of()), ACTOR_ID, ACTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(users, never()).save(any());
        verify(audit, never()).append(any());
    }

    @Test
    void approve_mailFailure_doesNotFailApproval() {
        UUID reqId = UUID.randomUUID();
        when(requests.findById(reqId)).thenReturn(Optional.of(pending(reqId, "m@x.com")));
        when(users.findByEmail("m@x.com")).thenReturn(Optional.empty());
        when(requests.deleteIfPresent(reqId)).thenReturn(1);
        when(users.save(any())).thenReturn(savedUser(UUID.randomUUID(), "m@x.com", "PLATFORM_OPERATOR"));
        doThrow(new RuntimeException("smtp down")).when(mail).send(anyString(), anyString(), anyString());

        assertThatCode(() -> service.approve(reqId,
                new SignupRequestDto.Approve("PLATFORM_OPERATOR", List.of()), ACTOR_ID, ACTOR))
                .doesNotThrowAnyException();
        verify(audit).append(any());
    }

    // ── reject ─────────────────────────────────────────────────────────────

    @Test
    void reject_deletesAuditsAndMails() {
        UUID reqId = UUID.randomUUID();
        when(requests.findById(reqId)).thenReturn(Optional.of(pending(reqId, "no@x.com")));
        when(requests.deleteIfPresent(reqId)).thenReturn(1);

        service.reject(reqId, ACTOR_ID, ACTOR);

        ArgumentCaptor<AuditAppendRequest> cap = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("ADMIN_SIGNUP_REJECT");
        assertThat(cap.getValue().targetType()).isEqualTo("ADMIN_SIGNUP_REQUEST");
        assertThat(cap.getValue().targetId()).isEqualTo(reqId.toString());
        verify(mail).send(eq("no@x.com"), anyString(), anyString());
    }

    @Test
    void reject_alreadyHandled_409() {
        UUID reqId = UUID.randomUUID();
        when(requests.findById(reqId)).thenReturn(Optional.of(pending(reqId, "no@x.com")));
        when(requests.deleteIfPresent(reqId)).thenReturn(0);

        assertThatThrownBy(() -> service.reject(reqId, ACTOR_ID, ACTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(audit, never()).append(any());
    }

    @Test
    void reject_missingRequest_404() {
        UUID reqId = UUID.randomUUID();
        when(requests.findById(reqId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reject(reqId, ACTOR_ID, ACTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── list ───────────────────────────────────────────────────────────────

    @Test
    void list_mapsToViewWithoutHash() {
        UUID id = UUID.randomUUID();
        when(requests.findAllByOrderByRequestedAtAsc()).thenReturn(List.of(pending(id, "l@x.com")));

        List<SignupRequestDto.View> views = service.list();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).id()).isEqualTo(id);
        assertThat(views.get(0).email()).isEqualTo("l@x.com");
        assertThat(views.get(0).reason()).isEqualTo("reason");
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :admin-app:test --tests '*SignupRequestServiceTest*' -q`
Expected: 컴파일 실패 (`SignupRequestService` 없음)

- [ ] **Step 4: 서비스 구현**

```java
// admin-app/src/main/java/com/crosscert/passkey/admin/operator/SignupRequestService.java
package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.entity.AdminSignupRequest;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminSignupRequestRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import com.crosscert.passkey.core.util.CryptoUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 어드민 가입 요청·승인 흐름.
 *
 * <p>request 는 공개 엔드포인트에서 호출된다. 계정 열거를 막기 위해 이메일 존재
 * 여부·대기 상한 도달 여부와 무관하게 예외 없이 반환하고, 호출측은 항상 같은
 * 응답을 준다. 저장하지 않는 경우는 마스킹 이메일로 WARN 만 남긴다.
 *
 * <p>approve/reject 는 PLATFORM_OPERATOR 전용. 요청 행 삭제를 건수 반환 쿼리로
 * 먼저 실행해 두 관리자의 동시 처리를 DB 에서 직렬화한다(0건이면 409).
 */
@Slf4j
@Service
public class SignupRequestService {

    /** 승인 대기 상한. 도달하면 새 요청을 조용히 받지 않는다(폭주 방지). */
    static final int MAX_PENDING = 100;

    private final AdminSignupRequestRepository requests;
    private final AdminUserRepository users;
    private final AdminUserTenantRepository mappings;
    private final AuditLogService audit;
    private final MailSender mail;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final Environment env;

    /** 승인 메일의 로그인 URL 앞부분. PasswordResetService 와 같은 속성을 읽는다. */
    @Value("${admin.invite.base-url:http://localhost:5173}")
    private String baseUrl;

    public SignupRequestService(AdminSignupRequestRepository requests,
                                AdminUserRepository users,
                                AdminUserTenantRepository mappings,
                                AuditLogService audit,
                                MailSender mail,
                                PasswordEncoder encoder,
                                Clock clock,
                                Environment env) {
        this.requests = requests;
        this.users = users;
        this.mappings = mappings;
        this.audit = audit;
        this.mail = mail;
        this.encoder = encoder;
        this.clock = clock;
        this.env = env;
    }

    /** G11 fail-fast — prod 에서 localhost 기본값으로 뜨면 승인 메일 링크가 깨진다. */
    @PostConstruct
    void validateBaseUrlForProd() {
        BaseUrlValidation.assertNotLocalhostFallbackInProd(env, baseUrl, "admin.invite.base-url");
    }

    /**
     * 의도적으로 @Transactional 을 붙이지 않는다 — save() 의 UNIQUE 위반(동시 요청)을
     * 여기서 잡아 삼키려면, 예외가 트랜잭션을 rollback-only 로 만들지 않아야 한다.
     * 각 리포지토리 호출은 자체 트랜잭션으로 돈다.
     */
    public void request(SignupRequestDto.Create req) {
        String email = normalize(req.email());
        String masked = CryptoUtils.maskEmail(email);

        if (users.findByEmail(email).isPresent()) {
            log.warn("signup request skipped: email={} reason=already-admin", masked);
            return;
        }
        if (requests.existsByEmail(email)) {
            log.warn("signup request skipped: email={} reason=already-pending", masked);
            return;
        }
        if (requests.count() >= MAX_PENDING) {
            log.warn("signup request skipped: email={} reason=pending-cap max={}", masked, MAX_PENDING);
            return;
        }

        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        var row = new AdminSignupRequest(email, encoder.encode(req.password()), reason, OffsetDateTime.now(clock));
        try {
            requests.save(row);
        } catch (DataIntegrityViolationException e) {
            // 같은 이메일의 동시 요청 — 먼저 들어간 쪽이 남는다. 응답은 동일.
            log.warn("signup request skipped: email={} reason=concurrent-duplicate", masked);
            return;
        }
        log.info("signup request accepted: email={}", masked);
    }

    @Transactional(readOnly = true)
    public List<SignupRequestDto.View> list() {
        return requests.findAllByOrderByRequestedAtAsc().stream()
                .map(r -> new SignupRequestDto.View(r.getId(), r.getEmail(), r.getReason(), r.getRequestedAt()))
                .toList();
    }

    @Transactional
    public AdminUserDto.View approve(UUID requestId, SignupRequestDto.Approve body,
                                     UUID actorId, String actorEmail) {
        // 1. 역할 규칙 — 초대 흐름의 검증을 그대로 옮김.
        if (!"PLATFORM_OPERATOR".equals(body.role()) && !"RP_ADMIN".equals(body.role())) {
            throw new IllegalArgumentException("Invalid role: " + body.role());
        }
        List<UUID> tenantIds = body.tenantIds() == null ? List.of() : body.tenantIds();
        if ("RP_ADMIN".equals(body.role()) && tenantIds.isEmpty()) {
            throw new IllegalArgumentException("RP_ADMIN requires at least one tenant");
        }
        if ("PLATFORM_OPERATOR".equals(body.role()) && !tenantIds.isEmpty()) {
            throw new IllegalArgumentException("PLATFORM_OPERATOR must not have tenant");
        }

        // 2. 요청 조회
        AdminSignupRequest req = requests.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "signup request not found"));
        String email = req.getEmail();
        String bcryptHash = req.getBcryptHash();

        // 3. 그 사이 같은 이메일 계정이 생겼으면 409
        if (users.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }

        // 4. 경합 판정 — 반드시 admin_user INSERT 보다 먼저. deleteIfPresent 는
        //    clearAutomatically 라 영속성 컨텍스트를 비운다; 뒤에 만드는 엔티티는
        //    무관하지만 앞에 save() 만 해둔 엔티티는 유실된다.
        if (requests.deleteIfPresent(requestId) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "signup request already handled");
        }

        // 5. 계정 생성 — 승인 즉시 로그인 가능(ACTIVE + enabled). 해시는 요청의 것을 복사.
        AdminUser user = AdminUser.create();
        user.setEmail(email);
        user.setBcryptHash(bcryptHash);
        user.setRole(body.role());
        user.setStatus("ACTIVE");
        user.setEnabled(true);
        user.setCreatedBy(actorEmail);
        AdminUser saved = users.save(user);

        List<UUID> distinctTenants = List.copyOf(new LinkedHashSet<>(tenantIds));
        for (UUID tid : distinctTenants) {
            mappings.save(AdminUserTenant.of(saved.getId(), tid, actorEmail));
        }

        // 6. 감사
        audit.append(new AuditAppendRequest(actorId, actorEmail, "ADMIN_SIGNUP_APPROVE",
                "ADMIN_USER", saved.getId().toString(), null,
                Map.of("role", body.role(), "tenantCount", distinctTenants.size())));
        log.info("signup request approved: email={} role={} tenantCount={} by={}",
                CryptoUtils.maskEmail(email), body.role(), distinctTenants.size(), CryptoUtils.maskEmail(actorEmail));

        // 7. 결과 메일 — 실패해도 승인은 성공
        String loginUrl = baseUrl + "/admin";
        sendQuietly(email, "관리자 계정 승인 — Passkey2",
                String.format("가입 요청이 승인되었습니다.<br>로그인: <a href=\"%s\">%s</a>", loginUrl, loginUrl));

        return new AdminUserDto.View(
                saved.getId(), saved.getEmail(), saved.getRole(),
                saved.getStatus() != null ? saved.getStatus() : "ACTIVE",
                distinctTenants,
                saved.getCreatedAt(), saved.getLastLoginAt(),
                saved.getSuspendedAt(), saved.getCreatedBy(), saved.isMfaEnabled());
    }

    @Transactional
    public void reject(UUID requestId, UUID actorId, String actorEmail) {
        AdminSignupRequest req = requests.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "signup request not found"));
        String email = req.getEmail();

        if (requests.deleteIfPresent(requestId) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "signup request already handled");
        }

        audit.append(new AuditAppendRequest(actorId, actorEmail, "ADMIN_SIGNUP_REJECT",
                "ADMIN_SIGNUP_REQUEST", requestId.toString(), null, Map.of()));
        log.info("signup request rejected: email={} by={}",
                CryptoUtils.maskEmail(email), CryptoUtils.maskEmail(actorEmail));

        sendQuietly(email, "관리자 계정 요청 거절 — Passkey2",
                "가입 요청이 거절되었습니다. 문의는 플랫폼 운영자에게 하세요.");
    }

    private void sendQuietly(String to, String subject, String body) {
        try {
            mail.send(to, subject, body);
        } catch (Exception e) {
            log.warn("signup result mail failed: email={} cause={}", CryptoUtils.maskEmail(to), e.toString());
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests '*SignupRequestServiceTest*' -q`
Expected: PASS (17 tests)

- [ ] **Step 6: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/operator/SignupRequestDto.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/operator/SignupRequestService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/operator/SignupRequestServiceTest.java
git commit -m "feat(admin): SignupRequestService — 요청·목록·승인·거절"
```

---

### Task 3: 컨트롤러 + 보안 설정 + 슬라이스 보안 테스트

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/SignupRequestController.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java:93-132`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/operator/SignupRequestControllerSecurityTest.java`

**Interfaces:**
- Consumes: Task 2 의 `SignupRequestService`, `SignupRequestDto`
- Produces: HTTP `POST /admin/api/signup-requests` (202, permitAll, CSRF 제외), `GET /admin/api/signup-requests`, `POST /admin/api/signup-requests/{id}/approve`, `POST /admin/api/signup-requests/{id}/reject` (204)

- [ ] **Step 1: 슬라이스 보안 테스트 작성**

MockBean 목록은 `MeControllerSecurityTest` 와 같되 `AdminUserInvitationRepository` 줄은 넣지 않는다(Task 4 에서 제거됨). 이 테스트를 Task 4 전에 실행하면 `AdminSecurityConfig` 가 초대 리포지토리를 요구하지 않으므로 그대로 통과한다.

```java
// admin-app/src/test/java/com/crosscert/passkey/admin/operator/SignupRequestControllerSecurityTest.java
package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.config.AdminSecurityConfig;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = SignupRequestController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
    }
)
@Import({
    AdminSecurityConfig.class,
    SignupRequestControllerSecurityTest.JpaStubs.class
})
class SignupRequestControllerSecurityTest {

    @TestConfiguration
    static class JpaStubs {
        @Bean
        EntityManagerFactory entityManagerFactory() {
            Metamodel metamodel = mock(Metamodel.class);
            when(metamodel.getEntities()).thenReturn(Set.of());
            when(metamodel.getManagedTypes()).thenReturn(Set.of());
            when(metamodel.getEmbeddables()).thenReturn(Set.of());
            EntityManagerFactory emf = mock(EntityManagerFactory.class);
            when(emf.getMetamodel()).thenReturn(metamodel);
            return emf;
        }
    }

    @Autowired MockMvc mvc;
    @MockBean SignupRequestService service;
    @MockBean AdminUserRepository users;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    @MockBean com.crosscert.passkey.core.repository.CeremonyEventRepository ceremonyEventRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyScopeRepository apiKeyScopeRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository credentialRepository;
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.SchedulerLeaseRepository schedulerLeaseRepository;
    @MockBean com.crosscert.passkey.core.repository.ActivityRepository activityRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository adminPasswordResetTokenRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository adminUserRecoveryCodeRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository tenantAaguidPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.SecurityPolicyRepository securityPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository tenantWebauthnSnapshotRepository;
    @MockBean com.crosscert.passkey.admin.policy.SecurityPolicyService securityPolicyService;
    @MockBean com.crosscert.passkey.admin.auth.TenantBoundary tenantBoundary;

    private static Authentication as(String role) {
        AdminUserDetails principal = new AdminUserDetails(
                UUID.randomUUID(), "who@example.com", "x",
                role, null, true, null, java.time.Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(principal, "x", principal.getAuthorities());
    }

    private static final String VALID_BODY =
            "{\"email\":\"new@example.com\",\"password\":\"password-12chars\",\"reason\":\"RP 담당\"}";

    // ── 공개 POST ──────────────────────────────────────────────────────────

    @Test
    void publicRequest_anonymousWithoutCsrf_isAccepted202() throws Exception {
        mvc.perform(post("/admin/api/signup-requests")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.accepted").value(true));
        verify(service).request(any());
    }

    @Test
    void publicRequest_shortPassword_is400_andServiceNotCalled() throws Exception {
        mvc.perform(post("/admin/api/signup-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new@example.com\",\"password\":\"short\"}"))
            .andExpect(status().isBadRequest());
        verify(service, never()).request(any());
    }

    @Test
    void publicRequest_invalidEmail_is400() throws Exception {
        mvc.perform(post("/admin/api/signup-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"password\":\"password-12chars\"}"))
            .andExpect(status().isBadRequest());
    }

    // ── 관리 GET ───────────────────────────────────────────────────────────

    @Test
    void list_anonymous_is401() throws Exception {
        mvc.perform(get("/admin/api/signup-requests")).andExpect(status().isUnauthorized());
    }

    @Test
    void list_rpAdmin_is403() throws Exception {
        mvc.perform(get("/admin/api/signup-requests").with(authentication(as("RP_ADMIN"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void list_platformOperator_is200() throws Exception {
        when(service.list()).thenReturn(List.of());
        mvc.perform(get("/admin/api/signup-requests").with(authentication(as("PLATFORM_OPERATOR"))))
            .andExpect(status().isOk());
    }

    // ── approve / reject ───────────────────────────────────────────────────

    @Test
    void approve_anonymous_is401() throws Exception {
        mvc.perform(post("/admin/api/signup-requests/" + UUID.randomUUID() + "/approve")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"PLATFORM_OPERATOR\",\"tenantIds\":[]}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void approve_rpAdmin_is403() throws Exception {
        mvc.perform(post("/admin/api/signup-requests/" + UUID.randomUUID() + "/approve")
                .with(authentication(as("RP_ADMIN"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"PLATFORM_OPERATOR\",\"tenantIds\":[]}"))
            .andExpect(status().isForbidden());
        verify(service, never()).approve(any(), any(), any(), any());
    }

    @Test
    void approve_platformOperator_delegates() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.approve(any(), any(), any(), any())).thenReturn(new AdminUserDto.View(
                UUID.randomUUID(), "new@example.com", "PLATFORM_OPERATOR", "ACTIVE",
                List.of(), null, null, null, "who@example.com", false));
        mvc.perform(post("/admin/api/signup-requests/" + id + "/approve")
                .with(authentication(as("PLATFORM_OPERATOR"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"PLATFORM_OPERATOR\",\"tenantIds\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("new@example.com"));
    }

    @Test
    void reject_platformOperator_is204() throws Exception {
        mvc.perform(post("/admin/api/signup-requests/" + UUID.randomUUID() + "/reject")
                .with(authentication(as("PLATFORM_OPERATOR"))).with(csrf()))
            .andExpect(status().isNoContent());
        verify(service).reject(any(), any(), any());
    }

    @Test
    void reject_rpAdmin_is403() throws Exception {
        mvc.perform(post("/admin/api/signup-requests/" + UUID.randomUUID() + "/reject")
                .with(authentication(as("RP_ADMIN"))).with(csrf()))
            .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :admin-app:test --tests '*SignupRequestControllerSecurityTest*' -q`
Expected: 컴파일 실패 (`SignupRequestController` 없음)

- [ ] **Step 3: 컨트롤러 작성**

```java
// admin-app/src/main/java/com/crosscert/passkey/admin/operator/SignupRequestController.java
package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 가입 요청·승인 엔드포인트.
 *
 * <p>{@code POST /admin/api/signup-requests} 만 공개(permitAll, CSRF 제외)이고
 * 항상 202 를 돌려준다 — 계정 열거 방지. 나머지는 PLATFORM_OPERATOR 전용이라
 * 메서드 단위 @PreAuthorize 를 쓴다(클래스 단위면 공개 POST 까지 막힌다).
 * 응답은 AdminUserController 처럼 raw JSON(envelope 없음).
 */
@RestController
@RequestMapping("/admin/api/signup-requests")
public class SignupRequestController {

    private final SignupRequestService service;

    public SignupRequestController(SignupRequestService service) {
        this.service = service;
    }

    private static UUID actorId(Authentication auth) {
        return ((AdminUserDetails) auth.getPrincipal()).getId();
    }

    @PostMapping
    public ResponseEntity<Map<String, Boolean>> request(@Valid @RequestBody SignupRequestDto.Create body) {
        service.request(body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("accepted", true));
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public List<SignupRequestDto.View> list() {
        return service.list();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public AdminUserDto.View approve(@PathVariable UUID id,
                                     @Valid @RequestBody SignupRequestDto.Approve body,
                                     Authentication auth) {
        return service.approve(id, body, actorId(auth), auth.getName());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public ResponseEntity<Void> reject(@PathVariable UUID id, Authentication auth) {
        service.reject(id, actorId(auth), auth.getName());
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: 보안 설정 수정**

`AdminSecurityConfig.java` 의 authorize 블록에서 초대 두 줄(108~110행)을 아래로 교체한다:

```java
                // 가입 요청(POST)만 미인증 허용 — 요청자는 아직 계정이 없다.
                // GET 목록과 /{id}/approve·reject 는 아래 /admin/api/** authenticated 에
                // 걸리고, 컨트롤러 @PreAuthorize 가 PLATFORM_OPERATOR 를 강제한다.
                .requestMatchers(HttpMethod.POST, "/admin/api/signup-requests").permitAll()
```

CSRF 블록(129~132행)을 아래로 교체한다:

```java
                // 가입 요청 POST 와 비밀번호 재설정은 세션·XSRF-TOKEN 쿠키가 없는
                // 미인증 컨텍스트의 1회성 호출이라 CSRF 를 면제한다. 가입 요청은
                // 정확히 POST 한 건만 — 하위 경로(approve/reject)는 보호 유지.
                .ignoringRequestMatchers(
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/admin/api/signup-requests"),
                        PathPatternRequestMatcher.withDefaults().matcher("/admin/api/password-reset/**")));
```

import 추가:

```java
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.PathPatternRequestMatcher;
```

또한 공개 SPA 경로 목록(100~102행)의 문자열 배열에 `"/admin/signup"` 을 추가한다(`"/admin/reset-password"` 뒤).

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests '*SignupRequestControllerSecurityTest*' --tests '*MeControllerSecurityTest*' -q`
Expected: PASS (11 + 5). MeControllerSecurityTest 는 보안 설정 변경이 다른 슬라이스를 깨지 않았음을 확인하는 대조군.

- [ ] **Step 6: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/operator/SignupRequestController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/operator/SignupRequestControllerSecurityTest.java
git commit -m "feat(admin): 가입 요청 엔드포인트 + 공개 POST permitAll/CSRF 면제"
```

---

### Task 4: 보존 정리 슬롯 교체 (초대 → 가입 요청)

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/retention/RetentionPurgeService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/retention/RetentionPurgeJob.java`
- Modify: `admin-app/src/main/resources/application.yml:83`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/retention/RetentionPurgeServiceTest.java`, `RetentionPurgeJobTest.java`

**Interfaces:**
- Consumes: Task 1 의 `AdminSignupRequestRepository.deleteRequestedBefore`
- Produces: `RetentionPurgeService.purgeSignupRequests(OffsetDateTime cutoff)` → int, 잡 payload 키 `signupRequestsPurged`, 속성 `passkey.retention.signup-request`

- [ ] **Step 1: 테스트 수정 (실패하게)**

`RetentionPurgeServiceTest.java`:
- import `AdminUserInvitationRepository` → `AdminSignupRequestRepository`
- `@Mock AdminUserInvitationRepository invitations;` → `@Mock AdminSignupRequestRepository signupRequests;`
- 생성자 첫 인자 `invitations` → `signupRequests`
- `purgeInvitations_delegates_cutoff_and_returns_count` 를 아래로 교체:

```java
    @Test
    void purgeSignupRequests_delegates_cutoff_and_returns_count() {
        OffsetDateTime cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        when(signupRequests.deleteRequestedBefore(eq(cutoff), anyInt())).thenReturn(3);
        assertThat(service.purgeSignupRequests(cutoff)).isEqualTo(3);
    }
```

- `purge_loops_until_batch_not_full_and_sums_totals` 안의 `invitations.deleteConsumedOrExpiredBefore` → `signupRequests.deleteRequestedBefore`, `service.purgeInvitations` → `service.purgeSignupRequests`

`RetentionPurgeJobTest.java`: `purgeInvitations` 5곳 → `purgeSignupRequests`, `"invitationsPurged"` 3곳 → `"signupRequestsPurged"`.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :admin-app:test --tests '*RetentionPurge*' -q`
Expected: 컴파일 실패

- [ ] **Step 3: 서비스·잡 수정**

`RetentionPurgeService.java`:
- import 교체: `AdminUserInvitationRepository` → `AdminSignupRequestRepository`
- 필드 `private final AdminUserInvitationRepository invitations;` → `private final AdminSignupRequestRepository signupRequests;`
- `purgeInvitations` 메서드를 교체:

```java
    /** 보존기간 지난 미처리 가입 요청 삭제 — 방치된 요청이 대기 상한을 영구 점유하지 않게. */
    public int purgeSignupRequests(OffsetDateTime cutoff) {
        int total = 0, n;
        do {
            n = signupRequests.deleteRequestedBefore(cutoff, BATCH);
            total += n;
        } while (n == BATCH);
        return total;
    }
```

`RetentionPurgeJob.java`:
- 필드 `invitationRetention` → `signupRequestRetention` (선언·생성자 파라미터·대입 3곳)
- `@Value("${passkey.retention.invitation:P90D}")` → `@Value("${passkey.retention.signup-request:P90D}")`
- purgeOne 첫 호출을 교체:

```java
            purgeOne(payload, failed, "signupRequestsPurged", "signupRequests",
                    () -> service.purgeSignupRequests(now.minus(signupRequestRetention)));
```

- 클래스 Javadoc 의 "6개 테이블" 은 그대로 둔다(개수 불변).

`application.yml` 83행:

```yaml
    signup-request: P90D        # 미처리 가입 요청 90일 (대기 상한 100 영구 점유 방지)
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests '*RetentionPurge*' -q`
Expected: PASS (6 + 4)

- [ ] **Step 5: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/retention/ \
        admin-app/src/main/resources/application.yml \
        admin-app/src/test/java/com/crosscert/passkey/admin/retention/
git commit -m "refactor(retention): 초대 정리 슬롯을 미처리 가입 요청 정리로 교체"
```

---

### Task 5: 초대 기능 백엔드 제거

**Files:**
- Delete: `admin-app/.../operator/InvitationService.java`, `InvitationController.java`
- Delete: `core/.../entity/AdminUserInvitation.java`, `core/.../repository/AdminUserInvitationRepository.java`
- Delete: `admin-app/src/test/.../operator/AdminUserInvitationFlowIT.java`, `InvitationServiceAcceptTest.java`, `core/src/test/.../entity/AdminUserInvitationExpiryTest.java`
- Modify: `admin-app/.../operator/AdminUserService.java`, `AdminUserController.java`, `AdminUserDto.java`, `PasswordResetService.java:21-27,59-68`
- Modify: `admin-app/src/test/.../operator/AdminUserServiceTest.java`, `BaseUrlValidationTest.java:13`
- Modify: 슬라이스 테스트 11개의 `@MockBean ...AdminUserInvitationRepository invitationRepository;` 줄 삭제
- Modify: `admin-app/src/main/resources/application-prod.yml:73-76`

**Interfaces:**
- Consumes: 없음
- Produces: `AdminUserService(AdminUserRepository, AdminUserTenantRepository, AuditLogService, Clock)` 4-인자 생성자. `AdminUserDto` 에는 `View` 만 남는다.

- [ ] **Step 1: 파일 삭제**

```bash
git rm admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationService.java \
       admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationController.java \
       core/src/main/java/com/crosscert/passkey/core/entity/AdminUserInvitation.java \
       core/src/main/java/com/crosscert/passkey/core/repository/AdminUserInvitationRepository.java \
       admin-app/src/test/java/com/crosscert/passkey/admin/operator/AdminUserInvitationFlowIT.java \
       admin-app/src/test/java/com/crosscert/passkey/admin/operator/InvitationServiceAcceptTest.java \
       core/src/test/java/com/crosscert/passkey/core/entity/AdminUserInvitationExpiryTest.java
```

- [ ] **Step 2: AdminUserService 정리**

- import 3줄 제거: `AdminUserInvitationRepository`, 그리고 `OffsetDateTime` 은 suspend 가 쓰므로 유지
- 필드 `invitationRepo`, `invitationService` 와 생성자 파라미터·대입 제거. 생성자는
  `AdminUserService(AdminUserRepository userRepo, AdminUserTenantRepository mappingRepo, AuditLogService audit, Clock clock)`
- `invite(...)` 메서드(64~106행) 전체 삭제
- `resendInvitation(...)` 메서드(173~183행) 전체 삭제
- 남은 코드에서 `LinkedHashSet` import 가 미사용이 되면 제거

- [ ] **Step 3: AdminUserController 정리**

- `invite` 메서드(36~40행), `resend` 메서드(52~57행) 삭제
- `@RequestParam` 을 더 이상 쓰지 않으면 와일드카드 import 라 변경 없음

- [ ] **Step 4: AdminUserDto 정리**

`InviteRequest`, `InviteResponse`, `InvitationInfo`, `InvitationCheck`, `AcceptRequest` 레코드 삭제. `View` 만 남기고 미사용 import(`Email`, `NotBlank`, `Size`) 제거.

- [ ] **Step 5: PasswordResetService 주석 정리**

- 24행 `InvitationService 패턴 복제 —` → `sha-256 token_hash, MailSender, 1회용 토큰.` (앞부분 삭제)
- 59~64행 Javadoc 을 아래로 교체:

```java
    /**
     * G11 fail-fast — see {@link BaseUrlValidation}. SignupRequestService 도 같은
     * admin.invite.base-url 속성으로 승인 메일의 로그인 링크를 만든다.
     */
```

- [ ] **Step 6: 테스트 정리**

`AdminUserServiceTest.java`:
- import `AdminUserInvitationRepository` 제거
- 필드 `invitationRepo`, `invitationService` 제거, 생성자 호출을 `new AdminUserService(userRepo, mappingRepo, audit, clock)` 로
- 테스트 4개 삭제: `rpAdminInviteRequiresAtLeastOneTenant`, `platformOperatorInviteMustHaveNoTenant`, `inviteAppendsAuditEntry`, `inviteCreatesDisabledPendingUser`
- 미사용 import(`ArgumentCaptor` 는 다른 테스트가 안 쓰면) 제거

`BaseUrlValidationTest.java` 13행: `InvitationService/PasswordResetService's` → `PasswordResetService/SignupRequestService's`

슬라이스 테스트 11개에서 `@MockBean com.crosscert.passkey.core.repository.AdminUserInvitationRepository invitationRepository;` 줄 삭제:

```bash
grep -rl "AdminUserInvitationRepository invitationRepository" admin-app/src/test/java | xargs sed -i '' '/AdminUserInvitationRepository invitationRepository;/d'
grep -rn "Invitation" admin-app/src core/src   # 남은 참조 0 이어야 함(주석 포함)
```

- [ ] **Step 7: application-prod.yml 주석 수정**

73~76행을 아래로 교체:

```yaml
admin:
  invite:
    # 공개 페이지 base URL — 메일 본문의 링크 앞에 붙는다.
    # 비밀번호 재설정(/reset-password?token=) 과 가입 승인 메일의 로그인 링크(/admin)
    # 가 이 값을 쓴다. PasswordResetService·SignupRequestService 의
    # @Value("${admin.invite.base-url:...}") 가 읽는다. 속성 이름은 호환성 때문에 유지.
    base-url: ${ADMIN_INVITE_BASE_URL:http://localhost:5173}
```

- [ ] **Step 8: 컴파일·테스트 확인**

Run: `./gradlew :core:compileJava :admin-app:compileJava :admin-app:compileTestJava -q`
Expected: 성공, 경고만

Run: `./gradlew :admin-app:test --tests '*AdminUserServiceTest*' --tests '*ControllerSecurityTest*' --tests '*BaseUrlValidationTest*' -q`
Expected: PASS. 슬라이스 테스트가 하나라도 컨텍스트 로드 실패하면 그 파일의 MockBean 줄 삭제가 빠진 것.

- [ ] **Step 9: 커밋**

```bash
git add -A admin-app/src core/src
git commit -m "refactor(admin): 초대 기능 제거 — 서비스·컨트롤러·엔티티·테스트·설정"
```

---

### Task 6: 통합 테스트 SignupRequestFlowIT (Testcontainers)

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/operator/SignupRequestFlowIT.java`

**Interfaces:**
- Consumes: `SignupRequestService`, `AdminUserService.suspend`, `TenantAdminService.create`, `AdminUserRepository`, `PasswordEncoder`

- [ ] **Step 1: IT 작성** (컨테이너·resetState 는 삭제된 AdminUserInvitationFlowIT 와 동일 골격)

```java
// admin-app/src/test/java/com/crosscert/passkey/admin/operator/SignupRequestFlowIT.java
package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.tenant.TenantAdminService;
import com.crosscert.passkey.core.repository.AdminSignupRequestRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가입 요청 → 목록 → 승인 → 로그인 가능 상태 풀 플로우 IT (Oracle Testcontainers + Redis).
 * V5 마이그레이션(테이블 생성·GRANT·초대 테이블 DROP)이 실제로 적용되는지도 여기서 확인된다.
 * 서비스 레이어 직접 호출 — 삭제된 AdminUserInvitationFlowIT 와 같은 패턴.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SignupRequestFlowIT {

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE = new OracleContainer(ORACLE_IMAGE)
            .withUsername("PSK_APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("bootstrap-schema.sql"),
                    "/tmp/bootstrap-schema.sql");

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/bootstrap-schema.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException("bootstrap-schema.sql failed (exit=" + exec.getExitCode() + ")\n"
                    + "STDOUT:\n" + exec.getStdout() + "\nSTDERR:\n" + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "PSK_APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired SignupRequestService signupService;
    @Autowired AdminSignupRequestRepository signupRepo;
    @Autowired AdminUserRepository adminUserRepo;
    @Autowired TenantAdminService tenantAdminService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired DataSource ds;
    @Autowired RedisConnectionFactory redisFactory;

    JdbcTemplate jdbc;

    private static final UUID ALICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String ALICE_EMAIL = "alice@crosscert.com";
    private static final UUID SECURITY_CTX_ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static HikariDataSource ownerPool;

    @AfterAll
    static void closeOwnerPool() {
        if (ownerPool != null) { ownerPool.close(); ownerPool = null; }
    }

    private static synchronized JdbcTemplate ownerJdbc() {
        if (ownerPool == null) {
            HikariDataSource p = new HikariDataSource();
            p.setJdbcUrl(ORACLE.getJdbcUrl());
            p.setUsername("PSK_APP_OWNER");
            p.setPassword(SYS_PASSWORD);
            p.setMaximumPoolSize(2);
            p.setPoolName("signup-flow-it-owner");
            ownerPool = p;
        }
        return new JdbcTemplate(ownerPool);
    }

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);
        jdbc.update("DELETE FROM PSK_APP_OWNER.admin_signup_request");
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_aaguid_policy_entry");
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_aaguid_policy");
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.tenant_webauthn_snapshot");
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.audit_log");
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key_scope");
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key");
        jdbc.update("DELETE FROM PSK_APP_OWNER.credential");
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_allowed_origin");
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_accepted_format");
        jdbc.update("DELETE FROM PSK_APP_OWNER.admin_user_tenant");
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.admin_user WHERE email NOT IN ('alice@crosscert.com','bob@crosscert.com')");
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant");

        AdminUserDetails operator = new AdminUserDetails(
                SECURITY_CTX_ACTOR_ID, ALICE_EMAIL, "{noop}unused",
                "PLATFORM_OPERATOR", null, true, null, java.time.Clock.systemUTC());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(operator, null, operator.getAuthorities()));

        var conn = redisFactory.getConnection();
        try { conn.serverCommands().flushAll(); } finally { conn.close(); }
    }

    @Test
    void requestListApproveFlow_rpAdmin() {
        TenantAdminDto.TenantCreateRequest createReq = new TenantAdminDto.TenantCreateRequest(
                "signup-it", "Signup IT Tenant", "localhost", "Signup IT RP",
                List.of("http://localhost:9090"), Set.of("none"), true, false, "NONE", 60000);
        UUID tenantId = tenantAdminService.create(createReq, ALICE_ID, ALICE_EMAIL).id();

        // 1. 공개 요청
        String password = "securePassword123!";
        signupService.request(new SignupRequestDto.Create("NewAdmin@Example.com", password, "RP IAM 담당"));

        // 2. 목록 — 소문자 정규화된 이메일 1건
        List<SignupRequestDto.View> pending = signupService.list();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).email()).isEqualTo("newadmin@example.com");
        assertThat(pending.get(0).reason()).isEqualTo("RP IAM 담당");
        UUID requestId = pending.get(0).id();

        // 3. 같은 이메일 재요청은 조용히 무시
        signupService.request(new SignupRequestDto.Create("newadmin@example.com", password, null));
        assertThat(signupRepo.count()).isEqualTo(1);

        // 4. 승인 — RP_ADMIN + 테넌트 1개
        AdminUserDto.View view = signupService.approve(requestId,
                new SignupRequestDto.Approve("RP_ADMIN", List.of(tenantId)), ALICE_ID, ALICE_EMAIL);
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.role()).isEqualTo("RP_ADMIN");
        assertThat(view.tenantIds()).containsExactly(tenantId);

        // 5. 계정은 로그인 가능 상태이고 비밀번호는 요청 때 것
        var user = adminUserRepo.findByEmail("newadmin@example.com").orElseThrow();
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getStatus()).isEqualTo("ACTIVE");
        assertThat(user.getCreatedBy()).isEqualTo(ALICE_EMAIL);
        assertThat(passwordEncoder.matches(password, user.getBcryptHash())).isTrue();

        // 6. 요청은 소비됨 — 재승인은 404
        assertThat(signupService.list()).isEmpty();
        assertThatThrownBy(() -> signupService.approve(requestId,
                new SignupRequestDto.Approve("RP_ADMIN", List.of(tenantId)), ALICE_ID, ALICE_EMAIL))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        // 7. 승인 감사 행 존재
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM PSK_APP_OWNER.audit_log WHERE action = 'ADMIN_SIGNUP_APPROVE'", Integer.class);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void rejectFlow_removesRequestAndAudits() {
        signupService.request(new SignupRequestDto.Create("reject@example.com", "securePassword123!", null));
        UUID requestId = signupService.list().get(0).id();

        signupService.reject(requestId, ALICE_ID, ALICE_EMAIL);

        assertThat(signupService.list()).isEmpty();
        assertThat(adminUserRepo.findByEmail("reject@example.com")).isEmpty();
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM PSK_APP_OWNER.audit_log WHERE action = 'ADMIN_SIGNUP_REJECT'", Integer.class);
        assertThat(audits).isEqualTo(1);

        // 거절 후 같은 이메일로 재요청 가능
        signupService.request(new SignupRequestDto.Create("reject@example.com", "securePassword123!", null));
        assertThat(signupService.list()).hasSize(1);
    }

    @Test
    void existingAdminEmail_requestIsSilentlyIgnored() {
        signupService.request(new SignupRequestDto.Create(ALICE_EMAIL, "securePassword123!", null));
        assertThat(signupService.list()).isEmpty();
    }
}
```

- [ ] **Step 2: 실행**

Run: `docker info >/dev/null 2>&1 && echo docker-ok` 로 Docker 가용 확인 후
`./gradlew :admin-app:test --tests '*SignupRequestFlowIT*' -q`
Expected: PASS (3 tests). Docker 가 없으면 이 Task 는 "CI 검증 대기" 로 보고하고 커밋은 진행한다.

- [ ] **Step 3: 커밋**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/operator/SignupRequestFlowIT.java
git commit -m "test(admin): 가입 요청→승인→로그인 가능 풀 플로우 IT (초대 IT 대체)"
```

---

### Task 7: 프론트 — 공개 가입 요청 페이지 + 로그인 링크 + 공개 경로 등록

**Files:**
- Create: `admin-ui/src/api/signupRequests.ts`
- Create: `admin-ui/src/pages/SignupRequestPage.tsx`
- Modify: `admin-ui/src/pages/LoginPage.tsx:205-208`
- Modify: `admin-ui/src/App.tsx:21-22,218,229-230`
- Modify: `admin-ui/src/api/client.ts:22-29`
- Modify: `admin-ui/src/api/adminUsers.ts:36-38,83-93,99-100,109-114`
- Modify: `admin-ui/src/api/types.ts:338-361`
- Test: `admin-ui/src/pages/SignupRequestPage.test.tsx`

**Interfaces:**
- Produces: `signupRequestsApi.request({email,password,reason})`, `signupRequestsApi.list()`, `signupRequestsApi.approve(id, {role, tenantIds})`, `signupRequestsApi.reject(id)`; 타입 `SignupRequestView`
- 공개 URL `/admin/signup`

- [ ] **Step 1: 실패하는 페이지 테스트 작성**

```tsx
// admin-ui/src/pages/SignupRequestPage.test.tsx
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';

vi.mock('@/api/signupRequests', () => ({
  signupRequestsApi: { request: vi.fn().mockResolvedValue({ accepted: true }) },
}));

import { signupRequestsApi } from '@/api/signupRequests';
import SignupRequestPage from './SignupRequestPage';

afterEach(cleanup);

function fill(email: string, pw: string, pw2: string) {
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: email } });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: pw } });
  fireEvent.change(screen.getByLabelText('비밀번호 확인'), { target: { value: pw2 } });
}

describe('SignupRequestPage', () => {
  it('keeps submit disabled while passwords differ', () => {
    render(<SignupRequestPage />);
    fill('a@x.com', 'password-12chars', 'password-12charX');
    expect(screen.getByText('두 비밀번호가 일치하지 않습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '가입 요청 보내기' })).toBeDisabled();
  });

  it('keeps submit disabled for a password shorter than 12', () => {
    render(<SignupRequestPage />);
    fill('a@x.com', 'short', 'short');
    expect(screen.getByRole('button', { name: '가입 요청 보내기' })).toBeDisabled();
  });

  it('shows the accepted notice after submit and never reveals account existence', async () => {
    render(<SignupRequestPage />);
    fill('a@x.com', 'password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    await waitFor(() =>
      expect(screen.getByText(/요청이 접수되었습니다/)).toBeInTheDocument(),
    );
    expect(signupRequestsApi.request).toHaveBeenCalledWith({
      email: 'a@x.com', password: 'password-12chars', reason: undefined,
    });
  });

  it('shows the same accepted notice even when the request fails', async () => {
    (signupRequestsApi.request as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('boom'));
    render(<SignupRequestPage />);
    fill('b@x.com', 'password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    await waitFor(() =>
      expect(screen.getByText(/요청이 접수되었습니다/)).toBeInTheDocument(),
    );
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd admin-ui && npx vitest run src/pages/SignupRequestPage.test.tsx`
Expected: FAIL (모듈 없음)

- [ ] **Step 3: API 모듈 작성**

```ts
// admin-ui/src/api/signupRequests.ts
import { api } from './client';
import { adminFetch, type AdminUserView } from './adminUsers';

export type SignupRequestView = {
  id: string;
  email: string;
  reason: string | null;
  requestedAt: string;
};

/**
 * 가입 요청·승인 API.
 * - request: 공개(permitAll) — 항상 202 {accepted:true}. envelope 없음 → postRaw.
 * - list/approve/reject: PLATFORM_OPERATOR 전용, raw JSON → adminFetch.
 */
export const signupRequestsApi = {
  request: (body: { email: string; password: string; reason?: string }) =>
    api.postRaw<{ accepted: boolean }>('/admin/api/signup-requests', body),

  list: (): Promise<SignupRequestView[]> =>
    adminFetch<SignupRequestView[]>('GET', '/admin/api/signup-requests'),

  approve: (id: string, body: { role: string; tenantIds: string[] }): Promise<AdminUserView> =>
    adminFetch<AdminUserView>('POST', `/admin/api/signup-requests/${id}/approve`, body),

  reject: (id: string): Promise<void> =>
    adminFetch<void>('POST', `/admin/api/signup-requests/${id}/reject`, {}),
};
```

- [ ] **Step 4: 페이지 작성**

```tsx
// admin-ui/src/pages/SignupRequestPage.tsx
import { useState } from 'react';
import { signupRequestsApi } from '@/api/signupRequests';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * 미인증 public 화면 — 관리자 계정 가입 요청. PLATFORM_OPERATOR 가 승인해야 로그인 가능.
 * enumeration 방지: 서버는 항상 202 를 주고, 실패해도 같은 안내를 보여준다.
 * 비밀번호 정책(12~128자)은 서버와 동일하게 클라이언트에서도 선검증.
 */
export default function SignupRequestPage() {
  const [email, setEmail] = useState('');
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  const mismatch = pw.length > 0 && pw2.length > 0 && pw !== pw2;
  const pwTooShort = pw.length > 0 && pw.length < 12;
  const canSubmit =
    EMAIL_RE.test(email.trim()) && pw.length >= 12 && pw.length <= 128 && pw === pw2
    && reason.length <= 500 && !submitting;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      await signupRequestsApi.request({
        email: email.trim().toLowerCase(),
        password: pw,
        reason: reason.trim() ? reason.trim() : undefined,
      });
    } catch {
      /* enumeration 방지: 실패해도 동일 안내. */
    } finally {
      setSubmitting(false);
      setDone(true);
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)' }}>
      <div style={{ width: 380, padding: 28, border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)' }}>
        <h2 style={{ marginTop: 0, fontSize: 20 }}>관리자 계정 가입 요청</h2>
        {done ? (
          <>
            <div style={{ padding: '12px 14px', background: 'var(--info-soft)', color: 'var(--info)', borderRadius: 8, fontSize: 13, lineHeight: 1.6 }}>
              요청이 접수되었습니다. 관리자 승인 후 로그인할 수 있습니다. 결과는 이메일로 안내됩니다.
            </div>
            <a href="/admin" className="btn btn--ghost btn--sm" style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>← 로그인으로</a>
          </>
        ) : (
          <form onSubmit={submit}>
            <div style={{ fontSize: 13, color: 'var(--text-mute)', margin: '6px 0 18px' }}>
              이메일과 비밀번호를 정하면 플랫폼 운영자가 검토 후 승인합니다.
            </div>
            <label className="label" htmlFor="signup-email">이메일</label>
            <input id="signup-email" className="input" type="email" autoFocus value={email}
              onChange={(e) => setEmail(e.target.value)} autoComplete="username"
              placeholder="you@company.com" style={{ width: '100%', marginBottom: 10 }} />
            <label className="label" htmlFor="signup-pw">비밀번호</label>
            <input id="signup-pw" className="input" type="password" value={pw}
              onChange={(e) => setPw(e.target.value)} autoComplete="new-password"
              style={{ width: '100%', marginBottom: 10 }} />
            {pwTooShort && <div style={{ color: 'var(--danger)', fontSize: 12, marginTop: -6, marginBottom: 8 }}>비밀번호는 12자 이상이어야 합니다.</div>}
            <label className="label" htmlFor="signup-pw2">비밀번호 확인</label>
            <input id="signup-pw2" className="input" type="password" value={pw2}
              onChange={(e) => setPw2(e.target.value)} autoComplete="new-password"
              style={{ width: '100%', marginBottom: 10 }} />
            {mismatch && <div style={{ color: 'var(--danger)', fontSize: 12, marginTop: -6, marginBottom: 8 }}>두 비밀번호가 일치하지 않습니다.</div>}
            <label className="label" htmlFor="signup-reason">요청 사유 (선택)</label>
            <textarea id="signup-reason" className="input" value={reason} maxLength={500}
              onChange={(e) => setReason(e.target.value)} rows={3}
              placeholder="소속·역할 등 승인 판단에 도움이 되는 내용" style={{ width: '100%', resize: 'vertical' }} />
            <button type="submit" className="btn btn--primary" disabled={!canSubmit}
              style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>
              {submitting ? '전송 중…' : '가입 요청 보내기'}
            </button>
            <a href="/admin" className="btn btn--ghost btn--sm" style={{ width: '100%', marginTop: 8, justifyContent: 'center' }}>← 로그인으로</a>
          </form>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 5: 로그인 페이지 링크**

`LoginPage.tsx` 205행 안내 박스 **앞**에 삽입:

```tsx
          <div style={{ marginTop: 16, fontSize: 12, color: 'var(--text-mute)', textAlign: 'center' }}>
            계정이 없으신가요?{' '}
            <a href="/admin/signup" style={{ color: 'var(--accent)', textDecoration: 'none', fontWeight: 600 }}>가입 요청</a>
          </div>
```

- [ ] **Step 6: App.tsx 공개 경로**

- 22행 뒤 import 추가: `import SignupRequestPage from '@/pages/SignupRequestPage';`
- 218행: `const isPublicPath = location.pathname === '/forgot-password' || location.pathname === '/reset-password' || location.pathname === '/signup';`
- 230행 뒤 추가: `if (location.pathname === '/signup') return <SignupRequestPage />;`

- [ ] **Step 7: 401 리다이렉트 예외**

`client.ts` `isOnLoginScreen()` 의 return 에 `|| p.startsWith('/admin/signup')` 추가.
`adminUsers.ts` 36~38행의 `onLogin` 판정에도 `|| p.startsWith('/admin/signup')` 추가.

- [ ] **Step 8: 초대 API·타입 제거**

`adminUsers.ts`: `InvitationInfo`, `InviteResponse` 타입(83~93행), `invite`(99~100행), `resendInvitation`(109~114행) 삭제.
`types.ts`: `InviteRequest`, `InvitationInfo`, `InviteResponse`, `InvitationCheck`(338~361행) 삭제.

이 시점에 `AdminUsersTab.tsx` 가 삭제된 심볼을 참조해 `tsc` 가 실패한다. Task 8 이 해결하므로 이 Task 의 검증은 vitest 만 돌린다.

- [ ] **Step 9: 테스트 통과 확인**

Run: `cd admin-ui && npx vitest run src/pages/SignupRequestPage.test.tsx`
Expected: PASS (4 tests)

- [ ] **Step 10: 커밋**

```bash
git add admin-ui/src/api/signupRequests.ts admin-ui/src/pages/SignupRequestPage.tsx \
        admin-ui/src/pages/SignupRequestPage.test.tsx admin-ui/src/pages/LoginPage.tsx \
        admin-ui/src/App.tsx admin-ui/src/api/client.ts admin-ui/src/api/adminUsers.ts admin-ui/src/api/types.ts
git commit -m "feat(admin-ui): 공개 가입 요청 페이지 + 로그인 링크, 초대 API 제거"
```

---

### Task 8: 프론트 — 운영자 탭 가입 요청 섹션·승인 다이얼로그·거절 확인

**Files:**
- Modify: `admin-ui/src/pages/settings/AdminUsersTab.tsx` (전면)
- Test: `admin-ui/src/pages/settings/AdminUsersTab.test.tsx`

**Interfaces:**
- Consumes: Task 7 의 `signupRequestsApi`, `SignupRequestView`; 기존 `adminUsersApi.list/suspend/activate`, `tenantsApi.list`, `Dialog`, `useToast`, `StatusBadge`

- [ ] **Step 1: 실패하는 테스트 작성**

```tsx
// admin-ui/src/pages/settings/AdminUsersTab.test.tsx
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { ToastHost } from '@/shell/ToastHost';

vi.mock('@/api/adminUsers', () => ({
  adminUsersApi: {
    list: vi.fn().mockResolvedValue([]),
    suspend: vi.fn(), activate: vi.fn(), addTenant: vi.fn(), removeTenant: vi.fn(),
  },
  adminFetch: vi.fn(),
}));
vi.mock('@/api/signupRequests', () => ({
  signupRequestsApi: {
    list: vi.fn(),
    approve: vi.fn().mockResolvedValue({}),
    reject: vi.fn().mockResolvedValue(undefined),
  },
}));
vi.mock('@/api/tenants', () => ({
  tenantsApi: { list: vi.fn().mockResolvedValue([{ id: 't1', name: 'Acme', slug: 'acme', status: 'ACTIVE' }]) },
}));

import { signupRequestsApi } from '@/api/signupRequests';
import AdminUsersTab from './AdminUsersTab';

const pendingOne = [{ id: 'r1', email: 'new@x.com', reason: 'RP 담당', requestedAt: new Date().toISOString() }];

beforeEach(() => {
  (signupRequestsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue(pendingOne);
});
afterEach(cleanup);

function renderTab() {
  return render(<ToastHost><AdminUsersTab /></ToastHost>);
}

describe('AdminUsersTab signup requests', () => {
  it('renders pending requests with count badge, email and reason', async () => {
    renderTab();
    await waitFor(() => expect(screen.getByText('new@x.com')).toBeInTheDocument());
    expect(screen.getByText('RP 담당')).toBeInTheDocument();
    expect(screen.getByText('대기 1건')).toBeInTheDocument();
  });

  it('shows empty notice when no request is pending', async () => {
    (signupRequestsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    renderTab();
    await waitFor(() => expect(screen.getByText('대기 중인 요청이 없습니다')).toBeInTheDocument());
  });

  it('approve opens a dialog with the email read-only and submits role + tenants', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인' }));
    const emailInput = screen.getByDisplayValue('new@x.com') as HTMLInputElement;
    expect(emailInput.readOnly).toBe(true);
    await waitFor(() => screen.getByText('Acme'));   // tenant 목록 로드
    fireEvent.click(screen.getByRole('button', { name: '승인하고 계정 생성' }));
    await waitFor(() =>
      expect(signupRequestsApi.approve).toHaveBeenCalledWith('r1', { role: 'RP_ADMIN', tenantIds: ['t1'] }),
    );
  });

  it('reject asks for in-app confirmation before calling the API', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절' }));
    expect(signupRequestsApi.reject).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '거절 확정' }));
    await waitFor(() => expect(signupRequestsApi.reject).toHaveBeenCalledWith('r1'));
  });

  it('no invite button remains', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    expect(screen.queryByText(/운영자 추가/)).not.toBeInTheDocument();
    expect(screen.queryByText(/초대/)).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd admin-ui && npx vitest run src/pages/settings/AdminUsersTab.test.tsx`
Expected: FAIL

- [ ] **Step 3: AdminUsersTab 재작성**

`AdminUsersTab.tsx` 를 아래 내용으로 교체한다. 유지되는 부분: `timeAgo`, `tail`, `Field`, 운영자 테이블(정지·활성화·MFA·마지막 로그인 열). 제거: `showNew`/`invitation` 상태, `handleCreate`, `handleResend`, PENDING 재발송 버튼, `NewAdminDialog`, `InvitationModal`, `copyToClipboard` import. 추가: 가입 요청 섹션, `ApproveDialog`, `RejectConfirm`.

```tsx
import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { adminUsersApi, type AdminUserView } from '@/api/adminUsers';
import { signupRequestsApi, type SignupRequestView } from '@/api/signupRequests';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
import { ApiError } from '@/api/types';
import { useToast } from '@/shell/ToastHost';
import { StatusBadge } from '@/shell/StatusBadge';
import { Dialog } from '@/shell/Dialog';

// ── Local utilities ───────────────────────────────────────────────────────────

function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return '방금 전';
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}일 전`;
  const mo = Math.floor(d / 30);
  return `${mo}개월 전`;
}

function tail(s: string, n: number): string {
  return s.slice(-n);
}

function Field({ label, hint, children }: { label: string; hint?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

// ── AdminUsersTab ─────────────────────────────────────────────────────────────

export default function AdminUsersTab() {
  const [users, setUsers] = useState<AdminUserView[]>([]);
  const [requests, setRequests] = useState<SignupRequestView[]>([]);
  const [loading, setLoading] = useState(true);
  const [approving, setApproving] = useState<SignupRequestView | null>(null);
  const [rejecting, setRejecting] = useState<SignupRequestView | null>(null);
  const toast = useToast();

  async function reload() {
    setLoading(true);
    try {
      const [list, pending] = await Promise.all([adminUsersApi.list(), signupRequestsApi.list()]);
      setUsers(list);
      setRequests(pending);
    } catch (e: unknown) {
      toast({ kind: 'err', title: 'Admin 사용자 로드 실패', message: errMsg(e) });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void reload(); }, []);

  async function handleApprove(req: SignupRequestView, body: { role: string; tenantIds: string[] }) {
    try {
      await signupRequestsApi.approve(req.id, body);
      setApproving(null);
      toast({ kind: 'ok', title: '가입 요청을 승인했습니다.', message: `${req.email} · 즉시 로그인 가능` });
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        toast({ kind: 'warn', title: '이미 처리된 요청입니다.', message: '다른 관리자가 먼저 처리했습니다.' });
        setApproving(null);
      } else {
        toast({ kind: 'err', title: '승인 실패', message: errMsg(e) });
      }
    } finally {
      await reload();
    }
  }

  async function handleReject(req: SignupRequestView) {
    try {
      await signupRequestsApi.reject(req.id);
      toast({ kind: 'warn', title: '가입 요청을 거절했습니다.', message: req.email });
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        toast({ kind: 'warn', title: '이미 처리된 요청입니다.', message: '다른 관리자가 먼저 처리했습니다.' });
      } else {
        toast({ kind: 'err', title: '거절 실패', message: errMsg(e) });
      }
    } finally {
      setRejecting(null);
      await reload();
    }
  }

  async function handleSuspend(u: AdminUserView) {
    try {
      await adminUsersApi.suspend(u.id);
      toast({ kind: 'warn', title: '운영자가 정지되었습니다.', message: u.email });
      await reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '정지 실패', message: errMsg(e) });
    }
  }

  async function handleActivate(u: AdminUserView) {
    try {
      await adminUsersApi.activate(u.id);
      toast({ kind: 'ok', title: '운영자가 재활성화되었습니다.', message: u.email });
      await reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '활성화 실패', message: errMsg(e) });
    }
  }

  if (loading && users.length === 0 && requests.length === 0) {
    return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Loading…</div>;
  }

  const activeCount = users.filter((u) => u.status === 'ACTIVE').length;

  return (
    <div className="stack-4">
      {/* ── 가입 요청 ── */}
      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">가입 요청</h3>
            <div className="card__sub">로그인 화면에서 접수된 계정 요청. 승인 시 역할과 tenant 를 지정합니다.</div>
          </div>
          <span className={`badge ${requests.length > 0 ? 'badge--warning' : ''}`}>대기 {requests.length}건</span>
        </div>
        {requests.length === 0 ? (
          <div style={{ padding: '14px 16px', fontSize: 13, color: 'var(--text-mute)' }}>대기 중인 요청이 없습니다</div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>이메일</th>
                <th>요청 사유</th>
                <th>요청 시각</th>
                <th style={{ textAlign: 'right' }}>액션</th>
              </tr>
            </thead>
            <tbody>
              {requests.map((r) => (
                <tr key={r.id}>
                  <td style={{ fontWeight: 600, fontSize: 13 }}>{r.email}</td>
                  <td style={{ fontSize: 13, color: r.reason ? 'var(--text)' : 'var(--text-mute)' }}>{r.reason ?? '—'}</td>
                  <td><span className="muted">{timeAgo(r.requestedAt)}</span></td>
                  <td style={{ textAlign: 'right' }}>
                    <div className="row" style={{ justifyContent: 'flex-end', gap: 4 }}>
                      <button className="btn btn--primary btn--xs" onClick={() => setApproving(r)}>승인</button>
                      <button className="btn btn--xs" style={{ color: 'var(--danger)' }} onClick={() => setRejecting(r)}>거절</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* ── 콘솔 운영자 ── */}
      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">콘솔 운영자</h3>
            <div className="card__sub">{users.length}명 · 활성 {activeCount}명</div>
          </div>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>운영자</th>
              <th>역할</th>
              <th>Tenant</th>
              <th>MFA</th>
              <th>마지막 로그인</th>
              <th>상태</th>
              <th style={{ textAlign: 'right' }}>액션</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id} style={{ opacity: u.status === 'SUSPENDED' ? 0.55 : 1 }}>
                <td>
                  <div className="row">
                    <div style={{
                      width: 28, height: 28, borderRadius: 999,
                      background: u.role === 'PLATFORM_OPERATOR' ? 'var(--violet-soft)' : 'var(--info-soft)',
                      color: u.role === 'PLATFORM_OPERATOR' ? 'var(--violet)' : 'var(--info)',
                      display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 11, flex: 'none',
                    }}>
                      {u.email.slice(0, 1).toUpperCase()}
                    </div>
                    <div className="stack-1">
                      <div style={{ fontWeight: 600, fontSize: 13 }}>{u.email}</div>
                      <div className="muted mono" style={{ fontSize: 11 }}>{tail(u.id, 10)}</div>
                    </div>
                  </div>
                </td>
                <td>
                  <span className={`badge ${u.role === 'PLATFORM_OPERATOR' ? 'badge--violet' : 'badge--info'}`}>{u.role}</span>
                </td>
                <td>
                  {u.tenantIds && u.tenantIds.length > 0 ? (
                    <span className="mono" style={{ fontSize: 12 }}>
                      {u.tenantIds.length === 1 ? tail(u.tenantIds[0], 10) : `${u.tenantIds.length}개 RP`}
                    </span>
                  ) : (
                    <span className="faint">—</span>
                  )}
                </td>
                <td>
                  {u.mfaEnabled ? (
                    <span className="badge badge--success" style={{ fontSize: 10 }}><Icons.Check size={10} /> ON</span>
                  ) : (
                    <span className="badge badge--warning" style={{ fontSize: 10 }}><Icons.Alert size={10} /> OFF</span>
                  )}
                </td>
                <td>
                  {u.lastLoginAt ? <span className="muted">{timeAgo(u.lastLoginAt)}</span> : <span className="faint">미접속</span>}
                </td>
                <td><StatusBadge status={u.status} /></td>
                <td style={{ textAlign: 'right' }}>
                  <div className="row" style={{ justifyContent: 'flex-end', gap: 4 }}>
                    {u.status === 'ACTIVE' && (
                      <button className="btn btn--xs" onClick={() => void handleSuspend(u)} style={{ color: 'var(--warning)' }}>정지</button>
                    )}
                    {u.status === 'SUSPENDED' && (
                      <button className="btn btn--xs" onClick={() => void handleActivate(u)} style={{ color: 'var(--success)' }}>활성화</button>
                    )}
                    <button className="btn btn--ghost btn--xs"><Icons.Dots size={14} /></button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {approving && (
        <ApproveDialog
          request={approving}
          onClose={() => setApproving(null)}
          onApprove={(body) => handleApprove(approving, body)}
        />
      )}

      {rejecting && (
        <Dialog
          open
          onClose={() => setRejecting(null)}
          title="가입 요청 거절"
          sub={`${rejecting.email} 의 요청을 거절합니다. 요청은 삭제되며 같은 이메일로 다시 요청할 수 있습니다.`}
          footer={
            <>
              <button className="btn" onClick={() => setRejecting(null)}>취소</button>
              <button className="btn btn--danger" onClick={() => void handleReject(rejecting)}>거절 확정</button>
            </>
          }
        >
          <div style={{ fontSize: 13, color: 'var(--text-mute)' }}>거절 사실은 요청자에게 메일로 안내되고 audit log 에 기록됩니다.</div>
        </Dialog>
      )}
    </div>
  );
}

// ── ApproveDialog ─────────────────────────────────────────────────────────────

function ApproveDialog({
  request,
  onClose,
  onApprove,
}: {
  request: SignupRequestView;
  onClose: () => void;
  onApprove: (body: { role: string; tenantIds: string[] }) => Promise<void>;
}) {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [role, setRole] = useState('RP_ADMIN');
  const [tenantIds, setTenantIds] = useState<string[]>([]);
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    tenantsApi
      .list()
      .then((list) => {
        const active = list.filter((t) => t.status === 'ACTIVE');
        setTenants(active);
        if (active.length > 0) setTenantIds([active[0].id]);
      })
      .catch(() => { /* non-critical */ });
  }, []);

  function toggleTenant(id: string) {
    setTenantIds((prev) => (prev.includes(id) ? prev.filter((t) => t !== id) : [...prev, id]));
  }

  const formValid = role === 'PLATFORM_OPERATOR' || tenantIds.length > 0;

  async function submit() {
    setTouched(true);
    if (!formValid) return;
    setSubmitting(true);
    try {
      await onApprove({ role, tenantIds: role === 'RP_ADMIN' ? tenantIds : [] });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog
      open
      onClose={onClose}
      wide
      title="가입 요청 승인"
      sub="역할과 tenant 를 지정하면 계정이 즉시 활성화되고 요청자가 정한 비밀번호로 로그인할 수 있습니다."
      footer={
        <>
          <button className="btn" onClick={onClose}>취소</button>
          <button className="btn btn--primary" disabled={!formValid || submitting} onClick={() => void submit()}>
            승인하고 계정 생성
          </button>
        </>
      }
    >
      <div className="stack-3">
        <Field label="이메일" hint="요청자가 입력한 로그인 ID 입니다.">
          <input className="input" type="email" value={request.email} readOnly />
        </Field>

        {request.reason && (
          <Field label="요청 사유">
            <div style={{ padding: '8px 12px', background: 'var(--surface-3)', borderRadius: 6, fontSize: 13, whiteSpace: 'pre-wrap' }}>{request.reason}</div>
          </Field>
        )}

        <Field label="Role">
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
            {[
              { v: 'PLATFORM_OPERATOR', t: 'Platform Operator', d: '모든 tenant에 대해 cross-tenant 운영 가능. Crosscert 사내용.' },
              { v: 'RP_ADMIN', t: 'RP Admin', d: '한 tenant 안에서만 모든 권한. RP 회사의 IAM 담당자용.' },
            ].map((opt) => (
              <button key={opt.v} type="button" onClick={() => setRole(opt.v)} style={{
                padding: '10px 12px', borderRadius: 8,
                border: `1px solid ${role === opt.v ? 'var(--accent)' : 'var(--border)'}`,
                background: role === opt.v ? 'var(--accent-soft)' : 'var(--surface)',
                color: role === opt.v ? 'var(--accent)' : 'var(--text)', cursor: 'pointer', textAlign: 'left',
              }}>
                <div className="row" style={{ gap: 6 }}>
                  <Icons.Shield size={13} />
                  <div style={{ fontSize: 13, fontWeight: 600 }}>{opt.t}</div>
                </div>
                <div style={{ fontSize: 11, marginTop: 4, lineHeight: 1.5,
                  color: role === opt.v ? 'color-mix(in oklab, var(--accent) 70%, var(--text))' : 'var(--text-mute)' }}>
                  {opt.d}
                </div>
              </button>
            ))}
          </div>
        </Field>

        {role === 'RP_ADMIN' && (
          <Field
            label="할당 Tenant"
            hint={tenantIds.length === 0 && touched
              ? <span style={{ color: 'var(--danger)' }}>Tenant를 최소 1개 선택해야 합니다.</span>
              : '이 운영자가 접근 가능한 tenant를 선택하세요 (복수 선택 가능).'}
          >
            <div style={{ border: '1px solid var(--border)', borderRadius: 6, overflow: 'hidden', maxHeight: 200, overflowY: 'auto' }}>
              {tenants.length === 0 && (
                <div style={{ padding: '10px 12px', fontSize: 13, color: 'var(--text-mute)' }}>활성 tenant 없음</div>
              )}
              {tenants.map((t, i) => {
                const checked = tenantIds.includes(t.id);
                return (
                  <label key={t.id} style={{
                    display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px',
                    borderBottom: i < tenants.length - 1 ? '1px solid var(--border)' : 'none',
                    background: checked ? 'var(--accent-soft)' : 'transparent', cursor: 'pointer', fontSize: 13,
                  }}>
                    <input type="checkbox" checked={checked} onChange={() => toggleTenant(t.id)} />
                    <div style={{ flex: 1 }}>
                      <div style={{ fontWeight: 600, color: checked ? 'var(--accent)' : 'var(--text)' }}>{t.name}</div>
                      <div style={{ fontSize: 11, color: 'var(--text-mute)' }}>{t.slug}</div>
                    </div>
                  </label>
                );
              })}
            </div>
          </Field>
        )}
      </div>
    </Dialog>
  );
}
```

`btn--danger` 클래스가 스타일시트에 없으면 `className="btn"` 에 `style={{ color: 'var(--danger)' }}` 로 대체한다(확인: `grep -rn "btn--danger" admin-ui/src/styles admin-ui/src/*.css`).

- [ ] **Step 4: 테스트·타입·빌드 확인**

Run: `cd admin-ui && npx vitest run`
Expected: 전체 PASS (기존 108 + 신규 4 + 5)

Run: `cd admin-ui && npx tsc -b && npm run build`
Expected: 성공. Task 7 에서 남긴 타입 오류가 여기서 해소된다.

- [ ] **Step 5: 커밋**

```bash
git add admin-ui/src/pages/settings/AdminUsersTab.tsx admin-ui/src/pages/settings/AdminUsersTab.test.tsx
git commit -m "feat(admin-ui): 운영자 탭 가입 요청 섹션·승인 다이얼로그·거절 확인, 초대 UI 제거"
```

---

### Task 9: 문서 정리 + 전체 검증

**Files:**
- Modify: `docs/logging-operations.md:113-115`
- Modify: `docs/entity-relationship-diagram.md:7`
- Modify: `docs/logging-conventions.md:26`
- Modify: `README.md` (초대 절차 언급이 있을 때만)

- [ ] **Step 1: 문서 수정**

`docs/logging-operations.md` 113~115행 세 줄을 아래로 교체:

```markdown
| `WARN signup request skipped: reason=already-admin` | 기존 계정 이메일로 가입 요청 | 정상(열거 방지 응답). 반복되면 계정 소유자 확인 |
| `WARN signup request skipped: reason=pending-cap` | 대기 요청 100건 도달 | 운영자 탭에서 요청 정리(승인/거절) 또는 retention 대기 |
| `WARN signup request skipped: reason=concurrent-duplicate` | 같은 이메일 동시 요청 | 정상 |
```

`docs/entity-relationship-diagram.md` 7행: `(recovery code, reset token, invitation)` → `(recovery code, reset token, signup request)`. 같은 문서에서 `ADMIN_USER_INVITATION` 박스가 있으면 `ADMIN_SIGNUP_REQUEST`(독립 테이블, FK 없음: email·bcrypt_hash·reason·requested_at) 로 교체한다:

```bash
grep -n "INVITATION\|invitation" docs/entity-relationship-diagram.md
```

`docs/logging-conventions.md` 26행 예시를 `log.info("signup request approved: email={} role={}", CryptoUtils.maskEmail(email), role);` 로.

README 와 docs 전체 점검:

```bash
grep -rn "초대\|invit" README.md docs/*.md | grep -v superpowers
```

가입 절차를 설명하는 곳이 있으면 "로그인 화면 → 가입 요청 → PLATFORM_OPERATOR 가 설정 › Admin 사용자 탭에서 승인" 으로 바꾼다.

- [ ] **Step 2: 잔존 참조 0 확인**

```bash
grep -rn "nvitation\|accept-invite\|INVITE" --include='*.java' --include='*.ts' --include='*.tsx' --include='*.yml' --include='*.sql' admin-app/src core/src admin-ui/src passkey-app/src
```

Expected: `V1__baseline_schema.sql` 의 초대 테이블 DDL(과거 스냅샷, 수정 금지)과 `admin.invite.base-url` 속성명만 남는다.

- [ ] **Step 3: 백엔드 전체 테스트 (IT 제외)**

Run: `./gradlew :core:test :admin-app:test -x :admin-app:testIntegration -q` 가 없으면
`./gradlew :core:test :admin-app:test --tests '*Test' -q`
Expected: 전부 PASS. 실패가 있으면 base worktree(main)에서 같은 테스트를 돌려 pre-existing 인지 대조한다(메모리: 전체 build 는 SliceConfig 충돌·Oracle 컨테이너 경합으로 원래 빨갈 수 있음).

- [ ] **Step 4: 프론트 전체**

Run: `cd admin-ui && npx vitest run && npx tsc -b && npm run build`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add docs README.md
git commit -m "docs: 가입 요청·승인 흐름 반영, 초대 절차 문서 제거"
```

---

## Self-Review

**Spec coverage:**
- §2 데이터 모델 → Task 1 (테이블·GRANT·DROP·엔티티·리포지토리)
- §3.1 공개 엔드포인트(202·열거방지·상한·UNIQUE 삼킴) → Task 2, 3
- §3.2 관리 엔드포인트(역할규칙·404·409·경합·감사·메일) → Task 2, 3
- §3.3 보안 설정(POST 만 permitAll·CSRF·초대 경로 제거·base-url 주석) → Task 3, 5
- §3.4 보존 정리 → Task 4
- §3.5 제거 목록 → Task 5 (백엔드), Task 7·8 (프론트), Task 9 (문서)
- §4.1 로그인 링크 → Task 7; §4.2 가입 요청 페이지·공개 경로·401 예외 → Task 7; §4.3 운영자 탭 → Task 8
- §5 테스트: 서비스 단위(Task 2, 17건), 슬라이스(Task 3, 11건), IT(Task 6), vitest(Task 7·8)
- §6 배포 주의(retention 속성 rename) → Task 4 yml + 스펙 문서에 이미 기재

**Placeholder scan:** TBD/TODO 없음. 모든 코드 스텝에 실제 코드 있음.

**Type consistency:**
- `SignupRequestService` 생성자 8-인자 순서: (requests, users, mappings, audit, mail, encoder, clock, env) — Task 2 테스트와 구현 일치
- `AdminUserService` 4-인자 생성자 (userRepo, mappingRepo, audit, clock) — Task 5 서비스·테스트 일치
- `deleteIfPresent(UUID)`, `deleteRequestedBefore(OffsetDateTime,int)` — Task 1 정의, Task 2·4 사용 일치
- `signupRequestsApi.request/list/approve/reject` — Task 7 정의, Task 8 사용 일치
- 감사 액션 `ADMIN_SIGNUP_APPROVE`/`ADMIN_SIGNUP_REJECT` — Task 2 구현·테스트·Task 6 IT 일치
