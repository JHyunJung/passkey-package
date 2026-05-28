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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.AuthenticationExtensionsClientOutputsConverter;
import com.webauthn4j.converter.CollectedClientDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.client.CollectedClientData;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.data.extension.client.AuthenticationExtensionsClientOutputs;
import com.webauthn4j.data.extension.client.RegistrationExtensionClientOutput;
import com.webauthn4j.server.ServerProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

        // codex P2: sanitize deserialization failures the same way
        // parse/verify failures are handled.
        //
        // Storage envelope (set by RegistrationFinishService.serializeCredentialRecordEnvelope):
        //   { "ao": "<b64url AttestationObject CBOR>",
        //     "cd": "<b64url CollectedClientData JSON>",
        //     "ce": "<JSON string of AuthenticationExtensionsClientOutputs>",
        //     "tr": ["usb", ...] }
        // We must round-trip each component through the corresponding
        // webauthn4j Converter and then call the public 4-arg
        // CredentialRecordImpl constructor. The natural shortcut of
        // CborConverter.readValue(bytes, CredentialRecordImpl.class)
        // does not work in webauthn4j 0.31.5: that class has no
        // @JsonCreator/default constructor and Jackson 3 will not
        // synthesize one (manifests at runtime as "no Creators…
        // cannot deserialize from Object value").
        CredentialRecord record;
        try {
            record = deserializeCredentialRecordEnvelope(cred.getCredentialRecordBytes());
        } catch (Exception e) {
            log.warn("stored credential record deserialization failed for credential {} on tenant {}: {}",
                    cred.getId(), ch.tenantId(), e.toString());
            throw new IllegalArgumentException("assertion verify failed");
        }

        // Leave the reconstructed record's counter at the registration
        // baseline for manager.verify(...). webauthn4j's default
        // verifier only enforces tampering on the signature/origin/
        // challenge — it does NOT enforce strict-monotonic counter
        // semantics on its own. That's the app's job; see the
        // explicit check below this verify call. Advancing the
        // record's counter to cred.signCount BEFORE verify would mask
        // our own strict-monotonic branch (codex round-2 P1).

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

        AuthenticationParameters wParams = new AuthenticationParameters(
                serverProperty,
                record,
                /* allowCredentials */ List.of(credentialId),
                /* userVerificationRequired */ tenant.isRequireUserVerification(),
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

        // Persist the new counter. We only update the cred.signCount
        // column (the envelope's stored counter is the AttestationObject's
        // initial value and cannot change without re-serializing the
        // immutable AttestationObject). The strict-monotonic check above
        // reads cred.getSignCount(), so the persisted column is the
        // single source of truth for replay defense.
        //
        // Pessimistic lock (above) + the @Transactional boundary make
        // this read-check-update sequence safe under concurrency.
        record.setCounter(newCounter);
        cred.recordAuthentication(newCounter, cred.getCredentialRecordBytes(), clock.instant());
        credentials.saveAndFlush(cred);

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

        return new AuthenticationFinishResponse(jwt, "Bearer", 900);
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

    private CredentialRecord deserializeCredentialRecordEnvelope(byte[] envelopeBytes) throws Exception {
        JsonNode env = mapper.readTree(envelopeBytes);

        byte[] ao = Base64.getUrlDecoder().decode(env.get("ao").asText());
        byte[] cd = Base64.getUrlDecoder().decode(env.get("cd").asText());
        JsonNode ceNode = env.get("ce");
        String ceJson = ceNode == null || ceNode.isNull() ? "{}" : ceNode.asText();
        JsonNode trNode = env.get("tr");

        AttestationObjectConverter aoConv = new AttestationObjectConverter(objectConverter);
        CollectedClientDataConverter cdConv = new CollectedClientDataConverter(objectConverter);
        AuthenticationExtensionsClientOutputsConverter ceConv =
                new AuthenticationExtensionsClientOutputsConverter(objectConverter);

        AttestationObject attestationObject = aoConv.convert(ao);
        CollectedClientData clientData = cdConv.convert(cd);
        AuthenticationExtensionsClientOutputs<RegistrationExtensionClientOutput> clientExtensions =
                ceConv.convert(ceJson);

        Set<AuthenticatorTransport> transports = new LinkedHashSet<>();
        if (trNode != null && trNode.isArray()) {
            for (JsonNode t : trNode) {
                String value = t.asText();
                if (value != null && !value.isBlank()) {
                    transports.add(AuthenticatorTransport.create(value));
                }
            }
        }

        return new CredentialRecordImpl(
                attestationObject,
                clientData,
                clientExtensions,
                transports);
    }

}
