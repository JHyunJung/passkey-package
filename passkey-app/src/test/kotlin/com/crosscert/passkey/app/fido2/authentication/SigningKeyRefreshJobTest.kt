package com.crosscert.passkey.app.fido2.authentication

import com.crosscert.passkey.core.jwt.SigningKeyProvider
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.scheduling.annotation.Scheduled

/**
 * [SigningKeyRefreshJob] 이 provider 예외를 삼켜 스케줄러를 멈추지 않는지, tick 마다 한 번만 확인하는지,
 * 설정 키로 스케줄되는지 확인한다.
 */
class SigningKeyRefreshJobTest {

    private val keys: SigningKeyProvider = mock()
    private val job = SigningKeyRefreshJob(keys, "PT1M")

    @Test
    fun runOnce_providerThrows_doesNotPropagate() {
        whenever(keys.refreshIfRotated()).thenThrow(IllegalStateException("boom"))

        assertThatCode { job.runOnce() }.doesNotThrowAnyException()
    }

    @Test
    fun runOnce_callsProviderOnce() {
        // 바뀐 경우: 성공 로그가 새 키의 kid 를 읽는다(signingKey 를 스텁하지 않으면 catch 로 빠진다).
        whenever(keys.refreshIfRotated()).thenReturn(true)
        whenever(keys.signingKey()).thenReturn(RSAKeyGenerator(2048).keyID("new-kid").generate())
        job.runOnce()
        verify(keys, times(1)).refreshIfRotated()
        verify(keys, times(1)).signingKey()

        val unchanged: SigningKeyProvider = mock()
        whenever(unchanged.refreshIfRotated()).thenReturn(false)
        SigningKeyRefreshJob(unchanged, "PT1M").runOnce()
        verify(unchanged, times(1)).refreshIfRotated()
        verify(unchanged, never()).signingKey()
    }

    @Test
    fun runOnce_isScheduledWithConfiguredKeys() {
        val scheduled = SigningKeyRefreshJob::class.java.getMethod("runOnce").getAnnotation(Scheduled::class.java)

        assertThat(scheduled).isNotNull
        assertThat(scheduled.fixedDelayString).isEqualTo("\${passkey.signing-key.refresh.fixed-delay:PT1M}")
        assertThat(scheduled.initialDelayString).isEqualTo("\${passkey.signing-key.refresh.initial-delay:PT1M}")
    }
}
