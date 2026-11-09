package com.crosscert.passkey.admin.apikey

import com.crosscert.passkey.core.entity.ApiKey
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

class ApiKeyAdminDto private constructor() {

    data class ApiKeyCreateRequest(
        @field:NotNull val tenantId: UUID?,
        @field:NotBlank @field:Size(max = 64) val name: String?,
        @field:NotEmpty val scopes: Set<@NotBlank @Size(max = 32) String>?,
        @field:Min(1) @field:Max(36) val expiresInMonths: Int?, // null = 무기한
    )

    data class ApiKeyCreateResponse(
        val id: UUID?,
        val plainText: String?, // ONE-TIME — only returned at issue
        val prefix: String?,
        val scopes: Set<String>?,
        val expiresAt: OffsetDateTime?, // null = 무기한
    )

    /** P1-5 rotation 응답 — plaintextKey 는 ONE-TIME, oldKeyExpiresAt 는 구 키 grace 만료 시각. */
    data class ApiKeyRotateResponse(
        val id: UUID?,
        val plaintextKey: String?, // ONE-TIME — only returned at rotate
        val prefix: String?,
        val scopes: Set<String>?,
        val oldKeyExpiresAt: OffsetDateTime?,
    )

    data class ApiKeyView(
        val id: UUID?,
        val tenantId: UUID?,
        val name: String?,
        val keyPrefix: String?,
        val scopes: Set<String>?,
        val createdAt: OffsetDateTime?,
        val expiresAt: OffsetDateTime?,
        val revokedAt: OffsetDateTime?,
        val lastUsedAt: OffsetDateTime?,
    ) {
        companion object {
            fun from(k: ApiKey): ApiKeyView = ApiKeyView(
                k.id, k.tenantId, k.name, k.keyPrefix,
                k.scopeValues,
                k.createdAt, k.expiresAt,
                k.revokedAt, k.lastUsedAt,
            )
        }
    }
}
