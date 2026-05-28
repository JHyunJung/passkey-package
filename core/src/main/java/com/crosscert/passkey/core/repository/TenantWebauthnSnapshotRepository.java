package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.TenantWebauthnSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TenantWebauthnSnapshotRepository extends JpaRepository<TenantWebauthnSnapshot, Long> {

    List<TenantWebauthnSnapshot> findByTenantIdOrderByTakenAtDesc(UUID tenantId);
}
