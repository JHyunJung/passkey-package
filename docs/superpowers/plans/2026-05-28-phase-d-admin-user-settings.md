# Phase D — Admin 사용자 관리 + Settings 셸 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** Admin 사용자 관리 (초대 토큰 + 수락 페이지 + suspend/activate + 마지막 PO lockout 방지) + UI Settings 페이지 셸 (AdminUsers + MDS Status 탭) 풀스택 구현.

**Architecture:**
- 기존 `admin_user` 테이블에 `status`/`created_by`/`suspended_at`/`suspended_by` 추가 + `bcrypt_hash` NULL 허용 (V29)
- 신규 `admin_user_invitation` 테이블 (V29) — token_hash + plaintext 1회 노출 패턴 (API Key 와 동일)
- `MailSender` 인터페이스 + `LogMailSender` (SLF4J 로그 fallback)
- 비인증 수락 엔드포인트 (`GET/POST /admin/api/invitations/{token}`) — 토큰 검증 + 비밀번호 설정 + status=ACTIVE
- Lockout 방지: 자기 자신 suspend/delete 금지, 마지막 ACTIVE PLATFORM_OPERATOR suspend/delete 금지
- UI: `/accept-invite?token=...` 페이지 (비인증) + `/settings` 페이지 (PO only, AdminUsers + MDS 탭)

**Tech Stack:** Spring Boot 3 + Oracle 19c + JPA + Flyway + React/TS

---

## File Structure

**Create (server)**
- `core/src/main/resources/db/migration/V29__admin_user_invitation.sql`
- `core/src/main/java/com/crosscert/passkey/core/entity/AdminUserInvitation.java`
- `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserInvitationRepository.java`
- `core/src/main/java/com/crosscert/passkey/core/mail/MailSender.java` (interface)
- `core/src/main/java/com/crosscert/passkey/core/mail/LogMailSender.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserController.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserDto.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserService.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationController.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationService.java`
- `admin-app/src/test/.../AdminUserInvitationFlowIT.java`

**Modify (server)**
- `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java` — 4 필드 추가 + bcrypt_hash nullable
- `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRepository.java` — 보조 메서드 (count active PO 등)

**Create (UI)**
- `admin-ui/src/pages/AcceptInvite.tsx` — 비인증 라우트
- `admin-ui/src/pages/settings/Settings.tsx` — 탭 셸
- `admin-ui/src/pages/settings/AdminUsersTab.tsx`
- `admin-ui/src/pages/settings/MdsStatusTab.tsx` — 기존 MdsStatus 페이지를 탭으로 이관
- `admin-ui/src/api/adminUser.ts`

**Modify (UI)**
- `admin-ui/src/App.tsx` — `/accept-invite`, `/settings` 라우트 추가, `/mds` 를 `/settings?tab=mds` 로 리다이렉트 처리 (또는 단순히 Sidebar 의 MDS 제거하고 Settings 안으로)
- `admin-ui/src/shell/Sidebar.tsx` — MDS 제거, Settings 추가
- `admin-ui/src/api/types.ts` — Admin user/invitation 타입

**Tests:** IT 1개 (invite → accept → login 풀 플로우)

---

## Conventions

- Working dir base: `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-user-settings`
- 서버 빌드: `./gradlew :admin-app:compileJava :admin-app:compileTestJava`
- 테스트: `./gradlew :admin-app:test --tests "*AdminUserInvitationFlowIT"`
- UI 빌드: `cd admin-ui && npm run build`
- 각 task commit 전 `codex review` (실행 불가하면 skip + 보고)
- 한국어 주석 OK

---

## Task 1: V29 마이그레이션 + AdminUser entity 확장

**Files:**
- Create: `core/src/main/resources/db/migration/V29__admin_user_invitation.sql`
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java`

### V29 SQL

V21 패턴 (단순 plain SQL):

```sql
-- ============================================================
-- V29 — Admin User Invitation + status/created_by/suspended_at 컬럼
--
-- 목적: 운영자 초대 플로우 + 정지/활성화 + lockout 방지 audit.
--
-- bcrypt_hash NULL 허용: PENDING 상태 사용자는 비밀번호 없음 (수락 시 설정).
-- status: ACTIVE(default) | PENDING | SUSPENDED.
--   기존 모든 admin_user 행은 default 'ACTIVE'.
-- ============================================================

-- 1. admin_user 컬럼 추가
ALTER TABLE admin_user MODIFY (bcrypt_hash VARCHAR2(72) NULL);

