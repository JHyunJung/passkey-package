package com.crosscert.passkey.admin.operator

import java.nio.charset.StandardCharsets

/** 관리자 비밀번호 최소 글자 수. */
internal const val PASSWORD_MIN = 12

/**
 * 최대 글자 수 — BCrypt 상한(72바이트)과 같은 수. 글자 수 검사(@Size·화면 maxlength)용 상한이고,
 * 다바이트 문자는 [PasswordPolicy.accepts] 의 바이트 검사가 따로 거른다(글자 수 ≤ 바이트 수).
 */
internal const val PASSWORD_MAX = 72

/** BCrypt 가 받는 최대 입력(UTF-8 바이트). 넘으면 encode 가 영어 원문 IllegalArgumentException 을 던진다. */
internal const val PASSWORD_MAX_BYTES = 72

/** 사용자에게 보이는 규칙 문구. 한글은 UTF-8 3바이트라 24자가 72바이트다. */
internal const val PASSWORD_RULE = "12자 이상, 영문 72자(한글 24자) 이하"

/** 가입·비밀번호 재설정이 공유하는 비밀번호 규칙. */
internal object PasswordPolicy {

    fun accepts(password: String?): Boolean =
        password != null &&
            password.length >= PASSWORD_MIN &&
            password.toByteArray(StandardCharsets.UTF_8).size <= PASSWORD_MAX_BYTES

    /** 규칙을 어기면 사용자용 한국어 메시지로 IllegalArgumentException(GlobalExceptionHandler → 400). */
    fun require(password: String?) {
        require(accepts(password)) { "비밀번호는 ${PASSWORD_RULE}로 입력하세요." }
    }
}
