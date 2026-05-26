package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.entity.Tenant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public final class TenantAdminDto {

    private TenantAdminDto() {}

    public record TenantCreateRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$") String slug,
            @NotBlank String displayName,
            @NotBlank String rpId,
            @NotBlank String rpName,
            @NotBlank String allowedOriginsJson,
            @NotBlank String attestationPolicyJson
    ) {}

    public record TenantUpdateRequest(
            @NotBlank String displayName,
            @NotBlank String rpId,
            @NotBlank String rpName,
            @NotBlank String allowedOriginsJson,
            @NotBlank String attestationPolicyJson
    ) {}

    public record TenantView(
            UUID id,
            String slug,
            String displayName,
            String status,
            String rpId,
            String rpName,
            String allowedOriginsJson,
            String attestationPolicyJson,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static TenantView from(Tenant t) {
            return new TenantView(
                    t.getId(), t.getSlug(), t.getDisplayName(), t.getStatus(),
                    t.getRpId(), t.getRpName(),
                    t.getAllowedOriginsJson(), t.getAttestationPolicyJson(),
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }
}
