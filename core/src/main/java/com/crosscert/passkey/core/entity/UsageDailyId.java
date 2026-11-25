package com.crosscert.passkey.core.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** usage_daily 복합 PK (tenant_id, usage_date). */
public class UsageDailyId implements Serializable {
    private UUID tenantId;
    private LocalDate usageDate;

    public UsageDailyId() {}
    public UsageDailyId(UUID tenantId, LocalDate usageDate) {
        this.tenantId = tenantId;
        this.usageDate = usageDate;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UsageDailyId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(usageDate, that.usageDate);
    }
    @Override public int hashCode() { return Objects.hash(tenantId, usageDate); }
}
