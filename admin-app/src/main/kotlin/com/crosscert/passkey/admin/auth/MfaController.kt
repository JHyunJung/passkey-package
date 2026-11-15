package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.policy.SecurityPolicyService
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
 * Second-factor (TOTP) endpoints.
 *
 * All endpoints require an authenticated session (`/admin/api/\*\*` is
 * `authenticated()` in `AdminSecurityConfig`). While a session is
 * MFA-pending, `MfaPendingFilter` allow-lists only
 * `/admin/api/mfa/verify` (exact match); the others are
 * rejected until MFA is satisfied. CSRF stays enabled for all — the client already
 * holds the `XSRF-TOKEN` cookie from the (pending) session, so adding
 * these to the CSRF ignore list would needlessly weaken the surface.
 *
 * 화면은 이 엔드포인트를 HTTP 로 부르지 않고 메서드로 직접 부른 뒤 ResponseEntity 의 상태·본문을 해석한다
 * (로그인 MFA 단계·등록은 LoginPageController, 설정 › 내 계정 탭은 AccountTabController).
 */
@RestController
@RequestMapping("/admin/api/mfa")
class MfaController(
    private val totp: TotpService,
    private val users: AdminUserRepository,
    private val clock: Clock,
    private val recoveryCodes: RecoveryCodeService,
    private val secretCipher: MfaSecretCipher,
    private val audit: AuditLogService,
    private val securityPolicy: SecurityPolicyService,
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
     * 400 대신 null 로 넘긴다. 지금은 빈 본문이 GlobalExceptionHandler 의 400 "Malformed JSON request" 다.
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
        // (mfaFailedCount) so a password-only login success can never
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
        val user = u!!
        user.recordSuccessfulMfa() // reset only the MFA counter on success
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
     *
     * An account that already has MFA **enabled** gets `409 {"error":"mfa_already_enabled"}` with the
     * secret and flag untouched: otherwise a password-only or stale session could swap the secret and
     * leave `mfa_enabled=N` without any TOTP proof (bypassing the policy's disable-409). The UI only
     * offers enroll while MFA is off; to re-enroll, turn MFA off first (TOTP-gated).
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
        if (u.isMfaEnabled) {
            log.warn("admin mfa enroll refused (already enabled): email={}", mask(email))
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(java.util.Map.of("error", "mfa_already_enabled"))
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
        // 강제 등록 여부는 해제 전에 읽는다. 등록은 이미 끝났으므로 감사 실패가 응답을 막거나 되돌리지 않는다.
        val forced = MfaSessionState.isEnrollRequired(req)
        MfaSessionState.clearEnrollRequired(req)
        try {
            audit.append(
                AuditAppendRequest(
                    u.id, email, "MFA_ENROLL",
                    "ADMIN_USER", u.id.toString(),
                    null,
                    java.util.Map.of("forced", forced),
                ),
            )
        } catch (e: Exception) {
            log.error("admin mfa enroll audit failed: {}", e.javaClass.simpleName)
        }
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
        // 정책 판정은 TOTP 검증보다 먼저 — 거부 시 코드 시도·mfaLastTotpStep 이 소모되지 않는다.
        // 정책 조회 실패(행 없음·CORS JSON 손상 등)는 fail-closed: 끄기를 거부한다.
        val policyRequiresMfa = try {
            securityPolicy.get().mfaRequired
        } catch (e: Exception) {
            log.warn("admin mfa disable: policy read failed, refusing (fail-closed): {}", e.toString())
            true
        }
        if (policyRequiresMfa) {
            log.warn("admin mfa disable refused (policy requires mfa): email={}", mask(auth.name))
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(java.util.Map.of("error", "mfa_policy_required"))
        }
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

    /**
     * Regenerate recovery codes while MFA stays enabled — for an operator who still has the
     * authenticator but lost the saved codes. Proof is the current TOTP code only (a recovery code
     * is not accepted: someone holding a leaked code must not be able to mint a fresh set).
     * On success every previous code is discarded ([RecoveryCodeService.generate] deletes first)
     * and the new plain codes are returned once in the body. The consumed time-step is persisted
     * (replay guard), and an `MFA_RECOVERY_REGENERATE` audit row is appended first (no code values).
     *
     * `401 {"error":"invalid_code"}` on a wrong/absent code, `400 {"error":"mfa_not_enabled"}`
     * when MFA is off (codes would be useless — turn MFA on instead).
     */
    @PostMapping("/recovery-codes")
    fun regenerateRecoveryCodes(
        @RequestBody body: VerifyRequest,
        auth: Authentication,
        req: HttpServletRequest,
    ): ResponseEntity<*> {
        if (isPending(req)) return mfaRequired()
        val email: String? = auth.name
        val u: AdminUser? = users.findByEmail(email).orElse(null)
        if (u == null || !validCode(u, body.code)) {
            log.warn("admin mfa recovery-code regenerate failed: email={}", mask(email))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(java.util.Map.of("error", "invalid_code"))
        }
        if (!u.isMfaEnabled) {
            log.warn("admin mfa recovery-code regenerate refused (mfa off): email={}", mask(email))
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "mfa_not_enabled"))
        }
        users.save(u) // persist mfaLastTotpStep advanced by validCode
        // Audit BEFORE generate: if the audit append fails nothing has changed (old codes still valid).
        // The reverse order could discard the old codes and then fail before the new ones reach the operator.
        audit.append(
            AuditAppendRequest(
                u.id, email, "MFA_RECOVERY_REGENERATE",
                "ADMIN_USER", u.id.toString(),
                null,
                java.util.Map.of(),
            ),
        )
        val codes = recoveryCodes.generate(u.id)
        log.info("admin mfa recovery codes regenerated: email={}", mask(email))
        return ResponseEntity.ok(java.util.Map.of("recoveryCodes", codes))
    }

    data class VerifyRequest(val code: String?)

    /**
     * Shared TOTP gate for verify/confirm/disable/recovery-codes: true only when the user row
     * exists, has an enrolled secret, a code was supplied, the code matches
     * the current time window, AND the matched time-step has not already been
     * consumed by an earlier successful check (RFC 6238 §5.2 replay guard).
     * Centralised so the security-critical predicate cannot drift
     * between the call sites.
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
 * Reject enroll/confirm/disable/recovery-codes while the session is still MFA-pending.
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
