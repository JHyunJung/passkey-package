package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.core.entity.ApiKey;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class ApiKeyAdminDto {

    private ApiKeyAdminDto() {}

    public record ApiKeyCreateRequest(
            @NotBlank String tenantId,
            @NotBlank String name,
            @NotBlank String scopesJson,
            Instant expiresAt          // optional
    ) {}

    public record ApiKeyCreateResponse(
            Long id,
            String prefix,
            String plainText,          // ONE-TIME — only returned at issue
            String name,
            String tenantId,
            Instant createdAt,
            Instant expiresAt
    ) {}

    public record ApiKeyView(
            Long id,
            String prefix,
            String name,
            String tenantId,
            Instant createdAt,
            Instant lastUsedAt,
            Instant expiresAt,
            Instant revokedAt
    ) {
        public static ApiKeyView from(ApiKey k) {
            return new ApiKeyView(
                    k.getId(), k.getKeyPrefix(), k.getName(), k.getTenantId(),
                    k.getCreatedAt(), k.getLastUsedAt(), k.getExpiresAt(), k.getRevokedAt());
        }
    }
}
