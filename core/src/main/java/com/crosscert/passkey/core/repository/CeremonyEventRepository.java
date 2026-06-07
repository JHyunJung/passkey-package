package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.CeremonyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ceremony_event 집계 쿼리. admin FunnelService 가 등록/인증 성공률 산출에 쓴다.
 * 메서드 시그니처는 AuditLogRepository 의 funnel 쿼리와 동일해, FunnelService 가
 * 데이터 소스를 본문 변경 없이 교체할 수 있다.
 */
public interface CeremonyEventRepository extends JpaRepository<CeremonyEvent, UUID> {

    /** stage attempt/success 카운터: tenant + action 의 since 이후 행 수. */
    long countByTenantIdAndActionAndCreatedAtAfter(UUID tenantId, String action, Instant since);

    /** 일별 series. 반환 행: [day TIMESTAMP, action VARCHAR, count NUMBER]. */
    @Query(value = "SELECT TRUNC(created_at), action, COUNT(*) "
                 + "FROM {h-schema}ceremony_event "
                 + "WHERE tenant_id = :tenantId AND action IN (:actions) AND created_at >= :since "
                 + "GROUP BY TRUNC(created_at), action",
           nativeQuery = true)
    List<Object[]> aggregateDailyByTenantAndActions(
            @Param("tenantId") UUID tenantId,
            @Param("actions") List<String> actions,
            @Param("since") Instant since);

    /** action 별 카운트. 반환 행: [action VARCHAR, count NUMBER]. */
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
