package com.crosscert.passkey.app.fido2.challenge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
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
        // Mirror production: Spring's auto-configured ObjectMapper has
        // JavaTimeModule installed by CoreJacksonConfig. The test uses
        // an equivalent configuration so Instant fields round-trip.
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
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
        assertThat(taken.tenantId()).isEqualTo("T_A");
        assertThat(taken.username()).isEqualTo("alice@example");
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
}
