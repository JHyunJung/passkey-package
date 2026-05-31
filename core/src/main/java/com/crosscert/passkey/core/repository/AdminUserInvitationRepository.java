package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUserInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserInvitationRepository extends JpaRepository<AdminUserInvitation, Long> {
    Optional<AdminUserInvitation> findByTokenHash(String tokenHash);
    List<AdminUserInvitation> findByAdminUserIdAndAcceptedAtIsNull(UUID adminUserId);

    /**
     * P1-4 retention: 완료(수락) 또는 만료된 invitation 중 그 시점이 cutoff 이전인 것 삭제.
     * pending(미수락·미만료)은 쿼리 조건상 절대 매칭 안 됨(활성 보존).
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("delete from AdminUserInvitation i where "
         + "(i.acceptedAt is not null and i.acceptedAt < :cutoff) "
         + "or (i.acceptedAt is null and i.expiresAt < :cutoff)")
    int deleteConsumedOrExpiredBefore(@Param("cutoff") Instant cutoff);
}
