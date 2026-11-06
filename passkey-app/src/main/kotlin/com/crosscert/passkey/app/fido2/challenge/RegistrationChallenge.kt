package com.crosscert.passkey.app.fido2.challenge

import java.time.Instant

/**
 * Redis 에 JSON 으로 저장되는 등록 challenge. 필드명이 저장 형식이므로 바꾸지 않는다.
 * ByteArray 필드: 생성된 equals 는 참조 동일성 비교(원 Java record 와 같음), hashCode 는
 * 내용 기반(Arrays.hashCode — 원 record 의 identity hashCode 와 다름). 이 타입은 동등성 비교나
 * 해시 키(Set/Map)로 쓰이지 않는다. toString 도 배열 내용을 출력(원 record 는 [B@identity)하나 로그에 남기지 않는다.
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
