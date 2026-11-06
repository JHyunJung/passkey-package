package com.crosscert.passkey.app.api.v1.rp.dto

data class AuthenticationFinishResponse(
    val idToken: String,
    val tokenType: String,
    val expiresIn: Long,
)
