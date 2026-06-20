package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AaguidPolicyDto {
    private AaguidPolicyDto() {}

    public record Entry(UUID aaguid, String note, String mdsName) {}

    public record View(
            UUID tenantId,
            TenantAaguidPolicy.Mode mode,
            boolean mdsStrict,
            List<Entry> entries,
            OffsetDateTime updatedAt,
            String updatedBy
    ) {}

    public record UpdateRequest(
            TenantAaguidPolicy.Mode mode,
            boolean mdsStrict,
            List<EntryInput> entries
    ) {}

    public record EntryInput(UUID aaguid, String note) {}
}
