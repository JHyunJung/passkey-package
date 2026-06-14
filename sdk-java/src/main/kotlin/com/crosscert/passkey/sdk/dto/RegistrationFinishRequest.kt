package com.crosscert.passkey.sdk.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

data class RegistrationFinishRequest(
    @get:JvmName("registrationToken") @get:JsonProperty("registrationToken") @param:JsonProperty("registrationToken") val registrationToken: String,
    @get:JvmName("publicKeyCredential") @get:JsonProperty("publicKeyCredential") @param:JsonProperty("publicKeyCredential") val publicKeyCredential: JsonNode,
)
