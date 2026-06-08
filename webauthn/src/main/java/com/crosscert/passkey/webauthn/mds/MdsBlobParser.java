package com.crosscert.passkey.webauthn.mds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MDS3 BLOB payload JSON → MdsBlob (FIDO MDS3 §3.1.6).
 * 소비처가 쓰는 필드만 파싱: no, nextUpdate, entries[].aaguid, entries[].statusReports[].
 * status는 원문 문자열 보존(미지 토큰도 거부 안 함).
 */
public final class MdsBlobParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MdsBlobParser() {}

    public static MdsBlob parse(byte[] payloadJson) throws MdsException {
        JsonNode root;
        try {
            root = MAPPER.readTree(payloadJson);
        } catch (Exception e) {
            throw new MdsException(MdsException.Reason.MALFORMED_PAYLOAD, "payload not JSON", e);
        }
        JsonNode noNode = root.get("no");
        if (noNode == null || !noNode.isInt()) {
            throw new MdsException(MdsException.Reason.MALFORMED_PAYLOAD, "payload missing int 'no'");
        }
        int no = noNode.asInt();
        LocalDate nextUpdate = parseDate(root.get("nextUpdate"), "nextUpdate", true);

        List<MdsBlobEntry> entries = new ArrayList<>();
        JsonNode entriesNode = root.get("entries");
        if (entriesNode != null && entriesNode.isArray()) {
            for (JsonNode entryNode : entriesNode) {
                entries.add(parseEntry(entryNode));
            }
        }
        return new MdsBlob(no, nextUpdate, entries);
    }

    private static MdsBlobEntry parseEntry(JsonNode entryNode) throws MdsException {
        byte[] aaguid = null;
        JsonNode aaguidNode = entryNode.get("aaguid");
        if (aaguidNode != null && aaguidNode.isTextual() && !aaguidNode.asText().isBlank()) {
            aaguid = uuidToBytes(aaguidNode.asText());
        }
        List<MdsStatusReport> reports = new ArrayList<>();
        JsonNode srNode = entryNode.get("statusReports");
        if (srNode != null && srNode.isArray()) {
            for (JsonNode r : srNode) {
                JsonNode statusNode = r.get("status");
                if (statusNode == null || !statusNode.isTextual()) {
                    throw new MdsException(MdsException.Reason.MALFORMED_PAYLOAD,
                            "statusReport missing status");
                }
                LocalDate eff = parseDate(r.get("effectiveDate"), "effectiveDate", false);
                reports.add(new MdsStatusReport(statusNode.asText(), eff));
            }
        }
        return new MdsBlobEntry(aaguid, reports);
    }

    private static byte[] uuidToBytes(String s) throws MdsException {
        try {
            UUID u = UUID.fromString(s);
            byte[] out = new byte[16];
            long hi = u.getMostSignificantBits(), lo = u.getLeastSignificantBits();
            for (int i = 0; i < 8; i++) out[i] = (byte) (hi >>> (8 * (7 - i)));
            for (int i = 0; i < 8; i++) out[8 + i] = (byte) (lo >>> (8 * (7 - i)));
            return out;
        } catch (IllegalArgumentException e) {
            throw new MdsException(MdsException.Reason.MALFORMED_PAYLOAD, "aaguid not a UUID: " + s, e);
        }
    }

    private static LocalDate parseDate(JsonNode node, String field, boolean required) throws MdsException {
        if (node == null || node.isNull() || !node.isTextual()) {
            if (required) {
                throw new MdsException(MdsException.Reason.MALFORMED_PAYLOAD, "payload missing " + field);
            }
            return null;
        }
        try {
            return LocalDate.parse(node.asText());
        } catch (DateTimeParseException e) {
            throw new MdsException(MdsException.Reason.MALFORMED_PAYLOAD, field + " not a date: " + node.asText(), e);
        }
    }
}
