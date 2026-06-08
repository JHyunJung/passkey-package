package com.crosscert.passkey.webauthn.attestation;

import java.security.cert.X509Certificate;
import java.util.List;

/** attestation 검증 결과 — trust type과 (있으면) 체인. */
public record AttestationResult(Type type, List<X509Certificate> trustPath) {
    public enum Type { NONE, SELF, BASIC, ATT_CA }

    public static AttestationResult none() { return new AttestationResult(Type.NONE, List.of()); }
    public static AttestationResult self() { return new AttestationResult(Type.SELF, List.of()); }
    public static AttestationResult basic(List<X509Certificate> path) {
        return new AttestationResult(Type.BASIC, path);
    }
}
