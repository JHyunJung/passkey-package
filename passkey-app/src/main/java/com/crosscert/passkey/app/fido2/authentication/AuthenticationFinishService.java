package com.crosscert.passkey.app.fido2.authentication;

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishResponse;
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder;
import com.crosscert.passkey.core.ceremony.CredentialAuthEventRecorder;
import com.crosscert.passkey.core.ceremony.CredentialAuthFailureReason;
import com.crosscert.passkey.core.ceremony.CredentialAuthResult;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.jwt.IdTokenIssuer;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.crosscert.passkey.webauthn.verifier.AuthenticationInput;
import com.crosscert.passkey.webauthn.verifier.AuthenticationResult;
import com.crosscert.passkey.webauthn.verifier.StoredCredential;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.crosscert.passkey.app.fido2.CeremonyMetrics;
import com.crosscert.passkey.core.alert.SecurityAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthenticationFinishService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFinishService.class);

    private final ChallengeStore store;
    private final WebAuthnVerifier verifier;
    private final TenantRepository tenants;
    private final CredentialRepository credentials;
    private final IdTokenIssuer idTokens;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CeremonyMetrics ceremonyMetrics;
    private final CeremonyEventRecorder ceremonyEvents;
    private final ApplicationEventPublisher eventPublisher;
    private final CredentialAuthEventRecorder authEvents;

    public AuthenticationFinishService(ChallengeStore store,
                                       WebAuthnVerifier verifier,
                                       TenantRepository tenants,
                                       CredentialRepository credentials,
                                       IdTokenIssuer idTokens,
                                       ObjectMapper mapper,
                                       Clock clock,
                                       CeremonyMetrics ceremonyMetrics,
                                       ApplicationEventPublisher eventPublisher,
                                       CeremonyEventRecorder ceremonyEvents,
                                       CredentialAuthEventRecorder authEvents) {
        this.store = store;
        this.verifier = verifier;
        this.tenants = tenants;
        this.credentials = credentials;
        this.idTokens = idTokens;
        this.mapper = mapper;
        this.clock = clock;
        this.ceremonyMetrics = ceremonyMetrics;
        this.eventPublisher = eventPublisher;
        this.ceremonyEvents = ceremonyEvents;
        this.authEvents = authEvents;
    }

    @Transactional
    public AuthenticationFinishResponse finish(AuthenticationFinishRequest req) {
        try {
            AuthenticationChallenge ch = store.takeAuthentication(req.authenticationToken())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "authentication token missing or expired"));

            // codex P2: bind challenge tenant to current API-key tenant before
            // touching tenant config — defense-in-depth on top of VPD.
            UUID ctxTenantUuid = TenantContextHolder.get();
            String ctxTenant = ctxTenantUuid == null ? null : ctxTenantUuid.toString();
            if (ctxTenant == null || !ctxTenant.equals(ch.tenantId())) {
                log.warn("tenant mismatch on authentication/finish: ctx={} ch={}",
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

            // Extract the asserted credential id (rawId, base64url) from the
            // request JSON so we can lock + load the stored row BEFORE
            // verification. The native verifier also binds the assertion
            // rawId to storedCredential.credentialId internally (defense in
            // depth), so a mismatch is rejected there as well.
            byte[] credentialId;
            try {
                String rawIdB64 = req.publicKeyCredential().get("rawId").asText();
                credentialId = Base64.getUrlDecoder().decode(rawIdB64);
            } catch (Exception e) {
                throw new IllegalArgumentException("assertion rawId invalid");
            }

            // codex P1: lock the row before the read-check-update on
            // signCount to prevent two concurrent /finish calls from both
            // passing the strict-monotonic check. VPD filters by tenant.
            Credential cred = credentials.findByCredentialIdForUpdate(credentialId)
                    .orElseThrow(() -> new IllegalArgumentException("credential not registered"));

            // codex P1: typed flow must bind the asserted credential to the
            // challenge's userHandle. Without this, server-side allowCredentials
            // is tautological (any same-tenant credential matching the
            // asserted ID would be accepted).
            if (ch.userHandle() != null && !Arrays.equals(ch.userHandle(), cred.getUserHandle())) {
                log.warn("userHandle mismatch on authentication/finish for tenant {}", ch.tenantId());
                throw new IllegalArgumentException("credential not registered");
            }

            StoredCredential stored = new StoredCredential(
                    cred.getCredentialId(),
                    cred.getCosePublicKey(),
                    cred.getSignCount());

            Set<String> origins = new LinkedHashSet<>(tenant.getAllowedOriginValues());
            if (origins.isEmpty()) {
                throw new IllegalStateException(
                        "tenant " + tenant.getId() + " has no allowed_origins configured");
            }

            AuthenticationInput input = new AuthenticationInput(
                    publicKeyCredentialJson,
                    ch.challenge(),
                    origins,
                    tenant.getRpId(),
                    tenant.isRequireUserVerification(),
                    stored);

            AuthenticationResult result;
            try {
                result = verifier.verifyAuthentication(input);
            } catch (WebAuthnVerificationException e) {
                log.warn("assertion verify failed for tenant {}: {} ({})",
                        ch.tenantId(), e.getMessage(), e.reason());
                // spec §6.1: credential 식별 이후의 검증 실패도 기록한다. newCounter 는
                // 아직 검증 전이라 보존된 cred.getSignCount() 를 signCount 로 남긴다.
                // recordAfterRollback: cred 는 PESSIMISTIC_WRITE 로 락된 행이라 즉시
                // REQUIRES_NEW INSERT 하면 FK 락 경합이 난다. outer 롤백 완료(락 해제) 후 기록.
                authEvents.recordAfterRollback(cred.getId(), UUID.fromString(ch.tenantId()),
                        CredentialAuthResult.FAILED, CredentialAuthFailureReason.SIGNATURE_INVALID,
                        cred.getSignCount());
                throw new IllegalArgumentException("assertion verify failed");
            }

            // codex P2: WebAuthn allows authenticators with no counter to
            // return 0 forever. The verifier already rejects non-increasing
            // counters when EITHER side is non-zero; only the (0,0) case
            // is treated as "no-counter" and accepted. Mirror that here so
            // we don't refuse legitimate passkeys.
            long newCounter = result.newSignCount();
            long storedCounter = cred.getSignCount();
            boolean counterless = (newCounter == 0 && storedCounter == 0);
            if (!counterless && newCounter <= storedCounter) {
                log.warn("signCount did not advance for credential {} on tenant {} (was {}, got {})",
                        cred.getId(), ch.tenantId(), storedCounter, newCounter);
                eventPublisher.publishEvent(new SecurityAlertEvent(
                        SecurityAlertEvent.AlertType.COUNTER_REGRESSION,
                        SecurityAlertEvent.Severity.HIGH,
                        "signCount did not advance (possible cloned authenticator)",
                        Map.of(
                                "credentialId", String.valueOf(cred.getId()),
                                "tenantId", String.valueOf(ch.tenantId()))));
                // recordAfterRollback: 위 verify 실패와 동일한 이유(락된 cred 행과의 FK
                // 락 경합 회피) — outer 롤백 완료 후 기록한다.
                authEvents.recordAfterRollback(cred.getId(), UUID.fromString(ch.tenantId()),
                        CredentialAuthResult.FAILED, CredentialAuthFailureReason.SIGN_COUNT_REPLAY,
                        newCounter);
                throw new IllegalArgumentException("signCount replay detected");
            }

            // Persist the new counter. The strict-monotonic check above reads
            // cred.getSignCount(), so the persisted column is the single
            // source of truth for replay defense.
            //
            // Pessimistic lock (above) + the @Transactional boundary make
            // this read-check-update sequence safe under concurrency.
            cred.recordAuthentication(newCounter, clock.instant());
            credentials.saveAndFlush(cred);
            authEvents.recordAfterCommit(cred.getId(), UUID.fromString(ch.tenantId()),
                    CredentialAuthResult.SUCCESS, null, newCounter);

            String credentialIdB64 = b64url(credentialId);
            log.info("authentication/finish ok: credentialIdTail={} counter={}",
                    idTail(credentialIdB64), newCounter);

            String jwt = idTokens.issue(
                    ch.tenantId(),
                    cred.getUserHandle(),
                    cred.getId(),
                    cred.getAaguid());
            // id-token claims meta only — never the JWT body itself
            String subTail = idTail(b64url(cred.getUserHandle()));
            log.info("id-token issued: subTail={} aud={} ttlSec={}",
                    subTail, ch.tenantId(), 900);

            AuthenticationFinishResponse response = new AuthenticationFinishResponse(jwt, "Bearer", 900);
            ceremonyEvents.recordAfterCommit(UUID.fromString(ch.tenantId()), CeremonyAction.AUTHENTICATION_SUCCESS);
            ceremonyMetrics.recordSuccess("authentication", "finish");
            return response;
        } catch (RuntimeException e) {
            ceremonyMetrics.recordFailure("authentication", "finish");
            throw e;
        }
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Last 12 chars of a base64url id for correlation — never the full id.
     *  Short ids (≤12 chars) are masked to "***" so the full value is never logged. */
    private static String idTail(String id) {
        if (id == null) return "null";
        if (id.length() <= 12) return "***";
        return "..." + id.substring(id.length() - 12);
    }

}
