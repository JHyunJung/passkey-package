package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ADMIN_USER")
public class AdminUser extends BaseEntity {

    @Column(name = "EMAIL", length = 255, nullable = false)
    private String email;

    @Column(name = "BCRYPT_HASH", length = 72)
    private String bcryptHash;

    @Column(name = "ROLE", length = 16, nullable = false)
    private String role;

    @Column(name = "ENABLED", columnDefinition = "CHAR(1)", nullable = false)
    private String enabledFlag;

    @Column(name = "LAST_LOGIN_AT")
    private OffsetDateTime lastLoginAt;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status = "ACTIVE";

    @Column(name = "CREATED_BY", length = 255)
    private String createdBy;

    @Column(name = "SUSPENDED_AT")
    private OffsetDateTime suspendedAt;

    @Column(name = "SUSPENDED_BY", length = 255)
    private String suspendedBy;

    @Column(name = "MFA_ENABLED", columnDefinition = "CHAR(1)", nullable = false)
    private String mfaEnabledFlag = "N";

    // length=255 matches V37 (sealed "enc:v1:"+base64 secret ≈ 87 chars exceeds 64).
    @Column(name = "MFA_SECRET", length = 255)
    private String mfaSecret;

    @Column(name = "FAILED_LOGIN_COUNT", nullable = false)
    private int failedLoginCount = 0;

    @Column(name = "LOCKED_UNTIL")
    private OffsetDateTime lockedUntil;

    protected AdminUser() {}

    /** No-arg constructor for programmatic creation via setters (e.g. invite flow). */
    public static AdminUser create() {
        AdminUser u = new AdminUser();
        u.enabledFlag = "Y";
        u.status = "ACTIVE";
        return u;
    }

    public AdminUser(String email, String bcryptHash, String role) {
        this.email = email;
        this.bcryptHash = bcryptHash;
        this.role = role;
        this.enabledFlag = "Y";
        this.status = "ACTIVE";
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getBcryptHash() { return bcryptHash; }
    public void setBcryptHash(String bcryptHash) { this.bcryptHash = bcryptHash; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public boolean isEnabled() { return "Y".equals(enabledFlag); }
    public void setEnabled(boolean enabled) { this.enabledFlag = enabled ? "Y" : "N"; }

    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }

    public void recordLogin(OffsetDateTime now) {
        this.lastLoginAt = now;
    }

    public boolean isPlatformOperator() { return "PLATFORM_OPERATOR".equals(role); }
    public boolean isRpAdmin()          { return "RP_ADMIN".equals(role); }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getSuspendedAt() { return suspendedAt; }
    public void setSuspendedAt(OffsetDateTime suspendedAt) { this.suspendedAt = suspendedAt; }

    public String getSuspendedBy() { return suspendedBy; }
    public void setSuspendedBy(String suspendedBy) { this.suspendedBy = suspendedBy; }

    public boolean isMfaEnabled() { return "Y".equals(mfaEnabledFlag); }
    public void setMfaEnabled(boolean v) { this.mfaEnabledFlag = v ? "Y" : "N"; }

    public String getMfaSecret() { return mfaSecret; }
    public void setMfaSecret(String v) { this.mfaSecret = v; }

    public OffsetDateTime getLockedUntil() { return lockedUntil; }

    /** 테스트 전용 lock 판정. 실제 로그인 게이트는 AdminUserDetails.isAccountNonLocked. */
    public boolean isLocked(OffsetDateTime now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public void recordFailedLogin(OffsetDateTime now, int maxAttempts, java.time.Duration lockDuration) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= maxAttempts) {
            this.lockedUntil = now.plus(lockDuration);
            this.failedLoginCount = 0;
        }
    }

    public void recordSuccessfulLogin() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }
}
