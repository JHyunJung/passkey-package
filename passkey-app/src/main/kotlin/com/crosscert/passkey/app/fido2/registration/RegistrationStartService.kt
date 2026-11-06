package com.crosscert.passkey.app.fido2.registration

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse
import com.crosscert.passkey.app.fido2.CeremonyMetrics
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.ceremony.CeremonyAction
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.Base64

private val log = LoggerFactory.getLogger(RegistrationStartService::class.java)

@Service
class RegistrationStartService(
    private val tenants: TenantRepository,
    private val credentials: CredentialRepository,
    private val challenges: ChallengeIssuer,
    private val store: ChallengeStore,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val ceremonyMetrics: CeremonyMetrics,
    private val ceremonyEvents: CeremonyEventRecorder,
) {

    /**
     * cross-tenant 누출을 막기 위해 `@Transactional` 필수.
     * [com.crosscert.passkey.core.tenant.TenantFilterAspect]는 `@Transactional`
     * 진입 시에만 Hibernate `tenantFilter`를 enable 하는데,
     * `credentials.findCredentialIdsByUserHandle(userHandle)`는 tenant 조건 없이
     * 앱 레벨 @Filter 에만 의존한다. 트랜잭션 경계가 없으면 필터가 켜지지 않아 다른 tenant의
     * credentialId 가 excludeCredentials 로 누출된다.
     *
     * JPA 접근은 모두 읽기(`findById`, `findCredentialIdsByUserHandle`)뿐이고
     * `store.putRegistration(...)`은 Redis 쓰기(JPA 트랜잭션 밖)이므로
     * `readOnly = true`로 둔다. `AuthenticationStartService.start()`와 동일.
     */
    // req 는 nullable: 원 Java 는 null 요청을 try 안 첫 역참조에서 NPE 로 거부해 실패 메트릭을 남겼다.
    // non-null 파라미터면 진입 시점(try 밖) NPE 가 되어 메트릭이 빠지므로 아래 `req!!` 로 같은 지점을 유지한다.
    @Transactional(readOnly = true)
    fun start(req: RegistrationStartRequest?): RegistrationStartResponse {
        try {
            log.info(
                "registration/start entry: usernamePresent={} displayNameLen={}",
                req!!.username != null, // 원 Java 의 첫 역참조(req.getUsername()) 지점 — 측정 블록 안 NPE
                req.displayName?.length ?: 0,
            )
            val tenantUuid = TenantContextHolder.get()
                ?: throw IllegalStateException(
                    "registration/start invoked without tenant context — ApiKeyAuthFilter must have set it",
                )
            val tenantId = tenantUuid.toString()
            val tenant = tenants.findById(tenantUuid)
                .orElseThrow { IllegalStateException("tenant $tenantId not found") }
            if (tenant.isSuspended) {
                throw BusinessException(ErrorCode.TENANT_SUSPENDED, "tenant suspended: $tenantId")
            }

            val userHandle = decodeAndValidateUserHandle(req.userHandle)
            val challenge = challenges.newChallengeBytes()
            val token = challenges.newToken()

            store.putRegistration(
                token,
                RegistrationChallenge(tenantId, challenge, userHandle, req.displayName, req.username, clock.instant()),
            )

            // 키 삽입 순서가 곧 응답 JSON 필드 순서다 — 순서를 바꾸지 않는다.
            val options = mapper.createObjectNode()
            options.put("challenge", b64url(challenge))
            options.putObject("rp").apply {
                put("id", tenant.rpId)
                put("name", tenant.rpName)
            }
            options.putObject("user").apply {
                put("id", req.userHandle)
                put("displayName", req.displayName)
                put("name", req.username)
            }
            options.putArray("pubKeyCredParams").apply {
                addObject().put("type", "public-key").put("alg", -7) // ES256
                addObject().put("type", "public-key").put("alg", -257) // RS256
            }
            options.put("timeout", tenant.webauthnTimeoutMs)
            options.put("attestation", tenant.attestationConveyanceLowercase)
            val excludeArr = options.putArray("excludeCredentials")
            for (existingId in credentials.findCredentialIdsByUserHandle(userHandle)) {
                excludeArr.addObject().apply {
                    put("type", "public-key")
                    put("id", b64url(existingId))
                }
            }
            options.putObject("authenticatorSelection").apply {
                put("userVerification", if (tenant.isRequireUserVerification) "required" else "preferred")
                put("residentKey", "preferred")
            }

            log.info(
                "registration/start issued: tokenTail={} timeoutMs={}",
                tokenTail(token),
                tenant.webauthnTimeoutMs,
            )
            val response = RegistrationStartResponse(token, options)
            ceremonyEvents.recordAfterCommit(tenantUuid, CeremonyAction.REGISTRATION_BEGIN)
            ceremonyMetrics.recordSuccess("registration", "start")
            return response
        } catch (e: RuntimeException) {
            ceremonyMetrics.recordFailure("registration", "start")
            throw e
        }
    }
}

/**
 * G18: WebAuthn 스펙상 `user.id`(userHandle) 는 1..64 바이트여야 한다
 * (https://www.w3.org/TR/webauthn-3/#dom-publickeycredentialuserentity-id).
 * 검증 없이 디코드만 하면 65바이트+ userHandle 도 start 를 통과해 finish 저장 시
 * Oracle `credential.user_handle RAW(64)` 컬럼 오버플로(ORA-12899)로
 * DataIntegrityViolationException → 500 이 노출된다. 여기서 fail-fast 400 으로 거부한다.
 * 문자셋 검증은 [Base64.Decoder.decode] 가 이미 수행하므로(비-base64url
 * 문자는 [IllegalArgumentException]) 별도 사전검사를 두지 않는다 — RFC 4648 §5 는
 * 패딩(`=`)을 허용하며 JDK 표준 디코더는 패딩 유무 둘 다 정상 처리하므로,
 * 문자셋 사전검사에서 패딩을 빠뜨리면 표준 인코더(패딩 포함)로 만든 정상 userHandle 을
 * 오탐 거부하는 회귀가 된다.
 */
private fun decodeAndValidateUserHandle(userHandleB64: String?): ByteArray {
    if (userHandleB64 == null) {
        throw BusinessException(ErrorCode.INVALID_INPUT, "userHandle must be valid base64url")
    }
    val userHandle = try {
        Base64.getUrlDecoder().decode(userHandleB64)
    } catch (e: IllegalArgumentException) {
        throw BusinessException(ErrorCode.INVALID_INPUT, "userHandle must be valid base64url")
    }
    if (userHandle.size !in 1..64) {
        throw BusinessException(
            ErrorCode.INVALID_INPUT,
            "userHandle must decode to 1..64 bytes (WebAuthn user.id cap), was ${userHandle.size}",
        )
    }
    return userHandle
}

/** Returns the last 8 chars of the token for correlation only — never the full secret. */
private fun tokenTail(token: String): String = if (token.length <= 8) "***" else token.takeLast(8)

private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
