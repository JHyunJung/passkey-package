package com.crosscert.passkey.app.fido2.registration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest
import com.crosscert.passkey.app.fido2.CeremonyMetrics
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge
import com.crosscert.passkey.app.fido2.mds.MdsVerifier
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.ceremony.CeremonyAction
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.policy.AaguidPolicyChecker
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import com.crosscert.passkey.webauthn.verifier.AttestationTrustPolicy
import com.crosscert.passkey.webauthn.verifier.COSEAlgorithm
import com.crosscert.passkey.webauthn.verifier.RegistrationInput
import com.crosscert.passkey.webauthn.verifier.RegistrationResult
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

private val NOW = Instant.parse("2026-11-06T00:00:00Z")
private val TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
private val AAGUID = byteArrayOf(
    0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
    0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
)

// 원 Java 는 static byte[20] 을 매 @BeforeEach 에서 1..20 으로 채웠다 — 같은 내용으로 한 번 초기화.
private val CRED_ID = ByteArray(20) { (it + 1).toByte() }

/**
 * Kotlin 전환 전 RegistrationFinishService 의 외부 관찰 가능 동작(실패별 예외 타입·메시지,
 * 검증 순서, 메트릭 이름/태그/횟수, 로그 문구, 저장 엔티티 필드, 응답 필드, ceremony 이벤트)을
 * 현재 Java 구현 기준으로 고정하는 특성 테스트.
 */
class RegistrationFinishServiceCharacterizationTest {

    private val store: ChallengeStore = mock()
    private val verifier: WebAuthnVerifier = mock()
    private val tenants: TenantRepository = mock()
    private val credentials: CredentialRepository = mock()
    private val mds: MdsVerifier = mock()
    private val policy: AaguidPolicyChecker = mock()
    private val ceremonyEvents: CeremonyEventRecorder = mock()
    private val meters = SimpleMeterRegistry()
    private val clock = Clock.fixed(NOW, KstTime.ZONE)
    private val tenant: Tenant = mock()

    private val logger = LoggerFactory.getLogger(RegistrationFinishService::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.DEBUG

        whenever(tenant.id).thenReturn(TENANT)
        whenever(tenant.allowedOriginValues).thenReturn(listOf("https://b.example", "https://a.example"))
        whenever(tenant.rpId).thenReturn("example.com")
        whenever(tenant.isRequireUserVerification).thenReturn(true)
        whenever(tenant.acceptedFormatValues).thenReturn(setOf("packed", "none"))
        whenever(tenant.isMdsRequired).thenReturn(true)
        whenever(tenants.findById(TENANT)).thenReturn(Optional.of(tenant))
    }

    @AfterEach
    fun tearDown() {
        TenantContextHolder.clear()
        logger.detachAppender(appender)
        logger.level = null
    }

    private fun svc() = RegistrationFinishService(
        store, verifier, tenants, credentials, mds, policy,
        ObjectMapper(), clock, CeremonyMetrics(meters), ceremonyEvents,
    )

    private fun challenge(tenantId: String) = RegistrationChallenge(
        tenantId, byteArrayOf(7, 7, 7), byteArrayOf(9, 9),
        "Alice", "alice", NOW,
    )

    private fun stubChallenge() {
        TenantContextHolder.set(TENANT)
        whenever(store.takeRegistration("tok")).thenReturn(Optional.of(challenge(TENANT.toString())))
    }

    private fun result(aaguid: ByteArray?, fmt: String, transports: Set<String>?) = RegistrationResult(
        CRED_ID, byteArrayOf(5, 6), 0L, aaguid, fmt,
        transports, true, true, false, false,
    )

    private fun count(result: String): Double =
        meters.find("passkey_ceremony_total")
            .tags("type", "registration", "phase", "finish", "result", result).counter()
            ?.count() ?: 0.0

    private fun messages(level: Level): List<String> =
        appender.list.filter { it.level == level }.map { it.formattedMessage }

    @Test
    fun nullRequest_npeInsideMeasuredBlock_failureMetricCounted() {
        // 원 Java: try 안 첫 문장(req.getRegistrationToken())에서 NPE → 실패 메트릭 1 → 재던짐.
        stubChallenge()

        assertThatThrownBy { svc().finish(null) }
            .isExactlyInstanceOf(NullPointerException::class.java)
        assertThat(count("failure")).isEqualTo(1.0)
        assertThat(count("success")).isEqualTo(0.0)
        verifyNoInteractions(store, tenants, ceremonyEvents)
    }

