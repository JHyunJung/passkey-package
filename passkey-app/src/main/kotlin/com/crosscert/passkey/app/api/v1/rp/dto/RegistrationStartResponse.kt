package com.crosscert.passkey.app.api.v1.rp.dto

import tools.jackson.databind.JsonNode

data class RegistrationStartResponse(
    val registrationToken: String,
    val publicKeyCredentialCreationOptions: JsonNode,
)
