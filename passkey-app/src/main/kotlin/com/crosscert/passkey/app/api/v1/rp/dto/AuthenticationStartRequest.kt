package com.crosscert.passkey.app.api.v1.rp.dto

/** `userHandle` 은 검증 없음 — null/누락이면 usernameless(discoverable credential) 흐름. */
data class AuthenticationStartRequest(
    val userHandle: String?,
)
