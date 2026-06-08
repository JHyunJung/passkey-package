package com.crosscert.passkey.webauthn.attestation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttestationVerifiersTest {

    private final AttestationVerifiers registry = AttestationVerifiers.defaults();

    @Test
    void resolvesNone() {
        assertEquals("none", registry.forFormat("none").format());
    }

    @Test
    void resolvesPacked() {
        assertEquals("packed", registry.forFormat("packed").format());
    }

    @Test
    void unknownFormatReturnsNull() {
        assertNull(registry.forFormat("no-such-format"));
    }

    @Test
    void ofRegistersCustomVerifiers() {
        AttestationVerifiers custom = AttestationVerifiers.of(
                java.util.List.of(new NoneAttestationVerifier()));
        assertEquals("none", custom.forFormat("none").format());
        assertNull(custom.forFormat("packed")); // packed not registered in custom
    }
}
