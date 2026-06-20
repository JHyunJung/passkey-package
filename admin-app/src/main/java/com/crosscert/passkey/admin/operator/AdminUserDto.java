package com.crosscert.passkey.admin.operator;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class AdminUserDto {
    private AdminUserDto() {}

    public record View(
            UUID id, String email, String role, String status,
            UUID tenantId, Instant createdAt, Instant lastLoginAt,
            Instant suspendedAt, String createdBy, boolean mfaEnabled
    ) {}

    public record InviteRequest(
            @NotBlank @Email String email,
            @NotBlank String role,
            UUID tenantId
    ) {}

    public record InviteResponse(
            View user,
            InvitationInfo invitation
    ) {}

    public record InvitationInfo(
            String tokenPrefix,
            String plaintextToken,
            String acceptUrl,
            OffsetDateTime expiresAt
    ) {}

    public record InvitationCheck(
            String email,
            String role,
            UUID tenantId,
            OffsetDateTime expiresAt
    ) {}

    public record AcceptRequest(
            @NotBlank @Size(min = 12, max = 128) String password
    ) {}
}
