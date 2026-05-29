package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.Credential;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository extends JpaRepository<Credential, UUID> {

    /**
     * Phase F2 — TenantAdminService.toView() 의 KPI 집계용.
     * Spring Data derived query: SELECT COUNT(*) FROM credential WHERE tenant_id = :tenantId.
     * VPD 가 활성화된 세션에서는 자동으로 tenant 격리되지만, 명시적으로 tenantId 를
     * 받아 PLATFORM_OPERATOR (cross-tenant listing) 케이스에도 동작하도록 한다.
     */
    long countByTenantId(UUID tenantId);

    /**
     * Pessimistic-locked lookup used by /authentication/finish to
     * serialize the read-check-update of signCount. Without the lock,
     * two concurrent finish calls for the same credential could both
     * observe the same stored counter and both pass the strict-monotonic
     * check (codex P1: TOCTOU counter race).
     *
     * <p>VPD filters credentials to the calling tenant, so a credential
     * ID match across tenants returns empty.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Credential c where c.credentialId = :credentialId")
    Optional<Credential> findByCredentialIdForUpdate(@Param("credentialId") byte[] credentialId);

    /**
     * tenant 의 모든 credential — lastUsedAt DESC NULLS LAST, id DESC 정렬.
     * Criteria API 는 NullPrecedence 미지원이므로 JPQL @Query 로 명시.
     * admin-app 의 CredentialAdminService.list() 가 사용.
     */
    @Query(value = "select c from Credential c where c.tenantId = :tenantId order by c.lastUsedAt desc nulls last, c.id desc",
           countQuery = "select count(c) from Credential c where c.tenantId = :tenantId")
    Page<Credential> findAllByTenantId(@Param("tenantId") UUID tenantId, Pageable p);

    /**
     * tenant 안에서 credential_id 또는 user_handle 의 hex 표현이 q 를 substring 으로 포함하는 행.
     * q 는 서비스 측 normalizeQ 가 이미 lowercase + wildcard escape (\\ % _) 한 상태로 들어와야 한다.
     * ESCAPE '\\' 절이 백슬래시 escape 를 인식.
     */
    @Query(value = """
            SELECT * FROM credential
            WHERE tenant_id = :tid
              AND (
                LOWER(RAWTOHEX(credential_id)) LIKE LOWER('%' || :q || '%') ESCAPE '\\'
                OR LOWER(RAWTOHEX(user_handle)) LIKE LOWER('%' || :q || '%') ESCAPE '\\'
              )
            """, nativeQuery = true,
        countQuery = """
            SELECT COUNT(*) FROM credential
            WHERE tenant_id = :tid
              AND (
                LOWER(RAWTOHEX(credential_id)) LIKE LOWER('%' || :q || '%') ESCAPE '\\'
                OR LOWER(RAWTOHEX(user_handle)) LIKE LOWER('%' || :q || '%') ESCAPE '\\'
              )
            """)
    Page<Credential> searchByTenantId(@Param("tid") UUID tid,
                                       @Param("q") String hexQ,
                                       Pageable p);

    /**
     * P0-3: registration/start 가 excludeCredentials 를 채우기 위해 사용.
     * 같은 userHandle 의 기존 credentialId 들을 반환 → 동일 authenticator 중복 등록 방지.
     * VPD 가 tenant 로 필터하므로 tenant 격리는 세션 컨텍스트가 담당.
     */
    @Query("select c.credentialId from Credential c where c.userHandle = :userHandle")
    java.util.List<byte[]> findCredentialIdsByUserHandle(@Param("userHandle") byte[] userHandle);
}
