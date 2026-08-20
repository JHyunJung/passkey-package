package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.core.config.DbSchemaProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G16-redisKEYS — GET /admin/api/mds/status must count AAGUID cache entries
 * without ever issuing a blocking Redis KEYS command. KEYS scans the entire
 * keyspace on Redis's single thread, blocking every other client (auth cache
 * lookups, challenge lookups, ...) for the duration.
 *
 * <p>Drives {@link MdsAdminController#status()} against a real Redis
 * (Testcontainers) with a large number of unrelated keys mixed in, and
 * asserts the reported trustAnchorCount only counts "mds:aaguid:*" keys —
 * while never calling KEYS (verified indirectly: this test seeds enough
 * keys that a is-empty/get-all approach would be trivially wrong, and the
 * production code path here is exercised end-to-end against real Redis
 * SCAN semantics rather than a mocked KEYS command).
 */
@Testcontainers
class MdsAdminControllerScanTest {

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) connectionFactory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void flush() {
        var conn = connectionFactory.getConnection();
        try {
            conn.serverCommands().flushAll();
        } finally {
            conn.close();
        }
    }

    private MdsAdminController controller(JdbcTemplate jdbc, MdsHistoryService history, Environment env,
                                           StringRedisTemplate redisTemplate) {
        return new MdsAdminController(jdbc, mock(MdsSchedulerService.class), history, redisTemplate, env,
                new DbSchemaProperties("PSK_APP_OWNER"));
    }

    @Test
    void statusCountsOnlyAaguidKeysUsingScanNotKeys() {
        // 3 real "mds:aaguid:*" entries...
        redis.opsForValue().set("mds:aaguid:11111111-2222-3333-4444-555555555555", "FIDO_CERTIFIED_L1", Duration.ofHours(1));
        redis.opsForValue().set("mds:aaguid:22222222-2222-3333-4444-555555555555", "FIDO_CERTIFIED_L1", Duration.ofHours(1));
        redis.opsForValue().set("mds:aaguid:33333333-2222-3333-4444-555555555555", "FIDO_CERTIFIED_L1", Duration.ofHours(1));
        // ...plus a large number of unrelated keys that a KEYS-based scan of the
        // FULL keyspace would also touch — SCAN with a "mds:aaguid:*" pattern
        // must not count them.
        for (int i = 0; i < 500; i++) {
            redis.opsForValue().set("challenge:" + i, "v", Duration.ofMinutes(5));
        }

        MdsHistoryService history = mock(MdsHistoryService.class);
        when(history.successRate30dCountOk()).thenReturn(0);
        when(history.successRate30dCountTotal()).thenReturn(0);
        Environment env = mock(Environment.class);
        when(env.getProperty("passkey.mds.trust-mode", "MDS_STRICT_OPTIONAL")).thenReturn("MDS_STRICT_OPTIONAL");

        // Spy on the real template so we can assert KEYS was never invoked while
        // still exercising the real Redis wire protocol for SCAN.
        StringRedisTemplate spiedRedis = spy(redis);

        // JdbcTemplate stand-in: only the Redis-derived trustAnchorCount matters here.
        JdbcTemplate jdbc = new CapturingJdbcTemplate();
        MdsAdminController ctrl = controller(jdbc, history, env, spiedRedis);

        var response = ctrl.status();

        assertThat(response.data().trustAnchorCount()).isEqualTo(3);
        // The whole point of G16: status() must never issue a blocking KEYS scan.
        verify(spiedRedis, never()).keys(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * Minimal JdbcTemplate stand-in: queryForObject invokes the caller's
     * RowMapper against a fabricated ResultSet-free row (only the
     * finalTrustAnchorCount closure matters to this test, not the SQL).
     * Avoids pulling in a real DataSource just to test the Redis path.
     */
    private static class CapturingJdbcTemplate extends JdbcTemplate {
        @Override
        public <T> T queryForObject(String sql, org.springframework.jdbc.core.RowMapper<T> rowMapper) {
            try {
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getLong("version")).thenReturn(1L);
                when(rs.getDate("nextUpdate")).thenReturn(Date.valueOf("2099-01-01"));
                when(rs.getTimestamp("fetchedAt")).thenReturn(Timestamp.valueOf("2026-01-01 00:00:00"));
                return rowMapper.mapRow(rs, 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
