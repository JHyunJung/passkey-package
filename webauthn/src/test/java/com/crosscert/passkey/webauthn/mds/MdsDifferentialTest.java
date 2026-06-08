package com.crosscert.passkey.webauthn.mds;

import org.junit.jupiter.api.Test;

import java.security.cert.TrustAnchor;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 동일 rawJwt를 자체 NativeMetadataBlobVerifier와 webauthn4j MetadataBLOBFactory에
 * 모두 넣어 파싱 결과(no/nextUpdate/entries/AAGUID/status)가 일치하는지 대조.
 *
 * <p>webauthn4j {@code MetadataBLOBFactory.parse(String)}는 서명을 검증하지 않고
 * 페이로드만 역직렬화하므로(별도 {@code isValidSignature()}) self-signed MdsTestBlob도
 * 그대로 파싱된다 — 두 구현이 동일 JWS의 같은 필드를 추출하는지 진짜 차등 대조가 가능하다.
 *
 * <p>{@code timeOfLastStatusChange}는 MDS3 §3.1.1에서 필수이며 webauthn4j 엔트리의
 * {@code @NotNull} 필드라 페이로드에 포함한다. 자체 파서는 소비처가 쓰지 않는 필드를
 * 무시하므로 대조에는 영향이 없다.
 */
class MdsDifferentialTest {

    private final NativeMetadataBlobVerifier nativeVerifier = new NativeMetadataBlobVerifier();
    private final Webauthn4jMetadataBlobVerifier w4j = new Webauthn4jMetadataBlobVerifier();

    @Test
    void parsingAgreesAcrossImplementations() throws Exception {
        String payload = "{\"no\":11,\"nextUpdate\":\"2026-09-15\",\"entries\":["
                + "{\"aaguid\":\"00112233-4455-6677-8899-aabbccddeeff\","
                + "\"timeOfLastStatusChange\":\"2026-01-02\","
                + "\"statusReports\":["
                + "{\"status\":\"FIDO_CERTIFIED_L1\",\"effectiveDate\":\"2025-06-01\"},"
                + "{\"status\":\"REVOKED\",\"effectiveDate\":\"2026-03-01\"}]}]}";
        MdsTestBlob tb = MdsTestBlob.rs256(payload);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(tb.root, null));

        MdsBlob nativeBlob = nativeVerifier.verify(tb.jws, anchors);
        MdsBlob w4jBlob = w4j.parse(tb.jws);

        // 베이스라인 non-trivial 보장 — 두 구현이 모두 빈/null로 회귀해도 통과하는
        // false-confidence 차단. webauthn4j 쪽이 실제 의미있는 값을 뽑았는지 먼저 단언 (codex P2).
        assertEquals(11, w4jBlob.no());
        assertEquals(1, w4jBlob.entries().size());
        assertNotNull(w4jBlob.entries().get(0).aaguid());
        assertEquals(16, w4jBlob.entries().get(0).aaguid().length, "aaguid must be 16 bytes");
        var w4jBaselineStatuses = w4jBlob.entries().get(0).statusReports().stream()
                .map(MdsStatusReport::status).toList();
        assertEquals(java.util.List.of("FIDO_CERTIFIED_L1", "REVOKED"), w4jBaselineStatuses);

        assertEquals(w4jBlob.no(), nativeBlob.no());
        assertEquals(w4jBlob.nextUpdate(), nativeBlob.nextUpdate());
        assertEquals(w4jBlob.entries().size(), nativeBlob.entries().size());
        assertArrayEquals(w4jBlob.entries().get(0).aaguid(), nativeBlob.entries().get(0).aaguid());
        var nativeStatuses = nativeBlob.entries().get(0).statusReports().stream().map(MdsStatusReport::status).toList();
        var w4jStatuses = w4jBlob.entries().get(0).statusReports().stream().map(MdsStatusReport::status).toList();
        assertEquals(w4jStatuses, nativeStatuses);

        // effectiveDate 까지 차등 대조 — 두 구현이 같은 LocalDate 를 뽑는지 확인
        var nativeEff = nativeBlob.entries().get(0).statusReports().stream().map(MdsStatusReport::effectiveDate).toList();
        var w4jEff = w4jBlob.entries().get(0).statusReports().stream().map(MdsStatusReport::effectiveDate).toList();
        assertEquals(w4jEff, nativeEff);
    }
}
