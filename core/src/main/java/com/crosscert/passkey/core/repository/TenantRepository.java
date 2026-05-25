package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, String> {
}