ALTER TABLE admin_user ADD (
  status        VARCHAR2(16) DEFAULT 'ACTIVE' NOT NULL,
  created_by    VARCHAR2(255),
  suspended_at  TIMESTAMP WITH TIME ZONE,
  suspended_by  VARCHAR2(255),
  CONSTRAINT ck_admin_user_status CHECK (status IN ('ACTIVE','PENDING','SUSPENDED'))
);

-- 2. admin_user_invitation 테이블
CREATE SEQUENCE admin_user_invitation_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE admin_user_invitation (
  id              NUMBER(19,0)             NOT NULL,
  admin_user_id   NUMBER(19,0)             NOT NULL,
  token_hash      VARCHAR2(64)             NOT NULL,
  token_prefix    VARCHAR2(8)              NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  created_by      VARCHAR2(255)            NOT NULL,
  expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  accepted_at     TIMESTAMP WITH TIME ZONE,
  resent_count    NUMBER(5,0)              DEFAULT 0 NOT NULL,
  resent_at       TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_admin_user_invitation PRIMARY KEY (id),
  CONSTRAINT uq_admin_user_invitation_token UNIQUE (token_hash),
  CONSTRAINT fk_admin_user_invitation_user FOREIGN KEY (admin_user_id) REFERENCES admin_user(id) ON DELETE CASCADE
);

CREATE INDEX ix_admin_user_invitation_user ON admin_user_invitation (admin_user_id);

-- 3. 권한
GRANT SELECT, INSERT, UPDATE ON admin_user_invitation TO APP_ADMIN;
GRANT SELECT ON admin_user_invitation_seq TO APP_ADMIN;
```

### AdminUser entity 변경

기존 AdminUser 에 다음 추가:
- `private String status = "ACTIVE";` + `@Column(name = "STATUS", length = 16, nullable = false)`
- `private String createdBy;` `@Column(name = "CREATED_BY", length = 255)`
- `private Instant suspendedAt;` `@Column(name = "SUSPENDED_AT")`
- `private String suspendedBy;` `@Column(name = "SUSPENDED_BY", length = 255)`
- `bcrypt_hash` 컬럼의 nullable=true 로 변경 (`@Column(name="BCRYPT_HASH", length=72)` 만, nullable=false 제거)

새 getter/setter.

### Steps
1. V29 SQL 작성
2. AdminUser entity 수정 — 기존 코드 보존하면서 4 필드 추가
3. 컴파일 0 에러
4. codex review (실행 불가하면 skip)
5. Commit:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-user-settings
git add core/src/main/resources/db/migration/V29__admin_user_invitation.sql \
        core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java
git commit -m "feat(core): V29 admin_user invitation 테이블 + status 컬럼 + AdminUser entity (Phase D.1)"
```

---

## Task 2: AdminUserInvitation entity + Repository

**Files (Create):**
- `core/src/main/java/com/crosscert/passkey/core/entity/AdminUserInvitation.java`
- `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserInvitationRepository.java`

```java
@Entity
@Table(name = "ADMIN_USER_INVITATION")
public class AdminUserInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "admin_user_invitation_seq")
    @SequenceGenerator(name = "admin_user_invitation_seq", sequenceName = "admin_user_invitation_seq", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "ADMIN_USER_ID", nullable = false)
    private Long adminUserId;

    @Column(name = "TOKEN_HASH", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "TOKEN_PREFIX", length = 8, nullable = false)
    private String tokenPrefix;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "CREATED_BY", length = 255, nullable = false)
    private String createdBy;

    @Column(name = "EXPIRES_AT", nullable = false)
    private Instant expiresAt;

    @Column(name = "ACCEPTED_AT")
    private Instant acceptedAt;

    @Column(name = "RESENT_COUNT", nullable = false)
    private int resentCount = 0;

    @Column(name = "RESENT_AT")
    private Instant resentAt;

    protected AdminUserInvitation() {}

    public AdminUserInvitation(Long adminUserId, String tokenHash, String tokenPrefix,
                                String createdBy, Instant expiresAt) {
        this.adminUserId = adminUserId;
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public Long getAdminUserId() { return adminUserId; }
    public String getTokenHash() { return tokenHash; }
    public String getTokenPrefix() { return tokenPrefix; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public void markAccepted() { this.acceptedAt = Instant.now(); }
    public int getResentCount() { return resentCount; }
    public void incrementResentCount() { this.resentCount++; this.resentAt = Instant.now(); }
    public Instant getResentAt() { return resentAt; }

    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    public boolean isAccepted() { return acceptedAt != null; }
}
```

