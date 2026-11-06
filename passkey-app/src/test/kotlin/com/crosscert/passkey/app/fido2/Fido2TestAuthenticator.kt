package com.crosscert.passkey.app.fido2

import com.webauthn4j.data.AttestationConveyancePreference
import com.webauthn4j.data.AuthenticatorAssertionResponse
import com.webauthn4j.data.AuthenticatorAttestationResponse
import com.webauthn4j.data.AuthenticatorSelectionCriteria
import com.webauthn4j.data.AuthenticatorTransport
import com.webauthn4j.data.PublicKeyCredential
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialDescriptor
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.PublicKeyCredentialRpEntity
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.PublicKeyCredentialUserEntity
import com.webauthn4j.data.ResidentKeyRequirement
import com.webauthn4j.data.UserVerificationRequirement
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.test.authenticator.webauthn.PackedAuthenticator
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor
import com.webauthn4j.test.client.ClientPlatform
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.Base64

private val B64URL_DEC: Base64.Decoder = Base64.getUrlDecoder()
private val B64URL_ENC: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

/**
 * Wraps a [ClientPlatform] backed by webauthn4j-test's packed
 * authenticator. Translates the server's JSON options into the typed
 * webauthn4j options the emulator expects, and the emulator's typed
 * response back into the WebAuthn-Level-3 JSON shape the server's
 * `WebAuthnManager.parseRegistrationResponseJSON` accepts.
 *
 * One instance can be reused across register + authenticate within
 * the same test: the underlying authenticator stores the credential and
 * counter state in memory.
 *
 * API-shape notes (webauthn4j 0.31.5):
 * - `EmulatorUtil.createClientPlatform(Origin)` does not exist
 *   — we instantiate [ClientPlatform] with the
 *   `(Origin, AuthenticatorAdaptor)` constructor and pass
 *   `EmulatorUtil.PACKED_AUTHENTICATOR`'s adaptor explicitly.
 * - The server emits "packed" attestation acceptable to the tenant's
 *   policy (`acceptedFormats=["none","packed"]`), so the
 *   packed authenticator's attestation statement passes the policy
 *   check in `RegistrationFinishService`.
 */
class Fido2TestAuthenticator(origin: Origin, private val mapper: ObjectMapper) {

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
    private val clientPlatform = ClientPlatform(origin, WebAuthnAuthenticatorAdaptor(PackedAuthenticator()))

    /**
     * Drive the authenticator to produce an attestation in response to
     * the JSON `publicKeyCredentialCreationOptions` block the
     * server returned from /registration/start. Returns the JSON the
     * client must POST to /registration/finish under
     * `publicKeyCredential`.
     */
    fun register(serverCreationOptions: JsonNode): JsonNode =
        toRegistrationJson(clientPlatform.create(parseCreationOptions(serverCreationOptions)))

    /**
     * Drive the authenticator to produce an assertion in response to
     * the JSON `publicKeyCredentialRequestOptions` block the
     * server returned from /authentication/start. Returns the JSON the
     * client must POST to /authentication/finish under
     * `publicKeyCredential`.
     */
    fun authenticate(serverRequestOptions: JsonNode): JsonNode =
        toAuthenticationJson(clientPlatform.get(parseRequestOptions(serverRequestOptions)))

    // ---------- JSON -> typed options ----------

    private fun parseCreationOptions(n: JsonNode): PublicKeyCredentialCreationOptions {
        val rp = PublicKeyCredentialRpEntity(
            n.get("rp").get("id").asString(),
            n.get("rp").get("name").asString(),
        )

        val user = n.get("user")
        val userEntity = PublicKeyCredentialUserEntity(
            B64URL_DEC.decode(user.get("id").asString()),
            user.get("name").asString(),
            user.get("displayName").asString(),
        )

        val challenge = DefaultChallenge(B64URL_DEC.decode(n.get("challenge").asString()))

        val params = n.get("pubKeyCredParams").values().map { p ->
            PublicKeyCredentialParameters(
                PublicKeyCredentialType.create(p.get("type").asString()),
                COSEAlgorithmIdentifier.create(p.get("alg").asLong()),
            )
        }

        val timeout = if (n.hasNonNull("timeout")) n.get("timeout").asLong() else null

        val sel = if (n.hasNonNull("authenticatorSelection")) {
            val s = n.get("authenticatorSelection")
            val uv = if (s.hasNonNull("userVerification")) {
                UserVerificationRequirement.create(s.get("userVerification").asString())
            } else {
                null
            }
            val rk = if (s.hasNonNull("residentKey")) {
                ResidentKeyRequirement.create(s.get("residentKey").asString())
            } else {
                null
            }
            // The 3-arg (attachment, residentKey, userVerification)
            // ctor — we don't model attachment in this RP.
            AuthenticatorSelectionCriteria(null, rk, uv)
        } else {
            null
        }

        // webauthn4j-test's ClientPlatform.create only knows DIRECT and
        // NONE — INDIRECT throws NotImplementedException. The server's
        // RegistrationStartService emits "indirect" (a reasonable
        // production choice), so we coerce to DIRECT here so the
        // emulator produces the packed attestation statement the
        // server's policy accepts. The server validates the attestation
        // FORMAT against tenant policy; the client-requested conveyance
        // preference is informational and does not affect verification.
        val att = AttestationConveyancePreference.DIRECT

        // Use the 9-arg "lean" constructor that omits everything we
        // don't model (excludeCredentials, extensions, hints), to keep
        // the typed options minimal and avoid wandering into nullable
        // overloads with mismatched arg counts.
        return PublicKeyCredentialCreationOptions(
            rp, userEntity, challenge, params, timeout,
            /* excludeCredentials */ null, sel, att, /* extensions */ null,
        )
    }

