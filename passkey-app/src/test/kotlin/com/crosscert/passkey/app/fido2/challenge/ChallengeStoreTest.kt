package com.crosscert.passkey.app.fido2.challenge

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant

class ChallengeStoreTest {

    private val redis: StringRedisTemplate = mock()
    private val ops: ValueOperations<String, String> = mock()

    // Jackson 3 는 java.time 지원이 내장이라 별도 모듈 없이 Instant 필드가 round-trip 된다.
    // (운영 매퍼와 같은 직렬화 형식 고정은 ChallengeStoreFormatCompatTest 가 검증.)
    private val store = ChallengeStore(redis, ObjectMapper())

    @BeforeEach
    fun setup() {
        whenever(redis.opsForValue()).thenReturn(ops)
    }

    @Test
    fun putRegistrationStoresJsonWithTtl() {
        val rc = RegistrationChallenge(
            "T_A", byteArrayOf(1, 2, 3), byteArrayOf(4, 5), "Alice", "alice@example",
            Instant.parse("2026-05-25T08:00:00Z"),
        )
        store.putRegistration("tok_x", rc)
        verify(ops).set(eq("challenge:reg:tok_x"), any<String>(), eq(Duration.ofMinutes(5)))
    }

    @Test
    fun takeRegistrationReturnsAndDeletesAtomically() {
        // getAndDelete is the atomic Redis operation that returns the
        // current value AND deletes the key in one round-trip. This
        // guarantees one-shot semantics under concurrent /finish calls.
        val json = """
            {"tenantId":"T_A","challenge":"AQID","userHandle":"BAU=",
             "displayName":"Alice","username":"alice@example",
             "issuedAt":"2026-05-25T08:00:00Z"}
            """.trimIndent()
        whenever(ops.getAndDelete("challenge:reg:tok_x")).thenReturn(json)

        val taken = store.takeRegistration("tok_x").orElseThrow()
        assertThat(taken.tenantId).isEqualTo("T_A")
        assertThat(taken.username).isEqualTo("alice@example")
        verify(ops).getAndDelete("challenge:reg:tok_x")
        // Critical: no separate delete call — atomicity is delivered by
        // the single getAndDelete invocation.
        verify(redis, never()).delete(any<String>())
    }

    @Test
    fun takeMissingReturnsEmpty() {
        whenever(ops.getAndDelete(any<String>())).thenReturn(null)
        assertThat(store.takeRegistration("missing")).isEmpty()
    }

    /**
     * 저장값이 JSON 리터럴 `null` 이면 역직렬화 결과가 null 이고, 원 Java 구현에서는
     * try/catch 밖의 `Optional.of(null)` 이 NPE 를 던졌다 — IllegalStateException 으로
     * 감싸지 않는 이 예외 흐름을 고정한다.
     */
    @Test
    fun takeRegistration_storedJsonNull_throwsBareNpe() {
        whenever(ops.getAndDelete("challenge:reg:tok_null")).thenReturn("null")
        assertThatThrownBy { store.takeRegistration("tok_null") }
            .isExactlyInstanceOf(NullPointerException::class.java)
    }

    @Test
    fun takeAuthentication_storedJsonNull_throwsBareNpe() {
        whenever(ops.getAndDelete("challenge:auth:tok_null")).thenReturn("null")
        assertThatThrownBy { store.takeAuthentication("tok_null") }
            .isExactlyInstanceOf(NullPointerException::class.java)
    }
}