    @Test
    fun nullToken_illegalArgumentInsideMeasuredBlock_failureMetricCounted() {
        // 브랜치 기준 Java: null 토큰 → "challenge:reg:null" 조회가 빈 값 → 토큰 누락과 같은 IAE·메시지,
        // 측정 블록 안이라 실패 메트릭 1. Kotlin 은 같은 결과를 Redis 조회 없이 낸다(외부 관찰 불가 차이).
        assertThatThrownBy { svc().finish(RegistrationFinishRequest(null, null)) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("registration token missing or expired")
        assertThat(count("failure")).isEqualTo(1.0)
        assertThat(count("success")).isEqualTo(0.0)
        verifyNoInteractions(store, tenants)
    }

    @Test
    fun tokenMissing_illegalArgument_failureMetric() {
        whenever(store.takeRegistration("tok")).thenReturn(Optional.empty())

        assertThatThrownBy { svc().finish(RegistrationFinishRequest("tok", null)) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("registration token missing or expired")
        assertThat(count("failure")).isEqualTo(1.0)
        assertThat(count("success")).isEqualTo(0.0)
        verifyNoInteractions(tenants)
    }

    @Test
    fun staleChallenge_logsWarnWithIssuedAtAndTtl() {
        TenantContextHolder.set(TENANT)
        val stale = NOW.minus(ChallengeStore.TTL).minusSeconds(1)
        whenever(store.takeRegistration("tok")).thenReturn(
            Optional.of(RegistrationChallenge(TENANT.toString(), byteArrayOf(1), byteArrayOf(1), null, null, stale)),
        )

        assertThatThrownBy { svc().finish(RegistrationFinishRequest("tok", null)) }
            .isExactlyInstanceOf(BusinessException::class.java)
            .hasMessage("challenge expired")
        assertThat(messages(Level.WARN)).containsExactly(
            "stale registration challenge rejected: issuedAt=$stale ttl=PT5M",
        )
        assertThat(count("failure")).isEqualTo(1.0)
    }

    @Test
    fun challengeExactlyAtTtl_isStillFresh() {
        // 경계: now - TTL == issuedAt → isAfter false → 통과 (tenant mismatch 에서 멈춤)
        whenever(store.takeRegistration("tok")).thenReturn(
            Optional.of(
                RegistrationChallenge(
                    TENANT.toString(), byteArrayOf(1), byteArrayOf(1), null, null, NOW.minus(ChallengeStore.TTL),
                ),
            ),
        )

        assertThatThrownBy { svc().finish(RegistrationFinishRequest("tok", null)) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("tenant mismatch")
    }

    @Test
    fun tenantMismatch_otherTenant_logsWarn() {
        val other = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        TenantContextHolder.set(other)
        whenever(store.takeRegistration("tok")).thenReturn(Optional.of(challenge(TENANT.toString())))

        assertThatThrownBy { svc().finish(RegistrationFinishRequest("tok", null)) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("tenant mismatch")
        assertThat(messages(Level.WARN)).containsExactly(
            "tenant mismatch on registration/finish: ctx=$other ch=$TENANT",
        )
        verifyNoInteractions(tenants)
        assertThat(count("failure")).isEqualTo(1.0)
    }

    @Test
    fun tenantMismatch_noContext_logsNullCtx() {
        whenever(store.takeRegistration("tok")).thenReturn(Optional.of(challenge(TENANT.toString())))

        assertThatThrownBy { svc().finish(RegistrationFinishRequest("tok", null)) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("tenant mismatch")
        assertThat(messages(Level.WARN)).containsExactly(
            "tenant mismatch on registration/finish: ctx=null ch=$TENANT",
        )
    }

    @Test
    fun tenantMissing_illegalState() {
        stubChallenge()
        whenever(tenants.findById(TENANT)).thenReturn(Optional.empty())

        assertThatThrownBy { svc().finish(RegistrationFinishRequest("tok", null)) }
            .isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("tenant $TENANT missing")
        assertThat(count("failure")).isEqualTo(1.0)
    }

    @Test
    fun noAllowedOrigins_illegalState() {
        stubChallenge()
        whenever(tenant.allowedOriginValues).thenReturn(listOf())

        assertThatThrownBy { svc().finish(RegistrationFinishRequest("tok", null)) }
            .isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("tenant $TENANT has no allowed_origins configured")
        verifyNoInteractions(verifier)
    }

    @Test
    fun verifierInput_carriesTenantPolicy() {
        stubChallenge()
        whenever(verifier.verifyRegistration(anyOrNull())).thenReturn(result(AAGUID, "packed", setOf()))
        whenever(mds.verify(true, AAGUID)).thenReturn(true)

        svc().finish(RegistrationFinishRequest("tok", null))

        val cap = argumentCaptor<RegistrationInput>()
        verify(verifier).verifyRegistration(cap.capture())
        val input = cap.lastValue
        // 명시적 JSON null 요청 → "null" 로 직렬화돼 verifier 로 넘어간다
        assertThat(input.credentialJson()).isEqualTo("null")
        assertThat(input.challenge()).containsExactly(7, 7, 7)
        assertThat(input.allowedOrigins()).isInstanceOf(LinkedHashSet::class.java)
            .containsExactly("https://b.example", "https://a.example")
        assertThat(input.rpId()).isEqualTo("example.com")
        assertThat(input.userVerificationRequired()).isTrue()
        assertThat(input.allowedAlgorithms()).containsExactlyInAnyOrder(COSEAlgorithm.ES256, COSEAlgorithm.RS256)
        assertThat(input.acceptedAttestationFormats()).containsExactlyInAnyOrder("packed", "none")
        assertThat(input.trustPolicy()).isEqualTo(AttestationTrustPolicy.SELF_ALLOWED)
    }

    @Test
    fun verifierThrows_illegalArgument_logsReason() {
        stubChallenge()
        whenever(verifier.verifyRegistration(anyOrNull())).thenThrow(
            WebAuthnVerificationException(WebAuthnVerificationException.Reason.BAD_SIGNATURE, "sig bad"),
        )

        assertThatThrownBy { svc().finish(RegistrationFinishRequest("tok", null)) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("attestation verify failed")
            .hasNoCause()
        assertThat(messages(Level.WARN)).containsExactly(
            "attestation verify failed for tenant $TENANT: sig bad (BAD_SIGNATURE)",
        )
        assertThat(count("failure")).isEqualTo(1.0)
        verifyNoInteractions(mds)
    }

    @Test
    fun formatNotAccepted_illegalArgument() {
        stubChallenge()
        whenever(verifier.verifyRegistration(anyOrNull())).thenReturn(result(AAGUID, "tpm", setOf()))

        assertThatThrownBy { svc().finish(RegistrationFinishRequest("tok", null)) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("attestation format not accepted by tenant policy")
        verifyNoInteractions(mds)
    }

    @Test
    fun mdsRejects_illegalArgument_beforePolicyCheck() {
        stubChallenge()
        whenever(verifier.verifyRegistration(anyOrNull())).thenReturn(result(AAGUID, "packed", setOf()))
        whenever(mds.verify(true, AAGUID)).thenReturn(false)

        assertThatThrownBy { svc().finish(RegistrationFinishRequest("tok", null)) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("authenticator metadata verification failed")
        verifyNoInteractions(policy)
        verify(credentials, never()).saveAndFlush(anyOrNull<Credential>())
    }

    @Test
    fun policyViolation_propagatesUnchanged_noSave() {
        stubChallenge()
        whenever(verifier.verifyRegistration(anyOrNull())).thenReturn(result(AAGUID, "packed", setOf()))
        whenever(mds.verify(true, AAGUID)).thenReturn(true)
        val boom: RuntimeException = BusinessException(ErrorCode.INVALID_INPUT, "policy")
        doThrow(boom).whenever(policy).check(anyOrNull(), anyOrNull())

        assertThatThrownBy { svc().finish(RegistrationFinishRequest("tok", null)) }
            .isSameAs(boom)
        verify(policy).check(TENANT, UUID.fromString("01020304-0506-0708-090a-0b0c0d0e0f10"))
        verify(credentials, never()).saveAndFlush(anyOrNull<Credential>())
        assertThat(count("failure")).isEqualTo(1.0)
    }

    @Test
    fun nonSixteenByteAaguid_policyCheckedWithNullUuid() {
        stubChallenge()
        val shortAaguid = byteArrayOf(1, 2, 3)
        whenever(verifier.verifyRegistration(anyOrNull())).thenReturn(result(shortAaguid, "none", null))
        whenever(mds.verify(true, shortAaguid)).thenReturn(true)

        val resp = svc().finish(RegistrationFinishRequest("tok", null))

        verify(policy).check(TENANT, null)
        assertThat(resp.aaguid).isEqualTo("010203")
    }

    @Test
    fun success_persistsCredentialAndRespondsAndRecords() {
        stubChallenge()
        whenever(verifier.verifyRegistration(anyOrNull()))
            .thenReturn(result(AAGUID, "packed", linkedSetOf("usb", "nfc")))
        whenever(mds.verify(true, AAGUID)).thenReturn(true)

        val resp = svc().finish(RegistrationFinishRequest("tok", null))

        val cap = argumentCaptor<Credential>()
        verify(credentials).saveAndFlush(cap.capture())
        val c = cap.lastValue
        assertThat(c.tenantId).isEqualTo(TENANT)
        assertThat(c.userHandle).containsExactly(9, 9)
        assertThat(c.credentialId).isEqualTo(CRED_ID)
        assertThat(c.cosePublicKey).containsExactly(5, 6)
        assertThat(c.aaguid).isEqualTo(AAGUID)
        assertThat(c.attestationFmt).isEqualTo("packed")
        assertThat(c.transports).isEqualTo("usb,nfc")

        assertThat(resp.credentialId).isEqualTo("AQIDBAUGBwgJCgsMDQ4PEBESExQ")
        assertThat(resp.aaguid).isEqualTo("0102030405060708090a0b0c0d0e0f10")
        assertThat(resp.attestationFormat).isEqualTo("packed")
        assertThat(resp.createdAt).isEqualTo(NOW)

        assertThat(messages(Level.INFO)).containsExactly(
            "registration/finish ok: credentialIdTail=...MDQ4PEBESExQ" +
                " aaguid=01020304-0506-0708-090a-0b0c0d0e0f10 format=packed",
        )
        verify(ceremonyEvents).recordAfterCommit(TENANT, CeremonyAction.REGISTRATION_SUCCESS)
        assertThat(count("success")).isEqualTo(1.0)
        assertThat(count("failure")).isEqualTo(0.0)

        val order = inOrder(store, tenants, verifier, mds, policy, credentials, ceremonyEvents)
        order.verify(store).takeRegistration("tok")
        order.verify(tenants).findById(TENANT)
        order.verify(verifier).verifyRegistration(anyOrNull())
        order.verify(mds).verify(true, AAGUID)
        order.verify(policy).check(anyOrNull(), anyOrNull())
        order.verify(credentials).saveAndFlush(anyOrNull<Credential>())
        order.verify(ceremonyEvents).recordAfterCommit(anyOrNull(), anyOrNull())
    }

    @Test
    fun success_nullAaguidAndEmptyTransports_shortIdMasked() {
        stubChallenge()
        val shortId = byteArrayOf(1, 2, 3, 4)
        whenever(verifier.verifyRegistration(anyOrNull())).thenReturn(
            RegistrationResult(shortId, byteArrayOf(5), 0L, null, "none", setOf(), false, true, false, false),
        )
        whenever(mds.verify(true, null)).thenReturn(true)

        val resp = svc().finish(RegistrationFinishRequest("tok", null))

        val cap = argumentCaptor<Credential>()
        verify(credentials).saveAndFlush(cap.capture())
        assertThat(cap.lastValue.aaguid).isNull()
        assertThat(cap.lastValue.transports).isNull()
        verify(policy).check(TENANT, null)
        assertThat(resp.aaguid).isNull()
        assertThat(resp.credentialId).isEqualTo("AQIDBA")
        assertThat(messages(Level.INFO)).containsExactly(
            "registration/finish ok: credentialIdTail=*** aaguid=null format=none",
        )
    }

    @Test
    fun success_twelveCharId_fullyMasked_thirteenCharId_tailed() {
        stubChallenge()
        whenever(verifier.verifyRegistration(anyOrNull())).thenReturn(
            RegistrationResult(ByteArray(9), byteArrayOf(5), 0L, null, "none", null, false, true, false, false),
        )
        whenever(mds.verify(true, null)).thenReturn(true)
        svc().finish(RegistrationFinishRequest("tok", null))

        whenever(store.takeRegistration("tok2")).thenReturn(Optional.of(challenge(TENANT.toString())))
        whenever(verifier.verifyRegistration(anyOrNull())).thenReturn(
            RegistrationResult(ByteArray(10), byteArrayOf(5), 0L, null, "none", null, false, true, false, false),
        )
        svc().finish(RegistrationFinishRequest("tok2", null))

        assertThat(messages(Level.INFO)).containsExactly(
            "registration/finish ok: credentialIdTail=*** aaguid=null format=none",
            "registration/finish ok: credentialIdTail=...AAAAAAAAAAAA aaguid=null format=none",
        )
    }

    @Test
    fun duplicate_logsWarnAndFailureMetric() {
        stubChallenge()
        whenever(verifier.verifyRegistration(anyOrNull())).thenReturn(result(AAGUID, "packed", setOf()))
        whenever(mds.verify(true, AAGUID)).thenReturn(true)
        whenever(credentials.saveAndFlush(anyOrNull<Credential>())).thenThrow(DataIntegrityViolationException("dup"))

        assertThatThrownBy { svc().finish(RegistrationFinishRequest("tok", null)) }
            .isExactlyInstanceOf(BusinessException::class.java)
            .hasMessage("credential already registered")
            .hasNoCause()
        assertThat(messages(Level.WARN)).containsExactly(
            "registration/finish rejected: duplicate credential for tenant $TENANT",
        )
        assertThat(count("failure")).isEqualTo(1.0)
        verifyNoInteractions(ceremonyEvents)
    }
}
