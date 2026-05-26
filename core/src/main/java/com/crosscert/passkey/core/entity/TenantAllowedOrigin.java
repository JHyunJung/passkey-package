package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "TENANT_ALLOWED_ORIGIN")
public class TenantAllowedOrigin {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TENANT_ID", nullable = false, columnDefinition = "RAW(16)")
    private Tenant tenant;

    @Column(name = "ORIGIN", length = 512, nullable = false)
    private String origin;

    @Column(name = "SORT_ORDER", nullable = false)
    private int sortOrder;

    protected TenantAllowedOrigin() {}  // JPA

    public TenantAllowedOrigin(Tenant tenant, String origin, int sortOrder) {
        this.tenant = tenant;
        this.origin = origin;
        this.sortOrder = sortOrder;
    }

    public UUID getId() { return id; }
    public Tenant getTenant() { return tenant; }
    public String getOrigin() { return origin; }
    public int getSortOrder() { return sortOrder; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TenantAllowedOrigin other)) return false;
        // Equality by (tenantId, origin) — the natural key (UNIQUE constraint).
        // Require both tenant IDs to be non-null (i.e. persisted) to avoid
        // two transient TenantAllowedOrigin instances comparing equal when
        // their tenant UUIDs have not yet been assigned by the persistence context.
        if (tenant == null || other.tenant == null) return false;
        UUID tid = tenant.getId();
        UUID otid = other.tenant.getId();
        if (tid == null || otid == null) return false;
        return Objects.equals(tid, otid)
            && Objects.equals(origin, other.origin);
    }

    @Override
    public int hashCode() {
        UUID tid = (tenant == null) ? null : tenant.getId();
        // Use a constant bucket for transient instances (tid == null) so that
        // hash does not change after persist — safer than hashing null.
        return (tid == null) ? 0 : Objects.hash(tid, origin);
    }
}
