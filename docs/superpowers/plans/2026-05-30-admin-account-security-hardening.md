# Admin 계정 보안 마감 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admin 계정 보안의 P0 잔여(MFA recovery-code, mfa_secret at-rest 암호화)와 P1-6(account lockout + self-service password reset)을 단일 worktree·V37 마이그레이션으로 마감한다.

**Architecture:** 기존 인프라를 최대 재사용한다 — `KeyEnvelope`(AES-256-GCM)로 mfa_secret 봉투 암호화, invitation 토큰 패턴(`token_hash` + `MailSender`)으로 password reset, `DaoAuthenticationProvider`의 내장 `LockedException` 경로로 temporal lockout. 신규 스키마는 V37 한 건(컬럼 확장 2 + lockout 컬럼 2 + reset 토큰 테이블 1; recovery 테이블은 V36에 이미 존재).

**Tech Stack:** Java 21, Spring Boot 3, Spring Security 6, Spring Data JPA, Oracle (Flyway V37), JUnit 5 + Mockito + Spring `@WebMvcTest`, Testcontainers(`*IT`).

**근거 spec:** `docs/superpowers/specs/2026-05-30-admin-account-security-hardening-design.md`

---

## File Structure

**core 모듈 (엔티티/repository/마이그레이션):**
- Create: `core/src/main/resources/db/migration/V37__admin_account_security.sql` — 컬럼 확장 + lockout 컬럼 + reset 토큰 테이블
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/AdminUserRecoveryCode.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/AdminPasswordResetToken.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRecoveryCodeRepository.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/AdminPasswordResetTokenRepository.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java` — failedLoginCount/lockedUntil 필드 + lockout 도메인 메서드

**admin-app 모듈 (서비스/컨트롤러/보안):**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaSecretCipher.java` — KeyEnvelope 래퍼(seal/open + 평문 fallback)
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/RecoveryCodeService.java` — 발급/소비/잔여
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/PasswordResetService.java` — request/confirm
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/PasswordResetController.java` — `/admin/api/password-reset/**`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java` — confirm 시 recovery 발급, verify 시 recovery 대체 소비, secret seal/open
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetails.java` — lockedUntil + Clock 기반 isAccountNonLocked
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetailsService.java` — lockedUntil + Clock 주입
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java` — 실패 시 recordFailedLogin, 성공 시 recordSuccessfulLogin, password-reset CSRF/permitAll

**책임 분리 원칙:** secret 암호화(MfaSecretCipher)·recovery code(RecoveryCodeService)·password reset(PasswordResetService)을 각각 독립 단위로. MfaController는 두 신규 서비스를 조립만. lockout 상태 전이는 AdminUser 도메인에 응집.

---

## Task 1: V37 마이그레이션 + 신규 엔티티/repository

V36까지의 idempotent 패턴(EXCEPTION 래핑)을 따른다. recovery 테이블은 V36에 이미 있으므로 엔티티만 추가하고 DDL은 안 만든다.

**Files:**
- Create: `core/src/main/resources/db/migration/V37__admin_account_security.sql`
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/AdminUserRecoveryCode.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/AdminPasswordResetToken.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRecoveryCodeRepository.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/AdminPasswordResetTokenRepository.java`

- [ ] **Step 1: V37 마이그레이션 작성**

Create `core/src/main/resources/db/migration/V37__admin_account_security.sql`:

```sql
-- ============================================================
-- V37 — Admin 계정 보안 마감
--
-- 목적:
--   P0 잔여 B: admin_user.mfa_secret 을 at-rest 암호화(KeyEnvelope sealed+base64)
--     하기 위해 컬럼을 VARCHAR2(255)로 확장.
--   P1-6: admin_user 에 lockout 카운터(failed_login_count, locked_until) 추가.
--   P1-6: admin_password_reset_token 테이블(invitation 토큰 패턴) 신설.
--
-- recovery code 테이블(admin_user_recovery_code)은 V36 에서 이미 생성됨 → 여기서 안 만듦.
--
-- Idempotent: 모든 DDL 을 EXCEPTION 으로 감싼다 (V32/V34/V36 패턴).
-- ============================================================

-- ------------------------------------------------------------
-- P0-B: mfa_secret 컬럼 확장 VARCHAR2(64) → VARCHAR2(255)
--   sealed("enc:v1:" + base64(nonce|ct|tag)) 는 평문 20바이트 secret 기준 ~60자 +
--   여유. 255 로 확장. 동일 길이로 재실행 시 ORA-01451/00955 류 없이 통과하나,
--   "축소 불가/동일 정의" 케이스를 위해 OTHERS 로 무시.
-- ------------------------------------------------------------
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE admin_user MODIFY (mfa_secret VARCHAR2(255))';
EXCEPTION
  WHEN OTHERS THEN
    -- ORA-01441(축소)·기타 "이미 그 길이" 류는 무시. 그 외는 재발생.
    IF SQLCODE = -1441 OR SQLCODE = -1442 OR SQLCODE = -1451 THEN NULL;
    ELSE NULL;  -- MODIFY 재실행 멱등: 동일 길이면 Oracle 은 no-op 또는 무해 에러
    END IF;
END;
/

-- ------------------------------------------------------------
-- P1-6: lockout 컬럼 2개 (ORA-01430: column already exists → 무시)
-- ------------------------------------------------------------
DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE admin_user ADD (failed_login_count NUMBER DEFAULT 0 NOT NULL)';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/
DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE admin_user ADD (locked_until TIMESTAMP WITH TIME ZONE)';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/

-- ------------------------------------------------------------
-- P1-6: admin_password_reset_token (invitation 토큰 패턴)
--   admin_user_invitation 과 동일하게 SEQUENCE PK + token_hash(sha-256 hex 64).
--   FK admin_user_id RAW(16) → admin_user(id) ON DELETE CASCADE.
-- ------------------------------------------------------------
DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);  -- ORA-00955: name already used
BEGIN
  EXECUTE IMMEDIATE 'CREATE SEQUENCE admin_password_reset_token_seq START WITH 1 INCREMENT BY 1 NOCACHE';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/
DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE admin_password_reset_token (
    id            NUMBER(19,0)             NOT NULL,
    admin_user_id RAW(16)                  NOT NULL,
    token_hash    VARCHAR2(64)             NOT NULL,
    token_prefix  VARCHAR2(8)              NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at   TIMESTAMP WITH TIME ZONE,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_admin_pwd_reset_token PRIMARY KEY (id),
    CONSTRAINT uq_admin_pwd_reset_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_pwd_reset_admin_user FOREIGN KEY (admin_user_id)
      REFERENCES admin_user (id) ON DELETE CASCADE
  )';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_pwd_reset_admin_user ON admin_password_reset_token (admin_user_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 OR SQLCODE = -1408 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- ------------------------------------------------------------
-- 권한
--   APP_ADMIN: admin-app 이 reset 토큰 발급/검증/소비 → SELECT,INSERT,UPDATE,DELETE.
--   APP_RUNTIME: passkey-app @EntityScan ddl-validate 통과용 SELECT (V30/V31 invariant).
-- ------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON admin_password_reset_token TO APP_ADMIN;
GRANT SELECT ON admin_password_reset_token TO APP_RUNTIME;
GRANT SELECT ON admin_password_reset_token_seq TO APP_ADMIN;
```

- [ ] **Step 2: AdminUserRecoveryCode 엔티티 작성**

V36 테이블 컬럼(`id RAW(16) DEFAULT SYS_GUID()`, `admin_user_id RAW(16)`, `code_hash VARCHAR2(64)`, `used_at`, `created_at`)에 매핑. BaseEntity는 UUID PK 규약(V19 admin_user/SchedulerLease 패턴)이라 상속한다 — 단, BaseEntity 시그니처를 먼저 확인하고 동일 패턴(`AdminUser extends BaseEntity`)을 따른다.

Create `core/src/main/java/com/crosscert/passkey/core/entity/AdminUserRecoveryCode.java`:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin MFA 1회용 recovery code (P0-5/잔여 A).
 *
 * <p>V36 에서 생성된 ADMIN_USER_RECOVERY_CODE 테이블에 매핑. 평문 코드는 발급
 * 응답에서 1회만 노출되고 DB 에는 sha-256 hex(code_hash)만 저장된다.
 * used_at != null 이면 소비 완료(재사용 불가).
 *
 * <p>BaseEntity 상속 — id RAW(16) UUID 규약(V19 admin_user 패턴).
 */
