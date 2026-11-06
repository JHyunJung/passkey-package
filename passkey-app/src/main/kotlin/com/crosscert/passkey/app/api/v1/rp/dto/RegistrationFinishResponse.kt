package com.crosscert.passkey.app.api.v1.rp.dto

import java.time.Instant

data class RegistrationFinishResponse(
    val credentialId: String,
    /** AAGUID hex — 인증기가 AAGUID 를 주지 않으면 null(응답에서 생략). */
    val aaguid: String?,
    val attestationFormat: String,
    val createdAt: Instant,
)
