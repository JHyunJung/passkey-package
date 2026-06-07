package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.CredentialAuthEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * credential 단위 인증 이벤트 조회 + retention 삭제.
 * deleteCreatedBefore 는 CeremonyEventRepository 와 동일한 batched native 패턴.
 */
public interface CredentialAuthEventRepository extends JpaRepository<CredentialAuthEvent, UUID> {

    /** credential 상세 "인증 기록" 페이지 조회 — 최신순. */
    Page<CredentialAuthEvent> findByCredentialIdOrderByCreatedAtDesc(UUID credentialId, Pageable pageable);

    /** retention: created_at 이 cutoff 이전인 이벤트 batched 삭제. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM {h-schema}credential_auth_event WHERE id IN ("
         + "SELECT id FROM {h-schema}credential_auth_event WHERE "
         + "created_at < :cutoff "
         + "AND ROWNUM <= :batchSize)", nativeQuery = true)
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
