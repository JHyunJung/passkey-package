package com.crosscert.passkey.app.fido2.challenge;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChallengeStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ChallengeStore store;

    @BeforeEach
    void setup() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        // Jackson 3 는 java.time 지원이 내장이라 별도 모듈 없이 Instant 필드가 round-trip 된다.
        // (운영 매퍼와 같은 직렬화 형식 고정은 ChallengeStoreFormatCompatTest 가 검증.)
        ObjectMapper mapper = new ObjectMapper();
        store = new ChallengeStore(redis, mapper);
    }

    @Test
    void putRegistrationStoresJsonWithTtl() {
        RegistrationChallenge rc = new RegistrationChallenge(
                "T_A", new byte[]{1,2,3}, new byte[]{4,5}, "Alice", "alice@example",
                Instant.parse("2026-05-25T08:00:00Z"));
        store.putRegistration("tok_x", rc);
        verify(ops).set(eq("challenge:reg:tok_x"), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void takeRegistrationReturnsAndDeletesAtomically() {
        // getAndDelete is the atomic Redis operation that returns the
        // current value AND deletes the key in one round-trip. This
        // guarantees one-shot semantics under concurrent /finish calls.
        String json = """
            {"tenantId":"T_A","challenge":"AQID","userHandle":"BAU=",
             "displayName":"Alice","username":"alice@example",
             "issuedAt":"2026-05-25T08:00:00Z"}
            """;
        when(ops.getAndDelete("challenge:reg:tok_x")).thenReturn(json);

        RegistrationChallenge taken = store.takeRegistration("tok_x").orElseThrow();
        assertThat(taken.getTenantId()).isEqualTo("T_A");
        assertThat(taken.getUsername()).isEqualTo("alice@example");
        verify(ops).getAndDelete("challenge:reg:tok_x");
        // Critical: no separate delete call — atomicity is delivered by
        // the single getAndDelete invocation.
        verify(redis, never()).delete(anyString());
    }

    @Test
    void takeMissingReturnsEmpty() {
        when(ops.getAndDelete(anyString())).thenReturn(null);
        assertThat(store.takeRegistration("missing")).isEmpty();
    }

    /**
     * 저장값이 JSON 리터럴 {@code null} 이면 역직렬화 결과가 null 이고, 원 Java 구현에서는
     * try/catch 밖의 {@code Optional.of(null)} 이 NPE 를 던졌다 — IllegalStateException 으로
     * 감싸지 않는 이 예외 흐름을 고정한다.
     */
    @Test
    void takeRegistration_storedJsonNull_throwsBareNpe() {
        when(ops.getAndDelete("challenge:reg:tok_null")).thenReturn("null");
        assertThatThrownBy(() -> store.takeRegistration("tok_null"))
                .isExactlyInstanceOf(NullPointerException.class);
    }

    @Test
    void takeAuthentication_storedJsonNull_throwsBareNpe() {
        when(ops.getAndDelete("challenge:auth:tok_null")).thenReturn("null");
        assertThatThrownBy(() -> store.takeAuthentication("tok_null"))
                .isExactlyInstanceOf(NullPointerException.class);
    }
}
