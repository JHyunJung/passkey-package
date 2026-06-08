package com.crosscert.passkey.webauthn.verifier;

import java.util.Set;

/** registration credentialJson에서 떼어낸 원시 구성요소. */
public record ParsedRegistration(
        byte[] rawId,
        byte[] clientDataJson,
        byte[] attestationObject,
        Set<String> transports
) {}
