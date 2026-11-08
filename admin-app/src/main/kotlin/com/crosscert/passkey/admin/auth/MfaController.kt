package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

private val log = LoggerFactory.getLogger(MfaController::class.java)

/**
 * Second-factor (TOTP) endpoints for the admin SPA.
 *
 * Both endpoints require an authenticated session (`/admin/api/\*\*` is
 * `authenticated()` in `AdminSecurityConfig`). While a session is
 * MFA-pending, `MfaPendingFilter` allow-lists only
 * `/admin/api/mfa/verify` (exact match); enroll/confirm/disable are
 * rejected until MFA is satisfied. CSRF stays enabled for both — the SPA already
 * holds the `XSRF-TOKEN` cookie from the (pending) session, so adding
 * these to the CSRF ignore list would needlessly weaken the surface.
 */
@RestController
@RequestMapping("/admin/api/mfa")
class MfaController(
    private val totp: TotpService,
    private val users: AdminUserRepository,
    private val clock: Clock,
    private val recoveryCodes: RecoveryCodeService,
    private val secretCipher: MfaSecretCipher,
    @Value("\${passkey.admin.lockout.max-attempts:5}") private val maxAttempts: Int,
    @Value("\${passkey.admin.lockout.duration:PT15M}") private val lockDuration: Duration,
) {

    /**
     * Verify a TOTP code and, on success, clear the MFA-pending flag so the
     * session can reach the rest of `/admin/api/\*\*`. Returns
     * `401 {"error":"invalid_code"}` on any failure (no secret, wrong
     * code) without distinguishing the two, to avoid leaking enrollment state.
     *
     * `body` non-null: `@RequestBody` 파라미터를 nullable 로 두면 Spring 이 optional 로 보고 빈 본문을
     * 400 대신 null 로 넘긴다. HTTP 경로에서 원 Java 의 `body == null` 분기는 도달 불가(required)라 뺀다.
     */
    @PostMapping("/verify")
    fun verify(
        @RequestBody body: VerifyRequest,
        auth: Authentication,
        req: HttpServletRequest,
    ): ResponseEntity<*> {
        val email: String? = auth.name
        val u: AdminUser? = users.findByEmail(email).orElse(null)

        // Brute-force lockout: MFA failures accrue against their OWN counter
        // (mfaFailedCount, G05) so a password-only login success can never
        // reset second-factor brute-force progress — only trips the shared
        // lockedUntil field on threshold. A locked account is refused before
        // any code check (fail-closed). The lock also gates primary login via
        // AdminUserDetails.isAccountNonLocked — acceptable because reaching
        // here means the password is already compromised.
        if (u != null && u.isLocked(OffsetDateTime.now(clock))) {
            log.warn("admin mfa verify blocked (locked): email={}", mask(email))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(java.util.Map.of("error", "invalid_code"))
        }

        val code: String? = body.code
        val totpOk = validCode(u, code)
        var recoveryOk = false
        if (!totpOk && u != null) {
            recoveryOk = recoveryCodes.consume(u.id, code)
        }
        if (!totpOk && !recoveryOk) {
            if (u != null) {
                u.recordFailedMfa(OffsetDateTime.now(clock), maxAttempts, lockDuration)
                users.save(u)
                // recordFailedMfa auto-locks once maxAttempts is reached. The
                // entry guard above already refused any already-locked account,
                // so reaching a locked state here means this failure tripped it.
                if (u.isLocked(OffsetDateTime.now(clock))) {
                    val s = req.getSession(false)
                    s?.invalidate() // kill the pending session on lockout
                    log.warn("admin mfa verify lockout triggered: email={}", mask(email))
                }
            }
            log.warn("admin mfa verify failed: email={}", mask(email))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(java.util.Map.of("error", "invalid_code"))
        }
        // 여기 도달 = totpOk(validCode 가 u != null 확인) 또는 recoveryOk(u != null 일 때만 계산) — u 는 non-null.
        // 원 Java 도 같은 지점(u.recordSuccessfulMfa())에서 null 이면 NPE 였다.
        val user = u!!
        user.recordSuccessfulMfa() // reset only the MFA counter on success (G05)
        users.save(user)
        val session = req.getSession(false)
        session?.removeAttribute(MfaPendingFilter.MFA_PENDING_ATTR)
        if (recoveryOk) {
            val left = recoveryCodes.remaining(user.id)
            log.warn("admin mfa verify via recovery code: email={} remaining={}", mask(email), left)
            return ResponseEntity.ok(
                java.util.Map.of("verified", true, "usedRecoveryCode", true, "remaining", left),
            )
        }
        log.info("admin mfa verify success: email={}", mask(email))
        return ResponseEntity.ok(java.util.Map.of("verified", true))
    }

    /**
     * Generate + store a fresh TOTP secret for the current operator, but leave
     * MFA **disabled** until [confirm] verifies the operator's app
     * produces a matching code. Returns the Base32 `secret` plus an
     * `otpauthUri` for QR provisioning.
     *
     * **Does NOT enable MFA and does NOT lock the current session.**
     * Setting `mfa_enabled=N` here is deliberate: an operator who
     * re-enrolls (overwriting their old secret) but abandons the flow before
     * confirming would otherwise have a fresh secret with `enabled=Y` —
     * their next login could no longer pass with the old authenticator entry.
     * Keeping enrollment disabled-until-confirmed closes that lock-out window.
     */
    @PostMapping("/enroll")
    fun enroll(auth: Authentication, req: HttpServletRequest): ResponseEntity<*> {
        if (isPending(req)) return mfaRequired()
        val email: String? = auth.name
        // Graceful, non-500 handling consistent with verify(): an authenticated
        // principal whose backing row is gone (concurrent delete / stale session)
        // gets 401 rather than a bare NoSuchElementException → 500.
        val u: AdminUser? = users.findByEmail(email).orElse(null)
        if (u == null) {
            log.warn("admin mfa enroll: no user row for authenticated principal email={}", mask(email))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(java.util.Map.of("error", "unauthorized"))
        }
        val secret = totp.newSecretBase32()
        u.mfaSecret = secretCipher.seal(secret)
        u.isMfaEnabled = false // not enabled until confirm() verifies a code
        users.save(u)
        val issuer = "Passkey Admin"
        val otpauthUri = "otpauth://totp/" + enc(issuer) + ":" + enc(email) +
            "?secret=" + secret + "&issuer=" + enc(issuer)
        log.info("admin mfa enroll (pending confirm): email={}", mask(email))
        return ResponseEntity.ok(java.util.Map.of("secret", secret, "otpauthUri", otpauthUri))
    }

    /**
     * Confirm enrollment: verify a TOTP code against the stored secret and, on
     * success, set `mfa_enabled=Y`. Returns `401 {"error":"invalid_code"}`
     * on any failure (no secret, wrong/absent code), mirroring [verify].
     */
    @PostMapping("/confirm")
    fun confirm(
        @RequestBody body: VerifyRequest,
        auth: Authentication,
        req: HttpServletRequest,
    ): ResponseEntity<*> {
        if (isPending(req)) return mfaRequired()
        val email: String? = auth.name
        val u: AdminUser? = users.findByEmail(email).orElse(null)
        val code: String? = body.code
        if (u == null || !validCode(u, code)) {
            log.warn("admin mfa confirm failed: email={}", mask(email))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(java.util.Map.of("error", "invalid_code"))
        }
        u.isMfaEnabled = true
        users.save(u)
        log.info("admin mfa confirmed: email={}", mask(email))
        val codes = recoveryCodes.generate(u.id)
        return ResponseEntity.ok(java.util.Map.of("confirmed", true, "recoveryCodes", codes))
    }

    /**
     * Disable MFA: verify a TOTP code against the stored secret and, on success,
     * set `mfa_enabled=N` and clear the stored secret. Returns
     * `401 {"error":"invalid_code"}` on any failure.
     */
    @PostMapping("/disable")
    fun disable(
        @RequestBody body: VerifyRequest,
        auth: Authentication,
        req: HttpServletRequest,
    ): ResponseEntity<*> {
        if (isPending(req)) return mfaRequired()
        val email: String? = auth.name
        val u: AdminUser? = users.findByEmail(email).orElse(null)
        val code: String? = body.code
        if (u == null || !validCode(u, code)) {
            log.warn("admin mfa disable failed: email={}", mask(email))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(java.util.Map.of("error", "invalid_code"))
        }
        u.isMfaEnabled = false
        u.mfaSecret = null
        users.save(u)
        log.info("admin mfa disabled: email={}", mask(email))
        return ResponseEntity.ok(java.util.Map.of("disabled", true))
    }

    data class VerifyRequest(val code: String?)

    /**
     * Shared TOTP gate for verify/confirm/disable: true only when the user row
     * exists, has an enrolled secret, a code was supplied, the code matches
     * the current time window, AND the matched time-step has not already been
     * consumed by an earlier successful check (RFC 6238 §5.2 replay guard —
     * F02). Centralised so the security-critical predicate cannot drift
     * between the three call sites.
     *
     * On a fresh match this advances `u.mfaLastTotpStep` in memory;
     * callers persist it via the `users.save(u)` they already perform on
     * their success path. A replayed step (matched counter `<=` the
     * stored high-water mark) is treated exactly like a wrong code — this
     * method returns `false` and does not mutate `u`.
     */
    private fun validCode(u: AdminUser?, code: String?): Boolean {
        if (u == null || u.mfaSecret == null || code == null) return false
        val plain = secretCipher.open(u.mfaSecret)
        val matched = totp.matchedCounter(plain, code, clock.millis())
        if (matched.isEmpty) return false
        val step = matched.asLong
        if (u.isTotpStepReplayed(step)) {
            log.warn("admin mfa code replay rejected: email={}", mask(u.email))
            return false
        }
        u.mfaLastTotpStep = step
        return true
    }
}

