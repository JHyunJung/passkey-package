package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.CborMap;

/** fmt=none (WebAuthn §8.7) — attStmt는 반드시 빈 map. */
public final class NoneAttestationVerifier implements AttestationVerifier {

    @Override
    public String format() { return "none"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        if (!(attStmt instanceof CborMap m) || !m.entries().isEmpty()) {
            throw new AttestationException("none attestation must have empty attStmt");
        }
        return AttestationResult.none();
    }
}
