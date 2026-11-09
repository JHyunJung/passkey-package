package com.crosscert.passkey.admin.operator

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Self-service password reset 엔드포인트 (P1-6).
 *
 * 두 엔드포인트 모두 미인증(permitAll). request 는 항상 200(enumeration 방지).
 * confirm 실패는 PasswordResetService 가 IllegalArgumentException → 400 으로 매핑.
 */
@RestController
@RequestMapping("/admin/api/password-reset")
class PasswordResetController(
    private val service: PasswordResetService,
) {

    /**
     * body 는 non-null(@RequestBody required — nullable 이면 Spring 이 선택 본문으로 본다). 원 Java 의
     * `body == null` 분기는 Spring 경로에서 도달 불가였고, 유일한 다른 호출자(PasswordPagesController)도 새 객체를 넘긴다.
     */
    @PostMapping("/request")
    fun request(@RequestBody body: RequestBodyDto): ResponseEntity<*> {
        val email = body.email
        // Java String.isBlank 와 같은 판정(Character.isWhitespace) — Kotlin isBlank 는 NBSP 등도 공백으로 본다.
        if (email != null && !email.all(Character::isWhitespace)) {
            service.request(email)
        }
        return ResponseEntity.ok(java.util.Map.of("ok", true))
    }

    @PostMapping("/confirm")
    fun confirm(@RequestBody body: ConfirmBody): ResponseEntity<*> {
        service.confirm(body.token, body.newPassword)
        return ResponseEntity.ok(java.util.Map.of("reset", true))
    }

    data class RequestBodyDto(val email: String?)

    data class ConfirmBody(val token: String?, val newPassword: String?)
}
