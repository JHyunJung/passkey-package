package com.crosscert.passkey.core.vpd;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/**
 * Test-only helper that drives APP_RUNTIME_USER traffic through the same
 * {@link TenantAwareDataSource} bridge the app uses in production.
 *
 * <p>The Spring-managed primary DataSource connects as APP_ADMIN_USER
 * (EXEMPT ACCESS POLICY) so Flyway can apply migrations and so the
 * "appAdminBypass" scenario can confirm rows really exist. VPD is only
 * exercised when the session is APP_RUNTIME_USER, so this helper builds
 * its own Hikari pool authenticated as APP_RUNTIME_USER and wraps it in
 * a {@link TenantAwareDataSource} — the same wrapper the real apps use.
 *
 * <p>Cross-tenant INSERT must use raw JDBC (this helper), not JPA save,
 * so the ORA-28115 exception fires synchronously inside the assertion
 * lambda rather than at JPA's lazy flush.
 */
@Component
public class RuntimeDsHelper {

    private final HikariDataSource runtimePhysical;
    private final JdbcTemplate runtimeJdbc;

    public RuntimeDsHelper(@Value("${spring.datasource.url}") String url) {
        // Build a dedicated physical pool authenticated as APP_RUNTIME_USER.
        // VPD policies only fire for APP_RUNTIME — APP_ADMIN holds
        // EXEMPT ACCESS POLICY, so reusing the primary DataSource would
        // silently bypass the predicate and the test would prove nothing.
        this.runtimePhysical = new HikariDataSource();
        this.runtimePhysical.setJdbcUrl(url);
        this.runtimePhysical.setUsername("APP_RUNTIME_USER");
        this.runtimePhysical.setPassword("runtime_pw");
        this.runtimePhysical.setMaximumPoolSize(2);
        this.runtimePhysical.setMinimumIdle(1);
        this.runtimePhysical.setPoolName("vpd-it-runtime-pool");

        DataSource wrapped = new TenantAwareDataSource(runtimePhysical);
        this.runtimeJdbc = new JdbcTemplate(wrapped);
    }

    @PreDestroy
    public void close() {
        runtimePhysical.close();
    }

    /**
     * SELECT all credential rows visible to APP_RUNTIME under the given
     * session tenant ({@code null} → no APP_CTX value, VPD filters all
     * rows out as the safe default).
     */
    public List<Object[]> selectAllCredentialsAsRuntime(UUID tenantId) {
        try {
            if (tenantId == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.set(tenantId);
            }
            return runtimeJdbc.query(
                    "SELECT RAWTOHEX(id) AS id, RAWTOHEX(tenant_id) AS tenant_id FROM APP_OWNER.credential ORDER BY id",
                    (rs, i) -> new Object[]{rs.getString("id"), rs.getString("tenant_id")});
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * INSERT a credential row as APP_RUNTIME_USER. {@code sessionTenant}
     * sets APP_CTX; {@code rowTenant} is the hex-encoded tenant UUID
     * written to the row's tenant_id RAW(16) column. When the two differ,
     * VPD's update_check=TRUE must raise ORA-28115 (policy violation).
     */
    public void insertCredentialAs(UUID sessionTenant, String rowTenant,
                                   String userHandle, String credentialId, String cosePublicKey) {
        try {
            TenantContextHolder.set(sessionTenant);
            runtimeJdbc.update(
                    "INSERT INTO APP_OWNER.credential " +
                    "(id, tenant_id, user_handle, credential_id, cose_public_key) " +
                    "VALUES (SYS_GUID(), HEXTORAW(?), " +
                    "UTL_RAW.CAST_TO_RAW(?), UTL_RAW.CAST_TO_RAW(?), UTL_RAW.CAST_TO_RAW(?))",
                    rowTenant, userHandle, credentialId, cosePublicKey);
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * COUNT rows in the given table as APP_RUNTIME_USER with no tenant context.
     *
     * <p>Used to verify VPD behavior for a session with no tenant context.
     * As of P0-1 (V35) the tenant child config tables
     * ({@code tenant_allowed_origin}, {@code tenant_accepted_format}, ...) ARE
     * VPD-protected, so — like {@code credential} — they return 0 rows when no
     * context is set.
     *
     * @param qualifiedTable fully-qualified table name, e.g. {@code APP_OWNER.tenant_allowed_origin}
     */
    public long countAsRuntimeNoContext(String qualifiedTable) {
        TenantContextHolder.clear();
        try {
            Long count = runtimeJdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + qualifiedTable, Long.class);
            return count == null ? 0L : count;
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * COUNT rows in the given table as APP_RUNTIME_USER WITH the given tenant
     * set as APP_CTX (the positive VPD path).
     *
     * <p>Mirrors {@link #countAsRuntimeNoContext(String)} but sets the tenant
     * context first, so the V35 predicate evaluates to
     * {@code tenant_id = HEXTORAW(SYS_CONTEXT('APP_CTX','TENANT_ID'))} rather
     * than {@code 1=0}. Used to prove the predicate actually MATCHES rows for
     * the session tenant — not just that it hides everything when no context
     * is set. Without this positive assertion the negative ("0 rows when no
     * context") could pass for a table that is simply empty, proving nothing.
     *
     * @param qualifiedTable fully-qualified table name, e.g. {@code APP_OWNER.tenant_aaguid_policy}
     * @param tenantId       tenant UUID to set as APP_CTX before the count
     */
    public long countAsRuntimeWithContext(String qualifiedTable, UUID tenantId) {
        TenantContextHolder.set(tenantId);
        try {
            Long count = runtimeJdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + qualifiedTable, Long.class);
            return count == null ? 0L : count;
        } finally {
            TenantContextHolder.clear();
        }
    }
}
