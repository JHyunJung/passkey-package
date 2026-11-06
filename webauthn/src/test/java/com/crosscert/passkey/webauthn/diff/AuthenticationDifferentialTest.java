package com.crosscert.passkey.webauthn.diff;

import com.crosscert.passkey.webauthn.verifier.AuthenticationInput;
import com.crosscert.passkey.webauthn.verifier.AuthenticationResult;
import com.crosscert.passkey.webauthn.verifier.NativeWebAuthnVerifier;
import com.crosscert.passkey.webauthn.verifier.StoredCredential;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * webauthn4j-test가 만든 진짜 packed authenticator의 assertion을 자체
 * 구현이 검증·통과시키는지 확인한다. (입력 생성에 webauthn4j-test를 쓰므로
 * "webauthn4j가 만든 서명을 자체 구현이 받아들인다"는 교차 검증이 된다.)
 */
class AuthenticationDifferentialTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NativeWebAuthnVerifier nativeVerifier = NativeWebAuthnVerifier.withDefaults(mapper);

    @Test
    void nativeVerifiesWebauthn4jGeneratedAssertion() throws Exception {
        CeremonyFixtures fx = new CeremonyFixtures("localhost", "https://localhost");
        byte[] authChallenge = "diff-auth".getBytes(StandardCharsets.UTF_8);
        CeremonyFixtures.AuthFixture af = fx.authenticate(
                "reg".getBytes(StandardCharsets.UTF_8), authChallenge, new byte[]{5, 6, 7, 8});

        StoredCredential stored = new StoredCredential(af.credentialId(), af.cosePublicKey(), 0);
        AuthenticationInput input = new AuthenticationInput(
                af.credentialJson(), authChallenge, Set.of(fx.origin()), fx.rpId(), false, stored);

        AuthenticationResult result = nativeVerifier.verifyAuthentication(input);
        assertArrayEquals(af.credentialId(), result.credentialId());
        assertTrue(result.newSignCount() >= 0);
    }

    @Test
    void nativeRejectsTamperedAssertionSignature() throws Exception {
        CeremonyFixtures fx = new CeremonyFixtures("localhost", "https://localhost");
        byte[] authChallenge = "diff-auth2".getBytes(StandardCharsets.UTF_8);
        CeremonyFixtures.AuthFixture af = fx.authenticate(
                "reg".getBytes(StandardCharsets.UTF_8), authChallenge, new byte[]{1});

        // tamper the signature in the assertion JSON
        tools.jackson.databind.node.ObjectNode root =
                (tools.jackson.databind.node.ObjectNode) mapper.readTree(af.credentialJson());
        tools.jackson.databind.node.ObjectNode resp =
                (tools.jackson.databind.node.ObjectNode) root.get("response");
        byte[] sig = java.util.Base64.getUrlDecoder().decode(resp.get("signature").asString());
        sig[sig.length - 1] ^= 0x01;
        resp.put("signature", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sig));

        StoredCredential stored = new StoredCredential(af.credentialId(), af.cosePublicKey(), 0);
        AuthenticationInput input = new AuthenticationInput(
                root.toString(), authChallenge, Set.of(fx.origin()), fx.rpId(), false, stored);

        com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException ex =
                assertThrows(com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException.class,
                        () -> nativeVerifier.verifyAuthentication(input));
        assertEquals(com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException.Reason.BAD_SIGNATURE,
                ex.reason());
    }
}