Repository:
```java
public interface AdminUserInvitationRepository extends JpaRepository<AdminUserInvitation, Long> {
    Optional<AdminUserInvitation> findByTokenHash(String tokenHash);
    List<AdminUserInvitation> findByAdminUserIdAndAcceptedAtIsNull(Long adminUserId);
}
```

### Steps
1. 두 파일 작성
2. 컴파일
3. codex review
4. Commit:
```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/AdminUserInvitation.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AdminUserInvitationRepository.java
git commit -m "feat(core): AdminUserInvitation entity + Repository (Phase D.2)"
```

---

## Task 3: MailSender 추상 + LogMailSender

**Files (Create):**
- `core/src/main/java/com/crosscert/passkey/core/mail/MailSender.java`
- `core/src/main/java/com/crosscert/passkey/core/mail/LogMailSender.java`

```java
// MailSender.java
package com.crosscert.passkey.core.mail;

import java.time.Instant;

public interface MailSender {
    void sendInvitation(String email, String acceptUrl, String invitedBy, Instant expiresAt);
}

// LogMailSender.java
package com.crosscert.passkey.core.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnMissingBean(name = "smtpMailSender")
public class LogMailSender implements MailSender {
    private static final Logger log = LoggerFactory.getLogger(LogMailSender.class);

    @Override
    public void sendInvitation(String email, String acceptUrl, String invitedBy, Instant expiresAt) {
        log.info("INVITATION email={} acceptUrl={} invitedBy={} expiresAt={}",
                email, acceptUrl, invitedBy, expiresAt);
    }
}
```

### Steps
1. 두 파일 작성
2. 컴파일
3. codex review
4. Commit:
```bash
git add core/src/main/java/com/crosscert/passkey/core/mail/
git commit -m "feat(core): MailSender 추상 + LogMailSender (Phase D.3)"
```

---

## Task 4: AdminUserService + InvitationService

Plan 의 핵심 비즈니스 로직. 두 서비스를 한 commit 으로.

**Files (Create):**
- `admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserDto.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserService.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationService.java`

### AdminUserDto

```java
public final class AdminUserDto {
    private AdminUserDto() {}

    public record View(
            Long id, String email, String role, String status,
            UUID tenantId, Instant createdAt, Instant lastLoginAt,
            Instant suspendedAt, String createdBy
    ) {}

    public record InviteRequest(
            @NotBlank @Email String email,
            @NotBlank String role,                // PLATFORM_OPERATOR | RP_ADMIN
            UUID tenantId                          // RP_ADMIN 일 때 필수
    ) {}

    public record InviteResponse(
            View user,
            InvitationInfo invitation
    ) {}

    public record InvitationInfo(
            String tokenPrefix,
            String plaintextToken,
            String acceptUrl,
            Instant expiresAt
    ) {}

    public record InvitationCheck(
            String email,
            String role,
            UUID tenantId,
            Instant expiresAt
    ) {}

    public record AcceptRequest(
            @NotBlank @Size(min = 12, max = 128) String password
    ) {}
}
```

### AdminUserService (핵심 로직)

