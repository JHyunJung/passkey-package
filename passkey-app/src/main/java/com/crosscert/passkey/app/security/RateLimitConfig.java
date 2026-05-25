package com.crosscert.passkey.app.security;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Wires a Bucket4j {@link ProxyManager} backed by a dedicated Lettuce
 * connection — separate from the Spring-Data-Redis {@code LettuceConnectionFactory}
 * because Bucket4j needs a {@code RedisCodec<String, byte[]>} (string keys,
 * binary state values), whereas Spring Data Redis defaults to {@code byte[]/byte[]}.
 *
 * <p>The {@link RedisClient} and {@link StatefulRedisConnection} beans are
 * declared with explicit {@code destroyMethod}s so Lettuce shuts down
 * cleanly on application stop.
 *
 * <p>Bucket TTL strategy: {@code basedOnTimeForRefillingBucketUpToMax(1h)}.
 * After a bucket has been idle long enough to fully refill (60 s for the
 * 60/min limits, 60 s for 300/min) plus a safety margin, Redis evicts the
 * key. This keeps memory pressure bounded while the worst-case
 * "first request from a tenant after a quiet period" is indistinguishable
 * from a fresh bucket — exactly the desired semantics.
 */
@Configuration
public class RateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient bucket4jRedisClient(RedisProperties props) {
        // Prefer the full spring.data.redis.url if set — covers
        // username/password/database/SSL/timeout that host+port alone
        // can't express. Falls back to host+port for the Phase 0
        // docker-compose default which only sets those two.
        RedisURI uri = StringUtils.hasText(props.getUrl())
                ? RedisURI.create(props.getUrl())
                : buildUriFromHostPort(props);
        return RedisClient.create(uri);
    }

    private static RedisURI buildUriFromHostPort(RedisProperties props) {
        RedisURI.Builder b = RedisURI.builder()
                .withHost(props.getHost())
                .withPort(props.getPort())
                .withDatabase(props.getDatabase());
        if (props.getTimeout() != null) {
            b.withTimeout(props.getTimeout());
        }
        if (StringUtils.hasText(props.getPassword())) {
            if (StringUtils.hasText(props.getUsername())) {
                b.withAuthentication(props.getUsername(),
                        props.getPassword().toCharArray());
            } else {
                b.withPassword(props.getPassword().toCharArray());
            }
        }
        if (props.getSsl() != null && props.getSsl().isEnabled()) {
            b.withSsl(true);
        }
        return b.build();
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> bucket4jRedisConnection(
            RedisClient client) {
        return client.connect(
                RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    public ProxyManager<String> bucket4jProxyManager(
            StatefulRedisConnection<String, byte[]> connection) {
        return LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                Duration.ofHours(1)))
                .build();
    }
}
