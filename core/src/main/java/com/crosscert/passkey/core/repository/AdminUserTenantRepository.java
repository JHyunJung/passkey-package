package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.entity.AdminUserTenantId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * G08 fix — locks every mapping row for this admin (SELECT ... FOR UPDATE)
     * before the caller counts them. Two concurrent removeTenant calls for
     * different tenants of the SAME admin now serialize: the second
     * transaction's FOR UPDATE blocks until the first commits its DELETE, so
     * it observes the post-delete row set instead of a stale pre-delete
     * snapshot. Without this, count-then-delete is a classic write-skew (two
     * disjoint DELETEs, no row-level conflict, both guards pass).
     *
     * <p>Returns rows (not a COUNT) because Oracle rejects {@code SELECT
     * count(*) ... FOR UPDATE} (ORA-01786) — aggregates have no single row to
     * lock. The caller uses {@code .size()} instead.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from AdminUserTenant m where m.adminUserId = :adminUserId")
    List<AdminUserTenant> findByAdminUserIdForUpdate(@Param("adminUserId") UUID adminUserId);

    @Transactional
    long deleteByAdminUserIdAndTenantId(UUID adminUserId, UUID tenantId);
}
