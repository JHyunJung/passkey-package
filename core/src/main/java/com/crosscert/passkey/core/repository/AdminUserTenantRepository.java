package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.entity.AdminUserTenantId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface AdminUserTenantRepository
        extends JpaRepository<AdminUserTenant, AdminUserTenantId> {

    @Query("select m.tenantId from AdminUserTenant m where m.adminUserId = :adminUserId")
    List<UUID> findTenantIdsByAdminUserId(@Param("adminUserId") UUID adminUserId);

    @Query("select m.adminUserId from AdminUserTenant m where m.tenantId = :tenantId")
    List<UUID> findAdminUserIdsByTenantId(@Param("tenantId") UUID tenantId);

    boolean existsByAdminUserIdAndTenantId(UUID adminUserId, UUID tenantId);

    long countByAdminUserId(UUID adminUserId);

    @Transactional
    long deleteByAdminUserIdAndTenantId(UUID adminUserId, UUID tenantId);
}
