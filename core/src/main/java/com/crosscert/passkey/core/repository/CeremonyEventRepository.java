package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.CeremonyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CeremonyEventRepository extends JpaRepository<CeremonyEvent, UUID> {

    /** stage attempt/success 카운터. */
    long countByTenantIdAndActionAndCreatedAtAfter(UUID tenantId, String action, Instant since);

    /** 일별 series. [day TIMESTAMP, action VARCHAR, count NUMBER]. */
    @Query(value = "SELECT TRUNC(created_at), action, COUNT(*) "
                 + "FROM {h-schema}ceremony_event "
                 + "WHERE tenant_id = :tenantId AND action IN (:actions) AND created_at >= :since "
                 + "GROUP BY TRUNC(created_at), action",
           nativeQuery = true)
    List<Object[]> aggregateDailyByTenantAndActions(
            @Param("tenantId") UUID tenantId,
            @Param("actions") List<String> actions,
            @Param("since") Instant since);

    /** action 별 카운트. [action VARCHAR, count NUMBER]. */
    @Query(value = "SELECT action, COUNT(*) "
                 + "FROM {h-schema}ceremony_event "
                 + "WHERE tenant_id = :tenantId AND action IN (:actions) AND created_at >= :since "
                 + "GROUP BY action",
           nativeQuery = true)
    List<Object[]> aggregateByTenantAndActionsGrouped(
            @Param("tenantId") UUID tenantId,
            @Param("actions") List<String> actions,
            @Param("since") Instant since);
}
