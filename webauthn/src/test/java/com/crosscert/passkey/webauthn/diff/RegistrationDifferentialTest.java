package com.crosscert.passkey.webauthn.diff;

import com.crosscert.passkey.webauthn.verifier.AttestationTrustPolicy;
import com.crosscert.passkey.webauthn.verifier.COSEAlgorithm;
import com.crosscert.passkey.webauthn.verifier.NativeWebAuthnVerifier;
import com.crosscert.passkey.webauthn.verifier.RegistrationInput;
import com.crosscert.passkey.webauthn.verifier.RegistrationResult;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationDifferentialTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NativeWebAuthnVerifier nativeVerifier = NativeWebAuthnVerifier.withDefaults(mapper);
    private final Webauthn4jVerifier w4j = new Webauthn4jVerifier();

    @Test
    void packedRegistrationAgreesAcrossImplementations() throws Exception {
        CeremonyFixtures fx = new CeremonyFixtures("localhost", "https://localhost");
        byte[] challenge = "diff-reg".getBytes(StandardCharsets.UTF_8);
        String credJson = fx.registerPacked(challenge, new byte[]{1, 2, 3, 4}).toString();

        RegistrationInput input = new RegistrationInput(
                credJson, challenge, Set.of(fx.origin()), fx.rpId(),
                false, Set.of(COSEAlgorithm.ES256), Set.of("packed"),
                AttestationTrustPolicy.SELF_ALLOWED);

        RegistrationResult w4jResult = w4j.verifyRegistration(input);

        // 베이스라인이 trivial(null/empty)이 아님을 먼저 보장 — 둘 다 회귀해도
        // assertEquals(null,null)로 통과하는 false-confidence 방지 (codex P2-2).
        assertNotNull(w4jResult.credentialId());
        assertTrue(w4jResult.credentialId().length > 0, "credentialId must be non-empty");
        assertEquals("packed", w4jResult.attestationFormat());
        assertNotNull(w4jResult.aaguid());
        assertEquals(16, w4jResult.aaguid().length, "aaguid must be 16 bytes");

        RegistrationResult nativeResult = nativeVerifier.verifyRegistration(input);

        assertArrayEquals(w4jResult.credentialId(), nativeResult.credentialId(),
                "credentialId must match webauthn4j");
        assertEquals(w4jResult.attestationFormat(), nativeResult.attestationFormat());
        assertEquals(w4jResult.signCount(), nativeResult.signCount());
        assertArrayEquals(w4jResult.aaguid(), nativeResult.aaguid());
    }

    @Test
    void bothRejectTamperedChallenge() throws Exception {
        CeremonyFixtures fx = new CeremonyFixtures("localhost", "https://localhost");
        byte[] challenge = "real".getBytes(StandardCharsets.UTF_8);
        String credJson = fx.registerPacked(challenge, new byte[]{1}).toString();

        RegistrationInput input = new RegistrationInput(
                credJson, "wrong".getBytes(StandardCharsets.UTF_8),
                Set.of(fx.origin()), fx.rpId(),
                false, Set.of(COSEAlgorithm.ES256), Set.of("packed"),
                AttestationTrustPolicy.SELF_ALLOWED);

        assertThrows(WebAuthnVerificationException.class, () -> nativeVerifier.verifyRegistration(input));
        assertThrows(WebAuthnVerificationException.class, () -> w4j.verifyRegistration(input));
    }

    @Test
    void bothRejectTamperedAttestationObject() throws Exception {
        CeremonyFixtures fx = new CeremonyFixtures("localhost", "https://localhost");
        byte[] challenge = "att".getBytes(StandardCharsets.UTF_8);
        JsonNode reg = fx.registerPacked(challenge, new byte[]{1});

        // attestationObject 안의 authData rpIdHash 첫 바이트를 flip 한다.
        // clientDataJSON·challenge·origin·rpId 는 그대로 두어 "유효 challenge +
        // 변조된 attestation" 시나리오를 만든다 (codex P2-3).
        //
        // 왜 rpIdHash 를 노리나: attestationObject 의 마지막 바이트(미사용 CA 인증서
        // x5c[1] 꼬리)나 attStmt.sig 를 flip 하면 native 는 거부하지만 webauthn4j 의
        // createNonStrictWebAuthnManager 는 NullPackedAttestationStatementVerifier 를
        // 써서 attestation statement 서명을 아예 검증하지 않아 통과한다(문서화된 동작).
        // → 그 경로는 "양쪽 거부" 가 성립하지 않는다(아래 BehaviorDifferenceNote 참고).
        // rpIdHash 는 두 구현 모두가 검증하는 ceremony 불변식이라 양쪽이 확실히 거부한다.
        //
        // rpIdHash = SHA-256(rpId) 는 authData 선두 32바이트라, attestationObject CBOR
        // 안에서 그 다이제스트를 그대로 찾아 첫 바이트를 flip 한다(native 내부 디코더에
        // 의존하지 않는 위치 탐색).
        ObjectNode root = (ObjectNode) reg;
        ObjectNode resp = (ObjectNode) root.get("response");
        byte[] ao = Base64.getUrlDecoder().decode(resp.get("attestationObject").asText());
        byte[] rpIdHash = MessageDigest.getInstance("SHA-256")
                .digest(fx.rpId().getBytes(StandardCharsets.UTF_8));
        int pos = indexOf(ao, rpIdHash);
        assertTrue(pos >= 0, "rpIdHash must be locatable inside attestationObject authData");
        ao[pos] ^= 0x01; // flip rpIdHash 첫 바이트 → CBOR 길이는 불변, 값만 손상
        resp.put("attestationObject", Base64.getUrlEncoder().withoutPadding().encodeToString(ao));
        String credJson = root.toString();

        RegistrationInput input = new RegistrationInput(
                credJson, challenge, Set.of(fx.origin()), fx.rpId(),
                false, Set.of(COSEAlgorithm.ES256), Set.of("packed"),
                AttestationTrustPolicy.SELF_ALLOWED);

        assertThrows(WebAuthnVerificationException.class, () -> nativeVerifier.verifyRegistration(input));
        assertThrows(WebAuthnVerificationException.class, () -> w4j.verifyRegistration(input));
    }

    /**
     * Differential finding (NOT a defect): native verifies the packed
     * attestation-statement signature unconditionally (PackedAttestationVerifier),
     * whereas webauthn4j's {@code createNonStrictWebAuthnManager} wires a
     * {@code NullPackedAttestationStatementVerifier} and skips it. So flipping a
     * byte in {@code attStmt.sig} (the attestation signature) is REJECTED by
     * native (BAD_SIGNATURE) but ACCEPTED by non-strict webauthn4j. The native
     * verifier is the stricter of the two here; we surface the difference rather
     * than weaken native. This test documents and pins that behavior.
     */
    @Test
    void nativeIsStricterThanNonStrictW4jOnAttestationSignature() throws Exception {
        CeremonyFixtures fx = new CeremonyFixtures("localhost", "https://localhost");
        byte[] challenge = "att-sig".getBytes(StandardCharsets.UTF_8);
        JsonNode reg = fx.registerPacked(challenge, new byte[]{1});

        ObjectNode root = (ObjectNode) reg;
        ObjectNode resp = (ObjectNode) root.get("response");
        byte[] ao = Base64.getUrlDecoder().decode(resp.get("attestationObject").asText());

        // attStmt.sig 는 ECDSA DER 서명(0x30 SEQUENCE 로 시작). attestationObject 안에서
        // sig 바이트열을 찾기 위해, native 디코더로 sig 를 꺼내 그 위치를 탐색한 뒤 중간
        // 바이트를 flip 한다(이 테스트는 의도적으로 native 내부 디코더를 사용).
        com.crosscert.passkey.webauthn.attestation.AttestationObject decoded =
                com.crosscert.passkey.webauthn.attestation.AttestationObjectDecoder.decode(ao);
        byte[] sig = ((com.crosscert.passkey.webauthn.cbor.CborValue.CborBytes)
                decoded.attStmt().get("sig")).value();
        int pos = indexOf(ao, sig);
        assertTrue(pos >= 0, "attStmt.sig must be locatable inside attestationObject");
        ao[pos + sig.length / 2] ^= 0x01; // 서명 중간 바이트 손상
        resp.put("attestationObject", Base64.getUrlEncoder().withoutPadding().encodeToString(ao));
        String credJson = root.toString();

        RegistrationInput input = new RegistrationInput(
                credJson, challenge, Set.of(fx.origin()), fx.rpId(),
                false, Set.of(COSEAlgorithm.ES256), Set.of("packed"),
                AttestationTrustPolicy.SELF_ALLOWED);

        // native: 패킷 x5c 서명 검증 실패로 거부.
        WebAuthnVerificationException ex = assertThrows(WebAuthnVerificationException.class,
                () -> nativeVerifier.verifyRegistration(input));
        assertEquals(WebAuthnVerificationException.Reason.BAD_SIGNATURE, ex.reason());

        // non-strict webauthn4j: attestation statement 서명을 검증하지 않으므로 통과.
        assertDoesNotThrow(() -> w4j.verifyRegistration(input),
                "non-strict webauthn4j skips packed attestation signature verification");
    }

    /** 바이트 배열 needle 의 첫 등장 위치(없으면 -1). */
    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
