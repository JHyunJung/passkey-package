package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishResponse;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge;
import com.crosscert.passkey.app.fido2.mds.MdsVerifier;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.policy.AaguidPolicyChecker;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.AuthenticationExtensionsClientOutputsConverter;
import com.webauthn4j.converter.CollectedClientDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AuthenticatorTransport;
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
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final MdsVerifier mds;
    private final AaguidPolicyChecker aaguidPolicyChecker;
    private final ObjectMapper mapper;
    private final ObjectConverter objectConverter;
    private final Clock clock;

    public RegistrationFinishService(ChallengeStore store,
                                     WebAuthnManager manager,
                                     TenantRepository tenants,
                                     CredentialRepository credentials,
                                     MdsVerifier mds,
                                     AaguidPolicyChecker aaguidPolicyChecker,
                                     ObjectMapper mapper,
                                     Clock clock) {
        this.store = store;
        this.manager = manager;
        this.tenants = tenants;
        this.credentials = credentials;
        this.mds = mds;
        this.aaguidPolicyChecker = aaguidPolicyChecker;
        this.mapper = mapper;
        this.objectConverter = new ObjectConverter();
        this.clock = clock;
    }

    @Transactional
    public RegistrationFinishResponse finish(RegistrationFinishRequest req) {
        RegistrationChallenge ch = store.takeRegistration(req.registrationToken())
                .orElseThrow(() -> new IllegalArgumentException(
                        "registration token missing or expired"));

        // codex P2: bind challenge tenant to current API-key tenant
        // before touching tenant config — defense-in-depth on top of
        // VPD. Mirrors AuthenticationFinishService.
        UUID ctxTenantUuid = TenantContextHolder.get();
        String ctxTenant = ctxTenantUuid == null ? null : ctxTenantUuid.toString();
        if (ctxTenant == null || !ctxTenant.equals(ch.tenantId())) {
            log.warn("tenant mismatch on registration/finish: ctx={} ch={}",
                    ctxTenant, ch.tenantId());
            throw new IllegalArgumentException("tenant mismatch");
        }

        Tenant tenant = tenants.findById(UUID.fromString(ch.tenantId()))
                .orElseThrow(() -> new IllegalStateException(
                        "tenant " + ch.tenantId() + " missing"));

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
        Set<Origin> origins = tenant.getAllowedOriginValues().stream()
                .map(Origin::create)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "tenant " + tenant.getId() + " has no allowed_origins configured");
        }
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
                /* userVerificationRequired */ tenant.isRequireUserVerification(),
                /* userPresenceRequired */ true);

        try {
            manager.verify(data, wParams);
        } catch (Exception e) {
            log.warn("attestation verify failed for tenant {}: {}", ch.tenantId(), e.toString());
            throw new IllegalArgumentException("attestation verify failed");
        }

        String fmt = data.getAttestationObject().getFormat();
        if (!tenant.getAcceptedFormatValues().contains(fmt)) {
            throw new IllegalArgumentException("attestation format not accepted by tenant policy");
        }

        // webauthn4j 0.31.5: AAGUID.getValue() returns UUID; getBytes() returns the raw 16-byte form.
        byte[] aaguid = data.getAttestationObject().getAuthenticatorData()
                .getAttestedCredentialData().getAaguid().getBytes();
        if (!mds.verify(tenant.isMdsRequired(), aaguid)) {
            throw new IllegalArgumentException("authenticator metadata verification failed");
        }

        // AAGUID 정책 검사 — MDS verify 통과 직후, credential 저장 직전
        // AaguidPolicyViolationException(BusinessException) 발생 시 GlobalExceptionHandler 가 HTTP 403 반환
        UUID aaguidUuid = aaguidFromBytes(aaguid);
        aaguidPolicyChecker.check(tenant.getId(), aaguidUuid);

        // Persist the four CredentialRecordImpl constructor inputs
        // (attestationObject, clientData, clientExtensions, transports)
        // in a JSON envelope. The natural choice would be to serialize
        // CredentialRecordImpl whole via objectConverter.getCborConverter(),
        // but webauthn4j 0.31.5's CredentialRecordImpl has no @JsonCreator
        // and Jackson 3 cannot synthesize one — so a CBOR readValue at
        // auth time throws "no Creators, like default constructor, exist".
        // The IT surfaced this as the actual blocker on first /authentication/finish.
        // Solution: round-trip each component through its own webauthn4j
        // converter (which IS @JsonCreator-friendly), envelope the bytes
        // as JSON, and reconstruct CredentialRecordImpl at verify time
        // via the public 4-arg constructor.
        byte[] credentialRecordBytes = serializeCredentialRecordEnvelope(data);

        byte[] credentialId = data.getAttestationObject().getAuthenticatorData()
                .getAttestedCredentialData().getCredentialId();
        Credential cred = new Credential(
                UUID.fromString(ch.tenantId()),
                ch.userHandle(),
                credentialId,
                credentialRecordBytes,
                aaguid);
        credentials.saveAndFlush(cred);

        String credentialIdB64 = b64url(credentialId);
        log.info("registration/finish ok: credentialIdTail={} aaguid={} format={}",
                idTail(credentialIdB64), aaguidUuid, fmt);

        return new RegistrationFinishResponse(
                credentialIdB64,
                aaguid == null ? null : HexFormat.of().formatHex(aaguid),
                fmt,
                clock.instant());
    }

    /** Last 12 chars of a base64url id for correlation — never the full credential id.
     *  Short ids (≤12 chars) are masked to "***" so the full value is never logged. */
    private static String idTail(String id) {
        if (id == null) return "null";
        if (id.length() <= 12) return "***";
        return "..." + id.substring(id.length() - 12);
    }

    private byte[] serializeCredentialRecordEnvelope(RegistrationData data) {
        try {
            AttestationObjectConverter aoConv = new AttestationObjectConverter(objectConverter);
            CollectedClientDataConverter cdConv = new CollectedClientDataConverter(objectConverter);
            AuthenticationExtensionsClientOutputsConverter ceConv =
                    new AuthenticationExtensionsClientOutputsConverter(objectConverter);

            byte[] aoBytes = aoConv.convertToBytes(data.getAttestationObject());
            byte[] cdBytes = cdConv.convertToBytes(data.getCollectedClientData());
            String ceJson = ceConv.convertToString(data.getClientExtensions());

            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("ao", b64url(aoBytes));
            envelope.put("cd", b64url(cdBytes));
            envelope.put("ce", ceJson);
            ArrayNode transports = envelope.putArray("tr");
            if (data.getTransports() != null) {
                for (AuthenticatorTransport t : data.getTransports()) {
                    if (t != null) transports.add(t.getValue());
                }
            }
            return mapper.writeValueAsBytes(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("credential record envelope serialization failed", e);
        }
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * 16-byte 원시 AAGUID 를 {@link UUID} 로 변환.
     * bytes 가 null 이거나 길이가 16 이 아니면 null 반환 (pass-through 처리).
     */
    private static UUID aaguidFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) return null;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes);
        long high = bb.getLong();
        long low = bb.getLong();
        return new UUID(high, low);
    }
}
