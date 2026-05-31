package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminPasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface AdminPasswordResetTokenRepository extends JpaRepository<AdminPasswordResetToken, Long> {

    Optional<AdminPasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * P1-4 retention: 소비 또는 만료된 reset 토큰 중 그 시점이 cutoff 이전인 것 삭제.
     * 미소비·미만료 토큰은 보존.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("delete from AdminPasswordResetToken t where "
         + "(t.consumedAt is not null and t.consumedAt < :cutoff) "
         + "or (t.consumedAt is null and t.expiresAt < :cutoff)")
    int deleteConsumedOrExpiredBefore(@Param("cutoff") Instant cutoff);
}
