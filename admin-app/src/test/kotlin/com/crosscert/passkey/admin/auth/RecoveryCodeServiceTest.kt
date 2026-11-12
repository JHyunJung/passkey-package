package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AdminUserRecoveryCode
import com.crosscert.passkey.core.jwt.KeyEnvelope
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ExtendWith(MockitoExtension::class)
class RecoveryCodeServiceTest {

    @Mock
    lateinit var repo: AdminUserRecoveryCodeRepository
    var clock: Clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), KstTime.ZONE)
    private val envelope = KeyEnvelope(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }), SecureRandom())
    lateinit var service: RecoveryCodeService

    @BeforeEach
    fun setUp() {
        service = RecoveryCodeService(repo, clock, envelope)
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
    fun generate_stores_keyed_hmac_not_plain_sha256() {
        // 코드는 32자 알파벳 8자(40비트)라 솔트 없는 SHA-256 은 DB 유출 시 수 분 내 전수 대입된다.
        // 저장값은 master key 파생 키의 HMAC 이어야 DB 만으로는 대입할 수 없다.
        val codes = service.generate(UUID.randomUUID())
        val saved = argumentCaptor<AdminUserRecoveryCode>()
        verify(repo, times(10)).save(saved.capture())
        val stored = saved.allValues.map { it.codeHash }
        assertThat(stored).containsExactlyElementsOf(codes.map { hmacHex(it) })
        assertThat(stored).doesNotContainAnyElementsOf(codes.map { sha256Hex(it) })
        assertThat(stored).allMatch { it.length == 64 } // code_hash VARCHAR2(64) 그대로
    }

    @Test
    fun consume_marks_matching_hmac_code_without_trying_legacy() {
        val uid = UUID.randomUUID()
        whenever(repo.markUsed(eq(uid), eq(hmacHex("ABCD-EFGH")), eq(OffsetDateTime.now(clock)))).thenReturn(1)

        assertThat(service.consume(uid, " abcd-efgh ")).isTrue() // 정규화(trim·대문자) 후 비교

        verify(repo, never()).markUsed(eq(uid), eq(sha256Hex("ABCD-EFGH")), anyOrNull())
    }

    @Test
    fun consume_still_accepts_legacy_sha256_codes_issued_before_hmac() {
        // 전환 전에 발급된 코드는 재발급 전까지 계속 써야 한다(운영자 잠김 방지).
        val uid = UUID.randomUUID()
        val now = OffsetDateTime.now(clock)
        whenever(repo.markUsed(eq(uid), eq(hmacHex("ABCD-EFGH")), eq(now))).thenReturn(0)
        whenever(repo.markUsed(eq(uid), eq(sha256Hex("ABCD-EFGH")), eq(now))).thenReturn(1)

        assertThat(service.consume(uid, "abcd-efgh")).isTrue()
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

    private fun hmacHex(code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(envelope.deriveSubkey("passkey/admin-recovery-code/v1"), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(code.toByteArray(StandardCharsets.UTF_8)))
    }

    private companion object {
        fun sha256Hex(s: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8)))
    }
}
