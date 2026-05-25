package com.crosscert.passkey.app.fido2.authentication;

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishResponse;
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.jwt.IdTokenIssuer;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AuthenticationFinishService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFinishService.class);

    private final ChallengeStore store;
    private final WebAuthnManager manager;
    private final TenantRepository tenants;
    private final CredentialRepository credentials;
    private final IdTokenIssuer idTokens;
    private final ObjectMapper mapper;
    private final ObjectConverter objectConverter;
    private final Clock clock;

    public AuthenticationFinishService(ChallengeStore store,
                                       WebAuthnManager manager,
                                       TenantRepository tenants,
                                       CredentialRepository credentials,
                                       IdTokenIssuer idTokens,
                                       ObjectMapper mapper,
                                       Clock clock) {
        this.store = store;
        this.manager = manager;
        this.tenants = tenants;
        this.credentials = credentials;
        this.idTokens = idTokens;
        this.mapper = mapper;
        this.objectConverter = new ObjectConverter();
        this.clock = clock;
    }

    @Transactional
    public AuthenticationFinishResponse finish(AuthenticationFinishRequest req) {
        AuthenticationChallenge ch = store.takeAuthentication(req.authenticationToken())
                .orElseThrow(() -> new IllegalArgumentException(
                        "authentication token missing or expired"));

        // codex P2: bind challenge tenant to current API-key tenant before
        // touching tenant config — defense-in-depth on top of VPD.
        String ctxTenant = TenantContextHolder.get();
        if (ctxTenant == null || !ctxTenant.equals(ch.tenantId())) {
            log.warn("tenant mismatch on authentication/finish: ctx={} ch={}",
                    ctxTenant, ch.tenantId());
            throw new IllegalArgumentException("tenant mismatch");
        }

        Tenant tenant = tenants.findById(ch.tenantId())
                .orElseThrow(() -> new IllegalStateException(
                        "tenant " + ch.tenantId() + " missing"));

        String publicKeyCredentialJson;
        try {
            publicKeyCredentialJson = mapper.writeValueAsString(req.publicKeyCredential());
        } catch (Exception e) {
            throw new IllegalArgumentException("publicKeyCredential JSON invalid");
        }

        AuthenticationData data;
        try {
            data = manager.parseAuthenticationResponseJSON(publicKeyCredentialJson);
        } catch (Exception e) {
            log.warn("assertion parse failed for tenant {}: {}", ch.tenantId(), e.toString());
            throw new IllegalArgumentException("assertion parse failed");
        }

        byte[] credentialId = data.getCredentialId();

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

        // codex P2: sanitize CBOR deserialization failures the same way
        // parse/verify failures are handled.
        CredentialRecord record;
        try {
            record = objectConverter.getCborConverter()
                    .readValue(cred.getCredentialRecordBytes(), CredentialRecordImpl.class);
        } catch (Exception e) {
            log.warn("stored credential record deserialization failed for credential {} on tenant {}: {}",
                    cred.getId(), ch.tenantId(), e.toString());
            throw new IllegalArgumentException("assertion verify failed");
        }

        Set<Origin> origins = parseOrigins(tenant);
        ServerProperty serverProperty = ServerProperty.builder()
                .origins(origins)
                .rpId(tenant.getRpId())
                .challenge(new DefaultChallenge(ch.challenge()))
                .build();

        AuthenticationParameters wParams = new AuthenticationParameters(
                serverProperty,
                record,
                /* allowCredentials */ List.of(credentialId),
                /* userVerificationRequired */ true,
                /* userPresenceRequired */ true);

        try {
            manager.verify(data, wParams);
        } catch (Exception e) {
            log.warn("assertion verify failed for tenant {}: {}", ch.tenantId(), e.toString());
            throw new IllegalArgumentException("assertion verify failed");
        }

        // codex P2: WebAuthn allows authenticators with no counter to
        // return 0 forever. webauthn4j already rejects non-increasing
        // counters when EITHER side is non-zero; only the (0,0) case
        // is treated as "no-counter" and accepted. Mirror that here so
        // we don't refuse legitimate passkeys.
        long newCounter = data.getAuthenticatorData().getSignCount();
        long storedCounter = cred.getSignCount();
        boolean counterless = (newCounter == 0 && storedCounter == 0);
        if (!counterless && newCounter <= storedCounter) {
            log.warn("signCount did not advance for credential {} on tenant {} (was {}, got {})",
                    cred.getId(), ch.tenantId(), storedCounter, newCounter);
            throw new IllegalArgumentException("signCount replay detected");
        }

        // Mutate the in-memory CredentialRecord, re-serialize, persist.
        // Pessimistic lock (above) + the @Transactional boundary make
        // this read-check-update sequence safe under concurrency.
        record.setCounter(newCounter);
        byte[] updatedBytes = objectConverter.getCborConverter().writeValueAsBytes(record);
        cred.recordAuthentication(newCounter, updatedBytes, clock.instant());
        credentials.saveAndFlush(cred);

        String jwt = idTokens.issue(
                ch.tenantId(),
                cred.getUserHandle(),
                cred.getId(),
                cred.getAaguid());
        return new AuthenticationFinishResponse(jwt, "Bearer", 900);
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

}
