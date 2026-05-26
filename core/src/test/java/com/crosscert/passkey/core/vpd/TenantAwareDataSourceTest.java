package com.crosscert.passkey.core.vpd;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAwareDataSourceTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String TENANT_A_HEX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; // 32-char no-dash

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
    }

    @Test
    void toHexProduces32CharNoDashString() {
        // UUID has 32 hex digits and 4 dashes = 36 chars. toHex strips dashes → 32.
        String hex = TenantAwareDataSource.toHex(TENANT_A);
        assertThat(hex).hasSize(32).isEqualTo(TENANT_A_HEX);
    }

    @Test
    void clearsThenSetsTenantOnBorrowWhenContextPresent() throws Exception {
        Connection underlying = mock(Connection.class);
        CallableStatement cs = mock(CallableStatement.class);
        when(underlying.prepareCall(anyString())).thenReturn(cs);

        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenReturn(underlying);

        TenantContextHolder.set(TENANT_A);
        TenantAwareDataSource ds = new TenantAwareDataSource(delegate);
        Connection conn = ds.getConnection();

        // Defense-in-depth: clear_tenant runs first to drop any stale value,
        // then set_tenant binds the current tenant. Order matters — a
        // reversed sequence would let the set get clobbered.
        InOrder order = inOrder(underlying);
        order.verify(underlying).prepareCall(contains("clear_tenant"));
        order.verify(underlying).prepareCall(contains("set_tenant"));
        // UUID is passed as 32-char hex (no dashes) for HEXTORAW compatibility.
        verify(cs).setString(eq(1), eq(TENANT_A_HEX));
        verify(cs, times(2)).execute(); // one clear + one set
        conn.close();
    }

    @Test
    void doesNotSetTenantWhenContextAbsent() throws Exception {
        Connection underlying = mock(Connection.class);
        CallableStatement closePathCs = mock(CallableStatement.class);
        when(underlying.prepareCall(anyString())).thenReturn(closePathCs);

        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenReturn(underlying);

        TenantContextHolder.clear();
        TenantAwareDataSource ds = new TenantAwareDataSource(delegate);
        Connection conn = ds.getConnection();

        // Without a tenant, only the pre-borrow clear runs — never set_tenant.
        verify(underlying).prepareCall(contains("clear_tenant"));
        verify(underlying, never()).prepareCall(contains("set_tenant"));
        conn.close();
    }

    @Test
    void runsClearTenantOnClose() throws Exception {
        Connection underlying = mock(Connection.class);
        CallableStatement cs = mock(CallableStatement.class);
        when(underlying.prepareCall(anyString())).thenReturn(cs);

        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenReturn(underlying);

        Connection conn = new TenantAwareDataSource(delegate).getConnection();
        conn.close();

        // pre-borrow clear + post-use clear = 2 clear_tenant invocations.
        verify(underlying, times(2)).prepareCall(contains("clear_tenant"));
        verify(underlying).close();
    }

    @Test
    void doubleCloseIsIdempotent() throws Exception {
        Connection underlying = mock(Connection.class);
        CallableStatement cs = mock(CallableStatement.class);
        when(underlying.prepareCall(anyString())).thenReturn(cs);

        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenReturn(underlying);

        Connection conn = new TenantAwareDataSource(delegate).getConnection();
        conn.close();
        conn.close(); // must not re-run clear_tenant and must not delegate close again

        verify(underlying, times(2)).prepareCall(contains("clear_tenant")); // pre-borrow + post-use only
        verify(underlying, times(1)).close();
    }

    @Test
    void closesUnderlyingWhenSetupFails() throws Exception {
        Connection underlying = mock(Connection.class);
        // First prepareCall (pre-borrow clear) throws — borrow must abort
        // and return the underlying connection to the pool.
        when(underlying.prepareCall(anyString())).thenThrow(new SQLException("clear failed"));

        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenReturn(underlying);

        TenantContextHolder.set(TENANT_A);
        TenantAwareDataSource ds = new TenantAwareDataSource(delegate);

        assertThatThrownBy(ds::getConnection)
                .isInstanceOf(SQLException.class)
                .hasMessage("clear failed");
        verify(underlying).close();
    }

    @Test
    void closesUnderlyingWhenSetTenantStepFails() throws Exception {
        // Pre-clear succeeds; the set_tenant call (second prepareCall) fails.
        // Wrapper must close the underlying connection back to the pool.
        Connection underlying = mock(Connection.class);
        CallableStatement clearCs = mock(CallableStatement.class);
        CallableStatement setCs = mock(CallableStatement.class);
        when(underlying.prepareCall(contains("clear_tenant"))).thenReturn(clearCs);
        when(underlying.prepareCall(contains("set_tenant"))).thenReturn(setCs);
        doThrowOnExecute(setCs, new SQLException("set failed"));

        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenReturn(underlying);

        TenantContextHolder.set(TENANT_A);
        TenantAwareDataSource ds = new TenantAwareDataSource(delegate);

        assertThatThrownBy(ds::getConnection)
                .isInstanceOf(SQLException.class)
                .hasMessage("set failed");
        verify(underlying).close();
    }

    @Test
    void closeStillDelegatesWhenClearTenantFailsOnClose() throws Exception {
        Connection underlying = mock(Connection.class);
        // First prepareCall (pre-borrow clear) returns a normal CS;
        // second prepareCall (close-path clear) returns a CS whose
        // execute throws. The close path must log+swallow and still
        // delegate to underlying.close().
        CallableStatement preBorrowCs = mock(CallableStatement.class);
        CallableStatement closePathCs = mock(CallableStatement.class);
        when(underlying.prepareCall(anyString()))
                .thenReturn(preBorrowCs)   // first call: pre-borrow clear
                .thenReturn(closePathCs);  // second call: close-path clear
        doThrowOnExecute(closePathCs, new SQLException("close-path clear failed"));

        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenReturn(underlying);

        Connection conn = new TenantAwareDataSource(delegate).getConnection();
        conn.close();   // must not throw — close-path clear error is swallowed/logged

        verify(underlying).close();
    }

    private static void doThrowOnExecute(CallableStatement cs, SQLException e) throws SQLException {
        when(cs.execute()).thenThrow(e);
    }

    @Test
    void unwrapsInvocationTargetExceptionToSqlException() throws Exception {
        Connection underlying = mock(Connection.class);
        CallableStatement cs = mock(CallableStatement.class);
        when(underlying.prepareCall(anyString())).thenReturn(cs);
        when(underlying.getAutoCommit()).thenThrow(new SQLException("downstream"));

        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenReturn(underlying);

        Connection conn = new TenantAwareDataSource(delegate).getConnection();

        // If reflection wrapped this as UndeclaredThrowableException, the
        // test would see that instead of SQLException and fail.
        assertThatThrownBy(conn::getAutoCommit)
                .isInstanceOf(SQLException.class)
                .hasMessage("downstream");
        conn.close();
    }
}
