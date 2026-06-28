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
