package com.crosscert.passkey.webauthn.mds;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class MdsJwsTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @Test
    void parsesHeaderPayloadSignatureAndX5c() throws Exception {
        String header = "{\"alg\":\"RS256\",\"x5c\":[\"" + Base64.getEncoder().encodeToString(new byte[]{1}) + "\"]}";
        String payload = "{\"no\":1}";
        String h64 = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String p64 = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig = B64.encodeToString(new byte[]{9, 9});
        String jws = h64 + "." + p64 + "." + sig;

        MdsJws parsed = MdsJws.parse(jws);

        assertEquals("RS256", parsed.alg());
        assertEquals(1, parsed.x5c().size());
        assertArrayEquals(new byte[]{1}, parsed.x5c().get(0));
        assertArrayEquals(new byte[]{9, 9}, parsed.signature());
        assertArrayEquals((h64 + "." + p64).getBytes(StandardCharsets.US_ASCII), parsed.signingInput());
        assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), parsed.payloadBytes());
    }

    @Test
    void rejectsNotThreeParts() {
        assertThrows(MdsException.class, () -> MdsJws.parse("aaa.bbb"));
    }

    @Test
    void rejectsPaddedParts() {
        assertThrows(MdsException.class, () -> MdsJws.parse("aa==.bb.cc"));
    }

    @Test
    void rejectsUnsupportedAlg() {
        String header = "{\"alg\":\"none\"}";
        String h64 = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String jws = h64 + "." + B64.encodeToString("{}".getBytes()) + "." + B64.encodeToString(new byte[]{1});
        assertThrows(MdsException.class, () -> MdsJws.parse(jws));
    }
}
