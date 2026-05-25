package com.crosscert.passkey.app.security;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;

/**
 * Calls the V8-installed PL/SQL package APP_OWNER.api_key_lookup_pkg
 * for the two operations the auth filter needs:
 *
 * <ul>
 *   <li>{@link #findByPrefix(String)} — definer-rights SELECT that
 *       bypasses VPD because the calling session has no tenant
 *       context yet. Returns the row data needed to BCrypt-verify
 *       and set TenantContextHolder.</li>
 *   <li>{@link #touchLastUsed(long, Instant)} — UPDATE last_used_at
 *       after successful authentication. The package internally
 *       constrains the UPDATE with WHERE tenant_id = SYS_CONTEXT(),
 *       so the caller must have TenantContextHolder set first.</li>
 * </ul>
 *
 * <p>Uses the primary TenantAwareDataSource bean for both calls.
 * set_tenant is a no-op when TenantContextHolder is null (findByPrefix
 * path), and behaves normally for touchLastUsed (TenantContextHolder is
 * set by ApiKeyAuthFilter before that call).
 */
@Service
public class ApiKeyLookupService {

    private final JdbcTemplate jdbc;

    public ApiKeyLookupService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public Optional<ApiKeyAuthRow> findByPrefix(String keyPrefix) {
        return jdbc.execute((ConnectionCallback<Optional<ApiKeyAuthRow>>) conn -> {
            try (CallableStatement cs = conn.prepareCall(
                    "{ call APP_OWNER.api_key_lookup_pkg.find_by_prefix(?, ?, ?, ?, ?, ?, ?) }")) {
                cs.setString(1, keyPrefix);
                cs.registerOutParameter(2, Types.NUMERIC);     // p_found
                cs.registerOutParameter(3, Types.NUMERIC);     // p_id
                cs.registerOutParameter(4, Types.VARCHAR);     // p_tenant_id
                cs.registerOutParameter(5, Types.VARCHAR);     // p_key_hash
                cs.registerOutParameter(6, Types.TIMESTAMP_WITH_TIMEZONE); // p_expires_at
                cs.registerOutParameter(7, Types.TIMESTAMP_WITH_TIMEZONE); // p_revoked_at
                cs.execute();

                if (cs.getInt(2) == 0) return Optional.empty();
                long id = cs.getLong(3);
                String tenantId = cs.getString(4);
                String keyHash = cs.getString(5);
                Timestamp expTs = cs.getTimestamp(6);
                Timestamp revTs = cs.getTimestamp(7);
                Instant expiresAt = expTs == null ? null : expTs.toInstant();
                Instant revokedAt = revTs == null ? null : revTs.toInstant();
                return Optional.of(new ApiKeyAuthRow(id, tenantId, keyHash, expiresAt, revokedAt));
            }
        });
    }

    public void touchLastUsed(long apiKeyId, Instant now) {
        // Best-effort: a touch failure must NOT turn a valid auth into
        // 500. Catch DataAccessException at the OUTER call site —
        // JdbcTemplate translates SQLException to DataAccessException
        // after the ConnectionCallback returns.
        try {
            jdbc.execute((ConnectionCallback<Void>) conn -> {
                try (CallableStatement cs = conn.prepareCall(
                        "{ call APP_OWNER.api_key_lookup_pkg.touch_last_used(?, ?) }")) {
                    cs.setLong(1, apiKeyId);
                    cs.setTimestamp(2, Timestamp.from(now));
                    cs.execute();
                    return null;
                }
            });
        } catch (DataAccessException e) {
            // Phase 2 may emit a metric here. For Phase 1, silent swallow
            // is acceptable; last_used_at stays stale by at most one tx.
        }
    }

    /** Minimal row data the filter needs from V8's package. */
    public record ApiKeyAuthRow(
            long id, String tenantId, String keyHash,
            Instant expiresAt, Instant revokedAt) {

        public boolean isActive(Instant now) {
            if (revokedAt != null) return false;
            if (expiresAt != null && !expiresAt.isAfter(now)) return false;
            return true;
        }
    }
}