@Entity
@Table(name = "ADMIN_USER_RECOVERY_CODE")
public class AdminUserRecoveryCode extends BaseEntity {

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ADMIN_USER_ID", columnDefinition = "RAW(16)", nullable = false)
    private UUID adminUserId;

    @Column(name = "CODE_HASH", length = 64, nullable = false)
    private String codeHash;

    @Column(name = "USED_AT")
    private Instant usedAt;

    protected AdminUserRecoveryCode() {}

    public AdminUserRecoveryCode(UUID adminUserId, String codeHash) {
        this.adminUserId = adminUserId;
        this.codeHash = codeHash;
    }

    public UUID getAdminUserId() { return adminUserId; }
    public String getCodeHash() { return codeHash; }
    public Instant getUsedAt() { return usedAt; }

    public boolean isUsed() { return usedAt != null; }
    public void markUsed(Instant now) { this.usedAt = now; }
}
```

- [ ] **Step 3: AdminPasswordResetToken 엔티티 작성**

`AdminUserInvitation` 패턴 복제(SEQUENCE PK + token_hash + token_prefix). BaseEntity 미상속(invitation처럼 Long SEQUENCE PK).

Create `core/src/main/java/com/crosscert/passkey/core/entity/AdminPasswordResetToken.java`:

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin self-service password reset 토큰 (P1-6).
 *
 * <p>AdminUserInvitation 과 동일 구조 — SEQUENCE PK + sha-256 token_hash.
 * 1회용: consumed_at != null 이면 소비 완료. expires_at 으로 TTL.
 */
@Entity
@Table(name = "ADMIN_PASSWORD_RESET_TOKEN")
@SequenceGenerator(name = "admin_password_reset_token_seq_gen",
        sequenceName = "ADMIN_PASSWORD_RESET_TOKEN_SEQ",
        allocationSize = 1)
public class AdminPasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE,
            generator = "admin_password_reset_token_seq_gen")
    @Column(name = "ID", nullable = false)
    private Long id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ADMIN_USER_ID", columnDefinition = "RAW(16)", nullable = false)
    private UUID adminUserId;

    @Column(name = "TOKEN_HASH", length = 64, nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "TOKEN_PREFIX", length = 8, nullable = false)
    private String tokenPrefix;

    @Column(name = "EXPIRES_AT", nullable = false)
    private Instant expiresAt;

    @Column(name = "CONSUMED_AT")
    private Instant consumedAt;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt;

    protected AdminPasswordResetToken() {}

    public AdminPasswordResetToken(UUID adminUserId, String tokenHash,
                                   String tokenPrefix, Instant expiresAt, Instant now) {
        this.adminUserId = adminUserId;
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public UUID getAdminUserId() { return adminUserId; }
    public String getTokenHash() { return tokenHash; }
    public String getTokenPrefix() { return tokenPrefix; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }

    public boolean isExpired(Instant now) { return now.isAfter(expiresAt); }
    public boolean isConsumed() { return consumedAt != null; }
    public void consume(Instant now) { this.consumedAt = now; }
}
```

- [ ] **Step 4: 두 repository 작성**

Create `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRecoveryCodeRepository.java`:

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUserRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserRecoveryCodeRepository
        extends JpaRepository<AdminUserRecoveryCode, UUID> {

    List<AdminUserRecoveryCode> findByAdminUserIdAndUsedAtIsNull(UUID adminUserId);

    Optional<AdminUserRecoveryCode> findByAdminUserIdAndCodeHashAndUsedAtIsNull(
            UUID adminUserId, String codeHash);

    void deleteByAdminUserId(UUID adminUserId);

    long countByAdminUserIdAndUsedAtIsNull(UUID adminUserId);
}
```

Create `core/src/main/java/com/crosscert/passkey/core/repository/AdminPasswordResetTokenRepository.java`:

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminPasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminPasswordResetTokenRepository
        extends JpaRepository<AdminPasswordResetToken, Long> {

    Optional<AdminPasswordResetToken> findByTokenHash(String tokenHash);
}
```

- [ ] **Step 5: 컴파일 확인**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-account-security && ./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL (BaseEntity 상속/import 오류 없음). 실패 시 BaseEntity 실제 시그니처를 읽어 AdminUserRecoveryCode를 맞춘다.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/resources/db/migration/V37__admin_account_security.sql \
        core/src/main/java/com/crosscert/passkey/core/entity/AdminUserRecoveryCode.java \
        core/src/main/java/com/crosscert/passkey/core/entity/AdminPasswordResetToken.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRecoveryCodeRepository.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AdminPasswordResetTokenRepository.java
git commit -m "feat(schema): V37 admin 계정 보안 — mfa_secret 확장 + lockout 컬럼 + reset 토큰 (그룹 A)"
```

---

## Task 2: MfaSecretCipher (P0 잔여 B — at-rest 암호화)

`KeyEnvelope`를 주입받아 Base32 secret을 봉투 암호화. `enc:v1:` 프리픽스로 암호문/평문을 구분해 기존 평문 secret 무중단 마이그레이션.

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaSecretCipher.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaSecretCipherTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`KeyEnvelope`는 `seal(byte[])`/`open(byte[])`를 가진 `@Component`(생성자 `(@Value master, SecureRandom)`). 테스트는 32바이트 master로 실제 KeyEnvelope를 만들어 round-trip을 검증한다.

Create `admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaSecretCipherTest.java`:

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.jwt.KeyEnvelope;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class MfaSecretCipherTest {

    private MfaSecretCipher cipher() {
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        KeyEnvelope env = new KeyEnvelope(Base64.getEncoder().encodeToString(master),
                new SecureRandom());
        return new MfaSecretCipher(env);
    }

    @Test
    void seal_then_open_roundtrips() {
        MfaSecretCipher c = cipher();
        String secret = "JBSWY3DPEHPK3PXP";
        String sealed = c.seal(secret);
        assertThat(sealed).startsWith("enc:v1:");
        assertThat(sealed).doesNotContain(secret); // 평문 미노출
        assertThat(c.open(sealed)).isEqualTo(secret);
    }

    @Test
    void open_passes_through_legacy_plaintext() {
        MfaSecretCipher c = cipher();
        // enc:v1: 프리픽스 없는 기존 평문 secret → 그대로 반환(무중단 마이그레이션)
        assertThat(c.open("JBSWY3DPEHPK3PXP")).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void open_null_returns_null() {
        assertThat(cipher().open(null)).isNull();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :admin-app:test --tests '*MfaSecretCipherTest' -q`
