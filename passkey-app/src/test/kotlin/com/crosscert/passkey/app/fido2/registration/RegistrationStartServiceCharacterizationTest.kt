package com.crosscert.passkey.app.fido2.registration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest
import com.crosscert.passkey.app.fido2.CeremonyMetrics
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.ceremony.CeremonyAction
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder
import com.crosscert.passkey.core.config.KstTime
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
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.UUID

private val NOW = Instant.parse("2026-11-06T00:00:00Z")
private val TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

/**
 * Kotlin 전환 전 RegistrationStartService 의 외부 관찰 가능 동작(실패별 예외 타입·메시지,
 * 옵션 JSON 바이트, 저장 challenge 필드, 로그 문구, 메트릭, ceremony 이벤트, null displayName/username
 * 수용)을 현재 Java 구현 기준으로 고정하는 특성 테스트. Exclude/UserHandleValidation 테스트가 덮는 분기는 제외.
 */
class RegistrationStartServiceCharacterizationTest {

    private val tenants: TenantRepository = mock()
    private val credentials: CredentialRepository = mock()
    private val challenges: ChallengeIssuer = mock()
    private val store: ChallengeStore = mock()
    private val ceremonyEvents: CeremonyEventRecorder = mock()
    private val meters = SimpleMeterRegistry()
    private val tenant: Tenant = mock()

    private val logger = LoggerFactory.getLogger(RegistrationStartService::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.DEBUG

        whenever(tenant.rpId).thenReturn("example.com")
        whenever(tenant.rpName).thenReturn("Example")
        whenever(tenant.webauthnTimeoutMs).thenReturn(60000)
        whenever(tenant.attestationConveyanceLowercase).thenReturn("direct")
        whenever(tenant.isRequireUserVerification).thenReturn(true)
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

    private fun svc() = RegistrationStartService(
        tenants, credentials, challenges, store, ObjectMapper(),
        Clock.fixed(NOW, KstTime.ZONE), CeremonyMetrics(meters), ceremonyEvents,
    )

    private fun count(result: String): Double =
        meters.find("passkey_ceremony_total")
            .tags("type", "registration", "phase", "start", "result", result).counter()
            ?.count() ?: 0.0

    private fun infos(): List<String> =
        appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }

    @Test
    fun noTenantContext_illegalState() {
        assertThatThrownBy { svc().start(RegistrationStartRequest("CQk", "Disp", "alice")) }
            .isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("registration/start invoked without tenant context — ApiKeyAuthFilter must have set it")
        assertThat(count("failure")).isEqualTo(1.0)
        assertThat(infos()).containsExactly("registration/start entry: usernamePresent=true displayNameLen=4")
    }

    @Test
    fun tenantNotFound_illegalState() {
        TenantContextHolder.set(TENANT)
        whenever(tenants.findById(TENANT)).thenReturn(Optional.empty())

        assertThatThrownBy { svc().start(RegistrationStartRequest("CQk", "Disp", "alice")) }
            .isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("tenant $TENANT not found")
    }

    @Test
    fun suspended_message_beforeUserHandleValidation() {
        TenantContextHolder.set(TENANT)
        whenever(tenant.isSuspended).thenReturn(true)

        assertThatThrownBy { svc().start(RegistrationStartRequest(null, null, null)) }
            .isExactlyInstanceOf(BusinessException::class.java)
            .hasMessage("tenant suspended: $TENANT")
    }

