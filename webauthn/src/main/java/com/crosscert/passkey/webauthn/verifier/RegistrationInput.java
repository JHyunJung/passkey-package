package com.crosscert.passkey.webauthn.verifier;

import java.util.Set;

public record RegistrationInput(
        String credentialJson,          // 클라가 보낸 PublicKeyCredential JSON 전체
        byte[] challenge,               // 서버 발급 challenge
        Set<String> allowedOrigins,
        String rpId,
        boolean userVerificationRequired,
        Set<COSEAlgorithm> allowedAlgorithms,
        Set<String> acceptedAttestationFormats,
        AttestationTrustPolicy trustPolicy
) {}
