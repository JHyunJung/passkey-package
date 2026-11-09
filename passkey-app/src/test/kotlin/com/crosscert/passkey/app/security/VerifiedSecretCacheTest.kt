package com.crosscert.passkey.app.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class VerifiedSecretCacheTest {

    private val cache = VerifiedSecretCache(Duration.ofSeconds(30), 100)

    @Test
    fun empty_cache_is_never_verified() {
        assertThat(cache.isVerified(ID_A, HASH, SECRET, T0)).isFalse()
    }

    @Test
    fun remembered_secret_is_verified_within_ttl_only() {
        cache.remember(ID_A, HASH, SECRET, T0)

        assertThat(cache.isVerified(ID_A, HASH, SECRET, T0)).isTrue()
        assertThat(cache.isVerified(ID_A, HASH, SECRET, T0.plusSeconds(29))).isTrue()
        assertThat(cache.isVerified(ID_A, HASH, SECRET, T0.plusSeconds(30))).isFalse()
        assertThat(cache.size()).isZero() // 만료 항목은 조회 시 제거
    }

    @Test
    fun clock_going_backwards_is_a_miss() {
        cache.remember(ID_A, HASH, SECRET, T0)
        assertThat(cache.isVerified(ID_A, HASH, SECRET, T0.minusMillis(1))).isFalse()
    }

    @Test
    fun different_secret_hash_or_id_is_a_miss() {
        cache.remember(ID_A, HASH, SECRET, T0)

        assertThat(cache.isVerified(ID_A, HASH, SECRET.substring(1) + "X", T0)).isFalse()
        assertThat(cache.isVerified(ID_A, HASH, SECRET + "X", T0)).isFalse()
        assertThat(cache.isVerified(ID_B, HASH, SECRET, T0)).isFalse()
        assertThat(cache.isVerified(ID_A, "\$2a\$12\$otherotherotherotherot", SECRET, T0)).isFalse()
        // keyHash 불일치는 항목을 버린다 — 이후 원래 hash 로도 다시 BCrypt 를 거쳐야 한다.
        assertThat(cache.isVerified(ID_A, HASH, SECRET, T0)).isFalse()
    }

    @Test
    fun null_key_hash_is_a_miss() {
        cache.remember(ID_A, HASH, SECRET, T0)
        assertThat(cache.isVerified(ID_A, null, SECRET, T0)).isFalse()
    }

    @Test
    fun secrets_sharing_first_72_bytes_are_not_conflated() {
        val base = "a".repeat(72)
        cache.remember(ID_A, HASH, base + "X", T0)
        assertThat(cache.isVerified(ID_A, HASH, base + "Y", T0)).isFalse()
    }

    @Test
    fun lone_surrogate_and_question_mark_are_not_conflated() {
        cache.remember(ID_A, HASH, "abc?", T0)
        assertThat(cache.isVerified(ID_A, HASH, "abc\uD800", T0)).isFalse()
    }

    @Test
    fun invalidate_removes_entry() {
        cache.remember(ID_A, HASH, SECRET, T0)
        cache.invalidate(ID_A)
        assertThat(cache.isVerified(ID_A, HASH, SECRET, T0)).isFalse()
    }

    @Test
    fun size_is_bounded_and_expired_entries_are_purged_to_make_room() {
        val small = VerifiedSecretCache(Duration.ofSeconds(30), 2)
        small.remember(ID_A, HASH, SECRET, T0)
        small.remember(ID_B, HASH, SECRET, T0)
        small.remember(ID_C, HASH, SECRET, T0)

        assertThat(small.size()).isEqualTo(2)
        assertThat(small.isVerified(ID_C, HASH, SECRET, T0)).isFalse()

        // 기존 키 갱신은 상한에서도 허용
        small.remember(ID_A, HASH, SECRET, T0.plusSeconds(1))
        assertThat(small.size()).isEqualTo(2)

        // A·B 가 만료된 뒤에는 정리하고 C 를 저장
        val later = T0.plusSeconds(31)
        small.remember(ID_C, HASH, SECRET, later)
        assertThat(small.isVerified(ID_C, HASH, SECRET, later)).isTrue()
        assertThat(small.size()).isEqualTo(1)
    }

    @Test
    fun rejects_invalid_configuration() {
        assertThatThrownBy { VerifiedSecretCache(Duration.ZERO, 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { VerifiedSecretCache(Duration.ofSeconds(1), 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-10-13T00:00:00Z")
        val ID_A: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val ID_B: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
        val ID_C: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c3")
        const val HASH = "\$2a\$12\$hashhashhashhashhashhu"
        const val SECRET = "SECRET_PART_OK_01234567890ABCDEF"
    }
}
