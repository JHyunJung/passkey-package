package com.crosscert.passkey.sdk.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

data class AuthenticationFinishRequest(
    @get:JvmName("authenticationToken") @get:JsonProperty("authenticationToken") @param:JsonProperty("authenticationToken") val authenticationToken: String,
    @get:JvmName("publicKeyCredential") @get:JsonProperty("publicKeyCredential") @param:JsonProperty("publicKeyCredential") val publicKeyCredential: JsonNode,
)
