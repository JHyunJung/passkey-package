package com.crosscert.passkey.app.fido2.authentication

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartRequest
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartResponse
import com.crosscert.passkey.app.fido2.CeremonyMetrics
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore
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

private val log = LoggerFactory.getLogger(AuthenticationStartService::class.java)

/**
 * Issues a PublicKeyCredentialRequestOptions and stashes the challenge
 * for the matching /finish call. Filters allowCredentials by userHandle
 * when present (typed flow); when null returns an empty allow-list
 * (usernameless / discoverable credential flow).
 *
 * Runs with TenantContextHolder set by ApiKeyAuthFilter, so the
 * app-level @Filter on credential applies — only the calling tenant's
 * credentials are visible.
 */
@Service
class AuthenticationStartService(
    private val tenants: TenantRepository,
    private val credentials: CredentialRepository,
    private val challenges: ChallengeIssuer,
    private val store: ChallengeStore,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val ceremonyMetrics: CeremonyMetrics,
    private val ceremonyEvents: CeremonyEventRecorder,
) {

    @Transactional(readOnly = true)
    fun start(req: AuthenticationStartRequest): AuthenticationStartResponse {
        try {
            log.info("authentication/start entry: userHandlePresent={}", req.userHandle != null)
            val tenantUuid = TenantContextHolder.get()
                ?: throw IllegalStateException("authentication/start invoked without tenant context")
            val tenantId = tenantUuid.toString()
            val tenant = tenants.findById(tenantUuid)
                .orElseThrow { IllegalStateException("tenant $tenantId not found") }
            if (tenant.isSuspended) {
                throw BusinessException(ErrorCode.TENANT_SUSPENDED, "tenant suspended: $tenantId")
            }

            // 잘못된 base64url 은 디코더의 IllegalArgumentException 그대로 전파(원 동작).
            val userHandle = req.userHandle?.let { Base64.getUrlDecoder().decode(it) }

            // The app-level @Filter scopes to this tenant; derived query avoids the findAll scan.
            // Usernameless flow (userHandle == null): server cannot know which
            // credentials to advertise → empty allowCredentials per WebAuthn.
            val userCreds = if (userHandle == null) emptyList() else credentials.findByUserHandle(userHandle)

            val challenge = challenges.newChallengeBytes()
            val token = challenges.newToken()
            store.putAuthentication(token, AuthenticationChallenge(tenantId, challenge, userHandle, clock.instant()))

            // 키 삽입 순서가 곧 응답 JSON 필드 순서다 — 순서를 바꾸지 않는다.
            val options = mapper.createObjectNode()
            options.put("challenge", b64url(challenge))
            options.put("rpId", tenant.rpId)
            options.put("timeout", tenant.webauthnTimeoutMs)
            options.put("userVerification", if (tenant.isRequireUserVerification) "required" else "preferred")
            val allow = options.putArray("allowCredentials")
            for (c in userCreds) {
                allow.addObject().apply {
                    put("type", "public-key")
                    put("id", b64url(c.credentialId))
                }
            }
            log.info(
                "authentication/start issued: tokenTail={} allowCount={} timeoutMs={}",
                tokenTail(token),
                userCreds.size,
                tenant.webauthnTimeoutMs,
            )
            val response = AuthenticationStartResponse(token, options)
            ceremonyEvents.recordAfterCommit(tenantUuid, CeremonyAction.AUTHENTICATION_BEGIN)
            ceremonyMetrics.recordSuccess("authentication", "start")
            return response
        } catch (e: RuntimeException) {
            ceremonyMetrics.recordFailure("authentication", "start")
            throw e
        }
    }
}

private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

/** Returns the last 8 chars of the token for correlation only — never the full secret. */
private fun tokenTail(token: String): String = if (token.length <= 8) "***" else token.takeLast(8)
