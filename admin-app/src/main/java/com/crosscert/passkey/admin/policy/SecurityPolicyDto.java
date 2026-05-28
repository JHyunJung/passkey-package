package com.crosscert.passkey.admin.policy;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class SecurityPolicyDto {
    private SecurityPolicyDto() {}

    public record View(
            int sessionIdleTimeoutMinutes,
            int passwordMinLength,
            boolean mfaRequired,
            List<String> corsAllowlist,
            Instant updatedAt,
            String updatedBy
    ) {
        public View {
            corsAllowlist = corsAllowlist == null ? List.of() : List.copyOf(corsAllowlist);
        }
    }

    public record UpdateRequest(
            @Min(1) int sessionIdleTimeoutMinutes,
            @Min(1) int passwordMinLength,
            @NotNull Boolean mfaRequired,
            @NotNull @Size(max = 64) List<@NotBlank @Size(max = 256) String> corsAllowlist
    ) {
        public UpdateRequest {
            if (corsAllowlist != null) {
                corsAllowlist = List.copyOf(corsAllowlist);
            }
        }
    }
}
