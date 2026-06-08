package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.u2f.FIDOU2FAuthenticator;
import com.webauthn4j.test.authenticator.u2f.FIDOU2FAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Approach A — REAL cross-impl vector. webauthn4j-test's FIDO-U2F authenticator
 * (FIDOU2FAuthenticator + FIDOU2FAuthenticatorAdaptor + ClientPlatform) mints a
 * genuine fmt=fido-u2f registration; the native FidoU2fAttestationVerifier must
 * verify it (WebAuthn §8.6). This proves the native U2F signature-base
 * reconstruction (0x00 || rpIdHash || clientDataHash || credentialId ||
 * 0x04||x||y) matches what a real U2F authenticator signs.
 */
class FidoU2fAttestationVerifierTest {

    private static final String RP_ID = "example.com";
    private static final String ORIGIN = "https://example.com";

    private final FidoU2fAttestationVerifier verifier = new FidoU2fAttestationVerifier();

    @Test
    void formatIsFidoU2f() {
        assertEquals("fido-u2f", verifier.format());
    }

    @Test
    void verifiesRealWebauthn4jU2fAttestation() throws Exception {
        Registered reg = mintU2fRegistration();

        AttestationObject ao = AttestationObjectDecoder.decode(reg.attestationObject);
        assertEquals("fido-u2f", ao.format(), "emulator must emit fido-u2f fmt");

        byte[] clientDataHash = sha256(reg.clientDataJSON);

        AttestationResult r = verifier.verify(
                ao.authData(), ao.rawAuthData(), ao.attStmt(), clientDataHash);

        assertEquals(AttestationResult.Type.BASIC, r.type());
        assertEquals(1, r.trustPath().size(), "fido-u2f trust path is the single leaf cert");
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        Registered reg = mintU2fRegistration();
        AttestationObject ao = AttestationObjectDecoder.decode(reg.attestationObject);
        byte[] clientDataHash = sha256(reg.clientDataJSON);

        // Flip the attStmt sig bytes to a same-length garbage signature.
        CborBytes origSig = (CborBytes) ao.attStmt().get("sig");
        byte[] tampered = origSig.value().clone();
        tampered[tampered.length - 1] ^= 0x01;

        CborValue x5c = ao.attStmt().get("x5c");
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("sig"), new CborBytes(tampered));
        m.put(new CborText("x5c"), x5c);
        CborMap badAttStmt = new CborMap(m);

        assertThrows(AttestationException.class,
                () -> verifier.verify(ao.authData(), ao.rawAuthData(), badAttStmt, clientDataHash));
    }

    @Test
    void rejectsWrongClientDataHash() throws Exception {
        // A clientDataHash that doesn't match the registration breaks the
        // signature base → native must reject (not silently pass).
        Registered reg = mintU2fRegistration();
        AttestationObject ao = AttestationObjectDecoder.decode(reg.attestationObject);

        assertThrows(AttestationException.class,
                () -> verifier.verify(ao.authData(), ao.rawAuthData(), ao.attStmt(), new byte[32]));
    }

    // --- helpers -----------------------------------------------------------

    private record Registered(byte[] attestationObject, byte[] clientDataJSON) {}

    /** Drive webauthn4j-test's U2F authenticator to produce a real fido-u2f registration. */
    private static Registered mintU2fRegistration() {
        FIDOU2FAuthenticatorAdaptor adaptor =
                new FIDOU2FAuthenticatorAdaptor(new FIDOU2FAuthenticator());
        ClientPlatform clientPlatform = new ClientPlatform(Origin.create(ORIGIN), adaptor);

        PublicKeyCredentialRpEntity rp = new PublicKeyCredentialRpEntity(RP_ID, "U2F RP");
        PublicKeyCredentialUserEntity user = new PublicKeyCredentialUserEntity(
                new byte[]{1, 2, 3, 4}, "u2f-user", "U2F User");
        List<PublicKeyCredentialParameters> params = List.of(
                new PublicKeyCredentialParameters(
                        PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256));
        // Non-null selection required by the adaptor path; U2F is non-resident.
        AuthenticatorSelectionCriteria selection = new AuthenticatorSelectionCriteria(
                null,
                ResidentKeyRequirement.DISCOURAGED,
                UserVerificationRequirement.DISCOURAGED);
        // DIRECT so the emulator emits the fido-u2f attestation statement.
        PublicKeyCredentialCreationOptions options = new PublicKeyCredentialCreationOptions(
                rp, user, new DefaultChallenge(new byte[]{10, 20, 30, 40, 50, 60}), params,
                null, null, selection,
                AttestationConveyancePreference.DIRECT, null);

        PublicKeyCredential<AuthenticatorAttestationResponse, ?> credential =
                clientPlatform.create(options);
        return new Registered(
                credential.getResponse().getAttestationObject(),
                credential.getResponse().getClientDataJSON());
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
