package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.cbor.CborValue.CborMap;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class NoneAttestationVerifierTest {

    private final NoneAttestationVerifier verifier = new NoneAttestationVerifier();

    @Test
    void formatIsNone() {
        assertEquals("none", verifier.format());
    }

    @Test
    void acceptsEmptyAttStmt() {
        CborMap empty = new CborMap(new LinkedHashMap<>());
        AttestationResult r = verifier.verify(null, new byte[0], empty, new byte[32]);
        assertEquals(AttestationResult.Type.NONE, r.type());
    }

    @Test
    void rejectsNonEmptyAttStmt() {
        LinkedHashMap<com.crosscert.passkey.webauthn.cbor.CborValue,
                com.crosscert.passkey.webauthn.cbor.CborValue> m = new LinkedHashMap<>();
        m.put(new com.crosscert.passkey.webauthn.cbor.CborValue.CborText("x"),
                new com.crosscert.passkey.webauthn.cbor.CborValue.CborInt(1));
        CborMap nonEmpty = new CborMap(m);
        assertThrows(AttestationException.class,
                () -> verifier.verify(null, new byte[0], nonEmpty, new byte[32]));
    }

    @Test
    void rejectsNonMapAttStmt() {
        // attStmt가 map이 아닌 경우(예: 배열)도 거부해야 한다 (codex 관찰: 커버리지 보강)
        com.crosscert.passkey.webauthn.cbor.CborValue notAMap =
                new com.crosscert.passkey.webauthn.cbor.CborValue.CborArray(java.util.List.of());
        assertThrows(AttestationException.class,
                () -> verifier.verify(null, new byte[0], notAMap, new byte[32]));
    }
}
