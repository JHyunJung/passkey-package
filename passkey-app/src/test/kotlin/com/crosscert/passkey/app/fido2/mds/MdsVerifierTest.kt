package com.crosscert.passkey.app.fido2.mds

import com.crosscert.passkey.core.mds.MdsAaguidCache
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Optional

class MdsVerifierTest {

    private val cache: MdsAaguidCache = mock()
    private val verifier = MdsVerifier(cache)
    private val aaguid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)

    @Test
    fun mdsNotRequiredAlwaysPasses() {
        assertThat(verifier.verify(false, aaguid)).isTrue()
    }

    @Test
    fun mdsRequiredAndEntryAcceptableReturnsTrue() {
        whenever(cache.lookup(anyOrNull())).thenReturn(
            Optional.of(MdsAaguidCache.Entry(listOf("FIDO_CERTIFIED_L1"))),
        )
        assertThat(verifier.verify(true, aaguid)).isTrue()
    }

    @Test
    fun mdsRequiredAndEntryBlockedReturnsFalse() {
        whenever(cache.lookup(anyOrNull())).thenReturn(
            Optional.of(MdsAaguidCache.Entry(listOf("FIDO_CERTIFIED_L1", "REVOKED"))),
        )
        assertThat(verifier.verify(true, aaguid)).isFalse()
    }

    @Test
    fun mdsRequiredAndEntryAbsentReturnsFalse() {
        whenever(cache.lookup(anyOrNull())).thenReturn(Optional.empty())
        assertThat(verifier.verify(true, aaguid)).isFalse()
    }

    @Test
    fun compromiseStatusesAreBlocked() {
        for (blocked in listOf(
            "REVOKED", "USER_VERIFICATION_BYPASS",
            "ATTESTATION_KEY_COMPROMISE",
            "USER_KEY_REMOTE_COMPROMISE",
            "USER_KEY_PHYSICAL_COMPROMISE",
        )) {
            whenever(cache.lookup(anyOrNull())).thenReturn(
                Optional.of(MdsAaguidCache.Entry(listOf("FIDO_CERTIFIED_L1", blocked))),
            )
            assertThat(verifier.verify(true, aaguid))
                .`as`("status %s must be blocked", blocked)
                .isFalse()
        }
    }
}
