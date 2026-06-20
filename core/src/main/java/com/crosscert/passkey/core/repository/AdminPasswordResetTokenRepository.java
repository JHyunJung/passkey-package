package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminPasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface AdminPasswordResetTokenRepository extends JpaRepository<AdminPasswordResetToken, Long> {

    Optional<AdminPasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * P1-4 retention: 소비 또는 만료된 reset 토큰 중 그 시점이 cutoff 이전인 것 삭제.
     * 미소비·미만료 토큰은 보존.
     *
     * <p>Batched: ROWNUM 캡(AdminUserInvitationRepository 참고). nativeQuery + 실제 컬럼명.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM {h-schema}admin_password_reset_token WHERE id IN ("
         + "SELECT id FROM {h-schema}admin_password_reset_token WHERE "
         + "((consumed_at IS NOT NULL AND consumed_at < :cutoff) "
         + "OR (consumed_at IS NULL AND expires_at < :cutoff)) "
         + "AND ROWNUM <= :batchSize)", nativeQuery = true)
    int deleteConsumedOrExpiredBefore(@Param("cutoff") OffsetDateTime cutoff, @Param("batchSize") int batchSize);
}
