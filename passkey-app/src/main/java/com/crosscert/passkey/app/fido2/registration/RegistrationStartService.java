package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Base64;

@Service
public class RegistrationStartService {

    private final TenantRepository tenants;
    private final ChallengeIssuer challenges;
    private final ChallengeStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public RegistrationStartService(TenantRepository tenants,
                                    ChallengeIssuer challenges,
                                    ChallengeStore store,
                                    ObjectMapper mapper,
                                    Clock clock) {
        this.tenants = tenants;
        this.challenges = challenges;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    public RegistrationStartResponse start(RegistrationStartRequest req) {
        String tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "registration/start invoked without tenant context — ApiKeyAuthFilter "
                            + "must have set it");
        }
        Tenant tenant = tenants.findById(tenantId)
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
        ObjectNode sel = options.putObject("authenticatorSelection");
        sel.put("userVerification", "required");
        sel.put("residentKey", "preferred");

        return new RegistrationStartResponse(token, options);
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
