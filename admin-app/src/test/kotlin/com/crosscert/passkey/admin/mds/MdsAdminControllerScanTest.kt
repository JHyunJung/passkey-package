package com.crosscert.passkey.admin.mds

import com.crosscert.passkey.core.config.DbSchemaProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.env.Environment
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration

/**
 * G16-redisKEYS — GET /admin/api/mds/status must count AAGUID cache entries
 * without ever issuing a blocking Redis KEYS command. KEYS scans the entire
 * keyspace on Redis's single thread, blocking every other client (auth cache
 * lookups, challenge lookups, ...) for the duration.
 *
 * Drives [MdsAdminController.status] against a real Redis
 * (Testcontainers) with a large number of unrelated keys mixed in, and
 * asserts the reported trustAnchorCount only counts "mds:aaguid:*" keys —
 * while never calling KEYS (verified indirectly: this test seeds enough
 * keys that a is-empty/get-all approach would be trivially wrong, and the
 * production code path here is exercised end-to-end against real Redis
 * SCAN semantics rather than a mocked KEYS command).
 */
@Testcontainers
class MdsAdminControllerScanTest {

    companion object {
        @Container
        @JvmField
        val REDIS: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

        var connectionFactory: LettuceConnectionFactory? = null
        lateinit var redis: StringRedisTemplate

        @JvmStatic
        @BeforeAll
        fun startRedis() {
            REDIS.start()
            val config = RedisStandaloneConfiguration(
                REDIS.host, REDIS.getMappedPort(6379),
            )
            val factory = LettuceConnectionFactory(config)
            connectionFactory = factory
            factory.afterPropertiesSet()
            redis = StringRedisTemplate(factory)
            redis.afterPropertiesSet()
        }

        @JvmStatic
        @AfterAll
        fun stopRedis() {
            connectionFactory?.destroy()
            REDIS.stop()
        }
    }

    @BeforeEach
    fun flush() {
        val conn = checkNotNull(connectionFactory).connection
        try {
            conn.serverCommands().flushAll()
        } finally {
            conn.close()
        }
    }

    private fun controller(
        jdbc: JdbcTemplate,
        history: MdsHistoryService,
        env: Environment,
        redisTemplate: StringRedisTemplate,
    ): MdsAdminController = MdsAdminController(
        jdbc, mock<MdsSchedulerService>(), history, redisTemplate, env,
        DbSchemaProperties("PSK_APP_OWNER"),
    )

    @Test
    fun statusCountsOnlyAaguidKeysUsingScanNotKeys() {
        // 3 real "mds:aaguid:*" entries...
        redis.opsForValue().set("mds:aaguid:11111111-2222-3333-4444-555555555555", "FIDO_CERTIFIED_L1", Duration.ofHours(1))
        redis.opsForValue().set("mds:aaguid:22222222-2222-3333-4444-555555555555", "FIDO_CERTIFIED_L1", Duration.ofHours(1))
        redis.opsForValue().set("mds:aaguid:33333333-2222-3333-4444-555555555555", "FIDO_CERTIFIED_L1", Duration.ofHours(1))
        // ...plus a large number of unrelated keys that a KEYS-based scan of the
        // FULL keyspace would also touch — SCAN with a "mds:aaguid:*" pattern
        // must not count them.
        for (i in 0 until 500) {
            redis.opsForValue().set("challenge:$i", "v", Duration.ofMinutes(5))
        }

        val history = mock<MdsHistoryService>()
        whenever(history.successRate30dCountOk()).thenReturn(0)
        whenever(history.successRate30dCountTotal()).thenReturn(0)
        val env = mock<Environment>()
        whenever(env.getProperty("passkey.mds.trust-mode", "MDS_STRICT_OPTIONAL")).thenReturn("MDS_STRICT_OPTIONAL")

        // Spy on the real template so we can assert KEYS was never invoked while
        // still exercising the real Redis wire protocol for SCAN.
        val spiedRedis = spy(redis)

        // JdbcTemplate stand-in: only the Redis-derived trustAnchorCount matters here.
        val jdbc: JdbcTemplate = CapturingJdbcTemplate()
        val ctrl = controller(jdbc, history, env, spiedRedis)

        val response = ctrl.status()

        assertThat(checkNotNull(response.data()).trustAnchorCount).isEqualTo(3)
        // The whole point of G16: status() must never issue a blocking KEYS scan.
        verify(spiedRedis, never()).keys(anyString())
    }

    /**
     * Minimal JdbcTemplate stand-in: queryForObject invokes the caller's
     * RowMapper against a fabricated ResultSet-free row (only the
     * finalTrustAnchorCount closure matters to this test, not the SQL).
     * Avoids pulling in a real DataSource just to test the Redis path.
     */
    private class CapturingJdbcTemplate : JdbcTemplate() {
        override fun <T> queryForObject(sql: String, rowMapper: RowMapper<T>): T {
            try {
                val rs = mock<ResultSet>()
                whenever(rs.getLong("version")).thenReturn(1L)
                whenever(rs.getDate("nextUpdate")).thenReturn(Date.valueOf("2099-01-01"))
                whenever(rs.getTimestamp("fetchedAt")).thenReturn(Timestamp.valueOf("2026-01-01 00:00:00"))
                return rowMapper.mapRow(rs, 0)
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }
}
