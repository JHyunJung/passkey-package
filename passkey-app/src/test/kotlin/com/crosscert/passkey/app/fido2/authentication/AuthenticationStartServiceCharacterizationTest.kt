package com.crosscert.passkey.app.fido2.authentication

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartRequest
import com.crosscert.passkey.app.fido2.CeremonyMetrics
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.ceremony.CeremonyAction
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

private val NOW = Instant.parse("2026-11-06T00:00:00Z")
private val TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

/**
 * Kotlin 전환 전 AuthenticationStartService 의 외부 관찰 가능 동작(실패별 예외 타입·메시지,
 * usernameless/typed 분기, 저장 challenge 필드, 옵션 JSON 바이트, 로그 문구, 메트릭, ceremony 이벤트)을
 * 현재 Java 구현 기준으로 고정하는 특성 테스트. Suspend 테스트가 덮는 분기는 제외.
 */
class AuthenticationStartServiceCharacterizationTest {

    private val tenants: TenantRepository = mock()
    private val credentials: CredentialRepository = mock()
    private val challenges: ChallengeIssuer = mock()
    private val store: ChallengeStore = mock()
    private val ceremonyEvents: CeremonyEventRecorder = mock()
    private val meters = SimpleMeterRegistry()
    private val tenant: Tenant = mock()

    private val logger = LoggerFactory.getLogger(AuthenticationStartService::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.DEBUG

        whenever(tenant.rpId).thenReturn("example.com")
        whenever(tenant.webauthnTimeoutMs).thenReturn(60000)
        whenever(tenants.findById(TENANT)).thenReturn(Optional.of(tenant))
        whenever(challenges.newChallengeBytes()).thenReturn(byteArrayOf(1, 2, 3))
        whenever(challenges.newToken()).thenReturn("tok_abcdefghijkl")
    }

    @AfterEach
    fun tearDown() {
        TenantContextHolder.clear()
        logger.detachAppender(appender)
        logger.level = null
    }

    private fun svc() = AuthenticationStartService(
        tenants, credentials, challenges, store, ObjectMapper(),
        Clock.fixed(NOW, KstTime.ZONE), CeremonyMetrics(meters), ceremonyEvents,
    )

    private fun count(result: String): Double =
        meters.find("passkey_ceremony_total")
            .tags("type", "authentication", "phase", "start", "result", result).counter()
            ?.count() ?: 0.0

    private fun infos(): List<String> =
        appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }

    @Test
    fun nullRequest_npeInsideMeasuredBlock_failureMetricCounted() {
        // 원 Java: try 안 첫 문장(entry 로그 인자 req.getUserHandle())에서 NPE → 실패 메트릭 1 → 재던짐.
        TenantContextHolder.set(TENANT)

        assertThatThrownBy { svc().start(null) }
            .isExactlyInstanceOf(NullPointerException::class.java)
        assertThat(count("failure")).isEqualTo(1.0)
        assertThat(count("success")).isEqualTo(0.0)
        assertThat(infos()).isEmpty()
        verifyNoInteractions(tenants, store, ceremonyEvents)
    }

    @Test
    fun noTenantContext_illegalState() {
        assertThatThrownBy { svc().start(AuthenticationStartRequest(null)) }
            .isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("authentication/start invoked without tenant context")
        assertThat(count("failure")).isEqualTo(1.0)
        assertThat(infos()).containsExactly("authentication/start entry: userHandlePresent=false")
        verifyNoInteractions(tenants)
    }

    @Test
    fun tenantNotFound_illegalState() {
        TenantContextHolder.set(TENANT)
        whenever(tenants.findById(TENANT)).thenReturn(Optional.empty())

        assertThatThrownBy { svc().start(AuthenticationStartRequest(null)) }
            .isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("tenant $TENANT not found")
    }

    @Test
    fun suspended_message() {
        TenantContextHolder.set(TENANT)
        whenever(tenant.isSuspended).thenReturn(true)

        assertThatThrownBy { svc().start(AuthenticationStartRequest(null)) }
            .isExactlyInstanceOf(BusinessException::class.java)
            .hasMessage("tenant suspended: $TENANT")
        assertThat(count("failure")).isEqualTo(1.0)
        verifyNoInteractions(challenges, store)
    }

    @Test
    fun invalidBase64UserHandle_rawDecoderException_failureMetric() {
        TenantContextHolder.set(TENANT)

        assertThatThrownBy { svc().start(AuthenticationStartRequest("***")) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Illegal base64 character")
        assertThat(count("failure")).isEqualTo(1.0)
        verifyNoInteractions(credentials, store)
    }

    @Test
    fun usernameless_emptyAllowList_noRepoLookup_nullUserHandleStored() {
        TenantContextHolder.set(TENANT)

        val resp = svc().start(AuthenticationStartRequest(null))

        verify(credentials, never()).findByUserHandle(anyOrNull())
        val cap = argumentCaptor<AuthenticationChallenge>()
        verify(store).putAuthentication(eq("tok_abcdefghijkl"), cap.capture())
        val ch = cap.lastValue
        assertThat(ch.tenantId).isEqualTo(TENANT.toString())
        assertThat(ch.challenge).containsExactly(1, 2, 3)
        assertThat(ch.userHandle).isNull()
        assertThat(ch.issuedAt).isEqualTo(NOW)

        assertThat(resp.authenticationToken).isEqualTo("tok_abcdefghijkl")
        assertThat(resp.publicKeyCredentialRequestOptions.toString()).isEqualTo(
            """{"challenge":"AQID","rpId":"example.com","timeout":60000,""" +
                """"userVerification":"preferred","allowCredentials":[]}""",
        )
        assertThat(infos()).containsExactly(
            "authentication/start entry: userHandlePresent=false",
            "authentication/start issued: tokenTail=efghijkl allowCount=0 timeoutMs=60000",
        )
        verify(ceremonyEvents).recordAfterCommit(TENANT, CeremonyAction.AUTHENTICATION_BEGIN)
        assertThat(count("success")).isEqualTo(1.0)
        assertThat(count("failure")).isEqualTo(0.0)
    }

    @Test
    fun typed_allowListInRepoOrder_decodedUserHandleStored() {
        TenantContextHolder.set(TENANT)
        val a: Credential = mock()
        whenever(a.credentialId).thenReturn(byteArrayOf(1))
        val b: Credential = mock()
        whenever(b.credentialId).thenReturn(byteArrayOf(2))
        whenever(credentials.findByUserHandle(anyOrNull())).thenReturn(listOf(a, b))
        whenever(challenges.newToken()).thenReturn("short")

        val resp = svc().start(AuthenticationStartRequest("CQk"))

        val cap = argumentCaptor<AuthenticationChallenge>()
        verify(store).putAuthentication(eq("short"), cap.capture())
        assertThat(cap.lastValue.userHandle).containsExactly(9, 9)
        assertThat(resp.publicKeyCredentialRequestOptions.get("allowCredentials").toString())
            .isEqualTo("""[{"type":"public-key","id":"AQ"},{"type":"public-key","id":"Ag"}]""")
        assertThat(infos()).containsExactly(
            "authentication/start entry: userHandlePresent=true",
            "authentication/start issued: tokenTail=*** allowCount=2 timeoutMs=60000",
        )
    }
}
