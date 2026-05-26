package com.crosscert.passkey.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;

@Configuration
public class CoreSecurityConfig {

    /**
     * BCrypt cost 12. Tolerable on the auth path because ApiKeyCache
     * gates a 30-second skip after the first verification.
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
