package com.crosscert.passkey.app.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.crosscert.passkey.core.config.DbSchemaProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kotlin 전환 전 ApiKeyLookupService 의 JDBC 경계 동작(정확한 SQL 텍스트·바인딩 순서·
 * RAW(16)↔UUID 바이트 순서·Timestamp 변환·malformed RAW 예외 메시지·touchLastUsed 의
 * DataAccessException 한정 swallow 와 로그 문구)을 Docker 없이 mock JDBC 로 고정한다.
 * 실제 Oracle 왕복은 ApiKeyLookupServiceIT 가 검증한다.
 */
class ApiKeyLookupServiceTest {

    private static final String SCHEMA = "SCH";
    private static final UUID ID = UUID.fromString("01234567-89ab-cdef-0011-223344556677");
    private static final UUID TENANT = UUID.fromString("fedcba98-7654-3210-ffee-ddccbbaa9988");
    private static final byte[] ID_BYTES = {
            0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef,
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77};
    private static final byte[] TENANT_BYTES = {
            (byte) 0xfe, (byte) 0xdc, (byte) 0xba, (byte) 0x98, 0x76, 0x54, 0x32, 0x10,
            (byte) 0xff, (byte) 0xee, (byte) 0xdd, (byte) 0xcc, (byte) 0xbb, (byte) 0xaa,
            (byte) 0x99, (byte) 0x88};

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection con = mock(Connection.class);
    private final PreparedStatement ps = mock(PreparedStatement.class);
    private final ResultSet rs = mock(ResultSet.class);
    private ApiKeyLookupService service;

    private Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(con);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        service = new ApiKeyLookupService(dataSource, new DbSchemaProperties(SCHEMA));
        logger = (Logger) LoggerFactory.getLogger(ApiKeyLookupService.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    // ── findByPrefix ────────────────────────────────────────────────────────

    @Test
    void findByPrefix_uses_exact_schema_qualified_sql_and_binds_prefix() throws SQLException {
        when(rs.next()).thenReturn(false);

        Optional<ApiKeyLookupService.ApiKeyAuthRow> row = service.findByPrefix("pk_abcd1234");

        assertThat(row).isEmpty();
        verify(con).prepareStatement(
                "SELECT id, tenant_id, key_hash, expires_at, revoked_at FROM SCH.api_key WHERE key_prefix = ?");
        verify(ps).setString(1, "pk_abcd1234");
    }

    @Test
    void findByPrefix_maps_big_endian_raw_and_timestamps() throws SQLException {
        Instant exp = Instant.parse("2027-01-02T03:04:05.123456789Z");
        Instant rev = Instant.parse("2026-12-01T00:00:00Z");
        when(rs.next()).thenReturn(true);
        when(rs.getBytes("id")).thenReturn(ID_BYTES);
        when(rs.getBytes("tenant_id")).thenReturn(TENANT_BYTES);
        when(rs.getString("key_hash")).thenReturn("HASH");
        when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(exp));
        when(rs.getTimestamp("revoked_at")).thenReturn(Timestamp.from(rev));

        ApiKeyLookupService.ApiKeyAuthRow row = service.findByPrefix("pk_abcd1234").orElseThrow();

        assertThat(row).isEqualTo(new ApiKeyLookupService.ApiKeyAuthRow(ID, TENANT, "HASH", exp, rev));
    }

    @Test
    void findByPrefix_null_timestamps_map_to_null() throws SQLException {
        when(rs.next()).thenReturn(true);
        when(rs.getBytes("id")).thenReturn(ID_BYTES);
        when(rs.getBytes("tenant_id")).thenReturn(TENANT_BYTES);
        when(rs.getString("key_hash")).thenReturn("HASH");

        ApiKeyLookupService.ApiKeyAuthRow row = service.findByPrefix("pk_abcd1234").orElseThrow();

        assertThat(row).isEqualTo(new ApiKeyLookupService.ApiKeyAuthRow(ID, TENANT, "HASH", null, null));
    }

    @Test
    void findByPrefix_null_id_fails_closed_with_exact_message() throws SQLException {
        when(rs.next()).thenReturn(true);
        when(rs.getBytes("id")).thenReturn(null);
        when(rs.getBytes("tenant_id")).thenReturn(TENANT_BYTES);

        assertThatThrownBy(() -> service.findByPrefix("pk_abcd1234"))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("api_key returned malformed RAW for id: expected 16 bytes, got null");
    }

    @Test
    void findByPrefix_wrong_length_tenant_fails_closed_with_exact_message() throws SQLException {
        when(rs.next()).thenReturn(true);
        when(rs.getBytes("id")).thenReturn(ID_BYTES);
        when(rs.getBytes("tenant_id")).thenReturn(new byte[15]);

        assertThatThrownBy(() -> service.findByPrefix("pk_abcd1234"))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("api_key returned malformed RAW for tenant_id: expected 16 bytes, got 15");
    }

    @Test
    void findByPrefix_connection_failure_propagates() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));

        assertThatThrownBy(() -> service.findByPrefix("pk_abcd1234"))
                .isInstanceOf(CannotGetJdbcConnectionException.class);
    }

    // ── touchLastUsed ───────────────────────────────────────────────────────

    @Test
    void touchLastUsed_uses_exact_sql_and_binding_order() throws SQLException {
        Instant now = Instant.parse("2026-11-06T01:02:03.456Z");

        service.touchLastUsed(ID, TENANT, now);

        verify(con).prepareStatement(
                "UPDATE SCH.api_key SET last_used_at = ? WHERE id = ? AND tenant_id = ?");
        var order = inOrder(ps);
        order.verify(ps).setTimestamp(1, Timestamp.from(now));
        order.verify(ps).setBytes(2, ID_BYTES);
        order.verify(ps).setBytes(3, TENANT_BYTES);
        order.verify(ps).executeUpdate();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void touchLastUsed_swallows_data_access_exception_and_warns() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));

        service.touchLastUsed(ID, TENANT, Instant.now());

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).isEqualTo(
                "api-key touch_last_used failed (best-effort): "
                        + "org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection");
    }

    @Test
    void touchLastUsed_does_not_swallow_non_data_access_exception() throws SQLException {
        RuntimeException boom = new IllegalArgumentException("boom");
        doThrow(boom).when(ps).setTimestamp(anyInt(), any(Timestamp.class));

        assertThatThrownBy(() -> service.touchLastUsed(ID, TENANT, Instant.now())).isSameAs(boom);
        assertThat(appender.list).isEmpty();
    }
}
