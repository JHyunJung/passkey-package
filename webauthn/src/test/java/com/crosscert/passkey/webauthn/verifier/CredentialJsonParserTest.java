package com.crosscert.passkey.webauthn.verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CredentialJsonParserTest {

    private final CredentialJsonParser parser = new CredentialJsonParser(new ObjectMapper());
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @Test
    void parsesRegistrationJson() {
        String json = "{"
                + "\"id\":\"" + B64.encodeToString(new byte[]{1, 2}) + "\","
                + "\"rawId\":\"" + B64.encodeToString(new byte[]{1, 2}) + "\","
                + "\"type\":\"public-key\","
                + "\"response\":{"
                + "  \"clientDataJSON\":\"" + B64.encodeToString("{}".getBytes()) + "\","
                + "  \"attestationObject\":\"" + B64.encodeToString(new byte[]{9}) + "\","
                + "  \"transports\":[\"usb\",\"nfc\"]"
                + "}}";
        ParsedRegistration p = parser.parseRegistration(json);
        assertArrayEquals(new byte[]{1, 2}, p.rawId());
        assertArrayEquals(new byte[]{9}, p.attestationObject());
        assertTrue(p.transports().contains("usb"));
        assertTrue(p.transports().contains("nfc"));
    }

    @Test
    void parsesAuthenticationJson() {
        String json = "{"
                + "\"id\":\"" + B64.encodeToString(new byte[]{1}) + "\","
                + "\"rawId\":\"" + B64.encodeToString(new byte[]{1}) + "\","
                + "\"type\":\"public-key\","
                + "\"response\":{"
                + "  \"clientDataJSON\":\"" + B64.encodeToString("{}".getBytes()) + "\","
                + "  \"authenticatorData\":\"" + B64.encodeToString(new byte[]{7}) + "\","
                + "  \"signature\":\"" + B64.encodeToString(new byte[]{8}) + "\""
                + "}}";
        ParsedAuthentication p = parser.parseAuthentication(json);
        assertArrayEquals(new byte[]{7}, p.authenticatorData());
        assertArrayEquals(new byte[]{8}, p.signature());
        assertNull(p.userHandle());
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(RuntimeException.class, () -> parser.parseRegistration("not-json"));
    }

    @Test
    void rejectsMissingResponseField() {
        String json = "{\"rawId\":\"AQ\",\"type\":\"public-key\"}";
        assertThrows(IllegalArgumentException.class, () -> parser.parseRegistration(json));
    }

    @Test
    void rejectsNonBase64UrlField() {
        String json = "{\"rawId\":\"!!!notb64!!!\",\"type\":\"public-key\","
                + "\"response\":{\"clientDataJSON\":\"" + B64.encodeToString("{}".getBytes())
                + "\",\"attestationObject\":\"" + B64.encodeToString(new byte[]{9}) + "\"}}";
        assertThrows(IllegalArgumentException.class, () -> parser.parseRegistration(json));
    }

    @Test
    void parsesAuthenticationWithUserHandle() {
        String json = "{\"rawId\":\"AQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString("{}".getBytes()) + "\","
                + "\"authenticatorData\":\"" + B64.encodeToString(new byte[]{7}) + "\","
                + "\"signature\":\"" + B64.encodeToString(new byte[]{8}) + "\","
                + "\"userHandle\":\"" + B64.encodeToString(new byte[]{5, 6}) + "\"}}";
        ParsedAuthentication p = parser.parseAuthentication(json);
        assertArrayEquals(new byte[]{5, 6}, p.userHandle());
    }

    @Test
    void rejectsNonStringRawId() {
        // rawId가 object → coercion으로 빈 byte[] 통과하면 안 됨
        String json = "{\"rawId\":{},\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString("{}".getBytes()) + "\","
                + "\"attestationObject\":\"" + B64.encodeToString(new byte[]{9}) + "\"}}";
        assertThrows(IllegalArgumentException.class, () -> parser.parseRegistration(json));
    }

    @Test
    void rejectsNonStringAttestationObject() {
        String json = "{\"rawId\":\"AQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString("{}".getBytes()) + "\","
                + "\"attestationObject\":12345}}";
        assertThrows(IllegalArgumentException.class, () -> parser.parseRegistration(json));
    }

    @Test
    void rejectsOversizedCredentialJson() {
        StringBuilder sb = new StringBuilder("{\"rawId\":\"AQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString("{}".getBytes())
                + "\",\"attestationObject\":\"");
        sb.append("A".repeat(70000)).append("\"}}");
        assertThrows(IllegalArgumentException.class, () -> parser.parseRegistration(sb.toString()));
    }
}
