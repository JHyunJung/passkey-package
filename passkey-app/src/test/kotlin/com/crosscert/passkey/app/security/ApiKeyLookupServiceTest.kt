package com.crosscert.passkey.app.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.crosscert.passkey.core.config.DbSchemaProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.jdbc.CannotGetJdbcConnectionException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private const val SCHEMA = "SCH"
private val ID = UUID.fromString("01234567-89ab-cdef-0011-223344556677")
private val TENANT = UUID.fromString("fedcba98-7654-3210-ffee-ddccbbaa9988")
private val ID_BYTES = byteArrayOf(
    0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(),
    0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
)
private val TENANT_BYTES = byteArrayOf(
    0xfe.toByte(), 0xdc.toByte(), 0xba.toByte(), 0x98.toByte(), 0x76, 0x54, 0x32, 0x10,
    0xff.toByte(), 0xee.toByte(), 0xdd.toByte(), 0xcc.toByte(), 0xbb.toByte(), 0xaa.toByte(),
    0x99.toByte(), 0x88.toByte(),
)

/**
 * Kotlin 전환 전 ApiKeyLookupService 의 JDBC 경계 동작(정확한 SQL 텍스트·바인딩 순서·
 * RAW(16)↔UUID 바이트 순서·Timestamp 변환·malformed RAW 예외 메시지·touchLastUsed 의
 * DataAccessException 한정 swallow 와 로그 문구)을 Docker 없이 mock JDBC 로 고정한다.
 * 실제 Oracle 왕복은 ApiKeyLookupServiceIT 가 검증한다.
 */
class ApiKeyLookupServiceTest {

    private val dataSource: DataSource = mock()
    private val con: Connection = mock()
    private val ps: PreparedStatement = mock()
    private val rs: ResultSet = mock()
    private val service = ApiKeyLookupService(dataSource, DbSchemaProperties(SCHEMA))

    private val logger = LoggerFactory.getLogger(ApiKeyLookupService::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        whenever(dataSource.connection).thenReturn(con)
        whenever(con.prepareStatement(any<String>())).thenReturn(ps)
        whenever(ps.executeQuery()).thenReturn(rs)
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
    }

    private fun stubRow(idBytes: ByteArray?, tenantBytes: ByteArray?) {
        whenever(rs.next()).thenReturn(true)
        whenever(rs.getBytes("id")).thenReturn(idBytes)
        whenever(rs.getBytes("tenant_id")).thenReturn(tenantBytes)
    }

    // ── findByPrefix ────────────────────────────────────────────────────────

    @Test
    fun findByPrefix_uses_exact_schema_qualified_sql_and_binds_prefix() {
        whenever(rs.next()).thenReturn(false)

        val row = service.findByPrefix("pk_abcd1234")

        assertThat(row).isEmpty()
        verify(con).prepareStatement(
            "SELECT id, tenant_id, key_hash, expires_at, revoked_at FROM SCH.api_key WHERE key_prefix = ?",
        )
        verify(ps).setString(1, "pk_abcd1234")
    }

    @Test
    fun findByPrefix_maps_big_endian_raw_and_timestamps() {
        val exp = Instant.parse("2027-01-02T03:04:05.123456789Z")
        val rev = Instant.parse("2026-12-01T00:00:00Z")
        stubRow(ID_BYTES, TENANT_BYTES)
        whenever(rs.getString("key_hash")).thenReturn("HASH")
        whenever(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(exp))
        whenever(rs.getTimestamp("revoked_at")).thenReturn(Timestamp.from(rev))

        val row = service.findByPrefix("pk_abcd1234").orElseThrow()

        assertThat(row).isEqualTo(ApiKeyLookupService.ApiKeyAuthRow(ID, TENANT, "HASH", exp, rev))
    }

    @Test
    fun findByPrefix_null_timestamps_map_to_null() {
        stubRow(ID_BYTES, TENANT_BYTES)
        whenever(rs.getString("key_hash")).thenReturn("HASH")

        val row = service.findByPrefix("pk_abcd1234").orElseThrow()

        assertThat(row).isEqualTo(ApiKeyLookupService.ApiKeyAuthRow(ID, TENANT, "HASH", null, null))
    }

