package com.crosscert.passkey.app.security

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.expiration.BasedOnTimeForRefillingBucketUpToMaxExpirationAfterWriteStrategy
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCredentials
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.codec.RedisCodec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotationUtils
import java.nio.ByteBuffer
import java.time.Duration

private fun uriOf(client: RedisClient): RedisURI =
    RedisClient::class.java.getDeclaredField("redisURI").apply { isAccessible = true }.get(client) as RedisURI

private fun creds(uri: RedisURI): RedisCredentials =
    checkNotNull(uri.credentialsProvider.resolveCredentials().block()) { "credentials provider resolved nothing" }

/**
 * Kotlin 전환 전 RateLimitConfig 의 빈 계약(빈 이름·destroyMethod·Redis URI 구성 분기·
 * 코덱·Bucket4j 만료 전략 값)을 현재 Java 구현 기준으로 고정한다. Redis 연결은 하지 않는다
 * (RedisClient.create 는 lazy, connection/proxy 는 mock).
 */
class RateLimitConfigTest {

    private val config = RateLimitConfig()

    private fun build(props: DataRedisProperties): RedisURI {
        val client = config.bucket4jRedisClient(props)
        try {
            return uriOf(client)
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun bean_methods_and_destroy_methods() {
        assertThat(AnnotationUtils.findAnnotation(RateLimitConfig::class.java, Configuration::class.java)).isNotNull()
        val client = RateLimitConfig::class.java.getMethod("bucket4jRedisClient", DataRedisProperties::class.java)
        val conn = RateLimitConfig::class.java.getMethod("bucket4jRedisConnection", RedisClient::class.java)
        val proxy = RateLimitConfig::class.java.getMethod("bucket4jProxyManager", StatefulRedisConnection::class.java)
        assertThat(client.getAnnotation(Bean::class.java).destroyMethod).isEqualTo("shutdown")
        assertThat(conn.getAnnotation(Bean::class.java).destroyMethod).isEqualTo("close")
        assertThat(proxy.getAnnotation(Bean::class.java)).isNotNull()
        assertThat(client.returnType).isEqualTo(RedisClient::class.java)
        assertThat(conn.genericReturnType.typeName)
            .isEqualTo("io.lettuce.core.api.StatefulRedisConnection<java.lang.String, byte[]>")
        assertThat(proxy.genericReturnType.typeName)
            .isEqualTo("io.github.bucket4j.distributed.proxy.ProxyManager<java.lang.String>")
        assertThat(proxy.genericParameterTypes[0].typeName)
            .isEqualTo("io.lettuce.core.api.StatefulRedisConnection<java.lang.String, byte[]>")
    }

    @Test
    fun url_wins_over_host_and_port() {
        val p = DataRedisProperties().apply {
            url = "redis://user1:pw1@redis.example:6380/3"
            host = "ignored-host"
            port = 1111
        }

        val uri = build(p)

        assertThat(uri.host).isEqualTo("redis.example")
        assertThat(uri.port).isEqualTo(6380)
        assertThat(uri.database).isEqualTo(3)
        assertThat(creds(uri).username).isEqualTo("user1")
        assertThat(String(creds(uri).password)).isEqualTo("pw1")
    }

    @Test
    fun blank_url_falls_back_to_host_port_defaults() {
        val p = DataRedisProperties().apply {
            url = "   "
            host = "h1"
            port = 7000
            database = 2
        }

        val uri = build(p)

        assertThat(uri.host).isEqualTo("h1")
        assertThat(uri.port).isEqualTo(7000)
        assertThat(uri.database).isEqualTo(2)
        assertThat(uri.timeout).isEqualTo(RedisURI.DEFAULT_TIMEOUT_DURATION)
        assertThat(uri.isSsl).isFalse()
        assertThat(creds(uri).hasPassword()).isFalse()
        assertThat(creds(uri).hasUsername()).isFalse()
    }

    @Test
    fun host_port_with_timeout_password_only_and_ssl() {
        val p = DataRedisProperties().apply {
            host = "h2"
            port = 6379
            timeout = Duration.ofSeconds(7)
            password = "secret-pw"
            username = "  "
            ssl.isEnabled = true
        }

        val uri = build(p)

        assertThat(uri.timeout).isEqualTo(Duration.ofSeconds(7))
        assertThat(uri.isSsl).isTrue()
        assertThat(creds(uri).hasUsername()).`as`("blank username → password-only auth").isFalse()
        assertThat(String(creds(uri).password)).isEqualTo("secret-pw")
    }

    @Test
    fun host_port_with_username_and_password_uses_acl_auth() {
        val p = DataRedisProperties().apply {
            host = "h3"
            username = "acl-user"
            password = "acl-pw"
        }

        val uri = build(p)

        assertThat(creds(uri).username).isEqualTo("acl-user")
        assertThat(String(creds(uri).password)).isEqualTo("acl-pw")
    }

    @Test
    fun blank_password_means_no_auth_even_with_username() {
        val p = DataRedisProperties().apply {
            host = "h4"
            username = "acl-user"
            password = " "
        }

        val uri = build(p)

        assertThat(creds(uri).hasPassword()).isFalse()
        assertThat(creds(uri).hasUsername()).isFalse()
    }

    @Test
    fun connection_uses_utf8_string_keys_and_raw_byte_values() {
        val client: RedisClient = mock()
        val conn: StatefulRedisConnection<String, ByteArray> = mock()
        whenever(client.connect(any<RedisCodec<String, ByteArray>>())).thenReturn(conn)

        assertThat(config.bucket4jRedisConnection(client)).isSameAs(conn)

        val captor = argumentCaptor<RedisCodec<String, ByteArray>>()
        verify(client).connect(captor.capture())
        val codec = captor.lastValue
        assertThat(codec.encodeKey("한글k")).isEqualTo(ByteBuffer.wrap("한글k".toByteArray(Charsets.UTF_8)))
        val value = byteArrayOf(1, 2, 0xff.toByte())
        val encoded = codec.encodeValue(value)
        // 저장 형식 동결: 값은 변환 없이 원 바이트 그대로 Redis 에 기록된다.
        val stored = ByteArray(encoded.remaining())
        encoded.duplicate().get(stored)
        assertThat(stored).containsExactly(*byteArrayOf(1, 2, 0xff.toByte()))
        assertThat(codec.decodeValue(encoded)).containsExactly(*byteArrayOf(1, 2, 0xff.toByte()))
    }

    @Test
    fun proxy_manager_expires_buckets_one_hour_after_full_refill() {
        val conn: StatefulRedisConnection<String, ByteArray> = mock()
        whenever(conn.async()).thenReturn(mock<RedisAsyncCommands<String, ByteArray>>())

        val pm = config.bucket4jProxyManager(conn)

        val s = pm.javaClass.getDeclaredField("expirationStrategy").apply { isAccessible = true }
            .get(pm) as ExpirationAfterWriteStrategy
        assertThat(s).isInstanceOf(BasedOnTimeForRefillingBucketUpToMaxExpirationAfterWriteStrategy::class.java)
        val keep = BasedOnTimeForRefillingBucketUpToMaxExpirationAfterWriteStrategy::class.java
            .getDeclaredField("keepAfterRefillDurationMillis").apply { isAccessible = true }
        assertThat(keep.getLong(s)).isEqualTo(Duration.ofHours(1).toMillis())
    }
}
