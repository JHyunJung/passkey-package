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

class RegistrationStartServiceExcludeTest {

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
    fun start_includesExcludeCredentials_whenUserHasExistingCredential() {
        val tenantId = UUID.randomUUID()
        TenantContextHolder.set(tenantId)
        val t: Tenant = mock()
        whenever(t.rpId).thenReturn("example.com")
        whenever(t.rpName).thenReturn("Example")
        whenever(tenants.findById(tenantId)).thenReturn(Optional.of(t))
        whenever(challenges.newChallengeBytes()).thenReturn(ByteArray(32))
        whenever(challenges.newToken()).thenReturn("tok_test_token_value")

        val existing = byteArrayOf(1, 2, 3, 4)
        val userHandle = byteArrayOf(9, 9)
        // eq(userHandle): start() must hand the repo the *decoded* userHandle bytes,
        // not the base64url string — eq() catches a regression that passes the wrong bytes.
        whenever(credentials.findCredentialIdsByUserHandle(eq(userHandle))).thenReturn(listOf(existing))

        // Named registry so we can assert the *production* counter name/tags fire
        // on the success path — guards against tag-string drift in CeremonyMetrics.
        val reg = SimpleMeterRegistry()
        val svc = RegistrationStartService(
            tenants, credentials, challenges, store, mapper, clock,
            CeremonyMetrics(reg), mock<CeremonyEventRecorder>(),
        )
        val req = RegistrationStartRequest(
            Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle),
            "Disp", "alice",
        )

        val resp = svc.start(req)

        verify(credentials).findCredentialIdsByUserHandle(eq(userHandle))
        val exclude = resp.publicKeyCredentialCreationOptions.get("excludeCredentials")
        assertThat(exclude).isNotNull()
        assertThat(exclude.isArray).isTrue()
        assertThat(exclude).hasSize(1)
        assertThat(exclude.get(0).get("type").asString()).isEqualTo("public-key")
        val expectedId = Base64.getUrlEncoder().withoutPadding().encodeToString(existing)
        assertThat(exclude.get(0).get("id").asString()).isEqualTo(expectedId)

        val success = checkNotNull(
            reg.find("passkey_ceremony_total")
                .tags("type", "registration", "phase", "start", "result", "success")
                .counter(),
        ).count()
        assertThat(success).isEqualTo(1.0)
    }

    @Test
    fun start_includesAllExcludeCredentials_whenUserHasMultipleCredentials() {
        val tenantId = UUID.randomUUID()
        TenantContextHolder.set(tenantId)
        val t: Tenant = mock()
        whenever(t.rpId).thenReturn("example.com")
        whenever(t.rpName).thenReturn("Example")
        whenever(tenants.findById(tenantId)).thenReturn(Optional.of(t))
        whenever(challenges.newChallengeBytes()).thenReturn(ByteArray(32))
        whenever(challenges.newToken()).thenReturn("tok_test_token_value")

        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(5, 6, 7, 8, 9)
        val userHandle = byteArrayOf(9, 9)
        whenever(credentials.findCredentialIdsByUserHandle(eq(userHandle)))
            .thenReturn(listOf(first, second))

        val svc = RegistrationStartService(
            tenants, credentials, challenges, store, mapper, clock,
            CeremonyMetrics(SimpleMeterRegistry()), mock<CeremonyEventRecorder>(),
        )
        val req = RegistrationStartRequest(
            Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle),
            "Disp", "alice",
        )

        val resp = svc.start(req)

        verify(credentials).findCredentialIdsByUserHandle(eq(userHandle))
        val exclude = resp.publicKeyCredentialCreationOptions.get("excludeCredentials")
        assertThat(exclude).isNotNull()
        assertThat(exclude.isArray).isTrue()
        assertThat(exclude).hasSize(2)
        val enc = Base64.getUrlEncoder().withoutPadding()
        assertThat(exclude.get(0).get("type").asString()).isEqualTo("public-key")
        assertThat(exclude.get(0).get("id").asString()).isEqualTo(enc.encodeToString(first))
        assertThat(exclude.get(1).get("type").asString()).isEqualTo("public-key")
        assertThat(exclude.get(1).get("id").asString()).isEqualTo(enc.encodeToString(second))
    }

    @Test
    fun start_emptyExcludeCredentials_whenNoExisting() {
        val tenantId = UUID.randomUUID()
        TenantContextHolder.set(tenantId)
        val t: Tenant = mock()
        whenever(t.rpId).thenReturn("example.com")
        whenever(t.rpName).thenReturn("Example")
        whenever(tenants.findById(tenantId)).thenReturn(Optional.of(t))
        whenever(challenges.newChallengeBytes()).thenReturn(ByteArray(32))
        whenever(challenges.newToken()).thenReturn("tok_test_token_value")
        val userHandle = byteArrayOf(9, 9)
        whenever(credentials.findCredentialIdsByUserHandle(eq(userHandle))).thenReturn(listOf())

        val svc = RegistrationStartService(
            tenants, credentials, challenges, store, mapper, clock,
            CeremonyMetrics(SimpleMeterRegistry()), mock<CeremonyEventRecorder>(),
        )
        val req = RegistrationStartRequest(
            Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle),
            "Disp", "alice",
        )

        val resp = svc.start(req)
        verify(credentials).findCredentialIdsByUserHandle(eq(userHandle))
        val exclude = resp.publicKeyCredentialCreationOptions.get("excludeCredentials")
        assertThat(exclude).isNotNull()
        assertThat(exclude.isArray).isTrue()
        assertThat(exclude).isEmpty()
    }

    @Test
    fun start_rejectsSuspendedTenant_withTenantSuspendedError() {
        val tenantId = UUID.randomUUID()
        TenantContextHolder.set(tenantId)
        val t: Tenant = mock()
        whenever(t.isSuspended).thenReturn(true)
        whenever(tenants.findById(tenantId)).thenReturn(Optional.of(t))

        // Named registry to assert the failure-path counter (result=failure)
        // fires with the production tags when the service throws.
        val reg = SimpleMeterRegistry()
        val svc = RegistrationStartService(
            tenants, credentials, challenges, store, mapper, clock,
            CeremonyMetrics(reg), mock<CeremonyEventRecorder>(),
        )
        val req = RegistrationStartRequest(
            Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(9, 9)),
            "Disp", "alice",
        )

        assertThatThrownBy { svc.start(req) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.TENANT_SUSPENDED)

        val failure = checkNotNull(
            reg.find("passkey_ceremony_total")
                .tags("type", "registration", "phase", "start", "result", "failure")
                .counter(),
        ).count()
        assertThat(failure).isEqualTo(1.0)
    }
}
