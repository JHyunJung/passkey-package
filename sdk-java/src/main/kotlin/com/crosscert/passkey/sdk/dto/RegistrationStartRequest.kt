package com.crosscert.passkey.sdk.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class RegistrationStartRequest(
    @get:JvmName("userHandle") @get:JsonProperty("userHandle") @param:JsonProperty("userHandle") val userHandle: String,
    @get:JvmName("displayName") @get:JsonProperty("displayName") @param:JsonProperty("displayName") val displayName: String,
    @get:JvmName("username") @get:JsonProperty("username") @param:JsonProperty("username") val username: String,
)
