package com.crosscert.passkey.app.fido2.challenge

import java.time.Instant

/**
 * Redis 에 JSON 으로 저장되는 인증 challenge. 필드명이 저장 형식이므로 바꾸지 않는다.
 * ByteArray 필드의 equals/hashCode 는 참조 비교(원 Java record 와 동일) — 값 비교에 쓰지 않는다.
 */
@Suppress("ArrayInDataClass")
data class AuthenticationChallenge(
    val tenantId: String,
    val challenge: ByteArray,
    val userHandle: ByteArray?, // usernameless 흐름이면 null
    val issuedAt: Instant,
)
