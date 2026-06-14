package com.crosscert.passkey.sdk.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistrationStartResponse(
    @get:JvmName("registrationToken") @get:JsonProperty("registrationToken") @param:JsonProperty("registrationToken") val registrationToken: String,
    @get:JvmName("publicKeyCredentialCreationOptions") @get:JsonProperty("publicKeyCredentialCreationOptions") @param:JsonProperty("publicKeyCredentialCreationOptions") val publicKeyCredentialCreationOptions: JsonNode,
)
