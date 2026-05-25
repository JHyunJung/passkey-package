package com.crosscert.passkey.app.fido2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
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

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Wraps a {@link ClientPlatform} backed by webauthn4j-test's packed
 * authenticator. Translates the server's JSON options into the typed
 * webauthn4j options the emulator expects, and the emulator's typed
 * response back into the WebAuthn-Level-3 JSON shape the server's
 * {@code WebAuthnManager.parseRegistrationResponseJSON} accepts.
 *
 * <p>One instance can be reused across register + authenticate within
 * the same test: the underlying authenticator stores the credential and
 * counter state in memory.
 *
 * <p>API-shape notes (webauthn4j 0.31.5):
 * <ul>
 *   <li>{@code EmulatorUtil.createClientPlatform(Origin)} does not exist
 *       — we instantiate {@link ClientPlatform} with the
 *       {@code (Origin, AuthenticatorAdaptor)} constructor and pass
 *       {@code EmulatorUtil.PACKED_AUTHENTICATOR}'s adaptor explicitly.</li>
 *   <li>The server emits "packed" attestation acceptable to the tenant's
 *       policy ({@code acceptedFormats=["none","packed"]}), so the
 *       packed authenticator's attestation statement passes the policy
 *       check in {@code RegistrationFinishService}.</li>
 * </ul>
 */
final class Fido2TestAuthenticator {

    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();
    private static final Base64.Encoder B64URL_ENC = Base64.getUrlEncoder().withoutPadding();

    private final ClientPlatform clientPlatform;
    private final ObjectMapper mapper;

    Fido2TestAuthenticator(Origin origin, ObjectMapper mapper) {
        // EmulatorUtil only ships a (no-arg → https://localhost) factory
        // and an (AuthenticatorAdaptor) overload — neither lets us pin
        // the origin to whatever the tenant config advertises. The
        // 2-arg ClientPlatform constructor is the closest thing.
        //
        // EmulatorUtil.PACKED_AUTHENTICATOR is the model, not the adaptor —
        // wrap it ourselves. Use a fresh PackedAuthenticator instance per
        // test invocation so credential and counter state never leak
        // between scenarios (the static singleton would carry counter
        // state forward, defeating the signCount-replay scenario).
        PackedAuthenticator model = new PackedAuthenticator();
        WebAuthnAuthenticatorAdaptor adaptor = new WebAuthnAuthenticatorAdaptor(model);
        this.clientPlatform = new ClientPlatform(origin, adaptor);
        this.mapper = mapper;
    }

    /**
     * Drive the authenticator to produce an attestation in response to
     * the JSON {@code publicKeyCredentialCreationOptions} block the
     * server returned from /registration/start. Returns the JSON the
     * client must POST to /registration/finish under
     * {@code publicKeyCredential}.
     */
    JsonNode register(JsonNode serverCreationOptions) {
        PublicKeyCredentialCreationOptions options = parseCreationOptions(serverCreationOptions);
        PublicKeyCredential<AuthenticatorAttestationResponse, ?> credential =
                clientPlatform.create(options);
        return toRegistrationJson(credential);
    }

    /**
     * Drive the authenticator to produce an assertion in response to
     * the JSON {@code publicKeyCredentialRequestOptions} block the
     * server returned from /authentication/start. Returns the JSON the
     * client must POST to /authentication/finish under
     * {@code publicKeyCredential}.
     */
    JsonNode authenticate(JsonNode serverRequestOptions) {
        PublicKeyCredentialRequestOptions options = parseRequestOptions(serverRequestOptions);
        PublicKeyCredential<AuthenticatorAssertionResponse, ?> credential =
                clientPlatform.get(options);
        return toAuthenticationJson(credential);
    }

    // ---------- JSON -> typed options ----------

