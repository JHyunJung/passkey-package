package com.crosscert.passkey.app.fido2.registration

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest
import com.crosscert.passkey.app.fido2.CeremonyMetrics
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge
import com.crosscert.passkey.app.fido2.mds.MdsVerifier
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.policy.AaguidPolicyChecker
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import com.crosscert.passkey.webauthn.verifier.RegistrationResult
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

private val NOW = Instant.parse("2026-05-29T00:00:00Z")

/**
 * G01 — 중복 credentialId 등록이 unique 제약 위반(DataIntegrityViolationException)으로
 * 500 노출되던 것을, saveAndFlush 를 감싸 409(CREDENTIAL_DUPLICATE)로 매핑하는지 검증한다.
 *
 * tenant/challenge freshness/tenant-match/attestation/format/MDS/AAGUID 검사는
 * 모두 통과시킨 뒤 credentials.saveAndFlush 에서만 실패하도록 모킹해, 실제 저장
 * 단계의 unique violation 을 재현한다.
 */
class RegistrationFinishServiceDuplicateCredentialTest {

    private val store: ChallengeStore = mock()
    private val verifier: WebAuthnVerifier = mock()
    private val tenants: TenantRepository = mock()
    private val credentials: CredentialRepository = mock()
    private val mds: MdsVerifier = mock()
    private val aaguidPolicyChecker: AaguidPolicyChecker = mock()
    private val clock = Clock.fixed(NOW, KstTime.ZONE)

    @AfterEach
    fun clear() {
        TenantContextHolder.clear()
    }

    private fun newService() = RegistrationFinishService(
        store, verifier, tenants, credentials, mds, aaguidPolicyChecker,
        ObjectMapper(), clock,
        CeremonyMetrics(SimpleMeterRegistry()), mock<CeremonyEventRecorder>(),
    )

    @Test
    fun finish_duplicateCredentialId_mappedToConflict() {
        val tenantId = UUID.randomUUID()
        TenantContextHolder.set(tenantId)

        val ch = RegistrationChallenge(
            tenantId.toString(), ByteArray(32), byteArrayOf(9, 9),
            "Alice", "alice", NOW,
        )
        whenever(store.takeRegistration("tok")).thenReturn(Optional.of(ch))

        val tenant: Tenant = mock()
        whenever(tenant.id).thenReturn(tenantId)
        whenever(tenant.allowedOriginValues).thenReturn(listOf("https://example.com"))
        whenever(tenant.rpId).thenReturn("example.com")
        whenever(tenant.isRequireUserVerification).thenReturn(false)
        whenever(tenant.acceptedFormatValues).thenReturn(setOf("packed", "none"))
        whenever(tenant.isMdsRequired).thenReturn(false)
        whenever(tenants.findById(tenantId)).thenReturn(Optional.of(tenant))

        val credentialId = byteArrayOf(1, 2, 3, 4)
        val result = RegistrationResult(
            credentialId, byteArrayOf(5, 6), 0L, null, "none",
            setOf(), false, true, false, false,
        )
        whenever(verifier.verifyRegistration(anyOrNull())).thenReturn(result)
        whenever(mds.verify(false, null)).thenReturn(true)

        whenever(credentials.saveAndFlush(any<Credential>()))
            .thenThrow(DataIntegrityViolationException("unique constraint (UX_CREDENTIAL_ID) violated"))

        val svc = newService()
        val req = RegistrationFinishRequest("tok", null)

        assertThatThrownBy { svc.finish(req) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.CREDENTIAL_DUPLICATE)
    }
}
