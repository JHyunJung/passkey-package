package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "TENANT_ACCEPTED_FORMAT")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class TenantAcceptedFormat extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TENANT_ID", nullable = false, columnDefinition = "RAW(16)")
    private Tenant tenant;

    @Column(name = "FORMAT", length = 32, nullable = false)
    private String format;

    protected TenantAcceptedFormat() {}  // JPA

    public TenantAcceptedFormat(Tenant tenant, String format) {
        this.tenant = tenant;
        this.format = format;
    }

    public Tenant getTenant() { return tenant; }
    public String getFormat() { return format; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TenantAcceptedFormat other)) return false;
        // Equality by (tenantId, format) — the natural key (UNIQUE constraint).
        // Require both tenant IDs to be non-null (i.e. persisted) to avoid
        // two transient TenantAcceptedFormat instances comparing equal when
        // their tenant UUIDs have not yet been assigned by the persistence context.
        if (tenant == null || other.tenant == null) return false;
        UUID tid = tenant.getId();
        UUID otid = other.tenant.getId();
        if (tid == null || otid == null) return false;
        return Objects.equals(tid, otid)
            && Objects.equals(format, other.format);
    }

    @Override
    public int hashCode() {
        UUID tid = (tenant == null) ? null : tenant.getId();
        // Use a constant bucket for transient instances (tid == null) so that
        // hash does not change after persist — safer than hashing null.
        return (tid == null) ? 0 : Objects.hash(tid, format);
    }
}
