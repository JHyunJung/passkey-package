package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantAaguidPolicyRepository extends JpaRepository<TenantAaguidPolicy, UUID> {
}
