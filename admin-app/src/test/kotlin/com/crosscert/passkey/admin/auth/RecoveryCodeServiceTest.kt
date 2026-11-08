package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AdminUserRecoveryCode
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class RecoveryCodeServiceTest {

    @Mock
    lateinit var repo: AdminUserRecoveryCodeRepository
    var clock: Clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), KstTime.ZONE)
    lateinit var service: RecoveryCodeService

    @BeforeEach
    fun setUp() {
        service = RecoveryCodeService(repo, clock)
    }

    @Test
    fun generate_returns_ten_plaintext_and_purges_old() {
        val uid = UUID.randomUUID()
        val codes = service.generate(uid)
        assertThat(codes).hasSize(10)
        assertThat(codes).doesNotHaveDuplicates()
        verify(repo).deleteByAdminUserId(uid)
        verify(repo, times(10)).save(any<AdminUserRecoveryCode>())
    }

    @Test
    fun consume_marks_matching_unused_code() {
        val uid = UUID.randomUUID()
        val plain = "abcd-efgh"
        val normalizedHash = sha256Hex("ABCD-EFGH") // service.normalize 결과 기준
        whenever(repo.markUsed(eq(uid), eq(normalizedHash), eq(OffsetDateTime.now(clock))))
            .thenReturn(1)

        val ok = service.consume(uid, plain)

        assertThat(ok).isTrue()
    }

    @Test
    fun consume_returns_false_when_no_match() {
        val uid = UUID.randomUUID()
        whenever(repo.markUsed(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(0)
        assertThat(service.consume(uid, "nope-nope")).isFalse()
    }

    @Test
    fun consume_returns_false_for_null_code() {
        assertThat(service.consume(UUID.randomUUID(), null)).isFalse()
        verifyNoInteractions(repo)
    }

    private companion object {
        fun sha256Hex(s: String): String {
            val h = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8))
            val sb = StringBuilder()
            for (b in h) sb.append(String.format("%02x", b))
            return sb.toString()
        }
    }
}
