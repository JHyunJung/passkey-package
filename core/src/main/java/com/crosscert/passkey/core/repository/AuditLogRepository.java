package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AuditLog;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Pessimistic-locked lookup of the latest row. Used by
     * AuditLogService.append to serialize the read-hash-then-INSERT
     * sequence so two concurrent admin actions cannot produce two
     * rows whose prev_hash both point at the same predecessor.
     *
     * <p>Orders by {@code createdAt DESC} (microsecond precision) with {@code id}
     * as tie-breaker. UUID v1 (time-ordered) id also correlates with time,
     * but {@code createdAt} is the primary insert-order proxy — consistent
     * with {@link #findAllOrdered()}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuditLog a order by a.createdAt desc, a.id desc limit 1")
    Optional<AuditLog> findLatestForUpdate();

    /**
     * For chain verification — ordered scan in append order.
     * Uses {@code createdAt ASC} (microsecond precision, same truncation
     * applied at append time) with {@code id} as tie-breaker. Under the
     * AUDIT_CHAIN_LOCK a single appender holds the lock, so concurrent
     * same-microsecond rows cannot occur in practice; the id tie-breaker
     * is belt-and-suspenders.
     */
    @Query("select a from AuditLog a order by a.createdAt asc, a.id asc")
    java.util.List<AuditLog> findAllOrdered();

    /** Read API for the audit-log page. Filters are all optional. */
    @Query("select a from AuditLog a " +
           "where (:action is null or a.action = :action) " +
           "  and (:actorId is null or a.actorId = :actorId) " +
           "  and (:from is null or a.createdAt >= :from) " +
           "  and (:to   is null or a.createdAt <  :to) " +
           "order by a.createdAt desc, a.id desc")
    Page<AuditLog> search(@Param("action") String action,
                          @Param("actorId") UUID actorId,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable page);
}
