package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.SecurityIncident;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SecurityIncidentRepository extends JpaRepository<SecurityIncident, UUID> {
    List<SecurityIncident> findAllByOrderByCreatedAtDesc();
    boolean existsByTenantIdAndStatus(UUID tenantId, String status);
    Optional<SecurityIncident> findByIdAndStatus(UUID id, String status);
}
