package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Second-factor (TOTP) endpoints for the admin SPA.
 *
 * <p>Both endpoints require an authenticated session ({@code /admin/api/**} is
 * {@code authenticated()} in {@code AdminSecurityConfig}). While a session is
 * MFA-pending, {@code MfaPendingFilter} allow-lists only
 * {@code /admin/api/mfa/verify} (exact match); enroll/confirm/disable are
 * rejected until MFA is satisfied. CSRF stays enabled for both — the SPA already
 * holds the {@code XSRF-TOKEN} cookie from the (pending) session, so adding
 * these to the CSRF ignore list would needlessly weaken the surface.
 */
@RestController
@RequestMapping("/admin/api/mfa")
public class MfaController {

    private static final Logger log = LoggerFactory.getLogger(MfaController.class);

    private final TotpService totp;
    private final AdminUserRepository users;
    private final Clock clock;
    private final RecoveryCodeService recoveryCodes;
    private final MfaSecretCipher secretCipher;
    private final int maxAttempts;
    private final Duration lockDuration;

    public MfaController(TotpService totp, AdminUserRepository users, Clock clock,
                         RecoveryCodeService recoveryCodes, MfaSecretCipher secretCipher,
                         @Value("${passkey.admin.lockout.max-attempts:5}") int maxAttempts,
                         @Value("${passkey.admin.lockout.duration:PT15M}") Duration lockDuration) {
        this.totp = totp;
        this.users = users;
        this.clock = clock;
        this.recoveryCodes = recoveryCodes;
        this.secretCipher = secretCipher;
        this.maxAttempts = maxAttempts;
        this.lockDuration = lockDuration;
    }

    /**
     * Verify a TOTP code and, on success, clear the MFA-pending flag so the
     * session can reach the rest of {@code /admin/api/**}. Returns
     * {@code 401 {"error":"invalid_code"}} on any failure (no secret, wrong
     * code) without distinguishing the two, to avoid leaking enrollment state.
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody VerifyRequest body,
                                    Authentication auth,
                                    HttpServletRequest req) {
        String email = auth.getName();
        AdminUser u = users.findByEmail(email).orElse(null);

        // Brute-force lockout: reuse the primary-login lockout fields so a
        // throttle exists on the second factor too. A locked account is
        // refused before any code check (fail-closed). The lock also gates
        // primary login via AdminUserDetails.isAccountNonLocked — acceptable
        // because reaching here means the password is already compromised.
        if (u != null && u.isLocked(clock.instant())) {
            log.warn("admin mfa verify blocked (locked): email={}", mask(email));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_code"));
        }

        String code = body == null ? null : body.code();
        boolean totpOk = validCode(u, code);
        boolean recoveryOk = false;
        if (!totpOk && u != null) {
            recoveryOk = recoveryCodes.consume(u.getId(), code);
        }
        if (!totpOk && !recoveryOk) {
            if (u != null) {
                u.recordFailedLogin(clock.instant(), maxAttempts, lockDuration);
                users.save(u);
                // recordFailedLogin auto-locks once maxAttempts is reached. The
                // entry guard above already refused any already-locked account,
                // so reaching a locked state here means this failure tripped it.
                if (u.isLocked(clock.instant())) {
                    var s = req.getSession(false);
                    if (s != null) s.invalidate();   // kill the pending session on lockout
                    log.warn("admin mfa verify lockout triggered: email={}", mask(email));
                }
            }
            log.warn("admin mfa verify failed: email={}", mask(email));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_code"));
        }
        u.recordSuccessfulLogin();   // reset shared failed-login counter on success
        users.save(u);
        var session = req.getSession(false);
        if (session != null) {
            session.removeAttribute(MfaPendingFilter.MFA_PENDING_ATTR);
        }
        if (recoveryOk) {
            long left = recoveryCodes.remaining(u.getId());
            log.warn("admin mfa verify via recovery code: email={} remaining={}", mask(email), left);
            return ResponseEntity.ok(Map.of("verified", true, "usedRecoveryCode", true, "remaining", left));
        }
        log.info("admin mfa verify success: email={}", mask(email));
        return ResponseEntity.ok(Map.of("verified", true));
    }

    /**
     * Generate + store a fresh TOTP secret for the current operator, but leave
     * MFA <b>disabled</b> until {@link #confirm} verifies the operator's app
     * produces a matching code. Returns the Base32 {@code secret} plus an
     * {@code otpauthUri} for QR provisioning.
     *
     * <p><b>Does NOT enable MFA and does NOT lock the current session.</b>
     * Setting {@code mfa_enabled=N} here is deliberate: an operator who
     * re-enrolls (overwriting their old secret) but abandons the flow before
     * confirming would otherwise have a fresh secret with {@code enabled=Y} —
     * their next login could no longer pass with the old authenticator entry.
     * Keeping enrollment disabled-until-confirmed closes that lock-out window.
     */
    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(Authentication auth, HttpServletRequest req) {
        if (isPending(req)) return mfaRequired();
        String email = auth.getName();
        // Graceful, non-500 handling consistent with verify(): an authenticated
        // principal whose backing row is gone (concurrent delete / stale session)
        // gets 401 rather than a bare NoSuchElementException → 500.
        AdminUser u = users.findByEmail(email).orElse(null);
        if (u == null) {
            log.warn("admin mfa enroll: no user row for authenticated principal email={}", mask(email));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        String secret = totp.newSecretBase32();
        u.setMfaSecret(secretCipher.seal(secret));
        u.setMfaEnabled(false); // not enabled until confirm() verifies a code
        users.save(u);
        String issuer = "Passkey Admin";
        String otpauthUri = "otpauth://totp/" + enc(issuer) + ":" + enc(email)
                + "?secret=" + secret + "&issuer=" + enc(issuer);
        log.info("admin mfa enroll (pending confirm): email={}", mask(email));
        return ResponseEntity.ok(Map.of("secret", secret, "otpauthUri", otpauthUri));
    }

    /**
     * Confirm enrollment: verify a TOTP code against the stored secret and, on
     * success, set {@code mfa_enabled=Y}. Returns {@code 401 {"error":"invalid_code"}}
     * on any failure (no secret, wrong/absent code), mirroring {@link #verify}.
     */
    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody VerifyRequest body, Authentication auth,
                                     HttpServletRequest req) {
        if (isPending(req)) return mfaRequired();
        String email = auth.getName();
        AdminUser u = users.findByEmail(email).orElse(null);
        String code = body == null ? null : body.code();
        if (!validCode(u, code)) {
            log.warn("admin mfa confirm failed: email={}", mask(email));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_code"));
        }
        u.setMfaEnabled(true);
        users.save(u);
        log.info("admin mfa confirmed: email={}", mask(email));
        var codes = recoveryCodes.generate(u.getId());
        return ResponseEntity.ok(Map.of("confirmed", true, "recoveryCodes", codes));
    }

    /**
     * Disable MFA: verify a TOTP code against the stored secret and, on success,
     * set {@code mfa_enabled=N} and clear the stored secret. Returns
     * {@code 401 {"error":"invalid_code"}} on any failure.
     */
    @PostMapping("/disable")
    public ResponseEntity<?> disable(@RequestBody VerifyRequest body, Authentication auth,
                                     HttpServletRequest req) {
        if (isPending(req)) return mfaRequired();
        String email = auth.getName();
        AdminUser u = users.findByEmail(email).orElse(null);
        String code = body == null ? null : body.code();
        if (!validCode(u, code)) {
            log.warn("admin mfa disable failed: email={}", mask(email));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_code"));
        }
        u.setMfaEnabled(false);
        u.setMfaSecret(null);
        users.save(u);
        log.info("admin mfa disabled: email={}", mask(email));
        return ResponseEntity.ok(Map.of("disabled", true));
    }

    public record VerifyRequest(String code) {}

    /**
     * Reject enroll/confirm/disable while the session is still MFA-pending.
     * Defense-in-depth behind MfaPendingFilter (which already blocks these
     * paths) — if the filter is ever bypassed or reordered, the handler still
     * refuses to mutate the secret from a password-only session.
     */
    private static boolean isPending(HttpServletRequest req) {
        // session==null means no session exists → request is unauthenticated
        // and rejected upstream by Spring Security; treat as "not pending".
        var session = req.getSession(false);
        return session != null
                && Boolean.TRUE.equals(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR));
    }

    private static ResponseEntity<?> mfaRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "mfa_required"));
    }

    /**
     * Shared TOTP gate for verify/confirm/disable: true only when the user row
     * exists, has an enrolled secret, a code was supplied, and the code matches
     * the current time window. Centralised so the security-critical predicate
     * cannot drift between the three call sites.
     */
    private boolean validCode(AdminUser u, String code) {
        if (u == null || u.getMfaSecret() == null || code == null) return false;
        String plain = secretCipher.open(u.getMfaSecret());
        return totp.verifyAt(plain, code, clock.millis());
    }

    private static String enc(String s) {
        // otpauth URIs use percent-encoding; URLEncoder emits '+' for spaces
        // (application/x-www-form-urlencoded), so normalise '+' to %20.
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String mask(String email) {
        if (email == null || email.isBlank()) return "(unknown)";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
