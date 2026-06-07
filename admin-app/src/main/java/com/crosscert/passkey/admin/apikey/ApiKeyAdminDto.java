package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.core.entity.ApiKey;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
            @NotBlank @Size(max = 64) String name,
            @NotEmpty Set<@NotBlank @Size(max = 32) String> scopes,
            @Min(1) @Max(36) Integer expiresInMonths   // null = 무기한
    ) {}

    public record ApiKeyCreateResponse(
            UUID id,
            String plainText,          // ONE-TIME — only returned at issue
            String prefix,
            Set<String> scopes,
            Instant expiresAt          // null = 무기한
    ) {}

    /** P1-5 rotation 응답 — plaintextKey 는 ONE-TIME, oldKeyExpiresAt 는 구 키 grace 만료 시각. */
    public record ApiKeyRotateResponse(
            UUID id,
            String plaintextKey,       // ONE-TIME — only returned at rotate
            String prefix,
            Set<String> scopes,
            Instant oldKeyExpiresAt
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
