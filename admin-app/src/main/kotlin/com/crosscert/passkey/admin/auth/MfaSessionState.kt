package com.crosscert.passkey.admin.auth

import jakarta.servlet.http.HttpServletRequest

/**
 * 로그인 직후 세션에 붙는 2단계 인증 관련 상태 속성의 읽기/지우기 헬퍼.
 *
 * - [MfaPendingFilter.MFA_PENDING_ATTR]: 이미 MFA 를 켠 운영자가 TOTP 검증을 기다리는 상태.
 * - [ENROLL_REQUIRED_ATTR]: 보안 정책상 MFA 가 필수인데 아직 등록하지 않은 운영자가
 *   등록을 마칠 때까지 막히는 상태. 두 속성은 동시에 붙지 않는다.
 *
 * 세션이 없으면(`getSession(false)` == null) 어느 상태도 아니다 — 세션을 새로 만들지 않는다.
 */
object MfaSessionState {
    const val ENROLL_REQUIRED_ATTR = "MFA_ENROLL_REQUIRED"

    fun isPending(req: HttpServletRequest): Boolean =
        java.lang.Boolean.TRUE == req.getSession(false)?.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)

    fun isEnrollRequired(req: HttpServletRequest): Boolean =
        java.lang.Boolean.TRUE == req.getSession(false)?.getAttribute(ENROLL_REQUIRED_ATTR)

    fun clearEnrollRequired(req: HttpServletRequest) {
        req.getSession(false)?.removeAttribute(ENROLL_REQUIRED_ATTR)
    }
}
