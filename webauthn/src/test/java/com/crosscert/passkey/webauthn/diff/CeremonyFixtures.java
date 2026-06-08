package com.crosscert.passkey.webauthn.diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.PackedAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;

import java.util.Base64;
import java.util.List;

/**
 * Mints REAL packed registration responses using webauthn4j-test's
 * {@link ClientPlatform} + {@link PackedAuthenticator} + {@link WebAuthnAuthenticatorAdaptor}.
 * The produced WebAuthn-L3 JSON is fed verbatim to BOTH the native verifier and
 * the webauthn4j adapter so the differential test compares them on identical input.
 *
 * <p>Pattern copied from {@code Fido2TestAuthenticator} (the production app's
 * integration-test helper), which uses the same webauthn4j 0.31.5 emulator API.
 *
 * <p>What the packed authenticator emits (confirmed from webauthn4j-test 0.31.5
 * sources): it ALWAYS produces an x5c (full) packed attestation — the leaf is a
 * fresh X.509 v3 cert (BigInteger serial=1, validity 2000..2999) over the fixed
 * 3-tier test attestation key pair, with NO Basic Constraints (so not a CA) and
 * NO id-fido-gen-ce-aaguid extension. AAGUID is {@code AAGUID.ZERO}. This passes
 * the native PackedAttestationVerifier §8.2.1 leaf checks (v3, non-CA,
 * AAGUID-extension absent → skipped).
 */
final class CeremonyFixtures {

    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();
    private static final Base64.Encoder B64URL_ENC = Base64.getUrlEncoder().withoutPadding();

    private final ObjectMapper mapper = new ObjectMapper();
    private final String rpId;
    private final String origin;
    private final ClientPlatform clientPlatform;

    CeremonyFixtures(String rpId, String origin) {
        this.rpId = rpId;
        this.origin = origin;
        // Fresh PackedAuthenticator per fixture so credential + counter state
        // never leaks between scenarios. EmulatorUtil.PACKED_AUTHENTICATOR is a
        // shared singleton; we instantiate our own and wrap it in the adaptor.
        PackedAuthenticator model = new PackedAuthenticator();
        WebAuthnAuthenticatorAdaptor adaptor = new WebAuthnAuthenticatorAdaptor(model);
        this.clientPlatform = new ClientPlatform(Origin.create(origin), adaptor);
    }

    String origin() {
        return origin;
    }

    String rpId() {
        return rpId;
    }

    /**
     * Drive the packed authenticator to produce a real attestation for the
     * given challenge and userHandle, and serialize it as the WebAuthn-L3
     * registration JSON ({id, rawId, type, response:{clientDataJSON,
     * attestationObject, transports}}) the verifiers accept.
     */
    JsonNode registerPacked(byte[] challenge, byte[] userHandle) {
        PublicKeyCredentialRpEntity rp = new PublicKeyCredentialRpEntity(rpId, "Diff RP");
        PublicKeyCredentialUserEntity user = new PublicKeyCredentialUserEntity(
                userHandle, "diff-user", "Diff User");
        Challenge ch = new DefaultChallenge(challenge);
        List<PublicKeyCredentialParameters> params = List.of(
                new PublicKeyCredentialParameters(
                        PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256));

        // webauthn4j-test's WebAuthnAuthenticatorAdaptor.register dereferences
        // getAuthenticatorSelection().getResidentKey(), so a non-null selection
        // is mandatory. DISCOURAGED → non-resident credential (the common case);
        // PREFERRED user verification lets the emulator set UV.
        AuthenticatorSelectionCriteria selection = new AuthenticatorSelectionCriteria(
                /* authenticatorAttachment */ null,
                ResidentKeyRequirement.DISCOURAGED,
                UserVerificationRequirement.PREFERRED);

        // Creation options: rp, user, challenge, pubKeyCredParams, timeout,
        // excludeCredentials, authenticatorSelection, attestation, extensions.
        // DIRECT so the emulator emits the packed attestation statement
        // (ClientPlatform.create only supports DIRECT/NONE).
        PublicKeyCredentialCreationOptions options = new PublicKeyCredentialCreationOptions(
                rp, user, ch, params,
                /* timeout */ null,
                /* excludeCredentials */ null,
                selection,
                AttestationConveyancePreference.DIRECT,
                /* extensions */ null);

        PublicKeyCredential<AuthenticatorAttestationResponse, ?> credential =
                clientPlatform.create(options);
        return toRegistrationJson(credential);
    }

    private JsonNode toRegistrationJson(
            PublicKeyCredential<AuthenticatorAttestationResponse, ?> cred) {
        ObjectNode root = mapper.createObjectNode();
        String id = B64URL_ENC.encodeToString(cred.getRawId());
        root.put("id", id);
        root.put("rawId", id);
        root.put("type", "public-key");
        ObjectNode response = root.putObject("response");
        response.put("clientDataJSON", B64URL_ENC.encodeToString(
                cred.getResponse().getClientDataJSON()));
        response.put("attestationObject", B64URL_ENC.encodeToString(
                cred.getResponse().getAttestationObject()));
        ArrayNode transports = response.putArray("transports");
        if (cred.getResponse().getTransports() != null) {
            for (var t : cred.getResponse().getTransports()) {
                transports.add(t.getValue());
            }
        }
        root.putObject("clientExtensionResults");
        return root;
    }
}
