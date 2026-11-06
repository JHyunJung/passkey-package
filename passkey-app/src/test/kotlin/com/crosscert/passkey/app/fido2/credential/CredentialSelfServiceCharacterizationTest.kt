package com.crosscert.passkey.app.fido2.credential

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.crosscert.passkey.app.api.v1.rp.dto.CredentialView
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.repository.CredentialRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional

/**
 * Kotlin 전환 전 CredentialSelfService 의 외부 관찰 가능 동작(뷰 매핑, null AAGUID, 미소유 예외 메시지,
 * 삭제 로그 문구/마스킹, 잠금 조회 사용)을 현재 Java 구현 기준으로 고정하는 특성 테스트.
 */
class CredentialSelfServiceCharacterizationTest {

    private val creds: CredentialRepository = mock()
    private val svc = CredentialSelfService(creds)

    private val logger = LoggerFactory.getLogger(CredentialSelfService::class.java) as Logger
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
    fun list_mapsAllFields_inRepoOrder_nullAaguidAndLabel() {
        val used = OffsetDateTime.of(2026, 11, 6, 9, 0, 0, 0, ZoneOffset.ofHours(9))
        val a: Credential = mock()
        whenever(a.credentialId).thenReturn(byteArrayOf(1, 2, 3, 4))
        whenever(a.label).thenReturn("Mac")
        whenever(a.aaguid).thenReturn(byteArrayOf(0xab.toByte(), 0x01))
        whenever(a.lastUsedAt).thenReturn(used)
        val b: Credential = mock()
        whenever(b.credentialId).thenReturn(byteArrayOf(5))
        whenever(creds.findByUserHandle(anyOrNull())).thenReturn(listOf(a, b))

        val views = svc.list(byteArrayOf(9))

        assertThat(views).containsExactly(
            CredentialView("AQIDBA", "Mac", "ab01", used),
            CredentialView("BQ", null, null, null),
        )
    }

    @Test
    fun list_empty() {
        whenever(creds.findByUserHandle(anyOrNull())).thenReturn(listOf())
        assertThat(svc.list(byteArrayOf(9))).isEmpty()
    }

    @Test
    fun notOwned_message() {
        whenever(creds.findOwnedForUpdate(anyOrNull(), anyOrNull())).thenReturn(Optional.empty())
        assertThatThrownBy { svc.rename(byteArrayOf(1), byteArrayOf(2), "x") }
            .isExactlyInstanceOf(BusinessException::class.java)
            .hasMessage("credential not found or not owned")
        assertThatThrownBy { svc.delete(byteArrayOf(1), byteArrayOf(2)) }
            .isExactlyInstanceOf(BusinessException::class.java)
            .hasMessage("credential not found or not owned")
        verify(creds, never()).delete(anyOrNull<Credential>())
        assertThat(warns()).isEmpty()
    }

    @Test
    fun rename_usesLockedOwnershipLookup_acceptsNullLabel() {
        val uh = byteArrayOf(1)
        val cid = byteArrayOf(2)
        val c: Credential = mock()
        whenever(creds.findOwnedForUpdate(cid, uh)).thenReturn(Optional.of(c))

        svc.rename(uh, cid, null)

        verify(creds).findOwnedForUpdate(cid, uh)
        verify(c).label = null
        verify(creds, never()).save(anyOrNull<Credential>())
    }

    @Test
    fun delete_logsMaskedTail_longId() {
        val cid = ByteArray(20) { (it + 1).toByte() }
        val c: Credential = mock()
        whenever(creds.findOwnedForUpdate(anyOrNull(), anyOrNull())).thenReturn(Optional.of(c))

        svc.delete(byteArrayOf(1), cid)

        verify(creds).delete(c)
        assertThat(warns()).containsExactly(
            "self-service credential deleted: id=...MDQ4PEBESExQ reason=rp-self-service",
        )
    }

    @Test
    fun delete_logsFullyMasked_shortId() {
        val c: Credential = mock()
        whenever(creds.findOwnedForUpdate(anyOrNull(), anyOrNull())).thenReturn(Optional.of(c))

        svc.delete(byteArrayOf(1), byteArrayOf(2))

        assertThat(warns()).containsExactly("self-service credential deleted: id=*** reason=rp-self-service")
    }

    @Test
    fun delete_logsFullyMasked_exactlyTwelveCharId() {
        // 9 바이트 → base64url 12자: 경계(≤12)에서 전체 마스킹
        val c: Credential = mock()
        whenever(creds.findOwnedForUpdate(anyOrNull(), anyOrNull())).thenReturn(Optional.of(c))

        svc.delete(byteArrayOf(1), ByteArray(9))

        assertThat(warns()).containsExactly("self-service credential deleted: id=*** reason=rp-self-service")
    }
}
