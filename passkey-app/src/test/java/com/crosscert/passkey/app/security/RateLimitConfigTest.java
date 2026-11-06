package com.crosscert.passkey.app.security;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.expiration.BasedOnTimeForRefillingBucketUpToMaxExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.RedisCodec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kotlin 전환 전 RateLimitConfig 의 빈 계약(빈 이름·destroyMethod·Redis URI 구성 분기·
 * 코덱·Bucket4j 만료 전략 값)을 현재 Java 구현 기준으로 고정한다. Redis 연결은 하지 않는다
 * (RedisClient.create 는 lazy, connection/proxy 는 mock).
 */
class RateLimitConfigTest {

    private final RateLimitConfig config = new RateLimitConfig();

    private static RedisURI uriOf(RedisClient client) throws Exception {
        Field f = RedisClient.class.getDeclaredField("redisURI");
        f.setAccessible(true);
        return (RedisURI) f.get(client);
    }

    private RedisURI build(DataRedisProperties props) throws Exception {
        RedisClient client = config.bucket4jRedisClient(props);
        try {
            return uriOf(client);
        } finally {
            client.shutdown();
        }
    }

    private static RedisCredentials creds(RedisURI uri) {
        return uri.getCredentialsProvider().resolveCredentials().block();
    }

    @Test
    void bean_methods_and_destroy_methods() throws Exception {
        assertThat(AnnotationUtils.findAnnotation(RateLimitConfig.class, Configuration.class)).isNotNull();
        Method client = RateLimitConfig.class.getMethod("bucket4jRedisClient", DataRedisProperties.class);
        Method conn = RateLimitConfig.class.getMethod("bucket4jRedisConnection", RedisClient.class);
        Method proxy = RateLimitConfig.class.getMethod("bucket4jProxyManager", StatefulRedisConnection.class);
        assertThat(client.getAnnotation(Bean.class).destroyMethod()).isEqualTo("shutdown");
        assertThat(conn.getAnnotation(Bean.class).destroyMethod()).isEqualTo("close");
        assertThat(proxy.getAnnotation(Bean.class)).isNotNull();
        assertThat(client.getReturnType()).isEqualTo(RedisClient.class);
        assertThat(conn.getGenericReturnType().getTypeName())
                .isEqualTo("io.lettuce.core.api.StatefulRedisConnection<java.lang.String, byte[]>");
        assertThat(proxy.getGenericReturnType().getTypeName())
                .isEqualTo("io.github.bucket4j.distributed.proxy.ProxyManager<java.lang.String>");
        assertThat(proxy.getGenericParameterTypes()[0].getTypeName())
                .isEqualTo("io.lettuce.core.api.StatefulRedisConnection<java.lang.String, byte[]>");
    }

    @Test
    void url_wins_over_host_and_port() throws Exception {
        DataRedisProperties p = new DataRedisProperties();
        p.setUrl("redis://user1:pw1@redis.example:6380/3");
        p.setHost("ignored-host");
        p.setPort(1111);

        RedisURI uri = build(p);

        assertThat(uri.getHost()).isEqualTo("redis.example");
        assertThat(uri.getPort()).isEqualTo(6380);
        assertThat(uri.getDatabase()).isEqualTo(3);
        assertThat(creds(uri).getUsername()).isEqualTo("user1");
        assertThat(new String(creds(uri).getPassword())).isEqualTo("pw1");
    }

    @Test
    void blank_url_falls_back_to_host_port_defaults() throws Exception {
        DataRedisProperties p = new DataRedisProperties();
        p.setUrl("   ");
        p.setHost("h1");
        p.setPort(7000);
        p.setDatabase(2);

        RedisURI uri = build(p);

        assertThat(uri.getHost()).isEqualTo("h1");
        assertThat(uri.getPort()).isEqualTo(7000);
        assertThat(uri.getDatabase()).isEqualTo(2);
        assertThat(uri.getTimeout()).isEqualTo(RedisURI.DEFAULT_TIMEOUT_DURATION);
        assertThat(uri.isSsl()).isFalse();
        assertThat(creds(uri).hasPassword()).isFalse();
        assertThat(creds(uri).hasUsername()).isFalse();
    }

