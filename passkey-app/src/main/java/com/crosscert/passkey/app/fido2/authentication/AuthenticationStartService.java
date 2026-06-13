package com.crosscert.passkey.app.fido2.authentication;

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartResponse;
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.crosscert.passkey.app.fido2.CeremonyMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationStartService {

    private final TenantRepository tenants;
    private final CredentialRepository credentials;
    private final ChallengeIssuer challenges;
    private final ChallengeStore store;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CeremonyMetrics ceremonyMetrics;
    private final CeremonyEventRecorder ceremonyEvents;

    @Transactional(readOnly = true)
    public AuthenticationStartResponse start(AuthenticationStartRequest req) {
        try {
            log.info("authentication/start entry: userHandlePresent={}",
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
            if (tenant.isSuspended()) {
                throw new BusinessException(ErrorCode.TENANT_SUSPENDED, "tenant suspended: " + tenantId);
            }

            byte[] userHandle = (req.userHandle() == null)
                    ? null
                    : Base64.getUrlDecoder().decode(req.userHandle());

            // VPD filters to this tenant; derived query avoids the findAll scan.
            // Usernameless flow (userHandle == null): server cannot know which
            // credentials to advertise → empty allowCredentials per WebAuthn.
            List<Credential> userCreds = (userHandle == null)
                    ? List.of()
                    : credentials.findByUserHandle(userHandle);

            byte[] challenge = challenges.newChallengeBytes();
            String token = challenges.newToken();
            store.putAuthentication(token, new AuthenticationChallenge(
                    tenantId, challenge, userHandle, clock.instant()));

            ObjectNode options = mapper.createObjectNode();
            options.put("challenge", b64url(challenge));
            options.put("rpId", tenant.getRpId());
            options.put("timeout", tenant.getWebauthnTimeoutMs());
            options.put("userVerification", tenant.isRequireUserVerification() ? "required" : "preferred");
            ArrayNode allow = options.putArray("allowCredentials");
            for (Credential c : userCreds) {
                ObjectNode entry = allow.addObject();
                entry.put("type", "public-key");
                entry.put("id", b64url(c.getCredentialId()));
            }
            log.info("authentication/start issued: tokenTail={} allowCount={} timeoutMs={}",
                    tokenTail(token), userCreds.size(), tenant.getWebauthnTimeoutMs());
            AuthenticationStartResponse response = new AuthenticationStartResponse(token, options);
            ceremonyEvents.recordAfterCommit(tenantUuid, CeremonyAction.AUTHENTICATION_BEGIN);
            ceremonyMetrics.recordSuccess("authentication", "start");
            return response;
        } catch (RuntimeException e) {
            ceremonyMetrics.recordFailure("authentication", "start");
            throw e;
        }
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
