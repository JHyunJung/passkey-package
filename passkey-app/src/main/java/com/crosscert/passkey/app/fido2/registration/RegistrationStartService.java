package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.crosscert.passkey.app.fido2.CeremonyMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final CeremonyMetrics ceremonyMetrics;

    public RegistrationStartService(TenantRepository tenants,
                                    CredentialRepository credentials,
                                    ChallengeIssuer challenges,
                                    ChallengeStore store,
                                    ObjectMapper mapper,
                                    Clock clock,
                                    CeremonyMetrics ceremonyMetrics) {
        this.tenants = tenants;
        this.credentials = credentials;
        this.challenges = challenges;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
        this.ceremonyMetrics = ceremonyMetrics;
    }

    /**
     * VPD off(SE2) 모드에서 cross-tenant 누출을 막기 위해 {@code @Transactional} 필수.
     * {@link com.crosscert.passkey.core.vpd.TenantFilterAspect}는 {@code @Transactional}
     * 진입 시에만 Hibernate {@code tenantFilter}를 enable 하는데, line 96의
     * {@code credentials.findCredentialIdsByUserHandle(userHandle)}는 tenant 조건 없이
     * 필터/VPD에만 의존한다. 트랜잭션 경계가 없으면 필터가 켜지지 않아 다른 tenant의
     * credentialId 가 excludeCredentials 로 누출된다.
     *
     * <p>JPA 접근은 모두 읽기({@code findById}, {@code findCredentialIdsByUserHandle})뿐이고
     * {@code store.putRegistration(...)}은 Redis 쓰기(JPA 트랜잭션 밖)이므로
     * {@code readOnly = true}로 둔다. {@code AuthenticationStartService.start()}와 동일.
     */
    @Transactional(readOnly = true)
    public RegistrationStartResponse start(RegistrationStartRequest req) {
        try {
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
            if (tenant.isSuspended()) {
                throw new BusinessException(ErrorCode.TENANT_SUSPENDED, "tenant suspended: " + tenantId);
            }

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
            options.put("timeout", tenant.getWebauthnTimeoutMs());
            options.put("attestation", tenant.getAttestationConveyanceLowercase());
            ArrayNode excludeArr = options.putArray("excludeCredentials");
            for (byte[] existingId : credentials.findCredentialIdsByUserHandle(userHandle)) {
                ObjectNode entry = excludeArr.addObject();
                entry.put("type", "public-key");
                entry.put("id", b64url(existingId));
            }
            ObjectNode sel = options.putObject("authenticatorSelection");
            sel.put("userVerification", tenant.isRequireUserVerification() ? "required" : "preferred");
            sel.put("residentKey", "preferred");

            log.info("registration/start issued: tokenTail={} timeoutMs={}",
                    tokenTail(token), tenant.getWebauthnTimeoutMs());
            RegistrationStartResponse response = new RegistrationStartResponse(token, options);
            ceremonyMetrics.recordSuccess("registration", "start");
            return response;
        } catch (RuntimeException e) {
            ceremonyMetrics.recordFailure("registration", "start");
            throw e;
        }
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
