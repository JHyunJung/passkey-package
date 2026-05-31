package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /**
     * Look up an API key by its prefix. VPD filters by tenant_id =
     * SYS_CONTEXT, so this call only returns a row when the calling
     * session has already set TenantContextHolder to the same tenant
     * that owns the key. Useful from admin endpoints (Phase 2) and
     * test fixtures (where APP_ADMIN bypasses VPD).
     *
     * <p>Phase 1 auth filter does NOT use this — auth runs before
     * tenant context exists. See {@code ApiKeyLookupService} (T16)
     * which calls a definer-rights PL/SQL package
     * ({@code APP_OWNER.api_key_lookup_pkg.find_by_prefix}) that
     * bypasses VPD safely.
     */
    Optional<ApiKey> findByKeyPrefix(String keyPrefix);

    /**
     * Phase F2 — TenantAdminService.toView() 의 KPI 집계용.
     * ApiKey 엔티티는 별도의 STATUS 컬럼을 두지 않고 revokedAt + expiresAt 로
     * "active" 여부를 파생한다 ({@link ApiKey#isActive(Instant)} 참고). 따라서
     * "활성 API 키 수" 카운트는 revokedAt IS NULL AND (expiresAt IS NULL OR
     * expiresAt > :now) 로 표현한다. 동일한 의미가 Service 레이어에서도 사용된다.
     */
    @Query("""
            select count(k) from ApiKey k
            where k.tenantId = :tenantId
              and k.revokedAt is null
              and (k.expiresAt is null or k.expiresAt > :now)
            """)
    long countActiveByTenantId(@Param("tenantId") UUID tenantId, @Param("now") Instant now);

    /**
     * Phase QW-3 — TenantAdminService.list() N+1 제거용 배치 집계.
     * countActiveByTenantId(tenantId, now) 와 **동일한 active 판정**을 tenant 전체에
     * 대해 한 번에 계산한다. where 절은 countActiveByTenantId 와 바이트 단위로 같아야
     * 한다(revokedAt IS NULL AND (expiresAt IS NULL OR expiresAt > :now)). 결과는
     * Object[]{UUID tenantId, Long activeCount}. active key 가 없는 tenant 는 결과
     * 행이 없으므로 호출부에서 0 으로 처리(countActiveByTenantId 가 0 반환과 동일).
     */
    @Query("""
            select k.tenantId, count(k) from ApiKey k
            where k.revokedAt is null
              and (k.expiresAt is null or k.expiresAt > :now)
            group by k.tenantId
            """)
    java.util.List<Object[]> countActiveGroupedByTenantId(@Param("now") Instant now);

    /** active = 미revoke AND 미만료. suspend 일괄 revoke·KPI 와 동일 정의. */
    @Query("""
            select k from ApiKey k
            where k.tenantId = :tenantId
              and k.revokedAt is null
              and (k.expiresAt is null or k.expiresAt > :now)
            """)
    java.util.List<com.crosscert.passkey.core.entity.ApiKey> findActiveByTenantId(
            @Param("tenantId") UUID tenantId, @Param("now") java.time.Instant now);
}
