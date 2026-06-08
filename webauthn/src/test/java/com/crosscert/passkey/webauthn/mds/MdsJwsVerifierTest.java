package com.crosscert.passkey.webauthn.mds;

import org.junit.jupiter.api.Test;

import java.security.cert.TrustAnchor;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MdsJwsVerifierTest {

    private final MdsJwsVerifier verifier = new MdsJwsVerifier();

    @Test
    void verifiesValidSignedBlobToTrustedRoot() throws Exception {
        MdsTestBlob blob = MdsTestBlob.rs256("{\"no\":7}");
        MdsJws jws = MdsJws.parse(blob.jws);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(blob.root, null));
        assertDoesNotThrow(() -> verifier.verify(jws, anchors));
    }

    @Test
    void rejectsUntrustedRoot() throws Exception {
        MdsTestBlob blob = MdsTestBlob.rs256("{\"no\":7}");
        MdsTestBlob other = MdsTestBlob.rs256("{\"no\":7}");
        MdsJws jws = MdsJws.parse(blob.jws);
        Set<TrustAnchor> wrong = Set.of(new TrustAnchor(other.root, null));
        MdsException ex = assertThrows(MdsException.class, () -> verifier.verify(jws, wrong));
        assertEquals(MdsException.Reason.UNTRUSTED_CHAIN, ex.reason());
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        MdsTestBlob blob = MdsTestBlob.rs256("{\"no\":7}");
        String[] parts = blob.jws.split("\\.");
        char last = parts[2].charAt(parts[2].length() - 1);
        parts[2] = parts[2].substring(0, parts[2].length() - 1) + (last == 'A' ? 'B' : 'A');
        MdsJws jws = MdsJws.parse(parts[0] + "." + parts[1] + "." + parts[2]);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(blob.root, null));
        MdsException ex = assertThrows(MdsException.class, () -> verifier.verify(jws, anchors));
        assertEquals(MdsException.Reason.BAD_SIGNATURE, ex.reason());
    }

    @Test
    void rejectsEmptyX5c() throws Exception {
        String header = "{\"alg\":\"RS256\"}";
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String h64 = b64.encodeToString(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String jwsStr = h64 + "." + b64.encodeToString("{}".getBytes()) + "." + b64.encodeToString(new byte[]{1});
        MdsJws jws = MdsJws.parse(jwsStr);
        MdsException ex = assertThrows(MdsException.class, () -> verifier.verify(jws, Set.of()));
        assertEquals(MdsException.Reason.MALFORMED_JWS, ex.reason());
    }
}
