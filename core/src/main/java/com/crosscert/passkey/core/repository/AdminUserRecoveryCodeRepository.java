package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUserRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AdminUserRecoveryCodeRepository extends JpaRepository<AdminUserRecoveryCode, UUID> {

    List<AdminUserRecoveryCode> findByAdminUserIdAndUsedAtIsNull(UUID adminUserId);

    @Modifying
    @Transactional
    void deleteByAdminUserId(UUID adminUserId);

    long countByAdminUserIdAndUsedAtIsNull(UUID adminUserId);

    /**
     * 미사용 매칭 코드 1개를 원자적으로 used 마킹한다. {@code usedAt is null} 술어가
     * 동시 요청 시 두 번째 writer 를 0행으로 만들어 double-spend 를 DB 레벨에서 차단한다.
     *
     * @return 업데이트된 행 수(매칭 미사용 코드 소비 성공이면 1, 아니면 0)
     */
    @Modifying
    @Query("update AdminUserRecoveryCode r set r.usedAt = :now "
            + "where r.adminUserId = :uid and r.codeHash = :hash and r.usedAt is null")
    int markUsed(@Param("uid") UUID uid, @Param("hash") String hash, @Param("now") Instant now);
}
