package com.crosscert.passkey.app.fido2;

import com.webauthn4j.WebAuthnManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Webauthn4jConfig {

    @Bean
    public WebAuthnManager webAuthnManager() {
        return WebAuthnManager.createNonStrictWebAuthnManager();
    }
}
