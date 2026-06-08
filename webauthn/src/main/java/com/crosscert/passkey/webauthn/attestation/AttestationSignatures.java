package com.crosscert.passkey.webauthn.attestation;

import java.security.PublicKey;
import java.security.Signature;

/** attestation 서명 검증 공통 로직 — 알고리즘명으로 JDK Signature 사용. */
final class AttestationSignatures {

    private AttestationSignatures() {}

    /** signed = authData || clientDataHash. jcaName 예: "SHA256withECDSA". */
    static boolean verify(String jcaName, PublicKey key, byte[] rawAuthData,
                          byte[] clientDataHash, byte[] signature) {
        try {
            Signature sig = Signature.getInstance(jcaName);
            sig.initVerify(key);
            sig.update(rawAuthData);
            sig.update(clientDataHash);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
