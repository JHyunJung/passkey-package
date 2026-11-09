package com.crosscert.passkey.admin.operator

import org.springframework.core.env.Environment

/**
 * G11 fail-fast guard shared by [PasswordResetService] and
 * [SignupRequestService] — both read `admin.invite.base-url`
 * and both mail out live links built from it (reset-password / signup approval).
 *
 * The property's dev-convenience default (`http://localhost:5173`)
 * lets the app boot successfully even when a prod deployment forgets to
 * inject `ADMIN_INVITE_BASE_URL`. The mailed link then points at the
 * operator's own loopback address, never reaches the real front-end, and the
 * failure is invisible until an operator actually clicks the link — by which
 * point they may be trying to recover from a lockout. Fail fast at boot
 * instead: only the "prod" profile is guarded (this is where the localhost
 * default is actually unsafe; dev/local/qa intentionally rely on it).
 *
 * 원 Java 는 package-private final 클래스 — Kotlin 에 package-private 이 없어 internal 로 둔다.
 */
internal object BaseUrlValidation {

    const val LOCALHOST_DEFAULT = "http://localhost:5173"

    fun assertNotLocalhostFallbackInProd(env: Environment, baseUrl: String?, propertyName: String) {
        var isProd = false
        for (p in env.activeProfiles) {
            if ("prod" == p) {
                isProd = true
                break
            }
        }
        if (isProd && (baseUrl == null || baseUrl.startsWith("http://localhost") ||
                baseUrl.startsWith("https://localhost"))
        ) {
            throw IllegalStateException(
                "prod profile requires " + propertyName +
                    " to be set to the real front-end origin — refusing to boot with the " +
                    "localhost dev default (" + baseUrl + "). Set ADMIN_INVITE_BASE_URL.",
            )
        }
    }
}
