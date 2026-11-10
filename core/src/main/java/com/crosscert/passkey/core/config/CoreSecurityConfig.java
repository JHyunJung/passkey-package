package com.crosscert.passkey.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;

@Configuration
public class CoreSecurityConfig {

    /**
     * BCrypt cost 12. Shared by admin-app (operator passwords, API key
     * issuance) and passkey-app (API key secret verification in
     * ApiKeyAuthFilter). On the RP auth path, passkey-app's
     * VerifiedSecretCache skips re-running BCrypt for 30 seconds after a
     * successful verification of the same secret, bound to the key's id and
     * keyHash. Every cache miss, and every failure, is verified by this encoder.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Shared SecureRandom instance for cryptographic key generation
     * (e.g. ApiKeyAdminService prefix + secret). java.security.SecureRandom
     * auto-reseeds from the OS entropy pool; a single shared instance is safe
     * and avoids the startup cost of seeding one per request.
     */
    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }
}
