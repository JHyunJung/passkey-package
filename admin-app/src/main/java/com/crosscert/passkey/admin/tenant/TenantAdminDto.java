package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.entity.Tenant;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class TenantAdminDto {

    private TenantAdminDto() {}

    public record TenantCreateRequest(
            @NotBlank String id,
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
            String id,
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
                    t.getId(), t.getDisplayName(), t.getStatus(),
                    t.getRpId(), t.getRpName(),
                    t.getAllowedOriginsJson(), t.getAttestationPolicyJson(),
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }
}
