package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.SecurityPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityPolicyRepository extends JpaRepository<SecurityPolicy, Long> {
    // Single-row table: always use findById(1L).
}
