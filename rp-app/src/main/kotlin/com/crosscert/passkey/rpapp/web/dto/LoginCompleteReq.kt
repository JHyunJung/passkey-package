package com.crosscert.passkey.rpapp.web.dto

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class LoginCompleteReq(
    @field:NotNull val publicKeyCredential: JsonNode?,
    @field:NotBlank val authenticationToken: String?,
)
