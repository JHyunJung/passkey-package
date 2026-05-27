package com.crosscert.passkey.admin.audit;

import java.time.Instant;
import java.util.List;

public record AuditChainOverview(
        Instant verifiedAt,
        int windowHours,
        int bucketSizeMinutes,
        Totals totals,
        List<AuditChainTenantOverview> tenants
) {
    public record Totals(
            int tenantsIntact,
            int tenantsTotal,
            int tenantsTampered,
            long verifiedRows,
            long verificationMs
    ) {}
}
