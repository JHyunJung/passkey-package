package com.crosscert.passkey.app.fido2.authentication;

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartResponse;
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Issues a PublicKeyCredentialRequestOptions and stashes the challenge
 * for the matching /finish call. Filters allowCredentials by userHandle
 * when present (typed flow); when null returns an empty allow-list
 * (usernameless / discoverable credential flow).
 *
 * <p>Runs with TenantContextHolder set by ApiKeyAuthFilter, so VPD on
 * credential applies — only the calling tenant's credentials are
 * visible.
 */
@Service
public class AuthenticationStartService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AuthenticationStartService.class);

    private final TenantRepository tenants;
    private final CredentialRepository credentials;
    private final ChallengeIssuer challenges;
    private final ChallengeStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AuthenticationStartService(TenantRepository tenants,
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

    @Transactional(readOnly = true)
    public AuthenticationStartResponse start(AuthenticationStartRequest req) {
        log.info("event=authentication/start phase=entry userHandlePresent={}",
                req.userHandle() != null);
        UUID tenantUuid = TenantContextHolder.get();
        if (tenantUuid == null) {
            throw new IllegalStateException(
                    "authentication/start invoked without tenant context");
        }
        String tenantId = tenantUuid.toString();
        Tenant tenant = tenants.findById(tenantUuid)
                .orElseThrow(() -> new IllegalStateException(
                        "tenant " + tenantId + " not found"));

        byte[] userHandle = (req.userHandle() == null)
                ? null
                : Base64.getUrlDecoder().decode(req.userHandle());

        // VPD filters to this tenant. Phase 2 will add a derived
        // findByUserHandle query for efficiency.
        List<Credential> userCreds = credentials.findAll();
        if (userHandle != null) {
            final byte[] uh = userHandle;
            userCreds = userCreds.stream()
                    .filter(c -> Arrays.equals(c.getUserHandle(), uh))
                    .toList();
        } else {
            // Usernameless flow: server cannot know which credentials
            // to advertise. Return empty allowCredentials per WebAuthn.
            userCreds = List.of();
        }

        byte[] challenge = challenges.newChallengeBytes();
        String token = challenges.newToken();
        store.putAuthentication(token, new AuthenticationChallenge(
                tenantId, challenge, userHandle, clock.instant()));

        ObjectNode options = mapper.createObjectNode();
        options.put("challenge", b64url(challenge));
        options.put("rpId", tenant.getRpId());
        options.put("timeout", 60000);
        options.put("userVerification", "required");
        ArrayNode allow = options.putArray("allowCredentials");
        for (Credential c : userCreds) {
            ObjectNode entry = allow.addObject();
            entry.put("type", "public-key");
            entry.put("id", b64url(c.getCredentialId()));
        }
        log.info("event=authentication/start phase=issued tokenTail={} allowCount={} timeoutMs={}",
                tokenTail(token), userCreds.size(), 60000);
        return new AuthenticationStartResponse(token, options);
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Returns the last 8 chars of the token for correlation only — never the full secret. */
    private static String tokenTail(String token) {
        if (token == null || token.length() <= 8) {
            return "***";
        }
        return token.substring(token.length() - 8);
    }
}
