package com.crosscert.passkey.app.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves API keys for the two operations the auth filter needs, using
 * direct {@link JdbcTemplate} SQL (no PL/SQL package, no VPD).
 *
 * <ul>
 *   <li>{@link #findByPrefix(String)} — native SELECT by the globally-unique
 *       {@code key_prefix}. Authentication runs BEFORE any tenant context is
 *       set, so the lookup must work without one. A JdbcTemplate query does NOT
 *       pass through Hibernate's {@code @Filter} (the app-level tenant isolation
 *       mechanism), so it sees every tenant's rows — exactly what is needed to
 *       resolve a tenant-agnostic prefix. {@code key_prefix} is UNIQUE (V7), so
 *       at most one row matches.</li>
 *   <li>{@link #touchLastUsed(UUID, UUID, Instant)} — native UPDATE of
 *       {@code last_used_at} after successful authentication. The UPDATE is
 *       constrained by both {@code id} AND {@code tenant_id} so a missing
 *       context can never be treated as authority over another tenant's row.</li>
 * </ul>
 *
 * <p>Previously these two calls went through a definer-rights PL/SQL package
 * ({@code APP_OWNER.api_key_lookup_pkg}) whose only purpose was to bypass the
 * Oracle VPD predicate ({@code tenant_id = SYS_CONTEXT(...)}) that would
 * otherwise return zero rows when no context was set. With VPD removed the
 * bypass is unnecessary: a plain SQL query already sees all rows, so the
 * package indirection is gone. Tenant isolation for the request path is
 * provided by the app-level Hibernate {@code @Filter}; this auth lookup runs
 * before that filter applies, by design.
 *
 * <p>The {@link ApiKeyAuthRow} record and the RAW(16) ↔ UUID helpers are
 * unchanged — the public method signatures are stable, so {@code ApiKeyAuthFilter}
 * needs no changes.
 */
@Slf4j
@Service
public class ApiKeyLookupService {

    private final JdbcTemplate jdbc;

    public ApiKeyLookupService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public Optional<ApiKeyAuthRow> findByPrefix(String keyPrefix) {
        // 인증 시점에는 아직 tenant context 가 없다. key_prefix 는 전역 UNIQUE(V7)
        // 이므로 tenant 없이 1행 룩업이 정확하다. JdbcTemplate 의 직접 SQL 은
        // Hibernate @Filter(ORM 레벨) 를 거치지 않으므로 모든 테넌트의 행을 본다.
        // 테이블은 APP_OWNER.api_key 로 명시 — hibernate.default_schema 는 ORM 에만
        // 적용되고 raw JDBC 의 미수식 이름은 로그인 사용자 스키마로 해석되기 때문이다
        // (AuditLogService 의 ad-hoc native 쿼리와 동일 규칙).
        return jdbc.query(
                "SELECT id, tenant_id, key_hash, expires_at, revoked_at "
              + "FROM APP_OWNER.api_key WHERE key_prefix = ?",
                ps -> ps.setString(1, keyPrefix),
                rs -> {
                    if (!rs.next()) return Optional.empty();
                    UUID id = toUuidOrFail(rs.getBytes("id"), "id");
                    UUID tenantId = toUuidOrFail(rs.getBytes("tenant_id"), "tenant_id");
                    String keyHash = rs.getString("key_hash");
                    Timestamp expTs = rs.getTimestamp("expires_at");
                    Timestamp revTs = rs.getTimestamp("revoked_at");
                    Instant expiresAt = expTs == null ? null : expTs.toInstant();
                    Instant revokedAt = revTs == null ? null : revTs.toInstant();
                    return Optional.of(new ApiKeyAuthRow(id, tenantId, keyHash, expiresAt, revokedAt));
                });
    }

    public void touchLastUsed(UUID apiKeyId, UUID tenantId, Instant now) {
        // Best-effort: touch 실패가 유효 인증을 500 으로 만들면 안 된다.
        // tenant_id 를 WHERE 에 명시 — context 부재를 인가로 취급하지 않는다(타 테넌트
        // 행 변조 차단). VPD 제거 후에도 격리 의미를 명시 검증으로 유지.
        try {
            jdbc.update(
                    "UPDATE APP_OWNER.api_key SET last_used_at = ? WHERE id = ? AND tenant_id = ?",
                    ps -> {
                        ps.setTimestamp(1, Timestamp.from(now));
                        ps.setBytes(2, toBytes(apiKeyId));
                        ps.setBytes(3, toBytes(tenantId));
                    });
        } catch (DataAccessException e) {
            // last_used_at 가 한 트랜잭션만큼 stale 해질 뿐이다. 지속 실패(= stale-key
            // 탐지 저하)는 보여야 하므로 로그만 남기고 인증은 성공으로 통과시킨다.
            log.warn("api-key touch_last_used failed (best-effort): {}", e.toString());
        }
    }

    /** Minimal row data the filter needs from the api_key table. */
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
     * a malformed row from proceeding with null tenant context.
     */
    private static UUID toUuidOrFail(byte[] bytes, String columnName) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalStateException(
                    "api_key returned malformed RAW for " + columnName
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
