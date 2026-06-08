package com.crosscert.passkey.webauthn.mds;

import org.junit.jupiter.api.Test;

import java.security.cert.TrustAnchor;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NativeMetadataBlobVerifierTest {

    private final MetadataBlobVerifier verifier = new NativeMetadataBlobVerifier();

    @Test
    void verifiesAndParsesSignedBlob() throws Exception {
        String payload = "{\"no\":5,\"nextUpdate\":\"2026-12-31\",\"entries\":["
                + "{\"aaguid\":\"00112233-4455-6677-8899-aabbccddeeff\","
                + "\"statusReports\":[{\"status\":\"FIDO_CERTIFIED_L1\"}]}]}";
        MdsTestBlob tb = MdsTestBlob.rs256(payload);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(tb.root, null));

        MdsBlob blob = verifier.verify(tb.jws, anchors);

        assertEquals(5, blob.no());
        assertEquals(LocalDate.of(2026, 12, 31), blob.nextUpdate());
        assertEquals(1, blob.entries().size());
        assertEquals("FIDO_CERTIFIED_L1", blob.entries().get(0).statusReports().get(0).status());
    }

    @Test
    void rejectsUntrustedThenNeverParses() throws Exception {
        MdsTestBlob tb = MdsTestBlob.rs256("{\"no\":1,\"nextUpdate\":\"2026-01-01\",\"entries\":[]}");
        MdsTestBlob other = MdsTestBlob.rs256("{\"no\":1,\"nextUpdate\":\"2026-01-01\",\"entries\":[]}");
        MdsException ex = assertThrows(MdsException.class,
                () -> verifier.verify(tb.jws, Set.of(new TrustAnchor(other.root, null))));
        assertEquals(MdsException.Reason.UNTRUSTED_CHAIN, ex.reason());
    }
}
