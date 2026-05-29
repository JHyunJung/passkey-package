package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

@Service
public class RegistrationStartService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RegistrationStartService.class);

    private final TenantRepository tenants;
    private final CredentialRepository credentials;
    private final ChallengeIssuer challenges;
    private final ChallengeStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public RegistrationStartService(TenantRepository tenants,
                                    CredentialRepository credentials,
                                    ChallengeIssuer challenges,
                                    ChallengeStore store,
                                    ObjectMapper mapper,
                                    Clock clock) {
        this.tenants = tenants;
        this.credentials = credentials;
        this.challenges = challenges;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    public RegistrationStartResponse start(RegistrationStartRequest req) {
        log.info("registration/start entry: usernamePresent={} displayNameLen={}",
                req.username() != null,
                req.displayName() == null ? 0 : req.displayName().length());
        UUID tenantUuid = TenantContextHolder.get();
        if (tenantUuid == null) {
            throw new IllegalStateException(
                    "registration/start invoked without tenant context — ApiKeyAuthFilter "
                            + "must have set it");
        }
        String tenantId = tenantUuid.toString();
        Tenant tenant = tenants.findById(tenantUuid)
                .orElseThrow(() -> new IllegalStateException(
                        "tenant " + tenantId + " not found"));

        byte[] userHandle = Base64.getUrlDecoder().decode(req.userHandle());
        byte[] challenge = challenges.newChallengeBytes();
        String token = challenges.newToken();

        store.putRegistration(token, new RegistrationChallenge(
                tenantId, challenge, userHandle, req.displayName(), req.username(),
                clock.instant()));

        ObjectNode options = mapper.createObjectNode();
        options.put("challenge", b64url(challenge));
        ObjectNode rp = options.putObject("rp");
        rp.put("id", tenant.getRpId());
        rp.put("name", tenant.getRpName());
        ObjectNode user = options.putObject("user");
        user.put("id", req.userHandle());
        user.put("displayName", req.displayName());
        user.put("name", req.username());
        ArrayNode params = options.putArray("pubKeyCredParams");
        params.addObject().put("type", "public-key").put("alg", -7);    // ES256
        params.addObject().put("type", "public-key").put("alg", -257);  // RS256
        options.put("timeout", 60000);
        options.put("attestation", "indirect");
        ArrayNode excludeArr = options.putArray("excludeCredentials");
        for (byte[] existingId : credentials.findCredentialIdsByUserHandle(userHandle)) {
            ObjectNode entry = excludeArr.addObject();
            entry.put("type", "public-key");
            entry.put("id", b64url(existingId));
        }
        ObjectNode sel = options.putObject("authenticatorSelection");
        sel.put("userVerification", "required");
        sel.put("residentKey", "preferred");

        log.info("registration/start issued: tokenTail={} timeoutMs={}",
                tokenTail(token), 60000);
        return new RegistrationStartResponse(token, options);
    }

    /** Returns the last 8 chars of the token for correlation only — never the full secret. */
    private static String tokenTail(String token) {
        if (token == null || token.length() <= 8) {
            return "***";
        }
        return token.substring(token.length() - 8);
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
