package com.crosscert.passkey.app.fido2.authentication

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartRequest
import com.crosscert.passkey.app.fido2.CeremonyMetrics
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
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
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.UUID

class AuthenticationStartServiceSuspendTest {

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

    @Test
    fun start_rejectsSuspendedTenant_withTenantSuspendedError() {
        val tenantId = UUID.randomUUID()
        TenantContextHolder.set(tenantId)
        val t: Tenant = mock()
        whenever(t.isSuspended).thenReturn(true)
        whenever(tenants.findById(tenantId)).thenReturn(Optional.of(t))

        val svc = AuthenticationStartService(
            tenants, credentials, challenges, store, mapper, clock,
            CeremonyMetrics(SimpleMeterRegistry()), mock<CeremonyEventRecorder>(),
        )
        val req = AuthenticationStartRequest(null)

        assertThatThrownBy { svc.start(req) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.TENANT_SUSPENDED)
    }

    @Test
    fun start_emits_tenant_timeout_and_preferred_uv_when_uv_not_required() {
        val tenantId = UUID.randomUUID()
        TenantContextHolder.set(tenantId)
        val t: Tenant = mock()
        whenever(t.isSuspended).thenReturn(false)
        whenever(t.rpId).thenReturn("example.com")
        whenever(t.webauthnTimeoutMs).thenReturn(90000)
        whenever(t.isRequireUserVerification).thenReturn(false)
        whenever(tenants.findById(tenantId)).thenReturn(Optional.of(t))
        whenever(challenges.newChallengeBytes()).thenReturn(ByteArray(32))
        whenever(challenges.newToken()).thenReturn("tok_test_token_value")

        val userHandle = byteArrayOf(9, 9)
        val credId = byteArrayOf(1, 2, 3, 4)
        val cred: Credential = mock()
        whenever(cred.credentialId).thenReturn(credId)
        // eq(userHandle): start() must hand the repo the *decoded* userHandle bytes.
        whenever(credentials.findByUserHandle(eq(userHandle))).thenReturn(listOf(cred))

        val svc = AuthenticationStartService(
            tenants, credentials, challenges, store, mapper, clock,
            CeremonyMetrics(SimpleMeterRegistry()), mock<CeremonyEventRecorder>(),
        )
        val req = AuthenticationStartRequest(Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle))

        val resp = svc.start(req)

        verify(credentials).findByUserHandle(eq(userHandle))
        val options = resp.publicKeyCredentialRequestOptions
        assertThat(options.get("timeout").asInt()).isEqualTo(90000)
        assertThat(options.get("userVerification").asString()).isEqualTo("preferred")
        val allow = options.get("allowCredentials")
        assertThat(allow).isNotNull()
        assertThat(allow.isArray).isTrue()
        assertThat(allow).hasSize(1)
        assertThat(allow.get(0).get("type").asString()).isEqualTo("public-key")
        val expectedId = Base64.getUrlEncoder().withoutPadding().encodeToString(credId)
        assertThat(allow.get(0).get("id").asString()).isEqualTo(expectedId)
    }

    @Test
    fun start_emits_required_uv_when_tenant_requires_it() {
        val tenantId = UUID.randomUUID()
        TenantContextHolder.set(tenantId)
        val t: Tenant = mock()
        whenever(t.isSuspended).thenReturn(false)
        whenever(t.rpId).thenReturn("example.com")
        whenever(t.webauthnTimeoutMs).thenReturn(90000)
        whenever(t.isRequireUserVerification).thenReturn(true)
        whenever(tenants.findById(tenantId)).thenReturn(Optional.of(t))
        whenever(challenges.newChallengeBytes()).thenReturn(ByteArray(32))
        whenever(challenges.newToken()).thenReturn("tok_test_token_value")

        val userHandle = byteArrayOf(9, 9)
        val credId = byteArrayOf(1, 2, 3, 4)
        val cred: Credential = mock()
        whenever(cred.credentialId).thenReturn(credId)
        whenever(credentials.findByUserHandle(eq(userHandle))).thenReturn(listOf(cred))

        val svc = AuthenticationStartService(
            tenants, credentials, challenges, store, mapper, clock,
            CeremonyMetrics(SimpleMeterRegistry()), mock<CeremonyEventRecorder>(),
        )
        val req = AuthenticationStartRequest(Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle))

        val resp = svc.start(req)

        val options = resp.publicKeyCredentialRequestOptions
        assertThat(options.get("timeout").asInt()).isEqualTo(90000)
        assertThat(options.get("userVerification").asString()).isEqualTo("required")
    }
}
