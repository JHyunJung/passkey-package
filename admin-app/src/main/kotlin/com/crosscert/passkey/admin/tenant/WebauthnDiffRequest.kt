package com.crosscert.passkey.admin.tenant

/**
 * Partial-friendly request for `POST /admin/api/tenants/{idOrSlug}/webauthn-config/diff`.
 *
 * All fields are intentionally nullable so a UI client can show "preview my
 * change for just this one field" without re-sending the full WebAuthn config.
 * A `null` field means "no change" — the diff service preserves the
 * tenant's current value.
 *
 * This is distinct from [TenantAdminDto.TenantUpdateRequest], which is
 * the strict `PUT` body and requires every field via `@NotNull /
 * @NotBlank`. Sharing one DTO across both endpoints would force the diff
 * endpoint to either drop validation entirely (silent bypass) or reject any
 * partial preview (loses the UX win).
 */
data class WebauthnDiffRequest(
    val rpId: String?,
    val rpName: String?,
    val allowedOrigins: List<String>?,
    val acceptedFormats: Set<String>?,
    val requireUserVerification: Boolean?,
    val mdsRequired: Boolean?,
)
