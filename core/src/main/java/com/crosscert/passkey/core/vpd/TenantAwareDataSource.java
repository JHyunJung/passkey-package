package com.crosscert.passkey.core.vpd;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps a HikariCP DataSource. On every borrow the wrapper always clears
 * APP_CTX first (defense-in-depth against a stale value from a previous
 * tenant), then if {@link TenantContextHolder#get()} is non-null, sets
 * the new tenant. On Connection.close(), the wrapper clears APP_CTX
 * before delegating to the underlying close so the connection rejoins
 * the pool with no tenant attribution. If any setup step fails during
 * borrow, the underlying connection is closed back to the pool so the
 * pool does not leak.
 *
 * <p>This is the Java side of the VPD bridge. Together with the V3
 * policy on credential, it provides tenant isolation. Defects in this
 * class are potential cross-tenant data exposure, so several layers of
 * cleanup are intentional rather than redundant.
 */
public class TenantAwareDataSource implements DataSource {

    private static final Logger LOG = Logger.getLogger(TenantAwareDataSource.class.getName());

    private static final String SET_SQL = "{ call APP_OWNER.ctx_pkg.set_tenant(?) }";
    private static final String CLEAR_SQL = "{ call APP_OWNER.ctx_pkg.clear_tenant() }";

    private final DataSource delegate;

    public TenantAwareDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection raw = delegate.getConnection();
        return wrap(raw);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection raw = delegate.getConnection(username, password);
        return wrap(raw);
    }

    private Connection wrap(Connection raw) throws SQLException {
        try {
            // Always clear first: if a prior borrow on the same physical
            // connection failed to clean up, this prevents inheriting
            // its tenant. clear_tenant on an unset attribute is a no-op
            // in Oracle.
            executeCall(raw, CLEAR_SQL, null);

            UUID tenantId = TenantContextHolder.get();
            if (tenantId != null) {
                // Convert UUID to 32-char hex string (no dashes) — matches
                // HEXTORAW input expected by V19's VPD policy function.
                executeCall(raw, SET_SQL, toHex(tenantId));
            }
        } catch (SQLException setup) {
            // Return the underlying connection to the pool so we do not
            // exhaust it on repeated borrow failures, then surface the
            // error to the caller.
            safelyClose(raw, setup);
            throw setup;
        }

        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ClearOnCloseHandler(raw));
    }

    private static void executeCall(Connection raw, String sql, String arg) throws SQLException {
        try (CallableStatement cs = raw.prepareCall(sql)) {
            if (arg != null) {
                cs.setString(1, arg);
            }
            cs.execute();
        }
    }

    private static void safelyClose(Connection raw, SQLException cause) {
        try {
            raw.close();
        } catch (SQLException closeError) {
            // Don't lose the original setup failure — attach the close
            // error as a suppressed exception so both surfaces in logs.
            cause.addSuppressed(closeError);
        }
    }

    private static class ClearOnCloseHandler implements InvocationHandler {
        private final Connection target;
        private volatile boolean closed = false;

        ClearOnCloseHandler(Connection target) { this.target = target; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName())) {
                if (closed) {
                    // Repeated close: idempotent no-op. Hikari sometimes
                    // close()s the proxy more than once; we do not want
                    // to issue clear_tenant or delegate close twice.
                    return null;
                }
                closed = true;
                if (!target.isClosed()) {
                    try (CallableStatement cs = target.prepareCall(CLEAR_SQL)) {
                        cs.execute();
                    } catch (SQLException clearError) {
                        // Log but do not throw: we still need to return
                        // the connection to the pool. The next borrow's
                        // pre-clear step (in wrap above) is the second
                        // line of defense against a stale APP_CTX value.
                        LOG.log(Level.WARNING,
                                "clear_tenant failed on close; pre-borrow clear will recover.",
                                clearError);
                    }
                }
                // Fall through to invokeOnTarget for the actual close.
            }
            return invokeOnTarget(method, args);
        }

        private Object invokeOnTarget(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException reflective) {
                // Surface the real exception (typically SQLException)
                // instead of UndeclaredThrowableException, preserving
                // the Connection interface contract.
                throw reflective.getCause();
            }
        }
    }

    // DataSource delegation boilerplate.
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }

    /**
     * Converts a UUID to the 32-char lowercase hex string (no dashes)
     * expected by Oracle's HEXTORAW in the VPD policy function.
     * Example: {@code 11111111-1111-1111-1111-111111111111} →
     * {@code "11111111111111111111111111111111"}.
     */
    static String toHex(UUID id) {
        return id.toString().replace("-", "");
    }
}
