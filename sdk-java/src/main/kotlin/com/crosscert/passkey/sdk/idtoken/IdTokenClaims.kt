package com.crosscert.passkey.sdk.idtoken

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

data class IdTokenClaims(
    @get:JvmName("iss") @get:JsonProperty("iss") val iss: String?,
    @get:JvmName("sub") @get:JsonProperty("sub") val sub: String?,
    @get:JvmName("aud") @get:JsonProperty("aud") val aud: String?,
    @get:JvmName("iat") @get:JsonProperty("iat") val iat: Instant?,
    @get:JvmName("exp") @get:JsonProperty("exp") val exp: Instant?,
    @get:JvmName("amr") @get:JsonProperty("amr") val amr: List<String>,
    @get:JvmName("credId") @get:JsonProperty("credId") val credId: String?,
    @get:JvmName("aaguid") @get:JsonProperty("aaguid") val aaguid: String?,
)
