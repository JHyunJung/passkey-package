package com.crosscert.passkey.webauthn;

import com.crosscert.passkey.webauthn.mds.MdsBlob;
import com.crosscert.passkey.webauthn.mds.MdsBlobParser;
import com.crosscert.passkey.webauthn.mds.MdsException;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * webauthn 의 독립 매퍼({@link JsonMappers#secure()})가 Jackson 2 시절 파서 상한을 유지하는지 고정한다.
 * 이 매퍼는 Spring 자동설정 밖이라 {@code spring.jackson.*} 상한이 적용되지 않는다.
 */
class JsonMappersTest {

    @Test
    void streamReadConstraints_matchJackson2Defaults() {
        StreamReadConstraints c = JsonMappers.secure().tokenStreamFactory().streamReadConstraints();
        assertEquals(1000, c.getMaxNestingDepth());
        assertEquals(20_000_000, c.getMaxStringLength());
        // 두 버전 기본값이 같은 항목 — 그대로 유지되는지 확인.
        assertEquals(-1, c.getMaxDocumentLength());
        assertEquals(1000, c.getMaxNumberLength());
        assertEquals(50_000, c.getMaxNameLength());
        assertEquals(-1, c.getMaxTokenCount());
    }

    @Test
    void mdsPayload_withIgnoredFieldNested600Deep_parses() throws Exception {
        MdsBlob blob = MdsBlobParser.parse(payloadWithNestedIgnoredField(600));
        assertEquals(1, blob.no());
    }

    @Test
    void mdsPayload_nestedBeyond1000_isMalformedPayload() {
        MdsException e = assertThrows(MdsException.class,
                () -> MdsBlobParser.parse(payloadWithNestedIgnoredField(1001)));
        assertEquals(MdsException.Reason.MALFORMED_PAYLOAD, e.reason());
        assertInstanceOf(StreamConstraintsException.class, e.getCause());
    }

    private static byte[] payloadWithNestedIgnoredField(int depth) {
        String json = "{\"no\":1,\"nextUpdate\":\"2026-07-01\",\"ignored\":"
                + "[".repeat(depth) + "]".repeat(depth)
                + ",\"entries\":[]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
