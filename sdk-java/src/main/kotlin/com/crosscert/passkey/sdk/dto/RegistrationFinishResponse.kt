package com.crosscert.passkey.sdk.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistrationFinishResponse(
    @get:JvmName("credentialId") @get:JsonProperty("credentialId") @param:JsonProperty("credentialId") val credentialId: String,
    @get:JvmName("aaguid") @get:JsonProperty("aaguid") @param:JsonProperty("aaguid") val aaguid: String,
    @get:JvmName("attestationFormat") @get:JsonProperty("attestationFormat") @param:JsonProperty("attestationFormat") val attestationFormat: String,
    @get:JvmName("createdAt") @get:JsonProperty("createdAt") @param:JsonProperty("createdAt") val createdAt: String,
)
