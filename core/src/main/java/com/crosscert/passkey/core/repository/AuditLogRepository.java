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
import java.util.List;
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

    /**
     * Phase B — per-tenant chain head 조회.
     * tenant_id 가 NULL 인 경우(PLATFORM_OPERATOR row)와 non-NULL 인 경우를
     * 모두 올바르게 처리하기 위해 JPQL is null 비교를 사용한다.
     * 인덱스 hint: audit_log_tenant_seq_ix (tenant_id, id) 로 ORDER BY id DESC LIMIT 1 최적화.
     */
    @Query("""
            select a from AuditLog a
            where (:tenantId is null and a.tenantId is null)
               or (a.tenantId = :tenantId)
            order by a.id desc
            """)
    List<AuditLog> findLatestByTenant(@Param("tenantId") UUID tenantId, Pageable limit);

    /** Read API for the audit-log page. Filters are all optional. */
    @Query("select a from AuditLog a " +
           "where (:action is null or a.action = :action) " +
           "  and (:actorId is null or a.actorId = :actorId) " +
           "  and (:tenantId is null or a.tenantId = :tenantId) " +
           "  and (:from is null or a.createdAt >= :from) " +
           "  and (:to   is null or a.createdAt <  :to) " +
           "order by a.createdAt desc, a.id desc")
    Page<AuditLog> search(@Param("action") String action,
                          @Param("actorId") UUID actorId,
                          @Param("tenantId") UUID tenantId,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable page);
}
