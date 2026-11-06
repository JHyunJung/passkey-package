package com.crosscert.passkey.app.fido2.authentication

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishRequest
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishResponse
import com.crosscert.passkey.app.fido2.CeremonyMetrics
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore
import com.crosscert.passkey.core.alert.SecurityAlertEvent
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.ceremony.CeremonyAction
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder
import com.crosscert.passkey.core.ceremony.CredentialAuthEventRecorder
import com.crosscert.passkey.core.ceremony.CredentialAuthFailureReason
import com.crosscert.passkey.core.ceremony.CredentialAuthResult
import com.crosscert.passkey.core.jwt.IdTokenIssuer
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import com.crosscert.passkey.webauthn.verifier.AuthenticationInput
import com.crosscert.passkey.webauthn.verifier.StoredCredential
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

private val log = LoggerFactory.getLogger(AuthenticationFinishService::class.java)

/** id-token 수명(초) — 응답 expiresIn 과 발급 로그에 같은 값을 쓴다. */
private const val ID_TOKEN_TTL_SEC = 900L

@Service
class AuthenticationFinishService(
    private val store: ChallengeStore,
    private val verifier: WebAuthnVerifier,
    private val tenants: TenantRepository,
    private val credentials: CredentialRepository,
    private val idTokens: IdTokenIssuer,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val ceremonyMetrics: CeremonyMetrics,
    private val eventPublisher: ApplicationEventPublisher,
    private val ceremonyEvents: CeremonyEventRecorder,
    private val authEvents: CredentialAuthEventRecorder,
) {

    // req 는 nullable: 원 Java 는 null 요청을 try 안 첫 역참조에서 NPE 로 거부해 실패 메트릭을 남겼다.
    // non-null 파라미터면 진입 시점(try 밖) NPE 가 되어 메트릭이 빠지므로 아래 `req!!` 로 같은 지점을 유지한다.
    @Transactional
    fun finish(req: AuthenticationFinishRequest?): AuthenticationFinishResponse {
        try {
            // 토큰 null(컨트롤러는 @NotBlank 로 거른다 — 서비스 직접 호출만 해당)은 토큰 만료와 같은 결과:
            // 같은 IllegalArgumentException·메시지, 측정 블록 안이라 실패 메트릭도 남는다.
            // req!!: 원 Java 의 첫 역참조 지점 — 측정 블록 안 NPE.
            val ch = req!!.authenticationToken?.let(store::takeAuthentication)?.getOrNull()
                ?: throw IllegalArgumentException("authentication token missing or expired")

            // F19 방어심화: challenge freshness 를 앱-레벨에서도 확인한다.
            // 평시에는 Redis TTL 이 만료 challenge 를 먼저 제거하므로 이 가드가
            // 추가로 거부하는 정상 요청은 없다(이중 방어). Redis 가 만료를 놓친
            // 경계 상황(시계 차이·키 잔존 등)에서만 issuedAt 기준으로 거부한다.
            if (clock.instant().minus(ChallengeStore.TTL).isAfter(ch.issuedAt)) {
                log.warn("stale authentication challenge rejected: issuedAt={} ttl={}", ch.issuedAt, ChallengeStore.TTL)
                throw BusinessException(ErrorCode.CHALLENGE_INVALID, "challenge expired")
            }

            // codex P2: bind challenge tenant to current API-key tenant before
            // touching tenant config — defense-in-depth on top of the app-level @Filter.
            val ctxTenant = TenantContextHolder.get()?.toString()
            if (ctxTenant != ch.tenantId) {
                log.warn("tenant mismatch on authentication/finish: ctx={} ch={}", ctxTenant, ch.tenantId)
                throw IllegalArgumentException("tenant mismatch")
            }

            val tenant = tenants.findById(UUID.fromString(ch.tenantId))
                .orElseThrow { IllegalStateException("tenant ${ch.tenantId} missing") }

            val publicKeyCredentialJson = try {
                mapper.writeValueAsString(req.publicKeyCredential)
            } catch (e: Exception) {
                throw IllegalArgumentException("publicKeyCredential JSON invalid")
            }

            // Extract the asserted credential id (rawId, base64url) from the
            // request JSON so we can lock + load the stored row BEFORE
            // verification. The native verifier also binds the assertion
            // rawId to storedCredential.credentialId internally (defense in
            // depth), so a mismatch is rejected there as well.
            val credentialId = try {
                // asString("") — Jackson 2 asText() 처럼 객체/배열을 예외 대신 "" 로 강제(→ 미등록 처리).
                // publicKeyCredential(JSON null 포함) 또는 rawId 부재도 같은 400 으로 거부한다.
                val rawIdB64 = req.publicKeyCredential?.get("rawId")?.asString("")
                    ?: throw IllegalArgumentException("assertion rawId invalid")
                Base64.getUrlDecoder().decode(rawIdB64)
            } catch (e: Exception) {
                throw IllegalArgumentException("assertion rawId invalid")
            }

            // codex P1: lock the row before the read-check-update on
            // signCount to prevent two concurrent /finish calls from both
            // passing the strict-monotonic check. The app-level @Filter scopes by tenant.
            val cred = credentials.findByCredentialIdForUpdate(credentialId)
                .orElseThrow { IllegalArgumentException("credential not registered") }

            // codex P1: typed flow must bind the asserted credential to the
            // challenge's userHandle. Without this, server-side allowCredentials
            // is tautological (any same-tenant credential matching the
            // asserted ID would be accepted).
            val expectedUserHandle = ch.userHandle
            if (expectedUserHandle != null && !(expectedUserHandle contentEquals cred.userHandle)) {
                log.warn("userHandle mismatch on authentication/finish for tenant {}", ch.tenantId)
                throw IllegalArgumentException("credential not registered")
            }

            val stored = StoredCredential(cred.credentialId, cred.cosePublicKey, cred.signCount)

            val origins = LinkedHashSet(tenant.allowedOriginValues)
            check(origins.isNotEmpty()) { "tenant ${tenant.id} has no allowed_origins configured" }

            val input = AuthenticationInput(
                publicKeyCredentialJson,
                ch.challenge,
                origins,
                tenant.rpId,
                tenant.isRequireUserVerification,
                stored,
            )

            val result = try {
                verifier.verifyAuthentication(input)
            } catch (e: WebAuthnVerificationException) {
                log.warn("assertion verify failed for tenant {}: {} ({})", ch.tenantId, e.message, e.reason())
                // spec §6.1: credential 식별 이후의 검증 실패도 기록한다. newCounter 는
                // 아직 검증 전이라 보존된 cred.signCount 를 signCount 로 남긴다.
                // recordAfterRollback: cred 는 PESSIMISTIC_WRITE 로 락된 행이라 즉시
                // REQUIRES_NEW INSERT 하면 FK 락 경합이 난다. outer 롤백 완료(락 해제) 후 기록.
                authEvents.recordAfterRollback(
                    cred.id,
                    UUID.fromString(ch.tenantId),
                    CredentialAuthResult.FAILED,
                    CredentialAuthFailureReason.SIGNATURE_INVALID,
                    cred.signCount,
                )
                throw IllegalArgumentException("assertion verify failed")
            }

            // codex P2: WebAuthn allows authenticators with no counter to
            // return 0 forever. The verifier already rejects non-increasing
            // counters when EITHER side is non-zero; only the (0,0) case
            // is treated as "no-counter" and accepted. Mirror that here so
            // we don't refuse legitimate passkeys.
            val newCounter = result.newSignCount()
            val storedCounter = cred.signCount
            val counterless = newCounter == 0L && storedCounter == 0L
            if (!counterless && newCounter <= storedCounter) {
                log.warn(
                    "signCount did not advance for credential {} on tenant {} (was {}, got {})",
                    cred.id,
                    ch.tenantId,
                    storedCounter,
                    newCounter,
                )
                eventPublisher.publishEvent(
                    SecurityAlertEvent(
                        SecurityAlertEvent.AlertType.COUNTER_REGRESSION,
                        SecurityAlertEvent.Severity.HIGH,
                        "signCount did not advance (possible cloned authenticator)",
                        mapOf(
                            "credentialId" to "${cred.id}", // String.valueOf 의미 유지
                            "tenantId" to ch.tenantId,
                        ),
                    ),
                )
                // recordAfterRollback: 위 verify 실패와 동일한 이유(락된 cred 행과의 FK
                // 락 경합 회피) — outer 롤백 완료 후 기록한다.
                authEvents.recordAfterRollback(
                    cred.id,
                    UUID.fromString(ch.tenantId),
                    CredentialAuthResult.FAILED,
                    CredentialAuthFailureReason.SIGN_COUNT_REPLAY,
                    newCounter,
                )
                throw IllegalArgumentException("signCount replay detected")
            }

            // Persist the new counter. The strict-monotonic check above reads
            // cred.signCount, so the persisted column is the single
            // source of truth for replay defense.
            //
            // Pessimistic lock (above) + the @Transactional boundary make
            // this read-check-update sequence safe under concurrency.
            cred.recordAuthentication(newCounter, OffsetDateTime.now(clock))
            credentials.saveAndFlush(cred)
            authEvents.recordAfterCommit(
                cred.id,
                UUID.fromString(ch.tenantId),
                CredentialAuthResult.SUCCESS,
                null,
                newCounter,
            )

            val credentialIdB64 = b64url(credentialId)
            log.info("authentication/finish ok: credentialIdTail={} counter={}", idTail(credentialIdB64), newCounter)

            val jwt = idTokens.issue(ch.tenantId, cred.userHandle, cred.id, cred.aaguid)
            // id-token claims meta only — never the JWT body itself
            val subTail = idTail(b64url(cred.userHandle))
            log.info("id-token issued: subTail={} aud={} ttlSec={}", subTail, ch.tenantId, ID_TOKEN_TTL_SEC)

            val response = AuthenticationFinishResponse(jwt, "Bearer", ID_TOKEN_TTL_SEC)
            ceremonyEvents.recordAfterCommit(UUID.fromString(ch.tenantId), CeremonyAction.AUTHENTICATION_SUCCESS)
            ceremonyMetrics.recordSuccess("authentication", "finish")
            return response
        } catch (e: RuntimeException) {
            ceremonyMetrics.recordFailure("authentication", "finish")
            throw e
        }
    }
}

private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

/**
 * Last 12 chars of a base64url id for correlation — never the full id.
 * Short ids (≤12 chars) are masked to "***" so the full value is never logged.
 */
private fun idTail(id: String): String = if (id.length <= 12) "***" else "..." + id.takeLast(12)
