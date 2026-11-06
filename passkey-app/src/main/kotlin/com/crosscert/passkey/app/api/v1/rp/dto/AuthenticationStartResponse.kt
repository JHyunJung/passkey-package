package com.crosscert.passkey.app.api.v1.rp.dto

import tools.jackson.databind.JsonNode

data class AuthenticationStartResponse(
    val authenticationToken: String,
    val publicKeyCredentialRequestOptions: JsonNode,
)
