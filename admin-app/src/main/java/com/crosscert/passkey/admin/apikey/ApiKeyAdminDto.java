package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.core.entity.ApiKey;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class ApiKeyAdminDto {

    private ApiKeyAdminDto() {}

    public record ApiKeyCreateRequest(
            @NotNull UUID tenantId,
            @NotBlank @Size(max = 256) String name,
            @NotEmpty Set<@NotBlank @Size(max = 32) String> scopes
    ) {}

    public record ApiKeyCreateResponse(
            UUID id,
            String plainText,          // ONE-TIME — only returned at issue
            String prefix,
            Set<String> scopes
    ) {}

    public record ApiKeyView(
            UUID id,
            UUID tenantId,
            String name,
            String keyPrefix,
            Set<String> scopes,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            Instant lastUsedAt
    ) {
        public static ApiKeyView from(ApiKey k) {
            return new ApiKeyView(
                    k.getId(), k.getTenantId(), k.getName(), k.getKeyPrefix(),
                    k.getScopeValues(),
                    k.getCreatedAt(), k.getExpiresAt(),
                    k.getRevokedAt(), k.getLastUsedAt());
        }
    }
}
