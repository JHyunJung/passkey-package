package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AuditLog;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only projection of AUDIT_LOG for the Activity dashboard.
 *
 * <p>This is a separate {@code Repository} interface (not extending
 * {@link AuditLogRepository}) to keep dashboard queries from polluting the
 * append/verify code path. {@code Repository} (not {@code JpaRepository}) so we
 * expose only what's needed.
 *
 * <p>The {@code feedRaw} / {@code feedFilteredRaw} WHERE clauses compare against
 * the {@code (createdAt, id)} tuple of {@code sinceId}'s row, NOT just createdAt.
 * This is required because Oracle TIMESTAMP precision is microseconds — two rows
 * can share a timestamp under heavy ADMIN_LOGIN bursts. ORDER BY uses the same
 * tuple so filter + sort are aligned, and the polling client never misses a row.
 */
public interface ActivityRepository extends Repository<AuditLog, UUID> {

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.createdAt >= :since")
    long countSince(@Param("since") Instant since);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.createdAt >= :since AND a.action IN :actions")
    long countByActionsSince(@Param("actions") Set<String> actions,
                              @Param("since") Instant since);

    @Query("""
        SELECT new com.crosscert.passkey.core.repository.ActivityRepository$TenantRow(a.tenantId, COUNT(a))
        FROM AuditLog a
        WHERE a.createdAt >= :since AND a.tenantId IS NOT NULL
        GROUP BY a.tenantId
        ORDER BY COUNT(a) DESC
    """)
    List<TenantRow> topTenantsSinceRaw(@Param("since") Instant since, Pageable limit);

    default List<TenantRow> topTenantsSince(Instant since, int n) {
        return topTenantsSinceRaw(since, PageRequest.of(0, n));
    }

    @Query("""
        SELECT a FROM AuditLog a
        WHERE :sinceId IS NULL
           OR a.createdAt > (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
           OR (a.createdAt = (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
               AND a.id > (SELECT b.id FROM AuditLog b WHERE b.id = :sinceId))
        ORDER BY a.createdAt DESC, a.id DESC
    """)
    List<AuditLog> feedRaw(@Param("sinceId") UUID sinceId, Pageable limit);

    default List<AuditLog> feed(UUID sinceId, int n) {
        return feedRaw(sinceId, PageRequest.of(0, n));
    }

    @Query("""
        SELECT a FROM AuditLog a
        WHERE a.action IN :actions
          AND (:sinceId IS NULL
               OR a.createdAt > (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
               OR (a.createdAt = (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
                   AND a.id > (SELECT b.id FROM AuditLog b WHERE b.id = :sinceId)))
        ORDER BY a.createdAt DESC, a.id DESC
    """)
    List<AuditLog> feedFilteredRaw(@Param("actions") Set<String> actions,
                                   @Param("sinceId") UUID sinceId, Pageable limit);

    default List<AuditLog> feedFiltered(Set<String> actions, UUID sinceId, int n) {
        return feedFilteredRaw(actions, sinceId, PageRequest.of(0, n));
    }

    record TenantRow(UUID tenantId, long count) {}
}
