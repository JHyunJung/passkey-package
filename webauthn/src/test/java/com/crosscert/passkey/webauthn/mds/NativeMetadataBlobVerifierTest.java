package com.crosscert.passkey.webauthn.mds;

import org.junit.jupiter.api.Test;

import java.security.cert.TrustAnchor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NativeMetadataBlobVerifierTest {

    private final NativeMetadataBlobVerifier verifier = new NativeMetadataBlobVerifier();

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

    // --- G21: nextUpdate 신선도 ---

    @Test
    void rejectsStaleBlobWhenAsOfPastNextUpdate() throws Exception {
        // nextUpdate=2026-01-01인 blob을 검증시각 2026-02-01(nextUpdate 이후)로 검증하면 STALE 거부.
        String payload = "{\"no\":9,\"nextUpdate\":\"2026-01-01\",\"entries\":[]}";
        MdsTestBlob tb = MdsTestBlob.rs256(payload);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(tb.root, null));
        Instant asOf = LocalDate.of(2026, 2, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

        MdsException ex = assertThrows(MdsException.class,
                () -> verifier.verify(tb.jws, anchors, asOf));
        assertEquals(MdsException.Reason.STALE, ex.reason());
    }

    @Test
    void acceptsBlobWhenAsOfBeforeNextUpdate() throws Exception {
        // nextUpdate=2026-12-31인 blob을 검증시각 2026-06-01(nextUpdate 이전)로 검증하면 통과.
        String payload = "{\"no\":10,\"nextUpdate\":\"2026-12-31\",\"entries\":[]}";
        MdsTestBlob tb = MdsTestBlob.rs256(payload);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(tb.root, null));
        Instant asOf = LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

        MdsBlob blob = verifier.verify(tb.jws, anchors, asOf);
        assertEquals(10, blob.no());
    }

    @Test
    void defaultOverloadUsesCurrentTimeAndAcceptsFutureNextUpdate() throws Exception {
        // 기본 동작 보존: asOf 없는 2-인자 오버로드는 now 기준이라, 미래 nextUpdate는 여전히 통과.
        String payload = "{\"no\":11,\"nextUpdate\":\"2999-01-01\",\"entries\":[]}";
        MdsTestBlob tb = MdsTestBlob.rs256(payload);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(tb.root, null));

        MdsBlob blob = verifier.verify(tb.jws, anchors);
        assertEquals(11, blob.no());
    }
}
