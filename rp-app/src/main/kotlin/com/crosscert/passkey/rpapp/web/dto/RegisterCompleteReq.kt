package com.crosscert.passkey.rpapp.web.dto

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class RegisterCompleteReq(
    @field:NotNull val publicKeyCredential: JsonNode?,
    @field:NotBlank val regRelayToken: String?,
)