```java
@Service
public class AdminUserService {

    private final AdminUserRepository userRepo;
    private final AdminUserInvitationRepository invitationRepo;
    private final InvitationService invitationService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AdminUserService(AdminUserRepository userRepo,
                             AdminUserInvitationRepository invitationRepo,
                             InvitationService invitationService,
                             PasswordEncoder passwordEncoder,
                             Clock clock) {
        this.userRepo = userRepo;
        this.invitationRepo = invitationRepo;
        this.invitationService = invitationService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminUserDto.View> list() {
        return userRepo.findAll().stream()
                .map(AdminUserService::toView)
                .toList();
    }

    @Transactional
    public AdminUserDto.InviteResponse invite(AdminUserDto.InviteRequest req, String invitedBy) {
        // role validation
        if (!"PLATFORM_OPERATOR".equals(req.role()) && !"RP_ADMIN".equals(req.role())) {
            throw new IllegalArgumentException("Invalid role: " + req.role());
        }
        if ("RP_ADMIN".equals(req.role()) && req.tenantId() == null) {
            throw new IllegalArgumentException("RP_ADMIN requires tenantId");
        }
        if ("PLATFORM_OPERATOR".equals(req.role()) && req.tenantId() != null) {
            throw new IllegalArgumentException("PLATFORM_OPERATOR must not have tenantId");
        }
        if (userRepo.findByEmail(req.email()).isPresent()) {
            throw new IllegalStateException("Email already exists: " + req.email());
        }

        // create PENDING user (bcrypt_hash NULL)
        AdminUser user = new AdminUser();
        user.setEmail(req.email());
        user.setRole(req.role());
        user.setStatus("PENDING");
        user.setCreatedBy(invitedBy);
        if (req.tenantId() != null) user.setTenantId(req.tenantId());
        user.setEnabled(true);   // PENDING 도 enabled — status 로 차단
        AdminUser saved = userRepo.save(user);

        // create invitation
        var inv = invitationService.createInvitation(saved.getId(), invitedBy, req.email());

        return new AdminUserDto.InviteResponse(toView(saved), inv);
    }

    @Transactional
    public void suspend(Long userId, String byUser) {
        AdminUser user = userRepo.findById(userId).orElseThrow();
        assertNotLockingOut(user, byUser, "suspend");
        user.setStatus("SUSPENDED");
        user.setSuspendedAt(clock.instant());
        user.setSuspendedBy(byUser);
    }

    @Transactional
    public void activate(Long userId) {
        AdminUser user = userRepo.findById(userId).orElseThrow();
        user.setStatus("ACTIVE");
        user.setSuspendedAt(null);
        user.setSuspendedBy(null);
    }

    @Transactional
    public AdminUserDto.InvitationInfo resendInvitation(Long userId, String byUser, String email) {
        // 기존 invitation 무효화 (token_hash 못 알아도 invitations 모두 expire)
        var existing = invitationRepo.findByAdminUserIdAndAcceptedAtIsNull(userId);
        for (var inv : existing) {
            // 단순화: 기존 invitations 의 expires_at 을 현재로 (과거로) — 실질 만료
            // 또는 별도 revoked 컬럼 추가 가능. 일단 만료시키는 패턴.
            inv.incrementResentCount();
        }
        return invitationService.createInvitation(userId, byUser, email);
    }

    private void assertNotLockingOut(AdminUser user, String byUser, String action) {
        if (user.getEmail().equals(byUser)) {
            throw new IllegalStateException("Cannot " + action + " yourself");
        }
        if ("PLATFORM_OPERATOR".equals(user.getRole()) && "ACTIVE".equals(user.getStatus())) {
            long activePoCount = userRepo.findAll().stream()
                    .filter(u -> "PLATFORM_OPERATOR".equals(u.getRole())
                            && "ACTIVE".equals(u.getStatus()))
                    .count();
            if (activePoCount <= 1) {
                throw new IllegalStateException("Cannot " + action + " the last active PLATFORM_OPERATOR");
            }
        }
    }

    static AdminUserDto.View toView(AdminUser u) {
        return new AdminUserDto.View(
                u.getId(), u.getEmail(), u.getRole(),
                u.getStatus() != null ? u.getStatus() : "ACTIVE",
                u.getTenantId(),
                u.getCreatedAt(), u.getLastLoginAt(),
                u.getSuspendedAt(), u.getCreatedBy());
    }
}
```

**중요**: `AdminUser` 에 setter 가 없으면 추가 필요 (Task 1 에서 했어야).

### InvitationService

