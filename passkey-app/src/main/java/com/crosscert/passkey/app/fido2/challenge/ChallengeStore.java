package com.crosscert.passkey.app.fido2.challenge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ChallengeStore {

    static final String REG_PREFIX = "challenge:reg:";
    static final String AUTH_PREFIX = "challenge:auth:";
    static final Duration TTL = Duration.ofMinutes(5);

    // Spring's auto-configured ObjectMapper already has JavaTimeModule
    // registered by CoreJacksonConfig. We trust the caller's mapper;
    // tests inject one configured the same way.
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public void putRegistration(String token, RegistrationChallenge value) {
        redis.opsForValue().set(REG_PREFIX + token, serialize(value), TTL);
    }

    public Optional<RegistrationChallenge> takeRegistration(String token) {
        // Atomic GETDEL ensures one-shot semantics under concurrent
        // /finish calls: even if two requests arrive with the same
        // token, only one observes the value; the other sees null.
        String json = redis.opsForValue().getAndDelete(REG_PREFIX + token);
        if (json == null) return Optional.empty();
        return Optional.of(deserialize(json, RegistrationChallenge.class));
    }

    public void putAuthentication(String token, AuthenticationChallenge value) {
        redis.opsForValue().set(AUTH_PREFIX + token, serialize(value), TTL);
    }

    public Optional<AuthenticationChallenge> takeAuthentication(String token) {
        String json = redis.opsForValue().getAndDelete(AUTH_PREFIX + token);
        if (json == null) return Optional.empty();
        return Optional.of(deserialize(json, AuthenticationChallenge.class));
    }

    private String serialize(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("ChallengeStore JSON serialize failed", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("ChallengeStore JSON deserialize failed", e);
        }
    }
}
