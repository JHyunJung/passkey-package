package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationStartService {

    private final TenantRepository tenants;
    private final CredentialRepository credentials;
    private final ChallengeIssuer challenges;
    private final ChallengeStore store;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CeremonyMetrics ceremonyMetrics;
    private final CeremonyEventRecorder ceremonyEvents;

    /**
     * cross-tenant 누출을 막기 위해 {@code @Transactional} 필수.
     * {@link com.crosscert.passkey.core.tenant.TenantFilterAspect}는 {@code @Transactional}
     * 진입 시에만 Hibernate {@code tenantFilter}를 enable 하는데, line 96의
     * {@code credentials.findCredentialIdsByUserHandle(userHandle)}는 tenant 조건 없이
     * 앱 레벨 @Filter 에만 의존한다. 트랜잭션 경계가 없으면 필터가 켜지지 않아 다른 tenant의
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

            byte[] userHandle = decodeAndValidateUserHandle(req.userHandle());
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
            ceremonyEvents.recordAfterCommit(tenantUuid, CeremonyAction.REGISTRATION_BEGIN);
            ceremonyMetrics.recordSuccess("registration", "start");
            return response;
        } catch (RuntimeException e) {
            ceremonyMetrics.recordFailure("registration", "start");
            throw e;
        }
    }

    /**
     * G18: WebAuthn 스펙상 {@code user.id}(userHandle) 는 1..64 바이트여야 한다
     * (https://www.w3.org/TR/webauthn-3/#dom-publickeycredentialuserentity-id).
     * 검증 없이 디코드만 하면 65바이트+ userHandle 도 start 를 통과해 finish 저장 시
     * Oracle {@code credential.user_handle RAW(64)} 컬럼 오버플로(ORA-12899)로
     * DataIntegrityViolationException → 500 이 노출된다. 여기서 fail-fast 400 으로 거부한다.
     * 문자셋 검증은 {@link Base64.Decoder#decode(String)} 이 이미 수행하므로(비-base64url
     * 문자는 {@link IllegalArgumentException}) 별도 사전검사를 두지 않는다 — RFC 4648 §5 는
     * 패딩(  {@code =}  )을 허용하며 JDK 표준 디코더는 패딩 유무 둘 다 정상 처리하므로,
     * 문자셋 사전검사에서 패딩을 빠뜨리면 표준 인코더(패딩 포함)로 만든 정상 userHandle 을
     * 오탐 거부하는 회귀가 된다.
     */
    private static byte[] decodeAndValidateUserHandle(String userHandleB64) {
        if (userHandleB64 == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "userHandle must be valid base64url");
        }
        byte[] userHandle;
        try {
            userHandle = Base64.getUrlDecoder().decode(userHandleB64);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "userHandle must be valid base64url");
        }
        if (userHandle.length < 1 || userHandle.length > 64) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "userHandle must decode to 1..64 bytes (WebAuthn user.id cap), was " + userHandle.length);
        }
        return userHandle;
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
