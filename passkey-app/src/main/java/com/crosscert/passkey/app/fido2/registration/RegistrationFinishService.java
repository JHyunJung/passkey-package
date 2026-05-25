package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishResponse;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge;
import com.crosscert.passkey.app.fido2.mds.MdsStubVerifier;
import com.crosscert.passkey.app.fido2.policy.AttestationPolicy;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class RegistrationFinishService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationFinishService.class);

    /**
     * Phase 1 supported credential algorithms — mirrors the
     * pubKeyCredParams advertised by RegistrationStartService. Passing
     * this explicit list to webauthn4j enforces that registered
     * authenticators use one of these algorithms; passing null would
     * disable that check (codex P1 — security-critical).
     */
    private static final List<PublicKeyCredentialParameters> ALLOWED_PUB_KEY_CRED_PARAMS = List.of(
            new PublicKeyCredentialParameters(
                    PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(
                    PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

    private final ChallengeStore store;
    private final WebAuthnManager manager;
    private final TenantRepository tenants;
    private final CredentialRepository credentials;
    private final MdsStubVerifier mds;
    private final ObjectMapper mapper;
    private final ObjectConverter objectConverter;
    private final Clock clock;

    public RegistrationFinishService(ChallengeStore store,
                                     WebAuthnManager manager,
                                     TenantRepository tenants,
                                     CredentialRepository credentials,
                                     MdsStubVerifier mds,
                                     ObjectMapper mapper,
                                     Clock clock) {
        this.store = store;
        this.manager = manager;
        this.tenants = tenants;
        this.credentials = credentials;
        this.mds = mds;
        this.mapper = mapper;
        this.objectConverter = new ObjectConverter();
        this.clock = clock;
    }

    @Transactional
    public RegistrationFinishResponse finish(RegistrationFinishRequest req) {
        RegistrationChallenge ch = store.takeRegistration(req.registrationToken())
                .orElseThrow(() -> new IllegalArgumentException(
                        "registration token missing or expired"));

        Tenant tenant = tenants.findById(ch.tenantId())
                .orElseThrow(() -> new IllegalStateException(
                        "tenant " + ch.tenantId() + " missing"));
        AttestationPolicy policy = AttestationPolicy.fromJson(tenant.getAttestationPolicyJson());

        String publicKeyCredentialJson;
        try {
            publicKeyCredentialJson = mapper.writeValueAsString(req.publicKeyCredential());
        } catch (Exception e) {
            throw new IllegalArgumentException("publicKeyCredential JSON invalid");
        }

        RegistrationData data;
        try {
            data = manager.parseRegistrationResponseJSON(publicKeyCredentialJson);
        } catch (Exception e) {
            log.warn("attestation parse failed for tenant {}: {}", ch.tenantId(), e.toString());
            throw new IllegalArgumentException("attestation parse failed");
        }

        // Build the ServerProperty with ALL tenant origins so cross-
        // origin RP setups (e.g. www. + app.) work, not just the first.
        Set<Origin> origins = parseOrigins(tenant);
        ServerProperty serverProperty = ServerProperty.builder()
                .origins(origins)
                .rpId(tenant.getRpId())
                .challenge(new DefaultChallenge(ch.challenge()))
                .build();

        // Pass ALLOWED_PUB_KEY_CRED_PARAMS so webauthn4j enforces our
        // algorithm allow-list. Passing null would let an authenticator
        // register with a weaker algorithm (codex P1 — closed here).
        RegistrationParameters wParams = new RegistrationParameters(
                serverProperty,
                ALLOWED_PUB_KEY_CRED_PARAMS,
                /* userVerificationRequired */ policy.requireUserVerification(),
                /* userPresenceRequired */ true);

        try {
            manager.verify(data, wParams);
        } catch (Exception e) {
            log.warn("attestation verify failed for tenant {}: {}", ch.tenantId(), e.toString());
            throw new IllegalArgumentException("attestation verify failed");
        }

        String fmt = data.getAttestationObject().getFormat();
        if (!policy.acceptedFormats().contains(fmt)) {
            throw new IllegalArgumentException("attestation format not accepted by tenant policy");
        }

        // webauthn4j 0.31.5: AAGUID.getValue() returns UUID; getBytes() returns the raw 16-byte form.
        byte[] aaguid = data.getAttestationObject().getAuthenticatorData()
                .getAttestedCredentialData().getAaguid().getBytes();
        if (!mds.verify(policy, aaguid)) {
            throw new IllegalArgumentException("authenticator metadata verification failed");
        }

        CredentialRecord record = new CredentialRecordImpl(
                data.getAttestationObject(),
                data.getCollectedClientData(),
                data.getClientExtensions(),
                data.getTransports());
        byte[] credentialRecordBytes = objectConverter.getCborConverter().writeValueAsBytes(record);

        byte[] credentialId = data.getAttestationObject().getAuthenticatorData()
                .getAttestedCredentialData().getCredentialId();
        Credential cred = new Credential(
                ch.tenantId(),
                ch.userHandle(),
                credentialId,
                credentialRecordBytes,
                aaguid);
        credentials.saveAndFlush(cred);

        return new RegistrationFinishResponse(
                b64url(credentialId),
                aaguid == null ? null : HexFormat.of().formatHex(aaguid),
                fmt,
                clock.instant());
    }

    private Set<Origin> parseOrigins(Tenant t) {
        try {
            String[] originStrings = mapper.readValue(t.getAllowedOriginsJson(), String[].class);
            Set<Origin> set = new LinkedHashSet<>();
            for (String s : originStrings) {
                set.add(Origin.create(s));
            }
            if (set.isEmpty()) {
                throw new IllegalStateException(
                        "tenant " + t.getId() + " has no allowed_origins configured");
            }
            return set;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "tenant " + t.getId() + " allowed_origins JSON invalid", e);
        }
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