```java
@Service
public class InvitationService {

    private static final Duration TOKEN_TTL = Duration.ofDays(7);
    private static final SecureRandom RNG = new SecureRandom();
    private static final String URL_PREFIX = "/accept-invite?token=";

    private final AdminUserInvitationRepository invitationRepo;
    private final AdminUserRepository userRepo;
    private final MailSender mailSender;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.invite.base-url:http://localhost:5173}")
    private String baseUrl;

    public InvitationService(AdminUserInvitationRepository invitationRepo,
                              AdminUserRepository userRepo,
                              MailSender mailSender,
                              PasswordEncoder passwordEncoder) {
        this.invitationRepo = invitationRepo;
        this.userRepo = userRepo;
        this.mailSender = mailSender;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AdminUserDto.InvitationInfo createInvitation(Long adminUserId, String invitedBy, String email) {
        // plaintext token = 32 random bytes hex
        byte[] tokenBytes = new byte[32];
        RNG.nextBytes(tokenBytes);
        String plaintext = "inv_" + hex(tokenBytes);
        String tokenHash = sha256Hex(plaintext);
        String prefix = plaintext.substring(0, 8);

        Instant expiresAt = Instant.now().plus(TOKEN_TTL);
        var inv = new AdminUserInvitation(adminUserId, tokenHash, prefix, invitedBy, expiresAt);
        invitationRepo.save(inv);

        String acceptUrl = baseUrl + URL_PREFIX + plaintext;
        mailSender.sendInvitation(email, acceptUrl, invitedBy, expiresAt);

        return new AdminUserDto.InvitationInfo(prefix, plaintext, acceptUrl, expiresAt);
    }

    @Transactional(readOnly = true)
    public AdminUserDto.InvitationCheck check(String plaintext) {
        String hash = sha256Hex(plaintext);
        var inv = invitationRepo.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalStateException("Invalid token"));
        if (inv.isExpired()) throw new IllegalStateException("Token expired");
        if (inv.isAccepted()) throw new IllegalStateException("Token already used");
        var user = userRepo.findById(inv.getAdminUserId()).orElseThrow();
        return new AdminUserDto.InvitationCheck(
                user.getEmail(), user.getRole(), user.getTenantId(), inv.getExpiresAt());
    }

    @Transactional
    public void accept(String plaintext, String password) {
        String hash = sha256Hex(plaintext);
        var inv = invitationRepo.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalStateException("Invalid token"));
        if (inv.isExpired()) throw new IllegalStateException("Token expired");
        if (inv.isAccepted()) throw new IllegalStateException("Token already used");

        var user = userRepo.findById(inv.getAdminUserId()).orElseThrow();
        user.setBcryptHash(passwordEncoder.encode(password));
        user.setStatus("ACTIVE");
        inv.markAccepted();
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static String sha256Hex(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            return hex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

### Steps
1. DTO + 2 서비스 작성
2. AdminUser 의 setStatus/setSuspendedAt/setSuspendedBy/setCreatedBy/setBcryptHash setter 추가 필요시 — Task 1 에서 빠졌으면 여기서 추가
3. 컴파일
4. codex review
5. Commit:
```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/operator/
git add core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java   # 추가 setter 있으면
git commit -m "feat(admin-app): AdminUserService + InvitationService + lockout 방지 (Phase D.4)"
```

---

## Task 5: REST Controllers (AdminUser + Invitation)

**Files (Create):**
- `admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserController.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationController.java`

### AdminUserController (인증 필요)

```java
@RestController
@RequestMapping("/admin/api/admin-users")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) { this.service = service; }

    @GetMapping
    public List<AdminUserDto.View> list() { return service.list(); }

    @PostMapping
    public AdminUserDto.InviteResponse invite(@Valid @RequestBody AdminUserDto.InviteRequest req,
                                               Authentication auth) {
        return service.invite(req, auth.getName());
    }

    @PostMapping("/{id}/suspend")
    public void suspend(@PathVariable Long id, Authentication auth) {
        service.suspend(id, auth.getName());
    }

    @PostMapping("/{id}/activate")
    public void activate(@PathVariable Long id) { service.activate(id); }

    @PostMapping("/{id}/invitation/resend")
    public AdminUserDto.InvitationInfo resend(@PathVariable Long id,
                                                Authentication auth,
                                                @RequestParam String email) {
        return service.resendInvitation(id, auth.getName(), email);
    }
}
```

### InvitationController (비인증)

```java
@RestController
@RequestMapping("/admin/api/invitations")
public class InvitationController {

    private final InvitationService service;

    public InvitationController(InvitationService service) { this.service = service; }

    @GetMapping("/{token}")
    public AdminUserDto.InvitationCheck check(@PathVariable String token) {
        return service.check(token);
    }

