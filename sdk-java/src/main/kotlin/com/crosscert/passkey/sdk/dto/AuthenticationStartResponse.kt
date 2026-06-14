package com.crosscert.passkey.sdk.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuthenticationStartResponse(
    @get:JvmName("authenticationToken") @get:JsonProperty("authenticationToken") @param:JsonProperty("authenticationToken") val authenticationToken: String,
    @get:JvmName("publicKeyCredentialRequestOptions") @get:JsonProperty("publicKeyCredentialRequestOptions") @param:JsonProperty("publicKeyCredentialRequestOptions") val publicKeyCredentialRequestOptions: JsonNode,
)
