package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AuditLog;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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
     * as tie-breaker. {@code createdAt} is the insert-order key: AuditLogService
     * makes it strictly increasing along the chain. The id must NOT be used as
     * the order key — Hibernate's TIME-style UUID changes its middle bits on
     * every JVM start, so ids from a restarted app can sort before older rows.
     *
     * <p>The sort key is written as {@code SYS_EXTRACT_UTC(createdAt)} so it
     * matches the V6 function-based index
     * {@code audit_log_created_id_ix (SYS_EXTRACT_UTC(created_at), id)} exactly.
     * Oracle cannot use that index to satisfy a plain {@code ORDER BY created_at}
     * on a TIMESTAMP WITH TIME ZONE column (measured: full scan + WINDOW SORT even
     * with an INDEX_DESC hint), but it does for the identical expression, so the
     * AUDIT_CHAIN_LOCK is no longer held across a full-table scan + sort.
     * Measured on 20,005 rows: buffer gets 376 → 92 (TABLE ACCESS FULL + WINDOW SORT
     * → INDEX FULL SCAN DESCENDING, no sort). It is still O(n): Hibernate renders the
     * locked {@code limit 1} as {@code id in (… fetch first 1 rows only) for update},
     * and Oracle's semi-join unnesting turns the FETCH FIRST into WINDOW BUFFER
     * without STOPKEY, so the whole index is read. A ROWNUM native form
     * ({@code where rownum <= 1} in the subquery) reaches COUNT STOPKEY and reads one
     * index entry (5 gets) — left as a follow-up.
     * The order is the same as {@code createdAt desc}:
     * Oracle compares TSTZ values as UTC instants, which is exactly what
     * SYS_EXTRACT_UTC returns ({@code AuditLogOrderIndexIT} pins this, including
     * mixed offsets and the id tie-breaker). Oracle-only JPQL — every caller runs
     * against Oracle (no H2 profile exists).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuditLog a order by function('SYS_EXTRACT_UTC', a.createdAt) desc, a.id desc limit 1")
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
     *
     * <p>순서는 global chain({@link #findLatestForUpdate()})과 같은 createdAt DESC, id DESC.
     * 예전에는 id DESC 였는데, TIME 방식 UUID 는 앱을 재시작할 때마다 가운데 자리가 바뀌어
     * 재시작 뒤 row 가 이전 row 보다 작게 정렬될 수 있다 → 같은 직전 row 를 두 번 이어 체인이
     * 갈라지고 검증이 위변조로 판정했다. 인덱스: audit_log_tenant_ix (tenant_id, created_at).
     */
    @Query("""
            select a from AuditLog a
            where (:tenantId is null and a.tenantId is null)
               or (a.tenantId = :tenantId)
            order by a.createdAt desc, a.id desc
            """)
    List<AuditLog> findLatestByTenant(@Param("tenantId") UUID tenantId, Pageable limit);

    /**
     * Phase B Task 4 — per-tenant chain 검증·백필용 전체 스캔 (append 순서 = createdAt ASC, id ASC).
     * tenantId 가 null 인 경우(PLATFORM_OPERATOR row) 와 non-null 인 경우를 모두 처리.
     * {@link #findLatestByTenant} 와 같은 순서여야 append 가 이은 체인을 검증이 같은 순서로 따라간다.
     */
    @Query("""
            select a from AuditLog a
            where (:tenantId is null and a.tenantId is null)
               or (a.tenantId = :tenantId)
            order by a.createdAt asc, a.id asc
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
     * MAX(createdAt). 결과는 Object[]{UUID tenantId, OffsetDateTime maxCreatedAt}. audit row 가
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
                          @Param("from") OffsetDateTime from,
                          @Param("to") OffsetDateTime to,
                          Pageable page);

    /**
     * Phase F3 — FunnelService stage attempt/success counters.
     * Spring Data derived query: select count(*) from audit_log
     *   where tenant_id = ? and action = ? and created_at > ?.
     */
    long countByTenantIdAndActionAndCreatedAtAfter(UUID tenantId, String action, OffsetDateTime since);

    /**
     * Phase F3 — FunnelService daily series.
     * Native query: groups by TRUNC(created_at) (day bucket in the DB session time zone, KST) and action.
     * Returns [day, action VARCHAR, count NUMBER] rows; day is an Oracle DATE that Hibernate 7 returns
     * as {@code LocalDateTime} — convert with {@code KstTime.dbDay}.
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
            @Param("since") OffsetDateTime since);

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
            @Param("since") OffsetDateTime since);
}
