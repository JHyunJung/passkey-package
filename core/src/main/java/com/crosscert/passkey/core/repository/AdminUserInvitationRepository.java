package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUserInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserInvitationRepository extends JpaRepository<AdminUserInvitation, Long> {
    Optional<AdminUserInvitation> findByTokenHash(String tokenHash);
    List<AdminUserInvitation> findByAdminUserIdAndAcceptedAtIsNull(UUID adminUserId);

    /**
     * G09 fix — conditional atomic UPDATE, marking this invitation accepted
     * only if it is still pending ({@code accepted_at IS NULL}). Two
     * concurrent accept(token, ...) calls for the same token now serialize at
     * the DB level: only the first UPDATE affects a row (returns 1); the
     * second sees {@code accepted_at IS NOT NULL} and affects 0 rows, so the
     * caller can reject it as "already used" instead of both succeeding
     * (double-accept / last-writer-wins password). Same pattern as
     * {@code AdminUserRecoveryCodeRepository.markUsed} and
     * {@code SecurityIncidentRepository.resolveIfOpen}.
     *
     * @return 1 if this call won the race (invitation was pending), 0 if it
     *         was already accepted by a concurrent/earlier call.
     */
    @Modifying(clearAutomatically = true)
    @Query("update AdminUserInvitation i set i.acceptedAt = :now "
            + "where i.id = :id and i.acceptedAt is null")
    int acceptIfPending(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /**
     * P1-4 retention: 완료(수락) 또는 만료된 invitation 중 그 시점이 cutoff 이전인 것 삭제.
     * pending(미수락·미만료)은 쿼리 조건상 절대 매칭 안 됨(활성 보존).
     *
     * <p>Batched: ROWNUM 으로 한 트랜잭션의 삭제 행 수를 batchSize 로 캡한다(첫 prod
     * 실행 시 수개월 누적분에 대한 unbounded DELETE 로 긴 row-lock·undo 가 생기는 것을
     * 방지). JPQL 은 ROWNUM 미지원이라 nativeQuery + 실제 컬럼명으로 작성. 호출측
     * (RetentionPurgeService) 이 0 행 될 때까지 반복한다.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM {h-schema}admin_user_invitation WHERE id IN ("
         + "SELECT id FROM {h-schema}admin_user_invitation WHERE "
         + "((accepted_at IS NOT NULL AND accepted_at < :cutoff) "
         + "OR (accepted_at IS NULL AND expires_at < :cutoff)) "
         + "AND ROWNUM <= :batchSize)", nativeQuery = true)
    int deleteConsumedOrExpiredBefore(@Param("cutoff") OffsetDateTime cutoff, @Param("batchSize") int batchSize);
}
