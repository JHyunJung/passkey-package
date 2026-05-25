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

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Pessimistic-locked lookup of the latest row. Used by
     * AuditLogService.append to serialize the read-hash-then-INSERT
     * sequence so two concurrent admin actions cannot produce two
     * rows whose prev_hash both point at the same predecessor.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuditLog a where a.id = (select max(a2.id) from AuditLog a2)")
    Optional<AuditLog> findLatestForUpdate();

    /** For chain verification — ordered scan starting from id=1. */
    @Query("select a from AuditLog a order by a.id asc")
    java.util.List<AuditLog> findAllOrdered();

    /** Read API for the audit-log page. Filters are all optional. */
    @Query("select a from AuditLog a " +
           "where (:action is null or a.action = :action) " +
           "  and (:actorId is null or a.actorId = :actorId) " +
           "  and (:from is null or a.createdAt >= :from) " +
           "  and (:to   is null or a.createdAt <  :to) " +
           "order by a.id desc")
    Page<AuditLog> search(@Param("action") String action,
                          @Param("actorId") Long actorId,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable page);
}
