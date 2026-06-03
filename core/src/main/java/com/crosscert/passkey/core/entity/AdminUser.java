package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

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

    // tenant 격리에서 의도적 제외: tenantId nullable (PLATFORM_OPERATOR=NULL).
    // @Filter(tenant_id=:tenantId)를 걸면 NULL row가 가려져 로그인/운영자 조회가 깨진다.
    // 격리는 앱 레벨 TenantBoundary 게이팅이 담당.
    @Column(name = "tenant_id", columnDefinition = "RAW(16)")
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID tenantId;

    @Column(name = "LAST_LOGIN_AT")
    private Instant lastLoginAt;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status = "ACTIVE";

    @Column(name = "CREATED_BY", length = 255)
    private String createdBy;

    @Column(name = "SUSPENDED_AT")
    private Instant suspendedAt;

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
    private Instant lockedUntil;

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

    public Instant getLastLoginAt() { return lastLoginAt; }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    public boolean isPlatformOperator() { return "PLATFORM_OPERATOR".equals(role); }
    public boolean isRpAdmin()          { return "RP_ADMIN".equals(role); }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public Instant getSuspendedAt() { return suspendedAt; }
    public void setSuspendedAt(Instant suspendedAt) { this.suspendedAt = suspendedAt; }

    public String getSuspendedBy() { return suspendedBy; }
    public void setSuspendedBy(String suspendedBy) { this.suspendedBy = suspendedBy; }

    public boolean isMfaEnabled() { return "Y".equals(mfaEnabledFlag); }
    public void setMfaEnabled(boolean v) { this.mfaEnabledFlag = v ? "Y" : "N"; }

    public String getMfaSecret() { return mfaSecret; }
    public void setMfaSecret(String v) { this.mfaSecret = v; }

    public Instant getLockedUntil() { return lockedUntil; }

    /** 테스트 전용 lock 판정. 실제 로그인 게이트는 AdminUserDetails.isAccountNonLocked. */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public void recordFailedLogin(Instant now, int maxAttempts, java.time.Duration lockDuration) {
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
