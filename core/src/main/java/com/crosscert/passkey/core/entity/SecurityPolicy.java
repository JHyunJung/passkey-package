package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "SECURITY_POLICY")
public class SecurityPolicy {

    @Id
    @Column(name = "ID")
    private Long id = 1L;

    @Column(name = "SESSION_IDLE_TIMEOUT_MINUTES", nullable = false)
    private int sessionIdleTimeoutMinutes;

    @Column(name = "PASSWORD_MIN_LENGTH", nullable = false)
    private int passwordMinLength;

    @Column(name = "MFA_REQUIRED", columnDefinition = "CHAR(1)", nullable = false)
    private String mfaRequiredFlag;

    @Lob
    @Column(name = "CORS_ALLOWLIST", nullable = false)
    private String corsAllowlistJson;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    @Column(name = "UPDATED_BY", length = 255)
    private String updatedBy;

    protected SecurityPolicy() {}

    public Long getId() { return id; }

    public int getSessionIdleTimeoutMinutes() { return sessionIdleTimeoutMinutes; }
    public void setSessionIdleTimeoutMinutes(int v) { this.sessionIdleTimeoutMinutes = v; }

    public int getPasswordMinLength() { return passwordMinLength; }
    public void setPasswordMinLength(int v) { this.passwordMinLength = v; }

    public boolean isMfaRequired() { return "Y".equals(mfaRequiredFlag); }
    public void setMfaRequired(boolean v) { this.mfaRequiredFlag = v ? "Y" : "N"; }

    public String getCorsAllowlistJson() { return corsAllowlistJson; }
    public void setCorsAllowlistJson(String v) { this.corsAllowlistJson = v; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
}
