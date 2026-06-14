package com.crosscert.passkey.rpapp.user

import com.fasterxml.jackson.annotation.JsonProperty
import java.io.Serializable
import java.time.Instant

data class RpAppUser(
    @field:JsonProperty("userHandle") val userHandle: String?,
    @field:JsonProperty("username") val username: String?,
    @field:JsonProperty("displayName") val displayName: String?,
    @field:JsonProperty("createdAt") val createdAt: Instant?,
    // confirmRegistration 후 채워짐. 없으면 null (pending).
    @field:JsonProperty("credentialId") val credentialId: String?,
) : Serializable
