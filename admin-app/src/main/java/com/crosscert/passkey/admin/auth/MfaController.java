package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;

/**
 * Second-factor (TOTP) endpoints for the admin SPA.
 *
 * <p>Both endpoints require an authenticated session ({@code /admin/api/**} is
 * {@code authenticated()} in {@code AdminSecurityConfig}) and are reachable
 * while a session is MFA-pending because {@code MfaPendingFilter} allow-lists
 * {@code /admin/api/mfa/**}. CSRF stays enabled for both — the SPA already
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

    public MfaController(TotpService totp, AdminUserRepository users, Clock clock) {
        this.totp = totp;
        this.users = users;
        this.clock = clock;
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
        String code = body == null ? null : body.code();
        if (!validCode(u, code)) {
            log.warn("admin mfa verify failed: email={}", mask(email));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_code"));
        }
        var session = req.getSession(false);
        if (session != null) {
            session.removeAttribute(MfaPendingFilter.MFA_PENDING_ATTR);
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
    public ResponseEntity<?> enroll(Authentication auth) {
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
        u.setMfaSecret(secret);
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
    public ResponseEntity<?> confirm(@RequestBody VerifyRequest body, Authentication auth) {
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
        return ResponseEntity.ok(Map.of("confirmed", true));
    }

    /**
     * Disable MFA: verify a TOTP code against the stored secret and, on success,
     * set {@code mfa_enabled=N} and clear the stored secret. Returns
     * {@code 401 {"error":"invalid_code"}} on any failure.
     */
    @PostMapping("/disable")
    public ResponseEntity<?> disable(@RequestBody VerifyRequest body, Authentication auth) {
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
     * Shared TOTP gate for verify/confirm/disable: true only when the user row
     * exists, has an enrolled secret, a code was supplied, and the code matches
     * the current time window. Centralised so the security-critical predicate
     * cannot drift between the three call sites.
     */
    private boolean validCode(AdminUser u, String code) {
        return u != null && u.getMfaSecret() != null && code != null
                && totp.verifyAt(u.getMfaSecret(), code, clock.millis());
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
