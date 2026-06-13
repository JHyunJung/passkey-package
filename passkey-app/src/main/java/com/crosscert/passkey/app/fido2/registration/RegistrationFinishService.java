package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishResponse;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge;
import com.crosscert.passkey.app.fido2.mds.MdsVerifier;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.policy.AaguidPolicyChecker;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.crosscert.passkey.webauthn.verifier.AttestationTrustPolicy;
import com.crosscert.passkey.webauthn.verifier.COSEAlgorithm;
import com.crosscert.passkey.webauthn.verifier.RegistrationInput;
import com.crosscert.passkey.webauthn.verifier.RegistrationResult;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.crosscert.passkey.app.fido2.CeremonyMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class RegistrationFinishService {

    private final ChallengeStore store;
    private final WebAuthnVerifier verifier;
    private final TenantRepository tenants;
    private final CredentialRepository credentials;
    private final MdsVerifier mds;
    private final AaguidPolicyChecker aaguidPolicyChecker;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CeremonyMetrics ceremonyMetrics;
    private final CeremonyEventRecorder ceremonyEvents;

    public RegistrationFinishService(ChallengeStore store,
                                     WebAuthnVerifier verifier,
                                     TenantRepository tenants,
                                     CredentialRepository credentials,
                                     MdsVerifier mds,
                                     AaguidPolicyChecker aaguidPolicyChecker,
                                     ObjectMapper mapper,
                                     Clock clock,
                                     CeremonyMetrics ceremonyMetrics,
                                     CeremonyEventRecorder ceremonyEvents) {
        this.store = store;
        this.verifier = verifier;
        this.tenants = tenants;
        this.credentials = credentials;
        this.mds = mds;
        this.aaguidPolicyChecker = aaguidPolicyChecker;
        this.mapper = mapper;
        this.clock = clock;
        this.ceremonyMetrics = ceremonyMetrics;
        this.ceremonyEvents = ceremonyEvents;
    }

    @Transactional
    public RegistrationFinishResponse finish(RegistrationFinishRequest req) {
        try {
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

            Set<String> origins = new LinkedHashSet<>(tenant.getAllowedOriginValues());
            if (origins.isEmpty()) {
                throw new IllegalStateException(
                        "tenant " + tenant.getId() + " has no allowed_origins configured");
            }

            RegistrationInput input = new RegistrationInput(
                    publicKeyCredentialJson,
                    ch.challenge(),
                    origins,
                    tenant.getRpId(),
                    tenant.isRequireUserVerification(),
                    Set.of(COSEAlgorithm.ES256, COSEAlgorithm.RS256),
                    tenant.getAcceptedFormatValues(),
                    AttestationTrustPolicy.SELF_ALLOWED);

            RegistrationResult result;
            try {
                result = verifier.verifyRegistration(input);
            } catch (WebAuthnVerificationException e) {
                log.warn("attestation verify failed for tenant {}: {} ({})",
                        ch.tenantId(), e.getMessage(), e.reason());
                throw new IllegalArgumentException("attestation verify failed");
            }

            String fmt = result.attestationFormat();
            if (!tenant.getAcceptedFormatValues().contains(fmt)) {
                throw new IllegalArgumentException("attestation format not accepted by tenant policy");
            }

            // AAGUID 정책 검사 — MDS verify 통과 직후, credential 저장 직전
            // AaguidPolicyViolationException(BusinessException) 발생 시 GlobalExceptionHandler 가 HTTP 403 반환
            byte[] aaguid = result.aaguid();
            if (!mds.verify(tenant.isMdsRequired(), aaguid)) {
                throw new IllegalArgumentException("authenticator metadata verification failed");
            }

            UUID aaguidUuid = aaguidFromBytes(aaguid);
            aaguidPolicyChecker.check(tenant.getId(), aaguidUuid);

            byte[] credentialId = result.credentialId();
            Credential cred = new Credential(
                    UUID.fromString(ch.tenantId()),
                    ch.userHandle(),
                    credentialId,
                    result.cosePublicKey(),
                    aaguid);
            cred.setAttestationFmt(fmt);
            if (result.transports() != null && !result.transports().isEmpty()) {
                cred.setTransports(String.join(",", result.transports()));
            }
            credentials.saveAndFlush(cred);

            String credentialIdB64 = b64url(credentialId);
            log.info("registration/finish ok: credentialIdTail={} aaguid={} format={}",
                    idTail(credentialIdB64), aaguidUuid, fmt);

            RegistrationFinishResponse response = new RegistrationFinishResponse(
                    credentialIdB64,
                    aaguid == null ? null : HexFormat.of().formatHex(aaguid),
                    fmt,
                    clock.instant());
            ceremonyEvents.recordAfterCommit(UUID.fromString(ch.tenantId()), CeremonyAction.REGISTRATION_SUCCESS);
            ceremonyMetrics.recordSuccess("registration", "finish");
            return response;
        } catch (RuntimeException e) {
            ceremonyMetrics.recordFailure("registration", "finish");
            throw e;
        }
    }

    /** Last 12 chars of a base64url id for correlation — never the full credential id.
     *  Short ids (≤12 chars) are masked to "***" so the full value is never logged. */
    private static String idTail(String id) {
        if (id == null) return "null";
        if (id.length() <= 12) return "***";
        return "..." + id.substring(id.length() - 12);
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
