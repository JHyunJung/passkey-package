package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.TenantWebauthnSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface TenantWebauthnSnapshotRepository extends JpaRepository<TenantWebauthnSnapshot, Long> {

    List<TenantWebauthnSnapshot> findByTenantIdOrderByTakenAtDesc(UUID tenantId);

    /**
     * P1-4 retention: taken_at 이 cutoff 이전인 스냅샷 삭제(append-only 이력 정리).
     *
     * <p>Batched: ROWNUM 캡(AdminUserInvitationRepository 참고). nativeQuery + 실제 컬럼명.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM {h-schema}tenant_webauthn_snapshot WHERE id IN ("
         + "SELECT id FROM {h-schema}tenant_webauthn_snapshot WHERE "
         + "taken_at < :cutoff "
         + "AND ROWNUM <= :batchSize)", nativeQuery = true)
    int deleteTakenBefore(@Param("cutoff") OffsetDateTime cutoff, @Param("batchSize") int batchSize);
}
