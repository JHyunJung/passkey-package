package com.crosscert.passkey.app.fido2.challenge

import java.time.Instant

/**
 * Redis 에 JSON 으로 저장되는 등록 challenge. 필드명이 저장 형식이므로 바꾸지 않는다.
 * ByteArray 필드의 equals/hashCode 는 참조 비교(원 Java record 와 동일) — 값 비교에 쓰지 않는다.
 */
@Suppress("ArrayInDataClass")
data class RegistrationChallenge(
    val tenantId: String,
    val challenge: ByteArray,
    val userHandle: ByteArray,
    // 요청 DTO 의 nullable 값을 그대로 담는다(검증은 컨트롤러 @Valid 몫) — 저장만 하고 읽지 않는다.
    val displayName: String?,
    val username: String?,
    val issuedAt: Instant,
)
