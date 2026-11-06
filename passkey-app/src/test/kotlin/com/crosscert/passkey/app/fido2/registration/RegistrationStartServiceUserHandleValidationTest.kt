package com.crosscert.passkey.app.fido2.registration

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest
import com.crosscert.passkey.app.fido2.CeremonyMetrics
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
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
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.UUID

/**
 * G18 — userHandle 은 WebAuthn 스펙상 user.id 상한(1..64 바이트)을 넘으면 안 된다.
 * 기존에는 이 검증이 전혀 없어 65바이트+ userHandle 도 /registration/start 를
 * 통과했고, Oracle credential.user_handle RAW(64) 컬럼에 저장 시(finish 단계)
 * ORA-12899 → DataIntegrityViolationException → catch-all 500 으로 노출됐다.
 *
 * 이 테스트는 start() 진입부에서 base64url 디코드 직후 길이 위반을
 * fail-fast BusinessException(400, INVALID_INPUT)으로 거부하는지 검증한다.
 */
class RegistrationStartServiceUserHandleValidationTest {

    private val tenants: TenantRepository = mock()
    private val credentials: CredentialRepository = mock()
    private val challenges: ChallengeIssuer = mock()
    private val store: ChallengeStore = mock()
    private val mapper = ObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), KstTime.ZONE)

    @AfterEach
    fun clear() {
        TenantContextHolder.clear()
    }

    private fun newService() = RegistrationStartService(
        tenants, credentials, challenges, store, mapper, clock,
        CeremonyMetrics(SimpleMeterRegistry()), mock<CeremonyEventRecorder>(),
    )

    private fun stubTenant(tenantId: UUID) {
        TenantContextHolder.set(tenantId)
        val t: Tenant = mock()
        whenever(t.rpId).thenReturn("example.com")
        whenever(t.rpName).thenReturn("Example")
        whenever(tenants.findById(tenantId)).thenReturn(Optional.of(t))
        whenever(challenges.newChallengeBytes()).thenReturn(ByteArray(32))
        whenever(challenges.newToken()).thenReturn("tok_test_token_value")
    }

    @Test
    fun start_rejects65ByteUserHandle_withInvalidInput() {
        val tenantId = UUID.randomUUID()
        stubTenant(tenantId)
        val oversized = ByteArray(65) // WebAuthn user.id cap is 64 bytes
        val userHandleB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(oversized)
        val req = RegistrationStartRequest(userHandleB64, "Disp", "alice")

        assertThatThrownBy { newService().start(req) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.INVALID_INPUT)
    }

    @Test
    fun start_rejectsEmptyUserHandle_withInvalidInput() {
        val tenantId = UUID.randomUUID()
        stubTenant(tenantId)
        val req = RegistrationStartRequest("", "Disp", "alice")

        assertThatThrownBy { newService().start(req) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.INVALID_INPUT)
    }

    @Test
    fun start_rejectsNonBase64urlUserHandle_withInvalidInput() {
        val tenantId = UUID.randomUUID()
        stubTenant(tenantId)
        // '+' and '/' are not part of the base64url alphabet
        val req = RegistrationStartRequest("abc+def/", "Disp", "alice")

        assertThatThrownBy { newService().start(req) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.INVALID_INPUT)
    }

    @Test
    fun start_rejectsWhitespaceUserHandle_withInvalidInput() {
        val tenantId = UUID.randomUUID()
        stubTenant(tenantId)
        val req = RegistrationStartRequest("abc def!", "Disp", "alice")

        assertThatThrownBy { newService().start(req) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.INVALID_INPUT)
    }

    /**
     * Regression guard: RFC 4648 §5 base64url permits padding ('='), and the JDK's
     * [Base64.Decoder] accepts padded input just fine. A prior fix introduced a
     * charset pre-check that rejected '=' outright, which broke any RP client using a
     * standard padded URL-safe encoder (e.g. `Base64.getUrlEncoder()` without
     * `withoutPadding()`) — a regression against "normal input keeps working".
     * This test must pass both before and after the pre-check removal; it existed as a
     * FAILING case against the pre-check implementation, proving the regression, and now
     * passes against the decoder-exception-based implementation.
     */
    @Test
    fun start_acceptsPadded64ByteUserHandle_standardUrlEncoder() {
        val tenantId = UUID.randomUUID()
        stubTenant(tenantId)
        val atMax = ByteArray(64)
        val userHandleB64 = Base64.getUrlEncoder().encodeToString(atMax) // padded, e.g. ends with "=="
        assertThat(userHandleB64).endsWith("=")
        whenever(credentials.findCredentialIdsByUserHandle(atMax)).thenReturn(listOf())
        val req = RegistrationStartRequest(userHandleB64, "Disp", "alice")

        val resp = newService().start(req)

        assertThat(resp).isNotNull()
    }

    @Test
    fun start_rejectsPadded65ByteUserHandle_withInvalidInput() {
        val tenantId = UUID.randomUUID()
        stubTenant(tenantId)
        val oversized = ByteArray(65)
        val userHandleB64 = Base64.getUrlEncoder().encodeToString(oversized) // padded

        val req = RegistrationStartRequest(userHandleB64, "Disp", "alice")

        assertThatThrownBy { newService().start(req) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.INVALID_INPUT)
    }

    @Test
    fun start_accepts64ByteUserHandle_atSpecMax() {
        val tenantId = UUID.randomUUID()
        stubTenant(tenantId)
        val atMax = ByteArray(64)
        val userHandleB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(atMax)
        whenever(credentials.findCredentialIdsByUserHandle(atMax)).thenReturn(listOf())
        val req = RegistrationStartRequest(userHandleB64, "Disp", "alice")

        val resp = newService().start(req)

        assertThat(resp).isNotNull()
    }

    @Test
    fun start_accepts1ByteUserHandle_atSpecMin() {
        val tenantId = UUID.randomUUID()
        stubTenant(tenantId)
        val atMin = byteArrayOf(7)
        val userHandleB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(atMin)
        whenever(credentials.findCredentialIdsByUserHandle(atMin)).thenReturn(listOf())
        val req = RegistrationStartRequest(userHandleB64, "Disp", "alice")

        val resp = newService().start(req)

        assertThat(resp).isNotNull()
    }
}
