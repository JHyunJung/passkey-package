package com.crosscert.passkey.webauthn.diff;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class Webauthn4jVerifierTest {
    @Test
    void instantiates() {
        assertNotNull(new Webauthn4jVerifier());
    }
}