/**
 * Reject enroll/confirm/disable while the session is still MFA-pending.
 * Defense-in-depth behind MfaPendingFilter (which already blocks these
 * paths) — if the filter is ever bypassed or reordered, the handler still
 * refuses to mutate the secret from a password-only session.
 */
private fun isPending(req: HttpServletRequest): Boolean {
    // session==null means no session exists → request is unauthenticated
    // and rejected upstream by Spring Security; treat as "not pending".
    val session = req.getSession(false)
    return session != null &&
        java.lang.Boolean.TRUE == session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)
}

private fun mfaRequired(): ResponseEntity<*> =
    ResponseEntity.status(HttpStatus.FORBIDDEN).body(java.util.Map.of("error", "mfa_required"))

private fun enc(s: String?): String =
    // otpauth URIs use percent-encoding; URLEncoder emits '+' for spaces
    // (application/x-www-form-urlencoded), so normalise '+' to %20.
    URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

private fun mask(email: String?): String {
    // Java String.isBlank 와 같은 판정(Character.isWhitespace) — Kotlin isBlank 는 NBSP 등도 공백으로 본다.
    if (email == null || email.all(Character::isWhitespace)) return "(unknown)"
    val at = email.indexOf('@')
    if (at <= 0) return "***"
    return "${email[0]}***${email.substring(at)}"
}
