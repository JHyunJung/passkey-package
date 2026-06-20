package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.entity.Tenant;
import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TenantAdminDto {

    private TenantAdminDto() {}

    public record TenantCreateRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$") String slug,
            @NotBlank @Size(max = 256) String displayName,
            @NotBlank @Size(max = 256) String rpId,
            @NotBlank @Size(max = 256) String rpName,
            @NotEmpty List<@NotBlank @Size(max = 512) String> allowedOrigins,
            @NotEmpty Set<@NotBlank @Size(max = 32) String> acceptedFormats,
            @NotNull Boolean requireUserVerification,
            @NotNull Boolean mdsRequired,
            @NotBlank @Pattern(regexp = "^(NONE|INDIRECT|DIRECT|ENTERPRISE)$") String attestationConveyance,
            @Min(1000) @Max(600000) int webauthnTimeoutMs
    ) {}

    public record TenantUpdateRequest(
            @NotBlank @Size(max = 256) String displayName,
            @NotBlank @Size(max = 256) String rpId,
            @NotBlank @Size(max = 256) String rpName,
            @NotEmpty List<@NotBlank @Size(max = 512) String> allowedOrigins,
            @NotEmpty Set<@NotBlank @Size(max = 32) String> acceptedFormats,
            @NotNull Boolean requireUserVerification,
            @NotNull Boolean mdsRequired,
            @NotBlank @Pattern(regexp = "^(NONE|INDIRECT|DIRECT|ENTERPRISE)$") String attestationConveyance,
            @Min(1000) @Max(600000) int webauthnTimeoutMs
    ) {}

    public record TenantView(
            UUID id,
            String slug,
            String displayName,
            String status,
            String rpId,
            String rpName,
            List<String> allowedOrigins,
            Set<String> acceptedFormats,
            boolean requireUserVerification,
            boolean mdsRequired,
            String attestationConveyance,
            int webauthnTimeoutMs,
            long credentials,
            long apiKeys,
            OffsetDateTime lastEventAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static TenantView from(Tenant t, long credentials, long apiKeys, OffsetDateTime lastEventAt) {
            return new TenantView(
                    t.getId(), t.getSlug(), t.getDisplayName(), t.getStatus(),
                    t.getRpId(), t.getRpName(),
                    t.getAllowedOriginValues(),
                    t.getAcceptedFormatValues(),
                    t.isRequireUserVerification(),
                    t.isMdsRequired(),
                    t.getAttestationConveyance(),
                    t.getWebauthnTimeoutMs(),
                    credentials, apiKeys, lastEventAt,
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }
}