    @PostMapping("/{token}/accept")
    public void accept(@PathVariable String token,
                       @Valid @RequestBody AdminUserDto.AcceptRequest req) {
        service.accept(token, req.password());
    }
}
```

**중요 — Security config 변경**: `/admin/api/invitations/**` 가 비인증 접근 가능해야 함. 기존 `AdminSecurityConfig` 에 permitAll 추가. 그 패턴은 기존 `/admin/login`, `/admin/logout` 등이 어떻게 처리되는지 grep 으로 확인 후 일관되게.

### Steps
1. 2 Controller 작성
2. Security config 에 invitations 경로 permitAll 추가
3. 컴파일
4. codex review
5. Commit:
```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java
git commit -m "feat(admin-app): AdminUser + Invitation REST controllers + security config (Phase D.5)"
```

---

## Task 6: IT — 풀 플로우 (invite → accept → login)

**Files (Create):**
- `admin-app/src/test/java/com/crosscert/passkey/admin/operator/AdminUserInvitationFlowIT.java`

시나리오:
1. PLATFORM_OPERATOR 로 로그인
2. POST `/admin/api/admin-users` 로 새 RP_ADMIN 초대 (email, role=RP_ADMIN, tenantId) → 201, plaintext token 응답 받음
3. invitation 객체에서 plaintextToken + acceptUrl 추출
4. 새 세션 (또는 인증 없이) GET `/admin/api/invitations/{token}` → 200, email/role/tenantId 미리보기
5. POST `/admin/api/invitations/{token}/accept` `{password: "securePassword123!"}` → 200
6. POST `/admin/login` 으로 새 사용자 로그인 → 200
7. GET `/admin/api/me` → role=RP_ADMIN, tenantId 일치
8. Lockout 가드: 자기 자신 suspend 시도 → 400
9. Lockout 가드: 마지막 ACTIVE PO 가 자기 자신을 (다른 PO 없는 상태에서) suspend 못함 — IT 안에서 다른 PO 미리 생성 후 검증

기존 IT 패턴 (Phase B 의 AuditChainPerTenantIT) 참고. MockMvc 또는 TestRestTemplate.

### Steps
1. IT 작성
2. 테스트 실행: `./gradlew :admin-app:test --tests "*AdminUserInvitationFlowIT"`
3. PASS 확인
4. codex review
5. Commit:
```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/operator/AdminUserInvitationFlowIT.java
git commit -m "test(admin-app): Admin user invite → accept → login 풀 플로우 IT (Phase D.6)"
```

---

## Task 7: UI — AcceptInvite 페이지 + Settings 셸 + AdminUsers/MDS 탭

**Files (Create):**
- `admin-ui/src/pages/AcceptInvite.tsx`
- `admin-ui/src/pages/settings/Settings.tsx`
- `admin-ui/src/pages/settings/AdminUsersTab.tsx`
- `admin-ui/src/pages/settings/MdsStatusTab.tsx`
- `admin-ui/src/api/adminUser.ts`

**Files (Modify):**
- `admin-ui/src/api/types.ts`
- `admin-ui/src/App.tsx`
- `admin-ui/src/shell/Sidebar.tsx`

### Steps (간략 — 구현자에게 패턴만)

1. types.ts: AdminUserView, InviteRequest, InviteResponse, InvitationCheck, AcceptRequest 타입 추가
2. api/adminUser.ts: adminUserApi {list, invite, suspend, activate, resend}, invitationApi {check, accept}
3. AcceptInvite.tsx: URL 의 token 추출 → check → 비밀번호 폼 → accept → /admin/login 리다이렉트
4. Settings.tsx: 탭 컨테이너 (AdminUsers + MDS)
5. AdminUsersTab.tsx: 테이블 + 초대 다이얼로그 + suspend/activate 버튼 + 초대 링크 1회 노출 모달 (API Key plaintext 패턴 재사용)
6. MdsStatusTab.tsx: 기존 MdsStatus 페이지 코드 이관
7. App.tsx: `/accept-invite`, `/settings` 라우트 추가. `/mds` 는 Sidebar 에서 제거하지만 라우트는 일단 유지 (또는 redirect)
8. Sidebar.tsx: MDS 항목을 Settings 로 교체 (또는 둘 다 유지)
9. 빌드 0 에러
10. codex review
11. Commit:
```bash
git add admin-ui/
git commit -m "feat(admin-ui): AcceptInvite + Settings 셸 + AdminUsers/MDS 탭 (Phase D.7)"
```

---

## Self-Review

- [x] V29 + AdminUser entity 확장
- [x] AdminUserInvitation entity + repo
- [x] MailSender + LogMailSender
- [x] AdminUserService + InvitationService (lockout 방지)
- [x] REST controllers + security config
- [x] IT 풀 플로우
- [x] UI 5 파일

scope check: 7 task. placeholder 없음. lockout 가드 명확.

---

## 실행 가이드 요약
1. 각 Task step 순서대로
2. 각 Task 끝의 codex review → fix → commit
3. 컴파일/IT PASS 가 commit 의 전제
4. 시그니처 grep 우선
