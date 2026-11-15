package com.crosscert.passkey.admin.auth

import jakarta.servlet.http.HttpServletRequest

/**
 * 로그인 직후 세션에 붙는 2단계 인증 관련 상태 속성의 읽기/지우기 헬퍼.
 *
 * - [MfaPendingFilter.MFA_PENDING_ATTR]: 이미 MFA 를 켠 운영자가 TOTP 검증을 기다리는 상태.
 * - [ENROLL_REQUIRED_ATTR]: 보안 정책상 MFA 가 필수인데 아직 등록하지 않은 운영자가
 *   등록을 마칠 때까지 막히는 상태. 두 속성은 동시에 붙지 않는다.
 *
 * 붙이는 곳은 비밀번호 로그인 성공 처리기(AdminSecurityConfig.adminLoginSuccessHandler)다 — MFA 를 켠 계정이면
 * `MFA_PENDING`, 아니고 정책이 MFA 필수면(정책 조회 실패도 필수로 본다) `MFA_ENROLL_REQUIRED`.
 * `MFA_PENDING` 은 [MfaController.verify] 성공이 지우고, 실패로 계정이 잠기면 verify 가 세션째 무효화한다.
 * `MFA_ENROLL_REQUIRED` 는 [MfaController.confirm] 성공이 지운다. 그사이 다른 세션에서 등록을 마친 계정이면
 * LoginPageController 가 `MFA_ENROLL_REQUIRED` 를 `MFA_PENDING` 으로 바꾼다. 어느 쪽이든 붙어 있는 동안
 * [MfaPendingFilter] 가 허용 목록 밖 요청을 막는다.
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
