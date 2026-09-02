package com.crosscert.passkey.admin.operator;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AdminUserDto {
    private AdminUserDto() {}

    public record View(
            UUID id, String email, String role, String status,
            List<UUID> tenantIds, OffsetDateTime createdAt, OffsetDateTime lastLoginAt,
            OffsetDateTime suspendedAt, String createdBy, boolean mfaEnabled
    ) {}
}
