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
        boolean ok = u != null
                && u.getMfaSecret() != null
                && code != null
                && totp.verifyAt(u.getMfaSecret(), code, clock.millis());
        if (!ok) {
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
     * Generate + store a fresh TOTP secret for the current operator and enable
     * MFA. Returns the Base32 secret so the operator can add it to an
     * authenticator app.
     *
     * <p><b>Does NOT lock the current session.</b> Enrollment only sets the
     * stored secret + {@code mfa_enabled=Y}; it does not set {@code MFA_PENDING}
     * for the already-authenticated session. The second-factor gate engages on
     * the operator's NEXT login. This avoids locking an operator out of the
     * very session they used to enroll (before they have confirmed the secret
     * works in their app).
     */
    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(Authentication auth) {
        String email = auth.getName();
        AdminUser u = users.findByEmail(email).orElseThrow();
        String secret = totp.newSecretBase32();
        u.setMfaSecret(secret);
        u.setMfaEnabled(true);
        users.save(u);
        log.info("admin mfa enrolled: email={}", mask(email));
        return ResponseEntity.ok(Map.of("secret", secret));
    }

    public record VerifyRequest(String code) {}

    private static String mask(String email) {
        if (email == null || email.isBlank()) return "(unknown)";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
