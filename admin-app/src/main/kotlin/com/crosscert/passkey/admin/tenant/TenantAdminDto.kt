package com.crosscert.passkey.admin.tenant

import com.crosscert.passkey.core.entity.Tenant
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

class TenantAdminDto private constructor() {

    data class TenantCreateRequest(
        @field:NotBlank @field:Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$") val slug: String?,
        @field:NotBlank @field:Size(max = 256) val displayName: String?,
        @field:NotBlank @field:Size(max = 256) val rpId: String?,
        @field:NotBlank @field:Size(max = 256) val rpName: String?,
        @field:NotEmpty val allowedOrigins: List<@NotBlank @Size(max = 512) String>?,
        @field:NotEmpty val acceptedFormats: Set<@NotBlank @Size(max = 32) String>?,
        @field:NotNull val requireUserVerification: Boolean?,
        @field:NotNull val mdsRequired: Boolean?,
        @field:NotBlank @field:Pattern(regexp = "^(NONE|INDIRECT|DIRECT|ENTERPRISE)$") val attestationConveyance: String?,
        @field:Min(1000) @field:Max(600000) val webauthnTimeoutMs: Int,
    )

    data class TenantUpdateRequest(
        @field:NotBlank @field:Size(max = 256) val displayName: String?,
        @field:NotBlank @field:Size(max = 256) val rpId: String?,
        @field:NotBlank @field:Size(max = 256) val rpName: String?,
        @field:NotEmpty val allowedOrigins: List<@NotBlank @Size(max = 512) String>?,
        @field:NotEmpty val acceptedFormats: Set<@NotBlank @Size(max = 32) String>?,
        @field:NotNull val requireUserVerification: Boolean?,
        @field:NotNull val mdsRequired: Boolean?,
        @field:NotBlank @field:Pattern(regexp = "^(NONE|INDIRECT|DIRECT|ENTERPRISE)$") val attestationConveyance: String?,
        @field:Min(1000) @field:Max(600000) val webauthnTimeoutMs: Int,
    )

    data class TenantView(
        val id: UUID?,
        val slug: String?,
        val displayName: String?,
        val status: String?,
        val rpId: String?,
        val rpName: String?,
        val allowedOrigins: List<String>?,
        val acceptedFormats: Set<String>?,
        val requireUserVerification: Boolean,
        val mdsRequired: Boolean,
        val attestationConveyance: String?,
        val webauthnTimeoutMs: Int,
        val credentials: Long,
        val apiKeys: Long,
        val lastEventAt: OffsetDateTime?,
        val createdAt: OffsetDateTime?,
        val updatedAt: OffsetDateTime?,
    ) {
        companion object {
            @JvmStatic
            fun from(t: Tenant, credentials: Long, apiKeys: Long, lastEventAt: OffsetDateTime?): TenantView =
                TenantView(
                    t.id, t.slug, t.displayName, t.status,
                    t.rpId, t.rpName,
                    t.allowedOriginValues,
                    t.acceptedFormatValues,
                    t.isRequireUserVerification,
                    t.isMdsRequired,
                    t.attestationConveyance,
                    t.webauthnTimeoutMs,
                    credentials, apiKeys, lastEventAt,
                    t.createdAt, t.updatedAt,
                )
        }
    }
}
