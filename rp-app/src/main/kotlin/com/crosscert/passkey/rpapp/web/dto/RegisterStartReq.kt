package com.crosscert.passkey.rpapp.web.dto

import jakarta.validation.constraints.NotBlank

data class RegisterStartReq(
    @field:NotBlank val username: String?,
    @field:NotBlank val displayName: String?,
)
