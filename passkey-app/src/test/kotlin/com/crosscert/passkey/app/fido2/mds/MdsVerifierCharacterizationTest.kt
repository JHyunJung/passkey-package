package com.crosscert.passkey.app.fido2.mds

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.crosscert.passkey.core.mds.MdsAaguidCache
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import java.util.Optional

private val AAGUID = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
private const val AAGUID_STR = "01020304-0506-0708-090a-0b0c0d0e0f10"

/**
 * Kotlin 전환 전 MdsVerifier 의 외부 관찰 가능 동작(null/비정상 AAGUID 처리, 빈 status 목록,
 * 마지막 status 만 판정, 로그 문구)을 현재 Java 구현 기준으로 고정하는 특성 테스트.
 */
class MdsVerifierCharacterizationTest {

    private val cache: MdsAaguidCache = mock()
    private val verifier = MdsVerifier(cache)

    private val logger = LoggerFactory.getLogger(MdsVerifier::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.DEBUG
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        logger.level = null
    }

    private fun warns(): List<String> =
        appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    @Test
    fun notRequired_nullAaguid_trueWithoutCacheLookup() {
        assertThat(verifier.verify(false, null)).isTrue()
        verifyNoInteractions(cache)
    }

    @Test
    fun required_absentEntry_logsCanonicalAaguid() {
        whenever(cache.lookup(anyOrNull())).thenReturn(Optional.empty())
        assertThat(verifier.verify(true, AAGUID)).isFalse()
        assertThat(warns()).containsExactly("MDS entry absent for aaguid $AAGUID_STR — fail-closed")
    }

    @Test
    fun required_nullAaguid_absentEntry_canonicalizationThrowsIllegalArgument() {
        // 캐시가 null 을 받아 empty 를 돌려줘도, 로그용 canonicalAaguid(null) 이 IAE 를 던진다.
        whenever(cache.lookup(anyOrNull())).thenReturn(Optional.empty())
        assertThatThrownBy { verifier.verify(true, null) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("aaguid must be 16 bytes")
    }

    @Test
    fun required_emptyStatuses_falseAndLogs() {
        whenever(cache.lookup(anyOrNull())).thenReturn(Optional.of(MdsAaguidCache.Entry(listOf())))
        assertThat(verifier.verify(true, AAGUID)).isFalse()
        assertThat(warns()).containsExactly(
            "MDS entry has empty status list for aaguid $AAGUID_STR — fail-closed",
        )
    }

    @Test
    fun required_onlyLastStatusCounts_recertifiedPasses() {
        whenever(cache.lookup(anyOrNull())).thenReturn(
            Optional.of(MdsAaguidCache.Entry(listOf("REVOKED", "FIDO_CERTIFIED"))),
        )
        assertThat(verifier.verify(true, AAGUID)).isTrue()
        assertThat(warns()).isEmpty()
    }

    @Test
    fun required_blockingLastStatus_logsStatus() {
        whenever(cache.lookup(anyOrNull())).thenReturn(
            Optional.of(MdsAaguidCache.Entry(listOf("FIDO_CERTIFIED", "USER_KEY_REMOTE_COMPROMISE"))),
        )
        assertThat(verifier.verify(true, AAGUID)).isFalse()
        assertThat(warns()).containsExactly(
            "MDS blocking status USER_KEY_REMOTE_COMPROMISE for aaguid $AAGUID_STR",
        )
    }

    @Test
    fun required_nonBlockingUnknownStatus_passes() {
        whenever(cache.lookup(anyOrNull())).thenReturn(
            Optional.of(MdsAaguidCache.Entry(listOf("UPDATE_AVAILABLE"))),
        )
        assertThat(verifier.verify(true, AAGUID)).isTrue()
    }
}
