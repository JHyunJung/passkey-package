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
}
