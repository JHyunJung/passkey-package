package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminPasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminPasswordResetTokenRepository extends JpaRepository<AdminPasswordResetToken, Long> {

    Optional<AdminPasswordResetToken> findByTokenHash(String tokenHash);
}