    private fun parseRequestOptions(n: JsonNode): PublicKeyCredentialRequestOptions {
        val challenge = DefaultChallenge(B64URL_DEC.decode(n.get("challenge").asString()))
        val timeout = if (n.hasNonNull("timeout")) n.get("timeout").asLong() else null
        val rpId = if (n.hasNonNull("rpId")) n.get("rpId").asString() else null

        val allow = if (n.hasNonNull("allowCredentials")) {
            n.get("allowCredentials").values().map { entry ->
                PublicKeyCredentialDescriptor(
                    PublicKeyCredentialType.create(entry.get("type").asString()),
                    B64URL_DEC.decode(entry.get("id").asString()),
                    /* transports */ null,
                )
            }
        } else {
            emptyList()
        }

        val uv = if (n.hasNonNull("userVerification")) {
            UserVerificationRequirement.create(n.get("userVerification").asString())
        } else {
            null
        }

        return PublicKeyCredentialRequestOptions(
            challenge, timeout, rpId, allow.ifEmpty { null }, uv, /* extensions */ null,
        )
    }

    // ---------- typed response -> JSON the server accepts ----------

    private fun toRegistrationJson(cred: PublicKeyCredential<AuthenticatorAttestationResponse, *>): JsonNode {
        // webauthn4j 는 response 를 @Nullable 로 선언하지만 ClientPlatform.create/get 은 항상 채운다(원 Java 는 그대로 역참조).
        val attestation = checkNotNull(cred.response) { "attestation response is null" }
        val root = mapper.createObjectNode()
        val id = B64URL_ENC.encodeToString(cred.rawId)
        root.put("id", id)
        root.put("rawId", id)
        root.put("type", "public-key")
        val response = root.putObject("response")
        response.put("clientDataJSON", B64URL_ENC.encodeToString(attestation.clientDataJSON))
        response.put("attestationObject", B64URL_ENC.encodeToString(attestation.attestationObject))
        // Spec says "transports" is OPTIONAL on the registration response;
        // webauthn4j's parseRegistrationResponseJSON tolerates absence
        // and presence equally.
        val transports = response.putArray("transports")
        // getTransports() 는 @NotNull 로 선언됐지만 생성자가 null 을 그대로 보관한다(CollectionUtil.unmodifiableSet(null)
        // == null) — 원 Java 와 같이 null 을 방어한다.
        val declaredTransports: Set<AuthenticatorTransport>? = attestation.transports
        declaredTransports?.forEach { transports.add(it.value) }
        root.putObject("clientExtensionResults")
        return root
    }

    private fun toAuthenticationJson(cred: PublicKeyCredential<AuthenticatorAssertionResponse, *>): JsonNode {
        val assertion = checkNotNull(cred.response) { "assertion response is null" }
        val root = mapper.createObjectNode()
        val id = B64URL_ENC.encodeToString(cred.rawId)
        root.put("id", id)
        root.put("rawId", id)
        root.put("type", "public-key")
        val response = root.putObject("response")
        response.put("clientDataJSON", B64URL_ENC.encodeToString(assertion.clientDataJSON))
        response.put("authenticatorData", B64URL_ENC.encodeToString(assertion.authenticatorData))
        response.put("signature", B64URL_ENC.encodeToString(assertion.signature))
        assertion.userHandle?.let { response.put("userHandle", B64URL_ENC.encodeToString(it)) }
        root.putObject("clientExtensionResults")
        return root
    }
}
