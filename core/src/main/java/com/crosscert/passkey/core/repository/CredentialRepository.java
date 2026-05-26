package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.Credential;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository extends JpaRepository<Credential, UUID> {

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
}
