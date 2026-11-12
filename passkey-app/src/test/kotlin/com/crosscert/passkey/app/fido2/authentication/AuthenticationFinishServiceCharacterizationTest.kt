package com.crosscert.passkey.app.fido2.authentication

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishRequest
import com.crosscert.passkey.app.fido2.CeremonyMetrics
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore
import com.crosscert.passkey.core.alert.SecurityAlertEvent
import com.crosscert.passkey.core.ceremony.CeremonyAction
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder
import com.crosscert.passkey.core.ceremony.CredentialAuthEventRecorder
import com.crosscert.passkey.core.ceremony.CredentialAuthFailureReason
import com.crosscert.passkey.core.ceremony.CredentialAuthResult
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.jwt.IdTokenIssuer
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import com.crosscert.passkey.webauthn.verifier.AuthenticationInput
import com.crosscert.passkey.webauthn.verifier.AuthenticationResult
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.util.ReflectionTestUtils
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

private val NOW = Instant.parse("2026-11-06T00:00:00Z")
private val TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
private val CRED_UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
private val CRED_ID = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
private const val CRED_ID_B64 = "AQIDBAUGBwgJCgsMDQ4PEBESExQ"
private val USER = byteArrayOf(9, 9)
private val AAGUID = ByteArray(16)

/**
 * Kotlin 전환 전 AuthenticationFinishService 의 외부 관찰 가능 동작(실패별 예외 타입·메시지,
 * 비관적 락 획득 위치/순서, signCount 규칙, 보안 알림 이벤트, 인증 이벤트 기록, 메트릭, 로그 문구,
 * id-token 발급 인자, 응답)을 현재 Java 구현 기준으로 고정하는 특성 테스트.
 */
class AuthenticationFinishServiceCharacterizationTest {

    private val store: ChallengeStore = mock()
    private val verifier: WebAuthnVerifier = mock()
    private val tenants: TenantRepository = mock()
    private val credentials: CredentialRepository = mock()
    private val idTokens: IdTokenIssuer = mock()
    private val events: ApplicationEventPublisher = mock()
    private val ceremonyEvents: CeremonyEventRecorder = mock()
    private val authEvents: CredentialAuthEventRecorder = mock()
    private val meters = SimpleMeterRegistry()
    private val clock = Clock.fixed(NOW, KstTime.ZONE)
    private val mapper = ObjectMapper()
    private val tenant: Tenant = mock()

    // id 는 JPA 생성 값이라 setter 가 없다 — 원 테스트와 같이 리플렉션으로 주입.
    private val cred = Credential(TENANT, USER, CRED_ID, byteArrayOf(5, 6), AAGUID)
        .also { ReflectionTestUtils.setField(it, "id", CRED_UUID) }

