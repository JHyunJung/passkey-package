package com.crosscert.passkey.admin.tenant;

import java.util.List;
import java.util.Set;

/**
 * Partial-friendly request for {@code POST /admin/api/tenants/{idOrSlug}/webauthn-config/diff}.
 *
 * <p>All fields are intentionally nullable so a UI client can show "preview my
 * change for just this one field" without re-sending the full WebAuthn config.
 * A {@code null} field means "no change" — the diff service preserves the
 * tenant's current value.
 *
 * <p>This is distinct from {@link TenantAdminDto.TenantUpdateRequest}, which is
 * the strict {@code PUT} body and requires every field via {@code @NotNull /
 * @NotBlank}. Sharing one DTO across both endpoints would force the diff
 * endpoint to either drop validation entirely (silent bypass) or reject any
 * partial preview (loses the UX win).
 */
public record WebauthnDiffRequest(
        String rpId,
        String rpName,
        List<String> allowedOrigins,
        Set<String> acceptedFormats,
        Boolean requireUserVerification,
        Boolean mdsRequired
) {}
