package com.crosscert.passkey.app.security;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.CallableStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Calls the V8-installed PL/SQL package APP_OWNER.api_key_lookup_pkg
 * for the two operations the auth filter needs:
 *
 * <ul>
 *   <li>{@link #findByPrefix(String)} — definer-rights SELECT that
 *       bypasses VPD because the calling session has no tenant
 *       context yet. Returns the row data needed to BCrypt-verify
 *       and set TenantContextHolder.</li>
 *   <li>{@link #touchLastUsed(UUID, Instant)} — UPDATE last_used_at
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
                cs.registerOutParameter(2, Types.NUMERIC);      // p_found
                cs.registerOutParameter(3, Types.BINARY);       // p_id (RAW(16))
                cs.registerOutParameter(4, Types.BINARY);       // p_tenant_id (RAW(16))
                cs.registerOutParameter(5, Types.VARCHAR);      // p_key_hash
                cs.registerOutParameter(6, Types.TIMESTAMP_WITH_TIMEZONE); // p_expires_at
                cs.registerOutParameter(7, Types.TIMESTAMP_WITH_TIMEZONE); // p_revoked_at
                cs.execute();

                if (cs.getInt(2) == 0) return Optional.empty();
                UUID id = toUuidOrFail(cs.getBytes(3), "p_id");
                UUID tenantId = toUuidOrFail(cs.getBytes(4), "p_tenant_id");
                String keyHash = cs.getString(5);
                Timestamp expTs = cs.getTimestamp(6);
                Timestamp revTs = cs.getTimestamp(7);
                Instant expiresAt = expTs == null ? null : expTs.toInstant();
                Instant revokedAt = revTs == null ? null : revTs.toInstant();
                return Optional.of(new ApiKeyAuthRow(id, tenantId, keyHash, expiresAt, revokedAt));
            }
        });
    }

    public void touchLastUsed(UUID apiKeyId, Instant now) {
        // Best-effort: a touch failure must NOT turn a valid auth into
        // 500. Catch DataAccessException at the OUTER call site —
        // JdbcTemplate translates SQLException to DataAccessException
        // after the ConnectionCallback returns.
        try {
            jdbc.execute((ConnectionCallback<Void>) conn -> {
                try (CallableStatement cs = conn.prepareCall(
                        "{ call APP_OWNER.api_key_lookup_pkg.touch_last_used(?, ?) }")) {
                    cs.setBytes(1, toBytes(apiKeyId));
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
            UUID id, UUID tenantId, String keyHash,
            Instant expiresAt, Instant revokedAt) {

        public boolean isActive(Instant now) {
            if (revokedAt != null) return false;
            if (expiresAt != null && !expiresAt.isAfter(now)) return false;
            return true;
        }
    }

    /**
     * Converts a 16-byte Oracle RAW value to a UUID.
     * Throws if bytes is null or not exactly 16 bytes — fail-closed to prevent
     * a malformed p_found=1 response from proceeding with null tenant context.
     */
    private static UUID toUuidOrFail(byte[] bytes, String paramName) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalStateException(
                    "api_key_lookup_pkg returned malformed RAW for " + paramName
                    + ": expected 16 bytes, got " + (bytes == null ? "null" : bytes.length));
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
    }

    /**
     * Converts a UUID to a 16-byte array suitable for Oracle RAW(16) binding.
     */
    private static byte[] toBytes(UUID uuid) {
        if (uuid == null) return null;
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }
}
