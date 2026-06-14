package com.crosscert.passkey.sdk.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuthenticationFinishResponse(
    @get:JvmName("idToken") @get:JsonProperty("idToken") @param:JsonProperty("idToken") val idToken: String,
    @get:JvmName("tokenType") @get:JsonProperty("tokenType") @param:JsonProperty("tokenType") val tokenType: String,
    @get:JvmName("expiresIn") @get:JsonProperty("expiresIn") @param:JsonProperty("expiresIn") val expiresIn: Long,
)
