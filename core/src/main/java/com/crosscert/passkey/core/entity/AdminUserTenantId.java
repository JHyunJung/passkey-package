package com.crosscert.passkey.core.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** admin_user_tenant 복합 PK (admin_user_id, tenant_id). */
public class AdminUserTenantId implements Serializable {
    private UUID adminUserId;
    private UUID tenantId;

    public AdminUserTenantId() {}
    public AdminUserTenantId(UUID adminUserId, UUID tenantId) {
        this.adminUserId = adminUserId;
        this.tenantId = tenantId;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AdminUserTenantId that)) return false;
        return Objects.equals(adminUserId, that.adminUserId)
            && Objects.equals(tenantId, that.tenantId);
    }
    @Override public int hashCode() { return Objects.hash(adminUserId, tenantId); }
}
