package com.crosscert.passkey.webauthn.verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NativeRegistrationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NativeWebAuthnVerifier verifier = NativeWebAuthnVerifier.withDefaults(mapper);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @Test
    void verifiesNoneRegistration() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        byte[] challenge = "reg-challenge".getBytes(StandardCharsets.UTF_8);

        String clientData = "{\"type\":\"webauthn.create\",\"challenge\":\""
                + B64.encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";
        byte[] clientDataBytes = clientData.getBytes(StandardCharsets.UTF_8);

        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8));
        byte[] cose = TestCose.es256();
        byte[] authData = buildAuthData(rpIdHash, cose);

        byte[] ao = TestAttestationObject.none(authData);

        String credJson = "{\"id\":\"AQ\",\"rawId\":\"AQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientDataBytes) + "\","
                + "\"attestationObject\":\"" + B64.encodeToString(ao) + "\"}}";

        RegistrationInput input = new RegistrationInput(
                credJson, challenge, Set.of(origin), rpId,
                false, Set.of(COSEAlgorithm.ES256), Set.of("none"),
                AttestationTrustPolicy.NONE_ONLY);

        RegistrationResult result = verifier.verifyRegistration(input);

        assertNotNull(result.credentialId());
        assertArrayEquals(cose, result.cosePublicKey());
        assertEquals("none", result.attestationFormat());
        assertTrue(result.upVerified());
    }

    @Test
    void rejectsWrongChallenge() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        String clientData = "{\"type\":\"webauthn.create\",\"challenge\":\""
                + B64.encodeToString("attacker".getBytes()) + "\",\"origin\":\"" + origin + "\"}";
        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes());
        byte[] ao = TestAttestationObject.none(buildAuthData(rpIdHash, TestCose.es256()));
        String credJson = "{\"id\":\"AQ\",\"rawId\":\"AQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientData.getBytes()) + "\","
                + "\"attestationObject\":\"" + B64.encodeToString(ao) + "\"}}";

        RegistrationInput input = new RegistrationInput(
                credJson, "server".getBytes(), Set.of(origin), rpId,
                false, Set.of(COSEAlgorithm.ES256), Set.of("none"),
                AttestationTrustPolicy.NONE_ONLY);

        WebAuthnVerificationException ex = assertThrows(WebAuthnVerificationException.class,
                () -> verifier.verifyRegistration(input));
        assertEquals(WebAuthnVerificationException.Reason.CHALLENGE_MISMATCH, ex.reason());
    }

    @Test
    void rejectsWrongRpId() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        byte[] challenge = "c".getBytes(StandardCharsets.UTF_8);
        String clientData = "{\"type\":\"webauthn.create\",\"challenge\":\""
                + B64.encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";
        // authData built with WRONG rpIdHash (hash of different rpId)
        byte[] wrongHash = MessageDigest.getInstance("SHA-256").digest("evil.com".getBytes());
        byte[] ao = TestAttestationObject.none(buildAuthData(wrongHash, TestCose.es256()));
        String credJson = "{\"id\":\"AQ\",\"rawId\":\"AQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientData.getBytes()) + "\","
                + "\"attestationObject\":\"" + B64.encodeToString(ao) + "\"}}";

        RegistrationInput input = new RegistrationInput(
                credJson, challenge, Set.of(origin), rpId,
                false, Set.of(COSEAlgorithm.ES256), Set.of("none"),
                AttestationTrustPolicy.NONE_ONLY);

        WebAuthnVerificationException ex = assertThrows(WebAuthnVerificationException.class,
                () -> verifier.verifyRegistration(input));
        assertEquals(WebAuthnVerificationException.Reason.RP_ID_HASH_MISMATCH, ex.reason());
    }

    @Test
    void rejectsUvRequiredButAbsent() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        byte[] challenge = "c".getBytes(StandardCharsets.UTF_8);
        String clientData = "{\"type\":\"webauthn.create\",\"challenge\":\""
                + B64.encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";
        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes());
        // buildAuthData sets flags = UP + AT (0x41), no UV
        byte[] ao = TestAttestationObject.none(buildAuthData(rpIdHash, TestCose.es256()));
        String credJson = "{\"id\":\"AQ\",\"rawId\":\"AQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientData.getBytes()) + "\","
                + "\"attestationObject\":\"" + B64.encodeToString(ao) + "\"}}";

        RegistrationInput input = new RegistrationInput(
                credJson, challenge, Set.of(origin), rpId,
                true /* UV required */, Set.of(COSEAlgorithm.ES256), Set.of("none"),
                AttestationTrustPolicy.NONE_ONLY);

        WebAuthnVerificationException ex = assertThrows(WebAuthnVerificationException.class,
                () -> verifier.verifyRegistration(input));
        assertEquals(WebAuthnVerificationException.Reason.UV_REQUIRED, ex.reason());
    }

    @Test
    void rejectsRawIdMismatchWithAttestedCredentialId() throws Exception {
        // F31: 클라이언트 top-level rawId가 attestationObject 내부 attestedCredentialData의
        // credentialId와 다르면 거부해야 한다 (인증 경로 rejectsCredentialIdMismatch와 대칭).
        // authDataWithCredential/buildAuthData는 credId={0x01}(base64url "AQ")을 심는다.
        String rpId = "example.com";
        String origin = "https://example.com";
        byte[] challenge = "reg-challenge".getBytes(StandardCharsets.UTF_8);

        String clientData = "{\"type\":\"webauthn.create\",\"challenge\":\""
                + B64.encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";
        byte[] clientDataBytes = clientData.getBytes(StandardCharsets.UTF_8);

        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8));
        byte[] cose = TestCose.es256();
        byte[] authData = buildAuthData(rpIdHash, cose); // attested credentialId = {0x01}

        byte[] ao = TestAttestationObject.none(authData);

        // top-level rawId를 attested credentialId({0x01}, "AQ")와 다른 값({0x02}, "Ag")으로 조작.
        String mismatchedRawId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[]{0x02});
        String credJson = "{\"id\":\"" + mismatchedRawId + "\",\"rawId\":\"" + mismatchedRawId + "\","
                + "\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientDataBytes) + "\","
                + "\"attestationObject\":\"" + B64.encodeToString(ao) + "\"}}";

        RegistrationInput input = new RegistrationInput(
                credJson, challenge, Set.of(origin), rpId,
                false, Set.of(COSEAlgorithm.ES256), Set.of("none"),
                AttestationTrustPolicy.NONE_ONLY);

        WebAuthnVerificationException ex = assertThrows(WebAuthnVerificationException.class,
                () -> verifier.verifyRegistration(input));
        assertEquals(WebAuthnVerificationException.Reason.MALFORMED_INPUT, ex.reason());
    }

    @Test
    void rejectsBackupStateWithoutEligibility() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        byte[] challenge = "c".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String clientData = "{\"type\":\"webauthn.create\",\"challenge\":\""
                + B64.encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";
        byte[] rpIdHash = java.security.MessageDigest.getInstance("SHA-256").digest(rpId.getBytes());
        // flags: UP(0x01) + BS(0x10) + AT(0x40) = 0x51, BE(0x08) 없음 → BE=0,BS=1 (malformed)
        byte[] ao = TestAttestationObject.none(buildAuthDataFlags(rpIdHash, TestCose.es256(), 0x51));
        String credJson = "{\"id\":\"AQ\",\"rawId\":\"AQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientData.getBytes()) + "\","
                + "\"attestationObject\":\"" + B64.encodeToString(ao) + "\"}}";
        RegistrationInput input = new RegistrationInput(
                credJson, challenge, Set.of(origin), rpId,
                false, Set.of(COSEAlgorithm.ES256), Set.of("none"),
                AttestationTrustPolicy.NONE_ONLY);
        WebAuthnVerificationException ex = assertThrows(WebAuthnVerificationException.class,
                () -> verifier.verifyRegistration(input));
        assertEquals(WebAuthnVerificationException.Reason.MALFORMED_INPUT, ex.reason());
    }

    private static byte[] buildAuthData(byte[] rpIdHash, byte[] cose) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(rpIdHash);
        o.write(0x41); // UP + AT
        o.writeBytes(new byte[]{0, 0, 0, 0});
        o.writeBytes(new byte[16]);          // aaguid
        o.writeBytes(new byte[]{0, 1});      // credIdLen = 1
        o.write(0x01);                       // credId
        o.writeBytes(cose);
        return o.toByteArray();
    }

    private static byte[] buildAuthDataFlags(byte[] rpIdHash, byte[] cose, int flags) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.writeBytes(rpIdHash);
        o.write(flags);
        o.writeBytes(new byte[]{0, 0, 0, 0});
        o.writeBytes(new byte[16]);          // aaguid
        o.writeBytes(new byte[]{0, 1});      // credIdLen = 1
        o.write(0x01);                       // credId
        o.writeBytes(cose);
        return o.toByteArray();
    }
}