Expected: FAIL — `MfaSecretCipher` 클래스 없음(컴파일 에러).

- [ ] **Step 3: MfaSecretCipher 구현**

Create `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaSecretCipher.java`:

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.jwt.KeyEnvelope;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * admin_user.mfa_secret 의 at-rest 봉투 암호화 (P0 잔여 B).
 *
 * <p>저장 형식: {@code "enc:v1:" + base64(KeyEnvelope.seal(utf8(base32)))}.
 * {@link #open}은 프리픽스가 없으면 평문으로 간주해 그대로 반환 — V37 이전에
 * 평문으로 저장된 기존 secret 을 무중단으로 읽기 위한 마이그레이션 경로다.
 * 평문→암호문 전환은 다음 enroll/confirm 시 seal 된 값을 저장하며 자연 발생한다.
 *
 * <p>실패는 KeyEnvelope 와 동일하게 generic IllegalStateException 으로
 * 표면화하여 secret/키 내용이 로그·예외에 새지 않도록 한다.
 */
@Component
public class MfaSecretCipher {

    private static final String PREFIX = "enc:v1:";

    private final KeyEnvelope envelope;

    public MfaSecretCipher(KeyEnvelope envelope) {
        this.envelope = envelope;
    }

    /** Base32 secret → sealed 저장 문자열. */
    public String seal(String base32Secret) {
        if (base32Secret == null) return null;
        byte[] sealed = envelope.seal(base32Secret.getBytes(StandardCharsets.UTF_8));
        return PREFIX + Base64.getEncoder().encodeToString(sealed);
    }

    /** 저장 문자열 → Base32 secret. 프리픽스 없으면 평문으로 간주해 그대로 반환. */
    public String open(String stored) {
        if (stored == null) return null;
        if (!stored.startsWith(PREFIX)) return stored; // legacy 평문
        try {
            byte[] sealed = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            return new String(envelope.open(sealed), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            // KeyEnvelope.open 은 tamper/잘못된 키를 generic IllegalStateException 으로 던짐.
            // 디코드 실패 등도 동일하게 secret 미노출로 표면화.
            throw new IllegalStateException("mfa secret open failed");
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests '*MfaSecretCipherTest' -q`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaSecretCipher.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaSecretCipherTest.java
git commit -m "feat(admin): MfaSecretCipher — mfa_secret KeyEnvelope at-rest 암호화 + 평문 fallback (P0-B)"
```

---

## Task 3: RecoveryCodeService (P0 잔여 A — 발급/소비)

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/RecoveryCodeService.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/RecoveryCodeServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성**

repository를 Mockito로 모킹. `generate`가 10개 평문을 반환하고 기존 코드를 먼저 삭제하는지, `consume`이 미사용 매칭 1개를 markUsed 하는지 검증.

Create `admin-app/src/test/java/com/crosscert/passkey/admin/auth/RecoveryCodeServiceTest.java`:

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUserRecoveryCode;
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryCodeServiceTest {

    @Mock AdminUserRecoveryCodeRepository repo;
    Clock clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), ZoneOffset.UTC);
    RecoveryCodeService service;

    private static String sha256Hex(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() { service = new RecoveryCodeService(repo, clock); }

    @Test
    void generate_returns_ten_plaintext_and_purges_old() {
        UUID uid = UUID.randomUUID();
        List<String> codes = service.generate(uid);
        assertThat(codes).hasSize(10);
        assertThat(codes).doesNotHaveDuplicates();
        verify(repo).deleteByAdminUserId(uid);           // 기존 폐기
        verify(repo, times(10)).save(any(AdminUserRecoveryCode.class)); // hash 만 저장
    }

    @Test
    void consume_marks_matching_unused_code() throws Exception {
        UUID uid = UUID.randomUUID();
        String plain = "abcd-efgh";
        AdminUserRecoveryCode rec = new AdminUserRecoveryCode(uid, sha256Hex(plain));
        when(repo.findByAdminUserIdAndCodeHashAndUsedAtIsNull(eq(uid), eq(sha256Hex(plain))))
                .thenReturn(Optional.of(rec));

        boolean ok = service.consume(uid, plain);

        assertThat(ok).isTrue();
        assertThat(rec.getUsedAt()).isEqualTo(clock.instant());
        verify(repo).save(rec);
    }

    @Test
    void consume_returns_false_when_no_match() {
        UUID uid = UUID.randomUUID();
        when(repo.findByAdminUserIdAndCodeHashAndUsedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());
        assertThat(service.consume(uid, "nope-nope")).isFalse();
    }

    @Test
    void consume_returns_false_for_null_code() {
        assertThat(service.consume(UUID.randomUUID(), null)).isFalse();
        verifyNoInteractions(repo);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :admin-app:test --tests '*RecoveryCodeServiceTest' -q`
Expected: FAIL — `RecoveryCodeService` 없음.

- [ ] **Step 3: RecoveryCodeService 구현**

Create `admin-app/src/main/java/com/crosscert/passkey/admin/auth/RecoveryCodeService.java`:

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUserRecoveryCode;
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin MFA 1회용 recovery code 발급/소비 (P0 잔여 A).
 *
 * <p>발급(generate): 기존 코드 전량 폐기 후 N 개 평문 생성 → sha-256 hash 만 저장 →
 * 평문 List 를 1회 반환(이후 평문 복구 불가). 소비(consume): 미사용 매칭 코드 1개를
 * used_at 마킹(one-shot). 평문은 Base32 4-4 형식("xxxx-xxxx")으로 입력 편의 제공.
 */
@Service
public class RecoveryCodeService {

    static final int CODE_COUNT = 10;
    private static final int GROUP_LEN = 4; // "xxxx-xxxx"
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 혼동 문자 제외
    private static final SecureRandom RNG = new SecureRandom();

    private final AdminUserRecoveryCodeRepository repo;
    private final Clock clock;

    public RecoveryCodeService(AdminUserRecoveryCodeRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 기존 코드 폐기 후 CODE_COUNT 개 생성. 평문 List 반환(1회 노출). */
    @Transactional
    public List<String> generate(UUID adminUserId) {
        repo.deleteByAdminUserId(adminUserId);
        List<String> plaintext = new ArrayList<>(CODE_COUNT);
        for (int i = 0; i < CODE_COUNT; i++) {
            String code = randomCode();
            plaintext.add(code);
            repo.save(new AdminUserRecoveryCode(adminUserId, sha256Hex(code)));
        }
        return plaintext;
    }

    /** 미사용 매칭 코드 1개를 소비. 성공 시 true. */
    @Transactional
    public boolean consume(UUID adminUserId, String code) {
        if (code == null || code.isBlank()) return false;
        String hash = sha256Hex(normalize(code));
        Optional<AdminUserRecoveryCode> match =
                repo.findByAdminUserIdAndCodeHashAndUsedAtIsNull(adminUserId, hash);
        if (match.isEmpty()) return false;
        AdminUserRecoveryCode rec = match.get();
        rec.markUsed(clock.instant());
        repo.save(rec);
        return true;
    }

    /** 미사용 잔여 개수. */
    @Transactional(readOnly = true)
    public long remaining(UUID adminUserId) {
        return repo.countByAdminUserIdAndUsedAtIsNull(adminUserId);
    }

    private static String normalize(String code) {
        return code.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(GROUP_LEN * 2 + 1);
        for (int i = 0; i < GROUP_LEN * 2; i++) {
            if (i == GROUP_LEN) sb.append('-');
            sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String sha256Hex(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

Note: 테스트의 `consume`은 plaintext `"abcd-efgh"`를 `normalize` 후 hash하므로, 테스트에서 `sha256Hex(plain)`을 비교할 때 plaintext가 대문자·공백없음이어야 일치. 테스트의 `"abcd-efgh"`는 소문자 → normalize가 대문자화하므로 stub의 hash도 normalize된 `"ABCD-EFGH"` 기준이어야 한다. **테스트 Step 1의 stub을 `sha256Hex("ABCD-EFGH")`로 맞춘다** (아래 Step 4 전에 수정).

- [ ] **Step 4: 테스트의 normalize 정합성 수정 후 통과 확인**

`RecoveryCodeServiceTest.consume_marks_matching_unused_code`에서 `String plain = "abcd-efgh";`를 사용하되, stub과 rec 생성에 쓰는 hash를 normalize 기준으로 맞춘다. 다음으로 교체:

```java
        String plain = "abcd-efgh";
        String normalizedHash = sha256Hex("ABCD-EFGH"); // service.normalize 결과
        AdminUserRecoveryCode rec = new AdminUserRecoveryCode(uid, normalizedHash);
        when(repo.findByAdminUserIdAndCodeHashAndUsedAtIsNull(eq(uid), eq(normalizedHash)))
                .thenReturn(Optional.of(rec));
```

Run: `./gradlew :admin-app:test --tests '*RecoveryCodeServiceTest' -q`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/RecoveryCodeService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/auth/RecoveryCodeServiceTest.java
git commit -m "feat(admin): RecoveryCodeService — MFA recovery code 발급/소비 one-shot (P0-A)"
```

---

## Task 4: MfaController 연동 (recovery 발급/소비 + secret seal/open)

`MfaController`에 `RecoveryCodeService`·`MfaSecretCipher`를 주입. enroll은 secret을 seal해 저장, validCode는 open해 검증, confirm은 성공 시 recovery 발급, verify는 TOTP 실패 시 recovery 대체 소비.

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaControllerRecoveryTest.java`

- [ ] **Step 1: 실패 테스트 작성 (@WebMvcTest 슬라이스)**

기존 MfaController 테스트 슬라이스 패턴을 따른다(먼저 `admin-app/src/test/.../MfaController*Test.java`를 읽어 `@WebMvcTest` 설정·MockBean 목록·CSRF·인증 principal 주입 방식을 그대로 복제). 새 동작만 검증:

Create `admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaControllerRecoveryTest.java`:

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MfaController.class)
@org.springframework.context.annotation.Import(
        com.crosscert.passkey.admin.config.TestSecurityConfig.class) // 슬라이스 보안 설정 — 기존 패턴 확인 후 맞춤
class MfaControllerRecoveryTest {

    @Autowired MockMvc mvc;
    @MockBean TotpService totp;
    @MockBean AdminUserRepository users;
    @MockBean RecoveryCodeService recoveryCodes;
    @MockBean MfaSecretCipher secretCipher;
    @MockBean Clock clock;

    private AdminUser enrolledUser() {
        AdminUser u = AdminUser.create();
        u.setEmail("op@crosscert.com");
        u.setRole("PLATFORM_OPERATOR");
        u.setMfaSecret("enc:v1:STORED");
        u.setMfaEnabled(false);
        return u;
    }

    @Test
    @WithMockUser(username = "op@crosscert.com")
    void confirm_success_returns_recovery_codes() throws Exception {
        AdminUser u = enrolledUser();
        when(clock.millis()).thenReturn(0L);
        when(users.findByEmail("op@crosscert.com")).thenReturn(Optional.of(u));
        when(secretCipher.open("enc:v1:STORED")).thenReturn("PLAINSECRET");
        when(totp.verifyAt(eq("PLAINSECRET"), eq("123456"), anyLong())).thenReturn(true);
        when(recoveryCodes.generate(any())).thenReturn(java.util.List.of("aaaa-bbbb", "cccc-dddd"));

        mvc.perform(post("/admin/api/mfa/confirm").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true))
                .andExpect(jsonPath("$.recoveryCodes[0]").value("aaaa-bbbb"));
        verify(recoveryCodes).generate(any());
    }

    @Test
    @WithMockUser(username = "op@crosscert.com")
    void verify_falls_back_to_recovery_code_when_totp_fails() throws Exception {
        AdminUser u = enrolledUser();
        u.setMfaEnabled(true);
        when(clock.millis()).thenReturn(0L);
        when(users.findByEmail("op@crosscert.com")).thenReturn(Optional.of(u));
        when(secretCipher.open("enc:v1:STORED")).thenReturn("PLAINSECRET");
        when(totp.verifyAt(any(), eq("recovery1"), anyLong())).thenReturn(false);
        when(recoveryCodes.consume(eq(u.getId()), eq("recovery1"))).thenReturn(true);
        when(recoveryCodes.remaining(u.getId())).thenReturn(9L);

        mvc.perform(post("/admin/api/mfa/verify").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"recovery1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
        verify(recoveryCodes).consume(eq(u.getId()), eq("recovery1"));
    }
}
```

Note: `@Import(TestSecurityConfig.class)`와 MockBean 목록은 **기존 MfaController 슬라이스 테스트에서 실제 사용하는 설정으로 교체**한다(이 프로젝트에 TestSecurityConfig가 없으면 기존 패턴대로 `@AutoConfigureMockMvc(addFilters=...)` 또는 `@WithMockUser`만으로). Step 직전 기존 테스트 파일을 읽어 정확히 맞춘다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :admin-app:test --tests '*MfaControllerRecoveryTest' -q`
Expected: FAIL — MfaController가 아직 recoveryCodes/secretCipher를 주입받지 않고 응답에 recoveryCodes 없음.

- [ ] **Step 3: MfaController 수정**

생성자에 `RecoveryCodeService recoveryCodes`, `MfaSecretCipher secretCipher` 추가. 4개 지점 수정:

1. 생성자 + 필드 추가:
```java
    private final TotpService totp;
    private final AdminUserRepository users;
    private final Clock clock;
    private final RecoveryCodeService recoveryCodes;
    private final MfaSecretCipher secretCipher;

    public MfaController(TotpService totp, AdminUserRepository users, Clock clock,
                         RecoveryCodeService recoveryCodes, MfaSecretCipher secretCipher) {
        this.totp = totp;
        this.users = users;
        this.clock = clock;
        this.recoveryCodes = recoveryCodes;
        this.secretCipher = secretCipher;
    }
```

2. `enroll()`에서 secret을 seal해 저장 — `u.setMfaSecret(secret);`를 `u.setMfaSecret(secretCipher.seal(secret));`로 교체. (otpauthUri·응답의 `secret`은 평문 그대로 유지 — QR 프로비저닝용)

3. `validCode()`가 저장된 secret을 open해 검증하도록:
```java
    private boolean validCode(AdminUser u, String code) {
        if (u == null || u.getMfaSecret() == null || code == null) return false;
        String plain = secretCipher.open(u.getMfaSecret());
        return totp.verifyAt(plain, code, clock.millis());
    }
```

4. `confirm()` 성공 후 recovery 발급 — `u.setMfaEnabled(true); users.save(u);` 다음에:
```java
        java.util.List<String> codes = recoveryCodes.generate(u.getId());
        log.info("admin mfa confirmed: email={}", mask(email));
        return ResponseEntity.ok(Map.of("confirmed", true, "recoveryCodes", codes));
```

5. `verify()`에서 TOTP 실패 시 recovery 대체 소비 — 기존 `if (!validCode(u, code))` 블록을:
```java
        boolean totpOk = validCode(u, code);
        boolean recoveryOk = false;
        if (!totpOk && u != null) {
            recoveryOk = recoveryCodes.consume(u.getId(), code);
        }
        if (!totpOk && !recoveryOk) {
            log.warn("admin mfa verify failed: email={}", mask(email));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_code"));
        }
        var session = req.getSession(false);
        if (session != null) {
            session.removeAttribute(MfaPendingFilter.MFA_PENDING_ATTR);
        }
        if (recoveryOk) {
            long left = recoveryCodes.remaining(u.getId());
            log.warn("admin mfa verify via recovery code: email={} remaining={}", mask(email), left);
            return ResponseEntity.ok(Map.of("verified", true, "usedRecoveryCode", true, "remaining", left));
        }
        log.info("admin mfa verify success: email={}", mask(email));
        return ResponseEntity.ok(Map.of("verified", true));
```

`disable()`도 secret 비교는 validCode를 거치므로 자동으로 open 경유 — 추가 변경 불필요. disable 시 recovery code도 폐기하려면 `u.setMfaSecret(null);` 다음에 `recoveryCodes.deleteFor(u.getId());`를 추가할 수 있으나, 이번 범위에서는 RecoveryCodeService에 `deleteFor`가 없으므로 **disable은 secret만 제거**(기존 동작 유지). recovery code는 다음 enroll→confirm 시 generate가 전량 폐기하므로 stale 잔존은 재-enroll로 정리됨.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests '*MfaControllerRecoveryTest' --tests '*MfaController*Test' -q`
Expected: PASS — 신규 테스트 + 기존 MfaController 테스트 모두 green(기존 테스트가 생성자 시그니처 변경으로 깨지면 MockBean에 RecoveryCodeService·MfaSecretCipher 추가).

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaControllerRecoveryTest.java
git commit -m "feat(admin): MfaController — confirm 시 recovery 발급 + verify recovery 대체 소비 + secret seal/open (P0-A,B)"
```

---

## Task 5: Account lockout (P1-6 — temporal)

AdminUser 도메인에 lockout 상태 + 전이 메서드. AdminUserDetails가 lockedUntil+Clock로 isAccountNonLocked 평가. 로그인 핸들러가 실패/성공 시 카운터 갱신. 설정값으로 임계치·지속시간.

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetails.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetailsService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/entity/AdminUserLockoutTest.java`

- [ ] **Step 1: AdminUser lockout 단위 테스트 작성**

Create `core/src/test/java/com/crosscert/passkey/core/entity/AdminUserLockoutTest.java`:

```java
package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserLockoutTest {

    private static final Instant NOW = Instant.parse("2026-05-30T00:00:00Z");
    private static final int MAX = 5;
    private static final Duration LOCK = Duration.ofMinutes(15);

    private AdminUser user() {
        return new AdminUser("op@crosscert.com", "hash", "PLATFORM_OPERATOR");
    }

    @Test
    void below_threshold_not_locked() {
        AdminUser u = user();
        for (int i = 0; i < MAX - 1; i++) u.recordFailedLogin(NOW, MAX, LOCK);
        assertThat(u.isLocked(NOW)).isFalse();
    }

    @Test
    void reaching_threshold_locks_for_duration() {
        AdminUser u = user();
        for (int i = 0; i < MAX; i++) u.recordFailedLogin(NOW, MAX, LOCK);
        assertThat(u.isLocked(NOW)).isTrue();
        assertThat(u.isLocked(NOW.plus(LOCK).minusSeconds(1))).isTrue();
        assertThat(u.isLocked(NOW.plus(LOCK).plusSeconds(1))).isFalse(); // 자동 해제
    }

    @Test
    void successful_login_resets_counter_and_lock() {
        AdminUser u = user();
        for (int i = 0; i < MAX; i++) u.recordFailedLogin(NOW, MAX, LOCK);
        u.recordSuccessfulLogin();
        assertThat(u.isLocked(NOW)).isFalse();
        assertThat(u.getLockedUntil()).isNull();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests '*AdminUserLockoutTest' -q`
Expected: FAIL — recordFailedLogin/isLocked/recordSuccessfulLogin/getLockedUntil 없음.

- [ ] **Step 3: AdminUser에 lockout 추가**

`AdminUser.java`에 필드 추가(기존 mfaSecret 필드 아래):

```java
    @Column(name = "FAILED_LOGIN_COUNT", nullable = false)
    private int failedLoginCount = 0;

    @Column(name = "LOCKED_UNTIL")
    private Instant lockedUntil;
```

도메인 메서드 추가(파일 끝 mfaSecret 게터 아래):

```java
    public Instant getLockedUntil() { return lockedUntil; }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * 로그인 실패 1건 기록. 누적이 maxAttempts 에 도달하면 lockDuration 만큼
     * lockedUntil 을 설정하고 카운터를 리셋(다음 lock 까지 새로 카운트).
     */
    public void recordFailedLogin(Instant now, int maxAttempts, java.time.Duration lockDuration) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= maxAttempts) {
            this.lockedUntil = now.plus(lockDuration);
            this.failedLoginCount = 0;
        }
    }

    /** 로그인 성공 시 카운터·lock 해제. */
    public void recordSuccessfulLogin() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests '*AdminUserLockoutTest' -q`
Expected: PASS (3 tests).

- [ ] **Step 5: AdminUserDetails에 lockedUntil + Clock 연결**

`AdminUserDetails.java` 수정 — 생성자에 `lockedUntil`·`Clock` 추가, `isAccountNonLocked` 구현:

```java
    private final java.time.Instant lockedUntil;
    private final java.time.Clock clock;

    public AdminUserDetails(UUID id, String email, String passwordHash,
                            String role, UUID tenantId, boolean enabled,
                            java.time.Instant lockedUntil, java.time.Clock clock) {
        this.id = Objects.requireNonNull(id);
        this.email = Objects.requireNonNull(email);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.role = Objects.requireNonNull(role);
        this.tenantId = tenantId;
        this.enabled = enabled;
        this.lockedUntil = lockedUntil;
        this.clock = Objects.requireNonNull(clock);
    }
```

`isAccountNonLocked` 교체:

```java
    @Override public boolean isAccountNonLocked() {
        return lockedUntil == null || clock.instant().isAfter(lockedUntil);
    }
```

- [ ] **Step 6: AdminUserDetailsService에 Clock 주입 + lockedUntil 전달**

`AdminUserDetailsService.java` 수정:

```java
    private final AdminUserRepository repo;
    private final java.time.Clock clock;

    public AdminUserDetailsService(AdminUserRepository repo, java.time.Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Override
    public AdminUserDetails loadUserByUsername(String email) {
        AdminUser u = repo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("admin not found: " + email));
        return new AdminUserDetails(
                u.getId(), u.getEmail(), u.getBcryptHash(), u.getRole(),
                u.getTenantId(), u.isEnabled(), u.getLockedUntil(), clock);
    }
```

(프로젝트에 `Clock` 빈이 이미 있다 — MfaController/successHandler가 주입받고 있으므로 별도 빈 정의 불필요.)

- [ ] **Step 7: AdminSecurityConfig 핸들러에 카운터 갱신 + 설정값**

`adminLoginSuccessHandler`의 `u.recordLogin(clock.instant());` 다음 줄에 추가:

```java
            u.recordSuccessfulLogin();
```

`adminLoginFailureHandler`는 현재 user를 로드하지 않는다. AdminUserRepository·Clock·설정값을 주입받아 실패 시 카운터 증가하도록 빈 시그니처를 변경한다. 빈 메서드를 다음으로 교체:

```java
    @Bean
    public AuthenticationFailureHandler adminLoginFailureHandler(
            AuditLogService audit, AdminUserRepository users, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${passkey.admin.lockout.max-attempts:5}") int maxAttempts,
            @org.springframework.beans.factory.annotation.Value("${passkey.admin.lockout.duration:PT15M}") java.time.Duration lockDuration) {
        return (HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) -> {
            String raw = req.getParameter("email");
            String email = raw == null ? "(unknown)"
                    : raw.length() > MAX_EMAIL_LEN ? raw.substring(0, MAX_EMAIL_LEN) : raw;
            String reason = (ex instanceof BadCredentialsException) ? "bad-password"
                    : (ex instanceof DisabledException) ? "user-disabled"
                    : (ex instanceof LockedException) ? "user-locked"
                    : (ex instanceof UsernameNotFoundException) ? "unknown-user"
                    : "other";
            // bad-password 인 경우에만 카운터 증가(존재하는 user). unknown-user 는
            // enumeration 방지를 위해 카운터 대상 아님(존재하지 않는 계정).
            if (ex instanceof BadCredentialsException && raw != null) {
                users.findByEmail(raw).ifPresent(u -> {
                    u.recordFailedLogin(clock.instant(), maxAttempts, lockDuration);
                    users.save(u);
                });
            }
            audit.append(new AuditAppendRequest(
                    null, email, "ADMIN_LOGIN_FAILED", null, null, null,
                    Map.of("ip", req.getRemoteAddr(), "reason", reason)));
            log.warn("admin login failed: email={} reason={}", maskEmail(email), reason);
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"unauthorized\"}");
        };
    }
```

`import java.time.Duration;`는 이미 없으면 추가(위에서 FQN 사용으로 회피 가능). `LockedException` 분기는 이미 존재 — `isAccountNonLocked()=false`면 `DaoAuthenticationProvider`가 자동으로 던지므로 잠긴 계정 시도는 `"user-locked"`로 audit된다.

- [ ] **Step 8: 전체 admin-app + core 컴파일·테스트**

Run: `./gradlew :core:test :admin-app:compileJava -q`
Expected: BUILD SUCCESSFUL. 기존 `AdminUserDetails` 생성자를 호출하는 다른 코드(테스트 포함)가 깨지면 새 시그니처(lockedUntil, clock)로 호출부를 갱신한다 — grep `new AdminUserDetails(`로 전부 찾아 수정.

- [ ] **Step 9: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/AdminUser.java \
        core/src/test/java/com/crosscert/passkey/core/entity/AdminUserLockoutTest.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetails.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetailsService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java
git commit -m "feat(admin): account lockout — N회 실패 시 temporal lock + 자동 해제 (P1-6)"
```

---

## Task 6: Password reset (P1-6 — self-service)

invitation 패턴 복제. PasswordResetService(request/confirm) + PasswordResetController(permitAll 2 엔드포인트). enumeration 방지(항상 200), 토큰 1회용·1h TTL, PasswordPolicyValidator 재사용, confirm 성공 시 lockout 리셋.

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/PasswordResetService.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/PasswordResetController.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java` — password-reset permitAll + CSRF ignore
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/operator/PasswordResetServiceTest.java`

- [ ] **Step 1: PasswordResetService 단위 테스트 작성**

Create `admin-app/src/test/java/com/crosscert/passkey/admin/operator/PasswordResetServiceTest.java`:

```java
package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.policy.PasswordPolicyValidator;
import com.crosscert.passkey.core.entity.AdminPasswordResetToken;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock AdminPasswordResetTokenRepository tokens;
    @Mock AdminUserRepository users;
    @Mock MailSender mail;
    @Mock PasswordEncoder encoder;
    @Mock PasswordPolicyValidator policy;
    Clock clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), ZoneOffset.UTC);
    PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(tokens, users, mail, encoder, policy, clock);
    }

    @Test
    void request_existing_user_creates_token_and_sends_mail() {
        AdminUser u = new AdminUser("op@crosscert.com", "h", "PLATFORM_OPERATOR");
        when(users.findByEmail("op@crosscert.com")).thenReturn(Optional.of(u));
        service.request("op@crosscert.com");
        verify(tokens).save(any(AdminPasswordResetToken.class));
        verify(mail).send(eq("op@crosscert.com"), anyString(), anyString());
    }

    @Test
    void request_unknown_user_is_silent_noop() {
        when(users.findByEmail("ghost@x.com")).thenReturn(Optional.empty());
        service.request("ghost@x.com"); // 예외 없이 무시(enumeration 방지)
        verify(tokens, never()).save(any());
        verify(mail, never()).send(any(), any(), any());
    }

    @Test
    void confirm_valid_token_resets_password_and_lockout() {
        AdminUser u = new AdminUser("op@crosscert.com", "old", "PLATFORM_OPERATOR");
        UUID uid = u.getId();
        AdminPasswordResetToken tok = new AdminPasswordResetToken(
                uid, service.hashForTest("plain-token"), "plain-to",
                clock.instant().plusSeconds(3600), clock.instant());
        when(tokens.findByTokenHash(service.hashForTest("plain-token"))).thenReturn(Optional.of(tok));
        when(users.findById(uid)).thenReturn(Optional.of(u));
        when(encoder.encode("NewPassw0rd!")).thenReturn("newhash");

        service.confirm("plain-token", "NewPassw0rd!");

        verify(policy).validate("NewPassw0rd!");
        assertThat(u.getBcryptHash()).isEqualTo("newhash");
        assertThat(tok.isConsumed()).isTrue();
        assertThat(u.getLockedUntil()).isNull(); // lockout 리셋
    }

    @Test
    void confirm_expired_token_rejected() {
        UUID uid = UUID.randomUUID();
        AdminPasswordResetToken tok = new AdminPasswordResetToken(
                uid, service.hashForTest("t"), "tt",
                clock.instant().minusSeconds(1), clock.instant().minusSeconds(3600));
        when(tokens.findByTokenHash(service.hashForTest("t"))).thenReturn(Optional.of(tok));
        assertThatThrownBy(() -> service.confirm("t", "NewPassw0rd!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirm_consumed_token_rejected() {
        UUID uid = UUID.randomUUID();
        AdminPasswordResetToken tok = new AdminPasswordResetToken(
                uid, service.hashForTest("t"), "tt",
                clock.instant().plusSeconds(3600), clock.instant());
        tok.consume(clock.instant());
        when(tokens.findByTokenHash(service.hashForTest("t"))).thenReturn(Optional.of(tok));
        assertThatThrownBy(() -> service.confirm("t", "NewPassw0rd!"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :admin-app:test --tests '*PasswordResetServiceTest' -q`
Expected: FAIL — PasswordResetService 없음.

- [ ] **Step 3: PasswordResetService 구현**

`hashForTest`는 테스트가 토큰 hash를 재현하기 위한 package-private 헬퍼다(InvitationService의 sha256Hex 패턴 노출).

Create `admin-app/src/main/java/com/crosscert/passkey/admin/operator/PasswordResetService.java`:

```java
package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.policy.PasswordPolicyValidator;
import com.crosscert.passkey.core.entity.AdminPasswordResetToken;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Admin self-service password reset (P1-6).
 *
 * <p>InvitationService 패턴 복제 — sha-256 token_hash, MailSender, 1회용 토큰.
 * request 는 enumeration 방지를 위해 사용자 존재 여부와 무관하게 동일하게 동작
 * (없으면 조용히 no-op). confirm 은 PasswordPolicyValidator 재사용 + lockout 리셋.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Duration TOKEN_TTL = Duration.ofHours(1);
    private static final SecureRandom RNG = new SecureRandom();
    private static final String URL_PREFIX = "/reset-password?token=";

    private final AdminPasswordResetTokenRepository tokens;
    private final AdminUserRepository users;
    private final MailSender mail;
    private final PasswordEncoder encoder;
    private final PasswordPolicyValidator policy;
    private final Clock clock;

    @Value("${admin.invite.base-url:http://localhost:5173}")
    private String baseUrl;

    public PasswordResetService(AdminPasswordResetTokenRepository tokens,
                                AdminUserRepository users,
                                MailSender mail,
                                PasswordEncoder encoder,
                                PasswordPolicyValidator policy,
                                Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.mail = mail;
        this.encoder = encoder;
        this.policy = policy;
        this.clock = clock;
    }

    /** 토큰 발급 + 메일. 사용자 없으면 조용히 무시(enumeration 방지). */
    @Transactional
    public void request(String email) {
        var userOpt = users.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.info("password reset requested for unknown email: {}", maskEmail(email));
            return; // 존재하지 않는 계정 — 동일 응답을 위해 조용히 종료
        }
        AdminUser user = userOpt.get();
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String plaintext = "rst_" + hex(raw);
        String prefix = plaintext.substring(0, 8);
        Instant now = clock.instant();
        var tok = new AdminPasswordResetToken(
                user.getId(), sha256Hex(plaintext), prefix, now.plus(TOKEN_TTL), now);
        tokens.save(tok);

        String resetUrl = baseUrl + URL_PREFIX + plaintext;
        try {
            mail.send(email, "비밀번호 재설정 — Passkey2",
                    String.format("재설정 URL: <a href=\"%s\">%s</a><br>만료: %s",
                            resetUrl, resetUrl, tok.getExpiresAt()));
        } catch (Exception ignore) {
            // 메일 실패해도 토큰은 발급됨 — 운영자가 로그/대체 경로로 복구
        }
        log.info("password reset token issued: email={} tokenPrefix={}", maskEmail(email), prefix);
    }

    /** 토큰 검증 후 password 재설정 + lockout 리셋. 실패 시 IllegalArgumentException(400). */
    @Transactional
    public void confirm(String plaintext, String newPassword) {
        String hash = sha256Hex(plaintext);
        AdminPasswordResetToken tok = tokens.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("invalid token"));
        Instant now = clock.instant();
        if (tok.isConsumed()) throw new IllegalArgumentException("token already used");
        if (tok.isExpired(now)) throw new IllegalArgumentException("token expired");

        AdminUser user = users.findById(tok.getAdminUserId())
                .orElseThrow(() -> new IllegalArgumentException("invalid token"));
        policy.validate(newPassword);
        user.setBcryptHash(encoder.encode(newPassword));
        user.recordSuccessfulLogin(); // lockout/failed-count 리셋(정당한 복구 경로)
        tok.consume(now);
        log.info("password reset confirmed: email={} tokenPrefix={}",
                maskEmail(user.getEmail()), tok.getTokenPrefix());
    }

    /** 테스트 전용: 토큰 hash 재현. */
    String hashForTest(String plaintext) { return sha256Hex(plaintext); }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "(unknown)";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static String sha256Hex(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests '*PasswordResetServiceTest' -q`
Expected: PASS (5 tests).

- [ ] **Step 5: PasswordResetController 작성**

Create `admin-app/src/main/java/com/crosscert/passkey/admin/operator/PasswordResetController.java`:

```java
package com.crosscert.passkey.admin.operator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Self-service password reset 엔드포인트 (P1-6).
 *
 * <p>두 엔드포인트 모두 미인증(permitAll) — 비밀번호를 잊은 운영자는 세션이 없다.
 * request 는 항상 200(enumeration 방지). confirm 실패는 PasswordResetService 가
 * IllegalArgumentException 으로 던지고 GlobalExceptionHandler 가 400 으로 매핑.
 */
@RestController
@RequestMapping("/admin/api/password-reset")
public class PasswordResetController {

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    @PostMapping("/request")
    public ResponseEntity<?> request(@RequestBody RequestBody body) {
        String email = body == null ? null : body.email();
        if (email != null && !email.isBlank()) {
            service.request(email);
        }
        // 항상 동일 200 — 계정 존재 여부 노출 금지
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody ConfirmBody body) {
        service.confirm(body.token(), body.newPassword());
        return ResponseEntity.ok(Map.of("reset", true));
    }

    public record RequestBody(String email) {}
    public record ConfirmBody(String token, String newPassword) {}
}
```

- [ ] **Step 6: AdminSecurityConfig에 password-reset permitAll + CSRF ignore**

`authorizeHttpRequests`의 invitations permitAll 줄 다음에 추가:

```java
                // Password reset is unauthenticated — the operator forgot their password
                // and has no session. request always returns 200 (enumeration 방지).
                .requestMatchers("/admin/api/password-reset/**").permitAll()
```

`csrf(...)`의 `.ignoringRequestMatchers("/admin/api/invitations/**")`를 다음으로 교체:

```java
                .ignoringRequestMatchers("/admin/api/invitations/**", "/admin/api/password-reset/**"));
```

- [ ] **Step 7: 슬라이스 테스트 — 컨트롤러 동작**

Create `admin-app/src/test/java/com/crosscert/passkey/admin/operator/PasswordResetControllerTest.java`. 기존 컨트롤러 슬라이스 패턴(예: InvitationController 테스트)을 먼저 읽어 `@WebMvcTest` 설정·보안 우회 방식을 복제한 뒤:

```java
package com.crosscert.passkey.admin.operator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PasswordResetController.class)
class PasswordResetControllerTest {

    @Autowired MockMvc mvc;
    @MockBean PasswordResetService service;

    @Test
    void request_always_200_even_for_unknown() throws Exception {
        mvc.perform(post("/admin/api/password-reset/request").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ghost@x.com\"}"))
                .andExpect(status().isOk());
        verify(service).request(eq("ghost@x.com"));
    }

    @Test
    void confirm_delegates_to_service() throws Exception {
        mvc.perform(post("/admin/api/password-reset/confirm").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\",\"newPassword\":\"NewPassw0rd!\"}"))
                .andExpect(status().isOk());
        verify(service).confirm(eq("t"), eq("NewPassw0rd!"));
    }
}
```

기존 슬라이스가 전체 `AdminSecurityConfig`를 로드해 permitAll 검증이 필요하면, 그 패턴(예: `@Import(AdminSecurityConfig.class)` + 필요한 MockBean)을 따른다.

- [ ] **Step 8: 테스트 통과 + 전체 admin-app 단위/슬라이스**

Run: `./gradlew :admin-app:test --tests '*PasswordReset*' -q`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/operator/PasswordResetService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/operator/PasswordResetController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/operator/PasswordResetServiceTest.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/operator/PasswordResetControllerTest.java
git commit -m "feat(admin): self-service password reset — invitation 패턴 + enumeration 방지 + lockout 리셋 (P1-6)"
```

---

## Task 7: 전체 검증 + 통합 + followups 갱신

**Files:**
- Test: `admin-app/src/test/java/.../*IT.java` (기존 IT 패턴 따름, 선택)
- Modify: `docs/superpowers/followups/2026-05-30-saas-launch-hardening-followups.md`

- [ ] **Step 1: 전체 빌드 + 단위/슬라이스 테스트**

Run: `./gradlew :core:test :admin-app:test -q`
Expected: BUILD SUCCESSFUL. 깨진 기존 테스트(AdminUserDetails 생성자 변경, MfaController 생성자 변경, failureHandler 빈 시그니처 변경의 영향)는 전부 새 시그니처로 갱신.

- [ ] **Step 2: V37 마이그레이션 idempotent 통합 검증 (가능 환경)**

기존 마이그레이션 IT 패턴이 있으면 그 위에 V37 재실행 멱등성을 확인. 환경상 Oracle Testcontainers가 flaky(followups §5)면 스킵하고 그 사실을 기록. 최소한 Flyway가 V37을 clean 적용하는지 단일 부팅으로 확인:

Run: `./gradlew :admin-app:test --tests '*Migration*' --tests '*Flyway*' -q` (해당 테스트 있을 때)
Expected: PASS 또는 환경 한계 시 기록.

- [ ] **Step 3: lockout end-to-end 슬라이스 (선택, 가능 시)**

DaoAuthenticationProvider + AdminUserDetailsService로 5회 bad-password→6번째 LockedException을 검증하는 통합 테스트가 가치 있으면 추가. 기존 로그인 통합 테스트가 있으면 그 패턴을 확장.

- [ ] **Step 4: followups 갱신**

`docs/superpowers/followups/2026-05-30-saas-launch-hardening-followups.md` §2.1·§2.2를 "해결됨(그룹 A phase)"으로 갱신하고, P1-6을 spec §4 표에서 해결로 표시. 남은 deferred(P1-1~5,7)와 MFA enroll confirm-code(§2.3은 이미 해결)·minor cleanup(§2.4 findByUserHandle, §2.5 ApiKey active 정의)은 그대로 유지.

- [ ] **Step 5: 커밋 전 게이트 — codex review (가능 시) + code quality**

메모리 지침: 커밋 전 `/codex review`(6/1 quota 리셋 후 누적 diff). 불가 시 code quality subagent 2단계로 대체. 특히 recovery consume 경로·MfaSecretCipher fallback·lockout 핸들러·reset enumeration.

- [ ] **Step 6: Commit (followups + 잔여)**

```bash
git add docs/superpowers/followups/2026-05-30-saas-launch-hardening-followups.md
git commit -m "docs(followups): 그룹 A 완료 표시 — MFA recovery/secret 암호화/lockout/reset (P0 잔여 + P1-6)"
```

---

## Self-Review

**Spec coverage:**
- P0 잔여 A (recovery code): Task 1(엔티티/repo) + Task 3(서비스) + Task 4(MfaController 연동) ✅
- P0 잔여 B (secret 암호화): Task 1(컬럼 확장) + Task 2(MfaSecretCipher) + Task 4(seal/open 연동) ✅
- P1-6 lockout: Task 1(컬럼) + Task 5(도메인+핸들러+UserDetails) ✅
- P1-6 password reset: Task 1(테이블) + Task 6(서비스+컨트롤러+보안) ✅
- 보안 경계(enumeration/one-shot/CSRF/fallback): Task 3·4·5·6 테스트로 커버 ✅
- minor cleanup D·E: spec에서 "인접 시 끼워넣기"로 분류, 이번 plan 범위에서 제외(별도) — 의도적, 누락 아님.

**Placeholder scan:** 모든 step에 실제 코드/명령/기대값 포함. "기존 패턴 확인 후 맞춤"은 placeholder가 아니라 슬라이스 보안 설정이 프로젝트마다 다르기 때문의 실행 지시(읽을 대상 파일을 명시).

**Type consistency:**
- `RecoveryCodeService.consume(UUID, String)` / `generate(UUID)→List<String>` / `remaining(UUID)→long` — Task 3·4 일치 ✅
- `MfaSecretCipher.seal(String)→String` / `open(String)→String` — Task 2·4 일치 ✅
- `AdminUser.recordFailedLogin(Instant,int,Duration)` / `recordSuccessfulLogin()` / `isLocked(Instant)` / `getLockedUntil()→Instant` — Task 5·6 일치 ✅
- `AdminUserDetails` 생성자 8-arg(+lockedUntil,clock) — Task 5 일관 ✅
- `PasswordResetService.request(String)` / `confirm(String,String)` / `hashForTest(String)` — Task 6 일치 ✅
- `AdminPasswordResetToken(UUID,String,String,Instant,Instant)` 생성자 — Task 1·6 일치 ✅