    @Test
    fun findByPrefix_null_id_fails_closed_with_exact_message() {
        stubRow(null, TENANT_BYTES)

        assertThatThrownBy { service.findByPrefix("pk_abcd1234") }
            .isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("api_key returned malformed RAW for id: expected 16 bytes, got null")
    }

    @Test
    fun findByPrefix_wrong_length_tenant_fails_closed_with_exact_message() {
        stubRow(ID_BYTES, ByteArray(15))

        assertThatThrownBy { service.findByPrefix("pk_abcd1234") }
            .isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("api_key returned malformed RAW for tenant_id: expected 16 bytes, got 15")
    }

    @Test
    fun findByPrefix_connection_failure_propagates() {
        whenever(dataSource.connection).thenThrow(SQLException("down"))

        assertThatThrownBy { service.findByPrefix("pk_abcd1234") }
            .isInstanceOf(CannotGetJdbcConnectionException::class.java)
    }

    // ── touchLastUsed ───────────────────────────────────────────────────────

    @Test
    fun touchLastUsed_uses_exact_sql_and_binding_order() {
        val now = Instant.parse("2026-11-06T01:02:03.456Z")

        service.touchLastUsed(ID, TENANT, now)

        verify(con).prepareStatement("UPDATE SCH.api_key SET last_used_at = ? WHERE id = ? AND tenant_id = ?")
        inOrder(ps) {
            verify(ps).setTimestamp(1, Timestamp.from(now))
            verify(ps).setBytes(2, ID_BYTES)
            verify(ps).setBytes(3, TENANT_BYTES)
            verify(ps).executeUpdate()
        }
        assertThat(appender.list).isEmpty()
    }

    @Test
    fun touchLastUsed_swallows_data_access_exception_and_warns() {
        whenever(dataSource.connection).thenThrow(SQLException("down"))

        service.touchLastUsed(ID, TENANT, Instant.now())

        assertThat(appender.list).hasSize(1)
        assertThat(appender.list[0].level).isEqualTo(Level.WARN)
        assertThat(appender.list[0].formattedMessage).isEqualTo(
            "api-key touch_last_used failed (best-effort): " +
                "org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection",
        )
    }

    @Test
    fun touchLastUsed_does_not_swallow_non_data_access_exception() {
        val boom: RuntimeException = IllegalArgumentException("boom")
        doThrow(boom).whenever(ps).setTimestamp(any<Int>(), any<Timestamp>())

        assertThatThrownBy { service.touchLastUsed(ID, TENANT, Instant.now()) }.isSameAs(boom)
        assertThat(appender.list).isEmpty()
    }

    // ── null inputs (원 Java 가 받던 입력 그대로) ──────────────────────────────

    @Test
    fun findByPrefix_null_prefix_binds_sql_null_and_returns_empty() {
        whenever(rs.next()).thenReturn(false)

        assertThat(service.findByPrefix(null)).isEmpty()
        verify(ps).setString(1, null)
    }

    @Test
    fun findByPrefix_null_key_hash_maps_to_row_with_null_hash() {
        stubRow(ID_BYTES, TENANT_BYTES)
        whenever(rs.getString("key_hash")).thenReturn(null)

        val row = service.findByPrefix("pk_abcd1234").orElseThrow()

        assertThat(row).isEqualTo(ApiKeyLookupService.ApiKeyAuthRow(ID, TENANT, null, null, null))
    }

    @Test
    fun touchLastUsed_null_ids_bind_sql_null_without_exception() {
        val now = Instant.parse("2026-11-06T01:02:03Z")
        whenever(ps.executeUpdate()).thenReturn(0)

        service.touchLastUsed(null, null, now)

        inOrder(ps) {
            verify(ps).setTimestamp(1, Timestamp.from(now))
            verify(ps).setBytes(2, null)
            verify(ps).setBytes(3, null)
            verify(ps).executeUpdate()
        }
        assertThat(appender.list).isEmpty()
    }

    @Test
    fun touchLastUsed_null_now_throws_npe_after_connection() {
        assertThatThrownBy { service.touchLastUsed(ID, TENANT, null) }
            .isInstanceOf(NullPointerException::class.java)
        verify(dataSource).connection
    }

    @Test
    fun touchLastUsed_null_now_with_connection_failure_is_swallowed() {
        whenever(dataSource.connection).thenThrow(SQLException("down"))

        service.touchLastUsed(ID, TENANT, null)

        assertThat(appender.list).hasSize(1)
    }
}