    @Test
    fun nullUserHandle_invalidInput_failureMetric() {
        TenantContextHolder.set(TENANT)

        assertThatThrownBy { svc().start(RegistrationStartRequest(null, "Disp", "alice")) }
            .isExactlyInstanceOf(BusinessException::class.java)
            .hasMessage("userHandle must be valid base64url")
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.INVALID_INPUT)
        assertThat(count("failure")).isEqualTo(1.0)
        verifyNoInteractions(challenges, store)
    }

    @Test
    fun invalidBase64_messageAndNoCause() {
        TenantContextHolder.set(TENANT)

        assertThatThrownBy { svc().start(RegistrationStartRequest("***", "Disp", "alice")) }
            .isExactlyInstanceOf(BusinessException::class.java)
            .hasMessage("userHandle must be valid base64url")
            .hasNoCause()
    }

    @Test
    fun tooLong_messageCarriesLength() {
        TenantContextHolder.set(TENANT)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(65))

        assertThatThrownBy { svc().start(RegistrationStartRequest(b64, "Disp", "alice")) }
            .isExactlyInstanceOf(BusinessException::class.java)
            .hasMessage("userHandle must decode to 1..64 bytes (WebAuthn user.id cap), was 65")
    }

    @Test
    fun success_optionsBytes_storedChallenge_logs_event() {
        TenantContextHolder.set(TENANT)
        whenever(credentials.findCredentialIdsByUserHandle(anyOrNull())).thenReturn(listOf(byteArrayOf(4)))

        val resp = svc().start(RegistrationStartRequest("CQk", "Disp", "alice"))

        assertThat(resp.registrationToken).isEqualTo("tok_abcdefghijkl")
        assertThat(resp.publicKeyCredentialCreationOptions.toString()).isEqualTo(
            """{"challenge":"AQID","rp":{"id":"example.com","name":"Example"},""" +
                """"user":{"id":"CQk","displayName":"Disp","name":"alice"},""" +
                """"pubKeyCredParams":[{"type":"public-key","alg":-7},{"type":"public-key","alg":-257}],""" +
                """"timeout":60000,"attestation":"direct",""" +
                """"excludeCredentials":[{"type":"public-key","id":"BA"}],""" +
                """"authenticatorSelection":{"userVerification":"required","residentKey":"preferred"}}""",
        )

        val cap = argumentCaptor<RegistrationChallenge>()
        verify(store).putRegistration(eq("tok_abcdefghijkl"), cap.capture())
        val ch = cap.lastValue
        assertThat(ch.tenantId).isEqualTo(TENANT.toString())
        assertThat(ch.challenge).containsExactly(1, 2, 3)
        assertThat(ch.userHandle).containsExactly(9, 9)
        assertThat(ch.displayName).isEqualTo("Disp")
        assertThat(ch.username).isEqualTo("alice")
        assertThat(ch.issuedAt).isEqualTo(NOW)

        assertThat(infos()).containsExactly(
            "registration/start entry: usernamePresent=true displayNameLen=4",
            "registration/start issued: tokenTail=efghijkl timeoutMs=60000",
        )
        verify(ceremonyEvents).recordAfterCommit(TENANT, CeremonyAction.REGISTRATION_BEGIN)
        assertThat(count("success")).isEqualTo(1.0)
        assertThat(count("failure")).isEqualTo(0.0)
    }

    @Test
    fun nullDisplayNameAndUsername_acceptedByService_emittedAsJsonNull() {
        // 서비스 직접 호출(컨트롤러 @Valid 우회) 시 null displayName/username 을 그대로 수용한다.
        TenantContextHolder.set(TENANT)
        whenever(tenant.rpName).thenReturn(null)
        whenever(tenant.isRequireUserVerification).thenReturn(false)
        whenever(credentials.findCredentialIdsByUserHandle(anyOrNull())).thenReturn(listOf())
        whenever(challenges.newToken()).thenReturn("tok8char")

        val resp = svc().start(RegistrationStartRequest("CQk", null, null))

        val options = resp.publicKeyCredentialCreationOptions
        assertThat(options.get("rp").toString()).isEqualTo("""{"id":"example.com","name":null}""")
        assertThat(options.get("user").toString()).isEqualTo("""{"id":"CQk","displayName":null,"name":null}""")
        assertThat(options.get("authenticatorSelection").get("userVerification").asString()).isEqualTo("preferred")
        val cap = argumentCaptor<RegistrationChallenge>()
        verify(store).putRegistration(eq("tok8char"), cap.capture())
        assertThat(cap.lastValue.displayName).isNull()
        assertThat(cap.lastValue.username).isNull()
        assertThat(infos()).containsExactly(
            "registration/start entry: usernamePresent=false displayNameLen=0",
            "registration/start issued: tokenTail=*** timeoutMs=60000",
        )
    }
}
