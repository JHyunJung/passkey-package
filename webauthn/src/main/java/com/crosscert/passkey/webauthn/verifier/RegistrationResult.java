package com.crosscert.passkey.webauthn.verifier;

import java.util.Set;

public record RegistrationResult(
        byte[] credentialId,
        byte[] cosePublicKey,           // 저장 스키마 핵심 (COSE_Key CBOR)
        long signCount,
        byte[] aaguid,
        String attestationFormat,
        Set<String> transports,
        boolean uvVerified,
        boolean upVerified,
        boolean backupEligible,
        boolean backupState
) {}