    private val logger = LoggerFactory.getLogger(AuthenticationFinishService::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.DEBUG

        whenever(tenant.id).thenReturn(TENANT)
        whenever(tenant.allowedOriginValues).thenReturn(listOf("https://a.example"))
        whenever(tenant.rpId).thenReturn("example.com")
        whenever(tenant.isRequireUserVerification).thenReturn(false)
        whenever(tenants.findById(TENANT)).thenReturn(Optional.of(tenant))

        whenever(credentials.findByCredentialIdForUpdate(anyOrNull())).thenReturn(Optional.of(cred))
        whenever(idTokens.issue(any<String>(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn("jwt.value")
        whenever(idTokens.tokenTtlSeconds()).thenReturn(900L)
    }

    @AfterEach
    fun tearDown() {
        TenantContextHolder.clear()
        logger.detachAppender(appender)
        logger.level = null
    }

    private fun svc() = AuthenticationFinishService(
        store, verifier, tenants, credentials, idTokens,
        mapper, clock, CeremonyMetrics(meters), events, ceremonyEvents, authEvents,
    )

    private fun stubChallenge(userHandle: ByteArray?) {
        TenantContextHolder.set(TENANT)
        whenever(store.takeAuthentication("tok")).thenReturn(
            Optional.of(AuthenticationChallenge(TENANT.toString(), byteArrayOf(7, 7), userHandle, NOW)),
        )
    }

    private fun req(rawIdJsonValue: String): AuthenticationFinishRequest {
        val pkc = mapper.readTree("""{"id":"x","rawId":$rawIdJsonValue}""")
        return AuthenticationFinishRequest("tok", pkc)
    }

    private fun validReq() = req("\"$CRED_ID_B64\"")

    private fun storedCount(n: Long) {
        cred.recordAuthentication(n, null)
    }

    private fun count(result: String): Double =
        meters.find("passkey_ceremony_total")
            .tags("type", "authentication", "phase", "finish", "result", result).counter()
            ?.count() ?: 0.0

    private fun messages(level: Level): List<String> =
        appender.list.filter { it.level == level }.map { it.formattedMessage }

    @Test
    fun nullToken_illegalArgumentInsideMeasuredBlock_failureMetricCounted() {
        // 브랜치 기준 Java: null 토큰 → "challenge:auth:null" 조회가 빈 값 → 토큰 누락과 같은 IAE·메시지,
        // 측정 블록 안이라 실패 메트릭 1. Kotlin 은 같은 결과를 Redis 조회 없이 낸다(외부 관찰 불가 차이).
        assertThatThrownBy { svc().finish(AuthenticationFinishRequest(null, null)) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("authentication token missing or expired")
        assertThat(count("failure")).isEqualTo(1.0)
        assertThat(count("success")).isEqualTo(0.0)
        verifyNoInteractions(store, tenants, credentials)
    }

    @Test
    fun tokenMissing_illegalArgument() {
        whenever(store.takeAuthentication("tok")).thenReturn(Optional.empty())

        assertThatThrownBy { svc().finish(validReq()) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("authentication token missing or expired")
        assertThat(count("failure")).isEqualTo(1.0)
        verifyNoInteractions(credentials)
    }

    @Test
    fun stale_logsWarn() {
        TenantContextHolder.set(TENANT)
        val stale = NOW.minus(ChallengeStore.TTL).minusMillis(1)
        whenever(store.takeAuthentication("tok")).thenReturn(
            Optional.of(AuthenticationChallenge(TENANT.toString(), byteArrayOf(7), null, stale)),
        )

        assertThatThrownBy { svc().finish(validReq()) }
            .hasMessage("challenge expired")
        assertThat(messages(Level.WARN)).containsExactly(
            "stale authentication challenge rejected: issuedAt=$stale ttl=PT5M",
        )
    }

    @Test
    fun tenantMismatch_logsWarn_noLock() {
        val other = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        TenantContextHolder.set(other)
        whenever(store.takeAuthentication("tok")).thenReturn(
            Optional.of(AuthenticationChallenge(TENANT.toString(), byteArrayOf(7), null, NOW)),
        )

        assertThatThrownBy { svc().finish(validReq()) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("tenant mismatch")
        assertThat(messages(Level.WARN)).containsExactly(
            "tenant mismatch on authentication/finish: ctx=$other ch=$TENANT",
        )
        verifyNoInteractions(tenants, credentials)
    }

    @Test
    fun tenantMissing_illegalState() {
        stubChallenge(USER)
        whenever(tenants.findById(TENANT)).thenReturn(Optional.empty())

        assertThatThrownBy { svc().finish(validReq()) }
            .isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("tenant $TENANT missing")
        verifyNoInteractions(credentials)
    }

    @Test
    fun nullPublicKeyCredential_rawIdInvalid() {
        stubChallenge(USER)

        assertThatThrownBy { svc().finish(AuthenticationFinishRequest("tok", null)) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("assertion rawId invalid")
            .hasNoCause()
        verifyNoInteractions(credentials)
        assertThat(count("failure")).isEqualTo(1.0)
    }

    @Test
    fun rawIdAbsent_rawIdInvalid() {
        stubChallenge(USER)
        val pkc = mapper.readTree("""{"id":"x"}""")

        assertThatThrownBy { svc().finish(AuthenticationFinishRequest("tok", pkc)) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("assertion rawId invalid")
        verifyNoInteractions(credentials)
    }

    @Test
    fun rawIdNotBase64url_rawIdInvalid() {
        stubChallenge(USER)

        assertThatThrownBy { svc().finish(req("\"***\"")) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("assertion rawId invalid")
        verifyNoInteractions(credentials)
    }

    @Test
    fun rawIdObject_coercedToEmpty_lookedUpAsNotRegistered() {
        stubChallenge(USER)
        whenever(credentials.findByCredentialIdForUpdate(anyOrNull())).thenReturn(Optional.empty())

        assertThatThrownBy { svc().finish(req("""{"a":1}""")) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("credential not registered")
        val cap = argumentCaptor<ByteArray>()
        verify(credentials).findByCredentialIdForUpdate(cap.capture())
        assertThat(cap.lastValue).isEmpty()
    }

    @Test
    fun credentialNotFound_illegalArgument() {
        stubChallenge(USER)
        whenever(credentials.findByCredentialIdForUpdate(anyOrNull())).thenReturn(Optional.empty())

        assertThatThrownBy { svc().finish(validReq()) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("credential not registered")
        verifyNoInteractions(verifier)
    }

    @Test
    fun userHandleMismatch_sameMessageAsNotRegistered_logsWarn() {
        stubChallenge(byteArrayOf(1, 1))

        assertThatThrownBy { svc().finish(validReq()) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("credential not registered")
        assertThat(messages(Level.WARN)).containsExactly(
            "userHandle mismatch on authentication/finish for tenant $TENANT",
        )
        verifyNoInteractions(verifier)
    }

    @Test
    fun noAllowedOrigins_illegalState_afterLock() {
        stubChallenge(USER)
        whenever(tenant.allowedOriginValues).thenReturn(listOf())

        assertThatThrownBy { svc().finish(validReq()) }
            .isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("tenant $TENANT has no allowed_origins configured")
        verify(credentials).findByCredentialIdForUpdate(anyOrNull())
        verifyNoInteractions(verifier)
    }

    @Test
    fun verifyFails_recordsAfterRollbackWithStoredCount() {
        stubChallenge(USER)
        storedCount(41)
        whenever(verifier.verifyAuthentication(anyOrNull())).thenThrow(
            WebAuthnVerificationException(WebAuthnVerificationException.Reason.BAD_SIGNATURE, "nope"),
        )

        assertThatThrownBy { svc().finish(validReq()) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("assertion verify failed")
            .hasNoCause()
        verify(authEvents).recordAfterRollback(
            CRED_UUID, TENANT, CredentialAuthResult.FAILED,
            CredentialAuthFailureReason.SIGNATURE_INVALID, 41L,
        )
        assertThat(messages(Level.WARN)).containsExactly(
            "assertion verify failed for tenant $TENANT: nope (BAD_SIGNATURE)",
        )
        verify(credentials, never()).saveAndFlush(anyOrNull<Credential>())
        assertThat(count("failure")).isEqualTo(1.0)
    }

    @Test
    fun verifierInput_carriesStoredCredentialAndPolicy() {
        stubChallenge(null) // usernameless: userHandle 바인딩 검사 생략
        storedCount(3)
        whenever(verifier.verifyAuthentication(anyOrNull()))
            .thenReturn(AuthenticationResult(CRED_ID, 4, true, true, false))

        svc().finish(validReq())

        val cap = argumentCaptor<AuthenticationInput>()
        verify(verifier).verifyAuthentication(cap.capture())
        val input = cap.lastValue
        assertThat(input.credentialJson()).isEqualTo("""{"id":"x","rawId":"$CRED_ID_B64"}""")
        assertThat(input.challenge()).containsExactly(7, 7)
        assertThat(input.allowedOrigins()).containsExactly("https://a.example")
        assertThat(input.rpId()).isEqualTo("example.com")
        assertThat(input.userVerificationRequired()).isFalse()
        assertThat(input.storedCredential().credentialId()).isEqualTo(CRED_ID)
        assertThat(input.storedCredential().cosePublicKey()).containsExactly(5, 6)
        assertThat(input.storedCredential().signCount()).isEqualTo(3L)
    }

    @Test
    fun counterNotAdvanced_publishesAlert_recordsReplay() {
        stubChallenge(USER)
        storedCount(10)
        whenever(verifier.verifyAuthentication(anyOrNull()))
            .thenReturn(AuthenticationResult(CRED_ID, 10, true, true, false))

        assertThatThrownBy { svc().finish(validReq()) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("signCount replay detected")

        val ev = argumentCaptor<Any>()
        verify(events).publishEvent(ev.capture())
        val alert = ev.lastValue as SecurityAlertEvent
        assertThat(alert.type()).isEqualTo(SecurityAlertEvent.AlertType.COUNTER_REGRESSION)
        assertThat(alert.severity()).isEqualTo(SecurityAlertEvent.Severity.HIGH)
        assertThat(alert.summary()).isEqualTo("signCount did not advance (possible cloned authenticator)")
        assertThat(alert.context()).isEqualTo(
            mapOf("credentialId" to CRED_UUID.toString(), "tenantId" to TENANT.toString()),
        )
        verify(authEvents).recordAfterRollback(
            CRED_UUID, TENANT, CredentialAuthResult.FAILED,
            CredentialAuthFailureReason.SIGN_COUNT_REPLAY, 10L,
        )
        assertThat(messages(Level.WARN)).containsExactly(
            "signCount did not advance for credential $CRED_UUID on tenant $TENANT (was 10, got 10)",
        )
        verify(credentials, never()).saveAndFlush(anyOrNull<Credential>())
        assertThat(cred.signCount).isEqualTo(10L)
        assertThat(count("failure")).isEqualTo(1.0)
    }

    @Test
    fun counterRegressedToZero_rejected() {
        stubChallenge(USER)
        storedCount(5)
        whenever(verifier.verifyAuthentication(anyOrNull()))
            .thenReturn(AuthenticationResult(CRED_ID, 0, true, true, false))

        assertThatThrownBy { svc().finish(validReq()) }
            .hasMessage("signCount replay detected")
        verify(authEvents).recordAfterRollback(
            CRED_UUID, TENANT, CredentialAuthResult.FAILED,
            CredentialAuthFailureReason.SIGN_COUNT_REPLAY, 0L,
        )
    }

    @Test
    fun counterless_zeroZero_accepted() {
        stubChallenge(USER)
        whenever(verifier.verifyAuthentication(anyOrNull()))
            .thenReturn(AuthenticationResult(CRED_ID, 0, true, true, false))

        val resp = svc().finish(validReq())

        assertThat(resp.idToken).isEqualTo("jwt.value")
        verify(authEvents).recordAfterCommit(CRED_UUID, TENANT, CredentialAuthResult.SUCCESS, null, 0L)
        verifyNoInteractions(events)
    }

    @Test
    fun success_expiresInFollowsIssuerTtl_notAHardcodedConstant() {
        // passkey.id-token.ttl 을 바꾸면 JWT exp 와 응답 expiresIn·발급 로그가 같은 값을 말해야 한다.
        whenever(idTokens.tokenTtlSeconds()).thenReturn(600L)
        stubChallenge(USER)
        storedCount(1)
        whenever(verifier.verifyAuthentication(anyOrNull()))
            .thenReturn(AuthenticationResult(CRED_ID, 2, true, true, false))

        val resp = svc().finish(validReq())

        assertThat(resp.expiresIn).isEqualTo(600L)
        assertThat(messages(Level.INFO)).contains("id-token issued: subTail=*** aud=$TENANT ttlSec=600")
    }

    @Test
    fun success_locksBeforeVerify_persistsCounter_issuesToken() {
        stubChallenge(USER)
        storedCount(1)
        whenever(verifier.verifyAuthentication(anyOrNull()))
            .thenReturn(AuthenticationResult(CRED_ID, 2, true, true, false))

        val resp = svc().finish(validReq())

        assertThat(resp.idToken).isEqualTo("jwt.value")
        assertThat(resp.tokenType).isEqualTo("Bearer")
        assertThat(resp.expiresIn).isEqualTo(900L)
        assertThat(cred.signCount).isEqualTo(2L)
        assertThat(cred.lastUsedAt).isEqualTo(OffsetDateTime.now(clock))

        val lockArg = argumentCaptor<ByteArray>()
        val order = inOrder(store, tenants, credentials, verifier, authEvents, idTokens, ceremonyEvents)
        order.verify(store).takeAuthentication("tok")
        order.verify(tenants).findById(TENANT)
        order.verify(credentials).findByCredentialIdForUpdate(lockArg.capture())
        order.verify(verifier).verifyAuthentication(anyOrNull())
        order.verify(credentials).saveAndFlush(cred)
        order.verify(authEvents).recordAfterCommit(CRED_UUID, TENANT, CredentialAuthResult.SUCCESS, null, 2L)
        order.verify(idTokens).issue(TENANT.toString(), USER, CRED_UUID, AAGUID)
        order.verify(ceremonyEvents).recordAfterCommit(TENANT, CeremonyAction.AUTHENTICATION_SUCCESS)
        assertThat(lockArg.lastValue).isEqualTo(CRED_ID)
        verify(credentials, never()).findByCredentialId(anyOrNull())
        verify(authEvents, never()).recordAfterRollback(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any<Long>())
        verifyNoInteractions(events)

        assertThat(messages(Level.INFO)).containsExactly(
            "authentication/finish ok: credentialIdTail=...MDQ4PEBESExQ counter=2",
            "id-token issued: subTail=*** aud=$TENANT ttlSec=900",
        )
        assertThat(count("success")).isEqualTo(1.0)
        assertThat(count("failure")).isEqualTo(0.0)
    }

    @Test
    fun twelveCharIds_fullyMaskedInLogs() {
        // credentialId 9 바이트 / userHandle 9 바이트 → base64url 12자 경계: 전체 마스킹
        val id9 = ByteArray(9)
        val user9 = ByteArray(9)
        val c9 = Credential(TENANT, user9, id9, byteArrayOf(5), null)
        ReflectionTestUtils.setField(c9, "id", CRED_UUID)
        whenever(credentials.findByCredentialIdForUpdate(anyOrNull())).thenReturn(Optional.of(c9))
        stubChallenge(user9)
        whenever(verifier.verifyAuthentication(anyOrNull()))
            .thenReturn(AuthenticationResult(id9, 1, true, true, false))

        svc().finish(req("\"AAAAAAAAAAAA\""))

        verify(idTokens).issue(TENANT.toString(), user9, CRED_UUID, null)
        assertThat(messages(Level.INFO)).containsExactly(
            "authentication/finish ok: credentialIdTail=*** counter=1",
            "id-token issued: subTail=*** aud=$TENANT ttlSec=900",
        )
    }

    @Test
    fun idTokenIssueFailure_failureMetric_afterPersist() {
        stubChallenge(USER)
        whenever(verifier.verifyAuthentication(anyOrNull()))
            .thenReturn(AuthenticationResult(CRED_ID, 1, true, true, false))
        val boom: RuntimeException = IllegalStateException("signer down")
        whenever(idTokens.issue(any<String>(), anyOrNull(), anyOrNull(), anyOrNull())).thenThrow(boom)

        assertThatThrownBy { svc().finish(validReq()) }.isSameAs(boom)
        verify(credentials).saveAndFlush(cred)
        verify(ceremonyEvents, never()).recordAfterCommit(anyOrNull(), eq(CeremonyAction.AUTHENTICATION_SUCCESS))
        assertThat(count("failure")).isEqualTo(1.0)
        assertThat(count("success")).isEqualTo(0.0)
    }
}
