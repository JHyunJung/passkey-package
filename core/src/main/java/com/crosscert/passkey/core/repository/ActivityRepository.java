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
        WHERE (:tenantId IS NULL OR a.tenantId = :tenantId)
          AND (:sinceId IS NULL
               OR a.createdAt > (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
               OR (a.createdAt = (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
                   AND a.id > (SELECT b.id FROM AuditLog b WHERE b.id = :sinceId)))
        ORDER BY a.createdAt DESC, a.id DESC
    """)
    List<AuditLog> feedRaw(@Param("sinceId") UUID sinceId,
                           @Param("tenantId") UUID tenantId,
                           Pageable limit);

    default List<AuditLog> feed(UUID sinceId, int n) {
        return feedRaw(sinceId, null, PageRequest.of(0, n));
    }

    /** Phase F5 — sinceId polling restricted to a single tenant. */
    default List<AuditLog> feed(UUID sinceId, UUID tenantId, int n) {
        return feedRaw(sinceId, tenantId, PageRequest.of(0, n));
    }

    @Query("""
        SELECT a FROM AuditLog a
        WHERE a.action IN :actions
          AND (:tenantId IS NULL OR a.tenantId = :tenantId)
          AND (:sinceId IS NULL
               OR a.createdAt > (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
               OR (a.createdAt = (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
                   AND a.id > (SELECT b.id FROM AuditLog b WHERE b.id = :sinceId)))
        ORDER BY a.createdAt DESC, a.id DESC
    """)
    List<AuditLog> feedFilteredRaw(@Param("actions") Set<String> actions,
                                   @Param("sinceId") UUID sinceId,
                                   @Param("tenantId") UUID tenantId,
                                   Pageable limit);

    default List<AuditLog> feedFiltered(Set<String> actions, UUID sinceId, int n) {
        return feedFilteredRaw(actions, sinceId, null, PageRequest.of(0, n));
    }

    /** Phase F5 — sinceId+category polling restricted to a single tenant. */
    default List<AuditLog> feedFiltered(Set<String> actions, UUID sinceId, UUID tenantId, int n) {
        return feedFilteredRaw(actions, sinceId, tenantId, PageRequest.of(0, n));
    }

    /**
     * Phase F5 — backward pagination + tenant scoping for the Activity feed.
     *
     * <p>Both filters are nullable so a single query covers four combinations:
     * <ul>
     *   <li>{@code before=null, tenantId=null}: equivalent to {@link #feed(UUID, int)}
     *       with no sinceId (newest 50). Kept for symmetry — service layer picks
     *       whichever entry point matches its branch.</li>
     *   <li>{@code before != null}: rows strictly older than the supplied instant
     *       (paginate backward through history). Order remains
     *       {@code createdAt DESC, id DESC}.</li>
     *   <li>{@code tenantId != null}: restrict to a single tenant's rows.</li>
     *   <li>both: combined.</li>
     * </ul>
     *
     * <p>Unlike {@link #feedRaw(UUID, Pageable)}, this variant compares against an
     * {@link Instant} directly (no subquery to look up the cursor row), because
     * {@code before} comes from the client as an ISO-8601 timestamp on the URL.
     * Tuple-precision concerns ({@code (createdAt, id)}) do not apply for
     * paginate-backward — the client passes the oldest visible {@code createdAt}
     * and a tiny duplicate-row overlap is acceptable for a 24h dashboard.
     *
     * <p>The {@code action IN} filter is intentionally omitted here. Phase F5 wires
     * {@code ?before=} / {@code ?tenantId=} only for the unfiltered feed path; if a
     * combined {@code category} + {@code before} flow is needed later, a parallel
     * {@code feedFilteredPageRaw} variant should be added rather than overloading
     * this one with more nullable params.
     */
    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:tenantId IS NULL OR a.tenantId = :tenantId)
          AND (:before IS NULL OR a.createdAt < :before)
        ORDER BY a.createdAt DESC, a.id DESC
    """)
    List<AuditLog> feedPageRaw(@Param("tenantId") UUID tenantId,
                               @Param("before") Instant before,
                               Pageable limit);

    default List<AuditLog> feedPage(UUID tenantId, Instant before, int n) {
        return feedPageRaw(tenantId, before, PageRequest.of(0, n));
    }

    /**
     * Phase F5 — {@link #feedPage(UUID, Instant, int)} with action category filter.
     * Mirrors {@link #feedFilteredRaw} but uses the (before, tenantId) cursor
     * pattern instead of (sinceId).
     */
    @Query("""
        SELECT a FROM AuditLog a
        WHERE a.action IN :actions
          AND (:tenantId IS NULL OR a.tenantId = :tenantId)
          AND (:before IS NULL OR a.createdAt < :before)
        ORDER BY a.createdAt DESC, a.id DESC
    """)
    List<AuditLog> feedFilteredPageRaw(@Param("actions") Set<String> actions,
                                       @Param("tenantId") UUID tenantId,
                                       @Param("before") Instant before,
                                       Pageable limit);

    default List<AuditLog> feedFilteredPage(Set<String> actions, UUID tenantId, Instant before, int n) {
        return feedFilteredPageRaw(actions, tenantId, before, PageRequest.of(0, n));
    }

    record TenantRow(UUID tenantId, long count) {}
}
