package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.TenantWebauthnSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TenantWebauthnSnapshotRepository extends JpaRepository<TenantWebauthnSnapshot, Long> {

    List<TenantWebauthnSnapshot> findByTenantIdOrderByTakenAtDesc(UUID tenantId);

    /**
     * P1-4 retention: taken_at 이 cutoff 이전인 스냅샷 삭제(append-only 이력 정리).
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("delete from TenantWebauthnSnapshot s where s.takenAt < :cutoff")
    int deleteTakenBefore(@Param("cutoff") Instant cutoff);
}
