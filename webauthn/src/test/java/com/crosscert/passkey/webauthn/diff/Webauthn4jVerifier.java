package com.crosscert.passkey.webauthn.diff;

import com.crosscert.passkey.webauthn.verifier.AuthenticationInput;
import com.crosscert.passkey.webauthn.verifier.AuthenticationResult;
import com.crosscert.passkey.webauthn.verifier.COSEAlgorithm;
import com.crosscert.passkey.webauthn.verifier.RegistrationInput;
import com.crosscert.passkey.webauthn.verifier.RegistrationResult;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException.Reason;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Differential-test-only {@link com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier}
 * implementation backed by webauthn4j. It produces the same pass/fail verdict
 * and the same extracted fields (credentialId, attestationFormat, signCount,
 * aaguid) as {@link com.crosscert.passkey.webauthn.verifier.NativeWebAuthnVerifier},
 * so {@code RegistrationDifferentialTest} can feed the SAME real packed
 * registration response to both and assert they agree.
 *
 * <p>This class lives in the TEST source set only — webauthn4j is a
 * {@code testImplementation} dependency and never reaches production.
 *
 * <p>webauthn4j 0.31.5 API notes (confirmed against the jar sources and
 * {@code RegistrationFinishService}):
 * <ul>
 *   <li>{@code WebAuthnManager.createNonStrictWebAuthnManager()} — non-strict
 *       so it accepts the full set of attestation formats without an MDS.</li>
 *   <li>flags: {@code AuthenticatorData#isFlagUV()/isFlagUP()/isFlagBE()/isFlagBS()}.</li>
 *   <li>aaguid: {@code AttestedCredentialData#getAaguid().getBytes()} (raw 16 bytes).</li>
 *   <li>signCount: {@code AuthenticatorData#getSignCount()}.</li>
 *   <li>credentialId: {@code AttestedCredentialData#getCredentialId()}.</li>
 * </ul>
 */
public final class Webauthn4jVerifier
        implements com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier {

    private final WebAuthnManager manager = WebAuthnManager.createNonStrictWebAuthnManager();

    /**
     * Map the boundary {@link COSEAlgorithm} set the app passed into the
     * webauthn4j pubKeyCredParams allow-list so the adapter enforces exactly
     * the algorithms the contract specifies — not a hardcoded ES256+RS256 set
     * (codex P2-1). This keeps the differential comparison faithful: both
     * verifiers see the same algorithm policy.
     */
    private static List<PublicKeyCredentialParameters> pubKeyCredParams(Set<COSEAlgorithm> algs) {
        List<PublicKeyCredentialParameters> params = new ArrayList<>();
        for (COSEAlgorithm alg : algs) {
            COSEAlgorithmIdentifier id = switch (alg) {
                case ES256 -> COSEAlgorithmIdentifier.ES256;
                case RS256 -> COSEAlgorithmIdentifier.RS256;
            };
            params.add(new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, id));
        }
        return params;
    }

    @Override
    public RegistrationResult verifyRegistration(RegistrationInput input)
            throws WebAuthnVerificationException {
        RegistrationData data;
        try {
            data = manager.parseRegistrationResponseJSON(input.credentialJson());
        } catch (RuntimeException e) {
            throw new WebAuthnVerificationException(Reason.MALFORMED_INPUT, e.toString(), e);
        }

        Set<Origin> origins = new LinkedHashSet<>();
        for (String origin : input.allowedOrigins()) {
            origins.add(Origin.create(origin));
        }
        ServerProperty serverProperty = ServerProperty.builder()
                .origins(origins)
                .rpId(input.rpId())
                .challenge(new DefaultChallenge(input.challenge()))
                .build();

        RegistrationParameters params = new RegistrationParameters(
                serverProperty,
                pubKeyCredParams(input.allowedAlgorithms()),
                /* userVerificationRequired */ input.userVerificationRequired(),
                /* userPresenceRequired */ true);

        try {
            manager.verify(data, params);
        } catch (RuntimeException e) {
            throw new WebAuthnVerificationException(Reason.BAD_SIGNATURE, e.toString(), e);
        }

        String format = data.getAttestationObject().getFormat();
        AuthenticatorData<?> authData = data.getAttestationObject().getAuthenticatorData();
        byte[] credentialId = authData.getAttestedCredentialData().getCredentialId();
        byte[] aaguid = authData.getAttestedCredentialData().getAaguid().getBytes();
        long signCount = authData.getSignCount();

        Set<String> transports = new LinkedHashSet<>();
        if (data.getTransports() != null) {
            for (AuthenticatorTransport t : data.getTransports()) {
                if (t != null) transports.add(t.getValue());
            }
        }

        return new RegistrationResult(
                credentialId,
                /* cosePublicKey — not compared (Task 4 proves the native COSE round-trip) */ null,
                signCount,
                aaguid,
                format,
                transports,
                authData.isFlagUV(),
                authData.isFlagUP(),
                authData.isFlagBE(),
                authData.isFlagBS());
    }

    @Override
    public AuthenticationResult verifyAuthentication(AuthenticationInput input) {
        // Task 18 handles authentication differentially (CredentialRecord
        // reconstruction in webauthn4j is non-trivial); not implemented here.
        throw new UnsupportedOperationException("auth diff handled separately");
    }
}
