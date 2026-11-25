package com.crosscert.passkey.core.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** usage_monthly 복합 PK (tenant_id, period). */
public class UsageMonthlyId implements Serializable {
    private UUID tenantId;
    private String period;

    public UsageMonthlyId() {}
    public UsageMonthlyId(UUID tenantId, String period) {
        this.tenantId = tenantId;
        this.period = period;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UsageMonthlyId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(period, that.period);
    }
    @Override public int hashCode() { return Objects.hash(tenantId, period); }
}
