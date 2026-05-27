package com.crosscert.passkey.admin.audit;

import java.util.List;
import java.util.UUID;

public record AuditChainTenantOverview(
        UUID tenantId,
        String tenantName,
        boolean intact,
        long verifiedRows,
        List<Long> buckets,
        UUID tamperedEntryId
) {}
