package com.crosscert.passkey.app.fido2;

import com.crosscert.passkey.webauthn.verifier.NativeWebAuthnVerifier;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WebAuthn 검증 구현 빈 구성. 자체 구현(NativeWebAuthnVerifier)을 사용한다.
 * webauthn4j는 프로덕션 classpath에서 제거됐다(:webauthn 모듈의 testImplementation에만 존재).
 */
@Configuration
public class WebAuthnVerifierConfig {

    @Bean
    public WebAuthnVerifier webAuthnVerifier(ObjectMapper mapper) {
        return NativeWebAuthnVerifier.withDefaults(mapper);
    }
}
