package com.crosscert.passkey.admin.operator;

import org.springframework.core.env.Environment;

/**
 * G11 fail-fast guard shared by {@link InvitationService} and
 * {@link PasswordResetService} — both read {@code admin.invite.base-url}
 * and both mail out live links built from it (accept-invite / reset-password).
 *
 * <p>The property's dev-convenience default ({@code http://localhost:5173})
 * lets the app boot successfully even when a prod deployment forgets to
 * inject {@code ADMIN_INVITE_BASE_URL}. The mailed link then points at the
 * operator's own loopback address, never reaches the real front-end, and the
 * failure is invisible until an operator actually clicks the link — by which
 * point they may be trying to recover from a lockout. Fail fast at boot
 * instead: only the "prod" profile is guarded (this is where the localhost
 * default is actually unsafe; dev/local/qa intentionally rely on it).
 */
final class BaseUrlValidation {
    private BaseUrlValidation() {}

    static final String LOCALHOST_DEFAULT = "http://localhost:5173";

    static void assertNotLocalhostFallbackInProd(Environment env, String baseUrl, String propertyName) {
        boolean isProd = false;
        for (String p : env.getActiveProfiles()) {
            if ("prod".equals(p)) {
                isProd = true;
                break;
            }
        }
        if (isProd && (baseUrl == null || baseUrl.startsWith("http://localhost")
                || baseUrl.startsWith("https://localhost"))) {
            throw new IllegalStateException(
                    "prod profile requires " + propertyName
                            + " to be set to the real front-end origin — refusing to boot with the "
                            + "localhost dev default (" + baseUrl + "). Set ADMIN_INVITE_BASE_URL.");
        }
    }
}
