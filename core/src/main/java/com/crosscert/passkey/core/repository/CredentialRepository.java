package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.Credential;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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
     * Phase QW-3 — TenantAdminService.list() N+1 제거용 배치 집계.
     * countByTenantId(UUID) 와 동일한 의미를 tenant 전체에 대해 한 번에 계산한다:
     * 각 tenant_id 별 credential row 수. 결과는 Object[]{UUID tenantId, Long count}.
     * credential row 가 없는 tenant 는 결과에 포함되지 않으므로(0 행) 호출부에서
     * 기본값 0 으로 처리한다 — countByTenantId 가 0 을 반환하는 것과 동일.
     */
    @Query("select c.tenantId, count(c) from Credential c group by c.tenantId")
    List<Object[]> countGroupedByTenantId();

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
     * Lock-free read-only lookup by WebAuthn credentialId. Mirrors
     * {@link #findByCredentialIdForUpdate(byte[])} but WITHOUT the pessimistic
     * lock — admin read APIs (e.g. credential 인증 이벤트 조회) must not take a
     * row lock on a read path. VPD/app-level tenant boundary still applies.
     */
    @Query("select c from Credential c where c.credentialId = :credentialId")
    Optional<Credential> findByCredentialId(@Param("credentialId") byte[] credentialId);

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
    List<byte[]> findCredentialIdsByUserHandle(@Param("userHandle") byte[] userHandle);

    /** P0-4: self-service — 특정 userHandle 의 모든 credential (VPD 가 tenant 격리). */
    List<Credential> findByUserHandle(byte[] userHandle);

    /** P0-4: self-service 삭제/수정 — credentialId + userHandle 동시 일치 (소유권 확인). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Credential c where c.credentialId = :credentialId and c.userHandle = :userHandle")
    Optional<Credential> findOwnedForUpdate(@Param("credentialId") byte[] credentialId,
                                            @Param("userHandle") byte[] userHandle);
}
