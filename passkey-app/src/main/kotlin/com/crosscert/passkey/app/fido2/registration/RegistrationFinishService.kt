package com.crosscert.passkey.app.fido2.registration

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishResponse
import com.crosscert.passkey.app.fido2.CeremonyMetrics
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore
import com.crosscert.passkey.app.fido2.mds.MdsVerifier
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.ceremony.CeremonyAction
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.policy.AaguidPolicyChecker
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import com.crosscert.passkey.webauthn.verifier.AttestationTrustPolicy
import com.crosscert.passkey.webauthn.verifier.COSEAlgorithm
import com.crosscert.passkey.webauthn.verifier.RegistrationInput
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.time.Clock
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

private val log = LoggerFactory.getLogger(RegistrationFinishService::class.java)

@Service
class RegistrationFinishService(
    private val store: ChallengeStore,
    private val verifier: WebAuthnVerifier,
    private val tenants: TenantRepository,
    private val credentials: CredentialRepository,
    private val mds: MdsVerifier,
    private val aaguidPolicyChecker: AaguidPolicyChecker,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val ceremonyMetrics: CeremonyMetrics,
    private val ceremonyEvents: CeremonyEventRecorder,
) {

    // req 는 nullable: 원 Java 는 null 요청을 try 안 첫 역참조에서 NPE 로 거부해 실패 메트릭을 남겼다.
    // non-null 파라미터면 진입 시점(try 밖) NPE 가 되어 메트릭이 빠지므로 아래 `req!!` 로 같은 지점을 유지한다.
    @Transactional
    fun finish(req: RegistrationFinishRequest?): RegistrationFinishResponse {
        try {
            // 토큰 null(컨트롤러는 @NotBlank 로 거른다 — 서비스 직접 호출만 해당)은 토큰 만료와 같은 결과:
            // 같은 IllegalArgumentException·메시지, 측정 블록 안이라 실패 메트릭도 남는다.
            // req!!: 원 Java 의 첫 역참조 지점 — 측정 블록 안 NPE.
            val ch = req!!.registrationToken?.let(store::takeRegistration)?.getOrNull()
                ?: throw IllegalArgumentException("registration token missing or expired")

            // F19 방어심화: challenge freshness 를 앱-레벨에서도 확인한다.
            // 평시에는 Redis TTL 이 만료 challenge 를 먼저 제거하므로 이 가드가
            // 추가로 거부하는 정상 요청은 없다(이중 방어). Redis 가 만료를 놓친
            // 경계 상황(시계 차이·키 잔존 등)에서만 issuedAt 기준으로 거부한다.
            if (clock.instant().minus(ChallengeStore.TTL).isAfter(ch.issuedAt)) {
                log.warn("stale registration challenge rejected: issuedAt={} ttl={}", ch.issuedAt, ChallengeStore.TTL)
                throw BusinessException(ErrorCode.CHALLENGE_INVALID, "challenge expired")
            }

            // codex P2: bind challenge tenant to current API-key tenant
            // before touching tenant config — defense-in-depth on top of
            // the app-level @Filter. Mirrors AuthenticationFinishService.
            val ctxTenant = TenantContextHolder.get()?.toString()
            if (ctxTenant != ch.tenantId) {
                log.warn("tenant mismatch on registration/finish: ctx={} ch={}", ctxTenant, ch.tenantId)
                throw IllegalArgumentException("tenant mismatch")
            }

            val tenant = tenants.findById(UUID.fromString(ch.tenantId))
                .orElseThrow { IllegalStateException("tenant ${ch.tenantId} missing") }

            val publicKeyCredentialJson = try {
                mapper.writeValueAsString(req.publicKeyCredential)
            } catch (e: Exception) {
                throw IllegalArgumentException("publicKeyCredential JSON invalid")
            }

            val origins = LinkedHashSet(tenant.allowedOriginValues)
            check(origins.isNotEmpty()) { "tenant ${tenant.id} has no allowed_origins configured" }

            val input = RegistrationInput(
                publicKeyCredentialJson,
                ch.challenge,
                origins,
                tenant.rpId,
                tenant.isRequireUserVerification,
                setOf(COSEAlgorithm.ES256, COSEAlgorithm.RS256),
                tenant.acceptedFormatValues,
                AttestationTrustPolicy.SELF_ALLOWED,
            )

            val result = try {
                verifier.verifyRegistration(input)
            } catch (e: WebAuthnVerificationException) {
                log.warn("attestation verify failed for tenant {}: {} ({})", ch.tenantId, e.message, e.reason())
                throw IllegalArgumentException("attestation verify failed")
            }

            val fmt = result.attestationFormat()
            require(fmt in tenant.acceptedFormatValues) { "attestation format not accepted by tenant policy" }

            // AAGUID 정책 검사 — MDS verify 통과 직후, credential 저장 직전
            // AaguidPolicyViolationException(BusinessException) 발생 시 GlobalExceptionHandler 가 HTTP 403 반환
            val aaguid: ByteArray? = result.aaguid() // 인증기가 AAGUID 를 주지 않으면 null
            require(mds.verify(tenant.isMdsRequired, aaguid)) { "authenticator metadata verification failed" }

            val aaguidUuid = aaguidFromBytes(aaguid)
            aaguidPolicyChecker.check(tenant.id, aaguidUuid)

            val credentialId = result.credentialId()
            val cred = Credential(
                UUID.fromString(ch.tenantId),
                ch.userHandle,
                credentialId,
                result.cosePublicKey(),
                aaguid,
            )
            cred.attestationFmt = fmt
            val transports: Set<String>? = result.transports()
            if (!transports.isNullOrEmpty()) {
                cred.transports = transports.joinToString(",")
            }
            try {
                // G01: saveAndFlush 로 INSERT 를 즉시 flush 해 unique 제약 위반(동일
                // credentialId 재등록)을 이 catch 안에서 잡는다. save 만 쓰면 INSERT 가
                // commit 까지 지연돼 이 try/catch 를 벗어난 뒤 위반이 터질 수 있다.
                credentials.saveAndFlush(cred)
            } catch (e: DataIntegrityViolationException) {
                log.warn("registration/finish rejected: duplicate credential for tenant {}", ch.tenantId)
                throw BusinessException(ErrorCode.CREDENTIAL_DUPLICATE, "credential already registered")
            }

            val credentialIdB64 = b64url(credentialId)
            log.info(
                "registration/finish ok: credentialIdTail={} aaguid={} format={}",
                idTail(credentialIdB64),
                aaguidUuid,
                fmt,
            )

            val response = RegistrationFinishResponse(
                credentialIdB64,
                aaguid?.let { HexFormat.of().formatHex(it) },
                fmt,
                clock.instant(),
            )
            ceremonyEvents.recordAfterCommit(UUID.fromString(ch.tenantId), CeremonyAction.REGISTRATION_SUCCESS)
            ceremonyMetrics.recordSuccess("registration", "finish")
            return response
        } catch (e: RuntimeException) {
            ceremonyMetrics.recordFailure("registration", "finish")
            throw e
        }
    }
}

/**
 * Last 12 chars of a base64url id for correlation — never the full credential id.
 * Short ids (≤12 chars) are masked to "***" so the full value is never logged.
 */
private fun idTail(id: String): String = if (id.length <= 12) "***" else "..." + id.takeLast(12)

private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

/**
 * 16-byte 원시 AAGUID 를 [UUID] 로 변환.
 * bytes 가 null 이거나 길이가 16 이 아니면 null 반환 (pass-through 처리).
 */
private fun aaguidFromBytes(bytes: ByteArray?): UUID? {
    if (bytes == null || bytes.size != 16) return null
    val bb = ByteBuffer.wrap(bytes)
    return UUID(bb.long, bb.long)
}
