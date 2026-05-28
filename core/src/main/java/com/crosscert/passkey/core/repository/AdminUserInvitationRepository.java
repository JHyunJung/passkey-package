package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUserInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserInvitationRepository extends JpaRepository<AdminUserInvitation, Long> {
    Optional<AdminUserInvitation> findByTokenHash(String tokenHash);
    List<AdminUserInvitation> findByAdminUserIdAndAcceptedAtIsNull(UUID adminUserId);
}
