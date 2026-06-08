package com.crosscert.passkey.webauthn.mds;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class MdsBlobParserTest {

    @Test
    void parsesNoNextUpdateAndEntries() throws Exception {
        String json = "{"
                + "\"no\":42,"
                + "\"nextUpdate\":\"2026-07-01\","
                + "\"entries\":["
                + "  {\"aaguid\":\"00112233-4455-6677-8899-aabbccddeeff\","
                + "   \"statusReports\":[{\"status\":\"FIDO_CERTIFIED_L1\",\"effectiveDate\":\"2020-01-01\"},"
                + "                      {\"status\":\"REVOKED\"}]},"
                + "  {\"statusReports\":[{\"status\":\"NOT_FIDO_CERTIFIED\"}]}"
                + "]}";

        MdsBlob blob = MdsBlobParser.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(42, blob.no());
        assertEquals(LocalDate.of(2026, 7, 1), blob.nextUpdate());
        assertEquals(2, blob.entries().size());

        MdsBlobEntry e0 = blob.entries().get(0);
        assertArrayEquals(HexFormat.of().parseHex("00112233445566778899aabbccddeeff"), e0.aaguid());
        assertEquals(2, e0.statusReports().size());
        assertEquals("FIDO_CERTIFIED_L1", e0.statusReports().get(0).status());
        assertEquals(LocalDate.of(2020, 1, 1), e0.statusReports().get(0).effectiveDate());
        assertEquals("REVOKED", e0.statusReports().get(1).status());
        assertNull(e0.statusReports().get(1).effectiveDate());

        MdsBlobEntry e1 = blob.entries().get(1);
        assertNull(e1.aaguid());
        assertEquals("NOT_FIDO_CERTIFIED", e1.statusReports().get(0).status());
    }

    @Test
    void preservesUnknownStatusToken() throws Exception {
        String json = "{\"no\":1,\"nextUpdate\":\"2026-01-01\",\"entries\":["
                + "{\"aaguid\":\"00000000-0000-0000-0000-000000000001\","
                + "\"statusReports\":[{\"status\":\"SOME_FUTURE_STATUS_X\"}]}]}";
        MdsBlob blob = MdsBlobParser.parse(json.getBytes());
        assertEquals("SOME_FUTURE_STATUS_X", blob.entries().get(0).statusReports().get(0).status());
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(MdsException.class, () -> MdsBlobParser.parse("not-json".getBytes()));
    }

    @Test
    void rejectsMissingNo() {
        assertThrows(MdsException.class,
                () -> MdsBlobParser.parse("{\"nextUpdate\":\"2026-01-01\",\"entries\":[]}".getBytes()));
    }
}
