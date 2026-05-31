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

    /**
     * Phase B Task 4 — per-tenant chain 검증용 전체 스캔 (id ASC).
     * tenantId 가 null 인 경우(PLATFORM_OPERATOR row) 와 non-null 인 경우를 모두 처리.
     */
    @Query("""
            select a from AuditLog a
            where (:tenantId is null and a.tenantId is null)
               or (a.tenantId = :tenantId)
            order by a.id asc
            """)
    List<AuditLog> findAllByTenantOrdered(@Param("tenantId") UUID tenantId);

    /**
     * Phase B Task 4 — 백필 및 검증에 필요한 distinct tenant_id 목록.
     * NULL tenant_id (PLATFORM_OPERATOR row) 도 포함된다.
     * NOTE: nativeQuery=true 는 Oracle RAW(16) 를 byte[] 로 반환하므로
     * JPQL (Hibernate 가 UUID 변환 처리) 을 사용한다.
     */
    @Query("select distinct a.tenantId from AuditLog a")
    List<UUID> findDistinctTenantIds();

    /**
     * Phase F2 — TenantAdminService.toView() 의 lastEventAt 집계용.
     * tenant 별 가장 최근 audit log 1건 (createdAt DESC). Spring Data derived
     * query 가 select ... where tenant_id = :tenantId order by created_at desc fetch first 1 row only
     * 로 자동 변환된다.
     */
    Optional<AuditLog> findFirstByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /**
     * Phase QW-3 — TenantAdminService.list() N+1 제거용 배치 집계.
     * findFirstByTenantIdOrderByCreatedAtDesc(tenantId) 는 tenant 의 최신 audit row 를
     * 반환하고 toView 는 거기서 createdAt 만 읽는다. 따라서 배치 등가물은 tenant_id 별
     * MAX(createdAt). 결과는 Object[]{UUID tenantId, Instant maxCreatedAt}. audit row 가
     * 없는 tenant 는 결과 행이 없으므로 호출부에서 null 처리(기존 .orElse(null) 와 동일).
     * tenant_id IS NULL 인 PLATFORM row 는 group key 가 null 이라 tenant lookup 에서
     * 자연히 무시된다.
     */
    @Query("select a.tenantId, max(a.createdAt) from AuditLog a where a.tenantId is not null group by a.tenantId")
    java.util.List<Object[]> findLatestCreatedAtGroupedByTenantId();

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

    /**
     * Phase F3 — FunnelService stage attempt/success counters.
     * Spring Data derived query: select count(*) from audit_log
     *   where tenant_id = ? and action = ? and created_at > ?.
     */
    long countByTenantIdAndActionAndCreatedAtAfter(UUID tenantId, String action, Instant since);

    /**
     * Phase F3 — FunnelService daily series.
     * Native query: groups by TRUNC(created_at) (day bucket, UTC server tz) and action.
     * Returns [day TIMESTAMP, action VARCHAR, count NUMBER] rows.
     * Native because JPQL has no portable TRUNC(date) and Oracle dialect varies.
     * Hibernate's @JdbcTypeCode(SqlTypes.UUID) on AuditLog.tenantId handles RAW(16) ↔ UUID binding.
     */
    @Query(value = "SELECT TRUNC(created_at), action, COUNT(*) "
                 + "FROM {h-schema}audit_log "
                 + "WHERE tenant_id = :tenantId AND action IN (:actions) AND created_at >= :since "
                 + "GROUP BY TRUNC(created_at), action",
           nativeQuery = true)
    List<Object[]> aggregateDailyByTenantAndActions(
            @Param("tenantId") UUID tenantId,
            @Param("actions") List<String> actions,
            @Param("since") Instant since);

    /**
     * Phase F3 — FunnelService by-event-type breakdown.
     * Native query: groups by action.
     * Returns [action VARCHAR, count NUMBER] rows.
     */
    @Query(value = "SELECT action, COUNT(*) "
                 + "FROM {h-schema}audit_log "
                 + "WHERE tenant_id = :tenantId AND action IN (:actions) AND created_at >= :since "
                 + "GROUP BY action",
           nativeQuery = true)
    List<Object[]> aggregateByTenantAndActionsGrouped(
            @Param("tenantId") UUID tenantId,
            @Param("actions") List<String> actions,
            @Param("since") Instant since);
}
