package com.crosscert.passkey.sdk.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AuthenticationStartRequest(
    @get:JvmName("userHandle") @get:JsonProperty("userHandle") @param:JsonProperty("userHandle") val userHandle: String?,
)