    @Test
    void host_port_with_timeout_password_only_and_ssl() throws Exception {
        DataRedisProperties p = new DataRedisProperties();
        p.setHost("h2");
        p.setPort(6379);
        p.setTimeout(Duration.ofSeconds(7));
        p.setPassword("secret-pw");
        p.setUsername("  ");
        p.getSsl().setEnabled(true);

        RedisURI uri = build(p);

        assertThat(uri.getTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(uri.isSsl()).isTrue();
        assertThat(creds(uri).hasUsername()).as("blank username → password-only auth").isFalse();
        assertThat(new String(creds(uri).getPassword())).isEqualTo("secret-pw");
    }

    @Test
    void host_port_with_username_and_password_uses_acl_auth() throws Exception {
        DataRedisProperties p = new DataRedisProperties();
        p.setHost("h3");
        p.setUsername("acl-user");
        p.setPassword("acl-pw");

        RedisURI uri = build(p);

        assertThat(creds(uri).getUsername()).isEqualTo("acl-user");
        assertThat(new String(creds(uri).getPassword())).isEqualTo("acl-pw");
    }

    @Test
    void blank_password_means_no_auth_even_with_username() throws Exception {
        DataRedisProperties p = new DataRedisProperties();
        p.setHost("h4");
        p.setUsername("acl-user");
        p.setPassword(" ");

        RedisURI uri = build(p);

        assertThat(creds(uri).hasPassword()).isFalse();
        assertThat(creds(uri).hasUsername()).isFalse();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void connection_uses_utf8_string_keys_and_raw_byte_values() {
        RedisClient client = mock(RedisClient.class);
        StatefulRedisConnection<String, byte[]> conn = mock(StatefulRedisConnection.class);
        when(client.connect(any(RedisCodec.class))).thenReturn((StatefulRedisConnection) conn);

        assertThat(config.bucket4jRedisConnection(client)).isSameAs(conn);

        ArgumentCaptor<RedisCodec> captor = ArgumentCaptor.forClass(RedisCodec.class);
        verify(client).connect(captor.capture());
        RedisCodec<String, byte[]> codec = captor.getValue();
        assertThat(codec.encodeKey("한글k"))
                .isEqualTo(ByteBuffer.wrap("한글k".getBytes(StandardCharsets.UTF_8)));
        byte[] value = {1, 2, (byte) 0xff};
        ByteBuffer encoded = codec.encodeValue(value);
        assertThat(codec.decodeValue(encoded)).containsExactly(1, 2, (byte) 0xff);
    }

    @Test
    @SuppressWarnings("unchecked")
    void proxy_manager_expires_buckets_one_hour_after_full_refill() throws Exception {
        StatefulRedisConnection<String, byte[]> conn = mock(StatefulRedisConnection.class);
        when(conn.async()).thenReturn(mock(RedisAsyncCommands.class));

        ProxyManager<String> pm = config.bucket4jProxyManager(conn);

        Field f = pm.getClass().getDeclaredField("expirationStrategy");
        f.setAccessible(true);
        ExpirationAfterWriteStrategy s = (ExpirationAfterWriteStrategy) f.get(pm);
        assertThat(s).isInstanceOf(BasedOnTimeForRefillingBucketUpToMaxExpirationAfterWriteStrategy.class);
        Field keep = BasedOnTimeForRefillingBucketUpToMaxExpirationAfterWriteStrategy.class
                .getDeclaredField("keepAfterRefillDurationMillis");
        keep.setAccessible(true);
        assertThat(keep.getLong(s)).isEqualTo(Duration.ofHours(1).toMillis());
    }
}
