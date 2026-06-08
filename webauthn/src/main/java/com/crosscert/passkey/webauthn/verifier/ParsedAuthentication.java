package com.crosscert.passkey.webauthn.verifier;

/** authentication credentialJson에서 떼어낸 원시 구성요소. */
public record ParsedAuthentication(
        byte[] rawId,
        byte[] clientDataJson,
        byte[] authenticatorData,
        byte[] signature,
        byte[] userHandle   // nullable
) {}
