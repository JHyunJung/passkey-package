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
import com.crosscert.passkey.core.policy.AaguidPolicyChecker
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

private val NOW = Instant.parse("2026-05-29T00:00:00Z")

/**
 * F19 방어심화: /registration/finish 가 challenge.issuedAt 을 앱-레벨에서 검증해
 * Redis TTL 만료를 이중으로 막는지 단위 검증.
 *
 * 가드는 store.takeRegistration() 직후 실행되므로 ChallengeStore 와
 * CeremonyMetrics 만 모킹하면 된다. Clock 을 고정해 issuedAt 의 신선/만료를
 * 결정적으로 통제한다.
 */
class RegistrationFinishServiceFreshnessTest {

    private val store: ChallengeStore = mock()
    private val clock = Clock.fixed(NOW, KstTime.ZONE)

    @AfterEach
    fun clear() {
        TenantContextHolder.clear()
    }

    private fun newService() = RegistrationFinishService(
        store,
        mock<WebAuthnVerifier>(),
        mock<TenantRepository>(),
        mock<CredentialRepository>(),
        mock<MdsVerifier>(),
        mock<AaguidPolicyChecker>(),
        ObjectMapper(),
        clock,
        CeremonyMetrics(SimpleMeterRegistry()),
        mock<CeremonyEventRecorder>(),
    )

    private fun challengeIssuedAt(issuedAt: Instant) = RegistrationChallenge(
        UUID.randomUUID().toString(), ByteArray(32), byteArrayOf(9, 9),
        "Alice", "alice@example", issuedAt,
    )

    @Test
    fun finish_staleChallenge_rejectedAsChallengeInvalid() {
        // issuedAt 이 TTL(5분)보다 1분 더 과거 → 앱-레벨 freshness 위반
        val stale = NOW.minus(ChallengeStore.TTL).minusSeconds(60)
        whenever(store.takeRegistration("tok"))
            .thenReturn(Optional.of(challengeIssuedAt(stale)))

        val svc = newService()
        val req = RegistrationFinishRequest("tok", null)

        assertThatThrownBy { svc.finish(req) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.CHALLENGE_INVALID)
    }

    @Test
    fun finish_freshChallenge_passesFreshnessGuard() {
        // issuedAt = now → freshness 가드 통과. 가드 이후의 tenant-mismatch
        // (컨텍스트 미설정)에서 IllegalArgumentException 으로 멈추는 것이
        // 가드를 통과했다는 증거. CHALLENGE_INVALID 가 아니어야 한다.
        whenever(store.takeRegistration("tok"))
            .thenReturn(Optional.of(challengeIssuedAt(NOW)))

        val svc = newService()
        val req = RegistrationFinishRequest("tok", null)

        assertThatThrownBy { svc.finish(req) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tenant mismatch")
    }
}