    private PublicKeyCredentialCreationOptions parseCreationOptions(JsonNode n) {
        PublicKeyCredentialRpEntity rp = new PublicKeyCredentialRpEntity(
                n.get("rp").get("id").asText(),
                n.get("rp").get("name").asText());

        JsonNode user = n.get("user");
        PublicKeyCredentialUserEntity userEntity = new PublicKeyCredentialUserEntity(
                B64URL_DEC.decode(user.get("id").asText()),
                user.get("name").asText(),
                user.get("displayName").asText());

        Challenge challenge = new DefaultChallenge(B64URL_DEC.decode(n.get("challenge").asText()));

        List<PublicKeyCredentialParameters> params = new ArrayList<>();
        for (JsonNode p : n.get("pubKeyCredParams")) {
            long alg = p.get("alg").asLong();
            COSEAlgorithmIdentifier coseAlg = COSEAlgorithmIdentifier.create(alg);
            params.add(new PublicKeyCredentialParameters(
                    PublicKeyCredentialType.create(p.get("type").asText()),
                    coseAlg));
        }

        Long timeout = n.hasNonNull("timeout") ? n.get("timeout").asLong() : null;

        AuthenticatorSelectionCriteria sel = null;
        if (n.hasNonNull("authenticatorSelection")) {
            JsonNode s = n.get("authenticatorSelection");
            UserVerificationRequirement uv = s.hasNonNull("userVerification")
                    ? UserVerificationRequirement.create(s.get("userVerification").asText())
                    : null;
            ResidentKeyRequirement rk = s.hasNonNull("residentKey")
                    ? ResidentKeyRequirement.create(s.get("residentKey").asText())
                    : null;
            // The 3-arg (attachment, residentKey, userVerification)
            // ctor — we don't model attachment in this RP.
            sel = new AuthenticatorSelectionCriteria(null, rk, uv);
        }

        // webauthn4j-test's ClientPlatform.create only knows DIRECT and
        // NONE — INDIRECT throws NotImplementedException. The server's
        // RegistrationStartService emits "indirect" (a reasonable
        // production choice), so we coerce to DIRECT here so the
        // emulator produces the packed attestation statement the
        // server's policy accepts. The server validates the attestation
        // FORMAT against tenant policy; the client-requested conveyance
        // preference is informational and does not affect verification.
        AttestationConveyancePreference att = AttestationConveyancePreference.DIRECT;

        // Use the 4-arg "lean" constructor that omits everything we
        // don't model (excludeCredentials, extensions, hints), to keep
        // the typed options minimal and avoid wandering into nullable
        // overloads with mismatched arg counts.
        return new PublicKeyCredentialCreationOptions(rp, userEntity, challenge, params,
                timeout, /* excludeCredentials */ null, sel, att, /* extensions */ null);
    }

    private PublicKeyCredentialRequestOptions parseRequestOptions(JsonNode n) {
        Challenge challenge = new DefaultChallenge(B64URL_DEC.decode(n.get("challenge").asText()));
        Long timeout = n.hasNonNull("timeout") ? n.get("timeout").asLong() : null;
        String rpId = n.hasNonNull("rpId") ? n.get("rpId").asText() : null;

        List<PublicKeyCredentialDescriptor> allow = new ArrayList<>();
        if (n.hasNonNull("allowCredentials")) {
            for (JsonNode entry : n.get("allowCredentials")) {
                allow.add(new PublicKeyCredentialDescriptor(
                        PublicKeyCredentialType.create(entry.get("type").asText()),
                        B64URL_DEC.decode(entry.get("id").asText()),
                        /* transports */ null));
            }
        }
        if (allow.isEmpty()) {
            allow = null;
        }

        UserVerificationRequirement uv = n.hasNonNull("userVerification")
                ? UserVerificationRequirement.create(n.get("userVerification").asText())
                : null;

        return new PublicKeyCredentialRequestOptions(
                challenge, timeout, rpId, allow, uv, /* extensions */ null);
    }

    // ---------- typed response -> JSON the server accepts ----------

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
        // Spec says "transports" is OPTIONAL on the registration response;
        // webauthn4j's parseRegistrationResponseJSON tolerates absence
        // and presence equally.
        ArrayNode transports = response.putArray("transports");
        if (cred.getResponse().getTransports() != null) {
            for (var t : cred.getResponse().getTransports()) {
                transports.add(t.getValue());
            }
        }
        root.putObject("clientExtensionResults");
        return root;
    }

    private JsonNode toAuthenticationJson(
            PublicKeyCredential<AuthenticatorAssertionResponse, ?> cred) {
        ObjectNode root = mapper.createObjectNode();
        String id = B64URL_ENC.encodeToString(cred.getRawId());
        root.put("id", id);
        root.put("rawId", id);
        root.put("type", "public-key");
        ObjectNode response = root.putObject("response");
        response.put("clientDataJSON", B64URL_ENC.encodeToString(
                cred.getResponse().getClientDataJSON()));
        response.put("authenticatorData", B64URL_ENC.encodeToString(
                cred.getResponse().getAuthenticatorData()));
        response.put("signature", B64URL_ENC.encodeToString(
                cred.getResponse().getSignature()));
        byte[] uh = cred.getResponse().getUserHandle();
        if (uh != null) {
            response.put("userHandle", B64URL_ENC.encodeToString(uh));
        }
        root.putObject("clientExtensionResults");
        return root;
    }
}
