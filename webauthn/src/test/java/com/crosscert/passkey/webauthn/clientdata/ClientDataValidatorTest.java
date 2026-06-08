package com.crosscert.passkey.webauthn.clientdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClientDataValidatorTest {

    private final ClientDataValidator validator = new ClientDataValidator(new ObjectMapper());

    private byte[] clientDataJson(String type, String challengeB64url, String origin) {
        String json = "{\"type\":\"" + type + "\",\"challenge\":\"" + challengeB64url
                + "\",\"origin\":\"" + origin + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Test
    void acceptsValidCreateClientData() {
        byte[] challenge = "the-challenge".getBytes(StandardCharsets.UTF_8);
        byte[] cd = clientDataJson("webauthn.create", b64url(challenge), "https://example.com");

        CollectedClientData parsed = validator.validate(
                cd, "webauthn.create", challenge, Set.of("https://example.com"));

        assertEquals("webauthn.create", parsed.type());
        assertEquals("https://example.com", parsed.origin());
    }

    @Test
    void rejectsWrongType() {
        byte[] challenge = "c".getBytes(StandardCharsets.UTF_8);
        byte[] cd = clientDataJson("webauthn.get", b64url(challenge), "https://example.com");
        ClientDataException ex = assertThrows(ClientDataException.class,
                () -> validator.validate(cd, "webauthn.create", challenge, Set.of("https://example.com")));
        assertEquals(ClientDataException.Reason.TYPE_MISMATCH, ex.reason());
    }

    @Test
    void rejectsWrongChallenge() {
        byte[] cd = clientDataJson("webauthn.create",
                b64url("attacker".getBytes(StandardCharsets.UTF_8)), "https://example.com");
        ClientDataException ex = assertThrows(ClientDataException.class,
                () -> validator.validate(cd, "webauthn.create",
                        "server".getBytes(StandardCharsets.UTF_8), Set.of("https://example.com")));
        assertEquals(ClientDataException.Reason.CHALLENGE_MISMATCH, ex.reason());
    }

    @Test
    void rejectsWrongOrigin() {
        byte[] challenge = "c".getBytes(StandardCharsets.UTF_8);
        byte[] cd = clientDataJson("webauthn.create", b64url(challenge), "https://evil.com");
        ClientDataException ex = assertThrows(ClientDataException.class,
                () -> validator.validate(cd, "webauthn.create", challenge, Set.of("https://example.com")));
        assertEquals(ClientDataException.Reason.ORIGIN_MISMATCH, ex.reason());
    }

    @Test
    void rejectsMissingTypeField() {
        byte[] cd = ("{\"challenge\":\"" + b64url("c".getBytes(StandardCharsets.UTF_8))
                + "\",\"origin\":\"https://example.com\"}").getBytes(StandardCharsets.UTF_8);
        ClientDataException ex = assertThrows(ClientDataException.class,
                () -> validator.validate(cd, "webauthn.create",
                        "c".getBytes(StandardCharsets.UTF_8), Set.of("https://example.com")));
        assertEquals(ClientDataException.Reason.MALFORMED, ex.reason());
    }

    @Test
    void rejectsMalformedJson() {
        byte[] cd = "not json".getBytes(StandardCharsets.UTF_8);
        ClientDataException ex = assertThrows(ClientDataException.class,
                () -> validator.validate(cd, "webauthn.create",
                        "c".getBytes(StandardCharsets.UTF_8), Set.of("https://example.com")));
        assertEquals(ClientDataException.Reason.MALFORMED, ex.reason());
    }

    @Test
    void rejectsChallengeThatIsPrefixOfExpected() {
        // 상수시간 비교가 길이 차이를 잡는지: expected가 더 긴 경우
        byte[] expected = "longer-challenge".getBytes(StandardCharsets.UTF_8);
        byte[] cd = clientDataJson("webauthn.create",
                b64url("longer".getBytes(StandardCharsets.UTF_8)), "https://example.com");
        ClientDataException ex = assertThrows(ClientDataException.class,
                () -> validator.validate(cd, "webauthn.create", expected, Set.of("https://example.com")));
        assertEquals(ClientDataException.Reason.CHALLENGE_MISMATCH, ex.reason());
    }

    @Test
    void rejectsNonBooleanCrossOrigin() {
        byte[] challenge = "c".getBytes(StandardCharsets.UTF_8);
        String json = "{\"type\":\"webauthn.create\",\"challenge\":\"" + b64url(challenge)
                + "\",\"origin\":\"https://example.com\",\"crossOrigin\":\"false\"}";
        ClientDataException ex = assertThrows(ClientDataException.class,
                () -> validator.validate(json.getBytes(StandardCharsets.UTF_8),
                        "webauthn.create", challenge, Set.of("https://example.com")));
        assertEquals(ClientDataException.Reason.MALFORMED, ex.reason());
    }

    @Test
    void acceptsValidBooleanCrossOrigin() {
        byte[] challenge = "c".getBytes(StandardCharsets.UTF_8);
        String json = "{\"type\":\"webauthn.create\",\"challenge\":\"" + b64url(challenge)
                + "\",\"origin\":\"https://example.com\",\"crossOrigin\":true}";
        CollectedClientData parsed = validator.validate(json.getBytes(StandardCharsets.UTF_8),
                "webauthn.create", challenge, Set.of("https://example.com"));
        assertTrue(parsed.crossOrigin());
    }

    @Test
    void rejectsOversizedClientData() {
        byte[] challenge = "c".getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("{\"type\":\"webauthn.create\",\"challenge\":\"")
                .append(b64url(challenge)).append("\",\"origin\":\"https://example.com\",\"junk\":\"");
        sb.append("A".repeat(20000)).append("\"}");
        ClientDataException ex = assertThrows(ClientDataException.class,
                () -> validator.validate(sb.toString().getBytes(StandardCharsets.UTF_8),
                        "webauthn.create", challenge, Set.of("https://example.com")));
        assertEquals(ClientDataException.Reason.MALFORMED, ex.reason());
    }
}
