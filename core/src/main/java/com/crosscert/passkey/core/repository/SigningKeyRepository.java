package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.SigningKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SigningKeyRepository extends JpaRepository<SigningKey, UUID> {

    /**
     * Returns the single ACTIVE key. There should be at most one at any
     * time — KeyRotationService inserts the new ACTIVE after
     * transitioning the old one to ROTATED inside the same
     * @Transactional.
     */
    Optional<SigningKey> findFirstByStatusOrderByCreatedAtDesc(String status);

    /**
     * Used by JwksAssembler. Returns ACTIVE + ROTATED rows so RPs
     * holding JWTs signed by the now-ROTATED key can still verify
     * during the grace period. REVOKED is excluded.
     */
    List<SigningKey> findAllByStatusIn(List<String> statuses);

    /**
     * Used by KeyExpirationJob. Finds ROTATED rows whose grace period
     * has elapsed, ready to transition to REVOKED.
     */
    List<SigningKey> findAllByStatusAndRotatedAtBefore(String status, OffsetDateTime cutoff);
}
