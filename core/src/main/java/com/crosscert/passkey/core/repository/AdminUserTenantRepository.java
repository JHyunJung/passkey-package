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

    /**
     * G17 batch fix — AdminUserService.list()의 toView() N+1 제거용.
     * findAll()로 얻은 adminUserId 집합 전체의 매핑을 한 번의 IN 쿼리로 로드해
     * 호출측에서 Map&lt;UUID,List&lt;UUID&gt;&gt;로 조립한다 (F09 activity feed의
     * findAllById 배치 패턴과 동일 계열).
     */
    @Query("select m from AdminUserTenant m where m.adminUserId in :adminUserIds")
    List<AdminUserTenant> findByAdminUserIdIn(@Param("adminUserIds") java.util.Collection<UUID> adminUserIds);

    @Query("select m.adminUserId from AdminUserTenant m where m.tenantId = :tenantId")
    List<UUID> findAdminUserIdsByTenantId(@Param("tenantId") UUID tenantId);

    boolean existsByAdminUserIdAndTenantId(UUID adminUserId, UUID tenantId);

    long countByAdminUserId(UUID adminUserId);

    @Transactional
    long deleteByAdminUserIdAndTenantId(UUID adminUserId, UUID tenantId);
}
