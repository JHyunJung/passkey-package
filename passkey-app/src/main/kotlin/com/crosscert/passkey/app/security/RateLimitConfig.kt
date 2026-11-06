package com.crosscert.passkey.app.security

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.StringUtils
import java.time.Duration

/**
 * Wires a Bucket4j [ProxyManager] backed by a dedicated Lettuce
 * connection — separate from the Spring-Data-Redis `LettuceConnectionFactory`
 * because Bucket4j needs a `RedisCodec<String, byte[]>` (string keys,
 * binary state values), whereas Spring Data Redis defaults to `byte[]/byte[]`.
 *
 * The [RedisClient] and [StatefulRedisConnection] beans are
 * declared with explicit `destroyMethod`s so Lettuce shuts down
 * cleanly on application stop.
 *
 * Bucket TTL strategy: `basedOnTimeForRefillingBucketUpToMax(1h)`.
 * After a bucket has been idle long enough to fully refill (60 s for the
 * 60/min limits, 60 s for 300/min) plus a safety margin, Redis evicts the
 * key. This keeps memory pressure bounded while the worst-case
 * "first request from a tenant after a quiet period" is indistinguishable
 * from a fresh bucket — exactly the desired semantics.
 */
@Configuration
class RateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    fun bucket4jRedisClient(props: DataRedisProperties): RedisClient {
        // Prefer the full spring.data.redis.url if set — covers
        // username/password/database/SSL/timeout that host+port alone
        // can't express. Falls back to host+port for the Phase 0
        // docker-compose default which only sets those two.
        val uri = props.url?.takeIf(StringUtils::hasText)?.let(RedisURI::create)
            ?: buildUriFromHostPort(props)
        return RedisClient.create(uri)
    }

    @Bean(destroyMethod = "close")
    fun bucket4jRedisConnection(client: RedisClient): StatefulRedisConnection<String, ByteArray> =
        client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE))

    // Bucket4j 8.18 은 builderFor/withExpirationStrategy 를 Bucket4jLettuce 빌더로 대체 예고(deprecated)했다.
    // 원 Java 구현과 동일한 API 를 유지해 동작을 보존한다 — 신 API 이관은 별도 작업.
    @Suppress("DEPRECATION")
    @Bean
    fun bucket4jProxyManager(connection: StatefulRedisConnection<String, ByteArray>): ProxyManager<String> =
        LettuceBasedProxyManager.builderFor(connection)
            .withExpirationStrategy(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofHours(1)),
            )
            .build()
}

private fun buildUriFromHostPort(props: DataRedisProperties): RedisURI {
    val builder = RedisURI.builder()
        .withHost(props.host)
        .withPort(props.port)
        .withDatabase(props.database)
    props.timeout?.let(builder::withTimeout)
    props.password?.takeIf(StringUtils::hasText)?.let { password ->
        val username = props.username?.takeIf(StringUtils::hasText)
        if (username != null) {
            builder.withAuthentication(username, password.toCharArray())
        } else {
            builder.withPassword(password.toCharArray())
        }
    }
    if (props.ssl.isEnabled) {
        builder.withSsl(true)
    }
    return builder.build()
}
