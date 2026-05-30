package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUserRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserRecoveryCodeRepository extends JpaRepository<AdminUserRecoveryCode, UUID> {

    List<AdminUserRecoveryCode> findByAdminUserIdAndUsedAtIsNull(UUID adminUserId);

    Optional<AdminUserRecoveryCode> findByAdminUserIdAndCodeHashAndUsedAtIsNull(UUID adminUserId, String codeHash);

    @Modifying
    @Transactional
    void deleteByAdminUserId(UUID adminUserId);

    long countByAdminUserIdAndUsedAtIsNull(UUID adminUserId);
}
