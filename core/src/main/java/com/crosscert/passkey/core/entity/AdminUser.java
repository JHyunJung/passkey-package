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

    // G05: MFA-verify failures accrue against their own counter so a
    // password-only login success (recordSuccessfulLogin) cannot reset
    // brute-force progress made against the second factor. Both counters
    // still trip the SAME lockedUntil (defense-in-depth: an MFA-triggered
    // lock also blocks primary login via AdminUserDetails.isAccountNonLocked —
    // acceptable because reaching MFA verify means the password already
    // matched). See V3__admin_user_mfa_lockout_counter.sql.
    @Column(name = "MFA_FAILED_COUNT", nullable = false)
    private int mfaFailedCount = 0;

    // RFC 6238 §5.2 replay guard: the TOTP time-step counter last accepted by
    // verify/confirm/disable. NULL means no step has ever been consumed (first
    // verification is never blocked). See V2__admin_user_totp_replay_guard.sql.
    @Column(name = "MFA_LAST_TOTP_STEP")
    private Long mfaLastTotpStep;

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

    public int getFailedLoginCount() { return failedLoginCount; }

    public void recordFailedLogin(OffsetDateTime now, int maxAttempts, java.time.Duration lockDuration) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= maxAttempts) {
            this.lockedUntil = now.plus(lockDuration);
            this.failedLoginCount = 0;
        }
    }

    /**
     * Password-login success. Resets ONLY the password failure counter (G05:
     * previously this also reset the counter MFA verify shared, letting a
     * password-holding attacker restart the second-factor brute-force cycle
     * indefinitely). {@code mfaFailedCount} is untouched — see
     * {@link #recordFailedMfa} / {@link #recordSuccessfulMfa}.
     *
     * <p>Clearing {@code lockedUntil} here is safe, not a lock bypass: Spring
     * Security's {@code DaoAuthenticationProvider} checks {@code
     * AdminUserDetails.isAccountNonLocked()} (backed by this same field)
     * BEFORE the success handler ever runs, so reaching here while genuinely
     * locked is impossible — this only clears an already-expired timestamp.
     */
    public void recordSuccessfulLogin() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public int getMfaFailedCount() { return mfaFailedCount; }

    /**
     * MFA-verify failure. Accrues against its own counter (independent of
     * {@link #recordFailedLogin}) so a password-only login success cannot
     * reset second-factor brute-force progress (G05). Trips the SAME
     * {@code lockedUntil} as the password counter on threshold — an
     * MFA-triggered lock still blocks primary login (defense-in-depth,
     * unchanged from before this fix).
     */
    public void recordFailedMfa(OffsetDateTime now, int maxAttempts, java.time.Duration lockDuration) {
        this.mfaFailedCount++;
        if (this.mfaFailedCount >= maxAttempts) {
            this.lockedUntil = now.plus(lockDuration);
            this.mfaFailedCount = 0;
        }
    }

    /** MFA-verify success. Resets only the MFA counter — password counter is untouched. */
    public void recordSuccessfulMfa() {
        this.mfaFailedCount = 0;
        this.lockedUntil = null;
    }

    public Long getMfaLastTotpStep() { return mfaLastTotpStep; }
    public void setMfaLastTotpStep(Long v) { this.mfaLastTotpStep = v; }

    /**
     * RFC 6238 §5.2 replay guard: true when {@code step} has already been
     * consumed by a prior successful verify/confirm/disable (i.e. {@code step
     * <= mfaLastTotpStep}). NULL {@code mfaLastTotpStep} means nothing has
     * been consumed yet, so nothing is rejected.
     */
    public boolean isTotpStepReplayed(long step) {
        return mfaLastTotpStep != null && step <= mfaLastTotpStep;
    }
}
